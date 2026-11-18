# AGENTS.md

This LSPosed API-102 module restores Xiaomi/MIUI back gestures and optional system-UI behavior.

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
  owning root `hyos_spawner`, and verify Launcher parentage, current APK mapping/inode,
  native readiness and absence of new tombstones. Do not bypass a failed safety check.
- Do not use module-local marker files as native runtime gates, install a standalone native
  module/controller, or deploy while a retired standalone owner remains mapped.
- Recover native crash loops through the deployment script's whole-APK rollback and exact
  spawner replacement, followed by one standard HOME start. Never clear launcher data or
  manipulate RescueParty properties. Reinstalling the launcher requires the user's request;
  persistent SafeLauncher after rollback/HOME recovery must be reported before further tests.

## Platform and authentication

- Android 16 may use launcher Java hooks. Android 17+ must keep MiuiHome in static scope for
  the APK native entry, register no launcher-process Java hooks, and remove inherited Java
  handles during hot reload. The native entry is exclusive to `com.miui.home`.
- POCO Java support is Android 16 only. Bind sender checks, resources and explicit targets
  to one selected launcher package per process generation; never trust both interchangeably.
- Authenticate explicit cross-process broadcasts by exact shared caller package and sending-UID
  ownership; bind state to its current owner/generation and commands to their immutable session.
  Missing, malformed, stale or ambiguous state grants no capability; do not trust arbitrary UIDs.
- Contextual-search platform calls belong to SystemUI on Android 16 and MiuiHome on Android
  17+. Authenticate the Google provider callback independently against its exact package.
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

## Verification and delivery

- Use Debug for iterative device tests and Release for final delivery. Run checks relevant to
  the changed behavior; broaden/repeat them only for a new change, failure or unresolved risk.
- Before APK handoff, clean-build the requested variant, verify signing/alignment and use
  the shared configured signing key. Keep keys and credentials out of source control.
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
