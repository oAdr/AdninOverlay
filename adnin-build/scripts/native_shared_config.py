"""Pin the sole legacy native save caller in both supported profiles."""
import struct

PROFILES = {
    'lunar': (0x19ffd, 0x399a0),
    'vanilla': (0x1a1c5, 0x3b680),
}


def reviewed_hook(pe, profile, code_rva, metadata):
    site, target = PROFILES[profile]
    expected = bytes.fromhex('488bd6488bcf') + b'\xe8' + struct.pack('<i', target-site-5) + bytes.fromhex('4885f67433')
    if pe.get_data(site-6, len(expected)) != expected:
        raise ValueError('Legacy config-save caller or JNI argument ABI changed: '+profile)
    return dict(callRva=site, originalTargetRva=target, bridgeTargetRva=code_rva+metadata['configSave'],
                callback='nativeRequestConfigSave', descriptor='()I', originalWriterRetired=True,
                synchronousDiskWrites=False, sharedFile='config.properties', unchangedSnapshotsSkipped=True)
