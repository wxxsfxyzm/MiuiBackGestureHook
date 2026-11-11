#pragma once

#include "launcher_profiles.h"

#include <stddef.h>
#include <stdint.h>

namespace miui_home_runtime_profile {

enum class ResolveStage : uint32_t {
    kNotStarted = 0,
    kParsingElf = 1,
    kResolvingImports = 2,
    kResolvingSideBoundary = 3,
    kResolvingRuntime = 4,
    kResolvingRString = 5,
    kResolvingContextualSearch = 6,
    kComplete = 7,
    kRejectedElf = 101,
    kRejectedImports = 102,
    kRejectedSideBoundary = 103,
    kRejectedRuntime = 104,
    kRejectedRString = 105,
};

struct ResolutionStorage {
    miui_home_profiles::LauncherProfile profile;
    miui_home_profiles::CodeFingerprint identity_fingerprint;
    uint8_t entry_fingerprint[48];
    uint8_t side_prologue[32];
    uint8_t contextual_long_press_prologue[32];
    uint8_t contextual_search_invoke_prologue[32];
};

struct ResolutionDiagnostics {
    ResolveStage stage;
    uint32_t side_candidate_count;
    uint32_t runtime_confirmation_count;
    uint32_t rstring_candidate_count;
    uint32_t contextual_support_candidate_count;
    uint32_t contextual_invoke_candidate_count;
    uint32_t contextual_long_press_candidate_count;
    uint32_t contextual_resolved;
    uint32_t xiaoai_candidate_count;
    uint32_t xiaoai_resolved;
    uintptr_t side_handler_offset;
    uintptr_t runtime_pointer_offset;
    uintptr_t runtime_state_offset;
    uintptr_t rstring_vtable_offset;
    uintptr_t contextual_search_invoke_offset;
    uintptr_t contextual_long_press_handler_offset;
    uintptr_t xiaoai_bundle_bool_return_offset;
};

// Resolves only the Android 17 side-boundary launcher family. Every published
// address is backed by a mapped ELF segment and the expected imported/internal
// call graph. The contextual-search and XiaoAi visibility extensions are
// optional: ambiguity leaves their profile fields empty without weakening the
// base side profile.
bool ResolveSideBoundaryProfile(const uint8_t* base, void* app_entry_point,
                                ResolutionStorage* storage,
                                ResolutionDiagnostics* diagnostics);

// Resolves only the optional Android 17 contextual-search terminal family
// and overlays it onto an already-validated static launcher profile. This is
// intentionally independent of the legacy runtime-singleton resolver: newer
// launchers can retain the exact long-press graph while moving that singleton.
bool ResolveContextualSearchOverlay(
        const uint8_t* base,
        const miui_home_profiles::LauncherProfile* base_profile,
        ResolutionStorage* storage,
        ResolutionDiagnostics* diagnostics);

// Resolve the physical monitor that starts Xiaomi's native bottom-long-press
// manager, before dispatch-disabled redirection can bypass further detector
// delivery. Require both the manager's handler graph and this receiver's exact
// id/action/finish import graph and direct call relationship.
bool ResolveContextualLongPressInput(const uint8_t* base, uintptr_t* entry_offset);

// SfAnimParam field offsets proven by SfAnim::start_animation's own entry log:
// the exact format template names each argument, and the argument array replay
// maps it to a field of the caller-owned parameter copy passed in x1.
struct SfAnimStartLayout {
    uintptr_t entry_offset;
    uint32_t anim_type;
    uint32_t w_start, w_end, w_velocity;
    uint32_t h_start, h_end, h_velocity;
    uint32_t cx_start, cx_end;
    uint32_t cy_start, cy_end;
};

// Resolves SfAnim::start_animation(&self, &mut SfAnimParam) and its layout.
// Every direct caller must pass a fresh memcpy'd parameter copy; any mismatch
// leaves the optional return-home start rewrite disabled.
bool ResolveSfAnimStartAnimation(const uint8_t* base, SfAnimStartLayout* layout);

}  // namespace miui_home_runtime_profile
