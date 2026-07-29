package dev.codex.miuibackgesturehook.hooks.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackProgressAnimator;
import android.window.BackTouchTracker;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
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

import io.github.libxposed.api.XposedInterface;

public abstract class SystemUiHookRuntime extends SystemUiInputRuntime {


    protected void installSystemUiHooks(ClassLoader classLoader) {
        try {
            hookMiuiOverviewProxy(classLoader);
            hookNavigationBarTransientAutoHide(classLoader);
            hookNavigationBarTransientAppearance(classLoader);
            hookStatusBarTransientAppearance(classLoader);
            hookNavigationBarGestureInsets(classLoader);
            hookEdgeBackGestureHandler(classLoader, true, true, true);
            hookNavigationBarControllerCreate(classLoader);
            hookNavigationBarControllerRemove(classLoader);
            hookNavigationBarControllerMode(classLoader);
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

    protected void hookMiuiOverviewProxy(ClassLoader classLoader) {
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

    protected void hookDefaultTransitionHandler(ClassLoader classLoader) {
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
    protected synchronized void resolveDefaultTransitionSnapshotReflection(
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

    protected Object registerDefaultTransitionHandler(XposedInterface.Chain chain)
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

    protected void captureRunningOpenTransition(Object handler, Object token, Object info)
            throws Exception {
        if (handler == null || token == null || info == null) {
            return;
        }
        resolveDefaultTransitionSnapshotReflection(handler.getClass(), info.getClass());
        Object type = transitionInfoGetTypeMethod.invoke(info);
        if (!(type instanceof Number) || ((Number) type).intValue() != 1) {
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
                log(Log.INFO, TAG, "Skipped partial Xiaomi OPEN transition snapshot"
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
            log(Log.INFO, TAG, "Published reversible Xiaomi OPEN transition snapshot"
                    + ", animatorCount=" + snapshot.animators.length
                    + ", info=" + shortObject(snapshot.transitionInfo));
        } catch (Throwable throwable) {
            invalidateOpenTransitionSnapshot(snapshot, "verificationFailure");
            log(Log.WARN, TAG, "Failed to verify Xiaomi OPEN transition snapshot",
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
                + ", animatorCount=" + snapshot.animators.length);
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
        if (listener == null) {
            return;
        }
        for (Animator animator : snapshot.animators) {
            animator.removeListener(listener);
        }
        snapshot.listener = null;
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
            log(Log.INFO, TAG, "Cleared Xiaomi OPEN transition snapshots"
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
        log(Log.INFO, TAG, "Armed Xiaomi interruption BACK correlation"
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
            log(Log.INFO, TAG, "Consumed one correlated duplicate BACK pair"
                    + ", attempt=" + attempt.id);
        } else if (outcome != null) {
            log(Log.WARN, TAG, "Released Xiaomi duplicate BACK guard"
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
        log(Log.INFO, TAG, "Expired Xiaomi duplicate BACK guard"
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
            log(Log.INFO, TAG, "Cleared Xiaomi duplicate BACK guard"
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
            log(Log.INFO, TAG,
                    "Hooked NavigationBar application gesture Insets restoration");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            if (!Boolean.TRUE.equals(readField(edgeBackGestureHandler, "mInGestureNavMode"))
                    || !Boolean.TRUE.equals(readField(
                    edgeBackGestureHandler, "mIsBackGestureAllowed"))) {
                return result;
            }

            Context context = (Context) readField(navigationBar, "mContext");
            EdgeWidthSnapshot widths = readEdgeWidthSnapshot(edgeBackGestureHandler,
                    context.getResources().getDisplayMetrics().density);
            Class<?> overrideClass = Class.forName(
                    "android.view.InsetsFrameProvider$InsetsSizeOverride", false,
                    navigationBar.getClass().getClassLoader());
            Constructor<?> overrideConstructor = overrideClass.getDeclaredConstructor(
                    int.class, Insets.class);
            overrideConstructor.setAccessible(true);
            Object imeOverride = overrideConstructor.newInstance(
                    WindowManager.LayoutParams.TYPE_INPUT_METHOD, Insets.NONE);
            Object imeOverrides = Array.newInstance(overrideClass, 1);
            Array.set(imeOverrides, 0, imeOverride);

            Object providers = readField(result, "providedInsets");
            if (providers == null || !providers.getClass().isArray()) {
                return result;
            }
            int restored = 0;
            int systemGestureType = WindowInsets.Type.systemGestures();
            for (int i = 0; i < Array.getLength(providers); i++) {
                Object provider = Array.get(providers, i);
                Object type = provider == null ? null
                        : invokeAnyMethod(provider, "getType", new Object[0]);
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
                    size = Insets.of(widths.leftSensitivity, 0, 0, 0);
                } else if (providerIndex == 1) {
                    size = Insets.of(0, 0, widths.rightSensitivity, 0);
                } else {
                    continue;
                }
                // WMS also applies the cutout-safe minimum to overridden frames, so keep it zero.
                invokeAnyMethod(provider, "setInsetsSizeOverrides",
                        new Object[]{imeOverrides});
                invokeAnyMethod(provider, "setMinimalInsetsSizeInDisplayCutoutSafe",
                        new Object[]{Insets.NONE});
                invokeAnyMethod(provider, "setInsetsSize", new Object[]{size});
                restored++;
            }
            log(restored == 2 ? Log.INFO : Log.WARN, TAG,
                    "Restored application system-gesture Insets with zero IME override"
                            + ", left=" + widths.leftSensitivity
                            + ", right=" + widths.rightSensitivity
                            + ", providers=" + restored);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            log(Log.INFO, TAG, "Hooked NavigationBar.showTransient auto-hide preservation");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            log(Log.WARN, TAG, "Cannot snapshot transient NavigationBar state", throwable);
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
                log(Log.WARN, TAG,
                        "Transparent transient NavigationBar has no AutoHideController");
                return result;
            }
            invokeAnyMethod(autoHideController, "touchAutoHide", new Object[0]);
            log(Log.INFO, TAG,
                    "Preserved native transient-bar auto-hide with unchanged transparent mode");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
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
            log(Log.INFO, TAG, "Hooked NavBarHelper.transitionMode transient appearance");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            log(Log.INFO, TAG, "Hooked status-bar transient appearance reducer");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            log(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.createNavigationBar"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook NavigationBarControllerImpl.createNavigationBar",
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
            log(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.removeNavigationBar"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook NavigationBarControllerImpl.removeNavigationBar",
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
            log(Log.INFO, TAG, "Hooked NavigationBarControllerImpl.onNavigationModeChanged"
                    + " for headless lifecycle ownership");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to hook NavigationBarControllerImpl.onNavigationModeChanged",
                    throwable);
        }
    }

    protected Object reconcileAfterNavigationBarCreate(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object display = chain.getArg(0);
        try {
            Object displayId = display == null ? null
                    : invokeAnyMethod(display, "getDisplayId", new Object[0]);
            if (displayId instanceof Number
                    && ((Number) displayId).intValue() == 0) {
                scheduleHeadlessNavBarReconcile(chain.getThisObject(),
                        "createNavigationBar");
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to identify created NavigationBar display",
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
                Object result = invokeAnyMethod(controller,
                        "shouldCreateNavBarAndTaskBar",
                        new Object[]{Integer.valueOf(displayId)});
                systemHasNavigationBar = Boolean.TRUE.equals(result);
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
                log(Log.WARN, TAG, "Headless NavBar updater disappeared"
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
                    log(Log.INFO, TAG, "Updated headless EdgeBackGestureHandler mode"
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
            log(Log.ERROR, TAG, "Failed to reconcile headless NavigationBar lifecycle"
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
            log(Log.WARN, TAG,
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
            log(Log.WARN, TAG, "Failed to resolve Xiaomi flip tiny-screen state;"
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
                "setBackAnimation", backAnimation.getClass());
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
            log(Log.INFO, TAG, "Attached headless SystemUI NavigationBar lifecycle"
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
            log(Log.INFO, TAG, "Detached headless SystemUI NavigationBar lifecycle"
                    + ", controller=" + shortObject(lease.controller)
                    + ", reason=" + reason);
            return true;
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to detach headless NavigationBar lifecycle"
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
                log(Log.ERROR, TAG, "Timed out detaching headless NavBar lease"
                        + " on the SystemUI main Looper");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log(Log.ERROR, TAG, "Interrupted detaching headless NavBar lease",
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
            log(Log.WARN, TAG, "Removed residual pre-reload headless NavBar updater"
                    + ", helper=" + shortObject(navBarHelper));
            return true;
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to remove residual pre-reload headless NavBar updater",
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
            log(Log.WARN, TAG, "Adopted residual pre-reload headless NavBar updater"
                    + " for deferred exact cleanup");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to adopt residual headless NavBar updater",
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
            log(Log.INFO, TAG, "Restored SystemUI hot reload lifecycle on main thread"
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
                log(Log.INFO, TAG, "SystemUI Dependency is not initialized;"
                        + " NavigationBar hooks will capture the controller later");
                return null;
            }
            Object lazyController = readField(dependency, "mNavigationBarController");
            Object controller = invokeAnyMethod(lazyController, "get", new Object[0]);
            if (controller == null
                    || !NAVIGATION_BAR_CONTROLLER_IMPL.equals(
                    controller.getClass().getName())) {
                log(Log.WARN, TAG, "Unexpected NavigationBarController dependency="
                        + shortObject(controller));
                return null;
            }
            return controller;
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to backfill NavigationBarController from Dependency",
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
            log(Log.ERROR, TAG, "Failed to resolve EdgeBackGestureHandler", throwable);
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
                log(Log.ERROR, TAG, "Failed to hook EdgeBackGestureHandler.updateIsEnabled",
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
                log(Log.ERROR, TAG,
                        "Failed to hook EdgeBackGestureHandler.onNavigationModeChanged",
                        throwable);
            }
        }
        if (hookSetBackAnimation) {
            try {
                Method setBackAnimation = handlerClass.getDeclaredMethod("setBackAnimation",
                        Class.forName(BACK_ANIMATION_CONTROLLER + "$BackAnimationImpl",
                                false, classLoader));
                setBackAnimation.setAccessible(true);
                recordHookHandle(hook(setBackAnimation)
                        .setId("systemui_edge_back_setBackAnimation")
                        .intercept(this::onEdgeBackSetBackAnimation));
                installed++;
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to hook EdgeBackGestureHandler.setBackAnimation",
                        throwable);
            }
        }
        log(Log.INFO, TAG, "Hooked EdgeBackGestureHandler AOSP path, installed=" + installed);
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
            hookCrossTaskBackground(classLoader);
            log(Log.INFO, TAG, "Hooked Shell BackAnimationController AOSP path");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook Shell back animation", throwable);
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
            log(Log.INFO, TAG,
                    "Hooked prepared-back remote-target arrival handoff");
        } catch (Throwable throwable) {
            preparedBackTargetArrivalHookReady = false;
            log(Log.ERROR, TAG,
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
            log(Log.INFO, TAG,
                    "Hooked prepared-back terminal handoff");
        } catch (Throwable throwable) {
            preparedBackTerminalHookReady = false;
            log(Log.ERROR, TAG,
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
        try {
            controller = readField(chain.getThisObject(), "this$0");
            apps = chain.getArg(0);
            token = chain.getArg(1);
            finishedCallback = chain.getArg(2);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed to capture prepared-back target arrival",
                    throwable);
        }
        Object result = chain.proceed();
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
            log(Log.WARN, TAG,
                    "Failed to authenticate prepared-back terminal",
                    throwable);
        }
        Object result = chain.proceed();
        if (exactTerminal) {
            schedulePreparedBackTransitionResume(hold, null, true);
        }
        return result;
    }

    protected boolean isExactFreeformPreparedBackTransition(
            Object handler, Object navigation, Object info) throws Exception {
        Object focusedTaskIdObject = invokeAnyMethod(
                navigation, "getFocusedTaskId", new Object[0]);
        int focusedTaskId = focusedTaskIdObject instanceof Number
                ? ((Number) focusedTaskIdObject).intValue() : -1;
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
        if (resolveTaskInfoWindowingMode(taskInfo)
                != WINDOWING_MODE_FREEFORM) {
            return false;
        }
        int displayId = readIntFieldOrDefault(taskInfo, "displayId", -1);
        Object configuration = readField(taskInfo, "configuration");
        Object windowConfiguration = readField(
                configuration, "windowConfiguration");
        Object taskBoundsObject = invokeAnyMethod(
                windowConfiguration, "getBounds", new Object[0]);
        Object changesObject = invokeAnyMethod(
                info, "getChanges", new Object[0]);
        Object rootCountObject = invokeAnyMethod(
                info, "getRootCount", new Object[0]);
        if (displayId < 0 || !(taskBoundsObject instanceof Rect)
                || ((Rect) taskBoundsObject).isEmpty()
                || !(changesObject instanceof List<?>)
                || ((List<?>) changesObject).size() != 2
                || !(rootCountObject instanceof Number)
                || ((Number) rootCountObject).intValue() != 1) {
            throw new IllegalStateException(
                    "freeform prepared task geometry unavailable"
                            + ", taskId=" + focusedTaskId
                            + ", displayId=" + displayId
                            + ", bounds=" + shortObject(taskBoundsObject)
                            + ", changes=" + shortObject(changesObject)
                            + ", roots=" + shortObject(rootCountObject));
        }
        Rect taskBounds = (Rect) taskBoundsObject;
        Object root = invokeAnyMethod(
                info, "getRoot", new Object[]{Integer.valueOf(0)});
        Object rootLeashObject = root == null ? null : invokeAnyMethod(
                root, "getLeash", new Object[0]);
        Object rootOffsetObject = root == null ? null : invokeAnyMethod(
                root, "getOffset", new Object[0]);
        if (!(rootLeashObject instanceof SurfaceControl)
                || !((SurfaceControl) rootLeashObject).isValid()
                || !(rootOffsetObject instanceof Point)
                || ((Point) rootOffsetObject).x != taskBounds.left
                || ((Point) rootOffsetObject).y != taskBounds.top) {
            throw new IllegalStateException(
                    "freeform prepared root mismatch"
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
            Object modeObject = invokeAnyMethod(
                    change, "getMode", new Object[0]);
            Object flagsObject = invokeAnyMethod(
                    change, "getFlags", new Object[0]);
            Object changeTaskInfo = invokeAnyMethod(
                    change, "getTaskInfo", new Object[0]);
            Object component = invokeAnyMethod(
                    change, "getActivityComponent", new Object[0]);
            Object leashObject = invokeAnyMethod(
                    change, "getLeash", new Object[0]);
            Object startBoundsObject = invokeAnyMethod(
                    change, "getStartAbsBounds", new Object[0]);
            Object endBoundsObject = invokeAnyMethod(
                    change, "getEndAbsBounds", new Object[0]);
            Object startDisplayObject = invokeAnyMethod(
                    change, "getStartDisplayId", new Object[0]);
            Object endDisplayObject = invokeAnyMethod(
                    change, "getEndDisplayId", new Object[0]);
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
                        "freeform prepared Activity change mismatch"
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
                        "freeform prepared Activity role mismatch"
                                + ", taskId=" + focusedTaskId
                                + ", changeIndex=" + changeIndex
                                + ", mode=" + mode
                                + ", flags=0x" + Integer.toHexString(flags));
            }
            changeIndex++;
        }
        return closingComponent != null && openingComponent != null
                && !closingComponent.equals(openingComponent)
                && !surfacesAreSame(closingLeash, openingLeash);
    }

    protected void hookPreparedBackTransitionDecision(ClassLoader classLoader) {
        try {
            Class<?> handlerClass = Class.forName(
                    BACK_TRANSITION_HANDLER, false, classLoader);
            Method startAnimation = requireExactDeclaredMethod(handlerClass,
                    "startAnimation", "boolean", IBinder.class.getName(),
                    "android.window.TransitionInfo",
                    SurfaceControl.Transaction.class.getName(),
                    SurfaceControl.Transaction.class.getName(),
                    "com.android.wm.shell.transition.Transitions$TransitionFinishCallback");
            preparePreparedBackStartAnimationInvoker(startAnimation);
            recordHookHandle(hook(startAnimation)
                    .setId("systemui_back_prepared_transition_decision")
                    .intercept(this::holdPreparedBackTransitionUntilTargets));
            log(Log.INFO, TAG,
                    "Hooked prepared-back transition target ordering");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            Object type = invokeAnyMethod(info, "getType", new Object[0]);
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
            Object navigationType = navigation == null ? null
                    : invokeAnyMethod(navigation, "getType", new Object[0]);
            if (!(navigationType instanceof Number)
                    || ((Number) navigationType).intValue()
                    != TYPE_CROSS_ACTIVITY) {
                return chain.proceed();
            }
            if (!isExactFreeformPreparedBackTransition(
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
            log(Log.WARN, TAG,
                    "Failed to qualify prepared-back transition hold",
                    throwable);
            return chain.proceed();
        }
        if (!preparedBackTransitionHold.compareAndSet(null, hold)) {
            return chain.proceed();
        }
        log(Log.INFO, TAG,
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
            log(Log.ERROR, TAG,
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
                log(Log.ERROR, TAG,
                        "Stock handler declined held prepared-back transition"
                                + ", transitionId=" + hold.transitionDebugId
                                + ", shellSessionId=" + hold.session.id
                                + ", event=" + event);
                return;
            }
            if (!preparedBackTransitionHold.compareAndSet(hold, null)) {
                log(Log.ERROR, TAG,
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
            log(Log.INFO, TAG,
                    "Resumed held prepared-back transition through stock handler"
                            + ", transitionId=" + hold.transitionDebugId
                            + ", shellSessionId=" + hold.session.id
                            + ", event=" + event
                            + ", apps=" + shortObject(controllerApps));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
        Object type = invokeAnyMethod(
                hold.transitionInfo, "getType", new Object[0]);
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
     * behind at alpha 0.9 -> 1 with its dim scrim tracking the drag and its corner
     * radius cleared, and the commit settles on a cubic ease-out. Exact freeform also
     * puts Xiaomi's fixed task radius on the prepared root and clears the moving page
     * radius. Targets, letterboxes, and the finish lifecycle stay native. Cross-task
     * and return-to-home are untouched. The independent apply hook adopts exact
     * freeform ColorLayers and that fixed clip into their prepared root whether or not
     * the slide preference is enabled.
     */
    protected void hookFreeformCrossActivityScrimCreation() {
        try {
            Method setHidden = requireExactDeclaredMethod(SurfaceControl.Builder.class,
                    "setHidden", SurfaceControl.Builder.class.getName(), "boolean");
            recordHookHandle(hook(setHidden)
                    .setId("systemui_back_color_root_scrim_creation")
                    .intercept(this::keepFreeformScrimHiddenUntilFirstApply));
            log(Log.INFO, TAG,
                    "Hooked freeform cross-activity scrim creation visibility");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            backMotionEventClass = Class.forName(
                    "android.window.BackMotionEvent", false, classLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Cross-activity animation classes unavailable",
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
                log(Log.INFO, TAG, "Hooked freeform color-layer root adoption");
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
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
                log(Log.INFO, TAG, "Hooked slide start as " + start.getName());
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to hook slide start", throwable);
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
                log(Log.INFO, TAG,
                        "Hooked slide progress via BackProgressAnimator registration");
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to hook slide progress", throwable);
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
                log(Log.INFO, TAG, "Hooked slide post-commit as "
                        + postCommit.getDeclaringClass().getSimpleName()
                        + "." + postCommit.getName()
                        + ", superPosition=" + miuixSlidePostCommitOnBase);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to hook slide post-commit", throwable);
            }
        }
        if (installDuration) {
            try {
                Method duration = resolveSlideMethod(defaultClass, baseClass,
                        "getPostCommitAnimationDuration", long.class);
                recordHookHandle(hook(duration)
                        .setId("systemui_back_slide_duration")
                        .intercept(this::onCrossActivitySlideDuration));
                log(Log.INFO, TAG, "Hooked slide duration as " + duration.getName());
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to hook slide duration", throwable);
            }
        }
        if (installFinish) {
            try {
                Method finish = resolveSlideMethod(defaultClass, baseClass,
                        "finishAnimation", void.class);
                recordHookHandle(hook(finish)
                        .setId("systemui_back_slide_finish")
                        .intercept(this::onCrossActivitySlideFinish));
                log(Log.INFO, TAG, "Hooked slide finish as "
                        + finish.getDeclaringClass().getSimpleName()
                        + "." + finish.getName());
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to hook slide finish", throwable);
            }
        }
    }

    protected void hookCrossTaskBackground(ClassLoader classLoader) {
        try {
            Class<?> backgroundClass = Class.forName(
                    BACK_ANIMATION_BACKGROUND, false, classLoader);
            for (Method method : backgroundClass.getDeclaredMethods()) {
                if ("ensureBackground".equals(method.getName())
                        && method.getParameterCount() == 6) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("systemui_cross_task_background")
                            .intercept(this::tintCrossTaskBackground));
                    log(Log.INFO, TAG, "Hooked cross-task background tint");
                    return;
                }
            }
            log(Log.WARN, TAG, "BackAnimationBackground.ensureBackground not found");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook cross-task background", throwable);
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
            if (color != CROSS_TASK_BACKGROUND_COLOR
                    || !isHyperOsSlideAnimationEnabled()) {
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
                log(Log.INFO, TAG, "Repainted cross-task background black");
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to tint cross-task background black", throwable);
        }
        return result;
    }

    // finishAnimation() is the animation's natural end; clear the session flag so a
    // later gesture re-arms cleanly. The original always runs.
    protected Object onCrossActivitySlideFinish(XposedInterface.Chain chain)
            throws Throwable {
        miuixSlideAnimActive = false;
        freeformColorRootCandidate.set(null);
        if (freeformColorRootAnimation == chain.getThisObject()) {
            freeformColorRootAnimation = null;
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
                log(Log.INFO, TAG, "Resolved " + name + " by signature as "
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
    protected volatile Object freeformColorRootAnimation;

    protected static final long MIUIX_SLIDE_SETTLE_DURATION_MS = 400L;
    protected static final float MIUIX_SLIDE_ENTERING_MIN_ALPHA = 0.9f;
    protected static final float MIUIX_SLIDE_PARALLAX_FRACTION = 0.25f;
    protected static final float MIUIX_SLIDE_SCRIM_OMEGA = 12.083f;
    protected static final float MIUIX_SLIDE_SCRIM_MAX_ALPHA = 0.5f;

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
        log(Log.INFO, TAG,
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
        Object rootCount = invokeAnyMethod(info, "getRootCount", new Object[0]);
        if (!(rootCount instanceof Number)
                || ((Number) rootCount).intValue() != 1) {
            return null;
        }
        Object root = invokeAnyMethod(info, "getRoot",
                new Object[]{Integer.valueOf(0)});
        Object leash = root == null ? null
                : invokeAnyMethod(root, "getLeash", new Object[0]);
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
            return chain.proceed();
        }
        Object adoptedAnimation = null;
        try {
            Object animation = chain.getThisObject();
            if (!matchesFreeformColorRootCandidate(candidate, animation)) {
                freeformColorRootCandidate.compareAndSet(candidate, null);
                log(Log.WARN, TAG,
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
                Object root = invokeAnyMethod(candidate.transitionInfo, "getRoot",
                        new Object[]{Integer.valueOf(0)});
                Object rootOffset = invokeAnyMethod(
                        root, "getOffset", new Object[0]);
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
                        surfaceTransaction.merge(donor);
                    }
                    adoptedAnimation = animation;
                }
            }
        } catch (Throwable throwable) {
            freeformColorRootCandidate.compareAndSet(candidate, null);
            log(Log.WARN, TAG,
                    "Failed freeform color-layer root adoption; leaving native layers",
                    throwable);
        }
        Object result = chain.proceed();
        if (adoptedAnimation != null) {
            freeformColorRootAnimation = adoptedAnimation;
            log(Log.INFO, TAG,
                    "Adopted freeform cross-activity color layers into prepared root"
                            + ", backgroundAlpha=0.0"
                            + ", rootCornerRadius=" + candidate.rootCornerRadius
                            + ", taskId=" + readIntFieldOrDefault(
                            candidate.closingTarget, "taskId", -1));
        }
        return result;
    }

    protected boolean matchesFreeformColorRootCandidate(
            FreeformColorRootCandidate candidate, Object animation) throws Exception {
        Object controller = readField(candidate.handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        Object navigationType = navigationInfo == null ? null
                : invokeAnyMethod(navigationInfo, "getType", new Object[0]);
        Object transitionType = invokeAnyMethod(
                candidate.transitionInfo, "getType", new Object[0]);
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
            log(Log.INFO, TAG, "Armed miuix slide back animation"
                    + ", width=" + width + ", rtl=" + rtl);
        } catch (Throwable throwable) {
            miuixSlideAnimActive = false;
            log(Log.WARN, TAG, "Failed to arm miuix slide geometry", throwable);
        }
        return result;
    }

    /**
     * Fires when any BackProgressAnimator registers its per-gesture ProgressCallback.
     * For the armed cross-activity animation's own animator, the native callback (the
     * inlined geometry) is replaced with the miuix frame driver; every other animator
     * registers untouched.
     */
    protected Object onCrossActivitySlideProgressRegistration(
            XposedInterface.Chain chain) throws Throwable {
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
            log(Log.WARN, TAG, "Failed to read progressAnimator", throwable);
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
                log(Log.WARN, TAG, "miuix slide frame failed"
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
        log(Log.INFO, TAG, "miuix slide progress callback proxied");
        return null;
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
                        log(Log.WARN, TAG, "miuix slide deferred post-commit failed",
                                throwable);
                    }
                });
                return null;
            }
            applyMiuixSlidePostCommitFrame(animation, linear);
            return null;
        } catch (Throwable throwable) {
            miuixSlideAnimActive = false;
            log(Log.WARN, TAG, "miuix slide post-commit frame failed", throwable);
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
        // Fullscreen keeps the moving top card rounded. In freeform the prepared root is
        // the fixed rounded frame, so both Activity surfaces are square internal pages.
        if (freeformColorRootAnimation == animation
                && closingLeash instanceof SurfaceControl
                && ((SurfaceControl) closingLeash).isValid()) {
            invokeMethod(transaction, "setCornerRadius",
                    new Class<?>[]{SurfaceControl.class, float.class},
                    new Object[]{closingLeash, Float.valueOf(0.0f)});
        }
        if (enteringLeash instanceof SurfaceControl
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
                    log(Log.INFO, TAG,
                            "Hooked Shell predictive prepare ownership correction");
                    return;
                }
            }
            log(Log.WARN, TAG,
                    "BackTransitionHandler.handlePrepareTransition not found");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            log(Log.INFO, TAG,
                    "Hooked Shell predictive return-home commit composition");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to hook Shell predictive return-home commit composition",
                    throwable);
        }
    }

    protected Method requireBackMergeAnimation(Class<?> handlerClass)
            throws NoSuchMethodException {
        return requireExactDeclaredMethod(handlerClass, "mergeAnimation", "void",
                IBinder.class.getName(), "android.window.TransitionInfo",
                SurfaceControl.Transaction.class.getName(),
                SurfaceControl.Transaction.class.getName(), IBinder.class.getName(),
                "com.android.wm.shell.transition.Transitions$TransitionFinishCallback");
    }

    protected void captureFreeformColorRootCandidate(Object handler,
                                                     Object transitionToken,
                                                     Object info) throws Exception {
        Object type = invokeAnyMethod(info, "getType", new Object[0]);
        if (!(type instanceof Number)
                || ((Number) type).intValue() != TRANSIT_PREDICTIVE_BACK
                || readField(handler, "mPrepareOpenTransition") != transitionToken
                || readField(handler, "mOpenTransitionInfo") != info) {
            return;
        }
        Object controller = readField(handler, "this$0");
        Object navigationInfo = readField(controller, "mBackNavigationInfo");
        Object navigationType = navigationInfo == null ? null
                : invokeAnyMethod(navigationInfo, "getType", new Object[0]);
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
        log(Log.INFO, TAG,
                "Armed freeform cross-activity color-layer root adoption"
                        + ", taskId=" + taskId
                        + ", rootCornerRadius=" + rootCornerRadius);
    }

    protected Object correctPredictiveBackPrepareReparent(
            XposedInterface.Chain chain) throws Throwable {
        freeformColorRootCandidate.set(null);
        freeformColorRootAnimation = null;
        Object result = chain.proceed();
        if (!Boolean.TRUE.equals(result)) {
            return result;
        }
        try {
            captureFreeformColorRootCandidate(
                    chain.getThisObject(), chain.getArg(0), chain.getArg(1));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed to capture freeform color-layer root candidate",
                    throwable);
        }
        try {
            Object handler = chain.getThisObject();
            Object info = chain.getArg(1);
            Object type = invokeAnyMethod(info, "getType", new Object[0]);
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
            Object navigationType = navigationInfo == null ? null
                    : invokeAnyMethod(navigationInfo, "getType", new Object[0]);
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
            invokeAnyMethod(preparedShape.appChange, "setMode",
                    new Object[]{Integer.valueOf(TRANSIT_CHANGE)});
            Object normalizedModeObject = invokeAnyMethod(
                    preparedShape.appChange, "getMode", new Object[0]);
            int normalizedMode = normalizedModeObject instanceof Number
                    ? ((Number) normalizedModeObject).intValue() : -1;
            if (normalizedMode != TRANSIT_CHANGE) {
                throw new IllegalStateException(
                        "prepared return-home role normalization was not retained"
                                + ", mode=" + normalizedMode);
            }
            log(Log.INFO, TAG,
                    "Corrected Xiaomi predictive return-home prepare role"
                            + ", taskId=" + composition.closingTaskId
                            + ", mode=" + TRANSIT_TO_FRONT + "->" + normalizedMode
                            + ", wallpaperPresent="
                            + (preparedShape.wallpaperLeash != null));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed Xiaomi predictive return-home prepare role correction",
                    throwable);
        }
        return result;
    }

    protected Object correctPredictiveBackCommitComposition(
            XposedInterface.Chain chain) throws Throwable {
        ReturnHomeCommitComposition candidate = null;
        try {
            candidate = captureReturnHomeCommitComposition(chain);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed to inspect predictive return-home commit composition",
                    throwable);
        }
        ReturnHomeFinishTransferCandidate finishTransfer = null;
        if (isReturnHomeFinishTransferReady()) {
            try {
                finishTransfer = captureReturnHomeFinishTransferCandidate(chain);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
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
                log(Log.INFO, TAG,
                        "Armed atomic prepared-finish transfer"
                                + ", transitionDebugId="
                                + finishTransfer.transitionDebugId
                                + ", preparedDebugId="
                                + finishTransfer.preparedDebugId
                                + ", taskId="
                                + finishTransfer.composition.closingTaskId);
            } else {
                log(Log.WARN, TAG,
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
                log(Log.WARN, TAG,
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
        if (candidate == null) {
            return result;
        }
        ReturnHomeComposition composition = candidate.composition;
        try {
            Object currentApps = readField(candidate.controller, "mApps");
            Object navigationInfo = readField(candidate.controller,
                    "mBackNavigationInfo");
            Object navigationType = navigationInfo == null ? null
                    : invokeAnyMethod(navigationInfo, "getType", new Object[0]);
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
                    log(Log.ERROR, TAG,
                            "Rejected non-atomic return-home commit composition"
                                    + ", taskId=" + composition.closingTaskId
                                    + ", boundaryPhase="
                                    + candidate.acceptedBoundaryComposition.get());
                    return result;
                }
                log(Log.INFO, TAG,
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
            log(Log.WARN, TAG,
                    "Failed predictive return-home merge exit verification",
                    throwable);
        }
        return result;
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
                            log(Log.WARN, TAG,
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
        Object navigationType = navigationInfo == null ? null
                : invokeAnyMethod(navigationInfo, "getType", new Object[0]);
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
        log(Log.INFO, TAG,
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
        Object navigationType = invokeAnyMethod(
                navigationInfo, "getType", new Object[0]);
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
        Object preparedTypeObject = invokeAnyMethod(
                preparedOpenInfo, "getType", new Object[0]);
        if (!(preparedTypeObject instanceof Number)
                || ((Number) preparedTypeObject).intValue()
                != TRANSIT_PREDICTIVE_BACK) {
            return null;
        }
        ReturnHomeComposition composition = resolveReturnHomeComposition(
                readField(controller, "mApps"));
        if (composition == null) {
            log(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "non-standard targets");
            return null;
        }
        PreparedReturnHomeShape preparedShape =
                resolvePreparedReturnHomeShape(
                        preparedOpenInfo, composition, TRANSIT_CHANGE);
        if (preparedShape == null) {
            log(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "non-standard prepared transition");
            return null;
        }
        Object transitionTypeObject = invokeAnyMethod(
                info, "getType", new Object[0]);
        int transitionType = transitionTypeObject instanceof Number
                ? ((Number) transitionTypeObject).intValue() : -1;
        boolean supportedClosingType = transitionType == TRANSIT_CLOSE
                || transitionType == TRANSIT_TO_BACK;
        if (!supportedClosingType) {
            log(Log.INFO, TAG,
                    "Skipped predictive return-home commit composition: "
                            + "unexpected transition type=" + transitionType);
            return null;
        }
        Object changesObject = invokeAnyMethod(info, "getChanges", new Object[0]);
        if (!(changesObject instanceof List<?>)) {
            return null;
        }
        Object matchingChange = null;
        int matchingMode = -1;
        boolean backGestureAnimated = false;
        boolean elementChangePresent = false;
        int matchCount = 0;
        for (Object change : (List<?>) changesObject) {
            Object flagsObject = invokeAnyMethod(
                    change, "getFlags", new Object[0]);
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            if (flags == FLAG_IS_ELEMENT) {
                elementChangePresent = true;
            }
            Object taskInfo = invokeAnyMethod(change, "getTaskInfo", new Object[0]);
            if (readIntFieldOrDefault(taskInfo, "taskId", -1)
                    != composition.closingTaskId) {
                continue;
            }
            matchCount++;
            matchingChange = change;
            Object modeObject = invokeAnyMethod(change, "getMode", new Object[0]);
            matchingMode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            backGestureAnimated = Boolean.TRUE.equals(invokeAnyMethod(
                    change, "hasFlags",
                    new Object[]{Integer.valueOf(FLAG_BACK_GESTURE_ANIMATED)}));
        }
        if (matchCount != 1 || matchingChange == null
                || matchingMode != transitionType
                || !backGestureAnimated || elementChangePresent) {
            log(Log.INFO, TAG,
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
        Object changeLeashObject = invokeAnyMethod(
                matchingChange, "getLeash", new Object[0]);
        if (!(changeLeashObject instanceof SurfaceControl)
                || !((SurfaceControl) changeLeashObject).isValid()
                || surfacesAreSame((SurfaceControl) changeLeashObject,
                composition.closingLeash)
                || surfacesAreSame((SurfaceControl) changeLeashObject,
                composition.openingLeash)
                || !surfacesAreSame((SurfaceControl) changeLeashObject,
                preparedShape.appLeash)) {
            log(Log.INFO, TAG,
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
        Object navigationType = navigationInfo == null ? null
                : invokeAnyMethod(navigationInfo, "getType", new Object[0]);
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

        Object incomingTypeObject = invokeAnyMethod(
                info, "getType", new Object[0]);
        Object preparedTypeObject = invokeAnyMethod(
                preparedOpenInfo, "getType", new Object[0]);
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
        Object focusedTaskIdObject = invokeAnyMethod(
                navigationInfo, "getFocusedTaskId", new Object[0]);
        if (!(focusedTaskIdObject instanceof Number)
                || ((Number) focusedTaskIdObject).intValue()
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

        Object changesObject = invokeAnyMethod(
                info, "getChanges", new Object[0]);
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
            Object modeObject = invokeAnyMethod(
                    change, "getMode", new Object[0]);
            Object flagsObject = invokeAnyMethod(
                    change, "getFlags", new Object[0]);
            Object taskInfo = invokeAnyMethod(
                    change, "getTaskInfo", new Object[0]);
            Object leashObject = invokeAnyMethod(
                    change, "getLeash", new Object[0]);
            Object startBoundsObject = invokeAnyMethod(
                    change, "getStartAbsBounds", new Object[0]);
            Object endBoundsObject = invokeAnyMethod(
                    change, "getEndAbsBounds", new Object[0]);
            Object startDisplayObject = invokeAnyMethod(
                    change, "getStartDisplayId", new Object[0]);
            Object endDisplayObject = invokeAnyMethod(
                    change, "getEndDisplayId", new Object[0]);
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
        Object typeObject = invokeAnyMethod(
                info, "getType", new Object[0]);
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
        Object changesObject = invokeAnyMethod(
                info, "getChanges", new Object[0]);
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
            Object modeObject = invokeAnyMethod(
                    change, "getMode", new Object[0]);
            Object flagsObject = invokeAnyMethod(
                    change, "getFlags", new Object[0]);
            Object taskInfo = invokeAnyMethod(
                    change, "getTaskInfo", new Object[0]);
            Object leashObject = invokeAnyMethod(
                    change, "getLeash", new Object[0]);
            Object startBoundsObject = invokeAnyMethod(
                    change, "getStartAbsBounds", new Object[0]);
            Object endBoundsObject = invokeAnyMethod(
                    change, "getEndAbsBounds", new Object[0]);
            Object startDisplayObject = invokeAnyMethod(
                    change, "getStartDisplayId", new Object[0]);
            Object endDisplayObject = invokeAnyMethod(
                    change, "getEndDisplayId", new Object[0]);
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
        Object typeObject = invokeAnyMethod(
                candidate.transitionInfo, "getType", new Object[0]);
        Object changesObject = invokeAnyMethod(
                candidate.transitionInfo, "getChanges", new Object[0]);
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
            Object modeObject = invokeAnyMethod(
                    change, "getMode", new Object[0]);
            Object flagsObject = invokeAnyMethod(
                    change, "getFlags", new Object[0]);
            Object taskInfo = invokeAnyMethod(
                    change, "getTaskInfo", new Object[0]);
            Object leashObject = invokeAnyMethod(
                    change, "getLeash", new Object[0]);
            Object startBoundsObject = invokeAnyMethod(
                    change, "getStartAbsBounds", new Object[0]);
            Object endBoundsObject = invokeAnyMethod(
                    change, "getEndAbsBounds", new Object[0]);
            Object startDisplayObject = invokeAnyMethod(
                    change, "getStartDisplayId", new Object[0]);
            Object endDisplayObject = invokeAnyMethod(
                    change, "getEndDisplayId", new Object[0]);
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
            Object navigationType = navigationInfo == null ? null
                    : invokeAnyMethod(
                    navigationInfo, "getType", new Object[0]);
            Object focusedTaskId = navigationInfo == null ? null
                    : invokeAnyMethod(navigationInfo,
                    "getFocusedTaskId", new Object[0]);
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
                log(Log.WARN, TAG,
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
            log(Log.INFO, TAG,
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
            log(Log.WARN, TAG,
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
            log(isReturnHomeFinishTransferReady() ? Log.INFO : Log.WARN, TAG,
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
            log(Log.ERROR, TAG,
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
            log(backFinishOpenCallerDeoptimized ? Log.INFO : Log.WARN,
                    TAG, "Deoptimized exact BackTransitionHandler.mergeAnimation"
                            + ", success="
                            + backFinishOpenCallerDeoptimized);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
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
            log(Log.INFO, TAG, "Optional Shell method unavailable: " + methodName);
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
            log(Log.WARN, TAG,
                    "Failed to capture fixed Shell completion identity",
                    throwable);
        }
        Object result = chain.proceed();
        if ("finishBackAnimation".equals(chain.getExecutable().getName())) {
            try {
                Object transitionHandler = readField(controller,
                        "mBackTransitionHandler");
                log(Log.INFO, TAG, "Completed stock Shell back-animation cleanup"
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
                log(Log.WARN, TAG,
                        "Failed to inspect completed Shell back-animation cleanup",
                        throwable);
            }
        }
        for (Runnable completion : completions) {
            try {
                completion.run();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
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
                if (intent.hasExtra(EXTRA_LAUNCHER_EDITING)) {
                    miuiLauncherEditing = intent.getBooleanExtra(
                            EXTRA_LAUNCHER_EDITING, false);
                    log(Log.INFO, TAG, "MiuiHome editing state changed"
                            + ", editing=" + miuiLauncherEditing
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
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
                        log(Log.WARN, TAG, "Ignored stale MiuiHome launcher OPEN break state"
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
                        log(Log.INFO, TAG, "MiuiHome launcher OPEN break state changed"
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
                    log(Log.WARN, TAG,
                            "Ignored launcher OPEN state without active lifecycle");
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

    protected void receiveMiuiHomeAcceptedInput(Intent intent) {
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
        boolean consumed = new ArrayList<>(nativeInputMonitors.values()).stream()
                .anyMatch(monitor -> monitor.acceptMiuiHomeInput(token));
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
            log(Log.WARN, TAG, "Failed to validate launcher-state sender uid=" + uid,
                    throwable);
        }
        return false;
    }

    protected synchronized void unregisterMiuiOverviewStateReceiver() {
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

    protected synchronized void beginMiuiOverviewDismiss(String reason) {
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

    protected synchronized void restoreMiuiOverviewAfterDismissTimeout(long pendingUntil) {
        if (miuiOverviewDismissPendingUntilUptime != pendingUntil) {
            return;
        }
        miuiOverviewDismissPendingUntilUptime = 0L;
        miuiOverviewVisible = true;
        log(Log.WARN, TAG, "Miui Recents dismiss confirmation timed out"
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
                log(Log.INFO, TAG, "Updated native SystemUI back input monitor"
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
                unregisterMiuiOverviewStateReceiver();
                return;
            }
            log(Log.INFO, TAG, "Installed native SystemUI back input monitor"
                    + ", controller=" + shortObject(controller));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install SystemUI back input driver", throwable);
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

    protected void ensureNativeEdgeBackPlugin(Object edgeBackGestureHandler, Context context) {
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

    protected void ensureAospBackAnimations(Object controller, String source) {
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
}
