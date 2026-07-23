package dev.codex.miuibackgesturehook.hooks.miuihome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.BackProgressAnimator;
import android.window.BackTouchTracker;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public abstract class MiuiHomeHookRuntime extends MiuiHomeReturnHomeRuntime {


    protected void hookMiuiHomeGestureStubShow(Class<?> gestureStubClass)
            throws NoSuchMethodException {
        Method method = gestureStubClass.getDeclaredMethod("showGestureStub");
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_gesture_stub_show")
                .intercept(this::restoreMiuiHomeGestureStubShow));
    }

    protected void hookMiuiHomeGestureInputArbiter(Class<?> processorClass,
                                                  Class<?> gestureStubClass)
            throws NoSuchMethodException {
        Method method = processorClass.getDeclaredMethod(
                "onPointerEvent", MotionEvent.class, gestureStubClass);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_gesture_input_arbiter")
                .intercept(this::arbitrateMiuiHomeAcceptedInput));
    }

    protected void hookMiuiHomeRecentsActualState(Class<?> recentsContainerClass)
            throws NoSuchMethodException {
        Method method = recentsContainerClass.getDeclaredMethod(
                "notifyRecentTaskState", Context.class, boolean.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_recents_actual_state_v2")
                .intercept(this::mirrorMiuiHomeRecentsActualState));
    }

    protected void hookMiuiHomeRecentsTaskLaunch(Class<?> taskViewClass)
            throws NoSuchMethodException {
        Method method = taskViewClass.getDeclaredMethod("onClick", View.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_recents_task_launch")
                .intercept(this::mirrorMiuiHomeRecentsTaskLaunch));
    }

    protected void hookMiuiHomeFullscreenState(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> stateNotifyClass = Class.forName(MIUI_HOME_STATE_NOTIFY_UTILS, false,
                classLoader);
        Method method = stateNotifyClass.getDeclaredMethod("sendStateBroadcast",
                Context.class, String.class, String.class, String.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_fullscreen_state")
                .intercept(this::mirrorMiuiHomeFullscreenState));
    }

    protected void hookMiuiHomeReturnHomeInitialize(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> overviewProxyClass = Class.forName(MIUI_HOME_OVERVIEW_PROXY_IMPL, false,
                classLoader);
        Method method = overviewProxyClass.getDeclaredMethod(
                "lambda$onInitialize$0", Bundle.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_return_home_initialize")
                .intercept(this::registerMiuiHomeReturnHome));
    }

    protected void hookMiuiHomeReturnHomeLocalHandoff(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> implementorClass = Class.forName(
                MIUI_HOME_LOCAL_WINDOW_ANIM_IMPLEMENTOR, false, classLoader);
        for (Method method : implementorClass.getDeclaredMethods()) {
            if ("getLastAnimParam".equals(method.getName())
                    && method.getParameterCount() == 1) {
                method.setAccessible(true);
                recordHookHandle(hook(method)
                        .setId("miui_home_return_home_local_handoff")
                        .intercept(this::provideMiuiHomeReturnHomeLocalHandoff));
                log(Log.INFO, TAG, "Hooked Xiaomi local return-home handoff");
                return;
            }
        }
        throw new NoSuchMethodException(
                MIUI_HOME_LOCAL_WINDOW_ANIM_IMPLEMENTOR + ".getLastAnimParam");
    }

    protected void hookMiuiHomeReturnHomeWallpaperCommands(
            ClassLoader classLoader, boolean hookSet, boolean hookAnim)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> wallpaperElementClass = Class.forName(
                MIUI_HOME_SYSTEM_WALLPAPER_ELEMENT, false, classLoader);
        Class<?> wallpaperParamsClass = Class.forName(
                MIUI_HOME_WALLPAPER_PARAMS, false, classLoader);
        if (hookSet) {
            Method setTo = wallpaperElementClass.getDeclaredMethod(
                    "setTo", wallpaperParamsClass);
            setTo.setAccessible(true);
            recordHookHandle(hook(setTo)
                    .setId("miui_home_return_home_wallpaper_set")
                    .intercept(this::observeMiuiHomeReturnHomeWallpaperSet));
        }
        if (hookAnim) {
            Method animTo = wallpaperElementClass.getDeclaredMethod(
                    "animTo", wallpaperParamsClass);
            animTo.setAccessible(true);
            recordHookHandle(hook(animTo)
                    .setId("miui_home_return_home_wallpaper_anim")
                    .intercept(this::observeMiuiHomeReturnHomeWallpaperAnim));
        }
        log(Log.INFO, TAG, "Hooked Xiaomi return-home wallpaper ownership"
                + ", set=" + hookSet + ", anim=" + hookAnim);
    }

    protected void hookMiuiHomeOpenBreakEnable(Class<?> breakControllerClass)
            throws NoSuchMethodException {
        Method method = breakControllerClass.getDeclaredMethod("enableBackBreakOpenAnim");
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_open_break_enable")
                .intercept(this::captureMiuiHomeOpenBreakEnable));
    }

    protected void hookMiuiHomeOpenBreakAnimationStart(Class<?> listenerClass)
            throws NoSuchMethodException {
        Method method = listenerClass.getDeclaredMethod("onAnimationStart", Object.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_open_break_animation_start")
                .intercept(this::mirrorMiuiHomeOpenBreakAnimationStart));
    }

    protected void hookMiuiHomeOpenBreakAnimationEnd(Class<?> listenerClass)
            throws NoSuchMethodException {
        Method method = listenerClass.getDeclaredMethod("onAnimationEnd", Object.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_open_break_animation_end")
                .intercept(this::mirrorMiuiHomeOpenBreakAnimationEnd));
    }

    protected void hookMiuiHomeLauncherOpenSnapshotTargets(
            ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> windowElementClass = Class.forName(
                MIUI_HOME_WINDOW_ELEMENT, false, classLoader);
        Class<?> remoteTargetClass = Class.forName(
                MIUI_HOME_REMOTE_ANIMATION_TARGET_COMPAT, false,
                classLoader);
        Class<?> remoteTargetArrayClass =
                Array.newInstance(remoteTargetClass, 0).getClass();
        Class<?> helperClass = Class.forName(
                MIUI_HOME_WINDOW_TRANSITION_CALLBACK_HELPER, false,
                classLoader);
        Method method = windowElementClass.getDeclaredMethod(
                "refreshTransitionCallbackHelper", remoteTargetArrayClass,
                helperClass, remoteTargetArrayClass);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_open_snapshot_targets")
                .intercept(
                        this::captureMiuiHomeLauncherOpenSnapshotAfterTargetsBound));
        log(Log.INFO, TAG,
                "Hooked Xiaomi launcher OPEN remote-target binding");
    }

    protected void hookMiuiHomeReusedCloseOpen(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> stateManagerClass = Class.forName(MIUI_HOME_STATE_MANAGER, false,
                classLoader);
        Method method = stateManagerClass.getDeclaredMethod("animToFullScreen", View.class);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_reused_close_open")
                .intercept(this::restoreMiuiHomeReusedCloseOpen));
    }

    protected void hookMiuiHomeTransitionContinuity(
            ClassLoader classLoader, boolean hookElementTransition,
            boolean hookElementLeashRearm, boolean hookElementAnimType)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> windowElementClass = Class.forName(
                MIUI_HOME_WINDOW_ELEMENT, false, classLoader);
        if (hookElementTransition) {
            Class<?> remoteTransitionInfoClass = Class.forName(
                    MIUI_HOME_REMOTE_TRANSITION_INFO, false, classLoader);
            Method injectRemoteTransition = windowElementClass.getDeclaredMethod(
                    "injectRemoteTransition", remoteTransitionInfoClass);
            injectRemoteTransition.setAccessible(true);
            recordHookHandle(hook(injectRemoteTransition)
                    .setId("miui_home_return_home_element_transition")
                    .intercept(this::prepareMiuiHomeElementTransitionContinuity));
        }
        if (hookElementLeashRearm) {
            Class<?> helperClass = Class.forName(
                    MIUI_HOME_WINDOW_TRANSITION_CALLBACK_HELPER, false,
                    classLoader);
            Method clearOpenLeash = helperClass.getDeclaredMethod(
                    "clearTempSaveOpenLeash");
            clearOpenLeash.setAccessible(true);
            recordHookHandle(hook(clearOpenLeash)
                    .setId("miui_home_return_home_element_leash_rearm")
                    .intercept(this::rearmMiuiHomeElementLeashAfterNativeClear));
        }
        if (hookElementAnimType) {
            Method animTo = null;
            for (Method method : windowElementClass.getDeclaredMethods()) {
                if ("animTo".equals(method.getName())
                        && method.getParameterCount() == 1) {
                    animTo = method;
                    break;
                }
            }
            if (animTo == null) {
                throw new NoSuchMethodException(
                        MIUI_HOME_WINDOW_ELEMENT + ".animTo(T)");
            }
            animTo.setAccessible(true);
            recordHookHandle(hook(animTo)
                    .setId("miui_home_return_home_element_anim_type")
                    .intercept(this::observeMiuiHomeElementAnimType));
        }
        log(Log.INFO, TAG, "Hooked MiuiHome transition continuity"
                + ", elementTransition=" + hookElementTransition
                + ", elementLeashRearm=" + hookElementLeashRearm
                + ", elementAnimType=" + hookElementAnimType);
    }

    protected void hookMiuiHomeUnifiedFinishEpoch(
            ClassLoader classLoader, boolean hookAnimToConfig,
            boolean hookFinishSource, boolean hookFinishApply)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> windowElementClass = Class.forName(
                MIUI_HOME_WINDOW_ELEMENT, false, classLoader);
        if (hookAnimToConfig) {
            Class<?> implementorClass = Class.forName(
                    MIUI_HOME_LOCAL_WINDOW_ANIM_IMPLEMENTOR, false,
                    classLoader);
            Class<?> rectFParamsClass = Class.forName(
                    MIUI_HOME_RECTF_PARAMS, false, classLoader);
            Method config = requireExactDeclaredMethod(
                    implementorClass, "animTo$lambda$3", "void",
                    rectFParamsClass.getName(), implementorClass.getName());
            recordHookHandle(hook(config)
                    .setId("miui_home_return_home_anim_to_config")
                    .intercept(this::observeMiuiHomeUnifiedAnimToConfigured));
        }
        if (hookFinishSource) {
            Method source = requireExactDeclaredMethod(
                    windowElementClass, "onFinishCompleted", "void");
            recordHookHandle(hook(source)
                    .setId("miui_home_return_home_finish_dispatch_source")
                    .intercept(this::captureMiuiHomeUnifiedFinishDispatch));
        }
        if (hookFinishApply) {
            Method apply = requireExactDeclaredMethod(
                    windowElementClass,
                    "onFinishCompleted$lambda$39", "void",
                    windowElementClass.getName());
            recordHookHandle(hook(apply)
                    .setId("miui_home_return_home_finish_dispatch_apply")
                    .intercept(this::guardMiuiHomeUnifiedFinishDispatch));
        }
        log(Log.INFO, TAG, "Hooked MiuiHome unified finish epochs"
                + ", animToConfig=" + hookAnimToConfig
                + ", finishSource=" + hookFinishSource
                + ", finishApply=" + hookFinishApply);
    }

    protected void hookMiuiHomePermissionMerge(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> compatClass = Class.forName(
                MIUI_HOME_WINDOW_TRANSITION_COMPAT, false, classLoader);
        Method handlerMethod = null;
        for (Method method : compatClass.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if ("handleTransitionForHandlerType".equals(method.getName())
                    && parameterTypes.length == 5
                    && "com.android.hideapi.TransitionInfoExpose".equals(
                    parameterTypes[0].getName())
                    && parameterTypes[1] == Object.class
                    && parameterTypes[2] == Object.class
                    && parameterTypes[3]
                    == SurfaceControl.Transaction.class
                    && "android.window.IRemoteTransitionFinishedCallback".equals(
                    parameterTypes[4].getName())) {
                handlerMethod = method;
                break;
            }
        }
        if (handlerMethod == null) {
            throw new NoSuchMethodException(
                    MIUI_HOME_WINDOW_TRANSITION_COMPAT
                            + ".handleTransitionForHandlerType");
        }
        handlerMethod.setAccessible(true);
        recordHookHandle(hook(handlerMethod)
                .setId("miui_home_permission_activity_merge")
                .intercept(this::preserveMiuiHomeOpenAcrossPermissionMerge));
    }

    protected Method requireExactDeclaredMethod(
            Class<?> owner, String methodName, String returnTypeName,
            String... parameterTypeNames) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())
                    || !returnTypeName.equals(method.getReturnType().getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != parameterTypeNames.length) {
                continue;
            }
            boolean exact = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!parameterTypeNames[index].equals(
                        parameterTypes[index].getName())) {
                    exact = false;
                    break;
                }
            }
            if (exact) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + methodName
                + "(" + String.join(",", parameterTypeNames) + "):"
                + returnTypeName);
    }

    protected void hookMiuiHomeNativeGeometryUpdate(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> owner = Class.forName(
                MIUI_HOME_LOCAL_WINDOW_ANIM_IMPLEMENTOR, false, classLoader);
        Method method = requireExactDeclaredMethod(owner, "onAnimUpdate", "void",
                RectF.class.getName(), "float", "float",
                MIUI_HOME_CORNER_RADII, MIUI_HOME_VALUE_CALLBACK);
        recordHookHandle(hook(method)
                .setId("miui_home_trace_on_anim_update")
                .intercept(this::captureMiuiHomeNativeGeometry));
    }

    protected void hookMiuiHomeNativeGeometryApply(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> owner = Class.forName(
                MIUI_HOME_CLIP_ANIMATION_HELPER, false, classLoader);
        Method method = requireExactDeclaredMethod(owner,
                "applySurfaceParams", "void",
                MIUI_HOME_SYNC_RT_SURFACE_APPLIER,
                MIUI_HOME_SURFACE_PARAMS_ARRAY,
                MIUI_HOME_TRANSACTION_COMPAT);
        recordHookHandle(hook(method)
                .setId("miui_home_trace_apply_surface_params")
                .intercept(this::applyMiuiHomeNativeSurfaceParams));
    }

    protected void hookMiuiHomeGeometryFrames(
            ClassLoader classLoader, boolean hookOnAnimUpdate,
            boolean hookApplySurfaceParams) {
        if (hookOnAnimUpdate) {
            try {
                hookMiuiHomeNativeGeometryUpdate(classLoader);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Failed to hook MiuiHome native geometry update", throwable);
            }
        }
        if (hookApplySurfaceParams) {
            try {
                hookMiuiHomeNativeGeometryApply(classLoader);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG,
                        "Failed to hook MiuiHome native geometry apply",
                        throwable);
            }
        }
        log(Log.INFO, TAG, "Installed MiuiHome native geometry hooks"
                + ", onAnimUpdate=" + hookOnAnimUpdate
                + ", applySurfaceParams=" + hookApplySurfaceParams);
    }

    protected void hookMiuiHomeTransitionSetupLeash(
            ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> owner = Class.forName(
                MIUI_HOME_TRANSITION_UTIL, false, classLoader);
        Method setupLeash = null;
        for (Method method : owner.getDeclaredMethods()) {
            if ("setupLeash".equals(method.getName())
                    && method.getParameterCount() == 6
                    && method.getReturnType() == void.class) {
                setupLeash = method;
            }
        }
        if (setupLeash == null) {
            throw new NoSuchMethodException(
                    MIUI_HOME_TRANSITION_UTIL + ".setupLeash");
        }
        setupLeash.setAccessible(true);
        recordHookHandle(hook(setupLeash)
                .setId("miui_home_trace_transition_setup_leash")
                .intercept(this::armMiuiHomeTransitionStartGeometry));
        log(Log.INFO, TAG,
                "Installed MiuiHome transition start-geometry hook");
    }

    @SuppressLint("SoonBlockedPrivateApi")
    protected void hookMiuiHomeStartTransactionApply(
            Set<String> existingHookIds) {
        String hookPrefix = "miui_home_trace_surface_transaction_";
        if (existingHookIds != null && existingHookIds.stream()
                .anyMatch(id -> id.startsWith(hookPrefix))) {
            return;
        }
        try {
            Method method = SurfaceControl.Transaction.class.getDeclaredMethod(
                    "apply", boolean.class);
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId(hookPrefix + "apply_boolean")
                    .intercept(this::applyMiuiHomeStartGeometry));
            log(Log.INFO, TAG,
                    "Installed MiuiHome start transaction apply hook");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed to hook MiuiHome start transaction apply", throwable);
        }
    }

    protected void hookMiuiHomeReturnHomeCloseInterruption(
            ClassLoader classLoader, boolean hookCancelSurface, boolean hookSetToOld)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> windowElementClass = Class.forName(
                MIUI_HOME_WINDOW_ELEMENT, false, classLoader);
        if (hookCancelSurface) {
            Class<?> shellTransitionCallbackClass = Class.forName(
                    MIUI_HOME_SHELL_TRANSITION_CALLBACK, false, classLoader);
            Method cancelSurface = windowElementClass.getDeclaredMethod(
                    "cancelSurfaceAnimOnly", String.class, boolean.class,
                    shellTransitionCallbackClass, Boolean.class, boolean.class,
                    shellTransitionCallbackClass, shellTransitionCallbackClass);
            cancelSurface.setAccessible(true);
            recordHookHandle(hook(cancelSurface)
                    .setId("miui_home_return_home_cancel_surface")
                    .intercept(this::captureMiuiHomeReturnHomeCloseInterruption));
        }
        if (hookSetToOld) {
            Class<?> stateManagerClass = Class.forName(
                    MIUI_HOME_STATE_MANAGER, false, classLoader);
            Method setToOld = stateManagerClass.getDeclaredMethod(
                    "setToOld", windowElementClass);
            setToOld.setAccessible(true);
            recordHookHandle(hook(setToOld)
                    .setId("miui_home_return_home_set_to_old")
                    .intercept(this::finishMiuiHomeReturnHomeCloseInterruption));
        }
        log(Log.INFO, TAG, "Hooked Xiaomi interrupted return-home handoff"
                + ", cancelSurface=" + hookCancelSurface
                + ", setToOld=" + hookSetToOld);
    }

    protected void hookMiuiHomeReturnHomeDirectCancel(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> windowElementClass = Class.forName(
                MIUI_HOME_WINDOW_ELEMENT, false, classLoader);
        Class<?> shellTransitionCallbackClass = Class.forName(
                MIUI_HOME_SHELL_TRANSITION_CALLBACK, false, classLoader);
        Method cancelDirect = windowElementClass.getDeclaredMethod(
                "cancelAnim", String.class, boolean.class,
                shellTransitionCallbackClass, Boolean.class,
                shellTransitionCallbackClass);
        cancelDirect.setAccessible(true);
        recordHookHandle(hook(cancelDirect)
                .setId("miui_home_return_home_cancel_direct")
                .intercept(this::wrapMiuiHomeReturnHomeDirectCancel));
        log(Log.INFO, TAG,
                "Hooked Xiaomi direct same-icon return-home cancellation");
    }

    protected void hookMiuiHomeReturnHomeSameIconParallel(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> stateManagerClass = Class.forName(
                MIUI_HOME_STATE_MANAGER, false, classLoader);
        Class<?> shellTransitionCallbackClass = Class.forName(
                MIUI_HOME_SHELL_TRANSITION_CALLBACK, false, classLoader);
        Method cancelAnim = stateManagerClass.getDeclaredMethod(
                "cancelAnim", String.class, boolean.class, boolean.class,
                shellTransitionCallbackClass);
        cancelAnim.setAccessible(true);
        recordHookHandle(hook(cancelAnim)
                .setId("miui_home_return_home_same_icon_parallel")
                .intercept(this::routeMiuiHomeReturnHomeSameIconParallel));
        log(Log.INFO, TAG,
                "Hooked Xiaomi same-icon predictive CLOSE parallel routing");
    }

    protected void hookMiuiHomeReturnHomeFreshOpen(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> stateManagerClass = Class.forName(
                MIUI_HOME_STATE_MANAGER, false, classLoader);
        Class<?> windowElementClass = Class.forName(
                MIUI_HOME_WINDOW_ELEMENT, false, classLoader);
        Method isOldElementReuseful = stateManagerClass.getDeclaredMethod(
                "isOldElementReuseful", windowElementClass,
                Intent.class, View.class);
        isOldElementReuseful.setAccessible(true);
        recordHookHandle(hook(isOldElementReuseful)
                .setId("miui_home_return_home_fresh_open")
                .intercept(this::forceMiuiHomeReturnHomeFreshOpen));
        log(Log.INFO, TAG,
                "Hooked Xiaomi non-reusable same-icon fresh OPEN selection");
    }

    protected void hookMiuiHomeDrawerState(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> managerClass = Class.forName(MIUI_HOME_LAUNCHER_STATE_MANAGER, false,
                classLoader);
        Class<?> stateClass = Class.forName(MIUI_HOME_LAUNCHER_STATE, false, classLoader);
        Method method = managerClass.getDeclaredMethod("onStateTransitionStart", stateClass);
        method.setAccessible(true);
        recordHookHandle(hook(method)
                .setId("miui_home_drawer_state")
                .intercept(this::mirrorMiuiHomeDrawerState));
    }

    protected void hookMiuiHomeEditingState(ClassLoader classLoader) {
        try {
            Class<?> launcherClass = Class.forName(
                    MIUI_HOME_BASE_LAUNCHER, false, classLoader);
            Method method = launcherClass.getDeclaredMethod("notifyBackGestureStatus");
            method.setAccessible(true);
            recordHookHandle(hook(method)
                    .setId("miui_home_editing_state")
                    .intercept(this::mirrorMiuiHomeEditingState));
        } catch (Throwable throwable) {
            // Editing callbacks are an additive launcher surface. Preserve all established
            // MiuiHome arbitration and return-home hooks on builds without this lifecycle point.
            log(Log.WARN, TAG, "MiuiHome editing-state lifecycle unavailable", throwable);
        }
    }

    protected Object captureMiuiHomeOpenBreakEnable(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object controller = chain.getThisObject();
        miuiHomeOpenBreakController = controller;
        miuiHomeOpenBreakGeneration = nextMiuiHomeOpenBreakGeneration();
        miuiHomeOpenBreakAnimationIdentity = null;
        miuiHomeOpenBreakGenerationPrepared = true;
        miuiHomeOpenBreakAnimationActive = false;
        miuiHomeOpenBreakCommandPending = false;
        refreshMiuiHomeOpenBreakAvailability(controller, "enable");
        return result;
    }

    protected Object mirrorMiuiHomeOpenBreakAnimationStart(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object animationListener = chain.getThisObject();
        Object animationIdentity = chain.getArg(0);
        MiuiHomeReturnHomeController returnHomeController =
                miuiHomeReturnHomeController;
        if (returnHomeController != null
                && returnHomeController.onNativeAnimationStart(
                animationListener, animationIdentity)) {
            return result;
        }
        invalidateMiuiHomeLauncherOpenSnapshot(null, "animationStart");
        if (!miuiHomeOpenBreakGenerationPrepared
                || miuiHomeOpenBreakGeneration == 0L) {
            miuiHomeOpenBreakGeneration = nextMiuiHomeOpenBreakGeneration();
        }
        miuiHomeOpenBreakGenerationPrepared = false;
        miuiHomeOpenBreakAnimationIdentity = animationIdentity;
        miuiHomeOpenBreakAnimationActive = true;
        miuiHomeOpenBreakCommandPending = false;
        long generation = miuiHomeOpenBreakGeneration;
        long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
        // The StateManager listener runs from inside the implementor's start callback. At
        // that point isRunning()/lastAnimType can still expose the pre-start values. Query on
        // the next main-loop turn, after animTo() has finished publishing the active state.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (miuiHomeOpenBreakCallbackEpoch.get() == callbackEpoch
                    && miuiHomeOpenBreakGeneration == generation
                    && miuiHomeOpenBreakAnimationIdentity == animationIdentity
                    && miuiHomeOpenBreakAnimationActive) {
                refreshMiuiHomeOpenBreakAvailability(
                        miuiHomeOpenBreakController, "animationStartSettled");
                captureMiuiHomeLauncherOpenSnapshot(
                        animationListener, animationIdentity,
                        generation, callbackEpoch);
            }
        });
        return result;
    }

    protected Object mirrorMiuiHomeOpenBreakAnimationEnd(XposedInterface.Chain chain)
            throws Throwable {
        Object endedAnimationIdentity = chain.getArg(0);
        MiuiHomeReturnHomeController returnHomeController =
                miuiHomeReturnHomeController;
        if (returnHomeController != null) {
            returnHomeController.captureNativeAnimationEndBeforeListener(
                    chain.getThisObject(), endedAnimationIdentity);
        }
        Object result = chain.proceed();
        if (returnHomeController != null
                && returnHomeController.onNativeAnimationEnd(
                chain.getThisObject(), endedAnimationIdentity)) {
            return result;
        }
        invalidateMiuiHomeLauncherOpenSnapshot(
                endedAnimationIdentity, "animationEnd");
        long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
        // StateManager cleanup executes inline when this callback is already on main. Queue
        // behind the completed listener so isOpenAnimRunning() observes the final state. If
        // another OPEN has replaced this one, the native query keeps the replacement state.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (miuiHomeOpenBreakCallbackEpoch.get() != callbackEpoch) {
                return;
            }
            if (miuiHomeOpenBreakAnimationIdentity == endedAnimationIdentity) {
                miuiHomeOpenBreakAnimationActive = false;
                miuiHomeOpenBreakGenerationPrepared = false;
            }
            refreshMiuiHomeOpenBreakAvailability(
                    miuiHomeOpenBreakController, "animationEnd");
        });
        return result;
    }

    protected Object captureMiuiHomeLauncherOpenSnapshotAfterTargetsBound(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            log(Log.WARN, TAG,
                    "Ignored Xiaomi launcher OPEN targets off main Looper"
                            + ", windowElement="
                            + shortObject(chain.getThisObject()));
            return result;
        }
        long nativeGeneration = miuiHomeOpenBreakGeneration;
        long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
        Object animationIdentity = miuiHomeOpenBreakAnimationIdentity;
        if (nativeGeneration == 0L || animationIdentity == null
                || !miuiHomeOpenBreakAnimationActive) {
            return result;
        }
        try {
            Object firstTarget = invokeAnyMethod(
                    result, "getFirstTarget", new Object[0]);
            if (firstTarget == null) {
                return result;
            }
            int boundTaskId = readIntFieldOrDefault(
                    firstTarget, "taskId", -1);
            Object windowElement = chain.getThisObject();
            Object stateManagerListener = readField(
                    windowElement, "stateManagerListener");
            Object stateManager = readField(
                    stateManagerListener, "this$0");
            captureMiuiHomeLauncherOpenSnapshot(
                    stateManager, windowElement, chain.getArg(1),
                    animationIdentity, nativeGeneration, callbackEpoch,
                    boundTaskId, "remoteTargetsBound");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed to capture Xiaomi launcher OPEN bound targets",
                    throwable);
        }
        return result;
    }

    protected Object prepareMiuiHomeElementTransitionContinuity(
            XposedInterface.Chain chain) throws Throwable {
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        boolean prepared = false;
        if (controller != null) {
            try {
                prepared = controller.prepareElementTransitionContinuity(
                        chain.getThisObject(), chain.getArg(0));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to inspect Xiaomi element CLOSE takeover",
                        throwable);
            }
            try {
                controller.observeUnifiedCommitTransition(
                        chain.getThisObject(), chain.getArg(0));
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to observe Xiaomi element commit transition",
                        throwable);
            }
        }
        if (controller != null && !prepared
                && controller.invalidateUnifiedCommitTransition(
                chain.getThisObject(), chain.getArg(0),
                "unsupportedElementShape")) {
            controller.invalidateElementTransitionContinuity(
                    null, "unsupportedElementShape", true);
        }
        try {
            return chain.proceed();
        } catch (Throwable throwable) {
            if (controller != null) {
                controller.invalidateUnifiedCommitTransition(
                        chain.getThisObject(), chain.getArg(0),
                        "injectRemoteTransitionThrew");
                if (prepared) {
                    controller.invalidateElementTransitionContinuity(
                            null, "injectRemoteTransitionThrew", true);
                }
            }
            throw throwable;
        }
    }

    protected Object rearmMiuiHomeElementLeashAfterNativeClear(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller != null) {
            try {
                controller.rearmElementLeashAfterNativeClear(
                        chain.getThisObject());
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to rearm predictive leash after Xiaomi clear",
                        throwable);
            }
        }
        return result;
    }

    protected Object observeMiuiHomeElementAnimType(XposedInterface.Chain chain)
            throws Throwable {
        Object params = chain.getArg(0);
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller != null) {
            try {
                controller.markUnifiedCommitAnimToEntering(
                        chain.getThisObject(), params);
            } catch (Throwable throwable) {
                boolean terminalQueued =
                        controller.onUnifiedCommitAnimToEntryFailed(
                                chain.getThisObject(), params,
                                throwable);
                log(Log.ERROR, TAG,
                        "Failed Xiaomi final animTo entry gating"
                                + ", terminalQueued="
                                + terminalQueued,
                        throwable);
                if (terminalQueued) {
                    // The exact native owner is now being cancelled on main. Do not let the
                    // failed entry configure a final spring without finish-epoch protection.
                    return null;
                }
            }
            try {
                controller.prepareUnifiedHandoffBeforeAnimTo(
                        chain.getThisObject(), params);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to prepare Xiaomi predictive handoff status",
                        throwable);
            }
        }
        Object result = chain.proceed();
        if (controller != null) {
            try {
                controller.hideElementBoundaryProviderFloatingIcon(
                        chain.getThisObject(), params);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to hide transient Xiaomi provider icon",
                        throwable);
            }
            controller.markUnifiedCommitAnimToReturned(
                    chain.getThisObject(), params);
            try {
                controller.adoptElementTransitionIfStarted(
                        chain.getThisObject(), params);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to verify Xiaomi element CLOSE adoption",
                        throwable);
            }
        }
        return result;
    }

    protected Object observeMiuiHomeUnifiedAnimToConfigured(
            XposedInterface.Chain chain) throws Throwable {
        MiuiHomeReturnHomeController controller =
                miuiHomeReturnHomeController;
        Object configLock = controller == null ? null
                : controller.resolveUnifiedAnimToConfigLock(
                chain.getArg(0));
        if (configLock != null) {
            synchronized (configLock) {
                return observeMiuiHomeUnifiedAnimToConfiguredLocked(
                        chain, controller);
            }
        }
        return observeMiuiHomeUnifiedAnimToConfiguredLocked(
                chain, controller);
    }

    protected Object observeMiuiHomeUnifiedAnimToConfiguredLocked(
            XposedInterface.Chain chain,
            MiuiHomeReturnHomeController controller) throws Throwable {
        Object ownerToken = controller == null ? null
                : controller.beginUnifiedNativeAnimToConfigHook(
                chain.getArg(0));
        Throwable hookFailure = null;
        String completionReason = "beforeOriginal";
        try {
            if (controller != null
                    && controller.shouldSkipInterruptedUnifiedAnimToConfig(
                    chain.getArg(1), chain.getArg(0))) {
                completionReason = "skippedInterruptedConfig";
                return null;
            }
            completionReason = "originalRunning";
            Object result = chain.proceed();
            completionReason = "originalReturned";
            if (controller != null) {
                controller.onUnifiedNativeAnimToConfigured(
                        chain.getArg(1), chain.getArg(0));
                completionReason = "configuredReturned";
            }
            return result;
        } catch (Throwable throwable) {
            hookFailure = throwable;
            completionReason += ":threw";
            throw throwable;
        } finally {
            if (controller != null) {
                try {
                    controller.onUnifiedNativeAnimToConfigHookCompleted(
                            chain.getArg(1), chain.getArg(0),
                            completionReason, hookFailure);
                } finally {
                    controller.finishUnifiedNativeAnimToConfigHook(
                            ownerToken, chain.getArg(0),
                            completionReason, hookFailure);
                }
            }
        }
    }

    protected Object captureMiuiHomeUnifiedFinishDispatch(
            XposedInterface.Chain chain) throws Throwable {
        MiuiHomeReturnHomeController controller =
                miuiHomeReturnHomeController;
        MiuiHomeReturnHomeController.UnifiedNativeFinishDispatchToken token =
                controller == null
                ? null : controller.beginUnifiedNativeFinishDispatch(
                chain.getThisObject());
        if (token != null && !token.allowed) {
            return null;
        }
        try {
            return chain.proceed();
        } catch (Throwable throwable) {
            if (controller != null && token != null) {
                controller.abortUnifiedNativeFinishDispatch(
                        token, "sourceThrew");
            }
            throw throwable;
        }
    }

    protected Object guardMiuiHomeUnifiedFinishDispatch(
            XposedInterface.Chain chain) throws Throwable {
        MiuiHomeReturnHomeController controller =
                miuiHomeReturnHomeController;
        Boolean proceed = controller == null ? null
                : controller.consumeUnifiedNativeFinishDispatch(
                chain.getArg(0));
        if (Boolean.FALSE.equals(proceed)) {
            return null;
        }
        return chain.proceed();
    }

    protected Object captureMiuiHomeNativeGeometry(XposedInterface.Chain chain)
            throws Throwable {
        MiuiHomeReturnHomeController controller =
                miuiHomeReturnHomeController;
        if (controller == null
                || !controller.hasEligibleNativeGeometrySession()) {
            return chain.proceed();
        }
        ReturnHomeNativeGeometrySnapshot previousPendingGeometry =
                miuiHomePendingNativeGeometry.get();
        long traceId = miuiHomeNativeGeometryFrameIds.incrementAndGet();
        ReturnHomeNativeGeometrySnapshot pendingGeometry =
                controller.prepareNativeGeometryBeforeAnimUpdate(
                        chain.getThisObject(), chain.getArg(0),
                        chain.getArg(3), traceId);
        if (pendingGeometry == null) {
            miuiHomePendingNativeGeometry.remove();
        } else {
            miuiHomePendingNativeGeometry.set(pendingGeometry);
        }
        try {
            return chain.proceed();
        } finally {
            if (previousPendingGeometry == null) {
                miuiHomePendingNativeGeometry.remove();
            } else {
                miuiHomePendingNativeGeometry.set(
                        previousPendingGeometry);
            }
        }
    }

    protected Object applyMiuiHomeNativeSurfaceParams(
            XposedInterface.Chain chain) throws Throwable {
        Object surfaceParams = chain.getArg(1);
        MiuiHomeReturnHomeController controller =
                miuiHomeReturnHomeController;
        if (controller == null
                || !controller.hasEligibleNativeGeometrySession()) {
            return chain.proceed();
        }
        ReturnHomeNativeGeometrySnapshot pendingGeometry =
                miuiHomePendingNativeGeometry.get();
        long frameId = pendingGeometry == null
                ? miuiHomeNativeGeometryFrameIds.incrementAndGet()
                : pendingGeometry.frameTraceId;
        ReturnHomeNativeGeometrySnapshot surfaceGeometry =
                controller.captureNativeGeometryFromSurfaceParams(
                        frameId, surfaceParams);
        ReturnHomeNativeGeometrySnapshot frameGeometry =
                surfaceGeometry == null ? pendingGeometry : surfaceGeometry;
        Object geometryApplyLock = controller.resolveNativeGeometryFrameApplyLock(
                frameId, chain.getArg(0), frameGeometry, surfaceParams);
        if (geometryApplyLock == null) {
            return chain.proceed();
        }
        synchronized (geometryApplyLock) {
            return chain.proceed();
        }
    }

    protected Object armMiuiHomeTransitionStartGeometry(
            XposedInterface.Chain chain) throws Throwable {
        MiuiHomeReturnHomeController controller =
                miuiHomeReturnHomeController;
        Object result = chain.proceed();
        if (controller != null) {
            controller.armElementAndClosingLeashStartGeometry(
                    chain.getArg(0), chain.getArg(1), chain.getArg(3),
                    chain.getArg(4));
        }
        return result;
    }

    protected Object applyMiuiHomeStartGeometry(
            XposedInterface.Chain chain) throws Throwable {
        if (!"apply".equals(chain.getExecutable().getName())
                || chain.getArgs().size() != 1
                || !Boolean.TRUE.equals(chain.getArg(0))) {
            // Retire old diagnostic hooks and ignore unrelated apply paths.
            return chain.proceed();
        }
        Object transaction = chain.getThisObject();
        MiuiHomeReturnHomeController controller =
                miuiHomeReturnHomeController;
        Object startGeometryApplyLock = controller == null ? null
                : controller.resolveStartGeometryApplyLock(
                transaction, chain.getArgs());
        if (startGeometryApplyLock == null) {
            return chain.proceed();
        }
        synchronized (startGeometryApplyLock) {
            controller.refreshStartGeometryAtApply(transaction);
            try {
                Object result = chain.proceed();
                controller.finishStartGeometryApply(transaction, true);
                return result;
            } catch (Throwable throwable) {
                controller.finishStartGeometryApply(transaction, false);
                throw throwable;
            }
        }
    }

    protected Object preserveMiuiHomeOpenAcrossPermissionMerge(
            XposedInterface.Chain chain) throws Throwable {
        Object handlerTypeObject = chain.getArg(1);
        int handlerType = handlerTypeObject instanceof Number
                ? ((Number) handlerTypeObject).intValue() : -1;
        boolean onlyActivityRecord = Boolean.TRUE.equals(chain.getArg(2));
        MiuiHomeLauncherOpenSnapshot snapshot =
                miuiHomeLauncherOpenSnapshot.get();

        MiuiHomePermissionMergeToken pending =
                miuiHomePermissionMergeToken.get();
        if (pending != null) {
            boolean routed = false;
            PermissionActivityTransition permissionClose = null;
            try {
                if (handlerType == 99 && onlyActivityRecord) {
                    permissionClose = resolveMatchingPermissionActivityClose(
                            chain.getThisObject(), chain.getArg(0), pending);
                }
                if (permissionClose != null) {
                    routed = pending.consumed.compareAndSet(0, 1)
                            && miuiHomePermissionMergeToken.compareAndSet(
                            pending, null);
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to verify PermissionController CLOSE merge",
                        throwable);
            }
            if (routed) {
                Object[] routedArgs = chain.getArgs().toArray();
                // Xiaomi's existing handler-0/only-ActivityRecord branch applies and finishes
                // this merge without cancelling the launcher OPEN. Reuse that branch verbatim.
                routedArgs[1] = Integer.valueOf(0);
                log(Log.INFO, TAG,
                        "Preserved Xiaomi launcher OPEN across PermissionController CLOSE"
                                + ", launcherGeneration="
                                + pending.launcherOpen.generation
                                + ", taskId="
                                + pending.launcherOpen.mainTask.taskId
                                + ", openDebugId="
                                + pending.permissionOpen.debugId
                                + ", closeDebugId="
                                + readTransitionDebugId(chain.getArg(0))
                                + ", animationType="
                                + pending.launcherOpen.animationType);
                return chain.proceed(routedArgs);
            }
            if (miuiHomePermissionMergeToken.compareAndSet(pending, null)) {
                log(Log.INFO, TAG,
                        "Invalidated non-adjacent PermissionController merge"
                                + ", launcherGeneration="
                                + pending.launcherOpen.generation
                                + ", handlerType=" + handlerType
                                + ", onlyActivityRecord="
                                + onlyActivityRecord);
            }
        }

        PermissionActivityTransition adjacentPermissionClose = null;
        if (handlerType == 99 && onlyActivityRecord && snapshot != null) {
            try {
                adjacentPermissionClose =
                        resolveAdjacentPermissionActivityClose(
                                chain.getThisObject(), chain.getArg(0),
                                snapshot);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to inspect adjacent PermissionController CLOSE merge",
                        throwable);
            }
        }
        if (adjacentPermissionClose != null) {
            Object[] routedArgs = chain.getArgs().toArray();
            // A permission Activity may open and finish before WM dispatches either merge.
            // In that case Shell emits only the CLOSE immediately after the launcher OPEN.
            // Reuse Xiaomi's ActivityRecord branch so this merge finishes without cancelling
            // the still-running launcher animation.
            routedArgs[1] = Integer.valueOf(0);
            log(Log.INFO, TAG,
                    "Preserved Xiaomi launcher OPEN across adjacent PermissionController CLOSE"
                            + ", launcherGeneration=" + snapshot.generation
                            + ", taskId=" + snapshot.mainTask.taskId
                            + ", mainDebugId="
                            + snapshot.mainTransitionDebugId
                            + ", closeDebugId="
                            + adjacentPermissionClose.debugId
                            + ", animationType=" + snapshot.animationType);
            return chain.proceed(routedArgs);
        }

        PermissionActivityTransition permissionOpen = null;
        MiuiHomeLauncherOpenSnapshot permissionOpenSnapshot = snapshot;
        String permissionOpenSource = "none";
        if (handlerType == 0 && onlyActivityRecord && snapshot != null) {
            try {
                if (isMiuiHomeLauncherOpenSnapshotCurrent(
                        chain.getThisObject(), snapshot)) {
                    permissionOpen = resolvePermissionActivityTransition(
                            chain.getArg(0), TRANSIT_OPEN,
                            FLAG_TRANSLUCENT | FLAG_FILLS_TASK,
                            FLAG_ONLY_ACTIVITY_RECORD, snapshot);
                    if (permissionOpen != null) {
                        permissionOpenSource = "preProceed";
                    }
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to inspect PermissionController OPEN merge",
                        throwable);
            }
        }

        Object result = chain.proceed();
        if (Boolean.TRUE.equals(result)
                && handlerType == 0 && onlyActivityRecord
                && permissionOpen == null) {
            try {
                MiuiHomeLauncherOpenSnapshot latestSnapshot =
                        miuiHomeLauncherOpenSnapshot.get();
                boolean latestSnapshotCurrent = latestSnapshot != null
                        && isMiuiHomeLauncherOpenSnapshotCurrent(
                        chain.getThisObject(), latestSnapshot);
                PermissionActivityTransition latestPermissionOpen =
                        latestSnapshotCurrent
                                ? resolvePermissionActivityTransition(
                                chain.getArg(0), TRANSIT_OPEN,
                                FLAG_TRANSLUCENT | FLAG_FILLS_TASK,
                                FLAG_ONLY_ACTIVITY_RECORD,
                                latestSnapshot)
                                : null;
                log(Log.INFO, TAG,
                        "Rechecked PermissionController OPEN merge after native handler"
                                + ", transitionDebugId="
                                + readTransitionDebugId(chain.getArg(0))
                                + ", snapshotPresent="
                                + (latestSnapshot != null)
                                + ", snapshotCurrent="
                                + latestSnapshotCurrent
                                + ", resolved="
                                + (latestPermissionOpen != null));
                if (latestPermissionOpen != null) {
                    permissionOpenSnapshot = latestSnapshot;
                    permissionOpen = latestPermissionOpen;
                    permissionOpenSource = "postProceedRecheck";
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to recheck PermissionController OPEN merge after native handler",
                        throwable);
            }
        }
        if (permissionOpen != null && Boolean.TRUE.equals(result)) {
            try {
                if (miuiHomeLauncherOpenSnapshot.get()
                        == permissionOpenSnapshot
                        && isMiuiHomeLauncherOpenSnapshotCurrent(
                        chain.getThisObject(), permissionOpenSnapshot)) {
                    MiuiHomePermissionMergeToken token =
                            new MiuiHomePermissionMergeToken(
                                    permissionOpenSnapshot, permissionOpen);
                    MiuiHomePermissionMergeToken replaced =
                            miuiHomePermissionMergeToken.getAndSet(token);
                    if (replaced != null) {
                        replaced.consumed.compareAndSet(0, 1);
                    }
                    log(Log.INFO, TAG,
                            "Captured PermissionController ActivityRecord OPEN merge"
                                    + ", launcherGeneration="
                                    + permissionOpenSnapshot.generation
                                    + ", taskId="
                                    + permissionOpenSnapshot.mainTask.taskId
                                    + ", debugId=" + permissionOpen.debugId
                                    + ", animationType="
                                    + permissionOpenSnapshot.animationType
                                    + ", source="
                                    + permissionOpenSource);
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG,
                        "Failed to publish PermissionController OPEN token",
                        throwable);
            }
        }
        return result;
    }

    protected void captureMiuiHomeLauncherOpenSnapshot(
            Object animationListener, Object animationIdentity,
            long nativeGeneration, long callbackEpoch) {
        try {
            Object stateManager = readField(animationListener, "this$0");
            Object windowElement = invokeAnyMethod(
                    stateManager, "getCurrentWindowElement", new Object[0]);
            captureMiuiHomeLauncherOpenSnapshot(
                    stateManager, windowElement, null, animationIdentity,
                    nativeGeneration, callbackEpoch, -1,
                    "animationStartSettled");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed to publish Xiaomi launcher OPEN snapshot",
                    throwable);
        }
    }

    protected void captureMiuiHomeLauncherOpenSnapshot(
            Object stateManager, Object windowElement, Object expectedHelper,
            Object animationIdentity, long nativeGeneration,
            long callbackEpoch, int expectedTaskId, String source)
            throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()
                || stateManager == null || windowElement == null
                || miuiHomeOpenBreakCallbackEpoch.get() != callbackEpoch
                || miuiHomeOpenBreakGeneration != nativeGeneration
                || miuiHomeOpenBreakAnimationIdentity != animationIdentity
                || !miuiHomeOpenBreakAnimationActive) {
            return;
        }
        Object currentWindowElement = invokeAnyMethod(
                stateManager, "getCurrentWindowElement", new Object[0]);
        if (currentWindowElement != windowElement) {
            return;
        }
        Object currentIdentity = invokeAnyMethod(
                windowElement, "getAnimSymbol", new Object[0]);
        String currentType = readNativeAnimationType(windowElement);
        boolean running = Boolean.TRUE.equals(invokeAnyMethod(
                windowElement, "isAnimRunning", new Object[0]));
        if (currentIdentity != animationIdentity || !running
                || !isMiuiHomeLauncherOpenType(currentType)) {
            return;
        }
        Object compat = invokeAnyMethod(windowElement,
                "getWindowTransitionCompat", new Object[0]);
        Object helper = invokeAnyMethod(compat,
                "getCallbackHelper", new Object[0]);
        if (expectedHelper != null && helper != expectedHelper) {
            log(Log.WARN, TAG,
                    "Ignored mismatched Xiaomi launcher OPEN targets"
                            + ", source=" + source
                            + ", expectedHelper="
                            + shortObject(expectedHelper)
                            + ", currentHelper=" + shortObject(helper));
            return;
        }
        Object mainInfo = readField(helper,
                "mRecentsMergeTransitionInfo");
        Object mainToken = readField(helper, "mRecentsMergeAnimToken");
        int mainDebugId = readTransitionDebugId(mainInfo);
        int compatDebugId = readIntFieldOrDefault(
                compat, "mMainTransitionInfoDebugId", -1);
        LauncherOpenMainTask mainTask =
                resolveLauncherOpenMainTask(mainInfo);
        if (mainToken == null || mainDebugId < 0
                || compatDebugId != mainDebugId || mainTask == null) {
            log(Log.INFO, TAG,
                    "Deferred Xiaomi launcher OPEN snapshot"
                            + ", source=" + source
                            + ", nativeGeneration=" + nativeGeneration
                            + ", hasMainToken=" + (mainToken != null)
                            + ", mainDebugId=" + mainDebugId
                            + ", compatDebugId=" + compatDebugId
                            + ", hasMainTask=" + (mainTask != null));
            return;
        }
        if (expectedTaskId >= 0 && mainTask.taskId != expectedTaskId) {
            log(Log.WARN, TAG,
                    "Ignored Xiaomi launcher OPEN target/task mismatch"
                            + ", source=" + source
                            + ", boundTaskId=" + expectedTaskId
                            + ", transitionTaskId=" + mainTask.taskId
                            + ", transitionDebugId=" + mainDebugId);
            return;
        }

        MiuiHomeLauncherOpenSnapshot existing =
                miuiHomeLauncherOpenSnapshot.get();
        boolean sameOwner = existing != null
                && existing.nativeGeneration == nativeGeneration
                && existing.callbackEpoch == callbackEpoch
                && existing.stateManager == stateManager
                && existing.windowElement == windowElement
                && existing.animationIdentity == animationIdentity;
        boolean sameSnapshot = sameOwner
                && existing.windowTransitionCompat == compat
                && existing.helper == helper
                && existing.mainTransitionToken == mainToken
                && existing.mainTransitionInfo == mainInfo
                && existing.mainTransitionDebugId == mainDebugId
                && existing.mainTask.taskId == mainTask.taskId;
        if (sameSnapshot) {
            return;
        }
        MiuiHomePermissionMergeToken permissionToken =
                miuiHomePermissionMergeToken.get();
        if (sameOwner && permissionToken != null
                && permissionToken.launcherOpen == existing) {
            log(Log.WARN, TAG,
                    "Kept Xiaomi launcher OPEN snapshot with active permission merge"
                            + ", generation=" + existing.generation
                            + ", source=" + source
                            + ", nativeGeneration=" + nativeGeneration);
            return;
        }

        MiuiHomeLauncherOpenSnapshot snapshot =
                new MiuiHomeLauncherOpenSnapshot(
                        miuiHomeLauncherOpenSnapshotIds.incrementAndGet(),
                        nativeGeneration, callbackEpoch, stateManager,
                        windowElement, animationIdentity, currentType, compat,
                        helper, mainToken, mainInfo, mainDebugId, mainTask);
        MiuiHomeLauncherOpenSnapshot replaced =
                miuiHomeLauncherOpenSnapshot.getAndSet(snapshot);
        MiuiHomePermissionMergeToken stalePermissionToken =
                miuiHomePermissionMergeToken.getAndSet(null);
        if (stalePermissionToken != null) {
            stalePermissionToken.consumed.compareAndSet(0, 1);
        }
        log(Log.INFO, TAG, "Published Xiaomi launcher OPEN snapshot"
                + ", generation=" + snapshot.generation
                + ", source=" + source
                + ", nativeGeneration=" + nativeGeneration
                + ", replacedGeneration="
                + (replaced == null ? 0L : replaced.generation)
                + ", taskId=" + mainTask.taskId
                + ", displayId=" + mainTask.displayId
                + ", component=" + mainTask.component
                + ", transitionDebugId=" + mainDebugId
                + ", animationType=" + currentType
                + ", animationIdentity="
                + shortObject(animationIdentity));
    }

    protected void invalidateMiuiHomeLauncherOpenSnapshot(
            Object animationIdentity, String reason) {
        while (true) {
            MiuiHomeLauncherOpenSnapshot snapshot =
                    miuiHomeLauncherOpenSnapshot.get();
            if (snapshot == null) {
                if (animationIdentity == null) {
                    MiuiHomePermissionMergeToken strayToken =
                            miuiHomePermissionMergeToken.getAndSet(null);
                    if (strayToken != null) {
                        strayToken.consumed.compareAndSet(0, 1);
                    }
                }
                return;
            }
            if (animationIdentity != null
                    && snapshot.animationIdentity != animationIdentity) {
                return;
            }
            if (!miuiHomeLauncherOpenSnapshot.compareAndSet(snapshot, null)) {
                continue;
            }
            MiuiHomePermissionMergeToken permissionToken =
                    miuiHomePermissionMergeToken.get();
            if (permissionToken != null
                    && permissionToken.launcherOpen == snapshot
                    && miuiHomePermissionMergeToken.compareAndSet(
                    permissionToken, null)) {
                permissionToken.consumed.compareAndSet(0, 1);
            }
            log(Log.INFO, TAG, "Invalidated Xiaomi launcher OPEN snapshot"
                    + ", generation=" + snapshot.generation
                    + ", reason=" + reason);
            return;
        }
    }

    protected boolean isMiuiHomeLauncherOpenSnapshotCurrent(
            Object compat, MiuiHomeLauncherOpenSnapshot snapshot)
            throws Exception {
        return snapshot != null
                && miuiHomeLauncherOpenSnapshot.get() == snapshot
                && snapshot.nativeGeneration
                == miuiHomeOpenBreakGeneration
                && snapshot.callbackEpoch
                == miuiHomeOpenBreakCallbackEpoch.get()
                && miuiHomeOpenBreakAnimationIdentity
                == snapshot.animationIdentity
                && miuiHomeOpenBreakAnimationActive
                && snapshot.windowTransitionCompat == compat
                && readField(compat, "mHelper") == snapshot.helper
                && readField(snapshot.helper, "mRecentsMergeTransitionInfo")
                == snapshot.mainTransitionInfo
                && readField(snapshot.helper, "mRecentsMergeAnimToken")
                == snapshot.mainTransitionToken
                && readIntFieldOrDefault(
                compat, "mMainTransitionInfoDebugId", -1)
                == snapshot.mainTransitionDebugId;
    }

    protected LauncherOpenMainTask resolveLauncherOpenMainTask(Object info)
            throws Exception {
        if (info == null) {
            return null;
        }
        Object typeObject = invokeAnyMethod(info, "getType", new Object[0]);
        if (!(typeObject instanceof Number)
                || ((Number) typeObject).intValue() != TRANSIT_OPEN) {
            return null;
        }
        Object changesObject = invokeAnyMethod(
                info, "getChanges", new Object[0]);
        if (!(changesObject instanceof List<?>)) {
            return null;
        }
        LauncherOpenMainTask candidate = null;
        for (Object change : (List<?>) changesObject) {
            Object taskInfo = invokeAnyMethod(
                    change, "getTaskInfo", new Object[0]);
            if (taskInfo == null
                    || resolveTaskInfoActivityType(taskInfo)
                    != ACTIVITY_TYPE_STANDARD) {
                continue;
            }
            Object modeObject = invokeAnyMethod(
                    change, "getMode", new Object[0]);
            int mode = modeObject instanceof Number
                    ? ((Number) modeObject).intValue() : -1;
            if ((mode != TRANSIT_OPEN && mode != TRANSIT_TO_FRONT)
                    || resolveTaskInfoWindowingMode(taskInfo)
                    != WINDOWING_MODE_FULLSCREEN || candidate != null) {
                return null;
            }
            int taskId = readIntFieldOrDefault(taskInfo, "taskId", -1);
            int displayId = readIntFieldOrDefault(
                    taskInfo, "displayId", -1);
            Object boundsObject = invokeAnyMethod(
                    change, "getEndAbsBounds", new Object[0]);
            ComponentName component = readTaskInfoComponent(taskInfo);
            if (taskId < 0 || displayId < 0
                    || !(boundsObject instanceof Rect)
                    || ((Rect) boundsObject).isEmpty()
                    || component == null
                    || MIUI_HOME.equals(component.getPackageName())
                    || PERMISSION_GRANT_ACTIVITY.equals(component)) {
                return null;
            }
            candidate = new LauncherOpenMainTask(
                    taskId, displayId, component,
                    new Rect((Rect) boundsObject));
        }
        return candidate;
    }

    protected int resolveTaskInfoActivityType(Object taskInfo) {
        try {
            Object directValue = invokeAnyMethod(
                    taskInfo, "getActivityType", new Object[0]);
            if (directValue instanceof Number) {
                return ((Number) directValue).intValue();
            }
            Object configuration = readField(taskInfo, "configuration");
            Object windowConfiguration = readField(
                    configuration, "windowConfiguration");
            Object value = invokeAnyMethod(windowConfiguration,
                    "getActivityType", new Object[0]);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return readIntFieldOrDefault(taskInfo, "topActivityType", -1);
    }

    protected int resolveTaskInfoWindowingMode(Object taskInfo) {
        try {
            Object directValue = invokeAnyMethod(
                    taskInfo, "getWindowingMode", new Object[0]);
            if (directValue instanceof Number) {
                return ((Number) directValue).intValue();
            }
            Object configuration = readField(taskInfo, "configuration");
            Object windowConfiguration = readField(
                    configuration, "windowConfiguration");
            Object value = invokeAnyMethod(windowConfiguration,
                    "getWindowingMode", new Object[0]);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    protected ComponentName readTaskInfoComponent(Object taskInfo) {
        for (String fieldName : new String[]{
                "baseActivity", "realActivity", "topActivity"}) {
            try {
                Object component = readField(taskInfo, fieldName);
                if (component instanceof ComponentName) {
                    return (ComponentName) component;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    protected boolean isMiuiHomeLauncherOpenType(String typeName) {
        return "OPEN_FROM_ELEMENT".equals(typeName)
                || "OPEN_FROM_HOME".equals(typeName)
                || "OPEN_FROM_RECENTS".equals(typeName);
    }

    protected PermissionActivityTransition resolvePermissionActivityTransition(
            Object infoExpose, int expectedMode, int expectedChangeFlags,
            int expectedInfoFlags, MiuiHomeLauncherOpenSnapshot snapshot)
            throws Exception {
        if (infoExpose == null || snapshot == null) {
            return null;
        }
        Object infoFlagsObject = invokeAnyMethod(
                infoExpose, "getFlags", new Object[0]);
        int infoFlags = infoFlagsObject instanceof Number
                ? ((Number) infoFlagsObject).intValue() : Integer.MIN_VALUE;
        Object transitionInfo = invokeAnyMethod(
                infoExpose, "unbox", new Object[0]);
        Object typeObject = invokeAnyMethod(
                transitionInfo, "getType", new Object[0]);
        if (infoFlags != expectedInfoFlags || !(typeObject instanceof Number)
                || ((Number) typeObject).intValue() != expectedMode) {
            return null;
        }
        int debugId = readTransitionDebugId(transitionInfo);
        if (expectedMode == TRANSIT_OPEN
                && (snapshot.mainTransitionDebugId < 0
                || debugId != snapshot.mainTransitionDebugId + 1)) {
            return null;
        }
        Object changesObject = invokeAnyMethod(
                infoExpose, "getChanges", new Object[0]);
        if (!(changesObject instanceof List<?>)
                || ((List<?>) changesObject).size() != 1) {
            return null;
        }
        Object changeExpose = ((List<?>) changesObject).get(0);
        Object modeObject = invokeAnyMethod(
                changeExpose, "getMode", new Object[0]);
        Object flagsObject = invokeAnyMethod(
                changeExpose, "getFlags", new Object[0]);
        Object taskInfo = invokeAnyMethod(
                changeExpose, "getTaskInfo", new Object[0]);
        Object componentObject = invokeAnyMethod(
                changeExpose, "getActivityComponent", new Object[0]);
        Object leashObject = invokeAnyMethod(
                changeExpose, "getLeash", new Object[0]);
        Object startBoundsObject = invokeAnyMethod(
                changeExpose, "getStartAbsBounds", new Object[0]);
        Object endBoundsObject = invokeAnyMethod(
                changeExpose, "getEndAbsBounds", new Object[0]);
        if (!(modeObject instanceof Number)
                || ((Number) modeObject).intValue() != expectedMode
                || !(flagsObject instanceof Number)
                || ((Number) flagsObject).intValue()
                != expectedChangeFlags || taskInfo != null
                || !PERMISSION_GRANT_ACTIVITY.equals(componentObject)
                || !(leashObject instanceof SurfaceControl)
                || !((SurfaceControl) leashObject).isValid()
                || !(startBoundsObject instanceof Rect)
                || !(endBoundsObject instanceof Rect)) {
            return null;
        }
        Rect startBounds = new Rect((Rect) startBoundsObject);
        Rect endBounds = new Rect((Rect) endBoundsObject);
        Object startDisplayObject = invokeAnyMethod(
                changeExpose, "getStartDisplayId", new Object[0]);
        Object endDisplayObject = invokeAnyMethod(
                changeExpose, "getEndDisplayId", new Object[0]);
        int startDisplay = startDisplayObject instanceof Number
                ? ((Number) startDisplayObject).intValue() : -2;
        int endDisplay = endDisplayObject instanceof Number
                ? ((Number) endDisplayObject).intValue() : -2;
        if (expectedMode == TRANSIT_OPEN) {
            if (!startBounds.isEmpty()
                    || !endBounds.equals(snapshot.mainTask.bounds)
                    || startDisplay >= 0
                    || endDisplay != snapshot.mainTask.displayId) {
                return null;
            }
        } else if (!startBounds.equals(snapshot.mainTask.bounds)
                || !endBounds.equals(snapshot.mainTask.bounds)
                || startDisplay != snapshot.mainTask.displayId
                || endDisplay != snapshot.mainTask.displayId) {
            return null;
        }
        Object change = invokeAnyMethod(
                changeExpose, "unbox", new Object[0]);
        Object container = invokeAnyMethod(
                change, "getContainer", new Object[0]);
        Object parent = invokeAnyMethod(
                changeExpose, "getParent", new Object[0]);
        Object backgroundObject = invokeAnyMethod(
                changeExpose, "getBackgroundColor", new Object[0]);
        return new PermissionActivityTransition(
                container, parent, (SurfaceControl) leashObject,
                (ComponentName) componentObject, startBounds, endBounds,
                debugId,
                backgroundObject instanceof Number
                        ? ((Number) backgroundObject).intValue() : 0,
                startDisplay, endDisplay);
    }

    protected PermissionActivityTransition resolveMatchingPermissionActivityClose(
            Object compat, Object infoExpose,
            MiuiHomePermissionMergeToken token) throws Exception {
        if (token == null || token.consumed.get() != 0
                || !isMiuiHomeLauncherOpenSnapshotCurrent(
                compat, token.launcherOpen)) {
            return null;
        }
        PermissionActivityTransition close =
                resolvePermissionActivityTransition(
                        infoExpose, TRANSIT_CLOSE,
                        FLAG_TRANSLUCENT | FLAG_FILLS_TASK
                                | FLAG_IS_OCCLUDED,
                        0, token.launcherOpen);
        PermissionActivityTransition open = token.permissionOpen;
        if (close == null || open.debugId < 0
                || close.debugId != open.debugId + 1
                || !open.component.equals(close.component)
                || open.backgroundColor != close.backgroundColor
                || !open.endBounds.equals(close.startBounds)
                || !open.endBounds.equals(close.endBounds)
                || open.endDisplayId != close.startDisplayId
                || open.endDisplayId != close.endDisplayId) {
            return null;
        }
        if ((open.container == null) != (close.container == null)
                || (open.container != null
                && !open.container.equals(close.container))) {
            return null;
        }
        if ((open.parent == null) != (close.parent == null)
                || (open.parent != null && !open.parent.equals(close.parent))) {
            return null;
        }
        return open.leash.isValid() && close.leash.isValid()
                && surfacesAreSame(open.leash, close.leash) ? close : null;
    }

    protected PermissionActivityTransition resolveAdjacentPermissionActivityClose(
            Object compat, Object infoExpose,
            MiuiHomeLauncherOpenSnapshot snapshot) throws Exception {
        if (!isMiuiHomeLauncherOpenSnapshotCurrent(compat, snapshot)
                || snapshot.mainTransitionDebugId < 0) {
            return null;
        }
        PermissionActivityTransition close =
                resolvePermissionActivityTransition(
                        infoExpose, TRANSIT_CLOSE,
                        FLAG_TRANSLUCENT | FLAG_FILLS_TASK
                                | FLAG_IS_OCCLUDED,
                        0, snapshot);
        return close != null
                && close.debugId == snapshot.mainTransitionDebugId + 1
                && close.container == null
                && close.parent == null ? close : null;
    }

    protected int readTransitionDebugId(Object infoOrExpose) {
        if (infoOrExpose == null) {
            return -1;
        }
        try {
            Object info = infoOrExpose;
            if (!"android.window.TransitionInfo".equals(
                    infoOrExpose.getClass().getName())) {
                info = invokeAnyMethod(
                        infoOrExpose, "unbox", new Object[0]);
            }
            Object value = invokeAnyMethod(
                    info, "getDebugId", new Object[0]);
            return value instanceof Number
                    ? ((Number) value).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    protected Object captureMiuiHomeReturnHomeCloseInterruption(
            XposedInterface.Chain chain) throws Throwable {
        Object windowElement = chain.getThisObject();
        Object reasonObject = chain.getArg(0);
        String reason = reasonObject instanceof String
                ? (String) reasonObject : null;
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller != null) {
            try {
                controller.prepareNativeCloseInterruption(
                        windowElement, reason);
            } catch (Throwable throwable) {
                // This runs before Xiaomi mutates the WindowElement. A failed bookkeeping
                // adoption must leave the native icon-click path untouched.
                log(Log.WARN, TAG,
                        "Failed to adopt return-home CLOSE before native interruption",
                        throwable);
            }
        }
        Object result = chain.proceed();
        if (controller != null) {
            try {
                controller.onNativeCloseCancelSurface(
                        windowElement, reason);
            } catch (Throwable throwable) {
                // Xiaomi has already queued its native cancellation. Module bookkeeping must
                // never turn an icon click into a launcher exception.
                log(Log.WARN, TAG,
                        "Failed to capture interrupted Xiaomi return-home CLOSE", throwable);
            }
        }
        return result;
    }

    protected Object finishMiuiHomeReturnHomeCloseInterruption(
            XposedInterface.Chain chain) throws Throwable {
        Object stateManager = chain.getThisObject();
        Object windowElement = chain.getArg(0);
        Object result = chain.proceed();
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller != null) {
            try {
                controller.onNativeCloseSetToOld(stateManager, windowElement);
            } catch (Throwable throwable) {
                // Preserve Xiaomi's completed callback chain. The old native animation-end
                // listener and watchdog remain valid fallbacks when verification fails.
                log(Log.WARN, TAG,
                        "Failed to finish interrupted Xiaomi return-home CLOSE", throwable);
            }
        }
        return result;
    }

    protected Object routeMiuiHomeReturnHomeSameIconParallel(
            XposedInterface.Chain chain) throws Throwable {
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller == null) {
            return chain.proceed();
        }
        Object[] originalArgs = chain.getArgs().toArray();
        boolean routeThroughNativeParallel;
        try {
            routeThroughNativeParallel = controller.shouldRouteSameIconThroughNativeParallel(
                    chain.getThisObject(), originalArgs);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed to inspect Xiaomi same-icon parallel routing",
                    throwable);
            return chain.proceed();
        }
        if (!routeThroughNativeParallel) {
            return chain.proceed();
        }
        Object[] routedArgs = originalArgs.clone();
        // StateManager's second argument is the same-element/direct-cancel selector. Xiaomi's
        // false branch already keeps the old WindowElement in its native cancellation lifecycle,
        // moves it to the old list, and starts FastLaunch OPEN from the native callback.
        routedArgs[1] = Boolean.FALSE;
        return chain.proceed(routedArgs);
    }

    protected Object forceMiuiHomeReturnHomeFreshOpen(
            XposedInterface.Chain chain) throws Throwable {
        Object originalResult = chain.proceed();
        if (!Boolean.TRUE.equals(originalResult)) {
            return originalResult;
        }
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller == null) {
            return originalResult;
        }
        try {
            if (controller.shouldForceFreshOpenAfterSameIconClose(
                    chain.getThisObject(), chain.getArg(0), chain.getArg(2))) {
                return Boolean.FALSE;
            }
        } catch (Throwable throwable) {
            // Xiaomi already selected its stock old-element behavior. Any inspection failure
            // must preserve that result instead of turning an unrelated launcher click into a
            // fresh remote transition.
            log(Log.WARN, TAG,
                    "Failed to inspect Xiaomi same-icon fresh OPEN selection",
                    throwable);
        }
        return originalResult;
    }

    protected Object wrapMiuiHomeReturnHomeDirectCancel(
            XposedInterface.Chain chain) throws Throwable {
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller == null) {
            return chain.proceed();
        }
        Object[] originalArgs = chain.getArgs().toArray();
        MiuiHomeReturnHomeController.ReturnHomeDirectCancelToken token;
        try {
            token = controller.prepareNativeDirectCancel(
                    chain.getThisObject(), originalArgs);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG,
                    "Failed to prepare direct Xiaomi return-home cancellation",
                    throwable);
            return chain.proceed();
        }
        if (token == null) {
            return chain.proceed();
        }
        Object[] wrappedArgs = originalArgs.clone();
        wrappedArgs[2] = token.wrappedCallback;
        try {
            return chain.proceed(wrappedArgs);
        } catch (Throwable throwable) {
            controller.invalidateNativeDirectCancel(
                    token, "cancelAnimThrew", false);
            throw throwable;
        }
    }

    protected Object restoreMiuiHomeReusedCloseOpen(XposedInterface.Chain chain)
            throws Throwable {
        Object stateManager = chain.getThisObject();
        Object closingElement;
        boolean reusableClose;
        try {
            closingElement = readField(stateManager, "windowElement");
            reusableClose = closingElement != null
                    && Boolean.TRUE.equals(
                    invokeAnyMethod(closingElement,
                            "isClosingAnimRunning", new Object[0]))
                    && Boolean.TRUE.equals(
                    invokeAnyMethod(closingElement,
                            "isReusefulAnimRunning", new Object[0]));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to inspect reusable launcher CLOSE; proceeding native",
                    throwable);
            return chain.proceed();
        }

        Object result = chain.proceed();
        if (!reusableClose) {
            return result;
        }

        try {
            Object openingElement = readField(stateManager, "windowElement");
            boolean sameElement = openingElement == closingElement;
            boolean openRunning = sameElement && Boolean.TRUE.equals(invokeAnyMethod(
                    openingElement, "isOpenAnimRunning", new Object[0]));
            Object currentAnimType = sameElement
                    ? invokeAnyMethod(openingElement,
                    "getCurrentAnimType", new Object[0]) : null;
            boolean openFromHome = currentAnimType instanceof Enum<?>
                    && "OPEN_FROM_HOME".equals(
                    ((Enum<?>) currentAnimType).name());
            if (!openRunning || !openFromHome) {
                log(Log.WARN, TAG, "Did not restore reused CLOSE-to-OPEN break state"
                        + ", sameElement=" + sameElement
                        + ", openRunning=" + openRunning
                        + ", openFromHome=" + openFromHome
                        + ", currentAnimType=" + currentAnimType
                        + ", closingElement=" + shortObject(closingElement)
                        + ", openingElement=" + shortObject(openingElement));
                return result;
            }

            Object controller = miuiHomeOpenBreakController;
            if (controller == null) {
                log(Log.WARN, TAG, "Cannot restore reused CLOSE-to-OPEN break state"
                        + ", controller=null"
                        + ", currentAnimType=" + currentAnimType
                        + ", windowElement=" + shortObject(openingElement));
                return result;
            }
            Object animationIdentity = invokeAnyMethod(
                    openingElement, "getAnimSymbol", new Object[0]);
            if (animationIdentity == null) {
                log(Log.WARN, TAG, "Cannot identify reused CLOSE-to-OPEN animation"
                        + ", animationIdentity=null"
                        + ", currentAnimType=" + currentAnimType
                        + ", windowElement=" + shortObject(openingElement));
                return result;
            }

            // Xiaomi retargets a reusable CLOSE_TO_HOME animator to OPEN_FROM_HOME in place.
            // That path has no new WindowElement animation-start callback and does not call
            // enableBackBreakOpenAnim(), so the controller and our generation both remain tied
            // to the old CLOSE. Restore Xiaomi's native enable state, then adopt the already
            // running animator under the new generation instead of creating a surface animation.
            long previousGeneration = miuiHomeOpenBreakGeneration;
            invokeAnyMethod(controller, "enableBackBreakOpenAnim", new Object[0]);
            long generation = miuiHomeOpenBreakGeneration;
            if (generation == 0L || generation == previousGeneration) {
                log(Log.WARN, TAG, "Cannot assign reused CLOSE-to-OPEN generation"
                        + ", previousGeneration=" + previousGeneration
                        + ", currentGeneration=" + generation
                        + ", currentAnimType=" + currentAnimType
                        + ", animationIdentity=" + shortObject(animationIdentity)
                        + ", windowElement=" + shortObject(openingElement));
                return result;
            }

            miuiHomeOpenBreakAnimationIdentity = animationIdentity;
            miuiHomeOpenBreakGenerationPrepared = false;
            miuiHomeOpenBreakAnimationActive = true;
            miuiHomeOpenBreakCommandPending = false;
            log(Log.INFO, TAG, "Restored native launcher OPEN break for reused CLOSE animation"
                    + ", generation=" + generation
                    + ", currentAnimType=" + currentAnimType
                    + ", animationIdentity=" + shortObject(animationIdentity)
                    + ", windowElement=" + shortObject(openingElement));

            long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (miuiHomeOpenBreakCallbackEpoch.get() != callbackEpoch
                        || miuiHomeOpenBreakGeneration != generation
                        || miuiHomeOpenBreakAnimationIdentity != animationIdentity
                        || !miuiHomeOpenBreakAnimationActive) {
                    return;
                }
                Object currentElement = null;
                try {
                    currentElement = readField(stateManager, "windowElement");
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "Failed to verify reused CLOSE-to-OPEN element",
                            throwable);
                }
                if (currentElement != openingElement) {
                    miuiHomeOpenBreakAnimationActive = false;
                    refreshMiuiHomeOpenBreakAvailability(
                            controller, "reusedCloseOpenReplaced");
                    return;
                }
                refreshMiuiHomeOpenBreakAvailability(controller, "reusedCloseOpenSettled");
            });
        } catch (Throwable throwable) {
            // The native retarget already completed. A module-side bookkeeping failure must
            // never turn a successful launcher click into an exception for MiuiHome.
            log(Log.ERROR, TAG, "Failed to restore reused CLOSE-to-OPEN break state",
                    throwable);
        }
        return result;
    }

    protected long nextMiuiHomeOpenBreakGeneration() {
        return miuiHomeOpenBreakGenerationIds.incrementAndGet();
    }

    protected void restoreMiuiHomeOpenBreakAfterHotReload() {
        Object controller = miuiHomeOpenBreakController;
        if (controller == null) {
            return;
        }
        long callbackEpoch = miuiHomeOpenBreakCallbackEpoch.get();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (miuiHomeOpenBreakCallbackEpoch.get() == callbackEpoch) {
                refreshMiuiHomeOpenBreakAvailability(controller, "hotReload");
            }
        });
    }

    protected void refreshMiuiHomeOpenBreakAvailability(Object controller, String reason) {
        if (controller == null) {
            log(Log.INFO, TAG, "MiuiHome launcher OPEN break controller unavailable"
                    + ", reason=" + reason);
            return;
        }
        miuiHomeOpenBreakController = controller;
        Context context = resolveMiuiHomeOpenBreakContext(controller);
        if (context == null) {
            log(Log.WARN, TAG, "Cannot publish MiuiHome launcher OPEN break state"
                    + ", reason=" + reason
                    + ", controller=" + shortObject(controller));
            return;
        }
        boolean receiverReady = ensureMiuiHomeOpenBreakCommandReceiver(context);
        long generation = miuiHomeOpenBreakGeneration;
        boolean available = receiverReady
                && generation != 0L
                && miuiHomeOpenBreakAnimationActive
                && !miuiHomeOpenBreakCommandPending
                && canUseMiuiHomeOpenBreak(controller, reason);
        String nativeState = describeMiuiHomeOpenBreakNativeState(controller);
        try {
            Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            stateIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_AVAILABLE, available);
            stateIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, generation);
            sendAuthenticatedMiuiHomeState(context, stateIntent);
            log(Log.INFO, TAG, "Published MiuiHome launcher OPEN break state"
                    + ", available=" + available
                    + ", receiverReady=" + receiverReady
                    + ", generation=" + generation
                    + ", commandPending=" + miuiHomeOpenBreakCommandPending
                    + ", nativeState=" + nativeState
                    + ", reason=" + reason
                    + ", controller=" + shortObject(controller));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to publish MiuiHome launcher OPEN break state"
                    + ", reason=" + reason, throwable);
        }
    }

    protected Context resolveMiuiHomeOpenBreakContext(Object controller) {
        Context context = miuiHomeOpenBreakContext;
        if (context != null) {
            return context;
        }
        try {
            Object navStubView = invokeAnyMethod(controller, "getNavStubView", new Object[0]);
            if (navStubView instanceof View) {
                context = ((View) navStubView).getContext().getApplicationContext();
                miuiHomeOpenBreakContext = context;
                return context;
            }
            log(Log.INFO, TAG, "MiuiHome NavStubView unavailable while resolving context"
                    + ", view=" + shortObject(navStubView));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to resolve MiuiHome OPEN break context", throwable);
        }
        return null;
    }

    protected boolean canUseMiuiHomeOpenBreak(Object controller, String reason) {
        try {
            return Boolean.TRUE.equals(invokeAnyMethod(controller,
                    "canUseBackGestureBreakOpenAnim", new Object[0]));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to query native MiuiHome OPEN break availability"
                    + ", reason=" + reason, throwable);
            return false;
        }
    }

    protected String describeMiuiHomeOpenBreakNativeState(Object controller) {
        if (controller == null) {
            return "controller=null";
        }
        Object navStubView = null;
        Object openRunning = null;
        Object belowThreshold = null;
        Object appLaunching = null;
        try {
            navStubView = invokeAnyMethod(controller, "getNavStubView", new Object[0]);
        } catch (Throwable ignored) {
        }
        try {
            openRunning = invokeAnyMethod(controller, "isOpenAnimRunning", new Object[0]);
        } catch (Throwable ignored) {
        }
        try {
            belowThreshold = invokeAnyMethod(controller,
                    "isOpenAnimBelowBackBreakThreshold", new Object[0]);
        } catch (Throwable ignored) {
        }
        try {
            appLaunching = readField(controller, "mAppLaunchingButNotResume");
        } catch (Throwable ignored) {
        }
        return "navStub=" + (navStubView != null)
                + ", openRunning=" + openRunning
                + ", belowThreshold=" + belowThreshold
                + ", appLaunching=" + appLaunching;
    }

    protected synchronized boolean ensureMiuiHomeOpenBreakCommandReceiver(Context context) {
        if (miuiHomeOpenBreakCommandReceiver != null) {
            return true;
        }
        if (context == null) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !MODULE_MIUI_HOME_OPEN_BREAK_COMMAND.equals(
                        intent.getAction())) {
                    return;
                }
                if (!isOrderedBroadcast()) {
                    log(Log.WARN, TAG, "Rejected unordered launcher OPEN break command");
                    return;
                }
                setResultCode(LAUNCHER_OPEN_BREAK_RESULT_REJECTED);
                setResultData("rejected");
                int senderUid = getSentFromUid();
                String senderPackage = getSentFromPackage();
                if (!isTrustedSystemUiBroadcastSender(
                        receiverContext, senderUid, senderPackage)) {
                    log(Log.WARN, TAG, "Rejected untrusted launcher OPEN break command"
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                    setResultData("untrustedSender");
                    return;
                }
                long commandGeneration = intent.getLongExtra(
                        EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, 0L);
                long attemptId = intent.getLongExtra(
                        EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT, 0L);
                Object controller = miuiHomeOpenBreakController;
                if (commandGeneration == 0L
                        || attemptId == 0L
                        || commandGeneration != miuiHomeOpenBreakGeneration
                        || !miuiHomeOpenBreakAnimationActive
                        || miuiHomeOpenBreakCommandPending
                        || controller == null
                        || !canUseMiuiHomeOpenBreak(controller, "command")) {
                    log(Log.WARN, TAG, "Rejected stale launcher OPEN break command"
                            + ", commandGeneration=" + commandGeneration
                            + ", currentGeneration=" + miuiHomeOpenBreakGeneration
                            + ", attempt=" + attemptId
                            + ", animationActive=" + miuiHomeOpenBreakAnimationActive
                            + ", commandPending=" + miuiHomeOpenBreakCommandPending
                            + ", controller=" + shortObject(controller));
                    setResultData(commandGeneration != miuiHomeOpenBreakGeneration
                            ? "generationMismatch" : "nativeUnavailable");
                    refreshMiuiHomeOpenBreakAvailability(controller, "commandRejected");
                    return;
                }
                miuiHomeOpenBreakCommandPending = true;
                refreshMiuiHomeOpenBreakAvailability(controller, "commandAccepted");
                try {
                    // Dynamic receivers run on MiuiHome's main thread. Xiaomi's
                    // MainThreadExecutor executes inline when already on that Looper, so an
                    // accepted ordered result means executeBackGestureBreak() has returned.
                    invokeAnyMethod(controller, "breakOpenAnim", new Object[0]);
                    setResultCode(LAUNCHER_OPEN_BREAK_RESULT_ACCEPTED);
                    setResultData("accepted");
                    log(Log.INFO, TAG, "Requested native MiuiHome launcher OPEN break"
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage
                            + ", generation=" + commandGeneration
                            + ", attempt=" + attemptId
                            + ", controller=" + shortObject(controller));
                } catch (Throwable throwable) {
                    setResultData("nativeException");
                    log(Log.ERROR, TAG, "Failed to request native MiuiHome launcher OPEN break",
                            throwable);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(MODULE_MIUI_HOME_OPEN_BREAK_COMMAND);
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            miuiHomeOpenBreakCommandReceiverContext = appContext;
            miuiHomeOpenBreakCommandReceiver = receiver;
            log(Log.INFO, TAG, "Registered MiuiHome launcher OPEN break command receiver");
            return true;
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to register MiuiHome launcher OPEN break receiver",
                    throwable);
            return false;
        }
    }

    protected boolean isTrustedSystemUiBroadcastSender(Context context, int uid,
                                                       String senderPackage) {
        if (context == null || uid == Process.INVALID_UID
                || !SYSTEM_UI.equals(senderPackage)) {
            return false;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains(SYSTEM_UI);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to validate SystemUI command sender uid=" + uid,
                    throwable);
        }
        return false;
    }

    protected synchronized void unregisterMiuiHomeOpenBreakCommandReceiver() {
        BroadcastReceiver receiver = miuiHomeOpenBreakCommandReceiver;
        Context receiverContext = miuiHomeOpenBreakCommandReceiverContext;
        miuiHomeOpenBreakCommandReceiver = null;
        miuiHomeOpenBreakCommandReceiverContext = null;
        if (receiver == null || receiverContext == null) {
            return;
        }
        try {
            receiverContext.unregisterReceiver(receiver);
            log(Log.INFO, TAG, "Unregistered MiuiHome launcher OPEN break receiver");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to unregister MiuiHome launcher OPEN break receiver",
                    throwable);
        }
    }

    protected Object mirrorMiuiHomeRecentsActualState(XposedInterface.Chain chain)
            throws Throwable {
        Object container = chain.getThisObject();
        Object result = chain.proceed();
        Object contextObject = chain.getArg(0);
        boolean overviewVisible = Boolean.TRUE.equals(chain.getArg(1));
        if (!(contextObject instanceof Context)) {
            log(Log.WARN, TAG, "Cannot mirror MiuiHome Recents state: context="
                    + shortObject(contextObject));
            return result;
        }
        try {
            Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            stateIntent.putExtra("overview_visible", overviewVisible);
            sendAuthenticatedMiuiHomeState((Context) contextObject, stateIntent);
            log(Log.INFO, TAG, "Mirrored MiuiHome actual Recents state"
                    + ", overviewVisible=" + overviewVisible
                    + ", container=" + shortObject(container));
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to mirror MiuiHome actual Recents state",
                    throwable);
        }
        return result;
    }

    protected Object mirrorMiuiHomeRecentsTaskLaunch(XposedInterface.Chain chain)
            throws Throwable {
        Object taskView = chain.getThisObject();
        if (taskView instanceof View) {
            try {
                Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
                stateIntent.putExtra("overview_visible", false);
                stateIntent.putExtra("task_launch_started", true);
                sendAuthenticatedMiuiHomeState(((View) taskView).getContext(), stateIntent);
                log(Log.INFO, TAG, "Mirrored MiuiHome Recents task launch start"
                        + ", overviewVisible=false"
                        + ", taskView=" + shortObject(taskView));
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to mirror MiuiHome Recents task launch start",
                        throwable);
            }
        } else {
            log(Log.WARN, TAG, "Cannot mirror MiuiHome task launch: taskView="
                    + shortObject(taskView));
        }
        // Notify SystemUI before Xiaomi's original onClick() starts the task transition.
        return chain.proceed();
    }

    protected Object mirrorMiuiHomeFullscreenState(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        Object contextObject = chain.getArg(0);
        Object stateObject = chain.getArg(1);
        if (!(contextObject instanceof Context) || !(stateObject instanceof String)) {
            log(Log.WARN, TAG, "Cannot mirror MiuiHome fullscreen state"
                    + ", context=" + shortObject(contextObject)
                    + ", state=" + shortObject(stateObject));
            return result;
        }
        try {
            Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            stateIntent.putExtra("state", (String) stateObject);
            stateIntent.putExtra("fullscreen_state", true);
            sendAuthenticatedMiuiHomeState((Context) contextObject, stateIntent);
            log(Log.INFO, TAG, "Mirrored MiuiHome fullscreen state"
                    + ", state=" + stateObject);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to mirror MiuiHome fullscreen state", throwable);
        }
        return result;
    }

    protected Object mirrorMiuiHomeDrawerState(XposedInterface.Chain chain)
            throws Throwable {
        Object manager = chain.getThisObject();
        Object targetState = chain.getArg(0);
        try {
            ClassLoader classLoader = targetState == null
                    ? manager.getClass().getClassLoader()
                    : targetState.getClass().getClassLoader();
            Class<?> stateClass = Class.forName(MIUI_HOME_LAUNCHER_STATE, false, classLoader);
            Object allAppsState = readStaticField(stateClass, "ALL_APPS");
            boolean drawerVisible = targetState == allAppsState;
            Object launcher = readField(manager, "mLauncher");
            if (launcher instanceof Context) {
                miuiDrawerVisible = drawerVisible;
                Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
                stateIntent.putExtra("drawer_visible", drawerVisible);
                sendAuthenticatedMiuiHomeState((Context) launcher, stateIntent);
                log(Log.INFO, TAG, "Mirrored MiuiHome drawer state"
                        + ", drawerVisible=" + drawerVisible
                        + ", target=" + shortObject(targetState));
            } else {
                log(Log.WARN, TAG, "Cannot mirror MiuiHome drawer state: launcher="
                        + shortObject(launcher));
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to mirror MiuiHome drawer state", throwable);
        }
        return chain.proceed();
    }

    protected Object mirrorMiuiHomeEditingState(XposedInterface.Chain chain)
            throws Throwable {
        Object launcher = chain.getThisObject();
        Object result = chain.proceed();
        publishMiuiHomeEditingState(launcher, "notifyBackGestureStatus");
        return result;
    }

    protected void publishMiuiHomeEditingState(Object launcher, String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(
                    () -> publishMiuiHomeEditingState(launcher, reason + ":main"));
            return;
        }
        if (!(launcher instanceof Context)) {
            log(Log.WARN, TAG, "Cannot mirror MiuiHome editing state: launcher="
                    + shortObject(launcher));
            return;
        }
        try {
            ClassLoader classLoader = launcher.getClass().getClassLoader();
            Class<?> applicationClass = Class.forName(
                    MIUI_HOME_APPLICATION, false, classLoader);
            Method getLauncher = applicationClass.getDeclaredMethod("getLauncher");
            getLauncher.setAccessible(true);
            Object activeLauncher = getLauncher.invoke(null);
            if (activeLauncher != launcher) {
                log(Log.INFO, TAG, "Ignored stale MiuiHome editing-state callback"
                        + ", reason=" + reason
                        + ", callbackLauncher=" + shortObject(launcher)
                        + ", activeLauncher=" + shortObject(activeLauncher));
                return;
            }
            Class<?> baseLauncherClass = Class.forName(
                    MIUI_HOME_BASE_LAUNCHER, false, classLoader);
            Method isInEditing = baseLauncherClass.getDeclaredMethod("isInEditing");
            isInEditing.setAccessible(true);
            boolean editing = Boolean.TRUE.equals(isInEditing.invoke(launcher));
            if (miuiHomeEditingStatePublished && miuiLauncherEditing == editing) {
                return;
            }
            Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
            stateIntent.putExtra(EXTRA_LAUNCHER_EDITING, editing);
            sendAuthenticatedMiuiHomeState((Context) launcher, stateIntent);
            miuiLauncherEditing = editing;
            miuiHomeEditingStatePublished = true;
            log(Log.INFO, TAG, "Mirrored MiuiHome editing state"
                    + ", editing=" + editing
                    + ", reason=" + reason
                    + ", launcher=" + shortObject(launcher));
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to mirror MiuiHome editing state", throwable);
        }
    }

    protected void refreshMiuiHomeEditingState(ClassLoader classLoader, String reason) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Class<?> applicationClass = Class.forName(
                        MIUI_HOME_APPLICATION, false, classLoader);
                Method getLauncher = applicationClass.getDeclaredMethod("getLauncher");
                getLauncher.setAccessible(true);
                Object launcher = getLauncher.invoke(null);
                if (launcher != null) {
                    publishMiuiHomeEditingState(launcher, reason);
                } else {
                    log(Log.INFO, TAG,
                            "Deferred MiuiHome editing-state backfill until Launcher is ready");
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to backfill MiuiHome editing state", throwable);
            }
        });
    }

    protected Object registerMiuiHomeReturnHome(XposedInterface.Chain chain)
            throws Throwable {
        Object overviewProxy = chain.getThisObject();
        Object result = chain.proceed();
        Object bundleObject = chain.getArg(0);
        if (!(bundleObject instanceof Bundle)) {
            log(Log.WARN, TAG, "Cannot register launcher back animation: bundle="
                    + shortObject(bundleObject));
            return result;
        }
        IBinder shellBackAnimation =
                ((Bundle) bundleObject).getBinder(SHELL_BACK_ANIMATION_DESCRIPTOR);
        if (shellBackAnimation == null) {
            log(Log.WARN, TAG, "Launcher initialization omitted Shell IBackAnimation"
                    + ", keys=" + ((Bundle) bundleObject).keySet());
            return result;
        }
        Context context = resolveMiuiHomeReturnHomeContext(overviewProxy,
                overviewProxy.getClass().getClassLoader());
        attachMiuiHomeReturnHome(shellBackAnimation,
                overviewProxy.getClass().getClassLoader(), context, "initialize");
        return result;
    }

    protected Object provideMiuiHomeReturnHomeLocalHandoff(
            XposedInterface.Chain chain) throws Throwable {
        Object nativeStatus = chain.proceed();
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (nativeStatus != null) {
            if (controller != null) {
                controller.discardLocalHandoffStatus(
                        chain.getThisObject(), chain.getArg(0),
                        "nativeStatus");
            }
            return nativeStatus;
        }
        if (controller != null) {
            Object status = controller.takeLocalHandoffStatus(
                    chain.getThisObject(), chain.getArg(0));
            if (status != null) {
                return status;
            }
        }
        return null;
    }

    protected Object observeMiuiHomeReturnHomeWallpaperSet(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller != null) {
            controller.onWallpaperCommand(chain.getThisObject(),
                    chain.getArg(0), false);
        }
        return result;
    }

    protected Object observeMiuiHomeReturnHomeWallpaperAnim(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        if (controller != null) {
            controller.onWallpaperCommand(chain.getThisObject(),
                    chain.getArg(0), true);
        }
        return result;
    }

    protected synchronized void attachMiuiHomeReturnHome(
            IBinder shellBackAnimation, ClassLoader classLoader, Context context,
            String reason) {
        if (shellBackAnimation == null || classLoader == null) {
            log(Log.WARN, TAG, "Cannot attach MiuiHome return-to-home runner"
                    + ", reason=" + reason
                    + ", binder=" + shortObject(shellBackAnimation)
                    + ", classLoader=" + classLoader);
            return;
        }
        MiuiHomeReturnHomeController existing = miuiHomeReturnHomeController;
        if (existing != null && existing.attached
                && existing.shellBackAnimation == shellBackAnimation) {
            if (context != null) {
                existing.context = context.getApplicationContext();
            }
            miuiHomeReturnHomeBinder = shellBackAnimation;
            log(Log.INFO, TAG, "Kept existing MiuiHome return-to-home runner"
                    + ", reason=" + reason
                    + ", binder=" + shortObject(shellBackAnimation));
            return;
        }
        if (existing != null && existing.blocksControllerReplacement()) {
            pendingMiuiHomeReturnHomeBinder = shellBackAnimation;
            pendingMiuiHomeReturnHomeClassLoader = classLoader;
            pendingMiuiHomeReturnHomeContext = context == null ? null
                    : context.getApplicationContext();
            pendingMiuiHomeReturnHomeReason = reason;
            existing.beginDeferredControllerReplacement(
                    "replace:" + reason);
            log(Log.WARN, TAG,
                    "Deferred Shell return-to-home controller replacement"
                            + ", reason=" + reason
                            + ", oldBinder="
                            + shortObject(existing.shellBackAnimation)
                            + ", newBinder="
                            + shortObject(shellBackAnimation)
                            + ", owner="
                            + existing.describeUnifiedOwner());
            return;
        }
        if (existing != null) {
            existing.detach(true, "replace:" + reason);
        }
        MiuiHomeReturnHomeController replacement =
                new MiuiHomeReturnHomeController(
                        shellBackAnimation, classLoader, context);
        if (!replacement.attach()) {
            // The synchronous registration may have succeeded before linkToDeath failed.
            // Clear best-effort so Shell never retains a callback from a rejected controller.
            replacement.detach(true, "attachFailed:" + reason);
            miuiHomeReturnHomeController = null;
            miuiHomeReturnHomeBinder = shellBackAnimation;
            return;
        }
        miuiHomeReturnHomeController = replacement;
        miuiHomeReturnHomeBinder = shellBackAnimation;
        clearPendingMiuiHomeReturnHomeAttachmentLocked();
        log(Log.INFO, TAG, "Registered standard Shell return-to-home callback/runner"
                + ", reason=" + reason
                + ", type=" + TYPE_RETURN_TO_HOME
                + ", binder=" + shortObject(shellBackAnimation)
                + ", nativePostCommit=Xiaomi-CLOSE_TO_HOME");
    }

    protected synchronized IBinder detachMiuiHomeReturnHome(String reason,
                                                           boolean clearShell) {
        IBinder binder = miuiHomeReturnHomeBinder;
        MiuiHomeReturnHomeController controller = miuiHomeReturnHomeController;
        miuiHomeReturnHomeController = null;
        miuiHomeReturnHomeBinder = null;
        clearPendingMiuiHomeReturnHomeAttachmentLocked();
        if (controller != null) {
            binder = controller.shellBackAnimation;
            controller.detach(clearShell, reason);
        }
        return binder;
    }

    protected synchronized void handleMiuiHomeReturnHomeBinderDeath(
            MiuiHomeReturnHomeController controller) {
        if (controller == null || miuiHomeReturnHomeController != controller) {
            return;
        }
        miuiHomeReturnHomeBinder = null;
        controller.onShellBinderDied();
        if (miuiHomeReturnHomeController != controller) {
            return;
        }
        if (!controller.blocksControllerReplacement()) {
            miuiHomeReturnHomeController = null;
            controller.detach(false, "shellBinderDied");
        }
    }

    protected synchronized void finishDeferredMiuiHomeReturnHomeController(
            MiuiHomeReturnHomeController retiredController, String reason) {
        if (retiredController == null
                || miuiHomeReturnHomeController != retiredController
                || !retiredController.deferredControllerReplacement
                || retiredController.currentSession != null
                || !retiredController
                .pendingUnifiedInterruptedAnimToConfigs.isEmpty()) {
            return;
        }
        miuiHomeReturnHomeController = null;
        miuiHomeReturnHomeBinder = null;
        IBinder pendingBinder = pendingMiuiHomeReturnHomeBinder;
        ClassLoader pendingClassLoader =
                pendingMiuiHomeReturnHomeClassLoader;
        Context pendingContext = pendingMiuiHomeReturnHomeContext;
        String pendingReason = pendingMiuiHomeReturnHomeReason;
        clearPendingMiuiHomeReturnHomeAttachmentLocked();
        retiredController.detach(true,
                "deferredOwnerCleaned:" + reason);
        if (pendingBinder != null && pendingClassLoader != null) {
            attachMiuiHomeReturnHome(pendingBinder, pendingClassLoader,
                    pendingContext,
                    "deferred:" + (pendingReason == null
                            ? reason : pendingReason));
        } else {
            log(Log.INFO, TAG,
                    "Retired Shell return-to-home controller without replacement"
                            + ", reason=" + reason
                            + ", oldBinder="
                            + shortObject(retiredController.shellBackAnimation));
        }
    }

    protected void clearPendingMiuiHomeReturnHomeAttachmentLocked() {
        pendingMiuiHomeReturnHomeBinder = null;
        pendingMiuiHomeReturnHomeClassLoader = null;
        pendingMiuiHomeReturnHomeContext = null;
        pendingMiuiHomeReturnHomeReason = null;
    }

    protected void restoreMiuiHomeReturnHomeAfterHotReload(ClassLoader classLoader) {
        IBinder binder = miuiHomeReturnHomeBinder;
        if (binder == null) {
            log(Log.INFO, TAG, "No saved Shell IBackAnimation after hot reload; "
                    + "waiting for launcher reinitialization");
            return;
        }
        Context context = resolveMiuiHomeReturnHomeContext(null, classLoader);
        attachMiuiHomeReturnHome(binder, classLoader, context, "hotReload");
    }

    protected Context resolveMiuiHomeReturnHomeContext(Object overviewProxy,
                                                      ClassLoader classLoader) {
        if (overviewProxy != null) {
            try {
                Object service = readField(overviewProxy, "mService");
                if (service instanceof Context) {
                    return ((Context) service).getApplicationContext();
                }
            } catch (Throwable ignored) {
            }
        }
        Context known = miuiHomeOpenBreakContext;
        if (known != null) {
            return known.getApplicationContext();
        }
        if (classLoader != null) {
            try {
                Class<?> applicationClass = Class.forName(
                        "com.miui.home.launcher.Application", false, classLoader);
                Method getInstance = applicationClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                Object application = getInstance.invoke(null);
                if (application instanceof Context) {
                    return ((Context) application).getApplicationContext();
                }
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to resolve MiuiHome context for return-to-home",
                        throwable);
            }
        }
        return null;
    }

    protected void sendAuthenticatedMiuiHomeState(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
        Intent explicitIntent = new Intent(intent);
        explicitIntent.setPackage(SYSTEM_UI);
        Bundle options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle();
        appContext.sendBroadcast(explicitIntent, null, options);
    }

    protected void sendAuthenticatedMiuiHomeOpenBreakCommand(
            Context context, long generation, long attemptId,
            SystemUiBackGestureDriver driver) {
        // Close the local admission gate as soon as one committed command is emitted. The
        // MiuiHome receiver independently revalidates its native controller before acting.
        if (miuiLauncherOpenBreakGeneration == generation) {
            miuiLauncherOpenBreakAvailable = false;
        }
        Context appContext = context.getApplicationContext();
        Intent commandIntent = new Intent(MODULE_MIUI_HOME_OPEN_BREAK_COMMAND);
        commandIntent.setPackage(MIUI_HOME);
        commandIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_GENERATION, generation);
        commandIntent.putExtra(EXTRA_LAUNCHER_OPEN_BREAK_ATTEMPT, attemptId);
        Bundle options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle();
        BroadcastReceiver resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                driver.onLauncherOpenBreakCommandResult(
                        generation, attemptId, getResultCode(), getResultData());
            }
        };
        appContext.sendOrderedBroadcast(commandIntent, null, options,
                resultReceiver, new Handler(Looper.getMainLooper()),
                LAUNCHER_OPEN_BREAK_RESULT_NO_RECEIVER, "noReceiver", null);
        log(Log.INFO, TAG, "Sent authenticated MiuiHome launcher OPEN break command"
                + ", generation=" + generation
                + ", attempt=" + attemptId
                + ", ordered=true");
    }

    protected Object arbitrateMiuiHomeAcceptedInput(XposedInterface.Chain chain) {
        Object eventObject = chain.getArg(0);
        Object stubObject = chain.getArg(1);
        if (!(eventObject instanceof MotionEvent) || !(stubObject instanceof View)) {
            log(Log.ERROR, TAG, "Blocked malformed MiuiHome gesture-processor call"
                    + ", event=" + shortObject(eventObject)
                    + ", stub=" + shortObject(stubObject));
            return null;
        }
        MotionEvent event = (MotionEvent) eventObject;
        View stub = (View) stubObject;
        ensureMiuiHomeInputArbiterReceiver(stub.getContext());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && event.getPointerCount() == 1
                && miuiHomeSystemUiInputArbiterReady
                && miuiHomeSystemUiInputArbiterGeneration != 0L) {
            try {
                int eventId = readMotionEventId(event);
                int edge = readIntFieldOrDefault(stub, "mGestureStubPos", -1);
                if (edge != EDGE_LEFT && edge != EDGE_RIGHT) {
                    throw new IllegalStateException("Invalid GestureStub edge=" + edge);
                }
                int displayId = readMotionEventDisplayId(event);
                Intent acceptedIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
                acceptedIntent.putExtra(EXTRA_INPUT_ACCEPTED, true);
                acceptedIntent.putExtra(EXTRA_INPUT_EVENT_ID, eventId);
                acceptedIntent.putExtra(EXTRA_INPUT_DOWN_TIME, event.getDownTime());
                acceptedIntent.putExtra(EXTRA_INPUT_DEVICE_ID, event.getDeviceId());
                acceptedIntent.putExtra(EXTRA_INPUT_SOURCE, event.getSource());
                acceptedIntent.putExtra(EXTRA_INPUT_DISPLAY_ID, displayId);
                acceptedIntent.putExtra(EXTRA_INPUT_EDGE, edge);
                acceptedIntent.putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                        miuiHomeSystemUiInputArbiterGeneration);
                MiuiHomeAcceptedInputToken inputIdentity =
                        new MiuiHomeAcceptedInputToken(
                                eventId, event.getDownTime(),
                                event.getDeviceId(), event.getSource(),
                                displayId, edge,
                                miuiHomeSystemUiInputArbiterGeneration);
                miuiHomeAcceptedInputIdentity.set(inputIdentity);
                MiuiHomeReturnHomeController returnHomeController =
                        miuiHomeReturnHomeController;
                if (returnHomeController != null) {
                    returnHomeController
                            .onMiuiHomeAcceptedInputIdentityChanged(
                                    inputIdentity);
                }
                sendAuthenticatedMiuiHomeState(stub.getContext(), acceptedIntent);
                log(Log.INFO, TAG, "Published MiuiHome accepted input token"
                        + ", eventId=" + eventId
                        + ", downTime=" + event.getDownTime()
                        + ", displayId=" + displayId
                        + ", edge=" + edge
                        + ", generation="
                        + miuiHomeSystemUiInputArbiterGeneration);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to publish MiuiHome accepted input token; "
                        + "gesture remains fail-closed", throwable);
            }
        }
        // GestureStubView calls this method only after its native redirect and early-route
        // decisions have accepted the stream. Keep the real Xiaomi input target, but never
        // let its legacy processor create a second BackAnimationAdapter, arrow, or BACK.
        return null;
    }

    protected int readMotionEventId(MotionEvent event) throws Exception {
        Object value = invokeAnyMethod(event, "getId", new Object[0]);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("MotionEvent.getId returned "
                    + shortObject(value));
        }
        return ((Number) value).intValue();
    }

    protected int readMotionEventDisplayId(MotionEvent event) throws Exception {
        Object value = invokeAnyMethod(event, "getDisplayId", new Object[0]);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    protected synchronized void ensureMiuiHomeInputArbiterReceiver(Context context) {
        if (miuiHomeInputArbiterReceiver != null || context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !MODULE_SYSTEMUI_INPUT_ARBITER_STATE.equals(
                        intent.getAction())) {
                    return;
                }
                int senderUid = getSentFromUid();
                String senderPackage = getSentFromPackage();
                if (!isTrustedSystemUiBroadcastSender(
                        receiverContext, senderUid, senderPackage)) {
                    log(Log.WARN, TAG, "Rejected untrusted SystemUI input-arbiter state"
                            + ", uid=" + senderUid
                            + ", package=" + senderPackage);
                    return;
                }
                long generation = intent.getLongExtra(
                        EXTRA_INPUT_ARBITER_GENERATION, 0L);
                boolean ready = intent.getBooleanExtra(EXTRA_INPUT_ARBITER_READY, false);
                if (generation == 0L) {
                    return;
                }
                boolean newGeneration = generation > miuiHomeSystemUiInputArbiterGeneration;
                if (generation >= miuiHomeSystemUiInputArbiterGeneration) {
                    miuiHomeSystemUiInputArbiterGeneration = generation;
                    miuiHomeSystemUiInputArbiterReady = ready;
                }
                log(Log.INFO, TAG, "SystemUI input arbiter state changed"
                        + ", ready=" + miuiHomeSystemUiInputArbiterReady
                        + ", generation="
                        + miuiHomeSystemUiInputArbiterGeneration
                        + ", senderGeneration=" + generation);
                if (newGeneration) {
                    miuiHomeAcceptedInputIdentity.set(null);
                    miuiHomeEditingStatePublished = false;
                    refreshMiuiHomeEditingState(
                            receiverContext.getClassLoader(), "systemUiArbiterGeneration");
                }
                if (intent.hasExtra(EXTRA_RETURN_HOME_COMMIT_ATTEMPT)) {
                    long attempt = intent.getLongExtra(
                            EXTRA_RETURN_HOME_COMMIT_ATTEMPT, 0L);
                    int taskId = intent.getIntExtra(
                            EXTRA_RETURN_HOME_COMMIT_TASK_ID, -1);
                    int transitionDebugId = intent.getIntExtra(
                            EXTRA_RETURN_HOME_COMMIT_DEBUG_ID, -1);
                    int eventId = intent.getIntExtra(
                            EXTRA_INPUT_EVENT_ID, 0);
                    long downTime = intent.getLongExtra(
                            EXTRA_INPUT_DOWN_TIME, Long.MIN_VALUE);
                    int deviceId = intent.getIntExtra(
                            EXTRA_INPUT_DEVICE_ID, Integer.MIN_VALUE);
                    int source = intent.getIntExtra(
                            EXTRA_INPUT_SOURCE, 0);
                    int displayId = intent.getIntExtra(
                            EXTRA_INPUT_DISPLAY_ID, Integer.MIN_VALUE);
                    int edge = intent.getIntExtra(
                            EXTRA_INPUT_EDGE, -1);
                    MiuiHomeReturnHomeController controller =
                            miuiHomeReturnHomeController;
                    if (attempt <= 0L || taskId < 0
                            || transitionDebugId < 0
                            || !intent.hasExtra(EXTRA_INPUT_EVENT_ID)
                            || downTime == Long.MIN_VALUE
                            || deviceId == Integer.MIN_VALUE
                            || displayId == Integer.MIN_VALUE
                            || (edge != EDGE_LEFT && edge != EDGE_RIGHT)
                            || generation
                            != miuiHomeSystemUiInputArbiterGeneration
                            || !miuiHomeSystemUiInputArbiterReady
                            || controller == null) {
                        log(Log.WARN, TAG,
                                "Rejected stale standard return-home commit signal"
                                        + ", attempt=" + attempt
                                        + ", taskId=" + taskId
                                        + ", transitionDebugId="
                                        + transitionDebugId
                                        + ", senderGeneration=" + generation
                                        + ", currentGeneration="
                                        + miuiHomeSystemUiInputArbiterGeneration
                                        + ", arbiterReady="
                                        + miuiHomeSystemUiInputArbiterReady
                                        + ", controller="
                                        + shortObject(controller)
                                        + ", eventId=" + eventId
                                        + ", downTime=" + downTime
                                        + ", displayId=" + displayId
                                        + ", edge=" + edge);
                        return;
                    }
                    controller.onStandardShellReturnHomeCommit(
                            new StandardReturnHomeCommitSignal(
                                    attempt, generation, taskId,
                                    transitionDebugId, eventId,
                                    downTime, deviceId, source,
                                    displayId, edge));
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            miuiHomeInputArbiterReceiverContext = appContext;
            miuiHomeInputArbiterReceiver = receiver;
            Intent query = new Intent(MODULE_MIUI_HOME_INPUT_ARBITER_QUERY);
            sendAuthenticatedMiuiHomeState(appContext, query);
            log(Log.INFO, TAG, "Registered MiuiHome input-arbiter state receiver"
                    + ", queriedSystemUi=true");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to register MiuiHome input-arbiter receiver",
                    throwable);
        }
    }

    protected synchronized void unregisterMiuiHomeInputArbiterReceiver() {
        BroadcastReceiver receiver = miuiHomeInputArbiterReceiver;
        Context receiverContext = miuiHomeInputArbiterReceiverContext;
        miuiHomeInputArbiterReceiver = null;
        miuiHomeInputArbiterReceiverContext = null;
        miuiHomeSystemUiInputArbiterReady = false;
        miuiHomeSystemUiInputArbiterGeneration = 0L;
        miuiHomeAcceptedInputIdentity.set(null);
        if (receiver == null || receiverContext == null) {
            return;
        }
        try {
            receiverContext.unregisterReceiver(receiver);
            log(Log.INFO, TAG, "Unregistered MiuiHome input-arbiter receiver");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to unregister MiuiHome input-arbiter receiver",
                    throwable);
        }
    }

    protected void onSystemUiInputMonitorAttached(Context context) {
        int count = systemUiInputArbiterMonitorCount.incrementAndGet();
        publishSystemUiInputArbiterState(context, count > 0, "monitorAttached");
    }

    protected void onSystemUiInputMonitorDetached(Context context) {
        int count = systemUiInputArbiterMonitorCount.decrementAndGet();
        if (count < 0) {
            systemUiInputArbiterMonitorCount.set(0);
            count = 0;
        }
        publishSystemUiInputArbiterState(context, count > 0, "monitorDetached");
    }

    protected void publishSystemUiInputArbiterState(Context context, boolean ready,
                                                   String reason) {
        if (context == null) {
            return;
        }
        try {
            Intent stateIntent = new Intent(MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
            stateIntent.setPackage(MIUI_HOME);
            stateIntent.putExtra(EXTRA_INPUT_ARBITER_READY, ready);
            stateIntent.putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                    systemUiInputArbiterGeneration);
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.getApplicationContext().sendBroadcast(stateIntent, null, options);
            log(Log.INFO, TAG, "Published SystemUI input-arbiter state"
                    + ", ready=" + ready
                    + ", generation=" + systemUiInputArbiterGeneration
                    + ", monitors=" + systemUiInputArbiterMonitorCount.get()
                    + ", reason=" + reason);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to publish SystemUI input-arbiter state"
                    + ", reason=" + reason, throwable);
        }
    }

    protected void publishStandardReturnHomeCommit(
            int taskId, int transitionDebugId,
            Object compositionController) {
        Context context = miuiOverviewReceiverContext;
        long attempt = systemUiReturnHomeCommitAttemptIds.incrementAndGet();
        SystemUiReturnHomeCommitIdentity identity =
                systemUiReturnHomeCommitIdentity.get();
        MiuiHomeAcceptedInputToken input = identity == null
                ? null : identity.input;
        if (context == null || taskId < 0 || transitionDebugId < 0
                || systemUiInputArbiterMonitorCount.get() <= 0
                || identity == null || input == null
                || identity.taskId != taskId
                || identity.controller != compositionController
                || input.generation
                != systemUiInputArbiterGeneration) {
            log(Log.ERROR, TAG,
                    "Could not publish standard return-home commit"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", transitionDebugId=" + transitionDebugId
                            + ", context=" + shortObject(context)
                            + ", monitors="
                            + systemUiInputArbiterMonitorCount.get()
                            + ", identityTaskId="
                            + (identity == null ? -1 : identity.taskId)
                            + ", sameController="
                            + (identity != null
                            && identity.controller
                            == compositionController)
                            + ", eventId="
                            + (input == null ? 0 : input.eventId)
                            + ", inputGeneration="
                            + (input == null ? 0L : input.generation));
            return;
        }
        if (!systemUiReturnHomeCommitIdentity.compareAndSet(
                identity, null)) {
            log(Log.ERROR, TAG,
                    "Lost committed return-home input identity before publish"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", eventId=" + input.eventId);
            return;
        }
        try {
            Intent intent = new Intent(MODULE_SYSTEMUI_INPUT_ARBITER_STATE);
            intent.setPackage(MIUI_HOME);
            intent.putExtra(EXTRA_INPUT_ARBITER_READY, true);
            intent.putExtra(EXTRA_INPUT_ARBITER_GENERATION,
                    systemUiInputArbiterGeneration);
            intent.putExtra(EXTRA_RETURN_HOME_COMMIT_ATTEMPT, attempt);
            intent.putExtra(EXTRA_RETURN_HOME_COMMIT_TASK_ID, taskId);
            intent.putExtra(EXTRA_RETURN_HOME_COMMIT_DEBUG_ID,
                    transitionDebugId);
            intent.putExtra(EXTRA_INPUT_EVENT_ID, input.eventId);
            intent.putExtra(EXTRA_INPUT_DOWN_TIME, input.downTime);
            intent.putExtra(EXTRA_INPUT_DEVICE_ID, input.deviceId);
            intent.putExtra(EXTRA_INPUT_SOURCE, input.source);
            intent.putExtra(EXTRA_INPUT_DISPLAY_ID, input.displayId);
            intent.putExtra(EXTRA_INPUT_EDGE, input.edge);
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.sendBroadcast(intent, null, options);
            log(Log.INFO, TAG,
                    "Published standard predictive return-home commit"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", transitionDebugId=" + transitionDebugId
                            + ", eventId=" + input.eventId
                            + ", downTime=" + input.downTime
                            + ", arbiterGeneration="
                            + systemUiInputArbiterGeneration);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to publish standard return-home commit"
                            + ", attempt=" + attempt
                            + ", taskId=" + taskId
                            + ", transitionDebugId=" + transitionDebugId,
                    throwable);
        }
    }

    protected void clearSystemUiReturnHomeCommitIdentity(
            Object controller, long shellSessionId, String reason) {
        while (true) {
            SystemUiReturnHomeCommitIdentity identity =
                    systemUiReturnHomeCommitIdentity.get();
            if (identity == null
                    || (controller != null
                    && identity.controller != controller)
                    || (shellSessionId != 0L
                    && identity.shellSessionId != shellSessionId)) {
                return;
            }
            if (systemUiReturnHomeCommitIdentity.compareAndSet(
                    identity, null)) {
                log(Log.INFO, TAG,
                        "Cleared committed return-home input identity"
                                + ", taskId=" + identity.taskId
                                + ", eventId="
                                + identity.input.eventId
                                + ", shellSessionId="
                                + identity.shellSessionId
                                + ", reason=" + reason);
                return;
            }
        }
    }

    protected void restoreMiuiHomeGestureStubsAfterHotReload(ClassLoader classLoader) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Class<?> applicationClass = Class.forName(
                        "com.miui.home.launcher.Application", false, classLoader);
                Method getApplication = applicationClass.getDeclaredMethod(
                        "getLauncherApplication");
                getApplication.setAccessible(true);
                Object application = getApplication.invoke(null);
                if (application == null) {
                    log(Log.WARN, TAG, "Cannot restore MiuiHome GestureStubs: "
                            + "launcher application is null");
                    return;
                }
                Object recents = invokeAnyMethod(application, "getRecentsImpl",
                        new Object[0]);
                if (recents == null) {
                    log(Log.WARN, TAG, "Cannot restore MiuiHome GestureStubs: "
                            + "RecentsImpl is null");
                    return;
                }
                Object left = readField(recents, "mGestureStubLeft");
                Object right = readField(recents, "mGestureStubRight");
                AtomicInteger remaining = new AtomicInteger(2);
                Runnable restored = () -> {
                    if (remaining.decrementAndGet() != 0) {
                        return;
                    }
                    Runnable recomputePolicy = () -> {
                        try {
                            invokeAnyMethod(recents, "adaptToTopActivity", new Object[0]);
                            log(Log.INFO, TAG, "Recomputed native MiuiHome GestureStub policy "
                                    + "after hot reload");
                        } catch (Throwable throwable) {
                            log(Log.WARN, TAG, "Failed to recompute native MiuiHome "
                                    + "GestureStub policy", throwable);
                        }
                    };
                    try {
                        Object handler = readField(recents, "mHandler");
                        if (handler instanceof Handler
                                && ((Handler) handler).post(recomputePolicy)) {
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    new Handler(Looper.getMainLooper()).post(recomputePolicy);
                };
                restoreMiuiHomeGestureStubOnOwner(left, "left", restored);
                restoreMiuiHomeGestureStubOnOwner(right, "right", restored);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Failed to restore existing MiuiHome GestureStubs",
                        throwable);
            }
        });
    }

    protected void restoreMiuiHomeGestureStubOnOwner(Object stubObject, String edge,
                                                    Runnable completion) {
        if (!(stubObject instanceof View)) {
            log(Log.INFO, TAG, "MiuiHome GestureStub absent during hot reload"
                    + ", edge=" + edge);
            completion.run();
            return;
        }
        View stub = (View) stubObject;
        Runnable restore = () -> {
            try {
                ensureMiuiHomeInputArbiterReceiver(stub.getContext());
                Object paramsObject = readField(stub, "mGestureStubParams");
                Object windowManagerObject = readField(stub, "mWindowManager");
                if (!(paramsObject instanceof WindowManager.LayoutParams)
                        || !(windowManagerObject instanceof WindowManager)) {
                    throw new IllegalStateException("Missing native window state");
                }
                WindowManager.LayoutParams params =
                        (WindowManager.LayoutParams) paramsObject;
                boolean changed = (params.flags
                        & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                if (stub.isAttachedToWindow()) {
                    ((WindowManager) windowManagerObject).updateViewLayout(stub, params);
                }
                stub.requestLayout();
                stub.requestApplyInsets();
                log(Log.INFO, TAG, "Restored existing MiuiHome GestureStub window"
                        + ", edge=" + edge
                        + ", attached=" + stub.isAttachedToWindow()
                        + ", clearedNotTouchable=" + changed);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "Failed to restore MiuiHome GestureStub"
                        + ", edge=" + edge, throwable);
            } finally {
                completion.run();
            }
        };
        Handler ownerHandler = stub.getHandler();
        if (ownerHandler == null || ownerHandler.getLooper() == Looper.myLooper()) {
            restore.run();
        } else if (!ownerHandler.post(restore)) {
            log(Log.WARN, TAG, "Failed to post MiuiHome GestureStub restore"
                    + ", edge=" + edge);
            completion.run();
        }
    }

    protected Object restoreMiuiHomeGestureStubTouchableLayout(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        if (result instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) result;
            layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        return result;
    }

    protected Object restoreMiuiHomeGestureStubShow(XposedInterface.Chain chain)
            throws Throwable {
        Object result = chain.proceed();
        try {
            Object view = chain.getThisObject();
            if (view instanceof View) {
                ensureMiuiHomeInputArbiterReceiver(((View) view).getContext());
                Object params = readField(view, "mGestureStubParams");
                Object windowManager = readField(view, "mWindowManager");
                if (params instanceof WindowManager.LayoutParams
                        && windowManager instanceof WindowManager
                        && ((((WindowManager.LayoutParams) params).flags
                        & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)) {
                    WindowManager.LayoutParams layoutParams =
                            (WindowManager.LayoutParams) params;
                    layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    if (((View) view).isAttachedToWindow()) {
                        ((WindowManager) windowManager).updateViewLayout(
                                (View) view, layoutParams);
                    }
                    ((View) view).requestLayout();
                    ((View) view).requestApplyInsets();
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Failed to restore MiuiHome gesture-stub window", throwable);
        }
        return result;
    }
}
