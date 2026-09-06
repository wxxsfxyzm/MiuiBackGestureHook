package dev.codex.miuibackgesturehook.util;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.MustBeInvokedByOverriders;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public interface Hooker {
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface XposedHooker {
        String name();
        String[] targets();
        int order() default 0;
    }

    void onPackageLoad();

    /** Returns whether this hooker applies to the process and platform it was attached to. */
    default boolean shouldInstallHooker() {
        return true;
    }

    /**
     * <p>Gets notified when the hooker is about to be reloaded.</p>
     *
     * This callback is called when hot reloading is triggered through the service, or by app updating if autoHotReload is set to true in module.prop.
     * App-update hot reloading still proceeds only if this callback returns true.
     *
     * <p>Note: <b>Only PREPARE data for new instance in this callback</b>,
     * don't free resource in this callback,
     * Please release in {@code onBeforeSubmitHotReloading},
     * because other hookers might deny HotReloading.</p>
     * @see XposedModuleInterface#onHotReloading(XposedModuleInterface.HotReloadingParam)
     * @see Hooker#onBeforeSubmitHotReloading()
     * @param param Information about the hot reloading event
     * @return {@code true} to allow hot reloading to proceed, {@code false} to cancel hot reloading
     */
    default boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        return false;
    }

    /**
     * <p>Gets notified when the hooker will be reloaded.</p>
     *
     * This is the LAST callback of old instance,
     * Framework will load new module after this callback complete
     *
     * <p><b>You MUST relase all of your resource in this callback.</b></p>
     */
    default void onBeforeSubmitHotReloading() {

    }

    /** Restore non-hook resources after this hook has been installed again. */
    default void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
    }

    @MustBeInvokedByOverriders
    void onAttached(String packageName, ClassLoader classLoader, XposedInterface xposed, ApplicationInfo applicationInfo);
}
