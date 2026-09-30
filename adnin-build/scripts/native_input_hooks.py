"""Pinned input subclass lifecycle hooks; original message handling is unchanged."""
import struct

MAGIC = b'ADNINP01'
FUNCTION_NAMES = ('inputResolve', 'inputMaintain', 'inputDetach', 'inputProc', 'inputInitialize')
STATE_SIZE = 256
STATE_MAGIC = b'ADNIST01'
PROFILES = {
    'lunar': dict(maintain=0xe7d0, maintainBytes='4883ec28ff15160a1200',
                  install=0x11730, installBytes='4883ec28e857f8ffff',
                  hwnd=0x1a6968, original=0x1a63d0, proc=0x1ec10,
                  cleanup=0x15120, cleanupBytes='488b0d411819004885c974174c8b059d',
                  restore=0x1513d, restoreBytes='ff151da51100'),
    'vanilla': dict(maintain=0xe570, maintainBytes='4883ec28ff15f63b1200',
                    install=0x114d0, installBytes='4883ec28e867f8ffff',
                    hwnd=0x17e9f8, original=0x17e450, proc=0x1f020,
                    cleanup=0x1523f, cleanupBytes='488b0db29716004885c974174c8b05fe',
                    restore=0x1525c, restoreBytes='ff1506d41100'),
}

def read_metadata(payload, code_rva, require):
    at = payload.find(MAGIC)
    require(at >= 160 and payload.find(MAGIC, at + 1) < 0 and at + 68 <= len(payload),
            'Missing or ambiguous input lifecycle metadata')
    values = struct.unpack_from('<15I', payload, at + 8)
    result = {}
    for index, name in enumerate(FUNCTION_NAMES):
        begin, end, unwind = values[index * 3:index * 3 + 3]
        result.update({name: begin, name+'Begin': begin, name+'End': end, name+'Unwind': unwind})
    return result

def reviewed_patches(pe, profile, code_rva, meta, state_rva, require):
    p = PROFILES[profile]
    for item in ('maintain', 'install', 'cleanup', 'restore'):
        expected = bytes.fromhex(p[item+'Bytes'])
        require(pe.get_data(p[item], len(expected)) == expected, 'Input '+item+' ABI changed')
    require(pe.get_data(p['proc'], 11) == bytes.fromhex('405355565741564883ec60'),
            'Original WndProc prologue changed')
    patches = []
    for item in ('maintain', 'install'):
        site = p[item]; before = bytes.fromhex(p[item+'Bytes'])
        target = 'inputInitialize' if item=='install' else 'inputMaintain'
        after = b'\xe9' + struct.pack('<i', code_rva + meta[target] - site - 5)
        after += b'\x90' * (len(before) - len(after))
        patches.append(dict(siteRva=site, before=before.hex(), after=after.hex(),
                            reason='Bind input subclass once per verified window lifetime; never reacquire an external chain'))
    return dict(profile=profile, patches=patches, hwndRva=p['hwnd'], predecessorRva=p['original'],
                originalWndProcRva=p['proc'], wrapperRva=code_rva+meta['inputProc'],
                stateRva=state_rva+64, activeCounterRva=state_rva+64,
                privateMessage='registered, HWND identity and token validated',
                destroyMessage=0x82, detachTimeoutMs=50, maintenanceIntervalMs=1000,
                ordinaryMessages='active counter and original handler only; all arguments and results preserved',
                unload='only after window-thread detach acknowledgement and zero active callbacks; otherwise retain DLL',
                originalHandlerModified=False, gameRuntimeTested=False)
