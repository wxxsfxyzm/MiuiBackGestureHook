//package dev.codex.miuibackgesturehook.hooks.systemserver;
//
//import android.util.Log;
//import android.view.SurfaceControl;
//
//import java.lang.reflect.Method;
//
//import io.github.libxposed.api.XposedInterface;
//
//final class SystemServerAndroid16Impl extends SystemServerPlatformImpl {
//    static boolean matches(Class<?> transitionClass) {
//        return findCalculateTransitionInfo(transitionClass) != null;
//    }
//
//    @Override
//    String name() {
//        return "android16";
//    }
//
//    @Override
//    boolean alwaysRegisterContextualSearchService() {
//        // SystemServer evaluates this gate only during boot. Keep the dormant service
//        // available so the authenticated runtime preference can be enabled later without
//        // having missed its one registration opportunity.
//        return true;
//    }
//
//    @Override
//    Method calculateTransitionInfoMethod(Class<?> transitionClass)
//            throws NoSuchMethodException {
//        Method method = findCalculateTransitionInfo(transitionClass);
//        if (method == null) {
//            throw new NoSuchMethodException(
//                    "Android 16 Transition.calculateTransitionInfo(int, int, ArrayList,"
//                            + " Transaction, int)");
//        }
//        method.setAccessible(true);
//        return method;
//    }
//
//    @Override
//    Object interceptScheduleAnimationPrepareTransition(
//            SystemServerHookRuntime runtime,
//            XposedInterface.Chain chain) throws Throwable {
//        ClassLoader loader = chain.getExecutable().getDeclaringClass().getClassLoader();
//        Object builder = chain.getThisObject();
//        Object launchBehind = runtime.readSystemServerPlatformFieldOrNull(
//                builder, "mIsLaunchBehind");
//        boolean launchBehindKnown = launchBehind instanceof Boolean;
//        boolean returnToHome = Boolean.TRUE.equals(launchBehind);
//        boolean unify = runtime.readSystemServerPlatformWindowFlag(
//                "unifyBackNavigationTransition", loader, false);
//        if (unify && launchBehindKnown && !returnToHome) {
//            boolean exactCrossActivity;
//            try {
//                exactCrossActivity = runtime.isExactFreeformCrossActivityPrepare(
//                        chain, builder);
//            } catch (Throwable throwable) {
//                runtime.logSystemServerPlatform(Log.WARN,
//                        "Failed to inspect Android 16 cross-activity prepare;"
//                                + " preserving the platform transition",
//                        throwable);
//                return chain.proceed();
//            }
//            if (exactCrossActivity) {
//                Object close = chain.getArg(1);
//                Object[] open = (Object[]) chain.getArg(2);
//                runtime.logSystemServerPlatform(Log.INFO,
//                        "Allowing Android 16 native unified prepare for exact"
//                                + " cross-activity, close="
//                                + runtime.describeSystemServerPlatformObject(close)
//                                + ", open="
//                                + runtime.describeSystemServerPlatformObject(open[0]));
//                Object transition = chain.proceed();
//                runtime.logSystemServerPlatform(Log.INFO,
//                        "Android 16 native cross-activity prepare completed"
//                                + ", transition="
//                                + runtime.describeSystemServerPlatformObject(transition));
//                return transition;
//            }
//            runtime.logSystemServerPlatform(Log.INFO,
//                    "Skipped Android 16 ScheduleAnimationBuilder.prepareTransitionIfNeeded"
//                            + " to avoid Xiaomi unified-transition leash reparenting"
//                            + ", unifyBackNavigationTransition=true"
//                            + ", returnToHome=false"
//                            + ", launchBehind=" + launchBehind
//                            + ", builder="
//                            + runtime.describeSystemServerPlatformObject(builder));
//            return null;
//        }
//        if (!launchBehindKnown) {
//            runtime.logSystemServerPlatform(Log.WARN,
//                    "Unable to identify Android 16 ScheduleAnimationBuilder back type;"
//                            + " preserving the platform transition"
//                            + ", launchBehind=" + launchBehind
//                            + ", builder="
//                            + runtime.describeSystemServerPlatformObject(builder));
//        }
//        runtime.logSystemServerPlatform(Log.INFO,
//                "Allowing Android 16 ScheduleAnimationBuilder.prepareTransitionIfNeeded"
//                        + ", unifyBackNavigationTransition=" + unify
//                        + ", returnToHome=" + (launchBehindKnown
//                        ? Boolean.toString(returnToHome) : "unknown")
//                        + ", launchBehind=" + launchBehind
//                        + ", path=" + (unify
//                        ? "unified-prepared-transition"
//                        : "Xiaomi/AOSP-setLaunchBehind"));
//        return chain.proceed();
//    }
//
//    private static Method findCalculateTransitionInfo(Class<?> transitionClass) {
//        for (Method method : transitionClass.getDeclaredMethods()) {
//            Class<?>[] parameters = method.getParameterTypes();
//            if ("calculateTransitionInfo".equals(method.getName())
//                    && parameters.length == 5
//                    && parameters[0] == int.class
//                    && parameters[1] == int.class
//                    && "java.util.ArrayList".equals(parameters[2].getName())
//                    && parameters[3] == SurfaceControl.Transaction.class
//                    && parameters[4] == int.class) {
//                return method;
//            }
//        }
//        return null;
//    }
//}
