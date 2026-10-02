"""Build a distinct Java 8 named-class/SRG runtime for Forge 1.8.9."""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

from classfile import read_class, rewrite_utf8
from java_compat import ClassPath, Mapping, remap_class, validate_runtime

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('adnin_forge_compat',ROOT/'scripts/build-java-compat.py')
compat = importlib.util.module_from_spec(spec); spec.loader.exec_module(compat)
common = compat.common
FORGE_GUI = 'net/minecraftforge/client/GuiIngameForge'
FORGE_GUI_STUB = '''package net.minecraftforge.client;
public class GuiIngameForge extends net.minecraft.client.gui.GuiIngame {
    public GuiIngameForge(net.minecraft.client.Minecraft minecraft) { super(minecraft); }
}
'''

def forge_source(path):
    text = compat.compatibility_source(path)
    edits = ()
    if path.stem == 'AdninIngameGui':
        edits = (('extends GuiIngame {', 'extends net.minecraftforge.client.GuiIngameForge {'),
                 ('if (forgeGui) originalGui.updateTick();\n        else super.updateTick();', 'super.updateTick();'),
                 ('if (forgeGui) originalGui.renderGameOverlay(f);\n        else super.renderGameOverlay(f);', 'super.renderGameOverlay(f);'))
    elif path.stem == 'AdninSessionHudInstaller':
        edits = (('Class clazz = GuiIngame.class;', 'Class clazz = guiIngame.getClass();'),
                 ('if (Modifier.isStatic(field.getModifiers())) continue;',
                  'if (Modifier.isStatic(field.getModifiers()) || !clazz.isInstance(guiIngame2)) continue;'))
    for before, after in edits:
        if text.count(before) != 1: raise ValueError('Unexpected Forge HUD source: '+path.name)
        text = text.replace(before, after)
    return text

def inject_forge_bootstrap(text, owner, helpers):
    # Named Forge game classes cannot anchor default-package Lookup.defineClass.
    return compat.inject_compatibility_bootstrap(text,owner,helpers,owner)

def obfuscated_mapping(resource):
    native = resource['nativeMapping']
    return {'classes':native['classes'], 'fields':[r[:3]+[r[4]] for r in native['fields']],
            'methods':[r[:3]+[r[4]] for r in native['methods']], 'provenance':resource['provenance']}

def remap_all(classes,data,classpath):
    source = ClassPath(classpath.split(os.pathsep),classes)
    try:
        mapping=Mapping(data,source)
        return {name:remap_class(value,mapping) for name,value in classes.items()},mapping
    finally: source.close()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk',required=True)
    parser.add_argument('--classpath')
    parser.add_argument('--runtime-jar',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    output=args.output.resolve(); output.mkdir(parents=True,exist_ok=True)
    runtime=(args.runtime_jar or compat.runtime_default()).resolve()
    resource=json.loads((ROOT/'resources/java-forge-1.8.9.json').read_text(encoding='utf8'))
    if common.sha(runtime.read_bytes()) != resource['provenance']['vanillaJarSha256']:
        raise ValueError('Forge mapping requires the matching Minecraft 1.8.9 jar')
    javac,java=common.find_java(args.jdk,'javac'),common.find_java(args.jdk,'java')
    cp=args.classpath or common.default_classpath(ROOT)
    paths=sorted(p for p in (ROOT/'src/java').glob('*.java') if p.stem not in ('AdninClientPump','AdninGuiNewChat'))
    paths.extend(sorted((ROOT/'src/java-compat').glob('*.java')))
    chat_path=ROOT/'src/java/AdninGuiNewChat.java'
    hashes={p.name:common.sha(p.read_bytes()) for p in paths+[chat_path]}
    sources={p.name:forge_source(p) for p in paths}
    with tempfile.TemporaryDirectory(prefix='adnin-java-forge-',dir=output.parent) as directory:
        work=Path(directory); stage=work/'sources';stage.mkdir()
        # Only compile/link against this owned signature stub; never embed it or
        # replace Forge's transformed class, which owns mod interfaces and state.
        stub_source=work/'GuiIngameForge.java';stub_source.write_text(FORGE_GUI_STUB,encoding='ascii')
        common.compile_sources(javac,[stub_source],cp,work/'forge-stub',work/'stub.args')
        stub=(work/'forge-stub'/(FORGE_GUI+'.class')).read_bytes()
        forge_cp=os.pathsep.join([str(work/'forge-stub'),cp])
        for name,text in sources.items(): (stage/name).write_text(text,encoding='utf8')
        common.compile_sources(javac,sorted(stage.glob('*.java')),forge_cp,work/'named1',work/'first.args')
        named=common.class_files(work/'named1')
        first,used=remap_all(named,resource,forge_cp)
        helpers={name:data for name,data in first.items() if name not in compat.ACTIVE_CLASSES}
        for name,text in sources.items():
            if Path(name).stem in compat.BOOTSTRAP_OWNERS:
                owner=Path(name).stem
                text=inject_forge_bootstrap(text,owner,helpers)
            (stage/name).write_text(text,encoding='utf8')
        common.compile_sources(javac,sorted(stage.glob('*.java')),forge_cp,work/'named2',work/'second.args')
        final,used=remap_all(common.class_files(work/'named2'),resource,forge_cp)
        if any(final[name]!=data for name,data in helpers.items()): raise ValueError('Forge helper changed between passes')
        # The recovered NewChat source is already in the vanilla namespace.
        # Compile there, with final SRG helper bytes, then remap its structural
        # types/overrides. Base64 literals deliberately remain unchanged.
        vanilla_helpers=work/'vanilla-helpers';vanilla_helpers.mkdir()
        vanilla_resource=json.loads((ROOT/'resources/java-compat-1.8.9.json').read_text(encoding='utf8'))
        vanilla,_=remap_all(named,vanilla_resource,forge_cp)
        for name,data in vanilla.items(): (vanilla_helpers/(name+'.class')).write_bytes(data)
        chat=inject_forge_bootstrap(compat.compatibility_chat_source(chat_path),'AdninGuiNewChat',helpers)
        chat_source=work/'AdninGuiNewChat.java';chat_source.write_text(chat,encoding='utf8')
        common.compile_sources(javac,[chat_source],os.pathsep.join([str(runtime),str(vanilla_helpers),cp]),work/'chat',work/'chat.args')
        chat_classes=common.class_files(work/'chat')
        alias_cp=ClassPath([runtime],chat_classes)
        try:
            alias=Mapping({'classes':{},'fields':[], 'methods':[['AdninGuiNewChat','adninLegacyClearChat','()V','i']]},alias_cp)
            chat_classes['AdninGuiNewChat']=remap_class(chat_classes['AdninGuiNewChat'],alias)
        finally: alias_cp.close()
        chat_final,_=remap_all(chat_classes,obfuscated_mapping(resource),str(runtime))
        final.update(chat_final)
        if set(compat.ACTIVE_CLASSES)-set(final): raise ValueError('Missing Forge native entrypoint')
        # Model Forge's deobfuscation transform for static/JVM linkage only.
        # The actual game's classes remain owned by LaunchClassLoader at runtime.
        transformed=work/'forge-srg-fixture.jar'
        original_cp=ClassPath([runtime]); fixture_mapping=obfuscated_mapping(resource)
        fixture_mapping['remapDeclaredFields']=True
        native_map=Mapping(fixture_mapping,original_cp)
        try:
            with zipfile.ZipFile(runtime) as archive,zipfile.ZipFile(transformed,'w',zipfile.ZIP_DEFLATED) as target:
                for name in archive.namelist():
                    if not name.endswith('.class'): continue
                    data=remap_class(archive.read(name),native_map)
                    target.writestr(read_class(data)['name']+'.class',data)
                stub_mapped,_=remap_all({FORGE_GUI:stub},resource,forge_cp)
                target.writestr(FORGE_GUI+'.class',stub_mapped[FORGE_GUI])
        finally: original_cp.close()
        if FORGE_GUI in final or read_class(final['AdninIngameGui'])['parent'] != FORGE_GUI:
            raise ValueError('Forge HUD must inherit the real game-owned GuiIngameForge')
        validation_resource=dict(resource,classes=dict(resource['classes'],**{FORGE_GUI:FORGE_GUI}))
        validation_cp=ClassPath(forge_cp.split(os.pathsep))
        try:
            validation=validate_runtime(final,transformed,Mapping(validation_resource,validation_cp),allow_named=True)
        finally: validation_cp.close()
        for name,data in final.items(): (output/(name+'.class')).write_bytes(data)
        fixtures=ROOT/'tests/java-compat'
        common.compile_sources(javac,[fixtures/'AdninCompatLinkVerify.java'],str(fixtures),work/'verify',work/'verify.args')
        runtime_paths=work/'paths.txt'
        runtime_paths.write_text('\n'.join([str(output),str(transformed),*cp.split(os.pathsep)])+'\n',encoding='utf8')
        verified=common.run([java,'-Xverify:all','-cp',work/'verify','AdninCompatLinkVerify',runtime_paths,*sorted(final)],
                'Forge SRG no-initialization JVM verification',45)
        for owner in compat.BOOTSTRAP_OWNERS:
            constants=compat.constant_utf8(final[owner])
            for name,data in helpers.items():
                encoded=base64.b64encode(data)
                if any(encoded[i:i+common.CHUNK_SIZE] not in constants for i in range(0,len(encoded),common.CHUNK_SIZE)):
                    raise ValueError('Forge helper bootstrap differs: '+owner+'/'+name)
        for path in paths+[chat_path]:
            if common.sha(path.read_bytes())!=hashes[path.name]: raise ValueError('Forge source changed during build')
        report={'compiler':common.run([javac,'-version'],'JDK version',10),'classVersion':52,
            'mode':'forge-named-srg-1.8.9-shared-source','compiledActiveClasses':list(compat.ACTIVE_CLASSES),
            'bootstrapOwners':list(compat.BOOTSTRAP_OWNERS),'helperOrder':common.order_helpers(helpers),
            'sourceSha256':hashes,'sourcePaths':{p.name:p.relative_to(ROOT).as_posix() for p in paths+[chat_path]},
            'mappingSha256':common.sha((ROOT/'resources/java-forge-1.8.9.json').read_bytes()),'base64ChunkLimit':common.CHUNK_SIZE,
            'helperSha256':{n:common.sha(d) for n,d in helpers.items()},
            'classSha256':{n:common.sha(d) for n,d in final.items()},'runtimeJarSha256':common.sha(runtime.read_bytes()),
            'runtimeClasspath':cp.split(os.pathsep),'runtimeJar':str(runtime),'runtimeLinkage':validation,
            'runtimeVerification':verified,'helpersRemappedBeforeEmbedding':True,'runtimeGameTested':False,
            'hudSuperclass':FORGE_GUI,'forgeStubEmbedded':False,'preservesForgeHudState':True}
        (output/'java-build-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
    print('Compiled Forge SRG runtime: '+str(len(final))+' classes')
    print(verified)

if __name__=='__main__':
    try: main()
    except (ValueError,OSError,subprocess.TimeoutExpired) as failure:
        print('Forge Java build failed: '+str(failure),file=sys.stderr); raise SystemExit(1)
