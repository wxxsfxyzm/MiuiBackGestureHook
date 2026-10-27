package dev.codex.miuibackgesturehook.hooks.systemserver;

import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RegionIterator;

/** Stages the optional strips before changing either WMS output region. */
final class SideTriggerExclusionUpdate {
    // MiuiHome's exclusion callback accepts at most 256 i32 coordinates (64 rectangles).
    static final int MAX_RECTANGLES = 64;

    interface Access<R> {
        R copy(R source);
        void union(R target, R additions);
        boolean withinLimit(R region, int limit);
        void set(R target, R source);
    }

    private static final Access<Region> ANDROID = new Access<>() {
        @Override public Region copy(Region source) { return new Region(source); }
        @Override public void union(Region target, Region additions) {
            target.op(additions, Region.Op.UNION);
        }
        @Override public boolean withinLimit(Region region, int limit) {
            RegionIterator iterator = new RegionIterator(region);
            Rect rectangle = new Rect();
            int count = 0;
            while (iterator.next(rectangle)) {
                if (++count > limit) return false;
            }
            return true;
        }
        @Override public void set(Region target, Region source) { target.set(source); }
    };

    private SideTriggerExclusionUpdate() { }

    static boolean apply(Region restricted, Region unrestricted, Region additions) {
        return apply(restricted, unrestricted, additions, ANDROID);
    }

    /**
     * On rejection or preparation failure the caller keeps this traversal's native output.
     * No previous module region is reused, and application rectangles are never truncated.
     */
    static <R> boolean apply(R restricted, R unrestricted, R additions, Access<R> access) {
        R candidate = access.copy(restricted);
        R unlimited = unrestricted == null ? null
                : unrestricted == restricted ? candidate : access.copy(unrestricted);
        access.union(candidate, additions);
        if (unlimited != null && unlimited != candidate) access.union(unlimited, additions);
        if (!access.withinLimit(candidate, MAX_RECTANGLES)
                || (unlimited != null && unlimited != candidate
                && !access.withinLimit(unlimited, MAX_RECTANGLES))) return false;
        access.set(restricted, candidate);
        if (unrestricted != null && unrestricted != restricted) access.set(unrestricted, unlimited);
        return true;
    }
}
