#include "dart_runtime_resolver.h"

#include <elf.h>
#include <string.h>

namespace miui_home_dart_profile {
namespace {

constexpr size_t kMaxLoadSegments = 16u;
constexpr uintptr_t kMaxImageSpan = 0x4000000u;
constexpr size_t kMaxOverviewCandidates = 16u;

struct LoadSegment {
    uintptr_t start;
    uintptr_t end;
    uint32_t flags;
};

struct ElfView {
    const uint8_t* base;
    LoadSegment loads[kMaxLoadSegments];
    size_t load_count;
    uintptr_t image_span;
};

struct DrawerCandidate {
    uintptr_t offset;
    uintptr_t all_apps_slot;
    uintptr_t home_slot;
    uintptr_t unbox_target;
};

struct OverviewCandidate {
    uintptr_t offset;
    uintptr_t state_slot;
    uintptr_t argument_pool_object;
    uintptr_t shared_pool_object;
    uintptr_t prepare_target;
    uintptr_t publish_target;
};

struct EditingCandidate {
    uintptr_t refresh_offset;
    uintptr_t query_offset;
    uintptr_t return_offset_a;
    uintptr_t return_offset_b;
};

bool AddOverflows(uintptr_t left, uintptr_t right) {
    return right > UINTPTR_MAX - left;
}

bool Contains(const ElfView& view, uintptr_t offset, size_t size,
              uint32_t required_flags, uint32_t forbidden_flags = 0u) {
    if (size == 0u || AddOverflows(offset, size)) return false;
    const uintptr_t end = offset + size;
    for (size_t index = 0u; index < view.load_count; ++index) {
        const LoadSegment& load = view.loads[index];
        if (offset >= load.start && end <= load.end &&
                (load.flags & required_flags) == required_flags &&
                (load.flags & forbidden_flags) == 0u) {
            return true;
        }
    }
    return false;
}

bool ParseElf(const uint8_t* base, ElfView* output) {
    if (base == nullptr || output == nullptr) return false;
    Elf64_Ehdr header{};
    memcpy(&header, base, sizeof(header));
    if (memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 ||
            header.e_ident[EI_CLASS] != ELFCLASS64 ||
            header.e_ident[EI_DATA] != ELFDATA2LSB ||
            header.e_type != ET_DYN || header.e_machine != EM_AARCH64 ||
            header.e_phentsize != sizeof(Elf64_Phdr) ||
            header.e_phnum == 0u || header.e_phnum > 64u ||
            header.e_phoff > 0x1000u ||
            AddOverflows(header.e_phoff,
                         static_cast<uintptr_t>(header.e_phnum) *
                                 sizeof(Elf64_Phdr)) ||
            header.e_phoff +
                    static_cast<uintptr_t>(header.e_phnum) *
                            sizeof(Elf64_Phdr) > 0x1000u) {
        return false;
    }
    ElfView view{};
    view.base = base;
    bool headers_covered = false;
    const uintptr_t headers_end = header.e_phoff +
            static_cast<uintptr_t>(header.e_phnum) * sizeof(Elf64_Phdr);
    for (size_t index = 0u; index < header.e_phnum; ++index) {
        Elf64_Phdr program_header{};
        memcpy(&program_header,
               base + header.e_phoff + index * sizeof(Elf64_Phdr),
               sizeof(program_header));
        if (program_header.p_type != PT_LOAD) continue;
        if (view.load_count >= kMaxLoadSegments ||
                program_header.p_memsz == 0u ||
                AddOverflows(program_header.p_vaddr,
                             program_header.p_memsz)) {
            return false;
        }
        const uintptr_t start = program_header.p_vaddr;
        const uintptr_t end = start + program_header.p_memsz;
        if (end > kMaxImageSpan) return false;
        view.loads[view.load_count++] = {
                start, end, program_header.p_flags};
        if (start == 0u && headers_end <= program_header.p_filesz) {
            headers_covered = true;
        }
        if (end > view.image_span) view.image_span = end;
    }
    if (!headers_covered || view.load_count == 0u || view.image_span == 0u) {
        return false;
    }
    *output = view;
    return true;
}

bool ReadInstruction(const ElfView& view, uintptr_t offset, uint32_t* value) {
    if (value == nullptr ||
            !Contains(view, offset, sizeof(*value), PF_R | PF_X)) {
        return false;
    }
    memcpy(value, view.base + offset, sizeof(*value));
    return true;
}

bool ReadInstructions(const ElfView& view, uintptr_t offset, uint32_t* values,
                      size_t count) {
    return values != nullptr && count != 0u &&
            Contains(view, offset, count * sizeof(*values), PF_R | PF_X) &&
            (memcpy(values, view.base + offset,
                    count * sizeof(*values)) != nullptr);
}

bool IsLoadX0FromX0(uint32_t instruction, uintptr_t* byte_offset) {
    if ((instruction & 0xffc003ffu) != 0xf9400000u) return false;
    if (byte_offset != nullptr) {
        *byte_offset = ((instruction >> 10u) & 0xfffu) * 8u;
    }
    return true;
}

bool IsBl(uint32_t instruction) {
    return (instruction & 0xfc000000u) == 0x94000000u;
}

bool DecodeBlTarget(uintptr_t instruction_offset, uint32_t instruction,
                    uintptr_t* target) {
    if (target == nullptr || !IsBl(instruction)) return false;
    int64_t immediate = static_cast<int64_t>(instruction & 0x03ffffffu);
    if ((immediate & (int64_t{1} << 25u)) != 0) {
        immediate -= int64_t{1} << 26u;
    }
    const int64_t signed_target = static_cast<int64_t>(instruction_offset) +
            immediate * 4;
    if (signed_target < 0) return false;
    *target = static_cast<uintptr_t>(signed_target);
    return true;
}

bool DecodeTestBranchTarget(uintptr_t instruction_offset,
                            uint32_t instruction, uintptr_t* target) {
    if (target == nullptr ||
            (instruction & 0xfff8001fu) != 0x36200002u) {
        return false;
    }
    int64_t immediate = static_cast<int64_t>((instruction >> 5u) & 0x3fffu);
    if ((immediate & (int64_t{1} << 13u)) != 0) {
        immediate -= int64_t{1} << 14u;
    }
    const int64_t signed_target = static_cast<int64_t>(instruction_offset) +
            immediate * 4;
    if (signed_target < 0) return false;
    *target = static_cast<uintptr_t>(signed_target);
    return true;
}

bool DecodePoolObject(uint32_t add, uint32_t load, uint32_t register_index,
                      uintptr_t* object_offset) {
    const uint32_t registers = (27u << 5u) | register_index;
    if (object_offset == nullptr ||
            (add & 0xff8003ffu) != (0x91000000u | registers) ||
            (load & 0xffc003ffu) !=
                    (0xf9400000u | (register_index << 5u) |
                     register_index)) {
        return false;
    }
    uintptr_t add_value = (add >> 10u) & 0xfffu;
    if ((add & 0x00400000u) != 0u) add_value <<= 12u;
    *object_offset = add_value +
            static_cast<uintptr_t>((load >> 10u) & 0xfffu) * 8u;
    return *object_offset < 0x1000000u;
}

bool MatchDrawer(const ElfView& view, uintptr_t offset,
                 DrawerCandidate* candidate) {
    uint32_t code[35]{};
    if (candidate == nullptr || !ReadInstructions(view, offset, code, 35u) ||
            code[0] != 0xa9bf79fdu || code[1] != 0xaa0f03fdu ||
            code[2] != 0xd10021efu || code[3] != 0xf81f83a2u ||
            code[4] != 0xf9403f40u || code[6] != 0xf9402370u ||
            code[7] != 0x6b10001fu || code[8] != 0x54000081u ||
            code[12] != 0xaa0003e1u || code[13] != 0xf85f83a0u ||
            code[14] != 0x6b01001fu || code[15] != 0x54000200u ||
            code[16] != 0xf9403f40u || code[18] != 0xf9402370u ||
            code[19] != 0x6b10001fu || code[20] != 0x54000081u ||
            code[24] != 0xf85f83a1u || code[25] != 0x6b00003fu ||
            code[26] != 0x910082d0u || code[27] != 0x9100c2d1u ||
            code[28] != 0x9a911202u || code[29] != 0xaa0203e0u ||
            code[30] != 0x14000002u || code[31] != 0x9100c2c0u ||
            code[32] != 0xaa1d03efu || code[33] != 0xa8c179fdu ||
            code[34] != 0xd65f03c0u) {
        return false;
    }
    uintptr_t all_apps = 0u;
    uintptr_t home = 0u;
    uintptr_t first_call = 0u;
    uintptr_t second_call = 0u;
    if (!IsLoadX0FromX0(code[5], &all_apps) ||
            !IsLoadX0FromX0(code[17], &home) ||
            all_apps != home + 0x10u || home == 0u ||
            !DecodeBlTarget(offset + 11u * 4u, code[11], &first_call) ||
            !DecodeBlTarget(offset + 23u * 4u, code[23], &second_call) ||
            first_call != second_call ||
            !Contains(view, first_call, sizeof(uint32_t), PF_R | PF_X)) {
        return false;
    }
    *candidate = {offset, all_apps, home, first_call};
    return true;
}

bool MatchTransition(const ElfView& view, uintptr_t offset,
                     uintptr_t unbox_target) {
    uint32_t code[12]{};
    uintptr_t branch_target = 0u;
    uintptr_t call_target = 0u;
    uintptr_t ignored_slot = 0u;
    return ReadInstructions(view, offset, code, 12u) &&
            code[0] == 0xa9bf79fdu && code[1] == 0xaa0f03fdu &&
            code[2] == 0xd10041efu && code[3] == 0xf81f83a1u &&
            DecodeTestBranchTarget(offset + 4u * 4u, code[4],
                                   &branch_target) &&
            branch_target >= offset + 0x100u &&
            branch_target <= offset + 0x300u && code[5] == 0xf9403f40u &&
            IsLoadX0FromX0(code[6], &ignored_slot) &&
            code[7] == 0xf9402370u && code[8] == 0x6b10001fu &&
            code[9] == 0x54000061u && code[10] == 0xf9403362u &&
            DecodeBlTarget(offset + 11u * 4u, code[11], &call_target) &&
            call_target == unbox_target;
}

constexpr uint32_t kDartRestoreFrame = 0xaa1d03efu;
constexpr uint32_t kDartPopFrame = 0xa8c179fdu;
constexpr uint32_t kDartReturn = 0xd65f03c0u;
constexpr uint32_t kDartReturnX22 = 0xaa1603e0u;

bool IsDartReturnEpilogue(const ElfView& view, uintptr_t offset,
                          uint32_t first) {
    uint32_t code[4]{};
    return ReadInstructions(view, offset, code, 4u) && code[0] == first &&
            code[1] == kDartRestoreFrame && code[2] == kDartPopFrame &&
            code[3] == kDartReturn;
}

bool FindUniqueDrawerCallerEpilogue(
        const ElfView& view, uintptr_t transition, uintptr_t* result) {
    if (result == nullptr) return false;
    constexpr uint32_t kCallerPrefix[] = {
            0xa9bf79fdu, 0xaa0f03fdu, 0xf9400fa0u, 0xb8417001u,
            0x8b1c8021u, 0xb840f020u, 0x8b1c8000u, 0xaa0003e1u,
            0xf9400ba2u};
    size_t direct_call_count = 0u;
    size_t valid_caller_count = 0u;
    uintptr_t match = 0u;
    for (size_t segment_index = 0u; segment_index < view.load_count;
         ++segment_index) {
        const LoadSegment& load = view.loads[segment_index];
        if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
        for (uintptr_t cursor = (load.start + 3u) & ~uintptr_t{3u};
             cursor < load.end && load.end - cursor >= sizeof(uint32_t);
             cursor += 4u) {
            uint32_t instruction = 0u;
            uintptr_t target = 0u;
            if (!ReadInstruction(view, cursor, &instruction) ||
                    !DecodeBlTarget(cursor, instruction, &target) ||
                    target != transition) {
                continue;
            }
            ++direct_call_count;
            if (cursor < sizeof(kCallerPrefix)) continue;
            const uintptr_t caller = cursor - sizeof(kCallerPrefix);
            uint32_t prefix[sizeof(kCallerPrefix) / sizeof(uint32_t)]{};
            if (!ReadInstructions(
                        view, caller, prefix,
                        sizeof(prefix) / sizeof(prefix[0])) ||
                    memcmp(prefix, kCallerPrefix, sizeof(prefix)) != 0 ||
                    !IsDartReturnEpilogue(
                            view, cursor + sizeof(uint32_t),
                            kDartReturnX22)) {
                continue;
            }
            ++valid_caller_count;
            match = cursor + sizeof(uint32_t);
        }
    }
    if (direct_call_count != 1u || valid_caller_count != 1u) return false;
    *result = match;
    return true;
}

bool MatchOverviewEnter(const ElfView& view, uintptr_t offset,
                        uintptr_t unbox_target,
                        OverviewCandidate* candidate) {
    uint32_t code[29]{};
    uintptr_t state_slot = 0u;
    uintptr_t unbox = 0u;
    uintptr_t argument = 0u;
    uintptr_t shared = 0u;
    uintptr_t prepare = 0u;
    uintptr_t publish = 0u;
    if (candidate == nullptr || !ReadInstructions(view, offset, code, 29u) ||
            code[0] != 0xa9bf79fdu || code[1] != 0xaa0f03fdu ||
            code[2] != 0xd10041efu || code[3] != 0xaa0103e2u ||
            code[4] != 0xf81f83a1u || code[5] != 0xf9403f40u ||
            !IsLoadX0FromX0(code[6], &state_slot) ||
            code[7] != 0xf9402370u || code[8] != 0x6b10001fu ||
            code[9] != 0x54000061u ||
            !DecodeBlTarget(offset + 11u * 4u, code[11], &unbox) ||
            unbox != unbox_target || code[14] != 0xf90001f0u ||
            code[15] != 0xf9402b64u ||
            !DecodeBlTarget(offset + 16u * 4u, code[16], &prepare) ||
            code[17] != 0xaa0003e1u || code[18] != 0xf85f83a2u ||
            !DecodePoolObject(code[19], code[20], 3u, &argument) ||
            !DecodePoolObject(code[21], code[22], 5u, &shared) ||
            argument + 8u != shared || code[23] != 0xf9438364u ||
            !DecodeBlTarget(offset + 24u * 4u, code[24], &publish) ||
            code[25] != 0xaa1603e0u || code[26] != 0xaa1d03efu ||
            code[27] != 0xa8c179fdu || code[28] != 0xd65f03c0u) {
        // Newer AOT snapshots retain the same state/animation call graph but
        // insert two pool loads and use relocated field offsets. Keep the
        // structural ABI checks while allowing those compiler-generated
        // immediates to move.
        uintptr_t modern_state = 0u;
        if (code[0] != 0xa9bf79fdu || code[1] != 0xaa0f03fdu ||
                code[2] != 0xd10041efu || code[3] != 0xaa0103e2u ||
                code[4] != 0xf81f83a1u || code[5] != 0xf9403f40u ||
                !IsLoadX0FromX0(code[6], &modern_state) ||
                code[7] != 0xf9402370u || code[8] != 0x6b10001fu ||
                code[9] != 0x54000061u || !DecodeBlTarget(
                        offset + 11u * 4u, code[11], &unbox) ||
                unbox != unbox_target || code[14] != 0xf90001f0u ||
                code[15] != 0xf9402b64u ||
                !DecodeBlTarget(offset + 16u * 4u, code[16], &prepare) ||
                code[17] != 0xaa0003e1u || code[18] != 0xf85f83a2u ||
                !DecodePoolObject(code[19], code[20], 3u, &argument) ||
                !DecodePoolObject(code[21], code[22], 5u, &shared) ||
                (code[23] & 0xffc003ffu) != 0xf9400364u ||
                !DecodeBlTarget(offset + 24u * 4u, code[24], &publish) ||
                code[25] != 0xaa1603e0u ||
                code[26] != 0xaa1d03efu || code[27] != 0xa8c179fdu ||
                code[28] != 0xd65f03c0u) {
            return false;
        }
        *candidate = {offset, modern_state, argument, shared, prepare,
                      publish};
        return true;
    }
    *candidate = {offset, state_slot, argument, shared, prepare, publish};
    return true;
}

bool MatchOverviewExit(const ElfView& view, uintptr_t offset,
                       uintptr_t unbox_target,
                       OverviewCandidate* candidate) {
    uint32_t code[44]{};
    uintptr_t state_slot = 0u;
    uintptr_t unbox = 0u;
    uintptr_t shared = 0u;
    uintptr_t prepare = 0u;
    uintptr_t publish = 0u;
    if (candidate == nullptr || !ReadInstructions(view, offset, code, 44u) ||
            code[0] != 0xa9bf79fdu || code[1] != 0xaa0f03fdu ||
            code[2] != 0xd10041efu || code[3] != 0xb8413080u ||
            code[4] != 0xb841f081u || code[5] != 0x8b1c8021u ||
            code[20] != 0xf9403f40u ||
            !IsLoadX0FromX0(code[21], &state_slot) ||
            code[22] != 0xf9402370u || code[23] != 0x6b10001fu ||
            code[24] != 0x54000061u ||
            !DecodeBlTarget(offset + 26u * 4u, code[26], &unbox) ||
            unbox != unbox_target || code[29] != 0xf90001f0u ||
            code[30] != 0xf9402b64u ||
            !DecodeBlTarget(offset + 31u * 4u, code[31], &prepare) ||
            code[32] != 0xaa0003e1u || code[33] != 0xf85f83a5u ||
            !DecodePoolObject(code[34], code[35], 2u, &shared) ||
            code[38] != 0xf9438364u ||
            !DecodeBlTarget(offset + 39u * 4u, code[39], &publish) ||
            code[40] != 0xaa1603e0u || code[41] != 0xaa1d03efu ||
            code[42] != 0xa8c179fdu || code[43] != 0xd65f03c0u) {
        // 6144's exit callback has a longer state-index preamble. Its
        // animation tail remains uniquely identifiable by the same unbox,
        // prepare/publish and pool-object relationships.
        uintptr_t modern_unbox = 0u;
        uintptr_t modern_prepare = 0u;
        uintptr_t modern_publish = 0u;
        uintptr_t modern_state = 0u;
        uintptr_t modern_shared = 0u;
        if (code[0] != 0xa9bf79fdu || code[1] != 0xaa0f03fdu ||
                code[2] != 0xd10041efu || code[3] != 0xb8413080u ||
                code[4] != 0xb841f081u || code[5] != 0x8b1c8021u ||
                !IsLoadX0FromX0(code[21], &modern_state) ||
                code[22] != 0xf9402370u || code[23] != 0x6b10001fu ||
                code[24] != 0x54000061u ||
                !DecodeBlTarget(offset + 26u * 4u, code[26], &modern_unbox) ||
                !(modern_unbox == unbox_target ||
                  (unbox_target >= 0x3cu &&
                   modern_unbox == unbox_target - 0x3cu)) ||
                code[29] != 0xf90001f0u ||
                code[30] != 0xf9402b64u ||
                !DecodeBlTarget(offset + 31u * 4u, code[31], &modern_prepare) ||
                code[32] != 0xaa0003e1u ||
                (code[33] & 0xffffffe0u) != 0xf85f83a0u ||
                !DecodePoolObject(code[34], code[35], 2u, &modern_shared) ||
                (code[38] & 0xffc003ffu) != 0xf9400364u ||
                !DecodeBlTarget(offset + 39u * 4u, code[39], &modern_publish) ||
                code[40] != 0xaa1603e0u || code[41] != 0xaa1d03efu ||
                code[42] != 0xa8c179fdu || code[43] != 0xd65f03c0u) {
            return false;
        }
        *candidate = {offset, modern_state, 0u, modern_shared,
                      modern_prepare, modern_publish};
        return true;
    }
    *candidate = {offset, state_slot, 0u, shared, prepare, publish};
    return true;
}

bool SameOverviewFamily(const OverviewCandidate& enter,
                        const OverviewCandidate& exit) {
    return enter.state_slot != 0u &&
            enter.state_slot == exit.state_slot &&
            enter.shared_pool_object != 0u &&
            enter.shared_pool_object == exit.shared_pool_object &&
            enter.prepare_target != 0u &&
            enter.prepare_target == exit.prepare_target &&
            enter.publish_target != 0u &&
            enter.publish_target == exit.publish_target;
}

bool SelectOverviewPair(const OverviewCandidate* enters, size_t enter_count,
                        const OverviewCandidate* exits, size_t exit_count,
                        OverviewCandidate* selected_enter,
                        OverviewCandidate* selected_exit) {
    if (enters == nullptr || exits == nullptr || selected_enter == nullptr ||
            selected_exit == nullptr || enter_count == 0u ||
            exit_count == 0u || enter_count > kMaxOverviewCandidates ||
            exit_count > kMaxOverviewCandidates) {
        return false;
    }
    if (enter_count == 1u && exit_count == 1u &&
            SameOverviewFamily(enters[0], exits[0])) {
        *selected_enter = enters[0];
        *selected_exit = exits[0];
        return true;
    }

    // Recent Dart AOT snapshots emit two otherwise identical enter callbacks
    // from adjacent pool objects. The callback for entering Overview uses the
    // upper object; its lower adjacent sibling is the complementary callback.
    // Require that pairing in addition to the unique exit callback's state,
    // shared object, and prepare/publish targets. This keeps selection dynamic
    // while failing closed for unrelated or multiply paired callback families.
    size_t pair_count = 0u;
    OverviewCandidate resolved_enter{};
    OverviewCandidate resolved_exit{};
    for (size_t exit_index = 0u; exit_index < exit_count; ++exit_index) {
        for (size_t enter_index = 0u; enter_index < enter_count;
             ++enter_index) {
            const OverviewCandidate& enter = enters[enter_index];
            const OverviewCandidate& exit = exits[exit_index];
            if (!SameOverviewFamily(enter, exit) ||
                    enter.argument_pool_object < sizeof(uint64_t)) {
                continue;
            }
            size_t lower_sibling_count = 0u;
            for (size_t sibling_index = 0u; sibling_index < enter_count;
                 ++sibling_index) {
                if (sibling_index == enter_index) continue;
                const OverviewCandidate& sibling = enters[sibling_index];
                if (SameOverviewFamily(sibling, exit) &&
                        sibling.argument_pool_object <=
                                UINTPTR_MAX - sizeof(uint64_t) &&
                        sibling.argument_pool_object + sizeof(uint64_t) ==
                                enter.argument_pool_object) {
                    ++lower_sibling_count;
                }
            }
            if (lower_sibling_count != 1u) continue;
            ++pair_count;
            resolved_enter = enter;
            resolved_exit = exit;
        }
    }
    if (pair_count != 1u) return false;
    *selected_enter = resolved_enter;
    *selected_exit = resolved_exit;
    return true;
}

bool MatchEditingQuery(const ElfView& view, uintptr_t offset) {
    uint32_t code[16]{};
    if (ReadInstructions(view, offset, code, 16u) &&
            code[0] == 0xa9bf79fdu && code[1] == 0xaa0f03fdu &&
            code[2] == 0xd10041efu && code[3] == 0xf81f83a1u &&
            code[4] == 0xf9403f40u && code[5] == 0xf95ae800u &&
            code[6] == 0x6b16001fu && code[7] == 0x540001a1u &&
            code[8] == 0xf9403f40u && code[9] == 0xf94d9400u &&
            code[10] == 0xf9402370u && code[11] == 0x6b10001fu &&
            code[12] == 0x54000061u &&
            (code[13] & 0xffc003ffu) == 0xf9400362u &&
            IsBl(code[14]) && code[15] == 0x91403b70u) {
        return true;
    }
    // 6144 changes only the two field-load immediates and the final add
    // immediate in this tiny query; the compare/branch/call ABI is stable.
    const bool modern = ReadInstructions(view, offset, code, 16u) &&
            code[0] == 0xa9bf79fdu && code[1] == 0xaa0f03fdu &&
            code[2] == 0xd10041efu && code[3] == 0xf81f83a1u &&
            (code[4] & 0xffc00000u) == 0xf9400000u &&
            code[6] == 0x6b16001fu && code[7] == 0x540001a1u &&
            code[8] == 0xf9403f40u &&
            (code[9] & 0xffc00000u) == 0xf9400000u &&
            code[10] == 0xf9402370u && code[11] == 0x6b10001fu &&
            code[12] == 0x54000061u && IsBl(code[14]);
    return modern;
}

bool MatchNotifyBackStatus(const ElfView& view, uintptr_t offset,
                           uintptr_t* refresh_offset) {
    uint32_t code[63]{};
    uintptr_t target = 0u;
    if (refresh_offset == nullptr ||
            !ReadInstructions(view, offset, code, 63u) ||
            code[0] != 0xa9bf79fdu || code[1] != 0xaa0f03fdu ||
            code[2] != 0xd100a1efu || code[3] != 0xf81f83a1u ||
            code[4] != 0xd28000a1u || !IsBl(code[5]) ||
            code[53] != 0xaa1603e0u || code[54] != 0xaa1d03efu ||
            code[55] != 0xa8c179fdu || code[56] != 0xd65f03c0u ||
            code[60] != 0xf85f83a1u ||
            !DecodeBlTarget(offset + 61u * 4u, code[61], &target) ||
            code[62] != 0xaa0003e1u ||
            !Contains(view, target, 2u * sizeof(uint32_t), PF_R | PF_X)) {
        return false;
    }
    uint32_t refresh_prefix[2]{};
    if (!ReadInstructions(view, target, refresh_prefix, 2u) ||
            refresh_prefix[0] != 0xa9bf79fdu ||
            refresh_prefix[1] != 0xaa0f03fdu) {
        return false;
    }
    *refresh_offset = target;
    return true;
}

bool MatchHomeSurfacePublication(const ElfView& view, uintptr_t notify,
                                 uintptr_t* published_epilogue) {
    // This native String equality fast path independently proves the layout
    // read by the observer: tagged OneByteString CID 94, Smi length at +7,
    // bytes at +15. A changed Dart ABI must not leave a guessed heap reader.
    constexpr uint32_t string_layout[] = {
            0xf94005e0u, 0xf94001e1u, 0xeb01001fu, 0x540002e0u,
            0x36000281u, 0xf85ff030u, 0xd34c7e10u, 0xf1017a1fu,
            0x54000281u, 0xf8407002u, 0xf8407030u, 0xeb10005fu,
            0x54000181u, 0x9341fc42u, 0x91001c42u, 0x9343fc42u,
            0x91003c00u, 0x91003c21u};
    uint32_t code[113]{};
    uintptr_t first_equals = 0u;
    uintptr_t second_equals = 0u;
    uintptr_t type_from = 0u;
    uintptr_t publish = 0u;
    if (published_epilogue == nullptr ||
            !ReadInstructions(view, notify, code, 113u)) return false;
    if (code[3] != 0xf81f83a1u || code[7] != 0xf81f03a0u ||
            code[8] != 0xb800f001u ||
            code[49] != 0xf85f03a2u || code[50] != 0xb8413040u ||
            code[51] != 0x8b1c8000u || code[52] != 0x362000a0u ||
            !IsDartReturnEpilogue(view, notify + 53u * 4u, kDartReturnX22) ||
            code[62] != 0xaa0003e1u || code[63] != 0xf85f03a0u ||
            code[64] != 0xb801b001u || code[65] != 0xaa0103e2u ||
            code[66] != 0xf85f83a1u ||
            !DecodeBlTarget(notify + 67u * 4u, code[67], &type_from) ||
            code[68] != 0xaa0003e1u || code[69] != 0xf85f03a2u ||
            code[70] != 0xb801f040u ||
            !DecodeBlTarget(notify + 80u * 4u, code[80], &first_equals) ||
            !DecodeBlTarget(notify + 88u * 4u, code[88], &second_equals) ||
            first_equals != second_equals ||
            !Contains(view, first_equals, sizeof(string_layout), PF_R | PF_X) ||
            memcmp(view.base + first_equals, string_layout, sizeof(string_layout)) != 0 ||
            code[103] != 0xf85f03a0u || code[104] != 0xb841b001u ||
            code[105] != 0x8b1c8021u || code[106] != 0xb841f002u ||
            code[107] != 0x8b1c8042u ||
            !DecodeBlTarget(notify + 108u * 4u, code[108], &publish) ||
            !Contains(view, type_from, 4u, PF_R | PF_X) ||
            !Contains(view, publish, 4u, PF_R | PF_X) ||
            !IsDartReturnEpilogue(view, notify + 109u * 4u, kDartReturnX22)) {
        return false;
    }
    *published_epilogue = notify + 109u * 4u;
    return true;
}

void Increment(uint32_t* value) {
    if (value != nullptr && *value != UINT32_MAX) ++*value;
}

}  // namespace

bool ResolveDartFeatureProfile(
        const uint8_t* base, const void* snapshot_instructions,
        const void* snapshot_build_id,
        const miui_home_profiles::LauncherProfile& launcher_profile,
        ResolutionStorage* storage, ResolutionDiagnostics* diagnostics) {
    if (storage == nullptr || diagnostics == nullptr) return false;
    *storage = {};
    *diagnostics = {};
    diagnostics->stage = ResolveStage::kParsingElf;
    ElfView view{};
    if (!ParseElf(base, &view) || snapshot_instructions == nullptr ||
            snapshot_build_id == nullptr) {
        diagnostics->stage = ResolveStage::kRejectedElf;
        return false;
    }
    const uintptr_t base_address = reinterpret_cast<uintptr_t>(base);
    const uintptr_t instructions_address =
            reinterpret_cast<uintptr_t>(snapshot_instructions);
    const uintptr_t build_id_address =
            reinterpret_cast<uintptr_t>(snapshot_build_id);
    if (instructions_address < base_address ||
            build_id_address < base_address) {
        diagnostics->stage = ResolveStage::kRejectedElf;
        return false;
    }
    const uintptr_t instructions_offset = instructions_address - base_address;
    const uintptr_t build_id_offset = build_id_address - base_address;
    uint32_t note[4]{};
    if ((instructions_offset & 3u) != 0u ||
            !Contains(view, instructions_offset, sizeof(uint32_t),
                      PF_R | PF_X) ||
            !Contains(view, build_id_offset, sizeof(storage->snapshot_build_id),
                      PF_R, PF_X) ||
            (memcpy(note, base + build_id_offset, sizeof(note)) == nullptr) ||
            note[0] != 4u || note[1] != 16u || note[2] != NT_GNU_BUILD_ID ||
            note[3] != 0x00554e47u) {
        diagnostics->stage = ResolveStage::kRejectedElf;
        return false;
    }

    diagnostics->stage = ResolveStage::kResolvingDrawer;
    DrawerCandidate drawer{};
    for (size_t segment_index = 0u; segment_index < view.load_count;
         ++segment_index) {
        const LoadSegment& load = view.loads[segment_index];
        if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
        uintptr_t offset = (load.start + 3u) & ~uintptr_t{3u};
        while (offset < load.end && load.end - offset >= 44u * 4u) {
            uint32_t first = 0u;
            if (!ReadInstruction(view, offset, &first)) break;
            if (first == 0xa9bf79fdu) {
                DrawerCandidate current_drawer{};
                if (MatchDrawer(view, offset, &current_drawer)) {
                    Increment(&diagnostics->drawer_candidate_count);
                    drawer = current_drawer;
                }
            }
            offset += 4u;
        }
    }
    if (diagnostics->drawer_candidate_count != 1u) {
        diagnostics->stage = ResolveStage::kRejectedDrawer;
        return false;
    }

    diagnostics->stage = ResolveStage::kResolvingTransition;
    uintptr_t transition_offset = 0u;
    for (size_t segment_index = 0u; segment_index < view.load_count;
         ++segment_index) {
        const LoadSegment& load = view.loads[segment_index];
        if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
        uintptr_t offset = (load.start + 3u) & ~uintptr_t{3u};
        while (offset < load.end && load.end - offset >= 44u * 4u) {
            if (MatchTransition(view, offset, drawer.unbox_target)) {
                Increment(&diagnostics->transition_candidate_count);
                transition_offset = offset;
            }
            offset += 4u;
        }
    }
    if (diagnostics->transition_candidate_count != 1u) {
        diagnostics->stage = ResolveStage::kRejectedTransition;
        return false;
    }

    diagnostics->stage = ResolveStage::kResolvingOverview;
    OverviewCandidate enter{};
    OverviewCandidate exit{};
    OverviewCandidate enter_candidates[kMaxOverviewCandidates]{};
    OverviewCandidate exit_candidates[kMaxOverviewCandidates]{};
    for (size_t segment_index = 0u; segment_index < view.load_count;
         ++segment_index) {
        const LoadSegment& load = view.loads[segment_index];
        if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
        uintptr_t offset = (load.start + 3u) & ~uintptr_t{3u};
        while (offset < load.end && load.end - offset >= 44u * 4u) {
            OverviewCandidate candidate{};
            if (MatchOverviewEnter(view, offset, drawer.unbox_target,
                                   &candidate)) {
                const uint32_t candidate_index =
                        diagnostics->overview_enter_candidate_count;
                if (candidate_index < kMaxOverviewCandidates) {
                    enter_candidates[candidate_index] = candidate;
                }
                Increment(&diagnostics->overview_enter_candidate_count);
            }
            candidate = {};
            if (MatchOverviewExit(view, offset, drawer.unbox_target,
                                  &candidate)) {
                const uint32_t candidate_index =
                        diagnostics->overview_exit_candidate_count;
                if (candidate_index < kMaxOverviewCandidates) {
                    exit_candidates[candidate_index] = candidate;
                }
                Increment(&diagnostics->overview_exit_candidate_count);
            }
            offset += 4u;
        }
    }
    if (!SelectOverviewPair(
                enter_candidates,
                diagnostics->overview_enter_candidate_count,
                exit_candidates,
                diagnostics->overview_exit_candidate_count,
                &enter, &exit)) {
        diagnostics->stage = ResolveStage::kRejectedOverview;
        return false;
    }

    diagnostics->stage = ResolveStage::kResolvingEditing;
    EditingCandidate editing{};
    uintptr_t editing_refresh = 0u;
    uintptr_t home_surface_notify = 0u;
    uintptr_t home_surface_published = 0u;
    uint32_t editing_notify_candidates = 0u;
    for (size_t segment_index = 0u; segment_index < view.load_count;
         ++segment_index) {
        const LoadSegment& load = view.loads[segment_index];
        if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
        uintptr_t offset = (load.start + 3u) & ~uintptr_t{3u};
        while (offset < load.end && load.end - offset >= 63u * 4u) {
            uintptr_t refresh = 0u;
            if (MatchNotifyBackStatus(view, offset, &refresh)) {
                Increment(&editing_notify_candidates);
                editing_refresh = refresh;
                home_surface_notify = offset;
            }
            offset += 4u;
        }
    }
    if (editing_notify_candidates == 1u &&
            MatchHomeSurfacePublication(view, home_surface_notify, &home_surface_published) &&
            editing_refresh <= UINTPTR_MAX - 0x500u) {
        const uintptr_t refresh_end = editing_refresh + 0x500u;
        for (size_t segment_index = 0u; segment_index < view.load_count;
             ++segment_index) {
            const LoadSegment& load = view.loads[segment_index];
            if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
            uintptr_t query = (load.start + 3u) & ~uintptr_t{3u};
            while (query < load.end && load.end - query >= 16u * 4u) {
                if (MatchEditingQuery(view, query)) {
                    uintptr_t returns[2]{};
                    uint32_t return_count = 0u;
                    for (uintptr_t caller = editing_refresh;
                         caller < refresh_end; caller += 4u) {
                        uint32_t instruction = 0u;
                        uintptr_t target = 0u;
                        if (!ReadInstruction(view, caller, &instruction)) break;
                        if (DecodeBlTarget(caller, instruction, &target) &&
                                target == query) {
                            if (return_count < 2u) {
                                returns[return_count] = caller + 4u;
                            }
                            Increment(&return_count);
                        }
                    }
                    if (return_count == 2u) {
                        Increment(&diagnostics->editing_candidate_count);
                        editing = {editing_refresh, query,
                                   returns[0], returns[1]};
                    }
                }
                query += 4u;
            }
        }
    }
    if (diagnostics->editing_candidate_count != 1u) {
        diagnostics->stage = ResolveStage::kRejectedEditing;
        return false;
    }

    uintptr_t drawer_epilogue = 0u;
    const uintptr_t enter_epilogue = enter.offset + 25u * 4u;
    const uintptr_t exit_epilogue = exit.offset + 40u * 4u;
    if (!FindUniqueDrawerCallerEpilogue(
                view, transition_offset, &drawer_epilogue) ||
            !IsDartReturnEpilogue(
                    view, enter_epilogue, kDartReturnX22) ||
            !IsDartReturnEpilogue(
                    view, exit_epilogue, kDartReturnX22)) {
        diagnostics->stage = ResolveStage::kRejectedEditing;
        return false;
    }

    storage->profile = launcher_profile;
    auto& profile = storage->profile;
    profile.dart_snapshot_instructions_offset = instructions_offset;
    profile.dart_snapshot_build_id_offset = build_id_offset;
    memcpy(storage->snapshot_build_id, base + build_id_offset,
           sizeof(storage->snapshot_build_id));
    profile.dart_snapshot_build_id = storage->snapshot_build_id;
    profile.dart_snapshot_build_id_size = sizeof(storage->snapshot_build_id);
    profile.dart_drawer_progress_end_offset = drawer.offset;
    memcpy(storage->drawer_progress_end_prologue, base + drawer.offset,
           sizeof(storage->drawer_progress_end_prologue));
    profile.dart_drawer_progress_end_prologue =
            storage->drawer_progress_end_prologue;
    profile.dart_drawer_progress_end_prologue_size =
            sizeof(storage->drawer_progress_end_prologue);
    profile.dart_drawer_transition_complete_offset = transition_offset;
    memcpy(storage->drawer_transition_complete_prologue,
           base + transition_offset,
           sizeof(storage->drawer_transition_complete_prologue));
    profile.dart_drawer_transition_complete_prologue =
            storage->drawer_transition_complete_prologue;
    profile.dart_drawer_transition_complete_prologue_size =
            sizeof(storage->drawer_transition_complete_prologue);
    profile.dart_all_apps_state_slot_offset = drawer.all_apps_slot;
    profile.dart_home_state_slot_offset = drawer.home_slot;
    profile.dart_overview_enter_offset = enter.offset;
    memcpy(storage->overview_enter_prologue, base + enter.offset,
           sizeof(storage->overview_enter_prologue));
    profile.dart_overview_enter_prologue = storage->overview_enter_prologue;
    profile.dart_overview_enter_prologue_size =
            sizeof(storage->overview_enter_prologue);
    profile.dart_overview_exit_offset = exit.offset;
    memcpy(storage->overview_exit_prologue, base + exit.offset,
           sizeof(storage->overview_exit_prologue));
    profile.dart_overview_exit_prologue = storage->overview_exit_prologue;
    profile.dart_overview_exit_prologue_size =
            sizeof(storage->overview_exit_prologue);
    profile.dart_editing_query_offset = editing.query_offset;
    memcpy(storage->editing_query_prologue, base + editing.query_offset,
           sizeof(storage->editing_query_prologue));
    profile.dart_editing_query_prologue = storage->editing_query_prologue;
    profile.dart_editing_query_prologue_size =
            sizeof(storage->editing_query_prologue);
    profile.dart_editing_query_return_offset_a = editing.return_offset_a;
    profile.dart_editing_query_return_offset_b = editing.return_offset_b;
    profile.dart_drawer_transition_epilogue_offset = drawer_epilogue;
    profile.dart_overview_enter_epilogue_offset = enter_epilogue;
    profile.dart_overview_exit_epilogue_offset = exit_epilogue;
    profile.dart_home_surface_notify_offset = home_surface_notify;
    profile.dart_home_surface_notify_code_size =
            home_surface_published + 16u - home_surface_notify;
    memcpy(storage->home_surface_notify_code, base + home_surface_notify,
           profile.dart_home_surface_notify_code_size);
    profile.dart_home_surface_notify_code = storage->home_surface_notify_code;
    profile.dart_home_surface_inactive_epilogue_offset = home_surface_notify + 53u * 4u;
    profile.dart_home_surface_published_epilogue_offset = home_surface_published;

    diagnostics->drawer_progress_end_offset = drawer.offset;
    diagnostics->drawer_transition_complete_offset = transition_offset;
    diagnostics->overview_enter_offset = enter.offset;
    diagnostics->overview_exit_offset = exit.offset;
    diagnostics->editing_refresh_offset = editing.refresh_offset;
    diagnostics->editing_query_offset = editing.query_offset;
    diagnostics->editing_query_return_offset_a = editing.return_offset_a;
    diagnostics->editing_query_return_offset_b = editing.return_offset_b;
    diagnostics->drawer_transition_epilogue_offset = drawer_epilogue;
    diagnostics->overview_enter_epilogue_offset = enter_epilogue;
    diagnostics->overview_exit_epilogue_offset = exit_epilogue;
    diagnostics->home_surface_notify_offset = home_surface_notify;
    diagnostics->home_surface_inactive_epilogue_offset =
            profile.dart_home_surface_inactive_epilogue_offset;
    diagnostics->home_surface_published_epilogue_offset = home_surface_published;
    diagnostics->all_apps_state_slot_offset = drawer.all_apps_slot;
    diagnostics->home_state_slot_offset = drawer.home_slot;
    diagnostics->stage = ResolveStage::kComplete;
    return true;
}

}  // namespace miui_home_dart_profile
