package dev.codex.miuibackgesturehook.hooks.miuihome;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;
import static dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeOpenInterruptionHook.*;
import static dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeReturnHomeHook.*;
import static dev.codex.miuibackgesturehook.data.ReturnHomeData.*;

import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.window.BackMotionEvent;

import java.lang.reflect.Method;

abstract class MiuiHomeReturnHomePreviewImpl
        extends MiuiHomeReturnHomeStateImpl {
    protected abstract class ReturnHomePreviewController
        extends MiuiHomeReturnHomeStateImpl.ReturnHomeStateController {
        ReturnHomePreviewController(IBinder shellBackAnimation,
                                     ClassLoader classLoader, Context context) {
            super(shellBackAnimation, classLoader, context);
        }

        protected boolean attach() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SHELL_BACK_ANIMATION_DESCRIPTOR);
                data.writeStrongBinder(backCallback);
                data.writeStrongBinder(animationRunner);
                if (!shellBackAnimation.transact(
                        SHELL_BACK_SET_LAUNCHER_CALLBACK_TRANSACTION,
                        data, reply, 0)) {
                    moduleLog(Log.WARN, TAG, "Shell rejected setBackToLauncherCallback transact");
                    return false;
                }
                reply.readException();
                shellBackAnimation.linkToDeath(shellDeathRecipient, 0);
                deathLinked = true;
                attached = true;
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to register Shell return-to-home runner",
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
                    || pendingLauncherOpenBarrier.get() != null
                    || !pendingUnifiedInterruptedAnimToConfigs.isEmpty();
        }

        protected void beginDeferredControllerReplacement(String reason) {
            if (!deferredControllerReplacement) {
                deferredControllerReplacement = true;
                attached = false;
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
                        "deferredControllerReplacement:" + reason);
            }
        }

        protected void onShellBinderDied() {
            shellBinderDead = true;
            deathLinked = false;
            invalidatePendingLauncherOpenBarrier(
                    "shellBinderDied", true);
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

        protected void detach(boolean clearShell, String reason) {
            attached = false;
            invalidatePendingLauncherOpenBarrier(
                    "detach:" + reason, true);
            invalidatePendingDirectCancel(null, "detach:" + reason, true);
            invalidateElementTransitionContinuity(
                    null, "detach:" + reason, true);
            if (deathLinked) {
                deathLinked = false;
                try {
                    shellBackAnimation.unlinkToDeath(shellDeathRecipient, 0);
                } catch (Throwable throwable) {
                    moduleLog(Log.INFO, TAG, "Shell back-animation death link already gone"
                            + ", reason=" + reason);
                }
            }
            ReturnHomeSession session = currentSession;
            if (session != null) {
                finishSession(session, "detach:" + reason);
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
                    moduleLog(Log.INFO, TAG, "Cleared standard Shell return-to-home callback"
                            + ", reason=" + reason);
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG, "Failed to clear Shell return-to-home callback"
                            + ", reason=" + reason, throwable);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }

        protected void onBackStarted(BackMotionEvent event) {
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
            startPreview(session, event, true);
        }

        protected void onBackProgressed(BackMotionEvent event) {
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
                    if (!startPreview(session, startEvent, true)) {
                        return;
                    }
                }
            }
            dispatchPreviewProgress(session, event, true);
        }

        protected void onBackCancelled() {
            if (discardRejectedRunnerCallback) {
                discardRejectedRunnerCallback = false;
                clearPendingCallbackState();
                moduleLog(Log.INFO, TAG,
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

        protected void onBackInvoked() {
            if (discardRejectedRunnerCallback) {
                discardRejectedRunnerCallback = false;
                clearPendingCallbackState();
                moduleLog(Log.INFO, TAG,
                        "Discarded invoke callback for rejected return-home runner");
                return;
            }
            ReturnHomeSession session = currentSession;
            if (session == null || session.progressFrozen) {
                pendingTerminalAction = RETURN_HOME_TERMINAL_INVOKE;
                moduleLog(Log.INFO, TAG, "Return-to-home invoke waiting for remote targets");
                return;
            }
            clearPendingCallbackState();
            startNativeClose(session);
        }

        protected void onRemoteAnimationStart(int transit, Object[] apps, Object[] wallpapers,
                                              Object[] nonApps, IBinder finishedCallback) {
            if (!attached) {
                notifyRemoteAnimationFinished(finishedCallback, "detachedStart");
                return;
            }
            ReturnHomeSession previous = currentSession;
            ReturnHomeLauncherOpenBarrierToken previousLauncherOpen =
                    pendingLauncherOpenBarrier.get();
            boolean retainedLauncherOpen = previousLauncherOpen != null
                    && !previousLauncherOpen.invalidated.get();
            boolean retainedNativeOwner = previous != null
                    && previous.finished.get() == 0
                    && previous.nativeHandoffStarted
                    && previous.nativeAnimationStarted
                    && isReturnHomeNativeCloseType(
                    previous.nativeAnimationType);
            boolean retainedPreviewOwner = previous != null
                    && previous.unifiedNativePreviewOwned
                    && !previous.unifiedNativeCleanupVerified
                    && previous.finished.get() == 0;
            if (retainedLauncherOpen || retainedPreviewOwner
                    || retainedNativeOwner) {
                if (!retainedLauncherOpen && retainedPreviewOwner) {
                    startUnifiedNativeCancel(
                            previous, "supersededRunner");
                }
                discardRejectedRunnerCallback =
                        pendingTerminalAction == RETURN_HOME_TERMINAL_NONE;
                clearPendingCallbackState();
                discardPendingStandardCommitForRunner(
                        finishedCallback, "overlappingRunnerRejected");
                notifyRemoteAnimationFinished(
                        finishedCallback, "previousNativeOwnerActive");
                releaseTargets(apps);
                releaseTargets(wallpapers);
                releaseTargets(nonApps);
                ReturnHomeSession retainedSession = retainedLauncherOpen
                        ? previousLauncherOpen.session : previous;
                moduleLog(Log.WARN, TAG,
                        "Rejected overlapping return-home runner"
                                + ", activeGeneration="
                                + (retainedSession == null ? 0L
                                : retainedSession.generation)
                                + ", nativeStarted="
                                + (retainedSession != null
                                && retainedSession.nativeAnimationStarted)
                                + ", launcherOpenPending="
                                + retainedLauncherOpen);
                return;
            }
            if (previous != null) {
                invalidatePendingDirectCancel(
                        previous, "superseded", true);
                finishSession(previous, "superseded");
            }
            invalidatePendingLauncherOpenBarrier("runnerStarted", true);
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
                    apps, wallpapers, nonApps, finishedCallback);
            currentSession = session;
            if (!session.resolveTargets()) {
                moduleLog(Log.WARN, TAG, "Invalid return-to-home animation targets"
                        + ", generation=" + session.generation
                        + ", apps=" + (apps == null ? -1 : apps.length));
                discardPendingStandardCommitForRunner(
                        finishedCallback, "invalidRunnerTargets");
                discardRejectedRunnerCallback =
                        terminalAction == RETURN_HOME_TERMINAL_NONE;
                releaseBackMotionEventTarget(startEvent);
                finishSession(session, "invalidTargets");
                return;
            }
            bindPendingStandardCommitToSession(session);
            if (startEvent != null) {
                if (!startPreview(session, startEvent,
                        terminalAction == RETURN_HOME_TERMINAL_NONE)) {
                    return;
                }
            }
            if (progressEvent != null) {
                if (terminalAction == RETURN_HOME_TERMINAL_NONE) {
                    dispatchPreviewProgress(session, progressEvent, true);
                } else {
                    // A release can beat the runner because callback and runner use separate
                    // Binder objects. There is no animation frame left in which the spring can
                    // catch up, so establish the exact latest gesture geometry once before the
                    // terminal path freezes/reset the animator.
                    session.lastInputProgress = clamp01(progressEvent.getProgress());
                    updatePreviewFrame(session, progressEvent.getProgress(),
                            progressEvent.getTouchY(), false);
                    moduleLog(Log.INFO, TAG,
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
            moduleLog(Log.INFO, TAG, "MiuiHome return-to-home remote animation started"
                    + ", generation=" + session.generation
                    + ", transit=" + transit
                    + ", apps=" + apps.length
                    + ", closing=" + shortObject(session.closingTarget)
                    + ", opening=" + shortObject(session.openingTarget));
        }

        protected void onRemoteAnimationCancelled() {
            clearPendingCallbackState();
            ReturnHomeSession session = currentSession;
            if (session != null) {
                if (requestUnifiedPendingCommitTermination(
                        session, "runnerCancelled")) {
                    return;
                }
                animateCancel(session, "runnerCancelled");
            } else {
                pendingStandardCommitSignal.set(null);
            }
        }

        protected boolean startPreview(ReturnHomeSession session, BackMotionEvent event,
                                       boolean terminalCallbackExpected) {
            if (session.finished.get() != 0 || currentSession != session) {
                return false;
            }
            session.initialTouchY = event.getTouchY();
            session.swipeEdge = event.getSwipeEdge();
            if (!session.previewInitialized) {
                if (!resolvePreviewTarget(session, event)) {
                    rejectUnavailableNativePreview(
                            session, terminalCallbackExpected);
                    return false;
                }
                Rect startBounds = resolveRemoteAnimationBounds(session.previewTarget);
                if (startBounds == null || startBounds.isEmpty()) {
                    moduleLog(Log.WARN, TAG, "Cannot resolve return-to-home preview bounds"
                            + ", generation=" + session.generation
                            + ", target=" + shortObject(session.previewTarget)
                            + ", source=" + session.previewTargetSource);
                    rejectUnavailableNativePreview(
                            session, terminalCallbackExpected);
                    return false;
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
                prepareNativePreviewBackdrop(session);
                prepareNativePreviewBlur(session);
                if (!prepareUnifiedNativePreview(session)) {
                    rejectUnavailableNativePreview(
                            session, terminalCallbackExpected);
                    return false;
                }
                moduleLog(Log.INFO, TAG, "Initialized return-to-home preview"
                        + ", generation=" + session.generation
                        + ", startRect=" + session.startRect
                        + ", startRadius=" + session.startCornerRadius
                        + ", targetSource=" + session.previewTargetSource
                        + ", previewTarget=" + shortObject(session.previewTarget)
                        + ", previewLeash=" + String.valueOf(session.previewLeash)
                        + ", runnerClosingLeash="
                        + String.valueOf(session.closingLeash)
                        + ", sameSurfaceAsRunner="
                        + describeSameSurface(session.previewLeash,
                        session.closingLeash));
            }
            if (!session.unifiedNativePreviewOwned
                    || session.unifiedNativeCancelPending) {
                rejectUnavailableNativePreview(
                        session, terminalCallbackExpected);
                return false;
            }
            startPreviewProgressAnimator(
                    session, event, terminalCallbackExpected);
            return currentSession == session
                    && session.finished.get() == 0
                    && session.unifiedNativePreviewOwned
                    && !session.unifiedNativeCleanupVerified
                    && !session.unifiedNativeCancelPending
                    && !session.progressFrozen;
        }

        protected void rejectUnavailableNativePreview(
                ReturnHomeSession session, boolean terminalCallbackExpected) {
            if (session == null || currentSession != session
                    || session.finished.get() != 0) {
                return;
            }
            discardRejectedRunnerCallback = terminalCallbackExpected;
            clearPendingCallbackState();
            discardPendingStandardCommitForRunner(
                    session.finishedCallback, "nativePreviewUnavailable");
            moduleLog(Log.WARN, TAG,
                    "Rejected return-home runner without unified Xiaomi preview"
                            + ", generation=" + session.generation
                            + ", nativeOwned="
                            + session.unifiedNativePreviewOwned
                            + ", cancelPending="
                            + session.unifiedNativeCancelPending
                            + ", terminalExpected="
                            + terminalCallbackExpected);
            finishSession(session, "nativePreviewUnavailable");
        }

        protected boolean resolvePreviewTarget(ReturnHomeSession session,
                                               BackMotionEvent event) {
            Object target = session.closingTarget;
            String source = "runnerClosing";
            if (!removeDepartTargetFromMotion) {
                try {
                    target = event.getDepartingAnimationTarget();
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG, "Failed to read departing predictive-back target"
                            + ", generation=" + session.generation, throwable);
                    return false;
                }
                source = "backMotionEvent";
                if (target == null) {
                    moduleLog(Log.WARN, TAG, "Missing departing predictive-back target"
                            + ", generation=" + session.generation
                            + ", removeDepartTargetFromMotion=false");
                    return false;
                }
                int mode = readIntFieldOrDefault(target, "mode", -1);
                if (mode != 1) {
                    moduleLog(Log.WARN, TAG, "Rejected non-closing departing back target"
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
                    moduleLog(Log.WARN, TAG, "Invalid return-to-home preview leash"
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
                moduleLog(Log.WARN, TAG, "Failed to resolve return-to-home preview target"
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
                ReturnHomeSession session, BackMotionEvent event,
                boolean terminalCallbackExpected) {
            if (event == null || session.progressFrozen
                    || session.finished.get() != 0 || currentSession != session
                    || !session.previewInitialized || session.nativeHandoffStarted) {
                return;
            }
            session.lastInputProgress = clamp01(event.getProgress());
            if (session.progressAnimatorStarted) {
                dispatchPreviewProgress(
                        session, event, terminalCallbackExpected);
                return;
            }
            if (session.progressAnimator == null || session.progressAnimatorFailed) {
                session.progressAnimatorFailed = true;
                updatePreviewFrame(session, event.getProgress(),
                        event.getTouchY(), terminalCallbackExpected);
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                session.progressAnimatorFailed = true;
                moduleLog(Log.WARN, TAG,
                        "Refused return-home BackProgressAnimator outside main Looper"
                                + ", generation=" + session.generation);
                updatePreviewFrame(session, event.getProgress(),
                        event.getTouchY(), terminalCallbackExpected);
                return;
            }
            session.progressAnimatorStarted = true;
            try {
                session.progressAnimator.onBackStarted(event, smoothedEvent -> {
                    if (session.progressFrozen || session.progressAnimatorFailed
                            || session.finished.get() != 0
                            || currentSession != session || !session.previewInitialized
                            || session.nativeHandoffStarted) {
                        return;
                    }
                    updatePreviewFrame(session, smoothedEvent.getProgress(),
                            smoothedEvent.getTouchY(),
                            terminalCallbackExpected);
                });
                moduleLog(Log.INFO, TAG,
                        "Started AOSP return-home progress smoothing"
                                + ", generation=" + session.generation
                                + ", inputProgress=" + session.lastInputProgress);
            } catch (Throwable throwable) {
                session.progressAnimatorFailed = true;
                try {
                    session.progressAnimator.reset();
                } catch (Throwable ignored) {
                }
                moduleLog(Log.WARN, TAG,
                        "Failed to start AOSP return-home progress smoothing"
                                + ", generation=" + session.generation,
                        throwable);
                updatePreviewFrame(session, event.getProgress(),
                        event.getTouchY(), terminalCallbackExpected);
            }
        }

        protected void dispatchPreviewProgress(
                ReturnHomeSession session, BackMotionEvent event,
                boolean terminalCallbackExpected) {
            if (event == null || session.progressFrozen
                    || session.finished.get() != 0 || currentSession != session
                    || !session.previewInitialized || session.nativeHandoffStarted) {
                return;
            }
            session.lastInputProgress = clamp01(event.getProgress());
            if (session.progressAnimator == null || session.progressAnimatorFailed
                    || !session.progressAnimatorStarted) {
                updatePreviewFrame(session, event.getProgress(),
                        event.getTouchY(), terminalCallbackExpected);
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
                moduleLog(Log.WARN, TAG,
                        "Failed to update AOSP return-home progress smoothing"
                                + ", generation=" + session.generation,
                        throwable);
                updatePreviewFrame(session, event.getProgress(),
                        event.getTouchY(), terminalCallbackExpected);
            }
        }

        protected void updatePreviewFrame(ReturnHomeSession session,
                                          float smoothedProgress, float touchY,
                                          boolean terminalCallbackExpected) {
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
            if (!driveUnifiedNativePreviewFrame(session, false)) {
                rejectUnavailableNativePreview(
                        session, terminalCallbackExpected);
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
                    moduleLog(Log.INFO, TAG,
                            "Stopped AOSP return-home progress smoothing"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason
                                    + ", inputProgress=" + session.lastInputProgress
                                    + ", smoothedProgress="
                                    + session.lastSmoothedProgress);
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
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
            if (!session.unifiedNativePreviewOwned) {
                finishSession(session, reason);
                return;
            }
            if (session.nativeHandoffStarted
                    || session.unifiedNativeCommitPending
                    || session.nativeAnimationStarted) {
                moduleLog(Log.WARN, TAG,
                        "Ignored return-home cancel after native commit"
                                + ", generation=" + session.generation
                                + ", reason=" + reason);
                return;
            }
            startUnifiedNativeCancel(session, reason);
        }

        protected void prepareNativePreviewBackdrop(ReturnHomeSession session) {
            if (session.finished.get() != 0 || currentSession != session
                    || session.nativeHandoffStarted
                    || !isStandardSingleTaskReturnHome(session)) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.INFO, TAG,
                            "Preserved running Xiaomi animation instead of preparing backdrop"
                                    + ", generation=" + session.generation);
                    return;
                }
                session.previewBackdropStateManager = stateManager;
                prepareNativePreviewShortcutLayer(session);
                prepareNativePreviewWallpaper(session);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
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
            float fullBlurProgress = clamp01(
                    dp(RETURN_HOME_PREVIEW_BLUR_DISTANCE_DP) / displayWidth);
            float normalized = fullBlurProgress <= 0.0f
                    ? 1.0f : clamp01(smoothedProgress / fullBlurProgress);
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
                    moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.WARN, TAG,
                            "Recovered Xiaomi blur after predictive write failure"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason,
                            cause);
                } else {
                    moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.INFO, TAG, "Transferred predictive blur ownership to Xiaomi"
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG, "Restored Xiaomi blur after predictive return"
                        + ", generation=" + session.generation
                        + ", reason=" + reason
                        + ", restored=" + session.previewBlurInitialRadius
                        + "/" + session.previewBlurInitialDimming);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
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
                        moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                        moduleLog(Log.WARN, TAG,
                                "Xiaomi CLOSE returned without taking launcher backdrop"
                                        + ", generation=" + session.generation);
                    }
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.INFO, TAG,
                            "Preserved replacement Xiaomi launcher backdrop"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason);
                    return;
                }
                invokeAnyMethod(element, "setTo", new Object[]{homeParams});
                moduleLog(Log.INFO, TAG,
                        "Restored Xiaomi launcher backdrop after predictive return"
                                + ", generation=" + session.generation
                                + ", reason=" + reason);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.INFO, TAG,
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

        protected void onWallpaperCommand(Object element, Object params, boolean animated) {
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
                        moduleLog(Log.INFO, TAG,
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
                        moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
                        "Restored Xiaomi wallpaper after predictive return"
                                + ", generation=" + session.generation
                                + ", reason=" + reason
                                + ", zoom="
                                + session.previewWallpaperHomeZoom);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
                        "Failed to prepare Xiaomi predictive wallpaper"
                                + ", generation=" + session.generation
                                + ", reason=" + reason,
                        cause);
                clearNativePreviewWallpaperReferences(session);
                return;
            }
            moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.INFO, TAG,
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
    }
}
