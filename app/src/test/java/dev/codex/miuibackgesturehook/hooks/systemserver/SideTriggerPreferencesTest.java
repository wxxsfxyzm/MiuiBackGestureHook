package dev.codex.miuibackgesturehook.hooks.systemserver;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.codex.miuibackgesturehook.PredictiveBackPreferences.*;

public class SideTriggerPreferencesTest {
    private static final class Host implements SideTriggerPreferences.Host {
        final Map<String, Object> values = new HashMap<>();
        SharedPreferences.OnSharedPreferenceChangeListener listener;
        boolean unavailable;
        int changes;
        int failures;
        int registrations;
        Runnable beforeSnapshot = () -> { };
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAll":
                            Map<String, Object> snapshot = new HashMap<>(values);
                            beforeSnapshot.run();
                            if (unavailable) throw new IllegalStateException("unavailable");
                            return snapshot;
                        case "registerOnSharedPreferenceChangeListener":
                            listener = (SharedPreferences.OnSharedPreferenceChangeListener) args[0];
                            registrations++;
                            return null;
                        case "unregisterOnSharedPreferenceChangeListener":
                            listener = null;
                            return null;
                        default: throw new AssertionError(method);
                    }
                });
        @Override public SharedPreferences preferences() { return preferences; }
        @Override public void changed() { changes++; }
        @Override public void failed(Throwable failure) { failures++; }
        void update(String key) { listener.onSharedPreferenceChanged(preferences, key); }
    }

    @Test public void changesUseOneSnapshotAndClearingRestoresNativeConfiguration() {
        Host host = new Host();
        host.values.put(KEY_GESTURE_TRIGGER_HEIGHT_PERCENT, 50);
        host.values.put(KEY_GESTURE_TRIGGER_POSITION_PERCENT, 100);
        SideTriggerPreferences session = new SideTriggerPreferences(host);
        session.connect();
        session.connect();
        assertEquals(1, host.registrations);
        assertEquals(50, session.configuration().height);
        assertEquals(100, session.configuration().position);
        host.values.clear();
        host.update(null);
        assertEquals(100, session.configuration().height);
        assertEquals(0, session.configuration().position);
        assertEquals(2, host.changes);
    }

    @Test public void failedInitialReadUnregistersAndCanReconnect() {
        Host host = new Host();
        host.unavailable = true;
        SideTriggerPreferences session = new SideTriggerPreferences(host);
        session.connect();
        assertFalse(session.connected());
        assertNull(host.listener);
        assertEquals(1, host.failures);
        host.unavailable = false;
        host.values.put(KEY_GESTURE_TRIGGER_HEIGHT_PERCENT, 50);
        session.connect();
        assertTrue(session.connected());
        assertEquals(50, session.configuration().height);
    }

    @Test public void malformedOrUnreadableConfigurationDoesNotKeepOldExclusions() {
        Host host = new Host();
        host.values.put(KEY_GESTURE_TRIGGER_HEIGHT_PERCENT, 50);
        SideTriggerPreferences session = new SideTriggerPreferences(host);
        session.connect();
        host.values.put(KEY_GESTURE_TRIGGER_POSITION_PERCENT, "bad");
        host.update(KEY_GESTURE_TRIGGER_POSITION_PERCENT);
        assertSame(SideTriggerPreferences.Configuration.NATIVE, session.configuration());
        host.values.remove(KEY_GESTURE_TRIGGER_POSITION_PERCENT);
        host.update(KEY_GESTURE_TRIGGER_POSITION_PERCENT);
        assertEquals(50, session.configuration().height);
        host.unavailable = true;
        host.update(KEY_GESTURE_TRIGGER_HEIGHT_PERCENT);
        assertSame(SideTriggerPreferences.Configuration.NATIVE, session.configuration());
        assertFalse(session.connected());
        assertNull(host.listener);
    }

    @Test public void callbackAlreadyReadingAtHotReloadCannotPublishAfterClose() throws Exception {
        Host host = new Host();
        SideTriggerPreferences session = new SideTriggerPreferences(host);
        session.connect();
        SharedPreferences.OnSharedPreferenceChangeListener stale = host.listener;
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        host.beforeSnapshot = () -> {
            reading.countDown();
            try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
        Thread callback = new Thread(() -> {
            try { stale.onSharedPreferenceChanged(host.preferences, null); }
            catch (Throwable e) { failure.set(e); }
        });
        callback.start();
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            session.close();
            assertNull(host.listener);
        } finally {
            resume.countDown();
            callback.join(5000);
        }
        assertFalse(callback.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertSame(SideTriggerPreferences.Configuration.NATIVE, session.configuration());
        assertEquals(1, host.changes);
        assertEquals(0, host.failures);
        session.connect();
        assertEquals(1, host.registrations);
    }

    @Test public void slowerOldSnapshotCannotOverwriteANewerPreferenceChange() throws Exception {
        Host host = new Host();
        host.values.put(KEY_GESTURE_TRIGGER_HEIGHT_PERCENT, 50);
        SideTriggerPreferences session = new SideTriggerPreferences(host);
        session.connect();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        host.beforeSnapshot = () -> {
            if (!first.getAndSet(false)) return;
            reading.countDown();
            try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
        Thread callback = new Thread(() -> host.update(KEY_GESTURE_TRIGGER_HEIGHT_PERCENT));
        callback.start();
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            host.values.put(KEY_GESTURE_TRIGGER_HEIGHT_PERCENT, 75);
            host.update(KEY_GESTURE_TRIGGER_HEIGHT_PERCENT);
            assertEquals(75, session.configuration().height);
        } finally {
            resume.countDown();
            callback.join(5000);
        }
        assertFalse(callback.isAlive());
        assertEquals(75, session.configuration().height);
        assertEquals(2, host.changes);
        assertEquals(0, host.failures);
    }
}
