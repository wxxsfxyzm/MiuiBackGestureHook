#include "../runtime_profile_resolver.h"
#include <elf.h>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

using miui_home_runtime_profile::ResolveSfAnimStartAnimation;
using miui_home_runtime_profile::SfAnimStartLayout;

static uint32_t word(const std::vector<uint8_t>& image, uintptr_t offset) {
    uint32_t value; std::memcpy(&value, image.data() + offset, 4); return value;
}
static void put(std::vector<uint8_t>& image, uintptr_t offset, uint32_t value) {
    std::memcpy(image.data() + offset, &value, 4);
}

int main(int argc, char** argv) {
    // Captured libapp_launcher.so and the independently observed entry,
    // one direct caller and the template-materializing adrp.
    assert(argc == 5);
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
    const uintptr_t caller = std::strtoull(argv[3], nullptr, 0);
    const uintptr_t site = std::strtoull(argv[4], nullptr, 0);

    SfAnimStartLayout layout{};
    assert(ResolveSfAnimStartAnimation(image.data(), &layout));
    assert(layout.entry_offset == entry);
    assert(layout.anim_type == 0x288);
    assert(layout.w_start == 0x70 && layout.w_end == 0x74 && layout.w_velocity == 0x7c);
    assert(layout.h_start == 0x90 && layout.h_end == 0x94 && layout.h_velocity == 0x9c);
    assert(layout.cx_start == 0x30 && layout.cx_end == 0x34);
    assert(layout.cy_start == 0x50 && layout.cy_end == 0x54);

    const auto reject = [&] {
        SfAnimStartLayout rejected{};
        assert(!ResolveSfAnimStartAnimation(image.data(), &rejected));
        assert(rejected.entry_offset == 0 && rejected.anim_type == 0);
    };
    // A different caller ABI (copy size) rejects the whole extension.
    const auto size = word(image, caller - 16);
    put(image, caller - 16, size ^ (1u << 5)); reject();
    put(image, caller - 16, size);
    // The template must be materialized exactly once.
    const auto adrp = word(image, site);
    put(image, site, adrp ^ (1u << 5)); reject();
    put(image, site, adrp);
    // A shifted channel field breaks the end = start + 4 record shape.
    for (uintptr_t at = site; at < site + 0x100; at += 4) {
        if (word(image, at) == 0x9101d2c8u) {  // add x8, x22, #0x74
            put(image, at, 0x9101e2c8u); reject();  // #0x78
            put(image, at, 0x9101d2c8u);
            break;
        }
    }
    assert(ResolveSfAnimStartAnimation(image.data(), &layout));
    std::puts("sf_anim_start_resolver_test passed");
    return 0;
}
