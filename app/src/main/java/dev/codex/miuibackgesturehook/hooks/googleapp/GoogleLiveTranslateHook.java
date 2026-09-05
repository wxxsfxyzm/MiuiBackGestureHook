package dev.codex.miuibackgesturehook.hooks.googleapp;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import dev.codex.miuibackgesturehook.util.ReflectionHelper;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

@Hooker.XposedHooker(name = "GoogleQuickSearchBoxHook", targets = "com.google.android.googlequicksearchbox")
public final class GoogleLiveTranslateHook extends HookerBridge {
    static {
        System.loadLibrary("dexkit");
    }
    private record GoogleLensScreenCapabilityTarget(Method capability, Constructor<?> coordinatorConstructor, List<Method> callers) { }

    public static final String TAG = "GoogleLiveTranslateHook";
    private static final String LIVE_TRANSLATE_SYSTEM_FEATURE =
            "com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE";
    private static final int LIVE_TRANSLATE_ACTION_ID = 271520;
    private static final String GOOGLE_LENS_USER_NAMESPACE =
            "com.google.android.apps.search.lens.user";
    private static final String GOOGLE_LENS_AIM_SEARCHBOX_FLAG = "45781832";
    private static final String GOOGLE_LENS_AIM_SCREEN_CONTEXT_FLAG = "45765529";

    private volatile SharedPreferences liveTranslatePreferences;

    @Override
    public void onPackageLoad() {
        try {
            Class<?> packageManagerClass = ReflectionHelper.findClass("android.app.ApplicationPackageManager",classLoader);
            Method method = packageManagerClass.getDeclaredMethod("hasSystemFeature", String.class);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                List<Object> args = chain.getArgs();
                if (args.size() == 1 && LIVE_TRANSLATE_SYSTEM_FEATURE.equals(args.getFirst()) && isContextualSearchLiveTranslateEnabled()) {
                    return Boolean.TRUE;
                }
                return result;
            });

            try (DexKitBridge bridge = DexKitBridge.create(applicationInfo.sourceDir)) {
                try {
                    Class<?> actionClass = resolveLiveTranslateActionClass(classLoader, bridge);
                    if (actionClass == null) {
                        log(Log.WARN, TAG, "Could not uniquely resolve the Android 16 live-translate action");
                    } else {
                        installActionVisibilityHook(actionClass);
                        installCapabilityHook(actionClass);
                    }
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "Failed to install Google live-translate compatibility", throwable);
                }
                try {
                    installGoogleLensAimScreenCapabilityHook(classLoader, bridge);
                } catch (Throwable throwable) {
                    log(Log.ERROR, TAG, "Failed to install native Google Lens screen capability", throwable);
                }
            }
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to hook GoogleLiveTranslate", throwable);
        }
    }

    private void installGoogleLensAimScreenCapabilityHook(ClassLoader classLoader, DexKitBridge bridge) throws Throwable {
        GoogleLensScreenCapabilityTarget target =
                resolveGoogleLensScreenCapabilityTarget(classLoader, bridge);
        if (target == null) {
            log(Log.WARN, TAG,
                    "Could not uniquely resolve the native Google Lens screen capability");
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
                log(Log.INFO, TAG, "Enabled native Google Lens screen capability before AIM model creation"
                                + ", gate=" + chain.getExecutable()
                                + ", original=" + result);
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

    private GoogleLensScreenCapabilityTarget resolveGoogleLensScreenCapabilityTarget(
            ClassLoader classLoader, DexKitBridge bridge) throws Throwable {
        MethodData consumer = findUniqueLensAimModelConsumer(bridge);
        if (consumer == null) {
            return null;
        }
        MethodData modelConstructor = findModelConstructorByConsumer(consumer);
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
    }

    @Nullable
    private static MethodData findModelConstructorByConsumer(MethodData consumer) {
        MethodData modelConstructor = null;
        boolean finished = false;
        MethodData resolved = null;
        for (MethodData invoke1 : consumer.getInvokes()) {
            if (!invoke1.isConstructor()
                    || !consumer.getReturnTypeName().equals(
                    invoke1.getDeclaredClassName())) {
                continue;
            }
            if (resolved != null && !resolved.equals(invoke1)) {
                finished = true;
                break;
            }
            resolved = invoke1;
        }
        if (!finished) {
            modelConstructor = resolved;
        }
        return modelConstructor;
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

    private Class<?> resolveLiveTranslateActionClass(
            ClassLoader classLoader, DexKitBridge bridge) throws Throwable {
        FindMethod query = FindMethod.create().matcher(
                MethodMatcher.create()
                        .paramCount(0)
                        .returnType("int")
                        .usingNumbers(LIVE_TRANSLATE_ACTION_ID));
        MethodDataList matches = bridge.findMethod(query);
        Class<?> resolved = null;
        for (MethodData match : matches) {
            if (match.getParamCount() != 0
                    || !"int".equals(match.getReturnTypeName())) {
                continue;
            }
            Class<?> candidate = match.getClassInstance(classLoader);
            if (ReflectionHelper.findExactBooleanMethod(candidate, "i") == null
                    || ReflectionHelper.findCapabilityMethod(candidate) == null) {
                continue;
            }
            if (resolved != null && resolved != candidate) {
                log(Log.WARN, TAG,
                        "Ambiguous live-translate action classes: "
                                + resolved.getName() + " and " + candidate.getName());
                return null;
            }
            resolved = candidate;
        }
        return resolved;
    }

    private void installActionVisibilityHook(Class<?> actionClass) throws Throwable {
        Method visibility = ReflectionHelper.findExactBooleanMethod(actionClass, "i");
        if (visibility == null) {
            throw new NoSuchMethodException(actionClass.getName() + ".i():boolean");
        }
        boolean deoptimized = deoptimize(visibility);
        log(deoptimized ? Log.INFO : Log.WARN, TAG,
                "Prepared live-translate action visibility"
                        + ", owner=" + actionClass.getName()
                        + ", deoptimized=" + deoptimized);
    }

    private void installCapabilityHook(Class<?> actionClass) throws Throwable {
        Method capability = ReflectionHelper.findCapabilityMethod(actionClass);
        if (capability == null) {
            throw new NoSuchMethodException(actionClass.getName() + " live-translate capability");
        }
        boolean deoptimized = deoptimize(capability);
        hook(capability).intercept(chain -> {
            Object result = chain.proceed();
            return isContextualSearchLiveTranslateEnabled() ? Boolean.TRUE : result;
        });
        log(deoptimized ? Log.INFO : Log.WARN, TAG,
                "Prepared live-translate capability gate"
                        + ", executable=" + capability
                        + ", deoptimized=" + deoptimized);
    }

    private boolean isContextualSearchLiveTranslateEnabled() {
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
            return enabled && contextualSearchEnabled;
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Live-translate preference unavailable; preserving Google App behavior",
                    throwable);
            return false;
        }
    }

    private boolean isGoogleLensContextualSearchboxEnabled() {
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
            return enabled && contextualSearchEnabled;
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Lens AIM preference unavailable; preserving Google App behavior",
                    throwable);
            return false;
        }
    }
}