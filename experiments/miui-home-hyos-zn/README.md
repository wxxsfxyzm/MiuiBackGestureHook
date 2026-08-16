# MiuiHome Android 17 native handoff

This directory builds the Zygisk Next half of the Android 17 back-gesture
handoff. Xiaomi's native MiuiHome runtime remains the physical side-window and
DOWN owner; this module identifies an accepted launcher stream and publishes
its immutable input identity to the companion SystemUI hook. SystemUI then owns
the indicator, pilfering, Shell navigation, and predictive-back animation.

This is no longer an observation-only probe. It contains device-proven profiles
for MiuiHome `4371` and `5334`.

## Requirements and scope

- Android 17 HyperOS with the exact supported `hyos_spawner` build below.
- arm64 device with Zygisk Next.
- MiuiHome `4371` or `5334`, selected automatically from immutable native
  identity. The ZIP contains both profiles.
- The companion LSPosed module enabled for its existing Android 17 SystemUI
  and `system` scopes. This ZN module alone does not create an AOSP gesture.

Zygisk Next injects the library only into:

```text
/system_ext/bin/hyos_spawner
```

The executable and controller remain inside the module directory. Nothing is
mounted into `/system/bin`, and neither the runtime nor the deployment tools
read or write Android properties.

The Zygisk Next module enabled state is the only runtime gate. A normal module
installation does not restart a process; after the user reboots, an enabled ZN
module immediately attempts the fail-closed profile match.

## Supported identities

Shared native runtime:

```text
hyos_spawner path:       /system_ext/bin/hyos_spawner
hyos_spawner Build ID:   87f2632e7d68fda0226366fda5346c2d
broadcast private IDs:   7f186b331ec39d84e6016daeba65366f (Xiaomi 17 Pro Max, OS4.0.0.20)
                         c4fec5d3d810f76eff819d9379517a4a (Redmi K90, OS4.0.0.18)
launcher process:        /proc/self/cmdline == com.miui.home
entry symbol:            app_entry_point
```

The broadcast-private dylib is per-ROM Build ID while its mangled symbols and
GOT layout stay identical, so the arbiter-bridge gate accepts either ID. The
`5334` profile has been validated on both the author's device and a Redmi K90
(`annibale`, `OS4.0.0.18.XPKCNXM`, `MiuiSystemUI 17.03.260226.r`) with the
full `verify-launcher-profiles.py` PASS.

Launcher profiles:

| Profile | Package identity | Native identity | Hook topology |
| --- | --- | --- | --- |
| `4371` | `801024371` / `RELEASE-8.01.02.4371-260727-08131546-R` | SHA-256 `a84365f864f88f85165b086bc03ba563efd09386c72f0c21926788fd90a028f9`; entry `0x885d00`; side boundary `0xc6e954`; edge `+0xec` | `legacy_three_stage` |
| `5334` | `801025334` / `RELEASE-8.01.02.5334-260807-08151151-R` | SHA-256 `a67fe9e3ef3880f920cce92eb1c006c7fe12a83c0632f2397b118e6915043091`; entry `0xc8ffd8`; side boundary `0x80c3bc`; edge `+0xf4` | `side_boundary_only` |

Package version is enforced by the host deployment script. At runtime the
module validates the exact `app_entry_point` RVA and the profile's immutable
code fingerprints before installing business hooks. Hook sites validate their
own prologues again. Zero matches, multiple matches, an incomplete profile, or
a topology mismatch leaves Xiaomi behavior unchanged.

This is not an AOB scanner. A nearby byte pattern is never treated as a
compatible build. The library SHA-256 is an offline profile-verification input;
runtime selection uses the exact entry address and fingerprints.

`4371` keeps two transparent legacy diagnostic hooks around the side boundary.
`5334` uses only the proven side boundary because Xiaomi inlined the older
processor stages. Both profiles hand off only a launcher-accepted BACK stream.

## Loader path

The confirmed native ownership chain is:

```text
hyos_spawner
  dlopen("libhyper_os_shell.so")
    -> libhyper_os_shell.so
         android_dlopen_ext("libhyper_os_app_public.so")
           -> libhyper_os_app_public.so
                dlsym(handle, "app_entry_point")
                  -> APK libapp_launcher.so
```

The module hooks only the relevant owner PLT slots and then installs
profile-bound hooks in the resolved launcher image. It does not hook the system
linker globally and does not depend on the randomized `/data/app` directory.

The authenticated launcher-to-SystemUI state channel uses an explicit
identity-sharing broadcast. The private Rust broadcast tail hook preserves its
raw AArch64 result ABI (`x8`, including the `sp+0x58` option slot); do not replace
it with an ordinary C++ aggregate-return hook.

## First boot and first gesture

After a standard ZIP install and reboot, the module starts enabled if the root
manager and Zygisk Next report it enabled. There is no additional marker or
feature switch.

SystemUI readiness can arrive after the launcher bridge is installed. On a
fresh process, treat the first side gesture as a **readiness warmup**: it may
stay entirely on Xiaomi's native path and publish no ownership token. Once the
matching SystemUI arbiter generation is ready, following accepted gestures use
the SystemUI/AOSP path. If the receiver, generation, identity, or native
readiness check is missing, the bridge fails closed instead of sending an
unauthenticated handoff.

For controlled validation, perform the readiness warmup first, then perform exactly one formal side gesture and capture evidence immediately.

## Profiles

[`launcher-profiles.json`](launcher-profiles.json) is the only editable profile
source. [`generate-launcher-profiles.py`](generate-launcher-profiles.py)
validates it and emits the C++ registry into the build directory. The device
does not parse a writable JSON or XML profile at runtime.

When locally retained launcher ELFs are available, verify both profiles without
adding those proprietary binaries or decompiler projects to Git:

```powershell
python .\experiments\miui-home-hyos-zn\verify-launcher-profiles.py `
  --library 4371=<4371-libapp_launcher.so> `
  --library 5334=<5334-libapp_launcher.so>
```

The verifier checks the recorded digest, translates every RVA through the ELF
LOAD table, and compares all identity and hook bytes inside executable
segments.

To adapt another MiuiHome build, add a new manifest profile only after static
analysis identifies the entry, accepted side boundary, edge field, topology,
ABI offsets, and immutable fingerprints. Generate and verify the profile, build
a Debug ZIP, then use the controlled deployment flow below. Never copy offsets
from the nearest version on version-name evidence alone.

## Build

From the repository root:

```powershell
.\experiments\miui-home-hyos-zn\build.ps1 -Configuration Debug
```

Use `Release` only for a distributable package:

```powershell
.\experiments\miui-home-hyos-zn\build.ps1 -Configuration Release
```

Output is written under:

```text
out/miui-home-hyos-zn/<Configuration>/
out/packages/miui-home-hyos-zn-<timestamp>.zip
```

The build runs the controller contract checks, invokes the Python profile
generator, verifies ELF64/AArch64 plus BTI/PAC, enforces the single `zn_module`
export, checks both assembly tail shims, generates `diagnostics.map`, and then
packages the module. Its version name comes from `app/build.gradle`; its version
code is the current Git commit count, matching the main app BuildConfig source.

The ZIP intentionally contains no Xiaomi library, APK, Ghidra project, JADX
output, or other decompiled/proprietary artifact.

## Installation and distribution

Install the Release ZIP from the KernelSU or Magisk module UI/CLI, with Zygisk
Next already available, then reboot. The installer only places files below the
module directory and deliberately does not restart `hyos_spawner` or MiuiHome.

A recipient with the exact shared runtime and either supported MiuiHome version
gets the matching profile automatically. A recipient on any other launcher or
native runtime gets a fail-closed no-hook result; this is not a promise of broad
HyperOS compatibility.

The companion LSPosed module and its Android 17 configuration must also be
installed. If it is absent or not ready, the ZN half cannot transfer ownership
to SystemUI.

## Controlled device deployment

For development, do not overwrite a mapped ELF and do not invoke a package
installer for each live iteration. Use
[safe-device-test.ps1](safe-device-test.ps1). It verifies the exact installed
MiuiHome package, stages the payload under a content-derived name, disables the
ZN module before replacing files, replaces only the exact root
`hyos_spawner`, verifies parentage and mappings, checks for a new tombstone, and
automatically rolls back on failure.

Status and evidence capture are read-only and require no profile confirmation:

```powershell
.\experiments\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Status -Serial <adb-serial>

.\experiments\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Capture -Serial <adb-serial>
```

Deploy one exact profile:

```powershell
.\experiments\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Deploy -Serial <adb-serial> `
  -PackageZip <debug-zip> -Confirm4371

.\experiments\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Deploy -Serial <adb-serial> `
  -PackageZip <debug-zip> -Confirm5334
```

Rollback uses the matching confirmation:

```powershell
.\experiments\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Rollback -Serial <adb-serial> -Confirm5334
```

After `Deploy`:

1. Wait for Home to be stable.
2. Perform one readiness warmup side gesture if this is a fresh launcher or
   SystemUI generation.
3. Perform exactly one formal side gesture.
4. Stop and run `Capture` before another test.

Evidence is stored under `out/device-tests/` and includes native counters,
filtered logcat, LSPosed logs, crash logs, process events, and tombstone state.

If launcher crashes, stop testing. Let the automatic rollback finish; if the
launcher remains in its crash/safe-mode state, reboot. Reinstall MiuiHome once
only when the launcher still does not recover after rollback/reboot, then wait
for a stable Home before deploying again.

## On-device controller

The package installs:

```text
/data/adb/modules/miui-home-hyos-zn/bin/hsctl
```

Run it through a root shell:

```sh
hsctl status
hsctl counters
hsctl logs 120
hsctl activate --confirm
hsctl rollback --confirm
```

`activate` and `rollback` are bounded runtime operations for an already staged
module. They do not install packages, write properties, kill MiuiHome directly,
or reboot the device. Host-side development should still use
`safe-device-test.ps1`, which supplies the exact package/profile guards and
evidence capture around these commands.

Common healthy evidence after handoff includes installed business/bridge state,
a ready nonzero arbiter generation, an accepted Stub BACK DOWN, a published
identity token, and suppression only after that token is accepted. Interpret
counters together with SystemUI logs; a counter alone is not proof that Shell
owned the gesture.

## Recovery and safety invariants

- Never overwrite `libmiui_home_hyos_zn.so` while it is mapped.
- Never deploy through `/system/bin` or a system overlay.
- Never use Android properties as gates, diagnostics, or recovery controls.
- Never broaden injection beyond the exact `hyos_spawner` path.
- Never add Xiaomi binaries, APK contents, JADX output, Ghidra projects, or
  proprietary disassembly artifacts to the repository.
- A profile or hook mismatch must preserve Xiaomi behavior and fail closed.
- A crash during controlled deployment must trigger rollback before further
  gestures are tested.

Historical loader and reverse-engineering details are kept in the repository's
root Android 17 native-loader report. This README describes the current module
contract, not the chronological experiment diary.
