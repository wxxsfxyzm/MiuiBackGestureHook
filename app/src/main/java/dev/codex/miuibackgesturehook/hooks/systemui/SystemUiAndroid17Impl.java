package dev.codex.miuibackgesturehook.hooks.systemui;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;

import android.animation.Animator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class SystemUiAndroid17Impl extends SystemUiPlatformImpl {
    private final Map<Object, Object> modulePlugins =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Object> moduleBackCallbacks =
            Collections.synchronizedMap(new WeakHashMap<>());

    static boolean matches(Class<?> edgeBackGestureHandlerClass, ClassLoader classLoader) {
        try {
            findField(edgeBackGestureHandlerClass, "mDisplayBackGestureHandlerFactory");
            findField(edgeBackGestureHandlerClass, "mDisplayBackGestureHandlers");
            try {
                findField(edgeBackGestureHandlerClass, "mEdgeBackPlugin");
                return false;
            } catch (NoSuchFieldException expected) {
                Class.forName("com.android.systemui.navigationbar.gestural."
                        + "DisplayBackGestureHandlerImpl", false, classLoader);
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    String name() {
        return "android17";
    }

    @Override
    boolean nativeLauncherOwnsContextualSearchLongPress() {
        return true;
    }

    @Override
    Class<?> backAnimationParameterClass(ClassLoader classLoader) throws Exception {
        return Class.forName("com.android.wm.shell.back.BackAnimation", false, classLoader);
    }

    @Override
    Method brokenBackAnimationStatusBarResetMethod(ClassLoader classLoader)
            throws Exception {
        // HyperOS Android 17 keeps the AOSP CrossActivityBackAnimation callsites but
        // strips status-bar customization from BackAnimationBackground. In exact 4371
        // bytecode customizeStatusBarAppearance(int) and setStatusBarCustomizer(...)
        // return immediately, while resetStatusBarCustomization() is `throw null`.
        // Resolve the whole stripped shape here so Android 16 never enters this path.
        Class<?> backgroundClass = Class.forName(
                "com.android.wm.shell.back.BackAnimationBackground", false, classLoader);
        Class<?> customizerClass = Class.forName(
                "com.android.wm.shell.back.StatusBarCustomizer", false, classLoader);
        Method customize = backgroundClass.getDeclaredMethod(
                "customizeStatusBarAppearance", int.class);
        Method setCustomizer = backgroundClass.getDeclaredMethod(
                "setStatusBarCustomizer", customizerClass);
        Method reset = backgroundClass.getDeclaredMethod(
                "resetStatusBarCustomization");
        if (customize.getReturnType() != void.class
                || setCustomizer.getReturnType() != void.class
                || reset.getReturnType() != void.class) {
            throw new NoSuchMethodException(
                    "Unexpected Android 17 BackAnimationBackground status-bar API shape");
        }
        reset.setAccessible(true);
        return reset;
    }

    @Override
    Method backEventGuardMethod(ClassLoader classLoader) throws Exception {
        // Android 17 adds the target display to the private stock key-event helper.
        Class<?> controllerClass = Class.forName(
                "com.android.wm.shell.back.BackAnimationController",
                false, classLoader);
        Method method = controllerClass.getDeclaredMethod(
                "sendBackEvent", int.class, int.class);
        method.setAccessible(true);
        return method;
    }

    @Override
    void injectLegacyBackKey(Object controller, int displayId) throws Exception {
        // Keep the complete DOWN/UP pair inside Shell. Exact Android 17 injectBackKey(int)
        // forwards displayId to sendBackEvent(action, displayId) for both events.
        invokeCompatible(controller, "injectBackKey", Integer.valueOf(displayId));
    }

    @Override
    boolean isNavigationOverlayExcluded(Object edgeBackGestureHandler,
                                        int x, int y) throws Exception {
        // Android 17 removed mNavBarOverlayExcludedBounds. The parent handler's application,
        // desktop and PiP exclusions remain authoritative for the main display; the new
        // per-display holder has only another application system-gesture exclusion Region.
        findField(edgeBackGestureHandler.getClass(), "mDisplayBackGestureHandlerFactory");
        findField(edgeBackGestureHandler.getClass(), "mDisplayBackGestureHandlers");
        return false;
    }

    @Override
    Object findNativeEdgeBackPlugin(Object edgeBackGestureHandler) {
        return modulePlugins.get(edgeBackGestureHandler);
    }

    @Override
    Object ensureNativeEdgeBackPlugin(Object edgeBackGestureHandler,
                                      Context context) throws Exception {
        Object existing = modulePlugins.get(edgeBackGestureHandler);
        if (existing != null) {
            installBackCallback(existing, edgeBackGestureHandler);
            updateDisplaySize(edgeBackGestureHandler, existing);
            return existing;
        }

        WindowManager windowManager = context.getSystemService(WindowManager.class);
        if (windowManager == null) {
            throw new IllegalStateException("Android 17 WindowManager is unavailable");
        }
        Object backCallback = readField(edgeBackGestureHandler, "mBackCallback");
        ClassLoader classLoader = edgeBackGestureHandler.getClass().getClassLoader();
        WindowManager.LayoutParams layoutParams = createBackPanelLayoutParams(context);
        Object dependency = Class.forName("com.android.systemui.Dependency", false,
                classLoader).getField("sDependency").get(null);
        Object navigationBarControllerLazy = readField(
                dependency, "mNavigationBarController");
        Object navigationBarController = invokeCompatible(
                navigationBarControllerLazy, "get");
        Object componentFactory = readField(
                navigationBarController, "mNavigationBarComponentFactory");
        Object sysUiComponent = readField(componentFactory,
                "referenceSysUIComponentImpl");
        Object panelFactoryProvider = readField(sysUiComponent, "factoryProvider129");
        Object panelFactory = invokeCompatible(panelFactoryProvider, "get");
        Handler handler = (Handler) readField(
                readField(edgeBackGestureHandler, "mUiThreadContext"), "handler");
        Object plugin = invokeCompatible(panelFactory, "create",
                context, windowManager, handler);
        invokeCompatible(plugin, "init");
        if (backCallback != null) {
            invokeCompatible(plugin, "setBackCallback", backCallback);
        }

        invokeCompatible(plugin, "setLayoutParams", layoutParams);
        modulePlugins.put(edgeBackGestureHandler, plugin);
        updateDisplaySize(edgeBackGestureHandler, plugin);
        return plugin;
    }

    @Override
    void prepareNativeBackPanel(Object edgeBackGestureHandler,
                                Object plugin) throws Exception {
        // Xiaomi's Android 17 constructor leaves mBackCallback null because the real
        // callback is normally wired while creating each DisplayBackGestureHandlerImpl.
        // Our headless panel is created earlier from setBackAnimation(), so recreate the
        // same inner callback only at the accepted-DOWN boundary, after construction.
        installBackCallback(plugin, edgeBackGestureHandler);
        updateDisplaySize(edgeBackGestureHandler, plugin);
        invokeCompatible(plugin, "updateConfiguration$3");
        View panel = requireAospBackPanelView(plugin);
        applyAospBackPanelSoftwareLayer(panel);
        restoreAospBackPanelColors(panel);
        invokeCompatible(plugin, "updateRestingArrowDimens");
    }

    private View requireAospBackPanelView(Object plugin) throws Exception {
        Object panelObject = readField(plugin, "mView");
        if (!(panelObject instanceof View)) {
            throw new IllegalStateException(
                    "Android 17 BackPanel View is unavailable: " + panelObject);
        }
        return (View) panelObject;
    }

    private void applyAospBackPanelSoftwareLayer(View panel) {
        // Keep this independent from optional dynamic-color lookup. HyperOS night themes
        // may omit those framework color names; a color fallback must not silently leave
        // this small visual-only panel on the affected hardware Path renderer.
        panel.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        if (panel.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
            throw new IllegalStateException(
                    "Android 17 BackPanel rejected its software layer");
        }
        panel.invalidate();
    }

    private void restoreAospBackPanelColors(View panel) {
        try {
            Object arrowPaintObject = readField(panel, "arrowPaint");
            Object backgroundPaintObject = readField(panel, "arrowBackgroundPaint");
            if (!(arrowPaintObject instanceof Paint)
                    || !(backgroundPaintObject instanceof Paint)) {
                throw new IllegalStateException(
                        "Android 17 BackPanel paints are unavailable");
            }

            Context context = panel.getContext();
            boolean night = (panel.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int arrowColor = resolveAndroidColor(context,
                    night ? "system_on_secondary_container"
                            : "system_on_secondary_fixed",
                    "system_on_secondary_fixed");
            int backgroundColor = resolveAndroidColor(context,
                    night ? "system_secondary_container"
                            : "system_secondary_fixed_dim",
                    "system_secondary_fixed_dim");

            ((Paint) arrowPaintObject).setColor(arrowColor);
            Paint backgroundPaint = (Paint) backgroundPaintObject;
            backgroundPaint.setColor(backgroundColor);
            panel.invalidate();
        } catch (Throwable ignored) {
            // The native controller remains visible with its own colors. Color restoration
            // must never make an otherwise valid accepted gesture lose BackPanel dispatch.
        }
    }

    private int resolveAndroidColor(Context context, String name,
                                    String fallbackName) {
        int colorId = context.getResources().getIdentifier(name, "color", "android");
        if (colorId == 0 && !name.equals(fallbackName)) {
            colorId = context.getResources().getIdentifier(
                    fallbackName, "color", "android");
        }
        if (colorId == 0) {
            throw new Resources.NotFoundException(
                    "Android 17 BackPanel framework color is unavailable: "
                            + name + "/" + fallbackName);
        }
        return context.getColor(colorId);
    }

    @Override
    void updateDisplaySize(Object edgeBackGestureHandler,
                           Object plugin) throws Exception {
        Context context = (Context) readField(edgeBackGestureHandler, "mContext");
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        if (windowManager == null) {
            throw new IllegalStateException("Android 17 WindowManager is unavailable");
        }
        Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
        invokeCompatible(plugin, "setDisplaySize",
                new Point(bounds.width(), bounds.height()));
    }

    @Override
    boolean canCreateNavBarOrTaskBar(Object controller, int displayId) throws Exception {
        Object result = invokeCompatible(controller,
                "canCreateNavBarOrTaskBar", Integer.valueOf(displayId));
        return Boolean.TRUE.equals(result);
    }

    @Override
    boolean requiresStableGestureInsetsOverrideTypes() {
        // Android 17 rejects a relayout that introduces a new override type after
        // the NavigationBar window has already been added.  Declare the IME type
        // on the very first LayoutParams even while gesture eligibility is false.
        return true;
    }

    @Override
    int backAnimationBackgroundEnsureParameterCount() {
        // Exact HyperOS 4371: ensureBackground(Rect, int, Transaction, int, int).
        return 5;
    }

    @Override
    String defaultTransitionAnimatorsFieldName() {
        // Android 17 renamed DefaultTransitionHandler's token-to-animator-list map.
        // Keep the versioned name here so Android 16 retains mAnimations and an
        // unknown platform shape continues to fail closed.
        return "mTransitionAnimators";
    }

    @Override
    boolean captureOpenFromTransitionsOwner() {
        // Hook the Binder-facing player boundary, then enqueue capture behind its own
        // ready task on the same Shell executor. This avoids every inlined inner call.
        return true;
    }

    @Override
    Animator unwrapDefaultTransitionAnimator(Object entry) throws Exception {
        if (entry instanceof Animator) {
            return (Animator) entry;
        }
        if (entry == null || !"com.android.wm.shell.transition.WindowAnimation"
                .equals(entry.getClass().getName())) {
            return null;
        }
        Field animatorField = entry.getClass().getDeclaredField("mAnimator");
        animatorField.setAccessible(true);
        Object animator = animatorField.get(entry);
        return animator instanceof Animator ? (Animator) animator : null;
    }

    @Override
    String systemUiInputArbiterStateAction(String defaultAction) {
        // The native Android 17 launcher already owns this protected dynamic
        // receiver.  Carry the module extras through that existing filter so
        // the native bridge never mutates or duplicates MiuiHome's filter.
        // The bridge observes its authenticated extras and then preserves the
        // original Xiaomi receiver callback for launcher FSG-region state.
        return "com.android.systemui.fsgesture";
    }

    @Override
    void destroy() {
        List<Object> plugins;
        synchronized (modulePlugins) {
            plugins = new ArrayList<>(modulePlugins.values());
            modulePlugins.clear();
        }
        for (Object plugin : plugins) {
            try {
                invokeCompatible(plugin, "onDestroy");
            } catch (Throwable ignored) {
            }
        }
        moduleBackCallbacks.clear();
    }

    private void installBackCallback(Object plugin, Object edgeBackGestureHandler)
            throws Exception {
        Object backCallback = readField(edgeBackGestureHandler, "mBackCallback");
        if (backCallback == null) {
            backCallback = moduleBackCallbacks.get(edgeBackGestureHandler);
        }
        if (backCallback == null) {
            Class<?> callbackClass = findField(
                    edgeBackGestureHandler.getClass(), "mBackCallback").getType();
            Constructor<?>[] callbackConstructors =
                    callbackClass.getDeclaredConstructors();
            Constructor<?> callbackConstructor = null;
            for (Constructor<?> constructor : callbackConstructors) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 0
                        || (parameters.length == 1
                        && parameters[0].isInstance(edgeBackGestureHandler))) {
                    callbackConstructor = constructor;
                    break;
                }
            }
            if (callbackConstructor == null) {
                if (callbackConstructors.length != 0
                        || !callbackClass.getName().startsWith(
                        edgeBackGestureHandler.getClass().getName() + "$")) {
                    throw new NoSuchMethodException(callbackClass.getName()
                            + " constructor for EdgeBackGestureHandler");
                }
                // Android 17's R8 output has no <init> at all for
                // EdgeBackGestureHandler$5. The stock DEX creates it with
                // new-instance, invokes Object.<init> directly, then stores
                // the synthetic this$0 field. Reflection therefore reports
                // zero constructors; allocate only that exact owner-bound
                // callback shape without inventing module callback logic.
                backCallback = allocateConstructorlessCallback(callbackClass);
            } else {
                callbackConstructor.setAccessible(true);
                backCallback = callbackConstructor.getParameterTypes().length == 0
                        ? callbackConstructor.newInstance()
                        : callbackConstructor.newInstance(edgeBackGestureHandler);
            }
            try {
                writeField(backCallback, "this$0", edgeBackGestureHandler);
            } catch (NoSuchFieldException ignored) {
                // A conventional non-static inner constructor already captured its owner.
            }
            moduleBackCallbacks.put(edgeBackGestureHandler, backCallback);
        }
        invokeCompatible(plugin, "setBackCallback", backCallback);
    }

    private Object allocateConstructorlessCallback(Class<?> callbackClass)
            throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe", false,
                callbackClass.getClassLoader());
        Field unsafeField = findField(unsafeClass, "theUnsafe");
        Object unsafe = unsafeField.get(null);
        if (unsafe == null) {
            throw new IllegalStateException("Android 17 Unsafe is unavailable");
        }
        Method allocateInstance = unsafeClass.getDeclaredMethod(
                "allocateInstance", Class.class);
        allocateInstance.setAccessible(true);
        Object callback = allocateInstance.invoke(unsafe, callbackClass);
        if (callback == null || !callbackClass.isInstance(callback)) {
            throw new IllegalStateException("Android 17 callback allocation failed");
        }
        return callback;
    }

    private WindowManager.LayoutParams createBackPanelLayoutParams(Context context)
            throws Exception {
        Resources resources = context.getResources();
        String packageName = context.getPackageName();
        int widthId = resources.getIdentifier(
                "navigation_edge_panel_width", "dimen", packageName);
        int heightId = resources.getIdentifier(
                "navigation_edge_panel_height", "dimen", packageName);
        int titleId = resources.getIdentifier(
                "nav_bar_edge_panel", "string", packageName);
        if (widthId == 0 || heightId == 0 || titleId == 0) {
            throw new IllegalStateException("Android 17 BackPanel resources are unavailable"
                    + ", width=" + widthId + ", height=" + heightId
                    + ", title=" + titleId);
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                resources.getDimensionPixelSize(widthId),
                resources.getDimensionPixelSize(heightId), 2024, 280, -3);
        writeField(params, "accessibilityTitle", context.getString(titleId));
        params.windowAnimations = 0;
        int privateFlags = ((Number) readField(params, "privateFlags")).intValue();
        writeField(params, "privateFlags", Integer.valueOf(privateFlags | 2097168));
        params.setTitle("DisplayBackGestureHandler "
                + context.getDisplay().getDisplayId());
        invokeCompatible(params, "setFitInsetsTypes", Integer.valueOf(0));
        invokeCompatible(params, "setTrustedOverlay");
        return params;
    }
}
