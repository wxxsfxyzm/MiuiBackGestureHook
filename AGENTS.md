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
callback/runner. MiuiHome binds its closing target to one native
`WindowElement`/`RectFSpringAnim` that owns both predictive preview and Xiaomi's
post-commit CLOSE animation.

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
- Preserve a PermissionController merge only under the exact current immutable launcher-OPEN
  snapshot. The normal sequence is main OPEN `N`, Permission ActivityRecord OPEN `N+1`, then
  its matching CLOSE `N+2`; when WM collapses the permission OPEN, accept only the isolated
  Permission CLOSE `N+1` with no container or parent.
- If Xiaomi handler 0 accepts a Permission OPEN while its launcher-OPEN snapshot is being
  published, synchronously re-read the current snapshot once after the native handler returns.
  Do not add a delayed retry or broadly reroute handler-99 transitions.

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

Predictive opt-in rules:

- The module UI may let the user explicitly select applications for complete predictive-back
  opt-in. The default selection is empty and configuration failures fail closed. Clearly warn that
  incompatible single-Activity, WebView, Compose Navigation, or custom-router applications may
  skip their internal back stack and return directly to Home.
- Keep selected applications out of the static scope and do not hook their processes. Write the
  selection through the API-102 Xposed service remote preferences and read it only from the
  existing `system` scope.
- Inject only the selected package's current launch `ActivityInfo`: clear its explicit-disable bit
  and set its explicit-enable bit before the platform opt-in check. Do not modify the shared
  `ApplicationInfo`. Selection changes apply to newly created Activity instances after the target
  application is fully restarted.
- Omit launcher applications whose parsed application-level metadata already opts into predictive
  back; keep absent, disabled, Activity-only, and mixed declarations visible. Prune stale selections
  in the UI and independently ignore them in `system_server` so an invisible entry cannot override
  an Activity-level opt-out. Failure to inspect application metadata in the UI fails open; the
  corresponding `system_server` failure preserves the platform decision without forcing opt-in.

Hot-reload rules:

- Preserve `autoHotReload=true`. Treat hook IDs as lifecycle keys: whenever a hook is added,
  renamed, or retired, update its normal installation, old-handle replacement mapping,
  presence tracking, and missing-hook backfill together.
- When Xiaomi skips `NavigationBar` creation for FSG with the gesture line hidden, attach
  `EdgeBackGestureHandler` headlessly through an exact module-owned
  `NavBarHelper.NavbarTaskbarStateUpdater`. Do not create an invisible navigation-bar window,
  forge Taskbar initialization, or write lifecycle booleans directly. Reconcile ownership after
  default-display NavigationBar create/remove and navigation-mode changes; a real NavigationBar
  or Taskbar owns the native listener lifecycle whenever present.
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
- When system bars are hidden at `ACTION_DOWN`, continue normal AOSP/Shell back arbitration.
  Yield only if native display policy actually shows the navigation bar transiently during that
  same stream; cancel any module-started Shell gesture and do not also commit BACK. Do not blanket
  turn immersive application callbacks into a two-gesture path.
- Preserve MiuiHome's native redirect decision for disabled, non-touchable, and application
  exclusion states: a redirected stream never reaches the processor and therefore never emits
  an accepted token. When SystemUI does not claim a stream, do not synthesize, replay, or
  transfer it. An accepted stream with no ready SystemUI arbiter fails closed rather than
  reviving MiuiHome's deprecated gesture processor.
- Use display width for callback progress. Do not restore the old fixed `220dp` distance.
- Treat `48dp` as a necessary commit condition, never as permission to overwrite cancellation.
  On a Shell path, preserve the active tracker's ordered trigger after `BackPanelController`
  callbacks; distance may veto native `true` but must never turn native `false` back to `true`.
  On an OPEN-interruption path with no Shell tracker, accept only a proven terminal native-panel
  commit after release; missing or unknown panel state fails closed.
- Do not intercept `setTriggerBack(false)` or recompute a cancelled release from pointer distance.

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
- When NotificationShade or Control Center overlays launcher Home on the default display,
  snapshot Xiaomi's notification/QS `SysUiState` expansion flags on `ACTION_DOWN` and give
  that overlay precedence over launcher Overview, drawer, editing, and OPEN interruption.
  Probe Shell once and accept only `TYPE_CALLBACK`; unreadable, keyguard, bouncer, or
  screen-pinning state and null/non-callback targets fail closed and remain unpilfered. Do not
  invoke launcher-local back or shade-collapse APIs directly.
- Classify launcher Home by the exact top component resolved from MiuiHome's `HOME` or
  `SECONDARY_HOME` entry, not by package name alone. MiuiHome-owned settings and other sibling
  Activities use the ordinary SystemUI/Shell back path. On resolution failure, fall back to the
  previous package-level Home classification; only existing authenticated Overview, drawer, or
  launcher-OPEN state may still claim that stream.
- Mirror `BaseLauncher.isInEditing()` from MiuiHome's native back-status refresh through the same
  authenticated state channel. Publish only from the exact active Launcher instance; a callback
  posted by an old or destroyed Launcher must not overwrite its replacement. While the real
  Launcher is editing, including its launcher-settings bottom sheet, probe Shell once on
  `ACTION_DOWN`, accept only `TYPE_CALLBACK`, and otherwise leave the stream unpilfered. A new
  SystemUI arbiter generation must force MiuiHome to republish the current editing state; idle Home
  remains ignored.

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
- On a committed gesture with non-null navigation, publish
  `BackNavigationInfo.getFocusedTaskId()` to the Shell back transition observer before
  post-commit. A missing or explicitly cancelled runner may finish directly; a waiting
  runner must wait, and a ready runner must enter post-commit.
- Treat reflection failures while reading remote runner state as unknown. Keep the tracker
  finished and wait for Shell's animation timeout instead of finishing navigation early.
- A released gesture whose runner is waiting must remain finished and wait for animation
  start. Preserve AOSP's legacy null-navigation behavior only for an ordinary in-app stream
  whose exact MiuiHome accepted-DOWN identity, driver attachment epoch, and Shell controller
  are still current and whose own `onGestureStarted(...)` changed a proven-false
  `mReceivedNullNavigationInfo` into `true` while producing a null `BackNavigationInfo`. Keep
  that physical stream and the native panel; on the Shell executor, publish
  `INVALID_TASK_ID` to the back transition observer and inject
  exactly one BACK DOWN/UP pair only when the ordered native tracker commits and the fixed
  `48dp` condition also passes, then finish navigation with the same trigger. Cancellation
  sends no key. Launcher Overview, shade, drawer, editing and
  other callback-only probes, Shell-busy or stale state, unaccepted/redirected streams,
  reflection uncertainty, and every other null-navigation path still cancel and clean with
  `finishBackNavigation(false)` without a fallback key.
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
  Use the platform `BackProgressAnimator` for smoothed progress and drive the same Xiaomi
  `WindowElement` against only that closing leash; leave the opening Home target, alpha,
  and layer order untouched. Drive Xiaomi preview blur from the same smoothed progress
  rather than a separate or release-time snap.
- Correct Xiaomi's prepared/commit composition only for the exact single fullscreen
  standard task-to-Home shape. A valid prepared transition contains exactly the application
  and Home changes, optionally plus one taskless wallpaper change when Shell already represents
  the visible wallpaper. Use this same two-or-three-change definition everywhere prepared
  identity or composition is revalidated. After stock prepare accepts it, keep Home and wallpaper
  roles unchanged, reparent the departing task under the existing closing leash, and normalize
  only its prepared role to `CHANGE`. Require Home flags `0x28001` for the three-change shape
  with wallpaper and `0x28000` for the two-change shape without it; the application flags do not
  vary with wallpaper presence. Fail closed for every other shape.
- Keep both commit handoffs compositor-atomic. For an accepted standard `CLOSE` or `TO_BACK`
  merge, append only the matching closing-change reparent to the original still-unapplied start
  transaction at the accepted finish-callback boundary. For the exact rejected element-close
  shape, merge the matching prepared finish transaction into Xiaomi's existing native start
  transaction before its donor is applied and released. Never expose a prepared fullscreen
  restore in a separate transaction ahead of Xiaomi's task reparent and start geometry.
- Pass the original runner targets into Xiaomi's native closing provider and publish only
  the exact current geometry and corner radius through Xiaomi's own handoff status.
  Application pixels remain on the real closing task Surface; do not introduce a screenshot
  replacement, module-owned icon/window crossfade, forced alpha, or layer manipulation.
- Keep the preview `WindowElement` and its `RectFSpringAnim` running across commit. Standard
  CLOSE remains driven by its authenticated Shell signal. For the exact rejected element-close
  shape, enter Xiaomi's complete native closing provider only at the fully validated element
  transition boundary, then allow the native `CLOSE_TO_HOME -> CLOSE_TO_ELEMENT` retarget on
  that same animation; do not stop, replace, or restart the spring at commit.
- Hold that preview spring's natural end through Xiaomi's own
  `getSetAnimEndEnableCallbacks()` while the module is driving `CLOSE_TO_DRAG`. Restore the
  callbacks before cancellation; a successful native closing-provider call adopts and re-enables
  them. Cleanup must restore a still-held callback set, and an unexpectedly idle spring before
  either native provider is a failed handoff rather than permission to rearm another owner.
- Preserve Xiaomi's paired floating-icon lifecycle at that element boundary. Allow the provider
  to initialize the unique target-matching `FloatingIconView2`, suppress only that temporary
  view's drawing and visibility, and let Xiaomi's native reset/show/recycle path restore the real
  launcher icon. Do not set `RectFParams.ignoreIcon` or hide the source `AppIcon`.
- Once the exact Xiaomi CLOSE starts, retain the Shell runner and remote targets until its
  matching native end or a verified launcher-interruption boundary. Do not restore or
  release the preview Surface over a captured native animation.
- For one animation and `animTo` epoch, preserve the first exact pre-clear finish snapshot.
  A duplicate `StateManager` end callback after element/target cleanup must not overwrite
  that terminal identity or keep the Shell runner alive.
- Preserve Xiaomi's parallel CLOSE-to-OPEN path when an icon is clicked before CLOSE ends.
  Finish the old Shell runner only after Xiaomi cancels the old application Surface and
  accepts its `setToOld` boundary, before the new OPEN starts; do not cancel or wait for the
  old floating-icon tail. Route a non-reusable same-icon Local CLOSE only through Xiaomi's
  existing parallel branch under exact identity guards; never fabricate Recents state or a
  controller, or invoke the real-Recents reversal path. After that exact old-list boundary, a
  module-owned element without a native recent transition must not be reset and reused as the
  replacement OPEN. Correct `StateManager.isOldElementReuseful(...)` only when its original
  result is `true` and the same main-Looper launch turn still matches the StateManager, old
  element, animation identity, clicked View, old-list membership, CLOSE type, and completed
  surface cancellation. Preserve original `false` results and genuine desktop native reuse;
  let Xiaomi create the fresh element and request its normal remote OPEN.
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
- When exposing an opening predictive-back target, convert
  `BackWindowAnimationAdaptor.mTarget` with `WindowContainer.asTaskFragment()`, matching native
  remote-target creation. Do not substitute `getTaskFragment()`: a `Task` is itself the required
  `TaskFragment`, while that method is only a parent lookup for child containers.
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
  it does not implement AOSP `mQueuedTracker` semantics. Keep that exact physical stream
  suppressed across Shell cleanup until its own `ACTION_UP` or `ACTION_CANCEL`; never reopen or
  late-pilfer it after the navigation that caused the rejection finishes.
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
- Preserve the shared signing configuration: when complete local or environment credentials are
  available, debug and release must use the same configured key. Keep keystores and credentials
  ignored and never hard-code them in Gradle or source.

## Useful Commands

Build debug only unless the user explicitly requests a release artifact:

```powershell
.\gradlew.bat assembleDebug
```

Before handing an APK to the user, use a clean build for the requested variant so
incremental packaging cannot leave stale ZIP slack in the artifact:

```powershell
.\gradlew.bat clean assembleDebug
```

`versionCode` is derived from the Git commit count. After committing the final source, rebuild the
artifact before handoff; do not reuse an APK produced from the same source before that commit.

When the user explicitly requests release, use:

```powershell
.\gradlew.bat clean assembleRelease
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
