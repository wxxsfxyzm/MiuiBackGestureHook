package dev.codex.miuibackgesturehook;

import android.app.ActivityThread;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;

import dev.codex.miuibackgesturehook.util.generated.HookRegistry;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class MiuiBackGestureHook extends XposedModule {
    private final Map<Integer, dev.codex.miuibackgesturehook.util.Hooker> registeredHooks = new HashMap<>();
    private static final String TAG = "MiuiBackGestureHook";
    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        installFor("system", param.getClassLoader(), null);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        installFor(param.getPackageName(), param.getDefaultClassLoader(), param.getApplicationInfo());
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        var realSavedInstance = new HashMap<>();

        for (var entry : registeredHooks.entrySet()) {
            boolean ret = entry.getValue().onHotReloading(new HotReloadingParam() {
                @Nullable
                @Override
                public Bundle getExtras() {
                    return param.getExtras();
                }

                @Override
                public void setSavedInstanceState(@Nullable Object outState) {
                    realSavedInstance.put(entry.getKey(), outState);
                }
            });

            if (!ret)
                return false;
        }

        // all hookers allow hotReload, save instance and notify the last callback

        for (var hooker : registeredHooks.values()) {
            hooker.onBeforeSubmitHotReloading();
        }

        param.setSavedInstanceState(realSavedInstance);
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);

        // The instance recreated, reinitialize everything
        List<HookRegistry.Entry> matchingHooks = new ArrayList<>();
        for (HookRegistry.Entry entry : HookRegistry.entries()) {
            if (entry.targets().contains(param.getProcessName())) matchingHooks.add(entry);
        }
        if (matchingHooks.isEmpty()) return;

        var savedState = param.getSavedInstanceState();

        if (!(savedState instanceof Map<?, ?>)) {
            return;
        }

        // Second, call the hooker to reinitialize hooks
        @SuppressWarnings("unchecked")
        Map<Integer, Object> state = (Map<Integer, Object>) savedState;

        for (HookRegistry.Entry entry : matchingHooks) {
            try {
                // First, Let's create hooker instance
                dev.codex.miuibackgesturehook.util.Hooker hook = HookRegistry.getHooker(entry.id());

                if (!hook.shouldInstallHooker()) {
                    log(Log.INFO, TAG, "Skipped " + entry.name()
                            + " for " + param.getProcessName());
                    continue;
                }

                Object hookState = state.get(entry.id());
                if (!(hookState instanceof Map<?, ?> hookStateMap)) {
                    throw new IllegalStateException("Illegal hookState! "+hookState);
                }
                Object savedClassLoader = hookStateMap.get("DEFAULT_CLASS_LOADER");

                // Next, notify hooker for classLoader and currentPackageName
                hook.onAttached(param.getProcessName(), (ClassLoader) savedClassLoader, this, ActivityThread.currentApplication().getApplicationInfo());

                // Call hooker's onHotReloaded func for hotReload support
                hook.onHotReloaded(new HotReloadedParam() {
                    @Nullable
                    @Override
                    public Bundle getExtras() {
                        return param.getExtras();
                    }

                    @Nullable
                    @Override
                    public Object getSavedInstanceState() {
                        return state.get(entry.id());
                    }

                    @NonNull
                    @Override
                    public List<HookHandle> getOldHookHandles() {
                        // emptyList, because we already unhooked
                        return Collections.emptyList();
                    }

                    @Override
                    public boolean isSystemServer() {
                        return param.isSystemServer();
                    }

                    @NonNull
                    @Override
                    public String getProcessName() {
                        return param.getProcessName();
                    }
                });

                // Next, Reinstall the hook group before restoring its non-hook state.
                hook.onPackageLoad();

                // Last, add to registeredHooks for future use
                registeredHooks.put(entry.id(), hook);
                log(Log.INFO, TAG, "Hook #" + entry.name() + " hotReloaded in " + param.getProcessName());
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Hook failed: " + entry.name(), throwable);
            }
        }
    }

    private void installFor(String targetPackage, ClassLoader targetClassLoader, ApplicationInfo applicationInfo) {
        List<HookRegistry.Entry> matchingHooks = new ArrayList<>();
        for (HookRegistry.Entry entry : HookRegistry.entries()) {
            if (entry.targets().contains(targetPackage)) matchingHooks.add(entry);
        }
        if (matchingHooks.isEmpty()) return;

        for (HookRegistry.Entry entry : matchingHooks) {
            try {
                // First, Let's create hooker instance
                dev.codex.miuibackgesturehook.util.Hooker hook = HookRegistry.getHooker(entry.id());

                if (!hook.shouldInstallHooker()) {
                    log(Log.INFO, TAG, "Skipped " + entry.name()
                            + " for " + targetPackage);
                    continue;
                }

                // Next, notify hooker for classLoader and currentPackageName
                hook.onAttached(targetPackage, targetClassLoader, this, applicationInfo);

                // Call hooker's onPackageLoad
                hook.onPackageLoad();

                // Last, add to registeredHooks for future use
                registeredHooks.put(entry.id(), hook);
                log(Log.INFO, TAG, "Hook #" + entry.name() + " loaded in " + targetPackage);
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Hook failed: " + entry.name(), throwable);
            }
        }
    }
}
