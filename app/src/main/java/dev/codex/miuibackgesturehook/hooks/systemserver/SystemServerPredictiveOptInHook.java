package dev.codex.miuibackgesturehook.hooks.systemserver;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;
import android.window.WindowOnBackInvokedDispatcher;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedInterface;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;

@Hooker.XposedHooker(
        name = "SystemServerPredictiveOptInHook",
        targets = "system",
        order = 110)
public final class SystemServerPredictiveOptInHook extends HookerBridge {
    private static final String TAG = "SystemServerPredictiveOptInHook";
    private static final String WINDOW_ON_BACK_INVOKED_DISPATCHER =
            WindowOnBackInvokedDispatcher.class.getName();
    private static final int ANDROID_17_API_LEVEL = 37;
    private static final int APPLICATION_PREDICTIVE_BACK_ENABLE_FLAG = 0x8;
    private static final int ACTIVITY_PREDICTIVE_BACK_ENABLE_FLAG = 0x4;
    private static final int ACTIVITY_PREDICTIVE_BACK_DISABLE_FLAG = 0x8;

    private volatile SharedPreferences predictiveBackPreferences;
    private volatile boolean predictiveBackPreferencesFailureLogged;
    private volatile boolean predictiveBackApplicationMetadataFailureLogged;

    @Override
    public void onPackageLoad() {
        try {
            ClassLoader serverLoader = findSystemServerClassLoader(classLoader);
            if (serverLoader == null) {
                throw new IllegalStateException("system_server ClassLoader unavailable");
            }
            hookPredictiveBackOptInMetadata(serverLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install system predictive opt-in", throwable);
        }
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        predictiveBackPreferences = null;
    }

    private ClassLoader findSystemServerClassLoader(ClassLoader preferred) {
        ClassLoader[] candidates = new ClassLoader[]{
                preferred,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                SystemServerPredictiveOptInHook.class.getClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Class.forName(WINDOW_ON_BACK_INVOKED_DISPATCHER, false, candidate);
                return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void hookPredictiveBackOptInMetadata(ClassLoader classLoader) {
        try {
            Method method = resolvePredictiveBackOptInMethod(classLoader);
            if (method == null) {
                log(Log.WARN, TAG,
                        "Predictive-back opt-in check not found in system_server");
                return;
            }
            method.setAccessible(true);
            hook(method)
                    .intercept(this::injectSelectedPredictiveBackMetadata);
            log(Log.INFO, TAG, "Hooked predictive-back opt-in metadata"
                    + ", owner=system_server"
                    + ", policy=selectedApplications"
                    + ", preferencesGroup=" + PredictiveBackPreferences.GROUP);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
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

    private Object injectSelectedPredictiveBackMetadata(XposedInterface.Chain chain)
            throws Throwable {
        Object activityInfoArgument = chain.getArg(0);
        if (!(activityInfoArgument instanceof ActivityInfo activityInfo)) {
            return chain.proceed();
        }
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
        if (applicationOptInEnabled) {
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

    private Boolean readApplicationPredictiveBackOptInEnabled(Object applicationInfo) {
        if (applicationInfo == null) {
            return null;
        }
        try {
            int privateFlagsExt = ((Number) readField(
                    applicationInfo, "privateFlagsExt")).intValue();
            predictiveBackApplicationMetadataFailureLogged = false;
            return (privateFlagsExt & APPLICATION_PREDICTIVE_BACK_ENABLE_FLAG) != 0;
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

    private boolean isPredictiveBackOptInSelected(String packageName) {
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
            return packages.contains(packageName);
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
}
