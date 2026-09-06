package dev.codex.miuibackgesturehook.hooks.systemserver

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemServerAndroid17ImplTest {
    @Test
    fun admitsTheRecordedQuarterTurnWithoutTreatingOtherBoundsAsEquivalent() {
        // Bilibili #228: display/closing frame is landscape; opening fixed frame is portrait.
        assertTrue(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, 1, 2670, 1200, 1200, 2670))
        assertTrue(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, 3, 2670, 1200, 1200, 2670))
        assertFalse(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, 0, 2670, 1200, 1200, 2670))
        assertFalse(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, 2, 2670, 1200, 1200, 2670))
        assertFalse(SystemServerAndroid17Impl.isQuarterTurnGeometry(1, 1, 2670, 1200, 1200, 2670))
        assertFalse(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, 1, 2670, 1200, 1200, 2600))
        assertFalse(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, 1, 2670, 1200, 2670, 1200))
        assertFalse(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, 1, 1200, 1200, 1200, 1200))
        assertFalse(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, 1, 0, 1200, 1200, 0))
        assertFalse(SystemServerAndroid17Impl.isQuarterTurnGeometry(0, -1, 2670, 1200, 1200, 2670))
    }
}
