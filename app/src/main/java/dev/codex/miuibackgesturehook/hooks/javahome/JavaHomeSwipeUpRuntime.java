package dev.codex.miuibackgesturehook.hooks.javahome;

import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.util.Log;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

/**
 * Android 17 bottom-swipe bridge for an installed Android 16 Java MiuiHome.
 *
 * <p>Android 17 input dispatch withholds a bottom hot-zone DOWN from ordinary windows and
 * delivers it only to gesture monitors, so the Java launcher's {@code GestureStubHome}
 * window never sees a swipe. The Flutter launcher registers a {@code swipe-up} monitor
 * instead. The Java launcher lacks {@code MONITOR_INPUT}, so SystemUI creates the monitor
 * for the exact launcher UID over its existing {@code ISystemUiProxy} Binder and the launcher
 * feeds the stream to its own {@code NavStubView} recognizer.
 */
public abstract class JavaHomeSwipeUpRuntime extends JavaHomeLauncherSwitchRuntime {
    protected static final String JAVA_HOME_HOOK_PREFIX = "javahome_";
    protected static final String SYSTEMUI_JAVA_HOME_MONITOR_HOOK =
            "systemui_javahome_swipe_up_monitor";
    private static final String JAVA_HOME_TAG = "JavaHomeSwipeUp";
    private static final String SYSTEM_UI_PROXY_DESCRIPTOR =
            "com.android.systemui.shared.recents.ISystemUiProxy";
    private static final String LAUNCHER_PROXY_SERVICE =
            "com.android.systemui.LauncherProxyService";
    private static final String NAV_STUB_VIEW = "com.miui.home.recents.NavStubView";
    private static final String MIUI_INPUT_MANAGER = "miui.hardware.input.MiuiInputManager";
    private static final String WINDOW_VISIBILITY_OBSERVER =
            "com.miui.home.recents.WindowVisibilityObserver";
    // XiaoAi reports its overlay to MiuiHome's enableState provider with this type.
    private static final String XIAOAI_WINDOW_TYPE = "typefrom_ai_voice_assist";
    private static final String SYSTEM_UI_PROXY_WRAPPER =
            "com.miui.home.recents.SystemUiProxyWrapper";
    private static final String QUICK_STEP_CONTRACT =
            "com.android.systemui.shared.recents.system.QuickStepContract";
    private static final String SWIPE_UP_MONITOR_NAME = "swipe-up";
    // Inside the AIDL user range and far above every generated ISystemUiProxy code.
    private static final int TRANSACTION_MONITOR_SWIPE_UP = 0x00FA5701;

    // Launcher-process state. Monitor callbacks and NavStubView both run on the main Looper.
    private final Object javaHomeLock = new Object();
    private HandlerThread javaHomeRequestThread;
    private Handler javaHomeRequestHandler;
    private int javaHomeGeneration;
    private IBinder javaHomeSystemUiBinder;
    private InputMonitor javaHomeMonitor;
    private SwipeUpReceiver javaHomeReceiver;
    private volatile boolean javaHomeMonitorLive;
    private WeakReference<View> javaHomeNavStub = new WeakReference<>(null);
    private View javaHomeActiveStreamView;
    // Android 17 delivers a bottom hot-zone DOWN only to gesture monitors. When NavStubView does
    // not take it (MiuiHome hides it for the shade, keyguard and similar states), ask the input
    // dispatcher to release that exact stream to the windows below, as the native launcher does.
    private Method javaHomeRedirectMethod;
    private Object javaHomeInputManager;
    private int javaHomeBridgeDispatchDepth;
    private Field javaHomeEmptyTouchableRegionField;
    protected boolean javaHomeBridgeInstalled;

    /** Whether this is the Android 17 launcher process running the Java (dex) MiuiHome. */
    protected static boolean isJavaHomeOnAndroid17(ApplicationInfo applicationInfo) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
                && applicationInfo != null
                && (applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
    }

    // ---------------------------------------------------------------- SystemUI side

    @Override
    protected void installSystemUiHooks(ClassLoader classLoader) {
        super.installSystemUiHooks(classLoader);
        hookJavaHomeSwipeUpMonitorService(classLoader);
        ensureMiuiHomeFlavorReceiver();
    }

    private BroadcastReceiver miuiHomeFlavorReceiver;
    private Context miuiHomeFlavorReceiverContext;

    /**
     * SystemUI outlives launcher replacement (Android 17 reverts a downgraded Java MiuiHome to
     * the native system image at boot), so the cached launcher contract follows the package.
     */
    protected void ensureMiuiHomeFlavorReceiver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            if (miuiHomeFlavorReceiver != null) {
                return;
            }
            Context context = ActivityThread.currentApplication();
            if (context == null) {
                main.postDelayed(this::ensureMiuiHomeFlavorReceiver, 1000);
                return;
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    invalidateMiuiHomeImplementation();
                    boolean nativeHome = usesNativeMiuiHome();
                    reconcileContextualSearchOwnership();
                    moduleLog(Log.INFO, JAVA_HOME_TAG, "MiuiHome package changed"
                            + ", action=" + intent.getAction()
                            + ", nativeContract=" + nativeHome);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addDataScheme("package");
            filter.addDataSchemeSpecificPart(MIUI_HOME, PatternMatcher.PATTERN_LITERAL);
            try {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                miuiHomeFlavorReceiver = receiver;
                miuiHomeFlavorReceiverContext = context;
                invalidateMiuiHomeImplementation();
                moduleLog(Log.INFO, JAVA_HOME_TAG, "MiuiHome flavor tracking active"
                        + ", nativeContract=" + usesNativeMiuiHome());
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, JAVA_HOME_TAG, "MiuiHome flavor tracking unavailable",
                        throwable);
            }
        });
    }

    protected void releaseMiuiHomeFlavorReceiver() {
        runOnMainSync(() -> {
            BroadcastReceiver receiver = miuiHomeFlavorReceiver;
            Context context = miuiHomeFlavorReceiverContext;
            miuiHomeFlavorReceiver = null;
            miuiHomeFlavorReceiverContext = null;
            if (receiver != null && context != null) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                    // Already gone with its context.
                }
            }
        });
    }

    protected void hookJavaHomeSwipeUpMonitorService(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return;
        }
        try {
            Class<?> proxyInterface = Class.forName(SYSTEM_UI_PROXY_DESCRIPTOR, false,
                    classLoader);
            Class<?> serviceClass = null;
            for (int index = 1; index <= 24 && serviceClass == null; index++) {
                try {
                    Class<?> candidate = Class.forName(LAUNCHER_PROXY_SERVICE + "$" + index,
                            false, classLoader);
                    if (Binder.class.isAssignableFrom(candidate)
                            && proxyInterface.isAssignableFrom(candidate)) {
                        serviceClass = candidate;
                    }
                } catch (ClassNotFoundException ignored) {
                    // Anonymous class indices are sparse; keep scanning.
                }
            }
            if (serviceClass == null) {
                throw new ClassNotFoundException(LAUNCHER_PROXY_SERVICE
                        + " ISystemUiProxy implementation");
            }
            Method onTransact = serviceClass.getDeclaredMethod("onTransact",
                    int.class, Parcel.class, Parcel.class, int.class);
            onTransact.setAccessible(true);
            recordHookHandle(hook(onTransact)
                    .setId(SYSTEMUI_JAVA_HOME_MONITOR_HOOK)
                    .intercept(this::serveJavaHomeSwipeUpMonitor));
            moduleLog(Log.INFO, JAVA_HOME_TAG, "SystemUI swipe-up monitor service installed"
                    + ", binder=" + serviceClass.getName());
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, JAVA_HOME_TAG,
                    "SystemUI swipe-up monitor service unavailable", throwable);
        }
    }

    protected Object serveJavaHomeSwipeUpMonitor(XposedInterface.Chain chain) throws Throwable {
        Object code = chain.getArg(0);
        if (!(code instanceof Integer)
                || (Integer) code != TRANSACTION_MONITOR_SWIPE_UP) {
            return chain.proceed();
        }
        Parcel data = (Parcel) chain.getArg(1);
        Parcel reply = (Parcel) chain.getArg(2);
        if (data == null || reply == null) {
            return Boolean.FALSE;
        }
        int callingUid = Binder.getCallingUid();
        InputMonitor monitor = null;
        try {
            data.enforceInterface(SYSTEM_UI_PROXY_DESCRIPTOR);
            int displayId = data.readInt();
            if (displayId != Display.DEFAULT_DISPLAY) {
                throw new SecurityException("Unsupported display " + displayId);
            }
            Context context = ActivityThread.currentApplication();
            if (context == null) {
                throw new IllegalStateException("SystemUI application unavailable");
            }
            ApplicationInfo launcher = context.getPackageManager()
                    .getApplicationInfo(MIUI_HOME, 0);
            if (launcher.uid != callingUid || !isJavaHomeOnAndroid17(launcher)) {
                throw new SecurityException("Rejected swipe-up monitor for uid "
                        + callingUid + ", launcherUid=" + launcher.uid
                        + ", hasCode=" + ((launcher.flags & ApplicationInfo.FLAG_HAS_CODE) != 0));
            }
            InputManager inputManager = context.getSystemService(InputManager.class);
            if (inputManager == null) {
                throw new IllegalStateException("InputManager unavailable");
            }
            Object created = invokeAnyMethod(inputManager, "monitorGestureInput",
                    new Object[]{SWIPE_UP_MONITOR_NAME, Integer.valueOf(displayId)});
            if (!(created instanceof InputMonitor)) {
                throw new IllegalStateException("monitorGestureInput returned "
                        + shortObject(created));
            }
            monitor = (InputMonitor) created;
            reply.writeNoException();
            reply.writeInt(1);
            // Return-value semantics close SystemUI's copy of the channel after parceling.
            ((Parcelable) (Object) monitor).writeToParcel(reply,
                    Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            monitor = null;
            moduleLog(Log.INFO, JAVA_HOME_TAG, "Created swipe-up monitor for launcher uid "
                    + callingUid);
        } catch (Throwable throwable) {
            if (monitor != null) {
                monitor.dispose();
            }
            moduleLog(Log.WARN, JAVA_HOME_TAG, "Swipe-up monitor request denied"
                    + ", callingUid=" + callingUid, throwable);
            reply.setDataPosition(0);
            reply.setDataSize(0);
            reply.writeNoException();
            reply.writeInt(0);
        }
        return Boolean.TRUE;
    }

    // ---------------------------------------------------------------- launcher side

    protected void installJavaHomeSwipeUpBridge(ClassLoader classLoader, Object savedState) {
        try {
            Class<?> navStubClass = Class.forName(NAV_STUB_VIEW, false, classLoader);
            Class<?> wrapperClass = Class.forName(SYSTEM_UI_PROXY_WRAPPER, false, classLoader);
            javaHomeEmptyTouchableRegionField = navStubClass.getDeclaredField(
                    "mUseEmptyTouchableRegion");
            javaHomeEmptyTouchableRegionField.setAccessible(true);
            Class<?> inputManagerClass = Class.forName(MIUI_INPUT_MANAGER, false, classLoader);
            javaHomeInputManager = inputManagerClass.getMethod("getInstance").invoke(null);
            javaHomeRedirectMethod = inputManagerClass.getMethod("requestRedirect",
                    MotionEvent.class);
            String proxyKey = (String) readStaticField(
                    Class.forName(QUICK_STEP_CONTRACT, false, classLoader),
                    "KEY_EXTRA_SYSUI_PROXY");

            Method setProxy = wrapperClass.getDeclaredMethod("setProxyByBundle", Bundle.class);
            setProxy.setAccessible(true);
            recordHookHandle(hook(setProxy)
                    .setId(JAVA_HOME_HOOK_PREFIX + "system_ui_proxy")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object bundle = chain.getArg(0);
                        IBinder binder = bundle instanceof Bundle
                                ? ((Bundle) bundle).getBinder(proxyKey) : null;
                        requestJavaHomeSwipeUpMonitor(binder, "setProxyByBundle");
                        return result;
                    }));

            // SystemUI ignores a back gesture on idle Home unless it knows XiaoAi covers Home;
            // only the native launcher published that state on Android 17.
            Class<?> observerClass = Class.forName(WINDOW_VISIBILITY_OBSERVER, false,
                    classLoader);
            Method visibilityChanged = observerClass.getDeclaredMethod(
                    "onWindowVisibilityChanged", String.class, boolean.class);
            visibilityChanged.setAccessible(true);
            recordHookHandle(hook(visibilityChanged)
                    .setId(JAVA_HOME_HOOK_PREFIX + "xiaoai_visibility")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (XIAOAI_WINDOW_TYPE.equals(chain.getArg(0))) {
                            publishJavaHomeXiaoAiState(
                                    Boolean.TRUE.equals(chain.getArg(1)), "provider");
                        }
                        return result;
                    }));
            try {
                Object observer = observerClass.getMethod("getInstance").invoke(null);
                Object showing = invokeAnyMethod(observer, "getShowingWindowTypeList",
                        new Object[0]);
                publishJavaHomeXiaoAiState(showing instanceof java.util.List
                        && ((java.util.List<?>) showing).contains(XIAOAI_WINDOW_TYPE),
                        "install");
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, JAVA_HOME_TAG, "XiaoAi state baseline unavailable",
                        throwable);
            }

            Method windowParam = navStubClass.getDeclaredMethod("getWindowParam", int.class);
            windowParam.setAccessible(true);
            recordHookHandle(hook(windowParam)
                    .setId(JAVA_HOME_HOOK_PREFIX + "nav_stub_window_param")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof View) {
                            javaHomeNavStub = new WeakReference<>(
                                    (View) chain.getThisObject());
                        }
                        return result;
                    }));

            Method onTouchEvent = navStubClass.getDeclaredMethod("onTouchEvent",
                    MotionEvent.class);
            onTouchEvent.setAccessible(true);
            recordHookHandle(hook(onTouchEvent)
                    .setId(JAVA_HOME_HOOK_PREFIX + "nav_stub_touch")
                    .intercept(chain -> {
                        // The monitor is the single source while it is live; a stray window
                        // delivery would otherwise feed the recognizer the same stream twice.
                        if (javaHomeMonitorLive && javaHomeBridgeDispatchDepth == 0) {
                            return Boolean.FALSE;
                        }
                        return chain.proceed();
                    }));

            javaHomeBridgeInstalled = true;
            moduleLog(Log.INFO, JAVA_HOME_TAG, "Java launcher swipe-up bridge installed");
            if (savedState instanceof Object[]) {
                Object[] state = (Object[]) savedState;
                if (state.length >= 2 && state[1] instanceof View) {
                    javaHomeNavStub = new WeakReference<>((View) state[1]);
                }
                if (state.length >= 2 && state[0] instanceof IBinder) {
                    requestJavaHomeSwipeUpMonitor((IBinder) state[0], "hot reload");
                }
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, JAVA_HOME_TAG,
                    "Java launcher swipe-up bridge unavailable", throwable);
        }
    }

    /** Called on the old generation before hot reload; returns the handoff state. */
    protected Object detachJavaHomeSwipeUpBridge(String reason) {
        IBinder binder;
        View navStub = javaHomeNavStub.get();
        synchronized (javaHomeLock) {
            javaHomeGeneration++;
            binder = javaHomeSystemUiBinder;
            javaHomeSystemUiBinder = null;
            if (javaHomeRequestThread != null) {
                javaHomeRequestThread.quitSafely();
                javaHomeRequestThread = null;
                javaHomeRequestHandler = null;
            }
        }
        runOnMainSync(() -> disposeJavaHomeMonitorOnMain(reason));
        return new Object[]{binder, navStub};
    }

    private void requestJavaHomeSwipeUpMonitor(IBinder binder, String reason) {
        if (binder == null) {
            return;
        }
        Handler handler;
        int generation;
        synchronized (javaHomeLock) {
            generation = ++javaHomeGeneration;
            javaHomeSystemUiBinder = binder;
            if (javaHomeRequestThread == null) {
                javaHomeRequestThread = new HandlerThread("JavaHomeSwipeUp");
                javaHomeRequestThread.start();
                javaHomeRequestHandler = new Handler(javaHomeRequestThread.getLooper());
            }
            handler = javaHomeRequestHandler;
        }
        handler.post(() -> {
            InputMonitor monitor = null;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SYSTEM_UI_PROXY_DESCRIPTOR);
                data.writeInt(Display.DEFAULT_DISPLAY);
                binder.transact(TRANSACTION_MONITOR_SWIPE_UP, data, reply, 0);
                reply.readException();
                if (reply.readInt() != 0) {
                    @SuppressWarnings("unchecked")
                    Parcelable.Creator<InputMonitor> creator =
                            (Parcelable.Creator<InputMonitor>) readStaticField(
                                    InputMonitor.class, "CREATOR");
                    monitor = creator.createFromParcel(reply);
                }
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, JAVA_HOME_TAG, "Swipe-up monitor request failed"
                        + ", reason=" + reason, throwable);
            } finally {
                data.recycle();
                reply.recycle();
            }
            InputMonitor received = monitor;
            new Handler(Looper.getMainLooper()).post(
                    () -> attachJavaHomeMonitorOnMain(received, generation, reason));
        });
    }

    private void attachJavaHomeMonitorOnMain(InputMonitor monitor, int generation,
                                             String reason) {
        synchronized (javaHomeLock) {
            if (generation != javaHomeGeneration) {
                if (monitor != null) {
                    monitor.dispose();
                }
                return;
            }
        }
        disposeJavaHomeMonitorOnMain("replaced");
        if (monitor == null) {
            moduleLog(Log.WARN, JAVA_HOME_TAG, "SystemUI granted no swipe-up monitor"
                    + "; GestureStubHome window path remains, reason=" + reason);
            return;
        }
        try {
            javaHomeReceiver = new SwipeUpReceiver(monitor.getInputChannel(),
                    Looper.getMainLooper(), this);
        } catch (Throwable throwable) {
            monitor.dispose();
            moduleLog(Log.WARN, JAVA_HOME_TAG, "Swipe-up receiver creation failed",
                    throwable);
            return;
        }
        javaHomeMonitor = monitor;
        javaHomeMonitorLive = true;
        moduleLog(Log.INFO, JAVA_HOME_TAG, "Swipe-up monitor attached, reason=" + reason);
    }

    private void disposeJavaHomeMonitorOnMain(String reason) {
        boolean hadMonitor = javaHomeMonitor != null;
        javaHomeMonitorLive = false;
        cancelJavaHomeActiveStream();
        if (javaHomeReceiver != null) {
            javaHomeReceiver.dispose();
            javaHomeReceiver = null;
        }
        if (javaHomeMonitor != null) {
            javaHomeMonitor.dispose();
            javaHomeMonitor = null;
        }
        if (hadMonitor) {
            moduleLog(Log.INFO, JAVA_HOME_TAG, "Swipe-up monitor detached, reason=" + reason);
        }
    }

    private void onJavaHomeMonitorEvent(InputEvent inputEvent) {
        if (!(inputEvent instanceof MotionEvent)) {
            return;
        }
        MotionEvent event = (MotionEvent) inputEvent;
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelJavaHomeActiveStream();
            View navStub = javaHomeNavStub.get();
            if (acceptsJavaHomeDown(navStub, event)) {
                javaHomeActiveStreamView = navStub;
            } else if (isBottomHotZoneDown(navStub, event)) {
                requestJavaHomeRedirect(event);
            }
        }
        View target = javaHomeActiveStreamView;
        if (target == null) {
            return;
        }
        dispatchToJavaHomeNavStub(target, event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            javaHomeActiveStreamView = null;
        }
    }

    private boolean acceptsJavaHomeDown(View navStub, MotionEvent event) {
        if (navStub == null || !navStub.isAttachedToWindow()
                || navStub.getVisibility() != View.VISIBLE
                || navStub.getWindowVisibility() != View.VISIBLE) {
            return false;
        }
        ViewGroup.LayoutParams params = navStub.getLayoutParams();
        if (params instanceof WindowManager.LayoutParams
                && (((WindowManager.LayoutParams) params).flags
                & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
            return false;
        }
        try {
            if (javaHomeEmptyTouchableRegionField.getBoolean(navStub)) {
                return false;
            }
        } catch (Throwable throwable) {
            return false;
        }
        int[] location = new int[2];
        navStub.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x < location[0] + navStub.getWidth()
                && y >= location[1] && y < location[1] + navStub.getHeight();
    }

    private void dispatchToJavaHomeNavStub(View target, MotionEvent event) {
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        MotionEvent copy = MotionEvent.obtain(event);
        // Window-relative x/y as the stub window would report; raw coordinates stay intact.
        copy.offsetLocation(-location[0], -location[1]);
        javaHomeBridgeDispatchDepth++;
        try {
            target.dispatchTouchEvent(copy);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, JAVA_HOME_TAG, "NavStubView rejected bridged event",
                    throwable);
        } finally {
            javaHomeBridgeDispatchDepth--;
            copy.recycle();
        }
    }

    /**
     * The bottom band where Android 17 withholds DOWN from windows. The dispatcher's band is a
     * little taller than the NavStubView window, so allow a margin above the window height.
     */
    private boolean isBottomHotZoneDown(View navStub, MotionEvent event) {
        if (navStub == null) {
            return false;
        }
        try {
            int displayHeight = navStub.getResources().getDisplayMetrics().heightPixels;
            ViewGroup.LayoutParams params = navStub.getLayoutParams();
            int windowHeight = params != null && params.height > 0 ? params.height : 0;
            int band = Math.max(windowHeight * 2, displayHeight / 20);
            return event.getRawY() >= displayHeight - band;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void publishJavaHomeXiaoAiState(boolean visible, String reason) {
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            return;
        }
        Intent stateIntent = new Intent(MODULE_MIUI_OVERVIEW_STATE_CHANGE);
        stateIntent.putExtra(EXTRA_LAUNCHER_XIAOAI_VISIBLE, visible);
        sendAuthenticatedMiuiHomeState(context, stateIntent);
        moduleLog(Log.INFO, JAVA_HOME_TAG, "Published Java launcher XiaoAi state"
                + ", visible=" + visible + ", reason=" + reason);
    }

    private void requestJavaHomeRedirect(MotionEvent down) {
        Method method = javaHomeRedirectMethod;
        Object inputManager = javaHomeInputManager;
        if (method == null || inputManager == null) {
            return;
        }
        try {
            method.invoke(inputManager, down);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, JAVA_HOME_TAG, "Hot-zone redirect request failed", throwable);
        }
    }

    private void cancelJavaHomeActiveStream() {
        View target = javaHomeActiveStreamView;
        javaHomeActiveStreamView = null;
        if (target == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        javaHomeBridgeDispatchDepth++;
        try {
            target.dispatchTouchEvent(cancel);
        } catch (Throwable ignored) {
            // The stub is already detached; nothing remains to cancel.
        } finally {
            javaHomeBridgeDispatchDepth--;
            cancel.recycle();
        }
    }

    private static void runOnMainSync(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
            return;
        }
        Object done = new Object();
        boolean[] finished = new boolean[1];
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                runnable.run();
            } finally {
                synchronized (done) {
                    finished[0] = true;
                    done.notifyAll();
                }
            }
        });
        synchronized (done) {
            long deadline = android.os.SystemClock.uptimeMillis() + 1000;
            while (!finished[0]) {
                long remaining = deadline - android.os.SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    return;
                }
                try {
                    done.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static final class SwipeUpReceiver extends InputEventReceiver {
        private final JavaHomeSwipeUpRuntime runtime;

        SwipeUpReceiver(InputChannel channel, Looper looper, JavaHomeSwipeUpRuntime runtime) {
            super(channel, looper);
            this.runtime = runtime;
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                runtime.onJavaHomeMonitorEvent(event);
            } finally {
                finishInputEvent(event, true);
            }
        }
    }
}
