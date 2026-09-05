package dev.codex.miuibackgesturehook.util;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.MustBeInvokedByOverriders;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * An abstract of Hooker
 * Provide a runtime environment of XposedInterface.
 */
public abstract class HookerBridge implements Hooker, XposedInterface {
    protected String packageName = null;
    protected ClassLoader classLoader = null;
    protected XposedInterface xposed = null;
    protected ApplicationInfo applicationInfo = null;

    @Override
    @MustBeInvokedByOverriders
    public void onAttached(String packageName, ClassLoader classLoader, XposedInterface xposed, ApplicationInfo applicationInfo) {
        this.packageName = packageName;
        this.classLoader = classLoader;
        this.xposed = xposed;
        this.applicationInfo = applicationInfo;
    }

    @Override
    public final boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        Map<String, Object> instanceStateMap = new HashMap<>();
        if (!onHotReloading(instanceStateMap)) return false;

        instanceStateMap.put("DEFAULT_CLASS_LOADER", classLoader);
        param.setSavedInstanceState(instanceStateMap);
        return true;
    }

    @Override
    public final void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        // First, let's restore states
        var savedState = param.getSavedInstanceState();

        if (!(savedState instanceof Map<?, ?>)) {
            return;
        }

        // Second, call the hooker to reinitialize hooks
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) savedState;
        classLoader = (ClassLoader) state.get("DEFAULT_CLASS_LOADER");
        onHotReloaded(state);
    }

    protected boolean onHotReloading(Map<String, Object> savedInstanceState) {
        return false;
    }

    protected void onHotReloaded(Map<String, Object> savedInstanceState) {}

    @Override
    public int getApiVersion() {
        return xposed.getApiVersion();
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return xposed.getFrameworkName();
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return xposed.getFrameworkVersion();
    }

    @Override
    public long getFrameworkVersionCode() {
        return xposed.getFrameworkVersionCode();
    }

    @Override
    public long getFrameworkProperties() {
        return xposed.getFrameworkProperties();
    }

    @NonNull
    @Override
    public HookBuilder hook(@NonNull Executable origin) {
        return xposed.hook(origin);
    }

    @NonNull
    @Override
    public HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        return xposed.hookClassInitializer(origin);
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        return xposed.deoptimize(executable);
    }

    @NonNull
    @Override
    public Invoker<?, Method> getInvoker(@NonNull Method method) {
        return xposed.getInvoker(method);
    }

    @NonNull
    @Override
    public <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        return xposed.getInvoker(constructor);
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg) {
        xposed.log(priority, tag, msg);
    }

    @NonNull
    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return xposed.getModuleApplicationInfo();
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr) {
        xposed.log(priority, tag, msg, tr);
    }

    @NonNull
    @Override
    public SharedPreferences getRemotePreferences(@NonNull String group) {
        return xposed.getRemotePreferences(group);
    }

    @NonNull
    @Override
    public String[] listRemoteFiles() {
        return xposed.listRemoteFiles();
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException {
        return xposed.openRemoteFile(name);
    }
}
