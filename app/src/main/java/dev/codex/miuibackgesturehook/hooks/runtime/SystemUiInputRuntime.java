package dev.codex.miuibackgesturehook.hooks.runtime;

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

public abstract class SystemUiInputRuntime extends HookRuntimeCore {
    protected abstract void sendAuthenticatedMiuiHomeOpenBreakCommand(
            Context context, long generation, long attemptId,
            SystemUiBackGestureDriver driver);

    protected final Map<Object, NativeBackInputMonitor> nativeInputMonitors =
            Collections.synchronizedMap(new WeakHashMap<>());

    protected NativeBackInputMonitor createNativeBackInputMonitor(Context context,
                                                                Object edgeBackGestureHandler, Object controller, Object backAnimationImpl)
            throws Exception {
        InputManager inputManager = context.getSystemService(InputManager.class);
        int displayId = readIntFieldOrDefault(
                edgeBackGestureHandler, "mDisplayId", 0);
        Object monitor = invokeAnyMethod(inputManager, "monitorGestureInput",
                new Object[]{"miui-aosp-back", Integer.valueOf(displayId)});
        if (!(monitor instanceof InputMonitor)) {
            throw new IllegalStateException("monitorGestureInput returned "
                    + shortObject(monitor));
        }
        Object inputChannel = ((InputMonitor) monitor).getInputChannel();
        if (!(inputChannel instanceof InputChannel)) {
            throw new IllegalStateException("InputMonitor channel is "
                    + shortObject(inputChannel));
        }
        return new NativeBackInputMonitor(context, edgeBackGestureHandler, controller,
                backAnimationImpl, (InputMonitor) monitor, (InputChannel) inputChannel,
                displayId);
    }

    protected final class NativeBackInputMonitor extends InputEventReceiver {
        protected final Context context;
        protected final Object edgeBackGestureHandler;
        protected final InputMonitor inputMonitor;
        protected final SystemUiBackGestureDriver driver;
        protected final int displayId;
        protected boolean gestureCandidate;
        protected boolean launcherOpenBreakCandidate;
        protected long launcherOpenBreakGenerationCandidate;
        protected boolean launcherShadeCandidate;
        protected boolean launcherDrawerCandidate;
        protected boolean launcherEditingCandidate;
        protected boolean miuiHomeInputAccepted;
        protected boolean pilfered;
        protected boolean waitingForTransientBarsAtDown;
        protected boolean arbiterAttached;
        protected int activeEdge;
        protected int downEventId;
        protected int downDeviceId = Integer.MIN_VALUE;
        protected int downSource;
        protected int downDisplayId = Integer.MIN_VALUE;
        protected float downX;
        protected float downY;
        protected long downTime = Long.MIN_VALUE;
        protected MotionEvent pendingDownEvent;
        protected MotionEvent pendingMotionEvent;

        protected NativeBackInputMonitor(Context context, Object edgeBackGestureHandler,
                                       Object controller, Object backAnimationImpl, InputMonitor inputMonitor,
                                       InputChannel inputChannel, int displayId)
                throws Exception {
            super(inputChannel, Looper.getMainLooper());
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.inputMonitor = inputMonitor;
            this.displayId = displayId;
            this.driver = new SystemUiBackGestureDriver(context, edgeBackGestureHandler,
                    controller, backAnimationImpl);
        }

        void attach() {
            if (!arbiterAttached) {
                arbiterAttached = true;
                driver.onInputMonitorAttached();
                onSystemUiInputMonitorAttached(context);
            }
            log(Log.INFO, TAG, "Native SystemUI back input receiver attached"
                    + ", inputModel=miuihome-accepted-token");
        }

        void detach() {
            resetCandidate();
            driver.detach();
            try {
                dispose();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispose native back input receiver", throwable);
            }
            try {
                inputMonitor.dispose();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispose native back input monitor", throwable);
            }
            if (arbiterAttached) {
                arbiterAttached = false;
                onSystemUiInputMonitorDetached(context);
            }
            log(Log.INFO, TAG, "Native SystemUI back input receiver detached");
        }

        void updateBackAnimation(Object newBackAnimationImpl) throws Exception {
            driver.updateBackAnimation(newBackAnimationImpl);
        }

        boolean blocksHotReload() {
            return driver.blocksHotReload();
        }

        String describeActiveShellSession() {
            return driver.describeActiveShellSession();
        }

        Runnable captureShellAnimationCompletion(
                Object finishedController, Object currentTracker,
                Object queuedTracker, Object navigation, String reason) {
            return driver.captureShellAnimationCompletion(
                    finishedController, currentTracker, queuedTracker,
                    navigation, reason);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                if (event instanceof MotionEvent) {
                    handled = handleMotionEvent((MotionEvent) event);
                }
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Native SystemUI back input failed", throwable);
                resetCandidate();
            } finally {
                finishInputEvent(event, handled);
            }
        }

        protected boolean handleMotionEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return onNativeDown(event);
                case MotionEvent.ACTION_MOVE:
                    return onNativeMove(event);
                case MotionEvent.ACTION_UP:
                    return onNativeUp(event, true);
                case MotionEvent.ACTION_CANCEL:
                    return onNativeUp(event, false);
                default:
                    return gestureCandidate;
            }
        }

        protected boolean onNativeDown(MotionEvent event) {
            resetCandidate();
            int edge = edgeForDown(event);
            if (edge < 0 || !canStartBackGesture(event, edge)) {
                return false;
            }
            gestureCandidate = true;
            activeEdge = edge;
            downX = event.getRawX();
            downY = event.getRawY();
            downTime = event.getDownTime();
            downDeviceId = event.getDeviceId();
            downSource = event.getSource();
            try {
                downEventId = readMotionEventId(event);
                downDisplayId = readMotionEventDisplayId(event);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Cannot identify pending MiuiHome DOWN", throwable);
                resetCandidate();
                return false;
            }
            pendingDownEvent = MotionEvent.obtain(event);
            log(Log.INFO, TAG, "Native SystemUI back candidate awaiting MiuiHome acceptance"
                    + ", eventId=" + downEventId
                    + ", downTime=" + downTime
                    + ", launcherOpenBreak=" + launcherOpenBreakCandidate
                    + ", launcherOpenBreakGeneration="
                    + launcherOpenBreakGenerationCandidate
                    + ", launcherShade=" + launcherShadeCandidate
                    + ", launcherDrawer=" + launcherDrawerCandidate
                    + ", launcherEditing=" + launcherEditingCandidate
                    + ", inputModel=miuihome-accepted-token"
                    + ", displayId=" + displayId
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            MiuiHomeAcceptedInputToken token = acceptedInputToken.get();
            if (token != null && matchesMiuiHomeInput(token)
                    && acceptedInputToken.compareAndSet(token, null)) {
                acceptMiuiHomeInput(token);
            }
            return false;
        }

        protected boolean onNativeMove(MotionEvent event) {
            if (!gestureCandidate) {
                return false;
            }
            if (!miuiHomeInputAccepted) {
                replacePendingMotionEvent(event);
                MiuiHomeAcceptedInputToken token = acceptedInputToken.get();
                if (token != null && matchesMiuiHomeInput(token)
                        && acceptedInputToken.compareAndSet(token, null)) {
                    acceptMiuiHomeInput(token);
                }
                return pilfered;
            }
            if (nativeTransientBarsClaimedGesture()) {
                return yieldToNativeTransientBars(event, "move");
            }
            float distance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            float verticalDistance = Math.abs(event.getRawY() - downY);
            if (!pilfered && distance <= 0.0f && verticalDistance > dp(PILFER_THRESHOLD_DP)) {
                cancelNativeCandidate(event, "moved toward edge or vertical before pilfer");
                return false;
            }
            if (!pilfered && verticalDistance > Math.max(dp(PILFER_THRESHOLD_DP),
                    Math.max(0.0f, distance) * 1.5f)) {
                cancelNativeCandidate(event, "vertical gesture before pilfer");
                return false;
            }
            if (!pilfered && driver.isRecentsVisualOnlyGesture()) {
                // MiuiHome can report Overview before WMS exposes Launcher as the back target.
                // Keep the native panel responsive, but leave the real input stream untouched
                // and never retry this gesture against a later navigation target.
                if (!driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate)) {
                    resetCandidate();
                    return false;
                }
                return false;
            }
            if (!pilfered && driver.isGestureSuppressed()) {
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate);
                return false;
            }
            if (!pilfered && distance > dp(PILFER_THRESHOLD_DP)) {
                pilferPointers(distance);
                if (!pilfered) {
                    cancelNativeCandidate(event, "failed to pilfer outward gesture");
                    return false;
                }
            }
            if (!driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                    launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                    launcherDrawerCandidate,
                    launcherEditingCandidate)) {
                resetCandidate();
                return false;
            }
            return pilfered;
        }

        boolean acceptMiuiHomeInput(MiuiHomeAcceptedInputToken token) {
            if (!matchesMiuiHomeInput(token)) {
                return false;
            }
            miuiHomeInputAccepted = true;
            MotionEvent down = pendingDownEvent;
            pendingDownEvent = null;
            try {
                if (down == null || !driver.handleTouch(down, activeEdge,
                        launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate,
                        launcherShadeCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate)) {
                    log(Log.INFO, TAG, "MiuiHome accepted DOWN but SystemUI path declined"
                            + ", eventId=" + token.eventId
                            + ", edge=" + token.edge);
                    resetCandidate();
                    return true;
                }
                driver.bindAcceptedInput(token);
                log(Log.INFO, TAG, "Matched MiuiHome accepted input token"
                        + ", eventId=" + token.eventId
                        + ", downTime=" + token.downTime
                        + ", displayId=" + token.displayId
                        + ", edge=" + token.edge
                        + ", generation=" + token.generation);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to start accepted MiuiHome input", throwable);
                resetCandidate();
                return true;
            } finally {
                if (down != null) {
                    down.recycle();
                }
            }
            MotionEvent pending = pendingMotionEvent;
            pendingMotionEvent = null;
            if (pending != null) {
                try {
                    onNativeMove(pending);
                } finally {
                    pending.recycle();
                }
            }
            return true;
        }

        protected boolean matchesMiuiHomeInput(MiuiHomeAcceptedInputToken token) {
            return token != null
                    && !token.isExpired()
                    && gestureCandidate
                    && !miuiHomeInputAccepted
                    && token.generation == systemUiInputArbiterGeneration
                    && token.eventId == downEventId
                    && token.downTime == downTime
                    && token.deviceId == downDeviceId
                    && token.source == downSource
                    && token.displayId == downDisplayId
                    && token.displayId == displayId
                    && token.edge == activeEdge;
        }

        protected void replacePendingMotionEvent(MotionEvent event) {
            MotionEvent old = pendingMotionEvent;
            pendingMotionEvent = MotionEvent.obtain(event);
            if (old != null) {
                old.recycle();
            }
        }

        protected void pilferPointers(float distance) {
            try {
                inputMonitor.pilferPointers();
                pilfered = true;
                log(Log.INFO, TAG, "Native SystemUI back pilfered pointers"
                        + ", distance=" + distance + ", edge=" + activeEdge);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to pilfer native back pointers", throwable);
            }
        }

        protected void cancelNativeCandidate(MotionEvent event, String reason) {
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate);
                cancel.recycle();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to cancel native back candidate", throwable);
            }
            log(Log.INFO, TAG, "Cancelled native SystemUI back candidate before pilfer"
                    + ", reason=" + reason
                    + ", edge=" + activeEdge
                    + ", x=" + event.getRawX()
                    + ", y=" + event.getRawY());
            resetCandidate();
        }

        protected boolean onNativeUp(MotionEvent event, boolean allowTrigger) {
            if (!gestureCandidate) {
                return false;
            }
            if (!miuiHomeInputAccepted) {
                resetCandidate();
                return false;
            }
            if (nativeTransientBarsClaimedGesture()) {
                return yieldToNativeTransientBars(event, "release");
            }
            if (driver.isRecentsVisualOnlyGesture()) {
                // Let BackPanelController finish its local animation. No Shell navigation is
                // active, and the monitor must not claim this input stream.
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate);
            } else if (allowTrigger && pilfered) {
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate);
            } else {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate);
                cancel.recycle();
            }
            boolean handled = pilfered;
            resetCandidate();
            return handled;
        }

        protected boolean nativeTransientBarsClaimedGesture() {
            return waitingForTransientBarsAtDown && isNavBarShownTransiently();
        }

        protected boolean yieldToNativeTransientBars(MotionEvent event, String phase) {
            boolean handled = pilfered;
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate);
                cancel.recycle();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to cancel Shell back after native transient-bars claim",
                        throwable);
            }
            log(Log.INFO, TAG, "Yielded immersive side gesture to native transient bars"
                    + ", phase=" + phase
                    + ", inputPilfered=" + handled
                    + ", downTime=" + downTime
                    + ", edge=" + activeEdge
                    + ", x=" + event.getRawX()
                    + ", y=" + event.getRawY());
            resetCandidate();
            return handled;
        }

        protected int edgeForDown(MotionEvent event) {
            float x = event.getRawX();
            float displayWidth = currentDisplayWidth();
            EdgeWidthSnapshot widths = readEdgeWidthSnapshot(
                    edgeBackGestureHandler,
                    context.getResources().getDisplayMetrics().density);
            if (x < widths.leftTouchWidth) {
                return EDGE_LEFT;
            }
            if (x >= displayWidth - widths.rightTouchWidth) {
                return EDGE_RIGHT;
            }
            return -1;
        }

        protected float currentDisplayWidth() {
            try {
                return Math.max(1.0f, context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getBounds().width());
            } catch (Throwable ignored) {
                return Math.max(1.0f,
                        context.getResources().getDisplayMetrics().widthPixels);
            }
        }

        protected boolean canStartBackGesture(MotionEvent event, int edge) {
            if (event.getPointerCount() != 1) {
                return false;
            }
            if (!isNativeBackInputActive()) {
                return false;
            }
            ComponentName topActivity = findTopActivity();
            if (topActivity == null) {
                log(Log.WARN, TAG, "Rejected native back because top activity is unknown"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
                return false;
            }
            boolean launcherPackage = MIUI_HOME.equals(topActivity.getPackageName());
            boolean launcherHome = launcherPackage
                    && isLauncherHomeComponent(topActivity);
            boolean launcherShade = launcherHome && displayId == 0
                    && isMiuiShadeExpanded();
            boolean launcherOpenBreak = displayId == 0
                    && !launcherShade
                    && !miuiOverviewVisible
                    && miuiLauncherOpenBreakAvailable
                    && miuiLauncherOpenBreakGeneration != 0L
                    && launcherOpenBreakCommandsInFlight.get() == 0;
            boolean launcherDrawer = launcherHome
                    && !launcherShade
                    && miuiDrawerVisible
                    && !miuiOverviewVisible
                    && !launcherOpenBreak;
            boolean launcherEditing = launcherHome
                    && !launcherShade
                    && miuiLauncherEditing
                    && !miuiOverviewVisible
                    && !launcherOpenBreak
                    && !launcherDrawer;
            if (launcherHome && !miuiOverviewVisible
                    && !launcherOpenBreak && !launcherShade
                    && !launcherDrawer && !launcherEditing) {
                log(Log.INFO, TAG, "Ignored native back on launcher Home"
                        + ", topActivity=" + topActivity.flattenToShortString()
                        + ", overviewVisible=false"
                        + ", launcherShade=false"
                        + ", launcherDrawer=false"
                        + ", launcherEditing=false"
                        + ", launcherOpenBreakAvailable="
                        + miuiLauncherOpenBreakAvailable
                        + ", commandsInFlight="
                        + launcherOpenBreakCommandsInFlight.get()
                        + ", generation=" + miuiLauncherOpenBreakGeneration
                        + ", displayId=" + displayId);
                return false;
            }
            if (launcherPackage && !launcherHome) {
                log(Log.INFO, TAG, "Accepted non-Home MiuiHome activity as a normal back target"
                        + ", topActivity=" + topActivity.flattenToShortString()
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherShade) {
                log(Log.INFO, TAG,
                        "Accepted native back for NotificationShade over MiuiHome Home"
                                + ", requireShellCallback=true"
                                + ", displayId=" + displayId
                                + ", edge=" + edge);
            }
            if (launcherDrawer) {
                log(Log.INFO, TAG, "Accepted native back in MiuiHome app drawer"
                        + ", drawerVisible=true"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherEditing) {
                log(Log.INFO, TAG, "Accepted native back in MiuiHome editing surface"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherOpenBreak) {
                log(Log.INFO, TAG, "Accepted native back during launcher OPEN animation"
                        + ", launcherOpenBreakAvailable=true"
                        + ", launcherHome=" + launcherHome
                        + ", generation=" + miuiLauncherOpenBreakGeneration
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            Long sysUiStateFlags = readSystemUiStateFlags();
            boolean navBarHidden = sysUiStateFlags != null
                    ? (sysUiStateFlags.longValue() & SYSUI_STATE_NAV_BAR_HIDDEN) != 0L
                    : isNavigationBarHidden();
            boolean navBarShownTransiently = isNavBarShownTransiently();
            boolean allowGestureIgnoringBarVisibility = sysUiStateFlags != null
                    && (sysUiStateFlags.longValue()
                    & SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY) != 0L;
            boolean authenticatedHeadlessLifecycle = navBarHidden
                    && !allowGestureIgnoringBarVisibility
                    && sysUiStateFlags != null
                    && isCurrentHeadlessNavBarLifecycle(edgeBackGestureHandler);
            if (navBarHidden && !allowGestureIgnoringBarVisibility
                    && !authenticatedHeadlessLifecycle) {
                log(Log.INFO, TAG, "Ignored native back by AOSP bar-visibility policy"
                        + ", sysUiStateFlags=" + sysUiStateFlags
                        + ", edge=" + edge + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
                return false;
            }
            waitingForTransientBarsAtDown = navBarHidden && !navBarShownTransiently;
            if (navBarHidden && authenticatedHeadlessLifecycle
                    && !allowGestureIgnoringBarVisibility) {
                log(Log.INFO, TAG,
                        "Accepted native back through authenticated headless lifecycle"
                                + ", sysUiStateFlags=" + sysUiStateFlags
                                + ", edge=" + edge + ", x=" + event.getRawX()
                                + ", y=" + event.getRawY());
            } else if (navBarHidden) {
                log(Log.INFO, TAG, "AOSP bar-visibility policy allows immersive back"
                        + ", sysUiStateFlags=" + sysUiStateFlags
                        + ", edge=" + edge + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
            }
            if (!isBackGestureAllowedBySystemUiState()) {
                log(Log.INFO, TAG, "Ignored native back while SystemUI state disallows back");
                return false;
            }
            if (isInBottomGestureRegion(event)) {
                log(Log.INFO, TAG, "Ignored native back inside bottom gesture region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            if (isInMiuiSidebarRegion(event)) {
                log(Log.INFO, TAG, "Ignored native back inside MIUI sidebar bounds"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            if (isInExcludedRegion(event, edge)) {
                log(Log.INFO, TAG, "Ignored native back inside exclusion region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            launcherOpenBreakCandidate = launcherOpenBreak;
            launcherOpenBreakGenerationCandidate = launcherOpenBreak
                    ? miuiLauncherOpenBreakGeneration : 0L;
            launcherShadeCandidate = launcherShade;
            launcherDrawerCandidate = launcherDrawer;
            launcherEditingCandidate = launcherEditing;
            // Geometry, attachment, touchability, and redirect acceptance are proved later
            // by the matching token emitted only from MiuiHome's accepted processor boundary.
            return true;
        }

        protected boolean isMiuiShadeExpanded() {
            try {
                Object sysUiState = readField(edgeBackGestureHandler, "mSysUiState");
                Object stateDisplayId = invokeAnyMethod(
                        sysUiState, "getDisplayId", new Object[0]);
                Object flagsObject = invokeAnyMethod(sysUiState, "getFlags", new Object[0]);
                if (!(stateDisplayId instanceof Number)
                        || ((Number) stateDisplayId).intValue() != displayId
                        || !(flagsObject instanceof Number)) {
                    log(Log.WARN, TAG, "Rejected launcher shade state with invalid SysUiState"
                            + ", stateDisplayId=" + stateDisplayId
                            + ", monitorDisplayId=" + displayId
                            + ", flags=" + flagsObject);
                    return false;
                }
                long flags = ((Number) flagsObject).longValue();
                return (flags & SYSUI_STATE_MIUI_SHADE_EXPANDED_MASK) != 0L
                        && (flags & SYSUI_STATE_LOCKED_OR_PINNED_MASK) == 0L;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to inspect MIUI notification shade state; preserving Home ignore",
                        throwable);
                return false;
            }
        }

        protected boolean isNativeBackInputActive() {
            try {
                if (!Boolean.TRUE.equals(readField(edgeBackGestureHandler, "mIsAttached"))
                        || !Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mInGestureNavMode"))
                        || !Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mIsBackGestureAllowed"))
                        || Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mUsingThreeButtonNav"))
                        || Boolean.TRUE.equals(
                        readField(edgeBackGestureHandler, "mDisabledForQuickstep"))) {
                    return false;
                }
                try {
                    // Some SystemUI branches expose this derived lifecycle state. Xiaomi's
                    // current build does not, so absence is compatible while a present false
                    // value must disable the driver.
                    Field enabledField = findCachedField(
                            edgeBackGestureHandler.getClass(), "mIsEnabled");
                    if (!Boolean.TRUE.equals(enabledField.get(edgeBackGestureHandler))) {
                        return false;
                    }
                } catch (NoSuchFieldException ignored) {
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        protected boolean isNavBarShownTransiently() {
            try {
                return Boolean.TRUE.equals(readField(edgeBackGestureHandler,
                        "mIsNavBarShownTransiently"));
            } catch (Throwable ignored) {
                return false;
            }
        }

        protected Long readSystemUiStateFlags() {
            try {
                Object sysUiState = readField(edgeBackGestureHandler, "mSysUiState");
                Object stateDisplayId = invokeAnyMethod(
                        sysUiState, "getDisplayId", new Object[0]);
                Object flagsObject = invokeAnyMethod(
                        sysUiState, "getFlags", new Object[0]);
                if (!(stateDisplayId instanceof Number)
                        || ((Number) stateDisplayId).intValue() != displayId
                        || !(flagsObject instanceof Number)) {
                    log(Log.WARN, TAG,
                            "Cannot bind AOSP gesture policy to current SysUiState"
                                    + ", stateDisplayId=" + stateDisplayId
                                    + ", monitorDisplayId=" + displayId
                                    + ", flags=" + flagsObject);
                    return null;
                }
                long flags = ((Number) flagsObject).longValue();
                boolean logHeadlessState = false;
                synchronized (headlessNavBarLifecycleLock) {
                    HeadlessNavBarLease lease = headlessNavBarLease;
                    if (lease != null
                            && lease.edgeBackGestureHandler == edgeBackGestureHandler
                            && !headlessSysUiStateLogged) {
                        headlessSysUiStateLogged = true;
                        logHeadlessState = true;
                    }
                }
                if (logHeadlessState) {
                    log(Log.INFO, TAG,
                            "Authenticated native SysUiState for headless NavigationBar"
                                    + ", displayId=" + displayId
                                    + ", flags=0x" + Long.toHexString(flags));
                }
                return Long.valueOf(flags);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to inspect AOSP immersive gesture visibility policy",
                        throwable);
                return null;
            }
        }

        protected boolean isBackGestureAllowedBySystemUiState() {
            try {
                Object allowed = readField(edgeBackGestureHandler, "mIsBackGestureAllowed");
                if (allowed instanceof Boolean && !((Boolean) allowed).booleanValue()) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
            try {
                Object running = readField(edgeBackGestureHandler,
                        "mGestureBlockingActivityRunning");
                Object value = invokeAnyMethod(running, "get", new Object[0]);
                if (!(value instanceof Boolean) || Boolean.TRUE.equals(value)) {
                    return false;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect gesture-blocking activity state",
                        throwable);
                return false;
            }
            return true;
        }

        protected ComponentName findTopActivity() {
            try {
                ActivityManager activityManager = context.getSystemService(ActivityManager.class);
                List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(20);
                if (tasks == null || tasks.isEmpty()) {
                    return null;
                }
                for (ActivityManager.RunningTaskInfo task : tasks) {
                    int taskDisplayId = readRunningTaskDisplayId(task);
                    if (taskDisplayId != displayId || task.topActivity == null) {
                        continue;
                    }
                    return task.topActivity;
                }
                return null;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect top activity for native back", throwable);
                return null;
            }
        }

        protected boolean isLauncherHomeComponent(ComponentName topActivity) {
            try {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(displayId == 0
                                ? Intent.CATEGORY_HOME : Intent.CATEGORY_SECONDARY_HOME)
                        .setPackage(MIUI_HOME);
                ResolveInfo resolved = context.getPackageManager().resolveActivity(
                        homeIntent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
                ActivityInfo activityInfo = resolved == null ? null : resolved.activityInfo;
                if (activityInfo == null || !MIUI_HOME.equals(activityInfo.packageName)) {
                    // Preserve the old fail-closed Home behavior when package resolution is
                    // unexpectedly unavailable. Pilfering a real launcher Home stream is worse
                    // than declining a sibling Activity until the resolution fault is known.
                    log(Log.WARN, TAG, "Could not resolve a MiuiHome HOME component"
                            + ", topActivity=" + topActivity.flattenToShortString()
                            + ", displayId=" + displayId);
                    return true;
                }
                ComponentName declared = new ComponentName(
                        activityInfo.packageName, activityInfo.name);
                return topActivity.equals(declared)
                        || (activityInfo.targetActivity != null
                        && topActivity.equals(new ComponentName(
                        activityInfo.packageName, activityInfo.targetActivity)));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to distinguish MiuiHome Activity from launcher Home"
                        + ", topActivity=" + topActivity.flattenToShortString()
                        + ", displayId=" + displayId, throwable);
                return true;
            }
        }

        protected int readRunningTaskDisplayId(ActivityManager.RunningTaskInfo task) {
            try {
                Object value = readField(task, "displayId");
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            } catch (Throwable throwable) {
                if (displayId != 0) {
                    log(Log.WARN, TAG, "Failed to inspect running-task display"
                            + ", monitorDisplayId=" + displayId, throwable);
                }
            }
            // The SDK stub hides TaskInfo.displayId. Default-display SystemUI can safely use
            // the historical default when reflection is unavailable; secondary displays fail
            // closed instead of borrowing another display's launcher state.
            return displayId == 0 ? 0 : Integer.MIN_VALUE;
        }

        protected boolean isInExcludedRegion(MotionEvent event, int edge) {
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            try {
                Object region = readField(edgeBackGestureHandler, "mExcludeRegion");
                if (!(region instanceof Region) || ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect application exclusion region",
                        throwable);
                return true;
            }
            try {
                Object region = readField(edgeBackGestureHandler, "mDesktopModeExcludeRegion");
                if (!(region instanceof Region) || ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect desktop exclusion region", throwable);
                return true;
            }
            try {
                Object bounds = readField(edgeBackGestureHandler, "mNavBarOverlayExcludedBounds");
                if (!(bounds instanceof Rect) || ((Rect) bounds).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect navigation-overlay exclusion bounds",
                        throwable);
                return true;
            }
            Boolean inPip = readPipState();
            if (inPip == null) {
                return true;
            }
            if (inPip.booleanValue()) {
                try {
                    Object bounds = readField(edgeBackGestureHandler, "mPipExcludedBounds");
                    if (!(bounds instanceof Rect) || ((Rect) bounds).contains(x, y)) {
                        return true;
                    }
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "Failed to inspect PiP exclusion bounds", throwable);
                    return true;
                }
            }
            return false;
        }

        protected Boolean readPipState() {
            try {
                Object value = readField(edgeBackGestureHandler, "mIsInPip");
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect PiP state", throwable);
                return null;
            }
        }

        protected boolean isInMiuiSidebarRegion(MotionEvent event) {
            String encoded;
            try {
                encoded = Settings.Secure.getString(context.getContentResolver(),
                        MIUI_SIDEBAR_BOUNDS);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to read MIUI sidebar bounds", throwable);
                return true;
            }
            if (encoded == null || encoded.trim().isEmpty()) {
                return false;
            }
            int x = Math.round(event.getRawX());
            int y = Math.round(event.getRawY());
            int padding = Math.max(0, Math.round(dp(MIUI_SIDEBAR_EXCLUSION_PADDING_DP)));
            try {
                JSONArray bounds = new JSONArray(encoded);
                for (int i = 0; i < bounds.length(); i++) {
                    JSONObject item = bounds.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    Rect rect = new Rect(item.optInt("l", -1), item.optInt("t", -1),
                            item.optInt("r", -1), item.optInt("b", -1));
                    if (rect.isEmpty()) {
                        continue;
                    }
                    rect.inset(-padding, -padding);
                    if (rect.contains(x, y)) {
                        return true;
                    }
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to parse MIUI sidebar bounds: " + encoded,
                        throwable);
                return true;
            }
            return false;
        }

        protected void resetCandidate() {
            gestureCandidate = false;
            MotionEvent oldDown = pendingDownEvent;
            MotionEvent oldMotion = pendingMotionEvent;
            pendingDownEvent = null;
            pendingMotionEvent = null;
            if (oldDown != null) {
                oldDown.recycle();
            }
            if (oldMotion != null) {
                oldMotion.recycle();
            }
            launcherOpenBreakCandidate = false;
            launcherOpenBreakGenerationCandidate = 0L;
            launcherShadeCandidate = false;
            launcherDrawerCandidate = false;
            launcherEditingCandidate = false;
            miuiHomeInputAccepted = false;
            pilfered = false;
            waitingForTransientBarsAtDown = false;
            activeEdge = EDGE_LEFT;
            downEventId = 0;
            downDeviceId = Integer.MIN_VALUE;
            downSource = 0;
            downDisplayId = Integer.MIN_VALUE;
            downX = 0.0f;
            downY = 0.0f;
            downTime = Long.MIN_VALUE;
        }

        protected boolean isNavigationBarHidden() {
            try {
                WindowInsets insets = context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getWindowInsets();
                return insets == null
                        || !insets.isVisible(WindowInsets.Type.navigationBars());
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect navigation-bar visibility", throwable);
                return true;
            }
        }

        protected float dp(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }

        protected float bottomGestureHeight() {
            float height = readFloatFieldOrDefault(edgeBackGestureHandler,
                    "mBottomGestureHeight", Float.NaN);
            return Float.isNaN(height) || Float.isInfinite(height)
                    ? Float.POSITIVE_INFINITY : Math.max(0.0f, height);
        }

        protected boolean isInBottomGestureRegion(MotionEvent event) {
            float bottomGestureHeight = bottomGestureHeight();
            if (bottomGestureHeight <= 0.0f) {
                return false;
            }
            try {
                Rect bounds = context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getBounds();
                return event.getRawY() >= bounds.bottom - bottomGestureHeight;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to inspect bottom gesture region", throwable);
                return true;
            }
        }
    }

    protected final class SystemUiBackGestureDriver {
        protected final class ShellOwner {
            final Object controller;
            final Executor executor;
            final long inputEpoch;

            ShellOwner(Object controller, Executor executor, long inputEpoch) {
                this.controller = controller;
                this.executor = executor;
                this.inputEpoch = inputEpoch;
            }
        }

        protected final class ShellStartSnapshot {
            final boolean ready;
            final boolean startInvoked;
            final String stateDescription;
            final Object tracker;
            final Object navigation;
            final boolean receivedNullNavigation;
            final Throwable failure;

            ShellStartSnapshot(boolean ready, boolean startInvoked,
                               String stateDescription, Object tracker,
                               Object navigation, boolean receivedNullNavigation,
                               Throwable failure) {
                this.ready = ready;
                this.startInvoked = startInvoked;
                this.stateDescription = stateDescription;
                this.tracker = tracker;
                this.navigation = navigation;
                this.receivedNullNavigation = receivedNullNavigation;
                this.failure = failure;
            }
        }

        protected final class ShellGestureSession {
            final long id = systemUiShellGestureSessionIds.incrementAndGet();
            final Object controller;
            final Executor executor;
            final long inputEpoch;
            final Object tracker;
            final Object navigation;
            final boolean receivedNullNavigation;
            final int edge;
            final float startX;
            final float startY;
            final float linearDistance;
            final float maxDistance;
            final float nonLinearFactor;
            final AtomicReference<MiuiHomeAcceptedInputToken> inputIdentity;
            final AtomicBoolean releaseQueued = new AtomicBoolean();
            final AtomicBoolean moveFailed = new AtomicBoolean();
            final AtomicBoolean awaitingStockCleanup = new AtomicBoolean();
            final AtomicBoolean completionConsumed = new AtomicBoolean();

            ShellGestureSession(ShellOwner owner, ShellStartSnapshot start,
                                int edge, float startX, float startY,
                                float linearDistance,
                                float maxDistance, float nonLinearFactor,
                                MiuiHomeAcceptedInputToken inputIdentity) {
                this.controller = owner.controller;
                this.executor = owner.executor;
                this.inputEpoch = owner.inputEpoch;
                this.tracker = start.tracker;
                this.navigation = start.navigation;
                this.receivedNullNavigation = start.receivedNullNavigation;
                this.edge = edge;
                this.startX = startX;
                this.startY = startY;
                this.linearDistance = linearDistance;
                this.maxDistance = maxDistance;
                this.nonLinearFactor = nonLinearFactor;
                this.inputIdentity = new AtomicReference<>(inputIdentity);
            }
        }

        protected final Context context;
        protected final Object edgeBackGestureHandler;
        protected volatile Object controller;
        protected volatile Object backAnimationImpl;
        protected volatile Executor shellExecutor;
        protected volatile ShellGestureSession activeShellSession;
        protected volatile boolean shellStartInFlight;
        protected volatile boolean shellOwnerUncertain;
        protected volatile String lastShellStateDescription = "unqueried";
        protected boolean gestureActive;
        protected boolean thresholdCrossed;
        protected boolean triggerBack;
        protected boolean shellGestureStarted;
        protected boolean shellGestureStartDeferred;
        protected volatile boolean gestureSuppressed;
        protected boolean legacyInterruptGesture;
        protected boolean aospNullNavigationGesture;
        protected Object legacyRunningOpenInfo;
        protected boolean launcherOpenBreakGesture;
        protected long launcherOpenBreakGeneration;
        protected long launcherOpenBreakAttemptId;
        protected long pendingLauncherOpenBreakGeneration;
        protected long pendingLauncherOpenBreakAttemptId;
        protected boolean launcherOverviewGesture;
        protected boolean launcherShadeGesture;
        protected boolean launcherDrawerGesture;
        protected boolean launcherEditingGesture;
        protected boolean recentsVisualOnlyGesture;
        protected MiuiHomeAcceptedInputToken acceptedInputIdentity;
        protected final AtomicLong inputMonitorEpoch = new AtomicLong();
        protected volatile boolean inputMonitorAttached;
        protected int activeEdge;
        protected float downX;
        protected float downY;

        SystemUiBackGestureDriver(Context context, Object edgeBackGestureHandler,
                                  Object controller, Object backAnimationImpl)
                throws Exception {
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.controller = controller;
            this.backAnimationImpl = backAnimationImpl;
            this.shellExecutor = resolveShellExecutor(controller);
        }

        void updateBackAnimation(Object newBackAnimationImpl) throws Exception {
            Object newController = readField(newBackAnimationImpl, "this$0");
            Executor newShellExecutor = resolveShellExecutor(newController);
            synchronized (backInputLifecycleLock) {
                if (controller != newController) {
                    inputMonitorEpoch.incrementAndGet();
                }
                this.backAnimationImpl = newBackAnimationImpl;
                this.controller = newController;
                this.shellExecutor = newShellExecutor;
            }
        }

        protected Executor resolveShellExecutor(Object shellController)
                throws Exception {
            Object executor = readField(shellController, "mShellExecutor");
            if (!(executor instanceof Executor)) {
                throw new IllegalStateException("mShellExecutor is "
                        + shortObject(executor));
            }
            return (Executor) executor;
        }

        boolean blocksHotReload() {
            ShellGestureSession session = activeShellSession;
            return shellStartInFlight || shellOwnerUncertain || session != null;
        }

        String describeActiveShellSession() {
            ShellGestureSession session = activeShellSession;
            return "startInFlight=" + shellStartInFlight
                    + ", uncertain=" + shellOwnerUncertain
                    + ", sessionId=" + (session == null ? 0L : session.id)
                    + ", controller=" + shortObject(
                    session == null ? null : session.controller)
                    + ", tracker=" + shortObject(
                    session == null ? null : session.tracker)
                    + ", navigation=" + shortObject(
                    session == null ? null : session.navigation)
                    + ", releaseQueued=" + (session != null
                    && session.releaseQueued.get())
                    + ", completionConsumed=" + (session != null
                    && session.completionConsumed.get());
        }

        protected ShellOwner captureShellOwner() {
            synchronized (backInputLifecycleLock) {
                if (!inputMonitorAttached || controller == null
                        || shellExecutor == null
                        || inputMonitorEpoch.get() == 0L) {
                    return null;
                }
                return new ShellOwner(controller, shellExecutor,
                        inputMonitorEpoch.get());
            }
        }

        protected boolean isShellOwnerCurrent(ShellOwner owner) {
            synchronized (backInputLifecycleLock) {
                return owner != null && inputMonitorAttached
                        && owner.controller == controller
                        && owner.executor == shellExecutor
                        && owner.inputEpoch == inputMonitorEpoch.get();
            }
        }

        protected boolean isShellSessionOwnerCurrent(
                ShellGestureSession session) {
            synchronized (backInputLifecycleLock) {
                return session != null && inputMonitorAttached
                        && session.controller == controller
                        && session.executor == shellExecutor
                        && session.inputEpoch == inputMonitorEpoch.get();
            }
        }

        protected boolean executeShellBlocking(Executor executor, Runnable task,
                                             String reason) {
            try {
                invokeAnyMethod(executor, "executeBlocking",
                        new Object[]{task});
                return true;
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Failed blocking Shell-owner task, reason=" + reason,
                        throwable);
                return false;
            }
        }

        void onInputMonitorAttached() {
            synchronized (backInputLifecycleLock) {
                inputMonitorEpoch.incrementAndGet();
                inputMonitorAttached = true;
            }
        }

        void bindAcceptedInput(MiuiHomeAcceptedInputToken token) {
            if (gestureActive && token != null
                    && token.generation
                    == systemUiInputArbiterGeneration) {
                acceptedInputIdentity = token;
                ShellGestureSession session = activeShellSession;
                if (session != null && session.edge == activeEdge) {
                    session.inputIdentity.compareAndSet(null, token);
                }
            }
        }

        protected boolean handleTouch(MotionEvent event, int edge,
                                    boolean launcherOpenBreakCandidate,
                                    long launcherOpenBreakGenerationCandidate,
                                    boolean launcherShadeCandidate,
                                    boolean launcherDrawerCandidate,
                                    boolean launcherEditingCandidate) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return onDown(event, edge, launcherOpenBreakCandidate,
                                launcherOpenBreakGenerationCandidate,
                                launcherShadeCandidate,
                                launcherDrawerCandidate, launcherEditingCandidate);
                    case MotionEvent.ACTION_MOVE:
                        return onMove(event);
                    case MotionEvent.ACTION_UP:
                        return onUp(event, true);
                    case MotionEvent.ACTION_CANCEL:
                        return onUp(event, false);
                    default:
                        return gestureActive;
                }
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "SystemUI back gesture driver failed", throwable);
                clearLocalGestureState();
                return false;
            }
        }

        protected boolean isRecentsVisualOnlyGesture() {
            return gestureActive && launcherOverviewGesture && recentsVisualOnlyGesture;
        }

        protected boolean isGestureSuppressed() {
            return gestureActive && gestureSuppressed;
        }

        protected boolean onDown(MotionEvent event, int edge,
                               boolean launcherOpenBreakCandidate,
                               long launcherOpenBreakGenerationCandidate,
                               boolean launcherShadeCandidate,
                               boolean launcherDrawerCandidate,
                               boolean launcherEditingCandidate) throws Exception {
            clearLegacyBackGuard("newPhysicalGesture");
            gestureActive = true;
            shellGestureStarted = false;
            shellGestureStartDeferred = false;
            gestureSuppressed = false;
            legacyInterruptGesture = false;
            aospNullNavigationGesture = false;
            legacyRunningOpenInfo = null;
            launcherOpenBreakGesture = launcherOpenBreakCandidate;
            launcherOpenBreakGeneration = launcherOpenBreakCandidate
                    ? launcherOpenBreakGenerationCandidate : 0L;
            launcherOpenBreakAttemptId = launcherOpenBreakCandidate
                    ? launcherOpenBreakAttemptIds.incrementAndGet() : 0L;
            launcherOverviewGesture = miuiOverviewVisible && !launcherShadeCandidate;
            launcherShadeGesture = launcherShadeCandidate;
            launcherDrawerGesture = launcherDrawerCandidate;
            launcherEditingGesture = launcherEditingCandidate;
            recentsVisualOnlyGesture = false;
            thresholdCrossed = false;
            triggerBack = false;
            activeEdge = edge;
            downX = event.getRawX();
            downY = event.getRawY();
            if (!isShellReadyForGesture()) {
                gestureSuppressed = true;
                log(Log.WARN, TAG, "Suppressed SystemUI back while Shell is busy"
                        + ", state=" + describeShellState());
                return true;
            }
            if (launcherOpenBreakGesture) {
                dispatchToEdgePlugin(event, activeEdge);
                log(Log.INFO, TAG, "SystemUI-owned launcher OPEN break candidate"
                        + ", useMiuiHomeNativeController=true"
                        + ", shellGestureStarted=false"
                        + ", generation=" + launcherOpenBreakGeneration
                        + ", attempt=" + launcherOpenBreakAttemptId
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                return true;
            }
            if (launcherOverviewGesture || launcherShadeGesture
                    || launcherDrawerGesture || launcherEditingGesture) {
                log(Log.INFO, TAG, (launcherShadeGesture
                        ? "SystemUI-owned NotificationShade back gesture candidate"
                        : launcherOverviewGesture
                        ? "SystemUI-owned Recents back gesture candidate"
                        : launcherDrawerGesture
                        ? "SystemUI-owned MiuiHome drawer back gesture candidate"
                        : "SystemUI-owned MiuiHome editing back gesture candidate")
                        + ", useShellCallback=true"
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                // Launcher Home, Recents, the drawer, and editing surfaces share one Activity.
                // Resolve the callback on DOWN while the real stream is still unpilfered.
                if (!startShellGesture()) {
                    if (recentsVisualOnlyGesture) {
                        dispatchToEdgePlugin(event, activeEdge);
                        log(Log.INFO, TAG, "Started visual-only Recents edge gesture"
                                + ", staleShellTargetRejected=true"
                                + ", inputWillRemainUnpilfered=true");
                        return true;
                    }
                    gestureActive = false;
                    gestureSuppressed = false;
                    log(Log.INFO, TAG, (launcherShadeGesture
                            ? "Ignored NotificationShade gesture without a callback target"
                            : launcherDrawerGesture
                            ? "Ignored MiuiHome drawer gesture without a callback target"
                            : launcherEditingGesture
                            ? "Ignored MiuiHome editing gesture without a callback target"
                            : "Ignored Recents edge gesture without a back navigation target")
                            + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                    return false;
                }
            } else {
                // MiuiHome GestureStub accepted the original DOWN and its legacy processor
                // was neutralized. Pilfering and Shell setup now share the intentional 8dp
                // boundary, with the authenticated token as the ownership proof.
                shellGestureStartDeferred = true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            log(Log.INFO, TAG, "SystemUI gesture driver candidate"
                    + ", shellStartDeferred=" + shellGestureStartDeferred
                    + ", inputModel=miuihome-accepted-token"
                    + ", launcherShade=" + launcherShadeGesture
                    + ", launcherDrawer=" + launcherDrawerGesture
                    + ", launcherEditing=" + launcherEditingGesture
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        protected boolean onMove(MotionEvent event) throws Exception {
            if (!gestureActive) {
                return false;
            }
            if (gestureSuppressed) {
                return true;
            }
            if (recentsVisualOnlyGesture) {
                dispatchToEdgePlugin(event, activeEdge);
                return true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            float distance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            if (shellGestureStartDeferred && distance > dp(PILFER_THRESHOLD_DP)) {
                if (!isShellReadyForGesture()) {
                    cancelLocalGesture(event,
                            "Shell became busy before deferred start, state="
                                    + describeShellState());
                    return false;
                }
                shellGestureStartDeferred = false;
                if (!startShellGesture()) {
                    cancelLocalGesture(event,
                            "BackNavigationInfo unavailable at intent threshold");
                    return false;
                }
                log(Log.INFO, TAG, "Started in-app back path at 8dp intent threshold"
                        + ", shellGestureStarted=" + shellGestureStarted
                        + ", legacyInterrupt=" + legacyInterruptGesture
                        + ", aospNullNavigation=" + aospNullNavigationGesture
                        + ", edge=" + activeEdge
                        + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
            }
            boolean crossedNow = false;
            if (!thresholdCrossed && distance > dp(PILFER_THRESHOLD_DP)) {
                crossedNow = crossIntentThreshold(distance);
            }
            boolean shouldTrigger = distance > dp(TRIGGER_THRESHOLD_DP);
            boolean triggerChanged = updateTriggerBack(shouldTrigger);
            if (!shellGestureStartDeferred
                    && !legacyInterruptGesture && !launcherOpenBreakGesture) {
                ShellGestureSession session = activeShellSession;
                if (session == null || !queueShellMove(session,
                        event.getRawX(), event.getRawY(), distance,
                        crossedNow, thresholdCrossed
                                && !aospNullNavigationGesture,
                        triggerChanged, shouldTrigger)) {
                    cancelLocalGesture(event,
                            "failed to queue fixed Shell-owner MOVE");
                    return false;
                }
            }
            return true;
        }

        protected boolean crossIntentThreshold(float distance) {
            if (thresholdCrossed) {
                return false;
            }
            thresholdCrossed = true;
            if (legacyInterruptGesture) {
                log(Log.INFO, TAG, "MIUI in-app interrupt threshold crossed, distance="
                        + distance);
            } else if (launcherOpenBreakGesture) {
                log(Log.INFO, TAG, "MiuiHome launcher OPEN break threshold crossed, distance="
                        + distance);
            } else {
                log(Log.INFO, TAG, launcherShadeGesture
                        ? "SystemUI NotificationShade Shell callback threshold crossed, distance="
                        + distance
                        : launcherOverviewGesture
                        ? "SystemUI Recents Shell callback threshold crossed, distance=" + distance
                        : "SystemUI gesture driver intent threshold crossed, distance=" + distance);
            }
            return true;
        }

        protected boolean onUp(MotionEvent event, boolean allowTrigger) throws Exception {
            if (!gestureActive) {
                return false;
            }
            if (gestureSuppressed) {
                clearLocalGestureState();
                log(Log.INFO, TAG, "Finished suppressed SystemUI back gesture");
                return true;
            }
            if (launcherOpenBreakGesture) {
                return finishLauncherOpenBreakGesture(event, allowTrigger);
            }
            if (legacyInterruptGesture) {
                return finishLegacyInterruptGesture(event, allowTrigger);
            }
            if (recentsVisualOnlyGesture) {
                // BackPanelController may set BackAnimationImpl's trigger bit while completing
                // its local animation. Clear it after UP/CANCEL: cancellation of the rejected
                // Shell navigation was already queued, and this gesture must never commit later.
                dispatchToEdgePlugin(event, activeEdge);
                float releaseDistance = activeEdge == EDGE_LEFT
                        ? event.getRawX() - downX
                        : downX - event.getRawX();
                clearControllerTriggerAfterVisualOnlyGesture();
                log(Log.INFO, TAG, "Finished visual-only Recents edge gesture"
                        + ", releaseDistance=" + releaseDistance
                        + ", wouldPassCommitThreshold="
                        + (allowTrigger && releaseDistance > dp(TRIGGER_THRESHOLD_DP))
                        + ", inputRemainedUnpilfered=true");
                clearLocalGestureState();
                return false;
            }
            if (!shellGestureStarted) {
                cancelLocalGesture(event, "released before first MOVE");
                return true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean trigger = allowTrigger
                    && thresholdCrossed
                    && releaseDistance > dp(TRIGGER_THRESHOLD_DP);
            ShellGestureSession session = activeShellSession;
            if (session == null || session.edge != activeEdge) {
                cancelLocalGesture(event,
                        "missing fixed Shell session at release");
                return true;
            }
            boolean ownerStillCurrent = isShellSessionOwnerCurrent(session);
            if (!ownerStillCurrent) {
                trigger = false;
                log(Log.WARN, TAG,
                        "Forced Shell release cancellation after owner changed"
                                + ", shellSessionId=" + session.id
                                + ", sessionController="
                                + shortObject(session.controller)
                                + ", currentController="
                                + shortObject(controller)
                                + ", sessionInputEpoch="
                                + session.inputEpoch
                                + ", currentInputEpoch="
                                + inputMonitorEpoch.get());
            }
            triggerBack = trigger;
            MiuiHomeAcceptedInputToken releaseInputIdentity =
                    session.inputIdentity.get();
            boolean queued = queueShellReleaseTransaction(
                    session,
                    event.getRawX(), event.getRawY(), releaseDistance,
                    thresholdCrossed, trigger, activeEdge,
                    launcherOverviewGesture, launcherShadeGesture, launcherDrawerGesture,
                    launcherEditingGesture, aospNullNavigationGesture,
                    session.inputEpoch,
                    releaseInputIdentity);
            log(queued ? Log.INFO : Log.ERROR, TAG,
                    "SystemUI gesture driver release queued=" + queued
                    + ", requestedTrigger=" + trigger
                    + ", recentsShellCallback=" + launcherOverviewGesture
                    + ", shadeShellCallback=" + launcherShadeGesture
                    + ", drawerShellCallback=" + launcherDrawerGesture
                    + ", editingShellCallback=" + launcherEditingGesture
                    + ", aospNullNavigation=" + aospNullNavigationGesture
                    + ", shellSessionId=" + session.id
                    + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        protected boolean finishLegacyInterruptGesture(MotionEvent event, boolean allowTrigger)
                throws Exception {
            String panelStateBeforeRelease = readNativePanelState();
            boolean panelReleaseDelivered =
                    dispatchToEdgePlugin(event, activeEdge);
            String panelStateAfterRelease = readNativePanelState();
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean fixedThresholdEligible = allowTrigger
                    && thresholdCrossed
                    && releaseDistance > dp(TRIGGER_THRESHOLD_DP);
            Boolean nativePanelTrigger = resolveNativePanelReleaseTrigger(
                    panelStateAfterRelease, panelReleaseDelivered);
            boolean trigger = fixedThresholdEligible
                    && Boolean.TRUE.equals(nativePanelTrigger);
            updateTriggerBack(trigger);
            if (trigger) {
                // A normal BACK creates the incoming CLOSE/TO_BACK transition. Xiaomi's
                // TransitionControllerImpl tags a consecutive inverse transition pair and
                // DefaultTransitionImpl.mergeAnimation() reverses the running OPEN animators.
                dispatchLegacyInterruptBack();
            }
            log(Log.INFO, TAG, "Finished MIUI in-app interrupt gesture"
                    + ", fixedThresholdEligible=" + fixedThresholdEligible
                    + ", nativePanelTrigger=" + nativePanelTrigger
                    + ", panelStateBeforeRelease="
                    + panelStateBeforeRelease
                    + ", panelStateAfterRelease="
                    + panelStateAfterRelease
                    + ", trigger=" + trigger
                    + ", releaseDistance=" + releaseDistance
                    + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        void detach() {
            synchronized (backInputLifecycleLock) {
                inputMonitorAttached = false;
                inputMonitorEpoch.incrementAndGet();
            }
            if (pendingLauncherOpenBreakAttemptId != 0L) {
                decrementLauncherOpenBreakCommandsInFlight();
            }
            pendingLauncherOpenBreakGeneration = 0L;
            pendingLauncherOpenBreakAttemptId = 0L;
            clearSystemUiReturnHomeCommitIdentity(
                    controller, 0L, "driverDetach");
            clearLocalGestureState();
        }

        protected boolean finishLauncherOpenBreakGesture(MotionEvent event,
                                                       boolean allowTrigger)
                throws Exception {
            String panelStateBeforeRelease = readNativePanelState();
            boolean panelReleaseDelivered =
                    dispatchToEdgePlugin(event, activeEdge);
            String panelStateAfterRelease = readNativePanelState();
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean fixedThresholdEligible = allowTrigger
                    && thresholdCrossed
                    && releaseDistance > dp(TRIGGER_THRESHOLD_DP);
            Boolean nativePanelTrigger = resolveNativePanelReleaseTrigger(
                    panelStateAfterRelease, panelReleaseDelivered);
            boolean trigger = fixedThresholdEligible
                    && Boolean.TRUE.equals(nativePanelTrigger);
            updateTriggerBack(trigger);
            // This gesture never starts a Shell tracker. Clear any trigger value posted by
            // BackPanelController after its release event, then hand a committed gesture to
            // MiuiHome's own BackGestureBreakController.
            clearControllerTriggerAfterVisualOnlyGesture();
            if (trigger) {
                long generation = launcherOpenBreakGeneration;
                long attemptId = launcherOpenBreakAttemptId;
                pendingLauncherOpenBreakGeneration = generation;
                pendingLauncherOpenBreakAttemptId = attemptId;
                launcherOpenBreakCommandsInFlight.incrementAndGet();
                try {
                    sendAuthenticatedMiuiHomeOpenBreakCommand(
                            context, generation, attemptId, this);
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "Failed to send launcher OPEN break command",
                            throwable);
                    onLauncherOpenBreakCommandResult(generation, attemptId,
                            LAUNCHER_OPEN_BREAK_RESULT_REJECTED, "sendException");
                }
            }
            log(Log.INFO, TAG, "Finished MiuiHome launcher OPEN break gesture"
                    + ", trigger=" + trigger
                    + ", generation=" + launcherOpenBreakGeneration
                    + ", attempt=" + launcherOpenBreakAttemptId
                    + ", releaseDistance=" + releaseDistance
                    + ", fixedThresholdEligible="
                    + fixedThresholdEligible
                    + ", nativePanelTrigger=" + nativePanelTrigger
                    + ", panelStateBeforeRelease="
                    + panelStateBeforeRelease
                    + ", panelStateAfterRelease="
                    + panelStateAfterRelease
                    + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        protected void onLauncherOpenBreakCommandResult(long generation, long attemptId,
                                                       int resultCode, String reason) {
            if (pendingLauncherOpenBreakGeneration != generation
                    || pendingLauncherOpenBreakAttemptId != attemptId) {
                log(Log.WARN, TAG, "Ignored stale launcher OPEN break command result"
                        + ", generation=" + generation
                        + ", attempt=" + attemptId
                        + ", pendingGeneration=" + pendingLauncherOpenBreakGeneration
                        + ", pendingAttempt=" + pendingLauncherOpenBreakAttemptId
                        + ", resultCode=" + resultCode
                        + ", reason=" + reason);
                return;
            }
            decrementLauncherOpenBreakCommandsInFlight();
            pendingLauncherOpenBreakGeneration = 0L;
            pendingLauncherOpenBreakAttemptId = 0L;
            if (resultCode == LAUNCHER_OPEN_BREAK_RESULT_ACCEPTED) {
                log(Log.INFO, TAG, "MiuiHome accepted launcher OPEN break command"
                        + ", generation=" + generation
                        + ", attempt=" + attemptId);
                return;
            }
            log(Log.WARN, TAG, "Falling back to one ordinary BACK after launcher OPEN "
                    + "break rejection"
                    + ", generation=" + generation
                    + ", attempt=" + attemptId
                    + ", resultCode=" + resultCode
                    + ", reason=" + reason);
            injectLegacyBackKey();
        }

        protected void decrementLauncherOpenBreakCommandsInFlight() {
            int remaining = launcherOpenBreakCommandsInFlight.decrementAndGet();
            if (remaining < 0) {
                launcherOpenBreakCommandsInFlight.set(0);
                log(Log.WARN, TAG, "Corrected launcher OPEN break in-flight underflow");
            }
        }

        protected boolean startShellGesture() throws Exception {
            // A running Xiaomi OPEN transition is the native interruption source. Prefer it
            // even when system_server can already return a valid predictive-back navigation;
            // otherwise Shell starts a new cross-activity animation and misses reverse().
            boolean launcherCallbackOnly = launcherOverviewGesture
                    || launcherShadeGesture || launcherDrawerGesture
                    || launcherEditingGesture;
            OpenTransitionSnapshot runningOpen = launcherCallbackOnly
                    ? null : findReversibleRunningOpenTransition();
            if (runningOpen != null) {
                legacyInterruptGesture = true;
                legacyRunningOpenInfo = runningOpen.transitionInfo;
                log(Log.INFO, TAG, "Preferred running Xiaomi OPEN transition before predictive back");
                return true;
            }

            try {
                invokeAnyMethod(edgeBackGestureHandler,
                        "updateDisplaySize$1", new Object[0]);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to update display size before Shell start",
                        throwable);
            }
            float maxDistance = Math.max(1.0f,
                    context.getResources().getDisplayMetrics().widthPixels);
            float linearThreshold = readFloatFieldOrDefault(
                    edgeBackGestureHandler, "mBackSwipeLinearThreshold",
                    dp(AOSP_PROGRESS_THRESHOLD_DP));
            float linearDistance = Math.min(maxDistance, linearThreshold);
            float nonLinearFactor = readFloatFieldOrDefault(
                    edgeBackGestureHandler, "mNonLinearFactor", 0.0f);
            float startX = downX;
            float startY = downY;
            int startEdge = activeEdge;
            ShellOwner owner = captureShellOwner();
            if (owner == null) {
                log(Log.WARN, TAG, "Rejected gesture without a stable Shell start owner"
                        + ", controller=" + shortObject(controller)
                        + ", inputEpoch=" + inputMonitorEpoch.get()
                        + ", inputAttached=" + inputMonitorAttached);
                return false;
            }

            AtomicReference<ShellStartSnapshot> startResult =
                    new AtomicReference<>();
            AtomicReference<ShellGestureSession> abandonedSession =
                    new AtomicReference<>();
            AtomicBoolean abandoned = new AtomicBoolean();
            AtomicBoolean startTaskEntered = new AtomicBoolean();
            Runnable startTask = () -> {
                boolean startInvoked = false;
                try {
                    startTaskEntered.set(true);
                    if (abandoned.get()) {
                        return;
                    }
                    String state = describeShellStateOnOwner(owner.controller);
                    if (!isShellReadyOnOwner(owner.controller)) {
                        startResult.set(new ShellStartSnapshot(
                                false, false, state, null, null,
                                false, null));
                        return;
                    }
                    ensureAospBackAnimations(owner.controller,
                            "gestureStartOwner");
                    if (abandoned.get()) {
                        return;
                    }
                    startInvoked = true;
                    invokeAnyMethod(owner.controller, "onGestureStarted",
                            new Object[]{Float.valueOf(startX),
                                    Float.valueOf(startY),
                                    Integer.valueOf(startEdge)});
                    Object tracker = invokeAnyMethod(owner.controller,
                            "getActiveTracker", new Object[0]);
                    if (tracker != null) {
                        applyProgressThresholds(tracker, linearDistance,
                                maxDistance, nonLinearFactor);
                    }
                    Object navigation = readField(owner.controller,
                            "mBackNavigationInfo");
                    boolean receivedNull = Boolean.TRUE.equals(readField(
                            owner.controller, "mReceivedNullNavigationInfo"));
                    startResult.set(new ShellStartSnapshot(
                            true, true,
                            describeShellStateOnOwner(owner.controller),
                            tracker, navigation, receivedNull, null));
                } catch (Throwable throwable) {
                    Object tracker = null;
                    Object navigation = null;
                    boolean receivedNull = false;
                    try {
                        tracker = invokeAnyMethod(owner.controller,
                                "getActiveTracker", new Object[0]);
                        navigation = readField(owner.controller,
                                "mBackNavigationInfo");
                        receivedNull = Boolean.TRUE.equals(readField(
                                owner.controller,
                                "mReceivedNullNavigationInfo"));
                    } catch (Throwable captureFailure) {
                        throwable.addSuppressed(captureFailure);
                    }
                    startResult.set(new ShellStartSnapshot(
                            false, startInvoked,
                            describeShellStateOnOwner(owner.controller),
                            tracker, navigation, receivedNull, throwable));
                } finally {
                    if (abandoned.get()) {
                        handleAbandonedShellStart(owner, startResult.get(),
                                abandonedSession, linearDistance,
                                maxDistance, nonLinearFactor,
                                startX, startY, startEdge);
                    }
                }
            };
            shellStartInFlight = true;
            boolean blockingStartCompleted = executeShellBlocking(
                    owner.executor, startTask, "gestureStart");
            if (!blockingStartCompleted) {
                abandoned.set(true);
                shellOwnerUncertain = true;
                boolean cleanupQueued = false;
                try {
                    owner.executor.execute(() -> handleAbandonedShellStart(
                            owner, startResult.get(), abandonedSession,
                            linearDistance, maxDistance, nonLinearFactor,
                            startX, startY, startEdge));
                    cleanupQueued = true;
                } catch (Throwable cleanupFailure) {
                    log(Log.ERROR, TAG,
                            "Failed to queue abandoned Shell-start cleanup",
                            cleanupFailure);
                }
                if (!cleanupQueued && !startTaskEntered.get()) {
                    shellStartInFlight = false;
                    shellOwnerUncertain = false;
                }
                log(Log.ERROR, TAG,
                        "Rejected gesture after Shell blocking start failed"
                                + ", cleanupQueued=" + cleanupQueued
                                + ", taskEntered=" + startTaskEntered.get()
                                + ", controller="
                                + shortObject(owner.controller)
                                + ", inputEpoch=" + owner.inputEpoch);
                return false;
            }
            ShellStartSnapshot start = startResult.get();
            if (start == null) {
                shellOwnerUncertain = true;
                abandoned.set(true);
                start = startResult.get();
                if (start != null) {
                    handleAbandonedShellStart(owner, start,
                            abandonedSession, linearDistance,
                            maxDistance, nonLinearFactor,
                            startX, startY, startEdge);
                }
                log(Log.ERROR, TAG,
                        "Rejected gesture after Shell blocking start timed out"
                                + ", controller="
                                + shortObject(owner.controller)
                                + ", inputEpoch=" + owner.inputEpoch);
                return false;
            }
            lastShellStateDescription = start.stateDescription;
            if (start.failure != null) {
                if (start.startInvoked) {
                    handleAbandonedShellStart(owner, start,
                            abandonedSession, linearDistance,
                            maxDistance, nonLinearFactor,
                            startX, startY, startEdge);
                }
                shellStartInFlight = false;
                log(Log.ERROR, TAG, "Shell-owner gesture start failed",
                        start.failure);
                return false;
            }
            if (!start.ready || !start.startInvoked) {
                shellStartInFlight = false;
                log(Log.WARN, TAG, "Rejected gesture while Shell is busy"
                        + ", state=" + start.stateDescription);
                return false;
            }
            ShellGestureSession session = new ShellGestureSession(
                    owner, start, startEdge, startX, startY, linearDistance,
                    maxDistance, nonLinearFactor, acceptedInputIdentity);
            if (!publishShellGestureSession(session)) {
                shellOwnerUncertain = true;
                shellStartInFlight = false;
                cancelUnpublishedShellSession(session,
                        "activeSessionCollision");
                return false;
            }
            shellStartInFlight = false;
            Object info = start.navigation;
            boolean receivedNull = start.receivedNullNavigation;
            if (!isShellOwnerCurrent(owner)) {
                log(Log.WARN, TAG, "Rejected Shell gesture after start owner changed"
                        + ", sessionId=" + session.id
                        + ", startController=" + shortObject(owner.controller)
                        + ", currentController=" + shortObject(controller)
                        + ", startInputEpoch=" + owner.inputEpoch
                        + ", currentInputEpoch=" + inputMonitorEpoch.get());
                cleanupRejectedShellGesture(session);
                return false;
            }
            if (info == null || receivedNull) {
                log(Log.WARN, TAG, "Shell rejected back navigation"
                        + ", info=" + shortObject(info)
                        + ", receivedNull=" + receivedNull
                        + ", state=" + start.stateDescription);
                if (launcherOverviewGesture) {
                    recentsVisualOnlyGesture = true;
                }
                if (launcherCallbackOnly) {
                    cleanupRejectedShellGesture(session);
                    if (launcherOverviewGesture) {
                        log(Log.INFO, TAG, "Rejected null Recents BackNavigationInfo"
                                + ", mode=visual-only"
                                + ", retry=false");
                    }
                    return false;
                }
                runningOpen = findReversibleRunningOpenTransition();
                if (receivedNull && runningOpen != null) {
                    cleanupRejectedShellGesture(session);
                    legacyInterruptGesture = true;
                    legacyRunningOpenInfo = runningOpen.transitionInfo;
                    log(Log.INFO, TAG, "Using SystemUI-owned legacy BACK for possible "
                            + "MIUI in-app transition interruption");
                    return true;
                }
                boolean authenticatedNullStart = false;
                if (info == null && receivedNull) {
                    synchronized (backInputLifecycleLock) {
                        if (isCurrentAcceptedInputIdentity(
                                acceptedInputIdentity, activeEdge,
                                session.controller, session.inputEpoch)) {
                            // Match stock BackAnimationController: retain this authenticated
                            // physical stream, let the native panel drive the tracker's terminal
                            // trigger, and inject one legacy BACK only if release commits.
                            // Launcher callback probes and Shell-busy rejection never reach this
                            // branch.
                            aospNullNavigationGesture = true;
                            shellGestureStarted = true;
                            authenticatedNullStart = true;
                        }
                    }
                }
                if (authenticatedNullStart) {
                    log(Log.INFO, TAG, "Continuing authenticated in-app gesture with AOSP "
                            + "null-navigation fallback"
                            + ", input=" + shortObject(acceptedInputIdentity)
                            + ", edge=" + activeEdge);
                    return true;
                }
                cleanupRejectedShellGesture(session);
                return false;
            }
            if (launcherCallbackOnly) {
                int navigationType = info instanceof BackNavigationInfo
                        ? ((BackNavigationInfo) info).getType() : -1;
                if (navigationType != TYPE_CALLBACK) {
                    log(Log.WARN, TAG, (launcherShadeGesture
                            ? "Rejected non-callback NotificationShade Shell target"
                            : launcherOverviewGesture
                            ? "Rejected stale Recents Shell target"
                            : launcherDrawerGesture
                            ? "Rejected non-callback MiuiHome drawer Shell target"
                            : "Rejected non-callback MiuiHome editing Shell target")
                            + ", type=" + navigationType
                            + ", info=" + shortObject(info));
                    if (launcherOverviewGesture) {
                        recentsVisualOnlyGesture = true;
                    }
                    cleanupRejectedShellGesture(session);
                    return false;
                }
                log(Log.INFO, TAG, (launcherShadeGesture
                        ? "Resolved NotificationShade Shell callback, type="
                        : launcherOverviewGesture
                        ? "Resolved Launcher Recents Shell callback, type="
                        : launcherDrawerGesture
                        ? "Resolved MiuiHome drawer Shell callback, type="
                        : "Resolved MiuiHome editing Shell callback, type=")
                        + navigationType);
            }
            shellGestureStarted = true;
            log(Log.INFO, TAG, "SystemUI gesture driver onGestureStarted"
                    + ", shellSessionId=" + session.id
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        protected boolean publishShellGestureSession(
                ShellGestureSession session) {
            synchronized (backInputLifecycleLock) {
                if (activeShellSession != null) {
                    return false;
                }
                activeShellSession = session;
                shellOwnerUncertain = false;
                return true;
            }
        }

        protected void handleAbandonedShellStart(
                ShellOwner owner, ShellStartSnapshot start,
                AtomicReference<ShellGestureSession> abandonedSession,
                float linearDistance, float maxDistance,
                float nonLinearFactor, float startX, float startY,
                int startEdge) {
            if (start == null) {
                shellStartInFlight = false;
                shellOwnerUncertain = false;
                return;
            }
            if (!start.startInvoked) {
                shellStartInFlight = false;
                shellOwnerUncertain = false;
                return;
            }
            ShellGestureSession session = abandonedSession.get();
            if (session == null) {
                ShellGestureSession candidate = new ShellGestureSession(
                        owner, start, startEdge, startX, startY,
                        linearDistance, maxDistance, nonLinearFactor,
                        acceptedInputIdentity);
                if (abandonedSession.compareAndSet(null, candidate)) {
                    session = candidate;
                } else {
                    session = abandonedSession.get();
                }
            }
            if (session == null || session.releaseQueued.get()) {
                return;
            }
            if (activeShellSession != session
                    && !publishShellGestureSession(session)) {
                shellStartInFlight = false;
                cancelUnpublishedShellSession(session,
                        "abandonedStartCollision");
                return;
            }
            shellStartInFlight = false;
            cleanupRejectedShellGesture(session);
        }

        protected void cancelUnpublishedShellSession(
                ShellGestureSession session, String reason) {
            shellOwnerUncertain = true;
            try {
                session.executor.execute(() -> cancelFailedShellRelease(
                        session, session.tracker));
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Could not cancel untracked Shell session"
                                + ", sessionId=" + session.id
                                + ", reason=" + reason,
                        throwable);
            }
        }

        protected OpenTransitionSnapshot findReversibleRunningOpenTransition() {
            for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
                if (snapshot.state.get() == OPEN_SNAPSHOT_ACTIVE) {
                    log(Log.INFO, TAG, "Detected reversible running OPEN transition"
                            + ", animatorCount=" + snapshot.animators.length
                            + ", info=" + shortObject(snapshot.transitionInfo));
                    return snapshot;
                }
            }
            return null;
        }

        protected boolean isShellReadyForGesture() {
            ShellGestureSession session = activeShellSession;
            if (shellOwnerUncertain || session != null) {
                lastShellStateDescription = describeActiveShellSession();
                return false;
            }
            ShellOwner owner = captureShellOwner();
            if (owner == null) {
                lastShellStateDescription = "owner-unavailable";
                return false;
            }
            AtomicReference<Boolean> ready = new AtomicReference<>();
            AtomicReference<String> state = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            executeShellBlocking(owner.executor, () -> {
                try {
                    state.set(describeShellStateOnOwner(owner.controller));
                    ready.set(Boolean.valueOf(
                            isShellReadyOnOwner(owner.controller)));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "readiness");
            lastShellStateDescription = state.get() == null
                    ? "readiness-timeout" : state.get();
            if (failure.get() != null) {
                log(Log.WARN, TAG,
                        "Failed to inspect Shell readiness; rejecting gesture",
                        failure.get());
            }
            return failure.get() == null
                    && Boolean.TRUE.equals(ready.get())
                    && isShellOwnerCurrent(owner);
        }

        protected boolean isShellReadyOnOwner(Object stateController)
                throws Exception {
            if (Boolean.TRUE.equals(readField(stateController,
                    "mPostCommitAnimationInProgress"))
                    || Boolean.TRUE.equals(readField(
                    stateController, "mBackGestureStarted"))
                    || Boolean.TRUE.equals(readField(
                    stateController, "mReceivedNullNavigationInfo"))
                    || readField(stateController,
                    "mBackNavigationInfo") != null
                    || readField(stateController,
                    "mBackAnimationFinishedCallback") != null) {
                return false;
            }
            Object current = readField(stateController, "mCurrentTracker");
            Object queued = readField(stateController, "mQueuedTracker");
            return isTrackerInitial(current) && isTrackerInitial(queued);
        }

        protected boolean isTrackerInitial(Object tracker) throws Exception {
            return tracker == null || ((BackTouchTracker) tracker).isInInitialState();
        }

        protected String describeShellState() {
            return lastShellStateDescription;
        }

        protected String describeShellStateOnOwner(Object stateController) {
            try {
                return "postCommit=" + readField(
                        stateController, "mPostCommitAnimationInProgress")
                        + ", backStarted=" + readField(stateController, "mBackGestureStarted")
                        + ", info=" + shortObject(readField(
                        stateController, "mBackNavigationInfo"))
                        + ", finishedCallback=" + shortObject(
                        readField(stateController, "mBackAnimationFinishedCallback"))
                        + ", current=" + shortObject(readField(
                        stateController, "mCurrentTracker"))
                        + ", queued=" + shortObject(readField(
                        stateController, "mQueuedTracker"));
            } catch (Throwable throwable) {
                return "unavailable:" + throwable.getClass().getSimpleName();
            }
        }

        protected void cleanupRejectedShellGesture(ShellGestureSession session) {
            // A prepared adapter may deliver onAnimationStart after onGestureStarted() returns.
            // Finishing navigation here would clear mBackNavigationInfo first; the late adapter
            // callback would then be retained while startSystemAnimation() exits early, leaving
            // every later gesture blocked by mBackAnimationFinishedCallback. Reuse the complete
            // Shell-owner release transaction so the tracker is finished with trigger=false and
            // a waiting runner receives cancellation before normal navigation cleanup.
            boolean queued = queueShellReleaseTransaction(
                    session,
                    session.startX, session.startY, 0.0f,
                    false, false, session.edge,
                    launcherOverviewGesture, launcherShadeGesture, launcherDrawerGesture,
                    launcherEditingGesture, false, 0L, null);
            log(queued ? Log.INFO : Log.ERROR, TAG,
                    "Rejected Shell navigation cancellation queued=" + queued
                            + ", requestedTrigger=false"
                            + ", recentsProbe=" + launcherOverviewGesture
                            + ", shadeProbe=" + launcherShadeGesture
                            + ", drawerProbe=" + launcherDrawerGesture
                            + ", editingProbe=" + launcherEditingGesture
                            + ", shellSessionId=" + session.id
                            + ", edge=" + session.edge);
        }

        protected void clearControllerTriggerAfterVisualOnlyGesture() {
            try {
                // Queue the clear through the same BackAnimationImpl path used by the panel's
                // BackCallback so it is ordered after any trigger=true posted by ACTION_UP.
                invokeAnyMethod(backAnimationImpl, "setTriggerBack",
                        new Object[]{Boolean.FALSE});
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to queue visual-only trigger clear through BackAnimationImpl",
                        throwable);
            }
        }

        protected boolean isCurrentAcceptedInputIdentity(
                MiuiHomeAcceptedInputToken inputIdentity, int edge,
                Object expectedController, long expectedInputMonitorEpoch) {
            return inputIdentity != null
                    && acceptingBackInputInstalls
                    && systemUiInputArbiterMonitorCount.get() > 0
                    && inputMonitorAttached
                    && expectedInputMonitorEpoch != 0L
                    && expectedInputMonitorEpoch == inputMonitorEpoch.get()
                    && expectedController == controller
                    && inputIdentity.generation == systemUiInputArbiterGeneration
                    && inputIdentity.edge == edge;
        }

        protected void clearLocalGestureState() {
            gestureActive = false;
            shellGestureStarted = false;
            shellGestureStartDeferred = false;
            gestureSuppressed = false;
            legacyInterruptGesture = false;
            aospNullNavigationGesture = false;
            legacyRunningOpenInfo = null;
            launcherOpenBreakGesture = false;
            launcherOpenBreakGeneration = 0L;
            launcherOpenBreakAttemptId = 0L;
            launcherOverviewGesture = false;
            launcherShadeGesture = false;
            launcherDrawerGesture = false;
            launcherEditingGesture = false;
            recentsVisualOnlyGesture = false;
            acceptedInputIdentity = null;
            thresholdCrossed = false;
            triggerBack = false;
        }

        protected void cancelLocalGesture(MotionEvent event, String reason) {
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                dispatchToEdgePlugin(cancel, activeEdge);
                cancel.recycle();
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to cancel local edge panel", throwable);
            }
            clearLocalGestureState();
            log(Log.INFO, TAG, "Cancelled local SystemUI back gesture, reason=" + reason);
        }

        protected Runnable captureShellAnimationCompletion(
                Object finishedController, Object currentTracker,
                Object queuedTracker, Object navigation, String reason) {
            ShellGestureSession session = activeShellSession;
            boolean exactIdentity = session != null
                    && session.navigation == navigation
                    && (session.tracker == currentTracker
                    || session.tracker == queuedTracker);
            if (session == null || session.completionConsumed.get()
                    || !session.releaseQueued.get()
                    || session.controller != finishedController
                    || (!exactIdentity
                    && !session.awaitingStockCleanup.get())) {
                return null;
            }
            return () -> {
                if (!exactIdentity) {
                    boolean quiescent = false;
                    try {
                        quiescent = activeShellSession == session
                                && isShellReadyOnOwner(finishedController);
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG,
                                "Failed to verify orphaned Shell cleanup",
                                throwable);
                    }
                    if (!quiescent) {
                        log(Log.WARN, TAG,
                                "Retained orphaned Shell session after non-quiescent finish"
                                        + ", shellSessionId=" + session.id
                                        + ", tracker="
                                        + shortObject(session.tracker)
                                        + ", currentTracker="
                                        + shortObject(currentTracker)
                                        + ", queuedTracker="
                                        + shortObject(queuedTracker)
                                        + ", navigation="
                                        + shortObject(session.navigation)
                                        + ", currentNavigation="
                                        + shortObject(navigation));
                        return;
                    }
                    log(Log.WARN, TAG,
                            "Accepted definitive stock cleanup for orphaned Shell session"
                                    + ", shellSessionId=" + session.id);
                }
                completeShellSessionOnOwner(
                        session, "stock-finish:" + reason);
            };
        }

        protected void completeShellSessionOnOwner(
                ShellGestureSession session, String reason) {
            if (session == null
                    || !session.completionConsumed.compareAndSet(
                    false, true)) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(
                    () -> completeShellSessionOnMain(session, reason));
        }

        protected void completeShellSessionOnMain(
                ShellGestureSession session, String reason) {
            synchronized (backInputLifecycleLock) {
                if (activeShellSession != session) {
                    log(Log.WARN, TAG,
                            "Ignored stale Shell session completion"
                                    + ", shellSessionId=" + session.id
                                    + ", activeSessionId="
                                    + (activeShellSession == null ? 0L
                                    : activeShellSession.id)
                                    + ", reason=" + reason);
                    return;
                }
                activeShellSession = null;
                shellOwnerUncertain = false;
            }
            clearSystemUiReturnHomeCommitIdentity(
                    session.controller, session.id,
                    "shellFinished:" + reason);
            if (gestureSuppressed) {
                // This DOWN was rejected while the previous Shell navigation was still busy.
                // Finishing that navigation must not reopen the already-rejected physical
                // stream: NativeBackInputMonitor still owns its candidate until UP/CANCEL and
                // would otherwise pilfer a later MOVE before noticing this driver was cleared.
                log(Log.INFO, TAG,
                        "Preserved suppressed SystemUI back stream after Shell completion"
                                + ", shellSessionId=" + session.id
                                + ", reason=" + reason);
                return;
            }
            if (gestureActive) {
                if (recentsVisualOnlyGesture) {
                    log(Log.INFO, TAG,
                            "Preserved visual-only Recents gesture after Shell cancellation"
                                    + ", shellSessionId=" + session.id
                                    + ", reason=" + reason);
                    return;
                }
                clearLocalGestureState();
                log(Log.WARN, TAG, "Cleared local gesture after Shell animation completion"
                        + ", shellSessionId=" + session.id
                        + ", reason=" + reason);
            }
        }

        protected boolean queueShellReleaseTransaction(ShellGestureSession session,
                                                       float rawX, float rawY,
                                                       float releaseDistance,
                                                       boolean dispatchFinalProgress,
                                                       boolean requestedTrigger,
                                                       int releaseEdge,
                                                       boolean recentsCallback,
                                                       boolean shadeCallback,
                                                       boolean drawerCallback,
                                                       boolean editingCallback,
                                                       boolean aospNullFallback,
                                                       long aospNullInputEpoch,
                                                       MiuiHomeAcceptedInputToken
                                                                inputIdentity) {
            if (session == null || session.completionConsumed.get()
                    || !session.releaseQueued.compareAndSet(false, true)) {
                return false;
            }
            try {
                session.executor.execute(() -> finishGestureOnShellExecutor(
                        session, rawX, rawY, releaseDistance,
                        dispatchFinalProgress, requestedTrigger, releaseEdge,
                        recentsCallback, shadeCallback, drawerCallback, editingCallback,
                        aospNullFallback, aospNullInputEpoch, inputIdentity));
                return true;
            } catch (Throwable throwable) {
                // A release must never fall back to mutating controller/tracker state from
                // the input Looper. Fail closed if the owner executor cannot be reached.
                log(Log.ERROR, TAG, "Failed to queue complete Shell release transaction"
                                + ", shellSessionId=" + session.id,
                        throwable);
                session.awaitingStockCleanup.set(true);
                shellOwnerUncertain = true;
                return false;
            }
        }

        protected void finishGestureOnShellExecutor(ShellGestureSession session,
                                                   float rawX, float rawY,
                                                  float releaseDistance,
                                                  boolean dispatchFinalProgress,
                                                  boolean requestedTrigger,
                                                  int releaseEdge,
                                                  boolean recentsCallback,
                                                  boolean shadeCallback,
                                                  boolean drawerCallback,
                                                  boolean editingCallback,
                                                  boolean aospNullFallback,
                                                  long aospNullInputEpoch,
                                                  MiuiHomeAcceptedInputToken
                                                           inputIdentity) {
            Object releaseController = session.controller;
            Object tracker = null;
            try {
                if (session.moveFailed.get()) {
                    requestedTrigger = false;
                    log(Log.WARN, TAG,
                            "Forced Shell release cancellation after MOVE failure"
                                    + ", shellSessionId=" + session.id);
                }
                if (!isShellSessionOwnerCurrent(session)) {
                    if (requestedTrigger) {
                        log(Log.WARN, TAG,
                                "Forced Shell release cancellation after owner changed on executor"
                                        + ", shellSessionId=" + session.id
                                        + ", sessionController="
                                        + shortObject(session.controller)
                                        + ", currentController="
                                        + shortObject(controller)
                                        + ", sessionInputEpoch="
                                        + session.inputEpoch
                                        + ", currentInputEpoch="
                                        + inputMonitorEpoch.get());
                    }
                    requestedTrigger = false;
                }
                tracker = invokeAnyMethod(releaseController,
                        "getActiveTracker", new Object[0]);
                if (tracker == null || tracker != session.tracker) {
                    failShellReleaseWithoutTracker(session,
                            "activeTrackerIdentityMismatch");
                    return;
                }
                Object currentNavigation = readField(releaseController,
                        "mBackNavigationInfo");
                if (currentNavigation != session.navigation) {
                    failShellReleaseWithoutTracker(session,
                            "navigationIdentityMismatch");
                    return;
                }
                applyProgressThresholds(tracker, session.linearDistance,
                        session.maxDistance, session.nonLinearFactor);
                ((BackTouchTracker) tracker).update(rawX, rawY);
                // BackPanelController posts its terminal ACTIVE/INACTIVE decision through
                // BackAnimationImpl before this release transaction. Preserve that ordered
                // decision: an INACTIVE panel has already supplied trigger=false even when the
                // finger remains beyond the module's fixed 48dp threshold. The fixed threshold
                // is still a necessary condition and may veto a native trigger, but it must not
                // turn a native cancellation back into a commit.
                boolean nativeTriggerBeforeThresholdVeto = Boolean.TRUE.equals(
                        invokeAnyMethod(tracker, "getTriggerBack", new Object[0]));
                if (!requestedTrigger && nativeTriggerBeforeThresholdVeto) {
                    invokeAnyMethod(releaseController, "setTriggerBack",
                            new Object[]{Boolean.FALSE});
                }
                tracker = invokeAnyMethod(releaseController,
                        "getActiveTracker", new Object[0]);
                if (tracker == null || tracker != session.tracker) {
                    failShellReleaseWithoutTracker(session,
                            "postTriggerTrackerIdentityMismatch");
                    return;
                }
                boolean actualTrigger = Boolean.TRUE.equals(invokeAnyMethod(
                        tracker, "getTriggerBack", new Object[0]));
                log(Log.INFO, TAG, "Resolved Shell release trigger"
                        + ", fixedThresholdEligible=" + requestedTrigger
                        + ", nativeTriggerBeforeThresholdVeto="
                        + nativeTriggerBeforeThresholdVeto
                        + ", actualTrigger=" + actualTrigger);
                if (dispatchFinalProgress && !aospNullFallback) {
                    dispatchExplicitProgressOnShell(session, tracker,
                            releaseDistance);
                }
                Object infoObject = readField(releaseController,
                        "mBackNavigationInfo");
                if (infoObject != session.navigation) {
                    failShellReleaseWithoutTracker(session,
                            "releaseNavigationIdentityMismatch");
                    return;
                }
                BackNavigationInfo info = infoObject instanceof BackNavigationInfo
                        ? (BackNavigationInfo) infoObject : null;
                if (info == null) {
                    finishNullNavigationOnShellExecutor(
                            session, tracker, requestedTrigger,
                            actualTrigger, releaseEdge, aospNullFallback,
                            aospNullInputEpoch, inputIdentity);
                    return;
                }
                int focusedTaskId = -1;
                if (actualTrigger) {
                    Object observer = readField(releaseController,
                            "mBackTransitionObserver");
                    Object focusedTaskIdObject = invokeAnyMethod(info,
                            "getFocusedTaskId", new Object[0]);
                    if (!(focusedTaskIdObject instanceof Number)) {
                        throw new IllegalStateException("getFocusedTaskId returned "
                                + shortObject(focusedTaskIdObject));
                    }
                    writeField(observer, "mFocusedTaskId",
                            Integer.valueOf(((Number) focusedTaskIdObject).intValue()));
                    focusedTaskId = ((Number) focusedTaskIdObject).intValue();
                }
                writeField(releaseController, "mThresholdCrossed", Boolean.FALSE);
                writeField(releaseController, "mPointersPilfered", Boolean.FALSE);
                writeField(releaseController, "mBackGestureStarted", Boolean.FALSE);
                setTrackerState(tracker, "FINISHED");

                if (Boolean.TRUE.equals(readField(releaseController,
                        "mPostCommitAnimationInProgress"))) {
                    session.awaitingStockCleanup.set(true);
                    log(Log.WARN, TAG, "Shell release found an existing post-commit animation"
                            + ", actualTrigger=" + actualTrigger
                            + ", edge=" + releaseEdge);
                    return;
                }
                if (actualTrigger && info.getType() == TYPE_RETURN_TO_HOME) {
                    if (focusedTaskId < 0 || inputIdentity == null
                            || inputIdentity.generation
                            != systemUiInputArbiterGeneration) {
                        log(Log.ERROR, TAG,
                                "Could not bind committed return-home to accepted DOWN"
                                        + ", taskId=" + focusedTaskId
                                        + ", input="
                                        + shortObject(inputIdentity)
                                        + ", inputGeneration="
                                        + (inputIdentity == null ? 0L
                                        : inputIdentity.generation)
                                        + ", arbiterGeneration="
                                        + systemUiInputArbiterGeneration);
                    } else {
                        SystemUiReturnHomeCommitIdentity identity =
                                new SystemUiReturnHomeCommitIdentity(
                                        releaseController, session.id,
                                        focusedTaskId, inputIdentity);
                        SystemUiReturnHomeCommitIdentity replaced =
                                systemUiReturnHomeCommitIdentity
                                        .getAndSet(identity);
                        log(Log.INFO, TAG,
                                "Bound committed return-home to accepted DOWN"
                                        + ", taskId=" + focusedTaskId
                                        + ", eventId="
                                        + inputIdentity.eventId
                                        + ", downTime="
                                        + inputIdentity.downTime
                                        + ", replacedTaskId="
                                        + (replaced == null ? -1
                                        : replaced.taskId));
                    }
                }
                if (!info.isPrepareRemoteAnimation()) {
                    invokeAnyMethod(releaseController, "invokeOrCancelBack",
                            new Object[]{tracker});
                    ((BackTouchTracker) tracker).reset();
                    logShellReleaseResult(info, requestedTrigger, actualTrigger,
                            "direct-callback", releaseEdge,
                            recentsCallback, shadeCallback, drawerCallback, editingCallback);
                    completeShellSessionOnOwner(session,
                            "direct-callback");
                    return;
                }

                session.awaitingStockCleanup.set(true);
                int runnerState = inspectRemoteRunnerState(releaseController, info);
                if (runnerState == REMOTE_RUNNER_MISSING
                        || runnerState == REMOTE_RUNNER_CANCELLED) {
                    invokeAnyMethod(releaseController, "invokeOrCancelBack",
                            new Object[]{tracker});
                    ((BackTouchTracker) tracker).reset();
                    logShellReleaseResult(info, requestedTrigger, actualTrigger,
                            runnerState == REMOTE_RUNNER_MISSING
                                    ? "runner-missing" : "runner-cancelled",
                            releaseEdge, recentsCallback, shadeCallback,
                            drawerCallback, editingCallback);
                    completeShellSessionOnOwner(session,
                            runnerState == REMOTE_RUNNER_MISSING
                                    ? "runner-missing" : "runner-cancelled");
                    return;
                }
                if (runnerState == REMOTE_RUNNER_WAITING
                        || runnerState == REMOTE_RUNNER_UNKNOWN) {
                    scheduleShellAnimationTimeout(releaseController);
                    logShellReleaseResult(info, requestedTrigger, actualTrigger,
                            runnerState == REMOTE_RUNNER_WAITING
                                    ? "runner-waiting" : "runner-unknown",
                            releaseEdge, recentsCallback, shadeCallback,
                            drawerCallback, editingCallback);
                    return;
                }
                if (!actualTrigger && info.getType() == TYPE_RETURN_TO_HOME) {
                    prepareReturnHomeCancelTransitionCleanup(releaseController);
                }
                invokeAnyMethod(releaseController,
                        "startPostCommitAnimation", new Object[0]);
                logShellReleaseResult(info, requestedTrigger, actualTrigger,
                        "post-commit", releaseEdge,
                        recentsCallback, shadeCallback, drawerCallback, editingCallback);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Complete Shell release transaction failed; cancelling",
                        throwable);
                cancelFailedShellRelease(session, tracker);
            }
        }

        protected void finishNullNavigationOnShellExecutor(
                ShellGestureSession session, Object tracker,
                boolean requestedTrigger, boolean actualTrigger,
                int releaseEdge, boolean aospNullFallback,
                long aospNullInputEpoch,
                MiuiHomeAcceptedInputToken inputIdentity) throws Exception {
            Object releaseController = session.controller;
            boolean authenticatedFallback;
            boolean commitLegacyBack;
            synchronized (backInputLifecycleLock) {
                authenticatedFallback = aospNullFallback
                        && isCurrentAcceptedInputIdentity(
                        inputIdentity, releaseEdge, releaseController,
                        aospNullInputEpoch);
                commitLegacyBack = authenticatedFallback
                        && requestedTrigger && actualTrigger;
                if (commitLegacyBack) {
                    Object observer = readField(releaseController,
                            "mBackTransitionObserver");
                    writeField(observer, "mFocusedTaskId", Integer.valueOf(-1));
                }
                writeField(releaseController, "mThresholdCrossed", Boolean.FALSE);
                writeField(releaseController, "mPointersPilfered", Boolean.FALSE);
                writeField(releaseController, "mBackGestureStarted", Boolean.FALSE);
                setTrackerState(tracker, "FINISHED");
                if (Boolean.TRUE.equals(readField(releaseController,
                        "mPostCommitAnimationInProgress"))) {
                    session.awaitingStockCleanup.set(true);
                    log(Log.WARN, TAG,
                            "Null-navigation release found an existing post-commit animation"
                                    + ", authenticatedFallback="
                                    + authenticatedFallback
                                    + ", actualTrigger=" + actualTrigger
                                    + ", edge=" + releaseEdge);
                    return;
                }
                ((BackTouchTracker) tracker).reset();
                if (commitLegacyBack) {
                    injectLegacyBackKey(releaseController);
                }
                invokeAnyMethod(releaseController, "finishBackNavigation",
                        new Object[]{Boolean.valueOf(commitLegacyBack)});
            }
            completeShellSessionOnOwner(session,
                    commitLegacyBack ? "null-navigation-commit"
                            : "null-navigation-cancel");
            log(commitLegacyBack ? Log.INFO : Log.WARN, TAG,
                    "Finished released gesture with null navigation"
                            + ", requestedTrigger=" + requestedTrigger
                            + ", actualTrigger=" + actualTrigger
                            + ", aospFallbackRequested=" + aospNullFallback
                            + ", authenticatedFallback=" + authenticatedFallback
                            + ", focusedTaskId="
                            + (commitLegacyBack ? "-1" : "unchanged")
                            + ", legacyBackCommitted=" + commitLegacyBack
                            + ", edge=" + releaseEdge);
        }

        protected void prepareReturnHomeCancelTransitionCleanup(
                Object releaseController) {
            try {
                Object transitionHandler = readField(releaseController,
                        "mBackTransitionHandler");
                Object prepareOpen = readField(transitionHandler,
                        "mPrepareOpenTransition");
                Object prepareClose = readField(transitionHandler,
                        "mClosePrepareTransition");
                Object closeRequested = readField(transitionHandler,
                        "mCloseTransitionRequested");
                if (prepareOpen == null) {
                    return;
                }
                boolean staleCloseRequested = prepareClose == null
                        && Boolean.TRUE.equals(closeRequested);
                if (staleCloseRequested) {
                    // A preceding committed Xiaomi close can finish without entering Shell's
                    // handleCloseTransition() callback, leaving this flag true after both
                    // prepared-transition tokens are gone. If it survives into a later cancel,
                    // stock finishBackAnimation() skips createClosePrepareTransition() and WM
                    // keeps the previous composed navigation indefinitely. Clear only that
                    // stale gate for this exact prepared return-home cancellation; stock
                    // startPostCommitAnimation()/finishBackAnimation() still own restoreBackNavi.
                    writeField(transitionHandler, "mCloseTransitionRequested",
                            Boolean.FALSE);
                }
                log(Log.INFO, TAG,
                        "Prepared stock return-to-home cancel transition cleanup"
                                + ", staleCloseRequested="
                                + staleCloseRequested
                                + ", prepareOpen="
                                + shortObject(prepareOpen)
                                + ", prepareClose="
                                + shortObject(prepareClose));
            } catch (Throwable throwable) {
                // Keep stock cancellation moving even when a vendor field changes. Its normal
                // timeout remains safer than finishing a prepared transition from this driver.
                log(Log.WARN, TAG,
                        "Failed to prepare return-to-home cancel transition cleanup",
                        throwable);
            }
        }

        protected void dispatchExplicitProgressOnShell(ShellGestureSession session,
                                                      Object tracker,
                                                      float distance) {
            try {
                Object callback = readField(session.controller,
                        "mActiveCallback");
                float progress = Math.max(0.0f,
                        Math.min(1.0f, distance
                                / Math.max(1.0f, session.maxDistance)));
                BackMotionEvent progressEvent =
                        ((BackTouchTracker) tracker).createProgressEvent(progress);
                invokeAnyMethod(session.controller, "dispatchOnBackProgressed",
                        new Object[]{callback, progressEvent});
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispatch final progress on Shell executor",
                        throwable);
            }
        }

        protected int inspectRemoteRunnerState(Object releaseController,
                                             BackNavigationInfo info) {
            try {
                Object registry = readField(releaseController,
                        "mShellBackAnimationRegistry");
                Object definitions = readField(registry, "mAnimationDefinition");
                Object runner = invokeAnyMethod(definitions, "get",
                        new Object[]{Integer.valueOf(info.getType())});
                if (runner == null) {
                    return REMOTE_RUNNER_MISSING;
                }
                Object cancelled = readField(runner, "mAnimationCancelled");
                if (!(cancelled instanceof Boolean)) {
                    throw new IllegalStateException("mAnimationCancelled is "
                            + shortObject(cancelled));
                }
                if (Boolean.TRUE.equals(cancelled)) {
                    return REMOTE_RUNNER_CANCELLED;
                }
                Object waiting = readField(runner, "mWaitingAnimation");
                if (!(waiting instanceof Boolean)) {
                    throw new IllegalStateException("mWaitingAnimation is "
                            + shortObject(waiting));
                }
                return Boolean.TRUE.equals(waiting)
                        ? REMOTE_RUNNER_WAITING : REMOTE_RUNNER_READY;
            } catch (Throwable throwable) {
                // Unknown is deliberately not treated as missing/cancelled. The tracker stays
                // FINISHED and Shell's own timeout is allowed to resolve the navigation.
                log(Log.WARN, TAG, "Remote runner state is unknown; waiting for Shell timeout",
                        throwable);
                return REMOTE_RUNNER_UNKNOWN;
            }
        }

        protected void scheduleShellAnimationTimeout(Object releaseController) {
            try {
                Object executor = readField(releaseController, "mShellExecutor");
                Object timeout = readField(releaseController,
                        "mAnimationTimeoutRunnable");
                invokeAnyMethod(executor, "executeDelayed",
                        new Object[]{timeout, Long.valueOf(2000L)});
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to schedule required Shell animation timeout",
                        throwable);
            }
        }

        protected void failShellReleaseWithoutTracker(
                ShellGestureSession session, String reason) {
            session.awaitingStockCleanup.set(true);
            scheduleShellAnimationTimeout(session.controller);
            log(Log.ERROR, TAG,
                    "Kept failed Shell session for stock timeout"
                            + ", shellSessionId=" + session.id
                            + ", reason=" + reason
                            + ", tracker=" + shortObject(session.tracker)
                            + ", navigation="
                            + shortObject(session.navigation));
        }

        protected void cancelFailedShellRelease(ShellGestureSession session,
                                              Object tracker) {
            Object releaseController = session.controller;
            try {
                Object activeTracker = invokeAnyMethod(releaseController,
                        "getActiveTracker", new Object[0]);
                Object infoObject = readField(releaseController,
                        "mBackNavigationInfo");
                if (tracker == null || tracker != session.tracker
                        || activeTracker != session.tracker
                        || infoObject != session.navigation) {
                    failShellReleaseWithoutTracker(session,
                            "failedReleaseIdentityMismatch");
                    return;
                }
                writeField(releaseController, "mThresholdCrossed", Boolean.FALSE);
                writeField(releaseController, "mPointersPilfered", Boolean.FALSE);
                writeField(releaseController, "mBackGestureStarted", Boolean.FALSE);
                invokeAnyMethod(tracker, "setTriggerBack",
                        new Object[]{Boolean.FALSE});
                setTrackerState(tracker, "FINISHED");
                if (Boolean.TRUE.equals(readField(releaseController,
                        "mPostCommitAnimationInProgress"))) {
                    session.awaitingStockCleanup.set(true);
                    scheduleShellAnimationTimeout(releaseController);
                    return;
                }
                BackNavigationInfo info = infoObject instanceof BackNavigationInfo
                        ? (BackNavigationInfo) infoObject : null;
                if (info == null) {
                    ((BackTouchTracker) tracker).reset();
                    invokeAnyMethod(releaseController, "finishBackNavigation",
                            new Object[]{Boolean.FALSE});
                    completeShellSessionOnOwner(session,
                            "failed-null-navigation-cancel");
                    return;
                }
                if (!info.isPrepareRemoteAnimation()) {
                    invokeAnyMethod(releaseController, "invokeOrCancelBack",
                            new Object[]{tracker});
                    ((BackTouchTracker) tracker).reset();
                    completeShellSessionOnOwner(session,
                            "failed-direct-cancel");
                    return;
                }
                session.awaitingStockCleanup.set(true);
                int runnerState = inspectRemoteRunnerState(
                        releaseController, info);
                if (runnerState == REMOTE_RUNNER_MISSING
                        || runnerState == REMOTE_RUNNER_CANCELLED) {
                    invokeAnyMethod(releaseController, "invokeOrCancelBack",
                            new Object[]{tracker});
                    ((BackTouchTracker) tracker).reset();
                    completeShellSessionOnOwner(session,
                            runnerState == REMOTE_RUNNER_MISSING
                                    ? "failed-runner-missing"
                                    : "failed-runner-cancelled");
                    return;
                }
                if (runnerState == REMOTE_RUNNER_WAITING
                        || runnerState == REMOTE_RUNNER_UNKNOWN) {
                    scheduleShellAnimationTimeout(releaseController);
                    return;
                }
                if (info.getType() == TYPE_RETURN_TO_HOME) {
                    prepareReturnHomeCancelTransitionCleanup(
                            releaseController);
                }
                invokeAnyMethod(releaseController,
                        "startPostCommitAnimation", new Object[0]);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to cancel broken Shell release transaction",
                        throwable);
                failShellReleaseWithoutTracker(session,
                        "failedReleaseCancellationException");
            }
        }

        protected void logShellReleaseResult(BackNavigationInfo info,
                                           boolean requestedTrigger,
                                           boolean actualTrigger,
                                           String outcome, int releaseEdge,
                                           boolean recentsCallback,
                                           boolean shadeCallback,
                                           boolean drawerCallback,
                                           boolean editingCallback) {
            log(Log.INFO, TAG, "Completed Shell release transaction"
                    + ", type=" + info.getType()
                    + ", requestedTrigger=" + requestedTrigger
                    + ", actualTrigger=" + actualTrigger
                    + ", outcome=" + outcome
                    + ", recentsShellCallback=" + recentsCallback
                    + ", shadeShellCallback=" + shadeCallback
                    + ", drawerShellCallback=" + drawerCallback
                    + ", editingShellCallback=" + editingCallback
                    + ", edge=" + releaseEdge);
        }

        protected void setTrackerState(Object tracker, String stateName) throws Exception {
            BackTouchTracker.TouchTrackerState state =
                    BackTouchTracker.TouchTrackerState.valueOf(stateName);
            ((BackTouchTracker) tracker).setState(state);
        }

        protected float dp(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }

        protected boolean updateTriggerBack(boolean newTriggerBack) {
            if (triggerBack == newTriggerBack) {
                return false;
            }
            triggerBack = newTriggerBack;
            log(Log.INFO, TAG,
                    "SystemUI gesture driver triggerBack=" + newTriggerBack);
            return true;
        }

        protected boolean queueShellMove(
                ShellGestureSession session, float rawX, float rawY,
                float distance, boolean crossedNow,
                boolean dispatchProgress, boolean triggerChanged,
                boolean newTriggerBack) {
            if (session == null || session.completionConsumed.get()
                    || session.releaseQueued.get()
                    || session.moveFailed.get()) {
                return false;
            }
            try {
                session.executor.execute(() -> {
                    Object tracker = null;
                    try {
                        if (session.completionConsumed.get()
                                || session.moveFailed.get()) {
                            return;
                        }
                        tracker = invokeAnyMethod(session.controller,
                                "getActiveTracker", new Object[0]);
                        Object navigation = readField(session.controller,
                                "mBackNavigationInfo");
                        boolean receivedNull = Boolean.TRUE.equals(readField(
                                session.controller,
                                "mReceivedNullNavigationInfo"));
                        if (tracker != session.tracker
                                || navigation != session.navigation
                                || (session.navigation == null
                                && receivedNull
                                != session.receivedNullNavigation)) {
                            throw new IllegalStateException(
                                    "Shell MOVE identity changed"
                                            + ", tracker="
                                            + shortObject(tracker)
                                            + ", navigation="
                                            + shortObject(navigation)
                                            + ", receivedNull="
                                            + receivedNull);
                        }
                        applyProgressThresholds(tracker,
                                session.linearDistance,
                                session.maxDistance,
                                session.nonLinearFactor);
                        ((BackTouchTracker) tracker).update(rawX, rawY);
                        if (crossedNow) {
                            invokeAnyMethod(session.controller,
                                    "onThresholdCrossed", new Object[0]);
                        }
                        if (dispatchProgress) {
                            dispatchExplicitProgressOnShell(
                                    session, tracker, distance);
                        }
                        if (triggerChanged) {
                            invokeAnyMethod(session.controller,
                                    "setTriggerBack", new Object[]{
                                            Boolean.valueOf(newTriggerBack)});
                        }
                    } catch (Throwable throwable) {
                        session.moveFailed.set(true);
                        log(Log.ERROR, TAG,
                                "Fixed Shell-owner MOVE failed; cancelling"
                                        + ", shellSessionId=" + session.id,
                                throwable);
                        if (session.releaseQueued.compareAndSet(
                                false, true)) {
                            cancelFailedShellRelease(session, tracker);
                        }
                    }
                });
                return true;
            } catch (Throwable throwable) {
                session.moveFailed.set(true);
                log(Log.ERROR, TAG,
                        "Failed to queue fixed Shell-owner MOVE"
                                + ", shellSessionId=" + session.id,
                        throwable);
                cleanupRejectedShellGesture(session);
                return false;
            }
        }

        protected void applyProgressThresholds(
                Object tracker, float linearDistance,
                float maxDistance, float nonLinearFactor) {
            ((BackTouchTracker) tracker).setProgressThresholds(
                    linearDistance, maxDistance, nonLinearFactor);
        }

        protected void dispatchLegacyInterruptBack() {
            if (legacyRunningOpenInfo == null) {
                log(Log.WARN, TAG, "Missing correlated OPEN info for legacy interruption; "
                        + "using ordinary BACK without duplicate guard");
                dispatchRealBack();
                return;
            }
            LegacyBackAttempt attempt = armLegacyBackGuard(controller,
                    legacyRunningOpenInfo);
            Object previousMarker = moduleLegacyBackInjection.get();
            moduleLegacyBackInjection.set(attempt);
            try {
                dispatchRealBack();
            } finally {
                if (previousMarker == null) {
                    moduleLegacyBackInjection.remove();
                } else {
                    moduleLegacyBackInjection.set(previousMarker);
                }
            }
        }

        protected void dispatchRealBack() {
            try {
                Object info = readField(controller, "mBackNavigationInfo");
                Object callback = info == null ? null
                        : ((BackNavigationInfo) info).getOnBackInvokedCallback();
                if (callback != null) {
                    try {
                        invokeAnyMethod(controller, "dispatchOnBackInvoked",
                                new Object[]{callback});
                    } catch (Throwable ignored) {
                        invokeAnyMethod(callback, "onBackInvoked", new Object[0]);
                    }
                    log(Log.INFO, TAG, "Dispatched real back callback, info="
                            + shortObject(info) + ", callback=" + shortObject(callback));
                    return;
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispatch real back callback", throwable);
            }
            injectLegacyBackKey();
        }

        protected void injectLegacyBackKey() {
            injectLegacyBackKey(controller);
        }

        protected void injectLegacyBackKey(Object injectionController) {
            if (injectionController == null) {
                log(Log.ERROR, TAG, "Cannot inject legacy BACK without a controller");
                return;
            }
            Object previousMarker = moduleLegacyBackInjection.get();
            if (previousMarker == null) {
                moduleLegacyBackInjection.set(this);
            }
            try {
                try {
                    invokeAnyMethod(injectionController, "injectBackKey", new Object[0]);
                    log(Log.INFO, TAG, "Injected legacy back key via controller");
                    return;
                } catch (Throwable throwable) {
                    if (!(throwable instanceof NoSuchMethodException)) {
                        log(Log.WARN, TAG, "Optional injectBackKey failed; using send pair",
                                throwable);
                    }
                }
                boolean downSent = false;
                try {
                    invokeAnyMethod(injectionController, "sendBackEvent",
                            new Object[]{Integer.valueOf(KEY_ACTION_DOWN)});
                    downSent = true;
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "Failed to inject legacy BACK down", throwable);
                } finally {
                    if (downSent) {
                        try {
                            invokeAnyMethod(injectionController, "sendBackEvent",
                                    new Object[]{Integer.valueOf(KEY_ACTION_UP)});
                            log(Log.INFO, TAG, "Injected legacy back key via sendBackEvent");
                        } catch (Throwable throwable) {
                            log(Log.ERROR, TAG, "Failed to inject legacy BACK up", throwable);
                        }
                    }
                }
            } finally {
                if (previousMarker == null) {
                    moduleLegacyBackInjection.remove();
                } else {
                    moduleLegacyBackInjection.set(previousMarker);
                }
            }
        }

        protected boolean dispatchToEdgePlugin(MotionEvent event, int edge) {
            MotionEvent screenEvent = null;
            try {
                Object plugin = readField(edgeBackGestureHandler, "mEdgeBackPlugin");
                if (plugin == null) {
                    log(Log.WARN, TAG, "NavigationEdgeBackPlugin is null; native panel unavailable");
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    prepareNativeBackPanel(plugin);
                }
                invokeMethod(plugin, "setIsLeftPanel",
                        new Class<?>[]{boolean.class},
                        new Object[]{Boolean.valueOf(edge == EDGE_LEFT)});
                screenEvent = MotionEvent.obtain(event);
                screenEvent.setLocation(event.getRawX(), event.getRawY());
                invokeMethod(plugin, "onMotionEvent",
                        new Class<?>[]{MotionEvent.class}, new Object[]{screenEvent});
                return true;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to dispatch event to NavigationEdgeBackPlugin",
                        throwable);
                return false;
            } finally {
                if (screenEvent != null) {
                    screenEvent.recycle();
                }
            }
        }

        protected String readNativePanelState() {
            try {
                Object plugin = readField(
                        edgeBackGestureHandler, "mEdgeBackPlugin");
                Object state = plugin == null ? null
                        : readField(plugin, "currentState");
                return state instanceof Enum<?>
                        ? ((Enum<?>) state).name() : null;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to read native BackPanelController state",
                        throwable);
                return null;
            }
        }

        protected Boolean resolveNativePanelReleaseTrigger(
                String stateAfterRelease, boolean releaseDelivered) {
            if (!releaseDelivered || stateAfterRelease == null) {
                return null;
            }
            switch (stateAfterRelease) {
                case "CANCELLED":
                case "GONE":
                    return Boolean.FALSE;
                case "ENTRY":
                case "ACTIVE":
                case "INACTIVE":
                case "FLUNG":
                case "COMMITTED":
                    // On Xiaomi's native panel an UP that remains ENTRY/ACTIVE/INACTIVE is
                    // the delayed/fling commit branch. A non-committing release changes to
                    // CANCELLED synchronously before onMotionEvent() returns.
                    return Boolean.TRUE;
                default:
                    return null;
            }
        }

        protected void prepareNativeBackPanel(Object plugin) {
            try {
                invokeAnyMethod(plugin, "updateConfiguration$1", new Object[0]);
                invokeAnyMethod(plugin, "updateRestingArrowDimens", new Object[0]);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to prepare native AOSP back panel", throwable);
            }
        }
    }
}
