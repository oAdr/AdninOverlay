"""Offline packaging privacy tests. All probe values are generated fake data."""
from pathlib import Path
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('adnin_package', ROOT / 'scripts/package.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class PackagePrivacyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='adnin-package-test-')
        self.root = Path(self.temp.name)
        # Random probes belong only to these isolated tests, never user settings.
        self.probe = str(uuid.uuid4())

    def tearDown(self):
        self.temp.cleanup()

    def assertBlocked(self, name, data, secrets=()):
        with self.assertRaises(package.PackageError) as caught:
            package.scan_content(name, data, secrets)
        self.assertNotIn(self.probe, str(caught.exception))

    def fake_source_tree(self):
        for filename in package.ROOT_FILES:
            (self.root / filename).write_text('Reviewed fixture\n', encoding='utf8')
        (self.root / 'src/java').mkdir(parents=True)
        fields = ('api_hypixel', 'api_seraph', 'api_aurora', 'api_urchin', 'botDenickerUrl')
        source = '\n'.join('public static String ' + field + ' = "";' for field in fields)
        (self.root / 'src/java/AdninGui4.java').write_text(source, encoding='utf8')
        (self.root / 'resources').mkdir()
        (self.root / 'resources/java-compat-1.8.9.json').write_text('{"fixture":true}', encoding='utf8')
        (self.root / 'resources/THIRD_PARTY_NOTICES.txt').write_text('Owned third-party notice fixture\n', encoding='utf8')

    def test_private_names_are_rejected_in_every_directory_and_case(self):
        for name in ('adnin-features.properties', 'TOGGLES.JSON', '.env.local',
                     'config.json', 'accounts.json', 'credential.pem'):
            with self.subTest(name=name):
                self.assertBlocked('adnin-build/tests/fixtures/' + name, b'')

    def test_key_literal_is_rejected_without_disclosing_value(self):
        content = ('public static String ' + 'api_' + 'urchin = "' + self.probe + '";').encode()
        self.assertBlocked('adnin-build/src/java/Feature.java', content)

    def test_json_escaped_json_and_utf16_are_checked(self):
        content = json.dumps({'api' + 'Key': self.probe})
        for value in (content.encode(), content.encode('utf-16-le'), content.encode('utf-16-be'),
                      content.replace('"', '\\"').encode()):
            with self.subTest(encoding_length=len(value)):
                self.assertBlocked('evidence/sample.json', value)

    def test_private_endpoint_assignment_is_rejected(self):
        url = 'https://' + 'private.example.test/api/users'
        content = ('botDenicker' + 'Url = "' + url + '";').encode()
        self.assertBlocked('adnin-build/src/java/Feature.java', content)

    def test_property_and_getproperty_defaults_are_checked(self):
        property_name = 'urchin.' + 'apiKey'
        self.assertBlocked('evidence/log.txt', (property_name + '=' + self.probe).encode())
        content = ('p.getProperty("' + property_name + '", "' + self.probe + '")').encode()
        self.assertBlocked('adnin-build/src/java/Feature.java', content)

    def test_blank_defaults_and_dynamic_assignments_are_allowed(self):
        package.scan_content('adnin-build/src/java/Feature.java',
                             b'api_urchin = "";\napi_urchin = value;\nbotDenickerUrl = field.getText();')

    def test_public_endpoints_and_test_loopback_are_allowed(self):
        for url in (b'https://api.urchin.ws/player/', b'https://api.mojang.com/users/profiles/minecraft/',
                    b'https://example.test/?q=<>', b'http://127.0.0.1:1/api/users'):
            package.scan_content('adnin-build/tests/Fixture.java', url)

    def test_nonloopback_numeric_host_is_rejected(self):
        url = ('http://' + '.'.join(('203', '0', '113', '45')) + ':1234/api/users').encode()
        self.assertBlocked('README.md', url)

    def test_url_userinfo_and_query_secret_are_rejected(self):
        host = 'example.test'
        for url in ('https://user:' + self.probe + '@' + host,
                    'https://' + host + '/?key=' + self.probe):
            self.assertBlocked('README.md', url.encode())

    def test_uuid_keys_are_rejected_even_without_a_key_field(self):
        self.assertBlocked('Adnin.exe', b'MZ\0embedded-dll\0' + self.probe.encode())
        self.assertBlocked('adnin-build/tests/Fixture.java', self.probe.encode())

    def test_sequential_player_uuid_fixture_is_allowed_only_in_tests(self):
        fixture = '-'.join(('12345678', '1234', '4234', '8234', '123456789abc')).encode()
        package.scan_content('adnin-build/tests/Fixture.java', fixture)
        self.assertBlocked('adnin-build/src/java/Feature.java', fixture)

    def test_known_private_value_is_checked_in_memory(self):
        # This probe is deliberately not UUID-shaped to exercise exact matching.
        probe = 'generated-test-' + self.probe.replace('-', '')
        for encoded in (probe.encode(), probe.encode('utf-16-le'), probe.encode('utf-16-be')):
            self.assertBlocked('Adnin.exe', b'MZ\0' + encoded, (probe,))

    def test_assembly_data_is_scanned(self):
        secret = ('api' + 'Key = "' + self.probe + '"').encode()
        rows = [b'db ' + b','.join(('0x%x' % b).encode() for b in secret[i:i+16])
                for i in range(0, len(secret), 16)]
        for extension in ('.asm', '.inc'):
            self.assertBlocked('adnin-build/src/native/data' + extension, b'\n'.join(rows))

    def test_stdin_values_do_not_echo(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            values = package.read_private_values(io.StringIO(json.dumps([self.probe])))
        self.assertEqual(values, (self.probe,))
        self.assertEqual(output.getvalue(), '')
        for bad in ('not-json', '{"wrong":true}', '["short"]'):
            with self.assertRaises(package.PackageError):
                package.read_private_values(io.StringIO(bad))

    def test_default_guard_requires_all_keys_and_url_to_be_blank(self):
        self.fake_source_tree()
        package.validate_defaults(self.root)
        source = self.root / 'src/java/AdninGui4.java'
        source.write_text(source.read_text().replace('""', '"' + self.probe + '"', 1))
        with self.assertRaises(package.PackageError):
            package.validate_defaults(self.root)

    def test_source_allowlist_excludes_unreviewed_logs_and_assets(self):
        self.fake_source_tree()
        (self.root / 'evidence').mkdir()
        (self.root / 'evidence/unreviewed-log.txt').write_text(self.probe)
        (self.root / 'src/java/unreviewed.bin').write_bytes(self.probe.encode())
        names = [name for path, name, root in package.source_entries(self.root)]
        self.assertTrue(any(name.endswith('AdninGui4.java') for name in names))
        self.assertFalse(any('unreviewed' in name for name in names))

    def test_source_allowlist_includes_only_exact_reviewed_icon_assets(self):
        self.fake_source_tree()
        assets = self.root / 'src/injector/assets'
        assets.mkdir(parents=True)
        for name in ('adnin.ico', 'adnin.png', 'private.ico', 'screenshot.png', 'adnin-backup.png'):
            (assets / name).write_bytes(b'synthetic icon fixture')
        other = self.root / 'src/java/adnin.png'
        other.write_bytes(b'unreviewed same-name file in another directory')
        entries = package.source_entries(self.root)
        names = {name for path, name, approved_root in entries}
        expected = {'adnin-build/src/injector/assets/adnin.ico',
                    'adnin-build/src/injector/assets/adnin.png'}
        self.assertEqual({name for name in names if name.endswith(('.ico', '.png'))}, expected)
        self.assertNotIn('.ico', package.SOURCE_TYPES['src'])
        self.assertNotIn('.png', package.SOURCE_TYPES['src'])
        # Exact allowlisting does not bypass ordinary content privacy checks.
        (assets / 'adnin.png').write_bytes(self.probe.encode())
        with self.assertRaisesRegex(package.PackageError, 'Privacy check failed'):
            package.write_archive(self.root / 'icons.zip', package.source_entries(self.root))

    def test_runtime_settings_in_source_tree_stop_packaging(self):
        self.fake_source_tree()
        (self.root / 'src/java/toggles.json').write_text('{}')
        with self.assertRaises(package.PackageError):
            package.source_entries(self.root)

    def test_escape_from_approved_root_is_rejected(self):
        outside = self.root / 'outside.java'
        outside.write_text('fixture')
        approved = self.root / 'approved'
        approved.mkdir()
        with self.assertRaises(package.PackageError):
            package.checked_file(outside, approved)

    def test_linked_file_is_rejected(self):
        target = self.root / 'target.java'
        target.write_text('fixture')
        link = self.root / 'linked.java'
        try:
            link.symlink_to(target)
        except (OSError, NotImplementedError):
            self.skipTest('This host cannot create a test symlink')
        with self.assertRaises(package.PackageError):
            package.checked_file(link, self.root)

    def test_archive_names_reject_traversal_absolute_and_duplicate_paths(self):
        for name in ('../x', '/x', 'C:/x', 'x\\y', 'x/../y', 'x//y'):
            with self.subTest(name=name), self.assertRaises(package.PackageError):
                package.archive_name(name)
        path = self.root / 'fixture.txt'
        path.write_text('safe')
        entries = [(path, 'Readme.txt', self.root), (path, 'README.TXT', self.root)]
        with self.assertRaises(package.PackageError):
            package.write_archive(self.root / 'duplicate.zip', entries)

    def test_archive_verification_rejects_private_files_and_symbolic_links(self):
        for name, symlink in (('toggles.json', False), ('linked', True)):
            with self.subTest(name=name):
                archive = self.root / 'bad.zip'
                with zipfile.ZipFile(archive, 'w') as output:
                    info = zipfile.ZipInfo(name)
                    if symlink:
                        info.create_system = 3
                        info.external_attr = (stat.S_IFLNK | 0o777) << 16
                    output.writestr(info, '{}')
                with self.assertRaises(package.PackageError):
                    package.verify(archive)

    def test_release_policy_contains_only_single_executable(self):
        self.assertEqual(package.RELEASE_FILES, ('Adnin.exe',))
        exe = self.root / 'Adnin.exe'
        exe.write_bytes(b'MZ safe synthetic embedded DLL fixture')
        archive = self.root / 'release.zip'
        package.write_archive(archive, [(exe, 'Adnin.exe', self.root)])
        package.verify(archive)
        with zipfile.ZipFile(archive) as saved:
            self.assertEqual(saved.namelist(), ['Adnin.exe'])
            self.assertEqual(saved.read('Adnin.exe'), exe.read_bytes())

    def fake_build_tree(self):
        self.fake_source_tree()
        build = self.root / 'build-v4'
        (build / 'bin').mkdir(parents=True)
        (build / 'java-runtime').mkdir()
        (build / 'java-vanilla').mkdir()
        helper = self.synthetic_class('AdninApi')
        owner = self.synthetic_class('AdninGui4', [base64.b64encode(helper)])
        class_report = {'classSha256': {'AdninGui4': hashlib.sha256(owner).hexdigest(),
                                       'AdninApi': hashlib.sha256(helper).hexdigest()},
                        'helperSha256': {'AdninApi': hashlib.sha256(helper).hexdigest()},
                        'compiledActiveClasses': ['AdninGui4'], 'bootstrapOwners': ['AdninGui4'],
                        'base64ChunkLimit': 30000}
        for folder in ('java-runtime', 'java-vanilla'):
            (build / folder / 'AdninGui4.class').write_bytes(owner)
            (build / folder / 'AdninApi.class').write_bytes(helper)
        prefix = b'MZ synthetic fixture'
        artifacts = []
        for name in package.ARTIFACTS:
            path = build / 'bin' / name
            path.write_bytes(prefix + owner if name.endswith('.dll') else prefix)
            artifacts.append({'file': name, 'bytes': path.stat().st_size,
                              'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        payloads = [{**item, 'id':'lunar' if item['file'] == 'Adnin.dll' else 'vanilla',
                     'resourceId':101 if item['file'] == 'Adnin.dll' else 102}
                    for item in artifacts if item['file'].endswith('.dll')]
        (build / 'build-report.json').write_text(json.dumps({'formatVersion':2,'artifacts': artifacts,
            'runtimePayloads':payloads,'sourceInputsSha256':package.production_source_hashes(self.root)}), encoding='utf8')
        for name in ('bridge.json', 'vanilla-bridge.json'):
            (build / name).write_text('{}', encoding='utf8')
        embedding = {'classes': [{'name': 'AdninGui4', 'offset': len(prefix), 'size': len(owner),
                                 'sha256': hashlib.sha256(owner).hexdigest()}]}
        for name in ('reembedding.json', 'vanilla-reembedding.json'):
            (build / name).write_text(json.dumps(embedding), encoding='utf8')
        source = self.root / 'src/java/AdninGui4.java'
        hashes = {source.name: hashlib.sha256(source.read_bytes()).hexdigest()}
        (build / 'java-runtime/java-build-report.json').write_text(json.dumps({**class_report, 'sourceSha256': hashes}), encoding='utf8')
        (build / 'java-vanilla/java-build-report.json').write_text(json.dumps({
            **class_report,
            'sourceSha256':hashes, 'mode':'vanilla-obfuscated-1.8.9-shared-source',
            'sourcePaths':{source.name:'src/java/' + source.name},
            'mappingSha256':hashlib.sha256((self.root / 'resources/java-compat-1.8.9.json').read_bytes()).hexdigest()}), encoding='utf8')
        return build

    @staticmethod
    def synthetic_class(name, strings=()):
        def utf(value):
            return b'\x01' + struct.pack('>H', len(value)) + value
        pool = [utf(name.encode('ascii')), b'\x07\x00\x01',
                utf(b'java/lang/Object'), b'\x07\x00\x03']
        for value in strings:
            pool.append(utf(value))
            pool.append(b'\x08' + struct.pack('>H', len(pool)))
        return (b'\xca\xfe\xba\xbe\x00\x00\x00\x34' + struct.pack('>H', len(pool) + 1)
                + b''.join(pool) + struct.pack('>7H', 0x11, 2, 4, 0, 0, 0, 0))

    def compiled_fixture(self, payload=b'public fixture'):
        directory = self.root / 'java-runtime'
        directory.mkdir()
        helper = self.synthetic_class('AdninApi', [b'A' * 30000, payload])
        encoded = base64.b64encode(helper)
        chunks = [encoded[i:i + 30000] for i in range(0, len(encoded), 30000)]
        owner = self.synthetic_class('AdninGui4', chunks)
        classes = {'AdninGui4': owner, 'AdninApi': helper}
        for name, data in classes.items():
            (directory / (name + '.class')).write_bytes(data)
        report = {'classSha256': {name:hashlib.sha256(data).hexdigest() for name, data in classes.items()},
                  'helperSha256': {'AdninApi': hashlib.sha256(helper).hexdigest()},
                  'compiledActiveClasses': ['AdninGui4'], 'bootstrapOwners': ['AdninGui4'],
                  'base64ChunkLimit': 30000}
        return directory, report, classes, chunks

    def test_compiled_helpers_are_scanned_beyond_the_first_base64_chunk(self):
        directory, report, classes, chunks = self.compiled_fixture(self.probe.encode())
        self.assertGreater(len(chunks), 1)
        # Raw owner scanning cannot see a value encoded into its later chunk.
        package.scan_content('AdninGui4.class', classes['AdninGui4'])
        with self.assertRaisesRegex(package.PackageError, 'Privacy check failed') as caught:
            package.validate_compiled_java(directory, report)
        self.assertNotIn(self.probe, str(caught.exception))

    def test_compiled_helpers_scan_exact_private_values_without_echo(self):
        probe = 'generated-test-' + self.probe.replace('-', '')
        directory, report, classes, chunks = self.compiled_fixture(probe.encode())
        package.scan_content('AdninGui4.class', classes['AdninGui4'], (probe,))
        with self.assertRaisesRegex(package.PackageError, 'supplied private value') as caught:
            package.validate_compiled_java(directory, report, (probe,))
        self.assertNotIn(probe, str(caught.exception))

    def test_compiled_inventory_hashes_and_complete_helper_chunks_are_verified(self):
        directory, report, classes, chunks = self.compiled_fixture()
        self.assertEqual(package.validate_compiled_java(directory, report), classes)
        helper_file = directory / 'AdninApi.class'
        helper_file.write_bytes(classes['AdninApi'] + b'changed')
        with self.assertRaisesRegex(package.PackageError, 'Java payload hash mismatch'):
            package.validate_compiled_java(directory, report)
        helper_file.write_bytes(classes['AdninApi'])
        unreported = directory / 'AdninUnexpected.class'
        unreported.write_bytes(classes['AdninApi'])
        with self.assertRaisesRegex(package.PackageError, 'files differ'):
            package.validate_compiled_java(directory, report)
        unreported.unlink()
        owner = self.synthetic_class('AdninGui4', chunks[:1])
        (directory / 'AdninGui4.class').write_bytes(owner)
        report['classSha256']['AdninGui4'] = hashlib.sha256(owner).hexdigest()
        with self.assertRaisesRegex(package.PackageError, 'Embedded helper chunks differ'):
            package.validate_compiled_java(directory, report)

    def test_compiled_payload_metadata_must_be_complete_and_safe(self):
        directory, report, classes, chunks = self.compiled_fixture()
        for key, invalid in (('classSha256', {}), ('helperSha256', None),
                             ('compiledActiveClasses', []), ('bootstrapOwners', []),
                             ('base64ChunkLimit', 1)):
            with self.subTest(field=key):
                with self.assertRaises(package.PackageError):
                    package.validate_compiled_java(directory, {**report, key:invalid})
        traversal = {**report, 'classSha256': {'../AdninApi': hashlib.sha256(classes['AdninApi']).hexdigest()}}
        with self.assertRaisesRegex(package.PackageError, 'Invalid Java payload fingerprint'):
            package.validate_compiled_java(directory, traversal)
        for data in (b'', b'\xca\xfe\xba\xbe\x00\x00\x00\x34\x00\x02\x01\xff\xff',
                     b'\xca\xfe\xba\xbe\x00\x00\x00\x34\x00\x02\xff'):
            with self.assertRaises(package.PackageError):
                package.java_utf8_constants('AdninFixture', data)

    def test_embedded_entrypoints_match_the_exact_scanned_class(self):
        directory, report, classes, chunks = self.compiled_fixture()
        owner = classes['AdninGui4']
        data = b'MZ' + owner
        entry = {'name':'AdninGui4', 'offset':2, 'size':len(owner), 'sha256':hashlib.sha256(owner).hexdigest()}
        package.validate_embedded_java(data, {'classes':[entry]}, report, classes)
        with self.assertRaisesRegex(package.PackageError, 'fingerprint differs'):
            package.validate_embedded_java(data[:-1] + b'\x01', {'classes':[entry]}, report, classes)
        modified = self.synthetic_class('AdninGui4', chunks + [b'changed'])
        replacement = {**entry, 'size':len(modified), 'sha256':hashlib.sha256(modified).hexdigest()}
        with self.assertRaisesRegex(package.PackageError, 'differs from scanned class'):
            package.validate_embedded_java(b'MZ' + modified, {'classes':[replacement]}, report, classes)
        for entries in ([], [entry, entry], [{**entry, 'offset':-1}], [{**entry, 'size':len(data) + 1}]):
            with self.subTest(entries=len(entries)), self.assertRaises(package.PackageError):
                package.validate_embedded_java(data, {'classes':entries}, report, classes)

    def invoke_main(self, build):
        output = io.StringIO()
        with mock.patch.object(package, 'ROOT', self.root), \
                mock.patch.object(package, 'OUTPUTS', self.root / 'release'), \
                mock.patch.object(sys, 'argv', ['package.py', '--build', str(build), '--version', 'v4']), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            package.main()
        self.assertNotIn(self.probe, output.getvalue())
        return output.getvalue()

    def test_main_publishes_only_exe_and_independent_source_archive(self):
        build = self.fake_build_tree()
        result = json.loads(self.invoke_main(build))
        release = self.root / 'release'
        with zipfile.ZipFile(release / 'adnin-test-build-v4.zip') as saved:
            self.assertEqual(saved.namelist(), ['Adnin.exe'])
        with zipfile.ZipFile(release / 'adnin-source-project-v4.zip') as saved:
            self.assertIn('adnin-build/src/java/AdninGui4.java', saved.namelist())
            self.assertFalse(any(name.endswith('.dll') for name in saved.namelist()))
        self.assertEqual(result['privacyChecks']['productionKeyAndPersonalUrlDefaultsBlank'], True)
        self.assertEqual(list(release.glob('.adnin-package-*')), [])

    def test_failed_scan_preserves_every_previous_final_deliverable(self):
        build = self.fake_build_tree()
        release = self.root / 'release'
        release.mkdir()
        names = ('adnin-test-build-v4.zip', 'adnin-source-project-v4.zip', 'adnin-release-manifest-v4.json')
        for name in names:
            (release / name).write_bytes(b'previous verified output')
        (self.root / 'README.md').write_text(self.probe, encoding='utf8')
        with self.assertRaises(package.PackageError) as caught:
            self.invoke_main(build)
        self.assertNotIn(self.probe, str(caught.exception))
        for name in names:
            self.assertEqual((release / name).read_bytes(), b'previous verified output')
        self.assertEqual(list(release.glob('.adnin-package-*')), [])

    def test_build_hash_and_source_hash_mismatches_block_release(self):
        build = self.fake_build_tree()
        exe = build / 'bin/Adnin.exe'
        original = exe.read_bytes()
        exe.write_bytes(original + b'changed')
        with self.assertRaisesRegex(package.PackageError, 'hash mismatch'):
            self.invoke_main(build)
        exe.write_bytes(original)
        source = self.root / 'src/java/AdninGui4.java'
        source.write_text(source.read_text() + '\n// Changed after build\n')
        with self.assertRaisesRegex(package.PackageError, 'Source differs from build'):
            self.invoke_main(build)

    def test_exact_retained_dormant_class_is_packaged_and_privacy_checked(self):
        build = self.fake_build_tree()
        dormant = self.root / 'src/java/AdninGuiNewChat.java'
        dormant.write_text('final class AdninGuiNewChat {}\n', encoding='utf8')
        report_path = build / 'java-runtime/java-build-report.json'
        report = json.loads(report_path.read_text(encoding='utf8'))
        report['retainedDormantClass'] = 'AdninGuiNewChat'
        report_path.write_text(json.dumps(report), encoding='utf8')
        compatibility_path = build / 'java-vanilla/java-build-report.json'
        compatibility = json.loads(compatibility_path.read_text(encoding='utf8'))
        compatibility['sourceSha256'][dormant.name] = hashlib.sha256(dormant.read_bytes()).hexdigest()
        compatibility['sourcePaths'][dormant.name] = 'src/java/' + dormant.name
        compatibility_path.write_text(json.dumps(compatibility), encoding='utf8')
        build_report_path = build / 'build-report.json'
        build_report = json.loads(build_report_path.read_text(encoding='utf8'))
        dormant_bytes = self.synthetic_class('AdninGuiNewChat')
        dll_path = build / 'bin/Adnin.dll'
        dll_data = dll_path.read_bytes()
        dll_path.write_bytes(dll_data + dormant_bytes)
        embedding_path = build / 'reembedding.json'
        embedding = json.loads(embedding_path.read_text(encoding='utf8'))
        embedding['classes'].append({'name':'AdninGuiNewChat', 'offset':len(dll_data),
                                     'size':len(dormant_bytes), 'sha256':hashlib.sha256(dormant_bytes).hexdigest()})
        embedding_path.write_text(json.dumps(embedding), encoding='utf8')
        for artifact in build_report['artifacts'] + build_report['runtimePayloads']:
            if artifact['file'] == 'Adnin.dll':
                artifact['bytes'] = dll_path.stat().st_size
                artifact['sha256'] = hashlib.sha256(dll_path.read_bytes()).hexdigest()
        build_report['sourceInputsSha256'] = package.production_source_hashes(self.root)
        build_report_path.write_text(json.dumps(build_report), encoding='utf8')
        self.invoke_main(build)
        with zipfile.ZipFile(self.root / 'release/adnin-source-project-v4.zip') as saved:
            self.assertEqual(saved.read('adnin-build/src/java/AdninGuiNewChat.java'), dormant.read_bytes())
        # Omitting a compile hash must not omit the source privacy scan.
        dormant.write_text(self.probe, encoding='utf8')
        with self.assertRaisesRegex(package.PackageError, 'Privacy check failed'):
            package.write_archive(self.root / 'dormant-source.zip', package.source_entries(self.root))

    def test_compatibility_source_and_mapping_drift_block_release(self):
        build = self.fake_build_tree()
        path = build / 'java-vanilla/java-build-report.json'
        report = json.loads(path.read_text(encoding='utf8'))
        report['sourceSha256'] = {}
        path.write_text(json.dumps(report), encoding='utf8')
        with self.assertRaisesRegex(package.PackageError, 'cover every compiled production Java source'):
            self.invoke_main(build)
        source = self.root / 'src/java/AdninGui4.java'
        report['sourceSha256'] = {source.name:hashlib.sha256(source.read_bytes()).hexdigest()}
        path.write_text(json.dumps(report), encoding='utf8')
        (self.root / 'resources/java-compat-1.8.9.json').write_text('{"fixture":false}', encoding='utf8')
        with self.assertRaisesRegex(package.PackageError, 'mappings differ from build'):
            self.invoke_main(build)

    def test_native_build_inputs_and_payload_mixups_block_release(self):
        build = self.fake_build_tree()
        native = self.root / 'src/native'
        native.mkdir()
        source = native / 'changed.asm'
        source.write_text('db 0x00\n', encoding='ascii')
        with self.assertRaisesRegex(package.PackageError, 'Production build inputs differ'):
            self.invoke_main(build)
        source.unlink()
        path = build / 'build-report.json'
        report = json.loads(path.read_text(encoding='utf8'))
        report['runtimePayloads'][1]['resourceId'] = 101
        path.write_text(json.dumps(report), encoding='utf8')
        with self.assertRaisesRegex(package.PackageError, 'Runtime payload identity differs'):
            self.invoke_main(build)

    def test_missing_hashes_require_exact_dormant_marker_and_file(self):
        build = self.fake_build_tree()
        dormant = self.root / 'src/java/AdninGuiNewChat.java'
        dormant.write_text('final class AdninGuiNewChat {}\n', encoding='utf8')
        report_path = build / 'java-runtime/java-build-report.json'
        report = json.loads(report_path.read_text(encoding='utf8'))
        for marker in (None, 'adninguinewchat', 'OtherClass'):
            with self.subTest(marker=marker):
                if marker is None:
                    report.pop('retainedDormantClass', None)
                else:
                    report['retainedDormantClass'] = marker
                report_path.write_text(json.dumps(report), encoding='utf8')
                with self.assertRaisesRegex(package.PackageError, 'cover every compiled production Java source'):
                    self.invoke_main(build)
        report['retainedDormantClass'] = 'AdninGuiNewChat'
        report_path.write_text(json.dumps(report), encoding='utf8')
        (self.root / 'src/java/OtherClass.java').write_text('final class OtherClass {}\n', encoding='utf8')
        with self.assertRaisesRegex(package.PackageError, 'cover every compiled production Java source'):
            self.invoke_main(build)

    def test_actual_reviewed_sources_pass_generic_privacy_checks(self):
        package.validate_defaults(ROOT)
        for path, name, approved_root in package.source_entries(ROOT):
            package.scan_content(name, path.read_bytes())

    def test_guards_still_run_when_python_optimizations_are_enabled(self):
        script = ('import importlib.util\n'
                  's=importlib.util.spec_from_file_location("pkg", ' + repr(str(ROOT / 'scripts/package.py')) + ')\n'
                  'm=importlib.util.module_from_spec(s);s.loader.exec_module(m)\n'
                  'try: m.archive_name("../escape")\n'
                  'except m.PackageError: raise SystemExit(0)\n'
                  'raise SystemExit(2)\n')
        result = subprocess.run([sys.executable, '-O', '-c', script], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, 'Privacy checks must not depend on Python assert')


if __name__ == '__main__':
    unittest.main(verbosity=2)
