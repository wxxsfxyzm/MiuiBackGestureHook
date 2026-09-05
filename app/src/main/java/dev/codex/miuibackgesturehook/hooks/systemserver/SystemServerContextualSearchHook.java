package dev.codex.miuibackgesturehook.hooks.systemserver;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;

@Hooker.XposedHooker(
        name = "SystemServerContextualSearchHook",
        targets = "system",
        order = 90)
public final class SystemServerContextualSearchHook extends HookerBridge {
    private static final String TAG = "SystemServerContextualSearchHook";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String MIUI_HOME = "com.miui.home";
    private static final String GOOGLE_CONTEXTUAL_SEARCH_PACKAGE =
            "com.google.android.googlequicksearchbox";
    private static final int ANDROID_17_API_LEVEL = 37;

    private final ThreadLocal<Boolean> contextualSearchBridgeInvocation = new ThreadLocal<>();
    private final AtomicInteger contextualSearchBridgeCallsInFlight = new AtomicInteger();
    private final AtomicInteger contextualSearchBridgeAcceptedCalls = new AtomicInteger();
    private volatile PackageManager contextualSearchPackageManager;
    private volatile int contextualSearchPackageResourceId;
    private volatile SharedPreferences contextualSearchPreferences;
    private volatile boolean contextualSearchPreferencesFailureLogged;
    private final SystemServerImpl systemServerImpl = new SystemServerImpl();

    @Override
    public void onPackageLoad() {
        try {
            ClassLoader serverLoader = findSystemServerClassLoader(classLoader);
            if (serverLoader == null) {
                throw new IllegalStateException("system_server ClassLoader unavailable");
            }
            selectSystemServerImplementation(serverLoader);
            hookContextualSearchCompatibility(serverLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install system contextual search", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return contextualSearchBridgeCallsInFlight.get() == 0;
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        contextualSearchBridgeInvocation.remove();
        contextualSearchPreferences = null;
    }

    private boolean isContextualSearchLongPressEnabled() {
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
                log(Log.ERROR, TAG,
                        "Contextual-search preference unavailable, policy=failClosed",
                        throwable);
            }
            return false;
        }
    }

    private ClassLoader findSystemServerClassLoader(ClassLoader preferred) {
        ClassLoader[] candidates = new ClassLoader[]{
                preferred,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                SystemServerContextualSearchHook.class.getClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Class.forName(
                        "com.android.server.contextualsearch.ContextualSearchManagerService",
                        false, candidate);
                return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private synchronized void selectSystemServerImplementation(ClassLoader classLoader)
            throws Exception {
        systemServerImpl.select(classLoader);
        log(Log.INFO, TAG, "Selected system_server contextual-search platform="
                + systemServerImpl.name());
    }

    protected void hookContextualSearchCompatibility(ClassLoader classLoader) {
        hookContextualSearchStartupGate(classLoader);
        try {
            Class<?> serviceClass = Class.forName(
                    "com.android.server.contextualsearch.ContextualSearchManagerService",
                    false, classLoader);
            Class<?> stubClass = Class.forName(
                    "com.android.server.contextualsearch.ContextualSearchManagerService"
                            + "$ContextualSearchManagerStub",
                    false, classLoader);
            installContextualSearchStartHook(stubClass);
            installContextualSearchStateHook(stubClass);
            installContextualSearchPermissionHook(serviceClass);
            installContextualSearchProviderHook(serviceClass);
            String callerPackage = systemServerImpl.isSelected()
                    && systemServerImpl.nativeLauncherOwnsContextualSearchLongPress()
                    ? MIUI_HOME : SYSTEM_UI;
            log(Log.INFO, TAG,
                    "Installed contextual-search compatibility bridge"
                            + ", callerPackage=" + callerPackage
                            + ", enabled=" + isContextualSearchLongPressEnabled());
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Contextual-search service compatibility unavailable", throwable);
        }
    }

    private void installContextualSearchStartHook(Class<?> stubClass) {
        try {
            Method start = findContextualSearchStartMethod(stubClass);
            if (start == null) {
                throw new NoSuchMethodException(
                        stubClass.getName() + ".startContextualSearch");
            }
            start.setAccessible(true);
            boolean deoptimized = deoptimize(start);
            hook(start)
                    .intercept(this::bridgeContextualSearchSystemUiCall);
            log(deoptimized ? Log.INFO : Log.WARN, TAG,
                    "Prepared contextual-search start boundary"
                            + ", executable=" + start
                            + ", deoptimized=" + deoptimized);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Contextual-search start boundary unavailable", throwable);
        }
    }

    private void installContextualSearchStateHook(Class<?> stubClass) {
        try {
            Method state = findContextualSearchStateMethod(stubClass);
            if (state == null) {
                throw new NoSuchMethodException(
                        stubClass.getName() + ".getContextualSearchState");
            }
            state.setAccessible(true);
            boolean deoptimized = deoptimize(state);
            hook(state)
                    .intercept(this::bridgeContextualSearchProviderCall);
            log(deoptimized ? Log.INFO : Log.WARN, TAG,
                    "Prepared contextual-search provider callback boundary"
                            + ", executable=" + state
                            + ", deoptimized=" + deoptimized);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Contextual-search provider callback boundary unavailable", throwable);
        }
    }

    private void installContextualSearchPermissionHook(Class<?> serviceClass) {
        try {
            Method permission = serviceClass.getDeclaredMethod(
                    "enforcePermission", String.class);
            permission.setAccessible(true);
            boolean deoptimized = deoptimize(permission);
            hook(permission)
                    .intercept(this::scopeContextualSearchPermission);
            log(deoptimized ? Log.INFO : Log.WARN, TAG,
                    "Prepared contextual-search permission boundary"
                            + ", deoptimized=" + deoptimized);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Contextual-search permission boundary unavailable", throwable);
        }
    }

    private void installContextualSearchProviderHook(Class<?> serviceClass) {
        try {
            Method provider = serviceClass.getDeclaredMethod(
                    "getContextualSearchPackageName");
            provider.setAccessible(true);
            boolean deoptimized = deoptimize(provider);
            hook(provider)
                    .intercept(this::scopeContextualSearchProvider);
            log(deoptimized ? Log.INFO : Log.WARN, TAG,
                    "Prepared contextual-search provider boundary"
                            + ", deoptimized=" + deoptimized);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Contextual-search provider boundary unavailable", throwable);
        }
    }

    protected void hookContextualSearchStartupGate(ClassLoader classLoader) {
        try {
            resolveContextualSearchPackageResourceId(classLoader);
            Class<?> systemServerClass = Class.forName(
                    "com.android.server.SystemServer", false, classLoader);
            Method gate = systemServerClass.getDeclaredMethod(
                    "deviceHasConfigString", Context.class, int.class);
            gate.setAccessible(true);
            hook(gate)
                    .intercept(this::enableContextualSearchServiceAtBoot);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
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
        boolean alwaysRegister = systemServerImpl.isSelected()
                && systemServerImpl.alwaysRegisterContextualSearchService();
        boolean preferenceEnabled = isContextualSearchLongPressEnabled();
        if (requestedResource instanceof Number
                && ((Number) requestedResource).intValue() == resourceId
                && (alwaysRegister || preferenceEnabled)) {
            log(Log.INFO, TAG,
                    "Enabled ContextualSearchManagerService startup"
                            + ", providerConfiguredAtCallTime=true"
                            + ", platform=" + (systemServerImpl.isSelected()
                            ? systemServerImpl.name() : "unresolved")
                            + ", alwaysRegister=" + alwaysRegister
                            + ", preferenceEnabled=" + preferenceEnabled);
            return Boolean.TRUE;
        }
        return chain.proceed();
    }

    protected Object bridgeContextualSearchSystemUiCall(
            XposedInterface.Chain chain) throws Throwable {
        if (!systemServerImpl.isSelected()) {
            log(Log.WARN, TAG,
                    "Contextual-search caller policy unavailable; preserving platform call");
            return chain.proceed();
        }
        String requiredPackage = systemServerImpl
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
        if (contextualSearchBridgeAcceptedCalls.incrementAndGet() == 1) {
            log(Log.INFO, TAG, "Accepted contextual-search bridge call"
                    + ", callerPackage=" + requiredPackage
                    + ", method=" + chain.getExecutable());
        }
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
            log(Log.WARN, TAG,
                    "Rejected contextual-search caller"
                            + ", uid=" + callingUid
                            + ", requiredPackage=" + requiredPackage);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
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
            log(Log.WARN, TAG,
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

    /** The contextual-search hook only needs the two platform policy bits. */
    private static final class SystemServerImpl {
        private volatile boolean selected;
        private volatile boolean android17;
        private volatile ClassLoader selectedClassLoader;

        synchronized void select(ClassLoader classLoader) throws Exception {
            if (classLoader == null) {
                throw new ClassNotFoundException("system_server ClassLoader is null");
            }
            if (selected && selectedClassLoader == classLoader) {
                return;
            }
            Class<?> transitionClass = Class.forName(
                    "com.android.server.wm.Transition", false, classLoader);
            if (matchesAndroid17(transitionClass)) {
                android17 = true;
            } else if (matchesAndroid16(transitionClass)) {
                android17 = false;
            } else {
                throw new NoSuchMethodException(
                        "Unsupported Transition.calculateTransitionInfo platform shape");
            }
            selectedClassLoader = classLoader;
            selected = true;
        }

        boolean isSelected() {
            return selected;
        }

        String name() {
            if (!selected) {
                return "unresolved";
            }
            return android17 ? "android17" : "android16";
        }

        boolean nativeLauncherOwnsContextualSearchLongPress() {
            return selected && android17;
        }

        boolean alwaysRegisterContextualSearchService() {
            return selected && !android17;
        }

        private static boolean matchesAndroid16(Class<?> transitionClass) {
            for (Method method : transitionClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("calculateTransitionInfo".equals(method.getName())
                        && parameters.length == 5
                        && parameters[0] == int.class
                        && parameters[1] == int.class
                        && "java.util.ArrayList".equals(parameters[2].getName())
                        && parameters[3] == android.view.SurfaceControl.Transaction.class
                        && parameters[4] == int.class) {
                    return true;
                }
            }
            return false;
        }

        private static boolean matchesAndroid17(Class<?> transitionClass) {
            for (Method method : transitionClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("calculateTransitionInfo".equals(method.getName())
                        && parameters.length == 6
                        && parameters[0] == int.class
                        && parameters[1] == int.class
                        && "java.util.ArrayList".equals(parameters[2].getName())
                        && parameters[3] == android.view.SurfaceControl.Transaction.class
                        && parameters[4] == int.class
                        && "com.android.server.wm.TransitionStub".equals(
                        parameters[5].getName())) {
                    return true;
                }
            }
            return false;
        }
    }
}
