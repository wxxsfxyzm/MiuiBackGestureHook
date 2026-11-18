package dev.codex.miuibackgesturehook.hooks.javahome;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Method;
import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.hooks.systemserver.SystemServerHookRuntime;
import io.github.libxposed.api.XposedInterface;

/**
 * Package-parse redirect that makes PackageManager read an Android 16 Java MiuiHome in place of
 * the Android 17 system image.
 *
 * <p>Android 17 reverts a downgraded MiuiHome data update to the newer system image at boot.
 * When the user selects the Java launcher, every parse of {@code /product/priv-app/MiuiHome}
 * (the boot scan and system rescans such as uninstalling updates) reads the Java MiuiHome APK
 * embedded in this module as an extracted native library, so components, permissions and the
 * system version all come from the Java launcher. Selecting the native launcher restores the
 * stock scan on the next boot.
 *
 * <p>The parse runs inside PackageManager's boot path: every failure falls back to the original
 * parse and nothing propagates into PackageManager.
 */
public abstract class JavaHomeLauncherScanRuntime extends SystemServerHookRuntime {
    protected static final String SERVER_JAVA_HOME_SCAN_HOOK =
            "server_javahome_launcher_parse_path";
    protected static final String SERVER_JAVA_HOME_SCAN_FLAGS_HOOK =
            "server_javahome_launcher_scan_flags";
    private static final String SCAN_TAG = "JavaHomeScan";
    private static final String PACKAGE_PARSER =
            "com.android.internal.pm.parsing.PackageParser2";
    private static final String PACKAGE_MANAGER_SERVICE =
            "com.android.server.pm.PackageManagerService";
    private static final File SYSTEM_MIUI_HOME_DIR = new File("/product/priv-app/MiuiHome");
    private static final String EMBEDDED_LAUNCHER_LIBRARY = "libjavahome_launcher.so";
    // Earlier builds placed a hand-copied replacement here; PackageManager may still record it.
    private static final File LEGACY_LAUNCHER_APK = new File("/data/app/MiuiHome-javahome.apkx");
    // ParsingPackageUtils.PARSE_MUST_BE_APK: rejects a code path without an .apk suffix.
    private static final int PARSE_MUST_BE_APK = 1;
    protected static final String SERVER_JAVA_HOME_ALLOWLIST_HOOK =
            "server_javahome_launcher_allowlist";
    private static final String APP_ID_PERMISSION_POLICY =
            "com.android.server.permission.access.permission.AppIdPermissionPolicy";
    private volatile boolean javaHomeLauncherRedirected;
    private volatile Method privilegedAllowlistStateMethod;

    @Override
    protected void installSystemServerHooks(ClassLoader classLoader) {
        super.installSystemServerHooks(classLoader);
        ClassLoader serverClassLoader = findSystemServerClassLoader(classLoader);
        if (serverClassLoader != null) {
            hookJavaHomeLauncherScanPath(serverClassLoader);
            hookJavaHomeLauncherScanFlags(serverClassLoader);
            hookJavaHomeLauncherAllowlist(serverClassLoader);
        }
    }

    /**
     * User-approved for {@code com.miui.home} only: the redirected Java MiuiHome requests
     * privileged permissions missing from the Android 17 allowlist. PackageManager grants an
     * unlisted privileged permission to an updated system app, which is how this launcher ran
     * before; as the redirected system image it would instead record a violation and abort
     * system_server at systemReady. Keep it on the updated-system-app rule while the redirect is
     * active. Listed permissions keep their allowlist decision.
     */
    protected void hookJavaHomeLauncherAllowlist(ClassLoader serverClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return;
        }
        try {
            Class<?> policyClass = Class.forName(APP_ID_PERMISSION_POLICY, false,
                    serverClassLoader);
            Method check = null;
            Method state = null;
            for (Method method : policyClass.getDeclaredMethods()) {
                if (method.getParameterCount() != 3) {
                    continue;
                }
                if ("checkPrivilegedPermissionAllowlistIfNeeded".equals(method.getName())) {
                    check = method;
                } else if ("getPrivilegedPermissionAllowlistState".equals(method.getName())
                        && method.getParameterTypes()[2] == String.class) {
                    state = method;
                }
            }
            if (check == null || state == null) {
                throw new NoSuchMethodException(APP_ID_PERMISSION_POLICY
                        + " privileged allowlist check");
            }
            check.setAccessible(true);
            state.setAccessible(true);
            privilegedAllowlistStateMethod = state;
            recordHookHandle(hook(check)
                    .setId(SERVER_JAVA_HOME_ALLOWLIST_HOOK)
                    .intercept(this::keepJavaHomeLauncherAllowlistState));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, SCAN_TAG, "MiuiHome allowlist rule unavailable", throwable);
        }
    }

    protected Object keepJavaHomeLauncherAllowlistState(XposedInterface.Chain chain)
            throws Throwable {
        Object packageState = chain.getArg(1);
        Method stateMethod = privilegedAllowlistStateMethod;
        if (packageState == null || stateMethod == null) {
            return chain.proceed();
        }
        String permissionName;
        try {
            if (!MIUI_HOME.equals(invokeAnyMethod(packageState, "getPackageName",
                    new Object[0])) || !isJavaHomeLauncherRedirectActive()) {
                return chain.proceed();
            }
            Object permissionInfo = invokeAnyMethod(chain.getArg(2), "getPermissionInfo",
                    new Object[0]);
            permissionName = ((android.content.pm.PermissionInfo) permissionInfo).name;
            if (stateMethod.invoke(chain.getThisObject(), chain.getArg(0), packageState,
                    permissionName) != null) {
                return chain.proceed();
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, SCAN_TAG, "Kept stock allowlist check for MiuiHome",
                    throwable);
            return chain.proceed();
        }
        moduleLog(Log.INFO, SCAN_TAG, "Applied updated-system-app allowlist rule"
                + ", package=" + MIUI_HOME + ", permission=" + permissionName);
        return Boolean.TRUE;
    }

    private boolean isJavaHomeLauncherRedirectActive() {
        return javaHomeLauncherRedirected
                || (isJavaHomeLauncherSelected() && isUsable(embeddedLauncherApk()));
    }

    protected void hookJavaHomeLauncherScanPath(ClassLoader serverClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return;
        }
        try {
            Class<?> parserClass = Class.forName(PACKAGE_PARSER, false, serverClassLoader);
            Method parsePackage = parserClass.getDeclaredMethod("parsePackage",
                    File.class, int.class, boolean.class);
            parsePackage.setAccessible(true);
            recordHookHandle(hook(parsePackage)
                    .setId(SERVER_JAVA_HOME_SCAN_HOOK)
                    .intercept(this::redirectJavaHomeLauncherScan));
            moduleLog(Log.INFO, SCAN_TAG, "MiuiHome parse redirect installed"
                    + ", javaLauncherSelected=" + isJavaHomeLauncherSelected()
                    + ", replacement=" + embeddedLauncherApk()
                    + ", replacementPresent=" + isUsable(embeddedLauncherApk()));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, SCAN_TAG, "MiuiHome parse redirect unavailable", throwable);
        }
    }

    /**
     * Restoring a system package derives its partition flags from the recorded code path; keep
     * the embedded launcher on the privileged product scan flags of the image it replaces.
     */
    protected void hookJavaHomeLauncherScanFlags(ClassLoader serverClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return;
        }
        try {
            Class<?> serviceClass = Class.forName(PACKAGE_MANAGER_SERVICE, false,
                    serverClassLoader);
            Method scanFlags = serviceClass.getDeclaredMethod("getSystemPackageScanFlags",
                    File.class);
            scanFlags.setAccessible(true);
            recordHookHandle(hook(scanFlags)
                    .setId(SERVER_JAVA_HOME_SCAN_FLAGS_HOOK)
                    .intercept(this::mapJavaHomeLauncherScanFlags));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, SCAN_TAG, "MiuiHome scan-flag mapping unavailable", throwable);
        }
    }

    protected Object redirectJavaHomeLauncherScan(XposedInterface.Chain chain) throws Throwable {
        Object scanFile = chain.getArg(0);
        if (!(scanFile instanceof File) || !isRedirectSource((File) scanFile)
                || !isJavaHomeLauncherSelected()) {
            return chain.proceed();
        }
        File replacement = embeddedLauncherApk();
        if (!isUsable(replacement)) {
            moduleLog(Log.WARN, SCAN_TAG, "Kept stock MiuiHome parse; embedded launcher missing"
                    + ", replacement=" + replacement);
            return chain.proceed();
        }
        Object parsed;
        try {
            Object[] redirected = chain.getArgs().toArray();
            redirected[0] = replacement;
            redirected[1] = ((Integer) redirected[1]) & ~PARSE_MUST_BE_APK;
            parsed = chain.proceed(redirected);
            Object packageName = invokeAnyMethod(parsed, "getPackageName", new Object[0]);
            if (!MIUI_HOME.equals(packageName)) {
                throw new IllegalStateException("Replacement package is " + packageName);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, SCAN_TAG, "Kept stock MiuiHome parse; replacement rejected"
                    + ", replacement=" + replacement, throwable);
            return chain.proceed();
        }
        javaHomeLauncherRedirected = true;
        moduleLog(Log.INFO, SCAN_TAG, "Redirected MiuiHome system parse"
                + ", from=" + scanFile
                + ", to=" + replacement);
        return parsed;
    }

    protected Object mapJavaHomeLauncherScanFlags(XposedInterface.Chain chain) throws Throwable {
        Object codePath = chain.getArg(0);
        if (!(codePath instanceof File) || SYSTEM_MIUI_HOME_DIR.equals(codePath)
                || !isRedirectSource((File) codePath)) {
            return chain.proceed();
        }
        Object[] mapped = chain.getArgs().toArray();
        mapped[0] = SYSTEM_MIUI_HOME_DIR;
        Object flags = chain.proceed(mapped);
        moduleLog(Log.INFO, SCAN_TAG, "Mapped embedded MiuiHome scan flags to the product image"
                + ", codePath=" + codePath + ", flags=0x" + Integer.toHexString(
                flags instanceof Integer ? (Integer) flags : 0));
        return flags;
    }

    /** The system image, the legacy copy, or an embedded copy from any module generation. */
    private static boolean isRedirectSource(File file) {
        return SYSTEM_MIUI_HOME_DIR.equals(file) || LEGACY_LAUNCHER_APK.equals(file)
                || EMBEDDED_LAUNCHER_LIBRARY.equals(file.getName());
    }

    protected File embeddedLauncherApk() {
        try {
            ApplicationInfo module = getModuleApplicationInfo();
            if (module != null && module.nativeLibraryDir != null) {
                return new File(module.nativeLibraryDir, EMBEDDED_LAUNCHER_LIBRARY);
            }
        } catch (Throwable ignored) {
            // Fall through: no embedded launcher can be located.
        }
        return null;
    }

    /** Unreadable configuration keeps the native launcher. */
    protected boolean isJavaHomeLauncherSelected() {
        try {
            return modulePreferences().getBoolean(
                    PredictiveBackPreferences.KEY_JAVA_HOME_LAUNCHER,
                    PredictiveBackPreferences.DEFAULT_JAVA_HOME_LAUNCHER);
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, SCAN_TAG, "Launcher implementation preference unavailable"
                    + ", policy=native", throwable);
            return false;
        }
    }

    protected static boolean isUsable(File file) {
        try {
            return file != null && file.isFile() && file.canRead() && file.length() > 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
