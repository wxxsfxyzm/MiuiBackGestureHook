package dev.codex.miuibackgesturehook.hooks.systemui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RecentsOpenSessionTest {
    private fun session() = RecentsOpenSession(Any(), Any(), Any(), Any(), Any(), 42, Any(), Any(), false)

    @Test fun repeatedAppDirectionRetainsOneAttemptButHomeRetiresIt() {
        val s = session()
        val first = s.toApp()!!
        assertSame(first, s.toApp())
        s.toHome()
        assertFalse(first.isActive)
        val next = s.toApp()!!
        assertNotSame(first, next)
        assertTrue(next.isActive)
        assertFalse(first.isActive)
    }

    @Test fun cleanupCannotRearmAnOldController() {
        val s = session()
        val attempt = s.toApp()!!
        s.close()
        assertFalse(attempt.isActive)
        assertFalse(attempt.isCompletedToApp)
        assertNull(s.toApp())
        s.close()
        assertNull(s.toApp())
    }

    @Test fun provenCompletionDoesNotReopenAdmissionOrReviveTheController() {
        val s = session()
        val attempt = s.toApp()!!
        s.close()
        assertFalse(attempt.isCompletedToApp)
        attempt.completedToApp()
        assertTrue(attempt.isCompletedToApp)
        assertFalse(attempt.isActive)
        assertNull(s.toApp())
        assertFalse(attempt.matches(s.owner, s.controller, s.transition, s.info, s.task, 42))
    }

    @Test fun pendingFinishRequiresTheOriginalHandlerAndTokenAndCannotReplaceIt() {
        val s = session()
        val a = s.toApp()!!
        val handler = Any()
        val token = Any()
        assertFalse(a.hasPendingFinish(token))
        assertFalse(a.bindPendingFinish(handler, null))
        assertTrue(a.bindPendingFinish(handler, token))
        assertTrue(a.matchesPendingFinish(handler, token))
        assertFalse(a.matchesPendingFinish(Any(), token))
        assertFalse(a.matchesPendingFinish(handler, Any()))
        assertFalse(a.bindPendingFinish(Any(), Any()))
        assertTrue(a.matchesPendingFinish(handler, token))
        s.toHome()
        assertFalse(a.hasPendingFinish(token))
        assertFalse(a.matchesPendingFinish(handler, token))
        assertFalse(a.bindPendingFinish(handler, token))
        assertFalse(s.toApp()!!.hasPendingFinish(token))
    }

    @Test fun everyOwnerAndTaskIdentityMustMatch() {
        val s = session()
        val attempt = s.toApp()!!
        fun matches(values: List<Any>, id: Int = 42) =
            attempt.matches(values[0], values[1], values[2], values[3], values[4], id)
        val original = listOf(s.owner, s.controller, s.transition, s.info, s.task)
        assertTrue(matches(original))
        original.indices.forEach { index ->
            assertFalse(matches(original.toMutableList().also { it[index] = Any() }))
        }
        assertFalse(matches(original, 43))
        s.close()
        assertFalse(matches(original))
    }
}
