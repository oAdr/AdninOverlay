"""Shared Replay PE and private mock-CPU checks, never loading a game DLL."""
import ctypes
import os
import struct
import subprocess
import tempfile
from pathlib import Path
import pefile
import native_replay


def verify_pe(test, before, final, report, profile):
    old, new = pefile.PE(data=before), pefile.PE(data=final)
    spec = native_replay.PROFILES[profile]
    items = report['replayOverlay']
    test.assertEqual(len(items), 4)
    test.assertEqual({v['purpose'] for v in items}, {'enabled', 'render', 'modelCache', 'fallbackRender'})
    expected = native_replay.reviewed_patches(old, profile, report['section']['rva'], report['metadata'])
    test.assertEqual(items, expected)
    for item, site in zip(items, spec['sites']):
        rva, original, callback, _, context_rva, context, continuation = site
        test.assertEqual(old.get_data(rva, len(bytes.fromhex(original))), bytes.fromhex(original))
        test.assertEqual(new.get_data(rva, len(bytes.fromhex(item['after']))), bytes.fromhex(item['after']))
        test.assertEqual(new.get_data(context_rva, len(bytes.fromhex(context))), bytes.fromhex(context))
        test.assertEqual(new.get_data(rva + len(bytes.fromhex(original)), len(bytes.fromhex(continuation))), bytes.fromhex(continuation))
        test.assertTrue(item['nativeGameStateUnchanged'])
        # All guards independently reject drift before a mutation is attempted.
        for altered in (rva, context_rva, rva + len(bytes.fromhex(original))):
            changed = bytearray(before)
            changed[old.get_offset_from_rva(altered)] ^= 1
            with test.assertRaisesRegex(ValueError, 'Replay overlay'):
                native_replay.reviewed_patches(pefile.PE(data=bytes(changed)), profile,
                                               report['section']['rva'], report['metadata'])


SNAPSHOT_ASM = r'''
bits 64
default rel
%macro SNAPSHOT 1
    mov [r12+%1+0], rax
    mov [r12+%1+8], rcx
    mov [r12+%1+16], rdx
    mov [r12+%1+24], rbx
    mov [r12+%1+32], rsp
    mov [r12+%1+40], rbp
    mov [r12+%1+48], rsi
    mov [r12+%1+56], rdi
    mov [r12+%1+64], r8
    mov [r12+%1+72], r9
    mov [r12+%1+80], r10
    mov [r12+%1+88], r11
    mov [r12+%1+96], r12
    mov [r12+%1+104], r13
    mov [r12+%1+112], r14
    mov [r12+%1+120], r15
    movdqu [r12+%1+128], xmm0
    movdqu [r12+%1+144], xmm1
    movdqu [r12+%1+160], xmm2
    movdqu [r12+%1+176], xmm3
    movdqu [r12+%1+192], xmm4
    movdqu [r12+%1+208], xmm5
    pushfq
    pop qword [r12+%1+224]
%endmacro
    push rbx
    push rbp
    push rsi
    push rdi
    push r12
    push r13
    push r14
    push r15
    sub rsp, 0x78
    mov r12, rdx
    mov rbx, 0x1011223344556600
    mov rdi, 0x7071223344556600
%ifndef FRAMEENV
    mov ENVREG, rcx
%endif
    mov rbp, 0x5051223344556600
%ifdef FRAMEENV
    lea rbp, [rsp+0x20]
    mov [rbp+0x28], rcx
%endif
    mov rsi, 0x6061223344556600
    mov r13, 0xd0d1223344556600
    mov r14, 0xe0e1223344556600
    mov r15, 0xf0f1223344556600
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
    add rsp, 0x78
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
    k32.VirtualAlloc.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32]
    k32.VirtualAlloc.restype = ctypes.c_void_p
    k32.VirtualFree.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32]
    k32.FlushInstructionCache.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t]
    base = k32.VirtualAlloc(None, report['imageSize'], 0x3000, 0x40)
    test.assertTrue(base)
    keep, events = [], []
    state = {'pending': False, 'failure': None, 'value': 0}
    section, meta = report['section'], report['metadata']
    ctypes.memmove(base + section['rva'], final[section['offset']:section['offset']+section['size']], section['size'])
    gui_rva = 0x1a63a8 if profile == 'lunar' else 0x17e428
    flag = ctypes.c_ubyte.from_address(base + native_replay.PROFILES[profile]['eligibilityRva'])
    gui = ctypes.c_uint64.from_address(base + gui_rva)
    table = (ctypes.c_void_p * 233)()
    env = (ctypes.c_void_p * 1)(ctypes.addressof(table))
    env_ptr = ctypes.addressof(env)
    P = ctypes.c_void_p

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

    def method(e, clazz, name, signature):
        events.append(('method', e, clazz, name, signature))
        if state['failure'] == 'method-exception': state['pending'] = True
        return None if state['failure'] in ('method-null', 'method-exception') else 1

    def integer(e, clazz, method_id, args):
        events.append(('integer', e, clazz, method_id, args))
        if state['failure'] == 'callback-exception': state['pending'] = True
        return state['value']

    bind(0x720, ctypes.c_ubyte, [P], check)
    bind(0x88, None, [P], clear)
    bind(0x388, P, [P, P, ctypes.c_char_p, ctypes.c_char_p], method)
    bind(0x418, ctypes.c_int, [P, P, P, P], integer)
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-replay-cpu-') as directory:
            folder = Path(directory)
            source = folder/'caller.asm'; output = folder/'caller.bin'
            source.write_text(SNAPSHOT_ASM, encoding='ascii')
            for index, (name, envreg) in enumerate((('replayRdi', 'rdi'), ('replayRbx', 'rbx'), ('replayFrame', 'frame'))):
                target = base + section['rva'] + meta[name]
                result = subprocess.run([str(nasm), '-f', 'bin', '-Ox', '-DTARGET='+hex(target),
                    ('-DFRAMEENV=1' if envreg == 'frame' else '-DENVREG='+envreg),
                    str(source), '-o', str(output)], capture_output=True, text=True)
                test.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                caller = output.read_bytes()
                site = base + 0x1000 + index*0x1000
                ctypes.memmove(site, caller, len(caller))
                k32.FlushInstructionCache(P(-1), site, len(caller))
                run = ctypes.WINFUNCTYPE(None, P, P)(site)
                cases = [(normal, replay, None, False, True, bool(normal or replay))
                         for normal in (0, 1) for replay in (0, 1)]
                cases += [(0, 1, None, True, True, False), (0, 1, None, False, False, False),
                          (0, 1, 'method-null', False, True, False),
                          (0, 1, 'method-exception', False, True, False),
                          (0, 1, 'callback-exception', False, True, False),
                          (0, 2, None, False, True, False), (0, -1, None, False, True, False),
                          (1, 0, 'callback-exception', True, False, True)]
                for normal, replay, failure, pending, present, enabled in cases:
                    with test.subTest(profile=profile, bridge=name, normal=normal, replay=replay,
                                      failure=failure, pending=pending, present=present):
                        flag.value = normal
                        gui.value = 0x12340000 if present else 0
                        state.update(pending=pending, failure=failure, value=replay)
                        events.clear()
                        snapshots = ctypes.create_string_buffer(512)
                        run(env_ptr, ctypes.addressof(snapshots))
                        test.assertEqual(snapshots.raw[:224], snapshots.raw[256:480],
                                         'all 16 integer registers, stack and XMM0-5 survive predicate')
                        zf = bool(struct.unpack_from('<Q', snapshots, 480)[0] & 0x40)
                        test.assertEqual(zf, not enabled)
                        test.assertEqual(flag.value, normal, 'native eligibility is never overwritten')
                        test.assertEqual(state['pending'], pending, 'old exception survives; new callback failure is cleared')
                        if normal or not present:
                            test.assertFalse(events)
                        for event in events:
                            test.assertEqual(event[1], env_ptr, 'JNI uses the call site register')
                            if event[0] == 'method':
                                test.assertEqual(event[2:], (0x12340000, b'nativeReplayMode', b'()I'))
                            if event[0] == 'integer':
                                test.assertEqual(event[2:], (0x12340000, 1, None))
                        if pending: test.assertFalse(any(e[0] in ('method', 'integer', 'clear') for e in events))
        check_unwind(test, base, report)
    finally:
        k32.VirtualFree(base, 0, 0x8000)


def check_unwind(test, base, report, names=('replayRdi', 'replayRbx', 'replayFrame')):
    unwind = ctypes.WinDLL('ntdll').RtlVirtualUnwind
    unwind.argtypes = [ctypes.c_uint32, ctypes.c_uint64, ctypes.c_uint64,
                      ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    unwind.restype = ctypes.c_void_p
    for entry in report['bridgeRuntimeFunctions']:
        if entry['name'] not in names: continue
        function = (ctypes.c_uint32 * 3)(entry['begin'], entry['end'], entry['unwind'])
        test.assertEqual(ctypes.string_at(base + entry['end'] - 6, 6), bytes.fromhex('488d65005dc3'))
        for position in ('body', 'lea', 'pop', 'ret'):
            with test.subTest(function=entry['name'], position=position):
                stack = ctypes.create_string_buffer(1024); context = ctypes.create_string_buffer(1232)
                sp = ctypes.addressof(stack) + 128
                saved_rbp, return_ip = 0x1122334455660000, 0x7ffefedc1234
                struct.pack_into('<QQ', stack, 128+0xf0, saved_rbp, return_ip)
                struct.pack_into('<I', context, 0x30, 0x00100003)
                actual_sp = sp if position in ('body', 'lea') else sp+0xf0+(8 if position=='ret' else 0)
                actual_rbp = saved_rbp if position == 'ret' else sp+0xf0
                pc = (entry['begin']+16 if position=='body' else entry['end']-{'lea':6,'pop':2,'ret':1}[position])
                struct.pack_into('<Q', context, 0x98, actual_sp)
                struct.pack_into('<Q', context, 0xa0, actual_rbp)
                struct.pack_into('<Q', context, 0xf8, base+pc)
                handler, frame = ctypes.c_void_p(), ctypes.c_uint64()
                unwind(0, base, base+pc, ctypes.byref(function), context,
                       ctypes.byref(handler), ctypes.byref(frame), None)
                test.assertEqual(struct.unpack_from('<Q', context, 0x98)[0], sp+0x100)
                test.assertEqual(struct.unpack_from('<Q', context, 0xa0)[0], saved_rbp)
                test.assertEqual(struct.unpack_from('<Q', context, 0xf8)[0], return_ip)
