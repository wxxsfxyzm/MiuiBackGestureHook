package dev.codex.miuibackgesturehook.hooks.hotreload;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import dev.codex.miuibackgesturehook.hooks.systemserver.SystemServerHookRuntime;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public abstract class HotReloadHookRuntime extends SystemServerHookRuntime {

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        if (blockMiuiHomeXposedHooks() && isMiuiHomeProcess(processName)) {
            // A protected Android 17 launcher process owns no module lifecycle state. Keep the
            // reload path just as inert as cold package loading.
            param.setSavedInstanceState(null);
            moduleLog(Log.WARN, TAG,
                    "Android 17 MiuiHome LSPosed protection retained during hot reload"
                            + ", process=" + processName);
            return true;
        }
        if (googleDexResolutionInFlight.get() != 0) {
            moduleLog(Log.WARN, TAG,
                    "Deferred hot reload during Google dex resolution"
                            + ", process=" + processName);
            return false;
        }
        if (contextualSearchBridgeCallsInFlight.get() != 0) {
            moduleLog(Log.WARN, TAG,
                    "Deferred hot reload during an authenticated contextual-search call"
                            + ", process=" + processName);
            return false;
        }
        PreparedBackTransitionHold heldTransition =
                preparedBackTransitionHold.get();
        if (heldTransition != null) {
            moduleLog(Log.WARN, TAG,
                    "Deferred hot reload while a prepared-back transition is held"
                            + ", process=" + processName
                            + ", "
                            + describePreparedBackTransitionHold(
                            heldTransition));
            return false;
        }
        for (NativeBackInputMonitor monitor
                : new ArrayList<>(nativeInputMonitors.values())) {
            if (monitor.blocksHotReload()) {
                moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.WARN, TAG,
                    "Deferred hot reload while Xiaomi owns predictive return-home"
                            + ", process=" + processName
                            + ", state="
                            + activeReturnHomeController.describeUnifiedOwner());
            return false;
        }
        moduleLog(Log.INFO, TAG, "Hot reloading, build=" + BUILD_MARK
                + ", process=" + processName
                + ", hooks=" + hookHandles.size());
        boolean savedMiuiOverviewVisible = miuiOverviewVisible;
        boolean savedMiuiDrawerVisible = miuiDrawerVisible;
        boolean savedMiuiFolderVisible = miuiFolderVisible;
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
        cancelOneUiCrossTaskForHotReload();
        miuiHomeLocalHandoffToken.set(null);
        invalidateMiuiHomeLauncherOpenSnapshot(null, "hotReload");
        IBinder savedMiuiHomeReturnHomeBinder =
                detachMiuiHomeReturnHome("hotReload", true);
        miuiHomePendingNativeGeometry.remove();
        returnHomeFinishTransferCandidate.remove();
        preparedBackTargetArrival.set(null);
        preparedBackTargetArrivalHookReady = false;
        preparedBackTerminalHookReady = false;
        preparedBackStartAnimationInvoker = null;
        freeformColorRootCandidate.set(null);
        freeformColorRootAdoption = null;
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
        miuiLauncherOpenActive = false;
        miuiLauncherOpenBreakAvailable = false;
        miuiLauncherOpenBreakGeneration = 0L;
        // The replacement SystemUI generation must obtain a fresh,
        // authenticated native publication before XiaoAi can be claimed.
        miuiLauncherXiaoAiVisible = false;
        acceptedInputToken.set(null);
        miuiHomeAcceptedInputIdentity.set(null);
        endedLauncherOpenWallpaperReset.set(null);
        Object[] savedContextualSearchNavigationBars =
                detachAllContextualSearchInputReceiversForHotReload();
        closeHyperOsBackHapticHelper();
        clearSystemUiReturnHomeCommitIdentity(null, 0L, "hotReload");
        unregisterMiuiOverviewStateReceiver();
        unregisterMiuiHomeOpenBreakCommandReceiver();
        unregisterMiuiHomeInputArbiterReceiver();
        releaseMiuiHomeGestureTriggerPreferenceListener();
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
        destroySystemUiPlatformImpl();
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
                Boolean.valueOf(savedMiuiLauncherEditing),
                Boolean.valueOf(savedMiuiFolderVisible),
                savedContextualSearchNavigationBars
        });
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        String reportedProcessName = param.getProcessName();
        boolean hadMiuiHomeHook = false;
        for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
            String oldHookId = oldHandle.getId();
            if (oldHookId != null && oldHookId.startsWith("miui_home_")) {
                hadMiuiHomeHook = true;
                break;
            }
        }
        if (blockMiuiHomeXposedHooks()
                && (isMiuiHomeProcess(reportedProcessName) || hadMiuiHomeHook)) {
            processName = reportedProcessName;
            int unhooked = 0;
            for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
                try {
                    oldHandle.unhook();
                    unhooked++;
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG,
                            "Failed to remove an old MiuiHome hook while enabling Android 17"
                                    + " protection: " + oldHandle,
                            throwable);
                }
            }
            moduleLog(Log.WARN, TAG,
                    "Android 17 MiuiHome LSPosed protection active after hot reload"
                            + ", process=" + reportedProcessName
                            + ", oldHooksRemoved=" + unhooked);
            return;
        }
        int replaced = 0;
        Set<String> oldHookIds = new java.util.HashSet<>();
        boolean hadServerHook = false;
        boolean hadSystemUiHook = false;
        ClassLoader hotReloadClassLoader = null;
        ClassLoader oldSystemServerClassLoader = null;
        ClassLoader oldSystemUiClassLoader = null;
        for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
            try {
                String oldHookId = oldHandle.getId();
                boolean oldServerHook = oldHookId != null
                        && (oldHookId.startsWith("server_")
                        || "predictive_opt_in_system_server".equals(oldHookId));
                hadServerHook |= oldServerHook;
                boolean oldSystemUiHook = oldHookId != null
                        && (oldHookId.startsWith("systemui_")
                        || oldHookId.startsWith("shell_back_")
                        || oldHookId.startsWith("shell_cross_")
                        || oldHookId.startsWith("shell_return_")
                        || oldHookId.startsWith("shell_default_"));
                hadSystemUiHook |= oldSystemUiHook;
                ClassLoader oldExecutableClassLoader = null;
                if (oldHandle.getExecutable() != null
                        && oldHandle.getExecutable().getDeclaringClass() != null) {
                    oldExecutableClassLoader = oldHandle.getExecutable()
                            .getDeclaringClass().getClassLoader();
                }
                if (hotReloadClassLoader == null && oldExecutableClassLoader != null) {
                    hotReloadClassLoader = oldExecutableClassLoader;
                }
                if (oldSystemServerClassLoader == null && oldServerHook
                        && oldExecutableClassLoader != null) {
                    try {
                        Class.forName(BACK_NAVIGATION_CONTROLLER, false,
                                oldExecutableClassLoader);
                        oldSystemServerClassLoader = oldExecutableClassLoader;
                    } catch (Throwable ignored) {
                        // Try the next old system-server hook executable.
                    }
                }
                if (oldSystemUiClassLoader == null && oldSystemUiHook
                        && oldExecutableClassLoader != null) {
                    try {
                        Class.forName(EDGE_BACK_GESTURE_HANDLER, false,
                                oldExecutableClassLoader);
                        oldSystemUiClassLoader = oldExecutableClassLoader;
                    } catch (Throwable ignored) {
                        // A Shell hook may use the Shell loader; keep scanning
                        // until an executable exposes the SystemUI classes.
                    }
                }
                boolean freeformRoleNormalizer =
                        "server_freeform_prepare_role_normalization".equals(oldHookId);
                if (freeformRoleNormalizer) {
                    initializeFreeformPrepareRoleReflection(oldExecutableClassLoader);
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
                    } else if ("systemui_back_prepared_target_arrival".equals(
                            oldHookId)) {
                        preparedBackTargetArrivalHookReady = true;
                    } else if ("systemui_back_prepared_terminal".equals(
                            oldHookId)) {
                        preparedBackTerminalHookReady = true;
                    } else if ("systemui_back_prepared_transition_decision".equals(
                            oldHookId)
                            && oldHandle.getExecutable() instanceof java.lang.reflect.Method) {
                        preparePreparedBackStartAnimationInvoker(
                                (java.lang.reflect.Method) oldHandle.getExecutable());
                    } else if ("systemui_oneui_cross_task_finish".equals(oldHookId)
                            && oldHandle.getExecutable() instanceof java.lang.reflect.Method) {
                        oneUiCrossTaskFinishMethod =
                                (java.lang.reflect.Method) oldHandle.getExecutable();
                    }
                    replaced++;
                } else {
                    oldHandle.unhook();
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to replace old hook: " + oldHandle, throwable);
            }
        }
        if (hadSystemUiHook) {
            processName = SYSTEM_UI;
        } else {
            processName = reportedProcessName;
        }
        restoreHotReloadInput(param.getSavedInstanceState());
        if (hadSystemUiHook && oldSystemUiClassLoader == null) {
            for (Object[] pair : pendingHotReloadInputState) {
                if (pair == null || pair.length == 0 || pair[0] == null) {
                    continue;
                }
                ClassLoader candidate = pair[0].getClass().getClassLoader();
                try {
                    Class.forName(EDGE_BACK_GESTURE_HANDLER, false, candidate);
                    oldSystemUiClassLoader = candidate;
                    break;
                } catch (Throwable ignored) {
                    // Keep the lifecycle fail-closed if the saved owner is stale.
                }
            }
        }
        if (hadSystemUiHook && oldSystemUiClassLoader != null) {
            hotReloadClassLoader = oldSystemUiClassLoader;
        }
        boolean shouldInstallServerHooks = param.isSystemServer()
                || "system".equals(processName)
                || hadServerHook;
        ClassLoader preferredServerClassLoader = oldSystemServerClassLoader != null
                ? oldSystemServerClassLoader : hotReloadClassLoader;
        if (shouldInstallServerHooks && replaced == 0) {
            installSystemServerHooks(preferredServerClassLoader);
        } else if (shouldInstallServerHooks) {
            ClassLoader serverClassLoader = findSystemServerClassLoader(
                    preferredServerClassLoader);
            if (serverClassLoader != null) {
                try {
                    // Re-resolve the Android 16/17 server adapter from the real package
                    // ClassLoader before backfilling version-specific method signatures.
                    // Replacement hookers remain lazy so an already-replaced handle cannot
                    // race this selection during hot-reload restoration.
                    selectSystemServerPlatformImpl(serverClassLoader);
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG,
                            "Failed to restore system_server platform implementation"
                                    + " after hot reload",
                            throwable);
                }
                if (!oldHookIds.contains("server_back_window_start_animation")) {
                    hookBackWindowStartAnimation(serverClassLoader);
                }
                if (!oldHookIds.contains("server_a17_opening_surface_visibility")) {
                    hookA17OpeningSurfaceVisibility(serverClassLoader);
                }
                if (!oldHookIds.contains(
                        "server_freeform_prepare_role_normalization")) {
                    hookFreeformCrossActivityPrepareRole(serverClassLoader);
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
                hookSecuritySidebarTransientBars(serverClassLoader, oldHookIds);
                hookContextualSearchCompatibility(serverClassLoader, oldHookIds);
            }
        }
        if (SYSTEM_UI.equals(processName) && hotReloadClassLoader != null) {
            try {
                // Platform adapters own version-specific field, signature and native-panel
                // access. They are deliberately destroyed before the old runtime is replaced,
                // so select the Android 16/17 adapter again before any restored input monitor
                // or replacement hook can observe an event.
                selectSystemUiPlatformImpl(hotReloadClassLoader);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed to restore SystemUI platform implementation after hot reload",
                        throwable);
            }
            boolean missingContextualSearchAttach = !oldHookIds.contains(
                    "systemui_contextual_search_nav_attach");
            boolean missingContextualSearchDetach = !oldHookIds.contains(
                    "systemui_contextual_search_nav_detach");
            if (missingContextualSearchAttach || missingContextualSearchDetach) {
                hookContextualSearchNavigationBar(hotReloadClassLoader,
                        missingContextualSearchAttach, missingContextualSearchDetach);
            }
            Class<?> hotReloadBackControllerClass = null;
            if (!oldHookIds.contains("shell_back_onBackAnimationFinished")
                    || !oldHookIds.contains("shell_back_finishBackAnimation")
                    || !oldHookIds.contains(
                    "shell_back_onBackNavigationInfoReceived")
                    || !oldHookIds.contains(
                    "systemui_back_prepared_terminal")) {
                try {
                    hotReloadBackControllerClass = Class.forName(
                            BACK_ANIMATION_CONTROLLER, false,
                            hotReloadClassLoader);
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG,
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
                    moduleLog(Log.ERROR, TAG,
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
                    moduleLog(Log.ERROR, TAG,
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
                    moduleLog(Log.ERROR, TAG,
                            "Failed to backfill Shell navigation-info hook",
                            throwable);
                }
            }
            if (hotReloadBackControllerClass != null
                    && !oldHookIds.contains(
                    "systemui_back_prepared_terminal")) {
                hookPreparedBackTerminal(hotReloadBackControllerClass);
            }
            if (!oldHookIds.contains(
                    "systemui_block_miui_gesture_line_progress")) {
                hookMiuiOverviewProxy(hotReloadClassLoader);
            }
            boolean missingEdgeUpdate = !oldHookIds.contains(
                    "systemui_edge_back_updateIsEnabled");
            boolean missingEdgeMode = !oldHookIds.contains(
                    "systemui_edge_back_onNavigationModeChanged");
            boolean missingEdgeAnimation = !oldHookIds.contains(
                    "systemui_edge_back_setBackAnimation");
            if (missingEdgeUpdate || missingEdgeMode || missingEdgeAnimation) {
                hookEdgeBackGestureHandler(hotReloadClassLoader,
                        missingEdgeUpdate, missingEdgeMode,
                        missingEdgeAnimation);
            }
            if (!oldHookIds.contains("systemui_back_panel_aosp_haptic")) {
                hookAospBackPanelHaptic(hotReloadClassLoader);
            }
            boolean missingAospViewHaptic = !oldHookIds.contains(
                    "systemui_back_panel_aosp_view_haptic");
            boolean missingAospViewHapticFlags = !oldHookIds.contains(
                    "systemui_back_panel_aosp_view_haptic_flags");
            if (missingAospViewHaptic || missingAospViewHapticFlags) {
                hookAospBackPanelViewHaptic(hotReloadClassLoader,
                        missingAospViewHaptic, missingAospViewHapticFlags);
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
            if (!oldHookIds.contains(
                    "systemui_a17_back_background_status_reset")) {
                hookPlatformBackAnimationStatusBarReset(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_navigation_bar_gesture_insets")) {
                hookNavigationBarGestureInsets(hotReloadClassLoader);
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
            String defaultTransitionOpenCaptureHookId =
                    defaultTransitionOpenCaptureHookId();
            if (!oldHookIds.contains(defaultTransitionOpenCaptureHookId)) {
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
            if (!oldHookIds.contains("systemui_back_prepared_target_arrival")) {
                hookPreparedBackTargetArrival(hotReloadClassLoader);
            }
            if (!oldHookIds.contains(
                    "systemui_back_prepared_transition_decision")) {
                hookPreparedBackTransitionDecision(hotReloadClassLoader);
            }
            if (!oldHookIds.contains(
                    "systemui_back_color_root_scrim_creation")) {
                hookFreeformCrossActivityScrimCreation();
            }
            if (!oldHookIds.contains("systemui_back_slide_start")
                    || !oldHookIds.contains("systemui_back_slide_progress")
                    || !oldHookIds.contains("systemui_back_slide_post_commit")
                    || !oldHookIds.contains("systemui_back_slide_duration")
                    || !oldHookIds.contains("systemui_back_slide_finish")
                    || !oldHookIds.contains("systemui_back_color_root_apply")) {
                hookCrossActivitySlideAnimation(hotReloadClassLoader,
                        !oldHookIds.contains("systemui_back_slide_start"),
                        !oldHookIds.contains("systemui_back_slide_progress"),
                        !oldHookIds.contains("systemui_back_slide_post_commit"),
                        !oldHookIds.contains("systemui_back_slide_duration"),
                        !oldHookIds.contains("systemui_back_slide_finish"),
                        !oldHookIds.contains("systemui_back_color_root_apply"));
            }
            if (!oldHookIds.contains("systemui_cross_task_background")) {
                hookCrossTaskBackground(hotReloadClassLoader);
            }
            if (!oldHookIds.contains("systemui_oneui_cross_task_invoke")
                    || !oldHookIds.contains("systemui_oneui_cross_task_finish")) {
                hookOneUiCrossTaskAnimation(hotReloadClassLoader,
                        !oldHookIds.contains("systemui_oneui_cross_task_invoke"),
                        !oldHookIds.contains("systemui_oneui_cross_task_finish"));
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
            Object[] navigationBars = pendingHotReloadContextualSearchNavigationBars;
            pendingHotReloadContextualSearchNavigationBars = new Object[0];
            restoreContextualSearchInputReceivers(navigationBars);
        }
        if (MIUI_HOME.equals(processName) && hotReloadClassLoader != null) {
            try {
                Class<?> gestureStubClass = Class.forName(MIUI_HOME_GESTURE_STUB, false,
                        hotReloadClassLoader);
                if (!oldHookIds.contains("miui_home_gesture_stub_trigger_region")) {
                    try {
                        hookMiuiHomeGestureStubTriggerRegion(gestureStubClass);
                    } catch (Throwable throwable) {
                        moduleLog(Log.WARN, TAG,
                                "MiuiHome trigger-region customization unavailable",
                                throwable);
                    }
                }
                if (!oldHookIds.contains("miui_home_gesture_stub_trigger_touch_region")) {
                    try {
                        hookMiuiHomeGestureStubTriggerTouchRegion(gestureStubClass);
                    } catch (Throwable throwable) {
                        moduleLog(Log.WARN, TAG,
                                "MiuiHome trigger touch-region customization unavailable",
                                throwable);
                    }
                }
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
                        moduleLog(Log.WARN, TAG,
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
                        moduleLog(Log.WARN, TAG,
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
                        moduleLog(Log.WARN, TAG,
                                "Failed to backfill MiuiHome finish-epoch hooks",
                                throwable);
                    }
                }
                if (!oldHookIds.contains("miui_home_permission_activity_merge")) {
                    try {
                        hookMiuiHomePermissionMerge(hotReloadClassLoader);
                    } catch (Throwable throwable) {
                        moduleLog(Log.WARN, TAG,
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
                        moduleLog(Log.WARN, TAG,
                                "Failed to backfill MiuiHome transition geometry hook",
                                throwable);
                    }
                }
                hookMiuiHomeStartTransactionApply(oldHookIds);
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
                boolean missingFolderOpen = !oldHookIds.contains(
                        "miui_home_folder_open_state");
                boolean missingFolderClose = !oldHookIds.contains(
                        "miui_home_folder_close_state");
                if (missingFolderOpen || missingFolderClose) {
                    hookMiuiHomeFolderState(hotReloadClassLoader,
                            missingFolderOpen, missingFolderClose);
                }
                if (!oldHookIds.contains("miui_home_freeform_back_touchability")) {
                    hookMiuiHomeFreeformBackTouchability(hotReloadClassLoader);
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
                refreshMiuiHomeFolderState(
                        hotReloadClassLoader, "hotReloadBackfill");
                restoreMiuiHomeOpenBreakAfterHotReload();
                restoreMiuiHomeReturnHomeAfterHotReload(hotReloadClassLoader);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to restore MiuiHome hooks",
                        throwable);
            }
        }
        if ((GOOGLE_APP.equals(processName)
                || processName.startsWith(GOOGLE_APP + ":"))
                && hotReloadClassLoader != null) {
            installGoogleAppHooks(
                    hotReloadClassLoader, resolveGoogleAppSourceDir(), oldHookIds);
        }
        moduleLog(Log.INFO, TAG, "Hot reloaded, build=" + BUILD_MARK
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
            case "systemui_contextual_search_nav_attach":
                return this::attachContextualSearchAfterNavigationBarAttached;
            case "systemui_contextual_search_nav_detach":
                return this::detachContextualSearchBeforeNavigationBarDetached;
            case "systemui_navigation_bar_transient_appearance":
            case "systemui_status_bar_transient_appearance":
                return this::preserveTransientBarAppearance;
            case "systemui_navigation_bar_show_transient":
                return this::preserveTransientBarAutoHide;
            case "systemui_a17_back_background_status_reset":
                return this::neutralizeBrokenBackAnimationStatusBarReset;
            case "systemui_navigation_bar_gesture_insets":
                return this::restoreNavigationBarGestureInsets;
            case "systemui_navigation_bar_controller_create":
                return this::reconcileAfterNavigationBarCreate;
            case "systemui_navigation_bar_controller_remove":
                return this::reconcileAfterNavigationBarRemove;
            case "systemui_navigation_bar_controller_onNavigationModeChanged":
                return this::reconcileAfterNavigationModeChanged;
            case "miui_home_gesture_stub_layout_params":
                return this::restoreMiuiHomeGestureStubTouchableLayout;
            case "miui_home_gesture_stub_trigger_region":
                return this::customizeMiuiHomeGestureStubTriggerRegion;
            case "miui_home_gesture_stub_trigger_touch_region":
                return this::customizeMiuiHomeGestureStubTriggerTouchRegion;
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
            case "miui_home_return_home_same_icon_parallel":
                return this::routeMiuiHomeReturnHomeSameIconParallel;
            case "miui_home_return_home_fresh_open":
                return this::forceMiuiHomeReturnHomeFreshOpen;
            case "miui_home_return_home_cancel_direct":
                return this::wrapMiuiHomeReturnHomeDirectCancel;
            case "miui_home_drawer_state":
                return this::mirrorMiuiHomeDrawerState;
            case "miui_home_folder_open_state":
                return this::mirrorMiuiHomeFolderOpened;
            case "miui_home_folder_close_state":
                return this::mirrorMiuiHomeFolderClosed;
            case "miui_home_freeform_back_touchability":
                return this::restoreMiuiHomeFreeformBackTouchability;
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
            case "server_back_window_start_animation":
                return this::prepareOpeningTaskFragment;
            case "server_a17_opening_surface_visibility":
                return this::restoreA17OpeningSurfaceVisibility;
            case "server_freeform_prepare_role_normalization":
                return this::normalizeFreeformCrossActivityTransitionInfo;
            case "server_schedule_animation_prepare_transition":
                return this::interceptScheduleAnimationPrepareTransition;
            case "server_back_navigation_done_cleanup":
                return this::cleanupSkippedRemoteAnimationOnNavigationDone;
            case "server_return_home_touch_occlusion":
                return this::allowCommittedReturnHomeTouchThrough;
            case "server_predictive_opt_in_metadata":
            case "predictive_opt_in_system_server":
                return this::injectSelectedPredictiveBackMetadata;
            case "server_contextual_search_startup_gate":
                return this::enableContextualSearchServiceAtBoot;
            case "server_contextual_search_start":
                return this::bridgeContextualSearchSystemUiCall;
            case "server_contextual_search_state":
                return this::bridgeContextualSearchProviderCall;
            case "server_contextual_search_permission":
                return this::scopeContextualSearchPermission;
            case "server_contextual_search_provider":
                return this::scopeContextualSearchProvider;
            case "google_live_translate_system_feature":
                return this::overrideLiveTranslateSystemFeature;
            case "google_live_translate_action_visibility":
                return this::preserveLiveTranslateActionVisibility;
            case "google_live_translate_capability":
                return this::overrideLiveTranslateBooleanGate;
            case "google_lens_aim_screen_capability":
                return this::overrideGoogleLensScreenCapability;
            case "systemui_default_transition_start":
                return this::registerDefaultTransitionHandler;
            case "systemui_a17_default_transition_dispatch_start":
                // Retired experimental hook: Android 17 inlines this final overload.
                return XposedInterface.Chain::proceed;
            case "systemui_a17_transitions_play_open_capture":
                // Retired wrapper hook: ART also inlines it into processReadyQueue.
                return XposedInterface.Chain::proceed;
            case "systemui_a17_transitions_ready_open_capture":
                // Retired ready-queue hook: its compiled callers bypassed the entry.
                return XposedInterface.Chain::proceed;
            case "systemui_a17_launcher_overview_shown":
            case "systemui_a17_launcher_overview_hidden":
                // Native MiuiHome owns Android 17 Overview state through the
                // authenticated generation-bearing broadcast.
                return XposedInterface.Chain::proceed;
            case "systemui_a17_transition_ready_inner_open_capture":
                // Retired inner hook: compiled synthetic lambda callers bypassed it.
                return XposedInterface.Chain::proceed;
            case "systemui_a17_transition_player_ready_open_capture":
                return this::capturePostedDefaultOpenTransition;
            case "systemui_default_transition_merge":
                return this::trackMiuiOpenCloseMerge;
            case "systemui_back_send_event_guard":
                return this::guardDuplicateBackEvent;
            case "systemui_back_slide_start":
                return this::onCrossActivitySlideStart;
            case "systemui_back_slide_progress":
                return this::onCrossActivitySlideProgressRegistration;
            case "systemui_back_slide_post_commit":
                return this::onCrossActivitySlidePostCommit;
            case "systemui_back_slide_duration":
                return this::onCrossActivitySlideDuration;
            case "systemui_back_slide_finish":
                return this::onCrossActivitySlideFinish;
            case "systemui_back_color_root_apply":
                return this::onCrossActivityColorRootApply;
            case "systemui_back_color_root_scrim_creation":
                return this::keepFreeformScrimHiddenUntilFirstApply;
            case "systemui_cross_task_background":
                return this::tintCrossTaskBackground;
            case "systemui_oneui_cross_task_invoke":
                return this::onOneUiCrossTaskInvoked;
            case "systemui_oneui_cross_task_finish":
                return this::onOneUiCrossTaskFinished;
            case "systemui_back_prepare_reparent":
                return this::correctPredictiveBackPrepareReparent;
            case "systemui_back_prepared_target_arrival":
                return this::onPreparedBackTargetArrival;
            case "systemui_back_prepared_terminal":
                return this::onPreparedBackTerminal;
            case "systemui_back_prepared_transition_decision":
                return this::holdPreparedBackTransitionUntilTargets;
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
            case "systemui_back_panel_aosp_haptic":
                return this::replaceAospBackPanelHaptic;
            case "systemui_back_panel_aosp_view_haptic":
            case "systemui_back_panel_aosp_view_haptic_flags":
                return this::replaceAospBackPanelViewHaptic;
            case "shell_back_onBackNavigationInfoReceived":
                return this::onBackNavigationInfoReceived;
            case "shell_back_onBackAnimationFinished":
                return this::proceedShellAnimationLifecycle;
            case "shell_back_finishBackAnimation":
                return this::onShellAnimationFinished;
            case "systemui_navigation_bar_view_insets":
            case "server_back_promote_to_tf_if_needed":
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
                if (state.length >= 15) {
                    miuiFolderVisible = Boolean.TRUE.equals(state[14]);
                }
                if (state.length >= 16 && state[15] instanceof Object[]) {
                    pendingHotReloadContextualSearchNavigationBars = (Object[]) state[15];
                }
            }
        }
        restoreMiuiOverviewDismissTimeoutAfterHotReload();
        Object[][] inputState = inputStateObject instanceof Object[][]
                ? (Object[][]) inputStateObject : new Object[0][0];
        if (SYSTEM_UI.equals(processName)) {
            pendingHotReloadInputState = inputState;
            moduleLog(Log.INFO, TAG, "Deferred SystemUI hot reload lifecycle restoration"
                    + ", inputCount=" + inputState.length
                    + ", headlessLeaseCount="
                    + pendingHotReloadHeadlessState.length);
            return;
        }
        if (!(inputStateObject instanceof Object[][])) {
            moduleLog(Log.INFO, TAG, "No hot reload back input state to restore");
            return;
        }
        if (inputState.length == 0) {
            moduleLog(Log.INFO, TAG, "Hot reload back input state is empty; "
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
            moduleLog(Log.INFO, TAG, "Restored hot reload back input on main thread, count="
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
            moduleLog(Log.WARN, TAG, "Expired Recents dismiss deadline during hot reload"
                    + ", restoredOverviewVisible=true");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> restoreMiuiOverviewAfterDismissTimeout(deadline), remaining);
        moduleLog(Log.INFO, TAG, "Restored Recents dismiss timeout after hot reload"
                + ", remainingMs=" + remaining);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String loadedPackage = param.getPackageName();
        if (MIUI_HOME.equals(loadedPackage) && blockMiuiHomeXposedHooks()) {
            processName = loadedPackage;
            moduleLog(Log.WARN, TAG,
                    "Skipped every MiuiHome LSPosed hook on Android 17"
                            + ", sourceDir=" + param.getApplicationInfo().sourceDir);
            return;
        }
        if (SYSTEM_UI.equals(loadedPackage) || MIUI_HOME.equals(loadedPackage)
                || processName == null) {
            processName = loadedPackage;
        }
        moduleLog(Log.INFO, TAG, "Package loaded: " + loadedPackage
                + ", classLoader=" + param.getDefaultClassLoader()
                + ", sourceDir=" + param.getApplicationInfo().sourceDir);
        if (SYSTEM_UI.equals(loadedPackage)) {
            installSystemUiHooks(param.getDefaultClassLoader());
        } else if (MIUI_HOME.equals(loadedPackage)) {
            installMiuiHomeHooks(param.getDefaultClassLoader());
        } else if (GOOGLE_APP.equals(loadedPackage)) {
            installGoogleAppHooks(
                    param.getDefaultClassLoader(),
                    param.getApplicationInfo().sourceDir,
                    Collections.emptySet());
        }
    }

    protected void installMiuiHomeHooks(ClassLoader classLoader) {
        if (blockMiuiHomeXposedHooks()) {
            moduleLog(Log.WARN, TAG,
                    "Rejected MiuiHome hook installation on Android 17");
            return;
        }
        try {
            Context launcherContext = resolveCurrentApplicationContext(classLoader);
            if (launcherContext != null) {
                ensureMiuiHomeInputArbiterReceiver(launcherContext);
            } else {
                moduleLog(Log.WARN, TAG,
                        "MiuiHome application context is not available yet; "
                                + "Android 16 status receiver will retry from GestureStub");
            }
            Class<?> gestureStubClass = Class.forName(MIUI_HOME_GESTURE_STUB, false,
                    classLoader);
            try {
                hookMiuiHomeGestureStubTriggerRegion(gestureStubClass);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "MiuiHome trigger-region customization unavailable",
                        throwable);
            }
            try {
                hookMiuiHomeGestureStubTriggerTouchRegion(gestureStubClass);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "MiuiHome trigger touch-region customization unavailable",
                        throwable);
            }
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
                moduleLog(Log.WARN, TAG,
                        "Failed to install Xiaomi OPEN target binding",
                        throwable);
            }
            hookMiuiHomeReusedCloseOpen(classLoader);
            try {
                hookMiuiHomeTransitionContinuity(
                        classLoader, true, true, true);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to install MiuiHome element continuity",
                        throwable);
            }
            try {
                hookMiuiHomeUnifiedFinishEpoch(
                        classLoader, true, true, true);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to install MiuiHome finish-epoch hooks",
                        throwable);
            }
            try {
                hookMiuiHomePermissionMerge(classLoader);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to install MiuiHome permission merge",
                        throwable);
            }
            hookMiuiHomeGeometryFrames(classLoader, true, true);
            try {
                hookMiuiHomeTransitionSetupLeash(classLoader);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to install MiuiHome transition geometry hook",
                        throwable);
            }
            hookMiuiHomeStartTransactionApply(
                    Collections.emptySet());
            hookMiuiHomeReturnHomeSameIconParallel(classLoader);
            hookMiuiHomeReturnHomeFreshOpen(classLoader);
            hookMiuiHomeReturnHomeDirectCancel(classLoader);
            hookMiuiHomeDrawerState(classLoader);
            hookMiuiHomeFolderState(classLoader, true, true);
            hookMiuiHomeFreeformBackTouchability(classLoader);
            hookMiuiHomeEditingState(classLoader);
            hookMiuiHomeReturnHomeInitialize(classLoader);
            hookMiuiHomeReturnHomeLocalHandoff(classLoader);
            hookMiuiHomeReturnHomeWallpaperCommands(classLoader, true, true);
            moduleLog(Log.INFO, TAG, "Enabled MiuiHome native side input arbitration"
                    + ", preservedGestureStubInitialization=true"
                    + ", preservesNativeRedirect=true"
                    + ", blocksLegacyGestureProcessor=true"
                    + ", requiresAcceptedInputToken=true"
                    + ", systemUiOwnsCommittedGesture=true"
                    + ", mirrorsActualRecentsState=true"
                    + ", mirrorsTaskLaunchExit=true"
                    + ", mirrorsAuthenticatedFullscreenState=true"
                    + ", mirrorsDrawerState=true"
                    + ", mirrorsFolderState=true"
                    + ", preservesSmallWindowBackTouchability=true"
                    + ", mirrorsLauncherEditingState=true"
                    + ", mirrorsLauncherOpenBreakState=true"
                    + ", repairsNonReusableSameIconOpen=true"
                    + ", usesStandardLauncherBackCallback=true");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to install MiuiHome input arbitration", throwable);
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        processName = "system";
        moduleLog(Log.INFO, TAG, "System server starting, build=" + BUILD_MARK
                + ", classLoader=" + param.getClassLoader());
        installSystemServerHooks(param.getClassLoader());
    }
}
