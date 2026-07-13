package dev.codex.miuibackgesturehook;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
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
    private static final String BUILD_MARK =
            "systemui-aosp-back-v102-clean";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String MIUI_HOME = "com.miui.home";

    private static final String EDGE_BACK_GESTURE_HANDLER =
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler";
    private static final String MIUI_OVERVIEW_PROXY =
            "com.android.systemui.recents.MiuiOverviewProxy";
    private static final String MIUI_HOME_GESTURE_STUB =
            "com.miui.home.recents.GestureStubView";
    private static final String MIUI_HOME_RECENTS_CONTAINER =
            "com.miui.home.recents.views.RecentsContainer";
    private static final String MIUI_HOME_TASK_VIEW =
            "com.miui.home.recents.views.TaskView";
    private static final String NAVIGATION_BAR =
            "com.android.systemui.navigationbar.views.NavigationBar";
    private static final String BACK_ANIMATION_CONTROLLER =
            "com.android.wm.shell.back.BackAnimationController";
    private static final String DEFAULT_TRANSITION_HANDLER =
            "com.android.wm.shell.transition.DefaultTransitionHandler";
    private static final String DEFAULT_TRANSITION_IMPL =
            "com.android.wm.shell.common.transition.DefaultTransitionImpl";
    private static final String BACK_NAVIGATION_CONTROLLER =
            "com.android.server.wm.BackNavigationController";
    private static final String BACK_ANIMATION_HANDLER =
            "com.android.server.wm.BackNavigationController$AnimationHandler";
    private static final String BACK_WINDOW_ANIMATION_ADAPTOR =
            "com.android.server.wm.BackNavigationController$AnimationHandler$BackWindowAnimationAdaptor";
    private static final String SCHEDULE_ANIMATION_BUILDER =
            "com.android.server.wm.BackNavigationController$AnimationHandler$ScheduleAnimationBuilder";
    private static final String DISPLAY_POLICY = "com.android.server.wm.DisplayPolicy";

    private static final int TRANSACTION_MIUI_ON_GESTURE_LINE_PROGRESS = 4;
    private static final int EDGE_LEFT = 0;
    private static final int EDGE_RIGHT = 1;
    private static final String MIUI_FULLSCREEN_STATE_CHANGE =
            "com.miui.fullscreen_state_change";
    private static final String MODULE_MIUI_OVERVIEW_STATE_CHANGE =
            "dev.codex.miuibackgesturehook.action.MIUI_OVERVIEW_STATE_CHANGE";
    private static final int KEY_ACTION_UP = 1;
    private static final int KEY_ACTION_DOWN = 0;
    private static final int TYPE_CROSS_ACTIVITY = 2;
    private static final int TYPE_CROSS_TASK = 3;
    private static final int TYPE_CALLBACK = 4;
    private static final float EDGE_TOUCH_WIDTH_DP = 24.0f;
    private static final float MIUI_INDICATOR_PILFER_THRESHOLD_DP = 1.0f;
    private static final float PILFER_THRESHOLD_DP = 8.0f;
    private static final float TRIGGER_THRESHOLD_DP = 48.0f;
    private static final float AOSP_PROGRESS_THRESHOLD_DP = 412.0f;
    private static final String MIUI_SIDEBAR_BOUNDS = "sidebar_bounds";
    private static final float MIUI_SIDEBAR_EXCLUSION_PADDING_DP = 8.0f;
    private static final long MIUI_OVERVIEW_DISMISS_TIMEOUT_MS = 2500L;
    private static final long MIUI_OVERVIEW_EXIT_GUARD_MS = 400L;

    private final List<XposedInterface.HookHandle> hookHandles = new ArrayList<>();
    private final Map<Object, NativeBackInputMonitor> nativeInputMonitors =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> defaultTransitionHandlers =
            Collections.synchronizedMap(new WeakHashMap<>());
    private volatile boolean miuiOverviewVisible;
    private volatile long miuiOverviewDismissPendingUntilUptime;
    private Context miuiOverviewReceiverContext;
    private BroadcastReceiver miuiOverviewReceiver;
    private String processName;
    private boolean nativePluginDiagnosticsLogged;
    private volatile long suppressDuplicateBackUntilUptime;
    private final ThreadLocal<Boolean> moduleLegacyBackInjection = new ThreadLocal<>();

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
        boolean savedMiuiOverviewVisible = miuiOverviewVisible;
        unregisterMiuiOverviewStateReceiver();
        Object[][] inputState = new Object[nativeInputMonitors.size()][2];
        int index = 0;
        for (Map.Entry<Object, NativeBackInputMonitor> entry
                : new ArrayList<>(nativeInputMonitors.entrySet())) {
            inputState[index][0] = entry.getKey();
            inputState[index][1] = entry.getValue().backAnimationImpl;
            index++;
        }
        for (NativeBackInputMonitor monitor : new ArrayList<>(nativeInputMonitors.values())) {
            monitor.detach();
        }
        nativeInputMonitors.clear();
        param.setSavedInstanceState(new Object[]{
                inputState, Boolean.valueOf(savedMiuiOverviewVisible)
        });
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        processName = param.getProcessName();
        int replaced = 0;
        boolean hadBackWindowStartHook = false;
        boolean hadPrepareTransitionHook = false;
        boolean hadNavigationBarGestureInsetsHook = false;
        boolean hadBackNavigationDoneHook = false;
        boolean hadMiuiHomeGestureStubLayoutHook = false;
        boolean hadMiuiHomeGestureStubShowHook = false;
        boolean hadMiuiHomeGestureStubTouchRegionHook = false;
        boolean hadMiuiHomeRecentsStateHook = false;
        boolean hadMiuiHomeTaskLaunchHook = false;
        boolean hadAnyServerHook = false;
        ClassLoader hotReloadClassLoader = null;
        for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
            try {
                if (oldHandle.getId() != null && oldHandle.getId().startsWith("server_")) {
                    hadAnyServerHook = true;
                }
                if ("server_back_window_start_animation".equals(oldHandle.getId())) {
                    hadBackWindowStartHook = true;
                } else if ("server_schedule_animation_prepare_transition".equals(
                        oldHandle.getId())) {
                    hadPrepareTransitionHook = true;
                } else if ("systemui_navigation_bar_gesture_insets".equals(
                        oldHandle.getId())) {
                    hadNavigationBarGestureInsetsHook = true;
                } else if ("server_back_navigation_done_cleanup".equals(
                        oldHandle.getId())) {
                    hadBackNavigationDoneHook = true;
                } else if ("miui_home_gesture_stub_layout_params".equals(
                        oldHandle.getId())) {
                    hadMiuiHomeGestureStubLayoutHook = true;
                } else if ("miui_home_gesture_stub_show".equals(oldHandle.getId())) {
                    hadMiuiHomeGestureStubShowHook = true;
                } else if ("miui_home_gesture_stub_touch_region".equals(
                        oldHandle.getId())) {
                    hadMiuiHomeGestureStubTouchRegionHook = true;
                } else if ("miui_home_recents_actual_state_v2".equals(oldHandle.getId())) {
                    hadMiuiHomeRecentsStateHook = true;
                } else if ("miui_home_recents_task_launch".equals(oldHandle.getId())) {
                    hadMiuiHomeTaskLaunchHook = true;
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
        restoreHotReloadInput(param.getSavedInstanceState());
        boolean shouldInstallServerHooks = param.isSystemServer()
                || "system".equals(processName)
                || hadAnyServerHook;
        if (shouldInstallServerHooks && replaced == 0) {
            installSystemServerHooks(null);
        }
        if (shouldInstallServerHooks
                && (!hadBackWindowStartHook || !hadPrepareTransitionHook
                || !hadBackNavigationDoneHook)) {
            ClassLoader serverClassLoader = findSystemServerClassLoader(hotReloadClassLoader);
            if (serverClassLoader != null) {
                if (!hadBackWindowStartHook) {
                    hookBackWindowStartAnimation(serverClassLoader);
                }
                if (!hadPrepareTransitionHook) {
                    hookScheduleAnimationPrepareTransition(serverClassLoader);
                }
                if (!hadBackNavigationDoneHook) {
                    hookBackNavigationDoneCleanup(serverClassLoader);
                }
            }
        }
        if (SYSTEM_UI.equals(processName) && !hadNavigationBarGestureInsetsHook
                && hotReloadClassLoader != null) {
            hookNavigationBarGestureInsets(hotReloadClassLoader);
        }
        if (MIUI_HOME.equals(processName) && hotReloadClassLoader != null) {
            try {
                Class<?> gestureStubClass = Class.forName(MIUI_HOME_GESTURE_STUB, false,
                        hotReloadClassLoader);
                if (!hadMiuiHomeGestureStubLayoutHook) {
                    hookMiuiHomeGestureStubLayoutParams(gestureStubClass);
                }
                if (!hadMiuiHomeGestureStubShowHook) {
                    hookMiuiHomeGestureStubShow(gestureStubClass);
                }
                if (!hadMiuiHomeGestureStubTouchRegionHook) {
                    hookMiuiHomeGestureStubTouchRegion(gestureStubClass);
                }
                if (!hadMiuiHomeRecentsStateHook) {
                    Class<?> recentsContainerClass = Class.forName(
                            MIUI_HOME_RECENTS_CONTAINER, false, hotReloadClassLoader);
                    hookMiuiHomeRecentsActualState(recentsContainerClass);
                }
                if (!hadMiuiHomeTaskLaunchHook) {
                    Class<?> taskViewClass = Class.forName(MIUI_HOME_TASK_VIEW, false,
                            hotReloadClassLoader);
                    hookMiuiHomeRecentsTaskLaunch(taskViewClass);
                }
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to restore MiuiHome hooks",
                        throwable);
            }
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
        if ("miui_home_gesture_stub_layout_params".equals(hookId)) {
            return this::makeMiuiHomeGestureStubNotTouchable;
        }
        if ("miui_home_gesture_stub_show".equals(hookId)) {
            return this::blockMiuiHomeGestureStubShow;
        }
        if ("miui_home_gesture_stub_touch_region".equals(hookId)) {
            return this::emptyMiuiHomeGestureStubTouchRegion;
        }
        if ("miui_home_recents_actual_state".equals(hookId)) {
            // v91/v92 hooked the background PowerKeeper notification lambda, which did not
            // retain the RecentsContainer instance needed for native back dispatch.
            return XposedInterface.Chain::proceed;
        }
        if ("miui_home_recents_actual_state_v2".equals(hookId)) {
            return this::mirrorMiuiHomeRecentsActualState;
        }
        if ("miui_home_recents_task_launch".equals(hookId)) {
            return this::mirrorMiuiHomeRecentsTaskLaunch;
        }
        if (hookId.startsWith("miui_home_block_gesture_window_")) {
            // v88 blocked BaseRecentsImpl.addBackStubWindow(), leaving its gesture-stub
            // fields null and short-circuiting unrelated NavStubView visibility updates.
            return XposedInterface.Chain::proceed;
        }
        if (hookId.startsWith("miui_home_")
                || "systemui_default_animation_reverse_frames".equals(hookId)) {
            // Retired experiments may still exist during API 102 hot reload. Replace them with
            // transparent hooks until the next process restart instead of retaining old behavior.
            return XposedInterface.Chain::proceed;
        }
        if ("server_back_promote_to_tf_if_needed".equals(hookId)) {
            return this::interceptPromoteToTaskFragmentIfNeeded;
        }
        if ("server_back_window_start_animation".equals(hookId)) {
            return this::prepareOpeningTaskFragment;
        }
        if ("server_schedule_animation_prepare_transition".equals(hookId)) {
            return this::interceptScheduleAnimationPrepareTransition;
        }
        if ("server_back_navigation_done_cleanup".equals(hookId)) {
            return this::cleanupSkippedRemoteAnimationOnNavigationDone;
        }
        if (hookId.startsWith("server_security_sidebar_transient_bars_")) {
            return this::interceptSecuritySidebarTransientBars;
        }
        if ("systemui_default_transition_start".equals(hookId)) {
            return this::registerDefaultTransitionHandler;
        }
        if ("systemui_default_transition_merge".equals(hookId)) {
            return this::trackMiuiOpenCloseMerge;
        }
        if ("systemui_back_send_event_guard".equals(hookId)) {
            return this::guardDuplicateBackEvent;
        }
        if ("systemui_edge_back_setBackAnimation".equals(hookId)) {
            return this::onEdgeBackSetBackAnimation;
        }
        if ("systemui_edge_back_updateIsEnabled".equals(hookId)) {
            return this::onEdgeBackUpdateIsEnabled;
        }
        if ("systemui_edge_back_onNavigationModeChanged".equals(hookId)) {
            return this::onEdgeBackNavigationModeChanged;
        }
        if ("shell_back_onBackNavigationInfoReceived".equals(hookId)) {
            return this::onBackNavigationInfoReceived;
        }
        if ("shell_back_onBackAnimationFinished".equals(hookId)
                || "shell_back_finishBackAnimation".equals(hookId)) {
            return this::onShellAnimationFinished;
        }
        return null;
    }

    private void restoreHotReloadInput(Object savedState) {
        Object inputStateObject = savedState;
        if (savedState instanceof Object[]) {
            Object[] state = (Object[]) savedState;
            if (state.length == 2 && state[0] instanceof Object[][]
                    && state[1] instanceof Boolean) {
                inputStateObject = state[0];
                miuiOverviewVisible = ((Boolean) state[1]).booleanValue();
            }
        }
        if (!(inputStateObject instanceof Object[][])) {
            log(Log.INFO, TAG, "No hot reload back input state to restore");
            return;
        }
        Object[][] inputState = (Object[][]) inputStateObject;
        if (inputState.length == 0) {
            log(Log.INFO, TAG, "Hot reload back input state is empty; "
                    + "will restore from next EdgeBackGestureHandler callback");
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            int restored = 0;
            for (Object[] pair : inputState) {
                if (pair == null || pair.length < 2) {
                    continue;
                }
                installBackInputDriver(pair[0], pair[1]);
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
            installSystemUiHooks(param.getDefaultClassLoader());
        } else if (MIUI_HOME.equals(processName)) {
            installMiuiHomeHooks(param.getDefaultClassLoader());
        }
    }

    private void installMiuiHomeHooks(ClassLoader classLoader) {
        try {
            Class<?> gestureStubClass = Class.forName(MIUI_HOME_GESTURE_STUB, false,
                    classLoader);
            hookMiuiHomeGestureStubLayoutParams(gestureStubClass);
            hookMiuiHomeGestureStubShow(gestureStubClass);
            hookMiuiHomeGestureStubTouchRegion(gestureStubClass);
            Class<?> recentsContainerClass = Class.forName(MIUI_HOME_RECENTS_CONTAINER, false,
                    classLoader);
            hookMiuiHomeRecentsActualState(recentsContainerClass);
            Class<?> taskViewClass = Class.forName(MIUI_HOME_TASK_VIEW, false, classLoader);
            hookMiuiHomeRecentsTaskLaunch(taskViewClass);
            log(Log.INFO, TAG, "Disabled MiuiHome side back gesture input"
                    + ", preservedGestureStubInitialization=true"
                    + ", mirrorsActualRecentsState=true"
                    + ", mirrorsTaskLaunchExit=true"
                    + ", usesStandardLauncherBackCallback=true");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to block MiuiHome gesture windows", throwable);
        }
    }

    private void hookMiuiHomeGestureStubLayoutParams(Class<?> gestureStubClass)
            throws NoSuchMethodException {
        Method method = gestureStubClass.getDeclaredMethod("getGestureStubWindowParam");
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_gesture_stub_layout_params")
                .intercept(this::makeMiuiHomeGestureStubNotTouchable));
    }

    private void hookMiuiHomeGestureStubShow(Class<?> gestureStubClass)
            throws NoSuchMethodException {
        Method method = gestureStubClass.getDeclaredMethod("showGestureStub");
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_gesture_stub_show")
                .intercept(this::blockMiuiHomeGestureStubShow));
    }

    private void hookMiuiHomeGestureStubTouchRegion(Class<?> gestureStubClass)
            throws NoSuchMethodException {
        for (Method method : gestureStubClass.getDeclaredMethods()) {
            if (!"updateTouchRegion".equals(method.getName())
                    || method.getParameterCount() != 1) {
                continue;
            }
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("miui_home_gesture_stub_touch_region")
                    .intercept(this::emptyMiuiHomeGestureStubTouchRegion));
            return;
        }
        throw new NoSuchMethodException(MIUI_HOME_GESTURE_STUB + ".updateTouchRegion/1");
    }

    private void hookMiuiHomeRecentsActualState(Class<?> recentsContainerClass)
            throws NoSuchMethodException {
        Method method = recentsContainerClass.getDeclaredMethod(
                "notifyRecentTaskState", Context.class, boolean.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_recents_actual_state_v2")
                .intercept(this::mirrorMiuiHomeRecentsActualState));
    }

    private void hookMiuiHomeRecentsTaskLaunch(Class<?> taskViewClass)
            throws NoSuchMethodException {
        Method method = taskViewClass.getDeclaredMethod("onClick", View.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_recents_task_launch")
                .intercept(this::mirrorMiuiHomeRecentsTaskLaunch));
    }

    private Object mirrorMiuiHomeRecentsActualState(XposedInterface.Chain chain)
            throws Throwable {
        Object container = chain.getThisObject();
        Object result = chain.proceed();
        Object contextObject = chain.getArg(0);
        boolean overviewVisible = Boolean.TRUE.equals(chain.getArg(1));
        if (!(contextObject instanceof Context)) {
            log(Log.WARN, TAG, "Cannot mirror MiuiHome Recents state: context="
                    + shortObject(contextObject));
            return result;
        }
        try {
            Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            stateIntent.setPackage(SYSTEM_UI);
            stateIntent.putExtra("overview_visible", overviewVisible);
            ((Context) contextObject).getApplicationContext().sendBroadcast(stateIntent);
            log(Log.INFO, TAG, "Mirrored MiuiHome actual Recents state"
                    + ", overviewVisible=" + overviewVisible
                    + ", container=" + shortObject(container));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to mirror MiuiHome actual Recents state",
                    throwable);
        }
        return result;
    }

    private Object mirrorMiuiHomeRecentsTaskLaunch(XposedInterface.Chain chain)
            throws Throwable {
        Object taskView = chain.getThisObject();
        if (taskView instanceof View) {
            try {
                Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
                stateIntent.setPackage(SYSTEM_UI);
                stateIntent.putExtra("overview_visible", false);
                stateIntent.putExtra("task_launch_started", true);
                ((View) taskView).getContext().getApplicationContext()
                        .sendBroadcast(stateIntent);
                log(Log.INFO, TAG, "Mirrored MiuiHome Recents task launch start"
                        + ", overviewVisible=false"
                        + ", taskView=" + shortObject(taskView));
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to mirror MiuiHome Recents task launch start",
                        throwable);
            }
        } else {
            log(Log.WARN, TAG, "Cannot mirror MiuiHome task launch: taskView="
                    + shortObject(taskView));
        }
        // Notify SystemUI before Xiaomi's original onClick() starts the task transition.
        return chain.proceed();
    }

    private Object makeMiuiHomeGestureStubNotTouchable(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (result instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) result;
            layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            writeField(chain.getThisObject(), "mTouchable", Boolean.FALSE);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to clear MiuiHome gesture-stub touch state",
                    throwable);
        }
        return result;
    }

    private Object blockMiuiHomeGestureStubShow(XposedInterface.Chain chain) {
        try {
            writeField(chain.getThisObject(), "mTouchable", Boolean.FALSE);
            Object view = chain.getThisObject();
            if (view instanceof View) {
                ((View) view).requestApplyInsets();
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to suppress MiuiHome gesture-stub show", throwable);
        }
        return null;
    }

    private Object emptyMiuiHomeGestureStubTouchRegion(XposedInterface.Chain chain)
            throws Throwable {
        Object insetsInfo = chain.getArg(0);
        try {
            invokeAnyMethod(insetsInfo, "setTouchableInsets",
                    new Object[]{Integer.valueOf(3)});
            invokeAnyMethod(insetsInfo, "setTouchableRegion",
                    new Object[]{new Region()});
            return null;
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to empty MiuiHome gesture-stub touch region",
                    throwable);
            return chain.proceed();
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
            hookTaskFragmentPromotionCompatibility(serverClassLoader);
            hookBackNavigationDoneCleanup(serverClassLoader);
            hookSecuritySidebarTransientBars(serverClassLoader);
            hookBackWindowStartAnimation(serverClassLoader);
            hookScheduleAnimationPrepareTransition(serverClassLoader);
            log(Log.INFO, TAG, "Installed system_server back navigation hooks, build="
                    + BUILD_MARK + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install system_server hooks", throwable);
        }
    }

    private void hookSecuritySidebarTransientBars(ClassLoader classLoader) {
        try {
            Class<?> policyClass = Class.forName(DISPLAY_POLICY, false, classLoader);
            int hooked = 0;
            for (Method method : policyClass.getDeclaredMethods()) {
                if (!"requestTransientBars".equals(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                int overload = hooked++;
                recordHookHandle(hook(method)
                        .setId("server_security_sidebar_transient_bars_" + overload)
                        .intercept(this::interceptSecuritySidebarTransientBars));
            }
            if (hooked == 0) {
                log(Log.WARN, TAG, "DisplayPolicy.requestTransientBars not found");
            } else {
                log(Log.INFO, TAG, "Hooked DisplayPolicy transient-bars overloads=" + hooked);
            }
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook security-sidebar transient bars", throwable);
        }
    }

    private Object interceptSecuritySidebarTransientBars(XposedInterface.Chain chain)
            throws Throwable {
        if (isSidebarTransientGesture(chain.getThisObject())) {
            log(Log.INFO, TAG, "Blocked transient bars from sidebar bounds"
                    + ", overload=" + chain.getExecutable().toGenericString());
            return null;
        }
        for (Object argument : chain.getArgs()) {
            if (argument == null) {
                continue;
            }
            String lower = String.valueOf(argument).toLowerCase();
            if (!lower.contains("sidebar")
                    && !lower.contains("game")
                    && !lower.contains("toolbox")) {
                continue;
            }
            String owner;
            try {
                owner = String.valueOf(invokeAnyMethod(
                        argument, "getOwningPackage", new Object[0]));
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            if ("com.miui.securitycenter".equals(owner)) {
                log(Log.INFO, TAG, "Blocked transient bars from security sidebar"
                        + ", target=" + shortObject(argument));
                return null;
            }
        }
        return chain.proceed();
    }

    private boolean isSidebarTransientGesture(Object displayPolicy) {
        try {
            Context context = (Context) readField(displayPolicy, "mContext");
            Object gestures = readField(displayPolicy, "mSystemGestures");
            float[] downXs = (float[]) readField(gestures, "mDownX");
            float[] downYs = (float[]) readField(gestures, "mDownY");
            long[] downTimes = (long[]) readField(gestures, "mDownTime");
            int downPointers = ((Number) readField(gestures, "mDownPointers")).intValue();
            if (context == null || downXs == null || downYs == null || downTimes == null
                    || downPointers <= 0) {
                return false;
            }
            String encoded = Settings.Secure.getString(context.getContentResolver(),
                    MIUI_SIDEBAR_BOUNDS);
            if (encoded == null || encoded.trim().isEmpty()) {
                return false;
            }
            int padding = Math.max(0, Math.round(MIUI_SIDEBAR_EXCLUSION_PADDING_DP
                    * context.getResources().getDisplayMetrics().density));
            JSONArray bounds = new JSONArray(encoded);
            int pointerCount = Math.min(downPointers,
                    Math.min(downXs.length, Math.min(downYs.length, downTimes.length)));
            long now = SystemClock.uptimeMillis();
            for (int pointer = 0; pointer < pointerCount; pointer++) {
                // Ignore stale slots left behind by an earlier system gesture.
                if (downTimes[pointer] <= 0L || now - downTimes[pointer] > 2000L) {
                    continue;
                }
                int x = Math.round(downXs[pointer]);
                int y = Math.round(downYs[pointer]);
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
                                    + ", pointer=" + pointer + ", x=" + x + ", y=" + y
                                    + ", bounds=" + rect);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect sidebar transient gesture", throwable);
        }
        return false;
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

    private void hookTaskFragmentPromotionCompatibility(ClassLoader classLoader) {
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
            log(Log.ERROR, TAG, "Failed to hook TaskFragment promotion compatibility", throwable);
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
                            .intercept(this::prepareOpeningTaskFragment));
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

    private Object prepareOpeningTaskFragment(XposedInterface.Chain chain) throws Throwable {
        Object adaptor = chain.getThisObject();
        try {
            Object target = readField(adaptor, "mTarget");
            Object isOpen = readField(adaptor, "mIsOpen");
            Object transaction = chain.getArg(1);
            if (Boolean.TRUE.equals(isOpen) && transaction instanceof SurfaceControl.Transaction) {
                ensureOpenTaskFragmentVisible(target, (SurfaceControl.Transaction) transaction);
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to prepare opening TaskFragment", throwable);
        }
        return chain.proceed();
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

    private Object interceptScheduleAnimationPrepareTransition(XposedInterface.Chain chain)
            throws Throwable {
        ClassLoader loader = chain.getExecutable().getDeclaringClass().getClassLoader();
        boolean unify = readWindowFlag("unifyBackNavigationTransition", loader, true);
        if (unify) {
            log(Log.INFO, TAG, "Skipped ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                    + " to avoid Xiaomi unified-transition leash reparenting"
                    + ", unifyBackNavigationTransition=true"
                    + ", builder=" + shortObject(chain.getThisObject()));
            return null;
        }
        log(Log.INFO, TAG, "Allowing ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                + ", unifyBackNavigationTransition=false"
                + ", path=Xiaomi/AOSP setLaunchBehind");
        return chain.proceed();
    }

    private Object interceptPromoteToTaskFragmentIfNeeded(XposedInterface.Chain chain)
            throws Throwable {
        Object close = chain.getArg(0);
        Object open = chain.getArg(1);
        boolean migrate = readWindowFlag("migratePredictiveBackTransition",
                chain.getExecutable().getDeclaringClass().getClassLoader(), false);
        if (!migrate) {
            Pair<Object, Object> result = new Pair<>(close, open);
            log(Log.INFO, TAG, "Bypassed TaskFragment promotion for predictive back"
                    + ", close=" + shortObject(close)
                    + ", open=" + shortObject(open));
            return result;
        }
        return chain.proceed();
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
            hookEdgeBackGestureHandler(classLoader);
            hookNavigationBarGestureInsets(classLoader);
            hookShellBackAnimation(classLoader);
            hookBackAnimationSendBackEvent(classLoader);
            hookDefaultTransitionHandler(classLoader);
            hookDefaultTransitionImplMerge(classLoader);
            log(Log.INFO, TAG, "Installed SystemUI AOSP back restoration hooks, build="
                    + BUILD_MARK + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install SystemUI hooks", throwable);
        }
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

    private void hookDefaultTransitionHandler(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(DEFAULT_TRANSITION_HANDLER, false,
                    classLoader);
            Method startAnimation = null;
            for (Method method : handlerClass.getDeclaredMethods()) {
                if ("startAnimation".equals(method.getName())
                        && method.getParameterCount() == 5) {
                    startAnimation = method;
                    break;
                }
            }
            if (startAnimation == null) {
                throw new NoSuchMethodException(DEFAULT_TRANSITION_HANDLER
                        + ".startAnimation/5");
            }
            startAnimation.setAccessible(true);
            recordHookHandle(hook(startAnimation)
                    .setId("systemui_default_transition_start")
                    .intercept(this::registerDefaultTransitionHandler));
            log(Log.INFO, TAG, "Hooked DefaultTransitionHandler.startAnimation");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook DefaultTransitionHandler", throwable);
        }
    }

    private Object registerDefaultTransitionHandler(XposedInterface.Chain chain)
            throws Throwable {
        defaultTransitionHandlers.put(chain.getThisObject(), Boolean.TRUE);
        return chain.proceed();
    }

    private void hookDefaultTransitionImplMerge(ClassLoader classLoader) {
        try {
            Class<?> implementationClass = Class.forName(DEFAULT_TRANSITION_IMPL, false,
                    classLoader);
            Method mergeAnimation = null;
            for (Method method : implementationClass.getDeclaredMethods()) {
                if ("mergeAnimation".equals(method.getName())
                        && method.getParameterCount() == 8) {
                    mergeAnimation = method;
                }
            }
            if (mergeAnimation == null) {
                throw new NoSuchMethodException(DEFAULT_TRANSITION_IMPL
                        + ".mergeAnimation/8");
            }
            mergeAnimation.setAccessible(true);
            recordHookHandle(hook(mergeAnimation)
                    .setId("systemui_default_transition_merge")
                    .intercept(this::trackMiuiOpenCloseMerge));
            log(Log.INFO, TAG, "Hooked DefaultTransitionImpl.mergeAnimation");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook DefaultTransitionImpl.mergeAnimation",
                    throwable);
        }
    }

    private Object trackMiuiOpenCloseMerge(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (Boolean.TRUE.equals(result)) {
            suppressDuplicateBackUntilUptime = SystemClock.uptimeMillis() + 700L;
            log(Log.INFO, TAG, "Tracked Xiaomi OPEN/CLOSE reverse merge");
        }
        return result;
    }

    private void hookBackAnimationSendBackEvent(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(BACK_ANIMATION_CONTROLLER, false,
                    classLoader);
            Method sendBackEvent = controllerClass.getDeclaredMethod("sendBackEvent",
                    int.class);
            sendBackEvent.setAccessible(true);
            recordHookHandle(hook(sendBackEvent)
                    .setId("systemui_back_send_event_guard")
                    .intercept(this::guardDuplicateBackEvent));
            log(Log.INFO, TAG, "Hooked BackAnimationController.sendBackEvent guard");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to hook BackAnimationController.sendBackEvent", throwable);
        }
    }

    private Object guardDuplicateBackEvent(XposedInterface.Chain chain) throws Throwable {
        int action = ((Number) chain.getArg(0)).intValue();
        boolean moduleInjection = Boolean.TRUE.equals(moduleLegacyBackInjection.get());
        long remaining = suppressDuplicateBackUntilUptime - SystemClock.uptimeMillis();
        if (!moduleInjection && remaining > 0) {
            log(Log.WARN, TAG, "Suppressed duplicate Shell BACK during MIUI reverse"
                    + ", action=" + action + ", remaining=" + remaining);
            return null;
        }
        return chain.proceed();
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

    private void hookEdgeBackGestureHandler(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(EDGE_BACK_GESTURE_HANDLER, false, classLoader);
            Method updateIsEnabled = handlerClass.getDeclaredMethod("updateIsEnabled");
            updateIsEnabled.setAccessible(true);
            recordHookHandle(hook(updateIsEnabled)
                    .setId("systemui_edge_back_updateIsEnabled")
                    .intercept(this::onEdgeBackUpdateIsEnabled));
            Method navigationModeChanged = handlerClass.getDeclaredMethod(
                    "onNavigationModeChanged", int.class);
            navigationModeChanged.setAccessible(true);
            recordHookHandle(hook(navigationModeChanged)
                    .setId("systemui_edge_back_onNavigationModeChanged")
                    .intercept(this::onEdgeBackNavigationModeChanged));
            Method setBackAnimation = handlerClass.getDeclaredMethod("setBackAnimation",
                    Class.forName(BACK_ANIMATION_CONTROLLER + "$BackAnimationImpl",
                            false, classLoader));
            setBackAnimation.setAccessible(true);
            recordHookHandle(hook(setBackAnimation)
                    .setId("systemui_edge_back_setBackAnimation")
                    .intercept(this::onEdgeBackSetBackAnimation));
            log(Log.INFO, TAG, "Hooked EdgeBackGestureHandler AOSP path");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook EdgeBackGestureHandler", throwable);
        }
    }

    private Object onEdgeBackUpdateIsEnabled(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        ensureBackInputInstalledFromHandler(chain.getThisObject(), "updateIsEnabled");
        return result;
    }

    private Object onEdgeBackNavigationModeChanged(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        ensureBackInputInstalledFromHandler(chain.getThisObject(), "onNavigationModeChanged");
        return result;
    }

    private Object onEdgeBackSetBackAnimation(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        installBackInputDriver(chain.getThisObject(), chain.getArg(0));
        return result;
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
            hookShellAnimationFinished(controllerClass, "onBackAnimationFinished",
                    "shell_back_onBackAnimationFinished", false);
            hookShellAnimationFinished(controllerClass, "finishBackAnimation",
                    "shell_back_finishBackAnimation", true);
            hookBackNavigationInfoReceived(controllerClass);
            log(Log.INFO, TAG, "Hooked Shell BackAnimationController AOSP path");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook Shell back animation", throwable);
        }
    }

    private void hookShellAnimationFinished(Class<?> controllerClass, String methodName,
                                            String hookId, boolean optional)
            throws NoSuchMethodException {
        try {
            Method method = controllerClass.getDeclaredMethod(methodName);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId(hookId)
                    .intercept(this::onShellAnimationFinished));
        } catch (NoSuchMethodException exception) {
            if (!optional) {
                throw exception;
            }
            log(Log.INFO, TAG, "Optional Shell method unavailable: " + methodName);
        }
    }

    private void hookBackNavigationInfoReceived(Class<?> controllerClass) {
        for (Method method : controllerClass.getDeclaredMethods()) {
            if (!"onBackNavigationInfoReceived".equals(method.getName())
                    || method.getParameterCount() != 2) {
                continue;
            }
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("shell_back_onBackNavigationInfoReceived")
                    .intercept(this::onBackNavigationInfoReceived));
            return;
        }
        log(Log.WARN, TAG, "BackAnimationController.onBackNavigationInfoReceived not found");
    }


    private Object onBackNavigationInfoReceived(XposedInterface.Chain chain)
            throws Throwable {
        ensureAospBackAnimations(chain.getThisObject(), "beforeNavigationInfo");
        forceSystemUiCallbackProgress(chain.getArg(0));
        Object result = chain.proceed();
        logBackNavigationInfo(chain.getArg(0));
        return result;
    }

    private Object onShellAnimationFinished(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        notifyShellAnimationFinished(chain.getThisObject(),
                chain.getExecutable().getName());
        return result;
    }

    private void notifyShellAnimationFinished(Object controller, String reason) {
        for (NativeBackInputMonitor monitor
                : new ArrayList<>(nativeInputMonitors.values())) {
            monitor.onShellAnimationFinished(controller, reason);
        }
    }

    private synchronized void ensureMiuiOverviewStateReceiver(Context context) {
        if (miuiOverviewReceiver != null || context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                String state = intent == null ? null : intent.getStringExtra("state");
                boolean overviewVisible;
                String source;
                if (MODULE_MIUI_OVERVIEW_STATE_CHANGE.equals(action)
                        && intent.hasExtra("overview_visible")) {
                    overviewVisible = intent.getBooleanExtra("overview_visible", false);
                    if (!overviewVisible
                            && intent.getBooleanExtra("task_launch_started", false)) {
                        beginMiuiOverviewDismiss("taskLaunch");
                        return;
                    }
                    state = overviewVisible ? "actualRecentsEnter" : "actualRecentsExit";
                    source = "RecentsContainer";
                } else if ("toRecents".equals(state)) {
                    overviewVisible = true;
                    source = "fullscreenState";
                } else if ("toHome".equals(state)
                        || "toAnotherApp".equals(state)
                        || "toCurrentApp".equals(state)
                        || "finishRecentDirectly".equals(state)) {
                    overviewVisible = false;
                    source = "fullscreenState";
                } else {
                    return;
                }
                updateMiuiOverviewState(overviewVisible, state, source);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(MIUI_FULLSCREEN_STATE_CHANGE);
            filter.addAction(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            miuiOverviewReceiverContext = appContext;
            miuiOverviewReceiver = receiver;
            log(Log.INFO, TAG, "Registered Miui launcher overview-state receiver"
                    + ", currentOverviewVisible=" + miuiOverviewVisible);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to register Miui overview-state receiver",
                    throwable);
        }
    }

    private synchronized void unregisterMiuiOverviewStateReceiver() {
        BroadcastReceiver receiver = miuiOverviewReceiver;
        Context receiverContext = miuiOverviewReceiverContext;
        miuiOverviewReceiver = null;
        miuiOverviewReceiverContext = null;
        if (receiver == null || receiverContext == null) {
            return;
        }
        try {
            receiverContext.unregisterReceiver(receiver);
            log(Log.INFO, TAG, "Unregistered Miui launcher overview-state receiver");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to unregister Miui overview-state receiver",
                    throwable);
        }
    }

    private synchronized void updateMiuiOverviewState(boolean overviewVisible,
                                                      String state, String source) {
        long now = SystemClock.uptimeMillis();
        long pendingUntil = miuiOverviewDismissPendingUntilUptime;
        if (overviewVisible && pendingUntil > now) {
            if ("toRecents".equals(state) && "fullscreenState".equals(source)) {
                // A lone container enter can be a late lifecycle notification from the
                // dismissal that just finished. The launcher's explicit toRecents signal,
                // however, is emitted by a new overview gesture. Accept it even inside the
                // short post-exit guard so quickly reopening Recents cannot leave SystemUI
                // permanently believing that launcher Home is visible.
                miuiOverviewDismissPendingUntilUptime = 0L;
                miuiOverviewVisible = true;
                log(Log.INFO, TAG, "Confirmed new Miui Recents entry during dismiss pending"
                        + ", state=" + state
                        + ", source=" + source
                        + ", clearedPendingForMs=" + (pendingUntil - now)
                        + ", overviewVisible=true");
                return;
            }
            log(Log.INFO, TAG, "Ignored Miui Recents enter while dismiss is pending"
                    + ", state=" + state
                    + ", source=" + source
                    + ", pendingForMs=" + (pendingUntil - now)
                    + ", overviewVisible=" + miuiOverviewVisible);
            return;
        }
        if (!overviewVisible && pendingUntil > now) {
            long guardUntil = now + MIUI_OVERVIEW_EXIT_GUARD_MS;
            miuiOverviewDismissPendingUntilUptime = guardUntil;
            miuiOverviewVisible = false;
            log(Log.INFO, TAG, "Confirmed Miui Recents dismiss"
                    + ", state=" + state
                    + ", source=" + source
                    + ", lateEnterGuardMs=" + MIUI_OVERVIEW_EXIT_GUARD_MS
                    + ", overviewVisible=false");
            return;
        }
        if (!overviewVisible || pendingUntil != 0L) {
            miuiOverviewDismissPendingUntilUptime = 0L;
        }
        miuiOverviewVisible = overviewVisible;
        log(Log.INFO, TAG, "Miui launcher state changed"
                + ", state=" + state
                + ", source=" + source
                + ", overviewVisible=" + overviewVisible);
    }

    private synchronized void beginMiuiOverviewDismiss(String reason) {
        long pendingUntil = SystemClock.uptimeMillis() + MIUI_OVERVIEW_DISMISS_TIMEOUT_MS;
        miuiOverviewDismissPendingUntilUptime = pendingUntil;
        miuiOverviewVisible = false;
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> restoreMiuiOverviewAfterDismissTimeout(pendingUntil),
                MIUI_OVERVIEW_DISMISS_TIMEOUT_MS);
        log(Log.INFO, TAG, "Started Miui Recents dismiss pending"
                + ", reason=" + reason
                + ", timeoutMs=" + MIUI_OVERVIEW_DISMISS_TIMEOUT_MS
                + ", overviewVisible=false");
    }

    private synchronized void restoreMiuiOverviewAfterDismissTimeout(long pendingUntil) {
        if (miuiOverviewDismissPendingUntilUptime != pendingUntil) {
            return;
        }
        miuiOverviewDismissPendingUntilUptime = 0L;
        miuiOverviewVisible = true;
        log(Log.WARN, TAG, "Miui Recents dismiss confirmation timed out"
                + ", restoredOverviewVisible=true");
    }

    private void installBackInputDriver(Object edgeBackGestureHandler, Object backAnimationImpl) {
        try {
            if (edgeBackGestureHandler == null || backAnimationImpl == null) {
                return;
            }
            Object controller = readField(backAnimationImpl, "this$0");
            ensureAospBackAnimations(controller, "setBackAnimation");
            Context context = (Context) readField(edgeBackGestureHandler, "mContext");
            ensureMiuiOverviewStateReceiver(context);
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
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install SystemUI back input driver", throwable);
        }
    }

    private void ensureBackInputInstalledFromHandler(Object edgeBackGestureHandler,
                                                     String reason) {
        if (edgeBackGestureHandler == null) {
            return;
        }
        try {
            if (nativeInputMonitors.containsKey(edgeBackGestureHandler)) {
                return;
            }
            Object backAnimation = readField(edgeBackGestureHandler, "mBackAnimation");
            if (backAnimation == null) {
                log(Log.INFO, TAG, "Cannot restore back input from handler yet"
                        + ", reason=" + reason + ", mBackAnimation=null");
                return;
            }
            installBackInputDriver(edgeBackGestureHandler, backAnimation);
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
        log(Log.WARN, TAG, "Native BackPanelController plugin unavailable"
                + ", handler=" + shortObject(edgeBackGestureHandler));
    }

    private void ensureAospBackAnimations(Object controller, String source) {
        if (controller == null) {
            return;
        }
        try {
            Object registry = readField(controller, "mShellBackAnimationRegistry");
            ensureAospRegistryDefinitions(registry, source);
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
                log(Log.INFO, TAG, "Restored AOSP registry definitions from " + source);
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
        private final SystemUiBackGestureDriver driver;
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
            this.driver = new SystemUiBackGestureDriver(context, edgeBackGestureHandler,
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
            if (!pilfered && driver.isRecentsVisualOnlyGesture()) {
                // MiuiHome can report Overview before WMS exposes Launcher as the back target.
                // Keep the native panel responsive, but leave the real input stream untouched
                // and never retry this gesture against a later navigation target.
                if (!driver.handleTouch(event, activeEdge)) {
                    resetCandidate();
                    return false;
                }
                return false;
            }
            if (!pilfered && distance > dp(MIUI_INDICATOR_PILFER_THRESHOLD_DP)) {
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
            if (driver.isRecentsVisualOnlyGesture()) {
                // Let BackPanelController finish its local animation. No Shell navigation is
                // active, and the monitor must not claim this input stream.
                driver.handleTouch(event, activeEdge);
            } else if (allowTrigger && pilfered) {
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
            if (isLauncherTopActivity() && !miuiOverviewVisible) {
                log(Log.INFO, TAG, "Ignored native back on launcher Home"
                        + ", overviewVisible=false");
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

    private final class SystemUiBackGestureDriver {
        private final Context context;
        private final Object edgeBackGestureHandler;
        private Object controller;
        private Object backAnimationImpl;
        private boolean gestureActive;
        private boolean thresholdCrossed;
        private boolean nativePanelActive;
        private boolean triggerBack;
        private boolean shellGestureStarted;
        private boolean gestureSuppressed;
        private boolean legacyInterruptGesture;
        private boolean launcherOverviewGesture;
        private boolean recentsVisualOnlyGesture;
        private int activeEdge;
        private float downX;
        private float downY;

        SystemUiBackGestureDriver(Context context, Object edgeBackGestureHandler,
                                 Object controller, Object backAnimationImpl) {
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.controller = controller;
            this.backAnimationImpl = backAnimationImpl;
        }

        void updateBackAnimation(Object newBackAnimationImpl) throws Exception {
            this.backAnimationImpl = newBackAnimationImpl;
            this.controller = readField(newBackAnimationImpl, "this$0");
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
                log(Log.ERROR, TAG, "SystemUI back gesture driver failed", throwable);
                clearLocalGestureState();
                return false;
            }
        }

        private boolean isRecentsVisualOnlyGesture() {
            return gestureActive && launcherOverviewGesture && recentsVisualOnlyGesture;
        }

        private boolean onDown(MotionEvent event, int edge) throws Exception {
            gestureActive = true;
            shellGestureStarted = false;
            gestureSuppressed = false;
            legacyInterruptGesture = false;
            launcherOverviewGesture = miuiOverviewVisible;
            recentsVisualOnlyGesture = false;
            thresholdCrossed = false;
            nativePanelActive = false;
            triggerBack = false;
            activeEdge = edge;
            downX = event.getRawX();
            downY = event.getRawY();
            if (!isShellReadyForGesture()) {
                gestureSuppressed = true;
                log(Log.WARN, TAG, "Suppressed SystemUI back while Shell is busy"
                        + ", state=" + describeShellState());
                return true;
            }
            if (launcherOverviewGesture) {
                log(Log.INFO, TAG, "SystemUI-owned Recents back gesture candidate"
                        + ", useShellCallback=true"
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            }
            // Home and Recents share the same launcher Activity. Resolve the actual back target
            // before feeding DOWN to BackPanelController so idle Home never shows the indicator.
            if (!startShellGesture()) {
                if (recentsVisualOnlyGesture) {
                    dispatchToEdgePlugin(event, activeEdge);
                    log(Log.INFO, TAG, "Started visual-only Recents edge gesture"
                            + ", staleShellTargetRejected=true"
                            + ", inputWillRemainUnpilfered=true");
                    return true;
                }
                gestureActive = false;
                gestureSuppressed = false;
                log(Log.INFO, TAG, "Ignored edge gesture without a back navigation target"
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                return false;
            }
            dispatchToEdgePlugin(event, activeEdge);
            log(Log.INFO, TAG, "SystemUI gesture driver candidate"
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
            if (recentsVisualOnlyGesture) {
                dispatchToEdgePlugin(event, activeEdge);
                return true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            if (!legacyInterruptGesture) {
                updateActiveTracker(event.getRawX(), event.getRawY());
            }
            float distance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            if (!thresholdCrossed && distance > dp(PILFER_THRESHOLD_DP)) {
                crossIntentThreshold(distance);
            }
            if (thresholdCrossed && !legacyInterruptGesture) {
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
            if (legacyInterruptGesture) {
                log(Log.INFO, TAG, "MIUI in-app interrupt threshold crossed, distance="
                        + distance);
            } else {
                invokeAnyMethod(controller, "onThresholdCrossed", new Object[0]);
                log(Log.INFO, TAG, launcherOverviewGesture
                        ? "SystemUI Recents Shell callback threshold crossed, distance="
                        + distance
                        : "SystemUI gesture driver intent threshold crossed, distance=" + distance);
            }
        }

        private boolean onUp(MotionEvent event, boolean allowTrigger) throws Exception {
            if (!gestureActive) {
                return false;
            }
            if (gestureSuppressed) {
                clearLocalGestureState();
                log(Log.INFO, TAG, "Finished suppressed SystemUI back gesture");
                return true;
            }
            if (legacyInterruptGesture) {
                return finishLegacyInterruptGesture(event, allowTrigger);
            }
            if (recentsVisualOnlyGesture) {
                // BackPanelController may set BackAnimationImpl's trigger bit while completing
                // its local animation. Clear it after UP/CANCEL: the rejected Shell navigation
                // was already finished, and this gesture must never be committed later.
                dispatchToEdgePlugin(event, activeEdge);
                float releaseDistance = activeEdge == EDGE_LEFT
                        ? event.getRawX() - downX
                        : downX - event.getRawX();
                clearControllerTriggerAfterVisualOnlyGesture();
                log(Log.INFO, TAG, "Finished visual-only Recents edge gesture"
                        + ", releaseDistance=" + releaseDistance
                        + ", wouldPassCommitThreshold="
                        + (allowTrigger && releaseDistance > dp(TRIGGER_THRESHOLD_DP))
                        + ", inputRemainedUnpilfered=true");
                clearLocalGestureState();
                return false;
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
                    log(Log.INFO, TAG, "SystemUI gesture driver delegated remote post-commit"
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
            log(Log.INFO, TAG, "SystemUI gesture driver finish, trigger=" + trigger
                    + ", recentsShellCallback=" + launcherOverviewGesture
                    + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        private boolean finishLegacyInterruptGesture(MotionEvent event, boolean allowTrigger)
                throws Exception {
            dispatchToEdgePlugin(event, activeEdge);
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean trigger = allowTrigger
                    && thresholdCrossed
                    && releaseDistance > dp(TRIGGER_THRESHOLD_DP);
            updateTriggerBack(trigger);
            if (trigger) {
                // A normal BACK creates the incoming CLOSE/TO_BACK transition. Xiaomi's
                // TransitionControllerImpl tags a consecutive inverse transition pair and
                // DefaultTransitionImpl.mergeAnimation() reverses the running OPEN animators.
                dispatchRealBack();
            }
            log(Log.INFO, TAG, "Finished MIUI in-app interrupt gesture"
                    + ", trigger=" + trigger + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        private boolean startShellGesture() throws Exception {
            // A running Xiaomi OPEN transition is the native interruption source. Prefer it
            // even when system_server can already return a valid predictive-back navigation;
            // otherwise Shell starts a new cross-activity animation and misses reverse().
            if (!launcherOverviewGesture && hasReversibleRunningOpenTransition()) {
                legacyInterruptGesture = true;
                log(Log.INFO, TAG, "Preferred running Xiaomi OPEN transition before predictive back");
                return true;
            }
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
                if (launcherOverviewGesture) {
                    recentsVisualOnlyGesture = true;
                    log(Log.INFO, TAG, "Rejected null Recents BackNavigationInfo"
                            + ", mode=visual-only"
                            + ", retry=false");
                    return false;
                }
                if (!launcherOverviewGesture && receivedNull
                        && hasReversibleRunningOpenTransition()) {
                    legacyInterruptGesture = true;
                    log(Log.INFO, TAG, "Using SystemUI-owned legacy BACK for possible "
                            + "MIUI in-app transition interruption");
                    return true;
                }
                return false;
            }
            if (launcherOverviewGesture) {
                int navigationType = info instanceof BackNavigationInfo
                        ? ((BackNavigationInfo) info).getType() : -1;
                if (navigationType != TYPE_CALLBACK) {
                    log(Log.WARN, TAG, "Rejected stale Recents Shell target"
                            + ", type=" + navigationType
                            + ", info=" + shortObject(info));
                    cleanupRejectedShellGesture();
                    recentsVisualOnlyGesture = true;
                    return false;
                }
                log(Log.INFO, TAG, "Resolved Launcher Recents Shell callback, type="
                        + navigationType);
            }
            shellGestureStarted = true;
            log(Log.INFO, TAG, "SystemUI gesture driver onGestureStarted"
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        private boolean hasReversibleRunningOpenTransition() {
            synchronized (defaultTransitionHandlers) {
                for (Object handler : new ArrayList<>(defaultTransitionHandlers.keySet())) {
                    if (handler == null) {
                        continue;
                    }
                    try {
                        Object transitionsObject = readField(handler, "mTransitions");
                        Object animationsObject = readField(handler, "mAnimations");
                        if (!(transitionsObject instanceof Map)
                                || !(animationsObject instanceof Map)) {
                            continue;
                        }
                        Map<?, ?> transitions = (Map<?, ?>) transitionsObject;
                        Map<?, ?> animations = (Map<?, ?>) animationsObject;
                        for (Map.Entry<?, ?> entry : transitions.entrySet()) {
                            Object info = entry.getValue();
                            Object type = info == null ? null
                                    : invokeAnyMethod(info, "getType", new Object[0]);
                            if (!(type instanceof Number)
                                    || ((Number) type).intValue() != 1) {
                                continue;
                            }
                            Object animatorList = animations.get(entry.getKey());
                            if (!(animatorList instanceof Iterable)) {
                                continue;
                            }
                            int count = 0;
                            boolean reversible = true;
                            for (Object animator : (Iterable<?>) animatorList) {
                                count++;
                                if (!Boolean.TRUE.equals(invokeAnyMethod(animator,
                                        "canReverse", new Object[0]))
                                        || !Boolean.TRUE.equals(invokeAnyMethod(animator,
                                        "isRunning", new Object[0]))) {
                                    reversible = false;
                                    break;
                                }
                            }
                            if (count > 0 && reversible) {
                                log(Log.INFO, TAG, "Detected reversible running OPEN transition"
                                        + ", animatorCount=" + count
                                        + ", info=" + shortObject(info));
                                return true;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return false;
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

        private void clearControllerTriggerAfterVisualOnlyGesture() {
            try {
                // Queue the clear through the same BackAnimationImpl path used by the panel's
                // BackCallback so it is ordered after any trigger=true posted by ACTION_UP.
                invokeAnyMethod(backAnimationImpl, "setTriggerBack",
                        new Object[]{Boolean.FALSE});
                return;
            } catch (Throwable ignored) {
            }
            try {
                invokeAnyMethod(controller, "setTriggerBack",
                        new Object[]{Boolean.FALSE});
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to clear visual-only Recents trigger", throwable);
            }
        }

        private void clearLocalGestureState() {
            gestureActive = false;
            shellGestureStarted = false;
            gestureSuppressed = false;
            legacyInterruptGesture = false;
            launcherOverviewGesture = false;
            recentsVisualOnlyGesture = false;
            thresholdCrossed = false;
            nativePanelActive = false;
            triggerBack = false;
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
            clearLocalGestureState();
            log(Log.INFO, TAG, "Cancelled local SystemUI back gesture, reason=" + reason);
        }

        private void onShellAnimationFinished(Object finishedController, String reason) {
            if (controller != finishedController) {
                return;
            }
            if (gestureActive) {
                clearLocalGestureState();
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
                    log(Log.INFO, TAG, "SystemUI gesture driver waiting for remote animation start"
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
                if (!legacyInterruptGesture) {
                    invokeAnyMethod(controller, "setTriggerBack",
                            new Object[]{Boolean.valueOf(newTriggerBack)});
                }
                if (!newTriggerBack && nativePanelActive) {
                    nativePanelActive = false;
                } else if (newTriggerBack && thresholdCrossed) {
                    nativePanelActive = true;
                }
                log(Log.INFO, TAG, "SystemUI gesture driver triggerBack=" + newTriggerBack);
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
            moduleLegacyBackInjection.set(Boolean.TRUE);
            try {
                invokeAnyMethod(controller, "sendBackEvent",
                        new Object[]{Integer.valueOf(KEY_ACTION_DOWN)});
                invokeAnyMethod(controller, "sendBackEvent",
                        new Object[]{Integer.valueOf(KEY_ACTION_UP)});
                log(Log.INFO, TAG, "Injected legacy back key via sendBackEvent");
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to inject legacy back key", throwable);
            } finally {
                moduleLegacyBackInjection.remove();
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

}
