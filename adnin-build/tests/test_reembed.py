"""PE/ABI regression tests. Does not execute the original or generated DLL.

Run: python tests/test_reembed.py --input ../lunar-full-build/build/bin/ChatReaderLunar.dll
"""
import argparse
import copy
import json
import re
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import pefile
import reembed
from classfile import read_class, rewrite_utf8

parser = argparse.ArgumentParser(add_help=False)
parser.add_argument('--input', type=Path, default=ROOT.parent / 'lunar-full-build/build/bin/ChatReaderLunar.dll')
parser.add_argument('--index', type=Path, default=ROOT / 'evidence/original-class-index.json')
ARGS, UNITTEST_ARGS = parser.parse_known_args()


class ReembeddingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = ARGS.input.read_bytes()
        cls.records = json.loads(ARGS.index.read_text(encoding='utf-8-sig'))
        cls.classes = {}
        cls.old_classes = {}
        for record in cls.records:
            start = int(record['offset'], 16)
            old = cls.original[start:start + record['size']]
            cls.old_classes[record['name']] = old
            cls.classes[reembed.renamed(record['name'])] = reembed.branded_class(old)
        cls.output, cls.report = reembed.rebuild(cls.original, cls.classes, cls.records)
        cls.before = pefile.PE(data=cls.original)
        cls.after = pefile.PE(data=cls.output)

    def test_deterministic_build(self):
        binary, report = reembed.rebuild(self.original, self.classes, self.records)
        self.assertEqual(binary, self.output)
        self.assertEqual(report, self.report)

    def test_ten_valid_renamed_classes_and_defineclass_targets(self):
        self.assertEqual(len(self.report['classes']), 10)
        for item in self.report['classes']:
            data = self.output[item['offset']:item['offset'] + item['size']]
            info = read_class(data)
            self.assertEqual(info['name'], item['name'])
            self.assertEqual(info['major'], 52)
            self.assertEqual(reembed.sha(data), item['sha256'])
            self.assertEqual(self.after.get_offset_from_rva(item['rva']), item['offset'])
            self.assertEqual(item['rva'] % 16, 0)
            if 'leaRva' in item:
                pointer = self.after.get_offset_from_rva(item['leaRva'])
                target = item['leaRva'] + 7 + struct.unpack_from('<i', self.output, pointer + 3)[0]
                self.assertEqual(target, item['rva'])
                length = self.after.get_offset_from_rva(item['lengthRva'])
                self.assertEqual(struct.unpack_from('<I', self.output, length + 4)[0], item['size'])

    def test_existing_sections_do_not_move(self):
        for old, new in zip(self.before.sections, self.after.sections):
            self.assertEqual(old.__pack__(), new.__pack__())
        section = self.after.sections[-1]
        self.assertEqual(section.Name.rstrip(b'\0'), b'.adnin')
        self.assertFalse(section.Characteristics & 0x20000000, 'Java class bytes must not be executable')
        self.assertFalse(section.Characteristics & 0x80000000, 'Java class bytes must not be writable')
        self.assertTrue(section.Characteristics & 0x40000000)
        self.assertEqual(section.VirtualAddress, 0x1ba000)
        self.assertEqual(section.PointerToRawData, len(self.original))
        self.assertEqual(self.after.OPTIONAL_HEADER.SizeOfImage,
                         reembed.align(section.VirtualAddress + section.Misc_VirtualSize, 0x1000))

    def test_export_addresses_ordinals_and_imports_preserved(self):
        before = [(s.name.replace(b'Frenchify', b'Adnin'), s.address, s.ordinal)
                  for s in self.before.DIRECTORY_ENTRY_EXPORT.symbols]
        after = [(s.name, s.address, s.ordinal) for s in self.after.DIRECTORY_ENTRY_EXPORT.symbols]
        self.assertEqual(before, after)
        self.assertEqual(len(after), 11)
        self.assertTrue(all(name.startswith(b'Java_Adnin') for name, _, _ in after))
        # Import, IAT, relocations, exception/unwind data and TLS directories
        # must retain their original RVAs and sizes.
        for index in (1, 3, 5, 9, 12):
            self.assertEqual(self.before.OPTIONAL_HEADER.DATA_DIRECTORY[index].__pack__(),
                             self.after.OPTIONAL_HEADER.DATA_DIRECTORY[index].__pack__())

    def test_all_branding_replaced_without_changing_fixed_color_lengths(self):
        self.assertIsNone(re.search(b'frenchify', self.output, re.I))
        self.assertNotIn(reembed.COLORED_OLD, self.output)
        self.assertEqual(self.output.count(reembed.COLORED_PREFIX_NEW), 9)
        self.assertEqual(len(reembed.COLORED_PREFIX_NEW), len(reembed.COLORED_PREFIX_OLD))
        for rva, (_, after) in reembed.LENGTH_PATCHES.items():
            offset = self.after.get_offset_from_rva(rva)
            expected = bytes.fromhex(after)
            self.assertEqual(self.output[offset:offset + len(expected)], expected)

    def test_full_native_prefixes_render_exact_adnin_brackets(self):
        # Full fixed-length copies must keep suffix offsets and never produce
        # a padding space inside the visible [Adnin] bracket.
        def visible(value):
            return re.sub('\u00a7[0-9a-fk-or]', '', value.decode('utf8'), flags=re.I)
        matches = list(re.finditer(re.escape(reembed.COLORED_PREFIX_OLD), self.original))
        self.assertEqual(len(matches), 9)
        for match in matches:
            start, end = match.span()
            self.assertEqual(visible(self.original[start:end]), '[Frenchify]')
            self.assertEqual(visible(self.output[start:end]), '[Adnin] ')
            self.assertEqual(self.original[end:end + 4], self.output[end:end + 4])

    def test_constructed_prefixes_fast_and_reserve_paths_render_adnin(self):
        # Reconstruct the actual std::string appends from the immediate stores,
        # length increments and reserve-call arguments in both native builders.
        # Merely searching for a contiguous brand misses these six immediates.
        constructors = (
            ((0x40b07, 0x40be6, 0x40cc5), (0x40bd1, 0x40cb0),
             (0x40bf8, 0x40cd7), (0x40c0b, 0x40cea), (0x40bcb, 0x40caa)),
            ((0x682e9, 0x683c4, 0x68499), (0x683af, 0x68484),
             (0x683d6, 0x684ab), (0x683e9, 0x684be), (0x683a9, 0x6847e)),
        )
        fragments = (0x16c440, 0x16c438, 0x16c430)
        def prefix(parts):
            text = b'\xc2\xa77[' + b''.join(b'\xc2\xa7' + p for p in parts) + b'\xc2\xa77] '
            self.assertNotIn(b'\0', text, 'A shortened append must not embed its NUL')
            return re.sub('\u00a7[0-9a-fk-or]', '', text.decode('utf8'), flags=re.I)
        for pe, expected in ((self.before, '[Frenchify] '), (self.after, '[Adnin] ')):
            cstrings = [pe.get_data(rva, 5).split(b'\0', 1)[0] for rva in fragments]
            # The direct strlen-based builder at RVA 0x87c79 uses these slots.
            self.assertEqual(prefix(cstrings), expected)
            for stores, increments, stack_counts, register_counts, guards in constructors:
                lengths = [4] + [pe.get_data(rva, 4)[3] for rva in increments]
                pieces = [pe.get_data(rva, 7)[3:7][:length] for rva, length in zip(stores, lengths)]
                self.assertEqual(prefix(pieces), expected)
                self.assertEqual(pieces, cstrings)
                self.assertEqual(lengths[1:], [struct.unpack('<I', pe.get_data(rva + 5, 4))[0] for rva in stack_counts])
                self.assertEqual(lengths[1:], [struct.unpack('<I', pe.get_data(rva + 1, 4))[0] for rva in register_counts])
                # Keep enough space for the original four-byte store plus NUL.
                self.assertTrue(all(pe.get_data(rva, 4) == bytes.fromhex('4883f804') for rva in guards))
        self.assertEqual(self.report['constructedBrandingPatched']['appendedFragmentLengths'], [4, 3, 1])

    def test_native_api_error_prose_is_entirely_red_in_equal_width_data_slots(self):
        expected_rvas = {0x13e2d0, 0x13e330}
        self.assertEqual(set(reembed.NATIVE_ERROR_STRINGS), expected_rvas)
        ledger = [p for p in self.report['patches']
                  if p['reason'].startswith('native API error prose red and [Adnin] prefix:')]
        self.assertEqual({p['rva'] for p in ledger}, expected_rvas)
        self.assertEqual(len(ledger), 2)
        for rva, (old, new) in reembed.NATIVE_ERROR_STRINGS.items():
            self.assertEqual(len(old), len(new))
            self.assertEqual(self.before.get_data(rva, len(old)), old)
            self.assertEqual(self.after.get_data(rva, len(new)), new)
            self.assertTrue(old.endswith(b'\0') and new.endswith(b'\0'))
            expected = old.replace(reembed.COLORED_PREFIX_OLD, reembed.COLORED_PREFIX_NEW, 1)
            expected = expected.replace('\u00a77. Check your key in settings.'.encode('utf8'),
                                        '\u00a7c. Check your key in settings.'.encode('utf8'), 1)
            self.assertEqual(new, expected)
            body = new[len(reembed.COLORED_PREFIX_NEW):].decode('utf8')
            self.assertEqual(re.findall('\u00a7([0-9a-fk-or])', body), ['c', 'c'])
            section = self.after.get_section_by_rva(rva)
            self.assertEqual(section.Name.rstrip(b'\0'), b'.rdata')
            self.assertFalse(section.Characteristics & 0x20000000)
            entry = next(p for p in ledger if p['rva'] == rva)
            self.assertEqual(bytes.fromhex(entry['before']), old)
            self.assertEqual(bytes.fromhex(entry['after']), new)
        self.assertEqual(self.report['nativeErrorProsePatched'], {
            'rvas': [0x13e2d0, 0x13e330], 'bodyColorCode': 'c',
            'sameByteLength': True, 'nativeInstructionsChangedByThisPatch': False,
        })

    def test_error_color_change_adds_no_native_text_patch(self):
        expected = set(reembed.LENGTH_PATCHES) | set(reembed.SPLIT_BRAND_PATCHES)
        for pointer, length in reembed.SITES.values():
            expected.update((pointer + 3, length + 4))
        actual = set()
        for entry in self.report['patches']:
            section = self.before.get_section_by_rva(entry['rva'])
            if section is not None and section.Name.rstrip(b'\0') == b'.text':
                actual.add(entry['rva'])
        self.assertEqual(actual, expected)
        self.assertEqual(reembed.sha(self.original), reembed.FIXED_SHA256)

    def test_stats_failure_retains_white_player_name_color(self):
        rva = 0x13e3c8
        tail = ' \u00a7cUnable to fetch stats for: \u00a7f\0'.encode('utf8')
        old, new = reembed.COLORED_PREFIX_OLD + tail, reembed.COLORED_PREFIX_NEW + tail
        self.assertEqual(self.before.get_data(rva, len(old)), old)
        self.assertEqual(self.after.get_data(rva, len(new)), new)
        self.assertEqual(len(old), len(new))

    def test_error_patch_rejects_a_mismatched_full_original_string(self):
        rva = 0x13e2d0
        old, new = reembed.NATIVE_ERROR_STRINGS[rva]
        wrong = old.replace(b'Invalid Hypixel', b'invalid Hypixel', 1)
        self.assertNotEqual(wrong, old)
        with mock.patch.dict(reembed.NATIVE_ERROR_STRINGS, {rva: (wrong, new)}):
            with self.assertRaisesRegex(ValueError, 'Unexpected bytes for native API error prose'):
                reembed.rebuild(self.original, self.classes, self.records)

    def test_preserves_adjacent_native_string_anchor(self):
        offset = self.before.get_offset_from_rva(reembed.PRESERVED_RVA)
        self.assertEqual(self.output[offset:offset + len(reembed.PRESERVED_BYTES)], reembed.PRESERVED_BYTES)
        record = next(r for r in self.records if r['name'] == 'FrenchifySessionHudInstaller')
        self.assertEqual(int(record['offset'], 16) + record['size'], offset)

    def test_old_slots_contain_valid_renamed_classes(self):
        for record in self.records:
            name = reembed.renamed(record['name'])
            expected = self.classes[name]
            start, size = int(record['offset'], 16), record['size']
            self.assertEqual(self.output[start:start + len(expected)], expected)
            self.assertEqual(self.output[start + len(expected):start + size], bytes(size - len(expected)))
            self.assertEqual(read_class(self.output[start:start + len(expected)])['name'], name)

    def test_every_old_byte_change_is_in_expected_byte_ledger(self):
        approved = bytearray(len(self.original))
        for patch in self.report['patches']:
            offset = patch['offset']
            before, after = bytes.fromhex(patch['before']), bytes.fromhex(patch['after'])
            self.assertEqual(len(before), len(after))
            self.assertEqual(self.original[offset:offset + len(before)], before)
            self.assertEqual(self.output[offset:offset + len(after)], after)
            self.assertFalse(any(approved[offset:offset + len(after)]))
            approved[offset:offset + len(after)] = b'\1' * len(after)
        self.assertTrue(all(a == b or approved[i] for i, (a, b) in enumerate(zip(self.original, self.output))))

    def test_checksum_and_no_false_runtime_claim(self):
        self.assertEqual(self.after.OPTIONAL_HEADER.CheckSum, self.after.generate_checksum())
        self.assertFalse(self.report['gameRuntimeTested'])
        self.assertFalse(self.report['stubClassesEmbedded'])

    def test_dormant_class_can_be_omitted(self):
        classes = dict(self.classes)
        del classes['AdninGuiNewChat']
        output, _ = reembed.rebuild(self.original, classes, self.records)
        self.assertEqual(output, self.output)

    def test_rejects_helper_class_as_unverified_extra(self):
        classes = dict(self.classes, AdninApi=self.classes['AdninClientPump'])
        with self.assertRaisesRegex(ValueError, 'Unexpected Java class'):
            reembed.rebuild(self.original, classes, self.records)

    def test_rejects_missing_active_class(self):
        classes = dict(self.classes)
        del classes['AdninGui4']
        with self.assertRaisesRegex(ValueError, 'Missing active'):
            reembed.rebuild(self.original, classes, self.records)

    def test_rejects_any_changed_native_input(self):
        altered = bytearray(self.original)
        altered[0x1000] ^= 1
        with self.assertRaisesRegex(ValueError, 'Input hash'):
            reembed.rebuild(altered, self.classes, self.records)

    def test_rejects_class_index_hash_mismatch(self):
        records = copy.deepcopy(self.records)
        records[0]['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'class slice changed'):
            reembed.rebuild(self.original, self.classes, records)

    def test_dormant_class_must_not_gain_behavior(self):
        classes = dict(self.classes)
        classes['AdninGuiNewChat'] = rewrite_utf8(classes['AdninGuiNewChat'], [(b'SourceFile', b'SourceFilo')])
        with self.assertRaisesRegex(ValueError, 'Dormant'):
            reembed.rebuild(self.original, classes, self.records)

    def test_additive_fields_methods_and_native_bridge_allowed(self):
        old = read_class(self.old_classes['FrenchifyClientPump'])
        new = read_class(self.classes['AdninClientPump'])
        new['fields'].append({'access': 9, 'name': 'api_urchin', 'descriptor': 'Ljava/lang/String;'})
        new['methods'].append({'access': 0x109, 'name': 'nativeForwardOutput', 'descriptor': '(Ljava/lang/String;)V'})
        reembed.compatible(old, new)

    def test_old_member_cannot_be_removed(self):
        old = read_class(self.old_classes['FrenchifyClientPump'])
        new = read_class(self.classes['AdninClientPump'])
        new['methods'].pop()
        with self.assertRaisesRegex(ValueError, 'Original members removed'):
            reembed.compatible(old, new)

    def test_old_native_or_static_flags_cannot_change(self):
        old = read_class(self.old_classes['FrenchifyClientPump'])
        new = read_class(self.classes['AdninClientPump'])
        native = next(m for m in new['methods'] if m['access'] & 0x100)
        native['access'] &= ~0x100
        with self.assertRaisesRegex(ValueError, 'Member access changed'):
            reembed.compatible(old, new)

    def packetlog_lambda_migration(self):
        old = read_class(self.old_classes['FrenchifyPacketLog'])
        new = read_class(self.classes['AdninPacketLog'])
        source = next(m for m in old['methods'] if m['name'] == 'lambda$install$0')
        self.assertEqual(source['access'], 0x100a)
        expected = ('([Ljava/lang/reflect/Method;[Ljava/lang/reflect/Method;[Ljava/lang/reflect/Method;'
                    'Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;')
        self.assertEqual(source['descriptor'], expected)
        new['methods'] = [m for m in new['methods'] if m['name'] != 'lambda$install$0']
        replacement = {'name': 'lambda$createHandler$0', 'access': 0x100a,
                       'descriptor': '(LAdninPacketLog$InstallRequest;' + expected[1:]}
        new['methods'].append(replacement)
        return old, new

    def test_only_exact_packetlog_synthetic_lambda_relocation_is_allowed(self):
        old, new = self.packetlog_lambda_migration()
        reembed.compatible(old, new)
        self.assertEqual([m for m in old['methods'] if m['access'] & 0x100],
                         [m for m in new['methods'] if m['access'] & 0x100])

    def test_packetlog_lambda_relocation_rejects_near_names_descriptors_and_flags(self):
        for target, key, value in (
                ('new', 'name', 'lambda$createHandler$1'),
                ('new', 'descriptor', '(Ljava/lang/Object;)Ljava/lang/Object;'),
                ('new', 'access', 0x000a),  # non-synthetic
                ('new', 'access', 0x110a),  # native
                ('new', 'access', 0x1009),  # public
                ('new', 'access', 0x1002),  # non-static
                ('old', 'access', 0x000a),
                ('old', 'access', 0x110a),
                ('old', 'access', 0x1009),
                ('old', 'name', 'lambda$install$1'),
                ('old', 'descriptor', '(Ljava/lang/Object;)Ljava/lang/Object;')):
            with self.subTest(target=target, key=key, value=value):
                old, new = self.packetlog_lambda_migration()
                owner = new if target == 'new' else old
                method = next(m for m in owner['methods'] if m['name'].startswith('lambda$'))
                method[key] = value
                with self.assertRaisesRegex(ValueError, 'Original members removed'):
                    reembed.compatible(old, new)

    def test_packetlog_lambda_exception_does_not_apply_to_other_class_or_synthetic_member(self):
        old, new = self.packetlog_lambda_migration()
        old['name'], new['name'] = 'FrenchifyOtherPacketLog', 'AdninOtherPacketLog'
        with self.assertRaisesRegex(ValueError, 'Original members removed'):
            reembed.compatible(old, new)
        old, new = self.packetlog_lambda_migration()
        old['methods'].append({'name': 'lambda$other$0', 'descriptor': '()V', 'access': 0x100a})
        with self.assertRaisesRegex(ValueError, 'Original members removed'):
            reembed.compatible(old, new)

    def test_packetlog_lambda_exception_preserves_native_and_declared_member_abi(self):
        old, new = self.packetlog_lambda_migration()
        new['methods'] = [m for m in new['methods'] if not m['access'] & 0x100]
        with self.assertRaisesRegex(ValueError, 'Original members removed'):
            reembed.compatible(old, new)
        for changed_bit in (0x100, 0x8, 0x2):
            with self.subTest(changed_bit=changed_bit):
                old, new = self.packetlog_lambda_migration()
                native = next(m for m in new['methods'] if m['access'] & 0x100)
                native['access'] ^= changed_bit
                with self.assertRaisesRegex(ValueError, 'Member access changed'):
                    reembed.compatible(old, new)
        for kind, name in (('methods', 'install'), ('fields', 's_spawnPlayerClass')):
            with self.subTest(kind=kind, name=name):
                old, new = self.packetlog_lambda_migration()
                new[kind] = [m for m in new[kind] if m['name'] != name]
                with self.assertRaisesRegex(ValueError, 'Original members removed'):
                    reembed.compatible(old, new)

    def test_old_descriptors_cannot_change(self):
        old = read_class(self.old_classes['FrenchifyGui4'])
        new = read_class(self.classes['AdninGui4'])
        new['fields'][0]['descriptor'] = 'I'
        with self.assertRaisesRegex(ValueError, 'Original members removed'):
            reembed.compatible(old, new)

    def test_only_documented_gui_access_widening_allowed(self):
        old = read_class(self.old_classes['FrenchifyGui4'])
        new = read_class(self.classes['AdninGui4'])
        for method in new['methods']:
            if method['name'] in ('mouseClicked', 'keyTyped'):
                self.assertEqual(method['access'], 4)
                method['access'] = 1
        reembed.compatible(old, new)
        method = next(m for m in new['methods'] if m['name'] == '<init>')
        method['access'] = 4
        with self.assertRaisesRegex(ValueError, 'Member access changed'):
            reembed.compatible(old, new)

    def test_requires_java8_bytecode(self):
        old = read_class(self.old_classes['FrenchifyClientPump'])
        new = read_class(self.classes['AdninClientPump'])
        new['major'] = 61
        with self.assertRaisesRegex(ValueError, 'Java 8'):
            reembed.compatible(old, new)

    def test_constant_pool_rewrite_preserves_unrelated_modified_utf8(self):
        source = self.old_classes['FrenchifyClientPump']
        source = rewrite_utf8(source, [(b'SourceFile', b'SourceFile\xc0\x80')])
        rewritten = reembed.branded_class(source)
        self.assertIn(b'SourceFile\xc0\x80', rewritten)
        self.assertNotIn(b'Frenchify', rewritten)
        self.assertEqual(read_class(rewritten)['name'], 'AdninClientPump')

    def test_cli_build_and_overwrite_guard(self):
        with tempfile.TemporaryDirectory(prefix='adnin-reembed-test-') as directory:
            root = Path(directory)
            classes = root / 'classes'
            classes.mkdir()
            for name, data in self.classes.items():
                (classes / (name + '.class')).write_bytes(data)
            output, report = root / 'Adnin.dll', root / 'report.json'
            command = [sys.executable, str(ROOT / 'scripts/reembed.py'), '--input', str(ARGS.input),
                       '--classes', str(classes), '--index', str(ARGS.index), '--output', str(output), '--report', str(report)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_bytes(), self.output)
            self.assertEqual(json.loads(report.read_text())['outputSha256'], self.report['outputSha256'])
            rejected = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('Output/report exists', rejected.stderr)
            forced = subprocess.run(command + ['--force'], capture_output=True, text=True)
            self.assertEqual(forced.returncode, 0, forced.stderr)


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0], *UNITTEST_ARGS], verbosity=2)
