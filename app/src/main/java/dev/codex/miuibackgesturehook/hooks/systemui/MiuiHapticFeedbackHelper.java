package dev.codex.miuibackgesturehook.hooks.systemui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Reproduces MiuiHome's two-stage back gesture haptics inside SystemUI through the
 * framework {@code miui.util.HapticFeedbackUtil} (boot classpath on MIUI/HyperOS).
 *
 * <p>Mirrors {@code HapticFeedbackCompatLinear}/{@code HapticFeedbackCompatV2}:
 * <ul>
 *   <li>ready-back (arrow fade-in complete): ext effect 162 on linear-motor devices,
 *       effect 0 on {@code sys.haptic.version=2.0} devices; arms a 140ms blocker on
 *       linear devices only.</li>
 *   <li>hand-up (committed release): ext effect 163 / 0, skipped on linear devices
 *       within 140ms of ready-back.</li>
 * </ul>
 * The optional two-stage mode requires {@code isSupportExtHapticFeedback(162) && (163)}
 * exactly like Xiaomi's {@code isSupportEffectGestureBackLinear()}; the single AOSP
 * replacement checks only its actual default effect (162 or V2 effect 0).
 */
final class MiuiHapticFeedbackHelper {

    private static final int EFFECT_GESTURE_READY_BACK_LINEAR = 162;
    private static final int EFFECT_GESTURE_BACK_HAND_UP_LINEAR = 163;
    private static final int EFFECT_GESTURE_BACK_V2 = 0;
    // Stock V2 plays effect 0 for both stages; enhanced mode uses a distinct hand-up.
    private static final int EFFECT_GESTURE_BACK_HAND_UP_V2_ENHANCED = 1;
    private static final long LINEAR_HAND_UP_BLOCK_MS = 140L;

    interface Logger {
        void log(int priority, String message, Throwable throwable);
    }

    private final Logger logger;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Object hapticFeedbackUtil;
    private final Method performExtHapticFeedback;
    private final boolean defaultBackSupported;
    private final boolean supported;
    private final boolean hapticV2;
    private final Vibrator vibrator;
    private final boolean scaledPrimitivesSupported;
    private final boolean thudPrimitiveSupported;
    private volatile boolean enhancedMode;
    private volatile int strengthPercent = 100;
    private volatile boolean customFeedback;
    private volatile int customPreset =
            PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS_PRESET;
    private volatile long lastReadyBackUptime;

    @SuppressLint("BlockedPrivateApi")
    MiuiHapticFeedbackHelper(Context context, Logger logger) {
        this.logger = logger;
        Object util = null;
        Method perform = null;
        boolean resolvedDefaultSupported = false;
        boolean resolvedSupported = false;
        boolean resolvedV2 = false;
        Vibrator resolvedVibrator = null;
        boolean resolvedScaledPrimitives = false;
        boolean resolvedThudPrimitive = false;
        try {
            String version = SystemProperties.get("sys.haptic.version", "1.0");
            resolvedV2 = "2.0".equals(version);

            Class<?> utilClass = Class.forName("miui.util.HapticFeedbackUtil");
            boolean linearMotor = Boolean.TRUE.equals(utilClass
                    .getMethod("isSupportLinearMotorVibrate").invoke(null));
            if (linearMotor) {
                util = utilClass.getConstructor(Context.class, boolean.class)
                        .newInstance(context, Boolean.TRUE);
                Method support = utilClass.getMethod(
                        "isSupportExtHapticFeedback", int.class);
                perform = utilClass.getMethod("performExtHapticFeedback", int.class);
                resolvedDefaultSupported = Boolean.TRUE.equals(support.invoke(util,
                        Integer.valueOf(resolvedV2
                                ? EFFECT_GESTURE_BACK_V2
                                : EFFECT_GESTURE_READY_BACK_LINEAR)));
                resolvedSupported = resolvedDefaultSupported
                        && Boolean.TRUE.equals(support.invoke(util,
                        Integer.valueOf(EFFECT_GESTURE_BACK_HAND_UP_LINEAR)));
            }
        } catch (Throwable throwable) {
            logger.log(Log.WARN, "MIUI haptic feedback unavailable"
                    + ", policy=keepNativePanelHaptics", throwable);
            util = null;
            perform = null;
            resolvedDefaultSupported = false;
            resolvedSupported = false;
        }
        try {
            VibratorManager vibratorManager = context.getSystemService(
                    VibratorManager.class);
            if (vibratorManager != null) {
                resolvedVibrator = vibratorManager.getDefaultVibrator();
                boolean[] primitiveSupport = resolvedVibrator.arePrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_THUD);
                resolvedScaledPrimitives = primitiveSupport.length == 3
                        && primitiveSupport[0] && primitiveSupport[1];
                resolvedThudPrimitive = primitiveSupport.length == 3
                        && primitiveSupport[2];
            }
        } catch (Throwable throwable) {
            logger.log(Log.WARN, "Scaled haptic primitives unavailable; "
                    + "keeping native HyperOS strength", throwable);
            resolvedVibrator = null;
            resolvedScaledPrimitives = false;
            resolvedThudPrimitive = false;
        }
        this.hapticFeedbackUtil = util;
        this.performExtHapticFeedback = perform;
        this.defaultBackSupported = resolvedDefaultSupported
                && util != null && perform != null;
        this.supported = resolvedSupported && util != null && perform != null;
        this.hapticV2 = resolvedV2;
        this.vibrator = resolvedVibrator;
        this.scaledPrimitivesSupported = resolvedScaledPrimitives;
        this.thudPrimitiveSupported = resolvedThudPrimitive;
        if (this.supported) {
            logger.log(Log.INFO, "MIUI two-stage back haptics ready"
                    + ", hapticV2=" + resolvedV2, null);
        } else if (this.defaultBackSupported) {
            logger.log(Log.INFO, "MIUI default AOSP replacement haptic ready"
                    + ", effect=" + (resolvedV2
                    ? EFFECT_GESTURE_BACK_V2 : EFFECT_GESTURE_READY_BACK_LINEAR)
                    + ", hapticV2=" + resolvedV2, null);
        }
    }

    boolean isSupported() {
        return supported;
    }

    boolean isDefaultBackSupported() {
        return defaultBackSupported;
    }

    boolean isCustomSupported() {
        return vibrator != null && vibrator.hasVibrator();
    }

    boolean isHapticV2() {
        return hapticV2;
    }

    /**
     * Enhanced release feedback: every committed release plays hand-up, V2 devices use
     * effect 1 instead of 0, and the 140ms blocker also applies to V2. Disabled keeps
     * the stock behavior.
     */
    void setEnhancedMode(boolean enabled) {
        enhancedMode = enabled;
    }

    boolean isEnhancedMode() {
        return enhancedMode;
    }

    void setStrengthPercent(int strengthPercent) {
        this.strengthPercent = Math.max(20, Math.min(200, strengthPercent));
    }

    void setCustomFeedback(boolean enabled, int preset) {
        customFeedback = enabled;
        customPreset = PredictiveBackPreferences.isValidCustomHapticsPreset(preset)
                ? preset : PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS_PRESET;
    }

    /** Plays only HyperOS's default back effect, without its second hand-up stage. */
    boolean performDefaultBack() {
        if (customFeedback) {
            return performCustomFeedback(false);
        }
        if (!defaultBackSupported) {
            return false;
        }
        int effectId = hapticV2 ? EFFECT_GESTURE_BACK_V2
                : EFFECT_GESTURE_READY_BACK_LINEAR;
        try {
            // The original View feedback call is synchronous. Keep this single replacement
            // synchronous as well so the threshold does not acquire an executor delay and a
            // runtime failure can still fall back to the native AOSP effect.
            performExtHapticFeedback.invoke(hapticFeedbackUtil,
                    Integer.valueOf(effectId));
            return true;
        } catch (Throwable throwable) {
            logger.log(Log.WARN, "Failed to play MIUI default AOSP replacement effect "
                    + effectId, throwable);
            return false;
        }
    }

    void close() {
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    /** Arrow fade-in completed; mirrors performGestureReadyBack(). */
    void performReadyBack() {
        if (customFeedback) {
            performCustomFeedback(false);
            lastReadyBackUptime = SystemClock.uptimeMillis();
            return;
        }
        if (!supported) {
            return;
        }
        if (!hapticV2 || enhancedMode) {
            lastReadyBackUptime = SystemClock.uptimeMillis();
        }
        int effect = hapticV2 ? EFFECT_GESTURE_BACK_V2
                : EFFECT_GESTURE_READY_BACK_LINEAR;
        play(effect);
    }

    /** Committed release; mirrors performGestureBackHandUp() with the 140ms blocker. */
    void performHandUp() {
        if (customFeedback) {
            if (SystemClock.uptimeMillis() - lastReadyBackUptime
                    >= LINEAR_HAND_UP_BLOCK_MS) {
                performCustomFeedback(true);
            }
            return;
        }
        if (!supported) {
            return;
        }
        if ((!hapticV2 || enhancedMode) && SystemClock.uptimeMillis() - lastReadyBackUptime
                < LINEAR_HAND_UP_BLOCK_MS) {
            return;
        }
        int effect;
        if (hapticV2) {
            effect = enhancedMode
                    ? EFFECT_GESTURE_BACK_HAND_UP_V2_ENHANCED : EFFECT_GESTURE_BACK_V2;
        } else {
            effect = EFFECT_GESTURE_BACK_HAND_UP_LINEAR;
        }
        play(effect);
    }

    /**
     * Framework-only custom effects. They do not depend on Xiaomi effect IDs, and therefore keep
     * their character and strength controls independent from HyperOS gesture haptics.
     */
    private boolean performCustomFeedback(boolean release) {
        if (!isCustomSupported()) {
            return false;
        }
        float baseScale = Math.max(0.1f, Math.min(1.0f, strengthPercent / 160.0f));
        try {
            VibrationEffect effect;
            if (!scaledPrimitivesSupported
                    && customPreset != PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DEEP) {
                int amplitude = Math.max(24,
                        Math.min(255, Math.round(strengthPercent * 1.275f)));
                effect = VibrationEffect.createOneShot(release ? 22L : 14L, amplitude);
                vibrator.vibrate(effect);
                return true;
            }
            switch (customPreset) {
                case PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_SOFT:
                    effect = VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK,
                                    Math.min(0.7f, baseScale * 0.65f))
                            .compose();
                    break;
                case PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DEEP:
                    if (thudPrimitiveSupported) {
                        effect = VibrationEffect.startComposition()
                                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD,
                                        baseScale)
                                .compose();
                    } else {
                        int amplitude = Math.max(32,
                                Math.min(255, Math.round(strengthPercent * 1.275f)));
                        effect = VibrationEffect.createOneShot(
                                release ? 42L : 34L, amplitude);
                    }
                    break;
                case PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DOUBLE:
                    effect = VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK,
                                    baseScale)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK,
                                    Math.min(1.0f, baseScale * 0.8f), 42)
                            .compose();
                    break;
                case PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_CRISP:
                default:
                    effect = VibrationEffect.startComposition()
                            .addPrimitive(release
                                            ? VibrationEffect.Composition.PRIMITIVE_CLICK
                                            : VibrationEffect.Composition.PRIMITIVE_TICK,
                                    baseScale)
                            .compose();
                    break;
            }
            vibrator.vibrate(effect);
            return true;
        } catch (Throwable throwable) {
            logger.log(Log.WARN, "Failed to play custom back feedback"
                    + ", preset=" + customPreset
                    + ", strengthPercent=" + strengthPercent, throwable);
            return false;
        }
    }

    private void play(int effectId) {
        executor.execute(() -> {
            try {
                performExtHapticFeedback.invoke(hapticFeedbackUtil,
                        Integer.valueOf(effectId));
            } catch (Throwable throwable) {
                logger.log(Log.WARN, "Failed to play MIUI ext haptic effect "
                        + effectId, throwable);
            }
        });
    }
}
