package dev.codex.miuibackgesturehook.hooks.hotreload;

import dev.codex.miuibackgesturehook.hooks.systemserver.SystemServerHookRuntime;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackProgressAnimator;
import android.window.BackTouchTracker;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public abstract class HotReloadHookRuntime extends SystemServerHookRuntime {


    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        for (NativeBackInputMonitor monitor
                : new ArrayList<>(nativeInputMonitors.values())) {
            if (monitor.blocksHotReload()) {
                log(Log.WARN, TAG,
                        "Deferred hot reload while a fixed Shell gesture session is active"
                                + ", process=" + processName
                                + ", state="
                                + monitor.describeActiveShellSession());
                return false;
            }
        }
        MiuiHomeReturnHomeController activeReturnHomeController =
                miuiHomeReturnHomeController;
        if (activeReturnHomeController != null
                && activeReturnHomeController.blocksControllerReplacement()) {
            log(Log.WARN, TAG,
                    "Deferred hot reload while Xiaomi owns predictive return-home"
                            + ", process=" + processName
                            + ", state="
                            + activeReturnHomeController.describeUnifiedOwner());
            return false;
        }
        log(Log.INFO, TAG, "Hot reloading, build=" + BUILD_MARK
                + ", process=" + processName
                + ", hooks=" + hookHandles.size());
        boolean savedMiuiOverviewVisible = miuiOverviewVisible;
        boolean savedMiuiDrawerVisible = miuiDrawerVisible;
        boolean savedMiuiLauncherEditing = miuiLauncherEditing;
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
        miuiHomeLocalHandoffToken.set(null);
        invalidateMiuiHomeLauncherOpenSnapshot(null, "hotReload");
        IBinder savedMiuiHomeReturnHomeBinder =
                detachMiuiHomeReturnHome("hotReload", true);
        miuiHomePendingNativeGeometry.remove();
        returnHomeFinishTransferCandidate.remove();
        backCommitCompositionHookReady = false;
        backFinishOpenAtomicHookReady = false;
        backFinishOpenCallerDeoptimized = false;
        acceptingOpenSnapshots = false;
        acceptingHeadlessNavBarLifecycle = false;
        synchronized (backInputLifecycleLock) {
            acceptingBackInputInstalls = false;
        }
        headlessNavBarLifecycleGeneration.incrementAndGet();
        miuiHomeOpenBreakCallbackEpoch.incrementAndGet();
        openSnapshotGeneration.incrementAndGet();
        invalidateAllOpenTransitionSnapshots("hotReload");
        clearLegacyBackGuard("hotReload");
        miuiLauncherOpenBreakAvailable = false;
        miuiLauncherOpenBreakGeneration = 0L;
        acceptedInputToken.set(null);
        miuiHomeAcceptedInputIdentity.set(null);
        systemUiReturnHomeCommitIdentity.set(null);
        unregisterMiuiOverviewStateReceiver();
        unregisterMiuiHomeOpenBreakCommandReceiver();
        unregisterMiuiHomeInputArbiterReceiver();
        Object[][] inputState = new Object[nativeInputMonitors.size()][2];
        int index = 0;
        for (Map.Entry<Object, NativeBackInputMonitor> entry
                : new ArrayList<>(nativeInputMonitors.entrySet())) {
            inputState[index][0] = entry.getKey();
            inputState[index][1] = entry.getValue().driver.backAnimationImpl;
            index++;
        }
        Object[][] savedHeadlessState =
                detachHeadlessNavBarLifecycleForHotReload();
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
                Boolean.valueOf(savedMiuiDrawerVisible),
                savedMiuiHomeReturnHomeBinder,
                savedHeadlessState,
                Boolean.valueOf(savedMiuiLauncherEditing)
        });
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        processName = param.getProcessName();
        int replaced = 0;
        Set<String> oldHookIds = new java.util.HashSet<>();
        boolean hadServerHook = false;
        ClassLoader hotReloadClassLoader = null;
        for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
            try {
                String oldHookId = oldHandle.getId();
                if (oldHookId != null) {
                    hadServerHook |= oldHookId.startsWith("server_")
                            || "predictive_opt_in_system_server".equals(oldHookId);
                }
                if (hotReloadClassLoader == null
                        && oldHandle.getExecutable() != null
                        && oldHandle.getExecutable().getDeclaringClass() != null) {
                    hotReloadClassLoader =
                            oldHandle.getExecutable().getDeclaringClass().getClassLoader();
                }
                XposedInterface.Hooker replacement = createHotReloadHooker(oldHandle.getId());
                if (replacement != null) {
                    XposedInterface.HookHandle replacementHandle =
                            oldHandle.replaceHook(replacement);
                    hookHandles.add(replacementHandle);
                    if (oldHookId != null) {
                        oldHookIds.add(oldHookId);
                    }
                    if ("systemui_back_commit_composition".equals(oldHookId)) {
                        backCommitCompositionHookReady = true;
                    } else if ("systemui_back_finish_open_atomic".equals(
                            oldHookId)) {
                        backFinishOpenAtomicHookReady = true;
                    }
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
                || hadServerHook;
        if (shouldInstallServerHooks && replaced == 0) {
            installSystemServerHooks(null);
        } else if (shouldInstallServerHooks
                && (!oldHookIds.contains("server_back_promote_to_tf_if_needed")
                || !oldHookIds.contains("server_back_window_start_animation")
                || !oldHookIds.contains("server_schedule_animation_prepare_transition")
                || !oldHookIds.contains("server_back_navigation_done_cleanup")
                || !oldHookIds.contains("server_return_home_touch_occlusion")
                || (!oldHookIds.contains("server_predictive_opt_in_metadata")
                && !oldHookIds.contains("predictive_opt_in_system_server")))) {
            ClassLoader serverClassLoader = findSystemServerClassLoader(hotReloadClassLoader);
            if (serverClassLoader != null) {
                if (!oldHookIds.contains(
                        "server_back_promote_to_tf_if_needed")) {
                    hookTaskFragmentPromotionCompatibility(
                            serverClassLoader);
                }
                if (!oldHookIds.contains("server_back_window_start_animation")) {
                    hookBackWindowStartAnimation(serverClassLoader);
                }
                if (!oldHookIds.contains("server_schedule_animation_prepare_transition")) {
                    hookScheduleAnimationPrepareTransition(serverClassLoader);
                }
                if (!oldHookIds.contains("server_back_navigation_done_cleanup")) {
                    hookBackNavigationDoneCleanup(serverClassLoader);
                }
                if (!oldHookIds.contains("server_return_home_touch_occlusion")) {
                    hookReturnHomeTouchOcclusion(serverClassLoader);
                }
                if (!oldHookIds.contains("server_predictive_opt_in_metadata")
                        && !oldHookIds.contains("predictive_opt_in_system_server")) {
                    hookPredictiveBackOptInMetadata(serverClassLoader);
                }
            }
        }
        if (SYSTEM_UI.equals(processName) && hotReloadClassLoader != null) {
            Class<?> hotReloadBackControllerClass = null;
            if (!oldHookIds.contains("shell_back_onBackAnimationFinished")
                    || !oldHookIds.contains("shell_back_finishBackAnimation")
                    || !oldHookIds.contains(
                    "shell_back_onBackNavigationInfoReceived")) {
                try {
                    hotReloadBackControllerClass = Class.forName(
                            BACK_ANIMATION_CONTROLLER, false,
                            hotReloadClassLoader);
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG,
                            "Failed to resolve Shell controller for hook backfill",
                            throwable);
                }
            }
            if (hotReloadBackControllerClass != null
                    && !oldHookIds.contains(
                    "shell_back_onBackAnimationFinished")) {
                try {
                    hookShellAnimationFinished(hotReloadBackControllerClass,
                            "onBackAnimationFinished",
                            "shell_back_onBackAnimationFinished", false);
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG,
                            "Failed to backfill outer Shell completion hook",
                            throwable);
                }
            }
            if (hotReloadBackControllerClass != null
                    && !oldHookIds.contains(
                    "shell_back_finishBackAnimation")) {
                try {
                    hookShellAnimationFinished(hotReloadBackControllerClass,
                            "finishBackAnimation",
                            "shell_back_finishBackAnimation", true);
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG,
                            "Failed to backfill definitive Shell completion hook",
                            throwable);
                }
            }
            if (hotReloadBackControllerClass != null
                    && !oldHookIds.contains(
                    "shell_back_onBackNavigationInfoReceived")) {
                try {
                    hookBackNavigationInfoReceived(
                            hotReloadBackControllerClass);
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG,
                            "Failed to backfill Shell navigation-info hook",
                            throwable);
                }
            }
            if (!oldHookIds.contains("systemui_navigation_bar_show_transient")) {
                hookNavigationBarTransientAutoHide(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_navigation_bar_transient_appearance")) {
                hookNavigationBarTransientAppearance(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_status_bar_transient_appearance")) {
                hookStatusBarTransientAppearance(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_navigation_bar_controller_create")) {
                hookNavigationBarControllerCreate(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_navigation_bar_controller_remove")) {
                hookNavigationBarControllerRemove(hotReloadClassLoader);
            }
            if (!oldHookIds.contains(
                    "systemui_navigation_bar_controller_onNavigationModeChanged")) {
                hookNavigationBarControllerMode(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_default_transition_start")) {
                hookDefaultTransitionHandler(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_default_transition_merge")) {
                hookDefaultTransitionImplMerge(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_back_send_event_guard")) {
                hookBackAnimationSendBackEvent(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_back_prepare_reparent")) {
                hookBackPrepareTransitionReparent(hotReloadClassLoader);
            }
            if (!backCommitCompositionHookReady) {
                hookBackCommitComposition(hotReloadClassLoader);
            }
            if (!backFinishOpenAtomicHookReady) {
                hookBackFinishOpenAtomicTransfer(hotReloadClassLoader);
            }
            if (backCommitCompositionHookReady
                    && backFinishOpenAtomicHookReady
                    && !backFinishOpenCallerDeoptimized) {
                deoptimizeBackFinishOpenCaller(hotReloadClassLoader);
            }
        }
        if (SYSTEM_UI.equals(processName)) {
            restoreSystemUiHotReloadLifecycle(hotReloadClassLoader);
        }
        if (MIUI_HOME.equals(processName) && hotReloadClassLoader != null) {
            try {
                Class<?> gestureStubClass = Class.forName(MIUI_HOME_GESTURE_STUB, false,
                        hotReloadClassLoader);
                if (!oldHookIds.contains("miui_home_gesture_stub_show")) {
                    hookMiuiHomeGestureStubShow(gestureStubClass);
                }
                if (!oldHookIds.contains("miui_home_gesture_input_arbiter")) {
                    Class<?> processorClass = Class.forName(
                            MIUI_HOME_GESTURE_PROCESSOR, false, hotReloadClassLoader);
                    hookMiuiHomeGestureInputArbiter(processorClass, gestureStubClass);
                }
                if (!oldHookIds.contains("miui_home_recents_actual_state_v2")) {
                    Class<?> recentsContainerClass = Class.forName(
                            MIUI_HOME_RECENTS_CONTAINER, false, hotReloadClassLoader);
                    hookMiuiHomeRecentsActualState(recentsContainerClass);
                }
                if (!oldHookIds.contains("miui_home_recents_task_launch")) {
                    Class<?> taskViewClass = Class.forName(MIUI_HOME_TASK_VIEW, false,
                            hotReloadClassLoader);
                    hookMiuiHomeRecentsTaskLaunch(taskViewClass);
                }
                if (!oldHookIds.contains("miui_home_fullscreen_state")) {
                    hookMiuiHomeFullscreenState(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_open_break_enable")) {
                    Class<?> breakControllerClass = Class.forName(
                            MIUI_HOME_BACK_GESTURE_BREAK_CONTROLLER, false,
                            hotReloadClassLoader);
                    hookMiuiHomeOpenBreakEnable(breakControllerClass);
                }
                if (!oldHookIds.contains("miui_home_open_break_animation_start")
                        || !oldHookIds.contains("miui_home_open_break_animation_end")) {
                    Class<?> listenerClass = Class.forName(
                            MIUI_HOME_WINDOW_ELEMENT_ANIM_LISTENER, false,
                            hotReloadClassLoader);
                    if (!oldHookIds.contains("miui_home_open_break_animation_start")) {
                        hookMiuiHomeOpenBreakAnimationStart(listenerClass);
                    }
                    if (!oldHookIds.contains("miui_home_open_break_animation_end")) {
                        hookMiuiHomeOpenBreakAnimationEnd(listenerClass);
                    }
                }
                if (!oldHookIds.contains("miui_home_open_snapshot_targets")) {
                    try {
                        hookMiuiHomeLauncherOpenSnapshotTargets(
                                hotReloadClassLoader);
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG,
                                "Failed to backfill Xiaomi OPEN target binding",
                                throwable);
                    }
                }
                if (!oldHookIds.contains("miui_home_reused_close_open")) {
                    hookMiuiHomeReusedCloseOpen(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_return_home_element_transition")
                        || !oldHookIds.contains("miui_home_return_home_element_leash_rearm")
                        || !oldHookIds.contains("miui_home_return_home_element_anim_type")) {
                    try {
                        hookMiuiHomeTransitionContinuity(
                                hotReloadClassLoader,
                                !oldHookIds.contains("miui_home_return_home_element_transition"),
                                !oldHookIds.contains("miui_home_return_home_element_leash_rearm"),
                                !oldHookIds.contains("miui_home_return_home_element_anim_type"));
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG,
                                "Failed to backfill MiuiHome element continuity",
                                throwable);
                    }
                }
                if (!oldHookIds.contains("miui_home_return_home_anim_to_config")
                        || !oldHookIds.contains("miui_home_return_home_finish_dispatch_source")
                        || !oldHookIds.contains("miui_home_return_home_finish_dispatch_apply")) {
                    try {
                        hookMiuiHomeUnifiedFinishEpoch(
                                hotReloadClassLoader,
                                !oldHookIds.contains("miui_home_return_home_anim_to_config"),
                                !oldHookIds.contains("miui_home_return_home_finish_dispatch_source"),
                                !oldHookIds.contains("miui_home_return_home_finish_dispatch_apply"));
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG,
                                "Failed to backfill MiuiHome finish-epoch hooks",
                                throwable);
                    }
                }
                if (!oldHookIds.contains("miui_home_permission_activity_merge")) {
                    try {
                        hookMiuiHomePermissionMerge(hotReloadClassLoader);
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG,
                                "Failed to backfill MiuiHome permission merge",
                                throwable);
                    }
                }
                if (!oldHookIds.contains("miui_home_trace_on_anim_update")
                        || !oldHookIds.contains("miui_home_trace_apply_surface_params")) {
                    hookMiuiHomeGeometryFrames(
                            hotReloadClassLoader,
                            !oldHookIds.contains("miui_home_trace_on_anim_update"),
                            !oldHookIds.contains("miui_home_trace_apply_surface_params"));
                }
                if (!oldHookIds.contains("miui_home_trace_transition_setup_leash")) {
                    try {
                        hookMiuiHomeTransitionSetupLeash(
                                hotReloadClassLoader);
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG,
                                "Failed to backfill MiuiHome transition geometry hook",
                                throwable);
                    }
                }
                hookMiuiHomeStartTransactionApply(oldHookIds);
                if (!oldHookIds.contains("miui_home_return_home_cancel_surface")
                        || !oldHookIds.contains("miui_home_return_home_set_to_old")) {
                    hookMiuiHomeReturnHomeCloseInterruption(
                            hotReloadClassLoader,
                            !oldHookIds.contains("miui_home_return_home_cancel_surface"),
                            !oldHookIds.contains("miui_home_return_home_set_to_old"));
                }
                if (!oldHookIds.contains("miui_home_return_home_same_icon_parallel")) {
                    hookMiuiHomeReturnHomeSameIconParallel(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_return_home_fresh_open")) {
                    hookMiuiHomeReturnHomeFreshOpen(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_return_home_cancel_direct")) {
                    hookMiuiHomeReturnHomeDirectCancel(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_drawer_state")) {
                    hookMiuiHomeDrawerState(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_editing_state")) {
                    hookMiuiHomeEditingState(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_return_home_initialize")) {
                    hookMiuiHomeReturnHomeInitialize(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_return_home_local_handoff")) {
                    hookMiuiHomeReturnHomeLocalHandoff(hotReloadClassLoader);
                }
                if (!oldHookIds.contains("miui_home_return_home_wallpaper_set")
                        || !oldHookIds.contains("miui_home_return_home_wallpaper_anim")) {
                    hookMiuiHomeReturnHomeWallpaperCommands(
                            hotReloadClassLoader,
                            !oldHookIds.contains("miui_home_return_home_wallpaper_set"),
                            !oldHookIds.contains("miui_home_return_home_wallpaper_anim"));
                }
                restoreMiuiHomeGestureStubsAfterHotReload(hotReloadClassLoader);
                refreshMiuiHomeEditingState(
                        hotReloadClassLoader, "hotReloadBackfill");
                restoreMiuiHomeOpenBreakAfterHotReload();
                restoreMiuiHomeReturnHomeAfterHotReload(hotReloadClassLoader);
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

    protected XposedInterface.Hooker createHotReloadHooker(String hookId) {
        if (hookId == null) {
            return null;
        }
        switch (hookId) {
            case "systemui_block_miui_gesture_line_progress":
                return this::interceptMiuiOverviewProxyTransact;
            case "systemui_navigation_bar_transient_appearance":
            case "systemui_status_bar_transient_appearance":
                return this::preserveTransientBarAppearance;
            case "systemui_navigation_bar_show_transient":
                return this::preserveTransientBarAutoHide;
            case "systemui_navigation_bar_controller_create":
                return this::reconcileAfterNavigationBarCreate;
            case "systemui_navigation_bar_controller_remove":
                return this::reconcileAfterNavigationBarRemove;
            case "systemui_navigation_bar_controller_onNavigationModeChanged":
                return this::reconcileAfterNavigationModeChanged;
            case "miui_home_gesture_stub_layout_params":
                return this::restoreMiuiHomeGestureStubTouchableLayout;
            case "miui_home_gesture_stub_show":
                return this::restoreMiuiHomeGestureStubShow;
            case "miui_home_gesture_input_arbiter":
                return this::arbitrateMiuiHomeAcceptedInput;
            case "miui_home_recents_actual_state_v2":
                return this::mirrorMiuiHomeRecentsActualState;
            case "miui_home_recents_task_launch":
                return this::mirrorMiuiHomeRecentsTaskLaunch;
            case "miui_home_fullscreen_state":
                return this::mirrorMiuiHomeFullscreenState;
            case "miui_home_open_break_enable":
                return this::captureMiuiHomeOpenBreakEnable;
            case "miui_home_open_break_animation_start":
                return this::mirrorMiuiHomeOpenBreakAnimationStart;
            case "miui_home_open_break_animation_end":
                return this::mirrorMiuiHomeOpenBreakAnimationEnd;
            case "miui_home_open_snapshot_targets":
                return this::captureMiuiHomeLauncherOpenSnapshotAfterTargetsBound;
            case "miui_home_reused_close_open":
                return this::restoreMiuiHomeReusedCloseOpen;
            case "miui_home_return_home_element_transition":
                return this::prepareMiuiHomeElementTransitionContinuity;
            case "miui_home_return_home_element_leash_rearm":
                return this::rearmMiuiHomeElementLeashAfterNativeClear;
            case "miui_home_return_home_element_anim_type":
                return this::observeMiuiHomeElementAnimType;
            case "miui_home_return_home_anim_to_config":
                return this::observeMiuiHomeUnifiedAnimToConfigured;
            case "miui_home_return_home_finish_dispatch_source":
                return this::captureMiuiHomeUnifiedFinishDispatch;
            case "miui_home_return_home_finish_dispatch_apply":
                return this::guardMiuiHomeUnifiedFinishDispatch;
            case "miui_home_trace_on_anim_update":
                return this::captureMiuiHomeNativeGeometry;
            case "miui_home_trace_apply_surface_params":
                return this::applyMiuiHomeNativeSurfaceParams;
            case "miui_home_trace_transition_setup_leash":
                return this::armMiuiHomeTransitionStartGeometry;
            case "miui_home_permission_activity_merge":
                return this::preserveMiuiHomeOpenAcrossPermissionMerge;
            case "miui_home_return_home_cancel_surface":
                return this::captureMiuiHomeReturnHomeCloseInterruption;
            case "miui_home_return_home_same_icon_parallel":
                return this::routeMiuiHomeReturnHomeSameIconParallel;
            case "miui_home_return_home_fresh_open":
                return this::forceMiuiHomeReturnHomeFreshOpen;
            case "miui_home_return_home_cancel_direct":
                return this::wrapMiuiHomeReturnHomeDirectCancel;
            case "miui_home_return_home_set_to_old":
                return this::finishMiuiHomeReturnHomeCloseInterruption;
            case "miui_home_drawer_state":
                return this::mirrorMiuiHomeDrawerState;
            case "miui_home_editing_state":
                return this::mirrorMiuiHomeEditingState;
            case "miui_home_return_home_initialize":
                return this::registerMiuiHomeReturnHome;
            case "miui_home_return_home_local_handoff":
                return this::provideMiuiHomeReturnHomeLocalHandoff;
            case "miui_home_return_home_wallpaper_set":
                return this::observeMiuiHomeReturnHomeWallpaperSet;
            case "miui_home_return_home_wallpaper_anim":
                return this::observeMiuiHomeReturnHomeWallpaperAnim;
            case "server_back_promote_to_tf_if_needed":
                return this::interceptPromoteToTaskFragmentIfNeeded;
            case "server_back_window_start_animation":
                return this::prepareOpeningTaskFragment;
            case "server_schedule_animation_prepare_transition":
                return this::interceptScheduleAnimationPrepareTransition;
            case "server_back_navigation_done_cleanup":
                return this::cleanupSkippedRemoteAnimationOnNavigationDone;
            case "server_return_home_touch_occlusion":
                return this::allowCommittedReturnHomeTouchThrough;
            case "server_predictive_opt_in_metadata":
            case "predictive_opt_in_system_server":
                return this::injectSelectedPredictiveBackMetadata;
            case "systemui_default_transition_start":
                return this::registerDefaultTransitionHandler;
            case "systemui_default_transition_merge":
                return this::trackMiuiOpenCloseMerge;
            case "systemui_back_send_event_guard":
                return this::guardDuplicateBackEvent;
            case "systemui_back_prepare_reparent":
                return this::correctPredictiveBackPrepareReparent;
            case "systemui_back_commit_composition":
                return this::correctPredictiveBackCommitComposition;
            case "systemui_back_finish_open_atomic":
                return this::transferReturnHomeFinishIntoCloseStart;
            case "systemui_edge_back_setBackAnimation":
                return this::onEdgeBackSetBackAnimation;
            case "systemui_edge_back_updateIsEnabled":
                return this::onEdgeBackUpdateIsEnabled;
            case "systemui_edge_back_onNavigationModeChanged":
                return this::onEdgeBackNavigationModeChanged;
            case "shell_back_onBackNavigationInfoReceived":
                return this::onBackNavigationInfoReceived;
            case "shell_back_onBackAnimationFinished":
                return this::proceedShellAnimationLifecycle;
            case "shell_back_finishBackAnimation":
                return this::onShellAnimationFinished;
            case "systemui_navigation_bar_view_insets":
            case "systemui_navigation_bar_window_state":
            case "systemui_navigation_bar_abort_transient":
            case "systemui_navigation_bar_auto_hide":
            case "systemui_edge_back_task_stack_changed":
            case "systemui_edge_back_exclusion_changed":
            case "systemui_edge_back_update_resources":
            case "miui_home_gesture_stub_touch_region":
            case "miui_home_recents_actual_state":
            case "miui_home_return_home_element_retarget":
            case "miui_home_recents_map_alpha":
            case "miui_home_trace_update_element_value":
            case "miui_home_trace_update_all_anim_values":
            case "miui_home_trace_apply_transform_new":
            case "miui_home_trace_task_view_primary":
            case "miui_home_trace_task_view_surface":
            case "miui_home_trace_schedule_apply_directly":
            case "miui_home_trace_transition_create_leash":
            case "miui_home_return_home_window_alpha":
            case "miui_home_return_home_native_frame":
            case "systemui_default_animation_reverse_frames":
                // Neutralize retired hooks until the next process restart.
                return XposedInterface.Chain::proceed;
            default:
                break;
        }
        if (hookId.startsWith("miui_home_trace_surface_transaction_")) {
            return this::applyMiuiHomeStartGeometry;
        }
        if (hookId.startsWith("server_security_sidebar_transient_bars_")) {
            return this::interceptSecuritySidebarTransientBars;
        }
        if (hookId.startsWith("miui_home_trace_island_protocol_")
                || hookId.startsWith("miui_home_block_gesture_window_")
                || hookId.startsWith("miui_home_")) {
            return XposedInterface.Chain::proceed;
        }
        return null;
    }

    protected void restoreHotReloadInput(Object savedState) {
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
                if (state.length >= 12 && state[11] instanceof IBinder) {
                    miuiHomeReturnHomeBinder = (IBinder) state[11];
                }
                if (state.length >= 13 && state[12] instanceof Object[][]) {
                    pendingHotReloadHeadlessState = (Object[][]) state[12];
                }
                if (state.length >= 14) {
                    miuiLauncherEditing = Boolean.TRUE.equals(state[13]);
                }
            }
        }
        restoreMiuiOverviewDismissTimeoutAfterHotReload();
        Object[][] inputState = inputStateObject instanceof Object[][]
                ? (Object[][]) inputStateObject : new Object[0][0];
        if (SYSTEM_UI.equals(processName)) {
            pendingHotReloadInputState = inputState;
            log(Log.INFO, TAG, "Deferred SystemUI hot reload lifecycle restoration"
                    + ", inputCount=" + inputState.length
                    + ", headlessLeaseCount="
                    + pendingHotReloadHeadlessState.length);
            return;
        }
        if (!(inputStateObject instanceof Object[][])) {
            log(Log.INFO, TAG, "No hot reload back input state to restore");
            return;
        }
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

    protected synchronized void restoreMiuiOverviewDismissTimeoutAfterHotReload() {
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

    protected void installMiuiHomeHooks(ClassLoader classLoader) {
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
            try {
                hookMiuiHomeLauncherOpenSnapshotTargets(classLoader);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install Xiaomi OPEN target binding",
                        throwable);
            }
            hookMiuiHomeReusedCloseOpen(classLoader);
            try {
                hookMiuiHomeTransitionContinuity(
                        classLoader, true, true, true);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install MiuiHome element continuity",
                        throwable);
            }
            try {
                hookMiuiHomeUnifiedFinishEpoch(
                        classLoader, true, true, true);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install MiuiHome finish-epoch hooks",
                        throwable);
            }
            try {
                hookMiuiHomePermissionMerge(classLoader);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install MiuiHome permission merge",
                        throwable);
            }
            hookMiuiHomeGeometryFrames(classLoader, true, true);
            try {
                hookMiuiHomeTransitionSetupLeash(classLoader);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to install MiuiHome transition geometry hook",
                        throwable);
            }
            hookMiuiHomeStartTransactionApply(
                    Collections.emptySet());
            hookMiuiHomeReturnHomeCloseInterruption(classLoader, true, true);
            hookMiuiHomeReturnHomeSameIconParallel(classLoader);
            hookMiuiHomeReturnHomeFreshOpen(classLoader);
            hookMiuiHomeReturnHomeDirectCancel(classLoader);
            hookMiuiHomeDrawerState(classLoader);
            hookMiuiHomeEditingState(classLoader);
            hookMiuiHomeReturnHomeInitialize(classLoader);
            hookMiuiHomeReturnHomeLocalHandoff(classLoader);
            hookMiuiHomeReturnHomeWallpaperCommands(classLoader, true, true);
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
                    + ", mirrorsLauncherEditingState=true"
                    + ", mirrorsLauncherOpenBreakState=true"
                    + ", repairsNonReusableSameIconOpen=true"
                    + ", usesStandardLauncherBackCallback=true");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install MiuiHome input arbitration", throwable);
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        processName = "system";
        log(Log.INFO, TAG, "System server starting, build=" + BUILD_MARK
                + ", classLoader=" + param.getClassLoader());
        installSystemServerHooks(param.getClassLoader());
    }
}
