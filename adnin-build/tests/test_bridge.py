"""PE regression and isolated Windows x64 bridge execution tests.

The original DLL is never loaded. Execution tests allocate fresh private mock
images with fake JNI callbacks and stubs for external functions. The chat-tail
equivalence check also copies the unchanged consumer into this owned fixture;
all of its external calls are mocked. No game or network is touched.
"""
import argparse
import ctypes
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import pefile
import bridge
import reembed
import native_anticheat
import replay_checks
import replay_stats_checks
import replay_denick_checks
import number_poll_checks
import api_policy_checks
import header_checks
import scheduler_checks
import process_entry_checks
import game_state_checks
import hypixel_http_checks
import skin_policy_checks
import input_hooks_checks
import chat_poll_prune_checks
import player_policy_checks
import party_mode_checks
import shared_config_checks

parser = argparse.ArgumentParser(add_help=False)
parser.add_argument('--input', type=Path, default=ROOT.parent / 'lunar-full-build/build/bin/ChatReaderLunar.dll')
parser.add_argument('--nasm', type=Path, default=ROOT.parents[2] / 'https-github-com-freecodexyz-free-code/work/nasm/nasm-3.02/nasm.exe')
ARGS, UNITTEST_ARGS = parser.parse_known_args()
FIXTURE = None


def fixture():
    global FIXTURE
    if FIXTURE is None:
        original = ARGS.input.read_bytes()
        records = json.loads((ROOT / 'evidence/original-class-index.json').read_text())
        classes = {}
        for record in records:
            offset = int(record['offset'], 16)
            classes[reembed.renamed(record['name'])] = reembed.branded_class(original[offset:offset + record['size']])
        intermediate, _ = reembed.rebuild(original, classes, records)
        output, report = bridge.rebuild(intermediate, ARGS.nasm)
        FIXTURE = (original, intermediate, output, report)
    return FIXTURE


class BridgePETests(unittest.TestCase):
    def test_shared_settings_retire_synchronous_writer_and_preserve_jni_abi(self):
        shared_config_checks.verify(self,self.input,self.output,self.report,'lunar')
        shared_config_checks.execute(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_lunar_party_mode_handoff_preserves_parser_consumer_and_abi(self):
        party_mode_checks.verify(self,self.input,self.output,self.report)
        party_mode_checks.execute(self,self.output,self.report,ARGS.nasm)

    def test_gray_query_guards_preserve_cached_state_pending_results_and_abi(self):
        player_policy_checks.verify(self,self.input,self.output,self.report,'lunar')
        player_policy_checks.execute(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_chat_poll_pruning_preserves_consumer_boundary_abi_refs_and_chained_unwind(self):
        chat_poll_prune_checks.verify(self,self.input,self.output,self.report,'lunar')
        chat_poll_prune_checks.execute(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_input_binding_lifecycle_and_ordinary_message_transparency(self):
        input_hooks_checks.verify(self,self.input,self.output,self.report,'lunar')
        input_hooks_checks.execute(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_mellow_skin_retires_hash_scans_without_changing_setting_or_other_denickers(self):
        skin_policy_checks.verify(self,self.input,self.output,self.report,'lunar')

    def test_game_state_published_after_parser_with_strict_predicate_and_safe_jni(self):
        game_state_checks.verify(self,self.input,self.output,self.report,'lunar')
        game_state_checks.execute(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_process_exit_guard_preserves_explicit_unload_attach_and_abi(self):
        process_entry_checks.verify(self,self.input,self.output,self.report,'lunar')
        process_entry_checks.execute(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_lunar_scheduler_releases_ignored_future_and_preserves_exception_abi(self):
        scheduler_checks.verify(self,self.input,self.output,self.report)
        scheduler_checks.execute(self,self.output,self.report,ARGS.nasm)

    def test_headers_pin_both_string_sites_and_preserve_original_layout(self):
        header_checks.verify_pe(self, self.input, self.output, self.report, 'lunar')

    def test_headers_actual_jni_return_ownership_fallback_and_nonvolatile_abi(self):
        header_checks.execute(self, self.output, self.report, 'lunar', ARGS.nasm)

    @classmethod
    def setUpClass(cls):
        cls.original, cls.input, cls.output, cls.report = fixture()
        cls.before = pefile.PE(data=cls.input)
        cls.after = pefile.PE(data=cls.output)

    def test_deterministic_build(self):
        binary, report = bridge.rebuild(self.input, ARGS.nasm)
        self.assertEqual(binary, self.output)
        self.assertEqual(report, self.report)

    def test_replay_changes_only_four_local_overlay_predicates(self):
        replay_checks.verify_pe(self, self.input, self.output, self.report, 'lunar')

    def test_replay_predicate_registers_truth_table_exceptions_and_unwind(self):
        replay_checks.execute(self, self.output, self.report, 'lunar', ARGS.nasm)

    def test_replay_stats_verified_identity_queue_readiness_jni_and_unwind(self):
        replay_stats_checks.verify_pe(self, self.input, self.output, self.report, 'lunar')
        replay_stats_checks.execute(self, self.output, self.report, 'lunar', ARGS.nasm)

    def test_replay_denicker_gate_cached_identity_and_actor_uuid_guard(self):
        replay_denick_checks.verify_pe(self, self.input, self.output, self.report, 'lunar')
        replay_denick_checks.execute(self, self.output, self.report, 'lunar', ARGS.nasm)

    def test_number_periodic_polling_defers_busy_worker_without_dropping_results(self):
        number_poll_checks.verify_pe(self,self.input,self.output,self.report,'lunar')
        number_poll_checks.execute(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_native_api_policy_guards_and_number_credential_sync(self):
        api_policy_checks.verify_pe(self,self.input,self.output,self.report,'lunar')

    def test_native_api_policy_empty_whitespace_proxy_and_number_copy_truth_tables(self):
        api_policy_checks.execute(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_hypixel_http_header_uuid_name_errors_and_abi(self):
        hypixel_http_checks.execute_http(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_hypixel_config_change_expires_failures_under_existing_mutex(self):
        hypixel_http_checks.execute_refresh(self,self.output,self.report,'lunar',ARGS.nasm)

    def test_lunar_unload_requires_java_stop_and_preserves_poll_cleanup_boundaries(self):
        item = self.report['lunarUnloadGuard']
        site = item['callRva']
        self.assertEqual(site, 0x150da)
        self.assertEqual(self.before.get_data(site, 6), bridge.LUNAR_UNLOAD_BEFORE)
        self.assertEqual(self.after.get_data(site, 6), bridge.call_bytes(site, item['bridgeTargetRva']) + b'\x90')
        self.assertEqual((item['stopOwner'], item['stopMethod'], item['descriptor'], item['requiredAcknowledgement']),
                         ('AdninGui4', 'nativeStopGameModules', '()I', 1))
        self.assertFalse(item['systemKeyPolling'])
        self.assertTrue(item['requiresClientAuthorizedRequest'] and item['requestSurvivesKeyRelease'])
        self.assertEqual(item['originalPollIntervalMs'], 20)
        for rva, size in ((0x150d5, 5), (0x150e0, 5), (0x1510e, 0x11), (0x15120, 0x112), (0x14d2e, 5)):
            self.assertEqual(self.after.get_data(rva, size), self.before.get_data(rva, size))

    def test_original_native_lineage_is_pinned(self):
        self.assertEqual(bridge.sha(bridge.normalized_text(self.original, pefile.PE(data=self.original))), bridge.TEXT_LINEAGE_SHA256)
        self.assertEqual(bridge.sha(bridge.normalized_text(self.input, self.before)), bridge.TEXT_LINEAGE_SHA256)
        modified = bytearray(self.input)
        modified[self.before.get_offset_from_rva(0x5000)] ^= 1
        with self.assertRaisesRegex(ValueError, 'lineage mismatch'):
            bridge.rebuild(bytes(modified), ARGS.nasm)

    def test_all_verified_call_sites(self):
        self.assertEqual(len(self.report['hooks']), 30 + len(bridge.native_player_policy.PROFILES['lunar'])
                         + len(bridge.native_player_policy.PRODUCERS['lunar']))
        for item in self.report['hooks']:
            site = item['callRva']
            self.assertEqual(self.before.get_data(site, 5), bridge.call_bytes(site, item['originalTargetRva']))
            self.assertEqual(self.after.get_data(site, 5), bridge.call_bytes(site, item['bridgeTargetRva']))

    def test_legacy_anticheat_dispatch_is_nopped_but_body_and_abi_are_retained(self):
        item = self.report['legacyAnticheat']
        self.assertEqual(item, native_anticheat.reviewed_patch(self.before, 'lunar'))
        self.assertTrue(item['disabled'] and item['guiFieldsAndJniAbiPreserved'])
        site, target, end = item['callRva'], item['originalTargetRva'], item['originalBodyEndRva']
        self.assertEqual(site, 0x1a3de)
        self.assertEqual(self.before.get_data(site, 5), bytes.fromhex('e81d9d0200'))
        self.assertEqual(self.after.get_data(site, 5), b'\x90' * 5)
        self.assertEqual(self.before.get_data(target, end - target), self.after.get_data(target, end - target))
        self.assertEqual(self.before.get_data(site - 12, 12), self.after.get_data(site - 12, 12))
        self.assertEqual(self.before.get_data(site + 5, 32), self.after.get_data(site + 5, 32))
        old = next(v.struct for v in self.before.DIRECTORY_ENTRY_EXCEPTION if v.struct.BeginAddress == target)
        new = next(v.struct for v in self.after.DIRECTORY_ENTRY_EXCEPTION if v.struct.BeginAddress == target)
        self.assertEqual(old.__pack__(), new.__pack__())

    def test_legacy_anticheat_guards_reject_changed_call_arguments_and_continuation(self):
        for rva, message in ((0x1a3de, 'dispatch CALL'), (0x1a3d2, 'argument ABI'),
                             (0x1a3e3, 'pump continuation'), (0x44100, 'detector prologue')):
            changed = bytearray(self.input)
            changed[self.before.get_offset_from_rva(rva)] ^= 1
            with self.subTest(rva=hex(rva)), self.assertRaisesRegex(ValueError, message):
                native_anticheat.reviewed_patch(pefile.PE(data=bytes(changed)), 'lunar')

    def test_skin_success_hook_preserves_success_guard_and_plain_abi(self):
        hook = next(h for h in self.report['hooks'] if h['callRva'] == 0x87f1d)
        self.assertEqual((hook['originalTargetRva'], hook['callback']), (0x38260, 'plainDenick'))
        self.assertEqual(self.before.get_data(0x87f1d, 5), bytes.fromhex('e83e03fbff'))
        gate = next(h for h in self.report['hooks'] if h['callRva'] == 0x87bfc)
        self.assertEqual(gate['callback'], 'grayLegacySuccess')
        self.assertEqual(self.before.get_data(0x87bfc, 5), bytes.fromhex('e81f2a0200'))
        self.assertEqual(self.after.get_data(0x87bfc, 5), bridge.call_bytes(0x87bfc, gate['bridgeTargetRva']))
        for rva, expected in ((0x87c01, '84c00f8430030000'),
                              (0x87f0a, '4c8d8d302500004d8b4608488b542458498bcd'),
                              (0xaa7d5, '80bf6202000000'), (0xaa7de, '837f3003'),
                              (0xaa7e4, '48837f7000'), (0xaa7eb, 'c6876202000000')):
            value = bytes.fromhex(expected)
            self.assertEqual(self.before.get_data(rva, len(value)), value)
            self.assertEqual(self.after.get_data(rva, len(value)), value)
        self.assertEqual(self.report['skinSuccessOutput']['noSuccessSkipRva'], 0x87f39)

    def test_only_seraph_producer_prefix_calls_are_redirected(self):
        prefixes = [h for h in self.report['hooks'] if h['callback'] == 'seraphPrefix']
        self.assertEqual([h['callRva'] for h in prefixes], [0x6aa7d, 0x6b0dd])
        self.assertTrue(all(h['originalTargetRva'] == 0x68100 for h in prefixes))
        self.assertEqual(prefixes[0]['bridgeTargetRva'], prefixes[1]['bridgeTargetRva'])

    def test_match_notification_only_hooks_entering_ingame(self):
        hooks = [h for h in self.report['hooks'] if h['callback'] == 'matchStart']
        self.assertEqual(len(hooks), 1)
        self.assertEqual(hooks[0]['callRva'], 0x13f0d)
        self.assertEqual(hooks[0]['originalTargetRva'], 0x8a240)
        # The separate queue-entry reset must never trigger the notification.
        self.assertEqual(self.before.get_data(0x13f87, 5), self.after.get_data(0x13f87, 5))
        self.assertEqual(self.after.get_data(0x13f87, 5), bridge.call_bytes(0x13f87, 0x8a240))

    def test_metadata_extension_keeps_previous_slots(self):
        raw = self.after.get_data(self.report['section']['rva'], 160)
        fields = struct.unpack_from('<38I', raw, 8)
        self.assertEqual(fields[:2], (2, 160))
        for slot, name in ((22, 'seraphPrefix'), (23, 'denicker'),
                           (24, 'denickerBegin'), (27, 'metricsBegin'), (30, 'matchStart'),
                           (34, 'columnCatalog'), (35, 'columnBegin'),
                           (36, 'columnEnd'), (37, 'columnUnwind')):
            self.assertEqual(fields[slot], self.report['metadata'][name])

    def test_catalog_is_extended_before_native_configuration_ordering(self):
        hooks = [h for h in self.report['hooks'] if h['callback'] == 'columnCatalog']
        self.assertEqual(len(hooks), 1)
        self.assertEqual(hooks[0]['callRva'], 0x5fa4d)
        self.assertEqual(hooks[0]['originalTargetRva'], 0x5e850)
        for rva, size in ((0x6eed0, 16), (0x8e25d, 43)):
            self.assertEqual(self.before.get_data(rva, size), self.after.get_data(rva, size))

    def test_all_other_original_bytes_are_untouched(self):
        allowed = bytearray(len(self.input))
        for item in self.report['patches']:
            offset = item['offset']
            before, after = bytes.fromhex(item['before']), bytes.fromhex(item['after'])
            self.assertEqual(self.input[offset:offset + len(before)], before)
            self.assertEqual(self.output[offset:offset + len(after)], after)
            self.assertFalse(any(allowed[offset:offset + len(after)]))
            allowed[offset:offset + len(after)] = b'\1' * len(after)
        self.assertTrue(all(a == b or allowed[i] for i, (a, b) in enumerate(zip(self.input, self.output))))

    def test_read_execute_section_and_existing_layout(self):
        for old, new in zip(self.before.sections, self.after.sections):
            self.assertEqual(old.__pack__(), new.__pack__())
        section,state = self.after.sections[-2:]
        self.assertEqual(section.Name.rstrip(b'\0'), b'.adncode')
        self.assertEqual(section.Characteristics, 0x60000020)
        self.assertEqual(section.PointerToRawData, len(self.input))
        self.assertEqual(self.after.get_data(section.VirtualAddress, 8), bridge.MAGIC)
        self.assertEqual(self.after.OPTIONAL_HEADER.SizeOfImage,
                         bridge.align(state.VirtualAddress + state.Misc_VirtualSize, 0x1000))
        self.assertEqual(state.Name.rstrip(b'\0'),b'.adnstat')
        self.assertEqual(state.Characteristics,0xc0000040)

    def test_exports_imports_relocations_tls_unchanged(self):
        def exports(pe):
            return [(s.name, s.address, s.ordinal) for s in pe.DIRECTORY_ENTRY_EXPORT.symbols]
        self.assertEqual(exports(self.before), exports(self.after))
        for index in (0, 1, 5, 9, 12):
            self.assertEqual(self.before.OPTIONAL_HEADER.DATA_DIRECTORY[index].__pack__(),
                             self.after.OPTIONAL_HEADER.DATA_DIRECTORY[index].__pack__())

    def test_merged_runtime_function_table(self):
        def table(pe):
            directory = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
            return list(struct.iter_unpack('<III', pe.get_data(directory.VirtualAddress, directory.Size)))
        old, new = table(self.before), table(self.after)
        self.assertEqual(new[:len(old)], old)
        self.assertEqual(len(new), len(old) + len(bridge.LUNAR_FUNCTION_NAMES))
        self.assertEqual(new, sorted(new))
        for entry in self.report['bridgeRuntimeFunctions']:
            self.assertIn((entry['begin'], entry['end'], entry['unwind']), new)

    def test_unwind_records_match_stack_frames(self):
        expected = {'output': (0x40, [13, 12, 7, 6, 3]), 'invoke': (0x20, [13, 12, 7, 6, 3]),
                    'string': (0x20, [7, 6, 3]), 'layout': (0x40, [13, 12, 7, 6, 3]),
                    'render': (0x80, [15, 14, 13, 12, 7, 6, 3]),
                    'denicker': (0x370, [15, 14, 13, 12, 7, 6, 3]),
                    'metrics': (0x30, [7, 6, 3]), 'match': (0x20, [7, 6, 3]),
                    'column': (0x20, [3]), 'header': (0x40, [13, 12, 7, 6, 3]), 'lunarStop': (0x30, [3]),
                    'lunarSchedule': (0x30,[3]), 'gameActive': (0x30,[7,6,3]), 'partyMode': (0x50,[7,6,3]),
                    'inputResolve':(0x20,[7,6,3]),'inputMaintain':(0x30,[7,6,3]),
                    'inputDetach':(0x50,[3]),'inputProc':(0x40,[12,5,7,6,3]),
                    'inputInitialize':(0x28,[]), 'configSave':(0x28,[]),
                    'replayStats': (0x2d0, [15, 14, 13, 12, 7, 6, 3]),
                    'replayStatsQueue': (0x30, [3]), 'replayStatsCleanup': (0x28, []),
                    'replayStatsFrameCleanup': (0x20, [3]), 'replayNickName': (0x30, [7, 6, 3]),
                    'numberLock': (0x28,[6,3]), 'hypixelHttp':(0x348,[15,14,13,12,7,6,5,3])}
        for item in self.report['bridgeRuntimeFunctions']:
            data = self.after.get_data(item['unwind'], 24)
            if item['name']=='chatPollTail':
                spec=bridge.native_chat_poll.PROFILES['lunar']
                self.assertEqual(data[:16],bytes.fromhex('21000000')+
                    struct.pack('<III',spec['collector'],spec['collectorEnd'],spec['unwind']))
                continue
            if item['name'] in ('replayRdi', 'replayRbx', 'replayFrame', 'replayDenickGate'):
                self.assertEqual(data[:12], bytes.fromhex('011004f5100308011e000150'))
                continue
            if item['name'] in ('replayUuidCopy','apiKeyReady','apiUuidReady','apiPingProxy','apiDecodeComponent','apiRefreshFailures','processEntry') + bridge.native_player_policy.FUNCTIONS:
                self.assertEqual(data[:4], bytes((1, 0, 0, 0)))
                continue
            stack, registers = expected[item['name']]
            self.assertEqual(data[0], 0x11 if item['name'] in ('replayStats', 'replayStatsQueue') else 1)
            large = stack > 128
            self.assertEqual(data[2], 1 + int(large) + len(registers))
            if large:
                self.assertEqual(data[5], 1)
                self.assertEqual(struct.unpack_from('<H', data, 6)[0] * 8, stack)
            else:
                self.assertEqual(data[5] & 15, 2)
                self.assertEqual((data[5] >> 4) * 8 + 8, stack)
            first_push = 6 + 2 * int(large)
            self.assertEqual([data[first_push + 1 + 2 * i] >> 4 for i in range(len(registers))], registers)
            self.assertTrue(all(data[first_push + 1 + 2 * i] & 15 == 0 for i in range(len(registers))))
            offsets = [data[4]] + [data[first_push + 2 * i] for i in range(len(registers))]
            self.assertEqual(offsets, sorted(offsets, reverse=True))
            self.assertEqual(data[1], offsets[0])

    def test_header_checksum_and_honest_report(self):
        self.assertEqual(self.after.OPTIONAL_HEADER.CheckSum, self.after.generate_checksum())
        header = bridge.generated_header(self.report)
        self.assertIn(f'0x{self.after.OPTIONAL_HEADER.SizeOfImage:x}u', header)
        self.assertIn(f'0x{self.report["section"]["rva"]:x}u', header)
        self.assertIn(f'0x{bridge.MARKER:x}ull', header)
        self.assertFalse(self.report['gameRuntimeTested'])
        self.assertFalse(self.report['originalDllExecutedByBuild'])

    def test_refuses_double_bridge(self):
        with self.assertRaisesRegex(ValueError, 'reembedding stage'):
            bridge.rebuild(self.output, ARGS.nasm)

    def test_cli_generates_matching_dll_report_header(self):
        with tempfile.TemporaryDirectory(prefix='adnin-bridge-test-') as directory:
            folder = Path(directory)
            source = folder / 'input.dll'
            source.write_bytes(self.input)
            command = [sys.executable, str(ROOT / 'scripts/bridge.py'), '--input', str(source),
                       '--output', str(folder / 'Adnin.dll'), '--report', str(folder / 'bridge.json'),
                       '--header', str(folder / 'generated.h'), '--nasm', str(ARGS.nasm)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((folder / 'Adnin.dll').read_bytes(), self.output)
            self.assertEqual(json.loads((folder / 'bridge.json').read_text()), self.report)
            self.assertEqual((folder / 'generated.h').read_text(), bridge.generated_header(self.report))


@unittest.skipUnless(os.name == 'nt' and ctypes.sizeof(ctypes.c_void_p) == 8, 'Windows x64 mock execution')
class BridgeExecutionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, _, cls.output, cls.report = fixture()

    def setUp(self):
        self.keep = []
        self.events = []
        self.refs = {}
        self.pending = False
        self.failure = None
        self.failure_text = None
        self.maps = {}
        self.return_width = 60
        self.stop_ack = 1
        self.output_ack = 0
        self.key_state = 0
        self.k32 = ctypes.WinDLL('kernel32', use_last_error=True)
        self.k32.VirtualAlloc.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32]
        self.k32.VirtualAlloc.restype = ctypes.c_void_p
        self.k32.VirtualFree.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32]
        self.k32.VirtualFree.restype = ctypes.c_int
        self.k32.FlushInstructionCache.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t]
        self.k32.FlushInstructionCache.restype = ctypes.c_int
        self.base = self.k32.VirtualAlloc(None, self.report['imageSize'], 0x3000, 0x40)
        self.assertTrue(self.base)
        section = self.report['section']
        ctypes.memmove(self.base + section['rva'], self.output[section['offset']:section['offset'] + section['size']], section['size'])
        ctypes.c_uint64.from_address(self.base + 0x1a63a8).value = 0x12340000
        self.table = (ctypes.c_void_p * 233)()
        self.env = (ctypes.c_void_p * 1)(ctypes.addressof(self.table))
        self.env_ptr = ctypes.addressof(self.env)
        self.methods = {'setGameActive': 9, 'nativeRenderGeneratedEvent': 1, 'nativeUrchinWidth': 2, 'nativeUrchinHeader': 3,
                        'nativeUrchinRow': 4, 'nativeOverlayRow': 5, 'nativeMatchStarted': 6,
                        'nativeOrderedOverlayRow': 7, 'nativeStopGameModules': 8}
        P, I = ctypes.c_void_p, ctypes.c_int

        def check(env):
            self.events.append(('check', env, self.pending))
            return int(self.pending)
        def clear(env):
            self.events.append(('clear', env))
            self.pending = False
        def delete(env, ref):
            self.events.append(('delete', env, ref))
            self.refs.pop(ref, None)
        def new_string(env, value):
            text = value.decode('utf8')
            self.events.append(('new', env, text))
            failure = self.failure if self.failure_text in (None, text) else None
            if failure in ('new-null', 'new-exception'):
                self.pending = failure == 'new-exception'
                return None
            ref = 0x4000 + len([e for e in self.events if e[0] == 'new'])
            self.refs[ref] = text
            if failure == 'new-ref-exception':
                self.pending = True
            return ref
        def method(env, clazz, name, signature):
            name, signature = name.decode('ascii'), signature.decode('ascii')
            self.events.append(('method', env, clazz, name, signature))
            if self.failure in ('method-null', 'method-exception'):
                self.pending = self.failure == 'method-exception'
                return None
            return self.methods.get(name)
        def void_call(env, clazz, method_id, args):
            name = next(k for k, v in self.methods.items() if v == method_id)
            if name == 'nativeMatchStarted':
                self.assertIsNone(args)
                values = ()
            elif name == 'setGameActive':
                values = (bool(ctypes.c_uint64.from_address(args).value),)
            elif name == 'nativeRenderGeneratedEvent':
                ref = ctypes.c_uint64.from_address(args).value
                values = (self.refs[ref], bool(ctypes.c_ubyte.from_address(args + 8).value),
                          ctypes.c_int.from_address(args + 16).value)
            elif name == 'nativeUrchinHeader':
                values = tuple(ctypes.c_float.from_address(args + i * 8).value for i in range(3))
            elif name == 'nativeOrderedOverlayRow':
                ref = ctypes.c_uint64.from_address(args).value
                values = (self.refs[ref],) + tuple(ctypes.c_float.from_address(args + i * 8).value for i in range(1, 6))
                values += tuple(self.refs.get(ctypes.c_uint64.from_address(args + i * 8).value) for i in (6, 7))
            else:
                ref = ctypes.c_uint64.from_address(args).value
                values = (self.refs[ref],) + tuple(ctypes.c_float.from_address(args + i * 8).value for i in range(1, 4))
                if name == 'nativeOverlayRow':
                    values += tuple(self.refs.get(ctypes.c_uint64.from_address(args + i * 8).value) for i in (4, 5))
            self.events.append(('callback', env, clazz, name, values))
            if self.failure == 'callback-exception':
                self.pending = True
        def int_call(env, clazz, method_id, args):
            if method_id == self.methods['nativeRenderGeneratedEvent']:
                void_call(env, clazz, method_id, args)
                return self.output_ack
            if method_id == self.methods['nativeStopGameModules']:
                self.events.append(('stop', env, clazz, method_id, args))
                if self.failure == 'callback-exception': self.pending = True
                return self.stop_ack
            self.events.append(('width', env, clazz, method_id, ctypes.c_int.from_address(args).value))
            if self.failure == 'callback-exception':
                self.pending = True
            return self.return_width

        self.bind(0x720, ctypes.c_ubyte, [P], check)
        self.bind(0x88, None, [P], clear)
        self.bind(0xb8, None, [P, P], delete)
        self.bind(0x538, P, [P, ctypes.c_char_p], new_string)
        self.bind(0x388, P, [P, P, ctypes.c_char_p, ctypes.c_char_p], method)
        self.bind(0x478, None, [P, P, P, P], void_call)
        self.bind(0x418, I, [P, P, P, P], int_call)
        def key_state(key):
            self.events.append(('key', key))
            return self.key_state
        key_callback = ctypes.WINFUNCTYPE(ctypes.c_short, ctypes.c_int)(key_state)
        self.keep.append(key_callback)
        ctypes.c_uint64.from_address(self.base + 0x12f628).value = ctypes.cast(key_callback, P).value
        self.install_original(0x38260, 'plain', 4, 0x3344)
        self.install_original(0x37f60, 'json', 4, 0x4455)
        self.install_original(0x8a360, 'layout', 3, None)
        self.install_original(0x8c870, 'render', 5, 0x5566)
        self.install_original(0x8a240, 'match', 1, 0x6677)
        self.catalog_original = None
        self.catalog_sources = []
        def catalog(vector):
            self.events.append(('original-catalog', vector))
            columns = [('base' + str(i), i * 10, 10) for i in range(29)]
            model = ctypes.create_string_buffer(0xf0)
            self.keep.append(model)
            data = self.make_columns(model, columns)
            begin = ctypes.addressof(data)
            ctypes.memmove(vector, struct.pack('<QQQ', begin, begin + 29 * 0x58, begin + 29 * 0x58), 24)
            self.catalog_original = bytes(data)
            return vector
        def append_column(vector, end, descriptor):
            begin, original_end, capacity = struct.unpack('<QQQ', ctypes.string_at(vector, 24))
            self.assertEqual(end, original_end)
            self.assertLessEqual(end, capacity)
            self.assertEqual((end - begin) % 0x58, 0)
            old_bytes = ctypes.string_at(begin, end - begin)
            source = ctypes.string_at(descriptor, 0x58)
            self.catalog_sources.append((descriptor, source))
            self.events.append(('append-column', vector, end, source[:16].split(b'\0')[0].decode('ascii')))
            new_data = ctypes.create_string_buffer(len(old_bytes) + 0x58)
            self.keep.append(new_data)
            new_begin = ctypes.addressof(new_data)
            ctypes.memmove(new_begin, old_bytes + source, len(old_bytes) + 0x58)
            new_end = new_begin + len(old_bytes) + 0x58
            ctypes.memmove(vector, struct.pack('<QQQ', new_begin, new_end, new_end), 24)
            return new_begin + len(old_bytes)
        self.install_callback(0x5e850, P, [P], catalog)
        self.install_callback(0x4f590, P, [P, P, P], append_column)
        def map_lookup(map_address, iterator, key, hash_value):
            length, capacity = struct.unpack('<QQ', ctypes.string_at(key + 16, 16))
            data = key if capacity < 16 else ctypes.c_uint64.from_address(key).value
            name = ctypes.string_at(data, length).decode('ascii')
            self.events.append(('lookup', map_address, name, hash_value))
            values = self.maps.get(map_address, {})
            ctypes.c_uint64.from_address(iterator).value = 0
            ctypes.c_uint64.from_address(iterator + 8).value = values.get(name, 0)
            return iterator
        callback = ctypes.WINFUNCTYPE(P, P, P, P, ctypes.c_uint64)(map_lookup)
        self.keep.append(callback)
        code = b'\x48\xb8' + struct.pack('<Q', ctypes.cast(callback, P).value) + b'\xff\xe0'
        ctypes.memmove(self.base + 0x45d0, code, len(code))
        # Mock native caller reproduces the verified layout call's RBX=JNIEnv.
        # Exposed signature: (env, context, layout, model).
        layout_target = self.base + section['rva'] + self.report['metadata']['prelayout']
        caller = bytes.fromhex('53 48 83 ec 20 48 89 cb 48 89 d1 4c 89 c2 4d 89 c8 48 b8')
        caller += struct.pack('<Q', layout_target) + bytes.fromhex('ff d0 48 83 c4 20 5b c3')
        ctypes.memmove(self.base + 0x1000, caller, len(caller))
        # Mock state-transition caller: (env, nativeContext), with env in RBX.
        match_target = self.base + section['rva'] + self.report['metadata']['matchStart']
        match_caller = bytes.fromhex('53 48 83 ec 20 48 89 cb 48 89 d1 48 b8')
        match_caller += struct.pack('<Q', match_target) + bytes.fromhex('ff d0 48 83 c4 20 5b c3')
        ctypes.memmove(self.base + 0x1100, match_caller, len(match_caller))
        # Poll caller leaves its attached-thread env in caller_rsp+0x30.
        stop_target = self.base + section['rva'] + self.report['metadata']['lunarStop']
        stop_caller = bytes.fromhex('48 83 ec 48 48 89 4c 24 30 89 d1 48 b8')
        stop_caller += struct.pack('<Q', stop_target) + bytes.fromhex('ff d0 48 83 c4 48 c3')
        ctypes.memmove(self.base + 0x1200, stop_caller, len(stop_caller))
        self.k32.FlushInstructionCache(ctypes.c_void_p(-1), self.base, self.report['imageSize'])

    def tearDown(self):
        if getattr(self, 'base', None):
            self.k32.VirtualFree(self.base, 0, 0x8000)

    def test_lunar_unload_barrier_acknowledgement_and_jni_failures(self):
        run = ctypes.WINFUNCTYPE(ctypes.c_short, ctypes.c_void_p, ctypes.c_int)(self.base + 0x1200)
        cases = [(0, 1, None, False, True, True, 1), (1, 1, None, False, True, True, 1),
                 (-32768, 1, None, False, True, True, 1), (-32767, 1, None, False, True, True, 1),
                 (1, 0, None, False, True, True, 0), (1, 2, None, False, True, True, 0),
                 (1, -1, None, False, True, True, 0), (1, 1, 'method-null', False, True, True, 0),
                 (1, 1, 'method-exception', False, True, True, 0),
                 (1, 1, 'callback-exception', False, True, True, 0),
                 (1, 1, None, True, True, True, 0), (1, 1, None, False, False, True, 0),
                 (1, 1, None, False, True, False, 0)]
        for key, ack, failure, pending, gui, env, expected in cases:
            with self.subTest(key=key, ack=ack, failure=failure, pending=pending, gui=gui, env=env):
                self.key_state, self.stop_ack, self.failure, self.pending = key, ack, failure, pending
                ctypes.c_uint64.from_address(self.base + 0x1a63a8).value = 0x12340000 if gui else 0
                self.events.clear()
                self.assertEqual(run(self.env_ptr if env else None, 0x23), expected)
                self.assertEqual(self.pending, pending)
                self.assertFalse(any(e[0] == 'key' for e in self.events))
                if not gui or not env: self.assertFalse(self.events)
                for event in self.events:
                    if event[0] == 'method':
                        self.assertEqual(event[1:], (self.env_ptr, 0x12340000, 'nativeStopGameModules', '()I'))
                    elif event[0] == 'stop':
                        self.assertEqual(event[1:], (self.env_ptr, 0x12340000, 8, None))
                if pending: self.assertFalse(any(e[0] in ('method', 'stop', 'clear') for e in self.events))

    def test_lunar_unload_retries_client_request_after_system_key_release(self):
        run = ctypes.WINFUNCTYPE(ctypes.c_short, ctypes.c_void_p, ctypes.c_int)(self.base + 0x1200)
        for key, acknowledgement, expected in ((-32768, 0, 0), (0, 0, 0), (0, 1, 1)):
            self.key_state, self.stop_ack = key, acknowledgement
            self.events.clear()
            self.assertEqual(run(self.env_ptr, 0x23), expected)
            self.assertEqual(len([e for e in self.events if e[0] == 'stop']), 1)
            self.assertFalse(any(e[0] == 'key' for e in self.events))

    def test_lunar_java_ack_cannot_release_an_active_input_callback(self):
        run=ctypes.WINFUNCTYPE(ctypes.c_short,ctypes.c_void_p,ctypes.c_int)(self.base+0x1200)
        active=ctypes.c_uint32.from_address(self.base+self.report['nativeInputHooks']['activeCounterRva'])
        self.stop_ack=1
        active.value=1
        self.assertEqual(run(self.env_ptr,0x23),0)
        active.value=0
        self.assertEqual(run(self.env_ptr,0x23),1)

    def bind(self, offset, result, args, callback):
        native = ctypes.WINFUNCTYPE(result, *args)(callback)
        self.keep.append(native)
        self.table[offset // 8] = ctypes.cast(native, ctypes.c_void_p).value

    def install_original(self, rva, kind, count, result):
        def original(*args):
            self.events.append(('original', kind, args, self.pending))
            return args[1] if result is None else result
        native = ctypes.WINFUNCTYPE(ctypes.c_void_p, *([ctypes.c_void_p] * count))(original)
        self.keep.append(native)
        code = b'\x48\xb8' + struct.pack('<Q', ctypes.cast(native, ctypes.c_void_p).value) + b'\xff\xe0'
        ctypes.memmove(self.base + rva, code, len(code))

    def install_callback(self, rva, result, args, callback):
        native = ctypes.WINFUNCTYPE(result, *args)(callback)
        self.keep.append(native)
        code = b'\x48\xb8' + struct.pack('<Q', ctypes.cast(native, ctypes.c_void_p).value) + b'\xff\xe0'
        ctypes.memmove(self.base + rva, code, len(code))

    def function(self, name, count):
        address = self.base + self.report['section']['rva'] + self.report['metadata'][name]
        return ctypes.WINFUNCTYPE(ctypes.c_void_p, *([ctypes.c_void_p] * count))(address)

    def string(self, text, heap=False, declared_length=None):
        raw = text.encode('utf8')
        data = ctypes.create_string_buffer(32)
        self.keep.append(data)
        if len(raw) > 15 or heap:
            allocation = ctypes.create_string_buffer(raw)
            self.keep.append(allocation)
            struct.pack_into('<Q', data, 0, ctypes.addressof(allocation))
            capacity = max(16, len(raw))
        else:
            ctypes.memmove(data, raw, len(raw))
            capacity = 15
        struct.pack_into('<QQ', data, 16, len(raw) if declared_length is None else declared_length, capacity)
        return data

    def output_call(self, text='hello', json=False, category=0, consumed=False, **kwargs):
        value = self.string(text, **kwargs)
        args = (self.env_ptr, 0x112233, 0x223344, ctypes.addressof(value))
        entry = ('json' if json else 'plain') + ('Tags' if category == 1 else 'Denick' if category == 3 else 'Local' if category == 4 else '')
        result = self.function(entry, 4)(*args)
        self.assertEqual(result, 1 if consumed else 0x4455 if json else 0x3344)
        originals = [e for e in self.events if e[0] == 'original']
        self.assertEqual(originals, [] if consumed else [('original', 'json' if json else 'plain', args, self.pending)])
        return args

    def callbacks(self, name=None):
        return [e for e in self.events if e[0] == 'callback' and (name is None or e[3] == name)]

    def make_layout(self, screen=800, width=200):
        context = ctypes.create_string_buffer(0x260)
        model = ctypes.create_string_buffer(0xf0)
        layout = (ctypes.c_int * 10)(100, 20, width, 200, 22, 40, 180, 0, 0, 0)
        struct.pack_into('<ii', context, 0x240, 12, 0)
        struct.pack_into('<i', context, 0x250, 4)
        struct.pack_into('<ii', model, 0xe8, screen, 600)
        self.keep.extend((context, model, layout))
        self.make_columns(model, [('name', 5, 128), ('hp', 136, 24), ('stars', 163, 40),
                                 ('fkdr', 206, 36), ('fklv', 245, 48), ('seraph', 296, 42),
                                 ('urchin', 341, 80)])
        caller = ctypes.WINFUNCTYPE(ctypes.c_void_p, *([ctypes.c_void_p] * 4))(self.base + 0x1000)
        result = caller(self.env_ptr, ctypes.addressof(context), ctypes.addressof(layout), ctypes.addressof(model))
        self.assertEqual(result, ctypes.addressof(layout))
        return context, model, layout

    def make_columns(self, model, columns, heap_keys=False):
        data = ctypes.create_string_buffer(max(1, len(columns) * 0x58))
        self.keep.append(data)
        for i, column in enumerate(columns):
            name, x, width = column[:3]
            enabled = column[3] if len(column) > 3 else 1
            key = self.string(name, heap=heap_keys)
            title = self.string({'fklv': 'FK/LV', 'urchin': 'Urchin'}.get(name, name))
            address = ctypes.addressof(data) + i * 0x58
            ctypes.memmove(address, key, 32)
            ctypes.memmove(address + 32, title, 32)
            ctypes.memmove(address + 64, struct.pack('<iiiB3xii', x, width, 1, enabled, i, 0), 24)
        begin = ctypes.addressof(data)
        struct.pack_into('<QQQ', model, 0xd0, begin, begin + len(columns) * 0x58, begin + len(columns) * 0x58)
        return data

    def make_rows(self, model, names):
        rows = ctypes.create_string_buffer(max(1, len(names) * 0x178))
        self.keep.append(rows)
        for i, name in enumerate(names):
            value = self.string(name)
            ctypes.memmove(ctypes.addressof(rows) + i * 0x178 + 0x40, value, 32)
        struct.pack_into('<QQ', model, 0, ctypes.addressof(rows), ctypes.addressof(rows) + len(names) * 0x178)
        return rows

    def set_row_metrics(self, rows, index, values):
        row = ctypes.addressof(rows) + index * 0x178
        sentinel = ctypes.create_string_buffer(0x50)
        buckets = ctypes.create_string_buffer(16)
        self.keep.extend((sentinel, buckets))
        ctypes.c_uint64.from_address(row + 0xd0).value = ctypes.addressof(sentinel)
        ctypes.c_uint64.from_address(row + 0xe0).value = ctypes.addressof(buckets)
        self.maps[row + 0xc8] = {}
        nodes = {}
        for name, raw_value in values.items():
            if raw_value is None:
                self.maps[row + 0xc8][name] = ctypes.addressof(sentinel)
                continue
            node = ctypes.create_string_buffer(0x50)
            value = self.string(raw_value)
            ctypes.memmove(ctypes.addressof(node) + 0x30, value, 32)
            self.keep.append(node)
            self.maps[row + 0xc8][name] = ctypes.addressof(node)
            nodes[name] = node
        return nodes

    def render(self, context, model, layout):
        args = (ctypes.addressof(context), self.env_ptr, 0x12345678, ctypes.addressof(model), ctypes.addressof(layout))
        self.assertEqual(self.function('render', 5)(*args), 0x5566)
        self.assertIn(('original', 'render', args, self.pending), self.events)

    def test_output_plain_sso_and_original_abi(self):
        self.output_call()
        self.assertEqual(self.callbacks(), [('callback', self.env_ptr, 0x12340000, 'nativeRenderGeneratedEvent', ('hello', False, 0))])
        self.assertFalse(self.refs)
        self.assertIn(('method', self.env_ptr, 0x12340000, 'nativeRenderGeneratedEvent', '(Ljava/lang/String;ZI)I'), self.events)

    def test_output_consumes_exactly_one_successful_localized_delivery(self):
        for json_mode in (False, True):
            for category in (0, 1, 3):
                self.events.clear()
                self.output_ack = 1
                self.output_call('[Adnin] UnitPlayer is nicked', json=json_mode, category=category, consumed=True)
                self.assertEqual(len(self.callbacks()), 1)
                self.assertFalse(self.refs)
                self.assertFalse(self.pending)

    def test_output_requires_exact_ack_and_recovers_callback_exception(self):
        for ack, failure in ((-1, None), (2, None), (1, 'callback-exception')):
            self.events.clear()
            self.output_ack = ack
            self.failure = failure
            self.output_call()
            self.assertEqual(len(self.callbacks()), 1)
            self.assertFalse(self.refs)
            self.assertFalse(self.pending)

    def test_local_api_error_category_preserves_ack_and_original_fallback(self):
        text = '[Adnin] Invalid Hypixel API key. Check your key in settings.'
        for ack in (0, 1):
            self.events.clear()
            self.output_ack = ack
            self.output_call(text, category=4, consumed=ack == 1)
            self.assertEqual(self.callbacks()[0][4], (text, False, 4))
            self.assertFalse(self.refs)

    def test_retired_detector_is_not_called_and_owned_pump_continuation_runs(self):
        item = self.report['legacyAnticheat']
        replacement = pefile.PE(data=self.output).get_data(item['callRva'], 5)
        self.assertEqual(replacement, b'\x90' * 5)
        self.install_original(item['originalTargetRva'], 'legacy-anticheat', 4, None)
        # Only the newly authored NOP replacement is copied from the result.
        # The surrounding stack frame/continuation and target stub are owned
        # fixtures; no original DLL function body is copied or executed.
        code = b'\x48\x83\xec\x28' + replacement + bytes.fromhex('b8115a00004883c428c3')
        address = self.base + item['callRva'] - 4
        ctypes.memmove(address, code, len(code))
        run = ctypes.WINFUNCTYPE(ctypes.c_uint32, *([ctypes.c_void_p] * 4))(address)
        for historical_gui_value in (None, 1, 0xffffffffffffffff):
            self.assertEqual(run(self.env_ptr, 0x112233, 0x223344, historical_gui_value), 0x5a11)
        self.assertFalse(any(e[:2] == ('original', 'legacy-anticheat') for e in self.events))

    def test_skin_success_plain_payload_keeps_local_chat_and_emits_one_event(self):
        text = '\u00a77[\u00a71Adnin\u00a77] \u00a7bisa5 \u00a77-> \u00a7aAdnin'
        self.output_call(text, category=3)
        self.assertEqual(self.callbacks(), [('callback', self.env_ptr, 0x12340000, 'nativeRenderGeneratedEvent', (text, False, 3))])
        self.assertFalse(self.refs)

    def match_call(self, env=None):
        caller = ctypes.WINFUNCTYPE(ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)(self.base + 0x1100)
        self.assertEqual(caller(self.env_ptr if env is None else env, 0x12345678), 0x6677)
        originals = [event for event in self.events if event[0] == 'original']
        self.assertEqual(originals, [('original', 'match', (0x12345678,), self.events[0][3])])
        self.assertEqual(self.events[0][0:2], ('original', 'match'))

    def test_match_start_calls_original_before_java_and_preserves_abi(self):
        self.match_call()
        self.assertEqual(self.callbacks(), [('callback', self.env_ptr, 0x12340000, 'nativeMatchStarted', ())])
        self.assertIn(('method', self.env_ptr, 0x12340000, 'nativeMatchStarted', '()V'), self.events)
        self.assertFalse(any(event[0] in ('new', 'delete', 'lookup') for event in self.events))

    def test_match_start_preserves_preexisting_exception(self):
        self.pending = True
        self.match_call()
        self.assertTrue(self.pending)
        self.assertFalse(self.callbacks())
        self.assertFalse(any(event[0] in ('clear', 'method') for event in self.events))

    def test_match_start_missing_method_and_callback_errors_preserve_reset(self):
        for failure in ('method-null', 'method-exception', 'callback-exception'):
            self.failure = failure
            self.events.clear()
            self.match_call()
            self.assertFalse(self.pending)
            self.assertEqual(sum(event[0] == 'clear' for event in self.events), int('exception' in failure))

    def test_match_start_missing_class_or_env_still_runs_original(self):
        self.match_call(env=0)
        self.assertEqual(len(self.events), 1)
        ctypes.c_uint64.from_address(self.base + 0x1a63a8).value = 0
        self.events.clear()
        self.match_call()
        self.assertEqual(len(self.events), 1)

    def test_seraph_leaf_constructor_builds_local_and_party_prefix(self):
        value = ctypes.create_string_buffer(b'X' * 32, 32)
        pointer = ctypes.addressof(value)
        self.assertEqual(self.function('seraphPrefix', 1)(pointer), pointer)
        length, capacity = struct.unpack_from('<QQ', value, 16)
        self.assertEqual((length, capacity), (15, 15))
        self.assertEqual(value.raw[length], 0)
        prefix = value.raw[:length].decode('utf8')
        self.assertEqual(prefix, '\u00a7d[Seraph]\u00a7r ')
        plain = prefix + 'ExamplePlayer is blacklisted for example reason'
        # Same native prefix goes directly into the plain message and through
        # JSON escaping for the JSON variant, before the two output callbacks.
        json_text = json.dumps({'text': '', 'extra': [{'text': prefix}, {'text': 'ExamplePlayer [Example tag]'}]})
        self.output_call(plain, category=1)
        self.assertEqual(self.callbacks()[0][4], (plain, False, 1))
        self.events.clear()
        self.output_call(json_text, json=True, category=1)
        self.assertEqual(self.callbacks()[0][4][1:], (True, 1))
        forwarded_json = self.callbacks()[0][4][0]
        reconstructed = ''.join(item['text'] for item in json.loads(forwarded_json)['extra'])
        import re
        party = '/pc ' + re.sub('\u00a7[0-9a-fk-or]', '', reconstructed, flags=re.I)
        self.assertEqual(party, '/pc [Seraph] ExamplePlayer [Example tag]')
        self.assertNotIn('Frenchify', plain + reconstructed)

    def test_output_json_heap_and_original_abi(self):
        self.output_call('{"text":"a longer test string"}', json=True)
        self.assertEqual(self.callbacks()[0][4], ('{"text":"a longer test string"}', True, 0))
        self.assertFalse(self.refs)

    def test_output_preexisting_exception_is_untouched(self):
        self.pending = True
        self.output_call()
        self.assertTrue(self.pending)
        self.assertFalse(self.callbacks())
        self.assertFalse(any(e[0] in ('clear', 'new', 'method') for e in self.events))

    def test_output_missing_class_skips_jni(self):
        ctypes.c_uint64.from_address(self.base + 0x1a63a8).value = 0
        self.output_call()
        self.assertEqual(len(self.events), 1)

    def test_output_empty_and_oversized_lengths_are_ignored(self):
        for length in (0, 16385, 0xffffffffffffffff):
            self.events.clear()
            self.output_call(declared_length=length)
            self.assertEqual(len(self.events), 1)

    def test_output_optional_failure_does_not_block_original(self):
        for failure in ('new-null', 'new-exception', 'new-ref-exception', 'method-null', 'method-exception', 'callback-exception'):
            with self.subTest(failure=failure):
                self.events.clear()
                self.failure = failure
                self.output_call()
                self.assertFalse(self.pending)
                self.assertFalse(self.refs)
                self.assertEqual(sum(e[0] == 'clear' for e in self.events), int('exception' in failure))

    def test_layout_forwards_original_model_and_preserves_all_slots(self):
        _, _, layout = self.make_layout()
        self.assertEqual(list(layout), [100, 20, 200, 200, 22, 40, 180, 0, 0, 0])
        self.assertEqual(len(self.events), 1)
        self.assertEqual(self.events[0][:2], ('original', 'layout'))

    def test_layout_does_not_append_a_second_extension_width(self):
        for desired, screen in ((1000, 300), (-1, 800), (0, 800), (30, 800), (1000, 240)):
            with self.subTest(desired=desired, screen=screen):
                self.return_width = desired
                _, _, layout = self.make_layout(screen=screen)
                self.assertEqual(list(layout), [100, 20, 200, 200, 22, 40, 180, 0, 0, 0])
        self.assertFalse(any(e[0] == 'width' for e in self.events))

    def test_layout_preserves_existing_exception(self):
        self.pending = True
        _, _, layout = self.make_layout()
        self.assertEqual(layout[8], 0)
        self.assertTrue(self.pending)
        self.assertFalse(any(e[0] == 'clear' for e in self.events))

    def test_layout_never_calls_optional_java(self):
        self.failure = 'callback-exception'
        _, _, layout = self.make_layout()
        self.assertEqual(layout[8], 0)
        self.assertFalse(self.pending)
        self.assertEqual(len(self.events), 1)

    def test_catalog_appends_native_owned_short_string_definitions(self):
        vector = (ctypes.c_uint64 * 3)()
        address = ctypes.addressof(vector)
        self.assertEqual(self.function('columnCatalog', 1)(address), address)
        self.assertEqual((vector[1] - vector[0]) // 0x58, 31)
        self.assertEqual(vector[1], vector[2])
        result = ctypes.string_at(vector[0], 31 * 0x58)
        self.assertEqual(result[:29 * 0x58], self.catalog_original)
        self.assertEqual([e[0] for e in self.events], ['original-catalog', 'append-column', 'append-column'])
        for index, name, title, width in ((29, 'fklv', 'FK/LV', 48), (30, 'urchin', 'Urchin', 80)):
            descriptor = result[index * 0x58:(index + 1) * 0x58]
            self.assertEqual(descriptor[:16].split(b'\0')[0].decode('ascii'), name)
            self.assertEqual(descriptor[32:48].split(b'\0')[0].decode('ascii'), title)
            self.assertEqual(struct.unpack_from('<QQ', descriptor, 16), (len(name), 15))
            self.assertEqual(struct.unpack_from('<QQ', descriptor, 48), (len(title), 15))
            self.assertEqual(struct.unpack_from('<iiiB3xii', descriptor, 64), (0, width, 1, 1, index, 0))
            source_address, source_before = self.catalog_sources[index - 29]
            self.assertEqual(ctypes.string_at(source_address, 0x58), source_before)
            self.assertEqual(descriptor, source_before)
            self.assertFalse(vector[0] <= source_address < vector[2])
        self.assertFalse(self.callbacks())

    def test_tab_header_rows_coordinates_and_fifth_argument(self):
        context, model, layout = self.make_layout()
        self.make_rows(model, ['Alex', 'abcdefghijklmnop'])
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual(self.events[0][0:2], ('original', 'render'))
        self.assertFalse(self.callbacks('nativeUrchinHeader'))
        self.assertFalse(self.callbacks('nativeOverlayRow'))
        self.assertEqual([e[4] for e in self.callbacks('nativeOrderedOverlayRow')],
                         [('Alex', 40.0, 345.0, 48.0, 441.0, 80.0, None, None),
                          ('abcdefghijklmnop', 52.0, 345.0, 48.0, 441.0, 80.0, None, None)])
        self.assertFalse(self.refs)
        self.assertFalse(self.pending)

    def test_tab_absent_or_disabled_extension_columns_skip_callbacks(self):
        context, model, layout = self.make_layout()
        self.make_rows(model, ['Alex'])
        self.make_columns(model, [('name', 5, 128), ('fkdr', 136, 36), ('seraph', 175, 42)])
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual(len(self.events), 1)
        self.make_columns(model, [('fklv', 5, 48, 0), ('urchin', 56, 80, 0)])
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual(len(self.events), 1)

    def test_tab_extension_positions_follow_native_order_in_both_directions(self):
        context, model, layout = self.make_layout()
        self.make_rows(model, ['Alex'])
        for columns, expected in (
                ([('name', 5, 128), ('fklv', 136, 48), ('fkdr', 187, 36), ('urchin', 226, 80), ('seraph', 309, 42)],
                 (236.0, 48.0, 326.0, 80.0)),
                ([('urchin', 5, 80), ('seraph', 88, 42), ('fklv', 133, 48), ('fkdr', 184, 36)],
                 (233.0, 48.0, 105.0, 80.0))):
            self.make_columns(model, columns)
            self.events.clear()
            self.render(context, model, layout)
            self.assertEqual(self.callbacks('nativeOrderedOverlayRow')[0][4][2:6], expected)

    def test_tab_single_column_passes_zero_width_for_absent_column(self):
        context, model, layout = self.make_layout()
        self.make_rows(model, ['Alex'])
        for columns, expected in (([('fklv', 20, 48)], (120.0, 48.0, 0.0, 0.0)),
                                  ([('urchin', 50, 80)], (0.0, 0.0, 150.0, 80.0))):
            self.make_columns(model, columns, heap_keys=True)
            self.events.clear()
            self.render(context, model, layout)
            self.assertEqual(self.callbacks('nativeOrderedOverlayRow')[0][4][2:6], expected)

    def test_tab_invalid_column_spans_and_widths_are_bounded(self):
        context, model, layout = self.make_layout()
        self.make_rows(model, ['Alex'])
        for delta in (-1, 0x58 * 129, 1):
            columns = self.make_columns(model, [('fklv', 5, 48)])
            struct.pack_into('<Q', model, 0xd8, ctypes.addressof(columns) + delta)
            self.events.clear()
            self.render(context, model, layout)
            self.assertEqual(len(self.events), 1)
        for x, width in ((5, 0), (5, -1), (5, 4097), (-1, 48), (65537, 48)):
            self.make_columns(model, [('fklv', x, width)])
            self.events.clear()
            self.render(context, model, layout)
            self.assertEqual(len(self.events), 1)

    def test_tab_existing_exception_is_not_cleared(self):
        context, model, layout = self.make_layout()
        self.make_rows(model, ['Alex'])
        self.pending = True
        self.events.clear()
        self.render(context, model, layout)
        self.assertTrue(self.pending)
        self.assertFalse(self.callbacks())
        self.assertFalse(any(e[0] == 'clear' for e in self.events))

    def test_tab_invalid_row_spans_are_bounded(self):
        for delta in (-1, 0x178 * 1001, 1):
            context, model, layout = self.make_layout()
            self.make_rows(model, ['Alex'])
            start = struct.unpack_from('<Q', model)[0]
            struct.pack_into('<Q', model, 8, start + delta)
            self.events.clear()
            self.render(context, model, layout)
            self.assertFalse(self.callbacks('nativeOrderedOverlayRow'))

    def test_tab_callback_failures_cleanup_and_keep_base_draw(self):
        context, model, layout = self.make_layout()
        self.make_rows(model, ['Alex', 'Steve'])
        self.failure = 'callback-exception'
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual(len(self.callbacks('nativeOrderedOverlayRow')), 2)
        self.assertFalse(self.pending)
        self.assertFalse(self.refs)
        self.assertEqual(sum(e[0] == 'clear' for e in self.events), 2)

    def test_metrics_pass_full_integer_values_and_free_three_refs(self):
        context, model, layout = self.make_layout()
        rows = self.make_rows(model, ['Alex'])
        self.set_row_metrics(rows, 0, {'finalKills': '2147483647', 'stars': '4294967295'})
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual(self.callbacks('nativeOrderedOverlayRow')[0][4],
                         ('Alex', 40.0, 345.0, 48.0, 441.0, 80.0, '2147483647', '4294967295'))
        self.assertEqual([(e[2], e[3]) for e in self.events if e[0] == 'lookup'],
                         [('finalKills', 0x40d2ddcc4d06d23c), ('stars', 0xee5d94ad45ad2006)])
        self.assertEqual(sum(e[0] == 'delete' for e in self.events), 3)
        self.assertFalse(self.refs)
        self.assertIn(('method', self.env_ptr, 0x12340000, 'nativeOrderedOverlayRow',
                       '(Ljava/lang/String;FFFFFLjava/lang/String;Ljava/lang/String;)V'), self.events)

    def test_metrics_missing_sentinel_empty_and_uninitialized_map(self):
        context, model, layout = self.make_layout()
        rows = self.make_rows(model, ['Empty', 'Sentinel', 'Uninitialized'])
        self.set_row_metrics(rows, 0, {'finalKills': ''})
        self.set_row_metrics(rows, 1, {'finalKills': None, 'stars': None})
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual([e[4][6:] for e in self.callbacks('nativeOrderedOverlayRow')], [(None, None)] * 3)
        self.assertEqual(sum(e[0] == 'lookup' for e in self.events), 4)
        self.assertFalse(self.refs)

    def test_metrics_heap_values_are_not_rounded_or_abbreviated(self):
        context, model, layout = self.make_layout()
        rows = self.make_rows(model, ['Alex'])
        # Native integers currently fit SSO. A longer test value proves this
        # bridge forwards the exact bytes without any numeric conversion.
        self.set_row_metrics(rows, 0, {'finalKills': '12345678901234567890', 'stars': '0'})
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual(self.callbacks('nativeOrderedOverlayRow')[0][4][6:], ('12345678901234567890', '0'))
        self.assertFalse(self.refs)

    def test_metrics_forward_verified_native_star_formats_without_losing_digits(self):
        # FUN_180025fe0 century100 branch (0x26359) produces the first value.
        # The 1000 branch uses a distinct color before each of the four digits.
        # These are display strings, not decimal strings accepted by parseDouble.
        context, model, layout = self.make_layout()
        rows = self.make_rows(model, ['Iron', 'Rainbow'])
        values = [('4517', '\u00a7f[187\u272b]'),
                  ('123456', '\u00a7c[\u00a761\u00a7e0\u00a7a0\u00a7b0\u00a7d\u272b\u00a75]')]
        for index, (finals, stars) in enumerate(values):
            self.set_row_metrics(rows, index, {'finalKills': finals, 'stars': stars})
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual([e[4][6:] for e in self.callbacks('nativeOrderedOverlayRow')], values)
        self.assertEqual(sum(e[0] == 'delete' for e in self.events), 6)
        self.assertFalse(self.refs)
        self.assertFalse(self.pending)

    def test_metrics_invalid_lengths_capacity_and_terminator_are_ignored(self):
        for corruption in ('length', 'capacity', 'terminator'):
            context, model, layout = self.make_layout()
            rows = self.make_rows(model, ['Alex'])
            nodes = self.set_row_metrics(rows, 0, {'finalKills': '123', 'stars': '5'})
            node = nodes['finalKills']
            if corruption == 'length':
                struct.pack_into('<Q', node, 0x40, 65)
            elif corruption == 'capacity':
                struct.pack_into('<Q', node, 0x48, 2)
            else:
                node[0x33] = b'X'
            self.events.clear()
            self.render(context, model, layout)
            self.assertEqual(self.callbacks('nativeOrderedOverlayRow')[0][4][6:], (None, '5'))
            self.assertFalse(self.refs)

    def test_metrics_optional_jni_failure_preserves_other_cell_and_row(self):
        for failure in ('new-null', 'new-exception', 'new-ref-exception'):
            context, model, layout = self.make_layout()
            rows = self.make_rows(model, ['Alex'])
            self.set_row_metrics(rows, 0, {'finalKills': '123', 'stars': '5'})
            self.failure, self.failure_text = failure, '123'
            self.events.clear()
            self.render(context, model, layout)
            self.assertEqual(self.callbacks('nativeOrderedOverlayRow')[0][4][6:], (None, '5'))
            self.assertFalse(self.pending)
            self.assertFalse(self.refs)

    def test_metrics_callback_exception_deletes_all_three_references(self):
        context, model, layout = self.make_layout()
        rows = self.make_rows(model, ['Alex'])
        self.set_row_metrics(rows, 0, {'finalKills': '123', 'stars': '5'})
        self.failure = 'callback-exception'
        self.events.clear()
        self.render(context, model, layout)
        self.assertEqual(sum(e[0] == 'delete' for e in self.events), 3)
        self.assertFalse(self.pending)
        self.assertFalse(self.refs)

    def test_windows_unwinds_every_output_tail_epilog_position(self):
        # Exercise Windows' real epilog decoder against our relative tail JMPs.
        # The stack and CONTEXT are synthetic; RtlVirtualUnwind executes no code.
        ntdll = ctypes.WinDLL('ntdll')
        unwind = ntdll.RtlVirtualUnwind
        unwind.argtypes = [ctypes.c_uint32, ctypes.c_uint64, ctypes.c_uint64,
                           ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                           ctypes.c_void_p, ctypes.c_void_p]
        unwind.restype = ctypes.c_void_p
        entry = next(f for f in self.report['bridgeRuntimeFunctions'] if f['name'] == 'output')
        runtime_function = (ctypes.c_uint32 * 3)(entry['begin'], entry['end'], entry['unwind'])
        pattern = bytes.fromhex('48 83 c4 40 41 5d 41 5c 5f 5e 5b e9')
        original_pe = pefile.PE(data=self.output)
        code = original_pe.get_data(entry['begin'], entry['end'] - entry['begin'])
        starts = [i for i in range(len(code)) if code.startswith(pattern, i)]
        self.assertEqual(len(starts), 2)
        offsets = (0, 4, 6, 8, 9, 10, 11)
        stack_moves = (0, 64, 72, 80, 88, 96, 104)
        expected_ip = 0x00007ffefedc1234
        for start in starts:
            for offset, move in zip(offsets, stack_moves):
                stack = ctypes.create_string_buffer(2048)
                context = ctypes.create_string_buffer(1232)
                sp = ctypes.addressof(stack) + 128
                struct.pack_into('<6Q', stack, 128 + 64,
                                 0x13131313, 0x12121212, 0x77777777,
                                 0x66666666, 0x33333333, expected_ip)
                struct.pack_into('<I', context, 0x30, 0x00100003)
                struct.pack_into('<Q', context, 0x98, sp + move)
                pc = self.base + entry['begin'] + start + offset
                struct.pack_into('<Q', context, 0xf8, pc)
                handler_data, frame = ctypes.c_void_p(), ctypes.c_uint64()
                unwind(0, self.base, pc, ctypes.byref(runtime_function), context,
                       ctypes.byref(handler_data), ctypes.byref(frame), None)
                self.assertEqual(struct.unpack_from('<Q', context, 0x98)[0], sp + 112)
                self.assertEqual(struct.unpack_from('<Q', context, 0xf8)[0], expected_ip)

    def test_windows_unwinds_metrics_render_and_other_complete_frames(self):
        unwind = ctypes.WinDLL('ntdll').RtlVirtualUnwind
        unwind.argtypes = [ctypes.c_uint32, ctypes.c_uint64, ctypes.c_uint64,
                           ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                           ctypes.c_void_p, ctypes.c_void_p]
        unwind.restype = ctypes.c_void_p
        frames = {'output': (0x40, [13, 12, 7, 6, 3]), 'invoke': (0x20, [13, 12, 7, 6, 3]),
                  'header': (0x40, [13, 12, 7, 6, 3]),
                  'string': (0x20, [7, 6, 3]), 'layout': (0x40, [13, 12, 7, 6, 3]),
                  'render': (0x80, [15, 14, 13, 12, 7, 6, 3]),
                  'denicker': (0x370, [15, 14, 13, 12, 7, 6, 3]),
                  'metrics': (0x30, [7, 6, 3]), 'match': (0x20, [7, 6, 3]),
                  'column': (0x20, [3]), 'replayRdi': (0xf0, [5]), 'replayRbx': (0xf0, [5]),
                  'replayFrame': (0xf0, [5]), 'lunarStop': (0x30, [3])}
        register_offsets = {3: 0x90, 5: 0xa0, 6: 0xa8, 7: 0xb0, 12: 0xd8, 13: 0xe0, 14: 0xe8, 15: 0xf0}
        expected_ip = 0x00007ffefedc1234
        for entry in self.report['bridgeRuntimeFunctions']:
            if entry['name'] not in frames: continue  # Replay stats handlers have dedicated checks.
            allocation, saved = frames[entry['name']]
            runtime_function = (ctypes.c_uint32 * 3)(entry['begin'], entry['end'], entry['unwind'])
            stack = ctypes.create_string_buffer(2048)
            context = ctypes.create_string_buffer(1232)
            sp = ctypes.addressof(stack) + 128
            for i, register in enumerate(saved):
                struct.pack_into('<Q', stack, 128 + allocation + i * 8, 0xdead0000 + register)
            struct.pack_into('<Q', stack, 128 + allocation + len(saved) * 8, expected_ip)
            struct.pack_into('<I', context, 0x30, 0x00100003)
            struct.pack_into('<Q', context, 0x98, sp)
            if entry['name'].startswith('replay'):
                struct.pack_into('<Q', context, 0xa0, sp + allocation)
            prologue_size = ctypes.c_ubyte.from_address(self.base + entry['unwind'] + 1).value
            pc = self.base + entry['begin'] + prologue_size
            struct.pack_into('<Q', context, 0xf8, pc)
            handler_data, frame = ctypes.c_void_p(), ctypes.c_uint64()
            unwind(0, self.base, pc, ctypes.byref(runtime_function), context,
                   ctypes.byref(handler_data), ctypes.byref(frame), None)
            self.assertEqual(struct.unpack_from('<Q', context, 0x98)[0], sp + allocation + len(saved) * 8 + 8)
            self.assertEqual(struct.unpack_from('<Q', context, 0xf8)[0], expected_ip)
            for register in saved:
                self.assertEqual(struct.unpack_from('<Q', context, register_offsets[register])[0], 0xdead0000 + register)


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0]] + UNITTEST_ARGS)
