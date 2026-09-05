package dev.codex.miuibackgesturehook.hooks.googleapp;

import android.app.ActivityThread;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/** Optional Android 16 Circle-to-Search Live Translate gates. */
@Hooker.XposedHooker(
        name = "GoogleLiveTranslateHook",
        targets = "com.google.android.googlequicksearchbox",
        order = 120)
public final class GoogleLiveTranslateHook extends HookerBridge {
    private static final String TAG = "GoogleLiveTranslateHook";
    private static final String LIVE_TRANSLATE_SYSTEM_FEATURE =
            "com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE";
    private static final int LIVE_TRANSLATE_ACTION_ID = 271520;

    private volatile SharedPreferences preferences;
    private volatile boolean preferenceFailureLogged;

    @Override
    public void onPackageLoad() {
        installHooks();
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return true;
    }

    private void installHooks() {
        installSystemFeatureHook();
        String sourceDir = applicationInfo.sourceDir;
        if (sourceDir == null || sourceDir.isEmpty()) {
            log(Log.WARN, TAG,
                    "Google App source path unavailable; Dex-resolved gates stay stock");
            return;
        }
        try {
            DexKitLoader.ensureLoaded();
            try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
                Class<?> actionClass = resolveActionClass(bridge);
                if (actionClass == null) {
                    log(Log.WARN, TAG,
                            "Could not uniquely resolve the Android 16 Live Translate action");
                    return;
                }
                installActionVisibilityHook(actionClass);
                installCapabilityHook(actionClass);
            }
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install Live Translate Dex hooks", throwable);
        }
    }

    private void installSystemFeatureHook() {
        try {
            Class<?> packageManagerClass = Class.forName(
                    "android.app.ApplicationPackageManager", false, classLoader);
            Method method = packageManagerClass.getDeclaredMethod(
                    "hasSystemFeature", String.class);
            method.setAccessible(true);
            hook(method).intercept(this::overrideSystemFeature);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Live Translate system-feature gate unavailable", throwable);
        }
    }

    private Class<?> resolveActionClass(DexKitBridge bridge) throws Throwable {
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
                log(Log.WARN, TAG, "Ambiguous Live Translate action classes: "
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
        hook(visibility).intercept(XposedInterface.Chain::proceed);
        log(deoptimized ? Log.INFO : Log.WARN, TAG,
                "Prepared Live Translate action visibility, owner="
                        + actionClass.getName() + ", deoptimized=" + deoptimized);
    }

    private void installCapabilityHook(Class<?> actionClass) throws Throwable {
        Method capability = findCapabilityMethod(actionClass);
        if (capability == null) {
            throw new NoSuchMethodException(
                    actionClass.getName() + " Live Translate capability");
        }
        boolean deoptimized = deoptimize(capability);
        hook(capability).intercept(chain -> {
            Object original = chain.proceed();
            return isEnabled() ? Boolean.TRUE : original;
        });
        log(deoptimized ? Log.INFO : Log.WARN, TAG,
                "Prepared Live Translate capability, executable="
                        + capability + ", deoptimized=" + deoptimized);
    }

    private Object overrideSystemFeature(XposedInterface.Chain chain) throws Throwable {
        Object original = chain.proceed();
        if (chain.getArgs().size() == 1
                && LIVE_TRANSLATE_SYSTEM_FEATURE.equals(chain.getArgs().get(0))
                && isEnabled()) {
            return Boolean.TRUE;
        }
        return original;
    }

    private boolean isEnabled() {
        try {
            SharedPreferences current = preferences;
            if (current == null) {
                synchronized (this) {
                    current = preferences;
                    if (current == null) {
                        current = getRemotePreferences(PredictiveBackPreferences.GROUP);
                        preferences = current;
                    }
                }
            }
            boolean enabled = current.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE);
            boolean contextualSearch = current.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            preferenceFailureLogged = false;
            return enabled && contextualSearch;
        } catch (Throwable throwable) {
            if (!preferenceFailureLogged) {
                preferenceFailureLogged = true;
                log(Log.ERROR, TAG,
                        "Live Translate preference unavailable; preserving Google behavior",
                        throwable);
            }
            return false;
        }
    }

    private static Method findCapabilityMethod(Class<?> actionClass) {
        Constructor<?> matching = null;
        for (Constructor<?> constructor : actionClass.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 3) {
                if (matching != null) {
                    return null;
                }
                matching = constructor;
            }
        }
        if (matching == null) {
            return null;
        }
        Class<?>[] parameters = matching.getParameterTypes();
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
}
