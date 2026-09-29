"""Review narrow client-tick gate patches against both original and built PEs.

No original native function or injection payload is executed by these tests.
"""
import argparse
from pathlib import Path
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import pefile
import native_tick_hook

parser = argparse.ArgumentParser(add_help=False)
parser.add_argument('--build', type=Path, required=True)
ARGS, REST = parser.parse_known_args()


class ClientTickGateTests(unittest.TestCase):
    def test_both_disabled_branches_enter_only_existing_install_maintenance(self):
        for profile, fixed, final in (
                ('lunar', 'native-fixed.dll', 'Adnin.dll'),
                ('vanilla', 'native-vanilla.dll', 'AdninVanilla.dll')):
            with self.subTest(profile=profile):
                original = pefile.PE(str(ARGS.build / fixed))
                built = pefile.PE(str(ARGS.build / 'bin' / final))
                patch = native_tick_hook.reviewed_patch(original, profile)
                rva = patch['branchRva']
                actual = built.get_data(rva, 6)
                self.assertEqual(actual, bytes.fromhex(patch['after']))
                self.assertEqual(actual[:2], b'\x0f\x84')
                self.assertEqual(rva + 6 + struct.unpack('<i', actual[2:])[0], patch['maintenanceRva'])
                self.assertGreater(patch['maintenanceRva'], rva + 6)
                self.assertLess(patch['maintenanceRva'], patch['installCallRva'])
                # Setting-on code and all scheduling/throttle/restore bytes stay identical.
                start, end = patch['blockStartRva'], patch['blockEndRva']
                expected = bytearray(original.get_data(start, end - start))
                expected[rva - start:rva - start + 6] = actual
                self.assertEqual(built.get_data(start, end - start), expected)
                self.assertTrue(patch['sessionStatsDisplaySettingPreserved'])
                self.assertTrue(patch['originalInstallerAndUnloadPreserved'])

    def test_every_changed_byte_in_reviewed_block_is_rejected(self):
        for profile, fixed in (('lunar', 'native-fixed.dll'), ('vanilla', 'native-vanilla.dll')):
            data = (ARGS.build / fixed).read_bytes()
            original = pefile.PE(data=data)
            p = native_tick_hook.PROFILES[profile]
            for rva in range(p['blockStartRva'], p['blockEndRva']):
                changed = bytearray(data)
                changed[original.get_offset_from_rva(rva)] ^= 1
                with self.subTest(profile=profile, rva=hex(rva)), self.assertRaisesRegex(ValueError, 'reviewed native source'):
                    native_tick_hook.reviewed_patch(pefile.PE(data=bytes(changed), fast_load=True), profile)

    def test_unknown_profile_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown client tick profile'):
            native_tick_hook.reviewed_patch(None, 'unknown')


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0]] + REST)
