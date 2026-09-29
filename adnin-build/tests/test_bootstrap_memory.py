"""Offline bootstrap allocation/classloader stress; no game or helper initialization."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


common = module(ROOT / 'scripts/build-java.py', 'bootstrap_memory_common')
compat = module(ROOT / 'scripts/build-java-compat.py', 'bootstrap_memory_compat')

VERIFIER = r'''
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

public final class BootstrapMemoryVerify {
    private static final String SENTINEL = "adnin.bootstrap.test.initialized";
    private static URL[] urls;
    private static String[] helpers;
    private static Path expected;
    private static long allocated;

    private static WeakReference<ClassLoader> cycle(boolean measure, int variant) throws Exception {
        URLClassLoader loader = new URLClassLoader(urls, null);
        try {
            if(variant%3==2) {
                final Throwable[] failed=new Throwable[2];
                final java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
                Thread[] workers=new Thread[2];
                for(int i=0;i<2;i++) {
                    final int slot=i;
                    workers[i]=new Thread(new Runnable() { public void run() {
                        try { start.await(); Class.forName("BootstrapMemoryOwner"+(slot==0?"A":"B"),true,loader); }
                        catch(Throwable error) { failed[slot]=error; }
                    }});
                    workers[i].setDaemon(true); workers[i].start();
                }
                start.countDown();
                for(Thread worker:workers) { worker.join(10000); if(worker.isAlive()) throw new AssertionError("Concurrent owner initialization stalled"); }
                for(Throwable failure:failed) if(failure!=null) throw new AssertionError("Concurrent bootstrap failed",failure);
            } else if(variant%3==1) Class.forName("BootstrapMemoryOwnerB",true,loader);
            Class<?> first = Class.forName("BootstrapMemoryOwnerA", true, loader);
            Class<?>[] original = new Class<?>[helpers.length];
            for (int i=0;i<helpers.length;i++) {
                original[i] = Class.forName(helpers[i], false, loader);
                if(original[i].getClassLoader()!=loader) throw new AssertionError("Wrong helper loader");
                if(loader.getResource(helpers[i]+".class")!=null) throw new AssertionError("Helper file exposed");
            }
            Class<?> second = Class.forName("BootstrapMemoryOwnerB", true, loader);
            Method a=first.getDeclaredMethod("adninDefineHelpers"); a.setAccessible(true);
            Method b=second.getDeclaredMethod("adninDefineHelpers"); b.setAccessible(true);
            for(int i=0;i<100;i++) { a.invoke(null); b.invoke(null); }
            if(measure) {
                com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
                if(!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("Allocation counters unavailable");
                bean.setThreadAllocatedMemoryEnabled(true);
                long thread=Thread.currentThread().getId(), before=bean.getThreadAllocatedBytes(thread);
                for(int i=0;i<2000;i++) { a.invoke(null); b.invoke(null); }
                allocated=bean.getThreadAllocatedBytes(thread)-before;
                // Check the emitted byte decoder against the real input classes.
                // Baseline generators lack the separate lazy decoder.
                try {
                    Method bytes=first.getDeclaredMethod("adninHelperBytes",int.class); bytes.setAccessible(true);
                    for(int i=0;i<helpers.length;i++) {
                        if(!Arrays.equals(Files.readAllBytes(expected.resolve(helpers[i]+".class")),
                                          (byte[])bytes.invoke(null,i)))
                            throw new AssertionError("Decoded helper bytes changed: "+helpers[i]);
                    }
                } catch(NoSuchMethodException baseline) { }
            }
            for(int i=0;i<helpers.length;i++)
                if(Class.forName(helpers[i],false,loader)!=original[i])
                    throw new AssertionError("Repeated bootstrap changed class identity");
            if(System.getProperty(SENTINEL)!=null) throw new AssertionError("Bootstrap initialized sentinel");
            return new WeakReference<ClassLoader>(loader);
        } finally { loader.close(); }
    }

    public static void main(String[] args) throws Exception {
        expected=Paths.get(args[0]);
        helpers=Files.readAllLines(Paths.get(args[1]),java.nio.charset.StandardCharsets.UTF_8).toArray(new String[0]);
        List<URL> paths=new ArrayList<URL>();
        for(String value:Files.readAllLines(Paths.get(args[2]),java.nio.charset.StandardCharsets.UTF_8))
            paths.add(Paths.get(value).toUri().toURL());
        urls=paths.toArray(new URL[0]);
        System.clearProperty(SENTINEL);
        List<WeakReference<ClassLoader>> old=new ArrayList<WeakReference<ClassLoader>>();
        old.add(cycle(true,0));
        for(int i=1;i<12;i++) old.add(cycle(false,i));
        int cleared=0;
        for(int retry=0;retry<20;retry++) {
            System.gc(); System.runFinalization(); Thread.sleep(15);
            cleared=0;
            for(WeakReference<ClassLoader> ref:old) if(ref.get()==null) cleared++;
            if(cleared==old.size()) break;
        }
        if(cleared<old.size()-1) throw new AssertionError("Unexpected retained isolated loaders: "+(old.size()-cleared));
        System.out.println("{\"java\":\""+System.getProperty("java.version")+"\",\"helpers\":"+helpers.length+
            ",\"repeatCalls\":4000,\"repeatAllocatedBytes\":"+allocated+
            ",\"loaderCycles\":12,\"collectedLoaders\":"+cleared+",\"heapLimitMiB\":32}");
    }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--java', type=Path, action='append', default=[])
    parser.add_argument('--baseline-generator', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    build = json.loads((args.classes / 'java-build-report.json').read_text(encoding='utf8'))
    helpers = {name:(args.classes / (name + '.class')).read_bytes() for name in build['helperOrder']}
    netty = [Path(path) for path in build['runtimeClasspath']
             if Path(path).name.startswith('netty-') and Path(path).suffix == '.jar']
    if not all(path.is_file() for path in netty):
        raise ValueError('Existing local Netty dependencies required')
    javac, java = common.find_java(args.jdk, 'javac'), common.find_java(args.jdk, 'java')
    runners = [java, *args.java]
    generators = [('lazy', common)]
    if args.baseline_generator:
        generators.insert(0, ('baseline', module(args.baseline_generator, 'bootstrap_memory_baseline')))
    results = []
    with tempfile.TemporaryDirectory(prefix='adnin-bootstrap-memory-') as temp:
        work = Path(temp)
        sentinel = work / 'AdninBootstrapSentinel.java'
        sentinel.write_text('public final class AdninBootstrapSentinel { static { '
            'System.setProperty("adnin.bootstrap.test.initialized", "true"); } }', encoding='utf8')
        common.compile_sources(javac, [sentinel], str(work), work / 'expected', work / 'sentinel.args')
        helpers['AdninBootstrapSentinel'] = (work / 'expected/AdninBootstrapSentinel.class').read_bytes()
        for name, data in helpers.items():
            (work / 'expected' / (name + '.class')).write_bytes(data)
        ordered = common.order_helpers(helpers)
        names = work / 'helpers.txt'
        names.write_text('\n'.join(ordered) + '\n', encoding='utf8')
        verifier = work / 'BootstrapMemoryVerify.java'
        verifier.write_text(VERIFIER, encoding='utf8')
        # --release hides the optional allocation counter API; compile verifier
        # with source/target 8 against the installed JDK's management API.
        common.javac_args_file(work / 'verify.args', ['-source', '8', '-target', '8', '-encoding', 'UTF-8',
            '-g:none', '-d', work / 'verify', verifier])
        common.run([javac, '@' + str(work / 'verify.args')], 'Bootstrap stress verifier compile')
        for label, generator in generators:
            owners = work / label
            owners.mkdir()
            anchor = owners / 'BootstrapMemoryAnchor.java'
            anchor.write_text('public final class BootstrapMemoryAnchor { }', encoding='utf8')
            sources = [anchor]
            for owner in ('BootstrapMemoryOwnerA', 'BootstrapMemoryOwnerB'):
                path = owners / (owner + '.java')
                text = 'public final class ' + owner + ' { }'
                if build['mode'].startswith('vanilla'):
                    previous = compat.common
                    try:
                        compat.common = generator
                        text = compat.inject_compatibility_bootstrap(text, owner, helpers, 'BootstrapMemoryAnchor')
                    finally:
                        compat.common = previous
                else:
                    text = generator.inject_bootstrap(text, owner, helpers)
                path.write_text(text, encoding='utf8')
                sources.append(path)
            common.compile_sources(javac, sources, str(owners), owners / 'classes', owners / 'compile.args')
            paths = owners / 'paths.txt'
            paths.write_text('\n'.join(map(str, [owners / 'classes', *netty])) + '\n', encoding='utf8')
            for runner in runners:
                output = common.run([runner, '-Xverify:all', '-Xmx32m', '-XX:MaxMetaspaceSize=64m',
                    '-cp', work / 'verify', 'BootstrapMemoryVerify', work / 'expected', names, paths],
                    'Bootstrap memory stress ' + label, 60)
                result = json.loads(output.splitlines()[-1])
                result['generator'] = label
                results.append(result)
        if args.baseline_generator:
            for version in {item['java'] for item in results}:
                before = next(item for item in results if item['java'] == version and item['generator'] == 'baseline')
                after = next(item for item in results if item['java'] == version and item['generator'] == 'lazy')
                if after['repeatAllocatedBytes'] >= before['repeatAllocatedBytes'] // 2:
                    raise AssertionError('Lazy bootstrap did not substantially reduce duplicate-call allocations')
    report = dict(profile=build['mode'], helpers=len(helpers)-1,
        helperClassBytes=sum(len(v) for n,v in helpers.items() if n != 'AdninBootstrapSentinel'),
        helperInitialization=False, gameExecuted=False, ownerOrders=['A then B', 'B then A', 'concurrent'], results=results)
    encoded = json.dumps(report, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(encoded + '\n', encoding='utf8')
    print(encoded)


if __name__ == '__main__':
    main()
