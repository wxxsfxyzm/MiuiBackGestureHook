package dev.codex.miuibackgesturehook.hooks.systemui;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.Map;

import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

@Hooker.XposedHooker(
        name = "SystemUiNavigationPolicyHook",
        targets = "com.android.systemui",
        order = 20)
public final class SystemUiNavigationPolicyHook extends HookerBridge {
    private static final String TAG = "SystemUiNavigationPolicyHook";
    private final SystemUiImpl implementation = new SystemUiImpl();
    static final String NAVIGATION_BAR =
            "com.android.systemui.navigationbar.views.NavigationBar";
    static final String NAV_BAR_HELPER =
            "com.android.systemui.navigationbar.NavBarHelper";
    static final String NAVIGATION_BAR_CONTROLLER_IMPL =
            "com.android.systemui.navigationbar.NavigationBarControllerImpl";
    static final String NAV_BAR_STATE_UPDATER =
            "com.android.systemui.navigationbar.NavBarHelper$NavbarTaskbarStateUpdater";
    static final String STATUS_BAR_APPEARANCE_LAMBDA =
            "com.android.systemui.statusbar.data.repository."
                    + "StatusBarModePerDisplayRepositoryImpl$statusBarAppearance$1";
    static final String SYSTEM_UI_DEPENDENCY = "com.android.systemui.Dependency";
    static final String MIUI_CONFIGS = "com.miui.utils.configs.MiuiConfigs";

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
            implementation.hookNavigationBarTransientAutoHide(classLoader);
            implementation.hookNavigationBarTransientAppearance(classLoader);
            implementation.hookStatusBarTransientAppearance(classLoader);
            implementation.hookNavigationBarGestureInsets(classLoader);
            implementation.hookPlatformBackAnimationStatusBarReset(classLoader);
            implementation.hookNavigationBarControllerCreate(classLoader);
            implementation.hookNavigationBarControllerRemove(classLoader);
            implementation.hookNavigationBarControllerMode(classLoader);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install SystemUI navigation policy", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return implementation.prepareNavigationPolicyHotReload(savedInstanceState);
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        implementation.releaseForHotReload();
    }

    @Override
    protected void onHotReloaded(Map<String, Object> savedInstanceState) {
        implementation.restoreNavigationPolicyHotReload(savedInstanceState);
    }
}
