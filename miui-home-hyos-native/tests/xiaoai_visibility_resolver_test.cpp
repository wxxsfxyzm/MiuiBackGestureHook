// Exercise the production structural matcher without loading hooks or a launcher process.
#include "../runtime_profile_resolver.cpp"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <vector>

using namespace miui_home_runtime_profile;

namespace {
struct Fixture {
    std::vector<uint8_t> bytes = std::vector<uint8_t>(0x6000);
    RequiredImports imports{};
    uintptr_t anchor;
    uintptr_t boolean_call;

    void put(uintptr_t address, uint32_t word) {
        memcpy(bytes.data() + address, &word, sizeof(word));
    }

    void address_pair(uintptr_t pc, uint32_t reg, uintptr_t address) {
        const uintptr_t pages = ((address >> 12u) - (pc >> 12u)) & 0x1fffffu;
        put(pc, 0x90000000u | ((pages & 3u) << 29u) | ((pages >> 2u) << 5u) | reg);
        put(pc + 4u, 0x91000000u | ((address & 0xfffu) << 10u) | (reg << 5u) | reg);
    }

    void call(uintptr_t pc, uintptr_t target) {
        put(pc, 0x94000000u | (((target - pc) >> 2u) & 0x3ffffffu));
    }

    void branch(uintptr_t pc, uintptr_t target, uint32_t condition) {
        put(pc, 0x54000000u | ((((target - pc) >> 2u) & 0x7ffffu) << 5u) | condition);
    }

    void plt(uintptr_t pc, uintptr_t got) {
        address_pair(pc, 16u, got);
        // PLT's second instruction is a load, followed by its matching ADD.
        put(pc + 4u, 0xf9400000u | (((got & 0xfffu) >> 3u) << 10u) | (16u << 5u) | 17u);
        put(pc + 8u, 0x91000000u | ((got & 0xfffu) << 10u) | (16u << 5u) | 16u);
        put(pc + 12u, 0xd61f0220u);
    }

    void candidate(bool wrapped, uintptr_t start) {
        const uintptr_t boolean = start + 0x80u;
        address_pair(start - 0x14u, wrapped ? 2u : 1u, 0x3100u);
        put(start - 0x0cu, wrapped ? 0xd100a3a0u : 0xd503201fu);
        put(start - 8u, wrapped ? 0xaa1403e1u : 0xaa1403e0u);
        put(start - 4u, wrapped ? 0x52800123u : 0x52800122u);
        call(start, wrapped ? 0x1800u : 0x2000u);
        address_pair(boolean - 0x10u, 1u, 0x3120u);
        put(boolean - 8u, 0xaa1403e0u);
        put(boolean - 4u, 0x528000e2u);
        call(boolean, 0x2010u);
        put(boolean + 4u, 0x53082008u);
        put(boolean + 8u, 0x7200001fu);
        if (wrapped) {
            put(boolean + 0x0cu, 0x1a8813f7u);
            address_pair(boolean + 0x10u, 8u, 0x4200u);
            put(boolean + 0x18u, 0x390063b7u);
            put(boolean + 0x1cu, 0x08dffd09u);
            put(boolean + 0x20u, 0x6b0902ffu);
        } else {
            address_pair(boolean + 0x0cu, 9u, 0x4200u);
            put(boolean + 0x14u, 0x1a8813e8u);
            put(boolean + 0x18u, 0x390003e8u);
            put(boolean + 0x1cu, 0x08dffd2au);
            put(boolean + 0x20u, 0x6b0a011fu);
        }
        branch(boolean + 0x24u, boolean + 0x60u, 1u);
        put(boolean + 0x60u, wrapped ? 0x089ffd17u : 0x089ffd28u);
    }

    explicit Fixture(bool wrapped, uintptr_t shift = 0u)
            : anchor(0x1100u + shift), boolean_call(anchor + 0x80u) {
        imports.bundle_get_string = 0x4000u;
        imports.bundle_get_boolean = 0x4008u;
        memcpy(bytes.data() + 0x3100u, "type_from", 9u);
        memcpy(bytes.data() + 0x3120u, "isEnter", 7u);
        plt(0x2000u, imports.bundle_get_string);
        plt(0x2010u, imports.bundle_get_boolean);
        const uint32_t wrapper[] = {
                0xd101c3ffu, 0xa9057bfdu, 0xf90033f3u, 0x910143fdu,
                0xaa0003f3u, 0x9100a3e8u, 0xaa0103e0u, 0xaa0203e1u, 0xaa0303e2u,
        };
        for (size_t i = 0u; i < sizeof(wrapper) / sizeof(wrapper[0]); ++i)
            put(0x1800u + i * 4u, wrapper[i]);
        call(0x1824u, 0x2000u);
        put(0x1828u, 0x3940a3e8u);
        put(0x182cu, 0x7100051fu);
        branch(0x1830u, 0x1854u, 0u);
        put(0x1854u, 0x92800008u);
        put(0x1858u, 0xf9000268u);
        candidate(wrapped, anchor);
    }

    ElfView view() const {
        ElfView result{};
        result.base = bytes.data();
        result.image_span = bytes.size();
        result.load_count = 3u;
        result.loads[0] = {0x1000u, 0x3000u, PF_R | PF_X};
        result.loads[1] = {0x3000u, 0x4000u, PF_R};
        result.loads[2] = {0x4000u, 0x6000u, PF_R | PF_W};
        return result;
    }

    void expect(bool accepted, uint32_t candidates) const {
        uintptr_t offset = 0u;
        uint32_t count = 0u;
        assert(ResolveXiaoAiVisibility(view(), imports, &offset, &count) == accepted);
        assert(count == candidates);
        assert(offset == (accepted ? boolean_call + 4u : 0u));
    }
};

void check_packaged_elf(const char* apk, uintptr_t data_offset, size_t file_size) {
    std::ifstream input(apk, std::ios::binary);
    assert(input.good());
    input.seekg(data_offset);
    std::vector<uint8_t> file(file_size);
    input.read(reinterpret_cast<char*>(file.data()), file.size());
    assert(static_cast<size_t>(input.gcount()) == file.size());
    assert(file.size() >= sizeof(Elf64_Ehdr));
    Elf64_Ehdr header{};
    memcpy(&header, file.data(), sizeof(header));
    assert(header.e_phentsize == sizeof(Elf64_Phdr));
    assert(header.e_phoff + header.e_phnum * sizeof(Elf64_Phdr) <= file.size());
    std::vector<Elf64_Phdr> loads;
    size_t span = 0u;
    for (size_t i = 0u; i < header.e_phnum; ++i) {
        Elf64_Phdr load{};
        memcpy(&load, file.data() + header.e_phoff + i * sizeof(load), sizeof(load));
        if (load.p_type != PT_LOAD) continue;
        assert(load.p_filesz <= load.p_memsz);
        assert(load.p_offset + load.p_filesz <= file.size());
        assert(load.p_vaddr + load.p_memsz <= kMaxImageSpan);
        loads.push_back(load);
        if (load.p_vaddr + load.p_memsz > span) span = load.p_vaddr + load.p_memsz;
    }
    std::vector<uint8_t> image(span);
    for (const auto& load : loads)
        memcpy(image.data() + load.p_vaddr, file.data() + load.p_offset, load.p_filesz);
    ElfView view{};
    RequiredImports imports{};
    assert(ParseElf(image.data(), &view));
    assert(ResolveXiaoAiImports(view, &imports));
    uintptr_t result = 0u;
    uint32_t count = 0u;
    assert(ResolveXiaoAiVisibility(view, imports, &result, &count));
    assert(count == 1u);
    std::printf("PASS: packaged launcher ELF uniquely resolved XiaoAi return=0x%zx\n", result);
}
}  // namespace

int main(int argc, char** argv) {
    for (bool wrapped : {false, true}) {
        Fixture(wrapped).expect(true, 1u);
        Fixture(wrapped, 0x200u).expect(true, 1u);
        { Fixture f(wrapped); f.bytes[0x3100u] ^= 1u; f.expect(false, 0u); }
        { Fixture f(wrapped); f.bytes[0x3120u] ^= 1u; f.expect(false, 0u); }
        { Fixture f(wrapped); f.call(f.boolean_call, 0x2000u); f.expect(false, 0u); }
        { Fixture f(wrapped); f.put(f.boolean_call + 0x60u, 0xd503201fu); f.expect(false, 0u); }
        {
            Fixture f(wrapped);
            f.address_pair(f.boolean_call + (wrapped ? 0x10u : 0x0cu), wrapped ? 8u : 9u, 0x3200u);
            f.expect(false, 0u); // State cannot be read-only memory.
        }
        { Fixture f(wrapped); f.candidate(wrapped, 0x1500u); f.expect(false, 2u); }
    }
    { Fixture f(true); f.put(0x1820u, 0xaa0403e2u); f.expect(false, 0u); }
    { Fixture f(true); f.call(0x1824u, 0x2010u); f.expect(false, 0u); }
    { Fixture f(true); f.put(0x1858u, 0xf9000288u); f.expect(false, 0u); }
    { Fixture f(true); f.put(f.boolean_call + 0x18u, 0x390063b6u); f.expect(false, 0u); }
    { Fixture f(true); f.put(f.boolean_call + 0x20u, 0x6b0902dfu); f.expect(false, 0u); }
    { Fixture f(true); f.put(f.anchor - 8u, 0xaa1503e1u); f.expect(false, 0u); }
    std::puts("PASS: 22 XiaoAi visibility resolver cases (legacy/wrapped, relocation, mismatch, ambiguity)");
    if (argc == 4)
        check_packaged_elf(argv[1], std::strtoull(argv[2], nullptr, 0),
                           std::strtoull(argv[3], nullptr, 0));
    else assert(argc == 1);
    return 0;
}
