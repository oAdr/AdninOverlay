"""Reviewed gate into the existing Nick branch; Replay actor identity is retained."""
import struct

PROFILES = {
    'lunar': dict(gate=0x8690c, before='4584e40f85e30a000083fb010f85da0a0000',
                  skip=0x873f8, continuation='488d9590030000',
                  copy=0x871f5, copyTarget=0x72c0, nameFrame=0x3d0),
    'vanilla': dict(gate=0x8818a, before='4584e40f853a0b000083fb010f85310b0000',
                    skip=0x88ccd, continuation='488d95b0030000',
                    copy=0x88aca, copyTarget=0x70c0, nameFrame=0x3f0),
}


def reviewed_patch(pe, profile, code_rva, metadata):
    spec = PROFILES[profile]
    site, expected = spec['gate'], bytes.fromhex(spec['before'])
    def require(ok, detail):
        if not ok:
            raise ValueError('Replay Denicker ' + detail + ' changed: ' + profile)
    require(pe.get_data(site, 18) == expected, 'eligibility gate')
    require(pe.get_data(site-4, 4) == bytes.fromhex('8b5c2460'), 'UUID-version source')
    following = bytes.fromhex(spec['continuation'])
    require(pe.get_data(site+18, len(following)) == following, 'Nick-path continuation')
    original_copy = b'\xe8' + struct.pack('<i', spec['copyTarget']-spec['copy']-5)
    require(pe.get_data(spec['copy'], 5) == original_copy, 'Skin actor UUID copy')
    target = code_rva + metadata['replayDenickGate']
    after = b'\xe8' + struct.pack('<i', target-site-5)
    after += b'\x0f\x84' + struct.pack('<i', spec['skip']-site-11) + b'\x90'*7
    return dict(profile=profile, gateRva=site, before=expected.hex(), after=after.hex(),
                bridgeTargetRva=target, skipRva=spec['skip'], fallthroughRva=site+18,
                uuidCopyCallRva=spec['copy'], uuidCopyTargetRva=code_rva+metadata['replayUuidCopy'],
                callbackOwner='AdninGui4', callback='nativeReplayIsNick', descriptor='(Ljava/lang/String;)I',
                requiredAcknowledgement=1, normalizedNameFrameOffset=spec['nameFrame'],
                ordinaryUuidV1GatePreserved=True, originalDenickerResolutionAlgorithmsPreserved=True,
                actorIdentityPreserved=True, historicalNameCacheGatesUnchanged=True,
                reason='Admit cached verified Replay Nicks to existing Denicker branch without changing actor identity')
