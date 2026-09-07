#include "lsposed_hook_backend.h"
#include "dart_runtime_resolver.h"
#include "dart_state_publication.h"
#include "launcher_profiles.h"
#include "launcher_profiles.generated.h"
#include "runtime_profile_resolver.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sched.h>
#include <pthread.h>
#include <poll.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/eventfd.h>
#include <unistd.h>

extern "C" {

// These hidden symbols are shared with broadcast_options_tail_hook.S.  The
// hook must remain a raw AArch64 tail call: the private Rust method returns a
// 16-byte Result through x8, which cannot be expressed as an equivalent C++
// aggregate return without changing the platform ABI.
__attribute__((visibility("hidden")))
void* miui_home_hyos_broadcast_original = nullptr;
__attribute__((visibility("hidden")))
uintptr_t miui_home_hyos_broadcast_option_thread = 0u;
__attribute__((visibility("hidden")))
void* miui_home_hyos_broadcast_option_bundle = nullptr;
__attribute__((visibility("hidden")))
uint32_t miui_home_hyos_broadcast_option_state = 0u;

__attribute__((visibility("hidden")))
void MiuiHomeHyosBroadcastOptionsTailHook();

__attribute__((visibility("hidden")))
void MiuiHomeHyosInputMonitorPilferHook();

__attribute__((visibility("hidden")))
void MiuiHomeHyosDartDrawerTransitionEpilogueHook();

__attribute__((visibility("hidden")))
void MiuiHomeHyosDartOverviewEnterEpilogueHook();

__attribute__((visibility("hidden")))
void MiuiHomeHyosDartOverviewExitEpilogueHook();

__attribute__((visibility("hidden")))
void MiuiHomeHyosDartHomeSurfaceInactiveEpilogueHook();

__attribute__((visibility("hidden")))
void MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook();

__attribute__((visibility("hidden")))
void MiuiHomeHyosInputMonitorPilferImpl(void* monitor, uintptr_t return_pc);

__attribute__((visibility("hidden")))
void* miui_home_hyos_dart_drawer_epilogue_original = nullptr;

__attribute__((visibility("hidden")))
void* miui_home_hyos_dart_overview_enter_epilogue_original = nullptr;

__attribute__((visibility("hidden")))
void* miui_home_hyos_dart_overview_exit_epilogue_original = nullptr;

__attribute__((visibility("hidden")))
void* miui_home_hyos_dart_editing_epilogue_original[2] = {};

// Dart AOT hooks update these cells directly in assembly.  Keep the symbols
// hidden so ADRP/ADD can address them without a GOT lookup, while C++ owns all
// publication and status-query reads outside the Dart thread.
// Dart observations are packed as (owner epoch << 2) | state, where state is
// 1=false and 2=true.  Publishing value and owner identity in one release
// store prevents a retired AOT mapping from being mistaken for the current
// launcher owner.
__attribute__((used, visibility("hidden")))
volatile uint64_t g_dart_state_owner_epoch = 1;
__attribute__((used, visibility("hidden")))
volatile int32_t g_dart_state_owner_pid = 0;
__attribute__((used, visibility("hidden")))
volatile uint64_t g_drawer_state_observation = 0;
__attribute__((used, visibility("hidden")))
volatile uint64_t g_overview_state_observation = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_overview_dart_enter_count = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_overview_dart_exit_count = 0;
__attribute__((used, visibility("hidden")))
volatile uint64_t g_editing_state_observation = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_editing_dart_observe_count = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_state_publish_pending = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_drawer_active_count = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_overview_enter_active_count = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_overview_exit_active_count = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_editing_active_count = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_drawer_retiring = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_overview_enter_retiring = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_overview_exit_retiring = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_editing_retiring = 0;
__attribute__((used)) volatile uint32_t g_dart_remap_pending_mask = 0;
__attribute__((used)) volatile uint32_t g_dart_remap_repair_in_flight = 0;
__attribute__((used, visibility("hidden")))
volatile int32_t g_dart_state_publish_event_fd = -1;
__attribute__((used, visibility("hidden")))
volatile uint64_t g_dart_state_publish_callback_epoch = 0;
__attribute__((used, visibility("hidden")))
volatile uint64_t g_dart_state_publish_failure_epoch = 0;
__attribute__((used, visibility("hidden")))
volatile uint64_t g_dart_state_owner_published_epoch = 0;
__attribute__((used, visibility("hidden")))
volatile int64_t g_dart_state_owner_published_generation = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_state_atfork_state = 0;
__attribute__((used, visibility("hidden")))
volatile uint32_t g_dart_state_publish_dispatcher_state = 0;

}

namespace {

constexpr uint32_t kDartDrawerRemapped = uint32_t{1} << 0u;
constexpr uint32_t kDartOverviewEnterRemapped = uint32_t{1} << 1u;
constexpr uint32_t kDartOverviewExitRemapped = uint32_t{1} << 2u;
constexpr uint32_t kDartEditingRemapped = uint32_t{1} << 3u;
constexpr uint32_t kDartDrawerPending = uint32_t{1} << 0u;
constexpr uint32_t kDartOverviewPending = uint32_t{1} << 1u;
constexpr uint32_t kDartEditingPending = uint32_t{1} << 2u;
constexpr uint32_t kDartOwnerPending = uint32_t{1} << 3u;
constexpr uint32_t kDartAllStatePending = kDartDrawerPending |
        kDartOverviewPending | kDartEditingPending | kDartOwnerPending;

constexpr char kLogTag[] = "MiuiHomeHyosLsp";
// The first 4371 private-broadcast hook confused its 16-byte Rust x8 result
// with the public wrapper's 48-byte result and aborted in Scudo.  The raw tail
// shim preserves that ABI. Device validation remains bounded by LSPosed's
// module/scope state, the exact process, and immutable library IDs.
constexpr char kSpawnerPath[] = "/system_ext/bin/hyos_spawner";
constexpr char kHyperRuntimeName[] = "libhyper_os_flutter.so";
constexpr char kBroadcastPrivatePath[] =
        "/system_ext/lib64/libhyper_os_broadcast_private.dylib.so";
constexpr char kBroadcastReceiverOnReceiveSymbol[] =
        "_RNvMs3_NtNtCslLvADlVgqlk_26hyper_os_broadcast_private13dyn_"
        "broadcast23BroadcastReceiver_traitINtB5_20BroadcastReceiver_TOINtNtNtNt"
        "Cs9Neji4M1weT_10abi_stable9std_types5boxed7private4RBoxuEE10on_receiveB9_";
constexpr char kBroadcastSendSymbol[] =
        "_RNvNtNtCslLvADlVgqlk_26hyper_os_broadcast_private5scene5impls14send_broadcast";
constexpr char kBroadcastIntentWithFeatureSymbol[] =
        "_RNvXs_NtCslLvADlVgqlk_26hyper_os_broadcast_private8sys_implNtB4_31"
        "ActivityManagerServiceProxyImplNtB4_27ActivityManagerServiceProxy26"
        "broadcastIntentWithFeature";
constexpr char kLauncherProcessName[] = "com.miui.home";
constexpr char kDataLauncherLibraryTail[] =
        "/base.apk!/lib/arm64-v8a/libapp_launcher.so";
constexpr char kSystemLauncherLibraryTail[] =
        "/MiuiHome.apk!/lib/arm64-v8a/libapp_launcher.so";
constexpr char kLauncherEntrySymbol[] = "app_entry_point";
constexpr char kDartLibraryName[] = "libapp.so";
constexpr char kDartLibraryTail[] = "/libapp.so";
constexpr char kDartSnapshotInstructionsSymbol[] =
        "_kDartIsolateSnapshotInstructions";
constexpr char kDartSnapshotBuildIdSymbol[] = "_kDartSnapshotBuildId";
using DlopenFn = void* (*)(const char*, int);
using MotionEventIntFn = int32_t (*)(void*);
using MotionEventLongFn = int64_t (*)(void*);
using MotionEventFloatFn = float (*)(void*);
using InputMonitorPilferFn = void (*)(void*);
using GestureStubPointerHandlerFn = void (*)(void*, void*, void*, uint32_t);
using GestureBackTouchProcessorFn = void (*)(void*, void*, void*);
using GestureStubBackHandlerFn = void (*)(void*, void*);
using ContextualLongPressHandlerFn = void (*)(void*, uint32_t);
using ContextualSearchInvokeFn = uint8_t (*)(uint32_t);
using ContextualClosureCleanupFn = void (*)(void*);
using DrawerStateHandlerFn = void (*)(int64_t, uint8_t*, uint32_t, uint32_t);

struct RString {
    char* data;
    uintptr_t length;
    uintptr_t capacity;
    const void* vtable;
};

struct ROptionRString {
    uint8_t tag;
    uint8_t padding[7];
    RString value;
};

struct BorrowedROptionRString {
    uint8_t tag;
    uint8_t padding[7];
    const char* data;
    uintptr_t length;
};

static_assert(sizeof(ROptionRString) == 40u);
static_assert(sizeof(BorrowedROptionRString) == 24u);

struct NativeResult {
    // Public HyperOS Broadcast and PackageManager wrappers return a 48-byte
    // RResult through x8 on launcher 4371.  This deliberately does not model
    // ActivityManagerServiceProxyImpl::broadcastIntentWithFeature: that
    // private Rust method writes only 16 bytes through x8, while an ordinary
    // 16-byte C++ aggregate would instead return in x0/x1.
    uint8_t bytes[48];
};

using PackageManagerHasSystemFeatureFn = NativeResult (*)(
        void*, const char*, size_t, uint32_t);

struct NativeI64Option {
    uint64_t tag;
    int64_t value;
};

using IntentGetActionFn = BorrowedROptionRString (*)(void*);
using IntentGetSenderPackageFn = BorrowedROptionRString (*)(void*);
using BroadcastReceiverOnReceiveFn = void (*)(void*, void*, void*);
extern "C" void MiuiHomeHyosBroadcastSendHook();
extern "C" void MiuiHomeHyosCaptureBroadcastRuntime(void* runtime);
using BroadcastSendFn = NativeResult (*)(void*, void*);
using IntentDefaultFn = void* (*)();
using IntentDropFn = void (*)(void*);
using IntentSetStringFn = void (*)(void*, ROptionRString*);
using IntentSetExtrasFn = void (*)(void*, void*);
using IntentGetExtrasFn = void* (*)(void*);
using BundleDefaultFn = void* (*)();
using BundleDropFn = void (*)(void*);
using BundleInsertBoolFn = void (*)(void*, RString*, uint8_t);
using BundleInsertI32Fn = void (*)(void*, RString*, int32_t);
using BundleInsertI64Fn = void (*)(void*, RString*, int64_t);
using BundleGetBoolFn = uint64_t (*)(void*, const char*, size_t);
using BundleGetI32Fn = uint64_t (*)(void*, const char*, size_t);
using BundleGetI64Fn = NativeI64Option (*)(void*, const char*, size_t);
using RuntimeStrongFn = void (*)(void*);
using PackageManagerGetApplicationInfoFn = NativeResult (*)(
        const char*, size_t, uint64_t);
using ApplicationInfoGetUidFn = int32_t (*)(void*);
using ApplicationInfoDropFn = void (*)(void*);

void* g_launcher_handle = nullptr;
void* g_original_motion_get_action = nullptr;
void* g_original_motion_get_action_masked = nullptr;
void* g_original_input_monitor_pilfer = nullptr;
void* g_original_gesture_stub_pointer_handler = nullptr;
void* g_original_gesture_back_touch_processor = nullptr;
void* g_original_gesture_stub_back_handler = nullptr;
void* g_original_contextual_long_press_handler = nullptr;
void* g_original_drawer_state_handler = nullptr;
void* g_dart_app_handle = nullptr;
uint8_t* g_dart_app_base = nullptr;
uint32_t g_dart_drawer_install_in_flight = 0;
uint32_t g_dart_overview_install_in_flight = 0;
uint32_t g_dart_editing_install_in_flight = 0;
__attribute__((used)) volatile uint32_t g_dart_loader_hook_state = 0;
void* g_contextual_search_invoke = nullptr;
void* g_original_broadcast_receiver_on_receive = nullptr;
void** g_broadcast_intent_with_feature_slot = nullptr;
void* g_motion_get_id = nullptr;
void* g_motion_get_down_time = nullptr;
void* g_motion_get_device_id = nullptr;
void* g_motion_get_source = nullptr;
void* g_motion_get_raw_x = nullptr;
void* g_motion_get_raw_y = nullptr;
void* g_motion_get_y = nullptr;
uint8_t* g_launcher_base = nullptr;
const miui_home_profiles::LauncherProfile* g_launcher_profile = nullptr;
miui_home_runtime_profile::ResolutionStorage g_dynamic_profile_storage{};
miui_home_runtime_profile::ResolutionStorage g_contextual_overlay_storage{};
__attribute__((used)) volatile uint32_t g_contextual_overlay_state = 0;
miui_home_dart_profile::ResolutionStorage g_dart_profile_storage{};
__attribute__((used)) miui_home_dart_profile::ResolutionDiagnostics
        g_dart_profile_diagnostics{};
const miui_home_profiles::LauncherProfile* g_resolved_dart_profile = nullptr;
__attribute__((used)) volatile uint32_t g_dart_profile_resolve_state = 0;
__attribute__((used)) volatile uint32_t g_dart_drawer_candidate_count = 0;
__attribute__((used)) volatile uint32_t g_dart_transition_candidate_count = 0;
__attribute__((used)) volatile uint32_t g_dart_overview_enter_candidate_count = 0;
__attribute__((used)) volatile uint32_t g_dart_overview_exit_candidate_count = 0;
__attribute__((used)) volatile uint32_t g_dart_editing_candidate_count = 0;
__attribute__((used)) volatile uintptr_t g_dart_drawer_resolved_offset = 0;
__attribute__((used)) volatile uintptr_t g_dart_transition_resolved_offset = 0;
__attribute__((used)) volatile uintptr_t g_dart_overview_enter_resolved_offset = 0;
__attribute__((used)) volatile uintptr_t g_dart_overview_exit_resolved_offset = 0;
__attribute__((used)) volatile uintptr_t g_dart_editing_resolved_offset = 0;
uint32_t g_native_receiver_state = 0;
int64_t g_systemui_arbiter_generation = 0;
uint32_t g_systemui_arbiter_ready = 0;
__attribute__((used)) volatile uint32_t g_contextual_search_enabled = 0;
uint32_t g_entry_reported = 0;
uint32_t g_business_hook_state = 0;
uint32_t g_arbiter_bridge_hook_state = 0;
// Specialization callbacks run post-fork. These values are consequently
// process-local in the final launcher and provide ordering evidence without
// retaining any callback-owned string pointer.
__attribute__((used)) volatile uint32_t g_hyos_specialize_count = 0;
__attribute__((used)) volatile uint32_t g_hyos_launcher_specialized = 0;
__attribute__((used)) volatile uint64_t g_hyos_lifecycle_sequence = 0;
__attribute__((used)) volatile uint64_t g_hyos_specialize_sequence = 0;
__attribute__((used)) volatile uint32_t g_launcher_library_observed_count = 0;
__attribute__((used)) volatile uint32_t
        g_launcher_library_after_specialize_count = 0;
__attribute__((used)) volatile uint64_t
        g_launcher_library_observed_sequence = 0;
__attribute__((used)) volatile uint32_t g_launcher_entry_observed_count = 0;
__attribute__((used)) volatile uint32_t
        g_launcher_entry_after_specialize_count = 0;
__attribute__((used)) volatile uint64_t g_launcher_entry_observed_sequence = 0;
// Readable through /proc/<pid>/mem even when launcher startup evicts logcat.
// send_state values are documented next to SendNativeBroadcast().
__attribute__((used)) uint32_t g_native_broadcast_send_count = 0;
__attribute__((used)) uint32_t g_native_broadcast_send_kind = 0;
__attribute__((used)) uint32_t g_native_broadcast_send_state = 0;
__attribute__((used)) uint32_t g_native_broadcast_result_tag = 0;
__attribute__((used)) uint32_t
        g_native_broadcast_options_consumed = 0;
__attribute__((used)) uint32_t g_arbiter_query_attempts = 0;
__attribute__((used)) volatile uint32_t g_arbiter_state_marked_count = 0;
__attribute__((used)) volatile uint32_t g_arbiter_state_passthrough_count = 0;
__attribute__((used)) volatile uint32_t g_accepted_processor_down_count = 0;
__attribute__((used)) volatile uint32_t g_accepted_processor_publish_count = 0;
__attribute__((used)) volatile uint32_t g_gesture_processor_suppressed_count = 0;
__attribute__((used)) volatile uint32_t g_gesture_processor_entry_count = 0;
__attribute__((used)) volatile uint32_t g_inner_gesture_type_last = 0;
__attribute__((used)) volatile uint32_t g_inner_gesture_type_1_count = 0;
__attribute__((used)) volatile uint32_t g_inner_gesture_type_2_count = 0;
__attribute__((used)) volatile uint32_t g_outer_down_post_type_last = 0;
__attribute__((used)) volatile uint32_t g_outer_down_post_type_0_count = 0;
__attribute__((used)) volatile uint32_t g_outer_down_post_type_1_count = 0;
__attribute__((used)) volatile uint32_t g_outer_down_post_type_2_count = 0;
__attribute__((used)) volatile uint32_t g_outer_down_post_type_3_count = 0;
__attribute__((used)) volatile uint32_t g_enable_systemui_ownership = 1;
__attribute__((used)) volatile uint32_t g_stub_back_handler_count = 0;
__attribute__((used)) volatile uint32_t g_stub_back_action_last = 0xffffffffu;
__attribute__((used)) volatile uint32_t g_stub_back_down_count = 0;
__attribute__((used)) volatile uint32_t g_stub_back_move_count = 0;
__attribute__((used)) volatile uint32_t g_stub_back_up_count = 0;
__attribute__((used)) volatile uint32_t g_stub_back_cancel_count = 0;
__attribute__((used)) volatile uint32_t g_stub_back_edge_last = 0xffffffffu;
__attribute__((used)) volatile uint32_t
        g_gesture_processor_boundary_return_count = 0;
__attribute__((used)) volatile uint32_t g_pilfer_hook_count = 0;
__attribute__((used)) volatile uint32_t
        g_owned_stream_pilfer_suppressed_count = 0;
__attribute__((used)) volatile uint32_t g_motion_down_capture_count = 0;
__attribute__((used)) volatile uint32_t g_contextual_feature_hook_state = 0;
__attribute__((used)) volatile uint32_t g_contextual_feature_query_count = 0;
__attribute__((used)) volatile uint32_t g_contextual_feature_override_count = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_long_press_hook_state = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_long_press_trigger_count = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_long_press_passthrough_count = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_search_invoke_count = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_search_invoke_last_result = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_long_press_upward_cancel_count = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_motion_snapshot_valid = 0;
__attribute__((used)) volatile int64_t
        g_contextual_motion_snapshot_down_time = 0;
__attribute__((used)) volatile int32_t
        g_contextual_motion_snapshot_device_id = 0;
__attribute__((used)) volatile int32_t
        g_contextual_motion_snapshot_source = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_motion_snapshot_down_y_bits = 0;
__attribute__((used)) volatile uint32_t
        g_contextual_motion_snapshot_current_y_bits = 0;
// Drawer visibility encoding: 0 unknown, 1 Home/not-all-apps, 2 ALL_APPS.
__attribute__((used)) volatile uint32_t g_drawer_state_hook_state = 0;
__attribute__((used)) volatile uint32_t g_drawer_published_state = 0;
__attribute__((used)) volatile int64_t g_drawer_published_generation = 0;
__attribute__((used)) volatile uint64_t g_drawer_published_owner_epoch = 0;
__attribute__((used)) volatile uint32_t g_drawer_state_publish_count = 0;
__attribute__((used)) volatile uint32_t g_overview_state_hook_state = 0;
__attribute__((used)) volatile uint32_t g_overview_published_state = 0;
__attribute__((used)) volatile int64_t g_overview_published_generation = 0;
__attribute__((used)) volatile uint64_t g_overview_published_owner_epoch = 0;
__attribute__((used)) volatile uint32_t g_overview_state_publish_count = 0;
// Home-surface encoding: 0 unknown, 1 inactive, 2 native Home child callback.
// Keep the legacy editing readiness group and retirement counters as lifecycle keys.
__attribute__((used)) volatile uint32_t g_editing_state_hook_state = 0;
__attribute__((used)) volatile uint32_t g_editing_published_state = 0;
__attribute__((used)) volatile int64_t g_editing_published_generation = 0;
__attribute__((used)) volatile uint64_t g_editing_published_owner_epoch = 0;
__attribute__((used)) volatile uint32_t g_editing_state_publish_count = 0;
__attribute__((used)) volatile uint32_t g_editing_dart_repair_attempt_count = 0;
__attribute__((used)) volatile uint32_t g_editing_dart_repair_success_count = 0;
__attribute__((used)) volatile uint32_t g_editing_dart_repair_failure_count = 0;
__attribute__((used)) volatile uint32_t g_editing_dart_repair_stage = 0;
__attribute__((used)) volatile uint32_t g_overview_dart_repair_attempt_count = 0;
__attribute__((used)) volatile uint32_t g_overview_dart_repair_success_count = 0;
__attribute__((used)) volatile uint32_t g_overview_dart_repair_failure_count = 0;
__attribute__((used)) volatile uint32_t g_overview_dart_repair_stage = 0;
// XiaoAi visibility encoding: 0 unknown, 1 hidden, 2 visible.
__attribute__((used)) volatile uint32_t g_xiaoai_state_hook_state = 0;
__attribute__((used)) volatile uint32_t g_xiaoai_state_observed = 0;
__attribute__((used)) volatile uint32_t g_xiaoai_published_state = 0;
__attribute__((used)) volatile int64_t g_xiaoai_published_generation = 0;
__attribute__((used)) volatile uint32_t g_xiaoai_state_observe_count = 0;
__attribute__((used)) volatile uint32_t g_xiaoai_state_publish_count = 0;
__attribute__((used)) volatile uint32_t g_dart_drawer_repair_attempt_count = 0;
__attribute__((used)) volatile uint32_t g_dart_drawer_repair_success_count = 0;
__attribute__((used)) volatile uint32_t g_dart_drawer_repair_failure_count = 0;
__attribute__((used)) volatile uint32_t g_dart_drawer_repair_stage = 0;
uint32_t g_drawer_state_publish_in_flight = 0;
uint32_t g_overview_state_publish_in_flight = 0;
uint32_t g_editing_state_publish_in_flight = 0;
uint32_t g_xiaoai_state_publish_in_flight = 0;
__attribute__((used)) volatile uint32_t g_business_repair_attempt_count = 0;
__attribute__((used)) volatile uint32_t g_business_repair_success_count = 0;
__attribute__((used)) volatile uint32_t g_business_repair_failure_count = 0;
__attribute__((used)) volatile uint32_t g_business_repair_stage = 0;
__attribute__((used)) volatile uint32_t g_runtime_status_query_count = 0;
__attribute__((used)) volatile uint32_t g_runtime_status_response_count = 0;
__attribute__((used)) volatile uint64_t g_runtime_status_last_nonce = 0;
__attribute__((used)) volatile uint32_t g_runtime_status_last_state = 0;
// Captured from the private Rust send_broadcast ABI (the Shared holder in x1).  The launcher no
// longer publishes this singleton through a stable global, so retain the
// live object observed on a legitimate native send and reuse it with the
// existing strong-count guard.
__attribute__((used)) void* g_last_broadcast_runtime = nullptr;
extern "C" __attribute__((used, visibility("default")))
        void* g_original_broadcast_send = nullptr;
// Runtime profile state is the numeric ResolveStage value. It is published
// last, after the immutable offsets and candidate counts below.
__attribute__((used)) volatile uint32_t g_dynamic_profile_state = 0;
__attribute__((used)) volatile uint32_t g_dynamic_side_candidate_count = 0;
__attribute__((used)) volatile uint32_t
        g_dynamic_runtime_confirmation_count = 0;
__attribute__((used)) volatile uint32_t g_dynamic_rstring_candidate_count = 0;
__attribute__((used)) volatile uint32_t
        g_dynamic_contextual_support_candidate_count = 0;
__attribute__((used)) volatile uint32_t
        g_dynamic_contextual_invoke_candidate_count = 0;
__attribute__((used)) volatile uint32_t
        g_dynamic_contextual_long_press_candidate_count = 0;
__attribute__((used)) volatile uint32_t
        g_dynamic_contextual_resolved = 0;
__attribute__((used)) volatile uint32_t
        g_dynamic_xiaoai_candidate_count = 0;
__attribute__((used)) volatile uint32_t g_dynamic_xiaoai_resolved = 0;
__attribute__((used)) volatile uintptr_t g_dynamic_side_handler_offset = 0;
__attribute__((used)) volatile uintptr_t g_dynamic_runtime_pointer_offset = 0;
__attribute__((used)) volatile uintptr_t g_dynamic_runtime_state_offset = 0;
__attribute__((used)) volatile uintptr_t g_dynamic_rstring_vtable_offset = 0;
__attribute__((used)) volatile uintptr_t
        g_dynamic_contextual_search_invoke_offset = 0;
__attribute__((used)) volatile uintptr_t
        g_dynamic_contextual_long_press_handler_offset = 0;
__attribute__((used)) volatile uintptr_t
        g_dynamic_xiaoai_bundle_bool_return_offset = 0;

constexpr uint32_t kLauncherInputSlotCount = 4u;
struct LauncherInputHookSlot {
    uintptr_t base;
    const miui_home_profiles::LauncherProfile* profile;
    void* original_action;
    void* original_action_masked;
    void* original_pilfer;
    void* original_has_system_feature;
    void* original_bundle_get_boolean;
    void* original_dlopen;
    uint32_t state;
    uint32_t contextual_search_state;
    uint32_t xiaoai_state;
};
__attribute__((used)) volatile uint32_t g_launcher_input_slot_count = 0;
__attribute__((used)) LauncherInputHookSlot
        g_launcher_input_slots[kLauncherInputSlotCount]{};

struct PendingDownIdentity {
    int32_t event_id;
    int64_t down_time;
    int32_t device_id;
    int32_t source;
    uint32_t edge;
    float raw_y;
    bool valid;
};

struct OwnedBackStreamIdentity {
    uintptr_t monitor;
    uintptr_t motion_event;
    int64_t generation;
    PendingDownIdentity down;
    bool valid;
};

thread_local PendingDownIdentity g_pending_down{};
thread_local bool g_systemui_owns_back_stream = false;
thread_local OwnedBackStreamIdentity g_owned_back_stream{};
thread_local uintptr_t g_last_motion_event = 0u;
thread_local uintptr_t g_last_dart_maintenance_down = 0u;

template <typename T>
T AtomicLoad(const T* value) {
    return __atomic_load_n(value, __ATOMIC_ACQUIRE);
}

template <typename T>
void AtomicStore(T* target, T value) {
    __atomic_store_n(target, value, __ATOMIC_RELEASE);
}

template <typename T>
void AtomicStore(volatile T* target, T value) {
    __atomic_store_n(target, value, __ATOMIC_RELEASE);
}

uint64_t NextHyosLifecycleSequence() {
    return __atomic_add_fetch(&g_hyos_lifecycle_sequence, uint64_t{1},
                              __ATOMIC_ACQ_REL);
}

void ResetDartStateOwnerForProcess(int32_t process_pid) {
    const int32_t inherited_event_fd =
            AtomicLoad(&g_dart_state_publish_event_fd);
    if (inherited_event_fd >= 0) close(inherited_event_fd);
    timespec now{};
    const uint64_t inherited_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    uint64_t owner_epoch = inherited_epoch + 1u;
    if (clock_gettime(CLOCK_BOOTTIME, &now) == 0 && now.tv_sec >= 0 &&
            now.tv_nsec >= 0) {
        const uint64_t clock_epoch = static_cast<uint64_t>(now.tv_sec) *
                uint64_t{1000000000} + static_cast<uint64_t>(now.tv_nsec);
        if (clock_epoch > owner_epoch) owner_epoch = clock_epoch;
    }
    if (owner_epoch == 0u) owner_epoch = 1u;
    __atomic_store_n(&g_dart_state_owner_epoch, owner_epoch,
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_drawer_state_observation, uint64_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_overview_state_observation, uint64_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_editing_state_observation, uint64_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_publish_pending, kDartOwnerPending,
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_drawer_active_count, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_overview_enter_active_count, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_overview_exit_active_count, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_editing_active_count, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_drawer_retiring, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_overview_enter_retiring, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_overview_exit_retiring, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_editing_retiring, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_remap_pending_mask, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_remap_repair_in_flight, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_publish_event_fd, int32_t{-1},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_publish_callback_epoch, uint64_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_publish_failure_epoch, uint64_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_owner_published_epoch, uint64_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_owner_published_generation, int64_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_publish_dispatcher_state, uint32_t{0},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_owner_pid, process_pid, __ATOMIC_RELEASE);
    g_last_dart_maintenance_down = 0u;
}

void ResetDartStateOwnerAfterFork() {
    ResetDartStateOwnerForProcess(static_cast<int32_t>(getpid()));
}

bool EnsureDartStateOwnerForCurrentProcess() {
    const int32_t process_pid = static_cast<int32_t>(getpid());
    if (process_pid <= 0) return false;
    int32_t owner_pid = AtomicLoad(&g_dart_state_owner_pid);
    if (owner_pid == process_pid) return true;

    for (;;) {
        if (owner_pid == process_pid) return true;
        if (owner_pid == -process_pid) {
            sched_yield();
            owner_pid = AtomicLoad(&g_dart_state_owner_pid);
            continue;
        }
        int32_t expected = owner_pid;
        if (__atomic_compare_exchange_n(
                    &g_dart_state_owner_pid, &expected, -process_pid, false,
                    __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            break;
        }
        owner_pid = expected;
    }
    ResetDartStateOwnerForProcess(process_pid);
    return true;
}

void RecordFirstLifecycleSequence(volatile uint64_t* target,
                                  uint64_t sequence) {
    uint64_t expected = 0u;
    __atomic_compare_exchange_n(target, &expected, sequence, false,
                                __ATOMIC_RELEASE, __ATOMIC_RELAXED);
}

void Log(int priority, const char* message) {
    __android_log_write(priority, kLogTag, message);
}

uint32_t FloatBits(float value) {
    uint32_t bits = 0;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

float BitsFloat(uint32_t bits) {
    float value = 0.0f;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

float ReadMotionY(void* event) {
    if (event == nullptr) return 0.0f;
    MotionEventFloatFn get_raw_y = reinterpret_cast<MotionEventFloatFn>(
            AtomicLoad(&g_motion_get_raw_y));
    const float raw_y = get_raw_y == nullptr ? 0.0f : get_raw_y(event);
    // HyperOS 5436 exposes the raw-Y import but returns the zero default for
    // the native MotionEvent wrapper used by the launcher long-press path.
    // Its ordinary local-Y leaf is the same event coordinate in this
    // non-transformed launcher window, so use it only when raw-Y is absent or
    // reports that zero sentinel.
    if (raw_y != 0.0f) return raw_y;
    MotionEventFloatFn get_y = reinterpret_cast<MotionEventFloatFn>(
            AtomicLoad(&g_motion_get_y));
    return get_y == nullptr ? raw_y : get_y(event);
}

constexpr size_t ConstStringLength(const char* value) {
    size_t length = 0u;
    if (value == nullptr) return length;
    while (value[length] != '\0') ++length;
    return length;
}

constexpr bool StartsWith(const char* value, const char* prefix) {
    if (value == nullptr || prefix == nullptr) return false;
    for (size_t index = 0u; prefix[index] != '\0'; ++index) {
        if (value[index] != prefix[index]) return false;
    }
    return true;
}

constexpr bool StringsEqual(const char* left, const char* right) {
    if (left == nullptr || right == nullptr) return false;
    size_t index = 0u;
    while (left[index] != '\0' && right[index] != '\0') {
        if (left[index] != right[index]) return false;
        ++index;
    }
    return left[index] == right[index];
}

constexpr bool EndsWith(const char* value, const char* suffix) {
    if (value == nullptr || suffix == nullptr) return false;
    const size_t value_length = ConstStringLength(value);
    const size_t suffix_length = ConstStringLength(suffix);
    if (suffix_length > value_length) return false;
    const size_t start = value_length - suffix_length;
    for (size_t index = 0u; index < suffix_length; ++index) {
        if (value[start + index] != suffix[index]) return false;
    }
    return true;
}

constexpr bool IsLauncherLibraryPath(const char* path) {
    if (path == nullptr) return false;
    if (StringsEqual(path, "libapp_launcher.so")) return true;
    if (StartsWith(path, "/data/app/") &&
            EndsWith(path, kDataLauncherLibraryTail)) {
        return true;
    }
    return StartsWith(path, "/product/priv-app/MiuiHome/") &&
            EndsWith(path, kSystemLauncherLibraryTail);
}

constexpr bool IsDartLibraryPath(const char* path) {
    return StringsEqual(path, kDartLibraryName) ||
            EndsWith(path, kDartLibraryTail);
}

static_assert(IsLauncherLibraryPath(
        "/data/app/~~token/com.miui.home-token/base.apk!/lib/arm64-v8a/"
        "libapp_launcher.so"));
static_assert(IsLauncherLibraryPath(
        "/product/priv-app/MiuiHome/MiuiHome.apk!/lib/arm64-v8a/"
        "libapp_launcher.so"));
static_assert(!IsLauncherLibraryPath(
        "/data/app/token/base.apk!/lib/armeabi-v7a/libapp_launcher.so"));
static_assert(!IsLauncherLibraryPath(
        "/data/app/token/base.apk!/lib/arm64-v8a/libnot_launcher.so"));
static_assert(!IsLauncherLibraryPath(
        "/data/local/tmp/base.apk!/lib/arm64-v8a/libapp_launcher.so"));
static_assert(IsLauncherLibraryPath("libapp_launcher.so"));
static_assert(StringsEqual("com.miui.home", kLauncherProcessName));
static_assert(!StringsEqual("com.miui.home:remote", kLauncherProcessName));
static_assert(!StringsEqual("com.evil.home", kLauncherProcessName));

bool IsLauncherProcess() {
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char command_line[64]{};
    ssize_t result;
    do {
        result = read(fd, command_line, sizeof(command_line));
    } while (result < 0 && errno == EINTR);
    close(fd);
    if (result <= 0) return false;

    constexpr size_t expected_length =
            ConstStringLength(kLauncherProcessName);
    return static_cast<size_t>(result) > expected_length &&
            command_line[expected_length] == '\0' &&
            StringsEqual(command_line, kLauncherProcessName);
}

bool IsHyosSpawnerProcessFamily() {
    char executable[128]{};
    const ssize_t length = readlink("/proc/self/exe", executable,
                                    sizeof(executable) - 1u);
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(executable)) {
        return false;
    }
    executable[length] = '\0';
    return StringsEqual(executable, kSpawnerPath);
}

// LSPosed's HYOS entry is initialized in the root spawner (whose cmdline is
// currently `usap64`) before it forks the package process.  The old check
// required the spawner itself to already advertise com.miui.home, which is no
// longer true after the launcher update.  Keep the executable identity as the
// hard boundary and accept the actual launcher child as well.
bool IsLauncherHookProcess() {
    return IsLauncherProcess() || IsHyosSpawnerProcessFamily();
}

void MarkLsposedLauncherSpecialized() {
    if (__atomic_exchange_n(&g_hyos_launcher_specialized, uint32_t{1},
                            __ATOMIC_ACQ_REL) != 0u) {
        return;
    }
    __atomic_fetch_add(&g_hyos_specialize_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    RecordFirstLifecycleSequence(&g_hyos_specialize_sequence,
                                 NextHyosLifecycleSequence());
}

constexpr char kSystemUiPackage[] = "com.android.systemui";
constexpr char kArbiterStateAction[] =
        "dev.codex.miuibackgesturehook.action.SYSTEMUI_INPUT_ARBITER_STATE";
constexpr char kArbiterStateCarrierAction[] = "com.android.systemui.fsgesture";
constexpr char kContextualSearchEnabledExtra[] =
        "contextual_search_enabled";
constexpr char kPlatformContextualSearchFeature[] =
        "android.software.contextualsearch";
constexpr char kGoogleContextualSearchFeature[] =
        "com.google.android.feature.CONTEXTUAL_SEARCH";
constexpr char kArbiterQueryAction[] =
        "dev.codex.miuibackgesturehook.action.MIUI_HOME_INPUT_ARBITER_QUERY";
constexpr char kContextualSearchTriggeredAction[] =
        "dev.codex.miuibackgesturehook.action.CONTEXTUAL_SEARCH_TRIGGERED";
constexpr char kRuntimeStatusResponseAction[] =
        "dev.codex.miuibackgesturehook.action.RUNTIME_STATUS_REPLY";
constexpr char kRuntimeStatusQueryExtra[] = "status_query";
constexpr char kRuntimeStatusNonceExtra[] = "status_nonce";
constexpr char kAcceptedStateAction[] =
        "dev.codex.miuibackgesturehook.action.MIUI_OVERVIEW_STATE_CHANGE";
constexpr char kLauncherStateOwnerEpochExtra[] =
        "launcher_state_owner_epoch";

bool MatchesCode(const uint8_t* base, uintptr_t offset,
                 const uint8_t* expected, size_t expected_size) {
    return base != nullptr && expected != nullptr && expected_size != 0u &&
            memcmp(base + offset, expected, expected_size) == 0;
}

bool DartMappedRangeHasFlags(const uint8_t* base, uintptr_t offset,
                             size_t size, uint32_t required_flags) {
    if (base == nullptr || offset == 0u || size == 0u ||
            offset > UINTPTR_MAX - size) {
        return false;
    }
    const auto* header = reinterpret_cast<const Elf64_Ehdr*>(base);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
            header->e_ident[EI_CLASS] != ELFCLASS64 ||
            header->e_ident[EI_DATA] != ELFDATA2LSB ||
            header->e_type != ET_DYN || header->e_machine != EM_AARCH64 ||
            header->e_phentsize != sizeof(Elf64_Phdr) ||
            header->e_phnum == 0u || header->e_phnum > 128u ||
            header->e_phoff > 0x10000u) {
        return false;
    }
    const auto* programs = reinterpret_cast<const Elf64_Phdr*>(
            base + header->e_phoff);
    const uintptr_t end = offset + size;
    for (uint16_t index = 0u; index < header->e_phnum; ++index) {
        const Elf64_Phdr& program = programs[index];
        if (program.p_type != PT_LOAD ||
                (program.p_flags & required_flags) != required_flags ||
                program.p_vaddr > UINTPTR_MAX - program.p_memsz) {
            continue;
        }
        const uintptr_t segment_start =
                static_cast<uintptr_t>(program.p_vaddr);
        const uintptr_t segment_end = segment_start +
                static_cast<uintptr_t>(program.p_memsz);
        if (offset >= segment_start && end <= segment_end) return true;
    }
    return false;
}

bool DartBlTargets(const uint8_t* base, uintptr_t instruction_offset,
                   uintptr_t expected_target) {
    if (!DartMappedRangeHasFlags(base, instruction_offset, sizeof(uint32_t),
                                 PF_R | PF_X)) {
        return false;
    }
    uint32_t instruction = 0u;
    memcpy(&instruction, base + instruction_offset, sizeof(instruction));
    if ((instruction & 0xfc000000u) != 0x94000000u) return false;
    int64_t immediate = static_cast<int64_t>(instruction & 0x03ffffffu);
    if ((immediate & (int64_t{1} << 25u)) != 0) {
        immediate -= int64_t{1} << 26u;
    }
    const int64_t target = static_cast<int64_t>(instruction_offset) +
            immediate * 4;
    return target >= 0 && static_cast<uintptr_t>(target) == expected_target;
}

bool MatchesLauncherProfile(
        const uint8_t* base, void* app_entry_point,
        const miui_home_profiles::LauncherProfile& profile) {
    if (base == nullptr || app_entry_point != base + profile.entry_offset ||
            profile.identity_fingerprints == nullptr ||
            profile.identity_fingerprint_count == 0u) {
        return false;
    }
    for (size_t index = 0u; index < profile.identity_fingerprint_count;
         ++index) {
        const auto& fingerprint = profile.identity_fingerprints[index];
        if (!MatchesCode(base, fingerprint.offset, fingerprint.bytes,
                         fingerprint.size)) {
            return false;
        }
    }
    return true;
}

const miui_home_profiles::LauncherProfile* ResolveLauncherProfile(
        void* app_entry_point, uint8_t** resolved_base) {
    Dl_info info{};
    if (resolved_base != nullptr) *resolved_base = nullptr;
    if (app_entry_point == nullptr || dladdr(app_entry_point, &info) == 0 ||
            info.dli_fbase == nullptr) {
        return nullptr;
    }
    auto* base = static_cast<uint8_t*>(info.dli_fbase);
    const miui_home_profiles::LauncherProfile* matched = nullptr;
    for (const auto* profile : miui_home_profiles::kProfiles) {
        if (profile != nullptr &&
                MatchesLauncherProfile(base, app_entry_point, *profile)) {
            if (matched != nullptr) return nullptr;
            matched = profile;
        }
    }
    if (matched != nullptr) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                "launcher static profile matched: id=%s entry=0x%zx base=%p",
                matched->id, matched->entry_offset, base);

        // Static profiles intentionally contain only immutable launcher
        // identity and the side boundary. New Xiaomi builds may retain the
        // contextual-search graph while changing its offsets, so resolve the
        // optional graph independently and overlay it without weakening the
        // already validated static profile.
        const bool contextual_missing =
                matched->contextual_long_press_handler_offset == 0u &&
                matched->contextual_search_invoke_offset == 0u;
        if (contextual_missing) {
            uint32_t expected_overlay = 0u;
            if (__atomic_compare_exchange_n(
                        &g_contextual_overlay_state, &expected_overlay,
                        uint32_t{1}, false, __ATOMIC_ACQ_REL,
                        __ATOMIC_ACQUIRE)) {
                miui_home_runtime_profile::ResolutionDiagnostics diagnostics{};
                const bool overlay =
                        miui_home_runtime_profile::ResolveContextualSearchOverlay(
                                base, matched, &g_contextual_overlay_storage,
                                &diagnostics);
                __atomic_store_n(&g_dynamic_contextual_support_candidate_count,
                        diagnostics.contextual_support_candidate_count,
                        __ATOMIC_RELAXED);
                __atomic_store_n(&g_dynamic_contextual_invoke_candidate_count,
                        diagnostics.contextual_invoke_candidate_count,
                        __ATOMIC_RELAXED);
                __atomic_store_n(
                        &g_dynamic_contextual_long_press_candidate_count,
                        diagnostics.contextual_long_press_candidate_count,
                        __ATOMIC_RELAXED);
                __atomic_store_n(&g_dynamic_contextual_resolved,
                        diagnostics.contextual_resolved, __ATOMIC_RELAXED);
                __atomic_store_n(&g_dynamic_contextual_search_invoke_offset,
                        diagnostics.contextual_search_invoke_offset,
                        __ATOMIC_RELAXED);
                __atomic_store_n(
                        &g_dynamic_contextual_long_press_handler_offset,
                        diagnostics.contextual_long_press_handler_offset,
                        __ATOMIC_RELAXED);
                __atomic_store_n(&g_contextual_overlay_state,
                        static_cast<uint32_t>(diagnostics.stage),
                        __ATOMIC_RELEASE);
                if (overlay) {
                    matched = &g_contextual_overlay_storage.profile;
                    Log(ANDROID_LOG_INFO,
                        "resolved contextual-search overlay for static launcher profile");
                } else {
                    __android_log_print(
                            ANDROID_LOG_WARN, kLogTag,
                            "contextual-search overlay unavailable: stage=%u support=%u invoke=%u long_press=%u",
                            static_cast<uint32_t>(diagnostics.stage),
                            diagnostics.contextual_support_candidate_count,
                            diagnostics.contextual_invoke_candidate_count,
                            diagnostics.contextual_long_press_candidate_count);
                }
            } else if (__atomic_load_n(&g_contextual_overlay_state,
                                       __ATOMIC_ACQUIRE) ==
                    static_cast<uint32_t>(
                            miui_home_runtime_profile::ResolveStage::kComplete)) {
                matched = &g_contextual_overlay_storage.profile;
            }
        }
    }
    const auto* current = AtomicLoad(&g_launcher_profile);
    if (matched == nullptr && current == nullptr) {
        uint32_t expected_state = static_cast<uint32_t>(
                miui_home_runtime_profile::ResolveStage::kNotStarted);
        if (__atomic_compare_exchange_n(
                    &g_dynamic_profile_state, &expected_state,
                    static_cast<uint32_t>(
                            miui_home_runtime_profile::ResolveStage::
                                    kParsingElf),
                    false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            miui_home_runtime_profile::ResolutionDiagnostics diagnostics{};
            const bool resolved =
                    miui_home_runtime_profile::ResolveSideBoundaryProfile(
                            base, app_entry_point,
                            &g_dynamic_profile_storage, &diagnostics);
            __atomic_store_n(&g_dynamic_side_candidate_count,
                    diagnostics.side_candidate_count, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_runtime_confirmation_count,
                    diagnostics.runtime_confirmation_count,
                    __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_rstring_candidate_count,
                    diagnostics.rstring_candidate_count, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_contextual_support_candidate_count,
                    diagnostics.contextual_support_candidate_count,
                    __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_contextual_invoke_candidate_count,
                    diagnostics.contextual_invoke_candidate_count,
                    __ATOMIC_RELAXED);
            __atomic_store_n(
                    &g_dynamic_contextual_long_press_candidate_count,
                    diagnostics.contextual_long_press_candidate_count,
                    __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_contextual_resolved,
                    diagnostics.contextual_resolved, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_xiaoai_candidate_count,
                    diagnostics.xiaoai_candidate_count, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_xiaoai_resolved,
                    diagnostics.xiaoai_resolved, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_side_handler_offset,
                    diagnostics.side_handler_offset, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_runtime_pointer_offset,
                    diagnostics.runtime_pointer_offset, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_runtime_state_offset,
                    diagnostics.runtime_state_offset, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_rstring_vtable_offset,
                    diagnostics.rstring_vtable_offset, __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_contextual_search_invoke_offset,
                    diagnostics.contextual_search_invoke_offset,
                    __ATOMIC_RELAXED);
            __atomic_store_n(
                    &g_dynamic_contextual_long_press_handler_offset,
                    diagnostics.contextual_long_press_handler_offset,
                    __ATOMIC_RELAXED);
            __atomic_store_n(
                    &g_dynamic_xiaoai_bundle_bool_return_offset,
                    diagnostics.xiaoai_bundle_bool_return_offset,
                    __ATOMIC_RELAXED);
            __atomic_store_n(&g_dynamic_profile_state,
                    static_cast<uint32_t>(diagnostics.stage),
                    __ATOMIC_RELEASE);
            if (resolved) {
                matched = &g_dynamic_profile_storage.profile;
                Log(ANDROID_LOG_INFO,
                    "resolved unique runtime side-boundary launcher profile");
            } else {
                __android_log_print(
                        ANDROID_LOG_ERROR, kLogTag,
                        "runtime side-boundary launcher profile rejected: "
                        "stage=%u side=%u runtime=%u rstring=%u "
                        "contextual=%u/%u/%u xiaoai=%u",
                        static_cast<uint32_t>(diagnostics.stage),
                        diagnostics.side_candidate_count,
                        diagnostics.runtime_confirmation_count,
                        diagnostics.rstring_candidate_count,
                        diagnostics.contextual_support_candidate_count,
                        diagnostics.contextual_invoke_candidate_count,
                        diagnostics.contextual_long_press_candidate_count,
                        diagnostics.xiaoai_candidate_count);
            }
        }
    }
    if (matched == nullptr && current == &g_dynamic_profile_storage.profile &&
            __atomic_load_n(&g_dynamic_profile_state, __ATOMIC_ACQUIRE) ==
                    static_cast<uint32_t>(
                            miui_home_runtime_profile::ResolveStage::
                                    kComplete) &&
            MatchesLauncherProfile(base, app_entry_point,
                                   g_dynamic_profile_storage.profile)) {
        matched = &g_dynamic_profile_storage.profile;
    }
    if (matched == nullptr || (current != nullptr && current != matched)) {
        return nullptr;
    }
    if (current == nullptr) AtomicStore(&g_launcher_profile, matched);
    if (resolved_base != nullptr) *resolved_base = base;
    return matched;
}

const miui_home_profiles::LauncherProfile* CurrentLauncherProfile() {
    return AtomicLoad(&g_launcher_profile);
}

bool NeedsDartFeatureResolution(
        const miui_home_profiles::LauncherProfile* profile) {
    return profile != nullptr &&
            profile->business_topology ==
                    miui_home_profiles::BusinessHookTopology::
                            kSideBoundaryOnly &&
            (StringsEqual(profile->id, "runtime-side-v1") ||
             (
            profile->drawer_state_handler_offset == 0u &&
            profile->drawer_state_handler_prologue == nullptr &&
            profile->drawer_state_handler_prologue_size == 0u));
}

const miui_home_profiles::LauncherProfile* CurrentDartFeatureProfile() {
    const auto* resolved = AtomicLoad(&g_resolved_dart_profile);
    if (resolved != nullptr) return resolved;
    const auto* launcher = CurrentLauncherProfile();
    return launcher != nullptr &&
                    launcher->dart_drawer_progress_end_offset != 0u
            ? launcher : nullptr;
}

bool HandleRuntimeStatusQuery(void* intent);
void PublishDrawerStateForCurrentGeneration();
void PublishOverviewStateForCurrentGeneration();
void PublishHomeSurfaceStateForCurrentGeneration();
void PublishPendingDartStates(uint64_t owner_epoch);
bool EnsureDartStatePublisherThread();
bool WakeDartStatePublisher();
void PublishXiaoAiStateForCurrentGeneration();
bool TryInstallDartDrawerStateHook(
        void* dart_handle,
        const miui_home_profiles::LauncherProfile* profile);
bool TryInstallDartOverviewStateHook(
        void* dart_handle,
        const miui_home_profiles::LauncherProfile* profile);
bool TryInstallDartEditingStateHook(
        void* dart_handle,
        const miui_home_profiles::LauncherProfile* profile);
bool InstallDrawerStateHook(
        uint8_t* base,
        const miui_home_profiles::LauncherProfile* profile);
void TryInstallLoadedDartDrawerStateHook(
        const miui_home_profiles::LauncherProfile* profile);
const miui_home_profiles::LauncherProfile* ResolveDartFeatureProfile(
        void* dart_handle, bool retry_rejected = false);
void TryResolveAndInstallLoadedDartProfile(bool retry_rejected = false);
void RepairDartDrawerStateHookIfRemapped(
        const miui_home_profiles::LauncherProfile* profile,
        bool remap_detected);
void RepairDartOverviewStateHookIfRemapped(
        const miui_home_profiles::LauncherProfile* profile,
        uint32_t remap_mask);
void RepairDartEditingStateHookIfRemapped(
        const miui_home_profiles::LauncherProfile* profile,
        bool remap_detected);
uint32_t DetectDartStateHookRemapMask(
        const miui_home_profiles::LauncherProfile* profile);
bool PrepareDartStateHookRetirement(uint32_t remap_mask);
void FinishDartStateHookRetirement(uint32_t remap_mask);
void InvalidateDartStateOwnerForRemap();

bool IsExactCallbackName(const char* value, size_t length,
                         const char* expected) {
    if (value == nullptr || expected == nullptr) return false;
    const size_t expected_length = ConstStringLength(expected);
    return length == expected_length &&
            memcmp(value, expected, expected_length) == 0;
}

bool MakeOwnedRString(const char* source, RString* output) {
    const auto* profile = CurrentLauncherProfile();
    if (source == nullptr || output == nullptr || g_launcher_base == nullptr ||
            profile == nullptr || profile->rstring_vtable_offset == 0u) {
        return false;
    }
    const size_t length = ConstStringLength(source);
    char* data = static_cast<char*>(malloc(length == 0u ? 1u : length));
    if (data == nullptr) return false;
    if (length != 0u) memcpy(data, source, length);
    output->data = data;
    output->length = length;
    output->capacity = length;
    output->vtable = g_launcher_base + profile->rstring_vtable_offset;
    return true;
}

bool MakeOwnedROptionString(const char* source, ROptionRString* output) {
    if (output == nullptr) return false;
    memset(output, 0, sizeof(*output));
    return MakeOwnedRString(source, &output->value);
}

template <typename T>
T ResolveLauncherSymbol(const char* symbol) {
    void* handle = AtomicLoad(&g_launcher_handle);
    return handle == nullptr ? nullptr : reinterpret_cast<T>(dlsym(handle, symbol));
}

bool IsNativeSuccess(const NativeResult& result) {
    return (result.bytes[0] & uint8_t{1}) == 0u;
}

bool ReadNativeBool(void* bundle, const char* key, bool* output) {
    BundleGetBoolFn get = ResolveLauncherSymbol<BundleGetBoolFn>(
            "Bundle_get_boolean");
    if (bundle == nullptr || key == nullptr || output == nullptr || get == nullptr) {
        return false;
    }
    const uint64_t encoded = get(bundle, key, ConstStringLength(key));
    if ((encoded & uint64_t{1}) != 0u) return false;
    *output = ((encoded >> 8u) & uint64_t{1}) != 0u;
    return true;
}

bool ReadNativeI32(void* bundle, const char* key, int32_t* output) {
    BundleGetI32Fn get = ResolveLauncherSymbol<BundleGetI32Fn>("Bundle_get_i32");
    if (bundle == nullptr || key == nullptr || output == nullptr || get == nullptr) {
        return false;
    }
    const uint64_t encoded = get(bundle, key, ConstStringLength(key));
    if ((encoded & uint64_t{1}) != 0u) return false;
    *output = static_cast<int32_t>(encoded >> 32u);
    return true;
}

bool ReadNativeI64(void* bundle, const char* key, int64_t* output) {
    BundleGetI64Fn get = ResolveLauncherSymbol<BundleGetI64Fn>("Bundle_get_i64");
    if (bundle == nullptr || key == nullptr || output == nullptr || get == nullptr) {
        return false;
    }
    const NativeI64Option result = get(bundle, key, ConstStringLength(key));
    if ((result.tag & uint64_t{1}) != 0u) return false;
    *output = result.value;
    return true;
}

bool VerifySystemUiUid(int32_t claimed_uid) {
    PackageManagerGetApplicationInfoFn get_info =
            ResolveLauncherSymbol<PackageManagerGetApplicationInfoFn>(
                    "PackageManager_get_application_info");
    ApplicationInfoGetUidFn get_uid =
            ResolveLauncherSymbol<ApplicationInfoGetUidFn>(
                    "ApplicationInfo_get_uid");
    ApplicationInfoDropFn drop = ResolveLauncherSymbol<ApplicationInfoDropFn>(
            "ApplicationInfo_drop");
    if (claimed_uid < 0 || get_info == nullptr || get_uid == nullptr ||
            drop == nullptr) {
        return false;
    }
    NativeResult result = get_info(kSystemUiPackage,
                                   ConstStringLength(kSystemUiPackage), 0u);
    if (!IsNativeSuccess(result)) return false;
    void* application_info = nullptr;
    memcpy(&application_info, result.bytes + 8u, sizeof(application_info));
    if (application_info == nullptr) return false;
    const int32_t actual_uid = get_uid(application_info);
    drop(application_info);
    return actual_uid == claimed_uid;
}

void ObserveArbiterStateIntent(void* intent) {
    IntentGetSenderPackageFn get_sender =
            ResolveLauncherSymbol<IntentGetSenderPackageFn>(
                    "Intent_get_sender_package_name");
    IntentGetExtrasFn get_extras = ResolveLauncherSymbol<IntentGetExtrasFn>(
            "Intent_get_extras");
    if (intent == nullptr || get_sender == nullptr || get_extras == nullptr) return;

    const BorrowedROptionRString sender = get_sender(intent);
    const bool correct_sender = sender.tag == 0u &&
            sender.data != nullptr &&
            sender.length == ConstStringLength(kSystemUiPackage) &&
            memcmp(sender.data, kSystemUiPackage, sender.length) == 0;
    if (!correct_sender) {
        Log(ANDROID_LOG_WARN, "rejected arbiter state from non-SystemUI sender");
        return;
    }

    void* extras = get_extras(intent);
    bool ready = false;
    bool contextual_search_enabled = false;
    int32_t sender_uid = -1;
    int64_t generation = 0;
    // The preference is optional for compatibility with an older companion
    // APK. Missing or malformed state must fail closed without preventing the
    // authenticated arbiter readiness update.
    ReadNativeBool(extras, kContextualSearchEnabledExtra,
                   &contextual_search_enabled);
    if (!ReadNativeBool(extras, "input_arbiter_ready", &ready) ||
            !ReadNativeI32(extras, "sender_uid", &sender_uid) ||
            !ReadNativeI64(extras, "input_arbiter_generation", &generation) ||
            generation <= 0 || !VerifySystemUiUid(sender_uid)) {
        Log(ANDROID_LOG_WARN, "rejected invalid SystemUI arbiter state");
        return;
    }
    const int64_t current = AtomicLoad(&g_systemui_arbiter_generation);
    if (generation < current) return;
    AtomicStore(&g_systemui_arbiter_generation, generation);
    AtomicStore(&g_systemui_arbiter_ready, ready ? uint32_t{1} : uint32_t{0});
    __atomic_store_n(&g_contextual_search_enabled,
                     contextual_search_enabled ? uint32_t{1} : uint32_t{0},
                     __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "native arbiter ready=%u contextual_search=%u "
                        "generation=%lld uid=%d",
                        ready ? 1u : 0u,
                        contextual_search_enabled ? 1u : 0u,
                        static_cast<long long>(generation), sender_uid);
}

bool IntentActionEquals(void* intent, const char* expected) {
    IntentGetActionFn get_action = ResolveLauncherSymbol<IntentGetActionFn>(
            "Intent_get_action");
    if (intent == nullptr || expected == nullptr || get_action == nullptr) {
        return false;
    }
    const BorrowedROptionRString action = get_action(intent);
    const bool matches = action.tag == 0u && action.data != nullptr &&
            action.length == ConstStringLength(expected) &&
            memcmp(action.data, expected, action.length) == 0;
    return matches;
}

extern "C" void MiuiHomeHyosCaptureBroadcastRuntime(void* runtime) {
    void* holder = runtime;
    void* shared = nullptr;
    if (reinterpret_cast<uintptr_t>(holder) >= 0x100000000ull) {
        shared = *reinterpret_cast<void**>(holder);
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "private broadcast send runtime_holder=%p shared=%p",
                        holder, shared);
    // The private bridge occasionally invokes this symbol for an error path
    // with a small tagged value in x0.  Never retain such a value as an Arc
    // object; raw_inc_strong would deliberately trap on it.
    if (reinterpret_cast<uintptr_t>(shared) >= 0x100000000ull) {
        __atomic_store_n(&g_last_broadcast_runtime, shared,
                         __ATOMIC_RELEASE);
    }
}

bool HasArbiterStateMarker(void* intent) {
    IntentGetExtrasFn get_extras = ResolveLauncherSymbol<IntentGetExtrasFn>(
            "Intent_get_extras");
    if (intent == nullptr || get_extras == nullptr) return false;
    void* extras = get_extras(intent);
    int64_t generation = 0;
    return ReadNativeI64(extras, "input_arbiter_generation", &generation) &&
            generation > 0;
}

__attribute__((noinline))
bool TryCallOriginalBroadcastReceiverOnReceive(void* receiver, void* context,
                                               void* intent) {
    // Reload immediately before the indirect branch.  Hot reload can retire
    // the trampoline between two callbacks; keeping a function pointer live
    // across the observer/publisher calls would turn that window into a null
    // branch in the Release build.
    BroadcastReceiverOnReceiveFn original =
            reinterpret_cast<BroadcastReceiverOnReceiveFn>(
                    AtomicLoad(&g_original_broadcast_receiver_on_receive));
    if (original == nullptr) return false;
    original(receiver, context, intent);
    return true;
}

void HookBroadcastReceiverOnReceive(void* receiver, void* context, void* intent) {
    if (IsLauncherProcess() &&
            !EnsureDartStateOwnerForCurrentProcess()) {
        (void)TryCallOriginalBroadcastReceiverOnReceive(
                receiver, context, intent);
        return;
    }
    if (intent == nullptr) {
        (void)TryCallOriginalBroadcastReceiverOnReceive(receiver, context, intent);
        return;
    }
    const bool arbiter_action = IntentActionEquals(intent, kArbiterStateAction) ||
            IntentActionEquals(intent, kArbiterStateCarrierAction);
    if (arbiter_action && HasArbiterStateMarker(intent)) {
        __atomic_fetch_add(&g_arbiter_state_marked_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        ObserveArbiterStateIntent(intent);
        // Android 17 reuses Xiaomi's protected fsgesture receiver as the
        // carrier. Its native callback owns launcher-side FSG-region refresh;
        // consuming the marked broadcast here leaves later app gestures
        // redirected before GesturesBackTouchProcessor. Authentication only
        // observes module state, then the original receiver must see the exact
        // unchanged intent and keep Xiaomi's native state machine intact.
        if (!TryCallOriginalBroadcastReceiverOnReceive(receiver, context, intent)) {
            // The trampoline may be retired between callbacks during hot
            // reload.  Without the Xiaomi receiver having run, none of the
            // mirrored or pending Dart state is authenticated for this
            // broadcast, so fail closed and wait for the next one.
            return;
        }
        __atomic_fetch_add(&g_arbiter_state_passthrough_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        // Make the dispatcher the sole pending-state consumer. Queue every
        // generation-bound value so a transient send failure remains owned by
        // its retry loop instead of depending on another receiver callback.
        __atomic_fetch_or(&g_dart_state_publish_pending,
                          kDartAllStatePending,
                          __ATOMIC_RELEASE);
        EnsureDartStatePublisherThread();
        PublishXiaoAiStateForCurrentGeneration();
        // A status query deliberately rides this authenticated action. Reply
        // after the original receiver and publisher setup so readiness covers
        // the actual safe drain boundary.
        HandleRuntimeStatusQuery(intent);
        return;
    }
    (void)TryCallOriginalBroadcastReceiverOnReceive(receiver, context, intent);
}

bool AddBundleBool(void* bundle, const char* key, bool value) {
    BundleInsertBoolFn insert = ResolveLauncherSymbol<BundleInsertBoolFn>(
            "Bundle_insert_boolean");
    RString owned{};
    if (insert == nullptr || !MakeOwnedRString(key, &owned)) return false;
    insert(bundle, &owned, value ? uint8_t{1} : uint8_t{0});
    return true;
}

bool AddBundleI32(void* bundle, const char* key, int32_t value) {
    BundleInsertI32Fn insert = ResolveLauncherSymbol<BundleInsertI32Fn>(
            "Bundle_insert_i32");
    RString owned{};
    if (insert == nullptr || !MakeOwnedRString(key, &owned)) return false;
    insert(bundle, &owned, value);
    return true;
}

bool AddBundleI64(void* bundle, const char* key, int64_t value) {
    BundleInsertI64Fn insert = ResolveLauncherSymbol<BundleInsertI64Fn>(
            "Bundle_insert_i64");
    RString owned{};
    if (insert == nullptr || !MakeOwnedRString(key, &owned)) return false;
    insert(bundle, &owned, value);
    return true;
}

constexpr uintptr_t kBroadcastOptionArmReserved = ~uintptr_t{0};

uintptr_t CurrentThreadPointer() {
    uintptr_t thread_pointer = 0u;
    asm volatile("mrs %0, tpidr_el0" : "=r"(thread_pointer));
    return thread_pointer;
}

bool ArmBroadcastOptions(void* options, uintptr_t* owner_thread) {
    if (options == nullptr || owner_thread == nullptr ||
            AtomicLoad(&g_broadcast_intent_with_feature_slot) == nullptr ||
            AtomicLoad(&miui_home_hyos_broadcast_original) == nullptr) {
        return false;
    }
    const uintptr_t thread = CurrentThreadPointer();
    if (thread == 0u || thread == kBroadcastOptionArmReserved) return false;

    uintptr_t expected = 0u;
    if (!__atomic_compare_exchange_n(
            &miui_home_hyos_broadcast_option_thread, &expected,
            kBroadcastOptionArmReserved, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return false;
    }
    AtomicStore(&miui_home_hyos_broadcast_option_state, uint32_t{0});
    AtomicStore(&miui_home_hyos_broadcast_option_bundle, options);
    AtomicStore(&miui_home_hyos_broadcast_option_thread, thread);
    *owner_thread = thread;
    return true;
}

bool DisarmBroadcastOptions(uintptr_t owner_thread) {
    if (owner_thread == 0u ||
            AtomicLoad(&miui_home_hyos_broadcast_option_thread) !=
                    owner_thread) {
        return false;
    }
    const uint32_t state =
            AtomicLoad(&miui_home_hyos_broadcast_option_state);
    AtomicStore(&miui_home_hyos_broadcast_option_bundle,
                static_cast<void*>(nullptr));
    AtomicStore(&miui_home_hyos_broadcast_option_state, uint32_t{0});
    uintptr_t expected = owner_thread;
    const bool released = __atomic_compare_exchange_n(
            &miui_home_hyos_broadcast_option_thread, &expected,
            uintptr_t{0}, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
    return released && state == 1u;
}

bool SetBroadcastPrivateGotWritable(void** slot, int protection) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (slot == nullptr || page_size <= 0 ||
            (page_size & (page_size - 1)) != 0) {
        return false;
    }
    const uintptr_t page = reinterpret_cast<uintptr_t>(slot) &
            ~static_cast<uintptr_t>(page_size - 1);
    return mprotect(reinterpret_cast<void*>(page),
                    static_cast<size_t>(page_size), protection) == 0;
}

bool RestoreBroadcastIntentWithFeatureGot() {
    void** slot = AtomicLoad(&g_broadcast_intent_with_feature_slot);
    void* original = AtomicLoad(&miui_home_hyos_broadcast_original);
    if (slot == nullptr || original == nullptr ||
            !SetBroadcastPrivateGotWritable(slot, PROT_READ | PROT_WRITE)) {
        return false;
    }
    void* expected = reinterpret_cast<void*>(
            MiuiHomeHyosBroadcastOptionsTailHook);
    const bool restored = __atomic_compare_exchange_n(
            slot, &expected, original, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
    const bool protected_again = SetBroadcastPrivateGotWritable(slot, PROT_READ);
    if (restored) {
        AtomicStore(&g_broadcast_intent_with_feature_slot,
                    static_cast<void**>(nullptr));
    }
    return restored && protected_again;
}

bool InstallBroadcastIntentWithFeatureGot(void* resolved, void* expected_base,
                                          void** slot) {
    AtomicStore(&g_native_receiver_state, uint32_t{100});
    Dl_info image{};
    if (resolved == nullptr || dladdr(resolved, &image) == 0 ||
            image.dli_fbase == nullptr || image.dli_fname == nullptr ||
            !StringsEqual(image.dli_fname, kBroadcastPrivatePath) ||
            image.dli_fbase != expected_base) {
        AtomicStore(&g_native_receiver_state, uint32_t{102});
        return false;
    }
    if (slot == nullptr) {
        AtomicStore(&g_native_receiver_state, uint32_t{103});
        return false;
    }
    if (AtomicLoad(slot) != resolved) {
        AtomicStore(&g_native_receiver_state, uint32_t{104});
        return false;
    }
    if (!SetBroadcastPrivateGotWritable(slot, PROT_READ | PROT_WRITE)) {
        AtomicStore(&g_native_receiver_state, uint32_t{105});
        return false;
    }

    // Publish the callable original before changing the live slot: another
    // launcher thread may enter the hook immediately after the atomic swap.
    AtomicStore(&miui_home_hyos_broadcast_original, resolved);
    AtomicStore(&g_broadcast_intent_with_feature_slot, slot);
    void* expected = resolved;
    const bool replaced = __atomic_compare_exchange_n(
            slot, &expected,
            reinterpret_cast<void*>(MiuiHomeHyosBroadcastOptionsTailHook),
            false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
    const bool protected_again = SetBroadcastPrivateGotWritable(slot, PROT_READ);
    if (replaced && protected_again) {
        AtomicStore(&g_native_receiver_state, uint32_t{109});
        const uintptr_t image_base = reinterpret_cast<uintptr_t>(
                image.dli_fbase);
        __android_log_print(
                ANDROID_LOG_INFO, kLogTag,
                "resolved broadcastIntentWithFeature PLT slot dynamically "
                "symbol_offset=0x%" PRIxPTR " slot_offset=0x%" PRIxPTR,
                reinterpret_cast<uintptr_t>(resolved) - image_base,
                reinterpret_cast<uintptr_t>(slot) - image_base);
        return true;
    }

    if (replaced) {
        // Best-effort rollback while the page is unexpectedly still writable.
        void* hook = reinterpret_cast<void*>(
                MiuiHomeHyosBroadcastOptionsTailHook);
        __atomic_compare_exchange_n(slot, &hook, resolved, false,
                                    __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
        SetBroadcastPrivateGotWritable(slot, PROT_READ);
    }
    AtomicStore(&g_broadcast_intent_with_feature_slot,
                static_cast<void**>(nullptr));
    AtomicStore(&g_native_receiver_state,
                replaced ? uint32_t{107} : uint32_t{106});
    return false;
}

bool SendNativeBroadcast(const char* action, void* extras) {
    // 1 entered, 2 invalid runtime/library, 3 missing symbol, 4 Intent alloc,
    // 5 owned string, 6 extras setter, 7 runtime pointer, 8 options bundle,
    // 9 options field, 10 arm, 11 public send returned, 12 not consumed,
    // 13 native error, 14 success. The state is terminal except for 1 and 11.
    __atomic_fetch_add(&g_native_broadcast_send_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    AtomicStore(&g_native_broadcast_send_kind,
                StringsEqual(action, kArbiterQueryAction)
                        ? uint32_t{1}
                        : (StringsEqual(action, kAcceptedStateAction)
                                ? uint32_t{2} : uint32_t{3}));
    AtomicStore(&g_native_broadcast_result_tag, uint32_t{0xffffffffu});
    AtomicStore(&g_native_broadcast_options_consumed, uint32_t{0});
    AtomicStore(&g_native_broadcast_send_state, uint32_t{1});
    IntentDefaultFn intent_default = ResolveLauncherSymbol<IntentDefaultFn>(
            "Intent_default");
    IntentDropFn intent_drop = ResolveLauncherSymbol<IntentDropFn>("Intent_drop");
    IntentSetStringFn set_action = ResolveLauncherSymbol<IntentSetStringFn>(
            "Intent_set_action");
    IntentSetStringFn set_package = ResolveLauncherSymbol<IntentSetStringFn>(
            "Intent_set_package");
    IntentSetExtrasFn set_extras = ResolveLauncherSymbol<IntentSetExtrasFn>(
            "Intent_set_extras");
    BroadcastSendFn send = ResolveLauncherSymbol<BroadcastSendFn>(
            "Broadcast_send_broadcast");
    RuntimeStrongFn inc = ResolveLauncherSymbol<RuntimeStrongFn>(
            "Runtime_inc_strong");
    RuntimeStrongFn dec = ResolveLauncherSymbol<RuntimeStrongFn>(
            "Runtime_dec_strong");
    BundleDefaultFn bundle_default = ResolveLauncherSymbol<BundleDefaultFn>(
            "Bundle_default");
    BundleDropFn bundle_drop = ResolveLauncherSymbol<BundleDropFn>(
            "Bundle_drop");
    const auto* profile = CurrentLauncherProfile();
    void* captured_runtime = __atomic_load_n(&g_last_broadcast_runtime,
                                             __ATOMIC_ACQUIRE);
    const uint32_t runtime_state = g_launcher_base != nullptr && profile != nullptr
            ? AtomicLoad(reinterpret_cast<uint32_t*>(
                    g_launcher_base + profile->runtime_state_offset))
            : 0u;
    if (g_launcher_base == nullptr || profile == nullptr ||
            (captured_runtime == nullptr &&
             runtime_state != profile->runtime_ready_value)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "native broadcast rejected: base=%p profile=%p state=0x%x expected=0x%x",
                g_launcher_base, profile, runtime_state,
                profile != nullptr ? profile->runtime_ready_value : 0u);
        AtomicStore(&g_native_broadcast_send_state, uint32_t{2});
        return false;
    }
    if (intent_default == nullptr || intent_drop == nullptr ||
            set_action == nullptr || set_package == nullptr ||
            send == nullptr || inc == nullptr || dec == nullptr ||
            bundle_default == nullptr || bundle_drop == nullptr) {
        AtomicStore(&g_native_broadcast_send_state, uint32_t{3});
        return false;
    }
    void* intent = intent_default();
    if (intent == nullptr) {
        AtomicStore(&g_native_broadcast_send_state, uint32_t{4});
        return false;
    }
    ROptionRString owned_action{};
    ROptionRString owned_package{};
    if (!MakeOwnedROptionString(action, &owned_action) ||
            !MakeOwnedROptionString(kSystemUiPackage, &owned_package)) {
        AtomicStore(&g_native_broadcast_send_state, uint32_t{5});
        intent_drop(intent);
        return false;
    }
    set_action(intent, &owned_action);
    set_package(intent, &owned_package);
    if (extras != nullptr) {
        if (set_extras == nullptr) {
            AtomicStore(&g_native_broadcast_send_state, uint32_t{6});
            intent_drop(intent);
            return false;
        }
        set_extras(intent, extras);
    }
    void* runtime = captured_runtime;
    if (reinterpret_cast<uintptr_t>(runtime) < 0x100000000ull) {
        runtime = nullptr;
    }
    if (runtime == nullptr) {
        runtime = AtomicLoad(reinterpret_cast<void**>(
                g_launcher_base + profile->runtime_pointer_offset));
    }
    if (runtime == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "native broadcast rejected: runtime pointer null offset=0x%zx state=0x%x",
                profile->runtime_pointer_offset, runtime_state);
        AtomicStore(&g_native_broadcast_send_state, uint32_t{7});
        intent_drop(intent);
        return false;
    }

    // BroadcastOptions.setShareIdentityEnabled(true) is encoded as bit 4 in
    // android:broadcast.flags.  The public HyperOS send wrapper has no options
    // parameter, so arm a thread-scoped one-shot for its exact private call.
    void* options = bundle_default();
    if (options == nullptr) {
        AtomicStore(&g_native_broadcast_send_state, uint32_t{8});
        Log(ANDROID_LOG_ERROR,
            "failed to create share-identity broadcast options");
        intent_drop(intent);
        return false;
    }
    if (!AddBundleI32(options, "android:broadcast.flags", 16)) {
        AtomicStore(&g_native_broadcast_send_state, uint32_t{9});
        Log(ANDROID_LOG_ERROR,
            "failed to create share-identity broadcast options");
        bundle_drop(options);
        intent_drop(intent);
        return false;
    }

    inc(runtime);
    uintptr_t option_owner_thread = 0u;
    if (!ArmBroadcastOptions(options, &option_owner_thread)) {
        AtomicStore(&g_native_broadcast_send_state, uint32_t{10});
        dec(runtime);
        bundle_drop(options);
        intent_drop(intent);
        return false;
    }
    void* shared_runtime = runtime;
    const NativeResult result = send(&shared_runtime, intent);
    AtomicStore(&g_native_broadcast_send_state, uint32_t{11});
    AtomicStore(&g_native_broadcast_result_tag,
                static_cast<uint32_t>(result.bytes[0]));
    const bool options_consumed =
            DisarmBroadcastOptions(option_owner_thread);
    AtomicStore(&g_native_broadcast_options_consumed,
                options_consumed ? uint32_t{1} : uint32_t{0});
    dec(runtime);
    bundle_drop(options);
    intent_drop(intent);
    if (!options_consumed) {
        AtomicStore(&g_native_broadcast_send_state, uint32_t{12});
        Log(ANDROID_LOG_ERROR,
            "share-identity broadcast options were not consumed");
        return false;
    }
    if (!IsNativeSuccess(result)) {
        AtomicStore(&g_native_broadcast_send_state, uint32_t{13});
        return false;
    }
    AtomicStore(&g_native_broadcast_send_state, uint32_t{14});
    return true;
}

constexpr uint64_t kDartObservationStateMask = uint64_t{3};

uint64_t PackDartStateObservation(uint64_t owner_epoch, uint32_t state) {
    return (owner_epoch << 2u) | state;
}

uint32_t DartStateObservationValue(uint64_t observation) {
    return static_cast<uint32_t>(observation & kDartObservationStateMask);
}

uint64_t DartStateObservationEpoch(uint64_t observation) {
    return observation >> 2u;
}

void PublishDrawerStateForCurrentGeneration() {
    const uint64_t owner_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    const uint64_t observation = AtomicLoad(&g_drawer_state_observation);
    const uint32_t observed = DartStateObservationValue(observation);
    const int64_t generation = AtomicLoad(&g_systemui_arbiter_generation);
    if (DartStateObservationEpoch(observation) != owner_epoch ||
            (observed != 1u && observed != 2u) || generation <= 0 ||
            (AtomicLoad(&g_drawer_published_state) == observed &&
             AtomicLoad(&g_drawer_published_generation) == generation &&
             AtomicLoad(&g_drawer_published_owner_epoch) == owner_epoch)) {
        return;
    }
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(
                &g_drawer_state_publish_in_flight, &expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    BundleDefaultFn bundle_default = ResolveLauncherSymbol<BundleDefaultFn>(
            "Bundle_default");
    void* extras = bundle_default == nullptr ? nullptr : bundle_default();
    const bool sent = extras != nullptr &&
            AddBundleBool(extras, "drawer_visible", observed == 2u) &&
            AddBundleI64(extras, kLauncherStateOwnerEpochExtra,
                         static_cast<int64_t>(owner_epoch)) &&
            AddBundleI64(extras, "input_arbiter_generation", generation) &&
            SendNativeBroadcast(kAcceptedStateAction, extras);
    if (miui_home_dart_state::RecordPublication(sent, observed, generation, owner_epoch,
            &g_drawer_published_state, &g_drawer_published_generation,
            &g_drawer_published_owner_epoch)) {
        __atomic_fetch_add(&g_drawer_state_publish_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "published native drawer visible=%u generation=%lld",
                            observed == 2u ? 1u : 0u,
                            static_cast<long long>(generation));
    } else if (!sent) {
        Log(ANDROID_LOG_WARN, "native drawer state broadcast failed");
    }
    AtomicStore(&g_drawer_state_publish_in_flight, uint32_t{0});
}

void HookDrawerStateHandler(int64_t port, uint8_t* data, uint32_t length,
                            uint32_t capacity) {
    DrawerStateHandlerFn original = reinterpret_cast<DrawerStateHandlerFn>(
            AtomicLoad(&g_original_drawer_state_handler));
    const bool valid = data != nullptr && length == 1u && capacity == 1u &&
            data[0] <= 1u;
    const uint32_t observed = valid
            ? (data[0] == 0u ? uint32_t{1} : uint32_t{2})
            : uint32_t{0};
    if (original != nullptr) {
        original(port, data, length, capacity);
    }
    // The FRB wrapper owns and may free data. Capture its one-byte bool before
    // the original call, but publish only after Xiaomi has queued the state.
    if (!valid) {
        Log(ANDROID_LOG_WARN, "ignored malformed native drawer-state bridge call");
        return;
    }
    if (!EnsureDartStateOwnerForCurrentProcess()) return;
    const uint64_t owner_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    __atomic_store_n(&g_drawer_state_observation,
                     PackDartStateObservation(owner_epoch, observed),
                     __ATOMIC_RELEASE);
    // The legacy FRB producer shares the Dart dispatcher's receipt ordering.
    __atomic_fetch_or(&g_dart_state_publish_pending, kDartDrawerPending,
                      __ATOMIC_RELEASE);
    WakeDartStatePublisher();
}

void PublishOverviewStateForCurrentGeneration() {
    const uint64_t owner_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    const uint64_t observation = AtomicLoad(&g_overview_state_observation);
    const uint32_t observed = DartStateObservationValue(observation);
    const int64_t generation = AtomicLoad(&g_systemui_arbiter_generation);
    if (DartStateObservationEpoch(observation) != owner_epoch ||
            (observed != 1u && observed != 2u) || generation <= 0 ||
            (AtomicLoad(&g_overview_published_state) == observed &&
             AtomicLoad(&g_overview_published_generation) == generation &&
             AtomicLoad(&g_overview_published_owner_epoch) == owner_epoch)) {
        return;
    }
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(
                &g_overview_state_publish_in_flight, &expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    BundleDefaultFn bundle_default = ResolveLauncherSymbol<BundleDefaultFn>(
            "Bundle_default");
    void* extras = bundle_default == nullptr ? nullptr : bundle_default();
    const bool sent = extras != nullptr &&
            AddBundleBool(extras, "overview_visible", observed == 2u) &&
            AddBundleI64(extras, kLauncherStateOwnerEpochExtra,
                         static_cast<int64_t>(owner_epoch)) &&
            AddBundleI64(extras, "input_arbiter_generation", generation) &&
            SendNativeBroadcast(kAcceptedStateAction, extras);
    if (miui_home_dart_state::RecordPublication(sent, observed, generation, owner_epoch,
            &g_overview_published_state, &g_overview_published_generation,
            &g_overview_published_owner_epoch)) {
        __atomic_fetch_add(&g_overview_state_publish_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "published native Overview visible=%u generation=%lld",
                            observed == 2u ? 1u : 0u,
                            static_cast<long long>(generation));
    } else if (!sent) {
        Log(ANDROID_LOG_WARN, "native Overview state broadcast failed");
    }
    AtomicStore(&g_overview_state_publish_in_flight, uint32_t{0});
}

void PublishHomeSurfaceStateForCurrentGeneration() {
    const uint64_t owner_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    const uint64_t observation = AtomicLoad(&g_editing_state_observation);
    const uint32_t observed = DartStateObservationValue(observation);
    const int64_t generation = AtomicLoad(&g_systemui_arbiter_generation);
    if (DartStateObservationEpoch(observation) != owner_epoch ||
            (observed != 1u && observed != 2u) || generation <= 0 ||
            (AtomicLoad(&g_editing_published_state) == observed &&
             AtomicLoad(&g_editing_published_generation) == generation &&
             AtomicLoad(&g_editing_published_owner_epoch) == owner_epoch)) {
        return;
    }
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(
                &g_editing_state_publish_in_flight, &expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    BundleDefaultFn bundle_default = ResolveLauncherSymbol<BundleDefaultFn>(
            "Bundle_default");
    void* extras = bundle_default == nullptr ? nullptr : bundle_default();
    const bool sent = extras != nullptr &&
            AddBundleBool(extras, "launcher_home_surface_visible", observed == 2u) &&
            AddBundleI64(extras, kLauncherStateOwnerEpochExtra,
                         static_cast<int64_t>(owner_epoch)) &&
            AddBundleI64(extras, "input_arbiter_generation", generation) &&
            SendNativeBroadcast(kAcceptedStateAction, extras);
    if (miui_home_dart_state::RecordPublication(sent, observed, generation, owner_epoch,
            &g_editing_published_state, &g_editing_published_generation,
            &g_editing_published_owner_epoch)) {
        __atomic_fetch_add(&g_editing_state_publish_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "published native Home surface visible=%u generation=%lld",
                            observed == 2u ? 1u : 0u,
                            static_cast<long long>(generation));
    } else if (!sent) {
        Log(ANDROID_LOG_WARN, "native Home-surface state broadcast failed");
    }
    AtomicStore(&g_editing_state_publish_in_flight, uint32_t{0});
}

bool PublishDartStateOwnerForCurrentGeneration(uint64_t owner_epoch) {
    const int64_t generation = AtomicLoad(&g_systemui_arbiter_generation);
    if (owner_epoch == 0u ||
            AtomicLoad(&g_dart_state_owner_epoch) != owner_epoch ||
            generation <= 0) {
        return false;
    }
    if (AtomicLoad(&g_dart_state_owner_published_epoch) == owner_epoch &&
            AtomicLoad(&g_dart_state_owner_published_generation) ==
                    generation) {
        return true;
    }
    BundleDefaultFn bundle_default = ResolveLauncherSymbol<BundleDefaultFn>(
            "Bundle_default");
    void* extras = bundle_default == nullptr ? nullptr : bundle_default();
    const bool sent = extras != nullptr &&
            AddBundleI64(extras, kLauncherStateOwnerEpochExtra,
                         static_cast<int64_t>(owner_epoch)) &&
            AddBundleI64(extras, "input_arbiter_generation", generation) &&
            SendNativeBroadcast(kAcceptedStateAction, extras);
    if (sent && AtomicLoad(&g_dart_state_owner_epoch) == owner_epoch &&
            AtomicLoad(&g_systemui_arbiter_generation) == generation) {
        __atomic_store_n(&g_dart_state_owner_published_epoch, owner_epoch,
                         __ATOMIC_RELEASE);
        __atomic_store_n(&g_dart_state_owner_published_generation, generation,
                         __ATOMIC_RELEASE);
        return true;
    }
    if (!sent) {
        Log(ANDROID_LOG_WARN, "native Dart owner reset broadcast failed");
    }
    return false;
}

void PublishPendingDartStates(uint64_t owner_epoch) {
    if (owner_epoch == 0u ||
            AtomicLoad(&g_dart_state_owner_epoch) != owner_epoch) {
        return;
    }
    const uint32_t pending = __atomic_exchange_n(
            &g_dart_state_publish_pending, uint32_t{0}, __ATOMIC_ACQ_REL);
    if (pending == 0u) return;

    if ((pending & kDartOwnerPending) != 0u) {
        PublishDartStateOwnerForCurrentGeneration(owner_epoch);
    }
    if ((pending & kDartDrawerPending) != 0u) {
        PublishDrawerStateForCurrentGeneration();
    }
    if ((pending & kDartOverviewPending) != 0u) {
        PublishOverviewStateForCurrentGeneration();
    }
    if ((pending & kDartEditingPending) != 0u) {
        PublishHomeSurfaceStateForCurrentGeneration();
    }

    // A concurrent Dart transition, an in-flight publisher, or a failed send
    // must remain eligible for the next authenticated arbiter receive. Re-arm
    // only the state whose observed value is still not published for the
    // current generation.
    if (AtomicLoad(&g_dart_state_owner_epoch) != owner_epoch) return;
    const int64_t generation = AtomicLoad(&g_systemui_arbiter_generation);
    uint32_t retry = 0u;
    if ((pending & kDartOwnerPending) != 0u &&
            (AtomicLoad(&g_dart_state_owner_published_epoch) != owner_epoch ||
             AtomicLoad(&g_dart_state_owner_published_generation) !=
                     generation)) {
        retry |= kDartOwnerPending;
    }
    const uint64_t drawer = AtomicLoad(&g_drawer_state_observation);
    if ((pending & kDartDrawerPending) != 0u &&
            DartStateObservationEpoch(drawer) == owner_epoch &&
            (DartStateObservationValue(drawer) !=
                     AtomicLoad(&g_drawer_published_state) ||
             AtomicLoad(&g_drawer_published_generation) != generation ||
             AtomicLoad(&g_drawer_published_owner_epoch) != owner_epoch)) {
        retry |= kDartDrawerPending;
    }
    const uint64_t overview = AtomicLoad(&g_overview_state_observation);
    if ((pending & kDartOverviewPending) != 0u &&
            DartStateObservationEpoch(overview) == owner_epoch &&
            (DartStateObservationValue(overview) !=
                     AtomicLoad(&g_overview_published_state) ||
             AtomicLoad(&g_overview_published_generation) != generation ||
             AtomicLoad(&g_overview_published_owner_epoch) != owner_epoch)) {
        retry |= kDartOverviewPending;
    }
    const uint64_t editing = AtomicLoad(&g_editing_state_observation);
    if ((pending & kDartEditingPending) != 0u &&
            DartStateObservationEpoch(editing) == owner_epoch &&
            (DartStateObservationValue(editing) !=
                     AtomicLoad(&g_editing_published_state) ||
             AtomicLoad(&g_editing_published_generation) != generation ||
             AtomicLoad(&g_editing_published_owner_epoch) != owner_epoch)) {
        retry |= kDartEditingPending;
    }
    if (retry != 0u) {
        __atomic_fetch_or(&g_dart_state_publish_pending, retry,
                          __ATOMIC_RELEASE);
    }
}

void* DartStatePublisherThreadMain(void* data) {
    const int fd = static_cast<int>(reinterpret_cast<intptr_t>(data));
    const uint64_t initial_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    if (fd < 0) {
        __atomic_store_n(&g_dart_state_publish_dispatcher_state,
                         uint32_t{6}, __ATOMIC_RELEASE);
        __atomic_store_n(&g_dart_state_publish_failure_epoch, initial_epoch,
                         __ATOMIC_RELEASE);
        return nullptr;
    }
    __atomic_store_n(&g_dart_state_publish_dispatcher_state, uint32_t{3},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_dart_state_publish_failure_epoch, uint64_t{0},
                     __ATOMIC_RELEASE);

    pollfd descriptor{fd, POLLIN, 0};
    for (;;) {
        descriptor.revents = 0;
        const int polled = poll(&descriptor, 1u, -1);
        if (polled < 0 && errno == EINTR) continue;
        if (polled <= 0 || (descriptor.revents & POLLIN) == 0) {
            __atomic_store_n(&g_dart_state_publish_failure_epoch,
                             AtomicLoad(&g_dart_state_owner_epoch),
                             __ATOMIC_RELEASE);
            __atomic_store_n(&g_dart_state_publish_dispatcher_state,
                             uint32_t{6}, __ATOMIC_RELEASE);
            // Keep the terminal fd allocated until process exit. A Dart hook
            // can have loaded it immediately before this failure; closing it
            // here could redirect that raw write into a reused descriptor.
            return nullptr;
        }
        uint64_t wake_count = 0u;
        while (read(fd, &wake_count, sizeof(wake_count)) ==
                static_cast<ssize_t>(sizeof(wake_count))) {
        }

        // Keep publication outside the triggering Dart frame. State changes
        // within one display interval coalesce into the latest immutable
        // observation, while the thread remains fully asleep when idle.
        timespec delay{0, 16 * 1000 * 1000};
        while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {
        }
        while (read(fd, &wake_count, sizeof(wake_count)) ==
                static_cast<ssize_t>(sizeof(wake_count))) {
        }
        uint32_t retry_delay_ms = 16u;
        for (;;) {
            const uint64_t owner_epoch =
                    AtomicLoad(&g_dart_state_owner_epoch);
            // Mark this publication cycle unresolved before exchanging the
            // pending mask. Readiness must not observe a transient zero while
            // a broadcast attempt owns those bits locally.
            __atomic_store_n(&g_dart_state_publish_failure_epoch,
                             owner_epoch, __ATOMIC_RELEASE);
            PublishPendingDartStates(owner_epoch);
            if (AtomicLoad(&g_dart_state_owner_epoch) == owner_epoch &&
                    AtomicLoad(&g_dart_state_publish_pending) == 0u) {
                __atomic_store_n(&g_dart_state_publish_callback_epoch,
                                 owner_epoch, __ATOMIC_RELEASE);
                uint64_t failed_epoch = owner_epoch;
                __atomic_compare_exchange_n(
                        &g_dart_state_publish_failure_epoch, &failed_epoch,
                        uint64_t{0}, false, __ATOMIC_RELEASE,
                        __ATOMIC_RELAXED);
                // A new Dart observation racing the success publication owns
                // a raw eventfd wake. Do not suppress that next cycle.
                if (AtomicLoad(&g_dart_state_publish_pending) == 0u) break;
            }

            // A re-armed bit is self-sustaining work. Drain any coalesced
            // eventfd writes and retry with bounded backoff instead of
            // returning to an indefinite poll or spinning on a broken
            // broadcast runtime.
            while (read(fd, &wake_count, sizeof(wake_count)) ==
                    static_cast<ssize_t>(sizeof(wake_count))) {
            }
            timespec retry_delay{
                    static_cast<time_t>(retry_delay_ms / 1000u),
                    static_cast<long>((retry_delay_ms % 1000u) *
                                      1000u * 1000u)};
            while (nanosleep(&retry_delay, &retry_delay) != 0 &&
                    errno == EINTR) {
            }
            retry_delay_ms = retry_delay_ms < 128u
                    ? retry_delay_ms * 2u : 250u;
        }
    }
}

bool WakeDartStatePublisher() {
    const uint64_t owner_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    const int fd = AtomicLoad(&g_dart_state_publish_event_fd);
    if (fd < 0 ||
            AtomicLoad(&g_dart_state_publish_dispatcher_state) !=
                    uint32_t{3}) {
        __atomic_store_n(&g_dart_state_publish_failure_epoch, owner_epoch,
                         __ATOMIC_RELEASE);
        return false;
    }
    const uint64_t one = 1u;
    const ssize_t written = write(fd, &one, sizeof(one));
    if (written == static_cast<ssize_t>(sizeof(one)) ||
            (written < 0 && errno == EAGAIN)) {
        return true;
    }
    __atomic_store_n(&g_dart_state_publish_failure_epoch, owner_epoch,
                     __ATOMIC_RELEASE);
    return false;
}

bool WaitForDartStatePublisherReady(uint64_t owner_epoch) {
    for (uint32_t attempt = 0u; attempt < 50u; ++attempt) {
        if (AtomicLoad(&g_dart_state_owner_epoch) != owner_epoch) {
            return false;
        }
        if (AtomicLoad(&g_dart_state_publish_callback_epoch) == owner_epoch &&
                AtomicLoad(&g_dart_state_publish_pending) == 0u &&
                AtomicLoad(&g_dart_state_publish_failure_epoch) !=
                        owner_epoch) {
            return true;
        }
        if (AtomicLoad(&g_dart_state_publish_dispatcher_state) !=
                uint32_t{3}) {
            return false;
        }
        timespec delay{0, 1000 * 1000};
        nanosleep(&delay, nullptr);
    }
    __atomic_store_n(&g_dart_state_publish_failure_epoch, owner_epoch,
                     __ATOMIC_RELEASE);
    return false;
}

bool EnsureDartStatePublisherThread() {
    if (!EnsureDartStateOwnerForCurrentProcess()) return false;
    const uint64_t owner_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    if (AtomicLoad(&g_dart_state_publish_dispatcher_state) == uint32_t{3} &&
            AtomicLoad(&g_dart_state_publish_event_fd) >= 0) {
        return (AtomicLoad(&g_dart_state_publish_callback_epoch) ==
                        owner_epoch &&
                AtomicLoad(&g_dart_state_publish_pending) == 0u &&
                AtomicLoad(&g_dart_state_publish_failure_epoch) !=
                        owner_epoch) ||
                (WakeDartStatePublisher() &&
                 WaitForDartStatePublisherReady(owner_epoch));
    }
    uint32_t expected = AtomicLoad(&g_dart_state_publish_dispatcher_state);
    while (expected != uint32_t{1}) {
        if (expected == uint32_t{3}) {
            return AtomicLoad(&g_dart_state_publish_event_fd) >= 0 &&
                    WakeDartStatePublisher() &&
                    WaitForDartStatePublisherReady(owner_epoch);
        }
        if (expected == uint32_t{6}) return false;
        if (__atomic_compare_exchange_n(
                    &g_dart_state_publish_dispatcher_state, &expected,
                    uint32_t{1}, false, __ATOMIC_ACQ_REL,
                    __ATOMIC_ACQUIRE)) {
            break;
        }
    }
    if (expected == uint32_t{1}) {
        for (uint32_t attempt = 0u; attempt < 50u &&
                AtomicLoad(&g_dart_state_publish_dispatcher_state) ==
                        uint32_t{1}; ++attempt) {
            timespec delay{0, 1000 * 1000};
            nanosleep(&delay, nullptr);
        }
        if (AtomicLoad(&g_dart_state_publish_dispatcher_state) !=
                    uint32_t{3} ||
                AtomicLoad(&g_dart_state_publish_event_fd) < 0) {
            return false;
        }
        return WakeDartStatePublisher() &&
                WaitForDartStatePublisherReady(owner_epoch);
    }

    const int fd = eventfd(0u, EFD_CLOEXEC | EFD_NONBLOCK);
    if (fd < 0) {
        __atomic_store_n(&g_dart_state_publish_dispatcher_state, uint32_t{6},
                         __ATOMIC_RELEASE);
        __atomic_store_n(&g_dart_state_publish_failure_epoch,
                         AtomicLoad(&g_dart_state_owner_epoch),
                         __ATOMIC_RELEASE);
        return false;
    }
    pthread_attr_t attributes{};
    pthread_t thread{};
    const bool attributes_ready = pthread_attr_init(&attributes) == 0;
    if (attributes_ready) {
        pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    }
    const int created = attributes_ready
            ? pthread_create(&thread, &attributes,
                             DartStatePublisherThreadMain,
                             reinterpret_cast<void*>(
                                     static_cast<intptr_t>(fd)))
            : EINVAL;
    if (attributes_ready) pthread_attr_destroy(&attributes);
    if (created != 0) {
        close(fd);
        __atomic_store_n(&g_dart_state_publish_dispatcher_state, uint32_t{6},
                         __ATOMIC_RELEASE);
        __atomic_store_n(&g_dart_state_publish_failure_epoch,
                         AtomicLoad(&g_dart_state_owner_epoch),
                         __ATOMIC_RELEASE);
        return false;
    }
    // Publish only after pthread_create succeeds; a failed setup must never
    // expose an fd that can be closed and reused under a raw Dart write.
    __atomic_store_n(&g_dart_state_publish_event_fd, fd, __ATOMIC_RELEASE);
    for (uint32_t attempt = 0u; attempt < 50u &&
            AtomicLoad(&g_dart_state_publish_dispatcher_state) ==
                    uint32_t{1}; ++attempt) {
        timespec delay{0, 1000 * 1000};
        nanosleep(&delay, nullptr);
    }
    if (AtomicLoad(&g_dart_state_publish_dispatcher_state) != uint32_t{3}) {
        return false;
    }
    return WakeDartStatePublisher() &&
            WaitForDartStatePublisherReady(owner_epoch);
}

void PublishXiaoAiStateForCurrentGeneration() {
    const uint32_t observed = AtomicLoad(&g_xiaoai_state_observed);
    const int64_t generation = AtomicLoad(&g_systemui_arbiter_generation);
    if ((observed != 1u && observed != 2u) || generation <= 0 ||
            (AtomicLoad(&g_xiaoai_published_state) == observed &&
             AtomicLoad(&g_xiaoai_published_generation) == generation)) {
        return;
    }
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(
                &g_xiaoai_state_publish_in_flight, &expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    BundleDefaultFn bundle_default = ResolveLauncherSymbol<BundleDefaultFn>(
            "Bundle_default");
    void* extras = bundle_default == nullptr ? nullptr : bundle_default();
    const bool sent = extras != nullptr &&
            AddBundleBool(extras, "xiaoai_visible", observed == 2u) &&
            AddBundleI64(extras, "input_arbiter_generation", generation) &&
            SendNativeBroadcast(kAcceptedStateAction, extras);
    if (sent && AtomicLoad(&g_xiaoai_state_observed) == observed &&
            AtomicLoad(&g_systemui_arbiter_generation) == generation) {
        __atomic_store_n(&g_xiaoai_published_state, observed,
                         __ATOMIC_RELEASE);
        __atomic_store_n(&g_xiaoai_published_generation, generation,
                         __ATOMIC_RELEASE);
        __atomic_fetch_add(&g_xiaoai_state_publish_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "published native XiaoAi visible=%u generation=%lld",
                            observed == 2u ? 1u : 0u,
                            static_cast<long long>(generation));
    } else if (!sent) {
        Log(ANDROID_LOG_WARN, "native XiaoAi state broadcast failed");
    }
    AtomicStore(&g_xiaoai_state_publish_in_flight, uint32_t{0});
}

bool HandleRuntimeStatusQuery(void* intent) {
    IntentGetSenderPackageFn get_sender =
            ResolveLauncherSymbol<IntentGetSenderPackageFn>(
                    "Intent_get_sender_package_name");
    IntentGetExtrasFn get_extras = ResolveLauncherSymbol<IntentGetExtrasFn>(
            "Intent_get_extras");
    if (intent == nullptr || get_sender == nullptr || get_extras == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "runtime status query rejected: symbols intent=%p sender=%p extras=%p",
                intent, reinterpret_cast<void*>(get_sender),
                reinterpret_cast<void*>(get_extras));
        return false;
    }
    const BorrowedROptionRString sender = get_sender(intent);
    if (sender.tag != 0u || sender.data == nullptr ||
            sender.length != ConstStringLength(kSystemUiPackage) ||
            memcmp(sender.data, kSystemUiPackage, sender.length) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "runtime status query rejected: sender tag=%u data=%p length=%zu",
                sender.tag, sender.data, sender.length);
        return false;
    }
    void* extras = get_extras(intent);
    bool query = false;
    int32_t sender_uid = -1;
    int64_t nonce = 0;
    if (!ReadNativeBool(extras, kRuntimeStatusQueryExtra, &query) || !query ||
            !ReadNativeI32(extras, "sender_uid", &sender_uid) ||
            !ReadNativeI64(extras, kRuntimeStatusNonceExtra, &nonce) ||
            nonce <= 0 || !VerifySystemUiUid(sender_uid)) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                "runtime status query rejected: extras=%p query=%u uid=%d nonce=%lld",
                extras, query ? 1u : 0u, sender_uid,
                static_cast<long long>(nonce));
        return false;
    }
    __atomic_fetch_add(&g_runtime_status_query_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    __atomic_store_n(&g_runtime_status_last_nonce,
                     static_cast<uint64_t>(nonce), __ATOMIC_RELEASE);
    // A Xiaomi desktop update can remap Flutter's libapp.so inside the
    // framework-owned HYOS process without recreating the native entry.  If
    // the first scan ran against an intermediate image, retry once from the
    // already-loaded image when the module asks for status.  The resolver
    // still requires the complete structural family and never loads a second
    // Dart owner.
    const uint32_t dart_stage = AtomicLoad(&g_dart_profile_resolve_state);
    if (dart_stage >= static_cast<uint32_t>(
                              miui_home_dart_profile::ResolveStage::
                                      kRejectedElf) &&
            AtomicLoad(&g_resolved_dart_profile) == nullptr) {
        TryResolveAndInstallLoadedDartProfile(true);
    }
    const auto* profile = CurrentLauncherProfile();
    const bool profile_resolved = profile != nullptr && g_launcher_base != nullptr;
    const bool dynamic_profile = profile_resolved &&
            StringsEqual(profile->id, "runtime-side-v1");
    const bool contextual_dynamic = profile_resolved &&
            profile->contextual_long_press_handler_offset != 0u &&
            profile->contextual_search_invoke_offset != 0u &&
            (dynamic_profile || profile == &g_contextual_overlay_storage.profile);
    const uint32_t business_state = AtomicLoad(&g_business_hook_state);
    const uint32_t bridge_state = AtomicLoad(&g_arbiter_bridge_hook_state);
    const auto* feature_profile = CurrentDartFeatureProfile();
    if (feature_profile == nullptr) feature_profile = profile;
    const bool dart_features_required =
            NeedsDartFeatureResolution(profile);
    const bool drawer_required = profile_resolved &&
            (dart_features_required ||
             feature_profile->drawer_state_handler_offset != 0u ||
             feature_profile->dart_drawer_transition_complete_offset != 0u);
    const bool overview_required = profile_resolved &&
            (dart_features_required ||
             feature_profile->dart_overview_enter_offset != 0u);
    const bool editing_required = profile_resolved &&
            (dart_features_required ||
             feature_profile->dart_editing_query_offset != 0u);
    const bool dart_scheduler_required = profile_resolved &&
            (dart_features_required ||
             feature_profile->dart_drawer_transition_complete_offset != 0u ||
             feature_profile->dart_overview_enter_offset != 0u ||
             feature_profile->dart_editing_query_offset != 0u);
    const uint64_t dart_owner_epoch = AtomicLoad(&g_dart_state_owner_epoch);
    const bool dart_repair_ready =
            AtomicLoad(&g_dart_remap_pending_mask) == 0u &&
            AtomicLoad(&g_dart_remap_repair_in_flight) == 0u &&
            AtomicLoad(&g_dart_drawer_retiring) == 0u &&
            AtomicLoad(&g_dart_overview_enter_retiring) == 0u &&
            AtomicLoad(&g_dart_overview_exit_retiring) == 0u &&
            AtomicLoad(&g_dart_editing_retiring) == 0u;
    const bool dart_scheduler_ready = !dart_scheduler_required ||
            (AtomicLoad(&g_dart_state_atfork_state) == uint32_t{3} &&
             dart_repair_ready &&
             AtomicLoad(&g_dart_state_publish_pending) == 0u &&
             AtomicLoad(&g_dart_state_publish_dispatcher_state) ==
                     uint32_t{3} &&
             AtomicLoad(&g_dart_state_publish_event_fd) >= 0 &&
             AtomicLoad(&g_dart_state_publish_callback_epoch) ==
                     dart_owner_epoch &&
             AtomicLoad(&g_dart_state_publish_failure_epoch) !=
                     dart_owner_epoch);
    const bool drawer_ready = profile_resolved &&
            (!drawer_required ||
             (AtomicLoad(&g_drawer_state_hook_state) == uint32_t{3} &&
              dart_scheduler_ready));
    const bool overview_ready = profile_resolved &&
            (!overview_required ||
             (AtomicLoad(&g_overview_state_hook_state) == uint32_t{3} &&
              dart_scheduler_ready));
    const bool editing_ready = profile_resolved &&
            (!editing_required ||
             (AtomicLoad(&g_editing_state_hook_state) == uint32_t{3} &&
              dart_scheduler_ready));
    const bool native_ready = profile_resolved &&
            AtomicLoad(&g_dart_state_atfork_state) == uint32_t{3} &&
            business_state == 3u &&
            bridge_state == 3u && drawer_ready && overview_ready &&
            editing_ready;
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "runtime status query accepted: nonce=%lld profile=%u native=%u business=%u bridge=%u drawer=%u overview=%u editing=%u",
            static_cast<long long>(nonce), profile_resolved ? 1u : 0u,
            native_ready ? 1u : 0u, business_state, bridge_state,
            drawer_ready ? 1u : 0u, overview_ready ? 1u : 0u,
            editing_ready ? 1u : 0u);
    BundleDefaultFn bundle_default = ResolveLauncherSymbol<BundleDefaultFn>(
            "Bundle_default");
    if (bundle_default == nullptr) {
        __atomic_store_n(&g_runtime_status_last_state, uint32_t{2},
                         __ATOMIC_RELEASE);
        return false;
    }
    void* response = bundle_default();
    if (response == nullptr ||
            !AddBundleBool(response, "status_native_ready", native_ready) ||
            !AddBundleBool(response, "status_native_profile_resolved",
                           profile_resolved) ||
            !AddBundleBool(response, "status_native_profile_dynamic",
                           dynamic_profile) ||
            !AddBundleBool(response, "status_native_contextual_dynamic",
                           contextual_dynamic) ||
            !AddBundleBool(response, "status_native_drawer_state_ready",
                           drawer_ready) ||
            !AddBundleBool(response, "status_native_overview_state_ready",
                           overview_ready) ||
            !AddBundleBool(response, "status_native_editing_state_ready",
                           editing_ready) ||
            !AddBundleBool(response, "status_native_xiaoai_state_ready",
                           AtomicLoad(&g_xiaoai_state_hook_state) ==
                                   uint32_t{3}) ||
            !AddBundleI64(response, kRuntimeStatusNonceExtra, nonce) ||
            !AddBundleI64(response, "status_native_profile_entry_offset",
                          profile_resolved
                                  ? static_cast<int64_t>(profile->entry_offset)
                                  : 0) ||
            !AddBundleI64(response, "status_native_side_offset",
                          profile_resolved
                                  ? static_cast<int64_t>(
                                          profile->side_handler_offset)
                                  : 0) ||
            !AddBundleI32(response, "status_native_runtime_profile_stage",
                          AtomicLoad(&g_dynamic_profile_state)) ||
            !AddBundleI32(response, "status_native_dart_resolver_stage",
                          AtomicLoad(&g_dart_profile_resolve_state)) ||
            !AddBundleI32(response, "status_native_dart_drawer_candidates",
                          AtomicLoad(&g_dart_drawer_candidate_count)) ||
            !AddBundleI32(response,
                          "status_native_dart_transition_candidates",
                          AtomicLoad(&g_dart_transition_candidate_count)) ||
            !AddBundleI32(response,
                          "status_native_dart_overview_enter_candidates",
                          AtomicLoad(&g_dart_overview_enter_candidate_count)) ||
            !AddBundleI32(response,
                          "status_native_dart_overview_exit_candidates",
                          AtomicLoad(&g_dart_overview_exit_candidate_count)) ||
            !AddBundleI32(response,
                          "status_native_dart_editing_candidates",
                          AtomicLoad(&g_dart_editing_candidate_count)) ||
            !AddBundleI32(response, "status_native_business_state",
                          business_state) ||
            !AddBundleI32(response, "status_native_bridge_state", bridge_state) ||
            !AddBundleI32(response, "status_native_drawer_state_hook",
                          AtomicLoad(&g_drawer_state_hook_state)) ||
            !AddBundleI32(response, "status_native_overview_state_hook",
                          AtomicLoad(&g_overview_state_hook_state)) ||
            !AddBundleI32(response, "status_native_editing_state_hook",
                          AtomicLoad(&g_editing_state_hook_state)) ||
            !AddBundleI32(response, "status_native_xiaoai_state_hook",
                          AtomicLoad(&g_xiaoai_state_hook_state)) ||
            !AddBundleI32(response, "status_native_xiaoai_candidates",
                          AtomicLoad(&g_dynamic_xiaoai_candidate_count)) ||
            !AddBundleI32(response, "status_native_receiver_state",
                          AtomicLoad(&g_native_receiver_state))) {
        __atomic_store_n(&g_runtime_status_last_state, uint32_t{3},
                         __ATOMIC_RELEASE);
        return false;
    }
    const bool sent = SendNativeBroadcast(kRuntimeStatusResponseAction,
                                           response);
    __atomic_store_n(&g_runtime_status_last_state,
                     sent ? uint32_t{1} : uint32_t{4}, __ATOMIC_RELEASE);
    if (sent) {
        __atomic_fetch_add(&g_runtime_status_response_count, uint32_t{1},
                           __ATOMIC_RELAXED);
    }
    __android_log_print(sent ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kLogTag,
            "runtime status response sent=%u nonce=%lld state=%u send_state=%u result=%u options=%u",
            sent ? 1u : 0u, static_cast<long long>(nonce),
            AtomicLoad(&g_runtime_status_last_state),
            AtomicLoad(&g_native_broadcast_send_state),
            AtomicLoad(&g_native_broadcast_result_tag),
            AtomicLoad(&g_native_broadcast_options_consumed));
    return sent;
}

bool TryQuerySystemUiArbiter(uint32_t maximum_attempts) {
    if (AtomicLoad(&g_systemui_arbiter_generation) > 0) return true;
    uint32_t attempts = AtomicLoad(&g_arbiter_query_attempts);
    while (attempts < maximum_attempts) {
        const uint32_t next = attempts + 1u;
        if (__atomic_compare_exchange_n(
                    &g_arbiter_query_attempts, &attempts, next, false,
                    __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            const bool sent = SendNativeBroadcast(kArbiterQueryAction, nullptr);
            __android_log_print(sent ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                                kLogTag,
                                "SystemUI arbiter query attempt=%u sent=%u "
                                "state=%u tag=%u consumed=%u",
                                next, sent ? 1u : 0u,
                                AtomicLoad(&g_native_broadcast_send_state),
                                AtomicLoad(&g_native_broadcast_result_tag),
                                AtomicLoad(&g_native_broadcast_options_consumed));
            return sent;
        }
    }
    return false;
}

void TryInstallArbiterBridge() {
    if (!IsLauncherHookProcess() ||
            AtomicLoad(&g_business_hook_state) != uint32_t{3}) {
        return;
    }
    uint32_t state = AtomicLoad(&g_arbiter_bridge_hook_state);
    if (state == 3u || state == 4u) return;
    uint32_t expected = state == 2u ? uint32_t{2} : uint32_t{0};
    if (!__atomic_compare_exchange_n(
            &g_arbiter_bridge_hook_state, &expected, uint32_t{1}, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }

    NativeSymbolResolver* resolver = NewNativeSymbolResolver(
            kBroadcastPrivatePath, nullptr);
    size_t receiver_size = 0u;
    size_t broadcast_size = 0u;
    size_t send_size = 0u;
    void* receiver_on_receive = resolver == nullptr ? nullptr
            : LookupNativeSymbol(resolver, kBroadcastReceiverOnReceiveSymbol,
                                 false, &receiver_size);
    void* broadcast_intent_with_feature = resolver == nullptr ? nullptr
            : LookupNativeSymbol(resolver, kBroadcastIntentWithFeatureSymbol,
                                 false, &broadcast_size);
    void* broadcast_send = resolver == nullptr ? nullptr
            : LookupNativeSymbol(resolver, kBroadcastSendSymbol, false,
                                 &send_size);
    void* broadcast_image_base = resolver == nullptr ? nullptr
            : GetNativeBaseAddress(resolver);
    void** broadcast_intent_with_feature_slot = resolver == nullptr ? nullptr
            : LookupNativePltSlot(resolver,
                                  kBroadcastIntentWithFeatureSymbol);
    if (receiver_on_receive == nullptr || broadcast_intent_with_feature == nullptr ||
            broadcast_send == nullptr || receiver_size == 0u ||
            broadcast_size == 0u || send_size == 0u) {
        if (resolver != nullptr) FreeNativeSymbolResolver(resolver);
        // The broadcast dylib is not a guaranteed dependency at launcher
        // entry. HookDlopen/HookDlsym will retry after later native loading.
        AtomicStore(&g_native_receiver_state, uint32_t{98});
        AtomicStore(&g_arbiter_bridge_hook_state, uint32_t{2});
        return;
    }
    // Installation is process-local. A normal MiuiHome replacement forked by
    // the same injected spawner must install its own bridge; the atomic state
    // above already prevents duplicate mutation inside one process. Exact
    // process, resolved image and symbol address, GOT address/value, and
    // the launcher profile's code fingerprints remain the fail-closed guards.
    const bool broadcast_got_installed = InstallBroadcastIntentWithFeatureGot(
            broadcast_intent_with_feature, broadcast_image_base,
            broadcast_intent_with_feature_slot);
    FreeNativeSymbolResolver(resolver);
    if (!broadcast_got_installed) {
        AtomicStore(&g_arbiter_bridge_hook_state,
                AtomicLoad(&g_native_receiver_state) == uint32_t{102}
                        ? uint32_t{2} : uint32_t{4});
        return;
    }
    if (AtomicLoad(&g_original_broadcast_send) == nullptr) {
        if (InstallInlineHook(
                    broadcast_send,
                    reinterpret_cast<void*>(MiuiHomeHyosBroadcastSendHook),
                    &g_original_broadcast_send) != kHookSuccess ||
                AtomicLoad(&g_original_broadcast_send) == nullptr) {
            RestoreBroadcastIntentWithFeatureGot();
            AtomicStore(&g_native_receiver_state, uint32_t{107});
            AtomicStore(&g_arbiter_bridge_hook_state, uint32_t{4});
            return;
        }
        Log(ANDROID_LOG_INFO,
            "capturing private broadcast runtime from send_broadcast");
    }
    if (InstallInlineHook(
                receiver_on_receive,
                reinterpret_cast<void*>(HookBroadcastReceiverOnReceive),
                &g_original_broadcast_receiver_on_receive) != kHookSuccess ||
            AtomicLoad(&g_original_broadcast_receiver_on_receive) == nullptr) {
        RestoreBroadcastIntentWithFeatureGot();
        AtomicStore(&g_native_receiver_state, uint32_t{108});
        AtomicStore(&g_arbiter_bridge_hook_state, uint32_t{4});
        return;
    }
    AtomicStore(&g_native_receiver_state, uint32_t{110});
    AtomicStore(&g_arbiter_bridge_hook_state, uint32_t{3});
    Log(ANDROID_LOG_INFO,
        "SystemUI input-arbiter bridge installed without receiver registration");
    if (TryQuerySystemUiArbiter(1u)) {
        Log(ANDROID_LOG_INFO,
            "queried SystemUI input arbiter with shared identity");
    } else {
        Log(ANDROID_LOG_ERROR,
            "failed to query SystemUI input arbiter with shared identity");
    }
}

bool PublishAcceptedDown(uint32_t edge) {
    if (!EnsureDartStateOwnerForCurrentProcess()) return false;
    const int64_t generation = AtomicLoad(&g_systemui_arbiter_generation);
    if (!g_pending_down.valid || edge > 1u || generation <= 0 ||
            AtomicLoad(&g_systemui_arbiter_ready) == 0u) {
        return false;
    }
    BundleDefaultFn bundle_default = ResolveLauncherSymbol<BundleDefaultFn>(
            "Bundle_default");
    if (bundle_default == nullptr) return false;
    void* extras = bundle_default();
    if (extras == nullptr ||
            !AddBundleBool(extras, "input_accepted", true) ||
            !AddBundleI32(extras, "input_event_id", g_pending_down.event_id) ||
            !AddBundleI64(extras, "input_down_time", g_pending_down.down_time) ||
            !AddBundleI32(extras, "input_device_id", g_pending_down.device_id) ||
            !AddBundleI32(extras, "input_source", g_pending_down.source) ||
            !AddBundleI32(extras, "input_display_id", 0) ||
            !AddBundleI32(extras, "input_edge", static_cast<int32_t>(edge)) ||
            !AddBundleI64(extras, kLauncherStateOwnerEpochExtra,
                          static_cast<int64_t>(AtomicLoad(
                                  &g_dart_state_owner_epoch))) ||
            !AddBundleI64(extras, "input_arbiter_generation", generation)) {
        return false;
    }
    return SendNativeBroadcast(kAcceptedStateAction, extras);
}

bool SameDownIdentity(const PendingDownIdentity& first,
                      const PendingDownIdentity& second) {
    return first.valid && second.valid &&
            first.event_id == second.event_id &&
            first.down_time == second.down_time &&
            first.device_id == second.device_id &&
            first.source == second.source && first.edge == second.edge;
}

bool CurrentMotionMatchesOwnedStream(void* monitor) {
    if (!g_systemui_owns_back_stream || !g_owned_back_stream.valid ||
            monitor == nullptr || g_last_motion_event == 0u) {
        return false;
    }
    MotionEventLongFn get_down_time = reinterpret_cast<MotionEventLongFn>(
            AtomicLoad(&g_motion_get_down_time));
    MotionEventIntFn get_device_id = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_device_id));
    MotionEventIntFn get_source = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_source));
    if (get_down_time == nullptr || get_device_id == nullptr ||
            get_source == nullptr) {
        return false;
    }
    void* event = reinterpret_cast<void*>(g_last_motion_event);
    const PendingDownIdentity& down = g_owned_back_stream.down;
    // MotionEvent.getId() identifies one MotionEvent object, not the whole
    // pointer stream. MOVE/UP/CANCEL therefore have different event IDs from
    // the authenticated DOWN. Stream continuity is the immutable downTime,
    // device and source tuple; eventId remains part of DOWN authentication
    // only.
    const bool same_stream = get_down_time(event) == down.down_time &&
            get_device_id(event) == down.device_id &&
            get_source(event) == down.source;
    if (!same_stream) return false;
    const uintptr_t monitor_identity = reinterpret_cast<uintptr_t>(monitor);
    if (g_owned_back_stream.monitor == 0u) {
        g_owned_back_stream.monitor = monitor_identity;
    }
    return g_owned_back_stream.monitor == monitor_identity;
}

bool CurrentEventMatchesOwnedStream(void* event) {
    if (!g_systemui_owns_back_stream || !g_owned_back_stream.valid ||
            event == nullptr) {
        return false;
    }
    MotionEventLongFn get_down_time = reinterpret_cast<MotionEventLongFn>(
            AtomicLoad(&g_motion_get_down_time));
    MotionEventIntFn get_device_id = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_device_id));
    MotionEventIntFn get_source = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_source));
    if (get_down_time == nullptr || get_device_id == nullptr ||
            get_source == nullptr) {
        return false;
    }
    const PendingDownIdentity& down = g_owned_back_stream.down;
    return get_down_time(event) == down.down_time &&
            get_device_id(event) == down.device_id &&
            get_source(event) == down.source;
}

void HandleInputMonitorPilfer(void* monitor, uintptr_t return_pc) {
    uintptr_t caller_base = 0u;
    const miui_home_profiles::LauncherProfile* caller_profile = nullptr;
    InputMonitorPilferFn original = nullptr;
    for (uint32_t index = 0u; index < kLauncherInputSlotCount; ++index) {
        LauncherInputHookSlot& slot = g_launcher_input_slots[index];
        const uintptr_t base = AtomicLoad(&slot.base);
        const auto* profile = AtomicLoad(&slot.profile);
        if (AtomicLoad(&slot.state) == uint32_t{3} && profile != nullptr &&
                return_pc >= base &&
                return_pc - base < profile->image_span) {
            caller_base = base;
            caller_profile = profile;
            original = reinterpret_cast<InputMonitorPilferFn>(
                    AtomicLoad(&slot.original_pilfer));
            break;
        }
    }
    if (original == nullptr) {
        original = reinterpret_cast<InputMonitorPilferFn>(
                AtomicLoad(&g_original_input_monitor_pilfer));
    }
    __atomic_fetch_add(&g_pilfer_hook_count, uint32_t{1}, __ATOMIC_RELAXED);
    const uintptr_t offset_base = caller_base != 0u
            ? caller_base : reinterpret_cast<uintptr_t>(g_launcher_base);
    if (caller_profile == nullptr) caller_profile = CurrentLauncherProfile();
    const intptr_t return_offset = offset_base == 0u
            ? intptr_t{-1}
            : static_cast<intptr_t>(return_pc - offset_base);

    const bool exact_primary_return = offset_base != 0u &&
            caller_profile != nullptr &&
            caller_profile->accepted_pilfer_return_offset != 0u &&
            return_offset == static_cast<intptr_t>(
                    caller_profile->accepted_pilfer_return_offset);
    const bool exact_caller_fingerprint = caller_profile != nullptr &&
            caller_profile->accepted_pilfer_caller != nullptr &&
            caller_profile->accepted_pilfer_caller_size != 0u &&
            return_pc >= 8u &&
            memcmp(reinterpret_cast<const void*>(return_pc - 8u),
                   caller_profile->accepted_pilfer_caller,
                   caller_profile->accepted_pilfer_caller_size) == 0;
    const bool ordinary_back_boundary = exact_primary_return ||
            exact_caller_fingerprint;
    if ((ordinary_back_boundary ||
            (caller_profile != nullptr &&
             caller_profile->home_pilfer_return_offset != 0u &&
             return_offset == static_cast<intptr_t>(
                    caller_profile->home_pilfer_return_offset))) &&
            CurrentMotionMatchesOwnedStream(monitor)) {
        __atomic_fetch_add(&g_owned_stream_pilfer_suppressed_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
        return;
    }
    if (original != nullptr) original(monitor);
}

void CaptureMotionAction(void* event, int32_t action);
void RepairBusinessHooksIfRemapped(uint32_t slot_index);

void HookGestureStubBackHandler(void* stub_window, void* event) {
    GestureStubBackHandlerFn original =
            reinterpret_cast<GestureStubBackHandlerFn>(
                    AtomicLoad(&g_original_gesture_stub_back_handler));
    MotionEventIntFn get_action_masked =
            reinterpret_cast<MotionEventIntFn>(
                    AtomicLoad(&g_original_motion_get_action_masked));
    const int32_t action = event != nullptr && get_action_masked != nullptr
            ? get_action_masked(event) : -1;
    __atomic_fetch_add(&g_stub_back_handler_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    __atomic_store_n(&g_stub_back_action_last,
                     static_cast<uint32_t>(action), __ATOMIC_RELAXED);
    uint32_t stub_edge = 0xffffffffu;
    const auto* profile = CurrentLauncherProfile();
    if (stub_window != nullptr && profile != nullptr &&
            profile->side_edge_field_offset != 0u) {
        stub_edge = static_cast<uint32_t>(
                *reinterpret_cast<const uint8_t*>(
                reinterpret_cast<const uint8_t*>(stub_window) +
                profile->side_edge_field_offset));
        __atomic_store_n(&g_stub_back_edge_last, stub_edge, __ATOMIC_RELAXED);
    }
    volatile uint32_t* counter = nullptr;
    switch (action) {
        case 0: counter = &g_stub_back_down_count; break;
        case 1: counter = &g_stub_back_up_count; break;
        case 2: counter = &g_stub_back_move_count; break;
        case 3: counter = &g_stub_back_cancel_count; break;
        default: break;
    }
    if (counter != nullptr) {
        __atomic_fetch_add(counter, uint32_t{1}, __ATOMIC_RELAXED);
    }
    const bool ownership_enabled = __atomic_load_n(
            &g_enable_systemui_ownership, __ATOMIC_RELAXED) != 0u;
    if (!ownership_enabled || action < 0 || event == nullptr) {
        if (original != nullptr) original(stub_window, event);
        return;
    }

    if (action == 0) {
        // This is the exact side-only GestureStubView accepted-DOWN boundary.
        // Re-capture the immutable identity here instead of relying on an
        // earlier shared GestureInputMonitor call on the same thread.
        CaptureMotionAction(event, action);
        __atomic_fetch_add(&g_accepted_processor_down_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
        if (!g_pending_down.valid || stub_edge > 1u ||
                g_pending_down.edge != stub_edge) {
            if (original != nullptr) original(stub_window, event);
            return;
        }
        if (AtomicLoad(&g_arbiter_bridge_hook_state) == uint32_t{3} &&
                (AtomicLoad(&g_systemui_arbiter_generation) <= 0 ||
                 AtomicLoad(&g_systemui_arbiter_ready) == 0u)) {
            // A readiness warmup is always Xiaomi-native. Never suppress its
            // DOWN while the SystemUI spy has no matching ready generation.
            if (original != nullptr) original(stub_window, event);
            TryQuerySystemUiArbiter(2u);
            return;
        }
        if (PublishAcceptedDown(stub_edge)) {
            __atomic_fetch_add(&g_accepted_processor_publish_count,
                               uint32_t{1}, __ATOMIC_RELAXED);
            g_systemui_owns_back_stream = true;
            g_owned_back_stream.monitor = 0u;
            g_owned_back_stream.motion_event =
                    reinterpret_cast<uintptr_t>(event);
            g_owned_back_stream.generation =
                    AtomicLoad(&g_systemui_arbiter_generation);
            g_owned_back_stream.down = g_pending_down;
            g_owned_back_stream.valid = true;
            g_pending_down.valid = false;
            Log(ANDROID_LOG_INFO,
                "published GestureStubView accepted Back DOWN");
        }
    }

    const bool owned_current = CurrentEventMatchesOwnedStream(event);
    if (g_systemui_owns_back_stream && !owned_current) {
        // Replacement or stale input cannot inherit an older accepted token.
        g_systemui_owns_back_stream = false;
        g_owned_back_stream.valid = false;
    }
    if (!owned_current) {
        if (original != nullptr) original(stub_window, event);
        return;
    }

    // Returning to UiWindow::on_window_motion_event preserves its native
    // cleanup while preventing only GestureInputBackHelper::on_touch_event,
    // Xiaomi's arrow, injection, and direct OPEN-break path for this exact
    // SystemUI-owned physical stream.
    __atomic_fetch_add(&g_gesture_processor_suppressed_count,
                       uint32_t{1}, __ATOMIC_RELAXED);
    __atomic_fetch_add(&g_gesture_processor_boundary_return_count,
                       uint32_t{1}, __ATOMIC_RELAXED);
    if (action == 1 || action == 3) {
        g_systemui_owns_back_stream = false;
        g_owned_back_stream.valid = false;
    }
}

void HookGestureStubPointerHandler(void* stub, void* event,
                                   void* state, uint32_t mode) {
    __atomic_fetch_add(&g_gesture_processor_entry_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    GestureStubPointerHandlerFn original =
            reinterpret_cast<GestureStubPointerHandlerFn>(
                    AtomicLoad(&g_original_gesture_stub_pointer_handler));
    MotionEventIntFn get_action_masked =
            reinterpret_cast<MotionEventIntFn>(
                    AtomicLoad(&g_original_motion_get_action_masked));
    const int32_t action = event != nullptr && get_action_masked != nullptr
            ? get_action_masked(event) : -1;

    if (action == 0) {
        // This outer routine is the shared GestureInputMonitor dispatcher,
        // not a side-only GestureStub entry. It sees Home as well as Back.
        // Retain transparent classification evidence and identity continuity,
        // but never claim from this shared path. The side-only GestureStubView
        // handler owns publication. Always run the unified Rust borrow unlock.
        CaptureMotionAction(event, action);
    }

    if (original != nullptr) original(stub, event, state, mode);
    if (action == 0 && state != nullptr) {
        const uint32_t post_type = *reinterpret_cast<const uint32_t*>(
                reinterpret_cast<const uint8_t*>(state) + 0x140u);
        __atomic_store_n(&g_outer_down_post_type_last, post_type,
                         __ATOMIC_RELAXED);
        volatile uint32_t* counter = nullptr;
        switch (post_type) {
            case 0u: counter = &g_outer_down_post_type_0_count; break;
            case 1u: counter = &g_outer_down_post_type_1_count; break;
            case 2u: counter = &g_outer_down_post_type_2_count; break;
            case 3u: counter = &g_outer_down_post_type_3_count; break;
            default: break;
        }
        if (counter != nullptr) {
            __atomic_fetch_add(counter, uint32_t{1}, __ATOMIC_RELAXED);
        }
    }
    if (AtomicLoad(&g_arbiter_bridge_hook_state) == uint32_t{3} &&
            AtomicLoad(&g_systemui_arbiter_generation) <= 0) {
        TryQuerySystemUiArbiter(2u);
    }
}

void HookGestureBackTouchProcessor(void* processor, void* event, void* state) {
    GestureBackTouchProcessorFn original =
            reinterpret_cast<GestureBackTouchProcessorFn>(
                    AtomicLoad(&g_original_gesture_back_touch_processor));
    const auto* profile = CurrentLauncherProfile();
    const uint32_t gesture_type = processor == nullptr || profile == nullptr ||
            profile->gesture_type_field_offset == 0u
            ? uint32_t{0}
            : *reinterpret_cast<const uint32_t*>(
                    reinterpret_cast<const uint8_t*>(processor) +
                    profile->gesture_type_field_offset);
    __atomic_store_n(&g_inner_gesture_type_last, gesture_type,
                     __ATOMIC_RELAXED);
    if (gesture_type == 1u) {
        __atomic_fetch_add(&g_inner_gesture_type_1_count, uint32_t{1},
                           __ATOMIC_RELAXED);
    } else if (gesture_type == 2u) {
        __atomic_fetch_add(&g_inner_gesture_type_2_count, uint32_t{1},
                           __ATOMIC_RELAXED);
    }

    // The shared trigger_gesture dispatcher is Home/monitoring state, not the
    // accepted side Back boundary. Keep it fully native even for an owned
    // GestureStubView stream.
    if (original != nullptr) original(processor, event, state);
}

void CaptureMotionAction(void* event, int32_t action) {
    if ((action & 0xff) != 0 || event == nullptr) return;
    MotionEventIntFn get_id = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_id));
    MotionEventLongFn get_down_time = reinterpret_cast<MotionEventLongFn>(
            AtomicLoad(&g_motion_get_down_time));
    MotionEventIntFn get_device_id = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_device_id));
    MotionEventIntFn get_source = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_source));
    MotionEventFloatFn get_raw_x = reinterpret_cast<MotionEventFloatFn>(
            AtomicLoad(&g_motion_get_raw_x));
    MotionEventFloatFn get_raw_y = reinterpret_cast<MotionEventFloatFn>(
            AtomicLoad(&g_motion_get_raw_y));
    PendingDownIdentity candidate{};
    candidate.valid = get_id != nullptr && get_down_time != nullptr &&
            get_device_id != nullptr && get_source != nullptr &&
            get_raw_x != nullptr &&
            (get_raw_y != nullptr || AtomicLoad(&g_motion_get_y) != nullptr);
    if (!candidate.valid) {
        g_systemui_owns_back_stream = false;
        g_owned_back_stream.valid = false;
        g_pending_down.valid = false;
        return;
    }
    const float raw_x = get_raw_x(event);
    // This exact 4371 branch is reached only for a physical left/right edge
    // stream. 256px is outside both native edge bands and therefore separates
    // them without depending on orientation-specific display width.
    if (!(raw_x >= 0.0f && raw_x <= 16384.0f)) {
        g_systemui_owns_back_stream = false;
        g_owned_back_stream.valid = false;
        g_pending_down.valid = false;
        return;
    }
    candidate.event_id = get_id(event);
    candidate.down_time = get_down_time(event);
    candidate.device_id = get_device_id(event);
    candidate.source = get_source(event);
    candidate.edge = raw_x <= 256.0f ? uint32_t{0} : uint32_t{1};
    candidate.raw_y = ReadMotionY(event);
    const bool repeated_owned_down = g_systemui_owns_back_stream &&
            g_owned_back_stream.valid &&
            SameDownIdentity(candidate, g_owned_back_stream.down);
    if (!repeated_owned_down) {
        g_systemui_owns_back_stream = false;
        g_owned_back_stream.valid = false;
    }
    g_pending_down = candidate;
}

// Contextual Search's long-press completion can run after the MotionEvent
// reader has crossed a native callback boundary.  Keep a small process-wide
// identity/value snapshot rather than relying on the back-arbiter TLS state.
// All fields are published atomically; the valid bit is written last.
void PublishContextualMotionSnapshot(void* event, int32_t action) {
    if (event == nullptr) return;
    MotionEventLongFn get_down_time = reinterpret_cast<MotionEventLongFn>(
            AtomicLoad(&g_motion_get_down_time));
    MotionEventIntFn get_device_id = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_device_id));
    MotionEventIntFn get_source = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&g_motion_get_source));
    MotionEventFloatFn get_raw_y = reinterpret_cast<MotionEventFloatFn>(
            AtomicLoad(&g_motion_get_raw_y));
    MotionEventFloatFn get_y = reinterpret_cast<MotionEventFloatFn>(
            AtomicLoad(&g_motion_get_y));
    if (get_down_time == nullptr || get_device_id == nullptr ||
            get_source == nullptr || (get_raw_y == nullptr && get_y == nullptr)) {
        return;
    }
    const int64_t down_time = get_down_time(event);
    const int32_t device_id = get_device_id(event);
    const int32_t source = get_source(event);
    const float raw_y = ReadMotionY(event);
    const uint32_t masked_action = static_cast<uint32_t>(action & 0xff);
    if (masked_action == 0u) {
        __atomic_store_n(&g_contextual_motion_snapshot_valid, uint32_t{0},
                         __ATOMIC_RELEASE);
        __atomic_store_n(&g_contextual_motion_snapshot_down_time, down_time,
                         __ATOMIC_RELAXED);
        __atomic_store_n(&g_contextual_motion_snapshot_device_id, device_id,
                         __ATOMIC_RELAXED);
        __atomic_store_n(&g_contextual_motion_snapshot_source, source,
                         __ATOMIC_RELAXED);
        __atomic_store_n(&g_contextual_motion_snapshot_down_y_bits,
                         FloatBits(raw_y),
                         __ATOMIC_RELAXED);
        __atomic_store_n(&g_contextual_motion_snapshot_current_y_bits,
                         FloatBits(raw_y),
                         __ATOMIC_RELAXED);
        __atomic_store_n(&g_contextual_motion_snapshot_valid, uint32_t{1},
                         __ATOMIC_RELEASE);
        return;
    }
    if (__atomic_load_n(&g_contextual_motion_snapshot_valid,
                        __ATOMIC_ACQUIRE) == 0u ||
            __atomic_load_n(&g_contextual_motion_snapshot_down_time,
                            __ATOMIC_RELAXED) != down_time ||
            __atomic_load_n(&g_contextual_motion_snapshot_device_id,
                            __ATOMIC_RELAXED) != device_id ||
            __atomic_load_n(&g_contextual_motion_snapshot_source,
                            __ATOMIC_RELAXED) != source) {
        return;
    }
    __atomic_store_n(&g_contextual_motion_snapshot_current_y_bits,
                     FloatBits(raw_y),
                     __ATOMIC_RELAXED);
}

NativeResult HookPackageManagerHasSystemFeatureForSlot(
        void* package_manager, const char* name, size_t name_length,
        uint32_t flags, uint32_t slot_index) {
    if (slot_index >= kLauncherInputSlotCount) return NativeResult{};
    LauncherInputHookSlot& slot = g_launcher_input_slots[slot_index];
    PackageManagerHasSystemFeatureFn original =
            reinterpret_cast<PackageManagerHasSystemFeatureFn>(
                    AtomicLoad(&slot.original_has_system_feature));
    if (original == nullptr) return NativeResult{};
    NativeResult result = original(package_manager, name, name_length, flags);
    const bool contextual_feature = IsExactCallbackName(
            name, name_length, kPlatformContextualSearchFeature) ||
            IsExactCallbackName(name, name_length,
                                kGoogleContextualSearchFeature);
    if (!contextual_feature) return result;
    __atomic_fetch_add(&g_contextual_feature_query_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    if (AtomicLoad(&g_contextual_search_enabled) != 0u &&
            IsNativeSuccess(result) && result.bytes[1] == uint8_t{0}) {
        // HyperOS PackageManager_has_system_feature returns Result<bool> in
        // the 48-byte native wrapper: byte 0 is the Result tag and byte 1 is
        // the successful boolean. Preserve errors and already-true results.
        result.bytes[1] = uint8_t{1};
        __atomic_fetch_add(&g_contextual_feature_override_count, uint32_t{1},
                           __ATOMIC_RELAXED);
    }
    return result;
}

NativeResult HookPackageManagerHasSystemFeature0(
        void* package_manager, const char* name, size_t name_length,
        uint32_t flags) {
    return HookPackageManagerHasSystemFeatureForSlot(
            package_manager, name, name_length, flags, 0u);
}
NativeResult HookPackageManagerHasSystemFeature1(
        void* package_manager, const char* name, size_t name_length,
        uint32_t flags) {
    return HookPackageManagerHasSystemFeatureForSlot(
            package_manager, name, name_length, flags, 1u);
}
NativeResult HookPackageManagerHasSystemFeature2(
        void* package_manager, const char* name, size_t name_length,
        uint32_t flags) {
    return HookPackageManagerHasSystemFeatureForSlot(
            package_manager, name, name_length, flags, 2u);
}
NativeResult HookPackageManagerHasSystemFeature3(
        void* package_manager, const char* name, size_t name_length,
        uint32_t flags) {
    return HookPackageManagerHasSystemFeatureForSlot(
            package_manager, name, name_length, flags, 3u);
}

uint64_t HookBundleGetBooleanForSlot(void* bundle, const char* key,
                                     size_t key_length,
                                     uint32_t slot_index,
                                     uintptr_t return_pc) {
    if (slot_index >= kLauncherInputSlotCount) return uint64_t{1};
    LauncherInputHookSlot& slot = g_launcher_input_slots[slot_index];
    BundleGetBoolFn original = reinterpret_cast<BundleGetBoolFn>(
            AtomicLoad(&slot.original_bundle_get_boolean));
    if (original == nullptr) return uint64_t{1};
    const uint64_t result = original(bundle, key, key_length);
    const uintptr_t base = AtomicLoad(&slot.base);
    const auto* profile = AtomicLoad(&slot.profile);
    const bool exact_xiaoai_call =
            AtomicLoad(&slot.xiaoai_state) == uint32_t{3} &&
            base != 0u && profile != nullptr &&
            profile->xiaoai_bundle_bool_return_offset != 0u &&
            return_pc == base +
                    profile->xiaoai_bundle_bool_return_offset &&
            key != nullptr && key_length == 7u &&
            memcmp(key, "isEnter", 7u) == 0;
    if (!exact_xiaoai_call || (result & uint64_t{1}) != 0u) return result;
    // The exact live voice-assistant window and this caller's isEnter result
    // were observed together: true means the overlay is showing. Publish the
    // semantic visibility while preserving malformed/error results above.
    const uint32_t observed = ((result >> 8u) & uint64_t{1}) != 0u
            ? uint32_t{2} : uint32_t{1};
    __atomic_fetch_add(&g_xiaoai_state_observe_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    __atomic_store_n(&g_xiaoai_state_observed, observed, __ATOMIC_RELEASE);
    PublishXiaoAiStateForCurrentGeneration();
    return result;
}

#define DEFINE_XIAOAI_BOOLEAN_HOOK(index)                                  \
    uint64_t HookBundleGetBoolean##index(                                  \
            void* bundle, const char* key, size_t key_length) {            \
        const uintptr_t return_pc = reinterpret_cast<uintptr_t>(           \
                __builtin_extract_return_addr(__builtin_return_address(0))); \
        return HookBundleGetBooleanForSlot(                                \
                bundle, key, key_length, index##u, return_pc);             \
    }

DEFINE_XIAOAI_BOOLEAN_HOOK(0)
DEFINE_XIAOAI_BOOLEAN_HOOK(1)
DEFINE_XIAOAI_BOOLEAN_HOOK(2)
DEFINE_XIAOAI_BOOLEAN_HOOK(3)

#undef DEFINE_XIAOAI_BOOLEAN_HOOK

const miui_home_profiles::LauncherProfile* ResolveDartFeatureProfile(
        void* dart_handle, bool retry_rejected) {
    const auto* current = AtomicLoad(&g_resolved_dart_profile);
    if (current != nullptr) return current;
    const auto* launcher = CurrentLauncherProfile();
    if (dart_handle == nullptr || !NeedsDartFeatureResolution(launcher)) {
        return nullptr;
    }
    uint32_t expected = 0u;
    if (retry_rejected) {
        const uint32_t state = AtomicLoad(&g_dart_profile_resolve_state);
        if (state < static_cast<uint32_t>(
                           miui_home_dart_profile::ResolveStage::
                                   kRejectedElf)) {
            return nullptr;
        }
        expected = state;
    }
    if (!__atomic_compare_exchange_n(&g_dart_profile_resolve_state, &expected,
                                     uint32_t{1}, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return nullptr;
    }
    void* instructions = dlsym(dart_handle,
                               kDartSnapshotInstructionsSymbol);
    void* build_id = dlsym(dart_handle, kDartSnapshotBuildIdSymbol);
    Dl_info info{};
    const bool mapped = instructions != nullptr && build_id != nullptr &&
            dladdr(instructions, &info) != 0 && info.dli_fbase != nullptr &&
            IsDartLibraryPath(info.dli_fname);
    auto* base = mapped ? static_cast<uint8_t*>(info.dli_fbase) : nullptr;
    const bool resolved = mapped &&
            miui_home_dart_profile::ResolveDartFeatureProfile(
                    base, instructions, build_id, *launcher,
                    &g_dart_profile_storage, &g_dart_profile_diagnostics);
    __atomic_store_n(&g_dart_drawer_candidate_count,
            g_dart_profile_diagnostics.drawer_candidate_count,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_transition_candidate_count,
            g_dart_profile_diagnostics.transition_candidate_count,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_overview_enter_candidate_count,
            g_dart_profile_diagnostics.overview_enter_candidate_count,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_overview_exit_candidate_count,
            g_dart_profile_diagnostics.overview_exit_candidate_count,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_editing_candidate_count,
            g_dart_profile_diagnostics.editing_candidate_count,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_drawer_resolved_offset,
            g_dart_profile_diagnostics.drawer_progress_end_offset,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_transition_resolved_offset,
            g_dart_profile_diagnostics.drawer_transition_complete_offset,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_overview_enter_resolved_offset,
            g_dart_profile_diagnostics.overview_enter_offset,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_overview_exit_resolved_offset,
            g_dart_profile_diagnostics.overview_exit_offset,
            __ATOMIC_RELAXED);
    __atomic_store_n(&g_dart_editing_resolved_offset,
            g_dart_profile_diagnostics.editing_query_offset,
            __ATOMIC_RELAXED);
    if (!resolved) {
        __atomic_store_n(
                &g_dart_profile_resolve_state,
                static_cast<uint32_t>(g_dart_profile_diagnostics.stage),
                __ATOMIC_RELEASE);
        __android_log_print(
                ANDROID_LOG_WARN, kLogTag,
                "mapped Dart AOT feature family was absent or ambiguous "
                "stage=%u drawer=%u transition=%u overview=%u/%u editing=%u",
                static_cast<uint32_t>(g_dart_profile_diagnostics.stage),
                g_dart_profile_diagnostics.drawer_candidate_count,
                g_dart_profile_diagnostics.transition_candidate_count,
                g_dart_profile_diagnostics.overview_enter_candidate_count,
                g_dart_profile_diagnostics.overview_exit_candidate_count,
                g_dart_profile_diagnostics.editing_candidate_count);
        return nullptr;
    }
    AtomicStore(&g_resolved_dart_profile,
                static_cast<const miui_home_profiles::LauncherProfile*>(
                        &g_dart_profile_storage.profile));
    __atomic_store_n(
            &g_dart_profile_resolve_state,
            static_cast<uint32_t>(
                    miui_home_dart_profile::ResolveStage::kComplete),
            __ATOMIC_RELEASE);
    __atomic_store_n(&g_drawer_state_hook_state, uint32_t{1},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_overview_state_hook_state, uint32_t{1},
                     __ATOMIC_RELEASE);
    __atomic_store_n(&g_editing_state_hook_state, uint32_t{1},
                     __ATOMIC_RELEASE);
    __android_log_print(
            ANDROID_LOG_INFO, kLogTag,
            "resolved mapped Dart drawer, Overview, and editing family "
            "candidates=%u/%u/%u/%u/%u offsets=0x%" PRIxPTR
            "/0x%" PRIxPTR "/0x%" PRIxPTR "/0x%" PRIxPTR
            "/0x%" PRIxPTR,
            g_dart_profile_diagnostics.drawer_candidate_count,
            g_dart_profile_diagnostics.transition_candidate_count,
            g_dart_profile_diagnostics.overview_enter_candidate_count,
            g_dart_profile_diagnostics.overview_exit_candidate_count,
            g_dart_profile_diagnostics.editing_candidate_count,
            g_dart_profile_diagnostics.drawer_progress_end_offset,
            g_dart_profile_diagnostics.drawer_transition_complete_offset,
            g_dart_profile_diagnostics.overview_enter_offset,
            g_dart_profile_diagnostics.overview_exit_offset,
            g_dart_profile_diagnostics.editing_query_offset);
    return &g_dart_profile_storage.profile;
}

void* HookLauncherDlopenForSlot(
        const char* filename, int flags, uint32_t slot_index) {
    if (slot_index >= kLauncherInputSlotCount) return nullptr;
    LauncherInputHookSlot& slot = g_launcher_input_slots[slot_index];
    DlopenFn original = reinterpret_cast<DlopenFn>(
            AtomicLoad(&slot.original_dlopen));
    if (original == nullptr) return nullptr;
    void* result = original(filename, flags);
    if (result != nullptr && IsDartLibraryPath(filename)) {
        const auto* profile = ResolveDartFeatureProfile(result);
        if (profile != nullptr) {
            TryInstallDartDrawerStateHook(result, profile);
            TryInstallDartOverviewStateHook(result, profile);
            TryInstallDartEditingStateHook(result, profile);
        } else {
            const auto* launcher = CurrentLauncherProfile();
            const uint32_t dart_stage = AtomicLoad(
                    &g_dart_profile_resolve_state);
            if (launcher != nullptr && g_launcher_base != nullptr &&
                    StringsEqual(launcher->id, "runtime-side-v1") &&
                    dart_stage >= static_cast<uint32_t>(
                            miui_home_dart_profile::ResolveStage::
                                    kRejectedElf)) {
                if (AtomicLoad(&g_overview_state_hook_state) == uint32_t{1}) {
                    __atomic_store_n(&g_overview_state_hook_state,
                                     uint32_t{2}, __ATOMIC_RELEASE);
                }
                if (AtomicLoad(&g_editing_state_hook_state) == uint32_t{1}) {
                    __atomic_store_n(&g_editing_state_hook_state,
                                     uint32_t{2}, __ATOMIC_RELEASE);
                }
            }
        }
    }
    return result;
}

void* HookLauncherDlopen0(const char* filename, int flags) {
    return HookLauncherDlopenForSlot(filename, flags, 0u);
}
void* HookLauncherDlopen1(const char* filename, int flags) {
    return HookLauncherDlopenForSlot(filename, flags, 1u);
}
void* HookLauncherDlopen2(const char* filename, int flags) {
    return HookLauncherDlopenForSlot(filename, flags, 2u);
}
void* HookLauncherDlopen3(const char* filename, int flags) {
    return HookLauncherDlopenForSlot(filename, flags, 3u);
}

void MaintainLauncherHooksOnActionDown(uint32_t slot_index) {
    RepairBusinessHooksIfRemapped(slot_index);
    EnsureDartStatePublisherThread();
    const auto* profile = CurrentDartFeatureProfile();
    if (profile == nullptr &&
            NeedsDartFeatureResolution(CurrentLauncherProfile()) &&
            AtomicLoad(&g_dart_profile_resolve_state) == uint32_t{0}) {
        // libflutter may own the AOT load rather than calling through
        // libapp_launcher's PLT. Acquire only the already-loaded image.
        TryResolveAndInstallLoadedDartProfile();
        profile = CurrentDartFeatureProfile();
    }
    const uint32_t remap_mask = DetectDartStateHookRemapMask(profile);
    uint32_t expected = 0u;
    if (remap_mask != 0u && __atomic_compare_exchange_n(
                &g_dart_remap_repair_in_flight, &expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        __atomic_fetch_or(&g_dart_remap_pending_mask, remap_mask,
                          __ATOMIC_ACQ_REL);
        if (PrepareDartStateHookRetirement(remap_mask)) {
            InvalidateDartStateOwnerForRemap();
            RepairDartDrawerStateHookIfRemapped(
                    profile,
                    (remap_mask & kDartDrawerRemapped) != 0u);
            RepairDartOverviewStateHookIfRemapped(profile, remap_mask);
            RepairDartEditingStateHookIfRemapped(
                    profile,
                    (remap_mask & kDartEditingRemapped) != 0u);
            FinishDartStateHookRetirement(remap_mask);
            __atomic_store_n(&g_dart_remap_pending_mask,
                             DetectDartStateHookRemapMask(profile),
                             __ATOMIC_RELEASE);
        }
        __atomic_store_n(&g_dart_remap_repair_in_flight, uint32_t{0},
                         __ATOMIC_RELEASE);
    }
    TryInstallLoadedDartDrawerStateHook(profile);
}

int32_t HookMotionGetActionForSlot(void* event, uint32_t slot_index,
                                   bool masked) {
    if (slot_index >= kLauncherInputSlotCount) return -1;
    if (!EnsureDartStateOwnerForCurrentProcess()) return -1;
    // hyos_spawner can resolve app_entry_point before the final Launcher child
    // remaps the APK-backed libapp_launcher text.  The PLT hooks survive that
    // transition, while inherited inline-hook state can outlive the actual
    // text patch. Repair only the exact return-epilogue loss;
    // the triggering stream remains native because its outer handler may
    // already be on the stack.
    LauncherInputHookSlot& slot = g_launcher_input_slots[slot_index];
    void* target = masked ? slot.original_action_masked
                          : slot.original_action;
    MotionEventIntFn original = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&target));
    if (original == nullptr) return -1;
    const int32_t action = original(event);
    g_last_motion_event = reinterpret_cast<uintptr_t>(event);
    PublishContextualMotionSnapshot(event, action);
    if ((action & 0xff) == 0) {
        const uintptr_t down_identity = reinterpret_cast<uintptr_t>(event);
        if (g_last_dart_maintenance_down != down_identity) {
            g_last_dart_maintenance_down = down_identity;
            MaintainLauncherHooksOnActionDown(slot_index);
        }
        // Dart observers only store epoch-bound atomics and wake the sleeping
        // dispatcher. Do not publish from a MotionEvent getter: its native
        // caller can still be an active Flutter/Rust 1.ui frame.
        __atomic_fetch_add(&g_motion_down_capture_count, uint32_t{1},
                           __ATOMIC_RELAXED);
    } else {
        g_last_dart_maintenance_down = 0u;
    }
    CaptureMotionAction(event, action);
    return action;
}

int32_t HookMotionGetAction0(void* event) {
    return HookMotionGetActionForSlot(event, 0u, false);
}
int32_t HookMotionGetAction1(void* event) {
    return HookMotionGetActionForSlot(event, 1u, false);
}
int32_t HookMotionGetAction2(void* event) {
    return HookMotionGetActionForSlot(event, 2u, false);
}
int32_t HookMotionGetAction3(void* event) {
    return HookMotionGetActionForSlot(event, 3u, false);
}
int32_t HookMotionGetActionMasked0(void* event) {
    return HookMotionGetActionForSlot(event, 0u, true);
}
int32_t HookMotionGetActionMasked1(void* event) {
    return HookMotionGetActionForSlot(event, 1u, true);
}
int32_t HookMotionGetActionMasked2(void* event) {
    return HookMotionGetActionForSlot(event, 2u, true);
}
int32_t HookMotionGetActionMasked3(void* event) {
    return HookMotionGetActionForSlot(event, 3u, true);
}

// The captured native Fn owns a small closure whose tail releases the temporary
// callback object after the completion marker is published.  We replace only
// the Flutter terminal call, so this exact cleanup must still run or the native
// LongPressDetector eventually remains in its completed state.
bool CleanupContextualLongPressClosure(void* closure) {
    if (closure == nullptr) return false;
    void* storage = *reinterpret_cast<void**>(
            reinterpret_cast<uint8_t*>(closure) + 0x8u);
    void* owner = *reinterpret_cast<void**>(
            reinterpret_cast<uint8_t*>(closure) + 0x10u);
    // The native function legitimately takes the no-storage branch for some
    // trigger modes; in that shape there is no temporary object to release.
    if (storage == nullptr) return true;
    if (owner == nullptr) return false;
    const uintptr_t object_size = *reinterpret_cast<uintptr_t*>(
            reinterpret_cast<uint8_t*>(owner) + 0x10u);
    ContextualClosureCleanupFn cleanup =
            *reinterpret_cast<ContextualClosureCleanupFn*>(
                    reinterpret_cast<uint8_t*>(owner) + 0x28u);
    if (object_size == 0u || cleanup == nullptr) return false;
    const uintptr_t aligned_offset = (object_size - 1u) &
            ~static_cast<uintptr_t>(0xfu);
    cleanup(reinterpret_cast<uint8_t*>(storage) + aligned_offset + 0x10u);
    return true;
}

bool ContextualLongPressHasUpwardIntent() {
    if (__atomic_load_n(&g_contextual_motion_snapshot_valid,
                        __ATOMIC_ACQUIRE) == 0u) {
        return false;
    }
    const float down_y = BitsFloat(__atomic_load_n(
            &g_contextual_motion_snapshot_down_y_bits, __ATOMIC_ACQUIRE));
    const float current_y = BitsFloat(__atomic_load_n(
            &g_contextual_motion_snapshot_current_y_bits, __ATOMIC_ACQUIRE));
    const float upward = down_y - current_y;
    return upward >= 8.0f;
}

void HookContextualLongPressHandler(void* closure, uint32_t trigger_mode) {
    __atomic_fetch_add(&g_contextual_long_press_trigger_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    ContextualLongPressHandlerFn original =
            reinterpret_cast<ContextualLongPressHandlerFn>(
                    AtomicLoad(&g_original_contextual_long_press_handler));
    ContextualSearchInvokeFn invoke =
            reinterpret_cast<ContextualSearchInvokeFn>(
                    AtomicLoad(&g_contextual_search_invoke));
    const bool upward_intent = ContextualLongPressHasUpwardIntent();
    __android_log_print(
            ANDROID_LOG_INFO, kLogTag,
            "native contextual-search terminal handler trigger_mode=%u"
            " upward=%u enabled=%u snapshot_valid=%u down_y=%.1f current_y=%.1f",
            static_cast<unsigned int>(trigger_mode), upward_intent ? 1u : 0u,
            AtomicLoad(&g_contextual_search_enabled),
            __atomic_load_n(&g_contextual_motion_snapshot_valid, __ATOMIC_ACQUIRE),
            static_cast<double>(BitsFloat(__atomic_load_n(
                    &g_contextual_motion_snapshot_down_y_bits, __ATOMIC_ACQUIRE))),
            static_cast<double>(BitsFloat(__atomic_load_n(
                    &g_contextual_motion_snapshot_current_y_bits, __ATOMIC_ACQUIRE))));
    if (closure == nullptr) {
        __atomic_fetch_add(&g_contextual_long_press_passthrough_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
        if (original != nullptr) original(closure, trigger_mode);
        return;
    }

    // DefaultLongPressHandler's native Fn closure first marks the captured
    // detector state complete with a release byte store, then invokes its
    // optional Flutter trigger hook. Preserve that state transition while
    // replacing only the terminal Flutter routing that rejects NavLongPress
    // "none" on China builds. The detector, animation and cancellation owner
    // remain Xiaomi's LongPressDetector/LongPressManager.
    void* completion_state = AtomicLoad(
            reinterpret_cast<void**>(closure));
    if (completion_state == nullptr) {
        __atomic_fetch_add(&g_contextual_long_press_passthrough_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
        if (original != nullptr) original(closure, trigger_mode);
        return;
    }
    // Validate the same closure tail used by the captured native function
    // before either terminal routing or cancellation cleanup.  A layout
    // mismatch must remain stock rather than risking a stale detector or an
    // invalid release call.
    void* closure_storage = *reinterpret_cast<void**>(
            reinterpret_cast<uint8_t*>(closure) + 0x8u);
    void* closure_owner = *reinterpret_cast<void**>(
            reinterpret_cast<uint8_t*>(closure) + 0x10u);
    if ((closure_storage != nullptr && closure_owner == nullptr) ||
            (closure_storage != nullptr &&
                    (*reinterpret_cast<uintptr_t*>(
                            reinterpret_cast<uint8_t*>(closure_owner) + 0x10u) ==
                            0u ||
                     *reinterpret_cast<ContextualClosureCleanupFn*>(
                            reinterpret_cast<uint8_t*>(closure_owner) + 0x28u) ==
                            nullptr))) {
        __atomic_fetch_add(&g_contextual_long_press_passthrough_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
        if (original != nullptr) original(closure, trigger_mode);
        return;
    }
    // An upward desktop transition must cancel the detector even when the
    // module's contextual-search preference is currently disabled.  Do not
    // call the original terminal handler here: that function would continue
    // into Xiaomi's CTS route.  Reproduce only its completion marker and
    // closure cleanup, leaving the detector in the same finished state without
    // invoking the service.
    if (upward_intent) {
        __atomic_fetch_add(&g_contextual_long_press_upward_cancel_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
        __atomic_store_n(static_cast<uint8_t*>(completion_state) + 0x10u,
                         uint8_t{1}, __ATOMIC_RELEASE);
        if (!CleanupContextualLongPressClosure(closure)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "contextual-search upward cancellation cleanup unavailable");
        }
        return;
    }
    if (AtomicLoad(&g_contextual_search_enabled) == 0u || invoke == nullptr) {
        __atomic_fetch_add(&g_contextual_long_press_passthrough_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
        if (original != nullptr) original(closure, trigger_mode);
        return;
    }
    __atomic_store_n(static_cast<uint8_t*>(completion_state) + 0x10u,
                     uint8_t{1}, __ATOMIC_RELEASE);
    const uint8_t result = invoke(uint32_t{1});
    __atomic_fetch_add(&g_contextual_search_invoke_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    __atomic_store_n(&g_contextual_search_invoke_last_result,
                     static_cast<uint32_t>(result), __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "native contextual-search terminal invoked result=%u",
                        static_cast<unsigned int>(result));
    if (result != 0u && !SendNativeBroadcast(
            kContextualSearchTriggeredAction, nullptr)) {
        Log(ANDROID_LOG_WARN,
            "contextual-search trigger haptic signal could not reach SystemUI");
    }
    if (!CleanupContextualLongPressClosure(closure)) {
        // This should be unreachable after the validation above.  Keep the
        // native callback as the safety net if the object changed concurrently.
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "contextual-search closure cleanup unavailable");
        if (original != nullptr) original(closure, trigger_mode);
    }
}

bool InstallContextualSearchLongPressHook(
        uint8_t* base,
        const miui_home_profiles::LauncherProfile* profile) {
    if (base == nullptr || profile == nullptr) return false;
    const bool absent = profile->contextual_long_press_handler_offset == 0u &&
            profile->contextual_long_press_handler_prologue == nullptr &&
            profile->contextual_long_press_handler_prologue_size == 0u &&
            profile->contextual_search_invoke_offset == 0u &&
            profile->contextual_search_invoke_prologue == nullptr &&
            profile->contextual_search_invoke_prologue_size == 0u;
    if (absent) {
        __atomic_store_n(&g_contextual_long_press_hook_state, uint32_t{2},
                         __ATOMIC_RELEASE);
        return true;
    }
    if (profile->contextual_long_press_handler_offset == 0u ||
            profile->contextual_long_press_handler_prologue == nullptr ||
            profile->contextual_long_press_handler_prologue_size == 0u ||
            profile->contextual_search_invoke_offset == 0u ||
            profile->contextual_search_invoke_prologue == nullptr ||
            profile->contextual_search_invoke_prologue_size == 0u ||
            !MatchesCode(
                    base, profile->contextual_long_press_handler_offset,
                    profile->contextual_long_press_handler_prologue,
                    profile->contextual_long_press_handler_prologue_size) ||
            !MatchesCode(base, profile->contextual_search_invoke_offset,
                         profile->contextual_search_invoke_prologue,
                         profile->contextual_search_invoke_prologue_size)) {
        __atomic_store_n(&g_contextual_long_press_hook_state, uint32_t{5},
                         __ATOMIC_RELEASE);
        AtomicStore(&g_contextual_search_invoke, static_cast<void*>(nullptr));
        Log(ANDROID_LOG_ERROR,
            "contextual-search native route rejected profile fingerprints");
        return false;
    }
    __atomic_store_n(&g_contextual_long_press_hook_state, uint32_t{1},
                     __ATOMIC_RELEASE);
    AtomicStore(&g_contextual_search_invoke,
                static_cast<void*>(
                        base + profile->contextual_search_invoke_offset));
    if (InstallInlineHook(
                base + profile->contextual_long_press_handler_offset,
                reinterpret_cast<void*>(HookContextualLongPressHandler),
                &g_original_contextual_long_press_handler) != kHookSuccess ||
            AtomicLoad(&g_original_contextual_long_press_handler) == nullptr) {
        AtomicStore(&g_contextual_search_invoke, static_cast<void*>(nullptr));
        __atomic_store_n(&g_contextual_long_press_hook_state, uint32_t{6},
                         __ATOMIC_RELEASE);
        Log(ANDROID_LOG_ERROR,
            "contextual-search native long-press hook failed");
        return false;
    }
    __atomic_store_n(&g_contextual_long_press_hook_state, uint32_t{3},
                     __ATOMIC_RELEASE);
    Log(ANDROID_LOG_INFO,
        "installed exact native contextual-search long-press route");
    return true;
}


constexpr uint32_t kDartReturnX22Epilogue[] = {
        0xaa1603e0u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u};

bool MatchesDartEpilogue(
        uint8_t* dart_base, uintptr_t offset, const uint32_t* words) {
    return dart_base != nullptr && offset != 0u && words != nullptr &&
            DartMappedRangeHasFlags(
                    dart_base, offset, sizeof(uint32_t) * 4u, PF_R | PF_X) &&
            MatchesCode(dart_base, offset,
                        reinterpret_cast<const uint8_t*>(words),
                        sizeof(uint32_t) * 4u);
}

bool ResolveDartProfileBase(
        void* dart_handle,
        const miui_home_profiles::LauncherProfile* profile,
        uint8_t** dart_base_out) {
    if (dart_handle == nullptr || profile == nullptr ||
            dart_base_out == nullptr) {
        return false;
    }
    void* instructions = dlsym(dart_handle, kDartSnapshotInstructionsSymbol);
    void* build_id = dlsym(dart_handle, kDartSnapshotBuildIdSymbol);
    Dl_info info{};
    auto* dart_base =
            instructions != nullptr && build_id != nullptr &&
                    dladdr(instructions, &info) != 0 &&
                    info.dli_fbase != nullptr && IsDartLibraryPath(info.dli_fname)
            ? static_cast<uint8_t*>(info.dli_fbase)
            : nullptr;
    const bool valid = dart_base != nullptr &&
            instructions == dart_base +
                    profile->dart_snapshot_instructions_offset &&
            build_id == dart_base + profile->dart_snapshot_build_id_offset &&
            profile->dart_snapshot_build_id != nullptr &&
            profile->dart_snapshot_build_id_size != 0u &&
            DartMappedRangeHasFlags(
                    dart_base, profile->dart_snapshot_instructions_offset,
                    sizeof(uint32_t), PF_R | PF_X) &&
            DartMappedRangeHasFlags(
                    dart_base, profile->dart_snapshot_build_id_offset,
                    profile->dart_snapshot_build_id_size, PF_R) &&
            memcmp(build_id, profile->dart_snapshot_build_id,
                   profile->dart_snapshot_build_id_size) == 0;
    if (valid) *dart_base_out = dart_base;
    return valid;
}

bool InstallDartEpilogue(
        uint8_t* dart_base, uintptr_t offset, const uint32_t* words,
        void* replacement, void** original) {
    if (!MatchesDartEpilogue(dart_base, offset, words) ||
            replacement == nullptr || original == nullptr) {
        return false;
    }
    *original = nullptr;
    return InstallInlineHook(dart_base + offset, replacement, original) ==
                    kHookSuccess &&
            *original != nullptr;
}

void RemoveDartEpilogueRange(
        uint8_t* dart_base, const uintptr_t* offsets, size_t count) {
    if (dart_base == nullptr || offsets == nullptr) return;
    for (size_t index = 0u; index < count; ++index) {
        if (offsets[index] != 0u) {
            RemoveInlineHook(dart_base + offsets[index]);
        }
    }
}

bool TryInstallDartDrawerStateHook(
        void* dart_handle,
        const miui_home_profiles::LauncherProfile* profile) {
    if (dart_handle == nullptr || profile == nullptr) return false;
    if (AtomicLoad(&g_drawer_state_hook_state) == uint32_t{3}) {
        if (AtomicLoad(&g_overview_state_hook_state) == uint32_t{1}) {
            TryInstallDartOverviewStateHook(dart_handle, profile);
        }
        return true;
    }
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(
                &g_dart_drawer_install_in_flight, &expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return AtomicLoad(&g_drawer_state_hook_state) == uint32_t{3};
    }
    AtomicStore(&g_drawer_state_hook_state, uint32_t{1});

    uint8_t* dart_base = nullptr;
    const bool valid = ResolveDartProfileBase(
                               dart_handle, profile, &dart_base) &&
            profile->dart_drawer_progress_end_offset != 0u &&
            profile->dart_drawer_progress_end_prologue != nullptr &&
            profile->dart_drawer_progress_end_prologue_size != 0u &&
            profile->dart_drawer_transition_complete_offset != 0u &&
            profile->dart_drawer_transition_complete_prologue != nullptr &&
            profile->dart_drawer_transition_complete_prologue_size != 0u &&
            profile->dart_drawer_transition_epilogue_offset != 0u &&
            profile->dart_all_apps_state_slot_offset != 0u &&
            profile->dart_home_state_slot_offset != 0u &&
            MatchesCode(dart_base,
                        profile->dart_drawer_progress_end_offset,
                        profile->dart_drawer_progress_end_prologue,
                        profile->dart_drawer_progress_end_prologue_size) &&
            MatchesCode(
                    dart_base,
                    profile->dart_drawer_transition_complete_offset,
                    profile->dart_drawer_transition_complete_prologue,
                    profile->dart_drawer_transition_complete_prologue_size) &&
            MatchesDartEpilogue(
                    dart_base,
                    profile->dart_drawer_transition_epilogue_offset,
                    kDartReturnX22Epilogue);
    if (!valid) {
        AtomicStore(&g_drawer_state_hook_state, uint32_t{5});
        AtomicStore(&g_dart_drawer_install_in_flight, uint32_t{0});
        Log(ANDROID_LOG_ERROR,
            "Dart ALL_APPS route rejected snapshot graph or return epilogue");
        return false;
    }

    AtomicStore(&g_dart_drawer_retiring, uint32_t{1});
    if (!InstallDartEpilogue(
                dart_base,
                profile->dart_drawer_transition_epilogue_offset,
                kDartReturnX22Epilogue,
                reinterpret_cast<void*>(
                        MiuiHomeHyosDartDrawerTransitionEpilogueHook),
                &miui_home_hyos_dart_drawer_epilogue_original)) {
        AtomicStore(&miui_home_hyos_dart_drawer_epilogue_original,
                    static_cast<void*>(nullptr));
        AtomicStore(&g_drawer_state_hook_state, uint32_t{6});
        AtomicStore(&g_dart_drawer_install_in_flight, uint32_t{0});
        Log(ANDROID_LOG_ERROR, "Dart ALL_APPS return-epilogue hook failed");
        return false;
    }
    AtomicStore(&g_dart_app_handle, dart_handle);
    AtomicStore(&g_dart_app_base, dart_base);
    AtomicStore(&g_drawer_state_hook_state, uint32_t{3});
    AtomicStore(&g_dart_drawer_retiring, uint32_t{0});
    AtomicStore(&g_dart_drawer_install_in_flight, uint32_t{0});
    Log(ANDROID_LOG_INFO,
        "installed post-publication Dart ALL_APPS state bridge");
    if (AtomicLoad(&g_overview_state_hook_state) == uint32_t{1}) {
        TryInstallDartOverviewStateHook(dart_handle, profile);
    }
    return true;
}

bool TryInstallDartOverviewStateHook(
        void* dart_handle,
        const miui_home_profiles::LauncherProfile* profile) {
    if (dart_handle == nullptr || profile == nullptr) return false;
    if (AtomicLoad(&g_overview_state_hook_state) == uint32_t{3}) return true;
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(
                &g_dart_overview_install_in_flight, &expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return AtomicLoad(&g_overview_state_hook_state) == uint32_t{3};
    }
    AtomicStore(&g_overview_state_hook_state, uint32_t{1});

    uint8_t* dart_base = nullptr;
    const bool valid = ResolveDartProfileBase(
                               dart_handle, profile, &dart_base) &&
            profile->dart_overview_enter_offset != 0u &&
            profile->dart_overview_enter_prologue != nullptr &&
            profile->dart_overview_enter_prologue_size != 0u &&
            profile->dart_overview_exit_offset != 0u &&
            profile->dart_overview_exit_prologue != nullptr &&
            profile->dart_overview_exit_prologue_size != 0u &&
            profile->dart_overview_enter_epilogue_offset != 0u &&
            profile->dart_overview_exit_epilogue_offset != 0u &&
            MatchesCode(dart_base, profile->dart_overview_enter_offset,
                        profile->dart_overview_enter_prologue,
                        profile->dart_overview_enter_prologue_size) &&
            MatchesCode(dart_base, profile->dart_overview_exit_offset,
                        profile->dart_overview_exit_prologue,
                        profile->dart_overview_exit_prologue_size) &&
            MatchesDartEpilogue(
                    dart_base, profile->dart_overview_enter_epilogue_offset,
                    kDartReturnX22Epilogue) &&
            MatchesDartEpilogue(
                    dart_base, profile->dart_overview_exit_epilogue_offset,
                    kDartReturnX22Epilogue);
    if (!valid) {
        AtomicStore(&g_overview_state_hook_state, uint32_t{5});
        AtomicStore(&g_dart_overview_install_in_flight, uint32_t{0});
        Log(ANDROID_LOG_ERROR,
            "Dart Overview route rejected snapshot graph or return epilogue");
        return false;
    }

    AtomicStore(&g_dart_overview_enter_retiring, uint32_t{1});
    AtomicStore(&g_dart_overview_exit_retiring, uint32_t{1});
    if (!InstallDartEpilogue(
                dart_base, profile->dart_overview_enter_epilogue_offset,
                kDartReturnX22Epilogue,
                reinterpret_cast<void*>(
                        MiuiHomeHyosDartOverviewEnterEpilogueHook),
                &miui_home_hyos_dart_overview_enter_epilogue_original)) {
        AtomicStore(&g_overview_state_hook_state, uint32_t{6});
        AtomicStore(&g_dart_overview_install_in_flight, uint32_t{0});
        Log(ANDROID_LOG_ERROR, "Dart Overview enter epilogue hook failed");
        return false;
    }
    if (!InstallDartEpilogue(
                dart_base, profile->dart_overview_exit_epilogue_offset,
                kDartReturnX22Epilogue,
                reinterpret_cast<void*>(
                        MiuiHomeHyosDartOverviewExitEpilogueHook),
                &miui_home_hyos_dart_overview_exit_epilogue_original)) {
        RemoveInlineHook(
                dart_base + profile->dart_overview_enter_epilogue_offset);
        AtomicStore(&miui_home_hyos_dart_overview_enter_epilogue_original,
                    static_cast<void*>(nullptr));
        AtomicStore(&miui_home_hyos_dart_overview_exit_epilogue_original,
                    static_cast<void*>(nullptr));
        AtomicStore(&g_overview_state_hook_state, uint32_t{6});
        AtomicStore(&g_dart_overview_install_in_flight, uint32_t{0});
        Log(ANDROID_LOG_ERROR, "Dart Overview exit epilogue hook failed");
        return false;
    }
    AtomicStore(&g_dart_app_handle, dart_handle);
    AtomicStore(&g_dart_app_base, dart_base);
    AtomicStore(&g_overview_state_hook_state, uint32_t{3});
    AtomicStore(&g_dart_overview_enter_retiring, uint32_t{0});
    AtomicStore(&g_dart_overview_exit_retiring, uint32_t{0});
    AtomicStore(&g_dart_overview_install_in_flight, uint32_t{0});
    Log(ANDROID_LOG_INFO,
        "installed post-publication Dart Overview state bridge");
    return true;
}

bool TryInstallDartEditingStateHook(
        void* dart_handle,
        const miui_home_profiles::LauncherProfile* profile) {
    if (dart_handle == nullptr || profile == nullptr) return false;
    if (AtomicLoad(&g_editing_state_hook_state) == uint32_t{3}) return true;
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(
                &g_dart_editing_install_in_flight, &expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return AtomicLoad(&g_editing_state_hook_state) == uint32_t{3};
    }
    AtomicStore(&g_editing_state_hook_state, uint32_t{1});
    uint8_t* dart_base = nullptr;
    const uintptr_t sites[] = {
            profile->dart_home_surface_inactive_epilogue_offset,
            profile->dart_home_surface_published_epilogue_offset};
    bool valid = ResolveDartProfileBase(dart_handle, profile, &dart_base) &&
            profile->dart_home_surface_notify_offset != 0u &&
            profile->dart_home_surface_notify_code != nullptr &&
            profile->dart_home_surface_notify_code_size == 113u * 4u &&
            sites[0] == profile->dart_home_surface_notify_offset + 53u * 4u &&
            sites[1] == profile->dart_home_surface_notify_offset + 109u * 4u &&
            MatchesCode(dart_base, profile->dart_home_surface_notify_offset,
                        profile->dart_home_surface_notify_code,
                        profile->dart_home_surface_notify_code_size) &&
            profile->dart_editing_query_return_offset_a >= 4u &&
            profile->dart_editing_query_return_offset_b >= 4u &&
            MatchesCode(dart_base, profile->dart_editing_query_offset,
                        profile->dart_editing_query_prologue,
                        profile->dart_editing_query_prologue_size) &&
            DartBlTargets(dart_base,
                    profile->dart_editing_query_return_offset_a - 4u,
                    profile->dart_editing_query_offset) &&
            DartBlTargets(dart_base,
                    profile->dart_editing_query_return_offset_b - 4u,
                    profile->dart_editing_query_offset);
    for (uintptr_t site : sites) {
        valid = valid && MatchesDartEpilogue(dart_base, site, kDartReturnX22Epilogue);
    }
    if (!valid) {
        AtomicStore(&g_editing_state_hook_state, uint32_t{5});
        AtomicStore(&g_dart_editing_install_in_flight, uint32_t{0});
        Log(ANDROID_LOG_ERROR,
            "Dart Home-surface route rejected complete notify frame");
        return false;
    }
    void* hooks[] = {
            reinterpret_cast<void*>(MiuiHomeHyosDartHomeSurfaceInactiveEpilogueHook),
            reinterpret_cast<void*>(MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook)};
    AtomicStore(&g_dart_editing_retiring, uint32_t{1});
    size_t installed = 0u;
    for (size_t index = 0u; index < 2u; ++index) {
        if (!InstallDartEpilogue(dart_base, sites[index], kDartReturnX22Epilogue,
                    hooks[index], &miui_home_hyos_dart_editing_epilogue_original[index])) {
            break;
        }
        ++installed;
    }
    if (installed != 2u) {
        RemoveDartEpilogueRange(dart_base, sites, 2u);
        memset(miui_home_hyos_dart_editing_epilogue_original, 0,
               sizeof(miui_home_hyos_dart_editing_epilogue_original));
        AtomicStore(&g_editing_state_hook_state, uint32_t{6});
        AtomicStore(&g_dart_editing_install_in_flight, uint32_t{0});
        Log(ANDROID_LOG_ERROR, "Dart Home-surface epilogue installation failed");
        return false;
    }
    AtomicStore(&g_dart_app_handle, dart_handle);
    AtomicStore(&g_dart_app_base, dart_base);
    AtomicStore(&g_editing_state_hook_state, uint32_t{3});
    AtomicStore(&g_dart_editing_retiring, uint32_t{0});
    AtomicStore(&g_dart_editing_install_in_flight, uint32_t{0});
    Log(ANDROID_LOG_INFO, "installed complete Dart Home-surface state epilogues");
    return true;
}

uint32_t DetectDartStateHookRemapMask(
        const miui_home_profiles::LauncherProfile* profile) {
    auto* dart_base = static_cast<uint8_t*>(AtomicLoad(&g_dart_app_base));
    if (profile == nullptr || dart_base == nullptr ||
            profile != CurrentDartFeatureProfile()) {
        return 0u;
    }
    const bool drawer =
            AtomicLoad(&g_drawer_state_hook_state) == uint32_t{3} &&
            MatchesDartEpilogue(
                    dart_base,
                    profile->dart_drawer_transition_epilogue_offset,
                    kDartReturnX22Epilogue);
    const bool overview_installed =
            AtomicLoad(&g_overview_state_hook_state) == uint32_t{3};
    const bool enter_original = overview_installed &&
            MatchesDartEpilogue(
                    dart_base, profile->dart_overview_enter_epilogue_offset,
                    kDartReturnX22Epilogue);
    const bool exit_original = overview_installed &&
            MatchesDartEpilogue(
                    dart_base, profile->dart_overview_exit_epilogue_offset,
                    kDartReturnX22Epilogue);
    const bool editing = AtomicLoad(&g_editing_state_hook_state) == uint32_t{3} &&
            (MatchesDartEpilogue(dart_base,
                    profile->dart_home_surface_inactive_epilogue_offset,
                    kDartReturnX22Epilogue) ||
             MatchesDartEpilogue(dart_base,
                    profile->dart_home_surface_published_epilogue_offset,
                    kDartReturnX22Epilogue));
    return (drawer ? kDartDrawerRemapped : 0u) |
            (enter_original ? kDartOverviewEnterRemapped : 0u) |
            (exit_original ? kDartOverviewExitRemapped : 0u) |
            (editing ? kDartEditingRemapped : 0u);
}

bool PrepareDartStateHookRetirement(uint32_t remap_mask) {
    if ((remap_mask & kDartDrawerRemapped) != 0u) {
        AtomicStore(&g_dart_drawer_retiring, uint32_t{1});
    }
    const bool overview_remapped =
            (remap_mask & (kDartOverviewEnterRemapped |
                           kDartOverviewExitRemapped)) != 0u;
    if (overview_remapped) {
        AtomicStore(&g_dart_overview_enter_retiring, uint32_t{1});
        AtomicStore(&g_dart_overview_exit_retiring, uint32_t{1});
    }
    if ((remap_mask & kDartEditingRemapped) != 0u) {
        AtomicStore(&g_dart_editing_retiring, uint32_t{1});
    }
    auto is_quiet = [remap_mask, overview_remapped]() {
        return ((remap_mask & kDartDrawerRemapped) == 0u ||
                AtomicLoad(&g_dart_drawer_active_count) == 0u) &&
                (!overview_remapped ||
                 AtomicLoad(&g_dart_overview_enter_active_count) == 0u) &&
                (!overview_remapped ||
                 AtomicLoad(&g_dart_overview_exit_active_count) == 0u) &&
                ((remap_mask & kDartEditingRemapped) == 0u ||
                 AtomicLoad(&g_dart_editing_active_count) == 0u);
    };
    for (uint32_t attempt = 0u; attempt < 8u; ++attempt) {
        if (is_quiet()) {
            timespec stable{0, 1000 * 1000};
            nanosleep(&stable, nullptr);
            if (is_quiet()) return true;
        } else {
            timespec wait{0, 1000 * 1000};
            nanosleep(&wait, nullptr);
        }
    }
    Log(ANDROID_LOG_WARN,
        "deferred Dart epilogue repair while an observer is active");
    return false;
}

void FinishDartStateHookRetirement(uint32_t remap_mask) {
    if ((remap_mask & kDartDrawerRemapped) != 0u &&
            AtomicLoad(&g_drawer_state_hook_state) == uint32_t{3}) {
        AtomicStore(&g_dart_drawer_retiring, uint32_t{0});
    }
    const bool overview_remapped =
            (remap_mask & (kDartOverviewEnterRemapped |
                           kDartOverviewExitRemapped)) != 0u;
    if (overview_remapped &&
            AtomicLoad(&g_overview_state_hook_state) == uint32_t{3}) {
        AtomicStore(&g_dart_overview_enter_retiring, uint32_t{0});
        AtomicStore(&g_dart_overview_exit_retiring, uint32_t{0});
    }
    if ((remap_mask & kDartEditingRemapped) != 0u &&
            AtomicLoad(&g_editing_state_hook_state) == uint32_t{3}) {
        AtomicStore(&g_dart_editing_retiring, uint32_t{0});
    }
}

void ClearRetiredDartObservation(
        volatile uint64_t* observation, uint64_t owner_epoch) {
    uint64_t current = AtomicLoad(observation);
    while (DartStateObservationEpoch(current) < owner_epoch) {
        if (__atomic_compare_exchange_n(
                    observation, &current, uint64_t{0}, false,
                    __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            return;
        }
    }
}

void InvalidateDartStateOwnerForRemap() {
    const uint64_t owner_epoch = __atomic_add_fetch(
            &g_dart_state_owner_epoch, uint64_t{1}, __ATOMIC_ACQ_REL);
    ClearRetiredDartObservation(&g_drawer_state_observation, owner_epoch);
    ClearRetiredDartObservation(&g_overview_state_observation, owner_epoch);
    ClearRetiredDartObservation(&g_editing_state_observation, owner_epoch);
    __atomic_fetch_or(&g_dart_state_publish_pending, kDartOwnerPending,
                      __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "invalidated retired Dart state owner epoch=%llu",
                        static_cast<unsigned long long>(owner_epoch));
    WakeDartStatePublisher();
}

bool RetireDartEpilogue(uint8_t* dart_base, uintptr_t offset) {
    return dart_base != nullptr && offset != 0u &&
            RemoveInlineHook(dart_base + offset) == kHookSuccess;
}

void RepairDartDrawerStateHookIfRemapped(
        const miui_home_profiles::LauncherProfile* profile,
        bool remap_detected) {
    if (!remap_detected || profile == nullptr ||
            AtomicLoad(&g_drawer_state_hook_state) != uint32_t{3}) {
        return;
    }
    auto* dart_base = static_cast<uint8_t*>(AtomicLoad(&g_dart_app_base));
    void* dart_handle = AtomicLoad(&g_dart_app_handle);
    __atomic_fetch_add(&g_dart_drawer_repair_attempt_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    AtomicStore(&g_dart_drawer_repair_stage, uint32_t{2});
    uint32_t expected = 3u;
    if (dart_base == nullptr || dart_handle == nullptr ||
            !__atomic_compare_exchange_n(
                    &g_drawer_state_hook_state, &expected, uint32_t{0},
                    false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE) ||
            !RetireDartEpilogue(
                    dart_base,
                    profile->dart_drawer_transition_epilogue_offset)) {
        AtomicStore(&g_drawer_state_hook_state, uint32_t{6});
        AtomicStore(&g_dart_drawer_repair_stage, uint32_t{5});
        __atomic_fetch_add(&g_dart_drawer_repair_failure_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        return;
    }
    AtomicStore(&miui_home_hyos_dart_drawer_epilogue_original,
                static_cast<void*>(nullptr));
    const bool installed =
            TryInstallDartDrawerStateHook(dart_handle, profile);
    AtomicStore(&g_dart_drawer_repair_stage,
                installed ? uint32_t{3} : uint32_t{6});
    __atomic_fetch_add(
            installed ? &g_dart_drawer_repair_success_count
                      : &g_dart_drawer_repair_failure_count,
            uint32_t{1}, __ATOMIC_RELAXED);
    Log(installed ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        installed ? "repaired remapped Dart ALL_APPS epilogue"
                  : "failed to repair remapped Dart ALL_APPS epilogue");
}

void RepairDartEditingStateHookIfRemapped(
        const miui_home_profiles::LauncherProfile* profile,
        bool remap_detected) {
    if (!remap_detected || profile == nullptr ||
            AtomicLoad(&g_editing_state_hook_state) != uint32_t{3}) {
        return;
    }
    auto* dart_base = static_cast<uint8_t*>(AtomicLoad(&g_dart_app_base));
    void* dart_handle = AtomicLoad(&g_dart_app_handle);
    __atomic_fetch_add(&g_editing_dart_repair_attempt_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    AtomicStore(&g_editing_dart_repair_stage, uint32_t{2});
    uint32_t expected = 3u;
    bool retired = dart_base != nullptr && dart_handle != nullptr &&
            __atomic_compare_exchange_n(
                    &g_editing_state_hook_state, &expected, uint32_t{0},
                    false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
    const uintptr_t sites[] = {
            profile->dart_home_surface_inactive_epilogue_offset,
            profile->dart_home_surface_published_epilogue_offset};
    for (uintptr_t site : sites) {
        retired = retired && RetireDartEpilogue(dart_base, site);
    }
    if (!retired) {
        AtomicStore(&g_editing_state_hook_state, uint32_t{6});
        AtomicStore(&g_editing_dart_repair_stage, uint32_t{5});
        __atomic_fetch_add(&g_editing_dart_repair_failure_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        return;
    }
    memset(miui_home_hyos_dart_editing_epilogue_original, 0,
           sizeof(miui_home_hyos_dart_editing_epilogue_original));
    const bool installed =
            TryInstallDartEditingStateHook(dart_handle, profile);
    AtomicStore(&g_editing_dart_repair_stage,
                installed ? uint32_t{3} : uint32_t{6});
    __atomic_fetch_add(
            installed ? &g_editing_dart_repair_success_count
                      : &g_editing_dart_repair_failure_count,
            uint32_t{1}, __ATOMIC_RELAXED);
    Log(installed ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        installed ? "repaired remapped Dart Home-surface epilogues"
                  : "failed to repair remapped Dart Home-surface epilogues");
}

void RepairDartOverviewStateHookIfRemapped(
        const miui_home_profiles::LauncherProfile* profile,
        uint32_t remap_mask) {
    const uint32_t overview_mask = remap_mask &
            (kDartOverviewEnterRemapped | kDartOverviewExitRemapped);
    if (overview_mask == 0u || profile == nullptr ||
            AtomicLoad(&g_overview_state_hook_state) != uint32_t{3}) {
        return;
    }
    auto* dart_base = static_cast<uint8_t*>(AtomicLoad(&g_dart_app_base));
    void* dart_handle = AtomicLoad(&g_dart_app_handle);
    __atomic_fetch_add(&g_overview_dart_repair_attempt_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    AtomicStore(&g_overview_dart_repair_stage, uint32_t{2});
    uint32_t expected = 3u;
    const bool retired = dart_base != nullptr && dart_handle != nullptr &&
            __atomic_compare_exchange_n(
                    &g_overview_state_hook_state, &expected, uint32_t{0},
                    false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE) &&
            RetireDartEpilogue(
                    dart_base,
                    profile->dart_overview_enter_epilogue_offset) &&
            RetireDartEpilogue(
                    dart_base,
                    profile->dart_overview_exit_epilogue_offset);
    if (!retired) {
        AtomicStore(&g_overview_state_hook_state, uint32_t{6});
        AtomicStore(&g_overview_dart_repair_stage, uint32_t{5});
        __atomic_fetch_add(&g_overview_dart_repair_failure_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        return;
    }
    AtomicStore(&miui_home_hyos_dart_overview_enter_epilogue_original,
                static_cast<void*>(nullptr));
    AtomicStore(&miui_home_hyos_dart_overview_exit_epilogue_original,
                static_cast<void*>(nullptr));
    const bool installed =
            TryInstallDartOverviewStateHook(dart_handle, profile);
    AtomicStore(&g_overview_dart_repair_stage,
                installed ? uint32_t{3} : uint32_t{6});
    __atomic_fetch_add(
            installed ? &g_overview_dart_repair_success_count
                      : &g_overview_dart_repair_failure_count,
            uint32_t{1}, __ATOMIC_RELAXED);
    __android_log_print(installed ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                        kLogTag, "%s Dart Overview epilogues mask=0x%x",
                        installed ? "repaired remapped"
                                  : "failed to repair remapped",
                        overview_mask);
}

void TryInstallLoadedDartDrawerStateHook(
        const miui_home_profiles::LauncherProfile* profile) {
    if (profile == nullptr) return;
    const bool drawer_pending =
            profile->dart_drawer_progress_end_offset != 0u &&
            AtomicLoad(&g_drawer_state_hook_state) == uint32_t{1};
    const bool overview_pending =
            profile->dart_overview_enter_offset != 0u &&
            AtomicLoad(&g_overview_state_hook_state) == uint32_t{1};
    const bool editing_pending =
            profile->dart_editing_query_offset != 0u &&
            AtomicLoad(&g_editing_state_hook_state) == uint32_t{1};
    if (!drawer_pending && !overview_pending && !editing_pending) return;
    // Flutter's engine can load the AOT image below libapp_launcher's own
    // dlopen boundary. RTLD_NOLOAD only acquires the already-loaded image; it
    // must never manufacture a second AOT owner during input readiness.
    void* dart_handle = dlopen(kDartLibraryName, RTLD_NOW | RTLD_NOLOAD);
    if (dart_handle == nullptr) return;
    bool retained = false;
    if (drawer_pending) {
        retained = TryInstallDartDrawerStateHook(dart_handle, profile);
    }
    if (AtomicLoad(&g_overview_state_hook_state) == uint32_t{1}) {
        retained = TryInstallDartOverviewStateHook(dart_handle, profile) ||
                retained;
    }
    if (AtomicLoad(&g_editing_state_hook_state) == uint32_t{1}) {
        retained = TryInstallDartEditingStateHook(dart_handle, profile) ||
                retained;
    }
    if (!retained) {
        dlclose(dart_handle);
    }
}

void TryResolveAndInstallLoadedDartProfile(bool retry_rejected) {
    // Acquire only an existing AOT mapping. Resolution is performed after the
    // loader has published both Dart snapshot symbols and never loads a second
    // copy merely to search it.
    void* dart_handle = dlopen(kDartLibraryName, RTLD_NOW | RTLD_NOLOAD);
    if (dart_handle == nullptr) return;
    const auto* profile = ResolveDartFeatureProfile(dart_handle,
                                                    retry_rejected);
    bool retained = false;
    if (profile != nullptr) {
        retained = TryInstallDartDrawerStateHook(dart_handle, profile);
        retained = TryInstallDartOverviewStateHook(dart_handle, profile) ||
                retained;
        retained = TryInstallDartEditingStateHook(dart_handle, profile) ||
                retained;
    }
    if (profile == nullptr) {
        const auto* launcher = CurrentLauncherProfile();
        const uint32_t dart_stage = AtomicLoad(&g_dart_profile_resolve_state);
        if (launcher != nullptr && g_launcher_base != nullptr &&
                StringsEqual(launcher->id, "runtime-side-v1") &&
                dart_stage >= static_cast<uint32_t>(
                        miui_home_dart_profile::ResolveStage::kRejectedElf)) {
            if (AtomicLoad(&g_overview_state_hook_state) == uint32_t{1}) {
                __atomic_store_n(&g_overview_state_hook_state,
                                 uint32_t{2}, __ATOMIC_RELEASE);
            }
            if (AtomicLoad(&g_editing_state_hook_state) == uint32_t{1}) {
                __atomic_store_n(&g_editing_state_hook_state,
                                 uint32_t{2}, __ATOMIC_RELEASE);
            }
        }
    }
    if (!retained) dlclose(dart_handle);
}

bool InstallDrawerStateHook(
        uint8_t* base,
        const miui_home_profiles::LauncherProfile* profile) {
    if (base == nullptr || profile == nullptr) return false;
    const bool has_dart_route =
            profile->dart_snapshot_instructions_offset != 0u ||
            profile->dart_snapshot_build_id_offset != 0u ||
            profile->dart_snapshot_build_id != nullptr ||
            profile->dart_snapshot_build_id_size != 0u ||
            profile->dart_drawer_progress_end_offset != 0u ||
            profile->dart_drawer_progress_end_prologue != nullptr ||
            profile->dart_drawer_progress_end_prologue_size != 0u ||
            profile->dart_drawer_transition_complete_offset != 0u ||
            profile->dart_drawer_transition_complete_prologue != nullptr ||
            profile->dart_drawer_transition_complete_prologue_size != 0u ||
            profile->dart_all_apps_state_slot_offset != 0u ||
            profile->dart_home_state_slot_offset != 0u;
    if (has_dart_route) {
        const bool complete =
                profile->dart_snapshot_instructions_offset != 0u &&
                profile->dart_snapshot_build_id_offset != 0u &&
                profile->dart_snapshot_build_id != nullptr &&
                profile->dart_snapshot_build_id_size != 0u &&
                profile->dart_drawer_progress_end_offset != 0u &&
                profile->dart_drawer_progress_end_prologue != nullptr &&
                profile->dart_drawer_progress_end_prologue_size != 0u &&
                profile->dart_drawer_transition_complete_offset != 0u &&
                profile->dart_drawer_transition_complete_prologue != nullptr &&
                profile->dart_drawer_transition_complete_prologue_size != 0u &&
                profile->dart_all_apps_state_slot_offset != 0u &&
                profile->dart_home_state_slot_offset != 0u;
        if (!complete) {
            __atomic_store_n(&g_drawer_state_hook_state, uint32_t{5},
                             __ATOMIC_RELEASE);
            Log(ANDROID_LOG_ERROR,
                "Dart ALL_APPS route rejected incomplete mixed profile");
            return false;
        }
        if (AtomicLoad(&g_dart_loader_hook_state) != uint32_t{3}) {
            __atomic_store_n(&g_drawer_state_hook_state, uint32_t{6},
                             __ATOMIC_RELEASE);
            Log(ANDROID_LOG_ERROR,
                "Dart ALL_APPS route missing launcher-local loader hook");
            return false;
        }
        // Launcher text and Flutter AOT can receive their final APK-backed
        // remap together. Preserve an already-installed AOT lifecycle here;
        // HookMotionGetActionForSlot performs the exact remap check immediately
        // after the Launcher business repair and adopts the fallback boundary.
        if (AtomicLoad(&g_drawer_state_hook_state) == uint32_t{3}) {
            return true;
        }
        __atomic_store_n(&g_drawer_state_hook_state, uint32_t{1},
                         __ATOMIC_RELEASE);
        void* dart_handle = AtomicLoad(&g_dart_app_handle);
        return dart_handle == nullptr ||
                TryInstallDartDrawerStateHook(dart_handle, profile);
    }
    const bool absent = profile->drawer_state_handler_offset == 0u &&
            profile->drawer_state_handler_prologue == nullptr &&
            profile->drawer_state_handler_prologue_size == 0u;
    if (absent) {
        __atomic_store_n(&g_drawer_state_hook_state, uint32_t{2},
                         __ATOMIC_RELEASE);
        return true;
    }
    if (profile->drawer_state_handler_offset == 0u ||
            profile->drawer_state_handler_prologue == nullptr ||
            profile->drawer_state_handler_prologue_size == 0u ||
            !MatchesCode(base, profile->drawer_state_handler_offset,
                         profile->drawer_state_handler_prologue,
                         profile->drawer_state_handler_prologue_size)) {
        __atomic_store_n(&g_drawer_state_hook_state, uint32_t{5},
                         __ATOMIC_RELEASE);
        Log(ANDROID_LOG_ERROR,
            "native drawer-state route rejected profile fingerprint");
        return false;
    }
    __atomic_store_n(&g_drawer_state_hook_state, uint32_t{1},
                     __ATOMIC_RELEASE);
    if (InstallInlineHook(
                base + profile->drawer_state_handler_offset,
                reinterpret_cast<void*>(HookDrawerStateHandler),
                &g_original_drawer_state_handler) != kHookSuccess ||
            AtomicLoad(&g_original_drawer_state_handler) == nullptr) {
        __atomic_store_n(&g_drawer_state_hook_state, uint32_t{6},
                         __ATOMIC_RELEASE);
        Log(ANDROID_LOG_ERROR, "native drawer-state hook failed");
        return false;
    }
    __atomic_store_n(&g_drawer_state_hook_state, uint32_t{3},
                     __ATOMIC_RELEASE);
    Log(ANDROID_LOG_INFO, "installed exact native ALL_APPS state bridge");
    return true;
}

bool InstallOverviewStateHook(
        uint8_t* base,
        const miui_home_profiles::LauncherProfile* profile) {
    if (base == nullptr || profile == nullptr) return false;
    const bool has_dart_route =
            profile->dart_overview_enter_offset != 0u ||
            profile->dart_overview_enter_prologue != nullptr ||
            profile->dart_overview_enter_prologue_size != 0u ||
            profile->dart_overview_exit_offset != 0u ||
            profile->dart_overview_exit_prologue != nullptr ||
            profile->dart_overview_exit_prologue_size != 0u;
    if (has_dart_route) {
        const bool complete =
                profile->dart_snapshot_instructions_offset != 0u &&
                profile->dart_snapshot_build_id_offset != 0u &&
                profile->dart_snapshot_build_id != nullptr &&
                profile->dart_snapshot_build_id_size != 0u &&
                profile->dart_overview_enter_offset != 0u &&
                profile->dart_overview_enter_prologue != nullptr &&
                profile->dart_overview_enter_prologue_size != 0u &&
                profile->dart_overview_exit_offset != 0u &&
                profile->dart_overview_exit_prologue != nullptr &&
                profile->dart_overview_exit_prologue_size != 0u;
        if (!complete || AtomicLoad(&g_dart_loader_hook_state) != uint32_t{3}) {
            __atomic_store_n(&g_overview_state_hook_state, uint32_t{5},
                             __ATOMIC_RELEASE);
            Log(ANDROID_LOG_ERROR,
                "Dart Overview route rejected incomplete mixed profile");
            return false;
        }
        if (AtomicLoad(&g_overview_state_hook_state) == uint32_t{3}) {
            return true;
        }
        __atomic_store_n(&g_overview_state_hook_state, uint32_t{1},
                         __ATOMIC_RELEASE);
        void* dart_handle = AtomicLoad(&g_dart_app_handle);
        return dart_handle == nullptr ||
                TryInstallDartOverviewStateHook(dart_handle, profile);
    }
    if (NeedsDartFeatureResolution(profile) &&
            AtomicLoad(&g_dart_profile_resolve_state) <
                    static_cast<uint32_t>(
                            miui_home_dart_profile::ResolveStage::kComplete)) {
        __atomic_store_n(&g_overview_state_hook_state, uint32_t{1},
                         __ATOMIC_RELEASE);
        return true;
    }
    __atomic_store_n(&g_overview_state_hook_state, uint32_t{2},
                     __ATOMIC_RELEASE);
    return true;
}

bool InstallLauncherInputHooksForProfile(void* app_entry_point) {
    uint8_t* base = nullptr;
    const auto* profile = ResolveLauncherProfile(app_entry_point, &base);
    if (profile == nullptr || base == nullptr) return false;
    for (uint32_t index = 0u; index < kLauncherInputSlotCount; ++index) {
        LauncherInputHookSlot& existing = g_launcher_input_slots[index];
        if (AtomicLoad(&existing.base) ==
                reinterpret_cast<uintptr_t>(base)) {
            return AtomicLoad(&existing.state) == uint32_t{3};
        }
    }
    const uint32_t index = __atomic_fetch_add(
            &g_launcher_input_slot_count, uint32_t{1}, __ATOMIC_ACQ_REL);
    if (index >= kLauncherInputSlotCount) return false;

    using MotionHookFn = int32_t (*)(void*);
    constexpr MotionHookFn kActionHooks[kLauncherInputSlotCount] = {
            HookMotionGetAction0, HookMotionGetAction1,
            HookMotionGetAction2, HookMotionGetAction3,
    };
    constexpr MotionHookFn kActionMaskedHooks[kLauncherInputSlotCount] = {
            HookMotionGetActionMasked0, HookMotionGetActionMasked1,
            HookMotionGetActionMasked2, HookMotionGetActionMasked3,
    };
    using FeatureHookFn = NativeResult (*)(void*, const char*, size_t, uint32_t);
    constexpr FeatureHookFn kContextualFeatureHooks[kLauncherInputSlotCount] = {
            HookPackageManagerHasSystemFeature0,
            HookPackageManagerHasSystemFeature1,
            HookPackageManagerHasSystemFeature2,
            HookPackageManagerHasSystemFeature3,
    };
    constexpr BundleGetBoolFn kXiaoAiBooleanHooks[kLauncherInputSlotCount] = {
            HookBundleGetBoolean0, HookBundleGetBoolean1,
            HookBundleGetBoolean2, HookBundleGetBoolean3,
    };
    constexpr DlopenFn kLauncherDlopenHooks[kLauncherInputSlotCount] = {
            HookLauncherDlopen0, HookLauncherDlopen1,
            HookLauncherDlopen2, HookLauncherDlopen3,
    };
    LauncherInputHookSlot& slot = g_launcher_input_slots[index];
    AtomicStore(&slot.base, reinterpret_cast<uintptr_t>(base));
    AtomicStore(&slot.profile, profile);
    AtomicStore(&slot.state, uint32_t{1});
    if (InstallPltHook(base, "input_MotionEvent_getAction",
                      reinterpret_cast<void*>(kActionHooks[index]),
                      &slot.original_action) != kHookSuccess ||
            slot.original_action == nullptr ||
            InstallPltHook(base, "input_MotionEvent_getActionMasked",
                          reinterpret_cast<void*>(kActionMaskedHooks[index]),
                          &slot.original_action_masked) != kHookSuccess ||
            slot.original_action_masked == nullptr ||
            InstallPltHook(base, "input_InputMonitor_pilferPointers",
                          reinterpret_cast<void*>(
                                  MiuiHomeHyosInputMonitorPilferHook),
                          &slot.original_pilfer) != kHookSuccess ||
            slot.original_pilfer == nullptr) {
        AtomicStore(&slot.state, uint32_t{6});
        return false;
    }
    if (AtomicLoad(&g_original_motion_get_action) == nullptr) {
        AtomicStore(&g_original_motion_get_action, slot.original_action);
    }
    if (AtomicLoad(&g_original_motion_get_action_masked) == nullptr) {
        AtomicStore(&g_original_motion_get_action_masked,
                    slot.original_action_masked);
    }
    if (AtomicLoad(&g_original_input_monitor_pilfer) == nullptr) {
        AtomicStore(&g_original_input_monitor_pilfer,
                    slot.original_pilfer);
    }
    if (NeedsDartFeatureResolution(profile) ||
            profile->dart_drawer_progress_end_offset != 0u) {
        __atomic_store_n(&g_dart_loader_hook_state, uint32_t{1},
                         __ATOMIC_RELEASE);
        if (InstallPltHook(base, "dlopen",
                           reinterpret_cast<void*>(
                                   kLauncherDlopenHooks[index]),
                           &slot.original_dlopen) != kHookSuccess ||
                slot.original_dlopen == nullptr) {
            __atomic_store_n(&g_dart_loader_hook_state, uint32_t{6},
                             __ATOMIC_RELEASE);
            __atomic_store_n(&g_drawer_state_hook_state, uint32_t{6},
                             __ATOMIC_RELEASE);
            Log(ANDROID_LOG_ERROR,
                "native launcher Dart-library observation hook failed");
        } else {
            __atomic_store_n(&g_dart_loader_hook_state, uint32_t{3},
                             __ATOMIC_RELEASE);
            TryResolveAndInstallLoadedDartProfile();
        }
    }
    AtomicStore(&slot.contextual_search_state, uint32_t{1});
    if (InstallPltHook(base, "PackageManager_has_system_feature",
                      reinterpret_cast<void*>(kContextualFeatureHooks[index]),
                      &slot.original_has_system_feature) == kHookSuccess &&
            slot.original_has_system_feature != nullptr) {
        AtomicStore(&slot.contextual_search_state, uint32_t{3});
        __atomic_store_n(&g_contextual_feature_hook_state, uint32_t{3},
                         __ATOMIC_RELEASE);
        Log(ANDROID_LOG_INFO,
            "installed native launcher contextual-search feature hook");
    } else {
        AtomicStore(&slot.contextual_search_state, uint32_t{6});
        if (AtomicLoad(&g_contextual_feature_hook_state) != uint32_t{3}) {
            __atomic_store_n(&g_contextual_feature_hook_state, uint32_t{6},
                             __ATOMIC_RELEASE);
        }
        Log(ANDROID_LOG_WARN,
            "native launcher contextual-search feature hook unavailable");
    }
    if (profile->xiaoai_bundle_bool_return_offset != 0u) {
        AtomicStore(&slot.xiaoai_state, uint32_t{1});
        if (InstallPltHook(base, "Bundle_get_boolean",
                          reinterpret_cast<void*>(
                                  kXiaoAiBooleanHooks[index]),
                          &slot.original_bundle_get_boolean) == kHookSuccess &&
                slot.original_bundle_get_boolean != nullptr) {
            AtomicStore(&slot.xiaoai_state, uint32_t{3});
            __atomic_store_n(&g_xiaoai_state_hook_state, uint32_t{3},
                             __ATOMIC_RELEASE);
            Log(ANDROID_LOG_INFO,
                "installed dynamically resolved native XiaoAi state observer");
        } else {
            AtomicStore(&slot.xiaoai_state, uint32_t{6});
            __atomic_store_n(&g_xiaoai_state_hook_state, uint32_t{6},
                             __ATOMIC_RELEASE);
            Log(ANDROID_LOG_WARN,
                "dynamic native XiaoAi state observer unavailable");
        }
    } else {
        AtomicStore(&slot.xiaoai_state, uint32_t{2});
        if (AtomicLoad(&g_xiaoai_state_hook_state) == uint32_t{0}) {
            __atomic_store_n(&g_xiaoai_state_hook_state, uint32_t{2},
                             __ATOMIC_RELEASE);
        }
    }
    AtomicStore(&slot.state, uint32_t{3});
    return true;
}

bool InstallClaimedBusinessHooksForProfile(void* app_entry_point, bool repair) {
    uint8_t* base = nullptr;
    const auto* profile = ResolveLauncherProfile(app_entry_point, &base);
    if (profile == nullptr || base == nullptr) {
        AtomicStore(&g_business_hook_state, uint32_t{4});
        Log(ANDROID_LOG_ERROR,
            "business probe failed to resolve a supported launcher profile");
        return false;
    }
    g_launcher_base = base;
    const bool side_matches = MatchesCode(
            base, profile->side_handler_offset,
            profile->side_handler_prologue,
            profile->side_handler_prologue_size);
    const bool legacy_matches =
            profile->business_topology !=
                    miui_home_profiles::BusinessHookTopology::kLegacyThreeStage ||
            (MatchesCode(base, profile->pointer_handler_offset,
                         profile->pointer_handler_prologue,
                         profile->pointer_handler_prologue_size) &&
             MatchesCode(base, profile->touch_processor_offset,
                         profile->touch_processor_prologue,
                         profile->touch_processor_prologue_size));
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "launcher profile fingerprints: id=%s side=0x%zx match=%d legacy=%d",
            profile->id, profile->side_handler_offset,
            side_matches ? 1 : 0, legacy_matches ? 1 : 0);
    if (!side_matches || !legacy_matches) {
        AtomicStore(&g_business_hook_state, uint32_t{5});
        Log(ANDROID_LOG_ERROR,
            "business probe rejected profile hook fingerprints");
        return false;
    }

    void* launcher_handle = AtomicLoad(&g_launcher_handle);
    if (launcher_handle == nullptr) {
        AtomicStore(&g_business_hook_state, uint32_t{6});
        Log(ANDROID_LOG_ERROR, "business probe missing launcher handle");
        return false;
    }
    AtomicStore(&g_motion_get_id,
                dlsym(launcher_handle, "input_MotionEvent_getId"));
    AtomicStore(&g_motion_get_down_time,
                dlsym(launcher_handle, "input_MotionEvent_getDownTime"));
    AtomicStore(&g_motion_get_device_id,
                dlsym(launcher_handle, "input_MotionEvent_getDeviceId"));
    AtomicStore(&g_motion_get_source,
                dlsym(launcher_handle, "input_MotionEvent_getSource"));
    AtomicStore(&g_motion_get_raw_x,
                dlsym(launcher_handle, "input_MotionEvent_getRawX"));
    AtomicStore(&g_motion_get_raw_y,
                dlsym(launcher_handle, "input_MotionEvent_getRawY"));
    AtomicStore(&g_motion_get_y,
                dlsym(launcher_handle, "input_MotionEvent_getY"));
    if (AtomicLoad(&g_motion_get_id) == nullptr ||
            AtomicLoad(&g_motion_get_down_time) == nullptr ||
            AtomicLoad(&g_motion_get_device_id) == nullptr ||
            AtomicLoad(&g_motion_get_source) == nullptr ||
            AtomicLoad(&g_motion_get_raw_x) == nullptr ||
            (AtomicLoad(&g_motion_get_raw_y) == nullptr &&
                    AtomicLoad(&g_motion_get_y) == nullptr) ||
            AtomicLoad(&g_original_motion_get_action) == nullptr ||
            AtomicLoad(&g_original_motion_get_action_masked) == nullptr ||
            AtomicLoad(&g_original_input_monitor_pilfer) == nullptr) {
        AtomicStore(&g_business_hook_state, uint32_t{6});
        Log(ANDROID_LOG_ERROR,
            "business probe MotionEvent identity hook failed");
        return false;
    }
    // Install transparent leaf hooks first. They cannot claim a stream until
    // the outer accepted-DOWN handler publishes thread-local ownership, so a
    // later failure cannot leave Xiaomi business unconditionally disabled.
    if (InstallInlineHook(
                base + profile->side_handler_offset,
                reinterpret_cast<void*>(HookGestureStubBackHandler),
                &g_original_gesture_stub_back_handler) != kHookSuccess ||
            AtomicLoad(&g_original_gesture_stub_back_handler) == nullptr) {
        AtomicStore(&g_business_hook_state, uint32_t{6});
        Log(ANDROID_LOG_ERROR, "GestureStub Back handler hook failed");
        return false;
    }
    if (profile->business_topology ==
            miui_home_profiles::BusinessHookTopology::kLegacyThreeStage) {
        // 4371 still has the two transparent diagnostic stages around the
        // side-only boundary. They never claim a stream themselves.
        if (InstallInlineHook(
                    base + profile->touch_processor_offset,
                    reinterpret_cast<void*>(HookGestureBackTouchProcessor),
                    &g_original_gesture_back_touch_processor) != kHookSuccess ||
                AtomicLoad(&g_original_gesture_back_touch_processor) == nullptr) {
            AtomicStore(&g_business_hook_state, uint32_t{6});
            Log(ANDROID_LOG_ERROR, "inner processor business hook failed");
            return false;
        }
        if (InstallInlineHook(
                    base + profile->pointer_handler_offset,
                    reinterpret_cast<void*>(HookGestureStubPointerHandler),
                    &g_original_gesture_stub_pointer_handler) != kHookSuccess ||
                AtomicLoad(&g_original_gesture_stub_pointer_handler) == nullptr) {
            AtomicStore(&g_business_hook_state, uint32_t{6});
            Log(ANDROID_LOG_ERROR, "accepted-DOWN outer handler hook failed");
            return false;
        }
    }
    if (!InstallContextualSearchLongPressHook(base, profile)) {
        // Contextual Search is an optional terminal route. Its exact profile
        // failure must leave Xiaomi's original closure intact without taking
        // down the independently proven side-gesture handoff.
        Log(ANDROID_LOG_WARN,
            "continuing without native contextual-search terminal route");
    }
    const auto* dart_profile = CurrentDartFeatureProfile();
    if (!InstallDrawerStateHook(
                       base, dart_profile != nullptr ? dart_profile : profile)) {
        // Drawer state is independent of accepted-DOWN ownership. If its
        // exact bridge is absent or ambiguous, idle launcher Home remains
        // unclaimed and the side gesture path stays otherwise unchanged.
        Log(ANDROID_LOG_WARN,
            "continuing without native ALL_APPS state bridge");
    }
    if (!InstallOverviewStateHook(
                       base, dart_profile != nullptr ? dart_profile : profile)) {
        // Overview is callback-only ownership. A missing exact state bridge
        // must leave launcher Home unclaimed; it must not weaken the already
        // validated in-app side-gesture handoff.
        Log(ANDROID_LOG_WARN,
            "continuing without native Overview state bridge");
    }
    AtomicStore(&g_business_hook_state, uint32_t{3});
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "%s profile %s with %s business hook",
            profile->id, repair ? "repaired" : "installed",
            profile->business_topology ==
                    miui_home_profiles::BusinessHookTopology::kLegacyThreeStage
                    ? "legacy diagnostics + side boundary"
                    : "side boundary only");
    TryInstallArbiterBridge();
    return true;
}

void InstallBusinessHooksForProfile(void* app_entry_point) {
    uint32_t expected_state = 0u;
    if (!__atomic_compare_exchange_n(&g_business_hook_state, &expected_state,
                                     uint32_t{1}, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    InstallClaimedBusinessHooksForProfile(app_entry_point, false);
}

void RepairBusinessHooksIfRemapped(uint32_t slot_index) {
    if (slot_index >= kLauncherInputSlotCount ||
            AtomicLoad(&g_business_hook_state) != uint32_t{3}) {
        return;
    }
    auto* base = reinterpret_cast<uint8_t*>(
            AtomicLoad(&g_launcher_input_slots[slot_index].base));
    const auto* profile = AtomicLoad(
            &g_launcher_input_slots[slot_index].profile);
    if (base == nullptr || base != g_launcher_base || profile == nullptr ||
            profile != CurrentLauncherProfile()) return;
    const bool outer_original = MatchesCode(
            base, profile->pointer_handler_offset,
            profile->pointer_handler_prologue,
            profile->pointer_handler_prologue_size);
    const bool inner_original = MatchesCode(
            base, profile->touch_processor_offset,
            profile->touch_processor_prologue,
            profile->touch_processor_prologue_size);
    const bool stub_back_handler_original = MatchesCode(
            base, profile->side_handler_offset,
            profile->side_handler_prologue,
            profile->side_handler_prologue_size);
    const bool contextual_hook_expected =
            AtomicLoad(&g_contextual_long_press_hook_state) == uint32_t{3} &&
            profile->contextual_long_press_handler_offset != 0u;
    const bool contextual_handler_original = contextual_hook_expected &&
            MatchesCode(
                    base, profile->contextual_long_press_handler_offset,
                    profile->contextual_long_press_handler_prologue,
                    profile->contextual_long_press_handler_prologue_size);
    const bool drawer_hook_expected =
            AtomicLoad(&g_drawer_state_hook_state) == uint32_t{3} &&
            AtomicLoad(&g_original_drawer_state_handler) != nullptr &&
            profile->drawer_state_handler_offset != 0u;
    const bool drawer_handler_original = drawer_hook_expected &&
            MatchesCode(base, profile->drawer_state_handler_offset,
                        profile->drawer_state_handler_prologue,
                        profile->drawer_state_handler_prologue_size);
    const bool legacy = profile->business_topology ==
            miui_home_profiles::BusinessHookTopology::kLegacyThreeStage;
    if (!stub_back_handler_original &&
            (!legacy || (!outer_original && !inner_original)) &&
            !contextual_handler_original && !drawer_handler_original) {
        return;
    }
    if (legacy && (outer_original != inner_original ||
            outer_original != stub_back_handler_original)) {
        __atomic_store_n(&g_business_repair_stage, uint32_t{6},
                         __ATOMIC_RELEASE);
        __atomic_fetch_add(&g_business_repair_failure_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        Log(ANDROID_LOG_ERROR,
            "refusing inconsistent one-sided Launcher business hook repair");
        return;
    }
    uint32_t expected_state = 3u;
    if (!__atomic_compare_exchange_n(&g_business_hook_state, &expected_state,
                                     uint32_t{1}, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    __atomic_fetch_add(&g_business_repair_attempt_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    __atomic_store_n(&g_business_repair_stage, uint32_t{1}, __ATOMIC_RELEASE);
    const int stub_back_handler_unhook = RemoveInlineHook(
            base + profile->side_handler_offset);
    const int inner_unhook = legacy ? RemoveInlineHook(
            base + profile->touch_processor_offset) : kHookSuccess;
    const int outer_unhook = legacy ? RemoveInlineHook(
            base + profile->pointer_handler_offset) : kHookSuccess;
    const int contextual_unhook = contextual_hook_expected
            ? RemoveInlineHook(
                    base + profile->contextual_long_press_handler_offset)
            : kHookSuccess;
    const int drawer_unhook = drawer_hook_expected
            ? RemoveInlineHook(base + profile->drawer_state_handler_offset)
            : kHookSuccess;
    if (stub_back_handler_unhook != kHookSuccess || inner_unhook != kHookSuccess ||
            outer_unhook != kHookSuccess || contextual_unhook != kHookSuccess ||
            drawer_unhook != kHookSuccess) {
        __atomic_store_n(&g_business_repair_stage, uint32_t{4},
                         __ATOMIC_RELEASE);
        AtomicStore(&g_business_hook_state, uint32_t{7});
        __atomic_fetch_add(&g_business_repair_failure_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        Log(ANDROID_LOG_ERROR,
            "failed to unregister stale Launcher business hooks");
        return;
    }
    __atomic_store_n(&g_business_repair_stage, uint32_t{2}, __ATOMIC_RELEASE);
    AtomicStore(&g_original_gesture_back_touch_processor,
                static_cast<void*>(nullptr));
    AtomicStore(&g_original_gesture_stub_back_handler,
                static_cast<void*>(nullptr));
    AtomicStore(&g_original_gesture_stub_pointer_handler,
                static_cast<void*>(nullptr));
    AtomicStore(&g_original_contextual_long_press_handler,
                static_cast<void*>(nullptr));
    AtomicStore(&g_contextual_search_invoke, static_cast<void*>(nullptr));
    AtomicStore(&g_original_drawer_state_handler, static_cast<void*>(nullptr));
    if (profile->contextual_long_press_handler_offset != 0u) {
        __atomic_store_n(&g_contextual_long_press_hook_state, uint32_t{0},
                         __ATOMIC_RELEASE);
    }
    if (profile->drawer_state_handler_offset != 0u) {
        __atomic_store_n(&g_drawer_state_hook_state, uint32_t{0},
                         __ATOMIC_RELEASE);
    }
    const bool repaired = InstallClaimedBusinessHooksForProfile(
            base + profile->entry_offset, true);
    __atomic_store_n(&g_business_repair_stage,
                     repaired ? uint32_t{3} : uint32_t{5},
                     __ATOMIC_RELEASE);
    __atomic_fetch_add(repaired ? &g_business_repair_success_count
                               : &g_business_repair_failure_count,
                       uint32_t{1}, __ATOMIC_RELAXED);
}

void RecordLauncherLibraryObservation() {
    const uint64_t sequence = NextHyosLifecycleSequence();
    __atomic_fetch_add(&g_launcher_library_observed_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    if (__atomic_load_n(&g_hyos_launcher_specialized, __ATOMIC_ACQUIRE) != 0u) {
        __atomic_fetch_add(&g_launcher_library_after_specialize_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
    }
    RecordFirstLifecycleSequence(&g_launcher_library_observed_sequence,
                                 sequence);
}

void RecordLauncherEntryObservation() {
    const uint64_t sequence = NextHyosLifecycleSequence();
    __atomic_fetch_add(&g_launcher_entry_observed_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    if (__atomic_load_n(&g_hyos_launcher_specialized, __ATOMIC_ACQUIRE) != 0u) {
        __atomic_fetch_add(&g_launcher_entry_after_specialize_count,
                           uint32_t{1}, __ATOMIC_RELAXED);
    }
    RecordFirstLifecycleSequence(&g_launcher_entry_observed_sequence,
                                 sequence);
}

void ObserveLauncherHandle(const char* filename, void* result) {
    if (result != nullptr && IsLauncherLibraryPath(filename) &&
            IsLauncherHookProcess()) {
        RecordLauncherLibraryObservation();
        AtomicStore(&g_launcher_handle, result);
        Log(ANDROID_LOG_INFO, "matched MiuiHome libapp_launcher.so");
    }
}

void ObserveLauncherSymbol(void* handle, const char* symbol, void* result) {
    if (result != nullptr && handle == AtomicLoad(&g_launcher_handle) &&
            symbol != nullptr &&
            strcmp(symbol, kLauncherEntrySymbol) == 0 &&
            __atomic_exchange_n(&g_entry_reported, uint32_t{1},
                                __ATOMIC_ACQ_REL) == 0u) {
        RecordLauncherEntryObservation();
        Log(ANDROID_LOG_INFO, "resolved MiuiHome app_entry_point");
    }
}

void OnLsposedLibraryLoaded(const char* name, void* handle);

// The HYOS launcher can preload its native images before LSPosed invokes the
// module's native_init entry.  LSPosed's on-library-loaded callback only
// covers subsequent loads, so explicitly replay the small set of library
// identities used by this payload.  RTLD_NOLOAD is intentional: this must not
// cause any new native dependency to be loaded merely because the module was
// initialized.
void BackfillLoadedLibrary(const char* name) {
    if (name == nullptr) return;
    void* handle = dlopen(name, RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) return;
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "backfilling already-loaded native image: %s", name);
    OnLsposedLibraryLoaded(name, handle);
    dlclose(handle);
}

void BackfillLoadedLibraries() {
    if (!IsLauncherHookProcess()) return;
    BackfillLoadedLibrary("libapp_launcher.so");
    BackfillLoadedLibrary(kHyperRuntimeName);
    BackfillLoadedLibrary("libapp.so");
    BackfillLoadedLibrary(kBroadcastPrivatePath);
}

void OnLsposedLibraryLoaded(const char* name, void* handle) {
    if (name == nullptr || handle == nullptr) return;
    if (IsLauncherProcess() &&
            !EnsureDartStateOwnerForCurrentProcess()) {
        Log(ANDROID_LOG_ERROR,
            "launcher child rejected without a process-local Dart owner");
        return;
    }

    if (EndsWith(name, kHyperRuntimeName)) {
        EnsureLsposedMadviseGuard(name);
    }
    ObserveLauncherHandle(name, handle);
    if (IsLauncherLibraryPath(name) && IsLauncherHookProcess()) {
        if (!EnsureLsposedMadviseGuard()) {
            Log(ANDROID_LOG_ERROR,
                "LSPosed launcher hooks rejected without madvise guard");
            return;
        }
        void* app_entry_point = dlsym(handle, kLauncherEntrySymbol);
        ObserveLauncherSymbol(handle, kLauncherEntrySymbol, app_entry_point);
        if (app_entry_point != nullptr) {
            AtomicStore(&g_launcher_handle, handle);
            if (InstallLauncherInputHooksForProfile(app_entry_point)) {
                InstallBusinessHooksForProfile(app_entry_point);
            } else {
                Log(ANDROID_LOG_ERROR,
                    "LSPosed callback rejected launcher input hook setup");
            }
        }
    }

    if (IsDartLibraryPath(name) && IsLauncherHookProcess()) {
        const auto* profile = ResolveDartFeatureProfile(handle);
        if (profile != nullptr) {
            TryInstallDartDrawerStateHook(handle, profile);
            TryInstallDartOverviewStateHook(handle, profile);
            TryInstallDartEditingStateHook(handle, profile);
        }
    }
    TryInstallArbiterBridge();
}

}  // namespace

extern "C" __attribute__((visibility("hidden")))
void MiuiHomeHyosInputMonitorPilferImpl(void* monitor, uintptr_t return_pc) {
    HandleInputMonitorPilfer(monitor, return_pc);
}

extern "C" __attribute__((visibility("default"), unused))
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    const bool backend_ready = InitializeLsposedHookBackend(entries);
    const bool hyos_process = IsHyosSpawnerProcessFamily();
    const bool launcher_process = IsLauncherProcess();
    __android_log_print(
            ANDROID_LOG_INFO, kLogTag,
            "native_init checks: entries=%u hook=%u unhook=%u backend=%u "
            "hyos_exe=%u launcher_cmdline=%u",
            entries != nullptr ? 1u : 0u,
            entries != nullptr && entries->hookFunc != nullptr ? 1u : 0u,
            entries != nullptr && entries->unhookFunc != nullptr ? 1u : 0u,
            backend_ready ? 1u : 0u, hyos_process ? 1u : 0u,
            launcher_process ? 1u : 0u);
    if (!backend_ready) {
        return nullptr;
    }
    if (!hyos_process) {
        Log(ANDROID_LOG_WARN,
            "LSPosed native entry rejected a non-launcher HYOS process");
        return nullptr;
    }
    uint32_t atfork_expected = 0u;
    if (__atomic_compare_exchange_n(
                &g_dart_state_atfork_state, &atfork_expected, uint32_t{1},
                false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        const int atfork_result = pthread_atfork(
                nullptr, nullptr, ResetDartStateOwnerAfterFork);
        __atomic_store_n(&g_dart_state_atfork_state,
                         atfork_result == 0 ? uint32_t{3} : uint32_t{6},
                         __ATOMIC_RELEASE);
        if (atfork_result != 0) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "Dart owner atfork registration failed: %d",
                                atfork_result);
        }
    }
    MarkLsposedLauncherSpecialized();
    Log(ANDROID_LOG_INFO,
        "LSPosed native hook initialized in MiuiHome HYOS child");
    BackfillLoadedLibraries();
    return OnLsposedLibraryLoaded;
}
