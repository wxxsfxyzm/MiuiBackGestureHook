package dev.codex.miuibackgesturehook.hooks.systemserver;

import android.content.SharedPreferences;

import java.util.Map;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;

/** Remote reads run outside WMS's global lock; the calculation only reads a cached snapshot. */
final class SideTriggerPreferences implements AutoCloseable {
    interface Host {
        SharedPreferences preferences();
        void changed();
        void failed(Throwable failure);
    }

    static final class Configuration {
        static final Configuration NATIVE = new Configuration(
                PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT,
                PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT);
        final int height;
        final int position;

        Configuration(int height, int position) {
            this.height = height;
            this.position = position;
        }

        static Configuration read(Map<String, ?> values) {
            Object height = values.get(PredictiveBackPreferences.KEY_GESTURE_TRIGGER_HEIGHT_PERCENT);
            Object position = values.get(PredictiveBackPreferences.KEY_GESTURE_TRIGGER_POSITION_PERCENT);
            if (height == null) height = PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT;
            if (position == null) position = PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT;
            if (!(height instanceof Integer) || !(position instanceof Integer)) return NATIVE;
            int h = (Integer) height;
            int p = (Integer) position;
            if (h < PredictiveBackPreferences.MIN_GESTURE_TRIGGER_HEIGHT_PERCENT
                    || h > PredictiveBackPreferences.MAX_GESTURE_TRIGGER_HEIGHT_PERCENT
                    || p < PredictiveBackPreferences.MIN_GESTURE_TRIGGER_POSITION_PERCENT
                    || p > PredictiveBackPreferences.MAX_GESTURE_TRIGGER_POSITION_PERCENT) return NATIVE;
            return new Configuration(h, p);
        }
    }

    private final Object lock = new Object();
    private final Host host;
    private volatile SharedPreferences preferences;
    private volatile Configuration configuration = Configuration.NATIVE;
    private volatile boolean closed;
    private long readGeneration;
    private boolean failureLogged;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (source, key) -> {
        if (key == null
                || PredictiveBackPreferences.KEY_GESTURE_TRIGGER_HEIGHT_PERCENT.equals(key)
                || PredictiveBackPreferences.KEY_GESTURE_TRIGGER_POSITION_PERCENT.equals(key)) {
            read(source);
        }
    };

    SideTriggerPreferences(Host host) {
        this.host = host;
    }

    Configuration configuration() { return configuration; }
    boolean connected() { return preferences != null; }

    void connect() {
        if (closed || connected()) return;
        SharedPreferences candidate = null;
        try {
            candidate = host.preferences();
            synchronized (lock) {
                if (closed || connected()) return;
                preferences = candidate;
            }
            candidate.registerOnSharedPreferenceChangeListener(listener);
            if (closed || preferences != candidate) {
                unregister(candidate);
                return;
            }
            read(candidate);
        } catch (Throwable failure) {
            fail(candidate, 0, failure);
        }
    }

    private void read(SharedPreferences source) {
        long generation;
        synchronized (lock) {
            if (closed || source == null || preferences != source) return;
            generation = ++readGeneration;
        }
        try {
            Configuration next = Configuration.read(source.getAll());
            synchronized (lock) {
                if (closed || preferences != source || readGeneration != generation) return;
                configuration = next;
                failureLogged = false;
            }
            host.changed();
        } catch (Throwable failure) {
            fail(source, generation, failure);
        }
    }

    private void fail(SharedPreferences source, long generation, Throwable failure) {
        boolean report;
        synchronized (lock) {
            if (closed || preferences != source
                    || (generation != 0 && generation != readGeneration)) return;
            configuration = Configuration.NATIVE;
            preferences = null;
            report = !failureLogged;
            failureLogged = true;
        }
        unregister(source);
        if (report) host.failed(failure);
        host.changed();
    }

    @Override public void close() {
        SharedPreferences previous;
        synchronized (lock) {
            closed = true;
            previous = preferences;
            preferences = null;
            configuration = Configuration.NATIVE;
        }
        unregister(previous);
    }

    private void unregister(SharedPreferences source) {
        if (source == null) return;
        try { source.unregisterOnSharedPreferenceChangeListener(listener); }
        catch (Throwable ignored) { }
    }
}
