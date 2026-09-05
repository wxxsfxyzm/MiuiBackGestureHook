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
        name = "MiuiHomeStateBridgeHook",
        targets = "com.miui.home",
        order = 50)
public final class MiuiHomeStateBridgeHook extends HookerBridge {
    private static final String TAG = "MiuiHomeStateBridgeHook";
    private final MiuiHomeImpl implementation = new MiuiHomeImpl();
    static final String MIUI_HOME_RECENTS_CONTAINER =
            "com.miui.home.recents.views.RecentsContainer";
    static final String MIUI_HOME_TASK_VIEW = "com.miui.home.recents.views.TaskView";
    static final String MIUI_HOME_STATE_NOTIFY_UTILS =
            "com.miui.home.recents.util.StateNotifyUtils";
    static final String MIUI_HOME_LAUNCHER_STATE_MANAGER =
            "com.miui.home.launcher.LauncherStateManager";
    static final String MIUI_HOME_LAUNCHER_STATE =
            "com.miui.home.launcher.LauncherState";
    static final String MIUI_HOME_BASE_LAUNCHER =
            "com.miui.home.launcher.BaseLauncher";
    static final String MIUI_HOME_SMALL_WINDOW_STATE_HELPER =
            "com.miui.home.smallwindow.SmallWindowStateHelper";
    static final String MIUI_HOME_APPLICATION =
            "com.miui.home.launcher.Application";
    static final String MIUI_HOME_OVERVIEW_PROXY_IMPL =
            "com.miui.home.recents.OverviewProxyImpl";

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
            Context context = implementation.resolveCurrentApplicationContext(classLoader);
            if (context != null) {
                implementation.ensureMiuiHomeInputArbiterReceiver(context);
            }
            implementation.hookMiuiHomeRecentsActualState(
                    Class.forName(MIUI_HOME_RECENTS_CONTAINER, false, classLoader));
            implementation.hookMiuiHomeRecentsTaskLaunch(
                    Class.forName(MIUI_HOME_TASK_VIEW, false, classLoader));
            implementation.hookMiuiHomeFullscreenState(classLoader);
            implementation.hookMiuiHomeDrawerState(classLoader);
            implementation.hookMiuiHomeFolderState(classLoader, true, true);
            implementation.hookMiuiHomeEditingState(classLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install MiuiHome state bridge", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        if (!implementation.isHotReloadSafe()) {
            return false;
        }
        savedInstanceState.put("overview", implementation.miuiOverviewVisibleState());
        savedInstanceState.put("drawer", implementation.miuiDrawerVisibleState());
        savedInstanceState.put("folder", implementation.miuiFolderVisibleState());
        savedInstanceState.put("editing", implementation.miuiLauncherEditingState());
        savedInstanceState.put("overviewDismissUntil",
                implementation.miuiOverviewDismissDeadlineState());
        return true;
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        implementation.releaseForHotReload();
    }

    @Override
    protected void onHotReloaded(Map<String, Object> savedInstanceState) {
        implementation.miuiOverviewVisibleState(
                Boolean.TRUE.equals(savedInstanceState.get("overview")));
        implementation.miuiDrawerVisibleState(
                Boolean.TRUE.equals(savedInstanceState.get("drawer")));
        implementation.miuiFolderVisibleState(
                Boolean.TRUE.equals(savedInstanceState.get("folder")));
        implementation.miuiLauncherEditingState(
                Boolean.TRUE.equals(savedInstanceState.get("editing")));
        Object dismiss = savedInstanceState.get("overviewDismissUntil");
        implementation.miuiOverviewDismissDeadlineState(
                dismiss instanceof Number ? ((Number) dismiss).longValue() : 0L);
        implementation.restoreMiuiOverviewDismissTimeoutAfterHotReload();
    }
}
