package dev.codex.miuibackgesturehook.hooks.miuihome;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;
import static dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeReturnHomeHook.*;
import static dev.codex.miuibackgesturehook.data.ReturnHomeData.*;

import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

abstract class MiuiHomeReturnHomeLifecycleImpl
        extends MiuiHomeReturnHomeUnifiedCommitImpl {
    protected abstract class ReturnHomeLifecycleController
        extends MiuiHomeReturnHomeUnifiedCommitImpl.ReturnHomeUnifiedCommitController {
        ReturnHomeLifecycleController(IBinder shellBackAnimation,
                                      ClassLoader classLoader, Context context) {
            super(shellBackAnimation, classLoader, context);
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

        protected boolean standardSignalCanBindSession(
                StandardReturnHomeCommitSignal signal,
                ReturnHomeSession session) {
            if (signal == null || signal.runnerSession == null
                    || session == null || currentSession != session
                    || session.finished.get() != 0
                    || session.unifiedNativeCleanupVerified
                    || signal.runnerSession != session.finishedCallback
                    || signal.arbiterGeneration
                    != miuiHomeSystemUiInputArbiterGeneration
                    || !isStandardSingleTaskReturnHome(session)) {
                return false;
            }
            int closingTaskId = session.unifiedNativeTaskId >= 0
                    ? session.unifiedNativeTaskId
                    : readIntFieldOrDefault(
                    session.closingTarget, "taskId", -1);
            return closingTaskId >= 0 && signal.taskId == closingTaskId;
        }

        protected boolean bindStandardSignalToSession(
                StandardReturnHomeCommitSignal signal,
                ReturnHomeSession session) {
            if (!standardSignalCanBindSession(signal, session)) {
                return false;
            }
            MiuiHomeAcceptedInputToken input = session.acceptedInputIdentity;
            if (input != null) {
                return signal.matchesInput(input);
            }
            session.acceptedInputIdentity = new MiuiHomeAcceptedInputToken(
                    signal.eventId, signal.downTime, signal.deviceId,
                    signal.source, signal.displayId, signal.edge,
                    signal.arbiterGeneration);
            return true;
        }

        protected boolean standardSignalMatchesSession(
                StandardReturnHomeCommitSignal signal,
                ReturnHomeSession session) {
            return standardSignalCanBindSession(signal, session)
                    && signal.matchesInput(session.acceptedInputIdentity);
        }

        protected void discardPendingStandardCommitForRunner(
                IBinder runnerSession, String reason) {
            while (runnerSession != null) {
                StandardReturnHomeCommitSignal pending =
                        pendingStandardCommitSignal.get();
                if (pending == null
                        || pending.runnerSession != runnerSession) {
                    return;
                }
                if (pendingStandardCommitSignal.compareAndSet(
                        pending, null)) {
                    moduleLog(Log.INFO, TAG,
                            "Discarded standard commit for rejected runner"
                                    + ", attempt=" + pending.attempt
                                    + ", taskId=" + pending.taskId
                                    + ", runnerSession="
                                    + shortObject(runnerSession)
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
                if (pending == null) {
                    return;
                }
                if (!bindStandardSignalToSession(pending, session)) {
                    return;
                }
                moduleLog(Log.INFO, TAG,
                        "Bound early standard commit to launcher runner"
                                + ", generation=" + session.generation
                                + ", attempt=" + pending.attempt
                                + ", taskId=" + pending.taskId
                                + ", eventId=" + pending.eventId
                                + ", runnerSession="
                                + shortObject(pending.runnerSession));
                return;
            }
        }

        protected void onStandardShellReturnHomeCommit(
                StandardReturnHomeCommitSignal signal) {
            if (signal == null || !attached) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post(() -> onStandardShellReturnHomeCommit(signal));
                return;
            }
            ReturnHomeSession activeSession = currentSession;
            boolean bindNow = standardSignalCanBindSession(
                    signal, activeSession);
            MiuiHomeAcceptedInputToken latestInput =
                    miuiHomeAcceptedInputIdentity.get();
            boolean frozenInputMatches = bindNow
                    && activeSession.acceptedInputIdentity != null
                    && signal.matchesInput(
                    activeSession.acceptedInputIdentity);
            boolean latestInputMatches = signal.matchesInput(latestInput);
            boolean inputAuthenticated = bindNow
                    ? activeSession.acceptedInputIdentity == null
                    || frozenInputMatches
                    : latestInputMatches;
            if (signal.arbiterGeneration
                    != miuiHomeSystemUiInputArbiterGeneration
                    || signal.runnerSession == null
                    || !inputAuthenticated) {
                moduleLog(Log.WARN, TAG,
                        "Rejected standard commit without an authenticated input owner"
                                + ", attempt=" + signal.attempt
                                + ", taskId=" + signal.taskId
                                + ", eventId=" + signal.eventId
                                + ", latestEventId="
                                + (latestInput == null ? 0
                                : latestInput.eventId)
                                + ", frozenInputMatches="
                                + frozenInputMatches
                                + ", signalGeneration="
                                + signal.arbiterGeneration
                                + ", currentGeneration="
                                + miuiHomeSystemUiInputArbiterGeneration);
                return;
            }
            if (signal.attempt <= lastStandardCommitSignalAttempt) {
                moduleLog(Log.WARN, TAG,
                        "Ignored reordered standard return-home commit signal"
                                + ", attempt=" + signal.attempt
                                + ", lastAttempt="
                                + lastStandardCommitSignalAttempt
                                + ", taskId=" + signal.taskId);
                return;
            }
            lastStandardCommitSignalAttempt = signal.attempt;
            if (bindNow && !bindStandardSignalToSession(
                    signal, activeSession)) {
                return;
            }
            while (true) {
                StandardReturnHomeCommitSignal previous =
                        pendingStandardCommitSignal.get();
                if (previous != null
                        && previous.attempt >= signal.attempt) {
                    moduleLog(Log.WARN, TAG,
                            "Ignored stale standard return-home commit signal"
                                    + ", attempt=" + signal.attempt
                                    + ", activeAttempt="
                                    + previous.attempt
                                    + ", taskId=" + signal.taskId);
                    return;
                }
                if (pendingStandardCommitSignal.compareAndSet(
                        previous, signal)) {
                    break;
                }
            }
            moduleLog(Log.INFO, TAG,
                    "Received authenticated standard return-home commit"
                            + ", attempt=" + signal.attempt
                            + ", taskId=" + signal.taskId
                            + ", transitionDebugId="
                            + signal.transitionDebugId
                            + ", arbiterGeneration="
                            + signal.arbiterGeneration
                            + ", eventId=" + signal.eventId
                            + ", runnerBound=" + bindNow
                            + ", runnerSession="
                            + shortObject(signal.runnerSession));
            if (bindNow) {
                continueUnifiedStandardCommit(activeSession);
            } else {
                moduleLog(Log.INFO, TAG,
                        "Retained authenticated standard commit until runner arrives"
                                + ", attempt="
                                + signal.attempt
                                + ", taskId="
                                + signal.taskId
                                + ", eventId="
                                + signal.eventId);
            }
        }

        protected void continueUnifiedStandardCommit(
                ReturnHomeSession session) {
            StandardReturnHomeCommitSignal signal =
                    pendingStandardCommitSignal.get();
            if (signal == null || signal.elementBoundaryOnly
                    || session == null
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
                    || !standardSignalMatchesSession(signal, session)
                    || signal.taskId != session.unifiedNativeTaskId
                    || session.unifiedNativeCommitTransition != null
                    || !isStandardSingleTaskReturnHome(session)) {
                if (signal.taskId != session.unifiedNativeTaskId
                        || signal.arbiterGeneration
                        != miuiHomeSystemUiInputArbiterGeneration
                        || !standardSignalMatchesSession(signal, session)) {
                    pendingStandardCommitSignal.compareAndSet(signal, null);
                }
                moduleLog(Log.WARN, TAG,
                        "Rejected non-matching standard return-home commit"
                                + ", attempt=" + signal.attempt
                                + ", signalTaskId=" + signal.taskId
                                + ", sessionTaskId="
                                + session.unifiedNativeTaskId
                                + ", signalGeneration="
                                + signal.arbiterGeneration
                                + ", currentGeneration="
                                + miuiHomeSystemUiInputArbiterGeneration
                                + ", sameRunnerSession="
                                + (signal.runnerSession
                                == session.finishedCallback)
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
                moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
                        && !pendingSignal.elementBoundaryOnly
                        && pendingSignal.taskId
                        == session.unifiedNativeTaskId
                        && pendingSignal.runnerSession
                        == session.finishedCallback
                        && pendingSignal.matchesInput(
                        session.acceptedInputIdentity)) {
                    pendingStandardCommitSignal.compareAndSet(
                            pendingSignal, null);
                }
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.ERROR, TAG,
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
            if (!session.unifiedNativePreviewOwned
                    || session.unifiedNativeCancelPending) {
                moduleLog(Log.WARN, TAG,
                        "Rejected return-home commit without unified Xiaomi preview"
                                + ", generation=" + session.generation
                                + ", nativeOwned="
                                + session.unifiedNativePreviewOwned
                                + ", cancelPending="
                                + session.unifiedNativeCancelPending);
                finishSession(session, "nativePreviewUnavailableAtCommit");
                return;
            }
            freezePreviewProgress(session, "commit");
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
                    if (!abandonReplacedUnifiedNativePreview(
                            session, "commit", currentElement,
                            currentIdentity, targetSetChanged)) {
                        moduleLog(Log.ERROR, TAG,
                                "Retained uncertain Xiaomi owner before commit"
                                        + ", generation="
                                        + session.generation);
                    }
                    return;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Could not verify Xiaomi owner before commit"
                                + ", generation=" + session.generation,
                        throwable);
                return;
            }
            if (session.previewBlurOwned) {
                // Revalidate exact ownership at the commit boundary and close any rounding
                // gap before provider code is allowed to replace params. This makes the
                // synchronous-abort identity relaxation cover only provider-owned changes.
                publishNativePreviewBlur(session,
                        session.previewBlurTargetRadius,
                        session.previewBlurTargetDimming, "commit");
            }
            prepareNativePreviewBackdropForCommit(session);
            session.nativeHandoffStarted = true;
            session.unifiedNativeCommitPending = true;
            moduleLog(Log.INFO, TAG,
                    "Held Xiaomi CLOSE_TO_DRAG for real commit transition"
                            + ", generation=" + session.generation
                            + ", animationIdentity="
                            + shortObject(
                            session.unifiedNativeAnimationIdentity)
                            + ", rect=" + session.currentRect);
            if (!session.unifiedNativeCommitReady.compareAndSet(
                    false, true)) {
                moduleLog(Log.ERROR, TAG,
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
        }

        protected boolean onNativeAnimationStart(
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
                    moduleLog(Log.INFO, TAG,
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
                return false;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to capture Xiaomi CLOSE animation identity"
                        + ", generation=" + session.generation, throwable);
                return false;
            }
        }

        protected ReturnHomeLauncherOpenBarrierToken prepareLauncherOpenBarrier(
                Object stateManager, Object[] args) throws Throwable {
            return prepareLauncherOpenBarrier(stateManager, args, false);
        }

        protected ReturnHomeLauncherOpenBarrierToken prepareLauncherOpenBarrier(
                Object stateManager, Object[] args,
                boolean configLocked) throws Throwable {
            if (args == null || args.length != 4
                    || !MIUI_HOME_ICON_CLICK_WITHOUT_RECENT_REASON.equals(args[0])
                    || !(args[1] instanceof Boolean)
                    || !Boolean.TRUE.equals(args[2])
                    || args[3] == null
                    || Looper.myLooper() != Looper.getMainLooper()) {
                return null;
            }
            Class<?> callbackClass = Class.forName(
                    MIUI_HOME_SHELL_TRANSITION_CALLBACK, false, classLoader);
            Object originalCallback = args[3];
            if (!callbackClass.isInstance(originalCallback)) {
                return null;
            }
            ReturnHomeSession session = currentSession;
            if (session != null) {
                adoptConfiguredCommitForInterruption(
                        session, session.nativeWindowElement,
                        "prepareCloseToOpenHandoff");
            }
            UnifiedNativePendingInterruptionSnapshot earlyPending =
                    session == null ? null
                            : session.unifiedNativePendingInterruption.get();
            if (!configLocked && session != null
                    && !session.nativeAnimationStarted
                    && earlyPending != null) {
                synchronized (earlyPending.configLock) {
                    return prepareLauncherOpenBarrier(
                            stateManager, args, true);
                }
            }
            if (!attached || session == null
                    || session.finished.get() != 0
                    || session.cleaned.get() != 0
                    || !session.nativeHandoffStarted
                    || session.stateManager != stateManager
                    || session.nativeWindowElement == null
                    || session.nativeAnimationIdentity == null) {
                return null;
            }
            Object windowElement = session.nativeWindowElement;
            Object currentElement = invokeAnyMethod(
                    stateManager, "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    windowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(windowElement);
            UnifiedNativeAdoptedStandardCommitIdentity standard =
                    session.unifiedNativeAdoptedStandardCommit;
            StandardReturnHomeCommitSignal pendingSignal =
                    pendingStandardCommitSignal.get();
            ReturnHomeElementLeashReuseToken elementBoundary =
                    pendingElementLeashReuse.get();
            boolean elementCandidate = pendingSignal != null
                    && pendingSignal.elementBoundaryOnly;
            StandardReturnHomeCommitSignal signal = elementCandidate
                    ? pendingSignal : standard == null
                    ? null : standard.signal;
            boolean standardOwned = signal != null
                    && !signal.elementBoundaryOnly
                    && standard != null
                    && standard.signal == signal
                    && standard.session == session
                    && standard.generation == session.generation
                    && standard.windowElement
                    == session.nativeWindowElement
                    && standard.animationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && standard.animationIdentity
                    == session.nativeAnimationIdentity
                    && standardSignalMatchesSession(signal, session);
            boolean elementOwned = isExactElementBoundarySignal(
                    session, signal, elementBoundary,
                    currentElement, currentIdentity, currentType);
            if (signal == null || signal.attempt <= 0L
                    || signal.taskId != session.unifiedNativeTaskId
                    || signal.transitionDebugId < 0
                    || (!standardOwned && !elementOwned)) {
                return null;
            }
            Object pendingReference = invokeAnyMethod(
                    stateManager, "getPendingIconViewWeakRef", new Object[0]);
            Object pendingIcon = pendingReference instanceof WeakReference<?>
                    ? ((WeakReference<?>) pendingReference).get() : null;
            boolean verifiedClose = session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && session.nativeAnimationIdentity == currentIdentity
                    && session.nativeAnimationType.equals(currentType)
                    && isReturnHomeNativeCloseType(currentType);
            UnifiedNativePendingInterruptionSnapshot pendingCommitInterruption =
                    session.unifiedNativePendingInterruption.get();
            boolean pendingCommit = !session.nativeAnimationStarted
                    && isExactUnifiedPendingInterruption(
                    session, pendingCommitInterruption,
                    currentElement, currentIdentity, currentType, true);
            boolean sameElement = pendingIcon instanceof View
                    && Boolean.TRUE.equals(invokeAnyMethod(
                    windowElement, "isSameElement",
                    new Object[]{pendingIcon}));
            if (currentElement != windowElement
                    || currentIdentity != session.nativeAnimationIdentity
                    || (!verifiedClose && !pendingCommit)
                    || !(pendingIcon instanceof View)
                    || sameElement != Boolean.TRUE.equals(args[1])) {
                return null;
            }
            boolean nativeParallelRoute = !sameElement;
            if (nativeParallelRoute
                    && (!verifiedClose
                    || Boolean.TRUE.equals(invokeAnyMethod(
                    stateManager, "shouldCancelSurfaceAndView",
                    new Object[]{args[2]}))
                    || Boolean.TRUE.equals(invokeAnyMethod(
                    stateManager, "shouldCancelElementAnim",
                    new Object[0])))) {
                return null;
            }
            invalidatePendingLauncherOpenBarrier("replacementClick");
            ReturnHomeLauncherOpenBarrierToken token =
                    new ReturnHomeLauncherOpenBarrierToken(
                            session, stateManager, windowElement,
                            session.nativeAnimationIdentity,
                            (View) pendingIcon, originalCallback, signal,
                            elementOwned ? elementBoundary : null,
                            pendingCommit ? pendingCommitInterruption : null,
                            nativeParallelRoute);
            token.wrappedCallback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, invocationArgs) ->
                            invokeLauncherOpenBarrierCallback(
                                    token, proxy, method, invocationArgs));
            if (!pendingLauncherOpenBarrier.compareAndSet(null, token)) {
                return null;
            }
            moduleLog(Log.INFO, TAG,
                    "Prepared Xiaomi CLOSE-to-OPEN handoff"
                            + ", generation=" + session.generation
                            + ", attempt=" + signal.attempt
                            + ", taskId=" + signal.taskId
                            + ", transitionDebugId="
                            + signal.transitionDebugId
                            + ", elementBoundary="
                            + signal.elementBoundaryOnly
                            + ", pendingCommit=" + pendingCommit
                            + ", nativeParallelRoute="
                            + nativeParallelRoute
                            + ", animationIdentity="
                            + shortObject(token.animationIdentity)
                            + ", clickedView="
                            + shortObject(token.clickedView));
            return token;
        }

        protected boolean isExactElementBoundarySignal(
                ReturnHomeSession session,
                StandardReturnHomeCommitSignal signal,
                ReturnHomeElementLeashReuseToken token,
                Object currentElement, Object currentIdentity,
                String currentType) {
            return session != null && signal != null
                    && signal.elementBoundaryOnly
                    && token != null
                    && pendingElementLeashReuse.get() == token
                    && currentSession == session
                    && token.session == session
                    && token.generation == session.generation
                    && token.windowElement == session.nativeWindowElement
                    && token.animationIdentity
                    == session.unifiedNativeAnimationIdentity
                    && token.animationIdentity
                    == session.nativeAnimationIdentity
                    && currentElement == token.windowElement
                    && currentIdentity == token.animationIdentity
                    && token.taskId == session.unifiedNativeTaskId
                    && token.taskId == signal.taskId
                    && token.transitionDebugId == signal.transitionDebugId
                    && readTransitionDebugId(token.transitionInfo)
                    == token.transitionDebugId
                    && token.phase.get()
                    == ReturnHomeElementLeashReuseToken.PHASE_ADOPTED
                    && token.startGeometrySeed.get()
                    == ReturnHomeElementLeashReuseToken.SEED_COMMITTED
                    && token.closingLeash == session.closingLeash
                    && token.closingLeash.isValid()
                    && session.unifiedNativeProviderCommitAdopted
                    && session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && "CLOSE_TO_ELEMENT".equals(
                    session.nativeAnimationType)
                    && "CLOSE_TO_ELEMENT".equals(currentType)
                    && standardSignalMatchesSession(signal, session);
        }

        protected boolean armLauncherOpenParallelRoute(
                ReturnHomeLauncherOpenBarrierToken token) throws Throwable {
            if (token == null || pendingLauncherOpenBarrier.get() != token
                    || token.invalidated.get()
                    || currentSession != token.session
                    || token.session.finished.get() != 0) {
                return false;
            }
            UnifiedNativePendingInterruptionSnapshot pending =
                    token.pendingCommitInterruption;
            if (pending != null) {
                synchronized (pending.configLock) {
                    adoptConfiguredCommitForInterruption(
                            token.session, token.windowElement,
                            "armCloseToOpenParallel");
                    Object currentElement = invokeAnyMethod(
                            token.stateManager,
                            "getCurrentWindowElement", new Object[0]);
                    Object currentIdentity = invokeAnyMethod(
                            token.windowElement,
                            "getAnimSymbol", new Object[0]);
                    String currentType = readNativeAnimationType(
                            token.windowElement);
                    if (!isExactUnifiedPendingInterruption(
                            token.session, pending, currentElement,
                            currentIdentity, currentType, true)) {
                        return false;
                    }
                    int mutation = pending.mutation.get();
                    if (mutation
                            != UnifiedNativePendingInterruptionSnapshot
                            .MUTATION_CANCEL_SURFACE
                            && !pending.mutation.compareAndSet(
                            UnifiedNativePendingInterruptionSnapshot
                                    .MUTATION_NONE,
                            UnifiedNativePendingInterruptionSnapshot
                                    .MUTATION_CANCEL_SURFACE)) {
                        return false;
                    }
                }
            }
            token.parallelRoute = true;
            return true;
        }

        protected Object invokeLauncherOpenBarrierCallback(
                ReturnHomeLauncherOpenBarrierToken token, Object proxy,
                Method method, Object[] invocationArgs) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(method.getName())) {
                    return "PredictiveLauncherOpenBarrierCallback{"
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
            if (!"onFinish".equals(method.getName())
                    || method.getParameterCount() != 0) {
                return invokeLauncherOpenCallback(
                        token.originalCallback, method, invocationArgs);
            }
            token.callbackMethod = method;
            token.callbackReceived.set(true);
            if (pendingLauncherOpenBarrier.get() != token
                    || token.invalidated.get()) {
                releaseInvalidatedLauncherOpenBarrierCallback(token);
                return null;
            }
            if (!token.armed.get()
                    && (token.parallelRoute
                    || token.nativeParallelRoute)) {
                try {
                    acceptNativeCloseToOpenBoundary(token);
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "Failed Xiaomi CLOSE-to-OPEN completion boundary"
                                    + ", generation=" + token.generation,
                            throwable);
                }
            }
            if (!token.armed.get()) {
                if (token.completed.compareAndSet(false, true)) {
                    try {
                        return invokeLauncherOpenCallback(
                                token.originalCallback, method,
                                invocationArgs);
                    } finally {
                        pendingLauncherOpenBarrier.compareAndSet(
                                token, null);
                        maybeFinishDeferredControllerAfterConfigAck(
                                "unarmedLauncherOpenBarrier");
                    }
                }
                return null;
            }
            completeLauncherOpenBarrier(token);
            return null;
        }

        protected Object invokeLauncherOpenCallback(
                Object callback, Method method,
                Object[] invocationArgs) throws Throwable {
            try {
                return method.invoke(callback, invocationArgs);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                throw cause == null ? exception : cause;
            }
        }

        protected boolean acceptNativeCloseToOpenBoundary(
                ReturnHomeLauncherOpenBarrierToken token) throws Throwable {
            if (token == null || pendingLauncherOpenBarrier.get() != token
                    || token.invalidated.get()
                    || (!token.parallelRoute
                    && !token.nativeParallelRoute)
                    || token.armed.get()) {
                return false;
            }
            ReturnHomeSession session = token.session;
            Object currentElement = invokeAnyMethod(
                    token.stateManager,
                    "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    token.windowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(token.windowElement);
            boolean elementBoundaryCurrent = token.expectedSignal != null
                    && (token.expectedSignal.elementBoundaryOnly
                    ? isExactElementBoundarySignal(
                    session, token.expectedSignal,
                    token.elementBoundary,
                    currentElement, currentIdentity, currentType)
                    : token.elementBoundary == null);
            boolean surfaceCanceled = Boolean.TRUE.equals(
                    readField(token.windowElement, "mSurfaceCanceled"));
            boolean surfaceCancelExecuted = Boolean.TRUE.equals(
                    readField(token.windowElement,
                            "mSurfaceCanceledExecute"));
            boolean canceled = Boolean.TRUE.equals(
                    readField(token.windowElement, "mCanceled"));
            boolean nativeCallbackConsumed =
                    readField(token.windowElement,
                            "mShellTransitionCallback") == null;
            Object oldListObject = readField(
                    token.stateManager, "windowElementOldList");
            boolean oldElementRecorded = oldListObject instanceof List<?>
                    && ((List<?>) oldListObject).contains(token.windowElement);
            boolean verifiedClose = session.nativeAnimationStarted
                    && session.nativeContinuationVerified
                    && session.nativeAnimationIdentity
                    == token.animationIdentity
                    && session.nativeAnimationType.equals(currentType)
                    && isReturnHomeNativeCloseType(currentType);
            UnifiedNativePendingInterruptionSnapshot pending =
                    token.pendingCommitInterruption;
            boolean pendingCommit = pending != null
                    && !session.nativeAnimationStarted
                    && isExactUnifiedPendingInterruption(
                    session, pending, currentElement,
                    currentIdentity, currentType, false);
            Object launcherTarget = invokeAnyMethod(
                    token.windowElement,
                    "getLauncherTargetView", new Object[0]);
            boolean hasRecentTransition = Boolean.TRUE.equals(
                    invokeAnyMethod(token.windowElement,
                            "hasRecentTransition", new Object[0]));
            boolean reusable = Boolean.TRUE.equals(
                    invokeAnyMethod(token.windowElement,
                            "isReusefulAnimRunning", new Object[0]));
            boolean freshOpenReady = token.nativeParallelRoute
                    || ("CLOSE_TO_HOME".equals(currentType)
                    && launcherTarget == token.clickedView
                    && !hasRecentTransition && !reusable);
            boolean valid = currentSession == session
                    && session.finished.get() == 0
                    && session.generation == token.generation
                    && session.stateManager == token.stateManager
                    && session.nativeWindowElement == token.windowElement
                    && session.nativeAnimationIdentity
                    == token.animationIdentity
                    && currentElement == token.windowElement
                    && currentIdentity == token.animationIdentity
                    && elementBoundaryCurrent
                    && (verifiedClose || pendingCommit)
                    && oldElementRecorded && surfaceCanceled
                    && surfaceCancelExecuted && canceled
                    && nativeCallbackConsumed && freshOpenReady;
            if (!valid) {
                moduleLog(Log.WARN, TAG,
                        "Rejected Xiaomi CLOSE-to-OPEN completion boundary"
                                + ", generation=" + session.generation
                                + ", sameElement="
                                + (currentElement == token.windowElement)
                                + ", sameIdentity="
                                + (currentIdentity == token.animationIdentity)
                                + ", elementBoundaryCurrent="
                                + elementBoundaryCurrent
                                + ", oldElementRecorded="
                                + oldElementRecorded
                                + ", surfaceCanceled=" + surfaceCanceled
                                + ", surfaceCancelExecuted="
                                + surfaceCancelExecuted
                                + ", canceled=" + canceled
                                + ", nativeCallbackConsumed="
                                + nativeCallbackConsumed
                                + ", type=" + currentType
                                + ", verifiedClose=" + verifiedClose
                                + ", pendingCommit=" + pendingCommit
                                + ", nativeParallelRoute="
                                + token.nativeParallelRoute
                                + ", freshOpenReady=" + freshOpenReady);
                return false;
            }
            if (pendingCommit && !consumeUnifiedPendingInterruption(
                    session, pending, "closeToOpenCallback")) {
                return false;
            }
            session.unifiedNativeCommitPending = false;
            if (session.unifiedNativePreviewOwned && !pendingCommit) {
                session.unifiedNativeCleanupVerified = true;
            }
            token.freshOpenReady = true;
            if (!token.armed.compareAndSet(false, true)) {
                return false;
            }
            moduleLog(Log.INFO, TAG,
                    "Accepted Xiaomi CLOSE-to-OPEN completion boundary"
                            + ", generation=" + session.generation
                            + ", type=" + currentType
                            + ", pendingCommit=" + pendingCommit
                            + ", nativeParallelRoute="
                            + token.nativeParallelRoute
                            + ", animationIdentity="
                            + shortObject(token.animationIdentity));
            finishSession(session,
                    "nativeCloseInterruptedForLauncherOpen");
            completeLauncherOpenBarrier(token);
            return true;
        }

        protected boolean armLauncherOpenBarrier(
                ReturnHomeSession session, Object stateManager,
                Object windowElement, Object animationIdentity,
                Object clickedView, String reason) {
            ReturnHomeLauncherOpenBarrierToken token =
                    pendingLauncherOpenBarrier.get();
            if (token == null || token.invalidated.get()
                    || token.parallelRoute
                    || token.session != session
                    || token.stateManager != stateManager
                    || token.windowElement != windowElement
                    || token.animationIdentity != animationIdentity
                    || token.clickedView != clickedView
                    || token.expectedSignal == null
                    || token.expectedSignal.runnerSession
                    != session.finishedCallback
                    || !token.expectedSignal.matchesInput(
                    session.acceptedInputIdentity)
                    || !token.armed.compareAndSet(false, true)) {
                return false;
            }
            moduleLog(Log.INFO, TAG,
                    "Armed Xiaomi launcher OPEN cleanup barrier"
                            + ", generation=" + token.generation
                            + ", attempt=" + token.expectedSignal.attempt
                            + ", taskId=" + token.expectedSignal.taskId
                            + ", transitionDebugId="
                            + token.expectedSignal.transitionDebugId
                            + ", reason=" + reason);
            completeLauncherOpenBarrier(token);
            return true;
        }

        protected void onStandardShellReturnHomeFinished(
                StandardReturnHomeCommitSignal signal) {
            ReturnHomeLauncherOpenBarrierToken token =
                    pendingLauncherOpenBarrier.get();
            if (token == null || signal == null
                    || !matchesReturnHomeSignal(
                    token.expectedSignal, signal)) {
                return;
            }
            token.finishSignal = signal;
            token.finishReceived.set(true);
            completeLauncherOpenBarrier(token);
        }

        protected boolean matchesReturnHomeSignal(
                StandardReturnHomeCommitSignal expected,
                StandardReturnHomeCommitSignal actual) {
            return expected != null && actual != null
                    && actual.attempt == expected.attempt
                    && actual.runnerSession == expected.runnerSession
                    && actual.arbiterGeneration
                    == expected.arbiterGeneration
                    && actual.taskId == expected.taskId
                    && actual.transitionDebugId
                    == expected.transitionDebugId
                    && actual.elementBoundaryOnly
                    == expected.elementBoundaryOnly
                    && actual.eventId == expected.eventId
                    && actual.downTime == expected.downTime
                    && actual.deviceId == expected.deviceId
                    && actual.source == expected.source
                    && actual.displayId == expected.displayId
                    && actual.edge == expected.edge;
        }

        protected void completeLauncherOpenBarrier(
                ReturnHomeLauncherOpenBarrierToken token) {
            if (token == null || !token.armed.get()
                    || !token.callbackReceived.get()
                    || !token.finishReceived.get()) {
                return;
            }
            boolean valid = (attached || deferredControllerReplacement)
                    && !token.invalidated.get()
                    && token.session.finished.get() == 1
                    && token.expectedSignal != null
                    && token.expectedSignal.runnerSession
                    == token.session.finishedCallback
                    && token.expectedSignal.arbiterGeneration
                    == miuiHomeSystemUiInputArbiterGeneration
                    && miuiHomeSystemUiInputArbiterReady
                    && token.expectedSignal.matchesInput(
                    token.session.acceptedInputIdentity)
                    && matchesReturnHomeSignal(
                    token.expectedSignal, token.finishSignal)
                    && (!token.parallelRoute || token.freshOpenReady);
            if (!valid || token.callbackMethod == null) {
                invalidateLauncherOpenBarrier(
                        token, "completionIdentityMismatch", true);
                return;
            }
            if (!token.completed.compareAndSet(false, true)) {
                return;
            }
            try {
                invokeLauncherOpenCallback(token.originalCallback,
                        token.callbackMethod, new Object[0]);
                moduleLog(Log.INFO, TAG,
                        "Released Xiaomi launcher OPEN after Shell cleanup"
                                + ", generation=" + token.generation
                                + ", attempt="
                                + token.expectedSignal.attempt
                                + ", taskId="
                                + token.expectedSignal.taskId
                                + ", transitionDebugId="
                                + token.expectedSignal.transitionDebugId);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed delayed Xiaomi launcher OPEN callback"
                                + ", generation=" + token.generation,
                        throwable);
            } finally {
                pendingLauncherOpenBarrier.compareAndSet(token, null);
                maybeFinishDeferredControllerAfterConfigAck(
                        "launcherOpenBarrier");
            }
        }

        protected void invalidateLauncherOpenBarrier(
                ReturnHomeLauncherOpenBarrierToken token, String reason) {
            invalidateLauncherOpenBarrier(token, reason, false);
        }

        protected void invalidateLauncherOpenBarrier(
                ReturnHomeLauncherOpenBarrierToken token, String reason,
                boolean releaseCallback) {
            if (token == null) {
                return;
            }
            if (releaseCallback) {
                token.releaseOnInvalidation = true;
            }
            if (token.invalidated.getAndSet(true)) {
                releaseInvalidatedLauncherOpenBarrierCallback(token);
                return;
            }
            pendingLauncherOpenBarrier.compareAndSet(token, null);
            if (releaseCallback) {
                releaseInvalidatedLauncherOpenBarrierCallback(token);
            } else {
                token.completed.set(true);
            }
            moduleLog(Log.INFO, TAG,
                    "Invalidated Xiaomi launcher OPEN cleanup barrier"
                            + ", generation=" + token.generation
                            + ", armed=" + token.armed.get()
                            + ", callbackReceived="
                            + token.callbackReceived.get()
                            + ", finishReceived="
                            + token.finishReceived.get()
                            + ", parallelRoute=" + token.parallelRoute
                            + ", nativeParallelRoute="
                            + token.nativeParallelRoute
                            + ", freshOpenReady=" + token.freshOpenReady
                            + ", releaseCallback="
                            + releaseCallback
                            + ", reason=" + reason);
            maybeFinishDeferredControllerAfterConfigAck(
                    "launcherOpenBarrierInvalidated:" + reason);
        }

        protected void releaseInvalidatedLauncherOpenBarrierCallback(
                ReturnHomeLauncherOpenBarrierToken token) {
            Method callbackMethod = token == null
                    ? null : token.callbackMethod;
            if (token == null || !token.releaseOnInvalidation
                    || !token.callbackReceived.get()
                    || callbackMethod == null
                    || !token.completed.compareAndSet(false, true)) {
                return;
            }
            try {
                invokeLauncherOpenCallback(token.originalCallback,
                        callbackMethod, new Object[0]);
                moduleLog(Log.INFO, TAG,
                        "Released Xiaomi launcher OPEN callback after barrier invalidation"
                                + ", generation=" + token.generation
                                + ", attempt="
                                + token.expectedSignal.attempt);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed Xiaomi launcher OPEN callback after barrier invalidation"
                                + ", generation=" + token.generation,
                        throwable);
            }
        }

        protected void invalidatePendingLauncherOpenBarrier(String reason) {
            invalidatePendingLauncherOpenBarrier(reason, false);
        }

        protected void invalidatePendingLauncherOpenBarrier(
                String reason, boolean releaseCallback) {
            ReturnHomeLauncherOpenBarrierToken token =
                    pendingLauncherOpenBarrier.get();
            if (token != null) {
                invalidateLauncherOpenBarrier(
                        token, reason, releaseCallback);
            }
        }

        protected boolean shouldRouteSameIconThroughNativeParallel(
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
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
            boolean noPendingHandoff = pendingDirectCancel.get() == null;
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
                moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.INFO, TAG,
                    "Routed same-icon predictive CLOSE through Xiaomi parallel launcher path"
                            + ", generation=" + session.generation
                            + ", type=" + currentType
                            + ", animationIdentity="
                            + shortObject(session.nativeAnimationIdentity)
                            + ", windowElement=" + shortObject(windowElement)
                            + ", pendingIcon=" + shortObject(pendingIcon));
            return true;
        }

        protected boolean shouldForceFreshOpenAfterSameIconClose(
                Object stateManager, Object oldWindowElement,
                Object clickedView) throws Throwable {
            ReturnHomeLauncherOpenBarrierToken token =
                    pendingLauncherOpenBarrier.get();
            if (token == null || Looper.myLooper() != Looper.getMainLooper()
                    || !token.parallelRoute || !token.armed.get()
                    || !token.freshOpenReady
                    || !token.callbackReceived.get()
                    || !token.finishReceived.get()
                    || token.stateManager != stateManager
                    || token.windowElement != oldWindowElement
                    || token.clickedView != clickedView
                    || token.freshOpenConsumed.get()) {
                return false;
            }
            Object currentElement = invokeAnyMethod(
                    stateManager, "getCurrentWindowElement", new Object[0]);
            Object currentIdentity = invokeAnyMethod(
                    oldWindowElement, "getAnimSymbol", new Object[0]);
            String currentType = readNativeAnimationType(oldWindowElement);
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
            boolean valid = pendingLauncherOpenBarrier.get() == token
                    && token.completed.get()
                    && token.session.finished.get() == 1
                    && currentElement == oldWindowElement
                    && currentIdentity == token.animationIdentity
                    && "CLOSE_TO_HOME".equals(currentType)
                    && oldElementRecorded
                    && !hasRecentTransition && !reusable
                    && surfaceCanceled && surfaceCancelExecuted
                    && canceled;
            if (!valid) {
                moduleLog(Log.WARN, TAG,
                        "Rejected stale Xiaomi CLOSE-to-OPEN handoff"
                                + ", generation=" + token.generation
                                + ", sameElement="
                                + (currentElement == oldWindowElement)
                                + ", sameIdentity="
                                + (currentIdentity == token.animationIdentity)
                                + ", type=" + currentType
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
            if (!token.freshOpenConsumed.compareAndSet(false, true)) {
                return false;
            }
            moduleLog(Log.INFO, TAG,
                    "Forced Xiaomi fresh OPEN for non-reusable same-icon CLOSE"
                            + ", generation=" + token.generation
                            + ", animationIdentity="
                            + shortObject(token.animationIdentity)
                            + ", windowElement="
                            + shortObject(token.windowElement)
                            + ", clickedView="
                            + shortObject(token.clickedView));
            return true;
        }

        protected ReturnHomeDirectCancelToken prepareNativeDirectCancel(
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
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
            moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG,
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
            boolean barrierArmed = armLauncherOpenBarrier(
                    session, token.stateManager, token.windowElement,
                    token.animationIdentity, token.pendingIcon,
                    "directCancelCallback");
            notifyRemoteAnimationFinished(session.finishedCallback,
                    "nativeDirectCancelBeforeLauncherOpen");
            moduleLog(Log.INFO, TAG,
                    "Finished Shell runner before direct same-icon Xiaomi OPEN"
                            + ", generation=" + session.generation
                            + ", type=" + currentType
                            + ", launcherBarrier=" + barrierArmed
                            + ", animationIdentity="
                            + shortObject(token.animationIdentity)
                            + ", windowElement="
                            + shortObject(token.windowElement));
        }

        protected void invalidateNativeDirectCancel(ReturnHomeDirectCancelToken token,
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
            moduleLog(Log.INFO, TAG,
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
                cleanupFinishedSession(token.session, reason);
            } else {
                handler.post(() -> cleanupFinishedSession(
                        token.session, reason));
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
                    moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.INFO, TAG,
                        "Accepted direct same-icon Xiaomi CLOSE end"
                                + ", generation=" + token.generation
                                + ", type="
                                + token.session.nativeAnimationType
                                + ", animationIdentity="
                                + shortObject(token.animationIdentity));
                handler.post(() -> cleanupNativeDirectCancel(
                        token, "nativeDirectCancelAnimationEnd"));
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
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

        protected void captureNativeAnimationEndBeforeListener(
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
                    moduleLog(Log.INFO, TAG,
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
            moduleLog(Log.INFO, TAG,
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

        protected boolean onNativeAnimationEnd(
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
                        moduleLog(Log.ERROR, TAG,
                                "Missing Xiaomi pre-listener finish snapshot; retained owner"
                                        + ", generation="
                                        + session.generation
                                        + ", animationIdentity="
                                        + shortObject(animationIdentity));
                        return true;
                    }
                    if (session.unifiedNativeCancelPending) {
                        session.unifiedNativeCancelEndObserved = true;
                        moduleLog(Log.INFO, TAG,
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
                    moduleLog(Log.INFO, TAG,
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
                return false;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to verify Xiaomi CLOSE animation end"
                        + ", generation=" + session.generation, throwable);
                return false;
            }
        }

        protected void finishSession(ReturnHomeSession session, String reason) {
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
                moduleLog(Log.WARN, TAG,
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
            invalidatePendingDirectCancel(
                    session, "finish:" + reason, false);
            invalidateElementTransitionContinuity(
                    session, "finish:" + reason,
                    !session.unifiedNativeOwnerAbandoned);
            if (Looper.myLooper() != Looper.getMainLooper()) {
                notifyRemoteAnimationFinished(session.finishedCallback, reason);
                handler.post(() -> cleanupFinishedSession(
                        session, reason));
                return;
            }
            notifyRemoteAnimationFinished(session.finishedCallback, reason);
            cleanupFinishedSession(session, reason);
        }

        protected void cleanupFinishedSession(ReturnHomeSession session, String reason) {
            if (session == null || !session.cleaned.compareAndSet(0, 1)) {
                return;
            }
            freezePreviewProgress(session, "cleanup:" + reason);
            Runnable timeout = session.nativeTimeout;
            if (timeout != null) {
                handler.removeCallbacks(timeout);
            }
            if (!session.unifiedNativeOwnerAbandoned) {
                restoreNativePreviewBlur(session, "cleanup:" + reason);
                restoreNativePreviewBackdrop(session, "cleanup:" + reason);
            }
            MiuiHomeLocalHandoffToken handoffToken = session.localHandoffToken;
            if (handoffToken != null) {
                miuiHomeLocalHandoffToken.compareAndSet(handoffToken, null);
            }
            if (!session.unifiedNativeOwnerAbandoned
                    && session.nativeStatusPublished
                    && session.nativeWindowAnimContext != null) {
                try {
                    Object currentStatus = invokeAnyMethod(
                            session.nativeWindowAnimContext,
                            "getLocalAnimLastStatus", new Object[0]);
                    if (currentStatus == session.nativePublishedStatus) {
                        invokeAnyMethod(session.nativeWindowAnimContext,
                                "setLocalAnimLastStatus", new Object[]{null});
                    } else if (currentStatus != null) {
                        moduleLog(Log.INFO, TAG, "Preserved replacement Xiaomi handoff status"
                                + ", generation=" + session.generation
                                + ", published="
                                + shortObject(session.nativePublishedStatus)
                                + ", current=" + shortObject(currentStatus));
                    }
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG, "Failed to clear unused Xiaomi handoff status"
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
            session.unifiedNativeAdoptedStandardCommit = null;
            StandardReturnHomeCommitSignal pendingStandard =
                    pendingStandardCommitSignal.get();
            if (pendingStandard != null
                    && pendingStandard.runnerSession
                    == session.finishedCallback) {
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
            if (!session.unifiedNativeOwnerAbandoned
                    && session.unifiedNativePreviewSpringEndHeld) {
                try {
                    setUnifiedNativePreviewSpringEndEnabled(
                            session, true, "cleanup:" + reason);
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "Failed to restore Xiaomi predictive spring end"
                                    + ", generation=" + session.generation
                                    + ", reason=" + reason,
                            throwable);
                }
            }
            session.unifiedNativeCleanupVerified = true;
            session.unifiedNativeTargetSet = null;
            session.unifiedNativeClipHelper = null;
            if (!session.unifiedNativeOwnerAbandoned
                    && session.previewLeash != null
                    && session.previewLeash != session.closingLeash) {
                try {
                    session.previewLeash.release();
                } catch (Throwable ignored) {
                }
            }
            session.previewTarget = null;
            session.previewLeash = null;
            if (!session.unifiedNativeOwnerAbandoned) {
                releaseTargets(session.apps);
                releaseTargets(session.wallpapers);
                releaseTargets(session.nonApps);
            }
            if (currentSession == session) {
                currentSession = null;
            }
            moduleLog(Log.INFO, TAG, "Finished MiuiHome return-to-home runner"
                    + ", generation=" + session.generation
                    + ", reason=" + reason
                    + ", nativeHandoff=" + session.nativeHandoffStarted
                    + ", nativeStarted=" + session.nativeAnimationStarted
                    + ", unifiedOwner="
                    + session.unifiedNativePreviewOwned
                    + ", ownerAbandoned="
                    + session.unifiedNativeOwnerAbandoned);
            dispatchDeferredControllerFinish(reason);
        }
    }
}
