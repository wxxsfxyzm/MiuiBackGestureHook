package dev.codex.miuibackgesturehook.hooks.systemserver;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SideTriggerExclusionUpdateTest {
    // Each ID represents a separate, non-touching rectangle. Android geometry is checked
    // separately on-device; these tests exercise preparation/commit and fallback ownership.
    private static final class RegionValue {
        final String name;
        final Set<Integer> rectangles = new HashSet<>();
        RegionValue(String name, int count) {
            this.name = name;
            for (int i = 0; i < count; i++) rectangles.add(i);
        }
    }

    private static final class Access implements SideTriggerExclusionUpdate.Access<RegionValue> {
        String failUnion;
        final RuntimeException failure = new IllegalStateException("region preparation failed");
        @Override public RegionValue copy(RegionValue source) {
            RegionValue result = new RegionValue(source.name, 0);
            result.rectangles.addAll(source.rectangles);
            return result;
        }
        @Override public void union(RegionValue target, RegionValue additions) {
            if (target.name.equals(failUnion)) throw failure;
            target.rectangles.addAll(additions.rectangles);
        }
        @Override public boolean withinLimit(RegionValue value, int limit) {
            return value.rectangles.size() <= limit;
        }
        @Override public void set(RegionValue target, RegionValue source) {
            target.rectangles.clear();
            target.rectangles.addAll(source.rectangles);
        }
    }

    private static RegionValue addition() {
        RegionValue value = new RegionValue("addition", 0);
        value.rectangles.add(1000);
        return value;
    }

    @Test public void publishesBothRegionsAtExactlyTheNativeLimit() {
        RegionValue restricted = new RegionValue("restricted", 63);
        RegionValue unrestricted = new RegionValue("unrestricted", 63);
        assertTrue(SideTriggerExclusionUpdate.apply(restricted, unrestricted, addition(), new Access()));
        assertEquals(64, restricted.rectangles.size());
        assertEquals(64, unrestricted.rectangles.size());
        assertTrue(restricted.rectangles.contains(1000));
        assertTrue(unrestricted.rectangles.contains(1000));
    }

    @Test public void overflowingUnrestrictedRegionCannotPartiallyPublishRestrictedStrips() {
        RegionValue restricted = new RegionValue("restricted", 2);
        RegionValue unrestricted = new RegionValue("unrestricted", 64);
        Set<Integer> original = new HashSet<>(unrestricted.rectangles);
        assertFalse(SideTriggerExclusionUpdate.apply(restricted, unrestricted, addition(), new Access()));
        assertEquals(2, restricted.rectangles.size());
        assertEquals(original, unrestricted.rectangles);
        assertFalse(restricted.rectangles.contains(1000));
    }

    @Test public void overflowingRestrictedRegionNeverTruncatesApplicationRectangles() {
        RegionValue restricted = new RegionValue("restricted", 64);
        RegionValue unrestricted = new RegionValue("unrestricted", 65);
        Set<Integer> original = new HashSet<>(restricted.rectangles);
        assertFalse(SideTriggerExclusionUpdate.apply(restricted, unrestricted, addition(), new Access()));
        assertEquals(original, restricted.rectangles);
        assertEquals(65, unrestricted.rectangles.size());
    }

    @Test public void rejectedUpdateKeepsFreshNativeOutputInsteadOfPreviousPublishedGeometry() {
        Access access = new Access();
        RegionValue restricted = new RegionValue("restricted", 1);
        assertTrue(SideTriggerExclusionUpdate.apply(restricted, null, addition(), access));
        assertTrue(restricted.rectangles.contains(1000));
        // DisplayContent's next chain.proceed() supplies new native output before our hook.
        RegionValue freshNative = new RegionValue("native", 64);
        access.set(restricted, freshNative);
        assertFalse(SideTriggerExclusionUpdate.apply(restricted, null, addition(), access));
        assertEquals(freshNative.rectangles, restricted.rectangles);
        assertFalse(restricted.rectangles.contains(1000));
    }

    @Test public void preparationFailureLeavesBothNativeOutputsUntouched() {
        Access access = new Access();
        access.failUnion = "unrestricted";
        RegionValue restricted = new RegionValue("restricted", 1);
        RegionValue unrestricted = new RegionValue("unrestricted", 3);
        try {
            SideTriggerExclusionUpdate.apply(restricted, unrestricted, addition(), access);
            fail("Preparation failure should reach the hook's native fallback");
        } catch (RuntimeException failure) {
            assertSame(access.failure, failure);
        }
        assertEquals(1, restricted.rectangles.size());
        assertEquals(3, unrestricted.rectangles.size());
        assertFalse(restricted.rectangles.contains(1000));
    }

    @Test public void absentUnrestrictedOutputDoesNotPreventPublication() {
        RegionValue restricted = new RegionValue("restricted", 1);
        assertTrue(SideTriggerExclusionUpdate.apply(restricted, null, addition(), new Access()));
        assertTrue(restricted.rectangles.contains(1000));
    }
}
