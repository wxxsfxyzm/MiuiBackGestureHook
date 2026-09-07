#pragma once

#include <stdint.h>

namespace miui_home_dart_state {

// Record the snapshot actually sent, even if the live observation changed
// during the broadcast. Otherwise A -> B -> A can leave cached A suppressing
// the correction after SystemUI has received B. Each publisher serializes its
// own receipts; the drain loop compares this receipt with the latest state.
inline bool RecordPublication(bool sent, uint32_t state, int64_t generation,
                              uint64_t owner_epoch,
                              volatile uint32_t* published_state,
                              volatile int64_t* published_generation,
                              volatile uint64_t* published_owner_epoch) {
    if (!sent) return false;
    __atomic_store_n(published_state, state, __ATOMIC_RELEASE);
    __atomic_store_n(published_generation, generation, __ATOMIC_RELEASE);
    __atomic_store_n(published_owner_epoch, owner_epoch, __ATOMIC_RELEASE);
    return true;
}

}  // namespace miui_home_dart_state
