"""Compose owned MCP references with Forge 1.8.9's bundled SRG mapping.

Only mapping names and descriptors are emitted. No game bytecode, user settings,
API credentials or machine-specific paths are included in the output resource.
"""
import argparse
import hashlib
import json
import lzma
from pathlib import Path
import re
import sys
import zipfile

from java_compat import ClassPath

FORGE_RESOURCE = 'deobfuscation_data-1.8.9.lzma'


def descriptor(value, classes):
    return re.sub(r'L([^;]+);', lambda match: 'L' + classes.get(match[1], match[1]) + ';', value)


def parse_srg(text):
    result = {'classes': {}, 'fields': {}, 'methods': {}}
    for number, line in enumerate(text.splitlines(), 1):
        row = line.split()
        if not row or row[0] == 'PK:':
            continue
        expected = {'CL:': 3, 'FD:': 3, 'MD:': 5}.get(row[0])
        if expected is None or len(row) != expected:
            raise ValueError('Invalid SRG mapping at line ' + str(number))
        if row[0] == 'CL:':
            table, key, value = result['classes'], row[1], row[2]
        else:
            owner, name = row[1].rsplit('/', 1)
            mapped_owner, mapped_name = row[2 if row[0] == 'FD:' else 3].rsplit('/', 1)
            if row[0] == 'FD:':
                table, key, value = result['fields'], (owner, name), (mapped_owner, mapped_name)
            else:
                table, key, value = result['methods'], (owner, name, row[2]), (mapped_owner, mapped_name, row[4])
        if key in table and table[key] != value:
            raise ValueError('Conflicting SRG mapping at line ' + str(number))
        table[key] = value
    for (owner, _), (mapped_owner, _) in result['fields'].items():
        if result['classes'].get(owner) != mapped_owner:
            raise ValueError('Field owner mapping mismatch: ' + owner)
    for (owner, _, source_desc), (mapped_owner, _, target_desc) in result['methods'].items():
        if result['classes'].get(owner) != mapped_owner:
            raise ValueError('Method owner mapping mismatch: ' + owner)
        if descriptor(source_desc, result['classes']) != target_desc:
            raise ValueError('Method descriptor mapping mismatch: ' + owner + source_desc)
    return result


def compose_mapping(compat, srg, runtime):
    classes = srg['classes']
    source_classes = {value: key for key, value in classes.items()}
    source_classes.update(compat['classes'])
    mapped_classes = {value: value for value in classes.values()}
    for named, obfuscated in compat['classes'].items():
        if obfuscated not in classes:
            raise ValueError('Forge class mapping missing: ' + named)
        mapped_classes[named] = classes[obfuscated]
    mapped_fields, mapped_methods = [], []
    for source, destination, is_field in (
            (compat['fields'], mapped_fields, True), (compat['methods'], mapped_methods, False)):
        for owner, name, desc, obfuscated_name in source:
            obfuscated_owner = source_classes.get(owner)
            if obfuscated_owner is None:
                raise ValueError('Compatibility owner class missing: ' + owner)
            obfuscated_desc = descriptor(desc, source_classes)
            table = srg['fields' if is_field else 'methods']
            key = (obfuscated_owner, obfuscated_name) if is_field else (obfuscated_owner, obfuscated_name, obfuscated_desc)
            target = table.get(key)
            if target is None:
                raise ValueError('Forge member mapping missing: ' + owner + '.' + name + desc)
            if target[0] != classes[obfuscated_owner]:
                raise ValueError('Forge composed owner mismatch: ' + owner)
            if is_field:
                info = runtime.get(obfuscated_owner)
                if info is None or not any(member['name'] == obfuscated_name
                        and member['descriptor'] == obfuscated_desc for member in info['fields']):
                    raise ValueError('Shared field descriptor missing from vanilla runtime: ' + owner + '.' + name)
            destination.append([owner, name, desc, target[1]])

    native_fields, native_methods, parents, absent_fields = [], [], {}, []
    for owner in sorted(classes):
        info = runtime.get(owner)
        if info is not None:
            parents[owner] = ([info['parent']] if info['parent'] else []) + list(info['interfaces'])
    for (owner, name), (mapped_owner, mapped_name) in sorted(srg['fields'].items()):
        info = runtime.get(owner)
        if info is None:
            absent_fields.append(owner + '/' + name)
            continue
        members = [field for field in info['fields'] if field['name'] == name]
        if not members:
            # The bundled mapping includes dedicated-server classes and fields
            # stripped from the client. They cannot be requested by this payload.
            absent_fields.append(owner + '/' + name)
            continue
        if len(members) != 1:
            raise ValueError('Vanilla field descriptor ambiguous: ' + owner + '/' + name)
        desc = members[0]['descriptor']
        native_fields.append([owner, name, desc, mapped_owner, mapped_name, descriptor(desc, classes)])
    for (owner, name, desc), (mapped_owner, mapped_name, mapped_desc) in sorted(srg['methods'].items()):
        native_methods.append([owner, name, desc, mapped_owner, mapped_name, mapped_desc])
    return {'format': 1, 'classes': dict(sorted(mapped_classes.items())),
            'fields': sorted(mapped_fields), 'methods': sorted(mapped_methods),
            'nativeMapping': {'format': 1, 'namespace': 'obfuscated-to-forge-srg',
                              'rowSchema': ['sourceOwner', 'sourceName', 'sourceDescriptor',
                                            'targetOwner', 'targetName', 'targetDescriptor'],
                              'classes': dict(sorted(classes.items())), 'fields': native_fields,
                              'methods': native_methods, 'parents': parents,
                              'absentClientFields': absent_fields}}


def generate(forge_jar, vanilla_jar, compatibility_mapping):
    with zipfile.ZipFile(forge_jar) as archive:
        compressed = archive.read(FORGE_RESOURCE)
    unpacked = lzma.decompress(compressed)
    compat_bytes = Path(compatibility_mapping).read_bytes()
    compat = json.loads(compat_bytes)
    runtime = ClassPath([vanilla_jar])
    try:
        result = compose_mapping(compat, parse_srg(unpacked.decode('utf-8')), runtime)
    finally:
        runtime.close()
    result['provenance'] = {
        'forgeResource': FORGE_RESOURCE,
        'forgeMappingSha256': hashlib.sha256(unpacked).hexdigest(),
        'compatibilityMappingResource': 'resources/java-compat-1.8.9.json',
        'compatibilityMappingSha256': hashlib.sha256(compat_bytes).hexdigest(),
        'vanillaJarSha256': hashlib.sha256(Path(vanilla_jar).read_bytes()).hexdigest(),
        'targetNamespace': 'Forge 1.8.9 named classes with SRG members',
        'fieldDescriptors': 'Read from the matching vanilla 1.8.9 client class declarations',
        'absentClientFields': len(result['nativeMapping']['absentClientFields'])}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--forge-jar', type=Path, required=True)
    parser.add_argument('--vanilla-jar', type=Path, required=True)
    parser.add_argument('--compat-mapping', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'resources/java-compat-1.8.9.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = generate(args.forge_jar, args.vanilla_jar, args.compat_mapping)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    native = result['nativeMapping']
    print('Forge mapping: ' + str(len(result['fields'])) + ' shared fields, '
          + str(len(result['methods'])) + ' shared methods; '
          + str(len(native['classes'])) + ' native classes, '
          + str(len(native['fields'])) + ' native fields, '
          + str(len(native['methods'])) + ' native methods')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print('Forge mapping failed: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
