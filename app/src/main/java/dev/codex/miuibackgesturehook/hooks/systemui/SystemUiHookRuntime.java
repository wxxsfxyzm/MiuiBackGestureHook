package dev.codex.miuibackgesturehook.hooks.systemui;

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

public abstract class SystemUiHookRuntime extends SystemUiInputRuntime {


    protected void installSystemUiHooks(ClassLoader classLoader) {
        try {
            hookMiuiOverviewProxy(classLoader);
            hookNavigationBarTransientAutoHide(classLoader);
            hookNavigationBarTransientAppearance(classLoader);
            hookStatusBarTransientAppearance(classLoader);
            hookEdgeBackGestureHandler(classLoader);
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

    protected void hookEdgeBackGestureHandler(ClassLoader classLoader) {
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
            hookBackPrepareTransitionReparent(classLoader);
            hookBackCommitComposition(classLoader);
            hookBackFinishOpenAtomicTransfer(classLoader);
            log(Log.INFO, TAG, "Hooked Shell BackAnimationController AOSP path");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook Shell back animation", throwable);
        }
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
                            "Hooked Shell predictive return-home prepare role correction");
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

    protected Object correctPredictiveBackPrepareReparent(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!Boolean.TRUE.equals(result)) {
            return result;
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
            int closingTaskId = composition.closingTaskId;
            int openingTaskId = composition.openingTaskId;
            Object changesObject = invokeAnyMethod(info, "getChanges", new Object[0]);
            if (!(changesObject instanceof List<?>)) {
                return result;
            }
            List<?> changes = (List<?>) changesObject;
            int changeCount = changes.size();
            if (changeCount != 2 && changeCount != 3) {
                return result;
            }
            int expectedWallpaperMatchCount = changeCount - 2;
            Object matchingChange = null;
            int matchingMode = -1;
            int closingMatchCount = 0;
            int homeMatchCount = 0;
            int wallpaperMatchCount = 0;
            boolean unexpectedChange = false;
            for (Object change : changes) {
                Object taskInfo = invokeAnyMethod(
                        change, "getTaskInfo", new Object[0]);
                int taskId = readIntFieldOrDefault(taskInfo, "taskId", -1);
                Object modeObject = invokeAnyMethod(
                        change, "getMode", new Object[0]);
                int mode = modeObject instanceof Number
                        ? ((Number) modeObject).intValue() : -1;
                boolean hasBackFlag = Boolean.TRUE.equals(invokeAnyMethod(
                        change, "hasFlags", new Object[]{Integer.valueOf(
                                FLAG_BACK_GESTURE_ANIMATED)}));
                boolean wallpaper = Boolean.TRUE.equals(invokeAnyMethod(
                        change, "hasFlags",
                        new Object[]{Integer.valueOf(FLAG_IS_WALLPAPER)}));
                if (taskId == closingTaskId) {
                    closingMatchCount++;
                    matchingMode = mode;
                    if (mode == TRANSIT_TO_FRONT && hasBackFlag
                            && !wallpaper) {
                        matchingChange = change;
                    } else {
                        unexpectedChange = true;
                    }
                    continue;
                }
                if (taskId == openingTaskId) {
                    homeMatchCount++;
                    if (mode != TRANSIT_TO_FRONT || !hasBackFlag
                            || wallpaper) {
                        unexpectedChange = true;
                    }
                    continue;
                }
                if (taskId < 0 && mode == TRANSIT_TO_FRONT
                        && wallpaper && !hasBackFlag) {
                    wallpaperMatchCount++;
                } else {
                    unexpectedChange = true;
                }
            }
            if (matchingChange == null || closingMatchCount != 1
                    || homeMatchCount != 1
                    || wallpaperMatchCount != expectedWallpaperMatchCount
                    || unexpectedChange) {
                return result;
            }
            Object changeLeashObject = invokeAnyMethod(
                    matchingChange, "getLeash", new Object[0]);
            Object startTransaction = chain.getArg(2);
            if (!(changeLeashObject instanceof SurfaceControl)
                    || !((SurfaceControl) changeLeashObject).isValid()
                    || surfacesAreSame((SurfaceControl) changeLeashObject,
                    composition.closingLeash)
                    || !(startTransaction instanceof SurfaceControl.Transaction)) {
                return result;
            }
            SurfaceControl changeLeash = (SurfaceControl) changeLeashObject;
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
            invokeAnyMethod(matchingChange, "setMode",
                    new Object[]{Integer.valueOf(TRANSIT_CHANGE)});
            Object normalizedModeObject = invokeAnyMethod(
                    matchingChange, "getMode", new Object[0]);
            int normalizedMode = normalizedModeObject instanceof Number
                    ? ((Number) normalizedModeObject).intValue() : -1;
            if (normalizedMode != TRANSIT_CHANGE) {
                throw new IllegalStateException(
                        "prepared return-home role normalization was not retained"
                                + ", mode=" + normalizedMode);
            }
            log(Log.INFO, TAG,
                    "Corrected Xiaomi predictive return-home prepare role"
                            + ", taskId=" + closingTaskId
                            + ", mode=" + matchingMode + "->" + normalizedMode
                            + ", wallpaperPresent="
                            + (wallpaperMatchCount == 1));
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
                    // Preserve the established correction if the exact accepted callback
                    // boundary cannot be wrapped on a future Shell build. This fallback is
                    // intentionally diagnostic: two applies can be presented in different
                    // frames, so the supported path above must report atomic composition.
                    try (SurfaceControl.Transaction transaction =
                                 new SurfaceControl.Transaction()) {
                        transaction.reparent(candidate.changeLeash,
                                composition.closingLeash);
                        transaction.apply();
                    }
                    log(Log.WARN, TAG,
                            "Fell back to post-apply return-home commit composition"
                                    + ", taskId=" + composition.closingTaskId
                                    + ", boundaryPhase="
                                    + candidate.acceptedBoundaryComposition.get());
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
                                + ", atomicStartTransaction="
                                + composedInStartTransaction);
                publishStandardReturnHomeCommit(
                        composition.closingTaskId,
                        readTransitionDebugId(candidate.transitionInfo),
                        candidate.controller);
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
                composition.openingLeash)) {
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
        Rect closingBounds = resolveExactRemoteTargetTransitionBounds(
                composition.closingTarget);
        Rect openingBounds = resolveExactRemoteTargetTransitionBounds(
                composition.openingTarget);
        if (!closingBounds.equals(openingBounds)) {
            return null;
        }

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

        Object preparedChangesObject = invokeAnyMethod(
                preparedOpenInfo, "getChanges", new Object[0]);
        if (!(preparedChangesObject instanceof List<?>)) {
            return null;
        }
        List<?> preparedChanges = (List<?>) preparedChangesObject;
        int preparedChangeCount = preparedChanges.size();
        if (preparedChangeCount != 2 && preparedChangeCount != 3) {
            return null;
        }
        boolean preparedWallpaperExpected = preparedChangeCount == 3;
        // The exact two-change prepared shape omits SHOW_WALLPAPER on Home.
        int expectedPreparedHomeFlags = preparedWallpaperExpected
                ? XIAOMI_PREPARED_HOME_CHANGE_FLAGS
                : XIAOMI_PREPARED_HOME_NO_WALLPAPER_CHANGE_FLAGS;
        SurfaceControl preparedAppLeash = null;
        SurfaceControl preparedHomeLeash = null;
        SurfaceControl preparedWallpaperLeash = null;
        for (Object change : preparedChanges) {
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
                    && preparedAppLeash == null) {
                boolean appFlags = flags
                        == FLAG_BACK_GESTURE_ANIMATED
                        || flags == (FLAG_BACK_GESTURE_ANIMATED
                        | FLAG_DISPLAY_CHANGE);
                if (mode != TRANSIT_CHANGE || !appFlags
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
                preparedAppLeash = (SurfaceControl) leashObject;
                continue;
            }
            if (taskId == composition.openingTaskId
                    && preparedHomeLeash == null) {
                if (mode != TRANSIT_TO_FRONT
                        || flags != expectedPreparedHomeFlags
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
                preparedHomeLeash = (SurfaceControl) leashObject;
                continue;
            }
            if (taskInfo == null && preparedWallpaperLeash == null
                    && mode == TRANSIT_TO_FRONT
                    && flags == FLAG_IS_WALLPAPER
                    && startBounds.equals(closingBounds)
                    && endBounds.equals(closingBounds)) {
                preparedWallpaperLeash = (SurfaceControl) leashObject;
                continue;
            }
            return null;
        }
        boolean preparedWallpaperMatches = preparedWallpaperExpected
                ? preparedWallpaperLeash != null
                && !surfacesAreSame(preparedWallpaperLeash, appLeash)
                && !surfacesAreSame(preparedWallpaperLeash, homeLeash)
                && !surfacesAreSame(preparedWallpaperLeash, elementLeash)
                : preparedWallpaperLeash == null;
        if (preparedAppLeash == null || preparedHomeLeash == null
                || !preparedWallpaperMatches
                || !surfacesAreSame(preparedAppLeash, appLeash)
                || !surfacesAreSame(preparedHomeLeash, homeLeash)) {
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
        return chain.proceed();
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
        List<Runnable> completions = new ArrayList<>();
        try {
            Object currentTracker = readField(controller, "mCurrentTracker");
            Object queuedTracker = readField(controller, "mQueuedTracker");
            Object navigation = readField(controller, "mBackNavigationInfo");
            for (NativeBackInputMonitor monitor
                    : new ArrayList<>(nativeInputMonitors.values())) {
                Runnable completion = monitor.captureShellAnimationCompletion(
                        controller, currentTracker, queuedTracker,
                        navigation, chain.getExecutable().getName());
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
                        long previousGeneration = miuiLauncherOpenBreakGeneration;
                        boolean previousAvailable = miuiLauncherOpenBreakAvailable;
                        miuiLauncherOpenBreakGeneration = generation;
                        miuiLauncherOpenBreakAvailable = available;
                        log(Log.INFO, TAG, "MiuiHome launcher OPEN break state changed"
                                + ", available=" + available
                                + ", generation=" + generation
                                + ", uid=" + senderUid
                                + ", package=" + senderPackage);
                        if (!available && previousAvailable
                                && previousGeneration == generation) {
                            for (NativeBackInputMonitor monitor
                                    : new ArrayList<>(nativeInputMonitors.values())) {
                                monitor.driver.onLauncherOpenBreakUnavailable(generation);
                            }
                        }
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
