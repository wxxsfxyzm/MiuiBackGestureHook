# AGENTS.md

This LSPosed API-102 module restores Xiaomi/MIUI back gestures and optional system-UI behavior.
On Android 17 it supports both the native (Flutter) MiuiHome and an embedded Android 16 Java
MiuiHome, selected by the user and switchable at runtime.

## Scope and decisions

- Preserve working callback, cross-Activity, cross-Task and return-to-Home navigation.
- Within the requested feature and these boundaries, investigation, fixes and hook-location
  changes do not need separate approval. Changes to safety, authentication or process scope
  require explicit user direction; a previous implementation is not an approval boundary.
- Keep static scope limited to `com.android.systemui`, `com.miui.home`,
  `com.mi.android.globallauncher`, `com.google.android.googlequicksearchbox` and `system`.
  Do not hook selected opt-in applications or the voice-assistant process.

## Device safety

- Never write Android system properties, including through helpers or recovery commands.
  Do not execute `setprop`, even with an unchanged value.
- Deliver the native entry only inside a complete APK through PackageManager and LSPosed's
  framework-owned HYOS injection. Never separately extract, push, overwrite or activate its
  `.so`, or modify/truncate any mapped native payload.
- Use `python3 miui-home-hyos-native/safe_lsposed_native_deploy.py` for every native APK
  update. It must prove API-102 hot reload without restarting SystemUI, replace only the exact
  owning root `hyos_spawner`, and verify absence of new tombstones. With a native launcher it
  verifies spawner parentage, current APK mapping/inode and native readiness; with a Java
  launcher it refuses a launcher running from the module's embedded copy and verifies one
  launcher outside the spawner, an unmapped native entry and the re-attached Java bridge.
  Do not bypass a failed safety check.
- Do not use module-local marker files as native runtime gates, install a standalone native
  module/controller, or deploy while a retired standalone owner remains mapped.
- Recover native crash loops through the deployment script's whole-APK rollback and exact
  spawner replacement, followed by one standard HOME start. Never clear launcher data or
  manipulate RescueParty properties. Reinstalling the launcher requires the user's request;
  persistent SafeLauncher after rollback/HOME recovery must be reported before further tests.
- The embedded Java MiuiHome is a proprietary Xiaomi APK supplied only through the local
  `javaHomeLauncherApk` build property. Never commit it, and never publish or hand out a build
  that contains it; builds without it hide the launcher option and change nothing.
- The Java launcher reaches PackageManager only through the parse redirect of
  `/product/priv-app/MiuiHome` while the user selects it. Every redirect, scan-flag or allowlist
  failure falls back to the stock path; never let an exception reach PackageManager.
- The privileged-permission allowlist rule is user-approved for `com.miui.home` only and only
  while the redirect is active: an unlisted privileged permission follows the
  updated-system-app rule. Do not widen it to other packages, listed permissions or grants
  beyond what an updated system app receives.
- The hot switch changes only MiuiHome's data update (install the selected launcher or restore
  the registered system image) after the user changes the selection, on system_server's own
  identity. Back up launcher data before the first switch. A system_server crash loop, LSPosed
  safe mode or a removed `system` scope must be reported before further tests.

## Platform and authentication

- Android 16 may use launcher Java hooks. On Android 17+ the installed MiuiHome flavor decides:
  a code-less (native) MiuiHome keeps the APK native entry, registers no launcher-process Java
  hooks and removes inherited Java handles during hot reload; a dex (Java) MiuiHome uses the
  Android 16 launcher contract and Java hooks. An unknown flavor fails closed to native. The
  native entry is exclusive to `com.miui.home` and must stay inert in a Java launcher.
- Key launcher-contract decisions (accepted-input tokens, launcher state, arbiter broadcasts,
  status handshakes, contextual-search ownership) on the flavor and Android 17 SystemUI/Shell
  API differences on the SDK. Processes outliving a launcher switch re-resolve the flavor after
  MiuiHome package changes.
- SystemUI may create the launcher's `swipe-up` gesture monitor only for the exact MiuiHome UID
  of a Java MiuiHome on Android 17, on the default display, over the existing ISystemUiProxy
  binder. Never grant MONITOR_INPUT or other permissions to the launcher.
- POCO Java support is Android 16 only. Bind sender checks, resources and explicit targets
  to one selected launcher package per process generation; never trust both interchangeably.
- Authenticate explicit cross-process broadcasts by exact shared caller package and sending-UID
  ownership; bind state to its current owner/generation and commands to their immutable session.
  Missing, malformed, stale or ambiguous state grants no capability; do not trust arbitrary UIDs.
- Contextual-search platform calls belong to SystemUI on Android 16 and with a Java MiuiHome,
  and to MiuiHome with a native MiuiHome on Android 17+. Authenticate the Google provider
  callback independently against its exact package.
- Use the declared SDK/NDK toolchain and configured analysis tools. Prefer DexSQL for APK/DEX
  work; use only configured MCP servers for JADX/Ghidra, respecting global readiness rules.
  Do not enable disabled JADX without user direction or invoke local JADX/Ghidra as a fallback.

## Input and animation ownership

- SystemUI owns claimed side-gesture input, pilfering and native BackPanel decisions; Shell
  owns predictive navigation. Preserve Xiaomi's physical edge windows, redirect/exclusion
  policy and the authenticated matching accepted-DOWN boundary before claiming a stream.
- Preserve native cancellation, ordered release decisions, IME behavior and display policy.
  Do not revive the retired GestureStub/BackAnimationAdapter injection path, duplicate a
  touchable shield, replay/transfer rejected input or force a cancelled gesture to commit.
- Preserve Xiaomi OPEN interruption and the existing guarded BACK fallbacks. An accepted
  native merge must not also send BACK. Bind handoffs and suppression to the exact attempt;
  do not replace identity checks with process-wide timers, polling or distance-only commits.
- Use each state owner's executor/Looper. Never inspect or mutate Shell-owned animation
  maps, trackers or runner state from the input Looper. Stale owners cannot finish a successor.
- Prefer existing Shell/Xiaomi runners and live task Surfaces. Preserve prepare, invoke/cancel,
  post-commit and cleanup ownership; do not substitute screenshots, guessed Surface animation,
  swapped targets or early prepared-animation completion for a missing native path.
- Resolve new native/Dart hooks from the current mapped image and prove ABI, layout and call
  relationships. Do not introduce launcher-version offsets or guessed layouts. Ambiguity
  preserves original behavior and disables only the unresolved optional extension.
- Assembly observers preserve live registers and NZCV and make no Dart/C++ calls. Invalidate
  stale observations and drain active observers before changing their owner/configuration.

## Optional features and user state

- Disabled optional features preserve original behavior. Google optional features and
  predictive opt-in selections default off/empty; unreadable configuration grants no opt-in.
- CTS reuses Xiaomi's existing long-press admission and CTS helper/branch. Do not add a global
  MotionEvent hook, synthetic input or second bottom-gesture recognizer. MarkOnly completion
  alone is not feature permission. Home may change only the admitted `NavLongPress` action
  while CTS is enabled; preserve the original lookup/await and the user's saved gesture setting.
- Feature-query overrides are limited to the exact capability queries validated for the
  enabled CTS/Google feature. Preserve unknown queries and unsuccessful/error results.
  Do not hook consuming FnOnce shims, copy GPL implementations or hand-write voice Binder calls.
- Preserve capture consent and Google's own provider/capture/submission flows. Never forge
  capture tokens/screenshots, spoof device identity, globally enable Labs or edit Phenotype.
- Predictive opt-in changes only selected applications' current launch ActivityInfo through
  API-102 remote preferences and existing `system` scope; never mutate shared ApplicationInfo.
  Preserve metadata exclusions, stale-selection pruning and the incompatible-app warning.
- Preserve window-requested bar appearance and native gesture-line/indicator lifecycle.
  Visual options cannot change input ownership or native commit/cancel decisions.

## Hook lifecycle

- Keep `autoHotReload=true`. Hook IDs cover normal installation, old-handle replacement,
  presence tracking and missing-hook backfill together; failed replacement remains missing.
- During hot reload, detach owned monitors/windows, unregister receivers/listeners and
  invalidate generations and pending attempts before restoring explicitly supported state.
  No duplicate owner, monitor, receiver or callback may survive reload.
- Keep `system` scope and recover the real system-server ClassLoader across cold/hot load;
  do not rely on one lifecycle boolean. Access Shell/SystemUI implementation classes through
  their actual ClassLoader and cached reflection; compile-only stubs are for platform APIs.
- Java hot reload must never unload or replace the native entry. A new native payload takes
  effect only through the clean framework-owned spawner replacement above.
- The launcher switch listener, status receiver and Java-launcher swipe-up monitor belong to
  one generation; release them before hot reload and register them again afterwards.
- While the Java launcher runs as the redirected system image, its APK is the module's
  extracted library. Reboot soon after updating or removing the module.

## Verification and delivery

- Use Debug for iterative device tests and Release for final delivery. Run checks relevant to
  the changed behavior; broaden/repeat them only for a new change, failure or unresolved risk.
- Build every APK for handoff with `python3 scripts/build_variants.py`, which clean-builds
  the public flavor (never embeds the Java MiuiHome) and the private `-javahome-private`
  flavor together and verifies both; without a local Java MiuiHome APK it skips the private
  flavor and says so. Do not hand over a plain `assembleRelease` output or skip a failed
  check; use `--only` only when the user asks for a single flavor.
- Use the shared configured signing key for both flavors and keep keys and credentials out of
  source control. Native libraries stay stored and 16 KiB page-aligned (`zipalign -c -P 16 4`)
  for LSPosed's in-APK loading; only the private flavor extracts them for the Java MiuiHome.
- `versionCode` uses the Git commit count: rebuild after the final source commit before
  handing over an artifact. Preserve API-102 hot-reload verification after installation.
- Keep native test revisions in separate stashes. Sign commits after required validation;
  device behavior changes need real-device confirmation, not just compilation or counters.
- Formal side-handoff evidence requires exactly one fresh gesture in a captured session
  proving matching native accepted-DOWN, SystemUI acceptance/pilfer and Shell start. Capture
  any readiness warmup separately; visuals or a hook counter alone are not takeover proof.
- Use LSPosed/module diagnostics rather than direct `android.util.Log` writes.

## Documentation and artifacts

- Keep this file limited to durable boundaries. Do not append investigation timelines,
  version-specific recipes, dumps, method inventories or build logs.
- For changes to runtime behavior, consult only the relevant section of
  [runtime contracts](docs/runtime-contracts.md); routine unrelated edits need no full reread.
  Implementation details describe existing code, not extra permission requirements.
- Keep research evidence/history under numbered, topic-specific `reports/` directories;
  keep reusable contracts in versioned documentation or code/tests. Never commit `reports/`,
  proprietary binaries/decompilation, signing secrets or generated native/device outputs.
