package dev.codex.miuibackgesturehook.hooks.googleapp;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/** Optional Lensient screen-thumbnail retention gate for the contextual search box. */
@Hooker.XposedHooker(
        name = "GoogleLensContextualSearchboxHook",
        targets = "com.google.android.googlequicksearchbox",
        order = 130)
public final class GoogleLensContextualSearchboxHook extends HookerBridge {
    private static final String TAG = "GoogleLensSearchboxHook";
    private static final String SCREEN_CAPABILITY_MARKER = "vidcip";

    private volatile SharedPreferences preferences;
    private volatile boolean preferenceFailureLogged;
    private volatile boolean overrideLogged;
    private final AtomicInteger dexResolutionInFlight = new AtomicInteger();

    @Override
    public void onPackageLoad() {
        installHook();
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return dexResolutionInFlight.get() == 0;
    }

    private void installHook() {
        String sourceDir = applicationInfo.sourceDir;
        if (sourceDir == null || sourceDir.isEmpty()) {
            log(Log.WARN, TAG,
                    "Google App source path unavailable; Lens screen capability stays stock");
            return;
        }
        dexResolutionInFlight.incrementAndGet();
        try {
            DexKitLoader.ensureLoaded();
            try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
                LensScreenCapabilityTarget target = resolveUniqueTarget(bridge);
                if (target == null) {
                    log(Log.WARN, TAG,
                            "Lens screen capability marker is missing or ambiguous");
                    return;
                }
                int deoptimized = deoptimize(target.method) ? 1 : 0;
                for (Method caller : target.callers) {
                    if (deoptimize(caller)) {
                        deoptimized++;
                    }
                }
                hook(target.method).intercept(chain -> {
                    Object original = chain.proceed();
                    if (!Boolean.FALSE.equals(original) || !isEnabled()) {
                        return original;
                    }
                    if (!overrideLogged) {
                        overrideLogged = true;
                        log(Log.INFO, TAG,
                                "Enabled exact Lens screen-thumbnail retention gate: "
                                        + chain.getExecutable());
                    }
                    return Boolean.TRUE;
                });
                log(deoptimized == target.callers.size() + 1
                                ? Log.INFO : Log.WARN, TAG,
                        "Prepared Lens screen capability, executable="
                                + target.method + ", deoptimizedCallers="
                                + deoptimized + "/" + (target.callers.size() + 1));
            }
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to resolve Lens contextual-searchbox capability", throwable);
        } finally {
            dexResolutionInFlight.decrementAndGet();
        }
    }

    private LensScreenCapabilityTarget resolveUniqueTarget(DexKitBridge bridge)
            throws Throwable {
        FindMethod query = FindMethod.create().matcher(
                MethodMatcher.create()
                        .paramCount(1)
                        .returnType("boolean")
                        .usingEqStrings(SCREEN_CAPABILITY_MARKER));
        MethodData resolved = null;
        for (MethodData candidate : bridge.findMethod(query)) {
            if (candidate.getParamCount() != 1
                    || !"boolean".equals(candidate.getReturnTypeName())) {
                continue;
            }
            if (resolved != null && !resolved.equals(candidate)) {
                return null;
            }
            resolved = candidate;
        }
        if (resolved == null) {
            return null;
        }
        List<Method> callers = new ArrayList<>();
        for (MethodData callerData : resolved.getCallers()) {
            if (!callerData.isMethod()) {
                continue;
            }
            Method caller = callerData.getMethodInstance(classLoader);
            if (!callers.contains(caller)) {
                callers.add(caller);
            }
        }
        return new LensScreenCapabilityTarget(
                resolved.getMethodInstance(classLoader), callers);
    }

    private static final class LensScreenCapabilityTarget {
        final Method method;
        final List<Method> callers;

        LensScreenCapabilityTarget(Method method, List<Method> callers) {
            this.method = method;
            this.callers = callers;
        }
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
                    PredictiveBackPreferences.KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX,
                    PredictiveBackPreferences.DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX);
            boolean contextualSearch = current.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            preferenceFailureLogged = false;
            return enabled && contextualSearch;
        } catch (Throwable throwable) {
            if (!preferenceFailureLogged) {
                preferenceFailureLogged = true;
                log(Log.ERROR, TAG,
                        "Lens searchbox preference unavailable; preserving Google behavior",
                        throwable);
            }
            return false;
        }
    }
}
