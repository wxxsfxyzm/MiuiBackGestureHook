package dev.codex.miuibackgesturehook.hooks.miuihome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.util.Map;

import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

@Hooker.XposedHooker(
        name = "MiuiHomeOpenInterruptionHook",
        targets = "com.miui.home",
        order = 60)
public final class MiuiHomeOpenInterruptionHook extends HookerBridge {
    private static final String TAG = "MiuiHomeOpenInterruptionHook";
    private final MiuiHomeImpl implementation = new MiuiHomeImpl();
    static final String MIUI_HOME_BACK_GESTURE_BREAK_CONTROLLER =
            "com.miui.home.recents.BackGestureBreakController";
    static final String MIUI_HOME_WINDOW_ELEMENT_ANIM_LISTENER =
            "com.miui.home.recents.anim.StateManager$windowElementAnimListener$1";
    static final String MIUI_HOME_STATE_MANAGER =
            "com.miui.home.recents.anim.StateManager";
    static final String MIUI_HOME_WINDOW_ELEMENT =
            "com.miui.home.recents.anim.WindowElement";
    static final String MIUI_HOME_REMOTE_TRANSITION_INFO =
            "com.miui.home.recents.event.RemoteTransitionInfo";
    static final String MIUI_HOME_WINDOW_TRANSITION_COMPAT =
            "com.android.systemui.shared.recents.system.WindowTransitionCompat";
    static final String MIUI_HOME_WINDOW_TRANSITION_CALLBACK_HELPER =
            "com.android.systemui.shared.recents.utilities.WindowTransitionCallbackHelper";

    @Override
    public void onAttached(String packageName, ClassLoader classLoader,
                           XposedInterface xposed, ApplicationInfo applicationInfo) {
        super.onAttached(packageName, classLoader, xposed, applicationInfo);
        implementation.onAttached(packageName, classLoader, xposed, applicationInfo);
    }
    @Override
    public boolean shouldInstallHooker() {
        return Build.VERSION.SDK_INT < 37;
    }

    @Override
    public void onPackageLoad() {
        implementation.start();
        try {
            Class<?> breakControllerClass = Class.forName(
                    MIUI_HOME_BACK_GESTURE_BREAK_CONTROLLER, false, classLoader);
            implementation.hookMiuiHomeOpenBreakEnable(breakControllerClass);
            Class<?> listenerClass = Class.forName(
                    MIUI_HOME_WINDOW_ELEMENT_ANIM_LISTENER, false, classLoader);
            implementation.hookMiuiHomeOpenBreakAnimationStart(listenerClass);
            implementation.hookMiuiHomeOpenBreakAnimationEnd(listenerClass);
            runOptional("MiuiHome OPEN target binding",
                    () -> implementation.hookMiuiHomeLauncherOpenSnapshotTargets(classLoader));
            implementation.hookMiuiHomeReusedCloseOpen(classLoader);
            runOptional("MiuiHome permission merge",
                    () -> implementation.hookMiuiHomePermissionMerge(classLoader));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install MiuiHome OPEN interruption", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        if (!implementation.isHotReloadSafe()) {
            return false;
        }
        savedInstanceState.put("controller",
                implementation.miuiHomeOpenBreakControllerState());
        savedInstanceState.put("context", implementation.miuiHomeOpenBreakContextState());
        savedInstanceState.put("generation",
                implementation.miuiHomeOpenBreakGenerationState());
        savedInstanceState.put("animation",
                implementation.miuiHomeOpenBreakAnimationState());
        savedInstanceState.put("prepared",
                implementation.miuiHomeOpenBreakPreparedState());
        savedInstanceState.put("active", implementation.miuiHomeOpenBreakActiveState());
        savedInstanceState.put("commandPending",
                implementation.miuiHomeOpenBreakCommandPendingState());
        return true;
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        implementation.releaseForHotReload();
    }

    @Override
    protected void onHotReloaded(Map<String, Object> savedInstanceState) {
        implementation.miuiHomeOpenBreakControllerState(
                savedInstanceState.get("controller"));
        Object context = savedInstanceState.get("context");
        implementation.miuiHomeOpenBreakContextState(
                context instanceof Context ? (Context) context : null);
        Object generation = savedInstanceState.get("generation");
        implementation.miuiHomeOpenBreakGenerationState(
                generation instanceof Number
                        ? ((Number) generation).longValue() : 0L);
        implementation.miuiHomeOpenBreakAnimationState(
                savedInstanceState.get("animation"));
        implementation.miuiHomeOpenBreakPreparedState(
                Boolean.TRUE.equals(savedInstanceState.get("prepared")));
        implementation.miuiHomeOpenBreakActiveState(
                Boolean.TRUE.equals(savedInstanceState.get("active")));
        implementation.miuiHomeOpenBreakCommandPendingState(
                Boolean.TRUE.equals(savedInstanceState.get("commandPending")));
        implementation.restoreMiuiHomeOpenBreakAfterHotReload();
    }

    private void runOptional(String label, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, label + " unavailable", throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}
