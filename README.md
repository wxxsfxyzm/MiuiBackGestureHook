# MIUI Back Gesture Hook

An LSPosed module for researching Xiaomi MIUI/HyperOS back gestures.

The APK integrates with SystemUI and the system back pipeline. It also carries
an arm64-v8a LSPosed Native Hook for the MiuiHome process family spawned by
`hyos_spawner`. Until LSPosed exposes hook-page lifecycle support, the APK
native entry protects its exact inline and PLT/GOT hook pages from Xiaomi's
`MADV_DONTNEED` cleanup before enabling Android 17 business hooks.

Optional integrations (off by default):

- Circle to Search from the gesture handle;
- Google App full-screen Live Translate, with the platform screen-capture consent
  flow unchanged.

The fullscreen back behavior dropdown shares a settings card with Side trigger area.
“Show system bars first” preserves the default AOSP policy; “Go back directly” lets the
first immersive side gesture use the existing back pipeline, even if system bars appear
during it. Changes apply to the next gesture without a restart.

Side trigger area limits back gestures to a vertical band on both edges. Touches outside the
band reach the application below. On Android 17, system_server publishes the main display's
rows outside the band as system gesture exclusion, so MiuiHome redirects those touches through
its own native path instead of keeping them in the full-height edge windows. Pass-through
restores touch delivery; application gestures still follow their own policies and window
configuration. If adding the strips would exceed MiuiHome's 64-rectangle callback limit,
the original application exclusion regions are preserved and the optional strips are skipped.

The HyperOS back indicator has an optional Advanced material switch, off by default.
It uses Xiaomi's system material APIs within the live indicator silhouette, keeps the arrow
sharp, and falls back to the original bitmap background when the APIs are unavailable or
reject the request. Changes apply to the next gesture. The switch animates into view only
when the HyperOS indicator style is selected. The material uses a dark background in
both light and dark system themes.

Android 16 uses the SystemUI gesture path. Android 17 keeps launcher-side
ownership in the LSPosed native entry and fails closed if its page guard or
validated native profile cannot be established.

## Compatibility

| Platform | Required components | Launcher support |
| --- | --- | --- |
| Android 16 | LSPosed | SystemUI gesture path |
| Android 17 / HyperOS 4+ | Internal LSPosed with HYOS-spawner support | `4371` static profile; validated newer builds use the runtime resolver |

The native entry is arm64-v8a only. Unsupported or ambiguous layouts fail closed.
LSPosed's enabled/scope state and HYOS injection own spawner compatibility; the
entry validates the exact executable and launcher process identity but does not
pin a `hyos_spawner` Build ID across OTAs. Launcher ELF profiles and business
hook fingerprints remain fail-closed.

## Build

Build the LSPosed release APK:

Linux/macOS:

```shell
./gradlew :app:assembleRelease
```

Windows:

```powershell
.\gradlew.bat :app:assembleRelease
```

Outputs:

```text
app/build/outputs/apk/release/app-release.apk
```

For iterative native development, use the guarded LSPosed deployment script.
It requires Python 3.10 or newer, builds the debug APK by default, installs the
complete APK through Package Manager, verifies the API-102 SystemUI hot reload,
and rolls only the exact
root `hyos_spawner` so the new native APK inode becomes active without a phone
reboot:

```text
python miui-home-hyos-native/safe_lsposed_native_deploy.py --action deploy --serial <adb-serial>
```

Pass `--skip-build` to deploy the existing debug APK, or `--apk <path>` to deploy
an explicitly selected complete APK. The script refuses to run while a retired
standalone owner remains mapped. It never extracts or pushes the APK's native
library separately and never writes Android system properties. The
upgrade requests PackageManager rollback with retained app data and also keeps
a verified copy of the previously installed complete APK as a fallback.

The APK contains `lib/arm64-v8a/libmiui_home_hyos_lsp.so` and declares it in
`META-INF/xposed/native_init.list`. This branch has no standalone native-module
package or activation path.

## Scope and runtime

The static LSPosed scope is:

```text
com.android.systemui
com.miui.home
com.mi.android.globallauncher
com.google.android.googlequicksearchbox
system
```

Android 16 also supports POCO Launcher (`com.mi.android.globallauncher`), which
retains MiuiHome's Java class namespace. Broadcast authentication and indicator
resources are bound to the selected launcher package. POCO support does not extend
to Android 17 or newer; its Java launcher hooks stay disabled there.

The Google App scope is used only when Live Translate is enabled. On Android 17
and newer, MiuiHome must remain in scope so LSPosed can load the native entry
through its HYOS-spawner support. It registers no Java launcher hooks;
launcher work is owned by the LSPosed native entry.

API 102 hot reload is enabled with `autoHotReload=true`. The settings screen can
refresh the authenticated SystemUI/native runtime status and resolved launcher profile.

## References

Checked-in AOSP references are under `refs/android16/aosp_back_16/`. Xiaomi
artifacts and device evidence remain local-only under ignored `refs/android17`
paths; see [refs/README.md](refs/README.md).

Shared native sources, the LSPosed APK target, and guarded deployment tooling are
under `miui-home-hyos-native/`.

## License

Apache License 2.0. See [LICENSE](LICENSE).

Bundled third-party components and their separate license terms are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
