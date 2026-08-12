package dev.codex.miuibackgesturehook;

public final class PredictiveBackPreferences {
    public static final String GROUP = "predictive_back_opt_in";
    public static final String KEY_PACKAGES = "packages";
    public static final String KEY_HYPEROS_INDICATOR = "hyperos_indicator_style";
    public static final boolean DEFAULT_HYPEROS_INDICATOR = false;
    public static final String KEY_HYPEROS_HAPTICS = "hyperos_indicator_haptics";
    public static final boolean DEFAULT_HYPEROS_HAPTICS = false;
    /** Only used to migrate the removed AOSP-specific switch once. */
    public static final String LEGACY_KEY_AOSP_HYPEROS_HAPTICS =
            "aosp_gesture_hyperos_haptics";
    public static final String KEY_HYPEROS_HAPTICS_ENHANCED =
            "hyperos_indicator_haptics_enhanced";
    public static final boolean DEFAULT_HYPEROS_HAPTICS_ENHANCED = false;
    public static final String KEY_HYPEROS_SLIDE_ANIMATION =
            "hyperos_slide_back_animation";
    public static final boolean DEFAULT_HYPEROS_SLIDE_ANIMATION = false;
    public static final String KEY_AOSP_BACKGROUND_MODE = "aosp_background_mode";
    public static final int AOSP_BACKGROUND_SYSTEM = 0;
    public static final int AOSP_BACKGROUND_BLACK = 1;
    public static final int AOSP_BACKGROUND_WALLPAPER = 2;
    public static final int DEFAULT_AOSP_BACKGROUND_MODE = AOSP_BACKGROUND_SYSTEM;
    public static final String KEY_AOSP_WALLPAPER_BLUR = "aosp_wallpaper_blur";
    public static final boolean DEFAULT_AOSP_WALLPAPER_BLUR = false;
    public static final String KEY_MODULE_LOGGING = "module_logging";
    public static final boolean DEFAULT_MODULE_LOGGING = true;
    /** The vertical size of both Xiaomi side trigger areas, expressed as a percentage. */
    public static final String KEY_GESTURE_TRIGGER_HEIGHT_PERCENT =
            "gesture_trigger_height_percent";
    public static final int DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT = 100;
    public static final int MIN_GESTURE_TRIGGER_HEIGHT_PERCENT = 10;
    public static final int MAX_GESTURE_TRIGGER_HEIGHT_PERCENT = 100;

    /** The top offset of both side trigger areas within their available vertical travel. */
    public static final String KEY_GESTURE_TRIGGER_POSITION_PERCENT =
            "gesture_trigger_position_percent";
    public static final int DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT = 0;
    public static final int MIN_GESTURE_TRIGGER_POSITION_PERCENT = 0;
    public static final int MAX_GESTURE_TRIGGER_POSITION_PERCENT = 100;

    private PredictiveBackPreferences() {
    }

    public static boolean isValidAospBackgroundMode(int mode) {
        return mode >= AOSP_BACKGROUND_SYSTEM && mode <= AOSP_BACKGROUND_WALLPAPER;
    }
}
