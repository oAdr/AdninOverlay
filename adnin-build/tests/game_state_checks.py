"""Private authored-bridge tests; original native code is never executed."""
import ctypes
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import pefile
import header_checks
import native_game_state
import replay_denick_checks


def verify(test, before, final, report, profile):
    old,new=pefile.PE(data=before),pefile.PE(data=final)
    p=native_game_state.PROFILES[profile]
    test.assertEqual(report['nativeGameState'],native_game_state.reviewed_hook(old,profile,report['section']['rva'],report['metadata']))
    item=report['nativeGameState'];site=item['callRva']
    test.assertEqual(new.get_data(site,5),b'\xe8'+struct.pack('<i',item['bridgeTargetRva']-site-5))
    for at,expected in native_game_state.checks(profile):
        if at!=site:test.assertEqual(new.get_data(at,len(expected)),expected)
        for delta in range(len(expected)):
            changed=bytearray(before);changed[old.get_offset_from_rva(at+delta)]^=1
            with test.assertRaisesRegex(ValueError,'game-state'):
                native_game_state.reviewed_hook(pefile.PE(data=bytes(changed)),profile,report['section']['rva'],report['metadata'])
    routes=report['outputRouting']
    test.assertEqual(routes['categories'],dict(players=0,tags=1,anticheatJavaOnly=2,denick=3,localOnly=4))
    test.assertEqual({s['callRva']:s['category'] for s in routes['sources']},
        ({0x19083:4,0x1917c:4,0x6da3d:0,0x6eb40:3,0x6aeb7:1,0x6b632:1,0x88048:3,0x8804f:3,0x87f1d:3}
         if profile=='lunar' else
         {0x19288:4,0x19381:4,0x6e4a2:0,0x6f5b0:3,0x6b907:1,0x6c082:1,0x89938:3,0x8993f:3,0x89819:3}))


def execute(test, final, report, profile, nasm):
    if os.name!='nt' or ctypes.sizeof(ctypes.c_void_p)!=8:test.skipTest('Windows x64 JNI mock')
    P=ctypes.c_void_p
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.VirtualAlloc.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32];kernel.VirtualAlloc.restype=P
    kernel.VirtualFree.argtypes=[P,ctypes.c_size_t,ctypes.c_uint32]
    kernel.FlushInstructionCache.argtypes=[P,P,ctypes.c_size_t]
    base=kernel.VirtualAlloc(None,report['imageSize'],0x3000,0x40);test.assertTrue(base)
    section,meta=report['section'],report['metadata'];p=native_game_state.PROFILES[profile]
    ctypes.memmove(base+section['rva'],final[section['offset']:section['offset']+section['size']],section['size'])
    gui=ctypes.c_uint64.from_address(base+(0x1a63a8 if profile=='lunar' else 0x17e428));gui.value=0x12340000
    table=(P*233)();env=(P*1)(ctypes.addressof(table));env_ptr=ctypes.addressof(env)
    keep,events,errors,refs=[],[],[],{}
    state=dict(pending=False,failure=None,next=b'Ingame',heap=False,capacity=15,length=None,nullHeap=False,ack=0)
    def write_state():
        raw=state['next'];obj=ctypes.create_string_buffer(32)
        if state['heap']:
            buf=ctypes.create_string_buffer(raw);keep.append(buf)
            struct.pack_into('<Q',obj,0,0 if state['nullHeap'] else ctypes.addressof(buf))
        else:ctypes.memmove(obj,raw,min(len(raw),15))
        struct.pack_into('<QQ',obj,16,len(raw) if state['length'] is None else state['length'],state['capacity'])
        ctypes.memmove(base+p['state'],obj,32)
    def original(actual_env,mc,clazz):
        events.append(('original',actual_env,mc,clazz))
        write_state()
        return 0x55aa66bb
    def check(actual_env):
        events.append(('check',));return state['pending']
    def clear(actual_env):
        events.append(('clear',));state['pending']=False
    def method(actual_env,clazz,name,sig):
        events.append(('method',name,sig))
        if state['failure'] in ('method-null','method-error'):
            state['pending']=state['failure']=='method-error';return None
        return 1 if name==b'setGameActive' and sig==b'(Z)V' else 2 if name==b'nativeRenderGeneratedEvent' and sig==b'(Ljava/lang/String;ZI)I' else None
    def call(actual_env,clazz,mid,args):
        try:
            test.assertEqual((actual_env,clazz,mid),(env_ptr,0x12340000,1))
            raw=ctypes.c_uint64.from_address(args).value;test.assertIn(raw,(0,1))
            events.append(('active',bool(raw)))
            if state['failure']=='callback-error':state['pending']=True
        except Exception as e:errors.append(str(e))
    def render(actual_env,clazz,mid,args):
        try:
            test.assertEqual((actual_env,clazz,mid),(env_ptr,0x12340000,2))
            values=struct.unpack('<3Q',ctypes.string_at(args,24))
            events.append(('output',refs[values[0]],values[1],values[2]))
            if state['failure']=='callback-error':state['pending']=True
            return state['ack']
        except Exception as e:errors.append(str(e));return 0
    def new_string(actual_env,raw):refs[0x7000]=raw;return 0x7000
    def delete(actual_env,ref):refs.pop(ref,None)
    def bind(offset,restype,args,callback):
        cb=ctypes.WINFUNCTYPE(restype,*args)(callback);keep.append(cb);table[offset//8]=ctypes.cast(cb,P).value
    bind(0x720,ctypes.c_ubyte,[P],check);bind(0x88,None,[P],clear)
    bind(0x388,P,[P,P,ctypes.c_char_p,ctypes.c_char_p],method);bind(0x478,None,[P,P,P,P],call)
    bind(0x418,ctypes.c_int,[P,P,P,P],render);bind(0x538,P,[P,ctypes.c_char_p],new_string);bind(0xb8,None,[P,P],delete)
    cb=ctypes.WINFUNCTYPE(P,P,P,P)(original);keep.append(cb)
    stub=b'\x48\xb8'+struct.pack('<Q',ctypes.cast(cb,P).value)+b'\xff\xe0'
    ctypes.memmove(base+p['target'],stub,len(stub))
    for target,json_mode in ((0x38260 if profile=='lunar' else 0x39f40,False),(0x37f60 if profile=='lunar' else 0x39c40,True)):
        def fallback(actual_env,mc,clazz,message,json_mode=json_mode):
            events.append(('fallback',json_mode));return 0x3344
        cb=ctypes.WINFUNCTYPE(P,P,P,P,P)(fallback);keep.append(cb)
        stub=b'\x48\xb8'+struct.pack('<Q',ctypes.cast(cb,P).value)+b'\xff\xe0';ctypes.memmove(base+target,stub,len(stub))
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-game-state-') as directory:
            source,binary=Path(directory)/'caller.asm',Path(directory)/'caller.bin'
            source.write_text('%define TARGET '+hex(base+section['rva']+meta['gameActive'])+'\n'+header_checks.CALLER,encoding='ascii')
            compiled=subprocess.run([str(nasm),'-f','bin','-Ox',str(source),'-o',str(binary)],capture_output=True,text=True)
            test.assertEqual(compiled.returncode,0,compiled.stdout+compiled.stderr)
            data=binary.read_bytes();ctypes.memmove(base+0x1000,data,len(data))
        kernel.FlushInstructionCache(P(-1),base,report['imageSize'])
        run=ctypes.WINFUNCTYPE(P,P,P,P,P,P)(base+0x1000)
        def invoke(raw=b'Ingame',expected=True,heap=False,capacity=None,length=None,mc=0x2000,clazz=0x3000,
                   pending=False,failure=None,missing_gui=False,missing_env=False,null_heap=False):
            events.clear();state.update(next=raw,heap=heap,capacity=(31 if heap else 15) if capacity is None else capacity,
                length=length,pending=pending,failure=failure,nullHeap=null_heap)
            gui.value=0 if missing_gui else 0x12340000
            snapshot=ctypes.create_string_buffer(64)
            result=run(None if missing_env else env_ptr,mc,clazz,0x4000,snapshot)
            test.assertEqual(result,0x55aa66bb)
            test.assertEqual(events[0],('original',None if missing_env else env_ptr,mc or None,clazz or None))
            test.assertEqual(struct.unpack_from('<7Q',snapshot,8),(0x3333333333333333,0x5555555555555555,
                0x6666666666666666,clazz,0x4000,0xdddddddddddddddd,0xeeeeeeeeeeeeeeee))
            actual=[e[1] for e in events if e[0]=='active']
            test.assertEqual(actual,[] if expected is None else [expected]);test.assertFalse(errors)
            return bytes(ctypes.string_at(base+p['state'],32))
        # The mock parser sets the new value inside the call: notification must
        # read the post-parser state, including repeated entry and every exit.
        for raw in (b'Ingame',b'Waiting',b'Ingame',b'Ingame',b'Pregame',b'Ingame',b'Replay',b'Unknown',b'',b'ingame',b'IngameX'):
            for heap in (False,True):invoke(raw,raw==b'Ingame',heap=heap)
        for opts in (dict(capacity=5),dict(length=5),dict(heap=True,null_heap=True),dict(mc=0),dict(clazz=0)):
            invoke(expected=False,**opts)
        for opts in (dict(pending=True),dict(missing_gui=True),dict(missing_env=True),dict(failure='method-null'),dict(failure='method-error')):
            invoke(expected=None,**opts)
            test.assertEqual(state['pending'],bool(opts.get('pending')))
            if opts.get('pending'):test.assertFalse(any(e[0]=='clear' for e in events))
            invoke() # a failed notification must not suppress a later retry
        invoke(failure='callback-error');test.assertFalse(state['pending']);invoke()
        # Identical content across producer categories proves no text heuristic
        # can turn a stats row containing Nick into a Denick result.
        raw=b'[Adnin] UnitPlayer Nick 100'
        buf=ctypes.create_string_buffer(raw);msg=ctypes.create_string_buffer(32)
        struct.pack_into('<4Q',msg,0,ctypes.addressof(buf),0,len(raw),len(raw))
        for entry,json_mode,category in (('plain',0,0),('json',1,0),('plainTags',0,1),('jsonTags',1,1),
                                        ('plainDenick',0,3),('jsonDenick',1,3),('plainLocal',0,4)):
            render_entry=ctypes.WINFUNCTYPE(P,P,P,P,P)(base+section['rva']+meta[entry])
            for ack,failure in ((0,None),(1,None),(2,None),(1,'callback-error')):
                state.update(ack=ack,failure=failure,pending=False);events.clear()
                result=render_entry(env_ptr,0x2000,0x3000,ctypes.addressof(msg))
                consumed=ack==1 and failure is None
                test.assertEqual(result,1 if consumed else 0x3344)
                test.assertEqual([e for e in events if e[0]=='output'],[('output',raw,json_mode,category)])
                test.assertEqual([e for e in events if e[0]=='fallback'],[] if consumed else [('fallback',bool(json_mode))])
                test.assertFalse(refs);test.assertFalse(state['pending']);test.assertFalse(errors)
        replay_denick_checks.check_simple_unwind(test,base,report)
    finally:kernel.VirtualFree(base,0,0x8000)
