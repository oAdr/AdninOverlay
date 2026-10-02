"""Build both reviewed Adnin runtimes into one verified C++ injector."""
import argparse, hashlib, importlib.util, json, os, re, struct, subprocess, sys, tempfile, zipfile
from pathlib import Path
import runtime_build
from classfile import read_class

ROOT = Path(__file__).resolve().parents[1]

def run(args, cwd=None):
    result = subprocess.run([str(x) for x in args], cwd=cwd, check=False)
    if result.returncode: raise RuntimeError('Build step failed: ' + str(args[0]))

def verify_forge_free(directory):
    """Reject accidental dependencies on the source mods or the Forge runtime."""
    checked = 0
    forbidden = (b'net/minecraftforge/', b'cpw/mods/fml/', b'keystrokesmod/')
    for path in sorted(directory.glob('Adnin*.class')):
        data = path.read_bytes()
        info = read_class(data)
        if info['major'] != 52 or any(prefix in data for prefix in forbidden):
            raise RuntimeError('Invalid Java 8 / Forge-free module dependency: ' + path.name)
        checked += 1
    if checked < 9:
        raise RuntimeError('Missing compiled runtime classes')
    return checked

def run_java_tests(jdk, out):
    """Offline/loopback tests; add installed transitive jars for no-init linkage."""
    cp=(out/'java-runtime/runtime-classpath.txt').read_text(encoding='utf8').strip()
    additions=[]
    for relative in ('org/apache/logging/log4j/log4j-api', 'it/unimi/dsi/fastutil'):
        candidates=[]
        for minecraft in (Path.home()/'AppData/Roaming/.minecraft', Path.home()/'.minecraft'):
            candidates.extend((minecraft/'libraries'/relative).glob('*/*.jar'))
        if candidates:
            # Installed versions vary across machines. Prefer the newest numeric
            # release without requiring obsolete 1.8.9 metadata versions.
            candidates.sort(key=lambda item: (tuple(int(v) for v in re.findall(r'\d+', item.parent.name)), item.name))
            selected=str(candidates[-1].resolve())
            if selected not in cp.split(os.pathsep): additions.append(selected)
    test_cp=os.pathsep.join([str(out/'java-runtime'), cp, *additions])
    testout=out/'test-java'; testout.mkdir(exist_ok=True)
    names=('AdninLanguageTest','AdninMessagesTest','AdninScaffoldPortTest','AdninEaglePortTest','AdninApiTest','AdninOutputTest','AdninMetricsTest','AdninColumnOrderTest','AdninColumnSettingsTest','AdninUrchinCacheTest','AdninUrchinWorkerTest','AdninFeaturePolicyTest','AdninFeaturePresentationTest','AdninTabEligibilityTest','AdninFeatureLinkageTest','AdninBootstrapVerify',
           'AdninAnticheatCoreTest','AdninAnticheatSettingsTest','AdninClientSoundsTest','AdninPacketLogConcurrencyTest','AdninPacketLogAccessorTest','AdninPartyQueueQueryTest','AdninPartyQueueScopeTest','AdninModuleIntegrationTest','AdninUiInteractionTest','AdninReplayTest','AdninReplayProfilesTest','AdninReplayApiTest','AdninOutputCategoriesTest','AdninBotCacheTest','AdninResourceLifecycleTest','AdninSharedConfigTest')
    sources=[ROOT/'tests/java'/(name+'.java') for name in names]
    compiler=subprocess.run([str(jdk/'bin/javac.exe'),'-J-Duser.language=en','-J-Dfile.encoding=UTF-8','--release','8','-encoding','UTF-8','-proc:none',
                             '-cp',test_cp,'-d',str(testout),*[str(source) for source in sources]],
                            capture_output=True,text=True,encoding='utf8',errors='replace',timeout=90)
    if compiler.returncode:
        raise RuntimeError('Java test compilation failed:\n'+(compiler.stdout+compiler.stderr)[-10000:])
    report=json.loads((out/'java-runtime/java-build-report.json').read_text(encoding='utf8'))
    invocations=[('AdninLanguageTest',[]),('AdninMessagesTest',[]),('AdninScaffoldPortTest',[]),('AdninEaglePortTest',[]),('AdninApiTest',[]),('AdninOutputTest',[]),('AdninMetricsTest',[ROOT/'src/java']),('AdninUrchinCacheTest',[]),('AdninUrchinWorkerTest',[]),
                 ('AdninColumnOrderTest',[]),('AdninColumnSettingsTest',[]),
                 ('AdninFeaturePolicyTest',[ROOT/'src/java',out/'java-runtime']),('AdninFeaturePresentationTest',[]),
                 ('AdninTabEligibilityTest',[ROOT/'src/java']),
                 ('AdninFeatureLinkageTest',[out/'java-runtime/AdninFeatures.class']),
                 ('AdninFeatureLinkageTest',[out/'java-runtime/AdninAnticheat.class']),
                 ('AdninFeatureLinkageTest',[out/'java-runtime/AdninClientSounds.class']),
                 ('AdninAnticheatCoreTest',[]),('AdninAnticheatSettingsTest',[]),
                 ('AdninClientSoundsTest',[]),('AdninPacketLogConcurrencyTest',[]),('AdninPacketLogAccessorTest',[]),('AdninPartyQueueQueryTest',[]),('AdninPartyQueueScopeTest',[]),('AdninModuleIntegrationTest',[]),('AdninUiInteractionTest',[]),('AdninReplayTest',[]),('AdninReplayProfilesTest',[]),('AdninReplayApiTest',[]),('AdninOutputCategoriesTest',[]),
                 ('AdninBotCacheTest',[]),('AdninResourceLifecycleTest',[]),('AdninSharedConfigTest',[]),('AdninBootstrapVerify',[out/'java-runtime',*report['helperOrder']])]
    transcript=['Java tests use offline fixtures and a loopback HTTP server; no game messages or real API keys.',
                'Linkage uses locally installed log4j-api and fastutil when present.']
    for name, extra in invocations:
        heap_args=['-Xmx48m'] if name == 'AdninResourceLifecycleTest' else []
        command=[jdk/'bin/java.exe',*heap_args,'-Duser.language=en','-Dfile.encoding=UTF-8','-Dadnin.language.shared=false','-Dadnin.config.shared=false','-Xverify:all','-cp',str(testout)+os.pathsep+test_cp,name,*extra]
        result=subprocess.run([str(value) for value in command],capture_output=True,text=True,
                              encoding='utf8',errors='replace',timeout=60)
        diagnostic=(result.stdout+result.stderr).strip()
        # Owned tests use fixed dummy credentials and never inspect user config.
        transcript.append(diagnostic)
        print(diagnostic)
        if result.returncode:
            (ROOT/'evidence/java-tests.txt').write_text('\n'.join(transcript)+'\nFAILED: '+name+'\n',encoding='utf8')
            raise RuntimeError('Java test failed: '+name)
    transcript.append('All Java tests passed. Live Minecraft behavior is verified separately.')
    compat_report=json.loads((out/'java-vanilla/java-build-report.json').read_text(encoding='utf8'))
    # The test entrypoint is unsigned in Minecraft's default package. Use an
    # owned signature-free copy solely for this no-game fixture; the separate
    # real signed-loader bootstrap checks still use the original signed JAR.
    with tempfile.TemporaryDirectory(prefix='adnin-config-runtime-') as directory:
        fixture_jar=Path(directory)/'owned-runtime.jar'
        with zipfile.ZipFile(compat_report['runtimeJar']) as original, zipfile.ZipFile(fixture_jar,'w') as fixture:
            for item in original.infolist():
                if not item.filename.upper().startswith('META-INF/'):
                    fixture.writestr(item,original.read(item))
        compat_cp=os.pathsep.join([str(testout),str(out/'java-vanilla'),str(fixture_jar),*compat_report['runtimeClasspath'],*additions])
        result=subprocess.run([str(jdk/'bin/java.exe'),'-Duser.language=en','-Dfile.encoding=UTF-8',
            '-Dadnin.language.shared=false','-Dadnin.config.shared=false','-Xverify:all','-cp',compat_cp,'AdninSharedConfigTest'],
            capture_output=True,text=True,encoding='utf8',errors='replace',timeout=60)
    transcript.append('Badlion/Vanilla shared profile: '+(result.stdout+result.stderr).strip())
    print(transcript[-1])
    if result.returncode: raise RuntimeError('Compatibility shared-config test failed')
    (ROOT/'evidence/java-tests.txt').write_text('\n'.join(transcript)+'\n',encoding='utf8')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for tool in ('nasm','jdk','toolchain','cmake','ninja'): p.add_argument('--'+tool, required=True, type=Path)
    p.add_argument('--build', type=Path, default=ROOT/'build')
    p.add_argument('--classpath')
    p.add_argument('--vanilla-jar', type=Path)
    args=p.parse_args(); out=args.build.resolve()
    source_inputs = runtime_build.source_hashes(ROOT)
    out.mkdir(parents=True,exist_ok=True); (out/'bin').mkdir(exist_ok=True); (out/'generated').mkdir(exist_ok=True)
    os.environ['PATH']=str(args.toolchain/'bin')+os.pathsep+os.environ['PATH']
    fixed=out/'native-fixed.dll'; intermediate=out/'adnin-java.dll'; final=out/'bin/Adnin.dll'
    run([args.nasm,'-f','bin','-Ox',ROOT/'src/native/ChatReaderLunar.asm','-o',fixed])
    command=[sys.executable,ROOT/'scripts/build-java.py','--jdk',args.jdk,'--output',out/'java-runtime']
    if args.classpath: command+=['--classpath',args.classpath]
    run(command)
    import reembed
    records=json.loads((ROOT/'evidence/original-class-index.json').read_text())
    classes={name.replace('Frenchify','Adnin'):(out/'java-runtime'/(name.replace('Frenchify','Adnin')+'.class')).read_bytes() for name in reembed.SITES}
    binary,report=reembed.rebuild(fixed.read_bytes(),classes,records)
    intermediate.write_bytes(binary)
    (out/'reembedding.json').write_text(json.dumps(report,indent=2),encoding='utf8')
    run([sys.executable,ROOT/'scripts/bridge.py','--input',intermediate,'--output',final,'--report',out/'bridge.json',
         '--header',out/'generated/adnin-image.h','--nasm',args.nasm])
    lunar_bridge = json.loads((out/'bridge.json').read_text(encoding='utf8'))
    compat_fixed = out/'native-vanilla.dll'
    compat_final = out/'bin/AdninVanilla.dll'
    run([args.nasm,'-f','bin','-Ox',ROOT/'src/native/ChatReaderVanilla.asm','-o',compat_fixed])
    compat_command = [sys.executable,ROOT/'scripts/build-java-compat.py','--jdk',args.jdk,'--output',out/'java-vanilla']
    if args.classpath: compat_command += ['--classpath',args.classpath]
    if args.vanilla_jar: compat_command += ['--runtime-jar',args.vanilla_jar]
    run(compat_command)
    import native_compat
    compat_profile = native_compat.profile()
    compat_records = json.loads((ROOT/'evidence/vanilla-class-index.json').read_text(encoding='utf8'))
    compat_classes = {reembed.renamed(name):(out/'java-vanilla'/(reembed.renamed(name)+'.class')).read_bytes()
                      for name in compat_profile['sites']}
    compat_intermediate, compat_reembedding = reembed.rebuild(compat_fixed.read_bytes(),compat_classes,compat_records,compat_profile)
    (out/'adnin-java-vanilla.dll').write_bytes(compat_intermediate)
    (out/'vanilla-reembedding.json').write_text(json.dumps(compat_reembedding,indent=2),encoding='utf8')
    compat_binary, compat_bridge = native_compat.build_bridge(compat_intermediate,args.nasm)
    compat_final.write_bytes(compat_binary)
    (out/'vanilla-bridge.json').write_text(json.dumps(compat_bridge,indent=2),encoding='utf8')
    forge_command=[sys.executable,ROOT/'scripts/build-java-forge.py','--jdk',args.jdk,'--output',out/'java-forge']
    if args.classpath: forge_command+=['--classpath',args.classpath]
    if args.vanilla_jar: forge_command+=['--runtime-jar',args.vanilla_jar]
    run(forge_command)
    import native_forge
    from java_compat import ClassPath, Mapping, remap_class
    import importlib.util
    forge_spec=importlib.util.spec_from_file_location('adnin_forge_build',ROOT/'scripts/build-java-forge.py')
    forge_java=importlib.util.module_from_spec(forge_spec);forge_spec.loader.exec_module(forge_java)
    forge_resource=json.loads((ROOT/'resources/java-forge-1.8.9.json').read_text(encoding='utf8'))
    forge_java_report=json.loads((out/'java-forge/java-build-report.json').read_text(encoding='utf8'))
    forge_cp=ClassPath([forge_java_report['runtimeJar']])
    def forge_abi(data):
        name=read_class(data)['name']
        forge_cp.classes[name]=data
        forge_cp.cache.pop(name,None)
        mapping=forge_java.obfuscated_mapping(forge_resource)
        if name == 'FrenchifyIngameGui':
            if read_class(data)['parent'] != 'avo': raise ValueError('Original HUD superclass changed')
            mapping['classes']=dict(mapping['classes'],avo=forge_java.FORGE_GUI)
        return remap_class(data,Mapping(mapping,forge_cp))
    forge_profile=native_compat.profile()
    forge_profile['abi_transform']=forge_abi
    forge_profile['allowed_gui_widenings']=(('func_73864_a','(III)V'),('func_73869_a','(CI)V'))
    forge_classes={reembed.renamed(name):(out/'java-forge'/(reembed.renamed(name)+'.class')).read_bytes()
                   for name in forge_profile['sites']}
    try:
        forge_intermediate,forge_reembedding=reembed.rebuild(compat_fixed.read_bytes(),forge_classes,compat_records,forge_profile)
    finally: forge_cp.close()
    (out/'forge-reembedding.json').write_text(json.dumps(forge_reembedding,indent=2),encoding='utf8')
    forge_base,forge_bridge=native_compat.build_bridge(forge_intermediate,args.nasm)
    run([args.jdk/'bin/javac.exe','--release','8','-d',out/'forge-mapper',ROOT/'src/java-forge/RuntimeMappings.java'])
    forge_binary,forge_jni=native_forge.build(forge_base,out/'forge-native',args.toolchain,args.jdk,
                                           out/'forge-mapper/adnin/forge/RuntimeMappings.class')
    forge_final=out/'bin/AdninForge.dll';forge_final.write_bytes(forge_binary)
    forge_bridge['forgeJni']=forge_jni
    forge_bridge['imageSize']=forge_jni['imageSize']
    forge_bridge['runtime_metadata']['imageSize']=forge_jni['imageSize']
    (out/'forge-bridge.json').write_text(json.dumps(forge_bridge,indent=2),encoding='utf8')
    if not all(report.get('legacyAnticheat', {}).get('disabled') for report in (lunar_bridge, compat_bridge)):
        raise RuntimeError('The old AntiCheat must be disabled in both native profiles')
    if not all(report.get('gameTickHook', {}).get('independentOfSessionStats') for report in (lunar_bridge, compat_bridge)):
        raise RuntimeError('Both profiles must install the fixed-phase tick hook independently of Session Stats')
    if not all(len(report.get('replayOverlay', [])) >= 4
               and all(item.get('nativeGameStateUnchanged') for item in report['replayOverlay'])
               for report in (lunar_bridge, compat_bridge)):
        raise RuntimeError('Replay must cover both render paths and model/enabled gates without changing game state')
    if not lunar_bridge.get('lunarUnloadGuard') or not compat_bridge.get('unloadGuard'):
        raise RuntimeError('Both profiles require a Java stop barrier before native unload')
    if not all(report.get('sharedConfiguration',{}).get('originalWriterRetired')
               and not report['sharedConfiguration']['synchronousDiskWrites'] for report in (lunar_bridge,compat_bridge)):
        raise RuntimeError('Both profiles must use the shared background configuration writer')
    for profile_report in (lunar_bridge, compat_bridge):
        skin_policy = profile_report.get('skinDenickerPolicy', {})
        if not (skin_policy.get('originalSkinResolutionRetired')
                and skin_policy.get('nativeSettingPreserved')
                and skin_policy.get('numberPriorityPreserved')
                and skin_policy.get('actorIdentityPreserved')
                and len(skin_policy.get('patches', [])) == 6):
            raise RuntimeError('Both profiles require the reviewed Mellow Skin replacement policy')
    forge_free_classes = {kind:verify_forge_free(out/directory)
                          for kind,directory in (('lunar','java-runtime'),('vanilla','java-vanilla'))}
    runtimes = [runtime_build.descriptor(final,'lunar',101,runtime_build.lunar_metadata(lunar_bridge)),
                runtime_build.descriptor(compat_final,'vanilla',102,compat_bridge['runtime_metadata']),
                runtime_build.descriptor(forge_final,'forge',103,forge_bridge['runtime_metadata'])]
    (out/'generated/adnin-runtimes.h').write_text(runtime_build.generated_header(runtimes),encoding='ascii')
    icon_path = ROOT/'src/injector/assets/adnin.ico'
    icon_data = icon_path.read_bytes()
    icon_header = struct.unpack_from('<HHH', icon_data)
    if icon_header != (0, 1, 7):
        raise RuntimeError('The application icon must contain the seven reviewed sizes')
    (out/'generated/adnin-payload.rc').write_text(
        '101 RCDATA "'+final.as_posix()+'"\n102 RCDATA "'+compat_final.as_posix()+'"\n103 RCDATA "'+forge_final.as_posix()+'"\n201 ICON "'+icon_path.as_posix()+'"\n'
        '202 RCDATA "'+(ROOT/'resources/THIRD_PARTY_NOTICES.txt').as_posix()+'"\n', encoding='utf8')
    run([args.cmake,'-S',ROOT,'-B',out,'-G','Ninja','-DCMAKE_MAKE_PROGRAM='+args.ninja.as_posix(),
         '-DCMAKE_CXX_COMPILER='+(args.toolchain/'bin/clang++.exe').as_posix(),
         '-DCMAKE_RC_COMPILER='+(args.toolchain/'bin/x86_64-w64-mingw32-windres.exe').as_posix(),
         '-DPython3_EXECUTABLE='+Path(sys.executable).as_posix(),
         '-DADNIN_TEST_WORK_DIRECTORY='+(ROOT.parents[1]/'work/adnin-injector-tests').as_posix(),
         '-DCMAKE_BUILD_TYPE=Release'])
    run([args.cmake,'--build',out])
    import pefile
    executable = out/'bin/Adnin.exe'
    with pefile.PE(str(executable)) as image:
        if image.OPTIONAL_HEADER.Subsystem != 2:
            raise RuntimeError('Standalone EXE must use the Windows GUI subsystem')
        runtime_build.verify_embedded(image,runtimes,out/'bin')
        def resources(kind_id, resource_id):
            values = []
            for kind in image.DIRECTORY_ENTRY_RESOURCE.entries:
                if kind.id != kind_id: continue
                for resource in kind.directory.entries:
                    if resource.id != resource_id: continue
                    for language in resource.directory.entries:
                        entry = language.data.struct
                        values.append(image.get_data(entry.OffsetToData, entry.Size))
            return values
        groups = resources(14, 201)
        if resources(10, 202) != [(ROOT/'resources/THIRD_PARTY_NOTICES.txt').read_bytes()]:
            raise RuntimeError('The standalone executable must retain the exact third-party notices')
        if len(groups) != 1 or struct.unpack_from('<HHH', groups[0]) != icon_header:
            raise RuntimeError('The application icon group is missing or incomplete')
        icon_sizes = []
        for index in range(icon_header[2]):
            frame = struct.unpack_from('<BBBBHHII', icon_data, 6 + index*16)
            group_frame = struct.unpack_from('<BBBBHHIH', groups[0], 6 + index*14)
            if frame[:2] != group_frame[:2] or frame[6] != group_frame[6]:
                raise RuntimeError('An application icon frame differs from its source')
            if resources(3, group_frame[7]) != [icon_data[frame[7]:frame[7]+frame[6]]]:
                raise RuntimeError('The embedded icon pixels differ from the reviewed logo')
            icon_sizes.append([frame[0] or 256, frame[1] or 256])
        if set(map(tuple, icon_sizes)) != {(n,n) for n in (16,24,32,48,64,128,256)}:
            raise RuntimeError('The application icon size set is incomplete')
    run([args.cmake.parent/'ctest.exe','--test-dir',out,'--output-on-failure'])
    run_java_tests(args.jdk,out)
    run([sys.executable,ROOT/'tests/test_messages_render.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_headers.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_ui_lifecycle.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_game_tick.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_ui_input_lifecycle.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_ui_performance_equivalence.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_party_queue_adapter.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_urchin_scope.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_gray_output.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_match_teams.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_skin_denicker.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_replay_roster.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    run([sys.executable,ROOT/'tests/test_anticheat_adapter.py','--jdk',args.jdk,'--classes',out/'java-runtime'])
    packet_tests = [sys.executable,ROOT/'tests/test_party_packet.py','--jdk',args.jdk,
                    '--classes',out/'java-runtime','--report',out/'party-packet-tests.json']
    if args.vanilla_jar: packet_tests += ['--vanilla-jar',args.vanilla_jar]
    run(packet_tests)
    run([sys.executable,ROOT/'tests/test_reembed.py','--input',fixed])
    run([sys.executable,ROOT/'tests/test_bridge.py','--input',fixed,'--nasm',args.nasm])
    run([sys.executable,ROOT/'tests/test_denicker.py','--input',fixed,'--nasm',args.nasm])
    os.environ['ADNIN_TEST_JDK'] = str(args.jdk)
    run([sys.executable,ROOT/'tests/test_java_compat.py'])
    run([sys.executable,ROOT/'tests/test_native_compat.py','--input',compat_fixed,
         '--classes',out/'java-vanilla','--nasm',args.nasm])
    run([sys.executable,ROOT/'tests/test_native_tick_hook.py','--build',out])
    run([sys.executable,ROOT/'tests/test_package.py'])
    run([sys.executable,ROOT/'tests/test_forge_mapping.py'])
    run([sys.executable,ROOT/'tests/test_forge_bootstrap.py','--jdk',args.jdk])
    run([sys.executable,ROOT/'tests/test_forge_hud.py','--jdk',args.jdk,'--classes',out/'java-forge'])
    run([sys.executable,ROOT/'tests/test_forge_facade.py','--jdk',args.jdk,'--toolchain',args.toolchain,'--build',out])
    run([sys.executable,ROOT/'tests/test_native_forge.py','--build',out])
    artifacts=[]
    for file in (final,compat_final,forge_final,executable):
        artifacts.append(dict(file=file.name,bytes=file.stat().st_size,sha256=hashlib.sha256(file.read_bytes()).hexdigest()))
    if source_inputs != runtime_build.source_hashes(ROOT):
        raise RuntimeError('Production sources changed during the build; rerun for a coherent release')
    report=dict(formatVersion=2,nativeSource='Reviewed native image assembly with targeted Java bridges; not a complete C++ rewrite',nativeFunctionsPreserved=2493,
                injectorSource='C++20',featureSource='Java 8',gameRuntimeTested=False,allFeaturesTested=False,
                standaloneExecutable=True,embeddedPayloadVerified=True,iconEmbeddedVerified=True,
                sharedUserConfiguration='LOCALAPPDATA/Adnin/config.properties',legacyClientWritersRetired=True,
                automaticCrashDiagnostics=True,crashDiagnosticsMemoryDump=False,crashDiagnosticsUpload=False,
                forgeRequired=False,forgeUsesVanillaPayload=False,forgeSrgPayload=True,forgeProcessMarker='FMLTweaker',
                forgeLiveRuntimeTested=False,
                forgeFreeClassesChecked=forge_free_classes,legacyAnticheatDisabled=True,
                fixedPhaseGameTick=True,replayOverlayEnabled=True,javaStopBeforeNativeUnload=True,
                ravenSourceCommit='14b0a03e8b3af4f109d7c05bc5d0b98d42470179',thirdPartyNoticesEmbedded=True,
                mellowScaffoldSourceCommit='17ef9b7466754a33ee8c8ed87fa7ea717573d775',
                mellowEagleSourceCommit='17ef9b7466754a33ee8c8ed87fa7ea717573d775',
                mellowSkinSourceCommit='17ef9b7466754a33ee8c8ed87fa7ea717573d775',
                legacySkinResolutionRetired=True,skinUsesLocalTextureMetadata=True,
                interfaceLanguages=['en','zh_CN','zh_TW'],interfaceScalePercent=[70,140],
                iconSizes=icon_sizes,iconSha256=hashlib.sha256(icon_data).hexdigest(),artifacts=artifacts,
                runtimePayloads=runtimes,sourceInputsSha256=source_inputs,
                supportedClients=['Lunar 1.8.9','Badlion 1.8.9','Forge 1.8.9','Vanilla 1.8.9'])
    (out/'build-report.json').write_text(json.dumps(report,indent=2),encoding='utf8')
    print('Built standalone Adnin.exe with three verified runtime payloads; see '+str(out/'build-report.json'))

if __name__=='__main__':
    # Do not let a localized Windows pipe codec hide a test's actual result.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'): stream.reconfigure(errors='backslashreplace')
    main()
