"""Optional real OpenGL GUI regression. Requires a local LWJGL2 native directory.

Creates a hidden Pbuffer, not a game window. Does not initialize game modules,
read personal settings, attach to a process, access a network or send messages.
"""
import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('classes', 'jdk', 'natives', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    dependencies = (args.classes / 'runtime-classpath.txt').read_text(encoding='utf8').strip()
    # Lunar's shaded jars and upstream LWJGL declare different package sealing.
    # Put the complete upstream LWJGL2 package first for this isolated fixture.
    lwjgl = [path for path in dependencies.split(os.pathsep)
             if '/org/lwjgl/' in path.replace('\\', '/') and 'lwjgl-platform' not in path]
    extras = []
    for relative in ('org/apache/logging/log4j/log4j-api', 'it/unimi/dsi/fastutil'):
        candidates = sorted((Path.home() / 'AppData/Roaming/.minecraft/libraries' / relative).glob('*/*.jar'))
        extras.extend(str(path) for path in candidates[-1:])
    classpath = os.pathsep.join([str(args.classes), *lwjgl, dependencies, *extras])
    sources = [Path(__file__).parent / 'java' / name for name in
               ('AdninUiRenderTest.java', 'AdninUiRasterStateTest.java')]
    subprocess.run([str(args.jdk / 'bin/javac.exe'), '--release', '8', '-encoding', 'UTF-8', '-proc:none',
                    '-cp', classpath, '-d', str(args.output), *(str(source) for source in sources)], check=True)
    subprocess.run([str(args.jdk / 'bin/java.exe'), '-Xverify:all', '-Duser.language=en',
                    '-Djava.library.path=' + str(args.natives), '-cp',
                    str(args.output) + os.pathsep + classpath, 'AdninUiRenderTest', str(args.output)], check=True)


if __name__ == '__main__':
    main()
