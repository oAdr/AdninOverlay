"""Offline packet round trips and production Features' adaptive limit probe.

The vanilla test instantiates only real packet/buffer classes with in-memory
Netty storage. The Features test shadows Minecraft and C01 with owned fixtures;
Minecraft initialization raises an assertion. No game is started, no DLL is
loaded, no chat is sent, and no personal settings are read.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('party_packet_build_common', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

parser = argparse.ArgumentParser(add_help=False)
parser.add_argument('--jdk', type=Path)
parser.add_argument('--classes', type=Path, required=True, help='Actual named compiled Features output')
parser.add_argument('--vanilla-jar', type=Path)
parser.add_argument('--classpath', help='Local compile/runtime dependencies; defaults to classes/runtime-classpath.txt')
parser.add_argument('--work', type=Path, help='Parent for temporary owned test artifacts')
parser.add_argument('--report', type=Path, help='Optional JSON results, including input hashes')
ARGS, REST = parser.parse_known_args()
REPORT = {'originalDllLoaded': False, 'gameInitialized': False, 'networkUsed': False, 'checks': {}}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def runtime_jar():
    if ARGS.vanilla_jar:
        value = ARGS.vanilla_jar.resolve()
        if value.is_file(): return value
        raise ValueError('Vanilla jar not found: ' + str(value))
    for folder in (Path.home() / 'AppData/Roaming/.minecraft', Path.home() / '.minecraft'):
        value = folder / 'versions/1.8.9/1.8.9.jar'
        if value.is_file(): return value.resolve()
    raise ValueError('No local vanilla 1.8.9 jar; provide --vanilla-jar')


def dependency(paths, members, preferred):
    # Lunar bundles may contain only a subset of the Netty/Guava namespace.
    # Prefer the real vanilla library jar and require the needed companion types.
    for path in sorted(paths, key=lambda item: (item.name != preferred, str(item))):
        if not path.is_file() or path.suffix.lower() != '.jar': continue
        with zipfile.ZipFile(path) as jar:
            if set(members).issubset(jar.namelist()): return path
    raise ValueError('Missing complete local dependency for ' + ', '.join(members))


def run(command, label):
    result = subprocess.run([str(value) for value in command], capture_output=True,
                            text=True, encoding='utf8', errors='replace', timeout=60)
    diagnostic = (result.stdout + result.stderr).strip()
    if result.returncode:
        raise AssertionError(label + ' failed:\n' + diagnostic[-12000:])
    return diagnostic


class PartyPacketTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = ARGS.classes.resolve()
        features = cls.classes / 'AdninFeatures.class'
        if not features.is_file(): raise ValueError('Missing compiled Features: ' + str(features))
        cls.vanilla = runtime_jar()
        jdk = ARGS.jdk or os.environ.get('ADNIN_TEST_JDK')
        cls.javac, cls.java = common.find_java(jdk, 'javac'), common.find_java(jdk, 'java')
        cp_file = cls.classes / 'runtime-classpath.txt'
        cp = ARGS.classpath or (cp_file.read_text(encoding='utf8').strip() if cp_file.is_file() else None)
        if not cp: raise ValueError('Provide --classpath or compiled runtime-classpath.txt')
        cls.dependencies = [Path(value).resolve() for value in cp.split(os.pathsep) if value]
        cls.netty = dependency(cls.dependencies,
                               ('io/netty/buffer/Unpooled.class', 'io/netty/buffer/UnpooledByteBufAllocator.class',
                                'io/netty/handler/codec/DecoderException.class'), 'netty-all-4.0.23.Final.jar')
        cls.guava = dependency(cls.dependencies, ('com/google/common/base/Charsets.class',), 'guava-17.0.jar')
        parent = (ARGS.work or ROOT.parents[1] / 'work/adnin-compat/native/packet-tests').resolve()
        parent.mkdir(parents=True, exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix='adnin-party-packet-', dir=parent)
        cls.addClassCleanup(cls.temp.cleanup)
        cls.work = Path(cls.temp.name)
        cls.fixtures = ROOT / 'tests/java-packet'
        vanilla_cp = os.pathsep.join(str(v) for v in (cls.vanilla, cls.netty, cls.guava))
        cls.vanilla_output = cls.work / 'vanilla'
        common.compile_sources(cls.javac, [cls.fixtures / 'AdninVanillaPacketTest.java'],
                               vanilla_cp, cls.vanilla_output, cls.work / 'vanilla.args')
        cls.vanilla_cp = str(cls.vanilla_output) + os.pathsep + vanilla_cp
        cls.probe_output = cls.work / 'probe'
        cls.probe_cp = os.pathsep.join(str(v) for v in (cls.probe_output, cls.classes, *cls.dependencies))
        sources = [cls.fixtures / 'AdninPacketProbeTest.java', *sorted((cls.fixtures / 'fixtures').rglob('*.java'))]
        common.compile_sources(cls.javac, sources, str(cls.classes) + os.pathsep + cp,
                               cls.probe_output, cls.work / 'probe.args')
        REPORT.update(vanillaJar={'path': str(cls.vanilla), 'sha256': sha(cls.vanilla)},
                      productionFeatures={'path': str(features), 'sha256': sha(features)},
                      dependencies=[{'name': p.name, 'sha256': sha(p)} for p in (cls.netty, cls.guava)])

    def test_real_vanilla_packet_constructor_serialization_and_decode_limit(self):
        result = run([self.java, '-Dfile.encoding=UTF-8', '-Duser.language=en', '-Xverify:all',
                      '-cp', self.vanilla_cp, 'adnin.packettests.AdninVanillaPacketTest'],
                     'Real vanilla packet round trip')
        self.assertIn('checks passed', result)
        REPORT['checks']['vanilla'] = result
        print(next(line for line in result.splitlines() if 'AdninVanillaPacketTest:' in line))

    def test_production_features_accepts_only_current_valid_packet_limit(self):
        result = run([self.java, '-Dfile.encoding=UTF-8', '-Duser.language=en', '-Xverify:all',
                      '-cp', self.probe_cp, 'AdninPacketProbeTest'],
                     'Production Features adaptive packet probe')
        self.assertIn('checks passed', result)
        REPORT['checks']['features'] = result
        print(next(line for line in result.splitlines() if 'AdninPacketProbeTest:' in line))


if __name__ == '__main__':
    result = unittest.main(argv=[sys.argv[0], *REST], exit=False).result
    REPORT['passed'] = result.wasSuccessful()
    if ARGS.report:
        ARGS.report.parent.mkdir(parents=True, exist_ok=True)
        ARGS.report.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    raise SystemExit(0 if result.wasSuccessful() else 1)
