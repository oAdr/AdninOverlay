"""Both native profiles retire only reviewed Skin rowbuilder gates, fail closed."""
import pefile
import native_skin_policy

def verify(test, before, final, report, profile):
    old, new = pefile.PE(data=before), pefile.PE(data=final)
    policy = native_skin_policy.reviewed_patches(old, profile)
    test.assertEqual(report['skinDenickerPolicy'], policy)
    getter, sites = native_skin_policy.PROFILES[profile]
    test.assertEqual(len(sites), 6)
    test.assertEqual(new.get_data(getter, 80), old.get_data(getter, 80), 'Setting getter unchanged')
    for site, surrounding in sites.items():
        expected = bytes.fromhex(surrounding)
        test.assertEqual(new.get_data(site, len(expected)), bytes.fromhex('31c0909090')+expected[5:])
        for delta in (0, 4, 5, len(expected)-1):
            changed = bytearray(before)
            changed[old.get_offset_from_rva(site+delta)] ^= 1
            with test.assertRaisesRegex(ValueError, 'Skin gate'):
                native_skin_policy.reviewed_patches(pefile.PE(data=bytes(changed)), profile)
    section = new.get_data(report['section']['rva'], report['section']['size'])
    test.assertIn(b'nativeDenickerProfile\0', section)
    test.assertIn(b'(Ljava/lang/String;Z)Ljava/lang/String;\0', section)
    test.assertIn(b'nativeDenickerPublished\0', section)
    test.assertIn(b'(Ljava/lang/String;Ljava/lang/String;)V\0', section)
