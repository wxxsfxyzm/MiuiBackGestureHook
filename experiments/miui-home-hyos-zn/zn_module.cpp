#include "zygisk_next_api.h"
#include "launcher_profiles.h"
#include "launcher_profiles.generated.h"

#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
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
void MiuiHomeHyosInputMonitorPilferImpl(void* monitor, uintptr_t return_pc);

}

namespace {

constexpr char kLogTag[] = "MiuiHomeHyosZn";
// The first 4371 private-broadcast hook confused its 16-byte Rust x8 result
// with the public wrapper's 48-byte result and aborted in Scudo.  The raw tail
// shim preserves that ABI. Device validation remains bounded by the Zygisk
// Next module enabled state, the exact process, and immutable library IDs.
constexpr char kNativeReceiverExperimentLeasePath[] =
        "/data/user_de/0/com.miui.home/cache/"
        "miui_home_hyos_zn_native_receiver_once";
constexpr char kSpawnerPath[] = "/system_ext/bin/hyos_spawner";
constexpr char kShellPath[] = "/system_ext/lib64/libhyper_os_shell.so";
constexpr char kShellName[] = "libhyper_os_shell.so";
constexpr char kAppPublicPath[] =
        "/system_ext/lib64/libhyper_os_app_public.so";
constexpr char kAppPublicName[] = "libhyper_os_app_public.so";
constexpr char kBroadcastPrivatePath[] =
        "/system_ext/lib64/libhyper_os_broadcast_private.dylib.so";
constexpr uintptr_t kBroadcastIntentWithFeatureGotOffset = 0x14ed0u;
constexpr uintptr_t kBroadcastIntentWithFeatureSymbolOffset = 0x10d74u;
constexpr char kBroadcastReceiverOnReceiveSymbol[] =
        "_RNvMs3_NtNtCslLvADlVgqlk_26hyper_os_broadcast_private13dyn_"
        "broadcast23BroadcastReceiver_traitINtB5_20BroadcastReceiver_TOINtNtNtNt"
        "Cs9Neji4M1weT_10abi_stable9std_types5boxed7private4RBoxuEE10on_receiveB9_";
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
constexpr uint8_t kExpectedSpawnerBuildId[] = {
        0x87, 0xf2, 0x63, 0x2e, 0x7d, 0x68, 0xfd, 0xa0,
        0x22, 0x63, 0x66, 0xfd, 0xa5, 0x34, 0x6c, 0x2d,
};
constexpr uint8_t kExpectedBroadcastPrivateBuildId[] = {
        0x7f, 0x18, 0x6b, 0x33, 0x1e, 0xc3, 0x9d, 0x84,
        0xe6, 0x01, 0x6d, 0xae, 0xba, 0x65, 0x36, 0x6f,
};
// Redmi K90 (annibale, OS4.0.0.18) ships the same broadcast-private dylib
// layout with a different Build ID (c4fec5d3d810f76eff819d9379517a4a); the
// arbiter-bridge gate accepts either one.
constexpr uint8_t kExpectedBroadcastPrivateBuildIdK90[] = {
        0xc4, 0xfe, 0xc5, 0xd3, 0xd8, 0x10, 0xf7, 0x6e,
        0xff, 0x81, 0x9d, 0x93, 0x79, 0x51, 0x7a, 0x4a,
};

using DlopenFn = void* (*)(const char*, int);
using AndroidDlopenExtFn = void* (*)(const char*, int,
                                    const android_dlextinfo*);
using DlsymFn = void* (*)(void*, const char*);
using BackCallbackQueryFn = uint8_t (*)(void*, void*, const char*, size_t);
using BackSwipeStartFn = void (*)(void*, void*, uint32_t);
using BackCancelledFn = void (*)(void*);
using BackInvokeFn = void (*)(void*, uint32_t);
using InterruptOpenPollFn = uint8_t (*)(void*, void*);
using MotionEventIntFn = int32_t (*)(void*);
using MotionEventLongFn = int64_t (*)(void*);
using MotionEventFloatFn = float (*)(void*);
using InputMonitorPilferFn = void (*)(void*);
using GestureStubPointerHandlerFn = void (*)(void*, void*, void*, uint32_t);
using GestureBackTouchProcessorFn = void (*)(void*, void*, void*);
using GestureStubBackHandlerFn = void (*)(void*, void*);

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

struct NativeI64Option {
    uint64_t tag;
    int64_t value;
};

using IntentGetActionFn = BorrowedROptionRString (*)(void*);
using IntentGetSenderPackageFn = BorrowedROptionRString (*)(void*);
using BroadcastReceiverOnReceiveFn = void (*)(void*, void*, void*);
using BroadcastRegisterReceiverFn = NativeResult (*)(
        void*, void*, void*, void*, void*, uint32_t);
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
using IntentFilterDefaultFn = void* (*)();
using IntentFilterAddActionFn = void (*)(void*, RString*);
using RuntimeStrongFn = void (*)(void*);
using PackageManagerGetApplicationInfoFn = NativeResult (*)(
        const char*, size_t, uint64_t);
using ApplicationInfoGetUidFn = int32_t (*)(void*);
using ApplicationInfoDropFn = void (*)(void*);

ZygiskNextAPI g_api{};
void* g_original_dlopen = nullptr;
void* g_original_dlsym = nullptr;
void* g_original_shell_android_dlopen_ext = nullptr;
void* g_original_shell_dlsym = nullptr;
void* g_original_app_public_dlsym = nullptr;
void* g_launcher_handle = nullptr;
void* g_original_back_callback_query = nullptr;
void* g_original_back_swipe_start = nullptr;
void* g_original_back_cancelled = nullptr;
void* g_original_back_invoke = nullptr;
void* g_original_interrupt_open_poll = nullptr;
void* g_original_motion_get_action = nullptr;
void* g_original_motion_get_action_masked = nullptr;
void* g_original_input_monitor_pilfer = nullptr;
void* g_original_gesture_stub_pointer_handler = nullptr;
void* g_original_gesture_back_touch_processor = nullptr;
void* g_original_gesture_stub_back_handler = nullptr;
void* g_original_broadcast_receiver_on_receive = nullptr;
void* g_original_broadcast_register_receiver = nullptr;
void** g_broadcast_intent_with_feature_slot = nullptr;
void* g_motion_get_id = nullptr;
void* g_motion_get_down_time = nullptr;
void* g_motion_get_device_id = nullptr;
void* g_motion_get_source = nullptr;
void* g_motion_get_raw_x = nullptr;
uint8_t* g_launcher_base = nullptr;
const miui_home_profiles::LauncherProfile* g_launcher_profile = nullptr;
uint32_t g_native_receiver_state = 0;
NativeResult g_module_receiver_registration{};
int64_t g_systemui_arbiter_generation = 0;
uint32_t g_systemui_arbiter_ready = 0;
uint32_t g_entry_reported = 0;
uint32_t g_shell_hook_state = 0;
uint32_t g_app_public_hook_state = 0;
uint32_t g_business_hook_state = 0;
uint32_t g_arbiter_bridge_hook_state = 0;
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
__attribute__((used)) volatile uint32_t g_back_callback_query_count = 0;
__attribute__((used)) volatile uint32_t g_back_callback_query_last_result = 0;
__attribute__((used)) volatile uint32_t g_back_swipe_start_count = 0;
__attribute__((used)) volatile uint32_t g_back_cancelled_count = 0;
__attribute__((used)) volatile uint32_t g_back_invoke_count = 0;
__attribute__((used)) volatile uint32_t g_back_invoke_last_arg1 = 0;
__attribute__((used)) volatile uint32_t g_interrupt_open_poll_count = 0;
__attribute__((used)) volatile uint32_t g_interrupt_open_last_result = 0;
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
__attribute__((used)) volatile uintptr_t g_pilfer_last_return_pc = 0;
__attribute__((used)) volatile intptr_t g_pilfer_last_return_offset = 0;
__attribute__((used)) volatile uint32_t g_pilfer_caller_be8e98_count = 0;
__attribute__((used)) volatile uint32_t g_pilfer_caller_bf07b4_count = 0;
__attribute__((used)) volatile uint32_t g_pilfer_caller_c11a7c_count = 0;
__attribute__((used)) volatile uint32_t g_pilfer_caller_c12e2c_count = 0;
__attribute__((used)) volatile uint32_t
        g_owned_stream_pilfer_suppressed_count = 0;
__attribute__((used)) volatile uint32_t g_motion_action_call_count = 0;
__attribute__((used)) volatile uint32_t g_motion_action_masked_call_count = 0;
__attribute__((used)) volatile uint32_t g_motion_down_capture_count = 0;
__attribute__((used)) volatile uint32_t g_business_repair_attempt_count = 0;
__attribute__((used)) volatile uint32_t g_business_repair_success_count = 0;
__attribute__((used)) volatile uint32_t g_business_repair_failure_count = 0;
__attribute__((used)) volatile uint32_t g_business_repair_stage = 0;

// Observation-only ring for identifying the exact 4371 pilfer owner. The
// sequence is published last, so /proc/<pid>/mem readers can reject a torn
// slot. No field participates in input ownership or changes native behavior.
constexpr uint32_t kPilferObservationCount = 16u;
struct PilferObservation {
    uint64_t sequence;
    uintptr_t return_pc;
    intptr_t return_offset;
    uintptr_t monitor;
    uintptr_t motion_event;
    uint64_t motion_sequence;
    int64_t down_time;
    int32_t tid;
    int32_t action;
    uint32_t action_masked_method;
    int32_t event_id;
    int32_t device_id;
    int32_t source;
    uint32_t edge;
    uint32_t pending_down_valid;
};
__attribute__((used)) volatile uint64_t g_pilfer_observation_sequence = 0;
__attribute__((used)) PilferObservation
        g_pilfer_observations[kPilferObservationCount]{};
constexpr uint32_t kLauncherInputSlotCount = 4u;
struct LauncherInputHookSlot {
    uintptr_t base;
    const miui_home_profiles::LauncherProfile* profile;
    void* original_action;
    void* original_action_masked;
    void* original_pilfer;
    uint32_t state;
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
thread_local uint64_t g_last_motion_sequence = 0u;
thread_local int32_t g_last_motion_action = -1;
thread_local bool g_last_motion_used_masked_method = false;
constexpr uint32_t kCaptureSlotCount = 64u;
constexpr uint32_t kCaptureSlotSize = 256u;
__attribute__((used)) volatile uint32_t g_dlopen_capture_index = 0;
__attribute__((used)) volatile char
        g_dlopen_capture[kCaptureSlotCount][kCaptureSlotSize]{};
__attribute__((used)) volatile uint32_t g_dlsym_capture_index = 0;
__attribute__((used)) volatile char
        g_dlsym_capture[kCaptureSlotCount][kCaptureSlotSize]{};
__attribute__((used)) volatile uint32_t
        g_android_dlopen_ext_capture_index = 0;
__attribute__((used)) volatile char
        g_android_dlopen_ext_capture[kCaptureSlotCount][kCaptureSlotSize]{};

template <typename T>
T AtomicLoad(const T* value) {
    return __atomic_load_n(value, __ATOMIC_ACQUIRE);
}

template <typename T>
void AtomicStore(T* target, T value) {
    __atomic_store_n(target, value, __ATOMIC_RELEASE);
}

void Log(int priority, const char* message) {
    __android_log_write(priority, kLogTag, message);
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

bool ReadFullyAt(int fd, void* destination, size_t size, off_t offset) {
    auto* cursor = static_cast<uint8_t*>(destination);
    while (size != 0u) {
        const ssize_t result = pread(fd, cursor, size, offset);
        if (result == 0) return false;
        if (result < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        cursor += static_cast<size_t>(result);
        size -= static_cast<size_t>(result);
        offset += result;
    }
    return true;
}

constexpr size_t AlignNote(size_t value) {
    return (value + size_t{3}) & ~size_t{3};
}

bool NoteContainsBuildId(const uint8_t* notes, size_t size,
                         const uint8_t* expected, size_t expected_size) {
    size_t offset = 0u;
    while (size - offset >= sizeof(Elf64_Nhdr)) {
        Elf64_Nhdr header{};
        memcpy(&header, notes + offset, sizeof(header));
        offset += sizeof(header);

        const size_t name_size = header.n_namesz;
        const size_t desc_size = header.n_descsz;
        const size_t aligned_name = AlignNote(name_size);
        const size_t aligned_desc = AlignNote(desc_size);
        if (aligned_name > size - offset) return false;
        const uint8_t* name = notes + offset;
        offset += aligned_name;
        if (aligned_desc > size - offset) return false;
        const uint8_t* description = notes + offset;
        offset += aligned_desc;

        if (header.n_type == NT_GNU_BUILD_ID && name_size == 4u &&
                memcmp(name, "GNU", 4u) == 0 &&
                desc_size == expected_size &&
                memcmp(description, expected, expected_size) == 0) {
            return true;
        }
    }
    return false;
}

bool ValidateElfBuildId(const char* path, const uint8_t* expected,
                        size_t expected_size) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    Elf64_Ehdr elf_header{};
    bool valid = ReadFullyAt(fd, &elf_header, sizeof(elf_header), 0) &&
            memcmp(elf_header.e_ident, ELFMAG, SELFMAG) == 0 &&
            elf_header.e_ident[EI_CLASS] == ELFCLASS64 &&
            elf_header.e_ident[EI_DATA] == ELFDATA2LSB &&
            elf_header.e_machine == EM_AARCH64 &&
            elf_header.e_phentsize == sizeof(Elf64_Phdr) &&
            elf_header.e_phnum != 0u && elf_header.e_phnum <= 128u;

    for (uint16_t index = 0u; valid && index < elf_header.e_phnum; ++index) {
        Elf64_Phdr program_header{};
        const off_t program_offset = static_cast<off_t>(elf_header.e_phoff) +
                static_cast<off_t>(index) * sizeof(Elf64_Phdr);
        if (!ReadFullyAt(fd, &program_header, sizeof(program_header),
                         program_offset)) {
            valid = false;
            break;
        }
        if (program_header.p_type != PT_NOTE) continue;
        if (program_header.p_filesz == 0u ||
                program_header.p_filesz > 64u * 1024u) {
            continue;
        }
        uint8_t notes[64u * 1024u]{};
        if (!ReadFullyAt(fd, notes,
                         static_cast<size_t>(program_header.p_filesz),
                         static_cast<off_t>(program_header.p_offset))) {
            valid = false;
            break;
        }
        if (NoteContainsBuildId(notes,
                    static_cast<size_t>(program_header.p_filesz),
                    expected, expected_size)) {
            close(fd);
            return true;
        }
    }

    close(fd);
    return false;
}

bool ValidateSpawnerBuildId() {
    return ValidateElfBuildId(kSpawnerPath, kExpectedSpawnerBuildId,
                              sizeof(kExpectedSpawnerBuildId));
}

bool IsExplicitlyEnabled() {
    // Reaching OnModuleLoaded already proves Zygisk Next enabled and injected
    // this module. hyos_spawner's SELinux domain cannot read /data/adb/modules,
    // so a module-local marker cannot be a valid in-process gate.
    return true;
}

bool IsBusinessProbeEnabled() {
    return true;
}

bool IsArbiterBridgeEnabled() {
    return true;
}

[[maybe_unused]] uint32_t GetNativeReceiverExperimentMode() {
    return 0u;
}

[[maybe_unused]] bool AcquireNativeReceiverExperimentLease() {
    const int fd = open(kNativeReceiverExperimentLeasePath,
                        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd >= 0) {
        close(fd);
        return true;
    }
    __android_log_print(
            errno == EEXIST ? ANDROID_LOG_WARN : ANDROID_LOG_ERROR,
            kLogTag,
            "native receiver experiment lease denied errno=%d", errno);
    return false;
}

[[maybe_unused]] constexpr uintptr_t kBackCallbackQueryOffset4371 = 0x85ab4cu;
[[maybe_unused]] constexpr uintptr_t kBackSwipeStartOffset4371 = 0xc0b440u;
[[maybe_unused]] constexpr uintptr_t kBackCancelledOffset4371 = 0xc0d840u;
[[maybe_unused]] constexpr uintptr_t kBackInvokeOffset4371 = 0xc0df2cu;
[[maybe_unused]] constexpr uintptr_t kInterruptOpenPollOffset4371 = 0xc91d94u;
// Historical traces saw InputMonitor.pilferPointers at 0xbf07b0 and later at
// 0xc11a78, but 0.8.20 proved that GestureStubView can remain the physical
// DOWN owner without either call. Keep these immutable callers as transparent
// diagnostics and suppress them only if the exact stream was already handed
// off at the processor's accepted DOWN boundary.
// Exact 4371 device evidence proves GestureStubViewWindow::handle_back_gesture
// is the side-only accepted-input boundary. Bottom Home never reaches it.
// Ownership is enabled only there; shared GestureInputMonitor hooks remain
// transparent diagnostics.
[[maybe_unused]] constexpr uintptr_t kRustLogFormatterOffset4371 = 0x688bacu;
[[maybe_unused]] constexpr uintptr_t kAcceptedLogCallOffset4371 = 0xbf4bb8u;
[[maybe_unused]] constexpr uintptr_t kAcceptedLogReturnOffset4371 = 0xbf4bc8u;

constexpr char kSystemUiPackage[] = "com.android.systemui";
constexpr char kArbiterStateAction[] =
        "dev.codex.miuibackgesturehook.action.SYSTEMUI_INPUT_ARBITER_STATE";
constexpr char kArbiterStateCarrierAction[] = "com.android.systemui.fsgesture";
constexpr char kArbiterQueryAction[] =
        "dev.codex.miuibackgesturehook.action.MIUI_HOME_INPUT_ARBITER_QUERY";
constexpr char kAcceptedStateAction[] =
        "dev.codex.miuibackgesturehook.action.MIUI_OVERVIEW_STATE_CHANGE";

[[maybe_unused]] constexpr uint8_t kBackCallbackQueryPrologue4371[] = {
        0xe8, 0x0f, 0x19, 0xfc, 0xfd, 0x7b, 0x01, 0xa9,
        0xfc, 0x6f, 0x02, 0xa9, 0xfa, 0x67, 0x03, 0xa9,
        0xf8, 0x5f, 0x04, 0xa9, 0xf6, 0x57, 0x05, 0xa9,
        0xf4, 0x4f, 0x06, 0xa9, 0xff, 0xc3, 0x0b, 0xd1,
};
[[maybe_unused]] constexpr uint8_t kBackSwipeStartPrologue4371[] = {
        0xe8, 0x0f, 0x19, 0xfc, 0xfd, 0x7b, 0x01, 0xa9,
        0xfc, 0x6f, 0x02, 0xa9, 0xfa, 0x67, 0x03, 0xa9,
        0xf8, 0x5f, 0x04, 0xa9, 0xf6, 0x57, 0x05, 0xa9,
        0xf4, 0x4f, 0x06, 0xa9, 0xff, 0x83, 0x06, 0xd1,
};
[[maybe_unused]] constexpr uint8_t kBackCancelledPrologue4371[] = {
        0xff, 0x83, 0x04, 0xd1, 0xfd, 0x7b, 0x0e, 0xa9,
        0xf8, 0x5f, 0x0f, 0xa9, 0xf6, 0x57, 0x10, 0xa9,
        0xf4, 0x4f, 0x11, 0xa9, 0x14, 0x04, 0x40, 0xf9,
        0xf3, 0x03, 0x00, 0xaa, 0xd4, 0x1b, 0x00, 0xb4,
};
[[maybe_unused]] constexpr uint8_t kBackInvokePrologue4371[] = {
        0xff, 0x03, 0x06, 0xd1, 0xfd, 0x7b, 0x12, 0xa9,
        0xfc, 0x6f, 0x13, 0xa9, 0xfa, 0x67, 0x14, 0xa9,
        0xf8, 0x5f, 0x15, 0xa9, 0xf6, 0x57, 0x16, 0xa9,
        0xf4, 0x4f, 0x17, 0xa9, 0xf9, 0x03, 0x00, 0xaa,
};
[[maybe_unused]] constexpr uint8_t kInterruptOpenPollPrologue4371[] = {
        0xff, 0xc3, 0x03, 0xd1, 0xfe, 0x5f, 0x0c, 0xa9,
        0xf6, 0x57, 0x0d, 0xa9, 0xf4, 0x4f, 0x0e, 0xa9,
        0x08, 0xc4, 0x40, 0x39, 0xf3, 0x03, 0x00, 0xaa,
        0xf4, 0x03, 0x01, 0xaa, 0x48, 0x02, 0x00, 0x34,
};
[[maybe_unused]] constexpr uint8_t kRustLogFormatterPrologue4371[] = {
        0xff, 0xc3, 0x02, 0xd1, 0xfe, 0x53, 0x00, 0xf9,
        0x49, 0x28, 0x40, 0xa9, 0x48, 0x10, 0x40, 0xf9,
};
[[maybe_unused]] constexpr uint8_t kAcceptedLogCall4371[] = {
        0x88, 0x37, 0x00, 0xf0, 0x08, 0x81, 0x12, 0x91,
        0xe8, 0x53, 0x00, 0xf9, 0xfa, 0x4f, 0xea, 0x97,
        0x00, 0x19, 0x80, 0x52,
};

bool MatchesCode(const uint8_t* base, uintptr_t offset,
                 const uint8_t* expected, size_t expected_size) {
    return base != nullptr && expected != nullptr && expected_size != 0u &&
            memcmp(base + offset, expected, expected_size) == 0;
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
    const auto* current = AtomicLoad(&g_launcher_profile);
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
    int32_t sender_uid = -1;
    int64_t generation = 0;
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
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "native arbiter ready=%u generation=%lld uid=%d",
                        ready ? 1u : 0u,
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

bool HasArbiterStateMarker(void* intent) {
    IntentGetExtrasFn get_extras = ResolveLauncherSymbol<IntentGetExtrasFn>(
            "Intent_get_extras");
    if (intent == nullptr || get_extras == nullptr) return false;
    void* extras = get_extras(intent);
    int64_t generation = 0;
    return ReadNativeI64(extras, "input_arbiter_generation", &generation) &&
            generation > 0;
}

void HookBroadcastReceiverOnReceive(void* receiver, void* context, void* intent) {
    BroadcastReceiverOnReceiveFn original =
            reinterpret_cast<BroadcastReceiverOnReceiveFn>(
                    AtomicLoad(&g_original_broadcast_receiver_on_receive));
    if (original == nullptr) return;
    if (intent == nullptr) {
        original(receiver, context, intent);
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
        __atomic_fetch_add(&g_arbiter_state_passthrough_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        original(receiver, context, intent);
        return;
    }
    original(receiver, context, intent);
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

bool InstallBroadcastIntentWithFeatureGot(void* resolved) {
    AtomicStore(&g_native_receiver_state, uint32_t{100});
    if (!ValidateElfBuildId(kBroadcastPrivatePath,
                    kExpectedBroadcastPrivateBuildId,
                    sizeof(kExpectedBroadcastPrivateBuildId)) &&
            !ValidateElfBuildId(kBroadcastPrivatePath,
                    kExpectedBroadcastPrivateBuildIdK90,
                    sizeof(kExpectedBroadcastPrivateBuildIdK90))) {
        AtomicStore(&g_native_receiver_state, uint32_t{101});
        return false;
    }
    Dl_info image{};
    if (resolved == nullptr || dladdr(resolved, &image) == 0 ||
            image.dli_fbase == nullptr || image.dli_fname == nullptr ||
            !StringsEqual(image.dli_fname, kBroadcastPrivatePath)) {
        AtomicStore(&g_native_receiver_state, uint32_t{102});
        return false;
    }
    const uintptr_t resolved_address = reinterpret_cast<uintptr_t>(resolved);
    if (resolved_address < kBroadcastIntentWithFeatureSymbolOffset ||
            reinterpret_cast<uintptr_t>(image.dli_fbase) !=
            resolved_address - kBroadcastIntentWithFeatureSymbolOffset) {
        AtomicStore(&g_native_receiver_state, uint32_t{103});
        return false;
    }
    auto** slot = reinterpret_cast<void**>(
            static_cast<uint8_t*>(image.dli_fbase) +
            kBroadcastIntentWithFeatureGotOffset);
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
    if (g_launcher_base == nullptr || profile == nullptr ||
            AtomicLoad(reinterpret_cast<uint32_t*>(
                    g_launcher_base + profile->runtime_state_offset)) !=
                    profile->runtime_ready_value) {
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
    void* runtime = AtomicLoad(reinterpret_cast<void**>(
            g_launcher_base + profile->runtime_pointer_offset));
    if (runtime == nullptr) {
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
    if (!IsExplicitlyEnabled() || !IsBusinessProbeEnabled() ||
            !IsArbiterBridgeEnabled() || !IsLauncherProcess() ||
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

    ZnSymbolResolver* resolver = g_api.newSymbolResolver(
            kBroadcastPrivatePath, nullptr);
    size_t receiver_size = 0u;
    size_t broadcast_size = 0u;
    void* receiver_on_receive = resolver == nullptr ? nullptr
            : g_api.symbolLookup(resolver, kBroadcastReceiverOnReceiveSymbol,
                                 false, &receiver_size);
    void* broadcast_intent_with_feature = resolver == nullptr ? nullptr
            : g_api.symbolLookup(resolver, kBroadcastIntentWithFeatureSymbol,
                                 false, &broadcast_size);
    if (resolver != nullptr) g_api.freeSymbolResolver(resolver);
    if (receiver_on_receive == nullptr || broadcast_intent_with_feature == nullptr ||
            receiver_size == 0u || broadcast_size == 0u) {
        // The broadcast dylib is not a guaranteed dependency at launcher
        // entry. HookDlopen/HookDlsym will retry after later native loading.
        AtomicStore(&g_native_receiver_state, uint32_t{98});
        AtomicStore(&g_arbiter_bridge_hook_state, uint32_t{2});
        return;
    }
    // Installation is process-local. A normal MiuiHome replacement forked by
    // the same injected spawner must install its own bridge; the atomic state
    // above already prevents duplicate mutation inside one process. Exact
    // process, build-ID, resolved-address, GOT-value, and code fingerprints
    // remain the fail-closed guards.
    if (!InstallBroadcastIntentWithFeatureGot(broadcast_intent_with_feature)) {
        AtomicStore(&g_arbiter_bridge_hook_state,
                AtomicLoad(&g_native_receiver_state) == uint32_t{102}
                        ? uint32_t{2} : uint32_t{4});
        return;
    }
    if (g_api.inlineHook(
                receiver_on_receive,
                reinterpret_cast<void*>(HookBroadcastReceiverOnReceive),
                &g_original_broadcast_receiver_on_receive) != ZN_SUCCESS ||
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

[[maybe_unused]] NativeResult HookBroadcastRegisterReceiver(void* runtime, void* receiver,
                                           void* callback_vtable, void* filter,
                                           void* permission, uint32_t flags) {
    BroadcastRegisterReceiverFn original =
            reinterpret_cast<BroadcastRegisterReceiverFn>(
                    AtomicLoad(&g_original_broadcast_register_receiver));
    if (original == nullptr) return NativeResult{};
    Log(ANDROID_LOG_INFO, "native register hook entered");
    NativeResult result = original(runtime, receiver, callback_vtable, filter,
                                   permission, flags);
    Log(ANDROID_LOG_INFO, "original native receiver registration returned");
    uint32_t expected = 0u;
    if (!IsNativeSuccess(result) || receiver == nullptr ||
            callback_vtable == nullptr ||
            !__atomic_compare_exchange_n(
                    &g_native_receiver_state, &expected, uint32_t{1}, false,
                    __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return result;
    }

    IntentFilterDefaultFn filter_default =
            ResolveLauncherSymbol<IntentFilterDefaultFn>(
                    "intent_fliter_default");
    IntentFilterAddActionFn add_action =
            ResolveLauncherSymbol<IntentFilterAddActionFn>(
                    "intent_fliter_add_action");
    void* module_filter = filter_default == nullptr ? nullptr : filter_default();
    Log(ANDROID_LOG_INFO, "separate native receiver filter constructed");
    RString action{};
    if (module_filter == nullptr || add_action == nullptr ||
            !MakeOwnedRString(kArbiterStateAction, &action)) {
        // A constructed filter cannot be safely recovered without its Rust
        // drop glue.  This experiment is process-lifetime-only, so retain it
        // on this failure path instead of guessing at its destructor ABI.
        AtomicStore(&g_native_receiver_state, uint32_t{4});
        Log(ANDROID_LOG_ERROR,
            "separate native receiver construction failed");
        return result;
    }

    // IntentFilter is built by a different Rust crate instance than
    // Intent/Bundle on launcher 4371 and therefore owns a distinct abi_stable
    // RString vtable.
    const auto* profile = CurrentLauncherProfile();
    if (profile == nullptr || profile->filter_rstring_vtable_offset == 0u) {
        AtomicStore(&g_native_receiver_state, uint32_t{4});
        return result;
    }
    action.vtable = g_launcher_base + profile->filter_rstring_vtable_offset;
    add_action(module_filter, &action);
    Log(ANDROID_LOG_INFO, "separate native receiver action added");

    // Launcher 4371 constructs this callback as a ref-counted 56-byte object,
    // retains one owner in its controller, then moves another owner into
    // Broadcast_register_receiver.  The original registration above consumed
    // its argument.  Clone the controller-owned reference exactly as the
    // launcher does at 0x609da4 before moving it into our second registration.
    const uint64_t old_count = __atomic_fetch_add(
            reinterpret_cast<uint64_t*>(receiver), uint64_t{1},
            __ATOMIC_ACQ_REL);
    if ((old_count & (uint64_t{1} << 63u)) != 0u) {
        __atomic_fetch_sub(reinterpret_cast<uint64_t*>(receiver), uint64_t{1},
                           __ATOMIC_ACQ_REL);
        AtomicStore(&g_native_receiver_state, uint32_t{4});
        Log(ANDROID_LOG_ERROR,
            "separate native receiver rejected invalid callback owner");
        return result;
    }

    Log(ANDROID_LOG_INFO, "registering separate native arbiter receiver");
    NativeResult module_result = original(runtime, receiver, callback_vtable,
                                          module_filter, permission, flags);
    if (!IsNativeSuccess(module_result)) {
        AtomicStore(&g_native_receiver_state, uint32_t{4});
        Log(ANDROID_LOG_ERROR,
            "separate native arbiter receiver registration failed");
        return result;
    }
    memcpy(&g_module_receiver_registration, &module_result,
           sizeof(g_module_receiver_registration));
    AtomicStore(&g_native_receiver_state, uint32_t{3});
    Log(ANDROID_LOG_INFO, "separate native arbiter receiver registered");
    return result;
}

bool PublishAcceptedDown(uint32_t edge) {
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
    __atomic_store_n(&g_pilfer_last_return_pc, return_pc, __ATOMIC_RELEASE);
    const uintptr_t offset_base = caller_base != 0u
            ? caller_base : reinterpret_cast<uintptr_t>(g_launcher_base);
    if (caller_profile == nullptr) caller_profile = CurrentLauncherProfile();
    const intptr_t return_offset = offset_base == 0u
            ? intptr_t{-1}
            : static_cast<intptr_t>(return_pc - offset_base);
    __atomic_store_n(&g_pilfer_last_return_offset, return_offset,
                     __ATOMIC_RELEASE);
    switch (return_offset) {
        case 0xbe8e98:
            __atomic_fetch_add(&g_pilfer_caller_be8e98_count, uint32_t{1},
                               __ATOMIC_RELAXED);
            break;
        case 0xbf07b4:
            __atomic_fetch_add(&g_pilfer_caller_bf07b4_count, uint32_t{1},
                               __ATOMIC_RELAXED);
            break;
        case 0xc11a7c:
            __atomic_fetch_add(&g_pilfer_caller_c11a7c_count, uint32_t{1},
                               __ATOMIC_RELAXED);
            break;
        case 0xc12e2c:
            __atomic_fetch_add(&g_pilfer_caller_c12e2c_count, uint32_t{1},
                               __ATOMIC_RELAXED);
            break;
        default:
            break;
    }

    const uint64_t observation_sequence = __atomic_add_fetch(
            &g_pilfer_observation_sequence, uint64_t{1}, __ATOMIC_RELAXED);
    PilferObservation& observation = g_pilfer_observations[
            (observation_sequence - 1u) % kPilferObservationCount];
    __atomic_store_n(&observation.sequence, uint64_t{0}, __ATOMIC_RELAXED);
    observation.return_pc = return_pc;
    observation.return_offset = return_offset;
    observation.monitor = reinterpret_cast<uintptr_t>(monitor);
    observation.motion_event = g_last_motion_event;
    observation.motion_sequence = g_last_motion_sequence;
    observation.down_time = g_pending_down.down_time;
    observation.tid = gettid();
    observation.action = g_last_motion_action;
    observation.action_masked_method =
            g_last_motion_used_masked_method ? 1u : 0u;
    observation.event_id = g_pending_down.event_id;
    observation.device_id = g_pending_down.device_id;
    observation.source = g_pending_down.source;
    observation.edge = g_pending_down.edge;
    observation.pending_down_valid = g_pending_down.valid ? 1u : 0u;
    __atomic_store_n(&observation.sequence, observation_sequence,
                     __ATOMIC_RELEASE);

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
    PendingDownIdentity candidate{};
    candidate.valid = get_id != nullptr && get_down_time != nullptr &&
            get_device_id != nullptr && get_source != nullptr &&
            get_raw_x != nullptr;
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
    const bool repeated_owned_down = g_systemui_owns_back_stream &&
            g_owned_back_stream.valid &&
            SameDownIdentity(candidate, g_owned_back_stream.down);
    if (!repeated_owned_down) {
        g_systemui_owns_back_stream = false;
        g_owned_back_stream.valid = false;
    }
    g_pending_down = candidate;
}

int32_t HookMotionGetActionForSlot(void* event, uint32_t slot_index,
                                   bool masked) {
    if (slot_index >= kLauncherInputSlotCount) return -1;
    // hyos_spawner can resolve app_entry_point before the final Launcher child
    // remaps the APK-backed libapp_launcher text.  The PLT hooks survive that
    // transition, while an inherited inline-hook state and trampoline can
    // outlive the actual text patch. Repair only the exact three-prologue loss;
    // the triggering stream remains native because its outer handler may
    // already be on the stack.
    RepairBusinessHooksIfRemapped(slot_index);
    LauncherInputHookSlot& slot = g_launcher_input_slots[slot_index];
    void* target = masked ? slot.original_action_masked
                          : slot.original_action;
    MotionEventIntFn original = reinterpret_cast<MotionEventIntFn>(
            AtomicLoad(&target));
    if (original == nullptr) return -1;
    const int32_t action = original(event);
    const uint64_t motion_sequence = masked
            ? __atomic_add_fetch(&g_motion_action_masked_call_count,
                                 uint32_t{1}, __ATOMIC_RELAXED)
            : __atomic_add_fetch(&g_motion_action_call_count, uint32_t{1},
                                 __ATOMIC_RELAXED);
    g_last_motion_event = reinterpret_cast<uintptr_t>(event);
    g_last_motion_action = action;
    g_last_motion_sequence = motion_sequence;
    g_last_motion_used_masked_method = masked;
    if ((action & 0xff) == 0) {
        __atomic_fetch_add(&g_motion_down_capture_count, uint32_t{1},
                           __ATOMIC_RELAXED);
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

[[maybe_unused]] uint8_t HookBackCallbackQuery(void* callback, void* callback_vtable,
                              const char* name, size_t name_length) {
    TryInstallArbiterBridge();
    BackCallbackQueryFn original = reinterpret_cast<BackCallbackQueryFn>(
            AtomicLoad(&g_original_back_callback_query));
    if (original == nullptr) return uint8_t{0};
    const uint8_t result =
            original(callback, callback_vtable, name, name_length);
    if (IsExactCallbackName(name, name_length,
                            "can_use_break_open_anim [default]")) {
        __atomic_fetch_add(&g_back_callback_query_count, uint32_t{1},
                           __ATOMIC_RELAXED);
        __atomic_store_n(&g_back_callback_query_last_result,
                         static_cast<uint32_t>(result), __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "back callback can_use_break_open_anim result=%u",
                            static_cast<unsigned int>(result));
    } else if (IsExactCallbackName(name, name_length,
                                   "is_back_gesture_anim_running")) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "back callback is_back_gesture_anim_running "
                            "result=%u",
                            static_cast<unsigned int>(result));
    }
    return result;
}

[[maybe_unused]] void HookBackSwipeStart(void* helper, void* state, uint32_t edge) {
    // This is Xiaomi's OPEN-interruption callback, not the ordinary gesture
    // monitor's accepted-input boundary. Keep it observable and fully native.
    // Only HookInputMonitorPilfer may transfer a physical stream to SystemUI.
    TryInstallArbiterBridge();
    __atomic_fetch_add(&g_back_swipe_start_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    Log(ANDROID_LOG_INFO, "back boundary swipe_start");
    BackSwipeStartFn original = reinterpret_cast<BackSwipeStartFn>(
            AtomicLoad(&g_original_back_swipe_start));
    if (original != nullptr) original(helper, state, edge);
}

[[maybe_unused]] void HookBackCancelled(void* helper) {
    __atomic_fetch_add(&g_back_cancelled_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    Log(ANDROID_LOG_INFO, "back boundary cancelled");
    if (g_systemui_owns_back_stream) {
        g_systemui_owns_back_stream = false;
        return;
    }
    BackCancelledFn original = reinterpret_cast<BackCancelledFn>(
            AtomicLoad(&g_original_back_cancelled));
    if (original != nullptr) original(helper);
}

[[maybe_unused]] void HookBackInvoke(void* helper, uint32_t raw_arg1) {
    __atomic_fetch_add(&g_back_invoke_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    __atomic_store_n(&g_back_invoke_last_arg1, raw_arg1, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "back boundary invoke raw_arg1=%u",
                        static_cast<unsigned int>(raw_arg1));
    if (g_systemui_owns_back_stream) {
        g_systemui_owns_back_stream = false;
        return;
    }
    BackInvokeFn original = reinterpret_cast<BackInvokeFn>(
            AtomicLoad(&g_original_back_invoke));
    if (original != nullptr) original(helper, raw_arg1);
}

[[maybe_unused]] uint8_t HookInterruptOpenPoll(void* future, void* context) {
    __atomic_fetch_add(&g_interrupt_open_poll_count, uint32_t{1},
                       __ATOMIC_RELAXED);
    InterruptOpenPollFn original = reinterpret_cast<InterruptOpenPollFn>(
            AtomicLoad(&g_original_interrupt_open_poll));
    if (original == nullptr) return uint8_t{0};
    const uint8_t result = original(future, context);
    __atomic_store_n(&g_interrupt_open_last_result,
                     static_cast<uint32_t>(result), __ATOMIC_RELEASE);
    return result;
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
    LauncherInputHookSlot& slot = g_launcher_input_slots[index];
    AtomicStore(&slot.base, reinterpret_cast<uintptr_t>(base));
    AtomicStore(&slot.profile, profile);
    AtomicStore(&slot.state, uint32_t{1});
    if (g_api.pltHook(base, "input_MotionEvent_getAction",
                      reinterpret_cast<void*>(kActionHooks[index]),
                      &slot.original_action) != ZN_SUCCESS ||
            slot.original_action == nullptr ||
            g_api.pltHook(base, "input_MotionEvent_getActionMasked",
                          reinterpret_cast<void*>(kActionMaskedHooks[index]),
                          &slot.original_action_masked) != ZN_SUCCESS ||
            slot.original_action_masked == nullptr ||
            g_api.pltHook(base, "input_InputMonitor_pilferPointers",
                          reinterpret_cast<void*>(
                                  MiuiHomeHyosInputMonitorPilferHook),
                          &slot.original_pilfer) != ZN_SUCCESS ||
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
    if (AtomicLoad(&g_motion_get_id) == nullptr ||
            AtomicLoad(&g_motion_get_down_time) == nullptr ||
            AtomicLoad(&g_motion_get_device_id) == nullptr ||
            AtomicLoad(&g_motion_get_source) == nullptr ||
            AtomicLoad(&g_motion_get_raw_x) == nullptr ||
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
    if (g_api.inlineHook(
                base + profile->side_handler_offset,
                reinterpret_cast<void*>(HookGestureStubBackHandler),
                &g_original_gesture_stub_back_handler) != ZN_SUCCESS ||
            AtomicLoad(&g_original_gesture_stub_back_handler) == nullptr) {
        AtomicStore(&g_business_hook_state, uint32_t{6});
        Log(ANDROID_LOG_ERROR, "GestureStub Back handler hook failed");
        return false;
    }
    if (profile->business_topology ==
            miui_home_profiles::BusinessHookTopology::kLegacyThreeStage) {
        // 4371 still has the two transparent diagnostic stages around the
        // side-only boundary. They never claim a stream themselves.
        if (g_api.inlineHook(
                    base + profile->touch_processor_offset,
                    reinterpret_cast<void*>(HookGestureBackTouchProcessor),
                    &g_original_gesture_back_touch_processor) != ZN_SUCCESS ||
                AtomicLoad(&g_original_gesture_back_touch_processor) == nullptr) {
            AtomicStore(&g_business_hook_state, uint32_t{6});
            Log(ANDROID_LOG_ERROR, "inner processor business hook failed");
            return false;
        }
        if (g_api.inlineHook(
                    base + profile->pointer_handler_offset,
                    reinterpret_cast<void*>(HookGestureStubPointerHandler),
                    &g_original_gesture_stub_pointer_handler) != ZN_SUCCESS ||
                AtomicLoad(&g_original_gesture_stub_pointer_handler) == nullptr) {
            AtomicStore(&g_business_hook_state, uint32_t{6});
            Log(ANDROID_LOG_ERROR, "accepted-DOWN outer handler hook failed");
            return false;
        }
    }
    AtomicStore(&g_business_hook_state, uint32_t{3});
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "%s profile %s with %s business hook",
            profile->id, repair ? "repaired" : "installed",
            profile->business_topology ==
                    miui_home_profiles::BusinessHookTopology::kLegacyThreeStage
                    ? "legacy diagnostics + side boundary"
                    : "side boundary only");
    if (IsArbiterBridgeEnabled()) TryInstallArbiterBridge();
    return true;
}

void InstallBusinessHooksForProfile(void* app_entry_point) {
    if (!IsBusinessProbeEnabled() || g_api.inlineHook == nullptr) return;
    uint32_t expected_state = 0u;
    if (!__atomic_compare_exchange_n(&g_business_hook_state, &expected_state,
                                     uint32_t{1}, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    InstallClaimedBusinessHooksForProfile(app_entry_point, false);
}

void RepairBusinessHooksIfRemapped(uint32_t slot_index) {
    if (!IsBusinessProbeEnabled() || g_api.inlineHook == nullptr ||
            g_api.inlineUnhook == nullptr ||
            slot_index >= kLauncherInputSlotCount ||
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
    const bool legacy = profile->business_topology ==
            miui_home_profiles::BusinessHookTopology::kLegacyThreeStage;
    if (!stub_back_handler_original &&
            (!legacy || (!outer_original && !inner_original))) {
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
    const int stub_back_handler_unhook = g_api.inlineUnhook(
            base + profile->side_handler_offset);
    const int inner_unhook = legacy ? g_api.inlineUnhook(
            base + profile->touch_processor_offset) : ZN_SUCCESS;
    const int outer_unhook = legacy ? g_api.inlineUnhook(
            base + profile->pointer_handler_offset) : ZN_SUCCESS;
    if (stub_back_handler_unhook != ZN_SUCCESS || inner_unhook != ZN_SUCCESS ||
            outer_unhook != ZN_SUCCESS) {
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
    const bool repaired = InstallClaimedBusinessHooksForProfile(
            base + profile->entry_offset, true);
    __atomic_store_n(&g_business_repair_stage,
                     repaired ? uint32_t{3} : uint32_t{5},
                     __ATOMIC_RELEASE);
    __atomic_fetch_add(repaired ? &g_business_repair_success_count
                               : &g_business_repair_failure_count,
                       uint32_t{1}, __ATOMIC_RELAXED);
}

void ObserveLauncherHandle(const char* filename, void* result) {
    if (!IsExplicitlyEnabled()) {
        AtomicStore(&g_launcher_handle, static_cast<void*>(nullptr));
        return;
    }
    if (result != nullptr && IsLauncherLibraryPath(filename) &&
            IsLauncherProcess()) {
        AtomicStore(&g_launcher_handle, result);
        Log(ANDROID_LOG_INFO, "matched MiuiHome libapp_launcher.so");
    }
}

void CaptureString(const char* value, volatile uint32_t* capture_index,
                   volatile char capture[kCaptureSlotCount]
                                        [kCaptureSlotSize]) {
    if (value == nullptr || !IsExplicitlyEnabled()) return;
    const uint32_t index = __atomic_fetch_add(
            capture_index, uint32_t{1}, __ATOMIC_RELAXED);
    volatile char* destination = capture[index % kCaptureSlotCount];
    uint32_t offset = 0u;
    while (offset + 1u < kCaptureSlotSize && value[offset] != '\0') {
        destination[offset] = value[offset];
        ++offset;
    }
    destination[offset] = '\0';
}

void ObserveLauncherSymbol(void* handle, const char* symbol, void* result) {
    if (IsExplicitlyEnabled() && result != nullptr &&
            handle == AtomicLoad(&g_launcher_handle) && symbol != nullptr &&
            strcmp(symbol, kLauncherEntrySymbol) == 0 &&
            __atomic_exchange_n(&g_entry_reported, uint32_t{1},
                                __ATOMIC_ACQ_REL) == 0u) {
        Log(ANDROID_LOG_INFO, "resolved MiuiHome app_entry_point");
    }
}

void* HookAppPublicDlsym(void* handle, const char* symbol) {
    DlsymFn original = reinterpret_cast<DlsymFn>(
            AtomicLoad(&g_original_app_public_dlsym));
    if (original == nullptr) return nullptr;
    void* result = original(handle, symbol);
    CaptureString(symbol, &g_dlsym_capture_index, g_dlsym_capture);
    if (IsExplicitlyEnabled() && IsLauncherProcess() && result != nullptr &&
            symbol != nullptr &&
            strcmp(symbol, kLauncherEntrySymbol) == 0) {
        AtomicStore(&g_launcher_handle, handle);
        if (__atomic_exchange_n(&g_entry_reported, uint32_t{1},
                                __ATOMIC_ACQ_REL) == 0u) {
            Log(ANDROID_LOG_INFO, "resolved MiuiHome app_entry_point");
        }
        if (!InstallLauncherInputHooksForProfile(result)) {
            Log(ANDROID_LOG_ERROR,
                "failed to install per-image launcher input hooks");
            return result;
        }
        InstallBusinessHooksForProfile(result);
    }
    return result;
}

void InstallAppPublicHooks() {
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(&g_app_public_hook_state, &expected,
                                     uint32_t{1}, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }
    ZnSymbolResolver* resolver =
            g_api.newSymbolResolver(kAppPublicPath, nullptr);
    if (resolver == nullptr) {
        AtomicStore(&g_app_public_hook_state, uint32_t{4});
        return;
    }
    void* base = g_api.getBaseAddress(resolver);
    g_api.freeSymbolResolver(resolver);
    if (base == nullptr) {
        AtomicStore(&g_app_public_hook_state, uint32_t{5});
        return;
    }
    if (g_api.pltHook(base, "dlsym", reinterpret_cast<void*>(HookAppPublicDlsym),
                      &g_original_app_public_dlsym) != ZN_SUCCESS ||
            AtomicLoad(&g_original_app_public_dlsym) == nullptr) {
        AtomicStore(&g_app_public_hook_state, uint32_t{6});
        return;
    }
    AtomicStore(&g_app_public_hook_state, uint32_t{3});
}

void* HookShellAndroidDlopenExt(const char* filename, int flags,
                               const android_dlextinfo* info) {
    AndroidDlopenExtFn original = reinterpret_cast<AndroidDlopenExtFn>(
            AtomicLoad(&g_original_shell_android_dlopen_ext));
    if (original == nullptr) return nullptr;
    void* result = original(filename, flags, info);
    CaptureString(filename, &g_android_dlopen_ext_capture_index,
                  g_android_dlopen_ext_capture);
    if (result != nullptr && filename != nullptr &&
            strcmp(filename, kAppPublicName) == 0) {
        InstallAppPublicHooks();
    }
    ObserveLauncherHandle(filename, result);
    TryInstallArbiterBridge();
    return result;
}

void* HookShellDlsym(void* handle, const char* symbol) {
    DlsymFn original = reinterpret_cast<DlsymFn>(
            AtomicLoad(&g_original_shell_dlsym));
    if (original == nullptr) return nullptr;
    void* result = original(handle, symbol);
    CaptureString(symbol, &g_dlsym_capture_index, g_dlsym_capture);
    ObserveLauncherSymbol(handle, symbol, result);
    TryInstallArbiterBridge();
    return result;
}

void InstallShellHooks() {
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(&g_shell_hook_state, &expected,
                                     uint32_t{1}, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return;
    }

    ZnSymbolResolver* resolver = g_api.newSymbolResolver(kShellPath, nullptr);
    if (resolver == nullptr) {
        AtomicStore(&g_shell_hook_state, uint32_t{4});
        return;
    }
    void* base = g_api.getBaseAddress(resolver);
    g_api.freeSymbolResolver(resolver);
    if (base == nullptr) {
        AtomicStore(&g_shell_hook_state, uint32_t{5});
        return;
    }
    if (g_api.pltHook(base, "android_dlopen_ext",
                      reinterpret_cast<void*>(HookShellAndroidDlopenExt),
                      &g_original_shell_android_dlopen_ext) != ZN_SUCCESS ||
            AtomicLoad(&g_original_shell_android_dlopen_ext) == nullptr) {
        AtomicStore(&g_shell_hook_state, uint32_t{6});
        return;
    }
    if (g_api.pltHook(base, "dlsym", reinterpret_cast<void*>(HookShellDlsym),
                      &g_original_shell_dlsym) != ZN_SUCCESS ||
            AtomicLoad(&g_original_shell_dlsym) == nullptr) {
        AtomicStore(&g_shell_hook_state, uint32_t{7});
        return;
    }
    AtomicStore(&g_shell_hook_state, uint32_t{3});
}

void* HookDlopen(const char* filename, int flags) {
    DlopenFn original = reinterpret_cast<DlopenFn>(
            AtomicLoad(&g_original_dlopen));
    if (original == nullptr) return nullptr;
    void* result = original(filename, flags);
    CaptureString(filename, &g_dlopen_capture_index, g_dlopen_capture);
    if (result != nullptr && filename != nullptr &&
            strcmp(filename, kShellName) == 0) {
        InstallShellHooks();
    }
    ObserveLauncherHandle(filename, result);
    return result;
}

void* HookDlsym(void* handle, const char* symbol) {
    DlsymFn original = reinterpret_cast<DlsymFn>(
            AtomicLoad(&g_original_dlsym));
    if (original == nullptr) return nullptr;
    void* result = original(handle, symbol);
    CaptureString(symbol, &g_dlsym_capture_index, g_dlsym_capture);
    ObserveLauncherSymbol(handle, symbol, result);
    return result;
}

void OnModuleLoaded(void*, const ZygiskNextAPI* api) {
    if (api == nullptr || api->pltHook == nullptr ||
            api->newSymbolResolver == nullptr ||
            api->freeSymbolResolver == nullptr ||
            api->getBaseAddress == nullptr || api->symbolLookup == nullptr) {
        return;
    }
    memcpy(&g_api, api, sizeof(g_api));

    if (!IsExplicitlyEnabled()) {
        Log(ANDROID_LOG_INFO, "disabled; no hook installed");
        return;
    }
    if (!ValidateSpawnerBuildId()) {
        Log(ANDROID_LOG_ERROR, "unsupported hyos_spawner Build ID");
        return;
    }

    ZnSymbolResolver* resolver =
            g_api.newSymbolResolver(kSpawnerPath, nullptr);
    if (resolver == nullptr) {
        Log(ANDROID_LOG_ERROR, "failed to create hyos_spawner resolver");
        return;
    }
    void* base = g_api.getBaseAddress(resolver);
    g_api.freeSymbolResolver(resolver);
    if (base == nullptr) {
        Log(ANDROID_LOG_ERROR, "failed to resolve hyos_spawner base");
        return;
    }

    if (g_api.pltHook(base, "dlopen", reinterpret_cast<void*>(HookDlopen),
                      &g_original_dlopen) != ZN_SUCCESS ||
            AtomicLoad(&g_original_dlopen) == nullptr) {
        Log(ANDROID_LOG_ERROR, "failed to hook hyos_spawner dlopen PLT");
        return;
    }

    if (g_api.pltHook(base, "dlsym", reinterpret_cast<void*>(HookDlsym),
                      &g_original_dlsym) != ZN_SUCCESS ||
            AtomicLoad(&g_original_dlsym) == nullptr) {
        Log(ANDROID_LOG_ERROR,
            "dlopen hook is transparent; failed to hook dlsym PLT");
        return;
    }
    Log(ANDROID_LOG_INFO, "hyos_spawner loader observation installed");
}

}  // namespace

extern "C" __attribute__((visibility("hidden")))
void MiuiHomeHyosInputMonitorPilferImpl(void* monitor, uintptr_t return_pc) {
    HandleInputMonitorPilfer(monitor, return_pc);
}

extern "C" __attribute__((visibility("default"), unused))
ZygiskNextModule zn_module = {
        ZYGISK_NEXT_API_VERSION_1,
        OnModuleLoaded,
};
