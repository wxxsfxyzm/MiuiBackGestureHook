package dev.codex.miuibackgesturehook.hooks.core;

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

public abstract class HookRuntimeCore extends XposedModule {
    protected abstract void invalidateOpenTransitionSnapshot(
            OpenTransitionSnapshot snapshot, String reason);
    protected abstract int readTransitionDebugId(Object infoOrExpose);
    protected abstract boolean isMiuiHomeLauncherOpenType(String typeName);
    protected abstract int resolveTaskInfoActivityType(Object taskInfo);
    protected abstract int resolveTaskInfoWindowingMode(Object taskInfo);
    protected abstract void publishSystemUiInputArbiterState(
            Context context, boolean ready, String reason);
    protected abstract void publishStandardReturnHomeCommit(
            int taskId, int transitionDebugId, Object compositionController);
    protected abstract void clearLegacyBackGuard(String reason);
    protected abstract void clearSystemUiReturnHomeCommitIdentity(
            Object controller, long attemptId, String reason);
    protected abstract LegacyBackAttempt armLegacyBackGuard(
            Object controller, Object runningInfo);
    protected abstract void ensureAospBackAnimations(Object controller, String source);
    protected abstract void onSystemUiInputMonitorAttached(Context context);
    protected abstract void onSystemUiInputMonitorDetached(Context context);
    protected abstract int readMotionEventId(MotionEvent event) throws Exception;
    protected abstract int readMotionEventDisplayId(MotionEvent event) throws Exception;
    protected abstract boolean isCurrentHeadlessNavBarLifecycle(
            Object edgeBackGestureHandler);
    protected abstract Method requireExactDeclaredMethod(
            Class<?> owner, String methodName, String returnTypeName,
            String... parameterTypeNames) throws NoSuchMethodException;

    protected static final String TAG = "MiuiBackGestureHook";
    protected static final String BUILD_MARK =
            "systemui-aosp-back-0.7.0-r48-headless-direct-back";
    protected static final String SYSTEM_UI = "com.android.systemui";
    protected static final String MIUI_HOME = "com.miui.home";
    protected static final String WINDOW_ON_BACK_INVOKED_DISPATCHER =
            "android.window.WindowOnBackInvokedDispatcher";
    protected static final int APPLICATION_PREDICTIVE_BACK_ENABLE_FLAG = 0x8;
    protected static final int ACTIVITY_PREDICTIVE_BACK_ENABLE_FLAG = 0x4;
    protected static final int ACTIVITY_PREDICTIVE_BACK_DISABLE_FLAG = 0x8;
    protected static final int UNIFIED_CONFIG_HOOK_PENDING = 0;
    protected static final int UNIFIED_CONFIG_HOOK_RUNNING = 1;
    protected static final int UNIFIED_CONFIG_HOOK_COMPLETED = 2;

    protected static final String EDGE_BACK_GESTURE_HANDLER =
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler";
    protected static final String MIUI_OVERVIEW_PROXY =
            "com.android.systemui.recents.MiuiOverviewProxy";
    protected static final String MIUI_HOME_GESTURE_STUB =
            "com.miui.home.recents.GestureStubView";
    protected static final String MIUI_HOME_GESTURE_PROCESSOR =
            "com.miui.home.recents.GesturesBackTouchProcessor";
    protected static final String MIUI_HOME_RECENTS_CONTAINER =
            "com.miui.home.recents.views.RecentsContainer";
    protected static final String MIUI_HOME_TASK_VIEW =
            "com.miui.home.recents.views.TaskView";
    protected static final String MIUI_HOME_STATE_NOTIFY_UTILS =
            "com.miui.home.recents.util.StateNotifyUtils";
    protected static final String MIUI_HOME_BACK_GESTURE_BREAK_CONTROLLER =
            "com.miui.home.recents.BackGestureBreakController";
    protected static final String MIUI_HOME_WINDOW_ELEMENT_ANIM_LISTENER =
            "com.miui.home.recents.anim.StateManager$windowElementAnimListener$1";
    protected static final String MIUI_HOME_STATE_MANAGER =
            "com.miui.home.recents.anim.StateManager";
    protected static final String MIUI_HOME_WINDOW_ELEMENT =
            "com.miui.home.recents.anim.WindowElement";
    protected static final String MIUI_HOME_REMOTE_TRANSITION_INFO =
            "com.miui.home.recents.event.RemoteTransitionInfo";
    protected static final String MIUI_HOME_WINDOW_TRANSITION_COMPAT =
            "com.android.systemui.shared.recents.system.WindowTransitionCompat";
    protected static final String MIUI_HOME_WINDOW_TRANSITION_CALLBACK_HELPER =
            "com.android.systemui.shared.recents.utilities.WindowTransitionCallbackHelper";
    protected static final String MIUI_HOME_ANIM_BACKGROUND_THREAD =
            "com.miui.launcher.common.AnimBackgroundThread";
    protected static final String MIUI_HOME_SHELL_TRANSITION_CALLBACK =
            "com.android.systemui.shared.recents.utilities.ShellTransitionCallback";
    protected static final String MIUI_HOME_RECENT_BLUR_PARAMS =
            "com.miui.home.recents.anim.RecentBlurParams";
    protected static final String MIUI_HOME_LAUNCHER_STATE_MANAGER =
            "com.miui.home.launcher.LauncherStateManager";
    protected static final String MIUI_HOME_LAUNCHER_STATE =
            "com.miui.home.launcher.LauncherState";
    protected static final String MIUI_HOME_BASE_LAUNCHER =
            "com.miui.home.launcher.BaseLauncher";
    protected static final String MIUI_HOME_APPLICATION =
            "com.miui.home.launcher.Application";
    protected static final String MIUI_HOME_OVERVIEW_PROXY_IMPL =
            "com.miui.home.recents.OverviewProxyImpl";
    protected static final String MIUI_HOME_REMOTE_ANIMATION_TARGET_COMPAT =
            "com.android.systemui.shared.recents.system.RemoteAnimationTargetCompat";
    protected static final String MIUI_HOME_REMOTE_ANIMATION_TARGET_SET =
            "com.miui.home.recents.util.RemoteAnimationTargetSet";
    protected static final String MIUI_HOME_WINDOW_ANIM_PARAMS =
            "com.miui.home.recents.util.WindowAnimParams";
    protected static final String MIUI_HOME_RECTF_PARAMS =
            "com.miui.home.recents.anim.RectFParams";
    protected static final String MIUI_HOME_RECTF_SPRING_ANIM =
            "com.miui.home.recents.util.RectFSpringAnim";
    protected static final String MIUI_HOME_RECTF_SPRING_ANIM_TYPE =
            MIUI_HOME_RECTF_SPRING_ANIM + "$AnimType";
    protected static final String MIUI_HOME_WINDOW_ANIM_LISTENER =
            "com.miui.home.recents.anim.windowanim.WindowAnimListener";
    protected static final String MIUI_HOME_GESTURE_HOME_CALCULATOR =
            "com.miui.home.recents.GestureHomeCalculator";
    protected static final String MIUI_HOME_ANIM_STATUS_PARAM =
            "com.miui.home.recents.anim.windowanim.sfanim.AnimStatusParam";
    protected static final String MIUI_HOME_LOCAL_WINDOW_ANIM_IMPLEMENTOR =
            "com.miui.home.recents.anim.windowanim.LocalWindowAnimImplementor";
    protected static final String MIUI_HOME_CLIP_ANIMATION_HELPER =
            "com.miui.home.recents.util.ClipAnimationHelper";
    protected static final String MIUI_HOME_SYNC_RT_SURFACE_APPLIER =
            "com.android.systemui.shared.recents.system."
                    + "SyncRtSurfaceTransactionApplierCompat";
    protected static final String MIUI_HOME_TRANSACTION_COMPAT =
            "com.android.systemui.shared.recents.system.TransactionCompat";
    protected static final String MIUI_HOME_TRANSITION_UTIL =
            "com.android.wm.shell.util.TransitionUtil";
    protected static final String MIUI_HOME_SURFACE_PARAMS =
            MIUI_HOME_SYNC_RT_SURFACE_APPLIER + "$SurfaceParams";
    protected static final String MIUI_HOME_SURFACE_PARAMS_ARRAY =
            "[L" + MIUI_HOME_SURFACE_PARAMS + ";";
    protected static final String MIUI_HOME_CORNER_RADII =
            "com.android.systemui.shared.recents.utilities.CornerRadii";
    protected static final String MIUI_HOME_VALUE_CALLBACK =
            "com.miui.home.recents.anim.IValueCallBack";
    protected static final String MIUI_HOME_SHORTCUT_MENU_LAYER_ELEMENT =
            "com.miui.home.recents.anim.ShortcutMenuLayerElement";
    protected static final String MIUI_HOME_SHORTCUT_MENU_LAYER_PARAMS =
            "com.miui.home.recents.anim.ShortcutMenuLayerParams";
    protected static final String MIUI_HOME_BASE_WALLPAPER_ELEMENT =
            "com.miui.home.recents.anim.BaseWallpaperElement";
    protected static final String MIUI_HOME_SYSTEM_WALLPAPER_ELEMENT =
            "com.miui.home.recents.anim.SystemWallpaperElement";
    protected static final String MIUI_HOME_WALLPAPER_PARAMS =
            "com.miui.home.recents.anim.WallpaperParam";
    protected static final String MIUI_HOME_DEVICE_LEVEL_UTILS =
            "com.miui.home.common.utils.DeviceLevelUtils";
    protected static final String MIUI_HOME_WINDOW_CORNER_RADIUS_UTIL =
            "com.miui.home.recents.util.WindowCornerRadiusUtil";
    protected static final String NAVIGATION_BAR =
            "com.android.systemui.navigationbar.views.NavigationBar";
    protected static final String NAV_BAR_HELPER =
            "com.android.systemui.navigationbar.NavBarHelper";
    protected static final String NAVIGATION_BAR_CONTROLLER_IMPL =
            "com.android.systemui.navigationbar.NavigationBarControllerImpl";
    protected static final String NAV_BAR_STATE_UPDATER =
            "com.android.systemui.navigationbar.NavBarHelper$NavbarTaskbarStateUpdater";
    protected static final String STATUS_BAR_APPEARANCE_LAMBDA =
            "com.android.systemui.statusbar.data.repository."
                    + "StatusBarModePerDisplayRepositoryImpl$statusBarAppearance$1";
    protected static final String SYSTEM_UI_DEPENDENCY =
            "com.android.systemui.Dependency";
    protected static final String MIUI_CONFIGS =
            "com.miui.utils.configs.MiuiConfigs";
    protected static final String BACK_ANIMATION_CONTROLLER =
            "com.android.wm.shell.back.BackAnimationController";
    protected static final String BACK_TRANSITION_HANDLER =
            "com.android.wm.shell.back.BackAnimationController$BackTransitionHandler";
    protected static final String DEFAULT_TRANSITION_HANDLER =
            "com.android.wm.shell.transition.DefaultTransitionHandler";
    protected static final String DEFAULT_TRANSITION_IMPL =
            "com.android.wm.shell.common.transition.DefaultTransitionImpl";
    protected static final String BACK_NAVIGATION_CONTROLLER =
            "com.android.server.wm.BackNavigationController";
    protected static final String BACK_ANIMATION_HANDLER =
            "com.android.server.wm.BackNavigationController$AnimationHandler";
    protected static final String BACK_WINDOW_ANIMATION_ADAPTOR =
            "com.android.server.wm.BackNavigationController$AnimationHandler$BackWindowAnimationAdaptor";
    protected static final String SCHEDULE_ANIMATION_BUILDER =
            "com.android.server.wm.BackNavigationController$AnimationHandler$ScheduleAnimationBuilder";
    protected static final String WINDOW_STATE = "com.android.server.wm.WindowState";
    protected static final String DISPLAY_POLICY = "com.android.server.wm.DisplayPolicy";

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
            "dev.codex.miuibackgesturehook.action.SYSTEMUI_INPUT_ARBITER_STATE";
    protected static final String MODULE_MIUI_HOME_INPUT_ARBITER_QUERY =
            "dev.codex.miuibackgesturehook.action.MIUI_HOME_INPUT_ARBITER_QUERY";
    protected static final String EXTRA_INPUT_ARBITER_READY = "input_arbiter_ready";
    protected static final String EXTRA_INPUT_ARBITER_GENERATION =
            "input_arbiter_generation";
    protected static final String EXTRA_INPUT_ACCEPTED = "input_accepted";
    protected static final String EXTRA_INPUT_EVENT_ID = "input_event_id";
    protected static final String EXTRA_INPUT_DOWN_TIME = "input_down_time";
    protected static final String EXTRA_INPUT_DEVICE_ID = "input_device_id";
    protected static final String EXTRA_INPUT_SOURCE = "input_source";
    protected static final String EXTRA_INPUT_DISPLAY_ID = "input_display_id";
    protected static final String EXTRA_INPUT_EDGE = "input_edge";
    protected static final String EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE =
            "launcher_open_break_available";
    protected static final String EXTRA_LAUNCHER_OPEN_BREAK_GENERATION =
            "launcher_open_break_generation";
    protected static final String EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT =
            "launcher_open_break_attempt";
    protected static final String EXTRA_LAUNCHER_EDITING = "launcher_editing";
    protected static final String EXTRA_RETURN_HOME_COMMIT_TASK_ID =
            "return_home_commit_task_id";
    protected static final String EXTRA_RETURN_HOME_COMMIT_DEBUG_ID =
            "return_home_commit_debug_id";
    protected static final String EXTRA_RETURN_HOME_COMMIT_ATTEMPT =
            "return_home_commit_attempt";
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
    protected static final float EDGE_TOUCH_WIDTH_DP = 24.0f;
    protected static final float PILFER_THRESHOLD_DP = 8.0f;
    protected static final float TRIGGER_THRESHOLD_DP = 48.0f;
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
    protected static final long RETURN_HOME_CANCEL_DURATION_MS = 200L;
    protected static final long RETURN_HOME_CANCEL_FINISH_GUARD_MS = 350L;
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
            "android.window.IOnBackInvokedCallback";
    protected static final String REMOTE_ANIMATION_RUNNER_DESCRIPTOR =
            "android.view.IRemoteAnimationRunner";
    protected static final String REMOTE_ANIMATION_FINISHED_DESCRIPTOR =
            "android.view.IRemoteAnimationFinishedCallback";
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

    protected final List<XposedInterface.HookHandle> hookHandles = new ArrayList<>();
    protected final ConcurrentHashMap<Object, OpenTransitionSnapshot> runningOpenTransitions =
            new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<Pair<Class<?>, String>, Field> reflectedFields =
            new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<Pair<Class<?>, String>, Method> reflectedAnyMethods =
            new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<Pair<Class<?>, String>, Method> reflectedExactMethods =
            new ConcurrentHashMap<>();
    protected final Set<Pair<Class<?>, String>> missingReflectedMembers =
            ConcurrentHashMap.newKeySet();
    protected final Object legacyBackGuardLock = new Object();
    protected final AtomicLong legacyBackAttemptIds = new AtomicLong();
    protected final AtomicLong openSnapshotGeneration = new AtomicLong();
    protected final AtomicLong headlessNavBarLifecycleGeneration = new AtomicLong();
    protected final AtomicLong launcherOpenBreakAttemptIds = new AtomicLong();
    protected final AtomicInteger launcherOpenBreakCommandsInFlight = new AtomicInteger();
    protected final AtomicLong miuiHomeOpenBreakGenerationIds =
            new AtomicLong(SystemClock.elapsedRealtimeNanos());
    protected final AtomicLong miuiHomeOpenBreakCallbackEpoch = new AtomicLong();
    protected final AtomicInteger systemUiInputArbiterMonitorCount = new AtomicInteger();
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
    protected volatile boolean miuiLauncherEditing;
    protected volatile boolean miuiHomeEditingStatePublished;
    protected volatile boolean miuiLauncherOpenBreakAvailable;
    protected volatile long miuiLauncherOpenBreakGeneration;
    protected volatile long miuiOverviewDismissPendingUntilUptime;
    protected Context miuiOverviewReceiverContext;
    protected BroadcastReceiver miuiOverviewReceiver;
    protected volatile Object miuiHomeOpenBreakController;
    protected volatile boolean miuiHomeOpenBreakCommandPending;
    protected volatile long miuiHomeOpenBreakGeneration;
    protected volatile Object miuiHomeOpenBreakAnimationIdentity;
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
    protected String processName;
    protected boolean nativePluginDiagnosticsLogged;
    protected boolean headlessSysUiStateLogged;
    protected volatile Field defaultTransitionAnimationsField;
    protected volatile Field defaultTransitionAnimationSizeField;
    protected volatile Field defaultTransitionAnimExecutorField;
    protected volatile Method transitionInfoGetTypeMethod;
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

    public static final class ReturnHomeNativeGeometrySnapshot {
        public final long generation;
        public final Object animationIdentity;
        public final long frameTraceId;
        public final int sourceKind;
        public final float[] matrixValues;
        public final Rect windowCrop;
        public final float[] surfaceCornerRadii;

        public ReturnHomeNativeGeometrySnapshot(
                long generation, Object animationIdentity,
                float[] matrixValues, Rect windowCrop,
                float[] surfaceCornerRadii, long frameTraceId,
                int sourceKind) {
            this.generation = generation;
            this.animationIdentity = animationIdentity;
            this.frameTraceId = frameTraceId;
            this.sourceKind = sourceKind;
            this.matrixValues = matrixValues.clone();
            this.windowCrop = new Rect(windowCrop);
            this.surfaceCornerRadii = surfaceCornerRadii.clone();
        }

        public float[] copyMatrixValues() {
            return matrixValues.clone();
        }

        public Rect copyWindowCrop() {
            return new Rect(windowCrop);
        }

        public float[] copySurfaceCornerRadii() {
            return surfaceCornerRadii.clone();
        }
    }

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
        public final AtomicInteger state = new AtomicInteger(OPEN_SNAPSHOT_PENDING);
        public volatile AnimatorListenerAdapter listener;

        public OpenTransitionSnapshot(Object token, Object transitionInfo, Animator[] animators,
                               int originalAnimatorCount, Executor animExecutor,
                               long generation) {
            this.token = token;
            this.transitionInfo = transitionInfo;
            this.originalAnimatorCount = originalAnimatorCount;
            this.animators = animators;
            this.animExecutor = animExecutor;
            this.generation = generation;
        }
    }

    public static final class OpenTransitionInvalidationListener
            extends AnimatorListenerAdapter {
        public final WeakReference<HookRuntimeCore> owner;
        public final OpenTransitionSnapshot snapshot;

        public OpenTransitionInvalidationListener(HookRuntimeCore owner,
                                           OpenTransitionSnapshot snapshot) {
            this.owner = new WeakReference<>(owner);
            this.snapshot = snapshot;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            HookRuntimeCore hook = owner.get();
            if (hook != null) {
                hook.invalidateOpenTransitionSnapshot(snapshot, "cancel");
            } else {
                animation.removeListener(this);
            }
        }

        @Override
        public void onAnimationEnd(Animator animation, boolean isReverse) {
            HookRuntimeCore hook = owner.get();
            if (hook != null) {
                hook.invalidateOpenTransitionSnapshot(snapshot,
                        isReverse ? "reverseEnd" : "end");
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

    public static final class MiuiHomeLocalHandoffToken {
        public final long generation;
        public final Object session;
        public final Object windowElement;
        public final Object windowAnimContext;
        public final Object status;

        public MiuiHomeLocalHandoffToken(long generation, Object session,
                                  Object windowElement, Object windowAnimContext,
                                  Object status) {
            this.generation = generation;
            this.session = session;
            this.windowElement = windowElement;
            this.windowAnimContext = windowAnimContext;
            this.status = status;
        }
    }

    public static final class LauncherOpenMainTask {
        public final int taskId;
        public final int displayId;
        public final ComponentName component;
        public final Rect bounds;

        public LauncherOpenMainTask(int taskId, int displayId,
                             ComponentName component, Rect bounds) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.component = component;
            this.bounds = bounds;
        }
    }

    public static final class MiuiHomeLauncherOpenSnapshot {
        public final long generation;
        public final long nativeGeneration;
        public final long callbackEpoch;
        public final Object stateManager;
        public final Object windowElement;
        public final Object animationIdentity;
        public final String animationType;
        public final Object windowTransitionCompat;
        public final Object helper;
        public final Object mainTransitionToken;
        public final Object mainTransitionInfo;
        public final int mainTransitionDebugId;
        public final LauncherOpenMainTask mainTask;

        public MiuiHomeLauncherOpenSnapshot(
                long generation, long nativeGeneration, long callbackEpoch,
                Object stateManager,
                Object windowElement, Object animationIdentity,
                String animationType, Object windowTransitionCompat,
                Object helper, Object mainTransitionToken,
                Object mainTransitionInfo, int mainTransitionDebugId,
                LauncherOpenMainTask mainTask) {
            this.generation = generation;
            this.nativeGeneration = nativeGeneration;
            this.callbackEpoch = callbackEpoch;
            this.stateManager = stateManager;
            this.windowElement = windowElement;
            this.animationIdentity = animationIdentity;
            this.animationType = animationType;
            this.windowTransitionCompat = windowTransitionCompat;
            this.helper = helper;
            this.mainTransitionToken = mainTransitionToken;
            this.mainTransitionInfo = mainTransitionInfo;
            this.mainTransitionDebugId = mainTransitionDebugId;
            this.mainTask = mainTask;
        }
    }

    public static final class PermissionActivityTransition {
        public final Object container;
        public final Object parent;
        public final SurfaceControl leash;
        public final ComponentName component;
        public final Rect startBounds;
        public final Rect endBounds;
        public final int debugId;
        public final int backgroundColor;
        public final int startDisplayId;
        public final int endDisplayId;

        public PermissionActivityTransition(
                Object container, Object parent, SurfaceControl leash,
                ComponentName component,
                Rect startBounds, Rect endBounds, int debugId,
                int backgroundColor, int startDisplayId, int endDisplayId) {
            this.container = container;
            this.parent = parent;
            this.leash = leash;
            this.component = component;
            this.startBounds = startBounds;
            this.endBounds = endBounds;
            this.debugId = debugId;
            this.backgroundColor = backgroundColor;
            this.startDisplayId = startDisplayId;
            this.endDisplayId = endDisplayId;
        }
    }

    public static final class MiuiHomePermissionMergeToken {
        public final MiuiHomeLauncherOpenSnapshot launcherOpen;
        public final PermissionActivityTransition permissionOpen;
        public final AtomicInteger consumed = new AtomicInteger();

        public MiuiHomePermissionMergeToken(
                MiuiHomeLauncherOpenSnapshot launcherOpen,
                PermissionActivityTransition permissionOpen) {
            this.launcherOpen = launcherOpen;
            this.permissionOpen = permissionOpen;
        }
    }

    public static final class ReturnHomeComposition {
        public final Object appsIdentity;
        public final Object closingTarget;
        public final Object openingTarget;
        public final SurfaceControl closingLeash;
        public final SurfaceControl openingLeash;
        public final int closingTaskId;
        public final int openingTaskId;
        public final int displayId;

        public ReturnHomeComposition(Object appsIdentity, Object closingTarget,
                              Object openingTarget, SurfaceControl closingLeash,
                              SurfaceControl openingLeash, int closingTaskId,
                              int openingTaskId, int displayId) {
            this.appsIdentity = appsIdentity;
            this.closingTarget = closingTarget;
            this.openingTarget = openingTarget;
            this.closingLeash = closingLeash;
            this.openingLeash = openingLeash;
            this.closingTaskId = closingTaskId;
            this.openingTaskId = openingTaskId;
            this.displayId = displayId;
        }
    }

    public static final class ReturnHomeCommitComposition {
        public final Object handler;
        public final Object controller;
        public final ReturnHomeComposition composition;
        public final SurfaceControl changeLeash;
        public final Object transitionToken;
        public final Object transitionInfo;
        public final Object startTransaction;
        public final Object finishTransaction;
        public final Object mergeTarget;
        public final Object finishCallback;
        public final Object previousAnimationFinishCallback;
        public final int transitionType;
        public final AtomicInteger acceptedBoundaryComposition =
                new AtomicInteger();

        public ReturnHomeCommitComposition(Object handler, Object controller,
                                    ReturnHomeComposition composition,
                                    SurfaceControl changeLeash,
                                    Object transitionToken,
                                    Object transitionInfo,
                                    Object startTransaction,
                                    Object finishTransaction,
                                    Object mergeTarget,
                                    Object finishCallback,
                                    Object previousAnimationFinishCallback,
                                    int transitionType) {
            this.handler = handler;
            this.controller = controller;
            this.composition = composition;
            this.changeLeash = changeLeash;
            this.transitionToken = transitionToken;
            this.transitionInfo = transitionInfo;
            this.startTransaction = startTransaction;
            this.finishTransaction = finishTransaction;
            this.mergeTarget = mergeTarget;
            this.finishCallback = finishCallback;
            this.previousAnimationFinishCallback =
                    previousAnimationFinishCallback;
            this.transitionType = transitionType;
        }
    }

    public static final class StandardReturnHomeCommitSignal {
        public final long attempt;
        public final long arbiterGeneration;
        public final int taskId;
        public final int transitionDebugId;
        public final int eventId;
        public final long downTime;
        public final int deviceId;
        public final int source;
        public final int displayId;
        public final int edge;
        public final long launcherSessionGeneration;

        public StandardReturnHomeCommitSignal(long attempt, long arbiterGeneration,
                                       int taskId, int transitionDebugId,
                                       int eventId, long downTime,
                                       int deviceId, int source,
                                       int displayId, int edge) {
            this(attempt, arbiterGeneration, taskId, transitionDebugId,
                    eventId, downTime, deviceId, source, displayId, edge,
                    0L);
        }

        public StandardReturnHomeCommitSignal(
                long attempt, long arbiterGeneration,
                int taskId, int transitionDebugId,
                int eventId, long downTime,
                int deviceId, int source, int displayId, int edge,
                long launcherSessionGeneration) {
            this.attempt = attempt;
            this.arbiterGeneration = arbiterGeneration;
            this.taskId = taskId;
            this.transitionDebugId = transitionDebugId;
            this.eventId = eventId;
            this.downTime = downTime;
            this.deviceId = deviceId;
            this.source = source;
            this.displayId = displayId;
            this.edge = edge;
            this.launcherSessionGeneration = launcherSessionGeneration;
        }

        public StandardReturnHomeCommitSignal bindToLauncherSession(
                long sessionGeneration) {
            return new StandardReturnHomeCommitSignal(
                    attempt, arbiterGeneration, taskId,
                    transitionDebugId, eventId, downTime,
                    deviceId, source, displayId, edge,
                    sessionGeneration);
        }

        public boolean matchesInput(MiuiHomeAcceptedInputToken token) {
            return token != null
                    && token.generation == arbiterGeneration
                    && token.eventId == eventId
                    && token.downTime == downTime
                    && token.deviceId == deviceId
                    && token.source == source
                    && token.displayId == displayId
                    && token.edge == edge;
        }
    }

    public static final class SystemUiReturnHomeCommitIdentity {
        public final Object controller;
        public final long shellSessionId;
        public final int taskId;
        public final MiuiHomeAcceptedInputToken input;

        public SystemUiReturnHomeCommitIdentity(
                Object controller, long shellSessionId, int taskId,
                MiuiHomeAcceptedInputToken input) {
            this.controller = controller;
            this.shellSessionId = shellSessionId;
            this.taskId = taskId;
            this.input = input;
        }
    }

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

        public int touchWidth(int edge) {
            return edge == EDGE_LEFT ? leftTouchWidth : rightTouchWidth;
        }

        public static int combineTouchWidth(int sensitivity, int inset) {
            long width = (long) sensitivity + (long) inset;
            return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE, width));
        }
    }

    public static final class ObjectIdentityKey {
        public final Object object;

        public ObjectIdentityKey(Object object) {
            this.object = object;
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof ObjectIdentityKey
                    && object == ((ObjectIdentityKey) candidate).object;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(object);
        }
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "Module loaded, build=" + BUILD_MARK
                + ", process=" + processName
                + ", systemServer=" + param.isSystemServer());
    }

    public static final class ReturnHomeFinishTransferCandidate {
        public final Object handler;
        public final Object controller;
        public final Thread ownerThread;
        public final Object transitions;
        public final Object remoteTransitionHandler;
        public final ReturnHomeComposition composition;
        public final Object transitionToken;
        public final Object transitionInfo;
        public final Object mergeTarget;
        public final SurfaceControl.Transaction startTransaction;
        public final Object preparedOpenInfo;
        public final SurfaceControl.Transaction preparedFinishTransaction;
        public final Object preparedFinishCallback;
        public final Object elementChange;
        public final Object appChange;
        public final SurfaceControl homeLeash;
        public final SurfaceControl elementLeash;
        public final SurfaceControl appLeash;
        public final Rect fullscreenBounds;
        public final Rect elementEndBounds;
        public final int transitionType;
        public final int appFlags;
        public final int elementStartDisplayId;
        public final int elementEndDisplayId;
        public final int transitionDebugId;
        public final int preparedDebugId;
        public final AtomicInteger transferAttempted = new AtomicInteger();

        public ReturnHomeFinishTransferCandidate(
                Object handler, Object controller,
                Thread ownerThread,
                Object transitions, Object remoteTransitionHandler,
                ReturnHomeComposition composition,
                Object transitionToken, Object transitionInfo,
                Object mergeTarget,
                SurfaceControl.Transaction startTransaction,
                Object preparedOpenInfo,
                SurfaceControl.Transaction preparedFinishTransaction,
                Object preparedFinishCallback, Object elementChange,
                Object appChange,
                SurfaceControl homeLeash, SurfaceControl elementLeash,
                SurfaceControl appLeash, Rect fullscreenBounds,
                Rect elementEndBounds, int transitionType, int appFlags,
                int elementStartDisplayId,
                int elementEndDisplayId, int transitionDebugId,
                int preparedDebugId) {
            this.handler = handler;
            this.controller = controller;
            this.ownerThread = ownerThread;
            this.transitions = transitions;
            this.remoteTransitionHandler = remoteTransitionHandler;
            this.composition = composition;
            this.transitionToken = transitionToken;
            this.transitionInfo = transitionInfo;
            this.mergeTarget = mergeTarget;
            this.startTransaction = startTransaction;
            this.preparedOpenInfo = preparedOpenInfo;
            this.preparedFinishTransaction = preparedFinishTransaction;
            this.preparedFinishCallback = preparedFinishCallback;
            this.elementChange = elementChange;
            this.appChange = appChange;
            this.homeLeash = homeLeash;
            this.elementLeash = elementLeash;
            this.appLeash = appLeash;
            this.fullscreenBounds = new Rect(fullscreenBounds);
            this.elementEndBounds = new Rect(elementEndBounds);
            this.transitionType = transitionType;
            this.appFlags = appFlags;
            this.elementStartDisplayId = elementStartDisplayId;
            this.elementEndDisplayId = elementEndDisplayId;
            this.transitionDebugId = transitionDebugId;
            this.preparedDebugId = preparedDebugId;
        }
    }

    public static final class MiuiHomeAcceptedInputToken {
        public final int eventId;
        public final long downTime;
        public final int deviceId;
        public final int source;
        public final int displayId;
        public final int edge;
        public final long generation;
        public final long receivedUptime;

        public MiuiHomeAcceptedInputToken(int eventId, long downTime, int deviceId,
                                   int source, int displayId, int edge,
                                   long generation) {
            this.eventId = eventId;
            this.downTime = downTime;
            this.deviceId = deviceId;
            this.source = source;
            this.displayId = displayId;
            this.edge = edge;
            this.generation = generation;
            this.receivedUptime = SystemClock.uptimeMillis();
        }

        public boolean isExpired() {
            long now = SystemClock.uptimeMillis();
            long streamAge = now - downTime;
            return now - receivedUptime > INPUT_ACCEPTED_TOKEN_TIMEOUT_MS
                    || streamAge < 0L
                    || streamAge > INPUT_ACCEPTED_TOKEN_TIMEOUT_MS;
        }
    }

    protected boolean readWindowFlag(String methodName, ClassLoader preferredLoader,
                                   boolean defaultValue) {
        String[] classNames = new String[]{
                "com.android.window.flags.Flags",
                "com.android.internal.hidden_from_bootclasspath.com.android.window.flags.Flags",
                "android.window.flags.Flags"
        };
        for (String className : classNames) {
            try {
                Class<?> flagsClass = Class.forName(className, false, preferredLoader);
                Method method = flagsClass.getDeclaredMethod(methodName);
                method.setAccessible(true);
                Object result = method.invoke(null);
                if (result instanceof Boolean) {
                    log(Log.INFO, TAG, "Read " + methodName + " from "
                            + className + ": " + result);
                    return ((Boolean) result).booleanValue();
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Flag lookup failed for " + className
                        + ": " + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            }
        }
        log(Log.WARN, TAG, "Unable to read " + methodName
                + "; defaulting to " + defaultValue);
        return defaultValue;
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

    protected Object createNativeEdgeBackPluginFromFactory(Object edgeBackGestureHandler,
                                                         Context context) throws Exception {
        Object factory = readField(
                edgeBackGestureHandler, "mBackPanelControllerFactory");
        Handler handler = (Handler) readField(
                readField(edgeBackGestureHandler, "mUiThreadContext"), "handler");
        Object plugin = invokeMethod(factory, "create",
                new Class<?>[]{Context.class, Handler.class},
                new Object[]{context, handler});
        invokeAnyMethod(plugin, "init", new Object[0]);
        return plugin;
    }

    protected void logNativePluginDiagnostics(Object edgeBackGestureHandler) {
        if (nativePluginDiagnosticsLogged) {
            return;
        }
        nativePluginDiagnosticsLogged = true;
        log(Log.WARN, TAG, "Native BackPanelController plugin unavailable"
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
            changed |= ensureRegistryRunner(definitions, TYPE_CROSS_ACTIVITY,
                    defaultCrossActivity, "crossActivity");
            changed |= ensureRegistryRunner(definitions, TYPE_CROSS_TASK,
                    crossTask, "crossTask");
            if (changed) {
                invokeAnyMethod(registry, "updateSupportedAnimators", new Object[0]);
                log(Log.INFO, TAG, "Restored AOSP registry definitions from " + source);
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to restore AOSP registry definitions from " + source,
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
            log(Log.INFO, TAG, "Added " + label + " runner to registry, type=" + type
                    + ", runner=" + shortObject(runner));
            return true;
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to add " + label + " runner, type=" + type,
                    throwable);
            return false;
        }
    }

    protected void recordHookHandle(XposedInterface.HookHandle hookHandle) {
        hookHandles.add(hookHandle);
    }

    protected Object readField(Object target, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findCachedField(target.getClass(), fieldName);
        return field.get(target);
    }

    protected Object readStaticField(Class<?> ownerClass, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findCachedField(ownerClass, fieldName);
        return field.get(null);
    }

    protected Object readFieldOrNull(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return readField(target, fieldName);
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }

    protected float readFloatFieldOrDefault(Object target, String fieldName,
                                          float defaultValue) {
        try {
            Object value = readField(target, fieldName);
            if (value instanceof Number) {
                return ((Number) value).floatValue();
            }
        } catch (Throwable ignored) {
        }
        return defaultValue;
    }

    protected int readIntFieldOrDefault(Object target, String fieldName,
                                      int defaultValue) {
        try {
            Object value = readField(target, fieldName);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return defaultValue;
    }

    protected boolean surfacesAreSame(SurfaceControl first, SurfaceControl second)
            throws Exception {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        Object same = invokeAnyMethod(
                first, "isSameSurface", new Object[]{second});
        if (!(same instanceof Boolean)) {
            throw new IllegalStateException("isSameSurface returned "
                    + shortObject(same));
        }
        return ((Boolean) same).booleanValue();
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

    protected void writeField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findCachedField(target.getClass(), fieldName);
        field.set(target, value);
    }

    protected Field findCachedField(Class<?> ownerClass, String fieldName)
            throws NoSuchFieldException {
        Pair<Class<?>, String> key = Pair.create(
                ownerClass, "field:" + fieldName);
        Field cached = reflectedFields.get(key);
        if (cached != null) {
            return cached;
        }
        if (missingReflectedMembers.contains(key)) {
            throw new NoSuchFieldException(ownerClass.getName() + "." + fieldName);
        }
        Class<?> current = ownerClass;
        while (current != null) {
            try {
                Field resolved = current.getDeclaredField(fieldName);
                resolved.setAccessible(true);
                Field raced = reflectedFields.putIfAbsent(key, resolved);
                return raced == null ? resolved : raced;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        missingReflectedMembers.add(key);
        throw new NoSuchFieldException(ownerClass.getName() + "." + fieldName);
    }

    protected static String shortObject(Object object) {
        if (object == null) {
            return "null";
        }
        try {
            String value = String.valueOf(object);
            if (value.length() > 180) {
                value = value.substring(0, 180) + "...";
            }
            return object.getClass().getName() + "{" + value + "}";
        } catch (Throwable throwable) {
            return object.getClass().getName() + "{toStringError="
                    + throwable.getClass().getSimpleName() + "}";
        }
    }

    protected void logBackNavigationInfo(Object info) {
        if (info == null) {
            log(Log.INFO, TAG, "BackNavigationInfo=null");
            return;
        }
        try {
            BackNavigationInfo navigationInfo = (BackNavigationInfo) info;
            int type = navigationInfo.getType();
            boolean prepare = navigationInfo.isPrepareRemoteAnimation();
            Object callback = navigationInfo.getOnBackInvokedCallback();
            log(Log.INFO, TAG, "BackNavigationInfo detail: type=" + type
                    + ", prepareRemoteAnimation=" + prepare
                    + ", callback=" + shortObject(callback));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect BackNavigationInfo", throwable);
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
                log(Log.INFO, TAG,
                        "Disabled app-generated progress for TYPE_CALLBACK; SystemUI will dispatch progress");
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to force SystemUI callback progress", throwable);
        }
    }

    protected Object invokeMethod(Object target, String methodName,
                                Class<?>[] parameterTypes, Object[] args) throws Exception {
        Class<?> owner = target.getClass();
        Pair<Class<?>, String> key = Pair.create(owner,
                "exact:" + methodName + Arrays.toString(parameterTypes));
        Method method = reflectedExactMethods.get(key);
        if (method == null) {
            if (missingReflectedMembers.contains(key)) {
                throw new NoSuchMethodException(owner.getName() + "." + methodName);
            }
            try {
                Method resolved = owner.getMethod(methodName, parameterTypes);
                resolved.setAccessible(true);
                Method raced = reflectedExactMethods.putIfAbsent(key, resolved);
                method = raced == null ? resolved : raced;
            } catch (NoSuchMethodException exception) {
                missingReflectedMembers.add(key);
                throw exception;
            }
        }
        return method.invoke(target, args);
    }

    protected Object invokeAnyMethod(Object target, String methodName, Object[] args)
            throws Exception {
        Class<?> owner = target.getClass();
        Pair<Class<?>, String> key = Pair.create(owner,
                "any:" + methodName + "/" + args.length);
        Method best = reflectedAnyMethods.get(key);
        if (best == null && missingReflectedMembers.contains(key)) {
            throw new NoSuchMethodException(owner.getName() + "." + methodName);
        }
        if (best == null) {
            Method resolved = findAnyMethod(owner, methodName, args.length);
            if (resolved != null) {
                resolved.setAccessible(true);
                Method raced = reflectedAnyMethods.putIfAbsent(key, resolved);
                best = raced == null ? resolved : raced;
            }
        }
        if (best == null) {
            missingReflectedMembers.add(key);
            throw new NoSuchMethodException(owner.getName() + "." + methodName);
        }
        return best.invoke(target, args);
    }

    protected String readNativeAnimationType(Object windowElement) throws Exception {
        return enumName(invokeAnyMethod(
                windowElement, "getCurrentAnimType", new Object[0]));
    }

    protected static String enumName(Object value) {
        return value instanceof Enum<?>
                ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    protected static Method findAnyMethod(Class<?> type, String methodName, int argCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodName.equals(method.getName())
                        && method.getParameterCount() == argCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            if (methodName.equals(method.getName())
                    && method.getParameterCount() == argCount) {
                return method;
            }
        }
        return null;
    }
}
