package dev.codex.miuibackgesturehook.hooks.systemserver;

import dev.codex.miuibackgesturehook.hooks.runtime.SystemServerHookRuntime;

/** Installs hooks owned by system_server. */
public final class SystemServerHookInstaller {
    private SystemServerHookInstaller() {
    }

    public static void install(SystemServerHookRuntime hook, ClassLoader preferredLoader) {
        try {
            ClassLoader classLoader = hook.resolveSystemServerClassLoader(preferredLoader);
            if (classLoader == null) {
                hook.logHookInstallationError(
                        "Unable to find system_server classloader for back navigation", null);
                return;
            }
            hook.hookTaskFragmentPromotionCompatibility(classLoader);
            hook.hookBackNavigationDoneCleanup(classLoader);
            hook.hookPredictiveBackOptInMetadata(classLoader);
            hook.hookSecuritySidebarTransientBars(classLoader);
            hook.hookBackWindowStartAnimation(classLoader);
            hook.hookScheduleAnimationPrepareTransition(classLoader);
            hook.hookReturnHomeTouchOcclusion(classLoader);
            hook.logHookInstallationInfo("Installed system_server back navigation hooks");
        } catch (Throwable throwable) {
            hook.logHookInstallationError("Failed to install system_server hooks", throwable);
        }
    }
}
