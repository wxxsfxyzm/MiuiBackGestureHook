//package dev.codex.miuibackgesturehook.hooks.systemserver;
//
//import java.lang.reflect.Method;
//
//import io.github.libxposed.api.XposedInterface;
//
//abstract class SystemServerPlatformImpl {
//    abstract String name();
//
//    abstract Method calculateTransitionInfoMethod(Class<?> transitionClass)
//            throws NoSuchMethodException;
//
//    abstract Object interceptScheduleAnimationPrepareTransition(
//            SystemServerHookRuntime runtime,
//            XposedInterface.Chain chain) throws Throwable;
//
//    Method openingSurfaceVisibilityMethod(ClassLoader classLoader) throws Exception {
//        return null;
//    }
//
//    void restoreOpeningSurfaceVisibility(SystemServerHookRuntime runtime,
//                                         XposedInterface.Chain chain) throws Exception {
//    }
//
//    boolean shouldForceOpeningTaskFragmentAtAnimationStart() {
//        return true;
//    }
//
//    boolean nativeLauncherOwnsContextualSearchLongPress() {
//        return false;
//    }
//
//    boolean alwaysRegisterContextualSearchService() {
//        return false;
//    }
//
//    void inspectCalculatedPredictiveTransition(SystemServerHookRuntime runtime,
//                                                XposedInterface.Chain chain,
//                                                Object result) throws Exception {
//    }
//}
