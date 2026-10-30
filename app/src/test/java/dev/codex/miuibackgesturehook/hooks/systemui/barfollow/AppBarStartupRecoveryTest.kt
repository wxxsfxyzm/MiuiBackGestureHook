package dev.codex.miuibackgesturehook.hooks.systemui.barfollow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppBarStartupRecoveryTest {
    private class Fixture {
        val feature = AppBarFeatureState()
        val queued = ArrayDeque<Runnable>()
        val recovery = AppBarStartupRecovery(feature) { queued.addLast(it) }
        var starts = 0
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    @Test fun homeReadyRecoversFailedStartupWithoutPreferenceChange() {
        val f = Fixture()
        f.feature.setEnabled(true)
        val originalGeneration = f.feature.generation()
        // Initial preparation had no Home injector. Its later bind is the retry trigger.
        f.recovery.homeReady { f.starts++ }
        assertEquals(0, f.starts)
        f.drain()
        assertEquals(1, f.starts)
        assertEquals(originalGeneration, f.feature.generation())
    }

    @Test fun multipleBindingsOnlyReconcileTheLatestPublication() {
        val f = Fixture()
        f.feature.setEnabled(true)
        f.recovery.homeReady { error("Superseded Home must not start an App window") }
        f.recovery.homeReady { f.starts++ }
        f.drain()
        assertEquals(1, f.starts)
    }

    @Test fun disabledPreferenceDoesNotQueueRecovery() {
        val f = Fixture()
        f.recovery.homeReady { f.starts++ }
        assertEquals(0, f.queued.size)
        f.drain()
        assertEquals(0, f.starts)
    }

    @Test fun disablingAndReenablingRejectsAnAlreadyQueuedHome() {
        val f = Fixture()
        f.feature.setEnabled(true)
        f.recovery.homeReady { error("Old preference generation must not recover") }
        f.feature.setEnabled(false)
        f.feature.setEnabled(true)
        f.drain()
        assertEquals(0, f.starts)
        f.recovery.homeReady { f.starts++ }
        f.drain()
        assertEquals(1, f.starts)
    }

    @Test fun retiredRuntimeCannotRecoverAfterReload() {
        val f = Fixture()
        f.feature.setEnabled(true)
        f.recovery.homeReady { error("Retired runtime must not recover") }
        f.feature.suspend()
        f.drain()
        assertFalse(f.feature.enabled())
        assertEquals(0, f.starts)
    }

    @Test fun rejectedReloadNeedsAFreshHomeEvent() {
        val f = Fixture()
        f.feature.setEnabled(true)
        f.recovery.homeReady { error("Pre-reload Home event must not recover") }
        f.feature.suspend()
        f.feature.resumeAfterRejectedReload()
        f.recovery.homeReady { f.starts++ }
        f.drain()
        assertEquals(1, f.starts)
    }
}
