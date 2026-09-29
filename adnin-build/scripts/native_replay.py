"""Reviewed overlay-only Replay predicates; never modifies real game state."""
import struct

PROFILES = {
    'lunar': {
        'eligibilityRva': 0x1a63bd,
        'sites': (
            (0x19f49, '38056ec41800', 'replayRdi', 'enabled',
             0x19f39, '84c00f8581000000380509a118007421', '7419b301eb17'),
            (0x1a5e8, '803dcebd180000', 'replayRdi', 'modelCache',
             0x1a5dd, '498bd7488bcfe828140300', '742b488bc3482b059dc41800483d2c0100007219'),
            (0x201be, '803df861180000', 'replayRbx', 'render',
             0x201ad, '488bcbe86b83ffff803d943e180000741b', '74124c8bc6488d0d6f621800488bd3e897bc0600'),
            (0x1d308, '803dae90180000', 'replayFrame', 'fallbackRender',
             0x1d2f6, '488b01ff9088000000803d4a6d180000741c', '7413488b5528488d0d249118004c8bc7e84ceb0600'),
        ),
    },
    'vanilla': {
        'eligibilityRva': 0x17e95d,
        'sites': (
            (0x1a111, '380546481600', 'replayRdi', 'enabled',
             0x1a101, '84c00f85810000003805c92416007421', '7419b301eb17'),
            (0x1a7b0, '803da641160000', 'replayRdi', 'modelCache',
             0x1a7a5, '498bd7488bcfe890180300', '742b488bc3482b0525431600483d2c0100007219'),
            (0x205af, '803da7e3150000', 'replayRbx', 'render',
             0x2059e, '488bcbe85a80ffff803d2bc0150000741b', '74124c8bc6488d0dfede1500488bd3e8e6d00600'),
            (0x1d6b9, '803d9d12160000', 'replayFrame', 'fallbackRender',
             0x1d6a7, '488b01ff9088000000803d21ef150000741c', '7413488b5528488d0df30d16004c8bc7e8dbff0600'),
        ),
    },
}


def reviewed_patches(pe, profile, code_rva, metadata):
    if profile not in PROFILES:
        raise ValueError('Unknown Replay overlay profile: ' + str(profile))
    spec = PROFILES[profile]
    result = []
    for rva, before, callback, purpose, context_rva, context, continuation in spec['sites']:
        original = bytes.fromhex(before)
        for at, expected, label in ((rva, original, 'predicate'),
                (context_rva, bytes.fromhex(context), 'caller context'),
                (rva + len(original), bytes.fromhex(continuation), 'continuation')):
            if pe.get_data(at, len(expected)) != expected:
                raise ValueError('Replay overlay ' + label + ' changed at ' + hex(at))
        target = code_rva + metadata[callback]
        after = b'\xe8' + struct.pack('<i', target - rva - 5) + b'\x90' * (len(original) - 5)
        result.append(dict(profile=profile, predicateRva=rva, bridgeTargetRva=target,
            before=before, after=after.hex(), callback=callback, purpose=purpose,
            nativeEligibilityRva=spec['eligibilityRva'], nativeGameStateUnchanged=True,
            reason='Allow observed Replay sidebar only at overlay ' + purpose + ' predicate'))
    return result
