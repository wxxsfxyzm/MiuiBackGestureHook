package dev.codex.miuibackgesturehook.hooks.systemserver;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;

import dev.codex.miuibackgesturehook.BuildConfig;
import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;

@Hooker.XposedHooker(
        name = "SystemServerBackTransitionHook",
        targets = "system",
        order = 100)
public final class SystemServerBackTransitionHook extends HookerBridge {
    private static final String TAG = "SystemServerBackTransitionHook";
    private static final String BUILD_MARK =
            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    private static final int ANDROID_17_API_LEVEL = 37;
    private static final int TRANSIT_PREDICTIVE_BACK = 13;
    private static final int TRANSIT_TO_FRONT = 3;
    private static final int TRANSIT_CHANGE = 6;
    private static final int FLAG_FILLS_TASK = 1 << 10;
    private static final int FLAG_IS_OCCLUDED = 1 << 15;
    private static final int FLAG_BACK_GESTURE_ANIMATED = 1 << 17;
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int TOUCH_OCCLUSION_MODE_USE_OPACITY = 1;
    private static final int TOUCH_OCCLUSION_MODE_ALLOW = 2;
    private static final int TYPE_RETURN_TO_HOME = 1;
    private static final String MIUI_SIDEBAR_BOUNDS = "sidebar_bounds";
    private static final float MIUI_SIDEBAR_EXCLUSION_PADDING_DP = 8.0f;

    @Override
    public void onPackageLoad() {
        start();
        try {
            ClassLoader serverLoader = requireSystemServerClassLoader();
            ensureSystemServerImplementationSelected(serverLoader);
            hookBackNavigationDoneCleanup(serverLoader);
            hookSecuritySidebarTransientBars(serverLoader);
            hookBackWindowStartAnimation(serverLoader);
            hookA17OpeningSurfaceVisibility(serverLoader);
            if (hasSystemServerImplementationSelected()) {
                hookFreeformCrossActivityPrepareRole(serverLoader);
                hookScheduleAnimationPrepareTransition(serverLoader);
            }
            hookReturnHomeTouchOcclusion(serverLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, "SystemServerBackTransitionHook",
                    "Failed to install system back-transition behavior", throwable);
        }
    }

    private final AtomicBoolean systemServerImplementationSelected = new AtomicBoolean();
    private final AtomicBoolean runtimeStarted = new AtomicBoolean();

    public void start() {
        if (runtimeStarted.compareAndSet(false, true)) {
            log(Log.INFO, TAG, "Starting system_server hook state, build="
                    + BUILD_MARK + ", process=" + packageName);
        }
    }

    public ClassLoader requireSystemServerClassLoader() {
        ClassLoader serverLoader = findSystemServerClassLoader(classLoader);
        if (serverLoader == null) {
            throw new IllegalStateException("system_server ClassLoader unavailable");
        }
        return serverLoader;
    }

    public synchronized void ensureSystemServerImplementationSelected(
            ClassLoader serverLoader) {
        if (systemServerImplementationSelected.get()) {
            return;
        }
        try {
            selectSystemServerImplementation(serverLoader);
            systemServerImplementationSelected.set(true);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to select system_server platform implementation; "
                            + "version-specific hooks stay disabled", throwable);
        }
    }

    public boolean hasSystemServerImplementationSelected() {
        return systemServerImplementationSelected.get();
    }
    static final String BACK_NAVIGATION_CONTROLLER =
            "com.android.server.wm.BackNavigationController";
    static final String BACK_WINDOW_ANIMATION_ADAPTOR =
            "com.android.server.wm.BackNavigationController$AnimationHandler$BackWindowAnimationAdaptor";
    static final String SCHEDULE_ANIMATION_BUILDER =
            "com.android.server.wm.BackNavigationController$AnimationHandler$ScheduleAnimationBuilder";
    static final String WINDOW_STATE = "com.android.server.wm.WindowState";
    static final String DISPLAY_POLICY = "com.android.server.wm.DisplayPolicy";

    private static final int SERVER_CHANGE_INFO_BACK_TOP = 128;
    private static final int SERVER_CHANGE_INFO_BACK_BELOW = 256;
    private static final int SERVER_CHANGE_INFO_CHANGE_YES_ANIMATION = 16;
    private static final int SERVER_ANIMATION_TYPE_PREDICTIVE_BACK = 256;
    private static final int SERVER_TRANSITION_INFO_BACK_TOP = 0x08000000;
    private static final int SERVER_FREEFORM_PREPARED_CLOSING_FLAGS =
            SERVER_TRANSITION_INFO_BACK_TOP | FLAG_BACK_GESTURE_ANIMATED | FLAG_FILLS_TASK;
    private static final int SERVER_FREEFORM_PREPARED_OPENING_FLAGS =
            FLAG_BACK_GESTURE_ANIMATED | FLAG_FILLS_TASK | FLAG_IS_OCCLUDED;
    private volatile Field serverTransitionChangeInfoFlagsField;
    private static final String MIUI_SECURITY_CENTER_PACKAGE =
            "com.miui.securitycenter";
    private static final String MIUI_SECURITY_GAME_SIDEBAR_HANDLE_TITLE =
            "FloatAssistantView";
    private static final String MIUI_SECURITY_VIDEO_SIDEBAR_HANDLE_TITLE =
            "VtbAssistantView";
    private static final int TYPE_DISPLAY_OVERLAY = 2026;
    private static final long SIDEBAR_GESTURE_MAX_AGE_MS = 2000L;
    private final SystemServerImpl systemServerImpl = new SystemServerImpl();

    private void hookSecuritySidebarTransientBars(ClassLoader classLoader) {
        try {
            Class<?> policyClass = Class.forName(DISPLAY_POLICY, false, classLoader);
            int hooked = 0;
            int installed = 0;
            for (Method method : policyClass.getDeclaredMethods()) {
                if (!"requestTransientBars".equals(method.getName())) {
                    continue;
                }
                int overload = hooked++;
                try {
                    method.setAccessible(true);
                    hook(method)
                            .intercept(this::interceptSecuritySidebarTransientBars);
                    installed++;
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG,
                            "Failed to hook security-sidebar transient bars overload " + overload,
                            throwable);
                }
            }
            if (hooked == 0) {
                log(Log.WARN, TAG, "DisplayPolicy.requestTransientBars not found");
            } else {
                log(Log.INFO, TAG, "Hooked DisplayPolicy transient-bars overloads="
                        + hooked + ", installed=" + installed);
            }
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook security-sidebar transient bars", throwable);
        }
    }

    private Object interceptSecuritySidebarTransientBars(XposedInterface.Chain chain)
            throws Throwable {
        if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL) {
            if (isAndroid17SecuritySidebarTransientRequest(chain)) {
                log(Log.INFO, TAG,
                        "Blocked Android 17 transient bars from security sidebar handle"
                                + ", overload=" + chain.getExecutable().toGenericString());
                return null;
            }
        }
        if (isSidebarTransientGesture(chain.getThisObject())) {
            log(Log.INFO, TAG, "Blocked transient bars from sidebar bounds"
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
                log(Log.INFO, TAG, "Blocked transient bars from security sidebar"
                        + ", target=" + shortObject(argument));
                return null;
            }
        }
        if (chain.getArgs().size() == 2
                && chain.getArg(0) == null
                && Boolean.FALSE.equals(chain.getArg(1))) {
            Object navigationBar;
            try {
                navigationBar = readField(chain.getThisObject(), "mNavigationBar");
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Cannot inspect AOSP side transient-bars target", throwable);
                return chain.proceed();
            }
            if (navigationBar == null) {
                log(Log.WARN, TAG,
                        "Cannot restore AOSP side transient bars: NavigationBar is absent");
                return chain.proceed();
            }
            Object[] args = chain.getArgs().toArray();
            args[0] = navigationBar;
            args[1] = Boolean.TRUE;
            log(Log.INFO, TAG, "Restored AOSP side transient-bars target"
                    + ", target=" + shortObject(navigationBar));
            return chain.proceed(args);
        }
        return chain.proceed();
    }

    private boolean isAndroid17SecuritySidebarTransientRequest(
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
                    log(Log.INFO, TAG,
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
            log(Log.WARN, TAG,
                    "Failed to inspect Android 17 security sidebar handle gesture",
                    throwable);
        }
        return false;
    }

    private boolean isAndroid17SecuritySidebarHandleWindow(
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

    private boolean isSidebarTransientGesture(Object displayPolicy) {
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
                            log(Log.INFO, TAG, "Matched sidebar transient gesture"
                                    + ", pointer=" + pointer + ", x=" + x + ", y=" + y
                                    + ", bounds=" + rect);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect sidebar transient gesture", throwable);
        }
        return false;
    }

    private ClassLoader findSystemServerClassLoader(ClassLoader preferred) {
        ClassLoader[] candidates = new ClassLoader[]{
                preferred,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                SystemServerBackTransitionHook.class.getClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Class.forName(BACK_NAVIGATION_CONTROLLER, false, candidate);
                log(Log.INFO, TAG, "Resolved system_server classloader: " + candidate);
                return candidate;
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "System_server classloader candidate failed: "
                        + candidate + ", error=" + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            }
        }
        return null;
    }

    private synchronized void selectSystemServerImplementation(ClassLoader classLoader)
            throws Exception {
        systemServerImpl.select(classLoader);
        log(Log.INFO, TAG, "Selected system_server platform implementation="
                + systemServerImpl.name() + ", classLoader=" + classLoader);
    }

    private SystemServerImpl requireSystemServerImplementation(
            ClassLoader classLoader) throws Exception {
        selectSystemServerImplementation(classLoader);
        return systemServerImpl;
    }

    private void hookBackNavigationDoneCleanup(ClassLoader classLoader) {
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
                hook(method)
                        .intercept(this::cleanupSkippedRemoteAnimationOnNavigationDone);
                log(Log.INFO, TAG, "Hooked BackNavigationController navigation-done cleanup"
                        + ", method=" + method.getName());
                return;
            }
            log(Log.WARN, TAG, "BackNavigationController.onBackNavigationDone not found");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook BackNavigationController navigation-done cleanup",
                    throwable);
        }
    }

    private Object cleanupSkippedRemoteAnimationOnNavigationDone(XposedInterface.Chain chain)
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
                        log(Log.INFO, TAG,
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
            log(Log.INFO, TAG, "Cleared committed remote-only predictive-back animation"
                    + " after skipped prepare transition");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed committed remote-only predictive-back cleanup",
                    throwable);
        }
        return result;
    }

    private void hookBackWindowStartAnimation(ClassLoader classLoader) {
        try {
            Class<?> adaptorClass = Class.forName(BACK_WINDOW_ANIMATION_ADAPTOR,
                    false, classLoader);
            for (Method method : adaptorClass.getDeclaredMethods()) {
                if ("startAnimation".equals(method.getName())
                        && method.getParameterCount() == 4) {
                    method.setAccessible(true);
                    hook(method)
                            .intercept(this::prepareOpeningTaskFragment);
                    log(Log.INFO, TAG, "Hooked BackWindowAnimationAdaptor.startAnimation");
                    return;
                }
            }
            log(Log.WARN, TAG, "BackWindowAnimationAdaptor.startAnimation not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook BackWindowAnimationAdaptor.startAnimation",
                    throwable);
        }
    }

    private void hookA17OpeningSurfaceVisibility(ClassLoader classLoader) {
        try {
            SystemServerImpl implementation =
                    requireSystemServerImplementation(classLoader);
            Method method = implementation.openingSurfaceVisibilityMethod(classLoader);
            if (method == null) {
                return;
            }
            method.setAccessible(true);
            hook(method)
                    .intercept(this::restoreA17OpeningSurfaceVisibility);
            log(Log.INFO, TAG,
                    "Hooked Android 17 opening-surface visibility recovery");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to hook Android 17 opening-surface visibility recovery",
                    throwable);
        }
    }

    private void hookFreeformCrossActivityPrepareRole(ClassLoader classLoader) {
        serverTransitionChangeInfoFlagsField = null;
        try {
            Class<?> transitionClass = Class.forName(
                    "com.android.server.wm.Transition", false, classLoader);
            if (!initializeFreeformPrepareRoleReflection(classLoader)) {
                return;
            }
            SystemServerImpl implementation =
                    requireSystemServerImplementation(classLoader);
            Method method = implementation.calculateTransitionInfoMethod(transitionClass);
            hook(method)
                    .intercept(this::normalizeFreeformCrossActivityTransitionInfo);
            log(Log.INFO, TAG,
                    "Hooked server cross-activity predictive-back prepare role"
                            + " normalization, platform=" + implementation.name()
                            + ", parameterCount=" + method.getParameterCount());
        } catch (Throwable throwable) {
            serverTransitionChangeInfoFlagsField = null;
            log(Log.ERROR, TAG,
                    "Failed to hook server cross-activity predictive-back prepare role",
                    throwable);
        }
    }

    private boolean initializeFreeformPrepareRoleReflection(ClassLoader classLoader) {
        try {
            Class<?> changeInfoClass = Class.forName(
                    "com.android.server.wm.Transition$ChangeInfo", false, classLoader);
            Field flags = changeInfoClass.getDeclaredField("mFlags");
            flags.setAccessible(true);
            serverTransitionChangeInfoFlagsField = flags;
            return true;
        } catch (Throwable throwable) {
            serverTransitionChangeInfoFlagsField = null;
            log(Log.ERROR, TAG,
                    "Server cross-activity prepare-role reflection unavailable", throwable);
            return false;
        }
    }

    private Object normalizeFreeformCrossActivityTransitionInfo(
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
            log(Log.WARN, TAG,
                    "Failed to inspect server cross-activity prepared targets;"
                            + " preserving the platform transition",
                    throwable);
        }
        Object result = chain.proceed();
        try {
            systemServerImpl.inspectCalculatedPredictiveTransition(
                    this, chain, result);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed platform-specific predictive transition inspection",
                    throwable);
        }
        if (closingChangeInfo == null || openingChangeInfo == null) {
            return result;
        }

        try {
            List<?> changesObject = readTransitionInfoChanges(result);
            if (changesObject == null) {
                throw new IllegalStateException("TransitionInfo changes unavailable");
            }
            if (changesObject.size() != 2 || closingIndex >= changesObject.size()) {
                throw new IllegalStateException("unexpected TransitionInfo change count="
                        + changesObject.size() + ", closingIndex=" + closingIndex);
            }
            Object closingChange = changesObject.get(closingIndex);
            Object openingChange = changesObject.get(1 - closingIndex);
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
            if ((closingMode != TRANSIT_TO_FRONT && closingMode != TRANSIT_CHANGE)
                    || openingMode != TRANSIT_TO_FRONT
                    || (closingFlags & SERVER_FREEFORM_PREPARED_CLOSING_FLAGS)
                    != SERVER_FREEFORM_PREPARED_CLOSING_FLAGS
                    || (openingFlags & SERVER_FREEFORM_PREPARED_OPENING_FLAGS)
                    != SERVER_FREEFORM_PREPARED_OPENING_FLAGS) {
                throw new IllegalStateException("unexpected prepared roles, closingMode="
                        + closingMode + ", openingMode=" + openingMode
                        + ", closingFlags=0x" + Integer.toHexString(closingFlags)
                        + ", openingFlags=0x" + Integer.toHexString(openingFlags));
            }
            Object closingContainer = readField(closingChangeInfo, "mContainer");
            Object openingContainer = readField(openingChangeInfo, "mContainer");
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
            AtomicReference<SurfaceControl.Transaction> transaction =
                    new AtomicReference<>((SurfaceControl.Transaction) startTransaction);
            transaction.get().setLayer((SurfaceControl) openingLeash, openingLayer);
            transaction.get().setLayer((SurfaceControl) closingLeash, closingLayer);
            log(Log.INFO, TAG,
                    "Normalized server cross-activity prepare role"
                            + ", transitionId=" + chain.getArg(4)
                            + ", changeIndex=" + closingIndex
                            + ", mode=" + closingMode + "->" + TRANSIT_CHANGE
                            + ", changed=" + (closingMode == TRANSIT_TO_FRONT)
                            + ", leashLayers=" + closingLayer + "/" + openingLayer
                            + ", flags=0x" + Integer.toHexString(normalizedFlags));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Server cross-activity prepare-role normalization failed;"
                            + " preserving the platform transition",
                    throwable);
        }
        return result;
    }

    private Object resolveExactFreeformCrossActivityChangeInfo(
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
        return closingTask != null
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
                && closingBounds.equals(openingBounds)
                ? closingInfo : null;
    }

    protected void hookScheduleAnimationPrepareTransition(ClassLoader classLoader) {
        try {
            Class<?> builderClass = Class.forName(SCHEDULE_ANIMATION_BUILDER, false,
                    classLoader);
            for (Method method : builderClass.getDeclaredMethods()) {
                if ("prepareTransitionIfNeeded".equals(method.getName())) {
                    method.setAccessible(true);
                    hook(method)
                            .intercept(this::interceptScheduleAnimationPrepareTransition);
                    log(Log.INFO, TAG, "Hooked ScheduleAnimationBuilder.prepareTransitionIfNeeded");
                    return;
                }
            }
            log(Log.WARN, TAG, "ScheduleAnimationBuilder.prepareTransitionIfNeeded not found");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to hook ScheduleAnimationBuilder.prepareTransitionIfNeeded",
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
            hook(method)
                    .intercept(this::allowCommittedReturnHomeTouchThrough);
            log(Log.INFO, TAG,
                    "Hooked committed return-home touch occlusion ownership");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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
            log(Log.INFO, TAG,
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
            log(Log.WARN, TAG,
                    "Failed to verify committed return-home touch ownership"
                            + ", window=" + shortObject(window),
                    throwable);
            return result;
        }
    }

    protected Object prepareOpeningTaskFragment(XposedInterface.Chain chain) throws Throwable {
        Object adaptor = chain.getThisObject();
        try {
            SystemServerImpl implementation = systemServerImpl;
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
            log(Log.WARN, TAG, "Failed to prepare opening TaskFragment", throwable);
        }
        return chain.proceed();
    }

    protected Object restoreA17OpeningSurfaceVisibility(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            ClassLoader loader = chain.getExecutable().getDeclaringClass().getClassLoader();
            requireSystemServerImplementation(loader)
                    .restoreOpeningSurfaceVisibility(this, chain);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
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
                log(Log.WARN, TAG, "Open TaskFragment update surface failed, target="
                        + shortObject(target) + ", taskFragment=" + shortObject(taskFragment)
                        + ", error=" + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            }
            Object surface = readFieldOrNull(taskFragment, "mSurfaceControl");
            if (surface instanceof SurfaceControl) {
                invokeAnyMethod(transaction, "show", new Object[]{surface});
                log(Log.INFO, TAG, "Forced opening TaskFragment visible for predictive back"
                        + ", target=" + shortObject(target)
                        + ", taskFragment=" + shortObject(taskFragment)
                        + ", surface=" + shortObject(surface));
            } else {
                log(Log.WARN, TAG, "Open TaskFragment has no SurfaceControl"
                        + ", target=" + shortObject(target)
                        + ", taskFragment=" + shortObject(taskFragment)
                        + ", surface=" + shortObject(surface));
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to force opening TaskFragment visible, target="
                    + shortObject(target), throwable);
        }
    }

    protected Object interceptScheduleAnimationPrepareTransition(XposedInterface.Chain chain)
            throws Throwable {
        ClassLoader loader = chain.getExecutable().getDeclaringClass().getClassLoader();
        return requireSystemServerImplementation(loader)
                .interceptScheduleAnimationPrepareTransition(this, chain);
    }

    protected boolean isExactFreeformCrossActivityPrepare(
            XposedInterface.Chain chain, Object builder) throws Exception {
        Object visibleArg = chain.getArg(0);
        Object close = chain.getArg(1);
        Object openArg = chain.getArg(2);
        if (!(visibleArg instanceof Object[] visibleOpen) || !(openArg instanceof Object[] promotedOpen)) {
            return false;
        }
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
            log(Log.INFO, TAG, "Treating fixed-rotation cross-activity prepare as non-exact"
                    + ", rotation=" + fixedRotation
                    + ", close=" + shortObject(closeActivity)
                    + ", open=" + shortObject(openActivity));
            return false;
        }
        return true;
    }

    /** Platform-specific server behavior owned by this hook. */
    private interface Platform {
        String name();

        Method calculateTransitionInfoMethod(Class<?> transitionClass)
                throws NoSuchMethodException;

        Object interceptScheduleAnimationPrepareTransition(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain) throws Throwable;

        Method openingSurfaceVisibilityMethod(ClassLoader classLoader) throws Exception;

        void restoreOpeningSurfaceVisibility(SystemServerBackTransitionHook hook,
                                             XposedInterface.Chain chain) throws Exception;

        boolean shouldForceOpeningTaskFragmentAtAnimationStart();

        void inspectCalculatedPredictiveTransition(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain, Object result) throws Exception;
    }

    /**
     * Keeps Android 16/17 selection and behavior local to the back-transition
     * hook.  It is deliberately composition-only: neither platform branch is a
     * superclass of the hook or of the other branch.
     */
    private static final class SystemServerImpl {
        private final Platform android16 = new Android16Platform();
        private final Platform android17 = new Android17Platform();
        private volatile Platform selected;
        private volatile ClassLoader selectedClassLoader;

        synchronized void select(ClassLoader classLoader) throws Exception {
            if (classLoader == null) {
                throw new ClassNotFoundException("system_server ClassLoader is null");
            }
            if (selected != null && selectedClassLoader == classLoader) {
                return;
            }
            Class<?> transitionClass = Class.forName(
                    "com.android.server.wm.Transition", false, classLoader);
            if (Android17Platform.matches(transitionClass)) {
                selected = android17;
            } else if (Android16Platform.matches(transitionClass)) {
                selected = android16;
            } else {
                throw new NoSuchMethodException(
                        "Unsupported Transition.calculateTransitionInfo platform shape");
            }
            selectedClassLoader = classLoader;
        }

        private Platform require() {
            Platform current = selected;
            if (current == null) {
                throw new IllegalStateException(
                        "system_server platform implementation is unavailable");
            }
            return current;
        }

        String name() {
            return require().name();
        }

        Method calculateTransitionInfoMethod(Class<?> transitionClass)
                throws NoSuchMethodException {
            return require().calculateTransitionInfoMethod(transitionClass);
        }

        Object interceptScheduleAnimationPrepareTransition(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain) throws Throwable {
            return require().interceptScheduleAnimationPrepareTransition(hook, chain);
        }

        Method openingSurfaceVisibilityMethod(ClassLoader classLoader) throws Exception {
            return require().openingSurfaceVisibilityMethod(classLoader);
        }

        void restoreOpeningSurfaceVisibility(SystemServerBackTransitionHook hook,
                                             XposedInterface.Chain chain) throws Exception {
            require().restoreOpeningSurfaceVisibility(hook, chain);
        }

        boolean shouldForceOpeningTaskFragmentAtAnimationStart() {
            return require().shouldForceOpeningTaskFragmentAtAnimationStart();
        }

        void inspectCalculatedPredictiveTransition(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain, Object result) throws Exception {
            require().inspectCalculatedPredictiveTransition(hook, chain, result);
        }
    }

    private static final class Android16Platform implements Platform {
        static boolean matches(Class<?> transitionClass) {
            return findCalculateTransitionInfo(transitionClass) != null;
        }

        @Override
        public String name() {
            return "android16";
        }

        @Override
        public Method calculateTransitionInfoMethod(Class<?> transitionClass)
                throws NoSuchMethodException {
            Method method = findCalculateTransitionInfo(transitionClass);
            if (method == null) {
                throw new NoSuchMethodException(
                        "Android 16 Transition.calculateTransitionInfo(int, int, ArrayList,"
                                + " Transaction, int)");
            }
            method.setAccessible(true);
            return method;
        }

        @Override
        public Object interceptScheduleAnimationPrepareTransition(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain) throws Throwable {
            ClassLoader loader = chain.getExecutable().getDeclaringClass().getClassLoader();
            Object builder = chain.getThisObject();
            Object launchBehind = readFieldOrNull(
                    builder, "mIsLaunchBehind");
            boolean launchBehindKnown = launchBehind instanceof Boolean;
            boolean returnToHome = Boolean.TRUE.equals(launchBehind);
            boolean unify = readWindowFlag(
                    "unifyBackNavigationTransition", loader, false);
            if (unify && launchBehindKnown && !returnToHome) {
                boolean exactCrossActivity;
                try {
                    exactCrossActivity = hook.isExactFreeformCrossActivityPrepare(
                            chain, builder);
                } catch (Throwable throwable) {
                    hook.log(Log.WARN, TAG,
                            "Failed to inspect Android 16 cross-activity prepare;"
                                    + " preserving the platform transition",
                            throwable);
                    return chain.proceed();
                }
                if (exactCrossActivity) {
                    Object close = chain.getArg(1);
                    Object[] open = (Object[]) chain.getArg(2);
                    hook.log(Log.INFO, TAG,
                            "Allowing Android 16 native unified prepare for exact"
                                    + " cross-activity, close="
                                    + shortObject(close)
                                    + ", open="
                                    + shortObject(open[0]));
                    Object transition = chain.proceed();
                    hook.log(Log.INFO, TAG,
                            "Android 16 native cross-activity prepare completed"
                                    + ", transition="
                                    + shortObject(transition));
                    return transition;
                }
                hook.log(Log.INFO, TAG,
                        "Skipped Android 16 ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                                + " to avoid Xiaomi unified-transition leash reparenting"
                                + ", unifyBackNavigationTransition=true"
                                + ", returnToHome=false"
                                + ", launchBehind=" + launchBehind
                                + ", builder="
                                + shortObject(builder));
                return null;
            }
            if (!launchBehindKnown) {
                hook.log(Log.WARN, TAG,
                        "Unable to identify Android 16 ScheduleAnimationBuilder back type;"
                                + " preserving the platform transition"
                                + ", launchBehind=" + launchBehind
                                + ", builder="
                                + shortObject(builder));
            }
            hook.log(Log.INFO, TAG,
                    "Allowing Android 16 ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                            + ", unifyBackNavigationTransition=" + unify
                            + ", returnToHome=" + (launchBehindKnown
                            ? Boolean.toString(returnToHome) : "unknown")
                            + ", launchBehind=" + launchBehind
                            + ", path=" + (unify
                            ? "unified-prepared-transition"
                            : "Xiaomi/AOSP-setLaunchBehind"));
            return chain.proceed();
        }

        @Override
        public Method openingSurfaceVisibilityMethod(ClassLoader classLoader) {
            return null;
        }

        @Override
        public void restoreOpeningSurfaceVisibility(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain) {
        }

        @Override
        public boolean shouldForceOpeningTaskFragmentAtAnimationStart() {
            return true;
        }

        @Override
        public void inspectCalculatedPredictiveTransition(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain, Object result) {
        }

        private static Method findCalculateTransitionInfo(Class<?> transitionClass) {
            for (Method method : transitionClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("calculateTransitionInfo".equals(method.getName())
                        && parameters.length == 5
                        && parameters[0] == int.class
                        && parameters[1] == int.class
                        && "java.util.ArrayList".equals(parameters[2].getName())
                        && parameters[3] == SurfaceControl.Transaction.class
                        && parameters[4] == int.class) {
                    return method;
                }
            }
            return null;
        }
    }

    private static final class Android17Platform implements Platform {
        private static final String TRANSITION_STUB =
                "com.android.server.wm.TransitionStub";
        private static final String BACK_WINDOW_ANIMATION_ADAPTOR =
                "com.android.server.wm.BackNavigationController$AnimationHandler"
                        + "$BackWindowAnimationAdaptor";
        private static final int TRANSIT_TO_FRONT = 3;
        private static final int TRANSIT_CHANGE = 6;
        private static final int WINDOWING_MODE_FULLSCREEN = 1;
        private static final int ANIMATION_TYPE_PREDICTIVE_BACK = 256;
        private static final int CHANGE_INFO_BACK_TOP = 0x80;
        private static final int CHANGE_INFO_BACK_BELOW_A17 = 0x110;
        private static final int TRANSITION_FLAG_BACK_TOP = 0x08000000;
        private static final int TRANSITION_FLAG_BACK_GESTURE_ANIMATED = 0x00020000;
        private static final int TRANSITION_FLAG_IS_OCCLUDED = 0x00008000;
        private static final int CROSS_TASK_CLOSING_FLAGS =
                TRANSITION_FLAG_BACK_TOP | TRANSITION_FLAG_BACK_GESTURE_ANIMATED;
        private static final int CROSS_TASK_OPENING_FLAGS =
                TRANSITION_FLAG_BACK_GESTURE_ANIMATED | TRANSITION_FLAG_IS_OCCLUDED;

        static boolean matches(Class<?> transitionClass) {
            return findCalculateTransitionInfo(transitionClass) != null;
        }

        @Override
        public String name() {
            return "android17";
        }

        @Override
        public Method calculateTransitionInfoMethod(Class<?> transitionClass)
                throws NoSuchMethodException {
            Method method = findCalculateTransitionInfo(transitionClass);
            if (method == null) {
                throw new NoSuchMethodException(
                        "Android 17 Transition.calculateTransitionInfo(int, int, ArrayList,"
                                + " Transaction, int, TransitionStub)");
            }
            method.setAccessible(true);
            return method;
        }

        @Override
        public Object interceptScheduleAnimationPrepareTransition(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain) throws Throwable {
            Object builder = chain.getThisObject();
            Object launchBehind = readFieldOrNull(
                    builder, "mIsLaunchBehind");
            boolean launchBehindKnown = launchBehind instanceof Boolean;
            boolean returnToHome = Boolean.TRUE.equals(launchBehind);

            // Android 17 removed unifyBackNavigationTransition(). Its
            // ScheduleAnimationBuilder now creates TRANSIT_PREDICTIVE_BACK for every
            // non-WindowState target, so preserving the stock call is the native prepared path.
            hook.log(Log.INFO, TAG,
                    "Allowing Android 17 ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                            + ", nativePreparedTransition=unconditional"
                            + ", returnToHome=" + (launchBehindKnown
                            ? Boolean.toString(returnToHome) : "unknown")
                            + ", launchBehind=" + launchBehind
                            + ", builder="
                            + shortObject(builder));
            return chain.proceed();
        }

        @Override
        public Method openingSurfaceVisibilityMethod(ClassLoader classLoader) throws Exception {
            Class<?> builderClass = Class.forName(
                    "com.android.server.wm.BackNavigationController$AnimationHandler"
                            + "$ScheduleAnimationBuilder",
                    false, classLoader);
            for (Method method : builderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("applyPreviewStrategy".equals(method.getName())
                        && parameters.length == 2
                        && parameters[0].getName().endsWith(
                        "$BackWindowAnimationAdaptorWrapper")
                        && parameters[1].isArray()
                        && "com.android.server.wm.ActivityRecord".equals(
                        Objects.requireNonNull(parameters[1].getComponentType()).getName())) {
                    method.setAccessible(true);
                    return method;
                }
            }
            throw new NoSuchMethodException(
                    "Android 17 ScheduleAnimationBuilder.applyPreviewStrategy");
        }

        @Override
        public boolean shouldForceOpeningTaskFragmentAtAnimationStart() {
            // Exact Android 17 createAdaptor() already updates and shows the TaskFragment for
            // Activity targets. The old module hook widened that behavior to Task targets, which
            // is not part of AOSP's cross-task preview preparation.
            return false;
        }

        @Override
        public void restoreOpeningSurfaceVisibility(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain) throws Exception {
            Object builder = chain.getThisObject();
            if (!Boolean.FALSE.equals(readFieldOrNull(
                    builder, "mIsLaunchBehind"))) {
                return;
            }
            Object close = readFieldOrNull(
                    builder, "mCloseTarget");
            Object opensObject = readFieldOrNull(
                    builder, "mOpenTargets");
            if (close == null || opensObject == null || !opensObject.getClass().isArray()
                    || Array.getLength(opensObject) != 1) {
                return;
            }
            Object open = Array.get(opensObject, 0);
            Object closeTask = invokeAnyMethod(
                    close, "asTask");
            Object openTask = open == null ? null
                    : invokeAnyMethod(
                    open, "asTask");
            if (closeTask != close || openTask != open || closeTask == openTask) {
                return;
            }
            Object activitiesObject = chain.getArg(1);
            if (activitiesObject == null || !activitiesObject.getClass().isArray()
                    || Array.getLength(activitiesObject) == 0) {
                return;
            }
            Object transitionController = readFieldOrNull(
                    Array.get(activitiesObject, 0), "mTransitionController");
            if (!Boolean.TRUE.equals(invokeAnyMethod(
                    transitionController, "isShellTransitionsEnabled"))) {
                return;
            }

            IdentityHashMap<Object, SurfaceVisibilityNode> nodes = new IdentityHashMap<>();
            Object displayContent = null;
            for (int index = 0; index < Array.getLength(activitiesObject); index++) {
                Object activity = Array.get(activitiesObject, index);
                if (activity == null
                        || invokeAnyMethod(
                        activity, "getTask") != openTask
                        || !Boolean.TRUE.equals(invokeAnyMethod(
                        activity, "isVisibleRequested"))) {
                    throw new IllegalStateException(
                            "Android 17 cross-task opening Activity identity changed");
                }
                Object activityDisplay = readFieldOrNull(
                        activity, "mDisplayContent");
                if (activityDisplay == null
                        || (displayContent != null && displayContent != activityDisplay)) {
                    throw new IllegalStateException(
                            "Android 17 cross-task opening display changed");
                }
                displayContent = activityDisplay;
                collectSurfaceNode(nodes, activity, true, false);
                for (Object parent = invokeAnyMethod(
                        activity, "getParent");
                     parent != null && parent != displayContent;
                     parent = invokeAnyMethod(
                             parent, "getParent")) {
                    Object task = invokeAnyMethod(
                            parent, "asTask");
                    collectSurfaceNode(nodes, parent, false, task == parent);
                }
            }
            if (!nodes.containsKey(openTask)) {
                throw new IllegalStateException(
                        "Android 17 cross-task opening Task is outside Activity ancestry");
            }

            // AOSP performs this at the end of applyPreviewStrategy(): show the opening
            // Activity and every parent, then schedule the shared sync transaction. Xiaomi
            // 4371 stripped that block and also gutted
            // TransitionController.onVisibleWithoutCollectingTransition(). This Android 17
            // branch no longer has ActivityRecord/Task.mLastSurfaceShowing; visibility state
            // is refreshed by WindowAnimator.updateSurfaceVisibility(), so writing the old
            // Android 14-16 field is both unnecessary and invalid here.
            for (SurfaceVisibilityNode node : new ArrayList<>(nodes.values())) {
                invokeAnyMethod(
                        node.transaction, "show", node.surface);
            }
            for (int index = 0; index < Array.getLength(activitiesObject); index++) {
                invokeAnyMethod(
                        Array.get(activitiesObject, index), "scheduleAnimation");
            }
            hook.log(Log.INFO, TAG,
                    "Restored AOSP Android 17 cross-task opening surfaces"
                            + ", closeTask="
                            + shortObject(closeTask)
                            + ", openTask="
                            + shortObject(openTask)
                            + ", activities=" + Array.getLength(activitiesObject)
                            + ", surfaces=" + nodes.size());
        }

        private static void collectSurfaceNode(
                IdentityHashMap<Object, SurfaceVisibilityNode> nodes,
                Object container, boolean activity, boolean task) throws Exception {
            if (nodes.containsKey(container)) {
                SurfaceVisibilityNode previous = nodes.get(container);
                previous.activity |= activity;
                previous.task |= task;
                return;
            }
            Object surfaceObject = readFieldOrNull(
                    container, "mSurfaceControl");
            Object transaction = invokeAnyMethod(
                    container, "getSyncTransaction");
            if (!(surfaceObject instanceof SurfaceControl)
                    || !((SurfaceControl) surfaceObject).isValid()
                    || !(transaction instanceof SurfaceControl.Transaction)) {
                throw new IllegalStateException(
                        "Android 17 cross-task opening Surface is unavailable, container="
                                + shortObject(container));
            }
            nodes.put(container, new SurfaceVisibilityNode(
                    container, (SurfaceControl) surfaceObject,
                    (SurfaceControl.Transaction) transaction, activity, task));
        }

        private static final class SurfaceVisibilityNode {
            final Object container;
            final SurfaceControl surface;
            final SurfaceControl.Transaction transaction;
            boolean activity;
            boolean task;

            SurfaceVisibilityNode(Object container, SurfaceControl surface,
                                  SurfaceControl.Transaction transaction,
                                  boolean activity, boolean task) {
                this.container = container;
                this.surface = surface;
                this.transaction = transaction;
                this.activity = activity;
                this.task = task;
            }
        }

        @Override
        public void inspectCalculatedPredictiveTransition(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain, Object result) throws Exception {
            Object type = chain.getArg(0);
            if (!(type instanceof Number)
                    || ((Number) type).intValue() != 13) {
                return;
            }
            Object targetsObject = chain.getArg(2);
            if (!(targetsObject instanceof List<?> targets)
                    || targets.size() != 2) {
                return;
            }
            Object firstContainer = readFieldOrNull(
                    targets.get(0), "mContainer");
            Object secondContainer = readFieldOrNull(
                    targets.get(1), "mContainer");
            Object firstTask = firstContainer == null ? null
                    : invokeAnyMethod(
                    firstContainer, "asTask");
            Object secondTask = secondContainer == null ? null
                    : invokeAnyMethod(
                    secondContainer, "asTask");
            if (firstTask != firstContainer || secondTask != secondContainer
                    || firstTask == secondTask) {
                return;
            }
            Object changesObject = readTransitionInfoChanges(result);
            String changesBefore = changesObject instanceof List<?>
                    ? describeChanges((List<?>) changesObject)
                    : shortObject(changesObject);
            boolean normalized = changesObject instanceof List<?>
                    && normalizeCrossTaskPrepareRole(hook, chain, targets,
                    (List<?>) changesObject);
            String changesAfter = normalized
                    ? describeChanges((List<?>) changesObject) : changesBefore;
            hook.log(Log.INFO, TAG,
                    "Android 17 cross-task prepared transition"
                            + ", transitionId=" + chain.getArg(4)
                            + ", targets=" + describeTargets(targets)
                            + ", normalized=" + normalized
                            + ", changesBefore=" + changesBefore
                            + (normalized ? ", changesAfter=" + changesAfter : ""));
        }

        private static boolean normalizeCrossTaskPrepareRole(
                SystemServerBackTransitionHook hook,
                XposedInterface.Chain chain,
                List<?> targets,
                List<?> changes) throws Exception {
            if (changes.size() != 2) {
                return false;
            }
            Object closingInfo = null;
            Object openingInfo = null;
            Object closingTask = null;
            Object openingTask = null;
            for (Object target : targets) {
                Object container = readFieldOrNull(
                        target, "mContainer");
                Object animator = readFieldOrNull(
                        container, "mSurfaceAnimator");
                Object animation = readFieldOrNull(
                        animator, "mAnimation");
                if (container == null
                        || invokeAnyMethod(
                        container, "asTask") != container
                        || animation == null
                        || !BACK_WINDOW_ANIMATION_ADAPTOR.equals(
                        animation.getClass().getName())
                        || readFieldOrNull(
                        animation, "mTarget") != container) {
                    return false;
                }
                Object isOpen = readFieldOrNull(
                        animation, "mIsOpen");
                if (Boolean.FALSE.equals(isOpen) && closingInfo == null) {
                    closingInfo = target;
                    closingTask = container;
                } else if (Boolean.TRUE.equals(isOpen) && openingInfo == null) {
                    openingInfo = target;
                    openingTask = container;
                } else {
                    return false;
                }
            }
            if (closingInfo == null || openingInfo == null || readIntField(closingInfo, "mFlags") != CHANGE_INFO_BACK_TOP || readIntField(openingInfo, "mFlags") != CHANGE_INFO_BACK_BELOW_A17 || !Boolean.TRUE.equals(readFieldOrNull(
                    closingInfo, "mVisible")) || !Boolean.FALSE.equals(readFieldOrNull(
                    openingInfo, "mVisible")) || !Boolean.TRUE.equals(invokeOrNull(
                    closingTask, "isVisibleRequested")) || !Boolean.TRUE.equals(invokeOrNull(
                    openingTask, "isVisibleRequested")) || valueOrDefault((Integer) invokeOrNull(
                    closingTask, "getWindowingMode")) != WINDOWING_MODE_FULLSCREEN || valueOrDefault((Integer) invokeOrNull(
                    openingTask, "getWindowingMode")) != WINDOWING_MODE_FULLSCREEN || readFieldOrNull(
                    closingTask, "mDisplayContent") != readFieldOrNull(
                    openingTask, "mDisplayContent")) {
                return false;
            }
            Object closingBoundsObject = invokeOrNull(closingTask, "getBounds");
            Object openingBoundsObject = invokeOrNull(openingTask, "getBounds");
            if (!(closingBoundsObject instanceof Rect)
                    || !(openingBoundsObject instanceof Rect)
                    || ((Rect) closingBoundsObject).isEmpty()
                    || !closingBoundsObject.equals(openingBoundsObject)) {
                return false;
            }

            int closingTaskId = readIntField(closingTask, "mTaskId");
            int openingTaskId = readIntField(openingTask, "mTaskId");
            Object closingChange = null;
            Object openingChange = null;
            for (Object change : changes) {
                Object taskInfo = readTransitionChangeTaskInfo(change);
                int taskId = readIntField(taskInfo, "taskId");
                if (taskId == closingTaskId) {
                    closingChange = change;
                } else if (taskId == openingTaskId) {
                    openingChange = change;
                } else {
                    return false;
                }
            }
            if (closingTaskId < 0 || openingTaskId < 0
                    || closingChange == null || openingChange == null
                    || valueOrDefault(
                    readTransitionChangeMode(
                            closingChange)) != TRANSIT_TO_FRONT
                    || valueOrDefault(
                    readTransitionChangeMode(
                            openingChange)) != TRANSIT_TO_FRONT
                    || valueOrDefault(
                    readTransitionChangeFlags(
                            closingChange)) != CROSS_TASK_CLOSING_FLAGS
                    || valueOrDefault(
                    readTransitionChangeFlags(
                            openingChange)) != CROSS_TASK_OPENING_FLAGS
                    || readTransitionChangeParent(
                    closingChange) != null
                    || readTransitionChangeLastParent(
                    closingChange) != null
                    || readTransitionChangeParent(
                    openingChange) != null
                    || readTransitionChangeLastParent(
                    openingChange) != null) {
                return false;
            }

            Object closingAnimator = readFieldOrNull(
                    closingTask, "mSurfaceAnimator");
            Object openingAnimator = readFieldOrNull(
                    openingTask, "mSurfaceAnimator");
            Object closingAnimation = readFieldOrNull(
                    closingAnimator, "mAnimation");
            Object openingAnimation = readFieldOrNull(
                    openingAnimator, "mAnimation");
            Object closingLeash = readFieldOrNull(
                    closingAnimator, "mLeash");
            Object openingLeash = readFieldOrNull(
                    openingAnimator, "mLeash");
            int closingLayer = readIntField(closingTask, "mLastLayer");
            int openingLayer = readIntField(openingTask, "mLastLayer");
            Object startTransaction = chain.getArg(3);
            if (readFieldOrNull(
                    closingAnimation, "mCapturedLeash") != closingLeash
                    || readFieldOrNull(
                    openingAnimation, "mCapturedLeash") != openingLeash
                    || readIntField(closingAnimator,
                    "mAnimationType") != ANIMATION_TYPE_PREDICTIVE_BACK
                    || readIntField(openingAnimator,
                    "mAnimationType") != ANIMATION_TYPE_PREDICTIVE_BACK
                    || readFieldOrNull(
                    closingTask, "mLastRelativeToLayer") != null
                    || readFieldOrNull(
                    openingTask, "mLastRelativeToLayer") != null
                    || !(closingLeash instanceof SurfaceControl)
                    || !(openingLeash instanceof SurfaceControl)
                    || !((SurfaceControl) closingLeash).isValid()
                    || !((SurfaceControl) openingLeash).isValid()
                    || !(startTransaction instanceof SurfaceControl.Transaction transaction)
                    || closingLayer <= openingLayer || openingLayer < 0) {
                return false;
            }
            if (!setTransitionChangeMode(
                    closingChange, TRANSIT_CHANGE)) {
                return false;
            }
            transaction.setLayer((SurfaceControl) openingLeash, openingLayer);
            transaction.setLayer((SurfaceControl) closingLeash, closingLayer);
            if (valueOrDefault(
                    readTransitionChangeMode(
                            closingChange)) != TRANSIT_CHANGE
                    || valueOrDefault(
                    readTransitionChangeMode(
                            openingChange)) != TRANSIT_TO_FRONT) {
                throw new IllegalStateException(
                        "Android 17 cross-task prepare role was not retained");
            }
            hook.log(Log.INFO, TAG,
                    "Normalized Android 17 cross-task prepare role"
                            + ", transitionId=" + chain.getArg(4)
                            + ", closingTaskId=" + closingTaskId
                            + ", openingTaskId=" + openingTaskId
                            + ", mode=" + TRANSIT_TO_FRONT + "->" + TRANSIT_CHANGE
                            + ", leashLayers=" + closingLayer + "/" + openingLayer);
            return true;
        }

        private static String describeTargets(List<?> targets) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < targets.size(); index++) {
                if (index > 0) {
                    result.append(", ");
                }
                Object changeInfo = targets.get(index);
                Object container = readFieldOrNull(
                        changeInfo, "mContainer");
                Object animator = readFieldOrNull(
                        container, "mSurfaceAnimator");
                result.append("{index=").append(index)
                        .append(", container=")
                        .append(shortObject(container))
                        .append(", taskId=")
                        .append(readIntField(container, "mTaskId"))
                        .append(", flags=0x")
                        .append(Integer.toHexString(readIntField(
                                changeInfo, "mFlags")))
                        .append(", visible=")
                        .append(readFieldOrNull(
                                changeInfo, "mVisible"))
                        .append(", requested=")
                        .append(invokeOrNull(container, "isVisibleRequested"))
                        .append(", lastLayer=")
                        .append(readFieldOrNull(
                                container, "mLastLayer"))
                        .append(", relative=")
                        .append(shortObject(
                                readFieldOrNull(
                                        container, "mLastRelativeToLayer")))
                        .append(", surface=")
                        .append(shortObject(
                                readFieldOrNull(
                                        container, "mSurfaceControl")))
                        .append(", animation=")
                        .append(shortObject(
                                readFieldOrNull(
                                        animator, "mAnimation")))
                        .append(", leash=")
                        .append(shortObject(
                                readFieldOrNull(
                                        animator, "mLeash")))
                        .append('}');
            }
            return result.append(']').toString();
        }

        private static String describeChanges(List<?> changes) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < changes.size(); index++) {
                if (index > 0) {
                    result.append(", ");
                }
                Object change = changes.get(index);
                Object taskInfo = readTransitionChangeTaskInfo(change);
                result.append("{index=").append(index)
                        .append(", taskId=")
                        .append(readIntField(taskInfo, "taskId"))
                        .append(", mode=")
                        .append(readTransitionChangeMode(change))
                        .append(", flags=0x")
                        .append(Integer.toHexString(valueOrDefault(
                                readTransitionChangeFlags(change))))
                        .append(", leash=")
                        .append(shortObject(
                                readTransitionChangeLeash(change)))
                        .append(", parent=")
                        .append(shortObject(
                                readTransitionChangeParent(change)))
                        .append(", lastParent=")
                        .append(shortObject(
                                readTransitionChangeLastParent(change)))
                        .append(", startBounds=")
                        .append(shortObject(
                                readTransitionChangeStartAbsBounds(change)))
                        .append(", endBounds=")
                        .append(shortObject(
                                readTransitionChangeEndAbsBounds(change)))
                        .append('}');
            }
            return result.append(']').toString();
        }

        private static int valueOrDefault(Integer value) {
            return value == null ? -1 : value;
        }

        private static Method findCalculateTransitionInfo(Class<?> transitionClass) {
            for (Method method : transitionClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("calculateTransitionInfo".equals(method.getName())
                        && parameters.length == 6
                        && parameters[0] == int.class
                        && parameters[1] == int.class
                        && "java.util.ArrayList".equals(parameters[2].getName())
                        && parameters[3] == SurfaceControl.Transaction.class
                        && parameters[4] == int.class
                        && TRANSITION_STUB.equals(parameters[5].getName())) {
                    return method;
                }
            }
            return null;
        }
    }

}
