package dev.codex.miuibackgesturehook.hooks.systemui;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.Map;

import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

@Hooker.XposedHooker(
        name = "SystemUiGestureInputHook",
        targets = "com.android.systemui",
        order = 30)
public final class SystemUiGestureInputHook extends HookerBridge {
    private static final String TAG = "SystemUiGestureInputHook";
    private final SystemUiImpl implementation = new SystemUiImpl();
    static final String EDGE_BACK_GESTURE_HANDLER =
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler";
    static final String VIBRATOR_HELPER =
            "com.android.systemui.statusbar.VibratorHelper";
    static final String BACK_PANEL_VIEW =
            "com.android.systemui.navigationbar.gestural.BackPanel";
    static final String MIUI_OVERVIEW_PROXY =
            "com.android.systemui.recents.MiuiOverviewProxy";

    @Override
    public void onAttached(String packageName, ClassLoader classLoader,
                           XposedInterface xposed, ApplicationInfo applicationInfo) {
        super.onAttached(packageName, classLoader, xposed, applicationInfo);
        implementation.onAttached(packageName, classLoader, xposed, applicationInfo);
    }

    @Override
    public void onPackageLoad() {
        implementation.start();
        try {
            implementation.ensureSystemUiPlatformSelected();
            implementation.installGestureInputHooks(classLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install SystemUI gesture input", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return implementation.prepareGestureInputHotReload(savedInstanceState);
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        implementation.releaseForHotReload();
    }

    @Override
    protected void onHotReloaded(Map<String, Object> savedInstanceState) {
        implementation.restoreGestureInputHotReload(savedInstanceState, classLoader);
    }
}
