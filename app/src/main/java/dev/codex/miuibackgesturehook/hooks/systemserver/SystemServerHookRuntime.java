package dev.codex.miuibackgesturehook.hooks.systemserver;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.hooks.googleapp.GoogleAppLiveTranslateRuntime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Binder;
import android.os.IBinder;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.WindowOnBackInvokedDispatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;

public abstract class SystemServerHookRuntime extends GoogleAppLiveTranslateRuntime {

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
    protected final AtomicInteger contextualSearchBridgeCallsInFlight = new AtomicInteger();
    private volatile PackageManager contextualSearchPackageManager;
    private volatile int contextualSearchPackageResourceId;
    private volatile SystemServerPlatformImpl systemServerPlatformImpl;
    private volatile ClassLoader systemServerPlatformClassLoader;

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
            moduleLog(Log.INFO, TAG, "Installed system_server back navigation hooks, build="
                    + BUILD_MARK
                    + ", platform=" + (systemServerPlatformImpl == null
                    ? "unresolved" : systemServerPlatformImpl.name())
                    + ", hooks=" + hookHandles.size());
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG, "Failed to install system_server hooks", throwable);
        }
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
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object application = activityThreadClass
                    .getDeclaredMethod("currentApplication")
                    .invoke(null);
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
        if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL) {
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

    protected Object interceptSecuritySidebarTransientBars(XposedInterface.Chain chain)
            throws Throwable {
        if (Build.VERSION.SDK_INT >= ANDROID_17_API_LEVEL) {
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
        if (chain.getArgs().size() == 2
                && chain.getArg(0) == null
                && Boolean.FALSE.equals(chain.getArg(1))) {
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

    protected SystemServerPlatformImpl requireSystemServerPlatformImpl(
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
