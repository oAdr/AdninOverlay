"""Pin Lunar's client-thread Prequeue mode handoff; retain native ownership."""
import hashlib
import struct

SITE = 0x1a360
TARGET = 0xce030
PARSER = 0xcdb80
KNOWN = 0x1a7e74
FUNCTION = 'partyMode'

def reviewed_hook(pe, code_rva, metadata):
    blocks = (
        (0x1a2e5, 0x1a375, '30c84d24bf4e08d77331051763beeb3dec752859c7af37bec8db10110a6bea3b'),
        (PARSER, 0xce026, '1a8112e0aca561658f8a1b03e1aca596005367a4ea4823c92f1e8049130ece21'),
        (TARGET, 0xce924, '0006328e43ed1cc4d01bbc362959f3207ae2b7ae4d822e6c10e4c3fe65125bc8'),
    )
    for start, end, digest in blocks:
        if hashlib.sha256(pe.get_data(start, end-start)).hexdigest() != digest:
            raise ValueError('Lunar Party mode handoff source changed: '+hex(start))
    if pe.get_data(SITE,5) != b'\xe8'+struct.pack('<i',TARGET-SITE-5):
        raise ValueError('Lunar Party mode consumer call changed')
    return dict(callRva=SITE, originalTargetRva=TARGET,
        bridgeTargetRva=code_rva+metadata[FUNCTION], callback=FUNCTION,
        owner='AdninGui4', method='pollPartyMode', descriptor='()I',
        parserRva=PARSER, modeKnownRva=KNOWN,
        clientThreadPrequeueOnly=True, noCallbackWhenModeKnown=True,
        originalParserLockAndConsumerPreserved=True, networkPacketsUnchanged=True,
        modes=['eight_one','eight_two','four_three','four_four','two_four'])
