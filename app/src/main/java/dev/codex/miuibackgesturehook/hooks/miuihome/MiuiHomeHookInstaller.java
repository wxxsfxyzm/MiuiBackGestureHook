package dev.codex.miuibackgesturehook.hooks.miuihome;

import java.util.Collections;

import dev.codex.miuibackgesturehook.hooks.runtime.MiuiHomeHookRuntime;

/** Installs hooks owned by the Xiaomi launcher process. */
public final class MiuiHomeHookInstaller {
    private MiuiHomeHookInstaller() {
    }

    public static void install(MiuiHomeHookRuntime hook, ClassLoader classLoader) {
        try {
            Class<?> gestureStubClass = Class.forName(
                    "com.miui.home.recents.GestureStubView", false, classLoader);
            hook.hookMiuiHomeGestureStubShow(gestureStubClass);
            Class<?> processorClass = Class.forName(
                    "com.miui.home.recents.GesturesBackTouchProcessor", false, classLoader);
            hook.hookMiuiHomeGestureInputArbiter(processorClass, gestureStubClass);
            Class<?> recentsContainerClass = Class.forName(
                    "com.miui.home.recents.views.RecentsContainer", false, classLoader);
            hook.hookMiuiHomeRecentsActualState(recentsContainerClass);
            Class<?> taskViewClass = Class.forName(
                    "com.miui.home.recents.views.TaskView", false, classLoader);
            hook.hookMiuiHomeRecentsTaskLaunch(taskViewClass);
            hook.hookMiuiHomeFullscreenState(classLoader);

            Class<?> breakControllerClass = Class.forName(
                    "com.miui.home.recents.BackGestureBreakController", false, classLoader);
            hook.hookMiuiHomeOpenBreakEnable(breakControllerClass);
            Class<?> listenerClass = Class.forName(
                    "com.miui.home.recents.anim.StateManager$windowElementAnimListener$1",
                    false, classLoader);
            hook.hookMiuiHomeOpenBreakAnimationStart(listenerClass);
            hook.hookMiuiHomeOpenBreakAnimationEnd(listenerClass);

            try {
                hook.hookMiuiHomeLauncherOpenSnapshotTargets(classLoader);
            } catch (Throwable throwable) {
                hook.logHookInstallationWarning("Failed to install Xiaomi OPEN target binding",
                        throwable);
            }
            hook.hookMiuiHomeReusedCloseOpen(classLoader);
            try {
                hook.hookMiuiHomeTransitionContinuity(classLoader, true, true, true);
            } catch (Throwable throwable) {
                hook.logHookInstallationWarning("Failed to install MiuiHome element continuity",
                        throwable);
            }
            try {
                hook.hookMiuiHomeUnifiedFinishEpoch(classLoader, true, true, true);
            } catch (Throwable throwable) {
                hook.logHookInstallationWarning("Failed to install MiuiHome finish-epoch hooks",
                        throwable);
            }
            try {
                hook.hookMiuiHomePermissionMerge(classLoader);
            } catch (Throwable throwable) {
                hook.logHookInstallationWarning("Failed to install MiuiHome permission merge",
                        throwable);
            }
            hook.hookMiuiHomeGeometryFrames(classLoader, true, true);
            try {
                hook.hookMiuiHomeTransitionSetupLeash(classLoader);
            } catch (Throwable throwable) {
                hook.logHookInstallationWarning(
                        "Failed to install MiuiHome transition geometry hook", throwable);
            }
            hook.hookMiuiHomeStartTransactionApply(Collections.emptySet());
            hook.hookMiuiHomeReturnHomeCloseInterruption(classLoader, true, true);
            hook.hookMiuiHomeReturnHomeSameIconParallel(classLoader);
            hook.hookMiuiHomeReturnHomeFreshOpen(classLoader);
            hook.hookMiuiHomeReturnHomeDirectCancel(classLoader);
            hook.hookMiuiHomeDrawerState(classLoader);
            hook.hookMiuiHomeEditingState(classLoader);
            hook.hookMiuiHomeReturnHomeInitialize(classLoader);
            hook.hookMiuiHomeReturnHomeLocalHandoff(classLoader);
            hook.hookMiuiHomeReturnHomeWallpaperCommands(classLoader, true, true);
            hook.logHookInstallationInfo("Enabled MiuiHome native side input arbitration"
                    + ", preservedGestureStubInitialization=true"
                    + ", preservesNativeRedirect=true"
                    + ", blocksLegacyGestureProcessor=true"
                    + ", requiresAcceptedInputToken=true"
                    + ", systemUiOwnsCommittedGesture=true"
                    + ", mirrorsActualRecentsState=true"
                    + ", mirrorsTaskLaunchExit=true"
                    + ", mirrorsAuthenticatedFullscreenState=true"
                    + ", mirrorsDrawerState=true"
                    + ", mirrorsLauncherEditingState=true"
                    + ", mirrorsLauncherOpenBreakState=true"
                    + ", repairsNonReusableSameIconOpen=true"
                    + ", usesStandardLauncherBackCallback=true");
        } catch (Throwable throwable) {
            hook.logHookInstallationError("Failed to install MiuiHome input arbitration",
                    throwable);
        }
    }
}
