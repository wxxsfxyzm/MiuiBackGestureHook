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

Preserve the working `TYPE_CALLBACK`, `TYPE_CROSS_ACTIVITY`, `TYPE_CROSS_TASK`, and
`TYPE_RETURN_TO_HOME` paths. `TYPE_RETURN_TO_HOME` uses the standard Shell-to-launcher
callback/runner for predictive preview, then hands post-commit ownership to Xiaomi's
native CLOSE animation.

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

Hot-reload rules:

- Preserve `autoHotReload=true`. Treat hook IDs as lifecycle keys: whenever a hook is added,
  renamed, or retired, update its normal installation, old-handle replacement mapping,
  presence tracking, and missing-hook backfill together.
- In `onHotReloading(...)`, detach module-owned input monitors, unregister receivers, and
  invalidate pending snapshots/attempts before saving only the state that is explicitly
  restored. In `onHotReloaded(...)`, replace or neutralize every old hook and restore state
  without duplicating monitors, receivers, or callbacks.
- When restoring MiuiHome from an older non-touchable Stub build, clear the existing
  `FLAG_NOT_TOUCHABLE` in WMS on each Stub's View owner Looper, request layout and internal
  insets, then let `BaseRecentsImpl.adaptToTopActivity()` recompute native touchability policy.
  Invalidate input-arbiter generations across reload; a stale readiness or accepted-input
  token must never admit a gesture.
- For `system_server`, do not rely only on `HotReloadedParam.isSystemServer()`. Also treat
  `processName == "system"` or an existing `server_*` hook as system-server evidence, and
  recover the real package ClassLoader from an old hook executable or the normal resolver.
  Keep `system` in the static scope so `onSystemServerStarting(...)` can obtain the real
  system-server ClassLoader after a cold start.

Keep MiuiHome `GestureStubView` initialization, native side-window flags,
`showGestureStub()`/`hideGestureStub()`, touch regions, and DOWN-time
`RedirectionHelper.requestRedirect(...)` arbitration intact. Do not attach a duplicate
SystemUI touchable shield. Neutralize `GesturesBackTouchProcessor.onPointerEvent(...)` only
at its accepted-input boundary so the old GestureStub/`BackAnimationAdapter`, native arrow,
injection, and direct OPEN-break path cannot race SystemUI. Keep the Xiaomi gesture-line
progress callback blocked so a gesture claimed by SystemUI remains on the SystemUI/AOSP path.

Same-activity and input rules:

- For `TYPE_CALLBACK`, call
  `BackNavigationInfo.disableAppProgressGenerationAllowed()` before the original
  `onBackNavigationInfoReceived(...)` body runs.
- Keep the module-created SystemUI `InputMonitor` as a spy while MiuiHome's native
  `GestureStubView` remains the original DOWN target in its physical-pixel width and vertical
  touch band. MiuiHome must publish an explicit identity-sharing accepted-DOWN token carrying
  MotionEvent identity, display, edge, and the current SystemUI arbiter generation. SystemUI
  may start or pilfer only after the token exactly matches its pending spy-channel DOWN.
- Use a single `8dp` outward threshold to pilfer the accepted MiuiHome stream and start a
  deferred Shell navigation. Retain the fixed `48dp` trigger threshold, native
  `BackPanelController` dispatch, and release-time invoke/cancel.
- Preserve MiuiHome's native redirect decision for disabled, non-touchable, and application
  exclusion states: a redirected stream never reaches the processor and therefore never emits
  an accepted token. When SystemUI does not claim a stream, do not synthesize, replay, or
  transfer it. An accepted stream with no ready SystemUI arbiter fails closed rather than
  reviving MiuiHome's deprecated gesture processor.
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
- Mirror MiuiHome `LauncherState.ALL_APPS` through the same explicit identity-sharing
  broadcast. When the drawer is visible, probe Shell once on `ACTION_DOWN`, accept only its
  standard `TYPE_CALLBACK`, and otherwise leave launcher Home ignored and unpilfered.

Remote-animation rules:

- Restore the whole AOSP WM Shell behavior, not only `TYPE_CROSS_ACTIVITY`.
- Prefer restoring Shell registry/runner/adapter wiring before writing custom Surface
  animation code.
- Restore cross-activity and cross-task registry entries only from non-null backing Xiaomi
  Shell animation objects; do not replace missing objects with hand-written surface code.
- For prepared remote animations, mark the tracker finished and call or wait for
  `startPostCommitAnimation()` so the runner receives cancel/invoke before navigation
  cleanup. Do not finish an active prepared animation directly from the overlay.
- Run the complete release transaction on the Shell executor: synchronize the requested
  trigger, read the active tracker's actual trigger, update focused-task/tracker state,
  inspect the runner, and finish or enter post-commit there. The SystemUI input Looper must
  not read or mutate these Shell-owned fields directly.
- On a committed gesture, publish `BackNavigationInfo.getFocusedTaskId()` to the Shell back
  transition observer before post-commit. A missing or explicitly cancelled runner may
  finish directly; a waiting runner must wait, and a ready runner must enter post-commit.
- Treat reflection failures while reading remote runner state as unknown. Keep the tracker
  finished and wait for Shell's animation timeout instead of finishing navigation early.
- A released gesture whose runner is waiting must remain finished and wait for animation
  start. Null navigation must cancel and clean with `finishBackNavigation(false)` without a
  fallback key.
- Do not swap closing/entering targets or transform leashes, and do not force
  alpha/visibility/layer order without new evidence for that exact fault.
- Use SystemUI's native `BackPanelController` indicator. Avoid Xiaomi/MiuiHome arrow paths
  and custom-drawn fallbacks except for native-indicator attachment diagnostics.
- Restore `TYPE_RETURN_TO_HOME` through the standard Shell-to-launcher callback/runner
  registration. The confirmed entry is the Shell `IBackAnimation` binder in
  `LauncherProxyService`'s external-interface bundle; make MiuiHome consume that standard
  interface instead of reviving adapter injection or hand-written launcher surface
  animation. `SurfaceControl.Transaction` remains allowed inside an AOSP-aligned launcher
  runner registered through Shell.

Return-to-home rules:

- Treat the launcher callback and remote runner as independent Binder endpoints; do not
  assume delivery order. Retain only the current generation's latest start/progress and at
  most one terminal action, consume them exactly once when the runner arrives, and discard
  them even when its targets are invalid.
- Follow the platform `removeDepartTargetFromMotion()` split: use the `BackMotionEvent`
  departing target when the flag is false and the runner closing target when it is true.
  Show and transform only that closing leash through the platform `BackProgressAnimator`;
  leave the opening Home target, alpha, and layer order untouched. Drive Xiaomi preview
  blur from the same smoothed progress rather than a separate or release-time snap.
- Correct Xiaomi's prepared/commit composition only for the exact single fullscreen
  standard task-to-Home shape. After stock prepare accepts it, keep Home and wallpaper
  roles unchanged, reparent the departing task under the existing closing leash, and
  normalize only its prepared role to `CHANGE`. After an accepted commit merge, reparent
  only the matching closing change to that leash. Fail closed for every other shape.
- Pass the original runner targets into Xiaomi's native closing provider and publish only
  the exact current geometry and corner radius through Xiaomi's own handoff status.
  Application pixels remain on the real closing task Surface; do not introduce a screenshot
  replacement, module-owned icon/window crossfade, forced alpha, or layer manipulation.
- Once the exact Xiaomi CLOSE starts, retain the Shell runner and remote targets until its
  matching native end or a verified launcher-interruption boundary. Do not restore or
  release the preview Surface over a captured native animation.
- Preserve Xiaomi's parallel CLOSE-to-OPEN path when an icon is clicked before CLOSE ends.
  Finish the old Shell runner only after Xiaomi cancels the old application Surface and
  accepts its `setToOld` boundary, before the new OPEN starts; do not cancel or wait for the
  old floating-icon tail. Route a non-reusable same-icon Local CLOSE only through Xiaomi's
  existing parallel branch under exact identity guards; never fabricate Recents state or a
  controller, or invoke the real-Recents reversal path.
- Keep preview blur, shortcut-layer, and wallpaper state on MiuiHome's main Looper under
  exact generation and object ownership. Commit transfers that state to Xiaomi;
  cancellation restores only unchanged module-owned state after the application preview is
  fullscreen. Never overwrite an unrelated or replacement native spring.
- A prepared cancellation must continue through the launcher runner and Shell's normal
  restore transition. Clear only a proven stale close-request gate while the exact
  prepare-open token remains owned; never directly finish or clear the prepared animation.

System-server compatibility rules:

- Resolve window flags from `com.android.window.flags.Flags` first, Xiaomi's relocated
  `com.android.internal.hidden_from_bootclasspath.com.android.window.flags.Flags` second,
  and `android.window.flags.Flags` only as the legacy fallback. Unreadable migrate/unify
  flags default to `false`.
- Gate the `ScheduleAnimationBuilder.prepareTransitionIfNeeded(...)` skip through
  `unifyBackNavigationTransition()`. When that flag resolves to `false`, preserve the
  original `setLaunchBehind()` path; do not blanket-skip transition preparation. Never skip
  the unified `TYPE_RETURN_TO_HOME` prepare path; if `mIsLaunchBehind` or the relevant flag
  cannot be proven, preserve the original platform method.
- Navigation-done cleanup may call `clearBackAnimations(false)` only after a committed
  navigation when the handler is still composed and both prepared-open and prepared-close
  transition fields are null. Leave normal transition-owned cleanup untouched.
- Change a committed return-home window from `USE_OPACITY` to `ALLOW` only when the original
  mode is `USE_OPACITY`, its standard Activity is no longer visible-requested and cannot
  receive touch, the last back type is `TYPE_RETURN_TO_HOME`, and ownership is proven by
  either `shouldPauseTouch(activity)` or the composed matching prepared-close target.
  Reflection failure preserves the platform result.
- Do not obtain launcher touch-through by fabricating a Recents input consumer/controller,
  forwarding or replaying MotionEvents, or using a timer or process-wide token.
  Cancellation, finish, replacement, and launcher OPEN must invalidate the server-owned
  predicate naturally.

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

The current implementation is AOSP-aligned but is not a completely stock AOSP input
pipeline:

- A module-created SystemUI `InputMonitor` and driver own claimed gesture processing instead
  of routing the whole stream through stock
  `EdgeBackGestureHandler -> BackAnimationImpl.onMotionEvent(...)`. MiuiHome's native
  `GestureStubView`, not a module-created SystemUI shield, prevents InputDispatcher from
  targeting the application with the original DOWN inside the native band.
- Recents still starts Shell once on `ACTION_DOWN` so a null or stale non-callback target
  can remain unpilfered. The app drawer follows the same callback-only launcher probe.
  All paths wait for a matching MiuiHome accepted-DOWN token; ordinary in-app candidates
  pilfer and start deferred Shell navigation together at `8dp`. Native GestureStub touch
  regions and redirect state remain authoritative while its legacy processor stays
  neutralized. When Shell is busy, the current behavior suppresses the new SystemUI gesture;
  it does not implement AOSP `mQueuedTracker` semantics.
- Release handling reflectively reproduces the relevant `onGestureFinished()` transaction
  but does not call the complete private AOSP method. Preserve the Shell-executor ownership
  and runner-state rules above unless new evidence justifies changing this boundary.

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
reports/
```

Compile-only dependencies:

```gradle
compileOnly "io.github.libxposed:api:102.0.0"
compileOnly project(":hidden-api")
```

LSPosed metadata:

```text
minApiVersion=102
targetApiVersion=102
staticScope=true
autoHotReload=true
```

The current module entry is:

```java
dev.codex.miuibackgesturehook.MiuiBackGestureHook
```

## LSPosed API 102 Notes

- `XposedModule` has a no-argument constructor.
- Use the API 102 lifecycle callbacks already implemented by the module:
  `onModuleLoaded(...)`, `onPackageLoaded(...)`, `onSystemServerStarting(...)`,
  `onHotReloading(...)`, and `onHotReloaded(...)`.
- `PackageLoadedParam` exposes `getDefaultClassLoader()`, not `getClassLoader()`.
- Logging uses `log(int priority, String tag, String message)`.
- `XposedInterface.Chain` exposes `getArgs()`, `getArg(int)`, `getThisObject()`, and
  `proceed()`.

## Development Guidelines

- Prefer Java; the current scaffold is Java-only.
- Use the modern LSPosed/libxposed API already declared by the project.
- Keep hooks small and heavily logged.
- Keep scope minimal while testing.
- Keep `AGENTS.md` limited to goals, operating boundaries, preserved invariants,
  prohibited approaches, and development workflow. Do not use it as an implementation
  report, experiment timeline, log summary, or version changelog.
- Put implementation notes, reverse-engineering evidence, experiment results, and
  historical findings under a topic-specific directory in `reports/`.
- Number experiment directories chronologically as
  `reports/NNN-short-topic/README.md`, starting at `001`. Once assigned, do not renumber or
  reuse a number; later work on the same experiment updates its existing report.
- Keep behavior, diagnostics, and documentation changes in atomic commits.

## Useful Commands

Build debug only unless the user explicitly requests a release artifact:

```powershell
.\gradlew.bat assembleDebug
```

Check debug APK metadata:

```powershell
jar tf app\build\outputs\apk\debug\app-debug.apk | Select-String -Pattern 'META-INF/xposed|classes\d*\.dex|AndroidManifest.xml'
```

Check that the debug APK is from the current build:

```powershell
Get-Item app\build\outputs\apk\debug\app-debug.apk | Select-Object FullName,Length,LastWriteTime
```

If `jar` is not on `PATH`, resolve it from the active Java runtime:

```powershell
$jar = Join-Path (Split-Path (Get-Command java).Source -Parent) 'jar.exe'
& $jar tf app\build\outputs\apk\debug\app-debug.apk | Select-String -Pattern 'META-INF/xposed|classes\d*\.dex|AndroidManifest.xml'
```

Git status:

```powershell
git status --short
```
