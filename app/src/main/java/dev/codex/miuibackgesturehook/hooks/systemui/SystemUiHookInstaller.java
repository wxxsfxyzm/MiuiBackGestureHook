package dev.codex.miuibackgesturehook.hooks.systemui;

import dev.codex.miuibackgesturehook.hooks.runtime.SystemUiHookRuntime;

/** Installs hooks owned by SystemUI and WM Shell. */
public final class SystemUiHookInstaller {
    private SystemUiHookInstaller() {
    }

    public static void install(SystemUiHookRuntime hook, ClassLoader classLoader) {
        try {
            hook.hookMiuiOverviewProxy(classLoader);
            hook.hookNavigationBarTransientAutoHide(classLoader);
            hook.hookNavigationBarTransientAppearance(classLoader);
            hook.hookStatusBarTransientAppearance(classLoader);
            hook.hookEdgeBackGestureHandler(classLoader);
            hook.hookNavigationBarControllerCreate(classLoader);
            hook.hookNavigationBarControllerRemove(classLoader);
            hook.hookNavigationBarControllerMode(classLoader);
            hook.hookShellBackAnimation(classLoader);
            hook.hookBackAnimationSendBackEvent(classLoader);
            hook.hookDefaultTransitionHandler(classLoader);
            hook.hookDefaultTransitionImplMerge(classLoader);
            hook.logHookInstallationInfo("Installed SystemUI AOSP back restoration hooks");
        } catch (Throwable throwable) {
            hook.logHookInstallationError("Failed to install SystemUI hooks", throwable);
        }
    }
}
