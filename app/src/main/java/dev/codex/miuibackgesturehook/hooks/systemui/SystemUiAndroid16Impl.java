package dev.codex.miuibackgesturehook.hooks.systemui;

import static dev.codex.miuibackgesturehook.util.ReflectionHelper.*;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;

final class SystemUiAndroid16Impl extends SystemUiPlatformImpl {
    @Override
    String name() {
        return "android16";
    }

    @Override
    Class<?> backAnimationParameterClass(ClassLoader classLoader) throws Exception {
        return Class.forName("com.android.wm.shell.back.BackAnimationController$BackAnimationImpl",
                false, classLoader);
    }

    @Override
    boolean isNavigationOverlayExcluded(Object edgeBackGestureHandler,
                                        int x, int y) throws Exception {
        Object bounds = readField(edgeBackGestureHandler, "mNavBarOverlayExcludedBounds");
        if (!(bounds instanceof Rect)) {
            throw new IllegalStateException("Unexpected navigation overlay bounds: " + bounds);
        }
        return ((Rect) bounds).contains(x, y);
    }

    @Override
    Object findNativeEdgeBackPlugin(Object edgeBackGestureHandler) throws Exception {
        return readField(edgeBackGestureHandler, "mEdgeBackPlugin");
    }

    @Override
    Object ensureNativeEdgeBackPlugin(Object edgeBackGestureHandler,
                                      Context context) throws Exception {
        Object existing = findNativeEdgeBackPlugin(edgeBackGestureHandler);
        if (existing != null) {
            return existing;
        }
        Object factory = readField(edgeBackGestureHandler, "mBackPanelControllerFactory");
        Handler handler = (Handler) readField(
                readField(edgeBackGestureHandler, "mUiThreadContext"), "handler");
        Object plugin = invokeCompatible(factory, "create", context, handler);
        invokeCompatible(plugin, "init");
        invokeCompatible(edgeBackGestureHandler, "setEdgeBackPlugin", plugin);
        return plugin;
    }

    @Override
    void prepareNativeBackPanel(Object edgeBackGestureHandler,
                                Object plugin) throws Exception {
        invokeCompatible(plugin, "updateConfiguration$1");
        invokeCompatible(plugin, "updateRestingArrowDimens");
    }

    @Override
    void updateDisplaySize(Object edgeBackGestureHandler,
                           Object plugin) throws Exception {
        invokeCompatible(edgeBackGestureHandler, "updateDisplaySize$1");
    }

    @Override
    boolean canCreateNavBarOrTaskBar(Object controller, int displayId) throws Exception {
        Object result = invokeCompatible(controller,
                "shouldCreateNavBarAndTaskBar", Integer.valueOf(displayId));
        return Boolean.TRUE.equals(result);
    }

    @Override
    boolean requiresStableGestureInsetsOverrideTypes() {
        // HyperOS Android 16 also rejects relayout when a provider gains a new
        // override type after the NavigationBar window has been added. Declare
        // the IME type on the first LayoutParams even if gesture eligibility is
        // not ready yet; the provider sizes remain gated by the normal state.
        return true;
    }
}
