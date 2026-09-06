package dev.codex.miuibackgesturehook.hooks.systemui;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.Map;

import dev.codex.miuibackgesturehook.util.Hooker;
import dev.codex.miuibackgesturehook.util.HookerBridge;
import io.github.libxposed.api.XposedInterface;

@Hooker.XposedHooker(
        name = "SystemUiContextualSearchHook",
        targets = "com.android.systemui",
        order = 40)
public final class SystemUiContextualSearchHook extends HookerBridge {
    private static final String TAG = "SystemUiContextualSearchHook";
    private final SystemUiImpl implementation = new SystemUiImpl();

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
            implementation.hookContextualSearchNavigationBar(classLoader, true, true);
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG,
                    "Failed to install SystemUI contextual search", throwable);
        }
    }

    @Override
    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return implementation.prepareContextualSearchHotReload(savedInstanceState);
    }

    @Override
    public void onBeforeSubmitHotReloading() {
        implementation.releaseForHotReload();
    }

    @Override
    protected void onHotReloaded(Map<String, Object> savedInstanceState) {
        implementation.restoreContextualSearchHotReload(savedInstanceState);
    }
}
