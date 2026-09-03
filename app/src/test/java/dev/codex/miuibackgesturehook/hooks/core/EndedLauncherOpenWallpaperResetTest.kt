package dev.codex.miuibackgesturehook.hooks.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndedLauncherOpenWallpaperResetTest {
    @Test
    fun matchesOnlyTheExactWallpaperHomeCommand() {
        val wallpaper = Any()
        val marker = HookRuntimeCore.EndedLauncherOpenWallpaperReset(
            null,
            0,
            null,
            null,
            wallpaper,
            1f,
        )

        assertTrue(marker.matchesCommand(wallpaper, 1f))
        assertFalse(marker.matchesCommand(Any(), 1f))
        assertFalse(marker.matchesCommand(wallpaper, 1.14f))
    }
}
