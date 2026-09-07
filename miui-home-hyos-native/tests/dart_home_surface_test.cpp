// Host check against a supplied, local launcher libapp.so. Compile this file
// with ../dart_runtime_resolver.cpp; Linux uses its system <elf.h>.
#include "../dart_runtime_resolver.h"
#include "../dart_state_publication.h"
#include <elf.h>
#include <algorithm>
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>

template <typename T>
T Read(const std::vector<uint8_t>& data, size_t offset) {
    assert(offset <= data.size() && sizeof(T) <= data.size() - offset);
    T value;
    std::memcpy(&value, data.data() + offset, sizeof(value));
    return value;
}

void Write(std::vector<uint8_t>& data, size_t offset, uint32_t value) {
    assert(offset <= data.size() && sizeof(value) <= data.size() - offset);
    std::memcpy(data.data() + offset, &value, sizeof(value));
}

int main(int argc, char** argv) {
    // Reproduce a true publication racing an exit back to the already-cached
    // false. The receipt must become true so the drain sends false again.
    volatile uint32_t published_state = 1;
    volatile int64_t published_generation = 7;
    volatile uint64_t published_owner = 3;
    auto receipt = [&](bool sent, uint32_t state, int64_t generation, uint64_t owner) {
        return miui_home_dart_state::RecordPublication(sent, state, generation,
                owner, &published_state, &published_generation, &published_owner);
    };
    assert(receipt(true, 2, 7, 3));
    const uint32_t current_after_exit = 1;
    assert(published_state != current_after_exit);
    assert(receipt(true, current_after_exit, 7, 3));
    assert(published_state == current_after_exit);
    assert(!receipt(false, 2, 8, 4));
    assert(published_state == 1 && published_generation == 7 && published_owner == 3);
    // A new Java arbiter generation also requires a receipt for that generation.
    assert(published_generation != 8);
    assert(receipt(true, 1, 8, 3));
    assert(published_generation == 8 && published_owner == 3);

    assert(argc == 2);
    std::ifstream input(argv[1], std::ios::binary);
    assert(input);
    std::vector<uint8_t> file((std::istreambuf_iterator<char>(input)), {});
    const auto header = Read<Elf64_Ehdr>(file, 0);
    assert(std::memcmp(header.e_ident, ELFMAG, SELFMAG) == 0);
    std::vector<Elf64_Phdr> loads;
    size_t span = 0, executable_end = 0;
    for (size_t i = 0; i < header.e_phnum; ++i) {
        auto load = Read<Elf64_Phdr>(file, header.e_phoff + i * header.e_phentsize);
        if (load.p_type != PT_LOAD) continue;
        assert(load.p_vaddr + load.p_memsz < 0x4000000u);
        span = std::max(span, size_t(load.p_vaddr + load.p_memsz));
        if (load.p_flags & PF_X) executable_end = load.p_vaddr + load.p_filesz;
        loads.push_back(load);
    }
    std::vector<uint8_t> image(span);
    for (const auto& load : loads) {
        assert(load.p_offset + load.p_filesz <= file.size());
        std::memcpy(image.data() + load.p_vaddr,
                    file.data() + load.p_offset, load.p_filesz);
    }
    uintptr_t instructions = 0, build_id = 0;
    for (size_t i = 0; i < header.e_shnum; ++i) {
        auto section = Read<Elf64_Shdr>(file, header.e_shoff + i * header.e_shentsize);
        if (section.sh_type != SHT_DYNSYM) continue;
        auto strings = Read<Elf64_Shdr>(file,
                header.e_shoff + section.sh_link * header.e_shentsize);
        for (size_t cursor = 0; cursor < section.sh_size; cursor += section.sh_entsize) {
            auto symbol = Read<Elf64_Sym>(file, section.sh_offset + cursor);
            const auto offset = strings.sh_offset + symbol.st_name;
            assert(offset < file.size());
            const auto* name = reinterpret_cast<const char*>(file.data() + offset);
            if (std::strcmp(name, "_kDartIsolateSnapshotInstructions") == 0) instructions = symbol.st_value;
            if (std::strcmp(name, "_kDartSnapshotBuildId") == 0) build_id = symbol.st_value;
        }
    }
    assert(instructions && build_id && executable_end > 0x1000);
    miui_home_profiles::LauncherProfile launcher{};
    miui_home_dart_profile::ResolutionStorage storage{};
    miui_home_dart_profile::ResolutionDiagnostics diagnostics{};
    auto resolve = [&](std::vector<uint8_t>& data) {
        return miui_home_dart_profile::ResolveDartFeatureProfile(data.data(),
                data.data() + instructions, data.data() + build_id,
                launcher, &storage, &diagnostics);
    };
    const bool resolved = resolve(image);
    std::cout << "resolver stage=" << uint32_t(diagnostics.stage) << std::endl;
    assert(resolved);
    const auto profile = storage.profile;
    const auto notify = profile.dart_home_surface_notify_offset;
    assert(notify && profile.dart_home_surface_notify_code_size == 113u * 4u);
    // Both early ineligibility and completed publication now have observers.
    for (auto site : {profile.dart_home_surface_inactive_epilogue_offset,
                     profile.dart_home_surface_published_epilogue_offset}) {
        assert(site > notify && site + 16 <= notify + profile.dart_home_surface_notify_code_size);
        assert(site < profile.dart_editing_query_offset || site > profile.dart_editing_query_offset + 0x200);
        assert(Read<uint32_t>(image, site) == 0xaa1603e0u);
    }
    auto reject = [&](std::vector<uint8_t>& corrupted) {
        assert(!resolve(corrupted));
        assert(diagnostics.stage == miui_home_dart_profile::ResolveStage::kRejectedEditing);
        assert(storage.profile.dart_home_surface_notify_offset == 0);
    };
    // An unchanged function prefix must not admit a different context field.
    auto corrupted = image;
    Write(corrupted, notify + 104 * 4, Read<uint32_t>(image, notify + 104 * 4) ^ 0x1000u);
    reject(corrupted);
    // A changed String CID invalidates the assembly's heap-layout proof.
    auto branch_target = [&](size_t pc) {
        auto word = Read<uint32_t>(image, pc);
        assert((word & 0xfc000000u) == 0x94000000u);
        return size_t(int64_t(pc) + (int64_t(int32_t(word << 6) >> 6) * 4));
    };
    corrupted = image;
    const auto string_equals = branch_target(notify + 80 * 4);
    Write(corrupted, string_equals + 7 * 4, Read<uint32_t>(image, string_equals + 7 * 4) ^ 0x400u);
    reject(corrupted);
    // A second notifier with the same refresh graph must be rejected as ambiguous.
    corrupted = image;
    const auto duplicate = (executable_end - 0x1000u) & ~size_t{3};
    std::memcpy(corrupted.data() + duplicate, image.data() + notify, 63u * 4u);
    for (size_t index : {size_t{5}, size_t{61}}) {
        const auto pc = duplicate + index * 4;
        const auto delta = int64_t(branch_target(notify + index * 4)) - int64_t(pc);
        Write(corrupted, pc, 0x94000000u | (uint32_t(delta / 4) & 0x03ffffffu));
    }
    reject(corrupted);
    std::cout << "PASS: complete publication, context mismatch, String ABI mismatch, ambiguity\n";
}
