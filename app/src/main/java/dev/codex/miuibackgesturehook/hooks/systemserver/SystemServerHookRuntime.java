package dev.codex.miuibackgesturehook.hooks.systemserver;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeHookRuntime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;

public abstract class SystemServerHookRuntime extends MiuiHomeHookRuntime {

    protected static final int SERVER_CHANGE_INFO_BACK_TOP = 128;
    protected static final int SERVER_CHANGE_INFO_BACK_BELOW = 256;
    protected static final int SERVER_TRANSITION_INFO_BACK_TOP = 0x08000000;
    protected static final int SERVER_FREEFORM_PREPARED_CLOSING_FLAGS =
            SERVER_TRANSITION_INFO_BACK_TOP | FLAG_BACK_GESTURE_ANIMATED | FLAG_FILLS_TASK;
    protected static final int SERVER_FREEFORM_PREPARED_OPENING_FLAGS =
            FLAG_BACK_GESTURE_ANIMATED | FLAG_FILLS_TASK | FLAG_IS_OCCLUDED;
    protected volatile boolean serverFreeformPrepareRoleHookReady;
    protected volatile Field serverTransitionChangeInfoFlagsField;
    protected volatile Method serverTransitionInfoChangeSetModeMethod;

    protected void installSystemServerHooks(ClassLoader classLoader) {
        try {
            ClassLoader serverClassLoader = findSystemServerClassLoader(classLoader);
            if (serverClassLoader == null) {
                log(Log.ERROR, TAG, "Unable to find system_server classloader for "
                        + BACK_NAVIGATION_CONTROLLER);
                return;
            }
            hookTaskFragmentPromotionCompatibility(serverClassLoader);
            hookBackNavigationDoneCleanup(serverClassLoader);
            hookPredictiveBackOptInMetadata(serverClassLoader);
            hookSecuritySidebarTransientBars(serverClassLoader);
            hookBackWindowStartAnimation(serverClassLoader);
            hookFreeformCrossActivityPrepareRole(serverClassLoader);
            hookScheduleAnimationPrepareTransition(serverClassLoader);
            hookReturnHomeTouchOcclusion(serverClassLoader);
            log(Log.INFO, TAG, "Installed system_server back navigation hooks, build="
                    + BUILD_MARK + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install system_server hooks", throwable);
        }
    }

    protected void hookPredictiveBackOptInMetadata(ClassLoader classLoader) {
        try {
            Class<?> dispatcherClass = Class.forName(
                    WINDOW_ON_BACK_INVOKED_DISPATCHER, false, classLoader);
            for (Method method : dispatcherClass.getDeclaredMethods()) {
                if (!"isOnBackInvokedCallbackEnabled".equals(method.getName())
                        || method.getParameterCount() != 3
                        || !"android.content.pm.ActivityInfo".equals(
                        method.getParameterTypes()[0].getName())
                        || !"android.content.pm.ApplicationInfo".equals(
                        method.getParameterTypes()[1].getName())) {
                    continue;
                }
                method.setAccessible(true);
                recordHookHandle(hook(method)
                        .setId("server_predictive_opt_in_metadata")
                        .intercept(this::injectSelectedPredictiveBackMetadata));
                log(Log.INFO, TAG, "Hooked predictive-back opt-in metadata"
                        + ", owner=system_server"
                        + ", policy=selectedApplications"
                        + ", preferencesGroup=" + PredictiveBackPreferences.GROUP);
                return;
            }
            log(Log.WARN, TAG,
                    "Predictive-back opt-in check not found in system_server");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to hook selected predictive-back metadata", throwable);
        }
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
            log(Log.INFO, TAG, "Ignored stale predictive-back selection"
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
            log(Log.ERROR, TAG, "Failed to inject selected predictive-back metadata"
                    + ", package=" + packageName
                    + ", activity=" + shortObject(activityInfo), throwable);
            return chain.proceed();
        }

        Object result = chain.proceed();
        int priority = Boolean.TRUE.equals(result) ? Log.INFO : Log.WARN;
        log(priority, TAG, "Selected predictive-back metadata result"
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
                log(Log.WARN, TAG,
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
                log(Log.ERROR, TAG, "Predictive-back preferences unavailable"
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
                    log(Log.ERROR, TAG,
                            "Failed to hook security-sidebar transient bars " + hookId,
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

    protected Object interceptSecuritySidebarTransientBars(XposedInterface.Chain chain)
            throws Throwable {
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
            if ("com.miui.securitycenter".equals(owner)) {
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
                if (downTimes[pointer] <= 0L || now - downTimes[pointer] > 2000L) {
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

    protected void hookTaskFragmentPromotionCompatibility(ClassLoader classLoader) {
        try {
            Class.forName(BACK_NAVIGATION_CONTROLLER, false, classLoader);
            Class<?> handlerClass = Class.forName(BACK_ANIMATION_HANDLER, false, classLoader);
            Method promote = null;
            for (Method method : handlerClass.getDeclaredMethods()) {
                if ("promoteToTFIfNeeded".equals(method.getName())
                        && method.getParameterCount() == 2) {
                    promote = method;
                    break;
                }
            }
            if (promote == null) {
                log(Log.WARN, TAG, "BackNavigationController promoteToTFIfNeeded not found");
                return;
            }
            promote.setAccessible(true);
            recordHookHandle(hook(promote)
                    .setId("server_back_promote_to_tf_if_needed")
                    .intercept(this::interceptPromoteToTaskFragmentIfNeeded));
            log(Log.INFO, TAG, "Hooked BackNavigationController promoteToTFIfNeeded");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook TaskFragment promotion compatibility", throwable);
        }
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

    protected void hookFreeformCrossActivityPrepareRole(ClassLoader classLoader) {
        serverFreeformPrepareRoleHookReady = false;
        serverTransitionChangeInfoFlagsField = null;
        serverTransitionInfoChangeSetModeMethod = null;
        try {
            Class<?> transitionClass = Class.forName(
                    "com.android.server.wm.Transition", false, classLoader);
            if (!initializeFreeformPrepareRoleReflection(classLoader)) {
                return;
            }
            for (Method method : transitionClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("calculateTransitionInfo".equals(method.getName())
                        && parameters.length == 5
                        && parameters[0] == int.class
                        && parameters[1] == int.class
                        && "java.util.ArrayList".equals(parameters[2].getName())
                        && parameters[3] == SurfaceControl.Transaction.class
                        && parameters[4] == int.class) {
                    method.setAccessible(true);
                    recordHookHandle(hook(method)
                            .setId("server_freeform_prepare_role_normalization")
                            .intercept(this::normalizeFreeformCrossActivityTransitionInfo));
                    serverFreeformPrepareRoleHookReady = true;
                    log(Log.INFO, TAG,
                            "Hooked server freeform predictive-back prepare role"
                                    + " normalization");
                    return;
                }
            }
            log(Log.WARN, TAG,
                    "Transition.calculateTransitionInfo five-argument overload not found");
        } catch (Throwable throwable) {
            serverFreeformPrepareRoleHookReady = false;
            serverTransitionChangeInfoFlagsField = null;
            serverTransitionInfoChangeSetModeMethod = null;
            log(Log.ERROR, TAG,
                    "Failed to hook server freeform predictive-back prepare role",
                    throwable);
        }
    }

    protected boolean initializeFreeformPrepareRoleReflection(ClassLoader classLoader) {
        try {
            Class<?> changeInfoClass = Class.forName(
                    "com.android.server.wm.Transition$ChangeInfo", false, classLoader);
            Field flags = changeInfoClass.getDeclaredField("mFlags");
            flags.setAccessible(true);
            Class<?> transitionInfoChangeClass = Class.forName(
                    "android.window.TransitionInfo$Change", false, classLoader);
            Method setMode = null;
            for (Method method : transitionInfoChangeClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("setMode".equals(method.getName())
                        && parameters.length == 1
                        && parameters[0] == int.class) {
                    setMode = method;
                    break;
                }
            }
            if (setMode == null) {
                throw new NoSuchMethodException("TransitionInfo.Change.setMode(int)");
            }
            setMode.setAccessible(true);
            serverTransitionChangeInfoFlagsField = flags;
            serverTransitionInfoChangeSetModeMethod = setMode;
            return true;
        } catch (Throwable throwable) {
            serverTransitionChangeInfoFlagsField = null;
            serverTransitionInfoChangeSetModeMethod = null;
            log(Log.ERROR, TAG,
                    "Server freeform prepare-role reflection unavailable", throwable);
            return false;
        }
    }

    protected Object normalizeFreeformCrossActivityTransitionInfo(
            XposedInterface.Chain chain) throws Throwable {
        Field flagsField = serverTransitionChangeInfoFlagsField;
        Method setModeMethod = serverTransitionInfoChangeSetModeMethod;
        Object closingChangeInfo = null;
        int closingIndex = -1;
        try {
            Object type = chain.getArg(0);
            if (flagsField != null
                    && setModeMethod != null
                    && type instanceof Number
                    && ((Number) type).intValue() == TRANSIT_PREDICTIVE_BACK) {
                Object targetsObject = chain.getArg(2);
                closingChangeInfo = resolveExactFreeformCrossActivityChangeInfo(
                        targetsObject, flagsField);
                if (closingChangeInfo != null) {
                    closingIndex = ((List<?>) targetsObject).indexOf(closingChangeInfo);
                }
            }
        } catch (Throwable throwable) {
            serverFreeformPrepareRoleHookReady = false;
            log(Log.WARN, TAG,
                    "Failed to inspect server freeform prepared targets;"
                            + " disabling native freeform prepare",
                    throwable);
        }
        Object result = chain.proceed();
        if (closingChangeInfo == null || closingIndex < 0 || setModeMethod == null) {
            return result;
        }

        try {
            Object changesObject = invokeAnyMethod(
                    result, "getChanges", new Object[0]);
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
            int closingMode = ((Number) invokeAnyMethod(
                    closingChange, "getMode", new Object[0])).intValue();
            int openingMode = ((Number) invokeAnyMethod(
                    openingChange, "getMode", new Object[0])).intValue();
            int closingFlags = ((Number) invokeAnyMethod(
                    closingChange, "getFlags", new Object[0])).intValue();
            int openingFlags = ((Number) invokeAnyMethod(
                    openingChange, "getFlags", new Object[0])).intValue();
            if ((closingMode != TRANSIT_TO_FRONT && closingMode != TRANSIT_CHANGE)
                    || openingMode != TRANSIT_TO_FRONT
                    || closingFlags != SERVER_FREEFORM_PREPARED_CLOSING_FLAGS
                    || openingFlags != SERVER_FREEFORM_PREPARED_OPENING_FLAGS) {
                throw new IllegalStateException("unexpected prepared roles, closingMode="
                        + closingMode + ", openingMode=" + openingMode
                        + ", closingFlags=0x" + Integer.toHexString(closingFlags)
                        + ", openingFlags=0x" + Integer.toHexString(openingFlags));
            }
            if (closingMode == TRANSIT_TO_FRONT) {
                setModeMethod.invoke(closingChange, TRANSIT_CHANGE);
            }
            int normalizedMode = ((Number) invokeAnyMethod(
                    closingChange, "getMode", new Object[0])).intValue();
            int normalizedFlags = ((Number) invokeAnyMethod(
                    closingChange, "getFlags", new Object[0])).intValue();
            int preservedOpeningMode = ((Number) invokeAnyMethod(
                    openingChange, "getMode", new Object[0])).intValue();
            int preservedOpeningFlags = ((Number) invokeAnyMethod(
                    openingChange, "getFlags", new Object[0])).intValue();
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
            log(Log.INFO, TAG,
                    "Normalized server freeform cross-activity prepare role"
                            + ", transitionId=" + chain.getArg(4)
                            + ", changeIndex=" + closingIndex
                            + ", mode=" + closingMode + "->" + TRANSIT_CHANGE
                            + ", changed=" + (closingMode == TRANSIT_TO_FRONT)
                            + ", flags=0x" + Integer.toHexString(normalizedFlags));
        } catch (Throwable throwable) {
            serverFreeformPrepareRoleHookReady = false;
            log(Log.ERROR, TAG,
                    "Server freeform prepare-role normalization failed;"
                            + " disabling native freeform prepare",
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
        Object closingActivity = null;
        Object openingActivity = null;
        for (Object changeInfo : (List<?>) targetsObject) {
            Object container = readField(changeInfo, "mContainer");
            Object activity = container == null ? null : invokeAnyMethod(
                    container, "asActivityRecord", new Object[0]);
            int flags = flagsField.getInt(changeInfo);
            if (activity == null) {
                return null;
            }
            if (flags == SERVER_CHANGE_INFO_BACK_TOP && closingInfo == null) {
                closingInfo = changeInfo;
                closingActivity = activity;
            } else if (flags == SERVER_CHANGE_INFO_BACK_BELOW
                    && openingInfo == null) {
                openingInfo = changeInfo;
                openingActivity = activity;
            } else {
                return null;
            }
        }
        if (closingActivity == null || openingActivity == null
                || closingActivity == openingActivity
                || !Boolean.TRUE.equals(readField(closingInfo, "mVisible"))
                || !Boolean.FALSE.equals(readField(openingInfo, "mVisible"))
                || !Boolean.TRUE.equals(invokeAnyMethod(
                closingActivity, "isVisibleRequested", new Object[0]))
                || !Boolean.TRUE.equals(invokeAnyMethod(
                openingActivity, "isVisibleRequested", new Object[0]))) {
            return null;
        }
        Object closingTask = invokeAnyMethod(
                closingActivity, "getTask", new Object[0]);
        Object openingTask = invokeAnyMethod(
                openingActivity, "getTask", new Object[0]);
        Object activityType = closingTask == null ? null : invokeAnyMethod(
                closingTask, "getActivityType", new Object[0]);
        Object closingMode = invokeAnyMethod(
                closingActivity, "getWindowingMode", new Object[0]);
        Object openingMode = invokeAnyMethod(
                openingActivity, "getWindowingMode", new Object[0]);
        Object closingBounds = invokeAnyMethod(
                closingActivity, "getBounds", new Object[0]);
        Object openingBounds = invokeAnyMethod(
                openingActivity, "getBounds", new Object[0]);
        return closingTask != null
                && closingTask == openingTask
                && activityType instanceof Number
                && ((Number) activityType).intValue() == ACTIVITY_TYPE_STANDARD
                && closingMode instanceof Number
                && ((Number) closingMode).intValue() == WINDOWING_MODE_FREEFORM
                && openingMode instanceof Number
                && ((Number) openingMode).intValue() == WINDOWING_MODE_FREEFORM
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
                    recordHookHandle(hook(method)
                            .setId("server_schedule_animation_prepare_transition")
                            .intercept(this::interceptScheduleAnimationPrepareTransition));
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
            recordHookHandle(hook(method)
                    .setId("server_return_home_touch_occlusion")
                    .intercept(this::allowCommittedReturnHomeTouchThrough));
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
        Object builder = chain.getThisObject();
        Object launchBehind = readFieldOrNull(builder, "mIsLaunchBehind");
        boolean launchBehindKnown = launchBehind instanceof Boolean;
        boolean returnToHome = Boolean.TRUE.equals(launchBehind);
        boolean unify = readWindowFlag("unifyBackNavigationTransition", loader, false);
        if (unify && launchBehindKnown && !returnToHome) {
            if (serverFreeformPrepareRoleHookReady
                    && isExactFreeformCrossActivityPrepare(chain, builder)) {
                Object close = chain.getArg(1);
                Object[] open = (Object[]) chain.getArg(2);
                log(Log.INFO, TAG, "Allowing native unified prepare for exact freeform"
                        + " cross-activity, close=" + shortObject(close)
                        + ", open=" + shortObject(open[0]));
                Object transition = chain.proceed();
                log(Log.INFO, TAG, "Native freeform cross-activity prepare completed"
                        + ", transition=" + shortObject(transition));
                return transition;
            }
            log(Log.INFO, TAG, "Skipped ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                    + " to avoid Xiaomi unified-transition leash reparenting"
                    + ", unifyBackNavigationTransition=true"
                    + ", returnToHome=false"
                    + ", launchBehind=" + launchBehind
                    + ", freeformRoleNormalizerReady="
                    + serverFreeformPrepareRoleHookReady
                    + ", builder=" + shortObject(builder));
            return null;
        }
        if (!launchBehindKnown) {
            log(Log.WARN, TAG, "Unable to identify ScheduleAnimationBuilder back type;"
                    + " preserving the platform transition"
                    + ", launchBehind=" + launchBehind
                    + ", builder=" + shortObject(builder));
        }
        log(Log.INFO, TAG, "Allowing ScheduleAnimationBuilder.prepareTransitionIfNeeded"
                + ", unifyBackNavigationTransition=" + unify
                + ", returnToHome=" + (launchBehindKnown
                    ? Boolean.toString(returnToHome)
                    : "unknown")
                + ", launchBehind=" + launchBehind
                + ", path=" + (unify
                    ? "unified-prepared-transition"
                    : "Xiaomi/AOSP-setLaunchBehind"));
        return chain.proceed();
    }

    protected boolean isExactFreeformCrossActivityPrepare(
            XposedInterface.Chain chain, Object builder) {
        try {
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
            Object closeActivity = invokeAnyMethod(
                    close, "asActivityRecord", new Object[0]);
            Object openActivity = invokeAnyMethod(
                    promotedOpen[0], "asActivityRecord", new Object[0]);
            if (closeActivity == null || openActivity == null
                    || close != closeActivity
                    || promotedOpen[0] != openActivity
                    || closeActivity == openActivity
                    || visibleOpen[0] != openActivity
                    || readField(builder, "mCloseTarget") != close) {
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
            return closeTask != null
                    && closeTask == openTask
                    && activityType instanceof Number
                    && ((Number) activityType).intValue() == ACTIVITY_TYPE_STANDARD
                    && closeMode instanceof Number
                    && ((Number) closeMode).intValue() == WINDOWING_MODE_FREEFORM
                    && openMode instanceof Number
                    && ((Number) openMode).intValue() == WINDOWING_MODE_FREEFORM
                    && Boolean.FALSE.equals(invokeAnyMethod(
                    openActivity, "isVisibleRequested", new Object[0]))
                    && Boolean.FALSE.equals(readField(
                    openActivity, "mLaunchTaskBehind"));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect freeform cross-activity prepare;"
                    + " preserving compatibility skip", throwable);
            return false;
        }
    }

    protected Object interceptPromoteToTaskFragmentIfNeeded(XposedInterface.Chain chain)
            throws Throwable {
        Object close = chain.getArg(0);
        Object open = chain.getArg(1);
        boolean migrate = readWindowFlag("migratePredictiveBackTransition",
                chain.getExecutable().getDeclaringClass().getClassLoader(), false);
        if (!migrate) {
            Pair<Object, Object> result = new Pair<>(close, open);
            log(Log.INFO, TAG, "Bypassed TaskFragment promotion for predictive back"
                    + ", close=" + shortObject(close)
                    + ", open=" + shortObject(open));
            return result;
        }
        return chain.proceed();
    }
}
