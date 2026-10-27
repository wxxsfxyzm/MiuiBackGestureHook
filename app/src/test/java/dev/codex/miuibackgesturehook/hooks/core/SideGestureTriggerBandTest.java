package dev.codex.miuibackgesturehook.hooks.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SideGestureTriggerBandTest {
    @Test
    public void defaultPreferencesKeepNativeArea() {
        assertNull(SideGestureTriggerBand.resolve(0, 2400, 100, 0));
    }

    @Test
    public void invalidPreferencesKeepNativeArea() {
        assertNull(SideGestureTriggerBand.resolve(0, 2400, 5, 0));
        assertNull(SideGestureTriggerBand.resolve(0, 2400, 50, 101));
    }

    @Test
    public void emptyFrameKeepsNativeArea() {
        assertNull(SideGestureTriggerBand.resolve(100, 100, 50, 50));
    }

    @Test
    public void lowerHalfBandExcludesUpperHalf() {
        assertArrayEquals(new int[]{1200, 2400},
                SideGestureTriggerBand.resolve(0, 2400, 50, 100));
    }

    @Test
    public void centeredBandLeavesBothEndsExcluded() {
        assertArrayEquals(new int[]{600, 1800},
                SideGestureTriggerBand.resolve(0, 2400, 50, 50));
    }

    @Test
    public void fullHeightAtNonDefaultPositionCoversFrame() {
        assertArrayEquals(new int[]{0, 2400},
                SideGestureTriggerBand.resolve(0, 2400, 100, 40));
    }

    @Test
    public void fractionalEdgesRoundUpLikeSystemUiComparison() {
        // Both SystemUI and WMS round [100.5, 430.5) to the representable [101, 431).
        assertArrayEquals(new int[]{101, 431},
                SideGestureTriggerBand.resolve(0, 1000, 33, 15));
    }

    @Test
    public void offsetFrameKeepsDisplayCoordinates() {
        assertArrayEquals(new int[]{1300, 2500},
                SideGestureTriggerBand.resolve(100, 2500, 50, 100));
    }
}
