#!/usr/bin/env python3
"""Build the public module APK and the private Java MiuiHome APK in one run.

The public flavor never embeds the Java MiuiHome and is the only one that may be shared. The
private flavor embeds the local proprietary launcher configured through `javaHomeLauncherApk`
and must never be published or handed out; without that launcher it is skipped.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_ROOT = REPO_ROOT / "out" / "releases"
EMBEDDED_LAUNCHER_ENTRY = "lib/arm64-v8a/libjavahome_launcher.so"
FLAVORS = ("public", "javahome")


def fail(message: str) -> None:
    sys.exit(f"error: {message}")


def run(command: list[str], capture: bool = False) -> str:
    result = subprocess.run(command, cwd=REPO_ROOT, text=True,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.STDOUT if capture else None)
    if result.returncode != 0:
        if capture:
            print(result.stdout, file=sys.stderr)
        fail(f"{command[0]} exited with {result.returncode}")
    return result.stdout if capture else ""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_local_properties() -> dict[str, str]:
    properties: dict[str, str] = {}
    path = REPO_ROOT / "local.properties"
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                properties[key.strip()] = value.strip().replace("\\:", ":")
    return properties


def build_tools_dir(properties: dict[str, str]) -> Path:
    sdk = properties.get("sdk.dir") or os.environ.get("ANDROID_HOME") \
        or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        fail("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME")
    versions = sorted((path for path in (Path(sdk) / "build-tools").iterdir() if path.is_dir()),
                      key=lambda path: [int(part) if part.isdigit() else 0
                                        for part in re.split(r"[.-]", path.name)])
    if not versions:
        fail(f"no build-tools installed under {sdk}")
    return versions[-1]


def tool(tools: Path, name: str) -> str:
    for candidate in (tools / name, tools / f"{name}.exe", tools / f"{name}.bat"):
        if candidate.is_file():
            return str(candidate)
    fail(f"{name} not found in {tools}")
    return ""


def gradle(variant: str, launcher_apk: str, clean: bool) -> None:
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    tasks = (["clean"] if clean else []) + [f":app:assemble{variant}"]
    # An explicit empty property overrides local.properties, so the public build never embeds.
    run([wrapper, *tasks, f"-PjavaHomeLauncherApk={launcher_apk}"])


def built_apk(variant: str) -> Path:
    directory = REPO_ROOT / "app" / "build" / "outputs" / "apk" / variant.lower()
    apks = sorted(directory.glob("*.apk"))
    if len(apks) != 1:
        fail(f"expected one APK in {directory}, found {len(apks)}")
    if list(directory.glob("*.idsig")):
        fail(f"unexpected v4 signature next to {apks[0]}")
    return apks[0]


def signer_digest(tools: Path, apk: Path) -> str:
    output = run([tool(tools, "apksigner"), "verify", "--print-certs", str(apk)], capture=True)
    match = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", output)
    if not match:
        fail(f"no signer certificate in {apk}")
    return match.group(1)


def extract_native_libs(tools: Path, apk: Path) -> bool:
    output = run([tool(tools, "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml",
                  str(apk)], capture=True)
    match = re.search(r"extractNativeLibs\([^)]*\)=(true|false)", output)
    # AGP omits the attribute only when the platform default (extract) applies.
    return match is None or match.group(1) == "true"


def verify(tools: Path, apk: Path, flavor: str, launcher_apk: str) -> str:
    run([tool(tools, "zipalign"), "-c", "-P", "16", "4", str(apk)], capture=True)
    digest = signer_digest(tools, apk)
    with zipfile.ZipFile(apk) as archive:
        libraries = [entry for entry in archive.infolist()
                     if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        compressed = [entry.filename for entry in libraries
                      if entry.compress_type != zipfile.ZIP_STORED]
        if compressed:
            fail(f"{apk.name}: compressed native libraries {compressed}")
        names = {entry.filename for entry in libraries}
        embedded = EMBEDDED_LAUNCHER_ENTRY in names
        if flavor == "public":
            if embedded:
                fail(f"{apk.name}: public build contains the Java MiuiHome")
            if extract_native_libs(tools, apk):
                fail(f"{apk.name}: public build must keep native libraries in the APK")
        else:
            if not embedded:
                fail(f"{apk.name}: Java MiuiHome is missing")
            if not extract_native_libs(tools, apk):
                fail(f"{apk.name}: Java MiuiHome build must extract native libraries")
            payload = hashlib.sha256(archive.read(EMBEDDED_LAUNCHER_ENTRY)).hexdigest()
            if payload != sha256_file(Path(launcher_apk)):
                fail(f"{apk.name}: embedded Java MiuiHome differs from {launcher_apk}")
    return digest


def version_label(tools: Path, apk: Path) -> str:
    badging = run([tool(tools, "aapt2"), "dump", "badging", str(apk)], capture=True)
    match = re.search(r"versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match:
        fail(f"no version in {apk}")
    commit = run(["git", "rev-parse", "--short", "HEAD"], capture=True).strip()
    dirty = run(["git", "status", "--porcelain", "--untracked-files=no"], capture=True).strip()
    return f"{match.group(2)}-{match.group(1)}-{commit}{'-dirty' if dirty else ''}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--variant", choices=("Release", "Debug"), default="Release")
    parser.add_argument("--only", choices=FLAVORS,
                        help="build a single flavor instead of both")
    parser.add_argument("--launcher-apk",
                        help="Java MiuiHome APK; defaults to javaHomeLauncherApk")
    parser.add_argument("--no-clean", action="store_true",
                        help="skip the clean build (iterative tests only)")
    args = parser.parse_args()

    properties = read_local_properties()
    tools = build_tools_dir(properties)
    flavors = (args.only,) if args.only else FLAVORS
    launcher_apk = args.launcher_apk or os.environ.get("JAVA_HOME_LAUNCHER_APK") \
        or properties.get("javaHomeLauncherApk", "")
    if "javahome" in flavors:
        if not launcher_apk or not Path(launcher_apk).is_file():
            # Only an explicit request for the private flavor requires the local launcher.
            if args.only == "javahome" or args.launcher_apk:
                fail("Java MiuiHome APK not found: pass --launcher-apk or set "
                     "javaHomeLauncherApk in local.properties")
            print("No local Java MiuiHome APK; skipping the javahome flavor.", flush=True)
            flavors = tuple(flavor for flavor in flavors if flavor != "javahome")
        else:
            launcher_apk = str(Path(launcher_apk).resolve())

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    results: list[tuple[str, Path, str]] = []
    for flavor in flavors:
        print(f"==> Building {flavor} {args.variant}", flush=True)
        gradle(args.variant, launcher_apk if flavor == "javahome" else "", not args.no_clean)
        apk = built_apk(args.variant)
        digest = verify(tools, apk, flavor, launcher_apk)
        suffix = "" if flavor == "public" else "-javahome-private"
        target = OUTPUT_ROOT / (f"MiuiBackGestureHook-{version_label(tools, apk)}"
                                f"-{args.variant.lower()}{suffix}.apk")
        shutil.copyfile(apk, target)
        results.append((flavor, target, digest))

    if len({digest for _, _, digest in results}) > 1:
        fail("the flavors are signed with different certificates")
    for flavor, target, _ in results:
        print(f"{flavor}: {target.relative_to(REPO_ROOT)}")
    if any(flavor == "javahome" for flavor, _, _ in results):
        print("The javahome APK contains the proprietary Xiaomi launcher: never publish it.")


if __name__ == "__main__":
    main()
