"""Validate only authored polling patches and private mock lock callbacks."""
import ctypes
import os
import struct
import subprocess
import tempfile
from pathlib import Path
import pefile
import bridge
import native_number_poll
import replay_denick_checks


def verify_pe(test,before,final,report,profile):
    old,new=pefile.PE(data=before),pefile.PE(data=final)
    spec=native_number_poll.PROFILES[profile]
    items=report['numberPolling']
    test.assertEqual(items,native_number_poll.reviewed_patches(old,profile,report['section']['rva'],report['metadata']))
    test.assertEqual(len(items),3)
    for item in items:
        call,branch=item['callRva'],item['branchRva']
        test.assertEqual(new.get_data(call,5),bridge.call_bytes(call,item['bridgeTargetRva']))
        test.assertEqual(new.get_data(branch,6),bytes.fromhex(item['after']))
        test.assertEqual(old.get_data(call+5,2),new.get_data(call+5,2))
        test.assertTrue(item['noLockCleanupPreserved'] and item['queuedItemsPreserved'])
        for site in (call,branch,item['busyRva'],spec['mutex']):
            changed=bytearray(before); changed[old.get_offset_from_rva(site)]^=1
            with test.assertRaisesRegex(ValueError,'Number polling'):
                native_number_poll.reviewed_patches(pefile.PE(data=bytes(changed)),profile,report['section']['rva'],report['metadata'])
    if profile=='lunar':
        # The worker's broad lock and HTTP call remain byte-for-byte intact.
        test.assertEqual(old.get_data(0xb81f0,0xba530-0xb81f0),new.get_data(0xb81f0,0xba530-0xb81f0))


CALLER=r'''
bits 64
default rel
    push rbx
    push rsi
    push r15
    sub rsp,0x20
    mov r15,rdx
    mov rbx,0x1234567890abcdef
    mov rsi,0x34567890abcdef12
    sub rsp,8
    call [target]
    add rsp,8
    mov [r15],rbx
    mov [r15+8],rsi
    mov [r15+24],rax
    add rsp,0x20
    pop r15
    pop rsi
    pop rbx
    ret
target: dq TARGET
'''


def execute(test,final,report,profile,nasm):
    if os.name!='nt' or ctypes.sizeof(ctypes.c_void_p)!=8: test.skipTest('Windows x64 private mock execution')
    P,I=ctypes.c_void_p,ctypes.c_int
    k32=ctypes.WinDLL('kernel32',use_last_error=True)
    k32.VirtualAlloc.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32];k32.VirtualAlloc.restype=P
    k32.VirtualFree.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32]
    k32.FlushInstructionCache.argtypes=[P,P,ctypes.c_size_t]
    base=k32.VirtualAlloc(None,report['imageSize'],0x3000,0x40)
    test.assertTrue(base)
    section=report['section'];spec=native_number_poll.PROFILES[profile]
    ctypes.memmove(base+section['rva'],final[section['offset']:section['offset']+section['size']],section['size'])
    keep,events=[],[]
    state=dict(module=True,export=True,status=0,fallbackStatus=0)
    def callback(result,args,fn):
        cb=ctypes.WINFUNCTYPE(result,*args)(fn);keep.append(cb)
        return ctypes.cast(cb,P).value
    def trylock(mutex):
        events.append(('trylock',mutex));return state['status']
    try_address=callback(I,[P],trylock)
    def module(name):
        events.append(('module',name));return 0x123400 if state['module'] else None
    def proc(handle,name):
        events.append(('proc',handle,name));return try_address if state['export'] else None
    def lock(mutex):
        events.append(('lock',mutex));return state['fallbackStatus']
    ctypes.c_uint64.from_address(base+spec['module']).value=callback(P,[ctypes.c_char_p],module)
    ctypes.c_uint64.from_address(base+spec['proc']).value=callback(P,[P,ctypes.c_char_p],proc)
    lock_iat=0x12f4d0 if profile=='lunar' else 0x132468
    ctypes.c_uint64.from_address(base+lock_iat).value=callback(I,[P],lock)
    # Real std::_Throw_Cpp_error does not return. This mock deliberately returns
    # solely to observe the original error code without raising through Python.
    throw_address=callback(None,[I],lambda code:events.append(('throw',code)))
    code=b'\x48\xb8'+struct.pack('<Q',throw_address)+b'\xff\xe0'
    ctypes.memmove(base+(0x122b89 if profile=='lunar' else 0x125889),code,len(code))
    try:
        callers=[]
        with tempfile.TemporaryDirectory(prefix='adnin-number-poll-') as temp:
            source,binary=Path(temp)/'caller.asm',Path(temp)/'caller.bin'
            source.write_text(CALLER,encoding='ascii')
            for index,item in enumerate(report['numberPolling']):
                site=item['callRva']
                # Only our replacement CALL/JNE plus authored TEST and marker
                # continuations are executed, never an original function body.
                patch=bridge.call_bytes(site,item['bridgeTargetRva'])+b'\x85\xc0'+bytes.fromhex(item['after'])
                ctypes.memmove(base+site,patch,len(patch))
                ctypes.memmove(base+site+13,bytes.fromhex('49c7471001000000c3'),9)
                ctypes.memmove(base+item['busyRva'],bytes.fromhex('49c7471002000000c3'),9)
                built=subprocess.run([str(nasm),'-f','bin','-Ox','-DTARGET='+hex(base+site),str(source),'-o',str(binary)],capture_output=True,text=True)
                test.assertEqual(built.returncode,0,built.stdout+built.stderr)
                machine=binary.read_bytes();address=base+0x1000*(index+1)
                ctypes.memmove(address,machine,len(machine))
                callers.append(ctypes.WINFUNCTYPE(None,P,P)(address))
        k32.FlushInstructionCache(P(-1),base,report['imageSize'])
        cases=[dict(status=v) for v in (0,3,1,4)]
        cases += [dict(module=False,fallbackStatus=v) for v in (0,1,3)]
        cases += [dict(export=False,fallbackStatus=v) for v in (0,1)]
        for kind,run in zip(('get','register','pop'),callers):
            for case in cases:
                with test.subTest(profile=profile,kind=kind,case=case):
                    state.update(module=True,export=True,status=0,fallbackStatus=0);state.update(case)
                    events.clear();values=ctypes.create_string_buffer(32)
                    fallback=not state['module'] or not state['export']
                    status=state['fallbackStatus'] if fallback else state['status']
                    run(base+spec['mutex'],ctypes.addressof(values))
                    rbx,rsi,path,result=struct.unpack('<4Q',values)
                    test.assertEqual(path,1 if status==0 else 2)
                    test.assertEqual(result & 0xffffffff,0 if status==0 else 3)
                    test.assertEqual(rbx,0x1234567890abcd00 if kind=='pop' and status else 0x1234567890abcdef)
                    test.assertEqual(rsi,0x34567890abcdef12)
                    test.assertEqual(events[0],('module',b'MSVCP140.dll'))
                    test.assertEqual([e for e in events if e[0] in ('lock','trylock')],
                                     [('lock' if fallback else 'trylock',base+spec['mutex'])])
                    error=status!=0 and (fallback or status!=3)
                    test.assertEqual([e for e in events if e[0]=='throw'],[('throw',5)] if error else [])
                    if state['module']:test.assertIn(('proc',0x123400,b'_Mtx_trylock'),events)
        replay_denick_checks.check_simple_unwind(test,base,report)
    finally:
        k32.VirtualFree(base,0,0x8000)
