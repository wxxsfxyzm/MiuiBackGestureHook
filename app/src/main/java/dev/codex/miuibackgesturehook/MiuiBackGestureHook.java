package dev.codex.miuibackgesturehook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.BroadcastOptions;
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
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class MiuiBackGestureHook extends XposedModule {
    private static final String TAG = "MiuiBackGestureHook";
    private static final String BUILD_MARK =
            "systemui-aosp-back-v115-miuihome-accepted-input";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String MIUI_HOME = "com.miui.home";

    private static final String EDGE_BACK_GESTURE_HANDLER =
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler";
    private static final String MIUI_OVERVIEW_PROXY =
            "com.android.systemui.recents.MiuiOverviewProxy";
    private static final String MIUI_HOME_GESTURE_STUB =
            "com.miui.home.recents.GestureStubView";
    private static final String MIUI_HOME_GESTURE_PROCESSOR =
            "com.miui.home.recents.GesturesBackTouchProcessor";
    private static final String MIUI_HOME_RECENTS_CONTAINER =
            "com.miui.home.recents.views.RecentsContainer";
    private static final String MIUI_HOME_TASK_VIEW =
            "com.miui.home.recents.views.TaskView";
    private static final String MIUI_HOME_STATE_NOTIFY_UTILS =
            "com.miui.home.recents.util.StateNotifyUtils";
    private static final String MIUI_HOME_BACK_GESTURE_BREAK_CONTROLLER =
            "com.miui.home.recents.BackGestureBreakController";
    private static final String MIUI_HOME_WINDOW_ELEMENT_ANIM_LISTENER =
            "com.miui.home.recents.anim.StateManager$windowElementAnimListener$1";
    private static final String MIUI_HOME_STATE_MANAGER =
            "com.miui.home.recents.anim.StateManager";
    private static final String MIUI_HOME_LAUNCHER_STATE_MANAGER =
            "com.miui.home.launcher.LauncherStateManager";
    private static final String MIUI_HOME_LAUNCHER_STATE =
            "com.miui.home.launcher.LauncherState";
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
    private static final String MODULE_MIUI_OVERVIEW_STATE_CHANGE =
            "dev.codex.miuibackgesturehook.action.MIUI_OVERVIEW_STATE_CHANGE";
    private static final String MODULE_MIUI_HOME_OPEN_BREAK_COMMAND =
            "dev.codex.miuibackgesturehook.action.MIUI_HOME_OPEN_BREAK";
    private static final String MODULE_SYSTEMUI_INPUT_ARBITER_STATE =
            "dev.codex.miuibackgesturehook.action.SYSTEMUI_INPUT_ARBITER_STATE";
    private static final String MODULE_MIUI_HOME_INPUT_ARBITER_QUERY =
            "dev.codex.miuibackgesturehook.action.MIUI_HOME_INPUT_ARBITER_QUERY";
    private static final String EXTRA_INPUT_ARBITER_READY = "input_arbiter_ready";
    private static final String EXTRA_INPUT_ARBITER_GENERATION =
            "input_arbiter_generation";
    private static final String EXTRA_INPUT_ACCEPTED = "input_accepted";
    private static final String EXTRA_INPUT_EVENT_ID = "input_event_id";
    private static final String EXTRA_INPUT_DOWN_TIME = "input_down_time";
    private static final String EXTRA_INPUT_DEVICE_ID = "input_device_id";
    private static final String EXTRA_INPUT_SOURCE = "input_source";
    private static final String EXTRA_INPUT_DISPLAY_ID = "input_display_id";
    private static final String EXTRA_INPUT_EDGE = "input_edge";
    private static final String EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE =
            "launcher_open_break_available";
    private static final String EXTRA_LAUNCHER_OPEN_BREAK_GENERATION =
            "launcher_open_break_generation";
    private static final String EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT =
            "launcher_open_break_attempt";
    private static final int LAUNCHER_OPEN_BREAK_RESULT_NO_RECEIVER = 0;
    private static final int LAUNCHER_OPEN_BREAK_RESULT_REJECTED = 1;
    private static final int LAUNCHER_OPEN_BREAK_RESULT_ACCEPTED = 2;
    private static final int KEY_ACTION_UP = 1;
    private static final int KEY_ACTION_DOWN = 0;
    private static final int TYPE_CROSS_ACTIVITY = 2;
    private static final int TYPE_CROSS_TASK = 3;
    private static final int TYPE_CALLBACK = 4;
    private static final float EDGE_TOUCH_WIDTH_DP = 24.0f;
    private static final float PILFER_THRESHOLD_DP = 8.0f;
    private static final float TRIGGER_THRESHOLD_DP = 48.0f;
    private static final float AOSP_PROGRESS_THRESHOLD_DP = 412.0f;
    private static final String MIUI_SIDEBAR_BOUNDS = "sidebar_bounds";
    private static final float MIUI_SIDEBAR_EXCLUSION_PADDING_DP = 8.0f;
    private static final long MIUI_OVERVIEW_DISMISS_TIMEOUT_MS = 2500L;
    private static final long MIUI_OVERVIEW_EXIT_GUARD_MS = 400L;
    private static final long LEGACY_BACK_MERGE_TIMEOUT_MS = 2000L;
    private static final long DUPLICATE_BACK_PAIR_TIMEOUT_MS = 700L;
    private static final long DUPLICATE_BACK_UP_INTERVAL_MS = 200L;
    private static final long INPUT_ACCEPTED_TOKEN_TIMEOUT_MS = 750L;
    private static final int BACK_GUARD_IDLE = 0;
    private static final int BACK_GUARD_WAIT_MERGE = 1;
    private static final int BACK_GUARD_EXPECT_DOWN = 2;
    private static final int BACK_GUARD_EXPECT_UP = 3;
    private static final int OPEN_SNAPSHOT_PENDING = 0;
    private static final int OPEN_SNAPSHOT_ACTIVE = 1;
    private static final int OPEN_SNAPSHOT_INVALID = 2;

    private final List<XposedInterface.HookHandle> hookHandles = new ArrayList<>();
    private final Map<Object, NativeBackInputMonitor> nativeInputMonitors =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ConcurrentHashMap<Object, OpenTransitionSnapshot> runningOpenTransitions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ReflectionKey, Field> reflectedFields =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ReflectionKey, Method> reflectedAnyMethods =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ReflectionKey, Method> reflectedExactMethods =
            new ConcurrentHashMap<>();
    private final Set<ReflectionKey> missingReflectedMembers =
            ConcurrentHashMap.newKeySet();
    private final Object legacyBackGuardLock = new Object();
    private final AtomicLong legacyBackAttemptIds = new AtomicLong();
    private final AtomicLong openSnapshotGeneration = new AtomicLong();
    private final AtomicLong launcherOpenBreakAttemptIds = new AtomicLong();
    private final AtomicInteger launcherOpenBreakCommandsInFlight = new AtomicInteger();
    private final AtomicLong miuiHomeOpenBreakGenerationIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    private final AtomicLong miuiHomeOpenBreakCallbackEpoch = new AtomicLong();
    private final AtomicInteger systemUiInputArbiterMonitorCount = new AtomicInteger();
    private final AtomicReference<MiuiHomeAcceptedInputToken> acceptedInputToken =
            new AtomicReference<>();
    private final long systemUiInputArbiterGeneration =
            Math.max(1L, SystemClock.elapsedRealtimeNanos());
    private volatile boolean acceptingOpenSnapshots = true;
    private volatile boolean miuiOverviewVisible;
    private volatile boolean miuiDrawerVisible;
    private volatile boolean miuiLauncherOpenBreakAvailable;
    private volatile long miuiLauncherOpenBreakGeneration;
    private volatile long miuiOverviewDismissPendingUntilUptime;
    private Context miuiOverviewReceiverContext;
    private BroadcastReceiver miuiOverviewReceiver;
    private volatile Object miuiHomeOpenBreakController;
    private volatile boolean miuiHomeOpenBreakCommandPending;
    private volatile long miuiHomeOpenBreakGeneration;
    private volatile Object miuiHomeOpenBreakAnimationIdentity;
    private volatile boolean miuiHomeOpenBreakGenerationPrepared;
    private volatile boolean miuiHomeOpenBreakAnimationActive;
    private Context miuiHomeOpenBreakContext;
    private Context miuiHomeOpenBreakCommandReceiverContext;
    private BroadcastReceiver miuiHomeOpenBreakCommandReceiver;
    private Context miuiHomeInputArbiterReceiverContext;
    private BroadcastReceiver miuiHomeInputArbiterReceiver;
    private volatile boolean miuiHomeSystemUiInputArbiterReady;
    private volatile long miuiHomeSystemUiInputArbiterGeneration;
    private String processName;
    private boolean nativePluginDiagnosticsLogged;
    private volatile Field defaultTransitionAnimationsField;
    private volatile Field defaultTransitionAnimationSizeField;
    private volatile Field defaultTransitionAnimExecutorField;
    private volatile Method transitionInfoGetTypeMethod;
    private volatile Method animatorCanReverseMethod;
    private LegacyBackAttempt legacyBackAttempt;
    private int legacyBackGuardPhase = BACK_GUARD_IDLE;
    private long legacyBackGuardDeadlineUptime;
    private long suppressedBackDownUptime;
    private Thread suppressedBackDownThread;
    private final ThreadLocal<Object> moduleLegacyBackInjection = new ThreadLocal<>();

    private static final class OpenTransitionSnapshot {
        final Object token;
        final Object transitionInfo;
        final int animatorCount;
        final int originalAnimatorCount;
        final Animator[] animators;
        final Executor animExecutor;
        final long generation;
        final AtomicInteger state = new AtomicInteger(OPEN_SNAPSHOT_PENDING);
        volatile AnimatorListenerAdapter listener;

        OpenTransitionSnapshot(Object token, Object transitionInfo, Animator[] animators,
                               int originalAnimatorCount, Executor animExecutor,
                               long generation) {
            this.token = token;
            this.transitionInfo = transitionInfo;
            this.animatorCount = animators.length;
            this.originalAnimatorCount = originalAnimatorCount;
            this.animators = animators;
            this.animExecutor = animExecutor;
            this.generation = generation;
        }
    }

    private static final class OpenTransitionInvalidationListener
            extends AnimatorListenerAdapter {
        private final WeakReference<MiuiBackGestureHook> owner;
        private final OpenTransitionSnapshot snapshot;

        OpenTransitionInvalidationListener(MiuiBackGestureHook owner,
                                           OpenTransitionSnapshot snapshot) {
            this.owner = new WeakReference<>(owner);
            this.snapshot = snapshot;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            MiuiBackGestureHook hook = owner.get();
            if (hook != null) {
                hook.invalidateOpenTransitionSnapshot(snapshot, "cancel");
            } else {
                animation.removeListener(this);
            }
        }

        @Override
        public void onAnimationEnd(Animator animation, boolean isReverse) {
            MiuiBackGestureHook hook = owner.get();
            if (hook != null) {
                hook.invalidateOpenTransitionSnapshot(snapshot,
                        isReverse ? "reverseEnd" : "end");
            } else {
                animation.removeListener(this);
            }
        }
    }

    private static final class LegacyBackAttempt {
        final long id;
        final Object controller;
        final Object runningTransitionInfo;
        final long startedUptime;

        LegacyBackAttempt(long id, Object controller, Object runningTransitionInfo,
                          long startedUptime) {
            this.id = id;
            this.controller = controller;
            this.runningTransitionInfo = runningTransitionInfo;
            this.startedUptime = startedUptime;
        }
    }

    private static final class EdgeWidthSnapshot {
        final int leftSensitivity;
        final int rightSensitivity;
        final int leftTouchWidth;
        final int rightTouchWidth;

        EdgeWidthSnapshot(int leftSensitivity, int rightSensitivity,
                          int leftInset, int rightInset) {
            this.leftSensitivity = leftSensitivity;
            this.rightSensitivity = rightSensitivity;
            this.leftTouchWidth = combineTouchWidth(leftSensitivity, leftInset);
            this.rightTouchWidth = combineTouchWidth(rightSensitivity, rightInset);
        }

        int touchWidth(int edge) {
            return edge == EDGE_LEFT ? leftTouchWidth : rightTouchWidth;
        }

        private static int combineTouchWidth(int sensitivity, int inset) {
            long width = (long) sensitivity + (long) inset;
            return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE, width));
        }
    }

    private static final class ReflectionKey {
        final Class<?> owner;
        final String signature;

        ReflectionKey(Class<?> owner, String signature) {
            this.owner = owner;
            this.signature = signature;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof ReflectionKey)) {
                return false;
            }
            ReflectionKey other = (ReflectionKey) object;
            return owner == other.owner && signature.equals(other.signature);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(owner) + signature.hashCode();
        }
    }

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
        boolean savedMiuiDrawerVisible = miuiDrawerVisible;
        long savedMiuiOverviewDismissDeadline = miuiOverviewDismissPendingUntilUptime;
        Object savedMiuiHomeOpenBreakController = miuiHomeOpenBreakController;
        Context savedMiuiHomeOpenBreakContext = miuiHomeOpenBreakContext;
        long savedMiuiHomeOpenBreakGeneration = miuiHomeOpenBreakGeneration;
        Object savedMiuiHomeOpenBreakAnimationIdentity =
                miuiHomeOpenBreakAnimationIdentity;
        boolean savedMiuiHomeOpenBreakGenerationPrepared =
                miuiHomeOpenBreakGenerationPrepared;
        boolean savedMiuiHomeOpenBreakAnimationActive =
                miuiHomeOpenBreakAnimationActive;
        boolean savedMiuiHomeOpenBreakCommandPending =
                miuiHomeOpenBreakCommandPending;
        acceptingOpenSnapshots = false;
        miuiHomeOpenBreakCallbackEpoch.incrementAndGet();
        openSnapshotGeneration.incrementAndGet();
        invalidateAllOpenTransitionSnapshots("hotReload");
        clearLegacyBackGuard("hotReload");
        miuiLauncherOpenBreakAvailable = false;
        miuiLauncherOpenBreakGeneration = 0L;
        acceptedInputToken.set(null);
        unregisterMiuiOverviewStateReceiver();
        unregisterMiuiHomeOpenBreakCommandReceiver();
        unregisterMiuiHomeInputArbiterReceiver();
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
                inputState, Boolean.valueOf(savedMiuiOverviewVisible),
                Long.valueOf(savedMiuiOverviewDismissDeadline),
                savedMiuiHomeOpenBreakController, savedMiuiHomeOpenBreakContext,
                Long.valueOf(savedMiuiHomeOpenBreakGeneration),
                savedMiuiHomeOpenBreakAnimationIdentity,
                Boolean.valueOf(savedMiuiHomeOpenBreakGenerationPrepared),
                Boolean.valueOf(savedMiuiHomeOpenBreakAnimationActive),
                Boolean.valueOf(savedMiuiHomeOpenBreakCommandPending),
                Boolean.valueOf(savedMiuiDrawerVisible)
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
        boolean hadMiuiHomeGestureStubShowHook = false;
        boolean hadMiuiHomeGestureInputArbiterHook = false;
        boolean hadMiuiHomeRecentsStateHook = false;
        boolean hadMiuiHomeTaskLaunchHook = false;
        boolean hadMiuiHomeFullscreenStateHook = false;
        boolean hadMiuiHomeOpenBreakEnableHook = false;
        boolean hadMiuiHomeOpenBreakAnimationStartHook = false;
        boolean hadMiuiHomeOpenBreakAnimationEndHook = false;
        boolean hadMiuiHomeReusedCloseOpenHook = false;
        boolean hadMiuiHomeDrawerStateHook = false;
        boolean hadDefaultTransitionStartHook = false;
        boolean hadDefaultTransitionMergeHook = false;
        boolean hadBackSendEventHook = false;
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
                } else if ("miui_home_gesture_stub_show".equals(oldHandle.getId())) {
                    hadMiuiHomeGestureStubShowHook = true;
                } else if ("miui_home_gesture_input_arbiter".equals(
                        oldHandle.getId())) {
                    hadMiuiHomeGestureInputArbiterHook = true;
                } else if ("miui_home_recents_actual_state_v2".equals(oldHandle.getId())) {
                    hadMiuiHomeRecentsStateHook = true;
                } else if ("miui_home_recents_task_launch".equals(oldHandle.getId())) {
                    hadMiuiHomeTaskLaunchHook = true;
                } else if ("miui_home_fullscreen_state".equals(oldHandle.getId())) {
                    hadMiuiHomeFullscreenStateHook = true;
                } else if ("miui_home_open_break_enable".equals(oldHandle.getId())) {
                    hadMiuiHomeOpenBreakEnableHook = true;
                } else if ("miui_home_open_break_animation_start".equals(
                        oldHandle.getId())) {
                    hadMiuiHomeOpenBreakAnimationStartHook = true;
                } else if ("miui_home_open_break_animation_end".equals(
                        oldHandle.getId())) {
                    hadMiuiHomeOpenBreakAnimationEndHook = true;
                } else if ("miui_home_reused_close_open".equals(oldHandle.getId())) {
                    hadMiuiHomeReusedCloseOpenHook = true;
                } else if ("miui_home_drawer_state".equals(oldHandle.getId())) {
                    hadMiuiHomeDrawerStateHook = true;
                } else if ("systemui_default_transition_start".equals(oldHandle.getId())) {
                    hadDefaultTransitionStartHook = true;
                } else if ("systemui_default_transition_merge".equals(oldHandle.getId())) {
                    hadDefaultTransitionMergeHook = true;
                } else if ("systemui_back_send_event_guard".equals(oldHandle.getId())) {
                    hadBackSendEventHook = true;
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
        if (SYSTEM_UI.equals(processName) && hotReloadClassLoader != null) {
            if (!hadDefaultTransitionStartHook) {
                hookDefaultTransitionHandler(hotReloadClassLoader);
            }
            if (!hadDefaultTransitionMergeHook) {
                hookDefaultTransitionImplMerge(hotReloadClassLoader);
            }
            if (!hadBackSendEventHook) {
                hookBackAnimationSendBackEvent(hotReloadClassLoader);
            }
        }
        if (MIUI_HOME.equals(processName) && hotReloadClassLoader != null) {
            try {
                Class<?> gestureStubClass = Class.forName(MIUI_HOME_GESTURE_STUB, false,
                        hotReloadClassLoader);
                if (!hadMiuiHomeGestureStubShowHook) {
                    hookMiuiHomeGestureStubShow(gestureStubClass);
                }
                if (!hadMiuiHomeGestureInputArbiterHook) {
                    Class<?> processorClass = Class.forName(
                            MIUI_HOME_GESTURE_PROCESSOR, false, hotReloadClassLoader);
                    hookMiuiHomeGestureInputArbiter(processorClass, gestureStubClass);
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
                if (!hadMiuiHomeFullscreenStateHook) {
                    hookMiuiHomeFullscreenState(hotReloadClassLoader);
                }
                if (!hadMiuiHomeOpenBreakEnableHook) {
                    Class<?> breakControllerClass = Class.forName(
                            MIUI_HOME_BACK_GESTURE_BREAK_CONTROLLER, false,
                            hotReloadClassLoader);
                    hookMiuiHomeOpenBreakEnable(breakControllerClass);
                }
                if (!hadMiuiHomeOpenBreakAnimationStartHook
                        || !hadMiuiHomeOpenBreakAnimationEndHook) {
                    Class<?> listenerClass = Class.forName(
                            MIUI_HOME_WINDOW_ELEMENT_ANIM_LISTENER, false,
                            hotReloadClassLoader);
                    if (!hadMiuiHomeOpenBreakAnimationStartHook) {
                        hookMiuiHomeOpenBreakAnimationStart(listenerClass);
                    }
                    if (!hadMiuiHomeOpenBreakAnimationEndHook) {
                        hookMiuiHomeOpenBreakAnimationEnd(listenerClass);
                    }
                }
                if (!hadMiuiHomeReusedCloseOpenHook) {
                    hookMiuiHomeReusedCloseOpen(hotReloadClassLoader);
                }
                if (!hadMiuiHomeDrawerStateHook) {
                    hookMiuiHomeDrawerState(hotReloadClassLoader);
                }
                restoreMiuiHomeGestureStubsAfterHotReload(hotReloadClassLoader);
                restoreMiuiHomeOpenBreakAfterHotReload();
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
        if ("systemui_navigation_bar_view_insets".equals(hookId)
                || "systemui_navigation_bar_window_state".equals(hookId)
                || "systemui_navigation_bar_show_transient".equals(hookId)
                || "systemui_navigation_bar_abort_transient".equals(hookId)
                || "systemui_navigation_bar_auto_hide".equals(hookId)
                || "systemui_edge_back_task_stack_changed".equals(hookId)
                || "systemui_edge_back_exclusion_changed".equals(hookId)
                || "systemui_edge_back_update_resources".equals(hookId)) {
            // These lifecycle hooks existed only to refresh the retired SystemUI shield.
            return XposedInterface.Chain::proceed;
        }
        if ("miui_home_gesture_stub_layout_params".equals(hookId)) {
            return this::restoreMiuiHomeGestureStubTouchableLayout;
        }
        if ("miui_home_gesture_stub_show".equals(hookId)) {
            return this::restoreMiuiHomeGestureStubShow;
        }
        if ("miui_home_gesture_stub_touch_region".equals(hookId)) {
            return XposedInterface.Chain::proceed;
        }
        if ("miui_home_gesture_input_arbiter".equals(hookId)) {
            return this::arbitrateMiuiHomeAcceptedInput;
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
        if ("miui_home_fullscreen_state".equals(hookId)) {
            return this::mirrorMiuiHomeFullscreenState;
        }
        if ("miui_home_open_break_enable".equals(hookId)) {
            return this::captureMiuiHomeOpenBreakEnable;
        }
        if ("miui_home_open_break_animation_start".equals(hookId)) {
            return this::mirrorMiuiHomeOpenBreakAnimationStart;
        }
        if ("miui_home_open_break_animation_end".equals(hookId)) {
            return this::mirrorMiuiHomeOpenBreakAnimationEnd;
        }
        if ("miui_home_reused_close_open".equals(hookId)) {
            return this::restoreMiuiHomeReusedCloseOpen;
        }
        if ("miui_home_drawer_state".equals(hookId)) {
            return this::mirrorMiuiHomeDrawerState;
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
            if (state.length >= 2 && state[0] instanceof Object[][]
                    && state[1] instanceof Boolean) {
                inputStateObject = state[0];
                miuiOverviewVisible = ((Boolean) state[1]).booleanValue();
                if (state.length >= 3 && state[2] instanceof Long) {
                    miuiOverviewDismissPendingUntilUptime = ((Long) state[2]).longValue();
                }
                if (state.length >= 10) {
                    miuiHomeOpenBreakController = state[3];
                    if (state[4] instanceof Context) {
                        miuiHomeOpenBreakContext = (Context) state[4];
                    }
                    if (state[5] instanceof Long) {
                        miuiHomeOpenBreakGeneration = ((Long) state[5]).longValue();
                        miuiHomeOpenBreakGenerationIds.set(Math.max(
                                miuiHomeOpenBreakGenerationIds.get(),
                                miuiHomeOpenBreakGeneration));
                    }
                    miuiHomeOpenBreakAnimationIdentity = state[6];
                    miuiHomeOpenBreakGenerationPrepared =
                            Boolean.TRUE.equals(state[7]);
                    miuiHomeOpenBreakAnimationActive = Boolean.TRUE.equals(state[8]);
                    miuiHomeOpenBreakCommandPending = Boolean.TRUE.equals(state[9]);
                }
                if (state.length >= 11) {
                    miuiDrawerVisible = Boolean.TRUE.equals(state[10]);
                }
            }
        }
        restoreMiuiOverviewDismissTimeoutAfterHotReload();
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

    private synchronized void restoreMiuiOverviewDismissTimeoutAfterHotReload() {
        long deadline = miuiOverviewDismissPendingUntilUptime;
        if (deadline == 0L) {
            return;
        }
        long remaining = deadline - SystemClock.uptimeMillis();
        if (remaining <= 0L) {
            miuiOverviewDismissPendingUntilUptime = 0L;
            miuiOverviewVisible = true;
            log(Log.WARN, TAG, "Expired Recents dismiss deadline during hot reload"
                    + ", restoredOverviewVisible=true");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> restoreMiuiOverviewAfterDismissTimeout(deadline), remaining);
        log(Log.INFO, TAG, "Restored Recents dismiss timeout after hot reload"
                + ", remainingMs=" + remaining);
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
            hookMiuiHomeGestureStubShow(gestureStubClass);
            Class<?> processorClass = Class.forName(
                    MIUI_HOME_GESTURE_PROCESSOR, false, classLoader);
            hookMiuiHomeGestureInputArbiter(processorClass, gestureStubClass);
            Class<?> recentsContainerClass = Class.forName(MIUI_HOME_RECENTS_CONTAINER, false,
                    classLoader);
            hookMiuiHomeRecentsActualState(recentsContainerClass);
            Class<?> taskViewClass = Class.forName(MIUI_HOME_TASK_VIEW, false, classLoader);
            hookMiuiHomeRecentsTaskLaunch(taskViewClass);
            hookMiuiHomeFullscreenState(classLoader);
            Class<?> breakControllerClass = Class.forName(
                    MIUI_HOME_BACK_GESTURE_BREAK_CONTROLLER, false, classLoader);
            hookMiuiHomeOpenBreakEnable(breakControllerClass);
            Class<?> windowElementAnimListenerClass = Class.forName(
                    MIUI_HOME_WINDOW_ELEMENT_ANIM_LISTENER, false, classLoader);
            hookMiuiHomeOpenBreakAnimationStart(windowElementAnimListenerClass);
            hookMiuiHomeOpenBreakAnimationEnd(windowElementAnimListenerClass);
            hookMiuiHomeReusedCloseOpen(classLoader);
            hookMiuiHomeDrawerState(classLoader);
            log(Log.INFO, TAG, "Enabled MiuiHome native side input arbitration"
                    + ", preservedGestureStubInitialization=true"
                    + ", preservesNativeRedirect=true"
                    + ", blocksLegacyGestureProcessor=true"
                    + ", requiresAcceptedInputToken=true"
                    + ", systemUiOwnsCommittedGesture=true"
                    + ", mirrorsActualRecentsState=true"
                    + ", mirrorsTaskLaunchExit=true"
                    + ", mirrorsAuthenticatedFullscreenState=true"
                    + ", mirrorsDrawerState=true"
                    + ", mirrorsLauncherOpenBreakState=true"
                    + ", usesStandardLauncherBackCallback=true");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install MiuiHome input arbitration", throwable);
        }
    }

    private void hookMiuiHomeGestureStubShow(Class<?> gestureStubClass)
            throws NoSuchMethodException {
        Method method = gestureStubClass.getDeclaredMethod("showGestureStub");
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_gesture_stub_show")
                .intercept(this::restoreMiuiHomeGestureStubShow));
    }

    private void hookMiuiHomeGestureInputArbiter(Class<?> processorClass,
                                                  Class<?> gestureStubClass)
            throws NoSuchMethodException {
        Method method = processorClass.getDeclaredMethod(
                "onPointerEvent", MotionEvent.class, gestureStubClass);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_gesture_input_arbiter")
                .intercept(this::arbitrateMiuiHomeAcceptedInput));
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

    private void hookMiuiHomeFullscreenState(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> stateNotifyClass = Class.forName(MIUI_HOME_STATE_NOTIFY_UTILS, false,
                classLoader);
        Method method = stateNotifyClass.getDeclaredMethod("sendStateBroadcast",
                Context.class, String.class, String.class, String.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_fullscreen_state")
                .intercept(this::mirrorMiuiHomeFullscreenState));
    }

    private void hookMiuiHomeOpenBreakEnable(Class<?> breakControllerClass)
            throws NoSuchMethodException {
        Method method = breakControllerClass.getDeclaredMethod("enableBackBreakOpenAnim");
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_open_break_enable")
                .intercept(this::captureMiuiHomeOpenBreakEnable));
    }

    private void hookMiuiHomeOpenBreakAnimationStart(Class<?> listenerClass)
            throws NoSuchMethodException {
        Method method = listenerClass.getDeclaredMethod("onAnimationStart", Object.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_open_break_animation_start")
                .intercept(this::mirrorMiuiHomeOpenBreakAnimationStart));
    }

    private void hookMiuiHomeOpenBreakAnimationEnd(Class<?> listenerClass)
            throws NoSuchMethodException {
        Method method = listenerClass.getDeclaredMethod("onAnimationEnd", Object.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_open_break_animation_end")
                .intercept(this::mirrorMiuiHomeOpenBreakAnimationEnd));
    }

    private void hookMiuiHomeReusedCloseOpen(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> stateManagerClass = Class.forName(MIUI_HOME_STATE_MANAGER, false,
                classLoader);
        Method method = stateManagerClass.getDeclaredMethod("animToFullScreen", View.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_reused_close_open")
                .intercept(this::restoreMiuiHomeReusedCloseOpen));
    }

    private void hookMiuiHomeDrawerState(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> managerClass = Class.forName(MIUI_HOME_LAUNCHER_STATE_MANAGER, false,
                classLoader);
        Class<?> stateClass = Class.forName(MIUI_HOME_LAUNCHER_STATE, false, classLoader);
        Method method = managerClass.getDeclaredMethod("onStateTransitionStart", stateClass);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_drawer_state")
                .intercept(this::mirrorMiuiHomeDrawerState));
    }

    private Object captureMiuiHomeOpenBreakEnable(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object controller = chain.getThisObject();
        miuiHomeOpenBreakController = controller;
        miuiHomeOpenBreakGeneration = nextMiuiHomeOpenBreakGeneration();
        miuiHomeOpenBreakAnimationIdentity = null;
        miuiHomeOpenBreakGenerationPrepared = true;
        miuiHomeOpenBreakAnimationActive = false;
        miuiHomeOpenBreakCommandPending = false;
        refreshMiuiHomeOpenBreakAvailability(controller, "enable");
        return result;
    }

    private Object mirrorMiuiHomeOpenBreakAnimationStart(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (!miuiHomeOpenBreakGenerationPrepared
                || miuiHomeOpenBreakGeneration == 0L) {
            miuiHomeOpenBreakGeneration = nextMiuiHomeOpenBreakGeneration();
        }
        miuiHomeOpenBreakGenerationPrepared = false;
        Object animationIdentity = chain.getArg(0);
        miuiHomeOpenBreakAnimationIdentity = animationIdentity;
        miuiHomeOpenBreakAnimationActive = true;
        miuiHomeOpenBreakCommandPending = false;
        long generation = miuiHomeOpenBreakGeneration;
        long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
        // The StateManager listener runs from inside the implementor's start callback. At
        // that point isRunning()/lastAnimType can still expose the pre-start values. Query on
        // the next main-loop turn, after animTo() has finished publishing the active state.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (miuiHomeOpenBreakCallbackEpoch.get() == callbackEpoch
                    && miuiHomeOpenBreakGeneration == generation
                    && miuiHomeOpenBreakAnimationIdentity == animationIdentity
                    && miuiHomeOpenBreakAnimationActive) {
                refreshMiuiHomeOpenBreakAvailability(
                        miuiHomeOpenBreakController, "animationStartSettled");
            }
        });
        return result;
    }

    private Object mirrorMiuiHomeOpenBreakAnimationEnd(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object endedAnimationIdentity = chain.getArg(0);
        long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
        // Xiaomi's listener posts its actual StateManager cleanup to the main executor.
        // Queue behind it so isOpenAnimRunning() observes the final state. If another OPEN
        // has already replaced this one, the native availability query keeps the new state.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (miuiHomeOpenBreakCallbackEpoch.get() != callbackEpoch) {
                return;
            }
            if (miuiHomeOpenBreakAnimationIdentity == endedAnimationIdentity) {
                miuiHomeOpenBreakAnimationActive = false;
                miuiHomeOpenBreakGenerationPrepared = false;
            }
            refreshMiuiHomeOpenBreakAvailability(
                    miuiHomeOpenBreakController, "animationEnd");
        });
        return result;
    }

    private Object restoreMiuiHomeReusedCloseOpen(XposedInterface.Chain chain)
            throws Throwable {
        Object stateManager = chain.getThisObject();
        Object closingElement;
        boolean reusableClose;
        try {
            closingElement = readField(stateManager, "windowElement");
            reusableClose = closingElement != null
                    && Boolean.TRUE.equals(
                    invokeAnyMethod(closingElement,
                            "isClosingAnimRunning", new Object[0]))
                    && Boolean.TRUE.equals(
                    invokeAnyMethod(closingElement,
                            "isReusefulAnimRunning", new Object[0]));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect reusable launcher CLOSE; proceeding native",
                    throwable);
            return chain.proceed();
        }

        Object result = chain.proceed();
        if (!reusableClose) {
            return result;
        }

        try {
            Object openingElement = readField(stateManager, "windowElement");
            boolean sameElement = openingElement == closingElement;
            boolean openRunning = sameElement && Boolean.TRUE.equals(invokeAnyMethod(
                    openingElement, "isOpenAnimRunning", new Object[0]));
            Object currentAnimType = sameElement
                    ? invokeAnyMethod(openingElement, "getCurrentAnimType", new Object[0])
                    : null;
            boolean openFromHome = currentAnimType instanceof Enum<?>
                    && "OPEN_FROM_HOME".equals(((Enum<?>) currentAnimType).name());
            if (!openRunning || !openFromHome) {
                log(Log.WARN, TAG, "Did not restore reused CLOSE-to-OPEN break state"
                        + ", sameElement=" + sameElement
                        + ", openRunning=" + openRunning
                        + ", openFromHome=" + openFromHome
                        + ", currentAnimType=" + currentAnimType
                        + ", closingElement=" + shortObject(closingElement)
                        + ", openingElement=" + shortObject(openingElement));
                return result;
            }

            Object controller = miuiHomeOpenBreakController;
            if (controller == null) {
                log(Log.WARN, TAG, "Cannot restore reused CLOSE-to-OPEN break state"
                        + ", controller=null"
                        + ", currentAnimType=" + currentAnimType
                        + ", windowElement=" + shortObject(openingElement));
                return result;
            }
            Object animationIdentity = invokeAnyMethod(
                    openingElement, "getAnimSymbol", new Object[0]);
            if (animationIdentity == null) {
                log(Log.WARN, TAG, "Cannot identify reused CLOSE-to-OPEN animation"
                        + ", animationIdentity=null"
                        + ", currentAnimType=" + currentAnimType
                        + ", windowElement=" + shortObject(openingElement));
                return result;
            }

            // Xiaomi retargets a reusable CLOSE_TO_HOME animator to OPEN_FROM_HOME in place.
            // That path has no new WindowElement animation-start callback and does not call
            // enableBackBreakOpenAnim(), so the controller and our generation both remain tied
            // to the old CLOSE. Restore Xiaomi's native enable state, then adopt the already
            // running animator under the new generation instead of creating a surface animation.
            long previousGeneration = miuiHomeOpenBreakGeneration;
            invokeAnyMethod(controller, "enableBackBreakOpenAnim", new Object[0]);
            long generation = miuiHomeOpenBreakGeneration;
            if (generation == 0L || generation == previousGeneration) {
                log(Log.WARN, TAG, "Cannot assign reused CLOSE-to-OPEN generation"
                        + ", previousGeneration=" + previousGeneration
                        + ", currentGeneration=" + generation
                        + ", currentAnimType=" + currentAnimType
                        + ", animationIdentity=" + shortObject(animationIdentity)
                        + ", windowElement=" + shortObject(openingElement));
                return result;
            }

            miuiHomeOpenBreakAnimationIdentity = animationIdentity;
            miuiHomeOpenBreakGenerationPrepared = false;
            miuiHomeOpenBreakAnimationActive = true;
            miuiHomeOpenBreakCommandPending = false;
            log(Log.INFO, TAG, "Restored native launcher OPEN break for reused CLOSE animation"
                    + ", generation=" + generation
                    + ", currentAnimType=" + currentAnimType
                    + ", animationIdentity=" + shortObject(animationIdentity)
                    + ", windowElement=" + shortObject(openingElement));

            long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (miuiHomeOpenBreakCallbackEpoch.get() != callbackEpoch
                        || miuiHomeOpenBreakGeneration != generation
                        || miuiHomeOpenBreakAnimationIdentity != animationIdentity
                        || !miuiHomeOpenBreakAnimationActive) {
                    return;
                }
                Object currentElement = null;
                try {
                    currentElement = readField(stateManager, "windowElement");
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "Failed to verify reused CLOSE-to-OPEN element",
                            throwable);
                }
                if (currentElement != openingElement) {
                    miuiHomeOpenBreakAnimationActive = false;
                    refreshMiuiHomeOpenBreakAvailability(
                            controller, "reusedCloseOpenReplaced");
                    return;
                }
                refreshMiuiHomeOpenBreakAvailability(controller, "reusedCloseOpenSettled");
            });
        } catch (Throwable throwable) {
            // The native retarget already completed. A module-side bookkeeping failure must
            // never turn a successful launcher click into an exception for MiuiHome.
            log(Log.ERROR, TAG, "Failed to restore reused CLOSE-to-OPEN break state",
                    throwable);
        }
        return result;
    }

    private long nextMiuiHomeOpenBreakGeneration() {
        return miuiHomeOpenBreakGenerationIds.incrementAndGet();
    }

    private void restoreMiuiHomeOpenBreakAfterHotReload() {
        Object controller = miuiHomeOpenBreakController;
        if (controller == null) {
            return;
        }
        long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (miuiHomeOpenBreakCallbackEpoch.get() == callbackEpoch) {
                refreshMiuiHomeOpenBreakAvailability(controller, "hotReload");
            }
        });
    }

    private void refreshMiuiHomeOpenBreakAvailability(Object controller, String reason) {
        if (controller == null) {
            log(Log.INFO, TAG, "MiuiHome launcher OPEN break controller unavailable"
                    + ", reason=" + reason);
            return;
        }
        miuiHomeOpenBreakController = controller;
        Context context = resolveMiuiHomeOpenBreakContext(controller);
        if (context == null) {
            log(Log.WARN, TAG, "Cannot publish MiuiHome launcher OPEN break state"
                    + ", reason=" + reason
                    + ", controller=" + shortObject(controller));
            return;
        }
        boolean receiverReady = ensureMiuiHomeOpenBreakCommandReceiver(context);
        long generation = miuiHomeOpenBreakGeneration;
        boolean available = receiverReady
                && generation != 0L
                && miuiHomeOpenBreakAnimationActive
                && !miuiHomeOpenBreakCommandPending
                && canUseMiuiHomeOpenBreak(controller, reason);
        String nativeState = describeMiuiHomeOpenBreakNativeState(controller);
        try {
            Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            stateIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE, available);
            stateIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, generation);
            sendAuthenticatedMiuiHomeState(context, stateIntent);
            log(Log.INFO, TAG, "Published MiuiHome launcher OPEN break state"
                    + ", available=" + available
                    + ", receiverReady=" + receiverReady
                    + ", generation=" + generation
                    + ", commandPending=" + miuiHomeOpenBreakCommandPending
                    + ", nativeState=" + nativeState
                    + ", reason=" + reason
                    + ", controller=" + shortObject(controller));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to publish MiuiHome launcher OPEN break state"
                    + ", reason=" + reason, throwable);
        }
    }

    private Context resolveMiuiHomeOpenBreakContext(Object controller) {
        Context context = miuiHomeOpenBreakContext;
        if (context != null) {
            return context;
        }
        try {
            Object navStubView = invokeAnyMethod(controller, "getNavStubView", new Object[0]);
            if (navStubView instanceof View) {
                context = ((View) navStubView).getContext().getApplicationContext();
                miuiHomeOpenBreakContext = context;
                return context;
            }
            log(Log.INFO, TAG, "MiuiHome NavStubView unavailable while resolving context"
                    + ", view=" + shortObject(navStubView));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to resolve MiuiHome OPEN break context", throwable);
        }
        return null;
    }

    private boolean canUseMiuiHomeOpenBreak(Object controller, String reason) {
        try {
            return Boolean.TRUE.equals(invokeAnyMethod(controller,
                    "canUseBackGestureBreakOpenAnim", new Object[0]));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to query native MiuiHome OPEN break availability"
                    + ", reason=" + reason, throwable);
            return false;
        }
    }

    private String describeMiuiHomeOpenBreakNativeState(Object controller) {
        if (controller == null) {
            return "controller=null";
        }
        Object navStubView = null;
        Object openRunning = null;
        Object belowThreshold = null;
        Object appLaunching = null;
        try {
            navStubView = invokeAnyMethod(controller, "getNavStubView", new Object[0]);
        } catch (Throwable ignored) {
        }
        try {
            openRunning = invokeAnyMethod(controller, "isOpenAnimRunning", new Object[0]);
        } catch (Throwable ignored) {
        }
        try {
            belowThreshold = invokeAnyMethod(controller,
                    "isOpenAnimBelowBackBreakThreshold", new Object[0]);
        } catch (Throwable ignored) {
        }
        try {
            appLaunching = readField(controller, "mAppLaunchingButNotResume");
        } catch (Throwable ignored) {
        }
        return "navStub=" + (navStubView != null)
                + ", openRunning=" + openRunning
                + ", belowThreshold=" + belowThreshold
                + ", appLaunching=" + appLaunching;
    }

    private synchronized boolean ensureMiuiHomeOpenBreakCommandReceiver(Context context) {
        if (miuiHomeOpenBreakCommandReceiver != null) {
            return true;
        }
        if (context == null) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !MODULE_MIUI_HOME_OPEN_BREAK_COMMAND.equals(
                        intent.getAction())) {
                    return;
                }
                if (!isOrderedBroadcast()) {
                    log(Log.WARN, TAG, "Rejected unordered launcher OPEN break command");
                    return;
                }
                setResultCode(LAUNCHER_OPEN_BREAK_RESULT_REJECTED);
                setResultData("rejected");
                int senderUid = getSentFromUid();
                String senderPackage = getSentFromPackage();
                if (!isTrustedSystemUiBroadcastSender(
                        receiverContext, senderUid, senderPackage)) {
                    log(Log.WARN, TAG, "Rejected untrusted launcher OPEN break command"
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                    setResultData("untrustedSender");
                    return;
                }
                long commandGeneration = intent.getLongExtra(
                        EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, 0L);
                long attemptId = intent.getLongExtra(
                        EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT, 0L);
                Object controller = miuiHomeOpenBreakController;
                if (commandGeneration == 0L
                        || attemptId == 0L
                        || commandGeneration != miuiHomeOpenBreakGeneration
                        || !miuiHomeOpenBreakAnimationActive
                        || miuiHomeOpenBreakCommandPending
                        || controller == null
                        || !canUseMiuiHomeOpenBreak(controller, "command")) {
                    log(Log.WARN, TAG, "Rejected stale launcher OPEN break command"
                            + ", commandGeneration=" + commandGeneration
                            + ", currentGeneration=" + miuiHomeOpenBreakGeneration
                            + ", attempt=" + attemptId
                            + ", animationActive=" + miuiHomeOpenBreakAnimationActive
                            + ", commandPending=" + miuiHomeOpenBreakCommandPending
                            + ", controller=" + shortObject(controller));
                    setResultData(commandGeneration != miuiHomeOpenBreakGeneration
                            ? "generationMismatch" : "nativeUnavailable");
                    refreshMiuiHomeOpenBreakAvailability(controller, "commandRejected");
                    return;
                }
                miuiHomeOpenBreakCommandPending = true;
                refreshMiuiHomeOpenBreakAvailability(controller, "commandAccepted");
                try {
                    // Dynamic receivers run on MiuiHome's main thread. Xiaomi's
                    // MainThreadExecutor executes inline when already on that Looper, so an
                    // accepted ordered result means executeBackGestureBreak() has returned.
                    invokeAnyMethod(controller, "breakOpenAnim", new Object[0]);
                    setResultCode(LAUNCHER_OPEN_BREAK_RESULT_ACCEPTED);
                    setResultData("accepted");
                    log(Log.INFO, TAG, "Requested native MiuiHome launcher OPEN break"
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage
                            + ", generation=" + commandGeneration
                            + ", attempt=" + attemptId
                            + ", controller=" + shortObject(controller));
                } catch (Throwable throwable) {
                    setResultData("nativeException");
                    log(Log.ERROR, TAG, "Failed to request native MiuiHome launcher OPEN break",
                            throwable);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(MODULE_MIUI_HOME_OPEN_BREAK_COMMAND);
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            miuiHomeOpenBreakCommandReceiverContext = appContext;
            miuiHomeOpenBreakCommandReceiver = receiver;
            log(Log.INFO, TAG, "Registered MiuiHome launcher OPEN break command receiver");
            return true;
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to register MiuiHome launcher OPEN break receiver",
                    throwable);
            return false;
        }
    }

    private boolean isTrustedSystemUiBroadcastSender(Context context, int uid,
                                                       String senderPackage) {
        if (context == null || uid == Process.INVALID_UID
                || !SYSTEM_UI.equals(senderPackage)) {
            return false;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String packageName : packages) {
                    if (SYSTEM_UI.equals(packageName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to validate SystemUI command sender uid=" + uid,
                    throwable);
        }
        return false;
    }

    private synchronized void unregisterMiuiHomeOpenBreakCommandReceiver() {
        BroadcastReceiver receiver = miuiHomeOpenBreakCommandReceiver;
        Context receiverContext = miuiHomeOpenBreakCommandReceiverContext;
        miuiHomeOpenBreakCommandReceiver = null;
        miuiHomeOpenBreakCommandReceiverContext = null;
        if (receiver == null || receiverContext == null) {
            return;
        }
        try {
            receiverContext.unregisterReceiver(receiver);
            log(Log.INFO, TAG, "Unregistered MiuiHome launcher OPEN break receiver");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to unregister MiuiHome launcher OPEN break receiver",
                    throwable);
        }
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
            stateIntent.putExtra("overview_visible", overviewVisible);
            sendAuthenticatedMiuiHomeState((Context) contextObject, stateIntent);
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
                stateIntent.putExtra("overview_visible", false);
                stateIntent.putExtra("task_launch_started", true);
                sendAuthenticatedMiuiHomeState(((View) taskView).getContext(), stateIntent);
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

    private Object mirrorMiuiHomeFullscreenState(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object contextObject = chain.getArg(0);
        Object stateObject = chain.getArg(1);
        if (!(contextObject instanceof Context) || !(stateObject instanceof String)) {
            log(Log.WARN, TAG, "Cannot mirror MiuiHome fullscreen state"
                    + ", context=" + shortObject(contextObject)
                    + ", state=" + shortObject(stateObject));
            return result;
        }
        try {
            Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            stateIntent.putExtra("state", (String) stateObject);
            stateIntent.putExtra("fullscreen_state", true);
            sendAuthenticatedMiuiHomeState((Context) contextObject, stateIntent);
            log(Log.INFO, TAG, "Mirrored MiuiHome fullscreen state"
                    + ", state=" + stateObject);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to mirror MiuiHome fullscreen state", throwable);
        }
        return result;
    }

    private Object mirrorMiuiHomeDrawerState(XposedInterface.Chain chain)
            throws Throwable {
        Object manager = chain.getThisObject();
        Object targetState = chain.getArg(0);
        try {
            ClassLoader classLoader = targetState == null
                    ? manager.getClass().getClassLoader()
                    : targetState.getClass().getClassLoader();
            Class<?> stateClass = Class.forName(MIUI_HOME_LAUNCHER_STATE, false, classLoader);
            Object allAppsState = readStaticField(stateClass, "ALL_APPS");
            boolean drawerVisible = targetState == allAppsState;
            Object launcher = readField(manager, "mLauncher");
            if (launcher instanceof Context) {
                miuiDrawerVisible = drawerVisible;
                Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
                stateIntent.putExtra("drawer_visible", drawerVisible);
                sendAuthenticatedMiuiHomeState((Context) launcher, stateIntent);
                log(Log.INFO, TAG, "Mirrored MiuiHome drawer state"
                        + ", drawerVisible=" + drawerVisible
                        + ", target=" + shortObject(targetState));
            } else {
                log(Log.WARN, TAG, "Cannot mirror MiuiHome drawer state: launcher="
                        + shortObject(launcher));
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to mirror MiuiHome drawer state", throwable);
        }
        return chain.proceed();
    }

    private void sendAuthenticatedMiuiHomeState(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
        Intent explicitIntent = new Intent(intent);
        explicitIntent.setPackage(SYSTEM_UI);
        Bundle options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle();
        appContext.sendBroadcast(explicitIntent, null, options);
    }

    private void sendAuthenticatedMiuiHomeOpenBreakCommand(
            Context context, long generation, long attemptId,
            SystemUiBackGestureDriver driver) {
        // Close the local admission gate as soon as one committed command is emitted. The
        // MiuiHome receiver independently revalidates its native controller before acting.
        if (miuiLauncherOpenBreakGeneration == generation) {
            miuiLauncherOpenBreakAvailable = false;
        }
        Context appContext = context.getApplicationContext();
        Intent commandIntent = new Intent(MODULE_MIUI_HOME_OPEN_BREAK_COMMAND);
        commandIntent.setPackage(MIUI_HOME);
        commandIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, generation);
        commandIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT, attemptId);
        Bundle options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle();
        BroadcastReceiver resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                driver.onLauncherOpenBreakCommandResult(
                        generation, attemptId, getResultCode(), getResultData());
            }
        };
        appContext.sendOrderedBroadcast(commandIntent, null, options,
                resultReceiver, new Handler(Looper.getMainLooper()),
                LAUNCHER_OPEN_BREAK_RESULT_NO_RECEIVER, "noReceiver", null);
        log(Log.INFO, TAG, "Sent authenticated MiuiHome launcher OPEN break command"
                + ", generation=" + generation
                + ", attempt=" + attemptId
                + ", ordered=true");
    }

    private Object arbitrateMiuiHomeAcceptedInput(XposedInterface.Chain chain) {
        Object eventObject = chain.getArg(0);
        Object stubObject = chain.getArg(1);
        if (!(eventObject instanceof MotionEvent) || !(stubObject instanceof View)) {
            log(Log.ERROR, TAG, "Blocked malformed MiuiHome gesture-processor call"
                    + ", event=" + shortObject(eventObject)
                    + ", stub=" + shortObject(stubObject));
            return null;
        }
        MotionEvent event = (MotionEvent) eventObject;
        View stub = (View) stubObject;
        ensureMiuiHomeInputArbiterReceiver(stub.getContext());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && event.getPointerCount() == 1
                && miuiHomeSystemUiInputArbiterReady
                && miuiHomeSystemUiInputArbiterGeneration != 0L) {
            try {
                int eventId = readMotionEventId(event);
                int edge = readIntFieldOrDefault(stub, "mGestureStubPos", -1);
                if (edge != EDGE_LEFT && edge != EDGE_RIGHT) {
                    throw new IllegalStateException("Invalid GestureStub edge=" + edge);
                }
                int displayId = readMotionEventDisplayId(event);
                Intent acceptedIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
                acceptedIntent.putExtra(EXTRA_INPUT_ACCEPTED, true);
                acceptedIntent.putExtra(EXTRA_INPUT_EVENT_ID, eventId);
                acceptedIntent.putExtra(EXTRA_INPUT_DOWN_TIME, event.getDownTime());
                acceptedIntent.putExtra(EXTRA_INPUT_DEVICE_ID, event.getDeviceId());
                acceptedIntent.putExtra(EXTRA_INPUT_SOURCE, event.getSource());
                acceptedIntent.putExtra(EXTRA_INPUT_DISPLAY_ID, displayId);
                acceptedIntent.putExtra(EXTRA_INPUT_EDGE, edge);
                acceptedIntent.putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                        miuiHomeSystemUiInputArbiterGeneration);
                sendAuthenticatedMiuiHomeState(stub.getContext(), acceptedIntent);
                log(Log.INFO, TAG, "Published MiuiHome accepted input token"
                        + ", eventId=" + eventId
                        + ", downTime=" + event.getDownTime()
                        + ", displayId=" + displayId
                        + ", edge=" + edge
                        + ", generation="
                        + miuiHomeSystemUiInputArbiterGeneration);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to publish MiuiHome accepted input token; "
                        + "gesture remains fail-closed", throwable);
            }
        }
        // GestureStubView calls this method only after its native redirect and early-route
        // decisions have accepted the stream. Keep the real Xiaomi input target, but never
        // let its legacy processor create a second BackAnimationAdapter, arrow, or BACK.
        return null;
    }

    private int readMotionEventId(MotionEvent event) throws Exception {
        Object value = invokeAnyMethod(event, "getId", new Object[0]);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("MotionEvent.getId returned "
                    + shortObject(value));
        }
        return ((Number) value).intValue();
    }

    private int readMotionEventDisplayId(MotionEvent event) throws Exception {
        Object value = invokeAnyMethod(event, "getDisplayId", new Object[0]);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private synchronized void ensureMiuiHomeInputArbiterReceiver(Context context) {
        if (miuiHomeInputArbiterReceiver != null || context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !MODULE_SYSTEMUI_INPUT_ARBITER_STATE.equals(
                        intent.getAction())) {
                    return;
                }
                int senderUid = getSentFromUid();
                String senderPackage = getSentFromPackage();
                if (!isTrustedSystemUiBroadcastSender(
                        receiverContext, senderUid, senderPackage)) {
                    log(Log.WARN, TAG, "Rejected untrusted SystemUI input-arbiter state"
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                    return;
                }
                long generation = intent.getLongExtra(
                        EXTRA_INPUT_ARBITER_GENERATION, 0L);
                boolean ready = intent.getBooleanExtra(EXTRA_INPUT_ARBITER_READY, false);
                if (generation == 0L) {
                    return;
                }
                if (generation >= miuiHomeSystemUiInputArbiterGeneration) {
                    miuiHomeSystemUiInputArbiterGeneration = generation;
                    miuiHomeSystemUiInputArbiterReady = ready;
                }
                log(Log.INFO, TAG, "SystemUI input arbiter state changed"
                        + ", ready=" + miuiHomeSystemUiInputArbiterReady
                        + ", generation="
                        + miuiHomeSystemUiInputArbiterGeneration
                        + ", senderGeneration=" + generation);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            miuiHomeInputArbiterReceiverContext = appContext;
            miuiHomeInputArbiterReceiver = receiver;
            Intent query = new Intent(MODULE_MIUI_HOME_INPUT_ARBITER_QUERY);
            sendAuthenticatedMiuiHomeState(appContext, query);
            log(Log.INFO, TAG, "Registered MiuiHome input-arbiter state receiver"
                    + ", queriedSystemUi=true");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to register MiuiHome input-arbiter receiver",
                    throwable);
        }
    }

    private synchronized void unregisterMiuiHomeInputArbiterReceiver() {
        BroadcastReceiver receiver = miuiHomeInputArbiterReceiver;
        Context receiverContext = miuiHomeInputArbiterReceiverContext;
        miuiHomeInputArbiterReceiver = null;
        miuiHomeInputArbiterReceiverContext = null;
        miuiHomeSystemUiInputArbiterReady = false;
        miuiHomeSystemUiInputArbiterGeneration = 0L;
        if (receiver == null || receiverContext == null) {
            return;
        }
        try {
            receiverContext.unregisterReceiver(receiver);
            log(Log.INFO, TAG, "Unregistered MiuiHome input-arbiter receiver");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to unregister MiuiHome input-arbiter receiver",
                    throwable);
        }
    }

    private void onSystemUiInputMonitorAttached(Context context) {
        int count = systemUiInputArbiterMonitorCount.incrementAndGet();
        publishSystemUiInputArbiterState(context, count > 0, "monitorAttached");
    }

    private void onSystemUiInputMonitorDetached(Context context) {
        int count = systemUiInputArbiterMonitorCount.decrementAndGet();
        if (count < 0) {
            systemUiInputArbiterMonitorCount.set(0);
            count = 0;
        }
        publishSystemUiInputArbiterState(context, count > 0, "monitorDetached");
    }

    private void publishSystemUiInputArbiterState(Context context, boolean ready,
                                                   String reason) {
        if (context == null) {
            return;
        }
        try {
            Intent stateIntent = new Intent(MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
            stateIntent.setPackage(MIUI_HOME);
            stateIntent.putExtra(EXTRA_INPUT_ARBITER_READY, ready);
            stateIntent.putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                    systemUiInputArbiterGeneration);
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.getApplicationContext().sendBroadcast(stateIntent, null, options);
            log(Log.INFO, TAG, "Published SystemUI input-arbiter state"
                    + ", ready=" + ready
                    + ", generation=" + systemUiInputArbiterGeneration
                    + ", monitors=" + systemUiInputArbiterMonitorCount.get()
                    + ", reason=" + reason);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to publish SystemUI input-arbiter state"
                    + ", reason=" + reason, throwable);
        }
    }

    private void restoreMiuiHomeGestureStubsAfterHotReload(ClassLoader classLoader) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Class<?> applicationClass = Class.forName(
                        "com.miui.home.launcher.Application", false, classLoader);
                Method getApplication = applicationClass.getDeclaredMethod(
                        "getLauncherApplication");
                getApplication.setAccessible(true);
                Object application = getApplication.invoke(null);
                if (application == null) {
                    log(Log.WARN, TAG, "Cannot restore MiuiHome GestureStubs: "
                            + "launcher application is null");
                    return;
                }
                Object recents = invokeAnyMethod(application, "getRecentsImpl",
                        new Object[0]);
                if (recents == null) {
                    log(Log.WARN, TAG, "Cannot restore MiuiHome GestureStubs: "
                            + "RecentsImpl is null");
                    return;
                }
                Object left = readField(recents, "mGestureStubLeft");
                Object right = readField(recents, "mGestureStubRight");
                AtomicInteger remaining = new AtomicInteger(2);
                Runnable restored = () -> {
                    if (remaining.decrementAndGet() != 0) {
                        return;
                    }
                    Runnable recomputePolicy = () -> {
                        try {
                            invokeAnyMethod(recents, "adaptToTopActivity", new Object[0]);
                            log(Log.INFO, TAG, "Recomputed native MiuiHome GestureStub policy "
                                    + "after hot reload");
                        } catch (Throwable throwable) {
                            log(Log.WARN, TAG, "Failed to recompute native MiuiHome "
                                    + "GestureStub policy", throwable);
                        }
                    };
                    try {
                        Object handler = readField(recents, "mHandler");
                        if (handler instanceof Handler
                                && ((Handler) handler).post(recomputePolicy)) {
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    new Handler(Looper.getMainLooper()).post(recomputePolicy);
                };
                restoreMiuiHomeGestureStubOnOwner(left, "left", restored);
                restoreMiuiHomeGestureStubOnOwner(right, "right", restored);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to restore existing MiuiHome GestureStubs",
                        throwable);
            }
        });
    }

    private void restoreMiuiHomeGestureStubOnOwner(Object stubObject, String edge,
                                                    Runnable completion) {
        if (!(stubObject instanceof View)) {
            log(Log.INFO, TAG, "MiuiHome GestureStub absent during hot reload"
                    + ", edge=" + edge);
            completion.run();
            return;
        }
        View stub = (View) stubObject;
        Runnable restore = () -> {
            try {
                ensureMiuiHomeInputArbiterReceiver(stub.getContext());
                Object paramsObject = readField(stub, "mGestureStubParams");
                Object windowManagerObject = readField(stub, "mWindowManager");
                if (!(paramsObject instanceof WindowManager.LayoutParams)
                        || !(windowManagerObject instanceof WindowManager)) {
                    throw new IllegalStateException("Missing native window state");
                }
                WindowManager.LayoutParams params =
                        (WindowManager.LayoutParams) paramsObject;
                boolean changed = (params.flags
                        & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                if (stub.isAttachedToWindow()) {
                    ((WindowManager) windowManagerObject).updateViewLayout(stub, params);
                }
                stub.requestLayout();
                stub.requestApplyInsets();
                log(Log.INFO, TAG, "Restored existing MiuiHome GestureStub window"
                        + ", edge=" + edge
                        + ", attached=" + stub.isAttachedToWindow()
                        + ", clearedNotTouchable=" + changed);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to restore MiuiHome GestureStub"
                        + ", edge=" + edge, throwable);
            } finally {
                completion.run();
            }
        };
        Handler ownerHandler = stub.getHandler();
        if (ownerHandler == null || ownerHandler.getLooper() == Looper.myLooper()) {
            restore.run();
        } else if (!ownerHandler.post(restore)) {
            log(Log.WARN, TAG, "Failed to post MiuiHome GestureStub restore"
                    + ", edge=" + edge);
            completion.run();
        }
    }

    private Object restoreMiuiHomeGestureStubTouchableLayout(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (result instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) result;
            layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        return result;
    }

    private static final class MiuiHomeAcceptedInputToken {
        final int eventId;
        final long downTime;
        final int deviceId;
        final int source;
        final int displayId;
        final int edge;
        final long generation;
        final long receivedUptime;

        MiuiHomeAcceptedInputToken(int eventId, long downTime, int deviceId,
                                   int source, int displayId, int edge,
                                   long generation) {
            this.eventId = eventId;
            this.downTime = downTime;
            this.deviceId = deviceId;
            this.source = source;
            this.displayId = displayId;
            this.edge = edge;
            this.generation = generation;
            this.receivedUptime = SystemClock.uptimeMillis();
        }

        boolean isExpired() {
            long now = SystemClock.uptimeMillis();
            long streamAge = now - downTime;
            return now - receivedUptime > INPUT_ACCEPTED_TOKEN_TIMEOUT_MS
                    || streamAge < 0L
                    || streamAge > INPUT_ACCEPTED_TOKEN_TIMEOUT_MS;
        }
    }

    private Object restoreMiuiHomeGestureStubShow(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        try {
            Object view = chain.getThisObject();
            if (view instanceof View) {
                ensureMiuiHomeInputArbiterReceiver(((View) view).getContext());
                Object params = readField(view, "mGestureStubParams");
                Object windowManager = readField(view, "mWindowManager");
                if (params instanceof WindowManager.LayoutParams
                        && windowManager instanceof WindowManager
                        && ((((WindowManager.LayoutParams) params).flags
                        & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)) {
                    WindowManager.LayoutParams layoutParams =
                            (WindowManager.LayoutParams) params;
                    layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    if (((View) view).isAttachedToWindow()) {
                        ((WindowManager) windowManager).updateViewLayout(
                                (View) view, layoutParams);
                    }
                    ((View) view).requestLayout();
                    ((View) view).requestApplyInsets();
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to restore MiuiHome gesture-stub window", throwable);
        }
        return result;
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
            String lower = String.valueOf(argument).toLowerCase(Locale.ROOT);
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
            Class<?> transitionInfoClass = Class.forName("android.window.TransitionInfo",
                    false, classLoader);
            Class<?> finishCallbackClass = Class.forName(
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback",
                    false, classLoader);
            resolveDefaultTransitionSnapshotReflection(handlerClass, transitionInfoClass);
            Method startAnimation = handlerClass.getDeclaredMethod("startAnimation",
                    IBinder.class, transitionInfoClass, SurfaceControl.Transaction.class,
                    SurfaceControl.Transaction.class, finishCallbackClass);
            startAnimation.setAccessible(true);
            recordHookHandle(hook(startAnimation)
                    .setId("systemui_default_transition_start")
                    .intercept(this::registerDefaultTransitionHandler));
            log(Log.INFO, TAG, "Hooked exact DefaultTransitionHandler.startAnimation");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook DefaultTransitionHandler", throwable);
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private synchronized void resolveDefaultTransitionSnapshotReflection(
            Class<?> handlerClass, Class<?> transitionInfoClass) throws ReflectiveOperationException {
        if (defaultTransitionAnimationsField != null
                && defaultTransitionAnimationSizeField != null
                && defaultTransitionAnimExecutorField != null
                && transitionInfoGetTypeMethod != null
                && animatorCanReverseMethod != null) {
            return;
        }
        Field animationsField = handlerClass.getDeclaredField("mAnimations");
        Field animationSizeField = handlerClass.getDeclaredField("mAnimationSize");
        Field animExecutorField = handlerClass.getDeclaredField("mAnimExecutor");
        Method getTypeMethod = transitionInfoClass.getDeclaredMethod("getType");
        // Animator.canReverse() is a boot-classpath hidden API. LSPosed loads this code inside
        // SystemUI with hidden-API access; the public SDK stub does not expose the method.
        Method canReverseMethod = Animator.class.getDeclaredMethod("canReverse");
        animationsField.setAccessible(true);
        animationSizeField.setAccessible(true);
        animExecutorField.setAccessible(true);
        getTypeMethod.setAccessible(true);
        canReverseMethod.setAccessible(true);
        defaultTransitionAnimationsField = animationsField;
        defaultTransitionAnimationSizeField = animationSizeField;
        defaultTransitionAnimExecutorField = animExecutorField;
        transitionInfoGetTypeMethod = getTypeMethod;
        animatorCanReverseMethod = canReverseMethod;
    }

    private Object registerDefaultTransitionHandler(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (!Boolean.TRUE.equals(result)) {
            return result;
        }
        try {
            captureRunningOpenTransition(chain.getThisObject(), chain.getArg(0),
                    chain.getArg(1));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to capture Xiaomi OPEN transition snapshot",
                    throwable);
        }
        return result;
    }

    private void captureRunningOpenTransition(Object handler, Object token, Object info)
            throws Exception {
        if (handler == null || token == null || info == null) {
            return;
        }
        resolveDefaultTransitionSnapshotReflection(handler.getClass(), info.getClass());
        Object type = transitionInfoGetTypeMethod.invoke(info);
        if (!(type instanceof Number) || ((Number) type).intValue() != 1) {
            return;
        }
        Object animationsObject = defaultTransitionAnimationsField.get(handler);
        Object animationSizeObject = defaultTransitionAnimationSizeField.get(handler);
        Object executorObject = defaultTransitionAnimExecutorField.get(handler);
        if (!(animationsObject instanceof Map) || !(animationSizeObject instanceof Map)
                || !(executorObject instanceof Executor)) {
            throw new IllegalStateException("Unexpected DefaultTransitionHandler fields"
                    + ", animations=" + shortObject(animationsObject)
                    + ", animationSize=" + shortObject(animationSizeObject)
                    + ", executor=" + shortObject(executorObject));
        }
        Object animatorListObject = ((Map<?, ?>) animationsObject).get(token);
        if (!(animatorListObject instanceof List)) {
            return;
        }
        List<?> animatorList = (List<?>) animatorListObject;
        Animator[] animators = new Animator[animatorList.size()];
        for (int index = 0; index < animatorList.size(); index++) {
            Object animator = animatorList.get(index);
            if (!(animator instanceof Animator)) {
                throw new IllegalStateException("Unexpected transition animator="
                        + shortObject(animator));
            }
            animators[index] = (Animator) animator;
        }
        Object originalSizeObject = ((Map<?, ?>) animationSizeObject).get(token);
        int originalSize = originalSizeObject instanceof Number
                ? ((Number) originalSizeObject).intValue() : 0;
        if (animators.length == 0) {
            return;
        }
        long generation = openSnapshotGeneration.get();
        if (!acceptingOpenSnapshots) {
            return;
        }
        OpenTransitionSnapshot snapshot = new OpenTransitionSnapshot(token, info, animators,
                originalSize, (Executor) executorObject, generation);
        OpenTransitionSnapshot previous = runningOpenTransitions.put(token, snapshot);
        if (previous != null) {
            invalidateOpenTransitionSnapshot(previous, "replaced");
        }
        if (!acceptingOpenSnapshots || generation != openSnapshotGeneration.get()) {
            invalidateOpenTransitionSnapshot(snapshot, "generationChanged");
            return;
        }
        try {
            snapshot.animExecutor.execute(() -> verifyAndActivateOpenTransition(snapshot));
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "executorRejected");
            throw new IllegalStateException("Animation executor rejected OPEN snapshot",
                    throwable);
        }
    }

    private void verifyAndActivateOpenTransition(OpenTransitionSnapshot snapshot) {
        try {
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || snapshot.state.get() != OPEN_SNAPSHOT_PENDING) {
                invalidateOpenTransitionSnapshot(snapshot, "staleValidator");
                return;
            }
            if (snapshot.animatorCount != snapshot.originalAnimatorCount) {
                log(Log.INFO, TAG, "Skipped partial Xiaomi OPEN transition snapshot"
                        + ", currentAnimatorCount=" + snapshot.animatorCount
                        + ", originalAnimatorCount=" + snapshot.originalAnimatorCount);
                invalidateOpenTransitionSnapshot(snapshot, "partialAnimationSet");
                return;
            }
            for (Animator animator : snapshot.animators) {
                if (!Boolean.TRUE.equals(animatorCanReverseMethod.invoke(animator))
                        || !animator.isRunning()) {
                    invalidateOpenTransitionSnapshot(snapshot, "notReversible");
                    return;
                }
            }
            AnimatorListenerAdapter invalidationListener =
                    new OpenTransitionInvalidationListener(this, snapshot);
            snapshot.listener = invalidationListener;
            for (Animator animator : snapshot.animators) {
                animator.addListener(invalidationListener);
            }
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || !snapshot.state.compareAndSet(
                    OPEN_SNAPSHOT_PENDING, OPEN_SNAPSHOT_ACTIVE)) {
                removeOpenTransitionListeners(snapshot);
                invalidateOpenTransitionSnapshot(snapshot, "activationRace");
                return;
            }
            log(Log.INFO, TAG, "Published reversible Xiaomi OPEN transition snapshot"
                    + ", animatorCount=" + snapshot.animatorCount
                    + ", info=" + shortObject(snapshot.transitionInfo));
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "verificationFailure");
            log(Log.WARN, TAG, "Failed to verify Xiaomi OPEN transition snapshot",
                    throwable);
        }
    }

    private void invalidateOpenTransitionSnapshot(OpenTransitionSnapshot snapshot,
                                                  String reason) {
        if (snapshot == null
                || snapshot.state.getAndSet(OPEN_SNAPSHOT_INVALID)
                == OPEN_SNAPSHOT_INVALID) {
            return;
        }
        runningOpenTransitions.remove(snapshot.token, snapshot);
        AnimatorListenerAdapter listener = snapshot.listener;
        if (listener != null) {
            try {
                snapshot.animExecutor.execute(() -> removeOpenTransitionListeners(snapshot));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to remove Xiaomi OPEN snapshot listeners"
                        + ", reason=" + reason, throwable);
            }
        }
        log(Log.INFO, TAG, "Invalidated Xiaomi OPEN transition snapshot"
                + ", reason=" + reason
                + ", animatorCount=" + snapshot.animatorCount);
    }

    private void removeOpenTransitionListeners(OpenTransitionSnapshot snapshot) {
        AnimatorListenerAdapter listener = snapshot.listener;
        if (listener == null) {
            return;
        }
        for (Animator animator : snapshot.animators) {
            animator.removeListener(listener);
        }
        snapshot.listener = null;
    }

    private void invalidateOpenTransitionForInfo(Object info, String reason) {
        for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
            if (snapshot.transitionInfo == info) {
                invalidateOpenTransitionSnapshot(snapshot, reason);
            }
        }
    }

    private void invalidateAllOpenTransitionSnapshots(String reason) {
        int count = runningOpenTransitions.size();
        for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
            invalidateOpenTransitionSnapshot(snapshot, reason);
        }
        runningOpenTransitions.clear();
        if (count > 0) {
            log(Log.INFO, TAG, "Cleared Xiaomi OPEN transition snapshots"
                    + ", reason=" + reason
                    + ", count=" + count);
        }
    }

    private void hookDefaultTransitionImplMerge(ClassLoader classLoader) {
        try {
            Class<?> implementationClass = Class.forName(DEFAULT_TRANSITION_IMPL, false,
                    classLoader);
            Class<?> shellExecutorClass = Class.forName(
                    "com.android.wm.shell.common.ShellExecutor", false, classLoader);
            Class<?> transitionInfoClass = Class.forName("android.window.TransitionInfo",
                    false, classLoader);
            Class<?> finishCallbackClass = Class.forName(
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback",
                    false, classLoader);
            Method mergeAnimation = implementationClass.getDeclaredMethod("mergeAnimation",
                    shellExecutorClass, shellExecutorClass, IBinder.class,
                    transitionInfoClass, ArrayList.class, transitionInfoClass,
                    int.class, finishCallbackClass);
            mergeAnimation.setAccessible(true);
            recordHookHandle(hook(mergeAnimation)
                    .setId("systemui_default_transition_merge")
                    .intercept(this::trackMiuiOpenCloseMerge));
            log(Log.INFO, TAG, "Hooked exact DefaultTransitionImpl.mergeAnimation");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook DefaultTransitionImpl.mergeAnimation",
                    throwable);
        }
    }

    private Object trackMiuiOpenCloseMerge(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (Boolean.TRUE.equals(result)) {
            Object runningInfo = chain.getArg(5);
            invalidateOpenTransitionForInfo(runningInfo, "reverseMerge");
            correlateLegacyBackMerge(runningInfo);
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
        if (moduleLegacyBackInjection.get() != null) {
            return chain.proceed();
        }
        if (shouldSuppressDuplicateBack(chain.getThisObject(), action)) {
            return null;
        }
        return chain.proceed();
    }

    private LegacyBackAttempt armLegacyBackGuard(Object controller, Object runningInfo) {
        long now = SystemClock.uptimeMillis();
        LegacyBackAttempt attempt = new LegacyBackAttempt(
                legacyBackAttemptIds.incrementAndGet(), controller, runningInfo, now);
        synchronized (legacyBackGuardLock) {
            resetLegacyBackGuardLocked();
            legacyBackAttempt = attempt;
            legacyBackGuardPhase = BACK_GUARD_WAIT_MERGE;
            legacyBackGuardDeadlineUptime = now + LEGACY_BACK_MERGE_TIMEOUT_MS;
        }
        log(Log.INFO, TAG, "Armed Xiaomi interruption BACK correlation"
                + ", attempt=" + attempt.id
                + ", controller=" + shortObject(controller)
                + ", runningInfo=" + shortObject(runningInfo));
        scheduleLegacyBackGuardExpiry(attempt, LEGACY_BACK_MERGE_TIMEOUT_MS);
        return attempt;
    }

    private void correlateLegacyBackMerge(Object runningInfo) {
        LegacyBackAttempt correlated = null;
        boolean expired = false;
        long now = SystemClock.uptimeMillis();
        synchronized (legacyBackGuardLock) {
            if (legacyBackGuardPhase != BACK_GUARD_WAIT_MERGE
                    || legacyBackAttempt == null) {
                return;
            }
            if (now > legacyBackGuardDeadlineUptime) {
                expired = true;
                correlated = legacyBackAttempt;
                resetLegacyBackGuardLocked();
            } else if (legacyBackAttempt.runningTransitionInfo == runningInfo) {
                correlated = legacyBackAttempt;
                legacyBackGuardPhase = BACK_GUARD_EXPECT_DOWN;
                legacyBackGuardDeadlineUptime = now + DUPLICATE_BACK_PAIR_TIMEOUT_MS;
                suppressedBackDownUptime = 0L;
                suppressedBackDownThread = null;
            }
        }
        if (correlated == null) {
            return;
        }
        if (expired) {
            log(Log.WARN, TAG, "Expired Xiaomi interruption BACK before merge"
                    + ", attempt=" + correlated.id
                    + ", elapsedMs=" + (now - correlated.startedUptime));
            return;
        }
        log(Log.INFO, TAG, "Correlated Xiaomi OPEN/CLOSE reverse merge"
                + ", attempt=" + correlated.id
                + ", elapsedMs=" + (now - correlated.startedUptime)
                + ", duplicatePairDeadlineMs=" + DUPLICATE_BACK_PAIR_TIMEOUT_MS);
        scheduleLegacyBackGuardExpiry(correlated, DUPLICATE_BACK_PAIR_TIMEOUT_MS);
    }

    private boolean shouldSuppressDuplicateBack(Object controller, int action) {
        long now = SystemClock.uptimeMillis();
        LegacyBackAttempt attempt;
        String outcome = null;
        boolean suppress = false;
        synchronized (legacyBackGuardLock) {
            attempt = legacyBackAttempt;
            if (legacyBackGuardPhase == BACK_GUARD_IDLE || attempt == null) {
                return false;
            }
            if (now > legacyBackGuardDeadlineUptime) {
                outcome = "expired";
                resetLegacyBackGuardLocked();
            } else if (legacyBackGuardPhase == BACK_GUARD_WAIT_MERGE) {
                return false;
            } else if (attempt.controller != controller) {
                outcome = "controllerMismatch";
                resetLegacyBackGuardLocked();
            } else if (legacyBackGuardPhase == BACK_GUARD_EXPECT_DOWN) {
                if (action == KEY_ACTION_DOWN) {
                    legacyBackGuardPhase = BACK_GUARD_EXPECT_UP;
                    suppressedBackDownUptime = now;
                    suppressedBackDownThread = Thread.currentThread();
                    legacyBackGuardDeadlineUptime = now + DUPLICATE_BACK_UP_INTERVAL_MS;
                    suppress = true;
                    outcome = "down";
                } else {
                    outcome = "expectedDownGot" + action;
                    resetLegacyBackGuardLocked();
                }
            } else if (legacyBackGuardPhase == BACK_GUARD_EXPECT_UP) {
                long interval = now - suppressedBackDownUptime;
                if (action == KEY_ACTION_UP
                        && suppressedBackDownThread == Thread.currentThread()
                        && interval >= 0L
                        && interval <= DUPLICATE_BACK_UP_INTERVAL_MS) {
                    suppress = true;
                    outcome = "pair";
                    resetLegacyBackGuardLocked();
                } else {
                    outcome = "invalidUp(action=" + action
                            + ", sameThread="
                            + (suppressedBackDownThread == Thread.currentThread())
                            + ", intervalMs=" + interval + ")";
                    resetLegacyBackGuardLocked();
                }
            }
        }
        if ("down".equals(outcome)) {
            scheduleLegacyBackGuardExpiry(attempt, DUPLICATE_BACK_UP_INTERVAL_MS);
        } else if ("pair".equals(outcome)) {
            log(Log.INFO, TAG, "Consumed one correlated duplicate BACK pair"
                    + ", attempt=" + attempt.id);
        } else if (outcome != null) {
            log(Log.WARN, TAG, "Released Xiaomi duplicate BACK guard"
                    + ", attempt=" + attempt.id
                    + ", reason=" + outcome);
        }
        return suppress;
    }

    private void scheduleLegacyBackGuardExpiry(LegacyBackAttempt attempt, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> expireLegacyBackGuard(attempt), Math.max(1L, delayMs));
    }

    private void expireLegacyBackGuard(LegacyBackAttempt expectedAttempt) {
        int phase;
        synchronized (legacyBackGuardLock) {
            if (legacyBackAttempt != expectedAttempt
                    || SystemClock.uptimeMillis() < legacyBackGuardDeadlineUptime) {
                return;
            }
            phase = legacyBackGuardPhase;
            resetLegacyBackGuardLocked();
        }
        log(Log.INFO, TAG, "Expired Xiaomi duplicate BACK guard"
                + ", attempt=" + expectedAttempt.id
                + ", phase=" + phase);
    }

    private void clearLegacyBackGuard(String reason) {
        LegacyBackAttempt attempt;
        int phase;
        synchronized (legacyBackGuardLock) {
            attempt = legacyBackAttempt;
            phase = legacyBackGuardPhase;
            resetLegacyBackGuardLocked();
        }
        if (phase != BACK_GUARD_IDLE && attempt != null) {
            log(Log.INFO, TAG, "Cleared Xiaomi duplicate BACK guard"
                    + ", attempt=" + attempt.id
                    + ", phase=" + phase
                    + ", reason=" + reason);
        }
    }

    private void resetLegacyBackGuardLocked() {
        legacyBackAttempt = null;
        legacyBackGuardPhase = BACK_GUARD_IDLE;
        legacyBackGuardDeadlineUptime = 0L;
        suppressedBackDownUptime = 0L;
        suppressedBackDownThread = null;
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
        EdgeWidthSnapshot widths = readEdgeWidthSnapshot(edgeBackGestureHandler,
                context.getResources().getDisplayMetrics().density);
        // AOSP publishes the configured sensitivities to application Insets. Window insets
        // extend only EdgeBackGestureHandler's own DOWN range; they do not widen providedInsets.
        int leftWidth = widths.leftSensitivity;
        int rightWidth = widths.rightSensitivity;

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
                if (!MODULE_MIUI_OVERVIEW_STATE_CHANGE.equals(action)
                        && !MODULE_MIUI_HOME_INPUT_ARBITER_QUERY.equals(action)) {
                    return;
                }
                int senderUid = getSentFromUid();
                String senderPackage = getSentFromPackage();
                if (!isTrustedMiuiHomeBroadcastSender(
                        receiverContext, senderUid, senderPackage)) {
                    log(Log.WARN, TAG, "Rejected untrusted Miui launcher-state broadcast"
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                    return;
                }
                if (MODULE_MIUI_HOME_INPUT_ARBITER_QUERY.equals(action)) {
                    publishSystemUiInputArbiterState(receiverContext,
                            systemUiInputArbiterMonitorCount.get() > 0,
                            "miuiHomeQuery");
                    return;
                }
                if (intent.getBooleanExtra(EXTRA_INPUT_ACCEPTED, false)) {
                    receiveMiuiHomeAcceptedInput(intent);
                }
                if (intent.hasExtra("drawer_visible")) {
                    miuiDrawerVisible = intent.getBooleanExtra("drawer_visible", false);
                    log(Log.INFO, TAG, "MiuiHome drawer state changed"
                            + ", drawerVisible=" + miuiDrawerVisible
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                }
                if (intent.hasExtra(EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE)) {
                    long generation = intent.getLongExtra(
                            EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, 0L);
                    boolean available = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE, false);
                    if (generation == 0L
                            || generation < miuiLauncherOpenBreakGeneration) {
                        log(Log.WARN, TAG, "Ignored stale MiuiHome launcher OPEN break state"
                                + ", available=" + available
                                + ", generation=" + generation
                                + ", currentGeneration="
                                + miuiLauncherOpenBreakGeneration);
                    } else {
                        miuiLauncherOpenBreakGeneration = generation;
                        miuiLauncherOpenBreakAvailable = available;
                        log(Log.INFO, TAG, "MiuiHome launcher OPEN break state changed"
                                + ", available=" + available
                                + ", generation=" + generation
                                + ", uid=" + senderUid
                                + ", package=" + senderPackage);
                    }
                }
                String state = intent == null ? null : intent.getStringExtra("state");
                boolean overviewVisible;
                String source;
                if (intent.hasExtra("overview_visible")) {
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
            IntentFilter filter = new IntentFilter(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            filter.addAction(MODULE_MIUI_HOME_INPUT_ARBITER_QUERY);
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

    private void receiveMiuiHomeAcceptedInput(Intent intent) {
        long generation = intent.getLongExtra(EXTRA_INPUT_ARBITER_GENERATION, 0L);
        if (generation != systemUiInputArbiterGeneration
                || systemUiInputArbiterMonitorCount.get() <= 0) {
            log(Log.WARN, TAG, "Ignored stale MiuiHome accepted input token"
                    + ", tokenGeneration=" + generation
                    + ", currentGeneration=" + systemUiInputArbiterGeneration
                    + ", monitors=" + systemUiInputArbiterMonitorCount.get());
            return;
        }
        if (!intent.hasExtra(EXTRA_INPUT_EVENT_ID)) {
            log(Log.WARN, TAG, "Rejected MiuiHome accepted token without event id");
            return;
        }
        MiuiHomeAcceptedInputToken token = new MiuiHomeAcceptedInputToken(
                intent.getIntExtra(EXTRA_INPUT_EVENT_ID, 0),
                intent.getLongExtra(EXTRA_INPUT_DOWN_TIME, Long.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_DEVICE_ID, Integer.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_SOURCE, 0),
                intent.getIntExtra(EXTRA_INPUT_DISPLAY_ID, Integer.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_EDGE, -1), generation);
        if (token.downTime == Long.MIN_VALUE
                || token.deviceId == Integer.MIN_VALUE
                || token.displayId == Integer.MIN_VALUE
                || (token.edge != EDGE_LEFT && token.edge != EDGE_RIGHT)) {
            log(Log.WARN, TAG, "Rejected malformed MiuiHome accepted input token"
                    + ", eventId=" + token.eventId
                    + ", downTime=" + token.downTime
                    + ", deviceId=" + token.deviceId
                    + ", displayId=" + token.displayId
                    + ", edge=" + token.edge);
            return;
        }
        acceptedInputToken.set(token);
        boolean consumed = false;
        for (NativeBackInputMonitor monitor
                : new ArrayList<>(nativeInputMonitors.values())) {
            if (monitor.acceptMiuiHomeInput(token)) {
                consumed = true;
                break;
            }
        }
        if (consumed) {
            acceptedInputToken.compareAndSet(token, null);
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> acceptedInputToken.compareAndSet(token, null),
                    INPUT_ACCEPTED_TOKEN_TIMEOUT_MS);
        }
        log(Log.INFO, TAG, "Received MiuiHome accepted input token"
                + ", eventId=" + token.eventId
                + ", downTime=" + token.downTime
                + ", displayId=" + token.displayId
                + ", edge=" + token.edge
                + ", matchedPendingDown=" + consumed
                + ", generation=" + generation);
    }

    private boolean isTrustedMiuiHomeBroadcastSender(Context context, int uid,
                                                      String senderPackage) {
        if (context == null || uid == Process.INVALID_UID
                || !MIUI_HOME.equals(senderPackage)) {
            return false;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String packageName : packages) {
                    if (MIUI_HOME.equals(packageName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to validate launcher-state sender uid=" + uid,
                    throwable);
        }
        return false;
    }

    private synchronized void unregisterMiuiOverviewStateReceiver() {
        BroadcastReceiver receiver = miuiOverviewReceiver;
        Context receiverContext = miuiOverviewReceiverContext;
        miuiOverviewReceiver = null;
        acceptedInputToken.set(null);
        miuiOverviewReceiverContext = null;
        miuiLauncherOpenBreakAvailable = false;
        miuiLauncherOpenBreakGeneration = 0L;
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
            nativeInputMonitors.put(edgeBackGestureHandler, monitor);
            monitor.attach();
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

    private Object readField(Object target, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findCachedField(target.getClass(), fieldName);
        return field.get(target);
    }

    private Object readStaticField(Class<?> ownerClass, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findCachedField(ownerClass, fieldName);
        return field.get(null);
    }

    private Object readFieldOrNull(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return readField(target, fieldName);
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }

    private float readFloatFieldOrDefault(Object target, String fieldName,
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

    private int readIntFieldOrDefault(Object target, String fieldName,
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

    private EdgeWidthSnapshot readEdgeWidthSnapshot(Object edgeBackGestureHandler,
                                                    float density) {
        int fallbackWidth = Math.max(1, Math.round(EDGE_TOUCH_WIDTH_DP * density));
        int leftSensitivity = readIntFieldOrDefault(edgeBackGestureHandler,
                "mEdgeWidthLeft", fallbackWidth);
        int rightSensitivity = readIntFieldOrDefault(edgeBackGestureHandler,
                "mEdgeWidthRight", fallbackWidth);
        if (leftSensitivity <= 0) {
            leftSensitivity = fallbackWidth;
        }
        if (rightSensitivity <= 0) {
            rightSensitivity = fallbackWidth;
        }
        int leftInset = readIntFieldOrDefault(edgeBackGestureHandler, "mLeftInset", 0);
        int rightInset = readIntFieldOrDefault(edgeBackGestureHandler, "mRightInset", 0);
        return new EdgeWidthSnapshot(leftSensitivity, rightSensitivity,
                leftInset, rightInset);
    }

    private void writeField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findCachedField(target.getClass(), fieldName);
        field.set(target, value);
    }

    private Field findCachedField(Class<?> ownerClass, String fieldName)
            throws NoSuchFieldException {
        ReflectionKey key = new ReflectionKey(ownerClass, "field:" + fieldName);
        Field cached = reflectedFields.get(key);
        if (cached != null) {
            return cached;
        }
        if (missingReflectedMembers.contains(key)) {
            throw new NoSuchFieldException(ownerClass.getName() + "." + fieldName);
        }
        Class<?> current = ownerClass;
        while (current != null) {
            try {
                Field resolved = current.getDeclaredField(fieldName);
                resolved.setAccessible(true);
                Field raced = reflectedFields.putIfAbsent(key, resolved);
                return raced == null ? resolved : raced;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        missingReflectedMembers.add(key);
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
                backAnimationImpl, (InputMonitor) monitor, (InputChannel) inputChannel,
                displayId);
    }

    private final class NativeBackInputMonitor extends InputEventReceiver {
        private final Context context;
        private final Object edgeBackGestureHandler;
        private final InputMonitor inputMonitor;
        private final SystemUiBackGestureDriver driver;
        private final int displayId;
        private Object backAnimationImpl;
        private boolean gestureCandidate;
        private boolean launcherOpenBreakCandidate;
        private long launcherOpenBreakGenerationCandidate;
        private boolean launcherDrawerCandidate;
        private boolean miuiHomeInputAccepted;
        private boolean pilfered;
        private boolean arbiterAttached;
        private int activeEdge;
        private int downEventId;
        private int downDeviceId = Integer.MIN_VALUE;
        private int downSource;
        private int downDisplayId = Integer.MIN_VALUE;
        private float downX;
        private float downY;
        private long downTime = Long.MIN_VALUE;
        private MotionEvent pendingDownEvent;
        private MotionEvent pendingMotionEvent;

        private NativeBackInputMonitor(Context context, Object edgeBackGestureHandler,
                                       Object controller, Object backAnimationImpl, InputMonitor inputMonitor,
                                       InputChannel inputChannel, int displayId) {
            super(inputChannel, Looper.getMainLooper());
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.backAnimationImpl = backAnimationImpl;
            this.inputMonitor = inputMonitor;
            this.displayId = displayId;
            this.driver = new SystemUiBackGestureDriver(context, edgeBackGestureHandler,
                    controller, backAnimationImpl);
        }

        void attach() {
            if (!arbiterAttached) {
                arbiterAttached = true;
                onSystemUiInputMonitorAttached(context);
            }
            log(Log.INFO, TAG, "Native SystemUI back input receiver attached"
                    + ", inputModel=miuihome-accepted-token");
        }

        void detach() {
            resetCandidate();
            driver.detach();
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
            if (arbiterAttached) {
                arbiterAttached = false;
                onSystemUiInputMonitorDetached(context);
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
            downTime = event.getDownTime();
            downDeviceId = event.getDeviceId();
            downSource = event.getSource();
            try {
                downEventId = readMotionEventId(event);
                downDisplayId = readMotionEventDisplayId(event);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Cannot identify pending MiuiHome DOWN", throwable);
                resetCandidate();
                return false;
            }
            pendingDownEvent = MotionEvent.obtain(event);
            log(Log.INFO, TAG, "Native SystemUI back candidate awaiting MiuiHome acceptance"
                    + ", eventId=" + downEventId
                    + ", downTime=" + downTime
                    + ", launcherOpenBreak=" + launcherOpenBreakCandidate
                    + ", launcherOpenBreakGeneration="
                    + launcherOpenBreakGenerationCandidate
                    + ", launcherDrawer=" + launcherDrawerCandidate
                    + ", inputModel=miuihome-accepted-token"
                    + ", displayId=" + displayId
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            MiuiHomeAcceptedInputToken token = acceptedInputToken.get();
            if (token != null && matchesMiuiHomeInput(token)
                    && acceptedInputToken.compareAndSet(token, null)) {
                acceptMiuiHomeInput(token);
            }
            return false;
        }

        private boolean onNativeMove(MotionEvent event) {
            if (!gestureCandidate) {
                return false;
            }
            if (!miuiHomeInputAccepted) {
                replacePendingMotionEvent(event);
                MiuiHomeAcceptedInputToken token = acceptedInputToken.get();
                if (token != null && matchesMiuiHomeInput(token)
                        && acceptedInputToken.compareAndSet(token, null)) {
                    acceptMiuiHomeInput(token);
                }
                return pilfered;
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
                if (!driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherDrawerCandidate)) {
                    resetCandidate();
                    return false;
                }
                return false;
            }
            if (!pilfered && driver.isGestureSuppressed()) {
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherDrawerCandidate);
                return false;
            }
            if (!pilfered && distance > dp(PILFER_THRESHOLD_DP)) {
                pilferPointers(distance);
                if (!pilfered) {
                    cancelNativeCandidate(event, "failed to pilfer outward gesture");
                    return false;
                }
            }
            if (!driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                    launcherOpenBreakGenerationCandidate, launcherDrawerCandidate)) {
                resetCandidate();
                return false;
            }
            return pilfered;
        }

        boolean acceptMiuiHomeInput(MiuiHomeAcceptedInputToken token) {
            if (!matchesMiuiHomeInput(token)) {
                return false;
            }
            miuiHomeInputAccepted = true;
            MotionEvent down = pendingDownEvent;
            pendingDownEvent = null;
            try {
                if (down == null || !driver.handleTouch(down, activeEdge,
                        launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate,
                        launcherDrawerCandidate)) {
                    log(Log.INFO, TAG, "MiuiHome accepted DOWN but SystemUI path declined"
                            + ", eventId=" + token.eventId
                            + ", edge=" + token.edge);
                    resetCandidate();
                    return true;
                }
                log(Log.INFO, TAG, "Matched MiuiHome accepted input token"
                        + ", eventId=" + token.eventId
                        + ", downTime=" + token.downTime
                        + ", displayId=" + token.displayId
                        + ", edge=" + token.edge
                        + ", generation=" + token.generation);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to start accepted MiuiHome input", throwable);
                resetCandidate();
                return true;
            } finally {
                if (down != null) {
                    down.recycle();
                }
            }
            MotionEvent pending = pendingMotionEvent;
            pendingMotionEvent = null;
            if (pending != null) {
                try {
                    onNativeMove(pending);
                } finally {
                    pending.recycle();
                }
            }
            return true;
        }

        private boolean matchesMiuiHomeInput(MiuiHomeAcceptedInputToken token) {
            return token != null
                    && !token.isExpired()
                    && gestureCandidate
                    && !miuiHomeInputAccepted
                    && token.generation == systemUiInputArbiterGeneration
                    && token.eventId == downEventId
                    && token.downTime == downTime
                    && token.deviceId == downDeviceId
                    && token.source == downSource
                    && token.displayId == downDisplayId
                    && token.displayId == displayId
                    && token.edge == activeEdge;
        }

        private void replacePendingMotionEvent(MotionEvent event) {
            MotionEvent old = pendingMotionEvent;
            pendingMotionEvent = MotionEvent.obtain(event);
            if (old != null) {
                old.recycle();
            }
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
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherDrawerCandidate);
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
            if (!miuiHomeInputAccepted) {
                resetCandidate();
                return false;
            }
            if (driver.isRecentsVisualOnlyGesture()) {
                // Let BackPanelController finish its local animation. No Shell navigation is
                // active, and the monitor must not claim this input stream.
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherDrawerCandidate);
            } else if (allowTrigger && pilfered) {
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherDrawerCandidate);
            } else {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherDrawerCandidate);
                cancel.recycle();
            }
            boolean handled = pilfered;
            resetCandidate();
            return handled;
        }

        private int edgeForDown(MotionEvent event) {
            float x = event.getRawX();
            float displayWidth = currentDisplayWidth();
            EdgeWidthSnapshot widths = edgeWidths();
            if (x < widths.leftTouchWidth) {
                return EDGE_LEFT;
            }
            if (x >= displayWidth - widths.rightTouchWidth) {
                return EDGE_RIGHT;
            }
            return -1;
        }

        private EdgeWidthSnapshot edgeWidths() {
            return readEdgeWidthSnapshot(edgeBackGestureHandler,
                    context.getResources().getDisplayMetrics().density);
        }

        private int edgeTouchWidth(int edge) {
            return edgeWidths().touchWidth(edge);
        }

        private float currentDisplayWidth() {
            try {
                return Math.max(1.0f, context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getBounds().width());
            } catch (Throwable ignored) {
                return Math.max(1.0f,
                        context.getResources().getDisplayMetrics().widthPixels);
            }
        }

        private boolean canStartBackGesture(MotionEvent event, int edge) {
            if (event.getPointerCount() != 1) {
                return false;
            }
            if (!isNativeBackInputActive()) {
                return false;
            }
            String topPackage = findTopActivityPackage();
            if (topPackage == null) {
                log(Log.WARN, TAG, "Rejected native back because top package is unknown"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
                return false;
            }
            boolean launcherTop = MIUI_HOME.equals(topPackage);
            boolean launcherOpenBreak = displayId == 0
                    && !miuiOverviewVisible
                    && miuiLauncherOpenBreakAvailable
                    && miuiLauncherOpenBreakGeneration != 0L
                    && launcherOpenBreakCommandsInFlight.get() == 0;
            boolean launcherDrawer = launcherTop
                    && miuiDrawerVisible
                    && !miuiOverviewVisible
                    && !launcherOpenBreak;
            if (launcherTop && !miuiOverviewVisible
                    && !launcherOpenBreak && !launcherDrawer) {
                log(Log.INFO, TAG, "Ignored native back on launcher Home"
                        + ", overviewVisible=false"
                        + ", launcherOpenBreakAvailable="
                        + miuiLauncherOpenBreakAvailable
                        + ", commandsInFlight="
                        + launcherOpenBreakCommandsInFlight.get()
                        + ", generation=" + miuiLauncherOpenBreakGeneration
                        + ", displayId=" + displayId);
                return false;
            }
            if (launcherDrawer) {
                log(Log.INFO, TAG, "Accepted native back in MiuiHome app drawer"
                        + ", drawerVisible=true"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherOpenBreak) {
                log(Log.INFO, TAG, "Accepted native back during launcher OPEN animation"
                        + ", launcherOpenBreakAvailable=true"
                        + ", launcherTop=" + launcherTop
                        + ", generation=" + miuiLauncherOpenBreakGeneration
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
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
            if (isInBottomGestureRegion(event)) {
                log(Log.INFO, TAG, "Ignored native back inside bottom gesture region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
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
            launcherOpenBreakCandidate = launcherOpenBreak;
            launcherOpenBreakGenerationCandidate = launcherOpenBreak
                    ? miuiLauncherOpenBreakGeneration : 0L;
            launcherDrawerCandidate = launcherDrawer;
            // Geometry, attachment, touchability, and redirect acceptance are proved later
            // by the matching token emitted only from MiuiHome's accepted processor boundary.
            return true;
        }

        private boolean isNativeBackInputActive() {
            try {
                if (!Boolean.TRUE.equals(readField(edgeBackGestureHandler, "mIsAttached"))
                        || !Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mInGestureNavMode"))
                        || !Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mIsBackGestureAllowed"))
                        || Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mUsingThreeButtonNav"))
                        || Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mDisabledForQuickstep"))) {
                    return false;
                }
                try {
                    // Some SystemUI branches expose this derived lifecycle state. Xiaomi's
                    // current build does not, so absence is compatible while a present false
                    // value must disable the driver.
                    Field enabledField = findCachedField(
                            edgeBackGestureHandler.getClass(), "mIsEnabled");
                    if (!Boolean.TRUE.equals(enabledField.get(edgeBackGestureHandler))) {
                        return false;
                    }
                } catch (NoSuchFieldException ignored) {
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean isNavBarShownTransiently() {
            try {
                return Boolean.TRUE.equals(readField(edgeBackGestureHandler,
                        "mIsNavBarShownTransiently"));
            } catch (Throwable ignored) {
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
                if (!(value instanceof Boolean) || Boolean.TRUE.equals(value)) {
                    return false;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect gesture-blocking activity state",
                        throwable);
                return false;
            }
            return true;
        }

        private String findTopActivityPackage() {
            try {
                ActivityManager activityManager = context.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(20);
                if (tasks == null || tasks.isEmpty()) {
                    return null;
                }
                for (ActivityManager.RunningTaskInfo task : tasks) {
                    int taskDisplayId = readRunningTaskDisplayId(task);
                    if (taskDisplayId != displayId || task.topActivity == null) {
                        continue;
                    }
                    ComponentName top = task.topActivity;
                    return top.getPackageName();
                }
                return null;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect top activity for native back", throwable);
                return null;
            }
        }

        private int readRunningTaskDisplayId(ActivityManager.RunningTaskInfo task) {
            try {
                Object value = readField(task, "displayId");
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            } catch (Throwable throwable) {
                if (displayId != 0) {
                    log(Log.WARN, TAG, "Failed to inspect running-task display"
                            + ", monitorDisplayId=" + displayId, throwable);
                }
            }
            // The SDK stub hides TaskInfo.displayId. Default-display SystemUI can safely use
            // the historical default when reflection is unavailable; secondary displays fail
            // closed instead of borrowing another display's launcher state.
            return displayId == 0 ? 0 : Integer.MIN_VALUE;
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
                return true;
            }
        }

        private boolean isInExcludedRegion(MotionEvent event, int edge) {
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            try {
                Object region = readField(edgeBackGestureHandler, "mExcludeRegion");
                if (!(region instanceof Region) || ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect application exclusion region",
                        throwable);
                return true;
            }
            try {
                Object region = readField(edgeBackGestureHandler, "mDesktopModeExcludeRegion");
                if (!(region instanceof Region) || ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect desktop exclusion region", throwable);
                return true;
            }
            try {
                Object bounds = readField(edgeBackGestureHandler, "mNavBarOverlayExcludedBounds");
                if (!(bounds instanceof Rect) || ((Rect) bounds).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect navigation-overlay exclusion bounds",
                        throwable);
                return true;
            }
            Boolean inPip = readPipState();
            if (inPip == null) {
                return true;
            }
            if (inPip.booleanValue()) {
                try {
                    Object bounds = readField(edgeBackGestureHandler, "mPipExcludedBounds");
                    if (!(bounds instanceof Rect) || ((Rect) bounds).contains(x, y)) {
                        return true;
                    }
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "Failed to inspect PiP exclusion bounds", throwable);
                    return true;
                }
            }
            return false;
        }

        private Boolean readPipState() {
            try {
                Object value = readField(edgeBackGestureHandler, "mIsInPip");
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect PiP state", throwable);
                return null;
            }
        }

        private boolean isInMiuiSidebarRegion(MotionEvent event) {
            String encoded;
            try {
                encoded = Settings.Secure.getString(context.getContentResolver(),
                        MIUI_SIDEBAR_BOUNDS);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to read MIUI sidebar bounds", throwable);
                return true;
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
                return true;
            }
            return false;
        }

        private void resetCandidate() {
            gestureCandidate = false;
            MotionEvent oldDown = pendingDownEvent;
            MotionEvent oldMotion = pendingMotionEvent;
            pendingDownEvent = null;
            pendingMotionEvent = null;
            if (oldDown != null) {
                oldDown.recycle();
            }
            if (oldMotion != null) {
                oldMotion.recycle();
            }
            launcherOpenBreakCandidate = false;
            launcherOpenBreakGenerationCandidate = 0L;
            launcherDrawerCandidate = false;
            miuiHomeInputAccepted = false;
            pilfered = false;
            activeEdge = EDGE_LEFT;
            downEventId = 0;
            downDeviceId = Integer.MIN_VALUE;
            downSource = 0;
            downDisplayId = Integer.MIN_VALUE;
            downX = 0.0f;
            downY = 0.0f;
            downTime = Long.MIN_VALUE;
        }

        private boolean areSystemBarsHidden() {
            try {
                WindowInsets insets = context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getWindowInsets();
                return insets == null
                        || (!insets.isVisible(WindowInsets.Type.statusBars())
                        && !insets.isVisible(WindowInsets.Type.navigationBars()));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect foreground system bars", throwable);
                return true;
            }
        }

        private float dp(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }

        private float bottomGestureHeight() {
            float height = readFloatFieldOrDefault(edgeBackGestureHandler,
                    "mBottomGestureHeight", Float.NaN);
            return Float.isNaN(height) || Float.isInfinite(height)
                    ? Float.POSITIVE_INFINITY : Math.max(0.0f, height);
        }

        private boolean isInBottomGestureRegion(MotionEvent event) {
            float bottomGestureHeight = bottomGestureHeight();
            if (bottomGestureHeight <= 0.0f) {
                return false;
            }
            try {
                Rect bounds = context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getBounds();
                return event.getRawY() >= bounds.bottom - bottomGestureHeight;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect bottom gesture region", throwable);
                return true;
            }
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
        private boolean shellGestureStartDeferred;
        private boolean gestureSuppressed;
        private boolean legacyInterruptGesture;
        private Object legacyRunningOpenInfo;
        private boolean launcherOpenBreakGesture;
        private long launcherOpenBreakGeneration;
        private long launcherOpenBreakAttemptId;
        private long pendingLauncherOpenBreakGeneration;
        private long pendingLauncherOpenBreakAttemptId;
        private boolean launcherOverviewGesture;
        private boolean launcherDrawerGesture;
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

        private boolean handleTouch(MotionEvent event, int edge,
                                    boolean launcherOpenBreakCandidate,
                                    long launcherOpenBreakGenerationCandidate,
                                    boolean launcherDrawerCandidate) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return onDown(event, edge, launcherOpenBreakCandidate,
                                launcherOpenBreakGenerationCandidate,
                                launcherDrawerCandidate);
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

        private boolean isGestureSuppressed() {
            return gestureActive && gestureSuppressed;
        }

        private boolean onDown(MotionEvent event, int edge,
                               boolean launcherOpenBreakCandidate,
                               long launcherOpenBreakGenerationCandidate,
                               boolean launcherDrawerCandidate) throws Exception {
            clearLegacyBackGuard("newPhysicalGesture");
            gestureActive = true;
            shellGestureStarted = false;
            shellGestureStartDeferred = false;
            gestureSuppressed = false;
            legacyInterruptGesture = false;
            legacyRunningOpenInfo = null;
            launcherOpenBreakGesture = launcherOpenBreakCandidate;
            launcherOpenBreakGeneration = launcherOpenBreakCandidate
                    ? launcherOpenBreakGenerationCandidate : 0L;
            launcherOpenBreakAttemptId = launcherOpenBreakCandidate
                    ? launcherOpenBreakAttemptIds.incrementAndGet() : 0L;
            launcherOverviewGesture = miuiOverviewVisible;
            launcherDrawerGesture = launcherDrawerCandidate;
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
            if (launcherOpenBreakGesture) {
                dispatchToEdgePlugin(event, activeEdge);
                log(Log.INFO, TAG, "SystemUI-owned launcher OPEN break candidate"
                        + ", useMiuiHomeNativeController=true"
                        + ", shellGestureStarted=false"
                        + ", generation=" + launcherOpenBreakGeneration
                        + ", attempt=" + launcherOpenBreakAttemptId
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                return true;
            }
            if (launcherOverviewGesture || launcherDrawerGesture) {
                log(Log.INFO, TAG, (launcherOverviewGesture
                        ? "SystemUI-owned Recents back gesture candidate"
                        : "SystemUI-owned MiuiHome drawer back gesture candidate")
                        + ", useShellCallback=true"
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                // Launcher Home, Recents and the drawer share one Activity. Resolve the
                // callback on DOWN while the real launcher stream is still unpilfered.
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
                    log(Log.INFO, TAG, (launcherDrawerGesture
                            ? "Ignored MiuiHome drawer gesture without a callback target"
                            : "Ignored Recents edge gesture without a back navigation target")
                            + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                    return false;
                }
            } else {
                // MiuiHome GestureStub accepted the original DOWN and its legacy processor
                // was neutralized. Pilfering and Shell setup now share the intentional 8dp
                // boundary, with the authenticated token as the ownership proof.
                shellGestureStartDeferred = true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            log(Log.INFO, TAG, "SystemUI gesture driver candidate"
                    + ", shellStartDeferred=" + shellGestureStartDeferred
                    + ", inputModel=miuihome-accepted-token"
                    + ", launcherDrawer=" + launcherDrawerGesture
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
            float distance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            if (shellGestureStartDeferred && distance > dp(PILFER_THRESHOLD_DP)) {
                if (!isShellReadyForGesture()) {
                    cancelLocalGesture(event,
                            "Shell became busy before deferred start, state="
                                    + describeShellState());
                    return false;
                }
                shellGestureStartDeferred = false;
                if (!startShellGesture()) {
                    cancelLocalGesture(event,
                            "BackNavigationInfo unavailable at intent threshold");
                    return false;
                }
                log(Log.INFO, TAG, "Started in-app back path at 8dp intent threshold"
                        + ", shellGestureStarted=" + shellGestureStarted
                        + ", legacyInterrupt=" + legacyInterruptGesture
                        + ", edge=" + activeEdge
                        + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
            }
            if (!shellGestureStartDeferred
                    && !legacyInterruptGesture && !launcherOpenBreakGesture) {
                updateActiveTracker(event.getRawX(), event.getRawY());
            }
            if (!thresholdCrossed && distance > dp(PILFER_THRESHOLD_DP)) {
                crossIntentThreshold(distance);
            }
            if (thresholdCrossed && !legacyInterruptGesture
                    && !launcherOpenBreakGesture) {
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
            } else if (launcherOpenBreakGesture) {
                log(Log.INFO, TAG, "MiuiHome launcher OPEN break threshold crossed, distance="
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
            if (launcherOpenBreakGesture) {
                return finishLauncherOpenBreakGesture(event, allowTrigger);
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
                    + ", drawerShellCallback=" + launcherDrawerGesture
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
                dispatchLegacyInterruptBack();
            }
            log(Log.INFO, TAG, "Finished MIUI in-app interrupt gesture"
                    + ", trigger=" + trigger + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        void detach() {
            if (pendingLauncherOpenBreakAttemptId != 0L) {
                decrementLauncherOpenBreakCommandsInFlight();
            }
            pendingLauncherOpenBreakGeneration = 0L;
            pendingLauncherOpenBreakAttemptId = 0L;
            clearLocalGestureState();
        }

        private boolean finishLauncherOpenBreakGesture(MotionEvent event,
                                                       boolean allowTrigger)
                throws Exception {
            dispatchToEdgePlugin(event, activeEdge);
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean trigger = allowTrigger
                    && thresholdCrossed
                    && releaseDistance > dp(TRIGGER_THRESHOLD_DP);
            updateTriggerBack(trigger);
            // This gesture never starts a Shell tracker. Clear any trigger value posted by
            // BackPanelController after its release event, then hand a committed gesture to
            // MiuiHome's own BackGestureBreakController.
            clearControllerTriggerAfterVisualOnlyGesture();
            if (trigger) {
                long generation = launcherOpenBreakGeneration;
                long attemptId = launcherOpenBreakAttemptId;
                pendingLauncherOpenBreakGeneration = generation;
                pendingLauncherOpenBreakAttemptId = attemptId;
                launcherOpenBreakCommandsInFlight.incrementAndGet();
                try {
                    sendAuthenticatedMiuiHomeOpenBreakCommand(
                            context, generation, attemptId, this);
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "Failed to send launcher OPEN break command",
                            throwable);
                    onLauncherOpenBreakCommandResult(generation, attemptId,
                            LAUNCHER_OPEN_BREAK_RESULT_REJECTED, "sendException");
                }
            }
            log(Log.INFO, TAG, "Finished MiuiHome launcher OPEN break gesture"
                    + ", trigger=" + trigger
                    + ", generation=" + launcherOpenBreakGeneration
                    + ", attempt=" + launcherOpenBreakAttemptId
                    + ", releaseDistance=" + releaseDistance
                    + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        private void onLauncherOpenBreakCommandResult(long generation, long attemptId,
                                                       int resultCode, String reason) {
            if (pendingLauncherOpenBreakGeneration != generation
                    || pendingLauncherOpenBreakAttemptId != attemptId) {
                log(Log.WARN, TAG, "Ignored stale launcher OPEN break command result"
                        + ", generation=" + generation
                        + ", attempt=" + attemptId
                        + ", pendingGeneration=" + pendingLauncherOpenBreakGeneration
                        + ", pendingAttempt=" + pendingLauncherOpenBreakAttemptId
                        + ", resultCode=" + resultCode
                        + ", reason=" + reason);
                return;
            }
            decrementLauncherOpenBreakCommandsInFlight();
            pendingLauncherOpenBreakGeneration = 0L;
            pendingLauncherOpenBreakAttemptId = 0L;
            if (resultCode == LAUNCHER_OPEN_BREAK_RESULT_ACCEPTED) {
                log(Log.INFO, TAG, "MiuiHome accepted launcher OPEN break command"
                        + ", generation=" + generation
                        + ", attempt=" + attemptId);
                return;
            }
            log(Log.WARN, TAG, "Falling back to one ordinary BACK after launcher OPEN "
                    + "break rejection"
                    + ", generation=" + generation
                    + ", attempt=" + attemptId
                    + ", resultCode=" + resultCode
                    + ", reason=" + reason);
            injectLegacyBackKey();
        }

        private void decrementLauncherOpenBreakCommandsInFlight() {
            int remaining = launcherOpenBreakCommandsInFlight.decrementAndGet();
            if (remaining < 0) {
                launcherOpenBreakCommandsInFlight.set(0);
                log(Log.WARN, TAG, "Corrected launcher OPEN break in-flight underflow");
            }
        }

        private boolean startShellGesture() throws Exception {
            // A running Xiaomi OPEN transition is the native interruption source. Prefer it
            // even when system_server can already return a valid predictive-back navigation;
            // otherwise Shell starts a new cross-activity animation and misses reverse().
            boolean launcherCallbackOnly = launcherOverviewGesture || launcherDrawerGesture;
            OpenTransitionSnapshot runningOpen = launcherCallbackOnly
                    ? null : findReversibleRunningOpenTransition();
            if (runningOpen != null) {
                legacyInterruptGesture = true;
                legacyRunningOpenInfo = runningOpen.transitionInfo;
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
                runningOpen = launcherCallbackOnly
                        ? null : findReversibleRunningOpenTransition();
                if (receivedNull && runningOpen != null) {
                    legacyInterruptGesture = true;
                    legacyRunningOpenInfo = runningOpen.transitionInfo;
                    log(Log.INFO, TAG, "Using SystemUI-owned legacy BACK for possible "
                            + "MIUI in-app transition interruption");
                    return true;
                }
                return false;
            }
            if (launcherCallbackOnly) {
                int navigationType = info instanceof BackNavigationInfo
                        ? ((BackNavigationInfo) info).getType() : -1;
                if (navigationType != TYPE_CALLBACK) {
                    log(Log.WARN, TAG, (launcherOverviewGesture
                            ? "Rejected stale Recents Shell target"
                            : "Rejected non-callback MiuiHome drawer Shell target")
                            + ", type=" + navigationType
                            + ", info=" + shortObject(info));
                    cleanupRejectedShellGesture();
                    if (launcherOverviewGesture) {
                        recentsVisualOnlyGesture = true;
                    }
                    return false;
                }
                log(Log.INFO, TAG, (launcherOverviewGesture
                        ? "Resolved Launcher Recents Shell callback, type="
                        : "Resolved MiuiHome drawer Shell callback, type=")
                        + navigationType);
            }
            shellGestureStarted = true;
            log(Log.INFO, TAG, "SystemUI gesture driver onGestureStarted"
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        private OpenTransitionSnapshot findReversibleRunningOpenTransition() {
            for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
                if (snapshot.state.get() == OPEN_SNAPSHOT_ACTIVE) {
                    log(Log.INFO, TAG, "Detected reversible running OPEN transition"
                            + ", animatorCount=" + snapshot.animatorCount
                            + ", info=" + shortObject(snapshot.transitionInfo));
                    return snapshot;
                }
            }
            return null;
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
            shellGestureStartDeferred = false;
            gestureSuppressed = false;
            legacyInterruptGesture = false;
            legacyRunningOpenInfo = null;
            launcherOpenBreakGesture = false;
            launcherOpenBreakGeneration = 0L;
            launcherOpenBreakAttemptId = 0L;
            launcherOverviewGesture = false;
            launcherDrawerGesture = false;
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
                if (!legacyInterruptGesture && !launcherOpenBreakGesture) {
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

        private void dispatchLegacyInterruptBack() {
            if (legacyRunningOpenInfo == null) {
                log(Log.WARN, TAG, "Missing correlated OPEN info for legacy interruption; "
                        + "using ordinary BACK without duplicate guard");
                dispatchRealBack();
                return;
            }
            LegacyBackAttempt attempt = armLegacyBackGuard(controller,
                    legacyRunningOpenInfo);
            Object previousMarker = moduleLegacyBackInjection.get();
            moduleLegacyBackInjection.set(attempt);
            try {
                dispatchRealBack();
            } finally {
                if (previousMarker == null) {
                    moduleLegacyBackInjection.remove();
                } else {
                    moduleLegacyBackInjection.set(previousMarker);
                }
            }
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
            Object previousMarker = moduleLegacyBackInjection.get();
            if (previousMarker == null) {
                moduleLegacyBackInjection.set(this);
            }
            try {
                try {
                    invokeAnyMethod(controller, "injectBackKey", new Object[0]);
                    log(Log.INFO, TAG, "Injected legacy back key via controller");
                    return;
                } catch (Throwable throwable) {
                    if (!(throwable instanceof NoSuchMethodException)) {
                        log(Log.WARN, TAG, "Optional injectBackKey failed; using send pair",
                                throwable);
                    }
                }
                boolean downSent = false;
                try {
                    invokeAnyMethod(controller, "sendBackEvent",
                            new Object[]{Integer.valueOf(KEY_ACTION_DOWN)});
                    downSent = true;
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "Failed to inject legacy BACK down", throwable);
                } finally {
                    if (downSent) {
                        try {
                            invokeAnyMethod(controller, "sendBackEvent",
                                    new Object[]{Integer.valueOf(KEY_ACTION_UP)});
                            log(Log.INFO, TAG, "Injected legacy back key via sendBackEvent");
                        } catch (Throwable throwable) {
                            log(Log.ERROR, TAG, "Failed to inject legacy BACK up", throwable);
                        }
                    }
                }
            } finally {
                if (previousMarker == null) {
                    moduleLegacyBackInjection.remove();
                } else {
                    moduleLegacyBackInjection.set(previousMarker);
                }
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

    private Object invokeMethod(Object target, String methodName,
                                Class<?>[] parameterTypes, Object[] args) throws Exception {
        Class<?> owner = target.getClass();
        ReflectionKey key = new ReflectionKey(owner,
                exactMethodSignature(methodName, parameterTypes));
        Method method = reflectedExactMethods.get(key);
        if (method == null) {
            if (missingReflectedMembers.contains(key)) {
                throw new NoSuchMethodException(owner.getName() + "." + methodName);
            }
            try {
                Method resolved = owner.getMethod(methodName, parameterTypes);
                resolved.setAccessible(true);
                Method raced = reflectedExactMethods.putIfAbsent(key, resolved);
                method = raced == null ? resolved : raced;
            } catch (NoSuchMethodException exception) {
                missingReflectedMembers.add(key);
                throw exception;
            }
        }
        return method.invoke(target, args);
    }

    private Object invokeAnyMethod(Object target, String methodName, Object[] args)
            throws Exception {
        Class<?> owner = target.getClass();
        ReflectionKey key = new ReflectionKey(owner,
                "any:" + methodName + "/" + args.length);
        Method best = reflectedAnyMethods.get(key);
        if (best == null && missingReflectedMembers.contains(key)) {
            throw new NoSuchMethodException(owner.getName() + "." + methodName);
        }
        if (best == null) {
            Method resolved = findAnyMethod(owner, methodName, args.length);
            if (resolved != null) {
                resolved.setAccessible(true);
                Method raced = reflectedAnyMethods.putIfAbsent(key, resolved);
                best = raced == null ? resolved : raced;
            }
        }
        if (best == null) {
            missingReflectedMembers.add(key);
            throw new NoSuchMethodException(owner.getName() + "." + methodName);
        }
        return best.invoke(target, args);
    }

    private static String exactMethodSignature(String methodName, Class<?>[] parameterTypes) {
        StringBuilder signature = new StringBuilder("exact:").append(methodName).append('(');
        for (Class<?> parameterType : parameterTypes) {
            signature.append(parameterType == null ? "null" : parameterType.getName())
                    .append(';');
        }
        return signature.append(')').toString();
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
