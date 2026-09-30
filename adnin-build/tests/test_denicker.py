"""Bot-to-native-statistics bridge regression with a private mock x64 image.

Only the newly assembled bridge executes. Original functions are Python-backed
ABI stubs; the game, original DLL and network are never loaded or contacted.
"""
import ctypes
import os
import struct
import sys
import unittest

import test_bridge

UUID = '12345678-1234-4567-89ab-123456789abc'
NICK_UUID = '12345678-1234-1567-89ab-123456789abc'


@unittest.skipUnless(os.name == 'nt' and ctypes.sizeof(ctypes.c_void_p) == 8, 'Windows x64 mock execution')
class DenickerExecutionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        test_bridge.BridgeExecutionTests.setUpClass()

    def setUp(self):
        self.h = h = test_bridge.BridgeExecutionTests(methodName='runTest')
        h.setUp()
        self.profile = 'Adnin|' + UUID
        self.original_name = ''
        self.original_return = 0
        self.original_ready = False
        self.skin_enabled = False
        self.skin_name = ''
        self.skin_status = 0
        self.cache_found = True
        self.ready_mode = 8
        self.callback_errors = []
        self.native_events = []
        self.replay_nick = 0
        self.next_ref = 0x200000
        h.methods['nativeDenickerProfile'] = 9
        h.methods['nativeDenickerPublished'] = 11
        h.methods['nativeReplayIsNick'] = 10
        P, I = ctypes.c_void_p, ctypes.c_int

        def number(nick, result):
            self.native_events.append(('number', self.read_string(nick)))
            if self.original_name:
                ctypes.c_ubyte.from_address(result).value = 1
                self.assign_string(result + 0x20, self.original_name)
                ctypes.c_ubyte.from_address(result + 0x160).value = self.original_ready
            return self.original_return

        def classify(value):
            text = self.read_string(value)
            self.native_events.append(('classify', text))
            return int(text[14], 16) if len(text) > 14 else 0

        def skin_lookup(name, target):
            if any(ctypes.string_at(target, 0x218)):
                self.callback_errors.append('Skin output was not initialized before native getter')
            self.native_events.append(('skin-read', self.read_string(name)))
            ctypes.c_int.from_address(target).value = self.skin_status
            if self.skin_name:
                self.assign_string(target + 8, self.skin_name)
            return int(self.skin_status != 0)

        def skin_destroy(target):
            self.native_events.append(('skin-destroy', self.read_string(target + 8)))

        def register(name, level):
            self.native_events.append(('register', self.read_string(name), level))

        def queue(name, uuid):
            self.native_events.append(('queue', self.read_string(name), self.read_string(uuid)))

        def stats(uuid, target):
            self.native_events.append(('cache', self.read_string(uuid)))
            if not self.cache_found:
                return 0
            block = bytearray(0x120)
            if self.ready_mode is not None:
                block[self.ready_mode] = 1
            struct.pack_into('<I', block, 0x18, 23456)
            struct.pack_into('<I', block, 0x20, 789)
            ctypes.memmove(target, bytes(block), len(block))
            return 1

        def assign(target, text):
            self.native_events.append(('name', text.decode('ascii')))
            self.assign_string(target, text.decode('ascii'))

        def candidates(target, source, count):
            self.native_events.append(('candidates', self.read_string(source), count))
            allocation = ctypes.create_string_buffer(32)
            h.keep.append(allocation)
            ctypes.memmove(allocation, source, 32)
            start = ctypes.addressof(allocation)
            for offset, value in ((0, start), (8, start + 32), (16, start + 32)):
                ctypes.c_uint64.from_address(target + offset).value = value

        def object_call(env, clazz, method, args):
            ref = ctypes.c_uint64.from_address(args).value
            self.native_events.append(('profile', h.refs[ref]))
            self.native_events.append(('skin-enabled', bool(ctypes.c_uint64.from_address(args + 8).value)))
            if h.failure == 'profile-exception':
                h.pending = True
                return None
            if h.failure == 'profile-null':
                return None
            self.next_ref += 1
            h.refs[self.next_ref] = self.profile
            return self.next_ref

        def length(env, ref):
            if h.failure == 'length-exception':
                h.pending = True
            return len(h.refs[ref].encode('utf-16-le')) // 2

        def region(env, ref, start, count, target):
            if h.failure == 'region-exception':
                h.pending = True
                return
            value = h.refs[ref][start:start + count].encode('utf8')
            ctypes.memmove(target, value, len(value))

        def replay_nick(env, clazz, method, args):
            self.native_events.append(('replay-nick', h.refs[ctypes.c_uint64.from_address(args).value]))
            if h.failure == 'callback-exception': h.pending = True
            return self.replay_nick

        def mutex(kind):
            def call(pointer):
                self.native_events.append((kind, pointer))
                return 0
            callback = ctypes.WINFUNCTYPE(I, P)(call)
            h.keep.append(callback)
            return ctypes.cast(callback, P).value

        self.install(0xba530, P, [P, P], number)
        self.install(0x7bf80, I, [P], classify)
        self.install(0xa73a0, ctypes.c_ubyte, [], lambda: self.skin_enabled)
        self.install(0xa5f80, ctypes.c_ubyte, [P, P], skin_lookup)
        self.install(0x58d80, None, [P], skin_destroy)
        self.install(0x910b0, None, [P, I], register)
        self.install(0x9a620, None, [P, P], queue)
        self.install(0x9d570, None, [P], lambda uuid: self.native_events.append(('replay-queue', self.read_string(uuid))))
        ctypes.c_uint64.from_address(h.base + 0x12f4d0).value = mutex('lock')
        ctypes.c_uint64.from_address(h.base + 0x12f4d8).value = mutex('unlock')
        self.install(0x98e50, ctypes.c_ubyte, [P, P], stats)
        self.install(0x7300, None, [P, ctypes.c_char_p], assign)
        self.install(0xb4fb0, None, [P, P, ctypes.c_uint64], candidates)
        h.bind(0x3a0, P, [P, P, P, P], object_call)
        h.bind(0x478, None, [P, P, P, P], lambda env, clazz, method, args: self.native_events.append(('published', h.refs[ctypes.c_uint64.from_address(args).value], h.refs[ctypes.c_uint64.from_address(args+8).value])))
        h.bind(0x520, I, [P, P], length)
        h.bind(0x6e8, None, [P, P, I, I, P], region)
        h.bind(0x418, I, [P, P, P, P], replay_nick)
        # Verified rowbuilder keeps JNIEnv in R13; public mock takes env/name/out.
        address = h.base + h.report['section']['rva'] + h.report['metadata']['denicker']
        caller = bytes.fromhex('41 55 48 83 ec 20 49 89 cd 48 89 d1 4c 89 c2 48 b8')
        caller += struct.pack('<Q', address) + bytes.fromhex('ff d0 48 83 c4 20 41 5d c3')
        ctypes.memmove(h.base + 0x1100, caller, len(caller))
        h.k32.FlushInstructionCache(ctypes.c_void_p(-1), h.base, h.report['imageSize'])

    def tearDown(self):
        self.h.tearDown()

    def install(self, rva, result, args, callback):
        native = ctypes.WINFUNCTYPE(result, *args)(callback)
        self.h.keep.append(native)
        code = b'\x48\xb8' + struct.pack('<Q', ctypes.cast(native, ctypes.c_void_p).value) + b'\xff\xe0'
        ctypes.memmove(self.h.base + rva, code, len(code))

    def read_string(self, pointer):
        size = ctypes.c_uint64.from_address(pointer + 16).value
        capacity = ctypes.c_uint64.from_address(pointer + 24).value
        data = ctypes.c_uint64.from_address(pointer).value if capacity > 15 else pointer
        return ctypes.string_at(data, size).decode('utf8') if size else ''

    def assign_string(self, target, text):
        value = self.h.string(text)
        ctypes.memmove(target, value, 32)

    def run_denicker(self, name='isa5', uuid=NICK_UUID, mode=0):
        row = ctypes.create_string_buffer(0x178)
        result = ctypes.create_string_buffer(0x168)
        self.h.keep.extend((row, result))
        self.assign_string(ctypes.addressof(row) + 0x40, name)
        self.assign_string(ctypes.addressof(row) + 0x148, uuid)
        before = bytes(row)
        ctypes.c_ubyte.from_address(self.h.base + 0x1a63b8).value = mode
        caller = ctypes.WINFUNCTYPE(ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)(self.h.base + 0x1100)
        returned = caller(self.h.env_ptr, ctypes.addressof(row) + 0x40, ctypes.addressof(result))
        self.assertEqual(bytes(row), before, 'Game row identity was modified')
        self.assertFalse(self.callback_errors)
        self.assertEqual(self.h.refs, {}, 'JNI local references leaked')
        return (returned or 0), result

    def calls(self, kind):
        return [item for item in self.native_events if item[0] == kind]

    def test_real_stats_use_original_queue_cache_and_owned_result(self):
        returned, result = self.run_denicker()
        self.assertEqual(returned, 1)
        self.assertEqual(self.calls('register'), [('register', 'Adnin', -1)])
        self.assertEqual(self.calls('queue'), [('queue', 'Adnin', UUID)])
        self.assertEqual(self.calls('cache'), [('cache', UUID)])
        self.assertEqual(self.read_string(ctypes.addressof(result) + 0x20), 'Adnin')
        self.assertEqual(result.raw[:3], b'\1\0\0')
        self.assertEqual(struct.unpack_from('<I', result, 4)[0], 1)
        self.assertEqual(struct.unpack_from('<I', result, 0x58)[0], 23456)
        self.assertEqual(result.raw[0x160], 1)
        self.assertEqual(self.calls('candidates'), [('candidates', 'Adnin', 1)])

    def test_replay_bot_reuses_stats_without_learning_actor_uuid_or_nick_history(self):
        self.replay_nick = 1
        returned, result = self.run_denicker(uuid=NICK_UUID[:14]+'2'+NICK_UUID[15:], mode=1)
        self.assertEqual(returned, 1)
        self.assertEqual(self.calls('replay-nick'), [('replay-nick', 'isa5')])
        self.assertEqual(self.calls('replay-queue'), [('replay-queue', UUID)])
        self.assertEqual(self.calls('cache'), [('cache', UUID)])
        self.assertFalse(self.calls('classify'))
        self.assertFalse(self.calls('register'))
        self.assertFalse(self.calls('queue'))
        self.assertEqual([e[0] for e in self.native_events if e[0] in ('lock','replay-queue','unlock')],
                         ['lock','replay-queue','unlock'])
        self.assertEqual(self.read_string(ctypes.addressof(result)+0x20), 'Adnin')

    def test_replay_requires_exact_cache_predicate_and_preserves_number_skin_priority(self):
        for ack in (0,2,-1):
            self.replay_nick = ack
            self.native_events.clear()
            self.assertEqual(self.run_denicker(mode=1)[0], 0)
            self.assertFalse(self.calls('profile'))
        self.replay_nick = 1
        self.original_name, self.original_return = 'NumberName', 1
        self.native_events.clear()
        self.assertEqual(self.run_denicker(mode=1)[0], 1)
        self.assertFalse(self.calls('profile'))
        self.original_name, self.original_return = '', 0
        self.skin_enabled, self.skin_status, self.skin_name = True, 3, 'SkinName'
        self.native_events.clear()
        self.assertEqual(self.run_denicker(mode=1)[0], 1)
        self.assertEqual(self.calls('skin-enabled'), [('skin-enabled', True)])
        self.assertFalse(self.calls('skin-read'))
        self.assertFalse(self.calls('skin-destroy'))
        self.assertEqual(self.calls('published'), [('published','isa5',self.profile)])

    def test_existing_number_identity_wins_even_before_stats_ready(self):
        self.original_name = 'NativeIdentity'
        self.original_return = 1
        returned, result = self.run_denicker()
        self.assertEqual(returned, 1)
        self.assertEqual(self.read_string(ctypes.addressof(result) + 0x20), self.original_name)
        self.assertFalse(self.calls('profile'))
        self.assertFalse(self.calls('queue'))

    def test_legacy_skin_identity_is_retired_and_native_setting_reaches_mellow(self):
        self.skin_enabled = True
        self.skin_status = 3
        self.skin_name = 'ObsoleteHashMatch'
        returned, _ = self.run_denicker()
        self.assertEqual(returned, 1)
        self.assertFalse(self.calls('skin-read'))
        self.assertFalse(self.calls('skin-destroy'))
        self.assertEqual(self.calls('skin-enabled'), [('skin-enabled', True)])
        self.assertEqual(self.calls('published'), [('published', 'isa5', self.profile)])

    def test_disabled_skin_still_allows_bot_fallback(self):
        self.skin_enabled = False
        returned, _ = self.run_denicker()
        self.assertEqual(returned, 1)
        self.assertEqual(self.calls('skin-enabled'), [('skin-enabled', False)])
        self.assertFalse(self.calls('skin-read'))

    def test_native_bot_npc_and_other_uuid_classes_never_query(self):
        for version in ('2', '3', '4', '0'):
            with self.subTest(version=version):
                self.native_events.clear()
                returned, _ = self.run_denicker(uuid=NICK_UUID[:14] + version + NICK_UUID[15:])
                self.assertEqual(returned, 0)
                self.assertFalse(self.calls('profile'))
                self.assertFalse(self.calls('register'))
                self.assertFalse(self.calls('queue'))
        self.native_events.clear()
        self.run_denicker(mode=1)
        self.assertFalse(self.calls('profile'))

    def test_invalid_nick_names_do_not_offer_api_candidates(self):
        for name in ('', '[NPC]', 'space name', 'a' * 17, 'bad/name'):
            with self.subTest(name=name):
                self.native_events.clear()
                returned, _ = self.run_denicker(name=name)
                self.assertEqual(returned, 0)
                self.assertFalse(self.calls('profile'))

    def test_empty_disabled_or_invalid_profiles_never_reach_native_api(self):
        invalid = ['', '|'+UUID, 'a'*17+'|'+UUID, 'bad/name|'+UUID, 'Name|'+UUID+'x',
                   'Name|'+UUID.replace('4567', '1567'), 'Name|'+UUID.replace('89ab','19ab'),
                   'Name|'+UUID.replace('-', ''), 'Name|'+UUID.replace('abc','abg'), '汉名|'+UUID]
        for profile in invalid:
            with self.subTest(profile=profile):
                self.profile = profile
                self.native_events.clear()
                returned, _ = self.run_denicker()
                self.assertEqual(returned, 0)
                self.assertFalse(self.calls('register'))
                self.assertFalse(self.calls('queue'))

    def test_cache_pending_or_error_does_not_publish_fake_stats(self):
        for found, flag in ((False, 8), (True, None)):
            with self.subTest(found=found, flag=flag):
                self.cache_found, self.ready_mode = found, flag
                returned, result = self.run_denicker()
                self.assertEqual(returned, 0)
                self.assertEqual(result.raw[0], 0)
                self.assertEqual(result.raw[0x160], 0)
                self.assertFalse(self.calls('published'))

    def test_each_original_supported_mode_ready_flag_is_accepted(self):
        for flag in (8, 0x60, 0xa0):
            with self.subTest(flag=flag):
                self.ready_mode = flag
                returned, result = self.run_denicker()
                self.assertEqual(returned, 1)
                self.assertEqual(result.raw[0x160], 1)

    def test_existing_exception_and_missing_class_are_untouched(self):
        self.h.pending = True
        self.run_denicker()
        self.assertTrue(self.h.pending)
        self.assertFalse(self.calls('profile'))
        self.assertFalse(any(event[0] == 'clear' for event in self.h.events))
        self.h.pending = False
        ctypes.c_uint64.from_address(self.h.base + 0x1a63a8).value = 0
        self.run_denicker()
        self.assertFalse(self.calls('profile'))

    def test_every_optional_jni_failure_preserves_original_and_cleans_refs(self):
        failures = ['new-null', 'new-exception', 'method-null', 'method-exception',
                    'profile-null', 'profile-exception', 'length-exception', 'region-exception']
        for failure in failures:
            with self.subTest(failure=failure):
                self.h.failure = failure
                self.h.pending = False
                self.native_events.clear()
                returned, _ = self.run_denicker()
                self.assertEqual(returned, 0)
                self.assertFalse(self.h.pending)
                self.assertFalse(self.calls('queue'))

    def test_maximum_name_and_uuid_uppercase_validate(self):
        self.profile = 'ABCDEFGHIJKLMNOP|' + UUID.upper()
        returned, result = self.run_denicker(name='abcdefghijklmnop')
        self.assertEqual(returned, 1)
        self.assertEqual(self.read_string(ctypes.addressof(result) + 0x20), 'ABCDEFGHIJKLMNOP')

    def test_unwind_alloc_large_restores_native_frame(self):
        h = self.h
        info = next(item for item in h.report['bridgeRuntimeFunctions'] if item['name'] == 'denicker')
        runtime = (ctypes.c_uint32 * 3)(info['begin'], info['end'], info['unwind'])
        header = ctypes.string_at(h.base + info['unwind'], 24)
        self.assertEqual(header[0], 1)
        self.assertEqual(header[2], 9)
        self.assertEqual(header[5], 1)
        self.assertEqual(struct.unpack_from('<H', header, 6)[0] * 8, 0x370)
        context, stack = ctypes.create_string_buffer(1232), ctypes.create_string_buffer(2048)
        sp = ctypes.addressof(stack) + 64
        for i in range(7):
            struct.pack_into('<Q', stack, 64 + 0x370 + i * 8, 0x1000 + i)
        struct.pack_into('<Q', stack, 64 + 0x3a8, 0x7ffefedc1234)
        struct.pack_into('<Q', context, 0x98, sp)
        pc = h.base + info['begin'] + header[1]
        struct.pack_into('<Q', context, 0xf8, pc)
        unwind = ctypes.WinDLL('ntdll').RtlVirtualUnwind
        unwind.argtypes = [ctypes.c_uint32, ctypes.c_uint64, ctypes.c_uint64, ctypes.c_void_p,
                           ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
        unwind.restype = ctypes.c_void_p
        handler, frame = ctypes.c_void_p(), ctypes.c_uint64()
        unwind(0, h.base, pc, ctypes.byref(runtime), context, ctypes.byref(handler), ctypes.byref(frame), None)
        self.assertEqual(struct.unpack_from('<Q', context, 0x98)[0], sp + 0x3b0)
        self.assertEqual(struct.unpack_from('<Q', context, 0xf8)[0], 0x7ffefedc1234)


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0]] + test_bridge.UNITTEST_ARGS)
