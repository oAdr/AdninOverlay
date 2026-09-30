"""Compile active Adnin Java classes and embed their helper-class bootstrap.

Uses an installed JDK and the user's own Lunar/Minecraft dependency jars. The
original native-loading ABI is kept to nine active classes; reembed.py retains
and brands the tenth, dormant GuiNewChat class. Helpers are present as ordinary
class files for tests, and also embedded inside both active bootstrap owners.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from classfile import read_class


ACTIVE_CLASSES = (
    "AdninGui4", "AdninTabOverlayNative", "AdninRenderGlobal",
    "AdninHitboxInstaller", "AdninIngameGui", "AdninSessionHud",
    "AdninSessionHudInstaller", "AdninPacketLog", "AdninClientPump",
)
BOOTSTRAP_OWNERS = ("AdninGui4", "AdninClientPump")
CHUNK_SIZE = 30000


def sha(data):
    return hashlib.sha256(data).hexdigest()


def default_classpath(root):
    """Prefer stable named vanilla APIs, then locally installed libraries."""
    workspace = root.parents[1]
    named = workspace / "work/dependencies/vanilla-1.8.9-named.jar"
    if not named.is_file():
        named = workspace / "work/dependencies/lunar-1.8-local.jar"
    if not named.is_file():
        raise ValueError("No named local Minecraft jar; provide --classpath with named 1.8.9 classes")
    home = Path.home()
    candidates = [named]
    candidates.extend(sorted((home / ".lunarclient/offline/multiver").glob("*.jar")))
    candidates.extend(sorted((home / ".lunarclient/libraries").rglob("*.jar")))
    minecraft_roots = [home / "AppData/Roaming/.minecraft", home / ".minecraft"]
    for minecraft in minecraft_roots:
        metadata_path = minecraft / "versions/1.8.9/1.8.9.json"
        if not metadata_path.is_file():
            continue
        metadata = json.loads(metadata_path.read_text(encoding="utf-8-sig"))
        for library in metadata.get("libraries", []):
            artifact = library.get("downloads", {}).get("artifact", {}).get("path")
            if artifact:
                candidate = minecraft / "libraries" / artifact
            else:
                coordinates = library.get("name", "").split(":")
                if len(coordinates) != 3:
                    continue
                group, name, version = coordinates
                candidate = minecraft / "libraries" / group.replace(".", "/") / name / version / (name + "-" + version + ".jar")
            if candidate.is_file():
                candidates.append(candidate)
        break
    result, seen = [], set()
    for path in candidates:
        path = path.resolve()
        if str(path).lower() not in seen and path.is_file():
            result.append(str(path))
            seen.add(str(path).lower())
    return os.pathsep.join(result)


def find_java(jdk, program):
    suffix = ".exe" if os.name == "nt" else ""
    if jdk:
        base = Path(jdk).expanduser().resolve()
        candidates = [base / "bin" / (program + suffix), base / (program + suffix)]
        for path in candidates:
            if path.is_file():
                return path
        raise ValueError("JDK does not contain " + program)
    value = shutil.which(program)
    if not value:
        raise ValueError("No " + program + " found; provide --jdk")
    return Path(value).resolve()


def run(command, label, timeout=90):
    result = subprocess.run([str(v) for v in command], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        diagnostic = (result.stdout + result.stderr).strip()
        raise ValueError(label + " failed:\n" + diagnostic[-14000:])
    return (result.stdout + result.stderr).strip()


def javac_args_file(path, values):
    # javac argfiles avoid the Windows command-line length limit. Use forward
    # slashes so its backslash escape grammar cannot damage Windows paths.
    lines = []
    for value in values:
        value = str(value).replace("\\", "/")
        if "\n" in value or "\r" in value or '"' in value:
            raise ValueError("Unsupported quote or newline in compiler argument")
        lines.append('"' + value + '"')
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def compile_sources(javac, source_paths, classpath, destination, arguments_path):
    destination.mkdir(parents=True, exist_ok=True)
    options = ["--release", "8", "-encoding", "UTF-8", "-g:none", "-proc:none",
               "-implicit:none", "-cp", classpath, "-d", destination]
    options.extend(source_paths)
    javac_args_file(arguments_path, options)
    return run([javac, "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "@" + str(arguments_path)], "Java compilation")


def staged_source(path):
    text = path.read_text(encoding="utf-8-sig")
    if path.stem == "AdninGui4":
        for method in ("mouseClicked", "keyTyped"):
            pattern = r"\bprotected\s+void\s+" + method + r"\s*\("
            text, count = re.subn(pattern, "public void " + method + "(", text)
            if count != 1:
                raise ValueError("Unexpected protected override for AdninGui4." + method)
    return text


def order_helpers(helper_bytes):
    metadata = {name: read_class(data) for name, data in helper_bytes.items()}
    result = []
    remaining = set(metadata)
    while remaining:
        ready = []
        for name in remaining:
            info = metadata[name]
            needs = {info["parent"], *info["interfaces"]} & remaining
            if not needs:
                ready.append(name)
        if not ready:
            raise ValueError("Cyclic helper superclass/interface dependencies")
        ready.sort(key=lambda name: (name != "AdninApi", name != "AdninFeatures", name.count("$"), name))
        result.extend(ready)
        remaining.difference_update(ready)
    return result


def bootstrap_method(owner, helper_bytes):
    names = order_helpers(helper_bytes)
    name_literal = ", ".join(json.dumps(name) for name in names)
    cases = []
    for index, name in enumerate(names):
        encoded = base64.b64encode(helper_bytes[name]).decode("ascii")
        pieces = [json.dumps(encoded[i:i + CHUNK_SIZE]) for i in range(0, len(encoded), CHUNK_SIZE)]
        if len(pieces) == 1:
            body = "return java.util.Base64.getDecoder().decode(" + pieces[0] + ");"
        else:
            # Append separately: '+' would fold into an overlong UTF8 constant.
            body = "StringBuilder encoded = new StringBuilder(" + str(len(encoded)) + ");\n"
            body += "\n".join("                encoded.append(" + piece + ");" for piece in pieces)
            body += "\n                return java.util.Base64.getDecoder().decode(encoded.toString());"
        cases.append("            case " + str(index) + ": { " + body + " }")
    case_literal = "\n".join(cases)
    return """
    // Generated by build-java.py. Defines helpers, without initializing them.
    private static void adninDefineHelpers() {
        final ClassLoader loader = OWNER.class.getClassLoader();
        final String[] names = new String[] {NAMES};
        if (loader == null) throw new ExceptionInInitializerError("Adnin requires an application class loader");
        synchronized (loader) {
            for (int index = 0; index < names.length; index++) {
                try {
                    Class.forName(names[index], false, loader);
                    continue;
                } catch (ClassNotFoundException missing) {
                    // The DLL defines only the original active class entrypoints.
                }
                byte[] bytes = adninHelperBytes(index);
                try {
                    java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
                    java.lang.reflect.Method define;
                    try {
                        define = java.lang.invoke.MethodHandles.Lookup.class.getMethod("defineClass", byte[].class);
                        define.invoke(lookup, new Object[] { bytes });
                    } catch (NoSuchMethodException java8) {
                        define = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
                        define.setAccessible(true);
                        define.invoke(loader, names[index], bytes, 0, bytes.length);
                    }
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof LinkageError) {
                        try {
                            Class.forName(names[index], false, loader);
                            continue;
                        } catch (ClassNotFoundException missing) { /* report the original failure */ }
                    }
                    throw new ExceptionInInitializerError(cause);
                } catch (ReflectiveOperationException failure) {
                    throw new ExceptionInInitializerError(failure);
                }
            }
        }
    }

    // Only a missing class reaches its Base64 literals. A second owner or a
    // repeated bootstrap does not create encoded arrays or decode class bytes.
    private static byte[] adninHelperBytes(int index) {
        switch (index) {
CASES
            default: throw new IllegalArgumentException("Unknown Adnin helper index");
        }
    }
""".replace("OWNER", owner).replace("NAMES", name_literal).replace("CASES", case_literal)


def inject_bootstrap(text, owner, helper_bytes):
    if "adninDefineHelpers" in text:
        raise ValueError("Source already contains generated bootstrap: " + owner)
    declaration = re.compile(r"(\bclass\s+" + re.escape(owner) + r"\b[^\{]*\{)")
    matches = list(declaration.finditer(text))
    if len(matches) != 1:
        raise ValueError("Could not locate unique owner class declaration: " + owner)
    at = matches[0].end()
    text = text[:at] + "\n    static { adninDefineHelpers(); }\n" + text[at:]
    end = text.rfind("}")
    if end < at or text[end + 1:].strip():
        raise ValueError("Unexpected trailing source content: " + owner)
    return text[:end] + bootstrap_method(owner, helper_bytes) + text[end:]


def class_files(directory):
    result = {}
    for file in sorted(directory.rglob("*.class")):
        data = file.read_bytes()
        info = read_class(data)
        name = info["name"]
        if "/" in name or not name.startswith("Adnin") or info["major"] != 52:
            raise ValueError("Unexpected compiled class: " + name)
        if name in result:
            raise ValueError("Duplicate compiled class: " + name)
        result[name] = data
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", help="Installed JDK 9+ directory (javac --release 8)")
    parser.add_argument("--classpath", help="Runtime dependency classpath; auto-discovered from local Lunar and Minecraft when omitted")
    parser.add_argument("--output", type=Path, required=True, help="Destination directory for final class files")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.expanduser().resolve()
    if output == root or output in root.parents or output == Path.home().resolve():
        raise ValueError("Output must be a dedicated build directory")
    javac, java = find_java(args.jdk, "javac"), find_java(args.jdk, "java")
    compiler = run([javac, "-version"], "JDK version", 10)
    classpath = args.classpath or default_classpath(root)
    paths = sorted(path for path in (root / "src/java").glob("*.java") if path.stem != "AdninGuiNewChat")
    names = {path.stem for path in paths}
    if not set(ACTIVE_CLASSES) <= names or not {"AdninApi", "AdninFeatures"} <= names:
        raise ValueError("Active classes and AdninApi.java / AdninFeatures.java must exist before building")
    source_texts = {path.name: staged_source(path) for path in paths}
    hashes = {path.name: sha(path.read_bytes()) for path in paths}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="adnin-java-", dir=output.parent) as temporary:
        work = Path(temporary)
        first_stage, second_stage = work / "stage1", work / "stage2"
        first_stage.mkdir()
        second_stage.mkdir()
        for name, content in source_texts.items():
            (first_stage / name).write_text(content, encoding="utf-8")
        compile_sources(javac, sorted(first_stage.glob("*.java")), classpath, work / "classes1", work / "pass1.args")
        first_classes = class_files(work / "classes1")
        helpers = {name: data for name, data in first_classes.items() if name not in ACTIVE_CLASSES}
        if not {"AdninApi", "AdninFeatures"} <= set(helpers):
            raise ValueError("Required helpers were not emitted by the first compilation")
        for name, content in source_texts.items():
            owner = Path(name).stem
            if owner in BOOTSTRAP_OWNERS:
                content = inject_bootstrap(content, owner, helpers)
            (second_stage / name).write_text(content, encoding="utf-8")
        compile_sources(javac, sorted(second_stage.glob("*.java")), classpath, work / "classes2", work / "pass2.args")
        final_classes = class_files(work / "classes2")
        if set(final_classes) != set(first_classes):
            raise ValueError("Class set changed between bootstrap passes")
        for name, data in helpers.items():
            if final_classes[name] != data:
                raise ValueError("Helper bytecode changed between passes: " + name)
        verifier = root / "tests/java/AdninBootstrapVerify.java"
        compile_sources(javac, [verifier], classpath, work / "verify", work / "verify.args")
        bootstrap_cp = os.pathsep.join([str(work / "verify"), *[
            path for path in classpath.split(os.pathsep)
            if Path(path).name.startswith("netty-") and Path(path).suffix == ".jar"]])
        verification = run([java, "-Xverify:all", "-cp", bootstrap_cp, "AdninBootstrapVerify", work / "classes2", *order_helpers(helpers)], "Isolated bootstrap verification", 30)
        for path in paths:
            if sha(path.read_bytes()) != hashes[path.name]:
                raise ValueError("Java source changed during compilation; rerun the build")
        output.mkdir(parents=True, exist_ok=True)
        # Delete only stale generated Adnin class artifacts, never unrelated files.
        for stale in output.glob("Adnin*.class"):
            if stale.stem not in final_classes:
                stale.unlink()
        for name, data in final_classes.items():
            (output / (name + ".class")).write_bytes(data)
        report = {
            "compiler": compiler, "classVersion": 52, "mode": "local-lunar-runtime-with-helper-bootstrap",
            "compiledActiveClasses": list(ACTIVE_CLASSES), "retainedDormantClass": "AdninGuiNewChat",
            "bootstrapOwners": list(BOOTSTRAP_OWNERS), "helperOrder": order_helpers(helpers),
            "bootstrapDoesNotInitializeHelpers": True, "base64ChunkLimit": CHUNK_SIZE,
            "stagedAccessWidening": ["AdninGui4.mouseClicked", "AdninGui4.keyTyped"],
            "sourceSha256": hashes, "helperSha256": {name: sha(data) for name, data in helpers.items()},
            "classSha256": {name: sha(data) for name, data in final_classes.items()},
            "runtimeClasspath": classpath.split(os.pathsep), "verification": verification,
            "runtimeGameTested": False,
        }
        (output / "java-build-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (output / "runtime-classpath.txt").write_text(classpath + "\n", encoding="utf-8")
    print("Compiled " + str(len(ACTIVE_CLASSES)) + " active classes and " + str(len(helpers)) + " embedded helpers with " + compiler)
    print(verification)
    print("Class files: " + str(output))
    print("Reembed only the nine active classes; reembed.py retains/renames AdninGuiNewChat itself.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print("Java build failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
