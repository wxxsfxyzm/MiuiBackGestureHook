package dev.codex.miuibackgesturehook.hooks.miuihome;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;
import static dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeReturnHomeHook.*;
import static dev.codex.miuibackgesturehook.data.ReturnHomeData.*;

import dev.codex.miuibackgesturehook.BuildConfig;
import dev.codex.miuibackgesturehook.NativeHookStatusProtocol;
import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.util.HookerBridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.window.BackMotionEvent;
import android.window.BackProgressAnimator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

abstract class MiuiHomeReturnHomeStateImpl extends HookerBridge {
    protected static final String TAG = "MiuiBackGestureHook";
    protected static final String BUILD_MARK =
            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    protected static final String SYSTEM_UI = "com.android.systemui";
    protected static final String MIUI_HOME = "com.miui.home";
    protected static final int ANDROID_17_API_LEVEL = 37;

    protected static final int UNIFIED_CONFIG_HOOK_PENDING = 0;
    protected static final int UNIFIED_CONFIG_HOOK_RUNNING = 1;
    protected static final int UNIFIED_CONFIG_HOOK_COMPLETED = 2;
    protected static final int EDGE_LEFT = 0;
    protected static final int EDGE_RIGHT = 1;
    protected static final int ACTIVITY_TYPE_STANDARD = 1;
    protected static final int ACTIVITY_TYPE_HOME = 2;
    protected static final int WINDOWING_MODE_FULLSCREEN = 1;
    protected static final int WINDOWING_MODE_FREEFORM = 5;
    protected static final int FLAG_TRANSLUCENT = 1 << 2;
    protected static final int FLAG_FILLS_TASK = 1 << 10;
    protected static final int FLAG_IS_OCCLUDED = 1 << 15;
    protected static final int FLAG_BACK_GESTURE_ANIMATED = 1 << 17;
    protected static final int FLAG_DISPLAY_CHANGE = 1 << 27;
    protected static final int FLAG_IS_ELEMENT = Integer.MIN_VALUE;
    protected static final int FLAG_ONLY_ACTIVITY_RECORD = 1 << 26;
    protected static final int TRANSIT_OPEN = 1;
    protected static final int TRANSIT_CLOSE = 2;
    protected static final int TRANSIT_TO_FRONT = 3;
    protected static final int TRANSIT_TO_BACK = 4;
    protected static final int LAUNCHER_OPEN_BREAK_RESULT_NO_RECEIVER = 0;
    protected static final int LAUNCHER_OPEN_BREAK_RESULT_REJECTED = 1;
    protected static final int LAUNCHER_OPEN_BREAK_RESULT_ACCEPTED = 2;
    protected static final int TYPE_RETURN_TO_HOME = 1;
    protected static final int TYPE_CROSS_ACTIVITY = 2;
    protected static final int TYPE_CROSS_TASK = 3;
    protected static final int TYPE_CALLBACK = 4;

    protected static final float RETURN_HOME_PREVIEW_BLUR_DISTANCE_DP = 48.0f;
    protected static final float RETURN_HOME_MIN_WINDOW_SCALE = 0.85f;
    protected static final float RETURN_HOME_WINDOW_MARGIN_DP = 8.0f;
    protected static final float RETURN_HOME_END_CORNER_RADIUS_DP = 28.0f;
    protected static final int RETURN_HOME_TERMINAL_NONE = 0;
    protected static final int RETURN_HOME_TERMINAL_CANCEL = 1;
    protected static final int RETURN_HOME_TERMINAL_INVOKE = 2;
    protected static final int RETURN_HOME_GEOMETRY_SOURCE_ANIM_UPDATE = 1;
    protected static final int RETURN_HOME_GEOMETRY_SOURCE_SURFACE_PARAMS = 2;
    protected static final long RETURN_HOME_NATIVE_TIMEOUT_MS = 1800L;
    protected static final int MIUI_SURFACE_PARAM_FLAG_MATRIX = 2;
    protected static final int MIUI_SURFACE_PARAM_FLAG_WINDOW_CROP = 4;
    protected static final int MIUI_SURFACE_PARAM_FLAG_CORNER_RADIUS = 16;
    protected static final int MIUI_SURFACE_PARAM_FLAG_SHOW = 512;
    protected static final String MIUI_HOME_ICON_CLICK_WITHOUT_RECENT_REASON =
            "Icon click without recent.";
    protected static final ComponentName PERMISSION_GRANT_ACTIVITY = new ComponentName(
            "com.android.permissioncontroller",
            "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity");
    protected static final long RETURN_HOME_DIRECT_CANCEL_CLEANUP_GUARD_MS = 500L;

    protected static final String MODULE_MIUI_OVERVIEW_STATE_CHANGE =
            "dev.codex.miuibackgesturehook.action.MIUI_OVERVIEW_STATE_CHANGE";
    protected static final String MODULE_MIUI_HOME_OPEN_BREAK_COMMAND =
            "dev.codex.miuibackgesturehook.action.MIUI_HOME_OPEN_BREAK";
    protected static final String MODULE_SYSTEMUI_INPUT_ARBITER_STATE =
            NativeHookStatusProtocol.ACTION_SYSTEMUI_STATE;
    protected static final String MODULE_MIUI_HOME_INPUT_ARBITER_QUERY =
            "dev.codex.miuibackgesturehook.action.MIUI_HOME_INPUT_ARBITER_QUERY";
    protected static final String MODULE_RUNTIME_STATUS_QUERY =
            NativeHookStatusProtocol.ACTION_QUERY;
    protected static final String MODULE_RUNTIME_STATUS_REPLY =
            NativeHookStatusProtocol.ACTION_REPLY;
    protected static final String EXTRA_STATUS_NONCE = NativeHookStatusProtocol.EXTRA_NONCE;
    protected static final String EXTRA_STATUS_QUERY = NativeHookStatusProtocol.EXTRA_QUERY;
    protected static final String EXTRA_STATUS_LEGACY_READY =
            NativeHookStatusProtocol.EXTRA_LEGACY_READY;
    protected static final String EXTRA_INPUT_ARBITER_READY = "input_arbiter_ready";
    protected static final String EXTRA_INPUT_ARBITER_GENERATION =
            "input_arbiter_generation";
    protected static final String EXTRA_CONTEXTUAL_SEARCH_ENABLED =
            "contextual_search_enabled";
    protected static final String EXTRA_INPUT_ACCEPTED = "input_accepted";
    protected static final String EXTRA_INPUT_EVENT_ID = "input_event_id";
    protected static final String EXTRA_INPUT_DOWN_TIME = "input_down_time";
    protected static final String EXTRA_INPUT_DEVICE_ID = "input_device_id";
    protected static final String EXTRA_INPUT_SOURCE = "input_source";
    protected static final String EXTRA_INPUT_DISPLAY_ID = "input_display_id";
    protected static final String EXTRA_INPUT_EDGE = "input_edge";
    protected static final String EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE =
            "launcher_open_break_available";
    protected static final String EXTRA_LAUNCHER_OPEN_ACTIVE = "launcher_open_active";
    protected static final String EXTRA_LAUNCHER_OPEN_BREAK_GENERATION =
            "launcher_open_break_generation";
    protected static final String EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT =
            "launcher_open_break_attempt";
    protected static final String EXTRA_LAUNCHER_EDITING = "launcher_editing";
    protected static final String EXTRA_LAUNCHER_FOLDER_VISIBLE =
            "launcher_folder_visible";
    protected static final String EXTRA_RETURN_HOME_COMMIT_TASK_ID =
            "return_home_commit_task_id";
    protected static final String EXTRA_RETURN_HOME_COMMIT_DEBUG_ID =
            "return_home_commit_debug_id";
    protected static final String EXTRA_RETURN_HOME_COMMIT_ATTEMPT =
            "return_home_commit_attempt";
    protected static final String EXTRA_RETURN_HOME_ELEMENT_BOUNDARY =
            "return_home_element_boundary";
    protected static final String EXTRA_RETURN_HOME_FINISH_ATTEMPT =
            "return_home_finish_attempt";
    protected static final String EXTRA_RETURN_HOME_RUNNER_SESSION =
            "return_home_runner_session";
    protected static final String EXTRA_LAUNCHER_XIAOAI_VISIBLE = "xiaoai_visible";

    protected static final String SHELL_BACK_ANIMATION_DESCRIPTOR =
            "com.android.wm.shell.back.IBackAnimation";
    protected static final String ON_BACK_INVOKED_CALLBACK_DESCRIPTOR =
            android.window.IOnBackInvokedCallback.class.getName();
    protected static final String REMOTE_ANIMATION_RUNNER_DESCRIPTOR =
            android.view.IRemoteAnimationRunner.class.getName();
    protected static final String REMOTE_ANIMATION_FINISHED_DESCRIPTOR =
            android.view.IRemoteAnimationFinishedCallback.class.getName();
    protected static final int SHELL_BACK_SET_LAUNCHER_CALLBACK_TRANSACTION = 1;
    protected static final int SHELL_BACK_CLEAR_LAUNCHER_CALLBACK_TRANSACTION = 2;

    protected final AtomicBoolean runtimeStarted = new AtomicBoolean();
    protected final AtomicBoolean runtimeReleased = new AtomicBoolean();
    protected final AtomicLong miuiHomeReturnHomeGenerationIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong miuiHomeLauncherOpenSnapshotIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong miuiHomeNativeGeometryFrameIds = new AtomicLong();
    protected final AtomicLong systemUiReturnHomeCommitAttemptIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong miuiHomeOpenBreakGenerationIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong miuiHomeOpenBreakCallbackEpoch = new AtomicLong();
    protected final AtomicInteger systemUiInputArbiterMonitorCount = new AtomicInteger();
    protected final ThreadLocal<ReturnHomeNativeGeometrySnapshot>
            miuiHomePendingNativeGeometry = new ThreadLocal<>();
    protected final ThreadLocal<ReturnHomeFinishTransferCandidate>
            returnHomeFinishTransferCandidate = new ThreadLocal<>();
    protected final AtomicReference<MiuiHomeAcceptedInputToken>
            miuiHomeAcceptedInputIdentity = new AtomicReference<>();
    protected final AtomicReference<MiuiHomeAcceptedInputToken>
            acceptedInputToken = new AtomicReference<>();
    protected final AtomicReference<SystemUiReturnHomeCommitIdentity>
            systemUiReturnHomeCommitIdentity = new AtomicReference<>();
    protected final AtomicReference<MiuiHomeLocalHandoffToken>
            miuiHomeLocalHandoffToken = new AtomicReference<>();
    protected final AtomicReference<MiuiHomeLauncherOpenSnapshot>
            miuiHomeLauncherOpenSnapshot = new AtomicReference<>();
    protected final AtomicReference<MiuiHomePermissionMergeToken>
            miuiHomePermissionMergeToken = new AtomicReference<>();
    protected final long systemUiInputArbiterGeneration =
            Math.max(1L, SystemClock.elapsedRealtimeNanos());
    protected volatile boolean acceptingBackInputInstalls = true;
    protected volatile boolean miuiOverviewVisible;
    protected volatile boolean miuiDrawerVisible;
    protected volatile boolean miuiFolderVisible;
    protected volatile boolean miuiLauncherEditing;
    protected volatile long miuiLauncherDartStateOwnerEpoch;
    protected volatile boolean miuiLauncherXiaoAiVisible;
    protected volatile boolean miuiHomeEditingStatePublished;
    protected volatile boolean miuiLauncherOpenActive;
    protected volatile boolean miuiLauncherOpenBreakAvailable;
    protected volatile long miuiLauncherOpenBreakGeneration;
    protected volatile long miuiOverviewDismissPendingUntilUptime;
    protected volatile Object miuiHomeOpenBreakController;
    protected volatile boolean miuiHomeOpenBreakCommandPending;
    protected volatile long miuiHomeOpenBreakGeneration;
    protected volatile Object miuiHomeOpenBreakStateManager;
    protected volatile Object miuiHomeOpenBreakAnimationIdentity;
    protected volatile boolean miuiHomeOpenBreakGenerationPrepared;
    protected volatile boolean miuiHomeOpenBreakAnimationActive;
    protected volatile Context miuiHomeOpenBreakContext;
    protected volatile Context miuiHomeOpenBreakCommandReceiverContext;
    protected volatile BroadcastReceiver miuiHomeOpenBreakCommandReceiver;
    protected volatile Context miuiHomeInputArbiterReceiverContext;
    protected volatile BroadcastReceiver miuiHomeInputArbiterReceiver;
    protected volatile Context miuiOverviewReceiverContext;
    protected volatile BroadcastReceiver miuiOverviewReceiver;
    protected volatile IBinder miuiHomeReturnHomeBinder;
    protected volatile IBinder pendingMiuiHomeReturnHomeBinder;
    protected volatile ClassLoader pendingMiuiHomeReturnHomeClassLoader;
    protected volatile Context pendingMiuiHomeReturnHomeContext;
    protected volatile String pendingMiuiHomeReturnHomeReason;
    protected volatile boolean miuiHomeSystemUiInputArbiterReady;
    protected volatile long miuiHomeSystemUiInputArbiterGeneration;
    protected volatile SharedPreferences contextualSearchPreferences;
    protected volatile boolean contextualSearchPreferencesFailureLogged;
    protected volatile boolean contextualSearchServiceUnavailableLogged;
    protected volatile boolean moduleLoggingEnabled =
            PredictiveBackPreferences.DEFAULT_MODULE_LOGGING;
    protected volatile SharedPreferences moduleLoggingPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener
            moduleLoggingPreferenceListener = (preferences, key) -> {
                if (!PredictiveBackPreferences.KEY_MODULE_LOGGING.equals(key)) {
                    return;
                }
                try {
                    moduleLoggingEnabled = preferences.getBoolean(
                            PredictiveBackPreferences.KEY_MODULE_LOGGING,
                            PredictiveBackPreferences.DEFAULT_MODULE_LOGGING);
                } catch (Throwable ignored) {
                    // Keep the last known logging policy when the remote preference is unreadable.
                }
            };

    public void start() {
        if (runtimeStarted.compareAndSet(false, true)) {
            initializeModuleLoggingPreference();
            moduleLog(Log.INFO, TAG, "Starting MiuiHome hook state, build="
                    + BUILD_MARK + ", process=" + packageName);
        }
    }

    public boolean isHotReloadSafe() {
        return true;
    }

    public void releaseForHotReload() {
        if (!runtimeReleased.compareAndSet(false, true)) {
            return;
        }
        acceptedInputToken.set(null);
        miuiHomeAcceptedInputIdentity.set(null);
        miuiHomeLocalHandoffToken.set(null);
        miuiHomeLauncherOpenSnapshot.set(null);
        miuiHomePermissionMergeToken.set(null);
        systemUiReturnHomeCommitIdentity.set(null);
        miuiHomePendingNativeGeometry.remove();
        returnHomeFinishTransferCandidate.remove();
        releaseModuleLoggingPreference();
    }

    public boolean miuiOverviewVisibleState() {
        return miuiOverviewVisible;
    }

    public void miuiOverviewVisibleState(boolean value) {
        miuiOverviewVisible = value;
    }

    public boolean miuiDrawerVisibleState() {
        return miuiDrawerVisible;
    }

    public void miuiDrawerVisibleState(boolean value) {
        miuiDrawerVisible = value;
    }

    public boolean miuiFolderVisibleState() {
        return miuiFolderVisible;
    }

    public void miuiFolderVisibleState(boolean value) {
        miuiFolderVisible = value;
    }

    public boolean miuiLauncherEditingState() {
        return miuiLauncherEditing;
    }

    public void miuiLauncherEditingState(boolean value) {
        miuiLauncherEditing = value;
    }

    public long miuiOverviewDismissDeadlineState() {
        return miuiOverviewDismissPendingUntilUptime;
    }

    public void miuiOverviewDismissDeadlineState(long value) {
        miuiOverviewDismissPendingUntilUptime = value;
    }

    public synchronized void restoreMiuiOverviewDismissTimeoutAfterHotReload() {
        long deadline = miuiOverviewDismissPendingUntilUptime;
        if (deadline == 0L) {
            return;
        }
        long remaining = deadline - SystemClock.uptimeMillis();
        if (remaining <= 0L) {
            miuiOverviewDismissPendingUntilUptime = 0L;
            miuiOverviewVisible = true;
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> restoreMiuiOverviewAfterDismissTimeout(deadline), remaining);
    }

    protected synchronized void restoreMiuiOverviewAfterDismissTimeout(long pendingUntil) {
        if (miuiOverviewDismissPendingUntilUptime != pendingUntil) {
            return;
        }
        miuiOverviewDismissPendingUntilUptime = 0L;
        miuiOverviewVisible = true;
    }

    protected String systemUiInputArbiterStateAction() {
        return MODULE_SYSTEMUI_INPUT_ARBITER_STATE;
    }

    protected boolean isContextualSearchPreferenceEnabled() {
        try {
            SharedPreferences preferences = contextualSearchPreferences;
            if (preferences == null) {
                synchronized (this) {
                    preferences = contextualSearchPreferences;
                    if (preferences == null) {
                        preferences = getRemotePreferences(PredictiveBackPreferences.GROUP);
                        contextualSearchPreferences = preferences;
                    }
                }
            }
            contextualSearchPreferencesFailureLogged = false;
            return preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
        } catch (Throwable throwable) {
            if (!contextualSearchPreferencesFailureLogged) {
                contextualSearchPreferencesFailureLogged = true;
                moduleLog(Log.ERROR, TAG, "Contextual-search preference unavailable"
                        + ", policy=failClosed", throwable);
            }
            return false;
        }
    }

    protected boolean isContextualSearchLongPressEnabled() {
        if (!isContextualSearchPreferenceEnabled()) {
            contextualSearchServiceUnavailableLogged = false;
            return false;
        }
        if (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL) {
            return true;
        }
        try {
            boolean available = isBinderServiceAlive("contextual_search");
            if (available) {
                contextualSearchServiceUnavailableLogged = false;
                return true;
            }
            if (!contextualSearchServiceUnavailableLogged) {
                contextualSearchServiceUnavailableLogged = true;
                moduleLog(Log.WARN, TAG,
                        "Suppressed Android 17 native contextual search because the"
                                + " contextual_search service is not registered"
                                + ", policy=failClosed");
            }
            return false;
        } catch (Throwable throwable) {
            if (!contextualSearchServiceUnavailableLogged) {
                contextualSearchServiceUnavailableLogged = true;
                moduleLog(Log.ERROR, TAG,
                        "Failed to verify Android 17 contextual-search service"
                                + ", policy=failClosed", throwable);
            }
            return false;
        }
    }

    protected final synchronized void initializeModuleLoggingPreference() {
        try {
            SharedPreferences preferences = getRemotePreferences(
                    PredictiveBackPreferences.GROUP);
            SharedPreferences previous = moduleLoggingPreferences;
            if (previous != preferences) {
                if (previous != null) {
                    previous.unregisterOnSharedPreferenceChangeListener(
                            moduleLoggingPreferenceListener);
                }
                preferences.registerOnSharedPreferenceChangeListener(
                        moduleLoggingPreferenceListener);
                moduleLoggingPreferences = preferences;
            }
            moduleLoggingEnabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_MODULE_LOGGING,
                    PredictiveBackPreferences.DEFAULT_MODULE_LOGGING);
        } catch (Throwable ignored) {
            moduleLoggingEnabled = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING;
        }
    }

    protected final synchronized void releaseModuleLoggingPreference() {
        SharedPreferences current = moduleLoggingPreferences;
        moduleLoggingPreferences = null;
        if (current != null) {
            try {
                current.unregisterOnSharedPreferenceChangeListener(
                        moduleLoggingPreferenceListener);
            } catch (Throwable ignored) {
                // The process is already retiring this module generation.
            }
        }
    }

    protected final void moduleLog(int priority, String tag, String message) {
        if (priority < Log.WARN && !moduleLoggingEnabled) {
            return;
        }
        super.log(priority, tag, message);
    }

    protected final void moduleLog(int priority, String tag, String message,
                                   Throwable throwable) {
        if (priority < Log.WARN && !moduleLoggingEnabled) {
            return;
        }
        super.log(priority, tag, message, throwable);
    }

    protected abstract int readTransitionDebugId(Object infoOrExpose);

    protected abstract boolean isMiuiHomeLauncherOpenType(String typeName);

    protected abstract int resolveTaskInfoActivityType(Object taskInfo);

    protected abstract int resolveTaskInfoWindowingMode(Object taskInfo);

    protected abstract void publishSystemUiReturnHomeFinish(
            Object controller, long shellSessionId,
            Object finishCallback, String reason);

    /** Shared state, callback binders, and immutable return-home snapshots. */
    protected abstract class ReturnHomeStateController {
        protected final IBinder shellBackAnimation;
        protected final ClassLoader classLoader;
        protected final Handler handler = new Handler(Looper.getMainLooper());
        protected final IBinder.DeathRecipient shellDeathRecipient = () ->
                handler.post(this::dispatchShellBinderDeath);
        protected final PathInterpolator backGestureInterpolator =
                new PathInterpolator(0.1f, 0.1f, 0.0f, 1.0f);
        /**
         * Runtime flag snapshot.  Keep this non-final because the value comes from
         * hidden framework flags through reflection; some IDE inspections otherwise
         * incorrectly fold the false branch as a constant.
         */
        protected volatile boolean removeDepartTargetFromMotion;
        protected final ReturnHomeBackCallback backCallback = new ReturnHomeBackCallback();
        protected final ReturnHomeAnimationRunner animationRunner =
                new ReturnHomeAnimationRunner();
        protected final AtomicReference<ReturnHomeDirectCancelToken>
                pendingDirectCancel = new AtomicReference<>();
        protected final AtomicReference<ReturnHomeLauncherOpenBarrierToken>
                pendingLauncherOpenBarrier = new AtomicReference<>();
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
        protected Method nativeCoordinateTransformMethod;
        protected Object nativeCloseToDragType;
        protected Object nativeAppToAppType;
        ReturnHomeStateController(IBinder shellBackAnimation,
                                  ClassLoader classLoader, Context context) {
            this.shellBackAnimation = shellBackAnimation;
            this.classLoader = classLoader;
            this.context = context;
            this.removeDepartTargetFromMotion = readWindowFlag(
                    "removeDepartTargetFromMotion", classLoader, false);
        }

        protected abstract void dispatchShellBinderDeath();
        protected abstract void dispatchDeferredControllerFinish(String reason);
        protected abstract boolean attach();

        public abstract boolean blocksControllerReplacement();

        protected abstract void beginDeferredControllerReplacement(String reason);

        protected abstract void onShellBinderDied();

        public abstract String describeUnifiedOwner();

        protected abstract void detach(boolean clearShell, String reason);

        protected abstract void onBackStarted(BackMotionEvent event);

        protected abstract void onBackProgressed(BackMotionEvent event);

        protected abstract void onBackCancelled();

        protected abstract void onBackInvoked();

        protected abstract void onRemoteAnimationStart(int transit, Object[] apps, Object[] wallpapers,
                                    Object[] nonApps, IBinder finishedCallback);

        protected abstract void onRemoteAnimationCancelled();

        protected abstract boolean startPreview(ReturnHomeSession session, BackMotionEvent event,
                                       boolean terminalCallbackExpected);

        protected abstract void rejectUnavailableNativePreview(
                ReturnHomeSession session, boolean terminalCallbackExpected);

        protected abstract boolean resolvePreviewTarget(ReturnHomeSession session,
                                             BackMotionEvent event);

        protected abstract String describeSameSurface(SurfaceControl first,
                                           SurfaceControl second);

        protected abstract void clearPendingCallbackState();

        protected abstract void releaseBackMotionEventTarget(BackMotionEvent event);

        protected abstract void startPreviewProgressAnimator(
                ReturnHomeSession session, BackMotionEvent event,
                boolean terminalCallbackExpected);

        protected abstract void dispatchPreviewProgress(
                ReturnHomeSession session, BackMotionEvent event,
                boolean terminalCallbackExpected);

        protected abstract void updatePreviewFrame(ReturnHomeSession session,
                                        float smoothedProgress, float touchY,
                                        boolean terminalCallbackExpected);

        protected abstract void freezePreviewProgress(ReturnHomeSession session, String reason);

        protected abstract void animateCancel(ReturnHomeSession session, String reason);

        protected abstract void prepareNativePreviewBackdrop(ReturnHomeSession session);

        protected abstract void prepareNativePreviewShortcutLayer(ReturnHomeSession session);

        protected abstract void prepareNativePreviewWallpaper(ReturnHomeSession session);

        protected abstract void prepareNativePreviewBlur(ReturnHomeSession session);

        protected abstract void updateNativePreviewBlur(ReturnHomeSession session,
                                             float smoothedProgress);

        protected abstract void publishNativePreviewBlur(ReturnHomeSession session,
                                              int radius, float dimming,
                                              String reason);

        protected abstract void recoverNativePreviewBlurWriteFailure(
                ReturnHomeSession session, String reason, Throwable cause);

        protected abstract void transferNativePreviewBlur(ReturnHomeSession session,
                                               String reason);

        protected abstract void completeNativePreviewBlurHandoff(ReturnHomeSession session);

        protected abstract void restoreNativePreviewBlur(ReturnHomeSession session,
                                              String reason);

        protected abstract void restorePreviewBlurToHome(
                ReturnHomeSession session, Object blurElement,
                Object homeParams) throws Throwable ;

        protected abstract void clearNativePreviewBlurReferences(ReturnHomeSession session);

        protected abstract void prepareNativePreviewBackdropForCommit(
                ReturnHomeSession session);

        protected abstract void completeNativePreviewBackdropHandoff(
                ReturnHomeSession session);

        protected abstract void restoreNativePreviewBackdrop(ReturnHomeSession session,
                                                  String reason);

        protected abstract void restoreNativePreviewShortcutLayer(
                ReturnHomeSession session, String reason);

        protected abstract void recoverNativePreviewShortcutLayer(
                ReturnHomeSession session, String reason, Throwable cause);

        protected abstract void transferNativePreviewShortcutLayer(
                ReturnHomeSession session, String reason);

        protected abstract void clearNativePreviewShortcutReferences(
                ReturnHomeSession session);

        protected abstract void invokePreviewWallpaperSetTo(
                ReturnHomeSession session, Object params) throws Throwable ;

        protected abstract void onWallpaperCommand(Object element, Object params, boolean animated);

        protected abstract void restoreNativePreviewWallpaper(
                ReturnHomeSession session, String reason);

        protected abstract void recoverNativePreviewWallpaper(
                ReturnHomeSession session, String reason, Throwable cause);

        protected abstract void transferNativePreviewWallpaper(
                ReturnHomeSession session, String reason);

        protected abstract void clearNativePreviewWallpaperReferences(
                ReturnHomeSession session);

        protected abstract IBinder readBackdropWindowToken(Object workspace);

        protected abstract void markUnifiedCommitAnimToEntering(
                Object windowElement, Object params) throws Throwable ;

        protected abstract boolean onUnifiedCommitAnimToEntryFailed(
                Object windowElement, Object params,
                Throwable failure);

        protected abstract void verifyUnifiedStateManagerListenerGate(
                ReturnHomeSession session, boolean disabled,
                String reason) throws Throwable ;

        protected abstract boolean publishUnifiedNativeTerminalFailure(
                ReturnHomeSession session, Object animParams,
                Object ownerToken, long animToEpoch, boolean cancel,
                String reason, Throwable failure);

        protected abstract boolean publishUnifiedNativeTerminalFailure(
                ReturnHomeSession session, Object animParams,
                Object ownerToken, long animToEpoch, boolean cancel,
                boolean pendingCommitTermination,
                boolean pendingCommitStateCleared,
                String reason, Throwable failure);

        protected abstract void invalidatePendingUnifiedTerminalFailure(
                ReturnHomeSession session, String reason);

        protected abstract boolean isExactUnifiedNativeTerminalFailure(
                UnifiedNativeTerminalFailureSnapshot snapshot,
                Object currentElement, Object currentIdentity);

        protected abstract void handleUnifiedNativeTerminalFailure(
                UnifiedNativeTerminalFailureSnapshot snapshot);

        protected abstract void completeUnifiedNativeTerminalFailure(
                UnifiedNativeTerminalFailureSnapshot snapshot,
                String completionReason);

        protected abstract AtomicInteger unifiedConfigHookState(Object ownerToken);

        protected abstract Object resolveUnifiedAnimToConfigOwnerToken(Object params);

        protected abstract Object beginUnifiedNativeAnimToConfigHook(Object params);

        protected abstract void finishUnifiedNativeAnimToConfigHook(
                Object ownerToken, Object params,
                String reason, Throwable failure);

        protected abstract UnifiedNativePendingInterruptionSnapshot
                findUnifiedInterruptedAnimToConfig(Object params);

        protected abstract boolean hasExactUnifiedInterruptedOwnerTuple(
                UnifiedNativePendingInterruptionSnapshot snapshot);

        protected abstract void maybeFinishDeferredControllerAfterConfigAck(
                String reason);

        protected abstract void scheduleUnifiedInterruptedConfigOwnerDrain(
                UnifiedNativePendingInterruptionSnapshot snapshot,
                String reason);

        protected abstract boolean acknowledgeSkippedUnifiedInterruptedAnimToConfig(
                UnifiedNativePendingInterruptionSnapshot snapshot,
                String reason);

        protected abstract void acknowledgeAppliedUnifiedInterruptedAnimToConfig(
                ReturnHomeSession session, Object params,
                Object ownerToken, long animToEpoch, String reason);

        protected abstract void onUnifiedNativeAnimToConfigHookCompleted(
                Object implementor, Object params,
                String reason, Throwable failure);

        protected abstract boolean isUnifiedConfigImplementorElement(
                Object implementor, Object expectedWindowElement);

        protected abstract Object resolveUnifiedAnimToConfigLock(Object params);

        protected abstract boolean shouldSkipInterruptedUnifiedAnimToConfig(
                Object implementor, Object params);

        protected abstract void onUnifiedNativeAnimToConfigured(
                Object implementor, Object params);

        protected abstract boolean isExactUnifiedConfiguredAnimTo(
                ReturnHomeSession session,
                UnifiedNativeConfiguredAnimToSnapshot configured,
                Object windowElement, Object animationIdentity,
                String actualType);

        protected abstract boolean isExactUnconfiguredCancelledCommitFinish(
                ReturnHomeSession session, Object windowElement,
                Object animationIdentity, String actualType) throws Throwable ;

        protected abstract UnifiedNativeFinishDispatchToken beginUnifiedNativeFinishDispatch(
                Object windowElement);

        protected abstract void abortUnifiedNativeFinishDispatch(
                UnifiedNativeFinishDispatchToken token,
                String reason);

        protected abstract Boolean consumeUnifiedNativeFinishDispatch(
                Object windowElement);

        protected abstract void completeUnconfiguredCancelledCommitFinish(
                UnifiedNativeFinishDispatchToken token);

        protected abstract long beginUnifiedAnimToEpoch(
                ReturnHomeSession session, String reason);

        protected abstract void markUnifiedCommitAnimToReturned(
                Object windowElement, Object params);

        protected abstract void prepareUnifiedHandoffBeforeAnimTo(
                Object windowElement, Object params) throws Throwable ;

        protected abstract void armUnifiedLocalHandoffStatus(
                ReturnHomeSession session, String reason) throws Throwable ;

        protected abstract Object takeLocalHandoffStatus(Object implementor, Object params);

        protected abstract void discardLocalHandoffStatus(Object implementor, Object params,
                                       String reason);

        protected abstract MiuiHomeLocalHandoffToken matchLocalHandoffToken(
                Object implementor, Object params) throws Exception ;

        protected abstract void observeUnifiedCommitTransition(
                Object windowElement, Object params) throws Throwable ;

        protected abstract boolean invalidateUnifiedCommitTransition(
                Object windowElement, Object params, String reason);

        protected abstract boolean prepareElementTransitionContinuity(
                Object windowElement, Object params) throws Throwable ;

        protected abstract void hideElementBoundaryProviderFloatingIcon(
                Object windowElement, Object params) throws Throwable ;

        protected abstract void rearmElementLeashAfterNativeClear(Object helper)
                throws Throwable ;

        protected abstract boolean hasEligibleNativeGeometrySession();

        protected abstract void logNativeGeometryFailureOnce(
                ReturnHomeSession session, String stage,
                long frameTraceId, Throwable throwable);

        protected abstract SurfaceControl surfaceFromNativeTarget(Object target)
                throws Throwable ;

        protected abstract ReturnHomeNativeGeometrySnapshot prepareNativeGeometryBeforeAnimUpdate(
                Object implementor, Object currentRectObject,
                Object currentRadii, long frameTraceId);

        protected abstract ReturnHomeNativeGeometrySnapshot captureNativeGeometryFromSurfaceParams(
                long frameTraceId, Object surfaceParams);

        protected abstract void publishNativeGeometrySnapshot(
                ReturnHomeSession session,
                ReturnHomeNativeGeometrySnapshot snapshot);

        protected abstract Object resolveNativeGeometryFrameApplyLock(
                long frameTraceId, Object applier,
                ReturnHomeNativeGeometrySnapshot pendingSnapshot,
                Object surfaceParams);

        protected abstract ReturnHomeNativeGeometrySnapshot createNativeGeometrySnapshot(
                ReturnHomeSession session, Object animationIdentity,
                float[] matrixValues, Rect crop, float[] surfaceRadii,
                long frameTraceId, int sourceKind);

        protected abstract boolean nativeGeometryMatchesSurfaceParams(
                ReturnHomeNativeGeometrySnapshot snapshot, Object params)
                throws Throwable ;

        protected abstract float[] readSurfaceParamsCornerRadii(Object params)
                throws Throwable ;

        protected abstract float[] readNativeCornerRadii(Object radii)
                throws Throwable ;

        protected abstract void armElementAndClosingLeashStartGeometry(
                Object leashObject, Object change, Object transitionInfo,
                Object transactionObject);

        protected abstract Object resolveStartGeometryApplyLock(
                Object transaction, List<?> arguments);

        protected abstract void refreshStartGeometryAtApply(Object transaction);

        protected abstract void applyNativeSurfaceCornerRadii(
                SurfaceControl.Transaction transaction,
                SurfaceControl surface, float[] radii) throws Throwable ;

        protected abstract void finishStartGeometryApply(
                Object transaction, boolean applied);

        protected abstract void adoptElementTransitionIfStarted(
                Object windowElement, Object params) throws Throwable ;

        protected abstract boolean isExactUnifiedCommitTransition(
                ReturnHomeSession session,
                UnifiedNativeCommitTransitionToken transition,
                Object windowElement,
                int requiredPhase) throws Throwable ;

        protected abstract boolean hasCommittedUnifiedElementGeometry(
                ReturnHomeSession session,
                UnifiedNativeCommitTransitionToken transition,
                Object windowElement, Object animationIdentity);

        protected abstract void adoptUnifiedStandardCommitIfStarted(
                Object windowElement, Object params) throws Throwable ;

        protected abstract void acceptUnifiedStandardCommit(
                ReturnHomeSession session,
                UnifiedNativeStandardCommitToken token,
                UnifiedNativeRetargetInspection inspection);

        protected abstract boolean isExactUnifiedStandardCommitToken(
                ReturnHomeSession session,
                UnifiedNativeStandardCommitToken token,
                int requiredPhase);

        protected abstract boolean isExactUnifiedStandardCommitTokenAtAnimToBoundary(
                ReturnHomeSession session,
                UnifiedNativeStandardCommitToken token);

        protected abstract boolean adoptUnifiedStandardCommitToken(
                UnifiedNativeStandardCommitToken token);

        protected abstract boolean isUnifiedCommitTransitionAtAnimToBoundary(
                ReturnHomeSession session,
                UnifiedNativeCommitTransitionToken transition);

        protected abstract boolean adoptUnifiedCommitTransitionToken(
                UnifiedNativeCommitTransitionToken transition);

        protected abstract boolean adoptUnifiedTokenPhase(AtomicInteger tokenPhase);

        protected abstract boolean hasProvisionalUnifiedCommitBoundary(
                ReturnHomeSession session);

        protected abstract void adoptUnifiedNativeCommitIfStarted(
                Object windowElement, Object params) throws Throwable ;

        protected abstract UnifiedNativeRetargetInspection inspectUnifiedNativeRetarget(
                ReturnHomeSession session, long attempt,
                String requestedType, boolean cancel);

        protected abstract void publishUnifiedProvisionalCommit(
                ReturnHomeSession session,
                UnifiedNativeStandardCommitToken standardToken,
                UnifiedNativeCommitTransitionToken transitionToken,
                UnifiedNativeRetargetInspection inspection);

        protected abstract UnifiedNativePendingInterruptionSnapshot
                armUnifiedPendingCommitInterruption(
                ReturnHomeSession session, Object expectedWindowElement,
                String reason) throws Throwable;

        protected abstract boolean isExactUnifiedPendingInterruption(
                ReturnHomeSession session,
                UnifiedNativePendingInterruptionSnapshot snapshot,
                Object currentElement, Object currentIdentity,
                String currentType, boolean requireTokenBoundary);

        protected abstract void invalidateUnifiedPendingInterruption(
                ReturnHomeSession session, String reason);

        protected abstract boolean consumeUnifiedPendingInterruption(
                ReturnHomeSession session,
                UnifiedNativePendingInterruptionSnapshot snapshot,
                String reason);

        protected abstract boolean adoptConfiguredCommitForInterruption(
                ReturnHomeSession session, Object expectedWindowElement,
                String reason) throws Throwable ;

        protected abstract UnifiedNativeRetargetInspection inspectUnifiedNativeRetarget(
                ReturnHomeSession session, long attempt,
                String requestedType, boolean cancel,
                UnifiedNativeCommitTransitionToken commitTransition);

        protected abstract boolean rectsNear(RectF first, RectF second,
                                  float tolerance);

        protected abstract boolean isUnifiedNativeFullscreen(
                ReturnHomeSession session, Object rectObject)
                throws Exception ;

        protected abstract UnifiedNativeFinishSnapshot captureUnifiedNativeFinishSnapshot(
                ReturnHomeSession session, Object listener,
                Object animationIdentity);

        protected abstract boolean hasExactUnifiedNativeFinishIdentity(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot);

        protected abstract boolean isExactUnifiedNativeFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot);

        protected abstract boolean isExactAdoptedNativeCloseFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot);

        protected abstract boolean isConsumableUnifiedNativeFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot);

        protected abstract boolean acceptUnifiedNativeCommitFromFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot,
                String reason);

        protected abstract boolean acceptUnifiedStandardCommitFromFinishSnapshot(
                ReturnHomeSession session,
                UnifiedNativeFinishSnapshot snapshot,
                String reason);

        protected abstract void finishUnifiedSessionAfterNativeListener(
                ReturnHomeSession session, String reason);

        protected abstract boolean consumeUnifiedNativeFinishSnapshot(
                ReturnHomeSession session, String reason);

        protected abstract void acceptUnifiedNativeCommit(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection);

        protected abstract void markUnifiedElementLeashAdopted(
                ReturnHomeSession session, Object animationIdentity,
                String animationType);

        protected abstract void scheduleUnifiedNativeEndTimeout(
                ReturnHomeSession session);

        protected abstract void classifyUnifiedCommitTransitionTimeout(
                ReturnHomeSession session);

        protected abstract void completeUnifiedCommitTransitionTimeout(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection);

        protected abstract void completeUnifiedNativeCommitHandoff(
                ReturnHomeSession session, Object animationIdentity,
                String animationType);

        protected abstract void invalidateElementTransitionContinuity(
                ReturnHomeSession session, String reason,
                boolean clearHelper);

        protected abstract void ensureUnifiedNativePreviewReflection()
                throws Exception ;

        protected abstract void executeOnNativeGestureAnimationOwner(Runnable runnable)
                throws Exception ;

        protected abstract void setUnifiedNativePreviewSpringEndEnabled(
                ReturnHomeSession session, boolean enabled,
                String reason) throws Throwable ;

        protected abstract Object wrapNativeAnimationTargets(Object[] targets)
                throws Exception ;

        protected abstract Object resolveUnifiedNativeClosingTarget(
                ReturnHomeSession session, Object targetSet)
                throws Exception ;

        protected abstract Object createUnifiedNativeRectFParams(
                ReturnHomeSession session, Object animType,
                RectF targetRect, float endRadius, boolean needFinish,
                RectF explicitStartRect) throws Exception ;

        protected abstract RectF toUnifiedNativeHomeRect(
                int currentRotation, int homeRotation, RectF displayRect)
                throws Exception ;

        protected abstract boolean prepareUnifiedNativePreview(
                ReturnHomeSession session);

        protected abstract boolean driveUnifiedNativePreviewFrame(
                ReturnHomeSession session, boolean firstFrame);

        protected abstract boolean abandonReplacedUnifiedNativePreview(
                ReturnHomeSession session, String reason,
                Object currentElement, Object currentIdentity,
                boolean targetSetChanged);

        protected abstract boolean requestUnifiedPendingCommitTermination(
                ReturnHomeSession session, String reason);

        protected abstract void completeUnifiedPendingCommitTermination(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection,
                String reason);

        protected abstract boolean startUnifiedNativeCancel(
                ReturnHomeSession session, String reason);

        protected abstract boolean startUnifiedNativeCancel(
                ReturnHomeSession session, String reason,
                boolean externalPendingCommitTermination);

        protected abstract void scheduleUnifiedNativeCancelTimeout(
                ReturnHomeSession session, long animToEpoch,
                String reason);

        protected abstract void completeUnifiedNativeCancelTimeout(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection,
                long animToEpoch, String reason);

        protected abstract void acceptUnifiedNativeCancel(
                ReturnHomeSession session,
                UnifiedNativeRetargetInspection inspection,
                String reason);

        protected abstract void completeUnifiedNativeCancel(
                ReturnHomeSession session, String reason);

        protected abstract void finishUnifiedCancelForReusedOpen(
                Object stateManager, Object windowElement,
                Object animationIdentity);

        protected abstract boolean isStandardSingleTaskReturnHome(ReturnHomeSession session);

        protected abstract boolean standardSignalCanBindSession(
                StandardReturnHomeCommitSignal signal,
                ReturnHomeSession session);

        protected abstract boolean bindStandardSignalToSession(
                StandardReturnHomeCommitSignal signal,
                ReturnHomeSession session);

        protected abstract boolean standardSignalMatchesSession(
                StandardReturnHomeCommitSignal signal,
                ReturnHomeSession session);

        protected abstract void discardPendingStandardCommitForRunner(
                IBinder runnerSession, String reason);

        protected abstract void bindPendingStandardCommitToSession(
                ReturnHomeSession session);

        protected abstract void onStandardShellReturnHomeCommit(
                StandardReturnHomeCommitSignal signal);

        protected abstract void continueUnifiedStandardCommit(
                ReturnHomeSession session);

        protected abstract boolean startUnifiedNativeProviderCommit(
                ReturnHomeSession session);

        protected abstract void startNativeClose(ReturnHomeSession session);

        protected abstract boolean onNativeAnimationStart(
                Object listener, Object animationIdentity);

        protected abstract ReturnHomeLauncherOpenBarrierToken prepareLauncherOpenBarrier(
                Object stateManager, Object[] args) throws Throwable ;

        protected abstract ReturnHomeLauncherOpenBarrierToken prepareLauncherOpenBarrier(
                Object stateManager, Object[] args,
                boolean configLocked) throws Throwable ;

        protected abstract boolean isExactElementBoundarySignal(
                ReturnHomeSession session,
                StandardReturnHomeCommitSignal signal,
                ReturnHomeElementLeashReuseToken token,
                Object currentElement, Object currentIdentity,
                String currentType);

        protected abstract boolean armLauncherOpenParallelRoute(
                ReturnHomeLauncherOpenBarrierToken token) throws Throwable ;

        protected abstract Object invokeLauncherOpenBarrierCallback(
                ReturnHomeLauncherOpenBarrierToken token, Object proxy,
                Method method, Object[] invocationArgs) throws Throwable ;

        protected abstract Object invokeLauncherOpenCallback(
                Object callback, Method method,
                Object[] invocationArgs) throws Throwable ;

        protected abstract boolean acceptNativeCloseToOpenBoundary(
                ReturnHomeLauncherOpenBarrierToken token) throws Throwable ;

        protected abstract boolean armLauncherOpenBarrier(
                ReturnHomeSession session, Object stateManager,
                Object windowElement, Object animationIdentity,
                Object clickedView, String reason);

        protected abstract void onStandardShellReturnHomeFinished(
                StandardReturnHomeCommitSignal signal);

        protected abstract boolean matchesReturnHomeSignal(
                StandardReturnHomeCommitSignal expected,
                StandardReturnHomeCommitSignal actual);

        protected abstract void completeLauncherOpenBarrier(
                ReturnHomeLauncherOpenBarrierToken token);

        protected abstract void invalidateLauncherOpenBarrier(
                ReturnHomeLauncherOpenBarrierToken token, String reason);

        protected abstract void invalidateLauncherOpenBarrier(
                ReturnHomeLauncherOpenBarrierToken token, String reason,
                boolean releaseCallback);

        protected abstract void releaseInvalidatedLauncherOpenBarrierCallback(
                ReturnHomeLauncherOpenBarrierToken token);

        protected abstract void invalidatePendingLauncherOpenBarrier(String reason);

        protected abstract void invalidatePendingLauncherOpenBarrier(
                String reason, boolean releaseCallback);

        protected abstract boolean shouldRouteSameIconThroughNativeParallel(
                Object stateManager, Object[] args) throws Throwable ;

        protected abstract boolean shouldRouteSameIconThroughNativeParallel(
                Object stateManager, Object[] args,
                boolean configLocked) throws Throwable ;

        protected abstract boolean shouldForceFreshOpenAfterSameIconClose(
                Object stateManager, Object oldWindowElement,
                Object clickedView) throws Throwable ;

        protected abstract ReturnHomeDirectCancelToken prepareNativeDirectCancel(
                Object windowElement, Object[] args) throws Throwable ;

        protected abstract ReturnHomeDirectCancelToken prepareNativeDirectCancel(
                Object windowElement, Object[] args,
                boolean configLocked) throws Throwable ;

        protected abstract Object invokeNativeDirectCancelCallback(
                ReturnHomeDirectCancelToken token, Object proxy,
                Method method, Object[] invocationArgs) throws Throwable ;

        protected abstract void acceptNativeDirectCancelCallback(
                ReturnHomeDirectCancelToken token) throws Throwable ;

        protected abstract void invalidateNativeDirectCancel(ReturnHomeDirectCancelToken token,
                                          String reason,
                                          boolean cleanupAccepted);

        protected abstract void invalidatePendingDirectCancel(
                ReturnHomeSession session, String reason,
                boolean cleanupAccepted);

        protected abstract void cleanupNativeDirectCancel(
                ReturnHomeDirectCancelToken token, String reason);

        protected abstract void finishNativeDirectCancelOnAnimationEnd(
                Object listener, Object animationIdentity);

        protected abstract boolean isReturnHomeNativeCloseType(String typeName);

        protected abstract void captureNativeAnimationEndBeforeListener(
                Object listener, Object animationIdentity);

        protected abstract boolean onNativeAnimationEnd(
                Object listener, Object animationIdentity);

        protected abstract void finishSession(ReturnHomeSession session, String reason);

        protected abstract void cleanupFinishedSession(ReturnHomeSession session, String reason);

        protected void notifyRemoteAnimationFinished(IBinder callback, String reason) {
            if (callback == null) {
                return;
            }
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(REMOTE_ANIMATION_FINISHED_DESCRIPTOR);
                callback.transact(1, data, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to finish Shell remote animation"
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
                Class<?> radiusClass = findClass(
                        MIUI_HOME_WINDOW_CORNER_RADIUS_UTIL, classLoader);
                Object value = invokeStaticMethod(radiusClass, "getCornerRadius",
                        new Class<?>[0], new Object[0]);
                if (value instanceof Number) {
                    return Math.max(0.0f, ((Number) value).floatValue());
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to resolve Xiaomi window corner radius",
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
            Parcelable.Creator creator = (Parcelable.Creator)
                    readParcelableCreator(BackMotionEvent.class);
            return (BackMotionEvent) parcel.readTypedObject(creator);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        protected Object[] readRemoteAnimationTargets(Parcel parcel) throws Exception {
            Parcelable.Creator creator = (Parcelable.Creator) readParcelableCreator(
                    android.view.RemoteAnimationTarget.class);
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
                            handler.post(ReturnHomeStateController.this::onBackCancelled);
                            return true;
                        case 4:
                            data.enforceNoDataAvail();
                            handler.post(ReturnHomeStateController.this::onBackInvoked);
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
                    moduleLog(Log.ERROR, TAG, "MiuiHome back callback transact failed"
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
                                ReturnHomeStateController.this::onRemoteAnimationCancelled);
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG, "MiuiHome remote runner transact failed"
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

        protected final class ReturnHomeLauncherOpenBarrierToken {
            final long generation;
            final ReturnHomeSession session;
            final Object stateManager;
            final Object windowElement;
            final Object animationIdentity;
            final View clickedView;
            final Object originalCallback;
            final StandardReturnHomeCommitSignal expectedSignal;
            final ReturnHomeElementLeashReuseToken elementBoundary;
            final UnifiedNativePendingInterruptionSnapshot
                    pendingCommitInterruption;
            final boolean nativeParallelRoute;
            final AtomicBoolean armed = new AtomicBoolean();
            final AtomicBoolean callbackReceived = new AtomicBoolean();
            final AtomicBoolean finishReceived = new AtomicBoolean();
            final AtomicBoolean completed = new AtomicBoolean();
            final AtomicBoolean invalidated = new AtomicBoolean();
            final AtomicBoolean freshOpenConsumed = new AtomicBoolean();
            volatile Object wrappedCallback;
            volatile Method callbackMethod;
            volatile StandardReturnHomeCommitSignal finishSignal;
            volatile boolean releaseOnInvalidation;
            volatile boolean parallelRoute;
            volatile boolean freshOpenReady;

            ReturnHomeLauncherOpenBarrierToken(
                    ReturnHomeSession session, Object stateManager,
                    Object windowElement, Object animationIdentity,
                    View clickedView, Object originalCallback,
                    StandardReturnHomeCommitSignal expectedSignal,
                    ReturnHomeElementLeashReuseToken elementBoundary,
                    UnifiedNativePendingInterruptionSnapshot
                            pendingCommitInterruption,
                    boolean nativeParallelRoute) {
                this.generation = session.generation;
                this.session = session;
                this.stateManager = stateManager;
                this.windowElement = windowElement;
                this.animationIdentity = animationIdentity;
                this.clickedView = clickedView;
                this.originalCallback = originalCallback;
                this.expectedSignal = expectedSignal;
                this.elementBoundary = elementBoundary;
                this.pendingCommitInterruption =
                        pendingCommitInterruption;
                this.nativeParallelRoute = nativeParallelRoute;
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

        protected final class UnifiedNativeAdoptedStandardCommitIdentity {
            final long generation;
            final ReturnHomeSession session;
            final Object windowElement;
            final Object animationIdentity;
            final StandardReturnHomeCommitSignal signal;

            UnifiedNativeAdoptedStandardCommitIdentity(
                    ReturnHomeSession session,
                    UnifiedNativeStandardCommitToken token) {
                this.generation = session.generation;
                this.session = session;
                this.windowElement = token.windowElement;
                this.animationIdentity = token.animationIdentity;
                this.signal = token.signal;
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
            volatile boolean applyAccepted;

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
            volatile MiuiHomeAcceptedInputToken acceptedInputIdentity;
            final AtomicInteger finished = new AtomicInteger();
            final AtomicInteger cleaned = new AtomicInteger();
            final Rect startRect = new Rect();
            final RectF currentRect = new RectF();
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
            boolean nativeHandoffStarted;
            boolean nativeStatusPublished;
            boolean nativeAnimationStarted;
            boolean nativeContinuationVerified;
            boolean nativeGeometryFailureLogged;
            boolean unifiedNativePreviewOwned;
            boolean unifiedNativeOwnerAbandoned;
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
            UnifiedNativeAdoptedStandardCommitIdentity
                    unifiedNativeAdoptedStandardCommit;
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
                              IBinder finishedCallback) {
                this.generation = generation;
                this.apps = apps;
                this.wallpapers = wallpapers;
                this.nonApps = nonApps;
                this.finishedCallback = finishedCallback;
                BackProgressAnimator animator = null;
                try {
                    if (Looper.myLooper() != Looper.getMainLooper()) {
                        throw new IllegalStateException(
                                "BackProgressAnimator constructed outside main Looper");
                    }
                    animator = new BackProgressAnimator();
                } catch (Throwable throwable) {
                    progressAnimatorFailed = true;
                    moduleLog(Log.WARN, TAG,
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
