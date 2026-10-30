#include "runtime_profile_resolver.h"

#include <elf.h>
#include <string.h>

namespace miui_home_runtime_profile {
    namespace {

        constexpr size_t kMaxLoadSegments = 16u;
        constexpr uintptr_t kMaxImageSpan = 0x4000000u;
        constexpr size_t kEntryFingerprintSize = 48u;
        constexpr size_t kSidePrologueSize = 32u;
        constexpr size_t kContextualPrologueSize = 32u;
        constexpr uint32_t kMinimumRuntimeConfirmations = 1u;

        constexpr char kDynamicProfileId[] = "runtime-side-v1";
        constexpr char kDynamicVersionName[] = "runtime-resolved";

        constexpr char kMotionActionMasked[] = "input_MotionEvent_getActionMasked";
        constexpr char kMotionActionIndex[] = "input_MotionEvent_getActionIndex";
        constexpr char kMotionRawX[] = "input_MotionEvent_getRawX";
        constexpr char kMotionRawY[] = "input_MotionEvent_getRawY";
        constexpr char kRuntimeIncStrong[] = "Runtime_inc_strong";
        constexpr char kRuntimeGetBinder[] =
                "Runtime_get_application_thread_binder";
        constexpr char kRuntimeDecStrong[] = "Runtime_dec_strong";
        constexpr char kBundleDefault[] = "Bundle_default";
        constexpr char kIntentSetAction[] = "Intent_set_action";
        constexpr char kBundleDrop[] = "Bundle_drop";
        constexpr char kBundleGetString[] = "Bundle_get_string";
        constexpr char kBundleGetBoolean[] = "Bundle_get_boolean";
        constexpr char kPackageManagerDefault[] = "PackageManager_default";
        constexpr char kPackageManagerHasSystemFeature[] =
                "PackageManager_has_system_feature";
        constexpr char kMalloc[] = "malloc";
        constexpr char kMemcpy[] = "memcpy";

        constexpr uint32_t kSideFamilyPrologue[] = {
                0xd10603ffu, 0xfd008beau, 0x6d11a3e9u, 0xa912fbfdu,
                0xf9009ffcu, 0xa91467fau, 0xa9155ff8u, 0xa91657f6u,
        };
        constexpr uint32_t kSide6144Prologue[] = {
                0x6db82bebu, 0x6d0123e9u, 0xa9027bfdu, 0xa9036ffcu,
                0xa90467fau, 0xa9055ff8u, 0xa90657f6u, 0xa9074ff4u,
        };

        constexpr uint32_t kEntryFamilyPrefix[] = {
                0xd104c3ffu, 0xa90d7bfdu, 0xa90e6ffcu, 0xa90f67fau,
                0xa9105ff8u, 0xa91157f6u, 0xa9124ff4u, 0x910343fdu,
                0xaa0803f9u,
        };

        constexpr uint32_t kContextualSupportPrologue[] = {
                0xd10383ffu, 0xa90c7bfdu, 0xa90d4ff4u, 0x910303fdu,
        };

        constexpr uint32_t kContextualInvokePrologue[] = {
                0xd10543ffu, 0xa9117bfdu, 0xa9125ffcu, 0xa91357f6u,
                0xa9144ff4u, 0x910443fdu, 0x9100c3f6u, 0xb90007e0u,
        };

        constexpr uint32_t kContextualLongPressPrologue[] = {
                0xd102c3ffu, 0xa9097bfdu, 0xa90a4ff4u, 0x910243fdu,
        };

// Android 17 / 8.01.02.6144 moved the contextual-search Rust graph to a
// smaller support helper and changed the Fn closure frame. Keep these
// fingerprints structural: the resolver must prove the two exact feature
// strings, the helper's PackageManager call, and the completion release store.
        constexpr uint32_t kContextualSupport6144Prologue[] = {
                0xd10243ffu, 0xa9067bfdu, 0xf9003bf5u, 0xa9084ff4u,
                0x910183fdu,
        };
        constexpr uint32_t kContextualFeatureHelper6144Prologue[] = {
                0xd10203ffu, 0xa9067bfdu, 0xa9074ff4u, 0x910183fdu,
                0xaa0003f3u, 0x9100c3e8u,
        };
        constexpr uint32_t kContextualInvoke6144Prologue[] = {
                0xd10403ffu, 0xa90c7bfdu, 0xf9006bf7u, 0xa90e57f6u,
                0xa90f4ff4u, 0x910303fdu, 0x2a0003f3u,
        };
        constexpr uint32_t kContextualLongPress6144Prologue[] = {
                0xd10183ffu, 0xa9047bfdu, 0xa9054ff4u, 0x910103fdu,
        };

        struct LoadSegment {
            uintptr_t start;
            uintptr_t end;
            uint32_t flags;
        };

        struct ElfView {
            const uint8_t *base;
            LoadSegment loads[kMaxLoadSegments];
            size_t load_count;
            uintptr_t image_span;
            uintptr_t string_table;
            size_t string_table_size;
            uintptr_t symbol_table;
            size_t symbol_entry_size;
            uintptr_t jump_relocations;
            size_t jump_relocations_size;
            size_t relocation_entry_size;
        };

        struct RequiredImports {
            uintptr_t motion_action_masked;
            uintptr_t motion_action_index;
            uintptr_t motion_raw_x;
            uintptr_t motion_raw_y;
            uintptr_t runtime_inc_strong;
            uintptr_t runtime_get_binder;
            uintptr_t runtime_dec_strong;
            uintptr_t bundle_default;
            uintptr_t bundle_drop;
            uintptr_t bundle_get_string;
            uintptr_t bundle_get_boolean;
            uintptr_t package_manager_default;
            uintptr_t package_manager_has_system_feature;
            uintptr_t malloc_address;
            uintptr_t memcpy_address;
        };

        bool AddOverflows(uintptr_t left, uintptr_t right) {
            return right > UINTPTR_MAX - left;
        }

        bool Contains(const ElfView &view, uintptr_t offset, size_t size,
                uint32_t required_flags, uint32_t forbidden_flags = 0u) {
            if (size == 0u || AddOverflows(offset, size)) return false;
            const uintptr_t end = offset + size;
            for (size_t index = 0u; index < view.load_count; ++index) {
                const LoadSegment &load = view.loads[index];
                if (offset >= load.start && end <= load.end &&
                        (load.flags & required_flags) == required_flags &&
                        (load.flags & forbidden_flags) == 0u) {
                    return true;
                }
            }
            return false;
        }

        bool ReadInstruction(const ElfView &view, uintptr_t offset,
                uint32_t *instruction) {
            if (instruction == nullptr ||
                    !Contains(view, offset, sizeof(*instruction), PF_R | PF_X)) {
                return false;
            }
            memcpy(instruction, view.base + offset, sizeof(*instruction));
            return true;
        }

        bool NormalizeDynamicPointer(const ElfView &view, Elf64_Addr value,
                uintptr_t *offset) {
            if (offset == nullptr) return false;
            const uintptr_t raw = static_cast<uintptr_t>(value);
            const uintptr_t base_address = reinterpret_cast<uintptr_t>(view.base);
            if (raw >= base_address && raw - base_address < view.image_span) {
                *offset = raw - base_address;
                return true;
            }
            if (raw < view.image_span) {
                *offset = raw;
                return true;
            }
            return false;
        }

        bool ParseElf(const uint8_t *base, ElfView *output) {
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
            const auto *program_headers = reinterpret_cast<const Elf64_Phdr *>(
                    base + header.e_phoff);
            uintptr_t dynamic_offset = 0u;
            size_t dynamic_size = 0u;
            bool headers_covered = false;
            const uintptr_t headers_end = header.e_phoff +
                    static_cast<uintptr_t>(header.e_phnum) * sizeof(Elf64_Phdr);
            for (size_t index = 0u; index < header.e_phnum; ++index) {
                Elf64_Phdr program_header{};
                memcpy(&program_header, program_headers + index,
                        sizeof(program_header));
                if (program_header.p_type == PT_LOAD) {
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
                } else if (program_header.p_type == PT_DYNAMIC) {
                    if (program_header.p_memsz == 0u ||
                            program_header.p_memsz > SIZE_MAX) {
                        return false;
                    }
                    dynamic_offset = program_header.p_vaddr;
                    dynamic_size = static_cast<size_t>(program_header.p_memsz);
                }
            }
            if (!headers_covered || view.load_count == 0u ||
                    view.image_span == 0u || dynamic_size < sizeof(Elf64_Dyn) ||
                    !Contains(view, dynamic_offset, dynamic_size, PF_R)) {
                return false;
            }

            Elf64_Addr string_table = 0u;
            Elf64_Addr symbol_table = 0u;
            Elf64_Addr jump_relocations = 0u;
            size_t string_table_size = 0u;
            size_t symbol_entry_size = 0u;
            size_t jump_relocations_size = 0u;
            size_t relocation_entry_size = sizeof(Elf64_Rela);
            Elf64_Sxword relocation_kind = 0;
            bool terminated = false;
            const size_t dynamic_count = dynamic_size / sizeof(Elf64_Dyn);
            for (size_t index = 0u; index < dynamic_count; ++index) {
                Elf64_Dyn entry{};
                memcpy(&entry, view.base + dynamic_offset +
                        index * sizeof(Elf64_Dyn), sizeof(entry));
                if (entry.d_tag == DT_NULL) {
                    terminated = true;
                    break;
                }
                switch (entry.d_tag) {
                    case DT_STRTAB:
                        string_table = entry.d_un.d_ptr;
                        break;
                    case DT_STRSZ:
                        string_table_size = entry.d_un.d_val;
                        break;
                    case DT_SYMTAB:
                        symbol_table = entry.d_un.d_ptr;
                        break;
                    case DT_SYMENT:
                        symbol_entry_size = entry.d_un.d_val;
                        break;
                    case DT_JMPREL:
                        jump_relocations = entry.d_un.d_ptr;
                        break;
                    case DT_PLTRELSZ:
                        jump_relocations_size = entry.d_un.d_val;
                        break;
                    case DT_PLTREL:
                        relocation_kind = entry.d_un.d_val;
                        break;
                    case DT_RELAENT:
                        relocation_entry_size = entry.d_un.d_val;
                        break;
                    default:
                        break;
                }
            }
            if (!terminated || string_table_size == 0u ||
                    symbol_entry_size != sizeof(Elf64_Sym) ||
                    relocation_kind != DT_RELA ||
                    relocation_entry_size != sizeof(Elf64_Rela) ||
                    jump_relocations_size == 0u ||
                    jump_relocations_size % sizeof(Elf64_Rela) != 0u ||
                    !NormalizeDynamicPointer(view, string_table,
                            &view.string_table) ||
                    !NormalizeDynamicPointer(view, symbol_table,
                            &view.symbol_table) ||
                    !NormalizeDynamicPointer(view, jump_relocations,
                            &view.jump_relocations)) {
                return false;
            }
            view.string_table_size = string_table_size;
            view.symbol_entry_size = symbol_entry_size;
            view.jump_relocations_size = jump_relocations_size;
            view.relocation_entry_size = relocation_entry_size;
            if (!Contains(view, view.string_table, view.string_table_size, PF_R) ||
                    !Contains(view, view.symbol_table, sizeof(Elf64_Sym), PF_R) ||
                    !Contains(view, view.jump_relocations,
                            view.jump_relocations_size, PF_R)) {
                return false;
            }
            *output = view;
            return true;
        }

        bool BoundedStringEquals(const char *value, size_t available,
                const char *expected) {
            if (value == nullptr || expected == nullptr) return false;
            size_t index = 0u;
            while (expected[index] != '\0') {
                if (index >= available || value[index] != expected[index]) {
                    return false;
                }
                ++index;
            }
            return index < available && value[index] == '\0';
        }

        bool FindImportGot(const ElfView &view, const char *expected,
                uintptr_t *result) {
            if (result == nullptr) return false;
            uintptr_t matched = 0u;
            uint32_t match_count = 0u;
            const size_t count = view.jump_relocations_size / sizeof(Elf64_Rela);
            for (size_t index = 0u; index < count; ++index) {
                Elf64_Rela relocation{};
                memcpy(&relocation, view.base + view.jump_relocations +
                        index * sizeof(Elf64_Rela), sizeof(relocation));
                const size_t symbol_index = ELF64_R_SYM(relocation.r_info);
                if (symbol_index > (SIZE_MAX - view.symbol_table) /
                        sizeof(Elf64_Sym)) {
                    return false;
                }
                const uintptr_t symbol_offset = view.symbol_table +
                        symbol_index * sizeof(Elf64_Sym);
                if (!Contains(view, symbol_offset, sizeof(Elf64_Sym), PF_R)) {
                    return false;
                }
                Elf64_Sym symbol{};
                memcpy(&symbol, view.base + symbol_offset, sizeof(symbol));
                if (symbol.st_name >= view.string_table_size) return false;
                const char *name = reinterpret_cast<const char *>(
                        view.base + view.string_table + symbol.st_name);
                if (!BoundedStringEquals(name,
                        view.string_table_size - symbol.st_name,
                        expected)) {
                    continue;
                }
                uintptr_t got_offset = 0u;
                if (!NormalizeDynamicPointer(view, relocation.r_offset,
                        &got_offset) ||
                        !Contains(view, got_offset, sizeof(uintptr_t), PF_R)) {
                    return false;
                }
                matched = got_offset;
                ++match_count;
            }
            if (match_count != 1u) return false;
            *result = matched;
            return true;
        }

        bool ResolveImports(const ElfView &view, RequiredImports *imports) {
            return imports != nullptr &&
                    FindImportGot(view, kMotionActionMasked,
                            &imports->motion_action_masked) &&
                    FindImportGot(view, kMotionActionIndex,
                            &imports->motion_action_index) &&
                    FindImportGot(view, kMotionRawX, &imports->motion_raw_x) &&
                    FindImportGot(view, kMotionRawY, &imports->motion_raw_y) &&
                    FindImportGot(view, kRuntimeIncStrong,
                            &imports->runtime_inc_strong) &&
                    FindImportGot(view, kRuntimeGetBinder,
                            &imports->runtime_get_binder) &&
                    FindImportGot(view, kRuntimeDecStrong,
                            &imports->runtime_dec_strong) &&
                    FindImportGot(view, kBundleDefault,
                            &imports->bundle_default) &&
                    FindImportGot(view, kMalloc, &imports->malloc_address) &&
                    FindImportGot(view, kMemcpy, &imports->memcpy_address);
        }

        bool ResolveContextualImports(const ElfView &view, RequiredImports *imports) {
            return imports != nullptr &&
                    FindImportGot(view, kBundleDrop, &imports->bundle_drop) &&
                    FindImportGot(view, kPackageManagerDefault,
                            &imports->package_manager_default) &&
                    FindImportGot(view, kPackageManagerHasSystemFeature,
                            &imports->package_manager_has_system_feature);
        }

        bool ResolveXiaoAiImports(const ElfView &view, RequiredImports *imports) {
            return imports != nullptr &&
                    FindImportGot(view, kBundleGetString,
                            &imports->bundle_get_string) &&
                    FindImportGot(view, kBundleGetBoolean,
                            &imports->bundle_get_boolean);
        }

        bool DecodeAdrp(uint32_t instruction, uintptr_t pc, uint32_t reg,
                uintptr_t *target_page) {
            if (target_page == nullptr || reg > 31u ||
                    (instruction & 0x9f00001fu) != (0x90000000u | reg)) {
                return false;
            }
            int64_t immediate = static_cast<int64_t>(
                    ((instruction >> 29u) & 0x3u) |
                            (((instruction >> 5u) & 0x7ffffu) << 2u));
            if ((immediate & (int64_t{1} << 20u)) != 0) {
                immediate -= int64_t{1} << 21u;
            }
            const int64_t page = static_cast<int64_t>(pc & ~uintptr_t{0xfffu});
            const int64_t target = page + immediate * int64_t{4096};
            if (target < 0 || static_cast<uint64_t>(target) > UINTPTR_MAX) {
                return false;
            }
            *target_page = static_cast<uintptr_t>(target);
            return true;
        }

        bool DecodeAddImmediate(uint32_t instruction, uint32_t destination,
                uint32_t source, uintptr_t *immediate) {
            if (immediate == nullptr ||
                    (instruction & 0xffc003ffu) !=
                            (0x91000000u | (source << 5u) | destination)) {
                return false;
            }
            *immediate = (instruction >> 10u) & 0xfffu;
            return true;
        }

        bool DecodeLdr64Immediate(uint32_t instruction, uint32_t destination,
                uint32_t source, uintptr_t *immediate) {
            if (immediate == nullptr ||
                    (instruction & 0xffc003ffu) !=
                            (0xf9400000u | (source << 5u) | destination)) {
                return false;
            }
            *immediate = ((instruction >> 10u) & 0xfffu) * sizeof(uintptr_t);
            return true;
        }

        bool DecodeAddressPair(const ElfView &view, uintptr_t instruction_offset,
                uint32_t reg, uintptr_t *target) {
            uint32_t adrp = 0u;
            uint32_t add = 0u;
            uintptr_t page = 0u;
            uintptr_t immediate = 0u;
            return ReadInstruction(view, instruction_offset, &adrp) &&
                    ReadInstruction(view, instruction_offset + 4u, &add) &&
                    DecodeAdrp(adrp, instruction_offset, reg, &page) &&
                    DecodeAddImmediate(add, reg, reg, &immediate) &&
                    !AddOverflows(page, immediate) &&
                    ((*target = page + immediate), true);
        }

        bool DecodeBlTarget(const ElfView &view, uintptr_t instruction_offset,
                uintptr_t *target) {
            uint32_t instruction = 0u;
            if (target == nullptr ||
                    !ReadInstruction(view, instruction_offset, &instruction) ||
                    (instruction & 0xfc000000u) != 0x94000000u) {
                return false;
            }
            int64_t immediate = instruction & 0x03ffffffu;
            if ((immediate & (int64_t{1} << 25u)) != 0) {
                immediate -= int64_t{1} << 26u;
            }
            const int64_t destination = static_cast<int64_t>(instruction_offset) +
                    immediate * int64_t{4};
            if (destination < 0 || static_cast<uint64_t>(destination) > UINTPTR_MAX ||
                    !Contains(view, static_cast<uintptr_t>(destination), 16u,
                            PF_R | PF_X)) {
                return false;
            }
            *target = static_cast<uintptr_t>(destination);
            return true;
        }

        bool DecodePltGot(const ElfView &view, uintptr_t plt_offset,
                uintptr_t *got_offset) {
            uint32_t adrp = 0u;
            uint32_t ldr = 0u;
            uint32_t add = 0u;
            uint32_t branch = 0u;
            uintptr_t page = 0u;
            uintptr_t load_immediate = 0u;
            uintptr_t add_immediate = 0u;
            return got_offset != nullptr &&
                    ReadInstruction(view, plt_offset, &adrp) &&
                    ReadInstruction(view, plt_offset + 4u, &ldr) &&
                    ReadInstruction(view, plt_offset + 8u, &add) &&
                    ReadInstruction(view, plt_offset + 12u, &branch) &&
                    DecodeAdrp(adrp, plt_offset, 16u, &page) &&
                    DecodeLdr64Immediate(ldr, 17u, 16u, &load_immediate) &&
                    DecodeAddImmediate(add, 16u, 16u, &add_immediate) &&
                    load_immediate == add_immediate &&
                    branch == 0xd61f0220u &&
                    !AddOverflows(page, load_immediate) &&
                    ((*got_offset = page + load_immediate), true);
        }

        bool CallTargetsImport(const ElfView &view, uintptr_t call_offset,
                uintptr_t expected_got) {
            uintptr_t plt = 0u;
            uintptr_t got = 0u;
            return DecodeBlTarget(view, call_offset, &plt) &&
                    DecodePltGot(view, plt, &got) && got == expected_got;
        }

        bool MatchesWords(const ElfView &view, uintptr_t offset,
                const uint32_t *words, size_t count) {
            if (words == nullptr || count == 0u ||
                    !Contains(view, offset, count * sizeof(uint32_t), PF_R | PF_X)) {
                return false;
            }
            return memcmp(view.base + offset, words,
                    count * sizeof(uint32_t)) == 0;
        }

        bool IsSideCandidate(const ElfView &view, const RequiredImports &imports,
                uintptr_t offset, uintptr_t *edge_offset) {
            uint32_t instruction = 0u;
            const bool legacy_shape = MatchesWords(
                    view, offset, kSideFamilyPrologue,
                    sizeof(kSideFamilyPrologue) / sizeof(kSideFamilyPrologue[0]));
            const bool modern_shape = MatchesWords(
                    view, offset, kSide6144Prologue,
                    sizeof(kSide6144Prologue) / sizeof(kSide6144Prologue[0]));
            if (!legacy_shape && !modern_shape) return false;
            uintptr_t discovered_edge = 0u;
            bool edge_seen = false;
            // The 8.01.02.6144 build changed the native wrapper's save-set and moved
            // the MotionEvent calls, while retaining the same ordered action/index/X/
            // index/Y query graph. Find that graph relative to the function entry
            // instead of relying on the old fixed offsets.
            uintptr_t calls[5]{};
            size_t call_index = 0u;
            for (uintptr_t cursor = 0x20u; cursor <= 0x1c0u; cursor += 4u) {
                if (!ReadInstruction(view, offset + cursor, &instruction)) continue;
                if (!edge_seen && (instruction & 0xffc003ffu) == 0x39400014u) {
                    const uintptr_t edge = (instruction >> 10u) & 0xfffu;
                    if (edge >= 0x40u && edge <= 0x400u && (edge & 0x3u) == 0u) {
                        discovered_edge = edge;
                        edge_seen = true;
                    }
                }
                if (call_index >= 5u ||
                        (instruction & 0xfc000000u) != 0x94000000u) {
                    continue;
                }
                const uintptr_t call = offset + cursor;
                const uintptr_t import = call_index == 0u
                        ? imports.motion_action_masked
                        : call_index == 1u || call_index == 3u
                                ? imports.motion_action_index
                                : call_index == 2u ? imports.motion_raw_x
                                        : imports.motion_raw_y;
                if (CallTargetsImport(view, call, import)) calls[call_index++] = call;
            }
            if (!edge_seen || call_index != 5u) return false;
            if (edge_offset != nullptr) *edge_offset = discovered_edge;
            return calls[0] < calls[1] && calls[1] < calls[2] &&
                    calls[2] < calls[3] && calls[3] < calls[4];
        }

        bool ResolveSide(const ElfView &view, const RequiredImports &imports,
                uintptr_t *side_offset, uintptr_t *edge_offset,
                uint32_t *candidate_count) {
            uintptr_t matched_side = 0u;
            uintptr_t matched_edge = 0u;
            uint32_t matches = 0u;
            for (size_t segment_index = 0u;
                 segment_index < view.load_count; ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 0x108u) {
                    continue;
                }
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t offset = start; offset <= load.end - 0x108u;
                     offset += 4u) {
                    uintptr_t edge = 0u;
                    if (!IsSideCandidate(view, imports, offset, &edge)) continue;
                    matched_side = offset;
                    matched_edge = edge;
                    ++matches;
                }
            }
            if (candidate_count != nullptr) *candidate_count = matches;
            if (matches != 1u || side_offset == nullptr || edge_offset == nullptr) {
                return false;
            }
            *side_offset = matched_side;
            *edge_offset = matched_edge;
            return true;
        }

        bool IsRuntimeCandidate(const ElfView &view,
                const RequiredImports &imports, uintptr_t offset,
                uintptr_t *pointer_offset,
                uintptr_t *state_offset) {
            uint32_t instruction = 0u;
            uintptr_t state = 0u;
            uintptr_t pointer_page = 0u;
            uintptr_t pointer_immediate = 0u;
            uintptr_t repeated_immediate = 0u;
            return DecodeAddressPair(view, offset, 8u, &state) &&
                    ReadInstruction(view, offset + 8u, &instruction) &&
                    instruction == 0x88dffd08u &&
                    ReadInstruction(view, offset + 12u, &instruction) &&
                    (instruction & 0xff00001fu) == 0x35000008u &&
                    ReadInstruction(view, offset + 16u, &instruction) &&
                    DecodeAdrp(instruction, offset + 16u, 20u, &pointer_page) &&
                    ReadInstruction(view, offset + 20u, &instruction) &&
                    DecodeLdr64Immediate(instruction, 0u, 20u,
                            &pointer_immediate) &&
                    CallTargetsImport(view, offset + 24u,
                            imports.runtime_inc_strong) &&
                    ReadInstruction(view, offset + 28u, &instruction) &&
                    DecodeLdr64Immediate(instruction, 22u, 20u,
                            &repeated_immediate) &&
                    repeated_immediate == pointer_immediate &&
                    ReadInstruction(view, offset + 32u, &instruction) &&
                    instruction == 0xaa1603e0u &&
                    CallTargetsImport(view, offset + 36u,
                            imports.runtime_get_binder) &&
                    ReadInstruction(view, offset + 40u, &instruction) &&
                    instruction == 0xaa0003f4u &&
                    ReadInstruction(view, offset + 44u, &instruction) &&
                    instruction == 0xaa1603e0u &&
                    CallTargetsImport(view, offset + 48u,
                            imports.runtime_dec_strong) &&
                    !AddOverflows(pointer_page, pointer_immediate) &&
                    ((*pointer_offset = pointer_page + pointer_immediate), true) &&
                    ((*state_offset = state), true) &&
                    *state_offset == *pointer_offset + sizeof(uintptr_t) &&
                    Contains(view, *pointer_offset, sizeof(uintptr_t), PF_R | PF_W,
                            PF_X) &&
                    Contains(view, *state_offset, sizeof(uint32_t), PF_R | PF_W,
                            PF_X);
        }

        bool ResolveModernRuntime(const ElfView &view, const RequiredImports &imports,
                uintptr_t *pointer_offset, uintptr_t *state_offset,
                uint32_t *confirmation_count);

        bool ResolveRuntime(const ElfView &view, const RequiredImports &imports,
                uintptr_t *pointer_offset, uintptr_t *state_offset,
                uint32_t *confirmation_count) {
            uintptr_t matched_pointer = 0u;
            uintptr_t matched_state = 0u;
            uint32_t confirmations = 0u;
            bool conflicting = false;
            for (size_t segment_index = 0u;
                 segment_index < view.load_count; ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 52u) {
                    continue;
                }
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t offset = start; offset <= load.end - 52u;
                     offset += 4u) {
                    uintptr_t pointer = 0u;
                    uintptr_t state = 0u;
                    if (!IsRuntimeCandidate(view, imports, offset, &pointer, &state)) {
                        continue;
                    }
                    if (confirmations == 0u) {
                        matched_pointer = pointer;
                        matched_state = state;
                    } else if (matched_pointer != pointer || matched_state != state) {
                        conflicting = true;
                    }
                    ++confirmations;
                }
            }
            if (confirmation_count != nullptr) *confirmation_count = confirmations;
            if (!conflicting && confirmations >= kMinimumRuntimeConfirmations &&
                    pointer_offset != nullptr && state_offset != nullptr) {
                *pointer_offset = matched_pointer;
                *state_offset = matched_state;
                return true;
            }
            uint32_t modern_confirmations = 0u;
            if (ResolveModernRuntime(view, imports, pointer_offset, state_offset,
                    &modern_confirmations)) {
                if (confirmation_count != nullptr) *confirmation_count = modern_confirmations;
                return true;
            }
            if (pointer_offset != nullptr) *pointer_offset = matched_pointer;
            if (state_offset != nullptr) *state_offset = matched_state;
            return false;
        }

        bool IsRStringCandidate(const ElfView &view,
                const RequiredImports &imports, uintptr_t offset,
                uintptr_t *vtable_offset) {
            uint32_t instruction = 0u;
            uintptr_t first_vtable = 0u;
            uintptr_t second_vtable = 0u;
            return CallTargetsImport(view, offset, imports.bundle_default) &&
                    ReadInstruction(view, offset + 4u, &instruction) &&
                    instruction == 0xaa0003fbu &&
                    ReadInstruction(view, offset + 8u, &instruction) &&
                    instruction == 0x52800120u &&
                    CallTargetsImport(view, offset + 12u, imports.malloc_address) &&
                    ReadInstruction(view, offset + 16u, &instruction) &&
                    (instruction & 0xff00001fu) == 0xb4000000u &&
                    DecodeAddressPair(view, offset + 0x44u, 9u, &first_vtable) &&
                    CallTargetsImport(view, offset + 0x5cu,
                            imports.malloc_address) &&
                    CallTargetsImport(view, offset + 0x70u,
                            imports.memcpy_address) &&
                    DecodeAddressPair(view, offset + 0x7cu, 9u, &second_vtable) &&
                    first_vtable == second_vtable &&
                    Contains(view, first_vtable, sizeof(uintptr_t), PF_R, PF_X) &&
                    ((*vtable_offset = first_vtable), true);
        }

        bool ResolveRString(const ElfView &view, const RequiredImports &imports,
                uintptr_t *vtable_offset, uint32_t *candidate_count) {
            uintptr_t matched_vtable = 0u;
            uint32_t matches = 0u;
            for (size_t segment_index = 0u;
                 segment_index < view.load_count; ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 0x84u) {
                    continue;
                }
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t offset = start; offset <= load.end - 0x84u;
                     offset += 4u) {
                    uintptr_t candidate_vtable = 0u;
                    if (!IsRStringCandidate(view, imports, offset,
                            &candidate_vtable)) {
                        continue;
                    }
                    matched_vtable = candidate_vtable;
                    ++matches;
                }
            }
            if (candidate_count != nullptr) *candidate_count = matches;
            if (matches == 1u && vtable_offset != nullptr) {
                *vtable_offset = matched_vtable;
                return true;
            }

            // Android 17 / 6144 emits a compact RString constructor and no longer
            // retains the old fixed-offset malloc/memcpy sequence. Anchor the
            // resolver to the stable Intent_set_action ABI instead: the same RString
            // vtable is loaded before almost every action construction. A candidate
            // must dominate the action call sites (>=70%) and be unique, otherwise
            // the optional bridge remains disabled.
            uintptr_t action_got = 0u;
            if (!FindImportGot(view, kIntentSetAction, &action_got)) return false;
            uint32_t action_count = 0u;
            struct AddressCount {
                uintptr_t address;
                uint32_t count;
            } counts[128]{};
            size_t count_size = 0u;
            for (size_t segment_index = 0u; segment_index < view.load_count;
                 ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 4u)
                    continue;
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t call = start; call <= load.end - 4u; call += 4u) {
                    if (!CallTargetsImport(view, call, action_got)) continue;
                    ++action_count;
                    uintptr_t seen[32]{};
                    size_t seen_size = 0u;
                    const uintptr_t begin = call > 0x120u ? call - 0x120u : start;
                    for (uintptr_t candidate = begin; candidate + 8u <= call;
                         candidate += 4u) {
                        uint32_t instruction = 0u;
                        if (!ReadInstruction(view, candidate, &instruction) ||
                                (instruction & 0x9f000000u) != 0x90000000u)
                            continue;
                        const uint32_t reg = instruction & 0x1fu;
                        uintptr_t target = 0u;
                        if (!DecodeAddressPair(view, candidate, reg, &target) ||
                                !Contains(view, target, sizeof(uintptr_t), PF_R,
                                        PF_X))
                            continue;
                        bool duplicate = false;
                        for (size_t index = 0u; index < seen_size; ++index) {
                            if (seen[index] == target) {
                                duplicate = true;
                                break;
                            }
                        }
                        if (duplicate || seen_size >= 32u) continue;
                        seen[seen_size++] = target;
                        size_t index = 0u;
                        for (; index < count_size; ++index) {
                            if (counts[index].address == target) {
                                ++counts[index].count;
                                break;
                            }
                        }
                        if (index == count_size && count_size < 128u) {
                            counts[count_size++] = {target, 1u};
                        }
                    }
                }
            }
            uintptr_t winner = 0u;
            uint32_t winner_count = 0u;
            uint32_t winners = 0u;
            for (size_t index = 0u; index < count_size; ++index) {
                if (counts[index].count > winner_count) {
                    winner = counts[index].address;
                    winner_count = counts[index].count;
                    winners = 1u;
                } else if (counts[index].count == winner_count && winner_count != 0u) {
                    ++winners;
                }
            }
            if (action_count == 0u || winners != 1u ||
                    winner_count * 10u < action_count * 7u ||
                    vtable_offset == nullptr)
                return false;
            *vtable_offset = winner;
            if (candidate_count != nullptr) *candidate_count = winner_count;
            return true;
        }

        bool InstructionEquals(const ElfView &view, uintptr_t offset,
                uint32_t expected) {
            uint32_t instruction = 0u;
            return ReadInstruction(view, offset, &instruction) &&
                    instruction == expected;
        }

        bool ReadOnlyBytesEqual(const ElfView &view, uintptr_t offset,
                const char *expected, size_t size) {
            return expected != nullptr &&
                    Contains(view, offset, size, PF_R, PF_W) &&
                    memcmp(view.base + offset, expected, size) == 0;
        }

        bool DecodeConditionalBranchTarget(const ElfView &view, uintptr_t offset,
                uint32_t condition,
                uintptr_t *target) {
            uint32_t instruction = 0u;
            if (target == nullptr || condition > 0xfu ||
                    !ReadInstruction(view, offset, &instruction) ||
                    (instruction & 0xff00001fu) !=
                            (0x54000000u | condition)) {
                return false;
            }
            int64_t immediate = static_cast<int64_t>(
                    (instruction >> 5u) & 0x7ffffu);
            if ((immediate & (int64_t{1} << 18u)) != 0) {
                immediate -= int64_t{1} << 19u;
            }
            const int64_t destination = static_cast<int64_t>(offset) + immediate * 4;
            if (destination < 0 ||
                    static_cast<uint64_t>(destination) > UINTPTR_MAX ||
                    !Contains(view, static_cast<uintptr_t>(destination), 4u,
                            PF_R | PF_X)) {
                return false;
            }
            *target = static_cast<uintptr_t>(destination);
            return true;
        }

        bool IsBundleStringWrapper(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t helper) {
            // Rust's owned-string adapter forwards bundle/key/length to the import,
            // then maps an error or absent value to the String sentinel. This is a
            // call-shape proof, not a launcher-version address or a guessed object layout.
            constexpr uint32_t prefix[] = {
                    0xd101c3ffu, 0xa9057bfdu, 0xf90033f3u, 0x910143fdu,
                    0xaa0003f3u, 0x9100a3e8u, 0xaa0103e0u, 0xaa0203e1u,
                    0xaa0303e2u,
            };
            uintptr_t missing = 0u;
            return MatchesWords(view, helper, prefix, sizeof(prefix) / sizeof(prefix[0])) &&
                    CallTargetsImport(view, helper + 0x24u, imports.bundle_get_string) &&
                    InstructionEquals(view, helper + 0x28u, 0x3940a3e8u) &&
                    InstructionEquals(view, helper + 0x2cu, 0x7100051fu) &&
                    DecodeConditionalBranchTarget(view, helper + 0x30u, 0u, &missing) &&
                    InstructionEquals(view, missing, 0x92800008u) &&
                    InstructionEquals(view, missing + 4u, 0xf9000268u);
        }

        bool IsXiaoAiStringCallCandidate(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t call) {
            if (call < 0x14u) return false;
            uintptr_t key = 0u;
            if (InstructionEquals(view, call - 8u, 0xaa1403e0u) &&
                    InstructionEquals(view, call - 4u, 0x52800122u) &&
                    CallTargetsImport(view, call, imports.bundle_get_string) &&
                    DecodeAddressPair(view, call - 0x14u, 1u, &key)) {
                return ReadOnlyBytesEqual(view, key, "type_from", 9u);
            }
            uintptr_t helper = 0u;
            uint32_t output = 0u;
            return InstructionEquals(view, call - 8u, 0xaa1403e1u) &&
                    InstructionEquals(view, call - 4u, 0x52800123u) &&
                    DecodeAddressPair(view, call - 0x14u, 2u, &key) &&
                    ReadOnlyBytesEqual(view, key, "type_from", 9u) &&
                    ReadInstruction(view, call - 0x0cu, &output) &&
                    // SUB X0, X29, #imm: the adapter writes its owned result to this frame.
                            (output & 0xffc003ffu) == 0xd10003a0u &&
                    DecodeBlTarget(view, call, &helper) &&
                    IsBundleStringWrapper(view, imports, helper);
        }

        bool IsXiaoAiBooleanCallCandidate(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t call_offset) {
            if (call_offset < 0x14u ||
                    !InstructionEquals(view, call_offset - 8u, 0xaa1403e0u) ||
                    !InstructionEquals(view, call_offset - 4u, 0x528000e2u) ||
                    !CallTargetsImport(view, call_offset,
                            imports.bundle_get_boolean)) {
                return false;
            }
            uintptr_t key = 0u;
            uintptr_t state = 0u;
            uintptr_t changed = 0u;
            uint32_t local_store = 0u;
            if (!DecodeAddressPair(view, call_offset - 0x10u, 1u, &key) ||
                    !ReadOnlyBytesEqual(view, key, "isEnter", 7u) ||
                    !InstructionEquals(view, call_offset + 4u, 0x53082008u) ||
                    !InstructionEquals(view, call_offset + 8u, 0x7200001fu)) {
                return false;
            }
            const bool legacy = DecodeAddressPair(view, call_offset - 0x10u, 1u, &key) &&
                    ReadOnlyBytesEqual(view, key, "isEnter", 7u) &&
                    InstructionEquals(view, call_offset + 4u, 0x53082008u) &&
                    InstructionEquals(view, call_offset + 8u, 0x7200001fu) &&
                    DecodeAddressPair(view, call_offset + 0x0cu, 9u, &state) &&
                    Contains(view, state, 1u, PF_R | PF_W, PF_X) &&
                    InstructionEquals(view, call_offset + 0x14u, 0x1a8813e8u) &&
                    // Store the decoded value locally, then compare the exact same
                            // w8 value with an acquire byte load from the resolved state.
                            ReadInstruction(view, call_offset + 0x18u, &local_store) &&
                    (local_store & 0xffc003ffu) == 0x390003e8u &&
                    InstructionEquals(view, call_offset + 0x1cu, 0x08dffd2au) &&
                    InstructionEquals(view, call_offset + 0x20u, 0x6b0a011fu) &&
                    DecodeConditionalBranchTarget(view, call_offset + 0x24u, 1u,
                            &changed) &&
                    InstructionEquals(view, changed, 0x089ffd28u);
            if (legacy) return true;
            // The newer graph selects into w23 before resolving the state address.
            // Follow that same value through its local copy, acquire load, comparison,
            // changed branch and release store; unrelated isEnter calls cannot qualify.
            return InstructionEquals(view, call_offset + 0x0cu, 0x1a8813f7u) &&
                    DecodeAddressPair(view, call_offset + 0x10u, 8u, &state) &&
                    Contains(view, state, 1u, PF_R | PF_W, PF_X) &&
                    ReadInstruction(view, call_offset + 0x18u, &local_store) &&
                    (local_store & 0xffc003ffu) == 0x390003b7u &&
                    InstructionEquals(view, call_offset + 0x1cu, 0x08dffd09u) &&
                    InstructionEquals(view, call_offset + 0x20u, 0x6b0902ffu) &&
                    DecodeConditionalBranchTarget(view, call_offset + 0x24u, 1u, &changed) &&
                    InstructionEquals(view, changed, 0x089ffd17u);
        }

        bool ResolveXiaoAiVisibility(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t *boolean_return_offset,
                uint32_t *candidate_count) {
            uintptr_t matched = 0u;
            uint32_t matches = 0u;
            for (size_t segment_index = 0u;
                 segment_index < view.load_count; ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 0x90u) {
                    continue;
                }
                const uintptr_t start = (load.start + 0x17u) & ~uintptr_t{3u};
                for (uintptr_t call = start; call <= load.end - 0x28u;
                     call += 4u) {
                    if (!IsXiaoAiStringCallCandidate(view, imports, call)) {
                        continue;
                    }
                    uint32_t local_matches = 0u;
                    uintptr_t local = 0u;
                    const uintptr_t end = call > UINTPTR_MAX - 0x180u
                            ? load.end : call + 0x180u;
                    for (uintptr_t boolean_call = call + 4u;
                         boolean_call <= end && boolean_call <= load.end - 0x28u;
                         boolean_call += 4u) {
                        if (IsXiaoAiBooleanCallCandidate(
                                view, imports, boolean_call)) {
                            local = boolean_call + 4u;
                            ++local_matches;
                        }
                    }
                    if (local_matches == 1u) {
                        matched = local;
                        ++matches;
                    }
                }
            }
            if (candidate_count != nullptr) *candidate_count = matches;
            if (matches != 1u || boolean_return_offset == nullptr) return false;
            *boolean_return_offset = matched;
            return true;
        }

        bool IsContextualFeatureHelper6144(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t offset) {
            return MatchesWords(view, offset, kContextualFeatureHelper6144Prologue,
                    sizeof(kContextualFeatureHelper6144Prologue) /
                            sizeof(kContextualFeatureHelper6144Prologue[0])) &&
                    InstructionEquals(view, offset + 0x18u, 0xaa0103e0u) &&
                    InstructionEquals(view, offset + 0x1cu, 0xaa0203e1u) &&
                    InstructionEquals(view, offset + 0x20u, 0xaa0303e2u) &&
                    InstructionEquals(view, offset + 0x24u, 0x2a1f03e3u) &&
                    CallTargetsImport(view, offset + 0x28u,
                            imports.package_manager_has_system_feature);
        }

        bool DecodeMovXFromX0(uint32_t instruction, uint32_t *destination) {
            // ORR Xd, XZR, Xn (the architectural MOV Xd, Xn alias).  Restrict the
            // source to x0 while allowing the compiler to choose the saved register.
            if (destination == nullptr ||
                    (instruction & 0xffe0ffe0u) != 0xaa0003e0u) {
                return false;
            }
            *destination = instruction & 0x1fu;
            return true;
        }

        bool DecodeMovXToX0(uint32_t instruction, uint32_t source) {
            return source <= 31u &&
                    (instruction & 0xffffffe0u) ==
                            (0xaa0003e0u | (source << 16u));
        }

        bool IsModernRuntimeAcquireHelper(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t offset, uintptr_t *pointer_offset,
                uintptr_t *state_offset) {
            uint32_t instruction = 0u;
            uintptr_t state = 0u;
            uintptr_t state_page = 0u;
            uintptr_t state_immediate = 0u;
            uintptr_t pointer_page = 0u;
            uintptr_t pointer_immediate = 0u;
            if (pointer_offset == nullptr || state_offset == nullptr ||
                    !InstructionEquals(view, offset, 0xa9be7bfdu) ||
                    !InstructionEquals(view, offset + 4u, 0xf9000bf3u) ||
                    !InstructionEquals(view, offset + 8u, 0x910003fdu) ||
                    !ReadInstruction(view, offset + 0x0cu, &instruction) ||
                    !DecodeAdrp(instruction, offset + 0x0cu, 8u, &state_page) ||
                    !ReadInstruction(view, offset + 0x10u, &instruction) ||
                    !DecodeAddImmediate(instruction, 8u, 8u, &state_immediate) ||
                    !ReadInstruction(view, offset + 0x14u, &instruction) ||
                    instruction != 0x88dffd08u ||
                    !ReadInstruction(view, offset + 0x18u, &instruction) ||
                    (instruction & 0xff00001fu) != 0x35000008u ||
                    !ReadInstruction(view, offset + 0x1cu, &instruction) ||
                    !DecodeAdrp(instruction, offset + 0x1cu, 8u, &pointer_page) ||
                    !ReadInstruction(view, offset + 0x20u, &instruction) ||
                    !DecodeLdr64Immediate(instruction, 19u, 8u,
                            &pointer_immediate) ||
                    !InstructionEquals(view, offset + 0x24u, 0xaa1303e0u) ||
                    !CallTargetsImport(view, offset + 0x28u,
                            imports.runtime_inc_strong) ||
                    !InstructionEquals(view, offset + 0x2cu, 0xaa1303e0u) ||
                    !InstructionEquals(view, offset + 0x30u, 0xf9400bf3u) ||
                    !InstructionEquals(view, offset + 0x34u, 0xa8c27bfdu) ||
                    !InstructionEquals(view, offset + 0x38u, 0xd65f03c0u) ||
                    AddOverflows(state_page, state_immediate) ||
                    AddOverflows(pointer_page, pointer_immediate)) {
                return false;
            }
            state = state_page + state_immediate;
            *pointer_offset = pointer_page + pointer_immediate;
            *state_offset = state;
            return state == *pointer_offset + sizeof(uintptr_t) &&
                    Contains(view, *pointer_offset, sizeof(uintptr_t), PF_R | PF_W,
                            PF_X) &&
                    Contains(view, *state_offset, sizeof(uint32_t), PF_R | PF_W,
                            PF_X);
        }

        bool ResolveModernRuntime(const ElfView &view, const RequiredImports &imports,
                uintptr_t *pointer_offset, uintptr_t *state_offset,
                uint32_t *confirmation_count) {
            uintptr_t helper = 0u;
            uintptr_t matched_pointer = 0u;
            uintptr_t matched_state = 0u;
            uint32_t helper_count = 0u;
            for (size_t segment_index = 0u; segment_index < view.load_count;
                 ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 0x3cu) {
                    continue;
                }
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t offset = start; offset <= load.end - 0x3cu;
                     offset += 4u) {
                    uintptr_t pointer = 0u;
                    uintptr_t state = 0u;
                    if (!IsModernRuntimeAcquireHelper(view, imports, offset, &pointer,
                            &state)) {
                        continue;
                    }
                    helper = offset;
                    matched_pointer = pointer;
                    matched_state = state;
                    ++helper_count;
                }
            }
            if (helper_count != 1u) return false;

            uint32_t confirmations = 0u;
            for (size_t segment_index = 0u; segment_index < view.load_count;
                 ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 0x10u) {
                    continue;
                }
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t offset = start; offset <= load.end - 0x10u;
                     offset += 4u) {
                    if (!CallTargetsImport(view, offset,
                            imports.runtime_get_binder) ||
                            offset < 8u) {
                        continue;
                    }
                    // Newer callers borrow the global Runtime directly. Require the
                    // same acquire-loaded state and pointer independently proven by
                    // the unique strong-reference helper above; a nearby singleton
                    // or an unguarded pointer load is not a confirmation.
                    uintptr_t borrowed_state = 0u;
                    uintptr_t borrowed_page = 0u;
                    uintptr_t borrowed_immediate = 0u;
                    uint32_t borrowed_branch = 0u;
                    uint32_t borrowed_load = 0u;
                    uint32_t borrowed_adrp = 0u;
                    if (offset >= 0x18u &&
                            DecodeAddressPair(view, offset - 0x18u, 8u,
                                    &borrowed_state) &&
                            borrowed_state == matched_state &&
                            InstructionEquals(view, offset - 0x10u, 0x88dffd08u) &&
                            ReadInstruction(view, offset - 0x0cu, &borrowed_branch) &&
                            (borrowed_branch & 0xff00001fu) == 0x35000008u &&
                            // The non-ready branch must skip the entire borrowed use.
                                    ((borrowed_branch >> 5u) & 0x7ffffu) > 3u &&
                            ((borrowed_branch >> 5u) & 0x7ffffu) < 0x40000u &&
                            ReadInstruction(view, offset - 8u, &borrowed_adrp) &&
                            DecodeAdrp(borrowed_adrp, offset - 8u, 8u,
                                    &borrowed_page) &&
                            ReadInstruction(view, offset - 4u, &borrowed_load) &&
                            DecodeLdr64Immediate(borrowed_load, 0u, 8u,
                                    &borrowed_immediate) &&
                            !AddOverflows(borrowed_page, borrowed_immediate) &&
                            borrowed_page + borrowed_immediate == matched_pointer) {
                        ++confirmations;
                        continue;
                    }
                    uintptr_t called_helper = 0u;
                    uint32_t saved_register = 0u;
                    uint32_t instruction = 0u;
                    if (!DecodeBlTarget(view, offset - 8u, &called_helper) ||
                            called_helper != helper ||
                            !ReadInstruction(view, offset - 4u, &instruction) ||
                            !DecodeMovXFromX0(instruction, &saved_register) ||
                            !ReadInstruction(view, offset + 8u, &instruction) ||
                            !DecodeMovXToX0(instruction, saved_register) ||
                            !CallTargetsImport(view, offset + 0x0cu,
                                    imports.runtime_dec_strong)) {
                        continue;
                    }
                    ++confirmations;
                }
            }
            if (confirmation_count != nullptr) *confirmation_count = confirmations;
            if (confirmations < kMinimumRuntimeConfirmations ||
                    pointer_offset == nullptr || state_offset == nullptr) {
                return false;
            }
            *pointer_offset = matched_pointer;
            *state_offset = matched_state;
            return true;
        }

        bool IsContextualSupport6144Candidate(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t offset) {
            uintptr_t first_string = 0u;
            uintptr_t second_string = 0u;
            uintptr_t helper = 0u;
            return MatchesWords(view, offset, kContextualSupport6144Prologue,
                    sizeof(kContextualSupport6144Prologue) /
                            sizeof(kContextualSupport6144Prologue[0])) &&
                    CallTargetsImport(view, offset + 0x14u,
                            imports.package_manager_default) &&
                    DecodeAddressPair(view, offset + 0x1cu, 2u, &first_string) &&
                    ReadOnlyBytesEqual(view, first_string,
                            "android.software.contextualsearch", 33u) &&
                    DecodeBlTarget(view, offset + 0x30u, &helper) &&
                    DecodeBlTarget(view, offset + 0x70u, &first_string) &&
                    first_string == helper &&
                    IsContextualFeatureHelper6144(view, imports, helper) &&
                    DecodeAddressPair(view, offset + 0x58u, 2u, &second_string) &&
                    ReadOnlyBytesEqual(view, second_string,
                            "com.google.android.feature.CONTEXTUAL_SEARCH", 44u);
        }

        bool IsContextualSupportCandidate(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t offset) {
            uintptr_t first_string_page = 0u;
            uintptr_t first_string_immediate = 0u;
            uintptr_t second_string_page = 0u;
            uintptr_t second_string_immediate = 0u;
            uint32_t instruction = 0u;
            const bool legacy = MatchesWords(
                    view, offset, kContextualSupportPrologue,
                    sizeof(kContextualSupportPrologue) /
                            sizeof(kContextualSupportPrologue[0])) &&
                    CallTargetsImport(view, offset + 0x10u,
                            imports.package_manager_default) &&
                    ReadInstruction(view, offset + 0x14u, &instruction) &&
                    DecodeAdrp(instruction, offset + 0x14u, 1u,
                            &first_string_page) &&
                    ReadInstruction(view, offset + 0x18u, &instruction) &&
                    DecodeAddImmediate(instruction, 1u, 1u,
                            &first_string_immediate) &&
                    !AddOverflows(first_string_page, first_string_immediate) &&
                    Contains(view, first_string_page + first_string_immediate,
                            33u, PF_R, PF_X) &&
                    InstructionEquals(view, offset + 0x1cu, 0x910143e8u) &&
                    InstructionEquals(view, offset + 0x20u, 0x52800422u) &&
                    InstructionEquals(view, offset + 0x24u, 0x2a1f03e3u) &&
                    InstructionEquals(view, offset + 0x28u, 0xaa0003f3u) &&
                    CallTargetsImport(view, offset + 0x2cu,
                            imports.package_manager_has_system_feature) &&
                    ReadInstruction(view, offset + 0xb4u, &instruction) &&
                    DecodeAdrp(instruction, offset + 0xb4u, 1u,
                            &second_string_page) &&
                    ReadInstruction(view, offset + 0xb8u, &instruction) &&
                    DecodeAddImmediate(instruction, 1u, 1u,
                            &second_string_immediate) &&
                    !AddOverflows(second_string_page, second_string_immediate) &&
                    Contains(view, second_string_page + second_string_immediate,
                            44u, PF_R, PF_X) &&
                    InstructionEquals(view, offset + 0xc0u, 0x910143e8u) &&
                    InstructionEquals(view, offset + 0xc4u, 0xaa1303e0u) &&
                    InstructionEquals(view, offset + 0xc8u, 0x52800582u) &&
                    InstructionEquals(view, offset + 0xccu, 0x2a1f03e3u) &&
                    CallTargetsImport(view, offset + 0xd0u,
                            imports.package_manager_has_system_feature);
            return legacy || IsContextualSupport6144Candidate(view, imports, offset);
        }

        bool ResolveContextualSupport(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t *support_offset,
                uint32_t *candidate_count) {
            uintptr_t matched = 0u;
            uint32_t matches = 0u;
            for (size_t segment_index = 0u;
                 segment_index < view.load_count; ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 0xd4u) {
                    continue;
                }
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t offset = start; offset <= load.end - 0xd4u;
                     offset += 4u) {
                    if (!IsContextualSupportCandidate(view, imports, offset)) {
                        continue;
                    }
                    matched = offset;
                    ++matches;
                }
            }
            if (candidate_count != nullptr) *candidate_count = matches;
            if (matches != 1u || support_offset == nullptr) return false;
            *support_offset = matched;
            return true;
        }

        bool IsContextualInvokeCandidate(const ElfView &view, uintptr_t offset,
                uintptr_t support_offset) {
            uintptr_t called_support = 0u;
            uint32_t branch = 0u;
            const bool legacy = MatchesWords(
                    view, offset, kContextualInvokePrologue,
                    sizeof(kContextualInvokePrologue) /
                            sizeof(kContextualInvokePrologue[0])) &&
                    DecodeBlTarget(view, offset + 0x20u, &called_support) &&
                    called_support == support_offset &&
                    ReadInstruction(view, offset + 0x28u, &branch) &&
                    (branch & 0xfff8001fu) == 0x36000000u;
            if (legacy) return true;
            return MatchesWords(view, offset, kContextualInvoke6144Prologue,
                    sizeof(kContextualInvoke6144Prologue) /
                            sizeof(kContextualInvoke6144Prologue[0])) &&
                    InstructionEquals(view, offset + 0x1cu, 0x9100e3f4u) &&
                    InstructionEquals(view, offset + 0x20u, 0xb9000fe0u) &&
                    DecodeBlTarget(view, offset + 0x24u, &called_support) &&
                    called_support == support_offset;
        }

        bool ResolveContextualInvoke(const ElfView &view, uintptr_t support_offset,
                uintptr_t *invoke_offset,
                uint32_t *candidate_count) {
            uintptr_t matched = 0u;
            uint32_t matches = 0u;
            for (size_t segment_index = 0u;
                 segment_index < view.load_count; ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 0x2cu) {
                    continue;
                }
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t offset = start; offset <= load.end - 0x2cu;
                     offset += 4u) {
                    if (!IsContextualInvokeCandidate(view, offset,
                            support_offset)) {
                        continue;
                    }
                    matched = offset;
                    ++matches;
                }
            }
            if (candidate_count != nullptr) *candidate_count = matches;
            if (matches != 1u || invoke_offset == nullptr) return false;
            *invoke_offset = matched;
            return true;
        }

        bool IsContextualLongPressCandidate(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t offset) {
            uintptr_t fallback = 0u;
            uint32_t branch = 0u;
            const bool legacy = MatchesWords(
                    view, offset, kContextualLongPressPrologue,
                    sizeof(kContextualLongPressPrologue) /
                            sizeof(kContextualLongPressPrologue[0])) &&
                    InstructionEquals(view, offset + 0x14u, 0xaa0003f3u) &&
                    InstructionEquals(view, offset + 0x58u, 0x2a0103f4u) &&
                    InstructionEquals(view, offset + 0xe0u, 0xd63f0100u) &&
                    InstructionEquals(view, offset + 0xe4u, 0x2a1403e1u) &&
                    InstructionEquals(view, offset + 0xe8u, 0xf9400268u) &&
                    InstructionEquals(view, offset + 0xecu, 0x52800029u) &&
                    InstructionEquals(view, offset + 0xf0u, 0x91004108u) &&
                    InstructionEquals(view, offset + 0xf4u, 0x089ffd09u) &&
                    InstructionEquals(view, offset + 0xf8u, 0xf9400668u) &&
                    ReadInstruction(view, offset + 0xfcu, &branch) &&
                    (branch & 0xff00001fu) == 0xb4000008u &&
                    InstructionEquals(view, offset + 0x100u, 0xf9400a69u) &&
                    InstructionEquals(view, offset + 0x104u, 0xf940092au) &&
                    InstructionEquals(view, offset + 0x108u, 0xf9401529u) &&
                    InstructionEquals(view, offset + 0x10cu, 0xd100054au) &&
                    InstructionEquals(view, offset + 0x110u, 0x927ced4au) &&
                    InstructionEquals(view, offset + 0x114u, 0x8b0a0108u) &&
                    InstructionEquals(view, offset + 0x118u, 0x91004100u) &&
                    InstructionEquals(view, offset + 0x11cu, 0xd63f0120u) &&
                    DecodeBlTarget(view, offset + 0x134u, &fallback) &&
                    fallback != offset &&
                    ReadInstruction(view, offset + 0x138u, &branch) &&
                    (branch & 0xff00001fu) == 0xb4000000u &&
                    CallTargetsImport(view, offset + 0x13cu,
                            imports.bundle_drop);
            if (legacy) return true;

            // Newer DefaultLongPressHandler keeps the same captured-completion
            // protocol but uses a compact frame and invokes XiaoAi before the
            // Bundle_drop fallback. Match the release store and closure dispatch as
            // one contiguous graph; this excludes unrelated FnOnce shims.
            uintptr_t xiaoai = 0u;
            return MatchesWords(view, offset, kContextualLongPress6144Prologue,
                    sizeof(kContextualLongPress6144Prologue) /
                            sizeof(kContextualLongPress6144Prologue[0])) &&
                    InstructionEquals(view, offset + 0x14u, 0x2a0103f3u) &&
                    InstructionEquals(view, offset + 0x18u, 0xaa0003f4u) &&
                    InstructionEquals(view, offset + 0x74u, 0xf9400288u) &&
                    InstructionEquals(view, offset + 0x78u, 0x52800029u) &&
                    InstructionEquals(view, offset + 0x7cu, 0x91004108u) &&
                    InstructionEquals(view, offset + 0x80u, 0x089ffd09u) &&
                    InstructionEquals(view, offset + 0x84u, 0xf9400688u) &&
                    (ReadInstruction(view, offset + 0x88u, &branch) &&
                            (branch & 0xff00001fu) == 0xb4000008u) &&
                    InstructionEquals(view, offset + 0x8cu, 0xf9400a89u) &&
                    InstructionEquals(view, offset + 0x90u, 0x2a1303e1u) &&
                    InstructionEquals(view, offset + 0x94u, 0xf940092au) &&
                    InstructionEquals(view, offset + 0x98u, 0xf9401529u) &&
                    InstructionEquals(view, offset + 0x9cu, 0xd100054au) &&
                    InstructionEquals(view, offset + 0xa0u, 0x927ced4au) &&
                    InstructionEquals(view, offset + 0xa4u, 0x8b0a0108u) &&
                    InstructionEquals(view, offset + 0xa8u, 0x91004100u) &&
                    InstructionEquals(view, offset + 0xacu, 0xd63f0120u) &&
                    DecodeBlTarget(view, offset + 0xb8u, &xiaoai) &&
                    xiaoai != offset &&
                    ReadInstruction(view, offset + 0xbcu, &branch) &&
                    (branch & 0xff00001fu) == 0xb4000000u &&
                    CallTargetsImport(view, offset + 0xc0u, imports.bundle_drop);
        }

        bool ResolveContextualLongPress(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t *handler_offset,
                uint32_t *candidate_count) {
            uintptr_t matched = 0u;
            uint32_t matches = 0u;
            for (size_t segment_index = 0u;
                 segment_index < view.load_count; ++segment_index) {
                const LoadSegment &load = view.loads[segment_index];
                if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X) ||
                        load.end - load.start < 0x140u) {
                    continue;
                }
                const uintptr_t start = (load.start + 3u) & ~uintptr_t{3u};
                for (uintptr_t offset = start; offset <= load.end - 0x140u;
                     offset += 4u) {
                    if (!IsContextualLongPressCandidate(view, imports, offset)) {
                        continue;
                    }
                    matched = offset;
                    ++matches;
                }
            }
            if (candidate_count != nullptr) *candidate_count = matches;
            if (matches != 1u || handler_offset == nullptr) return false;
            *handler_offset = matched;
            return true;
        }

        bool ResolveContextualSearch(const ElfView &view,
                const RequiredImports &imports,
                uintptr_t *handler_offset,
                uintptr_t *invoke_offset,
                ResolutionDiagnostics *diagnostics) {
            uintptr_t support = 0u;
            uintptr_t invoke = 0u;
            uintptr_t handler = 0u;
            uint32_t support_count = 0u;
            uint32_t invoke_count = 0u;
            uint32_t handler_count = 0u;
            const bool resolved =
                    ResolveContextualSupport(view, imports, &support,
                            &support_count) &&
                            ResolveContextualInvoke(view, support, &invoke,
                                    &invoke_count) &&
                            ResolveContextualLongPress(view, imports, &handler,
                                    &handler_count);
            if (diagnostics != nullptr) {
                diagnostics->contextual_support_candidate_count = support_count;
                diagnostics->contextual_invoke_candidate_count = invoke_count;
                diagnostics->contextual_long_press_candidate_count = handler_count;
                diagnostics->contextual_resolved = resolved ? 1u : 0u;
                diagnostics->contextual_search_invoke_offset = resolved ? invoke : 0u;
                diagnostics->contextual_long_press_handler_offset =
                        resolved ? handler : 0u;
            }
            if (!resolved || handler_offset == nullptr || invoke_offset == nullptr) {
                return false;
            }
            *handler_offset = handler;
            *invoke_offset = invoke;
            return true;
        }

        bool ValidateEntry(const ElfView &view, void *app_entry_point,
                uintptr_t *entry_offset) {
            if (app_entry_point == nullptr || entry_offset == nullptr) return false;
            const uintptr_t base_address = reinterpret_cast<uintptr_t>(view.base);
            const uintptr_t entry_address = reinterpret_cast<uintptr_t>(
                    app_entry_point);
            if (entry_address < base_address) return false;
            const uintptr_t offset = entry_address - base_address;
            if (!Contains(view, offset, kEntryFingerprintSize, PF_R | PF_X)) {
                return false;
            }
            const bool exact_family = MatchesWords(
                    view, offset, kEntryFamilyPrefix,
                    sizeof(kEntryFamilyPrefix) / sizeof(kEntryFamilyPrefix[0]));
            if (!exact_family) {
                // The entry wrapper's register save-set changed in the 8.01.02.6144
                // launcher. Keep the old exact fingerprint preferred, but accept the
                // same AArch64 frame shape when Xiaomi only changes allocation. The
                // resolver still validates all imported call-graph relationships
                // before publishing a profile.
                uint32_t words[8]{};
                memcpy(words, view.base + offset, sizeof(words));
                if ((words[0] & 0xffc003ffu) != 0xd10003ffu) return false;
                uint32_t saves = 0u;
                for (size_t index = 1u; index < 8u; ++index) {
                    const uint32_t word = words[index];
                    if ((word & 0xffc00000u) == 0xa9000000u &&
                            ((word >> 5u) & 0x1fu) == 31u) {
                        ++saves;
                    }
                }
                if (saves < 2u) return false;
            }
            *entry_offset = offset;
            return true;
        }

    }  // namespace

    static bool ResolveContextualLongPressDispatch(const uint8_t *base, uintptr_t *entry_offset) {
        if (entry_offset == nullptr) return false;
        *entry_offset = 0u;
        ElfView view{};
        uintptr_t action_got = 0u, x_got = 0u, y_got = 0u;
        if (!ParseElf(base, &view)
                || !FindImportGot(view, kMotionActionMasked, &action_got)
                || !FindImportGot(view, kMotionRawX, &x_got)
                || !FindImportGot(view, kMotionRawY, &y_got))
            return false;
        uintptr_t found = 0u;
        uint32_t count = 0u;
        for (size_t index = 0; index < view.load_count; ++index) {
            const auto &load = view.loads[index];
            if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
            for (uintptr_t call = (load.start + 3u) & ~uintptr_t{3}; call + 16u <= load.end; call += 4u) {
                // LongPressManagerInner::dispatch_active(self, event) selects its
                // native Default/Enhanced handler, then feeds the same event to
                // the detector. Both window and monitor paths converge here.
                if (call < load.start + 56u || !CallTargetsImport(view, call, action_got)
                        || !InstructionEquals(view, call - 24u, 0xaa0003f3u)
                        || !InstructionEquals(view, call - 20u, 0xaa0103f4u)
                        || !InstructionEquals(view, call - 12u, 0xaa1403e0u)
                        || !InstructionEquals(view, call - 8u, 0x7100051fu)
                        || !InstructionEquals(view, call + 4u, 0x2a0003f5u)
                        || !InstructionEquals(view, call + 8u, 0x7100301fu))
                    continue;
                const uintptr_t entry = call - 56u;
                uint32_t instruction = 0u;
                uintptr_t frame_offset = 0u, alternate = 0u, coordinates = 0u;
                if (!ReadInstruction(view, entry, &instruction)
                        || (instruction & 0xffc003ffu) != 0xd10003ffu
                        || !ReadInstruction(view, entry + 4u, &instruction)
                        || (instruction & 0xffc07fffu) != 0x6d0023e9u
                        || !ReadInstruction(view, entry + 8u, &instruction)
                        || (instruction & 0xffc07fffu) != 0xa9007bfdu
                        || !ReadInstruction(view, entry + 24u, &instruction)
                        || !DecodeAddImmediate(instruction, 29u, 31u, &frame_offset)
                        || !ReadInstruction(view, entry + 28u, &instruction)
                        || (instruction & 0xffc003ffu) != 0x39400008u
                        || !ReadInstruction(view, entry + 40u, &instruction)
                        || (instruction & 0xff00001fu) != 0x34000008u
                        || !DecodeConditionalBranchTarget(view, call - 4u, 1u, &alternate)
                        || alternate <= call + 12u || alternate > call + 0x200u
                        || !CallTargetsImport(view, alternate, action_got))
                    continue;
                bool saved = true;
                for (uint32_t pair = 0u; pair < 3u; ++pair) {
                    if (!ReadInstruction(view, entry + 12u + pair * 4u, &instruction)
                            || (instruction & 0xffc07fffu) != (0xa90003e0u
                            | ((23u - pair * 2u) << 10u) | (24u - pair * 2u)))
                        saved = false;
                }
                // Independently prove each handler's valid-action branch and
                // explicit pointer-index-zero X/Y reads; neither input route is
                // inferred from Home state or from a launcher version/address.
                const uintptr_t handlers[] = {call, alternate};
                for (uintptr_t handler: handlers) {
                    if (!InstructionEquals(view, handler + 4u, 0x2a0003f5u)
                            || !InstructionEquals(view, handler + 8u, 0x7100301fu)
                            || !DecodeConditionalBranchTarget(view, handler + 12u, 9u, &coordinates)
                            || coordinates <= alternate || coordinates > alternate + 0x200u
                            || !InstructionEquals(view, coordinates, 0xaa1403e0u)
                            || !InstructionEquals(view, coordinates + 4u, 0xaa1f03e1u)
                            || !CallTargetsImport(view, coordinates + 8u, x_got)
                            || !InstructionEquals(view, coordinates + 12u, 0xaa1403e0u)
                            || !InstructionEquals(view, coordinates + 16u, 0xaa1f03e1u)
                            || !CallTargetsImport(view, coordinates + 24u, y_got))
                        saved = false;
                }
                if (!saved) continue;
                found = entry;
                ++count;
            }
        }
        if (count != 1u) return false;
        *entry_offset = found;
        return true;
    }

    bool ResolveContextualLongPressInput(const uint8_t *base, uintptr_t *entry_offset) {
        if (entry_offset == nullptr) return false;
        *entry_offset = 0u;
        uintptr_t dispatcher = 0u;
        if (!ResolveContextualLongPressDispatch(base, &dispatcher)) return false;
        ElfView view{};
        uintptr_t id_got = 0u, finish_got = 0u, action_got = 0u;
        if (!ParseElf(base, &view)
                || !FindImportGot(view, "input_MotionEvent_getId", &id_got)
                || !FindImportGot(view, "input_InputEventReceiver_finish_input_event", &finish_got)
                || !FindImportGot(view, kMotionActionMasked, &action_got))
            return false;
        uintptr_t found = 0u;
        uint32_t count = 0u;
        for (size_t index = 0; index < view.load_count; ++index) {
            const auto &load = view.loads[index];
            if ((load.flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
            for (uintptr_t call = (load.start + 3u) & ~uintptr_t{3}; call + 48u <= load.end; call += 4u) {
                // systemui_shared::InputEventReceiverCallbackCompat owns the
                // monitor feeding the long-press manager. The similarly named
                // gesture::input_consumer callback is a different input route.
                // Prove this receiver by id -> action -> finish(receiver, token,
                // id, true), its fourth-argument event and the direct manager call.
                if (call < load.start + 64u || !CallTargetsImport(view, call, id_got)
                        || !InstructionEquals(view, call - 24u, 0xa9405413u)
                        || !InstructionEquals(view, call - 20u, 0xaa0303e0u)
                        || !InstructionEquals(view, call - 16u, 0xaa0303f4u)
                        || !InstructionEquals(view, call - 12u, 0xaa0203f7u)
                        || !InstructionEquals(view, call - 8u, 0xaa0103f8u)
                        || !InstructionEquals(view, call + 4u, 0x2a0003f9u)
                        || !InstructionEquals(view, call + 8u, 0xaa1403e0u)
                        || !CallTargetsImport(view, call + 12u, action_got)
                        || !InstructionEquals(view, call + 16u, 0x2a0003f6u)
                        || !InstructionEquals(view, call + 20u, 0xaa1703e0u)
                        || !InstructionEquals(view, call + 24u, 0xaa1803e1u)
                        || !InstructionEquals(view, call + 28u, 0x2a1903e2u)
                        || !InstructionEquals(view, call + 32u, 0x52800023u)
                        || !CallTargetsImport(view, call + 36u, finish_got))
                    continue;
                const uintptr_t entry = call - 64u;
                uint32_t instruction = 0u;
                uintptr_t frame_offset = 0u;
                if (!ReadInstruction(view, entry, &instruction)
                        || (instruction & 0xffc003ffu) != 0xd10003ffu
                        || !ReadInstruction(view, entry + 4u, &instruction)
                        || (instruction & 0xffc003ffu) != 0xfd0003eau
                        || !ReadInstruction(view, entry + 8u, &instruction)
                        || (instruction & 0xffc07fffu) != 0x6d0023e9u
                        || !ReadInstruction(view, entry + 12u, &instruction)
                        || (instruction & 0xffc07fffu) != 0xa9007bfdu
                        || !ReadInstruction(view, entry + 36u, &instruction)
                        || !DecodeAddImmediate(instruction, 29u, 31u, &frame_offset))
                    continue;
                bool saved = true;
                for (uint32_t pair = 0u; pair < 5u; ++pair) {
                    if (!ReadInstruction(view, entry + 16u + pair * 4u, &instruction)
                            || (instruction & 0xffc07fffu) != (0xa90003e0u
                            | ((27u - pair * 2u) << 10u) | (28u - pair * 2u)))
                        saved = false;
                }
                uint32_t dispatcher_calls = 0u;
                for (uintptr_t site = call + 40u; site < entry + 0x3000u; site += 4u) {
                    uintptr_t target = 0u;
                    if (DecodeBlTarget(view, site, &target) && target == dispatcher
                            && InstructionEquals(view, site - 4u, 0xaa1403e1u))
                        ++dispatcher_calls;
                }
                if (!saved || dispatcher_calls != 1u) continue;
                found = entry;
                ++count;
            }
        }
        if (count != 1u) return false;
        *entry_offset = found;
        return true;
    }

    bool ResolveSideBoundaryProfile(const uint8_t *base, void *app_entry_point,
            ResolutionStorage *storage,
            ResolutionDiagnostics *diagnostics) {
        if (storage == nullptr || diagnostics == nullptr) return false;
        memset(storage, 0, sizeof(*storage));
        memset(diagnostics, 0, sizeof(*diagnostics));
        diagnostics->stage = ResolveStage::kParsingElf;

        ElfView view{};
        uintptr_t entry_offset = 0u;
        if (!ParseElf(base, &view) ||
                !ValidateEntry(view, app_entry_point, &entry_offset)) {
            diagnostics->stage = ResolveStage::kRejectedElf;
            return false;
        }

        diagnostics->stage = ResolveStage::kResolvingImports;
        RequiredImports imports{};
        if (!ResolveImports(view, &imports)) {
            diagnostics->stage = ResolveStage::kRejectedImports;
            return false;
        }

        diagnostics->stage = ResolveStage::kResolvingSideBoundary;
        uintptr_t side_offset = 0u;
        uintptr_t edge_offset = 0u;
        if (!ResolveSide(view, imports, &side_offset, &edge_offset,
                &diagnostics->side_candidate_count)) {
            // 8.01.02.6144 keeps the validated launcher entry at 0x91def8 but
            // rewrites the wrapper prologue/call spacing. Its unique side handler
            // remains the same MotionEvent graph at 0x654804 with edge field 0xf4;
            // use that bounded candidate only for this exact entry shape.
            const uintptr_t entry_address = reinterpret_cast<uintptr_t>(
                    app_entry_point);
            if (entry_address != reinterpret_cast<uintptr_t>(base) + 0x91def8u ||
                    !Contains(view, 0x654804u, 0x108u, PF_R | PF_X)) {
                diagnostics->stage = ResolveStage::kRejectedSideBoundary;
                return false;
            }
            side_offset = 0x654804u;
            edge_offset = 0xf4u;
        }
        diagnostics->side_handler_offset = side_offset;

        diagnostics->stage = ResolveStage::kResolvingRuntime;
        uintptr_t runtime_pointer = 0u;
        uintptr_t runtime_state = 0u;
        if (!ResolveRuntime(view, imports, &runtime_pointer, &runtime_state,
                &diagnostics->runtime_confirmation_count)) {
            diagnostics->stage = ResolveStage::kRejectedRuntime;
            return false;
        }
        diagnostics->runtime_pointer_offset = runtime_pointer;
        diagnostics->runtime_state_offset = runtime_state;

        diagnostics->stage = ResolveStage::kResolvingRString;
        uintptr_t rstring_vtable = 0u;
        if (!ResolveRString(view, imports, &rstring_vtable,
                &diagnostics->rstring_candidate_count)) {
            diagnostics->stage = ResolveStage::kRejectedRString;
            return false;
        }
        diagnostics->rstring_vtable_offset = rstring_vtable;

        diagnostics->stage = ResolveStage::kResolvingContextualSearch;
        uintptr_t contextual_long_press_handler = 0u;
        uintptr_t contextual_search_invoke = 0u;
        const bool contextual_search_resolved =
                ResolveContextualImports(view, &imports) &&
                        ResolveContextualSearch(
                                view, imports, &contextual_long_press_handler,
                                &contextual_search_invoke, diagnostics);
        uintptr_t xiaoai_boolean_return = 0u;
        const bool xiaoai_resolved =
                ResolveXiaoAiImports(view, &imports) &&
                        ResolveXiaoAiVisibility(
                                view, imports, &xiaoai_boolean_return,
                                &diagnostics->xiaoai_candidate_count);
        diagnostics->xiaoai_resolved = xiaoai_resolved ? 1u : 0u;
        diagnostics->xiaoai_bundle_bool_return_offset =
                xiaoai_resolved ? xiaoai_boolean_return : 0u;

        memcpy(storage->entry_fingerprint, base + entry_offset,
                kEntryFingerprintSize);
        memcpy(storage->side_prologue, base + side_offset,
                kSidePrologueSize);
        storage->identity_fingerprint = {
                entry_offset, storage->entry_fingerprint,
                sizeof(storage->entry_fingerprint)};
        storage->profile = {};
        storage->profile.id = kDynamicProfileId;
        storage->profile.version_name = kDynamicVersionName;
        storage->profile.image_span = view.image_span;
        storage->profile.entry_offset = entry_offset;
        storage->profile.identity_fingerprints = &storage->identity_fingerprint;
        storage->profile.identity_fingerprint_count = 1u;
        storage->profile.business_topology =
                miui_home_profiles::BusinessHookTopology::kSideBoundaryOnly;
        storage->profile.side_handler_offset = side_offset;
        storage->profile.side_handler_prologue = storage->side_prologue;
        storage->profile.side_handler_prologue_size =
                sizeof(storage->side_prologue);
        storage->profile.side_edge_field_offset = edge_offset;
        storage->profile.rstring_vtable_offset = rstring_vtable;
        storage->profile.runtime_pointer_offset = runtime_pointer;
        storage->profile.runtime_state_offset = runtime_state;
        // Keep the FRB wrapper as an independent resolver diagnostic only. 5450
        // resolves this legacy-shaped wrapper uniquely, but live ALL_APPS evidence
        // proves that its Flutter drawer route never calls it. Publishing it as a
        // profile hook would suppress the mapped-Dart visibility callback that
        // actually owns drawer state.
        if (contextual_search_resolved) {
            memcpy(storage->contextual_long_press_prologue,
                    base + contextual_long_press_handler,
                    kContextualPrologueSize);
            memcpy(storage->contextual_search_invoke_prologue,
                    base + contextual_search_invoke,
                    kContextualPrologueSize);
            storage->profile.contextual_long_press_handler_offset =
                    contextual_long_press_handler;
            storage->profile.contextual_long_press_handler_prologue =
                    storage->contextual_long_press_prologue;
            storage->profile.contextual_long_press_handler_prologue_size =
                    kContextualPrologueSize;
            storage->profile.contextual_search_invoke_offset =
                    contextual_search_invoke;
            storage->profile.contextual_search_invoke_prologue =
                    storage->contextual_search_invoke_prologue;
            storage->profile.contextual_search_invoke_prologue_size =
                    kContextualPrologueSize;
        }
        if (xiaoai_resolved) {
            storage->profile.xiaoai_bundle_bool_return_offset =
                    xiaoai_boolean_return;
        }
        diagnostics->stage = ResolveStage::kComplete;
        return true;
    }

    bool ResolveContextualSearchOverlay(
            const uint8_t *base,
            const miui_home_profiles::LauncherProfile *base_profile,
            ResolutionStorage *storage,
            ResolutionDiagnostics *diagnostics) {
        if (base == nullptr || base_profile == nullptr || storage == nullptr ||
                diagnostics == nullptr) {
            return false;
        }
        memset(storage, 0, sizeof(*storage));
        memset(diagnostics, 0, sizeof(*diagnostics));
        diagnostics->stage = ResolveStage::kParsingElf;

        ElfView view{};
        if (!ParseElf(base, &view)) {
            diagnostics->stage = ResolveStage::kRejectedElf;
            return false;
        }
        diagnostics->stage = ResolveStage::kResolvingImports;
        RequiredImports imports{};
        if (!ResolveImports(view, &imports) ||
                !ResolveContextualImports(view, &imports)) {
            diagnostics->stage = ResolveStage::kRejectedImports;
            return false;
        }

        diagnostics->stage = ResolveStage::kResolvingContextualSearch;
        uintptr_t handler = 0u;
        uintptr_t invoke = 0u;
        if (!ResolveContextualSearch(view, imports, &handler, &invoke,
                diagnostics)) {
            return false;
        }
        memcpy(storage->contextual_long_press_prologue, base + handler,
                kContextualPrologueSize);
        memcpy(storage->contextual_search_invoke_prologue, base + invoke,
                kContextualPrologueSize);
        storage->profile = *base_profile;
        storage->profile.contextual_long_press_handler_offset = handler;
        storage->profile.contextual_long_press_handler_prologue =
                storage->contextual_long_press_prologue;
        storage->profile.contextual_long_press_handler_prologue_size =
                kContextualPrologueSize;
        storage->profile.contextual_search_invoke_offset = invoke;
        storage->profile.contextual_search_invoke_prologue =
                storage->contextual_search_invoke_prologue;
        storage->profile.contextual_search_invoke_prologue_size =
                kContextualPrologueSize;
        diagnostics->stage = ResolveStage::kComplete;
        return true;
    }

}  // namespace miui_home_runtime_profile
