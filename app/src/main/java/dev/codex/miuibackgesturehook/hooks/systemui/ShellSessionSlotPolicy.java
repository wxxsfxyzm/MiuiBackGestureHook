package dev.codex.miuibackgesturehook.hooks.systemui;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

final class ShellSessionSlotPolicy {
    enum Action {
        KEEP,
        COMPLETE_PENDING,
        RETIRE_ABANDONED,
        FAIL_CLOSED
    }

    private ShellSessionSlotPolicy() {
    }

    static Action beforeNewPhysicalStream(
            boolean hasSession, long sessionStreamId, long nextStreamId,
            boolean completionConsumed, boolean releaseQueued,
            boolean awaitingStockCleanup, boolean shellStartInFlight,
            boolean shellOwnerUncertain, boolean sessionOwnerCurrent) {
        if (hasSession && completionConsumed) {
            return Action.COMPLETE_PENDING;
        }
        if (shellStartInFlight || shellOwnerUncertain) {
            return Action.FAIL_CLOSED;
        }
        if (!hasSession || sessionStreamId == nextStreamId) {
            return Action.KEEP;
        }
        if (releaseQueued || awaitingStockCleanup || !sessionOwnerCurrent) {
            return Action.FAIL_CLOSED;
        }
        return Action.RETIRE_ABANDONED;
    }

    static boolean recover(AtomicBoolean releaseQueued, Predicate<Runnable> runOnOwner,
                           BooleanSupplier canClearSlot, Runnable clearSlot) {
        AtomicBoolean pending = new AtomicBoolean(true);
        try {
            return runOnOwner.test(() -> {
                if (!pending.compareAndSet(true, false) || releaseQueued.get()) {
                    return;
                }
                if (canClearSlot.getAsBoolean() && !releaseQueued.get()) {
                    clearSlot.run();
                }
            });
        } finally {
            // A failed blocking wait can leave its runnable posted on the owner.
            pending.set(false);
        }
    }
}
