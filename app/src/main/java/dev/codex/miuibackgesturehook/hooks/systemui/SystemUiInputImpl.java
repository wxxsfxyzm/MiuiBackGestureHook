package dev.codex.miuibackgesturehook.hooks.systemui;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;
import static dev.codex.miuibackgesturehook.data.ReturnHomeData.*;
import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import android.annotation.SuppressLint;
import android.animation.Animator;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.media.AudioAttributes;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackTouchTracker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import android.animation.AnimatorListenerAdapter;
import android.content.BroadcastReceiver;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.SurfaceControl;
import android.window.IOnBackInvokedCallback;
import android.window.TransitionInfo;
import android.window.WindowOnBackInvokedDispatcher;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import dev.codex.miuibackgesturehook.BuildConfig;
import dev.codex.miuibackgesturehook.NativeHookStatusProtocol;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

public abstract class SystemUiInputImpl extends HookerBridge {
    private final AtomicBoolean runtimeStarted = new AtomicBoolean();
    private final AtomicBoolean runtimeReleased = new AtomicBoolean();

    /** Shared lifecycle entry point used by both process-specific hook runtimes. */
    public void start() {
        if (runtimeStarted.compareAndSet(false, true)) {
            initializeModuleLoggingPreference();
            moduleLog(Log.INFO, TAG, "Starting hook state, build="
                    + BUILD_MARK + ", process=" + packageName);
        }
    }

    /** MiuiHome has no SystemUI-owned resources to inspect at this layer. */
    public boolean isHotReloadSafe() {
        return true;
    }

    /** Release only state owned by the shared input/communication layer. */
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

    /**
     * A callback-only launcher probe is authoritative only while Shell still returns the
     * launcher callback.  A different back type proves that the mirrored Overview state is
     * stale for this generation; let the SystemUI implementation consume that evidence through
     * its normal state reducer instead of probing the same stale target on every DOWN.
     */
    protected synchronized void clearMiuiOverviewAfterRejectedShellTarget(String reason) {
        miuiOverviewVisible = false;
        miuiOverviewDismissPendingUntilUptime = 0L;
    }

    protected String systemUiInputArbiterStateAction() {
        return MODULE_SYSTEMUI_INPUT_ARBITER_STATE;
    }

    /** Open-transition snapshots are captured only by the SystemUI implementation. */
    protected void invalidateOpenTransitionSnapshot(
            OpenTransitionSnapshot snapshot, String reason) {
    }

    protected void onOpenTransitionAnimatorEnded(
            OpenTransitionSnapshot snapshot, boolean isReverse) {
        invalidateOpenTransitionSnapshot(snapshot,
                isReverse ? "reverseEnd" : "end");
    }

    protected abstract int readTransitionDebugId(Object infoOrExpose);

    protected abstract boolean isMiuiHomeLauncherOpenType(String typeName);

    protected abstract int resolveTaskInfoActivityType(Object taskInfo);

    protected abstract int resolveTaskInfoWindowingMode(Object taskInfo);

    protected abstract void publishSystemUiInputArbiterState(
            Context context, boolean ready, String reason);

    protected abstract void publishStandardReturnHomeCommit(
            int taskId, int transitionDebugId, Object compositionController,
            Object exactFinishCallback, boolean elementBoundaryOnly);

    protected void clearLegacyBackGuard(String reason) {
    }

    protected abstract boolean clearSystemUiReturnHomeCommitIdentity(
            Object controller, long attemptId, String reason);

    protected LegacyBackAttempt armLegacyBackGuard(
            Object controller, Object runningInfo) {
        return null;
    }

    /** AOSP back animation registry setup is a SystemUI-only responsibility. */
    protected void ensureAospBackAnimations(Object controller, String source) {
    }

    protected abstract void onSystemUiInputMonitorAttached(Context context);

    protected abstract void onSystemUiInputMonitorDetached(Context context);

    /** Headless NavigationBar lifecycle is owned only by the SystemUI runtime. */
    protected boolean isCurrentHeadlessNavBarLifecycle(
            Object edgeBackGestureHandler) {
        return false;
    }


    protected static final String TAG = "MiuiBackGestureHook";
    protected static final String BUILD_MARK =
            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    protected static final String SYSTEM_UI = "com.android.systemui";
    protected static final String MIUI_HOME = "com.miui.home";
    protected static final String MODULE_PACKAGE = NativeHookStatusProtocol.PACKAGE_NAME;
    protected static final int ANDROID_17_API_LEVEL = 37;
    protected static final String WINDOW_ON_BACK_INVOKED_DISPATCHER =
            WindowOnBackInvokedDispatcher.class.getName();
    protected static final int APPLICATION_PREDICTIVE_BACK_ENABLE_FLAG = 0x8;
    protected static final int ACTIVITY_PREDICTIVE_BACK_ENABLE_FLAG = 0x4;
    protected static final int ACTIVITY_PREDICTIVE_BACK_DISABLE_FLAG = 0x8;
    protected static final int UNIFIED_CONFIG_HOOK_PENDING = 0;
    protected static final int UNIFIED_CONFIG_HOOK_RUNNING = 1;
    protected static final int UNIFIED_CONFIG_HOOK_COMPLETED = 2;

    protected static final int TRANSACTION_MIUI_ON_GESTURE_LINE_PROGRESS = 4;
    protected static final int TRANSIT_OPEN = 1;
    protected static final int TRANSIT_CLOSE = 2;
    protected static final int TRANSIT_PREDICTIVE_BACK = 13;
    protected static final int TRANSIT_TO_FRONT = 3;
    protected static final int TRANSIT_TO_BACK = 4;
    protected static final int TRANSIT_CHANGE = 6;
    protected static final int FLAG_IS_WALLPAPER = 1 << 1;
    protected static final int FLAG_TRANSLUCENT = 1 << 2;
    protected static final int FLAG_FILLS_TASK = 1 << 10;
    protected static final int FLAG_IS_OCCLUDED = 1 << 15;
    protected static final int FLAG_BACK_GESTURE_ANIMATED = 1 << 17;
    protected static final int FLAG_DISPLAY_CHANGE = 1 << 27;
    protected static final int FLAG_IS_ELEMENT = Integer.MIN_VALUE;
    protected static final int XIAOMI_PREPARED_HOME_CHANGE_FLAGS = 0x00028001;
    protected static final int XIAOMI_PREPARED_HOME_NO_WALLPAPER_CHANGE_FLAGS =
            0x00028000;
    protected static final int XIAOMI_ELEMENT_HOME_CHANGE_FLAGS = 0x00120001;
    protected static final int FLAG_ONLY_ACTIVITY_RECORD = 1 << 26;
    protected static final int EDGE_LEFT = 0;
    protected static final int EDGE_RIGHT = 1;
    protected static final String MODULE_MIUI_OVERVIEW_STATE_CHANGE =
            "dev.codex.miuibackgesturehook.action.MIUI_OVERVIEW_STATE_CHANGE";
    protected static final String MODULE_MIUI_HOME_OPEN_BREAK_COMMAND =
            "dev.codex.miuibackgesturehook.action.MIUI_HOME_OPEN_BREAK";
    protected static final String MODULE_SYSTEMUI_INPUT_ARBITER_STATE =
            NativeHookStatusProtocol.ACTION_SYSTEMUI_STATE;
    protected static final String MODULE_MIUI_HOME_INPUT_ARBITER_QUERY =
            "dev.codex.miuibackgesturehook.action.MIUI_HOME_INPUT_ARBITER_QUERY";
    protected static final String MODULE_CONTEXTUAL_SEARCH_TRIGGERED =
            "dev.codex.miuibackgesturehook.action.CONTEXTUAL_SEARCH_TRIGGERED";
    protected static final String MODULE_RUNTIME_STATUS_QUERY =
            NativeHookStatusProtocol.ACTION_QUERY;
    protected static final String MODULE_RUNTIME_STATUS_REPLY =
            NativeHookStatusProtocol.ACTION_REPLY;
    protected static final String EXTRA_STATUS_NONCE = NativeHookStatusProtocol.EXTRA_NONCE;
    protected static final String EXTRA_STATUS_QUERY = NativeHookStatusProtocol.EXTRA_QUERY;
    protected static final String EXTRA_STATUS_NATIVE_RESPONSE =
            NativeHookStatusProtocol.EXTRA_NATIVE_RESPONSE;
    protected static final String EXTRA_STATUS_LEGACY_MODE =
            NativeHookStatusProtocol.EXTRA_LEGACY_MODE;
    protected static final String EXTRA_STATUS_LEGACY_READY =
            NativeHookStatusProtocol.EXTRA_LEGACY_READY;
    protected static final String EXTRA_STATUS_NATIVE_READY =
            NativeHookStatusProtocol.EXTRA_NATIVE_READY;
    protected static final String EXTRA_STATUS_NATIVE_PROFILE_RESOLVED =
            NativeHookStatusProtocol.EXTRA_NATIVE_PROFILE_RESOLVED;
    protected static final String EXTRA_STATUS_NATIVE_PROFILE_DYNAMIC =
            NativeHookStatusProtocol.EXTRA_NATIVE_PROFILE_DYNAMIC;
    protected static final String EXTRA_STATUS_NATIVE_PROFILE_ENTRY_OFFSET =
            NativeHookStatusProtocol.EXTRA_NATIVE_PROFILE_ENTRY_OFFSET;
    protected static final String EXTRA_STATUS_NATIVE_SIDE_OFFSET =
            NativeHookStatusProtocol.EXTRA_NATIVE_SIDE_OFFSET;
    protected static final String EXTRA_STATUS_NATIVE_RUNTIME_PROFILE_STAGE =
            NativeHookStatusProtocol.EXTRA_NATIVE_RUNTIME_PROFILE_STAGE;
    protected static final String EXTRA_STATUS_NATIVE_DART_RESOLVER_STAGE =
            NativeHookStatusProtocol.EXTRA_NATIVE_DART_RESOLVER_STAGE;
    protected static final String EXTRA_STATUS_NATIVE_DART_DRAWER_CANDIDATES =
            NativeHookStatusProtocol.EXTRA_NATIVE_DART_DRAWER_CANDIDATES;
    protected static final String EXTRA_STATUS_NATIVE_DART_TRANSITION_CANDIDATES =
            NativeHookStatusProtocol.EXTRA_NATIVE_DART_TRANSITION_CANDIDATES;
    protected static final String EXTRA_STATUS_NATIVE_DART_OVERVIEW_ENTER_CANDIDATES =
            NativeHookStatusProtocol.EXTRA_NATIVE_DART_OVERVIEW_ENTER_CANDIDATES;
    protected static final String EXTRA_STATUS_NATIVE_DART_OVERVIEW_EXIT_CANDIDATES =
            NativeHookStatusProtocol.EXTRA_NATIVE_DART_OVERVIEW_EXIT_CANDIDATES;
    protected static final String EXTRA_STATUS_NATIVE_DART_EDITING_CANDIDATES =
            NativeHookStatusProtocol.EXTRA_NATIVE_DART_EDITING_CANDIDATES;
    protected static final String EXTRA_STATUS_NATIVE_DRAWER_STATE_READY =
            NativeHookStatusProtocol.EXTRA_NATIVE_DRAWER_STATE_READY;
    protected static final String EXTRA_STATUS_NATIVE_OVERVIEW_STATE_READY =
            NativeHookStatusProtocol.EXTRA_NATIVE_OVERVIEW_STATE_READY;
    protected static final String EXTRA_STATUS_NATIVE_EDITING_STATE_READY =
            NativeHookStatusProtocol.EXTRA_NATIVE_EDITING_STATE_READY;
    protected static final String EXTRA_STATUS_NATIVE_BUSINESS_STATE =
            NativeHookStatusProtocol.EXTRA_NATIVE_BUSINESS_STATE;
    protected static final String EXTRA_STATUS_NATIVE_BRIDGE_STATE =
            NativeHookStatusProtocol.EXTRA_NATIVE_BRIDGE_STATE;
    protected static final String EXTRA_STATUS_NATIVE_RECEIVER_STATE =
            NativeHookStatusProtocol.EXTRA_NATIVE_RECEIVER_STATE;
    protected static final String EXTRA_STATUS_SYSTEMUI_READY =
            NativeHookStatusProtocol.EXTRA_SYSTEMUI_READY;
    protected static final String EXTRA_STATUS_SYSTEMUI_GENERATION =
            NativeHookStatusProtocol.EXTRA_SYSTEMUI_GENERATION;
    protected static final String EXTRA_STATUS_SYSTEMUI_MONITORS =
            NativeHookStatusProtocol.EXTRA_SYSTEMUI_MONITORS;
    protected static final String EXTRA_STATUS_REASON = NativeHookStatusProtocol.EXTRA_REASON;
    protected static final String EXTRA_INPUT_ARBITER_READY = "input_arbiter_ready";
    protected static final String EXTRA_INPUT_ARBITER_GENERATION =
            "input_arbiter_generation";
    protected static final String EXTRA_LAUNCHER_STATE_OWNER_EPOCH =
            "launcher_state_owner_epoch";
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
    protected static final String EXTRA_LAUNCHER_OPEN_ACTIVE =
            "launcher_open_active";
    protected static final String EXTRA_LAUNCHER_OPEN_BREAK_GENERATION =
            "launcher_open_break_generation";
    protected static final String EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT =
            "launcher_open_break_attempt";
    protected static final String EXTRA_LAUNCHER_EDITING = "launcher_editing";
    protected static final String EXTRA_LAUNCHER_FOLDER_VISIBLE =
            "launcher_folder_visible";
    protected static final String EXTRA_LAUNCHER_XIAOAI_VISIBLE =
            "xiaoai_visible";
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
    protected static final int LAUNCHER_OPEN_BREAK_RESULT_NO_RECEIVER = 0;
    protected static final int LAUNCHER_OPEN_BREAK_RESULT_REJECTED = 1;
    protected static final int LAUNCHER_OPEN_BREAK_RESULT_ACCEPTED = 2;
    protected static final int KEY_ACTION_UP = 1;
    protected static final int KEY_ACTION_DOWN = 0;
    protected static final int TYPE_RETURN_TO_HOME = 1;
    protected static final int TYPE_CROSS_ACTIVITY = 2;
    protected static final int TYPE_CROSS_TASK = 3;
    protected static final int TYPE_CALLBACK = 4;
    protected static final long SYSUI_STATE_NAV_BAR_HIDDEN = 1L << 1;
    protected static final long SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY =
            1L << 17;
    protected static final long SYSUI_STATE_MIUI_QUICK_SETTINGS_EXPANDED = 1L << 60;
    protected static final long SYSUI_STATE_MIUI_NOTIFICATION_EXPANDED = 1L << 61;
    protected static final long SYSUI_STATE_MIUI_SHADE_EXPANDED_MASK =
            SYSUI_STATE_MIUI_QUICK_SETTINGS_EXPANDED
                    | SYSUI_STATE_MIUI_NOTIFICATION_EXPANDED;
    protected static final long SYSUI_STATE_LOCKED_OR_PINNED_MASK =
            1L | (1L << 3) | (1L << 6) | (1L << 9);
    protected static final int ACTIVITY_TYPE_STANDARD = 1;
    protected static final int ACTIVITY_TYPE_HOME = 2;
    protected static final int TOUCH_OCCLUSION_MODE_USE_OPACITY = 1;
    protected static final int TOUCH_OCCLUSION_MODE_ALLOW = 2;
    protected static final int WINDOWING_MODE_FULLSCREEN = 1;
    protected static final int WINDOWING_MODE_FREEFORM = 5;
    protected static final float EDGE_TOUCH_WIDTH_DP = 24.0f;
    protected static final float PILFER_THRESHOLD_DP = 8.0f;
    protected static final float RETURN_HOME_PREVIEW_BLUR_DISTANCE_DP = 48.0f;
    protected static final float MIUI_STYLE_SWIPE_START_PX = 20.0f;
    protected static final float MIUI_STYLE_ARROW_SHOW_PX = 180.0f;
    protected static final float AOSP_PROGRESS_THRESHOLD_DP = 412.0f;
    protected static final float RETURN_HOME_MIN_WINDOW_SCALE = 0.85f;
    protected static final float RETURN_HOME_WINDOW_MARGIN_DP = 8.0f;
    protected static final float RETURN_HOME_END_CORNER_RADIUS_DP = 28.0f;
    protected static final String MIUI_SIDEBAR_BOUNDS = "sidebar_bounds";
    protected static final float MIUI_SIDEBAR_EXCLUSION_PADDING_DP = 8.0f;
    protected static final long MIUI_OVERVIEW_DISMISS_TIMEOUT_MS = 2500L;
    protected static final long MIUI_OVERVIEW_EXIT_GUARD_MS = 400L;
    protected static final long LEGACY_BACK_MERGE_TIMEOUT_MS = 2000L;
    protected static final long DUPLICATE_BACK_PAIR_TIMEOUT_MS = 700L;
    protected static final long DUPLICATE_BACK_UP_INTERVAL_MS = 200L;
    protected static final long INPUT_ACCEPTED_TOKEN_TIMEOUT_MS = 750L;
    protected static final long RETURN_HOME_DIRECT_CANCEL_CLEANUP_GUARD_MS = 500L;
    protected static final long RETURN_HOME_NATIVE_TIMEOUT_MS = 1800L;
    protected static final int RETURN_HOME_GEOMETRY_SOURCE_ANIM_UPDATE = 1;
    protected static final int RETURN_HOME_GEOMETRY_SOURCE_SURFACE_PARAMS = 2;
    protected static final int MIUI_SURFACE_PARAM_FLAG_MATRIX = 2;
    protected static final int MIUI_SURFACE_PARAM_FLAG_WINDOW_CROP = 4;
    protected static final int MIUI_SURFACE_PARAM_FLAG_CORNER_RADIUS = 16;
    protected static final int MIUI_SURFACE_PARAM_FLAG_SHOW = 512;
    protected static final String MIUI_HOME_ICON_CLICK_WITHOUT_RECENT_REASON =
            "Icon click without recent.";
    protected static final ComponentName PERMISSION_GRANT_ACTIVITY = new ComponentName(
            "com.android.permissioncontroller",
            "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity");
    protected static final String SHELL_BACK_ANIMATION_DESCRIPTOR =
            "com.android.wm.shell.back.IBackAnimation";
    protected static final String ON_BACK_INVOKED_CALLBACK_DESCRIPTOR =
            IOnBackInvokedCallback.class.getName();
    protected static final String REMOTE_ANIMATION_RUNNER_DESCRIPTOR =
            IRemoteAnimationRunner.class.getName();
    protected static final String REMOTE_ANIMATION_FINISHED_DESCRIPTOR =
            IRemoteAnimationFinishedCallback.class.getName();
    protected static final int SHELL_BACK_SET_LAUNCHER_CALLBACK_TRANSACTION = 1;
    protected static final int SHELL_BACK_CLEAR_LAUNCHER_CALLBACK_TRANSACTION = 2;
    protected static final int RETURN_HOME_TERMINAL_NONE = 0;
    protected static final int RETURN_HOME_TERMINAL_CANCEL = 1;
    protected static final int RETURN_HOME_TERMINAL_INVOKE = 2;
    protected static final int REMOTE_RUNNER_MISSING = 0;
    protected static final int REMOTE_RUNNER_CANCELLED = 1;
    protected static final int REMOTE_RUNNER_WAITING = 2;
    protected static final int REMOTE_RUNNER_READY = 3;
    protected static final int REMOTE_RUNNER_UNKNOWN = 4;
    protected static final int BACK_GUARD_IDLE = 0;
    protected static final int BACK_GUARD_WAIT_MERGE = 1;
    protected static final int BACK_GUARD_EXPECT_DOWN = 2;
    protected static final int BACK_GUARD_EXPECT_UP = 3;
    protected static final int OPEN_SNAPSHOT_PENDING = 0;
    protected static final int OPEN_SNAPSHOT_ACTIVE = 1;
    protected static final int OPEN_SNAPSHOT_INVALID = 2;

    protected final ConcurrentHashMap<Object, OpenTransitionSnapshot> runningOpenTransitions =
            new ConcurrentHashMap<>();
    protected final Object legacyBackGuardLock = new Object();
    protected final AtomicLong legacyBackAttemptIds = new AtomicLong();
    protected final AtomicLong openSnapshotGeneration = new AtomicLong();
    protected final AtomicLong openSnapshotLifecycleEpoch = new AtomicLong();
    protected final AtomicLong headlessNavBarLifecycleGeneration = new AtomicLong();
    protected final AtomicLong launcherOpenBreakAttemptIds = new AtomicLong();
    protected final AtomicInteger launcherOpenBreakCommandsInFlight = new AtomicInteger();
    protected final AtomicLong miuiHomeOpenBreakGenerationIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong miuiHomeOpenBreakCallbackEpoch = new AtomicLong();
    protected final AtomicInteger systemUiInputArbiterMonitorCount = new AtomicInteger();
    protected final AtomicLong pendingModuleStatusNonce = new AtomicLong();
    protected final AtomicLong miuiHomeReturnHomeGenerationIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong miuiHomeLauncherOpenSnapshotIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong miuiHomeNativeGeometryFrameIds = new AtomicLong();
    protected final AtomicLong systemUiReturnHomeCommitAttemptIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong systemUiShellGestureSessionIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final ThreadLocal<ReturnHomeNativeGeometrySnapshot>
            miuiHomePendingNativeGeometry = new ThreadLocal<>();
    protected final ThreadLocal<ReturnHomeFinishTransferCandidate>
            returnHomeFinishTransferCandidate = new ThreadLocal<>();
    protected volatile boolean backCommitCompositionHookReady;
    protected volatile boolean backFinishOpenAtomicHookReady;
    protected volatile boolean backFinishOpenCallerDeoptimized;
    protected final AtomicReference<MiuiHomeAcceptedInputToken> acceptedInputToken =
            new AtomicReference<>();
    protected final AtomicReference<MiuiHomeAcceptedInputToken>
            miuiHomeAcceptedInputIdentity = new AtomicReference<>();
    protected final AtomicReference<SystemUiReturnHomeCommitIdentity>
            systemUiReturnHomeCommitIdentity = new AtomicReference<>();
    protected final AtomicReference<MiuiHomeLocalHandoffToken> miuiHomeLocalHandoffToken =
            new AtomicReference<>();
    protected final AtomicReference<MiuiHomeLauncherOpenSnapshot>
            miuiHomeLauncherOpenSnapshot = new AtomicReference<>();
    protected final AtomicReference<MiuiHomePermissionMergeToken>
            miuiHomePermissionMergeToken = new AtomicReference<>();
    protected final long systemUiInputArbiterGeneration =
            Math.max(1L, SystemClock.elapsedRealtimeNanos());
    protected volatile boolean acceptingOpenSnapshots = true;
    protected volatile boolean acceptingHeadlessNavBarLifecycle = true;
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
    protected Context miuiOverviewReceiverContext;
    protected BroadcastReceiver miuiOverviewReceiver;
    protected volatile Object miuiHomeOpenBreakController;
    protected volatile boolean miuiHomeOpenBreakCommandPending;
    protected volatile long miuiHomeOpenBreakGeneration;
    protected volatile Object miuiHomeOpenBreakStateManager;
    protected volatile Object miuiHomeOpenBreakAnimationIdentity;
    // The exact native Shell cross-task animation object sourced from the registry.
    // Diagnostic hooks use this identity instead of guessing from color or call stacks.
    protected volatile Object aospCrossTaskAnimation;
    protected volatile boolean miuiHomeOpenBreakGenerationPrepared;
    protected volatile boolean miuiHomeOpenBreakAnimationActive;
    protected Context miuiHomeOpenBreakContext;
    protected Context miuiHomeOpenBreakCommandReceiverContext;
    protected BroadcastReceiver miuiHomeOpenBreakCommandReceiver;
    protected Context miuiHomeInputArbiterReceiverContext;
    protected BroadcastReceiver miuiHomeInputArbiterReceiver;
    protected volatile IBinder miuiHomeReturnHomeBinder;
    protected volatile IBinder pendingMiuiHomeReturnHomeBinder;
    protected volatile ClassLoader pendingMiuiHomeReturnHomeClassLoader;
    protected volatile Context pendingMiuiHomeReturnHomeContext;
    protected volatile String pendingMiuiHomeReturnHomeReason;
    protected volatile boolean miuiHomeSystemUiInputArbiterReady;
    protected volatile long miuiHomeSystemUiInputArbiterGeneration;
    protected volatile SharedPreferences predictiveBackPreferences;
    protected volatile boolean predictiveBackPreferencesFailureLogged;
    protected volatile boolean predictiveBackApplicationMetadataFailureLogged;
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
    /**
     * Android 17 MiuiHome is protected from every LSPosed hook in this module. The package
     * remains in the static scope solely so the same APK can continue supporting Android 16;
     * libxposed does not provide a platform-conditional scope list.
     */
    protected static boolean blockMiuiHomeXposedHooks() {
        return Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL;
    }

    protected static boolean isMiuiHomeProcess(String candidate) {
        return MIUI_HOME.equals(candidate)
                || (candidate != null && candidate.startsWith(MIUI_HOME + ":"));
    }
    protected boolean nativePluginDiagnosticsLogged;
    protected boolean headlessSysUiStateLogged;
    protected volatile Field defaultTransitionAnimationsField;
    protected volatile Field defaultTransitionAnimationSizeField;
    protected volatile Field defaultTransitionAnimExecutorField;
    protected volatile Method animatorCanReverseMethod;
    protected LegacyBackAttempt legacyBackAttempt;
    protected int legacyBackGuardPhase = BACK_GUARD_IDLE;
    protected long legacyBackGuardDeadlineUptime;
    protected long suppressedBackDownUptime;
    protected Thread suppressedBackDownThread;
    protected final ThreadLocal<Object> moduleLegacyBackInjection = new ThreadLocal<>();
    protected final Object headlessNavBarLifecycleLock = new Object();
    protected final Object backInputLifecycleLock = new Object();
    protected HeadlessNavBarLease headlessNavBarLease;
    protected volatile Object[][] pendingHotReloadInputState = new Object[0][0];
    protected volatile Object[][] pendingHotReloadHeadlessState = new Object[0][0];

    public static final class HeadlessNavBarLease {
        public final Object controller;
        public final Object navBarHelper;
        public final Object edgeBackGestureHandler;
        public final Object updaterProxy;
        public final Class<?> updaterInterface;
        public final Object backAnimation;
        public final boolean ready;
        public int navigationMode;

        public HeadlessNavBarLease(Object controller, Object navBarHelper,
                                   Object edgeBackGestureHandler, Object updaterProxy,
                                   Class<?> updaterInterface, Object backAnimation,
                                   int navigationMode, boolean ready) {
            this.controller = controller;
            this.navBarHelper = navBarHelper;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.updaterProxy = updaterProxy;
            this.updaterInterface = updaterInterface;
            this.backAnimation = backAnimation;
            this.navigationMode = navigationMode;
            this.ready = ready;
        }
    }

    public static final class OpenTransitionSnapshot {
        public final Object token;
        public final Object transitionInfo;
        public final int originalAnimatorCount;
        public final Animator[] animators;
        public final Executor animExecutor;
        public final long generation;
        public final boolean miuiSpring;
        public final Object platformHandler;
        public final Object animationManager;
        public final Object[] animationGroups;
        public final Object[] springAnimations;
        public final Object miuiShellTransitionInfo;
        public final Object shellTransitions;
        public final int transitionDebugId;
        public final AtomicInteger state = new AtomicInteger(OPEN_SNAPSHOT_PENDING);
        public final AtomicBoolean endSignalQueued = new AtomicBoolean();
        public volatile AnimatorListenerAdapter listener;
        public volatile Object springEndListener;

        public OpenTransitionSnapshot(Object token, Object transitionInfo, Animator[] animators,
                                      int originalAnimatorCount, Executor animExecutor,
                                      long generation) {
            this(token, transitionInfo, animators, originalAnimatorCount,
                    animExecutor, generation, null);
        }

        public OpenTransitionSnapshot(Object token, Object transitionInfo, Animator[] animators,
                                      int originalAnimatorCount, Executor animExecutor,
                                      long generation, Object shellTransitions) {
            this.token = token;
            this.transitionInfo = transitionInfo;
            this.originalAnimatorCount = originalAnimatorCount;
            this.animators = animators;
            this.animExecutor = animExecutor;
            this.generation = generation;
            this.miuiSpring = false;
            this.platformHandler = null;
            this.animationManager = null;
            this.animationGroups = new Object[0];
            this.springAnimations = new Object[0];
            this.miuiShellTransitionInfo = null;
            this.shellTransitions = shellTransitions;
            this.transitionDebugId = -1;
        }

        public OpenTransitionSnapshot(Object token, Object transitionInfo,
                                      int springAnimationCount, Executor animExecutor,
                                      long generation, Object platformHandler,
                                      Object animationManager, Object[] animationGroups,
                                      Object[] springAnimations,
                                      Object miuiShellTransitionInfo,
                                      int transitionDebugId) {
            this.token = token;
            this.transitionInfo = transitionInfo;
            this.originalAnimatorCount = springAnimationCount;
            this.animators = new Animator[0];
            this.animExecutor = animExecutor;
            this.generation = generation;
            this.miuiSpring = true;
            this.platformHandler = platformHandler;
            this.animationManager = animationManager;
            this.animationGroups = animationGroups;
            this.springAnimations = springAnimations;
            this.miuiShellTransitionInfo = miuiShellTransitionInfo;
            this.shellTransitions = null;
            this.transitionDebugId = transitionDebugId;
        }

        public int animationCount() {
            return miuiSpring ? originalAnimatorCount : animators.length;
        }
    }

    public static final class OpenTransitionInvalidationListener
            extends AnimatorListenerAdapter {
        public final WeakReference<SystemUiInputImpl> owner;
        public final OpenTransitionSnapshot snapshot;

        public OpenTransitionInvalidationListener(SystemUiInputImpl owner,
                                                  OpenTransitionSnapshot snapshot) {
            this.owner = new WeakReference<>(owner);
            this.snapshot = snapshot;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            SystemUiInputImpl hook = owner.get();
            if (hook != null) {
                hook.invalidateOpenTransitionSnapshot(snapshot, "cancel");
            } else {
                animation.removeListener(this);
            }
        }

        @Override
        public void onAnimationEnd(Animator animation, boolean isReverse) {
            SystemUiInputImpl hook = owner.get();
            if (hook != null) {
                hook.onOpenTransitionAnimatorEnded(snapshot, isReverse);
            } else {
                animation.removeListener(this);
            }
        }
    }

    public static final class LegacyBackAttempt {
        public final long id;
        public final Object controller;
        public final Object runningTransitionInfo;
        public final long startedUptime;

        public LegacyBackAttempt(long id, Object controller, Object runningTransitionInfo,
                                 long startedUptime) {
            this.id = id;
            this.controller = controller;
            this.runningTransitionInfo = runningTransitionInfo;
            this.startedUptime = startedUptime;
        }
    }

    /** SystemUI-only edge-width snapshot; it is not part of the cross-process contract. */
    public static final class EdgeWidthSnapshot {
        public final int leftSensitivity;
        public final int rightSensitivity;
        public final int leftTouchWidth;
        public final int rightTouchWidth;

        public EdgeWidthSnapshot(int leftSensitivity, int rightSensitivity,
                                 int leftInset, int rightInset) {
            this.leftSensitivity = leftSensitivity;
            this.rightSensitivity = rightSensitivity;
            this.leftTouchWidth = combineTouchWidth(leftSensitivity, leftInset);
            this.rightTouchWidth = combineTouchWidth(rightSensitivity, rightInset);
        }

        public static int combineTouchWidth(int sensitivity, int inset) {
            long width = (long) sensitivity + (long) inset;
            return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, width));
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

    protected boolean isNativePluginAttached(Object plugin) {
        try {
            Object view = readField(plugin, "mView");
            if (view instanceof View) {
                return ((View) view).isAttachedToWindow();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    protected void logNativePluginDiagnostics(Object edgeBackGestureHandler) {
        if (nativePluginDiagnosticsLogged) {
            return;
        }
        nativePluginDiagnosticsLogged = true;
        moduleLog(Log.WARN, TAG, "Native BackPanelController plugin unavailable"
                + ", handler=" + shortObject(edgeBackGestureHandler));
    }

    protected void ensureAospRegistryDefinitions(Object registry, String source) {
        if (registry == null) {
            return;
        }
        boolean changed = false;
        try {
            Object definitions = readField(registry, "mAnimationDefinition");
            Object defaultCrossActivity = readField(registry, "mDefaultCrossActivityAnimation");
            Object crossTask = readField(registry, "mCrossTaskAnimation");
            if (crossTask != null) {
                aospCrossTaskAnimation = crossTask;
            }
            // Cross-task keeps its own native animation: the cross-activity slide
            // mishandles its differently shaped close transition.
            changed |= ensureRegistryRunner(definitions, TYPE_CROSS_ACTIVITY,
                    defaultCrossActivity, "crossActivity");
            changed |= ensureRegistryRunner(definitions, TYPE_CROSS_TASK,
                    crossTask, "crossTask");
            if (changed) {
                invokeAnyMethod(registry, "updateSupportedAnimators", new Object[0]);
                moduleLog(Log.INFO, TAG, "Restored AOSP registry definitions from " + source);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to restore AOSP registry definitions from " + source,
                    throwable);
        }
    }

    protected boolean ensureRegistryRunner(Object definitions, int type, Object animation,
                                           String label) {
        if (definitions == null || animation == null
                || definitions instanceof SparseArray<?>
                && ((SparseArray<?>) definitions).indexOfKey(type) >= 0) {
            return false;
        }
        try {
            Object runner = invokeAnyMethod(animation, "getRunner", new Object[0]);
            if (runner == null) {
                return false;
            }
            invokeAnyMethod(definitions, "set",
                    new Object[]{Integer.valueOf(type), runner});
            moduleLog(Log.INFO, TAG, "Added " + label + " runner to registry, type=" + type
                    + ", runner=" + shortObject(runner));
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to add " + label + " runner, type=" + type,
                    throwable);
            return false;
        }
    }

    protected EdgeWidthSnapshot readEdgeWidthSnapshot(Object edgeBackGestureHandler,
                                                      float density) {
        int fallbackWidth = Math.max(1, Math.round(EDGE_TOUCH_WIDTH_DP * density));
        int leftSensitivity = readIntFieldOrDefault(edgeBackGestureHandler,
                "mEdgeWidthLeft", fallbackWidth);
        int rightSensitivity = readIntFieldOrDefault(edgeBackGestureHandler,
                "mEdgeWidthRight", fallbackWidth);
        if (leftSensitivity <= 0) {
            leftSensitivity = fallbackWidth;
        }
        if (rightSensitivity <= 0) {
            rightSensitivity = fallbackWidth;
        }
        int leftInset = readIntFieldOrDefault(edgeBackGestureHandler, "mLeftInset", 0);
        int rightInset = readIntFieldOrDefault(edgeBackGestureHandler, "mRightInset", 0);
        return new EdgeWidthSnapshot(leftSensitivity, rightSensitivity,
                leftInset, rightInset);
    }

    protected void logBackNavigationInfo(Object info) {
        if (info == null) {
            moduleLog(Log.INFO, TAG, "BackNavigationInfo=null");
            return;
        }
        try {
            BackNavigationInfo navigationInfo = (BackNavigationInfo) info;
            int type = navigationInfo.getType();
            boolean prepare = navigationInfo.isPrepareRemoteAnimation();
            Object callback = navigationInfo.getOnBackInvokedCallback();
            moduleLog(Log.INFO, TAG, "BackNavigationInfo detail: type=" + type
                    + ", prepareRemoteAnimation=" + prepare
                    + ", callback=" + shortObject(callback));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to inspect BackNavigationInfo", throwable);
        }
    }

    protected void forceSystemUiCallbackProgress(Object info) {
        if (info == null) {
            return;
        }
        try {
            BackNavigationInfo navigationInfo = (BackNavigationInfo) info;
            if (navigationInfo.getType() == TYPE_CALLBACK) {
                navigationInfo.disableAppProgressGenerationAllowed();
                moduleLog(Log.INFO, TAG,
                        "Disabled app-generated progress for TYPE_CALLBACK; SystemUI will dispatch progress");
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to force SystemUI callback progress", throwable);
        }
    }

    /**
     * Reads the framework back target without routing a boot-classpath call through reflection.
     * A non-framework object is deliberately treated as unavailable so wrapper/compatibility
     * objects still fail closed at their existing callers.
     */
    protected abstract void sendAuthenticatedMiuiHomeOpenBreakCommand(
            Context context, long generation, long attemptId,
            SystemUiBackGestureDriver driver, Object releaseController);
    protected abstract void publishSystemUiReturnHomeFinish(
            Object controller, long shellSessionId,
            Object finishCallback, String reason);
    /** SystemUI-only native panel operations are deliberately inert for MiuiHome. */
    protected Object findNativeEdgeBackPlugin(
            Object edgeBackGestureHandler) throws Exception {
        return null;
    }

    protected void prepareNativeBackPanel(
            Object edgeBackGestureHandler, Object plugin) throws Exception {
    }

    protected void updateNativeBackPanelDisplaySize(
            Object edgeBackGestureHandler, Object plugin) throws Exception {
    }

    protected boolean isNavigationOverlayExcluded(
            Object edgeBackGestureHandler, int x, int y) throws Exception {
        return false;
    }

    protected void injectPlatformLegacyBackKey(
            Object controller, int displayId) throws Exception {
    }

    protected static boolean hasXiaomiBackIntent(
            float outwardDistance, float verticalDelta, float outwardThreshold) {
        return outwardDistance > outwardThreshold
                && outwardDistance >= Math.abs(verticalDelta) / 2.0f;
    }

    protected final Map<Object, NativeBackInputMonitor> nativeInputMonitors =
            Collections.synchronizedMap(new WeakHashMap<>());
    protected final Map<Object, ContextualSearchInputReceiver> contextualSearchInputReceivers =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** NavigationBar instances remain discoverable while the Android 16 switch is disabled. */
    protected final Set<Object> contextualSearchNavigationBars =
            Collections.newSetFromMap(new WeakHashMap<>());
    protected volatile Object[] pendingHotReloadContextualSearchNavigationBars = new Object[0];

    protected volatile SharedPreferences hyperOsIndicatorPreferences;
    protected volatile boolean hyperOsIndicatorPreferencesFailureLogged;
    protected volatile MiuiHapticFeedbackHelper hyperOsBackHapticHelper;
    protected volatile SharedPreferences gestureTriggerPreferences;
    protected volatile boolean gestureTriggerPreferencesFailureLogged;
    protected volatile SharedPreferences contextualSearchPreferences;
    protected volatile boolean contextualSearchPreferencesFailureLogged;
    protected volatile boolean contextualSearchServiceUnavailableLogged;

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
            boolean enabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            contextualSearchPreferencesFailureLogged = false;
            return enabled;
        } catch (Throwable throwable) {
            if (!contextualSearchPreferencesFailureLogged) {
                contextualSearchPreferencesFailureLogged = true;
                moduleLog(Log.ERROR, TAG, "Contextual-search preference unavailable"
                        + ", policy=failClosed", throwable);
            }
            return false;
        }
    }

    /**
     * Android 17 delegates the terminal long-press callback to native MiuiHome.  Xiaomi's
     * helper aborts the launcher when the platform contextual-search Binder service is absent,
     * which can happen after an API-102 hot upgrade because the service startup gate has already
     * run.  Never publish the requested preference to that native owner until the service really
     * exists.  Android 16 keeps its existing Java path, whose Binder request already fails closed.
     */
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
                if (contextualSearchServiceUnavailableLogged) {
                    moduleLog(Log.INFO, TAG,
                            "Contextual-search service became available;"
                                    + " native launcher state may be enabled");
                }
                contextualSearchServiceUnavailableLogged = false;
                return true;
            }
            if (!contextualSearchServiceUnavailableLogged) {
                contextualSearchServiceUnavailableLogged = true;
                moduleLog(Log.WARN, TAG,
                        "Suppressed Android 17 native contextual search because the"
                                + " contextual_search service is not registered;"
                                + " policy=failClosed, restartRequiredAfterHotUpgrade=true");
            }
            return false;
        } catch (Throwable throwable) {
            if (!contextualSearchServiceUnavailableLogged) {
                contextualSearchServiceUnavailableLogged = true;
                moduleLog(Log.ERROR, TAG,
                        "Failed to verify Android 17 contextual-search service;"
                                + " policy=failClosed",
                        throwable);
            }
            return false;
        }
    }

    protected boolean isContextualSearchHapticsEnabled() {
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
            return preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_HAPTICS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_HAPTICS);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search haptic preference unavailable, policy=disabled",
                    throwable);
            return false;
        }
    }

    /** MiCTS-compatible click feedback, emitted only after native CTS succeeds. */
    @SuppressLint("MissingPermission") // Runs inside SystemUI, which owns VIBRATE.
    protected boolean playContextualSearchHaptic(Context context) {
        if (context == null || !isContextualSearchHapticsEnabled()) {
            return false;
        }
        try {
            Vibrator vibrator = context.getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return false;
            }
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setFlags(128)
                    .build();
            vibrator.vibrate(VibrationEffect.createPredefined(
                    VibrationEffect.EFFECT_CLICK), attributes);
            moduleLog(Log.INFO, TAG, "Played contextual-search trigger haptic");
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to play contextual-search trigger haptic", throwable);
            return false;
        }
    }

    protected void attachContextualSearchInputReceiver(Object navigationBar) {
        detachContextualSearchInputReceiver(navigationBar);
        synchronized (contextualSearchNavigationBars) {
            contextualSearchNavigationBars.add(navigationBar);
        }
        if (!isContextualSearchLongPressEnabled()) {
            return;
        }
        try {
            Object viewObject;
            try {
                viewObject = invokeAnyMethod(navigationBar, "getView", new Object[0]);
            } catch (Throwable getterFailure) {
                viewObject = readField(navigationBar, "mView");
            }
            if (!(viewObject instanceof View)) {
                throw new IllegalStateException("NavigationBar view is "
                        + shortObject(viewObject));
            }
            View navigationView = (View) viewObject;
            Display display = navigationView.getDisplay();
            int displayId = display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            Context context = navigationView.getContext();
            InputManager inputManager = context.getSystemService(InputManager.class);
            if (inputManager == null) {
                throw new IllegalStateException("InputManager unavailable");
            }
            Object monitorObject = invokeAnyMethod(inputManager, "monitorGestureInput",
                    new Object[]{"miui-contextual-search", Integer.valueOf(displayId)});
            if (!(monitorObject instanceof InputMonitor)) {
                throw new IllegalStateException("monitorGestureInput returned "
                        + shortObject(monitorObject));
            }
            InputMonitor inputMonitor = (InputMonitor) monitorObject;
            ContextualSearchInputReceiver receiver;
            try {
                receiver = new ContextualSearchInputReceiver(
                        context, navigationBar, navigationView, inputMonitor, displayId);
            } catch (Throwable throwable) {
                inputMonitor.dispose();
                throw throwable;
            }
            synchronized (contextualSearchInputReceivers) {
                contextualSearchInputReceivers.put(navigationBar, receiver);
            }
            moduleLog(Log.INFO, TAG, "Contextual-search gesture observer attached"
                    + ", displayId=" + displayId
                    + ", enabled=" + isContextualSearchLongPressEnabled()
                    + ", recognitionTimeoutMs=" + receiver.getRecognitionTimeoutMillis());
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to attach contextual-search gesture observer", throwable);
        }
    }

    protected void detachContextualSearchInputReceiver(Object navigationBar) {
        ContextualSearchInputReceiver receiver;
        synchronized (contextualSearchInputReceivers) {
            receiver = contextualSearchInputReceivers.remove(navigationBar);
        }
        if (receiver != null) {
            receiver.detach();
        }
    }

    protected void forgetContextualSearchNavigationBar(Object navigationBar) {
        synchronized (contextualSearchNavigationBars) {
            contextualSearchNavigationBars.remove(navigationBar);
        }
    }

    /**
     * Reconcile the Android 16 input observer set after the remote switch changes.
     * Android 17's native launcher path does not call this method; it receives the
     * existing arbiter-state update instead.
     */
    protected void refreshContextualSearchInputReceivers() {
        Object[] navigationBars;
        synchronized (contextualSearchNavigationBars) {
            navigationBars = contextualSearchNavigationBars.toArray();
        }
        for (Object navigationBar : navigationBars) {
            if (navigationBar != null) {
                detachContextualSearchInputReceiver(navigationBar);
            }
        }
        if (!isContextualSearchLongPressEnabled()) {
            return;
        }
        for (Object navigationBar : navigationBars) {
            if (navigationBar != null) {
                attachContextualSearchInputReceiver(navigationBar);
            }
        }
        moduleLog(Log.INFO, TAG,
                "Refreshed Android 16 contextual-search gesture observers"
                        + ", count=" + navigationBars.length);
    }

    protected Object[] detachAllContextualSearchInputReceiversForHotReload() {
        Object[] navigationBars;
        ContextualSearchInputReceiver[] receivers;
        synchronized (contextualSearchNavigationBars) {
            navigationBars = contextualSearchNavigationBars.toArray();
        }
        synchronized (contextualSearchInputReceivers) {
            receivers = contextualSearchInputReceivers.values().toArray(
                    new ContextualSearchInputReceiver[0]);
            contextualSearchInputReceivers.clear();
        }
        for (ContextualSearchInputReceiver receiver : receivers) {
            receiver.detach();
        }
        return navigationBars;
    }

    protected void restoreContextualSearchInputReceivers(Object[] navigationBars) {
        if (navigationBars == null || navigationBars.length == 0) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            int restored = 0;
            for (Object navigationBar : navigationBars) {
                if (navigationBar == null) {
                    continue;
                }
                attachContextualSearchInputReceiver(navigationBar);
                restored++;
            }
            moduleLog(Log.INFO, TAG,
                    "Restored contextual-search gesture observers after hot reload"
                            + ", count=" + restored);
        });
    }

    protected boolean invokeContextualSearchService() {
        if (!isContextualSearchLongPressEnabled()) {
            return false;
        }
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Object binderObject = serviceManagerClass
                    .getMethod("getService", String.class)
                    .invoke(null, "contextual_search");
            if (!(binderObject instanceof IBinder)) {
                moduleLog(Log.WARN, TAG,
                        "Contextual-search service is not registered; restart may be required");
                return false;
            }
            Class<?> stubClass = Class.forName(
                    "android.app.contextualsearch.IContextualSearchManager$Stub");
            Object service = stubClass.getMethod("asInterface", IBinder.class)
                    .invoke(null, binderObject);
            if (service == null) {
                return false;
            }
            Class<?> interfaceClass = Class.forName(
                    "android.app.contextualsearch.IContextualSearchManager");
            Method startMethod;
            Object[] arguments;
            try {
                startMethod = interfaceClass.getMethod(
                        "startContextualSearch", int.class);
                arguments = new Object[]{Integer.valueOf(1)};
            } catch (NoSuchMethodException qpr0Missing) {
                Class<?> configClass = Class.forName(
                        "android.app.contextualsearch.ContextualSearchConfig");
                startMethod = interfaceClass.getMethod(
                        "startContextualSearch", int.class, configClass);
                arguments = new Object[]{Integer.valueOf(1), null};
            }
            startMethod.invoke(service, arguments);
            moduleLog(Log.INFO, TAG,
                    "Requested contextual search from the navigation handle"
                            + ", signature=" + startMethod.toGenericString());
            return true;
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search Binder request failed", throwable);
            return false;
        }
    }

    protected final class ContextualSearchInputReceiver extends InputEventReceiver {
        private static final int HANDLE_HORIZONTAL_PADDING_DP = 12;
        private static final int HANDLE_BOTTOM_REGION_HEIGHT_DP = 32;
        private static final int HANDLE_MAX_TOUCH_WIDTH_DP = 192;
        private static final long LONG_PRESS_OWNERSHIP_LEAD_MILLIS = 75L;
        private static final long MIN_LONG_PRESS_TIMEOUT_MILLIS = 200L;

        private final Object navigationBar;
        private final View navigationView;
        private final InputMonitor inputMonitor;
        private final int displayId;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final KeyguardManager keyguardManager;
        private final float touchSlopSquared;
        private final float upwardIntentDistance;
        private final long longPressTimeoutMillis;
        private boolean tracking;
        private boolean pilfered;
        private float downX;
        private float downY;
        private float lastX;
        private float lastY;
        private final Runnable longPress;

        private void onLongPressTimeout() {
            if (!tracking || pilfered || hasUpwardGestureIntent()
                    || shouldRejectLongPress()) {
                cancelTracking(false);
                return;
            }
            try {
                inputMonitor.pilferPointers();
                pilfered = true;
                moduleLog(Log.INFO, TAG,
                        "Claimed navigation-handle long press for contextual search"
                                + ", displayId=" + displayId);
                invokeContextualSearchService();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to claim contextual-search long press", throwable);
                cancelTracking(false);
            }
        }

        ContextualSearchInputReceiver(Context context, Object navigationBar,
                                      View navigationView, InputMonitor inputMonitor,
                                      int displayId) {
            super(inputMonitor.getInputChannel(), Looper.getMainLooper());
            this.navigationBar = navigationBar;
            this.navigationView = navigationView;
            this.inputMonitor = inputMonitor;
            this.displayId = displayId;
            this.keyguardManager = context.getSystemService(KeyguardManager.class);
            float touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            this.touchSlopSquared = touchSlop * touchSlop;
            this.upwardIntentDistance = Math.max(
                    touchSlop * 0.5f,
                    4.0f * context.getResources().getDisplayMetrics().density);
            long systemLongPressTimeoutMillis = ViewConfiguration.getLongPressTimeout();
            // Xiaomi's navigation-handle assistant action uses the platform long-press
            // deadline. Claim slightly before that deadline so the spy monitor can pilfer the
            // same physical stream before the competing callback commits.
            this.longPressTimeoutMillis = Math.min(
                    systemLongPressTimeoutMillis,
                    Math.max(MIN_LONG_PRESS_TIMEOUT_MILLIS,
                            systemLongPressTimeoutMillis
                                    - LONG_PRESS_OWNERSHIP_LEAD_MILLIS));
            this.longPress = this::onLongPressTimeout;
        }

        long getRecognitionTimeoutMillis() {
            return longPressTimeoutMillis;
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                if (event instanceof MotionEvent) {
                    handled = handleMotionEvent((MotionEvent) event);
                }
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Contextual-search input observer failed", throwable);
                cancelTracking(true);
            } finally {
                finishInputEvent(event, handled);
            }
        }

        void detach() {
            cancelTracking(true);
            try {
                dispose();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to dispose contextual-search input receiver", throwable);
            }
            try {
                inputMonitor.dispose();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to dispose contextual-search input monitor", throwable);
            }
        }

        private boolean handleMotionEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelTracking(true);
                    if (shouldRejectLongPress()
                            || !containsGestureHandle(event.getX(), event.getY())) {
                        return false;
                    }
                    tracking = true;
                    downX = event.getX();
                    downY = event.getY();
                    lastX = downX;
                    lastY = downY;
                    moduleLog(Log.INFO, TAG,
                            "Tracking navigation-handle long press"
                                    + ", displayId=" + displayId
                                    + ", x=" + Math.round(downX)
                                    + ", y=" + Math.round(downY));
                    mainHandler.postDelayed(longPress, longPressTimeoutMillis);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (tracking && !pilfered) {
                        lastX = event.getX();
                        lastY = event.getY();
                        float deltaX = lastX - downX;
                        float deltaY = lastY - downY;
                        if (event.getPointerCount() != 1
                                || hasUpwardGestureIntent()
                                || deltaX * deltaX + deltaY * deltaY > touchSlopSquared) {
                            moduleLog(Log.INFO, TAG,
                                    "Cancelled navigation-handle long press on movement"
                                            + ", displayId=" + displayId
                                            + ", dx=" + Math.round(deltaX)
                                            + ", dy=" + Math.round(deltaY)
                                            + ", pointers=" + event.getPointerCount());
                            cancelTracking(false);
                        }
                    }
                    return pilfered;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    boolean cancelledHandled = pilfered;
                    cancelTracking(true);
                    return cancelledHandled;
                case MotionEvent.ACTION_UP:
                    boolean upHandled = pilfered;
                    cancelTracking(true);
                    return upHandled;
                default:
                    return pilfered;
            }
        }

        private boolean hasUpwardGestureIntent() {
            float deltaX = lastX - downX;
            float deltaY = lastY - downY;
            float upward = -deltaY;
            return upward >= upwardIntentDistance
                    && upward >= Math.abs(deltaX) * 0.5f;
        }

        private boolean shouldRejectLongPress() {
            // HyperOS 3 / Android 16 does not expose
            // NavigationBar.shouldDisableNavbarGestures().  The observer is attached only to
            // the live default-display NavigationBar and the DOWN must still hit its visible
            // home handle, so keep the stable ownership checks here instead of failing every
            // stream on a version-specific helper.
            return !isContextualSearchLongPressEnabled()
                    || (keyguardManager != null && keyguardManager.isKeyguardLocked())
                    || !navigationView.isAttachedToWindow()
                    || !navigationView.isShown();
        }

        private boolean containsGestureHandle(float x, float y) {
            int handleId = navigationView.getResources().getIdentifier(
                    "home_handle", "id", SYSTEM_UI);
            if (handleId == 0) {
                return false;
            }
            View handle = navigationView.findViewById(handleId);
            if (handle == null || !handle.isShown() || handle.getAlpha() <= 0.0f) {
                return false;
            }
            Rect bounds = new Rect();
            if (!handle.getGlobalVisibleRect(bounds) || bounds.isEmpty()) {
                return false;
            }
            float density = navigationView.getResources().getDisplayMetrics().density;
            int screenHeight = navigationView.getResources()
                    .getDisplayMetrics().heightPixels;
            float bottomRegionHeight = HANDLE_BOTTOM_REGION_HEIGHT_DP * density;
            if (y < screenHeight - bottomRegionHeight || y > screenHeight) {
                return false;
            }
            float touchWidth = Math.min(
                    HANDLE_MAX_TOUCH_WIDTH_DP * density,
                    bounds.width() + 2.0f * HANDLE_HORIZONTAL_PADDING_DP * density);
            float centerX = bounds.exactCenterX();
            return x >= centerX - touchWidth / 2.0f
                    && x <= centerX + touchWidth / 2.0f;
        }

        private void cancelTracking(boolean clearPilfered) {
            mainHandler.removeCallbacks(longPress);
            tracking = false;
            if (clearPilfered) {
                pilfered = false;
            }
        }
    }

    protected boolean isHyperOsIndicatorEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
                PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR);
    }

    protected boolean isHyperOsHapticsEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_HYPEROS_HAPTICS,
                PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS);
    }

    protected boolean isHyperOsHapticsEnhancedEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
                PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_ENHANCED);
    }

    protected boolean isHyperOsSlideAnimationEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
                PredictiveBackPreferences.DEFAULT_HYPEROS_SLIDE_ANIMATION);
    }

    protected boolean isOneUiCrossTaskAnimationEnabled() {
        return readHyperOsBooleanPreference(
                PredictiveBackPreferences.KEY_ONEUI_CROSS_TASK_ANIMATION,
                PredictiveBackPreferences.DEFAULT_ONEUI_CROSS_TASK_ANIMATION);
    }

    protected boolean readHyperOsBooleanPreference(String key, boolean defaultValue) {
        try {
            SharedPreferences preferences = hyperOsIndicatorPreferences;
            if (preferences == null) {
                synchronized (this) {
                    preferences = hyperOsIndicatorPreferences;
                    if (preferences == null) {
                        preferences = getRemotePreferences(
                                PredictiveBackPreferences.GROUP);
                        hyperOsIndicatorPreferences = preferences;
                    }
                }
            }
            boolean enabled = preferences.getBoolean(key, defaultValue);
            hyperOsIndicatorPreferencesFailureLogged = false;
            return enabled;
        } catch (Throwable throwable) {
            if (!hyperOsIndicatorPreferencesFailureLogged) {
                hyperOsIndicatorPreferencesFailureLogged = true;
                moduleLog(Log.ERROR, TAG, "HyperOS indicator preference unavailable"
                        + ", policy=failClosedToAospPanel"
                        + ", key=" + key, throwable);
            }
            return false;
        }
    }

    /**
     * Replaces AOSP threshold haptics with HyperOS's default back effect. The
     * caller must keep the original AOSP method when this returns false.
     */
    protected boolean playHyperOsReplacementHaptic(Object panelViewOrController) {
        // HyperOS indicator mode owns its own two-stage feedback path. In AOSP-panel mode,
        // this same global switch replaces the panel's original threshold feedback.
        if (isHyperOsIndicatorEnabled() || !isHyperOsHapticsEnabled()) {
            return false;
        }
        try {
            MiuiHapticFeedbackHelper helper = hyperOsBackHapticHelper;
            if (helper == null) {
                synchronized (this) {
                    helper = hyperOsBackHapticHelper;
                    if (helper == null) {
                        Object panelView = panelViewOrController instanceof View
                                ? panelViewOrController
                                : readField(panelViewOrController, "mView");
                        if (!(panelView instanceof View)) {
                            return false;
                        }
                        Context panelContext = ((View) panelView).getContext();
                        helper = new MiuiHapticFeedbackHelper(panelContext,
                                (priority, message, throwable) -> {
                                    if (throwable == null) {
                                        moduleLog(priority, TAG, message);
                                    } else {
                                        moduleLog(priority, TAG, message, throwable);
                                    }
                                });
                        hyperOsBackHapticHelper = helper;
                    }
                }
            }
            if (!helper.isDefaultBackSupported()) {
                return false;
            }
            return helper.performDefaultBack();
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to replace native threshold haptic; keeping native feedback",
                    throwable);
            return false;
        }
    }

    /**
     * Adds the optional committed-release haptic while the native AOSP indicator remains
     * visible. HyperOS indicator mode already dispatches this stage from its arrow driver.
     */
    protected void playAospIndicatorHandUpHaptic(Object panelViewOrController) {
        if (isHyperOsIndicatorEnabled()
                || !isHyperOsHapticsEnabled()
                || !isHyperOsHapticsEnhancedEnabled()) {
            return;
        }
        try {
            MiuiHapticFeedbackHelper helper = hyperOsBackHapticHelper;
            if (helper == null) {
                Object panelView = panelViewOrController instanceof View
                        ? panelViewOrController
                        : readField(panelViewOrController, "mView");
                if (!(panelView instanceof View)) {
                    return;
                }
                Context panelContext = ((View) panelView).getContext();
                helper = new MiuiHapticFeedbackHelper(panelContext,
                        (priority, message, throwable) -> {
                            if (throwable == null) {
                                moduleLog(priority, TAG, message);
                            } else {
                                moduleLog(priority, TAG, message, throwable);
                            }
                        });
                hyperOsBackHapticHelper = helper;
            }
            if (!helper.isSupported()) {
                return;
            }
            helper.setEnhancedMode(true);
            helper.performHandUp();
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to play AOSP-indicator hand-up haptic", throwable);
        }
    }

    protected void closeHyperOsBackHapticHelper() {
        MiuiHapticFeedbackHelper helper = hyperOsBackHapticHelper;
        hyperOsBackHapticHelper = null;
        if (helper != null) {
            helper.close();
        }
    }

    protected boolean isOpenEndHandoffCurrent(long handoffEpoch) {
        return handoffEpoch != 0L
                && openSnapshotLifecycleEpoch.get() == handoffEpoch
                && runningOpenTransitions.isEmpty();
    }

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

    protected final class NativeBackInputMonitor extends InputEventReceiver {
        protected final Context context;
        protected final Object edgeBackGestureHandler;
        protected final InputMonitor inputMonitor;
        public final SystemUiBackGestureDriver driver;
        protected final int displayId;
        protected boolean gestureCandidate;
        protected boolean launcherOpenBreakCandidate;
        protected long launcherOpenBreakGenerationCandidate;
        protected boolean launcherShadeCandidate;
        protected boolean launcherXiaoAiCandidate;
        protected boolean launcherDrawerCandidate;
        protected boolean launcherEditingCandidate;
        protected boolean miuiHomeInputAccepted;
        protected boolean pilfered;
        protected boolean waitingForTransientBarsAtDown;
        protected boolean arbiterAttached;
        protected final AtomicBoolean detachStarted = new AtomicBoolean();
        protected int activeEdge;
        protected int downEventId;
        protected int downDeviceId = Integer.MIN_VALUE;
        protected int downSource;
        protected int downDisplayId = Integer.MIN_VALUE;
        protected float downX;
        protected float downY;
        protected long downTime = Long.MIN_VALUE;
        protected long downLauncherStateOwnerEpoch;
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
                    controller, backAnimationImpl, displayId);
        }

        void attach() {
            if (!arbiterAttached) {
                arbiterAttached = true;
                driver.onInputMonitorAttached();
                onSystemUiInputMonitorAttached(context);
            }
            moduleLog(Log.INFO, TAG, "Native SystemUI back input receiver attached"
                    + ", inputModel=miuihome-accepted-token");
        }

        public void detach() {
            if (!detachStarted.compareAndSet(false, true)) {
                return;
            }
            if (arbiterAttached) {
                arbiterAttached = false;
                onSystemUiInputMonitorDetached(context);
            }
            Runnable disposeOnOwner = () -> {
                resetCandidate();
                driver.detach();
                try {
                    dispose();
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "Failed to dispose native back input receiver", throwable);
                }
                try {
                    inputMonitor.dispose();
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "Failed to dispose native back input monitor", throwable);
                }
                moduleLog(Log.INFO, TAG,
                        "Native SystemUI back input receiver detached on owner Looper");
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                disposeOnOwner.run();
            } else {
                // InputEventReceiver enforces owner-Looper disposal on Android 17.
                // Hot reload runs from an LSPosed Binder thread, and its later
                // restoration is also queued to main, so FIFO ordering disposes
                // this old receiver before a replacement monitor is attached.
                new Handler(Looper.getMainLooper()).post(disposeOnOwner);
            }
        }

        void updateBackAnimation(Object newBackAnimationImpl) throws Exception {
            driver.updateBackAnimation(newBackAnimationImpl);
        }

        public boolean blocksHotReload() {
            return driver.blocksHotReload();
        }

        public String describeActiveShellSession() {
            return driver.describeActiveShellSession();
        }

        Runnable captureShellAnimationCompletion(
                Object finishedController, Object currentTracker,
                Object queuedTracker, Object navigation,
                Object finishCallback, String reason) {
            return driver.captureShellAnimationCompletion(
                    finishedController, currentTracker, queuedTracker,
                    navigation, finishCallback, reason);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            try {
                if (event instanceof MotionEvent) {
                    handled = handleMotionEvent((MotionEvent) event);
                }
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Native SystemUI back input failed", throwable);
                resetCandidate();
            } finally {
                finishInputEvent(event, handled);
            }
        }

        protected boolean handleMotionEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && gestureCandidate
                    && Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL
                    && downLauncherStateOwnerEpoch > 0L
                    && downLauncherStateOwnerEpoch
                    != miuiLauncherDartStateOwnerEpoch) {
                boolean handled = pilfered;
                cancelNativeCandidate(event,
                        "launcher Dart state owner changed");
                return handled;
            }
            switch (action) {
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
            downLauncherStateOwnerEpoch = miuiLauncherDartStateOwnerEpoch;
            try {
                downEventId = readMotionEventId(event);
                downDisplayId = readMotionEventDisplayId(event);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Cannot identify pending MiuiHome DOWN", throwable);
                resetCandidate();
                return false;
            }
            pendingDownEvent = MotionEvent.obtain(event);
            moduleLog(Log.INFO, TAG, "Native SystemUI back candidate awaiting MiuiHome acceptance"
                    + ", eventId=" + downEventId
                    + ", downTime=" + downTime
                    + ", launcherOpenBreak=" + launcherOpenBreakCandidate
                    + ", launcherOpenBreakGeneration="
                    + launcherOpenBreakGenerationCandidate
                    + ", launcherShade=" + launcherShadeCandidate
                    + ", launcherXiaoAi=" + launcherXiaoAiCandidate
                    + ", launcherDrawerOrFolder=" + launcherDrawerCandidate
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
            if (!pilfered && driver.isRecentsVisualOnlyGesture()) {
                // MiuiHome can report Overview before WMS exposes Launcher as the back target.
                // Keep the native panel responsive, but leave the real input stream untouched
                // and never retry this gesture against a later navigation target.
                if (!driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate)) {
                    resetCandidate();
                    return false;
                }
                return false;
            }
            if (!pilfered && driver.isGestureSuppressed()) {
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
                return false;
            }
            if (!pilfered && hasXiaomiBackIntent(
                    distance, event.getRawY() - downY, dp(PILFER_THRESHOLD_DP))) {
                if (!driver.canPilferAtIntentThreshold()) {
                    cancelNativeCandidate(event,
                            "Shell unavailable before pilfer");
                    return false;
                }
                pilferPointers(distance);
                if (!pilfered) {
                    cancelNativeCandidate(event, "failed to pilfer outward gesture");
                    return false;
                }
            }
            if (!driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                    launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                    launcherXiaoAiCandidate, launcherDrawerCandidate,
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
                        launcherXiaoAiCandidate,
                        launcherDrawerCandidate,
                        launcherEditingCandidate)) {
                    moduleLog(Log.INFO, TAG, "MiuiHome accepted DOWN but SystemUI path declined"
                            + ", eventId=" + token.eventId
                            + ", edge=" + token.edge);
                    resetCandidate();
                    return true;
                }
                if (!driver.bindAcceptedInput(token)) {
                    cancelNativeCandidate(down,
                            "driver owner changed before accepted-DOWN bind");
                    return true;
                }
                moduleLog(Log.INFO, TAG, "Matched MiuiHome accepted input token"
                        + ", eventId=" + token.eventId
                        + ", downTime=" + token.downTime
                        + ", displayId=" + token.displayId
                        + ", edge=" + token.edge
                        + ", generation=" + token.generation);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Failed to start accepted MiuiHome input", throwable);
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
                    && (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL
                    || (token.launcherStateOwnerEpoch > 0L
                    && token.launcherStateOwnerEpoch
                    == downLauncherStateOwnerEpoch))
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
                moduleLog(Log.INFO, TAG, "Native SystemUI back pilfered pointers"
                        + ", distance=" + distance + ", edge=" + activeEdge);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to pilfer native back pointers", throwable);
            }
        }

        protected void cancelNativeCandidate(MotionEvent event, String reason) {
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
                cancel.recycle();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to cancel native back candidate", throwable);
            }
            moduleLog(Log.INFO, TAG, "Cancelled native SystemUI back candidate before pilfer"
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
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
            } else if (allowTrigger && pilfered) {
                driver.handleTouch(event, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
            } else {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                driver.handleTouch(cancel, activeEdge, launcherOpenBreakCandidate,
                        launcherOpenBreakGenerationCandidate, launcherShadeCandidate,
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
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
                        launcherXiaoAiCandidate, launcherDrawerCandidate,
                        launcherEditingCandidate);
                cancel.recycle();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to cancel Shell back after native transient-bars claim",
                        throwable);
            }
            moduleLog(Log.INFO, TAG, "Yielded immersive side gesture to native transient bars"
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

        protected float currentDisplayHeight() {
            try {
                return Math.max(1.0f, context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getBounds().height());
            } catch (Throwable ignored) {
                return Math.max(1.0f,
                        context.getResources().getDisplayMetrics().heightPixels);
            }
        }

        /**
         * MiuiHome owns the physical side window. Keep the SystemUI spy on the same vertical
         * bounds so shrinking that native window cannot leave a second, larger SystemUI-owned
         * trigger area behind it.
         */
        protected boolean isWithinConfiguredGestureTriggerArea(MotionEvent event) {
            try {
                SharedPreferences preferences = gestureTriggerPreferences;
                if (preferences == null) {
                    synchronized (SystemUiInputImpl.this) {
                        preferences = gestureTriggerPreferences;
                        if (preferences == null) {
                            preferences = getRemotePreferences(
                                    PredictiveBackPreferences.GROUP);
                            gestureTriggerPreferences = preferences;
                        }
                    }
                }
                int heightPercent = preferences.getInt(
                        PredictiveBackPreferences.KEY_GESTURE_TRIGGER_HEIGHT_PERCENT,
                        PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT);
                int positionPercent = preferences.getInt(
                        PredictiveBackPreferences.KEY_GESTURE_TRIGGER_POSITION_PERCENT,
                        PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT);
                if (heightPercent < PredictiveBackPreferences.MIN_GESTURE_TRIGGER_HEIGHT_PERCENT
                        || heightPercent
                        > PredictiveBackPreferences.MAX_GESTURE_TRIGGER_HEIGHT_PERCENT
                        || positionPercent
                        < PredictiveBackPreferences.MIN_GESTURE_TRIGGER_POSITION_PERCENT
                        || positionPercent
                        > PredictiveBackPreferences.MAX_GESTURE_TRIGGER_POSITION_PERCENT) {
                    return true;
                }
                if (heightPercent
                        == PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT
                        && positionPercent
                        == PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT) {
                    gestureTriggerPreferencesFailureLogged = false;
                    return true;
                }
                float displayHeight = currentDisplayHeight();
                float configuredHeight = displayHeight * heightPercent / 100.0f;
                float availableTravel = Math.max(0.0f, displayHeight - configuredHeight);
                float top = availableTravel * positionPercent / 100.0f;
                float y = event.getRawY();
                boolean inside = y >= top && y < top + configuredHeight;
                gestureTriggerPreferencesFailureLogged = false;
                return inside;
            } catch (Throwable throwable) {
                if (!gestureTriggerPreferencesFailureLogged) {
                    gestureTriggerPreferencesFailureLogged = true;
                    moduleLog(Log.WARN, TAG,
                            "Gesture trigger configuration unavailable; preserving native area",
                            throwable);
                }
                return true;
            }
        }

        protected boolean canStartBackGesture(MotionEvent event, int edge) {
            if (event.getPointerCount() != 1) {
                return false;
            }
            if (!isWithinConfiguredGestureTriggerArea(event)) {
                moduleLog(Log.INFO, TAG, "Ignored native back outside configured trigger area"
                        + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY()
                        + ", edge=" + edge);
                return false;
            }
            if (!isNativeBackInputActive()) {
                return false;
            }
            ComponentName topActivity = findTopActivity();
            if (topActivity == null) {
                moduleLog(Log.WARN, TAG, "Rejected native back because top activity is unknown"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
                return false;
            }
            boolean launcherPackage = MIUI_HOME.equals(topActivity.getPackageName());
            boolean launcherHome = launcherPackage
                    && isLauncherHomeComponent(topActivity);
            boolean launcherShade = launcherHome && displayId == 0
                    && isMiuiShadeExpanded();
            boolean launcherXiaoAi = launcherHome && displayId == 0
                    && !launcherShade
                    && miuiLauncherXiaoAiVisible;
            boolean launcherOpenBreak = displayId == 0
                    && !launcherShade
                    && !launcherXiaoAi
                    && !miuiOverviewVisible
                    && miuiLauncherOpenActive
                    && miuiLauncherOpenBreakGeneration != 0L
                    && launcherOpenBreakCommandsInFlight.get() == 0;
            boolean launcherDrawer = launcherHome
                    && !launcherShade
                    && !launcherXiaoAi
                    && miuiDrawerVisible
                    && !miuiOverviewVisible
                    && !launcherOpenBreak;
            boolean launcherFolder = launcherHome
                    && !launcherShade
                    && !launcherXiaoAi
                    && miuiFolderVisible
                    && !miuiOverviewVisible
                    && !launcherOpenBreak
                    && !launcherDrawer;
            boolean launcherEditing = launcherHome
                    && !launcherShade
                    && !launcherXiaoAi
                    && miuiLauncherEditing
                    && !miuiOverviewVisible
                    && !launcherOpenBreak
                    && !launcherDrawer
                    && !launcherFolder;
            if (launcherHome && !miuiOverviewVisible
                    && !launcherOpenBreak && !launcherShade
                    && !launcherXiaoAi
                    && !launcherDrawer && !launcherFolder
                    && !launcherEditing) {
                moduleLog(Log.INFO, TAG, "Ignored native back on launcher Home"
                        + ", topActivity=" + topActivity.flattenToShortString()
                        + ", overviewVisible=false"
                        + ", launcherShade=false"
                        + ", launcherXiaoAi=false"
                        + ", launcherDrawer=false"
                        + ", launcherFolder=false"
                        + ", launcherEditing=false"
                        + ", launcherOpenActive="
                        + miuiLauncherOpenActive
                        + ", launcherOpenBreakAvailable="
                        + miuiLauncherOpenBreakAvailable
                        + ", commandsInFlight="
                        + launcherOpenBreakCommandsInFlight.get()
                        + ", generation=" + miuiLauncherOpenBreakGeneration
                        + ", displayId=" + displayId);
                return false;
            }
            if (launcherPackage && !launcherHome) {
                moduleLog(Log.INFO, TAG, "Accepted non-Home MiuiHome activity as a normal back target"
                        + ", topActivity=" + topActivity.flattenToShortString()
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherShade) {
                moduleLog(Log.INFO, TAG,
                        "Accepted native back for NotificationShade over MiuiHome Home"
                                + ", requireShellCallback=true"
                                + ", displayId=" + displayId
                                + ", edge=" + edge);
            }
            if (launcherXiaoAi) {
                moduleLog(Log.INFO, TAG,
                        "Accepted native back for XiaoAi over MiuiHome Home"
                                + ", requireShellCallback=true"
                                + ", displayId=" + displayId
                                + ", edge=" + edge);
            }
            if (launcherDrawer) {
                moduleLog(Log.INFO, TAG, "Accepted native back in MiuiHome app drawer"
                        + ", drawerVisible=true"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherFolder) {
                moduleLog(Log.INFO, TAG, "Accepted native back in MiuiHome folder"
                        + ", folderVisible=true"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherEditing) {
                moduleLog(Log.INFO, TAG, "Accepted native back in MiuiHome editing surface"
                        + ", requireShellCallback=true"
                        + ", displayId=" + displayId
                        + ", edge=" + edge);
            }
            if (launcherOpenBreak) {
                moduleLog(Log.INFO, TAG, "Accepted native back during launcher OPEN animation"
                        + ", launcherOpenBreakAvailable="
                        + miuiLauncherOpenBreakAvailable
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
                moduleLog(Log.INFO, TAG, "Ignored native back by AOSP bar-visibility policy"
                        + ", sysUiStateFlags=" + sysUiStateFlags
                        + ", edge=" + edge + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
                return false;
            }
            waitingForTransientBarsAtDown = navBarHidden && !navBarShownTransiently;
            if (navBarHidden && authenticatedHeadlessLifecycle
                    && !allowGestureIgnoringBarVisibility) {
                moduleLog(Log.INFO, TAG,
                        "Accepted native back through authenticated headless lifecycle"
                                + ", sysUiStateFlags=" + sysUiStateFlags
                                + ", edge=" + edge + ", x=" + event.getRawX()
                                + ", y=" + event.getRawY());
            } else if (navBarHidden) {
                moduleLog(Log.INFO, TAG, "AOSP bar-visibility policy allows immersive back"
                        + ", sysUiStateFlags=" + sysUiStateFlags
                        + ", edge=" + edge + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
            }
            if (!isBackGestureAllowedBySystemUiState()) {
                moduleLog(Log.INFO, TAG, "Ignored native back while SystemUI state disallows back");
                return false;
            }
            if (isInBottomGestureRegion(event)) {
                moduleLog(Log.INFO, TAG, "Ignored native back inside bottom gesture region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            if (isInMiuiSidebarRegion(event)) {
                moduleLog(Log.INFO, TAG, "Ignored native back inside MIUI sidebar bounds"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            if (isInExcludedRegion(event, edge)) {
                moduleLog(Log.INFO, TAG, "Ignored native back inside exclusion region"
                        + ", x=" + event.getRawX() + ", y=" + event.getRawY());
                return false;
            }
            launcherOpenBreakCandidate = launcherOpenBreak;
            launcherOpenBreakGenerationCandidate = launcherOpenBreak
                    ? miuiLauncherOpenBreakGeneration : 0L;
            launcherShadeCandidate = launcherShade;
            launcherXiaoAiCandidate = launcherXiaoAi;
            // Drawer and folder are mutually exclusive launcher surfaces with the same
            // callback-only Shell contract, so they share the established probe path.
            launcherDrawerCandidate = launcherDrawer || launcherFolder;
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
                    moduleLog(Log.WARN, TAG, "Rejected launcher shade state with invalid SysUiState"
                            + ", stateDisplayId=" + stateDisplayId
                            + ", monitorDisplayId=" + displayId
                            + ", flags=" + flagsObject);
                    return false;
                }
                long flags = ((Number) flagsObject).longValue();
                return (flags & SYSUI_STATE_MIUI_SHADE_EXPANDED_MASK) != 0L
                        && (flags & SYSUI_STATE_LOCKED_OR_PINNED_MASK) == 0L;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.INFO, TAG,
                            "Authenticated native SysUiState for headless NavigationBar"
                                    + ", displayId=" + displayId
                                    + ", flags=0x" + Long.toHexString(flags));
                }
                return Long.valueOf(flags);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG, "Failed to inspect gesture-blocking activity state",
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
                moduleLog(Log.WARN, TAG, "Failed to inspect top activity for native back", throwable);
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
                    moduleLog(Log.WARN, TAG, "Could not resolve a MiuiHome HOME component"
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
                moduleLog(Log.WARN, TAG, "Failed to distinguish MiuiHome Activity from launcher Home"
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
                    moduleLog(Log.WARN, TAG, "Failed to inspect running-task display"
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
                moduleLog(Log.WARN, TAG, "Failed to inspect application exclusion region",
                        throwable);
                return true;
            }
            try {
                Object region = readField(edgeBackGestureHandler, "mDesktopModeExcludeRegion");
                if (!(region instanceof Region) || ((Region) region).contains(x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect desktop exclusion region", throwable);
                return true;
            }
            try {
                if (isNavigationOverlayExcluded(edgeBackGestureHandler, x, y)) {
                    return true;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect navigation-overlay exclusion bounds",
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
                    moduleLog(Log.WARN, TAG, "Failed to inspect PiP exclusion bounds", throwable);
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
                moduleLog(Log.WARN, TAG, "Failed to inspect PiP state", throwable);
                return null;
            }
        }

        protected boolean isInMiuiSidebarRegion(MotionEvent event) {
            String encoded;
            try {
                encoded = Settings.Secure.getString(context.getContentResolver(),
                        MIUI_SIDEBAR_BOUNDS);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to read MIUI sidebar bounds", throwable);
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
                moduleLog(Log.WARN, TAG, "Failed to parse MIUI sidebar bounds: " + encoded,
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
            launcherXiaoAiCandidate = false;
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
            downLauncherStateOwnerEpoch = 0L;
        }

        protected boolean isNavigationBarHidden() {
            try {
                WindowInsets insets = context.getSystemService(WindowManager.class)
                        .getCurrentWindowMetrics().getWindowInsets();
                return insets == null
                        || !insets.isVisible(WindowInsets.Type.navigationBars());
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to inspect navigation-bar visibility", throwable);
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
                moduleLog(Log.WARN, TAG, "Failed to inspect bottom gesture region", throwable);
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
        protected final int displayId;
        protected volatile Object controller;
        public volatile Object backAnimationImpl;
        protected volatile Executor shellExecutor;
        protected volatile ShellGestureSession activeShellSession;
        protected volatile boolean shellStartInFlight;
        protected volatile boolean shellOwnerUncertain;
        protected boolean gestureActive;
        protected boolean thresholdCrossed;
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
        protected boolean launcherXiaoAiGesture;
        protected boolean launcherDrawerGesture;
        protected boolean launcherEditingGesture;
        protected boolean recentsVisualOnlyGesture;
        protected MiuiHomeAcceptedInputToken acceptedInputIdentity;
        protected ShellOwner gestureOwner;
        protected final AtomicLong inputMonitorEpoch = new AtomicLong();
        protected volatile boolean inputMonitorAttached;
        protected int activeEdge;
        protected float downX;
        protected float downY;
        protected float lastX;
        protected float lastY;
        protected MiuiStyleBackArrowOverlay miuiStyleOverlay;
        protected MiuiHapticFeedbackHelper miuiHapticHelper;
        protected boolean miuiStyleGestureActive;
        protected boolean miuiStyleSwipeStarted;
        protected boolean miuiStyleHapticsActive;

        SystemUiBackGestureDriver(Context context, Object edgeBackGestureHandler,
                                  Object controller, Object backAnimationImpl,
                                  int displayId)
                throws Exception {
            this.context = context;
            this.edgeBackGestureHandler = edgeBackGestureHandler;
            this.displayId = displayId;
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
                moduleLog(Log.ERROR, TAG,
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

        boolean bindAcceptedInput(MiuiHomeAcceptedInputToken token) {
            if (gestureActive && isShellOwnerCurrent(gestureOwner)
                    && isCurrentAcceptedInputIdentity(
                    token, activeEdge, gestureOwner.controller,
                    gestureOwner.inputEpoch)) {
                acceptedInputIdentity = token;
                ShellGestureSession session = activeShellSession;
                if (shellGestureStarted && session != null
                        && session.edge == activeEdge) {
                    session.inputIdentity.compareAndSet(null, token);
                }
                if (launcherOpenBreakGesture
                        && launcherOpenBreakGeneration
                        == miuiLauncherOpenBreakGeneration
                        && !miuiLauncherOpenActive) {
                    onLauncherOpenEnded(launcherOpenBreakGeneration);
                }
                return true;
            }
            return false;
        }

        protected boolean handleTouch(MotionEvent event, int edge,
                                    boolean launcherOpenBreakCandidate,
                                    long launcherOpenBreakGenerationCandidate,
                                    boolean launcherShadeCandidate,
                                    boolean launcherXiaoAiCandidate,
                                    boolean launcherDrawerCandidate,
                                    boolean launcherEditingCandidate) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return onDown(event, edge, launcherOpenBreakCandidate,
                                launcherOpenBreakGenerationCandidate,
                                launcherShadeCandidate,
                                launcherXiaoAiCandidate,
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
                moduleLog(Log.ERROR, TAG, "SystemUI back gesture driver failed", throwable);
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

        protected boolean canPilferAtIntentThreshold() {
            if (!gestureActive || gestureSuppressed) {
                return false;
            }
            if (!shellGestureStartDeferred
                    || findReversibleRunningOpenTransition() != null) {
                return true;
            }
            ShellOwner owner = gestureOwner;
            if (!isShellOwnerCurrent(owner)
                    || !prepareShellSessionSlotForStart()) {
                return false;
            }
            AtomicBoolean ready = new AtomicBoolean();
            AtomicReference<String> state = new AtomicReference<>("not-run");
            boolean completed = executeShellBlocking(owner.executor, () -> {
                try {
                    Object transitionHandler = readField(owner.controller,
                            "mBackTransitionHandler");
                    recoverStaleCloseTransitionRequest(
                            owner.controller,
                            readField(owner.controller, "mCurrentTracker"),
                            readField(owner.controller, "mQueuedTracker"),
                            readField(transitionHandler,
                                    "mOnAnimationFinishCallback"),
                            0L);
                    state.set(describeShellStateOnOwner(owner.controller));
                    ready.set(isShellStartReadyOnOwner(owner.controller));
                } catch (Throwable throwable) {
                    state.set(describeShellStateOnOwner(owner.controller));
                    moduleLog(Log.WARN, TAG,
                            "Failed Shell readiness check before pilfer",
                            throwable);
                }
            }, "prePilferReadiness");
            if (!completed || !ready.get()) {
                moduleLog(Log.INFO, TAG,
                        "Left accepted gesture unpilfered while Shell is busy"
                                + ", state=" + state.get());
                return false;
            }
            return true;
        }

        protected boolean onDown(MotionEvent event, int edge,
                               boolean launcherOpenBreakCandidate,
                               long launcherOpenBreakGenerationCandidate,
                               boolean launcherShadeCandidate,
                               boolean launcherXiaoAiCandidate,
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
            launcherXiaoAiGesture = launcherXiaoAiCandidate;
            if (launcherXiaoAiGesture) {
                launcherOverviewGesture = false;
            }
            launcherDrawerGesture = launcherDrawerCandidate;
            launcherEditingGesture = launcherEditingCandidate;
            recentsVisualOnlyGesture = false;
            thresholdCrossed = false;
            activeEdge = edge;
            downX = event.getRawX();
            downY = event.getRawY();
            lastX = downX;
            lastY = downY;
            gestureOwner = captureShellOwner();
            if (launcherOpenBreakGesture
                    && launcherOpenBreakGeneration
                    != miuiLauncherOpenBreakGeneration) {
                long staleGeneration = launcherOpenBreakGeneration;
                gestureActive = false;
                moduleLog(Log.WARN, TAG,
                        "Rejected stale launcher OPEN candidate"
                                + ", generation=" + staleGeneration
                                + ", currentGeneration="
                                + miuiLauncherOpenBreakGeneration
                                + ", edge=" + activeEdge);
                return false;
            }
            if (launcherOpenBreakGesture && !miuiLauncherOpenActive) {
                long endedGeneration = launcherOpenBreakGeneration;
                launcherOpenBreakGesture = false;
                launcherOpenBreakGeneration = 0L;
                launcherOpenBreakAttemptId = 0L;
                moduleLog(Log.INFO, TAG,
                        "Started expired launcher OPEN candidate on the normal Shell path"
                                + ", generation=" + endedGeneration
                                + ", edge=" + activeEdge);
            }
            if (launcherOpenBreakGesture) {
                dispatchToEdgePlugin(event, activeEdge);
                moduleLog(Log.INFO, TAG, "SystemUI-owned launcher OPEN break candidate"
                        + ", useMiuiHomeNativeController=true"
                        + ", shellGestureStarted=false"
                        + ", generation=" + launcherOpenBreakGeneration
                        + ", attempt=" + launcherOpenBreakAttemptId
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                return true;
            }
            if (launcherOverviewGesture || launcherShadeGesture
                    || launcherXiaoAiGesture
                    || launcherDrawerGesture || launcherEditingGesture) {
                moduleLog(Log.INFO, TAG, (launcherShadeGesture
                        ? "SystemUI-owned NotificationShade back gesture candidate"
                        : launcherXiaoAiGesture
                        ? "SystemUI-owned XiaoAi back gesture candidate"
                        : launcherOverviewGesture
                        ? "SystemUI-owned Recents back gesture candidate"
                        : launcherDrawerGesture
                        ? "SystemUI-owned MiuiHome drawer/folder back gesture candidate"
                        : "SystemUI-owned MiuiHome editing back gesture candidate")
                        + ", useShellCallback=true"
                        + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                // Launcher Home, Recents, drawer/folder, and editing share one Activity.
                // Resolve the callback on DOWN while the real stream is still unpilfered.
                if (!startShellGesture()) {
                    if (recentsVisualOnlyGesture) {
                        dispatchToEdgePlugin(event, activeEdge);
                        moduleLog(Log.INFO, TAG, "Started visual-only Recents edge gesture"
                                + ", staleShellTargetRejected=true"
                                + ", inputWillRemainUnpilfered=true");
                        return true;
                    }
                    gestureActive = false;
                    gestureSuppressed = false;
                    moduleLog(Log.INFO, TAG, (launcherShadeGesture
                            ? "Ignored NotificationShade gesture without a callback target"
                            : launcherXiaoAiGesture
                            ? "Ignored XiaoAi gesture without a callback target"
                            : launcherDrawerGesture
                            ? "Ignored MiuiHome drawer/folder gesture without a callback target"
                            : launcherEditingGesture
                            ? "Ignored MiuiHome editing gesture without a callback target"
                            : "Ignored Recents edge gesture without a back navigation target")
                            + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
                    return false;
                }
            } else {
                // MiuiHome GestureStub accepted the original DOWN and its legacy processor
                // was neutralized. Pilfering and Shell setup share the fixed 8dp outward
                // boundary and Xiaomi's non-terminal direction gate, with the authenticated
                // token as the ownership proof.
                shellGestureStartDeferred = true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            moduleLog(Log.INFO, TAG, "SystemUI gesture driver candidate"
                    + ", shellStartDeferred=" + shellGestureStartDeferred
                    + ", inputModel=miuihome-accepted-token"
                    + ", launcherShade=" + launcherShadeGesture
                    + ", launcherXiaoAi=" + launcherXiaoAiGesture
                    + ", launcherDrawerOrFolder=" + launcherDrawerGesture
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
            lastX = event.getRawX();
            lastY = event.getRawY();
            dispatchToEdgePlugin(event, activeEdge);
            float distance = activeEdge == EDGE_LEFT
                    ? lastX - downX
                    : downX - lastX;
            boolean intentQualified = hasXiaomiBackIntent(
                    distance, lastY - downY, dp(PILFER_THRESHOLD_DP));
            if (shellGestureStartDeferred && intentQualified) {
                shellGestureStartDeferred = false;
                if (!startShellGesture()) {
                    cancelLocalGesture(event,
                            "BackNavigationInfo unavailable at intent threshold");
                    return false;
                }
                moduleLog(Log.INFO, TAG, "Started in-app back path at 8dp intent threshold"
                        + ", shellGestureStarted=" + shellGestureStarted
                        + ", legacyInterrupt=" + legacyInterruptGesture
                        + ", aospNullNavigation=" + aospNullNavigationGesture
                        + ", edge=" + activeEdge
                        + ", x=" + event.getRawX()
                        + ", y=" + event.getRawY());
            }
            boolean crossedNow = false;
            if (!thresholdCrossed && intentQualified) {
                crossedNow = crossIntentThreshold(distance);
            }
            if (!shellGestureStartDeferred
                    && !legacyInterruptGesture && !launcherOpenBreakGesture) {
                ShellGestureSession session = activeShellSession;
                if (session == null || !queueShellMove(session,
                        event.getRawX(), event.getRawY(), distance,
                        crossedNow, thresholdCrossed
                                && !aospNullNavigationGesture)) {
                    cancelLocalGesture(event,
                            "failed to queue Shell-owner MOVE");
                    return false;
                }
            }
            return true;
        }

        void onLauncherOpenEnded(long generation) {
            if (!gestureActive || gestureSuppressed
                    || !launcherOpenBreakGesture
                    || generation == 0L
                    || launcherOpenBreakGeneration != generation
                    || miuiLauncherOpenBreakGeneration != generation
                    || miuiLauncherOpenActive
                    || pendingLauncherOpenBreakAttemptId != 0L
                    || !isShellOwnerCurrent(gestureOwner)
                    || !isCurrentAcceptedInputIdentity(
                    acceptedInputIdentity, activeEdge, gestureOwner.controller,
                    gestureOwner.inputEpoch)) {
                return;
            }
            // MiuiHome has authenticated that this exact OPEN ended. Seed the standard Shell
            // path with the coordinates accumulated while Xiaomi owned its native OPEN.
            launcherOpenBreakGesture = false;
            launcherOpenBreakGeneration = 0L;
            launcherOpenBreakAttemptId = 0L;
            handoffEndedOpenToShell("launcher OPEN",
                    ", generation=" + generation, 0L);
        }

        void onInAppOpenTransitionEnded(
                OpenTransitionSnapshot snapshot, long handoffEpoch) {
            if (!gestureActive || gestureSuppressed
                    || !legacyInterruptGesture
                    || snapshot == null
                    || legacyRunningOpenInfo != snapshot.transitionInfo
                    || shellGestureStarted
                    || !thresholdCrossed
                    || !isOpenEndHandoffCurrent(handoffEpoch)
                    || !isShellOwnerCurrent(gestureOwner)
                    || !isCurrentAcceptedInputIdentity(
                    acceptedInputIdentity, activeEdge, gestureOwner.controller,
                    gestureOwner.inputEpoch)) {
                return;
            }
            handoffEndedOpenToShell("in-app Activity OPEN",
                    ", info=" + shortObject(snapshot.transitionInfo), handoffEpoch);
        }

        protected void handoffEndedOpenToShell(
                String openDescription, String identityDescription,
                long openHandoffEpoch) {
            if (!thresholdCrossed) {
                shellGestureStartDeferred = true;
                moduleLog(Log.INFO, TAG,
                        "Deferred " + openDescription + "-to-Shell handoff until 8dp"
                                + identityDescription
                                + ", edge=" + activeEdge);
                return;
            }
            if (openHandoffEpoch != 0L
                    && !isOpenEndHandoffCurrent(openHandoffEpoch)) {
                return;
            }
            try {
                float distance = activeEdge == EDGE_LEFT
                        ? lastX - downX : downX - lastX;
                if (openHandoffEpoch != 0L) {
                    legacyInterruptGesture = false;
                    legacyRunningOpenInfo = null;
                }
                if (!startShellGesture(
                        gestureOwner, lastX, lastY, openHandoffEpoch)) {
                    gestureSuppressed = true;
                    moduleLog(Log.WARN, TAG,
                            "Suppressed ended " + openDescription
                                    + " handoff after Shell rejected navigation"
                                    + identityDescription);
                    return;
                }
                ShellGestureSession session = activeShellSession;
                moduleLog(Log.INFO, TAG,
                        (legacyInterruptGesture
                                ? "Handed ended " + openDescription
                                + " gesture to Xiaomi OPEN interruption"
                                : "Handed ended " + openDescription + " gesture to Shell")
                                + identityDescription
                                + ", distance=" + distance
                                + ", legacyInterrupt=" + legacyInterruptGesture
                                + ", aospNullNavigation="
                                + aospNullNavigationGesture
                                + ", trackerSeededBeforeRunner="
                                + !legacyInterruptGesture
                                + ", shellSessionId="
                                + (session == null ? 0L : session.id));
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed " + openDescription + "-to-Shell handoff"
                                + identityDescription,
                        throwable);
            }
        }

        protected boolean crossIntentThreshold(float distance) {
            if (thresholdCrossed) {
                return false;
            }
            thresholdCrossed = true;
            if (legacyInterruptGesture) {
                moduleLog(Log.INFO, TAG, "MIUI in-app interrupt threshold crossed, distance="
                        + distance);
            } else if (launcherOpenBreakGesture) {
                moduleLog(Log.INFO, TAG, "MiuiHome launcher OPEN break threshold crossed, distance="
                        + distance);
            } else {
                moduleLog(Log.INFO, TAG, launcherShadeGesture
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
                dispatchToEdgePlugin(event, activeEdge);
                clearControllerTriggerAfterVisualOnlyGesture();
                clearLocalGestureState();
                moduleLog(Log.INFO, TAG, "Finished suppressed SystemUI back gesture");
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
                maybePlayAospIndicatorHandUpHaptic(
                        readNativePanelState(), allowTrigger && thresholdCrossed);
                clearControllerTriggerAfterVisualOnlyGesture();
                moduleLog(Log.INFO, TAG, "Finished visual-only Recents edge gesture"
                        + ", releaseDistance=" + releaseDistance
                        + ", nativeReleaseDelivered=" + allowTrigger
                        + ", inputRemainedUnpilfered=true");
                clearLocalGestureState();
                return false;
            }
            if (!shellGestureStarted) {
                cancelLocalGesture(event, "released before back intent qualified");
                return true;
            }
            dispatchToEdgePlugin(event, activeEdge);
            String panelStateAfterRelease = readNativePanelState();
            float releaseDistance = activeEdge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
            boolean releaseAllowed = allowTrigger && thresholdCrossed;
            ShellGestureSession session = activeShellSession;
            if (session == null || session.edge != activeEdge) {
                cancelLocalGesture(event,
                        "missing fixed Shell session at release");
                return true;
            }
            boolean ownerStillCurrent = isShellSessionOwnerCurrent(session);
            if (!ownerStillCurrent) {
                releaseAllowed = false;
                moduleLog(Log.WARN, TAG,
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
            maybePlayAospIndicatorHandUpHaptic(panelStateAfterRelease, releaseAllowed);
            MiuiHomeAcceptedInputToken releaseInputIdentity =
                    session.inputIdentity.get();
            boolean queued = queueShellReleaseTransaction(
                    session,
                    event.getRawX(), event.getRawY(), releaseDistance,
                    thresholdCrossed, releaseAllowed, activeEdge,
                    launcherOverviewGesture, launcherShadeGesture, launcherDrawerGesture,
                    launcherEditingGesture, aospNullNavigationGesture,
                    session.inputEpoch,
                    releaseInputIdentity);
            moduleLog(queued ? Log.INFO : Log.ERROR, TAG,
                    "SystemUI gesture driver release queued=" + queued
                    + ", releaseAllowed=" + releaseAllowed
                    + ", recentsShellCallback=" + launcherOverviewGesture
                    + ", shadeShellCallback=" + launcherShadeGesture
                    + ", drawerOrFolderShellCallback=" + launcherDrawerGesture
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
            boolean releaseAllowed = allowTrigger && thresholdCrossed;
            Boolean nativePanelTrigger = resolveNativePanelReleaseTrigger(
                    panelStateAfterRelease, panelReleaseDelivered);
            Object releaseController = isShellOwnerCurrent(gestureOwner)
                    ? gestureOwner.controller : null;
            boolean exactReleaseOwner = releaseController != null
                    && isCurrentAcceptedInputIdentity(
                    acceptedInputIdentity, activeEdge, releaseController,
                    gestureOwner.inputEpoch);
            boolean trigger = releaseAllowed
                    && exactReleaseOwner
                    && Boolean.TRUE.equals(nativePanelTrigger);
            if (trigger) {
                maybePlayAospIndicatorHandUpHaptic(panelStateAfterRelease, true);
            }
            if (trigger) {
                // A normal BACK creates the incoming CLOSE/TO_BACK transition. Xiaomi's
                // TransitionControllerImpl tags a consecutive inverse transition pair and
                // DefaultTransitionImpl.mergeAnimation() reverses the running OPEN animators.
                dispatchLegacyInterruptBack(releaseController);
            }
            moduleLog(Log.INFO, TAG, "Finished MIUI in-app interrupt gesture"
                    + ", releaseAllowed=" + releaseAllowed
                    + ", nativePanelTrigger=" + nativePanelTrigger
                    + ", exactReleaseOwner=" + exactReleaseOwner
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
            teardownMiuiStyleIndicator();
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
            boolean releaseAllowed = allowTrigger && thresholdCrossed;
            Boolean nativePanelTrigger = resolveNativePanelReleaseTrigger(
                    panelStateAfterRelease, panelReleaseDelivered);
            Object releaseController = isShellOwnerCurrent(gestureOwner)
                    ? gestureOwner.controller : null;
            boolean exactReleaseOwner = releaseController != null
                    && isCurrentAcceptedInputIdentity(
                    acceptedInputIdentity, activeEdge, releaseController,
                    gestureOwner.inputEpoch);
            boolean trigger = releaseAllowed
                    && exactReleaseOwner
                    && Boolean.TRUE.equals(nativePanelTrigger);
            if (trigger) {
                maybePlayAospIndicatorHandUpHaptic(panelStateAfterRelease, true);
            }
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
                            context, generation, attemptId, this,
                            releaseController);
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG, "Failed to send launcher OPEN break command",
                            throwable);
                    onLauncherOpenBreakCommandResult(generation, attemptId,
                            LAUNCHER_OPEN_BREAK_RESULT_REJECTED, "sendException",
                            releaseController);
                }
            }
            moduleLog(Log.INFO, TAG, "Finished MiuiHome launcher OPEN break gesture"
                    + ", trigger=" + trigger
                    + ", generation=" + launcherOpenBreakGeneration
                    + ", attempt=" + launcherOpenBreakAttemptId
                    + ", releaseDistance=" + releaseDistance
                    + ", releaseAllowed=" + releaseAllowed
                    + ", nativePanelTrigger=" + nativePanelTrigger
                    + ", exactReleaseOwner=" + exactReleaseOwner
                    + ", panelStateBeforeRelease="
                    + panelStateBeforeRelease
                    + ", panelStateAfterRelease="
                    + panelStateAfterRelease
                    + ", edge=" + activeEdge);
            clearLocalGestureState();
            return true;
        }

        public void onLauncherOpenBreakCommandResult(long generation, long attemptId,
                                                       int resultCode, String reason,
                                                       Object releaseController) {
            if (pendingLauncherOpenBreakGeneration != generation
                    || pendingLauncherOpenBreakAttemptId != attemptId) {
                moduleLog(Log.WARN, TAG, "Ignored stale launcher OPEN break command result"
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
                moduleLog(Log.INFO, TAG, "MiuiHome accepted launcher OPEN break command"
                        + ", generation=" + generation
                        + ", attempt=" + attemptId);
                return;
            }
            moduleLog(Log.WARN, TAG, "Falling back to one ordinary BACK after launcher OPEN "
                    + "break rejection"
                    + ", generation=" + generation
                    + ", attempt=" + attemptId
                    + ", resultCode=" + resultCode
                    + ", reason=" + reason);
            injectLegacyBackKey(releaseController);
        }

        protected void decrementLauncherOpenBreakCommandsInFlight() {
            int remaining = launcherOpenBreakCommandsInFlight.decrementAndGet();
            if (remaining < 0) {
                launcherOpenBreakCommandsInFlight.set(0);
                moduleLog(Log.WARN, TAG, "Corrected launcher OPEN break in-flight underflow");
            }
        }

        protected boolean startShellGesture() throws Exception {
            return startShellGesture(null, 0.0f, 0.0f, 0L);
        }

        protected boolean startShellGesture(
                ShellOwner progressOwner, float progressX, float progressY,
                long openHandoffEpoch) throws Exception {
            if (openHandoffEpoch != 0L
                    && !isOpenEndHandoffCurrent(openHandoffEpoch)) {
                return false;
            }
            ShellOwner owner = progressOwner == null
                    ? gestureOwner : progressOwner;
            if (!isShellOwnerCurrent(owner)) {
                moduleLog(Log.WARN, TAG, "Rejected gesture without a stable Shell start owner"
                        + ", controller=" + shortObject(controller)
                        + ", inputEpoch=" + inputMonitorEpoch.get()
                        + ", inputAttached=" + inputMonitorAttached);
                return false;
            }
            // A running Xiaomi OPEN transition is the native interruption source. Prefer it
            // even when system_server can already return a valid predictive-back navigation;
            // otherwise Shell starts a new cross-activity animation and misses reverse().
            boolean launcherCallbackOnly = launcherOverviewGesture
                    || launcherShadeGesture || launcherXiaoAiGesture
                    || launcherDrawerGesture
                    || launcherEditingGesture;
            OpenTransitionSnapshot runningOpen = launcherCallbackOnly
                    ? null : findReversibleRunningOpenTransition();
            if (runningOpen != null) {
                legacyInterruptGesture = true;
                legacyRunningOpenInfo = runningOpen.transitionInfo;
                moduleLog(Log.INFO, TAG, "Preferred running Xiaomi OPEN transition before predictive back");
                return true;
            }

            try {
                Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                updateNativeBackPanelDisplaySize(edgeBackGestureHandler, plugin);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
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
            if (!prepareShellSessionSlotForStart()) {
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
                    if (!isShellStartReadyOnOwner(owner.controller)) {
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
                    if (openHandoffEpoch != 0L
                            && !isOpenEndHandoffCurrent(openHandoffEpoch)) {
                        startResult.set(new ShellStartSnapshot(
                                false, false, "stale-ended-open-handoff",
                                null, null, false, null));
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
                    if (progressOwner != null) {
                        if (tracker == null) {
                            throw new IllegalStateException(
                                    "Accumulated Shell start has no active tracker");
                        }
                        ((BackTouchTracker) tracker).update(progressX, progressY);
                        invokeAnyMethod(owner.controller,
                                "onThresholdCrossed", new Object[0]);
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
                    moduleLog(Log.ERROR, TAG,
                            "Failed to queue abandoned Shell-start cleanup",
                            cleanupFailure);
                }
                if (!cleanupQueued && !startTaskEntered.get()) {
                    shellStartInFlight = false;
                    shellOwnerUncertain = false;
                }
                moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG,
                        "Rejected gesture after Shell blocking start timed out"
                                + ", controller="
                                + shortObject(owner.controller)
                                + ", inputEpoch=" + owner.inputEpoch);
                return false;
            }
            if (start.failure != null) {
                if (start.startInvoked) {
                    handleAbandonedShellStart(owner, start,
                            abandonedSession, linearDistance,
                            maxDistance, nonLinearFactor,
                            startX, startY, startEdge);
                }
                shellStartInFlight = false;
                moduleLog(Log.ERROR, TAG, "Shell-owner gesture start failed",
                        start.failure);
                return false;
            }
            if (!start.ready || !start.startInvoked) {
                shellStartInFlight = false;
                moduleLog(Log.WARN, TAG, "Rejected gesture while Shell is busy"
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
                moduleLog(Log.WARN, TAG, "Rejected Shell gesture after start owner changed"
                        + ", sessionId=" + session.id
                        + ", startController=" + shortObject(owner.controller)
                        + ", currentController=" + shortObject(controller)
                        + ", startInputEpoch=" + owner.inputEpoch
                        + ", currentInputEpoch=" + inputMonitorEpoch.get());
                cleanupRejectedShellGesture(session);
                return false;
            }
            if (info == null || receivedNull) {
                moduleLog(Log.WARN, TAG, "Shell rejected back navigation"
                        + ", info=" + shortObject(info)
                        + ", receivedNull=" + receivedNull
                        + ", state=" + start.stateDescription);
                if (launcherOverviewGesture) {
                    recentsVisualOnlyGesture = true;
                    clearMiuiOverviewAfterRejectedShellTarget("nullNavigation");
                }
                if (launcherCallbackOnly) {
                    cleanupRejectedShellGesture(session);
                    if (launcherOverviewGesture) {
                        moduleLog(Log.INFO, TAG, "Rejected null Recents BackNavigationInfo"
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
                    moduleLog(Log.INFO, TAG, "Using SystemUI-owned legacy BACK for possible "
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
                    moduleLog(Log.INFO, TAG, "Continuing authenticated in-app gesture with AOSP "
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
                    moduleLog(Log.WARN, TAG, (launcherShadeGesture
                            ? "Rejected non-callback NotificationShade Shell target"
                            : launcherXiaoAiGesture
                            ? "Rejected non-callback XiaoAi Shell target"
                            : launcherOverviewGesture
                            ? "Rejected stale Recents Shell target"
                            : launcherDrawerGesture
                            ? "Rejected non-callback MiuiHome drawer/folder Shell target"
                            : "Rejected non-callback MiuiHome editing Shell target")
                            + ", type=" + navigationType
                            + ", info=" + shortObject(info));
                    if (launcherOverviewGesture) {
                        recentsVisualOnlyGesture = true;
                        clearMiuiOverviewAfterRejectedShellTarget(
                                "nonCallbackType=" + navigationType);
                    }
                    cleanupRejectedShellGesture(session);
                    return false;
                }
                moduleLog(Log.INFO, TAG, (launcherShadeGesture
                        ? "Resolved NotificationShade Shell callback, type="
                        : launcherXiaoAiGesture
                        ? "Resolved XiaoAi Shell callback, type="
                        : launcherOverviewGesture
                        ? "Resolved Launcher Recents Shell callback, type="
                        : launcherDrawerGesture
                        ? "Resolved MiuiHome drawer/folder Shell callback, type="
                        : "Resolved MiuiHome editing Shell callback, type=")
                        + navigationType);
            }
            shellGestureStarted = true;
            moduleLog(Log.INFO, TAG, "SystemUI gesture driver onGestureStarted"
                    + ", shellSessionId=" + session.id
                    + ", edge=" + activeEdge + ", x=" + downX + ", y=" + downY);
            return true;
        }

        protected boolean prepareShellSessionSlotForStart() {
            ShellGestureSession completedSession;
            synchronized (backInputLifecycleLock) {
                if (shellStartInFlight || shellOwnerUncertain) {
                    moduleLog(Log.WARN, TAG,
                            "Rejected Shell start while ownership is unsettled"
                                    + ", startInFlight=" + shellStartInFlight
                                    + ", ownerUncertain=" + shellOwnerUncertain);
                    return false;
                }
                completedSession = activeShellSession;
                if (completedSession == null) {
                    return true;
                }
            }
            if (!completedSession.completionConsumed.get()
                    && retireQuiescentShellSessionBeforeStart(completedSession)) {
                return true;
            }
            synchronized (backInputLifecycleLock) {
                if (shellStartInFlight || shellOwnerUncertain) {
                    moduleLog(Log.WARN, TAG,
                            "Rejected Shell start while ownership is unsettled"
                                    + ", startInFlight=" + shellStartInFlight
                                    + ", ownerUncertain=" + shellOwnerUncertain);
                    return false;
                }
                if (activeShellSession == null) {
                    return true;
                }
                if (activeShellSession != completedSession
                        || !completedSession.completionConsumed.get()) {
                    moduleLog(Log.WARN, TAG,
                            "Rejected Shell start while another session owns the slot"
                                    + ", shellSessionId=" + completedSession.id
                                    + ", completionConsumed="
                                    + completedSession.completionConsumed.get());
                    return false;
                }
            }
            completeShellSessionOnMain(
                    completedSession, "retired-before-next-start");
            synchronized (backInputLifecycleLock) {
                return activeShellSession == null
                        && !shellStartInFlight && !shellOwnerUncertain;
            }
        }

        /**
         * A rejected remote probe can finish on Shell without delivering the normal module
         * completion callback.  Once the owner executor proves the exact stock quiescent state,
         * retire that one module session so its stale Java slot cannot reject every next DOWN.
         * This is deliberately narrower than a timeout: any remaining Shell identity or
         * transition state keeps the slot fail-closed and lets stock cleanup finish it.
         */
        protected boolean retireQuiescentShellSessionBeforeStart(
                ShellGestureSession session) {
            if (session == null || !session.releaseQueued.get()
                    || !session.awaitingStockCleanup.get()
                    || session.completionConsumed.get()
                    || !isShellSessionOwnerCurrent(session)) {
                return false;
            }
            SystemUiReturnHomeCommitIdentity returnHomeIdentity =
                    systemUiReturnHomeCommitIdentity.get();
            if (returnHomeIdentity != null
                    && returnHomeIdentity.controller == session.controller
                    && returnHomeIdentity.shellSessionId == session.id) {
                return false;
            }
            AtomicBoolean quiescent = new AtomicBoolean();
            Runnable check = () -> {
                try {
                    Object stateController = session.controller;
                    Object currentTracker = readField(stateController,
                            "mCurrentTracker");
                    Object queuedTracker = readField(stateController,
                            "mQueuedTracker");
                    Object finishCallback = readField(stateController,
                            "mBackAnimationFinishedCallback");
                    recoverStaleCloseTransitionRequest(
                            stateController, currentTracker, queuedTracker,
                            finishCallback, session.id);
                    Object transitionHandler = readField(stateController,
                            "mBackTransitionHandler");
                    boolean ready = isShellReadyOnOwner(stateController)
                            && !Boolean.TRUE.equals(readField(transitionHandler,
                            "mCloseTransitionRequested"))
                            && readField(transitionHandler,
                            "mOnAnimationFinishCallback") == null
                            && readField(transitionHandler,
                            "mPrepareOpenTransition") == null
                            && readField(transitionHandler,
                            "mClosePrepareTransition") == null
                            && readField(transitionHandler,
                            "mOpenTransitionInfo") == null
                            && readField(transitionHandler,
                            "mFinishOpenTransaction") == null
                            && readField(transitionHandler,
                            "mFinishOpenTransitionCallback") == null
                            && readField(transitionHandler,
                            "mTakeoverHandler") == null;
                    quiescent.set(ready);
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG,
                            "Failed to verify quiescent Shell session before next start",
                            throwable);
                }
            };
            if (!executeShellBlocking(session.executor, check,
                    "retireQuiescentShellSession")) {
                return false;
            }
            if (!quiescent.get()) {
                return false;
            }
            synchronized (backInputLifecycleLock) {
                if (activeShellSession != session || shellStartInFlight
                        || shellOwnerUncertain
                        || !session.releaseQueued.get()
                        || !session.completionConsumed.compareAndSet(false, true)) {
                    return false;
                }
                activeShellSession = null;
                shellOwnerUncertain = false;
            }
            clearSystemUiReturnHomeCommitIdentity(
                    session.controller, session.id,
                    "quiescent-before-next-start");
            moduleLog(Log.WARN, TAG,
                    "Retired quiescent Shell session before next start"
                            + ", shellSessionId=" + session.id);
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
                moduleLog(Log.ERROR, TAG,
                        "Could not cancel untracked Shell session"
                                + ", sessionId=" + session.id
                                + ", reason=" + reason,
                        throwable);
            }
        }

        protected OpenTransitionSnapshot findReversibleRunningOpenTransition() {
            OpenTransitionSnapshot active = null;
            for (OpenTransitionSnapshot snapshot : runningOpenTransitions.values()) {
                if (snapshot.state.get() == OPEN_SNAPSHOT_ACTIVE) {
                    if (active != null) {
                        moduleLog(Log.WARN, TAG,
                                "Rejected ambiguous reversible OPEN transitions"
                                        + ", firstInfo="
                                        + shortObject(active.transitionInfo)
                                        + ", secondInfo="
                                        + shortObject(snapshot.transitionInfo));
                        return null;
                    }
                    active = snapshot;
                }
            }
            if (active != null) {
                moduleLog(Log.INFO, TAG, "Detected reversible running OPEN transition"
                        + ", animatorCount=" + active.animationCount()
                        + ", info=" + shortObject(active.transitionInfo));
            }
            return active;
        }

        protected boolean isShellStartReadyOnOwner(Object stateController)
                throws Exception {
            if (!isShellReadyOnOwner(stateController)) {
                return false;
            }
            Object transitionHandler = readField(stateController,
                    "mBackTransitionHandler");
            return !Boolean.TRUE.equals(readField(transitionHandler,
                    "mCloseTransitionRequested"))
                    && readField(transitionHandler,
                    "mPrepareOpenTransition") == null
                    && readField(transitionHandler,
                    "mClosePrepareTransition") == null;
        }

        protected String describeShellStateOnOwner(Object stateController) {
            try {
                Object transitionHandler = readField(stateController,
                        "mBackTransitionHandler");
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
                        stateController, "mQueuedTracker"))
                        + ", closeRequested=" + readField(
                        transitionHandler, "mCloseTransitionRequested")
                        + ", prepareOpen=" + shortObject(readField(
                        transitionHandler, "mPrepareOpenTransition"))
                        + ", prepareClose=" + shortObject(readField(
                        transitionHandler, "mClosePrepareTransition"));
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
            moduleLog(queued ? Log.INFO : Log.ERROR, TAG,
                    "Rejected Shell navigation cancellation queued=" + queued
                            + ", releaseAllowed=false"
                            + ", recentsProbe=" + launcherOverviewGesture
                            + ", shadeProbe=" + launcherShadeGesture
                            + ", xiaoAiProbe=" + launcherXiaoAiGesture
                            + ", drawerOrFolderProbe=" + launcherDrawerGesture
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
                moduleLog(Log.WARN, TAG,
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
                    && (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL
                    || (inputIdentity.launcherStateOwnerEpoch > 0L
                    && inputIdentity.launcherStateOwnerEpoch
                    == miuiLauncherDartStateOwnerEpoch))
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
            launcherXiaoAiGesture = false;
            launcherDrawerGesture = false;
            launcherEditingGesture = false;
            recentsVisualOnlyGesture = false;
            acceptedInputIdentity = null;
            gestureOwner = null;
            thresholdCrossed = false;
        }

        protected void cancelLocalGesture(MotionEvent event, String reason) {
            try {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                dispatchToEdgePlugin(cancel, activeEdge);
                cancel.recycle();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to cancel local edge panel", throwable);
            }
            clearLocalGestureState();
            moduleLog(Log.INFO, TAG, "Cancelled local SystemUI back gesture, reason=" + reason);
        }

        protected Runnable captureShellAnimationCompletion(
                Object finishedController, Object currentTracker,
                Object queuedTracker, Object navigation,
                Object finishCallback, String reason) {
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
                if (exactIdentity) {
                    recoverStaleCloseTransitionRequest(
                            finishedController, currentTracker, queuedTracker,
                            finishCallback, session.id);
                }
                if (!exactIdentity) {
                    boolean quiescent = false;
                    try {
                        quiescent = activeShellSession == session
                                && isShellReadyOnOwner(finishedController);
                        if (!quiescent
                                && activeShellSession == session
                                && navigation == null
                                && session.navigation != null
                                && currentTracker != session.tracker
                                && queuedTracker == session.tracker) {
                            Object currentAfterFinish = readField(
                                    finishedController, "mCurrentTracker");
                            Object queuedAfterFinish = readField(
                                    finishedController, "mQueuedTracker");
                            Object animationFinishedCallback = readField(
                                    finishedController,
                                    "mBackAnimationFinishedCallback");
                            boolean resetNavigationLost =
                                    currentAfterFinish == session.tracker
                                    && queuedAfterFinish == currentTracker
                                    && readField(finishedController,
                                    "mBackNavigationInfo") == null
                                    && isTrackerInitial(currentAfterFinish)
                                    && isTrackerInitial(queuedAfterFinish)
                                    && !Boolean.TRUE.equals(readField(
                                    finishedController,
                                    "mPostCommitAnimationInProgress"))
                                    && !Boolean.TRUE.equals(readField(
                                    finishedController,
                                    "mBackGestureStarted"))
                                    && !Boolean.TRUE.equals(readField(
                                    finishedController,
                                    "mReceivedNullNavigationInfo"))
                                    && animationFinishedCallback != null
                                    && session.tracker instanceof BackTouchTracker
                                    && !((BackTouchTracker) session.tracker)
                                    .getTriggerBack();
                            if (resetNavigationLost) {
                                // A focus-taking window can make AOSP reset an unfinished
                                // tracker before UP. Its timeout then skips invokeOrCancelBack()
                                // because both trackers are initial, leaving the animation
                                // callback behind forever. Reuse the native cancellation path
                                // only after that exact stock finish boundary is fully neutral.
                                invokeAnyMethod(finishedController,
                                        "invokeOrCancelBack",
                                        new Object[]{session.tracker});
                                quiescent = isShellReadyOnOwner(
                                        finishedController);
                                if (quiescent) {
                                    moduleLog(Log.WARN, TAG,
                                            "Cancelled orphaned Shell animation callback"
                                                    + " after stock timeout"
                                                    + ", shellSessionId="
                                                    + session.id);
                                }
                            }
                        }
                    } catch (Throwable throwable) {
                        moduleLog(Log.WARN, TAG,
                                "Failed to verify orphaned Shell cleanup",
                                throwable);
                    }
                    if (!quiescent) {
                        moduleLog(Log.WARN, TAG,
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
                    moduleLog(Log.WARN, TAG,
                            "Accepted definitive stock cleanup for orphaned Shell session"
                                    + ", shellSessionId=" + session.id);
                }
                publishSystemUiReturnHomeFinish(
                        session.controller, session.id,
                        finishCallback, reason);
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
                    moduleLog(Log.WARN, TAG,
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
        }

        /**
         * Xiaomi's Android 17 Shell can finish the predictive animation without ever entering
         * BackTransitionHandler.handleCloseTransition(). In that case the normal finish callback
         * is already gone, both trackers are reset, and only mCloseTransitionRequested remains
         * set. That flag blocks every subsequent startBackNavigation(). Clear it only after the
         * exact stock-finish quiescence boundary has been observed on the Shell owner executor.
         */
        protected void recoverStaleCloseTransitionRequest(
                Object stateController, Object capturedCurrentTracker,
                Object capturedQueuedTracker, Object capturedFinishCallback,
                long shellSessionId) {
            try {
                Object transitionHandler = readField(stateController,
                        "mBackTransitionHandler");
                boolean stale = Boolean.TRUE.equals(readField(transitionHandler,
                        "mCloseTransitionRequested"))
                        && !Boolean.TRUE.equals(readField(stateController,
                        "mPostCommitAnimationInProgress"))
                        && !Boolean.TRUE.equals(readField(stateController,
                        "mBackGestureStarted"))
                        && !Boolean.TRUE.equals(readField(stateController,
                        "mReceivedNullNavigationInfo"))
                        && readField(stateController, "mBackNavigationInfo") == null
                        && readField(stateController,
                        "mBackAnimationFinishedCallback") == null
                        && readField(transitionHandler,
                        "mOnAnimationFinishCallback") == null
                        && readField(transitionHandler,
                        "mPrepareOpenTransition") == null
                        && readField(transitionHandler,
                        "mClosePrepareTransition") == null
                        && readField(transitionHandler,
                        "mOpenTransitionInfo") == null
                        && readField(transitionHandler,
                        "mFinishOpenTransaction") == null
                        && readField(transitionHandler,
                        "mFinishOpenTransitionCallback") == null
                        && readField(transitionHandler,
                        "mTakeoverHandler") == null
                        && isTrackerInitial(readField(stateController,
                        "mCurrentTracker"))
                        && isTrackerInitial(readField(stateController,
                        "mQueuedTracker"))
                        && capturedFinishCallback == null;
                if (!stale) {
                    return;
                }
                Object currentAfter = readField(stateController, "mCurrentTracker");
                Object queuedAfter = readField(stateController, "mQueuedTracker");
                if (currentAfter != capturedCurrentTracker
                        || queuedAfter != capturedQueuedTracker) {
                    moduleLog(Log.WARN, TAG,
                            "Skipped stale Shell close-request recovery after tracker identity changed"
                                    + ", shellSessionId=" + shellSessionId);
                    return;
                }
                writeField(transitionHandler, "mCloseTransitionRequested",
                        Boolean.FALSE);
                if (!isShellStartReadyOnOwner(stateController)) {
                    moduleLog(Log.WARN, TAG,
                            "Shell close-request recovery did not reach ready state"
                                    + ", shellSessionId=" + shellSessionId
                                    + ", state=" + describeShellStateOnOwner(stateController));
                    return;
                }
                moduleLog(Log.WARN, TAG,
                        "Recovered orphaned Shell close-transition request"
                                + ", shellSessionId=" + shellSessionId);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to recover orphaned Shell close-transition request",
                        throwable);
            }
        }

        protected boolean queueShellReleaseTransaction(ShellGestureSession session,
                                                       float rawX, float rawY,
                                                       float releaseDistance,
                                                       boolean dispatchFinalProgress,
                                                       boolean releaseAllowed,
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
                        dispatchFinalProgress, releaseAllowed, releaseEdge,
                        recentsCallback, shadeCallback, drawerCallback, editingCallback,
                        aospNullFallback, aospNullInputEpoch, inputIdentity));
                return true;
            } catch (Throwable throwable) {
                // A release must never fall back to mutating controller/tracker state from
                // the input Looper. Fail closed if the owner executor cannot be reached.
                moduleLog(Log.ERROR, TAG, "Failed to queue complete Shell release transaction"
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
                                                  boolean releaseAllowed,
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
                if (releaseAllowed && !isCurrentAcceptedInputIdentity(
                        inputIdentity, releaseEdge, releaseController,
                        session.inputEpoch)) {
                    releaseAllowed = false;
                    moduleLog(Log.WARN, TAG,
                            "Forced Shell release cancellation after launcher "
                                    + "state owner changed"
                                    + ", shellSessionId=" + session.id
                                    + ", inputOwnerEpoch="
                                    + (inputIdentity == null ? 0L
                                    : inputIdentity.launcherStateOwnerEpoch)
                                    + ", currentOwnerEpoch="
                                    + miuiLauncherDartStateOwnerEpoch);
                }
                if (session.moveFailed.get()) {
                    releaseAllowed = false;
                    moduleLog(Log.WARN, TAG,
                            "Forced Shell release cancellation after MOVE failure"
                                    + ", shellSessionId=" + session.id);
                }
                if (!isShellSessionOwnerCurrent(session)) {
                    if (releaseAllowed) {
                        moduleLog(Log.WARN, TAG,
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
                    releaseAllowed = false;
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
                // BackPanelController posts its terminal ACTIVE/INACTIVE/FLUNG decision through
                // BackAnimationImpl before this release transaction. Preserve that ordered
                // native decision. Only an invalid release (ACTION_CANCEL, owner replacement, or
                // MOVE failure) may veto it; pointer distance does not redefine native commit.
                if (!(tracker instanceof BackTouchTracker)) {
                    throw new IllegalStateException("active tracker is not BackTouchTracker: "
                            + shortObject(tracker));
                }
                boolean nativeTriggerBeforeSafetyVeto =
                        ((BackTouchTracker) tracker).getTriggerBack();
                if (!releaseAllowed && nativeTriggerBeforeSafetyVeto) {
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
                if (!(tracker instanceof BackTouchTracker)) {
                    throw new IllegalStateException("post-trigger tracker is not BackTouchTracker: "
                            + shortObject(tracker));
                }
                boolean actualTrigger = ((BackTouchTracker) tracker).getTriggerBack();
                moduleLog(Log.INFO, TAG, "Resolved Shell release trigger"
                        + ", releaseAllowed=" + releaseAllowed
                        + ", nativeTriggerBeforeSafetyVeto="
                        + nativeTriggerBeforeSafetyVeto
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
                            session, tracker, releaseAllowed,
                            actualTrigger, releaseEdge, aospNullFallback,
                            aospNullInputEpoch, inputIdentity);
                    return;
                }
                int focusedTaskId = -1;
                if (actualTrigger) {
                    Object observer = readField(releaseController,
                            "mBackTransitionObserver");
                    int focusedTaskIdObject = info.getFocusedTaskId();
                    writeField(observer, "mFocusedTaskId",
                            focusedTaskIdObject);
                    focusedTaskId = focusedTaskIdObject;
                }
                writeField(releaseController, "mThresholdCrossed", Boolean.FALSE);
                writeField(releaseController, "mPointersPilfered", Boolean.FALSE);
                writeField(releaseController, "mBackGestureStarted", Boolean.FALSE);
                setTrackerState(tracker, "FINISHED");

                if (Boolean.TRUE.equals(readField(releaseController,
                        "mPostCommitAnimationInProgress"))) {
                    session.awaitingStockCleanup.set(true);
                    moduleLog(Log.WARN, TAG, "Shell release found an existing post-commit animation"
                            + ", actualTrigger=" + actualTrigger
                            + ", edge=" + releaseEdge);
                    return;
                }
                if (actualTrigger && info.getType() == TYPE_RETURN_TO_HOME) {
                    if (focusedTaskId < 0 || inputIdentity == null
                            || inputIdentity.generation
                            != systemUiInputArbiterGeneration) {
                        moduleLog(Log.ERROR, TAG,
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
                        moduleLog(Log.INFO, TAG,
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
                    logShellReleaseResult(info, releaseAllowed, actualTrigger,
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
                    logShellReleaseResult(info, releaseAllowed, actualTrigger,
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
                    logShellReleaseResult(info, releaseAllowed, actualTrigger,
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
                logShellReleaseResult(info, releaseAllowed, actualTrigger,
                        "post-commit", releaseEdge,
                        recentsCallback, shadeCallback, drawerCallback, editingCallback);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG, "Complete Shell release transaction failed; cancelling",
                        throwable);
                cancelFailedShellRelease(session, tracker);
            }
        }

        protected void finishNullNavigationOnShellExecutor(
                ShellGestureSession session, Object tracker,
                boolean releaseAllowed, boolean actualTrigger,
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
                        && releaseAllowed && actualTrigger;
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
                    moduleLog(Log.WARN, TAG,
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
            moduleLog(commitLegacyBack ? Log.INFO : Log.WARN, TAG,
                    "Finished released gesture with null navigation"
                            + ", releaseAllowed=" + releaseAllowed
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
                moduleLog(Log.INFO, TAG,
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
                moduleLog(Log.WARN, TAG,
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
                moduleLog(Log.WARN, TAG, "Failed to dispatch final progress on Shell executor",
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
                moduleLog(Log.WARN, TAG, "Remote runner state is unknown; waiting for Shell timeout",
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
                moduleLog(Log.ERROR, TAG, "Failed to schedule required Shell animation timeout",
                        throwable);
            }
        }

        protected void failShellReleaseWithoutTracker(
                ShellGestureSession session, String reason) {
            session.awaitingStockCleanup.set(true);
            scheduleShellAnimationTimeout(session.controller);
            moduleLog(Log.ERROR, TAG,
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
                moduleLog(Log.ERROR, TAG, "Failed to cancel broken Shell release transaction",
                        throwable);
                failShellReleaseWithoutTracker(session,
                        "failedReleaseCancellationException");
            }
        }

        protected void logShellReleaseResult(BackNavigationInfo info,
                                           boolean releaseAllowed,
                                           boolean actualTrigger,
                                           String outcome, int releaseEdge,
                                           boolean recentsCallback,
                                           boolean shadeCallback,
                                           boolean drawerCallback,
                                           boolean editingCallback) {
            moduleLog(Log.INFO, TAG, "Completed Shell release transaction"
                    + ", type=" + info.getType()
                    + ", releaseAllowed=" + releaseAllowed
                    + ", actualTrigger=" + actualTrigger
                    + ", outcome=" + outcome
                    + ", recentsShellCallback=" + recentsCallback
                    + ", shadeShellCallback=" + shadeCallback
                    + ", drawerOrFolderShellCallback=" + drawerCallback
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

        protected boolean queueShellMove(
                ShellGestureSession session, float rawX, float rawY,
                float distance, boolean crossedNow,
                boolean dispatchProgress) {
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
                    } catch (Throwable throwable) {
                        session.moveFailed.set(true);
                        moduleLog(Log.ERROR, TAG,
                                "Shell-owner MOVE failed; cancelling"
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
                moduleLog(Log.ERROR, TAG,
                        "Failed to queue Shell-owner MOVE"
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

        protected void dispatchLegacyInterruptBack(Object interruptionController) {
            if (legacyRunningOpenInfo == null) {
                moduleLog(Log.WARN, TAG, "Missing correlated OPEN info for legacy interruption; "
                        + "using ordinary BACK without duplicate guard");
                injectLegacyBackKey(interruptionController);
                return;
            }
            LegacyBackAttempt attempt = armLegacyBackGuard(interruptionController,
                    legacyRunningOpenInfo);
            Object previousMarker = moduleLegacyBackInjection.get();
            moduleLegacyBackInjection.set(attempt);
            try {
                injectLegacyBackKey(interruptionController);
            } finally {
                if (previousMarker == null) {
                    moduleLegacyBackInjection.remove();
                } else {
                    moduleLegacyBackInjection.set(previousMarker);
                }
            }
        }

        protected void injectLegacyBackKey(Object injectionController) {
            if (injectionController == null) {
                moduleLog(Log.ERROR, TAG, "Cannot inject legacy BACK without a controller");
                return;
            }
            Object previousMarker = moduleLegacyBackInjection.get();
            if (previousMarker == null) {
                moduleLegacyBackInjection.set(this);
            }
            try {
                try {
                    injectPlatformLegacyBackKey(injectionController, displayId);
                    moduleLog(Log.INFO, TAG,
                            "Injected one legacy BACK pair through stock Shell helper"
                                    + ", displayId=" + displayId);
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG,
                            "Failed to inject legacy BACK pair through stock Shell helper",
                            throwable);
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
                Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                if (plugin == null) {
                    moduleLog(Log.WARN, TAG, "NavigationEdgeBackPlugin is null; native panel unavailable");
                    if (miuiStyleGestureActive) {
                        miuiStyleGestureActive = false;
                        MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
                        if (overlay != null && miuiStyleSwipeStarted) {
                            overlay.onGestureEnd(-1.0f);
                        }
                        miuiStyleSwipeStarted = false;
                        miuiStyleHapticsActive = false;
                    }
                    return false;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    prepareNativeBackPanel(plugin);
                    miuiStyleGestureActive = prepareMiuiStyleIndicator(edge)
                            && applyNativePanelSkinVisibility(plugin, true);
                    if (!miuiStyleGestureActive) {
                        applyNativePanelSkinVisibility(plugin, false);
                    }
                    miuiStyleHapticsActive = miuiStyleGestureActive
                            && prepareMiuiStyleHaptics();
                    // May clear miuiStyleHapticsActive on failure, so the overlay must
                    // learn the final value afterwards or both sources would vibrate.
                    applyNativePanelHapticSuppression(plugin, miuiStyleHapticsActive);
                    if (miuiStyleOverlay != null) {
                        miuiStyleOverlay.setHapticsForGesture(miuiStyleHapticsActive);
                    }
                }
                invokeMethod(plugin, "setIsLeftPanel",
                        new Class<?>[]{boolean.class},
                        new Object[]{Boolean.valueOf(edge == EDGE_LEFT)});
                screenEvent = MotionEvent.obtain(event);
                screenEvent.setLocation(event.getRawX(), event.getRawY());
                invokeMethod(plugin, "onMotionEvent",
                        new Class<?>[]{MotionEvent.class}, new Object[]{screenEvent});
                if (miuiStyleGestureActive) {
                    driveMiuiStyleIndicator(event, edge);
                }
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to dispatch event to NavigationEdgeBackPlugin",
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
                Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                Object state = plugin == null ? null
                        : readField(plugin, "currentState");
                return state instanceof Enum<?>
                        ? ((Enum<?>) state).name() : null;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to read native BackPanelController state",
                        throwable);
                return null;
            }
        }

        protected void maybePlayAospIndicatorHandUpHaptic(
                String panelStateAfterRelease, boolean releaseAllowed) {
            if (!releaseAllowed || !("FLUNG".equals(panelStateAfterRelease)
                    || "COMMITTED".equals(panelStateAfterRelease))) {
                return;
            }
            try {
                Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                playAospIndicatorHandUpHaptic(plugin);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Failed to prepare AOSP-indicator hand-up haptic", throwable);
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
                case "FLUNG":
                case "COMMITTED":
                    return Boolean.TRUE;
                case "ENTRY":
                case "ACTIVE":
                case "INACTIVE":
                    // These can still be waiting on Xiaomi's delayed release branch.
                    // Only FLUNG/COMMITTED prove the native panel committed this attempt.
                    return null;
                default:
                    return null;
            }
        }

        protected void prepareNativeBackPanel(Object plugin) {
            try {
                SystemUiInputImpl.this.prepareNativeBackPanel(
                        edgeBackGestureHandler, plugin);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to prepare native AOSP back panel", throwable);
            }
        }

        protected boolean prepareMiuiStyleIndicator(int edge) {
            if (!isHyperOsIndicatorEnabled()) {
                return false;
            }
            try {
                if (miuiStyleOverlay == null) {
                    miuiStyleOverlay = new MiuiStyleBackArrowOverlay(context,
                            (priority, message, throwable) -> {
                                if (throwable == null) {
                                    moduleLog(priority, TAG, message);
                                } else {
                                    moduleLog(priority, TAG, message, throwable);
                                }
                            });
                }
                return miuiStyleOverlay.prepare(edge);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to prepare HyperOS-style indicator",
                        throwable);
                return false;
            }
        }

        /**
         * The hidden native BackPanelController keeps processing every event for state,
         * commit thresholds, and haptics; only its View pixels are suppressed while the
         * HyperOS-style overlay draws. Restoring is best-effort and re-evaluated on the
         * next ACTION_DOWN, so a failure here can never strand an invisible panel.
         */
        protected boolean applyNativePanelSkinVisibility(Object plugin, boolean hide) {
            try {
                Object panelView = readField(plugin, "mView");
                if (!(panelView instanceof View)) {
                    return false;
                }
                float alpha = hide ? 0.0f : 1.0f;
                View target = (View) panelView;
                if (target.getAlpha() != alpha) {
                    target.setAlpha(alpha);
                    moduleLog(Log.INFO, TAG, "Native BackPanel visuals "
                            + (hide ? "hidden behind HyperOS-style indicator"
                            : "restored"));
                }
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to change native BackPanel visibility"
                        + ", hide=" + hide, throwable);
                return false;
            }
        }

        protected void driveMiuiStyleIndicator(MotionEvent event, int edge) {
            MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
            if (overlay == null) {
                miuiStyleGestureActive = false;
                miuiStyleSwipeStarted = false;
                return;
            }
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // Nothing is drawn yet: Xiaomi's processor waits for the 20px
                        // horizontal intent threshold below before onSwipeStart().
                        miuiStyleSwipeStarted = false;
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float offset = miuiStyleGestureOffset(event, edge);
                        if (!miuiStyleSwipeStarted) {
                            // Mirrors GesturesBackTouchProcessor: horizontal delta must
                            // reach 20px and dominate half the vertical delta first.
                            float verticalDelta =
                                    Math.abs(event.getRawY() - downY);
                            if (offset < MIUI_STYLE_SWIPE_START_PX
                                    || offset < verticalDelta / 2.0f) {
                                break;
                            }
                            miuiStyleSwipeStarted = true;
                            overlay.onGestureStart(downY, edge);
                        }
                        overlay.onGestureProgress(Math.max(0.0f, offset),
                                offset > MIUI_STYLE_ARROW_SHOW_PX
                                        && isNativePanelStateLit());
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        float releaseOffset = Math.max(0.0f,
                                miuiStyleGestureOffset(event, edge));
                        // Snapshot before onGestureEnd() cancels the arrow animator,
                        // mirroring Xiaomi reading isArrowFeedBackDone() pre-stop.
                        boolean handUpEligible = miuiStyleHapticsActive
                                && miuiStyleSwipeStarted;
                        boolean arrowFeedbackDone = overlay.isArrowFeedbackDone();
                        if (miuiStyleSwipeStarted) {
                            overlay.onGestureEnd(releaseOffset);
                        }
                        if (handUpEligible) {
                            maybePerformMiuiHandUpHaptic(event, releaseOffset,
                                    arrowFeedbackDone);
                        }
                        miuiStyleGestureActive = false;
                        miuiStyleSwipeStarted = false;
                        miuiStyleHapticsActive = false;
                        break;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        if (miuiStyleSwipeStarted) {
                            overlay.onGestureEnd(-1.0f);
                        }
                        overlay.markArrowFeedbackDone();
                        miuiStyleGestureActive = false;
                        miuiStyleSwipeStarted = false;
                        miuiStyleHapticsActive = false;
                        break;
                    default:
                        break;
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to drive HyperOS-style indicator",
                        throwable);
                miuiStyleGestureActive = false;
                miuiStyleSwipeStarted = false;
            }
        }

        protected float miuiStyleGestureOffset(MotionEvent event, int edge) {
            return edge == EDGE_LEFT
                    ? event.getRawX() - downX
                    : downX - event.getRawX();
        }

        // The arrow lights exactly when the hidden native panel would commit on release,
        // so the HyperOS visuals stay truthful to the module's AOSP trigger semantics.
        protected boolean isNativePanelStateLit() {
            String state = readNativePanelState();
            return "ACTIVE".equals(state) || "FLUNG".equals(state)
                    || "COMMITTED".equals(state);
        }

        protected boolean prepareMiuiStyleHaptics() {
            if (!isHyperOsHapticsEnabled()) {
                return false;
            }
            try {
                if (miuiHapticHelper == null) {
                    miuiHapticHelper = new MiuiHapticFeedbackHelper(context,
                            (priority, message, throwable) -> {
                                if (throwable == null) {
                                    moduleLog(priority, TAG, message);
                                } else {
                                    moduleLog(priority, TAG, message, throwable);
                                }
                            });
                }
                if (!miuiHapticHelper.isSupported()) {
                    return false;
                }
                miuiHapticHelper.setEnhancedMode(isHyperOsHapticsEnhancedEnabled());
                MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
                if (overlay != null) {
                    overlay.setHapticListener(() -> {
                        MiuiHapticFeedbackHelper helper = miuiHapticHelper;
                        if (helper != null) {
                            helper.performReadyBack();
                        }
                    });
                }
                return true;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to prepare MIUI two-stage haptics",
                        throwable);
                return false;
            }
        }

        /**
         * Mirrors GestureStubView.onSwipeStop: hand-up plays only for a committed
         * release under the native doFeedBack rule — a slow release on non-V2 devices
         * or a ready-back haptic that never completed.
         */
        protected void maybePerformMiuiHandUpHaptic(MotionEvent event,
                                                    float releaseOffset,
                                                    boolean arrowFeedbackDone) {
            MiuiHapticFeedbackHelper helper = miuiHapticHelper;
            MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
            if (helper == null || overlay == null) {
                return;
            }
            try {
                String panelState = readNativePanelState();
                boolean panelCommittedForHaptics = "ENTRY".equals(panelState)
                        || "ACTIVE".equals(panelState)
                        || "INACTIVE".equals(panelState)
                        || "FLUNG".equals(panelState)
                        || "COMMITTED".equals(panelState);
                if (panelCommittedForHaptics) {
                    long gestureDuration =
                            event.getEventTime() - event.getDownTime();
                    float pxPerMs = gestureDuration > 0
                            ? releaseOffset / gestureDuration : 0.0f;
                    // Enhanced mode plays hand-up on every committed release; the
                    // helper's 140ms blocker still prevents doubles near ready-back.
                    boolean doFeedback = helper.isEnhancedMode()
                            || (!helper.isHapticV2() && pxPerMs < 2.0f)
                            || !arrowFeedbackDone;
                    if (doFeedback) {
                        helper.performHandUp();
                    }
                }
                overlay.markArrowFeedbackDone();
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to play MIUI hand-up haptic", throwable);
            }
        }

        /**
         * BackPanel plays its threshold haptics through View.performHapticFeedback on
         * its own view, so isHapticFeedbackEnabled() suppresses exactly that feedback.
         * On failure the two-stage haptics are disabled and the native feedback stays.
         */
        protected void applyNativePanelHapticSuppression(Object plugin,
                                                         boolean suppress) {
            try {
                Object panelView = readField(plugin, "mView");
                if (!(panelView instanceof View)) {
                    if (suppress) {
                        miuiStyleHapticsActive = false;
                    }
                    return;
                }
                View target = (View) panelView;
                boolean enabled = !suppress;
                if (target.isHapticFeedbackEnabled() != enabled) {
                    target.setHapticFeedbackEnabled(enabled);
                    moduleLog(Log.INFO, TAG, "Native BackPanel haptics "
                            + (suppress ? "suppressed for MIUI two-stage haptics"
                            : "restored"));
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Failed to change native BackPanel haptics"
                        + ", suppress=" + suppress, throwable);
                if (suppress) {
                    miuiStyleHapticsActive = false;
                }
            }
        }

        protected void teardownMiuiStyleIndicator() {
            miuiStyleGestureActive = false;
            miuiStyleSwipeStarted = false;
            miuiStyleHapticsActive = false;
            MiuiStyleBackArrowOverlay overlay = miuiStyleOverlay;
            miuiStyleOverlay = null;
            if (overlay != null) {
                overlay.detach();
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Object plugin = findNativeEdgeBackPlugin(edgeBackGestureHandler);
                    if (plugin != null) {
                        applyNativePanelSkinVisibility(plugin, false);
                        applyNativePanelHapticSuppression(plugin, false);
                    }
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG, "Failed to restore native BackPanel visuals",
                            throwable);
                }
            });
        }
    }
}
