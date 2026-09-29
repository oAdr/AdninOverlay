"""One reviewed Replay-only placeholder CALL; no actor UUID/history mutation."""
import hashlib
import struct

PROFILES = {
    'lunar': dict(call=0x87433, placeholder=0x76340, block=(0x873f8, 0x8744a),
        blockSha256='e1baf09e1d9cee700c487c4ed94c1054b6ef68b33a738f203fd46a7184cbf797',
        queue=0x9d570, getter=0x98e50, formatter=0x5bf40, destructor=0x6d40,
        mutex=0x1a4b70, lockIat=0x12f4d0, unlockIat=0x12f4d8,
        mode=0x1a6f88, replayFlag=0x1a63b8, gui=0x1a63a8,
        ready=(8, 0x60, 0xa0), strings=(0x70, 0xc0, 0xe0, 0x100), statsSize=0x120),
    'vanilla': dict(call=0x88d08, placeholder=0x77040, block=(0x88ccd, 0x88d1f),
        blockSha256='1766e7318c13a731209018941c2f48012bea4d1ee57054ff611795d1df20843f',
        queue=0x9fa40, getter=0x9afc0, formatter=0x5c900, destructor=0x6af0,
        mutex=0x17cb50, lockIat=0x132468, unlockIat=0x132470,
        mode=0x17ef48, replayFlag=0x17e438, gui=0x17e428,
        ready=(8, 0xc0, 0x100), strings=(0x10, 0x30, 0x50, 0xd0, 0x120, 0x140, 0x160), statsSize=0x180),
}


def reviewed_hook(pe, profile, code_rva, metadata):
    spec = PROFILES[profile]
    start, end = spec['block']
    block = pe.get_data(start, end - start)
    if hashlib.sha256(block).hexdigest() != spec['blockSha256']:
        raise ValueError('Replay stats dedicated placeholder block changed: ' + profile)
    expected = b'\xe8' + struct.pack('<i', spec['placeholder'] - spec['call'] - 5)
    if pe.get_data(spec['call'], 5) != expected:
        raise ValueError('Replay stats placeholder CALL changed: ' + profile)
    return dict(profile=profile, callRva=spec['call'], originalTargetRva=spec['placeholder'],
        bridgeTargetRva=code_rva + metadata['replayStats'], guardedBlockRva=start,
        guardedBlockSize=end-start, guardedBlockSha256=spec['blockSha256'],
        callbackOwner='AdninGui4', callback='nativeReplayProfile',
        descriptor='(Ljava/lang/String;)Ljava/lang/String;',
        validatedProfileFormat='accountName|onlineUUIDv4', rawNameOffset=0x40,
        confirmedNickSentinel='NICK', confirmedNickLabel='\u00a7c[NICK]', confirmedNickColor=0xff5555,
        unknownOrPendingAction='retain original placeholder',
        actorUuidOffset=0x148, rowSize=0x178, statsSize=spec['statsSize'],
        queueRva=spec['queue'], getterRva=spec['getter'], formatterRva=spec['formatter'],
        mutexRva=spec['mutex'], originalPlaceholderPreserved=True,
        actorIdentityPreserved=True, historicalNamesUntouched=True,
        nativeGameStateUnchanged=True, chatAndOutputUntouched=True,
        nativeQueueDeduplicationAndThrottlePreserved=True)
