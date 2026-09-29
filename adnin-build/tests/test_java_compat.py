"""Regression checks for semantic remapping; fixtures never load a real game."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from classfile import read_class
from java_compat import ClassPath, Mapping, remap_class, validate_runtime

def load_script(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

common = load_script('compat_test_build_common', 'build-java.py')
compat = load_script('compat_test_build', 'build-java-compat.py')

class CompatibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        jdk = os.environ.get('ADNIN_TEST_JDK')
        if not jdk and not shutil.which('javac'):
            installed = Path('C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot')
            if installed.is_dir(): jdk = str(installed)
        try:
            cls.javac, cls.java = common.find_java(jdk, 'javac'), common.find_java(jdk, 'java')
        except ValueError as error: raise unittest.SkipTest(str(error))
        cls.temp = tempfile.TemporaryDirectory(prefix='adnin-remap-test-')
        cls.work = Path(cls.temp.name)
        sources = {
            'net/minecraft/fixture/A.java':'package net.minecraft.fixture; public class A { public static int value=20; public static int twice(int n){return n*2;} }',
            'net/minecraft/fixture/B.java':'package net.minecraft.fixture; public class B { public static int value=30; public static int twice(int n){return n*3;} public static String twice(String n){return n+n;} }',
            'net/minecraft/fixture/Parent.java':'package net.minecraft.fixture; public class Parent { public int invoke(){return 5;} }',
            'AdninProbe.java':'''import net.minecraft.fixture.*;
public class AdninProbe extends Parent {
    public java.util.List<A> generic;
    public A[] array;
    public int invoke() { return super.invoke()+1; }
    public static String value() {
        return new AdninProbe().invoke()+":"+(A.value+A.twice(1))+":"+(B.value+B.twice(1))+":"+B.twice("x")+":"+"net/minecraft/fixture/A";
    }
    public static void main(String[] args) { System.out.print(value()); }
}''',
            'AdninAnnotation.java':'@Deprecated public class AdninAnnotation { }',
        }
        paths = []
        for name,text in sources.items():
            path = cls.work / 'src' / name; path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(text); paths.append(path)
        common.compile_sources(cls.javac,paths,str(cls.work / 'src'),cls.work / 'named',cls.work / 'named.args')
        target_sources = {'x.java':'public class x { public static int one=20; public static int a(int n){return n*2;} }',
                          'y.java':'public class y { public static int two=30; public static int b(int n){return n*3;} public static String c(String n){return n+n;} }',
                          'z.java':'public class z { public int c(){return 5;} }'}
        paths = []
        for name,text in target_sources.items():
            path = cls.work / name; path.write_text(text); paths.append(path)
        common.compile_sources(cls.javac,paths,str(cls.work),cls.work / 'runtime',cls.work / 'runtime.args')
        cls.runtime = cls.work / 'runtime.jar'
        with zipfile.ZipFile(cls.runtime,'w') as jar:
            for file in (cls.work / 'runtime').glob('*.class'): jar.write(file,file.name)
        cls.mapping_data = {'classes':{'net/minecraft/fixture/A':'x','net/minecraft/fixture/B':'y','net/minecraft/fixture/Parent':'z'},
                            'fields':[['net/minecraft/fixture/A','value','I','one'],['net/minecraft/fixture/B','value','I','two']],
                            'methods':[['net/minecraft/fixture/A','twice','(I)I','a'],['net/minecraft/fixture/B','twice','(I)I','b'],
                                       ['net/minecraft/fixture/B','twice','(Ljava/lang/String;)Ljava/lang/String;','c'],
                                       ['net/minecraft/fixture/Parent','invoke','()I','c']]}
        cls.cp = ClassPath([cls.work / 'named'])
        cls.original = (cls.work / 'named/AdninProbe.class').read_bytes()
        cls.mapped = remap_class(cls.original, Mapping(cls.mapping_data,cls.cp))

    @classmethod
    def tearDownClass(cls):
        cls.cp.close(); cls.temp.cleanup()

    def test_owner_sensitive_shared_name_and_type(self):
        refs = read_class(self.mapped)['references']
        self.assertTrue(any(r['owner']=='x' and r['name']=='a' and r['descriptor']=='(I)I' for r in refs))
        self.assertTrue(any(r['owner']=='y' and r['name']=='b' and r['descriptor']=='(I)I' for r in refs))
        self.assertTrue(any(r['owner']=='x' and r['name']=='one' for r in refs))
        self.assertTrue(any(r['owner']=='y' and r['name']=='two' for r in refs))

    def test_inherited_override_and_descriptors(self):
        info = read_class(self.mapped)
        self.assertEqual(info['parent'],'z')
        self.assertTrue(any(m['name']=='c' and m['descriptor']=='()I' for m in info['methods']))
        self.assertTrue(any(f['name']=='array' and f['descriptor']=='[Lx;' for f in info['fields']))
        self.assertIn(b'Ljava/util/List<Lx;>;',self.mapped)

    def test_strings_are_not_class_rewritten(self):
        self.assertIn(b'net/minecraft/fixture/A',self.mapped)

    def test_real_jvm_executes_remapped_pure_fixture(self):
        destination = self.work / 'runtime/AdninProbe.class'; destination.write_bytes(self.mapped)
        result = common.run([self.java,'-Xverify:all','-cp',self.work / 'runtime','AdninProbe'],'Remapped fixture')
        self.assertEqual(result,'6:22:33:xx:net/minecraft/fixture/A')

    def test_static_linkage_resolves_every_game_reference(self):
        result = validate_runtime({'AdninProbe':self.mapped},self.runtime,Mapping(self.mapping_data,self.cp))
        self.assertEqual(result['gameClassesResolved'],3)
        self.assertGreaterEqual(result['memberReferencesResolved'],8)

    def test_wrong_member_mapping_fails_linkage(self):
        bad = dict(self.mapping_data)
        bad['methods'] = [list(row) for row in bad['methods']]
        bad['methods'][0][-1] = 'missing'
        mapping = Mapping(bad,self.cp)
        changed = remap_class(self.original,mapping)
        with self.assertRaisesRegex(ValueError,'unresolved x.missing'):
            validate_runtime({'AdninProbe':changed},self.runtime,mapping)

    def test_missing_class_mapping_fails_closed(self):
        mapping = Mapping({'classes':{},'fields':[],'methods':[]},self.cp)
        with self.assertRaisesRegex(ValueError,'Missing compatibility class mapping'):
            remap_class(self.original,mapping)

    def test_unsupported_annotations_fail_closed(self):
        data = (self.work / 'named/AdninAnnotation.class').read_bytes()
        with self.assertRaisesRegex(ValueError,'Unsupported annotation attribute'):
            remap_class(data,Mapping(self.mapping_data,self.cp))

    def test_compatibility_source_keeps_lunar_sources_unchanged(self):
        path = ROOT / 'src/java/AdninIngameGui.java'
        original = path.read_text(encoding='utf-8-sig')
        self.assertNotIn('AdninFeatures.tick()',original)
        self.assertNotIn('AdninFeatures.tick()',compat.compatibility_source(path))
        self.assertEqual(path.read_text(encoding='utf-8-sig'),original)
        chat = compat.compatibility_chat_source(ROOT / 'src/java/AdninGuiNewChat.java')
        self.assertEqual(chat.count('AdninFeatures.tick()'),1)
        self.assertLess(chat.index('AdninFeatures.tick()'),chat.index('AdninGuiNewChat.nativeClientTick();'))
        self.assertIn('private static native void nativeClientTick();',chat)
        self.assertIn('extends avt implements Runnable',chat)
        self.assertIn('AdninCompatPump.start(ave2, this);',chat)
        self.assertIn('public static void adninStopClientPump() { AdninCompatPump.stop(); }',chat)
        self.assertIn('if (AdninCompatPump.isRunning()) AdninGuiNewChat.nativeClientTick();',chat)
        self.assertIn('public void a(int n) {\n        this.delegate.a(n);',chat)
        self.assertEqual(compat.BOOTSTRAP_OWNERS,('AdninGui4','AdninGuiNewChat'))
        self.assertNotIn('AdninClientPump',compat.ACTIVE_CLASSES)
        self.assertIn('AdninGuiNewChat',compat.ACTIVE_CLASSES)

    def test_reviewed_mapping_has_no_external_secrets_or_game_code(self):
        data = json.loads((ROOT / 'resources/java-compat-1.8.9.json').read_text())
        self.assertEqual(data['format'],1)
        self.assertRegex(data['provenance']['sha256'],r'^[0-9a-f]{64}$')
        self.assertEqual(data['classes']['net/minecraft/client/Minecraft'],'ave')
        self.assertLess(len(json.dumps(data)),20000)

    def test_owned_scheduler_against_queue_only_runtime(self):
        fixture = ROOT / 'tests/java-compat/scheduler'
        sources = sorted(fixture.rglob('*.java')) + [ROOT / 'src/java-compat/AdninCompatPump.java']
        common.compile_sources(self.javac,sources,str(fixture),self.work/'scheduler',self.work/'scheduler.args')
        for scenario in ('normal','queued-stop','active-stop','stop-before-start','start-stop-race','reentrant-stop'):
            with self.subTest(scenario=scenario):
                result = common.run([self.java,'-Xverify:all','-cp',self.work/'scheduler','AdninCompatPumpTest',scenario],
                                    'Scheduler behavior: '+scenario,15)
                self.assertIn('checks passed',result)
                if scenario == 'normal': self.assertIn('bounded queue',result)

    def test_signed_compatibility_bootstrap_preserves_game_domain(self):
        text = compat.inject_compatibility_bootstrap('public class AdninOwner { }','AdninOwner',{'AdninProbe':self.original},'ave')
        self.assertIn('final Class<?> anchor = ave.class;',text)
        self.assertIn('anchor.getProtectionDomain()',text)
        self.assertIn('"privateLookupIn"',text)
        self.assertIn('bytes, 0, bytes.length, domain',text)

if __name__ == '__main__': unittest.main()
