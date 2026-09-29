"""Pin the two header-only JNI string constructions in each supported image."""
import struct

SITES = {'lunar': (0x8e207, 0x8e29b), 'vanilla': (0x8fa47, 0x8fadb)}
BEFORE = bytes.fromhex('ff9038050000')
PREFIX = (bytes.fromhex('488b03488bcb'), bytes.fromhex('498bd6488bcb'))
SUFFIX = (bytes.fromhex('488bf04885c07504'), bytes.fromhex('488be84885c0746d'))


def reviewed_patches(pe, profile, code_rva, metadata):
    target = code_rva + metadata['header']
    patches = []
    for index, site in enumerate(SITES[profile]):
        if pe.get_data(site, 6) != BEFORE:
            raise ValueError('Overlay header full JNI CALL bytes changed at ' + hex(site))
        if pe.get_data(site - 6, 6) != PREFIX[index] or pe.get_data(site + 6, 8) != SUFFIX[index]:
            raise ValueError('Overlay header argument/result ABI changed at ' + hex(site))
        replacement = b'\xe8' + struct.pack('<i', target - site - 5) + b'\x90'
        patches.append(dict(siteRva=site, before=BEFORE.hex(), after=replacement.hex(),
                            bridgeTargetRva=target, purpose='measurement' if index == 0 else 'drawing',
                            reason='Localize allowlisted Overlay header before ' + ('measurement' if index == 0 else 'drawing')))
    return dict(owner='AdninGui4', callback='nativeOverlayHeader',
                descriptor='(Ljava/lang/String;Ljava/lang/Object;I)Ljava/lang/String;',
                titleOnly=True, nativeColumnLayoutChanged=False, fitToColumnWidth=True,
                nativeFontRegister='R12', nativeDescriptorRegister='RDI', widthOffset=0x44,
                patches=patches)
