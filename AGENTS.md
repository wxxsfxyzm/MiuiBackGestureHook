# AGENTS.md

## Project Context

This repository is an LSPosed module for researching Xiaomi/MIUI back gesture behavior.

Current workspace:

```text
D:/code/miui-back-gesture-hook
```

Previous jadx workspace:

```text
D:/code/jadx
```

## Current Goal

Historical AOSP gesture restoration research is archived under
`reports/restore-gesture/`. New predictive-back opt-in research is recorded under
`reports/enable-predictive/`.

The active experiment is a predictive-back opt-in manager. Initially force-enable
only `com.android.settings` by controlling the framework's canonical
`WindowOnBackInvokedDispatcher.isOnBackInvokedCallbackEnabled(...)` decision in both
the app process and system_server. Do not build the management UI yet.

The old MiuiHome-side experiment is abandoned.

The current direction is SystemUI-first: find and undo the Xiaomi path where SystemUI delegates gesture/back progress handling to MiuiHome, then restore the AOSP SystemUI/WM Shell back gesture pipeline.

The same-activity
`TYPE_CALLBACK` gesture baseline is currently usable. Preserve that baseline while researching and restoring the remaining AOSP WM Shell remote-animation behavior.

Primary target process:

```text
com.android.systemui
```

Do not reintroduce MiuiHome `BackAnimationAdapter` injection, MiuiHome hand-written
`SurfaceControl.Transaction` animations, or system_server cleanup experiments unless the user explicitly asks to revisit them.

Current remote-animation goal:

- Restore the whole AOSP WM Shell back animation behavior, not only `TYPE_CROSS_ACTIVITY`.
- Use local AOSP reference source at `D:/code/aosp-windowmanager`.
- Checked-in AOSP reference snippets are consolidated under `refs/aosp_back/`, split into `shell/` and `systemui/`.
- Prefer restoring Shell registry/runner/adapter wiring before writing custom Surface animation code.
- `TYPE_CROSS_ACTIVITY` and `TYPE_CROSS_TASK` can be restored from existing Xiaomi Shell animation objects if they exist.
-

`TYPE_RETURN_TO_HOME` depends on launcher registering a back-to-launcher callback through Shell; do not fabricate it without confirming the MiuiHome/SystemUI registration path.

## Current Findings

The loaded Xiaomi code contains AOSP-style SystemUI and WM Shell back components under jadx-renamed packages:

```text
com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler
com.android.wm.shell.back.BackAnimationController
com.android.wm.shell.back.ShellBackAnimationRegistry
com.android.wm.shell.back.CrossActivityBackAnimation
com.android.wm.shell.back.DefaultCrossActivityBackAnimation
```

In jadx output these WM Shell classes may appear as:

```text
com.android.p190wm.shell.back.*
```

Use runtime class names without the jadx numeric package segment in module code:

```text
com.android.wm.shell.back.*
```

The Xiaomi-specific SystemUI bridge to the launcher is:

```text
com.android.systemui.recents.MiuiOverviewProxy
```

Its binder descriptor is:

```text
com.miui.systemui.shared.recents.IMiuiSystemUiProxy
```

Known transaction:

```text
4 -> onGestureLineProgress(float)
```

Current hook blocks this Xiaomi gesture-line progress callback so gesture progress can remain on the SystemUI/AOSP path.

The current module also prevents MiuiHome from adding/showing its back stub window. This is only to keep gesture ownership in SystemUI; it is not the old MiuiHome predictive-back adapter experiment.

The AOSP Shell path to verify in logs is:

```text
EdgeBackGestureHandler.setBackAnimation(...)
EdgeBackGestureHandler.updateIsEnabled()
BackAnimationController.onGestureStarted(...)
BackAnimationController.onThresholdCrossed()
BackAnimationController.onBackNavigationInfoReceived(...)
BackAnimationController.startSystemAnimation()
BackAnimationController.finishBackNavigation(...)
ShellBackAnimationRegistry.updateSupportedAnimators()
```

Current same-activity predictive back finding:

```text
BackNavigationInfo type=4 -> TYPE_CALLBACK
```

For `TYPE_CALLBACK`, Xiaomi/AOSP `BackAnimationController.dispatchOnBackProgressed(...)` suppresses SystemUI-dispatched progress while
`BackNavigationInfo.isAppProgressGenerationAllowed()` is true. That works only when the app receives the raw touch stream and generates progress itself. Because this module uses a SystemUI-owned native input monitor and pilfers the gesture stream, the app does not reliably receive that stream. The module therefore calls:

```text
BackNavigationInfo.disableAppProgressGenerationAllowed()
```

when `BackAnimationController.onBackNavigationInfoReceived(...)` receives `TYPE_CALLBACK`, forcing SystemUI/WM Shell to dispatch
`onBackProgressed` to the app callback.

This must happen before the original `onBackNavigationInfoReceived(...)` body runs. If it happens after
`chain.proceed()`, Shell can still make its initial
`isAppProgressGenerationAllowed()` decisions on the old value, which breaks stricter clients such as Compose predictive-back handlers.

Progress distance mapping:

- Do not use a short fixed visual distance such as `220dp` for app callback progress.
- Current known-good logic baseline is SystemUI native input monitor ownership, early pointer pilfer at `8dp`, fixed
  `48dp` trigger threshold, native `BackPanelController` event dispatch, and release-time invoke/cancel.
- Only the progress denominator should differ from that baseline: use display width instead of
  `220dp`, while keeping the same cancel/commit state machine.
- Do not reintroduce the later v22/v23 experiments that moved trigger ownership to the native panel or intercepted
  `setTriggerBack(false)`; those caused wrong cancellation behavior.

Remote animation registry finding:

- AOSP
  `ShellBackAnimationRegistry` constructor has slots for cross-activity, cross-task, dialog-close, customize-activity, and return-to-home animations.
- The Xiaomi `ShellBackAnimationModule.provideBackAnimationRegistry(...)` found through jadx currently passes only three Shell animations.
- Current module version restores missing registry definitions only when the backing Xiaomi animation object already exists, starting with
  `TYPE_CROSS_ACTIVITY=2` and `TYPE_CROSS_TASK=3`.
- Current logs include registry definition keys, supported animator list, default/custom/cross-task animation objects, and
  `BackAnimationAdapter` supported animators.
- For prepared remote animations, do not finish the gesture by directly calling
  `invokeOrCancelBack(tracker)` from the overlay. AOSP release flow marks the tracker finished and calls
  `startPostCommitAnimation()`, which dispatches `onBackCancelled`/
  `onBackInvoked` to the animation runner callback first. Bypassing that leaves cross-activity leashes transformed when cancellation occurs.
- Current cross-activity logs show `TYPE_CROSS_ACTIVITY=2`, `prepareRemoteAnimation=true`, and `DefaultCrossActivityBackAnimation` with
  `closingTarget mode=1 order=93` and
  `enteringTarget mode=0 order=90`. On paper that matches AOSP target assignment, but the user visually observes the top activity playing the lower/entering animation. Next diagnostics should compare
  `BackMotionEvent.getDepartingAnimationTarget()` identity against `closingTarget` and `enteringTarget`.
- v30 logs confirmed `BackMotionEvent.getDepartingAnimationTarget()` identity matches `closingTarget`, not
  `enteringTarget`. The target mode assignment is therefore not directly reversed.
- v31 changes only the overlay start X from fixed edge
  `0/displayWidth` to the real touch down X, preserving the v24 trigger/cancel state machine. This should make remote animation
  `BackMotionEvent.touchX` match AOSP input semantics more closely.
- v31 logs confirmed real touch X is now used, but the user still visually observes the top activity playing the lower/entering animation.
- v32 forced `enteringTarget.leash` below `closingTarget.leash` with `SurfaceControl.Transaction.setRelativeLayer(entering, closing, -1)` after
  `cross_activity_startBackAnimation`; logs confirmed the code ran, but the user saw no improvement. This SystemUI-side layer patch was removed in v33 and should not be reintroduced.
- AOSP `BackNavigationController.AnimationHandler.initiate(...)` only calls `promoteToTFIfNeeded(close, open)` when
  `Flags.migratePredictiveBackTransition()` is true. Xiaomi's decompiled server code calls
  `promoteToTFIfNeeded` unconditionally for activity switches, and the observed remote targets are
  `TaskFragment{...} - animation-leash of predict_back`. v33 adds a system_server hook for
  `BackNavigationController$AnimationHandler.promoteToTFIfNeeded(...)`: when
  `android.window.flags.Flags.migratePredictiveBackTransition()` is false, return
  `Pair(close, open)` to preserve AOSP behavior and avoid TaskFragment promotion.
- The static scope must include `system`; otherwise
  `onSystemServerStarting(...)` will not reliably provide the real system_server classloader. If only hot reload is used after adding a new system_server hook, check logs for
  `Resolved system_server classloader` and `Hooked BackNavigationController promoteToTFIfNeeded`.
- After reboot, v33 successfully installed the system_server hook and intercepted
  `promoteToTFIfNeeded`, but the initial flag lookup used the wrong package (
  `android.window.flags.Flags`) and therefore defaulted to true. The correct AOSP/server flag class is
  `com.android.window.flags.Flags`; if both lookups fail, default to false to preserve pre-migration AOSP behavior.
- v33 after hot reload bypassed TaskFragment promotion successfully: cross-activity remote targets became
  `ActivityRecord{...AppDetailActivity}` for closing and
  `ActivityRecord{...MainActivity}` for entering. The user observed the previous dimming problem improved, but visually the lower activity still appears to be receiving the top/closing animation.
- v34 swapped `closingTarget` and `enteringTarget` immediately before
  `cross_activity_startBackAnimation`. The user reported the animation direction became correct, proving the transform-to-leash mapping is effectively reversed, but only the top layer was visible and the lower activity appeared only after the top exited. v35 kept the swap and forced the swapped closing target above the swapped entering target with
  `SurfaceControl.Transaction.setRelativeLayer(...)`; the user still saw only the top layer, so the missing lower activity is not just layer order.
- v36 removed target-field swapping and instead hooked
  `CrossActivityBackAnimation.applyTransform(...)` to swap only the leash argument. The user reported the original top-page offset returned and the lower page still was not visible, so do not continue that direction.
- v37 removed the transform swap and kept the stable server-side TaskFragment bypass, then forced both cross-activity leashes visible with alpha=1 at
  `cross_activity_startBackAnimation` start. The user reported the original problem remained: the top layer still appears to play the lower activity animation. Stop adding visual patches at this layer.
- v38 is diagnostics-only on top of the stable server-side TaskFragment bypass. It removes v37 visibility forcing and hooks
  `CrossActivityBackAnimation.applyTransform(...)` only to log whether each rect/alpha is applied to the original `closingTarget.leash` or
  `enteringTarget.leash`. Use these logs to determine whether Shell transform mapping is reversed or whether server-side target leash contents are wrong.
- v38 logs showed Shell transform mapping is not reversed: `closingTarget` (`AppDetailActivity`) receives closing rect/alpha and
  `enteringTarget` (
  `MainActivity`) receives entering rect/alpha. If the visual still looks reversed, investigate server-side animation leash creation and actual surface contents rather than Shell transform assignment.
- v39 adds server-side diagnostics only: hook `BackWindowAnimationAdaptor.startAnimation(...)` and
  `SurfaceAnimator.createAnimationLeash(...)` for predictive back (
  `type=256`) to log animatable target, original surface, animation leash parent, captured leash, and resulting
  `RemoteAnimationTarget`. This is to determine whether system_server reparented the wrong surface into an otherwise correctly named remote target leash.
- v39 logs show system_server did not swap cross-activity targets or reparent the wrong surface: `AppDetailActivity` is
  `isOpen=false/mode=1` with its own ActivityRecord surface, and `MainActivity` is
  `isOpen=true/mode=0` with its own ActivityRecord surface. Shell also applies closing rect/alpha to AppDetail and entering rect/alpha to Main. The remaining suspected failure is opening Activity visibility/preview exposure or parent-layer state, not transform assignment.
- v40 adds visibility-chain diagnostics only: hook `ScheduleAnimationBuilder.applyPreviewStrategy(...)`,
  `WindowContainer.enforceSurfaceVisible(...)`, and
  `ActivityRecord.setVisibility(boolean)` around predictive back. Use the next log to verify whether the entering activity is actually forced visible and whether a windowless/starting surface path is involved.
- v40 logs show the entering `MainActivity` is explicitly `setVisibility(true)` and passed to
  `WindowContainer.enforceSurfaceVisible(...)`, but its animation leash parent remains its own `TaskFragment`. AOSP
  `BackNavigationController.AnimationHandler.createAdaptor(...)` contains an activity-switch workaround for opening
  `ActivityRecord` targets: call `activity.getTaskFragment().updateOrganizedTaskFragmentSurface()` and
  `transaction.show(fragment.mSurfaceControl)` before `activity.startAnimation(...)`. v41 restores that behavior in the
  `BackWindowAnimationAdaptor.startAnimation(...)` hook for `isOpen=true` targets.
- v41 logs confirm the opening TaskFragment visibility restoration ran successfully:
  `Forced opening TaskFragment visible for predictive back` is logged for `MainActivity` and `TaskFragment{34833b}` before
  `WindowContainer.enforceSurfaceVisible(...)`. No exception was logged from the TaskFragment update/show path.
- v41 did not fix the visual issue. v42 adds system_server-only
  `SurfaceControl.Transaction` layer diagnostics for recent predictive-back windows, logging only calls involving `TaskFragment`,
  `ActivityRecord`, `Task=`, `predict_back`, or `animation-leash` surfaces. Inspect `setLayer`, `setRelativeLayer`, `reparent`, `show`,
  `hide`, and `setAlpha` ordering to determine whether TaskFragment parent surfaces are ordered incorrectly.
- v42 logs show the real conflict: `SurfaceAnimator.createAnimationLeash(...)` first reparents both ActivityRecord surfaces into their
  `predict_back` animation leashes, but immediately afterward the transition path reparents the same ActivityRecord surfaces back under their TaskFragments and creates
  `Transition Root #...: TaskFragment{...}`. This empties or bypasses the remote animation leashes that SystemUI animates. v43 therefore hooks
  `ScheduleAnimationBuilder.prepareTransitionIfNeeded(...)` and skips it when
  `migratePredictiveBackTransition=false`, restoring the old remote-animation-only path instead of the unified transition path that requires AOSP Shell
  `BackTransitionHandler`.
- v43 hot reload did not install the new `prepareTransitionIfNeeded` hook because
  `param.isSystemServer()` was not reliable in the hot-reload callback even though `process=system` and existing
  `server_*` hooks were replaced. v44 fixes server hook hot-reload installation by treating `processName=="system"` or existing
  `server_*` hook IDs as sufficient to install missing server hooks.
- Do not use direct `android.util.Log` logcat writes for module diagnostics; keep diagnostics in LSPosed/module logs under `logs/`.

Current AOSP indicator direction:

- Use SystemUI's native `BackPanelController` plugin when available.
- Feed it from the SystemUI native input monitor.
- Avoid Xiaomi/MiuiHome arrow drawing paths.
- Avoid custom-drawn fallback indicators unless debugging native indicator attachment.

## AOSP 16 QPR0 Alignment Status

The authoritative local AOSP reference is now:

```text
D:/code/aosp-windowmanager/base
tag: android-16.0.0_r1
commit: 99b01a65cc4c104933788b3143285ab6bae65827
```

Do not treat the previously checked-out 2025-03 `main` snapshot, or the current
checked-in `refs/aosp_back/shell/BackAnimationController.java`, as an exact Android
16 QPR0 copy. The checked-in controller snippet has known differences from r1.

The current implementation is not a completely native AOSP input pipeline. Its
remote-animation completion sequence is substantially aligned with Android 16 QPR0,
but the following module-specific differences remain:

- Input is owned by a module-created SystemUI `InputMonitor` and a custom
  `SystemUiBackInputOverlay`, rather than flowing entirely through the stock
  `EdgeBackGestureHandler -> BackAnimationImpl.onMotionEvent(...)` path.
- The module pilfers on `ACTION_DOWN` to prevent the MiuiHome indicator from briefly
  appearing. AOSP QPR0 normally starts the gestural Shell path on a later MOVE and
  pilfers according to its threshold state.
- When Shell is still busy, the module pilfers and silently suppresses the new
  gesture. AOSP supports a second gesture through `mQueuedTracker`; do not describe
  the current suppression behavior as native AOSP queuing.
- Release handling reflectively marks the tracker `FINISHED`, checks the backing
  `BackAnimationRunner.mWaitingAnimation`, and starts or waits for post-commit. It
  reproduces the relevant QPR0 behavior but does not directly execute the complete
  private AOSP `onGestureFinished()` method.
- `TYPE_CALLBACK` still calls
  `BackNavigationInfo.disableAppProgressGenerationAllowed()` because the module-owned
  input monitor pilfers the app touch stream.
- Callback progress uses display width, while the commit threshold remains the
  module's fixed `48dp` stable baseline.
- Xiaomi registry definitions and the system_server compatibility hooks for
  TaskFragment promotion/unified predictive-back transitions remain module-specific.
- The MiuiHome gesture-line progress binder callback remains blocked.

Hidden API optimization boundary:

- Compile-only stubs for boot-classpath hidden APIs such as
  `android.window.BackNavigationInfo`, `BackTouchTracker`, and `BackMotionEvent`
  have been tested successfully. They can be referenced directly because the
  module ClassLoader delegates these framework classes to the boot classpath.
- Do not add a normal compile-only stub and static type reference for
  `com.android.wm.shell.back.BackAnimationController` or other SystemUI/WM Shell
  implementation classes. Those classes live in the SystemUI APK ClassLoader,
  not the module or boot ClassLoader. The v60 experiment compiled but failed at
  runtime with `NoClassDefFoundError` from the module ClassLoader even though the
  object existed in SystemUI.
- Continue accessing SystemUI/WM Shell implementation classes through the real
  package ClassLoader and reflection. Optimize hot calls by resolving and caching
  `Method`/`Field` objects, rather than introducing static Shell type references.
- The failed v60 Shell-controller-stub experiment was not committed. The stable
  committed baseline is v59 (`91776ee`).

The currently aligned remote-animation behavior includes:

- A remote gesture released while its runner is waiting leaves the tracker
  `FINISHED` and waits for remote animation start instead of starting post-commit
  prematurely.
- Runner completion reaches `onBackAnimationFinished()` and then
  `finishBackNavigation(...)`; the old repeated 2-second timeout path is no longer
  the normal completion path.
- Null navigation is cancelled and cleaned with `finishBackNavigation(false)`; it
  does not continue into a visual commit or inject a fallback back key.
- Default cross-activity post-commit lasts about `450ms`, matching AOSP 16 QPR0
  `POST_COMMIT_DURATION`. During that transition WM still owns input, so the newly
  exposed activity is not clickable until animation completion. This is expected
  AOSP behavior, not the former 2-second stuck state.

Server cleanup finding after v59:

- Logs spanning a SystemUI restart showed the old SystemUI completing remote
  animations normally, followed by the new SystemUI receiving
  `BackNavigationInfo=null` for every gesture. This is consistent with
  `BackNavigationController.isMonitoringFinishTransition()` remaining true in
  system_server even though the local Shell controller was recreated.
- Android 16 QPR0 does not skip all of
  `ScheduleAnimationBuilder.prepareTransitionIfNeeded(...)` when unified back
  transitions are disabled; it can still create a prepare-back transition used
  by the normal server cleanup chain. The module's v43 blanket skip is therefore
  not a complete QPR0 reproduction.
- v61 keeps the v43 skip to avoid the previously confirmed Xiaomi surface
  reparent conflict only when Xiaomi's `unifyBackNavigationTransition()` is true;
  when it is false, allow the original `setLaunchBehind()` path. Xiaomi's compiled
  navigation-done method is named `lambda$startBackNavigation$4(Bundle,int)` even
  though JADX displays it as `onBackNavigationDone(...)`. v61 adds a narrow
  completion cleanup there: after a committed navigation, call
  `clearBackAnimations(false)` only when the
  handler is still composed and both prepared-open and prepared-close transition
  fields are null. Normal transition-owned cleanup is left untouched.

## Repository State

Important files:

```text
settings.gradle
build.gradle
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/java/dev/codex/miuibackgesturehook/MiuiBackGestureHook.java
app/src/main/resources/META-INF/xposed/module.prop
app/src/main/resources/META-INF/xposed/java_init.list
app/src/main/resources/META-INF/xposed/scope.list
```

API dependency:

```gradle
compileOnly "io.github.libxposed:api:102.0.0"
```

LSPosed metadata:

```text
minApiVersion=102
targetApiVersion=102
staticScope=true
autoHotReload=true
```

Current static scope:

```text
com.android.systemui
system
```

The current module entry is:

```java
dev.codex.miuibackgesturehook.MiuiBackGestureHook
```

Current build marker:

```text
systemui-aosp-back-v70-clear-overview-on-back
```

## LSPosed API 102 Notes

API 102 facts used in this scaffold:

- `XposedModule` has a no-arg constructor.
- Override `onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param)`.
- Override `onPackageLoaded(XposedModuleInterface.PackageLoadedParam param)`.
- `PackageLoadedParam` exposes `getDefaultClassLoader()`, not `getClassLoader()`.
- Logging uses `log(int priority, String tag, String message)`.
- `XposedInterface.Chain` exposes `getArgs()`, `getArg(int)`, `getThisObject()`, and `proceed()`.

## Development Guidelines

- Prefer Java for now; current scaffold is Java-only.
- Use modern LSPosed/libxposed API 102.
- Keep hooks small and heavily logged.
- First target `com.android.systemui`.
- Keep scope minimal while testing.
- Do not hook `system_server` unless SystemUI evidence shows the AOSP Shell path is blocked by framework/server behavior.
- Use jadx MCP to confirm method names and transaction codes before adding new hooks.

## Useful Commands

Build:

```powershell
.\gradlew.bat assembleDebug
```

Check APK metadata:

```powershell
jar tf app\build\outputs\apk\debug\app-debug.apk | Select-String -Pattern 'META-INF/xposed|classes.dex|AndroidManifest.xml'
```

If `jar` is not on `PATH`, resolve it from the active Java runtime:

```powershell
$jar = Join-Path (Split-Path (Get-Command java).Source -Parent) 'jar.exe'
& $jar tf app\build\outputs\apk\debug\app-debug.apk | Select-String -Pattern 'META-INF/xposed|classes.dex|AndroidManifest.xml'
```

Git status:

```powershell
git status --short
```
