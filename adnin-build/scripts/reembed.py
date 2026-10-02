"""Build the Adnin PE from the reviewed fixed DLL and ten renamed Java classes.

This patcher never loads its input. All old RVAs stay fixed. Nine verified
DefineClass sites point to Java-8 classes in a new read-only .adnin section.
The dormant GuiNewChat class is renamed without activating its unknown loader.
Helper classes are bootstrapped by the compiled Gui4/ClientPump, so they must
not be supplied separately here. The resulting DLL still contains the recovered
native implementation; this is not a complete native C++ rewrite.
"""
import argparse
import hashlib
import json
import re
import struct
from pathlib import Path

import pefile
from classfile import read_class, rewrite_utf8

FIXED_SHA256 = 'c1ad1b6978c381c21b8a519ef852ac71f3c70195712eda83ef8eb4d973702cca'
SITES = {
    'FrenchifyTabOverlayNative': (0x11a1a, 0x11a24),
    'FrenchifyGui4': (0x14e58, 0x14e50),
    'FrenchifyRenderGlobal': (0x49b0a, 0x49b23),
    'FrenchifyHitboxInstaller': (0x49c04, 0x49c0e),
    'FrenchifyIngameGui': (0xc2c32, 0xc2c3c),
    'FrenchifySessionHud': (0xc2e19, 0xc2e23),
    'FrenchifySessionHudInstaller': (0xc2d24, 0xc2d2e),
    'FrenchifyPacketLog': (0xcf103, 0xcf10d),
    'FrenchifyClientPump': (0xd5912, 0xd5935),
}
DORMANT = 'FrenchifyGuiNewChat'
REPLACEMENTS = ((b'Frenchify', b'Adnin'), (b'frenchify', b'adnin'), (b'FRENCHIFY', b'ADNIN'))
COLORED_OLD = '\u00a71Fre\u00a7fnch\u00a7cify'.encode('utf8')
# Preserve fixed-length native copies and suffix pointers. Padding belongs
# after the closing bracket, so the visible brand is exactly [Adnin].
COLORED_PREFIX_OLD = '\u00a77['.encode('utf8') + COLORED_OLD + '\u00a77]'.encode('utf8')
COLORED_PREFIX_NEW = '\u00a77[\u00a71Adnin\u00a77]\u00a7r\u00a7r\u00a7r '.encode('utf8')
# Verify and replace the entire NUL-terminated .rdata strings atomically. The
# prefix keeps its existing equal-width branding rewrite; only the prose's gray
# tail changes to red. No native instruction or player-name color is changed.
NATIVE_ERROR_STRINGS = {
    0x13e2d0: (
        COLORED_PREFIX_OLD + ' \u00a7cInvalid Hypixel API key\u00a77. Check your key in settings.\0'.encode('utf8'),
        COLORED_PREFIX_NEW + ' \u00a7cInvalid Hypixel API key\u00a7c. Check your key in settings.\0'.encode('utf8'),
    ),
    0x13e330: (
        COLORED_PREFIX_OLD + ' \u00a7cInvalid Seraph API key\u00a77. Check your key in settings.\0'.encode('utf8'),
        COLORED_PREFIX_NEW + ' \u00a7cInvalid Seraph API key\u00a7c. Check your key in settings.\0'.encode('utf8'),
    ),
}
PRESERVED_RVA = 0x1724d8
PRESERVED_BYTES = b'All generators are maxed! Your bed has three\0'
LENGTH_PATCHES = {
    0x113d8: ('4883f809', '4883f805'),
    0x113e2: ('41b909000000', '41b905000000'),
    0x11422: ('4883f909', '4883f905'),
    0x1142c: ('41b909000000', '41b905000000'),
    0x122ad: ('4883f809', '4883f805'),
    0x122b7: ('41b909000000', '41b905000000'),
    0x122f0: ('4883f809', '4883f805'),
    0x122fa: ('41b909000000', '41b905000000'),
}
# Two native builders inline the three strings instead of reading .rdata.
# Keep their conservative four-byte capacity guards and four-byte stores, but
# adjust logical append lengths for "fin" (3) and "r" (1). The stores contain
# the required NUL terminators, and still fit the original capacity checks.
SPLIT_BRAND_PATCHES = {
    0x40b07: ('c7040831467265', 'c704083141646e'),
    0x40bd1: ('488d4104', '488d4103'),
    0x40be6: ('c70408666e6368', 'c7040866696e00'),
    0x40bf8: ('48c744242004000000', '48c744242003000000'),
    0x40c0b: ('ba04000000', 'ba03000000'),
    0x40cb0: ('488d4104', '488d4101'),
    0x40cc5: ('c7040863696679', 'c7040872000000'),
    0x40cd7: ('48c744242004000000', '48c744242001000000'),
    0x40cea: ('ba04000000', 'ba01000000'),
    0x682e9: ('c7040831467265', 'c704083141646e'),
    0x683af: ('488d4104', '488d4103'),
    0x683c4: ('c70408666e6368', 'c7040866696e00'),
    0x683d6: ('48c744242004000000', '48c744242003000000'),
    0x683e9: ('ba04000000', 'ba03000000'),
    0x68484: ('488d4104', '488d4101'),
    0x68499: ('c7040863696679', 'c7040872000000'),
    0x684ab: ('48c744242004000000', '48c744242001000000'),
    0x684be: ('ba04000000', 'ba01000000'),
}
SPLIT_BRAND_STRINGS = {
    0x16c440: (b'1Fre\0', b'1Adn\0'),
    0x16c438: (b'fnch\0', b'fin\0\0'),
    0x16c430: (b'cify\0', b'r\0\0\0\0'),
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def align(value, boundary):
    require(boundary > 0 and not boundary & (boundary - 1), 'Alignment must be a power of two')
    return (value + boundary - 1) & ~(boundary - 1)


def renamed(value):
    if value is None:
        return None
    return value.replace('Frenchify', 'Adnin').replace('frenchify', 'adnin').replace('FRENCHIFY', 'ADNIN')


def branded_class(data):
    return rewrite_utf8(data, REPLACEMENTS)


# The packet observer moved from install() into its EventLoop-owned factory.
# javac therefore changes this one private synthetic implementation method and
# captures the owner request. No declared API/native method is exempt, and no
# other private or synthetic member is allowed to disappear.
PACKET_LOG_LAMBDA_BEFORE = (
    'lambda$install$0',
    '([Ljava/lang/reflect/Method;[Ljava/lang/reflect/Method;[Ljava/lang/reflect/Method;'
    'Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;')
PACKET_LOG_LAMBDA_AFTER = (
    'lambda$createHandler$0',
    '(LAdninPacketLog$InstallRequest;[Ljava/lang/reflect/Method;[Ljava/lang/reflect/Method;'
    '[Ljava/lang/reflect/Method;Ljava/lang/Object;Ljava/lang/reflect/Method;'
    '[Ljava/lang/Object;)Ljava/lang/Object;')
PACKET_LOG_LAMBDA_ACCESS = 0x100a  # ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC; not native


def compatible(old, new, gui_widenings=(('mouseClicked', '(III)V'), ('keyTyped', '(CI)V')), additional_interfaces=()):
    """Preserve original ABI, with one pinned compiler-lambda relocation."""
    require(new['major'] == 52 and new['minor'] == 0, 'Replacement must target Java 8')
    for key in ('name', 'parent'):
        require(renamed(old[key]) == new[key], f'Class ABI changed: {old["name"]} {key}')
    require(old['access'] == new['access'], f'Class access changed: {old["name"]}')
    require([renamed(v) for v in old['interfaces']] + list(additional_interfaces) == new['interfaces'],
            f'Class interfaces changed: {old["name"]}')
    for kind in ('fields', 'methods'):
        before = {(renamed(m['name']), renamed(m['descriptor'])): m['access'] for m in old[kind]}
        after = {(m['name'], m['descriptor']): m['access'] for m in new[kind]}
        require(len(after) == len(new[kind]), f'Duplicate members: {new["name"]} {kind}')
        relocated = set()
        if (old['name'] == 'FrenchifyPacketLog' and kind == 'methods'
                and before.get(PACKET_LOG_LAMBDA_BEFORE) == PACKET_LOG_LAMBDA_ACCESS
                and PACKET_LOG_LAMBDA_BEFORE not in after
                and after.get(PACKET_LOG_LAMBDA_AFTER) == PACKET_LOG_LAMBDA_ACCESS):
            relocated.add(PACKET_LOG_LAMBDA_BEFORE)
        require(before.keys() - relocated <= after.keys(),
                f'Original members removed or descriptors changed: {old["name"]} {kind}')
        for member, access in before.items():
            if member in relocated:
                continue
            changed = after[member]
            # The installed Lunar GuiScreen has widened these overrides. All
            # other original access/static/native flags remain authoritative.
            widened = (old['name'] == 'FrenchifyGui4' and kind == 'methods'
                       and member in gui_widenings
                       and access == 4 and changed == 1)
            require(access == changed or widened,
                    f'Member access changed: {new["name"]}.{member}')
    require(not any('Frenchify' in str(value) or 'frenchify' in str(value)
                    for value in new.values()), f'Old class branding remains in {new["name"]}')


def lunar_profile():
    """Hash-pinned native metadata; other runtimes must supply their own values."""
    return dict(fixed_sha256=FIXED_SHA256, sites=SITES, dormant=DORMANT,
                preserved_rva=PRESERVED_RVA, preserved_bytes=PRESERVED_BYTES,
                native_error_strings=NATIVE_ERROR_STRINGS, colored_prefix_count=9,
                fragment_rva=0x13d41c, length_patches=LENGTH_PATCHES,
                split_brand_patches=SPLIT_BRAND_PATCHES,
                split_brand_strings=SPLIT_BRAND_STRINGS,
                builder_rvas=[0x408b0, 0x68100],
                allowed_gui_widenings=(('mouseClicked', '(III)V'), ('keyTyped', '(CI)V')),
                initial_patches={})


def rebuild(original, classes, records, profile=None):
    """Return (DLL bytes, manifest); classes is {AdninClassName: class_bytes}."""
    profile = lunar_profile() if profile is None else profile
    required = set(lunar_profile()) - {'initial_patches'}
    require(required <= set(profile), 'Native runtime profile is incomplete')
    sites, dormant = profile['sites'], profile['dormant']
    preserved_rva, preserved_bytes = profile['preserved_rva'], profile['preserved_bytes']
    native_error_strings = profile['native_error_strings']
    require(sha(original) == profile['fixed_sha256'], 'Input hash differs from the reviewed fixed DLL')
    pe = pefile.PE(data=original)
    require(pe.FILE_HEADER.Machine == 0x8664 and pe.OPTIONAL_HEADER.Magic == 0x20b,
            'Expected AMD64 PE32+')
    require(not pe.OPTIONAL_HEADER.DATA_DIRECTORY[4].VirtualAddress,
            'Signed images require separate signature handling')
    require(pe.get_overlay_data_start_offset() is None, 'Unexpected PE overlay')
    names = {r['name'] for r in records}
    expected_original = set(sites) | ({dormant} if dormant else set())
    require(len(records) == len(expected_original) and names == expected_original, 'Unexpected original class index')
    expected = {renamed(name) for name in names}
    require(set(classes) <= expected, 'Unexpected Java class; helper bytes belong in the bootstrap')
    require(expected - set(classes) <= ({renamed(dormant)} if dormant else set()), 'Missing active Java class')
    classes = dict(classes)

    anchor_offset = pe.get_offset_from_rva(preserved_rva)
    require(original[anchor_offset:anchor_offset + len(preserved_bytes)] == preserved_bytes,
            'The adjacent SessionHudInstaller native-string anchor changed')
    section_header = pe.sections[-1].get_file_offset() + 40
    require(section_header + 40 <= min(s.PointerToRawData for s in pe.sections if s.SizeOfRawData),
            'No room for another PE section header')
    require(pe.OPTIONAL_HEADER.SizeOfHeaders >= section_header + 40,
            'New section header exceeds SizeOfHeaders')
    require(not any(original[section_header:section_header + 40]), 'Section-header space is occupied')
    new_rva = align(max(s.VirtualAddress + max(s.Misc_VirtualSize, s.SizeOfRawData)
                        for s in pe.sections), pe.OPTIONAL_HEADER.SectionAlignment)
    new_raw = align(len(original), pe.OPTIONAL_HEADER.FileAlignment)
    output = bytearray(original)
    patches, intervals, manifest, payload = [], [], [], bytearray()

    def patch(offset, expected_bytes, replacement, reason):
        require(len(expected_bytes) == len(replacement), f'Patch changes existing span length: {reason}')
        require(offset >= 0 and offset + len(expected_bytes) <= len(original), f'Patch outside input: {reason}')
        require(output[offset:offset + len(expected_bytes)] == expected_bytes,
                f'Unexpected bytes for {reason} at 0x{offset:x}')
        require(not any(offset < end and start < offset + len(expected_bytes) for start, end in intervals),
                f'Overlapping mutation: {reason}')
        output[offset:offset + len(replacement)] = replacement
        intervals.append((offset, offset + len(replacement)))
        patches.append({'offset': offset, 'rva': pe.get_rva_from_offset(offset),
                        'before': expected_bytes.hex(), 'after': replacement.hex(), 'reason': reason})

    for rva, (before, after) in profile.get('initial_patches', {}).items():
        patch(pe.get_offset_from_rva(rva), before, after, 'verified runtime-specific initialization correction')

    old_class_ranges = []
    for record in records:
        old_name, name = record['name'], renamed(record['name'])
        old_offset, old_size = int(record['offset'], 16), record['size']
        old = original[old_offset:old_offset + old_size]
        require(sha(old) == record['sha256'], f'Original class slice changed: {old_name}')
        original_branded = branded_class(old)
        classes.setdefault(name, original_branded)
        data = classes[name]
        abi_old = profile['abi_transform'](old) if profile.get('abi_transform') else old
        compatible(read_class(abi_old), read_class(data), profile['allowed_gui_widenings'],
                   profile.get('additional_interfaces', {}).get(old_name, ()))
        require(not re.search(b'frenchify', data, re.I), f'Old branding remains in class: {name}')
        if old_name == dormant:
            require(data == original_branded,
                    'Dormant AdninGuiNewChat may only receive a constant-pool branding rewrite')
        # Keep a valid renamed class in every former class slot. Each rewrite
        # shrinks only UTF8 constants; it never crosses the original boundary.
        require(len(original_branded) <= old_size, f'Branded original does not fit: {name}')
        patch(old_offset, old, original_branded + bytes(old_size - len(original_branded)),
              f'rename inactive original class slot: {name}')
        old_class_ranges.append((old_offset, old_offset + old_size))
        payload.extend(bytes(align(len(payload), 16) - len(payload)))
        delta = len(payload)
        payload.extend(data)
        item = {'name': name, 'originalName': old_name,
                'mode': 'relocated' if old_name in sites else 'relocated-dormant',
                'originalOffset': old_offset, 'originalSize': old_size,
                'offset': new_raw + delta, 'rva': new_rva + delta,
                'size': len(data), 'sha256': sha(data)}
        if old_name in sites:
            lea_rva, length_rva = sites[old_name]
            lea, length = pe.get_offset_from_rva(lea_rva), pe.get_offset_from_rva(length_rva)
            require(original[lea:lea + 3] == bytes.fromhex('4c8d0d'), f'Unexpected LEA: {name}')
            require(lea_rva + 7 + struct.unpack_from('<i', original, lea + 3)[0]
                    == pe.get_rva_from_offset(old_offset), f'LEA target differs: {name}')
            require(original[length:length + 4] == bytes.fromhex('c7442420'), f'Unexpected size instruction: {name}')
            require(struct.unpack_from('<I', original, length + 4)[0] == old_size, f'Original length differs: {name}')
            displacement = item['rva'] - (lea_rva + 7)
            require(-(1 << 31) <= displacement < (1 << 31), 'Class outside RIP-relative addressing range')
            patch(lea + 3, original[lea + 3:lea + 7], struct.pack('<i', displacement), f'{name} DefineClass pointer')
            patch(length + 4, original[length + 4:length + 8], struct.pack('<I', len(data)), f'{name} DefineClass size')
            item.update(leaRva=lea_rva, lengthRva=length_rva)
        manifest.append(item)

    def is_class_byte(offset):
        return any(start <= offset < end for start, end in old_class_ranges)

    # Brand C strings and JNI export names without changing their slot starts.
    # All such sites belong to read-only .rdata in this exact hash-pinned image.
    rdata = next(s for s in pe.sections if s.Name.rstrip(b'\0') == b'.rdata')
    slots = set()
    for match in re.finditer(b'frenchify', original, re.I):
        if is_class_byte(match.start()):
            continue
        start = original.rfind(b'\0', rdata.PointerToRawData, match.start()) + 1
        end = original.find(b'\0', match.end())
        require(rdata.PointerToRawData <= start <= match.start() < end
                < rdata.PointerToRawData + rdata.SizeOfRawData, 'Brand string outside .rdata')
        slots.add((start, end + 1))
    for start, end in sorted(slots):
        old = original[start:end]
        old[:-1].decode('utf8')
        new = old
        for before, after in REPLACEMENTS:
            new = new.replace(before, after)
        require(len(new) <= len(old), 'Brand replacement exceeds original slot')
        patch(start, old, new + bytes(len(old) - len(new)), 'native C string / JNI export branding')

    colored = list(re.finditer(re.escape(COLORED_PREFIX_OLD), original))
    require(len(colored) == profile['colored_prefix_count'], 'Unexpected colored brand site count')
    require(len(COLORED_PREFIX_OLD) == len(COLORED_PREFIX_NEW), 'Colored branding must preserve byte lengths')
    error_offsets = {pe.get_offset_from_rva(rva) for rva in native_error_strings}
    require(error_offsets <= {match.start() for match in colored}, 'Native error prefix location changed')
    for rva, (before, after) in native_error_strings.items():
        offset = pe.get_offset_from_rva(rva)
        require(rdata.PointerToRawData <= offset and offset + len(before)
                <= rdata.PointerToRawData + rdata.SizeOfRawData, 'Native error prose is outside .rdata')
        require(before.startswith(COLORED_PREFIX_OLD) and after.startswith(COLORED_PREFIX_NEW),
                'Native error prefix branding changed')
        patch(offset, before, after, f'native API error prose red and [Adnin] prefix: RVA 0x{rva:x}')
    for match in colored:
        if match.start() in error_offsets:
            continue  # The full-string verified patch already owns this prefix.
        patch(match.start(), COLORED_PREFIX_OLD, COLORED_PREFIX_NEW, 'equal-width colored [Adnin] prefix')
    fragment_rva = profile['fragment_rva']
    patch(pe.get_offset_from_rva(fragment_rva), b'Fre\0nch\0ify\0', b'Adn\0dni\0nin\0',
          'three-byte brand fallback fragments')
    for rva, (before, after) in profile['length_patches'].items():
        patch(pe.get_offset_from_rva(rva), bytes.fromhex(before), bytes.fromhex(after),
              'brand search length 9 to 5')
    for rva, (before, after) in profile['split_brand_patches'].items():
        patch(pe.get_offset_from_rva(rva), bytes.fromhex(before), bytes.fromhex(after),
              'constructed Adnin prefix: inline fragment and append length')
    for rva, (before, after) in profile['split_brand_strings'].items():
        patch(pe.get_offset_from_rva(rva), before, after,
              'constructed Adnin prefix: shared NUL-terminated fragment')

    raw_size = align(len(payload), pe.OPTIONAL_HEADER.FileAlignment)
    output.extend(bytes(new_raw - len(output)))
    output.extend(payload)
    output.extend(bytes(raw_size - len(payload)))
    header = struct.pack('<8sIIIIIIHHI', b'.adnin\0\0', len(payload), new_rva,
                         raw_size, new_raw, 0, 0, 0, 0, 0x40000040)
    patch(section_header, original[section_header:section_header + 40], header,
          'read-only Adnin class section')
    for key, value, fmt, obj in (
        ('NumberOfSections', len(pe.sections) + 1, '<H', pe.FILE_HEADER),
        ('SizeOfImage', align(new_rva + len(payload), pe.OPTIONAL_HEADER.SectionAlignment), '<I', pe.OPTIONAL_HEADER),
        ('SizeOfInitializedData', pe.OPTIONAL_HEADER.SizeOfInitializedData + raw_size, '<I', pe.OPTIONAL_HEADER),
    ):
        offset = obj.get_field_absolute_offset(key)
        length = struct.calcsize(fmt)
        patch(offset, original[offset:offset + length], struct.pack(fmt, value), key)
    checksum_at = pe.OPTIONAL_HEADER.get_field_absolute_offset('CheckSum')
    struct.pack_into('<I', output, checksum_at, 0)
    checksum = pefile.PE(data=bytes(output)).generate_checksum()
    # checksum_at has not been entered in the patch ledger yet.
    output[checksum_at:checksum_at + 4] = original[checksum_at:checksum_at + 4]
    patch(checksum_at, original[checksum_at:checksum_at + 4], struct.pack('<I', checksum), 'PE checksum')

    allowed = bytearray(len(original))
    for start, end in intervals:
        allowed[start:end] = b'\1' * (end - start)
    require(all(a == b or allowed[i] for i, (a, b) in enumerate(zip(original, output))),
            'Unrecorded original-byte mutation')
    require(output[anchor_offset:anchor_offset + len(preserved_bytes)] == preserved_bytes,
            'Native string adjacent to original class was changed')
    require(not re.search(b'frenchify', output, re.I), 'Old plain branding remains')
    require(COLORED_OLD not in output and b'Fre\0nch\0ify\0' not in output,
            'Old colored branding remains')
    for fragment in (b'1Fre', b'fnch', b'cify'):
        # Restrict short-fragment scanning to the pinned original image. New
        # Java/bootstrap data may coincidentally contain these four bytes.
        require(fragment not in output[:len(original)], 'Old constructed branding fragment remains')
    verified = pefile.PE(data=bytes(output))
    exports_before = [(s.name, s.address, s.ordinal) for s in pe.DIRECTORY_ENTRY_EXPORT.symbols]
    exports_after = [(s.name, s.address, s.ordinal) for s in verified.DIRECTORY_ENTRY_EXPORT.symbols]
    require([(name.replace(b'Frenchify', b'Adnin'), address, ordinal)
             for name, address, ordinal in exports_before] == exports_after, 'JNI export ABI changed')
    require([s.name for s in verified.DIRECTORY_ENTRY_EXPORT.symbols]
            == sorted(s.name for s in verified.DIRECTORY_ENTRY_EXPORT.symbols), 'PE export names are not sorted')
    require(verified.OPTIONAL_HEADER.CheckSum == verified.generate_checksum(), 'Invalid PE checksum')
    for item in manifest:
        data = output[item['offset']:item['offset'] + item['size']]
        require(sha(data) == item['sha256'] and read_class(data)['name'] == item['name'],
                f'Reembedded class verification failed: {item["name"]}')
    report = {
        'kind': 'Adnin hybrid: reconstructed native DLL plus rebuilt and renamed Java',
        'inputSha256': sha(original), 'outputSha256': sha(output), 'bytes': len(output),
        'gameRuntimeTested': False, 'stubClassesEmbedded': False,
        'section': {'name': '.adnin', 'rva': new_rva, 'offset': new_raw,
                    'virtualSize': len(payload), 'rawSize': raw_size, 'characteristics': 0x40000040},
        'classes': manifest, 'patches': patches,
        'preservedNativeAnchors': [{'rva': preserved_rva, 'bytes': preserved_bytes.hex()}],
        'nativeExports': [{'name': n.decode(), 'rva': a, 'ordinal': o} for n, a, o in exports_after],
        'coloredLabelTrailingSpace': True,
        'nativeErrorProsePatched': {
            'rvas': list(native_error_strings), 'bodyColorCode': 'c',
            'sameByteLength': True, 'nativeInstructionsChangedByThisPatch': False,
        },
        'constructedBrandingPatched': {
            'builders': profile['builder_rvas'], 'sharedFragments': list(profile['split_brand_strings']),
            'visiblePrefix': '[Adnin]', 'appendedFragmentLengths': [4, 3, 1],
        },
    }
    return bytes(output), report


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--classes', required=True, type=Path)
    parser.add_argument('--index', type=Path, default=root / 'evidence/original-class-index.json')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--force', action='store_true', help='replace only the explicitly named generated output/report')
    args = parser.parse_args()
    require(args.input.resolve() != args.output.resolve(), 'Refusing to overwrite input DLL')
    require(args.report.resolve() not in (args.input.resolve(), args.output.resolve()), 'Report path overlaps DLL')
    require(args.force or (not args.output.exists() and not args.report.exists()), 'Output/report exists; use --force for generated artifacts')
    files = list(args.classes.rglob('*.class'))
    require(all(f.parent == args.classes for f in files), 'Nested classes / compile stubs are not allowed')
    classes = {f.stem: f.read_bytes() for f in files}
    records = json.loads(args.index.read_text(encoding='utf-8-sig'))
    binary, report = rebuild(args.input.read_bytes(), classes, records)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(binary)
    args.report.write_text(json.dumps(report, indent=2), encoding='utf8')
    print(f'Built {len(report["classes"])} Adnin classes; {len(binary)} bytes; SHA256 {sha(binary)}')
    print(f'Class section RVA=0x{report["section"]["rva"]:x}; manifest={args.report}')


if __name__ == '__main__':
    main()
