package dev.codex.miuibackgesturehook.hooks.systemui;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;

import android.animation.Animator;
import android.content.Context;

import java.lang.reflect.Method;

abstract class SystemUiPlatformImpl {
    abstract String name();

    abstract Class<?> backAnimationParameterClass(ClassLoader classLoader) throws Exception;

    abstract boolean isNavigationOverlayExcluded(Object edgeBackGestureHandler,
                                                  int x, int y) throws Exception;

    abstract Object findNativeEdgeBackPlugin(Object edgeBackGestureHandler) throws Exception;

    abstract Object ensureNativeEdgeBackPlugin(Object edgeBackGestureHandler,
                                               Context context) throws Exception;

    abstract void prepareNativeBackPanel(Object edgeBackGestureHandler,
                                         Object plugin) throws Exception;

    abstract void updateDisplaySize(Object edgeBackGestureHandler,
                                    Object plugin) throws Exception;

    abstract boolean canCreateNavBarOrTaskBar(Object controller, int displayId)
            throws Exception;

    boolean requiresStableGestureInsetsOverrideTypes() {
        return false;
    }

    int backAnimationBackgroundEnsureParameterCount() {
        return 6;
    }

    String defaultTransitionAnimatorsFieldName() {
        return "mAnimations";
    }

    boolean captureOpenFromTransitionsOwner() {
        return false;
    }

    Animator unwrapDefaultTransitionAnimator(Object entry) throws Exception {
        return entry instanceof Animator ? (Animator) entry : null;
    }

    Method brokenBackAnimationStatusBarResetMethod(ClassLoader classLoader)
            throws Exception {
        return null;
    }

    Method backEventGuardMethod(ClassLoader classLoader) throws Exception {
        Class<?> controllerClass = Class.forName(
                "com.android.wm.shell.back.BackAnimationController",
                false, classLoader);
        Method method = controllerClass.getDeclaredMethod(
                "sendBackEvent", int.class);
        method.setAccessible(true);
        return method;
    }

    void injectLegacyBackKey(Object controller, int displayId) throws Exception {
        invokeCompatible(controller, "sendBackEvent", Integer.valueOf(0));
        invokeCompatible(controller, "sendBackEvent", Integer.valueOf(1));
    }

    String systemUiInputArbiterStateAction(String defaultAction) {
        return defaultAction;
    }

    boolean nativeLauncherOwnsContextualSearchLongPress() {
        return false;
    }

    void destroy() {
    }

}
