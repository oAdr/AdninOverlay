"""Run production Gui4 bytecode against owned input/lifecycle environment fixtures."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('ui_input_common', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

FIXTURES = {
    'AdninUi.java': '''public final class AdninUi {
        public static final class RenderBoundary extends RuntimeException {}
        public static final RenderBoundary STOP = new RenderBoundary();
        public static int releaseFailure;
        public static void begin(int w,int h,float scale,float y,float alpha) { throw STOP; }
        public static void releaseTextures() {
            int failure=releaseFailure;releaseFailure=0;
            if(failure==1)throw new IllegalStateException("Owned GL cleanup fault");
            if(failure==2)throw new NoClassDefFoundError("Owned GL linkage fault");
        }
    }''',
    'AdninFeatures.java': '''public final class AdninFeatures {
        public static int saves;
        public static void ensureInitialized() {}
        public static void requestSave() { saves++; }
    }''',
    'org/lwjgl/input/Mouse.java': '''package org.lwjgl.input;
    public final class Mouse {
        public static boolean down=true;
        public static boolean isCreated() { return true; }
        public static boolean isButtonDown(int button) { return button==0&&down; }
    }''',
    'org/lwjgl/input/Keyboard.java': '''package org.lwjgl.input;
    public final class Keyboard {
        public static final int KEY_NUMPADENTER=156;
        public static boolean repeat,created=true,linkageFailure;
        public static int setters;
        public static String failure="";
        public static void reset(boolean state) {
            repeat=state;created=true;linkageFailure=false;setters=0;failure="";
        }
        private static void fail(String operation) {
            if(!failure.equals(operation))return;
            if(linkageFailure)throw new NoClassDefFoundError("Owned input linkage fault");
            throw new IllegalStateException("Owned input device fault");
        }
        public static boolean isCreated() { fail("created");return created; }
        public static boolean isKeyDown(int key) { return false; }
        public static void enableRepeatEvents(boolean enabled) { fail("write");setters++;repeat=enabled; }
        public static boolean areRepeatEventsEnabled() { fail("read");return repeat; }
    }''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--java8', type=Path)
    args = parser.parse_args()
    classes = args.classes.resolve()
    cp_file = classes / 'runtime-classpath.txt'
    dependencies = cp_file.read_text(encoding='utf-8').strip() if cp_file.is_file() else common.default_classpath(ROOT)
    extra = []
    for minecraft in (Path.home() / 'AppData/Roaming/.minecraft', Path.home() / '.minecraft'):
        extra.extend((minecraft / 'libraries/org/apache/logging/log4j/log4j-api').glob('*/*.jar'))
    if extra:
        extra.sort(key=lambda item: tuple(int(v) for v in re.findall(r'\d+', item.parent.name)))
        dependencies += os.pathsep + str(extra[-1].resolve())
    runtime = str(classes) + os.pathsep + dependencies
    with tempfile.TemporaryDirectory(prefix='adnin-ui-input-') as directory:
        work = Path(directory)
        sources = [ROOT / 'tests/java/AdninUiInputLifecycleTest.java']
        for name, content in FIXTURES.items():
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
            print(common.run([java, '-Xverify:all', '-Djava.awt.headless=true',
                              '-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory',
                              '-cp', str(output) + os.pathsep + runtime,
                              'AdninUiInputLifecycleTest'], 'UI input lifecycle test', timeout=60))


if __name__ == '__main__':
    main()
