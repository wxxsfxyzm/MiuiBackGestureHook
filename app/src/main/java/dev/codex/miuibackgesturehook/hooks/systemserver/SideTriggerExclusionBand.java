package dev.codex.miuibackgesturehook.hooks.systemserver;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;

/**
 * Maps the shared side trigger preferences onto the vertical span of a system gesture frame.
 * SystemUI accepts a DOWN when {@code top <= rawY < top + height} using the same float math,
 * so the integer edges here round up to exclude exactly the rows SystemUI rejects.
 */
final class SideTriggerExclusionBand {
    private SideTriggerExclusionBand() {
    }

    /**
     * Returns {@code {bandTop, bandBottom}} in frame coordinates, or {@code null} when the
     * preferences keep the native full-height area or the frame has no vertical extent.
     */
    static int[] resolve(int frameTop, int frameBottom, int heightPercent,
                         int positionPercent) {
        if (heightPercent < PredictiveBackPreferences.MIN_GESTURE_TRIGGER_HEIGHT_PERCENT
                || heightPercent > PredictiveBackPreferences.MAX_GESTURE_TRIGGER_HEIGHT_PERCENT
                || positionPercent < PredictiveBackPreferences.MIN_GESTURE_TRIGGER_POSITION_PERCENT
                || positionPercent
                > PredictiveBackPreferences.MAX_GESTURE_TRIGGER_POSITION_PERCENT) {
            return null;
        }
        if (heightPercent == PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT
                && positionPercent
                == PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT) {
            return null;
        }
        int frameHeight = frameBottom - frameTop;
        if (frameHeight <= 0) {
            return null;
        }
        float configuredHeight = frameHeight * heightPercent / 100.0f;
        float availableTravel = Math.max(0.0f, frameHeight - configuredHeight);
        float top = availableTravel * positionPercent / 100.0f;
        int bandTop = frameTop + (int) Math.ceil(top);
        int bandBottom = frameTop + (int) Math.ceil(top + configuredHeight);
        bandTop = Math.max(frameTop, Math.min(frameBottom, bandTop));
        bandBottom = Math.max(bandTop, Math.min(frameBottom, bandBottom));
        return new int[]{bandTop, bandBottom};
    }
}
