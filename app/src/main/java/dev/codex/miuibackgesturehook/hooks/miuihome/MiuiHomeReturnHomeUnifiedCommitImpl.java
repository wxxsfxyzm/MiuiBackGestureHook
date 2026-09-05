package dev.codex.miuibackgesturehook.hooks.miuihome;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;
import static dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeOpenInterruptionHook.*;
import static dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeReturnHomeHook.*;
import static dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeStateBridgeHook.*;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

abstract class MiuiHomeReturnHomeUnifiedCommitImpl
        extends MiuiHomeReturnHomeUnifiedImpl {
    protected abstract class ReturnHomeUnifiedCommitController
        extends MiuiHomeReturnHomeUnifiedImpl.ReturnHomeUnifiedController {
        ReturnHomeUnifiedCommitController(IBinder shellBackAnimation,
                                    ClassLoader classLoader, Context context) {
            super(shellBackAnimation, classLoader, context);
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
                    && standard.signal.runnerSession
                    == session.finishedCallback
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.INFO, TAG,
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
            moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
            moduleLog(Log.INFO, TAG,
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
                            moduleLog(Log.ERROR, TAG,
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
            moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
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
            moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
            moduleLog(adopted ? Log.INFO : Log.WARN, TAG,
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
                    fullscreen = isUnifiedNativeFullscreen(
                            session, rectObject);
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

        protected boolean isUnifiedNativeFullscreen(
                ReturnHomeSession session, Object rectObject)
                throws Exception {
            if (!(rectObject instanceof RectF)) {
                return false;
            }
            RectF nativeFullscreen = toUnifiedNativeHomeRect(
                    session.unifiedNativeCurrentRotation,
                    session.unifiedNativeHomeRotation,
                    new RectF(session.startRect));
            return rectsNear((RectF) rectObject, nativeFullscreen,
                    Math.max(2.0f, dp(2.0f)));
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
                    fullscreen = isUnifiedNativeFullscreen(
                            session, rectObject);
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
            moduleLog(Log.INFO, TAG,
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
            session.unifiedNativeAdoptedStandardCommit =
                    new UnifiedNativeAdoptedStandardCommitIdentity(
                            session, token);
            completeUnifiedNativeCommitHandoff(
                    session, snapshot.animationIdentity,
                    snapshot.actualType);
            if (!snapshot.phase.compareAndSet(
                    UnifiedNativeFinishSnapshot.PHASE_PENDING,
                    UnifiedNativeFinishSnapshot.PHASE_CONSUMED)) {
                return session.unifiedNativeCleanupVerified;
            }
            session.unifiedNativeCleanupVerified = true;
            moduleLog(Log.INFO, TAG,
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
                    finishSession(session, reason);
                }
            });
        }

        protected boolean consumeUnifiedNativeFinishSnapshot(
                ReturnHomeSession session, String reason) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                moduleLog(Log.ERROR, TAG,
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
                    moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
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
                            moduleLog(Log.ERROR, TAG,
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
                    moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                    moduleLog(terminationQueued ? Log.WARN : Log.ERROR, TAG,
                            "Terminating exact drag after failed commit animTo"
                                    + ", generation="
                                    + session.generation
                                    + ", attempt="
                                    + inspection.attempt
                                    + ", terminationQueued="
                                    + terminationQueued);
                    return;
                }
                moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
            moduleLog(Log.INFO, TAG,
                    "Transferred predictive launcher state after Xiaomi commit"
                            + ", generation=" + session.generation
                            + ", type=" + animationType);
        }

        protected void invalidateElementTransitionContinuity(
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
                        moduleLog(Log.WARN, TAG,
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

        protected void ensureUnifiedNativePreviewReflection()
                throws Exception {
            if (nativeTargetSetConstructor != null
                    && nativeWindowAnimParamsConstructor != null
                    && nativeRectFParamsConstructor != null
                    && nativeCornerRadiiConstructor != null
                    && nativeClipAnimationHelperConstructor != null
                    && nativeGestureAnimExecutorMethod != null
                    && nativeCoordinateTransformMethod != null
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
            Class<?> coordinateTransformsClass = Class.forName(
                    "com.miui.home.launcher.util.CoordinateTransforms",
                    false, classLoader);

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
            Method coordinateTransform = coordinateTransformsClass.getDeclaredMethod(
                    "transformCoordinate", int.class, int.class, RectF.class);
            targetSet.setAccessible(true);
            windowParams.setAccessible(true);
            rectParams.setAccessible(true);
            radii.setAccessible(true);
            clip.setAccessible(true);
            gestureAnimExecutor.setAccessible(true);
            coordinateTransform.setAccessible(true);
            Class<? extends Enum> enumClass =
                    (Class<? extends Enum>) animTypeClass.asSubclass(
                            Enum.class);

            nativeTargetSetConstructor = targetSet;
            nativeWindowAnimParamsConstructor = windowParams;
            nativeRectFParamsConstructor = rectParams;
            nativeCornerRadiiConstructor = radii;
            nativeClipAnimationHelperConstructor = clip;
            nativeGestureAnimExecutorMethod = gestureAnimExecutor;
            nativeCoordinateTransformMethod = coordinateTransform;
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
            moduleLog(Log.INFO, TAG,
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
            RectF nativeTargetRect = toUnifiedNativeHomeRect(
                    session.unifiedNativeCurrentRotation,
                    session.unifiedNativeHomeRotation, targetRect);
            RectF nativeStartRect = explicitStartRect == null ? null
                    : toUnifiedNativeHomeRect(
                    session.unifiedNativeCurrentRotation,
                    session.unifiedNativeHomeRotation, explicitStartRect);
            Object startRadii = nativeCornerRadiiConstructor.newInstance(
                    Float.valueOf(session.currentCornerRadius));
            Object endRadii = nativeCornerRadiiConstructor.newInstance(
                    Float.valueOf(endRadius));
            Object windowParams =
                    nativeWindowAnimParamsConstructor.newInstance(
                            nativeStartRect, nativeTargetRect,
                            startRadii, endRadii,
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

        protected RectF toUnifiedNativeHomeRect(
                int currentRotation, int homeRotation, RectF displayRect)
                throws Exception {
            ensureUnifiedNativePreviewReflection();
            Object result = nativeCoordinateTransformMethod.invoke(
                    null, Integer.valueOf(currentRotation),
                    Integer.valueOf(homeRotation), new RectF(displayRect));
            if (!(result instanceof RectF) || ((RectF) result).isEmpty()) {
                throw new IllegalStateException(
                        "invalid Xiaomi Home-coordinate transform");
            }
            return new RectF((RectF) result);
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
                int taskId = readIntFieldOrDefault(
                        session.closingTarget, "taskId", -1);
                int closingDisplay = readIntFieldOrDefault(
                        closingTaskInfo, "displayId", -1);
                int openingDisplay = readIntFieldOrDefault(
                        openingTaskInfo, "displayId", -1);
                Object closingRotationObject = invokeAnyMethod(
                        closingConfiguration, "getRotation", new Object[0]);
                int closingRotation = closingRotationObject instanceof Number
                        ? ((Number) closingRotationObject).intValue() : -1;
                Class<?> applicationClass = Class.forName(
                        MIUI_HOME_APPLICATION, false, classLoader);
                Method getLauncher = applicationClass.getDeclaredMethod(
                        "getLauncher");
                getLauncher.setAccessible(true);
                Object launcher = getLauncher.invoke(null);
                Class<?> baseLauncherClass = Class.forName(
                        MIUI_HOME_BASE_LAUNCHER, false, classLoader);
                Method getCurrentDisplayRotation =
                        baseLauncherClass.getDeclaredMethod(
                                "getCurrentDisplayRotation");
                Method getRootViewRect = baseLauncherClass.getDeclaredMethod(
                        "getRootViewRect");
                getCurrentDisplayRotation.setAccessible(true);
                getRootViewRect.setAccessible(true);
                Object launcherRotationObject = launcher == null ? null
                        : getCurrentDisplayRotation.invoke(launcher);
                int launcherRotation = launcherRotationObject instanceof Number
                        ? ((Number) launcherRotationObject).intValue() : -1;
                WindowManager windowManager = launcher instanceof Context
                        ? (WindowManager) ((Context) launcher).getSystemService(
                        Context.WINDOW_SERVICE) : null;
                int homeRotation = windowManager == null
                        || windowManager.getDefaultDisplay() == null
                        ? -1 : windowManager.getDefaultDisplay().getRotation();
                Object homeBoundsObject = launcher == null ? null
                        : getRootViewRect.invoke(launcher);
                Rect homeBounds = homeBoundsObject instanceof Rect
                        ? new Rect((Rect) homeBoundsObject) : null;
                boolean exactShape = taskId >= 0 && closingDisplay >= 0
                        && closingDisplay == openingDisplay
                        && closingRotation >= 0
                        && launcherRotation == closingRotation
                        && homeRotation == 0
                        && homeBounds != null && !homeBounds.isEmpty()
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
                RectF nativeStartRect = toUnifiedNativeHomeRect(
                        launcherRotation, homeRotation,
                        new RectF(session.startRect));
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
                invokeAnyMethod(clipHelper, "updateHomeStack",
                        new Object[]{homeBounds});
                invokeAnyMethod(clipHelper, "prepareAnimation",
                        new Object[]{Boolean.FALSE});
                Rect nativeStartBounds = new Rect();
                nativeStartRect.round(nativeStartBounds);
                invokeAnyMethod(clipHelper, "updateTargetRect",
                        new Object[]{nativeStartBounds});
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
                    moduleLog(Log.INFO, TAG,
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
                session.unifiedNativeCurrentRotation = launcherRotation;
                session.unifiedNativeHomeRotation = homeRotation;
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
                Object currentElement = invokeAnyMethod(stateManager,
                        "getCurrentWindowElement", new Object[0]);
                Object currentIdentity = invokeAnyMethod(windowElement,
                        "getAnimSymbol", new Object[0]);
                boolean targetSetChanged = invokeAnyMethod(windowElement,
                        "getRemoteTargetSet", new Object[0]) != targetSet;
                if (currentElement != windowElement
                        || currentIdentity != animationIdentity
                        || targetSetChanged) {
                    if (abandonReplacedUnifiedNativePreview(
                            session, "previewStart", currentElement,
                            currentIdentity, targetSetChanged)) {
                        return false;
                    }
                    throw new IllegalStateException(
                            "Xiaomi predictive owner changed during start");
                }
                moduleLog(Log.INFO, TAG,
                        "Unified predictive preview with Xiaomi WindowElement"
                                + ", generation=" + session.generation
                                + ", taskId=" + taskId
                                + ", currentRotation="
                                + launcherRotation
                                + ", homeRotation=" + homeRotation
                                + ", animationIdentity="
                                + shortObject(animationIdentity)
                                + ", leash="
                                + shortObject(session.closingLeash));
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to establish unified Xiaomi predictive owner"
                                + ", generation=" + session.generation,
                        throwable);
                if (session.unifiedNativePreviewOwned) {
                    startUnifiedNativeCancel(
                            session, "prepareFailed");
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
                        || (!firstFrame && invokeAnyMethod(
                        session.nativeWindowElement,
                        "getRemoteTargetSet", new Object[0])
                        != session.unifiedNativeTargetSet)
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
                moduleLog(Log.WARN, TAG,
                        "Failed to drive unified Xiaomi predictive frame"
                                + ", generation=" + session.generation
                                + ", rect=" + session.currentRect,
                        throwable);
                return false;
            }
        }

        protected boolean abandonReplacedUnifiedNativePreview(
                ReturnHomeSession session, String reason,
                Object currentElement, Object currentIdentity,
                boolean targetSetChanged) {
            if (session == null || currentSession != session
                    || session.finished.get() != 0
                    || !session.unifiedNativePreviewOwned
                    || session.unifiedNativeCleanupVerified
                    || session.nativeHandoffStarted
                    || session.unifiedNativeCommitPending
                    || session.nativeAnimationStarted
                    || (currentElement == session.nativeWindowElement
                    && currentIdentity
                    == session.unifiedNativeAnimationIdentity
                    && !targetSetChanged)) {
                return false;
            }
            // The replacement is proven, so old-session cleanup must not touch the current
            // Xiaomi element or its animation-end callback gate.
            session.unifiedNativePreviewSpringEndHeld = false;
            session.unifiedNativeOwnerAbandoned = true;
            session.unifiedNativeCleanupVerified = true;
            moduleLog(Log.ERROR, TAG,
                    "Abandoned replaced Xiaomi predictive owner"
                            + ", generation=" + session.generation
                            + ", reason=" + reason
                            + ", sameElement="
                            + (currentElement == session.nativeWindowElement)
                            + ", sameIdentity="
                            + (currentIdentity
                            == session.unifiedNativeAnimationIdentity)
                            + ", targetSetChanged=" + targetSetChanged);
            handler.post(() -> finishSession(
                    session, "nativePreviewOwnerReplaced:" + reason));
            return true;
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
                moduleLog(Log.WARN, TAG,
                        "Queued exact Xiaomi pending-commit termination"
                                + ", generation=" + session.generation
                                + ", attempt=" + attempt
                                + ", reason=" + reason);
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                    moduleLog(Log.WARN, TAG,
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
            moduleLog(cancelEntered ? Log.WARN : Log.ERROR, TAG,
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
                boolean targetSetChanged = invokeAnyMethod(
                        session.nativeWindowElement,
                        "getRemoteTargetSet", new Object[0])
                        != session.unifiedNativeTargetSet;
                if (currentElement != session.nativeWindowElement
                        || currentIdentity
                        != session.unifiedNativeAnimationIdentity
                        || targetSetChanged) {
                    if (abandonReplacedUnifiedNativePreview(
                            session, "cancel:" + reason, currentElement,
                            currentIdentity, targetSetChanged)) {
                        return true;
                    }
                    throw new IllegalStateException(
                            "Xiaomi owner changed before cancel"
                                    + ", sameElement="
                                    + (currentElement
                                    == session.nativeWindowElement)
                                    + ", sameIdentity="
                                    + (currentIdentity
                                    == session.unifiedNativeAnimationIdentity)
                                    + ", targetSetChanged="
                                    + targetSetChanged);
                }
                String currentType = readNativeAnimationType(
                        session.nativeWindowElement);
                if (exactExternalPendingCommit) {
                    externalTransition =
                            session.unifiedNativeCommitTransition;
                    if (!"CLOSE_TO_DRAG".equals(currentType)
                            || externalTransition != null) {
                        moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                    moduleLog(Log.ERROR, TAG,
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
            boolean currentElementRead = false;
            try {
                currentElement = invokeAnyMethod(
                        session.stateManager,
                        "getCurrentWindowElement", new Object[0]);
                currentElementRead = true;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Could not verify timed-out Xiaomi cancel element"
                                + ", generation="
                                + session.generation
                                + ", attempt="
                                + inspection.attempt,
                        throwable);
            }
            moduleLog(Log.ERROR, TAG,
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
            if (currentElementRead
                    && finishUnifiedNativeCancelAtTerminalOwner(
                    session, inspection, currentElement,
                    animToEpoch, reason)) {
                return;
            }
            scheduleUnifiedNativeCancelTimeout(
                    session, animToEpoch, reason);
        }

        /**
         * Xiaomi can complete APP_TO_APP cancellation after clearing the remote target and
         * before dispatching the StateManager animation-end callback.  The owner is still
         * unambiguous at this boundary: the same configured animTo epoch and animation
         * identity report a stopped, fullscreen, finish-complete APP_TO_APP animation.
         * Consume that terminal state without rearming the native owner.
         */
        protected boolean finishUnifiedNativeCancelAtTerminalOwner(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection,
                Object currentElement, long animToEpoch, String reason) {
            UnifiedNativeConfiguredAnimToSnapshot configured = session == null
                    ? null : session.unifiedNativeConfiguredAnimTo.get();
            if (session == null || inspection == null
                    || inspection.failure != null
                    || currentSession != session
                    || session.finished.get() != 0
                    || session.unifiedNativeCleanupVerified
                    || !session.unifiedNativeCancelPending
                    || session.unifiedNativeCancelAnimToEpoch != animToEpoch
                    || session.unifiedNativeActiveAnimToEpoch != animToEpoch
                    || !inspection.sameAnimation
                    || inspection.animationIdentity
                    != session.unifiedNativeAnimationIdentity
                    || !"APP_TO_APP".equals(inspection.actualType)
                    || inspection.running
                    || !inspection.finishComplete
                    || !inspection.fullscreen
                    || !isExactUnifiedConfiguredAnimTo(
                    session, configured, session.nativeWindowElement,
                    inspection.animationIdentity, "APP_TO_APP")) {
                return false;
            }

            String currentElementType = null;
            if (currentElement != null) {
                try {
                    currentElementType = readNativeAnimationType(currentElement);
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "Could not verify current Xiaomi element at cancel terminal"
                                    + ", generation=" + session.generation
                                    + ", animToEpoch=" + animToEpoch,
                            throwable);
                    return false;
                }
                // A reused CLOSE-to-OPEN must be handed to the launcher OPEN owner first.
                if (currentElement == session.nativeWindowElement
                        && isMiuiHomeLauncherOpenType(currentElementType)) {
                    return false;
                }
            }

            Runnable timeout = session.unifiedNativeCancelTimeout;
            if (timeout != null) {
                handler.removeCallbacks(timeout);
            }
            session.unifiedNativeCancelTimeout = null;
            session.unifiedNativeCancelPending = false;
            session.unifiedNativeCancelRetargeted = false;
            session.unifiedNativeCancelEndObserved = true;

            boolean sameElement = currentElement == session.nativeWindowElement;
            if (!sameElement) {
                // The old WindowElement is terminal, but the current StateManager element
                // may already belong to Xiaomi's replacement/open path.  Do not restore
                // the old surface, callback gate, blur, or target set over that owner.
                session.unifiedNativePreviewSpringEndHeld = false;
                session.unifiedNativeOwnerAbandoned = true;
            }
            session.unifiedNativeCleanupVerified = true;
            moduleLog(Log.WARN, TAG,
                    "Finished Xiaomi cancel at terminal native owner without StateManager end"
                            + ", generation=" + session.generation
                            + ", attempt=" + inspection.attempt
                            + ", animToEpoch=" + animToEpoch
                            + ", sameElement=" + sameElement
                            + ", currentElementType=" + currentElementType
                            + ", exactTarget=" + inspection.exactTarget
                            + ", running=" + inspection.running
                            + ", finishComplete=" + inspection.finishComplete
                            + ", fullscreen=" + inspection.fullscreen);
            finishSession(session, "cancelTerminalOwnerCompleted:" + reason);
            return true;
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
            moduleLog(Log.INFO, TAG,
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

        protected void finishUnifiedCancelForReusedOpen(
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
            // The same WindowElement has already been adopted by Xiaomi's OPEN owner.  The
            // return-home session must finish without restoring or releasing that Surface.
            session.unifiedNativeOwnerAbandoned = true;
            session.unifiedNativePreviewSpringEndHeld = false;
            session.unifiedNativeCleanupVerified = true;
            moduleLog(Log.INFO, TAG,
                    "Finished cancelled return-home owner at reused launcher OPEN"
                            + ", generation=" + session.generation
                            + ", animToEpoch=" + configured.animToEpoch
                            + ", animationIdentity="
                            + shortObject(animationIdentity));
            finishSession(session, "cancelReusedForLauncherOpen");
        }
    }
}
