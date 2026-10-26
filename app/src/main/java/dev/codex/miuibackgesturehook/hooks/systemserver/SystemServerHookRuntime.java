package dev.codex.miuibackgesturehook.hooks.systemserver;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.WindowOnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeHookRuntime;
import io.github.libxposed.api.XposedInterface;

@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public abstract class SystemServerHookRuntime extends MiuiHomeHookRuntime {

    protected static final String SERVER_BAR_LISTENER_CREATE = "server_bar_follow_listener_create";
    protected static final String SERVER_BAR_LISTENER_UPDATE = "server_bar_follow_listener_update";
    protected static final String SERVER_BAR_START_CREATE = "server_bar_follow_start_create";
    protected static final String SERVER_BAR_START_RUN = "server_bar_follow_start_run";
    protected static final String SERVER_BAR_STATE_CREATE = "server_bar_follow_state_create";
    protected static final String SERVER_BAR_STATE_RUN = "server_bar_follow_state_run";
    protected static final String SERVER_BAR_STATE_PREFIX = "server_bar_follow_state_call_";
    protected static final String SERVER_BAR_CALLBACK_CREATE_PREFIX = "server_bar_follow_callback_create_";
    protected static final String SERVER_BAR_CALLBACK_RUN_PREFIX = "server_bar_follow_callback_run_";
    protected static final String SERVER_BAR_MUTATION_PREFIX = "server_bar_follow_mutation_";
    private final ThreadLocal<Object> rejectedServerBarState = new ThreadLocal<>();
    private final ServerBarFollowLifecycle serverBarFollowLifecycle = new ServerBarFollowLifecycle();
    private boolean serverBarLifetimeHooksReady;
    private volatile ServerBarWindowBridge serverBarWindowBridge;
    private final AtomicInteger serverNavigationPolicyEpoch = new AtomicInteger();
    protected static final String SERVER_BAR_BRIDGE_READY = "server_bar_window_bridge_ready";
    protected static final String SERVER_BAR_FEATURE = "server_bar_owned_multi_status";
    protected static final String SERVER_BAR_APPEARANCE_OWNER = "server_bar_appearance_source_owner";

    protected static final int SERVER_CHANGE_INFO_BACK_TOP = 128;
    protected static final int SERVER_CHANGE_INFO_BACK_BELOW = 256;
    protected static final int SERVER_CHANGE_INFO_CHANGE_YES_ANIMATION = 16;
    protected static final int SERVER_ANIMATION_TYPE_PREDICTIVE_BACK = 256;
    protected static final int SERVER_TRANSITION_INFO_BACK_TOP = 0x08000000;
    protected static final int SERVER_FREEFORM_PREPARED_CLOSING_FLAGS =
            SERVER_TRANSITION_INFO_BACK_TOP | FLAG_BACK_GESTURE_ANIMATED | FLAG_FILLS_TASK;
    protected static final int SERVER_FREEFORM_PREPARED_OPENING_FLAGS =
            FLAG_BACK_GESTURE_ANIMATED | FLAG_FILLS_TASK | FLAG_IS_OCCLUDED;
    protected volatile Field serverTransitionChangeInfoFlagsField;
    private static final String GOOGLE_CONTEXTUAL_SEARCH_PACKAGE =
            "com.google.android.googlequicksearchbox";
    private static final String MIUI_SECURITY_CENTER_PACKAGE =
            "com.miui.securitycenter";
    private static final String MIUI_SECURITY_GAME_SIDEBAR_HANDLE_TITLE =
            "FloatAssistantView";
    private static final String MIUI_SECURITY_VIDEO_SIDEBAR_HANDLE_TITLE =
            "VtbAssistantView";
    private static final int TYPE_DISPLAY_OVERLAY = 2026;
    private static final long SIDEBAR_GESTURE_MAX_AGE_MS = 2000L;
    private final ThreadLocal<Boolean> contextualSearchBridgeInvocation = new ThreadLocal<>();
    private final FullscreenSideSwipeScope fullscreenSideSwipeScope = new FullscreenSideSwipeScope();
    protected final AtomicInteger contextualSearchBridgeCallsInFlight = new AtomicInteger();
    private volatile PackageManager contextualSearchPackageManager;
    private volatile int contextualSearchPackageResourceId;
    private volatile SystemServerPlatformImpl systemServerPlatformImpl;
    private volatile ClassLoader systemServerPlatformClassLoader;
    protected static final String SERVER_SIDE_TRIGGER_EXCLUSION = "server_side_trigger_exclusion";
    private static final String DISPLAY_CONTENT = "com.android.server.wm.DisplayContent";
    private static final long SIDE_TRIGGER_PREFERENCE_RETRY_MS = 5000L;
    private final Object sideTriggerExclusionPreferenceLock = new Object();
    private volatile SharedPreferences sideTriggerExclusionPreferences;
    private volatile int sideTriggerHeightPercent =
            PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT;
    private volatile int sideTriggerPositionPercent =
            PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT;
    private volatile long sideTriggerExclusionPreferenceRetryUptime;
    private volatile boolean sideTriggerExclusionPreferenceFailureLogged;
    private volatile boolean sideTriggerExclusionFailureLogged;
    private volatile Method sideTriggerExclusionUpdateMethod;
    private volatile Field sideTriggerFrameLeftField;
    private volatile Field sideTriggerFrameRightField;
    private volatile Field sideTriggerDisplayIdField;
    private final Map<Integer, int[]> sideTriggerPublishedStates = new ConcurrentHashMap<>();
    private volatile WeakReference<Object> sideTriggerExclusionWindowManager =
            new WeakReference<>(null);
    private final SharedPreferences.OnSharedPreferenceChangeListener
            sideTriggerExclusionPreferenceListener = (preferences, key) -> {
        if (key != null
                && !PredictiveBackPreferences.KEY_GESTURE_TRIGGER_HEIGHT_PERCENT.equals(key)
                && !PredictiveBackPreferences.KEY_GESTURE_TRIGGER_POSITION_PERCENT.equals(key)) {
            return;
        }
        try {
            readSideTriggerExclusionPreferences(preferences);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to read side trigger preferences", throwable);
            return;
        }
        moduleLog(Log.INFO, TAG, "Side trigger preference changed"
                + ", key=" + key
                + ", height=" + sideTriggerHeightPercent
                + "%, position=" + sideTriggerPositionPercent + "%");
        requestSideTriggerExclusionRefresh("preference:" + key);
    };

    protected void installSystemServerHooks(ClassLoader classLoader) {
        try {
            ClassLoader serverClassLoader = findSystemServerClassLoader(classLoader);
            if (serverClassLoader == null) {
                moduleLog(Log.ERROR, TAG, "Unable to find system_server classloader for "
                        + BACK_NAVIGATION_CONTROLLER);
                return;
            }
            try {
                selectSystemServerPlatformImpl(serverClassLoader);
            } catch (Throwable throwable) {
                moduleLog(Log.ERROR, TAG,
                        "Failed to select system_server platform implementation;"
                                + " version-specific predictive-back hooks stay disabled",
                        throwable);
            }
            hookContextualSearchCompatibility(serverClassLoader, Collections.emptySet());
            hookServerBarLifetime(serverClassLoader, Collections.emptySet());
            hookBackNavigationDoneCleanup(serverClassLoader);
            hookPredictiveBackOptInMetadata(serverClassLoader);
            hookSecuritySidebarTransientBars(serverClassLoader);
            hookBackWindowStartAnimation(serverClassLoader);
            hookA17OpeningSurfaceVisibility(serverClassLoader);
            if (systemServerPlatformImpl != null) {
                hookFreeformCrossActivityPrepareRole(serverClassLoader);
                hookScheduleAnimationPrepareTransition(serverClassLoader);
            }
            hookReturnHomeTouchOcclusion(serverClassLoader);
            hookSideTriggerExclusion(serverClassLoader, Collections.emptySet());
            moduleLog(Log.INFO, TAG, "Installed system_server back navigation hooks, build="
                    + BUILD_MARK
                    + ", platform=" + (systemServerPlatformImpl == null
                    ? "unresolved" : systemServerPlatformImpl.name())
                    + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to install system_server hooks", throwable);
        }
    }

    protected void hookServerBarLifetime(ClassLoader loader, Set<String> present) {
        if (Build.VERSION.SDK_INT != 37) return;
        try {
            Class<?> controller = Class.forName("com.android.server.wm.MiuiStatusBarController", false, loader);
            Method feature = Class.forName("com.android.server.wm.BarFollowAnimationUtils", false, loader)
                    .getDeclaredMethod("MULTIS_STATUSBAR_ENABLED");
            if (feature.getReturnType() != boolean.class || !java.lang.reflect.Modifier.isStatic(feature.getModifiers()))
                throw new NoSuchMethodException("Multi-status feature shape changed");
            if (!present.contains(SERVER_BAR_FEATURE)) recordHookHandle(hook(feature)
                    .setId(SERVER_BAR_FEATURE).intercept(this::serverBarOwnedFeature));
            List<ServerBarCallbackProfile.Entry> callbacks = ServerBarCallbackProfile.resolve(loader);
            String[] writes = {"initFolme", "stopFolmeAnimate", "diableStatusBarVisibilityPolicy",
                    "enableStatusBarVisibility", "bindAssociatedTask", "updateHomeBarParent",
                    "reparentAppBarToBirthParent", "reparentHomeBarToOrigParent", "startStatusBarAnimation",
                    "updateBarAlpha", "updateAppBarAlphaUnCheck", "reparentStausBarToAppTask",
                    "onSystemWindowAdded", "onSystemWindowRemoved", "checkBarAlpha", "updateStatusBarAlpha",
                    "updateAppBarAlpha", "updateHomeBarAlpha", "animateStatusBarTo", "resetStatusBarState",
                    "setStatusBarUpdateState", "resetToInitState", "setAppBarContainer", "updateStatusBar"};
            java.util.ArrayList<Method> nativeWrites = new java.util.ArrayList<>();
            for (String name : writes) {
                Method resolved = null;
                for (Method method : controller.getDeclaredMethods()) if (method.getName().equals(name)) {
                    if (resolved != null || method.getReturnType() != void.class
                            || java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                        throw new NoSuchMethodException("Ambiguous native bar mutation " + name);
                    resolved = method;
                }
                if (resolved == null) throw new NoSuchMethodException(name);
                nativeWrites.add(resolved);
            }
            Class<?> listener = Class.forName(controller.getName() + "$1", false, loader);
            Class<?> impl = Class.forName("com.android.server.wm.BarFollowAnimationImpl", false, loader);
            Class<?> start = Class.forName(impl.getName() + "$9", false, loader);
            Class<?> info = Class.forName("android.window.TransitionInfo", false, loader);
            java.lang.reflect.Constructor<?> listenerConstructor = listener.getDeclaredConstructor(controller);
            java.lang.reflect.Constructor<?> startConstructor = start.getDeclaredConstructor(impl, info);
            Method update = listener.getDeclaredMethod("onUpdate", Object.class, java.util.Collection.class);
            Method run = start.getDeclaredMethod("run");
            Class<?> state = Class.forName(impl.getName() + "$8", false, loader);
            java.lang.reflect.Constructor<?> stateConstructor = state.getDeclaredConstructor(impl, int.class, boolean.class);
            Method stateRun = state.getDeclaredMethod("run");
            if (!Runnable.class.isAssignableFrom(state) || state.getDeclaredField("this$0").getType() != impl
                    || state.getDeclaredField("val$state").getType() != int.class
                    || state.getDeclaredField("val$homeTaskOnTop").getType() != boolean.class
                    || stateRun.getReturnType() != void.class)
                throw new NoSuchMethodException("Native mixed bar state contract changed");
            String[] stateMethods = {"setStatusBarState", "setLastStatusState", "updateStatusBarToRecents",
                    "updateStatusBarToHome", "updateStatusBarAnotherApp", "updateStatusBarCloseWindow",
                    "updateStatusBarTaskSnapshot", "updateStatusBarAppOpen", "updateStatusBarToCurrentApp"};
            Method[] stateCalls = new Method[stateMethods.length];
            for (int i = 0; i < stateCalls.length; i++) {
                stateCalls[i] = controller.getDeclaredMethod(stateMethods[i], i < 2 ? new Class<?>[]{int.class} : new Class<?>[0]);
                if (stateCalls[i].getReturnType() != void.class) throw new NoSuchMethodException(stateMethods[i]);
            }
            if (!listener.getSuperclass().getName().equals("miuix.animation.listener.TransitionListener")
                    || listener.getDeclaredField("this$0").getType() != controller
                    || !Runnable.class.isAssignableFrom(start)
                    || start.getDeclaredField("this$0").getType() != impl
                    || start.getDeclaredField("val$info").getType() != info
                    || impl.getDeclaredField("mStatusBarController").getType() != controller
                    || update.getReturnType() != void.class || run.getReturnType() != void.class)
                throw new NoSuchMethodException("Native bar lifetime contract changed");
            if (!present.contains(SERVER_BAR_LISTENER_CREATE)) recordHookHandle(hook(listenerConstructor)
                    .setId(SERVER_BAR_LISTENER_CREATE).intercept(this::createServerBarListener));
            if (!present.contains(SERVER_BAR_LISTENER_UPDATE)) recordHookHandle(hook(update)
                    .setId(SERVER_BAR_LISTENER_UPDATE).intercept(this::dispatchServerBarListener));
            if (!present.contains(SERVER_BAR_START_CREATE)) recordHookHandle(hook(startConstructor)
                    .setId(SERVER_BAR_START_CREATE).intercept(this::createServerBarStart));
            if (!present.contains(SERVER_BAR_START_RUN)) recordHookHandle(hook(run)
                    .setId(SERVER_BAR_START_RUN).intercept(this::dispatchServerBarStart));
            if (!present.contains(SERVER_BAR_STATE_CREATE)) recordHookHandle(hook(stateConstructor)
                    .setId(SERVER_BAR_STATE_CREATE).intercept(this::createServerBarStart));
            if (!present.contains(SERVER_BAR_STATE_RUN)) recordHookHandle(hook(stateRun)
                    .setId(SERVER_BAR_STATE_RUN).intercept(this::dispatchServerBarState));
            for (int i = 0; i < stateCalls.length; i++) if (!present.contains(SERVER_BAR_STATE_PREFIX + stateMethods[i]))
                recordHookHandle(hook(stateCalls[i]).setId(SERVER_BAR_STATE_PREFIX + stateMethods[i])
                        .intercept(this::dispatchServerBarStateCall));
            // Only these verified allocation sites create the fenced objects.
            Class<?> ease = Class.forName("miuix.animation.utils.EaseManager$EaseStyle", false, loader);
            boolean ready = deoptimize(controller.getDeclaredMethod("animateStatusBarTo", float.class, float.class, ease, ease));
            ready &= deoptimize(impl.getDeclaredMethod("startStatusBarAnimation", info));
            ready &= deoptimize(impl.getDeclaredMethod("updateBarState", info, int.class));
            ready &= deoptimize(stateRun);
            for (ServerBarCallbackProfile.Entry callback : callbacks) {
                if (!present.contains(SERVER_BAR_CALLBACK_CREATE_PREFIX + callback.id))
                    recordHookHandle(hook(callback.constructor).setId(SERVER_BAR_CALLBACK_CREATE_PREFIX + callback.id)
                            .intercept(this::createServerBarSynthetic));
                if (!present.contains(SERVER_BAR_CALLBACK_RUN_PREFIX + callback.id))
                    recordHookHandle(hook(callback.run).setId(SERVER_BAR_CALLBACK_RUN_PREFIX + callback.id)
                            .intercept(this::dispatchServerBarSynthetic));
                ready &= deoptimize(callback.producer);
            }
            for (Method mutation : nativeWrites) {
                String id = SERVER_BAR_MUTATION_PREFIX + mutation.getName();
                if (!present.contains(id)) recordHookHandle(hook(mutation).setId(id)
                        .intercept(this::dispatchServerBarMutation));
            }
            // Native controller helpers are inlined into each other and the
            // verified bar implementation. Recompile neither while a lease owns writes.
            for (Class<?> owner : new Class<?>[]{controller, impl}) for (Method method : owner.getDeclaredMethods())
                if (!java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                        && !java.lang.reflect.Modifier.isNative(method.getModifiers())) ready &= deoptimize(method);
            serverBarLifetimeHooksReady = ready;
            Method systemReady = impl.getDeclaredMethod("systemReady",
                    Class.forName("com.android.server.wm.WindowManagerService", false, loader));
            if (!present.contains(SERVER_BAR_BRIDGE_READY)) recordHookHandle(hook(systemReady)
                    .setId(SERVER_BAR_BRIDGE_READY).intercept(this::serverBarBridgeReady));
            // Do not construct the platform singleton during bootstrap. Its constructor
            // initializes BarFollowAnimationUtils, caching the feature service's fallback
            // before HyperOSCustFeatureResolve is available. systemReady supplies the
            // actual native owner; only hot reload may recover an already-running owner.
            moduleLog(Log.INFO, TAG, "Native bar lifetime guards installed; activation=" + false + ", ready=" + ready);
        } catch (Throwable failure) {
            serverBarLifetimeHooksReady = false;
            moduleLog(Log.WARN, TAG, "Native bar lifetime guards unavailable; no real-window activation", failure);
        }
    }

    protected Object createServerBarListener(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        serverBarFollowLifecycle.created(chain.getThisObject(), chain.getArg(0));
        return result;
    }

    protected Object dispatchServerBarListener(XposedInterface.Chain chain) throws Throwable {
        Object callback = chain.getThisObject();
        Object controller = readField(callback, "this$0");
        try (ServerBarFollowLifecycle.Permit permit = serverBarFollowLifecycle.enter(callback, controller)) {
            if (permit == null) return null;
            Object result = chain.proceed();
            ServerBarWindowBridge bridge = serverBarWindowBridge;
            if (bridge != null) bridge.recordNative(controller, null);
            return result;
        }
    }

    protected Object createServerBarStart(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        serverBarFollowLifecycle.created(chain.getThisObject(), readField(chain.getArg(0), "mStatusBarController"));
        return result;
    }

    protected Object dispatchServerBarStart(XposedInterface.Chain chain) throws Throwable {
        Object callback = chain.getThisObject();
        Object controller = readField(readField(callback, "this$0"), "mStatusBarController");
        try (ServerBarFollowLifecycle.Permit permit = serverBarFollowLifecycle.enter(callback, controller)) {
            return permit == null ? null : chain.proceed();
        }
    }

    protected Object dispatchServerBarState(XposedInterface.Chain chain) throws Throwable {
        Object callback = chain.getThisObject();
        Object controller = readField(readField(callback, "this$0"), "mStatusBarController");
        Object previous = rejectedServerBarState.get();
        try (ServerBarFollowLifecycle.Permit permit = serverBarFollowLifecycle.enter(callback, controller)) {
            if (permit == null) rejectedServerBarState.set(controller);
            // This Runnable also owns native navigation-bar work. A stale
            // status lifetime must not discard navigation state or its finish.
            Object result = chain.proceed();
            ServerBarWindowBridge bridge = serverBarWindowBridge;
            if (permit != null && bridge != null)
                bridge.recordNative(controller, (Integer) readField(callback, "val$state"));
            return result;
        } finally {
            if (previous == null) rejectedServerBarState.remove();
            else rejectedServerBarState.set(previous);
        }
    }

    protected Object dispatchServerBarStateCall(XposedInterface.Chain chain) throws Throwable {
        return rejectedServerBarState.get() == chain.getThisObject() ? null : dispatchServerBarMutation(chain);
    }

    protected Object dispatchServerBarMutation(XposedInterface.Chain chain) throws Throwable {
        try (ServerBarFollowLifecycle.Permit permit = serverBarFollowLifecycle.enterMutation(chain.getThisObject())) {
            if (permit == null) return null;
            ServerBarWindowBridge bridge = serverBarWindowBridge;
            String operation = chain.getExecutable().getName();
            if (bridge != null) bridge.recordDecision(chain.getThisObject(), operation, "before", chain.getArgs());
            Object result = chain.proceed();
            if (bridge != null) bridge.recordDecision(chain.getThisObject(), operation, "after", chain.getArgs());
            return result;
        }
    }

    private Object syntheticBarController(Object owner) throws ReflectiveOperationException {
        return owner.getClass().getName().equals("com.android.server.wm.MiuiStatusBarController")
                ? owner : readField(owner, "mStatusBarController");
    }

    protected Object createServerBarSynthetic(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        serverBarFollowLifecycle.created(chain.getThisObject(), syntheticBarController(chain.getArg(0)));
        return result;
    }

    protected Object dispatchServerBarSynthetic(XposedInterface.Chain chain) throws Throwable {
        Object callback = chain.getThisObject();
        Object controller = syntheticBarController(readField(callback, "f$0"));
        try (ServerBarFollowLifecycle.Permit permit = serverBarFollowLifecycle.enter(callback, controller)) {
            return permit == null ? null : chain.proceed();
        }
    }

    protected Object serverBarBridgeReady(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        installServerBarWindowBridge(chain.getThisObject());
        repairServerNavigationPolicy(chain.getThisObject());
        return result;
    }

    protected void restoreServerBarOwnerAfterHotReload(ClassLoader loader) {
        if (Build.VERSION.SDK_INT != 37 || !serverBarLifetimeHooksReady) return;
        try {
            Object instance = Class.forName("com.android.server.wm.BarFollowAnimationStub", false, loader)
                    .getMethod("getInstance").invoke(null);
            if (readField(instance, "mContext") == null || readField(instance, "mWmService") == null) return;
            installServerBarWindowBridge(instance);
            repairServerNavigationPolicy(instance);
        } catch (Throwable failure) {
            moduleLog(Log.WARN, TAG, "Native bar owner restoration unavailable", failure);
        }
    }

    private void repairServerNavigationPolicy(Object impl) {
        try {
            Class<?> policy = Class.forName("com.android.server.wm.BarFollowAnimationUtils", false,
                    impl.getClass().getClassLoader());
            Field cachedDisabled = policy.getDeclaredField("disableMultiBars");
            cachedDisabled.setAccessible(true);
            if (cachedDisabled.getBoolean(null)) return;
            Class<?> features = Class.forName("miui.os.HyperOSCustFeatureResolve", false,
                    impl.getClass().getClassLoader());
            Method resolve = features.getMethod("getBoolean", String.class, boolean.class);
            String feature = "disable_multi_statusbar_navigationbar";
            // Different fallback answers mean the service is absent or unreadable.
            // Neither default alone proves native policy, and we never rewrite it.
            if (!Boolean.TRUE.equals(resolve.invoke(null, feature, false))
                    || !Boolean.TRUE.equals(resolve.invoke(null, feature, true))) return;
            Object navigation = readField(impl, "mNavigationBarController");
            Object wms = readField(impl, "mWmService");
            Object display = readField(impl, "mDisplayContent");
            if (navigation == null || wms == null || display == null
                    || !navigation.getClass().getName().equals("com.android.server.wm.MiuiNavigationBarController")) return;
            Object lock = readField(wms, "mGlobalLock");
            android.os.Handler handler = (android.os.Handler) readField(wms, "mAnimationHandler");
            Method retire = impl.getClass().getDeclaredMethod("destroyMiuiNavigationBarController",
                    Class.forName("com.android.server.wm.DisplayContent", false, impl.getClass().getClassLoader()));
            retire.setAccessible(true);
            Method traverse = wms.getClass().getDeclaredMethod("requestTraversal");
            traverse.setAccessible(true);
            int epoch = serverNavigationPolicyEpoch.incrementAndGet();
            handler.post(() -> {
                try {
                    if (serverNavigationPolicyEpoch.get() != epoch
                            || !Boolean.TRUE.equals(resolve.invoke(null, feature, false))
                            || !Boolean.TRUE.equals(resolve.invoke(null, feature, true))) return;
                    synchronized (lock) {
                        if (serverNavigationPolicyEpoch.get() != epoch
                                || readField(impl, "mWmService") != wms
                                || readField(impl, "mDisplayContent") != display
                                || readField(impl, "mNavigationBarController") != navigation) return;
                        // Stock teardown restores the real NavigationBar in the WMS
                        // pending transaction and retires the obsolete controller on
                        // its own Handler. No module Surface/flag writes are needed.
                        retire.invoke(impl, display);
                        traverse.invoke(wms);
                    }
                    moduleLog(Log.INFO, TAG, "Retired prematurely initialized native multi-navigation owner;"
                            + " authoritative HyperOS policy disables task navigation bars");
                } catch (Throwable failure) {
                    moduleLog(Log.WARN, TAG, "Native navigation policy reconciliation failed", failure);
                }
            });
        } catch (Throwable failure) {
            moduleLog(Log.WARN, TAG, "Native navigation policy proof unavailable; preserving owner", failure);
        }
    }

    protected Object serverBarOwnedFeature(XposedInterface.Chain chain) throws Throwable {
        ServerBarWindowBridge bridge = serverBarWindowBridge;
        return bridge != null && bridge.animationEnabled() ? true : chain.proceed();
    }


    private synchronized void installServerBarWindowBridge(Object impl) {
        if (!serverBarLifetimeHooksReady || serverBarWindowBridge != null) return;
        try {
            serverBarWindowBridge = new ServerBarWindowBridge(impl, serverBarFollowLifecycle, (message, failure) -> {
                if (failure == null) moduleLog(Log.INFO, TAG, message);
                else moduleLog(Log.WARN, TAG, message, failure);
            });
        } catch (Throwable failure) { moduleLog(Log.WARN, TAG, "WMS bar window bridge unavailable", failure); }
    }

    protected boolean reserveServerBarReload() {
        ServerBarWindowBridge bridge = serverBarWindowBridge;
        try {
            if (bridge != null && !bridge.canAwaitClose()) return false;
        } catch (ReflectiveOperationException failure) {
            moduleLog(Log.WARN, TAG, "Cannot prove safe native bar retirement wait", failure);
            return false;
        }
        serverNavigationPolicyEpoch.incrementAndGet();
        if (bridge != null) { bridge.close(); serverBarWindowBridge = null; }
        boolean retired = serverBarFollowLifecycle.reserveReload();
        boolean ready = false;
        try {
            // Waiting happens outside the WMS lock and the native owner Looper.
            // Include the lease acknowledgement/death-recipient unlink turn,
            // which follows the Surface commit and native bookkeeping.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            retired = retired || serverBarFollowLifecycle.awaitReload(5000L);
            long remainingMillis = Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    deadline - System.nanoTime()));
            ready = retired && (bridge == null || bridge.awaitClose(remainingMillis));
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!ready) {
            if (bridge != null) bridge.recoverAfterRejectedReload(() -> {
                serverBarFollowLifecycle.cancelReloadReservation();
                installServerBarWindowBridge(bridge.nativeOwner());
            });
            else serverBarFollowLifecycle.cancelReloadReservation();
            moduleLog(Log.WARN, TAG, "Native bar retirement not committed; rejected reload keeps old runtime recovery");
        } else if (bridge != null) {
            moduleLog(Log.INFO, TAG, "Native bar restoration and lease cleanup completed within this hot-reload request");
        }
        return ready;
    }
    protected Object[] saveServerBarRetiredOwners() {
        return new Object[]{serverBarFollowLifecycle.retiredControllers(), serverBarFollowLifecycle.retiredCallbacks()};
    }
    protected void restoreServerBarRetiredOwners(Object saved) {
        if (!(saved instanceof Object[])) return;
        Object[] state = (Object[]) saved;
        if (state.length <= 17 || !(state[17] instanceof Object[])) return;
        Object[] barState = (Object[]) state[17];
        if (barState.length != 2 || !(barState[0] instanceof Object[]) || !(barState[1] instanceof Object[][])) return;
        java.util.ArrayList<Object> owners = new java.util.ArrayList<>();
        for (Object candidate : (Object[]) barState[0]) if (candidate != null
                && candidate.getClass().getName().equals("com.android.server.wm.MiuiStatusBarController")) owners.add(candidate);
        serverBarFollowLifecycle.restoreRetiredControllers(owners.toArray());
        serverBarFollowLifecycle.restoreRetiredCallbacks((Object[][]) barState[1]);
    }

    protected void hookContextualSearchCompatibility(
            ClassLoader classLoader, Set<String> existingHookIds) {
        hookContextualSearchStartupGate(classLoader, existingHookIds);
        try {
            Class<?> serviceClass = Class.forName(
                    "com.android.server.contextualsearch.ContextualSearchManagerService",
                    false, classLoader);
            Class<?> stubClass = Class.forName(
                    "com.android.server.contextualsearch.ContextualSearchManagerService"
                            + "$ContextualSearchManagerStub",
                    false, classLoader);
            installContextualSearchStartHook(stubClass, existingHookIds);
            installContextualSearchStateHook(stubClass, existingHookIds);
            installContextualSearchPermissionHook(serviceClass, existingHookIds);
            installContextualSearchProviderHook(serviceClass, existingHookIds);
            SystemServerPlatformImpl implementation = systemServerPlatformImpl;
            String callerPackage = implementation != null
                    && implementation.nativeLauncherOwnsContextualSearchLongPress()
                    ? MIUI_HOME : SYSTEM_UI;
            moduleLog(Log.INFO, TAG,
                    "Installed contextual-search compatibility bridge"
                            + ", callerPackage=" + callerPackage
                            + ", enabled=" + isContextualSearchLongPressEnabled());
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search service compatibility unavailable", throwable);
        }
    }

    private void installContextualSearchStartHook(
            Class<?> stubClass, Set<String> existingHookIds) {
        if (existingHookIds.contains("server_contextual_search_start")) {
            return;
        }
        try {
            Method start = findContextualSearchStartMethod(stubClass);
            if (start == null) {
                throw new NoSuchMethodException(
                        stubClass.getName() + ".startContextualSearch");
            }
            start.setAccessible(true);
            recordHookHandle(hook(start)
                    .setId("server_contextual_search_start")
                    .intercept(this::bridgeContextualSearchSystemUiCall));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search start boundary unavailable", throwable);
        }
    }

    private void installContextualSearchStateHook(
            Class<?> stubClass, Set<String> existingHookIds) {
        if (existingHookIds.contains("server_contextual_search_state")) {
            return;
        }
        try {
            Method state = findContextualSearchStateMethod(stubClass);
            if (state == null) {
                throw new NoSuchMethodException(
                        stubClass.getName() + ".getContextualSearchState");
            }
            state.setAccessible(true);
            recordHookHandle(hook(state)
                    .setId("server_contextual_search_state")
                    .intercept(this::bridgeContextualSearchProviderCall));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search provider callback boundary unavailable", throwable);
        }
    }

    private void installContextualSearchPermissionHook(
            Class<?> serviceClass, Set<String> existingHookIds) {
        if (existingHookIds.contains("server_contextual_search_permission")) {
            return;
        }
        try {
            Method permission = serviceClass.getDeclaredMethod(
                    "enforcePermission", String.class);
            permission.setAccessible(true);
            boolean deoptimized = deoptimize(permission);
            recordHookHandle(hook(permission)
                    .setId("server_contextual_search_permission")
                    .intercept(this::scopeContextualSearchPermission));
            moduleLog(deoptimized ? Log.INFO : Log.WARN, TAG,
                    "Prepared contextual-search permission boundary"
                            + ", deoptimized=" + deoptimized);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search permission boundary unavailable", throwable);
        }
    }

    private void installContextualSearchProviderHook(
            Class<?> serviceClass, Set<String> existingHookIds) {
        if (existingHookIds.contains("server_contextual_search_provider")) {
            return;
        }
        try {
            Method provider = serviceClass.getDeclaredMethod(
                    "getContextualSearchPackageName");
            provider.setAccessible(true);
            boolean deoptimized = deoptimize(provider);
            recordHookHandle(hook(provider)
                    .setId("server_contextual_search_provider")
                    .intercept(this::scopeContextualSearchProvider));
            moduleLog(deoptimized ? Log.INFO : Log.WARN, TAG,
                    "Prepared contextual-search provider boundary"
                            + ", deoptimized=" + deoptimized);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search provider boundary unavailable", throwable);
        }
    }

    protected void hookContextualSearchStartupGate(
            ClassLoader classLoader, Set<String> existingHookIds) {
        if (existingHookIds.contains("server_contextual_search_startup_gate")) {
            return;
        }
        try {
            resolveContextualSearchPackageResourceId(classLoader);
            Class<?> systemServerClass = Class.forName(
                    "com.android.server.SystemServer", false, classLoader);
            Method gate = systemServerClass.getDeclaredMethod(
                    "deviceHasConfigString", Context.class, int.class);
            gate.setAccessible(true);
            recordHookHandle(hook(gate)
                    .setId("server_contextual_search_startup_gate")
                    .intercept(this::enableContextualSearchServiceAtBoot));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search startup gate unavailable", throwable);
        }
    }

    protected Object enableContextualSearchServiceAtBoot(
            XposedInterface.Chain chain) throws Throwable {
        Object contextArgument = chain.getArg(0);
        if (contextArgument instanceof Context) {
            contextualSearchPackageManager =
                    ((Context) contextArgument).getPackageManager();
        }
        int resourceId = contextualSearchPackageResourceId;
        if (resourceId == 0) {
            resourceId = resolveContextualSearchPackageResourceId(
                    chain.getExecutable().getDeclaringClass().getClassLoader());
        }
        Object requestedResource = chain.getArg(1);
        SystemServerPlatformImpl implementation = systemServerPlatformImpl;
        boolean alwaysRegister = implementation != null
                && implementation.alwaysRegisterContextualSearchService();
        boolean preferenceEnabled = isContextualSearchLongPressEnabled();
        if (requestedResource instanceof Number
                && ((Number) requestedResource).intValue() == resourceId
                && (alwaysRegister || preferenceEnabled)) {
            moduleLog(Log.INFO, TAG,
                    "Enabled ContextualSearchManagerService startup"
                            + ", providerConfiguredAtCallTime=true"
                            + ", platform=" + (implementation == null
                            ? "unresolved" : implementation.name())
                            + ", alwaysRegister=" + alwaysRegister
                            + ", preferenceEnabled=" + preferenceEnabled);
            return Boolean.TRUE;
        }
        return chain.proceed();
    }

    protected Object bridgeContextualSearchSystemUiCall(
            XposedInterface.Chain chain) throws Throwable {
        SystemServerPlatformImpl implementation = systemServerPlatformImpl;
        if (implementation == null) {
            moduleLog(Log.WARN, TAG,
                    "Contextual-search caller policy unavailable; preserving platform call");
            return chain.proceed();
        }
        String requiredPackage = implementation
                .nativeLauncherOwnsContextualSearchLongPress()
                ? MIUI_HOME : SYSTEM_UI;
        return runContextualSearchBridge(chain, requiredPackage);
    }

    protected Object bridgeContextualSearchProviderCall(
            XposedInterface.Chain chain) throws Throwable {
        return runContextualSearchBridge(chain, GOOGLE_CONTEXTUAL_SEARCH_PACKAGE);
    }

    protected Object scopeContextualSearchPermission(
            XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(contextualSearchBridgeInvocation.get())) {
            return null;
        }
        return chain.proceed();
    }

    protected Object scopeContextualSearchProvider(
            XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(contextualSearchBridgeInvocation.get())) {
            return GOOGLE_CONTEXTUAL_SEARCH_PACKAGE;
        }
        return chain.proceed();
    }

    private Object runContextualSearchBridge(
            XposedInterface.Chain chain, String requiredPackage) throws Throwable {
        if (!isContextualSearchLongPressEnabled()
                || !callingUidOwnsPackage(requiredPackage)) {
            return chain.proceed();
        }
        Boolean previous = contextualSearchBridgeInvocation.get();
        contextualSearchBridgeCallsInFlight.incrementAndGet();
        contextualSearchBridgeInvocation.set(Boolean.TRUE);
        try {
            return chain.proceed();
        } finally {
            if (previous == null) {
                contextualSearchBridgeInvocation.remove();
            } else {
                contextualSearchBridgeInvocation.set(previous);
            }
            contextualSearchBridgeCallsInFlight.decrementAndGet();
        }
    }

    private boolean callingUidOwnsPackage(String requiredPackage) {
        PackageManager packageManager = resolveContextualSearchPackageManager();
        if (packageManager == null) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        try {
            String[] packages = packageManager.getPackagesForUid(callingUid);
            if (packages == null) {
                return false;
            }
            for (String packageName : packages) {
                if (requiredPackage.equals(packageName)) {
                    return true;
                }
            }
            moduleLog(Log.WARN, TAG,
                    "Rejected contextual-search caller"
                            + ", uid=" + callingUid
                            + ", requiredPackage=" + requiredPackage);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Could not authenticate contextual-search caller"
                            + ", uid=" + callingUid, throwable);
        }
        return false;
    }

    private PackageManager resolveContextualSearchPackageManager() {
        PackageManager cached = contextualSearchPackageManager;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> activityThreadClass = ActivityThread.class;
            Object application = ActivityThread.currentApplication();
            Context context = application instanceof Context ? (Context) application : null;
            if (context == null) {
                    Object activityThread = activityThreadClass
                        .getDeclaredMethod("currentActivityThread")
                        .invoke(null);
                if (activityThread != null) {
                    Method getSystemContext = activityThreadClass
                            .getDeclaredMethod("getSystemContext");
                    getSystemContext.setAccessible(true);
                    Object systemContext = getSystemContext.invoke(activityThread);
                    if (systemContext instanceof Context) {
                        context = (Context) systemContext;
                    }
                }
            }
            if (context != null) {
                cached = context.getPackageManager();
                contextualSearchPackageManager = cached;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Could not resolve system PackageManager for contextual search", throwable);
        }
        return cached;
    }

    private int resolveContextualSearchPackageResourceId(ClassLoader classLoader)
            throws Exception {
        int cached = contextualSearchPackageResourceId;
        if (cached != 0) {
            return cached;
        }
        Class<?> stringResources = Class.forName(
                "com.android.internal.R$string", false, classLoader);
        Field field = stringResources.getDeclaredField(
                "config_defaultContextualSearchPackageName");
        field.setAccessible(true);
        cached = field.getInt(null);
        contextualSearchPackageResourceId = cached;
        return cached;
    }

    private static Method findContextualSearchStartMethod(Class<?> stubClass) {
        for (Method method : stubClass.getDeclaredMethods()) {
            if (!"startContextualSearch".equals(method.getName())
                    || method.getReturnType() != void.class) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == int.class) {
                return method;
            }
            if (parameters.length == 2 && parameters[0] == int.class
                    && "android.app.contextualsearch.ContextualSearchConfig".equals(
                    parameters[1].getName())) {
                return method;
            }
        }
        return null;
    }

    private static Method findContextualSearchStateMethod(Class<?> stubClass) {
        for (Method method : stubClass.getDeclaredMethods()) {
            if (!"getContextualSearchState".equals(method.getName())
                    || method.getReturnType() != void.class) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0] == IBinder.class
                    && "android.app.contextualsearch.IContextualSearchCallback".equals(
                    parameters[1].getName())) {
                return method;
            }
        }
        return null;
    }

    protected void hookPredictiveBackOptInMetadata(ClassLoader classLoader) {
        try {
            Method method = resolvePredictiveBackOptInMethod(classLoader);
            if (method == null) {
                moduleLog(Log.WARN, TAG,
                        "Predictive-back opt-in check not found in system_server");
                return;
            }
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("server_predictive_opt_in_metadata")
                    .intercept(this::injectSelectedPredictiveBackMetadata));
            moduleLog(Log.INFO, TAG, "Hooked predictive-back opt-in metadata"
                    + ", owner=system_server"
                    + ", policy=selectedApplications"
                    + ", preferencesGroup=" + PredictiveBackPreferences.GROUP);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook selected predictive-back metadata", throwable);
        }
    }

    private Method resolvePredictiveBackOptInMethod(ClassLoader classLoader)
            throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            return WindowOnBackInvokedDispatcher.class.getDeclaredMethod(
                    "isOnBackInvokedCallbackEnabled", ActivityInfo.class,
                    ApplicationInfo.class, Supplier.class);
        }
        Class<?> dispatcherClass = Class.forName(
                WINDOW_ON_BACK_INVOKED_DISPATCHER, false, classLoader);
        for (Method method : dispatcherClass.getDeclaredMethods()) {
            if ("isOnBackInvokedCallbackEnabled".equals(method.getName())
                    && method.getParameterCount() == 3
                    && "android.content.pm.ActivityInfo".equals(
                    method.getParameterTypes()[0].getName())
                    && "android.content.pm.ApplicationInfo".equals(
                    method.getParameterTypes()[1].getName())) {
                return method;
            }
        }
        return null;
    }

    protected Object injectSelectedPredictiveBackMetadata(XposedInterface.Chain chain)
            throws Throwable {
        Object activityInfoArgument = chain.getArg(0);
        if (!(activityInfoArgument instanceof ActivityInfo)) {
            return chain.proceed();
        }
        ActivityInfo activityInfo = (ActivityInfo) activityInfoArgument;
        String packageName = activityInfo.packageName;
        if (packageName == null || packageName.isEmpty()
                || !isPredictiveBackOptInSelected(packageName)) {
            return chain.proceed();
        }
        Boolean applicationOptInEnabled = readApplicationPredictiveBackOptInEnabled(
                chain.getArg(1));
        if (applicationOptInEnabled == null) {
            return chain.proceed();
        }
        if (applicationOptInEnabled.booleanValue()) {
            moduleLog(Log.INFO, TAG, "Ignored stale predictive-back selection"
                    + ", package=" + packageName
                    + ", reason=applicationAlreadyOptedIn");
            return chain.proceed();
        }

        int originalFlags;
        int effectiveFlags;
        try {
            originalFlags = ((Number) readField(
                    activityInfo, "privateFlags")).intValue();
            effectiveFlags = (originalFlags
                    & ~ACTIVITY_PREDICTIVE_BACK_DISABLE_FLAG)
                    | ACTIVITY_PREDICTIVE_BACK_ENABLE_FLAG;
            writeField(activityInfo, "privateFlags", effectiveFlags);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to inject selected predictive-back metadata"
                    + ", package=" + packageName
                    + ", activity=" + shortObject(activityInfo), throwable);
            return chain.proceed();
        }

        Object result = chain.proceed();
        int priority = Boolean.TRUE.equals(result) ? Log.INFO : Log.WARN;
        moduleLog(priority, TAG, "Selected predictive-back metadata result"
                + ", package=" + packageName
                + ", activity=" + shortObject(activityInfo)
                + ", activityFlags=" + originalFlags + "->" + effectiveFlags
                + ", effectiveDecision=" + result
                + ", applicationInfoMutated=false"
                + ", restartRequiredForChanges=true");
        return result;
    }

    protected Boolean readApplicationPredictiveBackOptInEnabled(Object applicationInfo) {
        if (applicationInfo == null) {
            return null;
        }
        try {
            int privateFlagsExt = ((Number) readField(
                    applicationInfo, "privateFlagsExt")).intValue();
            predictiveBackApplicationMetadataFailureLogged = false;
            return Boolean.valueOf(
                    (privateFlagsExt & APPLICATION_PREDICTIVE_BACK_ENABLE_FLAG) != 0);
        } catch (Throwable throwable) {
            if (!predictiveBackApplicationMetadataFailureLogged) {
                predictiveBackApplicationMetadataFailureLogged = true;
                moduleLog(Log.WARN, TAG,
                        "Could not inspect application predictive-back metadata"
                                + ", policy=preservePlatformDecision",
                        throwable);
            }
            return null;
        }
    }

    protected boolean isPredictiveBackOptInSelected(String packageName) {
        try {
            SharedPreferences preferences = predictiveBackPreferences;
            if (preferences == null) {
                synchronized (this) {
                    preferences = predictiveBackPreferences;
                    if (preferences == null) {
                        preferences = getRemotePreferences(PredictiveBackPreferences.GROUP);
                        predictiveBackPreferences = preferences;
                    }
                }
            }
            Set<String> packages = preferences.getStringSet(
                    PredictiveBackPreferences.KEY_PACKAGES,
                    Collections.emptySet());
            predictiveBackPreferencesFailureLogged = false;
            return packages != null && packages.contains(packageName);
        } catch (Throwable throwable) {
            if (!predictiveBackPreferencesFailureLogged) {
                predictiveBackPreferencesFailureLogged = true;
                moduleLog(Log.ERROR, TAG, "Predictive-back preferences unavailable"
                        + ", policy=failClosed"
                        + ", package=" + packageName, throwable);
            }
            return false;
        }
    }

    protected void hookSecuritySidebarTransientBars(ClassLoader classLoader) {
        hookSecuritySidebarTransientBars(classLoader, Collections.emptySet());
    }

    protected void hookSecuritySidebarTransientBars(ClassLoader classLoader,
                                                    Set<String> existingHookIds) {
        hookFullscreenSideSwipeCallbacks(classLoader, existingHookIds);
        try {
            Class<?> policyClass = Class.forName(DISPLAY_POLICY, false, classLoader);
            int hooked = 0;
            int installed = 0;
            for (Method method : policyClass.getDeclaredMethods()) {
                if (!"requestTransientBars".equals(method.getName())) {
                    continue;
                }
                int overload = hooked++;
                String hookId = "server_security_sidebar_transient_bars_" + overload;
                if (existingHookIds.contains(hookId)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId(hookId)
                            .intercept(this::interceptSecuritySidebarTransientBars));
                    installed++;
                } catch (Throwable throwable) {
                    moduleLog(Log.ERROR, TAG,
                            "Failed to hook security-sidebar transient bars " + hookId,
                            throwable);
                }
            }
            if (hooked == 0) {
                moduleLog(Log.WARN, TAG, "DisplayPolicy.requestTransientBars not found");
            } else {
                moduleLog(Log.INFO, TAG, "Hooked DisplayPolicy transient-bars overloads="
                        + hooked + ", installed=" + installed);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook security-sidebar transient bars", throwable);
        }
    }

    private void hookFullscreenSideSwipeCallbacks(ClassLoader classLoader,
                                                  Set<String> existingHookIds) {
        String[] hookIds = {"server_fullscreen_side_swipe_left",
                "server_fullscreen_side_swipe_right"};
        if (existingHookIds.contains(hookIds[0]) && existingHookIds.contains(hookIds[1])) {
            return;
        }
        try {
            Class<?> policyClass = Class.forName(DISPLAY_POLICY, false, classLoader);
            Class<?> callbacksClass = Class.forName(DISPLAY_POLICY + "$1", false, classLoader);
            Class<?> callbacksContract = Class.forName(
                    "com.android.server.wm.SystemGesturesPointerEventListener$Callbacks",
                    false, classLoader);
            if (!callbacksContract.isAssignableFrom(callbacksClass)
                    || callbacksClass.getDeclaredField("this$0").getType() != policyClass) {
                throw new IllegalStateException("Unexpected DisplayPolicy swipe callback owner");
            }
            Method[] methods = {callbacksClass.getDeclaredMethod("onSwipeFromLeft"),
                    callbacksClass.getDeclaredMethod("onSwipeFromRight")};
            for (Method method : methods) {
                if (method.getReturnType() != void.class) {
                    throw new IllegalStateException("Unexpected side-swipe callback signature");
                }
            }
            for (int i = 0; i < methods.length; i++) {
                if (existingHookIds.contains(hookIds[i])) {
                    continue;
                }
                methods[i].setAccessible(true);
                recordHookHandle(hook(methods[i]).setId(hookIds[i])
                        .intercept(this::interceptFullscreenSideSwipe));
                moduleLog(Log.INFO, TAG, "Hooked fullscreen side-swipe origin: " + hookIds[i]);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Fullscreen side-swipe callback unavailable; preserving native requests",
                    throwable);
        }
    }

    protected Object interceptFullscreenSideSwipe(XposedInterface.Chain chain) throws Throwable {
        if (!isFullscreenDirectBackEnabled()) {
            return chain.proceed();
        }
        Object policy;
        Object navigationBar;
        boolean matchingHost;
        try {
            policy = readField(chain.getThisObject(), "this$0");
            boolean left = "onSwipeFromLeft".equals(chain.getExecutable().getName());
            String hostField = left ? "mLeftGestureHost" : "mRightGestureHost";
            Object gestures = readField(policy, "mSystemGestures");
            boolean mouseHovering = !Boolean.FALSE.equals(readField(gestures,
                    left ? "mMouseHoveringAtLeft" : "mMouseHoveringAtRight"));
            Object lock = readField(policy, "mLock");
            synchronized (lock) {
                navigationBar = readField(policy, "mNavigationBar");
                matchingHost = !mouseHovering && navigationBar != null
                        && readField(policy, hostField) == navigationBar;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Cannot identify native side-swipe owner", throwable);
            return chain.proceed();
        }
        if (!matchingHost) {
            return chain.proceed();
        }
        return fullscreenSideSwipeScope.intercept(chain, policy, navigationBar);
    }

    protected Object interceptSecuritySidebarTransientBars(XposedInterface.Chain chain)
            throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            if (isAndroid17SecuritySidebarTransientRequest(chain)) {
                moduleLog(Log.INFO, TAG,
                        "Blocked Android 17 transient bars from security sidebar handle"
                                + ", overload=" + chain.getExecutable().toGenericString());
                return null;
            }
        }
        if (isSidebarTransientGesture(chain.getThisObject())) {
            moduleLog(Log.INFO, TAG, "Blocked transient bars from sidebar bounds"
                    + ", overload=" + chain.getExecutable().toGenericString());
            return null;
        }
        for (Object argument : chain.getArgs()) {
            if (argument == null) {
                continue;
            }
            String lower = String.valueOf(argument).toLowerCase(Locale.ROOT);
            if (!lower.contains("sidebar")
                    && !lower.contains("game")
                    && !lower.contains("toolbox")) {
                continue;
            }
            String owner;
            try {
                owner = String.valueOf(invokeAnyMethod(
                        argument, "getOwningPackage", new Object[0]));
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            if (MIUI_SECURITY_CENTER_PACKAGE.equals(owner)) {
                moduleLog(Log.INFO, TAG, "Blocked transient bars from security sidebar"
                        + ", target=" + shortObject(argument));
                return null;
            }
        }
        Object[] sideSwipeArguments = fullscreenSideSwipeScope.requestArguments(
                chain.getThisObject(), chain.getArgs());
        if (sideSwipeArguments != null) {
            moduleLog(Log.INFO, TAG, "Preserved Xiaomi side-swipe null target"
                    + ", originalTarget=" + shortObject(chain.getArg(0)));
            return chain.proceed(sideSwipeArguments);
        }
        if (chain.getArgs().size() == 2
                && chain.getArg(0) == null
                && Boolean.FALSE.equals(chain.getArg(1))) {
            if (isFullscreenDirectBackEnabled()) {
                // Xiaomi returns early for a null swipe target. Keep that native path
                // in direct-back mode instead of supplying a target that reveals bars.
                return chain.proceed();
            }
            Object navigationBar;
            try {
                navigationBar = readField(chain.getThisObject(), "mNavigationBar");
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG,
                        "Cannot inspect AOSP side transient-bars target", throwable);
                return chain.proceed();
            }
            if (navigationBar == null) {
                moduleLog(Log.WARN, TAG,
                        "Cannot restore AOSP side transient bars: NavigationBar is absent");
                return chain.proceed();
            }
            Object[] args = chain.getArgs().toArray();
            args[0] = navigationBar;
            args[1] = Boolean.TRUE;
            moduleLog(Log.INFO, TAG, "Restored AOSP side transient-bars target"
                    + ", target=" + shortObject(navigationBar));
            return chain.proceed(args);
        }
        return chain.proceed();
    }

    protected boolean isAndroid17SecuritySidebarTransientRequest(
            XposedInterface.Chain chain) {
        Object displayPolicy = chain.getThisObject();
        try {
            Object gestures = readField(displayPolicy, "mSystemGestures");
            float[] downXs = (float[]) readField(gestures, "mDownX");
            float[] downYs = (float[]) readField(gestures, "mDownY");
            long[] downTimes = (long[]) readField(gestures, "mDownTime");
            int downPointers = ((Number) readField(
                    gestures, "mDownPointers")).intValue();
            if (downXs == null || downYs == null || downTimes == null
                    || downPointers <= 0) {
                return false;
            }

            Object displayContent = readField(displayPolicy, "mDisplayContent");
            Object windowManagerLock = readField(displayPolicy, "mLock");
            if (displayContent == null || windowManagerLock == null) {
                return false;
            }
            int pointerCount = Math.min(downPointers,
                    Math.min(downXs.length, Math.min(downYs.length, downTimes.length)));
            long now = SystemClock.uptimeMillis();
            for (int pointer = 0; pointer < pointerCount; pointer++) {
                long age = now - downTimes[pointer];
                if (downTimes[pointer] <= 0L || age < 0L
                        || age > SIDEBAR_GESTURE_MAX_AGE_MS) {
                    continue;
                }
                int x = Math.round(downXs[pointer]);
                int y = Math.round(downYs[pointer]);
                Throwable[] matchFailure = new Throwable[1];
                Predicate<Object> matcher = window -> {
                    try {
                        return isAndroid17SecuritySidebarHandleWindow(window, x, y);
                    } catch (Throwable throwable) {
                        matchFailure[0] = throwable;
                        return false;
                    }
                };
                Object matchedWindow;
                synchronized (windowManagerLock) {
                    matchedWindow = invokeAnyMethod(
                            displayContent, "getWindow", new Object[]{matcher});
                }
                if (matchedWindow != null) {
                    moduleLog(Log.INFO, TAG,
                            "Matched Android 17 security sidebar handle"
                                    + ", pointer=" + pointer
                                    + ", x=" + x + ", y=" + y
                                    + ", window=" + shortObject(matchedWindow));
                    return true;
                }
                if (matchFailure[0] != null) {
                    throw matchFailure[0];
                }
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to inspect Android 17 security sidebar handle gesture",
                    throwable);
        }
        return false;
    }

    protected boolean isAndroid17SecuritySidebarHandleWindow(
            Object window, int x, int y) throws Exception {
        if (window == null
                || !MIUI_SECURITY_CENTER_PACKAGE.equals(String.valueOf(invokeAnyMethod(
                window, "getOwningPackage", new Object[0])))
                || ((Number) invokeAnyMethod(
                window, "getWindowType", new Object[0])).intValue()
                != TYPE_DISPLAY_OVERLAY) {
            return false;
        }
        String windowTag = String.valueOf(invokeAnyMethod(
                window, "getWindowTag", new Object[0]));
        if ((!MIUI_SECURITY_GAME_SIDEBAR_HANDLE_TITLE.equals(windowTag)
                && !MIUI_SECURITY_VIDEO_SIDEBAR_HANDLE_TITLE.equals(windowTag))
                || !Boolean.TRUE.equals(invokeAnyMethod(
                window, "isVisible", new Object[0]))) {
            return false;
        }
        Object frameObject = invokeAnyMethod(window, "getFrame", new Object[0]);
        if (!(frameObject instanceof Rect)) {
            throw new IllegalStateException(
                    "Unexpected security sidebar handle frame=" + shortObject(frameObject));
        }
        Rect frame = new Rect((Rect) frameObject);
        return !frame.isEmpty() && frame.contains(x, y);
    }

    protected boolean isSidebarTransientGesture(Object displayPolicy) {
        try {
            Context context = (Context) readField(displayPolicy, "mContext");
            Object gestures = readField(displayPolicy, "mSystemGestures");
            float[] downXs = (float[]) readField(gestures, "mDownX");
            float[] downYs = (float[]) readField(gestures, "mDownY");
            long[] downTimes = (long[]) readField(gestures, "mDownTime");
            int downPointers = ((Number) readField(gestures, "mDownPointers")).intValue();
            if (context == null || downXs == null || downYs == null || downTimes == null
                    || downPointers <= 0) {
                return false;
            }
            String encoded = Settings.Secure.getString(context.getContentResolver(),
                    MIUI_SIDEBAR_BOUNDS);
            if (encoded == null || encoded.trim().isEmpty()) {
                return false;
            }
            int padding = Math.max(0, Math.round(MIUI_SIDEBAR_EXCLUSION_PADDING_DP
                    * context.getResources().getDisplayMetrics().density));
            JSONArray bounds = new JSONArray(encoded);
            int pointerCount = Math.min(downPointers,
                    Math.min(downXs.length, Math.min(downYs.length, downTimes.length)));
            long now = SystemClock.uptimeMillis();
            for (int pointer = 0; pointer < pointerCount; pointer++) {
                // Ignore stale slots left behind by an earlier system gesture.
                if (downTimes[pointer] <= 0L
                        || now - downTimes[pointer] > SIDEBAR_GESTURE_MAX_AGE_MS) {
                    continue;
                }
                int x = Math.round(downXs[pointer]);
                int y = Math.round(downYs[pointer]);
                for (int i = 0; i < bounds.length(); i++) {
                    JSONObject item = bounds.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    Rect rect = new Rect(item.optInt("l", -1), item.optInt("t", -1),
                            item.optInt("r", -1), item.optInt("b", -1));
                    if (!rect.isEmpty()) {
                        rect.inset(-padding, -padding);
                        if (rect.contains(x, y)) {
                            moduleLog(Log.INFO, TAG, "Matched sidebar transient gesture"
                                    + ", pointer=" + pointer + ", x=" + x + ", y=" + y
                                    + ", bounds=" + rect);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to inspect sidebar transient gesture", throwable);
        }
        return false;
    }

    protected ClassLoader findSystemServerClassLoader(ClassLoader preferred) {
        ClassLoader[] candidates = new ClassLoader[]{
                preferred,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                SystemServerHookRuntime.class.getClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Class.forName(BACK_NAVIGATION_CONTROLLER, false, candidate);
                moduleLog(Log.INFO, TAG, "Resolved system_server classloader: " + candidate);
                return candidate;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "System_server classloader candidate failed: "
                        + candidate + ", error=" + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            }
        }
        return null;
    }

    protected synchronized void selectSystemServerPlatformImpl(ClassLoader classLoader)
            throws Exception {
        if (classLoader == null) {
            throw new ClassNotFoundException("system_server ClassLoader is null");
        }
        SystemServerPlatformImpl current = systemServerPlatformImpl;
        if (current != null && systemServerPlatformClassLoader == classLoader) {
            return;
        }
        Class<?> transitionClass = Class.forName(
                "com.android.server.wm.Transition", false, classLoader);
        SystemServerPlatformImpl selected;
        if (SystemServerAndroid17Impl.matches(transitionClass)) {
            selected = new SystemServerAndroid17Impl();
        } else if (SystemServerAndroid16Impl.matches(transitionClass)) {
            selected = new SystemServerAndroid16Impl();
        } else {
            throw new NoSuchMethodException(
                    "Unsupported Transition.calculateTransitionInfo platform shape");
        }
        systemServerPlatformImpl = selected;
        systemServerPlatformClassLoader = classLoader;
        moduleLog(Log.INFO, TAG, "Selected system_server platform implementation="
                + selected.name() + ", classLoader=" + classLoader);
    }

    private SystemServerPlatformImpl requireSystemServerPlatformImpl(
            ClassLoader classLoader) throws Exception {
        selectSystemServerPlatformImpl(classLoader);
        SystemServerPlatformImpl implementation = systemServerPlatformImpl;
        if (implementation == null) {
            throw new IllegalStateException(
                    "system_server platform implementation is unavailable");
        }
        return implementation;
    }

    protected void hookBackNavigationDoneCleanup(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(BACK_NAVIGATION_CONTROLLER, false,
                    classLoader);
            for (Method method : controllerClass.getDeclaredMethods()) {
                String name = method.getName();
                if (!("onBackNavigationDone".equals(name)
                        || "lambda$startBackNavigation$4".equals(name))
                        || method.getParameterCount() != 2
                        || method.getParameterTypes()[0] != Bundle.class
                        || method.getParameterTypes()[1] != int.class) {
                    continue;
                }
                method.setAccessible(true);
                recordHookHandle(hook(method)
                        .setId("server_back_navigation_done_cleanup")
                        .intercept(this::cleanupSkippedRemoteAnimationOnNavigationDone));
                moduleLog(Log.INFO, TAG, "Hooked BackNavigationController navigation-done cleanup"
                        + ", method=" + method.getName());
                return;
            }
            moduleLog(Log.WARN, TAG, "BackNavigationController.onBackNavigationDone not found");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to hook BackNavigationController navigation-done cleanup",
                    throwable);
        }
    }

    protected Object cleanupSkippedRemoteAnimationOnNavigationDone(XposedInterface.Chain chain)
            throws Throwable {
        Bundle resultBundle = (Bundle) chain.getArg(0);
        boolean committed = resultBundle != null
                && resultBundle.containsKey("NavigationFinished")
                && resultBundle.getBoolean("NavigationFinished");
        Object result = chain.proceed();
        if (!committed) {
            return result;
        }
        Object controller = chain.getThisObject();
        try {
            Object windowManagerService = readField(
                    controller, "mWindowManagerService");
            Object globalLock = readField(windowManagerService, "mGlobalLock");
            invokeAnyMethod(windowManagerService,
                    "boostPriorityForLockedSection", new Object[0]);
            try {
                synchronized (globalLock) {
                    Object handler = readField(controller, "mAnimationHandler");
                    if (!Boolean.TRUE.equals(readField(handler, "mComposed"))) {
                        return result;
                    }
                    Object prepareClose = readField(handler, "mPrepareCloseTransition");
                    Object openAdaptor = readField(handler, "mOpenAnimAdaptor");
                    Object prepareOpen = openAdaptor == null ? null
                            : readField(openAdaptor, "mPreparedOpenTransition");
                    if (prepareClose != null || prepareOpen != null) {
                        moduleLog(Log.INFO, TAG,
                                "Kept composed predictive-back animation for transition cleanup"
                                        + ", prepareOpen=" + shortObject(prepareOpen)
                                        + ", prepareClose=" + shortObject(prepareClose));
                        return result;
                    }
                    invokeAnyMethod(controller, "clearBackAnimations",
                            new Object[]{Boolean.FALSE});
                }
            } finally {
                invokeAnyMethod(windowManagerService,
                        "resetPriorityAfterLockedSection", new Object[0]);
            }
            moduleLog(Log.INFO, TAG, "Cleared committed remote-only predictive-back animation"
                    + " after skipped prepare transition");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed committed remote-only predictive-back cleanup",
                    throwable);
        }
        return result;
    }

    protected void hookBackWindowStartAnimation(ClassLoader classLoader) {
        try {
            Class<?> adaptorClass = Class.forName(BACK_WINDOW_ANIMATION_ADAPTOR,
                    false, classLoader);
            for (Method method : adaptorClass.getDeclaredMethods()) {
                if ("startAnimation".equals(method.getName())
                        && method.getParameterCount() == 4) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_back_window_start_animation")
                            .intercept(this::prepareOpeningTaskFragment));
                    moduleLog(Log.INFO, TAG, "Hooked BackWindowAnimationAdaptor.startAnimation");
                    return;
                }
            }
            moduleLog(Log.WARN, TAG, "BackWindowAnimationAdaptor.startAnimation not found");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to hook BackWindowAnimationAdaptor.startAnimation",
                    throwable);
        }
    }

    protected void hookA17OpeningSurfaceVisibility(ClassLoader classLoader) {
        try {
            SystemServerPlatformImpl implementation =
                    requireSystemServerPlatformImpl(classLoader);
            Method method = implementation.openingSurfaceVisibilityMethod(classLoader);
            if (method == null) {
                return;
            }
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("server_a17_opening_surface_visibility")
                    .intercept(this::restoreA17OpeningSurfaceVisibility));
            moduleLog(Log.INFO, TAG,
                    "Hooked Android 17 opening-surface visibility recovery");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook Android 17 opening-surface visibility recovery",
                    throwable);
        }
    }

    protected void hookFreeformCrossActivityPrepareRole(ClassLoader classLoader) {
        serverTransitionChangeInfoFlagsField = null;
        try {
            Class<?> transitionClass = Class.forName(
                    "com.android.server.wm.Transition", false, classLoader);
            if (!initializeFreeformPrepareRoleReflection(classLoader)) {
                return;
            }
            SystemServerPlatformImpl implementation =
                    requireSystemServerPlatformImpl(classLoader);
            Method method = implementation.calculateTransitionInfoMethod(transitionClass);
            recordHookHandle(hook(method)
                    .setId("server_freeform_prepare_role_normalization")
                    .intercept(this::normalizeFreeformCrossActivityTransitionInfo));
            moduleLog(Log.INFO, TAG,
                    "Hooked server cross-activity predictive-back prepare role"
                            + " normalization, platform=" + implementation.name()
                            + ", parameterCount=" + method.getParameterCount());
        } catch (Throwable throwable) {
            serverTransitionChangeInfoFlagsField = null;
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook server cross-activity predictive-back prepare role",
                    throwable);
        }
    }

    protected boolean initializeFreeformPrepareRoleReflection(ClassLoader classLoader) {
        try {
            Class<?> changeInfoClass = Class.forName(
                    "com.android.server.wm.Transition$ChangeInfo", false, classLoader);
            Field flags = changeInfoClass.getDeclaredField("mFlags");
            flags.setAccessible(true);
            serverTransitionChangeInfoFlagsField = flags;
            return true;
        } catch (Throwable throwable) {
            serverTransitionChangeInfoFlagsField = null;
            moduleLog(Log.ERROR, TAG,
                    "Server cross-activity prepare-role reflection unavailable", throwable);
            return false;
        }
    }

    protected Object normalizeFreeformCrossActivityTransitionInfo(
            XposedInterface.Chain chain) throws Throwable {
        Field flagsField = serverTransitionChangeInfoFlagsField;
        Object closingChangeInfo = null;
        Object openingChangeInfo = null;
        int closingIndex = -1;
        try {
            Object type = chain.getArg(0);
            if (flagsField != null
                    && type instanceof Number
                    && ((Number) type).intValue() == TRANSIT_PREDICTIVE_BACK) {
                Object targetsObject = chain.getArg(2);
                closingChangeInfo = resolveExactFreeformCrossActivityChangeInfo(
                        targetsObject, flagsField);
                if (closingChangeInfo != null) {
                    closingIndex = ((List<?>) targetsObject).indexOf(closingChangeInfo);
                    if (closingIndex >= 0) {
                        openingChangeInfo = ((List<?>) targetsObject).get(1 - closingIndex);
                    }
                }
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to inspect server cross-activity prepared targets;"
                            + " preserving the platform transition",
                    throwable);
        }
        Object result = chain.proceed();
        try {
            SystemServerPlatformImpl implementation = systemServerPlatformImpl;
            if (implementation != null) {
                implementation.inspectCalculatedPredictiveTransition(
                        this, chain, result);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed platform-specific predictive transition inspection",
                    throwable);
        }
        if (closingChangeInfo == null || openingChangeInfo == null
                || closingIndex < 0) {
            return result;
        }

        try {
            Object changesObject = readTransitionInfoChanges(result);
            if (!(changesObject instanceof List<?>)) {
                throw new IllegalStateException("TransitionInfo changes unavailable");
            }
            List<?> changes = (List<?>) changesObject;
            if (changes.size() != 2 || closingIndex >= changes.size()) {
                throw new IllegalStateException("unexpected TransitionInfo change count="
                        + changes.size() + ", closingIndex=" + closingIndex);
            }
            Object closingChange = changes.get(closingIndex);
            Object openingChange = changes.get(1 - closingIndex);
            Integer closingModeObject = readTransitionChangeMode(closingChange);
            Integer openingModeObject = readTransitionChangeMode(openingChange);
            Integer closingFlagsObject = readTransitionChangeFlags(closingChange);
            Integer openingFlagsObject = readTransitionChangeFlags(openingChange);
            if (closingModeObject == null || openingModeObject == null
                    || closingFlagsObject == null || openingFlagsObject == null) {
                throw new IllegalStateException("TransitionInfo changes unavailable");
            }
            int closingMode = closingModeObject;
            int openingMode = openingModeObject;
            int closingFlags = closingFlagsObject;
            int openingFlags = openingFlagsObject;
            Object closingContainer = readField(closingChangeInfo, "mContainer");
            Object openingContainer = readField(openingChangeInfo, "mContainer");
            boolean fixedRotation = isA17FixedRotationCrossActivityPair(
                    closingContainer, openingContainer);
            int requiredOpeningFlags = fixedRotation
                    ? SERVER_FREEFORM_PREPARED_OPENING_FLAGS & ~FLAG_FILLS_TASK
                    : SERVER_FREEFORM_PREPARED_OPENING_FLAGS;
            if ((closingMode != TRANSIT_TO_FRONT && closingMode != TRANSIT_CHANGE)
                    || openingMode != TRANSIT_TO_FRONT
                    || (closingFlags & SERVER_FREEFORM_PREPARED_CLOSING_FLAGS)
                    != SERVER_FREEFORM_PREPARED_CLOSING_FLAGS
                    || (openingFlags & requiredOpeningFlags) != requiredOpeningFlags
                    || (fixedRotation && (closingFlags != SERVER_FREEFORM_PREPARED_CLOSING_FLAGS
                    || openingFlags != requiredOpeningFlags))) {
                throw new IllegalStateException("unexpected prepared roles, closingMode="
                        + closingMode + ", openingMode=" + openingMode
                        + ", closingFlags=0x" + Integer.toHexString(closingFlags)
                        + ", openingFlags=0x" + Integer.toHexString(openingFlags));
            }
            if (fixedRotation) {
                validateFixedRotationCrossActivityChanges(result, closingContainer,
                        openingContainer, closingChange, openingChange);
            }
            Object surfaceAnimator = readField(closingContainer, "mSurfaceAnimator");
            Object openingSurfaceAnimator = readField(
                    openingContainer, "mSurfaceAnimator");
            Object animation = readField(surfaceAnimator, "mAnimation");
            Object openingAnimation = readField(
                    openingSurfaceAnimator, "mAnimation");
            Object closingLeash = readField(surfaceAnimator, "mLeash");
            Object openingLeash = readField(openingSurfaceAnimator, "mLeash");
            Object startTransaction = chain.getArg(3);
            int closingLayer = ((Number) readField(
                    closingContainer, "mLastLayer")).intValue();
            int openingLayer = ((Number) readField(
                    openingContainer, "mLastLayer")).intValue();
            if (animation == null
                    || !BACK_WINDOW_ANIMATION_ADAPTOR.equals(
                    animation.getClass().getName())
                    || openingAnimation == null
                    || !BACK_WINDOW_ANIMATION_ADAPTOR.equals(
                    openingAnimation.getClass().getName())
                    || readField(animation, "mTarget") != closingContainer
                    || readField(openingAnimation, "mTarget") != openingContainer
                    || readField(animation, "mCapturedLeash") != closingLeash
                    || readField(openingAnimation, "mCapturedLeash") != openingLeash
                    || !Boolean.FALSE.equals(readField(animation, "mIsOpen"))
                    || !Boolean.TRUE.equals(readField(openingAnimation, "mIsOpen"))
                    || ((Number) readField(surfaceAnimator,
                    "mAnimationType")).intValue()
                    != SERVER_ANIMATION_TYPE_PREDICTIVE_BACK
                    || ((Number) readField(openingSurfaceAnimator,
                    "mAnimationType")).intValue()
                    != SERVER_ANIMATION_TYPE_PREDICTIVE_BACK
                    || readField(closingContainer, "mLastRelativeToLayer") != null
                    || readField(openingContainer, "mLastRelativeToLayer") != null
                    || !(closingLeash instanceof SurfaceControl)
                    || !(openingLeash instanceof SurfaceControl)
                    || !((SurfaceControl) closingLeash).isValid()
                    || !((SurfaceControl) openingLeash).isValid()
                    || (fixedRotation && closingLeash == openingLeash)
                    || !(startTransaction instanceof SurfaceControl.Transaction)
                    || closingLayer <= openingLayer || openingLayer < 0) {
                throw new IllegalStateException("predictive leashes unavailable"
                        + ", closingAnimation=" + shortObject(animation)
                        + ", openingAnimation=" + shortObject(openingAnimation)
                        + ", closingLeash=" + shortObject(closingLeash)
                        + ", openingLeash=" + shortObject(openingLeash)
                        + ", layers=" + closingLayer + "/" + openingLayer);
            }
            if (closingMode == TRANSIT_TO_FRONT) {
                if (!setTransitionChangeMode(closingChange, TRANSIT_CHANGE)) {
                    throw new IllegalStateException("closing TransitionInfo change unavailable");
                }
            }
            Integer normalizedModeObject = readTransitionChangeMode(closingChange);
            Integer normalizedFlagsObject = readTransitionChangeFlags(closingChange);
            Integer preservedOpeningModeObject = readTransitionChangeMode(openingChange);
            Integer preservedOpeningFlagsObject = readTransitionChangeFlags(openingChange);
            if (normalizedModeObject == null || normalizedFlagsObject == null
                    || preservedOpeningModeObject == null
                    || preservedOpeningFlagsObject == null) {
                throw new IllegalStateException("normalized TransitionInfo change unavailable");
            }
            int normalizedMode = normalizedModeObject;
            int normalizedFlags = normalizedFlagsObject;
            int preservedOpeningMode = preservedOpeningModeObject;
            int preservedOpeningFlags = preservedOpeningFlagsObject;
            if (normalizedMode != TRANSIT_CHANGE
                    || normalizedFlags != closingFlags
                    || preservedOpeningMode != openingMode
                    || preservedOpeningFlags != openingFlags) {
                throw new IllegalStateException("prepared role normalization changed state, mode="
                        + normalizedMode + ", openingMode=" + preservedOpeningMode
                        + ", flags=0x"
                        + Integer.toHexString(closingFlags) + "->0x"
                        + Integer.toHexString(normalizedFlags) + ", openingFlags=0x"
                        + Integer.toHexString(openingFlags) + "->0x"
                        + Integer.toHexString(preservedOpeningFlags));
            }
            SurfaceControl.Transaction transaction =
                    (SurfaceControl.Transaction) startTransaction;
            transaction.setLayer((SurfaceControl) openingLeash, openingLayer);
            transaction.setLayer((SurfaceControl) closingLeash, closingLayer);
            moduleLog(Log.INFO, TAG,
                    "Normalized server cross-activity prepare role"
                            + ", transitionId=" + chain.getArg(4)
                            + ", changeIndex=" + closingIndex
                            + ", mode=" + closingMode + "->" + TRANSIT_CHANGE
                            + ", changed=" + (closingMode == TRANSIT_TO_FRONT)
                            + ", leashLayers=" + closingLayer + "/" + openingLayer
                            + ", flags=0x" + Integer.toHexString(normalizedFlags)
                            + ", openingFlags=0x" + Integer.toHexString(openingFlags)
                            + ", fixedRotation=" + fixedRotation);
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Server cross-activity prepare-role normalization failed;"
                            + " preserving the platform transition",
                    throwable);
        }
        return result;
    }

    protected Object resolveExactFreeformCrossActivityChangeInfo(
            Object targetsObject, Field flagsField) throws Exception {
        if (!(targetsObject instanceof List<?>)
                || ((List<?>) targetsObject).size() != 2) {
            return null;
        }
        Object closingInfo = null;
        Object openingInfo = null;
        Object closingContainer = null;
        Object openingContainer = null;
        for (Object changeInfo : (List<?>) targetsObject) {
            Object container = readField(changeInfo, "mContainer");
            Object activity = container == null ? null : invokeAnyMethod(
                    container, "asActivityRecord", new Object[0]);
            boolean embeddedTaskFragment = activity != container;
            if (embeddedTaskFragment) {
                Object taskFragment = container == null ? null : invokeAnyMethod(
                        container, "asTaskFragment", new Object[0]);
                if (activity != null || taskFragment != container
                        || !Boolean.TRUE.equals(invokeAnyMethod(
                        taskFragment, "isEmbedded", new Object[0]))) {
                    return null;
                }
            }
            int flags = flagsField.getInt(changeInfo);
            if (flags == SERVER_CHANGE_INFO_BACK_TOP && closingInfo == null) {
                closingInfo = changeInfo;
                closingContainer = container;
            } else if (flags == (SERVER_CHANGE_INFO_BACK_BELOW
                    | (embeddedTaskFragment
                    ? SERVER_CHANGE_INFO_CHANGE_YES_ANIMATION : 0))
                    && openingInfo == null) {
                openingInfo = changeInfo;
                openingContainer = container;
            } else {
                return null;
            }
        }
        if (closingContainer == null || openingContainer == null
                || closingContainer == openingContainer
                || !Boolean.TRUE.equals(readField(closingInfo, "mVisible"))
                || !Boolean.FALSE.equals(readField(openingInfo, "mVisible"))
                || !Boolean.TRUE.equals(invokeAnyMethod(
                closingContainer, "isVisibleRequested", new Object[0]))
                || !Boolean.TRUE.equals(invokeAnyMethod(
                openingContainer, "isVisibleRequested", new Object[0]))) {
            return null;
        }
        Object closingTask = invokeAnyMethod(
                closingContainer, "getTask", new Object[0]);
        Object openingTask = invokeAnyMethod(
                openingContainer, "getTask", new Object[0]);
        Object activityType = closingTask == null ? null : invokeAnyMethod(
                closingTask, "getActivityType", new Object[0]);
        Object closingMode = invokeAnyMethod(
                closingContainer, "getWindowingMode", new Object[0]);
        Object openingMode = invokeAnyMethod(
                openingContainer, "getWindowingMode", new Object[0]);
        Object closingBounds = invokeAnyMethod(
                closingContainer, "getBounds", new Object[0]);
        Object openingBounds = invokeAnyMethod(
                openingContainer, "getBounds", new Object[0]);
        boolean exact = closingTask != null
                && closingTask == openingTask
                && activityType instanceof Number
                && ((Number) activityType).intValue() == ACTIVITY_TYPE_STANDARD
                && closingMode instanceof Number
                && openingMode instanceof Number
                && ((Number) closingMode).intValue()
                == ((Number) openingMode).intValue()
                && (((Number) closingMode).intValue() == WINDOWING_MODE_FREEFORM
                || ((Number) closingMode).intValue() == WINDOWING_MODE_FULLSCREEN)
                && closingBounds instanceof Rect
                && !((Rect) closingBounds).isEmpty()
                && openingBounds instanceof Rect;
        if (!exact) {
            return null;
        }
        if (closingBounds.equals(openingBounds)) {
            return closingInfo;
        }
        boolean fixedRotation = isA17FixedRotationCrossActivityPair(
                closingContainer, openingContainer);
        if (systemServerPlatformImpl instanceof SystemServerAndroid17Impl) {
            moduleLog(Log.INFO, TAG, "Inspected Android 17 fixed-rotation prepare candidate"
                    + ", admitted=" + fixedRotation + ", closingBounds=" + closingBounds
                    + ", openingBounds=" + openingBounds);
        }
        return fixedRotation ? closingInfo : null;
    }

    private boolean isA17FixedRotationCrossActivityPair(Object closing, Object opening)
            throws Exception {
        if (!(systemServerPlatformImpl instanceof SystemServerAndroid17Impl)) {
            return false;
        }
        Object closingBounds = invokeAnyMethod(closing, "getBounds", new Object[0]);
        Object openingBounds = invokeAnyMethod(opening, "getBounds", new Object[0]);
        return closingBounds instanceof Rect && openingBounds instanceof Rect
                && ((SystemServerAndroid17Impl) systemServerPlatformImpl)
                .isFixedRotationCrossActivityPair(this, closing, opening,
                        (Rect) closingBounds, (Rect) openingBounds);
    }

    private void validateFixedRotationCrossActivityChanges(Object info,
                                                           Object closing, Object opening,
                                                           Object closingChange, Object openingChange)
            throws Exception {
        Rect taskBounds = (Rect) invokeAnyMethod(closing, "getBounds", new Object[0]);
        Object rootCount = invokeAnyMethod(info, "getRootCount", new Object[0]);
        if (!(rootCount instanceof Number) || ((Number) rootCount).intValue() != 1) {
            throw new IllegalStateException("fixed-rotation prepared root count changed");
        }
        Object root = readTransitionInfoRoot(info, 0);
        Object rootLeash = readTransitionRootLeash(root);
        Object offset = readTransitionRootOffset(root);
        if (!(rootLeash instanceof SurfaceControl) || !((SurfaceControl) rootLeash).isValid()
                || !Integer.valueOf(0).equals(invokeAnyMethod(root, "getDisplayId", new Object[0]))
                || !(offset instanceof Point) || ((Point) offset).x != 0 || ((Point) offset).y != 0) {
            throw new IllegalStateException("fixed-rotation prepared root unavailable");
        }
        Object[] containers = {closing, opening};
        Object[] changes = {closingChange, openingChange};
        for (int index = 0; index < 2; index++) {
            Object container = containers[index];
            Object change = changes[index];
            Object token = invokeAnyMethod(readField(container, "mRemoteToken"),
                    "toWindowContainerToken", new Object[0]);
            Object expectedLeash = invokeAnyMethod(container,
                    index == 0 ? "getSurfaceControl" : "getFixedRotationLeash", new Object[0]);
            Object leash = readTransitionChangeLeash(change);
            if (token == null || !token.equals(invokeAnyMethod(change, "getContainer", new Object[0]))
                    || !(leash instanceof SurfaceControl) || !((SurfaceControl) leash).isValid()
                    || leash != expectedLeash || leash == rootLeash
                    || !readField(container, "mActivityComponent").equals(
                    readTransitionChangeActivityComponent(change))
                    || invokeAnyMethod(change, "getTaskInfo", new Object[0]) != null
                    || invokeAnyMethod(change, "getParent", new Object[0]) != null
                    || invokeAnyMethod(change, "getLastParent", new Object[0]) != null
                    || !taskBounds.equals(readTransitionChangeStartAbsBounds(change))
                    || !taskBounds.equals(readTransitionChangeEndAbsBounds(change))
                    || !Integer.valueOf(0).equals(readTransitionChangeStartDisplayId(change))
                    || !Integer.valueOf(0).equals(readTransitionChangeEndDisplayId(change))) {
                throw new IllegalStateException("fixed-rotation Activity target changed, index=" + index);
            }
        }
        if (readTransitionChangeLeash(closingChange) == readTransitionChangeLeash(openingChange)) {
            throw new IllegalStateException("fixed-rotation Activity leashes are not distinct");
        }
        // Stock Shell uses CHANGE + FLAG_BACK_GESTURE_ANIMATED for the closing
        // runner leash. Keep the opening rotation leash, transforms and flags
        // native; only the existing role/layer correction follows this proof.
    }

    protected void hookScheduleAnimationPrepareTransition(ClassLoader classLoader) {
        try {
            Class<?> builderClass = Class.forName(SCHEDULE_ANIMATION_BUILDER, false,
                    classLoader);
            for (Method method : builderClass.getDeclaredMethods()) {
                if ("prepareTransitionIfNeeded".equals(method.getName())) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_schedule_animation_prepare_transition")
                            .intercept(this::interceptScheduleAnimationPrepareTransition));
                    moduleLog(Log.INFO, TAG, "Hooked ScheduleAnimationBuilder.prepareTransitionIfNeeded");
                    return;
                }
            }
            moduleLog(Log.WARN, TAG, "ScheduleAnimationBuilder.prepareTransitionIfNeeded not found");
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to hook ScheduleAnimationBuilder.prepareTransitionIfNeeded",
                    throwable);
        }
    }

    protected void hookReturnHomeTouchOcclusion(ClassLoader classLoader) {
        try {
            Class<?> windowStateClass = Class.forName(
                    WINDOW_STATE, false, classLoader);
            Method method = windowStateClass.getDeclaredMethod(
                    "getTouchOcclusionMode");
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("server_return_home_touch_occlusion")
                    .intercept(this::allowCommittedReturnHomeTouchThrough));
            moduleLog(Log.INFO, TAG,
                    "Hooked committed return-home touch occlusion ownership");
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to hook committed return-home touch occlusion",
                    throwable);
        }
    }

    protected Object allowCommittedReturnHomeTouchThrough(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!(result instanceof Number)
                || ((Number) result).intValue()
                != TOUCH_OCCLUSION_MODE_USE_OPACITY) {
            return result;
        }
        Object window = chain.getThisObject();
        try {
            Object activity = readField(window, "mActivityRecord");
            if (activity == null
                    || Boolean.TRUE.equals(invokeAnyMethod(activity,
                    "isVisibleRequested", new Object[0]))) {
                return result;
            }
            if (!Boolean.FALSE.equals(invokeAnyMethod(window,
                    "canReceiveTouchInput", new Object[0]))) {
                return result;
            }
            Object task = invokeAnyMethod(activity, "getTask", new Object[0]);
            Object activityType = task == null ? null : invokeAnyMethod(
                    task, "getActivityType", new Object[0]);
            if (!(activityType instanceof Number)
                    || ((Number) activityType).intValue()
                    != ACTIVITY_TYPE_STANDARD) {
                return result;
            }
            Object windowManagerService = readField(window, "mWmService");
            Object activityTaskManager = readField(
                    windowManagerService, "mAtmService");
            Object controller = readField(
                    activityTaskManager, "mBackNavigationController");
            if (controller == null
                    || readIntFieldOrDefault(controller,
                    "mLastBackType", -1) != TYPE_RETURN_TO_HOME) {
                return result;
            }
            boolean pausedByController = Boolean.TRUE.equals(invokeAnyMethod(
                    controller, "shouldPauseTouch", new Object[]{activity}));
            Object animationHandler = readField(controller, "mAnimationHandler");
            Object preparedCloseTransition = readField(
                    animationHandler, "mPrepareCloseTransition");
            boolean preparedCloseTarget = preparedCloseTransition != null
                    && Boolean.TRUE.equals(readField(
                    animationHandler, "mComposed"))
                    && Boolean.TRUE.equals(invokeAnyMethod(animationHandler,
                    "isTarget", new Object[]{activity, Boolean.FALSE}));
            if (!pausedByController && !preparedCloseTarget) {
                return result;
            }
            // Before onTransactionReady(), shouldPauseTouch() owns the exact composed target.
            // Once the matching close transition is prepared, AOSP deliberately makes that
            // method false because mPrepareCloseTransition is non-null; the handler's immutable
            // prepared-transition/target relationship then owns the same close until finish.
            // The Surface remains visible for the launcher animation in both phases, so
            // USE_OPACITY would make this already non-touchable surface block Launcher input.
            moduleLog(Log.INFO, TAG,
                    "Allowed Launcher touch through committed predictive CLOSE"
                            + ", window=" + shortObject(window)
                            + ", activity=" + shortObject(activity)
                            + ", task=" + shortObject(task)
                            + ", phase=" + (pausedByController
                            ? "controllerPaused" : "preparedClose")
                            + ", preparedClose="
                            + shortObject(preparedCloseTransition)
                            + ", backType=" + TYPE_RETURN_TO_HOME);
            return Integer.valueOf(TOUCH_OCCLUSION_MODE_ALLOW);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed to verify committed return-home touch ownership"
                            + ", window=" + shortObject(window),
                    throwable);
            return result;
        }
    }

    /**
     * Android 17 MiuiHome keeps full-height native edge windows, so shrinking only the SystemUI
     * spy leaves rejected touches inside the launcher. MiuiHome already redirects a DOWN that
     * lands in the system gesture exclusion region to the window below; publishing the rows
     * outside the configured band as exclusion lets Xiaomi's own redirect deliver them.
     */
    protected void hookSideTriggerExclusion(ClassLoader classLoader,
                                            Set<String> existingHookIds) {
        if (Build.VERSION.SDK_INT < 37) return;
        try {
            Class<?> displayContentClass = Class.forName(DISPLAY_CONTENT, false, classLoader);
            Method calculate = displayContentClass.getDeclaredMethod(
                    "calculateSystemGestureExclusion", Region.class, Region.class);
            Method update = displayContentClass.getDeclaredMethod(
                    "updateSystemGestureExclusion");
            Field frameLeft = displayContentClass.getDeclaredField("mSystemGestureFrameLeft");
            Field frameRight = displayContentClass.getDeclaredField("mSystemGestureFrameRight");
            Field displayId = displayContentClass.getDeclaredField("mDisplayId");
            if (calculate.getReturnType() != boolean.class
                    || update.getReturnType() != boolean.class
                    || frameLeft.getType() != Rect.class
                    || frameRight.getType() != Rect.class
                    || displayId.getType() != int.class) {
                throw new IllegalStateException("Unexpected DisplayContent exclusion shape");
            }
            update.setAccessible(true);
            frameLeft.setAccessible(true);
            frameRight.setAccessible(true);
            displayId.setAccessible(true);
            sideTriggerExclusionUpdateMethod = update;
            sideTriggerFrameLeftField = frameLeft;
            sideTriggerFrameRightField = frameRight;
            sideTriggerDisplayIdField = displayId;
            ensureSideTriggerExclusionPreferences();
            if (!existingHookIds.contains(SERVER_SIDE_TRIGGER_EXCLUSION)) {
                calculate.setAccessible(true);
                recordHookHandle(hook(calculate)
                        .setId(SERVER_SIDE_TRIGGER_EXCLUSION)
                        .intercept(this::appendSideTriggerExclusion));
                moduleLog(Log.INFO, TAG, "Hooked side trigger exclusion publication"
                        + ", height=" + sideTriggerHeightPercent
                        + "%, position=" + sideTriggerPositionPercent + "%");
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Side trigger exclusion unavailable; preserving native exclusion",
                    throwable);
        }
    }

    protected Object appendSideTriggerExclusion(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        Object displayContent = chain.getThisObject();
        try {
            if (sideTriggerExclusionWindowManager.get() == null) {
                sideTriggerExclusionWindowManager = new WeakReference<>(
                        readField(displayContent, "mWmService"));
            }
            if (sideTriggerExclusionPreferences == null) {
                // This runs on every layout pass under mGlobalLock; retry a failed remote
                // preference connection occasionally rather than on each traversal.
                long now = SystemClock.uptimeMillis();
                if (now >= sideTriggerExclusionPreferenceRetryUptime) {
                    sideTriggerExclusionPreferenceRetryUptime =
                            now + SIDE_TRIGGER_PREFERENCE_RETRY_MS;
                    ensureSideTriggerExclusionPreferences();
                }
            }
            int heightPercent = sideTriggerHeightPercent;
            int positionPercent = sideTriggerPositionPercent;
            if (heightPercent == PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT
                    && positionPercent
                    == PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT) {
                if (!sideTriggerPublishedStates.isEmpty()) {
                    int displayId = sideTriggerDisplayIdField.getInt(displayContent);
                    if (sideTriggerPublishedStates.remove(displayId) != null) {
                        moduleLog(Log.INFO, TAG, "Restored native side trigger exclusion"
                                + ", display=" + displayId);
                    }
                }
                return result;
            }
            // Xiaomi's interceptGestureExclusion widens these frames to the live GestureStub
            // width during the traversal that just completed, so they cover the edge windows.
            Rect left = (Rect) sideTriggerFrameLeftField.get(displayContent);
            Rect right = (Rect) sideTriggerFrameRightField.get(displayContent);
            Region restricted = (Region) chain.getArg(0);
            Region unrestricted = (Region) chain.getArg(1);
            int[] leftBand = appendOutsideSideTriggerBand(left, heightPercent, positionPercent,
                    restricted, unrestricted);
            int[] rightBand = appendOutsideSideTriggerBand(right, heightPercent,
                    positionPercent, restricted, unrestricted);
            reportSideTriggerExclusion(sideTriggerDisplayIdField.getInt(displayContent),
                    heightPercent, positionPercent, left, leftBand, right, rightBand);
            sideTriggerExclusionFailureLogged = false;
        } catch (Throwable throwable) {
            if (!sideTriggerExclusionFailureLogged) {
                sideTriggerExclusionFailureLogged = true;
                moduleLog(Log.WARN, TAG,
                        "Failed to publish side trigger exclusion; preserving native region",
                        throwable);
            }
        }
        return result;
    }

    /** Returns the accepted band for this frame, or null when the frame stays native. */
    private static int[] appendOutsideSideTriggerBand(Rect frame, int heightPercent,
                                                      int positionPercent, Region restricted,
                                                      Region unrestricted) {
        if (frame == null || frame.isEmpty()) return null;
        int[] band = SideTriggerExclusionBand.resolve(frame.top, frame.bottom,
                heightPercent, positionPercent);
        if (band == null) return null;
        // The limit-free unrestricted copy must carry the same strips, otherwise a listener
        // that prefers it whenever an app was clipped would lose the configured band.
        for (Region region : new Region[]{restricted, unrestricted}) {
            if (region == null) continue;
            if (band[0] > frame.top) {
                region.op(frame.left, frame.top, frame.right, band[0], Region.Op.UNION);
            }
            if (band[1] < frame.bottom) {
                region.op(frame.left, band[1], frame.right, frame.bottom, Region.Op.UNION);
            }
        }
        return band;
    }

    /**
     * The calculation reruns on every layout pass, so detailed logs follow changes in the
     * published geometry (preferences, rotation, GestureStub width) rather than each traversal.
     */
    private void reportSideTriggerExclusion(int displayId, int heightPercent,
                                            int positionPercent, Rect left, int[] leftBand,
                                            Rect right, int[] rightBand) {
        int[] state = {heightPercent, positionPercent,
                left == null ? 0 : left.left, left == null ? 0 : left.top,
                left == null ? 0 : left.right, left == null ? 0 : left.bottom,
                leftBand == null ? -1 : leftBand[0], leftBand == null ? -1 : leftBand[1],
                right == null ? 0 : right.left, right == null ? 0 : right.top,
                right == null ? 0 : right.right, right == null ? 0 : right.bottom,
                rightBand == null ? -1 : rightBand[0], rightBand == null ? -1 : rightBand[1]};
        int[] previous = sideTriggerPublishedStates.put(displayId, state);
        if (Arrays.equals(previous, state)) return;
        moduleLog(Log.INFO, TAG, "Published side trigger exclusion"
                + ", display=" + displayId
                + ", height=" + heightPercent
                + "%, position=" + positionPercent
                + "%, leftFrame=" + left
                + ", leftBand=" + describeSideTriggerBand(leftBand)
                + ", rightFrame=" + right
                + ", rightBand=" + describeSideTriggerBand(rightBand));
    }

    private static String describeSideTriggerBand(int[] band) {
        return band == null ? "native" : "[" + band[0] + "," + band[1] + ")";
    }

    protected void ensureSideTriggerExclusionPreferences() {
        synchronized (sideTriggerExclusionPreferenceLock) {
            if (sideTriggerExclusionPreferences != null) return;
            try {
                SharedPreferences preferences = getRemotePreferences(
                        PredictiveBackPreferences.GROUP);
                preferences.registerOnSharedPreferenceChangeListener(
                        sideTriggerExclusionPreferenceListener);
                sideTriggerExclusionPreferences = preferences;
                readSideTriggerExclusionPreferences(preferences);
                sideTriggerExclusionPreferenceFailureLogged = false;
            } catch (Throwable throwable) {
                if (!sideTriggerExclusionPreferenceFailureLogged) {
                    sideTriggerExclusionPreferenceFailureLogged = true;
                    moduleLog(Log.WARN, TAG,
                            "Side trigger preferences unavailable; preserving native exclusion",
                            throwable);
                }
            }
        }
    }

    private void readSideTriggerExclusionPreferences(SharedPreferences preferences) {
        sideTriggerHeightPercent = preferences.getInt(
                PredictiveBackPreferences.KEY_GESTURE_TRIGGER_HEIGHT_PERCENT,
                PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT);
        sideTriggerPositionPercent = preferences.getInt(
                PredictiveBackPreferences.KEY_GESTURE_TRIGGER_POSITION_PERCENT,
                PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT);
    }

    protected void releaseSideTriggerExclusionPreferenceListener() {
        synchronized (sideTriggerExclusionPreferenceLock) {
            SharedPreferences preferences = sideTriggerExclusionPreferences;
            sideTriggerExclusionPreferences = null;
            if (preferences != null) {
                try {
                    preferences.unregisterOnSharedPreferenceChangeListener(
                            sideTriggerExclusionPreferenceListener);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Re-publishes exclusion on WMS's own handler. Preference callbacks may hold the provider's
     * lock while WMS threads read these values under mGlobalLock, so never take the global lock
     * from the callback thread itself.
     */
    protected void requestSideTriggerExclusionRefresh(String reason) {
        Object windowManager = sideTriggerExclusionWindowManager.get();
        Method update = sideTriggerExclusionUpdateMethod;
        if (windowManager == null || update == null) return;
        try {
            Handler handler = (Handler) readField(windowManager, "mH");
            Object lock = readField(windowManager, "mGlobalLock");
            Object root = readField(windowManager, "mRoot");
            Method forAllDisplays = root.getClass().getMethod(
                    "forAllDisplays", Consumer.class);
            forAllDisplays.setAccessible(true);
            handler.post(() -> {
                try {
                    int[] displays = {0, 0};
                    synchronized (lock) {
                        forAllDisplays.invoke(root, (Consumer<Object>) display -> {
                            try {
                                displays[0]++;
                                if (Boolean.TRUE.equals(update.invoke(display))) displays[1]++;
                            } catch (Throwable throwable) {
                                moduleLog(Log.WARN, TAG,
                                        "Failed to refresh side trigger exclusion"
                                                + ", display=" + shortObject(display),
                                        throwable);
                            }
                        });
                    }
                    moduleLog(Log.INFO, TAG, "Refreshed side trigger exclusion"
                            + ", reason=" + reason
                            + ", displays=" + displays[0]
                            + ", dispatched=" + displays[1]);
                } catch (Throwable throwable) {
                    moduleLog(Log.WARN, TAG, "Failed to refresh side trigger exclusion"
                            + ", reason=" + reason, throwable);
                }
            });
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Cannot schedule side trigger exclusion refresh"
                    + ", reason=" + reason, throwable);
        }
    }

    protected Object prepareOpeningTaskFragment(XposedInterface.Chain chain) throws Throwable {
        Object adaptor = chain.getThisObject();
        try {
            SystemServerPlatformImpl implementation = systemServerPlatformImpl;
            if (implementation != null
                    && !implementation.shouldForceOpeningTaskFragmentAtAnimationStart()) {
                return chain.proceed();
            }
            Object target = readField(adaptor, "mTarget");
            Object isOpen = readField(adaptor, "mIsOpen");
            Object transaction = chain.getArg(1);
            if (Boolean.TRUE.equals(isOpen) && transaction instanceof SurfaceControl.Transaction) {
                ensureOpenTaskFragmentVisible(target, (SurfaceControl.Transaction) transaction);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to prepare opening TaskFragment", throwable);
        }
        return chain.proceed();
    }

    protected Object restoreA17OpeningSurfaceVisibility(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            ClassLoader loader = chain.getExecutable().getDeclaringClass().getClassLoader();
            requireSystemServerPlatformImpl(loader)
                    .restoreOpeningSurfaceVisibility(this, chain);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Failed Android 17 opening-surface visibility recovery;"
                            + " preserving native preview",
                    throwable);
        }
        return result;
    }

    protected void ensureOpenTaskFragmentVisible(Object target, SurfaceControl.Transaction transaction) {
        if (target == null || transaction == null) {
            return;
        }
        try {
            // BackWindowAnimationAdaptor.mTarget is a WindowContainer. Match its native
            // createRemoteAnimationTarget() conversion: Task itself is a TaskFragment,
            // while getTaskFragment() is only the ActivityRecord/WindowState parent lookup.
            Object taskFragment = invokeAnyMethod(target, "asTaskFragment", new Object[0]);
            if (taskFragment == null) {
                return;
            }
            try {
                invokeAnyMethod(taskFragment, "updateOrganizedTaskFragmentSurface",
                        new Object[0]);
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, TAG, "Open TaskFragment update surface failed, target="
                        + shortObject(target) + ", taskFragment=" + shortObject(taskFragment)
                        + ", error=" + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            }
            Object surface = readFieldOrNull(taskFragment, "mSurfaceControl");
            if (surface instanceof SurfaceControl) {
                invokeAnyMethod(transaction, "show", new Object[]{surface});
                moduleLog(Log.INFO, TAG, "Forced opening TaskFragment visible for predictive back"
                        + ", target=" + shortObject(target)
                        + ", taskFragment=" + shortObject(taskFragment)
                        + ", surface=" + shortObject(surface));
            } else {
                moduleLog(Log.WARN, TAG, "Open TaskFragment has no SurfaceControl"
                        + ", target=" + shortObject(target)
                        + ", taskFragment=" + shortObject(taskFragment)
                        + ", surface=" + shortObject(surface));
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG, "Failed to force opening TaskFragment visible, target="
                    + shortObject(target), throwable);
        }
    }

    protected Object interceptScheduleAnimationPrepareTransition(XposedInterface.Chain chain)
            throws Throwable {
        ClassLoader loader = chain.getExecutable().getDeclaringClass().getClassLoader();
        return requireSystemServerPlatformImpl(loader)
                .interceptScheduleAnimationPrepareTransition(this, chain);
    }

    final Object readSystemServerPlatformFieldOrNull(Object target, String name) {
        return readFieldOrNull(target, name);
    }

    final void writeSystemServerPlatformField(Object target, String name, Object value)
            throws Exception {
        writeField(target, name, value);
    }

    final Object invokeSystemServerPlatformMethod(Object target, String name,
                                                  Object... args) throws Exception {
        return invokeAnyMethod(target, name, args);
    }

    final Object readSystemServerPlatformTransitionChanges(Object info) {
        return readTransitionInfoChanges(info);
    }

    final Integer readSystemServerPlatformTransitionChangeMode(Object change) {
        return readTransitionChangeMode(change);
    }

    final Integer readSystemServerPlatformTransitionChangeFlags(Object change) {
        return readTransitionChangeFlags(change);
    }

    final Object readSystemServerPlatformTransitionChangeTaskInfo(Object change) {
        return readTransitionChangeTaskInfo(change);
    }

    final Object readSystemServerPlatformTransitionChangeParent(Object change) {
        return readTransitionChangeParent(change);
    }

    final Object readSystemServerPlatformTransitionChangeLastParent(Object change) {
        return readTransitionChangeLastParent(change);
    }

    final Object readSystemServerPlatformTransitionChangeLeash(Object change) {
        return readTransitionChangeLeash(change);
    }

    final Object readSystemServerPlatformTransitionChangeStartAbsBounds(Object change) {
        return readTransitionChangeStartAbsBounds(change);
    }

    final Object readSystemServerPlatformTransitionChangeEndAbsBounds(Object change) {
        return readTransitionChangeEndAbsBounds(change);
    }

    final boolean setSystemServerPlatformTransitionChangeMode(Object change, int mode) {
        return setTransitionChangeMode(change, mode);
    }

    final boolean readSystemServerPlatformWindowFlag(String methodName,
                                                     ClassLoader preferredLoader,
                                                     boolean fallback) {
        return readWindowFlag(methodName, preferredLoader, fallback);
    }

    final String describeSystemServerPlatformObject(Object value) {
        return shortObject(value);
    }

    final void logSystemServerPlatform(int priority, String message) {
        moduleLog(priority, TAG, message);
    }

    final void logSystemServerPlatform(int priority, String message,
                                       Throwable throwable) {
        moduleLog(priority, TAG, message, throwable);
    }

    protected boolean isExactFreeformCrossActivityPrepare(
            XposedInterface.Chain chain, Object builder) throws Exception {
        Object visibleArg = chain.getArg(0);
        Object close = chain.getArg(1);
        Object openArg = chain.getArg(2);
        if (!(visibleArg instanceof Object[]) || !(openArg instanceof Object[])) {
            return false;
        }
        Object[] visibleOpen = (Object[]) visibleArg;
        Object[] promotedOpen = (Object[]) openArg;
        if (visibleOpen.length != 1 || promotedOpen.length != 1
                || close == null || promotedOpen[0] == null) {
            return false;
        }
        Object closeActivity = readField(builder, "mCloseTarget");
        Object openActivity = visibleOpen[0];
        if (closeActivity == null || openActivity == null
                || invokeAnyMethod(closeActivity,
                "asActivityRecord", new Object[0]) != closeActivity
                || invokeAnyMethod(openActivity,
                "asActivityRecord", new Object[0]) != openActivity
                || closeActivity == openActivity) {
            return false;
        }
        Object closeTaskFragment = invokeAnyMethod(
                closeActivity, "getTaskFragment", new Object[0]);
        Object openTaskFragment = invokeAnyMethod(
                openActivity, "getTaskFragment", new Object[0]);
        if (closeTaskFragment != null && !Boolean.TRUE.equals(invokeAnyMethod(
                closeTaskFragment, "isEmbedded", new Object[0]))) {
            closeTaskFragment = null;
        }
        if (openTaskFragment != null && !Boolean.TRUE.equals(invokeAnyMethod(
                openTaskFragment, "isEmbedded", new Object[0]))) {
            openTaskFragment = null;
        }
        boolean promoted = closeTaskFragment != openTaskFragment;
        Object expectedClose = promoted && closeTaskFragment != null
                ? closeTaskFragment : closeActivity;
        Object expectedOpen = promoted && openTaskFragment != null
                ? openTaskFragment : openActivity;
        if (close != expectedClose || promotedOpen[0] != expectedOpen) {
            return false;
        }
        Object closeTask = invokeAnyMethod(
                closeActivity, "getTask", new Object[0]);
        Object openTask = invokeAnyMethod(
                openActivity, "getTask", new Object[0]);
        Object activityType = closeTask == null ? null : invokeAnyMethod(
                closeTask, "getActivityType", new Object[0]);
        Object closeMode = invokeAnyMethod(
                closeActivity, "getWindowingMode", new Object[0]);
        Object openMode = invokeAnyMethod(
                openActivity, "getWindowingMode", new Object[0]);
        Object closeBounds = invokeAnyMethod(
                closeActivity, "getBounds", new Object[0]);
        Object openBounds = invokeAnyMethod(
                openActivity, "getBounds", new Object[0]);
        boolean exact = closeTask != null
                && closeTask == openTask
                && activityType instanceof Number
                && ((Number) activityType).intValue() == ACTIVITY_TYPE_STANDARD
                && closeMode instanceof Number
                && openMode instanceof Number
                && ((Number) closeMode).intValue()
                == ((Number) openMode).intValue()
                && (((Number) closeMode).intValue() == WINDOWING_MODE_FREEFORM
                || ((Number) closeMode).intValue() == WINDOWING_MODE_FULLSCREEN)
                && closeBounds instanceof Rect
                && !((Rect) closeBounds).isEmpty()
                && closeBounds.equals(openBounds)
                && Boolean.TRUE.equals(invokeAnyMethod(
                closeActivity, "isVisibleRequested", new Object[0]))
                && Boolean.FALSE.equals(invokeAnyMethod(
                openActivity, "isVisibleRequested", new Object[0]))
                && Boolean.FALSE.equals(readField(
                openActivity, "mLaunchTaskBehind"));
        if (!exact) {
            return false;
        }
        Object displayContent = readField(openActivity, "mDisplayContent");
        int fixedRotation = ((Number) invokeAnyMethod(displayContent,
                "rotationForActivityInDifferentOrientation",
                new Object[]{openActivity})).intValue();
        if (fixedRotation != -1) {
            moduleLog(Log.INFO, TAG, "Treating fixed-rotation cross-activity prepare as non-exact"
                    + ", rotation=" + fixedRotation
                    + ", close=" + shortObject(closeActivity)
                    + ", open=" + shortObject(openActivity));
            return false;
        }
        return true;
    }

}
