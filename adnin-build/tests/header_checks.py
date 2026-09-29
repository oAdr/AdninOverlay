"""Header-only bridges: pinned PE callsites and authored x64 code with fake JNI."""
import ctypes
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import pefile
import native_headers


def verify_pe(test, before, final, report, profile):
    old, new = pefile.PE(data=before), pefile.PE(data=final)
    item = report['headerLocalization']
    test.assertEqual(item, native_headers.reviewed_patches(old, profile, report['section']['rva'], report['metadata']))
    test.assertTrue(item['titleOnly'] and item['fitToColumnWidth'])
    test.assertFalse(item['nativeColumnLayoutChanged'])
    test.assertEqual(len(item['patches']), 2)
    for patch in item['patches']:
        at = patch['siteRva']
        test.assertEqual(old.get_data(at, 6), native_headers.BEFORE)
        test.assertEqual(new.get_data(at, 6), bytes.fromhex(patch['after']))
        test.assertEqual(new.get_data(at - 6, 6), old.get_data(at - 6, 6))
        test.assertEqual(new.get_data(at + 6, 8), old.get_data(at + 6, 8))
        for delta in range(-6, 14):
            changed = bytearray(before)
            changed[old.get_offset_from_rva(at + delta)] ^= 1
            with test.subTest(profile=profile, site=hex(at), changed=delta), test.assertRaisesRegex(ValueError, 'Overlay header'):
                native_headers.reviewed_patches(pefile.PE(data=bytes(changed)), profile, report['section']['rva'], report['metadata'])


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
    sub rsp,0x28
    mov r15,[rsp+0x90]
    mov rdi,r8
    mov r12,r9
    mov rbx,0x3333333333333333
    mov rbp,0x5555555555555555
    mov rsi,0x6666666666666666
    mov r13,0xdddddddddddddddd
    mov r14,0xeeeeeeeeeeeeeeee
    call [target]
    mov [r15],rax
    mov [r15+8],rbx
    mov [r15+16],rbp
    mov [r15+24],rsi
    mov [r15+32],rdi
    mov [r15+40],r12
    mov [r15+48],r13
    mov [r15+56],r14
    add rsp,0x28
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
        test.skipTest('Windows x64 private mock execution')
    P = ctypes.c_void_p
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.VirtualAlloc.argtypes = [P, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32]
    kernel.VirtualAlloc.restype = P
    kernel.VirtualFree.argtypes = [P, ctypes.c_size_t, ctypes.c_uint32]
    kernel.FlushInstructionCache.argtypes = [P, P, ctypes.c_size_t]
    base = kernel.VirtualAlloc(None, report['imageSize'], 0x3000, 0x40)
    test.assertTrue(base)
    section, meta = report['section'], report['metadata']
    ctypes.memmove(base + section['rva'], final[section['offset']:section['offset'] + section['size']], section['size'])
    gui = ctypes.c_uint64.from_address(base + (0x1a63a8 if profile == 'lunar' else 0x17e428))
    gui.value = 0x12340000
    table = (P * 233)()
    env = (P * 1)(ctypes.addressof(table))
    env_ptr = ctypes.addressof(env)
    keep, events, refs, callback_errors = [], [], {}, []
    state = dict(mode='translated', pending=False, sequence=0)

    def bind(offset, result, args, function):
        def checked(*values):
            try:
                test.assertEqual(values[0], env_ptr, 'Actual JNIEnv reaches every JNI call')
                return function(*values)
            except Exception as error:
                # ctypes otherwise prints and swallows callback assertion failures.
                callback_errors.append((function.__name__, repr(error)))
                return None if result is None or result is P else 0
        callback = ctypes.WINFUNCTYPE(result, *args)(checked)
        keep.append(callback)
        table[offset // 8] = ctypes.cast(callback, P).value

    def reference(text):
        state['sequence'] += 1
        result = 0x4000 + state['sequence']
        refs[result] = text
        return result

    def check(env):
        test.assertEqual(env, env_ptr)
        return int(state['pending'])

    def clear(env):
        events.append(('clear',))
        state['pending'] = False

    def delete(env, ref):
        events.append(('delete', ref))
        test.assertIn(ref, refs, 'No duplicate local-ref deletion')
        refs.pop(ref, None)

    def new_string(env, raw):
        text = raw.decode('utf8')
        events.append(('new', text))
        mode = state['mode']
        if mode in ('new-error', 'new-ref-error'):
            state['pending'] = True
        if mode in ('new-null', 'new-error'):
            return None
        return reference(text)

    def method(env, clazz, name, signature):
        events.append(('method', name.decode(), signature.decode()))
        test.assertEqual(clazz, gui.value)
        if state['mode'] == 'lookup-error':
            state['pending'] = True
        return None if state['mode'] in ('lookup-null', 'lookup-error') else 0x5511

    def callback(env, clazz, method, args):
        ref, font, width = struct.unpack('<QQi', ctypes.string_at(args, 20))
        events.append(('callback', refs[ref], font, width))
        test.assertEqual(method, 0x5511)
        mode = state['mode']
        if mode in ('callback-error', 'callback-ref-error'):
            state['pending'] = True
        if mode in ('null-result', 'callback-error'):
            return None
        if mode == 'same-ref':
            return ref
        return reference(refs[ref] if mode == 'same-object' else '' if mode == 'empty' else 'translated:' + refs[ref])

    bind(0x720, ctypes.c_ubyte, [P], check)
    bind(0x88, None, [P], clear)
    bind(0xb8, None, [P, P], delete)
    bind(0x538, P, [P, ctypes.c_char_p], new_string)
    bind(0x388, P, [P, P, ctypes.c_char_p, ctypes.c_char_p], method)
    bind(0x3a0, P, [P, P, P, P], callback)
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-header-abi-') as directory:
            source, binary = Path(directory) / 'caller.asm', Path(directory) / 'caller.bin'
            source.write_text('%define TARGET ' + hex(base + section['rva'] + meta['header']) + '\n' + CALLER, encoding='ascii')
            built = subprocess.run([str(nasm), '-f', 'bin', '-Ox', str(source), '-o', str(binary)], capture_output=True, text=True)
            test.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            data = binary.read_bytes()
            ctypes.memmove(base + 0x1000, data, len(data))
        kernel.FlushInstructionCache(P(-1), base, report['imageSize'])
        run = ctypes.WINFUNCTYPE(P, P, P, P, P, P)(base + 0x1000)
        column = ctypes.create_string_buffer(b'X' * 0x58, 0x58)
        struct.pack_into('<i', column, 0x44, 48)
        original = bytes(column)
        font = 0x12345670
        raw = ctypes.create_string_buffer(b'Name')

        def invoke(mode='translated', pending=False, missing_gui=False, missing_env=False):
            events.clear(); refs.clear(); callback_errors.clear()
            state.update(mode=mode, pending=pending)
            gui.value = 0 if missing_gui else 0x12340000
            snapshot = ctypes.create_string_buffer(64)
            result = run(None if missing_env else env_ptr, raw, column, font, snapshot)
            test.assertFalse(callback_errors, 'JNI mock callback assertions: ' + repr(callback_errors))
            test.assertEqual(struct.unpack_from('<7Q', snapshot, 8),
                (0x3333333333333333, 0x5555555555555555, 0x6666666666666666,
                 ctypes.addressof(column), font, 0xdddddddddddddddd, 0xeeeeeeeeeeeeeeee))
            test.assertEqual(bytes(column), original, 'Column title, width and offset untouched')
            text = refs.get(result)
            if result:
                test.assertEqual(set(refs), {result}, 'Exactly one caller-owned local ref remains')
                delete(env_ptr, result)
            test.assertFalse(refs)
            return result, text

        # The native measurement and draw sites must receive the same chosen title.
        first = invoke()[1]
        test.assertEqual(first, 'translated:Name')
        test.assertEqual(invoke()[1], first)
        test.assertIn(('method', 'nativeOverlayHeader', '(Ljava/lang/String;Ljava/lang/Object;I)Ljava/lang/String;'), events)
        test.assertIn(('callback', 'Name', font, 48), events)
        for mode, expected in (('same-ref', 'Name'), ('same-object', 'Name'), ('empty', ''),
                ('null-result', 'Name'), ('lookup-null', 'Name'), ('lookup-error', 'Name'),
                ('callback-error', 'Name'), ('callback-ref-error', 'Name')):
            with test.subTest(profile=profile, mode=mode):
                test.assertEqual(invoke(mode)[1], expected)
                test.assertFalse(state['pending'])
                test.assertEqual(sum(e[0] == 'clear' for e in events), int('error' in mode))
        test.assertEqual(invoke(pending=True)[1], 'Name')
        test.assertTrue(state['pending'])
        test.assertFalse(any(e[0] in ('method', 'callback', 'clear') for e in events))
        test.assertEqual(invoke(missing_gui=True)[1], 'Name')
        test.assertFalse(any(e[0] in ('method', 'callback') for e in events))
        test.assertEqual(invoke(missing_env=True), (None, None))
        test.assertFalse(events)
        for mode in ('new-null', 'new-error', 'new-ref-error'):
            test.assertEqual(invoke(mode)[1], 'Name' if mode == 'new-ref-error' else None)
            test.assertEqual(state['pending'], 'error' in mode)
            test.assertFalse(any(e[0] in ('clear', 'method', 'callback') for e in events))
    finally:
        kernel.VirtualFree(base, 0, 0x8000)
