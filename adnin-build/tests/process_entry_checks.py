"""PE-entry truth table using authored leaf code and a fake CRT entry only."""
import ctypes
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import pefile
import header_checks
import native_process_entry
import replay_denick_checks


def verify(test,before,final,report,profile):
    old,new=pefile.PE(data=before),pefile.PE(data=final)
    change=report['processTerminationGuard']
    test.assertEqual(change,native_process_entry.reviewed_patch(old,profile,report['section']['rva'],report['metadata']))
    test.assertEqual(new.OPTIONAL_HEADER.AddressOfEntryPoint,change['bridgeEntryRva'])
    test.assertEqual(new.get_data(change['originalEntryRva'],len(native_process_entry.ENTRY_BYTES)),native_process_entry.ENTRY_BYTES)
    for index in (0,1,5,9,12):
        test.assertEqual(new.OPTIONAL_HEADER.DATA_DIRECTORY[index].__pack__(),old.OPTIONAL_HEADER.DATA_DIRECTORY[index].__pack__())
    for offset in range(len(native_process_entry.ENTRY_BYTES)):
        raw=bytearray(before);raw[old.get_offset_from_rva(change['originalEntryRva']+offset)]^=1
        with test.assertRaisesRegex(ValueError,'CRT entry'):
            native_process_entry.reviewed_patch(pefile.PE(data=bytes(raw)),profile,report['section']['rva'],report['metadata'])
    old.OPTIONAL_HEADER.AddressOfEntryPoint+=1
    with test.assertRaisesRegex(ValueError,'CRT entry'):
        native_process_entry.reviewed_patch(old,profile,report['section']['rva'],report['metadata'])


def execute(test,final,report,profile,nasm):
    if os.name!='nt' or ctypes.sizeof(ctypes.c_void_p)!=8:test.skipTest('Windows x64 authored entry mock')
    P=ctypes.c_void_p;k=ctypes.WinDLL('kernel32',use_last_error=True)
    k.VirtualAlloc.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32];k.VirtualAlloc.restype=P
    k.VirtualFree.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32];k.FlushInstructionCache.argtypes=[P,P,ctypes.c_size_t]
    base=k.VirtualAlloc(None,report['imageSize'],0x3000,0x40);test.assertTrue(base)
    section=report['section'];ctypes.memmove(base+section['rva'],final[section['offset']:section['offset']+section['size']],section['size'])
    called=[]
    def original(module,reason,reserved):called.append((module,reason,reserved));return 0x171717
    cb=ctypes.WINFUNCTYPE(ctypes.c_uint32,P,ctypes.c_uint32,P)(original)
    stub=b'\x48\xb8'+struct.pack('<Q',ctypes.cast(cb,P).value)+b'\xff\xe0'
    ctypes.memmove(base+native_process_entry.ENTRIES[profile],stub,len(stub))
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-entry-abi-') as directory:
            source,binary=Path(directory)/'caller.asm',Path(directory)/'caller.bin'
            source.write_text('%define TARGET '+hex(base+report['processTerminationGuard']['bridgeEntryRva'])+'\n'+header_checks.CALLER,encoding='ascii')
            built=subprocess.run([str(nasm),'-f','bin','-Ox',str(source),'-o',str(binary)],capture_output=True,text=True)
            test.assertEqual(built.returncode,0,built.stdout+built.stderr)
            data=binary.read_bytes();ctypes.memmove(base+0x1000,data,len(data))
        k.FlushInstructionCache(P(-1),base,report['imageSize'])
        run=ctypes.WINFUNCTYPE(ctypes.c_uint32,P,ctypes.c_uint32,P,P,P)(base+0x1000)
        for reason in (0,1,2,3,4,0xffffffff):
            for reserved in (0,1,0x1234567812345678):
                called.clear();snapshot=ctypes.create_string_buffer(64)
                result=run(0x77770000,reason,reserved,0x4000,snapshot)
                skip=reason==0 and reserved!=0
                test.assertEqual(result,1 if skip else 0x171717)
                test.assertEqual(called,[] if skip else [(0x77770000,reason,reserved or None)])
                test.assertEqual(struct.unpack_from('<7Q',snapshot,8),
                    (0x3333333333333333,0x5555555555555555,0x6666666666666666,reserved,0x4000,
                     0xdddddddddddddddd,0xeeeeeeeeeeeeeeee))
        replay_denick_checks.check_simple_unwind(test,base,report)
    finally:k.VirtualFree(base,0,0x8000)
