"""Install the existing client-tick HUD independently of Session Stats display.

Only retarget the disabled-setting conditional branch to the existing hook
maintenance block. The enabled branch's stats update is still skipped when
disabled. Original hook idempotence, two-second install throttle, scheduled
client-thread installation, field copying, native registration, and unload
restoration remain intact. No setting or global game-state value is changed.
"""
import hashlib
import struct

PROFILES = {
    'lunar': dict(branchRva=0x1a4b6, originalTargetRva=0x1a5af,
                  maintenanceRva=0x1a4d6, installCallRva=0x1a574, installerRva=0xc8fe0,
                  blockStartRva=0x1a49f, blockEndRva=0x1a5b4,
                  blockSha256='2d5151fa335276ef2e7ce0dc20369710851785243fd0efc6081a896930ae9521'),
    'vanilla': dict(branchRva=0x1a67e, originalTargetRva=0x1a777,
                    maintenanceRva=0x1a69e, installCallRva=0x1a73c, installerRva=0xcbdc0,
                    blockStartRva=0x1a667, blockEndRva=0x1a77c,
                    blockSha256='44d6728354f08f5395b5435606e8ff3d85b8550c5489c5e4443d5863480dcaa0'),
}


def reviewed_patch(pe, profile):
    if profile not in PROFILES:
        raise ValueError('Unknown client tick profile: ' + str(profile))
    p = PROFILES[profile]
    start, end, site = p['blockStartRva'], p['blockEndRva'], p['branchRva']
    if hashlib.sha256(pe.get_data(start, end - start)).hexdigest() != p['blockSha256']:
        raise ValueError('Client tick installation block differs from reviewed native source')
    before = b'\x0f\x84' + struct.pack('<i', p['originalTargetRva'] - site - 6)
    after = b'\x0f\x84' + struct.pack('<i', p['maintenanceRva'] - site - 6)
    if pe.get_data(site, 6) != before:
        raise ValueError('Client tick conditional branch changed')
    if pe.get_data(p['maintenanceRva'], 5) != bytes.fromhex('488b5c2468'):
        raise ValueError('Client tick maintenance no longer restores the timestamp register')
    call = b'\xe8' + struct.pack('<i', p['installerRva'] - p['installCallRva'] - 5)
    if pe.get_data(p['installCallRva'], 5) != call:
        raise ValueError('Client tick native installer target changed')
    return dict(profile=profile, **p, before=before.hex(), after=after.hex(),
                independentOfSessionStats=True, sessionStatsDisplaySettingPreserved=True,
                originalInstallerAndUnloadPreserved=True,
                reason='Maintain fixed-phase game tick hook even when Session Stats is disabled')
