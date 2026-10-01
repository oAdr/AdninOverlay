"""Pin query-only gray-name guards; existing row/cache reads stay unchanged."""
import struct

QUERY_FUNCTIONS = ('grayNumberRegister', 'grayStatsAllowed', 'grayLegacyLookup', 'grayLegacySuccess', 'graySkinRegister')
PRODUCER_FUNCTIONS = ('grayStatsProduce', 'grayTagsProduce')
FUNCTIONS = QUERY_FUNCTIONS + PRODUCER_FUNCTIONS
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

# Notification producers also run on cache hits, so guarding query admission
# alone is insufficient. These reviewed call sites pass the actual row in R9
# (two distinct model-frame row locations) and include fifth/sixth stack args.
# Pin all 48 setup bytes and eight continuation bytes around each call.
PRODUCERS = {
    'lunar': (
        (0x86d8d, 0x6b850, 'grayStatsProduce', '90030000e8da51fdff40887c2428488d85d006000048894424204c8d8d900300004d8b4608488b5c2458488bd3498bcde8be4afeff80bd91060000000f'),
        (0x87256, 0x6b850, 'grayStatsProduce', '0000488d8d90030000e80c4dfdffc644242801488d85e027000048894424204c8d8d900300004d8b4608488bd3498bcde8f545feff4883bdc827000000'),
        (0x87649, 0x6b850, 'grayStatsProduce', '90030000e81e49fdffc644242800488d85d01e000048894424204c8d8d900300004d8b4608488b5c2458488bd3498bcde80242feffe9b7010000488d15'),
        (0x89a32, 0x6b850, 'grayStatsProduce', 'fdff498bcfe824fe000088442428488d85a01c000048894424204c8d8d100500004d8b4608488b5c2458488bd3498bcde8191efeffeb05488b5c24584c'),
        (0x872b6, 0x69bd0, 'grayTagsProduce', '488d9550310000488d8d90030000e8474dfdff488d855031000048894424204c8d8d900300004d8b4608488bd3498bcde81529feff90488d8d50310000'),
        (0x87841, 0x69bd0, 'grayTagsProduce', '488d95b0290000488d8d90030000e8bc47fdff488d85b029000048894424204c8d8d900300004d8b4608488bd3498bcde88a23feff4c896530c6453800'),
        (0x89ad6, 0x69bd0, 'grayTagsProduce', '488d95502a0000488d8d10050000e82725fdff488d85502a000048894424204c8d8d100500004d8b4608488bd3498bcde8f500feff4c896548c6455000'),
    ),
    'vanilla': (
        (0x88632, 0x6c2a0, 'grayStatsProduce', 'b0030000e8f542fdff40887c2428488d85f006000048894424204c8d8db00300004d8b4608488b5c2458488bd3498bcde8693cfeff80bdb1060000000f'),
        (0x88b2b, 0x6c2a0, 'grayStatsProduce', '0000488d8db0030000e8f73dfdffc644242801488d85202a000048894424204c8d8db00300004d8b4608488bd3498bcde87037feff4883bd082a000000'),
        (0x88f45, 0x6c2a0, 'grayStatsProduce', 'b0030000e8e239fdffc644242800488d853028000048894424204c8d8db00300004d8b4608488b5c2458488bd3498bcde85633feffe9b7010000488d15'),
        (0x8b1bf, 0x6c2a0, 'grayStatsProduce', 'fdff498bcfe80708010088442428488d85f01e000048894424204c8d8d300500004d8b4608488b5c2458488bd3498bcde8dc10feffeb05488b5c24584c'),
        (0x88b8b, 0x6a620, 'grayTagsProduce', '488d9530330000488d8db0030000e8323efdff488d853033000048894424204c8d8db00300004d8b4608488bd3498bcde8901afeff90488d8d30330000'),
        (0x8913d, 0x6a620, 'grayTagsProduce', '488d95502c0000488d8db0030000e88038fdff488d85502c000048894424204c8d8db00300004d8b4608488bd3498bcde8de14feff4c896550c6455800'),
        (0x8b2b6, 0x6a620, 'grayTagsProduce', '488d95401d0000488d8d30050000e80717fdff488d85401d000048894424204c8d8d300500004d8b4608488bd3498bcde865f3fdff4c896538c6454000'),
    ),
}

def reviewed_policy(pe, profile):
    for site, target, _, context in PROFILES[profile]:
        expected = bytes.fromhex(context)
        call = b'\xe8' + struct.pack('<i', target-site-5)
        if expected[7:12] != call or pe.get_data(site-7, len(expected)) != expected:
            raise ValueError('Gray player query ABI changed: ' + profile + ' ' + hex(site))
    for site, target, _, context in PRODUCERS[profile]:
        expected = bytes.fromhex(context)
        call = b'\xe8' + struct.pack('<i', target-site-5)
        if len(expected) != 61 or expected[48:53] != call or pe.get_data(site-48, len(expected)) != expected:
            raise ValueError('Gray player producer ABI changed: ' + profile + ' ' + hex(site))
    return dict(profile=profile, nameColorOffset=0x94, lightGrayRgb=0xaaaaaa,
                cachedIdentityAndStatisticsPreserved=True, originalRowRenderingPreserved=True,
                noJniOrAllocation=True, cachedNotificationProducersGuarded=True,
                producerRowRegister='r9',
                producers=[dict(callRva=s,originalTargetRva=t,callback=n) for s,t,n,_ in PRODUCERS[profile]],
                gates=[dict(callRva=s,originalTargetRva=t,callback=n) for s,t,n,_ in PROFILES[profile]])
