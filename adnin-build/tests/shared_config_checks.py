"""Pinned legacy save caller and authored callback execution in private mock memory."""
import ctypes
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import pefile
import header_checks
import native_shared_config
import replay_denick_checks


def verify(test, before, final, report, profile):
    old, new = pefile.PE(data=before), pefile.PE(data=final)
    item = report['sharedConfiguration']
    test.assertEqual(item, native_shared_config.reviewed_hook(old, profile, report['section']['rva'], report['metadata']))
    site, target = native_shared_config.PROFILES[profile]
    test.assertEqual(new.get_data(site, 5), b'\xe8' + struct.pack('<i', item['bridgeTargetRva'] - site - 5))
    test.assertTrue(item['originalWriterRetired'])
    test.assertFalse(item['synchronousDiskWrites'])
    test.assertEqual(old.get_data(target, 64), new.get_data(target, 64))
    for delta in range(-6, 10):
        changed = bytearray(before)
        changed[old.get_offset_from_rva(site + delta)] ^= 1
        with test.assertRaisesRegex(ValueError, 'config-save'):
            native_shared_config.reviewed_hook(pefile.PE(data=bytes(changed)), profile, report['section']['rva'], report['metadata'])


def execute(test, final, report, profile, nasm):
    if os.name != 'nt' or ctypes.sizeof(ctypes.c_void_p) != 8:
        test.skipTest('Windows x64 owned JNI fixture')
    P = ctypes.c_void_p
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.VirtualAlloc.argtypes = [P, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32]
    kernel.VirtualAlloc.restype = P
    kernel.VirtualFree.argtypes = [P, ctypes.c_size_t, ctypes.c_uint32]
    kernel.FlushInstructionCache.argtypes = [P, P, ctypes.c_size_t]
    base = kernel.VirtualAlloc(None, report['imageSize'], 0x3000, 0x40)
    test.assertTrue(base)
    section = report['section']
    ctypes.memmove(base + section['rva'], final[section['offset']:section['offset'] + section['size']], section['size'])
    gui = ctypes.c_uint64.from_address(base + (0x1a63a8 if profile == 'lunar' else 0x17e428))
    table = (P * 233)()
    env = (P * 1)(ctypes.addressof(table))
    env_ptr = ctypes.addressof(env)
    keep, events, errors = [], [], []
    state = dict(pending=False, failure=None)
    def method(actual_env, clazz, name, sig):
        events.append(('method', name, sig))
        if state['failure'] in ('method-null', 'method-error'):
            state['pending'] = state['failure'] == 'method-error'
            return None
        return 1 if name == b'nativeRequestConfigSave' and sig == b'()I' else None
    def callback(actual_env, clazz, mid, args):
        try:
            test.assertEqual((actual_env, clazz, mid, args), (env_ptr, 0x12340000, 1, None))
            events.append(('save',))
            if state['failure'] == 'callback-error': state['pending'] = True
            return 1
        except Exception as failure:
            errors.append(str(failure)); return 0
    def check(actual_env): return state['pending']
    def clear(actual_env): state['pending'] = False; events.append(('clear',))
    for offset, result, args, fn in ((0x720, ctypes.c_ubyte, [P], check),
                                    (0x88, None, [P], clear),
                                    (0x388, P, [P, P, ctypes.c_char_p, ctypes.c_char_p], method),
                                    (0x418, ctypes.c_int, [P, P, P, P], callback)):
        cb = ctypes.WINFUNCTYPE(result, *args)(fn)
        keep.append(cb); table[offset // 8] = ctypes.cast(cb, P).value
    try:
        with tempfile.TemporaryDirectory(prefix='adnin-config-jni-') as directory:
            source, binary = Path(directory) / 'caller.asm', Path(directory) / 'caller.bin'
            source.write_text('%define TARGET ' + hex(base + section['rva'] + report['metadata']['configSave'])
                              + '\n' + header_checks.CALLER, encoding='ascii')
            result = subprocess.run([str(nasm), '-f', 'bin', '-Ox', str(source), '-o', str(binary)], capture_output=True, text=True)
            test.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            data = binary.read_bytes(); ctypes.memmove(base + 0x1000, data, len(data))
        kernel.FlushInstructionCache(P(-1), base, report['imageSize'])
        run = ctypes.WINFUNCTYPE(P, P, P, P, P, P)(base + 0x1000)
        for failure in (None, 'missing-env', 'missing-gui', 'pending', 'method-null', 'method-error', 'callback-error', None):
            state.update(pending=failure == 'pending', failure=failure); events.clear()
            gui.value = 0 if failure == 'missing-gui' else 0x12340000
            snapshot = ctypes.create_string_buffer(64)
            returned = run(None if failure == 'missing-env' else env_ptr, 0x2000, 0x3000, 0x4000, snapshot)
            test.assertEqual(returned, 1 if failure is None else None)
            test.assertEqual(struct.unpack_from('<7Q', snapshot, 8), (0x3333333333333333, 0x5555555555555555,
                0x6666666666666666, 0x3000, 0x4000, 0xdddddddddddddddd, 0xeeeeeeeeeeeeeeee))
            test.assertEqual(events.count(('save',)), int(failure in (None, 'callback-error')))
            test.assertEqual(state['pending'], failure == 'pending')
            test.assertFalse(errors)
        replay_denick_checks.check_simple_unwind(test, base, report)
    finally:
        kernel.VirtualFree(base, 0, 0x8000)
