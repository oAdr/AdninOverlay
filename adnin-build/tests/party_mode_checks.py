"""Lunar mode handoff in owned JNI/native mocks; never load the game DLL."""
import ctypes
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import pefile
import header_checks
import native_party_mode
import replay_denick_checks


def verify(test, before, final, report):
    old,new=pefile.PE(data=before),pefile.PE(data=final)
    item=report['lunarPartyModeHandoff']
    test.assertEqual(item,native_party_mode.reviewed_hook(old,report['section']['rva'],report['metadata']))
    site=item['callRva']
    test.assertEqual(new.get_data(site,5),b'\xe8'+struct.pack('<i',item['bridgeTargetRva']-site-5))
    expected=bytearray(old.get_data(0x1a2e5,0x90))
    expected[site-0x1a2e5:site-0x1a2e5+5]=new.get_data(site,5)
    test.assertEqual(new.get_data(0x1a2e5,0x90),expected)
    for start,end in ((0xcdb80,0xce026),(0xce030,0xce924)):
        test.assertEqual(old.get_data(start,end-start),new.get_data(start,end-start))
    for address in (0x1a2e5,0x1a31b,0x1a352,site,site+5,0x1a370,0xcdb80,0xce025,0xce030,0xce923):
        changed=bytearray(before);changed[old.get_offset_from_rva(address)]^=1
        with test.assertRaisesRegex(ValueError,'Party mode'):
            native_party_mode.reviewed_hook(pefile.PE(data=bytes(changed)),report['section']['rva'],report['metadata'])


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
    gui=ctypes.c_uint64.from_address(base+0x1a63a8);gui.value=0x12340000
    known=ctypes.c_ubyte.from_address(base+native_party_mode.KNOWN)
    table=(P*233)();env=(P*1)(ctypes.addressof(table));env_ptr=ctypes.addressof(env)
    keep,events,errors=[],[],[]
    state=dict(pending=False,failure=None,mode=0)
    def check(actual): return state['pending']
    def clear(actual): events.append(('clear',));state['pending']=False
    def method(actual,clazz,name,sig):
        events.append(('method',name,sig))
        if state['failure'] in ('method-null','method-error'):
            state['pending']=state['failure']=='method-error';return None
        return 1 if name==b'pollPartyMode' and sig==b'()I' else None
    def poll(actual,clazz,mid,args):
        try:
            test.assertEqual((actual,clazz,mid,args),(env_ptr,0x12340000,1,None))
            events.append(('poll',state['mode']))
            if state['failure']=='callback-error':state['pending']=True
            return state['mode']
        except Exception as error:errors.append(repr(error));return 0
    def parser(value):
        try:
            length,capacity=struct.unpack('<QQ',ctypes.string_at(value+16,16))
            test.assertEqual(capacity,15);test.assertLessEqual(length,10)
            text=ctypes.string_at(value,length)
            test.assertEqual(ctypes.c_ubyte.from_address(value+length).value,0)
            events.append(('parse',text));known.value=1
        except Exception as error:errors.append(repr(error))
    def consumer(actual,mc,clazz,extra):
        events.append(('consumer',actual,mc,clazz,extra));return 0x55667788
    def bind(offset,restype,args,function):
        cb=ctypes.WINFUNCTYPE(restype,*args)(function);keep.append(cb);table[offset//8]=ctypes.cast(cb,P).value
    def stub(at,restype,args,function):
        cb=ctypes.WINFUNCTYPE(restype,*args)(function);keep.append(cb)
        code=b'\x48\xb8'+struct.pack('<Q',ctypes.cast(cb,P).value)+b'\xff\xe0'
        ctypes.memmove(base+at,code,len(code))
    bind(0x720,ctypes.c_ubyte,[P],check);bind(0x88,None,[P],clear)
    bind(0x388,P,[P,P,ctypes.c_char_p,ctypes.c_char_p],method)
    bind(0x418,ctypes.c_int,[P,P,P,P],poll)
    stub(native_party_mode.PARSER,None,[P],parser)
    stub(native_party_mode.TARGET,P,[P,P,P,P],consumer)
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-party-mode-') as directory:
            source,binary=Path(directory)/'caller.asm',Path(directory)/'caller.bin'
            source.write_text('%define TARGET '+hex(base+section['rva']+meta['partyMode'])+'\n'+header_checks.CALLER,encoding='ascii')
            compiled=subprocess.run([str(nasm),'-f','bin','-Ox',str(source),'-o',str(binary)],capture_output=True,text=True)
            test.assertEqual(compiled.returncode,0,compiled.stdout+compiled.stderr)
            data=binary.read_bytes();ctypes.memmove(base+0x1000,data,len(data))
        kernel.FlushInstructionCache(P(-1),base,report['imageSize'])
        run=ctypes.WINFUNCTYPE(P,P,P,P,P,P)(base+0x1000)
        modes=[b'eight_one',b'eight_two',b'four_three',b'four_four',b'two_four']
        def invoke(mode,known_value=0,pending=False,failure=None,missing_gui=False,missing_env=False,mc=0x2000,clazz=0x3000):
            events.clear();state.update(mode=mode,pending=pending,failure=failure)
            known.value=known_value;gui.value=0 if missing_gui else 0x12340000
            snap=ctypes.create_string_buffer(64)
            result=run(None if missing_env else env_ptr,mc,clazz,0x4000,snap)
            test.assertEqual(result,0x55667788)
            test.assertEqual(events[-1],('consumer',None if missing_env else env_ptr,mc or None,clazz or None,0x4000))
            test.assertEqual(sum(x[0]=='consumer' for x in events),1)
            test.assertEqual(struct.unpack_from('<7Q',snap,8),(0x3333333333333333,0x5555555555555555,
                0x6666666666666666,clazz,0x4000,0xdddddddddddddddd,0xeeeeeeeeeeeeeeee))
            accepted=not (known_value or pending or failure or missing_gui or missing_env or not mc or not clazz) and 1<=mode<=5
            test.assertEqual([x[1] for x in events if x[0]=='parse'],[modes[mode-1]] if accepted else [])
            test.assertFalse(errors)
            if known_value:test.assertEqual(events,[('consumer',env_ptr,mc,clazz,0x4000)])
            test.assertEqual(state['pending'],pending,'Pre-existing exceptions are retained; new optional callback exceptions are cleared')
        for mode in (-2147483648,-1,0,1,2,3,4,5,6,2147483647):
            for k in (0,1):invoke(mode,k)
        for opts in (dict(pending=True),dict(failure='method-null'),dict(failure='method-error'),
                     dict(failure='callback-error'),dict(missing_gui=True),dict(missing_env=True),dict(mc=0),dict(clazz=0)):
            invoke(2,**opts);invoke(2)
        # Once mode is known, repeated model ticks do not enter Java, allocate a
        # mode string, log, or re-run the parser. The original consumer remains.
        for _ in range(1000):invoke(2,1)
        # Native clearing on queue exit allows a validated fresh response later.
        invoke(0);invoke(5)
        replay_denick_checks.check_simple_unwind(test,base,report)
    finally:kernel.VirtualFree(base,0,0x8000)
