"""Retire only the reviewed legacy client-side AntiCheat dispatch CALL.

Both fixed native images enter their old detector through one 50 ms pump call.
Leaving the old GUI/JNI fields and config loader intact preserves their ABI,
while replacing this call prevents historical settings from re-enabling the
native checks. The detector body, prologue, unwind records, and the pump's
following operations remain unchanged. The caller does not use a return value.

The full normalized native text lineage is checked by each bridge builder
before this narrower instruction, argument, continuation, and unwind guard.
"""

PROFILES = {
    'lunar': {
        'callRva': 0x1a3de,
        'originalTargetRva': 0x44100,
        'originalBodyEndRva': 0x483e8,
        'before': 'e81d9d0200',
        'continuation': '904c8b7c24584c8b6424484c8d2d43291200',
    },
    'vanilla': {
        'callRva': 0x1a5a6,
        'originalTargetRva': 0x44760,
        'originalBodyEndRva': 0x48a27,
        'before': 'e8b5a10200',
        'continuation': '904c8b7c24584c8b6424484c8d2d1b561200',
    },
}
ARGUMENTS = bytes.fromhex('4c8bce4d8bc4498bd5488bcf')
PROLOGUE = bytes.fromhex('488bc448895820555657415441554156')
REPLACEMENT = bytes.fromhex('9090909090')


def reviewed_patch(pe, profile):
    """Return one JSON-serializable patch specification after exact validation."""
    if profile not in PROFILES:
        raise ValueError('Unknown legacy AntiCheat profile: ' + str(profile))
    item = PROFILES[profile]
    site, target = item['callRva'], item['originalTargetRva']
    checks = (
        (site, bytes.fromhex(item['before']), 'dispatch CALL'),
        (site - len(ARGUMENTS), ARGUMENTS, 'argument ABI'),
        (site + 5, bytes.fromhex(item['continuation']), 'pump continuation'),
        (target, PROLOGUE, 'detector prologue'),
    )
    for rva, expected, name in checks:
        if pe.get_data(rva, len(expected)) != expected:
            raise ValueError('Legacy AntiCheat ' + name + ' changed at ' + hex(rva))
    entries = [entry.struct for entry in getattr(pe, 'DIRECTORY_ENTRY_EXCEPTION', ())
               if entry.struct.BeginAddress == target]
    if len(entries) != 1 or entries[0].EndAddress != item['originalBodyEndRva']:
        raise ValueError('Legacy AntiCheat runtime-function boundary changed')
    return dict(profile=profile, disabled=True, callRva=site,
                originalTargetRva=target, originalBodyEndRva=item['originalBodyEndRva'],
                before=item['before'], after=REPLACEMENT.hex(),
                reason='Retire legacy native AntiCheat dispatcher independently of historical configuration',
                guiFieldsAndJniAbiPreserved=True, detectorBodyAndUnwindPreserved=True)
