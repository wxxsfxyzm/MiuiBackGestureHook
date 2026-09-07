package dev.codex.miuibackgesturehook.hooks.googleapp;

import android.content.SharedPreferences;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeHookRuntime;
import io.github.libxposed.api.XposedInterface;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/** Optional Google App compatibility features, all gated by remote preferences. */
public abstract class GoogleAppLiveTranslateRuntime extends MiuiHomeHookRuntime {
    protected static final String GOOGLE_APP =
            "com.google.android.googlequicksearchbox";

    private static final String LIVE_TRANSLATE_SYSTEM_FEATURE =
            "com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE";
    private static final int LIVE_TRANSLATE_ACTION_ID = 271520;
    private static final String GOOGLE_LENS_USER_NAMESPACE =
            "com.google.android.apps.search.lens.user";
    private static final String GOOGLE_LENS_AIM_SEARCHBOX_FLAG = "45781832";
    private static final String GOOGLE_LENS_AIM_SCREEN_CONTEXT_FLAG = "45765529";

    private volatile SharedPreferences liveTranslatePreferences;
    private volatile boolean liveTranslatePreferenceFailureLogged;
    private volatile boolean lensAimPreferenceFailureLogged;
    private volatile boolean lensAimScreenCapabilityLogged;
    protected final AtomicInteger googleDexResolutionInFlight =
            new AtomicInteger();
    protected volatile String googleAppSourceDir;

    protected String resolveGoogleAppSourceDir() {
        String cached = googleAppSourceDir;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod(
                    "currentApplication");
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                cached = ((Context) application).getApplicationInfo().sourceDir;
                googleAppSourceDir = cached;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Could not recover Google App source path after hot reload", throwable);
        }
        return cached;
    }

    protected void installGoogleAppHooks(
            ClassLoader classLoader, String sourceDir, Set<String> existingHookIds) {
        googleAppSourceDir = sourceDir;
        installLiveTranslateSystemFeatureHook(classLoader, existingHookIds);

        boolean resolveLiveTranslate =
                !existingHookIds.contains("google_live_translate_action_visibility")
                        || !existingHookIds.contains("google_live_translate_capability");
        boolean resolveLensCapability =
                !existingHookIds.contains("google_lens_aim_screen_capability");
        if (!resolveLiveTranslate && !resolveLensCapability) {
            return;
        }
        if (sourceDir == null || sourceDir.isEmpty()) {
            moduleLog(Log.WARN, TAG,
                    "Google App source path unavailable; optional Google hooks remain stock");
            return;
        }

        googleDexResolutionInFlight.incrementAndGet();
        try {
            ensureDexKitLibraryLoaded();
            try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
                if (resolveLiveTranslate) {
                    try {
                        installGoogleAppLiveTranslateDexHooks(
                                classLoader, existingHookIds, bridge);
                    } catch (Throwable throwable) {
                        moduleLog(Log.ERROR, TAG,
                                "Failed to install Google live-translate compatibility",
                                throwable);
                    }
                }
                if (resolveLensCapability) {
                    try {
                        installGoogleLensAimScreenCapabilityHook(
                                classLoader, existingHookIds, bridge);
                    } catch (Throwable throwable) {
                        moduleLog(Log.ERROR, TAG,
                                "Failed to install native Google Lens screen capability",
                                throwable);
                    }
                }
            }
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to resolve optional Google App hooks", throwable);
        } finally {
            googleDexResolutionInFlight.decrementAndGet();
        }
    }

    private void installGoogleLensAimScreenCapabilityHook(
            ClassLoader classLoader,
            Set<String> existingHookIds,
            DexKitBridge bridge) throws Throwable {
        GoogleLensScreenCapabilityTarget target =
                resolveGoogleLensScreenCapabilityTarget(classLoader, bridge);
        if (target == null) {
            moduleLog(Log.WARN, TAG,
                    "Could not uniquely resolve the native Google Lens screen capability");
            return;
        }
        int deoptimized = deoptimize(target.coordinatorConstructor) ? 1 : 0;
        for (Method caller : target.callers) {
            if (deoptimize(caller)) {
                deoptimized++;
            }
        }
        if (!existingHookIds.contains("google_lens_aim_screen_capability")) {
            recordHookHandle(hook(target.capability)
                    .setId("google_lens_aim_screen_capability")
                    .intercept(this::overrideGoogleLensScreenCapability));
        }
        moduleLog(deoptimized == target.callers.size() + 1
                        ? Log.INFO : Log.WARN,
                TAG, "Prepared native Google Lens screen capability"
                        + ", executable=" + target.capability
                        + ", callersDeoptimized=" + deoptimized
                        + "/" + (target.callers.size() + 1));
    }

    private GoogleLensScreenCapabilityTarget resolveGoogleLensScreenCapabilityTarget(
            ClassLoader classLoader, DexKitBridge bridge) throws Throwable {
            MethodData consumer = findUniqueLensAimModelConsumer(bridge);
            if (consumer == null) {
                return null;
            }
            MethodData modelConstructor = findConstructedReturnType(consumer);
            if (modelConstructor == null
                    || modelConstructor.getParamTypeNames().size() <= 6) {
                return null;
            }

            String coordinatorName = modelConstructor.getParamTypeNames().get(6);
            Class<?> coordinatorClass = Class.forName(
                    coordinatorName, false, classLoader);
            FindMethod constructorQuery = FindMethod.create().matcher(
                    MethodMatcher.create()
                            .declaredClass(coordinatorClass)
                            .name("<init>"));
            MethodData coordinatorConstructorData = null;
            MethodData capabilityData = null;
            for (MethodData candidate : bridge.findMethod(constructorQuery)) {
                MethodData constructorCapability = null;
                for (MethodData invoke : candidate.getInvokes()) {
                    if (!invoke.isMethod()
                            || invoke.getParamCount() != 0
                            || !"boolean".equals(invoke.getReturnTypeName())
                            || coordinatorName.equals(invoke.getDeclaredClassName())) {
                        continue;
                    }
                    if (constructorCapability != null
                            && !constructorCapability.equals(invoke)) {
                        constructorCapability = null;
                        break;
                    }
                    constructorCapability = invoke;
                }
                if (constructorCapability == null) {
                    continue;
                }
                if (coordinatorConstructorData != null
                        && (!coordinatorConstructorData.equals(candidate)
                        || !capabilityData.equals(constructorCapability))) {
                    return null;
                }
                coordinatorConstructorData = candidate;
                capabilityData = constructorCapability;
            }
            if (coordinatorConstructorData == null || capabilityData == null) {
                return null;
            }

            List<Method> callers = new ArrayList<>();
            for (MethodData caller : capabilityData.getCallers()) {
                if (!caller.isMethod()) {
                    continue;
                }
                Method method = caller.getMethodInstance(classLoader);
                if (!callers.contains(method)) {
                    callers.add(method);
                }
            }
            return new GoogleLensScreenCapabilityTarget(
                    capabilityData.getMethodInstance(classLoader),
                    coordinatorConstructorData.getConstructorInstance(classLoader),
                    callers);
    }

    private static MethodData findUniqueLensAimModelConsumer(DexKitBridge bridge) {
        FindMethod query = FindMethod.create().matcher(
                MethodMatcher.create()
                        .paramCount(0)
                        .usingEqStrings(
                                GOOGLE_LENS_USER_NAMESPACE,
                                GOOGLE_LENS_AIM_SEARCHBOX_FLAG,
                                GOOGLE_LENS_AIM_SCREEN_CONTEXT_FLAG));
        MethodData resolved = null;
        for (MethodData candidate : bridge.findMethod(query)) {
            if ("void".equals(candidate.getReturnTypeName())) {
                continue;
            }
            if (resolved != null && !resolved.equals(candidate)) {
                return null;
            }
            resolved = candidate;
        }
        return resolved;
    }

    private static MethodData findConstructedReturnType(MethodData consumer) {
        MethodData resolved = null;
        for (MethodData invoke : consumer.getInvokes()) {
            if (!invoke.isConstructor()
                    || !consumer.getReturnTypeName().equals(
                            invoke.getDeclaredClassName())) {
                continue;
            }
            if (resolved != null && !resolved.equals(invoke)) {
                return null;
            }
            resolved = invoke;
        }
        return resolved;
    }

    protected Object overrideGoogleLensScreenCapability(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (isGoogleLensContextualSearchboxEnabled()) {
            if (!lensAimScreenCapabilityLogged) {
                lensAimScreenCapabilityLogged = true;
                moduleLog(Log.INFO, TAG,
                        "Enabled native Google Lens screen capability before AIM model creation"
                                + ", gate=" + chain.getExecutable()
                                + ", original=" + result);
            }
            return Boolean.TRUE;
        }
        return result;
    }

    private static final class GoogleLensScreenCapabilityTarget {
        final Method capability;
        final Constructor<?> coordinatorConstructor;
        final List<Method> callers;

        GoogleLensScreenCapabilityTarget(
                Method capability,
                Constructor<?> coordinatorConstructor,
                List<Method> callers) {
            this.capability = capability;
            this.coordinatorConstructor = coordinatorConstructor;
            this.callers = callers;
        }
    }

    private void installGoogleAppLiveTranslateDexHooks(
            ClassLoader classLoader,
            Set<String> existingHookIds,
            DexKitBridge bridge) throws Throwable {
        Class<?> actionClass = resolveLiveTranslateActionClass(classLoader, bridge);
        if (actionClass == null) {
            moduleLog(Log.WARN, TAG,
                    "Could not uniquely resolve the Android 16 live-translate action");
            return;
        }
        if (!existingHookIds.contains("google_live_translate_action_visibility")) {
            installActionVisibilityHook(actionClass);
        }
        if (!existingHookIds.contains("google_live_translate_capability")) {
            installCapabilityHook(actionClass);
        }
    }

    private void installLiveTranslateSystemFeatureHook(
            ClassLoader classLoader, Set<String> existingHookIds) {
        if (existingHookIds.contains("google_live_translate_system_feature")) {
            return;
        }
        try {
            Class<?> packageManagerClass = Class.forName(
                    "android.app.ApplicationPackageManager", false, classLoader);
            Method method = packageManagerClass.getDeclaredMethod(
                    "hasSystemFeature", String.class);
            recordHookHandle(hook(method)
                    .setId("google_live_translate_system_feature")
                    .intercept(this::overrideLiveTranslateSystemFeature));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Live-translate system-feature gate unavailable", throwable);
        }
    }

    private Class<?> resolveLiveTranslateActionClass(
            ClassLoader classLoader, DexKitBridge bridge) throws Throwable {
            FindMethod query = FindMethod.create().matcher(
                    MethodMatcher.create()
                            .paramCount(0)
                            .returnType("int")
                            .usingNumbers(Integer.valueOf(LIVE_TRANSLATE_ACTION_ID)));
            MethodDataList matches = bridge.findMethod(query);
            Class<?> resolved = null;
            for (MethodData match : matches) {
                if (match.getParamCount() != 0
                        || !"int".equals(match.getReturnTypeName())) {
                    continue;
                }
                Class<?> candidate = match.getClassInstance(classLoader);
                if (findExactBooleanMethod(candidate, "i") == null
                        || findCapabilityMethod(candidate) == null) {
                    continue;
                }
                if (resolved != null && resolved != candidate) {
                    moduleLog(Log.WARN, TAG,
                            "Ambiguous live-translate action classes: "
                                    + resolved.getName() + " and " + candidate.getName());
                    return null;
                }
                resolved = candidate;
            }
            return resolved;
    }

    private void installActionVisibilityHook(Class<?> actionClass) throws Throwable {
        Method visibility = findExactBooleanMethod(actionClass, "i");
        if (visibility == null) {
            throw new NoSuchMethodException(actionClass.getName() + ".i():boolean");
        }
        boolean deoptimized = deoptimize(visibility);
        recordHookHandle(hook(visibility)
                .setId("google_live_translate_action_visibility")
                // The native action list owns first-screen visibility.  Live Translate is
                // only added after the user taps the native Translate affordance; forcing
                // this gate makes a dead button appear on the initial Circle-to-Search page.
                .intercept(this::preserveLiveTranslateActionVisibility));
        moduleLog(deoptimized ? Log.INFO : Log.WARN, TAG,
                "Prepared live-translate action visibility"
                        + ", owner=" + actionClass.getName()
                        + ", deoptimized=" + deoptimized);
    }

    private void installCapabilityHook(Class<?> actionClass) throws Throwable {
        Method capability = findCapabilityMethod(actionClass);
        if (capability == null) {
            throw new NoSuchMethodException(
                    actionClass.getName() + " live-translate capability");
        }
        boolean deoptimized = deoptimize(capability);
        recordHookHandle(hook(capability)
                .setId("google_live_translate_capability")
                .intercept(this::overrideLiveTranslateBooleanGate));
        moduleLog(deoptimized ? Log.INFO : Log.WARN, TAG,
                "Prepared live-translate capability gate"
                        + ", executable=" + capability
                        + ", deoptimized=" + deoptimized);
    }

    private static Method findCapabilityMethod(Class<?> actionClass) {
        Constructor<?> matchingConstructor = null;
        for (Constructor<?> constructor : actionClass.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 3) {
                if (matchingConstructor != null) {
                    return null;
                }
                matchingConstructor = constructor;
            }
        }
        if (matchingConstructor == null) {
            return null;
        }
        Class<?>[] parameters = matchingConstructor.getParameterTypes();
        return parameters.length == 3
                ? findExactBooleanMethod(parameters[1], "a") : null;
    }

    private static Method findExactBooleanMethod(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            if (method.getReturnType() != Boolean.TYPE
                    || method.getParameterCount() != 0) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    protected Object overrideLiveTranslateSystemFeature(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        List<Object> args = chain.getArgs();
        if (args.size() == 1
                && LIVE_TRANSLATE_SYSTEM_FEATURE.equals(args.get(0))
                && isContextualSearchLiveTranslateEnabled()) {
            return Boolean.TRUE;
        }
        return result;
    }

    protected Object overrideLiveTranslateBooleanGate(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        return isContextualSearchLiveTranslateEnabled() ? Boolean.TRUE : result;
    }

    /**
     * Keep the native action-list visibility decision intact.  The capability hook above is
     * deliberately separate: it makes the feature usable once the native flow requests it,
     * but it must not manufacture a Translate item on the initial results page.
     */
    protected Object preserveLiveTranslateActionVisibility(
            XposedInterface.Chain chain) throws Throwable {
        return chain.proceed();
    }

    protected boolean isContextualSearchLiveTranslateEnabled() {
        try {
            SharedPreferences preferences = liveTranslatePreferences;
            if (preferences == null) {
                synchronized (this) {
                    preferences = liveTranslatePreferences;
                    if (preferences == null) {
                        preferences = getRemotePreferences(
                                PredictiveBackPreferences.GROUP);
                        liveTranslatePreferences = preferences;
                    }
                }
            }
            boolean enabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE);
            boolean contextualSearchEnabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            liveTranslatePreferenceFailureLogged = false;
            return enabled && contextualSearchEnabled;
        } catch (Throwable throwable) {
            if (!liveTranslatePreferenceFailureLogged) {
                liveTranslatePreferenceFailureLogged = true;
                moduleLog(Log.ERROR, TAG,
                        "Live-translate preference unavailable; preserving Google App behavior",
                        throwable);
            }
            return false;
        }
    }

    protected boolean isGoogleLensContextualSearchboxEnabled() {
        try {
            SharedPreferences preferences = liveTranslatePreferences;
            if (preferences == null) {
                synchronized (this) {
                    preferences = liveTranslatePreferences;
                    if (preferences == null) {
                        preferences = getRemotePreferences(
                                PredictiveBackPreferences.GROUP);
                        liveTranslatePreferences = preferences;
                    }
                }
            }
            boolean enabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX,
                    PredictiveBackPreferences.DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX);
            boolean contextualSearchEnabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            lensAimPreferenceFailureLogged = false;
            return enabled && contextualSearchEnabled;
        } catch (Throwable throwable) {
            if (!lensAimPreferenceFailureLogged) {
                lensAimPreferenceFailureLogged = true;
                moduleLog(Log.ERROR, TAG,
                        "Lens AIM preference unavailable; preserving Google App behavior",
                        throwable);
            }
            return false;
        }
    }

}
