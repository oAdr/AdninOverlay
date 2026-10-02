"""Generate and verify built-in runtime descriptors from the exact final DLLs."""
import hashlib
import json
from pathlib import Path

import pefile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(value, message):
    if not value:
        raise ValueError(message)


def lunar_metadata(report):
    return dict(peHeaderOffset=0x110, imageSize=report['imageSize'],
                markerRva=report['section']['rva'], marker=int(report['marker'], 16),
                signatures=[dict(rva=0x3d3f2, bytes='eb179090909090')],
                classes=[dict(rva=rva, width=8) for rva in (0x1a6390, 0x1a6398, 0x1a63a0, 0x1a63a8)],
                pump=[dict(rva=0x1a81a0, width=8), dict(rva=0x1a63be, width=1)],
                hooks=[dict(rva=0x1a6440, width=1), dict(rva=0x1a63bc, width=1),
                       dict(rva=0x1a6968, width=8), dict(rva=0x1a63d0, width=8)],
                heartbeatRva=0x1a63c8, heartbeatWidth=8)


def descriptor(path, kind, resource_id, metadata):
    data = Path(path).read_bytes()
    pe = pefile.PE(data=data)
    require(kind in ('lunar', 'vanilla', 'forge'), 'Unknown built-in runtime')
    require(pe.FILE_HEADER.Machine == 0x8664 and pe.OPTIONAL_HEADER.Magic == 0x20b,
            'Built-in runtime must be x64')
    require(pe.FILE_HEADER.Characteristics & 0x2000, 'Built-in runtime must be a DLL')
    require(metadata['peHeaderOffset'] == pe.DOS_HEADER.e_lfanew, 'Runtime PE offset differs')
    require(metadata['imageSize'] == pe.OPTIONAL_HEADER.SizeOfImage, 'Runtime image size differs')
    marker = metadata['marker']
    if isinstance(marker, str):
        marker = int(marker, 0)
    require(pe.get_data(metadata['markerRva'], 8) == marker.to_bytes(8, 'little'), 'Runtime marker differs')
    require(metadata['classes'] and metadata['hooks'], 'Runtime initialization checks are missing')
    checks = [item for name in ('classes', 'pump', 'hooks') for item in metadata[name]]
    checks.append(dict(rva=metadata['heartbeatRva'], width=metadata['heartbeatWidth']))
    for item in checks:
        require(item['width'] in (1, 4, 8) and 0 < item['rva'] <= metadata['imageSize'] - item['width'],
                'Runtime initialization address or width is invalid')
        section = pe.get_section_by_rva(item['rva'])
        require(section is not None and section.Characteristics & 0x40000000,
                'Runtime initialization address is not readable')
    for item in metadata['signatures']:
        expected = bytes.fromhex(item['bytes'])
        require(expected and pe.get_data(item['rva'], len(expected)) == expected,
                'Runtime instruction signature differs')
    return dict(id=kind, file=Path(path).name, resourceId=resource_id,
                bytes=len(data), sha256=sha(data), initialization={**metadata, 'marker':marker})


def generated_header(entries):
    require({item['id'] for item in entries} == {'lunar', 'vanilla', 'forge'} and len(entries) == 3,
            'Exactly the three reviewed payloads are required')
    require(len({item['resourceId'] for item in entries}) == 3, 'Payload resource IDs must be distinct')
    lines = ['// Generated from the final verified payloads; do not edit.', '#pragma once',
             '#include "runtime-profile.h"']
    names = []
    for item in entries:
        prefix = 'ADNIN_' + item['id'].upper()
        meta = item['initialization']
        for i, signature in enumerate(meta['signatures']):
            lines.append('inline constexpr unsigned char ' + prefix + '_SIG_' + str(i) + '[] = {' +
                         ','.join('0x' + value for value in (signature['bytes'][j:j+2] for j in range(0,len(signature['bytes']),2))) + '};')
        signature_name = '{}'
        if meta['signatures']:
            signature_name = prefix + '_SIGNATURES'
            lines.append('inline constexpr adnin::RuntimeSignature ' + signature_name + '[] = {')
            for i, signature in enumerate(meta['signatures']):
                lines.append('  {0x%xu, %s_SIG_%d},' % (signature['rva'], prefix, i))
            lines.append('};')
        collections = {}
        for category in ('classes','pump','hooks'):
            collections[category] = '{}'
            if meta[category]:
                name = prefix + '_' + category.upper()
                collections[category] = name
                lines.append('inline constexpr adnin::RuntimeCheck ' + name + '[] = {' +
                             ','.join('{0x%xu,%du}' % (check['rva'],check['width']) for check in meta[category]) + '};')
        kind = {'lunar':'Lunar','vanilla':'Vanilla','forge':'Forge'}[item['id']]
        names.append('  {adnin::PayloadKind::%s, "%s", %du, "%s", %du, 0x%xu, 0x%xu, 0x%xu, 0x%xull, %s, %s, %s, %s, 0x%xu, %du}' % (
            kind, item['id'], item['resourceId'], item['sha256'], item['bytes'],
            meta['peHeaderOffset'],meta['imageSize'],meta['markerRva'],meta['marker'],
            signature_name, collections['classes'],collections['pump'],collections['hooks'],
            meta['heartbeatRva'],meta['heartbeatWidth']))
    lines.append('inline constexpr adnin::RuntimeProfile ADNIN_RUNTIMES[] = {\n' + ',\n'.join(names) + '\n};')
    return '\n'.join(lines) + '\n'


def verify_embedded(image, entries, binary_directory):
    observed = {}
    for kind in image.DIRECTORY_ENTRY_RESOURCE.entries:
        if kind.id != 10:
            continue
        for resource in kind.directory.entries:
            if resource.id not in {entry['resourceId'] for entry in entries}:
                continue
            values = []
            for language in resource.directory.entries:
                entry = language.data.struct
                values.append(image.get_data(entry.OffsetToData, entry.Size))
            observed[resource.id] = values
    for entry in entries:
        data = (Path(binary_directory) / entry['file']).read_bytes()
        require(observed.get(entry['resourceId']) == [data], 'Embedded runtime payload differs: ' + entry['id'])
        require(sha(data) == entry['sha256'], 'Runtime payload changed after descriptor generation')


def source_hashes(root):
    root = Path(root)
    result = {}
    extensions = {'.java','.cpp','.h','.hpp','.asm','.inc','.py','.ps1','.json'}
    for directory in ('src','scripts','resources'):
        for path in sorted((root / directory).rglob('*')):
            if path.is_file() and path.suffix in extensions and '__pycache__' not in path.parts:
                result[path.relative_to(root).as_posix()] = sha(path.read_bytes())
    for filename in ('CMakeLists.txt','Build.ps1','requirements.txt','resources/THIRD_PARTY_NOTICES.txt'):
        result[filename] = sha((root / filename).read_bytes())
    return result
