"""Static PE checks and isolated execution of only our newly assembled bridges.

The supplied/reconstructed native DLL and Java classes are never executed.
Private mock memory contains only our bridge and Python-backed ABI stubs.
"""
import argparse
import ctypes
import json
import os
import struct
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import pefile
import bridge
import native_compat
import reembed
import native_anticheat
import replay_checks
import replay_stats_checks
import replay_denick_checks
import number_poll_checks
import api_policy_checks
import hypixel_http_checks
import header_checks
import process_entry_checks

parser = argparse.ArgumentParser(add_help=False)
parser.add_argument('--input', type=Path, required=True)
parser.add_argument('--classes', type=Path)
parser.add_argument('--nasm', type=Path, required=True)
ARGS, REST = parser.parse_known_args()
FIXTURE = None


def fixture():
    global FIXTURE
    if FIXTURE is None:
        raw = ARGS.input.read_bytes()
        records = json.loads((ROOT / 'evidence/vanilla-class-index.json').read_text())
        classes = {}
        for record in records:
            name = reembed.renamed(record['name'])
            start = int(record['offset'], 16)
            classes[name] = ((ARGS.classes / (name + '.class')).read_bytes() if ARGS.classes
                             else reembed.branded_class(raw[start:start + record['size']]))
        profile = native_compat.profile()
        if not ARGS.classes:
            # Original branded classes serve only as static PE test fixtures;
            # production compiled NewChat explicitly adds the Runnable interface.
            profile['additional_interfaces'] = {}
        relocated, remap = reembed.rebuild(raw, classes, records, profile)
        final, report = native_compat.build_bridge(relocated, ARGS.nasm)
        FIXTURE = raw, relocated, remap, final, report
    return FIXTURE


class CompatPETests(unittest.TestCase):
    def test_process_exit_guard_preserves_explicit_unload_attach_and_abi(self):
        process_entry_checks.verify(self,self.before,self.final,self.report,'vanilla')
        process_entry_checks.execute(self,self.final,self.report,'vanilla',ARGS.nasm)

    def test_headers_pin_both_string_sites_and_preserve_original_layout(self):
        header_checks.verify_pe(self, self.before, self.final, self.report, 'vanilla')

    def test_headers_actual_jni_return_ownership_fallback_and_nonvolatile_abi(self):
        header_checks.execute(self, self.final, self.report, 'vanilla', ARGS.nasm)

    @classmethod
    def setUpClass(cls):
        cls.raw, cls.before, cls.remap, cls.final, cls.report = fixture()
        cls.pe = pefile.PE(data=cls.final)

    def test_nine_active_class_loaders_and_original_abi(self):
        self.assertEqual(len(self.remap['classes']), 9)
        self.assertTrue(all(c['mode'] == 'relocated' for c in self.remap['classes']))
        self.assertIn('AdninGuiNewChat', {c['name'] for c in self.remap['classes']})
        self.assertNotIn('AdninClientPump', {c['name'] for c in self.remap['classes']})

    def test_replay_changes_only_four_local_overlay_predicates(self):
        replay_checks.verify_pe(self, self.before, self.final, self.report, 'vanilla')

    def test_replay_predicate_registers_truth_table_exceptions_and_unwind(self):
        replay_checks.execute(self, self.final, self.report, 'vanilla', ARGS.nasm)

    def test_replay_stats_verified_identity_queue_readiness_jni_and_unwind(self):
        replay_stats_checks.verify_pe(self, self.before, self.final, self.report, 'vanilla')
        replay_stats_checks.execute(self, self.final, self.report, 'vanilla', ARGS.nasm)

    def test_replay_denicker_gate_cached_identity_and_actor_uuid_guard(self):
        replay_denick_checks.verify_pe(self, self.before, self.final, self.report, 'vanilla')
        replay_denick_checks.execute(self, self.final, self.report, 'vanilla', ARGS.nasm)

    def test_number_periodic_polling_defers_busy_worker_without_dropping_results(self):
        number_poll_checks.verify_pe(self,self.before,self.final,self.report,'vanilla')
        number_poll_checks.execute(self,self.final,self.report,'vanilla',ARGS.nasm)

    def test_native_api_policy_guards_and_number_credential_sync(self):
        api_policy_checks.verify_pe(self,self.before,self.final,self.report,'vanilla')

    def test_native_api_policy_empty_whitespace_proxy_and_number_copy_truth_tables(self):
        api_policy_checks.execute(self,self.final,self.report,'vanilla',ARGS.nasm)

    def test_hypixel_http_header_uuid_name_errors_and_abi(self):
        hypixel_http_checks.execute_http(self,self.final,self.report,'vanilla',ARGS.nasm)

    def test_hypixel_config_change_expires_failures_under_existing_mutex(self):
        hypixel_http_checks.execute_refresh(self,self.final,self.report,'vanilla',ARGS.nasm)

    def test_lineage_rejects_unreviewed_code(self):
        original = pefile.PE(data=self.before)
        changed = bytearray(self.before)
        changed[original.get_offset_from_rva(0x885a4)] ^= 1
        with self.assertRaisesRegex(ValueError, 'lineage mismatch'):
            native_compat.build_bridge(bytes(changed), ARGS.nasm)

    def test_rebuild_is_deterministic(self):
        data, report = native_compat.build_bridge(self.before, ARGS.nasm)
        self.assertEqual(data, self.final)
        self.assertEqual(report, self.report)

    def test_registration_unload_guard_and_all_feature_hooks(self):
        self.assertEqual(len(self.report['hooks']), 28)
        self.assertEqual({h['callback'] for h in self.report['hooks']},
                         {'plain', 'json', 'plainTags', 'jsonTags', 'plainLocal', 'prelayout', 'render', 'seraphPrefix', 'denicker', 'matchStart', 'columnCatalog', 'replayStats', 'replayUuidCopy', 'numberGetLock', 'numberRegisterLock', 'numberPopLock', 'apiPingProxy', 'hypixelHttp', 'apiRefreshFailures', 'registerClientTick', 'stopClientPumpBeforeUnload'})
        for item in self.report['hooks']:
            expected = bridge.call_bytes(item['callRva'], item['bridgeTargetRva'])
            self.assertEqual(self.pe.get_data(item['callRva'], 5), expected)
        self.assertEqual(self.pe.get_data(native_compat.REGISTER_SITE + 5, 1), b'\x90')
        self.assertEqual(self.pe.get_data(native_compat.UNLOAD_KEY_SITE + 5, 1), b'\x90')

    def test_legacy_anticheat_dispatch_is_nopped_and_original_body_is_retained(self):
        before = pefile.PE(data=self.before)
        item = self.report['legacyAnticheat']
        self.assertEqual(item, native_anticheat.reviewed_patch(before, 'vanilla'))
        self.assertTrue(item['disabled'] and item['guiFieldsAndJniAbiPreserved'])
        site, target, end = item['callRva'], item['originalTargetRva'], item['originalBodyEndRva']
        self.assertEqual(site, 0x1a5a6)
        self.assertEqual(before.get_data(site, 5), bytes.fromhex('e8b5a10200'))
        self.assertEqual(self.pe.get_data(site, 5), b'\x90' * 5)
        self.assertEqual(before.get_data(target, end - target), self.pe.get_data(target, end - target))
        self.assertEqual(before.get_data(site - 12, 12), self.pe.get_data(site - 12, 12))
        self.assertEqual(before.get_data(site + 5, 32), self.pe.get_data(site + 5, 32))
        old = next(v.struct for v in before.DIRECTORY_ENTRY_EXCEPTION if v.struct.BeginAddress == target)
        new = next(v.struct for v in self.pe.DIRECTORY_ENTRY_EXCEPTION if v.struct.BeginAddress == target)
        self.assertEqual(old.__pack__(), new.__pack__())

    def test_legacy_anticheat_guard_rejects_changed_instruction_or_abi(self):
        before = pefile.PE(data=self.before)
        for rva, message in ((0x1a5a6, 'dispatch CALL'), (0x1a59a, 'argument ABI'),
                             (0x1a5ab, 'pump continuation'), (0x44760, 'detector prologue')):
            changed = bytearray(self.before)
            changed[before.get_offset_from_rva(rva)] ^= 1
            with self.subTest(rva=hex(rva)), self.assertRaisesRegex(ValueError, message):
                native_anticheat.reviewed_patch(pefile.PE(data=bytes(changed)), 'vanilla')

    def test_skin_success_hook_keeps_pending_status_and_nonempty_name_guards(self):
        before = pefile.PE(data=self.before)
        hook = next(h for h in self.report['hooks'] if h['callRva'] == 0x89819)
        self.assertEqual((hook['originalTargetRva'], hook['callback']), (0x39f40, 'plain'))
        self.assertEqual(before.get_data(0x89819, 5), bytes.fromhex('e82207fbff'))
        for rva, expected in ((0x894f8, 'e80337020084c00f8430030000'),
                              (0x89806, '4c8d8df02500004d8b4608488b542458498bcd'),
                              (0xacdb5, '80bfc202000000'), (0xacdbe, '837f3003'),
                              (0xacdc4, '48837f7000'), (0xacdcb, 'c687c202000000')):
            value = bytes.fromhex(expected)
            self.assertEqual(before.get_data(rva, len(value)), value)
            self.assertEqual(self.pe.get_data(rva, len(value)), value)
        self.assertEqual(self.report['skinSuccessOutput']['noSuccessSkipRva'], 0x89835)

    def test_every_original_mutation_has_expected_bytes(self):
        allowed = bytearray(len(self.before))
        for item in self.report['patches']:
            at, before, after = item['offset'], bytes.fromhex(item['before']), bytes.fromhex(item['after'])
            self.assertEqual(self.before[at:at + len(before)], before)
            self.assertEqual(self.final[at:at + len(after)], after)
            self.assertFalse(any(allowed[at:at + len(after)]))
            allowed[at:at + len(after)] = b'\x01' * len(after)
        self.assertTrue(all(a == b or allowed[i] for i, (a, b) in enumerate(zip(self.before, self.final))))

    def test_separate_writable_state_and_executable_code(self):
        code, state = self.pe.sections[-2:]
        self.assertEqual(code.Name.rstrip(b'\0'), b'.adncode')
        self.assertEqual(state.Name.rstrip(b'\0'), b'.adnstat')
        self.assertEqual(code.Characteristics, 0x60000020)
        self.assertEqual(state.Characteristics, 0xc0000040)
        self.assertEqual(self.pe.get_data(state.VirtualAddress, 40), native_compat.STATE_MAGIC + bytes(32))

    def test_unwind_and_runtime_descriptor(self):
        self.assertEqual(len(self.report['bridgeRuntimeFunctions']), 30)
        self.assertEqual(len(self.pe.DIRECTORY_ENTRY_EXCEPTION), 3825)
        runtime = self.report['runtime_metadata']
        self.assertEqual(runtime['heartbeatWidth'], 8)
        self.assertEqual(runtime['heartbeatRva'], self.report['stateSection']['rva'] + 16)
        self.assertTrue(runtime['classes'] and runtime['hooks'])
        self.assertFalse(runtime['pump'])
        self.assertFalse(self.report['originalDllExecutedByBuild'])
        self.assertFalse(self.report['gameRuntimeTested'])

    def test_extension_ids_follow_all_thirty_native_columns(self):
        section = self.report['section']
        payload = self.pe.get_data(section['rva'], self.report['metadata']['payloadSize'])
        for name, number in ((b'fklv\0', 30), (b'urchin\0', 31)):
            at = payload.index(name)
            self.assertEqual(struct.unpack_from('<I', payload, at + 0x50)[0], number)
        self.assertEqual(self.report['columnCatalog']['originalMaxId'], 29)


@unittest.skipUnless(os.name == 'nt' and ctypes.sizeof(ctypes.c_void_p) == 8, 'Windows x64 bridge mocks')
class CompatExecutionTests(unittest.TestCase):
    def setUp(self):
        _, _, _, final, self.report = fixture()
        self.pe = pefile.PE(data=final)
        self.keep, self.events, self.failures, self.lifecycle_events = [], [], [], []
        self.k32 = ctypes.WinDLL('kernel32', use_last_error=True)
        self.k32.VirtualAlloc.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32]
        self.k32.VirtualAlloc.restype = ctypes.c_void_p
        self.k32.VirtualFree.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32]
        self.base = self.k32.VirtualAlloc(None, self.report['imageSize'], 0x3000, 0x40)
        self.assertTrue(self.base)
        section, meta = self.report['section'], self.report['metadata']
        # No original machine code or whole PE is copied into executable memory.
        payload = self.pe.get_data(section['rva'], meta['payloadSize'])
        ctypes.memmove(self.base + section['rva'], payload, len(payload))
        self.table = (ctypes.c_void_p * 256)()
        self.env = (ctypes.c_void_p * 1)(ctypes.addressof(self.table))
        self.env_ptr = ctypes.addressof(self.env)
        self.state = self.base + self.report['stateSection']['rva']
        self.return_status = 0
        self.pending_exception = 0
        self.stop_method = 0x4455
        self.global_class = 0x7788
        self.key_state = 0
        self.stop_throws = False
        self.entries = (ctypes.c_uint64 * 6)(*[self.base + v for v in (0x140cd0, 0x140ce8, 0x20490, 0x140d00, 0x140d28, 0x204a0)])
        def register(env, clazz, methods, count):
            self.events.append(('register', env, clazz, count, ctypes.string_at(methods, count * 24)))
            return self.return_status
        self.bind(0x6b8, ctypes.c_int, [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int], register)
        P = ctypes.c_void_p
        self.bind(0x720, ctypes.c_ubyte, [P], lambda env: self.pending_exception)
        def method(env, clazz, name, signature):
            self.lifecycle_events.append(('method', env, clazz, name, signature))
            return self.stop_method
        def new_global(env, clazz):
            self.lifecycle_events.append(('new-global', env, clazz))
            return self.global_class
        def delete_global(env, clazz):
            self.lifecycle_events.append(('delete-global', env, clazz))
        def stop(env, clazz, method_id, args):
            self.lifecycle_events.append(('stop', env, clazz, method_id, args))
            if self.stop_throws: self.pending_exception = 1
        self.bind(0x388, P, [P, P, ctypes.c_char_p, ctypes.c_char_p], method)
        self.bind(0xa8, P, [P, P], new_global)
        self.bind(0xb0, None, [P, P], delete_global)
        self.bind(0x478, None, [P, P, P, P], stop)
        key_callback = ctypes.WINFUNCTYPE(ctypes.c_short, ctypes.c_int)(lambda key: self.key_state)
        self.keep.append(key_callback)
        ctypes.c_void_p.from_address(self.base + 0x132640).value = ctypes.cast(key_callback, P).value
        target = self.base + section['rva'] + meta['register']
        caller = bytes.fromhex('41 56 48 83 ec 20 49 89 d6 48 b8') + struct.pack('<Q', target)
        caller += bytes.fromhex('ff d0 48 83 c4 20 41 5e c3')
        ctypes.memmove(self.base + 0x1000, caller, len(caller))
        self.register = ctypes.WINFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int)(self.base + 0x1000)
        self.tick = ctypes.WINFUNCTYPE(None)(self.base + section['rva'] + meta['clientTick'])
        target = self.base + section['rva'] + meta['unloadKey']
        caller = bytes.fromhex('53 48 83 ec 40 48 89 54 24 30 48 b8') + struct.pack('<Q', target)
        caller += bytes.fromhex('ff d0 48 83 c4 40 5b c3')
        ctypes.memmove(self.base + 0x1400, caller, len(caller))
        self.unload_key = ctypes.WINFUNCTYPE(ctypes.c_short, ctypes.c_int, P)(self.base + 0x1400)

    def tearDown(self):
        if self.base:
            self.k32.VirtualFree(self.base, 0, 0x8000)

    def bind(self, offset, result, args, callback):
        function = ctypes.WINFUNCTYPE(result, *args)(callback)
        self.keep.append(function)
        self.table[offset // 8] = ctypes.cast(function, ctypes.c_void_p).value

    def stub(self, rva, result, args, callback):
        function = ctypes.WINFUNCTYPE(result, *args)(callback)
        self.keep.append(function)
        code = b'\x48\xb8' + struct.pack('<Q', ctypes.cast(function, ctypes.c_void_p).value) + b'\xff\xe0'
        ctypes.memmove(self.base + rva, code, len(code))

    def test_registration_copies_original_entries_and_adds_exact_method(self):
        original = bytes(self.entries)
        self.assertEqual(self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2), 0)
        event = self.events[-1]
        self.assertEqual(event[1:4], (self.env_ptr, 0x1234, 3))
        self.assertEqual(event[4][:48], original)
        name, signature, callback = struct.unpack_from('<QQQ', event[4], 48)
        self.assertEqual(ctypes.string_at(name), b'nativeClientTick')
        self.assertEqual(ctypes.string_at(signature), b'()V')
        self.assertEqual(callback, self.base + self.report['section']['rva'] + self.report['metadata']['clientTick'])
        self.assertEqual(bytes(self.entries), original)
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 8).value, 1)
        self.assertEqual(ctypes.c_uint64.from_address(self.state + 24).value, self.global_class)
        self.assertEqual(ctypes.c_uint64.from_address(self.state + 32).value, self.stop_method)
        self.assertEqual(self.lifecycle_events[0], ('method', self.env_ptr, 0x1234, b'adninStopClientPump', b'()V'))

    def test_registration_failure_is_preserved_and_not_reported_ready(self):
        self.return_status = -7
        self.assertEqual(self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2), -7)
        self.assertEqual(self.events[-1][3], 3)
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 8).value, 0)

    def test_unexpected_count_or_table_is_forwarded_unmodified(self):
        self.assertEqual(self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 1), 0)
        self.assertEqual(self.events[-1][3], 1)
        self.entries[5] += 1
        self.assertEqual(self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2), 0)
        self.assertEqual(self.events[-1][3], 2)
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 8).value, 0)

    def test_heartbeat_changes_only_when_client_callback_runs(self):
        self.assertEqual(ctypes.c_uint64.from_address(self.state + 16).value, 0)
        self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2)
        self.assertEqual(ctypes.c_uint64.from_address(self.state + 16).value, 0)
        for _ in range(5): self.tick()
        self.assertEqual(ctypes.c_uint64.from_address(self.state + 16).value, 5)

    def test_missing_stop_method_blocks_installer_before_pump_construction(self):
        self.stop_method = 0
        self.assertEqual(self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2), -1)
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 8).value, 0)
        self.assertEqual([e[0] for e in self.lifecycle_events], ['method'])

    def test_missing_global_class_blocks_installer_before_pump_construction(self):
        self.global_class = 0
        self.assertEqual(self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2), -1)
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 8).value, 0)
        self.assertEqual(ctypes.c_uint64.from_address(self.state + 24).value, 0)

    def test_end_stops_callback_before_allowing_original_cleanup(self):
        self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2)
        self.key_state = -32768
        self.assertEqual(self.unload_key(0x23, self.env_ptr), -32768)
        self.assertEqual(self.lifecycle_events[-2:], [('stop', self.env_ptr, self.global_class, self.stop_method, None),
                                                     ('delete-global', self.env_ptr, self.global_class)])
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 8).value, 0)
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 12).value, 1)
        self.assertEqual(bytes(ctypes.string_at(self.state + 24, 16)), bytes(16))

    def test_idle_key_and_uninstalled_pump_do_not_call_stop(self):
        self.key_state = -32768
        self.assertEqual(self.unload_key(0x23, None), -32768)
        self.assertFalse(self.lifecycle_events)
        self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2)
        self.key_state = 0
        before = list(self.lifecycle_events)
        self.assertEqual(self.unload_key(0x23, self.env_ptr), 0)
        self.assertEqual(self.lifecycle_events, before)

    def test_jni_stop_exception_defers_cleanup_and_retains_class(self):
        self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2)
        self.key_state = -32768
        self.stop_throws = True
        self.assertEqual(self.unload_key(0x23, self.env_ptr), 0)
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 8).value, 1)
        self.assertEqual(ctypes.c_uint64.from_address(self.state + 24).value, self.global_class)
        self.assertEqual(ctypes.c_uint32.from_address(self.state + 12).value, 0)
        self.assertEqual(self.lifecycle_events[-1][0], 'stop')
        before = list(self.lifecycle_events)
        self.assertEqual(self.unload_key(0x23, self.env_ptr), 0)
        self.assertEqual(self.lifecycle_events, before)

    def test_missing_attached_env_defers_cleanup(self):
        self.register(self.env_ptr, 0x1234, ctypes.addressof(self.entries), 2)
        self.key_state = -32768
        before = list(self.lifecycle_events)
        self.assertEqual(self.unload_key(0x23, None), 0)
        self.assertEqual(self.lifecycle_events, before)

    def test_retired_detector_is_not_called_for_any_owned_gui_fixture(self):
        item = self.report['legacyAnticheat']
        replacement = self.pe.get_data(item['callRva'], 5)
        self.assertEqual(replacement, b'\x90' * 5)
        self.stub(item['originalTargetRva'], None, [ctypes.c_void_p] * 4,
                  lambda *args: self.events.append(('legacy-anticheat', args)))
        # This is an owned miniature pump, not a copy of the original pump.
        code = b'\x48\x83\xec\x28' + replacement + bytes.fromhex('b8115a00004883c428c3')
        address = self.base + item['callRva'] - 4
        ctypes.memmove(address, code, len(code))
        run = ctypes.WINFUNCTYPE(ctypes.c_uint32, *([ctypes.c_void_p] * 4))(address)
        for historical_gui_value in (None, 1, 0xffffffffffffffff):
            self.assertEqual(run(self.env_ptr, 0x112233, 0x223344, historical_gui_value), 0x5a11)
        self.assertFalse(any(e[0] == 'legacy-anticheat' for e in self.events))

    def test_windows_unwinds_registration_and_unload_guard_frames(self):
        # Windows reads only our appended code and synthetic stack/CONTEXT.
        unwind = ctypes.WinDLL('ntdll').RtlVirtualUnwind
        unwind.argtypes = [ctypes.c_uint32, ctypes.c_uint64, ctypes.c_uint64,
                           ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                           ctypes.c_void_p, ctypes.c_void_p]
        unwind.restype = ctypes.c_void_p
        frames = {'register': (0x70, [7, 6, 3]), 'unloadKey': (0x30, [3])}
        registers = {3: 0x90, 6: 0xa8, 7: 0xb0}
        expected_ip = 0x00007ffefedc1234
        for entry in self.report['bridgeRuntimeFunctions']:
            if entry['name'] not in frames: continue
            allocation, saved = frames[entry['name']]
            runtime_function = (ctypes.c_uint32 * 3)(entry['begin'], entry['end'], entry['unwind'])
            stack, context = ctypes.create_string_buffer(2048), ctypes.create_string_buffer(1232)
            sp = ctypes.addressof(stack) + 128
            for i, register in enumerate(saved):
                struct.pack_into('<Q', stack, 128 + allocation + i * 8, 0xdead0000 + register)
            struct.pack_into('<Q', stack, 128 + allocation + len(saved) * 8, expected_ip)
            struct.pack_into('<I', context, 0x30, 0x00100003)
            struct.pack_into('<Q', context, 0x98, sp)
            pc = self.base + entry['begin'] + ctypes.c_ubyte.from_address(self.base + entry['unwind'] + 1).value
            struct.pack_into('<Q', context, 0xf8, pc)
            handler_data, frame = ctypes.c_void_p(), ctypes.c_uint64()
            unwind(0, self.base, pc, ctypes.byref(runtime_function), context,
                   ctypes.byref(handler_data), ctypes.byref(frame), None)
            self.assertEqual(struct.unpack_from('<Q', context, 0x98)[0], sp + allocation + len(saved) * 8 + 8)
            self.assertEqual(struct.unpack_from('<Q', context, 0xf8)[0], expected_ip)
            for register in saved:
                self.assertEqual(struct.unpack_from('<Q', context, registers[register])[0], 0xdead0000 + register)

    def test_expanded_skin_buffer_reaches_last_compatibility_field(self):
        P, I = ctypes.c_void_p, ctypes.c_int
        self.bind(0x720, ctypes.c_ubyte, [P], lambda env: 0)
        ctypes.c_uint64.from_address(self.base + 0x17e428).value = 0x1234
        self.stub(0xbcf40, P, [P, P], lambda name, result: 0)
        self.stub(0x7d540, I, [P], lambda uuid: 1)
        self.stub(0xa9a10, ctypes.c_ubyte, [], lambda: 1)
        def skin(name, target):
            if any(ctypes.string_at(target, 0x280)):
                self.failures.append('expanded Skin buffer was not zeroed')
            ctypes.memset(target, 0x51, 0x280)
            ctypes.c_uint32.from_address(target).value = 3
            ctypes.c_uint64.from_address(target + 0x18).value = 6
            ctypes.c_uint32.from_address(target + 0x270).value = 0x44332211
            self.events.append(('skin', target))
            return 1
        def destroy(target):
            if ctypes.c_uint32.from_address(target + 0x270).value != 0x44332211:
                self.failures.append('final Skin scalar changed')
            self.events.append(('destroy', target))
        self.stub(0xa84c0, ctypes.c_ubyte, [P, P], skin)
        self.stub(0x59510, None, [P], destroy)
        target = self.base + self.report['section']['rva'] + self.report['metadata']['denicker']
        caller = bytes.fromhex('41 55 48 83 ec 20 49 89 cd 48 89 d1 4c 89 c2 48 b8')
        caller += struct.pack('<Q', target) + bytes.fromhex('ff d0 48 83 c4 20 41 5d c3')
        ctypes.memmove(self.base + 0x1200, caller, len(caller))
        row, result = ctypes.create_string_buffer(0x178), ctypes.create_string_buffer(0x1c8)
        run = ctypes.WINFUNCTYPE(P, P, P, P)(self.base + 0x1200)
        self.assertFalse(run(self.env_ptr, ctypes.addressof(row) + 0x40, ctypes.addressof(result)))
        self.assertEqual([event[0] for event in self.events], ['skin', 'destroy'])
        self.assertEqual(self.events[0][1], self.events[1][1])
        self.assertFalse(self.failures)

    def test_bot_results_use_expanded_stats_and_all_three_mode_flags(self):
        P, I = ctypes.c_void_p, ctypes.c_int
        profile = b'Adnin|12345678-1234-4567-89ab-123456789abc'
        ctypes.c_uint64.from_address(self.base + 0x17e428).value = 0x1234
        self.bind(0x720, ctypes.c_ubyte, [P], lambda env: 0)
        self.bind(0x538, P, [P, ctypes.c_char_p], lambda env, name: 1)
        self.bind(0x388, P, [P, P, ctypes.c_char_p, ctypes.c_char_p], lambda *args: 2)
        self.bind(0x3a0, P, [P, P, P, P], lambda *args: 3)
        self.bind(0x520, I, [P, P], lambda *args: len(profile))
        self.bind(0x6e8, None, [P, P, I, I, P], lambda env, ref, start, size, target: ctypes.memmove(target, profile, len(profile)))
        self.bind(0xb8, None, [P, P], lambda env, ref: self.events.append(('delete', ref)))
        self.stub(0xbcf40, P, [P, P], lambda *args: 0)
        self.stub(0x7d540, I, [P], lambda uuid: 1)
        self.stub(0xa9a10, ctypes.c_ubyte, [], lambda: 0)
        self.stub(0x93230, None, [P, I], lambda name, level: self.events.append(('name-queue', level)))
        self.stub(0x9c790, None, [P, P], lambda *args: self.events.append(('uuid-queue',)))
        def stats(uuid, target):
            block = bytearray(0x180)
            block[self.mode_ready] = 1
            struct.pack_into('<I', block, 0x178, 0x12345678)
            ctypes.memmove(target, bytes(block), len(block))
            self.events.append(('stats-copy',))
            return 1
        def assign(target, value):
            text = ctypes.string_at(value)
            ctypes.memmove(target, text + bytes(16 - len(text)) + struct.pack('<QQ', len(text), 15), 32)
        def candidates(target, source, count):
            storage = ctypes.create_string_buffer(32)
            self.keep.append(storage)
            ctypes.memmove(storage, source, 32)
            pointer = ctypes.addressof(storage)
            ctypes.memmove(target, struct.pack('<QQQ', pointer, pointer + 32, pointer + 32), 24)
        self.stub(0x9afc0, ctypes.c_ubyte, [P, P], stats)
        self.stub(0x7100, None, [P, P], assign)
        self.stub(0xb79c0, None, [P, P, ctypes.c_uint64], candidates)
        target = self.base + self.report['section']['rva'] + self.report['metadata']['denicker']
        caller = bytes.fromhex('41 55 48 83 ec 20 49 89 cd 48 89 d1 4c 89 c2 48 b8')
        caller += struct.pack('<Q', target) + bytes.fromhex('ff d0 48 83 c4 20 41 5d c3')
        ctypes.memmove(self.base + 0x1200, caller, len(caller))
        run = ctypes.WINFUNCTYPE(P, P, P, P)(self.base + 0x1200)
        for self.mode_ready in (8, 0xc0, 0x100):
            row = ctypes.create_string_buffer(0x178)
            result = ctypes.create_string_buffer(0x1d0)
            name = ctypes.create_string_buffer(b'isa5')
            assign(ctypes.addressof(row) + 0x40, ctypes.addressof(name))
            ctypes.memset(ctypes.addressof(result) + 0x1c8, 0x5a, 8)
            before = bytes(row)
            self.assertEqual(run(self.env_ptr, ctypes.addressof(row) + 0x40, ctypes.addressof(result)), 1)
            self.assertEqual(result.raw[:3], b'\x01\x00\x00')
            self.assertEqual(result.raw[0x1c0], 1)
            self.assertEqual(result.raw[0x40 + self.mode_ready], 1)
            self.assertEqual(struct.unpack_from('<I', result, 0x1b8)[0], 0x12345678)
            self.assertEqual(result.raw[0x1c8:0x1d0], b'Z' * 8)
            self.assertEqual(bytes(row), before)
            self.assertEqual(result.raw[0x20:0x25], b'Adnin')
        self.assertEqual(sum(e[0] == 'uuid-queue' for e in self.events), 3)
        self.assertEqual(sum(e[0] == 'stats-copy' for e in self.events), 3)
        # Replay reuses verified Bot results through the UUID-only queue.
        self.events.clear()
        self.bind(0x418, I, [P,P,P,P], lambda *args: 1)
        self.stub(0x9fa40, None, [P], lambda *args: self.events.append(('replay-uuid-queue',)))
        for offset, event in ((0x132468, 'lock'), (0x132470, 'unlock')):
            callback = ctypes.WINFUNCTYPE(I,P)(lambda pointer, event=event: self.events.append((event,pointer)) or 0)
            self.keep.append(callback)
            ctypes.c_uint64.from_address(self.base+offset).value = ctypes.cast(callback,P).value
        ctypes.c_ubyte.from_address(self.base+0x17e438).value = 1
        row,result = ctypes.create_string_buffer(0x178),ctypes.create_string_buffer(0x1c8)
        name = ctypes.create_string_buffer(b'isa5')
        assign(ctypes.addressof(row)+0x40,ctypes.addressof(name))
        before=bytes(row)
        self.assertEqual(run(self.env_ptr,ctypes.addressof(row)+0x40,ctypes.addressof(result)),1)
        self.assertEqual(bytes(row),before)
        self.assertEqual([e[0] for e in self.events if e[0]!='delete'],
                         ['lock','replay-uuid-queue','unlock','stats-copy'])


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0], *REST])
