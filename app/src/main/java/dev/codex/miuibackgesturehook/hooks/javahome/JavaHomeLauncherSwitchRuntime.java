package dev.codex.miuibackgesturehook.hooks.javahome;

import android.app.ActivityThread;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import dev.codex.miuibackgesturehook.PredictiveBackPreferences;

/**
 * User-approved Android 17 launcher hot switch, applied in system_server.
 *
 * <p>PackageManager registers the system image of MiuiHome only at boot, so a switch without a
 * reboot uses the update mechanism: when the selected launcher matches the registered system
 * image the data update is uninstalled, otherwise the selected launcher is installed as an update
 * (the product image for the native launcher, the embedded APK for the Java launcher).
 */
public abstract class JavaHomeLauncherSwitchRuntime extends JavaHomeLauncherScanRuntime {
    private static final String SWITCH_TAG = "JavaHomeSwitch";
    private static final String ACTION_SWITCH_STATUS =
            "dev.codex.miuibackgesturehook.action.JAVA_HOME_SWITCH_STATUS";
    // PackageManager.MATCH_FACTORY_ONLY (system API): the registered system image.
    private static final int MATCH_FACTORY_ONLY = 0x00200000;
    private static final File NATIVE_LAUNCHER_APK =
            new File("/product/priv-app/MiuiHome/MiuiHome.apk");

    private final Object javaHomeSwitchLock = new Object();
    private HandlerThread javaHomeSwitchThread;
    private Handler javaHomeSwitchHandler;
    private SharedPreferences javaHomeSwitchPreferences;
    private SharedPreferences.OnSharedPreferenceChangeListener javaHomeSwitchListener;
    private BroadcastReceiver javaHomeSwitchStatusReceiver;
    // Switch thread only: one PackageManager operation at a time. Preference callbacks can
    // repeat; a change arriving while one is pending is reconciled after it finishes.
    private boolean javaHomeSwitchPending;
    private Context javaHomeSwitchStatusContext;

    @Override
    protected void installSystemServerHooks(ClassLoader classLoader) {
        super.installSystemServerHooks(classLoader);
        ensureJavaHomeLauncherSwitch();
    }

    /** Listens for the launcher selection; the switch runs on its own system-identity thread. */
    protected void ensureJavaHomeLauncherSwitch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return;
        }
        synchronized (javaHomeSwitchLock) {
            if (javaHomeSwitchListener != null) {
                return;
            }
            try {
                HandlerThread thread = new HandlerThread("JavaHomeSwitch");
                thread.start();
                Handler handler = new Handler(thread.getLooper());
                SharedPreferences preferences = modulePreferences();
                SharedPreferences.OnSharedPreferenceChangeListener listener =
                        (changed, key) -> {
                            if (PredictiveBackPreferences.KEY_JAVA_HOME_LAUNCHER.equals(key)) {
                                handler.post(this::reconcileJavaHomeLauncher);
                            }
                        };
                preferences.registerOnSharedPreferenceChangeListener(listener);
                javaHomeSwitchThread = thread;
                javaHomeSwitchHandler = handler;
                javaHomeSwitchPreferences = preferences;
                javaHomeSwitchListener = listener;
            } catch (Throwable throwable) {
                moduleLog(Log.WARN, SWITCH_TAG, "Launcher hot switch unavailable", throwable);
            }
        }
    }

    /** Called by the retiring generation before hot reload. */
    protected void releaseJavaHomeLauncherSwitch() {
        synchronized (javaHomeSwitchLock) {
            if (javaHomeSwitchPreferences != null && javaHomeSwitchListener != null) {
                try {
                    javaHomeSwitchPreferences.unregisterOnSharedPreferenceChangeListener(
                            javaHomeSwitchListener);
                } catch (Throwable ignored) {
                    // The remote preferences are already gone.
                }
            }
            if (javaHomeSwitchStatusReceiver != null && javaHomeSwitchStatusContext != null) {
                try {
                    javaHomeSwitchStatusContext.unregisterReceiver(javaHomeSwitchStatusReceiver);
                } catch (Throwable ignored) {
                    // Already unregistered with its context.
                }
            }
            if (javaHomeSwitchThread != null) {
                javaHomeSwitchThread.quitSafely();
            }
            javaHomeSwitchThread = null;
            javaHomeSwitchHandler = null;
            javaHomeSwitchPreferences = null;
            javaHomeSwitchListener = null;
            javaHomeSwitchStatusReceiver = null;
            javaHomeSwitchStatusContext = null;
        }
    }

    private void reconcileJavaHomeLauncher() {
        if (javaHomeSwitchPending) {
            return;
        }
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            moduleLog(Log.WARN, SWITCH_TAG, "Launcher switch deferred; system context unavailable");
            return;
        }
        boolean wantJava = isJavaHomeLauncherSelected();
        File embedded = embeddedLauncherApk();
        if (wantJava && !isUsable(embedded)) {
            moduleLog(Log.WARN, SWITCH_TAG, "Java launcher selected but not embedded"
                    + ", replacement=" + embedded);
            return;
        }
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo current = packageManager.getApplicationInfo(MIUI_HOME, 0);
            ApplicationInfo factory = packageManager.getApplicationInfo(MIUI_HOME,
                    MATCH_FACTORY_ONLY);
            boolean currentJava = (current.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
            boolean factoryJava = (factory.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
            boolean updated = (current.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (currentJava == wantJava) {
                moduleLog(Log.INFO, SWITCH_TAG, "Launcher already matches the selection"
                        + ", java=" + wantJava);
                return;
            }
            IntentSender status = switchStatusSender(context);
            javaHomeSwitchPending = true;
            PackageInstaller installer = packageManager.getPackageInstaller();
            if (factoryJava == wantJava && updated) {
                installer.uninstall(new VersionedPackage(MIUI_HOME,
                        PackageManager.VERSION_CODE_HIGHEST), status);
                moduleLog(Log.INFO, SWITCH_TAG, "Requested system launcher restore"
                        + ", java=" + wantJava);
                return;
            }
            File apk = wantJava ? embedded : NATIVE_LAUNCHER_APK;
            installLauncherUpdate(installer, apk, wantJava, status);
            moduleLog(Log.INFO, SWITCH_TAG, "Requested launcher update install"
                    + ", java=" + wantJava + ", apk=" + apk);
        } catch (Throwable throwable) {
            javaHomeSwitchPending = false;
            moduleLog(Log.WARN, SWITCH_TAG, "Launcher switch failed, java=" + wantJava,
                    throwable);
        }
    }

    private void installLauncherUpdate(PackageInstaller installer, File apk, boolean downgrade,
                                       IntentSender status) throws Exception {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(MIUI_HOME);
        if (downgrade) {
            // The Java launcher is older than the Android 17 image it replaces.
            invokeAnyMethod(params, "setRequestDowngrade", new Object[]{Boolean.TRUE});
        }
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }
            session.commit(status);
        } catch (Throwable throwable) {
            installer.abandonSession(sessionId);
            throw throwable;
        }
    }

    private IntentSender switchStatusSender(Context context) {
        synchronized (javaHomeSwitchLock) {
            if (javaHomeSwitchStatusReceiver == null && javaHomeSwitchHandler != null) {
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE);
                        // Runs on the switch thread; settle any selection change made meanwhile.
                        javaHomeSwitchPending = false;
                        reconcileJavaHomeLauncher();
                        moduleLog(code == PackageInstaller.STATUS_SUCCESS ? Log.INFO : Log.WARN,
                                SWITCH_TAG, "Launcher switch finished, status=" + code
                                        + ", message=" + intent.getStringExtra(
                                        PackageInstaller.EXTRA_STATUS_MESSAGE));
                    }
                };
                context.registerReceiver(receiver, new IntentFilter(ACTION_SWITCH_STATUS),
                        null, javaHomeSwitchHandler, Context.RECEIVER_NOT_EXPORTED);
                javaHomeSwitchStatusReceiver = receiver;
                javaHomeSwitchStatusContext = context;
            }
        }
        Intent intent = new Intent(ACTION_SWITCH_STATUS).setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE)
                .getIntentSender();
    }
}
