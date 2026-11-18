package dev.codex.miuibackgesturehook

import android.content.SharedPreferences

object PredictiveBackPreferences {
    const val GROUP = "predictive_back_opt_in"
    const val KEY_PACKAGES = "packages"
    const val KEY_HYPEROS_INDICATOR = "hyperos_indicator_style"
    const val DEFAULT_HYPEROS_INDICATOR = false
    const val KEY_HYPEROS_INDICATOR_BLUR = "hyperos_indicator_blur"
    const val DEFAULT_HYPEROS_INDICATOR_BLUR = false
    const val KEY_HYPEROS_HAPTICS = "hyperos_indicator_haptics"
    const val DEFAULT_HYPEROS_HAPTICS = false
    const val KEY_GESTURE_HAPTIC_EFFECT = "gesture_haptic_effect"
    const val GESTURE_HAPTIC_AOSP = 0
    const val GESTURE_HAPTIC_HYPEROS = 1
    const val GESTURE_HAPTIC_DISABLED = 2

    @JvmStatic
    fun readGestureHapticEffect(preferences: SharedPreferences): Int {
        if (!preferences.contains(KEY_GESTURE_HAPTIC_EFFECT)) {
            return if (preferences.getBoolean(KEY_HYPEROS_HAPTICS, DEFAULT_HYPEROS_HAPTICS)) {
                GESTURE_HAPTIC_HYPEROS
            } else {
                GESTURE_HAPTIC_AOSP
            }
        }
        return when (val effect = preferences.getInt(KEY_GESTURE_HAPTIC_EFFECT, GESTURE_HAPTIC_AOSP)) {
            GESTURE_HAPTIC_HYPEROS, GESTURE_HAPTIC_DISABLED -> effect
            else -> GESTURE_HAPTIC_AOSP
        }
    }

    /** Only used to migrate the removed AOSP-specific switch once. */
    const val LEGACY_KEY_AOSP_HYPEROS_HAPTICS = "aosp_gesture_hyperos_haptics"

    const val KEY_HYPEROS_HAPTICS_ENHANCED = "hyperos_indicator_haptics_enhanced"
    const val DEFAULT_HYPEROS_HAPTICS_ENHANCED = false
    const val KEY_HYPEROS_SLIDE_ANIMATION = "hyperos_slide_back_animation"
    const val DEFAULT_HYPEROS_SLIDE_ANIMATION = false
    const val KEY_ONEUI_CROSS_TASK_ANIMATION = "oneui_cross_task_animation"
    const val DEFAULT_ONEUI_CROSS_TASK_ANIMATION = false
    // Android 17 only: AOSP-style return-to-Home preview landing on the Home icon.
    const val KEY_RETURN_HOME_ICON_PREVIEW = "return_home_icon_preview"
    const val DEFAULT_RETURN_HOME_ICON_PREVIEW = false
    // Android 17 only: boot-scan the Java MiuiHome in place of the native system image.
    const val KEY_JAVA_HOME_LAUNCHER = "java_home_launcher"
    const val DEFAULT_JAVA_HOME_LAUNCHER = false
    const val KEY_MODULE_LOGGING = "module_logging"
    const val DEFAULT_MODULE_LOGGING = true
    const val KEY_CONTEXTUAL_SEARCH_LONG_PRESS = "contextual_search_long_press"
    const val DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS = false
    const val KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE = "contextual_search_live_translate"
    const val DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE = false
    const val KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX = "google_lens_contextual_searchbox"
    const val DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX = false
    const val KEY_CONTEXTUAL_SEARCH_HAPTICS = "contextual_search_haptics"
    const val DEFAULT_CONTEXTUAL_SEARCH_HAPTICS = false

    /** Skip the reveal-system-bars step for an immersive side-back gesture. */
    const val KEY_FULLSCREEN_DIRECT_BACK = "fullscreen_direct_back"
    const val DEFAULT_FULLSCREEN_DIRECT_BACK = false

    /** Restore the native App/Home status-bar following pipeline on Android 17. */
    const val KEY_RESTORE_STATUS_BAR_FOLLOW = "restore_status_bar_follow"
    const val DEFAULT_RESTORE_STATUS_BAR_FOLLOW = true

    /** The vertical size of both Xiaomi side trigger areas, expressed as a percentage. */
    const val KEY_GESTURE_TRIGGER_HEIGHT_PERCENT = "gesture_trigger_height_percent"
    const val DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT = 100
    const val MIN_GESTURE_TRIGGER_HEIGHT_PERCENT = 10
    const val MAX_GESTURE_TRIGGER_HEIGHT_PERCENT = 100

    /** The top offset of both side trigger areas within their available vertical travel. */
    const val KEY_GESTURE_TRIGGER_POSITION_PERCENT = "gesture_trigger_position_percent"
    const val DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT = 0
    const val MIN_GESTURE_TRIGGER_POSITION_PERCENT = 0
    const val MAX_GESTURE_TRIGGER_POSITION_PERCENT = 100
}
