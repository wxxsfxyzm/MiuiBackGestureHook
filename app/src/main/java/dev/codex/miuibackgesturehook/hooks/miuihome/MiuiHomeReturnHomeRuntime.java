package dev.codex.miuibackgesturehook.hooks.miuihome;

import dev.codex.miuibackgesturehook.hooks.systemui.SystemUiHookRuntime;

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

public abstract class MiuiHomeReturnHomeRuntime extends SystemUiHookRuntime {
    protected abstract void handleMiuiHomeReturnHomeBinderDeath(
            MiuiHomeReturnHomeController controller);
    protected abstract void finishDeferredMiuiHomeReturnHomeController(
            MiuiHomeReturnHomeController controller, String reason);

    protected volatile MiuiHomeReturnHomeController miuiHomeReturnHomeController;

    protected final class MiuiHomeReturnHomeController {
        protected final IBinder shellBackAnimation;
        protected final ClassLoader classLoader;
        protected final Handler handler = new Handler(Looper.getMainLooper());
        protected final IBinder.DeathRecipient shellDeathRecipient = () ->
                handler.post(() -> handleMiuiHomeReturnHomeBinderDeath(
                        MiuiHomeReturnHomeController.this));
        protected final PathInterpolator backGestureInterpolator =
                new PathInterpolator(0.1f, 0.1f, 0.0f, 1.0f);
        protected final boolean removeDepartTargetFromMotion;
        protected final ReturnHomeBackCallback backCallback = new ReturnHomeBackCallback();
        protected final ReturnHomeAnimationRunner animationRunner =
                new ReturnHomeAnimationRunner();
        protected final AtomicReference<ReturnHomeCloseInterruptionToken>
                pendingCloseInterruption = new AtomicReference<>();
        protected final AtomicReference<ReturnHomeFreshOpenToken>
                pendingFreshOpen = new AtomicReference<>();
        protected final AtomicReference<ReturnHomeDirectCancelToken>
                pendingDirectCancel = new AtomicReference<>();
        protected final AtomicReference<ReturnHomeElementLeashReuseToken>
                pendingElementLeashReuse = new AtomicReference<>();
        protected final AtomicReference<StandardReturnHomeCommitSignal>
                pendingStandardCommitSignal = new AtomicReference<>();
        protected final ConcurrentHashMap<Object,
                ConcurrentLinkedQueue<UnifiedNativeFinishDispatchToken>>
                pendingUnifiedNativeFinishDispatches =
                new ConcurrentHashMap<>();
        protected final ConcurrentHashMap<ObjectIdentityKey,
                UnifiedNativePendingInterruptionSnapshot>
                pendingUnifiedInterruptedAnimToConfigs =
                new ConcurrentHashMap<>();
        protected final AtomicLong unifiedNativeFinishDispatchIds =
                new AtomicLong();
        protected volatile Context context;
        protected volatile boolean attached;
        protected volatile boolean deathLinked;
        protected volatile boolean deferredControllerReplacement;
        protected volatile boolean shellBinderDead;
        protected volatile ReturnHomeSession currentSession;
        protected long lastStandardCommitSignalAttempt;
        protected BackMotionEvent pendingStartEvent;
        protected BackMotionEvent pendingProgressEvent;
        protected int pendingTerminalAction = RETURN_HOME_TERMINAL_NONE;
        protected boolean discardRejectedRunnerCallback;
        protected Constructor<?> nativeTargetSetConstructor;
        protected Constructor<?> nativeWindowAnimParamsConstructor;
        protected Constructor<?> nativeRectFParamsConstructor;
        protected Constructor<?> nativeCornerRadiiConstructor;
        protected Constructor<?> nativeClipAnimationHelperConstructor;
        protected Method nativeGestureAnimExecutorMethod;
        protected Object nativeCloseToDragType;
        protected Object nativeAppToAppType;

        MiuiHomeReturnHomeController(IBinder shellBackAnimation,
                                    ClassLoader classLoader, Context context) {
            this.shellBackAnimation = shellBackAnimation;
            this.classLoader = classLoader;
            this.context = context;
            this.removeDepartTargetFromMotion = readWindowFlag(
                    "removeDepartTargetFromMotion", classLoader, false);
        }

        boolean attach() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SHELL_BACK_ANIMATION_DESCRIPTOR);
                data.writeStrongBinder(backCallback);
                data.writeStrongBinder(animationRunner);
                if (!shellBackAnimation.transact(
                        SHELL_BACK_SET_LAUNCHER_CALLBACK_TRANSACTION,
                        data, reply, 0)) {
                    log(Log.WARN, TAG, "Shell rejected setBackToLauncherCallback transact");
                    return false;
                }
                reply.readException();
                shellBackAnimation.linkToDeath(shellDeathRecipient, 0);
                deathLinked = true;
                attached = true;
                return true;
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to register Shell return-to-home runner",
                        throwable);
                return false;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        public boolean blocksControllerReplacement() {
            ReturnHomeSession session = currentSession;
            return (session != null && session.cleaned.get() == 0)
                    || !pendingUnifiedInterruptedAnimToConfigs.isEmpty();
        }

        void beginDeferredControllerReplacement(String reason) {
            if (!deferredControllerReplacement) {
                deferredControllerReplacement = true;
                attached = false;
                invalidatePendingFreshOpen("deferredControllerReplacement:" + reason);
                clearPendingCallbackState();
                discardRejectedRunnerCallback = false;
                pendingStandardCommitSignal.set(null);
                pendingUnifiedNativeFinishDispatches.clear();
            }
            ReturnHomeSession session = currentSession;
            if (session != null && session.finished.get() == 0
                    && requestUnifiedPendingCommitTermination(
                    session, "deferredControllerReplacement:" + reason)) {
                return;
            }
            if (session != null && session.finished.get() == 0
                    && !session.nativeHandoffStarted
                    && !session.nativeAnimationStarted) {
                finishSession(session,
                        "deferredControllerReplacement:" + reason,
                        shouldRestorePreview(session));
            }
        }

        void onShellBinderDied() {
            shellBinderDead = true;
            deathLinked = false;
            beginDeferredControllerReplacement("shellBinderDied");
        }

        public String describeUnifiedOwner() {
            ReturnHomeSession session = currentSession;
            if (session == null) {
                return "session=none, interruptedConfigTombstones="
                        + pendingUnifiedInterruptedAnimToConfigs.size();
            }
            return "generation=" + session.generation
                    + ", attached=" + attached
                    + ", deferred=" + deferredControllerReplacement
                    + ", finished=" + session.finished.get()
                    + ", cleaned=" + session.cleaned.get()
                    + ", commitPending="
                    + session.unifiedNativeCommitPending
                    + ", cancelPending="
                    + session.unifiedNativeCancelPending
                    + ", nativeStarted=" + session.nativeAnimationStarted
                    + ", interruptedConfigTombstones="
                    + pendingUnifiedInterruptedAnimToConfigs.size();
        }

        void detach(boolean clearShell, String reason) {
            attached = false;
            invalidatePendingCloseInterruption(null, "detach:" + reason);
            invalidatePendingFreshOpen("detach:" + reason);
            invalidatePendingDirectCancel(null, "detach:" + reason, true);
            invalidateElementTransitionContinuity(
                    null, "detach:" + reason, true);
            if (deathLinked) {
                deathLinked = false;
                try {
                    shellBackAnimation.unlinkToDeath(shellDeathRecipient, 0);
                } catch (Throwable throwable) {
                    log(Log.INFO, TAG, "Shell back-animation death link already gone"
                            + ", reason=" + reason);
                }
            }
            ReturnHomeSession session = currentSession;
            if (session != null) {
                finishSession(session, "detach:" + reason,
                        shouldRestorePreview(session));
            }
            clearPendingCallbackState();
            discardRejectedRunnerCallback = false;
            pendingStandardCommitSignal.set(null);
            pendingUnifiedNativeFinishDispatches.clear();
            if (clearShell && !shellBinderDead
                    && shellBackAnimation.isBinderAlive()) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(SHELL_BACK_ANIMATION_DESCRIPTOR);
                    shellBackAnimation.transact(
                            SHELL_BACK_CLEAR_LAUNCHER_CALLBACK_TRANSACTION,
                            data, reply, 0);
                    reply.readException();
                    log(Log.INFO, TAG, "Cleared standard Shell return-to-home callback"
                            + ", reason=" + reason);
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "Failed to clear Shell return-to-home callback"
                            + ", reason=" + reason, throwable);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }

        void onBackStarted(BackMotionEvent event) {
            if (!attached || event == null) {
                return;
            }
            // A callback start is the generation boundary even if the preceding runner never
            // arrived. Do not let its last progress sample bleed into this gesture.
            clearPendingCallbackState();
            if (discardRejectedRunnerCallback) {
                releaseBackMotionEventTarget(event);
                return;
            }
            ReturnHomeSession session = currentSession;
            if (session == null || session.progressFrozen) {
                pendingStartEvent = event;
                return;
            }
            startPreview(session, event);
        }

        void onBackProgressed(BackMotionEvent event) {
            if (!attached || event == null) {
                return;
            }
            if (discardRejectedRunnerCallback) {
                return;
            }
            ReturnHomeSession session = currentSession;
            if (session == null || session.progressFrozen) {
                pendingProgressEvent = event;
                return;
            }
            if (!session.previewInitialized) {
                BackMotionEvent startEvent = pendingStartEvent;
                if (startEvent != null) {
                    pendingStartEvent = null;
                    startPreview(session, startEvent);
                }
            }
            dispatchPreviewProgress(session, event);
        }

        void onBackCancelled() {
            if (discardRejectedRunnerCallback) {
                discardRejectedRunnerCallback = false;
                clearPendingCallbackState();
                log(Log.INFO, TAG,
                        "Discarded cancel callback for rejected return-home runner");
                return;
            }
            ReturnHomeSession session = currentSession;
            if (session == null || session.progressFrozen) {
                pendingTerminalAction = RETURN_HOME_TERMINAL_CANCEL;
                return;
            }
            clearPendingCallbackState();
            animateCancel(session, "callbackCancelled");
        }

        void onBackInvoked() {
            if (discardRejectedRunnerCallback) {
                discardRejectedRunnerCallback = false;
                clearPendingCallbackState();
                log(Log.INFO, TAG,
                        "Discarded invoke callback for rejected return-home runner");
                return;
            }
            ReturnHomeSession session = currentSession;
            if (session == null || session.progressFrozen) {
                pendingTerminalAction = RETURN_HOME_TERMINAL_INVOKE;
                log(Log.INFO, TAG, "Return-to-home invoke waiting for remote targets");
                return;
            }
            clearPendingCallbackState();
            startNativeClose(session);
        }

        void onRemoteAnimationStart(int transit, Object[] apps, Object[] wallpapers,
                                    Object[] nonApps, IBinder finishedCallback) {
            if (!attached) {
                notifyRemoteAnimationFinished(finishedCallback, "detachedStart");
                return;
            }
            ReturnHomeSession previous = currentSession;
            if (previous != null) {
                boolean retainedNativeOwner = previous.finished.get() == 0
                        && previous.nativeHandoffStarted
                        && previous.nativeAnimationStarted
                        && isReturnHomeNativeCloseType(
                        previous.nativeAnimationType);
                if ((previous.unifiedNativePreviewOwned
                        && !previous.unifiedNativeCleanupVerified
                        && previous.finished.get() == 0)
                        || retainedNativeOwner) {
                    if (previous.unifiedNativePreviewOwned
                        && previous.finished.get() == 0) {
                        startUnifiedNativeCancel(
                                previous, "supersededRunner");
                    }
                    discardRejectedRunnerCallback =
                            pendingTerminalAction == RETURN_HOME_TERMINAL_NONE;
                    clearPendingCallbackState();
                    discardMatchingUnboundStandardSignal(
                            miuiHomeAcceptedInputIdentity.get(),
                            "overlappingRunnerRejected");
                    notifyRemoteAnimationFinished(
                            finishedCallback, "previousNativeOwnerActive");
                    releaseTargets(apps);
                    releaseTargets(wallpapers);
                    releaseTargets(nonApps);
                    log(Log.WARN, TAG,
                            "Rejected overlapping return-home runner"
                                    + ", activeGeneration="
                                    + previous.generation
                                    + ", nativeStarted="
                                    + previous.nativeAnimationStarted);
                    return;
                }
                invalidatePendingDirectCancel(
                        previous, "superseded", true);
                finishSession(previous, "superseded",
                        shouldRestorePreview(previous));
            }
            // Callback and runner are separate Binder objects. Consume the pending callback
            // state exactly once for this runner arrival, including when its targets prove
            // invalid, so a stale terminal action can never leak into the next animation.
            BackMotionEvent startEvent = pendingStartEvent;
            BackMotionEvent progressEvent = pendingProgressEvent;
            int terminalAction = pendingTerminalAction;
            pendingStartEvent = null;
            pendingProgressEvent = null;
            pendingTerminalAction = RETURN_HOME_TERMINAL_NONE;
            ReturnHomeSession session = new ReturnHomeSession(
                    miuiHomeReturnHomeGenerationIds.incrementAndGet(),
                    apps, wallpapers, nonApps, finishedCallback,
                    miuiHomeAcceptedInputIdentity.get());
            currentSession = session;
            if (!session.resolveTargets()) {
                log(Log.WARN, TAG, "Invalid return-to-home animation targets"
                        + ", generation=" + session.generation
                        + ", apps=" + (apps == null ? -1 : apps.length));
                discardMatchingUnboundStandardSignal(
                        session.acceptedInputIdentity,
                        "invalidRunnerTargets");
                releaseBackMotionEventTarget(startEvent);
                finishSession(session, "invalidTargets", false);
                return;
            }
            bindPendingStandardCommitToSession(session);
            if (startEvent != null) {
                startPreview(session, startEvent);
            }
            if (progressEvent != null) {
                if (terminalAction == RETURN_HOME_TERMINAL_NONE) {
                    dispatchPreviewProgress(session, progressEvent);
                } else {
                    // A release can beat the runner because callback and runner use separate
                    // Binder objects. There is no animation frame left in which the spring can
                    // catch up, so establish the exact latest gesture geometry once before the
                    // terminal path freezes/reset the animator.
                    session.lastInputProgress = clamp01(progressEvent.getProgress());
                    updatePreviewFrame(session, progressEvent.getProgress(),
                            progressEvent.getTouchY(), false);
                    log(Log.INFO, TAG,
                            "Applied terminal return-home progress catch-up"
                                    + ", generation=" + session.generation
                                    + ", terminalAction=" + terminalAction
                                    + ", progress=" + session.lastInputProgress);
                }
            } else if (terminalAction != RETURN_HOME_TERMINAL_NONE
                    && startEvent != null && session.previewInitialized) {
                session.lastInputProgress = clamp01(startEvent.getProgress());
                updatePreviewFrame(session, startEvent.getProgress(),
                        startEvent.getTouchY(), false);
            }
            if (terminalAction == RETURN_HOME_TERMINAL_CANCEL) {
                animateCancel(session, "pendingCallbackCancelled");
            } else if (terminalAction == RETURN_HOME_TERMINAL_INVOKE) {
                startNativeClose(session);
            }
            log(Log.INFO, TAG, "MiuiHome return-to-home remote animation started"
                    + ", generation=" + session.generation
                    + ", transit=" + transit
                    + ", apps=" + apps.length
                    + ", closing=" + shortObject(session.closingTarget)
                    + ", opening=" + shortObject(session.openingTarget));
        }

        void onRemoteAnimationCancelled() {
            clearPendingCallbackState();
            ReturnHomeSession session = currentSession;
            if (session != null) {
                if (requestUnifiedPendingCommitTermination(
                        session, "runnerCancelled")) {
                    return;
                }
                animateCancel(session, "runnerCancelled");
            } else {
                discardMatchingUnboundStandardSignal(
                        miuiHomeAcceptedInputIdentity.get(),
                        "runnerCancelledWithoutSession");
            }
        }

        protected void startPreview(ReturnHomeSession session, BackMotionEvent event) {
            if (session.finished.get() != 0 || currentSession != session) {
                return;
            }
            session.initialTouchY = event.getTouchY();
            session.swipeEdge = event.getSwipeEdge();
            if (!session.previewInitialized) {
                if (!resolvePreviewTarget(session, event)) {
                    return;
                }
                Rect startBounds = resolveRemoteAnimationBounds(session.previewTarget);
                if (startBounds == null || startBounds.isEmpty()) {
                    log(Log.WARN, TAG, "Cannot resolve return-to-home preview bounds"
                            + ", generation=" + session.generation
                            + ", target=" + shortObject(session.previewTarget)
                            + ", source=" + session.previewTargetSource);
                    return;
                }
                session.startRect.set(startBounds);
                session.currentRect.set(startBounds);
                session.startCornerRadius = resolveMiuiWindowCornerRadius(
                        session.previewTarget);
                session.endCornerRadius =
                        dp(RETURN_HOME_END_CORNER_RADIUS_DP);
                session.currentCornerRadius = session.startCornerRadius;
                Context currentContext = context;
                session.previewProgressDistancePx = currentContext == null
                        ? Math.max(1.0f, session.startRect.width())
                        : Math.max(1.0f, currentContext.getResources()
                        .getDisplayMetrics().widthPixels);
                session.previewInitialized = true;
                preparePreviewLeash(session);
                prepareNativePreviewBackdrop(session);
                prepareNativePreviewBlur(session);
                if (!prepareUnifiedNativePreview(session)) {
                    applyPreviewTransform(session, session.currentRect,
                            session.startCornerRadius, false);
                }
                log(Log.INFO, TAG, "Initialized return-to-home preview"
                        + ", generation=" + session.generation
                        + ", startRect=" + session.startRect
                        + ", startRadius=" + session.startCornerRadius
                        + ", leashShown=" + session.previewLeashPrepared
                        + ", targetSource=" + session.previewTargetSource
                        + ", previewTarget=" + shortObject(session.previewTarget)
                        + ", previewLeash=" + String.valueOf(session.previewLeash)
                        + ", runnerClosingLeash="
                        + String.valueOf(session.closingLeash)
                        + ", sameSurfaceAsRunner="
                        + describeSameSurface(session.previewLeash,
                                session.closingLeash));
            }
            startPreviewProgressAnimator(session, event);
        }

        protected boolean resolvePreviewTarget(ReturnHomeSession session,
                                             BackMotionEvent event) {
            Object target = session.closingTarget;
            String source = "runnerClosing";
            if (!removeDepartTargetFromMotion) {
                try {
                    target = event.getDepartingAnimationTarget();
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "Failed to read departing predictive-back target"
                            + ", generation=" + session.generation, throwable);
                    return false;
                }
                source = "backMotionEvent";
                if (target == null) {
                    log(Log.WARN, TAG, "Missing departing predictive-back target"
                            + ", generation=" + session.generation
                            + ", removeDepartTargetFromMotion=false");
                    return false;
                }
                int mode = readIntFieldOrDefault(target, "mode", -1);
                if (mode != 1) {
                    log(Log.WARN, TAG, "Rejected non-closing departing back target"
                            + ", generation=" + session.generation
                            + ", mode=" + mode
                            + ", target=" + shortObject(target));
                    releaseBackMotionEventTarget(event);
                    return false;
                }
            }
            try {
                Object leash = readField(target, "leash");
                if (!(leash instanceof SurfaceControl)
                        || !((SurfaceControl) leash).isValid()) {
                    log(Log.WARN, TAG, "Invalid return-to-home preview leash"
                            + ", generation=" + session.generation
                            + ", source=" + source
                            + ", target=" + shortObject(target)
                            + ", leash=" + shortObject(leash));
                    if (!removeDepartTargetFromMotion) {
                        releaseBackMotionEventTarget(event);
                    }
                    return false;
                }
                session.previewTarget = target;
                session.previewLeash = (SurfaceControl) leash;
                session.previewTargetSource = source;
                return true;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to resolve return-to-home preview target"
                        + ", generation=" + session.generation
                        + ", source=" + source
                        + ", target=" + shortObject(target), throwable);
                if (!removeDepartTargetFromMotion) {
                    releaseBackMotionEventTarget(event);
                }
                return false;
            }
        }

        protected String describeSameSurface(SurfaceControl first,
                                           SurfaceControl second) {
            if (first == null || second == null) {
                return "unavailable";
            }
            if (first == second) {
                return "sameObject";
            }
            try {
                Object result = invokeAnyMethod(first, "isSameSurface",
                        new Object[]{second});
                return result instanceof Boolean
                        ? result.toString() : "unknown";
            } catch (Throwable ignored) {
                return "unknown";
            }
        }

        protected void clearPendingCallbackState() {
            BackMotionEvent event = pendingStartEvent;
            pendingStartEvent = null;
            pendingProgressEvent = null;
            pendingTerminalAction = RETURN_HOME_TERMINAL_NONE;
            releaseBackMotionEventTarget(event);
        }

        protected void releaseBackMotionEventTarget(BackMotionEvent event) {
            if (event == null) {
                return;
            }
            try {
                Object target = event.getDepartingAnimationTarget();
                if (target == null) {
                    return;
                }
                Object leash = readField(target, "leash");
                if (leash instanceof SurfaceControl) {
                    ((SurfaceControl) leash).release();
                }
            } catch (Throwable ignored) {
            }
        }

        protected void startPreviewProgressAnimator(
                ReturnHomeSession session, BackMotionEvent event) {
            if (event == null || session.progressFrozen
                    || session.finished.get() != 0 || currentSession != session
                    || !session.previewInitialized || session.nativeHandoffStarted) {
                return;
            }
            session.lastInputProgress = clamp01(event.getProgress());
            if (session.progressAnimatorStarted) {
                dispatchPreviewProgress(session, event);
                return;
            }
            if (session.progressAnimator == null || session.progressAnimatorFailed) {
                session.progressAnimatorFailed = true;
                updatePreviewFrame(session, event.getProgress(), event.getTouchY(), false);
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                session.progressAnimatorFailed = true;
                log(Log.WARN, TAG,
                        "Refused return-home BackProgressAnimator outside main Looper"
                                + ", generation=" + session.generation);
                updatePreviewFrame(session, event.getProgress(), event.getTouchY(), false);
                return;
            }
            session.progressAnimatorStarted = true;
            session.progressInitialCallbackDelivered = false;
            try {
                session.progressAnimator.onBackStarted(event, smoothedEvent -> {
                    if (session.progressFrozen || session.progressAnimatorFailed
                            || session.finished.get() != 0
                            || currentSession != session || !session.previewInitialized
                            || session.nativeHandoffStarted) {
                        return;
                    }
                    boolean animationFrame = session.progressInitialCallbackDelivered;
                    session.progressInitialCallbackDelivered = true;
                    updatePreviewFrame(session, smoothedEvent.getProgress(),
                            smoothedEvent.getTouchY(), animationFrame);
                });
                log(Log.INFO, TAG,
                        "Started AOSP return-home progress smoothing"
                                + ", generation=" + session.generation
                                + ", inputProgress=" + session.lastInputProgress);
            } catch (Throwable throwable) {
                session.progressAnimatorFailed = true;
                try {
                    session.progressAnimator.reset();
                } catch (Throwable ignored) {
                }
                log(Log.WARN, TAG,
                        "Failed to start AOSP return-home progress smoothing"
                                + ", generation=" + session.generation,
                        throwable);
                updatePreviewFrame(session, event.getProgress(), event.getTouchY(), false);
            }
        }

        protected void dispatchPreviewProgress(
                ReturnHomeSession session, BackMotionEvent event) {
            if (event == null || session.progressFrozen
                    || session.finished.get() != 0 || currentSession != session
                    || !session.previewInitialized || session.nativeHandoffStarted) {
                return;
            }
            session.lastInputProgress = clamp01(event.getProgress());
            if (session.progressAnimator == null || session.progressAnimatorFailed
                    || !session.progressAnimatorStarted) {
                updatePreviewFrame(session, event.getProgress(), event.getTouchY(), false);
                return;
            }
            try {
                session.progressAnimator.onBackProgressed(event);
            } catch (Throwable throwable) {
                session.progressAnimatorFailed = true;
                try {
                    session.progressAnimator.reset();
                } catch (Throwable ignored) {
                }
                log(Log.WARN, TAG,
                        "Failed to update AOSP return-home progress smoothing"
                                + ", generation=" + session.generation,
                        throwable);
                updatePreviewFrame(session, event.getProgress(), event.getTouchY(), false);
            }
        }

        protected void updatePreviewFrame(ReturnHomeSession session,
                                        float smoothedProgress, float touchY,
                                        boolean animationFrame) {
            if (session.finished.get() != 0 || currentSession != session
                    || !session.previewInitialized || session.nativeHandoffStarted
                    || session.progressFrozen) {
                return;
            }
            float rawProgress = clamp01(smoothedProgress);
            session.lastSmoothedProgress = rawProgress;
            float progress = backGestureInterpolator.getInterpolation(rawProgress);
            float startWidth = session.startRect.width();
            float startHeight = session.startRect.height();
            float width = startWidth * (1.0f
                    - ((1.0f - RETURN_HOME_MIN_WINDOW_SCALE) * progress));
            float height = startWidth <= 0.0f
                    ? startHeight : startHeight * (width / startWidth);
            float rawYDelta = touchY - session.initialTouchY;
            float halfHeight = Math.max(1.0f, startHeight / 2.0f);
            float yRatio = Math.min(1.0f, Math.abs(rawYDelta) / halfHeight);
            float interpolatedY = 1.0f - ((1.0f - yRatio) * (1.0f - yRatio));
            float maxYShift = Math.max(0.0f,
                    ((startHeight - height) / 2.0f)
                            - dp(RETURN_HOME_WINDOW_MARGIN_DP));
            float yShift = Math.signum(rawYDelta) * interpolatedY * maxYShift;
            float top = session.startRect.top
                    + ((startHeight - height) / 2.0f) + yShift;
            float margin = dp(RETURN_HOME_WINDOW_MARGIN_DP) * progress;
            float left = session.swipeEdge == EDGE_RIGHT
                    ? session.startRect.left + margin
                    : session.startRect.right - margin - width;
            session.currentRect.set(left, top, left + width, top + height);
            session.currentCornerRadius = lerp(session.startCornerRadius,
                    session.endCornerRadius, progress);
            updateNativePreviewBlur(session, rawProgress);
            if (session.unifiedNativePreviewOwned) {
                if (!driveUnifiedNativePreviewFrame(session, false)) {
                    startUnifiedNativeCancel(
                            session, "previewFrameFailed");
                    return;
                }
            } else {
                applyPreviewTransform(session, session.currentRect,
                        session.currentCornerRadius, animationFrame);
            }
        }

        protected void freezePreviewProgress(ReturnHomeSession session, String reason) {
            if (session == null) {
                return;
            }
            session.progressFrozen = true;
            Runnable reset = () -> {
                if (!session.progressReset.compareAndSet(0, 1)
                        || session.progressAnimator == null
                        || !session.progressAnimatorStarted) {
                    return;
                }
                try {
                    // Freeze first: BackProgressAnimator.reset() can synchronously emit zero.
                    // The identity/frozen guard above updatePreviewFrame keeps that reset frame
                    // from overwriting the handoff or the verified cancel start rectangle.
                    session.progressAnimator.reset();
                    log(Log.INFO, TAG,
                            "Stopped AOSP return-home progress smoothing"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason
                                    + ", inputProgress=" + session.lastInputProgress
                                    + ", smoothedProgress="
                                    + session.lastSmoothedProgress);
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG,
                            "Failed to stop AOSP return-home progress smoothing"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason,
                            throwable);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                reset.run();
            } else {
                handler.post(reset);
            }
        }

        protected void animateCancel(ReturnHomeSession session, String reason) {
            if (session.finished.get() != 0 || currentSession != session) {
                return;
            }
            freezePreviewProgress(session, "cancel:" + reason);
            if (session.unifiedNativePreviewOwned) {
                if (session.nativeHandoffStarted
                        || session.unifiedNativeCommitPending
                        || session.nativeAnimationStarted) {
                    log(Log.WARN, TAG,
                            "Ignored return-home cancel after native commit"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason);
                    return;
                }
                startUnifiedNativeCancel(session, reason);
                return;
            }
            if (!session.previewInitialized) {
                finishSession(session, reason, false);
                return;
            }
            if (session.nativeHandoffStarted) {
                finishSession(session, reason, shouldRestorePreview(session));
                return;
            }
            if (session.cancelAnimator != null && session.cancelAnimator.isRunning()) {
                return;
            }
            RectF from = new RectF(session.currentRect);
            float fromRadius = session.currentCornerRadius;
            int fromBlur = session.previewBlurPublishedRadius;
            float fromDimming = session.previewBlurPublishedDimming;
            ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
            session.cancelAnimator = animator;
            Runnable finishGuard = () -> {
                if (currentSession == session && session.finished.get() == 0) {
                    log(Log.WARN, TAG,
                            "Forced delayed return-to-home cancel completion"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason);
                    finishSession(session, reason + "Guard", true);
                }
            };
            session.cancelFinishGuard = finishGuard;
            handler.postDelayed(finishGuard, RETURN_HOME_CANCEL_FINISH_GUARD_MS);
            animator.setDuration(RETURN_HOME_CANCEL_DURATION_MS);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(valueAnimator -> {
                if (session.finished.get() != 0 || currentSession != session) {
                    return;
                }
                float value = ((Float) valueAnimator.getAnimatedValue()).floatValue();
                session.currentRect.set(
                        lerp(from.left, session.startRect.left, value),
                        lerp(from.top, session.startRect.top, value),
                        lerp(from.right, session.startRect.right, value),
                        lerp(from.bottom, session.startRect.bottom, value));
                session.currentCornerRadius = lerp(fromRadius,
                        session.startCornerRadius, value);
                applyPreviewTransform(session, session.currentRect,
                        session.currentCornerRadius, false);
                if (session.previewBlurOwned) {
                    publishNativePreviewBlur(session,
                            Math.round(lerp(fromBlur,
                                    session.previewBlurInitialRadius, value)),
                            lerp(fromDimming,
                                    session.previewBlurInitialDimming, value),
                            "cancel");
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    finishSession(session, reason, true);
                }
            });
            log(Log.INFO, TAG, "Started return-to-home cancel recovery"
                    + ", generation=" + session.generation
                    + ", reason=" + reason
                    + ", from=" + from);
            animator.start();
        }

        protected void preparePreviewLeash(ReturnHomeSession session) {
            if (session.previewLeashPrepared) {
                return;
            }
            try {
                // Stock Launcher3 explicitly shows the prepared departing target before
                // applying progress transforms. Without this, the matrices update a hidden
                // predict_back leash while the untransformed app remains on screen.
                invokeAnyMethod(session.transaction, "show",
                        new Object[]{session.previewLeash});
                invokeAnyMethod(session.transaction, "setAnimationTransaction",
                        new Object[0]);
                session.previewLeashPrepared = true;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to prepare return-to-home preview leash"
                        + ", generation=" + session.generation, throwable);
            }
        }

        protected void prepareNativePreviewBackdrop(ReturnHomeSession session) {
            if (session.finished.get() != 0 || currentSession != session
                    || session.nativeHandoffStarted
                    || !isStandardSingleTaskReturnHome(session)) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                log(Log.WARN, TAG,
                        "Refused Xiaomi preview backdrop outside MiuiHome main Looper"
                                + ", generation=" + session.generation);
                return;
            }
            try {
                Class<?> stateManagerClass = Class.forName(
                        MIUI_HOME_STATE_MANAGER, false, classLoader);
                Object companion = readStaticField(stateManagerClass, "Companion");
                Object stateManager = invokeAnyMethod(companion,
                        "getInstance", new Object[0]);
                if (Boolean.TRUE.equals(invokeAnyMethod(stateManager,
                        "isWindowElementRunning", new Object[0]))) {
                    log(Log.INFO, TAG,
                            "Preserved running Xiaomi animation instead of preparing backdrop"
                                    + ", generation=" + session.generation);
                    return;
                }
                session.previewBackdropStateManager = stateManager;
                prepareNativePreviewShortcutLayer(session);
                prepareNativePreviewWallpaper(session);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to resolve Xiaomi predictive backdrop owner"
                                + ", generation=" + session.generation,
                        throwable);
            }
        }

        protected void prepareNativePreviewShortcutLayer(ReturnHomeSession session) {
            try {
                Class<?> deviceLevelClass = Class.forName(
                        MIUI_HOME_DEVICE_LEVEL_UTILS, false, classLoader);
                Method simpleAnimMethod = deviceLevelClass.getDeclaredMethod(
                        "isUseSimpleAnim");
                simpleAnimMethod.setAccessible(true);
                if (Boolean.TRUE.equals(simpleAnimMethod.invoke(null))) {
                    return;
                }
                Class<?> elementClass = Class.forName(
                        MIUI_HOME_SHORTCUT_MENU_LAYER_ELEMENT,
                        false, classLoader);
                Object elementCompanion = readStaticField(elementClass,
                        "Companion");
                Object element = invokeAnyMethod(elementCompanion,
                        "getInstance", new Object[0]);
                Object layerObject = invokeAnyMethod(element,
                        "getLayer", new Object[0]);
                if (!(layerObject instanceof View)) {
                    throw new IllegalStateException(
                            "Xiaomi ShortcutMenuLayer is not a View");
                }
                View layer = (View) layerObject;
                Object spring = readField(element, "mSpringAnimation");
                boolean springRunning = spring != null && Boolean.TRUE.equals(
                        invokeAnyMethod(spring, "isRunning", new Object[0]));
                Class<?> paramsClass = Class.forName(
                        MIUI_HOME_SHORTCUT_MENU_LAYER_PARAMS,
                        false, classLoader);
                Object paramsCompanion = readStaticField(paramsClass,
                        "Companion");
                Object appParams = invokeAnyMethod(paramsCompanion,
                        "getAppStateParams", new Object[]{Boolean.FALSE});
                Object homeParams = invokeAnyMethod(paramsCompanion,
                        "getHomeStateParams", new Object[]{Boolean.FALSE});
                float homeAlpha = ((Number) invokeAnyMethod(homeParams,
                        "getAlpha", new Object[0])).floatValue();
                float homeScaleX = ((Number) invokeAnyMethod(homeParams,
                        "getScaleX", new Object[0])).floatValue();
                float homeScaleY = ((Number) invokeAnyMethod(homeParams,
                        "getScaleY", new Object[0])).floatValue();
                float appAlpha = ((Number) invokeAnyMethod(appParams,
                        "getAlpha", new Object[0])).floatValue();
                float appScaleX = ((Number) invokeAnyMethod(appParams,
                        "getScaleX", new Object[0])).floatValue();
                float appScaleY = ((Number) invokeAnyMethod(appParams,
                        "getScaleY", new Object[0])).floatValue();
                if (springRunning
                        || Float.compare(layer.getAlpha(), homeAlpha) != 0
                        || Float.compare(layer.getScaleX(), homeScaleX) != 0
                        || Float.compare(layer.getScaleY(), homeScaleY) != 0
                        || appScaleX >= homeScaleX
                        || appScaleY >= homeScaleY) {
                    log(Log.INFO, TAG,
                            "Preserved active Xiaomi launcher layer during predictive preview"
                                    + ", generation=" + session.generation
                                    + ", current=" + layer.getAlpha() + "/"
                                    + layer.getScaleX() + "/" + layer.getScaleY()
                                    + ", home=" + homeAlpha + "/"
                                    + homeScaleX + "/" + homeScaleY
                                    + ", app=" + appAlpha + "/"
                                    + appScaleX + "/" + appScaleY
                                    + ", springRunning=" + springRunning);
                    return;
                }
                session.previewShortcutElement = element;
                session.previewShortcutView = layer;
                session.previewShortcutAppParams = appParams;
                session.previewShortcutHomeParams = homeParams;
                session.previewShortcutOwnedParams = appParams;
                session.previewShortcutAppAlpha = appAlpha;
                session.previewShortcutAppScaleX = appScaleX;
                session.previewShortcutAppScaleY = appScaleY;
                session.previewShortcutOwned = true;
                invokeAnyMethod(element, "setTo", new Object[]{appParams});
                Object appliedParams = readField(element, "params");
                if (readField(element, "shortcutMenuLayer") != layer
                        || appliedParams != appParams
                        || Float.compare(layer.getAlpha(), appAlpha) != 0
                        || Float.compare(layer.getScaleX(), appScaleX) != 0
                        || Float.compare(layer.getScaleY(), appScaleY) != 0) {
                    throw new IllegalStateException(
                            "Xiaomi launcher backdrop did not reach App state");
                }
                log(Log.INFO, TAG,
                        "Prepared Xiaomi predictive launcher backdrop"
                                + ", generation=" + session.generation
                                + ", home=" + homeAlpha + "/"
                                + homeScaleX + "/" + homeScaleY
                                + ", app=" + appAlpha + "/"
                                + appScaleX + "/" + appScaleY
                                + ", element=" + shortObject(element)
                                + ", view=" + shortObject(layer));
            } catch (Throwable throwable) {
                recoverNativePreviewShortcutLayer(session,
                        "prepareFailure", throwable);
            }
        }

        protected void prepareNativePreviewWallpaper(ReturnHomeSession session) {
            try {
                Class<?> elementClass = Class.forName(
                        MIUI_HOME_BASE_WALLPAPER_ELEMENT, false, classLoader);
                Object elementCompanion = readStaticField(elementClass,
                        "Companion");
                Object element = invokeAnyMethod(elementCompanion,
                        "getInstance", new Object[0]);
                if (element == null || !MIUI_HOME_SYSTEM_WALLPAPER_ELEMENT.equals(
                        element.getClass().getName())) {
                    log(Log.INFO, TAG,
                            "Preserved unsupported Xiaomi wallpaper backend"
                                    + ", generation=" + session.generation
                                    + ", element=" + shortObject(element));
                    return;
                }
                Object workspace = invokeAnyMethod(element,
                        "getMWorkspace", new Object[0]);
                if (!(workspace instanceof View)
                        || ((View) workspace).getWindowToken() == null) {
                    throw new IllegalStateException(
                            "Xiaomi wallpaper workspace is not attached");
                }
                Class<?> paramsClass = Class.forName(
                        MIUI_HOME_WALLPAPER_PARAMS, false, classLoader);
                Object paramsCompanion = readStaticField(paramsClass,
                        "Companion");
                Object appParams = invokeAnyMethod(paramsCompanion,
                        "getAppStateParams", new Object[0]);
                Object homeParams = invokeAnyMethod(paramsCompanion,
                        "getHomeStateParams", new Object[0]);
                float appZoom = ((Number) invokeAnyMethod(appParams,
                        "getZoomOut", new Object[0])).floatValue();
                float homeZoom = ((Number) invokeAnyMethod(homeParams,
                        "getZoomOut", new Object[0])).floatValue();
                if (appZoom <= homeZoom) {
                    throw new IllegalStateException(
                            "unexpected Xiaomi wallpaper App/Home scale"
                                    + ", app=" + appZoom
                                    + ", home=" + homeZoom);
                }
                session.previewWallpaperElement = element;
                session.previewWallpaperWorkspace = workspace;
                session.previewWallpaperAppParams = appParams;
                session.previewWallpaperHomeParams = homeParams;
                session.previewWallpaperAppZoom = appZoom;
                session.previewWallpaperHomeZoom = homeZoom;
                session.previewWallpaperOwned = true;
                invokePreviewWallpaperSetTo(session, appParams);
                log(Log.INFO, TAG,
                        "Prepared Xiaomi predictive wallpaper backdrop"
                                + ", generation=" + session.generation
                                + ", home=" + homeZoom
                                + ", app=" + appZoom
                                + ", element=" + shortObject(element)
                                + ", workspace=" + shortObject(workspace));
            } catch (Throwable throwable) {
                recoverNativePreviewWallpaper(session,
                        "prepareFailure", throwable);
            }
        }

        protected void prepareNativePreviewBlur(ReturnHomeSession session) {
            if (session.previewBlurOwned || session.nativeHandoffStarted
                    || session.finished.get() != 0 || currentSession != session
                    || !session.previewInitialized || session.progressFrozen) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                log(Log.WARN, TAG,
                        "Refused Xiaomi preview blur outside MiuiHome main Looper"
                                + ", generation=" + session.generation);
                return;
            }
            try {
                Class<?> stateManagerClass = Class.forName(
                        MIUI_HOME_STATE_MANAGER, false, classLoader);
                Object stateManagerCompanion = readStaticField(
                        stateManagerClass, "Companion");
                Object stateManager = invokeAnyMethod(stateManagerCompanion,
                        "getInstance", new Object[0]);
                if (Boolean.TRUE.equals(invokeAnyMethod(stateManager,
                        "isWindowElementRunning", new Object[0]))) {
                    log(Log.INFO, TAG,
                            "Preserved running Xiaomi animation instead of preparing blur"
                                    + ", generation=" + session.generation);
                    return;
                }
                Class<?> blurElementClass = Class.forName(
                        "com.miui.home.recents.anim.RecentBlurViewElement",
                        false, classLoader);
                Object blurElementCompanion = readStaticField(
                        blurElementClass, "Companion");
                Object blurElement = invokeAnyMethod(blurElementCompanion,
                        "getInstance", new Object[0]);
                Object blurView = readField(blurElement, "blurView");
                Object blurSpring = readField(blurElement, "mSpringAnimation");
                Object currentParams = readField(blurElement, "params");
                if (blurView == null || blurSpring == null) {
                    throw new IllegalStateException("Xiaomi preview blur is not bound"
                            + ", element=" + shortObject(blurElement)
                            + ", view=" + shortObject(blurView));
                }
                boolean springRunning = Boolean.TRUE.equals(invokeAnyMethod(
                        blurSpring, "isRunning", new Object[0]));
                int currentBlur = ((Number) invokeAnyMethod(blurView,
                        "getCurrentBlur", new Object[0])).intValue();
                float currentDimming = ((Number) invokeAnyMethod(blurView,
                        "getCurrentDimming", new Object[0])).floatValue();

                Class<?> blurParamsClass = Class.forName(
                        MIUI_HOME_RECENT_BLUR_PARAMS, false, classLoader);
                Object blurParamsCompanion = readStaticField(
                        blurParamsClass, "Companion");
                Object appParams = invokeAnyMethod(blurParamsCompanion,
                        "getAppStateParams", new Object[0]);
                Object homeParams = invokeAnyMethod(blurParamsCompanion,
                        "getHomeStateParams", new Object[0]);
                int homeBlur = Math.round(((Number) invokeAnyMethod(homeParams,
                        "getBlurRadius", new Object[0])).floatValue());
                float homeDimming = ((Number) invokeAnyMethod(homeParams,
                        "getDimming", new Object[0])).floatValue();
                float homeDamping = ((Number) invokeAnyMethod(homeParams,
                        "getDampingRatio", new Object[0])).floatValue();
                float homeResponse = ((Number) invokeAnyMethod(homeParams,
                        "getResponse", new Object[0])).floatValue();
                int appBlur = Math.round(((Number) invokeAnyMethod(appParams,
                        "getBlurRadius", new Object[0])).floatValue());
                float appDimming = ((Number) invokeAnyMethod(appParams,
                        "getDimming", new Object[0])).floatValue();
                float currentTargetBlur = currentParams == null ? Float.NaN
                        : ((Number) invokeAnyMethod(currentParams,
                        "getBlurRadius", new Object[0])).floatValue();
                float currentTargetDimming = currentParams == null ? Float.NaN
                        : ((Number) invokeAnyMethod(currentParams,
                        "getDimming", new Object[0])).floatValue();
                float currentTargetDamping = currentParams == null ? Float.NaN
                        : ((Number) invokeAnyMethod(currentParams,
                        "getDampingRatio", new Object[0])).floatValue();
                float currentTargetResponse = currentParams == null ? Float.NaN
                        : ((Number) invokeAnyMethod(currentParams,
                        "getResponse", new Object[0])).floatValue();
                boolean returningHomeSpring = springRunning
                        && currentParams != null
                        && Math.round(currentTargetBlur) == homeBlur
                        && Float.compare(currentTargetDimming, homeDimming) == 0
                        && Float.compare(currentTargetDamping, homeDamping) == 0
                        && Float.compare(currentTargetResponse, homeResponse) == 0
                        && currentBlur >= Math.min(homeBlur, appBlur)
                        && currentBlur <= Math.max(homeBlur, appBlur)
                        && currentDimming >= Math.min(homeDimming, appDimming)
                        && currentDimming <= Math.max(homeDimming, appDimming);
                if ((springRunning && !returningHomeSpring)
                        || (!springRunning && (currentBlur != homeBlur
                        || Float.compare(currentDimming, homeDimming) != 0))
                        || appBlur <= homeBlur) {
                    log(Log.INFO, TAG,
                            "Preserved active Xiaomi blur instead of taking preview ownership"
                                    + ", generation=" + session.generation
                                    + ", current=" + currentBlur + "/" + currentDimming
                                    + ", home=" + homeBlur + "/" + homeDimming
                                    + ", target=" + appBlur + "/" + appDimming
                                    + ", springRunning=" + springRunning
                                    + ", springTarget=" + currentTargetBlur
                                    + "/" + currentTargetDimming
                                    + "/" + currentTargetDamping
                                    + "/" + currentTargetResponse
                                    + ", returningHomeSpring="
                                    + returningHomeSpring);
                    return;
                }

                Object ownedParams = homeParams;
                if (returningHomeSpring) {
                    // A task launched from Recents leaves its launcher blur spring running
                    // toward Home after the task is already interactive. Preserving that
                    // spring lets it erase the first predictive preview. Stop only an exact
                    // Home-directed spring, keep its current visible value, and continue the
                    // gesture from there. Other native blur directions remain untouched.
                    invokeAnyMethod(blurSpring, "cancel", new Object[0]);
                    boolean stillRunning = Boolean.TRUE.equals(invokeAnyMethod(
                            blurSpring, "isRunning", new Object[0]));
                    int stoppedBlur = ((Number) invokeAnyMethod(blurView,
                            "getCurrentBlur", new Object[0])).intValue();
                    float stoppedDimming = ((Number) invokeAnyMethod(blurView,
                            "getCurrentDimming", new Object[0])).floatValue();
                    Object stoppedParams = readField(blurElement, "params");
                    Object stoppedView = readField(blurElement, "blurView");
                    Object stoppedSpring = readField(
                            blurElement, "mSpringAnimation");
                    boolean windowElementStarted = Boolean.TRUE.equals(
                            invokeAnyMethod(stateManager,
                                    "isWindowElementRunning", new Object[0]));
                    if (stillRunning || stoppedParams != currentParams
                            || stoppedView != blurView
                            || stoppedSpring != blurSpring
                            || windowElementStarted
                            || stoppedBlur != currentBlur
                            || Float.compare(stoppedDimming, currentDimming) != 0) {
                        if (!stillRunning && stoppedParams == currentParams
                                && stoppedView == blurView
                                && stoppedSpring == blurSpring
                                && !windowElementStarted) {
                            // Cancellation was the only mutation and ownership cannot be
                            // published safely. Resume the exact observed native Home target
                            // before failing closed.
                            invokeAnyMethod(blurElement, "animTo",
                                    new Object[]{currentParams});
                        }
                        throw new IllegalStateException(
                                "Xiaomi Home blur spring did not stop cleanly"
                                        + ", running=" + stillRunning
                                        + ", paramsMatch="
                                        + (stoppedParams == currentParams)
                                        + ", viewMatch="
                                        + (stoppedView == blurView)
                                        + ", springMatch="
                                        + (stoppedSpring == blurSpring)
                                        + ", windowElementStarted="
                                        + windowElementStarted
                                        + ", value=" + stoppedBlur + "/"
                                        + stoppedDimming
                                        + ", expected=" + currentBlur + "/"
                                        + currentDimming);
                    }
                    currentBlur = stoppedBlur;
                    currentDimming = stoppedDimming;
                    ownedParams = currentParams;
                    session.previewBlurInterruptedHomeSpring = true;
                }

                session.previewBlurElement = blurElement;
                session.previewBlurView = blurView;
                session.previewBlurAppParams = appParams;
                session.previewBlurHomeParams = returningHomeSpring
                        ? currentParams : homeParams;
                session.previewBlurOwnedParams = ownedParams;
                session.previewBlurInitialRadius = currentBlur;
                session.previewBlurInitialDimming = currentDimming;
                session.previewBlurTargetRadius = appBlur;
                session.previewBlurTargetDimming = appDimming;
                session.previewBlurPublishedRadius = currentBlur;
                session.previewBlurPublishedDimming = currentDimming;
                session.previewBlurOwned = true;
                // Establish a generation-owned params identity without changing the visible
                // Home values. Gesture progress below writes only the two BlurView values;
                // setTo() is never used on the hot path because it cancels Xiaomi's spring.
                if (!returningHomeSpring) {
                    invokeAnyMethod(blurElement, "setTo", new Object[]{homeParams});
                }
                int appliedBlur = ((Number) invokeAnyMethod(blurView,
                        "getCurrentBlur", new Object[0])).intValue();
                float appliedDimming = ((Number) invokeAnyMethod(blurView,
                        "getCurrentDimming", new Object[0])).floatValue();
                Object appliedParams = readField(blurElement, "params");
                if (appliedParams != ownedParams
                        || appliedBlur != currentBlur
                        || Float.compare(appliedDimming, currentDimming) != 0) {
                    throw new IllegalStateException(
                            "Xiaomi preview blur ownership did not preserve Home state"
                                    + ", applied=" + appliedBlur + "/" + appliedDimming
                                    + ", expected=" + currentBlur + "/"
                                    + currentDimming
                                    + ", paramsMatch="
                                    + (appliedParams == ownedParams));
                }
                log(Log.INFO, TAG,
                        "Prepared progressive Xiaomi predictive return-home blur"
                        + ", generation=" + session.generation
                        + ", initial=" + currentBlur + "/" + currentDimming
                        + ", commit=" + appBlur + "/" + appDimming
                        + ", interruptedHomeSpring="
                        + session.previewBlurInterruptedHomeSpring
                        + ", element=" + shortObject(blurElement)
                        + ", view=" + shortObject(blurView));
            } catch (Throwable throwable) {
                recoverNativePreviewBlurWriteFailure(session,
                        "prepareFailure", throwable);
            }
        }

        protected void updateNativePreviewBlur(ReturnHomeSession session,
                                             float smoothedProgress) {
            if (!session.previewBlurOwned || session.nativeHandoffStarted
                    || session.finished.get() != 0 || currentSession != session) {
                return;
            }
            float displayWidth = Math.max(1.0f,
                    session.previewProgressDistancePx);
            float triggerProgress = clamp01(dp(TRIGGER_THRESHOLD_DP) / displayWidth);
            float normalized = triggerProgress <= 0.0f
                    ? 1.0f : clamp01(smoothedProgress / triggerProgress);
            float blurFraction = normalized * normalized
                    * (3.0f - (2.0f * normalized));
            int radius = Math.round(lerp(session.previewBlurInitialRadius,
                    session.previewBlurTargetRadius, blurFraction));
            float dimming = lerp(session.previewBlurInitialDimming,
                    session.previewBlurTargetDimming, blurFraction);
            publishNativePreviewBlur(session, radius, dimming, "gesture");
        }

        protected void publishNativePreviewBlur(ReturnHomeSession session,
                                              int radius, float dimming,
                                              String reason) {
            if (!session.previewBlurOwned || session.finished.get() != 0
                    || currentSession != session) {
                return;
            }
            try {
                Object blurElement = session.previewBlurElement;
                Object blurView = session.previewBlurView;
                Object ownedParams = session.previewBlurOwnedParams;
                if (blurElement == null || blurView == null
                        || ownedParams == null) {
                    throw new IllegalStateException(
                            "incomplete progressive blur snapshot");
                }
                Object currentView = readField(blurElement, "blurView");
                Object currentParams = readField(blurElement, "params");
                Object blurSpring = readField(blurElement, "mSpringAnimation");
                boolean springRunning = blurSpring != null && Boolean.TRUE.equals(
                        invokeAnyMethod(blurSpring, "isRunning", new Object[0]));
                int currentBlur = ((Number) invokeAnyMethod(blurView,
                        "getCurrentBlur", new Object[0])).intValue();
                float currentDimming = ((Number) invokeAnyMethod(blurView,
                        "getCurrentDimming", new Object[0])).floatValue();
                boolean stillOwned = currentView == blurView
                        && currentParams == ownedParams
                        && !springRunning
                        && currentBlur == session.previewBlurPublishedRadius
                        && Float.compare(currentDimming,
                                session.previewBlurPublishedDimming) == 0;
                if (!stillOwned) {
                    session.previewBlurOwned = false;
                    log(Log.INFO, TAG,
                            "Preserved replacement Xiaomi blur during predictive progress"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason
                                    + ", viewMatch=" + (currentView == blurView)
                                    + ", paramsMatch="
                                    + (currentParams == ownedParams)
                                    + ", springRunning=" + springRunning
                                    + ", current=" + currentBlur + "/"
                                    + currentDimming
                                    + ", expected="
                                    + session.previewBlurPublishedRadius + "/"
                                    + session.previewBlurPublishedDimming);
                    clearNativePreviewBlurReferences(session);
                    return;
                }
                if (currentBlur == radius
                        && Float.compare(currentDimming, dimming) == 0) {
                    return;
                }
                // Xiaomi's private helper only forwards to BlurView.setBlurRadius() and
                // setDimming(); it does not replace params or touch the native spring.
                invokeAnyMethod(blurElement, "updateTargetParams",
                        new Object[]{Float.valueOf(radius), Float.valueOf(dimming)});
                int appliedBlur = ((Number) invokeAnyMethod(blurView,
                        "getCurrentBlur", new Object[0])).intValue();
                float appliedDimming = ((Number) invokeAnyMethod(blurView,
                        "getCurrentDimming", new Object[0])).floatValue();
                Object appliedView = readField(blurElement, "blurView");
                Object appliedParams = readField(blurElement, "params");
                if (appliedView != blurView || appliedParams != ownedParams
                        || appliedBlur != radius
                        || Float.compare(appliedDimming, dimming) != 0) {
                    throw new IllegalStateException(
                            "Xiaomi progressive blur write was not retained"
                                    + ", applied=" + appliedBlur + "/"
                                    + appliedDimming
                                    + ", target=" + radius + "/" + dimming
                                    + ", viewMatch=" + (appliedView == blurView)
                                    + ", paramsMatch="
                                    + (appliedParams == ownedParams));
                }
                session.previewBlurPublishedRadius = appliedBlur;
                session.previewBlurPublishedDimming = appliedDimming;
            } catch (Throwable throwable) {
                recoverNativePreviewBlurWriteFailure(session,
                        "progressFailure:" + reason, throwable);
            }
        }

        protected void recoverNativePreviewBlurWriteFailure(
                ReturnHomeSession session, String reason, Throwable cause) {
            if (!session.previewBlurOwned) {
                log(Log.WARN, TAG,
                        "Failed to prepare Xiaomi native predictive return-home blur"
                                + ", generation=" + session.generation
                                + ", reason=" + reason,
                        cause);
                return;
            }
            Object blurElement = session.previewBlurElement;
            Object blurView = session.previewBlurView;
            Object ownedParams = session.previewBlurOwnedParams;
            Object homeParams = session.previewBlurHomeParams;
            session.previewBlurOwned = false;
            try {
                if (blurElement == null || blurView == null
                        || ownedParams == null || homeParams == null) {
                    throw new IllegalStateException(
                            "incomplete progressive blur recovery snapshot",
                            cause);
                }
                Object currentView = readField(blurElement, "blurView");
                Object currentParams = readField(blurElement, "params");
                Object blurSpring = readField(blurElement, "mSpringAnimation");
                boolean springRunning = blurSpring != null && Boolean.TRUE.equals(
                        invokeAnyMethod(blurSpring, "isRunning", new Object[0]));
                if (currentView == blurView && currentParams == ownedParams
                        && !springRunning) {
                    // A module write can fail between radius and dimming. Exact identity is
                    // enough to repair that module-created partial value; provider-abort's
                    // relaxed params rule is deliberately not used here.
                    restorePreviewBlurToHome(
                            session, blurElement, homeParams);
                    log(Log.WARN, TAG,
                            "Recovered Xiaomi blur after predictive write failure"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason,
                            cause);
                } else {
                    log(Log.WARN, TAG,
                            "Preserved replacement Xiaomi blur after write failure"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason
                                    + ", viewMatch="
                                    + (currentView == blurView)
                                    + ", paramsMatch="
                                    + (currentParams == ownedParams)
                                    + ", springRunning=" + springRunning,
                            cause);
                }
            } catch (Throwable recoveryFailure) {
                log(Log.WARN, TAG,
                        "Failed to recover Xiaomi predictive return-home blur"
                                + ", generation=" + session.generation
                                + ", reason=" + reason,
                        recoveryFailure);
            } finally {
                clearNativePreviewBlurReferences(session);
            }
        }

        protected void transferNativePreviewBlur(ReturnHomeSession session,
                                               String reason) {
            if (!session.previewBlurOwned) {
                clearNativePreviewBlurReferences(session);
                return;
            }
            session.previewBlurOwned = false;
            log(Log.INFO, TAG, "Transferred predictive blur ownership to Xiaomi"
                    + ", generation=" + session.generation
                    + ", reason=" + reason
                    + ", nativeIdentity="
                    + shortObject(session.nativeAnimationIdentity));
            clearNativePreviewBlurReferences(session);
        }

        protected void completeNativePreviewBlurHandoff(ReturnHomeSession session) {
            if (!session.previewBlurOwned) {
                return;
            }
            try {
                Object blurElement = session.previewBlurElement;
                Object blurView = session.previewBlurView;
                Object appParams = session.previewBlurAppParams;
                if (blurElement == null || blurView == null || appParams == null) {
                    throw new IllegalStateException(
                            "incomplete Xiaomi preview blur handoff snapshot");
                }
                Object currentView = readField(blurElement, "blurView");
                Object currentParams = readField(blurElement, "params");
                Object blurSpring = readField(blurElement, "mSpringAnimation");
                boolean springRunning = blurSpring != null && Boolean.TRUE.equals(
                        invokeAnyMethod(blurSpring, "isRunning", new Object[0]));
                int currentBlur = ((Number) invokeAnyMethod(blurView,
                        "getCurrentBlur", new Object[0])).intValue();
                float currentDimming = ((Number) invokeAnyMethod(blurView,
                        "getCurrentDimming", new Object[0])).floatValue();
                boolean remainsAtPreviewAppState = currentBlur
                        == session.previewBlurTargetRadius
                        && Float.compare(currentDimming,
                                session.previewBlurTargetDimming) == 0;
                boolean nativeAcquired = currentView != blurView
                        || springRunning || !remainsAtPreviewAppState;
                if (nativeAcquired) {
                    transferNativePreviewBlur(session,
                            "nativeCloseReturned"
                                    + ":viewMatch=" + (currentView == blurView)
                                    + ":paramsReplaced="
                                    + (currentParams != appParams)
                                    + ":springRunning=" + springRunning
                                    + ":current=" + currentBlur + "/"
                                    + currentDimming);
                    return;
                }
                log(Log.WARN, TAG,
                        "Xiaomi CLOSE returned without taking preview blur ownership"
                                + ", generation=" + session.generation
                                + ", paramsReplaced="
                                + (currentParams != appParams)
                                + ", current=" + currentBlur + "/"
                                + currentDimming);
                // A successful provider may have repeated setTo(AppState) without starting
                // the final Home spring. Adopt that exact static params identity so later
                // native-end cleanup can restore it without weakening ordinary cancel gates.
                session.previewBlurOwnedParams = currentParams;
            } catch (Throwable throwable) {
                // Retain module ownership when takeover cannot be proven. Native end/reject
                // cleanup can then restore Home only if the exact App-state snapshot is intact.
                log(Log.WARN, TAG,
                        "Could not verify Xiaomi predictive blur handoff"
                                + ", generation=" + session.generation,
                        throwable);
            }
        }

        protected void restoreNativePreviewBlur(ReturnHomeSession session,
                                              String reason) {
            if (!session.previewBlurOwned) {
                return;
            }
            Object blurElement = session.previewBlurElement;
            Object blurView = session.previewBlurView;
            Object appParams = session.previewBlurAppParams;
            Object ownedParams = session.previewBlurOwnedParams;
            Object homeParams = session.previewBlurHomeParams;
            session.previewBlurOwned = false;
            try {
                if (blurElement == null || blurView == null
                        || appParams == null || homeParams == null) {
                    throw new IllegalStateException(
                            "incomplete Xiaomi preview blur ownership snapshot");
                }
                Object currentView = readField(blurElement, "blurView");
                Object currentParams = readField(blurElement, "params");
                Object blurSpring = readField(blurElement, "mSpringAnimation");
                boolean springRunning = blurSpring != null && Boolean.TRUE.equals(
                        invokeAnyMethod(blurSpring, "isRunning", new Object[0]));
                int currentBlur = ((Number) invokeAnyMethod(blurView,
                        "getCurrentBlur", new Object[0])).intValue();
                float currentDimming = ((Number) invokeAnyMethod(blurView,
                        "getCurrentDimming", new Object[0])).floatValue();
                boolean synchronousProviderAbort = session.nativeHandoffStarted
                        && !session.previewBlurProviderReturned;
                boolean stillOwned = currentView == blurView
                        && !springRunning
                        && (currentParams == ownedParams
                        || synchronousProviderAbort)
                        && currentBlur == session.previewBlurPublishedRadius
                        && Float.compare(currentDimming,
                                session.previewBlurPublishedDimming) == 0;
                if (!stillOwned) {
                    log(Log.INFO, TAG,
                            "Preserved replacement Xiaomi blur state"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason
                                    + ", viewMatch=" + (currentView == blurView)
                                    + ", paramsMatch="
                                    + (currentParams == ownedParams)
                                    + ", initialParamsMatch="
                                    + (currentParams == appParams)
                                    + ", synchronousProviderAbort="
                                    + synchronousProviderAbort
                                    + ", providerReturned="
                                    + session.previewBlurProviderReturned
                                    + ", springRunning=" + springRunning
                                    + ", current=" + currentBlur + "/"
                                    + currentDimming
                                    + ", expected="
                                    + session.previewBlurPublishedRadius + "/"
                                    + session.previewBlurPublishedDimming);
                    return;
                }
                restorePreviewBlurToHome(
                        session, blurElement, homeParams);
                log(Log.INFO, TAG, "Restored Xiaomi blur after predictive return"
                        + ", generation=" + session.generation
                        + ", reason=" + reason
                        + ", restored=" + session.previewBlurInitialRadius
                        + "/" + session.previewBlurInitialDimming);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to restore Xiaomi predictive return-home blur"
                                + ", generation=" + session.generation
                                + ", reason=" + reason,
                        throwable);
            } finally {
                clearNativePreviewBlurReferences(session);
            }
        }

        protected void restorePreviewBlurToHome(
                ReturnHomeSession session, Object blurElement,
                Object homeParams) throws Throwable {
            invokeAnyMethod(blurElement,
                    session.previewBlurInterruptedHomeSpring
                            ? "animTo" : "setTo",
                    new Object[]{homeParams});
        }

        protected void clearNativePreviewBlurReferences(ReturnHomeSession session) {
            session.previewBlurElement = null;
            session.previewBlurView = null;
            session.previewBlurAppParams = null;
            session.previewBlurOwnedParams = null;
            session.previewBlurHomeParams = null;
            session.previewBlurInterruptedHomeSpring = false;
        }

        protected void prepareNativePreviewBackdropForCommit(
                ReturnHomeSession session) {
            if (session.previewShortcutOwned) {
                try {
                    Object element = session.previewShortcutElement;
                    View view = session.previewShortcutView;
                    Object appParams = session.previewShortcutAppParams;
                    Object spring = readField(element, "mSpringAnimation");
                    boolean springRunning = spring != null && Boolean.TRUE.equals(
                            invokeAnyMethod(spring, "isRunning", new Object[0]));
                    boolean stillOwned = readField(element,
                            "shortcutMenuLayer") == view
                            && readField(element, "params")
                            == session.previewShortcutOwnedParams
                            && !springRunning
                            && Float.compare(view.getAlpha(),
                            session.previewShortcutAppAlpha) == 0
                            && Float.compare(view.getScaleX(),
                            session.previewShortcutAppScaleX) == 0
                            && Float.compare(view.getScaleY(),
                            session.previewShortcutAppScaleY) == 0;
                    if (!stillOwned) {
                        transferNativePreviewShortcutLayer(session,
                                "commitOwnershipLost");
                    } else {
                        // The predictive preview established the exact Xiaomi App state.
                        // Reissuing setTo() at release is numerically redundant but creates a
                        // separate launcher-View command immediately before native CLOSE starts.
                        // Keep the proven state in place and let Xiaomi's animTo(Home) acquire it.
                        log(Log.INFO, TAG,
                                "Retained predictive launcher App state at commit"
                                        + ", generation="
                                        + session.generation
                                        + ", params="
                                        + shortObject(appParams));
                    }
                } catch (Throwable throwable) {
                    recoverNativePreviewShortcutLayer(session,
                            "commitFailure", throwable);
                }
            }
            if (session.previewWallpaperOwned) {
                try {
                    if (session.previewWallpaperElement == null
                            || session.previewWallpaperWorkspace == null
                            || session.previewWallpaperAppParams == null
                            || invokeAnyMethod(
                            session.previewWallpaperElement,
                            "getMWorkspace", new Object[0])
                            != session.previewWallpaperWorkspace
                            || readBackdropWindowToken(
                            session.previewWallpaperWorkspace) == null) {
                        throw new IllegalStateException(
                                "incomplete wallpaper commit snapshot");
                    }
                    // The preview-time setTo(App) command already reached the exact attached
                    // wallpaper token. Do not reset that Surface command at release; classify
                    // Xiaomi's following animTo(Home) as continuation of the established state.
                    session.previewWallpaperNativeAppSetObserved = true;
                    log(Log.INFO, TAG,
                            "Retained predictive wallpaper App state at commit"
                                    + ", generation="
                                    + session.generation
                                    + ", zoom="
                                    + session.previewWallpaperAppZoom);
                } catch (Throwable throwable) {
                    recoverNativePreviewWallpaper(session,
                            "commitFailure", throwable);
                }
            }
        }

        protected void completeNativePreviewBackdropHandoff(
                ReturnHomeSession session) {
            session.previewBackdropProviderReturned = true;
            if (session.previewShortcutOwned) {
                try {
                    Object element = session.previewShortcutElement;
                    View view = session.previewShortcutView;
                    Object spring = readField(element, "mSpringAnimation");
                    boolean springRunning = spring != null && Boolean.TRUE.equals(
                            invokeAnyMethod(spring, "isRunning", new Object[0]));
                    Object currentParams = readField(element, "params");
                    boolean remainsAtPreparedAppState = view != null
                            && Float.compare(view.getAlpha(),
                            session.previewShortcutAppAlpha) == 0
                            && Float.compare(view.getScaleX(),
                            session.previewShortcutAppScaleX) == 0
                            && Float.compare(view.getScaleY(),
                            session.previewShortcutAppScaleY) == 0;
                    boolean nativeAcquired = readField(element,
                            "shortcutMenuLayer") != view
                            || currentParams
                            != session.previewShortcutOwnedParams
                            || springRunning
                            || !remainsAtPreparedAppState;
                    if (nativeAcquired) {
                        transferNativePreviewShortcutLayer(session,
                                "nativeCloseReturned"
                                        + ":paramsReplaced="
                                        + (currentParams
                                        != session.previewShortcutOwnedParams)
                                        + ":springRunning=" + springRunning);
                    } else {
                        log(Log.WARN, TAG,
                                "Xiaomi CLOSE returned without taking launcher backdrop"
                                        + ", generation=" + session.generation);
                    }
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG,
                            "Could not verify Xiaomi launcher backdrop handoff"
                                    + ", generation=" + session.generation,
                            throwable);
                }
            }
            if (session.previewWallpaperOwned
                    && session.previewWallpaperNativeAppSetObserved
                    && session.previewWallpaperNativeHomeAnimObserved) {
                transferNativePreviewWallpaper(session,
                        "nativeCommandsObserved");
            }
        }

        protected void restoreNativePreviewBackdrop(ReturnHomeSession session,
                                                  String reason) {
            restoreNativePreviewShortcutLayer(session, reason);
            restoreNativePreviewWallpaper(session, reason);
            session.previewBackdropStateManager = null;
        }

        protected void restoreNativePreviewShortcutLayer(
                ReturnHomeSession session, String reason) {
            if (!session.previewShortcutOwned) {
                clearNativePreviewShortcutReferences(session);
                return;
            }
            Object element = session.previewShortcutElement;
            View view = session.previewShortcutView;
            Object ownedParams = session.previewShortcutOwnedParams;
            Object homeParams = session.previewShortcutHomeParams;
            session.previewShortcutOwned = false;
            try {
                Object spring = readField(element, "mSpringAnimation");
                boolean springRunning = spring != null && Boolean.TRUE.equals(
                        invokeAnyMethod(spring, "isRunning", new Object[0]));
                boolean stillOwned = readField(element,
                        "shortcutMenuLayer") == view
                        && readField(element, "params") == ownedParams
                        && !springRunning
                        && Float.compare(view.getAlpha(),
                        session.previewShortcutAppAlpha) == 0
                        && Float.compare(view.getScaleX(),
                        session.previewShortcutAppScaleX) == 0
                        && Float.compare(view.getScaleY(),
                        session.previewShortcutAppScaleY) == 0;
                if (!stillOwned) {
                    log(Log.INFO, TAG,
                            "Preserved replacement Xiaomi launcher backdrop"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason);
                    return;
                }
                invokeAnyMethod(element, "setTo", new Object[]{homeParams});
                log(Log.INFO, TAG,
                        "Restored Xiaomi launcher backdrop after predictive return"
                                + ", generation=" + session.generation
                                + ", reason=" + reason);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to restore Xiaomi predictive launcher backdrop"
                                + ", generation=" + session.generation
                                + ", reason=" + reason,
                        throwable);
            } finally {
                clearNativePreviewShortcutReferences(session);
            }
        }

        protected void recoverNativePreviewShortcutLayer(
                ReturnHomeSession session, String reason, Throwable cause) {
            if (!session.previewShortcutOwned) {
                log(Log.WARN, TAG,
                        "Failed to prepare Xiaomi predictive launcher backdrop"
                                + ", generation=" + session.generation
                                + ", reason=" + reason,
                        cause);
                clearNativePreviewShortcutReferences(session);
                return;
            }
            restoreNativePreviewShortcutLayer(session,
                    "recovery:" + reason);
        }

        protected void transferNativePreviewShortcutLayer(
                ReturnHomeSession session, String reason) {
            if (session.previewShortcutOwned) {
                log(Log.INFO, TAG,
                        "Transferred predictive launcher backdrop to Xiaomi"
                                + ", generation=" + session.generation
                                + ", reason=" + reason);
            }
            session.previewShortcutOwned = false;
            clearNativePreviewShortcutReferences(session);
        }

        protected void clearNativePreviewShortcutReferences(
                ReturnHomeSession session) {
            session.previewShortcutElement = null;
            session.previewShortcutView = null;
            session.previewShortcutAppParams = null;
            session.previewShortcutHomeParams = null;
            session.previewShortcutOwnedParams = null;
        }

        protected void invokePreviewWallpaperSetTo(
                ReturnHomeSession session, Object params) throws Throwable {
            session.previewWallpaperModuleCommandDepth++;
            try {
                invokeAnyMethod(session.previewWallpaperElement,
                        "setTo", new Object[]{params});
            } finally {
                session.previewWallpaperModuleCommandDepth--;
            }
        }

        void onWallpaperCommand(Object element, Object params, boolean animated) {
            ReturnHomeSession session = currentSession;
            if (session == null || session.finished.get() != 0
                    || !session.previewWallpaperOwned
                    || session.previewWallpaperElement != element
                    || session.previewWallpaperModuleCommandDepth > 0) {
                return;
            }
            try {
                float zoom = ((Number) invokeAnyMethod(params,
                        "getZoomOut", new Object[0])).floatValue();
                if (session.nativeHandoffStarted
                        && !session.previewBackdropProviderReturned) {
                    if (!animated && Float.compare(zoom,
                            session.previewWallpaperAppZoom) == 0) {
                        session.previewWallpaperNativeAppSetObserved = true;
                        log(Log.INFO, TAG,
                                "Observed Xiaomi native wallpaper App handoff"
                                        + ", generation=" + session.generation
                                        + ", zoom=" + zoom);
                        return;
                    }
                    if (animated
                            && session.previewWallpaperNativeAppSetObserved
                            && Float.compare(zoom,
                            session.previewWallpaperHomeZoom) == 0) {
                        session.previewWallpaperNativeHomeAnimObserved = true;
                        log(Log.INFO, TAG,
                                "Observed Xiaomi native wallpaper Home continuation"
                                        + ", generation=" + session.generation
                                        + ", zoom=" + zoom);
                        return;
                    }
                }
                transferNativePreviewWallpaper(session,
                        "externalCommand:animated=" + animated
                                + ":zoom=" + zoom);
            } catch (Throwable throwable) {
                transferNativePreviewWallpaper(session,
                        "unreadableExternalCommand");
                log(Log.WARN, TAG,
                        "Could not classify Xiaomi wallpaper replacement"
                                + ", generation=" + session.generation,
                        throwable);
            }
        }

        protected void restoreNativePreviewWallpaper(
                ReturnHomeSession session, String reason) {
            if (!session.previewWallpaperOwned) {
                clearNativePreviewWallpaperReferences(session);
                return;
            }
            Object stateManager = session.previewBackdropStateManager;
            Object element = session.previewWallpaperElement;
            Object workspace = session.previewWallpaperWorkspace;
            Object homeParams = session.previewWallpaperHomeParams;
            session.previewWallpaperOwned = false;
            try {
                boolean running = stateManager != null && Boolean.TRUE.equals(
                        invokeAnyMethod(stateManager,
                                "isWindowElementRunning", new Object[0]));
                Object currentWorkspace = invokeAnyMethod(element,
                        "getMWorkspace", new Object[0]);
                if (running || currentWorkspace != workspace
                        || readBackdropWindowToken(workspace) == null) {
                    log(Log.INFO, TAG,
                            "Preserved replacement Xiaomi wallpaper state"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason
                                    + ", running=" + running
                                    + ", workspaceMatch="
                                    + (currentWorkspace == workspace));
                    return;
                }
                session.previewWallpaperOwned = true;
                invokePreviewWallpaperSetTo(session, homeParams);
                session.previewWallpaperOwned = false;
                log(Log.INFO, TAG,
                        "Restored Xiaomi wallpaper after predictive return"
                                + ", generation=" + session.generation
                                + ", reason=" + reason
                                + ", zoom="
                                + session.previewWallpaperHomeZoom);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to restore Xiaomi predictive wallpaper"
                                + ", generation=" + session.generation
                                + ", reason=" + reason,
                        throwable);
            } finally {
                session.previewWallpaperOwned = false;
                clearNativePreviewWallpaperReferences(session);
            }
        }

        protected void recoverNativePreviewWallpaper(
                ReturnHomeSession session, String reason, Throwable cause) {
            if (!session.previewWallpaperOwned) {
                log(Log.WARN, TAG,
                        "Failed to prepare Xiaomi predictive wallpaper"
                                + ", generation=" + session.generation
                                + ", reason=" + reason,
                        cause);
                clearNativePreviewWallpaperReferences(session);
                return;
            }
            log(Log.WARN, TAG,
                    "Recovering Xiaomi predictive wallpaper"
                            + ", generation=" + session.generation
                            + ", reason=" + reason,
                    cause);
            restoreNativePreviewWallpaper(session,
                    "recovery:" + reason);
        }

        protected void transferNativePreviewWallpaper(
                ReturnHomeSession session, String reason) {
            if (session.previewWallpaperOwned) {
                log(Log.INFO, TAG,
                        "Transferred predictive wallpaper to Xiaomi"
                                + ", generation=" + session.generation
                                + ", reason=" + reason);
            }
            session.previewWallpaperOwned = false;
            clearNativePreviewWallpaperReferences(session);
        }

        protected void clearNativePreviewWallpaperReferences(
                ReturnHomeSession session) {
            session.previewWallpaperElement = null;
            session.previewWallpaperWorkspace = null;
            session.previewWallpaperAppParams = null;
            session.previewWallpaperHomeParams = null;
            session.previewWallpaperModuleCommandDepth = 0;
        }

        protected IBinder readBackdropWindowToken(Object workspace) {
            return workspace instanceof View
                    ? ((View) workspace).getWindowToken() : null;
        }

        protected void applyPreviewTransform(ReturnHomeSession session, RectF targetRect,
                                           float cornerRadius,
                                           boolean tagFrameTimeline) {
            SurfaceControl leash = session.previewLeash;
            if (leash == null || !leash.isValid()
                    || session.startRect.isEmpty() || targetRect.isEmpty()) {
                return;
            }
            try {
                float scaleX = targetRect.width() / session.startRect.width();
                float scaleY = targetRect.height() / session.startRect.height();
                session.matrix.reset();
                session.matrix.setScale(scaleX, scaleY);
                session.matrix.postTranslate(
                        targetRect.left - (session.startRect.left * scaleX),
                        targetRect.top - (session.startRect.top * scaleY));
                invokeAnyMethod(session.transaction, "setMatrix",
                        new Object[]{leash, session.matrix, session.matrixValues});
                invokeAnyMethod(session.transaction, "setWindowCrop",
                        new Object[]{leash, session.startRect});
                invokeAnyMethod(session.transaction, "setCornerRadius",
                        new Object[]{leash, Float.valueOf(
                                Math.max(0.0f, cornerRadius))});
                if (tagFrameTimeline) {
                    try {
                        Object vsyncValue = invokeAnyMethod(
                                Choreographer.getInstance(), "getVsyncId", new Object[0]);
                        if (vsyncValue instanceof Number
                                && ((Number) vsyncValue).longValue() > 0L) {
                            invokeAnyMethod(session.transaction,
                                    "setFrameTimelineVsync",
                                    new Object[]{Long.valueOf(
                                            ((Number) vsyncValue).longValue())});
                        }
                    } catch (Throwable throwable) {
                        if (!session.frameTimelineTagFailed) {
                            session.frameTimelineTagFailed = true;
                            log(Log.WARN, TAG,
                                    "Failed to tag return-home animation frame"
                                            + ", generation=" + session.generation,
                                    throwable);
                        }
                    }
                }
                session.transaction.apply();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to apply return-to-home preview transform"
                        + ", generation=" + session.generation, throwable);
            }
        }

        void markUnifiedCommitAnimToEntering(
                Object windowElement, Object params) throws Throwable {
            ReturnHomeSession session = currentSession;
            if (Looper.myLooper() != Looper.getMainLooper()
                    || session == null || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || !session.unifiedNativeCommitPending
                    || !session.unifiedNativeCommitReady.get()
                    || session.unifiedNativeCleanupVerified
                    || session.nativeWindowElement != windowElement
                    || params == null) {
                return;
            }
            UnifiedNativeStandardCommitToken standardToken =
                    session.unifiedNativeStandardCommit;
            if (standardToken != null
                    && standardToken == session.unifiedNativeStandardCommit
                    && standardToken.session == session
                    && standardToken.generation == session.generation
                    && standardToken.windowElement == windowElement
                    && standardToken.animationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && session.unifiedNativeCommitTransition == null
                    && standardToken.phase.get()
                    == UnifiedNativeStandardCommitToken.PHASE_PENDING
                    && standardToken.animParams.compareAndSet(null, params)
                    && standardToken.phase.compareAndSet(
                    UnifiedNativeStandardCommitToken.PHASE_PENDING,
                    UnifiedNativeStandardCommitToken.PHASE_ENTERING)) {
                standardToken.animToEpoch = beginUnifiedAnimToEpoch(
                        session, "standardCommit");
                verifyUnifiedStateManagerListenerGate(
                        session, true, "standardCommitEntry");
                log(Log.INFO, TAG,
                        "Recorded Xiaomi standard animTo entry"
                                + ", generation=" + session.generation
                                + ", signalAttempt="
                                + standardToken.signal.attempt
                                + ", animToEpoch="
                                + standardToken.animToEpoch
                                + ", animationIdentity="
                                + shortObject(
                                standardToken.animationIdentity));
                return;
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            if (transition == null
                    || transition != session.unifiedNativeCommitTransition
                    || transition.session != session
                    || transition.generation != session.generation
                    || transition.windowElement != windowElement
                    || transition.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || transition.phase.get()
                    != UnifiedNativeCommitTransitionToken.PHASE_PENDING
                    || !transition.animParams.compareAndSet(null, params)
                    || !transition.phase.compareAndSet(
                    UnifiedNativeCommitTransitionToken.PHASE_PENDING,
                    UnifiedNativeCommitTransitionToken.PHASE_ENTERING)) {
                return;
            }
            transition.animToEpoch = beginUnifiedAnimToEpoch(
                    session, "transitionCommit");
            verifyUnifiedStateManagerListenerGate(
                    session, true, "transitionCommitEntry");
            log(Log.INFO, TAG,
                    "Recorded Xiaomi transition animTo entry"
                            + ", generation=" + session.generation
                            + ", debugId="
                            + transition.transitionDebugId
                            + ", animToEpoch="
                            + transition.animToEpoch
                            + ", animationIdentity="
                            + shortObject(
                            transition.animationIdentity));
        }

        boolean onUnifiedCommitAnimToEntryFailed(
                Object windowElement, Object params,
                Throwable failure) {
            ReturnHomeSession session = currentSession;
            if (session == null || params == null
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.nativeWindowElement != windowElement) {
                return false;
            }
            Object ownerToken = null;
            long epoch = 0L;
            UnifiedNativeStandardCommitToken standard =
                    session.unifiedNativeStandardCommit;
            if (standard != null
                    && standard.animParams.get() == params
                    && standard.animToEpoch > 0L) {
                ownerToken = standard;
                epoch = standard.animToEpoch;
            } else {
                UnifiedNativeCommitTransitionToken transition =
                        session.unifiedNativeCommitTransition;
                if (transition != null
                        && transition.animParams.get() == params
                        && transition.animToEpoch > 0L) {
                    ownerToken = transition;
                    epoch = transition.animToEpoch;
                }
            }
            return epoch > 0L
                    && publishUnifiedNativeTerminalFailure(
                    session, params, ownerToken, epoch,
                    false, "animToEntryGateFailure", failure);
        }

        protected void verifyUnifiedStateManagerListenerGate(
                ReturnHomeSession session, boolean disabled,
                String reason) throws Throwable {
            Throwable accessorFailure = null;
            try {
                invokeAnyMethod(session.nativeWindowElement,
                        "setMDisableStateManagerListener",
                        new Object[]{Boolean.valueOf(disabled)});
                Object actual = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getMDisableStateManagerListener",
                        new Object[0]);
                if (actual instanceof Boolean
                        && ((Boolean) actual).booleanValue() == disabled) {
                    return;
                }
                accessorFailure = new IllegalStateException(
                        "Xiaomi listener-gate accessor write did not stick"
                                + ", requestedDisabled=" + disabled
                                + ", actual=" + shortObject(actual));
            } catch (Throwable throwable) {
                accessorFailure = throwable;
            }
            try {
                writeField(session.nativeWindowElement,
                        "mDisableStateManagerListener",
                        Boolean.valueOf(disabled));
                Object actual = readField(
                        session.nativeWindowElement,
                        "mDisableStateManagerListener");
                if (actual instanceof Boolean
                        && ((Boolean) actual).booleanValue() == disabled) {
                    log(Log.WARN, TAG,
                            "Used exact Xiaomi listener-gate field fallback"
                                    + ", generation="
                                    + session.generation
                                    + ", requestedDisabled="
                                    + disabled
                                    + ", reason=" + reason,
                            accessorFailure);
                    return;
                }
                throw new IllegalStateException(
                        "Xiaomi listener-gate field write did not stick"
                                + ", requestedDisabled=" + disabled
                                + ", actual=" + shortObject(actual));
            } catch (Throwable fieldFailure) {
                if (accessorFailure != null) {
                    fieldFailure.addSuppressed(accessorFailure);
                }
                throw new IllegalStateException(
                        "Could not write Xiaomi StateManager listener gate"
                                + ", requestedDisabled=" + disabled
                                + ", reason=" + reason,
                        fieldFailure);
            }
        }

        protected boolean publishUnifiedNativeTerminalFailure(
                ReturnHomeSession session, Object animParams,
                Object ownerToken, long animToEpoch, boolean cancel,
                String reason, Throwable failure) {
            return publishUnifiedNativeTerminalFailure(
                    session, animParams, ownerToken,
                    animToEpoch, cancel, false, false,
                    reason, failure);
        }

        protected boolean publishUnifiedNativeTerminalFailure(
                ReturnHomeSession session, Object animParams,
                Object ownerToken, long animToEpoch, boolean cancel,
                boolean pendingCommitTermination,
                boolean pendingCommitStateCleared,
                String reason, Throwable failure) {
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified) {
                return false;
            }
            UnifiedNativeTerminalFailureSnapshot snapshot =
                    new UnifiedNativeTerminalFailureSnapshot(
                            session, animParams, ownerToken,
                            animToEpoch, cancel,
                            pendingCommitTermination,
                            pendingCommitStateCleared,
                            reason, failure);
            while (true) {
                UnifiedNativeTerminalFailureSnapshot existing =
                        session.unifiedNativeTerminalFailure.get();
                if (existing != null
                        && existing.phase.get()
                        != UnifiedNativeTerminalFailureSnapshot.PHASE_INVALID) {
                    boolean sameFailure = existing.session == session
                            && existing.windowElement
                            == session.nativeWindowElement
                            && existing.animationIdentity
                            == session.unifiedNativeAnimationIdentity
                            && existing.animParams == animParams
                            && existing.ownerToken == ownerToken
                            && existing.animToEpoch == animToEpoch
                            && existing.cancel == cancel
                            && existing.pendingCommitTermination
                            == pendingCommitTermination
                            && existing.pendingCommitStateCleared
                            == pendingCommitStateCleared;
                    if (sameFailure) {
                        return true;
                    }
                    if (!existing.phase.compareAndSet(
                            UnifiedNativeTerminalFailureSnapshot.PHASE_PENDING,
                            UnifiedNativeTerminalFailureSnapshot.PHASE_INVALID)) {
                        return false;
                    }
                    if (!session.unifiedNativeTerminalFailure
                            .compareAndSet(existing, snapshot)) {
                        continue;
                    }
                    break;
                }
                if (session.unifiedNativeTerminalFailure.compareAndSet(
                        existing, snapshot)) {
                    if (existing != null) {
                        existing.phase.set(
                                UnifiedNativeTerminalFailureSnapshot.PHASE_INVALID);
                    }
                    break;
                }
            }
            handler.post(() -> handleUnifiedNativeTerminalFailure(snapshot));
            log(Log.ERROR, TAG,
                    "Published guarded Xiaomi native-owner terminal failure"
                            + ", generation=" + session.generation
                            + ", animToEpoch=" + animToEpoch
                            + ", cancel=" + cancel
                            + ", reason=" + reason,
                    failure);
            return true;
        }

        protected void invalidatePendingUnifiedTerminalFailure(
                ReturnHomeSession session, String reason) {
            if (session == null) {
                return;
            }
            while (true) {
                UnifiedNativeTerminalFailureSnapshot snapshot =
                        session.unifiedNativeTerminalFailure.get();
                if (snapshot == null
                        || snapshot.phase.get()
                        != UnifiedNativeTerminalFailureSnapshot.PHASE_PENDING) {
                    return;
                }
                if (!snapshot.phase.compareAndSet(
                        UnifiedNativeTerminalFailureSnapshot.PHASE_PENDING,
                        UnifiedNativeTerminalFailureSnapshot.PHASE_INVALID)) {
                    continue;
                }
                session.unifiedNativeTerminalFailure.compareAndSet(
                        snapshot, null);
                log(Log.INFO, TAG,
                        "Invalidated queued Xiaomi terminal failure before new owner epoch"
                                + ", generation="
                                + session.generation
                                + ", oldAnimToEpoch="
                                + snapshot.animToEpoch
                                + ", reason=" + reason);
                return;
            }
        }

        protected boolean isExactUnifiedNativeTerminalFailure(
                UnifiedNativeTerminalFailureSnapshot snapshot,
                Object currentElement, Object currentIdentity) {
            ReturnHomeSession session = snapshot == null
                    ? null : snapshot.session;
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || session.cleaned.get() != 0
                    || snapshot.generation != session.generation
                    || snapshot.windowElement
                    != session.nativeWindowElement
                    || snapshot.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || currentElement != snapshot.windowElement
                    || currentIdentity != snapshot.animationIdentity
                    || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified
                    || session.unifiedNativeTerminalFailure.get()
                    != snapshot) {
                return false;
            }
            Object targetSet;
            try {
                targetSet = invokeAnyMethod(
                        snapshot.windowElement,
                        "getRemoteTargetSet", new Object[0]);
                if (resolveUnifiedNativeClosingTarget(
                        session, targetSet) == null) {
                    return false;
                }
            } catch (Throwable throwable) {
                return false;
            }
            if (snapshot.animToEpoch == 0L) {
                boolean idlePreview = !session.nativeHandoffStarted
                        && !session.unifiedNativeCommitPending
                        && !session.nativeAnimationStarted;
                boolean pendingCommitTermination =
                        snapshot.pendingCommitTermination
                                && snapshot.cancel
                                && !session.nativeAnimationStarted
                                && (snapshot.pendingCommitStateCleared
                                ? (!session.nativeHandoffStarted
                                && !session.unifiedNativeCommitPending
                                && session.unifiedNativeCancelPending)
                                : (session.nativeHandoffStarted
                                && session.unifiedNativeCommitPending));
                return snapshot.animParams == null
                        && snapshot.ownerToken == null
                        && (idlePreview
                        || pendingCommitTermination);
            }
            if (snapshot.animToEpoch
                    != session.unifiedNativeActiveAnimToEpoch
                    || snapshot.animParams == null) {
                return false;
            }
            if (snapshot.cancel) {
                return session.unifiedNativeCancelPending
                        && session.unifiedNativeCancelAnimParams
                        == snapshot.animParams
                        && session.unifiedNativeCancelAnimToEpoch
                        == snapshot.animToEpoch
                        && snapshot.ownerToken == snapshot.animParams;
            }
            if (snapshot.ownerToken
                    instanceof UnifiedNativeConfiguredAnimToSnapshot) {
                UnifiedNativeConfiguredAnimToSnapshot configured =
                        (UnifiedNativeConfiguredAnimToSnapshot)
                                snapshot.ownerToken;
                return configured.session == session
                        && configured
                        == session.unifiedNativeConfiguredAnimTo.get()
                        && configured.animParams
                        == snapshot.animParams
                        && configured.animToEpoch
                        == snapshot.animToEpoch
                        && !configured.cancel
                        && session.nativeAnimationStarted
                        && session.nativeContinuationVerified
                        && session.nativeAnimationIdentity
                        == snapshot.animationIdentity
                        && configured.animationType.equals(
                        session.nativeAnimationType);
            }
            if (snapshot.ownerToken
                    instanceof UnifiedNativeStandardCommitToken) {
                UnifiedNativeStandardCommitToken token =
                        (UnifiedNativeStandardCommitToken)
                                snapshot.ownerToken;
                return token.session == session
                        && token == session.unifiedNativeStandardCommit
                        && token.animParams.get()
                        == snapshot.animParams
                        && token.animToEpoch
                        == snapshot.animToEpoch
                        && isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                        session, token);
            }
            if (snapshot.ownerToken
                    instanceof UnifiedNativeCommitTransitionToken) {
                UnifiedNativeCommitTransitionToken token =
                        (UnifiedNativeCommitTransitionToken)
                                snapshot.ownerToken;
                return token.session == session
                        && token
                        == session.unifiedNativeCommitTransition
                        && token.animParams.get()
                        == snapshot.animParams
                        && token.animToEpoch
                        == snapshot.animToEpoch
                        && isUnifiedCommitTransitionAtAnimToBoundary(
                        session, token);
            }
            return false;
        }

        protected void handleUnifiedNativeTerminalFailure(
                UnifiedNativeTerminalFailureSnapshot snapshot) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post(() ->
                        handleUnifiedNativeTerminalFailure(snapshot));
                return;
            }
            if (snapshot == null
                    || !snapshot.phase.compareAndSet(
                    UnifiedNativeTerminalFailureSnapshot.PHASE_PENDING,
                    UnifiedNativeTerminalFailureSnapshot.PHASE_CANCELLING)) {
                return;
            }
            ReturnHomeSession session = snapshot.session;
            Object currentElement = null;
            Object currentIdentity = null;
            try {
                currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
                currentIdentity = invokeAnyMethod(
                        snapshot.windowElement,
                        "getAnimSymbol", new Object[0]);
            } catch (Throwable throwable) {
                snapshot.failure.addSuppressed(throwable);
            }
            if (!isExactUnifiedNativeTerminalFailure(
                    snapshot, currentElement, currentIdentity)) {
                snapshot.phase.set(
                        UnifiedNativeTerminalFailureSnapshot.PHASE_INVALID);
                log(Log.ERROR, TAG,
                        "Rejected stale Xiaomi native-owner terminal failure"
                                + ", generation="
                                + snapshot.generation
                                + ", animToEpoch="
                                + snapshot.animToEpoch
                                + ", sameSession="
                                + (currentSession == session)
                                + ", sameElement="
                                + (currentElement
                                == snapshot.windowElement)
                                + ", sameIdentity="
                                + (currentIdentity
                                == snapshot.animationIdentity)
                                + ", reason=" + snapshot.reason,
                        snapshot.failure);
                return;
            }

            Runnable nativeTimeout = session.nativeTimeout;
            if (nativeTimeout != null) {
                handler.removeCallbacks(nativeTimeout);
            }
            Runnable cancelTimeout = session.unifiedNativeCancelTimeout;
            if (cancelTimeout != null) {
                handler.removeCallbacks(cancelTimeout);
            }
            session.nativeTimeout = null;
            session.unifiedNativeCancelTimeout = null;
            UnifiedNativeStandardCommitToken standard =
                    session.unifiedNativeStandardCommit;
            if (standard != null) {
                standard.phase.set(
                        UnifiedNativeStandardCommitToken.PHASE_INVALID);
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            if (transition != null) {
                transition.phase.set(
                        UnifiedNativeCommitTransitionToken.PHASE_INVALID);
            }
            UnifiedNativeProvisionalCommitSnapshot provisional =
                    session.unifiedNativeProvisionalCommit.getAndSet(null);
            if (provisional != null) {
                provisional.phase.set(
                        UnifiedNativeProvisionalCommitSnapshot.PHASE_INVALID);
            }
            session.unifiedNativeConfiguredAnimTo.set(null);
            invalidateUnifiedPendingInterruption(
                    session, "terminalFailure:" + snapshot.reason);
            session.unifiedNativeStandardCommit = null;
            session.unifiedNativeCommitTransition = null;
            session.unifiedNativeCommitPending = false;
            session.unifiedNativeCancelPending = false;
            session.unifiedNativeCancelRetargeted = false;
            boolean toHome = !snapshot.cancel
                    && session.nativeHandoffStarted;
            try {
                verifyUnifiedStateManagerListenerGate(
                        session, false,
                        "terminalFailure:" + snapshot.reason);
            } catch (Throwable throwable) {
                snapshot.failure.addSuppressed(throwable);
            }

            Class<?> callbackClass;
            Object callback;
            try {
                callbackClass = Class.forName(
                        MIUI_HOME_SHELL_TRANSITION_CALLBACK,
                        false, classLoader);
                callback = Proxy.newProxyInstance(
                        callbackClass.getClassLoader(),
                        new Class<?>[]{callbackClass},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return headlessUpdaterResult(
                                        proxy, method, args);
                            }
                            if ("onFinish".equals(method.getName())) {
                                handler.post(() ->
                                        completeUnifiedNativeTerminalFailure(
                                                snapshot,
                                                "nativeCancelCallback"));
                            }
                            return primitiveDefaultValue(
                                    method.getReturnType());
                        });
                invokeAnyMethod(snapshot.windowElement,
                        "cancelAnim", new Object[]{
                                "MiuiBackGestureHook:" + snapshot.reason,
                                Boolean.FALSE, null,
                                Boolean.valueOf(toHome), callback});
                Runnable guard = () ->
                        completeUnifiedNativeTerminalFailure(
                                snapshot, "nativeCancelGuard");
                snapshot.cleanupGuard = guard;
                handler.postDelayed(guard,
                        RETURN_HOME_NATIVE_TIMEOUT_MS);
                log(Log.ERROR, TAG,
                        "Issued exact Xiaomi native cancel for terminal failure"
                                + ", generation="
                                + session.generation
                                + ", animToEpoch="
                                + snapshot.animToEpoch
                                + ", toHome=" + toHome
                                + ", reason=" + snapshot.reason,
                        snapshot.failure);
            } catch (Throwable throwable) {
                snapshot.failure.addSuppressed(throwable);
                try {
                    invokeAnyMethod(snapshot.windowElement,
                            "finishTransition", new Object[]{
                                    Boolean.valueOf(toHome),
                                    Boolean.FALSE});
                } catch (Throwable finishFailure) {
                    snapshot.failure.addSuppressed(finishFailure);
                }
                handler.post(() ->
                        completeUnifiedNativeTerminalFailure(
                                snapshot,
                                "nativeCancelInvocationFailed"));
                log(Log.ERROR, TAG,
                        "Xiaomi native terminal cancel invocation failed"
                                + ", generation="
                                + session.generation
                                + ", animToEpoch="
                                + snapshot.animToEpoch
                                + ", reason=" + snapshot.reason,
                        snapshot.failure);
            }
        }

        protected void completeUnifiedNativeTerminalFailure(
                UnifiedNativeTerminalFailureSnapshot snapshot,
                String completionReason) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post(() ->
                        completeUnifiedNativeTerminalFailure(
                                snapshot, completionReason));
                return;
            }
            if (snapshot == null
                    || !snapshot.phase.compareAndSet(
                    UnifiedNativeTerminalFailureSnapshot.PHASE_CANCELLING,
                    UnifiedNativeTerminalFailureSnapshot.PHASE_COMPLETED)) {
                return;
            }
            Runnable guard = snapshot.cleanupGuard;
            if (guard != null) {
                handler.removeCallbacks(guard);
            }
            ReturnHomeSession session = snapshot.session;
            if (currentSession == session
                    && session.finished.get() == 0
                    && session.unifiedNativeTerminalFailure.get()
                    == snapshot) {
                session.unifiedNativeCleanupVerified = true;
                log(Log.ERROR, TAG,
                        "Completed guarded Xiaomi native-owner terminal cleanup"
                                + ", generation="
                                + session.generation
                                + ", animToEpoch="
                                + snapshot.animToEpoch
                                + ", completion="
                                + completionReason
                                + ", reason=" + snapshot.reason,
                        snapshot.failure);
                try {
                    Object currentElement = invokeAnyMethod(
                            session.stateManager,
                            "getCurrentWindowElement", new Object[0]);
                    Object currentIdentity = invokeAnyMethod(
                            snapshot.windowElement,
                            "getAnimSymbol", new Object[0]);
                    if (currentElement == snapshot.windowElement
                            && currentIdentity
                            == snapshot.animationIdentity) {
                        int finishStage = snapshot.finishStage.get();
                        if (finishStage
                                == UnifiedNativeTerminalFailureSnapshot
                                .FINISH_STAGE_SOURCE_SKIPPED) {
                            // The source invocation was skipped before it could release the
                            // Home leash or enqueue the native main-thread cleanup.
                            invokeAnyMethod(snapshot.windowElement,
                                    "onFinishCompleted",
                                    new Object[0]);
                        } else if (finishStage
                                == UnifiedNativeTerminalFailureSnapshot
                                .FINISH_STAGE_APPLY_SKIPPED) {
                            // The source already released the Home leash; replay only its exact
                            // static apply body, never the outer source a second time.
                            invokeAnyMethod(snapshot.windowElement,
                                    "onFinishCompleted$lambda$39",
                                    new Object[]{snapshot.windowElement});
                        }
                    }
                } catch (Throwable throwable) {
                    snapshot.failure.addSuppressed(throwable);
                    log(Log.ERROR, TAG,
                            "Could not finish skipped Xiaomi terminal cleanup stage"
                                    + ", generation="
                                    + session.generation
                                    + ", reason="
                                    + snapshot.reason,
                            snapshot.failure);
                }
                handler.post(() -> {
                    if (currentSession == session
                            && session.finished.get() == 0
                            && session.unifiedNativeCleanupVerified) {
                        finishSession(session,
                                "unifiedNativeTerminalFailure:"
                                        + snapshot.reason,
                                false);
                    }
                });
            }
        }

        protected AtomicInteger unifiedConfigHookState(Object ownerToken) {
            if (ownerToken instanceof UnifiedNativeStandardCommitToken) {
                return ((UnifiedNativeStandardCommitToken) ownerToken)
                        .configHookState;
            }
            if (ownerToken instanceof UnifiedNativeCommitTransitionToken) {
                return ((UnifiedNativeCommitTransitionToken) ownerToken)
                        .configHookState;
            }
            return null;
        }

        protected Object resolveUnifiedAnimToConfigOwnerToken(Object params) {
            UnifiedNativePendingInterruptionSnapshot interrupted =
                    findUnifiedInterruptedAnimToConfig(params);
            if (interrupted != null) {
                return interrupted.ownerToken;
            }
            ReturnHomeSession session = currentSession;
            if (session == null || params == null) {
                return null;
            }
            UnifiedNativeStandardCommitToken standard =
                    session.unifiedNativeStandardCommit;
            if (standard != null && standard.animParams.get() == params) {
                return standard;
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            return transition != null
                    && transition.animParams.get() == params
                    ? transition : null;
        }

        Object beginUnifiedNativeAnimToConfigHook(Object params) {
            Object ownerToken = resolveUnifiedAnimToConfigOwnerToken(params);
            AtomicInteger state = unifiedConfigHookState(ownerToken);
            if (state == null) {
                return null;
            }
            int previous = state.get();
            if (previous == UNIFIED_CONFIG_HOOK_PENDING) {
                state.compareAndSet(UNIFIED_CONFIG_HOOK_PENDING,
                        UNIFIED_CONFIG_HOOK_RUNNING);
                previous = state.get();
            }
            log(previous == UNIFIED_CONFIG_HOOK_RUNNING
                            ? Log.INFO : Log.WARN,
                    TAG,
                    "Entered Xiaomi animTo config hook"
                            + ", state=" + previous
                            + ", params=" + shortObject(params));
            return ownerToken;
        }

        void finishUnifiedNativeAnimToConfigHook(
                Object ownerToken, Object params,
                String reason, Throwable failure) {
            AtomicInteger state = unifiedConfigHookState(ownerToken);
            if (state == null) {
                return;
            }
            boolean paramsExact = ownerToken
                    instanceof UnifiedNativeStandardCommitToken
                    ? ((UnifiedNativeStandardCommitToken) ownerToken)
                    .animParams.get() == params
                    : ((UnifiedNativeCommitTransitionToken) ownerToken)
                    .animParams.get() == params;
            int previous = state.getAndSet(
                    UNIFIED_CONFIG_HOOK_COMPLETED);
            log(failure == null && paramsExact ? Log.INFO : Log.ERROR,
                    TAG,
                    "Completed Xiaomi animTo config hook"
                            + ", previousState=" + previous
                            + ", paramsExact=" + paramsExact
                            + ", reason=" + reason,
                    failure);
        }

        protected UnifiedNativePendingInterruptionSnapshot
        findUnifiedInterruptedAnimToConfig(Object params) {
            if (params == null) {
                return null;
            }
            UnifiedNativePendingInterruptionSnapshot snapshot =
                    pendingUnifiedInterruptedAnimToConfigs.get(
                            new ObjectIdentityKey(params));
            return snapshot != null && snapshot.animParams == params
                    ? snapshot : null;
        }

        protected boolean hasExactUnifiedInterruptedOwnerTuple(
                UnifiedNativePendingInterruptionSnapshot snapshot) {
            if (snapshot == null || snapshot.animParams == null
                    || snapshot.animToEpoch <= 0L
                    || snapshot.ownerAttempt <= 0L
                    || snapshot.configLock == null) {
                return false;
            }
            if (snapshot.ownerToken
                    instanceof UnifiedNativeStandardCommitToken) {
                UnifiedNativeStandardCommitToken token =
                        (UnifiedNativeStandardCommitToken)
                                snapshot.ownerToken;
                return token.session == snapshot.session
                        && token.generation == snapshot.generation
                        && token.windowElement == snapshot.windowElement
                        && token.animationIdentity
                        == snapshot.animationIdentity
                        && token.animParams.get() == snapshot.animParams
                        && token.animToEpoch == snapshot.animToEpoch
                        && token.ownerAttempt == snapshot.ownerAttempt
                        && token.configLock == snapshot.configLock;
            }
            if (snapshot.ownerToken
                    instanceof UnifiedNativeCommitTransitionToken) {
                UnifiedNativeCommitTransitionToken token =
                        (UnifiedNativeCommitTransitionToken)
                                snapshot.ownerToken;
                return token.session == snapshot.session
                        && token.generation == snapshot.generation
                        && token.windowElement == snapshot.windowElement
                        && token.animationIdentity
                        == snapshot.animationIdentity
                        && token.animParams.get() == snapshot.animParams
                        && token.animToEpoch == snapshot.animToEpoch
                        && token.configLock == snapshot.configLock;
            }
            return false;
        }

        protected void maybeFinishDeferredControllerAfterConfigAck(
                String reason) {
            if (!deferredControllerReplacement
                    || currentSession != null
                    || !pendingUnifiedInterruptedAnimToConfigs.isEmpty()) {
                return;
            }
            handler.post(() -> finishDeferredMiuiHomeReturnHomeController(
                    MiuiHomeReturnHomeController.this,
                    "interruptedConfigAck:" + reason));
        }

        protected void scheduleUnifiedInterruptedConfigOwnerDrain(
                UnifiedNativePendingInterruptionSnapshot snapshot,
                String reason) {
            try {
                executeOnNativeGestureAnimationOwner(() -> {
                    UnifiedNativePendingInterruptionSnapshot current =
                            findUnifiedInterruptedAnimToConfig(
                                    snapshot.animParams);
                    if (current == snapshot
                            && snapshot.configDisposition.get()
                            == UnifiedNativePendingInterruptionSnapshot
                            .CONFIG_PENDING) {
                        // This runnable is FIFO behind Xiaomi's already queued animTo lambda.
                        // Retain the tombstone: removing it without an intercepted config ack
                        // could admit a later stale invocation after controller replacement.
                        log(Log.ERROR, TAG,
                                "Xiaomi animTo owner drain reached without config ack"
                                        + ", generation="
                                        + snapshot.generation
                                        + ", ownerAttempt="
                                        + snapshot.ownerAttempt
                                        + ", animToEpoch="
                                        + snapshot.animToEpoch
                                        + ", mutation="
                                        + snapshot.mutation.get()
                                        + ", reason=" + reason);
                    }
                });
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not queue Xiaomi interrupted-config owner drain"
                                + ", generation=" + snapshot.generation
                                + ", animToEpoch=" + snapshot.animToEpoch
                                + ", reason=" + reason,
                        throwable);
            }
        }

        protected boolean acknowledgeSkippedUnifiedInterruptedAnimToConfig(
                UnifiedNativePendingInterruptionSnapshot snapshot,
                String reason) {
            int disposition = snapshot.configDisposition.get();
            if (disposition
                    == UnifiedNativePendingInterruptionSnapshot
                    .CONFIG_ACK_SKIPPED) {
                return true;
            }
            if (disposition
                    != UnifiedNativePendingInterruptionSnapshot
                    .CONFIG_PENDING
                    || !snapshot.configDisposition.compareAndSet(
                    UnifiedNativePendingInterruptionSnapshot.CONFIG_PENDING,
                    UnifiedNativePendingInterruptionSnapshot
                            .CONFIG_ACK_SKIPPED)) {
                return snapshot.configDisposition.get()
                        == UnifiedNativePendingInterruptionSnapshot
                        .CONFIG_ACK_SKIPPED;
            }
            pendingUnifiedInterruptedAnimToConfigs.remove(
                    new ObjectIdentityKey(snapshot.animParams), snapshot);
            log(Log.INFO, TAG,
                    "Acknowledged skipped stale Xiaomi animTo config"
                            + ", generation=" + snapshot.generation
                            + ", ownerAttempt=" + snapshot.ownerAttempt
                            + ", animToEpoch=" + snapshot.animToEpoch
                            + ", mutation=" + snapshot.mutation.get()
                            + ", requestedType=" + snapshot.requestedType
                            + ", reason=" + reason);
            maybeFinishDeferredControllerAfterConfigAck(reason);
            return true;
        }

        protected void acknowledgeAppliedUnifiedInterruptedAnimToConfig(
                ReturnHomeSession session, Object params,
                Object ownerToken, long animToEpoch, String reason) {
            UnifiedNativePendingInterruptionSnapshot snapshot =
                    findUnifiedInterruptedAnimToConfig(params);
            if (snapshot == null || snapshot.session != session
                    || snapshot.ownerToken != ownerToken
                    || snapshot.animToEpoch != animToEpoch
                    || snapshot.mutation.get()
                    != UnifiedNativePendingInterruptionSnapshot.MUTATION_NONE
                    || !hasExactUnifiedInterruptedOwnerTuple(snapshot)
                    || !snapshot.configDisposition.compareAndSet(
                    UnifiedNativePendingInterruptionSnapshot.CONFIG_PENDING,
                    UnifiedNativePendingInterruptionSnapshot
                            .CONFIG_ACK_APPLIED)) {
                return;
            }
            pendingUnifiedInterruptedAnimToConfigs.remove(
                    new ObjectIdentityKey(params), snapshot);
            session.unifiedNativePendingInterruption.compareAndSet(
                    snapshot, null);
            snapshot.phase.compareAndSet(
                    UnifiedNativePendingInterruptionSnapshot.PHASE_PENDING,
                    UnifiedNativePendingInterruptionSnapshot.PHASE_INVALID);
            log(Log.INFO, TAG,
                    "Acknowledged normally applied Xiaomi animTo config"
                            + ", generation=" + snapshot.generation
                            + ", ownerAttempt=" + snapshot.ownerAttempt
                            + ", animToEpoch=" + snapshot.animToEpoch
                            + ", requestedType=" + snapshot.requestedType
                            + ", reason=" + reason);
            maybeFinishDeferredControllerAfterConfigAck(reason);
        }

        void onUnifiedNativeAnimToConfigHookCompleted(
                Object implementor, Object params,
                String reason, Throwable failure) {
            UnifiedNativePendingInterruptionSnapshot snapshot =
                    findUnifiedInterruptedAnimToConfig(params);
            if (snapshot == null
                    || snapshot.configDisposition.get()
                    != UnifiedNativePendingInterruptionSnapshot.CONFIG_PENDING
                    || !snapshot.configDisposition.compareAndSet(
                    UnifiedNativePendingInterruptionSnapshot.CONFIG_PENDING,
                    UnifiedNativePendingInterruptionSnapshot.CONFIG_INVALID)) {
                return;
            }
            pendingUnifiedInterruptedAnimToConfigs.remove(
                    new ObjectIdentityKey(params), snapshot);
            if (snapshot.ownerToken
                    instanceof UnifiedNativeStandardCommitToken) {
                UnifiedNativeStandardCommitToken standard =
                        (UnifiedNativeStandardCommitToken)
                                snapshot.ownerToken;
                standard.phase.set(
                        UnifiedNativeStandardCommitToken.PHASE_INVALID);
                if (snapshot.session.unifiedNativeStandardCommit
                        == standard) {
                    snapshot.session.unifiedNativeStandardCommit = null;
                }
            } else if (snapshot.ownerToken
                    instanceof UnifiedNativeCommitTransitionToken) {
                UnifiedNativeCommitTransitionToken transition =
                        (UnifiedNativeCommitTransitionToken)
                                snapshot.ownerToken;
                transition.phase.set(
                        UnifiedNativeCommitTransitionToken.PHASE_INVALID);
                if (snapshot.session.unifiedNativeCommitTransition
                        == transition) {
                    snapshot.session.unifiedNativeCommitTransition = null;
                }
            }
            snapshot.session.unifiedNativeCommitPending = false;
            if (snapshot.mutation.get()
                    == UnifiedNativePendingInterruptionSnapshot.MUTATION_NONE) {
                snapshot.session.unifiedNativePendingInterruption
                        .compareAndSet(snapshot, null);
                snapshot.phase.compareAndSet(
                        UnifiedNativePendingInterruptionSnapshot.PHASE_PENDING,
                        UnifiedNativePendingInterruptionSnapshot.PHASE_INVALID);
            }
            log(Log.ERROR, TAG,
                    "Closed unacknowledged Xiaomi animTo config hook"
                            + ", generation=" + snapshot.generation
                            + ", ownerAttempt=" + snapshot.ownerAttempt
                            + ", animToEpoch=" + snapshot.animToEpoch
                            + ", mutation=" + snapshot.mutation.get()
                            + ", phase=" + snapshot.phase.get()
                            + ", sameImplementorElement="
                            + isUnifiedConfigImplementorElement(
                            implementor, snapshot.windowElement)
                            + ", reason=" + reason,
                    failure == null
                            ? new IllegalStateException(
                            "animTo config completed without CONFIG_ACK")
                            : failure);
            maybeFinishDeferredControllerAfterConfigAck(reason);
        }

        protected boolean isUnifiedConfigImplementorElement(
                Object implementor, Object expectedWindowElement) {
            try {
                return readField(implementor, "windowElement")
                        == expectedWindowElement;
            } catch (Throwable throwable) {
                return false;
            }
        }

        Object resolveUnifiedAnimToConfigLock(Object params) {
            UnifiedNativePendingInterruptionSnapshot interrupted =
                    findUnifiedInterruptedAnimToConfig(params);
            if (interrupted != null
                    && interrupted.configDisposition.get()
                    == UnifiedNativePendingInterruptionSnapshot
                    .CONFIG_PENDING) {
                return interrupted.configLock;
            }
            ReturnHomeSession session = currentSession;
            if (session == null || params == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || session.unifiedNativeCleanupVerified) {
                return null;
            }
            UnifiedNativeStandardCommitToken standard =
                    session.unifiedNativeStandardCommit;
            if (standard != null
                    && standard.animParams.get() == params
                    && standard.animToEpoch > 0L
                    && standard.animToEpoch
                    == session.unifiedNativeActiveAnimToEpoch) {
                return standard.configLock;
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            return transition != null
                    && transition.animParams.get() == params
                    && transition.animToEpoch > 0L
                    && transition.animToEpoch
                    == session.unifiedNativeActiveAnimToEpoch
                    ? transition.configLock : null;
        }

        boolean shouldSkipInterruptedUnifiedAnimToConfig(
                Object implementor, Object params) {
            UnifiedNativePendingInterruptionSnapshot snapshot =
                    findUnifiedInterruptedAnimToConfig(params);
            if (snapshot == null
                    || snapshot.configDisposition.get()
                    != UnifiedNativePendingInterruptionSnapshot.CONFIG_PENDING
                    || snapshot.mutation.get()
                    == UnifiedNativePendingInterruptionSnapshot.MUTATION_NONE) {
                return false;
            }
            ReturnHomeSession session = snapshot.session;
            boolean ownerTupleExact =
                    hasExactUnifiedInterruptedOwnerTuple(snapshot);
            boolean implementorExact = false;
            Throwable verificationFailure = null;
            try {
                Object windowElement = readField(
                        implementor, "windowElement");
                implementorExact = windowElement == snapshot.windowElement;
                if (snapshot.mutation.get()
                        == UnifiedNativePendingInterruptionSnapshot
                        .MUTATION_CANCEL_SURFACE
                        && currentSession == session
                        && session.finished.get() == 0
                        && !session.unifiedNativeCleanupVerified) {
                    verifyUnifiedStateManagerListenerGate(
                            session, true,
                            "skipInterruptedConfig:"
                                    + snapshot.animToEpoch);
                }
            } catch (Throwable throwable) {
                verificationFailure = throwable;
            }
            if (!ownerTupleExact || !implementorExact
                    || verificationFailure != null) {
                log(Log.ERROR, TAG,
                        "Fail-closed interrupted Xiaomi animTo config verification"
                                + ", generation="
                                + snapshot.generation
                                + ", animToEpoch="
                                + snapshot.animToEpoch
                                + ", ownerTupleExact="
                                + ownerTupleExact
                                + ", implementorExact="
                                + implementorExact,
                        verificationFailure == null
                                ? new IllegalStateException(
                                "interrupted animTo identity mismatch")
                                : verificationFailure);
            }
            if (!acknowledgeSkippedUnifiedInterruptedAnimToConfig(
                    snapshot, ownerTupleExact && implementorExact
                            && verificationFailure == null
                            ? "configHook"
                            : "configHookFailClosed")) {
                return false;
            }
            log(Log.INFO, TAG,
                    "Skipped Xiaomi final animTo config after native interruption began"
                            + ", generation="
                            + snapshot.generation
                            + ", ownerAttempt="
                            + snapshot.ownerAttempt
                            + ", animToEpoch="
                            + snapshot.animToEpoch
                            + ", phase=" + snapshot.phase.get()
                            + ", mutation="
                            + snapshot.mutation.get()
                            + ", requestedType="
                            + snapshot.requestedType);
            return true;
        }

        void onUnifiedNativeAnimToConfigured(
                Object implementor, Object params) {
            ReturnHomeSession session = currentSession;
            if (session == null || params == null
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified) {
                return;
            }
            long epoch = 0L;
            Object ownerToken = null;
            boolean cancel = false;
            UnifiedNativeStandardCommitToken standard =
                    session.unifiedNativeStandardCommit;
            if (standard != null
                    && standard.animParams.get() == params
                    && standard.animToEpoch > 0L
                    && standard.phase.get()
                    != UnifiedNativeStandardCommitToken.PHASE_INVALID) {
                epoch = standard.animToEpoch;
                ownerToken = standard;
            } else {
                UnifiedNativeCommitTransitionToken transition =
                        session.unifiedNativeCommitTransition;
                if (transition != null
                        && transition.animParams.get() == params
                        && transition.animToEpoch > 0L
                        && transition.phase.get()
                        != UnifiedNativeCommitTransitionToken.PHASE_INVALID) {
                    epoch = transition.animToEpoch;
                    ownerToken = transition;
                } else if (session.unifiedNativeCancelPending
                        && session.unifiedNativeCancelAnimParams == params
                        && session.unifiedNativeCancelAnimToEpoch > 0L) {
                    epoch = session.unifiedNativeCancelAnimToEpoch;
                    ownerToken = params;
                    cancel = true;
                }
            }
            if (epoch == 0L
                    || epoch != session.unifiedNativeActiveAnimToEpoch) {
                return;
            }
            UnifiedNativePendingInterruptionSnapshot pendingInterruption =
                    session.unifiedNativePendingInterruption.get();
            int pendingMutation = pendingInterruption == null
                    ? UnifiedNativePendingInterruptionSnapshot.MUTATION_NONE
                    : pendingInterruption.mutation.get();
            if (pendingInterruption != null
                    && pendingInterruption
                    == session.unifiedNativePendingInterruption.get()
                    && pendingInterruption.session == session
                    && pendingInterruption.windowElement
                    == session.nativeWindowElement
                    && pendingInterruption.animationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && pendingInterruption.animParams == params
                    && pendingInterruption.ownerToken == ownerToken
                    && pendingInterruption.animToEpoch == epoch
                    && pendingInterruption.phase.get()
                    == UnifiedNativePendingInterruptionSnapshot.PHASE_PENDING
                    && pendingMutation
                    != UnifiedNativePendingInterruptionSnapshot.MUTATION_NONE) {
                if (pendingMutation
                        == UnifiedNativePendingInterruptionSnapshot
                        .MUTATION_CANCEL_SURFACE) {
                    try {
                        verifyUnifiedStateManagerListenerGate(
                                session, true,
                                "configuredDuringCancelSurface:"
                                        + epoch);
                    } catch (Throwable throwable) {
                        log(Log.ERROR, TAG,
                                "Could not retain Xiaomi listener gate during cancel-surface interruption"
                                        + ", generation="
                                        + session.generation
                                        + ", animToEpoch=" + epoch,
                                throwable);
                    }
                }
                log(Log.INFO, TAG,
                        "Suppressed final-owner publication after native interruption began"
                                + ", generation=" + session.generation
                                + ", animToEpoch=" + epoch
                                + ", mutation=" + pendingMutation
                                + ", animationIdentity="
                                + shortObject(
                                session.unifiedNativeAnimationIdentity));
                return;
            }
            try {
                Object windowElement = readField(
                        implementor, "windowElement");
                Object currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
                Object animationIdentity = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getAnimSymbol", new Object[0]);
                Object requestedTypeObject = invokeAnyMethod(
                        params, "getAnimType", new Object[0]);
                Object actualTypeObject = invokeAnyMethod(
                        animationIdentity, "getLastAminType",
                        new Object[0]);
                String requestedType = enumName(requestedTypeObject);
                String actualType = enumName(actualTypeObject);
                Object targetSet = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getRemoteTargetSet", new Object[0]);
                boolean finalType = cancel
                        ? "APP_TO_APP".equals(actualType)
                        : isReturnHomeNativeCloseType(actualType);
                boolean running = animationIdentity != null
                        && Boolean.TRUE.equals(invokeAnyMethod(
                        animationIdentity, "isRunning", new Object[0]));
                boolean finishComplete = Boolean.TRUE.equals(readField(
                        session.nativeWindowElement, "mFinishComplete"));
                if (windowElement != session.nativeWindowElement
                        || currentElement != session.nativeWindowElement
                        || animationIdentity
                        != session.unifiedNativeAnimationIdentity
                        || !requestedType.equals(actualType)
                        || !finalType
                        || resolveUnifiedNativeClosingTarget(
                        session, targetSet) == null) {
                    throw new IllegalStateException(
                            "Xiaomi final animTo owner did not configure exactly"
                                    + ", requestedType=" + requestedType
                                    + ", actualType=" + actualType
                                    + ", sameImplementorElement="
                                    + (windowElement
                                    == session.nativeWindowElement)
                                    + ", sameCurrentElement="
                                    + (currentElement
                                    == session.nativeWindowElement)
                                    + ", sameIdentity="
                                    + (animationIdentity
                                    == session.unifiedNativeAnimationIdentity));
                }
                verifyUnifiedStateManagerListenerGate(
                        session, false,
                        "configured:" + actualType + ":" + epoch);
                UnifiedNativeConfiguredAnimToSnapshot configured =
                        new UnifiedNativeConfiguredAnimToSnapshot(
                                session, params, ownerToken, epoch,
                                actualType, cancel, running,
                                finishComplete);
                session.unifiedNativeConfiguredAnimTo.set(configured);
                acknowledgeAppliedUnifiedInterruptedAnimToConfig(
                        session, params, ownerToken, epoch,
                        "configured:" + actualType);
                Object configuredStartAlpha = null;
                Object configuredEndAlpha = null;
                Object elementTarget = null;
                Object homeTarget = null;
                try {
                    Object windowAnimParams = invokeAnyMethod(
                            params, "getWindowAnimParams", new Object[0]);
                    configuredStartAlpha = windowAnimParams == null
                            ? null : invokeAnyMethod(windowAnimParams,
                            "getStartAlpha", new Object[0]);
                    configuredEndAlpha = windowAnimParams == null
                            ? null : invokeAnyMethod(windowAnimParams,
                            "getEndAlpha", new Object[0]);
                    elementTarget = invokeAnyMethod(
                            targetSet, "getElementTarget", new Object[0]);
                    homeTarget = invokeAnyMethod(
                            targetSet, "getHomeTarget", new Object[0]);
                } catch (Throwable ignored) {
                    // Diagnostics must not turn a native accepted animTo into a rejection.
                }
                log(Log.INFO, TAG,
                        "Published configured Xiaomi final animTo epoch"
                                + ", generation=" + session.generation
                                + ", animToEpoch=" + epoch
                                + ", type=" + actualType
                                + ", cancel=" + cancel
                                + ", configuredAlpha="
                                + configuredStartAlpha + "->"
                                + configuredEndAlpha
                                + ", springAlpha="
                                + readFieldOrNull(
                                animationIdentity, "mStartAlpha")
                                + "->" + readFieldOrNull(
                                animationIdentity, "mEndAlpha")
                                + ", hasElementTarget="
                                + (elementTarget != null)
                                + ", hasHomeTarget="
                                + (homeTarget != null)
                                + ", animationIdentity="
                                + shortObject(animationIdentity));
            } catch (Throwable throwable) {
                try {
                    verifyUnifiedStateManagerListenerGate(
                            session, true,
                            "configuredFailure:" + epoch);
                } catch (Throwable gateFailure) {
                    throwable.addSuppressed(gateFailure);
                }
                boolean terminalQueued =
                        publishUnifiedNativeTerminalFailure(
                                session, params, ownerToken, epoch,
                                cancel,
                                "animToConfiguredFailure",
                                throwable);
                log(Log.ERROR, TAG,
                        "Rejected unverified Xiaomi final animTo configuration"
                                + ", generation=" + session.generation
                                + ", animToEpoch=" + epoch
                                + ", cancel=" + cancel
                                + ", terminalQueued="
                                + terminalQueued,
                        throwable);
            }
        }

        protected boolean isExactUnifiedConfiguredAnimTo(
                ReturnHomeSession session,
                UnifiedNativeConfiguredAnimToSnapshot configured,
                Object windowElement, Object animationIdentity,
                String actualType) {
            if (session == null || configured == null
                    || configured.session != session
                    || configured.generation != session.generation
                    || configured.windowElement != windowElement
                    || configured.animationIdentity != animationIdentity
                    || configured.animToEpoch == 0L
                    || configured.animToEpoch
                    != session.unifiedNativeActiveAnimToEpoch
                    || !configured.animationType.equals(actualType)) {
                return false;
            }
            if (configured.cancel) {
                return session.unifiedNativeCancelPending
                        && session.unifiedNativeCancelAnimParams
                        == configured.animParams
                        && session.unifiedNativeCancelAnimToEpoch
                        == configured.animToEpoch
                        && "APP_TO_APP".equals(actualType);
            }
            if (!isReturnHomeNativeCloseType(actualType)) {
                return false;
            }
            if (configured.ownerToken
                    instanceof UnifiedNativeStandardCommitToken) {
                UnifiedNativeStandardCommitToken token =
                        (UnifiedNativeStandardCommitToken)
                                configured.ownerToken;
                return token.session == session
                        && token.animParams.get()
                        == configured.animParams
                        && token.animToEpoch == configured.animToEpoch
                        && token.phase.get()
                        != UnifiedNativeStandardCommitToken.PHASE_INVALID;
            }
            if (configured.ownerToken
                    instanceof UnifiedNativeCommitTransitionToken) {
                UnifiedNativeCommitTransitionToken token =
                        (UnifiedNativeCommitTransitionToken)
                                configured.ownerToken;
                return token.session == session
                        && token.animParams.get()
                        == configured.animParams
                        && token.animToEpoch == configured.animToEpoch
                        && token.phase.get()
                        != UnifiedNativeCommitTransitionToken.PHASE_INVALID;
            }
            return false;
        }

        UnifiedNativeFinishDispatchToken beginUnifiedNativeFinishDispatch(
                Object windowElement) {
            ReturnHomeSession session = currentSession;
            if (session == null || windowElement == null
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified
                    || session.nativeWindowElement != windowElement) {
                return null;
            }
            if (session.unifiedNativeProviderCommitAdopted) {
                // The original runner targets have already entered Xiaomi's complete native
                // closing provider. From this point the WindowElement follows the same
                // CLOSE_TO_HOME -> CLOSE_TO_ELEMENT lifecycle as Xiaomi's ordinary launcher
                // animation, so its own finish dispatch must run without the preview-only
                // epoch gate.
                return null;
            }
            long dispatchId = unifiedNativeFinishDispatchIds
                    .incrementAndGet();
            Object animationIdentity =
                    session.unifiedNativeAnimationIdentity;
            UnifiedNativeConfiguredAnimToSnapshot configured =
                    session.unifiedNativeConfiguredAnimTo.get();
            boolean allowed = false;
            String actualType = "unknown";
            Throwable failure = null;
            try {
                Object currentIdentity = invokeAnyMethod(
                        windowElement, "getAnimSymbol", new Object[0]);
                String currentType = readNativeAnimationType(windowElement);
                Object actualTypeObject = invokeAnyMethod(
                        currentIdentity, "getLastAminType",
                        new Object[0]);
                actualType = enumName(actualTypeObject);
                allowed = currentIdentity == animationIdentity
                        && configured
                        == session.unifiedNativeConfiguredAnimTo.get()
                        && isExactUnifiedConfiguredAnimTo(
                        session, configured, windowElement,
                        currentIdentity, actualType);
                verifyUnifiedStateManagerListenerGate(
                        session, !allowed,
                        "finishSource:" + dispatchId + ":"
                                + actualType);
            } catch (Throwable throwable) {
                failure = throwable;
                allowed = false;
                try {
                    verifyUnifiedStateManagerListenerGate(
                            session, true,
                            "finishSourceFailure:" + dispatchId);
                } catch (Throwable gateFailure) {
                    throwable.addSuppressed(gateFailure);
                }
                if (configured != null) {
                    publishUnifiedNativeTerminalFailure(
                            session, configured.animParams,
                            configured.cancel
                                    ? configured.animParams
                                    : configured,
                            configured.animToEpoch,
                            configured.cancel,
                            "finishSourceFailure:" + dispatchId,
                            throwable);
                }
            }
            UnifiedNativeFinishDispatchToken token =
                    new UnifiedNativeFinishDispatchToken(
                            dispatchId, session, windowElement,
                            animationIdentity, configured, allowed);
            if (allowed) {
                pendingUnifiedNativeFinishDispatches
                        .computeIfAbsent(windowElement,
                                ignored -> new ConcurrentLinkedQueue<>())
                        .offer(token);
                log(Log.INFO, TAG,
                        "Admitted Xiaomi final finish source"
                                + ", generation=" + session.generation
                                + ", dispatchId=" + dispatchId
                                + ", animToEpoch="
                                + configured.animToEpoch
                                + ", type=" + actualType);
            } else {
                UnifiedNativeTerminalFailureSnapshot terminal =
                        session.unifiedNativeTerminalFailure.get();
                if (terminal != null
                        && terminal.session == session
                        && terminal.windowElement == windowElement
                        && terminal.animationIdentity
                        == animationIdentity) {
                    terminal.markFinishSourceSkipped();
                }
                log(Log.WARN, TAG,
                        "Skipped superseded Xiaomi finish source"
                                + ", generation=" + session.generation
                                + ", dispatchId=" + dispatchId
                                + ", activeAnimToEpoch="
                                + session.unifiedNativeActiveAnimToEpoch
                                + ", configuredAnimToEpoch="
                                + (configured == null ? 0L
                                : configured.animToEpoch)
                                + ", type=" + actualType,
                        failure);
            }
            return token;
        }

        void abortUnifiedNativeFinishDispatch(
                UnifiedNativeFinishDispatchToken token,
                String reason) {
            if (token == null || !token.allowed) {
                return;
            }
            ConcurrentLinkedQueue<UnifiedNativeFinishDispatchToken> queue =
                    pendingUnifiedNativeFinishDispatches.get(
                            token.windowElement);
            if (queue != null) {
                queue.remove(token);
                if (queue.isEmpty()) {
                    pendingUnifiedNativeFinishDispatches.remove(
                            token.windowElement, queue);
                }
            }
            ReturnHomeSession session = token.session;
            try {
                if (currentSession == session
                        && session.finished.get() == 0
                        && session.nativeWindowElement
                        == token.windowElement
                        && session.unifiedNativeConfiguredAnimTo.get()
                        == token.configured) {
                    verifyUnifiedStateManagerListenerGate(
                            session, true,
                            "finishSourceAbort:" + reason);
                }
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not close Xiaomi listener gate after finish-source abort"
                                + ", generation=" + token.generation
                                + ", dispatchId=" + token.dispatchId
                                + ", reason=" + reason,
                        throwable);
            }
        }

        Boolean consumeUnifiedNativeFinishDispatch(
                Object windowElement) {
            ConcurrentLinkedQueue<UnifiedNativeFinishDispatchToken> queue =
                    pendingUnifiedNativeFinishDispatches.get(windowElement);
            UnifiedNativeFinishDispatchToken token = queue == null
                    ? null : queue.poll();
            if (queue != null && queue.isEmpty()) {
                pendingUnifiedNativeFinishDispatches.remove(
                        windowElement, queue);
            }
            if (token == null) {
                ReturnHomeSession session = currentSession;
                if (session != null
                        && session.finished.get() == 0
                        && session.unifiedNativePreviewOwned
                        && session.unifiedNativeProviderCommitAdopted
                        && session.nativeWindowElement == windowElement) {
                    return null;
                }
                if (session != null
                        && session.finished.get() == 0
                        && session.unifiedNativePreviewOwned
                        && session.nativeWindowElement == windowElement) {
                    log(Log.ERROR, TAG,
                            "Skipped unpaired Xiaomi finish apply"
                                    + ", generation="
                                    + session.generation);
                    return Boolean.FALSE;
                }
                return null;
            }
            ReturnHomeSession session = token.session;
            Object currentIdentity = null;
            String actualType = "unknown";
            boolean exact = false;
            Throwable failure = null;
            try {
                currentIdentity = invokeAnyMethod(
                        token.windowElement,
                        "getAnimSymbol", new Object[0]);
                Object actualTypeObject = invokeAnyMethod(
                        currentIdentity, "getLastAminType",
                        new Object[0]);
                actualType = enumName(actualTypeObject);
                exact = token.allowed
                        && token.windowElement == windowElement
                        && token.animationIdentity == currentIdentity
                        && currentSession == session
                        && session.finished.get() == 0
                        && !session.unifiedNativeCleanupVerified
                        && session.unifiedNativeConfiguredAnimTo.get()
                        == token.configured
                        && isExactUnifiedConfiguredAnimTo(
                        session, token.configured,
                        windowElement, currentIdentity, actualType);
                if (exact) {
                    verifyUnifiedStateManagerListenerGate(
                            session, false,
                            "finishApply:" + token.dispatchId);
                }
            } catch (Throwable throwable) {
                failure = throwable;
                exact = false;
                UnifiedNativeConfiguredAnimToSnapshot configured =
                        token.configured;
                if (configured != null) {
                    publishUnifiedNativeTerminalFailure(
                            session, configured.animParams,
                            configured.cancel
                                    ? configured.animParams
                                    : configured,
                            configured.animToEpoch,
                            configured.cancel,
                            "finishApplyFailure:"
                                    + token.dispatchId,
                            throwable);
                }
            }
            if (!exact) {
                UnifiedNativeTerminalFailureSnapshot terminal =
                        session.unifiedNativeTerminalFailure.get();
                if (terminal != null
                        && terminal.session == session
                        && terminal.windowElement == windowElement
                        && terminal.animationIdentity
                        == token.animationIdentity) {
                    terminal.markFinishApplySkipped();
                }
                log(Log.WARN, TAG,
                        "Skipped stale Xiaomi finish apply"
                                + ", generation=" + token.generation
                                + ", dispatchId=" + token.dispatchId
                                + ", type=" + actualType
                                + ", sameSession="
                                + (currentSession == session)
                                + ", sameIdentity="
                                + (currentIdentity
                                == token.animationIdentity),
                        failure);
                return Boolean.FALSE;
            }
            log(Log.INFO, TAG,
                    "Admitted Xiaomi final finish apply"
                            + ", generation=" + token.generation
                            + ", dispatchId=" + token.dispatchId
                            + ", animToEpoch="
                            + token.configured.animToEpoch
                            + ", type=" + actualType);
            return Boolean.TRUE;
        }

        protected long beginUnifiedAnimToEpoch(
                ReturnHomeSession session, String reason) {
            invalidateUnifiedPendingInterruption(
                    session, "newAnimTo:" + reason);
            invalidatePendingUnifiedTerminalFailure(
                    session, "newAnimTo:" + reason);
            long epoch = session.unifiedNativeAnimToEpochs.incrementAndGet();
            session.unifiedNativeActiveAnimToEpoch = epoch;
            session.unifiedNativeCommitEndObserved = false;
            session.unifiedNativeConfiguredAnimTo.set(null);
            UnifiedNativeProvisionalCommitSnapshot provisional =
                    session.unifiedNativeProvisionalCommit.getAndSet(null);
            if (provisional != null
                    && provisional.phase.get()
                    != UnifiedNativeProvisionalCommitSnapshot.PHASE_ADOPTED) {
                provisional.phase.set(
                        UnifiedNativeProvisionalCommitSnapshot.PHASE_INVALID);
            }
            UnifiedNativeFinishSnapshot previous =
                    session.unifiedNativeFinishSnapshot.get();
            if (previous != null
                    && previous.phase.compareAndSet(
                    UnifiedNativeFinishSnapshot.PHASE_PENDING,
                    UnifiedNativeFinishSnapshot.PHASE_INVALID)) {
                session.unifiedNativeFinishSnapshot.compareAndSet(
                        previous, null);
                log(Log.INFO, TAG,
                        "Invalidated previous Xiaomi finish snapshot at animTo entry"
                                + ", generation=" + session.generation
                                + ", previousEpoch="
                                + previous.animToEpoch
                                + ", newEpoch=" + epoch
                                + ", previousType="
                                + previous.actualType
                                + ", reason=" + reason);
            }
            return epoch;
        }

        void markUnifiedCommitAnimToReturned(
                Object windowElement, Object params) {
            ReturnHomeSession session = currentSession;
            if (session == null || session.finished.get() != 0
                    || session.nativeWindowElement != windowElement
                    || params == null) {
                return;
            }
            long epoch = 0L;
            UnifiedNativeStandardCommitToken standardToken =
                    session.unifiedNativeStandardCommit;
            if (standardToken != null
                    && standardToken.animParams.get() == params
                    && standardToken.phase.compareAndSet(
                    UnifiedNativeStandardCommitToken.PHASE_ENTERING,
                    UnifiedNativeStandardCommitToken.PHASE_ENTERED)) {
                epoch = standardToken.animToEpoch;
            } else {
                UnifiedNativeCommitTransitionToken transition =
                        session.unifiedNativeCommitTransition;
                if (transition != null
                        && transition.animParams.get() == params
                        && transition.phase.compareAndSet(
                        UnifiedNativeCommitTransitionToken.PHASE_ENTERING,
                        UnifiedNativeCommitTransitionToken.PHASE_ENTERED)) {
                    epoch = transition.animToEpoch;
                }
            }
            if (epoch == 0L) {
                return;
            }
            UnifiedNativeFinishSnapshot snapshot =
                    session.unifiedNativeFinishSnapshot.get();
            if (snapshot != null
                    && snapshot.animToEpoch == epoch
                    && "CLOSE_TO_DRAG".equals(snapshot.actualType)
                    && snapshot.phase.compareAndSet(
                    UnifiedNativeFinishSnapshot.PHASE_PENDING,
                    UnifiedNativeFinishSnapshot.PHASE_INVALID)) {
                session.unifiedNativeFinishSnapshot.compareAndSet(
                        snapshot, null);
                session.unifiedNativeCommitEndObserved = false;
                log(Log.INFO, TAG,
                        "Discarded previous drag finish from commit animTo call"
                                + ", generation=" + session.generation
                                + ", animToEpoch=" + epoch
                                + ", animationIdentity="
                                + shortObject(snapshot.animationIdentity));
            }
        }

        void prepareUnifiedHandoffBeforeAnimTo(
                Object windowElement, Object params) throws Throwable {
            ReturnHomeSession session = currentSession;
            if (Looper.myLooper() != Looper.getMainLooper()
                    || session == null || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || !session.unifiedNativeCommitPending
                    || !session.unifiedNativeCommitReady.get()
                    || session.unifiedNativeCleanupVerified
                    || session.nativeWindowElement != windowElement
                    || params == null) {
                return;
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            if (!isExactUnifiedCommitTransition(
                    session, transition, windowElement,
                    UnifiedNativeCommitTransitionToken.PHASE_ENTERING)) {
                return;
            }
            Object typeObject = invokeAnyMethod(
                    params, "getAnimType", new Object[0]);
            String typeName = enumName(typeObject);
            if (!"CLOSE_TO_ELEMENT".equals(typeName)) {
                return;
            }
            Object targetSet = invokeAnyMethod(
                    params, "getTargetApps", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isAnimRunning", new Object[0]));
            if (running
                    || currentIdentity
                    != session.unifiedNativeAnimationIdentity
                    || resolveUnifiedNativeClosingTarget(
                    session, targetSet) == null) {
                return;
            }
            armUnifiedLocalHandoffStatus(
                    session, "idleElementCommit");
        }

        protected void armUnifiedLocalHandoffStatus(
                ReturnHomeSession session, String reason) throws Throwable {
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.nativeWindowElement == null
                    || session.unifiedNativeAnimationIdentity == null
                    || session.currentRect.isEmpty()
                    || !Float.isFinite(session.currentCornerRadius)
                    || session.currentCornerRadius < 0.0f) {
                return;
            }
            MiuiHomeLocalHandoffToken existing = session.localHandoffToken;
            if (existing != null
                    && existing.session == session
                    && existing.windowElement
                    == session.nativeWindowElement
                    && miuiHomeLocalHandoffToken.get() == existing) {
                return;
            }
            Object windowAnimContext = invokeAnyMethod(
                    session.nativeWindowElement,
                    "getWindowAnimContext", new Object[0]);
            if (windowAnimContext == null) {
                throw new IllegalStateException(
                        "unified WindowElement has no WindowAnimContext");
            }
            Object previousStatus = invokeAnyMethod(windowAnimContext,
                    "getLocalAnimLastStatus", new Object[0]);
            Object status = previousStatus;
            if (status == null) {
                Class<?> animStatusClass = Class.forName(
                        MIUI_HOME_ANIM_STATUS_PARAM, false, classLoader);
                Object companion = readStaticField(
                        animStatusClass, "Companion");
                status = invokeAnyMethod(companion,
                        "getAnimParamFromRect",
                        new Object[]{new RectF(session.currentRect),
                                Float.valueOf(session.currentCornerRadius),
                                Float.valueOf(1.0f)});
                if (status == null) {
                    throw new IllegalStateException(
                            "could not create unified local handoff status");
                }
                // Preserve Xiaomi's own handoff state whenever it already exists. Synthesize
                // one only when the native context has not published any status yet.
                invokeAnyMethod(windowAnimContext,
                        "setLocalAnimLastStatus", new Object[]{status});
            }
            MiuiHomeLocalHandoffToken token =
                    new MiuiHomeLocalHandoffToken(
                            session.generation, session,
                            session.nativeWindowElement,
                            windowAnimContext, status);
            MiuiHomeLocalHandoffToken replaced =
                    miuiHomeLocalHandoffToken.getAndSet(token);
            session.nativeWindowAnimContext = windowAnimContext;
            session.nativePublishedStatus = status;
            session.nativeStatusPublished = true;
            session.localHandoffToken = token;
            log(Log.INFO, TAG,
                    "Armed stopped unified predictive handoff status"
                            + ", generation=" + session.generation
                            + ", reason=" + reason
                            + ", reusedNativeStatus="
                            + (previousStatus != null)
                            + ", replacedGeneration="
                            + (replaced == null ? 0L
                            : replaced.generation)
                            + ", rect=" + session.currentRect
                            + ", radius="
                            + session.currentCornerRadius);
        }

        Object takeLocalHandoffStatus(Object implementor, Object params) {
            try {
                MiuiHomeLocalHandoffToken token = matchLocalHandoffToken(
                        implementor, params);
                if (token == null
                        || !miuiHomeLocalHandoffToken.compareAndSet(token, null)) {
                    return null;
                }
                ReturnHomeSession session = (ReturnHomeSession) token.session;
                if (currentSession != session || session.finished.get() != 0) {
                    return null;
                }
                Object currentStatus = invokeAnyMethod(token.windowAnimContext,
                        "getLocalAnimLastStatus", new Object[0]);
                if (currentStatus != token.status) {
                    return null;
                }
                invokeAnyMethod(token.windowAnimContext, "setLocalAnimLastStatus",
                        new Object[]{null});
                log(Log.INFO, TAG, "Supplied predictive handoff to Xiaomi local animator"
                        + ", generation=" + token.generation
                        + ", rect=" + session.currentRect
                        + ", radius=" + session.currentCornerRadius);
                return token.status;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to supply Xiaomi local predictive handoff"
                        + ", token=" + shortObject(miuiHomeLocalHandoffToken.get()),
                        throwable);
                return null;
            }
        }

        void discardLocalHandoffStatus(Object implementor, Object params,
                                       String reason) {
            try {
                MiuiHomeLocalHandoffToken token = matchLocalHandoffToken(
                        implementor, params);
                if (token == null
                        || !miuiHomeLocalHandoffToken.compareAndSet(token, null)) {
                    return;
                }
                ReturnHomeSession session = (ReturnHomeSession) token.session;
                if (currentSession != session || session.finished.get() != 0) {
                    return;
                }
                Object currentStatus = invokeAnyMethod(token.windowAnimContext,
                        "getLocalAnimLastStatus", new Object[0]);
                if (currentStatus != token.status) {
                    return;
                }
                invokeAnyMethod(token.windowAnimContext, "setLocalAnimLastStatus",
                        new Object[]{null});
                log(Log.INFO, TAG, "Preserved Xiaomi native local handoff"
                        + ", generation=" + token.generation
                        + ", reason=" + reason);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to discard module local handoff"
                        + ", reason=" + reason, throwable);
            }
        }

        protected MiuiHomeLocalHandoffToken matchLocalHandoffToken(
                Object implementor, Object params) throws Exception {
            MiuiHomeLocalHandoffToken token = miuiHomeLocalHandoffToken.get();
            if (token == null || !(token.session instanceof ReturnHomeSession)) {
                return null;
            }
            ReturnHomeSession session = (ReturnHomeSession) token.session;
            if (currentSession != session || session.finished.get() != 0
                    || !session.nativeHandoffStarted
                    || !session.nativeStatusPublished
                    || session.localHandoffToken != token
                    || token.status != session.nativePublishedStatus) {
                return null;
            }
            Object windowElement = readField(implementor, "windowElement");
            Object windowAnimContext = readField(implementor, "windowAnimContext");
            Object animType = invokeAnyMethod(params, "getAnimType", new Object[0]);
            String typeName = enumName(animType);
            if (windowElement != token.windowElement
                    || windowAnimContext != token.windowAnimContext
                    || !isReturnHomeNativeCloseType(typeName)) {
                return null;
            }
            Object currentStatus = invokeAnyMethod(windowAnimContext,
                    "getLocalAnimLastStatus", new Object[0]);
            return currentStatus == token.status ? token : null;
        }

        void observeUnifiedCommitTransition(
                Object windowElement, Object params) throws Throwable {
            ReturnHomeSession session = currentSession;
            if (Looper.myLooper() != Looper.getMainLooper()
                    || session == null
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || !session.unifiedNativeCommitPending
                    || !session.unifiedNativeCommitReady.get()
                    || session.unifiedNativeCleanupVerified
                    || session.nativeWindowElement != windowElement
                    || params == null
                    || Boolean.TRUE.equals(invokeAnyMethod(
                    params, "isMerge", new Object[0]))) {
                return;
            }
            Object transitionTypeObject = invokeAnyMethod(
                    params, "getTransitionType", new Object[0]);
            Object info = invokeAnyMethod(
                    params, "getTransitionInfo", new Object[0]);
            Object token = invokeAnyMethod(
                    params, "getToken", new Object[0]);
            Object startTransaction = invokeAnyMethod(
                    params, "getT", new Object[0]);
            Object finishCallback = invokeAnyMethod(
                    params, "getFinishCallback", new Object[0]);
            Object mainDebugObject = invokeAnyMethod(
                    params, "getMainInfoDebugId", new Object[0]);
            Object infoTypeObject = info == null ? null
                    : invokeAnyMethod(info, "getType", new Object[0]);
            int transitionType = transitionTypeObject instanceof Number
                    ? ((Number) transitionTypeObject).intValue() : -1;
            int mainDebugId = mainDebugObject instanceof Number
                    ? ((Number) mainDebugObject).intValue() : -1;
            int infoType = infoTypeObject instanceof Number
                    ? ((Number) infoTypeObject).intValue() : -1;
            int infoDebugId = readTransitionDebugId(info);
            Object currentElement = invokeAnyMethod(
                    session.stateManager, "getCurrentWindowElement",
                    new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(windowElement);
            boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isAnimRunning", new Object[0]));
            Object compat = invokeAnyMethod(windowElement,
                    "getWindowTransitionCompat", new Object[0]);
            Object helper = compat == null ? null : invokeAnyMethod(
                    compat, "getCallbackHelper", new Object[0]);
            int compatDebugIdBeforeStart = readIntFieldOrDefault(
                    compat, "mMainTransitionInfoDebugId", -1);
            if (transitionType != TYPE_RETURN_TO_HOME || token == null
                    || startTransaction == null || finishCallback == null
                    || (infoType != TRANSIT_CLOSE
                    && infoType != TRANSIT_TO_BACK)
                    || infoDebugId < 0 || mainDebugId != infoDebugId
                    || compat == null || helper == null
                    || currentElement != windowElement
                    || currentIdentity
                    != session.unifiedNativeAnimationIdentity
                    || !"CLOSE_TO_DRAG".equals(currentType)) {
                return;
            }
            UnifiedNativeCommitTransitionToken previous =
                    session.unifiedNativeCommitTransition;
            if (previous != null
                    && previous.remoteTransitionParams == params
                    && previous.phase.get()
                    != UnifiedNativeCommitTransitionToken.PHASE_INVALID) {
                return;
            }
            if (previous != null) {
                previous.phase.set(
                        UnifiedNativeCommitTransitionToken.PHASE_INVALID);
                session.unifiedNativeCommitAttempt = 0L;
                log(Log.INFO, TAG,
                        "Invalidated superseded Xiaomi commit transition"
                                + ", generation=" + session.generation
                                + ", oldDebugId="
                                + previous.transitionDebugId
                                + ", newDebugId=" + infoDebugId);
            }
            UnifiedNativeCommitTransitionToken accepted =
                    new UnifiedNativeCommitTransitionToken(
                            session, windowElement, currentIdentity,
                            params, compat, helper, token, info,
                            infoDebugId);
            session.unifiedNativeCommitTransition = accepted;
            log(Log.INFO, TAG,
                    "Accepted real Xiaomi return-home transition"
                            + ", generation=" + session.generation
                            + ", debugId=" + infoDebugId
                            + ", infoType=" + infoType
                            + ", compatDebugIdBeforeStart="
                            + compatDebugIdBeforeStart
                            + ", running=" + running
                            + ", animationIdentity="
                            + shortObject(currentIdentity));
        }

        boolean invalidateUnifiedCommitTransition(
                Object windowElement, Object params, String reason) {
            ReturnHomeSession session = currentSession;
            UnifiedNativeCommitTransitionToken transition = session == null
                    ? null : session.unifiedNativeCommitTransition;
            if (session == null || transition == null
                    || transition.session != session
                    || transition.windowElement != windowElement
                    || transition.remoteTransitionParams != params
                    || !transition.phase.compareAndSet(
                    UnifiedNativeCommitTransitionToken.PHASE_PENDING,
                    UnifiedNativeCommitTransitionToken.PHASE_INVALID)) {
                return false;
            }
            if (session.unifiedNativeCommitTransition == transition) {
                session.unifiedNativeCommitTransition = null;
                session.unifiedNativeCommitAttempt = 0L;
                session.unifiedNativeCommitRequestedType = null;
            }
            log(Log.WARN, TAG,
                    "Invalidated failed Xiaomi commit transition injection"
                            + ", generation=" + session.generation
                            + ", debugId="
                            + transition.transitionDebugId
                            + ", reason=" + reason);
            return true;
        }

        boolean prepareElementTransitionContinuity(
                Object windowElement, Object params) throws Throwable {
            ReturnHomeSession session = currentSession;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return false;
            }
            boolean merge = params != null && Boolean.TRUE.equals(
                    invokeAnyMethod(params, "isMerge", new Object[0]));
            boolean verifiedCandidate = session != null
                    && session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && session.nativeAnimationIdentity != null
                    && isReturnHomeNativeCloseType(
                    session.nativeAnimationType);
            boolean provisionalCandidate = session != null
                    && session.unifiedNativePreviewOwned
                    && session.unifiedNativeCommitPending
                    && session.unifiedNativeCommitReady.get()
                    && !session.unifiedNativeCleanupVerified
                    && session.unifiedNativeAnimationIdentity != null
                    && session.nativeAnimationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && "CLOSE_TO_DRAG".equals(
                    session.nativeAnimationType);
            if (session == null || session.finished.get() != 0
                    || !session.nativeHandoffStarted
                    || (!verifiedCandidate && !provisionalCandidate)
                    || session.nativeWindowElement != windowElement
                    || params == null
                    || merge) {
                return false;
            }
            Object transitionTypeObject = invokeAnyMethod(
                    params, "getTransitionType", new Object[0]);
            Object info = invokeAnyMethod(
                    params, "getTransitionInfo", new Object[0]);
            Object transitionToken = invokeAnyMethod(
                    params, "getToken", new Object[0]);
            Object mainDebugObject = invokeAnyMethod(
                    params, "getMainInfoDebugId", new Object[0]);
            Object infoTypeObject = invokeAnyMethod(
                    info, "getType", new Object[0]);
            int transitionType = transitionTypeObject instanceof Number
                    ? ((Number) transitionTypeObject).intValue() : -1;
            int mainDebugId = mainDebugObject instanceof Number
                    ? ((Number) mainDebugObject).intValue() : -1;
            int infoType = infoTypeObject instanceof Number
                    ? ((Number) infoTypeObject).intValue() : -1;
            int infoDebugId = readTransitionDebugId(info);
            // A rejected unified Shell merge can consume the opening Home change before
            // dispatching the remaining exact element/task pair to Xiaomi. That pair keeps
            // TO_BACK on both changes; ordinary island takeover uses CLOSE. Both are closing
            // representations here, but every task/flag/bounds/leash guard below remains exact.
            boolean supportedClosingInfo = infoType == TRANSIT_CLOSE
                    || infoType == TRANSIT_TO_BACK;
            if (transitionType != 1 || transitionToken == null
                    || !supportedClosingInfo || infoDebugId < 0
                    || mainDebugId != infoDebugId) {
                return false;
            }
            int closingTaskId = readIntFieldOrDefault(
                    session.closingTarget, "taskId", -1);
            Object closingTaskInfo = readField(
                    session.closingTarget, "taskInfo");
            int displayId = readIntFieldOrDefault(
                    closingTaskInfo, "displayId", -1);
            if (closingTaskId < 0 || displayId < 0
                    || resolveRemoteTargetActivityType(
                    session.closingTarget) != ACTIVITY_TYPE_STANDARD
                    || resolveRemoteTargetWindowingMode(
                    session.closingTarget) != WINDOWING_MODE_FULLSCREEN
                    || !session.closingLeash.isValid()) {
                return false;
            }
            Object changesObject = invokeAnyMethod(
                    info, "getChanges", new Object[0]);
            if (!(changesObject instanceof List<?>)
                    || ((List<?>) changesObject).size() != 2) {
                return false;
            }
            Object elementChange = null;
            Object appChange = null;
            SurfaceControl elementLeash = null;
            SurfaceControl appLeash = null;
            for (Object change : (List<?>) changesObject) {
                Object modeObject = invokeAnyMethod(
                        change, "getMode", new Object[0]);
                Object flagsObject = invokeAnyMethod(
                        change, "getFlags", new Object[0]);
                int mode = modeObject instanceof Number
                        ? ((Number) modeObject).intValue() : -1;
                int flags = flagsObject instanceof Number
                        ? ((Number) flagsObject).intValue() : 0;
                Object taskInfo = invokeAnyMethod(
                        change, "getTaskInfo", new Object[0]);
                Object leashObject = invokeAnyMethod(
                        change, "getLeash", new Object[0]);
                if (mode != infoType
                        || !(leashObject instanceof SurfaceControl)
                        || !((SurfaceControl) leashObject).isValid()) {
                    return false;
                }
                if (flags == FLAG_IS_ELEMENT && taskInfo == null
                        && elementChange == null) {
                    elementChange = change;
                    elementLeash = (SurfaceControl) leashObject;
                    continue;
                }
                Object startDisplayObject = invokeAnyMethod(
                        change, "getStartDisplayId", new Object[0]);
                Object endDisplayObject = invokeAnyMethod(
                        change, "getEndDisplayId", new Object[0]);
                int startDisplayId = startDisplayObject instanceof Number
                        ? ((Number) startDisplayObject).intValue() : -1;
                int endDisplayId = endDisplayObject instanceof Number
                        ? ((Number) endDisplayObject).intValue() : -1;
                if ((flags == FLAG_BACK_GESTURE_ANIMATED
                        || flags == (FLAG_BACK_GESTURE_ANIMATED
                        | FLAG_DISPLAY_CHANGE))
                        && taskInfo != null && appChange == null
                        && readIntFieldOrDefault(taskInfo, "taskId", -1)
                        == closingTaskId
                        && readIntFieldOrDefault(taskInfo, "displayId", -1)
                        == displayId
                        && startDisplayId == displayId
                        && endDisplayId == displayId) {
                    // This is the terminal CLOSE TaskInfo. WMS has already removed its last
                    // Activity by this point, so TaskInfo.getActivityType()/getWindowingMode()
                    // may read undefined values from the emptied Configuration even though
                    // topActivityType and the transition geometry still describe the same task.
                    // The immutable runner target above already proved standard/fullscreen;
                    // retain exact task/display/flags/bounds/leash identity here instead of
                    // rejecting that valid terminal representation.
                    appChange = change;
                    appLeash = (SurfaceControl) leashObject;
                    continue;
                }
                return false;
            }
            if (elementChange == null || appChange == null
                    || elementLeash == null || appLeash == null
                    || surfacesAreSame(elementLeash, appLeash)
                    || surfacesAreSame(elementLeash, session.closingLeash)
                    || surfacesAreSame(appLeash, session.closingLeash)) {
                return false;
            }
            Object appStartBounds = invokeAnyMethod(
                    appChange, "getStartAbsBounds", new Object[0]);
            Object appEndBounds = invokeAnyMethod(
                    appChange, "getEndAbsBounds", new Object[0]);
            Object elementEndBounds = invokeAnyMethod(
                    elementChange, "getEndAbsBounds", new Object[0]);
            if (!(appStartBounds instanceof Rect)
                    || !(appEndBounds instanceof Rect)
                    || !(elementEndBounds instanceof Rect)
                    || !((Rect) appStartBounds).equals(session.startRect)
                    || !((Rect) appEndBounds).equals(session.startRect)
                    || ((Rect) elementEndBounds).isEmpty()
                    || ((Rect) elementEndBounds).equals(session.startRect)) {
                return false;
            }
            if (provisionalCandidate) {
                session.unifiedNativeProviderBoundaryDebugId = infoDebugId;
                try {
                    if (startUnifiedNativeProviderCommit(session)) {
                        verifiedCandidate = true;
                        provisionalCandidate = false;
                    }
                } finally {
                    session.unifiedNativeProviderBoundaryDebugId = -1;
                }
            }
            Object stateManager = session.stateManager;
            Object currentElement = invokeAnyMethod(
                    stateManager, "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(windowElement);
            boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isAnimRunning", new Object[0]));
            Object expectedAnimationIdentity = provisionalCandidate
                    ? session.unifiedNativeAnimationIdentity
                    : session.nativeAnimationIdentity;
            if (currentElement != windowElement
                    || currentIdentity != expectedAnimationIdentity
                    || !session.nativeAnimationType.equals(currentType)
                    || (!running && !provisionalCandidate)) {
                return false;
            }
            Object compat = invokeAnyMethod(windowElement,
                    "getWindowTransitionCompat", new Object[0]);
            Object helper = invokeAnyMethod(compat,
                    "getCallbackHelper", new Object[0]);
            if (compat == null || helper == null
                    || Boolean.TRUE.equals(invokeAnyMethod(
                    helper, "hasMainFinishCallback", new Object[0]))
                    || Boolean.TRUE.equals(invokeAnyMethod(
                    helper, "isFinishCalled", new Object[0]))) {
                return false;
            }
            invalidateElementTransitionContinuity(
                    null, "replacement", true);
            ReturnHomeElementLeashReuseToken reuseToken =
                    new ReturnHomeElementLeashReuseToken(
                            session, windowElement,
                            expectedAnimationIdentity, compat, helper,
                            info, infoDebugId, closingTaskId, appLeash,
                            elementChange, elementLeash,
                            session.closingLeash);
            pendingElementLeashReuse.set(reuseToken);
            log(Log.INFO, TAG,
                    "Prepared predictive element leash continuity"
                            + ", generation=" + session.generation
                            + ", taskId=" + closingTaskId
                            + ", transitionDebugId=" + infoDebugId
                            + ", transitionInfoType=" + infoType
                            + ", animationType="
                            + session.nativeAnimationType
                            + ", provisional=" + provisionalCandidate);
            return true;
        }

        void hideElementBoundaryProviderFloatingIcon(
                Object windowElement, Object params) throws Throwable {
            ReturnHomeSession session = currentSession;
            if (Looper.myLooper() != Looper.getMainLooper()
                    || session == null
                    || session.nativeWindowElement != windowElement
                    || session.unifiedNativeProviderBoundaryDebugId < 0
                    || params == null) {
                return;
            }
            Object typeObject = invokeAnyMethod(
                    params, "getAnimType", new Object[0]);
            String typeName = enumName(typeObject);
            String currentType = readNativeAnimationType(windowElement);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            Object targetView = invokeAnyMethod(
                    params, "getTargetView", new Object[0]);
            if ((!"CLOSE_TO_HOME".equals(typeName)
                    && !"CLOSE_TO_HOME_CENTER".equals(typeName))
                    || !typeName.equals(currentType)
                    || currentIdentity
                    != session.unifiedNativeAnimationIdentity
                    || targetView == null) {
                return;
            }
            Object context = invokeAnyMethod(
                    windowElement, "getWindowAnimContext", new Object[0]);
            Object floatingIcons = context == null ? null
                    : invokeAnyMethod(
                    context, "getFloatingIcons", new Object[0]);
            if (floatingIcons == null
                    || !floatingIcons.getClass().isArray()) {
                throw new IllegalStateException(
                        "Xiaomi provider has no floating-icon array");
            }
            int count = Array.getLength(floatingIcons);
            Object candidate = null;
            for (int i = 0; i < count; i++) {
                Object floatingIcon = Array.get(floatingIcons, i);
                if (floatingIcon == null
                        || !"com.miui.home.recents.views.FloatingIconView2"
                        .equals(floatingIcon.getClass().getName())
                        || !Boolean.TRUE.equals(invokeAnyMethod(
                        floatingIcon, "isInit", new Object[0]))
                        || invokeAnyMethod(floatingIcon,
                        "getAnimTarget", new Object[0]) != targetView) {
                    continue;
                }
                if (candidate != null) {
                    throw new IllegalStateException(
                            "Xiaomi provider has multiple matching floating icons"
                                    + ", arrayLength=" + count);
                }
                candidate = floatingIcon;
            }
            if (!(candidate instanceof View)) {
                throw new IllegalStateException(
                        "Xiaomi provider has no matching floating icon"
                                + ", arrayLength=" + count);
            }
            View floatingIconView = (View) candidate;
            invokeAnyMethod(candidate, "setIsDrawIcon",
                    new Object[]{Boolean.FALSE});
            floatingIconView.setVisibility(View.INVISIBLE);
            if (!Boolean.FALSE.equals(invokeAnyMethod(
                    candidate, "isDrawIcon", new Object[0]))
                    || floatingIconView.getVisibility() != View.INVISIBLE) {
                throw new IllegalStateException(
                        "Xiaomi floating icon remained drawable");
            }
            log(Log.INFO, TAG,
                    "Retained native Xiaomi floating-icon lifecycle without drawing"
                            + ", generation=" + session.generation
                            + ", transitionDebugId="
                            + session.unifiedNativeProviderBoundaryDebugId
                            + ", type=" + typeName);
        }

        void rearmElementLeashAfterNativeClear(Object helper)
                throws Throwable {
            ReturnHomeElementLeashReuseToken token =
                    pendingElementLeashReuse.get();
            if (token == null || token.helper != helper
                    || !token.phase.compareAndSet(
                    ReturnHomeElementLeashReuseToken.PHASE_PREPARED,
                    ReturnHomeElementLeashReuseToken.PHASE_REARMING)) {
                return;
            }
            try {
                ReturnHomeSession session = token.session;
                Class<?> animBackgroundThreadClass = Class.forName(
                        MIUI_HOME_ANIM_BACKGROUND_THREAD, false,
                        classLoader);
                Method getHandler = animBackgroundThreadClass.getDeclaredMethod(
                        "getHandler");
                getHandler.setAccessible(true);
                Object animHandlerObject = getHandler.invoke(null);
                boolean ownerThread = animHandlerObject instanceof Handler
                        && ((Handler) animHandlerObject).getLooper()
                        == Looper.myLooper();
                boolean valid = currentSession == session
                        && session.finished.get() == 0
                        && session.nativeWindowElement == token.windowElement
                        && session.nativeAnimationIdentity
                        == token.animationIdentity
                        && session.closingLeash == token.closingLeash
                        && token.closingLeash.isValid()
                        && token.appLeash.isValid()
                        && token.elementLeash.isValid()
                        && readTransitionDebugId(token.transitionInfo)
                        == token.transitionDebugId
                        && readIntFieldOrDefault(token.compat,
                        "mMainTransitionInfoDebugId", -1)
                        == token.transitionDebugId
                        && ownerThread;
                if (!valid) {
                    throw new IllegalStateException(
                            "element leash token changed before native clear"
                                    + ", ownerThread=" + ownerThread);
                }
                invokeAnyMethod(helper, "tempSaveOpenLeash",
                        new Object[]{Integer.valueOf(token.taskId),
                                token.closingLeash});
                Object savedLeash = invokeAnyMethod(
                        helper, "getOpenLeash", new Object[0]);
                boolean containsTask = Boolean.TRUE.equals(invokeAnyMethod(
                        helper, "containsTaskId",
                        new Object[]{Integer.valueOf(token.taskId)}));
                if (!(savedLeash instanceof SurfaceControl)
                        || !containsTask || !surfacesAreSame(
                        (SurfaceControl) savedLeash, token.closingLeash)) {
                    throw new IllegalStateException(
                            "Xiaomi helper did not retain predictive leash");
                }
                token.phase.set(
                        ReturnHomeElementLeashReuseToken.PHASE_REARMED);
                log(Log.INFO, TAG,
                        "Rearmed predictive leash after Xiaomi native clear"
                                + ", generation=" + session.generation
                                + ", taskId=" + token.taskId
                                + ", transitionDebugId="
                                + token.transitionDebugId);
            } catch (Throwable throwable) {
                token.phase.set(
                        ReturnHomeElementLeashReuseToken.PHASE_INVALID);
                pendingElementLeashReuse.compareAndSet(token, null);
                try {
                    Object savedLeash = invokeAnyMethod(
                            helper, "getOpenLeash", new Object[0]);
                    boolean containsTask = Boolean.TRUE.equals(
                            invokeAnyMethod(helper, "containsTaskId",
                                    new Object[]{Integer.valueOf(
                                            token.taskId)}));
                    if (containsTask && savedLeash instanceof SurfaceControl
                            && surfacesAreSame((SurfaceControl) savedLeash,
                            token.closingLeash)) {
                        invokeAnyMethod(helper, "clearTempSaveOpenLeash",
                                new Object[0]);
                    }
                } catch (Throwable rollbackFailure) {
                    log(Log.WARN, TAG,
                            "Failed to roll back rejected predictive leash"
                                    + ", generation=" + token.generation,
                            rollbackFailure);
                }
                throw throwable;
            }
        }

        boolean hasEligibleNativeGeometrySession() {
            ReturnHomeSession session = currentSession;
            boolean unifiedPreview = session != null
                    && session.unifiedNativePreviewOwned
                    && !session.unifiedNativeCleanupVerified
                    && session.nativeAnimationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && "CLOSE_TO_DRAG".equals(
                    session.nativeAnimationType);
            boolean nativeClose = session != null
                    && session.nativeHandoffStarted
                    && session.nativeAnimationStarted
                    && session.nativeAnimationIdentity != null
                    && isReturnHomeNativeCloseType(
                    session.nativeAnimationType);
            return session != null
                    && session.finished.get() == 0
                    && (unifiedPreview || nativeClose)
                    && Looper.myLooper() == Looper.getMainLooper();
        }

        protected void logNativeGeometryFailureOnce(
                ReturnHomeSession session, String stage,
                long frameTraceId, Throwable throwable) {
            if (session.nativeGeometryFailureLogged) {
                return;
            }
            session.nativeGeometryFailureLogged = true;
            log(Log.WARN, TAG,
                    "Failed Xiaomi native return geometry"
                            + ", generation=" + session.generation
                            + ", stage=" + stage
                            + ", frameTraceId=" + frameTraceId,
                    throwable);
        }

        protected SurfaceControl surfaceFromNativeTarget(Object target)
                throws Throwable {
            Object leashCompat = target == null ? null
                    : readFieldOrNull(target, "leash");
            Object surface = leashCompat == null ? null
                    : readFieldOrNull(leashCompat, "mSurfaceControl");
            return surface instanceof SurfaceControl
                    ? (SurfaceControl) surface : null;
        }

        ReturnHomeNativeGeometrySnapshot prepareNativeGeometryBeforeAnimUpdate(
                Object implementor, Object currentRectObject,
                Object currentRadii, long frameTraceId) {
            ReturnHomeSession session = currentSession;
            boolean unifiedPreview = session != null
                    && session.unifiedNativePreviewOwned
                    && !session.unifiedNativeCleanupVerified
                    && session.nativeAnimationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && "CLOSE_TO_DRAG".equals(
                    session.nativeAnimationType);
            if (session == null || session.finished.get() != 0
                    || (!unifiedPreview
                    && (!session.nativeHandoffStarted
                    || !session.nativeAnimationStarted))
                    || Looper.myLooper() != Looper.getMainLooper()) {
                return null;
            }
            try {
                Object windowElement = readField(
                        implementor, "windowElement");
                Object animation = readField(implementor, "mAnim");
                String typeName = readNativeAnimationType(windowElement);
                boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                        animation, "isRunning", new Object[0]));
                boolean exact = currentSession == session
                        && session.finished.get() == 0
                        && session.nativeWindowElement == windowElement
                        && session.nativeAnimationIdentity == animation
                        && session.nativeAnimationType.equals(typeName)
                        && ("CLOSE_TO_DRAG".equals(typeName)
                        || isReturnHomeNativeCloseType(typeName))
                        && running && currentRectObject instanceof RectF
                        && currentRadii != null;
                if (!exact) {
                    return null;
                }
                RectF currentRect = new RectF((RectF) currentRectObject);
                Rect fullscreen = session.startRect;
                if (currentRect.isEmpty() || fullscreen.isEmpty()
                        || fullscreen.left != 0 || fullscreen.top != 0) {
                    throw new IllegalStateException(
                            "native geometry is not fullscreen");
                }
                // Xiaomi's vertical-island path crops the source before applying one
                // uniform matrix. currentRect.height() therefore includes the crop and
                // cannot be used to infer the matrix's Y scale. Width remains the exact
                // fullscreen source width on this guarded single-task path.
                float scale = currentRect.width() / fullscreen.width();
                if (!Float.isFinite(scale) || scale <= 0.0f
                        || scale > 1.05f) {
                    throw new IllegalStateException(
                            "native geometry scale mismatch: " + scale);
                }
                float sourceCropHeight = currentRect.height() / scale;
                int cropHeight = (int) Math.ceil(sourceCropHeight - 0.01f);
                if (!Float.isFinite(sourceCropHeight) || cropHeight <= 0
                        || cropHeight > fullscreen.height()) {
                    throw new IllegalStateException(
                            "native geometry crop mismatch: visible="
                                    + currentRect.height() + ", scale=" + scale
                                    + ", source=" + sourceCropHeight
                                    + ", fullscreen=" + fullscreen);
                }
                Rect crop = new Rect(0, 0, fullscreen.width(), cropHeight);
                Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                matrix.postTranslate(currentRect.left, currentRect.top);
                float[] matrixValues = new float[9];
                matrix.getValues(matrixValues);
                float[] physicalRadii = readNativeCornerRadii(currentRadii);
                float[] surfaceRadii = new float[physicalRadii.length];
                for (int index = 0; index < physicalRadii.length; index++) {
                    surfaceRadii[index] = physicalRadii[index] / scale;
                }
                ReturnHomeNativeGeometrySnapshot snapshot =
                        createNativeGeometrySnapshot(session, animation,
                                matrixValues, crop, surfaceRadii, frameTraceId,
                                RETURN_HOME_GEOMETRY_SOURCE_ANIM_UPDATE);
                publishNativeGeometrySnapshot(session, snapshot);
                return snapshot;
            } catch (Throwable throwable) {
                logNativeGeometryFailureOnce(
                        session, "animUpdate", frameTraceId, throwable);
                return null;
            }
        }

        ReturnHomeNativeGeometrySnapshot captureNativeGeometryFromSurfaceParams(
                long frameTraceId, Object surfaceParams) {
            ReturnHomeSession session = currentSession;
            boolean unifiedPreview = session != null
                    && session.unifiedNativePreviewOwned
                    && !session.unifiedNativeCleanupVerified
                    && session.nativeAnimationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && "CLOSE_TO_DRAG".equals(
                    session.nativeAnimationType);
            if (session == null || session.finished.get() != 0
                    || (!unifiedPreview
                    && (!session.nativeHandoffStarted
                    || !session.nativeAnimationStarted))
                    || Looper.myLooper() != Looper.getMainLooper()
                    || surfaceParams == null
                    || !surfaceParams.getClass().isArray()) {
                return null;
            }
            try {
                boolean exactSession = currentSession == session
                        && session.finished.get() == 0
                        && session.nativeAnimationIdentity != null
                        && ("CLOSE_TO_DRAG".equals(
                        session.nativeAnimationType)
                        || isReturnHomeNativeCloseType(
                        session.nativeAnimationType))
                        && session.closingLeash != null
                        && session.closingLeash.isValid();
                if (!exactSession) {
                    return null;
                }
                ReturnHomeNativeGeometrySnapshot captured = null;
                int count = java.lang.reflect.Array.getLength(surfaceParams);
                for (int index = 0; index < count; index++) {
                    Object params = java.lang.reflect.Array.get(
                            surfaceParams, index);
                    Object surfaceObject = readFieldOrNull(params, "surface");
                    if (!(surfaceObject instanceof SurfaceControl)
                            || !((SurfaceControl) surfaceObject).isValid()
                            || !surfacesAreSame((SurfaceControl) surfaceObject,
                            session.closingLeash)) {
                        continue;
                    }
                    Object flagsObject = readFieldOrNull(params, "flags");
                    int flags = flagsObject instanceof Number
                            ? ((Number) flagsObject).intValue() : 0;
                    int requiredFlags = MIUI_SURFACE_PARAM_FLAG_MATRIX
                            | MIUI_SURFACE_PARAM_FLAG_WINDOW_CROP
                            | MIUI_SURFACE_PARAM_FLAG_CORNER_RADIUS
                            | MIUI_SURFACE_PARAM_FLAG_SHOW;
                    Object matrixObject = readFieldOrNull(params, "matrix");
                    Object cropObject = readFieldOrNull(params, "windowCrop");
                    Object showObject = readFieldOrNull(params, "isShow");
                    if ((flags & requiredFlags) != requiredFlags
                            || !Boolean.TRUE.equals(showObject)
                            || !(matrixObject instanceof Matrix)
                            || !(cropObject instanceof Rect)) {
                        continue;
                    }
                    if (captured != null) {
                        throw new IllegalStateException(
                                "multiple closing geometry SurfaceParams");
                    }
                    float[] matrixValues = new float[9];
                    ((Matrix) matrixObject).getValues(matrixValues);
                    float[] surfaceRadii =
                            readSurfaceParamsCornerRadii(params);
                    captured = createNativeGeometrySnapshot(
                            session, session.nativeAnimationIdentity,
                            matrixValues, (Rect) cropObject, surfaceRadii,
                            frameTraceId,
                            RETURN_HOME_GEOMETRY_SOURCE_SURFACE_PARAMS);
                }
                if (captured == null) {
                    return null;
                }
                publishNativeGeometrySnapshot(session, captured);
                return captured;
            } catch (Throwable throwable) {
                // The anim-update snapshot remains the guarded fallback for this frame.
                return null;
            }
        }

        protected void publishNativeGeometrySnapshot(
                ReturnHomeSession session,
                ReturnHomeNativeGeometrySnapshot snapshot) {
            if (session == null || snapshot == null
                    || currentSession != session
                    || session.generation != snapshot.generation
                    || session.nativeAnimationIdentity
                    != snapshot.animationIdentity
                    || session.finished.get() != 0
                    || Looper.myLooper() != Looper.getMainLooper()) {
                return;
            }
            ReturnHomeNativeGeometrySnapshot previous =
                    session.nativeGeometrySnapshot.get();
            if (previous != null
                    && (previous.frameTraceId > snapshot.frameTraceId
                    || (previous.frameTraceId == snapshot.frameTraceId
                    && previous.sourceKind >= snapshot.sourceKind))) {
                return;
            }
            session.nativeGeometrySnapshot.set(snapshot);
        }

        Object resolveNativeGeometryFrameApplyLock(
                long frameTraceId, Object applier,
                ReturnHomeNativeGeometrySnapshot pendingSnapshot,
                Object surfaceParams) {
            ReturnHomeSession session = currentSession;
            boolean exact = applier == null && session != null
                    && Looper.myLooper() == Looper.getMainLooper()
                    && session.finished.get() == 0
                    && session.nativeHandoffStarted
                    && session.nativeAnimationStarted
                    && ("CLOSE_TO_HOME".equals(session.nativeAnimationType)
                    || "CLOSE_TO_HOME_CENTER".equals(
                    session.nativeAnimationType))
                    && pendingSnapshot != null
                    && pendingSnapshot.generation == session.generation
                    && pendingSnapshot.animationIdentity
                    == session.nativeAnimationIdentity
                    && pendingSnapshot.frameTraceId == frameTraceId;
            if (!exact || surfaceParams == null
                    || !surfaceParams.getClass().isArray()) {
                return null;
            }
            try {
                int count = java.lang.reflect.Array.getLength(
                        surfaceParams);
                for (int index = 0; index < count; index++) {
                    Object params = java.lang.reflect.Array.get(
                            surfaceParams, index);
                    Object surface = readFieldOrNull(params, "surface");
                    if (!(surface instanceof SurfaceControl)
                            || !((SurfaceControl) surface).isValid()
                            || !surfacesAreSame(
                            (SurfaceControl) surface,
                            session.closingLeash)) {
                        continue;
                    }
                    if (nativeGeometryMatchesSurfaceParams(
                            pendingSnapshot, params)) {
                        return session.nativeGeometryApplyLock;
                    }
                }
            } catch (Throwable throwable) {
                logNativeGeometryFailureOnce(
                        session, "surfaceParams", frameTraceId, throwable);
            }
            return null;
        }

        protected ReturnHomeNativeGeometrySnapshot createNativeGeometrySnapshot(
                ReturnHomeSession session, Object animationIdentity,
                float[] matrixValues, Rect crop, float[] surfaceRadii,
                long frameTraceId, int sourceKind) {
            if (session == null || animationIdentity == null
                    || matrixValues == null || matrixValues.length != 9
                    || crop == null || crop.isEmpty()
                    || surfaceRadii == null || surfaceRadii.length != 4) {
                throw new IllegalStateException(
                        "incomplete native geometry snapshot");
            }
            for (float value : matrixValues) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException(
                            "non-finite native matrix value");
                }
            }
            float scaleX = matrixValues[Matrix.MSCALE_X];
            float scaleY = matrixValues[Matrix.MSCALE_Y];
            if (scaleX <= 0.0f || scaleX > 1.05f
                    || scaleY <= 0.0f || scaleY > 1.05f
                    || Math.abs(scaleX - scaleY) > 0.002f
                    || Math.abs(matrixValues[Matrix.MSKEW_X]) > 0.002f
                    || Math.abs(matrixValues[Matrix.MSKEW_Y]) > 0.002f
                    || Math.abs(matrixValues[Matrix.MPERSP_0]) > 0.0001f
                    || Math.abs(matrixValues[Matrix.MPERSP_1]) > 0.0001f
                    || Math.abs(matrixValues[Matrix.MPERSP_2] - 1.0f)
                    > 0.0001f) {
                throw new IllegalStateException(
                        "unsupported native matrix: "
                                + java.util.Arrays.toString(matrixValues));
            }
            Rect fullscreen = session.startRect;
            if (fullscreen.isEmpty() || fullscreen.left != 0
                    || fullscreen.top != 0 || crop.left != 0
                    || crop.top != 0
                    || Math.abs(crop.width() - fullscreen.width()) > 1
                    || crop.height() > fullscreen.height()) {
                throw new IllegalStateException(
                        "unsupported native crop: fullscreen="
                                + fullscreen + ", crop=" + crop);
            }
            for (float radius : surfaceRadii) {
                if (!Float.isFinite(radius) || radius < 0.0f) {
                    throw new IllegalStateException(
                            "invalid native surface radius: " + radius);
                }
            }
            return new ReturnHomeNativeGeometrySnapshot(
                    session.generation, animationIdentity,
                    matrixValues, crop, surfaceRadii, frameTraceId,
                    sourceKind);
        }

        protected boolean nativeGeometryMatchesSurfaceParams(
                ReturnHomeNativeGeometrySnapshot snapshot, Object params)
                throws Throwable {
            if (snapshot == null || params == null) {
                return false;
            }
            Object flagsObject = readFieldOrNull(params, "flags");
            int flags = flagsObject instanceof Number
                    ? ((Number) flagsObject).intValue() : 0;
            int requiredFlags = MIUI_SURFACE_PARAM_FLAG_MATRIX
                    | MIUI_SURFACE_PARAM_FLAG_WINDOW_CROP
                    | MIUI_SURFACE_PARAM_FLAG_CORNER_RADIUS
                    | MIUI_SURFACE_PARAM_FLAG_SHOW;
            Object matrixObject = readFieldOrNull(params, "matrix");
            Object cropObject = readFieldOrNull(params, "windowCrop");
            if ((flags & requiredFlags) != requiredFlags
                    || !Boolean.TRUE.equals(
                    readFieldOrNull(params, "isShow"))
                    || !(matrixObject instanceof Matrix)
                    || !(cropObject instanceof Rect)
                    || !snapshot.copyWindowCrop().equals(cropObject)) {
                return false;
            }
            float[] expectedMatrix = snapshot.copyMatrixValues();
            float[] actualMatrix = new float[9];
            ((Matrix) matrixObject).getValues(actualMatrix);
            for (int index = 0; index < expectedMatrix.length; index++) {
                if (Math.abs(expectedMatrix[index] - actualMatrix[index])
                        > 0.002f) {
                    return false;
                }
            }
            float[] expectedRadii = snapshot.copySurfaceCornerRadii();
            float[] actualRadii = readSurfaceParamsCornerRadii(params);
            for (int index = 0; index < expectedRadii.length; index++) {
                if (Math.abs(expectedRadii[index] - actualRadii[index])
                        > 0.02f) {
                    return false;
                }
            }
            return true;
        }

        protected float[] readSurfaceParamsCornerRadii(Object params)
                throws Throwable {
            Object radii = readFieldOrNull(params, "radii");
            if (radii != null) {
                return readNativeCornerRadii(radii);
            }
            Object cornerRadius = readFieldOrNull(params, "cornerRadius");
            if (!(cornerRadius instanceof Number)) {
                throw new IllegalStateException(
                        "SurfaceParams corner radius is missing");
            }
            float radius = ((Number) cornerRadius).floatValue();
            if (!Float.isFinite(radius) || radius < 0.0f) {
                throw new IllegalStateException(
                        "invalid SurfaceParams corner radius: " + radius);
            }
            return new float[]{radius, radius, radius, radius};
        }

        protected float[] readNativeCornerRadii(Object radii)
                throws Throwable {
            String[] getters = new String[]{
                    "getRadiusTL", "getRadiusTR",
                    "getRadiusBR", "getRadiusBL"};
            float[] result = new float[getters.length];
            for (int index = 0; index < getters.length; index++) {
                String getter = getters[index];
                Object value = invokeAnyMethod(
                        radii, getter, new Object[0]);
                if (!(value instanceof Number)) {
                    throw new IllegalStateException(
                            "corner radius is not numeric: " + getter);
                }
                float radius = ((Number) value).floatValue();
                if (!Float.isFinite(radius) || radius < 0.0f) {
                    throw new IllegalStateException(
                            "invalid corner radius: " + radius);
                }
                result[index] = radius;
            }
            return result;
        }

        void armElementAndClosingLeashStartGeometry(
                Object leashObject, Object change, Object transitionInfo,
                Object transactionObject) {
            ReturnHomeElementLeashReuseToken token =
                    pendingElementLeashReuse.get();
            if (token == null || token.elementChange != change
                    || token.transitionInfo != transitionInfo
                    || !(leashObject instanceof SurfaceControl)
                    || !(transactionObject
                    instanceof SurfaceControl.Transaction)) {
                return;
            }
            try {
                if (!surfacesAreSame((SurfaceControl) leashObject,
                        token.elementLeash)) {
                    return;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed element leash identity check before geometry seed",
                        throwable);
                return;
            }
            if (!token.startGeometrySeed.compareAndSet(
                    ReturnHomeElementLeashReuseToken.SEED_PENDING,
                    ReturnHomeElementLeashReuseToken.SEEDING)) {
                return;
            }
            try {
                ReturnHomeSession session = token.session;
                Class<?> animBackgroundThreadClass = Class.forName(
                        MIUI_HOME_ANIM_BACKGROUND_THREAD, false,
                        classLoader);
                Method getHandler = animBackgroundThreadClass
                        .getDeclaredMethod("getHandler");
                getHandler.setAccessible(true);
                Object handlerObject = getHandler.invoke(null);
                boolean ownerThread = handlerObject instanceof Handler
                        && ((Handler) handlerObject).getLooper()
                        == Looper.myLooper();
                Object startBoundsObject = invokeAnyMethod(
                        change, "getStartAbsBounds", new Object[0]);
                boolean exact = ownerThread
                        && pendingElementLeashReuse.get() == token
                        && currentSession == session
                        && session.finished.get() == 0
                        && token.phase.get()
                        == ReturnHomeElementLeashReuseToken.PHASE_REARMED
                        && session.nativeWindowElement
                        == token.windowElement
                        && session.nativeAnimationIdentity
                        == token.animationIdentity
                        && token.elementLeash.isValid()
                        && token.closingLeash.isValid()
                        && session.closingLeash == token.closingLeash
                        && readTransitionDebugId(transitionInfo)
                        == token.transitionDebugId
                        && startBoundsObject instanceof Rect
                        && ((Rect) startBoundsObject).equals(
                        session.startRect);
                if (!exact) {
                    throw new IllegalStateException(
                            "element/closing geometry arm ownership changed"
                                    + ", ownerThread=" + ownerThread
                                    + ", tokenPhase=" + token.phase.get()
                                    + ", startBounds=" + startBoundsObject);
                }
                Object savedLeash = invokeAnyMethod(
                        token.helper, "getOpenLeash", new Object[0]);
                boolean containsTask = Boolean.TRUE.equals(invokeAnyMethod(
                        token.helper, "containsTaskId",
                        new Object[]{Integer.valueOf(token.taskId)}));
                if (!(savedLeash instanceof SurfaceControl)
                        || !containsTask
                        || !surfacesAreSame((SurfaceControl) savedLeash,
                        token.closingLeash)) {
                    throw new IllegalStateException(
                            "Xiaomi helper no longer owns predictive closing leash");
                }
                SurfaceControl.Transaction startTransaction =
                        (SurfaceControl.Transaction) transactionObject;
                synchronized (token) {
                    boolean stillOwned = pendingElementLeashReuse.get() == token
                            && currentSession == session
                            && session.finished.get() == 0
                            && token.phase.get()
                            == ReturnHomeElementLeashReuseToken
                            .PHASE_REARMED
                            && token.startGeometrySeed.get()
                            == ReturnHomeElementLeashReuseToken.SEEDING;
                    if (!stillOwned) {
                        throw new IllegalStateException(
                                "element/closing geometry arm invalidated");
                    }
                    if (token.startTransaction != null
                            && token.startTransaction
                            != startTransaction) {
                        throw new IllegalStateException(
                                "element/closing geometry start transaction changed");
                    }
                    token.startTransaction = startTransaction;
                    token.startGeometrySeed.set(
                            ReturnHomeElementLeashReuseToken.SEED_APPLIED);
                }
                log(Log.INFO, TAG,
                        "Armed predictive closing geometry for transition apply"
                                + ", generation=" + token.generation
                                + ", taskId=" + token.taskId
                                + ", transitionDebugId="
                                + token.transitionDebugId);
            } catch (Throwable throwable) {
                token.startGeometrySeed.compareAndSet(
                        ReturnHomeElementLeashReuseToken.SEEDING,
                        ReturnHomeElementLeashReuseToken.SEED_INVALID);
                log(Log.WARN, TAG,
                        "Rejected element/closing transition-start geometry arm"
                                + ", generation=" + token.generation
                                + ", taskId=" + token.taskId
                                + ", transitionDebugId="
                                + token.transitionDebugId,
                        throwable);
            }
        }

        Object resolveStartGeometryApplyLock(
                Object transaction, List<?> arguments) {
            ReturnHomeElementLeashReuseToken token =
                    pendingElementLeashReuse.get();
            if (token == null || token.startTransaction != transaction
                    || token.startGeometrySeed.get()
                    != ReturnHomeElementLeashReuseToken.SEED_APPLIED
                    || arguments == null || arguments.size() != 1
                    || !Boolean.TRUE.equals(arguments.get(0))) {
                return null;
            }
            try {
                Class<?> animBackgroundThreadClass = Class.forName(
                        MIUI_HOME_ANIM_BACKGROUND_THREAD, false,
                        classLoader);
                Method getHandler = animBackgroundThreadClass
                        .getDeclaredMethod("getHandler");
                getHandler.setAccessible(true);
                Object handlerObject = getHandler.invoke(null);
                boolean ownerThread = handlerObject instanceof Handler
                        && ((Handler) handlerObject).getLooper()
                        == Looper.myLooper();
                ReturnHomeSession session = token.session;
                boolean exact = ownerThread
                        && pendingElementLeashReuse.get() == token
                        && currentSession == session
                        && session.finished.get() == 0
                        && token.phase.get()
                        == ReturnHomeElementLeashReuseToken.PHASE_REARMED
                        && session.nativeWindowElement
                        == token.windowElement
                        && session.nativeAnimationIdentity
                        == token.animationIdentity
                        && session.closingLeash == token.closingLeash
                        && token.elementLeash.isValid()
                        && token.closingLeash.isValid();
                return exact ? session.nativeGeometryApplyLock : null;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to resolve return-home start transaction apply lock"
                                + ", generation=" + token.generation
                                + ", taskId=" + token.taskId,
                        throwable);
                return null;
            }
        }

        void refreshStartGeometryAtApply(Object transaction) {
            ReturnHomeElementLeashReuseToken token =
                    pendingElementLeashReuse.get();
            if (token == null || token.startTransaction != transaction
                    || !token.startGeometrySeed.compareAndSet(
                    ReturnHomeElementLeashReuseToken.SEED_APPLIED,
                    ReturnHomeElementLeashReuseToken.SEED_REFRESHING)) {
                return;
            }
            try {
                ReturnHomeSession session = token.session;
                ReturnHomeNativeGeometrySnapshot snapshot =
                        session.nativeGeometrySnapshot.get();
                boolean exact = pendingElementLeashReuse.get() == token
                        && currentSession == session
                        && session.finished.get() == 0
                        && token.phase.get()
                        == ReturnHomeElementLeashReuseToken.PHASE_REARMED
                        && session.nativeWindowElement
                        == token.windowElement
                        && session.nativeAnimationIdentity
                        == token.animationIdentity
                        && session.closingLeash == token.closingLeash
                        && token.elementLeash.isValid()
                        && token.closingLeash.isValid()
                        && readTransitionDebugId(token.transitionInfo)
                        == token.transitionDebugId
                        && snapshot != null
                        && session.nativeGeometrySnapshot.get() == snapshot
                        && snapshot.generation == token.generation
                        && snapshot.animationIdentity
                        == token.animationIdentity;
                if (!exact) {
                    throw new IllegalStateException(
                            "start geometry apply ownership changed"
                                    + ", tokenPhase=" + token.phase.get()
                                    + ", snapshot=" + shortObject(snapshot)
                                    + ", snapshotGeneration="
                                    + (snapshot == null ? -1L
                                    : snapshot.generation));
                }
                Object savedLeash = invokeAnyMethod(
                        token.helper, "getOpenLeash", new Object[0]);
                boolean containsTask = Boolean.TRUE.equals(invokeAnyMethod(
                        token.helper, "containsTaskId",
                        new Object[]{Integer.valueOf(token.taskId)}));
                if (!(savedLeash instanceof SurfaceControl)
                        || !containsTask
                        || !surfacesAreSame((SurfaceControl) savedLeash,
                        token.closingLeash)) {
                    throw new IllegalStateException(
                            "Xiaomi helper lost the predictive closing leash before apply");
                }
                float[] matrixValues = snapshot.copyMatrixValues();
                Rect crop = snapshot.copyWindowCrop();
                float[] surfaceRadii =
                        snapshot.copySurfaceCornerRadii();
                try (SurfaceControl.Transaction refreshTransaction =
                             new SurfaceControl.Transaction()) {
                    Matrix refreshMatrix = new Matrix();
                    refreshMatrix.setValues(matrixValues);
                    // The element leash belongs to Xiaomi's native island animation. Only
                    // preserve the predictive geometry on the real application task leash;
                    // CLOSE_TO_ELEMENT must retain its native element geometry and spring.
                    invokeAnyMethod(refreshTransaction, "setMatrix",
                            new Object[]{token.closingLeash,
                                    refreshMatrix, new float[9]});
                    invokeAnyMethod(refreshTransaction, "setWindowCrop",
                            new Object[]{token.closingLeash, crop});
                    applyNativeSurfaceCornerRadii(
                            refreshTransaction, token.closingLeash,
                            surfaceRadii);
                    if (pendingElementLeashReuse.get() != token
                            || currentSession != session
                            || session.finished.get() != 0
                            || token.startGeometrySeed.get()
                            != ReturnHomeElementLeashReuseToken
                            .SEED_REFRESHING) {
                        throw new IllegalStateException(
                                "start geometry changed during apply refresh");
                    }
                    ((SurfaceControl.Transaction) transaction).merge(
                            refreshTransaction);
                }
                log(Log.INFO, TAG,
                        "Refreshed return-home start geometry at apply boundary"
                                + ", generation=" + token.generation
                                + ", taskId=" + token.taskId
                                + ", transitionDebugId="
                                + token.transitionDebugId
                                + ", frameTraceId="
                                + snapshot.frameTraceId);
            } catch (Throwable throwable) {
                token.startGeometrySeed.set(
                        ReturnHomeElementLeashReuseToken.SEED_INVALID);
                log(Log.WARN, TAG,
                        "Failed to refresh return-home start geometry at apply boundary"
                                + ", generation=" + token.generation
                                + ", taskId=" + token.taskId
                                + ", transitionDebugId="
                                + token.transitionDebugId,
                        throwable);
            }
        }

        protected void applyNativeSurfaceCornerRadii(
                SurfaceControl.Transaction transaction,
                SurfaceControl surface, float[] radii) throws Throwable {
            if (transaction == null || surface == null || radii == null
                    || radii.length != 4) {
                throw new IllegalStateException(
                        "incomplete native corner-radii apply");
            }
            boolean uniform = Math.abs(radii[0] - radii[1]) <= 0.01f
                    && Math.abs(radii[1] - radii[2]) <= 0.01f
                    && Math.abs(radii[2] - radii[3]) <= 0.01f;
            if (uniform) {
                invokeAnyMethod(transaction, "setCornerRadius",
                        new Object[]{surface, Float.valueOf(radii[0])});
                return;
            }
            float[] miRadii = new float[]{
                    radii[0], radii[0], radii[1], radii[1],
                    radii[2], radii[2], radii[3], radii[3]};
            invokeAnyMethod(transaction, "setMiCornerRadii",
                    new Object[]{surface, miRadii});
        }

        void finishStartGeometryApply(
                Object transaction, boolean applied) {
            ReturnHomeElementLeashReuseToken token =
                    pendingElementLeashReuse.get();
            if (token == null || token.startTransaction != transaction) {
                return;
            }
            int expected = ReturnHomeElementLeashReuseToken.SEED_REFRESHING;
            int result = applied
                    ? ReturnHomeElementLeashReuseToken.SEED_COMMITTED
                    : ReturnHomeElementLeashReuseToken.SEED_INVALID;
            boolean changed = token.startGeometrySeed.compareAndSet(
                    expected, result);
            log(applied && changed ? Log.INFO : Log.WARN, TAG,
                    "Finished return-home start geometry apply"
                            + ", generation=" + token.generation
                            + ", taskId=" + token.taskId
                            + ", transitionDebugId="
                            + token.transitionDebugId
                            + ", applied=" + applied
                            + ", phaseChanged=" + changed
                            + ", phase="
                            + token.startGeometrySeed.get());
            Object pendingAnimParams = token.pendingAnimParams;
            if (applied && changed) {
                handler.post(() -> {
                    if (pendingElementLeashReuse.get() != token
                            || token.startGeometrySeed.get()
                            != ReturnHomeElementLeashReuseToken
                            .SEED_COMMITTED) {
                        return;
                    }
                    if (pendingAnimParams != null) {
                        try {
                            adoptElementTransitionIfStarted(
                                    token.windowElement,
                                    pendingAnimParams);
                        } catch (Throwable throwable) {
                            log(Log.WARN, TAG,
                                    "Failed delayed predictive element adoption after geometry commit"
                                            + ", generation="
                                            + token.generation
                                            + ", taskId="
                                            + token.taskId,
                                    throwable);
                        }
                    }
                    consumeUnifiedNativeFinishSnapshot(
                            token.session,
                            "elementStartGeometryCommitted");
                });
            }
        }

        void adoptElementTransitionIfStarted(
                Object windowElement, Object params) throws Throwable {
            adoptUnifiedStandardCommitIfStarted(windowElement, params);
            adoptUnifiedNativeCommitIfStarted(windowElement, params);
            ReturnHomeElementLeashReuseToken token =
                    pendingElementLeashReuse.get();
            if (token == null || token.windowElement != windowElement
                    || token.phase.get()
                    != ReturnHomeElementLeashReuseToken.PHASE_REARMED
                    || Looper.myLooper() != Looper.getMainLooper()
                    || params == null) {
                return;
            }
            Object typeObject = invokeAnyMethod(
                    params, "getAnimType", new Object[0]);
            String typeName = enumName(typeObject);
            if (!"CLOSE_TO_ELEMENT".equals(typeName)) {
                return;
            }
            if (token.pendingAnimParams == null) {
                token.pendingAnimParams = params;
            } else if (token.pendingAnimParams != params) {
                return;
            }
            ReturnHomeSession session = token.session;
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(windowElement);
            boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isAnimRunning", new Object[0]));
            Object targetSet = invokeAnyMethod(
                    params, "getTargetApps", new Object[0]);
            Object firstTarget = targetSet == null ? null
                    : invokeAnyMethod(targetSet,
                    "getFirstTarget", new Object[0]);
            Object leashCompat = firstTarget == null ? null
                    : readField(firstTarget, "leash");
            Object adoptedLeash = leashCompat == null ? null
                    : readField(leashCompat, "mSurfaceControl");
            boolean valid = currentSession == session
                    && session.finished.get() == 0
                    && session.nativeWindowElement == windowElement
                    && currentIdentity == token.animationIdentity
                    && "CLOSE_TO_ELEMENT".equals(currentType)
                    && token.startGeometrySeed.get()
                    == ReturnHomeElementLeashReuseToken.SEED_COMMITTED
                    && running
                    && adoptedLeash instanceof SurfaceControl
                    && ((SurfaceControl) adoptedLeash).isValid()
                    && surfacesAreSame((SurfaceControl) adoptedLeash,
                    token.closingLeash);
            if (!valid) {
                log(Log.WARN, TAG,
                        "Rejected predictive element leash adoption"
                                + ", generation=" + session.generation
                                + ", currentType=" + currentType
                                + ", running=" + running
                                + ", geometrySeed="
                                + token.startGeometrySeed.get());
                return;
            }
            if (!token.phase.compareAndSet(
                    ReturnHomeElementLeashReuseToken.PHASE_REARMED,
                    ReturnHomeElementLeashReuseToken.PHASE_ADOPTED)) {
                return;
            }
            session.nativeAnimationType = "CLOSE_TO_ELEMENT";
            log(Log.INFO, TAG,
                    "Adopted predictive leash for Xiaomi CLOSE_TO_ELEMENT"
                            + ", generation=" + session.generation
                            + ", taskId=" + token.taskId
                            + ", transitionDebugId="
                            + token.transitionDebugId);
        }

        protected boolean isExactUnifiedCommitTransition(
                ReturnHomeSession session,
                UnifiedNativeCommitTransitionToken transition,
                Object windowElement,
                int requiredPhase) throws Throwable {
            if (session == null || transition == null
                    || transition.session != session
                    || transition.generation != session.generation
                    || session.unifiedNativeCommitTransition
                    != transition
                    || transition.phase.get() != requiredPhase
                    || transition.windowElement != windowElement
                    || transition.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || transition.transitionToken == null
                    || transition.transitionInfo == null
                    || readTransitionDebugId(
                    transition.transitionInfo)
                    != transition.transitionDebugId) {
                return false;
            }
            Object currentCompat = invokeAnyMethod(windowElement,
                    "getWindowTransitionCompat", new Object[0]);
            Object currentHelper = currentCompat == null ? null
                    : invokeAnyMethod(currentCompat,
                    "getCallbackHelper", new Object[0]);
            if (currentCompat != transition.compat
                    || currentHelper != transition.helper
                    || readIntFieldOrDefault(currentCompat,
                    "mMainTransitionInfoDebugId", -1)
                    != transition.transitionDebugId) {
                return false;
            }
            Object remoteParams = transition.remoteTransitionParams;
            Object mainDebugObject = invokeAnyMethod(
                    remoteParams, "getMainInfoDebugId", new Object[0]);
            return invokeAnyMethod(remoteParams,
                    "getToken", new Object[0])
                    == transition.transitionToken
                    && invokeAnyMethod(remoteParams, "getTransitionInfo",
                    new Object[0]) == transition.transitionInfo
                    && mainDebugObject instanceof Number
                    && ((Number) mainDebugObject).intValue()
                    == transition.transitionDebugId;
        }

        protected boolean hasCommittedUnifiedElementGeometry(
                ReturnHomeSession session,
                UnifiedNativeCommitTransitionToken transition,
                Object windowElement, Object animationIdentity) {
            ReturnHomeElementLeashReuseToken token =
                    pendingElementLeashReuse.get();
            return token != null && transition != null
                    && token.session == session
                    && token.windowElement == windowElement
                    && token.animationIdentity == animationIdentity
                    && token.compat == transition.compat
                    && token.helper == transition.helper
                    && token.transitionInfo
                    == transition.transitionInfo
                    && token.transitionDebugId
                    == transition.transitionDebugId
                    && token.closingLeash == session.closingLeash
                    && token.startGeometrySeed.get()
                    == ReturnHomeElementLeashReuseToken.SEED_COMMITTED;
        }

        protected void adoptUnifiedStandardCommitIfStarted(
                Object windowElement, Object params) throws Throwable {
            ReturnHomeSession session = currentSession;
            UnifiedNativeStandardCommitToken token = session == null
                    ? null : session.unifiedNativeStandardCommit;
            if (Looper.myLooper() != Looper.getMainLooper()
                    || session == null || token == null
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || !session.unifiedNativeCommitPending
                    || session.unifiedNativeCleanupVerified
                    || token.session != session
                    || token.windowElement != windowElement
                    || token.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || params == null
                    || token.animParams.get() != params) {
                return;
            }
            int phase = token.phase.get();
            if (phase == UnifiedNativeStandardCommitToken.PHASE_ENTERING) {
                if (!token.phase.compareAndSet(
                        UnifiedNativeStandardCommitToken.PHASE_ENTERING,
                        UnifiedNativeStandardCommitToken.PHASE_ENTERED)) {
                    return;
                }
            } else if (phase
                    != UnifiedNativeStandardCommitToken.PHASE_ENTERED) {
                return;
            }
            Object typeObject = invokeAnyMethod(
                    params, "getAnimType", new Object[0]);
            String typeName = enumName(typeObject);
            if (!"CLOSE_TO_HOME".equals(typeName)
                    && !"CLOSE_TO_HOME_CENTER".equals(typeName)) {
                return;
            }
            Object currentElement = invokeAnyMethod(
                    session.stateManager,
                    "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            Object targetSet = invokeAnyMethod(
                    params, "getTargetApps", new Object[0]);
            if (currentElement != windowElement
                    || currentIdentity != token.animationIdentity
                    || resolveUnifiedNativeClosingTarget(
                    session, targetSet) == null) {
                return;
            }
            if (!token.phase.compareAndSet(
                    UnifiedNativeStandardCommitToken.PHASE_ENTERED,
                    UnifiedNativeStandardCommitToken.PHASE_CONSUMED)) {
                return;
            }
            long ownerAttempt = session.unifiedNativeRetargetAttempts
                    .incrementAndGet();
            token.ownerAttempt = ownerAttempt;
            log(Log.INFO, TAG,
                    "Queued Xiaomi standard commit owner verification"
                            + ", generation=" + session.generation
                            + ", signalAttempt="
                            + token.signal.attempt
                            + ", ownerAttempt=" + ownerAttempt
                            + ", requestedType=" + typeName
                            + ", animationIdentity="
                            + shortObject(currentIdentity));
            try {
                executeOnNativeGestureAnimationOwner(() -> {
                    UnifiedNativeRetargetInspection inspection =
                            inspectUnifiedNativeRetarget(
                                    session, ownerAttempt,
                                    typeName, false, null);
                    publishUnifiedProvisionalCommit(
                            session, token, null, inspection);
                    handler.post(() -> acceptUnifiedStandardCommit(
                            session, token, inspection));
                });
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not queue standard commit owner verification"
                                + ", generation="
                                + session.generation
                                + ", signalAttempt="
                                + token.signal.attempt
                                + ", ownerAttempt="
                                + ownerAttempt
                                + ", retainedConsumedToken=true",
                        throwable);
            }
        }

        protected void acceptUnifiedStandardCommit(
                ReturnHomeSession session,
                UnifiedNativeStandardCommitToken token,
                UnifiedNativeRetargetInspection inspection) {
            if (session == null || token == null || inspection == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativeCommitPending
                    || !isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                    session, token)
                    || token.ownerAttempt != inspection.attempt
                    ) {
                return;
            }
            Object currentElement = null;
            try {
                currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Could not verify standard Xiaomi commit element"
                                + ", generation="
                                + session.generation
                                + ", ownerAttempt="
                                + inspection.attempt,
                        throwable);
            }
            boolean standardType = "CLOSE_TO_HOME".equals(
                    inspection.actualType)
                    || "CLOSE_TO_HOME_CENTER".equals(
                    inspection.actualType);
            boolean exact = inspection.failure == null
                    && inspection.sameAnimation
                    && inspection.exactTarget
                    && inspection.requestedType.equals(
                    inspection.actualType)
                    && standardType
                    && currentElement == session.nativeWindowElement;
            if (!exact) {
                log(Log.ERROR, TAG,
                        "Rejected Xiaomi standard commit at animation-owner tail"
                                + ", generation="
                                + session.generation
                                + ", signalAttempt="
                                + token.signal.attempt
                                + ", ownerAttempt="
                                + inspection.attempt
                                + ", requestedType="
                                + inspection.requestedType
                                + ", actualType="
                                + inspection.actualType
                                + ", sameAnimation="
                                + inspection.sameAnimation
                                + ", exactTarget="
                                + inspection.exactTarget
                                + ", sameElement="
                                + (currentElement
                                == session.nativeWindowElement)
                                + ", running="
                                + inspection.running,
                        inspection.failure);
                return;
            }
            if (!adoptUnifiedStandardCommitToken(token)) {
                return;
            }
            Runnable previousTimeout = session.nativeTimeout;
            if (previousTimeout != null) {
                handler.removeCallbacks(previousTimeout);
            }
            session.nativeTimeout = null;
            session.unifiedNativeCommitPending = false;
            session.unifiedNativeStandardCommit = null;
            session.nativeAnimationIdentity =
                    inspection.animationIdentity;
            session.nativeAnimationType = inspection.actualType;
            session.nativeAnimationStarted = true;
            session.nativeContinuationVerified = true;
            handler.post(() -> completeUnifiedNativeCommitHandoff(
                    session, inspection.animationIdentity,
                    inspection.actualType));
            scheduleUnifiedNativeEndTimeout(session);
            log(Log.INFO, TAG,
                    "Accepted the same Xiaomi predictive spring for standard return-home"
                            + ", generation=" + session.generation
                            + ", signalAttempt="
                            + token.signal.attempt
                            + ", ownerAttempt="
                            + inspection.attempt
                            + ", from=CLOSE_TO_DRAG"
                            + ", to=" + inspection.actualType
                            + ", running=" + inspection.running
                            + ", finishComplete="
                            + inspection.finishComplete
                            + ", animationIdentity="
                            + shortObject(
                            inspection.animationIdentity)
                            + ", leash="
                            + shortObject(session.closingLeash));
        }

        protected boolean isExactUnifiedStandardCommitToken(
                ReturnHomeSession session,
                UnifiedNativeStandardCommitToken token,
                int requiredPhase) {
            StandardReturnHomeCommitSignal signal = token == null
                    ? null : token.signal;
            return session != null && token != null
                    && token == session.unifiedNativeStandardCommit
                    && token.session == session
                    && token.generation == session.generation
                    && token.windowElement
                    == session.nativeWindowElement
                    && token.animationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && token.phase.get() == requiredPhase
                    && token.animParams.get() != null
                    && token.animToEpoch > 0L
                    && token.animToEpoch
                    == session.unifiedNativeActiveAnimToEpoch
                    && session.unifiedNativeCommitTransition == null
                    && signal != null
                    && signal.attempt > 0L
                    && signal.taskId >= 0
                    && signal.transitionDebugId >= 0
                    && signal.taskId == session.unifiedNativeTaskId
                    && signal.arbiterGeneration
                    == miuiHomeSystemUiInputArbiterGeneration
                    && signal.launcherSessionGeneration
                    == session.generation
                    && signal.matchesInput(
                    session.acceptedInputIdentity)
                    && isStandardSingleTaskReturnHome(session);
        }

        protected boolean isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                ReturnHomeSession session,
                UnifiedNativeStandardCommitToken token) {
            int phase = token == null
                    ? UnifiedNativeStandardCommitToken.PHASE_INVALID
                    : token.phase.get();
            return (phase == UnifiedNativeStandardCommitToken.PHASE_ENTERING
                    || phase
                    == UnifiedNativeStandardCommitToken.PHASE_ENTERED
                    || phase
                    == UnifiedNativeStandardCommitToken.PHASE_CONSUMED)
                    && isExactUnifiedStandardCommitToken(
                    session, token, phase);
        }

        protected boolean adoptUnifiedStandardCommitToken(
                UnifiedNativeStandardCommitToken token) {
            return adoptUnifiedTokenPhase(token == null ? null : token.phase);
        }

        protected boolean isUnifiedCommitTransitionAtAnimToBoundary(
                ReturnHomeSession session,
                UnifiedNativeCommitTransitionToken transition) {
            if (session == null || transition == null
                    || transition.session != session
                    || transition.generation != session.generation
                    || transition != session.unifiedNativeCommitTransition
                    || transition.windowElement
                    != session.nativeWindowElement
                    || transition.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || transition.animParams.get() == null
                    || transition.animToEpoch <= 0L
                    || transition.animToEpoch
                    != session.unifiedNativeActiveAnimToEpoch
                    || transition.transitionToken == null
                    || transition.transitionInfo == null
                    || readTransitionDebugId(
                    transition.transitionInfo)
                    != transition.transitionDebugId) {
                return false;
            }
            int phase = transition.phase.get();
            return phase
                    == UnifiedNativeCommitTransitionToken.PHASE_ENTERING
                    || phase
                    == UnifiedNativeCommitTransitionToken.PHASE_ENTERED
                    || phase
                    == UnifiedNativeCommitTransitionToken.PHASE_CONSUMED;
        }

        protected boolean adoptUnifiedCommitTransitionToken(
                UnifiedNativeCommitTransitionToken transition) {
            return adoptUnifiedTokenPhase(
                    transition == null ? null : transition.phase);
        }

        protected boolean adoptUnifiedTokenPhase(AtomicInteger tokenPhase) {
            if (tokenPhase == null) {
                return false;
            }
            while (true) {
                int phase = tokenPhase.get();
                if (phase
                        != UnifiedNativeCommitTransitionToken.PHASE_ENTERING
                        && phase
                        != UnifiedNativeCommitTransitionToken.PHASE_ENTERED
                        && phase
                        != UnifiedNativeCommitTransitionToken.PHASE_CONSUMED) {
                    return false;
                }
                if (tokenPhase.compareAndSet(
                        phase,
                        UnifiedNativeCommitTransitionToken.PHASE_ADOPTED)) {
                    return true;
                }
            }
        }

        protected boolean hasProvisionalUnifiedCommitBoundary(
                ReturnHomeSession session) {
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativeCommitPending) {
                return false;
            }
            UnifiedNativeStandardCommitToken standard =
                    session.unifiedNativeStandardCommit;
            if (standard != null
                    && standard == session.unifiedNativeStandardCommit
                    && standard.session == session
                    && standard.generation == session.generation
                    && standard.windowElement
                    == session.nativeWindowElement
                    && standard.animationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && standard.signal != null
                    && standard.signal.taskId
                    == session.unifiedNativeTaskId
                    && standard.signal.arbiterGeneration
                    == miuiHomeSystemUiInputArbiterGeneration
                    && standard.signal.launcherSessionGeneration
                    == session.generation
                    && standard.signal.matchesInput(
                    session.acceptedInputIdentity)) {
                int phase = standard.phase.get();
                if (phase
                        == UnifiedNativeStandardCommitToken.PHASE_PENDING
                        || phase
                        == UnifiedNativeStandardCommitToken.PHASE_ENTERING
                        || phase
                        == UnifiedNativeStandardCommitToken.PHASE_ENTERED
                        || phase
                        == UnifiedNativeStandardCommitToken.PHASE_CONSUMED
                        || phase
                        == UnifiedNativeStandardCommitToken.PHASE_ADOPTED) {
                    return true;
                }
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            if (transition == null
                    || transition != session.unifiedNativeCommitTransition
                    || transition.session != session
                    || transition.generation != session.generation
                    || transition.windowElement
                    != session.nativeWindowElement
                    || transition.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || transition.transitionToken == null
                    || transition.transitionInfo == null) {
                return false;
            }
            int phase = transition.phase.get();
            return phase
                    == UnifiedNativeCommitTransitionToken.PHASE_PENDING
                    || phase
                    == UnifiedNativeCommitTransitionToken.PHASE_ENTERING
                    || phase
                    == UnifiedNativeCommitTransitionToken.PHASE_ENTERED
                    || phase
                    == UnifiedNativeCommitTransitionToken.PHASE_CONSUMED
                    || phase
                    == UnifiedNativeCommitTransitionToken.PHASE_ADOPTED;
        }

        protected void adoptUnifiedNativeCommitIfStarted(
                Object windowElement, Object params) throws Throwable {
            ReturnHomeSession session = currentSession;
            if (Looper.myLooper() != Looper.getMainLooper()
                    || session == null
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || !session.unifiedNativeCommitPending
                    || session.unifiedNativeCleanupVerified
                    || session.nativeWindowElement != windowElement
                    || params == null) {
                return;
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            if (transition == null
                    || transition.session != session
                    || transition.generation != session.generation
                    || transition != session.unifiedNativeCommitTransition
                    || transition.windowElement != windowElement
                    || transition.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || transition.animParams.get() != params) {
                return;
            }
            int phase = transition.phase.get();
            if (phase == UnifiedNativeCommitTransitionToken.PHASE_ENTERING) {
                if (!transition.phase.compareAndSet(
                        UnifiedNativeCommitTransitionToken.PHASE_ENTERING,
                        UnifiedNativeCommitTransitionToken.PHASE_ENTERED)) {
                    return;
                }
            } else if (phase
                    != UnifiedNativeCommitTransitionToken.PHASE_ENTERED) {
                return;
            }
            if (!isExactUnifiedCommitTransition(
                    session, transition, windowElement,
                    UnifiedNativeCommitTransitionToken.PHASE_ENTERED)) {
                return;
            }
            Object typeObject = invokeAnyMethod(
                    params, "getAnimType", new Object[0]);
            String typeName = enumName(typeObject);
            if (!isReturnHomeNativeCloseType(typeName)) {
                return;
            }
            Object currentElement = invokeAnyMethod(
                    session.stateManager, "getCurrentWindowElement",
                    new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            Object targetSet = invokeAnyMethod(
                    params, "getTargetApps", new Object[0]);
            Object closingTarget = resolveUnifiedNativeClosingTarget(
                    session, targetSet);
            if (currentElement != windowElement
                    || currentIdentity
                    != session.unifiedNativeAnimationIdentity
                    || closingTarget == null) {
                log(Log.WARN, TAG,
                        "Rejected Xiaomi unified commit candidate"
                                + ", generation=" + session.generation
                                + ", requestedType=" + typeName
                                + ", sameIdentity="
                                + (currentIdentity
                                == session.unifiedNativeAnimationIdentity)
                                + ", sameLeash="
                                + (closingTarget != null));
                return;
            }
            if ("CLOSE_TO_ELEMENT".equals(typeName)
                    && !hasCommittedUnifiedElementGeometry(
                    session, transition, windowElement,
                    currentIdentity)) {
                log(Log.INFO, TAG,
                        "Deferred Xiaomi unified element commit until start geometry is committed"
                                + ", generation="
                                + session.generation
                                + ", debugId="
                                + transition.transitionDebugId);
                return;
            }
            if (!transition.phase.compareAndSet(
                    UnifiedNativeCommitTransitionToken.PHASE_ENTERED,
                    UnifiedNativeCommitTransitionToken.PHASE_CONSUMED)) {
                return;
            }
            long attempt = session.unifiedNativeRetargetAttempts
                    .incrementAndGet();
            session.unifiedNativeCommitAttempt = attempt;
            session.unifiedNativeCommitRequestedType = typeName;
            log(Log.INFO, TAG,
                    "Queued Xiaomi unified commit verification"
                            + ", generation=" + session.generation
                            + ", attempt=" + attempt
                            + ", requestedType=" + typeName
                            + ", animationIdentity="
                            + shortObject(currentIdentity));
            try {
                executeOnNativeGestureAnimationOwner(() -> {
                    UnifiedNativeRetargetInspection inspection =
                            inspectUnifiedNativeRetarget(
                                    session, attempt, typeName, false,
                                    transition);
                    publishUnifiedProvisionalCommit(
                            session, null, transition, inspection);
                    handler.post(() -> acceptUnifiedNativeCommit(
                            session, inspection));
                });
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not queue Xiaomi commit owner-tail verification"
                                + ", generation=" + session.generation
                                + ", attempt=" + attempt,
                        throwable);
            }
        }

        protected UnifiedNativeRetargetInspection inspectUnifiedNativeRetarget(
                ReturnHomeSession session, long attempt,
                String requestedType, boolean cancel) {
            return inspectUnifiedNativeRetarget(
                    session, attempt, requestedType, cancel, null);
        }

        protected void publishUnifiedProvisionalCommit(
                ReturnHomeSession session,
                UnifiedNativeStandardCommitToken standardToken,
                UnifiedNativeCommitTransitionToken transitionToken,
                UnifiedNativeRetargetInspection inspection) {
            UnifiedNativeConfiguredAnimToSnapshot configured =
                    session == null ? null
                            : session.unifiedNativeConfiguredAnimTo.get();
            boolean standard = standardToken != null
                    && transitionToken == null;
            boolean exact = session != null && inspection != null
                    && currentSession == session
                    && session.finished.get() == 0
                    && session.unifiedNativeCommitPending
                    && !session.nativeAnimationStarted
                    && !session.unifiedNativeCleanupVerified
                    && inspection.failure == null
                    && inspection.sameAnimation
                    && inspection.exactTarget
                    && inspection.animationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && inspection.requestedType.equals(
                    inspection.actualType)
                    && isReturnHomeNativeCloseType(
                    inspection.actualType)
                    && configured != null
                    && configured
                    == session.unifiedNativeConfiguredAnimTo.get()
                    && !configured.cancel
                    && configured.animationType.equals(
                    inspection.actualType)
                    && configured.animToEpoch
                    == session.unifiedNativeActiveAnimToEpoch
                    && ((standard
                    && configured.ownerToken == standardToken
                    && standardToken.ownerAttempt
                    == inspection.attempt
                    && isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                    session, standardToken))
                    || (!standard
                    && configured.ownerToken == transitionToken
                    && inspection.commitTransition == transitionToken
                    && session.unifiedNativeCommitAttempt
                    == inspection.attempt
                    && isUnifiedCommitTransitionAtAnimToBoundary(
                    session, transitionToken)));
            if (!exact) {
                return;
            }
            UnifiedNativeProvisionalCommitSnapshot snapshot =
                    new UnifiedNativeProvisionalCommitSnapshot(
                            session, configured, standardToken,
                            transitionToken, inspection);
            UnifiedNativeProvisionalCommitSnapshot previous =
                    session.unifiedNativeProvisionalCommit
                            .getAndSet(snapshot);
            if (previous != null && previous != snapshot) {
                previous.phase.compareAndSet(
                        UnifiedNativeProvisionalCommitSnapshot.PHASE_PENDING,
                        UnifiedNativeProvisionalCommitSnapshot.PHASE_INVALID);
            }
            log(Log.INFO, TAG,
                    "Published provisional Xiaomi final-owner acceptance"
                            + ", generation=" + session.generation
                            + ", ownerAttempt="
                            + inspection.attempt
                            + ", animToEpoch="
                            + configured.animToEpoch
                            + ", type="
                            + inspection.actualType
                            + ", standard=" + standard);
        }

        protected UnifiedNativePendingInterruptionSnapshot
        armUnifiedPendingCommitInterruption(
                ReturnHomeSession session, Object expectedWindowElement,
                String reason) throws Throwable {
            if (Looper.myLooper() != Looper.getMainLooper()
                    || session == null || currentSession != session
                    || session.finished.get() != 0
                    || session.cleaned.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || !session.nativeHandoffStarted
                    || !session.unifiedNativeCommitPending
                    || session.nativeAnimationStarted
                    || session.unifiedNativeCleanupVerified
                    || session.nativeWindowElement
                    != expectedWindowElement
                    || session.unifiedNativeConfiguredAnimTo.get()
                    != null) {
                return null;
            }
            Object ownerToken;
            Object animParams;
            long animToEpoch;
            long ownerAttempt;
            UnifiedNativeCommitTransitionToken transition = null;
            UnifiedNativeStandardCommitToken standard =
                    session.unifiedNativeStandardCommit;
            if (standard != null
                    && standard.ownerAttempt > 0L
                    && isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                    session, standard)) {
                ownerToken = standard;
                animParams = standard.animParams.get();
                animToEpoch = standard.animToEpoch;
                ownerAttempt = standard.ownerAttempt;
            } else {
                transition = session.unifiedNativeCommitTransition;
                if (transition == null
                        || session.unifiedNativeCommitAttempt <= 0L
                        || !isUnifiedCommitTransitionAtAnimToBoundary(
                        session, transition)) {
                    return null;
                }
                int phase = transition.phase.get();
                if (!isExactUnifiedCommitTransition(
                        session, transition,
                        expectedWindowElement, phase)) {
                    return null;
                }
                ownerToken = transition;
                animParams = transition.animParams.get();
                animToEpoch = transition.animToEpoch;
                ownerAttempt =
                        session.unifiedNativeCommitAttempt;
            }
            if (animParams == null || animToEpoch <= 0L
                    || animToEpoch
                    != session.unifiedNativeActiveAnimToEpoch) {
                return null;
            }
            Object requestedTypeObject = invokeAnyMethod(
                    animParams, "getAnimType", new Object[0]);
            String requestedType = enumName(requestedTypeObject);
            if (!isReturnHomeNativeCloseType(requestedType)
                    || ("CLOSE_TO_ELEMENT".equals(requestedType)
                    && !hasCommittedUnifiedElementGeometry(
                    session, transition, expectedWindowElement,
                    session.unifiedNativeAnimationIdentity))) {
                return null;
            }
            Object currentElement = invokeAnyMethod(
                    session.stateManager,
                    "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    expectedWindowElement,
                    "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(expectedWindowElement);
            Object targetSet = invokeAnyMethod(
                    expectedWindowElement,
                    "getRemoteTargetSet", new Object[0]);
            if (currentElement != expectedWindowElement
                    || currentIdentity
                    != session.unifiedNativeAnimationIdentity
                    || (!"CLOSE_TO_DRAG".equals(currentType)
                    && !requestedType.equals(currentType))
                    || resolveUnifiedNativeClosingTarget(
                    session, targetSet) == null) {
                return null;
            }
            UnifiedNativePendingInterruptionSnapshot snapshot =
                    new UnifiedNativePendingInterruptionSnapshot(
                            session, animParams, ownerToken,
                            animToEpoch, ownerAttempt,
                            requestedType);
            synchronized (snapshot.configLock) {
                boolean ownerStillAtBoundary = ownerToken
                        instanceof UnifiedNativeStandardCommitToken
                        ? isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                        session,
                        (UnifiedNativeStandardCommitToken) ownerToken)
                        : session.unifiedNativeCommitAttempt == ownerAttempt
                        && isUnifiedCommitTransitionAtAnimToBoundary(
                        session,
                        (UnifiedNativeCommitTransitionToken) ownerToken);
                if (currentSession != session
                        || session.finished.get() != 0
                        || session.cleaned.get() != 0
                        || session.unifiedNativeCleanupVerified
                        || session.nativeAnimationStarted
                        || session.unifiedNativeConfiguredAnimTo.get()
                        != null
                        || session.unifiedNativeActiveAnimToEpoch
                        != animToEpoch
                        || unifiedConfigHookState(ownerToken) == null
                        || unifiedConfigHookState(ownerToken).get()
                        != UNIFIED_CONFIG_HOOK_PENDING
                        || !ownerStillAtBoundary) {
                    return null;
                }
                while (true) {
                    UnifiedNativePendingInterruptionSnapshot existing =
                            session.unifiedNativePendingInterruption.get();
                    if (existing != null
                            && existing.phase.get()
                            == UnifiedNativePendingInterruptionSnapshot
                            .PHASE_PENDING
                            && existing.session == session
                            && existing.windowElement
                            == expectedWindowElement
                            && existing.animationIdentity
                            == currentIdentity
                            && existing.animParams == animParams
                            && existing.ownerToken == ownerToken
                            && existing.animToEpoch == animToEpoch
                            && existing.ownerAttempt == ownerAttempt
                            && existing.requestedType.equals(
                            requestedType)) {
                        if (existing.configDisposition.get()
                                != UnifiedNativePendingInterruptionSnapshot
                                .CONFIG_PENDING) {
                            // The config hook already acknowledged this exact queued lambda.
                            // Keep the live native-callback token, but never resurrect its
                            // params tombstone after CONFIG_ACK_SKIPPED removed it.
                            return existing;
                        }
                        UnifiedNativePendingInterruptionSnapshot mapped =
                                pendingUnifiedInterruptedAnimToConfigs
                                        .putIfAbsent(
                                                new ObjectIdentityKey(
                                                        animParams),
                                                existing);
                        return mapped == null || mapped == existing
                                ? existing : null;
                    }
                    if (existing != null
                            && existing.phase.get()
                            != UnifiedNativePendingInterruptionSnapshot
                            .PHASE_INVALID) {
                        return null;
                    }
                    if (session.unifiedNativePendingInterruption
                            .compareAndSet(existing, snapshot)) {
                        UnifiedNativePendingInterruptionSnapshot mapped =
                                pendingUnifiedInterruptedAnimToConfigs
                                        .putIfAbsent(
                                                new ObjectIdentityKey(
                                                        animParams),
                                                snapshot);
                        if (mapped != null && mapped != snapshot) {
                            session.unifiedNativePendingInterruption
                                    .compareAndSet(snapshot, null);
                            snapshot.phase.set(
                                    UnifiedNativePendingInterruptionSnapshot
                                            .PHASE_INVALID);
                            log(Log.ERROR, TAG,
                                    "Rejected colliding Xiaomi animTo interruption tombstone"
                                            + ", generation="
                                            + session.generation
                                            + ", animToEpoch="
                                            + animToEpoch
                                            + ", existingGeneration="
                                            + mapped.generation
                                            + ", existingEpoch="
                                            + mapped.animToEpoch);
                            return null;
                        }
                        if (existing != null) {
                            existing.phase.set(
                                    UnifiedNativePendingInterruptionSnapshot
                                            .PHASE_INVALID);
                            if (existing.mutation.get()
                                    == UnifiedNativePendingInterruptionSnapshot
                                    .MUTATION_NONE) {
                                existing.configDisposition.compareAndSet(
                                        UnifiedNativePendingInterruptionSnapshot
                                                .CONFIG_PENDING,
                                        UnifiedNativePendingInterruptionSnapshot
                                                .CONFIG_INVALID);
                                pendingUnifiedInterruptedAnimToConfigs.remove(
                                        new ObjectIdentityKey(
                                                existing.animParams),
                                        existing);
                            }
                        }
                        break;
                    }
                }
            }
            log(Log.INFO, TAG,
                    "Armed exact pre-config Xiaomi interruption boundary"
                            + ", generation=" + session.generation
                            + ", ownerAttempt=" + ownerAttempt
                            + ", animToEpoch=" + animToEpoch
                            + ", requestedType=" + requestedType
                            + ", currentType=" + currentType
                            + ", reason=" + reason);
            return snapshot;
        }

        protected boolean isExactUnifiedPendingInterruption(
                ReturnHomeSession session,
                UnifiedNativePendingInterruptionSnapshot snapshot,
                Object currentElement, Object currentIdentity,
                String currentType, boolean requireTokenBoundary) {
            if (session == null || snapshot == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || session.cleaned.get() != 0
                    || session.unifiedNativeCleanupVerified
                    || snapshot
                    != session.unifiedNativePendingInterruption.get()
                    || snapshot.phase.get()
                    != UnifiedNativePendingInterruptionSnapshot.PHASE_PENDING
                    || snapshot.session != session
                    || snapshot.generation != session.generation
                    || snapshot.windowElement
                    != session.nativeWindowElement
                    || snapshot.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || snapshot.animToEpoch <= 0L
                    || snapshot.animToEpoch
                    != session.unifiedNativeActiveAnimToEpoch
                    || snapshot.animParams == null
                    || currentElement != snapshot.windowElement
                    || currentIdentity != snapshot.animationIdentity
                    || (!"CLOSE_TO_DRAG".equals(currentType)
                    && !snapshot.requestedType.equals(currentType))) {
                return false;
            }
            try {
                Object targetSet = invokeAnyMethod(
                        snapshot.windowElement,
                        "getRemoteTargetSet", new Object[0]);
                if (resolveUnifiedNativeClosingTarget(
                        session, targetSet) == null) {
                    return false;
                }
            } catch (Throwable throwable) {
                return false;
            }
            if (snapshot.ownerToken
                    instanceof UnifiedNativeStandardCommitToken) {
                UnifiedNativeStandardCommitToken token =
                        (UnifiedNativeStandardCommitToken)
                                snapshot.ownerToken;
                boolean identity = token.session == session
                        && token.generation == session.generation
                        && token.windowElement == snapshot.windowElement
                        && token.animationIdentity
                        == snapshot.animationIdentity
                        && token.animParams.get()
                        == snapshot.animParams
                        && token.animToEpoch
                        == snapshot.animToEpoch
                        && token.ownerAttempt
                        == snapshot.ownerAttempt;
                return identity && (!requireTokenBoundary
                        || isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                        session, token));
            }
            if (snapshot.ownerToken
                    instanceof UnifiedNativeCommitTransitionToken) {
                UnifiedNativeCommitTransitionToken token =
                        (UnifiedNativeCommitTransitionToken)
                                snapshot.ownerToken;
                boolean identity = token.session == session
                        && token.generation == session.generation
                        && token.windowElement == snapshot.windowElement
                        && token.animationIdentity
                        == snapshot.animationIdentity
                        && token.animParams.get()
                        == snapshot.animParams
                        && token.animToEpoch
                        == snapshot.animToEpoch
                        && snapshot.ownerAttempt > 0L;
                return identity && (!requireTokenBoundary
                        || (session.unifiedNativeCommitAttempt
                        == snapshot.ownerAttempt
                        && isUnifiedCommitTransitionAtAnimToBoundary(
                        session, token)));
            }
            return false;
        }

        protected void invalidateUnifiedPendingInterruption(
                ReturnHomeSession session, String reason) {
            if (session == null) {
                return;
            }
            UnifiedNativePendingInterruptionSnapshot snapshot =
                    session.unifiedNativePendingInterruption.get();
            if (snapshot == null) {
                return;
            }
            synchronized (snapshot.configLock) {
                if (snapshot
                        != session.unifiedNativePendingInterruption.get()) {
                    return;
                }
                if (snapshot.phase.get()
                        == UnifiedNativePendingInterruptionSnapshot
                        .PHASE_PENDING
                        && snapshot.mutation.get()
                        != UnifiedNativePendingInterruptionSnapshot
                        .MUTATION_NONE) {
                    // A native cancel already owns this exact WindowElement. Retain both the
                    // callback token and the params-identity tombstone until the native callback
                    // consumes the former and the queued gesture-executor config acks the latter.
                    log(Log.INFO, TAG,
                            "Retained terminal Xiaomi animTo interruption tombstone"
                                    + ", generation="
                                    + session.generation
                                    + ", animToEpoch="
                                    + snapshot.animToEpoch
                                    + ", mutation="
                                    + snapshot.mutation.get()
                                    + ", configDisposition="
                                    + snapshot.configDisposition.get()
                                    + ", reason=" + reason);
                    return;
                }
                if (!session.unifiedNativePendingInterruption
                        .compareAndSet(snapshot, null)) {
                    return;
                }
                snapshot.phase.compareAndSet(
                        UnifiedNativePendingInterruptionSnapshot.PHASE_PENDING,
                        UnifiedNativePendingInterruptionSnapshot.PHASE_INVALID);
                snapshot.configDisposition.compareAndSet(
                        UnifiedNativePendingInterruptionSnapshot.CONFIG_PENDING,
                        UnifiedNativePendingInterruptionSnapshot.CONFIG_INVALID);
                pendingUnifiedInterruptedAnimToConfigs.remove(
                        new ObjectIdentityKey(snapshot.animParams), snapshot);
                log(Log.INFO, TAG,
                        "Invalidated pre-config Xiaomi interruption boundary"
                                + ", generation="
                                + session.generation
                                + ", animToEpoch="
                                + snapshot.animToEpoch
                                + ", configDisposition="
                                + snapshot.configDisposition.get()
                                + ", reason=" + reason);
            }
        }

        protected boolean consumeUnifiedPendingInterruption(
                ReturnHomeSession session,
                UnifiedNativePendingInterruptionSnapshot snapshot,
                String reason) {
            if (session == null || snapshot == null
                    || snapshot
                    != session.unifiedNativePendingInterruption.get()
                    || !snapshot.phase.compareAndSet(
                    UnifiedNativePendingInterruptionSnapshot.PHASE_PENDING,
                    UnifiedNativePendingInterruptionSnapshot.PHASE_CONSUMED)) {
                return false;
            }
            session.unifiedNativePendingInterruption.compareAndSet(
                    snapshot, null);
            Runnable nativeTimeout = session.nativeTimeout;
            if (nativeTimeout != null) {
                handler.removeCallbacks(nativeTimeout);
            }
            session.nativeTimeout = null;
            UnifiedNativeStandardCommitToken standard =
                    session.unifiedNativeStandardCommit;
            if (standard != null) {
                standard.phase.set(
                        UnifiedNativeStandardCommitToken.PHASE_INVALID);
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            if (transition != null) {
                transition.phase.set(
                        UnifiedNativeCommitTransitionToken.PHASE_INVALID);
            }
            UnifiedNativeProvisionalCommitSnapshot provisional =
                    session.unifiedNativeProvisionalCommit.getAndSet(null);
            if (provisional != null) {
                provisional.phase.set(
                        UnifiedNativeProvisionalCommitSnapshot.PHASE_INVALID);
            }
            UnifiedNativeTerminalFailureSnapshot terminal =
                    session.unifiedNativeTerminalFailure.get();
            if (terminal != null
                    && terminal.animToEpoch == snapshot.animToEpoch
                    && terminal.phase.compareAndSet(
                    UnifiedNativeTerminalFailureSnapshot.PHASE_PENDING,
                    UnifiedNativeTerminalFailureSnapshot.PHASE_INVALID)) {
                session.unifiedNativeTerminalFailure.compareAndSet(
                        terminal, null);
            }
            session.unifiedNativeConfiguredAnimTo.set(null);
            session.unifiedNativeStandardCommit = null;
            session.unifiedNativeCommitTransition = null;
            session.unifiedNativeCommitPending = false;
            session.unifiedNativeCleanupVerified = true;
            log(Log.INFO, TAG,
                    "Consumed exact pre-config Xiaomi interruption boundary"
                            + ", generation=" + session.generation
                            + ", ownerAttempt="
                            + snapshot.ownerAttempt
                            + ", animToEpoch="
                            + snapshot.animToEpoch
                            + ", requestedType="
                            + snapshot.requestedType
                            + ", configDisposition="
                            + snapshot.configDisposition.get()
                            + ", reason=" + reason);
            if (snapshot.configDisposition.get()
                    == UnifiedNativePendingInterruptionSnapshot
                    .CONFIG_PENDING) {
                scheduleUnifiedInterruptedConfigOwnerDrain(
                        snapshot, reason);
            }
            return true;
        }

        protected boolean adoptConfiguredCommitForInterruption(
                ReturnHomeSession session, Object expectedWindowElement,
                String reason) throws Throwable {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                log(Log.ERROR, TAG,
                        "Rejected provisional Xiaomi commit adoption off main"
                                + ", generation="
                                + (session == null ? 0L
                                : session.generation)
                                + ", reason=" + reason);
                return false;
            }
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || session.cleaned.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || !session.nativeHandoffStarted
                    || session.unifiedNativeCleanupVerified
                    || session.nativeWindowElement
                    != expectedWindowElement) {
                return false;
            }
            if (session.nativeAnimationStarted) {
                return session.nativeContinuationVerified
                        && session.nativeAnimationIdentity
                        == session.unifiedNativeAnimationIdentity
                        && isReturnHomeNativeCloseType(
                        session.nativeAnimationType);
            }
            if (!session.unifiedNativeCommitPending) {
                return false;
            }

            UnifiedNativeConfiguredAnimToSnapshot configured =
                    session.unifiedNativeConfiguredAnimTo.get();
            if (configured == null) {
                UnifiedNativePendingInterruptionSnapshot armed =
                        armUnifiedPendingCommitInterruption(
                        session, expectedWindowElement,
                        "awaitConfigured:" + reason);
                if (armed != null) {
                    return false;
                }
                // The config owner may have won the same configLock immediately before arm's
                // locked recheck. Adopt that freshly published exact owner in this invocation;
                // otherwise the caller would fall through into Xiaomi's cancel path and mutate
                // the WindowElement before a post hook could recover it.
                configured = session.unifiedNativeConfiguredAnimTo.get();
                if (configured == null) {
                    return false;
                }
            }
            if (configured
                    != session.unifiedNativeConfiguredAnimTo.get()
                    || configured.cancel
                    || configured.session != session
                    || configured.generation != session.generation
                    || configured.windowElement
                    != expectedWindowElement
                    || configured.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || configured.animToEpoch <= 0L
                    || configured.animToEpoch
                    != session.unifiedNativeActiveAnimToEpoch
                    || !isReturnHomeNativeCloseType(
                    configured.animationType)) {
                return false;
            }

            Object currentElement = invokeAnyMethod(
                    session.stateManager,
                    "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    expectedWindowElement,
                    "getAnimSymbol", new Object[0]);
            Object actualTypeObject = invokeAnyMethod(
                    currentIdentity, "getLastAminType", new Object[0]);
            String actualType = enumName(actualTypeObject);
            Object requestedTypeObject = invokeAnyMethod(
                    configured.animParams,
                    "getAnimType", new Object[0]);
            String requestedType = enumName(requestedTypeObject);
            Object targetSet = invokeAnyMethod(
                    expectedWindowElement,
                    "getRemoteTargetSet", new Object[0]);
            if (currentElement != expectedWindowElement
                    || currentIdentity
                    != session.unifiedNativeAnimationIdentity
                    || !configured.animationType.equals(actualType)
                    || !requestedType.equals(actualType)
                    || resolveUnifiedNativeClosingTarget(
                    session, targetSet) == null) {
                return false;
            }

            UnifiedNativeStandardCommitToken standard = null;
            UnifiedNativeCommitTransitionToken transition = null;
            long attempt;
            if (configured.ownerToken
                    instanceof UnifiedNativeStandardCommitToken) {
                standard = (UnifiedNativeStandardCommitToken)
                        configured.ownerToken;
                if (standard.animParams.get()
                        != configured.animParams
                        || standard.animToEpoch
                        != configured.animToEpoch
                        || standard.ownerAttempt <= 0L
                        || !isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                        session, standard)) {
                    return false;
                }
                attempt = standard.ownerAttempt;
            } else if (configured.ownerToken
                    instanceof UnifiedNativeCommitTransitionToken) {
                transition = (UnifiedNativeCommitTransitionToken)
                        configured.ownerToken;
                if (transition.animParams.get()
                        != configured.animParams
                        || transition.animToEpoch
                        != configured.animToEpoch
                        || session.unifiedNativeCommitAttempt <= 0L
                        || !isUnifiedCommitTransitionAtAnimToBoundary(
                        session, transition)
                        || ("CLOSE_TO_ELEMENT".equals(actualType)
                        && !hasCommittedUnifiedElementGeometry(
                        session, transition,
                        expectedWindowElement, currentIdentity))) {
                    return false;
                }
                attempt = session.unifiedNativeCommitAttempt;
            } else {
                return false;
            }

            UnifiedNativeProvisionalCommitSnapshot provisional =
                    session.unifiedNativeProvisionalCommit.get();
            UnifiedNativeRetargetInspection inspection = null;
            boolean claimedProvisional = false;
            if (provisional != null
                    && provisional
                    == session.unifiedNativeProvisionalCommit.get()
                    && provisional.session == session
                    && provisional.generation == session.generation
                    && provisional.windowElement
                    == expectedWindowElement
                    && provisional.animationIdentity == currentIdentity
                    && provisional.configured == configured
                    && provisional.standardToken == standard
                    && provisional.transitionToken == transition
                    && provisional.ownerAttempt == attempt
                    && provisional.animToEpoch
                    == configured.animToEpoch
                    && provisional.animationType.equals(actualType)
                    && provisional.phase.compareAndSet(
                    UnifiedNativeProvisionalCommitSnapshot.PHASE_PENDING,
                    UnifiedNativeProvisionalCommitSnapshot.PHASE_ADOPTING)) {
                inspection = provisional.inspection;
                claimedProvisional = true;
            }
            if (inspection == null) {
                inspection = new UnifiedNativeRetargetInspection(
                        attempt, requestedType, actualType,
                        currentIdentity, true, true,
                        configured.running, configured.finishComplete,
                        false, transition, null);
            }

            if (standard != null) {
                acceptUnifiedStandardCommit(
                        session, standard, inspection);
            } else {
                acceptUnifiedNativeCommit(session, inspection);
            }
            boolean adopted = currentSession == session
                    && session.finished.get() == 0
                    && session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && session.nativeAnimationIdentity == currentIdentity
                    && actualType.equals(session.nativeAnimationType);
            if (claimedProvisional) {
                provisional.phase.set(adopted
                        ? UnifiedNativeProvisionalCommitSnapshot.PHASE_ADOPTED
                        : UnifiedNativeProvisionalCommitSnapshot.PHASE_INVALID);
            }
            log(adopted ? Log.INFO : Log.WARN, TAG,
                    "Adopted configured Xiaomi commit before launcher interruption"
                            + ", generation=" + session.generation
                            + ", reason=" + reason
                            + ", adopted=" + adopted
                            + ", ownerAttempt=" + attempt
                            + ", animToEpoch="
                            + configured.animToEpoch
                            + ", type=" + actualType
                            + ", usedProvisional="
                            + claimedProvisional);
            return adopted;
        }

        protected UnifiedNativeRetargetInspection inspectUnifiedNativeRetarget(
                ReturnHomeSession session, long attempt,
                String requestedType, boolean cancel,
                UnifiedNativeCommitTransitionToken commitTransition) {
            try {
                Object animationIdentity = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getAnimSymbol", new Object[0]);
                Object typeObject = animationIdentity == null ? null
                        : invokeAnyMethod(animationIdentity,
                        "getLastAminType", new Object[0]);
                String actualType = enumName(typeObject);
                boolean running = animationIdentity != null
                        && Boolean.TRUE.equals(invokeAnyMethod(
                        animationIdentity, "isRunning", new Object[0]));
                Object targetSet = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getRemoteTargetSet", new Object[0]);
                boolean exactTarget = resolveUnifiedNativeClosingTarget(
                        session, targetSet) != null;
                boolean finishComplete = Boolean.TRUE.equals(readField(
                        session.nativeWindowElement, "mFinishComplete"));
                boolean fullscreen = false;
                if (cancel && animationIdentity != null) {
                    Object rectObject = invokeAnyMethod(
                            animationIdentity, "getCurrentRectF",
                            new Object[0]);
                    float tolerance = Math.max(2.0f, dp(2.0f));
                    fullscreen = rectObject instanceof RectF
                            && rectsNear((RectF) rectObject,
                            new RectF(session.startRect), tolerance);
                }
                return new UnifiedNativeRetargetInspection(
                        attempt, requestedType, actualType,
                        animationIdentity,
                        animationIdentity
                                == session.unifiedNativeAnimationIdentity,
                        exactTarget, running, finishComplete,
                        fullscreen, commitTransition, null);
            } catch (Throwable throwable) {
                return new UnifiedNativeRetargetInspection(
                        attempt, requestedType, "unknown", null,
                        false, false, false, false,
                        false, commitTransition, throwable);
            }
        }

        protected boolean rectsNear(RectF first, RectF second,
                                  float tolerance) {
            return first != null && second != null
                    && Math.abs(first.left - second.left) <= tolerance
                    && Math.abs(first.top - second.top) <= tolerance
                    && Math.abs(first.right - second.right) <= tolerance
                    && Math.abs(first.bottom - second.bottom) <= tolerance;
        }

        protected UnifiedNativeFinishSnapshot captureUnifiedNativeFinishSnapshot(
                ReturnHomeSession session, Object listener,
                Object animationIdentity) {
            Object callbackStateManager = null;
            Object currentElement = null;
            String currentElementType = null;
            boolean oldElementRecorded = false;
            Object currentIdentity = null;
            String actualType = "unknown";
            boolean exactTarget = false;
            boolean running = false;
            boolean finishComplete = false;
            boolean fullscreen = false;
            Throwable failure = null;
            try {
                callbackStateManager = readField(listener, "this$0");
                currentElement = invokeAnyMethod(
                        session.stateManager, "getCurrentWindowElement",
                        new Object[0]);
                if (currentElement != null) {
                    try {
                        currentElementType = readNativeAnimationType(
                                currentElement);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Object oldListObject = readField(
                            session.stateManager, "windowElementOldList");
                    oldElementRecorded = oldListObject instanceof List<?>
                            && ((List<?>) oldListObject).contains(
                            session.nativeWindowElement);
                } catch (Throwable ignored) {
                }
                currentIdentity = invokeAnyMethod(
                        session.nativeWindowElement, "getAnimSymbol",
                        new Object[0]);
                Object typeObject = animationIdentity == null ? null
                        : invokeAnyMethod(animationIdentity,
                        "getLastAminType", new Object[0]);
                if (typeObject == null) {
                    typeObject = invokeAnyMethod(
                            session.nativeWindowElement,
                            "getCurrentAnimType", new Object[0]);
                }
                actualType = enumName(typeObject);
                running = animationIdentity != null
                        && Boolean.TRUE.equals(invokeAnyMethod(
                        animationIdentity, "isRunning", new Object[0]));
                Object targetSet = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getRemoteTargetSet", new Object[0]);
                exactTarget = resolveUnifiedNativeClosingTarget(
                        session, targetSet) != null;
                finishComplete = Boolean.TRUE.equals(readField(
                        session.nativeWindowElement, "mFinishComplete"));
                if (animationIdentity != null) {
                    Object rectObject = invokeAnyMethod(
                            animationIdentity, "getCurrentRectF",
                            new Object[0]);
                    float tolerance = Math.max(2.0f, dp(2.0f));
                    fullscreen = rectObject instanceof RectF
                            && rectsNear((RectF) rectObject,
                            new RectF(session.startRect), tolerance);
                }
            } catch (Throwable throwable) {
                failure = throwable;
            }
            return new UnifiedNativeFinishSnapshot(
                    session, callbackStateManager, currentElement,
                    currentElementType, oldElementRecorded,
                    animationIdentity, currentIdentity, actualType,
                    exactTarget, running, finishComplete, fullscreen,
                    session.unifiedNativeActiveAnimToEpoch,
                    session.unifiedNativeCommitTransition, failure);
        }

        protected boolean hasExactUnifiedNativeFinishIdentity(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot) {
            return session != null && snapshot != null
                    && snapshot.failure == null
                    && snapshot.session == session
                    && snapshot.generation == session.generation
                    && snapshot.stateManager == session.stateManager
                    && snapshot.callbackStateManager == session.stateManager
                    && snapshot.windowElement
                    == session.nativeWindowElement
                    && snapshot.animationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && snapshot.currentAnimationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && snapshot.animToEpoch
                    == session.unifiedNativeActiveAnimToEpoch
                    && snapshot.exactTarget
                    && snapshot.finishComplete;
        }

        protected boolean isExactUnifiedNativeFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot) {
            return hasExactUnifiedNativeFinishIdentity(session, snapshot)
                    && snapshot.currentElement
                    == session.nativeWindowElement;
        }

        protected boolean isExactAdoptedNativeCloseFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot) {
            return hasExactUnifiedNativeFinishIdentity(session, snapshot)
                    && session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && session.nativeAnimationIdentity
                    == snapshot.animationIdentity
                    && session.nativeAnimationType != null
                    && session.nativeAnimationType.equals(snapshot.actualType)
                    && isReturnHomeNativeCloseType(snapshot.actualType);
        }

        protected boolean isConsumableUnifiedNativeFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot) {
            return session != null && session.nativeAnimationStarted
                    ? isExactAdoptedNativeCloseFinishSnapshot(
                    session, snapshot)
                    : isExactUnifiedNativeFinishSnapshot(
                    session, snapshot);
        }

        protected boolean acceptUnifiedNativeCommitFromFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot,
                String reason) {
            UnifiedNativeCommitTransitionToken transition = snapshot == null
                    ? null : snapshot.commitTransition;
            boolean exact = currentSession == session
                    && session.finished.get() == 0
                    && session.unifiedNativeCommitPending
                    && !session.nativeAnimationStarted
                    && isExactUnifiedNativeFinishSnapshot(session, snapshot)
                    && isReturnHomeNativeCloseType(snapshot.actualType)
                    && isUnifiedCommitTransitionAtAnimToBoundary(
                    session, transition)
                    && (!"CLOSE_TO_ELEMENT".equals(snapshot.actualType)
                    || hasCommittedUnifiedElementGeometry(
                    session, transition, session.nativeWindowElement,
                    snapshot.animationIdentity));
            if (!exact || !adoptUnifiedCommitTransitionToken(
                    transition)) {
                return false;
            }
            Runnable timeout = session.nativeTimeout;
            if (timeout != null) {
                handler.removeCallbacks(timeout);
            }
            Runnable unifiedCancelTimeout =
                    session.unifiedNativeCancelTimeout;
            if (unifiedCancelTimeout != null) {
                handler.removeCallbacks(unifiedCancelTimeout);
            }
            session.unifiedNativeCancelTimeout = null;
            session.nativeTimeout = null;
            session.unifiedNativeCommitPending = false;
            session.nativeAnimationIdentity = snapshot.animationIdentity;
            session.nativeAnimationType = snapshot.actualType;
            session.nativeAnimationStarted = true;
            session.nativeContinuationVerified = true;
            session.unifiedNativeCommitEndObserved = true;
            markUnifiedElementLeashAdopted(
                    session, snapshot.animationIdentity,
                    snapshot.actualType);
            completeUnifiedNativeCommitHandoff(
                    session, snapshot.animationIdentity,
                    snapshot.actualType);
            if (!snapshot.phase.compareAndSet(
                    UnifiedNativeFinishSnapshot.PHASE_PENDING,
                    UnifiedNativeFinishSnapshot.PHASE_CONSUMED)) {
                return session.unifiedNativeCleanupVerified;
            }
            session.unifiedNativeCleanupVerified = true;
            log(Log.INFO, TAG,
                    "Accepted completed Xiaomi commit at pre-clear finish boundary"
                            + ", generation=" + session.generation
                            + ", reason=" + reason
                            + ", type=" + snapshot.actualType
                            + ", transitionDebugId="
                            + transition.transitionDebugId
                            + ", animationIdentity="
                            + shortObject(snapshot.animationIdentity));
            finishUnifiedSessionAfterNativeListener(session, reason);
            return true;
        }

        protected boolean acceptUnifiedStandardCommitFromFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot,
                String reason) {
            UnifiedNativeStandardCommitToken token = session == null
                    ? null : session.unifiedNativeStandardCommit;
            StandardReturnHomeCommitSignal signal = token == null
                    ? null : token.signal;
            boolean standardType = snapshot != null
                    && ("CLOSE_TO_HOME".equals(snapshot.actualType)
                    || "CLOSE_TO_HOME_CENTER".equals(
                    snapshot.actualType));
            boolean exact = session != null
                    && currentSession == session
                    && session.finished.get() == 0
                    && session.unifiedNativeCommitPending
                    && !session.nativeAnimationStarted
                    && isExactUnifiedNativeFinishSnapshot(session, snapshot)
                    && standardType
                    && isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                    session, token);
            if (!exact || !adoptUnifiedStandardCommitToken(token)) {
                return false;
            }
            Runnable timeout = session.nativeTimeout;
            if (timeout != null) {
                handler.removeCallbacks(timeout);
            }
            session.nativeTimeout = null;
            session.unifiedNativeCommitPending = false;
            session.unifiedNativeStandardCommit = null;
            session.nativeAnimationIdentity = snapshot.animationIdentity;
            session.nativeAnimationType = snapshot.actualType;
            session.nativeAnimationStarted = true;
            session.nativeContinuationVerified = true;
            session.unifiedNativeCommitEndObserved = true;
            completeUnifiedNativeCommitHandoff(
                    session, snapshot.animationIdentity,
                    snapshot.actualType);
            if (!snapshot.phase.compareAndSet(
                    UnifiedNativeFinishSnapshot.PHASE_PENDING,
                    UnifiedNativeFinishSnapshot.PHASE_CONSUMED)) {
                return session.unifiedNativeCleanupVerified;
            }
            session.unifiedNativeCleanupVerified = true;
            log(Log.INFO, TAG,
                    "Accepted completed Xiaomi standard commit at pre-clear finish boundary"
                            + ", generation=" + session.generation
                            + ", reason=" + reason
                            + ", type=" + snapshot.actualType
                            + ", signalAttempt=" + signal.attempt
                            + ", taskId=" + signal.taskId
                            + ", transitionDebugId="
                            + signal.transitionDebugId
                            + ", animationIdentity="
                            + shortObject(snapshot.animationIdentity));
            finishUnifiedSessionAfterNativeListener(session, reason);
            return true;
        }

        protected void finishUnifiedSessionAfterNativeListener(
                ReturnHomeSession session, String reason) {
            handler.post(() -> {
                if (currentSession == session
                        && session.finished.get() == 0
                        && session.unifiedNativeCleanupVerified) {
                    finishSession(session, reason, false);
                }
            });
        }

        protected boolean consumeUnifiedNativeFinishSnapshot(
                ReturnHomeSession session, String reason) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                log(Log.ERROR, TAG,
                        "Rejected Xiaomi finish-snapshot consumption off main"
                                + ", generation="
                                + (session == null ? 0L
                                : session.generation)
                                + ", reason=" + reason);
                return false;
            }
            UnifiedNativeFinishSnapshot snapshot = session == null ? null
                    : session.unifiedNativeFinishSnapshot.get();
            if (session == null || snapshot == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || session.unifiedNativeCleanupVerified
                    || snapshot.phase.get()
                    != UnifiedNativeFinishSnapshot.PHASE_PENDING) {
                return false;
            }
            if (!isConsumableUnifiedNativeFinishSnapshot(
                    session, snapshot)) {
                if (snapshot.phase.compareAndSet(
                        UnifiedNativeFinishSnapshot.PHASE_PENDING,
                        UnifiedNativeFinishSnapshot.PHASE_INVALID)) {
                    log(Log.ERROR, TAG,
                            "Rejected Xiaomi pre-clear finish snapshot; retained owner"
                                    + ", generation="
                                    + session.generation
                                    + ", reason=" + reason
                                    + ", type=" + snapshot.actualType
                                    + ", sameStateManager="
                                    + (snapshot.callbackStateManager
                                    == session.stateManager)
                                    + ", sameElement="
                                    + (snapshot.currentElement
                                    == session.nativeWindowElement)
                                    + ", currentElementType="
                                    + snapshot.currentElementType
                                    + ", oldElementRecorded="
                                    + snapshot.oldElementRecorded
                                    + ", sameIdentity="
                                    + (snapshot.currentAnimationIdentity
                                    == session.unifiedNativeAnimationIdentity)
                                    + ", exactTarget="
                                    + snapshot.exactTarget
                                    + ", running=" + snapshot.running
                                    + ", finishComplete="
                                    + snapshot.finishComplete,
                            snapshot.failure);
                }
                return false;
            }
            if (session.unifiedNativeCancelPending) {
                if (!session.unifiedNativeCancelEndObserved
                        || snapshot.animToEpoch
                        != session.unifiedNativeCancelAnimToEpoch
                        || !"APP_TO_APP".equals(snapshot.actualType)
                        || !snapshot.fullscreen) {
                    return false;
                }
                session.unifiedNativeCancelRetargeted = true;
                if (!snapshot.phase.compareAndSet(
                        UnifiedNativeFinishSnapshot.PHASE_PENDING,
                        UnifiedNativeFinishSnapshot.PHASE_CONSUMED)) {
                    return false;
                }
                session.unifiedNativeCancelPending = false;
                session.unifiedNativeCancelRetargeted = false;
                session.unifiedNativeCleanupVerified = true;
                log(Log.INFO, TAG,
                        "Accepted completed Xiaomi cancel at pre-clear finish boundary"
                                + ", generation=" + session.generation
                                + ", reason=" + reason
                                + ", animationIdentity="
                                + shortObject(snapshot.animationIdentity));
                finishUnifiedSessionAfterNativeListener(session, reason);
                return true;
            }
            if (!session.unifiedNativeCommitEndObserved) {
                return false;
            }
            if (session.nativeAnimationStarted) {
                boolean exactClose = session.nativeAnimationIdentity
                        == snapshot.animationIdentity
                        && snapshot.actualType.equals(
                        session.nativeAnimationType)
                        && isReturnHomeNativeCloseType(
                        snapshot.actualType);
                if (!exactClose || !snapshot.phase.compareAndSet(
                        UnifiedNativeFinishSnapshot.PHASE_PENDING,
                        UnifiedNativeFinishSnapshot.PHASE_CONSUMED)) {
                    return false;
                }
                completeUnifiedNativeCommitHandoff(
                        session, snapshot.animationIdentity,
                        snapshot.actualType);
                session.unifiedNativeCommitPending = false;
                session.unifiedNativeCleanupVerified = true;
                finishUnifiedSessionAfterNativeListener(session, reason);
                return true;
            }
            if (acceptUnifiedNativeCommitFromFinishSnapshot(
                    session, snapshot, reason)) {
                return true;
            }
            if (acceptUnifiedStandardCommitFromFinishSnapshot(
                    session, snapshot, reason)) {
                return true;
            }
            boolean activeCommitAnimTo = snapshot.animToEpoch > 0L
                    && ((session.unifiedNativeCommitTransition != null
                    && session.unifiedNativeCommitTransition.animToEpoch
                    == snapshot.animToEpoch
                    && isUnifiedCommitTransitionAtAnimToBoundary(
                    session, session.unifiedNativeCommitTransition))
                    || (session.unifiedNativeStandardCommit != null
                    && session.unifiedNativeStandardCommit.animToEpoch
                    == snapshot.animToEpoch
                    && isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                    session, session.unifiedNativeStandardCommit)));
            if ((activeCommitAnimTo
                    || hasProvisionalUnifiedCommitBoundary(session))
                    && "CLOSE_TO_DRAG".equals(snapshot.actualType)) {
                log(Log.INFO, TAG,
                        "Retained drag finish emitted inside commit animTo boundary"
                                + ", generation=" + session.generation
                                + ", reason=" + reason
                                + ", animToEpoch="
                                + snapshot.animToEpoch);
                return false;
            }
            if (session.unifiedNativeCommitPending
                    && "CLOSE_TO_DRAG".equals(snapshot.actualType)
                    && snapshot.phase.compareAndSet(
                    UnifiedNativeFinishSnapshot.PHASE_PENDING,
                    UnifiedNativeFinishSnapshot.PHASE_CONSUMED)) {
                Runnable timeout = session.nativeTimeout;
                if (timeout != null) {
                    handler.removeCallbacks(timeout);
                }
                session.nativeTimeout = null;
                session.unifiedNativeCommitPending = false;
                session.unifiedNativeCleanupVerified = true;
                log(Log.WARN, TAG,
                        "Finished committed return-home at exact stopped drag boundary"
                                + ", generation=" + session.generation
                                + ", reason=" + reason
                                + ", animationIdentity="
                                + shortObject(snapshot.animationIdentity));
                finishUnifiedSessionAfterNativeListener(session, reason);
                return true;
            }
            return false;
        }

        protected void acceptUnifiedNativeCommit(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection) {
            if (session == null || inspection == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativeCommitPending
                    || inspection.commitTransition == null
                    || session.unifiedNativeCommitTransition
                    != inspection.commitTransition
                    || session.unifiedNativeCommitAttempt
                    != inspection.attempt) {
                return;
            }
            Object currentElement;
            boolean exactTransition;
            try {
                currentElement = invokeAnyMethod(
                        session.stateManager, "getCurrentWindowElement",
                        new Object[0]);
                int transitionPhase = inspection.commitTransition.phase.get();
                exactTransition =
                        isUnifiedCommitTransitionAtAnimToBoundary(
                                session, inspection.commitTransition)
                                && isExactUnifiedCommitTransition(
                                session, inspection.commitTransition,
                                session.nativeWindowElement,
                                transitionPhase);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Could not verify Xiaomi commit element on main"
                                + ", generation=" + session.generation
                                + ", attempt=" + inspection.attempt,
                        throwable);
                return;
            }
            boolean exact = inspection.failure == null
                    && exactTransition
                    && inspection.commitTransition.animParams.get()
                    != null
                    && inspection.sameAnimation
                    && inspection.exactTarget
                    && inspection.requestedType.equals(
                    inspection.actualType)
                    && isReturnHomeNativeCloseType(
                    inspection.actualType)
                    && (!"CLOSE_TO_ELEMENT".equals(
                    inspection.actualType)
                    || hasCommittedUnifiedElementGeometry(
                    session, inspection.commitTransition,
                    session.nativeWindowElement,
                    inspection.animationIdentity))
                    && currentElement == session.nativeWindowElement;
            if (!exact) {
                log(Log.WARN, TAG,
                        "Rejected Xiaomi unified commit at animation-owner tail"
                                + ", generation=" + session.generation
                                + ", attempt=" + inspection.attempt
                                + ", requestedType="
                                + inspection.requestedType
                                + ", actualType="
                                + inspection.actualType
                                + ", sameAnimation="
                                + inspection.sameAnimation
                                + ", exactTarget="
                                + inspection.exactTarget
                                + ", running=" + inspection.running,
                        inspection.failure);
                return;
            }
            if (!adoptUnifiedCommitTransitionToken(
                    inspection.commitTransition)) {
                return;
            }
            Runnable previousTimeout = session.nativeTimeout;
            if (previousTimeout != null) {
                handler.removeCallbacks(previousTimeout);
            }
            session.nativeTimeout = null;
            session.unifiedNativeCommitPending = false;
            session.nativeAnimationIdentity =
                    inspection.animationIdentity;
            session.nativeAnimationType = inspection.actualType;
            session.nativeAnimationStarted = true;
            session.nativeContinuationVerified = true;
            markUnifiedElementLeashAdopted(
                    session, inspection.animationIdentity,
                    inspection.actualType);
            handler.post(() -> completeUnifiedNativeCommitHandoff(
                    session, inspection.animationIdentity,
                    inspection.actualType));
            scheduleUnifiedNativeEndTimeout(session);
            log(Log.INFO, TAG,
                    "Accepted the same Xiaomi predictive spring at owner tail"
                            + ", generation=" + session.generation
                            + ", attempt=" + inspection.attempt
                            + ", from=CLOSE_TO_DRAG"
                            + ", to=" + inspection.actualType
                            + ", running=" + inspection.running
                            + ", finishComplete="
                            + inspection.finishComplete
                            + ", animationIdentity="
                            + shortObject(inspection.animationIdentity)
                            + ", leash="
                            + shortObject(session.closingLeash));
        }

        protected void markUnifiedElementLeashAdopted(
                ReturnHomeSession session, Object animationIdentity,
                String animationType) {
            ReturnHomeElementLeashReuseToken token =
                    pendingElementLeashReuse.get();
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            if (token == null || token.session != session
                    || token.windowElement
                    != session.nativeWindowElement
                    || token.animationIdentity != animationIdentity
                    || token.closingLeash != session.closingLeash
                    || !"CLOSE_TO_ELEMENT".equals(animationType)
                    || !hasCommittedUnifiedElementGeometry(
                    session, transition,
                    session.nativeWindowElement,
                    animationIdentity)) {
                return;
            }
            if (token.phase.compareAndSet(
                    ReturnHomeElementLeashReuseToken.PHASE_REARMED,
                    ReturnHomeElementLeashReuseToken.PHASE_ADOPTED)) {
                log(Log.INFO, TAG,
                        "Accepted predictive element leash at animation-owner tail"
                                + ", generation=" + session.generation
                                + ", taskId=" + token.taskId
                                + ", transitionDebugId="
                                + token.transitionDebugId);
            }
        }

        protected void scheduleUnifiedNativeEndTimeout(
                ReturnHomeSession session) {
            Runnable endTimeout = () -> {
                if (currentSession != session
                        || session.finished.get() != 0
                        || !session.nativeAnimationStarted
                        || session.unifiedNativeCleanupVerified) {
                    return;
                }
                if (consumeUnifiedNativeFinishSnapshot(
                        session, "nativeEndTimeout")) {
                    return;
                }
                long attempt = session.unifiedNativeRetargetAttempts
                        .incrementAndGet();
                try {
                    executeOnNativeGestureAnimationOwner(() -> {
                        UnifiedNativeRetargetInspection inspection =
                                inspectUnifiedNativeRetarget(
                                        session, attempt,
                                        session.nativeAnimationType,
                                        false);
                        handler.post(() -> {
                            if (currentSession != session
                                    || session.finished.get() != 0
                                    || session.unifiedNativeCleanupVerified
                                    || session.nativeAnimationIdentity
                                    != inspection.animationIdentity) {
                                return;
                            }
                            if (consumeUnifiedNativeFinishSnapshot(
                                    session,
                                    "nativeEndOwnerTimeout")) {
                                return;
                            }
                            log(Log.ERROR, TAG,
                                    "Retained timed-out Xiaomi native owner without same-epoch end"
                                            + ", generation="
                                            + session.generation
                                            + ", type="
                                            + inspection.actualType
                                            + ", running="
                                            + inspection.running
                                            + ", finishComplete="
                                            + inspection.finishComplete,
                                    inspection.failure);
                            scheduleUnifiedNativeEndTimeout(session);
                        });
                    });
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG,
                            "Could not classify timed-out Xiaomi native owner"
                                    + ", generation="
                                    + session.generation,
                            throwable);
                    scheduleUnifiedNativeEndTimeout(session);
                }
            };
            session.nativeTimeout = endTimeout;
            handler.postDelayed(endTimeout,
                    RETURN_HOME_NATIVE_TIMEOUT_MS);
        }

        protected void classifyUnifiedCommitTransitionTimeout(
                ReturnHomeSession session) {
            if (currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativeCommitPending
                    || session.nativeAnimationStarted) {
                return;
            }
            long attempt = session.unifiedNativeRetargetAttempts
                    .incrementAndGet();
            session.unifiedNativeCommitAttempt = attempt;
            String requestedType =
                    session.unifiedNativeCommitRequestedType;
            try {
                executeOnNativeGestureAnimationOwner(() -> {
                    UnifiedNativeRetargetInspection inspection =
                            inspectUnifiedNativeRetarget(
                                    session, attempt,
                                    requestedType == null
                                            ? "timeout-unclassified"
                                            : requestedType,
                                    false,
                                    session.unifiedNativeCommitTransition);
                    handler.post(() ->
                            completeUnifiedCommitTransitionTimeout(
                                    session, inspection));
                });
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not classify timed-out Xiaomi commit owner"
                                + ", generation=" + session.generation
                                + ", attempt=" + attempt,
                        throwable);
            }
        }

        protected void completeUnifiedCommitTransitionTimeout(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection) {
            if (session == null || inspection == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativeCommitPending
                    || session.nativeAnimationStarted
                    || session.unifiedNativeCommitAttempt
                    != inspection.attempt) {
                return;
            }
            if (consumeUnifiedNativeFinishSnapshot(
                    session, "commitTransitionTimeout")) {
                return;
            }
            Object currentElement;
            try {
                currentElement = invokeAnyMethod(
                        session.stateManager, "getCurrentWindowElement",
                        new Object[0]);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not verify timed-out Xiaomi commit element"
                                + ", generation=" + session.generation,
                        throwable);
                return;
            }
            boolean exactOwner = inspection.failure == null
                    && inspection.sameAnimation
                    && inspection.exactTarget
                    && currentElement == session.nativeWindowElement;
            UnifiedNativeStandardCommitToken standardCommit =
                    session.unifiedNativeStandardCommit;
            boolean standardType = "CLOSE_TO_HOME".equals(
                    inspection.actualType)
                    || "CLOSE_TO_HOME_CENTER".equals(
                    inspection.actualType);
            boolean exactStandardCommit = exactOwner
                    && standardType
                    && inspection.commitTransition == null
                    && isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                    session, standardCommit);
            if (exactStandardCommit) {
                standardCommit.ownerAttempt = inspection.attempt;
                UnifiedNativeRetargetInspection normalized =
                        new UnifiedNativeRetargetInspection(
                                inspection.attempt,
                                inspection.actualType,
                                inspection.actualType,
                                inspection.animationIdentity,
                                true, true, inspection.running,
                                inspection.finishComplete,
                                inspection.fullscreen,
                                null, null);
                log(Log.WARN, TAG,
                        "Recovered Xiaomi standard commit from timeout inspection"
                                + ", generation=" + session.generation
                                + ", signalAttempt="
                                + standardCommit.signal.attempt
                                + ", ownerAttempt="
                                + inspection.attempt
                                + ", type="
                                + inspection.actualType
                                + ", running="
                                + inspection.running
                                + ", finishComplete="
                                + inspection.finishComplete);
                acceptUnifiedStandardCommit(
                        session, standardCommit, normalized);
                return;
            }
            UnifiedNativeCommitTransitionToken transition =
                    inspection.commitTransition;
            boolean exactTransition =
                    isUnifiedCommitTransitionAtAnimToBoundary(
                            session, transition);
            if (exactOwner && exactTransition
                    && isReturnHomeNativeCloseType(
                    inspection.actualType)) {
                session.unifiedNativeCommitRequestedType =
                        inspection.actualType;
                UnifiedNativeRetargetInspection normalized =
                        new UnifiedNativeRetargetInspection(
                                inspection.attempt,
                                inspection.actualType,
                                inspection.actualType,
                                inspection.animationIdentity,
                                true, true, inspection.running,
                                inspection.finishComplete,
                                inspection.fullscreen,
                                transition, null);
                acceptUnifiedNativeCommit(session, normalized);
                return;
            }
            if (exactOwner
                    && "CLOSE_TO_DRAG".equals(
                    inspection.actualType)) {
                boolean invalidatedAnimToBoundary = false;
                UnifiedNativeStandardCommitToken stalledStandard =
                        session.unifiedNativeStandardCommit;
                if (stalledStandard != null
                        && isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                        session, stalledStandard)
                        && adoptUnifiedStandardCommitToken(
                        stalledStandard)) {
                    stalledStandard.phase.set(
                            UnifiedNativeStandardCommitToken.PHASE_INVALID);
                    if (session.unifiedNativeStandardCommit
                            == stalledStandard) {
                        session.unifiedNativeStandardCommit = null;
                    }
                    invalidatedAnimToBoundary = true;
                }
                UnifiedNativeCommitTransitionToken stalledTransition =
                        session.unifiedNativeCommitTransition;
                if (stalledTransition != null
                        && isUnifiedCommitTransitionAtAnimToBoundary(
                        session, stalledTransition)
                        && adoptUnifiedCommitTransitionToken(
                        stalledTransition)) {
                    stalledTransition.phase.set(
                            UnifiedNativeCommitTransitionToken.PHASE_INVALID);
                    if (session.unifiedNativeCommitTransition
                            == stalledTransition) {
                        session.unifiedNativeCommitTransition = null;
                    }
                    session.unifiedNativeCommitAttempt = 0L;
                    session.unifiedNativeCommitRequestedType = null;
                    invalidateElementTransitionContinuity(
                            session, "exactDragAfterCommitAnimTo", true);
                    invalidatedAnimToBoundary = true;
                }
                if (invalidatedAnimToBoundary) {
                    boolean terminationQueued =
                            requestUnifiedPendingCommitTermination(
                                    session,
                                    "commitAnimToStayedDrag");
                    log(terminationQueued ? Log.WARN : Log.ERROR, TAG,
                            "Terminating exact drag after failed commit animTo"
                                    + ", generation="
                                    + session.generation
                                    + ", attempt="
                                    + inspection.attempt
                                    + ", terminationQueued="
                                    + terminationQueued);
                    return;
                }
                log(Log.WARN, TAG,
                        "Retained committed Xiaomi drag owner without same-epoch end"
                                + ", generation="
                                + session.generation
                                + ", attempt="
                                + inspection.attempt
                                + ", running="
                                + inspection.running
                                + ", finishComplete="
                                + inspection.finishComplete);
                String externalReason =
                        session.unifiedNativeExternalTerminationReason;
                if (externalReason != null) {
                    requestUnifiedPendingCommitTermination(
                            session, externalReason);
                }
                return;
            }
            log(Log.ERROR, TAG,
                    "Retained unclassified Xiaomi commit owner"
                            + ", generation=" + session.generation
                            + ", attempt=" + inspection.attempt
                            + ", type=" + inspection.actualType
                            + ", sameAnimation="
                            + inspection.sameAnimation
                            + ", exactTarget="
                            + inspection.exactTarget
                            + ", sameElement="
                            + (currentElement
                            == session.nativeWindowElement)
                            + ", running=" + inspection.running
                            + ", finishComplete="
                            + inspection.finishComplete,
                    inspection.failure);
        }

        protected void completeUnifiedNativeCommitHandoff(
                ReturnHomeSession session, Object animationIdentity,
                String animationType) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                log(Log.ERROR, TAG,
                        "Rejected launcher-state handoff off main"
                                + ", generation="
                                + (session == null ? 0L
                                : session.generation)
                                + ", type=" + animationType);
                return;
            }
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.nativeAnimationStarted
                    || session.nativeAnimationIdentity
                    != animationIdentity
                    || !animationType.equals(
                    session.nativeAnimationType)) {
                return;
            }
            invalidateUnifiedPendingInterruption(
                    session, "nativeCommitAccepted:" + animationType);
            // WindowElement.animTo() is the first part of StateManager's closing update.
            // Queue behind that main-loop turn so shortcut, wallpaper, and blur commands have
            // all reached their native ownership boundary before module state is transferred.
            session.previewBlurProviderReturned = true;
            session.previewBackdropProviderReturned = true;
            completeNativePreviewBackdropHandoff(session);
            completeNativePreviewBlurHandoff(session);
            log(Log.INFO, TAG,
                    "Transferred predictive launcher state after Xiaomi commit"
                            + ", generation=" + session.generation
                            + ", type=" + animationType);
        }

        void invalidateElementTransitionContinuity(
                ReturnHomeSession session, String reason,
                boolean clearHelper) {
            while (true) {
                ReturnHomeElementLeashReuseToken token =
                        pendingElementLeashReuse.get();
                if (token == null || (session != null
                        && token.session != session)) {
                    return;
                }
                synchronized (token.session.nativeGeometryApplyLock) {
                    synchronized (token) {
                        if (pendingElementLeashReuse.get() != token) {
                            continue;
                        }
                        if (!pendingElementLeashReuse.compareAndSet(
                                token, null)) {
                            continue;
                        }
                        token.phase.set(
                                ReturnHomeElementLeashReuseToken.PHASE_INVALID);
                        int seedPhase = token.startGeometrySeed.get();
                        if (seedPhase != ReturnHomeElementLeashReuseToken
                                .SEED_COMMITTED) {
                            token.startGeometrySeed.set(
                                    ReturnHomeElementLeashReuseToken.SEED_INVALID);
                        }
                    }
                }
                // The token is unreachable and the apply lock is released before
                // touching Xiaomi's helper lock below.
                if (clearHelper) {
                    try {
                        Object savedLeash = invokeAnyMethod(
                                token.helper, "getOpenLeash", new Object[0]);
                        boolean containsTask = Boolean.TRUE.equals(
                                invokeAnyMethod(token.helper,
                                        "containsTaskId",
                                        new Object[]{Integer.valueOf(
                                                token.taskId)}));
                        if (containsTask
                                && savedLeash instanceof SurfaceControl
                                && surfacesAreSame(
                                (SurfaceControl) savedLeash,
                                token.closingLeash)) {
                            invokeAnyMethod(token.helper,
                                    "clearTempSaveOpenLeash",
                                    new Object[0]);
                        }
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG,
                                "Failed to clear predictive element leash token"
                                        + ", generation="
                                        + token.generation
                                        + ", reason=" + reason,
                                throwable);
                    }
                }
                return;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        protected void ensureUnifiedNativePreviewReflection()
                throws Exception {
            if (nativeTargetSetConstructor != null
                    && nativeWindowAnimParamsConstructor != null
                    && nativeRectFParamsConstructor != null
                    && nativeCornerRadiiConstructor != null
                    && nativeClipAnimationHelperConstructor != null
                    && nativeGestureAnimExecutorMethod != null
                    && nativeCloseToDragType != null
                    && nativeAppToAppType != null) {
                return;
            }
            Class<?> compatClass = Class.forName(
                    MIUI_HOME_REMOTE_ANIMATION_TARGET_COMPAT, false,
                    classLoader);
            Class<?> compatArrayClass =
                    Array.newInstance(compatClass, 0).getClass();
            Class<?> targetSetClass = Class.forName(
                    MIUI_HOME_REMOTE_ANIMATION_TARGET_SET, false,
                    classLoader);
            Class<?> windowAnimParamsClass = Class.forName(
                    MIUI_HOME_WINDOW_ANIM_PARAMS, false, classLoader);
            Class<?> rectFParamsClass = Class.forName(
                    MIUI_HOME_RECTF_PARAMS, false, classLoader);
            Class<?> animTypeClass = Class.forName(
                    MIUI_HOME_RECTF_SPRING_ANIM_TYPE, false,
                    classLoader);
            Class<?> springAnimClass = Class.forName(
                    MIUI_HOME_RECTF_SPRING_ANIM, false, classLoader);
            Class<?> cornerRadiiClass = Class.forName(
                    MIUI_HOME_CORNER_RADII, false, classLoader);
            Class<?> clipAnimationHelperClass = Class.forName(
                    MIUI_HOME_CLIP_ANIMATION_HELPER, false, classLoader);
            Class<?> windowAnimListenerClass = Class.forName(
                    MIUI_HOME_WINDOW_ANIM_LISTENER, false, classLoader);
            Class<?> gestureCalculatorClass = Class.forName(
                    MIUI_HOME_GESTURE_HOME_CALCULATOR, false,
                    classLoader);

            Constructor<?> targetSet = targetSetClass.getDeclaredConstructor(
                    compatArrayClass, int.class, compatArrayClass);
            Constructor<?> windowParams =
                    windowAnimParamsClass.getDeclaredConstructor(
                            RectF.class, RectF.class, cornerRadiiClass,
                            cornerRadiiClass, float.class, float.class);
            Constructor<?> rectParams = rectFParamsClass.getDeclaredConstructor(
                    targetSetClass, windowAnimParamsClass, animTypeClass,
                    boolean.class, boolean.class, boolean.class, View.class,
                    windowAnimListenerClass, clipAnimationHelperClass,
                    boolean.class, int.class, int.class,
                    gestureCalculatorClass, boolean.class, int.class,
                    boolean.class, boolean.class, int.class, int.class,
                    boolean.class, boolean.class, boolean.class);
            Constructor<?> radii = cornerRadiiClass.getDeclaredConstructor(
                    float.class);
            Constructor<?> clip =
                    clipAnimationHelperClass.getDeclaredConstructor();
            Method gestureAnimExecutor = springAnimClass.getDeclaredMethod(
                    "getGestureAnimRunningExecutor");
            targetSet.setAccessible(true);
            windowParams.setAccessible(true);
            rectParams.setAccessible(true);
            radii.setAccessible(true);
            clip.setAccessible(true);
            gestureAnimExecutor.setAccessible(true);
            Class<? extends Enum> enumClass =
                    (Class<? extends Enum>) animTypeClass.asSubclass(
                            Enum.class);

            nativeTargetSetConstructor = targetSet;
            nativeWindowAnimParamsConstructor = windowParams;
            nativeRectFParamsConstructor = rectParams;
            nativeCornerRadiiConstructor = radii;
            nativeClipAnimationHelperConstructor = clip;
            nativeGestureAnimExecutorMethod = gestureAnimExecutor;
            nativeCloseToDragType = Enum.valueOf(
                    enumClass, "CLOSE_TO_DRAG");
            nativeAppToAppType = Enum.valueOf(enumClass, "APP_TO_APP");
        }

        protected void executeOnNativeGestureAnimationOwner(Runnable runnable)
                throws Exception {
            ensureUnifiedNativePreviewReflection();
            Object executor = nativeGestureAnimExecutorMethod.invoke(null);
            if (executor instanceof Executor) {
                ((Executor) executor).execute(runnable);
                return;
            }
            invokeAnyMethod(executor, "execute", new Object[]{runnable});
        }

        protected void setUnifiedNativePreviewSpringEndEnabled(
                ReturnHomeSession session, boolean enabled,
                String reason) throws Throwable {
            if (session == null || session.nativeWindowElement == null) {
                throw new IllegalStateException(
                        "missing Xiaomi predictive WindowElement");
            }
            if (enabled != session.unifiedNativePreviewSpringEndHeld) {
                return;
            }
            Object callbackCollection = invokeAnyMethod(
                    session.nativeWindowElement,
                    "getSetAnimEndEnableCallbacks", new Object[0]);
            if (!(callbackCollection instanceof List<?>)
                    || ((List<?>) callbackCollection).isEmpty()) {
                throw new IllegalStateException(
                        "missing Xiaomi animation-end callbacks");
            }
            // Mark a hold before dispatch so a partial failure is retried as an enable.
            if (!enabled) {
                session.unifiedNativePreviewSpringEndHeld = true;
            }
            for (Object callback : (List<?>) callbackCollection) {
                invokeAnyMethod(callback, "invoke",
                        new Object[]{Boolean.valueOf(enabled)});
            }
            session.unifiedNativePreviewSpringEndHeld = !enabled;
            log(Log.INFO, TAG,
                    (enabled ? "Released" : "Held")
                            + " Xiaomi predictive spring natural end"
                            + ", generation=" + session.generation
                            + ", callbacks="
                            + ((List<?>) callbackCollection).size()
                            + ", reason=" + reason);
        }

        protected Object wrapNativeAnimationTargets(Object[] targets)
                throws Exception {
            Class<?> compatClass = Class.forName(
                    MIUI_HOME_REMOTE_ANIMATION_TARGET_COMPAT, false,
                    classLoader);
            if (targets == null || targets.length == 0) {
                return Array.newInstance(compatClass, 0);
            }
            Method wrap = compatClass.getDeclaredMethod(
                    "wrap", targets.getClass());
            wrap.setAccessible(true);
            return wrap.invoke(null, new Object[]{targets});
        }

        protected Object resolveUnifiedNativeClosingTarget(
                ReturnHomeSession session, Object targetSet)
                throws Exception {
            Object target = targetSet == null ? null
                    : invokeAnyMethod(targetSet,
                    "getFirstTarget", new Object[0]);
            Object compatLeash = target == null ? null
                    : readField(target, "leash");
            Object surface = compatLeash == null ? null
                    : readField(compatLeash, "mSurfaceControl");
            return target != null
                    && readIntFieldOrDefault(target, "taskId", -1)
                    == session.unifiedNativeTaskId
                    && surface instanceof SurfaceControl
                    && ((SurfaceControl) surface).isValid()
                    && surfacesAreSame((SurfaceControl) surface,
                    session.closingLeash) ? target : null;
        }

        protected Object createUnifiedNativeRectFParams(
                ReturnHomeSession session, Object animType,
                RectF targetRect, float endRadius, boolean needFinish,
                RectF explicitStartRect) throws Exception {
            ensureUnifiedNativePreviewReflection();
            Object startRadii = nativeCornerRadiiConstructor.newInstance(
                    Float.valueOf(session.currentCornerRadius));
            Object endRadii = nativeCornerRadiiConstructor.newInstance(
                    Float.valueOf(endRadius));
            Object windowParams =
                    nativeWindowAnimParamsConstructor.newInstance(
                            explicitStartRect == null ? null
                                    : new RectF(explicitStartRect),
                            new RectF(targetRect), startRadii, endRadii,
                            Float.valueOf(1.0f), Float.valueOf(1.0f));
            return nativeRectFParamsConstructor.newInstance(
                    session.unifiedNativeTargetSet, windowParams, animType,
                    Boolean.TRUE, Boolean.valueOf(needFinish), Boolean.FALSE,
                    null, null, session.unifiedNativeClipHelper,
                    Boolean.TRUE,
                    Integer.valueOf(session.unifiedNativeCurrentRotation),
                    Integer.valueOf(session.unifiedNativeHomeRotation), null,
                    Boolean.valueOf(needFinish), Integer.valueOf(0),
                    Boolean.FALSE, Boolean.FALSE,
                    Integer.valueOf(session.unifiedNativeTaskId),
                    Integer.valueOf(2), Boolean.valueOf(needFinish),
                    Boolean.FALSE, Boolean.FALSE);
        }

        protected boolean prepareUnifiedNativePreview(
                ReturnHomeSession session) {
            if (session == null || session.finished.get() != 0
                    || currentSession != session
                    || !session.previewInitialized
                    || session.unifiedNativePreviewOwned
                    || !isStandardSingleTaskReturnHome(session)
                    || Looper.myLooper() != Looper.getMainLooper()) {
                return false;
            }
            try {
                Object closingTaskInfo = readField(
                        session.closingTarget, "taskInfo");
                Object openingTaskInfo = readField(
                        session.openingTarget, "taskInfo");
                Object closingConfiguration = readField(
                        session.closingTarget, "windowConfiguration");
                Object openingConfiguration = readField(
                        session.openingTarget, "windowConfiguration");
                int taskId = readIntFieldOrDefault(
                        session.closingTarget, "taskId", -1);
                int closingDisplay = readIntFieldOrDefault(
                        closingTaskInfo, "displayId", -1);
                int openingDisplay = readIntFieldOrDefault(
                        openingTaskInfo, "displayId", -1);
                Object closingRotationObject = invokeAnyMethod(
                        closingConfiguration, "getRotation", new Object[0]);
                Object openingRotationObject = invokeAnyMethod(
                        openingConfiguration, "getRotation", new Object[0]);
                int closingRotation = closingRotationObject instanceof Number
                        ? ((Number) closingRotationObject).intValue() : -1;
                int openingRotation = openingRotationObject instanceof Number
                        ? ((Number) openingRotationObject).intValue() : -1;
                boolean exactShape = taskId >= 0 && closingDisplay >= 0
                        && closingDisplay == openingDisplay
                        && closingRotation >= 0 && openingRotation >= 0
                        && resolveRemoteTargetActivityType(
                        session.closingTarget) == ACTIVITY_TYPE_STANDARD
                        && resolveRemoteTargetWindowingMode(
                        session.closingTarget) == WINDOWING_MODE_FULLSCREEN
                        && resolveRemoteTargetActivityType(
                        session.openingTarget) == ACTIVITY_TYPE_HOME
                        && resolveRemoteTargetWindowingMode(
                        session.openingTarget) == WINDOWING_MODE_FULLSCREEN
                        && session.previewLeash != null
                        && session.closingLeash != null
                        && session.previewLeash.isValid()
                        && session.closingLeash.isValid()
                        && surfacesAreSame(session.previewLeash,
                        session.closingLeash);
                if (!exactShape) {
                    return false;
                }

                ensureUnifiedNativePreviewReflection();
                Object compatApps = wrapNativeAnimationTargets(session.apps);
                Object closingCompat = null;
                int appCount = Array.getLength(compatApps);
                for (int index = 0; index < appCount; index++) {
                    Object candidate = Array.get(compatApps, index);
                    if (readIntFieldOrDefault(candidate, "mode", -1) == 1
                            && readIntFieldOrDefault(
                            candidate, "taskId", -1) == taskId) {
                        if (closingCompat != null) {
                            throw new IllegalStateException(
                                    "multiple Xiaomi closing targets");
                        }
                        closingCompat = candidate;
                    }
                }
                if (closingCompat == null) {
                    throw new IllegalStateException(
                            "missing Xiaomi closing target");
                }
                Class<?> compatClass =
                        compatApps.getClass().getComponentType();
                Object previewApps = Array.newInstance(compatClass, 1);
                Array.set(previewApps, 0, closingCompat);
                Object emptyTargets = Array.newInstance(compatClass, 0);
                Object targetSet = nativeTargetSetConstructor.newInstance(
                        previewApps, Integer.valueOf(1), emptyTargets);
                session.unifiedNativeTaskId = taskId;
                Object firstTarget = resolveUnifiedNativeClosingTarget(
                        session, targetSet);
                if (firstTarget == null) {
                    throw new IllegalStateException(
                            "wrapped Xiaomi closing leash changed");
                }

                Object clipHelper =
                        nativeClipAnimationHelperConstructor.newInstance();
                invokeAnyMethod(clipHelper, "updateSourceStack",
                        new Object[]{firstTarget});
                invokeAnyMethod(clipHelper, "updateSourceStackBounds",
                        new Object[]{targetSet, Boolean.TRUE});
                Object sourceBounds = readField(
                        firstTarget, "sourceContainerBounds");
                Rect nativeBounds = sourceBounds instanceof Rect
                        && !((Rect) sourceBounds).isEmpty()
                        ? new Rect((Rect) sourceBounds)
                        : new Rect(session.startRect);
                invokeAnyMethod(clipHelper, "updateHomeStack",
                        new Object[]{nativeBounds});
                invokeAnyMethod(clipHelper, "updateTargetRect",
                        new Object[]{nativeBounds});
                invokeAnyMethod(clipHelper, "prepareAnimation",
                        new Object[]{Boolean.FALSE});
                invokeAnyMethod(clipHelper, "setIsUseForHomeGesture",
                        new Object[]{Boolean.TRUE});

                Class<?> stateManagerClass = Class.forName(
                        MIUI_HOME_STATE_MANAGER, false, classLoader);
                Object companion = readStaticField(
                        stateManagerClass, "Companion");
                Object stateManager = invokeAnyMethod(
                        companion, "getInstance", new Object[0]);
                Object previousElement = invokeAnyMethod(
                        stateManager, "getCurrentWindowElement",
                        new Object[0]);
                if (previousElement != null || Boolean.TRUE.equals(
                        invokeAnyMethod(stateManager,
                                "isWindowElementRunning", new Object[0]))) {
                    log(Log.INFO, TAG,
                            "Skipped unified predictive owner for active element"
                                    + ", generation=" + session.generation
                                    + ", element="
                                    + shortObject(previousElement));
                    return false;
                }
                invokeAnyMethod(stateManager,
                        "initWindowElement", new Object[0]);
                Object windowElement = invokeAnyMethod(
                        stateManager, "getCurrentWindowElement",
                        new Object[0]);
                Object animationIdentity = windowElement == null ? null
                        : invokeAnyMethod(windowElement,
                        "getAnimSymbol", new Object[0]);
                if (windowElement == null || animationIdentity == null) {
                    throw new IllegalStateException(
                            "new Xiaomi WindowElement has no animation owner");
                }
                // Publish ownership before validating the rest of the freshly-created
                // element. Any later failure must cancel this exact WindowElement instead of
                // falling back to a second surface animator while leaving it in StateManager.
                session.stateManager = stateManager;
                session.nativeWindowElement = windowElement;
                session.nativeAnimationIdentity = animationIdentity;
                session.nativeAnimationType = "CLOSE_TO_DRAG";
                session.unifiedNativeAnimationIdentity =
                        animationIdentity;
                session.unifiedNativeTargetSet = targetSet;
                session.unifiedNativeClipHelper = clipHelper;
                session.unifiedNativeCurrentRotation = closingRotation;
                session.unifiedNativeHomeRotation = openingRotation;
                session.unifiedNativePreviewOwned = true;
                // Exercise both generated accessors (with the exact backing-field fallback)
                // before the module starts the first native frame. Later finish-epoch gating
                // must not discover an unusable gate after ownership has already transferred.
                verifyUnifiedStateManagerListenerGate(
                        session, true, "previewClaimProbeDisable");
                verifyUnifiedStateManagerListenerGate(
                        session, false, "previewClaimProbeRestore");
                Object compat = invokeAnyMethod(windowElement,
                        "getWindowTransitionCompat", new Object[0]);
                Object helper = compat == null ? null
                        : invokeAnyMethod(compat,
                        "getCallbackHelper", new Object[0]);
                if (helper == null || Boolean.TRUE.equals(invokeAnyMethod(
                        windowElement, "isAnimRunning", new Object[0]))
                        || Boolean.TRUE.equals(invokeAnyMethod(
                        helper, "hasMainFinishCallback", new Object[0]))
                        || Boolean.TRUE.equals(invokeAnyMethod(
                        helper, "hasMergeFinishCallback", new Object[0]))
                        || Boolean.TRUE.equals(invokeAnyMethod(
                        helper, "isFinishCalled", new Object[0]))
                        || invokeAnyMethod(helper,
                        "getOpenLeash", new Object[0]) != null) {
                    throw new IllegalStateException(
                            "new Xiaomi WindowElement is not idle");
                }
                // Xiaomi exposes this callback set to keep every WindowElement animator
                // logically running while a native owner must survive a stationary phase.
                // Its closing provider restores the same callbacks before retargeting;
                // cancellation restores them explicitly below.
                setUnifiedNativePreviewSpringEndEnabled(
                        session, false, "previewStart");
                if (!driveUnifiedNativePreviewFrame(session, true)) {
                    throw new IllegalStateException(
                            "failed first Xiaomi CLOSE_TO_DRAG frame");
                }
                if (invokeAnyMethod(stateManager,
                        "getCurrentWindowElement", new Object[0])
                        != windowElement
                        || invokeAnyMethod(windowElement,
                        "getAnimSymbol", new Object[0])
                        != animationIdentity
                        || invokeAnyMethod(windowElement,
                        "getRemoteTargetSet", new Object[0]) != targetSet) {
                    throw new IllegalStateException(
                            "Xiaomi predictive owner changed during start");
                }
                log(Log.INFO, TAG,
                        "Unified predictive preview with Xiaomi WindowElement"
                                + ", generation=" + session.generation
                                + ", taskId=" + taskId
                                + ", animationIdentity="
                                + shortObject(animationIdentity)
                                + ", leash="
                                + shortObject(session.closingLeash));
                return true;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to establish unified Xiaomi predictive owner"
                                + ", generation=" + session.generation,
                        throwable);
                if (session.unifiedNativePreviewOwned) {
                    startUnifiedNativeCancel(
                            session, "prepareFailed");
                    return true;
                }
                return false;
            }
        }

        protected boolean driveUnifiedNativePreviewFrame(
                ReturnHomeSession session, boolean firstFrame) {
            if (session == null || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified
                    || session.finished.get() != 0
                    || currentSession != session
                    || session.nativeHandoffStarted
                    || session.unifiedNativeCancelPending) {
                return false;
            }
            try {
                Object currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
                Object currentIdentity = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getAnimSymbol", new Object[0]);
                if (currentElement != session.nativeWindowElement
                        || currentIdentity
                        != session.unifiedNativeAnimationIdentity
                        || session.previewLeash == null
                        || !session.previewLeash.isValid()
                        || !surfacesAreSame(session.previewLeash,
                        session.closingLeash)) {
                    throw new IllegalStateException(
                            "unified Xiaomi preview ownership changed");
                }
                Object params = createUnifiedNativeRectFParams(
                        session, nativeCloseToDragType,
                        session.currentRect, session.currentCornerRadius,
                        false, firstFrame
                                ? new RectF(session.startRect) : null);
                invokeAnyMethod(session.nativeWindowElement,
                        "animTo", new Object[]{params});
                return true;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to drive unified Xiaomi predictive frame"
                                + ", generation=" + session.generation
                                + ", rect=" + session.currentRect,
                        throwable);
                return false;
            }
        }

        protected boolean requestUnifiedPendingCommitTermination(
                ReturnHomeSession session, String reason) {
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified
                    || !session.nativeHandoffStarted
                    || !session.unifiedNativeCommitPending
                    || session.nativeAnimationStarted
                    || session.unifiedNativeCancelPending) {
                return false;
            }
            long attempt = session.unifiedNativeRetargetAttempts
                    .incrementAndGet();
            session.unifiedNativeExternalTerminationAttempt = attempt;
            session.unifiedNativeExternalTerminationReason = reason;
            try {
                executeOnNativeGestureAnimationOwner(() -> {
                    UnifiedNativeRetargetInspection inspection =
                            inspectUnifiedNativeRetarget(
                                    session, attempt,
                                    "CLOSE_TO_DRAG", false,
                                    session.unifiedNativeCommitTransition);
                    handler.post(() ->
                            completeUnifiedPendingCommitTermination(
                                    session, inspection, reason));
                });
                log(Log.WARN, TAG,
                        "Queued exact Xiaomi pending-commit termination"
                                + ", generation=" + session.generation
                                + ", attempt=" + attempt
                                + ", reason=" + reason);
                return true;
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not inspect Xiaomi pending commit at external terminal"
                                + ", generation=" + session.generation
                                + ", attempt=" + attempt
                                + ", reason=" + reason,
                        throwable);
                return false;
            }
        }

        protected void completeUnifiedPendingCommitTermination(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection,
                String reason) {
            if (session == null || inspection == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || session.unifiedNativeCleanupVerified
                    || !session.nativeHandoffStarted
                    || !session.unifiedNativeCommitPending
                    || session.nativeAnimationStarted
                    || session.unifiedNativeExternalTerminationAttempt
                    != inspection.attempt
                    || !reason.equals(
                    session.unifiedNativeExternalTerminationReason)) {
                return;
            }
            if (consumeUnifiedNativeFinishSnapshot(
                    session, "externalTerminal:" + reason)) {
                return;
            }
            Object currentElement;
            try {
                currentElement = invokeAnyMethod(
                        session.stateManager, "getCurrentWindowElement",
                        new Object[0]);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not verify Xiaomi owner at external terminal"
                                + ", generation=" + session.generation
                                + ", attempt=" + inspection.attempt
                                + ", reason=" + reason,
                        throwable);
                return;
            }
            UnifiedNativeCommitTransitionToken transition =
                    session.unifiedNativeCommitTransition;
            int transitionPhase = transition == null
                    ? UnifiedNativeCommitTransitionToken.PHASE_INVALID
                    : transition.phase.get();
            boolean exactDragOwner = inspection.failure == null
                    && inspection.sameAnimation
                    && inspection.exactTarget
                    && "CLOSE_TO_DRAG".equals(inspection.actualType)
                    && currentElement == session.nativeWindowElement;
            boolean safeExternalBoundary = exactDragOwner
                    && transition == null;
            if (!safeExternalBoundary) {
                log(Log.ERROR, TAG,
                        "Retained Xiaomi owner across unsafe external terminal"
                                + ", generation=" + session.generation
                                + ", attempt=" + inspection.attempt
                                + ", reason=" + reason
                                + ", type=" + inspection.actualType
                                + ", sameAnimation="
                                + inspection.sameAnimation
                                + ", exactTarget="
                                + inspection.exactTarget
                                + ", sameElement="
                                + (currentElement
                                == session.nativeWindowElement)
                                + ", running=" + inspection.running
                                + ", finishComplete="
                                + inspection.finishComplete
                                + ", transitionPhase="
                                + transitionPhase,
                        inspection.failure);
                return;
            }
            UnifiedNativeStandardCommitToken standardCommit =
                    session.unifiedNativeStandardCommit;
            if (standardCommit != null
                    && standardCommit.session == session
                    && standardCommit.windowElement
                    == session.nativeWindowElement
                    && standardCommit.animationIdentity
                    == session.unifiedNativeAnimationIdentity) {
                int standardPhase = standardCommit.phase.get();
                if (standardPhase
                        == UnifiedNativeStandardCommitToken.PHASE_ENTERING
                        || standardPhase
                        == UnifiedNativeStandardCommitToken.PHASE_ENTERED
                        || standardPhase
                        == UnifiedNativeStandardCommitToken.PHASE_CONSUMED) {
                    standardCommit.phase.set(
                            UnifiedNativeStandardCommitToken.PHASE_INVALID);
                    if (session.unifiedNativeStandardCommit
                            == standardCommit) {
                        session.unifiedNativeStandardCommit = null;
                    }
                    log(Log.WARN, TAG,
                            "Invalidated standard commit after exact drag proof"
                                    + ", generation="
                                    + session.generation
                                    + ", signalAttempt="
                                    + standardCommit.signal.attempt
                                    + ", phase=" + standardPhase
                                    + ", reason=" + reason);
                }
            }
            boolean accepted = startUnifiedNativeCancel(
                    session, "externalTerminal:" + reason, true);
            boolean cancelEntered = accepted
                    && session.unifiedNativeCancelPending;
            if (cancelEntered) {
                session.unifiedNativeExternalTerminationAttempt = 0L;
                session.unifiedNativeExternalTerminationReason = null;
            }
            log(cancelEntered ? Log.WARN : Log.ERROR, TAG,
                    "Applied Xiaomi pending-commit termination"
                            + ", generation=" + session.generation
                            + ", attempt=" + inspection.attempt
                            + ", reason=" + reason
                            + ", running=" + inspection.running
                            + ", transitionPhase=" + transitionPhase
                            + ", accepted=" + cancelEntered);
        }

        protected boolean startUnifiedNativeCancel(
                ReturnHomeSession session, String reason) {
            return startUnifiedNativeCancel(session, reason, false);
        }

        protected boolean startUnifiedNativeCancel(
                ReturnHomeSession session, String reason,
                boolean externalPendingCommitTermination) {
            if (session == null || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified
                    || currentSession != session
                    || session.finished.get() != 0) {
                return false;
            }
            freezePreviewProgress(session,
                    "unifiedCancel:" + reason);
            if (session.unifiedNativeCancelPending) {
                return true;
            }
            boolean exactExternalPendingCommit =
                    externalPendingCommitTermination
                            && session.nativeHandoffStarted
                            && session.unifiedNativeCommitPending
                            && !session.nativeAnimationStarted;
            if (session.nativeAnimationStarted
                    || (!exactExternalPendingCommit
                    && (session.nativeHandoffStarted
                    || session.unifiedNativeCommitPending))) {
                return false;
            }
            boolean animToEntered = false;
            boolean externalStateCleared = false;
            UnifiedNativeCommitTransitionToken externalTransition = null;
            Runnable externalTimeout = null;
            Object cancelParams = null;
            long cancelAnimToEpoch = 0L;
            try {
                Object currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
                Object currentIdentity = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getAnimSymbol", new Object[0]);
                String currentType = readNativeAnimationType(
                        session.nativeWindowElement);
                if (currentElement != session.nativeWindowElement
                        || currentIdentity
                        != session.unifiedNativeAnimationIdentity) {
                    throw new IllegalStateException(
                            "Xiaomi owner changed before cancel"
                                    + ", type=" + currentType
                                    + ", sameElement="
                                    + (currentElement
                                    == session.nativeWindowElement)
                                    + ", sameIdentity="
                                    + (currentIdentity
                                    == session.unifiedNativeAnimationIdentity));
                }
                if (exactExternalPendingCommit) {
                    externalTransition =
                            session.unifiedNativeCommitTransition;
                    if (!"CLOSE_TO_DRAG".equals(currentType)
                            || externalTransition != null) {
                        log(Log.WARN, TAG,
                                "Rejected external termination across Xiaomi commit boundary"
                                        + ", generation="
                                        + session.generation
                                        + ", reason=" + reason
                                        + ", type=" + currentType
                                        + ", transitionPhase="
                                        + (externalTransition == null
                                        ? -1
                                        : externalTransition.phase.get()));
                        return false;
                    }
                    externalTimeout = session.nativeTimeout;
                    session.nativeHandoffStarted = false;
                    session.unifiedNativeCommitPending = false;
                    externalStateCleared = true;
                }
                session.unifiedNativeCancelPending = true;
                session.unifiedNativeCancelRetargeted = false;
                session.unifiedNativeCancelEndObserved = false;
                long attempt = session.unifiedNativeRetargetAttempts
                        .incrementAndGet();
                session.unifiedNativeCancelAttempt = attempt;
                cancelParams = createUnifiedNativeRectFParams(
                        session, nativeAppToAppType,
                        new RectF(session.startRect),
                        session.startCornerRadius, true, null);
                cancelAnimToEpoch = beginUnifiedAnimToEpoch(
                        session, "cancel:" + reason);
                session.unifiedNativeCancelAnimToEpoch =
                        cancelAnimToEpoch;
                session.unifiedNativeCancelEndObserved = false;
                session.unifiedNativeCancelAnimParams = cancelParams;
                verifyUnifiedStateManagerListenerGate(
                        session, true, "cancelEntry:" + reason);
                scheduleUnifiedNativeCancelTimeout(
                        session, cancelAnimToEpoch, reason);
                setUnifiedNativePreviewSpringEndEnabled(
                        session, true, "cancelEntry:" + reason);
                animToEntered = true;
                invokeAnyMethod(session.nativeWindowElement,
                        "animTo", new Object[]{cancelParams});
                UnifiedNativeFinishSnapshot synchronousFinish =
                        session.unifiedNativeFinishSnapshot.get();
                if (synchronousFinish != null
                        && synchronousFinish.animToEpoch
                        == cancelAnimToEpoch
                        && "CLOSE_TO_DRAG".equals(
                        synchronousFinish.actualType)
                        && synchronousFinish.phase.compareAndSet(
                        UnifiedNativeFinishSnapshot.PHASE_PENDING,
                        UnifiedNativeFinishSnapshot.PHASE_INVALID)) {
                    session.unifiedNativeFinishSnapshot.compareAndSet(
                            synchronousFinish, null);
                    session.unifiedNativeCancelEndObserved = false;
                    log(Log.INFO, TAG,
                            "Discarded previous drag finish from cancel animTo call"
                                    + ", generation="
                                    + session.generation
                                    + ", animToEpoch="
                                    + cancelAnimToEpoch);
                }
                if (externalStateCleared) {
                    session.unifiedNativeCommitAttempt = 0L;
                    session.unifiedNativeCommitRequestedType = null;
                    if (externalTimeout != null) {
                        handler.removeCallbacks(externalTimeout);
                    }
                    session.nativeTimeout = null;
                    if (externalTransition != null) {
                        externalTransition.phase.set(
                                UnifiedNativeCommitTransitionToken
                                        .PHASE_INVALID);
                    }
                    session.unifiedNativeCommitTransition = null;
                    invalidateElementTransitionContinuity(
                            session, "externalCommitTermination", true);
                }
                executeOnNativeGestureAnimationOwner(() -> {
                    UnifiedNativeRetargetInspection inspection =
                            inspectUnifiedNativeRetarget(
                                    session, attempt,
                                    "APP_TO_APP", true);
                    handler.post(() -> acceptUnifiedNativeCancel(
                            session, inspection, reason));
                });
                log(Log.INFO, TAG,
                        "Queued unified Xiaomi fullscreen-cancel verification"
                                + ", generation=" + session.generation
                                + ", attempt=" + attempt
                                + ", reason=" + reason
                                + ", observedTypeBeforeQueue="
                                + currentType
                                + ", animationIdentity="
                                + shortObject(
                                session.unifiedNativeAnimationIdentity));
                return true;
            } catch (Throwable throwable) {
                boolean terminalQueued =
                        publishUnifiedNativeTerminalFailure(
                                session, cancelParams,
                                cancelParams, cancelAnimToEpoch,
                                true, exactExternalPendingCommit,
                                externalStateCleared,
                                "cancelFailure:" + reason,
                                throwable);
                if (terminalQueued) {
                    log(Log.ERROR, TAG,
                            "Terminating failed unified Xiaomi cancel through exact native owner"
                                    + ", generation="
                                    + session.generation
                                    + ", reason=" + reason
                                    + ", animToEntered="
                                    + animToEntered
                                    + ", animToEpoch="
                                    + cancelAnimToEpoch,
                            throwable);
                    return true;
                }
                if (!animToEntered) {
                    if (externalStateCleared) {
                        session.nativeHandoffStarted = true;
                        session.unifiedNativeCommitPending = true;
                        session.unifiedNativeCommitTransition =
                                externalTransition;
                        session.nativeTimeout = externalTimeout;
                    }
                    session.unifiedNativeCancelPending = false;
                    session.unifiedNativeCancelRetargeted = false;
                    session.unifiedNativeCancelEndObserved = false;
                    session.unifiedNativeCancelAttempt = 0L;
                    session.unifiedNativeCancelAnimParams = null;
                } else if (externalStateCleared) {
                    session.unifiedNativeCommitAttempt = 0L;
                    session.unifiedNativeCommitRequestedType = null;
                    if (externalTimeout != null) {
                        handler.removeCallbacks(externalTimeout);
                    }
                    session.nativeTimeout = null;
                    if (externalTransition != null) {
                        externalTransition.phase.set(
                                UnifiedNativeCommitTransitionToken
                                        .PHASE_INVALID);
                    }
                    session.unifiedNativeCommitTransition = null;
                    invalidateElementTransitionContinuity(
                            session, "externalCommitTerminationPartial",
                            true);
                }
                log(Log.ERROR, TAG,
                        "Could not queue or terminate unified Xiaomi cancel"
                                + ", generation=" + session.generation
                                + ", reason=" + reason
                                + ", animToEntered="
                                + animToEntered,
                        throwable);
                return false;
            }
        }

        protected void scheduleUnifiedNativeCancelTimeout(
                ReturnHomeSession session, long animToEpoch,
                String reason) {
            Runnable previous = session.unifiedNativeCancelTimeout;
            if (previous != null) {
                handler.removeCallbacks(previous);
            }
            Runnable timeout = () -> {
                if (currentSession != session
                        || session.finished.get() != 0
                        || session.unifiedNativeCleanupVerified
                        || !session.unifiedNativeCancelPending
                        || session.unifiedNativeCancelAnimToEpoch
                        != animToEpoch) {
                    return;
                }
                long attempt = session.unifiedNativeRetargetAttempts
                        .incrementAndGet();
                session.unifiedNativeCancelTimeoutAttempt = attempt;
                try {
                    executeOnNativeGestureAnimationOwner(() -> {
                        UnifiedNativeRetargetInspection inspection =
                                inspectUnifiedNativeRetarget(
                                        session, attempt,
                                        "APP_TO_APP", true);
                        handler.post(() ->
                                completeUnifiedNativeCancelTimeout(
                                        session, inspection,
                                        animToEpoch, reason));
                    });
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG,
                            "Could not inspect timed-out Xiaomi cancel owner"
                                    + ", generation="
                                    + session.generation
                                    + ", attempt=" + attempt
                                    + ", animToEpoch="
                                    + animToEpoch
                                    + ", reason=" + reason,
                            throwable);
                    scheduleUnifiedNativeCancelTimeout(
                            session, animToEpoch, reason);
                }
            };
            session.unifiedNativeCancelTimeout = timeout;
            handler.postDelayed(timeout,
                    RETURN_HOME_NATIVE_TIMEOUT_MS);
        }

        protected void completeUnifiedNativeCancelTimeout(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection,
                long animToEpoch, String reason) {
            if (session == null || inspection == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || session.unifiedNativeCleanupVerified
                    || !session.unifiedNativeCancelPending
                    || session.unifiedNativeCancelAnimToEpoch
                    != animToEpoch
                    || session.unifiedNativeActiveAnimToEpoch
                    != animToEpoch
                    || session.unifiedNativeCancelTimeoutAttempt
                    != inspection.attempt) {
                return;
            }
            if (consumeUnifiedNativeFinishSnapshot(
                    session, "unifiedCancelTimeout:" + reason)) {
                return;
            }
            Object currentElement = null;
            try {
                currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Could not verify timed-out Xiaomi cancel element"
                                + ", generation="
                                + session.generation
                                + ", attempt="
                                + inspection.attempt,
                        throwable);
            }
            log(Log.ERROR, TAG,
                    "Retained timed-out Xiaomi cancel owner without same-epoch end"
                            + ", generation=" + session.generation
                            + ", attempt=" + inspection.attempt
                            + ", animToEpoch=" + animToEpoch
                            + ", type=" + inspection.actualType
                            + ", sameAnimation="
                            + inspection.sameAnimation
                            + ", exactTarget="
                            + inspection.exactTarget
                            + ", sameElement="
                            + (currentElement
                            == session.nativeWindowElement)
                            + ", running=" + inspection.running
                            + ", finishComplete="
                            + inspection.finishComplete
                            + ", fullscreen="
                            + inspection.fullscreen,
                    inspection.failure);
            scheduleUnifiedNativeCancelTimeout(
                    session, animToEpoch, reason);
        }

        protected void acceptUnifiedNativeCancel(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection,
                String reason) {
            if (session == null || inspection == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativeCancelPending
                    || session.unifiedNativeCancelAttempt
                    != inspection.attempt) {
                return;
            }
            Object currentElement;
            try {
                currentElement = invokeAnyMethod(
                        session.stateManager, "getCurrentWindowElement",
                        new Object[0]);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Could not verify Xiaomi cancel element on main"
                                + ", generation=" + session.generation
                                + ", attempt=" + inspection.attempt,
                        throwable);
                return;
            }
            boolean exact = inspection.failure == null
                    && inspection.sameAnimation
                    && inspection.exactTarget
                    && "APP_TO_APP".equals(inspection.actualType)
                    && currentElement == session.nativeWindowElement;
            if (!exact) {
                log(Log.ERROR, TAG,
                        "Rejected Xiaomi cancel at animation-owner tail; retained owner"
                                + ", generation=" + session.generation
                                + ", attempt=" + inspection.attempt
                                + ", actualType="
                                + inspection.actualType
                                + ", sameAnimation="
                                + inspection.sameAnimation
                                + ", exactTarget="
                                + inspection.exactTarget
                                + ", running=" + inspection.running,
                        inspection.failure);
                return;
            }
            session.unifiedNativeCancelRetargeted = true;
            log(Log.INFO, TAG,
                    "Accepted unified Xiaomi fullscreen cancel at owner tail"
                            + ", generation=" + session.generation
                            + ", attempt=" + inspection.attempt
                            + ", reason=" + reason
                            + ", running=" + inspection.running
                            + ", finishComplete="
                            + inspection.finishComplete
                            + ", fullscreen="
                            + inspection.fullscreen);
            if (session.unifiedNativeCancelEndObserved) {
                completeUnifiedNativeCancel(
                        session, "unifiedCancelEndBeforeAcceptance");
            }
        }

        protected void completeUnifiedNativeCancel(
                ReturnHomeSession session, String reason) {
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativeCancelPending
                    || !session.unifiedNativeCancelRetargeted
                    || !session.unifiedNativeCancelEndObserved) {
                return;
            }
            consumeUnifiedNativeFinishSnapshot(session, reason);
        }

        void finishUnifiedCancelForReusedOpen(
                Object stateManager, Object windowElement,
                Object animationIdentity) {
            ReturnHomeSession session = currentSession;
            UnifiedNativeConfiguredAnimToSnapshot configured = session == null
                    ? null : session.unifiedNativeConfiguredAnimTo.get();
            if (Looper.myLooper() != Looper.getMainLooper()
                    || session == null || session.finished.get() != 0
                    || session.unifiedNativeCleanupVerified
                    || session.stateManager != stateManager
                    || session.nativeWindowElement != windowElement
                    || session.unifiedNativeAnimationIdentity != animationIdentity
                    || session.nativeHandoffStarted
                    || session.nativeAnimationStarted
                    || session.unifiedNativeCommitPending
                    || !isExactUnifiedConfiguredAnimTo(
                    session, configured, windowElement,
                    animationIdentity, "APP_TO_APP")) {
                return;
            }
            Runnable timeout = session.unifiedNativeCancelTimeout;
            if (timeout != null) {
                handler.removeCallbacks(timeout);
            }
            session.unifiedNativeCancelTimeout = null;
            session.unifiedNativeCancelPending = false;
            session.unifiedNativeCancelRetargeted = false;
            session.unifiedNativeCleanupVerified = true;
            log(Log.INFO, TAG,
                    "Finished cancelled return-home owner at reused launcher OPEN"
                            + ", generation=" + session.generation
                            + ", animToEpoch=" + configured.animToEpoch
                            + ", animationIdentity="
                            + shortObject(animationIdentity));
            finishSession(session, "cancelReusedForLauncherOpen", false);
        }

        protected boolean isStandardSingleTaskReturnHome(ReturnHomeSession session) {
            if (session.apps == null || session.apps.length != 2
                    || session.closingTarget == null
                    || session.openingTarget == null) {
                return false;
            }
            int closingCount = 0;
            int openingCount = 0;
            for (Object target : session.apps) {
                int mode = readIntFieldOrDefault(target, "mode", -1);
                if (mode == 1) {
                    closingCount++;
                    if (target != session.closingTarget) {
                        return false;
                    }
                } else if (mode == 0) {
                    openingCount++;
                    if (target != session.openingTarget) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            return closingCount == 1 && openingCount == 1;
        }

        protected boolean standardSignalMatchesSession(
                StandardReturnHomeCommitSignal signal,
                ReturnHomeSession session) {
            if (signal == null || session == null
                    || session.finished.get() != 0
                    || session.unifiedNativeCleanupVerified
                    || !signal.matchesInput(
                    session.acceptedInputIdentity)
                    || signal.arbiterGeneration
                    != miuiHomeSystemUiInputArbiterGeneration
                    || (signal.launcherSessionGeneration != 0L
                    && signal.launcherSessionGeneration
                    != session.generation)
                    || !isStandardSingleTaskReturnHome(session)) {
                return false;
            }
            int closingTaskId = session.unifiedNativeTaskId >= 0
                    ? session.unifiedNativeTaskId
                    : readIntFieldOrDefault(
                    session.closingTarget, "taskId", -1);
            return closingTaskId >= 0
                    && signal.taskId == closingTaskId;
        }

        void onMiuiHomeAcceptedInputIdentityChanged(
                MiuiHomeAcceptedInputToken identity) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post(() ->
                        onMiuiHomeAcceptedInputIdentityChanged(identity));
                return;
            }
            while (true) {
                StandardReturnHomeCommitSignal pending =
                        pendingStandardCommitSignal.get();
                if (pending == null
                        || pending.launcherSessionGeneration != 0L
                        || pending.matchesInput(identity)) {
                    return;
                }
                if (pendingStandardCommitSignal.compareAndSet(
                        pending, null)) {
                    log(Log.WARN, TAG,
                            "Discarded unbound standard commit on new accepted DOWN"
                                    + ", attempt=" + pending.attempt
                                    + ", oldEventId="
                                    + pending.eventId
                                    + ", newEventId="
                                    + (identity == null ? 0
                                    : identity.eventId));
                    return;
                }
            }
        }

        protected void discardMatchingUnboundStandardSignal(
                MiuiHomeAcceptedInputToken identity, String reason) {
            if (identity == null) {
                return;
            }
            while (true) {
                StandardReturnHomeCommitSignal pending =
                        pendingStandardCommitSignal.get();
                if (pending == null
                        || pending.launcherSessionGeneration != 0L
                        || pending.arbiterGeneration
                        != miuiHomeSystemUiInputArbiterGeneration
                        || !pending.matchesInput(identity)) {
                    return;
                }
                if (pendingStandardCommitSignal.compareAndSet(
                        pending, null)) {
                    log(Log.WARN, TAG,
                            "Discarded matching unbound standard commit"
                                    + ", attempt=" + pending.attempt
                                    + ", taskId=" + pending.taskId
                                    + ", eventId=" + pending.eventId
                                    + ", arbiterGeneration="
                                    + pending.arbiterGeneration
                                    + ", reason=" + reason);
                    return;
                }
            }
        }

        protected void bindPendingStandardCommitToSession(
                ReturnHomeSession session) {
            if (session == null || currentSession != session) {
                return;
            }
            while (true) {
                StandardReturnHomeCommitSignal pending =
                        pendingStandardCommitSignal.get();
                if (pending == null
                        || pending.launcherSessionGeneration
                        == session.generation) {
                    return;
                }
                if (pending.launcherSessionGeneration != 0L
                        || !standardSignalMatchesSession(
                        pending, session)) {
                    if (pending.launcherSessionGeneration == 0L
                            && pendingStandardCommitSignal.compareAndSet(
                            pending, null)) {
                        log(Log.WARN, TAG,
                                "Discarded unbound standard commit at runner mismatch"
                                        + ", attempt="
                                        + pending.attempt
                                        + ", eventId="
                                        + pending.eventId
                                        + ", runnerGeneration="
                                        + session.generation);
                    }
                    return;
                }
                StandardReturnHomeCommitSignal bound =
                        pending.bindToLauncherSession(
                                session.generation);
                if (pendingStandardCommitSignal.compareAndSet(
                        pending, bound)) {
                    log(Log.INFO, TAG,
                            "Bound early standard commit to launcher runner"
                                    + ", attempt=" + bound.attempt
                                    + ", taskId=" + bound.taskId
                                    + ", eventId=" + bound.eventId
                                    + ", launcherGeneration="
                                    + bound.launcherSessionGeneration);
                    return;
                }
            }
        }

        void onStandardShellReturnHomeCommit(
                StandardReturnHomeCommitSignal signal) {
            if (signal == null || !attached) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post(() -> onStandardShellReturnHomeCommit(signal));
                return;
            }
            ReturnHomeSession activeSession = currentSession;
            boolean bindNow = standardSignalMatchesSession(
                    signal, activeSession);
            MiuiHomeAcceptedInputToken latestInput =
                    miuiHomeAcceptedInputIdentity.get();
            boolean latestInputMatches = signal.matchesInput(latestInput);
            if (signal.arbiterGeneration
                    != miuiHomeSystemUiInputArbiterGeneration
                    || (!latestInputMatches && !bindNow)) {
                log(Log.WARN, TAG,
                        "Rejected standard commit without current accepted DOWN"
                                + ", attempt=" + signal.attempt
                                + ", taskId=" + signal.taskId
                                + ", eventId=" + signal.eventId
                                + ", currentEventId="
                                + (latestInput == null ? 0
                                : latestInput.eventId));
                return;
            }
            if (signal.attempt <= lastStandardCommitSignalAttempt) {
                log(Log.WARN, TAG,
                        "Ignored reordered standard return-home commit signal"
                                + ", attempt=" + signal.attempt
                                + ", lastAttempt="
                                + lastStandardCommitSignalAttempt
                                + ", taskId=" + signal.taskId);
                return;
            }
            lastStandardCommitSignalAttempt = signal.attempt;
            StandardReturnHomeCommitSignal boundSignal = bindNow
                    ? signal.bindToLauncherSession(
                    activeSession.generation)
                    : signal;
            while (true) {
                StandardReturnHomeCommitSignal previous =
                        pendingStandardCommitSignal.get();
                if (previous != null
                        && previous.attempt >= boundSignal.attempt) {
                    log(Log.WARN, TAG,
                            "Ignored stale standard return-home commit signal"
                                    + ", attempt=" + boundSignal.attempt
                                    + ", activeAttempt="
                                    + previous.attempt
                                    + ", taskId=" + boundSignal.taskId);
                    return;
                }
                if (pendingStandardCommitSignal.compareAndSet(
                        previous, boundSignal)) {
                    break;
                }
            }
            log(Log.INFO, TAG,
                    "Received authenticated standard return-home commit"
                            + ", attempt=" + boundSignal.attempt
                            + ", taskId=" + boundSignal.taskId
                            + ", transitionDebugId="
                            + boundSignal.transitionDebugId
                            + ", arbiterGeneration="
                            + boundSignal.arbiterGeneration
                            + ", eventId=" + boundSignal.eventId
                            + ", inputIdentitySource="
                            + (latestInputMatches ? "latest" : "activeSession")
                            + ", latestEventId="
                            + (latestInput == null ? 0 : latestInput.eventId)
                            + ", launcherGeneration="
                            + boundSignal.launcherSessionGeneration);
            if (bindNow) {
                continueUnifiedStandardCommit(activeSession);
            } else {
                log(Log.INFO, TAG,
                        "Retained authenticated standard commit until runner arrives"
                                + ", attempt="
                                + boundSignal.attempt
                                + ", taskId="
                                + boundSignal.taskId
                                + ", eventId="
                                + boundSignal.eventId);
            }
        }

        protected void continueUnifiedStandardCommit(
                ReturnHomeSession session) {
            StandardReturnHomeCommitSignal signal =
                    pendingStandardCommitSignal.get();
            if (signal == null || session == null
                    || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || !session.nativeHandoffStarted
                    || !session.unifiedNativeCommitPending
                    || !session.unifiedNativeCommitReady.get()
                    || session.unifiedNativeCleanupVerified
                    || session.unifiedNativeStandardCommit != null) {
                return;
            }
            if (signal.arbiterGeneration
                    != miuiHomeSystemUiInputArbiterGeneration
                    || signal.launcherSessionGeneration
                    != session.generation
                    || !signal.matchesInput(
                    session.acceptedInputIdentity)
                    || signal.taskId != session.unifiedNativeTaskId
                    || session.unifiedNativeCommitTransition != null
                    || !isStandardSingleTaskReturnHome(session)) {
                if (signal.taskId != session.unifiedNativeTaskId
                        || signal.arbiterGeneration
                        != miuiHomeSystemUiInputArbiterGeneration
                        || signal.launcherSessionGeneration
                        != session.generation
                        || !signal.matchesInput(
                        session.acceptedInputIdentity)) {
                    pendingStandardCommitSignal.compareAndSet(signal, null);
                }
                log(Log.WARN, TAG,
                        "Rejected non-matching standard return-home commit"
                                + ", attempt=" + signal.attempt
                                + ", signalTaskId=" + signal.taskId
                                + ", sessionTaskId="
                                + session.unifiedNativeTaskId
                                + ", signalGeneration="
                                + signal.arbiterGeneration
                                + ", currentGeneration="
                                + miuiHomeSystemUiInputArbiterGeneration
                                + ", signalLauncherGeneration="
                                + signal.launcherSessionGeneration
                                + ", sessionGeneration="
                                + session.generation
                                + ", inputMatch="
                                + signal.matchesInput(
                                session.acceptedInputIdentity)
                                + ", nativeTransition="
                                + shortObject(
                                session.unifiedNativeCommitTransition));
                return;
            }
            UnifiedNativeStandardCommitToken standardToken = null;
            try {
                Object currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
                Object currentIdentity = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getAnimSymbol", new Object[0]);
                String currentType = readNativeAnimationType(
                        session.nativeWindowElement);
                if (currentElement != session.nativeWindowElement
                        || currentIdentity
                        != session.unifiedNativeAnimationIdentity
                        || !"CLOSE_TO_DRAG".equals(currentType)
                        || !pendingStandardCommitSignal.compareAndSet(
                        signal, null)) {
                    throw new IllegalStateException(
                            "standard commit owner changed"
                                    + ", currentType=" + currentType
                                    + ", sameElement="
                                    + (currentElement
                                    == session.nativeWindowElement)
                                    + ", sameIdentity="
                                    + (currentIdentity
                                    == session.unifiedNativeAnimationIdentity));
                }
                UnifiedNativeStandardCommitToken token =
                        new UnifiedNativeStandardCommitToken(
                                session, signal);
                standardToken = token;
                session.unifiedNativeStandardCommit = token;
                boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                        session.nativeWindowElement,
                        "isAnimRunning", new Object[0]));
                if (!running) {
                    throw new IllegalStateException(
                            "held Xiaomi predictive spring became idle"
                                    + " before standard CLOSE");
                }
                Object compatApps = wrapNativeAnimationTargets(
                        session.apps);
                Object compatNonApps = wrapNativeAnimationTargets(
                        session.nonApps);
                invokeAnyMethod(session.nativeWindowElement,
                        "onClosingWindowTransitionExecute",
                        new Object[]{compatApps, compatNonApps,
                                null, Boolean.FALSE});
                session.unifiedNativePreviewSpringEndHeld = false;
                session.previewBlurProviderReturned = true;
                session.previewBackdropProviderReturned = true;
                log(Log.INFO, TAG,
                        "Requested Xiaomi standard CLOSE on unified owner"
                                + ", generation=" + session.generation
                                + ", attempt=" + signal.attempt
                                + ", taskId=" + signal.taskId
                                + ", transitionDebugId="
                                + signal.transitionDebugId
                                + ", dragRunning=" + running
                                + ", animationIdentity="
                                + shortObject(currentIdentity));
            } catch (Throwable throwable) {
                UnifiedNativeStandardCommitToken token = standardToken;
                if (token == null) {
                    token = session.unifiedNativeStandardCommit;
                }
                boolean failedBeforeAdoption = token == null
                        || (token.session == session
                        && token.phase.compareAndSet(
                        UnifiedNativeStandardCommitToken.PHASE_PENDING,
                        UnifiedNativeStandardCommitToken.PHASE_INVALID));
                if (failedBeforeAdoption) {
                    if (token != null
                            && session.unifiedNativeStandardCommit == token) {
                        session.unifiedNativeStandardCommit = null;
                    }
                    boolean terminationQueued =
                            requestUnifiedPendingCommitTermination(
                                    session,
                                    "standardNativeHandoffRejected");
                    log(Log.ERROR, TAG,
                            "Failed Xiaomi standard CLOSE before animTo adoption"
                                    + ", generation="
                                    + session.generation
                                    + ", attempt=" + signal.attempt
                                    + ", taskId=" + signal.taskId
                                    + ", tokenCreated=" + (token != null)
                                    + ", terminationQueued="
                                    + terminationQueued,
                            throwable);
                    return;
                }
                int phase = token == null
                        ? UnifiedNativeStandardCommitToken.PHASE_INVALID
                        : token.phase.get();
                boolean terminationQueued = (phase
                        == UnifiedNativeStandardCommitToken.PHASE_ENTERING
                        || phase
                        == UnifiedNativeStandardCommitToken.PHASE_ENTERED)
                        && requestUnifiedPendingCommitTermination(
                        session, "standardNativeProviderFailed");
                log(Log.ERROR, TAG,
                        "Xiaomi standard CLOSE provider tail failed; retained native owner"
                                + ", generation=" + session.generation
                                + ", attempt=" + signal.attempt
                                + ", taskId=" + signal.taskId
                                + ", tokenPhase=" + phase
                                + ", nativeStarted="
                                + session.nativeAnimationStarted
                                + ", cleanupVerified="
                                + session.unifiedNativeCleanupVerified
                                + ", terminationQueued="
                                + terminationQueued,
                        throwable);
            }
        }

        protected boolean startUnifiedNativeProviderCommit(
                ReturnHomeSession session) {
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeProviderCommitAdopted
                    || !session.nativeHandoffStarted
                    || !session.unifiedNativeCommitPending
                    || !session.unifiedNativeCommitReady.get()
                    || session.stateManager == null
                    || session.nativeWindowElement == null
                    || session.unifiedNativeAnimationIdentity == null) {
                return false;
            }
            try {
                Object currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
                Object currentIdentity = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getAnimSymbol", new Object[0]);
                String currentType = readNativeAnimationType(
                        session.nativeWindowElement);
                if (currentElement != session.nativeWindowElement
                        || currentIdentity
                        != session.unifiedNativeAnimationIdentity
                        || !"CLOSE_TO_DRAG".equals(currentType)) {
                    throw new IllegalStateException(
                            "predictive owner changed before native provider"
                                    + ", sameElement="
                                    + (currentElement
                                    == session.nativeWindowElement)
                                    + ", sameIdentity="
                                    + (currentIdentity
                                    == session.unifiedNativeAnimationIdentity)
                                    + ", type=" + currentType);
                }
                if (!Boolean.TRUE.equals(invokeAnyMethod(
                        session.nativeWindowElement,
                        "isAnimRunning", new Object[0]))) {
                    throw new IllegalStateException(
                            "held Xiaomi predictive spring became idle"
                                    + " before native provider");
                }

                long providerAnimToEpoch = beginUnifiedAnimToEpoch(
                        session, "nativeProviderCommit");
                Object compatApps = wrapNativeAnimationTargets(
                        session.apps);
                Object compatNonApps = wrapNativeAnimationTargets(
                        session.nonApps);
                invokeAnyMethod(session.nativeWindowElement,
                        "onClosingWindowTransitionExecute",
                        new Object[]{compatApps, compatNonApps,
                                null, Boolean.FALSE});
                session.unifiedNativePreviewSpringEndHeld = false;

                Object providerElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
                Object providerIdentity = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getAnimSymbol", new Object[0]);
                String providerType = readNativeAnimationType(
                        session.nativeWindowElement);
                Object providerTargetSet = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getRemoteTargetSet", new Object[0]);
                Object homeTarget = providerTargetSet == null ? null
                        : invokeAnyMethod(providerTargetSet,
                        "getHomeTarget", new Object[0]);
                SurfaceControl homeSurface = surfaceFromNativeTarget(
                        homeTarget);
                Object floatingObject = readField(
                        session.nativeWindowElement,
                        "mFloatingIconLayerLeash");
                SurfaceControl floatingSurface =
                        floatingObject instanceof SurfaceControl
                                ? (SurfaceControl) floatingObject : null;
                boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                        session.nativeWindowElement,
                        "isAnimRunning", new Object[0]));
                boolean exact = providerElement
                        == session.nativeWindowElement
                        && providerIdentity
                        == session.unifiedNativeAnimationIdentity
                        && ("CLOSE_TO_HOME".equals(providerType)
                        || "CLOSE_TO_HOME_CENTER".equals(providerType))
                        && resolveUnifiedNativeClosingTarget(
                        session, providerTargetSet) != null
                        && homeSurface != null && homeSurface.isValid()
                        && floatingSurface != null
                        && floatingSurface.isValid();
                if (!exact) {
                    throw new IllegalStateException(
                            "Xiaomi native provider did not establish full closing context"
                                    + ", sameElement="
                                    + (providerElement
                                    == session.nativeWindowElement)
                                    + ", sameIdentity="
                                    + (providerIdentity
                                    == session.unifiedNativeAnimationIdentity)
                                    + ", type=" + providerType
                                    + ", hasHome="
                                    + (homeSurface != null
                                    && homeSurface.isValid())
                                    + ", hasFloatingLayer="
                                    + (floatingSurface != null
                                    && floatingSurface.isValid())
                                    + ", running=" + running);
                }

                verifyUnifiedStateManagerListenerGate(
                        session, false, "nativeProviderAdopted");
                session.unifiedNativeProviderCommitAdopted = true;
                session.unifiedNativeCommitPending = false;
                session.nativeAnimationIdentity = providerIdentity;
                session.nativeAnimationType = providerType;
                session.nativeAnimationStarted = true;
                session.nativeContinuationVerified = true;
                session.previewBlurProviderReturned = true;
                session.previewBackdropProviderReturned = true;
                Runnable previousTimeout = session.nativeTimeout;
                if (previousTimeout != null) {
                    handler.removeCallbacks(previousTimeout);
                }
                session.nativeTimeout = null;
                completeUnifiedNativeCommitHandoff(
                        session, providerIdentity, providerType);
                scheduleUnifiedNativeEndTimeout(session);
                StandardReturnHomeCommitSignal pendingSignal =
                        pendingStandardCommitSignal.get();
                if (pendingSignal != null
                        && pendingSignal.taskId
                        == session.unifiedNativeTaskId
                        && pendingSignal.launcherSessionGeneration
                        == session.generation
                        && pendingSignal.matchesInput(
                        session.acceptedInputIdentity)) {
                    pendingStandardCommitSignal.compareAndSet(
                            pendingSignal, null);
                }
                log(Log.INFO, TAG,
                        "Adopted Xiaomi full native closing provider on unified owner"
                                + ", generation=" + session.generation
                                + ", from=CLOSE_TO_DRAG"
                                + ", to=" + providerType
                                + ", animToEpoch="
                                + providerAnimToEpoch
                                + ", running=" + running
                                + ", hasHome=true"
                                + ", floatingLayer="
                                + shortObject(floatingSurface)
                                + ", animationIdentity="
                                + shortObject(providerIdentity));
                return true;
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Failed Xiaomi full native provider adoption on unified owner"
                                + ", generation="
                                + session.generation,
                        throwable);
                return false;
            }
        }

        protected void startNativeClose(ReturnHomeSession session) {
            if (session.finished.get() != 0 || currentSession != session
                    || session.nativeHandoffStarted) {
                return;
            }
            freezePreviewProgress(session, "commit");
            if (session.previewBlurOwned) {
                // Revalidate exact ownership at the commit boundary and close any rounding
                // gap before provider code is allowed to replace params. This makes the
                // synchronous-abort identity relaxation cover only provider-owned changes.
                publishNativePreviewBlur(session,
                        session.previewBlurTargetRadius,
                        session.previewBlurTargetDimming, "commit");
            }
            prepareNativePreviewBackdropForCommit(session);
            if (session.unifiedNativePreviewOwned) {
                session.nativeHandoffStarted = true;
                session.unifiedNativeCommitPending = true;
                log(Log.INFO, TAG,
                        "Held Xiaomi CLOSE_TO_DRAG for real commit transition"
                                + ", generation=" + session.generation
                                + ", animationIdentity="
                                + shortObject(
                                session.unifiedNativeAnimationIdentity)
                                + ", rect=" + session.currentRect);
                if (!session.unifiedNativeCommitReady.compareAndSet(
                        false, true)) {
                    log(Log.ERROR, TAG,
                            "Rejected duplicate Xiaomi predictive retarget boundary"
                                    + ", generation=" + session.generation
                                    + ", ready=true");
                    return;
                }
                // A standard close is admitted by its authenticated Shell signal. An exact
                // element close enters the provider at its validated transition boundary.
                Runnable timeout = () ->
                        classifyUnifiedCommitTransitionTimeout(session);
                session.nativeTimeout = timeout;
                handler.postDelayed(timeout,
                        RETURN_HOME_NATIVE_TIMEOUT_MS);
                continueUnifiedStandardCommit(session);
                return;
            }
            session.nativeHandoffStarted = true;
            try {
                Class<?> stateManagerClass = Class.forName(
                        MIUI_HOME_STATE_MANAGER, false, classLoader);
                Object companion = readStaticField(stateManagerClass, "Companion");
                Object stateManager = invokeAnyMethod(companion,
                        "getInstance", new Object[0]);
                if (Boolean.TRUE.equals(invokeAnyMethod(stateManager,
                        "isWindowElementRunning", new Object[0]))) {
                    throw new IllegalStateException(
                            "Xiaomi WindowElement already has a running animation");
                }
                invokeAnyMethod(stateManager, "initWindowElement", new Object[0]);
                Object windowElement = invokeAnyMethod(stateManager,
                        "getCurrentWindowElement", new Object[0]);
                if (windowElement == null) {
                    throw new IllegalStateException("Xiaomi WindowElement is null");
                }
                session.stateManager = stateManager;
                session.nativeWindowElement = windowElement;
                Object windowAnimContext = invokeAnyMethod(windowElement,
                        "getWindowAnimContext", new Object[0]);
                session.nativeWindowAnimContext = windowAnimContext;
                Class<?> animStatusClass = Class.forName(
                        MIUI_HOME_ANIM_STATUS_PARAM, false, classLoader);
                Object animStatusCompanion = readStaticField(
                        animStatusClass, "Companion");
                RectF handoffRect = session.previewInitialized
                        ? new RectF(session.currentRect)
                        : new RectF(session.startRect);
                if (handoffRect.isEmpty()) {
                    throw new IllegalStateException("predictive handoff rect is empty");
                }
                Object localAnimStatus = invokeAnyMethod(animStatusCompanion,
                        "getAnimParamFromRect",
                        new Object[]{handoffRect,
                                Float.valueOf(session.currentCornerRadius),
                                Float.valueOf(1.0f)});
                invokeAnyMethod(windowAnimContext, "setLocalAnimLastStatus",
                        new Object[]{localAnimStatus});
                session.nativePublishedStatus = localAnimStatus;
                session.nativeStatusPublished = true;
                MiuiHomeLocalHandoffToken handoffToken =
                        new MiuiHomeLocalHandoffToken(
                                session.generation, session, windowElement,
                                windowAnimContext, localAnimStatus);
                session.localHandoffToken = handoffToken;
                MiuiHomeLocalHandoffToken replacedToken =
                        miuiHomeLocalHandoffToken.getAndSet(handoffToken);
                if (replacedToken != null) {
                    log(Log.WARN, TAG, "Replaced stale Xiaomi local handoff token"
                            + ", oldGeneration=" + replacedToken.generation
                            + ", newGeneration=" + session.generation);
                }
                Object compatApps = wrapNativeAnimationTargets(session.apps);
                Object compatNonApps = wrapNativeAnimationTargets(session.nonApps);
                invokeAnyMethod(windowElement,
                        "onClosingWindowTransitionExecute",
                        new Object[]{compatApps, compatNonApps, null, Boolean.FALSE});
                session.previewBlurProviderReturned = true;
                session.previewBackdropProviderReturned = true;
                // StateManager starts WindowElement before its final RecentBlur animTo(Home).
                // The whole provider has returned now, so spring/value state can distinguish a
                // real native takeover from a static repeated setTo(AppState).
                completeNativePreviewBackdropHandoff(session);
                completeNativePreviewBlurHandoff(session);
                Object remainingStatus = invokeAnyMethod(windowAnimContext,
                        "getLocalAnimLastStatus", new Object[0]);
                if (remainingStatus != localAnimStatus) {
                    miuiHomeLocalHandoffToken.compareAndSet(handoffToken, null);
                } else {
                    log(Log.WARN, TAG, "Xiaomi animator did not consume predictive handoff"
                            + ", generation=" + session.generation
                            + ", windowElement=" + shortObject(windowElement));
                }
                if (session.nativeAnimationIdentity == null) {
                    // Xiaomi's MainThreadExecutor and StateManager both execute inline when
                    // entered from this owner Looper. A missing listener identity here means
                    // the native provider rejected the CLOSE instead of starting later.
                    throw new IllegalStateException(
                            "Xiaomi CLOSE returned without an animation identity");
                }
                Runnable timeout = () -> {
                    if (currentSession == session && session.finished.get() == 0) {
                        log(Log.WARN, TAG, "Xiaomi return-to-home animation timed out"
                                + ", generation=" + session.generation
                                + ", statusPublished="
                                + session.nativeStatusPublished
                                + ", animationStarted="
                                + session.nativeAnimationStarted);
                        if (session.nativeAnimationStarted
                                && isReturnHomeNativeCloseType(
                                session.nativeAnimationType)) {
                            log(Log.ERROR, TAG,
                                    "Retained timed-out Xiaomi CLOSE owner until exact native end"
                                            + ", generation="
                                            + session.generation
                                            + ", type="
                                            + session.nativeAnimationType);
                        } else {
                            finishSession(session, "nativeTimeout",
                                    shouldRestorePreview(session));
                        }
                    }
                };
                session.nativeTimeout = timeout;
                handler.postDelayed(timeout, RETURN_HOME_NATIVE_TIMEOUT_MS);
                log(Log.INFO, TAG, "Handed predictive-back post-commit to Xiaomi"
                        + ", generation=" + session.generation
                        + ", windowElement=" + shortObject(windowElement)
                        + ", previewRect=" + session.currentRect
                        + ", previewRadius=" + session.currentCornerRadius
                        + ", statusConsumed="
                        + (miuiHomeLocalHandoffToken.get() != handoffToken));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Xiaomi native return-to-home handoff rejected"
                        + ", generation=" + session.generation, throwable);
                finishSession(session, "nativeHandoffRejected",
                        shouldRestorePreview(session));
            }
        }

        protected boolean shouldRestorePreview(ReturnHomeSession session) {
            // The synchronous listener capture proves Xiaomi has begun using this exact
            // WindowElement even before the next-loop state verification marks it running.
            // Never fight that native animation by restoring our preview transform.
            return !session.nativeAnimationStarted
                    && session.nativeAnimationIdentity == null;
        }

        boolean onNativeAnimationStart(
                Object listener, Object animationIdentity) {
            ReturnHomeSession session = currentSession;
            if (session == null) {
                return false;
            }
            try {
                Object stateManager = readField(listener, "this$0");
                Object windowElement = invokeAnyMethod(stateManager,
                        "getCurrentWindowElement", new Object[0]);
                if (session.finished.get() == 0
                        && session.unifiedNativePreviewOwned
                        && stateManager == session.stateManager
                        && windowElement == session.nativeWindowElement
                        && animationIdentity
                        == session.unifiedNativeAnimationIdentity) {
                    log(Log.INFO, TAG,
                            "Observed module-owned Xiaomi predictive spring"
                                    + ", generation="
                                    + session.generation
                                    + ", phase="
                                    + (session.nativeHandoffStarted
                                    ? "commit" : "preview")
                                    + ", animationIdentity="
                                    + shortObject(animationIdentity));
                    return true;
                }
                if (session.finished.get() != 0
                        || !session.nativeHandoffStarted
                        || !session.nativeStatusPublished
                        || session.nativeAnimationIdentity != null) {
                    return false;
                }
                if (windowElement != session.nativeWindowElement) {
                    return false;
                }
                session.nativeAnimationIdentity = animationIdentity;
                verifyNativeContinuationStart(session, animationIdentity);
                handler.post(() -> verifyNativeAnimationStarted(session,
                        stateManager, windowElement, animationIdentity));
                return true;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to capture Xiaomi CLOSE animation identity"
                        + ", generation=" + session.generation, throwable);
                return false;
            }
        }

        protected void verifyNativeContinuationStart(
                ReturnHomeSession session, Object animationIdentity) {
            try {
                Object nativeStartObject = invokeAnyMethod(
                        animationIdentity, "getStartRect", new Object[0]);
                Object nativeTargetObject = readField(animationIdentity, "mTargetRect");
                Object progressTypeObject = readField(
                        animationIdentity, "mProgressCalculateType");
                Object currentWidthObject = readField(animationIdentity, "mCurrentWidth");
                Object currentHeightObject = readField(animationIdentity, "mCurrentHeight");
                if (!(nativeStartObject instanceof RectF)
                        || !(nativeTargetObject instanceof RectF)
                        || !(progressTypeObject instanceof Number)
                        || ((Number) progressTypeObject).intValue() != 1001
                        || !(currentWidthObject instanceof Number)
                        || !(currentHeightObject instanceof Number)) {
                    throw new IllegalStateException(
                            "unexpected RectFSpringAnim continuation state");
                }
                RectF nativeStart = (RectF) nativeStartObject;
                RectF nativeTarget = (RectF) nativeTargetObject;
                RectF handoff = new RectF(session.currentRect);
                RectF fullStart = new RectF(session.startRect);
                float tolerance = Math.max(2.0f, dp(2.0f));
                float currentWidth = ((Number) currentWidthObject).floatValue();
                float currentHeight = ((Number) currentHeightObject).floatValue();
                if (handoff.isEmpty() || fullStart.isEmpty() || nativeTarget.isEmpty()
                        || Math.abs(nativeStart.width() - handoff.width()) > tolerance
                        || Math.abs(nativeStart.height() - handoff.height()) > tolerance
                        || Math.abs(currentWidth - handoff.width()) > tolerance
                        || Math.abs(currentHeight - handoff.height()) > tolerance
                        || nativeTarget.width() >= handoff.width()
                        || handoff.width() > fullStart.width() + tolerance) {
                    throw new IllegalStateException(
                            "native continuation does not match predictive handoff"
                                    + ", nativeStart=" + nativeStart
                                    + ", current=" + currentWidth + "x" + currentHeight
                                    + ", handoff=" + handoff
                                    + ", fullStart=" + fullStart
                                    + ", target=" + nativeTarget);
                }
                // AOSP LauncherBackAnimationController deliberately creates the post-commit
                // RectFSpringAnim from the handed-off current Rect. Its normalized phase starts
                // at zero; continuity comes from identical geometry, not from carrying the
                // gesture percentage into the new spring. Xiaomi has already copied this exact
                // Rect into the live values and SpringBundles, so verify it and leave its start,
                // current state, velocity, target, and progress origin untouched.
                session.nativeContinuationVerified = true;
                log(Log.INFO, TAG,
                        "Verified Xiaomi predictive return continuation start"
                                + ", generation=" + session.generation
                                + ", handoff=" + handoff
                                + ", fullStart=" + fullStart
                                + ", target=" + nativeTarget
                                + ", postCommitProgress=0.0");
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to verify Xiaomi predictive return continuation start"
                                + ", generation=" + session.generation,
                        throwable);
            }
        }

        void prepareNativeCloseInterruption(
                Object windowElement, String reason) throws Throwable {
            if (!MIUI_HOME_ICON_CLICK_WITHOUT_RECENT_REASON.equals(reason)) {
                return;
            }
            adoptConfiguredCommitForInterruption(
                    currentSession, windowElement,
                    "cancelSurfacePre:" + reason);
            ReturnHomeSession session = currentSession;
            UnifiedNativePendingInterruptionSnapshot snapshot =
                    session == null ? null
                            : session.unifiedNativePendingInterruption.get();
            if (snapshot != null) {
                synchronized (snapshot.configLock) {
                    adoptConfiguredCommitForInterruption(
                            currentSession, windowElement,
                            "cancelSurfacePreLocked:" + reason);
                    session = currentSession;
                    UnifiedNativePendingInterruptionSnapshot active =
                            session == null ? null
                                    : session.unifiedNativePendingInterruption.get();
                    if (active == snapshot && currentSession == session
                            && !session.nativeAnimationStarted
                            && snapshot.windowElement == windowElement
                            && snapshot.animationIdentity
                            == session.unifiedNativeAnimationIdentity
                            && snapshot.phase.get()
                            == UnifiedNativePendingInterruptionSnapshot
                            .PHASE_PENDING
                            && snapshot.mutation.compareAndSet(
                            UnifiedNativePendingInterruptionSnapshot
                                    .MUTATION_NONE,
                            UnifiedNativePendingInterruptionSnapshot
                                    .MUTATION_CANCEL_SURFACE)) {
                        log(Log.INFO, TAG,
                                "Marked pre-config Xiaomi cancel-surface mutation"
                                        + ", generation="
                                        + session.generation
                                        + ", animToEpoch="
                                        + snapshot.animToEpoch);
                    }
                }
            }
        }

        boolean shouldRouteSameIconThroughNativeParallel(
                Object stateManager, Object[] args) throws Throwable {
            return shouldRouteSameIconThroughNativeParallel(
                    stateManager, args, false);
        }

        protected boolean shouldRouteSameIconThroughNativeParallel(
                Object stateManager, Object[] args,
                boolean configLocked) throws Throwable {
            if (args == null || args.length != 4
                    || !MIUI_HOME_ICON_CLICK_WITHOUT_RECENT_REASON.equals(args[0])
                    || !Boolean.TRUE.equals(args[1])
                    || !Boolean.TRUE.equals(args[2])
                    || args[3] == null) {
                return false;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                log(Log.WARN, TAG,
                        "Rejected Xiaomi same-icon native parallel routing off main Looper");
                return false;
            }
            Class<?> callbackClass = Class.forName(
                    MIUI_HOME_SHELL_TRANSITION_CALLBACK, false, classLoader);
            if (!callbackClass.isInstance(args[3])) {
                return false;
            }
            ReturnHomeSession session = currentSession;
            if (session == null) {
                return false;
            }
            adoptConfiguredCommitForInterruption(
                    session, session.nativeWindowElement,
                    "sameIconParallelRoute");
            UnifiedNativePendingInterruptionSnapshot earlyPending =
                    session.unifiedNativePendingInterruption.get();
            if (!configLocked && !session.nativeAnimationStarted
                    && earlyPending != null) {
                synchronized (earlyPending.configLock) {
                    return shouldRouteSameIconThroughNativeParallel(
                            stateManager, args, true);
                }
            }
            if (session.finished.get() != 0
                    || session.cleaned.get() != 0
                    || !session.nativeHandoffStarted
                    || session.stateManager != stateManager
                    || session.nativeWindowElement == null
                    || session.nativeAnimationIdentity == null) {
                log(Log.WARN, TAG,
                        "Rejected inactive Xiaomi same-icon native parallel routing"
                                + ", generation=" + session.generation
                                + ", attached=" + attached
                                + ", finished=" + session.finished.get()
                                + ", cleaned=" + session.cleaned.get()
                                + ", nativeHandoff=" + session.nativeHandoffStarted
                                + ", nativeStarted=" + session.nativeAnimationStarted
                                + ", continuationVerified="
                                + session.nativeContinuationVerified
                                + ", sameStateManager="
                                + (session.stateManager == stateManager)
                                + ", hasWindowElement="
                                + (session.nativeWindowElement != null)
                                + ", hasAnimationIdentity="
                                + (session.nativeAnimationIdentity != null)
                                + ", type=" + session.nativeAnimationType);
                return false;
            }

            Object windowElement = session.nativeWindowElement;
            Object currentElement = invokeAnyMethod(
                    stateManager, "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(windowElement);
            Object pendingReference = invokeAnyMethod(
                    stateManager, "getPendingIconViewWeakRef", new Object[0]);
            Object pendingIcon = pendingReference instanceof WeakReference<?>
                    ? ((WeakReference<?>) pendingReference).get() : null;
            boolean samePendingIcon = pendingIcon instanceof View
                    && Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isSameElement", new Object[]{pendingIcon}));
            boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isAnimRunning", new Object[0]));
            boolean reusable = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isReusefulAnimRunning", new Object[0]));
            boolean usingSf = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isUsingSfAnim", new Object[0]));
            boolean mainAnimNoFinishClear = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "mainAnimNoFinishClear", new Object[0]));
            boolean validSurface = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "hasValidSurface", new Object[0]));
            Object multiFlyHelper = invokeAnyMethod(
                    windowElement, "getMultiFlyHelper", new Object[0]);
            boolean currentMultiFly = multiFlyHelper != null
                    && Boolean.TRUE.equals(invokeAnyMethod(
                    multiFlyHelper, "isCurrentMultiFly", new Object[0]));
            boolean nativeWouldCancelSurfaceAndView = Boolean.TRUE.equals(invokeAnyMethod(
                    stateManager, "shouldCancelSurfaceAndView",
                    new Object[]{Boolean.TRUE}));
            boolean nativeWouldCancelElement = Boolean.TRUE.equals(invokeAnyMethod(
                    stateManager, "shouldCancelElementAnim", new Object[0]));
            boolean canceled = Boolean.TRUE.equals(
                    readField(windowElement, "mCanceled"));
            boolean surfaceCanceled = Boolean.TRUE.equals(
                    readField(windowElement, "mSurfaceCanceled"));
            boolean surfaceCancelExecuted = Boolean.TRUE.equals(
                    readField(windowElement, "mSurfaceCanceledExecute"));
            boolean listenerDisabled = Boolean.TRUE.equals(
                    readField(windowElement, "mDisableStateManagerListener"));
            boolean finishSurface = Boolean.TRUE.equals(
                    readField(windowElement, "mFinishSurface"));
            boolean finishComplete = Boolean.TRUE.equals(
                    readField(windowElement, "mFinishComplete"));
            boolean duringMerge = Boolean.TRUE.equals(
                    readField(windowElement, "mDuringMerge"));
            boolean endWaitingMerge = Boolean.TRUE.equals(
                    readField(windowElement, "mEndWaitingMerge"));
            boolean cancelSurfaceTaskClear =
                    readField(windowElement, "mCancelSurfaceTask") == null;
            boolean useShellListener = Boolean.TRUE.equals(
                    readField(windowElement, "mUseShellAnimListener"));
            boolean couldExecuteShellEnd = Boolean.TRUE.equals(
                    readField(windowElement, "couldExecuteShellAnimEnd"));
            boolean callbackClear =
                    readField(windowElement, "mShellTransitionCallback") == null;
            boolean noPendingHandoff = pendingCloseInterruption.get() == null
                    && pendingDirectCancel.get() == null;
            boolean verifiedClose = session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && session.nativeAnimationIdentity == currentIdentity
                    && "CLOSE_TO_HOME".equals(currentType)
                    && session.nativeAnimationType.equals(currentType);
            UnifiedNativePendingInterruptionSnapshot
                    pendingCommitInterruption =
                    session.unifiedNativePendingInterruption.get();
            boolean pendingCommit = !session.nativeAnimationStarted
                    && isExactUnifiedPendingInterruption(
                    session, pendingCommitInterruption,
                    currentElement, currentIdentity,
                    currentType, true);
            boolean valid = currentSession == session
                    && session.generation > 0L
                    && currentElement == windowElement
                    && currentIdentity == session.nativeAnimationIdentity
                    && (verifiedClose || pendingCommit)
                    && samePendingIcon && running && !reusable && !usingSf
                    && !mainAnimNoFinishClear
                    && validSurface && multiFlyHelper != null && !currentMultiFly
                    && !nativeWouldCancelSurfaceAndView
                    && !nativeWouldCancelElement
                    && !canceled && !surfaceCanceled && !surfaceCancelExecuted
                    && (pendingCommit
                    ? listenerDisabled : !listenerDisabled)
                    && !finishSurface
                    && !duringMerge && !endWaitingMerge && cancelSurfaceTaskClear
                    && useShellListener && couldExecuteShellEnd && callbackClear
                    && noPendingHandoff;
            if (!valid) {
                log(Log.WARN, TAG,
                        "Rejected Xiaomi same-icon native parallel routing"
                                + ", generation=" + session.generation
                                + ", currentSession=" + (currentSession == session)
                                + ", sameElement=" + (currentElement == windowElement)
                                + ", sameIdentity="
                                + (currentIdentity == session.nativeAnimationIdentity)
                                + ", type=" + currentType
                                + ", expectedType=" + session.nativeAnimationType
                                + ", samePendingIcon=" + samePendingIcon
                                + ", running=" + running
                                + ", reusable=" + reusable
                                + ", usingSf=" + usingSf
                                + ", mainAnimNoFinishClear="
                                + mainAnimNoFinishClear
                                + ", validSurface=" + validSurface
                                + ", hasMultiFly=" + (multiFlyHelper != null)
                                + ", currentMultiFly=" + currentMultiFly
                                + ", nativeCancelSurfaceAndView="
                                + nativeWouldCancelSurfaceAndView
                                + ", nativeCancelElement=" + nativeWouldCancelElement
                                + ", canceled=" + canceled
                                + ", surfaceCanceled=" + surfaceCanceled
                                + ", surfaceCancelExecuted="
                                + surfaceCancelExecuted
                                + ", listenerDisabled=" + listenerDisabled
                                + ", finishSurface=" + finishSurface
                                + ", finishComplete=" + finishComplete
                                + ", duringMerge=" + duringMerge
                                + ", endWaitingMerge=" + endWaitingMerge
                                + ", cancelSurfaceTaskClear="
                                + cancelSurfaceTaskClear
                                + ", useShellListener=" + useShellListener
                                + ", couldExecuteShellEnd=" + couldExecuteShellEnd
                                + ", callbackClear=" + callbackClear
                                + ", noPendingHandoff=" + noPendingHandoff);
                return false;
            }
            log(Log.INFO, TAG,
                    "Routed same-icon predictive CLOSE through Xiaomi parallel launcher path"
                            + ", generation=" + session.generation
                            + ", type=" + currentType
                            + ", animationIdentity="
                            + shortObject(session.nativeAnimationIdentity)
                            + ", windowElement=" + shortObject(windowElement)
                            + ", pendingIcon=" + shortObject(pendingIcon));
            return true;
        }

        void onNativeCloseCancelSurface(Object windowElement, String reason) {
            if (!MIUI_HOME_ICON_CLICK_WITHOUT_RECENT_REASON.equals(reason)) {
                return;
            }
            ReturnHomeSession session = currentSession;
            try {
                adoptConfiguredCommitForInterruption(
                        session, windowElement,
                        "cancelSurfacePost:" + reason);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Could not adopt Xiaomi commit at cancel-surface fallback"
                                + ", generation="
                                + (session == null ? 0L
                                : session.generation),
                        throwable);
            }
            if (session == null || session.finished.get() != 0
                    || session.stateManager == null
                    || session.nativeWindowElement != windowElement
                    || session.nativeAnimationIdentity == null) {
                return;
            }
            try {
                Object currentElement = invokeAnyMethod(
                        session.stateManager, "getCurrentWindowElement", new Object[0]);
                Object currentIdentity = invokeAnyMethod(
                        windowElement, "getAnimSymbol", new Object[0]);
                String currentType = readNativeAnimationType(windowElement);
                boolean surfaceCanceled = Boolean.TRUE.equals(
                        readField(windowElement, "mSurfaceCanceled"));
                boolean listenerDisabled = Boolean.TRUE.equals(
                        readField(windowElement, "mDisableStateManagerListener"));
                boolean verifiedClose = session.nativeAnimationStarted
                        && session.nativeContinuationVerified
                        && session.nativeAnimationIdentity
                        == currentIdentity
                        && session.nativeAnimationType.equals(currentType)
                        && isReturnHomeNativeCloseType(currentType);
                UnifiedNativePendingInterruptionSnapshot
                        pendingCommitInterruption =
                        session.unifiedNativePendingInterruption.get();
                boolean pendingCommit = !session.nativeAnimationStarted
                        && isExactUnifiedPendingInterruption(
                        session, pendingCommitInterruption,
                        currentElement, currentIdentity,
                        currentType, false);
                if (currentElement != windowElement
                        || currentIdentity != session.nativeAnimationIdentity
                        || (!verifiedClose && !pendingCommit)
                        || !surfaceCanceled || !listenerDisabled) {
                    log(Log.WARN, TAG,
                            "Rejected mismatched interrupted Xiaomi return-home CLOSE"
                                    + ", generation=" + session.generation
                                    + ", sameElement=" + (currentElement == windowElement)
                                    + ", sameIdentity="
                                    + (currentIdentity == session.nativeAnimationIdentity)
                                    + ", surfaceCanceled=" + surfaceCanceled
                                    + ", listenerDisabled=" + listenerDisabled
                                    + ", type=" + currentType
                                    + ", verifiedClose=" + verifiedClose
                                    + ", pendingCommit=" + pendingCommit);
                    return;
                }
                ReturnHomeCloseInterruptionToken token =
                        new ReturnHomeCloseInterruptionToken(
                                session, session.stateManager, windowElement,
                                session.nativeAnimationIdentity,
                                pendingCommit
                                        ? pendingCommitInterruption
                                        : null);
                ReturnHomeCloseInterruptionToken replaced =
                        pendingCloseInterruption.getAndSet(token);
                if (replaced != null && replaced.session != session) {
                    log(Log.WARN, TAG,
                            "Replaced stale interrupted return-home token"
                                    + ", oldGeneration=" + replaced.generation
                                    + ", newGeneration=" + session.generation);
                }
                log(Log.INFO, TAG,
                        "Captured interrupted Xiaomi return-home CLOSE"
                                + ", generation=" + session.generation
                                + ", reason=" + reason
                                + ", type=" + currentType
                                + ", pendingCommit=" + pendingCommit
                                + ", animationIdentity="
                                + shortObject(session.nativeAnimationIdentity)
                                + ", windowElement=" + shortObject(windowElement));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to verify interrupted Xiaomi return-home CLOSE"
                                + ", generation=" + session.generation, throwable);
            }
        }

        void onNativeCloseSetToOld(Object stateManager, Object windowElement) {
            ReturnHomeCloseInterruptionToken token = pendingCloseInterruption.get();
            if (token == null || token.stateManager != stateManager
                    || token.windowElement != windowElement) {
                return;
            }
            ReturnHomeSession session = token.session;
            try {
                Object currentElement = invokeAnyMethod(
                        stateManager, "getCurrentWindowElement", new Object[0]);
                String replacementType = null;
                if (currentElement != null && currentElement != windowElement) {
                    replacementType = readNativeAnimationType(currentElement);
                }
                Object currentIdentity = invokeAnyMethod(
                        windowElement, "getAnimSymbol", new Object[0]);
                String currentType = readNativeAnimationType(windowElement);
                boolean surfaceCanceled = Boolean.TRUE.equals(
                        readField(windowElement, "mSurfaceCanceled"));
                boolean surfaceCancelExecuted = Boolean.TRUE.equals(
                        readField(windowElement, "mSurfaceCanceledExecute"));
                boolean canceled = Boolean.TRUE.equals(
                        readField(windowElement, "mCanceled"));
                boolean listenerDisabled = Boolean.TRUE.equals(
                        readField(windowElement, "mDisableStateManagerListener"));
                boolean nativeCallbackConsumed =
                        readField(windowElement, "mShellTransitionCallback") == null;
                Object oldListObject = readField(
                        stateManager, "windowElementOldList");
                boolean oldElementRecorded = oldListObject instanceof List<?>
                        && ((List<?>) oldListObject).contains(windowElement);
                boolean verifiedClose = session.nativeAnimationStarted
                        && session.nativeContinuationVerified
                        && session.nativeAnimationIdentity
                        == token.animationIdentity
                        && session.nativeAnimationType.equals(currentType)
                        && isReturnHomeNativeCloseType(currentType);
                UnifiedNativePendingInterruptionSnapshot
                        pendingCommitInterruption =
                        token.pendingCommitInterruption;
                boolean pendingCommit = pendingCommitInterruption != null
                        && !session.nativeAnimationStarted
                        && isExactUnifiedPendingInterruption(
                        session, pendingCommitInterruption,
                        currentElement, currentIdentity,
                        currentType, false);
                UnifiedNativeFinishSnapshot finishSnapshot =
                        session.unifiedNativeFinishSnapshot.get();
                boolean exactInterruptedFinish = verifiedClose
                        && finishSnapshot != null
                        && finishSnapshot.phase.get()
                        == UnifiedNativeFinishSnapshot.PHASE_PENDING
                        && finishSnapshot.animationIdentity
                        == token.animationIdentity
                        && currentType.equals(finishSnapshot.actualType)
                        && isExactAdoptedNativeCloseFinishSnapshot(
                        session, finishSnapshot);
                boolean replacementElementValid = currentElement != null
                        && currentElement != windowElement
                        && isMiuiHomeLauncherOpenType(replacementType);
                boolean pendingCommitValid = pendingCommit
                        && currentElement == windowElement
                        && currentIdentity == token.animationIdentity;
                boolean earlySetToOldValid = !pendingCommit
                        && verifiedClose
                        && currentElement == windowElement
                        && currentIdentity == token.animationIdentity
                        && oldElementRecorded;
                boolean completedReplacementValid = !pendingCommit
                        && exactInterruptedFinish
                        && oldElementRecorded
                        && replacementElementValid;
                boolean valid = currentSession == session
                        && session.finished.get() == 0
                        && session.generation == token.generation
                        && session.stateManager == stateManager
                        && session.nativeWindowElement == windowElement
                        && session.nativeAnimationIdentity == token.animationIdentity
                        && currentIdentity == token.animationIdentity
                        && (pendingCommitValid || earlySetToOldValid
                        || completedReplacementValid)
                        && surfaceCanceled && surfaceCancelExecuted && canceled
                        && nativeCallbackConsumed;
                if (!valid) {
                    pendingCloseInterruption.compareAndSet(token, null);
                    log(Log.WARN, TAG,
                            "Rejected interrupted return-home completion boundary"
                                    + ", generation=" + session.generation
                                    + ", currentSession=" + (currentSession == session)
                                    + ", finished=" + session.finished.get()
                                    + ", sameElement=" + (currentElement == windowElement)
                                    + ", replacementType=" + replacementType
                                    + ", sameIdentity="
                                    + (currentIdentity == token.animationIdentity)
                                    + ", surfaceCanceled=" + surfaceCanceled
                                    + ", surfaceCancelExecuted="
                                    + surfaceCancelExecuted
                                    + ", canceled=" + canceled
                                    + ", listenerDisabled=" + listenerDisabled
                                    + ", nativeCallbackConsumed="
                                    + nativeCallbackConsumed
                                    + ", oldElementRecorded="
                                    + oldElementRecorded
                                    + ", exactInterruptedFinish="
                                    + exactInterruptedFinish
                                    + ", earlySetToOldValid="
                                    + earlySetToOldValid
                                    + ", type=" + currentType
                                    + ", verifiedClose=" + verifiedClose
                                    + ", pendingCommit=" + pendingCommit);
                    return;
                }
                if (!pendingCloseInterruption.compareAndSet(token, null)) {
                    return;
                }
                if (pendingCommit
                        && !consumeUnifiedPendingInterruption(
                        session, pendingCommitInterruption,
                        "cancelSurfaceSetToOld")) {
                    return;
                }
                if (!pendingCommit && !earlySetToOldValid) {
                    if (finishSnapshot == null
                            || !finishSnapshot.phase.compareAndSet(
                            UnifiedNativeFinishSnapshot.PHASE_PENDING,
                            UnifiedNativeFinishSnapshot.PHASE_CONSUMED)) {
                        log(Log.WARN, TAG,
                                "Lost interrupted Xiaomi finish snapshot race"
                                        + ", generation="
                                        + session.generation
                                        + ", replacementType="
                                        + replacementType);
                        return;
                    }
                    session.unifiedNativeCommitEndObserved = true;
                    session.unifiedNativeCommitPending = false;
                    completeUnifiedNativeCommitHandoff(
                            session, token.animationIdentity, currentType);
                } else if (earlySetToOldValid) {
                    // Xiaomi has cancelled the exact old application Surface, consumed its
                    // Shell callback, and accepted the WindowElement into the old list. With
                    // the complete native provider this boundary can precede the old CLOSE
                    // finish callback; waiting for that callback lets the replacement OPEN
                    // become current and strands the old Shell runner.
                    session.unifiedNativeCommitPending = false;
                }
                log(Log.INFO, TAG,
                        "Accepted interrupted Xiaomi return-home completion boundary"
                                + ", generation=" + session.generation
                                + ", type=" + currentType
                                + ", pendingCommit=" + pendingCommit
                                + ", earlySetToOld="
                                + earlySetToOldValid
                                + ", replacementType=" + replacementType
                                + ", oldElementRecorded="
                                + oldElementRecorded
                                + ", animationIdentity="
                                + shortObject(token.animationIdentity)
                                + ", windowElement=" + shortObject(windowElement));
                if (session.unifiedNativePreviewOwned
                        && !pendingCommit) {
                    session.unifiedNativeCleanupVerified = true;
                }
                armFreshOpenAfterSameIconClose(
                        session, stateManager, windowElement,
                        token.animationIdentity, currentType);
                // Xiaomi has completed the exact old CLOSE callback, while its outer icon-click
                // callback has not yet created or requested the new FastLaunch OPEN. Release the
                // prepared Shell transition here so its finish transaction cannot land on top of
                // that new OPEN. The old surface is already cancelled, so never restore preview.
                finishSession(session, "nativeCloseInterruptedForLauncherOpen", false);
            } catch (Throwable throwable) {
                pendingCloseInterruption.compareAndSet(token, null);
                log(Log.WARN, TAG,
                        "Failed to verify interrupted return-home completion boundary"
                                + ", generation=" + session.generation, throwable);
            }
        }

        protected void armFreshOpenAfterSameIconClose(
                ReturnHomeSession session, Object stateManager,
                Object windowElement, Object animationIdentity,
                String currentType) {
            if (Looper.myLooper() != Looper.getMainLooper()
                    || currentSession != session
                    || session.finished.get() != 0
                    || session.stateManager != stateManager
                    || session.nativeWindowElement != windowElement
                    || session.nativeAnimationIdentity != animationIdentity
                    || !"CLOSE_TO_HOME".equals(currentType)) {
                return;
            }
            try {
                Object currentElement = invokeAnyMethod(
                        stateManager, "getCurrentWindowElement", new Object[0]);
                Object currentIdentity = invokeAnyMethod(
                        windowElement, "getAnimSymbol", new Object[0]);
                Object launcherTarget = invokeAnyMethod(
                        windowElement, "getLauncherTargetView", new Object[0]);
                Object pendingReference = invokeAnyMethod(
                        stateManager, "getPendingIconViewWeakRef", new Object[0]);
                Object pendingIcon = pendingReference instanceof WeakReference<?>
                        ? ((WeakReference<?>) pendingReference).get() : null;
                Object oldListObject = readField(
                        stateManager, "windowElementOldList");
                boolean oldElementRecorded = oldListObject instanceof List<?>
                        && ((List<?>) oldListObject).contains(windowElement);
                boolean hasRecentTransition = Boolean.TRUE.equals(
                        invokeAnyMethod(windowElement,
                                "hasRecentTransition", new Object[0]));
                boolean reusable = Boolean.TRUE.equals(
                        invokeAnyMethod(windowElement,
                                "isReusefulAnimRunning", new Object[0]));
                boolean surfaceCanceled = Boolean.TRUE.equals(
                        readField(windowElement, "mSurfaceCanceled"));
                boolean surfaceCancelExecuted = Boolean.TRUE.equals(
                        readField(windowElement, "mSurfaceCanceledExecute"));
                boolean canceled = Boolean.TRUE.equals(
                        readField(windowElement, "mCanceled"));
                boolean listenerDisabled = Boolean.TRUE.equals(
                        readField(windowElement, "mDisableStateManagerListener"));
                boolean valid = currentElement == windowElement
                        && currentIdentity == animationIdentity
                        && pendingIcon instanceof View
                        && launcherTarget == pendingIcon
                        && oldElementRecorded
                        && !hasRecentTransition && !reusable
                        && surfaceCanceled && surfaceCancelExecuted
                        && canceled;
                if (!valid) {
                    log(Log.INFO, TAG,
                            "Preserved Xiaomi old-element OPEN selection"
                                    + ", generation=" + session.generation
                                    + ", sameElement="
                                    + (currentElement == windowElement)
                                    + ", sameIdentity="
                                    + (currentIdentity == animationIdentity)
                                    + ", sameClickedView="
                                    + (launcherTarget == pendingIcon)
                                    + ", oldElementRecorded="
                                    + oldElementRecorded
                                    + ", hasRecentTransition="
                                    + hasRecentTransition
                                    + ", reusable=" + reusable
                                    + ", surfaceCanceled="
                                    + surfaceCanceled
                                    + ", surfaceCancelExecuted="
                                    + surfaceCancelExecuted
                                    + ", canceled=" + canceled
                                    + ", listenerDisabled="
                                    + listenerDisabled);
                    return;
                }
                ReturnHomeFreshOpenToken freshOpen =
                        new ReturnHomeFreshOpenToken(
                                session, stateManager, windowElement,
                                animationIdentity, (View) pendingIcon);
                ReturnHomeFreshOpenToken replaced =
                        pendingFreshOpen.getAndSet(freshOpen);
                if (replaced != null) {
                    log(Log.WARN, TAG,
                            "Replaced stale Xiaomi fresh-OPEN token"
                                    + ", oldGeneration="
                                    + replaced.generation
                                    + ", newGeneration="
                                    + freshOpen.generation);
                }
                handler.post(() -> expirePendingFreshOpen(freshOpen));
                log(Log.INFO, TAG,
                        "Armed Xiaomi fresh OPEN after non-reusable same-icon CLOSE"
                                + ", generation=" + session.generation
                                + ", type=" + currentType
                                + ", animationIdentity="
                                + shortObject(animationIdentity)
                                + ", windowElement="
                                + shortObject(windowElement)
                                + ", clickedView="
                                + shortObject(pendingIcon));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to arm Xiaomi same-icon fresh OPEN"
                                + ", generation=" + session.generation,
                        throwable);
            }
        }

        boolean shouldForceFreshOpenAfterSameIconClose(
                Object stateManager, Object oldWindowElement,
                Object clickedView) throws Throwable {
            ReturnHomeFreshOpenToken token = pendingFreshOpen.get();
            if (token == null || Looper.myLooper() != Looper.getMainLooper()
                    || token.stateManager != stateManager
                    || token.windowElement != oldWindowElement
                    || token.clickedView != clickedView) {
                return false;
            }
            Object currentElement = invokeAnyMethod(
                    stateManager, "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    oldWindowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(oldWindowElement);
            Object launcherTarget = invokeAnyMethod(
                    oldWindowElement, "getLauncherTargetView", new Object[0]);
            Object oldListObject = readField(
                    stateManager, "windowElementOldList");
            boolean oldElementRecorded = oldListObject instanceof List<?>
                    && ((List<?>) oldListObject).contains(oldWindowElement);
            boolean hasRecentTransition = Boolean.TRUE.equals(
                    invokeAnyMethod(oldWindowElement,
                            "hasRecentTransition", new Object[0]));
            boolean reusable = Boolean.TRUE.equals(
                    invokeAnyMethod(oldWindowElement,
                            "isReusefulAnimRunning", new Object[0]));
            boolean surfaceCanceled = Boolean.TRUE.equals(
                    readField(oldWindowElement, "mSurfaceCanceled"));
            boolean surfaceCancelExecuted = Boolean.TRUE.equals(
                    readField(oldWindowElement, "mSurfaceCanceledExecute"));
            boolean canceled = Boolean.TRUE.equals(
                    readField(oldWindowElement, "mCanceled"));
            boolean listenerDisabled = Boolean.TRUE.equals(
                    readField(oldWindowElement,
                            "mDisableStateManagerListener"));
            boolean valid = pendingFreshOpen.get() == token
                    && currentElement == oldWindowElement
                    && currentIdentity == token.animationIdentity
                    && "CLOSE_TO_HOME".equals(currentType)
                    && launcherTarget == clickedView
                    && oldElementRecorded
                    && !hasRecentTransition && !reusable
                    && surfaceCanceled && surfaceCancelExecuted
                    && canceled;
            if (!valid) {
                pendingFreshOpen.compareAndSet(token, null);
                log(Log.WARN, TAG,
                        "Rejected stale Xiaomi fresh-OPEN token"
                                + ", generation=" + token.generation
                                + ", sameElement="
                                + (currentElement == oldWindowElement)
                                + ", sameIdentity="
                                + (currentIdentity == token.animationIdentity)
                                + ", type=" + currentType
                                + ", sameClickedView="
                                + (launcherTarget == clickedView)
                                + ", oldElementRecorded="
                                + oldElementRecorded
                                + ", hasRecentTransition="
                                + hasRecentTransition
                                + ", reusable=" + reusable
                                + ", surfaceCanceled="
                                + surfaceCanceled
                                + ", surfaceCancelExecuted="
                                + surfaceCancelExecuted
                                + ", canceled=" + canceled
                                + ", listenerDisabled="
                                + listenerDisabled);
                return false;
            }
            int invocation = token.invocations.incrementAndGet();
            log(Log.INFO, TAG,
                    "Forced Xiaomi fresh OPEN for non-reusable same-icon CLOSE"
                            + ", generation=" + token.generation
                            + ", invocation=" + invocation
                            + ", animationIdentity="
                            + shortObject(token.animationIdentity)
                            + ", windowElement="
                            + shortObject(token.windowElement)
                            + ", clickedView="
                            + shortObject(token.clickedView));
            return true;
        }

        protected void expirePendingFreshOpen(ReturnHomeFreshOpenToken token) {
            if (pendingFreshOpen.compareAndSet(token, null)) {
                log(Log.INFO, TAG,
                        "Expired Xiaomi same-icon fresh-OPEN token"
                                + ", generation=" + token.generation
                                + ", invocations="
                                + token.invocations.get());
            }
        }

        protected void invalidatePendingFreshOpen(String reason) {
            ReturnHomeFreshOpenToken token = pendingFreshOpen.getAndSet(null);
            if (token != null) {
                log(Log.INFO, TAG,
                        "Invalidated Xiaomi same-icon fresh-OPEN token"
                                + ", generation=" + token.generation
                                + ", invocations="
                                + token.invocations.get()
                                + ", reason=" + reason);
            }
        }

        ReturnHomeDirectCancelToken prepareNativeDirectCancel(
                Object windowElement, Object[] args) throws Throwable {
            return prepareNativeDirectCancel(
                    windowElement, args, false);
        }

        protected ReturnHomeDirectCancelToken prepareNativeDirectCancel(
                Object windowElement, Object[] args,
                boolean configLocked) throws Throwable {
            if (args == null || args.length != 5
                    || !MIUI_HOME_ICON_CLICK_WITHOUT_RECENT_REASON.equals(args[0])
                    || !Boolean.FALSE.equals(args[1])
                    || args[2] == null || args[3] != null || args[4] != null) {
                return null;
            }
            Class<?> callbackClass = Class.forName(
                    MIUI_HOME_SHELL_TRANSITION_CALLBACK, false, classLoader);
            Object originalCallback = args[2];
            if (!callbackClass.isInstance(originalCallback)) {
                return null;
            }
            ReturnHomeSession session = currentSession;
            adoptConfiguredCommitForInterruption(
                    session, windowElement, "directCancel");
            UnifiedNativePendingInterruptionSnapshot earlyPending =
                    session == null ? null
                            : session.unifiedNativePendingInterruption.get();
            if (!configLocked && session != null
                    && !session.nativeAnimationStarted
                    && earlyPending != null) {
                synchronized (earlyPending.configLock) {
                    return prepareNativeDirectCancel(
                            windowElement, args, true);
                }
            }
            if (session == null || session.finished.get() != 0
                    || !session.nativeHandoffStarted
                    || session.stateManager == null
                    || session.nativeWindowElement != windowElement
                    || session.nativeAnimationIdentity == null) {
                return null;
            }

            Object stateManager = session.stateManager;
            Object currentElement = invokeAnyMethod(
                    stateManager, "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(windowElement);
            Object pendingReference = invokeAnyMethod(
                    stateManager, "getPendingIconViewWeakRef", new Object[0]);
            Object pendingIcon = pendingReference instanceof WeakReference<?>
                    ? ((WeakReference<?>) pendingReference).get() : null;
            boolean samePendingIcon = pendingIcon instanceof View
                    && Boolean.TRUE.equals(invokeAnyMethod(windowElement,
                    "isSameElement", new Object[]{pendingIcon}));
            boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isAnimRunning", new Object[0]));
            boolean reusable = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isReusefulAnimRunning", new Object[0]));
            boolean mainAnimPending = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "mainAnimNoFinishClear", new Object[0]));
            boolean validSurface = Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "hasValidSurface", new Object[0]));
            boolean canceled = Boolean.TRUE.equals(
                    readField(windowElement, "mCanceled"));
            boolean surfaceCanceled = Boolean.TRUE.equals(
                    readField(windowElement, "mSurfaceCanceled"));
            boolean surfaceCancelExecuted = Boolean.TRUE.equals(
                    readField(windowElement, "mSurfaceCanceledExecute"));
            boolean listenerDisabled = Boolean.TRUE.equals(
                    readField(windowElement, "mDisableStateManagerListener"));
            boolean useShellListener = Boolean.TRUE.equals(
                    readField(windowElement, "mUseShellAnimListener"));
            boolean couldExecuteShellEnd = Boolean.TRUE.equals(
                    readField(windowElement, "couldExecuteShellAnimEnd"));
            boolean callbackClear =
                    readField(windowElement, "mShellTransitionCallback") == null;
            boolean verifiedClose = session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && currentIdentity
                    == session.nativeAnimationIdentity
                    && session.nativeAnimationType.equals(currentType)
                    && isReturnHomeNativeCloseType(currentType);
            UnifiedNativePendingInterruptionSnapshot
                    pendingCommitInterruption =
                    session.unifiedNativePendingInterruption.get();
            boolean pendingCommit = !session.nativeAnimationStarted
                    && isExactUnifiedPendingInterruption(
                    session, pendingCommitInterruption,
                    currentElement, currentIdentity,
                    currentType, true);
            boolean valid = currentSession == session
                    && session.generation > 0L
                    && session.stateManager == stateManager
                    && currentElement == windowElement
                    && currentIdentity == session.nativeAnimationIdentity
                    && (verifiedClose || pendingCommit)
                    && samePendingIcon && running && !reusable
                    && mainAnimPending && validSurface
                    && !canceled && !surfaceCanceled && !surfaceCancelExecuted
                    && (pendingCommit
                    ? listenerDisabled : !listenerDisabled)
                    && useShellListener
                    && couldExecuteShellEnd && callbackClear;
            if (!valid) {
                log(Log.WARN, TAG,
                        "Rejected direct same-icon Xiaomi CLOSE handoff"
                                + ", generation=" + session.generation
                                + ", currentSession=" + (currentSession == session)
                                + ", sameElement=" + (currentElement == windowElement)
                                + ", sameIdentity="
                                + (currentIdentity == session.nativeAnimationIdentity)
                                + ", type=" + currentType
                                + ", expectedType=" + session.nativeAnimationType
                                + ", verifiedClose=" + verifiedClose
                                + ", pendingCommit=" + pendingCommit
                                + ", samePendingIcon=" + samePendingIcon
                                + ", running=" + running
                                + ", reusable=" + reusable
                                + ", mainAnimPending=" + mainAnimPending
                                + ", validSurface=" + validSurface
                                + ", canceled=" + canceled
                                + ", surfaceCanceled=" + surfaceCanceled
                                + ", surfaceCancelExecuted="
                                + surfaceCancelExecuted
                                + ", listenerDisabled=" + listenerDisabled
                                + ", useShellListener=" + useShellListener
                                + ", couldExecuteShellEnd="
                                + couldExecuteShellEnd
                                + ", callbackClear=" + callbackClear);
                return null;
            }
            if (pendingCommit) {
                synchronized (pendingCommitInterruption.configLock) {
                    adoptConfiguredCommitForInterruption(
                            currentSession, windowElement,
                            "directCancelLocked");
                    if (currentSession == session
                            && session.nativeAnimationStarted) {
                        return prepareNativeDirectCancel(
                                windowElement, args, true);
                    }
                    Object lockedElement = invokeAnyMethod(
                            stateManager, "getCurrentWindowElement",
                            new Object[0]);
                    Object lockedIdentity = invokeAnyMethod(
                            windowElement, "getAnimSymbol",
                            new Object[0]);
                    String lockedType = readNativeAnimationType(windowElement);
                    int mutation =
                            pendingCommitInterruption.mutation.get();
                    if (!isExactUnifiedPendingInterruption(
                            session, pendingCommitInterruption,
                            lockedElement, lockedIdentity,
                            lockedType, true)
                            || (mutation
                            != UnifiedNativePendingInterruptionSnapshot
                            .MUTATION_DIRECT_CANCEL
                            && !pendingCommitInterruption.mutation
                            .compareAndSet(
                                    UnifiedNativePendingInterruptionSnapshot
                                    .MUTATION_NONE,
                                    UnifiedNativePendingInterruptionSnapshot
                                    .MUTATION_DIRECT_CANCEL))) {
                        return null;
                    }
                }
            }

            ReturnHomeDirectCancelToken token =
                    new ReturnHomeDirectCancelToken(session, stateManager,
                            windowElement, session.nativeAnimationIdentity,
                            pendingIcon, originalCallback,
                            pendingCommit
                                    ? pendingCommitInterruption
                                    : null);
            Object wrappedCallback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, invocationArgs) ->
                            invokeNativeDirectCancelCallback(
                                    token, proxy, method, invocationArgs));
            token.wrappedCallback = wrappedCallback;
            if (!pendingDirectCancel.compareAndSet(null, token)) {
                log(Log.WARN, TAG,
                        "Rejected overlapping direct same-icon Xiaomi CLOSE handoff"
                                + ", generation=" + session.generation);
                return null;
            }
            Runnable cleanupGuard = () -> {
                int phase = token.phase.get();
                if (phase == ReturnHomeDirectCancelToken.PHASE_PENDING) {
                    invalidateNativeDirectCancel(
                            token, "callbackTimeout", false);
                } else if (phase
                        == ReturnHomeDirectCancelToken.PHASE_FINISHED_NOTIFIED) {
                    cleanupNativeDirectCancel(
                            token, "directCancelCleanupGuard");
                }
            };
            token.cleanupGuard = cleanupGuard;
            handler.postDelayed(cleanupGuard,
                    RETURN_HOME_DIRECT_CANCEL_CLEANUP_GUARD_MS);
            log(Log.INFO, TAG,
                    "Prepared direct same-icon Xiaomi CLOSE handoff"
                            + ", generation=" + session.generation
                            + ", type=" + currentType
                            + ", pendingCommit=" + pendingCommit
                            + ", animationIdentity="
                            + shortObject(token.animationIdentity)
                            + ", windowElement=" + shortObject(windowElement)
                            + ", pendingIcon=" + shortObject(pendingIcon));
            return token;
        }

        protected Object invokeNativeDirectCancelCallback(
                ReturnHomeDirectCancelToken token, Object proxy,
                Method method, Object[] invocationArgs) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(method.getName())) {
                    return "PredictiveReturnHomeDirectCancelCallback{"
                            + shortObject(token.originalCallback) + "}";
                }
                if ("hashCode".equals(method.getName())) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(method.getName())) {
                    return Boolean.valueOf(invocationArgs != null
                            && invocationArgs.length == 1
                            && proxy == invocationArgs[0]);
                }
            }
            if ("onFinish".equals(method.getName())
                    && method.getParameterCount() == 0) {
                try {
                    acceptNativeDirectCancelCallback(token);
                } catch (Throwable throwable) {
                    invalidateNativeDirectCancel(
                            token, "callbackVerificationFailed", false);
                    log(Log.WARN, TAG,
                            "Failed direct same-icon Xiaomi CLOSE callback boundary"
                                    + ", generation=" + token.generation,
                            throwable);
                }
            }
            try {
                return method.invoke(token.originalCallback, invocationArgs);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                throw cause == null ? exception : cause;
            }
        }

        protected void acceptNativeDirectCancelCallback(
                ReturnHomeDirectCancelToken token) throws Throwable {
            if (pendingDirectCancel.get() != token
                    || token.phase.get()
                    != ReturnHomeDirectCancelToken.PHASE_PENDING) {
                return;
            }
            ReturnHomeSession session = token.session;
            Object currentElement = invokeAnyMethod(
                    token.stateManager, "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    token.windowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(token.windowElement);
            Object pendingReference = invokeAnyMethod(
                    token.stateManager, "getPendingIconViewWeakRef", new Object[0]);
            Object pendingIcon = pendingReference instanceof WeakReference<?>
                    ? ((WeakReference<?>) pendingReference).get() : null;
            boolean samePendingIcon = pendingIcon == token.pendingIcon
                    && pendingIcon instanceof View
                    && Boolean.TRUE.equals(invokeAnyMethod(token.windowElement,
                    "isSameElement", new Object[]{pendingIcon}));
            boolean canceled = Boolean.TRUE.equals(
                    readField(token.windowElement, "mCanceled"));
            boolean surfaceCanceled = Boolean.TRUE.equals(
                    readField(token.windowElement, "mSurfaceCanceled"));
            boolean surfaceCancelExecuted = Boolean.TRUE.equals(
                    readField(token.windowElement, "mSurfaceCanceledExecute"));
            boolean listenerDisabled = Boolean.TRUE.equals(
                    readField(token.windowElement,
                            "mDisableStateManagerListener"));
            boolean useShellListener = Boolean.TRUE.equals(
                    readField(token.windowElement, "mUseShellAnimListener"));
            boolean couldExecuteShellEnd = Boolean.TRUE.equals(
                    readField(token.windowElement, "couldExecuteShellAnimEnd"));
            boolean callbackConsumed =
                    readField(token.windowElement,
                            "mShellTransitionCallback") == null;
            boolean validSurface = Boolean.TRUE.equals(invokeAnyMethod(
                    token.windowElement, "hasValidSurface", new Object[0]));
            boolean verifiedClose = session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && session.nativeAnimationIdentity
                    == token.animationIdentity
                    && session.nativeAnimationType.equals(currentType)
                    && isReturnHomeNativeCloseType(currentType);
            UnifiedNativePendingInterruptionSnapshot
                    pendingCommitInterruption =
                    token.pendingCommitInterruption;
            boolean pendingCommit = pendingCommitInterruption != null
                    && !session.nativeAnimationStarted
                    && isExactUnifiedPendingInterruption(
                    session, pendingCommitInterruption,
                    currentElement, currentIdentity,
                    currentType, false);
            boolean valid = currentSession == session
                    && session.finished.get() == 0
                    && session.generation == token.generation
                    && session.stateManager == token.stateManager
                    && session.nativeWindowElement == token.windowElement
                    && session.nativeAnimationIdentity == token.animationIdentity
                    && currentElement == token.windowElement
                    && currentIdentity == token.animationIdentity
                    && (verifiedClose || pendingCommit)
                    && samePendingIcon && canceled
                    && !surfaceCanceled && !surfaceCancelExecuted
                    && !listenerDisabled && !useShellListener
                    && !couldExecuteShellEnd && callbackConsumed
                    && validSurface;
            if (!valid) {
                invalidateNativeDirectCancel(token,
                        "callbackStateMismatch", false);
                log(Log.WARN, TAG,
                        "Rejected direct same-icon Xiaomi CLOSE callback boundary"
                                + ", generation=" + session.generation
                                + ", currentSession=" + (currentSession == session)
                                + ", finished=" + session.finished.get()
                                + ", sameElement="
                                + (currentElement == token.windowElement)
                                + ", sameIdentity="
                                + (currentIdentity == token.animationIdentity)
                                + ", type=" + currentType
                                + ", verifiedClose=" + verifiedClose
                                + ", pendingCommit=" + pendingCommit
                                + ", samePendingIcon=" + samePendingIcon
                                + ", canceled=" + canceled
                                + ", surfaceCanceled=" + surfaceCanceled
                                + ", surfaceCancelExecuted="
                                + surfaceCancelExecuted
                                + ", listenerDisabled=" + listenerDisabled
                                + ", useShellListener=" + useShellListener
                                + ", couldExecuteShellEnd="
                                + couldExecuteShellEnd
                                + ", callbackConsumed=" + callbackConsumed
                                + ", validSurface=" + validSurface);
                return;
            }
            if (!token.phase.compareAndSet(
                    ReturnHomeDirectCancelToken.PHASE_PENDING,
                    ReturnHomeDirectCancelToken.PHASE_FINISHED_NOTIFIED)) {
                return;
            }
            if (pendingCommit
                    && !consumeUnifiedPendingInterruption(
                    session, pendingCommitInterruption,
                    "directCancelCallback")) {
                token.phase.set(ReturnHomeDirectCancelToken.PHASE_CLEANED);
                pendingDirectCancel.compareAndSet(token, null);
                return;
            }
            if (session.unifiedNativePreviewOwned
                    && !pendingCommit) {
                session.unifiedNativeCleanupVerified = true;
            }
            if (!session.finished.compareAndSet(0, 1)) {
                token.phase.set(ReturnHomeDirectCancelToken.PHASE_CLEANED);
                pendingDirectCancel.compareAndSet(token, null);
                Runnable guard = token.cleanupGuard;
                if (guard != null) {
                    handler.removeCallbacks(guard);
                }
                return;
            }
            freezePreviewProgress(session, "directSameIconCancel");
            invalidatePendingCloseInterruption(
                    session, "directSameIconCancel");
            Runnable timeout = session.nativeTimeout;
            if (timeout != null) {
                handler.removeCallbacks(timeout);
            }
            Runnable cleanupGuard = token.cleanupGuard;
            if (cleanupGuard != null) {
                // The pending callback timeout starts at hook preparation. Once Xiaomi
                // consumes the callback, restart the full cleanup guard from this verified
                // Shell-finish boundary so a late callback cannot shorten target lifetime.
                handler.removeCallbacks(cleanupGuard);
                handler.postDelayed(cleanupGuard,
                        RETURN_HOME_DIRECT_CANCEL_CLEANUP_GUARD_MS);
            }
            notifyRemoteAnimationFinished(session.finishedCallback,
                    "nativeDirectCancelBeforeLauncherOpen");
            log(Log.INFO, TAG,
                    "Finished Shell runner before direct same-icon Xiaomi OPEN"
                            + ", generation=" + session.generation
                            + ", type=" + currentType
                            + ", animationIdentity="
                            + shortObject(token.animationIdentity)
                            + ", windowElement="
                            + shortObject(token.windowElement));
        }

        void invalidateNativeDirectCancel(ReturnHomeDirectCancelToken token,
                                          String reason,
                                          boolean cleanupAccepted) {
            if (token == null) {
                return;
            }
            int phase = token.phase.get();
            if (phase == ReturnHomeDirectCancelToken.PHASE_FINISHED_NOTIFIED) {
                if (cleanupAccepted) {
                    cleanupNativeDirectCancel(token, reason);
                }
                return;
            }
            if (phase != ReturnHomeDirectCancelToken.PHASE_PENDING
                    || !token.phase.compareAndSet(
                    ReturnHomeDirectCancelToken.PHASE_PENDING,
                    ReturnHomeDirectCancelToken.PHASE_CLEANED)) {
                return;
            }
            pendingDirectCancel.compareAndSet(token, null);
            Runnable guard = token.cleanupGuard;
            if (guard != null) {
                handler.removeCallbacks(guard);
            }
            log(Log.INFO, TAG,
                    "Invalidated direct same-icon Xiaomi CLOSE handoff"
                            + ", generation=" + token.generation
                            + ", reason=" + reason);
        }

        protected void invalidatePendingDirectCancel(
                ReturnHomeSession session, String reason,
                boolean cleanupAccepted) {
            ReturnHomeDirectCancelToken token = pendingDirectCancel.get();
            if (token == null
                    || (session != null && token.session != session)) {
                return;
            }
            invalidateNativeDirectCancel(token, reason, cleanupAccepted);
        }

        protected void cleanupNativeDirectCancel(
                ReturnHomeDirectCancelToken token, String reason) {
            if (token == null || !token.phase.compareAndSet(
                    ReturnHomeDirectCancelToken.PHASE_FINISHED_NOTIFIED,
                    ReturnHomeDirectCancelToken.PHASE_CLEANED)) {
                return;
            }
            pendingDirectCancel.compareAndSet(token, null);
            Runnable guard = token.cleanupGuard;
            if (guard != null) {
                handler.removeCallbacks(guard);
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanupFinishedSession(token.session, reason, false);
            } else {
                handler.post(() -> cleanupFinishedSession(
                        token.session, reason, false));
            }
        }

        protected void finishNativeDirectCancelOnAnimationEnd(
                Object listener, Object animationIdentity) {
            ReturnHomeDirectCancelToken token = pendingDirectCancel.get();
            if (token == null
                    || token.phase.get()
                    != ReturnHomeDirectCancelToken.PHASE_FINISHED_NOTIFIED
                    || token.animationIdentity != animationIdentity) {
                return;
            }
            try {
                Object callbackStateManager = readField(listener, "this$0");
                Object currentIdentity = invokeAnyMethod(
                        token.windowElement, "getAnimSymbol", new Object[0]);
                boolean pendingCommit =
                        token.pendingCommitInterruption != null
                                && token.pendingCommitInterruption.phase.get()
                                == UnifiedNativePendingInterruptionSnapshot
                                .PHASE_CONSUMED
                                && token.pendingCommitInterruption.animToEpoch
                                == token.session
                                .unifiedNativeActiveAnimToEpoch;
                boolean valid = token.session.finished.get() == 1
                        && token.session.stateManager == token.stateManager
                        && token.session.nativeWindowElement == token.windowElement
                        && token.session.nativeAnimationIdentity
                        == token.animationIdentity
                        && callbackStateManager == token.stateManager
                        && currentIdentity == token.animationIdentity
                        && (pendingCommit
                        || isReturnHomeNativeCloseType(
                        token.session.nativeAnimationType));
                if (!valid) {
                    log(Log.WARN, TAG,
                            "Rejected direct same-icon Xiaomi CLOSE end"
                                    + ", generation=" + token.generation
                                    + ", finished="
                                    + token.session.finished.get()
                                    + ", sameStateManager="
                                    + (callbackStateManager
                                    == token.stateManager)
                                    + ", sameIdentity="
                                    + (currentIdentity
                                    == token.animationIdentity)
                                    + ", pendingCommit=" + pendingCommit
                                    + ", type="
                                    + token.session.nativeAnimationType);
                    return;
                }
                log(Log.INFO, TAG,
                        "Accepted direct same-icon Xiaomi CLOSE end"
                                + ", generation=" + token.generation
                                + ", type="
                                + token.session.nativeAnimationType
                                + ", animationIdentity="
                                + shortObject(token.animationIdentity));
                handler.post(() -> cleanupNativeDirectCancel(
                        token, "nativeDirectCancelAnimationEnd"));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to verify direct same-icon Xiaomi CLOSE end"
                                + ", generation=" + token.generation,
                        throwable);
            }
        }

        protected boolean isReturnHomeNativeCloseType(String typeName) {
            return "CLOSE_TO_HOME".equals(typeName)
                    || "CLOSE_TO_HOME_CENTER".equals(typeName)
                    || "CLOSE_TO_ELEMENT".equals(typeName);
        }

        protected void invalidatePendingCloseInterruption(
                ReturnHomeSession session, String reason) {
            while (true) {
                ReturnHomeCloseInterruptionToken token =
                        pendingCloseInterruption.get();
                if (token == null || (session != null && token.session != session)) {
                    return;
                }
                if (pendingCloseInterruption.compareAndSet(token, null)) {
                    log(Log.INFO, TAG,
                            "Invalidated interrupted return-home token"
                                    + ", generation=" + token.generation
                                    + ", reason=" + reason);
                    return;
                }
            }
        }

        protected void verifyNativeAnimationStarted(ReturnHomeSession session,
                                                  Object stateManager,
                                                  Object windowElement,
                                                  Object animationIdentity) {
            if (currentSession != session || session.finished.get() != 0
                    || session.nativeAnimationIdentity != animationIdentity) {
                return;
            }
            try {
                Object currentElement = invokeAnyMethod(stateManager,
                        "getCurrentWindowElement", new Object[0]);
                Object currentIdentity = invokeAnyMethod(windowElement,
                        "getAnimSymbol", new Object[0]);
                String typeName = readNativeAnimationType(windowElement);
                boolean closeToHome = isReturnHomeNativeCloseType(typeName);
                boolean running = Boolean.TRUE.equals(invokeAnyMethod(windowElement,
                        "isAnimRunning", new Object[0]));
                if (currentElement == windowElement
                        && currentIdentity == animationIdentity
                        && closeToHome && running) {
                    session.nativeAnimationStarted = true;
                    session.nativeAnimationType = typeName;
                    log(Log.INFO, TAG, "Verified Xiaomi predictive return post-commit"
                            + ", generation=" + session.generation
                            + ", type=" + typeName
                            + ", animationIdentity="
                            + shortObject(animationIdentity));
                } else {
                    log(Log.WARN, TAG, "Xiaomi CLOSE start did not settle as expected"
                            + ", generation=" + session.generation
                            + ", sameElement=" + (currentElement == windowElement)
                            + ", sameIdentity="
                            + (currentIdentity == animationIdentity)
                            + ", type=" + typeName
                            + ", running=" + running);
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to verify Xiaomi CLOSE animation start"
                        + ", generation=" + session.generation, throwable);
            }
        }

        void captureNativeAnimationEndBeforeListener(
                Object listener, Object animationIdentity) {
            ReturnHomeSession session = currentSession;
            if (session == null || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || animationIdentity
                    != session.unifiedNativeAnimationIdentity) {
                return;
            }
            UnifiedNativeFinishSnapshot snapshot =
                    captureUnifiedNativeFinishSnapshot(
                            session, listener, animationIdentity);
            UnifiedNativeFinishSnapshot previous;
            while (true) {
                previous = session.unifiedNativeFinishSnapshot.get();
                if (previous != null
                        && previous.phase.get()
                        == UnifiedNativeFinishSnapshot.PHASE_PENDING
                        && previous.animationIdentity == animationIdentity
                        && previous.animToEpoch == snapshot.animToEpoch
                        && isConsumableUnifiedNativeFinishSnapshot(
                        session, previous)) {
                    snapshot.phase.set(
                            UnifiedNativeFinishSnapshot.PHASE_INVALID);
                    log(Log.INFO, TAG,
                            "Preserved first exact Xiaomi finish state across duplicate StateManager end"
                                    + ", generation="
                                    + session.generation
                                    + ", type="
                                    + previous.actualType
                                    + ", animToEpoch="
                                    + previous.animToEpoch
                                    + ", duplicateSameElement="
                                    + (snapshot.currentElement
                                    == session.nativeWindowElement)
                                    + ", duplicateExactTarget="
                                    + snapshot.exactTarget);
                    return;
                }
                if (session.unifiedNativeFinishSnapshot.compareAndSet(
                        previous, snapshot)) {
                    break;
                }
            }
            if (previous != null && previous != snapshot) {
                previous.phase.compareAndSet(
                        UnifiedNativeFinishSnapshot.PHASE_PENDING,
                        UnifiedNativeFinishSnapshot.PHASE_INVALID);
            }
            log(Log.INFO, TAG,
                    "Captured Xiaomi finish state before StateManager listener"
                            + ", generation=" + session.generation
                            + ", type=" + snapshot.actualType
                            + ", sameStateManager="
                            + (snapshot.callbackStateManager
                            == session.stateManager)
                            + ", sameElement="
                            + (snapshot.currentElement
                            == session.nativeWindowElement)
                            + ", sameIdentity="
                            + (snapshot.currentAnimationIdentity
                            == session.unifiedNativeAnimationIdentity)
                            + ", exactTarget="
                            + snapshot.exactTarget
                            + ", running=" + snapshot.running
                            + ", finishComplete="
                            + snapshot.finishComplete
                            + ", fullscreen=" + snapshot.fullscreen,
                    snapshot.failure);
        }

        boolean onNativeAnimationEnd(
                Object listener, Object animationIdentity) {
            finishNativeDirectCancelOnAnimationEnd(listener, animationIdentity);
            ReturnHomeSession session = currentSession;
            if (session == null) {
                return false;
            }
            try {
                Object callbackStateManager = readField(listener, "this$0");
                if (session.finished.get() == 0
                        && session.unifiedNativePreviewOwned
                        && callbackStateManager == session.stateManager
                        && animationIdentity
                        == session.unifiedNativeAnimationIdentity) {
                    UnifiedNativeFinishSnapshot snapshot =
                            session.unifiedNativeFinishSnapshot.get();
                    if (snapshot == null
                            || snapshot.animationIdentity
                            != animationIdentity) {
                        log(Log.ERROR, TAG,
                                "Missing Xiaomi pre-listener finish snapshot; retained owner"
                                        + ", generation="
                                        + session.generation
                                        + ", animationIdentity="
                                        + shortObject(animationIdentity));
                        return true;
                    }
                    if (session.unifiedNativeCancelPending) {
                        session.unifiedNativeCancelEndObserved = true;
                        log(Log.INFO, TAG,
                                "Captured unified Xiaomi cancel finish before target clear"
                                        + ", generation="
                                        + session.generation
                                        + ", type="
                                        + snapshot.actualType
                                        + ", exactTarget="
                                        + snapshot.exactTarget
                                        + ", fullscreen="
                                        + snapshot.fullscreen
                                        + ", animationIdentity="
                                        + shortObject(animationIdentity));
                        handler.post(() ->
                                consumeUnifiedNativeFinishSnapshot(
                                        session,
                                        "unifiedCancelEnd"));
                        return true;
                    }
                    session.unifiedNativeCommitEndObserved = true;
                    log(Log.INFO, TAG,
                            "Captured unified Xiaomi commit finish before target clear"
                                    + ", generation="
                                    + session.generation
                                    + ", type="
                                    + snapshot.actualType
                                    + ", commitPending="
                                    + session.unifiedNativeCommitPending
                                    + ", nativeStarted="
                                    + session.nativeAnimationStarted
                                    + ", exactTarget="
                                    + snapshot.exactTarget
                                    + ", finishComplete="
                                    + snapshot.finishComplete
                                    + ", animationIdentity="
                                    + shortObject(animationIdentity));
                    handler.post(() ->
                            consumeUnifiedNativeFinishSnapshot(
                                    session,
                                    "unifiedNativeAnimationEnd"));
                    return true;
                }
                if (session.finished.get() != 0
                        || !session.nativeAnimationStarted
                        || session.nativeAnimationIdentity
                        != animationIdentity) {
                    return false;
                }
                Object currentIdentity = invokeAnyMethod(
                        session.nativeWindowElement, "getAnimSymbol", new Object[0]);
                String typeName = session.nativeAnimationType;
                boolean closeToHome = isReturnHomeNativeCloseType(typeName);
                if (callbackStateManager != session.stateManager
                        || currentIdentity != animationIdentity
                        || !closeToHome) {
                    log(Log.WARN, TAG, "Rejected mismatched Xiaomi CLOSE end"
                            + ", generation=" + session.generation
                            + ", sameStateManager="
                            + (callbackStateManager == session.stateManager)
                            + ", sameIdentity="
                            + (currentIdentity == animationIdentity)
                            + ", type=" + typeName);
                    return false;
                }
                log(Log.INFO, TAG, "Accepted Xiaomi predictive return end"
                        + ", generation=" + session.generation
                        + ", type=" + typeName
                        + ", animationIdentity=" + shortObject(animationIdentity));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to verify Xiaomi CLOSE animation end"
                        + ", generation=" + session.generation, throwable);
                return false;
            }
            // The hooked listener's original body may already replace StateManager's current
            // element before this after-hook runs. The stored WindowElement and exact animation
            // symbol still identify the completed CLOSE; queue once behind the remaining
            // listener cleanup before releasing Shell's prepared animation.
            handler.post(() -> {
                if (currentSession == session && session.finished.get() == 0
                        && session.nativeAnimationIdentity == animationIdentity) {
                    finishSession(session, "nativeAnimationEnd", false);
                }
            });
            return true;
        }

        protected void finishSession(ReturnHomeSession session, String reason,
                                   boolean restoreSurface) {
            if (session == null) {
                return;
            }
            if (session.unifiedNativePreviewOwned
                    && !session.unifiedNativeCleanupVerified) {
                if (!session.nativeHandoffStarted
                        && !session.unifiedNativeCommitPending
                        && !session.nativeAnimationStarted) {
                    startUnifiedNativeCancel(
                            session, "finish:" + reason);
                }
                log(Log.WARN, TAG,
                        "Deferred runner finish behind Xiaomi native owner"
                                + ", generation=" + session.generation
                                + ", reason=" + reason
                                + ", commitPending="
                                + session.unifiedNativeCommitPending
                                + ", cancelPending="
                                + session.unifiedNativeCancelPending
                                + ", nativeStarted="
                                + session.nativeAnimationStarted);
                return;
            }
            if (!session.finished.compareAndSet(0, 1)) {
                return;
            }
            freezePreviewProgress(session, "finish:" + reason);
            invalidatePendingCloseInterruption(session, "finish:" + reason);
            invalidatePendingDirectCancel(
                    session, "finish:" + reason, false);
            invalidateElementTransitionContinuity(
                    session, "finish:" + reason, true);
            if (Looper.myLooper() != Looper.getMainLooper()) {
                notifyRemoteAnimationFinished(session.finishedCallback, reason);
                handler.post(() -> cleanupFinishedSession(
                        session, reason, restoreSurface));
                return;
            }
            if (restoreSurface && session.previewInitialized
                    && !session.unifiedNativePreviewOwned) {
                session.currentRect.set(session.startRect);
                session.currentCornerRadius = session.startCornerRadius;
                applyPreviewTransform(session, session.currentRect,
                        session.currentCornerRadius, false);
            }
            notifyRemoteAnimationFinished(session.finishedCallback, reason);
            cleanupFinishedSession(session, reason, false);
        }

        protected void cleanupFinishedSession(ReturnHomeSession session, String reason,
                                            boolean restoreSurface) {
            if (session == null || !session.cleaned.compareAndSet(0, 1)) {
                return;
            }
            freezePreviewProgress(session, "cleanup:" + reason);
            if (restoreSurface && session.previewInitialized
                    && !session.unifiedNativePreviewOwned) {
                session.currentRect.set(session.startRect);
                session.currentCornerRadius = session.startCornerRadius;
                applyPreviewTransform(session, session.currentRect,
                        session.currentCornerRadius, false);
            }
            Runnable timeout = session.nativeTimeout;
            if (timeout != null) {
                handler.removeCallbacks(timeout);
            }
            Runnable cancelFinishGuard = session.cancelFinishGuard;
            if (cancelFinishGuard != null) {
                handler.removeCallbacks(cancelFinishGuard);
            }
            ValueAnimator animator = session.cancelAnimator;
            if (animator != null && animator.isRunning()) {
                animator.cancel();
            }
            restoreNativePreviewBlur(session, "cleanup:" + reason);
            restoreNativePreviewBackdrop(session, "cleanup:" + reason);
            MiuiHomeLocalHandoffToken handoffToken = session.localHandoffToken;
            if (handoffToken != null) {
                miuiHomeLocalHandoffToken.compareAndSet(handoffToken, null);
            }
            if (session.nativeStatusPublished
                    && session.nativeWindowAnimContext != null) {
                try {
                    Object currentStatus = invokeAnyMethod(
                            session.nativeWindowAnimContext,
                            "getLocalAnimLastStatus", new Object[0]);
                    if (currentStatus == session.nativePublishedStatus) {
                        invokeAnyMethod(session.nativeWindowAnimContext,
                                "setLocalAnimLastStatus", new Object[]{null});
                    } else if (currentStatus != null) {
                        log(Log.INFO, TAG, "Preserved replacement Xiaomi handoff status"
                                + ", generation=" + session.generation
                                + ", published="
                                + shortObject(session.nativePublishedStatus)
                                + ", current=" + shortObject(currentStatus));
                    }
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "Failed to clear unused Xiaomi handoff status"
                            + ", generation=" + session.generation, throwable);
                }
            }
            session.nativeStatusPublished = false;
            session.nativePublishedStatus = null;
            session.nativeGeometrySnapshot.set(null);
            session.nativeWindowAnimContext = null;
            session.localHandoffToken = null;
            UnifiedNativeCommitTransitionToken commitTransition =
                    session.unifiedNativeCommitTransition;
            if (commitTransition != null) {
                commitTransition.phase.set(
                        UnifiedNativeCommitTransitionToken.PHASE_INVALID);
            }
            session.unifiedNativeCommitTransition = null;
            UnifiedNativeStandardCommitToken standardCommit =
                    session.unifiedNativeStandardCommit;
            if (standardCommit != null) {
                standardCommit.phase.set(
                        UnifiedNativeStandardCommitToken.PHASE_INVALID);
            }
            session.unifiedNativeStandardCommit = null;
            StandardReturnHomeCommitSignal pendingStandard =
                    pendingStandardCommitSignal.get();
            if (pendingStandard != null
                    && pendingStandard.launcherSessionGeneration
                    == session.generation) {
                pendingStandardCommitSignal.compareAndSet(
                        pendingStandard, null);
            }
            session.unifiedNativeExternalTerminationAttempt = 0L;
            session.unifiedNativeExternalTerminationReason = null;
            session.unifiedNativeConfiguredAnimTo.set(null);
            invalidateUnifiedPendingInterruption(
                    session, "cleanup:" + reason);
            UnifiedNativeProvisionalCommitSnapshot provisionalCommit =
                    session.unifiedNativeProvisionalCommit.getAndSet(null);
            if (provisionalCommit != null
                    && provisionalCommit.phase.get()
                    != UnifiedNativeProvisionalCommitSnapshot.PHASE_ADOPTED) {
                provisionalCommit.phase.set(
                        UnifiedNativeProvisionalCommitSnapshot.PHASE_INVALID);
            }
            UnifiedNativeTerminalFailureSnapshot terminalFailure =
                    session.unifiedNativeTerminalFailure.getAndSet(null);
            if (terminalFailure != null) {
                Runnable terminalGuard = terminalFailure.cleanupGuard;
                if (terminalGuard != null) {
                    handler.removeCallbacks(terminalGuard);
                }
                if (terminalFailure.phase.get()
                        != UnifiedNativeTerminalFailureSnapshot.PHASE_COMPLETED) {
                    terminalFailure.phase.set(
                            UnifiedNativeTerminalFailureSnapshot.PHASE_INVALID);
                }
            }
            session.unifiedNativeCancelAnimParams = null;
            for (Map.Entry<Object,
                    ConcurrentLinkedQueue<UnifiedNativeFinishDispatchToken>>
                    entry : pendingUnifiedNativeFinishDispatches.entrySet()) {
                ConcurrentLinkedQueue<UnifiedNativeFinishDispatchToken>
                        queue = entry.getValue();
                queue.removeIf(dispatch -> dispatch.session == session);
                if (queue.isEmpty()) {
                    pendingUnifiedNativeFinishDispatches.remove(
                            entry.getKey(), queue);
                }
            }
            UnifiedNativeFinishSnapshot finishSnapshot =
                    session.unifiedNativeFinishSnapshot.getAndSet(null);
            if (finishSnapshot != null
                    && finishSnapshot.phase.get()
                    == UnifiedNativeFinishSnapshot.PHASE_PENDING) {
                finishSnapshot.phase.set(
                        UnifiedNativeFinishSnapshot.PHASE_INVALID);
            }
            if (session.unifiedNativePreviewSpringEndHeld) {
                try {
                    setUnifiedNativePreviewSpringEndEnabled(
                            session, true, "cleanup:" + reason);
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG,
                            "Failed to restore Xiaomi predictive spring end"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason,
                            throwable);
                }
            }
            session.unifiedNativeCleanupVerified = true;
            session.unifiedNativeTargetSet = null;
            session.unifiedNativeClipHelper = null;
            try {
                session.transaction.close();
            } catch (Throwable ignored) {
            }
            if (session.previewLeash != null
                    && session.previewLeash != session.closingLeash) {
                try {
                    session.previewLeash.release();
                } catch (Throwable ignored) {
                }
            }
            session.previewTarget = null;
            session.previewLeash = null;
            releaseTargets(session.apps);
            releaseTargets(session.wallpapers);
            releaseTargets(session.nonApps);
            if (currentSession == session) {
                currentSession = null;
            }
            log(Log.INFO, TAG, "Finished MiuiHome return-to-home runner"
                    + ", generation=" + session.generation
                    + ", reason=" + reason
                    + ", nativeHandoff=" + session.nativeHandoffStarted
                    + ", nativeStarted=" + session.nativeAnimationStarted
                    + ", unifiedOwner="
                    + session.unifiedNativePreviewOwned);
            finishDeferredMiuiHomeReturnHomeController(
                    MiuiHomeReturnHomeController.this, reason);
        }

        protected void notifyRemoteAnimationFinished(IBinder callback, String reason) {
            if (callback == null) {
                return;
            }
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(REMOTE_ANIMATION_FINISHED_DESCRIPTOR);
                callback.transact(1, data, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to finish Shell remote animation"
                        + ", reason=" + reason, throwable);
            } finally {
                data.recycle();
            }
        }

        protected Rect resolveRemoteAnimationBounds(Object target) {
            try {
                Object configuration = readField(target, "windowConfiguration");
                Object bounds = invokeAnyMethod(configuration,
                        "getMaxBounds", new Object[0]);
                if (bounds instanceof Rect && !((Rect) bounds).isEmpty()) {
                    return trimRemoteAnimationContentInsets(
                            target, new Rect((Rect) bounds));
                }
            } catch (Throwable ignored) {
            }
            for (String fieldName : new String[]{
                    "screenSpaceBounds", "startBounds", "sourceContainerBounds",
                    "localBounds"}) {
                try {
                    Object value = readField(target, fieldName);
                    if (value instanceof Rect && !((Rect) value).isEmpty()) {
                        return trimRemoteAnimationContentInsets(
                                target, new Rect((Rect) value));
                    }
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        protected Rect trimRemoteAnimationContentInsets(Object target, Rect bounds) {
            try {
                Object value = readField(target, "contentInsets");
                if (value instanceof Rect) {
                    int bottomInset = Math.max(0, ((Rect) value).bottom);
                    if (bottomInset > 0 && bottomInset < bounds.height()) {
                        // Launcher3's return-to-home preview excludes only the navigation
                        // inset from the closing app's starting content rectangle.
                        bounds.bottom -= bottomInset;
                    }
                }
            } catch (Throwable ignored) {
            }
            return bounds;
        }

        protected float resolveMiuiWindowCornerRadius(Object target) {
            try {
                Class<?> radiusClass = Class.forName(
                        MIUI_HOME_WINDOW_CORNER_RADIUS_UTIL, false, classLoader);
                Method method = radiusClass.getDeclaredMethod("getCornerRadius");
                method.setAccessible(true);
                Object value = method.invoke(null);
                if (value instanceof Number) {
                    return Math.max(0.0f, ((Number) value).floatValue());
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to resolve Xiaomi window corner radius",
                        throwable);
            }
            return Math.max(0.0f, readFloatFieldOrDefault(
                    target, "cornerRadius", 0.0f));
        }

        protected void releaseTargets(Object[] targets) {
            if (targets == null) {
                return;
            }
            for (Object target : targets) {
                try {
                    Object leash = readField(target, "leash");
                    if (leash instanceof SurfaceControl) {
                        ((SurfaceControl) leash).release();
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        protected float dp(float value) {
            Context currentContext = context;
            float density = currentContext == null ? 1.0f
                    : currentContext.getResources().getDisplayMetrics().density;
            return value * Math.max(0.1f, density);
        }

        protected float clamp01(float value) {
            return Math.max(0.0f, Math.min(1.0f, value));
        }

        protected float lerp(float start, float end, float progress) {
            return start + ((end - start) * progress);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        protected BackMotionEvent readBackMotionEvent(Parcel parcel) throws Exception {
            Parcelable.Creator creator = (Parcelable.Creator) readStaticField(
                    BackMotionEvent.class, "CREATOR");
            return (BackMotionEvent) parcel.readTypedObject(creator);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        protected Object[] readRemoteAnimationTargets(Parcel parcel) throws Exception {
            Parcelable.Creator creator = (Parcelable.Creator) readStaticField(
                    android.view.RemoteAnimationTarget.class, "CREATOR");
            return (Object[]) parcel.createTypedArray(creator);
        }

        protected final class ReturnHomeBackCallback extends Binder implements IInterface {
            ReturnHomeBackCallback() {
                attachInterface(this, ON_BACK_INVOKED_CALLBACK_DESCRIPTOR);
            }

            @Override
            public IBinder asBinder() {
                return this;
            }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws android.os.RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) {
                        reply.writeString(ON_BACK_INVOKED_CALLBACK_DESCRIPTOR);
                    }
                    return true;
                }
                if (code >= 1 && code <= 6) {
                    data.enforceInterface(ON_BACK_INVOKED_CALLBACK_DESCRIPTOR);
                }
                try {
                    switch (code) {
                        case 1: {
                            BackMotionEvent event = readBackMotionEvent(data);
                            data.enforceNoDataAvail();
                            handler.post(() -> onBackStarted(event));
                            return true;
                        }
                        case 2: {
                            BackMotionEvent event = readBackMotionEvent(data);
                            data.enforceNoDataAvail();
                            handler.post(() -> onBackProgressed(event));
                            return true;
                        }
                        case 3:
                            data.enforceNoDataAvail();
                            handler.post(MiuiHomeReturnHomeController.this::onBackCancelled);
                            return true;
                        case 4:
                            data.enforceNoDataAvail();
                            handler.post(MiuiHomeReturnHomeController.this::onBackInvoked);
                            return true;
                        case 5:
                            data.readBoolean();
                            data.enforceNoDataAvail();
                            return true;
                        case 6:
                            data.readStrongBinder();
                            data.enforceNoDataAvail();
                            return true;
                        default:
                            return super.onTransact(code, data, reply, flags);
                    }
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "MiuiHome back callback transact failed"
                            + ", code=" + code, throwable);
                    return true;
                }
            }
        }

        protected final class ReturnHomeAnimationRunner extends Binder implements IInterface {
            ReturnHomeAnimationRunner() {
                attachInterface(this, REMOTE_ANIMATION_RUNNER_DESCRIPTOR);
            }

            @Override
            public IBinder asBinder() {
                return this;
            }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws android.os.RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) {
                        reply.writeString(REMOTE_ANIMATION_RUNNER_DESCRIPTOR);
                    }
                    return true;
                }
                if (code == 1 || code == 2) {
                    data.enforceInterface(REMOTE_ANIMATION_RUNNER_DESCRIPTOR);
                }
                try {
                    if (code == 1) {
                        int transit = data.readInt();
                        Object[] apps = readRemoteAnimationTargets(data);
                        Object[] wallpapers = readRemoteAnimationTargets(data);
                        Object[] nonApps = readRemoteAnimationTargets(data);
                        IBinder finishedCallback = data.readStrongBinder();
                        data.enforceNoDataAvail();
                        handler.post(() -> onRemoteAnimationStart(transit, apps,
                                wallpapers, nonApps, finishedCallback));
                        return true;
                    }
                    if (code == 2) {
                        data.enforceNoDataAvail();
                        handler.post(
                                MiuiHomeReturnHomeController.this::onRemoteAnimationCancelled);
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "MiuiHome remote runner transact failed"
                            + ", code=" + code, throwable);
                    return true;
                }
            }
        }

        protected final class ReturnHomeElementLeashReuseToken {
            static final int PHASE_PREPARED = 0;
            static final int PHASE_REARMING = 1;
            static final int PHASE_REARMED = 2;
            static final int PHASE_ADOPTED = 3;
            static final int PHASE_INVALID = 4;
            static final int SEED_PENDING = 0;
            static final int SEEDING = 1;
            static final int SEED_APPLIED = 2;
            static final int SEED_REFRESHING = 3;
            static final int SEED_COMMITTED = 4;
            static final int SEED_INVALID = 5;

            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final Object compat;
            final Object helper;
            final Object transitionInfo;
            final int transitionDebugId;
            final int taskId;
            final SurfaceControl appLeash;
            final Object elementChange;
            final SurfaceControl elementLeash;
            final SurfaceControl closingLeash;
            final AtomicInteger phase = new AtomicInteger(PHASE_PREPARED);
            final AtomicInteger startGeometrySeed =
                    new AtomicInteger(SEED_PENDING);
            volatile SurfaceControl.Transaction startTransaction;
            volatile Object pendingAnimParams;

            ReturnHomeElementLeashReuseToken(
                    ReturnHomeSession session, Object windowElement,
                    Object animationIdentity, Object compat, Object helper,
                    Object transitionInfo, int transitionDebugId, int taskId,
                    SurfaceControl appLeash, Object elementChange,
                    SurfaceControl elementLeash,
                    SurfaceControl closingLeash) {
                this.generation = session.generation;
                this.session = session;
                this.windowElement = windowElement;
                this.animationIdentity = animationIdentity;
                this.compat = compat;
                this.helper = helper;
                this.transitionInfo = transitionInfo;
                this.transitionDebugId = transitionDebugId;
                this.taskId = taskId;
                this.appLeash = appLeash;
                this.elementChange = elementChange;
                this.elementLeash = elementLeash;
                this.closingLeash = closingLeash;
            }
        }

        protected final class ReturnHomeCloseInterruptionToken {
            final long generation;
            final ReturnHomeSession session;
            final Object stateManager;
            final Object windowElement;
            final Object animationIdentity;
            final UnifiedNativePendingInterruptionSnapshot
                    pendingCommitInterruption;

            ReturnHomeCloseInterruptionToken(
                    ReturnHomeSession session, Object stateManager,
                    Object windowElement, Object animationIdentity,
                    UnifiedNativePendingInterruptionSnapshot
                            pendingCommitInterruption) {
                this.generation = session.generation;
                this.session = session;
                this.stateManager = stateManager;
                this.windowElement = windowElement;
                this.animationIdentity = animationIdentity;
                this.pendingCommitInterruption =
                        pendingCommitInterruption;
            }
        }

        protected final class ReturnHomeFreshOpenToken {
            final long generation;
            final Object stateManager;
            final Object windowElement;
            final Object animationIdentity;
            final View clickedView;
            final AtomicInteger invocations = new AtomicInteger();

            ReturnHomeFreshOpenToken(
                    ReturnHomeSession session, Object stateManager,
                    Object windowElement, Object animationIdentity,
                    View clickedView) {
                this.generation = session.generation;
                this.stateManager = stateManager;
                this.windowElement = windowElement;
                this.animationIdentity = animationIdentity;
                this.clickedView = clickedView;
            }
        }

        protected final class ReturnHomeDirectCancelToken {
            static final int PHASE_PENDING = 0;
            static final int PHASE_FINISHED_NOTIFIED = 1;
            static final int PHASE_CLEANED = 2;

            final long generation;
            final ReturnHomeSession session;
            final Object stateManager;
            final Object windowElement;
            final Object animationIdentity;
            final Object pendingIcon;
            final Object originalCallback;
            final UnifiedNativePendingInterruptionSnapshot
                    pendingCommitInterruption;
            final AtomicInteger phase = new AtomicInteger(PHASE_PENDING);
            volatile Object wrappedCallback;
            volatile Runnable cleanupGuard;

            ReturnHomeDirectCancelToken(
                    ReturnHomeSession session, Object stateManager,
                    Object windowElement, Object animationIdentity,
                    Object pendingIcon, Object originalCallback,
                    UnifiedNativePendingInterruptionSnapshot
                            pendingCommitInterruption) {
                this.generation = session.generation;
                this.session = session;
                this.stateManager = stateManager;
                this.windowElement = windowElement;
                this.animationIdentity = animationIdentity;
                this.pendingIcon = pendingIcon;
                this.originalCallback = originalCallback;
                this.pendingCommitInterruption =
                        pendingCommitInterruption;
            }
        }

        protected final class UnifiedNativePendingInterruptionSnapshot {
            static final int PHASE_PENDING = 0;
            static final int PHASE_CONSUMED = 1;
            static final int PHASE_INVALID = 2;
            static final int MUTATION_NONE = 0;
            static final int MUTATION_DIRECT_CANCEL = 1;
            static final int MUTATION_CANCEL_SURFACE = 2;
            static final int CONFIG_PENDING = 0;
            static final int CONFIG_ACK_APPLIED = 1;
            static final int CONFIG_ACK_SKIPPED = 2;
            static final int CONFIG_INVALID = 3;

            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final Object animParams;
            final Object ownerToken;
            final Object configLock;
            final long animToEpoch;
            final long ownerAttempt;
            final String requestedType;
            final AtomicInteger phase =
                    new AtomicInteger(PHASE_PENDING);
            final AtomicInteger mutation =
                    new AtomicInteger(MUTATION_NONE);
            final AtomicInteger configDisposition =
                    new AtomicInteger(CONFIG_PENDING);

            UnifiedNativePendingInterruptionSnapshot(
                    ReturnHomeSession session, Object animParams,
                    Object ownerToken, long animToEpoch,
                    long ownerAttempt, String requestedType) {
                this.generation = session.generation;
                this.session = session;
                this.windowElement = session.nativeWindowElement;
                this.animationIdentity =
                        session.unifiedNativeAnimationIdentity;
                this.animParams = animParams;
                this.ownerToken = ownerToken;
                this.configLock = ownerToken
                        instanceof UnifiedNativeStandardCommitToken
                        ? ((UnifiedNativeStandardCommitToken)
                        ownerToken).configLock
                        : ((UnifiedNativeCommitTransitionToken)
                        ownerToken).configLock;
                this.animToEpoch = animToEpoch;
                this.ownerAttempt = ownerAttempt;
                this.requestedType = requestedType;
            }
        }

        protected final class UnifiedNativeCommitTransitionToken {
            static final int PHASE_PENDING = 0;
            static final int PHASE_ENTERING = 1;
            static final int PHASE_ENTERED = 2;
            static final int PHASE_CONSUMED = 3;
            static final int PHASE_ADOPTED = 4;
            static final int PHASE_INVALID = 5;

            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final Object remoteTransitionParams;
            final Object compat;
            final Object helper;
            final Object transitionToken;
            final Object transitionInfo;
            final int transitionDebugId;
            final Object configLock = new Object();
            final AtomicInteger configHookState =
                    new AtomicInteger(UNIFIED_CONFIG_HOOK_PENDING);
            final AtomicInteger phase =
                    new AtomicInteger(PHASE_PENDING);
            final AtomicReference<Object> animParams =
                    new AtomicReference<>();
            volatile long animToEpoch;

            UnifiedNativeCommitTransitionToken(
                    ReturnHomeSession session, Object windowElement,
                    Object animationIdentity,
                    Object remoteTransitionParams,
                    Object compat, Object helper,
                    Object transitionToken, Object transitionInfo,
                    int transitionDebugId) {
                this.generation = session.generation;
                this.session = session;
                this.windowElement = windowElement;
                this.animationIdentity = animationIdentity;
                this.remoteTransitionParams = remoteTransitionParams;
                this.compat = compat;
                this.helper = helper;
                this.transitionToken = transitionToken;
                this.transitionInfo = transitionInfo;
                this.transitionDebugId = transitionDebugId;
            }
        }

        protected final class UnifiedNativeStandardCommitToken {
            static final int PHASE_PENDING = 0;
            static final int PHASE_ENTERING = 1;
            static final int PHASE_ENTERED = 2;
            static final int PHASE_CONSUMED = 3;
            static final int PHASE_ADOPTED = 4;
            static final int PHASE_INVALID = 5;

            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final StandardReturnHomeCommitSignal signal;
            final Object configLock = new Object();
            final AtomicInteger configHookState =
                    new AtomicInteger(UNIFIED_CONFIG_HOOK_PENDING);
            final AtomicInteger phase = new AtomicInteger(PHASE_PENDING);
            final AtomicReference<Object> animParams = new AtomicReference<>();
            volatile long ownerAttempt;
            volatile long animToEpoch;

            UnifiedNativeStandardCommitToken(
                    ReturnHomeSession session,
                    StandardReturnHomeCommitSignal signal) {
                this.generation = session.generation;
                this.session = session;
                this.windowElement = session.nativeWindowElement;
                this.animationIdentity =
                        session.unifiedNativeAnimationIdentity;
                this.signal = signal;
            }
        }

        protected final class UnifiedNativeConfiguredAnimToSnapshot {
            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final Object animParams;
            final Object ownerToken;
            final long animToEpoch;
            final String animationType;
            final boolean cancel;
            final boolean running;
            final boolean finishComplete;

            UnifiedNativeConfiguredAnimToSnapshot(
                    ReturnHomeSession session, Object animParams,
                    Object ownerToken, long animToEpoch,
                    String animationType, boolean cancel,
                    boolean running, boolean finishComplete) {
                this.generation = session.generation;
                this.session = session;
                this.windowElement = session.nativeWindowElement;
                this.animationIdentity =
                        session.unifiedNativeAnimationIdentity;
                this.animParams = animParams;
                this.ownerToken = ownerToken;
                this.animToEpoch = animToEpoch;
                this.animationType = animationType;
                this.cancel = cancel;
                this.running = running;
                this.finishComplete = finishComplete;
            }
        }

        protected final class UnifiedNativeTerminalFailureSnapshot {
            static final int PHASE_PENDING = 0;
            static final int PHASE_CANCELLING = 1;
            static final int PHASE_COMPLETED = 2;
            static final int PHASE_INVALID = 3;
            static final int FINISH_STAGE_NONE = 0;
            static final int FINISH_STAGE_SOURCE_SKIPPED = 1;
            static final int FINISH_STAGE_APPLY_SKIPPED = 2;

            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final Object animParams;
            final Object ownerToken;
            final long animToEpoch;
            final boolean cancel;
            final boolean pendingCommitTermination;
            final boolean pendingCommitStateCleared;
            final String reason;
            final Throwable failure;
            final AtomicInteger phase =
                    new AtomicInteger(PHASE_PENDING);
            final AtomicInteger finishStage = new AtomicInteger();
            volatile Runnable cleanupGuard;

            UnifiedNativeTerminalFailureSnapshot(
                    ReturnHomeSession session, Object animParams,
                    Object ownerToken, long animToEpoch,
                    boolean cancel,
                    boolean pendingCommitTermination,
                    boolean pendingCommitStateCleared,
                    String reason,
                    Throwable failure) {
                this.generation = session.generation;
                this.session = session;
                this.windowElement = session.nativeWindowElement;
                this.animationIdentity =
                        session.unifiedNativeAnimationIdentity;
                this.animParams = animParams;
                this.ownerToken = ownerToken;
                this.animToEpoch = animToEpoch;
                this.cancel = cancel;
                this.pendingCommitTermination =
                        pendingCommitTermination;
                this.pendingCommitStateCleared =
                        pendingCommitStateCleared;
                this.reason = reason;
                this.failure = failure == null
                        ? new IllegalStateException(reason)
                        : failure;
                if (reason.startsWith("finishSourceFailure:")) {
                    finishStage.set(FINISH_STAGE_SOURCE_SKIPPED);
                } else if (reason.startsWith(
                        "finishApplyFailure:")) {
                    finishStage.set(FINISH_STAGE_APPLY_SKIPPED);
                }
            }

            void markFinishSourceSkipped() {
                finishStage.set(FINISH_STAGE_SOURCE_SKIPPED);
            }

            void markFinishApplySkipped() {
                finishStage.compareAndSet(
                        FINISH_STAGE_NONE,
                        FINISH_STAGE_APPLY_SKIPPED);
            }
        }

        protected final class UnifiedNativeFinishDispatchToken {
            final long dispatchId;
            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final UnifiedNativeConfiguredAnimToSnapshot configured;
            final boolean allowed;

            UnifiedNativeFinishDispatchToken(
                    long dispatchId, ReturnHomeSession session,
                    Object windowElement, Object animationIdentity,
                    UnifiedNativeConfiguredAnimToSnapshot configured,
                    boolean allowed) {
                this.dispatchId = dispatchId;
                this.generation = session.generation;
                this.session = session;
                this.windowElement = windowElement;
                this.animationIdentity = animationIdentity;
                this.configured = configured;
                this.allowed = allowed;
            }
        }

        protected final class UnifiedNativeProvisionalCommitSnapshot {
            static final int PHASE_PENDING = 0;
            static final int PHASE_ADOPTING = 1;
            static final int PHASE_ADOPTED = 2;
            static final int PHASE_INVALID = 3;

            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final UnifiedNativeConfiguredAnimToSnapshot configured;
            final UnifiedNativeStandardCommitToken standardToken;
            final UnifiedNativeCommitTransitionToken transitionToken;
            final UnifiedNativeRetargetInspection inspection;
            final long ownerAttempt;
            final long animToEpoch;
            final String animationType;
            final AtomicInteger phase =
                    new AtomicInteger(PHASE_PENDING);

            UnifiedNativeProvisionalCommitSnapshot(
                    ReturnHomeSession session,
                    UnifiedNativeConfiguredAnimToSnapshot configured,
                    UnifiedNativeStandardCommitToken standardToken,
                    UnifiedNativeCommitTransitionToken transitionToken,
                    UnifiedNativeRetargetInspection inspection) {
                this.generation = session.generation;
                this.session = session;
                this.windowElement = session.nativeWindowElement;
                this.animationIdentity =
                        session.unifiedNativeAnimationIdentity;
                this.configured = configured;
                this.standardToken = standardToken;
                this.transitionToken = transitionToken;
                this.inspection = inspection;
                this.ownerAttempt = inspection.attempt;
                this.animToEpoch = configured.animToEpoch;
                this.animationType = inspection.actualType;
            }
        }

        protected final class UnifiedNativeFinishSnapshot {
            static final int PHASE_PENDING = 0;
            static final int PHASE_CONSUMED = 1;
            static final int PHASE_INVALID = 2;

            final long generation;
            final ReturnHomeSession session;
            final Object stateManager;
            final Object windowElement;
            final Object callbackStateManager;
            final Object currentElement;
            final String currentElementType;
            final boolean oldElementRecorded;
            final Object animationIdentity;
            final Object currentAnimationIdentity;
            final String actualType;
            final boolean exactTarget;
            final boolean running;
            final boolean finishComplete;
            final boolean fullscreen;
            final long animToEpoch;
            final UnifiedNativeCommitTransitionToken commitTransition;
            final Throwable failure;
            final AtomicInteger phase =
                    new AtomicInteger(PHASE_PENDING);

            UnifiedNativeFinishSnapshot(
                    ReturnHomeSession session,
                    Object callbackStateManager,
                    Object currentElement,
                    String currentElementType,
                    boolean oldElementRecorded,
                    Object animationIdentity,
                    Object currentAnimationIdentity,
                    String actualType, boolean exactTarget,
                    boolean running, boolean finishComplete,
                    boolean fullscreen, long animToEpoch,
                    UnifiedNativeCommitTransitionToken commitTransition,
                    Throwable failure) {
                this.generation = session.generation;
                this.session = session;
                this.stateManager = session.stateManager;
                this.windowElement = session.nativeWindowElement;
                this.callbackStateManager = callbackStateManager;
                this.currentElement = currentElement;
                this.currentElementType = currentElementType;
                this.oldElementRecorded = oldElementRecorded;
                this.animationIdentity = animationIdentity;
                this.currentAnimationIdentity =
                        currentAnimationIdentity;
                this.actualType = actualType;
                this.exactTarget = exactTarget;
                this.running = running;
                this.finishComplete = finishComplete;
                this.fullscreen = fullscreen;
                this.animToEpoch = animToEpoch;
                this.commitTransition = commitTransition;
                this.failure = failure;
            }
        }

        protected final class UnifiedNativeRetargetInspection {
            final long attempt;
            final String requestedType;
            final String actualType;
            final Object animationIdentity;
            final boolean sameAnimation;
            final boolean exactTarget;
            final boolean running;
            final boolean finishComplete;
            final boolean fullscreen;
            final UnifiedNativeCommitTransitionToken commitTransition;
            final Throwable failure;

            UnifiedNativeRetargetInspection(
                    long attempt, String requestedType, String actualType,
                    Object animationIdentity, boolean sameAnimation,
                    boolean exactTarget, boolean running,
                    boolean finishComplete, boolean fullscreen,
                    UnifiedNativeCommitTransitionToken commitTransition,
                    Throwable failure) {
                this.attempt = attempt;
                this.requestedType = requestedType;
                this.actualType = actualType;
                this.animationIdentity = animationIdentity;
                this.sameAnimation = sameAnimation;
                this.exactTarget = exactTarget;
                this.running = running;
                this.finishComplete = finishComplete;
                this.fullscreen = fullscreen;
                this.commitTransition = commitTransition;
                this.failure = failure;
            }
        }

        protected final class ReturnHomeSession {
            final long generation;
            final Object[] apps;
            final Object[] wallpapers;
            final Object[] nonApps;
            final IBinder finishedCallback;
            final MiuiHomeAcceptedInputToken acceptedInputIdentity;
            final AtomicInteger finished = new AtomicInteger();
            final AtomicInteger cleaned = new AtomicInteger();
            final Rect startRect = new Rect();
            final RectF currentRect = new RectF();
            final Matrix matrix = new Matrix();
            final float[] matrixValues = new float[9];
            final SurfaceControl.Transaction transaction =
                    new SurfaceControl.Transaction();
            final BackProgressAnimator progressAnimator;
            final AtomicInteger progressReset = new AtomicInteger();
            final AtomicReference<ReturnHomeNativeGeometrySnapshot>
                    nativeGeometrySnapshot = new AtomicReference<>();
            final AtomicLong unifiedNativeRetargetAttempts =
                    new AtomicLong();
            final AtomicLong unifiedNativeAnimToEpochs =
                    new AtomicLong();
            final AtomicReference<UnifiedNativeFinishSnapshot>
                    unifiedNativeFinishSnapshot = new AtomicReference<>();
            final AtomicReference<UnifiedNativeConfiguredAnimToSnapshot>
                    unifiedNativeConfiguredAnimTo = new AtomicReference<>();
            final AtomicReference<UnifiedNativeTerminalFailureSnapshot>
                    unifiedNativeTerminalFailure =
                    new AtomicReference<>();
            final AtomicReference<UnifiedNativePendingInterruptionSnapshot>
                    unifiedNativePendingInterruption =
                    new AtomicReference<>();
            final AtomicReference<UnifiedNativeProvisionalCommitSnapshot>
                    unifiedNativeProvisionalCommit =
                    new AtomicReference<>();
            final AtomicBoolean unifiedNativeCommitReady =
                    new AtomicBoolean();
            final Object nativeGeometryApplyLock = new Object();
            Object closingTarget;
            Object openingTarget;
            SurfaceControl closingLeash;
            Object previewTarget;
            SurfaceControl previewLeash;
            String previewTargetSource;
            boolean previewInitialized;
            boolean previewLeashPrepared;
            float initialTouchY;
            int swipeEdge;
            float startCornerRadius;
            float endCornerRadius;
            float currentCornerRadius;
            float previewProgressDistancePx;
            float lastInputProgress;
            float lastSmoothedProgress;
            volatile boolean progressFrozen;
            boolean progressAnimatorStarted;
            boolean progressAnimatorFailed;
            boolean progressInitialCallbackDelivered;
            boolean frameTimelineTagFailed;
            boolean nativeHandoffStarted;
            boolean nativeStatusPublished;
            boolean nativeAnimationStarted;
            boolean nativeContinuationVerified;
            boolean nativeGeometryFailureLogged;
            boolean unifiedNativePreviewOwned;
            boolean unifiedNativePreviewSpringEndHeld;
            boolean unifiedNativeCancelPending;
            boolean unifiedNativeCancelRetargeted;
            boolean unifiedNativeCancelEndObserved;
            boolean unifiedNativeCommitPending;
            boolean unifiedNativeCommitEndObserved;
            boolean unifiedNativeProviderCommitAdopted;
            boolean unifiedNativeCleanupVerified;
            int unifiedNativeTaskId = -1;
            int unifiedNativeCurrentRotation;
            int unifiedNativeHomeRotation;
            int unifiedNativeProviderBoundaryDebugId = -1;
            Object stateManager;
            Object nativeWindowElement;
            Object nativeWindowAnimContext;
            Object nativePublishedStatus;
            Object nativeAnimationIdentity;
            String nativeAnimationType;
            Object unifiedNativeAnimationIdentity;
            UnifiedNativeCommitTransitionToken
                    unifiedNativeCommitTransition;
            UnifiedNativeStandardCommitToken
                    unifiedNativeStandardCommit;
            volatile long unifiedNativeCommitAttempt;
            volatile long unifiedNativeCancelAttempt;
            volatile long unifiedNativeCancelTimeoutAttempt;
            volatile long unifiedNativeExternalTerminationAttempt;
            volatile long unifiedNativeActiveAnimToEpoch;
            volatile long unifiedNativeCancelAnimToEpoch;
            volatile String unifiedNativeCommitRequestedType;
            volatile String unifiedNativeExternalTerminationReason;
            Object unifiedNativeTargetSet;
            Object unifiedNativeClipHelper;
            Object unifiedNativeCancelAnimParams;
            MiuiHomeLocalHandoffToken localHandoffToken;
            Runnable nativeTimeout;
            Runnable unifiedNativeCancelTimeout;
            Runnable cancelFinishGuard;
            ValueAnimator cancelAnimator;
            Object previewBackdropStateManager;
            boolean previewBackdropProviderReturned;
            boolean previewShortcutOwned;
            Object previewShortcutElement;
            View previewShortcutView;
            Object previewShortcutAppParams;
            Object previewShortcutHomeParams;
            Object previewShortcutOwnedParams;
            float previewShortcutAppAlpha;
            float previewShortcutAppScaleX;
            float previewShortcutAppScaleY;
            boolean previewWallpaperOwned;
            Object previewWallpaperElement;
            Object previewWallpaperWorkspace;
            Object previewWallpaperAppParams;
            Object previewWallpaperHomeParams;
            float previewWallpaperAppZoom;
            float previewWallpaperHomeZoom;
            int previewWallpaperModuleCommandDepth;
            boolean previewWallpaperNativeAppSetObserved;
            boolean previewWallpaperNativeHomeAnimObserved;
            boolean previewBlurOwned;
            boolean previewBlurProviderReturned;
            Object previewBlurElement;
            Object previewBlurView;
            Object previewBlurAppParams;
            Object previewBlurOwnedParams;
            Object previewBlurHomeParams;
            int previewBlurInitialRadius;
            float previewBlurInitialDimming;
            int previewBlurTargetRadius;
            float previewBlurTargetDimming;
            int previewBlurPublishedRadius;
            float previewBlurPublishedDimming;
            boolean previewBlurInterruptedHomeSpring;

            ReturnHomeSession(long generation, Object[] apps,
                              Object[] wallpapers, Object[] nonApps,
                              IBinder finishedCallback,
                              MiuiHomeAcceptedInputToken acceptedInputIdentity) {
                this.generation = generation;
                this.apps = apps;
                this.wallpapers = wallpapers;
                this.nonApps = nonApps;
                this.finishedCallback = finishedCallback;
                this.acceptedInputIdentity = acceptedInputIdentity;
                BackProgressAnimator animator = null;
                try {
                    if (Looper.myLooper() != Looper.getMainLooper()) {
                        throw new IllegalStateException(
                                "BackProgressAnimator constructed outside main Looper");
                    }
                    animator = new BackProgressAnimator();
                } catch (Throwable throwable) {
                    progressAnimatorFailed = true;
                    log(Log.WARN, TAG,
                            "Could not create AOSP return-home progress smoothing"
                                    + ", generation=" + generation,
                            throwable);
                }
                this.progressAnimator = animator;
            }

            boolean resolveTargets() {
                if (apps == null || apps.length == 0 || finishedCallback == null) {
                    return false;
                }
                for (Object target : apps) {
                    int mode = readIntFieldOrDefault(target, "mode", -1);
                    if (mode == 1 && closingTarget == null) {
                        closingTarget = target;
                    } else if (mode == 0 && openingTarget == null) {
                        openingTarget = target;
                    }
                }
                if (closingTarget == null || openingTarget == null) {
                    return false;
                }
                try {
                    Object leash = readField(closingTarget, "leash");
                    if (leash instanceof SurfaceControl
                            && ((SurfaceControl) leash).isValid()) {
                        closingLeash = (SurfaceControl) leash;
                    }
                } catch (Throwable ignored) {
                }
                return closingLeash != null;
            }
        }
    }
}
