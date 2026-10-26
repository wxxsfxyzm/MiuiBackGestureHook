package dev.codex.miuibackgesturehook.hooks.systemserver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SideTriggerExclusionBandTest {
    @Test
    public void defaultPreferencesKeepNativeArea() {
        assertNull(SideTriggerExclusionBand.resolve(0, 2400, 100, 0));
    }

    @Test
    public void invalidPreferencesKeepNativeArea() {
        assertNull(SideTriggerExclusionBand.resolve(0, 2400, 5, 0));
        assertNull(SideTriggerExclusionBand.resolve(0, 2400, 50, 101));
    }

    @Test
    public void emptyFrameKeepsNativeArea() {
        assertNull(SideTriggerExclusionBand.resolve(100, 100, 50, 50));
    }

    @Test
    public void lowerHalfBandExcludesUpperHalf() {
        assertArrayEquals(new int[]{1200, 2400},
                SideTriggerExclusionBand.resolve(0, 2400, 50, 100));
    }

    @Test
    public void centeredBandLeavesBothEndsExcluded() {
        assertArrayEquals(new int[]{600, 1800},
                SideTriggerExclusionBand.resolve(0, 2400, 50, 50));
    }

    @Test
    public void fullHeightAtNonDefaultPositionCoversFrame() {
        assertArrayEquals(new int[]{0, 2400},
                SideTriggerExclusionBand.resolve(0, 2400, 100, 40));
    }

    @Test
    public void fractionalEdgesRoundUpLikeSystemUiComparison() {
        // height 330, travel 670, top 100.5: SystemUI accepts rawY in [100.5, 430.5).
        assertArrayEquals(new int[]{101, 431},
                SideTriggerExclusionBand.resolve(0, 1000, 33, 15));
    }

    @Test
    public void offsetFrameKeepsDisplayCoordinates() {
        assertArrayEquals(new int[]{1300, 2500},
                SideTriggerExclusionBand.resolve(100, 2500, 50, 100));
    }
}
