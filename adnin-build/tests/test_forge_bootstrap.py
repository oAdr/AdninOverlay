"""Verify Forge's generated default-package helper bootstrap on Java 8/9+."""
import argparse
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'scripts'))

def run(command):
    result = subprocess.run([str(item) for item in command], capture_output=True,
                            text=True, timeout=30)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--java8', type=Path)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('forge_builder', ROOT/'scripts/build-java-forge.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    with tempfile.TemporaryDirectory(prefix='adnin-forge-bootstrap-') as directory:
        work = Path(directory)
        helper_source = work/'AdninProbe.java'
        helper_source.write_text('public final class AdninProbe { public static final int VALUE=24; '
                                 'static {System.setProperty("adnin.probe.initialized", "true");} }\n', encoding='ascii')
        run([args.jdk/'bin/javac.exe', '--release', '8', '-d', work, helper_source])
        helper = (work/'AdninProbe.class').read_bytes()
        owner_source = builder.inject_forge_bootstrap(
            'public final class AdninForgeOwner {}\n', 'AdninForgeOwner', {'AdninProbe': helper})
        (work/'AdninForgeOwner.java').write_text(owner_source, encoding='utf8')
        run([args.jdk/'bin/javac.exe', '--release', '8', '-d', work, work/'AdninForgeOwner.java'])
        (work/'AdninProbe.class').unlink()
        runner = work/'BootstrapRunner.java'
        runner.write_text('''
import java.net.*;
import java.nio.file.*;
public final class BootstrapRunner {
  public static void main(String[] args) throws Exception {
    URLClassLoader loader = new URLClassLoader(new URL[]{Paths.get(args[0]).toUri().toURL()}, null);
    Class.forName("AdninForgeOwner", true, loader);
    Class<?> probe = Class.forName("AdninProbe", false, loader);
    if (probe.getClassLoader() != loader) throw new AssertionError("Wrong helper class loader");
    if (System.getProperty("adnin.probe.initialized") != null) throw new AssertionError("Helper initialized during bootstrap");
    if (loader.getResource("AdninProbe.class") != null) throw new AssertionError("Helper was available as a file");
    if (probe.getField("VALUE").getInt(null) != 24) throw new AssertionError("Helper value mismatch");
    System.out.println("Forge default-package helper bootstrap passed on " + System.getProperty("java.version"));
  }
}
''', encoding='ascii')
        run([args.jdk/'bin/javac.exe', '--release', '8', '-d', work, runner])
        java_runtimes = [args.jdk/'bin/java.exe']
        if args.java8:
            java_runtimes.append(args.java8)
        else:
            java_runtimes.extend(sorted(Path('C:/Program Files/Eclipse Adoptium').glob('jdk-8*/bin/java.exe'))[-1:])
        for java in java_runtimes:
            print(run([java, '-Xverify:all', '-cp', work, 'BootstrapRunner', work]))

if __name__ == '__main__':
    main()
