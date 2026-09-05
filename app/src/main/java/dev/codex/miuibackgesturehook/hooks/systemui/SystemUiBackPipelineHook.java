package dev.codex.miuibackgesturehook.hooks.systemui;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.Map;

import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

@Hooker.XposedHooker(
        name = "SystemUiBackPipelineHook",
        targets = "com.android.systemui",
        order = 10)
public final class SystemUiBackPipelineHook extends HookerBridge {
    private static final String TAG = "SystemUiBackPipelineHook";
    private final SystemUiImpl implementation = new SystemUiImpl();
    static final String BACK_ANIMATION_CONTROLLER =
            "com.android.wm.shell.back.BackAnimationController";
    static final String CROSS_ACTIVITY_BACK_ANIMATION =
            "com.android.wm.shell.back.CrossActivityBackAnimation";
    static final String DEFAULT_CROSS_ACTIVITY_BACK_ANIMATION =
            "com.android.wm.shell.back.DefaultCrossActivityBackAnimation";
    static final String CROSS_TASK_BACK_ANIMATION =
            "com.android.wm.shell.back.CrossTaskBackAnimation";
    static final String BACK_ANIMATION_BACKGROUND =
            "com.android.wm.shell.back.BackAnimationBackground";
    // The hard-coded color CrossTaskBackAnimation passes to ensureBackground (0x43433A).
    // Used to distinguish its background from cross-activity's task-colored one.
    static final int CROSS_TASK_BACKGROUND_COLOR = 4408122;
    static final String BACK_TRANSITION_HANDLER =
            "com.android.wm.shell.back.BackAnimationController$BackTransitionHandler";
    static final String DEFAULT_TRANSITION_HANDLER =
            "com.android.wm.shell.transition.DefaultTransitionHandler";
    static final String DEFAULT_TRANSITION_IMPL =
            "com.android.wm.shell.common.transition.DefaultTransitionImpl";

    @Override
    public void onAttached(String packageName, ClassLoader classLoader,
                           XposedInterface xposed, ApplicationInfo applicationInfo) {
        super.onAttached(packageName, classLoader, xposed, applicationInfo);
        implementation.onAttached(packageName, classLoader, xposed, applicationInfo);
    }

    @Override
    public void onPackageLoad() {
        implementation.start();
        try {
            implementation.ensureSystemUiPlatformSelected();
            implementation.hookShellBackAnimation(classLoader);
            implementation.hookBackAnimationSendBackEvent(classLoader);
            implementation.hookDefaultTransitionHandler(classLoader);
            implementation.hookDefaultTransitionImplMerge(classLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to install SystemUI back pipeline", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return implementation.isHotReloadSafe();
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        implementation.releaseForHotReload();
    }
}
