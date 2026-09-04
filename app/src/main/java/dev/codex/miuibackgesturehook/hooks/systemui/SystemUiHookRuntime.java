package dev.codex.miuibackgesturehook.hooks.systemui;

import android.app.BroadcastOptions;
import android.app.ActivityThread;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.InsetsFrameProvider;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackProgressAnimator;
import android.window.BackTouchTracker;
import android.window.TransitionInfo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import io.github.libxposed.api.XposedInterface;

public abstract class SystemUiHookRuntime extends SystemUiInputRuntime {

    private volatile SystemUiPlatformImpl systemUiPlatformImpl;
    private volatile SharedPreferences contextualSearchStatePreferences;
    private volatile Context contextualSearchStateContext;
    private final SharedPreferences.OnSharedPreferenceChangeListener
            contextualSearchStatePreferenceListener = (preferences, key) -> {
                if (!PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS
                        .equals(key)) {
                    return;
                }
                contextualSearchPreferences = preferences;
                Context context = contextualSearchStateContext;
                SystemUiPlatformImpl implementation = systemUiPlatformImpl;
                if (context == null || implementation == null) {
                    return;
                }
                Handler mainHandler = new Handler(context.getMainLooper());
                mainHandler.post(() -> {
                    if (contextualSearchStateContext != context) {
                        return;
                    }
                    if (!implementation.nativeLauncherOwnsContextualSearchLongPress()) {
                        refreshContextualSearchInputReceivers();
                        return;
                    }
                    publishSystemUiInputArbiterState(context,
                            systemUiInputArbiterMonitorCount.get() > 0,
                            "contextualSearchPreference:" + key);
                });
            };


    protected void installSystemUiHooks(ClassLoader classLoader) {
        try {
            selectSystemUiPlatformImpl(classLoader);
            hookContextualSearchNavigationBar(classLoader, true, true);
            Context systemUiContext = resolveCurrentApplicationContext(classLoader);
            if (systemUiContext != null) {
                ensureMiuiOverviewStateReceiver(systemUiContext);
            } else {
                moduleLog(Log.WARN, TAG,
                        "SystemUI application context is not available yet; "
                                + "status receiver will retry from the input owner");
            }
            hookMiuiOverviewProxy(classLoader);
            hookNavigationBarTransientAutoHide(classLoader);
            hookNavigationBarTransientAppearance(classLoader);
            hookStatusBarTransientAppearance(classLoader);
            hookNavigationBarGestureInsets(classLoader);
            hookPlatformBackAnimationStatusBarReset(classLoader);
            hookEdgeBackGestureHandler(classLoader, true, true, true);
            hookAospBackPanelHaptic(classLoader);
            hookAospBackPanelViewHaptic(classLoader);
            hookNavigationBarControllerCreate(classLoader);
            hookNavigationBarControllerRemove(classLoader);
            hookNavigationBarControllerMode(classLoader);
            hookShellBackAnimation(classLoader);
            hookBackAnimationSendBackEvent(classLoader);
            hookDefaultTransitionHandler(classLoader);
            hookDefaultTransitionImplMerge(classLoader);
            moduleLog(Log.INFO, TAG, "Installed SystemUI AOSP back restoration hooks, build="
                    + BUILD_MARK + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to install SystemUI hooks", throwable);
        }
    }

    protected void hookContextualSearchNavigationBar(
            ClassLoader classLoader, boolean hookAttach, boolean hookDetach) {
        try {
            Class<?> navigationBarClass = Class.forName(
                    NAVIGATION_BAR, false, classLoader);
            if (hookDetach) {
                Method detached = navigationBarClass.getDeclaredMethod("onViewDetached");
                detached.setAccessible(true);
                recordHookHandle(hook(detached)
                        .setId("systemui_contextual_search_nav_detach")
                        .intercept(this::detachContextualSearchBeforeNavigationBarDetached));
            }
            if (hookAttach) {
                Method attached = navigationBarClass.getDeclaredMethod("onViewAttached");
                attached.setAccessible(true);
                recordHookHandle(hook(attached)
                        .setId("systemui_contextual_search_nav_attach")
                        .intercept(this::attachContextualSearchAfterNavigationBarAttached));
            }
            moduleLog(Log.INFO, TAG,
                    "Installed contextual-search NavigationBar lifecycle hooks"
                            + ", attach=" + hookAttach + ", detach=" + hookDetach);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search NavigationBar lifecycle unavailable", throwable);
        }
    }

    protected Object attachContextualSearchAfterNavigationBarAttached(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!requireSystemUiPlatformImpl()
                .nativeLauncherOwnsContextualSearchLongPress()) {
            attachContextualSearchInputReceiver(chain.getThisObject());
        }
        return result;
    }

    protected Object detachContextualSearchBeforeNavigationBarDetached(
            XposedInterface.Chain chain) throws Throwable {
        forgetContextualSearchNavigationBar(chain.getThisObject());
        detachContextualSearchInputReceiver(chain.getThisObject());
        return chain.proceed();
    }

    @Override
    protected void restoreContextualSearchInputReceivers(Object[] navigationBars) {
        if (requireSystemUiPlatformImpl()
                .nativeLauncherOwnsContextualSearchLongPress()) {
            return;
        }
        super.restoreContextualSearchInputReceivers(navigationBars);
    }

    protected synchronized void ensureContextualSearchStatePreferenceListener(
            Context context) {
        if (context == null || contextualSearchStatePreferences != null) {
            return;
        }
        try {
            SharedPreferences preferences = getRemotePreferences(
                    PredictiveBackPreferences.GROUP);
            preferences.registerOnSharedPreferenceChangeListener(
                    contextualSearchStatePreferenceListener);
            contextualSearchPreferences = preferences;
            contextualSearchStatePreferences = preferences;
            contextualSearchStateContext = context.getApplicationContext();
            moduleLog(Log.INFO, TAG,
                    "Registered live contextual-search preference listener");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Live contextual-search preference listener unavailable",
                    throwable);
        }
    }

    protected synchronized void releaseContextualSearchStatePreferenceListener() {
        SharedPreferences preferences = contextualSearchStatePreferences;
        contextualSearchStatePreferences = null;
        contextualSearchStateContext = null;
        contextualSearchPreferences = null;
        if (preferences == null) {
            return;
        }
        try {
            preferences.unregisterOnSharedPreferenceChangeListener(
                    contextualSearchStatePreferenceListener);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to unregister live contextual-search preference listener",
                    throwable);
        }
    }

    protected void selectSystemUiPlatformImpl(ClassLoader classLoader) throws Exception {
        Class<?> edgeHandlerClass = Class.forName(EDGE_BACK_GESTURE_HANDLER,
                false, classLoader);
        SystemUiPlatformImpl selected = SystemUiAndroid17Impl.matches(
                edgeHandlerClass, classLoader)
                ? new SystemUiAndroid17Impl() : new SystemUiAndroid16Impl();
        SystemUiPlatformImpl previous = systemUiPlatformImpl;
        systemUiPlatformImpl = selected;
        if (previous != null && previous != selected) {
            previous.destroy();
        }
        moduleLog(Log.INFO, TAG, "Selected SystemUI platform implementation: "
                + selected.name());
    }

    protected SystemUiPlatformImpl requireSystemUiPlatformImpl() {
        SystemUiPlatformImpl implementation = systemUiPlatformImpl;
        if (implementation == null) {
            throw new IllegalStateException("SystemUI platform implementation not selected");
        }
        return implementation;
    }

    protected String defaultTransitionOpenCaptureHookId() {
        return requireSystemUiPlatformImpl().defaultTransitionOpenCaptureHookId();
    }

    protected String systemUiInputArbiterStateAction() {
        SystemUiPlatformImpl implementation = systemUiPlatformImpl;
        return implementation == null
                ? MODULE_SYSTEMUI_INPUT_ARBITER_STATE
                : implementation.systemUiInputArbiterStateAction(
                        MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
    }

    protected void hookPlatformBackAnimationStatusBarReset(ClassLoader classLoader) {
        try {
            Method reset = requireSystemUiPlatformImpl()
                    .brokenBackAnimationStatusBarResetMethod(classLoader);
            if (reset == null) {
                return;
            }
            recordHookHandle(hook(reset)
                    .setId("systemui_a17_back_background_status_reset")
                    .intercept(this::neutralizeBrokenBackAnimationStatusBarReset));
            moduleLog(Log.INFO, TAG,
                    "Neutralized stripped Android 17 BackAnimationBackground status reset");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Android 17 BackAnimationBackground status reset",
                    throwable);
        }
    }

    protected Object neutralizeBrokenBackAnimationStatusBarReset(
            XposedInterface.Chain chain) {
        // Xiaomi's companion customize/set methods are no-ops, so there is no active
        // customization to reset. Preserve the app-requested status-bar appearance.
        return null;
    }

    @Override
    protected Object findNativeEdgeBackPlugin(Object edgeBackGestureHandler) throws Exception {
        return requireSystemUiPlatformImpl().findNativeEdgeBackPlugin(edgeBackGestureHandler);
    }

    @Override
    protected void prepareNativeBackPanel(Object edgeBackGestureHandler,
                                          Object plugin) throws Exception {
        requireSystemUiPlatformImpl().prepareNativeBackPanel(
                edgeBackGestureHandler, plugin);
    }

    @Override
    protected void updateNativeBackPanelDisplaySize(Object edgeBackGestureHandler,
                                                    Object plugin) throws Exception {
        requireSystemUiPlatformImpl().updateDisplaySize(
                edgeBackGestureHandler, plugin);
    }

    @Override
    protected boolean isNavigationOverlayExcluded(Object edgeBackGestureHandler,
                                                   int x, int y) throws Exception {
        return requireSystemUiPlatformImpl().isNavigationOverlayExcluded(
                edgeBackGestureHandler, x, y);
    }

    @Override
    protected void injectPlatformLegacyBackKey(
            Object controller, int displayId) throws Exception {
        requireSystemUiPlatformImpl().injectLegacyBackKey(controller, displayId);
    }

    protected void destroySystemUiPlatformImpl() {
        SystemUiPlatformImpl implementation = systemUiPlatformImpl;
        if (implementation != null) {
            implementation.destroy();
        }
    }

    protected void hookMiuiOverviewProxy(ClassLoader classLoader) {
        try {
            Class<?> proxyClass = Class.forName(MIUI_OVERVIEW_PROXY, false, classLoader);
            Method method = proxyClass.getDeclaredMethod("onTransact",
                    int.class, Parcel.class, Parcel.class, int.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_block_miui_gesture_line_progress")
                    .intercept(this::interceptMiuiOverviewProxyTransact));
            moduleLog(Log.INFO, TAG, "Hooked MiuiOverviewProxy.onTransact");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook MiuiOverviewProxy", throwable);
        }
    }

    protected void hookDefaultTransitionHandler(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(DEFAULT_TRANSITION_HANDLER, false,
                    classLoader);
            resolveDefaultTransitionSnapshotReflection(handlerClass);
            SystemUiPlatformImpl implementation = requireSystemUiPlatformImpl();
            if (implementation.captureOpenFromTransitionsOwner()) {
                Class<?> playerClass = Class.forName(
                        "com.android.wm.shell.transition.Transitions$TransitionPlayerImpl",
                        false, classLoader);
                Method onTransitionReady = playerClass.getDeclaredMethod(
                        "onTransitionReady", IBinder.class, TransitionInfo.class,
                        SurfaceControl.Transaction.class, SurfaceControl.Transaction.class);
                onTransitionReady.setAccessible(true);
                recordHookHandle(hook(onTransitionReady)
                        .setId(implementation.defaultTransitionOpenCaptureHookId())
                        .intercept(this::capturePostedDefaultOpenTransition));
                moduleLog(Log.INFO, TAG,
                        "Hooked Android 17 TransitionPlayerImpl.onTransitionReady OPEN capture");
                return;
            }
            Class<?> finishCallbackClass = Class.forName(
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback",
                    false, classLoader);
            Method startAnimation = handlerClass.getDeclaredMethod("startAnimation",
                    IBinder.class, TransitionInfo.class, SurfaceControl.Transaction.class,
                    SurfaceControl.Transaction.class, finishCallbackClass);
            startAnimation.setAccessible(true);
            recordHookHandle(hook(startAnimation)
                    .setId(implementation.defaultTransitionOpenCaptureHookId())
                    .intercept(this::registerDefaultTransitionHandler));
            moduleLog(Log.INFO, TAG, "Hooked exact DefaultTransitionHandler.startAnimation"
                    + ", impl=" + implementation.name());
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook DefaultTransitionHandler", throwable);
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    protected synchronized void resolveDefaultTransitionSnapshotReflection(
            Class<?> handlerClass) throws ReflectiveOperationException {
        if (defaultTransitionAnimationsField != null
                && defaultTransitionAnimationSizeField != null
                && defaultTransitionAnimExecutorField != null
                && animatorCanReverseMethod != null) {
            return;
        }
        String animatorsFieldName = requireSystemUiPlatformImpl()
                .defaultTransitionAnimatorsFieldName();
        Field animationsField = handlerClass.getDeclaredField(animatorsFieldName);
        Field animationSizeField = handlerClass.getDeclaredField("mAnimationSize");
        Field animExecutorField = handlerClass.getDeclaredField("mAnimExecutor");
        // Animator.canReverse() is a boot-classpath hidden API. LSPosed loads this code inside
        // SystemUI with hidden-API access; the public SDK stub does not expose the method.
        Method canReverseMethod = Animator.class.getDeclaredMethod("canReverse");
        animationsField.setAccessible(true);
        animationSizeField.setAccessible(true);
        animExecutorField.setAccessible(true);
        canReverseMethod.setAccessible(true);
        defaultTransitionAnimationsField = animationsField;
        defaultTransitionAnimationSizeField = animationSizeField;
        defaultTransitionAnimExecutorField = animExecutorField;
        animatorCanReverseMethod = canReverseMethod;
    }

    protected Object registerDefaultTransitionHandler(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (!Boolean.TRUE.equals(result)) {
            return result;
        }
        if (requireSystemUiPlatformImpl().captureOpenFromTransitionsOwner()) {
            // Neutralize an Android 16-style handle left behind by hot reload. Android 17
            // captures from the non-inlined Transitions owner after handler selection.
            return result;
        }
        try {
            captureRunningOpenTransition(chain.getThisObject(), chain.getArg(0),
                    chain.getArg(1));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to capture Xiaomi OPEN transition snapshot",
                    throwable);
        }
        return result;
    }

    protected Object capturePostedDefaultOpenTransition(XposedInterface.Chain chain)
            throws Throwable {
        Object token = chain.getArg(0);
        Object info = chain.getArg(1);
        int transitionType = info instanceof TransitionInfo
                ? ((TransitionInfo) info).getType() : -1;
        boolean open = transitionType == 1;
        if (open) {
            moduleLog(Log.INFO, TAG,
                    "Entered Android 17 TransitionPlayer OPEN ready hook"
                            + ", token=" + shortObject(token)
                            + ", thread=" + Thread.currentThread().getName());
        }
        Object result = chain.proceed();
        if (!open && transitionType != 2) {
            return result;
        }
        try {
            Object transitions = readField(chain.getThisObject(), "this$0");
            Object executorObject = readField(transitions, "mMainExecutor");
            if (!(executorObject instanceof Executor)) {
                throw new IllegalStateException("Unexpected Shell main executor: "
                        + shortObject(executorObject));
            }
            ((Executor) executorObject).execute(() -> {
                if (open) {
                    capturePostedDefaultOpenTransitionOnOwner(
                            transitions, token, info);
                } else {
                    observePostedMiuiOpenCloseMerge(info);
                }
            });
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to enqueue Android 17 OPEN transition capture",
                    throwable);
        }
        return result;
    }

    private void capturePostedDefaultOpenTransitionOnOwner(
            Object transitions, Object token, Object info) {
        try {
            Object knownObject = readField(transitions, "mKnownTransitions");
            Object activeTransition = knownObject instanceof Map
                    ? ((Map<?, ?>) knownObject).get(token) : null;
            Object handler = activeTransition == null
                    ? null : readField(activeTransition, "mHandler");
            moduleLog(Log.INFO, TAG,
                    "Resolved Android 17 OPEN owner after dispatch"
                            + ", token=" + shortObject(token)
                            + ", active=" + shortObject(activeTransition)
                            + ", handler=" + shortObject(handler)
                            + ", handlerClass="
                            + (handler == null ? "null" : handler.getClass().getName())
                            + ", thread=" + Thread.currentThread().getName());
            if (handler == null) {
                return;
            }
            String handlerClass = handler.getClass().getName();
            if (DEFAULT_TRANSITION_HANDLER.equals(handlerClass)) {
                captureRunningOpenTransition(handler, token, info, transitions);
            } else if ("com.android.wm.shell.common.transition.MiuiTransitionHandler"
                    .equals(handlerClass)) {
                captureRunningMiuiSpringOpenTransition(
                        handler, token, info);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture Android 17 posted OPEN transition snapshot",
                    throwable);
        }
    }

    private void captureRunningMiuiSpringOpenTransition(
            Object handler, Object token, Object info)
            throws Exception {
        Object transitionMapObject = readField(handler, "mTransitions");
        Object executorObject = readField(handler, "mAnimExecutor");
        if (!(transitionMapObject instanceof Map)
                || ((Map<?, ?>) transitionMapObject).get(token) != info
                || !(executorObject instanceof Executor)
                || !(info instanceof TransitionInfo)) {
            moduleLog(Log.INFO, TAG,
                    "Skipped Android 17 Xiaomi OPEN without exact active owner"
                            + ", token=" + shortObject(token));
            return;
        }
        Class<?> managerClass = Class.forName(
                "com.android.wm.shell.common.transition.animation."
                        + "MiuiSpringAnimationManager",
                false, handler.getClass().getClassLoader());
        Method getInstance = managerClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object manager = getInstance.invoke(null);
        int transitionDebugId = ((TransitionInfo) info).getDebugId();
        long generation = openSnapshotGeneration.get();
        if (!acceptingOpenSnapshots) {
            return;
        }
        openSnapshotLifecycleEpoch.incrementAndGet();
        ((Executor) executorObject).execute(() ->
                captureRunningMiuiSpringOpenTransitionOnAnim(
                        handler, manager, token, info,
                        transitionDebugId, generation,
                        (Executor) executorObject));
    }

    private void captureRunningMiuiSpringOpenTransitionOnAnim(
            Object handler, Object manager,
            Object token, Object info, int transitionDebugId,
            long generation, Executor animExecutor) {
        try {
            if (!acceptingOpenSnapshots
                    || generation != openSnapshotGeneration.get()) {
                return;
            }
            Object groupsByTransitionObject = readField(
                    manager, "mRunningAnimGroupMapByTransition");
            Object groupMapObject = groupsByTransitionObject instanceof Map
                    ? ((Map<?, ?>) groupsByTransitionObject).get(
                    Integer.valueOf(transitionDebugId)) : null;
            if (!(groupMapObject instanceof Map)
                    || ((Map<?, ?>) groupMapObject).isEmpty()) {
                moduleLog(Log.INFO, TAG,
                        "Skipped Android 17 Xiaomi OPEN without running spring groups"
                                + ", transitionId=" + transitionDebugId);
                return;
            }
            Object[] groups = ((Map<?, ?>) groupMapObject).values().toArray();
            Object[] springAnimations = collectRunningMiuiSpringAnimations(
                    groups, true);
            if (springAnimations.length == 0) {
                return;
            }
            Object shellTransitionInfo = readField(
                    groups[0], "mMiuiShellTransitionInfo");
            OpenTransitionSnapshot snapshot = new OpenTransitionSnapshot(
                    token, info, springAnimations.length, animExecutor, generation,
                    handler, manager, groups, springAnimations,
                    shellTransitionInfo, transitionDebugId);
            OpenTransitionSnapshot previous = runningOpenTransitions.put(token, snapshot);
            if (previous != null) {
                invalidateOpenTransitionSnapshot(previous, "replaced");
            }
            verifyAndActivateMiuiSpringOpenTransition(snapshot);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture Android 17 Xiaomi spring OPEN snapshot",
                    throwable);
        }
    }

    private Object[] collectRunningMiuiSpringAnimations(
            Object[] groups, boolean requireRunning) throws Exception {
        ArrayList<Object> animations = new ArrayList<>();
        for (Object group : groups) {
            Object wrappersObject = readField(group, "mMiuiSpringAnimations");
            if (!(wrappersObject instanceof List)
                    || ((List<?>) wrappersObject).isEmpty()) {
                return new Object[0];
            }
            for (Object wrapper : (List<?>) wrappersObject) {
                Method getSpringAnimation = wrapper.getClass()
                        .getDeclaredMethod("getMiuiSpringAnimation");
                getSpringAnimation.setAccessible(true);
                Object spring = getSpringAnimation.invoke(wrapper);
                if (spring == null
                        || (requireRunning && !isMiuiSpringRunning(spring))) {
                    return new Object[0];
                }
                animations.add(spring);
            }
        }
        return animations.toArray();
    }

    private boolean isMiuiSpringRunning(Object spring) throws Exception {
        Method isRunning = spring.getClass().getMethod("isRunning");
        isRunning.setAccessible(true);
        return Boolean.TRUE.equals(isRunning.invoke(spring));
    }

    private void verifyAndActivateMiuiSpringOpenTransition(
            OpenTransitionSnapshot snapshot) {
        try {
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || snapshot.state.get() != OPEN_SNAPSHOT_PENDING) {
                invalidateOpenTransitionSnapshot(snapshot, "staleValidator");
                return;
            }
            Object transitionMapObject = readField(
                    snapshot.platformHandler, "mTransitions");
            Object groupsByTransitionObject = readField(
                    snapshot.animationManager,
                    "mRunningAnimGroupMapByTransition");
            Object groupMapObject = groupsByTransitionObject instanceof Map
                    ? ((Map<?, ?>) groupsByTransitionObject).get(
                    Integer.valueOf(snapshot.transitionDebugId)) : null;
            if (!(transitionMapObject instanceof Map)
                    || ((Map<?, ?>) transitionMapObject).get(snapshot.token)
                    != snapshot.transitionInfo
                    || !(groupMapObject instanceof Map)
                    || ((Map<?, ?>) groupMapObject).size()
                    != snapshot.animationGroups.length) {
                invalidateOpenTransitionSnapshot(snapshot, "ownerChanged");
                return;
            }
            for (Object group : snapshot.animationGroups) {
                if (!((Map<?, ?>) groupMapObject).containsValue(group)) {
                    invalidateOpenTransitionSnapshot(snapshot, "groupChanged");
                    return;
                }
            }
            Object[] currentSprings = collectRunningMiuiSpringAnimations(
                    snapshot.animationGroups, true);
            if (currentSprings.length != snapshot.springAnimations.length) {
                invalidateOpenTransitionSnapshot(snapshot, "animationSetChanged");
                return;
            }
            for (Object spring : snapshot.springAnimations) {
                if (!containsIdentity(currentSprings, spring)) {
                    invalidateOpenTransitionSnapshot(snapshot, "animationChanged");
                    return;
                }
            }
            attachMiuiOpenEndListener(snapshot);
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || !snapshot.state.compareAndSet(
                    OPEN_SNAPSHOT_PENDING, OPEN_SNAPSHOT_ACTIVE)) {
                removeOpenTransitionListeners(snapshot);
                invalidateOpenTransitionSnapshot(snapshot, "notReversible");
                return;
            }
            moduleLog(Log.INFO, TAG,
                    "Published reversible Android 17 Xiaomi OPEN snapshot"
                            + ", springAnimationCount="
                            + snapshot.originalAnimatorCount
                            + ", transitionId="
                            + snapshot.transitionDebugId);
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "verificationFailure");
            moduleLog(Log.WARN, TAG,
                    "Failed to verify Android 17 Xiaomi spring OPEN snapshot",
                    throwable);
        }
    }

    private boolean containsIdentity(Object[] values, Object expected) {
        for (Object value : values) {
            if (value == expected) {
                return true;
            }
        }
        return false;
    }

    private void attachMiuiOpenEndListener(OpenTransitionSnapshot snapshot)
            throws Exception {
        if (snapshot.springAnimations.length == 0) {
            throw new IllegalStateException("Missing Xiaomi spring animation identity");
        }
        ClassLoader classLoader = snapshot.platformHandler.getClass().getClassLoader();
        Class<?> listenerInterface = Class.forName(
                "com.android.wm.shell.common.transition.animation.spring."
                        + "MiuiDynamicAnimation$OnAnimationEndListener",
                false, classLoader);
        Object listener = Proxy.newProxyInstance(classLoader,
                new Class<?>[]{listenerInterface}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "equals":
                                return args != null && args.length == 1
                                        && proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "toString":
                                return "MiuiOpenSpringEndListener@"
                                        + Integer.toHexString(
                                        System.identityHashCode(proxy));
                            default:
                                return null;
                        }
                    }
                    if ("onAnimationEnd".equals(method.getName())) {
                        boolean canceled = args != null && args.length > 1
                                && Boolean.TRUE.equals(args[1]);
                        onMiuiOpenSpringEnded(snapshot, canceled);
                    }
                    return null;
                });
        snapshot.springEndListener = listener;
        try {
            for (Object spring : snapshot.springAnimations) {
                Method addEndListener = spring.getClass().getMethod(
                        "addEndListener", listenerInterface);
                addEndListener.setAccessible(true);
                addEndListener.invoke(spring, listener);
            }
        } catch (Throwable throwable) {
            removeOpenTransitionListeners(snapshot);
            if (throwable instanceof Exception) {
                throw (Exception) throwable;
            }
            throw new ReflectiveOperationException(
                    "Failed to attach Xiaomi spring end listener", throwable);
        }
    }

    private void onMiuiOpenSpringEnded(
            OpenTransitionSnapshot snapshot, boolean canceled) {
        if (snapshot.state.get() != OPEN_SNAPSHOT_ACTIVE
                || runningOpenTransitions.get(snapshot.token) != snapshot) {
            return;
        }
        try {
            if (canceled) {
                invalidateOpenTransitionSnapshot(snapshot, "cancel");
                return;
            }
            if (snapshot.miuiShellTransitionInfo != null
                    && Boolean.TRUE.equals(readField(
                    snapshot.miuiShellTransitionInfo,
                    "mIsMergeOtherTransition"))) {
                invalidateOpenTransitionSnapshot(snapshot, "reverseMerge");
                correlateLegacyBackMerge(snapshot.transitionInfo);
                return;
            }
            for (Object spring : snapshot.springAnimations) {
                if (isMiuiSpringRunning(spring)) {
                    return;
                }
            }
            // The native final spring listener may post transition cleanup to the
            // Shell main executor before this module listener runs. At this point
            // immutable spring identity, cancellation, merge state, snapshot state,
            // and gesture ownership are the authoritative natural-end proof; the
            // handler's token map is allowed to have completed normally already.
            invalidateOpenTransitionSnapshot(snapshot, "end");
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "endVerificationFailure");
            moduleLog(Log.WARN, TAG,
                    "Failed to verify Android 17 Xiaomi OPEN spring end",
                    throwable);
        }
    }

    private void observePostedMiuiOpenCloseMerge(Object incomingInfo) {
        for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
            if (!snapshot.miuiSpring
                    || snapshot.state.get() != OPEN_SNAPSHOT_ACTIVE
                    || snapshot.miuiShellTransitionInfo == null) {
                continue;
            }
            try {
                if (!Boolean.TRUE.equals(readField(
                        snapshot.miuiShellTransitionInfo,
                        "mIsMergeOtherTransition"))) {
                    continue;
                }
                invalidateOpenTransitionSnapshot(snapshot, "reverseMerge");
                correlateLegacyBackMerge(snapshot.transitionInfo);
                moduleLog(Log.INFO, TAG,
                        "Observed accepted Android 17 Xiaomi OPEN/CLOSE merge"
                                + ", runningTransitionId="
                                + snapshot.transitionDebugId
                                + ", incomingInfo="
                                + shortObject(incomingInfo));
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to verify Android 17 Xiaomi OPEN/CLOSE merge",
                        throwable);
            }
        }
    }

    protected void captureRunningOpenTransition(Object handler, Object token, Object info)
            throws Exception {
        captureRunningOpenTransition(handler, token, info, null);
    }

    protected void captureRunningOpenTransition(
            Object handler, Object token, Object info, Object shellTransitions)
            throws Exception {
        if (handler == null || token == null || info == null) {
            return;
        }
        resolveDefaultTransitionSnapshotReflection(handler.getClass());
        if (!(info instanceof TransitionInfo) || ((TransitionInfo) info).getType() != 1) {
            return;
        }
        openSnapshotLifecycleEpoch.incrementAndGet();
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
            moduleLog(Log.INFO, TAG, "Skipped Xiaomi OPEN snapshot without animator list"
                    + ", token=" + shortObject(token)
                    + ", mapClass=" + animationsObject.getClass().getName()
                    + ", mapSize=" + ((Map<?, ?>) animationsObject).size()
                    + ", containsToken="
                    + ((Map<?, ?>) animationsObject).containsKey(token)
                    + ", value=" + shortObject(animatorListObject));
            return;
        }
        List<?> animatorList = (List<?>) animatorListObject;
        Animator[] animators = new Animator[animatorList.size()];
        for (int index = 0; index < animatorList.size(); index++) {
            Object entry = animatorList.get(index);
            Animator animator = requireSystemUiPlatformImpl()
                    .unwrapDefaultTransitionAnimator(entry);
            if (animator == null) {
                throw new IllegalStateException("Unexpected transition animator="
                        + shortObject(entry));
            }
            animators[index] = animator;
        }
        Object originalSizeObject = ((Map<?, ?>) animationSizeObject).get(token);
        int originalSize = originalSizeObject instanceof Number
                ? ((Number) originalSizeObject).intValue() : 0;
        if (animators.length == 0) {
            moduleLog(Log.INFO, TAG, "Skipped Xiaomi OPEN snapshot with empty animator list"
                    + ", token=" + shortObject(token)
                    + ", originalAnimatorCount=" + originalSize);
            return;
        }
        long generation = openSnapshotGeneration.get();
        if (!acceptingOpenSnapshots) {
            return;
        }
        OpenTransitionSnapshot snapshot = new OpenTransitionSnapshot(token, info, animators,
                originalSize, (Executor) executorObject, generation,
                shellTransitions);
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

    protected void verifyAndActivateOpenTransition(OpenTransitionSnapshot snapshot) {
        try {
            if (!acceptingOpenSnapshots
                    || snapshot.generation != openSnapshotGeneration.get()
                    || runningOpenTransitions.get(snapshot.token) != snapshot
                    || snapshot.state.get() != OPEN_SNAPSHOT_PENDING) {
                invalidateOpenTransitionSnapshot(snapshot, "staleValidator");
                return;
            }
            if (snapshot.animators.length != snapshot.originalAnimatorCount) {
                moduleLog(Log.INFO, TAG, "Skipped partial Xiaomi OPEN transition snapshot"
                        + ", currentAnimatorCount=" + snapshot.animators.length
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
            moduleLog(Log.INFO, TAG, "Published reversible Xiaomi OPEN transition snapshot"
                    + ", animatorCount=" + snapshot.animators.length
                    + ", info=" + shortObject(snapshot.transitionInfo));
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "verificationFailure");
            moduleLog(Log.WARN, TAG, "Failed to verify Xiaomi OPEN transition snapshot",
                    throwable);
        }
    }

    @Override
    protected void onOpenTransitionAnimatorEnded(
            OpenTransitionSnapshot snapshot, boolean isReverse) {
        if (isReverse || snapshot.shellTransitions == null) {
            super.onOpenTransitionAnimatorEnded(snapshot, isReverse);
            return;
        }
        if (!snapshot.endSignalQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            Object executorObject = readField(
                    snapshot.shellTransitions, "mMainExecutor");
            if (!(executorObject instanceof Executor)) {
                throw new IllegalStateException(
                        "Unexpected Transitions main executor="
                                + shortObject(executorObject));
            }
            ((Executor) executorObject).execute(() ->
                    registerDefaultOpenEndAfterShellIdle(snapshot));
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot,
                    "idleRegistrationFailure");
            moduleLog(Log.WARN, TAG,
                    "Failed to enqueue Android 17 default OPEN idle end",
                    throwable);
        }
    }

    private void registerDefaultOpenEndAfterShellIdle(
            OpenTransitionSnapshot snapshot) {
        if (snapshot.state.get() != OPEN_SNAPSHOT_ACTIVE
                || runningOpenTransitions.get(snapshot.token) != snapshot) {
            return;
        }
        try {
            Method runOnIdle = snapshot.shellTransitions.getClass()
                    .getDeclaredMethod("runOnIdle", Runnable.class);
            runOnIdle.setAccessible(true);
            runOnIdle.invoke(snapshot.shellTransitions, (Runnable) () -> {
                if (snapshot.state.get() == OPEN_SNAPSHOT_ACTIVE
                        && runningOpenTransitions.get(snapshot.token) == snapshot) {
                    invalidateOpenTransitionSnapshot(snapshot, "end");
                }
            });
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot,
                    "idleRegistrationFailure");
            moduleLog(Log.WARN, TAG,
                    "Failed to register Android 17 default OPEN idle end",
                    throwable);
        }
    }

    protected void invalidateOpenTransitionSnapshot(OpenTransitionSnapshot snapshot,
                                                    String reason) {
        if (snapshot == null) {
            return;
        }
        boolean normalEnd = "end".equals(reason);
        if (!normalEnd) {
            openSnapshotLifecycleEpoch.incrementAndGet();
        }
        int previousState = snapshot.state.getAndSet(OPEN_SNAPSHOT_INVALID);
        if (previousState == OPEN_SNAPSHOT_INVALID) {
            return;
        }
        runningOpenTransitions.remove(snapshot.token, snapshot);
        AnimatorListenerAdapter listener = snapshot.listener;
        if (listener != null || snapshot.springEndListener != null) {
            try {
                snapshot.animExecutor.execute(() -> removeOpenTransitionListeners(snapshot));
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to remove Xiaomi OPEN snapshot listeners"
                        + ", reason=" + reason, throwable);
            }
        }
        moduleLog(Log.INFO, TAG, "Invalidated Xiaomi OPEN transition snapshot"
                + ", reason=" + reason
                    + ", animatorCount=" + snapshot.animationCount());
        if (previousState == OPEN_SNAPSHOT_ACTIVE && normalEnd) {
            long handoffEpoch = openSnapshotLifecycleEpoch.incrementAndGet();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isOpenEndHandoffCurrent(handoffEpoch)) {
                    return;
                }
                for (NativeBackInputMonitor monitor
                        : new ArrayList<>(nativeInputMonitors.values())) {
                    monitor.driver.onInAppOpenTransitionEnded(snapshot, handoffEpoch);
                }
            });
        }
    }

    protected void removeOpenTransitionListeners(OpenTransitionSnapshot snapshot) {
        AnimatorListenerAdapter listener = snapshot.listener;
        if (listener != null) {
            for (Animator animator : snapshot.animators) {
                animator.removeListener(listener);
            }
            snapshot.listener = null;
        }
        Object springEndListener = snapshot.springEndListener;
        if (springEndListener == null) {
            return;
        }
        try {
            Class<?> listenerInterface = springEndListener.getClass()
                    .getInterfaces()[0];
            for (Object spring : snapshot.springAnimations) {
                Method removeEndListener = spring.getClass().getMethod(
                        "removeEndListener", listenerInterface);
                removeEndListener.setAccessible(true);
                removeEndListener.invoke(spring, springEndListener);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to remove Android 17 Xiaomi OPEN spring listener",
                    throwable);
        } finally {
            snapshot.springEndListener = null;
        }
    }

    protected void invalidateOpenTransitionForInfo(Object info, String reason) {
        for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
            if (snapshot.transitionInfo == info) {
                invalidateOpenTransitionSnapshot(snapshot, reason);
            }
        }
    }

    protected void invalidateAllOpenTransitionSnapshots(String reason) {
        int count = runningOpenTransitions.size();
        for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
            invalidateOpenTransitionSnapshot(snapshot, reason);
        }
        runningOpenTransitions.clear();
        if (count > 0) {
            moduleLog(Log.INFO, TAG, "Cleared Xiaomi OPEN transition snapshots"
                    + ", reason=" + reason
                    + ", count=" + count);
        }
    }

    protected void hookDefaultTransitionImplMerge(ClassLoader classLoader) {
        try {
            Class<?> implementationClass = Class.forName(DEFAULT_TRANSITION_IMPL, false,
                    classLoader);
            Class<?> shellExecutorClass = Class.forName(
                    "com.android.wm.shell.common.ShellExecutor", false, classLoader);
            Class<?> transitionInfoClass = TransitionInfo.class;
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
            moduleLog(Log.INFO, TAG, "Hooked exact DefaultTransitionImpl.mergeAnimation");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook DefaultTransitionImpl.mergeAnimation",
                    throwable);
        }
    }

    protected Object trackMiuiOpenCloseMerge(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (Boolean.TRUE.equals(result)) {
            Object runningInfo = chain.getArg(5);
            invalidateOpenTransitionForInfo(runningInfo, "reverseMerge");
            correlateLegacyBackMerge(runningInfo);
        }
        return result;
    }

    protected void hookBackAnimationSendBackEvent(ClassLoader classLoader) {
        try {
            Method sendBackEvent = requireSystemUiPlatformImpl()
                    .backEventGuardMethod(classLoader);
            recordHookHandle(hook(sendBackEvent)
                    .setId("systemui_back_send_event_guard")
                    .intercept(this::guardDuplicateBackEvent));
            moduleLog(Log.INFO, TAG, "Hooked BackAnimationController.sendBackEvent guard");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook BackAnimationController.sendBackEvent", throwable);
        }
    }

    protected Object guardDuplicateBackEvent(XposedInterface.Chain chain) throws Throwable {
        int action = ((Number) chain.getArg(0)).intValue();
        if (moduleLegacyBackInjection.get() != null) {
            return chain.proceed();
        }
        if (shouldSuppressDuplicateBack(chain.getThisObject(), action)) {
            return null;
        }
        return chain.proceed();
    }

    protected LegacyBackAttempt armLegacyBackGuard(Object controller, Object runningInfo) {
        long now = SystemClock.uptimeMillis();
        LegacyBackAttempt attempt = new LegacyBackAttempt(
                legacyBackAttemptIds.incrementAndGet(), controller, runningInfo, now);
        synchronized (legacyBackGuardLock) {
            resetLegacyBackGuardLocked();
            legacyBackAttempt = attempt;
            legacyBackGuardPhase = BACK_GUARD_WAIT_MERGE;
            legacyBackGuardDeadlineUptime = now + LEGACY_BACK_MERGE_TIMEOUT_MS;
        }
        moduleLog(Log.INFO, TAG, "Armed Xiaomi interruption BACK correlation"
                + ", attempt=" + attempt.id
                + ", controller=" + shortObject(controller)
                + ", runningInfo=" + shortObject(runningInfo));
        scheduleLegacyBackGuardExpiry(attempt, LEGACY_BACK_MERGE_TIMEOUT_MS);
        return attempt;
    }

    protected void correlateLegacyBackMerge(Object runningInfo) {
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
            moduleLog(Log.WARN, TAG, "Expired Xiaomi interruption BACK before merge"
                    + ", attempt=" + correlated.id
                    + ", elapsedMs=" + (now - correlated.startedUptime));
            return;
        }
        moduleLog(Log.INFO, TAG, "Correlated Xiaomi OPEN/CLOSE reverse merge"
                + ", attempt=" + correlated.id
                + ", elapsedMs=" + (now - correlated.startedUptime)
                + ", duplicatePairDeadlineMs=" + DUPLICATE_BACK_PAIR_TIMEOUT_MS);
        scheduleLegacyBackGuardExpiry(correlated, DUPLICATE_BACK_PAIR_TIMEOUT_MS);
    }

    protected boolean shouldSuppressDuplicateBack(Object controller, int action) {
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
            moduleLog(Log.INFO, TAG, "Consumed one correlated duplicate BACK pair"
                    + ", attempt=" + attempt.id);
        } else if (outcome != null) {
            moduleLog(Log.WARN, TAG, "Released Xiaomi duplicate BACK guard"
                    + ", attempt=" + attempt.id
                    + ", reason=" + outcome);
        }
        return suppress;
    }

    protected void scheduleLegacyBackGuardExpiry(LegacyBackAttempt attempt, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> expireLegacyBackGuard(attempt), Math.max(1L, delayMs));
    }

    protected void expireLegacyBackGuard(LegacyBackAttempt expectedAttempt) {
        int phase;
        synchronized (legacyBackGuardLock) {
            if (legacyBackAttempt != expectedAttempt
                    || SystemClock.uptimeMillis() < legacyBackGuardDeadlineUptime) {
                return;
            }
            phase = legacyBackGuardPhase;
            resetLegacyBackGuardLocked();
        }
        moduleLog(Log.INFO, TAG, "Expired Xiaomi duplicate BACK guard"
                + ", attempt=" + expectedAttempt.id
                + ", phase=" + phase);
    }

    protected void clearLegacyBackGuard(String reason) {
        LegacyBackAttempt attempt;
        int phase;
        synchronized (legacyBackGuardLock) {
            attempt = legacyBackAttempt;
            phase = legacyBackGuardPhase;
            resetLegacyBackGuardLocked();
        }
        if (phase != BACK_GUARD_IDLE && attempt != null) {
            moduleLog(Log.INFO, TAG, "Cleared Xiaomi duplicate BACK guard"
                    + ", attempt=" + attempt.id
                    + ", phase=" + phase
                    + ", reason=" + reason);
        }
    }

    protected void resetLegacyBackGuardLocked() {
        legacyBackAttempt = null;
        legacyBackGuardPhase = BACK_GUARD_IDLE;
        legacyBackGuardDeadlineUptime = 0L;
        suppressedBackDownUptime = 0L;
        suppressedBackDownThread = null;
    }

    protected Object interceptMiuiOverviewProxyTransact(XposedInterface.Chain chain)
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

    protected void hookNavigationBarGestureInsets(ClassLoader classLoader) {
        try {
            Class<?> navigationBarClass = Class.forName(NAVIGATION_BAR, false, classLoader);
            Method method = navigationBarClass.getDeclaredMethod(
                    "getBarLayoutParamsForRotation", int.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_navigation_bar_gesture_insets")
                    .intercept(this::restoreNavigationBarGestureInsets));
            moduleLog(Log.INFO, TAG,
                    "Hooked NavigationBar application gesture Insets restoration");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook NavigationBar application gesture Insets", throwable);
        }
    }

    protected Object restoreNavigationBarGestureInsets(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (!(result instanceof WindowManager.LayoutParams)) {
            return result;
        }

        try {
            Object navigationBar = chain.getThisObject();
            Object edgeBackGestureHandler = readField(
                    navigationBar, "mEdgeBackGestureHandler");
            InsetsFrameProvider.InsetsSizeOverride imeOverride =
                    new InsetsFrameProvider.InsetsSizeOverride(
                            WindowManager.LayoutParams.TYPE_INPUT_METHOD, Insets.NONE);
            InsetsFrameProvider.InsetsSizeOverride[] imeOverrides =
                    new InsetsFrameProvider.InsetsSizeOverride[]{imeOverride};
            Object providers = readField(result, "providedInsets");
            if (providers == null || !providers.getClass().isArray()) {
                return result;
            }
            int systemGestureType = WindowInsets.Type.systemGestures();
            boolean stableOverrideTypes = requireSystemUiPlatformImpl()
                    .requiresStableGestureInsetsOverrideTypes();
            if (stableOverrideTypes) {
                for (int i = 0; i < Array.getLength(providers); i++) {
                    Object provider = Array.get(providers, i);
                    if (!(provider instanceof InsetsFrameProvider)
                            || ((InsetsFrameProvider) provider).getType()
                            != systemGestureType) {
                        continue;
                    }
                    InsetsFrameProvider typedProvider = (InsetsFrameProvider) provider;
                    typedProvider.setInsetsSizeOverrides(imeOverrides);
                    typedProvider.setMinimalInsetsSizeInDisplayCutoutSafe(Insets.NONE);
                }
            }
            if (!Boolean.TRUE.equals(readField(edgeBackGestureHandler, "mInGestureNavMode"))
                    || !Boolean.TRUE.equals(readField(
                    edgeBackGestureHandler, "mIsBackGestureAllowed"))) {
                return result;
            }

            Context context = (Context) readField(navigationBar, "mContext");
            EdgeWidthSnapshot widths = readEdgeWidthSnapshot(edgeBackGestureHandler,
                    context.getResources().getDisplayMetrics().density);
            int restored = 0;
            for (int i = 0; i < Array.getLength(providers); i++) {
                Object provider = Array.get(providers, i);
                if (!(provider instanceof InsetsFrameProvider)
                        || ((InsetsFrameProvider) provider).getType() != systemGestureType) {
                    continue;
                }
                InsetsFrameProvider typedProvider = (InsetsFrameProvider) provider;
                int providerIndex = typedProvider.getIndex();
                Insets size;
                if (providerIndex == 0) {
                    size = Insets.of(widths.leftSensitivity, 0, 0, 0);
                } else if (providerIndex == 1) {
                    size = Insets.of(0, 0, widths.rightSensitivity, 0);
                } else {
                    continue;
                }
                // WMS also applies the cutout-safe minimum to overridden frames, so keep it zero.
                if (!stableOverrideTypes) {
                    typedProvider.setInsetsSizeOverrides(imeOverrides);
                    typedProvider.setMinimalInsetsSizeInDisplayCutoutSafe(Insets.NONE);
                }
                typedProvider.setInsetsSize(size);
                restored++;
            }
            moduleLog(restored == 2 ? Log.INFO : Log.WARN, TAG,
                    "Restored application system-gesture Insets with zero IME override"
                            + ", left=" + widths.leftSensitivity
                            + ", right=" + widths.rightSensitivity
                            + ", providers=" + restored);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to restore IME-safe application gesture Insets", throwable);
        }
        return result;
    }

    protected void hookNavigationBarTransientAutoHide(ClassLoader classLoader) {
        try {
            Class<?> navigationBarClass = Class.forName(NAVIGATION_BAR, false, classLoader);
            Method method = navigationBarClass.getDeclaredMethod(
                    "showTransient", int.class, int.class, boolean.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_navigation_bar_show_transient")
                    .intercept(this::preserveTransientBarAutoHide));
            moduleLog(Log.INFO, TAG, "Hooked NavigationBar.showTransient auto-hide preservation");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook NavigationBar transient auto-hide", throwable);
        }
    }

    protected Object preserveTransientBarAutoHide(XposedInterface.Chain chain)
            throws Throwable {
        Object navigationBar = chain.getThisObject();
        boolean wasTransient = false;
        Integer modeBefore = null;
        try {
            wasTransient = Boolean.TRUE.equals(readField(navigationBar, "mTransientShown"));
            Object mode = readField(navigationBar, "mTransitionMode");
            if (mode instanceof Number) {
                modeBefore = Integer.valueOf(((Number) mode).intValue());
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Cannot snapshot transient NavigationBar state", throwable);
        }

        Object result = chain.proceed();
        try {
            if (wasTransient || modeBefore == null
                    || !Boolean.TRUE.equals(readField(navigationBar, "mTransientShown"))) {
                return result;
            }
            Object modeAfter = readField(navigationBar, "mTransitionMode");
            if (!(modeAfter instanceof Number)
                    || ((Number) modeAfter).intValue() != modeBefore.intValue()) {
                return result;
            }
            Object autoHideController = readField(navigationBar, "mAutoHideController");
            if (autoHideController == null) {
                moduleLog(Log.WARN, TAG,
                        "Transparent transient NavigationBar has no AutoHideController");
                return result;
            }
            invokeAnyMethod(autoHideController, "touchAutoHide", new Object[0]);
            moduleLog(Log.INFO, TAG,
                    "Preserved native transient-bar auto-hide with unchanged transparent mode");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to preserve transparent transient-bar auto-hide", throwable);
        }
        return result;
    }

    protected void hookNavigationBarTransientAppearance(ClassLoader classLoader) {
        try {
            Class<?> helperClass = Class.forName(NAV_BAR_HELPER, false, classLoader);
            Method method = helperClass.getDeclaredMethod(
                    "transitionMode", int.class, boolean.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_navigation_bar_transient_appearance")
                    .intercept(this::preserveTransientBarAppearance));
            moduleLog(Log.INFO, TAG, "Hooked NavBarHelper.transitionMode transient appearance");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook NavBarHelper transient appearance", throwable);
        }
    }

    protected void hookStatusBarTransientAppearance(ClassLoader classLoader) {
        try {
            Class<?> appearanceClass = Class.forName(
                    STATUS_BAR_APPEARANCE_LAMBDA, false, classLoader);
            Method method = findAnyMethod(appearanceClass, "invoke", 6);
            if (method == null) {
                throw new NoSuchMethodException(STATUS_BAR_APPEARANCE_LAMBDA + ".invoke/6");
            }
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_status_bar_transient_appearance")
                    .intercept(this::preserveTransientBarAppearance));
            moduleLog(Log.INFO, TAG, "Hooked status-bar transient appearance reducer");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook status-bar transient appearance", throwable);
        }
    }

    protected Object preserveTransientBarAppearance(XposedInterface.Chain chain)
            throws Throwable {
        if (!Boolean.TRUE.equals(chain.getArg(1))) {
            return chain.proceed();
        }
        Object[] args = chain.getArgs().toArray();
        args[1] = Boolean.FALSE;
        return chain.proceed(args);
    }

    protected void hookNavigationBarControllerCreate(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(
                    NAVIGATION_BAR_CONTROLLER_IMPL, false, classLoader);
            Method method = findAnyMethod(controllerClass, "createNavigationBar", 3);
            if (method == null) {
                throw new NoSuchMethodException(
                        NAVIGATION_BAR_CONTROLLER_IMPL + ".createNavigationBar/3");
            }
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_navigation_bar_controller_create")
                    .intercept(this::reconcileAfterNavigationBarCreate));
            moduleLog(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.createNavigationBar"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook NavigationBarControllerImpl.createNavigationBar",
                    throwable);
        }
    }

    protected void hookNavigationBarControllerRemove(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(
                    NAVIGATION_BAR_CONTROLLER_IMPL, false, classLoader);
            Method method = controllerClass.getDeclaredMethod(
                    "removeNavigationBar", int.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_navigation_bar_controller_remove")
                    .intercept(this::reconcileAfterNavigationBarRemove));
            moduleLog(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.removeNavigationBar"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook NavigationBarControllerImpl.removeNavigationBar",
                    throwable);
        }
    }

    protected void hookNavigationBarControllerMode(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(
                    NAVIGATION_BAR_CONTROLLER_IMPL, false, classLoader);
            Method method = controllerClass.getDeclaredMethod(
                    "onNavigationModeChanged", int.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("systemui_navigation_bar_controller_onNavigationModeChanged")
                    .intercept(this::reconcileAfterNavigationModeChanged));
            moduleLog(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.onNavigationModeChanged"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook NavigationBarControllerImpl.onNavigationModeChanged",
                    throwable);
        }
    }

    protected Object reconcileAfterNavigationBarCreate(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object display = chain.getArg(0);
        try {
            if (display instanceof Display && ((Display) display).getDisplayId() == 0) {
                scheduleHeadlessNavBarReconcile(chain.getThisObject(),
                        "createNavigationBar");
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to identify created NavigationBar display",
                    throwable);
        }
        return result;
    }

    protected Object reconcileAfterNavigationBarRemove(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object displayId = chain.getArg(0);
        if (displayId instanceof Number && ((Number) displayId).intValue() == 0) {
            scheduleHeadlessNavBarReconcile(chain.getThisObject(),
                    "removeNavigationBar");
        }
        return result;
    }

    protected Object reconcileAfterNavigationModeChanged(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        scheduleHeadlessNavBarReconcile(chain.getThisObject(),
                "onNavigationModeChanged");
        return result;
    }

    protected void scheduleHeadlessNavBarReconcile(Object controller, String reason) {
        if (controller == null || !acceptingHeadlessNavBarLifecycle) {
            return;
        }
        long generation = headlessNavBarLifecycleGeneration.get();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!acceptingHeadlessNavBarLifecycle
                    || generation != headlessNavBarLifecycleGeneration.get()) {
                return;
            }
            reconcileHeadlessNavBarLifecycle(controller, reason);
        });
    }

    protected void reconcileHeadlessNavBarLifecycle(Object controller, String reason) {
        if (controller == null || !acceptingHeadlessNavBarLifecycle) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scheduleHeadlessNavBarReconcile(controller, reason + ":ownerThread");
            return;
        }
        try {
            Object injector = readField(controller, "mNavigationModeControllerInjector");
            Object navigationBars = readField(controller, "mNavigationBars");
            Object taskbarDelegate = readField(controller, "mTaskbarDelegate");
            Object navBarHelper = readField(controller, "mNavBarHelper");
            Object edgeBackGestureHandler = readField(navBarHelper,
                    "mEdgeBackGestureHandler");
            Object backAnimation = readField(taskbarDelegate, "mBackAnimation");
            Object navModeValue = readField(controller, "mNavMode");
            Object contextValue = readField(controller, "mContext");
            if (!(contextValue instanceof Context)
                    || !(navModeValue instanceof Number)) {
                throw new IllegalStateException("Unexpected NavigationBar controller state"
                        + ", context=" + shortObject(contextValue)
                        + ", navMode=" + shortObject(navModeValue));
            }
            Context context = (Context) contextValue;
            Object displayIdValue = invokeAnyMethod(
                    context, "getDisplayId", new Object[0]);
            if (!(displayIdValue instanceof Number)) {
                throw new IllegalStateException("Unexpected Context displayId="
                        + shortObject(displayIdValue));
            }
            int displayId = ((Number) displayIdValue).intValue();
            int navigationMode = ((Number) navModeValue).intValue();
            Object defaultNavigationBar = invokeAnyMethod(navigationBars, "get",
                    new Object[]{Integer.valueOf(0)});
            boolean taskbarInitialized = Boolean.TRUE.equals(
                    readField(taskbarDelegate, "mInitialized"));
            boolean fsgMode = Boolean.TRUE.equals(readField(injector, "mIsFsgMode"));
            boolean hideGestureLine = Boolean.TRUE.equals(
                    readField(injector, "mHideGestureLine"));
            boolean flipTinyScreen = isMiuiFlipTinyScreen(
                    context, controller.getClass().getClassLoader());
            boolean hasNativeOwner = defaultNavigationBar != null || taskbarInitialized;
            boolean systemHasNavigationBar = false;
            if (displayId == 0) {
                systemHasNavigationBar = requireSystemUiPlatformImpl()
                        .canCreateNavBarOrTaskBar(controller, displayId);
            }
            boolean headlessDesired = displayId == 0
                    && fsgMode
                    && hideGestureLine
                    && !flipTinyScreen
                    && systemHasNavigationBar
                    && !hasNativeOwner
                    && backAnimation != null;

            HeadlessNavBarLease existing;
            synchronized (headlessNavBarLifecycleLock) {
                existing = headlessNavBarLease;
            }
            if (existing != null && existing.controller != controller) {
                if (!detachHeadlessNavBarLease(
                        existing, reason + ":controllerReplaced")) {
                    return;
                }
                existing = null;
            }
            if (existing != null
                    && !containsIdentity(readField(existing.navBarHelper,
                    "mStateListeners"), existing.updaterProxy)) {
                synchronized (headlessNavBarLifecycleLock) {
                    if (headlessNavBarLease == existing) {
                        headlessNavBarLease = null;
                    }
                }
                moduleLog(Log.WARN, TAG, "Headless NavBar updater disappeared"
                        + ", controller=" + shortObject(controller)
                        + ", reason=" + reason);
                existing = null;
            }
            if (existing != null && !existing.ready) {
                if (!detachHeadlessNavBarLease(
                        existing, reason + ":partialAttachCleanup")) {
                    return;
                }
                existing = null;
            }
            if (existing != null && flipTinyScreen) {
                detachHeadlessNavBarLease(existing, reason + ":flipTinyScreen");
                return;
            }
            if (existing != null && hasNativeOwner) {
                detachHeadlessNavBarLease(existing, reason + ":nativeOwnerReady");
                return;
            }
            if (existing != null && !headlessDesired) {
                detachHeadlessNavBarLease(existing, reason + ":noLongerHeadless");
                return;
            }
            if (existing != null) {
                if (existing.navigationMode != navigationMode) {
                    invokeMethod(existing.edgeBackGestureHandler,
                            "onNavigationModeChanged",
                            new Class<?>[]{int.class},
                            new Object[]{Integer.valueOf(navigationMode)});
                    existing.navigationMode = navigationMode;
                    moduleLog(Log.INFO, TAG, "Updated headless EdgeBackGestureHandler mode"
                            + ", mode=" + navigationMode
                            + ", reason=" + reason);
                }
                if (headlessDesired && existing.backAnimation != backAnimation) {
                    if (!detachHeadlessNavBarLease(existing,
                            reason + ":backAnimationReplaced")) {
                        return;
                    }
                    existing = null;
                } else {
                    ensureBackInputInstalledFromHandler(
                            existing.edgeBackGestureHandler,
                            "headlessNavBar:" + reason);
                    return;
                }
            }
            if (!headlessDesired) {
                return;
            }
            attachHeadlessNavBarLease(controller, navBarHelper,
                    edgeBackGestureHandler, backAnimation, navigationMode, reason);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to reconcile headless NavigationBar lifecycle"
                    + ", controller=" + shortObject(controller)
                    + ", reason=" + reason, throwable);
        }
    }

    protected boolean isCurrentHeadlessNavBarLifecycle(Object edgeBackGestureHandler) {
        if (edgeBackGestureHandler == null
                || !acceptingHeadlessNavBarLifecycle
                || Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }
        long generation = headlessNavBarLifecycleGeneration.get();
        HeadlessNavBarLease lease;
        synchronized (headlessNavBarLifecycleLock) {
            lease = headlessNavBarLease;
            if (lease == null || !lease.ready
                    || lease.edgeBackGestureHandler != edgeBackGestureHandler) {
                return false;
            }
        }
        try {
            Object navigationBars = readField(lease.controller, "mNavigationBars");
            Object taskbarDelegate = readField(lease.controller, "mTaskbarDelegate");
            Object injector = readField(
                    lease.controller, "mNavigationModeControllerInjector");
            Object defaultNavigationBar = invokeAnyMethod(
                    navigationBars, "get", new Object[]{Integer.valueOf(0)});
            boolean taskbarInitialized = Boolean.TRUE.equals(
                    readField(taskbarDelegate, "mInitialized"));
            boolean fsgMode = Boolean.TRUE.equals(readField(injector, "mIsFsgMode"));
            boolean hideGestureLine = Boolean.TRUE.equals(
                    readField(injector, "mHideGestureLine"));
            boolean updaterRegistered = containsIdentity(
                    readField(lease.navBarHelper, "mStateListeners"), lease.updaterProxy);
            boolean backAnimationCurrent = readField(
                    taskbarDelegate, "mBackAnimation") == lease.backAnimation;
            if (defaultNavigationBar != null || taskbarInitialized
                    || !fsgMode || !hideGestureLine || !updaterRegistered
                    || !backAnimationCurrent) {
                scheduleHeadlessNavBarReconcile(lease.controller, "inputDown:staleLease");
                return false;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Cannot authenticate live headless NavigationBar lifecycle", throwable);
            return false;
        }
        if (!acceptingHeadlessNavBarLifecycle
                || generation != headlessNavBarLifecycleGeneration.get()) {
            return false;
        }
        synchronized (headlessNavBarLifecycleLock) {
            return headlessNavBarLease == lease
                    && lease.ready
                    && lease.edgeBackGestureHandler == edgeBackGestureHandler;
        }
    }

    protected boolean isMiuiFlipTinyScreen(Context context, ClassLoader classLoader) {
        try {
            Class<?> configsClass = Class.forName(MIUI_CONFIGS, false, classLoader);
            Method method = configsClass.getMethod("isFlipTinyScreen", Context.class);
            return Boolean.TRUE.equals(method.invoke(null, context));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to resolve Xiaomi flip tiny-screen state;"
                    + " headless NavigationBar will fail closed", throwable);
            return true;
        }
    }

    protected void attachHeadlessNavBarLease(Object controller, Object navBarHelper,
                                             Object edgeBackGestureHandler,
                                             Object backAnimation, int navigationMode,
                                             String reason) throws Exception {
        ClassLoader classLoader = controller.getClass().getClassLoader();
        Class<?> updaterInterface = Class.forName(
                NAV_BAR_STATE_UPDATER, false, classLoader);
        Method navigationModeChanged = edgeBackGestureHandler.getClass().getMethod(
                "onNavigationModeChanged", int.class);
        Method registerUpdater = navBarHelper.getClass().getMethod(
                "registerNavTaskStateUpdater", updaterInterface);
        Method removeUpdater = navBarHelper.getClass().getMethod(
                "removeNavTaskStateUpdater", updaterInterface);
        Method setBackAnimation = edgeBackGestureHandler.getClass().getMethod(
                "setBackAnimation", requireSystemUiPlatformImpl()
                        .backAnimationParameterClass(classLoader));
        navigationModeChanged.setAccessible(true);
        registerUpdater.setAccessible(true);
        removeUpdater.setAccessible(true);
        setBackAnimation.setAccessible(true);
        Object updaterProxy = Proxy.newProxyInstance(
                updaterInterface.getClassLoader(),
                new Class<?>[]{updaterInterface},
                (proxy, method, args) -> headlessUpdaterResult(proxy, method, args));
        try {
            navigationModeChanged.invoke(edgeBackGestureHandler,
                    Integer.valueOf(navigationMode));
            registerUpdater.invoke(navBarHelper, updaterProxy);
            Object currentBackAnimation = readField(
                    edgeBackGestureHandler, "mBackAnimation");
            if (currentBackAnimation != backAnimation) {
                setBackAnimation.invoke(edgeBackGestureHandler, backAnimation);
            } else {
                ensureBackInputInstalledFromHandler(edgeBackGestureHandler,
                        "headlessNavBar:existingBackAnimation");
            }
            HeadlessNavBarLease lease = new HeadlessNavBarLease(
                    controller, navBarHelper, edgeBackGestureHandler,
                    updaterProxy, updaterInterface, backAnimation, navigationMode, true);
            synchronized (headlessNavBarLifecycleLock) {
                if (headlessNavBarLease != null) {
                    throw new IllegalStateException("Headless NavBar lease raced with "
                            + shortObject(headlessNavBarLease.controller));
                }
                headlessNavBarLease = lease;
            }
            moduleLog(Log.INFO, TAG, "Attached headless SystemUI NavigationBar lifecycle"
                    + ", controller=" + shortObject(controller)
                    + ", helper=" + shortObject(navBarHelper)
                    + ", handler=" + shortObject(edgeBackGestureHandler)
                    + ", backAnimation=" + shortObject(backAnimation)
                    + ", mode=" + navigationMode
                    + ", reason=" + reason
                    + ", createsWindow=false");
        } catch (Throwable throwable) {
            boolean updaterRemains = false;
            try {
                updaterRemains = containsIdentity(
                        readField(navBarHelper, "mStateListeners"), updaterProxy);
                if (updaterRemains) {
                    removeUpdater.invoke(navBarHelper, updaterProxy);
                }
                updaterRemains = containsIdentity(
                        readField(navBarHelper, "mStateListeners"), updaterProxy);
            } catch (Throwable rollbackFailure) {
                throwable.addSuppressed(rollbackFailure);
                try {
                    updaterRemains = containsIdentity(
                            readField(navBarHelper, "mStateListeners"), updaterProxy);
                } catch (Throwable ignored) {
                    updaterRemains = true;
                }
            }
            if (updaterRemains) {
                HeadlessNavBarLease partialLease = new HeadlessNavBarLease(
                        controller, navBarHelper, edgeBackGestureHandler,
                        updaterProxy, updaterInterface, backAnimation,
                        navigationMode, false);
                synchronized (headlessNavBarLifecycleLock) {
                    if (headlessNavBarLease == null) {
                        headlessNavBarLease = partialLease;
                    }
                }
            }
            if (throwable instanceof Exception) {
                throw (Exception) throwable;
            }
            throw new IllegalStateException("Failed to attach headless NavBar lease",
                    throwable);
        }
    }

    protected Object headlessUpdaterResult(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "equals":
                    return Boolean.valueOf(args != null && args.length == 1
                            && proxy == args[0]);
                case "hashCode":
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "toString":
                    return "MiuiBackGestureHook.HeadlessNavBarUpdater@"
                            + Integer.toHexString(System.identityHashCode(proxy));
                default:
                    return null;
            }
        }
        return primitiveDefaultValue(method.getReturnType());
    }

    protected static Object primitiveDefaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0.0f);
        }
        if (type == double.class) {
            return Double.valueOf(0.0d);
        }
        return null;
    }

    protected static boolean containsIdentity(Object collection, Object target) {
        if (!(collection instanceof Iterable)) {
            return false;
        }
        for (Object value : (Iterable<?>) collection) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    protected boolean detachHeadlessNavBarLease(HeadlessNavBarLease lease, String reason) {
        if (lease == null) {
            return true;
        }
        try {
            Object listeners = readField(lease.navBarHelper, "mStateListeners");
            if (containsIdentity(listeners, lease.updaterProxy)) {
                invokeMethod(lease.navBarHelper, "removeNavTaskStateUpdater",
                        new Class<?>[]{lease.updaterInterface},
                        new Object[]{lease.updaterProxy});
            }
            if (containsIdentity(readField(lease.navBarHelper, "mStateListeners"),
                    lease.updaterProxy)) {
                throw new IllegalStateException("Headless NavBar updater remains registered");
            }
            synchronized (headlessNavBarLifecycleLock) {
                if (headlessNavBarLease == lease) {
                    headlessNavBarLease = null;
                }
            }
            moduleLog(Log.INFO, TAG, "Detached headless SystemUI NavigationBar lifecycle"
                    + ", controller=" + shortObject(lease.controller)
                    + ", reason=" + reason);
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to detach headless NavigationBar lifecycle"
                    + ", controller=" + shortObject(lease.controller)
                    + ", reason=" + reason, throwable);
            return false;
        }
    }

    protected Object[][] detachHeadlessNavBarLifecycleForHotReload() {
        HeadlessNavBarLease lease;
        synchronized (headlessNavBarLifecycleLock) {
            lease = headlessNavBarLease;
        }
        if (lease == null) {
            return new Object[0][0];
        }
        Object[][] savedState = new Object[][]{{
                lease.controller, lease.navBarHelper, lease.updaterProxy,
                lease.updaterInterface
        }};
        Runnable detach = () -> detachHeadlessNavBarLease(lease, "hotReload");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detach.run();
            return savedState;
        }
        CountDownLatch completed = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                detach.run();
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(5L, TimeUnit.SECONDS)) {
                moduleLog(Log.ERROR, TAG, "Timed out detaching headless NavBar lease"
                        + " on the SystemUI main Looper");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            moduleLog(Log.ERROR, TAG, "Interrupted detaching headless NavBar lease",
                    exception);
        }
        return savedState;
    }

    protected boolean cleanupOldHeadlessNavBarProxy(Object[] savedLease) {
        if (savedLease.length < 4 || savedLease[1] == null
                || savedLease[2] == null || !(savedLease[3] instanceof Class<?>)) {
            return false;
        }
        Object navBarHelper = savedLease[1];
        Object updaterProxy = savedLease[2];
        Class<?> updaterInterface = (Class<?>) savedLease[3];
        try {
            if (!containsIdentity(readField(navBarHelper, "mStateListeners"),
                    updaterProxy)) {
                return true;
            }
            invokeMethod(navBarHelper, "removeNavTaskStateUpdater",
                    new Class<?>[]{updaterInterface}, new Object[]{updaterProxy});
            if (containsIdentity(readField(navBarHelper, "mStateListeners"),
                    updaterProxy)) {
                throw new IllegalStateException(
                        "Residual headless NavBar updater remains registered");
            }
            moduleLog(Log.WARN, TAG, "Removed residual pre-reload headless NavBar updater"
                    + ", helper=" + shortObject(navBarHelper));
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to remove residual pre-reload headless NavBar updater",
                    throwable);
            return false;
        }
    }

    protected void adoptResidualHeadlessNavBarLease(Object[] savedLease) {
        if (savedLease.length < 4 || savedLease[0] == null
                || savedLease[1] == null || savedLease[2] == null
                || !(savedLease[3] instanceof Class<?>)) {
            return;
        }
        try {
            Object controller = savedLease[0];
            Object navBarHelper = savedLease[1];
            Object edgeBackGestureHandler = readField(
                    navBarHelper, "mEdgeBackGestureHandler");
            Object taskbarDelegate = readField(controller, "mTaskbarDelegate");
            Object backAnimation = readField(taskbarDelegate, "mBackAnimation");
            Object navigationMode = readField(controller, "mNavMode");
            if (!(navigationMode instanceof Number)) {
                throw new IllegalStateException("Residual NavBar mode="
                        + shortObject(navigationMode));
            }
            HeadlessNavBarLease residual = new HeadlessNavBarLease(
                    controller, navBarHelper, edgeBackGestureHandler,
                    savedLease[2], (Class<?>) savedLease[3], backAnimation,
                    ((Number) navigationMode).intValue(), false);
            synchronized (headlessNavBarLifecycleLock) {
                if (headlessNavBarLease == null) {
                    headlessNavBarLease = residual;
                }
            }
            moduleLog(Log.WARN, TAG, "Adopted residual pre-reload headless NavBar updater"
                    + " for deferred exact cleanup");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to adopt residual headless NavBar updater",
                    throwable);
        }
    }

    protected void restoreSystemUiHotReloadLifecycle(ClassLoader classLoader) {
        Object[][] inputState = pendingHotReloadInputState;
        Object[][] savedHeadlessState = pendingHotReloadHeadlessState;
        pendingHotReloadInputState = new Object[0][0];
        pendingHotReloadHeadlessState = new Object[0][0];
        long generation = headlessNavBarLifecycleGeneration.get();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!acceptingHeadlessNavBarLifecycle
                    || generation != headlessNavBarLifecycleGeneration.get()) {
                return;
            }
            Context systemUiContext = resolveCurrentApplicationContext(classLoader);
            if (systemUiContext != null) {
                ensureMiuiOverviewStateReceiver(systemUiContext);
            }
            int headlessRestored = 0;
            for (Object[] savedLease : savedHeadlessState) {
                if (savedLease == null || savedLease.length == 0
                        || savedLease[0] == null) {
                    continue;
                }
                if (!cleanupOldHeadlessNavBarProxy(savedLease)) {
                    adoptResidualHeadlessNavBarLease(savedLease);
                    headlessRestored++;
                    continue;
                }
                Object controller = savedLease[0];
                reconcileHeadlessNavBarLifecycle(controller, "hotReload:savedController");
                headlessRestored++;
            }
            if (headlessRestored == 0 && classLoader != null) {
                Object controller = findNavigationBarControllerFromDependency(classLoader);
                if (controller != null) {
                    reconcileHeadlessNavBarLifecycle(controller,
                            "hotReload:dependencyBackfill");
                    headlessRestored++;
                }
            }
            int inputRestored = 0;
            for (Object[] pair : inputState) {
                if (pair == null || pair.length < 2) {
                    continue;
                }
                installBackInputDriver(pair[0], pair[1]);
                inputRestored++;
            }
            moduleLog(Log.INFO, TAG, "Restored SystemUI hot reload lifecycle on main thread"
                    + ", headlessControllers=" + headlessRestored
                    + ", inputMonitors=" + inputRestored);
        });
    }

    protected Object findNavigationBarControllerFromDependency(ClassLoader classLoader) {
        try {
            Class<?> dependencyClass = Class.forName(
                    SYSTEM_UI_DEPENDENCY, false, classLoader);
            Object dependency = readStaticField(dependencyClass, "sDependency");
            if (dependency == null) {
                moduleLog(Log.INFO, TAG, "SystemUI Dependency is not initialized;"
                        + " NavigationBar hooks will capture the controller later");
                return null;
            }
            Object lazyController = readField(dependency, "mNavigationBarController");
            Object controller = invokeAnyMethod(lazyController, "get", new Object[0]);
            if (controller == null
                    || !NAVIGATION_BAR_CONTROLLER_IMPL.equals(
                    controller.getClass().getName())) {
                moduleLog(Log.WARN, TAG, "Unexpected NavigationBarController dependency="
                        + shortObject(controller));
                return null;
            }
            return controller;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to backfill NavigationBarController from Dependency",
                    throwable);
            return null;
        }
    }

    protected void hookEdgeBackGestureHandler(ClassLoader classLoader,
                                              boolean hookUpdateIsEnabled, boolean hookNavigationModeChanged,
                                              boolean hookSetBackAnimation) {
        Class<?> handlerClass;
        try {
            handlerClass = Class.forName(EDGE_BACK_GESTURE_HANDLER, false, classLoader);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to resolve EdgeBackGestureHandler", throwable);
            return;
        }
        int installed = 0;
        if (hookUpdateIsEnabled) {
            try {
                Method updateIsEnabled = handlerClass.getDeclaredMethod("updateIsEnabled");
                updateIsEnabled.setAccessible(true);
                recordHookHandle(hook(updateIsEnabled)
                        .setId("systemui_edge_back_updateIsEnabled")
                        .intercept(this::onEdgeBackUpdateIsEnabled));
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook EdgeBackGestureHandler.updateIsEnabled",
                        throwable);
            }
        }
        if (hookNavigationModeChanged) {
            try {
                Method navigationModeChanged = handlerClass.getDeclaredMethod(
                        "onNavigationModeChanged", int.class);
                navigationModeChanged.setAccessible(true);
                recordHookHandle(hook(navigationModeChanged)
                        .setId("systemui_edge_back_onNavigationModeChanged")
                        .intercept(this::onEdgeBackNavigationModeChanged));
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed to hook EdgeBackGestureHandler.onNavigationModeChanged",
                        throwable);
            }
        }
        if (hookSetBackAnimation) {
            try {
                Method setBackAnimation = handlerClass.getDeclaredMethod("setBackAnimation",
                        requireSystemUiPlatformImpl()
                                .backAnimationParameterClass(classLoader));
                setBackAnimation.setAccessible(true);
                recordHookHandle(hook(setBackAnimation)
                        .setId("systemui_edge_back_setBackAnimation")
                        .intercept(this::onEdgeBackSetBackAnimation));
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook EdgeBackGestureHandler.setBackAnimation",
                        throwable);
            }
        }
        moduleLog(Log.INFO, TAG, "Hooked EdgeBackGestureHandler AOSP path, installed=" + installed);
    }

    /**
     * Replaces the AOSP back-panel threshold constants. Hooking the shared helper keeps this
     * working when R8 inlines BackPanelController's private threshold methods. The panel still
     * owns all gesture state and visuals; when the optional effect cannot be prepared, the
     * original AOSP method remains the fallback.
     */
    protected void hookAospBackPanelHaptic(ClassLoader classLoader) {
        try {
            Class<?> vibratorHelperClass = Class.forName(VIBRATOR_HELPER,
                    false, classLoader);
            Method performHapticFeedback = vibratorHelperClass.getDeclaredMethod(
                    "performHapticFeedback", View.class, int.class);
            performHapticFeedback.setAccessible(true);
            recordHookHandle(hook(performHapticFeedback)
                    .setId("systemui_back_panel_aosp_haptic")
                    .intercept(this::replaceAospBackPanelHaptic));
            moduleLog(Log.INFO, TAG,
                    "Hooked AOSP back-panel threshold haptic replacement");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to hook AOSP back-panel activation haptic; native effect remains",
                    throwable);
        }
    }

    protected Object replaceAospBackPanelHaptic(XposedInterface.Chain chain)
            throws Throwable {
        Object feedbackConstant = chain.getArg(1);
        if (isAospBackThresholdHaptic(feedbackConstant)
                && playHyperOsReplacementHaptic(chain.getArg(0))) {
            return null;
        }
        return chain.proceed();
    }

    /**
     * HyperOS removes VibratorHelper.performHapticFeedback(View, int) on some builds and
     * emits the same AOSP threshold call directly from BackPanel. Hook the actual View
     * boundary as the compatibility path. Only the BackPanel instance and the two original
     * AOSP threshold constants are intercepted; both use the same single HyperOS default
     * effect, without adding another feedback stage.
     */
    protected void hookAospBackPanelViewHaptic(ClassLoader classLoader) {
        hookAospBackPanelViewHaptic(classLoader, true, true);
    }

    protected void hookAospBackPanelViewHaptic(ClassLoader classLoader,
                                               boolean hookSingleArgument,
                                               boolean hookFlagsArgument) {
        int installed = 0;
        if (hookSingleArgument) {
            try {
                Class<?> viewClass = Class.forName("android.view.View", false, classLoader);
                Method performHapticFeedback = viewClass.getDeclaredMethod(
                        "performHapticFeedback", int.class);
                performHapticFeedback.setAccessible(true);
                recordHookHandle(hook(performHapticFeedback)
                        .setId("systemui_back_panel_aosp_view_haptic")
                        .intercept(this::replaceAospBackPanelViewHaptic));
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to hook View.performHapticFeedback(int) for AOSP back panel",
                        throwable);
            }
        }
        if (hookFlagsArgument) {
            try {
                Class<?> viewClass = Class.forName("android.view.View", false, classLoader);
                Method performHapticFeedback = viewClass.getDeclaredMethod(
                        "performHapticFeedback", int.class, int.class);
                performHapticFeedback.setAccessible(true);
                recordHookHandle(hook(performHapticFeedback)
                        .setId("systemui_back_panel_aosp_view_haptic_flags")
                        .intercept(this::replaceAospBackPanelViewHaptic));
                installed++;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to hook View.performHapticFeedback(int, int) for AOSP back panel",
                        throwable);
            }
        }
        if (installed > 0) {
            moduleLog(Log.INFO, TAG,
                    "Hooked AOSP BackPanel View threshold haptic replacement"
                            + ", methods=" + installed);
        }
    }

    protected Object replaceAospBackPanelViewHaptic(XposedInterface.Chain chain)
            throws Throwable {
        Object view = chain.getThisObject();
        Object feedbackConstant = chain.getArg(0);
        if (isAospBackPanelView(view)
                && isAospBackThresholdHaptic(feedbackConstant)
                && playHyperOsReplacementHaptic(view)) {
            // View.performHapticFeedback returns boolean. Report that the original
            // threshold feedback was accepted after replacing its effect.
            return Boolean.TRUE;
        }
        return chain.proceed();
    }

    protected boolean isAospBackThresholdHaptic(Object feedbackConstant) {
        if (!(feedbackConstant instanceof Number)) {
            return false;
        }
        int constant = ((Number) feedbackConstant).intValue();
        return constant == HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                || constant == HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE;
    }

    protected boolean isAospBackPanelView(Object value) {
        return value instanceof View
                && BACK_PANEL_VIEW.equals(value.getClass().getName());
    }

    protected Object onEdgeBackUpdateIsEnabled(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        ensureBackInputInstalledFromHandler(chain.getThisObject(), "updateIsEnabled");
        return result;
    }

    protected Object onEdgeBackNavigationModeChanged(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        ensureBackInputInstalledFromHandler(chain.getThisObject(), "onNavigationModeChanged");
        return result;
    }

    protected Object onEdgeBackSetBackAnimation(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        installBackInputDriver(chain.getThisObject(), chain.getArg(0));
        return result;
    }

    protected void hookShellBackAnimation(ClassLoader classLoader) {
        try {
            Class<?> controllerClass =
                    Class.forName(BACK_ANIMATION_CONTROLLER, false, classLoader);
            hookShellAnimationFinished(controllerClass, "onBackAnimationFinished",
                    "shell_back_onBackAnimationFinished", false);
            hookShellAnimationFinished(controllerClass, "finishBackAnimation",
                    "shell_back_finishBackAnimation", true);
            hookBackNavigationInfoReceived(controllerClass);
            hookPreparedBackTargetArrival(classLoader);
            hookPreparedBackTerminal(controllerClass);
            hookPreparedBackTransitionDecision(classLoader);
            hookBackPrepareTransitionReparent(classLoader);
            hookBackCommitComposition(classLoader);
            hookBackFinishOpenAtomicTransfer(classLoader);
            hookFreeformCrossActivityScrimCreation();
            hookCrossActivitySlideAnimation(classLoader,
                    true, true, true, true, true, true);
            hookOneUiCrossTaskAnimation(classLoader, true, true);
            hookCrossTaskBackground(classLoader);
            moduleLog(Log.INFO, TAG, "Hooked Shell BackAnimationController AOSP path");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook Shell back animation", throwable);
        }
    }

    protected static final class PreparedBackTargetArrival {
        final Object controller;
        final Object transitionToken;
        final Object apps;
        final Object finishedCallback;

        PreparedBackTargetArrival(Object controller, Object transitionToken,
                                  Object apps, Object finishedCallback) {
            this.controller = controller;
            this.transitionToken = transitionToken;
            this.apps = apps;
            this.finishedCallback = finishedCallback;
        }
    }

    protected final class PreparedBackTransitionHold {
        final NativeBackInputMonitor monitor;
        final SystemUiBackGestureDriver.ShellGestureSession session;
        final Handler shellHandler;
        final XposedInterface.Invoker<?, Method> startAnimationInvoker;
        final Object handler;
        final Object controller;
        final Object transitionToken;
        final Object transitionInfo;
        final SurfaceControl.Transaction startTransaction;
        final SurfaceControl.Transaction finishTransaction;
        final Object finishCallback;
        final int transitionDebugId;
        final AtomicBoolean stockResumeAttempted = new AtomicBoolean();

        PreparedBackTransitionHold(
                NativeBackInputMonitor monitor,
                SystemUiBackGestureDriver.ShellGestureSession session,
                Handler shellHandler,
                XposedInterface.Invoker<?, Method> startAnimationInvoker,
                Object handler, Object controller,
                Object transitionToken, Object transitionInfo,
                SurfaceControl.Transaction startTransaction,
                SurfaceControl.Transaction finishTransaction,
                Object finishCallback) {
            this.monitor = monitor;
            this.session = session;
            this.shellHandler = shellHandler;
            this.startAnimationInvoker = startAnimationInvoker;
            this.handler = handler;
            this.controller = controller;
            this.transitionToken = transitionToken;
            this.transitionInfo = transitionInfo;
            this.startTransaction = startTransaction;
            this.finishTransaction = finishTransaction;
            this.finishCallback = finishCallback;
            this.transitionDebugId = readTransitionDebugId(transitionInfo);
        }
    }

    protected final AtomicReference<PreparedBackTransitionHold>
            preparedBackTransitionHold = new AtomicReference<>();
    protected final AtomicReference<PreparedBackTargetArrival>
            preparedBackTargetArrival = new AtomicReference<>();
    protected volatile XposedInterface.Invoker<?, Method>
            preparedBackStartAnimationInvoker;
    protected volatile boolean preparedBackTargetArrivalHookReady;
    protected volatile boolean preparedBackTerminalHookReady;

    protected void hookPreparedBackTargetArrival(ClassLoader classLoader) {
        try {
            Class<?> adapterClass = Class.forName(
                    BACK_ANIMATION_CONTROLLER + "$3", false, classLoader);
            Method onAnimationStart = findAnyMethod(
                    adapterClass, "onAnimationStart", 3);
            if (onAnimationStart == null) {
                throw new NoSuchMethodException(
                        "Back animation adapter onAnimationStart");
            }
            onAnimationStart.setAccessible(true);
            recordHookHandle(hook(onAnimationStart)
                    .setId("systemui_back_prepared_target_arrival")
                    .intercept(this::onPreparedBackTargetArrival));
            preparedBackTargetArrivalHookReady = true;
            moduleLog(Log.INFO, TAG,
                    "Hooked prepared-back remote-target arrival handoff");
        } catch (Throwable throwable) {
            preparedBackTargetArrivalHookReady = false;
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook prepared-back remote-target arrival handoff",
                    throwable);
        }
    }

    protected void hookPreparedBackTerminal(Class<?> controllerClass) {
        try {
            Method finishBackNavigation = controllerClass.getDeclaredMethod(
                    "finishBackNavigation", boolean.class);
            finishBackNavigation.setAccessible(true);
            recordHookHandle(hook(finishBackNavigation)
                    .setId("systemui_back_prepared_terminal")
                    .intercept(this::onPreparedBackTerminal));
            preparedBackTerminalHookReady = true;
            moduleLog(Log.INFO, TAG,
                    "Hooked prepared-back terminal handoff");
        } catch (Throwable throwable) {
            preparedBackTerminalHookReady = false;
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook prepared-back terminal handoff",
                    throwable);
        }
    }

    protected Object onPreparedBackTargetArrival(XposedInterface.Chain chain)
            throws Throwable {
        Object controller = null;
        Object apps = null;
        Object token = null;
        Object finishedCallback = null;
        int navigationType = -1;
        try {
            controller = readField(chain.getThisObject(), "this$0");
            apps = chain.getArg(0);
            token = chain.getArg(1);
            finishedCallback = chain.getArg(2);
            Object navigation = readFieldOrNull(controller, "mBackNavigationInfo");
            if (navigation instanceof BackNavigationInfo) {
                navigationType = ((BackNavigationInfo) navigation).getType();
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture prepared-back target arrival",
                    throwable);
        }
        Object result = chain.proceed();
        if (navigationType == TYPE_CROSS_TASK) {
            moduleLog(Log.INFO, TAG, "Cross-task remote targets arrived"
                    + ", token=" + shortObject(token)
                    + ", apps=" + describeRemoteAnimationTargets(apps)
                    + ", finishedCallback=" + shortObject(finishedCallback));
            logCrossTaskTargetDiagnostics();
        }
        if (controller != null && token != null) {
            PreparedBackTargetArrival arrival = new PreparedBackTargetArrival(
                    controller, token, apps, finishedCallback);
            preparedBackTargetArrival.set(arrival);
            schedulePreparedBackTransitionResume(
                    preparedBackTransitionHold.get(), arrival, false);
        }
        return result;
    }

    protected Object onPreparedBackTerminal(
            XposedInterface.Chain chain) throws Throwable {
        PreparedBackTransitionHold hold = preparedBackTransitionHold.get();
        boolean exactTerminal = false;
        try {
            exactTerminal = hold != null
                    && hold.controller == chain.getThisObject()
                    && isExactPreparedBackSession(hold)
                    && isHeldPreparedBackTransitionUntouched(hold);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to authenticate prepared-back terminal",
                    throwable);
        }
        Object result = chain.proceed();
        if (exactTerminal) {
            schedulePreparedBackTransitionResume(hold, null, true);
        }
        return result;
    }

    protected boolean isExactPreparedBackTransition(
            Object handler, Object navigation, Object info) throws Exception {
        if (!(navigation instanceof BackNavigationInfo)) {
            return false;
        }
        int focusedTaskId = ((BackNavigationInfo) navigation).getFocusedTaskId();
        if (focusedTaskId < 0) {
            return false;
        }
        Object transitions = readField(handler, "mTransitions");
        Object organizer = readField(transitions, "mOrganizer");
        Object taskInfo = invokeAnyMethod(organizer, "getRunningTaskInfo",
                new Object[]{Integer.valueOf(focusedTaskId)});
        if (taskInfo == null
                || readIntFieldOrDefault(taskInfo, "taskId", -1)
                != focusedTaskId
                || resolveTaskInfoActivityType(taskInfo)
                != ACTIVITY_TYPE_STANDARD) {
            return false;
        }
        int windowingMode = resolveTaskInfoWindowingMode(taskInfo);
        if (windowingMode != WINDOWING_MODE_FREEFORM
                && windowingMode != WINDOWING_MODE_FULLSCREEN) {
            return false;
        }
        int displayId = readIntFieldOrDefault(taskInfo, "displayId", -1);
        Object configuration = readField(taskInfo, "configuration");
        Object windowConfiguration = readField(
                configuration, "windowConfiguration");
        Object taskBoundsObject = invokeAnyMethod(
                windowConfiguration, "getBounds", new Object[0]);
        Object changesObject = readTransitionInfoChanges(info);
        Object rootCountObject = readTransitionInfoRootCount(info);
        if (displayId < 0 || !(taskBoundsObject instanceof Rect)
                || ((Rect) taskBoundsObject).isEmpty()
                || !(changesObject instanceof List<?>)
                || ((List<?>) changesObject).size() != 2
                || !(rootCountObject instanceof Number)
                || ((Number) rootCountObject).intValue() != 1) {
            throw new IllegalStateException(
                    "prepared task geometry unavailable"
                            + ", taskId=" + focusedTaskId
                            + ", displayId=" + displayId
                            + ", bounds=" + shortObject(taskBoundsObject)
                            + ", changes=" + shortObject(changesObject)
                            + ", roots=" + shortObject(rootCountObject));
        }
        Rect taskBounds = (Rect) taskBoundsObject;
        Object root = readTransitionInfoRoot(info, 0);
        Object rootLeashObject = readTransitionRootLeash(root);
        Object rootOffsetObject = readTransitionRootOffset(root);
        if (!(rootLeashObject instanceof SurfaceControl)
                || !((SurfaceControl) rootLeashObject).isValid()
                || !(rootOffsetObject instanceof Point)
                || ((Point) rootOffsetObject).x != taskBounds.left
                || ((Point) rootOffsetObject).y != taskBounds.top) {
            throw new IllegalStateException(
                    "prepared root mismatch"
                            + ", taskId=" + focusedTaskId
                            + ", bounds=" + taskBounds
                            + ", root=" + shortObject(rootLeashObject)
                            + ", offset=" + shortObject(rootOffsetObject));
        }
        SurfaceControl rootLeash = (SurfaceControl) rootLeashObject;
        final int closingFlags = 0x08000000
                | FLAG_BACK_GESTURE_ANIMATED | FLAG_FILLS_TASK;
        final int openingFlags = FLAG_BACK_GESTURE_ANIMATED
                | FLAG_FILLS_TASK | FLAG_IS_OCCLUDED;
        Object closingComponent = null;
        Object openingComponent = null;
        SurfaceControl closingLeash = null;
        SurfaceControl openingLeash = null;
        int changeIndex = 0;
        for (Object change : (List<?>) changesObject) {
            Object modeObject = readTransitionChangeMode(change);
            Object flagsObject = readTransitionChangeFlags(change);
            Object changeTaskInfo = readTransitionChangeTaskInfo(change);
            Object component = readTransitionChangeActivityComponent(change);
            Object leashObject = readTransitionChangeLeash(change);
            Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
            Object endBoundsObject = readTransitionChangeEndAbsBounds(change);
            Object startDisplayObject = readTransitionChangeStartDisplayId(change);
            Object endDisplayObject = readTransitionChangeEndDisplayId(change);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : -1;
            if (changeTaskInfo != null || component == null
                    || !(leashObject instanceof SurfaceControl)
                    || !((SurfaceControl) leashObject).isValid()
                    || surfacesAreSame(
                    (SurfaceControl) leashObject, rootLeash)
                    || !taskBounds.equals(startBoundsObject)
                    || !taskBounds.equals(endBoundsObject)
                    || !(startDisplayObject instanceof Number)
                    || !(endDisplayObject instanceof Number)
                    || ((Number) startDisplayObject).intValue() != displayId
                    || ((Number) endDisplayObject).intValue() != displayId) {
                throw new IllegalStateException(
                        "prepared Activity change mismatch"
                                + ", taskId=" + focusedTaskId
                                + ", changeIndex=" + changeIndex
                                + ", mode=" + mode
                                + ", flags=0x" + Integer.toHexString(flags)
                                + ", taskInfo=" + shortObject(changeTaskInfo)
                                + ", component=" + shortObject(component)
                                + ", leash=" + shortObject(leashObject)
                                + ", startBounds="
                                + shortObject(startBoundsObject)
                                + ", endBounds=" + shortObject(endBoundsObject)
                                + ", startDisplay="
                                + shortObject(startDisplayObject)
                                + ", endDisplay="
                                + shortObject(endDisplayObject));
            }
            if (mode == TRANSIT_CHANGE && flags == closingFlags
                    && closingComponent == null) {
                closingComponent = component;
                closingLeash = (SurfaceControl) leashObject;
            } else if (mode == TRANSIT_TO_FRONT && flags == openingFlags
                    && openingComponent == null) {
                openingComponent = component;
                openingLeash = (SurfaceControl) leashObject;
            } else {
                throw new IllegalStateException(
                        "prepared Activity role mismatch"
                                + ", taskId=" + focusedTaskId
                                + ", changeIndex=" + changeIndex
                                + ", mode=" + mode
                                + ", flags=0x" + Integer.toHexString(flags));
            }
            changeIndex++;
        }
        return closingComponent != null && openingComponent != null
                && !surfacesAreSame(closingLeash, openingLeash);
    }

    protected void hookPreparedBackTransitionDecision(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method startAnimation = requireExactDeclaredMethod(handlerClass,
                    "startAnimation", "boolean", IBinder.class.getName(),
                    TransitionInfo.class.getName(),
                    SurfaceControl.Transaction.class.getName(),
                    SurfaceControl.Transaction.class.getName(),
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback");
            preparePreparedBackStartAnimationInvoker(startAnimation);
            recordHookHandle(hook(startAnimation)
                    .setId("systemui_back_prepared_transition_decision")
                    .intercept(this::holdPreparedBackTransitionUntilTargets));
            moduleLog(Log.INFO, TAG,
                    "Hooked prepared-back transition target ordering");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook prepared-back transition target ordering",
                    throwable);
        }
    }

    protected void preparePreparedBackStartAnimationInvoker(Method startAnimation) {
        XposedInterface.Invoker<?, Method> invoker = getInvoker(startAnimation);
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
        preparedBackStartAnimationInvoker = invoker;
    }

    protected Object holdPreparedBackTransitionUntilTargets(
            XposedInterface.Chain chain) throws Throwable {
        if (!preparedBackTargetArrivalHookReady
                || !preparedBackTerminalHookReady
                || preparedBackStartAnimationInvoker == null) {
            return chain.proceed();
        }
        PreparedBackTransitionHold hold;
        try {
            Object info = chain.getArg(1);
            Object type = readTransitionInfoType(info);
            if (!(type instanceof Number)
                    || ((Number) type).intValue() != TRANSIT_PREDICTIVE_BACK) {
                return chain.proceed();
            }
            if (!(chain.getArg(2) instanceof SurfaceControl.Transaction)
                    || !(chain.getArg(3) instanceof SurfaceControl.Transaction)
                    || chain.getArg(0) == null || chain.getArg(4) == null) {
                return chain.proceed();
            }
            Object handler = chain.getThisObject();
            Object controller = readField(handler, "this$0");
            Object transitionToken = chain.getArg(0);
            if (readField(controller, "mBackTransitionHandler") != handler
                    || readField(controller, "mApps") != null
                    || readField(handler, "mPrepareOpenTransition")
                    != transitionToken
                    || readField(handler, "mClosePrepareTransition") != null
                    || readField(handler, "mOpenTransitionInfo") != null
                    || readField(handler, "mFinishOpenTransaction") != null
                    || readField(handler, "mFinishOpenTransitionCallback") != null
                    || readField(handler, "mOnAnimationFinishCallback") != null
                    || Boolean.TRUE.equals(readField(
                    handler, "mCloseTransitionRequested"))) {
                return chain.proceed();
            }
            Object navigation = readField(controller, "mBackNavigationInfo");
            Object navigationType = readBackNavigationType(navigation);
            if (!(navigationType instanceof Number)
                    || ((Number) navigationType).intValue()
                    != TYPE_CROSS_ACTIVITY) {
                return chain.proceed();
            }
            if (!isExactPreparedBackTransition(
                    handler, navigation, info)) {
                return chain.proceed();
            }
            NativeBackInputMonitor exactMonitor = null;
            SystemUiBackGestureDriver.ShellGestureSession exactSession = null;
            Object currentTracker = readField(controller, "mCurrentTracker");
            for (NativeBackInputMonitor monitor
                    : new ArrayList<>(nativeInputMonitors.values())) {
                SystemUiBackGestureDriver.ShellGestureSession session =
                        monitor.driver.activeShellSession;
                if (session == null || session.controller != controller
                        || session.navigation != navigation
                        || session.tracker != currentTracker
                        || session.completionConsumed.get()
                        || !monitor.driver.isShellSessionOwnerCurrent(session)) {
                    continue;
                }
                if (exactSession != null) {
                    return chain.proceed();
                }
                exactMonitor = monitor;
                exactSession = session;
            }
            if (exactSession == null) {
                return chain.proceed();
            }
            Object shellExecutor = readField(controller, "mShellExecutor");
            Object shellHandler = readField(shellExecutor, "mHandler");
            if (shellExecutor != exactSession.executor
                    || !(shellHandler instanceof Handler)
                    || !((Handler) shellHandler).getLooper().isCurrentThread()) {
                return chain.proceed();
            }
            hold = new PreparedBackTransitionHold(
                    exactMonitor, exactSession, (Handler) shellHandler,
                    preparedBackStartAnimationInvoker,
                    handler, controller,
                    transitionToken, info,
                    (SurfaceControl.Transaction) chain.getArg(2),
                    (SurfaceControl.Transaction) chain.getArg(3),
                    chain.getArg(4));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to qualify prepared-back transition hold",
                    throwable);
            return chain.proceed();
        }
        if (!preparedBackTransitionHold.compareAndSet(null, hold)) {
            return chain.proceed();
        }
        moduleLog(Log.INFO, TAG,
                "Held prepared-back transition until remote targets"
                        + ", transitionId=" + hold.transitionDebugId
                        + ", shellSessionId=" + hold.session.id
                        + ", token=" + shortObject(hold.transitionToken));
        schedulePreparedBackTransitionResume(
                hold, preparedBackTargetArrival.get(), false);
        return Boolean.TRUE;
    }

    protected void schedulePreparedBackTransitionResume(
            PreparedBackTransitionHold hold,
            PreparedBackTargetArrival arrival, boolean terminal) {
        if (hold == null || preparedBackTransitionHold.get() != hold
                || (!terminal && (arrival == null
                || arrival.controller != hold.controller
                || arrival.transitionToken != hold.transitionToken))) {
            return;
        }
        try {
            if (!hold.shellHandler.post(() -> resumePreparedBackTransition(
                    hold, arrival, terminal))) {
                throw new IllegalStateException("Shell Handler rejected resume");
            }
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to queue held prepared-back transition resume"
                            + ", transitionId=" + hold.transitionDebugId
                            + ", event=" + (terminal ? "terminal" : "targets"),
                    throwable);
        }
    }

    protected void resumePreparedBackTransition(
            PreparedBackTransitionHold hold,
            PreparedBackTargetArrival arrival, boolean terminal) {
        String event = terminal ? "terminal" : "targets";
        try {
            if (preparedBackTransitionHold.get() != hold
                    || (terminal
                    ? !isHeldPreparedBackTransitionTerminalReady(hold)
                    : !isHeldPreparedBackTransitionUntouched(hold))) {
                return;
            }
            Object controllerApps = readField(hold.controller, "mApps");
            if (!terminal) {
                PreparedBackTargetArrival latest =
                        preparedBackTargetArrival.get();
                if (latest != arrival) {
                    schedulePreparedBackTransitionResume(
                            hold, latest, false);
                    return;
                }
                if (controllerApps != arrival.apps
                        || readField(hold.controller,
                        "mBackAnimationFinishedCallback")
                        != arrival.finishedCallback
                        || !isExactPreparedBackSession(hold)) {
                    return;
                }
            } else if (controllerApps != null
                    || readField(hold.controller, "mBackNavigationInfo") != null) {
                return;
            }
            if (!hold.stockResumeAttempted.compareAndSet(false, true)) {
                return;
            }
            Object result;
            try {
                result = hold.startAnimationInvoker.invoke(
                        hold.handler, hold.transitionToken,
                        hold.transitionInfo, hold.startTransaction,
                        hold.finishTransaction, hold.finishCallback);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                throw cause == null ? exception : cause;
            }
            if (!Boolean.TRUE.equals(result)) {
                moduleLog(Log.ERROR, TAG,
                        "Stock handler declined held prepared-back transition"
                                + ", transitionId=" + hold.transitionDebugId
                                + ", shellSessionId=" + hold.session.id
                                + ", event=" + event);
                return;
            }
            if (!preparedBackTransitionHold.compareAndSet(hold, null)) {
                moduleLog(Log.ERROR, TAG,
                        "Lost held prepared-back ownership after stock resume"
                                + ", transitionId=" + hold.transitionDebugId
                                + ", shellSessionId=" + hold.session.id);
                return;
            }
            PreparedBackTargetArrival consumedArrival = arrival != null
                    ? arrival : preparedBackTargetArrival.get();
            if (consumedArrival != null
                    && consumedArrival.controller == hold.controller
                    && consumedArrival.transitionToken == hold.transitionToken) {
                preparedBackTargetArrival.compareAndSet(
                        consumedArrival, null);
            }
            moduleLog(Log.INFO, TAG,
                    "Resumed held prepared-back transition through stock handler"
                            + ", transitionId=" + hold.transitionDebugId
                            + ", shellSessionId=" + hold.session.id
                            + ", event=" + event
                            + ", apps=" + shortObject(controllerApps));
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to resume held prepared-back transition"
                            + ", transitionId=" + hold.transitionDebugId
                            + ", shellSessionId=" + hold.session.id
                            + ", event=" + event,
                    throwable);
        }
    }

    protected boolean isExactPreparedBackSession(
            PreparedBackTransitionHold hold) throws Exception {
        return hold.monitor.driver.activeShellSession == hold.session
                && !hold.session.completionConsumed.get()
                && hold.monitor.driver.isShellSessionOwnerCurrent(hold.session)
                && readField(hold.controller, "mBackNavigationInfo")
                == hold.session.navigation
                && (readField(hold.controller, "mCurrentTracker")
                == hold.session.tracker
                || readField(hold.controller, "mQueuedTracker")
                == hold.session.tracker);
    }

    protected boolean isHeldPreparedBackTransitionUntouched(
            PreparedBackTransitionHold hold) throws Exception {
        return isHeldPreparedBackTransitionBase(hold)
                && readField(hold.handler, "mClosePrepareTransition") == null
                && !Boolean.TRUE.equals(readField(
                hold.handler, "mCloseTransitionRequested"));
    }

    protected boolean isHeldPreparedBackTransitionTerminalReady(
            PreparedBackTransitionHold hold) throws Exception {
        return isHeldPreparedBackTransitionBase(hold);
    }

    protected boolean isHeldPreparedBackTransitionBase(
            PreparedBackTransitionHold hold) throws Exception {
        Object type = readTransitionInfoType(hold.transitionInfo);
        return readField(hold.handler, "this$0") == hold.controller
                && readField(hold.controller, "mBackTransitionHandler")
                == hold.handler
                && type instanceof Number
                && ((Number) type).intValue() == TRANSIT_PREDICTIVE_BACK
                && readField(hold.handler, "mPrepareOpenTransition")
                == hold.transitionToken
                && readField(hold.handler, "mOpenTransitionInfo") == null
                && readField(hold.handler, "mFinishOpenTransaction") == null
                && readField(hold.handler,
                "mFinishOpenTransitionCallback") == null
                && readField(hold.handler, "mOnAnimationFinishCallback") == null;
    }

    protected String describePreparedBackTransitionHold(
            PreparedBackTransitionHold hold) {
        return "transitionId=" + hold.transitionDebugId
                + ", shellSessionId=" + hold.session.id
                + ", stockResumeAttempted="
                + hold.stockResumeAttempted.get();
    }

    /**
     * Restyles the native cross-activity predictive-back animation into the miuix slide
     * when the preference is on: the closing surface follows the finger full-width with
     * no scale and no fade, the entering surface parallaxes in from a quarter width
     * behind at alpha 0.9 -> 1 with its dim scrim tracking the drag, and the commit
     * settles on a cubic ease-out. Exact freeform puts Xiaomi's task-local radius on
     * the prepared root and both Activity targets; the default and slide geometries
     * also inverse-map a fixed task crop into each moving target. Fullscreen still
     * clears only the revealed lower page. Targets, letterboxes, and the finish
     * lifecycle stay native.
     * Cross-task and return-to-home are untouched. The independent apply hook adopts
     * exact freeform ColorLayers and normalizes those corners whether or not the slide
     * preference is enabled.
     */
    protected void hookFreeformCrossActivityScrimCreation() {
        try {
            Method setHidden = requireExactDeclaredMethod(SurfaceControl.Builder.class,
                    "setHidden", SurfaceControl.Builder.class.getName(), "boolean");
            recordHookHandle(hook(setHidden)
                    .setId("systemui_back_color_root_scrim_creation")
                    .intercept(this::keepFreeformScrimHiddenUntilFirstApply));
            moduleLog(Log.INFO, TAG,
                    "Hooked freeform cross-activity scrim creation visibility");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook freeform cross-activity scrim creation",
                    throwable);
        }
    }

    protected void hookCrossActivitySlideAnimation(ClassLoader classLoader,
                                                   boolean installStart,
                                                   boolean installProgress,
                                                   boolean installPostCommit,
                                                   boolean installDuration,
                                                   boolean installFinish,
                                                   boolean installColorRootApply) {
        Class<?> baseClass;
        Class<?> defaultClass;
        Class<?> backMotionEventClass;
        try {
            baseClass = Class.forName(
                    CROSS_ACTIVITY_BACK_ANIMATION, false, classLoader);
            defaultClass = Class.forName(
                    DEFAULT_CROSS_ACTIVITY_BACK_ANIMATION, false, classLoader);
            backMotionEventClass = BackMotionEvent.class;
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Cross-activity animation classes unavailable",
                    throwable);
            return;
        }
        // Each hook installs independently: R8 may rename individual members in
        // Xiaomi's build, and a missing one must only degrade its own stage.
        if (installColorRootApply) {
            try {
                Method apply = resolveSlideMethod(defaultClass, baseClass,
                        "applyTransaction", void.class);
                recordHookHandle(hook(apply)
                        .setId("systemui_back_color_root_apply")
                        .intercept(this::onCrossActivityColorRootApply));
                moduleLog(Log.INFO, TAG, "Hooked freeform color-layer root adoption");
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed to hook freeform color-layer root adoption", throwable);
            }
        }
        if (installStart) {
            try {
                Method start = resolveSlideMethod(defaultClass, baseClass,
                        "startBackAnimation", void.class, backMotionEventClass);
                recordHookHandle(hook(start)
                        .setId("systemui_back_slide_start")
                        .intercept(this::onCrossActivitySlideStart));
                moduleLog(Log.INFO, TAG, "Hooked slide start as " + start.getName());
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide start", throwable);
            }
        }
        if (installProgress) {
            try {
                // R8 inlines the private per-frame method into its registration
                // lambda, so the stable interception point is the framework
                // BackProgressAnimator: swap the registered ProgressCallback for the
                // armed animation's own animator and drive the frames ourselves.
                Method register = BackProgressAnimator.class.getDeclaredMethod(
                        "onBackStarted", BackMotionEvent.class,
                        BackProgressAnimator.ProgressCallback.class);
                register.setAccessible(true);
                recordHookHandle(hook(register)
                        .setId("systemui_back_slide_progress")
                        .intercept(this::onCrossActivitySlideProgressRegistration));
                moduleLog(Log.INFO, TAG,
                        "Hooked slide progress via BackProgressAnimator registration");
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide progress", throwable);
            }
        }
        if (installPostCommit) {
            try {
                Method postCommit = resolveSlideMethod(defaultClass, baseClass,
                        "onPostCommitProgress", void.class, float.class);
                // Hooked on the base class means this is the super-call position:
                // the subclass override keeps writing its native geometry after our
                // interceptor returns, so our frame must be applied afterwards.
                miuixSlidePostCommitOnBase =
                        postCommit.getDeclaringClass() == baseClass;
                recordHookHandle(hook(postCommit)
                        .setId("systemui_back_slide_post_commit")
                        .intercept(this::onCrossActivitySlidePostCommit));
                moduleLog(Log.INFO, TAG, "Hooked slide post-commit as "
                        + postCommit.getDeclaringClass().getSimpleName()
                        + "." + postCommit.getName()
                        + ", superPosition=" + miuixSlidePostCommitOnBase);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide post-commit", throwable);
            }
        }
        if (installDuration) {
            try {
                Method duration = resolveSlideMethod(defaultClass, baseClass,
                        "getPostCommitAnimationDuration", long.class);
                recordHookHandle(hook(duration)
                        .setId("systemui_back_slide_duration")
                        .intercept(this::onCrossActivitySlideDuration));
                moduleLog(Log.INFO, TAG, "Hooked slide duration as " + duration.getName());
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide duration", throwable);
            }
        }
        if (installFinish) {
            try {
                Method finish = resolveSlideMethod(defaultClass, baseClass,
                        "finishAnimation", void.class);
                recordHookHandle(hook(finish)
                        .setId("systemui_back_slide_finish")
                        .intercept(this::onCrossActivitySlideFinish));
                moduleLog(Log.INFO, TAG, "Hooked slide finish as "
                        + finish.getDeclaringClass().getSimpleName()
                        + "." + finish.getName());
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to hook slide finish", throwable);
            }
        }
    }

    protected void hookCrossTaskBackground(ClassLoader classLoader) {
        try {
            Class<?> backgroundClass = Class.forName(
                    BACK_ANIMATION_BACKGROUND, false, classLoader);
            int expectedParameterCount = requireSystemUiPlatformImpl()
                    .backAnimationBackgroundEnsureParameterCount();
            for (Method method : backgroundClass.getDeclaredMethods()) {
                if ("ensureBackground".equals(method.getName())
                        && method.getParameterCount() == expectedParameterCount) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("systemui_cross_task_background")
                            .intercept(this::tintCrossTaskBackground));
                    moduleLog(Log.INFO, TAG, "Hooked cross-task background tint");
                    return;
                }
            }
            moduleLog(Log.WARN, TAG, "BackAnimationBackground.ensureBackground not found");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook cross-task background", throwable);
        }
    }

    /**
     * With the slide preference on, repaints the native cross-task color-layer
     * background pure black. ensureBackground(bounds, color, transaction, ...) creates
     * the color layer and writes the color into the caller's pending transaction; when
     * the color is cross-task's hard-coded tint, overwrite it on that same transaction
     * before it is applied. Cross-activity passes its task color and is left alone.
     */
    protected Object tintCrossTaskBackground(XposedInterface.Chain chain)
            throws Throwable {
        Object colorArg = chain.getArg(1);
        int color = colorArg instanceof Number ? ((Number) colorArg).intValue() : 0;
        Object result = chain.proceed();
        try {
            if (color != CROSS_TASK_BACKGROUND_COLOR) {
                return result;
            }
            logCrossTaskTargetDiagnostics();
            if (!isHyperOsSlideAnimationEnabled()) {
                return result;
            }
            Object surface = readFieldOrNull(
                    chain.getThisObject(), "mBackgroundSurface");
            Object transaction = chain.getArg(2);
            if (surface instanceof SurfaceControl
                    && ((SurfaceControl) surface).isValid()
                    && transaction instanceof SurfaceControl.Transaction) {
                invokeMethod(transaction, "setColor",
                        new Class<?>[]{SurfaceControl.class, float[].class},
                        new Object[]{surface, new float[]{0.0f, 0.0f, 0.0f}});
                moduleLog(Log.INFO, TAG, "Repainted cross-task background black");
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to tint cross-task background black", throwable);
        }
        return result;
    }

    protected String describeRemoteAnimationTargets(Object targets) {
        if (targets == null || !targets.getClass().isArray()) {
            return shortObject(targets);
        }
        int count = java.lang.reflect.Array.getLength(targets);
        StringBuilder description = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                description.append(", ");
            }
            description.append(describeRemoteAnimationTarget(
                    java.lang.reflect.Array.get(targets, index)));
        }
        return description.append(']').toString();
    }

    /**
     * Records the exact targets owned by Shell's native cross-task animation at the point
     * where it creates its background. This is deliberately read-only: compositor alpha
     * must not be corrected until the faulty leash and frame are proven on-device.
     */
    protected void logCrossTaskTargetDiagnostics() {
        Object animation = aospCrossTaskAnimation;
        if (animation == null) {
            moduleLog(Log.WARN, TAG, "Cross-task diagnostic missing registry animation");
            return;
        }
        Object closingTarget = readFieldOrNull(animation, "mClosingTarget");
        Object enteringTarget = readFieldOrNull(animation, "mEnteringTarget");
        moduleLog(Log.INFO, TAG, "Cross-task exact targets"
                + ", animation=" + shortObject(animation)
                + ", closing=" + describeRemoteAnimationTarget(closingTarget)
                + ", entering=" + describeRemoteAnimationTarget(enteringTarget)
                + ", closingRect="
                + shortObject(readFieldOrNull(animation, "mClosingCurrentRect"))
                + ", enteringRect="
                + shortObject(readFieldOrNull(animation, "mEnteringCurrentRect")));
    }

    protected String describeRemoteAnimationTarget(Object target) {
        if (target == null || target instanceof String) {
            return shortObject(target);
        }
        return "{identity=" + shortObject(target)
                + ", taskId=" + readIntFieldOrDefault(target, "taskId", -1)
                + ", mode=" + readIntFieldOrDefault(target, "mode", -1)
                + ", leash=" + shortObject(readFieldOrNull(target, "leash"))
                + ", localBounds=" + shortObject(readFieldOrNull(target, "localBounds"))
                + ", screenBounds="
                + shortObject(readFieldOrNull(target, "screenSpaceBounds"))
                + ", translucent=" + shortObject(readFieldOrNull(target, "isTranslucent"))
                + "}";
    }

    // finishAnimation() is the animation's natural end; clear the session flag so a
    // later gesture re-arms cleanly. The original always runs.
    protected Object onCrossActivitySlideFinish(XposedInterface.Chain chain)
            throws Throwable {
        miuixSlideAnimActive = false;
        freeformColorRootCandidate.set(null);
        FreeformColorRootAdoption adoption = freeformColorRootAdoption;
        if (adoption != null && adoption.animation == chain.getThisObject()) {
            freeformColorRootAdoption = null;
        }
        return chain.proceed();
    }

    /**
     * Resolves an animation-class member by name first, then falls back to a unique
     * signature match so an R8-renamed member is still found. Same-name declarations
     * across the hierarchy are one virtual method — the most-derived one wins;
     * different-name candidates at the same level are ambiguous and fail.
     */
    protected Method resolveSlideMethod(Class<?> leaf, Class<?> stop, String name,
                                        Class<?> returnType, Class<?>... parameters)
            throws NoSuchMethodException {
        try {
            return findDeclaredMethodInHierarchy(leaf, stop, name, parameters);
        } catch (NoSuchMethodException ignored) {
        }
        Class<?> current = leaf;
        while (current != null) {
            Method match = null;
            for (Method candidate : current.getDeclaredMethods()) {
                if (candidate.isSynthetic()
                        || candidate.getReturnType() != returnType
                        || !Arrays.equals(candidate.getParameterTypes(), parameters)) {
                    continue;
                }
                if (match != null) {
                    throw new NoSuchMethodException(name
                            + ": ambiguous signature fallback in " + current.getName()
                            + " (" + match.getName() + " vs " + candidate.getName()
                            + ")");
                }
                match = candidate;
            }
            if (match != null) {
                match.setAccessible(true);
                moduleLog(Log.INFO, TAG, "Resolved " + name + " by signature as "
                        + current.getName() + "." + match.getName());
                return match;
            }
            if (current == stop) {
                break;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(leaf.getName() + "." + name);
    }

    protected Method findDeclaredMethodInHierarchy(Class<?> leaf, Class<?> stop,
                                                   String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Class<?> current = leaf;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            if (current == stop) {
                break;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(leaf.getName() + "." + name);
    }

    protected volatile boolean miuixSlideAnimActive;
    protected final AtomicReference<OneUiCrossTaskSession>
            oneUiCrossTaskSession = new AtomicReference<>();
    protected volatile Method oneUiCrossTaskFinishMethod;
    protected boolean oneUiCrossTaskRegistrationReentry;
    protected final RectF miuixSlideCommitClosing = new RectF();
    protected final RectF miuixSlideCommitEntering = new RectF();
    protected boolean miuixSlideCommitPoseCaptured;
    protected float miuixSlideCommitEnteringAlpha = 1.0f;
    protected float miuixSlideCommitScrimAlpha;
    protected float miuixSlideCommitScrimVelocity;
    protected float miuixSlideProgressVelocity;
    protected float miuixSlideLastProgressSample;
    protected long miuixSlideLastProgressSampleMs;
    protected boolean miuixSlidePostCommitOnBase;
    protected volatile WeakReference<Object> miuixSlideArmedAnimation;
    protected boolean miuixSlideRegistrationReentry;
    protected volatile Method crossActivityApplyTransform;
    protected volatile Object crossActivityNoFling;
    protected volatile Method multiTaskingControllerGetInstance;
    protected final AtomicReference<FreeformColorRootCandidate>
            freeformColorRootCandidate = new AtomicReference<>();
    protected volatile FreeformColorRootAdoption freeformColorRootAdoption;

    protected static final long MIUIX_SLIDE_SETTLE_DURATION_MS = 400L;
    protected static final float MIUIX_SLIDE_ENTERING_MIN_ALPHA = 0.9f;
    protected static final float MIUIX_SLIDE_PARALLAX_FRACTION = 0.25f;
    protected static final float MIUIX_SLIDE_SCRIM_OMEGA = 12.083f;
    protected static final float MIUIX_SLIDE_SCRIM_MAX_ALPHA = 0.5f;
    protected static final long ONEUI_CROSS_TASK_SETTLE_DURATION_MS = 525L;
    protected static final float ONEUI_CROSS_TASK_MARGIN_FRACTION = 0.08f;
    protected static final float ONEUI_CROSS_TASK_TOUCH_Y_FRACTION = 0.1f;
    protected static final PathInterpolator ONEUI_CROSS_TASK_SETTLE_INTERPOLATOR =
            new PathInterpolator(0.22f, 0.25f, 0.0f, 1.0f);

    protected static final class OneUiCrossTaskSession {
        final Object animation;
        final BackProgressAnimator progressAnimator;
        final Object closingTarget;
        final Object enteringTarget;
        final SurfaceControl closingLeash;
        final SurfaceControl enteringLeash;
        final SurfaceControl.Transaction transaction;
        final BackProgressAnimator.ProgressCallback nativeProgressCallback;
        final Rect taskRect;
        final Matrix matrix = new Matrix();
        final float[] matrixValues = new float[9];
        final RectF closingCurrent = new RectF();
        final RectF enteringCurrent = new RectF();
        final AtomicBoolean terminal = new AtomicBoolean();
        final float cornerRadius;
        final int closingTaskId;
        final int enteringTaskId;
        final float initialTouchY;
        volatile float scale = 1.0f;
        volatile ValueAnimator settleAnimator;
        volatile BackEvent lastEvent;
        volatile boolean invokingNativeFinish;

        OneUiCrossTaskSession(Object animation,
                              BackProgressAnimator progressAnimator,
                              Object closingTarget, Object enteringTarget,
                              SurfaceControl closingLeash,
                              SurfaceControl enteringLeash,
                              SurfaceControl.Transaction transaction,
                              BackProgressAnimator.ProgressCallback nativeProgressCallback,
                              Rect taskRect, float cornerRadius,
                              int closingTaskId, int enteringTaskId,
                              float initialTouchY) {
            this.animation = animation;
            this.progressAnimator = progressAnimator;
            this.closingTarget = closingTarget;
            this.enteringTarget = enteringTarget;
            this.closingLeash = closingLeash;
            this.enteringLeash = enteringLeash;
            this.transaction = transaction;
            this.nativeProgressCallback = nativeProgressCallback;
            this.taskRect = taskRect;
            this.cornerRadius = cornerRadius;
            this.closingTaskId = closingTaskId;
            this.enteringTaskId = enteringTaskId;
            this.initialTouchY = initialTouchY;
        }
    }

    protected static final class FreeformColorRootCandidate {
        final Object handler;
        final Object transitionToken;
        final Object transitionInfo;
        final Object appsIdentity;
        final Object closingTarget;
        final Object enteringTarget;
        final SurfaceControl rootLeash;
        final SurfaceControl closingLeash;
        final SurfaceControl enteringLeash;
        final float rootCornerRadius;

        FreeformColorRootCandidate(Object handler, Object transitionToken,
                                   Object transitionInfo,
                                   Object appsIdentity, Object closingTarget,
                                   Object enteringTarget, SurfaceControl rootLeash,
                                   SurfaceControl closingLeash,
                                   SurfaceControl enteringLeash,
                                   float rootCornerRadius) {
            this.handler = handler;
            this.transitionToken = transitionToken;
            this.transitionInfo = transitionInfo;
            this.appsIdentity = appsIdentity;
            this.closingTarget = closingTarget;
            this.enteringTarget = enteringTarget;
            this.rootLeash = rootLeash;
            this.closingLeash = closingLeash;
            this.enteringLeash = enteringLeash;
            this.rootCornerRadius = rootCornerRadius;
        }
    }

    protected static final class FreeformColorRootAdoption {
        final Object animation;
        final FreeformColorRootCandidate candidate;
        final Rect closingCrop = new Rect();
        final Rect enteringCrop = new Rect();

        FreeformColorRootAdoption(Object animation,
                                  FreeformColorRootCandidate candidate) {
            this.animation = animation;
            this.candidate = candidate;
        }
    }

    protected Object keepFreeformScrimHiddenUntilFirstApply(
            XposedInterface.Chain chain) throws Throwable {
        FreeformColorRootCandidate candidate = freeformColorRootCandidate.get();
        Object builder = chain.getThisObject();
        if (candidate == null
                || !Boolean.FALSE.equals(chain.getArg(0))
                || !"Cross-Activity back animation scrim".equals(
                readFieldOrNull(builder, "mName"))
                || !"CrossActivityBackAnimation".equals(
                readFieldOrNull(builder, "mCallsite"))) {
            return chain.proceed();
        }
        moduleLog(Log.INFO, TAG,
                "Kept freeform cross-activity scrim hidden until atomic first apply"
                        + ", taskId=" + readIntFieldOrDefault(
                        candidate.closingTarget, "taskId", -1));
        return chain.proceed(new Object[]{Boolean.TRUE});
    }

    protected boolean isExactFreeformCrossActivityPair(Object closingTarget,
                                                       Object enteringTarget)
            throws Exception {
        int taskId = readIntFieldOrDefault(closingTarget, "taskId", -1);
        Object closingBounds = readFieldOrNull(closingTarget, "localBounds");
        Object enteringBounds = readFieldOrNull(enteringTarget, "localBounds");
        return closingTarget != null && enteringTarget != null
                && closingTarget != enteringTarget && taskId >= 0
                && taskId == readIntFieldOrDefault(enteringTarget, "taskId", -1)
                && resolveRemoteTargetWindowingMode(closingTarget)
                == WINDOWING_MODE_FREEFORM
                && resolveRemoteTargetWindowingMode(enteringTarget)
                == WINDOWING_MODE_FREEFORM
                && closingBounds instanceof Rect
                && !((Rect) closingBounds).isEmpty()
                && closingBounds.equals(enteringBounds);
    }

    protected SurfaceControl resolveSingleTransitionRoot(Object info) throws Exception {
        Object rootCount = readTransitionInfoRootCount(info);
        if (!(rootCount instanceof Number)
                || ((Number) rootCount).intValue() != 1) {
            return null;
        }
        Object root = readTransitionInfoRoot(info, 0);
        Object leash = readTransitionRootLeash(root);
        return leash instanceof SurfaceControl ? (SurfaceControl) leash : null;
    }

    protected float resolveFreeformRootCornerRadius(Object handler, int taskId)
            throws Exception {
        ClassLoader classLoader = handler.getClass().getClassLoader();
        Class<?> controllerClass = Class.forName(
                "com.android.wm.shell.dagger.MultiTaskingControllerImpl",
                false, classLoader);
        Method getInstance = multiTaskingControllerGetInstance;
        if (getInstance == null
                || getInstance.getDeclaringClass() != controllerClass) {
            getInstance = controllerClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            multiTaskingControllerGetInstance = getInstance;
        }
        Object controller = getInstance.invoke(null);
        Object repository = invokeAnyMethod(controller,
                "getMultiTaskingTaskRepository", new Object[0]);
        Object taskInfo = invokeAnyMethod(repository,
                "getMiuiFreeformTaskInfo", new Object[]{Integer.valueOf(taskId)});
        Object radiusValue = invokeAnyMethod(taskInfo,
                "getCornerRadius", new Object[0]);
        Object scaleValue = invokeAnyMethod(taskInfo,
                "getFreeformScale", new Object[0]);
        if (!(radiusValue instanceof Number) || !(scaleValue instanceof Number)) {
            throw new IllegalStateException("freeform radius or scale unavailable");
        }
        float radius = ((Number) radiusValue).floatValue();
        float scale = ((Number) scaleValue).floatValue();
        float rootRadius = radius / scale;
        if (!(radius > 0.0f) || !(scale > 0.0f) || !Float.isFinite(rootRadius)) {
            throw new IllegalStateException("invalid freeform radius geometry"
                    + ", radius=" + radius + ", scale=" + scale);
        }
        return rootRadius;
    }

    protected Object onCrossActivityColorRootApply(XposedInterface.Chain chain)
            throws Throwable {
        FreeformColorRootCandidate candidate = freeformColorRootCandidate.get();
        if (candidate == null) {
            FreeformColorRootAdoption adoption = freeformColorRootAdoption;
            if (adoption != null && adoption.animation == chain.getThisObject()) {
                try {
                    applyAdoptedFreeformTargetGeometry(
                            chain.getThisObject(), adoption);
                } catch (Throwable throwable) {
                    if (freeformColorRootAdoption == adoption) {
                        freeformColorRootAdoption = null;
                    }
                    moduleLog(Log.WARN, TAG,
                            "Failed freeform Activity geometry normalization;"
                                    + " preserving native target geometry",
                            throwable);
                }
            }
            return chain.proceed();
        }
        FreeformColorRootAdoption adopted = null;
        try {
            Object animation = chain.getThisObject();
            if (!matchesFreeformColorRootCandidate(candidate, animation)) {
                freeformColorRootCandidate.compareAndSet(candidate, null);
                moduleLog(Log.WARN, TAG,
                        "Rejected stale freeform color-layer root candidate");
            } else {
                Object scrim = readFieldOrNull(animation, "scrimLayer");
                Object backgroundOwner = readFieldOrNull(animation, "background");
                Object background = readFieldOrNull(
                        backgroundOwner, "mBackgroundSurface");
                Object transaction = readFieldOrNull(animation, "transaction");
                if (!(scrim instanceof SurfaceControl)
                        || !((SurfaceControl) scrim).isValid()
                        || !(background instanceof SurfaceControl)
                        || !((SurfaceControl) background).isValid()
                        || !(transaction instanceof SurfaceControl.Transaction)) {
                    throw new IllegalStateException(
                            "freeform color layers unavailable at first apply");
                }
                Object root = readTransitionInfoRoot(candidate.transitionInfo, 0);
                Object rootOffset = readTransitionRootOffset(root);
                Object colorBounds = readFieldOrNull(
                        candidate.closingTarget, "localBounds");
                Object targetCrop = readFieldOrNull(animation, "cropRect");
                if (!(rootOffset instanceof Point)
                        || !(colorBounds instanceof Rect)
                        || !(targetCrop instanceof Rect)
                        || !Boolean.FALSE.equals(
                        readFieldOrNull(animation, "isLetterboxed"))) {
                    throw new IllegalStateException(
                            "freeform color-layer crop geometry unavailable");
                }
                Rect rootLocalColorCrop = new Rect((Rect) colorBounds);
                Point offset = (Point) rootOffset;
                rootLocalColorCrop.offset(-offset.x, -offset.y);
                if (!rootLocalColorCrop.equals(targetCrop)) {
                    throw new IllegalStateException(
                            "freeform color-layer crop does not match prepared root");
                }
                if (freeformColorRootCandidate.compareAndSet(candidate, null)) {
                    SurfaceControl.Transaction surfaceTransaction =
                            (SurfaceControl.Transaction) transaction;
                    try (SurfaceControl.Transaction donor =
                                 new SurfaceControl.Transaction()) {
                        donor.setCrop(candidate.rootLeash, rootLocalColorCrop);
                        invokeMethod(donor, "setCornerRadius",
                                new Class<?>[]{SurfaceControl.class, float.class},
                                new Object[]{candidate.rootLeash,
                                        Float.valueOf(candidate.rootCornerRadius)});
                        donor.reparent((SurfaceControl) background,
                                        candidate.rootLeash)
                                .setCrop((SurfaceControl) background,
                                        rootLocalColorCrop)
                                .setAlpha((SurfaceControl) background, 0.0f)
                                .setLayer((SurfaceControl) background, -1)
                                .reparent((SurfaceControl) scrim,
                                        candidate.rootLeash)
                                .setCrop((SurfaceControl) scrim,
                                        rootLocalColorCrop);
                        invokeMethod(donor, "setRelativeLayer",
                                new Class<?>[]{SurfaceControl.class,
                                        SurfaceControl.class, int.class},
                                new Object[]{scrim, candidate.closingLeash,
                                        Integer.valueOf(-1)});
                        invokeMethod(donor, "setCornerRadius",
                                new Class<?>[]{SurfaceControl.class, float.class},
                                new Object[]{candidate.closingLeash,
                                        Float.valueOf(candidate.rootCornerRadius)});
                        invokeMethod(donor, "setCornerRadius",
                                new Class<?>[]{SurfaceControl.class, float.class},
                                new Object[]{candidate.enteringLeash,
                                        Float.valueOf(candidate.rootCornerRadius)});
                        surfaceTransaction.merge(donor);
                    }
                    adopted = new FreeformColorRootAdoption(animation, candidate);
                }
            }
        } catch (Throwable throwable) {
            freeformColorRootCandidate.compareAndSet(candidate, null);
            moduleLog(Log.WARN, TAG,
                    "Failed freeform color-layer root adoption; leaving native layers",
                    throwable);
        }
        Object result = chain.proceed();
        if (adopted != null) {
            freeformColorRootAdoption = adopted;
            moduleLog(Log.INFO, TAG,
                    "Adopted freeform cross-activity color layers into prepared root"
                            + ", backgroundAlpha=0.0"
                            + ", rootCornerRadius=" + candidate.rootCornerRadius
                            + ", taskId=" + readIntFieldOrDefault(
                            candidate.closingTarget, "taskId", -1));
        }
        return result;
    }

    protected void applyAdoptedFreeformTargetGeometry(
            Object animation, FreeformColorRootAdoption adoption) throws Exception {
        FreeformColorRootCandidate candidate = adoption.candidate;
        Object transaction = readFieldOrNull(animation, "transaction");
        if (readFieldOrNull(animation, "closingTarget") != candidate.closingTarget
                || readFieldOrNull(animation, "enteringTarget")
                != candidate.enteringTarget
                || !(transaction instanceof SurfaceControl.Transaction)
                || !candidate.closingLeash.isValid()
                || !candidate.enteringLeash.isValid()) {
            throw new IllegalStateException(
                    "freeform Activity target ownership changed");
        }
        SurfaceControl.Transaction surfaceTransaction =
                (SurfaceControl.Transaction) transaction;
        boolean defaultGeometry = DEFAULT_CROSS_ACTIVITY_BACK_ANIMATION.equals(
                animation.getClass().getName());
        WeakReference<Object> slideReference = miuixSlideArmedAnimation;
        boolean slideGeometry = defaultGeometry && miuixSlideAnimActive
                && slideReference != null
                && slideReference.get() == animation;
        if (!defaultGeometry) {
            setFreeformTargetCornerRadius(surfaceTransaction,
                    candidate.closingLeash, candidate.rootCornerRadius);
            setFreeformTargetCornerRadius(surfaceTransaction,
                    candidate.enteringLeash, candidate.rootCornerRadius);
            return;
        }
        Object frame = readFieldOrNull(animation, "backAnimRect");
        Object nativeCrop = readFieldOrNull(animation, "cropRect");
        Object closingRect = readFieldOrNull(animation, "currentClosingRect");
        Object enteringRect = readFieldOrNull(animation, "currentEnteringRect");
        if (!(frame instanceof Rect) || !(nativeCrop instanceof Rect)
                || !(closingRect instanceof RectF)
                || !(enteringRect instanceof RectF)
                || ((RectF) closingRect).isEmpty()
                || ((RectF) enteringRect).isEmpty()) {
            setFreeformTargetCornerRadius(surfaceTransaction,
                    candidate.closingLeash, candidate.rootCornerRadius);
            setFreeformTargetCornerRadius(surfaceTransaction,
                    candidate.enteringLeash, candidate.rootCornerRadius);
            return;
        }
        float postCommitScale = 1.0f;
        if (!slideGeometry) {
            Object lastFlingScale = readFieldOrNull(
                    animation, "lastPostCommitFlingScale");
            if (lastFlingScale instanceof Number) {
                float value = ((Number) lastFlingScale).floatValue();
                if (value > 0.0f && value <= 1.0f) {
                    postCommitScale = value;
                }
            }
        }
        float closingRadius = resolveFixedFreeformTargetClip(
                (Rect) frame, (Rect) nativeCrop,
                (RectF) closingRect, postCommitScale,
                candidate.rootCornerRadius, adoption.closingCrop);
        float enteringRadius = resolveFixedFreeformTargetClip(
                (Rect) frame, (Rect) nativeCrop,
                (RectF) enteringRect, postCommitScale,
                candidate.rootCornerRadius, adoption.enteringCrop);
        surfaceTransaction.setCrop(candidate.closingLeash, adoption.closingCrop);
        setFreeformTargetCornerRadius(surfaceTransaction,
                candidate.closingLeash, closingRadius);
        surfaceTransaction.setCrop(candidate.enteringLeash, adoption.enteringCrop);
        setFreeformTargetCornerRadius(surfaceTransaction,
                candidate.enteringLeash, enteringRadius);
    }

    protected void setFreeformTargetCornerRadius(
            SurfaceControl.Transaction transaction, SurfaceControl leash,
            float cornerRadius) throws Exception {
        invokeMethod(transaction, "setCornerRadius",
                new Class<?>[]{SurfaceControl.class, float.class},
                new Object[]{leash, Float.valueOf(cornerRadius)});
    }

    protected float resolveFixedFreeformTargetClip(
            Rect frame, Rect nativeCrop, RectF currentRect,
            float additionalScale, float rootCornerRadius, Rect outCrop) {
        if (frame.isEmpty() || frame.left != 0 || frame.top != 0
                || !nativeCrop.equals(frame)
                || !(additionalScale > 0.0f)
                || !Float.isFinite(additionalScale)) {
            throw new IllegalStateException("unsupported freeform target crop geometry");
        }
        float visualWidth = currentRect.width() * additionalScale;
        float visualHeight = currentRect.height() * additionalScale;
        float scaleX = visualWidth / frame.width();
        float scaleY = visualHeight / frame.height();
        if (!(scaleX > 0.0f) || !(scaleY > 0.0f)
                || !Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                || Math.abs(scaleX - scaleY) > 0.01f) {
            throw new IllegalStateException("non-uniform freeform target transform");
        }
        float visualLeft = currentRect.centerX() - (visualWidth / 2.0f);
        float visualTop = currentRect.centerY() - (visualHeight / 2.0f);
        int left = Math.max(nativeCrop.left, Math.min(nativeCrop.right,
                (int) Math.ceil((frame.left - visualLeft) / scaleX)));
        int top = Math.max(nativeCrop.top, Math.min(nativeCrop.bottom,
                (int) Math.ceil((frame.top - visualTop) / scaleY)));
        int right = Math.max(nativeCrop.left, Math.min(nativeCrop.right,
                (int) Math.floor((frame.right - visualLeft) / scaleX)));
        int bottom = Math.max(nativeCrop.top, Math.min(nativeCrop.bottom,
                (int) Math.floor((frame.bottom - visualTop) / scaleY)));
        if (right < left) {
            right = left;
        }
        if (bottom < top) {
            bottom = top;
        }
        outCrop.set(left, top, right, bottom);
        return outCrop.isEmpty() ? 0.0f
                : Math.min(rootCornerRadius / scaleX,
                Math.min(outCrop.width(), outCrop.height()) / 2.0f);
    }

    protected boolean matchesFreeformColorRootCandidate(
            FreeformColorRootCandidate candidate, Object animation) throws Exception {
        Object controller = readField(candidate.handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        Object navigationType = readBackNavigationType(navigationInfo);
        Object transitionType = readTransitionInfoType(candidate.transitionInfo);
        if (readField(candidate.handler, "mPrepareOpenTransition")
                != candidate.transitionToken
                || readField(candidate.handler, "mOpenTransitionInfo")
                != candidate.transitionInfo
                || readField(controller, "mApps")
                != candidate.appsIdentity
                || !(navigationType instanceof Number)
                || ((Number) navigationType).intValue() != TYPE_CROSS_ACTIVITY
                || !(transitionType instanceof Number)
                || ((Number) transitionType).intValue() != TRANSIT_PREDICTIVE_BACK) {
            return false;
        }
        SurfaceControl rootLeash = resolveSingleTransitionRoot(
                candidate.transitionInfo);
        Object closingTarget = readFieldOrNull(animation, "closingTarget");
        Object enteringTarget = readFieldOrNull(animation, "enteringTarget");
        Object closingLeash = readFieldOrNull(closingTarget, "leash");
        Object enteringLeash = readFieldOrNull(enteringTarget, "leash");
        if (rootLeash != candidate.rootLeash
                || closingTarget != candidate.closingTarget
                || enteringTarget != candidate.enteringTarget
                || closingLeash != candidate.closingLeash
                || enteringLeash != candidate.enteringLeash
                || !isExactFreeformCrossActivityPair(
                closingTarget, enteringTarget)) {
            return false;
        }
        return candidate.rootLeash.isValid()
                && candidate.closingLeash.isValid()
                && candidate.enteringLeash.isValid();
    }

    protected Object onCrossActivitySlideStart(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        try {
            Object animation = chain.getThisObject();
            miuixSlideCommitPoseCaptured = false;
            boolean slideEnabled = isHyperOsSlideAnimationEnabled();
            if (!slideEnabled) {
                miuixSlideAnimActive = false;
                return result;
            }
            if (readField(animation, "closingTarget") == null
                    || readField(animation, "enteringTarget") == null) {
                miuixSlideAnimActive = false;
                return result;
            }
            Rect backAnimRect = (Rect) readField(animation, "backAnimRect");
            float width = backAnimRect.width();
            if (width <= 0f) {
                miuixSlideAnimActive = false;
                return result;
            }
            Context animationContext = (Context) readField(animation, "context");
            boolean rtl = animationContext.getResources().getConfiguration()
                    .getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float slide = rtl ? -width : width;
            RectF startClosing = (RectF) readField(animation, "startClosingRect");
            RectF targetClosing = (RectF) readField(animation, "targetClosingRect");
            RectF startEntering = (RectF) readField(animation, "startEnteringRect");
            RectF targetEntering = (RectF) readField(animation, "targetEnteringRect");
            startClosing.set(backAnimRect);
            targetClosing.set(backAnimRect);
            targetClosing.offset(slide, 0f);
            targetEntering.set(backAnimRect);
            startEntering.set(backAnimRect);
            startEntering.offset(-slide * MIUIX_SLIDE_PARALLAX_FRACTION, 0f);
            miuixSlideProgressVelocity = 0.0f;
            miuixSlideLastProgressSample = 0.0f;
            miuixSlideLastProgressSampleMs = 0L;
            miuixSlideArmedAnimation = new WeakReference<>(animation);
            miuixSlideAnimActive = true;
            moduleLog(Log.INFO, TAG, "Armed miuix slide back animation"
                    + ", width=" + width + ", rtl=" + rtl);
        } catch (Throwable throwable) {
            miuixSlideAnimActive = false;
            moduleLog(Log.WARN, TAG, "Failed to arm miuix slide geometry", throwable);
        }
        return result;
    }

    protected boolean interceptOneUiCrossTaskProgressRegistration(
            XposedInterface.Chain chain) throws Throwable {
        if (!isOneUiCrossTaskAnimationEnabled()
                || oneUiCrossTaskFinishMethod == null
                || aospCrossTaskAnimation == null) {
            return false;
        }
        Object animation = aospCrossTaskAnimation;
        try {
            Object progressObject = readFirstCrossTaskField(
                    animation, "mProgressAnimator", "progressAnimator");
            if (progressObject != chain.getThisObject()
                    || !(progressObject instanceof BackProgressAnimator)) {
                return false;
            }
            Object callbackObject = chain.getArg(1);
            if (!(callbackObject instanceof BackProgressAnimator.ProgressCallback)) {
                return false;
            }
            OneUiCrossTaskSession session = createOneUiCrossTaskSession(
                    animation, (BackProgressAnimator) progressObject,
                    (BackProgressAnimator.ProgressCallback) callbackObject,
                    (BackMotionEvent) chain.getArg(0));
            if (session == null || !oneUiCrossTaskSession.compareAndSet(null, session)) {
                return false;
            }
            BackProgressAnimator.ProgressCallback replacement = event -> {
                OneUiCrossTaskSession current = oneUiCrossTaskSession.get();
                if (current != session || session.terminal.get()) {
                    return;
                }
                try {
                    if (!isOneUiCrossTaskAnimationEnabled()
                            || !isExactOneUiCrossTaskSession(session)) {
                        oneUiCrossTaskSession.compareAndSet(session, null);
                        session.nativeProgressCallback.onProgressUpdate(event);
                        return;
                    }
                    session.lastEvent = event;
                    applyOneUiCrossTaskGestureFrame(session, event);
                } catch (Throwable throwable) {
                    oneUiCrossTaskSession.compareAndSet(session, null);
                    moduleLog(Log.WARN, TAG,
                            "One UI CrossTask frame failed; restored native driver",
                            throwable);
                    session.nativeProgressCallback.onProgressUpdate(event);
                }
            };
            oneUiCrossTaskRegistrationReentry = true;
            try {
                session.progressAnimator.onBackStarted(
                        (BackMotionEvent) chain.getArg(0), replacement);
            } finally {
                oneUiCrossTaskRegistrationReentry = false;
            }
            moduleLog(Log.INFO, TAG, "Armed One UI CrossTask gesture"
                    + ", animation=" + shortObject(animation)
                    + ", task=" + session.closingTaskId
                    + "->" + session.enteringTaskId
                    + ", impl=" + requireSystemUiPlatformImpl().name());
            return true;
        } catch (Throwable throwable) {
            oneUiCrossTaskSession.set(null);
            moduleLog(Log.WARN, TAG,
                    "One UI CrossTask gesture did not pass fail-closed validation",
                    throwable);
            return false;
        }
    }

    protected OneUiCrossTaskSession createOneUiCrossTaskSession(
            Object animation, BackProgressAnimator progressAnimator,
            BackProgressAnimator.ProgressCallback nativeCallback,
            BackMotionEvent startEvent) throws Exception {
        Object closingObject = readFirstCrossTaskField(
                animation, "mClosingTarget", "closingTarget");
        Object enteringObject = readFirstCrossTaskField(
                animation, "mEnteringTarget", "enteringTarget");
        Object transactionObject = readFirstCrossTaskField(
                animation, "mTransaction", "transaction");
        Object taskRectObject = readFirstCrossTaskField(
                animation, "mStartTaskRect", "startTaskRect");
        if (!(transactionObject instanceof SurfaceControl.Transaction)
                || !(taskRectObject instanceof Rect)) {
            return null;
        }
        Object closingLeashObject = readFirstCrossTaskField(closingObject, "leash");
        Object enteringLeashObject = readFirstCrossTaskField(enteringObject, "leash");
        if (!(closingLeashObject instanceof SurfaceControl)
                || !(enteringLeashObject instanceof SurfaceControl)) {
            return null;
        }
        SurfaceControl closingLeash = (SurfaceControl) closingLeashObject;
        SurfaceControl enteringLeash = (SurfaceControl) enteringLeashObject;
        Rect taskRect = new Rect((Rect) taskRectObject);
        if (!isExactFullscreenCrossTaskPair(animation,
                closingObject, enteringObject,
                closingLeash, enteringLeash, taskRect)) {
            return null;
        }
        Object cornerObject = readFirstCrossTaskField(
                animation, "mCornerRadius", "cornerRadius");
        float cornerRadius = cornerObject instanceof Number
                ? Math.max(0.0f, ((Number) cornerObject).floatValue()) : 0.0f;
        return new OneUiCrossTaskSession(animation, progressAnimator,
                closingObject, enteringObject, closingLeash, enteringLeash,
                (SurfaceControl.Transaction) transactionObject,
                nativeCallback, taskRect, cornerRadius,
                readIntFieldOrDefault(closingObject, "taskId", -1),
                readIntFieldOrDefault(enteringObject, "taskId", -1),
                startEvent.getTouchY());
    }

    protected boolean isExactFullscreenCrossTaskPair(
            Object animation, Object closing, Object entering,
            SurfaceControl closingLeash, SurfaceControl enteringLeash,
            Rect taskRect) throws Exception {
        int closingTaskId = readIntFieldOrDefault(closing, "taskId", -1);
        int enteringTaskId = readIntFieldOrDefault(entering, "taskId", -1);
        Object closingTaskInfo = readFirstCrossTaskField(closing, "taskInfo");
        Object enteringTaskInfo = readFirstCrossTaskField(entering, "taskInfo");
        int closingDisplayId = readIntFieldOrDefault(closingTaskInfo, "displayId", -1);
        int enteringDisplayId = readIntFieldOrDefault(enteringTaskInfo, "displayId", -1);
        if (closing == entering
                || readIntFieldOrDefault(closing, "mode", -1) != 1
                || readIntFieldOrDefault(entering, "mode", -1) != 0
                || closingTaskId < 0 || enteringTaskId < 0
                || closingTaskId == enteringTaskId
                || resolveTaskInfoWindowingMode(closingTaskInfo)
                != WINDOWING_MODE_FULLSCREEN
                || resolveTaskInfoWindowingMode(enteringTaskInfo)
                != WINDOWING_MODE_FULLSCREEN
                || resolveTaskInfoActivityType(closingTaskInfo) != ACTIVITY_TYPE_STANDARD
                || resolveTaskInfoActivityType(enteringTaskInfo) != ACTIVITY_TYPE_STANDARD
                || closingDisplayId < 0 || closingDisplayId != enteringDisplayId
                || !closingLeash.isValid() || !enteringLeash.isValid()
                || taskRect.isEmpty() || taskRect.left != 0 || taskRect.top != 0) {
            return false;
        }
        Object closingConfiguration = readFirstCrossTaskField(
                closing, "windowConfiguration");
        Object enteringConfiguration = readFirstCrossTaskField(
                entering, "windowConfiguration");
        Object closingBoundsObject = invokeAnyMethod(
                closingConfiguration, "getBounds", new Object[0]);
        Object enteringBoundsObject = invokeAnyMethod(
                enteringConfiguration, "getBounds", new Object[0]);
        if (!(closingBoundsObject instanceof Rect)
                || !(enteringBoundsObject instanceof Rect)) {
            return false;
        }
        Rect closingBounds = (Rect) closingBoundsObject;
        Rect enteringBounds = (Rect) enteringBoundsObject;
        if (closingBounds == null || enteringBounds == null
                || closingBounds.isEmpty() || enteringBounds.isEmpty()
                || closingBounds.width() != enteringBounds.width()
                || closingBounds.height() != enteringBounds.height()
                || taskRect.width() != closingBounds.width()
                || taskRect.height() > closingBounds.height()) {
            return false;
        }
        Object runner = readFirstCrossTaskField(
                animation, "mBackAnimationRunner", "backAnimationRunner");
        Object apps = readFirstCrossTaskField(runner, "mApps", "apps");
        if (apps == null || !apps.getClass().isArray()
                || Array.getLength(apps) != 2) {
            return false;
        }
        Object first = Array.get(apps, 0);
        Object second = Array.get(apps, 1);
        return first != second
                && (first == closing && second == entering
                || first == entering && second == closing);
    }

    protected boolean isExactOneUiCrossTaskSession(
            OneUiCrossTaskSession session) throws Exception {
        return aospCrossTaskAnimation == session.animation
                && readFirstCrossTaskField(
                session.animation, "mProgressAnimator", "progressAnimator")
                == session.progressAnimator
                && readFirstCrossTaskField(
                session.animation, "mClosingTarget", "closingTarget")
                == session.closingTarget
                && readFirstCrossTaskField(
                session.animation, "mEnteringTarget", "enteringTarget")
                == session.enteringTarget
                && readFirstCrossTaskField(session.closingTarget, "leash")
                == session.closingLeash
                && readFirstCrossTaskField(session.enteringTarget, "leash")
                == session.enteringLeash
                && session.closingLeash.isValid()
                && session.enteringLeash.isValid();
    }

    protected Object readFirstCrossTaskField(Object target, String... names)
            throws Exception {
        Throwable failure = null;
        for (String name : names) {
            try {
                return readField(target, name);
            } catch (Throwable throwable) {
                failure = throwable;
            }
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        throw new NoSuchFieldException(target.getClass().getName()
                + "." + Arrays.toString(names));
    }

    /**
     * Fires when any BackProgressAnimator registers its per-gesture ProgressCallback.
     * For the armed cross-activity animation's own animator, the native callback (the
     * inlined geometry) is replaced with the miuix frame driver; every other animator
     * registers untouched.
     */
    protected Object onCrossActivitySlideProgressRegistration(
            XposedInterface.Chain chain) throws Throwable {
        if (!oneUiCrossTaskRegistrationReentry
                && interceptOneUiCrossTaskProgressRegistration(chain)) {
            return null;
        }
        if (miuixSlideRegistrationReentry || !miuixSlideAnimActive) {
            return chain.proceed();
        }
        WeakReference<Object> armedReference = miuixSlideArmedAnimation;
        Object animation = armedReference == null ? null : armedReference.get();
        if (animation == null) {
            return chain.proceed();
        }
        Object progressAnimator;
        try {
            progressAnimator = readField(animation, "progressAnimator");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to read progressAnimator", throwable);
            return chain.proceed();
        }
        if (progressAnimator != chain.getThisObject()) {
            return chain.proceed();
        }
        Object originalCallback = chain.getArg(1);
        BackProgressAnimator.ProgressCallback replacement = event -> {
            try {
                onMiuixSlideFrame(animation, event);
            } catch (Throwable throwable) {
                miuixSlideAnimActive = false;
                moduleLog(Log.WARN, TAG, "miuix slide frame failed"
                        + ", fallingBackToNativeCallback=true", throwable);
                if (originalCallback instanceof BackProgressAnimator.ProgressCallback) {
                    ((BackProgressAnimator.ProgressCallback) originalCallback)
                            .onProgressUpdate(event);
                }
            }
        };
        miuixSlideRegistrationReentry = true;
        try {
            ((BackProgressAnimator) chain.getThisObject()).onBackStarted(
                    (BackMotionEvent) chain.getArg(0), replacement);
        } finally {
            miuixSlideRegistrationReentry = false;
        }
        moduleLog(Log.INFO, TAG, "miuix slide progress callback proxied");
        return null;
    }

    protected void applyOneUiCrossTaskGestureFrame(
            OneUiCrossTaskSession session, BackEvent event) throws Exception {
        float progress = Math.max(0.0f, Math.min(1.0f, event.getProgress()));
        float animationProgress = progress <= 0.5f
                ? 1.2f * progress
                : 0.6f + (progress - 0.5f) * 0.3f;
        float width = session.taskRect.width();
        float height = session.taskRect.height();
        float halfHeight = height / 2.0f;
        float scale = 1.0f - 0.26666668f * animationProgress;
        session.scale = scale;
        float top = (1.0f - scale) * halfHeight;
        float bottom = (scale + 1.0f) * halfHeight;
        float touchOffset = (event.getTouchY() - session.initialTouchY)
                * ONEUI_CROSS_TASK_TOUCH_Y_FRACTION;
        if (touchOffset > 0.0f && bottom + touchOffset >= height) {
            touchOffset = height - bottom;
        } else if (touchOffset < 0.0f && top + touchOffset <= 0.0f) {
            touchOffset = -top;
        }
        top += touchOffset;
        bottom += touchOffset;
        float margin = ONEUI_CROSS_TASK_MARGIN_FRACTION * width;
        float enteringLeft = -width - margin
                + 0.528f * width * animationProgress;
        float closingLeft = 0.24f * width * animationProgress;
        session.enteringCurrent.set(enteringLeft, top,
                enteringLeft + scale * width, bottom);
        session.closingCurrent.set(closingLeft, top,
                closingLeft + scale * width, bottom);
        applyOneUiCrossTaskFrame(session, scale, scale);
    }

    protected Object onOneUiCrossTaskInvoked(XposedInterface.Chain chain)
            throws Throwable {
        OneUiCrossTaskSession session = oneUiCrossTaskSession.get();
        if (session == null) {
            return chain.proceed();
        }
        try {
            Object runner = readFirstCrossTaskField(
                    session.animation, "mBackAnimationRunner", "backAnimationRunner");
            Object callback = readFirstCrossTaskField(runner, "mCallback", "callback");
            if (callback != chain.getThisObject()) {
                return chain.proceed();
            }
            if (!isOneUiCrossTaskAnimationEnabled()
                    || !isExactOneUiCrossTaskSession(session)) {
                restoreOneUiNativeProgress(session);
                oneUiCrossTaskSession.compareAndSet(session, null);
                return chain.proceed();
            }
            if (!session.terminal.compareAndSet(false, true)) {
                return null;
            }
            RectF enteringStart = new RectF(session.enteringCurrent);
            RectF closingStart = new RectF(session.closingCurrent);
            float startScale = session.scale;
            session.progressAnimator.reset();
            ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
            animator.setDuration(ONEUI_CROSS_TASK_SETTLE_DURATION_MS);
            animator.setInterpolator(ONEUI_CROSS_TASK_SETTLE_INTERPOLATOR);
            animator.addUpdateListener(valueAnimator -> {
                try {
                    applyOneUiCrossTaskSettleFrame(session,
                            enteringStart, closingStart, startScale,
                            valueAnimator.getAnimatedFraction());
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "One UI CrossTask settle frame failed", throwable);
                    valueAnimator.cancel();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean finished;

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (finished || session.invokingNativeFinish) {
                        return;
                    }
                    finished = true;
                    finishOneUiCrossTaskSession(session);
                }
            });
            session.settleAnimator = animator;
            animator.start();
            moduleLog(Log.INFO, TAG, "Started One UI CrossTask commit settle"
                    + ", durationMs=" + ONEUI_CROSS_TASK_SETTLE_DURATION_MS);
            return null;
        } catch (Throwable throwable) {
            restoreOneUiNativeProgress(session);
            oneUiCrossTaskSession.compareAndSet(session, null);
            moduleLog(Log.WARN, TAG,
                    "One UI CrossTask commit rejected; restored native commit",
                    throwable);
            return chain.proceed();
        }
    }

    protected void applyOneUiCrossTaskSettleFrame(
            OneUiCrossTaskSession session, RectF enteringStart,
            RectF closingStart, float startScale, float fraction)
            throws Exception {
        float width = session.taskRect.width();
        float height = session.taskRect.height();
        float enteringTravel = Math.abs(session.taskRect.left - enteringStart.left)
                * fraction + 0.5f;
        float enteringScale = startScale + (1.0f - startScale) * fraction;
        float enteringLeft = Math.min(enteringStart.left + enteringTravel, 0.0f);
        session.enteringCurrent.set(enteringLeft,
                (1.0f - fraction) * enteringStart.top,
                enteringLeft + width * enteringScale,
                enteringStart.bottom + (height - enteringStart.bottom) * fraction);

        float closingDestination = session.taskRect.right + 0.08f * width;
        float closingOffset = 0.5f
                + (closingDestination - closingStart.left) * fraction;
        float closingLeft = Math.min(
                closingStart.left + closingOffset, closingDestination);
        float closingScale = startScale + (0.9f - startScale) * fraction;
        float verticalInset = 0.05f * height;
        session.closingCurrent.set(closingLeft,
                closingStart.top + (verticalInset - closingStart.top) * fraction,
                closingLeft + width * closingScale,
                closingStart.bottom
                        + (height - verticalInset - closingStart.bottom) * fraction);
        applyOneUiCrossTaskFrame(session, closingScale, enteringScale);
    }

    protected void applyOneUiCrossTaskFrame(
            OneUiCrossTaskSession session, float closingScale,
            float enteringScale) throws Exception {
        applyOneUiCrossTaskTransform(session, session.closingLeash,
                session.closingCurrent, closingScale);
        applyOneUiCrossTaskTransform(session, session.enteringLeash,
                session.enteringCurrent, enteringScale);
        copyOneUiCrossTaskRect(session.animation,
                session.closingCurrent, "mClosingCurrentRect", "closingCurrentRect");
        copyOneUiCrossTaskRect(session.animation,
                session.enteringCurrent, "mEnteringCurrentRect", "enteringCurrentRect");
        session.transaction.apply();
    }

    protected void applyOneUiCrossTaskTransform(
            OneUiCrossTaskSession session, SurfaceControl leash,
            RectF destination, float scale) {
        session.matrix.reset();
        session.matrix.setScale(scale, scale);
        session.matrix.postTranslate(destination.left, destination.top);
        try {
            invokeMethod(session.transaction, "setMatrix",
                    new Class<?>[]{SurfaceControl.class, Matrix.class, float[].class},
                    new Object[]{leash, session.matrix, session.matrixValues});
            invokeMethod(session.transaction, "setWindowCrop",
                    new Class<?>[]{SurfaceControl.class, Rect.class},
                    new Object[]{leash, session.taskRect});
            invokeMethod(session.transaction, "setCornerRadius",
                    new Class<?>[]{SurfaceControl.class, float.class},
                    new Object[]{leash, Float.valueOf(session.cornerRadius)});
        } catch (Exception exception) {
            throw new IllegalStateException("CrossTask transform API unavailable", exception);
        }
    }

    protected void copyOneUiCrossTaskRect(
            Object animation, RectF value, String... names) {
        for (String name : names) {
            try {
                Object target = readField(animation, name);
                if (target instanceof RectF) {
                    ((RectF) target).set(value);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    protected void restoreOneUiNativeProgress(OneUiCrossTaskSession session) {
        BackEvent lastEvent = session.lastEvent;
        if (lastEvent == null) {
            return;
        }
        try {
            session.nativeProgressCallback.onProgressUpdate(lastEvent);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to restore native CrossTask progress", throwable);
        }
    }

    protected void finishOneUiCrossTaskSession(OneUiCrossTaskSession session) {
        try {
            Method finish = oneUiCrossTaskFinishMethod;
            if (finish == null
                    || !finish.getDeclaringClass().isInstance(session.animation)) {
                throw new IllegalStateException("CrossTask finish method unavailable");
            }
            session.invokingNativeFinish = true;
            finish.invoke(session.animation);
        } catch (InvocationTargetException throwable) {
            moduleLog(Log.WARN, TAG, "One UI CrossTask native finish failed",
                    throwable.getCause() == null ? throwable : throwable.getCause());
            oneUiCrossTaskSession.compareAndSet(session, null);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "One UI CrossTask native finish failed", throwable);
            oneUiCrossTaskSession.compareAndSet(session, null);
        } finally {
            session.invokingNativeFinish = false;
        }
    }

    protected Object onOneUiCrossTaskFinished(XposedInterface.Chain chain)
            throws Throwable {
        OneUiCrossTaskSession session = oneUiCrossTaskSession.get();
        if (session != null && session.animation == chain.getThisObject()) {
            oneUiCrossTaskSession.compareAndSet(session, null);
            ValueAnimator animator = session.settleAnimator;
            if (!session.invokingNativeFinish
                    && animator != null && animator.isRunning()) {
                session.invokingNativeFinish = true;
                animator.cancel();
            }
        }
        try {
            return chain.proceed();
        } finally {
            if (session != null) {
                session.invokingNativeFinish = false;
            }
        }
    }

    protected void cancelOneUiCrossTaskForHotReload() {
        OneUiCrossTaskSession session = oneUiCrossTaskSession.getAndSet(null);
        ValueAnimator animator = session == null ? null : session.settleAnimator;
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
    }

    protected void onMiuixSlideFrame(Object animation, BackEvent backEvent)
            throws Exception {
        if (!miuixSlideAnimActive) {
            return;
        }
        // The delivered progress already tracks the finger through
        // BackProgressAnimator's smoothing spring (cancel rides it back to zero); mapping
        // it linearly, without the native gesture interpolator, is the miuix slide.
        float progress = Math.max(0.0f, Math.min(1.0f, backEvent.getProgress()));
        trackMiuixSlideProgressVelocity(progress);
        writeField(animation, "gestureProgress", Float.valueOf(progress));
        applyMiuixSlideFrame(animation, progress,
                MIUIX_SLIDE_ENTERING_MIN_ALPHA
                        + (1.0f - MIUIX_SLIDE_ENTERING_MIN_ALPHA) * progress);
    }

    // Smoothed progress-per-second, so the commit can seed the scrim fade with the
    // finger's release speed instead of starting from rest.
    protected void trackMiuixSlideProgressVelocity(float progress) {
        long now = SystemClock.uptimeMillis();
        if (miuixSlideLastProgressSampleMs != 0L && now > miuixSlideLastProgressSampleMs) {
            float instant = (progress - miuixSlideLastProgressSample)
                    / ((now - miuixSlideLastProgressSampleMs) / 1000.0f);
            miuixSlideProgressVelocity =
                    0.5f * miuixSlideProgressVelocity + 0.5f * instant;
        }
        miuixSlideLastProgressSample = progress;
        miuixSlideLastProgressSampleMs = now;
    }

    protected Object onCrossActivitySlidePostCommit(XposedInterface.Chain chain)
            throws Throwable {
        if (!miuixSlideAnimActive) {
            return chain.proceed();
        }
        try {
            Object animation = chain.getThisObject();
            float linear = ((Number) chain.getArg(0)).floatValue();
            if (!miuixSlideCommitPoseCaptured) {
                miuixSlideCommitClosing.set((RectF) readField(
                        animation, "currentClosingRect"));
                miuixSlideCommitEntering.set((RectF) readField(
                        animation, "currentEnteringRect"));
                float commitProgress = readFloatFieldOrDefault(
                        animation, "gestureProgress", 0.0f);
                miuixSlideCommitEnteringAlpha = MIUIX_SLIDE_ENTERING_MIN_ALPHA
                        + (1.0f - MIUIX_SLIDE_ENTERING_MIN_ALPHA) * commitProgress;
                // Anchor the settle's dim fade to the exact alpha the drag ended on, and
                // seed it with the matching release speed (scrim falls as progress rises).
                miuixSlideCommitScrimAlpha =
                        MIUIX_SLIDE_SCRIM_MAX_ALPHA * (1.0f - commitProgress);
                miuixSlideCommitScrimVelocity =
                        -MIUIX_SLIDE_SCRIM_MAX_ALPHA * miuixSlideProgressVelocity;
                // The subclass onGestureCommitted (not hooked) rewrites the target
                // rects to its own 0.9 card pose, so the slide would settle short of
                // the edge. Restore the full slide-out destination for the settle.
                Rect backAnimRect = (Rect) readField(animation, "backAnimRect");
                Context animationContext = (Context) readField(animation, "context");
                boolean rtl = animationContext.getResources().getConfiguration()
                        .getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                float slide = rtl ? -backAnimRect.width() : backAnimRect.width();
                RectF targetClosing = (RectF) readField(
                        animation, "targetClosingRect");
                RectF targetEntering = (RectF) readField(
                        animation, "targetEnteringRect");
                targetClosing.set(backAnimRect);
                targetClosing.offset(slide, 0f);
                targetEntering.set(backAnimRect);
                miuixSlideCommitPoseCaptured = true;
            }
            if (miuixSlidePostCommitOnBase) {
                // Super-call position: the subclass override writes its native
                // geometry after this hook returns. Reapply ours behind it on the
                // same looper turn so the miuix frame is what reaches the compositor.
                Handler frameHandler = new Handler(Objects.requireNonNull(Looper.myLooper()));
                frameHandler.post(() -> {
                    try {
                        if (miuixSlideAnimActive) {
                            applyMiuixSlidePostCommitFrame(animation, linear);
                        }
                    } catch (Throwable throwable) {
                        miuixSlideAnimActive = false;
                        moduleLog(Log.WARN, TAG, "miuix slide deferred post-commit failed",
                                throwable);
                    }
                });
                return null;
            }
            applyMiuixSlidePostCommitFrame(animation, linear);
            return null;
        } catch (Throwable throwable) {
            miuixSlideAnimActive = false;
            moduleLog(Log.WARN, TAG, "miuix slide post-commit frame failed", throwable);
            return chain.proceed();
        }
    }

    protected Object onCrossActivitySlideDuration(XposedInterface.Chain chain)
            throws Throwable {
        if (miuixSlideAnimActive) {
            return Long.valueOf(MIUIX_SLIDE_SETTLE_DURATION_MS);
        }
        return chain.proceed();
    }

    protected void applyMiuixSlidePostCommitFrame(Object animation, float linear)
            throws Exception {
        float eased = miuixSlideSettleEase(linear);
        RectF targetClosing = (RectF) readField(animation, "targetClosingRect");
        RectF targetEntering = (RectF) readField(animation, "targetEnteringRect");
        RectF currentClosing = (RectF) readField(animation, "currentClosingRect");
        RectF currentEntering = (RectF) readField(animation, "currentEnteringRect");
        lerpRectF(miuixSlideCommitClosing, targetClosing, eased, currentClosing);
        lerpRectF(miuixSlideCommitEntering, targetEntering, eased, currentEntering);
        float enteringAlpha = miuixSlideCommitEnteringAlpha
                + (1.0f - miuixSlideCommitEnteringAlpha) * eased;
        // Analytic critically damped decay to zero from the committed alpha, seeded with
        // the release speed: leaves the commit value at the finger's dimming rate and
        // eases to rest. Decoupled from the geometry's ease-out on purpose.
        float t = linear * (MIUIX_SLIDE_SETTLE_DURATION_MS / 1000.0f);
        float decay = (float) Math.exp(-MIUIX_SLIDE_SCRIM_OMEGA * t);
        float scrimAlpha = (miuixSlideCommitScrimAlpha
                + (miuixSlideCommitScrimVelocity
                + MIUIX_SLIDE_SCRIM_OMEGA * miuixSlideCommitScrimAlpha) * t) * decay;
        applyMiuixSlideTransforms(animation, currentClosing, currentEntering,
                enteringAlpha, Math.max(0.0f, scrimAlpha));
    }

    protected void applyMiuixSlideFrame(Object animation, float progress,
                                        float enteringAlpha) throws Exception {
        RectF startClosing = (RectF) readField(animation, "startClosingRect");
        RectF targetClosing = (RectF) readField(animation, "targetClosingRect");
        RectF startEntering = (RectF) readField(animation, "startEnteringRect");
        RectF targetEntering = (RectF) readField(animation, "targetEnteringRect");
        RectF currentClosing = (RectF) readField(animation, "currentClosingRect");
        RectF currentEntering = (RectF) readField(animation, "currentEnteringRect");
        lerpRectF(startClosing, targetClosing, progress, currentClosing);
        lerpRectF(startEntering, targetEntering, progress, currentEntering);
        // Dim tracks the finger: fully dimmed at rest, gone once the top has pulled a
        // full width away.
        float scrimAlpha = MIUIX_SLIDE_SCRIM_MAX_ALPHA * (1.0f - progress);
        applyMiuixSlideTransforms(animation, currentClosing, currentEntering,
                enteringAlpha, scrimAlpha);
    }

    protected void applyMiuixSlideTransforms(Object animation, RectF closingRect,
                                             RectF enteringRect, float enteringAlpha,
                                             float scrimAlpha)
            throws Exception {
        Object closingLeash = readFieldOrNull(
                readField(animation, "closingTarget"), "leash");
        Object enteringLeash = readFieldOrNull(
                readField(animation, "enteringTarget"), "leash");
        applyCrossActivityTransform(animation, closingLeash, closingRect, 1.0f);
        applyCrossActivityTransform(animation, enteringLeash, enteringRect,
                enteringAlpha);
        Object scrim = readFieldOrNull(animation, "scrimLayer");
        if (scrim instanceof SurfaceControl && ((SurfaceControl) scrim).isValid()) {
            ((SurfaceControl.Transaction) readField(animation, "transaction"))
                    .setAlpha((SurfaceControl) scrim,
                            Math.max(0.0f, Math.min(1.0f, scrimAlpha)));
        }
        Object transaction = readField(animation, "transaction");
        FreeformColorRootAdoption adoption = freeformColorRootAdoption;
        // Fullscreen keeps only the moving top card rounded. Exact freeform is
        // normalized for both targets by the common applyTransaction hook.
        if ((adoption == null || adoption.animation != animation)
                && enteringLeash instanceof SurfaceControl
                && ((SurfaceControl) enteringLeash).isValid()) {
            invokeMethod(transaction, "setCornerRadius",
                    new Class<?>[]{SurfaceControl.class, float.class},
                    new Object[]{enteringLeash, Float.valueOf(0.0f)});
        }
        invokeAnyMethod(animation, "applyTransaction", new Object[0]);
        Object background = readField(animation, "background");
        if (background != null) {
            invokeAnyMethod(background, "customizeStatusBarAppearance",
                    new Object[]{Integer.valueOf((int) closingRect.top)});
        }
    }

    protected void applyCrossActivityTransform(Object animation, Object leash,
                                               RectF rect, float alpha)
            throws Exception {
        Method method = crossActivityApplyTransform;
        if (method == null || !method.getDeclaringClass().isInstance(animation)) {
            method = null;
            for (Class<?> current = animation.getClass(); current != null;
                 current = current.getSuperclass()) {
                for (Method candidate : current.getDeclaredMethods()) {
                    if ("applyTransform".equals(candidate.getName())
                            && candidate.getParameterCount() == 5) {
                        candidate.setAccessible(true);
                        method = candidate;
                        break;
                    }
                }
                if (method != null) {
                    break;
                }
            }
            if (method == null) {
                throw new NoSuchMethodException("applyTransform");
            }
            crossActivityApplyTransform = method;
            crossActivityNoFling = method.getParameterTypes()[4].getEnumConstants()[0];
        }
        method.invoke(animation, leash, rect, Float.valueOf(alpha), null,
                crossActivityNoFling);
    }

    protected void lerpRectF(RectF start, RectF target, float progress, RectF out) {
        out.left = start.left + (target.left - start.left) * progress;
        out.top = start.top + (target.top - start.top) * progress;
        out.right = start.right + (target.right - start.right) * progress;
        out.bottom = start.bottom + (target.bottom - start.bottom) * progress;
    }

    // Cubic ease-out: full speed at release for a continuous handoff from the finger,
    // then a decisive settle — a critically damped closed form starts at zero velocity
    // (a visible hitch at release) and crawls sub-pixel through its final stretch.
    protected float miuixSlideSettleEase(float linearProgress) {
        float remaining = 1.0f - Math.max(0.0f, Math.min(1.0f, linearProgress));
        return 1.0f - remaining * remaining * remaining;
    }

    protected void hookBackPrepareTransitionReparent(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            for (Method method : handlerClass.getDeclaredMethods()) {
                if ("handlePrepareTransition".equals(method.getName())
                        && method.getParameterCount() == 5) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("systemui_back_prepare_reparent")
                            .intercept(this::correctPredictiveBackPrepareReparent));
                    moduleLog(Log.INFO, TAG,
                            "Hooked Shell predictive prepare ownership correction");
                    return;
                }
            }
            moduleLog(Log.WARN, TAG,
                    "BackTransitionHandler.handlePrepareTransition not found");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Shell predictive-back prepare reparent",
                    throwable);
        }
    }

    protected void hookBackCommitComposition(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method method = requireBackMergeAnimation(handlerClass);
            recordHookHandle(hook(method)
                    .setId("systemui_back_commit_composition")
                    .intercept(this::correctPredictiveBackCommitComposition));
            backCommitCompositionHookReady = true;
            moduleLog(Log.INFO, TAG,
                    "Hooked Shell predictive return-home commit composition");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Shell predictive return-home commit composition",
                    throwable);
        }
    }

    protected Method requireBackMergeAnimation(Class<?> handlerClass)
            throws NoSuchMethodException {
        return requireExactDeclaredMethod(handlerClass, "mergeAnimation", "void",
                IBinder.class.getName(), TransitionInfo.class.getName(),
                SurfaceControl.Transaction.class.getName(),
                SurfaceControl.Transaction.class.getName(), IBinder.class.getName(),
                "com.android.wm.shell.transition.Transitions$TransitionFinishCallback");
    }

    protected void captureFreeformColorRootCandidate(Object handler,
                                                     Object transitionToken,
                                                     Object info) throws Exception {
        Object type = readTransitionInfoType(info);
        if (!(type instanceof Number)
                || ((Number) type).intValue() != TRANSIT_PREDICTIVE_BACK
                || readField(handler, "mPrepareOpenTransition") != transitionToken
                || readField(handler, "mOpenTransitionInfo") != info) {
            return;
        }
        Object controller = readField(handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        Object navigationType = readBackNavigationType(navigationInfo);
        Object apps = readField(controller, "mApps");
        if (!(navigationType instanceof Number)
                || ((Number) navigationType).intValue() != TYPE_CROSS_ACTIVITY
                || apps == null || !apps.getClass().isArray()
                || Array.getLength(apps) != 2) {
            return;
        }
        Object closingTarget = null;
        Object enteringTarget = null;
        for (int index = 0; index < 2; index++) {
            Object target = Array.get(apps, index);
            int mode = readIntFieldOrDefault(target, "mode", -1);
            if (mode == 0 && enteringTarget == null) {
                enteringTarget = target;
            } else if (mode == 1 && closingTarget == null) {
                closingTarget = target;
            } else {
                return;
            }
        }
        int taskId = readIntFieldOrDefault(closingTarget, "taskId", -1);
        if (!isExactFreeformCrossActivityPair(
                closingTarget, enteringTarget)) {
            return;
        }
        Object closingLeash = readFieldOrNull(closingTarget, "leash");
        Object enteringLeash = readFieldOrNull(enteringTarget, "leash");
        if (!(closingLeash instanceof SurfaceControl)
                || !(enteringLeash instanceof SurfaceControl)
                || closingLeash == enteringLeash
                || !((SurfaceControl) closingLeash).isValid()
                || !((SurfaceControl) enteringLeash).isValid()) {
            return;
        }
        SurfaceControl rootLeash = resolveSingleTransitionRoot(info);
        if (rootLeash == null
                || rootLeash == closingLeash || rootLeash == enteringLeash
                || !rootLeash.isValid()) {
            return;
        }
        float rootCornerRadius = resolveFreeformRootCornerRadius(handler, taskId);
        freeformColorRootCandidate.set(new FreeformColorRootCandidate(
                handler, transitionToken, info, apps,
                closingTarget, enteringTarget, rootLeash,
                (SurfaceControl) closingLeash, (SurfaceControl) enteringLeash,
                rootCornerRadius));
        moduleLog(Log.INFO, TAG,
                "Armed freeform cross-activity color-layer root adoption"
                        + ", taskId=" + taskId
                        + ", rootCornerRadius=" + rootCornerRadius);
    }

    protected Object correctPredictiveBackPrepareReparent(
            XposedInterface.Chain chain) throws Throwable {
        freeformColorRootCandidate.set(null);
        freeformColorRootAdoption = null;
        Object result = chain.proceed();
        if (!Boolean.TRUE.equals(result)) {
            return result;
        }
        try {
            captureFreeformColorRootCandidate(
                    chain.getThisObject(), chain.getArg(0), chain.getArg(1));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture freeform color-layer root candidate",
                    throwable);
        }
        try {
            Object handler = chain.getThisObject();
            Object info = chain.getArg(1);
            Object type = readTransitionInfoType(info);
            if (!(type instanceof Number)
                    || ((Number) type).intValue() != TRANSIT_PREDICTIVE_BACK) {
                return result;
            }
            if (readField(handler, "mPrepareOpenTransition") != chain.getArg(0)
                    || readField(handler, "mOpenTransitionInfo") != info) {
                return result;
            }
            Object controller = readField(handler, "this$0");
            Object navigationInfo = readField(controller, "mBackNavigationInfo");
            Object navigationType = readBackNavigationType(navigationInfo);
            if (!(navigationType instanceof Number)
                    || ((Number) navigationType).intValue()
                    != TYPE_RETURN_TO_HOME) {
                return result;
            }
            Object apps = readField(controller, "mApps");
            ReturnHomeComposition composition =
                    resolveReturnHomeComposition(apps);
            if (composition == null) {
                return result;
            }
            PreparedReturnHomeShape preparedShape =
                    resolvePreparedReturnHomeShape(
                            info, composition, TRANSIT_TO_FRONT);
            Object startTransaction = chain.getArg(2);
            if (preparedShape == null
                    || !(startTransaction instanceof SurfaceControl.Transaction)) {
                return result;
            }
            SurfaceControl changeLeash = preparedShape.appLeash;
            // The stock body already accepted and retained this prepare info, but Xiaomi's
            // TO_FRONT role made it treat the departing task as another opening surface. Repair
            // the physical parent first, then normalize only the retained semantic role to the
            // AOSP CHANGE shape used by handlePrepareTransition and mergePendingTransitions.
            ((SurfaceControl.Transaction) startTransaction)
                    .reparent(changeLeash, composition.closingLeash)
                    .apply();
            if (readField(handler, "mOpenTransitionInfo") != info
                    || readField(handler, "mPrepareOpenTransition")
                    != chain.getArg(0)) {
                throw new IllegalStateException(
                        "prepared return-home ownership changed after reparent");
            }
            if (!setTransitionChangeMode(preparedShape.appChange, TRANSIT_CHANGE)) {
                throw new IllegalStateException("prepared return-home change is not framework Change");
            }
            Object normalizedModeObject = readTransitionChangeMode(
                    preparedShape.appChange);
            int normalizedMode = normalizedModeObject instanceof Number
                    ? ((Number) normalizedModeObject).intValue() : -1;
            if (normalizedMode != TRANSIT_CHANGE) {
                throw new IllegalStateException(
                        "prepared return-home role normalization was not retained"
                                + ", mode=" + normalizedMode);
            }
            moduleLog(Log.INFO, TAG,
                    "Corrected Xiaomi predictive return-home prepare role"
                            + ", taskId=" + composition.closingTaskId
                            + ", mode=" + TRANSIT_TO_FRONT + "->" + normalizedMode
                            + ", wallpaperPresent="
                            + (preparedShape.wallpaperLeash != null));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed Xiaomi predictive return-home prepare role correction",
                    throwable);
        }
        return result;
    }

    protected Object correctPredictiveBackCommitComposition(
            XposedInterface.Chain chain) throws Throwable {
        OrphanedCloseRequestCandidate orphanedCloseRequest = null;
        try {
            orphanedCloseRequest = captureOrphanedCloseRequestCandidate(chain);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to inspect predictive-back close-request ownership",
                    throwable);
        }
        ReturnHomeCommitComposition candidate = null;
        try {
            candidate = captureReturnHomeCommitComposition(chain);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to inspect predictive return-home commit composition",
                    throwable);
        }
        ReturnHomeFinishTransferCandidate finishTransfer = null;
        if (isReturnHomeFinishTransferReady()) {
            try {
                finishTransfer = captureReturnHomeFinishTransferCandidate(chain);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to inspect rejected return-home CLOSE boundary",
                        throwable);
            }
        }
        boolean finishTransferArmed = false;
        if (finishTransfer != null) {
            ReturnHomeFinishTransferCandidate nested =
                    returnHomeFinishTransferCandidate.get();
            if (nested == null) {
                returnHomeFinishTransferCandidate.set(finishTransfer);
                finishTransferArmed = true;
                moduleLog(Log.INFO, TAG,
                        "Armed atomic prepared-finish transfer"
                                + ", transitionDebugId="
                                + finishTransfer.transitionDebugId
                                + ", preparedDebugId="
                                + finishTransfer.preparedDebugId
                                + ", taskId="
                                + finishTransfer.composition.closingTaskId);
            } else {
                moduleLog(Log.WARN, TAG,
                        "Rejected nested atomic prepared-finish transfer"
                                + ", transitionDebugId="
                                + finishTransfer.transitionDebugId
                                + ", activeTransitionDebugId="
                                + nested.transitionDebugId);
            }
        }
        Object[] routedArgs = null;
        if (candidate != null) {
            try {
                Object wrappedFinishCallback =
                        wrapAcceptedReturnHomeFinishCallback(candidate);
                routedArgs = chain.getArgs().toArray();
                routedArgs[5] = wrappedFinishCallback;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Could not arm accepted return-home commit composition",
                        throwable);
            }
        }
        Object result;
        try {
            result = routedArgs == null
                    ? chain.proceed() : chain.proceed(routedArgs);
        } finally {
            if (finishTransferArmed
                    && returnHomeFinishTransferCandidate.get()
                    == finishTransfer) {
                returnHomeFinishTransferCandidate.remove();
            }
        }
        if (finishTransferArmed
                && finishTransfer.transferAttempted.get() == 2) {
            publishStandardReturnHomeCommit(
                    finishTransfer.composition.closingTaskId,
                    finishTransfer.transitionDebugId,
                    finishTransfer.controller,
                    finishTransfer.preparedFinishCallback, true);
        }
        clearOrphanedCloseRequest(orphanedCloseRequest, chain);
        if (candidate == null) {
            return result;
        }
        ReturnHomeComposition composition = candidate.composition;
        try {
            Object currentApps = readField(candidate.controller, "mApps");
            Object navigationInfo = readField(candidate.controller,
                    "mBackNavigationInfo");
            Object navigationType = readBackNavigationType(navigationInfo);
            Object animationFinishCallback = readField(candidate.handler,
                    "mOnAnimationFinishCallback");
            Object currentPrepareOpen = readField(candidate.handler,
                    "mPrepareOpenTransition");
            Object currentOpenInfo = readField(candidate.handler,
                    "mOpenTransitionInfo");
            Object currentCloseRequested = readField(candidate.handler,
                    "mCloseTransitionRequested");
            boolean appsSame = currentApps == composition.appsIdentity;
            boolean callIdentitySame = chain.getArg(0) == candidate.transitionToken
                    && chain.getArg(1) == candidate.transitionInfo
                    && chain.getArg(2) == candidate.startTransaction
                    && chain.getArg(3) == candidate.finishTransaction
                    && chain.getArg(4) == candidate.mergeTarget
                    && chain.getArg(5) == candidate.finishCallback;
            boolean returnHomeStillCurrent = navigationType instanceof Number
                    && ((Number) navigationType).intValue() == TYPE_RETURN_TO_HOME;
            boolean closeStillRequested = Boolean.TRUE.equals(
                    currentCloseRequested);
            boolean prepareOpenSame = currentPrepareOpen == candidate.mergeTarget;
            boolean preparedInfoConsumed = currentOpenInfo == null;
            boolean freshFinishCallback = animationFinishCallback != null
                    && animationFinishCallback
                    != candidate.previousAnimationFinishCallback;
            boolean changeLeashValid = candidate.changeLeash.isValid();
            boolean closingLeashValid = composition.closingLeash.isValid();
            boolean openingLeashValid = composition.openingLeash.isValid();
            boolean accepted = appsSame && callIdentitySame
                    && returnHomeStillCurrent
                    && closeStillRequested && prepareOpenSame
                    && preparedInfoConsumed && freshFinishCallback
                    && changeLeashValid && closingLeashValid
                    && openingLeashValid;
            if (accepted) {
                boolean composedInStartTransaction =
                        candidate.acceptedBoundaryComposition.get() == 2;
                if (!composedInStartTransaction) {
                    moduleLog(Log.ERROR, TAG,
                            "Rejected non-atomic return-home commit composition"
                                    + ", taskId=" + composition.closingTaskId
                                    + ", boundaryPhase="
                                    + candidate.acceptedBoundaryComposition.get());
                    return result;
                }
                moduleLog(Log.INFO, TAG,
                        "Corrected accepted predictive return-home commit composition"
                                + ", taskId=" + composition.closingTaskId
                                + ", homeTaskId=" + composition.openingTaskId
                                + ", transitionType="
                                + candidate.transitionType
                                + ", changeLeash=" + candidate.changeLeash
                                + ", closingLeash="
                                + composition.closingLeash
                                + ", atomicStartTransaction=true");
                publishStandardReturnHomeCommit(
                        composition.closingTaskId,
                        readTransitionDebugId(candidate.transitionInfo),
                        candidate.controller, null, false);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed predictive return-home merge exit verification",
                    throwable);
        }
        return result;
    }

    protected static final class OrphanedCloseRequestCandidate {
        final Thread ownerThread;
        final Object handler;
        final Object controller;
        final Object preparedOpen;
        final Object preparedInfo;

        OrphanedCloseRequestCandidate(Thread ownerThread, Object handler,
                                      Object controller, Object preparedOpen,
                                      Object preparedInfo) {
            this.ownerThread = ownerThread;
            this.handler = handler;
            this.controller = controller;
            this.preparedOpen = preparedOpen;
            this.preparedInfo = preparedInfo;
        }
    }

    protected OrphanedCloseRequestCandidate
    captureOrphanedCloseRequestCandidate(XposedInterface.Chain chain)
            throws Exception {
        Thread ownerThread = Thread.currentThread();
        if (!"wmshell.main".equals(ownerThread.getName())) {
            return null;
        }
        Object handler = chain.getThisObject();
        Object controller = readField(handler, "this$0");
        Object preparedOpen = readField(handler, "mPrepareOpenTransition");
        Object preparedInfo = readField(handler, "mOpenTransitionInfo");
        Object preparedType = readTransitionInfoType(preparedInfo);
        if (controller == null
                || readField(controller, "mBackTransitionHandler") != handler
                || !Boolean.TRUE.equals(readField(
                handler, "mCloseTransitionRequested"))
                || preparedOpen == null || preparedOpen != chain.getArg(4)
                || preparedOpen == chain.getArg(0)
                || preparedInfo == null || preparedInfo == chain.getArg(1)
                || !(preparedType instanceof Number)
                || ((Number) preparedType).intValue()
                != TRANSIT_PREDICTIVE_BACK
                || readField(handler, "mFinishOpenTransaction") == null
                || readField(handler, "mFinishOpenTransitionCallback") == null
                || readField(handler, "mClosePrepareTransition") != null
                || readField(handler, "mOnAnimationFinishCallback") != null
                || readField(controller, "mApps") != null
                || readField(controller, "mActiveCallback") != null
                || !isShellReadyOnOwner(controller)) {
            return null;
        }
        return new OrphanedCloseRequestCandidate(ownerThread, handler,
                controller, preparedOpen, preparedInfo);
    }

    protected void clearOrphanedCloseRequest(
            OrphanedCloseRequestCandidate candidate,
            XposedInterface.Chain chain) {
        if (candidate == null) {
            return;
        }
        try {
            Object handler = chain.getThisObject();
            Object controller = readField(handler, "this$0");
            boolean orphaned = Thread.currentThread() == candidate.ownerThread
                    && "wmshell.main".equals(
                    candidate.ownerThread.getName())
                    && handler == candidate.handler
                    && controller == candidate.controller
                    && readField(controller, "mBackTransitionHandler") == handler
                    && chain.getArg(4) == candidate.preparedOpen
                    && Boolean.TRUE.equals(readField(
                    handler, "mCloseTransitionRequested"))
                    && readField(handler, "mPrepareOpenTransition") == null
                    && readField(handler, "mOpenTransitionInfo") == null
                    && readField(handler, "mFinishOpenTransaction") == null
                    && readField(handler, "mFinishOpenTransitionCallback") == null
                    && readField(handler, "mClosePrepareTransition") == null
                    && readField(handler, "mOnAnimationFinishCallback") == null
                    && readField(handler, "mTakeoverHandler") == null
                    && readField(controller, "mApps") == null
                    && readField(controller, "mActiveCallback") == null
                    && isShellReadyOnOwner(controller);
            if (!orphaned) {
                return;
            }
            writeField(handler, "mCloseTransitionRequested", Boolean.FALSE);
            if (!Boolean.FALSE.equals(readField(
                    handler, "mCloseTransitionRequested"))) {
                throw new IllegalStateException(
                        "predictive-back close request remained set");
            }
            moduleLog(Log.INFO, TAG,
                    "Cleared orphaned predictive-back close request"
                            + ", preparedTransitionDebugId="
                            + readTransitionDebugId(candidate.preparedInfo)
                            + ", mergeTransitionDebugId="
                            + readTransitionDebugId(chain.getArg(1)));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to clear orphaned predictive-back close request",
                    throwable);
        }
    }

    protected Object wrapAcceptedReturnHomeFinishCallback(
            ReturnHomeCommitComposition candidate) throws Exception {
        ClassLoader classLoader = candidate.handler.getClass().getClassLoader();
        Class<?> callbackClass = Class.forName(
                "com.android.wm.shell.transition.Transitions$TransitionFinishCallback",
                false, classLoader);
        if (!callbackClass.isInstance(candidate.finishCallback)) {
            throw new IllegalStateException("Unexpected transition finish callback: "
                    + shortObject(candidate.finishCallback));
        }
        return Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass},
                (proxy, method, invocationArgs) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return headlessUpdaterResult(
                                proxy, method, invocationArgs);
                    }
                    if ("onTransitionFinished".equals(method.getName())
                            && method.getParameterCount() == 1
                            && candidate.acceptedBoundaryComposition
                            .compareAndSet(0, 1)) {
                        try {
                            composeAcceptedReturnHomeCommit(candidate);
                            candidate.acceptedBoundaryComposition.set(2);
                        } catch (Throwable throwable) {
                            candidate.acceptedBoundaryComposition.set(3);
                            moduleLog(Log.WARN, TAG,
                                    "Failed accepted return-home start-transaction composition"
                                            + ", taskId="
                                            + candidate.composition.closingTaskId
                                            + ", transitionDebugId="
                                            + readTransitionDebugId(
                                            candidate.transitionInfo),
                                    throwable);
                        }
                    }
                    try {
                        return method.invoke(candidate.finishCallback,
                                invocationArgs);
                    } catch (InvocationTargetException exception) {
                        Throwable cause = exception.getCause();
                        throw cause == null ? exception : cause;
                    }
                });
    }

    protected void composeAcceptedReturnHomeCommit(
            ReturnHomeCommitComposition candidate) throws Exception {
        ReturnHomeComposition composition = candidate.composition;
        Object currentApps = readField(candidate.controller, "mApps");
        Object navigationInfo = readField(candidate.controller,
                "mBackNavigationInfo");
        Object navigationType = readBackNavigationType(navigationInfo);
        boolean exact = "wmshell.main".equals(Thread.currentThread().getName())
                && currentApps == composition.appsIdentity
                && navigationType instanceof Number
                && ((Number) navigationType).intValue()
                == TYPE_RETURN_TO_HOME
                && Boolean.TRUE.equals(readField(candidate.handler,
                "mCloseTransitionRequested"))
                && readField(candidate.handler, "mPrepareOpenTransition")
                == candidate.mergeTarget
                && readField(candidate.handler, "mOpenTransitionInfo") == null
                && readField(candidate.handler, "mOnAnimationFinishCallback")
                == candidate.previousAnimationFinishCallback
                && candidate.startTransaction
                instanceof SurfaceControl.Transaction
                && candidate.changeLeash.isValid()
                && composition.closingLeash.isValid()
                && composition.openingLeash.isValid();
        if (!exact) {
            throw new IllegalStateException(
                    "return-home ownership changed at accepted callback");
        }
        // Xiaomi's current BackTransitionHandler calls this finish callback only after its
        // commit predicates have accepted the merge and immediately before applying the same
        // start Transaction. Append the AOSP closing-parent correction at that boundary so
        // SurfaceFlinger can never present the unparented fullscreen change in between.
        ((SurfaceControl.Transaction) candidate.startTransaction).reparent(
                candidate.changeLeash, composition.closingLeash);
        moduleLog(Log.INFO, TAG,
                "Composed accepted predictive return-home commit in original start transaction"
                        + ", taskId=" + composition.closingTaskId
                        + ", homeTaskId=" + composition.openingTaskId
                        + ", transitionDebugId="
                        + readTransitionDebugId(candidate.transitionInfo)
                        + ", changeLeash=" + candidate.changeLeash
                        + ", closingLeash=" + composition.closingLeash);
    }

    protected ReturnHomeCommitComposition captureReturnHomeCommitComposition(
            XposedInterface.Chain chain) throws Exception {
        Object handler = chain.getThisObject();
        if (!Boolean.TRUE.equals(readField(handler, "mCloseTransitionRequested"))) {
            return null;
        }
        Object controller = readField(handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        if (navigationInfo == null) {
            return null;
        }
        Object navigationType = readBackNavigationType(navigationInfo);
        if (!(navigationType instanceof Number)
                || ((Number) navigationType).intValue() != TYPE_RETURN_TO_HOME) {
            return null;
        }
        Object transitionToken = chain.getArg(0);
        Object info = chain.getArg(1);
        Object startTransaction = chain.getArg(2);
        Object finishTransaction = chain.getArg(3);
        Object mergeTarget = chain.getArg(4);
        Object finishCallback = chain.getArg(5);
        Object preparedOpenInfo = readField(handler, "mOpenTransitionInfo");
        Object previousAnimationFinishCallback = readField(
                handler, "mOnAnimationFinishCallback");
        Object preparedOpenToken = readField(handler, "mPrepareOpenTransition");
        if (transitionToken == null || info == null
                || !(startTransaction instanceof SurfaceControl.Transaction)
                || !(finishTransaction instanceof SurfaceControl.Transaction)
                || mergeTarget == null || finishCallback == null
                || transitionToken == mergeTarget
                || preparedOpenToken != mergeTarget
                || preparedOpenInfo == null
                || previousAnimationFinishCallback != null) {
            return null;
        }
        Object preparedTypeObject = readTransitionInfoType(preparedOpenInfo);
        if (!(preparedTypeObject instanceof Number)
                || ((Number) preparedTypeObject).intValue()
                != TRANSIT_PREDICTIVE_BACK) {
            return null;
        }
        ReturnHomeComposition composition = resolveReturnHomeComposition(
                readField(controller, "mApps"));
        if (composition == null) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "non-standard targets");
            return null;
        }
        PreparedReturnHomeShape preparedShape =
                resolvePreparedReturnHomeShape(
                        preparedOpenInfo, composition, TRANSIT_CHANGE);
        if (preparedShape == null) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "non-standard prepared transition");
            return null;
        }
        Object transitionTypeObject = readTransitionInfoType(info);
        int transitionType = transitionTypeObject instanceof Number
                ? ((Number) transitionTypeObject).intValue() : -1;
        boolean supportedClosingType = transitionType == TRANSIT_CLOSE
                || transitionType == TRANSIT_TO_BACK;
        if (!supportedClosingType) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "unexpected transition type=" + transitionType);
            return null;
        }
        Object changesObject = readTransitionInfoChanges(info);
        if (!(changesObject instanceof List<?>)) {
            return null;
        }
        Object matchingChange = null;
        int matchingMode = -1;
        boolean backGestureAnimated = false;
        boolean elementChangePresent = false;
        int matchCount = 0;
        for (Object change : (List<?>) changesObject) {
            Object flagsObject = readTransitionChangeFlags(change);
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            if (flags == FLAG_IS_ELEMENT) {
                elementChangePresent = true;
            }
            Object taskInfo = readTransitionChangeTaskInfo(change);
            if (readIntFieldOrDefault(taskInfo, "taskId", -1)
                    != composition.closingTaskId) {
                continue;
            }
            matchCount++;
            matchingChange = change;
            Object modeObject = readTransitionChangeMode(change);
            matchingMode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            backGestureAnimated = Boolean.TRUE.equals(hasTransitionChangeFlags(
                    change, FLAG_BACK_GESTURE_ANIMATED));
        }
        if (matchCount != 1 || matchingChange == null
                || matchingMode != transitionType
                || !backGestureAnimated || elementChangePresent) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "closing change mismatch"
                            + ", taskId=" + composition.closingTaskId
                            + ", transitionType=" + transitionType
                            + ", matches=" + matchCount
                            + ", mode=" + matchingMode
                            + ", backGestureAnimated=" + backGestureAnimated
                            + ", elementChangePresent="
                            + elementChangePresent);
            return null;
        }
        Object changeLeashObject = readTransitionChangeLeash(matchingChange);
        if (!(changeLeashObject instanceof SurfaceControl)
                || !((SurfaceControl) changeLeashObject).isValid()
                || surfacesAreSame((SurfaceControl) changeLeashObject,
                composition.closingLeash)
                || surfacesAreSame((SurfaceControl) changeLeashObject,
                composition.openingLeash)
                || !surfacesAreSame((SurfaceControl) changeLeashObject,
                preparedShape.appLeash)) {
            moduleLog(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "invalid or aliased change leash"
                            + ", taskId=" + composition.closingTaskId
                            + ", transitionType=" + transitionType
                            + ", mode=" + matchingMode
                            + ", changeLeash=" + shortObject(changeLeashObject));
            return null;
        }
        return new ReturnHomeCommitComposition(handler, controller, composition,
                (SurfaceControl) changeLeashObject, transitionToken, info,
                startTransaction, finishTransaction, mergeTarget,
                finishCallback, previousAnimationFinishCallback,
                transitionType);
    }

    protected ReturnHomeFinishTransferCandidate
    captureReturnHomeFinishTransferCandidate(
            XposedInterface.Chain chain) throws Exception {
        Thread ownerThread = Thread.currentThread();
        if (!isReturnHomeFinishTransferReady()
                || !"wmshell.main".equals(ownerThread.getName())) {
            return null;
        }
        Object handler = chain.getThisObject();
        if (!Boolean.TRUE.equals(readField(
                handler, "mCloseTransitionRequested"))) {
            return null;
        }
        Object controller = readField(handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        Object navigationType = readBackNavigationType(navigationInfo);
        if (!(navigationType instanceof Number)
                || ((Number) navigationType).intValue()
                != TYPE_RETURN_TO_HOME) {
            return null;
        }

        Object transitionToken = chain.getArg(0);
        Object info = chain.getArg(1);
        Object startTransactionObject = chain.getArg(2);
        Object incomingFinishTransactionObject = chain.getArg(3);
        Object mergeTarget = chain.getArg(4);
        Object incomingFinishCallback = chain.getArg(5);
        Object preparedOpenInfo = readField(handler, "mOpenTransitionInfo");
        Object preparedOpenToken = readField(
                handler, "mPrepareOpenTransition");
        Object preparedFinishTransactionObject = readField(
                handler, "mFinishOpenTransaction");
        Object preparedFinishCallback = readField(
                handler, "mFinishOpenTransitionCallback");
        Object animationFinishCallback = readField(
                handler, "mOnAnimationFinishCallback");
        Object closePrepareTransition = readField(
                handler, "mClosePrepareTransition");
        Object takeoverHandler = readField(handler, "mTakeoverHandler");
        if (transitionToken == null || info == null
                || !(startTransactionObject
                instanceof SurfaceControl.Transaction)
                || !(incomingFinishTransactionObject
                instanceof SurfaceControl.Transaction)
                || mergeTarget == null || incomingFinishCallback == null
                || preparedOpenInfo == null
                || preparedOpenToken != mergeTarget
                || !(preparedFinishTransactionObject
                instanceof SurfaceControl.Transaction)
                || preparedFinishCallback == null
                || animationFinishCallback != null
                || closePrepareTransition != null
                || takeoverHandler != null
                || transitionToken == mergeTarget
                || startTransactionObject == incomingFinishTransactionObject
                || startTransactionObject == preparedFinishTransactionObject
                || incomingFinishTransactionObject
                == preparedFinishTransactionObject
                || incomingFinishCallback == preparedFinishCallback) {
            return null;
        }

        Object incomingTypeObject = readTransitionInfoType(info);
        Object preparedTypeObject = readTransitionInfoType(preparedOpenInfo);
        int incomingType = incomingTypeObject instanceof Number
                ? ((Number) incomingTypeObject).intValue() : -1;
        int preparedType = preparedTypeObject instanceof Number
                ? ((Number) preparedTypeObject).intValue() : -1;
        int transitionDebugId = readTransitionDebugId(info);
        int preparedDebugId = readTransitionDebugId(preparedOpenInfo);
        boolean supportedIncomingType = incomingType == TRANSIT_CLOSE
                || incomingType == TRANSIT_TO_BACK;
        if (!supportedIncomingType
                || preparedType != TRANSIT_PREDICTIVE_BACK
                || transitionDebugId < 0 || preparedDebugId < 0
                || transitionDebugId == preparedDebugId) {
            return null;
        }
        Object transitions = readField(handler, "mTransitions");
        Object remoteTransitionHandler = invokeAnyMethod(
                transitions, "getRemoteTransitionHandler", new Object[0]);
        Object remoteHandlerType = invokeAnyMethod(
                remoteTransitionHandler, "getTransitionType", new Object[0]);
        Object miuiTransitionInfo = invokeAnyMethod(
                info, "getMiuiTransitionInfo", new Object[0]);
        Object expectedHandlerType = invokeAnyMethod(
                miuiTransitionInfo, "getExpectHandlerType", new Object[0]);
        Object remoteCanHandle = invokeAnyMethod(
                remoteTransitionHandler, "canHandleTransition",
                new Object[]{transitionToken, info});
        if (!"com.android.wm.shell.transition.RemoteTransitionHandler".equals(
                remoteTransitionHandler.getClass().getName())
                || !(remoteHandlerType instanceof Number)
                || ((Number) remoteHandlerType).intValue() != 11
                || !(expectedHandlerType instanceof Number)
                || ((Number) expectedHandlerType).intValue() != 11
                || !Boolean.TRUE.equals(remoteCanHandle)) {
            return null;
        }

        ReturnHomeComposition composition = resolveReturnHomeComposition(
                readField(controller, "mApps"));
        if (composition == null) {
            return null;
        }
        if (!(navigationInfo instanceof BackNavigationInfo)
                || ((BackNavigationInfo) navigationInfo).getFocusedTaskId()
                != composition.closingTaskId) {
            return null;
        }
        PreparedReturnHomeShape preparedShape =
                resolvePreparedReturnHomeShape(
                        preparedOpenInfo, composition, TRANSIT_CHANGE);
        if (preparedShape == null) {
            return null;
        }
        Rect closingBounds = preparedShape.closingBounds;
        Rect openingBounds = preparedShape.openingBounds;

        Object changesObject = readTransitionInfoChanges(info);
        if (!(changesObject instanceof List<?>)
                || ((List<?>) changesObject).size() != 3) {
            return null;
        }
        Object elementChange = null;
        Object appChange = null;
        SurfaceControl homeLeash = null;
        SurfaceControl elementLeash = null;
        SurfaceControl appLeash = null;
        Rect elementEndBounds = null;
        int capturedAppFlags = Integer.MIN_VALUE;
        int elementStartDisplayId = Integer.MIN_VALUE;
        int elementEndDisplayId = Integer.MIN_VALUE;
        for (Object change : (List<?>) changesObject) {
            Object modeObject = readTransitionChangeMode(change);
            Object flagsObject = readTransitionChangeFlags(change);
            Object taskInfo = readTransitionChangeTaskInfo(change);
            Object leashObject = readTransitionChangeLeash(change);
            Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
            Object endBoundsObject = readTransitionChangeEndAbsBounds(change);
            Object startDisplayObject = readTransitionChangeStartDisplayId(change);
            Object endDisplayObject = readTransitionChangeEndDisplayId(change);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            int taskId = readIntFieldOrDefault(taskInfo, "taskId", -1);
            int startDisplayId = startDisplayObject instanceof Number
                    ? ((Number) startDisplayObject).intValue() : -2;
            int endDisplayId = endDisplayObject instanceof Number
                    ? ((Number) endDisplayObject).intValue() : -2;
            if (!(leashObject instanceof SurfaceControl)
                    || !((SurfaceControl) leashObject).isValid()
                    || !(startBoundsObject instanceof Rect)
                    || !(endBoundsObject instanceof Rect)) {
                return null;
            }
            Rect startBounds = (Rect) startBoundsObject;
            Rect endBounds = (Rect) endBoundsObject;
            if (taskId == composition.openingTaskId
                    && homeLeash == null) {
                if (mode != TRANSIT_TO_FRONT
                        || flags != XIAOMI_ELEMENT_HOME_CHANGE_FLAGS
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_HOME
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != composition.displayId
                        || startDisplayId != composition.displayId
                        || endDisplayId != composition.displayId
                        || !startBounds.equals(openingBounds)
                        || !endBounds.equals(openingBounds)) {
                    return null;
                }
                homeLeash = (SurfaceControl) leashObject;
                continue;
            }
            if (taskId == composition.closingTaskId
                    && appChange == null) {
                boolean appFlags = flags
                        == FLAG_BACK_GESTURE_ANIMATED
                        || flags == (FLAG_BACK_GESTURE_ANIMATED
                        | FLAG_DISPLAY_CHANGE);
                if (mode != incomingType || !appFlags
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_STANDARD
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != composition.displayId
                        || startDisplayId != composition.displayId
                        || endDisplayId != composition.displayId
                        || !startBounds.equals(closingBounds)
                        || !endBounds.equals(closingBounds)) {
                    return null;
                }
                appChange = change;
                appLeash = (SurfaceControl) leashObject;
                capturedAppFlags = flags;
                continue;
            }
            if (taskInfo == null && elementChange == null
                    && mode == incomingType
                    && flags == FLAG_IS_ELEMENT
                    && startBounds.equals(closingBounds)
                    && !endBounds.isEmpty()
                    && !endBounds.equals(closingBounds)
                    && startDisplayId == endDisplayId
                    && (startDisplayId == -1
                    || startDisplayId == composition.displayId)
                    && closingBounds.contains(endBounds)) {
                elementChange = change;
                elementLeash = (SurfaceControl) leashObject;
                elementEndBounds = new Rect(endBounds);
                elementStartDisplayId = startDisplayId;
                elementEndDisplayId = endDisplayId;
                continue;
            }
            return null;
        }
        if (elementChange == null || appChange == null || homeLeash == null
                || elementLeash == null || appLeash == null
                || elementEndBounds == null
                || capturedAppFlags == Integer.MIN_VALUE
                || elementStartDisplayId == Integer.MIN_VALUE
                || elementEndDisplayId == Integer.MIN_VALUE
                || surfacesAreSame(homeLeash, elementLeash)
                || surfacesAreSame(homeLeash, appLeash)
                || surfacesAreSame(elementLeash, appLeash)
                || surfacesAreSame(appLeash,
                composition.closingLeash)
                || surfacesAreSame(homeLeash,
                composition.openingLeash)
                || surfacesAreSame(elementLeash,
                composition.closingLeash)
                || surfacesAreSame(elementLeash,
                composition.openingLeash)) {
            return null;
        }

        if (!surfacesAreSame(preparedShape.appLeash, appLeash)
                || !surfacesAreSame(preparedShape.homeLeash, homeLeash)
                || (preparedShape.wallpaperLeash != null
                && surfacesAreSame(
                preparedShape.wallpaperLeash, elementLeash))) {
            return null;
        }

        return new ReturnHomeFinishTransferCandidate(
                handler, controller, ownerThread, transitions,
                remoteTransitionHandler, composition,
                transitionToken, info,
                mergeTarget,
                (SurfaceControl.Transaction) startTransactionObject,
                preparedOpenInfo,
                (SurfaceControl.Transaction) preparedFinishTransactionObject,
                preparedFinishCallback, elementChange, appChange,
                homeLeash, elementLeash, appLeash, closingBounds,
                elementEndBounds, incomingType, capturedAppFlags,
                elementStartDisplayId,
                elementEndDisplayId, transitionDebugId, preparedDebugId);
    }

    protected static final class PreparedReturnHomeShape {
        public final Object appChange;
        public final SurfaceControl appLeash;
        public final SurfaceControl homeLeash;
        public final SurfaceControl wallpaperLeash;
        public final Rect closingBounds;
        public final Rect openingBounds;

        PreparedReturnHomeShape(
                Object appChange, SurfaceControl appLeash,
                SurfaceControl homeLeash, SurfaceControl wallpaperLeash,
                Rect closingBounds, Rect openingBounds) {
            this.appChange = appChange;
            this.appLeash = appLeash;
            this.homeLeash = homeLeash;
            this.wallpaperLeash = wallpaperLeash;
            this.closingBounds = closingBounds;
            this.openingBounds = openingBounds;
        }
    }

    protected PreparedReturnHomeShape resolvePreparedReturnHomeShape(
            Object info, ReturnHomeComposition composition,
            int expectedAppMode) throws Exception {
        Object typeObject = readTransitionInfoType(info);
        if (!(typeObject instanceof Number)
                || ((Number) typeObject).intValue()
                != TRANSIT_PREDICTIVE_BACK
                || (expectedAppMode != TRANSIT_TO_FRONT
                && expectedAppMode != TRANSIT_CHANGE)) {
            return null;
        }
        Rect closingBounds = resolveExactRemoteTargetTransitionBounds(
                composition.closingTarget);
        Rect openingBounds = resolveExactRemoteTargetTransitionBounds(
                composition.openingTarget);
        if (!closingBounds.equals(openingBounds)) {
            return null;
        }
        Object changesObject = readTransitionInfoChanges(info);
        if (!(changesObject instanceof List<?>)) {
            return null;
        }
        List<?> changes = (List<?>) changesObject;
        int changeCount = changes.size();
        if (changeCount != 2 && changeCount != 3) {
            return null;
        }
        boolean wallpaperExpected = changeCount == 3;
        // The exact two-change prepared shape omits SHOW_WALLPAPER on Home.
        int expectedHomeFlags = wallpaperExpected
                ? XIAOMI_PREPARED_HOME_CHANGE_FLAGS
                : XIAOMI_PREPARED_HOME_NO_WALLPAPER_CHANGE_FLAGS;
        Object appChange = null;
        SurfaceControl appLeash = null;
        SurfaceControl homeLeash = null;
        SurfaceControl wallpaperLeash = null;
        for (Object change : changes) {
            Object modeObject = readTransitionChangeMode(change);
            Object flagsObject = readTransitionChangeFlags(change);
            Object taskInfo = readTransitionChangeTaskInfo(change);
            Object leashObject = readTransitionChangeLeash(change);
            Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
            Object endBoundsObject = readTransitionChangeEndAbsBounds(change);
            Object startDisplayObject = readTransitionChangeStartDisplayId(change);
            Object endDisplayObject = readTransitionChangeEndDisplayId(change);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            int taskId = readIntFieldOrDefault(taskInfo, "taskId", -1);
            int startDisplayId = startDisplayObject instanceof Number
                    ? ((Number) startDisplayObject).intValue() : -2;
            int endDisplayId = endDisplayObject instanceof Number
                    ? ((Number) endDisplayObject).intValue() : -2;
            if (!(leashObject instanceof SurfaceControl)
                    || !((SurfaceControl) leashObject).isValid()
                    || !(startBoundsObject instanceof Rect)
                    || !(endBoundsObject instanceof Rect)) {
                return null;
            }
            Rect startBounds = (Rect) startBoundsObject;
            Rect endBounds = (Rect) endBoundsObject;
            if (taskId == composition.closingTaskId
                    && appLeash == null) {
                boolean appFlags = flags
                        == FLAG_BACK_GESTURE_ANIMATED
                        || flags == (FLAG_BACK_GESTURE_ANIMATED
                        | FLAG_DISPLAY_CHANGE);
                if (mode != expectedAppMode || !appFlags
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_STANDARD
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != composition.displayId
                        || startDisplayId != composition.displayId
                        || endDisplayId != composition.displayId
                        || !startBounds.equals(closingBounds)
                        || !endBounds.equals(closingBounds)) {
                    return null;
                }
                appChange = change;
                appLeash = (SurfaceControl) leashObject;
                continue;
            }
            if (taskId == composition.openingTaskId
                    && homeLeash == null) {
                if (mode != TRANSIT_TO_FRONT
                        || flags != expectedHomeFlags
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_HOME
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != composition.displayId
                        || startDisplayId != composition.displayId
                        || endDisplayId != composition.displayId
                        || !startBounds.equals(openingBounds)
                        || !endBounds.equals(openingBounds)) {
                    return null;
                }
                homeLeash = (SurfaceControl) leashObject;
                continue;
            }
            if (taskInfo == null && wallpaperLeash == null
                    && mode == TRANSIT_TO_FRONT
                    && flags == FLAG_IS_WALLPAPER
                    && startBounds.equals(closingBounds)
                    && endBounds.equals(closingBounds)) {
                wallpaperLeash = (SurfaceControl) leashObject;
                continue;
            }
            return null;
        }
        boolean wallpaperMatches = wallpaperExpected
                ? wallpaperLeash != null
                  && !surfacesAreSame(wallpaperLeash, appLeash)
                  && !surfacesAreSame(wallpaperLeash, homeLeash)
                : wallpaperLeash == null;
        if (appChange == null || appLeash == null || homeLeash == null
                || !wallpaperMatches
                || surfacesAreSame(appLeash, homeLeash)
                || surfacesAreSame(appLeash, composition.closingLeash)
                || surfacesAreSame(appLeash, composition.openingLeash)
                || surfacesAreSame(homeLeash, composition.closingLeash)
                || surfacesAreSame(homeLeash, composition.openingLeash)
                || (wallpaperLeash != null
                && (surfacesAreSame(
                wallpaperLeash, composition.closingLeash)
                || surfacesAreSame(
                wallpaperLeash, composition.openingLeash)))) {
            return null;
        }
        return new PreparedReturnHomeShape(
                appChange, appLeash, homeLeash, wallpaperLeash,
                new Rect(closingBounds), new Rect(openingBounds));
    }

    protected Rect resolveExactRemoteTargetTransitionBounds(Object target)
            throws Exception {
        Object startBoundsObject = readField(target, "startBounds");
        Object sourceBoundsObject = readField(
                target, "sourceContainerBounds");
        if (!(startBoundsObject instanceof Rect)
                || !(sourceBoundsObject instanceof Rect)) {
            throw new IllegalStateException(
                    "RemoteAnimationTarget transition bounds unavailable");
        }
        Rect startBounds = (Rect) startBoundsObject;
        Rect sourceBounds = (Rect) sourceBoundsObject;
        if (startBounds.isEmpty() || !startBounds.equals(sourceBounds)) {
            throw new IllegalStateException(
                    "RemoteAnimationTarget transition bounds mismatch"
                            + ", start=" + startBounds
                            + ", source=" + sourceBounds);
        }
        return new Rect(startBounds);
    }

    protected boolean isExactReturnHomeFinishTransferPostShape(
            ReturnHomeFinishTransferCandidate candidate) throws Exception {
        Object typeObject = readTransitionInfoType(candidate.transitionInfo);
        Object changesObject = readTransitionInfoChanges(candidate.transitionInfo);
        if (!(typeObject instanceof Number)
                || ((Number) typeObject).intValue()
                != candidate.transitionType
                || !(changesObject instanceof List<?>)
                || ((List<?>) changesObject).size() != 2) {
            return false;
        }
        boolean elementMatched = false;
        boolean appMatched = false;
        for (Object change : (List<?>) changesObject) {
            Object modeObject = readTransitionChangeMode(change);
            Object flagsObject = readTransitionChangeFlags(change);
            Object taskInfo = readTransitionChangeTaskInfo(change);
            Object leashObject = readTransitionChangeLeash(change);
            Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
            Object endBoundsObject = readTransitionChangeEndAbsBounds(change);
            Object startDisplayObject = readTransitionChangeStartDisplayId(change);
            Object endDisplayObject = readTransitionChangeEndDisplayId(change);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            int startDisplayId = startDisplayObject instanceof Number
                    ? ((Number) startDisplayObject).intValue() : -2;
            int endDisplayId = endDisplayObject instanceof Number
                    ? ((Number) endDisplayObject).intValue() : -2;
            if (!(leashObject instanceof SurfaceControl)
                    || !((SurfaceControl) leashObject).isValid()
                    || !(startBoundsObject instanceof Rect)
                    || !(endBoundsObject instanceof Rect)) {
                return false;
            }
            if (change == candidate.elementChange) {
                if (elementMatched || taskInfo != null
                        || mode != candidate.transitionType
                        || flags != FLAG_IS_ELEMENT
                        || startDisplayId
                        != candidate.elementStartDisplayId
                        || endDisplayId != candidate.elementEndDisplayId
                        || !candidate.fullscreenBounds.equals(
                        startBoundsObject)
                        || !candidate.elementEndBounds.equals(
                        endBoundsObject)
                        || !surfacesAreSame(
                        (SurfaceControl) leashObject,
                        candidate.elementLeash)) {
                    return false;
                }
                elementMatched = true;
                continue;
            }
            if (change == candidate.appChange) {
                if (appMatched || taskInfo == null
                        || mode != candidate.transitionType
                        || flags != candidate.appFlags
                        || readIntFieldOrDefault(
                        taskInfo, "taskId", -1)
                        != candidate.composition.closingTaskId
                        || readIntFieldOrDefault(
                        taskInfo, "displayId", -1)
                        != candidate.composition.displayId
                        || resolveTaskInfoActivityType(taskInfo)
                        != ACTIVITY_TYPE_STANDARD
                        || resolveTaskInfoWindowingMode(taskInfo)
                        != WINDOWING_MODE_FULLSCREEN
                        || startDisplayId
                        != candidate.composition.displayId
                        || endDisplayId
                        != candidate.composition.displayId
                        || !candidate.fullscreenBounds.equals(
                        startBoundsObject)
                        || !candidate.fullscreenBounds.equals(
                        endBoundsObject)
                        || !surfacesAreSame(
                        (SurfaceControl) leashObject,
                        candidate.appLeash)) {
                    return false;
                }
                appMatched = true;
                continue;
            }
            return false;
        }
        return elementMatched && appMatched
                && candidate.homeLeash.isValid()
                && candidate.elementLeash.isValid()
                && candidate.appLeash.isValid()
                && candidate.composition.closingLeash.isValid()
                && candidate.composition.openingLeash.isValid();
    }

    protected Object transferReturnHomeFinishIntoCloseStart(
            XposedInterface.Chain chain) throws Throwable {
        ReturnHomeFinishTransferCandidate candidate =
                returnHomeFinishTransferCandidate.get();
        if (candidate == null) {
            return chain.proceed();
        }
        boolean exact = false;
        boolean transferred = false;
        try {
            Object handler = chain.getThisObject();
            Object navigationInfo = readField(
                    candidate.controller, "mBackNavigationInfo");
            Object navigationType = readBackNavigationType(navigationInfo);
            Object focusedTaskId = navigationInfo instanceof BackNavigationInfo
                    ? ((BackNavigationInfo) navigationInfo).getFocusedTaskId() : null;
            Object transitions = readField(handler, "mTransitions");
            Object remoteTransitionHandler = invokeAnyMethod(
                    transitions, "getRemoteTransitionHandler", new Object[0]);
            Object remoteHandlerType = invokeAnyMethod(
                    remoteTransitionHandler, "getTransitionType", new Object[0]);
            Object miuiTransitionInfo = invokeAnyMethod(
                    candidate.transitionInfo,
                    "getMiuiTransitionInfo", new Object[0]);
            Object expectedHandlerType = invokeAnyMethod(
                    miuiTransitionInfo,
                    "getExpectHandlerType", new Object[0]);
            Object remoteCanHandle = invokeAnyMethod(
                    remoteTransitionHandler, "canHandleTransition",
                    new Object[]{candidate.transitionToken,
                            candidate.transitionInfo});
            exact = isReturnHomeFinishTransferReady()
                    && Thread.currentThread() == candidate.ownerThread
                    && "wmshell.main".equals(
                    Thread.currentThread().getName())
                    && handler == candidate.handler
                    && chain.getExecutable().getParameterCount() == 0
                    && Boolean.TRUE.equals(readField(
                    handler, "mCloseTransitionRequested"))
                    && readField(handler, "mOpenTransitionInfo") == null
                    && readField(handler, "mPrepareOpenTransition")
                    == candidate.mergeTarget
                    && readField(handler, "mFinishOpenTransaction")
                    == candidate.preparedFinishTransaction
                    && readField(handler, "mFinishOpenTransitionCallback")
                    == candidate.preparedFinishCallback
                    && readField(handler, "mOnAnimationFinishCallback") == null
                    && readField(handler, "mClosePrepareTransition") == null
                    && readField(handler, "mTakeoverHandler") == null
                    && transitions == candidate.transitions
                    && remoteTransitionHandler
                    == candidate.remoteTransitionHandler
                    && remoteHandlerType instanceof Number
                    && ((Number) remoteHandlerType).intValue() == 11
                    && expectedHandlerType instanceof Number
                    && ((Number) expectedHandlerType).intValue() == 11
                    && Boolean.TRUE.equals(remoteCanHandle)
                    && readField(candidate.controller, "mApps")
                    == candidate.composition.appsIdentity
                    && navigationType instanceof Number
                    && ((Number) navigationType).intValue()
                    == TYPE_RETURN_TO_HOME
                    && focusedTaskId instanceof Number
                    && ((Number) focusedTaskId).intValue()
                    == candidate.composition.closingTaskId
                    && readTransitionDebugId(candidate.transitionInfo)
                    == candidate.transitionDebugId
                    && readTransitionDebugId(candidate.preparedOpenInfo)
                    == candidate.preparedDebugId
                    && isExactReturnHomeFinishTransferPostShape(candidate);
            boolean firstAttempt = candidate.transferAttempted.compareAndSet(0, 1);
            if (!exact || !firstAttempt) {
                moduleLog(Log.WARN, TAG,
                        "Rejected prepared-finish atomic transfer at apply boundary"
                                + ", exact=" + exact
                                + ", transitionDebugId="
                                + candidate.transitionDebugId
                                + ", preparedDebugId="
                                + candidate.preparedDebugId);
                return chain.proceed();
            }

            // BackTransitionHandler is about to apply the prepared transition's finish
            // transaction and then release it. Move those operations into the exact incoming
            // native start transaction first. The original method applies the now-empty donor,
            // while MiuiHome appends its task reparent/geometry to the same incoming transaction
            // before the transaction is finally applied. This removes the compositor-visible
            // gap without changing either native animation's surfaces or geometry.
            candidate.startTransaction.merge(
                    candidate.preparedFinishTransaction);
            transferred = true;
            moduleLog(Log.INFO, TAG,
                    "Transferred prepared finish into Xiaomi native start transaction"
                            + ", transitionDebugId="
                            + candidate.transitionDebugId
                            + ", preparedDebugId="
                            + candidate.preparedDebugId
                            + ", transitionType="
                            + candidate.transitionType
                            + ", taskId="
                            + candidate.composition.closingTaskId);
        } catch (Throwable throwable) {
            candidate.transferAttempted.set(1);
            moduleLog(Log.WARN, TAG,
                    "Failed prepared-finish atomic transfer"
                            + ", exact=" + exact
                            + ", transitionDebugId="
                            + candidate.transitionDebugId
                            + ", preparedDebugId="
                            + candidate.preparedDebugId,
                    throwable);
        }
        Object result = chain.proceed();
        if (transferred) {
            candidate.transferAttempted.compareAndSet(1, 2);
        }
        return result;
    }

    protected void hookBackFinishOpenAtomicTransfer(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method applyFinishOpen = handlerClass.getDeclaredMethod(
                    "applyFinishOpenTransition");
            applyFinishOpen.setAccessible(true);
            recordHookHandle(hook(applyFinishOpen)
                    .setId("systemui_back_finish_open_atomic")
                    .intercept(this::transferReturnHomeFinishIntoCloseStart));
            backFinishOpenAtomicHookReady = true;
            boolean callerDeoptimized =
                    deoptimizeBackFinishOpenCaller(classLoader);
            moduleLog(isReturnHomeFinishTransferReady() ? Log.INFO : Log.WARN, TAG,
                    "Hooked Shell prepared-finish atomic transfer"
                            + ", outerHook="
                            + backCommitCompositionHookReady
                            + ", nestedHook="
                            + backFinishOpenAtomicHookReady
                            + ", mergeCallerDeoptimized="
                            + callerDeoptimized
                            + ", ready="
                            + isReturnHomeFinishTransferReady());
        } catch (Throwable throwable) {
            backFinishOpenAtomicHookReady = false;
            backFinishOpenCallerDeoptimized = false;
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Shell prepared-finish atomic transfer",
                    throwable);
        }
    }

    protected boolean deoptimizeBackFinishOpenCaller(ClassLoader classLoader) {
        backFinishOpenCallerDeoptimized = false;
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method mergeAnimation = requireBackMergeAnimation(handlerClass);
            backFinishOpenCallerDeoptimized = deoptimize(mergeAnimation);
            moduleLog(backFinishOpenCallerDeoptimized ? Log.INFO : Log.WARN,
                    TAG, "Deoptimized exact BackTransitionHandler.mergeAnimation"
                            + ", success="
                            + backFinishOpenCallerDeoptimized);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to deoptimize BackTransitionHandler.mergeAnimation"
                            + " for finish/start atomicity",
                    throwable);
        }
        return backFinishOpenCallerDeoptimized;
    }

    protected boolean isReturnHomeFinishTransferReady() {
        return backCommitCompositionHookReady
                && backFinishOpenAtomicHookReady
                && backFinishOpenCallerDeoptimized;
    }

    protected ReturnHomeComposition resolveReturnHomeComposition(Object apps)
            throws Exception {
        if (apps == null || !apps.getClass().isArray()
                || Array.getLength(apps) != 2) {
            return null;
        }
        Object closingTarget = null;
        Object openingTarget = null;
        for (int i = 0; i < 2; i++) {
            Object target = Array.get(apps, i);
            int mode = readIntFieldOrDefault(target, "mode", -1);
            if (mode == 1 && closingTarget == null) {
                closingTarget = target;
            } else if (mode == 0 && openingTarget == null) {
                openingTarget = target;
            } else {
                return null;
            }
        }
        if (closingTarget == null || openingTarget == null
                || Boolean.TRUE.equals(readField(closingTarget, "isElement"))
                || Boolean.TRUE.equals(readField(openingTarget, "isElement"))
                || resolveRemoteTargetActivityType(closingTarget)
                != ACTIVITY_TYPE_STANDARD
                || resolveRemoteTargetActivityType(openingTarget)
                != ACTIVITY_TYPE_HOME
                || resolveRemoteTargetWindowingMode(closingTarget)
                != WINDOWING_MODE_FULLSCREEN
                || resolveRemoteTargetWindowingMode(openingTarget)
                != WINDOWING_MODE_FULLSCREEN) {
            return null;
        }
        int closingTaskId = readIntFieldOrDefault(
                closingTarget, "taskId", -1);
        int openingTaskId = readIntFieldOrDefault(
                openingTarget, "taskId", -1);
        Object closingTaskInfo = readField(closingTarget, "taskInfo");
        Object openingTaskInfo = readField(openingTarget, "taskInfo");
        int closingDisplayId = readIntFieldOrDefault(
                closingTaskInfo, "displayId", -1);
        int openingDisplayId = readIntFieldOrDefault(
                openingTaskInfo, "displayId", -1);
        Object closingLeashObject = readField(closingTarget, "leash");
        Object openingLeashObject = readField(openingTarget, "leash");
        if (closingTaskId < 0 || openingTaskId < 0
                || closingTaskId == openingTaskId
                || closingDisplayId < 0 || closingDisplayId != openingDisplayId
                || readIntFieldOrDefault(closingTaskInfo, "taskId", -1)
                != closingTaskId
                || readIntFieldOrDefault(openingTaskInfo, "taskId", -1)
                != openingTaskId
                || !(closingLeashObject instanceof SurfaceControl)
                || !(openingLeashObject instanceof SurfaceControl)) {
            return null;
        }
        SurfaceControl closingLeash = (SurfaceControl) closingLeashObject;
        SurfaceControl openingLeash = (SurfaceControl) openingLeashObject;
        if (!closingLeash.isValid() || !openingLeash.isValid()
                || surfacesAreSame(closingLeash, openingLeash)) {
            return null;
        }
        return new ReturnHomeComposition(apps, closingTarget, openingTarget,
                closingLeash, openingLeash, closingTaskId, openingTaskId,
                closingDisplayId);
    }

    protected int resolveRemoteTargetActivityType(Object target) throws Exception {
        Object windowConfiguration = readField(target, "windowConfiguration");
        Object activityType = invokeAnyMethod(
                windowConfiguration, "getActivityType", new Object[0]);
        return activityType instanceof Number
                ? ((Number) activityType).intValue() : -1;
    }

    protected int resolveRemoteTargetWindowingMode(Object target) throws Exception {
        Object windowConfiguration = readField(target, "windowConfiguration");
        Object windowingMode = invokeAnyMethod(
                windowConfiguration, "getWindowingMode", new Object[0]);
        return windowingMode instanceof Number
                ? ((Number) windowingMode).intValue() : -1;
    }

    protected void hookShellAnimationFinished(Class<?> controllerClass, String methodName,
                                              String hookId, boolean optional)
            throws NoSuchMethodException {
        try {
            Method method = controllerClass.getDeclaredMethod(methodName);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId(hookId)
                    .intercept("finishBackAnimation".equals(methodName)
                            ? this::onShellAnimationFinished
                            : this::proceedShellAnimationLifecycle));
        } catch (NoSuchMethodException exception) {
            if (!optional) {
                throw exception;
            }
            moduleLog(Log.INFO, TAG, "Optional Shell method unavailable: " + methodName);
        }
    }

    protected void hookBackNavigationInfoReceived(Class<?> controllerClass)
            throws NoSuchMethodException {
        Method method = controllerClass.getDeclaredMethod(
                "onBackNavigationInfoReceived",
                BackNavigationInfo.class, BackTouchTracker.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("shell_back_onBackNavigationInfoReceived")
                .intercept(this::onBackNavigationInfoReceived));
    }


    protected Object onBackNavigationInfoReceived(XposedInterface.Chain chain)
            throws Throwable {
        ensureAospBackAnimations(chain.getThisObject(), "beforeNavigationInfo");
        forceSystemUiCallbackProgress(chain.getArg(0));
        Object result = chain.proceed();
        logBackNavigationInfo(chain.getArg(0));
        return result;
    }

    protected Object proceedShellAnimationLifecycle(
            XposedInterface.Chain chain) throws Throwable {
        return chain.proceed();
    }

    protected Object onShellAnimationFinished(XposedInterface.Chain chain) throws Throwable {
        Object controller = chain.getThisObject();
        if (preparedBackTransitionHold.get() == null) {
            preparedBackTargetArrival.set(null);
        }
        List<Runnable> completions = new ArrayList<>();
        try {
            Object currentTracker = readField(controller, "mCurrentTracker");
            Object queuedTracker = readField(controller, "mQueuedTracker");
            Object navigation = readField(controller, "mBackNavigationInfo");
            Object transitionHandler = readField(controller,
                    "mBackTransitionHandler");
            Object finishCallback = readField(transitionHandler,
                    "mOnAnimationFinishCallback");
            for (NativeBackInputMonitor monitor
                    : new ArrayList<>(nativeInputMonitors.values())) {
                Runnable completion = monitor.captureShellAnimationCompletion(
                        controller, currentTracker, queuedTracker,
                        navigation, finishCallback,
                        chain.getExecutable().getName());
                if (completion != null) {
                    completions.add(completion);
                }
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to capture fixed Shell completion identity",
                    throwable);
        }
        Object result = chain.proceed();
        if ("finishBackAnimation".equals(chain.getExecutable().getName())) {
            try {
                Object transitionHandler = readField(controller,
                        "mBackTransitionHandler");
                moduleLog(Log.INFO, TAG, "Completed stock Shell back-animation cleanup"
                        + ", postCommit=" + readField(controller,
                        "mPostCommitAnimationInProgress")
                        + ", navigation=" + shortObject(readField(controller,
                        "mBackNavigationInfo"))
                        + ", finishedCallback=" + shortObject(readField(controller,
                        "mBackAnimationFinishedCallback"))
                        + ", currentTracker=" + shortObject(readField(controller,
                        "mCurrentTracker"))
                        + ", queuedTracker=" + shortObject(readField(controller,
                        "mQueuedTracker"))
                        + ", closeRequested=" + readField(
                        transitionHandler, "mCloseTransitionRequested")
                        + ", prepareOpen=" + shortObject(readField(
                        transitionHandler, "mPrepareOpenTransition"))
                        + ", prepareClose=" + shortObject(readField(
                        transitionHandler, "mClosePrepareTransition")));
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to inspect completed Shell back-animation cleanup",
                        throwable);
            }
        }
        for (Runnable completion : completions) {
            try {
                completion.run();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to publish fixed Shell completion",
                        throwable);
            }
        }
        return result;
    }

    protected synchronized void ensureMiuiOverviewStateReceiver(Context context) {
        if (miuiOverviewReceiver != null || context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (MODULE_RUNTIME_STATUS_QUERY.equals(action)) {
                    handleModuleRuntimeStatusQuery(receiverContext,
                            getSentFromUid(), getSentFromPackage(), intent);
                    return;
                }
                if (MODULE_RUNTIME_STATUS_REPLY.equals(action)) {
                    handleNativeRuntimeStatusReply(receiverContext,
                            getSentFromUid(), getSentFromPackage(), intent);
                    return;
                }
                if (MODULE_CONTEXTUAL_SEARCH_TRIGGERED.equals(action)) {
                    int senderUid = getSentFromUid();
                    String senderPackage = getSentFromPackage();
                    if (!isTrustedMiuiHomeBroadcastSender(
                            receiverContext, senderUid, senderPackage)) {
                        moduleLog(Log.WARN, TAG,
                                "Rejected untrusted contextual-search trigger"
                                        + ", uid=" + senderUid
                                        + ", package=" + senderPackage);
                        return;
                    }
                    playContextualSearchHaptic(receiverContext);
                    return;
                }
                if (!MODULE_MIUI_OVERVIEW_STATE_CHANGE.equals(action)
                        && !MODULE_MIUI_HOME_INPUT_ARBITER_QUERY.equals(action)) {
                    return;
                }
                int senderUid = getSentFromUid();
                String senderPackage = getSentFromPackage();
                if (!isTrustedMiuiHomeBroadcastSender(
                        receiverContext, senderUid, senderPackage)) {
                    moduleLog(Log.WARN, TAG, "Rejected untrusted Miui launcher-state broadcast"
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
                boolean carriesDartState = intent.hasExtra("drawer_visible")
                        || intent.hasExtra("overview_visible")
                        || intent.hasExtra(EXTRA_LAUNCHER_EDITING);
                if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                        && (carriesDartState
                        || intent.hasExtra(EXTRA_LAUNCHER_STATE_OWNER_EPOCH))) {
                    long ownerEpoch = intent.getLongExtra(
                            EXTRA_LAUNCHER_STATE_OWNER_EPOCH, 0L);
                    long ownerGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (ownerEpoch <= 0L
                            || ownerGeneration != systemUiInputArbiterGeneration) {
                        moduleLog(Log.WARN, TAG,
                                "Rejected invalid native launcher-state owner"
                                        + ", ownerEpoch=" + ownerEpoch
                                        + ", generation=" + ownerGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                        return;
                    }
                    if (ownerEpoch < miuiLauncherDartStateOwnerEpoch) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored retired native launcher-state owner"
                                        + ", ownerEpoch=" + ownerEpoch
                                        + ", currentOwnerEpoch="
                                        + miuiLauncherDartStateOwnerEpoch);
                        return;
                    }
                    if (ownerEpoch > miuiLauncherDartStateOwnerEpoch) {
                        miuiLauncherDartStateOwnerEpoch = ownerEpoch;
                        acceptedInputToken.set(null);
                        miuiDrawerVisible = false;
                        miuiOverviewVisible = false;
                        miuiLauncherEditing = false;
                        miuiOverviewDismissPendingUntilUptime = 0L;
                        moduleLog(Log.INFO, TAG,
                                "Adopted native launcher-state owner"
                                        + ", ownerEpoch=" + ownerEpoch
                                        + ", generation=" + ownerGeneration);
                    }
                }
                if (intent.getBooleanExtra(EXTRA_INPUT_ACCEPTED, false)) {
                    receiveMiuiHomeAcceptedInput(intent);
                }
                if (intent.hasExtra("drawer_visible")) {
                    long drawerGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            && drawerGeneration != systemUiInputArbiterGeneration) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored stale native MiuiHome drawer state"
                                        + ", generation=" + drawerGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                    } else {
                        miuiDrawerVisible = intent.getBooleanExtra(
                                "drawer_visible", false);
                        moduleLog(Log.INFO, TAG, "MiuiHome drawer state changed"
                                + ", drawerVisible=" + miuiDrawerVisible
                                + ", generation=" + drawerGeneration
                                + ", uid=" + senderUid
                                + ", package=" + senderPackage);
                    }
                }
                if (intent.hasExtra(EXTRA_LAUNCHER_XIAOAI_VISIBLE)) {
                    long xiaoAiGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            && xiaoAiGeneration
                            != systemUiInputArbiterGeneration) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored stale native MiuiHome XiaoAi state"
                                        + ", generation=" + xiaoAiGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                    } else {
                        miuiLauncherXiaoAiVisible = intent.getBooleanExtra(
                                EXTRA_LAUNCHER_XIAOAI_VISIBLE, false);
                        moduleLog(Log.INFO, TAG,
                                "MiuiHome XiaoAi overlay state changed"
                                        + ", visible="
                                        + miuiLauncherXiaoAiVisible
                                        + ", generation=" + xiaoAiGeneration
                                        + ", uid=" + senderUid
                                        + ", package=" + senderPackage);
                    }
                }
                if (intent.hasExtra(EXTRA_LAUNCHER_FOLDER_VISIBLE)) {
                    miuiFolderVisible = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_FOLDER_VISIBLE, false);
                    moduleLog(Log.INFO, TAG, "MiuiHome folder state changed"
                            + ", visible=" + miuiFolderVisible
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                }
                if (intent.hasExtra(EXTRA_LAUNCHER_EDITING)) {
                    long editingGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            && (editingGeneration <= 0L
                            || editingGeneration
                            != systemUiInputArbiterGeneration)) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored stale native MiuiHome editing state"
                                        + ", generation=" + editingGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                    } else {
                        miuiLauncherEditing = intent.getBooleanExtra(
                                EXTRA_LAUNCHER_EDITING, false);
                        moduleLog(Log.INFO, TAG,
                                "MiuiHome editing state changed"
                                        + ", editing=" + miuiLauncherEditing
                                        + ", generation=" + editingGeneration
                                        + ", uid=" + senderUid
                                        + ", package=" + senderPackage);
                    }
                }
                if (intent.hasExtra(EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE)
                        && intent.hasExtra(EXTRA_LAUNCHER_OPEN_ACTIVE)) {
                    long generation = intent.getLongExtra(
                            EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, 0L);
                    boolean active = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_OPEN_ACTIVE, false);
                    boolean available = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE, false);
                    if (generation == 0L
                            || generation < miuiLauncherOpenBreakGeneration) {
                        moduleLog(Log.WARN, TAG, "Ignored stale MiuiHome launcher OPEN break state"
                                + ", active=" + active
                                + ", available=" + available
                                + ", generation=" + generation
                                + ", currentGeneration="
                                + miuiLauncherOpenBreakGeneration);
                    } else {
                        long previousGeneration = miuiLauncherOpenBreakGeneration;
                        boolean previousActive = miuiLauncherOpenActive;
                        miuiLauncherOpenBreakGeneration = generation;
                        miuiLauncherOpenActive = active;
                        miuiLauncherOpenBreakAvailable = available;
                        moduleLog(Log.INFO, TAG, "MiuiHome launcher OPEN break state changed"
                                + ", active=" + active
                                + ", available=" + available
                                + ", generation=" + generation
                                + ", uid=" + senderUid
                                + ", package=" + senderPackage);
                        if (!active && previousActive
                                && previousGeneration == generation) {
                            for (NativeBackInputMonitor monitor
                                    : new ArrayList<>(nativeInputMonitors.values())) {
                                monitor.driver.onLauncherOpenEnded(generation);
                            }
                        }
                    }
                } else if (intent.hasExtra(
                        EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE)) {
                    moduleLog(Log.WARN, TAG,
                            "Ignored launcher OPEN state without active lifecycle");
                }
                String state = intent == null ? null : intent.getStringExtra("state");
                boolean overviewVisible;
                String source;
                if (intent.hasExtra("overview_visible")) {
                    long overviewGeneration = intent.getLongExtra(
                            EXTRA_INPUT_ARBITER_GENERATION, 0L);
                    if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            && overviewGeneration
                            != systemUiInputArbiterGeneration) {
                        moduleLog(Log.WARN, TAG,
                                "Ignored stale native MiuiHome Overview state"
                                        + ", generation=" + overviewGeneration
                                        + ", currentGeneration="
                                        + systemUiInputArbiterGeneration);
                        return;
                    }
                    overviewVisible = intent.getBooleanExtra("overview_visible", false);
                    if (!overviewVisible
                            && intent.getBooleanExtra("task_launch_started", false)) {
                        beginMiuiOverviewDismiss("taskLaunch");
                        return;
                    }
                    state = overviewVisible ? "actualRecentsEnter" : "actualRecentsExit";
                    source = Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                            ? "nativeOverview" : "RecentsContainer";
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
            filter.addAction(MODULE_CONTEXTUAL_SEARCH_TRIGGERED);
            filter.addAction(MODULE_RUNTIME_STATUS_QUERY);
            filter.addAction(MODULE_RUNTIME_STATUS_REPLY);
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            miuiOverviewReceiverContext = appContext;
            miuiOverviewReceiver = receiver;
            ensureContextualSearchStatePreferenceListener(appContext);
            moduleLog(Log.INFO, TAG, "Registered Miui launcher overview-state receiver"
                    + ", currentOverviewVisible=" + miuiOverviewVisible);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to register Miui overview-state receiver",
                    throwable);
        }
    }

    /**
     * Ports Samsung's Android 16 CrossTask geometry onto Xiaomi's existing Shell runner.
     * The callback and finish hooks are deliberately separate from the global
     * BackProgressAnimator hook: the latter only proxies the exact animator owned by the
     * current registry animation, while Xiaomi still owns target arrival, cancellation,
     * remote completion, and cleanup on both Android 16 and Android 17.
     */
    protected void hookOneUiCrossTaskAnimation(ClassLoader classLoader,
                                               boolean installInvoke,
                                               boolean installFinish) {
        try {
            Class<?> animationClass = Class.forName(
                    CROSS_TASK_BACK_ANIMATION, false, classLoader);
            if (installInvoke) {
                Class<?> callbackClass = null;
                try {
                    callbackClass = Class.forName(
                            CROSS_TASK_BACK_ANIMATION + "$Callback", false, classLoader);
                } catch (Throwable ignored) {
                    for (Class<?> candidate : animationClass.getDeclaredClasses()) {
                        if (findAnyMethod(candidate, "onBackInvoked", 0) != null) {
                            if (callbackClass != null) {
                                throw new NoSuchMethodException(
                                        "Ambiguous CrossTask onBackInvoked callback");
                            }
                            callbackClass = candidate;
                        }
                    }
                }
                if (callbackClass == null) {
                    throw new ClassNotFoundException("CrossTask callback");
                }
                Method invoked = callbackClass.getDeclaredMethod("onBackInvoked");
                invoked.setAccessible(true);
                recordHookHandle(hook(invoked)
                        .setId("systemui_oneui_cross_task_invoke")
                        .intercept(this::onOneUiCrossTaskInvoked));
            }
            if (installFinish) {
                Method finish = null;
                for (Method candidate : animationClass.getDeclaredMethods()) {
                    if (candidate.getParameterCount() == 0
                            && candidate.getReturnType() == void.class
                            && candidate.getName().startsWith("finishAnimation")) {
                        if (finish != null) {
                            throw new NoSuchMethodException(
                                    "Ambiguous CrossTask finishAnimation");
                        }
                        finish = candidate;
                    }
                }
                if (finish == null) {
                    throw new NoSuchMethodException("CrossTask finishAnimation");
                }
                finish.setAccessible(true);
                oneUiCrossTaskFinishMethod = finish;
                recordHookHandle(hook(finish)
                        .setId("systemui_oneui_cross_task_finish")
                        .intercept(this::onOneUiCrossTaskFinished));
            }
            moduleLog(Log.INFO, TAG, "Hooked One UI CrossTask animation bridge"
                    + ", invoke=" + installInvoke + ", finish=" + installFinish
                    + ", impl=" + requireSystemUiPlatformImpl().name());
        } catch (Throwable throwable) {
            oneUiCrossTaskFinishMethod = null;
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook One UI CrossTask animation bridge", throwable);
        }
    }

    protected Context resolveCurrentApplicationContext(ClassLoader classLoader) {
        try {
            if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL) {
                return ActivityThread.currentApplication();
            }
            Class<?> activityThread = Class.forName(
                    "android.app.ActivityThread", false, classLoader);
            Method currentApplication = activityThread.getDeclaredMethod(
                    "currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to resolve SystemUI application context for status receiver",
                    throwable);
            return null;
        }
    }

    protected void handleModuleRuntimeStatusQuery(Context context, int senderUid,
                                                  String senderPackage,
                                                  Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!isTrustedModuleStatusSender(context, senderUid, senderPackage)) {
            moduleLog(Log.WARN, TAG, "Rejected untrusted runtime status query"
                    + ", uid=" + senderUid + ", package=" + senderPackage);
            return;
        }
        long nonce = intent.getLongExtra(EXTRA_STATUS_NONCE, 0L);
        if (nonce <= 0L) {
            moduleLog(Log.WARN, TAG, "Rejected runtime status query without nonce");
            return;
        }
        pendingModuleStatusNonce.set(nonce);
        boolean ready = systemUiInputArbiterMonitorCount.get() > 0;
        sendModuleRuntimeStatusReply(context, nonce, false, ready,
                "systemUiResponse", null);
        try {
            Intent nativeQuery = new Intent(systemUiInputArbiterStateAction())
                    .setPackage(MIUI_HOME)
                    .putExtra(EXTRA_STATUS_QUERY, true)
                    .putExtra(EXTRA_STATUS_NONCE, nonce)
                    .putExtra(EXTRA_INPUT_ARBITER_READY, ready)
                    .putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                            systemUiInputArbiterGeneration)
                    // The native launcher receiver consumes this marked query as
                    // an arbiter-state update too. Keep the effective runtime bit
                    // on the query so a missing boot-created service remains
                    // fail-closed after an API-102 hot upgrade.
                    .putExtra(EXTRA_CONTEXTUAL_SEARCH_ENABLED,
                            isContextualSearchLongPressRuntimeEnabled())
                    .putExtra("sender_uid", Process.myUid());
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.getApplicationContext().sendBroadcast(nativeQuery, null, options);
            moduleLog(Log.INFO, TAG, "Sent native runtime status query"
                    + ", nonce=" + nonce + ", ready=" + ready);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to send native runtime status query",
                    throwable);
        }
    }

    protected void handleNativeRuntimeStatusReply(Context context, int senderUid,
                                                  String senderPackage,
                                                  Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!isTrustedMiuiHomeBroadcastSender(context, senderUid, senderPackage)) {
            moduleLog(Log.WARN, TAG, "Rejected untrusted native runtime status reply"
                    + ", uid=" + senderUid + ", package=" + senderPackage);
            return;
        }
        long nonce = intent.getLongExtra(EXTRA_STATUS_NONCE, 0L);
        if (nonce <= 0L || pendingModuleStatusNonce.get() != nonce) {
            moduleLog(Log.WARN, TAG, "Ignored stale native runtime status reply"
                    + ", nonce=" + nonce
                    + ", pending=" + pendingModuleStatusNonce.get());
            return;
        }
        pendingModuleStatusNonce.compareAndSet(nonce, 0L);
        sendModuleRuntimeStatusReply(context, nonce, true,
                systemUiInputArbiterMonitorCount.get() > 0,
                "nativeResponse", intent);
    }

    protected void sendModuleRuntimeStatusReply(Context context, long nonce,
                                                boolean nativeResponse,
                                                boolean systemUiReady,
                                                String reason,
                                                Intent nativeReply) {
        if (context == null || nonce <= 0L) {
            return;
        }
        try {
            boolean legacyMode = Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL;
            boolean legacyReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_LEGACY_READY, false);
            boolean profileResolved = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_PROFILE_RESOLVED, false);
            boolean nativeReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_READY, false);
            int businessState = nativeReply == null ? 0 : nativeReply.getIntExtra(
                    EXTRA_STATUS_NATIVE_BUSINESS_STATE, 0);
            int bridgeState = nativeReply == null ? 0 : nativeReply.getIntExtra(
                    EXTRA_STATUS_NATIVE_BRIDGE_STATE, 0);
            int profileStage = nativeReply == null ? 0 : nativeReply.getIntExtra(
                    EXTRA_STATUS_NATIVE_RUNTIME_PROFILE_STAGE, 0);
            int dartResolverStage = nativeReply == null ? 0 : nativeReply.getIntExtra(
                    EXTRA_STATUS_NATIVE_DART_RESOLVER_STAGE, 0);
            boolean drawerStateReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_DRAWER_STATE_READY, false);
            boolean overviewStateReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_OVERVIEW_STATE_READY, false);
            boolean editingStateReady = nativeReply != null && nativeReply.getBooleanExtra(
                    EXTRA_STATUS_NATIVE_EDITING_STATE_READY, false);
            boolean profileRejected = (profileStage >= 101 && profileStage <= 105)
                    || (dartResolverStage >= 101 && dartResolverStage <= 105);
            boolean statusReady = nativeResponse && systemUiReady
                    && (legacyMode ? legacyReady
                    : !profileRejected && nativeReady && profileResolved
                    && businessState == 3 && bridgeState == 3
                    && drawerStateReady && overviewStateReady
                    && editingStateReady);
            Intent reply = new Intent(MODULE_RUNTIME_STATUS_REPLY)
                    .setPackage(MODULE_PACKAGE)
                    .putExtra(EXTRA_STATUS_NONCE, nonce)
                    .putExtra(EXTRA_STATUS_NATIVE_RESPONSE, nativeResponse)
                    .putExtra(EXTRA_STATUS_LEGACY_MODE, legacyMode)
                    .putExtra(EXTRA_STATUS_SYSTEMUI_READY, systemUiReady)
                    .putExtra(EXTRA_STATUS_SYSTEMUI_GENERATION,
                            systemUiInputArbiterGeneration)
                    .putExtra(EXTRA_STATUS_SYSTEMUI_MONITORS,
                            systemUiInputArbiterMonitorCount.get())
                    .putExtra(EXTRA_STATUS_REASON, reason);
            if (nativeReply != null) {
                reply.putExtra(EXTRA_STATUS_LEGACY_READY, legacyReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_PROFILE_RESOLVED, profileResolved);
                reply.putExtra(EXTRA_STATUS_NATIVE_READY, nativeReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_PROFILE_DYNAMIC,
                        nativeReply.getBooleanExtra(
                                EXTRA_STATUS_NATIVE_PROFILE_DYNAMIC, false));
                reply.putExtra(EXTRA_STATUS_NATIVE_PROFILE_ENTRY_OFFSET,
                        nativeReply.getLongExtra(
                                EXTRA_STATUS_NATIVE_PROFILE_ENTRY_OFFSET, 0L));
                reply.putExtra(EXTRA_STATUS_NATIVE_SIDE_OFFSET,
                        nativeReply.getLongExtra(EXTRA_STATUS_NATIVE_SIDE_OFFSET, 0L));
                reply.putExtra(EXTRA_STATUS_NATIVE_RUNTIME_PROFILE_STAGE, profileStage);
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_RESOLVER_STAGE,
                        dartResolverStage);
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_DRAWER_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_DRAWER_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_TRANSITION_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_TRANSITION_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_OVERVIEW_ENTER_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_OVERVIEW_ENTER_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_OVERVIEW_EXIT_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_OVERVIEW_EXIT_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DART_EDITING_CANDIDATES,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_DART_EDITING_CANDIDATES, 0));
                reply.putExtra(EXTRA_STATUS_NATIVE_DRAWER_STATE_READY,
                        drawerStateReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_OVERVIEW_STATE_READY,
                        overviewStateReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_EDITING_STATE_READY,
                        editingStateReady);
                reply.putExtra(EXTRA_STATUS_NATIVE_BUSINESS_STATE, businessState);
                reply.putExtra(EXTRA_STATUS_NATIVE_BRIDGE_STATE, bridgeState);
                reply.putExtra(EXTRA_STATUS_NATIVE_RECEIVER_STATE,
                        nativeReply.getIntExtra(
                                EXTRA_STATUS_NATIVE_RECEIVER_STATE, 0));
            }
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.getApplicationContext().sendBroadcast(reply, null, options);
            moduleLog(Log.INFO, TAG, "Published module runtime status reply"
                    + ", nonce=" + nonce + ", nativeResponse=" + nativeResponse
                    + ", systemUiReady=" + systemUiReady
                    + ", statusReady=" + statusReady
                    + ", profileResolved=" + profileResolved
                    + ", nativeReady=" + nativeReady
                    + ", businessState=" + businessState
                    + ", bridgeState=" + bridgeState
                    + ", profileStage=" + profileStage
                    + ", dartResolverStage=" + dartResolverStage
                    + ", drawerStateReady=" + drawerStateReady
                    + ", overviewStateReady=" + overviewStateReady
                    + ", editingStateReady=" + editingStateReady
                    + ", reason=" + reason);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to publish module runtime status reply",
                    throwable);
        }
    }

    protected void receiveMiuiHomeAcceptedInput(Intent intent) {
        long generation = intent.getLongExtra(EXTRA_INPUT_ARBITER_GENERATION, 0L);
        if (generation != systemUiInputArbiterGeneration
                || systemUiInputArbiterMonitorCount.get() <= 0) {
            moduleLog(Log.WARN, TAG, "Ignored stale MiuiHome accepted input token"
                    + ", tokenGeneration=" + generation
                    + ", currentGeneration=" + systemUiInputArbiterGeneration
                    + ", monitors=" + systemUiInputArbiterMonitorCount.get());
            return;
        }
        if (!intent.hasExtra(EXTRA_INPUT_EVENT_ID)) {
            moduleLog(Log.WARN, TAG, "Rejected MiuiHome accepted token without event id");
            return;
        }
        MiuiHomeAcceptedInputToken token = new MiuiHomeAcceptedInputToken(
                intent.getIntExtra(EXTRA_INPUT_EVENT_ID, 0),
                intent.getLongExtra(EXTRA_INPUT_DOWN_TIME, Long.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_DEVICE_ID, Integer.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_SOURCE, 0),
                intent.getIntExtra(EXTRA_INPUT_DISPLAY_ID, Integer.MIN_VALUE),
                intent.getIntExtra(EXTRA_INPUT_EDGE, -1), generation,
                intent.getLongExtra(EXTRA_LAUNCHER_STATE_OWNER_EPOCH, 0L));
        if (token.downTime == Long.MIN_VALUE
                || token.deviceId == Integer.MIN_VALUE
                || token.displayId == Integer.MIN_VALUE
                || (token.edge != EDGE_LEFT && token.edge != EDGE_RIGHT)) {
            moduleLog(Log.WARN, TAG, "Rejected malformed MiuiHome accepted input token"
                    + ", eventId=" + token.eventId
                    + ", downTime=" + token.downTime
                    + ", deviceId=" + token.deviceId
                    + ", displayId=" + token.displayId
                    + ", edge=" + token.edge);
            return;
        }
        acceptedInputToken.set(token);
        boolean consumed = new ArrayList<>(nativeInputMonitors.values()).stream()
                .anyMatch(monitor -> monitor.acceptMiuiHomeInput(token));
        if (consumed) {
            acceptedInputToken.compareAndSet(token, null);
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> acceptedInputToken.compareAndSet(token, null),
                    INPUT_ACCEPTED_TOKEN_TIMEOUT_MS);
        }
        moduleLog(Log.INFO, TAG, "Received MiuiHome accepted input token"
                + ", eventId=" + token.eventId
                + ", downTime=" + token.downTime
                + ", displayId=" + token.displayId
                + ", edge=" + token.edge
                + ", matchedPendingDown=" + consumed
                + ", generation=" + generation);
    }

    protected boolean isTrustedMiuiHomeBroadcastSender(Context context, int uid,
                                                       String senderPackage) {
        if (context == null || uid == Process.INVALID_UID
                || !MIUI_HOME.equals(senderPackage)) {
            return false;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains(MIUI_HOME);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to validate launcher-state sender uid=" + uid,
                    throwable);
        }
        return false;
    }

    protected boolean isTrustedModuleStatusSender(Context context, int uid,
                                                  String senderPackage) {
        if (context == null || uid == Process.INVALID_UID
                || !MODULE_PACKAGE.equals(senderPackage)) {
            return false;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains(MODULE_PACKAGE);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to validate module status sender uid=" + uid,
                    throwable);
        }
        return false;
    }

    protected synchronized void unregisterMiuiOverviewStateReceiver() {
        releaseContextualSearchStatePreferenceListener();
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
            moduleLog(Log.INFO, TAG, "Unregistered Miui launcher overview-state receiver");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to unregister Miui overview-state receiver",
                    throwable);
        }
    }

    protected synchronized void updateMiuiOverviewState(boolean overviewVisible,
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
                moduleLog(Log.INFO, TAG, "Confirmed new Miui Recents entry during dismiss pending"
                        + ", state=" + state
                        + ", source=" + source
                        + ", clearedPendingForMs=" + (pendingUntil - now)
                        + ", overviewVisible=true");
                return;
            }
            moduleLog(Log.INFO, TAG, "Ignored Miui Recents enter while dismiss is pending"
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
            moduleLog(Log.INFO, TAG, "Confirmed Miui Recents dismiss"
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
        moduleLog(Log.INFO, TAG, "Miui launcher state changed"
                + ", state=" + state
                + ", source=" + source
                + ", overviewVisible=" + overviewVisible);
    }

    protected synchronized void beginMiuiOverviewDismiss(String reason) {
        long pendingUntil = SystemClock.uptimeMillis() + MIUI_OVERVIEW_DISMISS_TIMEOUT_MS;
        miuiOverviewDismissPendingUntilUptime = pendingUntil;
        miuiOverviewVisible = false;
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> restoreMiuiOverviewAfterDismissTimeout(pendingUntil),
                MIUI_OVERVIEW_DISMISS_TIMEOUT_MS);
        moduleLog(Log.INFO, TAG, "Started Miui Recents dismiss pending"
                + ", reason=" + reason
                + ", timeoutMs=" + MIUI_OVERVIEW_DISMISS_TIMEOUT_MS
                + ", overviewVisible=false");
    }

    protected synchronized void restoreMiuiOverviewAfterDismissTimeout(long pendingUntil) {
        if (miuiOverviewDismissPendingUntilUptime != pendingUntil) {
            return;
        }
        miuiOverviewDismissPendingUntilUptime = 0L;
        miuiOverviewVisible = true;
        moduleLog(Log.WARN, TAG, "Miui Recents dismiss confirmation timed out"
                + ", restoredOverviewVisible=true");
    }

    protected void installBackInputDriver(Object edgeBackGestureHandler, Object backAnimationImpl) {
        if (!acceptingBackInputInstalls) {
            return;
        }
        try {
            if (edgeBackGestureHandler == null || backAnimationImpl == null) {
                return;
            }
            Object controller = readField(backAnimationImpl, "this$0");
            Context context = (Context) readField(edgeBackGestureHandler, "mContext");
            ensureMiuiOverviewStateReceiver(context);
            ensureNativeEdgeBackPlugin(edgeBackGestureHandler, context);
            NativeBackInputMonitor existing = nativeInputMonitors.get(edgeBackGestureHandler);
            if (existing != null) {
                existing.updateBackAnimation(backAnimationImpl);
                moduleLog(Log.INFO, TAG, "Updated native SystemUI back input monitor"
                        + ", controller=" + shortObject(controller));
                return;
            }
            NativeBackInputMonitor monitor = createNativeBackInputMonitor(context,
                    edgeBackGestureHandler, controller, backAnimationImpl);
            boolean published;
            synchronized (backInputLifecycleLock) {
                published = acceptingBackInputInstalls;
                if (published) {
                    nativeInputMonitors.put(edgeBackGestureHandler, monitor);
                    try {
                        monitor.attach();
                    } catch (Throwable throwable) {
                        if (nativeInputMonitors.get(edgeBackGestureHandler) == monitor) {
                            nativeInputMonitors.remove(edgeBackGestureHandler);
                        }
                        try {
                            monitor.detach();
                        } catch (Throwable cleanupFailure) {
                            throwable.addSuppressed(cleanupFailure);
                        }
                        throw throwable;
                    }
                }
            }
            if (!published) {
                monitor.detach();
                // Keep the status/overview receiver alive even when the input monitor could
                // not be published.  It must be able to report SystemUI-not-ready and allow
                // a later status query to observe a repaired monitor without depending on a
                // second receiver registration.
                return;
            }
            moduleLog(Log.INFO, TAG, "Installed native SystemUI back input monitor"
                    + ", controller=" + shortObject(controller));
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to install SystemUI back input driver", throwable);
        }
    }

    protected void ensureBackInputInstalledFromHandler(Object edgeBackGestureHandler,
                                                       String reason) {
        if (!acceptingBackInputInstalls || edgeBackGestureHandler == null) {
            return;
        }
        try {
            if (nativeInputMonitors.containsKey(edgeBackGestureHandler)) {
                return;
            }
            Object backAnimation = readField(edgeBackGestureHandler, "mBackAnimation");
            if (backAnimation == null) {
                moduleLog(Log.INFO, TAG, "Cannot restore back input from handler yet"
                        + ", reason=" + reason + ", mBackAnimation=null");
                return;
            }
            installBackInputDriver(edgeBackGestureHandler, backAnimation);
            moduleLog(Log.INFO, TAG, "Restored back input from existing EdgeBackGestureHandler"
                    + ", reason=" + reason);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to restore back input from handler"
                    + ", reason=" + reason, throwable);
        }
    }

    protected void ensureNativeEdgeBackPlugin(Object edgeBackGestureHandler, Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(
                    () -> ensureNativeEdgeBackPlugin(edgeBackGestureHandler, context));
            return;
        }
        try {
            Object plugin = requireSystemUiPlatformImpl().ensureNativeEdgeBackPlugin(
                    edgeBackGestureHandler, context);
            if (plugin != null) {
                moduleLog(Log.INFO, TAG, "Installed native AOSP NavigationEdgeBackPlugin: "
                        + shortObject(plugin) + ", impl="
                        + requireSystemUiPlatformImpl().name());
                return;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to create native AOSP edge back plugin", throwable);
        }
        logNativePluginDiagnostics(edgeBackGestureHandler);
    }

    protected void ensureAospBackAnimations(Object controller, String source) {
        if (controller == null) {
            return;
        }
        try {
            Object registry = readField(controller, "mShellBackAnimationRegistry");
            ensureAospRegistryDefinitions(registry, source);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to ensure AOSP back animations from " + source,
                    throwable);
        }
    }
}
