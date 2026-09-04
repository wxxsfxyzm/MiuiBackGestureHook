#pragma once

#include <stddef.h>
#include <stdint.h>

namespace miui_home_profiles {

enum class BusinessHookTopology : uint8_t {
    kLegacyThreeStage,
    kSideBoundaryOnly,
};

struct CodeFingerprint {
    uintptr_t offset;
    const uint8_t* bytes;
    size_t size;
};

struct LauncherProfile {
    const char* id;
    const char* version_name;
    uintptr_t image_span;
    uintptr_t entry_offset;
    const CodeFingerprint* identity_fingerprints;
    size_t identity_fingerprint_count;

    BusinessHookTopology business_topology;
    uintptr_t side_handler_offset;
    const uint8_t* side_handler_prologue;
    size_t side_handler_prologue_size;
    uintptr_t side_edge_field_offset;

    uintptr_t pointer_handler_offset;
    const uint8_t* pointer_handler_prologue;
    size_t pointer_handler_prologue_size;
    uintptr_t touch_processor_offset;
    const uint8_t* touch_processor_prologue;
    size_t touch_processor_prologue_size;
    uintptr_t gesture_type_field_offset;

    uintptr_t drawer_state_handler_offset;
    const uint8_t* drawer_state_handler_prologue;
    size_t drawer_state_handler_prologue_size;

    uintptr_t dart_snapshot_instructions_offset;
    uintptr_t dart_snapshot_build_id_offset;
    const uint8_t* dart_snapshot_build_id;
    size_t dart_snapshot_build_id_size;
    uintptr_t dart_drawer_progress_end_offset;
    const uint8_t* dart_drawer_progress_end_prologue;
    size_t dart_drawer_progress_end_prologue_size;
    uintptr_t dart_drawer_transition_complete_offset;
    const uint8_t* dart_drawer_transition_complete_prologue;
    size_t dart_drawer_transition_complete_prologue_size;
    uintptr_t dart_all_apps_state_slot_offset;
    uintptr_t dart_home_state_slot_offset;
    uintptr_t dart_overview_enter_offset;
    const uint8_t* dart_overview_enter_prologue;
    size_t dart_overview_enter_prologue_size;
    uintptr_t dart_overview_exit_offset;
    const uint8_t* dart_overview_exit_prologue;
    size_t dart_overview_exit_prologue_size;
    uintptr_t dart_editing_query_offset;
    const uint8_t* dart_editing_query_prologue;
    size_t dart_editing_query_prologue_size;
    uintptr_t dart_editing_query_return_offset_a;
    uintptr_t dart_editing_query_return_offset_b;

    uintptr_t contextual_long_press_handler_offset;
    const uint8_t* contextual_long_press_handler_prologue;
    size_t contextual_long_press_handler_prologue_size;
    uintptr_t contextual_search_invoke_offset;
    const uint8_t* contextual_search_invoke_prologue;
    size_t contextual_search_invoke_prologue_size;

    // Optional runtime-only XiaoAi visibility observer. This is the return
    // address of the uniquely resolved Bundle_get_boolean("isEnter") call,
    // not a version/profile offset supplied by the manifest.
    uintptr_t xiaoai_bundle_bool_return_offset;

    uintptr_t accepted_pilfer_return_offset;
    uintptr_t home_pilfer_return_offset;
    const uint8_t* accepted_pilfer_caller;
    size_t accepted_pilfer_caller_size;

    uintptr_t rstring_vtable_offset;
    uintptr_t runtime_pointer_offset;
    uintptr_t runtime_state_offset;
    uint32_t runtime_ready_value;

    // Dynamically resolved Dart AOT observation sites. These point to exact
    // four-instruction return epilogues; no Dart function entry is replaced.
    uintptr_t dart_drawer_transition_epilogue_offset;
    uintptr_t dart_overview_enter_epilogue_offset;
    uintptr_t dart_overview_exit_epilogue_offset;
    uintptr_t dart_editing_true_epilogue_offsets[4];
    size_t dart_editing_true_epilogue_count;
    uintptr_t dart_editing_false_epilogue_offsets[4];
    size_t dart_editing_false_epilogue_count;
};

}  // namespace miui_home_profiles
