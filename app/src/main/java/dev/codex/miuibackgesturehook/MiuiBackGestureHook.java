package dev.codex.miuibackgesturehook;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackTouchTracker;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class MiuiBackGestureHook extends XposedModule {
    private static final String TAG = "MiuiBackGestureHook";
    private static final String BUILD_MARK = "systemui-aosp-back-v71-launcher-owned-home";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String MIUI_HOME = "com.miui.home";

    private static final String EDGE_BACK_GESTURE_HANDLER =
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler";
    private static final String MIUI_OVERVIEW_PROXY =
            "com.android.systemui.recents.MiuiOverviewProxy";
    private static final String LAUNCHER_PROXY_SERVICE =
            "com.android.systemui.recents.LauncherProxyService";
    private static final String NAVIGATION_BAR =
            "com.android.systemui.navigationbar.views.NavigationBar";
    private static final String BACK_ANIMATION_CONTROLLER =
            "com.android.wm.shell.back.BackAnimationController";
    private static final String SHELL_BACK_ANIMATION_REGISTRY =
            "com.android.wm.shell.back.ShellBackAnimationRegistry";
    private static final String CROSS_ACTIVITY_BACK_ANIMATION =
            "com.android.wm.shell.back.CrossActivityBackAnimation";
    private static final String DEFAULT_CROSS_ACTIVITY_BACK_ANIMATION =
            "com.android.wm.shell.back.DefaultCrossActivityBackAnimation";
    private static final String CUSTOM_CROSS_ACTIVITY_BACK_ANIMATION =
            "com.android.wm.shell.back.CustomCrossActivityBackAnimation";
    private static final String MIUI_BASE_RECENTS_IMPL =
            "com.miui.home.recents.BaseRecentsImpl";
    private static final String BACK_NAVIGATION_CONTROLLER =
            "com.android.server.wm.BackNavigationController";
    private static final String BACK_ANIMATION_HANDLER =
            "com.android.server.wm.BackNavigationController$AnimationHandler";
    private static final String BACK_WINDOW_ANIMATION_ADAPTOR =
            "com.android.server.wm.BackNavigationController$AnimationHandler$BackWindowAnimationAdaptor";
    private static final String SCHEDULE_ANIMATION_BUILDER =
            "com.android.server.wm.BackNavigationController$AnimationHandler$ScheduleAnimationBuilder";
    private static final String SURFACE_ANIMATOR =
            "com.android.server.wm.SurfaceAnimator";
    private static final String WINDOW_CONTAINER =
            "com.android.server.wm.WindowContainer";
    private static final String DISPLAY_POLICY = "com.android.server.wm.DisplayPolicy";
    private static final String ACTIVITY_RECORD =
            "com.android.server.wm.ActivityRecord";
    private static final String SURFACE_CONTROL_TRANSACTION =
            "android.view.SurfaceControl$Transaction";

    private static final int TRANSACTION_MIUI_ON_GESTURE_LINE_PROGRESS = 4;
    private static final int EDGE_LEFT = 0;
    private static final int EDGE_RIGHT = 1;
    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;
    private static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 0x00000010;
    private static final int KEY_ACTION_UP = 1;
    private static final int KEY_ACTION_DOWN = 0;
    private static final int TYPE_RETURN_TO_HOME = 1;
    private static final int TYPE_CROSS_ACTIVITY = 2;
    private static final int TYPE_CROSS_TASK = 3;
    private static final int TYPE_CALLBACK = 4;
    private static final boolean INSTALL_MIUI_HOME_STUB_HOOKS = false;
    private static final boolean INSTALL_WINDOW_INPUT_OVERLAY = false;
    // Debug builds retain frame-level diagnostics; release builds keep lifecycle/error logs only.
    private static final boolean ENABLE_VERBOSE_DIAGNOSTICS = BuildConfig.DEBUG;
    private static final float EDGE_TOUCH_WIDTH_DP = 24.0f;
    private static final float PILFER_THRESHOLD_DP = 8.0f;
    private static final float TRIGGER_THRESHOLD_DP = 48.0f;
    private static final float AOSP_PROGRESS_THRESHOLD_DP = 412.0f;
    private static final String MIUI_SIDEBAR_BOUNDS = "sidebar_bounds";
    private static final float MIUI_SIDEBAR_EXCLUSION_PADDING_DP = 8.0f;

    private final List<XposedInterface.HookHandle> hookHandles = new ArrayList<>();
    private final Map<Object, SystemUiBackInputOverlay> overlays =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, NativeBackInputMonitor> nativeInputMonitors =
            Collections.synchronizedMap(new WeakHashMap<>());
    private String processName;
    private ClassLoader systemUiClassLoader;
    private boolean nativePluginDiagnosticsLogged;
    private volatile long lastPredictiveBackServerTraceUptime;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "Module loaded, build=" + BUILD_MARK
                + ", process=" + processName
                + ", systemServer=" + param.isSystemServer());
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        log(Log.INFO, TAG, "Hot reloading, build=" + BUILD_MARK
                + ", process=" + processName
                + ", hooks=" + hookHandles.size());
        Object[][] overlayState = new Object[overlays.size() + nativeInputMonitors.size()][2];
        int index = 0;
        for (Map.Entry<Object, SystemUiBackInputOverlay> entry
                : new ArrayList<>(overlays.entrySet())) {
            overlayState[index][0] = entry.getKey();
            overlayState[index][1] = entry.getValue().backAnimationImpl;
            index++;
        }
        for (Map.Entry<Object, NativeBackInputMonitor> entry
                : new ArrayList<>(nativeInputMonitors.entrySet())) {
            overlayState[index][0] = entry.getKey();
            overlayState[index][1] = entry.getValue().backAnimationImpl;
            index++;
        }
        for (SystemUiBackInputOverlay overlay : new ArrayList<>(overlays.values())) {
            overlay.detach();
        }
        for (NativeBackInputMonitor monitor : new ArrayList<>(nativeInputMonitors.values())) {
            monitor.detach();
        }
        overlays.clear();
        nativeInputMonitors.clear();
        param.setSavedInstanceState(overlayState);
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        processName = param.getProcessName();
        int replaced = 0;
        boolean hadApplyTransformHook = false;
        boolean hadBackWindowStartHook = false;
        boolean hadSurfaceCreateLeashHook = false;
        boolean hadPreviewHook = false;
        boolean hadPrepareTransitionHook = false;
        boolean hadEnforceVisibleHook = false;
        boolean hadActivityVisibilityHook = false;
        boolean hadSurfaceTransactionLayerHook = false;
        boolean hadNavigationBarGestureInsetsHook = false;
        boolean hadBackNavigationDoneHook = false;
        boolean hadAnyServerHook = false;
        ClassLoader hotReloadClassLoader = null;
        for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
            try {
                if (oldHandle.getId() != null && oldHandle.getId().startsWith("server_")) {
                    hadAnyServerHook = true;
                }
                if ("cross_activity_applyTransform".equals(oldHandle.getId())) {
                    hadApplyTransformHook = true;
                } else if ("server_back_window_start_animation".equals(oldHandle.getId())) {
                    hadBackWindowStartHook = true;
                } else if ("server_surface_animator_create_leash".equals(oldHandle.getId())) {
                    hadSurfaceCreateLeashHook = true;
                } else if ("server_schedule_animation_apply_preview".equals(oldHandle.getId())) {
                    hadPreviewHook = true;
                } else if ("server_schedule_animation_prepare_transition".equals(
                        oldHandle.getId())) {
                    hadPrepareTransitionHook = true;
                } else if ("server_window_container_enforce_surface_visible"
                        .equals(oldHandle.getId())) {
                    hadEnforceVisibleHook = true;
                } else if ("server_activity_record_set_visibility".equals(oldHandle.getId())) {
                    hadActivityVisibilityHook = true;
                } else if (oldHandle.getId() != null
                        && oldHandle.getId().startsWith("server_surface_transaction_")) {
                    hadSurfaceTransactionLayerHook = true;
                } else if ("systemui_navigation_bar_gesture_insets".equals(
                        oldHandle.getId())) {
                    hadNavigationBarGestureInsetsHook = true;
                } else if ("server_back_navigation_done_cleanup".equals(
                        oldHandle.getId())) {
                    hadBackNavigationDoneHook = true;
                }
                if (hotReloadClassLoader == null
                        && oldHandle.getExecutable() != null
                        && oldHandle.getExecutable().getDeclaringClass() != null) {
                    hotReloadClassLoader =
                            oldHandle.getExecutable().getDeclaringClass().getClassLoader();
                }
                XposedInterface.Hooker replacement = createHotReloadHooker(oldHandle.getId());
                if (replacement != null) {
                    hookHandles.add(oldHandle.replaceHook(replacement));
                    replaced++;
                } else {
                    oldHandle.unhook();
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to replace old hook: " + oldHandle, throwable);
            }
        }
        restoreHotReloadOverlays(param.getSavedInstanceState());
        boolean shouldInstallServerHooks = param.isSystemServer()
                || "system".equals(processName)
                || hadAnyServerHook;
        if (shouldInstallServerHooks && replaced == 0) {
            installSystemServerHooks(null);
        }
        if (shouldInstallServerHooks
                && (!hadBackWindowStartHook || !hadSurfaceCreateLeashHook || !hadPreviewHook
                || !hadPrepareTransitionHook || !hadEnforceVisibleHook || !hadActivityVisibilityHook
                || !hadSurfaceTransactionLayerHook || !hadBackNavigationDoneHook)) {
            ClassLoader serverClassLoader = findSystemServerClassLoader(hotReloadClassLoader);
            if (serverClassLoader != null) {
                if (!hadBackWindowStartHook) {
                    hookBackWindowStartAnimation(serverClassLoader);
                }
                if (!hadSurfaceCreateLeashHook) {
                    hookSurfaceAnimatorCreateAnimationLeash(serverClassLoader);
                }
                if (!hadPreviewHook) {
                    hookScheduleAnimationPreview(serverClassLoader);
                }
                if (!hadPrepareTransitionHook) {
                    hookScheduleAnimationPrepareTransition(serverClassLoader);
                }
                if (!hadEnforceVisibleHook) {
                    hookWindowContainerEnforceSurfaceVisible(serverClassLoader);
                }
                if (!hadActivityVisibilityHook) {
                    hookActivityRecordSetVisibility(serverClassLoader);
                }
                if (!hadSurfaceTransactionLayerHook) {
                    hookSurfaceTransactionLayerDiagnostics(serverClassLoader);
                }
                if (!hadBackNavigationDoneHook) {
                    hookBackNavigationDoneCleanup(serverClassLoader);
                }
            }
        }
        if (SYSTEM_UI.equals(processName) && !hadApplyTransformHook
                && hotReloadClassLoader != null) {
            hookCrossActivityApplyTransformDiagnostics(hotReloadClassLoader);
        }
        if (SYSTEM_UI.equals(processName) && !hadNavigationBarGestureInsetsHook
                && hotReloadClassLoader != null) {
            hookNavigationBarGestureInsets(hotReloadClassLoader);
        }
        log(Log.INFO, TAG, "Hot reloaded, build=" + BUILD_MARK
                + ", process=" + processName
                + ", oldHooksReplaced=" + replaced
                + ", hooks=" + hookHandles.size());
    }

    private XposedInterface.Hooker createHotReloadHooker(String hookId) {
        if (hookId == null) {
            return null;
        }
        if ("systemui_block_miui_gesture_line_progress".equals(hookId)) {
            return this::interceptMiuiOverviewProxyTransact;
        }
        if ("systemui_navigation_bar_gesture_insets".equals(hookId)) {
            return this::restoreNavigationBarGestureInsets;
        }
        if (hookId.startsWith("miui_home_disable_")) {
            return chain -> {
                log(Log.INFO, TAG, "Blocked MiuiHome " + chain.getExecutable().getName()
                        + "; SystemUI owns side back input");
                return null;
            };
        }
        if ("server_back_promote_to_tf_if_needed".equals(hookId)) {
            return this::interceptPromoteToTaskFragmentIfNeeded;
        }
        if ("server_back_window_start_animation".equals(hookId)) {
            return this::traceBackWindowStartAnimation;
        }
        if ("server_surface_animator_create_leash".equals(hookId)) {
            return this::traceSurfaceAnimatorCreateLeash;
        }
        if ("server_schedule_animation_apply_preview".equals(hookId)) {
            return this::traceScheduleAnimationPreview;
        }
        if ("server_schedule_animation_prepare_transition".equals(hookId)) {
            return this::interceptScheduleAnimationPrepareTransition;
        }
        if ("server_back_navigation_done_cleanup".equals(hookId)) {
            return this::cleanupSkippedRemoteAnimationOnNavigationDone;
        }
        if ("server_window_container_enforce_surface_visible".equals(hookId)) {
            return this::traceEnforceSurfaceVisible;
        }
        if ("server_activity_record_set_visibility".equals(hookId)) {
            return this::traceActivityRecordSetVisibility;
        }
        if (hookId != null && hookId.startsWith("server_surface_transaction_")) {
            return this::traceSurfaceTransactionLayerCall;
        }
        if ("shell_back_registry_updateSupportedAnimators".equals(hookId)) {
            return chain -> {
                Object result = chain.proceed();
                logRegistryState(chain.getThisObject(), "updateSupportedAnimators");
                return result;
            };
        }
        if (hookId.startsWith("systemui_") || hookId.startsWith("shell_back_")
                || hookId.startsWith("cross_activity_")
                || hookId.startsWith("default_cross_activity_")
                || hookId.startsWith("custom_cross_activity_")) {
            return chain -> traceHook(chain, hookId);
        }
        return null;
    }

    private void restoreHotReloadOverlays(Object savedState) {
        if (!(savedState instanceof Object[][])) {
            log(Log.INFO, TAG, "No hot reload back input state to restore");
            return;
        }
        Object[][] overlayState = (Object[][]) savedState;
        if (overlayState.length == 0) {
            log(Log.INFO, TAG, "Hot reload back input state is empty; "
                    + "will restore from next EdgeBackGestureHandler callback");
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            int restored = 0;
            for (Object[] pair : overlayState) {
                if (pair == null || pair.length < 2) {
                    continue;
                }
                installBackInputOverlay(pair[0], pair[1]);
                restored++;
            }
            log(Log.INFO, TAG, "Restored hot reload back input on main thread, count="
                    + restored);
        });
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        processName = param.getPackageName();
        log(Log.INFO, TAG, "Package loaded: " + processName
                + ", classLoader=" + param.getDefaultClassLoader()
                + ", sourceDir=" + param.getApplicationInfo().sourceDir);
        if (SYSTEM_UI.equals(processName)) {
            systemUiClassLoader = param.getDefaultClassLoader();
            installSystemUiHooks(systemUiClassLoader);
        } else if (MIUI_HOME.equals(processName) && INSTALL_MIUI_HOME_STUB_HOOKS) {
            installMiuiHomeHooks(param.getDefaultClassLoader());
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        processName = "system";
        log(Log.INFO, TAG, "System server starting, build=" + BUILD_MARK
                + ", classLoader=" + param.getClassLoader());
        installSystemServerHooks(param.getClassLoader());
    }

    private void installSystemServerHooks(ClassLoader classLoader) {
        try {
            ClassLoader serverClassLoader = findSystemServerClassLoader(classLoader);
            if (serverClassLoader == null) {
                log(Log.ERROR, TAG, "Unable to find system_server classloader for "
                        + BACK_NAVIGATION_CONTROLLER);
                return;
            }
            hookBackNavigationControllerDiagnostics(serverClassLoader);
            hookBackNavigationMonitoringDiagnostics(serverClassLoader);
            hookBackNavigationDoneCleanup(serverClassLoader);
            hookSecuritySidebarTransientBars(serverClassLoader);
            hookServerLeashDiagnostics(serverClassLoader);
            log(Log.INFO, TAG, "Installed system_server back navigation hooks, build="
                    + BUILD_MARK + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install system_server hooks", throwable);
        }
    }

    private void hookSecuritySidebarTransientBars(ClassLoader classLoader) {
        try {
            Class<?> policyClass = Class.forName(DISPLAY_POLICY, false, classLoader);
            for (Method method : policyClass.getDeclaredMethods()) {
                if (!"requestTransientBars".equals(method.getName())
                        || method.getParameterCount() != 2
                        || method.getParameterTypes()[1] != boolean.class) {
                    continue;
                }
                method.setAccessible(true);
                recordHookHandle(hook(method)
                        .setId("server_security_sidebar_transient_bars")
                        .intercept(chain -> {
                            if (isSidebarTransientGesture(chain.getThisObject())) {
                                log(Log.INFO, TAG, "Blocked transient bars from sidebar bounds");
                                return null;
                            }
                            Object swipeTarget = chain.getArg(0);
                            String owner = String.valueOf(invokeAnyMethod(
                                    swipeTarget, "getOwningPackage", new Object[0]));
                            String window = String.valueOf(swipeTarget);
                            String lower = window.toLowerCase();
                            if ("com.miui.securitycenter".equals(owner)
                                    && (lower.contains("sidebar")
                                    || lower.contains("game")
                                    || lower.contains("toolbox"))) {
                                log(Log.INFO, TAG, "Blocked transient bars from security sidebar"
                                        + ", target=" + shortObject(swipeTarget));
                                return null;
                            }
                            return chain.proceed();
                        }));
                log(Log.INFO, TAG, "Hooked DisplayPolicy security-sidebar transient bars");
                return;
            }
            log(Log.WARN, TAG, "DisplayPolicy.requestTransientBars(WindowState,boolean) not found");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook security-sidebar transient bars", throwable);
        }
    }

    private boolean isSidebarTransientGesture(Object displayPolicy) {
        try {
            Context context = (Context) readField(displayPolicy, "mContext");
            Object gestures = readField(displayPolicy, "mSystemGestures");
            float[] downXs = (float[]) readField(gestures, "mDownX");
            float[] downYs = (float[]) readField(gestures, "mDownY");
            if (context == null || downXs == null || downYs == null
                    || downXs.length == 0 || downYs.length == 0) {
                return false;
            }
            String encoded = Settings.Secure.getString(context.getContentResolver(),
                    MIUI_SIDEBAR_BOUNDS);
            if (encoded == null || encoded.trim().isEmpty()) {
                return false;
            }
            int x = Math.round(downXs[0]);
            int y = Math.round(downYs[0]);
            int padding = Math.max(0, Math.round(MIUI_SIDEBAR_EXCLUSION_PADDING_DP
                    * context.getResources().getDisplayMetrics().density));
            JSONArray bounds = new JSONArray(encoded);
            for (int i = 0; i < bounds.length(); i++) {
                JSONObject item = bounds.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Rect rect = new Rect(item.optInt("l", -1), item.optInt("t", -1),
                        item.optInt("r", -1), item.optInt("b", -1));
                if (!rect.isEmpty()) {
                    rect.inset(-padding, -padding);
                    if (rect.contains(x, y)) {
                        log(Log.INFO, TAG, "Matched sidebar transient gesture"
                                + ", x=" + x + ", y=" + y + ", bounds=" + rect);
                        return true;
                    }
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect sidebar transient gesture", throwable);
        }
        return false;
    }

    private void hookBackNavigationMonitoringDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(BACK_NAVIGATION_CONTROLLER, false,
                    classLoader);
            Method monitoring = controllerClass.getDeclaredMethod("isMonitoringFinishTransition");
            monitoring.setAccessible(true);
            recordHookHandle(hook(monitoring)
                    .setId("server_back_monitoring_finish_transition")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!Boolean.TRUE.equals(result)) {
                            return result;
                        }
                        Object controller = chain.getThisObject();
                        Object handler = readField(controller, "mAnimationHandler");
                        Object monitor = readField(controller, "mNavigationMonitor");
                        log(Log.WARN, TAG, "Back navigation blocked by unfinished transition"
                                + ", composed=" + readField(handler, "mComposed")
                                + ", prepareClose=" + shortObject(
                                readField(handler, "mPrepareCloseTransition"))
                                + ", openAdaptor=" + shortObject(
                                readField(handler, "mOpenAnimAdaptor"))
                                + ", navigationMonitor=" + shortObject(monitor)
                                + ", monitorForRemote=" + invokeAnyMethod(
                                monitor, "isMonitorForRemote", new Object[0]));
                        return result;
                    }));
            log(Log.INFO, TAG, "Hooked BackNavigationController transition-monitor diagnostics");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook transition-monitor diagnostics", throwable);
        }
    }

    private ClassLoader findSystemServerClassLoader(ClassLoader preferred) {
        ClassLoader[] candidates = new ClassLoader[]{
                preferred,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                MiuiBackGestureHook.class.getClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Class.forName(BACK_NAVIGATION_CONTROLLER, false, candidate);
                log(Log.INFO, TAG, "Resolved system_server classloader: " + candidate);
                return candidate;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "System_server classloader candidate failed: "
                        + candidate + ", error=" + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            }
        }
        return null;
    }

    private void hookBackNavigationControllerDiagnostics(ClassLoader classLoader) {
        try {
            Class.forName(BACK_NAVIGATION_CONTROLLER, false, classLoader);
            Class<?> handlerClass = Class.forName(BACK_ANIMATION_HANDLER, false, classLoader);
            Method promote = null;
            for (Method method : handlerClass.getDeclaredMethods()) {
                if ("promoteToTFIfNeeded".equals(method.getName())
                        && method.getParameterCount() == 2) {
                    promote = method;
                    break;
                }
            }
            if (promote == null) {
                log(Log.WARN, TAG, "BackNavigationController promoteToTFIfNeeded not found");
                return;
            }
            promote.setAccessible(true);
            recordHookHandle(hook(promote)
                    .setId("server_back_promote_to_tf_if_needed")
                    .intercept(this::interceptPromoteToTaskFragmentIfNeeded));
            log(Log.INFO, TAG, "Hooked BackNavigationController promoteToTFIfNeeded");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook BackNavigationController diagnostics", throwable);
        }
    }

    private void hookBackNavigationDoneCleanup(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(BACK_NAVIGATION_CONTROLLER, false,
                    classLoader);
            for (Method method : controllerClass.getDeclaredMethods()) {
                String name = method.getName();
                if (!("onBackNavigationDone".equals(name)
                        || "lambda$startBackNavigation$4".equals(name))
                        || method.getParameterCount() != 2
                        || method.getParameterTypes()[0] != Bundle.class
                        || method.getParameterTypes()[1] != int.class) {
                    continue;
                }
                method.setAccessible(true);
                recordHookHandle(hook(method)
                        .setId("server_back_navigation_done_cleanup")
                        .intercept(this::cleanupSkippedRemoteAnimationOnNavigationDone));
                log(Log.INFO, TAG, "Hooked BackNavigationController navigation-done cleanup"
                        + ", method=" + method.getName());
                return;
            }
            log(Log.WARN, TAG, "BackNavigationController.onBackNavigationDone not found");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook BackNavigationController navigation-done cleanup",
                    throwable);
        }
    }

    private Object cleanupSkippedRemoteAnimationOnNavigationDone(XposedInterface.Chain chain)
            throws Throwable {
        Bundle resultBundle = (Bundle) chain.getArg(0);
        boolean committed = resultBundle != null
                && resultBundle.containsKey("NavigationFinished")
                && resultBundle.getBoolean("NavigationFinished");
        Object result = chain.proceed();
        if (!committed) {
            return result;
        }
        Object controller = chain.getThisObject();
        try {
            Object handler = readField(controller, "mAnimationHandler");
            if (!Boolean.TRUE.equals(readField(handler, "mComposed"))) {
                return result;
            }
            Object prepareClose = readField(handler, "mPrepareCloseTransition");
            Object openAdaptor = readField(handler, "mOpenAnimAdaptor");
            Object prepareOpen = openAdaptor == null ? null
                    : readField(openAdaptor, "mPreparedOpenTransition");
            if (prepareClose != null || prepareOpen != null) {
                log(Log.INFO, TAG, "Kept composed predictive-back animation for transition cleanup"
                        + ", prepareOpen=" + shortObject(prepareOpen)
                        + ", prepareClose=" + shortObject(prepareClose));
                return result;
            }
            invokeAnyMethod(controller, "clearBackAnimations",
                    new Object[]{Boolean.FALSE});
            log(Log.INFO, TAG, "Cleared committed remote-only predictive-back animation"
                    + " after skipped prepare transition");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed committed remote-only predictive-back cleanup",
                    throwable);
        }
        return result;
    }

    private void hookServerLeashDiagnostics(ClassLoader classLoader) {
        hookBackWindowStartAnimation(classLoader);
        hookScheduleAnimationPrepareTransition(classLoader);
        if (ENABLE_VERBOSE_DIAGNOSTICS) {
            hookSurfaceAnimatorCreateAnimationLeash(classLoader);
            hookScheduleAnimationPreview(classLoader);
            hookWindowContainerEnforceSurfaceVisible(classLoader);
            hookActivityRecordSetVisibility(classLoader);
            hookSurfaceTransactionLayerDiagnostics(classLoader);
        }
    }

    private void hookBackWindowStartAnimation(ClassLoader classLoader) {
        try {
            Class<?> adaptorClass = Class.forName(BACK_WINDOW_ANIMATION_ADAPTOR,
                    false, classLoader);
            for (Method method : adaptorClass.getDeclaredMethods()) {
                if ("startAnimation".equals(method.getName())
                        && method.getParameterCount() == 4) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_back_window_start_animation")
                            .intercept(this::traceBackWindowStartAnimation));
                    log(Log.INFO, TAG, "Hooked BackWindowAnimationAdaptor.startAnimation");
                    return;
                }
            }
            log(Log.WARN, TAG, "BackWindowAnimationAdaptor.startAnimation not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook BackWindowAnimationAdaptor.startAnimation",
                    throwable);
        }
    }

    private void hookSurfaceAnimatorCreateAnimationLeash(ClassLoader classLoader) {
        try {
            Class<?> surfaceAnimatorClass = Class.forName(SURFACE_ANIMATOR, false, classLoader);
            for (Method method : surfaceAnimatorClass.getDeclaredMethods()) {
                if ("createAnimationLeash".equals(method.getName())
                        && method.getParameterCount() == 10) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_surface_animator_create_leash")
                            .intercept(this::traceSurfaceAnimatorCreateLeash));
                    log(Log.INFO, TAG, "Hooked SurfaceAnimator.createAnimationLeash");
                    return;
                }
            }
            log(Log.WARN, TAG, "SurfaceAnimator.createAnimationLeash not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook SurfaceAnimator.createAnimationLeash",
                    throwable);
        }
    }

    private void hookScheduleAnimationPreview(ClassLoader classLoader) {
        try {
            Class<?> builderClass = Class.forName(SCHEDULE_ANIMATION_BUILDER, false,
                    classLoader);
            for (Method method : builderClass.getDeclaredMethods()) {
                if ("applyPreviewStrategy".equals(method.getName())) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_schedule_animation_apply_preview")
                            .intercept(this::traceScheduleAnimationPreview));
                    log(Log.INFO, TAG, "Hooked ScheduleAnimationBuilder.applyPreviewStrategy");
                    return;
                }
            }
            log(Log.WARN, TAG, "ScheduleAnimationBuilder.applyPreviewStrategy not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook ScheduleAnimationBuilder.applyPreviewStrategy",
                    throwable);
        }
    }

    private void hookScheduleAnimationPrepareTransition(ClassLoader classLoader) {
        try {
            Class<?> builderClass = Class.forName(SCHEDULE_ANIMATION_BUILDER, false,
                    classLoader);
            for (Method method : builderClass.getDeclaredMethods()) {
                if ("prepareTransitionIfNeeded".equals(method.getName())) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_schedule_animation_prepare_transition")
                            .intercept(this::interceptScheduleAnimationPrepareTransition));
                    log(Log.INFO, TAG, "Hooked ScheduleAnimationBuilder.prepareTransitionIfNeeded");
                    return;
                }
            }
            log(Log.WARN, TAG, "ScheduleAnimationBuilder.prepareTransitionIfNeeded not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook ScheduleAnimationBuilder.prepareTransitionIfNeeded",
                    throwable);
        }
    }

    private void hookWindowContainerEnforceSurfaceVisible(ClassLoader classLoader) {
        try {
            Class<?> windowContainerClass = Class.forName(WINDOW_CONTAINER, false, classLoader);
            for (Method method : windowContainerClass.getDeclaredMethods()) {
                if ("enforceSurfaceVisible".equals(method.getName())
                        && method.getParameterCount() == 1) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_window_container_enforce_surface_visible")
                            .intercept(this::traceEnforceSurfaceVisible));
                    log(Log.INFO, TAG, "Hooked WindowContainer.enforceSurfaceVisible");
                    return;
                }
            }
            log(Log.WARN, TAG, "WindowContainer.enforceSurfaceVisible not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook WindowContainer.enforceSurfaceVisible",
                    throwable);
        }
    }

    private void hookActivityRecordSetVisibility(ClassLoader classLoader) {
        try {
            Class<?> activityRecordClass = Class.forName(ACTIVITY_RECORD, false, classLoader);
            for (Method method : activityRecordClass.getDeclaredMethods()) {
                if ("setVisibility".equals(method.getName())
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == boolean.class) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_activity_record_set_visibility")
                            .intercept(this::traceActivityRecordSetVisibility));
                    log(Log.INFO, TAG, "Hooked ActivityRecord.setVisibility(boolean)");
                    return;
                }
            }
            log(Log.WARN, TAG, "ActivityRecord.setVisibility(boolean) not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook ActivityRecord.setVisibility(boolean)",
                    throwable);
        }
    }

    private void hookSurfaceTransactionLayerDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> transactionClass = Class.forName(SURFACE_CONTROL_TRANSACTION, false,
                    classLoader);
            int count = 0;
            for (Method method : transactionClass.getDeclaredMethods()) {
                String name = method.getName();
                if (("setLayer".equals(name) && method.getParameterCount() == 2)
                        || ("setRelativeLayer".equals(name) && method.getParameterCount() == 3)
                        || ("reparent".equals(name) && method.getParameterCount() == 2)
                        || ("show".equals(name) && method.getParameterCount() == 1)
                        || ("hide".equals(name) && method.getParameterCount() == 1)
                        || ("setAlpha".equals(name) && method.getParameterCount() == 2)) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_surface_transaction_" + name + "_"
                                    + method.getParameterCount())
                            .intercept(this::traceSurfaceTransactionLayerCall));
                    count++;
                }
            }
            log(Log.INFO, TAG, "Hooked SurfaceControl.Transaction layer diagnostics"
                    + ", count=" + count);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook SurfaceControl.Transaction layer diagnostics",
                    throwable);
        }
    }

    private Object traceBackWindowStartAnimation(XposedInterface.Chain chain) throws Throwable {
        markPredictiveBackServerTrace();
        Object adaptor = chain.getThisObject();
        try {
            Object target = readField(adaptor, "mTarget");
            Object isOpen = readField(adaptor, "mIsOpen");
            Object switchType = readField(adaptor, "mSwitchType");
            Object animationLeash = chain.getArg(0);
            Object transaction = chain.getArg(1);
            Object type = chain.getArg(2);
            log(Log.INFO, TAG, "BackWindowAnimationAdaptor.startAnimation before"
                    + ", target=" + shortObject(target)
                    + ", isOpen=" + isOpen
                    + ", switchType=" + switchType
                    + ", animationLeashArg=" + shortObject(animationLeash)
                    + ", type=" + type
                    + ", targetSurface=" + describeSurfaceFromTarget(target)
                    + ", leashParent=" + describeAnimationLeashParent(target));
            if (Boolean.TRUE.equals(isOpen) && transaction instanceof SurfaceControl.Transaction) {
                ensureOpenTaskFragmentVisible(target, (SurfaceControl.Transaction) transaction);
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to trace BackWindow startAnimation before", throwable);
        }
        Object result = chain.proceed();
        try {
            Object target = readField(adaptor, "mTarget");
            Object isOpen = readField(adaptor, "mIsOpen");
            Object capturedLeash = readField(adaptor, "mCapturedLeash");
            Object animationTarget = readField(adaptor, "mAnimationTarget");
            log(Log.INFO, TAG, "BackWindowAnimationAdaptor.startAnimation after"
                    + ", target=" + shortObject(target)
                    + ", isOpen=" + isOpen
                    + ", capturedLeash=" + shortObject(capturedLeash)
                    + ", remoteTarget=" + describeRemoteTarget(animationTarget));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to trace BackWindow startAnimation after", throwable);
        }
        return result;
    }

    private void ensureOpenTaskFragmentVisible(Object target, SurfaceControl.Transaction transaction) {
        if (target == null || transaction == null) {
            return;
        }
        try {
            Object taskFragment = invokeAnyMethod(target, "getTaskFragment", new Object[0]);
            if (taskFragment == null) {
                return;
            }
            try {
                invokeAnyMethod(taskFragment, "updateOrganizedTaskFragmentSurface",
                        new Object[0]);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Open TaskFragment update surface failed, target="
                        + shortObject(target) + ", taskFragment=" + shortObject(taskFragment)
                        + ", error=" + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            }
            Object surface = readFieldOrNull(taskFragment, "mSurfaceControl");
            if (surface instanceof SurfaceControl) {
                invokeAnyMethod(transaction, "show", new Object[]{surface});
                log(Log.INFO, TAG, "Forced opening TaskFragment visible for predictive back"
                        + ", target=" + shortObject(target)
                        + ", taskFragment=" + shortObject(taskFragment)
                        + ", surface=" + shortObject(surface));
            } else {
                log(Log.WARN, TAG, "Open TaskFragment has no SurfaceControl"
                        + ", target=" + shortObject(target)
                        + ", taskFragment=" + shortObject(taskFragment)
                        + ", surface=" + shortObject(surface));
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to force opening TaskFragment visible, target="
                    + shortObject(target), throwable);
        }
    }

    private Object traceScheduleAnimationPreview(XposedInterface.Chain chain) throws Throwable {
        markPredictiveBackServerTrace();
        Object builder = chain.getThisObject();
        try {
            log(Log.INFO, TAG, "ScheduleAnimationBuilder.applyPreviewStrategy before"
                    + ", builder=" + shortObject(builder)
                    + ", close=" + shortObject(readFieldOrNull(builder, "mCloseActivity"))
                    + ", open=" + describeArray(readFieldOrNull(builder, "mOpenActivities"))
                    + ", isLaunchBehind=" + readFieldOrNull(builder, "mIsLaunchBehind")
                    + ", showWindowlessSurface="
                    + readFieldOrNull(builder, "mShowWindowlessSurface")
                    + ", openAdaptors="
                    + describeArray(readFieldOrNull(builder, "mOpenAnimationAdaptors")));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to trace applyPreviewStrategy before", throwable);
        }
        Object result = chain.proceed();
        try {
            log(Log.INFO, TAG, "ScheduleAnimationBuilder.applyPreviewStrategy after"
                    + ", builder=" + shortObject(builder)
                    + ", result=" + shortObject(result));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to trace applyPreviewStrategy after", throwable);
        }
        return result;
    }

    private Object interceptScheduleAnimationPrepareTransition(XposedInterface.Chain chain)
            throws Throwable {
        markPredictiveBackServerTrace();
        ClassLoader loader = chain.getExecutable().getDeclaringClass().getClassLoader();
        boolean unify = readWindowFlag("unifyBackNavigationTransition", loader, true);
        if (unify) {
            log(Log.INFO, TAG, "Skipped ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                    + " to avoid Xiaomi unified-transition leash reparenting"
                    + ", unifyBackNavigationTransition=true"
                    + ", builder=" + shortObject(chain.getThisObject())
                    + ", args=" + describeArgs(chain));
            return null;
        }
        log(Log.INFO, TAG, "Allowing ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                + ", unifyBackNavigationTransition=false"
                + ", path=Xiaomi/AOSP setLaunchBehind"
                + ", args=" + describeArgs(chain));
        return chain.proceed();
    }

    private Object traceEnforceSurfaceVisible(XposedInterface.Chain chain) throws Throwable {
        Object target = chain.getArg(0);
        if (isRecentPredictiveBackServerTrace()) {
            log(Log.INFO, TAG, "WindowContainer.enforceSurfaceVisible before"
                    + ", target=" + shortObject(target)
                    + ", surface=" + describeSurfaceFromTarget(target)
                    + ", leashParent=" + describeAnimationLeashParent(target));
        }
        Object result = chain.proceed();
        if (isRecentPredictiveBackServerTrace()) {
            log(Log.INFO, TAG, "WindowContainer.enforceSurfaceVisible after"
                    + ", target=" + shortObject(target)
                    + ", surface=" + describeSurfaceFromTarget(target)
                    + ", leashParent=" + describeAnimationLeashParent(target)
                    + ", result=" + shortObject(result));
        }
        return result;
    }

    private Object traceActivityRecordSetVisibility(XposedInterface.Chain chain)
            throws Throwable {
        Object activity = chain.getThisObject();
        Object visible = chain.getArg(0);
        if (isRecentPredictiveBackServerTrace()) {
            log(Log.INFO, TAG, "ActivityRecord.setVisibility before"
                    + ", activity=" + shortObject(activity)
                    + ", visible=" + visible
                    + ", surface=" + describeSurfaceFromTarget(activity)
                    + ", leashParent=" + describeAnimationLeashParent(activity));
        }
        Object result = chain.proceed();
        if (isRecentPredictiveBackServerTrace()) {
            log(Log.INFO, TAG, "ActivityRecord.setVisibility after"
                    + ", activity=" + shortObject(activity)
                    + ", visible=" + visible
                    + ", surface=" + describeSurfaceFromTarget(activity)
                    + ", leashParent=" + describeAnimationLeashParent(activity)
                    + ", result=" + shortObject(result));
        }
        return result;
    }

    private Object traceSurfaceTransactionLayerCall(XposedInterface.Chain chain)
            throws Throwable {
        if (!ENABLE_VERBOSE_DIAGNOSTICS) {
            return chain.proceed();
        }
        if (!isRecentPredictiveBackServerTrace()
                || !isInterestingSurfaceTransaction(chain.getArgs())) {
            return chain.proceed();
        }
        log(Log.INFO, TAG, "SurfaceTransaction." + chain.getExecutable().getName()
                + ", args=" + describeObjectList(chain.getArgs()));
        return chain.proceed();
    }

    private boolean isInterestingSurfaceTransaction(List<Object> args) {
        for (Object arg : args) {
            if (arg instanceof SurfaceControl) {
                String value = String.valueOf(arg);
                if (value.contains("predict_back")
                        || value.contains("animation-leash")
                        || value.contains("ActivityRecord")
                        || value.contains("TaskFragment")
                        || value.contains("Task=")) {
                    return true;
                }
            }
        }
        return false;
    }

    private Object traceSurfaceAnimatorCreateLeash(XposedInterface.Chain chain)
            throws Throwable {
        Object type = chain.getArg(3);
        boolean predictiveBack = type instanceof Integer && ((Integer) type).intValue() == 256;
        if (!predictiveBack) {
            return chain.proceed();
        }
        markPredictiveBackServerTrace();
        Object animatable = chain.getArg(0);
        Object surface = chain.getArg(1);
        Object hidden = chain.getArg(8);
        log(Log.INFO, TAG, "SurfaceAnimator.createAnimationLeash before"
                + ", animatable=" + shortObject(animatable)
                + ", surfaceArg=" + shortObject(surface)
                + ", type=" + type
                + ", hidden=" + hidden
                + ", animatableSurface=" + describeSurfaceFromTarget(animatable)
                + ", leashParent=" + describeAnimationLeashParent(animatable));
        Object result = chain.proceed();
        log(Log.INFO, TAG, "SurfaceAnimator.createAnimationLeash after"
                + ", animatable=" + shortObject(animatable)
                + ", resultLeash=" + shortObject(result));
        return result;
    }

    private String describeSurfaceFromTarget(Object target) {
        if (target == null) {
            return "null";
        }
        try {
            return shortObject(invokeAnyMethod(target, "getSurfaceControl", new Object[0]));
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }

    private String describeAnimationLeashParent(Object target) {
        if (target == null) {
            return "null";
        }
        try {
            return shortObject(invokeAnyMethod(target, "getAnimationLeashParent",
                    new Object[0]));
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }

    private void markPredictiveBackServerTrace() {
        lastPredictiveBackServerTraceUptime = SystemClock.uptimeMillis();
    }

    private boolean isRecentPredictiveBackServerTrace() {
        long last = lastPredictiveBackServerTraceUptime;
        return last != 0 && SystemClock.uptimeMillis() - last < 5000;
    }

    private Object interceptPromoteToTaskFragmentIfNeeded(XposedInterface.Chain chain)
            throws Throwable {
        Object close = chain.getArg(0);
        Object open = chain.getArg(1);
        markPredictiveBackServerTrace();
        boolean migrate = readWindowFlag("migratePredictiveBackTransition",
                chain.getExecutable().getDeclaringClass().getClassLoader(), false);
        log(Log.INFO, TAG, "BackNavigationController.promoteToTFIfNeeded before"
                + ", migratePredictiveBackTransition=" + migrate
                + ", close=" + shortObject(close)
                + ", open=" + describeArray(open));
        if (!migrate) {
            Pair<Object, Object> result = new Pair<>(close, open);
            log(Log.INFO, TAG, "Bypassed TaskFragment promotion for predictive back"
                    + ", result=" + shortObject(result));
            return result;
        }
        Object result = chain.proceed();
        log(Log.INFO, TAG, "BackNavigationController.promoteToTFIfNeeded after"
                + ", result=" + shortObject(result));
        return result;
    }

    private boolean readWindowFlag(String methodName, ClassLoader preferredLoader,
                                   boolean defaultValue) {
        String[] classNames = new String[]{
                "com.android.window.flags.Flags",
                "android.window.flags.Flags"
        };
        for (String className : classNames) {
            try {
                Class<?> flagsClass = Class.forName(className, false, preferredLoader);
                Method method = flagsClass.getDeclaredMethod(methodName);
                method.setAccessible(true);
                Object result = method.invoke(null);
                if (result instanceof Boolean) {
                    log(Log.INFO, TAG, "Read " + methodName + " from "
                            + className + ": " + result);
                    return ((Boolean) result).booleanValue();
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Flag lookup failed for " + className
                        + ": " + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            }
        }
        log(Log.WARN, TAG, "Unable to read " + methodName
                + "; defaulting to " + defaultValue);
        return defaultValue;
    }

    private void installSystemUiHooks(ClassLoader classLoader) {
        try {
            hookMiuiOverviewProxy(classLoader);
            hookLauncherProxyService(classLoader);
            hookEdgeBackGestureHandler(classLoader);
            hookNavigationBarGestureInsets(classLoader);
            hookShellBackAnimation(classLoader);
            log(Log.INFO, TAG, "Installed SystemUI AOSP back restoration hooks, build="
                    + BUILD_MARK + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install SystemUI hooks", throwable);
        }
    }

    private void installMiuiHomeHooks(ClassLoader classLoader) {
        try {
            Class<?> recentsClass = Class.forName(MIUI_BASE_RECENTS_IMPL, false, classLoader);
            hookMiuiHomeNoArgNoOp(recentsClass, "addBackStubWindow",
                    "miui_home_disable_addBackStubWindow");
            hookMiuiHomeNoArgNoOp(recentsClass, "showBackStubWindow",
                    "miui_home_disable_showBackStubWindow_0");
            Method showWithArg = recentsClass.getDeclaredMethod("showBackStubWindow",
                    boolean.class);
            showWithArg.setAccessible(true);
            recordHookHandle(hook(showWithArg)
                    .setId("miui_home_disable_showBackStubWindow_1")
                    .intercept(chain -> {
                        log(Log.INFO, TAG, "Blocked MiuiHome showBackStubWindow(boolean); "
                                + "SystemUI owns side back input");
                        return null;
                    }));
            log(Log.INFO, TAG, "Installed MiuiHome side back stub disable hooks"
                    + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install MiuiHome side back hooks", throwable);
        }
    }

    private void hookMiuiHomeNoArgNoOp(Class<?> ownerClass, String methodName, String hookId)
            throws NoSuchMethodException {
        Method method = ownerClass.getDeclaredMethod(methodName);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId(hookId)
                .intercept(chain -> {
                    log(Log.INFO, TAG, "Blocked MiuiHome " + methodName
                            + "; SystemUI owns side back input");
                    return null;
                }));
    }

    private void hookMiuiOverviewProxy(ClassLoader classLoader) {
        try {
            Class<?> proxyClass = Class.forName(MIUI_OVERVIEW_PROXY, false, classLoader);
            Method method = proxyClass.getDeclaredMethod("onTransact",
                    int.class, Parcel.class, Parcel.class, int.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_block_miui_gesture_line_progress")
                    .intercept(this::interceptMiuiOverviewProxyTransact));
            log(Log.INFO, TAG, "Hooked MiuiOverviewProxy.onTransact");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook MiuiOverviewProxy", throwable);
        }
    }

    private Object interceptMiuiOverviewProxyTransact(XposedInterface.Chain chain)
            throws Throwable {
        int code = (Integer) chain.getArg(0);
        if (code != TRANSACTION_MIUI_ON_GESTURE_LINE_PROGRESS) {
            return chain.proceed();
        }

        Parcel reply = (Parcel) chain.getArg(2);
        if (reply != null) {
            reply.writeNoException();
        }
        return Boolean.TRUE;
    }

    private void hookLauncherProxyService(ClassLoader classLoader) {
        try {
            Class<?> serviceClass = Class.forName(LAUNCHER_PROXY_SERVICE, false, classLoader);
            hookNoArgTrace(serviceClass, "maybeBindService",
                    "systemui_launcher_proxy_maybeBindService");
            hookMethodTrace(serviceClass.getDeclaredMethod("disconnectFromLauncherService",
                            String.class),
                    "systemui_launcher_proxy_disconnect");
            hookNoArgTrace(serviceClass, "updateEnabledAndBinding",
                    "systemui_launcher_proxy_updateEnabledAndBinding");
            log(Log.INFO, TAG, "Hooked LauncherProxyService trace points");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook LauncherProxyService", throwable);
        }
    }

    private void hookEdgeBackGestureHandler(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(EDGE_BACK_GESTURE_HANDLER, false, classLoader);
            hookNoArgTrace(handlerClass, "updateIsEnabled",
                    "systemui_edge_back_updateIsEnabled");
            hookMethodTrace(handlerClass.getDeclaredMethod("onNavigationModeChanged",
                            int.class),
                    "systemui_edge_back_onNavigationModeChanged");
            hookNoArgTrace(handlerClass, "updateBackAnimationThresholds",
                    "systemui_edge_back_updateThresholds");
            hookMethodTrace(handlerClass.getDeclaredMethod("setBackAnimation",
                            Class.forName(BACK_ANIMATION_CONTROLLER + "$BackAnimationImpl",
                                    false, classLoader)),
                    "systemui_edge_back_setBackAnimation");
            log(Log.INFO, TAG, "Hooked EdgeBackGestureHandler AOSP path");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook EdgeBackGestureHandler", throwable);
        }
    }

    private void hookNavigationBarGestureInsets(ClassLoader classLoader) {
        try {
            Class<?> navigationBarClass = Class.forName(NAVIGATION_BAR, false, classLoader);
            Method method = navigationBarClass.getDeclaredMethod(
                    "getBarLayoutParamsForRotation", int.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_navigation_bar_gesture_insets")
                    .intercept(this::restoreNavigationBarGestureInsets));
            log(Log.INFO, TAG, "Hooked NavigationBar system gesture Insets restoration");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook NavigationBar gesture Insets", throwable);
        }
    }


    private Object restoreNavigationBarGestureInsets(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (!(result instanceof WindowManager.LayoutParams)) {
            return result;
        }

        Object navigationBar = chain.getThisObject();
        Object edgeBackGestureHandler = readField(navigationBar, "mEdgeBackGestureHandler");
        Object inGestureNavMode = readField(edgeBackGestureHandler, "mInGestureNavMode");
        Object backGestureAllowed = readField(edgeBackGestureHandler,
                "mIsBackGestureAllowed");
        if (!Boolean.TRUE.equals(inGestureNavMode)
                || !Boolean.TRUE.equals(backGestureAllowed)) {
            log(Log.INFO, TAG, "Kept system gesture Insets empty"
                    + ", gesturalMode=" + inGestureNavMode
                    + ", backAllowed=" + backGestureAllowed);
            return result;
        }

        Context context = (Context) readField(navigationBar, "mContext");
        int fallbackWidth = Math.max(1, Math.round(EDGE_TOUCH_WIDTH_DP
                * context.getResources().getDisplayMetrics().density));
        int leftWidth = readIntFieldOrDefault(edgeBackGestureHandler,
                "mEdgeWidthLeft", fallbackWidth);
        int rightWidth = readIntFieldOrDefault(edgeBackGestureHandler,
                "mEdgeWidthRight", fallbackWidth);
        if (leftWidth <= 0) {
            leftWidth = fallbackWidth;
        }
        if (rightWidth <= 0) {
            rightWidth = fallbackWidth;
        }

        Object providers = readField(result, "providedInsets");
        if (providers == null || !providers.getClass().isArray()) {
            log(Log.WARN, TAG, "NavigationBar LayoutParams has no providedInsets array");
            return result;
        }

        int restored = 0;
        int systemGestureType = WindowInsets.Type.systemGestures();
        for (int i = 0; i < Array.getLength(providers); i++) {
            Object provider = Array.get(providers, i);
            if (provider == null) {
                continue;
            }
            Object type = invokeAnyMethod(provider, "getType", new Object[0]);
            if (!(type instanceof Number)
                    || ((Number) type).intValue() != systemGestureType) {
                continue;
            }
            Object index = invokeAnyMethod(provider, "getIndex", new Object[0]);
            if (!(index instanceof Number)) {
                continue;
            }
            int providerIndex = ((Number) index).intValue();
            Insets size;
            if (providerIndex == 0) {
                size = Insets.of(leftWidth, 0, 0, 0);
            } else if (providerIndex == 1) {
                size = Insets.of(0, 0, rightWidth, 0);
            } else {
                continue;
            }
            invokeAnyMethod(provider, "setInsetsSize", new Object[]{size});
            invokeAnyMethod(provider, "setMinimalInsetsSizeInDisplayCutoutSafe",
                    new Object[]{size});
            restored++;
        }
        log(restored == 2 ? Log.INFO : Log.WARN, TAG,
                "Restored NavigationBar system gesture Insets"
                        + ", left=" + leftWidth
                        + ", right=" + rightWidth
                        + ", providers=" + restored);
        return result;
    }

    private void hookShellBackAnimation(ClassLoader classLoader) {
        try {
            Class<?> controllerClass =
                    Class.forName(BACK_ANIMATION_CONTROLLER, false, classLoader);
            hookMethodTrace(controllerClass.getDeclaredMethod("onGestureStarted",
                            float.class, float.class, int.class),
                    "shell_back_onGestureStarted");
            hookNoArgTrace(controllerClass, "onThresholdCrossed",
                    "shell_back_onThresholdCrossed");
            hookNoArgTrace(controllerClass, "startSystemAnimation",
                    "shell_back_startSystemAnimation");
            hookMethodTrace(controllerClass.getDeclaredMethod("finishBackNavigation",
                            boolean.class),
                    "shell_back_finishBackNavigation");
            hookNoArgTrace(controllerClass, "onBackAnimationFinished",
                    "shell_back_onBackAnimationFinished");
            hookOptionalNoArgTrace(controllerClass, "finishBackAnimation",
                    "shell_back_finishBackAnimation");
            hookBackNavigationInfoReceived(controllerClass);
            hookRegistryUpdate(classLoader);
            hookCrossActivityDiagnostics(classLoader);
            log(Log.INFO, TAG, "Hooked Shell BackAnimationController AOSP path");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook Shell back animation", throwable);
        }
    }

    private void hookCrossActivityDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> crossClass =
                    Class.forName(CROSS_ACTIVITY_BACK_ANIMATION, false, classLoader);
            for (Method method : crossClass.getDeclaredMethods()) {
                if ("startBackAnimation".equals(method.getName())
                        || "finishAnimation".equals(method.getName())) {
                    hookMethodTrace(method, "cross_activity_" + method.getName());
                }
            }
            hookCrossActivityApplyTransformDiagnostics(classLoader);
            hookPreCommitDiagnostics(classLoader, DEFAULT_CROSS_ACTIVITY_BACK_ANIMATION,
                    "default_cross_activity");
            hookPreCommitDiagnostics(classLoader, CUSTOM_CROSS_ACTIVITY_BACK_ANIMATION,
                    "custom_cross_activity");
            log(Log.INFO, TAG, "Hooked CrossActivityBackAnimation diagnostics");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook CrossActivity diagnostics", throwable);
        }
    }

    private void hookCrossActivityApplyTransformDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> crossClass =
                    Class.forName(CROSS_ACTIVITY_BACK_ANIMATION, false, classLoader);
            for (Method method : crossClass.getDeclaredMethods()) {
                if ("applyTransform".equals(method.getName())
                        && method.getParameterCount() >= 3) {
                    hookMethodTrace(method, "cross_activity_applyTransform");
                    log(Log.INFO, TAG, "Hooked CrossActivityBackAnimation.applyTransform diagnostics"
                            + ", signature=" + method);
                    return;
                }
            }
            log(Log.WARN, TAG, "CrossActivityBackAnimation.applyTransform not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook CrossActivity applyTransform diagnostics",
                    throwable);
        }
    }

    private void hookPreCommitDiagnostics(ClassLoader classLoader, String className, String prefix) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            hookMethodTrace(clazz.getDeclaredMethod("preparePreCommitClosingRectMovement",
                            int.class),
                    prefix + "_prepareClosing");
            hookNoArgTrace(clazz, "preparePreCommitEnteringRectMovement",
                    prefix + "_prepareEntering");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook " + className + " diagnostics", throwable);
        }
    }

    private void hookBackNavigationInfoReceived(Class<?> controllerClass) {
        for (Method method : controllerClass.getDeclaredMethods()) {
            if (!"onBackNavigationInfoReceived".equals(method.getName())
                    || method.getParameterCount() != 2) {
                continue;
            }
            hookMethodTrace(method, "shell_back_onBackNavigationInfoReceived");
            return;
        }
        log(Log.WARN, TAG, "BackAnimationController.onBackNavigationInfoReceived not found");
    }

    private void hookRegistryUpdate(ClassLoader classLoader) {
        try {
            Class<?> registryClass =
                    Class.forName(SHELL_BACK_ANIMATION_REGISTRY, false, classLoader);
            Method method = registryClass.getDeclaredMethod("updateSupportedAnimators");
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("shell_back_registry_updateSupportedAnimators")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        logRegistryState(chain.getThisObject(), "updateSupportedAnimators");
                        return result;
                    }));
            log(Log.INFO, TAG, "Hooked ShellBackAnimationRegistry.updateSupportedAnimators");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook ShellBackAnimationRegistry", throwable);
        }
    }

    private void hookNoArgTrace(Class<?> ownerClass, String methodName, String hookId)
            throws NoSuchMethodException {
        Method method = ownerClass.getDeclaredMethod(methodName);
        hookMethodTrace(method, hookId);
    }

    private void hookOptionalNoArgTrace(Class<?> ownerClass, String methodName, String hookId) {
        try {
            hookNoArgTrace(ownerClass, methodName, hookId);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Optional Shell method unavailable: " + methodName,
                    throwable);
        }
    }

    private void hookMethodTrace(Method method, String hookId) {
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId(hookId)
                .intercept(chain -> traceHook(chain, hookId)));
    }

    private Object traceHook(XposedInterface.Chain chain, String hookId) throws Throwable {
        if ("cross_activity_applyTransform".equals(hookId)) {
            if (!ENABLE_VERBOSE_DIAGNOSTICS) {
                return chain.proceed();
            }
            return traceCrossActivityApplyTransform(chain);
        }
        if (ENABLE_VERBOSE_DIAGNOSTICS) {
            log(Log.INFO, TAG, "before " + hookId
                    + ", this=" + shortObject(chain.getThisObject())
                    + ", args=" + describeArgs(chain));
        }
        if ("shell_back_onBackNavigationInfoReceived".equals(hookId)) {
            ensureAospBackAnimations(chain.getThisObject(), "beforeNavigationInfo");
            forceSystemUiCallbackProgress(chain.getArg(0));
        }
        boolean b = hookId.startsWith("cross_activity_")
                || hookId.startsWith("default_cross_activity_")
                || hookId.startsWith("custom_cross_activity_");
        if (b && ENABLE_VERBOSE_DIAGNOSTICS) {
            logCrossActivityState("before " + hookId, chain.getThisObject(), chain.getArgs());
        }
        Object result = chain.proceed();
        if ("shell_back_onBackAnimationFinished".equals(hookId)
                || "shell_back_finishBackAnimation".equals(hookId)) {
            notifyShellAnimationFinished(chain.getThisObject(), hookId);
        }
        switch (hookId) {
            case "systemui_edge_back_setBackAnimation":
                installBackInputOverlay(chain.getThisObject(), chain.getArg(0));
                logNativeInputState(chain.getThisObject(), "setBackAnimation");
                break;
            case "systemui_edge_back_updateIsEnabled":
            case "systemui_edge_back_onNavigationModeChanged":
                ensureBackInputInstalledFromHandler(chain.getThisObject(), hookId);
                logNativeInputState(chain.getThisObject(), hookId);
                break;
            case "shell_back_onBackNavigationInfoReceived":
                logBackNavigationInfo(chain.getArg(0));
                break;
        }
        if (b && ENABLE_VERBOSE_DIAGNOSTICS) {
            logCrossActivityState("after " + hookId, chain.getThisObject(), chain.getArgs());
        }
        if (ENABLE_VERBOSE_DIAGNOSTICS) {
            log(Log.INFO, TAG, "after " + hookId
                    + ", result=" + shortObject(result));
        }
        return result;
    }

    private void notifyShellAnimationFinished(Object controller, String reason) {
        for (NativeBackInputMonitor monitor
                : new ArrayList<>(nativeInputMonitors.values())) {
            monitor.onShellAnimationFinished(controller, reason);
        }
        for (SystemUiBackInputOverlay overlay : new ArrayList<>(overlays.values())) {
            overlay.onShellAnimationFinished(controller, reason);
        }
    }

    private Object traceCrossActivityApplyTransform(XposedInterface.Chain chain)
            throws Throwable {
        try {
            Object animation = chain.getThisObject();
            Object leash = chain.getArg(0);
            Object rect = chain.getArg(1);
            Object alpha = chain.getArg(2);
            Object closing = readField(animation, "closingTarget");
            Object entering = readField(animation, "enteringTarget");
            Object closingLeash = closing == null ? null : readField(closing, "leash");
            Object enteringLeash = entering == null ? null : readField(entering, "leash");
            String targetRole = "unknown";
            if (leash == closingLeash) {
                targetRole = "closing";
            } else if (leash == enteringLeash) {
                targetRole = "entering";
            }
            log(Log.INFO, TAG, "CrossActivity.applyTransform"
                    + ", targetRole=" + targetRole
                    + ", rect=" + rect
                    + ", alpha=" + alpha
                    + ", leash=" + shortObject(leash)
                    + ", closing=" + describeRemoteTarget(closing)
                    + ", entering=" + describeRemoteTarget(entering));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to trace cross-activity applyTransform", throwable);
        }
        return chain.proceed();
    }

    private void installBackInputOverlay(Object edgeBackGestureHandler, Object backAnimationImpl) {
        try {
            if (edgeBackGestureHandler == null || backAnimationImpl == null) {
                return;
            }
            if (!INSTALL_WINDOW_INPUT_OVERLAY) {
                Object controller = readField(backAnimationImpl, "this$0");
                ensureAospBackAnimations(controller, "setBackAnimation");
                Context context = (Context) readField(edgeBackGestureHandler, "mContext");
                ensureNativeEdgeBackPlugin(edgeBackGestureHandler, context);
                NativeBackInputMonitor existing = nativeInputMonitors.get(edgeBackGestureHandler);
                if (existing != null) {
                    existing.updateBackAnimation(backAnimationImpl);
                    log(Log.INFO, TAG, "Updated native SystemUI back input monitor"
                            + ", controller=" + shortObject(controller));
                    return;
                }
                NativeBackInputMonitor monitor = createNativeBackInputMonitor(context,
                        edgeBackGestureHandler, controller, backAnimationImpl);
                monitor.attach();
                nativeInputMonitors.put(edgeBackGestureHandler, monitor);
                log(Log.INFO, TAG, "Installed native SystemUI back input monitor"
                        + ", controller=" + shortObject(controller));
                return;
            }
            SystemUiBackInputOverlay existing = overlays.get(edgeBackGestureHandler);
            if (existing != null) {
                existing.updateBackAnimation(backAnimationImpl);
                return;
            }
            Context context = (Context) readField(edgeBackGestureHandler, "mContext");
            ensureNativeEdgeBackPlugin(edgeBackGestureHandler, context);
            Object controller = readField(backAnimationImpl, "this$0");
            ensureAospBackAnimations(controller, "setBackAnimation");
            SystemUiBackInputOverlay overlay =
                    new SystemUiBackInputOverlay(context, edgeBackGestureHandler,
                            controller, backAnimationImpl);
            overlay.attach();
            overlays.put(edgeBackGestureHandler, overlay);
            log(Log.INFO, TAG, "Installed SystemUI back input overlay"
                    + ", controller=" + shortObject(controller));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install SystemUI back input overlay", throwable);
        }
    }

    private void ensureBackInputInstalledFromHandler(Object edgeBackGestureHandler,
                                                     String reason) {
        if (edgeBackGestureHandler == null) {
            return;
        }
        try {
            if (nativeInputMonitors.containsKey(edgeBackGestureHandler)
                    || overlays.containsKey(edgeBackGestureHandler)) {
                return;
            }
            Object backAnimation = readField(edgeBackGestureHandler, "mBackAnimation");
            if (backAnimation == null) {
                log(Log.INFO, TAG, "Cannot restore back input from handler yet"
                        + ", reason=" + reason + ", mBackAnimation=null");
                return;
            }
            installBackInputOverlay(edgeBackGestureHandler, backAnimation);
            log(Log.INFO, TAG, "Restored back input from existing EdgeBackGestureHandler"
                    + ", reason=" + reason);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to restore back input from handler"
                    + ", reason=" + reason, throwable);
        }
    }

    private void ensureNativeEdgeBackPlugin(Object edgeBackGestureHandler, Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(
                    () -> ensureNativeEdgeBackPlugin(edgeBackGestureHandler, context));
            return;
        }
        try {
            Object existing = readField(edgeBackGestureHandler, "mEdgeBackPlugin");
            if (existing != null && isNativePluginAttached(existing)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object plugin = createNativeEdgeBackPluginFromFactory(edgeBackGestureHandler, context);
            if (plugin != null) {
                invokeAnyMethod(edgeBackGestureHandler, "setEdgeBackPlugin",
                        new Object[]{plugin});
                log(Log.INFO, TAG, "Installed native AOSP NavigationEdgeBackPlugin: "
                        + shortObject(plugin));
                return;
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to create native AOSP edge back plugin", throwable);
        }
        logNativePluginDiagnostics(edgeBackGestureHandler);
    }

    private boolean isNativePluginAttached(Object plugin) {
        try {
            Object view = readField(plugin, "mView");
            if (view instanceof View) {
                return ((View) view).isAttachedToWindow();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private Object createNativeEdgeBackPluginFromFactory(Object edgeBackGestureHandler,
                                                         Context context) throws Exception {
        Handler handler = findUiHandler(edgeBackGestureHandler);
        for (Field field : edgeBackGestureHandler.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object candidate = field.get(edgeBackGestureHandler);
            if (candidate == null) {
                continue;
            }
            for (Method method : candidate.getClass().getMethods()) {
                if (!"create".equals(method.getName())
                        || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (!types[0].isAssignableFrom(Context.class)
                        && !Context.class.isAssignableFrom(types[0])) {
                    continue;
                }
                if (!types[1].isAssignableFrom(Handler.class)
                        && !Handler.class.isAssignableFrom(types[1])) {
                    continue;
                }
                method.setAccessible(true);
                Object plugin = method.invoke(candidate, context, handler);
                log(Log.INFO, TAG, "Created native edge back plugin from field "
                        + field.getName() + ", factory=" + shortObject(candidate)
                        + ", plugin=" + shortObject(plugin));
                return plugin;
            }
        }
        return null;
    }

    private Handler findUiHandler(Object edgeBackGestureHandler) {
        try {
            Object uiThreadContext = readField(edgeBackGestureHandler, "mUiThreadContext");
            Object handler = readField(uiThreadContext, "handler");
            if (handler instanceof Handler) {
                return (Handler) handler;
            }
        } catch (Throwable ignored) {
        }
        return new Handler(Looper.getMainLooper());
    }

    private void logNativePluginDiagnostics(Object edgeBackGestureHandler) {
        if (nativePluginDiagnosticsLogged) {
            return;
        }
        nativePluginDiagnosticsLogged = true;
        try {
            StringBuilder fields = new StringBuilder();
            for (Field field : edgeBackGestureHandler.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = null;
                try {
                    value = field.get(edgeBackGestureHandler);
                } catch (Throwable ignored) {
                }
                fields.append(field.getName())
                        .append("=")
                        .append(shortObject(value))
                        .append("; ");
            }
            log(Log.WARN, TAG, "Native edge plugin unavailable; handler fields: " + fields);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to dump EdgeBackGestureHandler fields", throwable);
        }
        try {
            Class<?> controllerClass = Class.forName(
                    "com.android.systemui.navigationbar.gestural.BackPanelController",
                    false, edgeBackGestureHandler.getClass().getClassLoader());
            StringBuilder constructors = new StringBuilder();
            for (Constructor<?> constructor : controllerClass.getDeclaredConstructors()) {
                constructors.append(constructor.toString()).append("; ");
            }
            log(Log.WARN, TAG, "BackPanelController constructors: " + constructors);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to dump BackPanelController constructors", throwable);
        }
    }

    private void logNativeInputState(Object edgeBackGestureHandler, String reason) {
        if (edgeBackGestureHandler == null) {
            return;
        }
        try {
            log(Log.INFO, TAG, "Native EdgeBack input state"
                    + ", reason=" + reason
                    + ", inputMonitorResources="
                    + shortObject(readField(edgeBackGestureHandler, "mInputMonitorResources"))
                    + ", allowGesture="
                    + shortObject(readField(edgeBackGestureHandler, "mAllowGesture"))
                    + ", isBackGestureAllowed="
                    + shortObject(readField(edgeBackGestureHandler, "mIsBackGestureAllowed"))
                    + ", gestureBlockingActivityRunning="
                    + shortObject(readField(edgeBackGestureHandler,
                    "mGestureBlockingActivityRunning"))
                    + ", excludeRegion="
                    + shortObject(readField(edgeBackGestureHandler, "mExcludeRegion"))
                    + ", unrestrictedExcludeRegion="
                    + shortObject(readField(edgeBackGestureHandler,
                    "mUnrestrictedExcludeRegion"))
                    + ", navBarOverlayExcludedBounds="
                    + shortObject(readField(edgeBackGestureHandler,
                    "mNavBarOverlayExcludedBounds"))
                    + ", pipExcludedBounds="
                    + shortObject(readField(edgeBackGestureHandler, "mPipExcludedBounds"))
                    + ", backAnimation="
                    + shortObject(readField(edgeBackGestureHandler, "mBackAnimation"))
                    + ", edgePlugin="
                    + shortObject(readField(edgeBackGestureHandler, "mEdgeBackPlugin")));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to log native EdgeBack input state", throwable);
        }
    }

    private void logRegistryState(Object registry, String source) {
        try {
            ensureAospRegistryDefinitions(registry, source);
            Object animationDefinition = readField(registry, "mAnimationDefinition");
            Object supportedAnimators = readField(registry, "mSupportedAnimators");
            Object defaultCrossActivity = readField(registry, "mDefaultCrossActivityAnimation");
            Object customizeActivity = readField(registry, "mCustomizeActivityAnimation");
            Object crossTask = readField(registry, "mCrossTaskAnimation");
            log(Log.INFO, TAG, "ShellBackAnimationRegistry." + source
                    + ": definitions=" + animationDefinition
                    + ", definitionKeys=" + sparseArrayKeys(animationDefinition)
                    + ", supported=" + supportedAnimators
                    + ", defaultCrossActivity=" + shortObject(defaultCrossActivity)
                    + ", customizeActivity=" + shortObject(customizeActivity)
                    + ", crossTask=" + shortObject(crossTask));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect ShellBackAnimationRegistry", throwable);
        }
    }

    private void ensureAospBackAnimations(Object controller, String source) {
        if (controller == null) {
            return;
        }
        try {
            Object registry = readField(controller, "mShellBackAnimationRegistry");
            ensureAospRegistryDefinitions(registry, source);
            logRegistryState(registry, source);
            logBackAnimationAdapter(controller, source);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to ensure AOSP back animations from " + source,
                    throwable);
        }
    }

    private void ensureAospRegistryDefinitions(Object registry, String source) {
        if (registry == null) {
            return;
        }
        boolean changed = false;
        try {
            Object definitions = readField(registry, "mAnimationDefinition");
            Object defaultCrossActivity = readField(registry, "mDefaultCrossActivityAnimation");
            Object crossTask = readField(registry, "mCrossTaskAnimation");
            changed |= ensureRegistryRunner(definitions, TYPE_CROSS_ACTIVITY,
                    defaultCrossActivity, "crossActivity");
            changed |= ensureRegistryRunner(definitions, TYPE_CROSS_TASK,
                    crossTask, "crossTask");
            if (changed) {
                invokeAnyMethod(registry, "updateSupportedAnimators", new Object[0]);
                log(Log.INFO, TAG, "Restored AOSP registry definitions from " + source
                        + ", keys=" + sparseArrayKeys(definitions));
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to restore AOSP registry definitions from " + source,
                    throwable);
        }
    }

    private boolean ensureRegistryRunner(Object definitions, int type, Object animation,
                                         String label) {
        if (definitions == null || animation == null || sparseArrayContains(definitions, type)) {
            return false;
        }
        try {
            Object runner = invokeAnyMethod(animation, "getRunner", new Object[0]);
            if (runner == null) {
                return false;
            }
            invokeAnyMethod(definitions, "set",
                    new Object[]{Integer.valueOf(type), runner});
            log(Log.INFO, TAG, "Added " + label + " runner to registry, type=" + type
                    + ", runner=" + shortObject(runner));
            return true;
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to add " + label + " runner, type=" + type,
                    throwable);
            return false;
        }
    }

    private void logBackAnimationAdapter(Object controller, String source) {
        try {
            Object adapter = readField(controller, "mBackAnimationAdapter");
            Object supported = adapter == null ? null : readField(adapter, "mSupportedAnimators");
            log(Log.INFO, TAG, "BackAnimationAdapter." + source
                    + ": adapter=" + shortObject(adapter)
                    + ", supported=" + supported);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect BackAnimationAdapter from " + source,
                    throwable);
        }
    }

    private void logCrossActivityState(String source, Object animation, List<Object> args) {
        if (animation == null) {
            return;
        }
        try {
            Object closing = readField(animation, "closingTarget");
            Object entering = readField(animation, "enteringTarget");
            Object startClosing = readField(animation, "startClosingRect");
            Object targetClosing = readField(animation, "targetClosingRect");
            Object currentClosing = readField(animation, "currentClosingRect");
            Object startEntering = readField(animation, "startEnteringRect");
            Object targetEntering = readField(animation, "targetEnteringRect");
            Object currentEntering = readField(animation, "currentEnteringRect");
            Object backAnimRect = readField(animation, "backAnimRect");
            Object gestureProgress = readField(animation, "gestureProgress");
            log(Log.INFO, TAG, "CrossActivity." + source
                    + ", class=" + animation.getClass().getName()
                    + ", args=" + describeObjectList(args)
                    + ", backMotion=" + describeBackMotionArgs(args)
                    + ", progress=" + gestureProgress
                    + ", backAnimRect=" + backAnimRect
                    + ", closing=" + describeRemoteTarget(closing)
                    + ", entering=" + describeRemoteTarget(entering)
                    + ", startClosing=" + startClosing
                    + ", targetClosing=" + targetClosing
                    + ", currentClosing=" + currentClosing
                    + ", startEntering=" + startEntering
                    + ", targetEntering=" + targetEntering
                    + ", currentEntering=" + currentEntering);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to log cross activity state from " + source,
                    throwable);
        }
    }

    private String describeObjectList(List<Object> args) {
        if (args == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(shortObject(args.get(i)));
        }
        return builder.append(']').toString();
    }

    private String describeArray(Object array) {
        if (array == null) {
            return "null";
        }
        if (!array.getClass().isArray()) {
            return shortObject(array);
        }
        int length = java.lang.reflect.Array.getLength(array);
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(shortObject(java.lang.reflect.Array.get(array, i)));
        }
        return builder.append(']').toString();
    }

    private String describeRemoteTarget(Object target) {
        if (target == null) {
            return "null";
        }
        try {
            Object mode = readField(target, "mode");
            Object prefixOrderIndex = readField(target, "prefixOrderIndex");
            Object localBounds = readField(target, "localBounds");
            Object screenSpaceBounds = readField(target, "screenSpaceBounds");
            Object leash = readField(target, "leash");
            Object windowConfiguration = readField(target, "windowConfiguration");
            Object bounds = windowConfiguration == null ? null
                    : invokeAnyMethod(windowConfiguration, "getBounds", new Object[0]);
            return target.getClass().getName()
                    + "@" + Integer.toHexString(System.identityHashCode(target))
                    + "{mode=" + mode
                    + ", order=" + prefixOrderIndex
                    + ", local=" + localBounds
                    + ", screen=" + screenSpaceBounds
                    + ", bounds=" + bounds
                    + ", leash=" + shortObject(leash) + "}";
        } catch (Throwable throwable) {
            return shortObject(target);
        }
    }

    private String describeBackMotionArgs(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "none";
        }
        Object event = args.get(0);
        if (event == null || !event.getClass().getName().contains("BackMotionEvent")) {
            return "none";
        }
        try {
            BackMotionEvent motionEvent = (BackMotionEvent) event;
            return "{touchX=" + motionEvent.getTouchX()
                    + ", touchY=" + motionEvent.getTouchY()
                    + ", progress=" + motionEvent.getProgress()
                    + ", triggerBack=" + motionEvent.getTriggerBack()
                    + ", swipeEdge=" + motionEvent.getSwipeEdge()
                    + ", departing=" + describeRemoteTarget(
                    motionEvent.getDepartingAnimationTarget()) + "}";
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }

    private boolean sparseArrayContains(Object sparseArray, int key) {
        try {
            int size = ((Integer) invokeAnyMethod(sparseArray, "size", new Object[0])).intValue();
            for (int i = 0; i < size; i++) {
                Object keyAt = invokeAnyMethod(sparseArray, "keyAt",
                        new Object[]{Integer.valueOf(i)});
                if (keyAt instanceof Integer && ((Integer) keyAt).intValue() == key) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect SparseArray contains key=" + key, throwable);
        }
        return false;
    }

    private String sparseArrayKeys(Object sparseArray) {
        if (sparseArray == null) {
            return "null";
        }
        try {
            int size = ((Integer) invokeAnyMethod(sparseArray, "size", new Object[0])).intValue();
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(invokeAnyMethod(sparseArray, "keyAt",
                        new Object[]{Integer.valueOf(i)}));
            }
            return builder.append(']').toString();
        } catch (Throwable throwable) {
            return "unknown:" + throwable.getClass().getSimpleName();
        }
    }

    private void recordHookHandle(XposedInterface.HookHandle hookHandle) {
        hookHandles.add(hookHandle);
    }

    private static Object readField(Object target, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object readFieldOrNull(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return readField(target, fieldName);
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }

    private static float readFloatFieldOrDefault(Object target, String fieldName,
                                                 float defaultValue) {
        try {
            Object value = readField(target, fieldName);
            if (value instanceof Number) {
                return ((Number) value).floatValue();
            }
        } catch (Throwable ignored) {
        }
        return defaultValue;
    }

    private static int readIntFieldOrDefault(Object target, String fieldName,
                                             int defaultValue) {
        try {
            Object value = readField(target, fieldName);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return defaultValue;
    }

    private static void writeField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setFieldIfPresent(Object target, String fieldName, Object value) {
        try {
            writeField(target, fieldName, value);
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> ownerClass, String fieldName)
            throws NoSuchFieldException {
        Class<?> current = ownerClass;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(ownerClass.getName() + "." + fieldName);
    }

    private static String describeArgs(XposedInterface.Chain chain) {
        StringBuilder builder = new StringBuilder("[");
        List<Object> args = chain.getArgs();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(shortObject(args.get(i)));
        }
        return builder.append(']').toString();
    }

    private static String shortObject(Object object) {
        if (object == null) {
            return "null";
        }
        String value = String.valueOf(object);
        if (value.length() > 180) {
            value = value.substring(0, 180) + "...";
        }
        return object.getClass().getName() + "{" + value + "}";
    }

    private void logBackNavigationInfo(Object info) {
        if (info == null) {
            log(Log.INFO, TAG, "BackNavigationInfo=null");
            return;
        }
        try {
            BackNavigationInfo navigationInfo = (BackNavigationInfo) info;
            int type = navigationInfo.getType();
            boolean prepare = navigationInfo.isPrepareRemoteAnimation();
            Object callback = navigationInfo.getOnBackInvokedCallback();
            log(Log.INFO, TAG, "BackNavigationInfo detail: type=" + type
                    + ", prepareRemoteAnimation=" + prepare
                    + ", callback=" + shortObject(callback));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect BackNavigationInfo", throwable);
        }
    }

    private void forceSystemUiCallbackProgress(Object info) {
        if (info == null) {
            return;
        }
        try {
            BackNavigationInfo navigationInfo = (BackNavigationInfo) info;
            if (navigationInfo.getType() == TYPE_CALLBACK) {
                navigationInfo.disableAppProgressGenerationAllowed();
                log(Log.INFO, TAG,
                        "Disabled app-generated progress for TYPE_CALLBACK; SystemUI will dispatch progress");
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to force SystemUI callback progress", throwable);
        }
    }

    private NativeBackInputMonitor createNativeBackInputMonitor(Context context,
                                                                Object edgeBackGestureHandler, Object controller, Object backAnimationImpl)
            throws Exception {
        InputManager inputManager = context.getSystemService(InputManager.class);
        int displayId = 0;
        try {
            Object value = readField(edgeBackGestureHandler, "mDisplayId");
            if (value instanceof Integer) {
                displayId = ((Integer) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        Object monitor = invokeAnyMethod(inputManager, "monitorGestureInput",
                new Object[]{"miui-aosp-back", Integer.valueOf(displayId)});
        if (!(monitor instanceof InputMonitor)) {
            throw new IllegalStateException("monitorGestureInput returned "
                    + shortObject(monitor));
        }
        Object inputChannel = ((InputMonitor) monitor).getInputChannel();
        if (!(inputChannel instanceof InputChannel)) {
            throw new IllegalStateException("InputMonitor channel is "
                    + shortObject(inputChannel));
        }
        return new NativeBackInputMonitor(context, edgeBackGestureHandler, controller,
                backAnimationImpl, (InputMonitor) monitor, (InputChannel) inputChannel);
    }

    private final class NativeBackInputMonitor extends InputEventReceiver {
        private final Context context;
        private final Object edgeBackGestureHandler;
        private final InputMonitor inputMonitor;
        private final SystemUiBackInputOverlay driver;
        private Object backAnimationImpl;
        private boolean gestureCandidate;
        private boolean pilfered;
        private int activeEdge;
        private float downX;
        private float downY;

        private NativeBackInputMonitor(Context context, Object edgeBackGestureHandler,
                                       Object controller, Object backAnimationImpl, InputMonitor inputMonitor,
                                       InputChannel inputChannel) {
            super(inputChannel, Looper.getMainLooper());
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.backAnimationImpl = backAnimationImpl;
            this.inputMonitor = inputMonitor;
            this.driver = new SystemUiBackInputOverlay(context, edgeBackGestureHandler,
                    controller, backAnimationImpl);
        }

        void attach() {
            log(Log.INFO, TAG, "Native SystemUI back input receiver attached");
        }

        void detach() {
            try {
                dispose();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispose native back input receiver", throwable);
            }
            try {
                inputMonitor.dispose();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispose native back input monitor", throwable);
            }
            log(Log.INFO, TAG, "Native SystemUI back input receiver detached");
        }

        void updateBackAnimation(Object newBackAnimationImpl) throws Exception {
            this.backAnimationImpl = newBackAnimationImpl;
            driver.updateBackAnimation(newBackAnimationImpl);
        }

        void onShellAnimationFinished(Object finishedController, String reason) {
            driver.onShellAnimationFinished(finishedController, reason);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                if (event instanceof MotionEvent) {
                    handled = handleMotionEvent((MotionEvent) event);
                }
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Native SystemUI back input failed", throwable);
                resetCandidate();
            } finally {
                finishInputEvent(event, handled);
            }
        }

        private boolean handleMotionEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return onNativeDown(event);
                case MotionEvent.ACTION_MOVE:
                    return onNativeMove(event);
                case MotionEvent.ACTION_UP:
                    return onNativeUp(event, true);
                case MotionEvent.ACTION_CANCEL:
                    return onNativeUp(event, false);
                default:
                    return gestureCandidate;
            }
        }

        private boolean onNativeDown(MotionEvent event) {
            resetCandidate();
            int edge = edgeForDown(event);
            if (edge < 0 || !canStartBackGesture(event, edge)) {
                return false;
            }
            gestureCandidate = true;
            activeEdge = edge;
            downX = event.getRawX();
            downY = event.getRawY();
            if (!driver.handleTouch(event, activeEdge)) {
                resetCandidate();
                return false;
            }
            log(Log.INFO, TAG, "Native SystemUI back candidate started"
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return pilfered;
        }

        private boolean onNativeMove(MotionEvent event) {
            if (!gestureCandidate) {
                return false;
            }
            float distance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            float verticalDistance = Math.abs(event.getRawY() - downY);
            if (!pilfered && distance <= 0.0f && verticalDistance > dp(PILFER_THRESHOLD_DP)) {
                cancelNativeCandidate(event, "moved toward edge or vertical before pilfer");
                return false;
            }
            if (!pilfered && verticalDistance > Math.max(dp(PILFER_THRESHOLD_DP),
                    Math.max(0.0f, distance) * 1.5f)) {
                cancelNativeCandidate(event, "vertical gesture before pilfer");
                return false;
            }
            if (!pilfered && distance > dp(PILFER_THRESHOLD_DP)) {
                pilferPointers(distance);
            }
            if (!driver.handleTouch(event, activeEdge)) {
                resetCandidate();
                return false;
            }
            return pilfered;
        }

        private void pilferPointers(float distance) {
            try {
                inputMonitor.pilferPointers();
                pilfered = true;
                log(Log.INFO, TAG, "Native SystemUI back pilfered pointers"
                        + ", distance=" + distance + ", edge=" + activeEdge);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to pilfer native back pointers", throwable);
            }
        }

        private void cancelNativeCandidate(MotionEvent event, String reason) {
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge);
                cancel.recycle();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to cancel native back candidate", throwable);
            }
            log(Log.INFO, TAG, "Cancelled native SystemUI back candidate before pilfer"
                    + ", reason=" + reason
                    + ", edge=" + activeEdge
                    + ", x=" + event.getRawX()
                    + ", y=" + event.getRawY());
            resetCandidate();
        }

        private boolean onNativeUp(MotionEvent event, boolean allowTrigger) {
            if (!gestureCandidate) {
                return false;
            }
            if (allowTrigger && pilfered) {
                driver.handleTouch(event, activeEdge);
            } else {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge);
                cancel.recycle();
            }
            boolean handled = pilfered;
            resetCandidate();
            return handled;
        }

        private int edgeForDown(MotionEvent event) {
            float x = event.getRawX();
            float displayWidth = Math.max(1.0f,
                    context.getResources().getDisplayMetrics().widthPixels);
            float edgeWidth = Math.max(1.0f, dp(EDGE_TOUCH_WIDTH_DP));
            if (x <= edgeWidth) {
                return EDGE_LEFT;
            }
            if (x >= displayWidth - edgeWidth) {
                return EDGE_RIGHT;
            }
            return -1;
        }

        private boolean canStartBackGesture(MotionEvent event, int edge) {
            if (event.getPointerCount() != 1) {
                return false;
            }
            if (isLauncherTopActivity()) {
                log(Log.INFO, TAG, "Ignored native back on launcher top activity");
                return false;
            }
            if (areSystemBarsHidden() && !isNavBarShownTransiently()) {
                log(Log.INFO, TAG, "Deferred native back to immersive transient bars"
                        + ", edge=" + edge + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
                return false;
            }
            if (!isBackGestureAllowedBySystemUiState()) {
                log(Log.INFO, TAG, "Ignored native back while SystemUI state disallows back");
                return false;
            }
            if (isInImeRegion(event)) {
                log(Log.INFO, TAG, "Ignored native back inside visible IME region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            if (isInMiuiSidebarRegion(event)) {
                log(Log.INFO, TAG, "Ignored native back inside MIUI sidebar bounds"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            if (isInExcludedRegion(event, edge)) {
                log(Log.INFO, TAG, "Ignored native back inside exclusion region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            return true;
        }

        private boolean isNavBarShownTransiently() {
            try {
                return Boolean.TRUE.equals(readField(edgeBackGestureHandler,
                        "mIsNavBarShownTransiently"));
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean areSystemBarsHidden() {
            try {
                WindowManager windowManager = context.getSystemService(WindowManager.class);
                WindowInsets insets = windowManager.getCurrentWindowMetrics().getWindowInsets();
                return insets != null
                        && !insets.isVisible(WindowInsets.Type.statusBars())
                        && !insets.isVisible(WindowInsets.Type.navigationBars());
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect immersive system bars", throwable);
                return false;
            }
        }

        private boolean isBackGestureAllowedBySystemUiState() {
            try {
                Object allowed = readField(edgeBackGestureHandler, "mIsBackGestureAllowed");
                if (allowed instanceof Boolean && !((Boolean) allowed).booleanValue()) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object running = readField(edgeBackGestureHandler,
                        "mGestureBlockingActivityRunning");
                Object value = invokeAnyMethod(running, "get", new Object[0]);
                if (Boolean.TRUE.equals(value)) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
            return true;
        }

        private boolean isLauncherTopActivity() {
            try {
                ActivityManager activityManager = context.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
                if (tasks == null || tasks.isEmpty()) {
                    return false;
                }
                ComponentName top = tasks.get(0).topActivity;
                return top != null && MIUI_HOME.equals(top.getPackageName());
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect top activity for native back", throwable);
                return false;
            }
        }

        private boolean isInImeRegion(MotionEvent event) {
            try {
                WindowManager windowManager = context.getSystemService(WindowManager.class);
                WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
                WindowInsets insets = metrics.getWindowInsets();
                if (insets == null || !insets.isVisible(WindowInsets.Type.ime())) {
                    return false;
                }
                Insets imeInsets = insets.getInsets(WindowInsets.Type.ime());
                Rect bounds = metrics.getBounds();
                return imeInsets.bottom > 0 && event.getRawY() >= bounds.bottom - imeInsets.bottom;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect IME region for native back", throwable);
                return false;
            }
        }

        private boolean isInExcludedRegion(MotionEvent event, int edge) {
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            try {
                Object region = readField(edgeBackGestureHandler, "mExcludeRegion");
                if (region instanceof Region && ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object region = readField(edgeBackGestureHandler, "mUnrestrictedExcludeRegion");
                if (region instanceof Region && ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object bounds = readField(edgeBackGestureHandler, "mNavBarOverlayExcludedBounds");
                if (bounds instanceof Rect && ((Rect) bounds).contains(x, y)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object bounds = readField(edgeBackGestureHandler, "mPipExcludedBounds");
                if (bounds instanceof Rect && ((Rect) bounds).contains(x, y)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        private boolean isInMiuiSidebarRegion(MotionEvent event) {
            String encoded;
            try {
                encoded = Settings.Secure.getString(context.getContentResolver(),
                        MIUI_SIDEBAR_BOUNDS);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to read MIUI sidebar bounds", throwable);
                return false;
            }
            if (encoded == null || encoded.trim().isEmpty()) {
                return false;
            }
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            int padding = Math.max(0, Math.round(dp(MIUI_SIDEBAR_EXCLUSION_PADDING_DP)));
            try {
                JSONArray bounds = new JSONArray(encoded);
                for (int i = 0; i < bounds.length(); i++) {
                    JSONObject item = bounds.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    Rect rect = new Rect(item.optInt("l", -1), item.optInt("t", -1),
                            item.optInt("r", -1), item.optInt("b", -1));
                    if (rect.isEmpty()) {
                        continue;
                    }
                    rect.inset(-padding, -padding);
                    if (rect.contains(x, y)) {
                        return true;
                    }
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to parse MIUI sidebar bounds: " + encoded,
                        throwable);
            }
            return false;
        }

        private void resetCandidate() {
            gestureCandidate = false;
            pilfered = false;
            activeEdge = EDGE_LEFT;
            downX = 0.0f;
            downY = 0.0f;
        }

        private float dp(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }
    }

    private final class SystemUiBackInputOverlay {
        private final Context context;
        private final Object edgeBackGestureHandler;
        private final WindowManager windowManager;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final View leftView;
        private final View rightView;
        private Object controller;
        private Object backAnimationImpl;
        private boolean gestureActive;
        private boolean thresholdCrossed;
        private boolean nativePanelActive;
        private boolean triggerBack;
        private boolean shellGestureStarted;
        private boolean gestureSuppressed;
        private int activeEdge;
        private float downX;
        private float downY;

        SystemUiBackInputOverlay(Context context, Object edgeBackGestureHandler,
                                 Object controller, Object backAnimationImpl) {
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.windowManager = context.getSystemService(WindowManager.class);
            this.controller = controller;
            this.backAnimationImpl = backAnimationImpl;
            this.leftView = createEdgeView(EDGE_LEFT);
            this.rightView = createEdgeView(EDGE_RIGHT);
        }

        void updateBackAnimation(Object newBackAnimationImpl) throws Exception {
            this.backAnimationImpl = newBackAnimationImpl;
            this.controller = readField(newBackAnimationImpl, "this$0");
        }

        void attach() {
            mainHandler.post(() -> {
                try {
                    windowManager.addView(leftView, layoutParams(Gravity.LEFT));
                    windowManager.addView(rightView, layoutParams(Gravity.RIGHT));
                    log(Log.INFO, TAG, "SystemUI back input overlay attached");
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "Failed to attach SystemUI back input overlay",
                            throwable);
                }
            });
        }

        void detach() {
            mainHandler.post(() -> {
                removeViewIfAttached(leftView);
                removeViewIfAttached(rightView);
                log(Log.INFO, TAG, "SystemUI back input overlay detached");
            });
        }

        private void removeViewIfAttached(View view) {
            try {
                if (view.isAttachedToWindow()) {
                    windowManager.removeViewImmediate(view);
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to detach SystemUI back input overlay view",
                        throwable);
            }
        }

        private View createEdgeView(int edge) {
            View view = new View(context);
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setOnTouchListener((v, event) -> handleTouch(event, edge));
            return view;
        }

        private WindowManager.LayoutParams layoutParams(int horizontalGravity) {
            int width = Math.max(1, Math.round(dp(EDGE_TOUCH_WIDTH_DP)));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = horizontalGravity | Gravity.TOP;
            lp.setTitle("MiuiBackGestureHook.SystemUiBackInputOverlay");
            addPrivateFlags(lp, PRIVATE_FLAG_TRUSTED_OVERLAY);
            return lp;
        }

        private boolean handleTouch(MotionEvent event, int edge) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return onDown(event, edge);
                    case MotionEvent.ACTION_MOVE:
                        return onMove(event);
                    case MotionEvent.ACTION_UP:
                        return onUp(event, true);
                    case MotionEvent.ACTION_CANCEL:
                        return onUp(event, false);
                    default:
                        return gestureActive;
                }
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "SystemUI back overlay touch failed", throwable);
                gestureActive = false;
                return false;
            }
        }

        private boolean onDown(MotionEvent event, int edge) throws Exception {
            if (!isShellReadyForGesture()) {
                gestureActive = true;
                shellGestureStarted = false;
                gestureSuppressed = true;
                activeEdge = edge;
                downX = event.getRawX();
                downY = event.getRawY();
                log(Log.WARN, TAG, "Suppressed SystemUI back while Shell is busy"
                        + ", state=" + describeShellState());
                return true;
            }
            gestureActive = true;
            shellGestureStarted = false;
            gestureSuppressed = false;
            thresholdCrossed = false;
            nativePanelActive = false;
            triggerBack = false;
            activeEdge = edge;
            downX = event.getRawX();
            downY = event.getRawY();
            // Home and Recents share the same launcher Activity. Resolve the actual back target
            // before feeding DOWN to BackPanelController so idle Home never shows the indicator.
            if (!startShellGesture()) {
                gestureActive = false;
                gestureSuppressed = false;
                log(Log.INFO, TAG, "Ignored edge gesture without a back navigation target"
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                return false;
            }
            dispatchToEdgePlugin(event, activeEdge);
            log(Log.INFO, TAG, "SystemUI overlay gesture candidate"
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        private boolean onMove(MotionEvent event) throws Exception {
            if (!gestureActive) {
                return false;
            }
            if (gestureSuppressed) {
                return true;
            }
            if (!shellGestureStarted && !startShellGesture()) {
                cancelLocalGesture(event, "BackNavigationInfo unavailable");
                return false;
            }
            dispatchToEdgePlugin(event, activeEdge);
            updateActiveTracker(event.getRawX(), event.getRawY());
            float distance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            if (!thresholdCrossed && distance > dp(PILFER_THRESHOLD_DP)) {
                crossIntentThreshold(distance);
            }
            if (thresholdCrossed) {
                dispatchExplicitProgress(distance);
            }
            boolean shouldTrigger = distance > dp(TRIGGER_THRESHOLD_DP);
            updateTriggerBack(shouldTrigger);
            return true;
        }

        private void crossIntentThreshold(float distance) throws Exception {
            if (thresholdCrossed) {
                return;
            }
            thresholdCrossed = true;
            nativePanelActive = true;
            invokeAnyMethod(controller, "onThresholdCrossed", new Object[0]);
            log(Log.INFO, TAG, "SystemUI overlay intent threshold crossed, distance="
                    + distance);
        }

        private boolean onUp(MotionEvent event, boolean allowTrigger) throws Exception {
            if (!gestureActive) {
                return false;
            }
            if (gestureSuppressed) {
                gestureActive = false;
                gestureSuppressed = false;
                log(Log.INFO, TAG, "Finished suppressed SystemUI back gesture");
                return true;
            }
            if (!shellGestureStarted) {
                cancelLocalGesture(event, "released before first MOVE");
                return true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            updateActiveTracker(event.getRawX(), event.getRawY());
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            if (thresholdCrossed) {
                dispatchExplicitProgress(releaseDistance);
            }
            boolean trigger = allowTrigger
                    && thresholdCrossed
                    && releaseDistance > dp(TRIGGER_THRESHOLD_DP);
            updateTriggerBack(trigger);
            Object tracker = invokeAnyMethod(controller, "getActiveTracker", new Object[0]);
            if (tracker != null) {
                if (startRemotePostCommitIfNeeded(tracker)) {
                    log(Log.INFO, TAG, "SystemUI overlay delegated remote post-commit"
                            + ", trigger=" + trigger + ", edge=" + activeEdge);
                } else {
                    invokeAnyMethod(controller, "invokeOrCancelBack", new Object[]{tracker});
                    resetGestureState(tracker);
                }
            } else if (trigger) {
                log(Log.WARN, TAG, "No active back tracker on release; using fallback back");
                dispatchRealBack();
                invokeAnyMethod(controller, "finishBackNavigation",
                        new Object[]{Boolean.TRUE});
                resetGestureState(null);
            } else {
                dispatchCancelBack();
                invokeAnyMethod(controller, "finishBackNavigation",
                        new Object[]{Boolean.FALSE});
                resetGestureState(null);
            }
            log(Log.INFO, TAG, "SystemUI overlay finish, trigger=" + trigger
                    + ", edge=" + activeEdge);
            gestureActive = false;
            shellGestureStarted = false;
            gestureSuppressed = false;
            thresholdCrossed = false;
            nativePanelActive = false;
            triggerBack = false;
            return true;
        }

        private boolean startShellGesture() throws Exception {
            syncAospProgressThresholds();
            invokeAnyMethod(controller, "onGestureStarted",
                    new Object[]{Float.valueOf(downX), Float.valueOf(downY),
                            Integer.valueOf(activeEdge)});
            syncAospProgressThresholds();
            Object info = readField(controller, "mBackNavigationInfo");
            boolean receivedNull = Boolean.TRUE.equals(
                    readField(controller, "mReceivedNullNavigationInfo"));
            if (info == null || receivedNull) {
                log(Log.WARN, TAG, "Shell rejected back navigation"
                        + ", info=" + shortObject(info)
                        + ", receivedNull=" + receivedNull
                        + ", state=" + describeShellState());
                cleanupRejectedShellGesture();
                return false;
            }
            shellGestureStarted = true;
            log(Log.INFO, TAG, "SystemUI overlay onGestureStarted"
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        private boolean isShellReadyForGesture() {
            try {
                if (Boolean.TRUE.equals(readField(controller,
                        "mPostCommitAnimationInProgress"))) {
                    return false;
                }
                if (Boolean.TRUE.equals(readField(controller, "mBackGestureStarted"))) {
                    return false;
                }
                if (readField(controller, "mBackNavigationInfo") != null
                        || readField(controller, "mBackAnimationFinishedCallback") != null) {
                    return false;
                }
                Object current = readField(controller, "mCurrentTracker");
                Object queued = readField(controller, "mQueuedTracker");
                return isTrackerInitial(current) && isTrackerInitial(queued);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect Shell readiness; rejecting gesture",
                        throwable);
                return false;
            }
        }

        private boolean isTrackerInitial(Object tracker) throws Exception {
            return tracker == null || ((BackTouchTracker) tracker).isInInitialState();
        }

        private String describeShellState() {
            try {
                return "postCommit=" + readField(controller, "mPostCommitAnimationInProgress")
                        + ", backStarted=" + readField(controller, "mBackGestureStarted")
                        + ", info=" + shortObject(readField(controller, "mBackNavigationInfo"))
                        + ", finishedCallback=" + shortObject(
                        readField(controller, "mBackAnimationFinishedCallback"))
                        + ", current=" + shortObject(readField(controller, "mCurrentTracker"))
                        + ", queued=" + shortObject(readField(controller, "mQueuedTracker"));
            } catch (Throwable throwable) {
                return "unavailable:" + throwable.getClass().getSimpleName();
            }
        }

        private void cleanupRejectedShellGesture() {
            try {
                writeField(controller, "mBackGestureStarted", Boolean.FALSE);
                Object tracker = invokeAnyMethod(controller, "getActiveTracker", new Object[0]);
                if (tracker != null) {
                    ((BackTouchTracker) tracker).reset();
                }
                invokeAnyMethod(controller, "finishBackNavigation",
                        new Object[]{Boolean.FALSE});
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to clean rejected Shell gesture", throwable);
            }
        }

        private void cancelLocalGesture(MotionEvent event, String reason) {
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                dispatchToEdgePlugin(cancel, activeEdge);
                cancel.recycle();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to cancel local edge panel", throwable);
            }
            gestureActive = false;
            shellGestureStarted = false;
            gestureSuppressed = false;
            thresholdCrossed = false;
            nativePanelActive = false;
            triggerBack = false;
            log(Log.INFO, TAG, "Cancelled local SystemUI back gesture, reason=" + reason);
        }

        private void onShellAnimationFinished(Object finishedController, String reason) {
            if (controller != finishedController) {
                return;
            }
            if (gestureActive) {
                gestureActive = false;
                shellGestureStarted = false;
                gestureSuppressed = false;
                thresholdCrossed = false;
                nativePanelActive = false;
                triggerBack = false;
                log(Log.WARN, TAG, "Cleared local gesture after Shell animation completion"
                        + ", reason=" + reason);
            }
        }

        private boolean startRemotePostCommitIfNeeded(Object tracker) {
            try {
                Object info = readField(controller, "mBackNavigationInfo");
                if (!isPreparedRemoteAnimation(info)) {
                    return false;
                }
                writeField(controller, "mThresholdCrossed", Boolean.FALSE);
                writeField(controller, "mPointersPilfered", Boolean.FALSE);
                writeField(controller, "mBackGestureStarted", Boolean.FALSE);
                setTrackerState(tracker, "FINISHED");
                if (isRemoteAnimationWaiting(info)) {
                    scheduleShellAnimationTimeout();
                    log(Log.INFO, TAG, "SystemUI overlay waiting for remote animation start"
                            + ", type=" + ((BackNavigationInfo) info).getType());
                    return true;
                }
                invokeAnyMethod(controller, "startPostCommitAnimation", new Object[0]);
                return true;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to start remote post-commit path", throwable);
                return false;
            }
        }

        private boolean isRemoteAnimationWaiting(Object info) {
            try {
                int type = ((BackNavigationInfo) info).getType();
                Object registry = readField(controller, "mShellBackAnimationRegistry");
                Object definitions = readField(registry, "mAnimationDefinition");
                Object runner = invokeAnyMethod(definitions, "get",
                        new Object[]{Integer.valueOf(type)});
                return runner != null
                        && Boolean.TRUE.equals(readField(runner, "mWaitingAnimation"));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect remote animation waiting state",
                        throwable);
                return false;
            }
        }

        private void scheduleShellAnimationTimeout() {
            try {
                Object executor = readField(controller, "mShellExecutor");
                Object timeout = readField(controller, "mAnimationTimeoutRunnable");
                invokeAnyMethod(executor, "executeDelayed",
                        new Object[]{timeout, Long.valueOf(2000L)});
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to schedule Shell animation timeout", throwable);
            }
        }

        private boolean isPreparedRemoteAnimation(Object info) {
            if (info == null) {
                return false;
            }
            try {
                return ((BackNavigationInfo) info).isPrepareRemoteAnimation();
            } catch (Throwable throwable) {
                return false;
            }
        }

        private void setTrackerState(Object tracker, String stateName) throws Exception {
            BackTouchTracker.TouchTrackerState state =
                    BackTouchTracker.TouchTrackerState.valueOf(stateName);
            ((BackTouchTracker) tracker).setState(state);
        }

        private float dp(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }

        private void resetGestureState(Object tracker) {
            try {
                writeField(controller, "mBackGestureStarted", Boolean.FALSE);
                if (tracker != null) {
                    ((BackTouchTracker) tracker).reset();
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to reset back gesture state", throwable);
            }
        }

        private void updateTriggerBack(boolean newTriggerBack) {
            if (triggerBack == newTriggerBack) {
                return;
            }
            triggerBack = newTriggerBack;
            try {
                invokeAnyMethod(controller, "setTriggerBack",
                        new Object[]{Boolean.valueOf(newTriggerBack)});
                if (!newTriggerBack && nativePanelActive) {
                    nativePanelActive = false;
                } else if (newTriggerBack && thresholdCrossed) {
                    nativePanelActive = true;
                }
                log(Log.INFO, TAG, "SystemUI overlay triggerBack=" + newTriggerBack);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to update triggerBack=" + newTriggerBack,
                        throwable);
            }
        }

        private void syncAospProgressThresholds() {
            try {
                invokeAnyMethod(edgeBackGestureHandler, "updateDisplaySize$1", new Object[0]);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to update display size for back progress", throwable);
            }
            try {
                Object tracker = invokeAnyMethod(controller, "getActiveTracker", new Object[0]);
                if (tracker != null) {
                    applyProgressThresholds(tracker);
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to sync active tracker progress thresholds",
                        throwable);
            }
        }

        private void applyProgressThresholds(Object tracker) {
            float maxDistance = Math.max(1.0f,
                    context.getResources().getDisplayMetrics().widthPixels);
            float linearThreshold = readFloatFieldOrDefault(edgeBackGestureHandler,
                    "mBackSwipeLinearThreshold", dp(AOSP_PROGRESS_THRESHOLD_DP));
            float linearDistance = Math.min(maxDistance, linearThreshold);
            float nonLinearFactor = readFloatFieldOrDefault(edgeBackGestureHandler,
                    "mNonLinearFactor", 0.0f);
            ((BackTouchTracker) tracker).setProgressThresholds(
                    linearDistance, maxDistance, nonLinearFactor);
        }

        private void updateActiveTracker(float rawX, float rawY) {
            try {
                Object tracker = invokeAnyMethod(controller, "getActiveTracker", new Object[0]);
                if (tracker == null) {
                    return;
                }
                applyProgressThresholds(tracker);
                ((BackTouchTracker) tracker).update(rawX, rawY);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to update active back tracker", throwable);
            }
        }

        private void dispatchExplicitProgress(float distance) {
            try {
                Object tracker = invokeAnyMethod(controller, "getActiveTracker", new Object[0]);
                if (tracker == null) {
                    return;
                }
                Object callback = readField(controller, "mActiveCallback");
                float progress = Math.max(0.0f,
                        Math.min(1.0f, distance / Math.max(1.0f, progressDistancePx())));
                BackMotionEvent progressEvent =
                        ((BackTouchTracker) tracker).createProgressEvent(progress);
                invokeAnyMethod(controller, "dispatchOnBackProgressed",
                        new Object[]{callback, progressEvent});
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispatch explicit back progress", throwable);
            }
        }

        private float progressDistancePx() {
            return Math.max(1.0f, context.getResources().getDisplayMetrics().widthPixels);
        }

        private void dispatchRealBack() {
            try {
                Object info = readField(controller, "mBackNavigationInfo");
                Object callback = info == null ? null
                        : ((BackNavigationInfo) info).getOnBackInvokedCallback();
                if (callback != null) {
                    try {
                        invokeAnyMethod(controller, "dispatchOnBackInvoked",
                                new Object[]{callback});
                    } catch (Throwable ignored) {
                        invokeAnyMethod(callback, "onBackInvoked", new Object[0]);
                    }
                    log(Log.INFO, TAG, "Dispatched real back callback, info="
                            + shortObject(info) + ", callback=" + shortObject(callback));
                    return;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispatch real back callback", throwable);
            }
            injectLegacyBackKey();
        }

        private void dispatchCancelBack() {
            try {
                Object info = readField(controller, "mBackNavigationInfo");
                Object callback = info == null ? null
                        : ((BackNavigationInfo) info).getOnBackInvokedCallback();
                if (callback != null) {
                    try {
                        invokeAnyMethod(controller, "tryDispatchOnBackCancelled",
                                new Object[]{callback});
                    } catch (Throwable ignored) {
                        invokeAnyMethod(callback, "onBackCancelled", new Object[0]);
                    }
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispatch back cancel", throwable);
            }
        }

        private void injectLegacyBackKey() {
            try {
                invokeAnyMethod(controller, "injectBackKey", new Object[0]);
                log(Log.INFO, TAG, "Injected legacy back key via controller");
                return;
            } catch (Throwable ignored) {
            }
            try {
                invokeAnyMethod(controller, "sendBackEvent",
                        new Object[]{Integer.valueOf(KEY_ACTION_DOWN)});
                invokeAnyMethod(controller, "sendBackEvent",
                        new Object[]{Integer.valueOf(KEY_ACTION_UP)});
                log(Log.INFO, TAG, "Injected legacy back key via sendBackEvent");
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to inject legacy back key", throwable);
            }
        }

        private void dispatchToEdgePlugin(MotionEvent event, int edge) {
            try {
                Object plugin = readField(edgeBackGestureHandler, "mEdgeBackPlugin");
                if (plugin == null) {
                    log(Log.WARN, TAG, "NavigationEdgeBackPlugin is null; native panel unavailable");
                    return;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    prepareNativeBackPanel(plugin);
                }
                invokeMethod(plugin, "setIsLeftPanel",
                        new Class<?>[]{boolean.class},
                        new Object[]{Boolean.valueOf(edge == EDGE_LEFT)});
                MotionEvent screenEvent = MotionEvent.obtain(event);
                screenEvent.setLocation(event.getRawX(), event.getRawY());
                invokeMethod(plugin, "onMotionEvent",
                        new Class<?>[]{MotionEvent.class}, new Object[]{screenEvent});
                screenEvent.recycle();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispatch event to NavigationEdgeBackPlugin",
                        throwable);
            }
        }

        private void prepareNativeBackPanel(Object plugin) {
            try {
                invokeAnyMethod(plugin, "updateConfiguration$1", new Object[0]);
                invokeAnyMethod(plugin, "updateRestingArrowDimens", new Object[0]);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to prepare native AOSP back panel", throwable);
            }
        }
    }

    private static Object invokeMethod(Object target, String methodName,
                                       Class<?>[] parameterTypes, Object[] args) throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object invokeBestMethod(Object target, String methodName, Object[] args)
            throws Exception {
        Method best = null;
        for (Method method : target.getClass().getMethods()) {
            if (!methodName.equals(method.getName())
                    || method.getParameterCount() != args.length) {
                continue;
            }
            best = method;
            break;
        }
        if (best == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + methodName);
        }
        best.setAccessible(true);
        return best.invoke(target, args);
    }

    private static Object invokeAnyMethod(Object target, String methodName, Object[] args)
            throws Exception {
        Method best = findAnyMethod(target.getClass(), methodName, args.length);
        if (best == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + methodName);
        }
        best.setAccessible(true);
        return best.invoke(target, args);
    }

    private static Method findAnyMethod(Class<?> type, String methodName, int argCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodName.equals(method.getName())
                        && method.getParameterCount() == argCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            if (methodName.equals(method.getName())
                    && method.getParameterCount() == argCount) {
                return method;
            }
        }
        return null;
    }

    private static void addPrivateFlags(WindowManager.LayoutParams lp, int flags) {
        try {
            Field field = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            field.setAccessible(true);
            field.setInt(lp, field.getInt(lp) | flags);
        } catch (Throwable ignored) {
        }
    }
}
