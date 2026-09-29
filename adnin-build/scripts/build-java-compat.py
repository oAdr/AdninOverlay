"""Build the shared Adnin features for the vanilla-obfuscated 1.8.9 ABI.

The supplied compatibility DLL defines nine classes, including GuiNewChat but
not ClientPump. Helpers are remapped before bootstrap Base64 is generated.
All output game references must resolve against the installed vanilla jar.
"""
import argparse
import base64
import importlib.util
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
from classfile import read_class
from java_compat import ClassPath, Mapping, remap_class, validate_runtime, constant_utf8

spec = importlib.util.spec_from_file_location('adnin_java_build', Path(__file__).with_name('build-java.py'))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
ACTIVE_CLASSES = tuple(name for name in common.ACTIVE_CLASSES if name != 'AdninClientPump') + ('AdninGuiNewChat',)
BOOTSTRAP_OWNERS = ('AdninGui4', 'AdninGuiNewChat')

def inject_compatibility_bootstrap(text, owner, helpers, anchor='net.minecraft.client.Minecraft'):
    text = common.inject_bootstrap(text,owner,helpers)
    loader = 'final ClassLoader loader = ' + owner + '.class.getClassLoader();'
    text = text.replace(loader, loader + '\n        final Class<?> anchor = ' + anchor + '.class;\n'
                        '        final java.security.ProtectionDomain domain = anchor.getProtectionDomain();\n'
                        '        if (anchor.getClassLoader() != loader) throw new ExceptionInInitializerError("Adnin helper loader mismatch");')
    lookup = 'java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();'
    text = text.replace(lookup,lookup + '''
                    try {
                        java.lang.reflect.Method privateLookup = java.lang.invoke.MethodHandles.class.getMethod(
                            "privateLookupIn", Class.class, java.lang.invoke.MethodHandles.Lookup.class);
                        lookup = (java.lang.invoke.MethodHandles.Lookup) privateLookup.invoke(null, anchor, lookup);
                    } catch (NoSuchMethodException java8) { /* five-argument defineClass below */ }
''')
    text = text.replace('getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class)',
                        'getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, java.security.ProtectionDomain.class)')
    text = text.replace('define.invoke(loader, names[index], bytes, 0, bytes.length);',
                        'define.invoke(loader, names[index], bytes, 0, bytes.length, domain);')
    return text

def find_native_compiler(explicit, root):
    if explicit:
        candidate = Path(explicit).expanduser().resolve()
        if candidate.is_file(): return candidate
        raise ValueError('JNI verifier compiler does not exist')
    for name in ('x86_64-w64-mingw32-clang', 'clang'):
        found = shutil.which(name)
        if found: return Path(found)
    candidates = sorted((root.parents[1] / 'work/toolchain').glob('*/bin/x86_64-w64-mingw32-clang.exe'))
    if candidates: return candidates[-1]
    raise ValueError('No C compiler for signed JNI bootstrap test; provide --jni-compiler')

def compatibility_chat_source(path):
    text = path.read_text(encoding='utf-8-sig')
    # The recovered wrapper's final three method names do not exist in the
    # vanilla 1.8.9 avt class. Preserve their delegate behavior under the actual
    # runtime names; native registration uses only the two native methods.
    pairs = (('public void i() {\n        this.delegate.i();',
              'public void a() {\n        this.delegate.a();'),
             ('public void j(int n) {\n        this.delegate.j(n);',
              'public void c(int n) {\n        this.delegate.c(n);'),
             ('public int k() {\n        return this.delegate.k();',
              'public int i() {\n        return this.delegate.i();'))
    for before,after in pairs:
        if text.count(before) != 1: raise ValueError('Unexpected recovered chat wrapper source')
        text = text.replace(before,after)
    if text.count('extends avt {') != 1 or text.count('this.delegate = avt2;') != 1:
        raise ValueError('Unexpected chat constructor or superclass')
    text = text.replace('extends avt {','extends avt implements Runnable {')
    text = text.replace('this.delegate = avt2;','this.delegate = avt2;\n        AdninCompatPump.start(ave2, this);')
    insertion = '''
    // Keep recovered non-native aliases callable while delegating to actual
    // vanilla methods. The build renames the first alias to i()V in bytecode;
    // JVM descriptors permit both i()V and the vanilla i()I override.
    public void adninLegacyClearChat() { this.delegate.a(); }
    public void j(int n) { this.delegate.c(n); }
    public int k() { return this.delegate.i(); }
    private static native void nativeClientTick();
    public static void adninStopClientPump() { AdninCompatPump.stop(); }
    @Override public void run() {
        try {
            AdninFeatures.tick();
            if (AdninCompatPump.isRunning()) AdninGuiNewChat.nativeClientTick();
        } catch (Exception failure) {
            // Optional features cannot interrupt the game's scheduled-task loop.
        }
    }
'''
    at = text.rfind('}')
    if at < 0: raise ValueError('Invalid chat wrapper source')
    text = text[:at] + insertion + text[at:]
    return text

def runtime_default():
    for root in (Path.home() / 'AppData/Roaming/.minecraft', Path.home() / '.minecraft'):
        jar = root / 'versions/1.8.9/1.8.9.jar'
        if jar.is_file(): return jar
    raise ValueError('No local vanilla 1.8.9 jar; provide --runtime-jar')

def compatibility_source(path):
    text = common.staged_source(path)
    if path.stem == 'AdninHitboxInstaller':
        if text.count('copyRenderGlobalFields') != 1: raise ValueError('Unexpected hitbox installer source')
        text = text.replace('copyRenderGlobalFields', 'copyBfrFields')
    elif path.stem == 'AdninSessionHudInstaller':
        if text.count('copyGuiIngameFields') != 1: raise ValueError('Unexpected HUD installer source')
        text = text.replace('copyGuiIngameFields', 'copyAvoFields')
    return text

def map_all(classes, mapping_data, classpath):
    source = ClassPath(classpath.split(os.pathsep), classes)
    try:
        mapping = Mapping(mapping_data, source)
        result = {name:remap_class(data, mapping) for name,data in classes.items()}
        return result, mapping
    finally: source.close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk')
    parser.add_argument('--classpath', help='Named compile dependencies; same default as Lunar shared-source build')
    parser.add_argument('--runtime-jar', type=Path, help='Actual vanilla-obfuscated 1.8.9 client jar for static linkage validation')
    parser.add_argument('--mapping', type=Path, help='Reviewed minimal mapping JSON')
    parser.add_argument('--jni-compiler', type=Path, help='C compiler for the owned signed-loader JNI verification fixture')
    parser.add_argument('--java8', type=Path, help='Additional Java 8 executable to test the signed helper fallback')
    parser.add_argument('--write-used-mapping', type=Path, help='Write the exact mapping subset used by both passes for review')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.expanduser().resolve()
    if output == root or output in root.parents or output == Path.home().resolve():
        raise ValueError('Output must be a dedicated build directory')
    runtime = (args.runtime_jar or runtime_default()).expanduser().resolve()
    mapping_file = (args.mapping or root / 'resources/java-compat-1.8.9.json').resolve()
    mapping_data = json.loads(mapping_file.read_text(encoding='utf-8'))
    classpath = args.classpath or common.default_classpath(root)
    javac, java = common.find_java(args.jdk, 'javac'), common.find_java(args.jdk, 'java')
    compiler = common.run([javac, '-version'], 'JDK version', 10)
    paths = sorted(p for p in (root / 'src/java').glob('*.java') if p.stem not in ('AdninClientPump','AdninGuiNewChat'))
    paths.extend(sorted((root / 'src/java-compat').glob('*.java')))
    chat_path = root / 'src/java/AdninGuiNewChat.java'
    hashes = {p.name:common.sha(p.read_bytes()) for p in paths + [chat_path]}
    sources = {p.name:compatibility_source(p) for p in paths}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='adnin-java-compat-', dir=output.parent) as directory:
        work = Path(directory)
        first, second = work / 'source1', work / 'source2'
        first.mkdir(); second.mkdir()
        for name,text in sources.items(): (first / name).write_text(text, encoding='utf-8')
        common.compile_sources(javac, sorted(first.glob('*.java')), classpath, work / 'named1', work / 'pass1.args')
        named1 = common.class_files(work / 'named1')
        mapped1, used = map_all(named1, mapping_data, classpath)
        helpers = {name:data for name,data in mapped1.items() if name not in ACTIVE_CLASSES}
        if not {'AdninApi','AdninFeatures'} <= set(helpers): raise ValueError('Missing required helper classes')
        for name,text in sources.items():
            owner = Path(name).stem
            if owner in BOOTSTRAP_OWNERS: text = inject_compatibility_bootstrap(text, owner, helpers)
            (second / name).write_text(text, encoding='utf-8')
        common.compile_sources(javac, sorted(second.glob('*.java')), classpath, work / 'named2', work / 'pass2.args')
        named2 = common.class_files(work / 'named2')
        final, used2 = map_all(named2, mapping_data, classpath)
        if set(named1) != set(named2): raise ValueError('Class set changed between bootstrap passes')
        for name,data in helpers.items():
            if final[name] != data: raise ValueError('Remapped helper changed between bootstrap passes: ' + name)
        mapped_dir = work / 'mapped'; mapped_dir.mkdir()
        for name,data in final.items(): (mapped_dir / (name + '.class')).write_bytes(data)
        # GuiNewChat is always installed by the compatibility native GUI init;
        # unlike IngameGui it does not depend on Session Stats being enabled.
        # Compile its staged shared source directly in the target namespace.
        chat_source = work / 'AdninGuiNewChat.java'
        chat_text = inject_compatibility_bootstrap(compatibility_chat_source(chat_path),'AdninGuiNewChat',helpers,'ave')
        chat_source.write_text(chat_text, encoding='utf-8')
        common.compile_sources(javac, [chat_source], os.pathsep.join([str(runtime),str(mapped_dir),classpath]),
                               work / 'chat', work / 'chat.args')
        chat_classes = common.class_files(work / 'chat')
        if set(chat_classes) != {'AdninGuiNewChat'}: raise ValueError('Unexpected compiled chat wrapper classes')
        alias_path = ClassPath([runtime], chat_classes)
        try:
            aliases = Mapping({'classes':{},'fields':[],
                               'methods':[['AdninGuiNewChat','adninLegacyClearChat','()V','i']]}, alias_path)
            chat_classes['AdninGuiNewChat'] = remap_class(chat_classes['AdninGuiNewChat'], aliases)
        finally: alias_path.close()
        final.update(chat_classes)
        (mapped_dir / 'AdninGuiNewChat.class').write_bytes(final['AdninGuiNewChat'])
        if set(ACTIVE_CLASSES) - set(final): raise ValueError('Missing native compatibility entrypoint')
        if 'AdninClientPump' in final: raise ValueError('Compatibility must not require ClientPump native ABI')
        for owner in BOOTSTRAP_OWNERS:
            constants = constant_utf8(final[owner])
            for name,data in helpers.items():
                encoded = base64.b64encode(data)
                chunks = [encoded[i:i+common.CHUNK_SIZE] for i in range(0,len(encoded),common.CHUNK_SIZE)]
                if name.encode('ascii') not in constants or any(chunk not in constants for chunk in chunks):
                    raise ValueError('Missing remapped helper bootstrap data: ' + owner + '/' + name)
        validation = validate_runtime(final, runtime, used2)
        fixtures = root / 'tests/java-compat'
        common.compile_sources(javac, sorted(fixtures.glob('*.java')), str(fixtures), work / 'verify', work / 'verify.args')
        bootstrap_cp = os.pathsep.join([str(work/'verify'), *[
            path for path in classpath.split(os.pathsep)
            if Path(path).name.startswith('netty-') and Path(path).suffix == '.jar']])
        verification = common.run([java,'-Xverify:all','-cp',bootstrap_cp,'AdninCompatBootstrapVerify',
                                   mapped_dir,work / 'verify',*common.order_helpers(helpers)],
                                  'Compatibility isolated bootstrap verification',30)
        runtime_paths = work / 'runtime-paths.txt'
        runtime_paths.write_text('\n'.join([str(mapped_dir),str(runtime),*classpath.split(os.pathsep)])+'\n',encoding='utf-8')
        runtime_verification = common.run([java,'-Xverify:all','-cp',work / 'verify','AdninCompatLinkVerify',
                                           runtime_paths,*sorted(final)],'Real-runtime no-initialization JVM verification',30)
        # A normal signed URLClassLoader is also exercised with an owned JNI
        # DefineClass shim. Unlike the ABI-only byte loader, this preserves real
        # JAR certificates and reproduces native owner definition faithfully.
        native_compiler = find_native_compiler(args.jni_compiler,root)
        jdk_root = javac.parent.parent
        shim = work / ('adnin-jni-verify.dll' if os.name == 'nt' else 'libadnin-jni-verify.so')
        native_args = [native_compiler,'-shared','-O2','-I'+str(jdk_root/'include'),
                       '-I'+str(jdk_root/'include'/('win32' if os.name=='nt' else 'linux')),
                       fixtures/'adnin_jni_verify.c','-o',shim]
        if os.name != 'nt': native_args.insert(1,'-fPIC')
        common.run(native_args,'Owned JNI verifier compilation')
        signed_sources = []
        for probe,legacy in (('AdninSignedLegacyProbe',True),('AdninSignedFixedProbe',False)):
            text = 'public final class '+probe+' { }'
            text = common.inject_bootstrap(text,probe,helpers) if legacy else inject_compatibility_bootstrap(text,probe,helpers,'ave')
            path = work/(probe+'.java'); path.write_text(text,encoding='utf-8'); signed_sources.append(path)
        common.compile_sources(javac,signed_sources,os.pathsep.join([str(runtime),classpath]),work/'signed-probes',work/'signed.args')
        signed_paths = work/'signed-runtime-paths.txt'
        signed_paths.write_text('\n'.join([str(runtime),*classpath.split(os.pathsep)])+'\n',encoding='utf-8')
        java_runtimes = [java]
        java8 = args.java8
        if not java8:
            installed8 = sorted(Path('C:/Program Files/Eclipse Adoptium').glob('jdk-8*/bin/java.exe')) if os.name == 'nt' else []
            if installed8: java8 = installed8[-1]
        if java8 and Path(java8).resolve() != java.resolve(): java_runtimes.append(Path(java8).resolve())
        signed_verifications = []
        for runner in java_runtimes:
            signed_verifications.append(common.run([runner,'-Xverify:all','-cp',work/'verify','AdninSignedBootstrapVerify',
                shim,signed_paths,work/'signed-probes',*common.order_helpers(helpers)],'Signed JNI helper bootstrap verification',30))
        for path in paths + [chat_path]:
            if common.sha(path.read_bytes()) != hashes[path.name]: raise ValueError('Java source changed during compatibility compilation; rerun')
        output.mkdir(parents=True, exist_ok=True)
        for stale in output.glob('Adnin*.class'):
            if stale.stem not in final: stale.unlink()
        for name,data in final.items(): (output / (name + '.class')).write_bytes(data)
        if args.write_used_mapping:
            args.write_used_mapping.parent.mkdir(parents=True,exist_ok=True)
            args.write_used_mapping.write_text(json.dumps(used2.subset(),indent=2)+'\n',encoding='utf-8')
        report = {'compiler':compiler,'classVersion':52,'mode':'vanilla-obfuscated-1.8.9-shared-source',
                  'compiledActiveClasses':list(ACTIVE_CLASSES),
                  'bootstrapOwners':list(BOOTSTRAP_OWNERS),'helperOrder':common.order_helpers(helpers),
                  'helpersRemappedBeforeEmbedding':True,'bootstrapDoesNotInitializeHelpers':True,
                  'bootstrapEmbeddedBytesVerified':list(BOOTSTRAP_OWNERS),'base64ChunkLimit':common.CHUNK_SIZE,
                  'helperProtectionDomain':'Minecraft class domain; privateLookupIn on Java9+, explicit ProtectionDomain on Java8',
                  'compatibilityTickOwner':'AdninGuiNewChat.run()V via Minecraft.addScheduledTask','nativeClientPumpRequired':False,
                  'compatibilityScheduler':{'daemonIntervalMs':50,'maxQueuedCallbacks':1,'runsInMenus':True,
                                            'startOwner':'AdninGuiNewChat.<init>',
                                            'stopOwner':'AdninGuiNewChat.adninStopClientPump()V',
                                            'unloadWaitsForRunningCallback':True,
                                            'queuedCallbacksBecomeInertAfterStop':True,'restartAfterStop':False},
                  'compatibilityHeartbeatNative':'AdninGuiNewChat.nativeClientTick()V',
                  'stagedInstallerAliases':['copyBfrFields','copyAvoFields'],
                  'stagedChatWrapperRepairs':{'i()V':'a()V','j(I)V':'c(I)V','k()I':'i()I'},
                  'retainedChatLegacyAliases':['i()V','j(I)V','k()I'],
                  'sourceSha256':hashes,'mappingSha256':common.sha(mapping_file.read_bytes()),
                  'sourcePaths':{p.name:p.relative_to(root).as_posix() for p in paths+[chat_path]},
                  'mappingProvenance':mapping_data.get('provenance',{}),
                  'runtimeJarSha256':common.sha(runtime.read_bytes()),'runtimeJar':str(runtime),
                  'helperSha256':{name:common.sha(data) for name,data in helpers.items()},
                  'classSha256':{name:common.sha(data) for name,data in final.items()},
                  'runtimeClasspath':classpath.split(os.pathsep),'runtimeLinkage':validation,
                  'verification':verification,'runtimeVerification':runtime_verification,
                  'signedBootstrapVerification':signed_verifications,'runtimeGameTested':False}
        (output / 'java-build-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        (output / 'runtime-classpath.txt').write_text(classpath+'\n',encoding='utf-8')
    print('Compiled '+str(len(ACTIVE_CLASSES))+' compatibility entrypoints and '+str(len(helpers))+' remapped helpers')
    print(verification)
    print(runtime_verification)
    for verified in signed_verifications: print(verified)
    print('Static game linkage: '+json.dumps(validation))
    print('Class files: '+str(output))

if __name__ == '__main__':
    try: main()
    except (ValueError,OSError,subprocess.TimeoutExpired) as error:
        print('Compatibility Java build failed: '+str(error),file=sys.stderr)
        raise SystemExit(1)
