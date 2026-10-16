#!/usr/bin/env python3
"""Locally package unchanged Windows dependencies/runtime with a newly compiled app JAR.

Requires Python 3, Mono mcs/mono, the clean source checkout and :desktop:jar output.
This does not execute or verify Windows native behavior. See docs/RELEASING.md.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile
import zipfile


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def digest(path):
    with path.open("rb") as stream:
        result = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
        return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_directory", type=Path, help="Downloaded prior EXE and its update.json")
    args = parser.parse_args()
    if run("git", "status", "--porcelain"):
        raise SystemExit("Commit source/docs before packaging.")
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
    version = properties["betterdesk.version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise SystemExit("Invalid source version.")
    manifest = json.loads((args.base_directory / "update.json").read_text())
    base_version = manifest["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", base_version) or tuple(map(int, base_version.split("."))) >= tuple(map(int, version.split("."))):
        raise SystemExit("Use a previous release as the runtime baseline.")
    asset = manifest["windows"]
    if asset["name"] != f"BetterDesk-Windows-{base_version}.exe":
        raise SystemExit("Unexpected baseline asset name.")
    baseline = args.base_directory / asset["name"]
    if baseline.stat().st_size != asset["size"] or digest(baseline) != asset["sha256"]:
        raise SystemExit("Baseline EXE fails its published size/SHA-256.")
    # A runtime/dependency change requires a fresh native Windows package instead.
    run("git", "diff", "--exit-code", f"v{base_version}", "--", "build.gradle.kts", "desktop/build.gradle.kts", "settings.gradle.kts", "gradle", "assets", "desktop/src/main/resources")
    old_properties = run("git", "show", f"v{base_version}:gradle.properties")
    normalize = lambda text: [line for line in text.splitlines() if not line.startswith(("betterdesk.version=", "betterdesk.versionCode="))]
    if normalize(old_properties) != normalize((ROOT / "gradle.properties").read_text()):
        raise SystemExit("Build properties changed beyond the version; rebuild on Windows.")
    # Compile current sources locally; this task does not start the app or run tests.
    subprocess.run(["bash", "./gradlew", ":desktop:jar", "--no-daemon", "--no-parallel", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8", "-Pkotlin.compiler.execution.strategy=in-process", "-Pkotlin.incremental=false"], cwd=ROOT, check=True)
    current_jar = ROOT / "desktop/build/libs/desktop.jar"
    output_root = ROOT / "desktop/build"
    with tempfile.TemporaryDirectory(prefix="windows-reuse-", dir=output_root) as directory:
        stage = Path(directory)
        extractor = stage / "Extract.cs"
        extractor.write_text('''using System.IO;
using System.Reflection;
class Extract { static void Main(string[] args) {
  var assembly = Assembly.LoadFile(Path.GetFullPath(args[0]));
  using (var input = assembly.GetManifestResourceStream("BetterDesk.Payload"))
  using (var output = File.Create(args[1])) { input.CopyTo(output); }
}}\n''')
        run("mcs", f"-out:{stage / 'Extract.exe'}", str(extractor))
        original_payload = stage / "original.zip"
        run("mono", str(stage / "Extract.exe"), str(baseline.resolve()), str(original_payload))
        payload = stage / "payload.zip"
        with zipfile.ZipFile(original_payload) as source, zipfile.ZipFile(payload, "w", compression=zipfile.ZIP_DEFLATED) as target:
            names = source.namelist()
            if len(names) != len(set(names)) or any(PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts or ":" in name or "\\" in name for name in names):
                raise SystemExit("Invalid baseline payload paths.")
            config = source.read("app/BetterDesk.cfg").decode("utf-8")
            jars = re.findall(r"^app.classpath=\$APPDIR\\(desktop(?:-[0-9]+)?\.jar)\r?$", config, re.M)
            if len(jars) != 1 or f"app/{jars[0]}" not in names or "BetterDesk.exe" not in names or "runtime/bin/java.dll" not in names:
                raise SystemExit("Unexpected baseline application/runtime layout.")
            app_jar = "app/" + jars[0]
            updated_config = config.replace(jars[0], "desktop.jar").replace(f"app-version={base_version}", f"app-version={version}").replace(f"betterdesk.version={base_version}", f"betterdesk.version={version}")
            if f"betterdesk.version={version}" not in updated_config or f"app-version={version}" not in updated_config:
                raise SystemExit("Baseline lacks expected version options.")
            for entry in source.infolist():
                if entry.filename == app_jar:
                    target.write(current_jar, "app/desktop.jar")
                elif entry.filename == "app/BetterDesk.cfg":
                    target.writestr(entry, updated_config)
                else:
                    target.writestr(entry, source.read(entry))
        # Read back every retained byte, including Windows native libs and JRE legal notices.
        with zipfile.ZipFile(original_payload) as source, zipfile.ZipFile(payload) as rebuilt:
            for name in source.namelist():
                if name not in (app_jar, "app/BetterDesk.cfg"):
                    assert source.read(name) == rebuilt.read(name), name
            assert rebuilt.read("app/desktop.jar") == current_jar.read_bytes()
        attributes = stage / "Version.cs"
        attributes.write_text(f'[assembly: System.Reflection.AssemblyVersion("{version}.0")]\n[assembly: System.Reflection.AssemblyProduct("BetterDesk")]\n')
        destination = output_root / f"BetterDesk-Windows-{version}.exe"
        temporary_exe = stage / destination.name
        run("mcs", "-target:winexe", "-platform:x64", "-optimize+", "-r:System.Windows.Forms", "-r:System.Drawing", "-r:System.IO.Compression", "-r:System.IO.Compression.FileSystem", f"-win32icon:{ROOT / 'assets/betterdesk.ico'}", f"-resource:{payload},BetterDesk.Payload", f"-out:{temporary_exe}", str(ROOT / "desktop/launcher/Portable.cs"), str(attributes))
        # Verify the actual outer EXE resource, rather than only the pre-embed ZIP.
        embedded = stage / "embedded.zip"
        run("mono", str(stage / "Extract.exe"), str(temporary_exe), str(embedded))
        assert digest(embedded) == digest(payload)
        temporary_exe.replace(destination)
        print(f"{destination}\nSHA-256: {digest(destination)}\nRuntime baseline: {baseline.name} ({asset['sha256']})\nWindows runtime execution: UNTESTED")


ROOT = Path(__file__).resolve().parent.parent
if __name__ == "__main__":
    main()
