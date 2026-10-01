"""Replay-only stats bridge checks; execute new code in a private mock image."""
import ctypes
import os
import struct
import subprocess
import tempfile
import uuid
from pathlib import Path
import pefile
import bridge
import native_replay_stats


def verify_pe(test, before, final, report, profile):
    old, new = pefile.PE(data=before), pefile.PE(data=final)
    spec = native_replay_stats.PROFILES[profile]
    test.assertEqual(report['replayStats'], native_replay_stats.reviewed_hook(
        old, profile, report['section']['rva'], report['metadata']))
    hook = next(h for h in report['hooks'] if h['callback'] == 'replayStats')
    test.assertEqual(hook['callRva'], spec['call'])
    test.assertEqual(hook['originalTargetRva'], spec['placeholder'])
    test.assertEqual(new.get_data(spec['call'], 5), bridge.call_bytes(spec['call'], hook['bridgeTargetRva']))
    start, end = spec['block']
    test.assertEqual(old.get_data(start, spec['call']-start), new.get_data(start, spec['call']-start))
    test.assertEqual(old.get_data(spec['call']+5, end-spec['call']-5),
                     new.get_data(spec['call']+5, end-spec['call']-5))
    for at in (start, spec['call'], end-1):
        changed = bytearray(before)
        changed[old.get_offset_from_rva(at)] ^= 1
        with test.assertRaisesRegex(ValueError, 'Replay stats'):
            native_replay_stats.reviewed_hook(pefile.PE(data=bytes(changed)), profile,
                                             report['section']['rva'], report['metadata'])
    # Chat/history gate stays native and is not widened by Replay stats.
    at, size = (0x88098, 45) if profile == 'lunar' else (0x8998c, 45)
    test.assertEqual(old.get_data(at, size), new.get_data(at, size))
    metadata = report['metadata']
    payload = bytearray(new.get_data(report['section']['rva'], metadata['payloadSize']))
    queue_unwind = metadata['replayStatsQueueUnwind']
    test.assertEqual(payload[queue_unwind:queue_unwind+4], bytes((0x11, 5, 2, 0)))
    test.assertEqual(struct.unpack_from('<I', payload, queue_unwind+8)[0],
                     report['section']['rva'] + metadata['replayStatsCleanup'])
    payload[queue_unwind+8] ^= 1
    with test.assertRaisesRegex(ValueError, 'cleanup handler'):
        bridge.read_metadata(payload, report['section']['rva'])


CALLER = r'''
bits 64
default rel
    push rbx
    push rbp
    push rsi
    push rdi
    push r12
    push r13
    push r14
    push r15
    sub rsp, 0x38
    mov r13, rdx
    mov r12, r8
    mov rbx, 0x3333444455556666
    mov rbp, 0x5555666677778888
    mov rsi, 0x6666777788889999
    mov rdi, 0x777788889999aaaa
    mov r14, 0xeeee111122223333
    mov r15, 0xffff222233334444
    mov [r12+72], rsp
    mov rdx, 0x43214321
    mov r8d, 0xaaaaaa
    call [target]
    mov [r12], rax
    mov [r12+8], rbx
    mov [r12+16], rbp
    mov [r12+24], rsi
    mov [r12+32], rdi
    mov [r12+40], r13
    mov [r12+48], r14
    mov [r12+56], r15
    mov [r12+64], rsp
    add rsp, 0x38
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
'''


def execute(test, final, report, profile, nasm):
    if os.name != 'nt' or ctypes.sizeof(ctypes.c_void_p) != 8:
        test.skipTest('Windows x64 mock execution')
    spec = native_replay_stats.PROFILES[profile]
    P, I = ctypes.c_void_p, ctypes.c_int
    k32 = ctypes.WinDLL('kernel32', use_last_error=True)
    k32.VirtualAlloc.argtypes = [P, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32]
    k32.VirtualAlloc.restype = P
    k32.VirtualFree.argtypes = [P, ctypes.c_size_t, ctypes.c_uint32]
    k32.FlushInstructionCache.argtypes = [P, P, ctypes.c_size_t]
    base = k32.VirtualAlloc(None, report['imageSize'], 0x3000, 0x40)
    test.assertTrue(base)
    section, meta = report['section'], report['metadata']
    ctypes.memmove(base+section['rva'], final[section['offset']:section['offset']+section['size']], section['size'])
    keep, events, refs, state = [], [], {}, {}
    table = (P*233)()
    env = (P*1)(ctypes.addressof(table))
    env_ptr = ctypes.addressof(env)
    # Explicit synthetic UUID fields; never a recorded account or credential.
    expected_uuid = str(uuid.UUID(fields=(0x01234567, 0x89ab, 0x4cde, 0x8f, 0xab, 0x0123456789ab)))
    def bind(offset, result, args, callback):
        fn = ctypes.WINFUNCTYPE(result, *args)(callback)
        keep.append(fn)
        table[offset//8] = ctypes.cast(fn, P).value
    def stub(rva, result, args, callback):
        fn = ctypes.WINFUNCTYPE(result, *args)(callback)
        keep.append(fn)
        code = b'\x48\xb8' + struct.pack('<Q', ctypes.cast(fn, P).value) + b'\xff\xe0'
        ctypes.memmove(base+rva, code, len(code))
    def string_at(pointer):
        length, capacity = struct.unpack('<QQ', ctypes.string_at(pointer+16, 16))
        data = ctypes.c_uint64.from_address(pointer).value if capacity >= 16 else pointer
        return ctypes.string_at(data, length).decode('utf8')
    def check(e):
        events.append(('check', e))
        return state['pending']
    def clear(e):
        events.append(('clear', e))
        state['pending'] = False
    def delete(e, ref):
        events.append(('delete', e, ref))
        refs.pop(ref, None)
    def new(e, value):
        events.append(('new', e, value))
        fail = state['failure']
        if fail in ('new-null', 'new-exception'):
            state['pending'] = fail.endswith('exception')
            return None
        refs[0x2100] = value
        if fail == 'new-ref-exception': state['pending'] = True
        return 0x2100
    def method(e, clazz, name, signature):
        events.append(('method', e, clazz, name, signature))
        if state['failure'] in ('method-null', 'method-exception'):
            state['pending'] = state['failure'].endswith('exception')
            return None
        return 0x55
    def call(e, clazz, method_id, args):
        value = refs.get(ctypes.c_uint64.from_address(args).value)
        events.append(('call', e, clazz, method_id, value))
        if state['failure'] in ('call-null', 'call-exception'):
            state['pending'] = state['failure'].endswith('exception')
            return None
        refs[0x2200] = state['profile']
        if state['failure'] == 'call-ref-exception': state['pending'] = True
        return 0x2200
    def length(e, ref):
        events.append(('length', e, ref))
        if state['failure'] == 'length-exception': state['pending'] = True
        return len(refs[ref])
    def region(e, ref, start, size, buffer):
        events.append(('region', e, ref, start, size))
        if state['failure'] == 'region-exception': state['pending'] = True
        data = refs[ref].encode('utf8')
        ctypes.memmove(buffer, data, len(data))
    def placeholder(row, text, color):
        events.append(('placeholder', row, text, color))
        if text != 0x43214321:
            events.append(('nick', string_at(text), color, row))
            return 0x98765432
        return 0x12345678
    def lock(mutex):
        events.append(('lock', mutex))
        if not state['lockError']: state['locked'] = True
        return state['lockError']
    def unlock(mutex):
        events.append(('unlock', mutex, state['locked']))
        state['locked'] = False
        return 0
    def queue(uuid):
        events.append(('queue', string_at(uuid), state['locked']))
    def getter(uuid, stats):
        capacities = tuple(ctypes.c_uint64.from_address(stats+at+0x18).value for at in spec['strings'])
        events.append(('get', string_at(uuid), stats, state['locked'], capacities))
        state['stats'] = stats
        ctypes.memset(stats, 0, 0x180)
        for ready in state['ready']: ctypes.c_ubyte.from_address(stats+ready).value = 1
        return state['cacheHit']
    def formatter(row, stats, mode, unused):
        events.append(('format', row, stats, mode, unused))
    def destroy(stats): events.append(('destroy', stats))
    bind(0x720, ctypes.c_ubyte, [P], check)
    bind(0x88, None, [P], clear)
    bind(0xb8, None, [P, P], delete)
    bind(0x538, P, [P, ctypes.c_char_p], new)
    bind(0x388, P, [P, P, ctypes.c_char_p, ctypes.c_char_p], method)
    bind(0x3a0, P, [P, P, P, P], call)
    bind(0x520, I, [P, P], length)
    bind(0x6e8, None, [P, P, I, I, P], region)
    stub(spec['placeholder'], P, [P, P, I], placeholder)
    stub(spec['queue'], None, [P], queue)
    stub(spec['getter'], ctypes.c_ubyte, [P, P], getter)
    stub(spec['formatter'], None, [P, P, I, P], formatter)
    stub(spec['destructor'], None, [P], destroy)
    for iat, callback in ((spec['lockIat'], lock), (spec['unlockIat'], unlock)):
        fn = ctypes.WINFUNCTYPE(I, P)(callback)
        keep.append(fn)
        ctypes.c_void_p.from_address(base+iat).value = ctypes.cast(fn, P).value
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-replay-stats-') as directory:
            asm, output = Path(directory)/'caller.asm', Path(directory)/'caller.bin'
            asm.write_text(CALLER, encoding='ascii')
            run = subprocess.run([str(nasm), '-f', 'bin', '-Ox',
                '-DTARGET='+hex(base+section['rva']+meta['replayStats']), str(asm), '-o', str(output)],
                capture_output=True, text=True)
            test.assertEqual(run.returncode, 0, run.stdout+run.stderr)
            code = output.read_bytes()
            ctypes.memmove(base+0x1000, code, len(code))
        k32.FlushInstructionCache(P(-1), base, report['imageSize'])
        invoke = ctypes.WINFUNCTYPE(None, P, P, P)(base+0x1000)
        def run_case(**kwargs):
            events.clear(); refs.clear(); state.clear()
            state.update(pending=False, failure=None, profile='RealAccount|'+expected_uuid,
                         lockError=0, locked=False, ready=list(spec['ready']), cacheHit=1)
            state.update(kwargs)
            ctypes.c_ubyte.from_address(base+spec['replayFlag']).value = kwargs.get('replay', 1)
            ctypes.c_uint64.from_address(base+spec['gui']).value = 0 if kwargs.get('noGui') else 0x12340000
            mode = kwargs.get('mode', 1)
            ctypes.c_int.from_address(base+spec['mode']).value = mode
            row = ctypes.create_string_buffer(bytes((i*13+7)&255 for i in range(0x178)), 0x178)
            name = kwargs.get('name', b'ActorName')
            storage = ctypes.create_string_buffer(name)
            raw_name = name+bytes(16-len(name)) if len(name)<16 else struct.pack('<QQ', ctypes.addressof(storage), 0)
            row_bytes = raw_name+struct.pack('<QQ', len(name), 15 if len(name)<16 else 31)
            ctypes.memmove(ctypes.addressof(row)+0x40, row_bytes, 32)
            struct.pack_into('<I',row,0x94,kwargs.get('color',0xffffff))
            old_row = row.raw
            snapshot = (ctypes.c_uint64*10)()
            invoke(ctypes.addressof(row), None if kwargs.get('noEnv') else env_ptr, ctypes.addressof(snapshot))
            test.assertEqual(snapshot[0], 0x12345678, 'preserve original placeholder return value')
            test.assertEqual(tuple(snapshot)[1:8], (0x3333444455556666, 0x5555666677778888,
                0x6666777788889999, 0x777788889999aaaa, 0 if kwargs.get('noEnv') else env_ptr,
                0xeeee111122223333, 0xffff222233334444))
            test.assertEqual(snapshot[8], snapshot[9], 'stack and caller nonvolatile registers survive')
            test.assertEqual(row.raw, old_row, 'bridge never overwrites Replay actor identity')
            test.assertEqual(events[0], ('placeholder', ctypes.addressof(row), 0x43214321, 0xaaaaaa))
            test.assertFalse(refs, 'all bridge-owned JNI local references released')
            test.assertEqual(state['pending'], bool(kwargs.get('pending')), 'preexisting exception survives')
            test.assertFalse(state['locked'])
            for e in events:
                if e[0] in ('check', 'clear', 'delete', 'new', 'method', 'call', 'length', 'region'):
                    test.assertEqual(e[1], env_ptr)
                if e[0] == 'method': test.assertEqual(e[2:], (0x12340000, b'nativeReplayProfile', b'(Ljava/lang/String;)Ljava/lang/String;'))
                if e[0] == 'call': test.assertEqual(e[2:], (0x12340000, 0x55, name))
                if e[0] == 'queue': test.assertEqual(e[1:], (expected_uuid, True))
                if e[0] == 'get': test.assertEqual((e[1], e[3]), (expected_uuid, False))
                if e[0] == 'get': test.assertEqual(e[4], (15,)*len(spec['strings']), 'all Stats strings constructed as valid empty SSO')
                if e[0] == 'format': test.assertEqual(e[1:], (ctypes.addressof(row), state['stats'], mode, None))
                if e[0] == 'destroy': test.assertEqual(e[1], state['stats'])
                if e[0] == 'nick': test.assertEqual(e[1:], ('\u00a7c[NICK]', 0xff5555, ctypes.addressof(row)))
                if e[0] in ('lock', 'unlock'): test.assertEqual(e[1], base+spec['mutex'])
            return [e[0] for e in events]
        for args in ({'replay':0}, {'noEnv':True}, {'noGui':True}, {'pending':True},
                     {'name':b''}, {'name':b'Invalid-name'}, {'name':b'12345678901234567'}):
            with test.subTest(profile=profile, scenario=args):
                names = run_case(**args)
                test.assertNotIn('queue', names); test.assertNotIn('get', names)
                if args.get('pending'): test.assertNotIn('clear', names)
        for failure in ('new-null','new-exception','new-ref-exception','method-null','method-exception',
                        'call-null','call-exception','call-ref-exception','length-exception','region-exception'):
            with test.subTest(profile=profile, failure=failure):
                names = run_case(failure=failure)
                test.assertNotIn('queue', names); test.assertNotIn('get', names)
                test.assertEqual(names.count('clear'), int(failure.endswith('exception')))
        invalid_profiles = ['', 'nick', 'Nick', 'NICK ', ' NICK', 'NICK\x00', 'NICK|',
            'N1CK', 'NICK\u00a7r', '\uff2e\uff29\uff23\uff2b',
            '|'+expected_uuid, 'Bad-Name|'+expected_uuid,
            '12345678901234567|'+expected_uuid, 'RealAccount|'+expected_uuid.replace('-4cde-', '-1cde-'),
            'RealAccount|'+expected_uuid.replace('-8fab-', '-cfab-'),
            'RealAccount|'+expected_uuid.replace('89ab', '89az'),
            'RealAccount|'+expected_uuid.replace('-', '', 1), 'RealAccount|'+expected_uuid+'x',
            'RealAccount|'+expected_uuid[:-1], '\u00e9|'+expected_uuid]
        for value in invalid_profiles:
            with test.subTest(profile=profile, malformed=value):
                names = run_case(profile=value)
                test.assertNotIn('queue', names); test.assertNotIn('get', names)
                test.assertNotIn('nick', names)
        for mode in (0,1,2,3,4):
            with test.subTest(profile=profile, nickMode=mode):
                names = run_case(profile='NICK', mode=mode)
                test.assertEqual(names.count('placeholder'), 2)
                test.assertEqual(names.count('nick'), 1)
                for forbidden in ('lock','queue','unlock','get','format','destroy'):
                    test.assertNotIn(forbidden, names, 'confirmed Nick skips statistics work')
                test.assertEqual(names.count('delete'), 2, 'both JNI refs released for Nick result')
        for failure in ('call-exception','call-ref-exception','length-exception','region-exception'):
            with test.subTest(profile=profile, nickFailure=failure):
                names = run_case(profile='NICK', failure=failure)
                test.assertNotIn('nick', names)
                test.assertNotIn('queue', names)
        names = run_case(profile='NICK', pending=True)
        test.assertNotIn('nick', names); test.assertNotIn('clear', names)
        names = run_case(profile='NICK', replay=0)
        test.assertEqual(names, ['placeholder'], 'ordinary path does not consume Replay result')
        for mode, ready_index in ((0,0),(1,0),(2,1),(3,2),(4,2)):
            for ready, hit in ((True,1),(False,1),(True,0)):
                with test.subTest(profile=profile, mode=mode, ready=ready, hit=hit):
                    names = run_case(mode=mode, ready=[spec['ready'][ready_index]] if ready else [], cacheHit=hit)
                    test.assertEqual([v for v in names if v in ('lock','queue','unlock','get','format','destroy')],
                        ['lock','queue','unlock','get']+(['format'] if ready and hit else [])+['destroy'])
        for mode,ready_index in ((0,0),(1,0),(2,1),(3,2),(4,2)):
            for ready,hit in ((True,1),(False,1),(True,0)):
                with test.subTest(profile=profile,grayMode=mode,ready=ready,hit=hit):
                    names=run_case(color=0xaaaaaa,mode=mode,
                        ready=[spec['ready'][ready_index]] if ready else [],cacheHit=hit)
                    test.assertEqual([v for v in names if v in ('lock','queue','unlock','get','format','destroy')],
                        ['get']+(['format'] if ready and hit else [])+['destroy'],
                        'gray keeps cached stats rendering and skips queue/locks even for a cache miss')
            with test.subTest(profile=profile,grayNickMode=mode):
                names=run_case(color=0xaaaaaa,profile='NICK',mode=mode)
                test.assertEqual(names.count('nick'),1,'gray retains the known Nick label')
                for forbidden in ('lock','queue','unlock','get','format','destroy'):
                    test.assertNotIn(forbidden,names)
        for color in (0x555555,0xffffff,0xff5555,0,0xaaaaa9,0xaaaaab,0xffaaaaaa):
            with test.subTest(profile=profile,resumedColor=hex(color)):
                names=run_case(color=color)
                test.assertEqual([v for v in names if v in ('lock','queue','unlock','get','format','destroy')],
                    ['lock','queue','unlock','get','format','destroy'],
                    'all non-light-gray colors retain ordinary request and cached rendering behavior')
        names = run_case(name=b'1234567890123456')
        test.assertIn('format', names, '16-character heap-backed profile name works')
        names = run_case(lockError=5)
        test.assertNotIn('queue', names); test.assertNotIn('unlock', names)
        # Invoke only our cleanup handler against synthetic exception/frame memory.
        cleanup = ctypes.WINFUNCTYPE(I, P, P, P, P)(base+section['rva']+meta['replayStatsCleanup'])
        frame, exception = ctypes.create_string_buffer(64), ctypes.create_string_buffer(152)
        for flags, held, expected in ((0,1,0),(2,0,0),(2,1,1),(4,1,1),(6,1,1)):
            events.clear(); state['locked'] = bool(held)
            struct.pack_into('<I', exception, 4, flags)
            struct.pack_into('<I', frame, 0x20, held)
            test.assertEqual(cleanup(ctypes.addressof(exception), ctypes.addressof(frame), None, None), 1)
            test.assertEqual(sum(e[0]=='unlock' for e in events), expected)
            if expected:
                cleanup(ctypes.addressof(exception), ctypes.addressof(frame), None, None)
                test.assertEqual(len(events), 1, 'handler releases only the lock owned by this frame, once')
        frame_cleanup = ctypes.WINFUNCTYPE(I, P, P, P, P)(base+section['rva']+meta['replayStatsFrameCleanup'])
        main_frame = ctypes.create_string_buffer(0x2d0)
        for flags, constructed in ((0,1),(2,0),(2,1),(4,1)):
            events.clear(); refs.clear(); refs.update({0x2100:b'Actor', 0x2200:'RealAccount|'+expected_uuid})
            struct.pack_into('<I', exception, 4, flags)
            struct.pack_into('<I', main_frame, 0x40, constructed)
            struct.pack_into('<QQQ', main_frame, 0x48, env_ptr, 0x2100, 0x2200)
            test.assertEqual(frame_cleanup(ctypes.addressof(exception), ctypes.addressof(main_frame), None, None), 1)
            if flags:
                expected = ([('destroy', ctypes.addressof(main_frame)+0x140)] if constructed else [])
                expected += [('delete', env_ptr, 0x2200), ('delete', env_ptr, 0x2100)]
                test.assertEqual(events, expected)
                test.assertFalse(refs)
                frame_cleanup(ctypes.addressof(exception), ctypes.addressof(main_frame), None, None)
                test.assertEqual(events, expected, 'main unwind cleanup destroys Stats and refs exactly once')
            else:
                test.assertFalse(events)
        check_unwind(test, base, report)
    finally:
        k32.VirtualFree(base, 0, 0x8000)


def check_unwind(test, base, report):
    unwind = ctypes.WinDLL('ntdll').RtlVirtualUnwind
    unwind.argtypes = [ctypes.c_uint32, ctypes.c_uint64, ctypes.c_uint64, ctypes.c_void_p,
                      ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    unwind.restype = ctypes.c_void_p
    frames = {'replayStats': (0x2d0, [15,14,13,12,7,6,3]),
              'replayStatsQueue': (0x30, [3]), 'replayStatsCleanup': (0x28, []),
              'replayStatsFrameCleanup': (0x20, [3])}
    offsets = {3:0x90, 6:0xa8, 7:0xb0, 12:0xd8, 13:0xe0, 14:0xe8, 15:0xf0}
    for entry in report['bridgeRuntimeFunctions']:
        if entry['name'] not in frames: continue
        allocation, saved = frames[entry['name']]
        function = (ctypes.c_uint32*3)(entry['begin'], entry['end'], entry['unwind'])
        stack, context = ctypes.create_string_buffer(2048), ctypes.create_string_buffer(1232)
        sp = ctypes.addressof(stack)+128
        for i, reg in enumerate(saved): struct.pack_into('<Q', stack, 128+allocation+i*8, 0xdead0000+reg)
        return_ip = 0x7ffefedc1234
        struct.pack_into('<Q', stack, 128+allocation+len(saved)*8, return_ip)
        struct.pack_into('<I', context, 0x30, 0x00100003)
        struct.pack_into('<Q', context, 0x98, sp)
        pc = base+entry['begin']+ctypes.c_ubyte.from_address(base+entry['unwind']+1).value
        struct.pack_into('<Q', context, 0xf8, pc)
        handler_data, frame = ctypes.c_void_p(), ctypes.c_uint64()
        handler = unwind(2, base, pc, ctypes.byref(function), context, ctypes.byref(handler_data), ctypes.byref(frame), None)
        handlers = {'replayStats':'replayStatsFrameCleanup', 'replayStatsQueue':'replayStatsCleanup'}
        expected_handler = base+report['section']['rva']+report['metadata'][handlers[entry['name']]] if entry['name'] in handlers else None
        test.assertEqual(handler, expected_handler)
        test.assertEqual(frame.value, sp)
        test.assertEqual(struct.unpack_from('<Q', context, 0x98)[0], sp+allocation+len(saved)*8+8)
        test.assertEqual(struct.unpack_from('<Q', context, 0xf8)[0], return_ip)
        for reg in saved: test.assertEqual(struct.unpack_from('<Q', context, offsets[reg])[0], 0xdead0000+reg)
