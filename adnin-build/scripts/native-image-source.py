"""Create an exact flat NASM source for a reviewed PE image, without executing it.

This is a byte-preserving native image representation, not recovered C++ source.
Runtime-specific changes are applied separately through hash-pinned patch ledgers.
Normal Adnin builds assemble the checked-in source and do not need the input DLL.
"""
import argparse
import hashlib
import struct
from pathlib import Path


def render(data, expected_sha256):
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected_sha256:
        raise ValueError('Input differs from the reviewed native image')
    if len(data) < 0x100 or data[:2] != b'MZ':
        raise ValueError('Expected a PE image')
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    if pe + 24 > len(data) or data[pe:pe+4] != b'PE\0\0':
        raise ValueError('Invalid PE header')
    machine, count = struct.unpack_from('<HH', data, pe+4)
    optional_size, flags = struct.unpack_from('<HH', data, pe+20)
    if machine != 0x8664 or not flags & 0x2000:
        raise ValueError('Expected an x64 DLL')
    table = pe + 24 + optional_size
    if not 1 <= count <= 32 or table + count*40 > len(data):
        raise ValueError('Invalid section table')
    sections = {}
    for i in range(count):
        entry = table + i*40
        name = data[entry:entry+8].split(b'\0', 1)[0].decode('ascii')
        rva, size, offset = struct.unpack_from('<III', data, entry+12)
        if offset + size > len(data):
            raise ValueError('Truncated native section')
        if size:
            sections[offset] = (name, rva, size)
    lines = [
        '; Exact reviewed native PE image; the build does not read the supplied DLL.',
        '; Native code and data are encoded as bytes, not a complete C++ rewrite.',
        '; SHA-256: ' + digest,
        'BITS 64', 'SECTION pe_image start=0 vstart=0 align=1',
    ]
    for offset in range(0, len(data), 16):
        if offset in sections:
            name, rva, size = sections[offset]
            lines.append('; SECTION %s | RVA 0x%x | file 0x%x | size 0x%x' % (name, rva, offset, size))
        lines.append('db ' + ','.join('0x%02x' % value for value in data[offset:offset+16]))
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve() or args.output.exists():
        raise ValueError('Refusing to replace an existing file')
    source = render(args.input.read_bytes(), args.sha256.lower())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(source, encoding='ascii')
    print('Created exact native image source; no input code was executed.')


if __name__ == '__main__':
    main()
