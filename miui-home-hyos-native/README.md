# MiuiHome LSPosed native hook

This directory contains the Android 17 MiuiHome native payload embedded in the
LSPosed module APK. This branch has no standalone native-module package,
installer, controller, activation path, alternate exported module entry, or
adapter for the retired standalone native API.

The only produced native library is:

```text
lib/arm64-v8a/libmiui_home_hyos_lsp.so
```

The APK declares it through `META-INF/xposed/native_init.list`, and the internal
LSPosed build owns HYOS injection. The library exports only `native_init`.
Launcher business hooks, profile validation, the runtime/Dart resolvers,
authenticated state broadcasts, and the MiCTS-style `madvise` guard are built
into this APK payload.

The private-broadcast bridge resolves its exact Rust dynamic symbol in the
loaded system image and then requires exactly one matching
`R_AARCH64_JUMP_SLOT` from `DT_JMPREL`. It does not carry build-specific
function or GOT offsets. Image ownership, the resolved slot value, RELRO page
handling, and rollback remain fail-closed guards; missing or ambiguous
relocations leave the bridge disabled.

The mapped Dart AOT resolver also carries no launcher-version callback RVAs.
When the compiler emits paired Overview-enter callbacks, it selects the upper
adjacent pool-object member only after it uniquely shares the exit callback's
state slot, shared object, and prepare/publish call targets. Missing, oversized,
or multiply paired candidate families reject the whole Dart state profile.

Home child surfaces use the complete native `notifyBackGestureStatus()` decision.
Two dynamically resolved return epilogues observe early ineligibility and the final
`interactable`/`typeFrom` pair after the normal forwarding call. The same mapped
image must prove the frame and OneByteString layout. The observer copies only a
boolean with its owner epoch; the existing publisher sends authenticated state.
The native readiness protocol retains its `editing` group name for compatibility,
while Android 17 consumes the distinct `launcher_home_surface_visible` state key.

## Build

Use the application build; there is no separate native-module package task:

```text
./gradlew :app:assembleDebug          # Linux/macOS
gradlew.bat :app:assembleDebug        # Windows
./gradlew :app:assembleRelease        # Linux/macOS
gradlew.bat :app:assembleRelease      # Windows
```

Debug is the normal iterative target. Release is reserved for a final delivery
candidate.

## Rebootless iterative deployment

Use the guarded deployment script:

Python 3.10 or newer is required. The script uses only the standard library.

```text
python miui-home-hyos-native/safe_lsposed_native_deploy.py --action deploy --serial <adb-serial>
```

It builds Debug by default, validates the complete APK, installs it through
PackageManager with rollback enabled, proves the existing SystemUI API-102 hot
reload, and then replaces only the exact root `hyos_spawner`. A clean MiuiHome
child must map the current APK inode. The script then runs the module's
authenticated module -> SystemUI -> native status challenge; the dynamic
profile, business/bridge hooks, drawer/overview hooks, and SystemUI monitor must
all report ready, with no new tombstone.

The script never extracts or pushes the native `.so`, never installs anything
under `/system/bin`, never writes Android system properties, and never performs
a whole-device reboot. A retired standalone owner mapping is treated as a hard
conflict; the script does not enable, disable, reload, or otherwise control it.

Useful variants:

```text
# Deploy an existing Debug APK without rebuilding.
python miui-home-hyos-native/safe_lsposed_native_deploy.py --action deploy --serial <adb-serial> --skip-build

# Deploy one explicitly selected complete APK.
python miui-home-hyos-native/safe_lsposed_native_deploy.py --action deploy --serial <adb-serial> --apk <apk-path>

# Read-only process/package status.
python miui-home-hyos-native/safe_lsposed_native_deploy.py --action status --serial <adb-serial>

# Verify the current APK mapping and authenticated native readiness.
python miui-home-hyos-native/safe_lsposed_native_deploy.py --action verify --serial <adb-serial>

# Capture process, LSPosed-log, native-log, and tombstone evidence.
python miui-home-hyos-native/safe_lsposed_native_deploy.py --action capture --serial <adb-serial>
```

## Resolver verification

The launcher profile generator and offline verifiers remain available because
they validate the LSPosed payload itself:

```text
python miui-home-hyos-native/generate-launcher-profiles.py --manifest miui-home-hyos-native/launcher-profiles.json --output miui-home-hyos-native/generated/launcher_profiles.generated.h

python miui-home-hyos-native/verify-launcher-profiles.py --manifest miui-home-hyos-native/launcher-profiles.json --library <profile-id>=<libapp_launcher.so-path>

python miui-home-hyos-native/verify-runtime-profile.py --manifest miui-home-hyos-native/launcher-profiles.json --library <profile-id>=<libapp_launcher.so-path>
```

Local Xiaomi binaries and reverse-engineering workspaces remain ignored and
must never be committed.
