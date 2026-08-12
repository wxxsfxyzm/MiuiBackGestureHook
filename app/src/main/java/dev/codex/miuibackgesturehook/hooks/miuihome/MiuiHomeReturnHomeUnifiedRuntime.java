package dev.codex.miuibackgesturehook.hooks.miuihome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

abstract class MiuiHomeReturnHomeUnifiedRuntime
        extends MiuiHomeReturnHomePreviewRuntime {
    protected abstract class ReturnHomeUnifiedController
            extends MiuiHomeReturnHomePreviewRuntime.ReturnHomePreviewController {
        ReturnHomeUnifiedController(IBinder shellBackAnimation,
                                    ClassLoader classLoader, Context context) {
            super(shellBackAnimation, classLoader, context);
        }

        protected void markUnifiedCommitAnimToEntering(
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
                moduleLog(Log.INFO, TAG,
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
            moduleLog(Log.INFO, TAG,
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

        protected boolean onUnifiedCommitAnimToEntryFailed(
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
                    moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
            session.unifiedNativeAdoptedStandardCommit = null;
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
                moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                    moduleLog(Log.ERROR, TAG,
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
                                        + snapshot.reason);
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

        protected Object beginUnifiedNativeAnimToConfigHook(Object params) {
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
            moduleLog(previous == UNIFIED_CONFIG_HOOK_RUNNING
                            ? Log.INFO : Log.WARN,
                    TAG,
                    "Entered Xiaomi animTo config hook"
                            + ", state=" + previous
                            + ", params=" + shortObject(params));
            return ownerToken;
        }

        protected void finishUnifiedNativeAnimToConfigHook(
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
            moduleLog(failure == null && paramsExact ? Log.INFO : Log.ERROR,
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
                    || pendingLauncherOpenBarrier.get() != null
                    || pendingWidgetOpenBarrier.get() != null
                    || !pendingUnifiedInterruptedAnimToConfigs.isEmpty()) {
                return;
            }
            handler.post(() -> dispatchDeferredControllerFinish("interruptedConfigAck:" + reason));
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
                        moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
            moduleLog(Log.INFO, TAG,
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
            moduleLog(Log.INFO, TAG,
                    "Acknowledged normally applied Xiaomi animTo config"
                            + ", generation=" + snapshot.generation
                            + ", ownerAttempt=" + snapshot.ownerAttempt
                            + ", animToEpoch=" + snapshot.animToEpoch
                            + ", requestedType=" + snapshot.requestedType
                            + ", reason=" + reason);
            maybeFinishDeferredControllerAfterConfigAck(reason);
        }

        protected void onUnifiedNativeAnimToConfigHookCompleted(
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
            moduleLog(Log.ERROR, TAG,
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

        protected Object resolveUnifiedAnimToConfigLock(Object params) {
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

        protected boolean shouldSkipInterruptedUnifiedAnimToConfig(
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
                moduleLog(Log.ERROR, TAG,
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
            moduleLog(Log.INFO, TAG,
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

        protected void onUnifiedNativeAnimToConfigured(
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
                        moduleLog(Log.ERROR, TAG,
                                "Could not retain Xiaomi listener gate during cancel-surface interruption"
                                        + ", generation="
                                        + session.generation
                                        + ", animToEpoch=" + epoch,
                                throwable);
                    }
                }
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.ERROR, TAG,
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

        protected boolean isExactUnconfiguredCancelledCommitFinish(
                ReturnHomeSession session, Object windowElement,
                Object animationIdentity, String actualType) throws Throwable {
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified
                    || !session.nativeHandoffStarted
                    || !session.unifiedNativeCommitPending
                    || !session.unifiedNativeCommitReady.get()
                    || session.nativeAnimationStarted
                    || session.unifiedNativeProviderCommitAdopted
                    || session.stateManager == null
                    || session.nativeWindowElement != windowElement
                    || session.nativeAnimationIdentity != animationIdentity
                    || session.unifiedNativeAnimationIdentity
                    != animationIdentity
                    || !"CLOSE_TO_DRAG".equals(
                    session.nativeAnimationType)
                    || (!"CLOSE_TO_HOME".equals(actualType)
                    && !"CLOSE_TO_HOME_CENTER".equals(actualType))
                    || session.unifiedNativeActiveAnimToEpoch != 0L
                    || session.unifiedNativeConfiguredAnimTo.get() != null
                    || session.unifiedNativeCommitTransition != null
                    || session.unifiedNativeStandardCommit != null
                    || session.unifiedNativePendingInterruption.get() != null
                    || session.unifiedNativeProvisionalCommit.get() != null
                    || session.unifiedNativeTerminalFailure.get() != null
                    || session.unifiedNativeCommitEndObserved) {
                return false;
            }
            Object currentElement = invokeAnyMethod(
                    session.stateManager, "getCurrentWindowElement",
                    new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            Object targetSet = invokeAnyMethod(
                    windowElement, "getRemoteTargetSet", new Object[0]);
            return currentElement == windowElement
                    && currentIdentity == animationIdentity
                    && resolveUnifiedNativeClosingTarget(
                    session, targetSet) != null
                    && Boolean.TRUE.equals(readField(
                    windowElement, "mCanceled"))
                    && !Boolean.TRUE.equals(readField(
                    windowElement, "mDisableStateManagerListener"))
                    && !Boolean.TRUE.equals(readField(
                    windowElement, "mUseShellAnimListener"))
                    && !Boolean.TRUE.equals(readField(
                    windowElement, "couldExecuteShellAnimEnd"))
                    && !Boolean.TRUE.equals(readField(
                    windowElement, "mFinishComplete"))
                    && readField(windowElement,
                    "mShellTransitionCallback") != null;
        }

        protected UnifiedNativeFinishDispatchToken beginUnifiedNativeFinishDispatch(
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
                        && (isExactUnifiedConfiguredAnimTo(
                        session, configured, windowElement,
                        currentIdentity, actualType)
                        || (configured == null
                        && isExactUnconfiguredCancelledCommitFinish(
                        session, windowElement, currentIdentity,
                        actualType)));
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
                moduleLog(Log.INFO, TAG,
                        "Admitted Xiaomi final finish source"
                                + ", generation=" + session.generation
                                + ", dispatchId=" + dispatchId
                                + ", animToEpoch="
                                + (configured == null ? 0L
                                : configured.animToEpoch)
                                + ", terminalCancelFallback="
                                + (configured == null)
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
                moduleLog(Log.WARN, TAG,
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

        protected void abortUnifiedNativeFinishDispatch(
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
                moduleLog(Log.ERROR, TAG,
                        "Could not close Xiaomi listener gate after finish-source abort"
                                + ", generation=" + token.generation
                                + ", dispatchId=" + token.dispatchId
                                + ", reason=" + reason,
                        throwable);
            }
        }

        protected Boolean consumeUnifiedNativeFinishDispatch(
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
                    moduleLog(Log.ERROR, TAG,
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
                boolean terminalCancelFallback = token.allowed
                        && token.configured == null;
                exact = token.allowed
                        && token.windowElement == windowElement
                        && token.animationIdentity == currentIdentity
                        && currentSession == session
                        && session.finished.get() == 0
                        && !session.unifiedNativeCleanupVerified
                        && session.unifiedNativeConfiguredAnimTo.get()
                        == token.configured
                        && (isExactUnifiedConfiguredAnimTo(
                        session, token.configured,
                        windowElement, currentIdentity, actualType)
                        || (terminalCancelFallback
                        && isExactUnconfiguredCancelledCommitFinish(
                        session, windowElement, currentIdentity,
                        actualType)));
                if (exact) {
                    verifyUnifiedStateManagerListenerGate(
                            session, false,
                            "finishApply:" + token.dispatchId);
                    token.applyAccepted = true;
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
                moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.INFO, TAG,
                    "Admitted Xiaomi final finish apply"
                            + ", generation=" + token.generation
                            + ", dispatchId=" + token.dispatchId
                            + ", animToEpoch="
                            + (token.configured == null ? 0L
                            : token.configured.animToEpoch)
                            + ", terminalCancelFallback="
                            + (token.configured == null)
                            + ", type=" + actualType);
            return Boolean.TRUE;
        }

        protected void completeUnconfiguredCancelledCommitFinish(
                UnifiedNativeFinishDispatchToken token) {
            if (token == null || !token.allowed
                    || token.configured != null) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post(() ->
                        completeUnconfiguredCancelledCommitFinish(token));
                return;
            }
            if (!token.applyAccepted) {
                return;
            }
            ReturnHomeSession session = token.session;
            UnifiedNativeFinishSnapshot snapshot =
                    session.unifiedNativeFinishSnapshot.get();
            boolean exact = currentSession == session
                    && session.finished.get() == 0
                    && !session.unifiedNativeCleanupVerified
                    && session.nativeHandoffStarted
                    && session.unifiedNativeCommitPending
                    && !session.nativeAnimationStarted
                    && session.nativeWindowElement == token.windowElement
                    && session.nativeAnimationIdentity
                    == token.animationIdentity
                    && session.unifiedNativeAnimationIdentity
                    == token.animationIdentity
                    && session.unifiedNativeActiveAnimToEpoch == 0L
                    && session.unifiedNativeConfiguredAnimTo.get() == null
                    && session.unifiedNativeCommitTransition == null
                    && session.unifiedNativeStandardCommit == null
                    && session.unifiedNativePendingInterruption.get() == null
                    && session.unifiedNativeCommitEndObserved
                    && snapshot != null
                    && snapshot.phase.get()
                    == UnifiedNativeFinishSnapshot.PHASE_PENDING
                    && ("CLOSE_TO_HOME".equals(snapshot.actualType)
                    || "CLOSE_TO_HOME_CENTER".equals(
                    snapshot.actualType))
                    && isExactUnifiedNativeFinishSnapshot(
                    session, snapshot);
            if (!exact || !snapshot.phase.compareAndSet(
                    UnifiedNativeFinishSnapshot.PHASE_PENDING,
                    UnifiedNativeFinishSnapshot.PHASE_CONSUMED)) {
                moduleLog(Log.ERROR, TAG,
                        "Retained rejected unconfigured Xiaomi cancel finish"
                                + ", generation=" + token.generation
                                + ", dispatchId=" + token.dispatchId
                                + ", commitEndObserved="
                                + session.unifiedNativeCommitEndObserved
                                + ", hasSnapshot=" + (snapshot != null));
                return;
            }
            session.unifiedNativeCommitPending = false;
            session.unifiedNativeCleanupVerified = true;
            moduleLog(Log.WARN, TAG,
                    "Finished unconfigured cancelled Xiaomi commit owner"
                            + ", generation=" + token.generation
                            + ", dispatchId=" + token.dispatchId
                            + ", type=" + snapshot.actualType
                            + ", animationIdentity="
                            + shortObject(token.animationIdentity));
            finishSession(session,
                    "unconfiguredNativeCancelFinish");
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
                moduleLog(Log.INFO, TAG,
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

        protected void markUnifiedCommitAnimToReturned(
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
                moduleLog(Log.INFO, TAG,
                        "Discarded previous drag finish from commit animTo call"
                                + ", generation=" + session.generation
                                + ", animToEpoch=" + epoch
                                + ", animationIdentity="
                                + shortObject(snapshot.animationIdentity));
            }
        }

        protected void prepareUnifiedHandoffBeforeAnimTo(
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
            moduleLog(Log.INFO, TAG,
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

        protected Object takeLocalHandoffStatus(Object implementor, Object params) {
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
                moduleLog(Log.INFO, TAG, "Supplied predictive handoff to Xiaomi local animator"
                        + ", generation=" + token.generation
                        + ", rect=" + session.currentRect
                        + ", radius=" + session.currentCornerRadius);
                return token.status;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to supply Xiaomi local predictive handoff"
                        + ", token=" + shortObject(miuiHomeLocalHandoffToken.get()),
                        throwable);
                return null;
            }
        }

        protected void discardLocalHandoffStatus(Object implementor, Object params,
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
                moduleLog(Log.INFO, TAG, "Preserved Xiaomi native local handoff"
                        + ", generation=" + token.generation
                        + ", reason=" + reason);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to discard module local handoff"
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

        protected void observeUnifiedCommitTransition(
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
            Object infoTypeObject = readTransitionInfoType(info);
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
                moduleLog(Log.INFO, TAG,
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
            moduleLog(Log.INFO, TAG,
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

        protected boolean invalidateUnifiedCommitTransition(
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
            moduleLog(Log.WARN, TAG,
                    "Invalidated failed Xiaomi commit transition injection"
                            + ", generation=" + session.generation
                            + ", debugId="
                            + transition.transitionDebugId
                            + ", reason=" + reason);
            return true;
        }

        protected boolean prepareElementTransitionContinuity(
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
            Object infoTypeObject = readTransitionInfoType(info);
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
            Object changesObject = readTransitionInfoChanges(info);
            if (!(changesObject instanceof List<?>)
                    || ((List<?>) changesObject).size() != 2) {
                return false;
            }
            Object elementChange = null;
            Object appChange = null;
            SurfaceControl elementLeash = null;
            SurfaceControl appLeash = null;
            for (Object change : (List<?>) changesObject) {
                Object modeObject = readTransitionChangeMode(change);
                Object flagsObject = readTransitionChangeFlags(change);
                int mode = modeObject instanceof Number
                        ? ((Number) modeObject).intValue() : -1;
                int flags = flagsObject instanceof Number
                        ? ((Number) flagsObject).intValue() : 0;
                Object taskInfo = readTransitionChangeTaskInfo(change);
                Object leashObject = readTransitionChangeLeash(change);
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
                Object startDisplayObject = readTransitionChangeStartDisplayId(change);
                Object endDisplayObject = readTransitionChangeEndDisplayId(change);
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
            Object appStartBounds = readTransitionChangeStartAbsBounds(appChange);
            Object appEndBounds = readTransitionChangeEndAbsBounds(appChange);
            Object elementEndBounds = readTransitionChangeEndAbsBounds(elementChange);
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
            moduleLog(Log.INFO, TAG,
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

        protected void hideElementBoundaryProviderFloatingIcon(
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
            moduleLog(Log.INFO, TAG,
                    "Retained native Xiaomi floating-icon lifecycle without drawing"
                            + ", generation=" + session.generation
                            + ", transitionDebugId="
                            + session.unifiedNativeProviderBoundaryDebugId
                            + ", type=" + typeName);
        }

        protected void rearmElementLeashAfterNativeClear(Object helper)
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
                moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.WARN, TAG,
                            "Failed to roll back rejected predictive leash"
                                    + ", generation=" + token.generation,
                            rollbackFailure);
                }
                throw throwable;
            }
        }

        protected boolean hasEligibleNativeGeometrySession() {
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
            moduleLog(Log.WARN, TAG,
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

        protected ReturnHomeNativeGeometrySnapshot prepareNativeGeometryBeforeAnimUpdate(
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
                if (session.unifiedNativeCurrentRotation
                        != session.unifiedNativeHomeRotation
                        && isReturnHomeNativeCloseType(typeName)) {
                    session.nativeGeometrySnapshot.set(null);
                    return null;
                }
                RectF nativeCurrentRect = new RectF((RectF) currentRectObject);
                Object surfaceRectObject = invokeAnyMethod(
                        windowElement, "getSurfaceRotationRect",
                        new Object[]{nativeCurrentRect});
                if (!(surfaceRectObject instanceof RectF)) {
                    throw new IllegalStateException(
                            "missing Xiaomi surface-space geometry");
                }
                RectF currentRect = new RectF((RectF) surfaceRectObject);
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

        protected ReturnHomeNativeGeometrySnapshot captureNativeGeometryFromSurfaceParams(
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
                // Unrotated frames may retain their guarded anim-update fallback.
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

        protected Object resolveNativeGeometryFrameApplyLock(
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
                    || crop.width() > fullscreen.width()
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

        protected void armElementAndClosingLeashStartGeometry(
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
                moduleLog(Log.WARN, TAG,
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
                Object startBoundsObject = readTransitionChangeStartAbsBounds(change);
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
                moduleLog(Log.INFO, TAG,
                        "Armed predictive closing geometry for transition apply"
                                + ", generation=" + token.generation
                                + ", taskId=" + token.taskId
                                + ", transitionDebugId="
                                + token.transitionDebugId);
            } catch (Throwable throwable) {
                token.startGeometrySeed.compareAndSet(
                        ReturnHomeElementLeashReuseToken.SEEDING,
                        ReturnHomeElementLeashReuseToken.SEED_INVALID);
                moduleLog(Log.WARN, TAG,
                        "Rejected element/closing transition-start geometry arm"
                                + ", generation=" + token.generation
                                + ", taskId=" + token.taskId
                                + ", transitionDebugId="
                                + token.transitionDebugId,
                        throwable);
            }
        }

        protected Object resolveStartGeometryApplyLock(
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
                moduleLog(Log.WARN, TAG,
                        "Failed to resolve return-home start transaction apply lock"
                                + ", generation=" + token.generation
                                + ", taskId=" + token.taskId,
                        throwable);
                return null;
            }
        }

        protected void refreshStartGeometryAtApply(Object transaction) {
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
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.WARN, TAG,
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

        protected void finishStartGeometryApply(
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
            moduleLog(applied && changed ? Log.INFO : Log.WARN, TAG,
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
                            moduleLog(Log.WARN, TAG,
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

        protected void adoptElementTransitionIfStarted(
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
                moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.INFO, TAG,
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
            moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
            session.unifiedNativeAdoptedStandardCommit =
                    new UnifiedNativeAdoptedStandardCommitIdentity(
                            session, token);
            handler.post(() -> completeUnifiedNativeCommitHandoff(
                    session, inspection.animationIdentity,
                    inspection.actualType));
            scheduleUnifiedNativeEndTimeout(session);
            moduleLog(Log.INFO, TAG,
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
                    && signal.runnerSession == session.finishedCallback
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
    }
}
