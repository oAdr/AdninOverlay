"""Retire rowbuilder skin hash-learning in favor of the Mellow metadata resolver.

Only the six Skin-enabled CALL sites in the verified Tab builder are bypassed.
The native setting getter/setter, Number/Bot gates, all actor identities and
stats API queues are unchanged. The appended bridge still reads the real setting.
"""
import struct

PROFILES = {
    'lunar': (0xa73a0, {
        0x854ef: 'e8ac1e020084c0742d4c8bc749',
        0x869cb: 'e8d009020084c00f84b0000000',
        0x86a8d: 'e80e09020084c00f84eb000000',
        0x86bb5: 'e8e607020084c074354883bd30',
        0x87199: 'e80202020084c00f84d6010000',
        0x8744a: 'e851ff010084c0742283fb0475',
    }),
    'vanilla': (0xa9a10, {
        0x86d2e: 'e8dd2c020084c0742d4c8bc349',
        0x88249: 'e8c217020084c00f84b0000000',
        0x8830b: 'e80017020084c00f84eb000000',
        0x88433: 'e8d815020084c074354883bd90',
        0x88a6e: 'e89d0f020084c00f84d6010000',
        0x88d1f: 'e8ec0c020084c0742283fb0475',
    }),
}

def reviewed_patches(pe, profile):
    getter, sites = PROFILES[profile]
    if len(sites) != 6:
        raise ValueError('Missing reviewed Skin gate set: ' + profile)
    patches = []
    for site, surrounding in sites.items():
        expected = bytes.fromhex(surrounding)
        call = b'\xe8' + struct.pack('<i', getter-site-5)
        if expected[:5] != call or pe.get_data(site, len(expected)) != expected:
            raise ValueError('Changed original Skin gate: ' + profile + ' ' + hex(site))
        patches.append(dict(siteRva=site, before=call.hex(), after='31c0909090',
            reason='Replace legacy per-row Skin hash scans with bounded Mellow texture-owner resolver'))
    return dict(profile=profile, callback='nativeDenickerProfile',
        descriptor='(Ljava/lang/String;Z)Ljava/lang/String;',
        evidence='current Tab textures metadata; shared Nick hashes excluded',
        originalSkinResolutionRetired=True, nativeSettingPreserved=True,
        numberPriorityPreserved=True, actorIdentityPreserved=True, patches=patches)
