#include "../runtime_profile_resolver.h"
#include <elf.h>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

using miui_home_runtime_profile::ResolutionDiagnostics;
using miui_home_runtime_profile::ResolutionStorage;
using miui_home_runtime_profile::ResolveContextualLongPressInput;
using miui_home_runtime_profile::ResolveSideBoundaryProfile;

static uint32_t word(const std::vector<uint8_t>& image, uintptr_t offset) {
    uint32_t value; std::memcpy(&value, image.data() + offset, 4); return value;
}
static void put(std::vector<uint8_t>& image, uintptr_t offset, uint32_t value) {
    std::memcpy(image.data() + offset, &value, 4);
}

int main(int argc, char** argv) {
    // Captured libapp_launcher.so, app_entry_point, the expected
    // DefaultLongPressHandler Fn, its closure layout (0 captured completion,
    // 1 external completion) and the long-press monitor receiver entry.
    assert(argc == 6);
    std::ifstream input(argv[1], std::ios::binary);
    const std::vector<char> file((std::istreambuf_iterator<char>(input)), {});
    assert(file.size() >= sizeof(Elf64_Ehdr));
    Elf64_Ehdr header{}; std::memcpy(&header, file.data(), sizeof(header));
    std::vector<uint8_t> image(0x4000000);
    for (uint16_t i = 0; i < header.e_phnum; ++i) {
        Elf64_Phdr p{};
        std::memcpy(&p, file.data() + header.e_phoff + i * header.e_phentsize, sizeof(p));
        if (p.p_type != PT_LOAD) continue;
        assert(p.p_vaddr + p.p_memsz <= image.size() && p.p_offset + p.p_filesz <= file.size());
        std::memcpy(image.data() + p.p_vaddr, file.data() + p.p_offset, p.p_filesz);
    }
    const uintptr_t entry = std::strtoull(argv[2], nullptr, 0);
    const uintptr_t handler = std::strtoull(argv[3], nullptr, 0);
    const uint32_t external = static_cast<uint32_t>(std::strtoul(argv[4], nullptr, 0));
    const uintptr_t receiver = std::strtoull(argv[5], nullptr, 0);

    static ResolutionStorage storage{};
    const auto resolve_handler = [&] {
        ResolutionDiagnostics diagnostics{};
        assert(ResolveSideBoundaryProfile(image.data(), image.data() + entry,
                &storage, &diagnostics));
        return storage.profile.contextual_long_press_handler_offset;
    };
    assert(resolve_handler() == handler);
    assert(storage.profile.contextual_long_press_completion_external == external);
    uintptr_t observer = 0u;
    assert(ResolveContextualLongPressInput(image.data(), &observer) && observer == receiver);

    // The optional extension is cleared, never guessed, when the closure
    // dispatch or the XiaoAi/Bundle_drop fallback changes.
    const uintptr_t blr = handler + (external != 0u ? 0x9cu : 0xacu);
    const auto dispatch = word(image, blr);
    assert(dispatch == 0xd63f0120u);  // blr x9
    put(image, blr, 0xd63f0100u); assert(resolve_handler() == 0u);  // blr x8
    put(image, blr, dispatch);
    assert(resolve_handler() == handler);

    // Receiver data flow: finish must take the saved token and the event must
    // feed getActionMasked; a swapped register rejects the observer.
    uintptr_t finish_token = 0u;
    for (uintptr_t at = receiver; at < receiver + 0x80u; at += 4u) {
        if (word(image, at) == 0x52800023u) {  // mov w3, #1
            finish_token = at - 8u;  // mov x1, x<token>
            break;
        }
    }
    assert(finish_token != 0u);
    const auto token = word(image, finish_token);
    assert((token & 0xffe0ffffu) == 0xaa0003e1u);
    put(image, finish_token, token ^ (1u << 16));
    assert(!ResolveContextualLongPressInput(image.data(), &observer) && observer == 0u);
    put(image, finish_token, token);
    assert(ResolveContextualLongPressInput(image.data(), &observer) && observer == receiver);
    std::puts("contextual_long_press_resolver_test passed");
    return 0;
}
