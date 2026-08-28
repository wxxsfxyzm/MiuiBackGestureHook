#!/usr/bin/env python3
"""Guarded cross-platform deployment for the LSPosed native APK payload."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


PACKAGE_NAME = "dev.codex.miuibackgesturehook"
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
EVIDENCE_ROOT = SCRIPT_DIR / "out" / "lsposed-device-tests"


@dataclass(frozen=True)
class CommandResult:
    exit_code: int
    lines: tuple[str, ...]
    text: str


@dataclass(frozen=True)
class LauncherProcess:
    pid: int
    parent_pid: int


@dataclass(frozen=True)
class ApkIdentity:
    path: str
    sha256: str
    device_inode_size: str
    inode: int


@dataclass(frozen=True)
class LogSnapshot:
    path: str = ""
    size: int = 0


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_local_property(value: str) -> str:
    return value.replace(r"\:", ":").replace(r"\\", os.sep)


def resolve_adb() -> Path:
    located = shutil.which("adb")
    if located:
        return Path(located).resolve()
    properties = REPO_ROOT / "local.properties"
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk = Path(decode_local_property(line[len("sdk.dir=") :]))
                executable = "adb.exe" if os.name == "nt" else "adb"
                candidate = sdk / "platform-tools" / executable
                if candidate.is_file():
                    return candidate.resolve()
                break
    raise RuntimeError(
        "adb is unavailable and local.properties does not identify an Android SDK."
    )


class Deployer:
    def __init__(self, serial: str) -> None:
        self.serial = serial
        self.adb = resolve_adb()
        self.sdk = self.adb.parent.parent

    @staticmethod
    def emit(value: str) -> None:
        print(value, flush=True)

    @staticmethod
    def run_host(
        arguments: Sequence[os.PathLike[str] | str],
        *,
        cwd: Path | None = None,
    ) -> CommandResult:
        command = [os.fspath(argument) for argument in arguments]
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            cwd=cwd,
            check=False,
        )
        text = completed.stdout.rstrip("\r\n")
        return CommandResult(completed.returncode, tuple(text.splitlines()), text)

    @staticmethod
    def require_success(result: CommandResult, description: str) -> CommandResult:
        if result.exit_code != 0:
            suffix = f"\n{result.text}" if result.text else ""
            raise RuntimeError(f"{description} failed ({result.exit_code}).{suffix}")
        return result

    def invoke_adb(
        self, arguments: Sequence[str], *, allow_failure: bool = False
    ) -> CommandResult:
        result = self.run_host([self.adb, "-s", self.serial, *arguments])
        if result.exit_code != 0 and not allow_failure:
            suffix = f"\n{result.text}" if result.text else ""
            raise RuntimeError(
                f"adb failed ({result.exit_code}): {' '.join(arguments)}{suffix}"
            )
        return result

    def invoke_root(self, command: str, *, allow_failure: bool = False) -> CommandResult:
        normalized = command.replace("\r\n", "\n").strip()
        remote = f"su -c {shlex.quote(normalized)}"
        return self.invoke_adb(["shell", remote], allow_failure=allow_failure)

    def resolve_build_tool(self, name: str) -> Path:
        root = self.sdk / "build-tools"
        candidates: list[tuple[tuple[int, int, int], Path]] = []
        if root.is_dir():
            for directory in root.iterdir():
                if not directory.is_dir() or not re.fullmatch(
                    r"\d+(?:\.\d+){2}", directory.name
                ):
                    continue
                candidates.append(
                    (tuple(int(part) for part in directory.name.split(".")), directory)
                )
        if not candidates:
            raise RuntimeError("No stable Android build-tools directory exists.")
        directory = max(candidates)[1]
        candidate = directory / name
        if not candidate.is_file():
            raise RuntimeError(f"Android build tool is missing: {candidate}")
        return candidate

    def assert_device(self) -> None:
        if self.invoke_adb(["get-state"]).text.strip() != "device":
            raise RuntimeError(f"Device {self.serial} is not online.")
        if self.invoke_root("id -u").text.strip() != "0":
            raise RuntimeError(f"Device {self.serial} has no adb-accessible root shell.")

    def assert_no_foreign_native_owner(self) -> None:
        # This retired standalone-module path is intentionally retained only as
        # a safety detector. A mapped legacy owner makes APK deployment unsafe.
        command = r'''
spawner=""
for pid in $(ps -A -o USER,PID,PPID | awk '$1 == "root" && $3 == 1 {print $2}'); do
  [ "$(readlink "/proc/$pid/exe" 2>/dev/null)" = "/system_ext/bin/hyos_spawner" ] || continue
  spawner="$pid"
done
[ -n "$spawner" ] || exit 41
for pid in "$spawner" $(ps -A -o PID,PPID | awk -v owner="$spawner" '$2 == owner {print $1}'); do
  [ -r "/proc/$pid/maps" ] || continue
  if grep -F '/data/adb/modules/miui-home-hyos-zn/' "/proc/$pid/maps" >/dev/null 2>&1; then
    echo "$pid"
    exit 42
  fi
done
'''
        result = self.invoke_root(command, allow_failure=True)
        if result.exit_code == 42:
            raise RuntimeError(
                f"Refusing deployment: PID {result.text.strip()} maps a foreign "
                "native module owner."
            )
        if result.exit_code != 0:
            raise RuntimeError(
                "Refusing deployment: native-owner state is unreadable "
                f"(exit={result.exit_code})."
            )

    def get_exact_spawner_pid(self) -> int | None:
        result = self.invoke_root(
            r'''
for pid in $(ps -A -o USER,PID,PPID | awk '$1 == "root" && $3 == 1 {print $2}'); do
  [ "$(readlink "/proc/$pid/exe" 2>/dev/null)" = "/system_ext/bin/hyos_spawner" ] || continue
  echo "$pid"
done
''',
            allow_failure=True,
        )
        pids = [int(line) for line in result.lines if re.fullmatch(r"\d+", line)]
        return pids[0] if result.exit_code == 0 and len(pids) == 1 else None

    def get_launcher_processes(self) -> list[LauncherProcess]:
        result = self.invoke_root(
            r'''
for pid in $(pidof com.miui.home 2>/dev/null); do
  [ -r "/proc/$pid/cmdline" ] || continue
  name="$(tr '\000' '\n' < "/proc/$pid/cmdline" | head -n 1)"
  [ "$name" = "com.miui.home" ] || continue
  ppid="$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$pid/status")"
  echo "$pid:$ppid"
done
''',
            allow_failure=True,
        )
        processes: list[LauncherProcess] = []
        for line in result.lines:
            match = re.fullmatch(r"(\d+):(\d+)", line)
            if match:
                processes.append(LauncherProcess(int(match[1]), int(match[2])))
        return processes

    def get_systemui_pid(self) -> int | None:
        text = self.invoke_adb(
            ["shell", "pidof", "com.android.systemui"], allow_failure=True
        ).text.strip()
        return int(text) if re.fullmatch(r"\d+", text) else None

    def wait_for_device_connection(self, timeout_seconds: float = 90.0) -> None:
        deadline = time.monotonic() + timeout_seconds
        last_state = ""
        while time.monotonic() < deadline:
            state = self.invoke_adb(["get-state"], allow_failure=True)
            last_state = state.text.strip()
            if state.exit_code == 0 and last_state == "device":
                return
            time.sleep(0.25)
        raise RuntimeError(
            "ADB did not reconnect within the bounded post-install window. "
            f"Last state: {last_state or 'unavailable'}"
        )

    def get_installed_apk_path(self) -> str | None:
        result = self.invoke_adb(
            ["shell", "pm", "path", PACKAGE_NAME], allow_failure=True
        )
        paths: list[str] = []
        for line in result.lines:
            match = re.fullmatch(r"package:(/data/app/[^\s]+/base\.apk)", line)
            if match:
                paths.append(match[1])
        return paths[0] if result.exit_code == 0 and len(paths) == 1 else None

    def get_remote_apk_identity(self, path: str) -> ApkIdentity:
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_~+=./-]+/base\.apk", path):
            raise RuntimeError(f"Unsafe installed APK path: {path}")
        quoted = shlex.quote(path)
        result = self.invoke_root(
            f"sha256sum {quoted} | awk '{{print $1}}'\n"
            f"stat -c '%D:%i:%s' {quoted}"
        )
        if (
            len(result.lines) < 2
            or not re.fullmatch(r"[0-9a-fA-F]{64}", result.lines[0])
            or not re.fullmatch(r"[0-9a-fA-F]+:\d+:\d+", result.lines[1])
        ):
            raise RuntimeError(f"Installed APK identity is unreadable: {path}")
        return ApkIdentity(
            path,
            result.lines[0].lower(),
            result.lines[1],
            int(result.lines[1].split(":")[1]),
        )

    def get_latest_tombstone(self) -> str:
        return self.invoke_root(
            r'''
for item in /data/tombstones/tombstone_*; do
  [ -f "$item" ] && stat -c '%Y:%s:%n' "$item"
done | sort -n | tail -n 1
''',
            allow_failure=True,
        ).text.strip()

    def get_lsposed_log_snapshot(self) -> LogSnapshot:
        result = self.invoke_root(
            r'''
path="$(ls -1t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1)"
[ -n "$path" ] || exit 0
size="$(stat -c '%s' "$path" 2>/dev/null)" || exit 0
echo "$path"
echo "$size"
''',
            allow_failure=True,
        )
        if (
            len(result.lines) < 2
            or not re.fullmatch(r"/data/adb/lspd/log/modules_[^/]+\.log", result.lines[0])
            or not re.fullmatch(r"\d+", result.lines[1])
        ):
            return LogSnapshot()
        return LogSnapshot(result.lines[0], int(result.lines[1]))

    def read_lsposed_log_delta(self, before: LogSnapshot) -> str:
        current = self.get_lsposed_log_snapshot()
        if not current.path:
            return ""
        quoted = shlex.quote(current.path)
        if current.path == before.path and current.size >= before.size:
            return self.invoke_root(
                f"tail -c +{before.size + 1} {quoted}", allow_failure=True
            ).text
        return self.invoke_root(f"cat {quoted}", allow_failure=True).text

    def wait_api102_hot_reload(
        self, expected_systemui_pid: int, log_before: LogSnapshot
    ) -> str:
        for _ in range(100):
            current_systemui_pid = self.get_systemui_pid()
            if current_systemui_pid is None:
                # Some devices briefly restart adbd after PackageManager
                # replaces a debuggable APK. An unreadable PID is not evidence
                # that SystemUI restarted: wait for the same serialized device
                # to return, then compare the actual process identity.
                self.wait_for_device_connection()
                current_systemui_pid = self.get_systemui_pid()
            if current_systemui_pid != expected_systemui_pid:
                raise RuntimeError(
                    "SystemUI restarted during installation; API-102 hot reload was "
                    "not preserved. "
                    f"Expected PID {expected_systemui_pid}, got "
                    f"{current_systemui_pid or 'unavailable'}."
                )
            delta = self.read_lsposed_log_delta(log_before)
            if re.search(r"Hot reloaded, build=.*process=com\.android\.systemui", delta):
                return delta
            time.sleep(0.2)
        raise RuntimeError(
            "No fresh SystemUI API-102 hot-reload completion appeared within 20 seconds."
        )

    def stop_exact_spawner(self, old_pid: int) -> int:
        if self.get_exact_spawner_pid() != old_pid:
            raise RuntimeError(f"PID {old_pid} is no longer the exact root hyos_spawner.")
        self.invoke_root(f"kill -TERM {old_pid}")
        for _ in range(150):
            new_pid = self.get_exact_spawner_pid()
            if new_pid is not None and new_pid != old_pid:
                return new_pid
            time.sleep(0.1)
        raise RuntimeError(
            "hyos_spawner did not reach a replacement PID within 15 seconds."
        )

    def assert_launcher_maps_current_apk(
        self,
        launcher_pid: int,
        apk_identity: ApkIdentity,
        rejected_inodes: Iterable[int] = (),
    ) -> str:
        active_path = shlex.quote(f" {apk_identity.path}")
        result = self.invoke_root(
            f"grep -F {active_path} /proc/{launcher_pid}/maps", allow_failure=True
        )
        if result.exit_code != 0 or not result.lines:
            raise RuntimeError(
                f"Launcher PID {launcher_pid} does not map the active module APK."
            )
        if "(deleted)" in result.text:
            raise RuntimeError(
                f"Launcher PID {launcher_pid} still maps a deleted APK inode."
            )
        for line in result.lines:
            columns = line.strip().split()
            if (
                len(columns) < 6
                or not columns[4].isdigit()
                or int(columns[4]) != apk_identity.inode
            ):
                raise RuntimeError(
                    f"Launcher PID {launcher_pid} maps the active path through a "
                    "different inode."
                )
        rejected = set(rejected_inodes)
        if rejected:
            all_maps = self.invoke_root(f"cat /proc/{launcher_pid}/maps")
            for line in all_maps.lines:
                columns = line.strip().split()
                if len(columns) >= 5 and columns[4].isdigit() and int(columns[4]) in rejected:
                    raise RuntimeError(
                        f"Launcher PID {launcher_pid} retains a rejected APK inode."
                    )
        package = shlex.quote(PACKAGE_NAME)
        wrong = self.invoke_root(
            f"grep -F {package} /proc/{launcher_pid}/maps | grep -F '(deleted)'",
            allow_failure=True,
        )
        if wrong.exit_code == 0 and wrong.text.strip():
            raise RuntimeError(
                f"Launcher PID {launcher_pid} retains an older module APK mapping."
            )
        return result.text

    def start_and_wait_launcher(
        self,
        expected_parent: int,
        rejected_pids: Iterable[int],
        apk_identity: ApkIdentity,
        rejected_inodes: Iterable[int] = (),
    ) -> LauncherProcess:
        self.invoke_adb(
            [
                "shell",
                "am",
                "start",
                "-W",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.HOME",
            ],
            allow_failure=True,
        )
        rejected_pid_set = set(rejected_pids)
        last_mapping_failure = ""
        for _ in range(200):
            launchers = self.get_launcher_processes()
            accepted = [
                process
                for process in launchers
                if process.parent_pid == expected_parent
                and process.pid not in rejected_pid_set
            ]
            rejected_alive = [
                process for process in launchers if process.pid in rejected_pid_set
            ]
            if len(launchers) == 1 and len(accepted) == 1 and not rejected_alive:
                try:
                    self.assert_launcher_maps_current_apk(
                        accepted[0].pid, apk_identity, rejected_inodes
                    )
                    return accepted[0]
                except RuntimeError as error:
                    # HYOS may publish a short-lived child before the final
                    # launcher maps the current module APK.
                    last_mapping_failure = str(error)
            time.sleep(0.1)
        raise RuntimeError(
            "MiuiHome did not reach one clean mapped child under the replacement "
            f"spawner. {last_mapping_failure}"
        )

    def wait_native_ready_status(self, launcher_pid: int) -> str:
        logs = self.invoke_adb(
            [
                "shell",
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                "2000",
                "-s",
                "MiuiHomeHyosLsp:V",
                "MiuiHomeHyosMadvise:V",
                "*:S",
            ],
            allow_failure=True,
        )
        native_log = "\n".join(
            line
            for line in logs.lines
            if re.search(rf"\s{launcher_pid}\s+\d+\s+[VDIWEF]\s+MiuiHomeHyos", line)
        )
        if "LSPosed launcher hooks rejected without madvise guard" in native_log:
            raise RuntimeError(
                f"Native initialization rejected the replacement Launcher.\n{native_log}"
            )

        status_component = f"{PACKAGE_NAME}/.activity.PredictiveBackSettingsActivity"
        last_native_status = ""
        try:
            # The first authenticated query can race the private broadcast
            # runtime-holder capture immediately after a fresh launcher fork.
            # Retry with a new Activity instance and nonce; every attempt still
            # requires an authenticated native response and full ready state.
            for probe_attempt in range(3):
                status_log_before = self.get_lsposed_log_snapshot()
                start = self.invoke_adb(
                    ["shell", "am", "start", "-S", "-W", "-n", status_component],
                    allow_failure=True,
                )
                if start.exit_code != 0 or not re.search(r"Status:\s+ok", start.text):
                    raise RuntimeError(
                        "Could not start the authenticated native status probe "
                        f"attempt {probe_attempt + 1}.\n{start.text}"
                    )
                for _ in range(20):
                    time.sleep(0.25)
                    status_log = self.read_lsposed_log_delta(status_log_before)
                    lines = [
                        line
                        for line in status_log.splitlines()
                        if "Published module runtime status reply" in line
                        and "nativeResponse=true" in line
                    ]
                    native_status = lines[-1] if lines else ""
                    if native_status:
                        last_native_status = native_status
                    if "statusReady=true" in native_status:
                        return f"authenticated_native_status=ready\n{native_status}"
                    if "statusReady=false" in native_status:
                        raise RuntimeError(
                            "Authenticated native status reported not ready.\n"
                            f"{native_status}"
                        )
            raise RuntimeError(
                "Authenticated native status did not become ready after "
                "3 bounded probes.\n"
                f"{last_native_status}"
            )
        finally:
            self.invoke_adb(
                ["shell", "am", "force-stop", PACKAGE_NAME], allow_failure=True
            )

    def assert_local_apk(self, path: Path) -> None:
        required = {
            "META-INF/xposed/native_init.list",
            "lib/arm64-v8a/libmiui_home_hyos_lsp.so",
            "lib/arm64-v8a/liblsplt.so",
        }
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            missing = sorted(required - names)
            if missing:
                raise RuntimeError(f"APK is missing {missing[0]}.")
            native_list = archive.read("META-INF/xposed/native_init.list").decode(
                "utf-8"
            )
            if native_list.strip() != "libmiui_home_hyos_lsp.so":
                raise RuntimeError(
                    "APK native_init.list does not name the LSPosed HYOS payload exactly."
                )
        zipalign_name = "zipalign.exe" if os.name == "nt" else "zipalign"
        zipalign = self.resolve_build_tool(zipalign_name)
        self.require_success(
            self.run_host([zipalign, "-c", "-P", "16", "4", path]),
            "APK 16KB ZIP alignment verification",
        )
        signer_name = "apksigner.bat" if os.name == "nt" else "apksigner"
        signer = self.resolve_build_tool(signer_name)
        self.require_success(
            self.run_host([signer, "verify", path]), "APK signature verification"
        )

    def resolve_deployment_apk(
        self, apk: str | None, variant: str, skip_build: bool
    ) -> Path:
        if apk:
            path = Path(apk).expanduser().resolve()
            if not path.is_file():
                raise RuntimeError(f"Deployment APK does not exist: {path}")
            return path
        variant_lower = variant.lower()
        output = (
            REPO_ROOT
            / "app"
            / "build"
            / "outputs"
            / "apk"
            / variant_lower
            / f"app-{variant_lower}.apk"
        )
        if not skip_build:
            if os.name == "nt":
                gradle_command: list[os.PathLike[str] | str] = [
                    REPO_ROOT / "gradlew.bat"
                ]
            else:
                wrapper = REPO_ROOT / "gradlew"
                gradle_command = (
                    [wrapper] if os.access(wrapper, os.X_OK) else ["sh", wrapper]
                )
            result = self.run_host(
                [*gradle_command, f":app:assemble{variant}"], cwd=REPO_ROOT
            )
            if result.text:
                self.emit(result.text)
            self.require_success(result, f"Gradle assemble{variant}")
        if not output.is_file():
            raise RuntimeError(f"Deployment APK does not exist: {output}")
        return output.resolve()

    def write_evidence(self, phase: str, extra: str = "") -> Path:
        stamp = time.strftime("%Y%m%d-%H%M%S")
        target = EVIDENCE_ROOT / f"{stamp}-{phase}"
        if target.exists():
            target = EVIDENCE_ROOT / f"{stamp}-{phase}-{uuid.uuid4().hex[:8]}"
        target.mkdir(parents=True, exist_ok=False)
        status = [
            f"serial={self.serial}",
            f"hyos_pid={self.get_exact_spawner_pid()}",
            f"systemui_pid={self.get_systemui_pid()}",
        ]
        path = self.get_installed_apk_path()
        status.append(f"apk_path={path or ''}")
        if path:
            try:
                identity = self.get_remote_apk_identity(path)
                status.extend(
                    [
                        f"apk_sha256={identity.sha256}",
                        f"apk_device_inode_size={identity.device_inode_size}",
                    ]
                )
            except RuntimeError as error:
                status.append(f"apk_identity_error={error}")
        status.extend(
            f"launcher_pid={process.pid},parent={process.parent_pid}"
            for process in self.get_launcher_processes()
        )
        if extra.strip():
            status.append(extra)
        (target / "device-status.txt").write_text(
            "\n".join(status) + "\n", encoding="utf-8"
        )
        logs = self.invoke_adb(
            [
                "shell",
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                "3000",
                "-s",
                "MiuiHomeHyosLsp:V",
                "MiuiHomeHyosMadvise:V",
                "*:S",
            ],
            allow_failure=True,
        )
        selected = [line for line in logs.lines if "MiuiHomeHyos" in line]
        (target / "runtime-logcat.txt").write_text(
            "\n".join(selected) + "\n", encoding="utf-8"
        )
        latest = self.get_lsposed_log_snapshot()
        lsposed = (
            self.invoke_root(
                f"tail -n 8000 {shlex.quote(latest.path)}", allow_failure=True
            ).text
            if latest.path
            else ""
        )
        (target / "lsposed-module-log.txt").write_text(
            lsposed + "\n", encoding="utf-8"
        )
        tombstones = self.invoke_root(
            r'''
for item in /data/tombstones/tombstone_*; do
  [ -f "$item" ] && stat -c '%Y %s %n' "$item"
done
''',
            allow_failure=True,
        )
        (target / "tombstones.txt").write_text(
            tombstones.text + "\n", encoding="utf-8"
        )
        return target

    def install_apk(
        self,
        path: Path,
        *,
        enable_rollback: bool = False,
        allow_downgrade: bool = False,
        allow_failure: bool = False,
    ) -> CommandResult:
        arguments = ["install", "-r"]
        if enable_rollback:
            arguments.extend(["--enable-rollback", "2"])
        if allow_downgrade:
            arguments.append("-d")
        arguments.append(os.fspath(path))
        result = self.invoke_adb(arguments, allow_failure=allow_failure)
        if not allow_failure and "Success" not in result.text:
            raise RuntimeError(
                f"Package installation did not report success.\n{result.text}"
            )
        return result

    def invoke_rollback(self, rollback_apk: Path, expected_hash: str) -> str:
        if not rollback_apk.is_file():
            return "rollback_apk=unavailable"
        rollback_method = "platform"
        platform = self.invoke_adb(
            ["shell", "pm", "rollback-app", PACKAGE_NAME], allow_failure=True
        )
        identity: ApkIdentity | None = None
        if platform.exit_code == 0 and "Success" in platform.text:
            for _ in range(200):
                candidate_path = self.get_installed_apk_path()
                if candidate_path:
                    try:
                        candidate = self.get_remote_apk_identity(candidate_path)
                        if candidate.sha256 == expected_hash:
                            identity = candidate
                            break
                    except RuntimeError:
                        pass
                time.sleep(0.1)
        if identity is None:
            rollback_method = "pulled-apk-fallback"
            install = self.install_apk(
                rollback_apk, allow_downgrade=True, allow_failure=True
            )
            if install.exit_code != 0 or "Success" not in install.text:
                return (
                    "rollback_install=failed\n"
                    f"platform={platform.text}\nfallback={install.text}"
                )
            current_path = self.get_installed_apk_path()
            if not current_path:
                return "rollback_install=identity-unavailable"
            identity = self.get_remote_apk_identity(current_path)
        if identity.sha256 != expected_hash:
            return f"rollback_install=hash-mismatch,actual={identity.sha256}"
        old_spawner = self.get_exact_spawner_pid()
        if old_spawner is None:
            return "rollback_spawner=unavailable"
        old_launchers = [process.pid for process in self.get_launcher_processes()]
        try:
            new_spawner = self.stop_exact_spawner(old_spawner)
            launcher = self.start_and_wait_launcher(
                new_spawner, old_launchers, identity
            )
            self.wait_native_ready_status(launcher.pid)
            return (
                f"rollback=restored,method={rollback_method},hyos_pid={new_spawner},"
                f"launcher_pid={launcher.pid}"
            )
        except RuntimeError as error:
            return f"rollback_activation=failed,error={error}"

    def show_status(self) -> None:
        self.assert_no_foreign_native_owner()
        self.emit("foreign_native_owner=absent")
        self.emit(f"hyos_pid={self.get_exact_spawner_pid()}")
        self.emit(f"systemui_pid={self.get_systemui_pid()}")
        path = self.get_installed_apk_path()
        self.emit(f"apk_path={path or ''}")
        if path:
            identity = self.get_remote_apk_identity(path)
            self.emit(f"apk_sha256={identity.sha256}")
            self.emit(f"apk_device_inode_size={identity.device_inode_size}")
        for launcher in self.get_launcher_processes():
            self.emit(f"launcher_pid={launcher.pid},parent={launcher.parent_pid}")

    def verify(self) -> None:
        self.assert_no_foreign_native_owner()
        spawner = self.get_exact_spawner_pid()
        if spawner is None:
            raise RuntimeError("Exact root hyos_spawner is unavailable.")
        launchers = self.get_launcher_processes()
        matching = [process for process in launchers if process.parent_pid == spawner]
        if len(matching) != 1 or len(launchers) != 1:
            raise RuntimeError(
                "Current Launcher is not one clean child of the exact spawner."
            )
        path = self.get_installed_apk_path()
        if not path:
            raise RuntimeError("The module APK is not installed.")
        identity = self.get_remote_apk_identity(path)
        self.assert_launcher_maps_current_apk(matching[0].pid, identity)
        self.emit(self.wait_native_ready_status(matching[0].pid))
        self.emit(f"apk_sha256={identity.sha256}")
        self.emit(f"hyos_pid={spawner}")
        self.emit(f"launcher_pid={matching[0].pid}")

    def deploy(
        self,
        apk: str | None,
        variant: str,
        skip_build: bool,
        skip_rollback_backup: bool = False,
    ) -> None:
        self.assert_no_foreign_native_owner()
        deployment_apk = self.resolve_deployment_apk(apk, variant, skip_build)
        self.assert_local_apk(deployment_apk)
        local_hash = sha256_file(deployment_apk)
        systemui_before = self.get_systemui_pid()
        if systemui_before is None:
            raise RuntimeError("SystemUI is unavailable.")
        spawner_before = self.get_exact_spawner_pid()
        if spawner_before is None:
            raise RuntimeError("Exact root hyos_spawner is unavailable.")
        launchers_before = [process.pid for process in self.get_launcher_processes()]
        tombstone_before = self.get_latest_tombstone()
        log_before = self.get_lsposed_log_snapshot()
        if not log_before.path:
            raise RuntimeError(
                "LSPosed module log is unavailable; API-102 hot reload cannot be proven."
            )
        installed_before_path = self.get_installed_apk_path()
        if not installed_before_path:
            raise RuntimeError(
                "This guarded update path requires an already installed module APK."
            )
        installed_before = self.get_remote_apk_identity(installed_before_path)
        rollback_apk = Path(tempfile.gettempdir()) / (
            f"miui-back-gesture-hook-rollback-{uuid.uuid4().hex}.apk"
        )
        rollback_hash = ""
        mutation_started = False
        try:
            if skip_rollback_backup:
                self.emit("rollback_backup=skipped")
            else:
                rollback_hash = installed_before.sha256
                self.invoke_adb(["pull", installed_before_path, os.fspath(rollback_apk)])
                if sha256_file(rollback_apk) != rollback_hash:
                    raise RuntimeError(
                        "Pulled rollback APK hash does not match the installed package."
                    )

            self.install_apk(deployment_apk, enable_rollback=True)
            mutation_started = True
            self.wait_for_device_connection()
            installed_path = self.get_installed_apk_path()
            if not installed_path:
                raise RuntimeError(
                    "PackageManager did not publish one active base APK after installation."
                )
            installed = self.get_remote_apk_identity(installed_path)
            if installed.sha256 != local_hash:
                raise RuntimeError(f"Installed APK hash mismatch: {installed.sha256}")
            self.wait_api102_hot_reload(systemui_before, log_before)

            if self.get_exact_spawner_pid() != spawner_before:
                raise RuntimeError(
                    "hyos_spawner changed unexpectedly before the controlled replacement."
                )
            spawner_after = self.stop_exact_spawner(spawner_before)
            launcher_after = self.start_and_wait_launcher(
                spawner_after,
                launchers_before,
                installed,
                [installed_before.inode],
            )
            self.wait_native_ready_status(launcher_after.pid)
            time.sleep(1.5)
            tombstone_after = self.get_latest_tombstone()
            if tombstone_after != tombstone_before:
                raise RuntimeError(
                    f"A new tombstone appeared during activation: {tombstone_after}"
                )
            self.emit(f"apk_sha256={local_hash}")
            self.emit(f"apk_path={installed_path}")
            self.emit(f"hyos_old_pid={spawner_before}")
            self.emit(f"hyos_new_pid={spawner_after}")
            self.emit(f"launcher_pid={launcher_after.pid}")
            self.emit(f"launcher_parent={launcher_after.parent_pid}")
            self.emit("api102_hot_reload=verified")
            self.emit("native_generation=current-apk")
            evidence = self.write_evidence(
                "lsposed-native-activated", f"apk_sha256={local_hash}"
            )
            self.emit(f"evidence={evidence}")
        except Exception as error:
            failure = str(error)
            rollback = (
                self.invoke_rollback(rollback_apk, rollback_hash)
                if mutation_started and rollback_hash
                else "rollback=not-attempted"
            )
            evidence = self.write_evidence(
                "lsposed-native-failure", f"failure={failure}\n{rollback}"
            )
            raise RuntimeError(f"{failure}\n{rollback}\n evidence={evidence}") from error
        finally:
            rollback_apk.unlink(missing_ok=True)


def parse_args(arguments: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--action",
        choices=("status", "verify", "deploy", "capture"),
        default="status",
        type=str.lower,
    )
    parser.add_argument("--serial", required=True)
    parser.add_argument(
        "--variant", choices=("Debug", "Release"), default="Debug", type=str.capitalize
    )
    parser.add_argument("--apk")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-rollback-backup", action="store_true")
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    args = parse_args(arguments)
    deployer = Deployer(args.serial)
    try:
        deployer.assert_device()
        if args.action == "status":
            deployer.show_status()
        elif args.action == "verify":
            deployer.verify()
        elif args.action == "capture":
            deployer.emit(f"evidence={deployer.write_evidence('manual-capture')}")
        else:
            deployer.deploy(
                args.apk,
                args.variant,
                args.skip_build,
                args.skip_rollback_backup,
            )
        return 0
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
