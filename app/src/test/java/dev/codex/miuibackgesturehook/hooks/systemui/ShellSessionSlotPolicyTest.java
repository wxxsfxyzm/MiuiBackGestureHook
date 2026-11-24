package dev.codex.miuibackgesturehook.hooks.systemui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ShellSessionSlotPolicyTest {
    @Test
    public void laterStreamCanRecoverAfterBusyShellBecomesIdle() {
        AtomicBoolean releaseQueued = new AtomicBoolean();
        AtomicBoolean slotOccupied = new AtomicBoolean(true);
        AtomicBoolean shellReady = new AtomicBoolean();
        Runnable clearSlot = () -> slotOccupied.set(false);

        ShellSessionSlotPolicy.recover(releaseQueued, this::runOnOwner, shellReady::get, clearSlot);
        assertTrue(slotOccupied.get());
        assertFalse(releaseQueued.get());

        shellReady.set(true);
        ShellSessionSlotPolicy.recover(releaseQueued, this::runOnOwner, shellReady::get, clearSlot);
        assertFalse(slotOccupied.get());
        assertFalse(releaseQueued.get());
    }

    @Test
    public void taskDeliveredAfterFailedWaitCannotInspectOrClearSlot() {
        AtomicBoolean releaseQueued = new AtomicBoolean();
        AtomicReference<Runnable> postedTask = new AtomicReference<>();
        Runnable unexpectedAction = () -> { throw new AssertionError("Late recovery acted"); };

        assertFalse(ShellSessionSlotPolicy.recover(releaseQueued, task -> {
            postedTask.set(task);
            return false;
        }, () -> { throw new AssertionError("Late recovery inspected Shell"); },
                unexpectedAction));

        postedTask.get().run();
        assertFalse(releaseQueued.get());
    }

    @Test
    public void keepsSlotWhenReleaseIsQueuedDuringReadinessCheck() {
        AtomicBoolean releaseQueued = new AtomicBoolean();
        AtomicBoolean slotOccupied = new AtomicBoolean(true);

        ShellSessionSlotPolicy.recover(releaseQueued, this::runOnOwner, () -> {
            releaseQueued.set(true);
            return true;
        }, () -> slotOccupied.set(false));

        assertTrue(slotOccupied.get());
        assertTrue(releaseQueued.get());
    }

    @Test
    public void noSessionDoesNotAdmitStreamWhileStartOwnershipIsUnsettled() {
        assertEquals(ShellSessionSlotPolicy.Action.FAIL_CLOSED,
                decide(false, 0L, 30L, false, false, false,
                        true, false, false));
    }

    private boolean runOnOwner(Runnable task) {
        task.run();
        return true;
    }

    @Test
    public void keepsCurrentSlotWhenThereIsNoSession() {
        assertEquals(ShellSessionSlotPolicy.Action.KEEP,
                decide(false, 29L, 30L, false, false, false,
                        false, false, false));
    }

    @Test
    public void completesSessionWhoseCompletionIsAlreadyConsumed() {
        assertEquals(ShellSessionSlotPolicy.Action.COMPLETE_PENDING,
                decide(true, 29L, 30L, true, false, false,
                        false, false, false));
    }

    @Test
    public void retiresUnreleasedSessionFromAnOlderPhysicalStream() {
        assertEquals(ShellSessionSlotPolicy.Action.RETIRE_ABANDONED,
                decide(true, 29L, 30L, false, false, false,
                        false, false, true));
    }

    @Test
    public void failsClosedWhileReleaseIsQueued() {
        assertEquals(ShellSessionSlotPolicy.Action.FAIL_CLOSED,
                decide(true, 29L, 30L, false, true, false,
                        false, false, true));
    }

    @Test
    public void failsClosedWhenTheShellOwnerChanged() {
        assertEquals(ShellSessionSlotPolicy.Action.FAIL_CLOSED,
                decide(true, 29L, 30L, false, false, false,
                        false, false, false));
    }

    @Test
    public void keepsTheSamePhysicalStream() {
        assertEquals(ShellSessionSlotPolicy.Action.KEEP,
                decide(true, 29L, 29L, false, false, false,
                        false, false, true));
    }

    @Test
    public void failsClosedWhileStockCleanupIsPending() {
        assertEquals(ShellSessionSlotPolicy.Action.FAIL_CLOSED,
                decide(true, 29L, 30L, false, false, true,
                        false, false, true));
    }

    @Test
    public void failsClosedWhileShellStartIsInFlight() {
        assertEquals(ShellSessionSlotPolicy.Action.FAIL_CLOSED,
                decide(true, 29L, 30L, false, false, false,
                        true, false, true));
    }

    private static ShellSessionSlotPolicy.Action decide(
            boolean hasSession, long sessionStreamId, long nextStreamId,
            boolean completionConsumed, boolean releaseQueued,
            boolean awaitingStockCleanup, boolean shellStartInFlight,
            boolean shellOwnerUncertain, boolean sessionOwnerCurrent) {
        return ShellSessionSlotPolicy.beforeNewPhysicalStream(
                hasSession, sessionStreamId, nextStreamId,
                completionConsumed, releaseQueued, awaitingStockCleanup,
                shellStartInFlight, shellOwnerUncertain, sessionOwnerCurrent);
    }
}
