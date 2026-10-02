"""Execute owned JNI facade fixtures without attaching to a game."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

def final_image_header(build, work):
    import pefile
    image=pefile.PE(data=(build/'bin/AdninForge.dll').read_bytes())
    facade=pefile.PE(data=(build/'forge-native/forge-facade.dll').read_bytes())
    report=json.loads((build/'forge-bridge.json').read_text(encoding='utf8'))['forgeJni']
    code=image.get_section_by_rva(report['facadeCodeRva'])
    delta=report['facadeCodeRva']-0x1000
    exports={item.name.decode():item.address+delta for item in facade.DIRECTORY_ENTRY_EXPORT.symbols}
    relocations=[item.rva for block in image.DIRECTORY_ENTRY_BASERELOC for item in block.entries
                 if item.type==10 and code.VirtualAddress<=item.rva<code.VirtualAddress+code.Misc_VirtualSize]
    header=['#define FORGE_IMAGE_SIZE '+str(image.OPTIONAL_HEADER.SizeOfImage),
            '#define FORGE_CODE_RVA '+str(code.VirtualAddress),
            '#define FORGE_PREFERRED_BASE '+hex(image.OPTIONAL_HEADER.ImageBase)+'ULL',
            'static const unsigned char FORGE_CODE[] = {'+','.join(str(x) for x in image.get_data(code.VirtualAddress,code.Misc_VirtualSize))+'};',
            'static const unsigned long FORGE_RELOCATIONS[] = {'+','.join(str(x) for x in relocations)+'};']
    for name,rva in exports.items():
        short=name.removeprefix('adnin_forge_')
        header.append('#define FORGE_'+('FIND' if short=='find' else short)+'_RVA '+str(rva))
    (work/'forge-final-image.h').write_text('\n'.join(header)+'\n',encoding='ascii')

ROOT=Path(__file__).resolve().parents[1]
def run(command):
    result=subprocess.run([str(x) for x in command],capture_output=True,text=True,timeout=45)
    if result.returncode: raise RuntimeError(result.stdout+result.stderr)
    return result.stdout.strip()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk',type=Path,required=True)
    parser.add_argument('--toolchain',type=Path,required=True)
    parser.add_argument('--build',type=Path)
    args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='adnin-forge-facade-') as directory:
        work=Path(directory);fixtures=ROOT/'tests/java-forge'
        run([args.jdk/'bin/javac.exe','--release','8','-d',work/'system',fixtures/'ForgeFacadeVerify.java',
             fixtures/'net/minecraft/launchwrapper/Launch.java',fixtures/'net/minecraft/launchwrapper/LaunchClassLoader.java',
             ROOT/'src/java-forge/RuntimeMappings.java'])
        run([args.jdk/'bin/javac.exe','--release','8','-d',work/'game',*sorted((fixtures/'net/minecraft/fixture').glob('*.java')),
             fixtures/'net/minecraftforge/fml/common/asm/transformers/deobf/FMLDeobfuscatingRemapper.java'])
        bytecode=(work/'system/adnin/forge/RuntimeMappings.class').read_bytes()
        # The mapper is defined by the owned native code, not placed on the app classpath.
        (work/'system/adnin/forge/RuntimeMappings.class').unlink()
        (work/'forge-mapper-bytes.h').write_text('static const unsigned char ADNIN_FORGE_MAPPER[] = {'
            +','.join(str(b) for b in bytecode)+'};\n',encoding='ascii')
        modes=['fixture']
        if args.build:
            final_image_header(args.build,work)
            modes.append('final')
        runners=[args.jdk/'bin/java.exe']
        runners.extend(sorted(Path('C:/Program Files/Eclipse Adoptium').glob('jdk-8*/bin/java.exe'))[-1:])
        for mode in modes:
            dll=work/('forge-facade-'+mode+'.dll')
            command=[args.toolchain/'bin/clang++.exe','-shared','-static','-O2','-I'+str(work),'-I'+str(args.jdk/'include'),
                '-I'+str(args.jdk/'include/win32'),fixtures/'forge_facade_verify.cpp','-o',dll]
            if mode=='final': command.append('-DADNIN_FORGE_FINAL_IMAGE=1')
            run(command)
            for runner in runners:
                print(mode+': '+run([runner,'-Xverify:all','-cp',work/'system','ForgeFacadeVerify',dll,work/'game']))

if __name__=='__main__': main()
