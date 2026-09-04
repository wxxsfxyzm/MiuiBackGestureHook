#pragma once

#include "launcher_profiles.h"

#include <stddef.h>
#include <stdint.h>

namespace miui_home_dart_profile {

enum class ResolveStage : uint32_t {
    kNotStarted = 0,
    kParsingElf = 1,
    kResolvingDrawer = 2,
    kResolvingTransition = 3,
    kResolvingOverview = 4,
    kResolvingEditing = 6,
    kComplete = 5,
    kRejectedElf = 101,
    kRejectedDrawer = 102,
    kRejectedTransition = 103,
    kRejectedOverview = 104,
    kRejectedEditing = 105,
};

struct ResolutionStorage {
    miui_home_profiles::LauncherProfile profile;
    uint8_t snapshot_build_id[32];
    uint8_t drawer_progress_end_prologue[64];
    uint8_t drawer_transition_complete_prologue[40];
    uint8_t overview_enter_prologue[32];
    uint8_t overview_exit_prologue[32];
    uint8_t editing_query_prologue[64];
};

struct ResolutionDiagnostics {
    ResolveStage stage;
    uint32_t drawer_candidate_count;
    uint32_t transition_candidate_count;
    uint32_t overview_enter_candidate_count;
    uint32_t overview_exit_candidate_count;
    uint32_t editing_candidate_count;
    uintptr_t drawer_progress_end_offset;
    uintptr_t drawer_transition_complete_offset;
    uintptr_t overview_enter_offset;
    uintptr_t overview_exit_offset;
    uintptr_t drawer_transition_epilogue_offset;
    uintptr_t overview_enter_epilogue_offset;
    uintptr_t overview_exit_epilogue_offset;
    uintptr_t editing_refresh_offset;
    uintptr_t editing_query_offset;
    uintptr_t editing_query_return_offset_a;
    uintptr_t editing_query_return_offset_b;
    uintptr_t editing_true_epilogue_offsets[4];
    size_t editing_true_epilogue_count;
    uintptr_t editing_false_epilogue_offsets[4];
    size_t editing_false_epilogue_count;
    uintptr_t all_apps_state_slot_offset;
    uintptr_t home_state_slot_offset;
};

// Resolves the Dart AOT callback family directly from the currently mapped
// libapp.so. No manifest address or companion-process configuration is used.
// Every callback must be unique and must share the expected AOT call/pool
// relationships; otherwise no profile is published.
bool ResolveDartFeatureProfile(
        const uint8_t* base, const void* snapshot_instructions,
        const void* snapshot_build_id,
        const miui_home_profiles::LauncherProfile& launcher_profile,
        ResolutionStorage* storage, ResolutionDiagnostics* diagnostics);

}  // namespace miui_home_dart_profile
