"""Execute only the new Replay Denicker bridge in a private mock image."""
import ctypes
import os
import struct
import subprocess
import tempfile
from pathlib import Path
import pefile
import native_replay_denick
import replay_checks


def verify_pe(test, before, final, report, profile):
    old, new = pefile.PE(data=before), pefile.PE(data=final)
    spec = native_replay_denick.PROFILES[profile]
    item = report['replayDenicker']
    test.assertEqual(item, native_replay_denick.reviewed_patch(old, profile, report['section']['rva'], report['metadata']))
    test.assertEqual(new.get_data(spec['gate'], 18), bytes.fromhex(item['after']))
    test.assertTrue(item['actorIdentityPreserved'] and item['historicalNameCacheGatesUnchanged'])
    copy = next(h for h in report['hooks'] if h['callRva'] == spec['copy'])
    test.assertEqual((copy['originalTargetRva'], copy['callback']), (spec['copyTarget'], 'replayUuidCopy'))
    for site in (spec['gate'], spec['gate']-4, spec['gate']+18, spec['copy']):
        changed = bytearray(before)
        changed[old.get_offset_from_rva(site)] ^= 1
        with test.assertRaisesRegex(ValueError, 'Replay Denicker'):
            native_replay_denick.reviewed_patch(pefile.PE(data=bytes(changed)), profile, report['section']['rva'], report['metadata'])
    tags = [h['callRva'] for h in report['hooks'] if h['callback'] in ('plainTags', 'jsonTags')]
    test.assertEqual(tags, [0x6aeb7, 0x6b632] if profile == 'lunar' else [0x6b907, 0x6c082])


SNAPSHOT = replay_checks.SNAPSHOT_ASM.split('    push rbx\n')[0].replace('[r12+', '[r15+')
CALLER = SNAPSHOT + r'''
    push rbx
    push rbp
    push rsi
    push rdi
    push r12
    push r13
    push r14
    push r15
    sub rsp, 0x28
    mov r15, rdx
    mov r13, rcx
    mov rbp, r8
    sub rbp, NAMEFRAME
    mov r12d, r9d
    shr r9, 32
    mov ebx, r9d
    mov rsi, 0x6061223344556600
    mov rdi, 0x7071223344556600
    mov r14, 0xe0e1223344556600
    movdqu xmm0, [pattern]
    movdqu xmm1, [pattern+16]
    movdqu xmm2, [pattern+32]
    movdqu xmm3, [pattern+48]
    movdqu xmm4, [pattern+64]
    movdqu xmm5, [pattern+80]
    mov rax, 0x0001223344556600
    mov rcx, 0x1111223344556600
    mov rdx, 0x2221223344556600
    mov r8, 0x8881223344556600
    mov r9, 0x9991223344556600
    mov r10, 0xaaa1223344556600
    mov r11, 0xbbb1223344556600
    SNAPSHOT 0
    call [target]
    SNAPSHOT 256
    add rsp, 0x28
    pop r15
    pop r14
    pop r13
    pop r12
    pop rdi
    pop rsi
    pop rbp
    pop rbx
    ret
target: dq TARGET
pattern:
%assign x 0
%rep 96
    db (x*17+7)&255
%assign x x+1
%endrep
'''


def execute(test, final, report, profile, nasm):
    if os.name != 'nt' or ctypes.sizeof(ctypes.c_void_p) != 8:
        test.skipTest('Windows x64 private mock execution')
    k32 = ctypes.WinDLL('kernel32', use_last_error=True)
    P, I = ctypes.c_void_p, ctypes.c_int
    k32.VirtualAlloc.argtypes = [P, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32]
    k32.VirtualAlloc.restype = P
    k32.VirtualFree.argtypes = [P, ctypes.c_size_t, ctypes.c_uint32]
    k32.FlushInstructionCache.argtypes = [P, P, ctypes.c_size_t]
    base = k32.VirtualAlloc(None, report['imageSize'], 0x3000, 0x40)
    test.assertTrue(base)
    keep, refs, events = [], {}, []
    state = dict(value=1, pending=False, failure=None)
    spec = native_replay_denick.PROFILES[profile]
    section, meta = report['section'], report['metadata']
    ctypes.memmove(base+section['rva'], final[section['offset']:section['offset']+section['size']], section['size'])
    gui = ctypes.c_uint64.from_address(base + (0x1a63a8 if profile == 'lunar' else 0x17e428))
    flag = ctypes.c_ubyte.from_address(base + (0x1a63b8 if profile == 'lunar' else 0x17e438))
    table = (P*233)()
    env = (P*1)(ctypes.addressof(table))
    env_ptr = ctypes.addressof(env)
    def bind(offset, result, args, fn):
        callback = ctypes.WINFUNCTYPE(result, *args)(fn)
        keep.append(callback)
        table[offset//8] = ctypes.cast(callback, P).value
    def check(e):
        events.append(('check', e))
        return state['pending']
    def clear(e):
        events.append(('clear', e))
        state['pending'] = False
    def new(e, text):
        events.append(('new', text))
        if state['failure'] == 'new-null': return None
        if state['failure'] in ('new-exception', 'new-ref-exception'): state['pending'] = True
        if state['failure'] == 'new-exception': return None
        refs[0x4000] = text
        return 0x4000
    def delete(e, ref):
        events.append(('delete', ref))
        refs.pop(ref, None)
    def method(e, clazz, name, signature):
        events.append(('method', name, signature))
        if state['failure'] == 'method-exception': state['pending'] = True
        return None if state['failure'] in ('method-null','method-exception') else 1
    def integer(e, clazz, mid, args):
        ref = ctypes.c_uint64.from_address(args).value
        events.append(('integer', refs[ref]))
        if state['failure'] == 'callback-exception': state['pending'] = True
        return state['value']
    bind(0x720, ctypes.c_ubyte, [P], check)
    bind(0x88, None, [P], clear)
    bind(0x538, P, [P, ctypes.c_char_p], new)
    bind(0xb8, None, [P,P], delete)
    bind(0x388, P, [P,P,ctypes.c_char_p,ctypes.c_char_p], method)
    bind(0x418, I, [P,P,P,P], integer)
    def native_string(raw):
        value = ctypes.create_string_buffer(32)
        keep.append(value)
        if len(raw) > 15:
            heap = ctypes.create_string_buffer(raw)
            keep.append(heap)
            struct.pack_into('<Q', value, 0, ctypes.addressof(heap))
        else: ctypes.memmove(value, raw, len(raw))
        struct.pack_into('<QQ', value, 16, len(raw), max(15,len(raw)))
        return value
    try:
        callers = []
        with tempfile.TemporaryDirectory(prefix='adnin-replay-denick-') as tmp:
            source, binary = Path(tmp)/'caller.asm', Path(tmp)/'caller.bin'
            source.write_text(CALLER, encoding='ascii')
            for index, target in enumerate((base+section['rva']+meta['replayDenickGate'], base+spec['gate'])):
                source.write_text(CALLER if index == 0 else CALLER.replace('    call [target]',
                    '    sub rsp, 8\n    call [target]\n    add rsp, 8'), encoding='ascii')
                built = subprocess.run([str(nasm), '-f','bin','-Ox','-DTARGET='+hex(target),
                    '-DNAMEFRAME='+hex(spec['nameFrame']), str(source),'-o',str(binary)],capture_output=True,text=True)
                test.assertEqual(built.returncode,0,built.stdout+built.stderr)
                code = binary.read_bytes()
                ctypes.memmove(base+0x1000+0x1000*index,code,len(code))
                callers.append(ctypes.WINFUNCTYPE(None,P,P,P,ctypes.c_uint64)(base+0x1000+0x1000*index))
        # Only authored replacement bytes and return stubs execute here.
        patch = bytes.fromhex(report['replayDenicker']['after'])
        ctypes.memmove(base+spec['gate'],patch,len(patch))
        ctypes.memmove(base+spec['gate']+18, bytes.fromhex('b801000000c3'), 6)
        ctypes.memmove(base+spec['skip'], bytes.fromhex('31c0c3'), 3)
        def copy(dst, src):
            events.append(('copy', dst, src))
            ctypes.memmove(dst,src,32)
            return dst
        callback = ctypes.WINFUNCTYPE(P,P,P)(copy); keep.append(callback)
        code = b'\x48\xb8'+struct.pack('<Q',ctypes.cast(callback,P).value)+b'\xff\xe0'
        ctypes.memmove(base+spec['copyTarget'],code,len(code))
        k32.FlushInstructionCache(P(-1),base,report['imageSize'])
        cases = [dict(mode=0,version=v,expected=v==1) for v in range(5)]
        cases += [dict(mode=1,version=v,value=ack,expected=ack==1) for v in (1,2,4) for ack in (0,1,2,-1)]
        cases += [dict(mode=1,version=2,failure=f,expected=False) for f in
                  ('new-null','new-exception','new-ref-exception','method-null','method-exception','callback-exception')]
        cases += [dict(mode=1,version=2,expected=False,**arg) for arg in
                  ({'pending':True},{'noGui':True},{'noEnv':True},{'name':b''},{'name':b'Bad-name'},
                   {'name':b'x'*17},{'name':b'[NPC]'},{'name':b'\xc2\xa7aisa5'},{'capacity':3},{'unterminated':True})]
        cases += [dict(mode=1,version=2,name=b'a'*16,expected=True)]
        for case in cases:
            for flow, run in enumerate(callers):
                with test.subTest(profile=profile,flow=flow,case=case):
                    events.clear(); refs.clear()
                    state.update(value=case.get('value',1),pending=case.get('pending',False),failure=case.get('failure'))
                    gui.value = 0 if case.get('noGui') else 0x12340000
                    raw = case.get('name',b'isa5')
                    name = native_string(raw)
                    if 'capacity' in case: struct.pack_into('<Q',name,24,case['capacity'])
                    if case.get('unterminated'): name[len(raw)] = b'X'
                    before = bytes(name)
                    snapshots = ctypes.create_string_buffer(512)
                    run(0 if case.get('noEnv') else env_ptr,ctypes.addressof(snapshots),ctypes.addressof(name),
                        case['mode'] | case['version']<<32)
                    if flow:
                        test.assertEqual(struct.unpack_from('<Q',snapshots,256)[0],int(case['expected']))
                    else:
                        test.assertEqual(snapshots.raw[:224],snapshots.raw[256:480], 'all GPR/XMM0-5/stack preserved')
                        test.assertEqual(bool(struct.unpack_from('<Q',snapshots,480)[0]&0x40),not case['expected'])
                    test.assertEqual(bytes(name),before)
                    test.assertEqual(refs,{})
                    if not case['mode']: test.assertEqual(events,[])
                    if case.get('pending'): test.assertFalse(any(e[0]=='clear' for e in events))
                    for e in events:
                        if e[0]=='method': test.assertEqual(e[1:],(b'nativeReplayIsNick',b'(Ljava/lang/String;)I'))
        copy_bridge = ctypes.WINFUNCTYPE(P,P,P)(base+section['rva']+meta['replayUuidCopy'])
        for replay in (0,1):
            events.clear(); flag.value = replay
            dst,src = native_string(b'actor'),native_string(b'resolved')
            old = bytes(dst)
            test.assertEqual(copy_bridge(ctypes.addressof(dst),ctypes.addressof(src)),ctypes.addressof(dst))
            test.assertEqual(bytes(dst),old if replay else bytes(src))
            test.assertEqual(len(events),0 if replay else 1)
        replay_checks.check_unwind(test,base,report,('replayDenickGate',))
        check_simple_unwind(test,base,report)
    finally:
        k32.VirtualFree(base,0,0x8000)


def check_simple_unwind(test,base,report):
    unwind = ctypes.WinDLL('ntdll').RtlVirtualUnwind
    unwind.argtypes = [ctypes.c_uint32,ctypes.c_uint64,ctypes.c_uint64]+[ctypes.c_void_p]*5
    unwind.restype = ctypes.c_void_p
    for item in report['bridgeRuntimeFunctions']:
        frames={'replayNickName':(0x30,[7,6,3]),'replayUuidCopy':(0,[]),'numberLock':(0x28,[6,3]),
                'header':(0x40,[13,12,7,6,3]),
                'lunarSchedule':(0x30,[3]),
                'processEntry':(0,[]),
                'apiKeyReady':(0,[]),'apiUuidReady':(0,[]),'apiPingProxy':(0,[]),
                'hypixelHttp':(0x348,[15,14,13,12,7,6,5,3]),'apiDecodeComponent':(0,[]),'apiRefreshFailures':(0,[])}
        if item['name'] not in frames: continue
        allocation, registers = frames[item['name']]
        stack,context = ctypes.create_string_buffer(2048),ctypes.create_string_buffer(1232)
        sp = ctypes.addressof(stack)+128
        for i,register in enumerate(registers): struct.pack_into('<Q',stack,128+allocation+i*8,0xabc000+register)
        ret = 0x7ffefedc1234
        struct.pack_into('<Q',stack,128+allocation+8*len(registers),ret)
        struct.pack_into('<I',context,0x30,0x00100003)
        struct.pack_into('<Q',context,0x98,sp)
        prolog = ctypes.c_ubyte.from_address(base+item['unwind']+1).value
        pc=base+item['begin']+prolog
        struct.pack_into('<Q',context,0xf8,pc)
        runtime=(ctypes.c_uint32*3)(item['begin'],item['end'],item['unwind'])
        handler,frame=ctypes.c_void_p(),ctypes.c_uint64()
        unwind(0,base,pc,ctypes.byref(runtime),context,ctypes.byref(handler),ctypes.byref(frame),None)
        test.assertEqual(struct.unpack_from('<Q',context,0xf8)[0],ret)
        test.assertEqual(struct.unpack_from('<Q',context,0x98)[0],sp+allocation+8*len(registers)+8)
        for register in registers:
            test.assertEqual(struct.unpack_from('<Q',context,0x78+register*8)[0],0xabc000+register)
