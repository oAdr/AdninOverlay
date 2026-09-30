"""Execute only authored input bridges in private memory with fake Win32 APIs.

No original DLL, HWND, game input, network or live process is accessed.
"""
import ctypes as C
import os
import struct
import pefile
import native_input_hooks
import replay_denick_checks

def verify(test,before,final,report,profile):
    old,new=pefile.PE(data=before),pefile.PE(data=final)
    data=report['nativeInputHooks'];p=native_input_hooks.PROFILES[profile]
    test.assertEqual(data,native_input_hooks.reviewed_patches(old,profile,report['section']['rva'],
        report['metadata'],report['stateSection']['rva'],test.assertTrue))
    for change in data['patches']:
        test.assertEqual(new.get_data(change['siteRva'],len(bytes.fromhex(change['after']))),bytes.fromhex(change['after']))
    test.assertEqual(old.get_data(p['proc'],0x2c0),new.get_data(p['proc'],0x2c0))
    test.assertEqual(old.get_data(p['cleanup'],0x30),new.get_data(p['cleanup'],0x30))
    test.assertFalse(new.sections[-1].Characteristics & 0x20000000)
    test.assertFalse(new.sections[-2].Characteristics & 0x80000000)

def execute(test,final,report,profile,nasm):
    if os.name!='nt' or C.sizeof(C.c_void_p)!=8:test.skipTest('Windows x64 private input fixture')
    P=C.c_void_p;Q=C.c_size_t;D=C.c_uint32;I=C.c_int32
    k=C.WinDLL('kernel32',use_last_error=True)
    k.VirtualAlloc.argtypes=[P,Q,D,D];k.VirtualAlloc.restype=P
    k.VirtualFree.argtypes=[P,Q,D]
    k.FlushInstructionCache.argtypes=[P,P,Q]
    base=k.VirtualAlloc(None,report['imageSize'],0x3000,0x40);test.assertTrue(base)
    section,meta=report['section'],report['metadata'];p=native_input_hooks.PROFILES[profile]
    C.memmove(base+section['rva'],final[section['offset']:section['offset']+section['size']],section['size'])
    state=base+report['nativeInputHooks']['stateRva'];keep=[];events=[];errors=[];model={}
    def q(at,value=None):
        v=Q.from_address(at)
        if value is not None:v.value=value
        return v.value
    def d(at,value=None):
        v=D.from_address(at)
        if value is not None:v.value=value
        return v.value
    def callback(restype,args,fn):
        def wrapped(*args):
            try:return fn(*args)
            except Exception as exc:errors.append(repr(exc));return 0
        cb=C.WINFUNCTYPE(restype,*args)(wrapped);keep.append(cb);return C.cast(cb,P).value
    def stub(rva,fn):
        raw=b'\x48\xb8'+struct.pack('<Q',fn)+b'\xff\xe0';C.memmove(base+rva,raw,len(raw))
    def win(hwnd):return model['windows'].get(hwnd)
    def is_window(hwnd):events.append(('is-window',hwnd));return int(bool(win(hwnd)))
    def window_thread(hwnd,pid):
        events.append(('thread',hwnd))
        w=win(hwnd)
        if pid:d(pid,w['pid'] if w else 0)
        return w['tid'] if w else 0
    def get_prop(hwnd,name):events.append(('get-prop',hwnd));return win(hwnd)['prop'] if win(hwnd) else 0
    def set_prop(hwnd,name,value):
        events.append(('set-prop',hwnd))
        if model.get('set-prop-fail'):return 0
        win(hwnd)['prop']=value;return 1
    def remove_prop(hwnd,name):
        events.append(('remove-prop',hwnd))
        w=win(hwnd)
        if not w:return 0
        old=w['prop'];w['prop']=0;return old
    def get_long(hwnd,index):
        test.assertEqual(index,-4);events.append(('get-long',hwnd));return win(hwnd)['proc'] if win(hwnd) else 0
    def set_long(hwnd,index,proc):
        test.assertEqual(index,-4);events.append(('set-long',hwnd,proc))
        w=win(hwnd)
        if not w or model.get('set-long-fail'):return 0
        if model.pop('race',False):w['proc']=0x2002
        old=w['proc'];w['proc']=proc;return old
    def native(hwnd,msg,wp,lp):
        events.append(('native',hwnd,msg,wp,lp))
        test.assertGreaterEqual(d(state),1)
        if model.pop('nested',False):
            test.assertEqual(proc(hwnd,0x200,0x99,0x88),result(hwnd,0x200,0x99,0x88))
            test.assertEqual(d(state),1)
        return result(hwnd,msg,wp,lp)
    def result(hwnd,msg,wp,lp):return (hwnd ^ (msg<<24) ^ wp ^ lp ^ 0xFEDCBA9800000000)&0xffffffffffffffff
    proc=C.WINFUNCTYPE(Q,Q,D,Q,Q)(base+section['rva']+meta['inputProc'])
    def send(hwnd,msg,wp,lp,flags,timeout,out):
        events.append(('send',hwnd,msg,flags,timeout));test.assertEqual((flags,timeout),(3,50))
        if model['send']=='timeout-before':return 0
        prior=model['tid'];model['tid']=win(hwnd)['tid']
        try:
            if win(hwnd)['proc']==base+section['rva']+meta['inputProc']:
                value=proc(hwnd,msg,wp,lp)
            else:value=0x77
            q(out,value)
        finally:model['tid']=prior
        return 0 if model['send']=='timeout-after' else 1
    apis={
        b'IsWindow':callback(I,[Q],is_window),
        b'GetWindowThreadProcessId':callback(D,[Q,P],window_thread),
        b'GetPropA':callback(Q,[Q,P],get_prop),
        b'SetPropA':callback(I,[Q,P,Q],set_prop),
        b'RemovePropA':callback(Q,[Q,P],remove_prop),
        b'SendMessageTimeoutA':callback(Q,[Q,D,Q,Q,D,D,P],send),
        b'RegisterWindowMessageA':callback(D,[P],lambda name:0xc1aa)}
    lunar=profile=='lunar'
    iats=dict(module=0x12f1c8 if lunar else 0x132148,getproc=0x12f1c0 if lunar else 0x132140,
              tick=0x12f1f0 if lunar else 0x132170,pid=0x12f200 if lunar else 0x132180,
              tid=0x12f220 if lunar else 0x132230,get=0x12f658 if lunar else 0x132660,
              set=0x12f660 if lunar else 0x132668)
    q(base+iats['module'],callback(Q,[P],lambda name:0x1111))
    q(base+iats['getproc'],callback(Q,[Q,C.c_char_p],lambda module,name:0 if model.get('missing')==name else apis[name]))
    q(base+iats['tick'],callback(Q,[],lambda:model['tick']))
    q(base+iats['pid'],callback(D,[],lambda:12345))
    q(base+iats['tid'],callback(D,[],lambda:model['tid']))
    q(base+iats['get'],callback(Q,[Q,I],get_long));q(base+iats['set'],callback(Q,[Q,I,Q],set_long))
    stub(0x10f90 if lunar else 0x10d40,callback(Q,[],lambda:model['candidate']))
    stub(p['proc'],callback(Q,[Q,D,Q,Q],native))
    if not lunar:stub(0x12240,callback(Q,[],lambda:events.append(('compat-init-tail',)) or 0x55))
    initialize=C.WINFUNCTYPE(Q)(base+section['rva']+meta['inputInitialize'])
    maintain=C.WINFUNCTYPE(None)(base+section['rva']+meta['inputMaintain'])
    detach=C.WINFUNCTYPE(I)(base+section['rva']+meta['inputDetach'])
    ours=base+section['rva']+meta['inputProc']
    def window(proc=0x1001,tid=77,pid=12345):return dict(proc=proc,tid=tid,pid=pid,prop=0)
    def reset():
        C.memset(state,0,128);q(base+p['hwnd'],0);q(base+p['original'],0)
        q(base+(0x1a69d0 if lunar else 0x17ea60),0)
        model.clear();model.update(windows={0x101:window()},candidate=0x101,tid=99,tick=10000,send='ok')
        events.clear();errors.clear()
    def tick():model['tick']+=1000;maintain();test.assertFalse(errors)
    def install():
        reset();tick();test.assertEqual(win(0x101)['proc'],ours);test.assertEqual(q(base+p['original']),0x1001)
        test.assertEqual(win(0x101)['prop'],state);test.assertEqual(d(state+88),1);events.clear()
    try:
        k.FlushInstructionCache(P(-1),base,report['imageSize'])
        reset();initialize();test.assertEqual(win(0x101)['proc'],ours)
        test.assertEqual(sum(e[0]=='compat-init-tail' for e in events),0 if lunar else 1)
        install()
        for i in range(20):tick()
        test.assertFalse(any(e[0]=='set-long' for e in events))
        win(0x101)['proc']=0x2002;events.clear()
        for i in range(20):tick()
        test.assertEqual(win(0x101)['proc'],0x2002);test.assertEqual(q(base+p['original']),0x1001)
        test.assertFalse(any(e[0]=='set-long' for e in events));test.assertEqual(detach(),0)
        test.assertFalse(any(e[0]=='send' for e in events))
        win(0x101)['proc']=ours;test.assertEqual(detach(),1)
        test.assertEqual(win(0x101)['proc'],0x1001);test.assertEqual(q(base+p['hwnd']),0)
        test.assertEqual(d(state+4),3);events.clear();tick();test.assertEqual(events,[])
        # All ordinary mouse/key/character/focus/raw-input values and returns survive.
        install()
        messages=(0,1,5,6,7,8,0x1c,0xff,0x100,0x101,0x102,0x103,0x104,0x105,0x106,
                  0x200,0x201,0x202,0x203,0x204,0x205,0x206,0x207,0x208,0x209,0x20a,0x20b,0x20c,0x20e,0xc1aa,0xffff)
        for msg in messages:
            for wp,lp in ((0,0),(9,0x80000000),(0xffffffffffffffff,0xfedcba9876543210)):
                events.clear();test.assertEqual(proc(0x101,msg,wp,lp),result(0x101,msg,wp,lp))
                test.assertEqual(events,[('native',0x101,msg,wp,lp)]);test.assertEqual(d(state),0)
        model['nested']=True;events.clear();proc(0x101,0x100,65,0)
        test.assertEqual(len(events),2);test.assertEqual(d(state),0)
        # Ordinary traffic cannot resolve APIs, inspect properties or synchronously wait.
        events.clear()
        for n in range(10000):proc(0x101,0x200,n,n<<1)
        test.assertEqual(len(events),10000);test.assertTrue(all(e[0]=='native' for e in events))
        # A new live HWND cannot replace the previous chain.
        install();model['windows'][0x202]=window(0x3003);model['candidate']=0x202;tick()
        test.assertEqual(win(0x101)['proc'],ours);test.assertEqual(win(0x202)['proc'],0x3003)
        test.assertEqual(q(base+p['original']),0x1001)
        # Completed destruction and HWND reuse preserve the new predecessor.
        model['tid']=77;proc(0x101,0x82,0,0);model['tid']=99
        del model['windows'][0x101];tick()
        test.assertEqual(win(0x202)['proc'],ours);test.assertEqual(q(base+p['original']),0x3003)
        install();model['tid']=77;proc(0x101,0x82,0,0);model['tid']=99
        model['windows'][0x101]=window(0x4004);tick()
        test.assertEqual(q(base+p['original']),0x4004);test.assertEqual(win(0x101)['proc'],ours)
        # An unproven replacement GUI thread and foreign process are not hooked.
        install();del model['windows'][0x101];model['windows'][0x202]=window(0x5005,tid=88)
        model['candidate']=0x202;tick();test.assertEqual(win(0x202)['proc'],0x5005);test.assertEqual(detach(),0)
        reset();win(0x101)['pid']=54321;tick();test.assertEqual(win(0x101)['proc'],0x1001)
        # Missing WinAPI/property/SetWindowLong failures never leave a fake binding.
        for failure in ('missing','set-prop-fail','set-long-fail'):
            reset();model[failure]=b'IsWindow' if failure=='missing' else True;tick()
            test.assertEqual(q(base+p['hwnd']),0);test.assertEqual(win(0x101)['proc'],0x1001)
        # A GUI-thread caller, active callback or timeout cannot authorize unload.
        install();model['tid']=77;test.assertEqual(detach(),0);model['tid']=99
        d(state,1);test.assertEqual(detach(),0);d(state,0)
        model['send']='timeout-before';test.assertEqual(detach(),0);test.assertEqual(win(0x101)['proc'],ours)
        model['send']='ok';test.assertEqual(detach(),1)
        # A completion after timeout requires a second thread-drain message.
        install();model['send']='timeout-after';test.assertEqual(detach(),0)
        test.assertEqual(d(state+4),2);test.assertEqual(win(0x101)['proc'],0x1001)
        model['send']='timeout-before';test.assertEqual(detach(),0)
        model['send']='ok';events.clear();test.assertEqual(detach(),1)
        test.assertEqual([e[2] for e in events if e[0]=='send'],[0])
        # A raced outer owner is restored, retains our DLL, and later can release it.
        install();model['race']=True;test.assertEqual(detach(),0)
        test.assertEqual(win(0x101)['proc'],0x2002);test.assertEqual(q(base+p['original']),0x1001)
        win(0x101)['proc']=ours;test.assertEqual(detach(),1)
        # Forged private messages from a different thread/token are harmless.
        install();events.clear();test.assertEqual(proc(0x101,0xc1aa,state,0x41444e49),0)
        test.assertEqual(win(0x101)['proc'],ours);test.assertEqual(d(state),0)
        events.clear();test.assertEqual(proc(0x101,0xc1aa,state+8,0x41444e49),result(0x101,0xc1aa,state+8,0x41444e49))
        test.assertEqual(events,[('native',0x101,0xc1aa,state+8,0x41444e49)])
        test.assertFalse(errors)
        replay_denick_checks.check_simple_unwind(test,base,report)
    finally:k.VirtualFree(base,0,0x8000)
