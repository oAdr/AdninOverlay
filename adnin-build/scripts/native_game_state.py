"""Pin the post-parser strict Ingame notification for both original profiles."""
PROFILES = {
    'lunar': dict(site=0x185ff, target=0x12a20, state=0x1a4098,
        call='e81ca4ffff', suffix='4c8bce4d8bc4498bd5488bcfe86be2ffff',
        entry='4885c90f8410220000488bc4488958205556574154415541',
        compare=0x13ed3, compareBytes='488d0dbe01190048833dce0119000f480f470dae0119004c8b05b70119004c3bc67510',
        assign=0x14030, assignBytes='488d0d61001900e854f7feff', literal=0x13e078),
    'vanilla': dict(site=0x186df, target=0x12b90, state=0x17c090,
        call='e8aca4ffff', suffix='4c8bce4d8bc4498bd5488bcfe88be2ffff',
        entry='4885c90f84ea210000488bc4488958205556574154415541',
        compare=0x14043, compareBytes='488d0d4680160048833d568016000f480f470d368016004c8b053f8016004c3bc67510',
        assign=0x141a0, assignBytes='488d0de97e1600e8c4f4feff', literal=0x140b38),
}
PREFIX = bytes.fromhex('4d8bc4498bd5488bcf')


def checks(profile):
    p = PROFILES[profile]
    return ((p['site']-len(PREFIX),PREFIX), (p['site'],bytes.fromhex(p['call'])),
            (p['site']+5,bytes.fromhex(p['suffix'])), (p['target'],bytes.fromhex(p['entry'])),
            (p['compare'],bytes.fromhex(p['compareBytes'])),
            (p['assign'],bytes.fromhex(p['assignBytes'])), (p['literal'],b'Ingame\0'))


def reviewed_hook(pe, profile, code_rva, metadata):
    p = PROFILES[profile]
    for at, expected in checks(profile):
        if pe.get_data(at,len(expected)) != expected:
            raise ValueError('Native game-state parser ABI or strict Ingame source changed at '+hex(at))
    return dict(callRva=p['site'], originalTargetRva=p['target'], bridgeTargetRva=code_rva+metadata['gameActive'],
        callback='setGameActive', owner='AdninGui4', descriptor='(Z)V', nativeStateStringRva=p['state'],
        predicate='exact six-byte Ingame after original parser; false for null Minecraft/class',
        notification='each original parser call, including already active state and exits',
        parserIntervalMs=500, javaWorldPresenceGuardRequired=True,
        existingExceptionPreserved=True, optionalCallbackFailureRetried=True)
