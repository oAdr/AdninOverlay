"""Pin query-only gray-name guards; existing row/cache reads stay unchanged."""
import struct

FUNCTIONS = ('grayNumberRegister', 'grayStatsAllowed', 'grayLegacyLookup', 'grayLegacySuccess', 'graySkinRegister')
PROFILES = {
    'lunar': (
        (0x86bb0, 0xbb4f0, 'grayNumberRegister', '488d8dd0030000e83b490300e8e6070200'),
        (0x8747c, 0x99600, 'grayStatsAllowed', '488d8dd0030000e87f2101000fb6d884c0'),
        (0x87f54, 0xaa8b0, 'grayLegacyLookup', '488d8dd0030000e85729020090488d8d50'),
        (0x87bfc, 0xaa620, 'grayLegacySuccess', '488d8dd0030000e81f2a020084c00f8430'),
        (0x86bee, 0xa7750, 'graySkinRegister', '488d8dd0030000e85d0b02004032ff66c78590'),
    ),
    'vanilla': (
        (0x8842e, 0xbe090, 'grayNumberRegister', '488d8df0030000e85d5c0300e8d8150200'),
        (0x88d51, 0x9b770, 'grayStatsAllowed', '488d8df0030000e81a2a01000fb6d884c0'),
        (0x89850, 0xace90, 'grayLegacyLookup', '488d8df0030000e83b36020090488d8d10'),
        (0x894f8, 0xacc00, 'grayLegacySuccess', '488d8df0030000e80337020084c00f8430'),
        (0x8846c, 0xa9dc0, 'graySkinRegister', '488d8df0030000e84f1902004032ff66c785b0'),
    ),
}

def reviewed_policy(pe, profile):
    for site, target, _, context in PROFILES[profile]:
        expected = bytes.fromhex(context)
        call = b'\xe8' + struct.pack('<i', target-site-5)
        if expected[7:12] != call or pe.get_data(site-7, len(expected)) != expected:
            raise ValueError('Gray player query ABI changed: ' + profile + ' ' + hex(site))
    return dict(profile=profile, nameColorOffset=0x94, lightGrayRgb=0xaaaaaa,
                cachedIdentityAndStatisticsPreserved=True, originalRowRenderingPreserved=True,
                noJniOrAllocation=True,
                gates=[dict(callRva=s,originalTargetRva=t,callback=n) for s,t,n,_ in PROFILES[profile]])
