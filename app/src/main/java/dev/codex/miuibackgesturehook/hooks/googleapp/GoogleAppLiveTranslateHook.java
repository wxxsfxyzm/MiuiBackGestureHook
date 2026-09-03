package dev.codex.miuibackgesturehook.hooks.googleapp;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

@Hooker.XposedHooker(name = "GoogleAppLiveTranslateRuntime", targets = "com.google.android.googlequicksearchbox")
public class GoogleAppLiveTranslateHook extends HookerBridge {
    private final static String TAG = "GoogleAppLiveTranslateHook";
    static {
        System.loadLibrary("dexkit");
    }

    private static final String GOOGLE_LENS_USER_NAMESPACE = "com.google.android.apps.search.lens.user";
    private static final String GOOGLE_LENS_AIM_SEARCHBOX_FLAG = "45781832";
    private static final String GOOGLE_LENS_AIM_SCREEN_CONTEXT_FLAG = "45765529";

    private volatile SharedPreferences liveTranslatePreferences;
    private volatile boolean liveTranslatePreferenceFailureLogged;
    private volatile boolean lensAimPreferenceFailureLogged;
    private volatile boolean lensAimScreenCapabilityLogged;

    protected void installGoogleAppHooks() {
        try {
            @SuppressLint("PrivateApi") Class<?> packageManagerClass = Class.forName("android.app.ApplicationPackageManager", false, classLoader);
            Method method = packageManagerClass.getDeclaredMethod("hasSystemFeature", String.class);
            hook(method).intercept( chain -> {
                Object result = chain.proceed();
                List<Object> args = chain.getArgs();
                if (args.size() == 1
                        && "com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE".equals(args.get(0))
                        && isContextualSearchLiveTranslateEnabled()) {
                    return Boolean.TRUE;
                }
                return result;
            });
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Live-translate system-feature gate unavailable", throwable);
        }

        try (DexKitBridge bridge = DexKitBridge.create(ActivityThread.currentApplication().getApplicationInfo().sourceDir)) {
            GoogleLensScreenCapabilityTarget target = resolveGoogleLensScreenCapabilityTarget(classLoader, bridge);
            if (target == null) {
                log(Log.WARN, TAG, "Could not uniquely resolve the native Google Lens screen capability");
                return;
            }
            int deoptimized = deoptimize(target.coordinatorConstructor) ? 1 : 0;
            for (Method caller : target.callers) {
                if (deoptimize(caller)) {
                    deoptimized++;
                }
            }
            hook(target.capability).intercept(chain -> {
                Object result = chain.proceed();
                if (isGoogleLensContextualSearchboxEnabled()) {
                    if (!lensAimScreenCapabilityLogged) {
                        lensAimScreenCapabilityLogged = true;
                        log(Log.INFO, TAG,
                                "Enabled native Google Lens screen capability before AIM model creation"
                                        + ", gate=" + chain.getExecutable()
                                        + ", original=" + result);
                    }
                    return Boolean.TRUE;
                }
                return result;
            });
            log(deoptimized == target.callers.size() + 1
                            ? Log.INFO : Log.WARN,
                    TAG, "Prepared native Google Lens screen capability"
                            + ", executable=" + target.capability
                            + ", callersDeoptimized=" + deoptimized
                            + "/" + (target.callers.size() + 1));
        }
    }

    private GoogleLensScreenCapabilityTarget resolveGoogleLensScreenCapabilityTarget(ClassLoader classLoader, DexKitBridge bridge) {
        try {
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
            if (coordinatorConstructorData == null) {
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
        } catch (Throwable t) {
            log(Log.INFO, TAG, "Failed to resolve GoogleLenScreenCapabilityTarget by DexKit, Google do breaking change?");
            return null;
        }
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

    @Override
    public void onPackageLoad() {
        installGoogleAppHooks();
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return true;
    }

    @Override
    protected void onHotReloaded(Map<String, Object> savedInstanceState) {
        installGoogleAppHooks();
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
                log(Log.ERROR, TAG,
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
            boolean enabled = preferences.getBoolean("google_lens_contextual_searchbox", false);
            boolean contextualSearchEnabled = preferences.getBoolean("contextual_search_long_press", false);
            lensAimPreferenceFailureLogged = false;
            return enabled && contextualSearchEnabled;
        } catch (Throwable throwable) {
            if (!lensAimPreferenceFailureLogged) {
                lensAimPreferenceFailureLogged = true;
                log(Log.ERROR, TAG,
                        "Lens AIM preference unavailable; preserving Google App behavior",
                        throwable);
            }
            return false;
        }
    }

}
