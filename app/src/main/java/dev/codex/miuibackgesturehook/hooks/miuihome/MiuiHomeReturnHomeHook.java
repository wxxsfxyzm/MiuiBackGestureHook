package dev.codex.miuibackgesturehook.hooks.miuihome;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.Map;

import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

@Hooker.XposedHooker(
        name = "MiuiHomeReturnHomeHook",
        targets = "com.miui.home",
        order = 70)
public final class MiuiHomeReturnHomeHook extends HookerBridge {
    private static final String TAG = "MiuiHomeReturnHomeHook";
    private final MiuiHomeImpl implementation = new MiuiHomeImpl();
    static final String MIUI_HOME_ANIM_BACKGROUND_THREAD =
            "com.miui.launcher.common.AnimBackgroundThread";
    static final String MIUI_HOME_SHELL_TRANSITION_CALLBACK =
            "com.android.systemui.shared.recents.utilities.ShellTransitionCallback";
    static final String MIUI_HOME_RECENT_BLUR_PARAMS =
            "com.miui.home.recents.anim.RecentBlurParams";
    static final String MIUI_HOME_REMOTE_ANIMATION_TARGET_COMPAT =
            "com.android.systemui.shared.recents.system.RemoteAnimationTargetCompat";
    static final String MIUI_HOME_REMOTE_ANIMATION_TARGET_SET =
            "com.miui.home.recents.util.RemoteAnimationTargetSet";
    static final String MIUI_HOME_WINDOW_ANIM_PARAMS =
            "com.miui.home.recents.util.WindowAnimParams";
    static final String MIUI_HOME_RECTF_PARAMS =
            "com.miui.home.recents.anim.RectFParams";
    static final String MIUI_HOME_RECTF_SPRING_ANIM =
            "com.miui.home.recents.util.RectFSpringAnim";
    static final String MIUI_HOME_RECTF_SPRING_ANIM_TYPE =
            MIUI_HOME_RECTF_SPRING_ANIM + "$AnimType";
    static final String MIUI_HOME_WINDOW_ANIM_LISTENER =
            "com.miui.home.recents.anim.windowanim.WindowAnimListener";
    static final String MIUI_HOME_GESTURE_HOME_CALCULATOR =
            "com.miui.home.recents.GestureHomeCalculator";
    static final String MIUI_HOME_ANIM_STATUS_PARAM =
            "com.miui.home.recents.anim.windowanim.sfanim.AnimStatusParam";
    static final String MIUI_HOME_LOCAL_WINDOW_ANIM_IMPLEMENTOR =
            "com.miui.home.recents.anim.windowanim.LocalWindowAnimImplementor";
    static final String MIUI_HOME_CLIP_ANIMATION_HELPER =
            "com.miui.home.recents.util.ClipAnimationHelper";
    static final String MIUI_HOME_SYNC_RT_SURFACE_APPLIER =
            "com.android.systemui.shared.recents.system."
                    + "SyncRtSurfaceTransactionApplierCompat";
    static final String MIUI_HOME_TRANSACTION_COMPAT =
            "com.android.systemui.shared.recents.system.TransactionCompat";
    static final String MIUI_HOME_TRANSITION_UTIL =
            "com.android.wm.shell.util.TransitionUtil";
    static final String MIUI_HOME_SURFACE_PARAMS =
            MIUI_HOME_SYNC_RT_SURFACE_APPLIER + "$SurfaceParams";
    static final String MIUI_HOME_SURFACE_PARAMS_ARRAY =
            "[L" + MIUI_HOME_SURFACE_PARAMS + ";";
    static final String MIUI_HOME_CORNER_RADII =
            "com.android.systemui.shared.recents.utilities.CornerRadii";
    static final String MIUI_HOME_VALUE_CALLBACK =
            "com.miui.home.recents.anim.IValueCallBack";
    static final String MIUI_HOME_SHORTCUT_MENU_LAYER_ELEMENT =
            "com.miui.home.recents.anim.ShortcutMenuLayerElement";
    static final String MIUI_HOME_SHORTCUT_MENU_LAYER_PARAMS =
            "com.miui.home.recents.anim.ShortcutMenuLayerParams";
    static final String MIUI_HOME_BASE_WALLPAPER_ELEMENT =
            "com.miui.home.recents.anim.BaseWallpaperElement";
    static final String MIUI_HOME_SYSTEM_WALLPAPER_ELEMENT =
            "com.miui.home.recents.anim.SystemWallpaperElement";
    static final String MIUI_HOME_WALLPAPER_PARAMS =
            "com.miui.home.recents.anim.WallpaperParam";
    static final String MIUI_HOME_DEVICE_LEVEL_UTILS =
            "com.miui.home.common.utils.DeviceLevelUtils";
    static final String MIUI_HOME_WINDOW_CORNER_RADIUS_UTIL =
            "com.miui.home.recents.util.WindowCornerRadiusUtil";

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
            runOptional("MiuiHome element continuity",
                    () -> implementation.hookMiuiHomeTransitionContinuity(
                            classLoader, true, true, true));
            runOptional("MiuiHome finish epoch",
                    () -> implementation.hookMiuiHomeUnifiedFinishEpoch(
                            classLoader, true, true, true));
            implementation.hookMiuiHomeGeometryFrames(classLoader, true, true);
            runOptional("MiuiHome transition geometry",
                    () -> implementation.hookMiuiHomeTransitionSetupLeash(classLoader));
            implementation.hookMiuiHomeStartTransactionApply();
            implementation.hookMiuiHomeReturnHomeSameIconParallel(classLoader);
            implementation.hookMiuiHomeReturnHomeFreshOpen(classLoader);
            implementation.hookMiuiHomeReturnHomeDirectCancel(classLoader);
            implementation.hookMiuiHomeReturnHomeInitialize(classLoader);
            implementation.hookMiuiHomeReturnHomeLocalHandoff(classLoader);
            implementation.hookMiuiHomeReturnHomeWallpaperCommands(
                    classLoader, true, true);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install MiuiHome return-home", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        if (!implementation.isHotReloadSafe()) {
            return false;
        }
        savedInstanceState.put("shellBackAnimation",
                implementation.snapshotMiuiHomeReturnHomeBinder());
        return true;
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        implementation.releaseForHotReload();
    }

    @Override
    protected void onHotReloaded(Map<String, Object> savedInstanceState) {
        Object binder = savedInstanceState.get("shellBackAnimation");
        implementation.restoreMiuiHomeReturnHomeBinderState(
                binder instanceof IBinder ? (IBinder) binder : null);
        implementation.restoreMiuiHomeReturnHomeAfterHotReload(classLoader);
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
