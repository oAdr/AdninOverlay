"""Package reviewed files after privacy checks; never read runtime user settings.

Public service endpoints remain in the program. Optional --secrets-stdin accepts
a JSON array through stdin for exact, memory-only checks. Never supply a private
value as a command-line argument or put it in a fixture.
"""
from pathlib import Path, PurePosixPath
import argparse
import base64
import hashlib
import ipaddress
import json
import re
import stat
import struct
import sys
import tempfile
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUTS = ROOT.parent
ARTIFACTS = ('Adnin.exe', 'Adnin.dll', 'AdninVanilla.dll')
RELEASE_FILES = ('Adnin.exe',)
ROOT_FILES = ('README.md', '使用说明.txt', '验证状态.md', 'Build.ps1',
              'CMakeLists.txt', 'requirements.txt')
SOURCE_TYPES = {
    'src': {'.java', '.asm', '.inc', '.cpp', '.h', '.hpp'},
    'scripts': {'.py', '.ps1'},
    'tests': {'.py', '.java', '.c', '.cpp', '.h', '.hpp', '.ps1'},
    'cmake': {'.cmake', '.txt'},
}
SOURCE_ASSETS = ('src/injector/assets/adnin.ico', 'src/injector/assets/adnin.png',
                 'resources/java-compat-1.8.9.json', 'resources/THIRD_PARTY_NOTICES.txt')
# Historical build/load logs and arbitrary future evidence are not release inputs.
EVIDENCE_FILES = ('bridge-tests.txt', 'denicker-tests.txt', 'java-tests.txt',
                  'pe-tests.txt', 'original-class-index.json', 'package-tests.txt',
                  'api-verification-v4.json', 'native-tab-filter-v7.md',
                  'vanilla-class-index.json', 'compatibility-v8.md', 'compatibility-tests-v8.txt',
                  'java-compat-validation.md', 'party-output-v9.md', 'party-packet-tests-v9.json',
                  'badlion-window-v10.json', 'native-anticheat-retirement-v11.md',
                  'raven-anticheat-v11.md', 'client-sounds-source-v11.md',
                  'client-sounds-mappings-v11.json', 'client-sounds-tests-v11.json',
                  'anticheat-fixes-v12.md', 'anticheat-phase-baseline-v12.json',
                  'anticheat-tests-v12.json', 'replay-overlay-v12.md', 'session-hud-lifecycle-v12.md',
                  'anticheat-audit-v13.md', 'anticheat-audit-baseline-v13.json', 'anticheat-tests-v13.json',
                  'replay-native-v13-audit.md', 'replay-identities-v13.md', 'replay-format-v14.md',
                  'replay-denick-output-v15.md', 'ui-replay-api-v16.md',
                  'ui-api-scaffold-language-v17.md', 'stability-v18.md',
                  'output-cache-team-v19.md', 'lunar-hang-v20.md',
                  'stability-performance-v21.md', 'nick-skin-output-v22.md',
                  'lobby-eagle-crash-v22.md', 'input-performance-v22.md',
                  'input-hook-review-v22.md', 'native-chat-prune-v22.md')
PRIVATE_NAMES = {
    'toggles.json', 'adnin-features.properties', 'adnin-runtime-status.properties',
    'options.txt', 'accounts.json', 'launcher_accounts.json', 'credentials.json',
    'secrets.json', 'settings.json', 'config.json',
}
PRIVATE_SUFFIXES = {'.properties', '.env', '.pem', '.key', '.p12', '.pfx', '.jks'}
MAX_ENTRY_BYTES = 64 * 1024 * 1024
MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
UUID_LITERAL = re.compile(rb'(?<![A-Za-z0-9])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![A-Za-z0-9])', re.I)
URL_LITERAL = re.compile(rb'https?://[^\s\x00\"\'<>\\]+', re.I)
SENSITIVE_FIELD = rb'(?:api[_ .-]?key|api_(?:hypixel|seraph|aurora|urchin)|urchin\.apiKey|botDenicker(?:Url|\.url)|authorization|access[_-]?token|client[_-]?secret|password|secret)'
QUOTED_CREDENTIAL = re.compile(rb'(?i)(?<![A-Za-z0-9_])' + SENSITIVE_FIELD + rb'''["']?\s*[:=]\s*["']([^"'\r\n\x00]*)["']''')
PROPERTY_CREDENTIAL = re.compile(rb'(?im)^\s*' + SENSITIVE_FIELD + rb'\s*=\s*([^\r\n]+)')
PROPERTY_LITERAL = re.compile(rb'''(?i)(?:setProperty|getProperty)\(\s*["']''' + SENSITIVE_FIELD + rb'''["']\s*,\s*["']([^"'\r\n\x00]*)["']''')
FIXTURE_SECRETS = {b'unit-test-not-secret', b'test-key', b'test-secret',
                   b'test-api-key', b'fixture-key', b'synthetic-key'}


class PackageError(ValueError):
    """Messages contain a file and rule only, never a matched private value."""


def require(condition, message):
    if not condition:
        raise PackageError(message)


def archive_name(name):
    require(isinstance(name, str) and bool(name), 'Invalid archive name')
    path = PurePosixPath(name)
    require('\\' not in name and ':' not in name and not path.is_absolute()
            and all(part not in ('', '.', '..') for part in name.split('/')),
            'Unsafe archive member name')
    return path


def is_private_name(name):
    path = PurePosixPath(name)
    return any(part.lower() in PRIVATE_NAMES or part.lower().startswith('.env')
               or Path(part).suffix.lower() in PRIVATE_SUFFIXES for part in path.parts)


def checked_file(path, allowed_root):
    path, allowed_root = Path(path), Path(allowed_root).resolve()
    require(path.is_file(), 'Required release input is missing: ' + path.name)
    require(path.resolve().is_relative_to(allowed_root),
            'Release input escapes its approved root: ' + path.name)
    current = path.absolute()
    while current != allowed_root and current != current.parent:
        require(not current.is_symlink(), 'Linked release input is forbidden: ' + path.name)
        current = current.parent
    require(path.stat().st_size <= MAX_ENTRY_BYTES, 'Release input exceeds size limit: ' + path.name)
    return path


def is_test_member(name):
    return 'tests' in PurePosixPath(name).parts


def is_fixture_uuid(value, name):
    if not is_test_member(name):
        return False
    # Player-identity fixtures use this conspicuously sequential fake prefix.
    # Arbitrary UUIDs, including pasted API keys, are not accepted as fixtures.
    fields = value.lower().split(b'-')
    return fields[0][1:] == b'2345678' and fields[1] == b'1234'


def fail_content(name, rule):
    raise PackageError('Privacy check failed in ' + name + ': ' + rule)


def scan_bytes(name, data, secrets=()):
    """Reject common private literals without printing a matched value."""
    for secret in secrets:
        variants = (secret.encode('utf8'), secret.encode('utf-16-le'),
                    secret.encode('utf-16-be'),
                    json.dumps(secret, ensure_ascii=True)[1:-1].encode('ascii'),
                    urllib.parse.quote(secret, safe='').encode('ascii'))
        if any(value and value in data for value in variants):
            fail_content(name, 'supplied private value')
    views = [data]
    if b'\0' in data:
        views.append(data.replace(b'\0', b''))
    if b'\\"' in data:
        views.append(data.replace(b'\\"', b'"'))
    for view in views:
        patterns = [QUOTED_CREDENTIAL, PROPERTY_LITERAL]
        if PurePosixPath(name).suffix.lower() in ('.txt', '.json', '.md', '.ini', '.toml', '.yaml', '.yml', '.properties'):
            patterns.append(PROPERTY_CREDENTIAL)
        for pattern in patterns:
            for match in pattern.finditer(view):
                value = match.group(1).strip().strip(b'\"\'')
                if value in (b'', b'Bearer', b'<>', b'<key>', b'<api-key>'):
                    continue
                if is_test_member(name) and value in FIXTURE_SECRETS:
                    continue
                fail_content(name, 'nonempty credential or personal endpoint literal')
        for match in UUID_LITERAL.finditer(view):
            if not is_fixture_uuid(match.group(), name):
                fail_content(name, 'unreviewed UUID-shaped literal')
        for match in URL_LITERAL.finditer(view):
            try:
                url = urllib.parse.urlsplit(match.group().decode('ascii'))
                hostname = url.hostname
            except (UnicodeError, ValueError):
                continue
            fixture_host = is_test_member(name) and hostname in ('example.test', 'example.invalid', 'example.com')
            fixture_credentials = (fixture_host and url.username in ('u', 'user', 'test')
                                   and url.password in (None, 'p', 'pass', 'password', 'test'))
            if (url.username is not None or url.password is not None) and not fixture_credentials:
                fail_content(name, 'URL contains credentials')
            try:
                address = ipaddress.ip_address(hostname or '')
            except ValueError:
                address = None
            if address is not None and not address.is_loopback:
                fail_content(name, 'unreviewed numeric-host API URL')
            for key, value in urllib.parse.parse_qsl(url.query, keep_blank_values=True):
                if re.fullmatch(r'(?i)(key|api[_-]?key|token|access[_-]?token|secret)', key) and value:
                    synthetic_marker = (is_test_member(name)
                                        and re.fullmatch(r'(?:[A-Z]+_)*(?:NOT|PRIVATE)(?:_[A-Z]+)+', value))
                    if synthetic_marker or is_test_member(name) and value.encode('utf8') in FIXTURE_SECRETS:
                        continue
                    if value not in ('<>', '<key>', '<api-key>'):
                        fail_content(name, 'URL contains a credential query value')


def scan_content(name, data, secrets=()):
    require(not is_private_name(name), 'Runtime settings/private file is forbidden: ' + name)
    scan_bytes(name, data, secrets)
    if PurePosixPath(name).suffix.lower() not in ('.asm', '.inc'):
        return
    # Decode numeric db runs so recovered PE data is checked in source archives.
    run = bytearray()
    for line in data.splitlines():
        text = line.strip().split(b';', 1)[0].strip()
        if re.fullmatch(rb'db\s+0x[0-9a-fA-F]{1,2}(?:\s*,\s*0x[0-9a-fA-F]{1,2})*', text):
            run.extend(int(value, 16) for value in re.findall(rb'0x([0-9a-fA-F]{1,2})', text))
        elif text and not text.endswith(b':'):
            if run:
                scan_bytes(name, bytes(run), secrets)
                run.clear()
    if run:
        scan_bytes(name, bytes(run), secrets)


def source_entries(root=None):
    root = Path(ROOT if root is None else root).resolve()
    entries = []
    for directory, suffixes in SOURCE_TYPES.items():
        for path in sorted((root / directory).rglob('*')):
            relative = path.relative_to(root)
            if '__pycache__' in relative.parts or path.suffix == '.pyc':
                continue
            require(not is_private_name(relative.as_posix()),
                    'Runtime settings/private file found in source tree: ' + relative.as_posix())
            require(not path.is_symlink(), 'Linked source input is forbidden: ' + relative.as_posix())
            if path.is_file() and path.suffix.lower() in suffixes:
                entries.append((checked_file(path, root), 'adnin-build/' + relative.as_posix(), root))
    for name in EVIDENCE_FILES:
        path = root / 'evidence' / name
        if path.exists():
            entries.append((checked_file(path, root), 'adnin-build/evidence/' + name, root))
    # Only these two reviewed branding assets are release inputs. Keep arbitrary
    # PNG/ICO files, screenshots, and other assets outside the source archive.
    for name in SOURCE_ASSETS:
        path = root / name
        if path.exists():
            entries.append((checked_file(path, root), 'adnin-build/' + name, root))
    for name in ROOT_FILES:
        entries.append((checked_file(root / name, root), 'adnin-build/' + name, root))
    return entries


def validate_defaults(root=None):
    root = Path(ROOT if root is None else root).resolve()
    source = checked_file(Path(root) / 'src/java/AdninGui4.java', root).read_text(encoding='utf8')
    for field in ('api_hypixel', 'api_seraph', 'api_aurora', 'api_urchin', 'botDenickerUrl'):
        pattern = r'public\s+static\s+String\s+' + field + r'\s*=\s*""\s*;'
        require(len(re.findall(pattern, source)) == 1,
                'Production key/URL default must be blank: ' + field)


def write_archive(destination, entries, secrets=()):
    names, total = set(), 0
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, name, approved_root in entries:
            archive_name(name)
            require(name.casefold() not in names, 'Duplicate archive member: ' + name)
            names.add(name.casefold())
            data = checked_file(path, approved_root).read_bytes()
            total += len(data)
            require(total <= MAX_ARCHIVE_BYTES, 'Archive exceeds size limit')
            scan_content(name, data, secrets)
            archive.writestr(name, data)


def verify(path, secrets=()):
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, 'Archive CRC verification failed')
        names, total = set(), 0
        for item in archive.infolist():
            archive_name(item.filename)
            require(item.filename.casefold() not in names, 'Duplicate archive member: ' + item.filename)
            names.add(item.filename.casefold())
            require(not stat.S_ISLNK(item.external_attr >> 16), 'Archive contains a symbolic link')
            total += item.file_size
            require(item.file_size <= MAX_ENTRY_BYTES and total <= MAX_ARCHIVE_BYTES,
                    'Archive exceeds size limit')
            scan_content(item.filename, archive.read(item), secrets)
    return {'file': path.name, 'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def read_private_values(stream):
    raw = stream.read(65537)
    require(len(raw) <= 65536, 'Private scan input exceeds size limit')
    try:
        values = json.loads(raw)
    except (json.JSONDecodeError, UnicodeError):
        raise PackageError('Private scan input must be a JSON array of strings') from None
    require(isinstance(values, list) and len(values) <= 32
            and all(isinstance(value, str) and 8 <= len(value) <= 4096 for value in values),
            'Private scan input must contain at most 32 nonempty values of 8-4096 characters')
    return tuple(values)


def production_source_hashes(root):
    root = Path(root)
    extensions = {'.java', '.cpp', '.h', '.hpp', '.asm', '.inc', '.py', '.ps1', '.json'}
    result = {}
    for directory in ('src', 'scripts', 'resources'):
        for path in sorted((root / directory).rglob('*')):
            if path.is_file() and path.suffix in extensions and '__pycache__' not in path.parts:
                checked_file(path, root)
                result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    for name in ('CMakeLists.txt', 'Build.ps1', 'requirements.txt', 'resources/THIRD_PARTY_NOTICES.txt'):
        result[name] = hashlib.sha256(checked_file(root / name, root).read_bytes()).hexdigest()
    return result


def validate_java_sources(java_report, source_names, *, compatibility=False):
    hashes = java_report.get('sourceSha256', {})
    source_paths = {name:'src/java/' + name for name in source_names}
    if compatibility:
        require(java_report.get('mode') == 'vanilla-obfuscated-1.8.9-shared-source',
                'Compatibility Java report has the wrong target')
        allowed_uncompiled = {'AdninClientPump.java'}
        for path in (ROOT / 'src/java-compat').glob('*.java'):
            require(path.name not in source_paths, 'Duplicate compatibility source basename')
            source_paths[path.name] = path.relative_to(ROOT).as_posix()
        source_names = set(source_paths)
        mapping = checked_file(ROOT / 'resources/java-compat-1.8.9.json', ROOT)
        require(hashlib.sha256(mapping.read_bytes()).hexdigest() == java_report.get('mappingSha256'),
                'Compatibility mappings differ from build')
    else:
        allowed_uncompiled = ({'AdninGuiNewChat.java'}
                              if java_report.get('retainedDormantClass') == 'AdninGuiNewChat' else set())
    require(isinstance(hashes, dict) and set(hashes) <= source_names
            and source_names - set(hashes) <= allowed_uncompiled,
            'Java build report must cover every compiled production Java source')
    if compatibility:
        require(java_report.get('sourcePaths') == {name:source_paths[name] for name in hashes},
                'Compatibility source paths differ from the approved source tree')
    for name, digest in hashes.items():
        require(hashlib.sha256(checked_file(ROOT / source_paths[name], ROOT).read_bytes()).hexdigest() == digest,
                'Source differs from build: ' + name)


def java_utf8_constants(name, data):
    """Read bounded class constants without loading or executing Java bytecode."""
    require(len(data) >= 10 and data[:4] == b'\xca\xfe\xba\xbe',
            'Invalid compiled Java payload: ' + name)
    count, position, index, values = struct.unpack_from('>H', data, 8)[0], 10, 1, set()
    require(count > 0, 'Invalid compiled Java constant pool: ' + name)
    while index < count:
        require(position < len(data), 'Truncated Java constant pool: ' + name)
        tag = data[position]
        position += 1
        if tag == 1:
            require(position + 2 <= len(data), 'Truncated Java constant pool: ' + name)
            size = struct.unpack_from('>H', data, position)[0]
            position += 2
        else:
            size = {3:4, 4:4, 5:8, 6:8, 7:2, 8:2, 9:4, 10:4,
                    11:4, 12:4, 15:3, 16:2, 17:4, 18:4, 19:2, 20:2}.get(tag)
            require(size is not None, 'Unsupported Java constant pool tag: ' + name)
        require(position + size <= len(data), 'Truncated Java constant pool: ' + name)
        if tag == 1:
            values.add(data[position:position + size])
        position += size
        index += 2 if tag in (5, 6) else 1
    require(index == count and position + 14 <= len(data),
            'Incomplete compiled Java payload: ' + name)
    return values


def validate_compiled_java(directory, java_report, secrets=()):
    """Scan raw helpers, including bytes hidden by owner Base64 chunk encoding."""
    directory = Path(directory)
    hashes = java_report.get('classSha256')
    helpers = java_report.get('helperSha256')
    active = java_report.get('compiledActiveClasses')
    owners = java_report.get('bootstrapOwners')
    require(isinstance(hashes, dict) and hashes and isinstance(helpers, dict)
            and isinstance(active, list) and active and isinstance(owners, list) and owners,
            'Java payload fingerprints or bootstrap inventory are missing')
    require(all(isinstance(name, str) and re.fullmatch(r'Adnin[A-Za-z0-9_$]*', name)
                and isinstance(digest, str) and re.fullmatch(r'[0-9a-f]{64}', digest)
                for name, digest in hashes.items()), 'Invalid Java payload fingerprint')
    require(all(isinstance(name, str) for name in active + owners)
            and len(active) == len(set(active)) and len(owners) == len(set(owners))
            and set(owners) <= set(active) and not set(active) & set(helpers)
            and set(hashes) == set(active) | set(helpers),
            'Java payload inventory does not cover every active class and helper')
    require(set(path.stem for path in directory.glob('*.class')) == set(hashes),
            'Compiled Java files differ from the build inventory')
    require(java_report.get('base64ChunkLimit') == 30000,
            'Unsupported Java bootstrap chunk format')
    classes, constants, total = {}, {}, 0
    for name, digest in hashes.items():
        path = checked_file(directory / (name + '.class'), directory)
        data = path.read_bytes()
        total += len(data)
        require(total <= MAX_ARCHIVE_BYTES, 'Compiled Java payloads exceed size limit')
        require(hashlib.sha256(data).hexdigest() == digest, 'Java payload hash mismatch: ' + name)
        scan_content(directory.name + '/' + name + '.class', data, secrets)
        constants[name] = java_utf8_constants(name, data)
        classes[name] = data
    for name, digest in helpers.items():
        require(digest == hashes[name], 'Helper fingerprint differs from compiled payload: ' + name)
        encoded = base64.b64encode(classes[name])
        chunks = [encoded[i:i + 30000] for i in range(0, len(encoded), 30000)]
        for owner in owners:
            require(all(chunk in constants[owner] for chunk in chunks),
                    'Embedded helper chunks differ from compiled payload: ' + owner + '/' + name)
    return classes


def validate_embedded_java(data, embedding, java_report, classes, secrets=()):
    """Tie scanned compiled classes to their exact byte ranges in the DLL."""
    entries = embedding.get('classes')
    require(isinstance(entries, list) and entries, 'Embedded Java inventory is missing')
    seen, spans = set(), []
    active = set(java_report['compiledActiveClasses'])
    dormant = java_report.get('retainedDormantClass')
    expected = active | ({dormant} if dormant else set())
    for item in entries:
        require(isinstance(item, dict), 'Invalid embedded Java entry')
        name, offset, size = item.get('name'), item.get('offset'), item.get('size')
        require(isinstance(name, str) and name in expected and name not in seen,
                'Unexpected or duplicate embedded Java class')
        require(type(offset) is int and type(size) is int and offset >= 0 and size > 0
                and offset + size <= len(data), 'Invalid embedded Java byte range: ' + name)
        require(all(offset + size <= start or offset >= end for start, end in spans),
                'Overlapping embedded Java byte ranges')
        seen.add(name)
        spans.append((offset, offset + size))
        embedded = data[offset:offset + size]
        require(hashlib.sha256(embedded).hexdigest() == item.get('sha256'),
                'Embedded Java fingerprint differs from DLL: ' + name)
        if name in active:
            require(embedded == classes[name], 'Embedded Java differs from scanned class: ' + name)
        java_utf8_constants(name, embedded)
        scan_content('embedded/' + name + '.class', embedded, secrets)
    require(seen == expected, 'Embedded Java inventory does not cover all entrypoints')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, default=ROOT / 'build')
    parser.add_argument('--version', default='v10')
    parser.add_argument('--secrets-stdin', action='store_true',
                        help='Read optional private comparison values from stdin only; never log or save them')
    args = parser.parse_args()
    require(re.fullmatch(r'v[1-9][0-9]*(?:\.[0-9]+)?', args.version), 'Version must look like v10 or v10.1')
    secrets = read_private_values(sys.stdin) if args.secrets_stdin else ()
    build = args.build.resolve()
    validate_defaults()
    report_path = checked_file(build / 'build-report.json', build)
    report = json.loads(report_path.read_text(encoding='utf8'))
    require(report.get('formatVersion') == 2, 'Build must contain the two reviewed runtime payloads')
    require(isinstance(report.get('artifacts'), list), 'Build artifact report is missing')
    require(len(report['artifacts']) == len(ARTIFACTS)
            and {item.get('file') for item in report['artifacts']} == set(ARTIFACTS),
            'Build report must contain exactly the approved two DLLs and injector')
    for artifact in report['artifacts']:
        data = checked_file(build / 'bin' / artifact['file'], build).read_bytes()
        require(hashlib.sha256(data).hexdigest() == artifact.get('sha256'),
                'Build artifact hash mismatch: ' + artifact['file'])
        scan_content(artifact['file'], data, secrets)
    java_path = checked_file(build / 'java-runtime/java-build-report.json', build)
    java_report = json.loads(java_path.read_text(encoding='utf8'))
    source_names = {path.name for path in (ROOT / 'src/java').glob('*.java')}
    # build-java deliberately retains this one original dormant class instead of
    # compiling its source. Its .java still goes through source_entries/privacy
    # checks and into the source ZIP; this exception only concerns compiled hashes.
    validate_java_sources(java_report, source_names)
    compat_java_path = checked_file(build / 'java-vanilla/java-build-report.json', build)
    compat_java_report = json.loads(compat_java_path.read_text(encoding='utf8'))
    validate_java_sources(compat_java_report, source_names, compatibility=True)
    for directory, java_manifest, embedding_name, dll_name in (
            ('java-runtime', java_report, 'reembedding.json', 'Adnin.dll'),
            ('java-vanilla', compat_java_report, 'vanilla-reembedding.json', 'AdninVanilla.dll')):
        classes = validate_compiled_java(build / directory, java_manifest, secrets)
        embedding = json.loads(checked_file(build / embedding_name, build).read_text(encoding='utf8'))
        validate_embedded_java(checked_file(build / 'bin' / dll_name, build).read_bytes(),
                               embedding, java_manifest, classes, secrets)
    require(report.get('sourceInputsSha256') == production_source_hashes(ROOT),
            'Production build inputs differ from the verified build')
    payloads = report.get('runtimePayloads', [])
    require(isinstance(payloads, list) and len(payloads) == 2
            and {item.get('id') for item in payloads} == {'lunar', 'vanilla'},
            'Runtime payload manifest is incomplete')
    artifact_lookup = {item['file']:item for item in report['artifacts']}
    for payload in payloads:
        expected_file = 'Adnin.dll' if payload['id'] == 'lunar' else 'AdninVanilla.dll'
        expected_resource = 101 if payload['id'] == 'lunar' else 102
        require(payload.get('file') == expected_file and payload.get('resourceId') == expected_resource,
                'Runtime payload identity differs')
        require(all(payload.get(key) == artifact_lookup[expected_file][key] for key in ('bytes','sha256')),
                'Runtime payload fingerprint differs')
    live_path = ROOT / 'evidence' / ('live-verification-' + args.version + '.json')
    live = json.loads(checked_file(live_path, ROOT).read_text(encoding='utf8')) if live_path.exists() else {}
    if live:
        require(live.get('artifacts') == report['artifacts'], 'Live evidence belongs to a different build')

    release_entries = [(build / 'bin' / name, name, build) for name in RELEASE_FILES]
    sources = source_entries()
    for name in ('build-report.json', 'bridge.json', 'reembedding.json', 'vanilla-bridge.json', 'vanilla-reembedding.json'):
        sources.append((build / name, 'adnin-build/evidence/' + name, build))
    sources.append((java_path, 'adnin-build/evidence/java-build-report.json', build))
    sources.append((compat_java_path, 'adnin-build/evidence/java-compat-build-report.json', build))
    release_name = 'adnin-test-build-' + args.version + '.zip'
    source_name = 'adnin-source-project-' + args.version + '.zip'
    manifest_name = 'adnin-release-manifest-' + args.version + '.json'
    OUTPUTS.mkdir(parents=True, exist_ok=True)
    # Publish only after both temporary archives pass all checks.
    with tempfile.TemporaryDirectory(prefix='.adnin-package-', dir=OUTPUTS) as temporary:
        temporary = Path(temporary)
        require(temporary.resolve().parent == OUTPUTS.resolve(), 'Invalid staging directory')
        release, source = temporary / release_name, temporary / source_name
        write_archive(release, release_entries, secrets)
        write_archive(source, sources, secrets)
        archives = [verify(release, secrets), verify(source, secrets)]
        manifest = {
            'version': args.version, 'artifacts': report['artifacts'], 'archives': archives,
            'runtimeLoadAndConfigMenuVerified': bool(live.get('runtimeReady') and live.get('configMenuOpened')),
            'liveGameVerified': False, 'liveMultiplayerFeaturesVerified': False,
            'nativeFunctionsAreCompleteCppRewrite': False,
            'runtimePayloads': payloads,
            'privacyChecks': {
                'runtimeSettingsExcluded': True, 'productionKeyAndPersonalUrlDefaultsBlank': True,
                'archiveContentsScanned': True, 'knownPrivateValuesChecked': bool(secrets),
                'compiledJavaPayloadsScanned': True, 'embeddedHelperBytesVerified': True,
                'publicServiceEndpointsRetained': True,
            },
        }
        encoded_manifest = json.dumps(manifest, indent=2)
        scan_content(manifest_name, encoded_manifest.encode('utf8'), secrets)
        (temporary / manifest_name).write_text(encoded_manifest, encoding='utf8')
        release.replace(OUTPUTS / release_name)
        source.replace(OUTPUTS / source_name)
        (temporary / manifest_name).replace(OUTPUTS / manifest_name)
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    try:
        main()
    except PackageError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
