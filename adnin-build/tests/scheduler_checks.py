"""Execute only authored scheduler cleanup against fake JNI on an attached thread."""
import ctypes
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import pefile
import header_checks
import native_scheduler
import replay_denick_checks


def verify(test, before, final, report):
    old,new = pefile.PE(data=before),pefile.PE(data=final)
    entry = report['schedulerLocalReference']
    test.assertEqual(entry,native_scheduler.reviewed_hook(old,report['section']['rva'],report['metadata']))
    for at,data in ((native_scheduler.SITE-len(native_scheduler.PREFIX),native_scheduler.PREFIX),
                    (native_scheduler.SITE,native_scheduler.CALL),
                    (native_scheduler.SITE+5,native_scheduler.SUFFIX),
                    (native_scheduler.TARGET,native_scheduler.THUNK)):
        for offset in range(len(data)):
            changed=bytearray(before); changed[old.get_offset_from_rva(at+offset)]^=1
            with test.assertRaisesRegex(ValueError,'scheduler'):
                native_scheduler.reviewed_hook(pefile.PE(data=bytes(changed)),report['section']['rva'],report['metadata'])
    site=entry['callRva']
    test.assertEqual(new.get_data(site,5),b'\xe8'+struct.pack('<i',entry['bridgeTargetRva']-site-5))
    test.assertEqual(new.get_data(site+5,len(native_scheduler.SUFFIX)),native_scheduler.SUFFIX)


def execute(test, final, report, nasm):
    if os.name!='nt' or ctypes.sizeof(ctypes.c_void_p)!=8:test.skipTest('Windows x64 private JNI mock')
    P=ctypes.c_void_p
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.VirtualAlloc.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32];kernel.VirtualAlloc.restype=P
    kernel.VirtualFree.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32]
    kernel.FlushInstructionCache.argtypes=[P,P,ctypes.c_size_t]
    base=kernel.VirtualAlloc(None,report['imageSize'],0x3000,0x40);test.assertTrue(base)
    section,meta=report['section'],report['metadata']
    ctypes.memmove(base+section['rva'],final[section['offset']:section['offset']+section['size']],section['size'])
    table=(P*233)();env=(P*1)(ctypes.addressof(table));env_ptr=ctypes.addressof(env)
    refs=set();errors=[];state=dict(sequence=0,returns=True,pending=False,peak=0,deleted=0)
    def schedule(actual_env,receiver,method,task):
        try:
            test.assertEqual((actual_env,receiver,method,task),(env_ptr,0x2000,0x3000,0x4000))
            if not state['returns']:return None
            state['sequence']+=1;handle=0x10000+state['sequence'];refs.add(handle)
            state['peak']=max(state['peak'],len(refs));return handle
        except Exception as error:errors.append(repr(error));return None
    def delete(actual_env,ref):
        try:
            test.assertEqual(actual_env,env_ptr);test.assertIn(ref,refs)
            refs.remove(ref);state['deleted']+=1
        except Exception as error:errors.append(repr(error))
    callback=ctypes.WINFUNCTYPE(P,P,P,P,P)(schedule)
    cleanup=ctypes.WINFUNCTYPE(None,P,P)(delete)
    table[0xb8//8]=ctypes.cast(cleanup,P).value
    stub=b'\x48\xb8'+struct.pack('<Q',ctypes.cast(callback,P).value)+b'\xff\xe0'
    ctypes.memmove(base+native_scheduler.TARGET,stub,len(stub))
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-scheduler-abi-') as directory:
            source,binary=Path(directory)/'caller.asm',Path(directory)/'caller.bin'
            source.write_text('%define TARGET '+hex(base+section['rva']+meta['lunarSchedule'])+'\n'+header_checks.CALLER,encoding='ascii')
            compiled=subprocess.run([str(nasm),'-f','bin','-Ox',str(source),'-o',str(binary)],capture_output=True,text=True)
            test.assertEqual(compiled.returncode,0,compiled.stdout+compiled.stderr)
            data=binary.read_bytes();ctypes.memmove(base+0x1000,data,len(data))
        kernel.FlushInstructionCache(P(-1),base,report['imageSize'])
        checked=ctypes.WINFUNCTYPE(P,P,P,P,P,P)(base+0x1000)
        direct=ctypes.WINFUNCTYPE(P,P,P,P,P)(base+section['rva']+meta['lunarSchedule'])
        for returns in (False,True):
            for pending in (False,True):
                state.update(returns=returns,pending=pending)
                snapshot=ctypes.create_string_buffer(64)
                result=checked(env_ptr,0x2000,0x3000,0x4000,snapshot)
                test.assertEqual(bool(result),returns);test.assertEqual(state['pending'],pending)
                test.assertEqual(struct.unpack_from('<7Q',snapshot,8),
                    (0x3333333333333333,0x5555555555555555,0x6666666666666666,
                     0x3000,0x4000,0xdddddddddddddddd,0xeeeeeeeeeeeeeeee))
                test.assertFalse(refs);test.assertFalse(errors)
        state.update(returns=True,pending=False)
        # The baseline original call discards each result without DeleteLocalRef.
        for _ in range(1000):callback(env_ptr,0x2000,0x3000,0x4000)
        test.assertEqual(len(refs),1000)
        refs.clear();state.update(peak=0,deleted=0)
        # No native return-to-Java local frame or detach occurs in this fixture.
        for _ in range(100000):direct(env_ptr,0x2000,0x3000,0x4000)
        test.assertFalse(errors);test.assertFalse(refs)
        test.assertEqual((state['peak'],state['deleted']),(1,100000))
        replay_denick_checks.check_simple_unwind(test,base,report)
    finally:kernel.VirtualFree(base,0,0x8000)
