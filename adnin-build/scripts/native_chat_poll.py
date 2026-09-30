"""Pin consumer-equivalent early termination of periodic native chat scans."""
import struct

PROFILES = {
    'lunar': dict(collector=0xc6e0, collectorEnd=0xd53e, consumer=0x16880,
        consumerEnd=0x16fcc, unwind=0x188e6c, site=0xd17e, exit=0xd192, periodicCall=0x16920,
        resetCall=0x1cdff, initialized=0x1a69db, counter=0x1a4640,
        initializedCheck=(0x16946, '803d8e001900000f8580000000'),
        boundaryCheck=(0x16c5f, '8b05dbd9180085c0780839010f8eb4010000')),
    'vanilla': dict(collector=0xc480, collectorEnd=0xd2de, consumer=0x16980,
        consumerEnd=0x170ac, unwind=0x1611fc, site=0xcf1e, exit=0xcf32, periodicCall=0x16a20,
        resetCall=0x1d1af, initialized=0x17e9f7, counter=0x17c690,
        initializedCheck=(0x16a2d, '803dc37f160000757d'),
        boundaryCheck=(0x16d3f, '8b054b59160085c0780839010f8eb4010000')),
}
TAIL = bytes.fromhex('8b442440ffc0894424403b4424440f8c7ef9ffff4c8b6588')
PROLOG = bytes.fromhex('40555356574154415541564157488d6c24984881ec68010000')


def reviewed_patch(pe, profile, code_rva, metadata):
    spec = PROFILES[profile]
    def require(ok, label):
        if not ok:
            raise ValueError('Chat collector ' + label + ' changed: ' + profile)
    require(pe.get_data(spec['collector'], len(PROLOG)) == PROLOG, 'frame')
    require(any(e.struct.BeginAddress == spec['collector'] and
                e.struct.EndAddress == spec['collectorEnd'] and
                e.struct.UnwindData == spec['unwind']
                for e in pe.DIRECTORY_ENTRY_EXCEPTION), 'original unwind')
    require(pe.get_data(spec['site'], len(TAIL)) == TAIL, 'loop cleanup tail')
    for key in ('initializedCheck', 'boundaryCheck'):
        rva, value = spec[key]
        require(pe.get_data(rva, len(bytes.fromhex(value))) == bytes.fromhex(value), key)
    for key in ('periodicCall', 'resetCall'):
        rva = spec[key]
        require(pe.get_data(rva, 5) == b'\xe8' + struct.pack('<i', spec['collector']-rva-5), key)
    return dict(siteRva=spec['site'], width=6, before=TAIL[:6].hex(),
        callback='chatPollTail', bridgeTargetRva=code_rva+metadata['chatPollTail'],
        consumerRva=spec['consumer'], consumerEndRva=spec['consumerEnd'],
        periodicReturnRva=spec['periodicCall']+5, otherCallerUntouched=True,
        initializedRva=spec['initialized'], counterRva=spec['counter'],
        cleanupExitRva=spec['exit'], fullBoundaryRecordPreserved=True,
        crossPollComponentCaching=False, consumerUnchanged=True,
        referenceOwnershipPreserved=True, schedulingUnchanged=True,
        reason='Stop only after the unchanged consumer would ignore every remaining history row')
