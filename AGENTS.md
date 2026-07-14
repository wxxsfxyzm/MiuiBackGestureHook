# AGENTS.md

## Project Context

This repository is an LSPosed module for researching Xiaomi/MIUI back gesture behavior.

Current workspace:

```text
repository root (use the current working directory)
```

Previous jadx workspace:

```text
use the currently configured jadx MCP workspace; do not assume an absolute path
```

## Current Goal

The abandoned MiuiHome experiment is specifically the old GestureStub/
`BackAnimationAdapter` injection path. Standard launcher callback/runner registration
through Shell remains in scope for `TYPE_RETURN_TO_HOME`.

The current direction is SystemUI-first: keep gesture ownership and progress in SystemUI,
then restore the AOSP SystemUI/WM Shell back gesture pipeline.

Preserve the usable same-activity `TYPE_CALLBACK` baseline while restoring the remaining
remote-animation behavior. Restore `TYPE_CROSS_ACTIVITY` and `TYPE_CROSS_TASK` before
working on `TYPE_RETURN_TO_HOME`.

Preserve the Xiaomi-native in-app Activity OPEN interruption path alongside the AOSP
predictive-back path.

- Check for a reversible running Xiaomi OPEN transition before calling
  `BackAnimationController.onGestureStarted(...)`.
- Do not pause or manually animate the running OPEN animators; let Xiaomi finish or reverse
  them through its native OPEN/CLOSE merge path.
- Commit interruption gestures with a normal BACK. If OPEN has ended or Xiaomi rejects the
  merge, ordinary CLOSE fallback is expected.
- When no reversible OPEN exists, preserve the existing AOSP predictive-back path.
- Capture the transition/animator set on
  `DefaultTransitionHandler.startAnimation(...)`'s owner thread, verify animator state on
  `mAnimExecutor`, and publish a module-owned snapshot with immutable transition/animator
  identity and atomic lifecycle state. Do not read Shell's map or animator state from the
  input Looper.
- Tie duplicate BACK suppression to the current interruption attempt, controller, and
  `TransitionInfo`. It may consume at most one adjacent DOWN/UP pair after a successful
  merge; never use a process-wide time window.

Launcher-to-app OPEN animations are owned by MiuiHome's remote-animation path and do not
reach SystemUI `DefaultTransitionHandler.startAnimation(...)`. Preserve Xiaomi's native
launcher interruption path.

- Mirror `BackGestureBreakController`/`StateManager` native availability to SystemUI. Query
  it on the next main-Looper turn after the StateManager start callback, guarded by the
  animation identity and generation; the synchronous callback state is premature.
- Keep gesture input, the native `BackPanelController` indicator, pilfering, and the fixed
  `48dp` trigger threshold in SystemUI.
- Snapshot the active MiuiHome generation on `ACTION_DOWN`. Accept a commit command only if
  it still matches the active generation.
- Use explicit identity-sharing broadcasts in both directions. Validate the shared caller
  package and verify that the sending UID owns it.
- At commit, recheck native availability before `breakOpenAnim()`. An accepted command must
  not also send BACK. A missing receiver or explicit rejection gets exactly one ordinary
  BACK fallback; cancellation sends no command.
- When a reusable `CLOSE_TO_HOME` animation is retargeted in place to `OPEN_FROM_HOME`,
  adopt that running animation under a fresh generation only after verifying the native
  reused-close/open state. Preserve command-time validation and normal end cleanup.

Primary target process:

```text
com.android.systemui
```

Current static scope:

```text
com.android.systemui
com.miui.home
system
```

Keep scope minimal. Do not add target applications or further `system_server` cleanup or
compatibility hooks unless new SystemUI/server evidence requires them.

Keep MiuiHome `GestureStubView` initialization intact, but keep its side windows
non-touchable, empty their touch regions, and block `showGestureStub()`. Keep the Xiaomi
gesture-line progress callback blocked so progress remains on the SystemUI/AOSP path.

Same-activity and input rules:

- For `TYPE_CALLBACK`, call
  `BackNavigationInfo.disableAppProgressGenerationAllowed()` before the original
  `onBackNavigationInfoReceived(...)` body runs.
- Keep SystemUI native input-monitor ownership, early outward pointer pilfer at `8dp`, the
  fixed `48dp` trigger threshold, native `BackPanelController` dispatch, and release-time
  invoke/cancel.
- Use display width for callback progress. Do not restore the old fixed `220dp` distance.
- Do not move trigger ownership to the native panel or intercept
  `setTriggerBack(false)`.

Recents ownership rules:

- Track the launcher's existing overview state and task-launch exit signals in SystemUI.
- Mirror state through the explicit SystemUI-targeted identity-sharing broadcast. Validate
  that the sending UID owns `com.miui.home` and the shared caller package is exactly
  `com.miui.home`; do not trust the unprotected native fullscreen-state broadcast.
- Start Shell once on `ACTION_DOWN` and accept only `TYPE_CALLBACK`.
- For null or stale non-callback targets, clean Shell navigation, keep the native panel
  visual only, and leave the input stream unpilfered.
- Do not add focus polling, retries, delayed commits, synthetic input, launcher binder BACK
  transactions, or direct `RecentsContainer.onBackPressed()` calls.

Remote-animation rules:

- Restore the whole AOSP WM Shell behavior, not only `TYPE_CROSS_ACTIVITY`.
- Prefer restoring Shell registry/runner/adapter wiring before writing custom Surface
  animation code.
- For prepared remote animations, mark the tracker finished and call or wait for
  `startPostCommitAnimation()` so the runner receives cancel/invoke before navigation
  cleanup. Do not finish an active prepared animation directly from the overlay.
- A released gesture whose runner is waiting must remain finished and wait for animation
  start. Null navigation must cancel and clean with `finishBackNavigation(false)` without a
  fallback key.
- Do not swap closing/entering targets or transform leashes, and do not force
  alpha/visibility/layer order without new evidence for that exact fault.
- Use SystemUI's native `BackPanelController` indicator. Avoid Xiaomi/MiuiHome arrow paths
  and custom-drawn fallbacks except for native-indicator attachment diagnostics.
- Restore `TYPE_RETURN_TO_HOME` through the standard Shell-to-launcher callback/runner
  registration; do not revive adapter injection or hand-written launcher surface animation.

Do not use direct `android.util.Log` writes for module diagnostics; keep diagnostics in
LSPosed/module logs.

## AOSP 16 QPR0 Alignment Status

The authoritative AOSP reference revision is:

```text
tag: android-16.0.0_r1
commit: 99b01a65cc4c104933788b3143285ab6bae65827
```

Discover a suitable local checkout instead of hard-coding a machine-specific path. If it
is unavailable, fetch only the exact tag/projects/files needed. Checked-in reference
snippets are under `refs/aosp_back/shell/` and `refs/aosp_back/systemui/`; do not assume the
controller snippet is an exact r1 copy.

Use the currently configured jadx MCP workspace to confirm Xiaomi method names,
signatures, fields, transaction codes, and call sites before adding hooks. Jadx may render
`com.android.wm.shell` with a numeric package segment; runtime names must use
`com.android.wm.shell` without that segment.

Hidden API optimization boundary:

- Boot-classpath hidden APIs such as `android.window.BackNavigationInfo`,
  `BackTouchTracker`, and `BackMotionEvent` may use compile-only stubs.
- Do not add compile-only stubs or static type references for SystemUI/WM Shell
  implementation classes. They live in the SystemUI APK ClassLoader.
- Continue accessing those implementation classes through the real package ClassLoader and
  reflection. Cache resolved `Method`/`Field` objects for hot calls.

## Repository State

Important files:

```text
settings.gradle
build.gradle
app/build.gradle
hidden-api/build.gradle
hidden-api/src/main/java/android/view/
hidden-api/src/main/java/android/window/
app/src/main/AndroidManifest.xml
app/src/main/java/dev/codex/miuibackgesturehook/MiuiBackGestureHook.java
app/src/main/resources/META-INF/xposed/module.prop
app/src/main/resources/META-INF/xposed/java_init.list
app/src/main/resources/META-INF/xposed/scope.list
```

Compile-only dependencies:

```gradle
compileOnly "io.github.libxposed:api:102.0.0"
compileOnly project(":hidden-api")
```

The current module entry is:

```java
dev.codex.miuibackgesturehook.MiuiBackGestureHook
```

## LSPosed API 102 Notes

- `XposedModule` has a no-argument constructor.
- `PackageLoadedParam` exposes `getDefaultClassLoader()`, not `getClassLoader()`.
- Logging uses `log(int priority, String tag, String message)`.
- `XposedInterface.Chain` exposes `getArgs()`, `getArg(int)`, `getThisObject()`, and
  `proceed()`.

## Development Guidelines

- Prefer Java; the current scaffold is Java-only.
- Use the modern LSPosed/libxposed API already declared by the project.
- Keep hooks small and heavily logged.
- Keep scope minimal while testing.
- Keep behavior, diagnostics, and documentation changes in atomic commits.

## Useful Commands

Build:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Git status:

```powershell
git status --short
```
