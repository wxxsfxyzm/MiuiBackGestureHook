package dev.codex.miuibackgesturehook.hooks.miuihome;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.util.Map;

import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

@Hooker.XposedHooker(name = "MiuiHomeGestureInputHook", targets = "com.miui.home", order = 80)
public final class MiuiHomeGestureInputHook extends HookerBridge {
    private static final String TAG = "MiuiHomeGestureInputHook";
    private final MiuiHomeImpl implementation = new MiuiHomeImpl();
    static final String MIUI_HOME_GESTURE_STUB =
            "com.miui.home.recents.GestureStubView";
    static final String MIUI_HOME_GESTURE_PROCESSOR =
            "com.miui.home.recents.GesturesBackTouchProcessor";

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
            // This hooker owns the MiuiHomeImpl instance that handles the accepted-DOWN
            // boundary. Register its arbiter receiver during cold startup so the initial
            // SystemUI readiness publication is available before the first gesture.
            Context launcherContext = ActivityThread.currentApplication();
            if (launcherContext != null) {
                implementation.ensureMiuiHomeInputArbiterReceiver(launcherContext);
            } else {
                log(Log.WARN, TAG,
                        "MiuiHome application context is not available yet; "
                                + "input arbiter will retry from GestureStub");
            }
            Class<?> gestureStubClass = Class.forName(
                    MIUI_HOME_GESTURE_STUB, false, classLoader);
            runOptional("MiuiHome trigger region",
                    () -> implementation.hookMiuiHomeGestureStubTriggerRegion(
                            gestureStubClass));
            runOptional("MiuiHome trigger touch region",
                    () -> implementation.hookMiuiHomeGestureStubTriggerTouchRegion(
                            gestureStubClass));
            implementation.hookMiuiHomeGestureStubShow(gestureStubClass);
            Class<?> processorClass = Class.forName(
                    MIUI_HOME_GESTURE_PROCESSOR, false, classLoader);
            implementation.hookMiuiHomeGestureInputArbiter(
                    processorClass, gestureStubClass);
            implementation.hookMiuiHomeFreeformBackTouchability(classLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install MiuiHome gesture input", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return implementation.isHotReloadSafe();
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        implementation.releaseForHotReload();
    }

    @Override
    protected void onHotReloaded(Map<String, Object> savedInstanceState) {
        // MiuiHome's Stub windows are created on their own owner loopers.  Restore
        // their touchability before the first post-reload gesture; this is a reload
        // recovery step only and is never part of cold-start activation.
        implementation.restoreMiuiHomeGestureStubsAfterHotReload(classLoader);
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
