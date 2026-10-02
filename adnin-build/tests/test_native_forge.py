"""Read-only validation of the final Forge image and every lookup transfer."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import unittest

import pefile
from capstone import Cs, CS_ARCH_X86, CS_MODE_64

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
import native_forge
ARGS=None
class ForgeImageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data=(ARGS.build/'bin/AdninForge.dll').read_bytes()
        cls.image=pefile.PE(data=cls.data)
        cls.report=json.loads((ARGS.build/'forge-bridge.json').read_text(encoding='utf8'))
        cls.facade=cls.report['forgeJni']

    def test_code_is_not_writable_and_state_is_not_executable(self):
        for section in self.image.sections:
            self.assertFalse(section.Characteristics&0x20000000 and section.Characteristics&0x80000000)
        state=self.image.get_section_by_rva(self.facade['stateRva'])
        self.assertTrue(state.Characteristics&0x80000000)
        self.assertFalse(state.Characteristics&0x20000000)
        self.assertEqual(self.image.get_data(self.facade['stateRva'],32),bytes(32))

    def test_reserved_code_extent_reaches_state_section(self):
        code=self.image.get_section_by_rva(self.facade['facadeCodeRva'])
        self.assertEqual(code.VirtualAddress+code.Misc_VirtualSize,self.facade['stateRva'])

    @unittest.skipUnless(os.name == 'nt' and ctypes.sizeof(ctypes.c_void_p) == 8,
                         'Requires Windows x64 loader API')
    def test_windows_loader_accepts_complete_image_without_initializing_it(self):
        kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        load=kernel.LoadLibraryExW
        load.argtypes=[ctypes.c_wchar_p,ctypes.c_void_p,ctypes.c_uint32]
        load.restype=ctypes.c_void_p
        free=kernel.FreeLibrary
        free.argtypes=[ctypes.c_void_p]
        free.restype=ctypes.c_int
        ctypes.set_last_error(0)
        # DONT_RESOLVE_DLL_REFERENCES validates the image without executing DllMain.
        module=load(str((ARGS.build/'bin/AdninForge.dll').resolve()),None,1)
        self.assertTrue(module,'Windows loader rejected Forge image: '+str(ctypes.get_last_error()))
        try:
            self.assertEqual(ctypes.string_at(module,2),b'MZ')
        finally:
            self.assertTrue(free(module),'FreeLibrary failed: '+str(ctypes.get_last_error()))

    def test_all_lookup_transfers_target_the_owned_facade(self):
        self.assertEqual(self.facade['lookupCounts'],{'find':106,'method':302,'static_method':47,'field':173,'static_field':110})
        self.assertTrue(self.facade['jvmTableUnchanged'])
        self.assertTrue(self.facade['gameClassesUnchanged'])
        self.assertTrue(self.facade['otherNativeModulesUnchanged'])
        spans=[]
        for item in self.facade['patches']:
            before,after=bytes.fromhex(item['before']),bytes.fromhex(item['after'])
            self.assertEqual(len(before),len(after))
            self.assertEqual(self.image.get_data(item['rva'],len(after)),after)
            self.assertIn(after[0],(0xe8,0xe9))
            target=item['rva']+5+struct.unpack_from('<i',after,1)[0]
            self.assertGreaterEqual(target,self.facade['facadeCodeRva'])
            self.assertLess(target,self.facade['stateRva'])
            self.assertEqual(after[5:],b'\x90'*(len(after)-5))
            spans.append((item['rva'],item['rva']+len(after)))
        self.assertTrue(all(a[1]<=b[0] for a,b in zip(spans,spans[1:])))

    def test_java_vm_get_env_calls_and_arguments_are_preserved(self):
        patches=self.facade['patches']
        self.assertEqual(self.facade['preservedJavaVmGetEnvCalls'],sorted(native_forge.JAVA_VM_GET_ENV))
        for call,(start,expected) in native_forge.JAVA_VM_GET_ENV.items():
            raw=bytes.fromhex(expected)
            self.assertEqual(self.image.get_data(start,len(raw)),raw)
            self.assertFalse(any(p['rva']<call+3 and p['rva']+len(bytes.fromhex(p['after']))>call
                                 for p in patches))

    @unittest.skipUnless(os.name == 'nt' and ctypes.sizeof(ctypes.c_void_p) == 8,
                         'Requires Windows x64 native execution')
    def test_final_get_env_transfers_invoke_vm_table_with_original_abi(self):
        kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        kernel.VirtualAlloc.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32]
        kernel.VirtualAlloc.restype=ctypes.c_void_p
        kernel.VirtualProtect.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_uint32,ctypes.POINTER(ctypes.c_uint32)]
        kernel.VirtualFree.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_uint32]
        function=ctypes.WINFUNCTYPE(ctypes.c_int,ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p),ctypes.c_int)
        observations=[]
        def get_env(vm,output,version):
            observations.append((vm,version))
            output[0]=0x12345678
            return -2
        callback=function(get_env)
        table=(ctypes.c_void_p*8)()
        table[6]=ctypes.cast(callback,ctypes.c_void_p).value
        vm=ctypes.c_void_p(ctypes.addressof(table))
        for call in native_forge.JAVA_VM_GET_ENV:
            # Execute the final image's table load and transfer with x64 shadow space.
            code=bytes.fromhex('4883ec28')+self.image.get_data(call-3,6)+bytes.fromhex('4883c428c3')
            memory=kernel.VirtualAlloc(None,4096,0x3000,4)
            self.assertTrue(memory)
            try:
                ctypes.memmove(memory,code,len(code))
                old=ctypes.c_uint32()
                self.assertTrue(kernel.VirtualProtect(memory,4096,0x20,ctypes.byref(old)))
                output=ctypes.c_void_p()
                result=function(memory)(ctypes.byref(vm),ctypes.byref(output),0x10006)
                self.assertEqual(result,-2)
                self.assertEqual(output.value,0x12345678)
                self.assertEqual(observations[-1],(ctypes.addressof(vm),0x10006))
            finally:
                self.assertTrue(kernel.VirtualFree(memory,0,0x8000))

    def test_all_unwind_ranges_and_relocations_are_valid(self):
        functions=[(x.struct.BeginAddress,x.struct.EndAddress,x.struct.UnwindData)
                   for x in self.image.DIRECTORY_ENTRY_EXCEPTION]
        self.assertEqual(len(functions),self.facade['unwindRecords'])
        self.assertEqual(functions,sorted(functions))
        self.assertTrue(all(a[1]<=b[0] for a,b in zip(functions,functions[1:])))
        for lo,hi,unwind in functions:
            self.assertLess(lo,hi)
            self.assertIsNotNone(self.image.get_section_by_rva(unwind))
        self.assertGreater(len(self.image.DIRECTORY_ENTRY_BASERELOC),0)
        for block in self.image.DIRECTORY_ENTRY_BASERELOC:
            for item in block.entries:
                if not item.type: continue
                self.assertEqual(item.type,10)
                self.assertIsNotNone(self.image.get_section_by_rva(item.rva))

    @unittest.skipUnless(os.name == 'nt' and ctypes.sizeof(ctypes.c_void_p) == 8,
                         'Requires Windows x64 unwind API')
    def test_moved_calls_preserve_the_original_unwind_state(self):
        original=pefile.PE(data=(ARGS.build/'native-vanilla.dll').read_bytes())
        decoder=Cs(CS_ARCH_X86,CS_MODE_64)
        images=[ctypes.create_string_buffer(p.get_memory_mapped_image()) for p in (original,self.image)]
        unwind=ctypes.WinDLL('ntdll').RtlVirtualUnwind
        unwind.argtypes=[ctypes.c_ulong,ctypes.c_uint64,ctypes.c_uint64,ctypes.c_void_p,
                         ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p]
        unwind.restype=ctypes.c_void_p
        functions=[[x.struct for x in p.DIRECTORY_ENTRY_EXCEPTION] for p in (original,self.image)]
        checked=0
        for patch in self.facade['patches']:
            before=list(decoder.disasm(bytes.fromhex(patch['before']),patch['rva']))
            if len(before)<2 or before[-1].mnemonic != 'call': continue
            target=patch['rva']+5+struct.unpack_from('<i',bytes.fromhex(patch['after']),1)[0]
            owner=next(f for f in functions[0] if f.BeginAddress<=patch['rva']<f.EndAddress)
            thunk=next(f for f in functions[1] if f.BeginAddress==target)
            thunk_code=list(decoder.disasm(self.image.get_data(target,thunk.EndAddress-target),target))
            call=next(i for i in thunk_code if i.mnemonic=='call')
            stack=ctypes.create_string_buffer(65536)
            for at in range(0,len(stack),8): struct.pack_into('<Q',stack,at,0x100000+at)
            stack_pointer=ctypes.addressof(stack)+4096
            states=[]
            for index,(function,pc) in enumerate(((owner,before[-1].address+before[-1].size),
                                                (thunk,call.address+call.size))):
                context=ctypes.create_string_buffer(1248)
                aligned=(ctypes.addressof(context)+15)&~15
                for offset in range(120,256,8): ctypes.c_uint64.from_address(aligned+offset).value=0xabc000+offset
                ctypes.c_uint64.from_address(aligned+152).value=stack_pointer
                ctypes.c_uint64.from_address(aligned+248).value=ctypes.addressof(images[index])+pc
                ctypes.c_uint32.from_address(aligned+48).value=0x10000b
                record=(ctypes.c_uint32*3)(function.BeginAddress,function.EndAddress,function.UnwindData)
                handler,frame=ctypes.c_void_p(),ctypes.c_uint64()
                unwind(0,ctypes.addressof(images[index]),ctypes.addressof(images[index])+pc,
                       record,aligned,ctypes.byref(handler),ctypes.byref(frame),None)
                states.append(ctypes.string_at(aligned+120,136))
            self.assertEqual(states[0],states[1],hex(patch['rva']))
            checked+=1
        self.assertGreaterEqual(checked,100)

    def test_mapper_bytecode_is_present_exactly_once(self):
        mapper=(ARGS.build/'forge-mapper/adnin/forge/RuntimeMappings.class').read_bytes()
        self.assertEqual(hashlib.sha256(mapper).hexdigest(),self.facade['mapperSha256'])
        self.assertEqual(self.data.count(mapper),1)
        self.assertEqual(self.image.OPTIONAL_HEADER.SizeOfImage,self.facade['imageSize'])
        self.assertFalse(self.facade['runtimeGameTested'])

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build',type=Path,required=True)
    ARGS,extra=parser.parse_known_args()
    unittest.main(argv=[__file__,*extra])
