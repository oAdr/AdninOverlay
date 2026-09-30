"""Exact production UI output/cache/geometry checks with owned GL counters."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from classfile import read_class
spec = importlib.util.spec_from_file_location('ui_perf_common', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

STATE = '''package org.lwjgl.opengl;
public final class UiGeometryFixture {
    public static final int[] values = new int[32768];
    public static int count, texture;
    public static void vertex(float x,float y) {
        if(count+2>values.length)count=0;
        values[count++]=Float.floatToIntBits(x);values[count++]=Float.floatToIntBits(y);
    }
}'''


def java_type(value):
    if value.startswith('L'):
        return value[1:-1].replace('/', '.')
    return {'I': 'int', 'F': 'float', 'Z': 'boolean', 'V': 'void'}[value]


def fixtures(bytecode):
    methods = {}
    for ref in read_class(bytecode)['references']:
        if not ref['owner'].startswith('org/lwjgl/opengl/'):
            continue
        name, desc = ref['name'], ref['descriptor']
        inputs, output = desc[1:].split(')')
        types = re.findall(r'L[^;]+;|[IFZ]', inputs)
        body = ''
        if name == 'glGetInteger':
            body = ('a1.put(0,0);a1.put(1,0);a1.put(2,800);a1.put(3,560);' if output == 'V'
                    else 'return a0==34018?2:a0==2976?5888:0;')
        elif name == 'glGenTextures':
            body = 'return ++UiGeometryFixture.texture;'
        elif name == 'glIsEnabled':
            body = 'return false;'
        elif name == 'glVertex2f':
            body = 'UiGeometryFixture.vertex(a0,a1);'
        elif output != 'V':
            raise ValueError('Unexpected renderer dependency: ' + repr(ref))
        params = ','.join(java_type(value) + ' a' + str(i) for i, value in enumerate(types))
        method = 'public static ' + java_type(output) + ' ' + name + '(' + params + '){' + body + '}'
        methods.setdefault(ref['owner'], []).append(method)
    result = {'org/lwjgl/opengl/UiGeometryFixture.java': STATE}
    for owner, declarations in methods.items():
        result[owner + '.java'] = ('package org.lwjgl.opengl; public final class '
                                  + owner.rsplit('/', 1)[1] + '{' + '\n'.join(declarations) + '}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--java8', type=Path)
    args = parser.parse_args()
    classes = args.classes.resolve()
    cp_file = classes / 'runtime-classpath.txt'
    dependencies = cp_file.read_text(encoding='utf-8').strip() if cp_file.is_file() else common.default_classpath(ROOT)
    candidates = []
    for minecraft in (Path.home() / 'AppData/Roaming/.minecraft', Path.home() / '.minecraft'):
        candidates.extend((minecraft / 'libraries/org/apache/logging/log4j/log4j-api').glob('*/*.jar'))
    if candidates:
        candidates.sort(key=lambda item: tuple(int(v) for v in re.findall(r'\d+', item.parent.name)))
        dependencies += os.pathsep + str(candidates[-1].resolve())
    runtime = str(classes) + os.pathsep + dependencies
    with tempfile.TemporaryDirectory(prefix='adnin-ui-equivalence-') as directory:
        work = Path(directory)
        sources = [ROOT / 'tests/java/AdninUiPerformanceEquivalenceTest.java']
        for name, content in fixtures((classes / 'AdninUi.class').read_bytes()).items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        output = work / 'classes'
        common.compile_sources(common.find_java(args.jdk, 'javac'), sources, runtime, output, work / 'args.txt')
        runtimes = [common.find_java(args.jdk, 'java')]
        if args.java8:
            runtimes.append(args.java8)
        for java in runtimes:
            print(common.run([java, '-Xverify:all', '-Xmx256m', '-Djava.awt.headless=true',
                              '-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory',
                              '-cp', str(output) + os.pathsep + runtime,
                              'AdninUiPerformanceEquivalenceTest'], 'UI performance equivalence', timeout=90))


if __name__ == '__main__':
    main()
