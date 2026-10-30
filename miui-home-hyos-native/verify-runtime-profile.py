#!/usr/bin/env python3
"""Offline structural regression for the runtime side-boundary resolver."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from dataclasses import dataclass
from pathlib import Path

from elftools.elf.elffile import ELFFile


PF_X = 1
PF_W = 2
PF_R = 4

SIDE_PROLOGUE = struct.pack(
    "<8I",
    0xD10603FF,
    0xFD008BEA,
    0x6D11A3E9,
    0xA912FBFD,
    0xF9009FFC,
    0xA91467FA,
    0xA9155FF8,
    0xA91657F6,
)
SIDE_6144_PROLOGUE = struct.pack(
    "<8I",
    0x6DB82BEB,
    0x6D0123E9,
    0xA9027BFD,
    0xA9036FFC,
    0xA90467FA,
    0xA9055FF8,
    0xA90657F6,
    0xA9074FF4,
)
ENTRY_PREFIX = struct.pack(
    "<9I",
    0xD104C3FF,
    0xA90D7BFD,
    0xA90E6FFC,
    0xA90F67FA,
    0xA9105FF8,
    0xA91157F6,
    0xA9124FF4,
    0x910343FD,
    0xAA0803F9,
)

IMPORT_NAMES = (
    "input_MotionEvent_getActionMasked",
    "input_MotionEvent_getActionIndex",
    "input_MotionEvent_getRawX",
    "input_MotionEvent_getRawY",
    "Runtime_inc_strong",
    "Runtime_get_application_thread_binder",
    "Runtime_dec_strong",
    "Bundle_default",
    "Bundle_drop",
    "Bundle_get_string",
    "Bundle_get_boolean",
    "PackageManager_default",
    "PackageManager_has_system_feature",
    "malloc",
    "memcpy",
    "Intent_set_action",
)


@dataclass(frozen=True)
class Segment:
    start: int
    end: int
    flags: int


@dataclass(frozen=True)
class Resolution:
    side: int
    edge: int
    runtime_pointer: int
    runtime_state: int
    runtime_confirmations: int
    rstring: int
    contextual_support: int
    contextual_invoke: int
    contextual_long_press: int
    xiaoai_boolean_return: int


class LoadedElf:
    def __init__(self, path: Path):
        self.path = path
        file_bytes = path.read_bytes()
        self.digest = hashlib.sha256(file_bytes).hexdigest()
        with path.open("rb") as stream:
            elf = ELFFile(stream)
            loads = [
                segment
                for segment in elf.iter_segments()
                if segment["p_type"] == "PT_LOAD"
            ]
            self.segments = [
                Segment(
                    int(segment["p_vaddr"]),
                    int(segment["p_vaddr"] + segment["p_memsz"]),
                    int(segment["p_flags"]),
                )
                for segment in loads
            ]
            span = max(segment.end for segment in self.segments)
            self.image = bytearray(span)
            for segment in loads:
                start = int(segment["p_vaddr"])
                data = segment.data()
                self.image[start : start + len(data)] = data

            relocation_section = elf.get_section_by_name(".rela.plt")
            if relocation_section is None:
                raise ValueError("missing .rela.plt")
            symbol_section = elf.get_section(relocation_section["sh_link"])
            imports: dict[str, list[int]] = {name: [] for name in IMPORT_NAMES}
            for relocation in relocation_section.iter_relocations():
                name = symbol_section.get_symbol(
                    relocation["r_info_sym"]
                ).name
                if name in imports:
                    imports[name].append(int(relocation["r_offset"]))
            duplicate = {
                name: values for name, values in imports.items() if len(values) != 1
            }
            if duplicate:
                raise ValueError(f"missing or duplicate imports: {duplicate}")
            self.imports = {name: values[0] for name, values in imports.items()}

    def contains(self, offset: int, size: int, required: int, forbidden: int = 0) -> bool:
        return size > 0 and any(
            offset >= segment.start
            and offset + size <= segment.end
            and segment.flags & required == required
            and segment.flags & forbidden == 0
            for segment in self.segments
        )

    def u32(self, offset: int) -> int:
        if not self.contains(offset, 4, PF_R | PF_X):
            raise ValueError(f"instruction outside executable LOAD: 0x{offset:x}")
        return struct.unpack_from("<I", self.image, offset)[0]

    def executable_offsets(self, required_size: int):
        for segment in self.segments:
            if segment.flags & (PF_R | PF_X) != PF_R | PF_X:
                continue
            start = (segment.start + 3) & ~3
            for offset in range(start, segment.end - required_size + 1, 4):
                yield offset


def decode_adrp(instruction: int, pc: int, register: int) -> int | None:
    if instruction & 0x9F00001F != 0x90000000 | register:
        return None
    immediate = ((instruction >> 29) & 3) | (((instruction >> 5) & 0x7FFFF) << 2)
    if immediate & (1 << 20):
        immediate -= 1 << 21
    target = (pc & ~0xFFF) + (immediate << 12)
    return target if target >= 0 else None


def decode_add(instruction: int, destination: int, source: int) -> int | None:
    if instruction & 0xFFC003FF != 0x91000000 | source << 5 | destination:
        return None
    return (instruction >> 10) & 0xFFF


def decode_ldr(instruction: int, destination: int, source: int) -> int | None:
    if instruction & 0xFFC003FF != 0xF9400000 | source << 5 | destination:
        return None
    return ((instruction >> 10) & 0xFFF) * 8


def decode_address_pair(image: LoadedElf, offset: int, register: int) -> int | None:
    page = decode_adrp(image.u32(offset), offset, register)
    immediate = decode_add(image.u32(offset + 4), register, register)
    if page is None or immediate is None:
        return None
    return page + immediate


def decode_bl(image: LoadedElf, offset: int) -> int | None:
    instruction = image.u32(offset)
    if instruction & 0xFC000000 != 0x94000000:
        return None
    immediate = instruction & 0x03FFFFFF
    if immediate & (1 << 25):
        immediate -= 1 << 26
    target = offset + (immediate << 2)
    return target if image.contains(target, 16, PF_R | PF_X) else None


def decode_plt_got(image: LoadedElf, offset: int) -> int | None:
    page = decode_adrp(image.u32(offset), offset, 16)
    load = decode_ldr(image.u32(offset + 4), 17, 16)
    add = decode_add(image.u32(offset + 8), 16, 16)
    if (
        page is None
        or load is None
        or add is None
        or load != add
        or image.u32(offset + 12) != 0xD61F0220
    ):
        return None
    return page + load


def call_targets(image: LoadedElf, offset: int, import_name: str) -> bool:
    target = decode_bl(image, offset)
    return target is not None and decode_plt_got(image, target) == image.imports[import_name]


def resolve_side(image: LoadedElf) -> tuple[int, int, int]:
    matches: list[tuple[int, int]] = []
    expected_imports = (
        IMPORT_NAMES[0], IMPORT_NAMES[1], IMPORT_NAMES[2],
        IMPORT_NAMES[1], IMPORT_NAMES[3],
    )
    for offset in image.executable_offsets(0x1C4):
        prologue = image.image[offset : offset + len(SIDE_PROLOGUE)]
        if prologue not in (SIDE_PROLOGUE, SIDE_6144_PROLOGUE):
            continue
        edge = None
        call_index = 0
        for cursor in range(0x20, 0x1C4, 4):
            instruction = image.u32(offset + cursor)
            if edge is None and instruction & 0xFFC003FF == 0x39400014:
                candidate_edge = (instruction >> 10) & 0xFFF
                if 0x40 <= candidate_edge <= 0x400 and not candidate_edge & 3:
                    edge = candidate_edge
            if call_index < len(expected_imports) and call_targets(
                image, offset + cursor, expected_imports[call_index]
            ):
                call_index += 1
        if edge is not None and call_index == len(expected_imports):
            matches.append((offset, edge))
    if len(matches) != 1:
        raise ValueError(f"expected one side boundary, found {len(matches)}")
    return matches[0][0], matches[0][1], len(matches)


def resolve_runtime(image: LoadedElf) -> tuple[int, int, int]:
    matches: list[tuple[int, int]] = []
    for offset in image.executable_offsets(52):
        state = decode_address_pair(image, offset, 8)
        pointer_page = decode_adrp(image.u32(offset + 16), offset + 16, 20)
        pointer_immediate = decode_ldr(image.u32(offset + 20), 0, 20)
        repeated = decode_ldr(image.u32(offset + 28), 22, 20)
        if state is None or pointer_page is None or pointer_immediate is None:
            continue
        pointer = pointer_page + pointer_immediate
        if (
            image.u32(offset + 8) != 0x88DFFD08
            or image.u32(offset + 12) & 0xFF00001F != 0x35000008
            or not call_targets(image, offset + 24, IMPORT_NAMES[4])
            or repeated != pointer_immediate
            or image.u32(offset + 32) != 0xAA1603E0
            or not call_targets(image, offset + 36, IMPORT_NAMES[5])
            or image.u32(offset + 40) != 0xAA0003F4
            or image.u32(offset + 44) != 0xAA1603E0
            or not call_targets(image, offset + 48, IMPORT_NAMES[6])
            or state != pointer + 8
            or not image.contains(pointer, 8, PF_R | PF_W, PF_X)
            or not image.contains(state, 4, PF_R | PF_W, PF_X)
        ):
            continue
        matches.append((pointer, state))
    if matches and len(set(matches)) == 1:
        return matches[0][0], matches[0][1], len(matches)

    modern_helpers: list[tuple[int, int, int]] = []
    for offset in image.executable_offsets(0x3C):
        words = [image.u32(offset + index) for index in range(0, 0x3C, 4)]
        if words[:3] != [0xA9BE7BFD, 0xF9000BF3, 0x910003FD]:
            continue
        state = decode_address_pair(image, offset + 0x0C, 8)
        pointer_page = decode_adrp(image.u32(offset + 0x1C), offset + 0x1C, 8)
        pointer_immediate = decode_ldr(image.u32(offset + 0x20), 19, 8)
        if (
            state is None
            or pointer_page is None
            or pointer_immediate is None
            or image.u32(offset + 0x14) != 0x88DFFD08
            or image.u32(offset + 0x18) & 0xFF00001F != 0x35000008
            or image.u32(offset + 0x24) != 0xAA1303E0
            or not call_targets(image, offset + 0x28, "Runtime_inc_strong")
            or image.u32(offset + 0x2C) != 0xAA1303E0
            or image.u32(offset + 0x30) != 0xF9400BF3
            or image.u32(offset + 0x34) != 0xA8C27BFD
            or image.u32(offset + 0x38) != 0xD65F03C0
        ):
            continue
        pointer = pointer_page + pointer_immediate
        if (
            state != pointer + 8
            or not image.contains(pointer, 8, PF_R | PF_W, PF_X)
            or not image.contains(state, 4, PF_R | PF_W, PF_X)
        ):
            continue
        modern_helpers.append((offset, pointer, state))
    if len(modern_helpers) != 1:
        raise ValueError(f"modern runtime helper is not unique: {modern_helpers}")
    helper, pointer, state = modern_helpers[0]

    def mov_from_x0(instruction: int) -> int | None:
        if instruction & 0xFFE0FFE0 != 0xAA0003E0:
            return None
        return instruction & 0x1F

    modern_confirmations = 0
    for offset in image.executable_offsets(0x10):
        if call_targets(image, offset, "Runtime_get_application_thread_binder") and offset >= 0x18:
            borrowed_page = decode_adrp(image.u32(offset - 8), offset - 8, 8)
            borrowed_immediate = decode_ldr(image.u32(offset - 4), 0, 8)
            branch = image.u32(offset - 0x0C)
            if (
                decode_address_pair(image, offset - 0x18, 8) == state
                and image.u32(offset - 0x10) == 0x88DFFD08
                and branch & 0xFF00001F == 0x35000008
                and 3 < (branch >> 5) & 0x7FFFF < 0x40000
                and borrowed_page is not None
                and borrowed_immediate is not None
                and borrowed_page + borrowed_immediate == pointer
            ):
                modern_confirmations += 1
                continue
        if (
            not call_targets(image, offset, "Runtime_get_application_thread_binder")
            or offset < 8
            or decode_bl(image, offset - 8) != helper
        ):
            continue
        saved = mov_from_x0(image.u32(offset - 4))
        if saved is None:
            continue
        if image.u32(offset + 8) & 0xFFFFFFE0 != 0xAA0003E0 | saved << 16:
            continue
        if not call_targets(image, offset + 0x0C, "Runtime_dec_strong"):
            continue
        modern_confirmations += 1
    if modern_confirmations < 2:
        raise ValueError(
            f"modern runtime graph confirmations too low: {modern_confirmations}"
        )
    return pointer, state, modern_confirmations


def resolve_rstring(image: LoadedElf) -> tuple[int, int]:
    matches: list[int] = []
    for offset in image.executable_offsets(0x84):
        if (
            not call_targets(image, offset, IMPORT_NAMES[7])
            or image.u32(offset + 4) != 0xAA0003FB
            or image.u32(offset + 8) != 0x52800120
            or not call_targets(image, offset + 12, "malloc")
            or image.u32(offset + 16) & 0xFF00001F != 0xB4000000
            or not call_targets(image, offset + 0x5C, "malloc")
            or not call_targets(image, offset + 0x70, "memcpy")
        ):
            continue
        first = decode_address_pair(image, offset + 0x44, 9)
        second = decode_address_pair(image, offset + 0x7C, 9)
        if (
            first is None
            or first != second
            or not image.contains(first, 8, PF_R, PF_X)
        ):
            continue
        matches.append(first)
    if len(matches) == 1:
        return matches[0], len(matches)
    action_calls = [
        offset
        for offset in image.executable_offsets(4)
        if call_targets(image, offset, "Intent_set_action")
    ]
    counts: dict[int, int] = {}
    for call in action_calls:
        seen: set[int] = set()
        for offset in range(max(0, call - 0x120), call, 4):
            instruction = image.u32(offset)
            if instruction & 0x9F000000 != 0x90000000:
                continue
            register = instruction & 0x1F
            target = decode_address_pair(image, offset, register)
            if target is None or not image.contains(target, 8, PF_R, PF_X):
                continue
            if target not in seen:
                seen.add(target)
                counts[target] = counts.get(target, 0) + 1
    if not action_calls or not counts:
        raise ValueError(f"expected one RString constructor, found {len(matches)}")
    winner_count = max(counts.values())
    winners = [target for target, count in counts.items() if count == winner_count]
    if len(winners) != 1 or winner_count * 10 < len(action_calls) * 7:
        raise ValueError(f"RString action anchor is ambiguous: {counts}")
    return winners[0], winner_count


def resolve_contextual_support(image: LoadedElf) -> int:
    def feature_helper_6144(offset: int) -> bool:
        prologue = (
            0xD10203FF, 0xA9067BFD, 0xA9074FF4,
            0x910183FD, 0xAA0003F3, 0x9100C3E8,
        )
        return (
            image.image[offset : offset + 24] == struct.pack("<6I", *prologue)
            and image.u32(offset + 0x18) == 0xAA0103E0
            and image.u32(offset + 0x1C) == 0xAA0203E1
            and image.u32(offset + 0x20) == 0xAA0303E2
            and image.u32(offset + 0x24) == 0x2A1F03E3
            and call_targets(image, offset + 0x28,
                             "PackageManager_has_system_feature")
        )

    modern_prologue = (
        0xD10243FF, 0xA9067BFD, 0xF9003BF5,
        0xA9084FF4, 0x910183FD,
    )
    prologue = (0xD10383FF, 0xA90C7BFD, 0xA90D4FF4, 0x910303FD)
    prologue_bytes = struct.pack("<4I", *prologue)
    matches: list[int] = []
    for offset in image.executable_offsets(0xD4):
        if image.image[offset : offset + len(prologue_bytes)] == prologue_bytes:
            first_page = decode_adrp(image.u32(offset + 0x14), offset + 0x14, 1)
            first_add = decode_add(image.u32(offset + 0x18), 1, 1)
            second_page = decode_adrp(image.u32(offset + 0xB4), offset + 0xB4, 1)
            second_add = decode_add(image.u32(offset + 0xB8), 1, 1)
            if (
                call_targets(image, offset + 0x10, "PackageManager_default")
                and first_page is not None and first_add is not None
                and image.contains(first_page + first_add, 33, PF_R, PF_X)
                and image.u32(offset + 0x1C) == 0x910143E8
                and image.u32(offset + 0x20) == 0x52800422
                and image.u32(offset + 0x24) == 0x2A1F03E3
                and image.u32(offset + 0x28) == 0xAA0003F3
                and call_targets(image, offset + 0x2C,
                                 "PackageManager_has_system_feature")
                and second_page is not None and second_add is not None
                and image.contains(second_page + second_add, 44, PF_R, PF_X)
                and image.u32(offset + 0xC0) == 0x910143E8
                and image.u32(offset + 0xC4) == 0xAA1303E0
                and image.u32(offset + 0xC8) == 0x52800582
                and image.u32(offset + 0xCC) == 0x2A1F03E3
                and call_targets(image, offset + 0xD0,
                                 "PackageManager_has_system_feature")
            ):
                matches.append(offset)
            continue
        modern_bytes = struct.pack("<5I", *modern_prologue)
        if image.image[offset : offset + len(modern_bytes)] != modern_bytes:
            continue
        first = decode_address_pair(image, offset + 0x1C, 2)
        second = decode_address_pair(image, offset + 0x58, 2)
        helper = decode_bl(image, offset + 0x30)
        if (
            not call_targets(image, offset + 0x14, "PackageManager_default")
            or first is None or second is None
            or image.image[first : first + 33] != b"android.software.contextualsearch"
            or image.image[second : second + 44]
            != b"com.google.android.feature.CONTEXTUAL_SEARCH"
            or helper is None
            or decode_bl(image, offset + 0x70) != helper
            or not feature_helper_6144(helper)
        ):
            continue
        matches.append(offset)
    if len(matches) != 1:
        raise ValueError(f"expected one contextual support function, found {len(matches)}")
    return matches[0]


def resolve_contextual_invoke(image: LoadedElf, support: int) -> int:
    prologue = (
        0xD10543FF,
        0xA9117BFD,
        0xA9125FFC,
        0xA91357F6,
        0xA9144FF4,
        0x910443FD,
        0x9100C3F6,
        0xB90007E0,
    )
    prologue_bytes = struct.pack("<8I", *prologue)
    matches: list[int] = []
    modern = struct.pack(
        "<7I", 0xD10403FF, 0xA90C7BFD, 0xF9006BF7,
        0xA90E57F6, 0xA90F4FF4, 0x910303FD, 0x2A0003F3
    )
    for offset in image.executable_offsets(0x2C):
        if image.image[offset : offset + len(prologue_bytes)] == prologue_bytes:
            if (decode_bl(image, offset + 0x20) == support
                    and image.u32(offset + 0x28) & 0xFFF8001F
                    == 0x36000000):
                matches.append(offset)
        elif (image.image[offset : offset + len(modern)] == modern
              and image.u32(offset + 0x1C) == 0x9100E3F4
              and image.u32(offset + 0x20) == 0xB9000FE0
              and decode_bl(image, offset + 0x24) == support):
            matches.append(offset)
    if len(matches) != 1:
        raise ValueError(f"expected one contextual invoke function, found {len(matches)}")
    return matches[0]


def resolve_contextual_long_press(image: LoadedElf) -> int:
    prologue = (0xD102C3FF, 0xA9097BFD, 0xA90A4FF4, 0x910243FD)
    prologue_bytes = struct.pack("<4I", *prologue)
    exact = {
        0x14: 0xAA0003F3,
        0x58: 0x2A0103F4,
        0xE0: 0xD63F0100,
        0xE4: 0x2A1403E1,
        0xE8: 0xF9400268,
        0xEC: 0x52800029,
        0xF0: 0x91004108,
        0xF4: 0x089FFD09,
        0xF8: 0xF9400668,
        0x100: 0xF9400A69,
        0x104: 0xF940092A,
        0x108: 0xF9401529,
        0x10C: 0xD100054A,
        0x110: 0x927CED4A,
        0x114: 0x8B0A0108,
        0x118: 0x91004100,
        0x11C: 0xD63F0120,
    }
    modern_prologue = struct.pack(
        "<4I", 0xD10183FF, 0xA9047BFD, 0xA9054FF4, 0x910103FD
    )
    matches: list[int] = []
    for offset in image.executable_offsets(0x140):
        if image.image[offset : offset + len(prologue_bytes)] != prologue_bytes:
            if image.image[offset : offset + len(modern_prologue)] != modern_prologue:
                continue
            if (
                image.u32(offset + 0x14) != 0x2A0103F3
                or image.u32(offset + 0x18) != 0xAA0003F4
                or image.u32(offset + 0x74) != 0xF9400288
                or image.u32(offset + 0x78) != 0x52800029
                or image.u32(offset + 0x7C) != 0x91004108
                or image.u32(offset + 0x80) != 0x089FFD09
                or image.u32(offset + 0x84) != 0xF9400688
                or image.u32(offset + 0x88) & 0xFF00001F != 0xB4000008
                or image.u32(offset + 0x8C) != 0xF9400A89
                or image.u32(offset + 0x90) != 0x2A1303E1
                or image.u32(offset + 0x94) != 0xF940092A
                or image.u32(offset + 0x98) != 0xF9401529
                or image.u32(offset + 0x9C) != 0xD100054A
                or image.u32(offset + 0xA0) != 0x927CED4A
                or image.u32(offset + 0xA4) != 0x8B0A0108
                or image.u32(offset + 0xA8) != 0x91004100
                or image.u32(offset + 0xAC) != 0xD63F0120
                or decode_bl(image, offset + 0xB8) is None
                or image.u32(offset + 0xBC) & 0xFF00001F != 0xB4000000
                or not call_targets(image, offset + 0xC0, "Bundle_drop")
            ):
                continue
            matches.append(offset)
            continue
        fallback = decode_bl(image, offset + 0x134)
        if (
            any(image.u32(offset + relative) != word for relative, word in exact.items())
            or image.u32(offset + 0xFC) & 0xFF00001F != 0xB4000008
            or fallback is None
            or fallback == offset
            or image.u32(offset + 0x138) & 0xFF00001F != 0xB4000000
            or not call_targets(image, offset + 0x13C, "Bundle_drop")
        ):
            continue
        matches.append(offset)
    if len(matches) != 1:
        raise ValueError(f"expected one contextual long-press Fn, found {len(matches)}")
    return matches[0]


def decode_conditional_branch(
    image: LoadedElf, offset: int, condition: int
) -> int | None:
    instruction = image.u32(offset)
    if instruction & 0xFF00001F != 0x54000000 | condition:
        return None
    immediate = (instruction >> 5) & 0x7FFFF
    if immediate & (1 << 18):
        immediate -= 1 << 19
    target = offset + (immediate << 2)
    return target if image.contains(target, 4, PF_R | PF_X) else None


def is_bundle_string_wrapper(image: LoadedElf, helper: int) -> bool:
    prefix = (
        0xD101C3FF, 0xA9057BFD, 0xF90033F3, 0x910143FD,
        0xAA0003F3, 0x9100A3E8, 0xAA0103E0, 0xAA0203E1, 0xAA0303E2,
    )
    if not image.contains(helper, 0x34, PF_R | PF_X):
        return False
    missing = decode_conditional_branch(image, helper + 0x30, 0)
    return (
        all(image.u32(helper + i * 4) == code for i, code in enumerate(prefix))
        and call_targets(image, helper + 0x24, "Bundle_get_string")
        and image.u32(helper + 0x28) == 0x3940A3E8
        and image.u32(helper + 0x2C) == 0x7100051F
        and missing is not None
        and image.contains(missing, 8, PF_R | PF_X)
        and image.u32(missing) == 0x92800008
        and image.u32(missing + 4) == 0xF9000268
    )


def is_xiaoai_string_call(image: LoadedElf, call: int) -> bool:
    key = decode_address_pair(image, call - 0x14, 1)
    if (
        image.u32(call - 8) == 0xAA1403E0
        and image.u32(call - 4) == 0x52800122
        and call_targets(image, call, "Bundle_get_string")
        and key is not None
        and image.contains(key, 9, PF_R, PF_W)
        and image.image[key : key + 9] == b"type_from"
    ):
        return True
    key = decode_address_pair(image, call - 0x14, 2)
    helper = decode_bl(image, call)
    return (
        image.u32(call - 8) == 0xAA1403E1
        and image.u32(call - 4) == 0x52800123
        and key is not None
        and image.contains(key, 9, PF_R, PF_W)
        and image.image[key : key + 9] == b"type_from"
        and image.u32(call - 0x0C) & 0xFFC003FF == 0xD10003A0
        and helper is not None
        and is_bundle_string_wrapper(image, helper)
    )


def resolve_xiaoai_visibility(image: LoadedElf) -> int:
    matches: list[int] = []
    for call in image.executable_offsets(0x28):
        if call < 0x14 or not image.contains(call - 0x14, 0x3C, PF_R | PF_X):
            continue
        if not is_xiaoai_string_call(image, call):
            continue
        local: list[int] = []
        for boolean_call in range(call + 4, call + 0x184, 4):
            if not image.contains(boolean_call - 0x10, 0x38, PF_R | PF_X):
                break
            key = decode_address_pair(image, boolean_call - 0x10, 1)
            changed = decode_conditional_branch(image, boolean_call + 0x24, 1)
            if (
                image.u32(boolean_call - 8) != 0xAA1403E0
                or image.u32(boolean_call - 4) != 0x528000E2
                or not call_targets(image, boolean_call, "Bundle_get_boolean")
                or key is None
                or not image.contains(key, 7, PF_R, PF_W)
                or image.image[key : key + 7] != b"isEnter"
                or image.u32(boolean_call + 4) != 0x53082008
                or image.u32(boolean_call + 8) != 0x7200001F
                or changed is None
            ):
                continue
            state = decode_address_pair(image, boolean_call + 0x0C, 9)
            legacy = (
                state is not None
                and image.contains(state, 1, PF_R | PF_W, PF_X)
                and image.u32(boolean_call + 0x14) == 0x1A8813E8
                and image.u32(boolean_call + 0x18) & 0xFFC003FF == 0x390003E8
                and image.u32(boolean_call + 0x1C) == 0x08DFFD2A
                and image.u32(boolean_call + 0x20) == 0x6B0A011F
                and image.u32(changed) == 0x089FFD28
            )
            state = decode_address_pair(image, boolean_call + 0x10, 8)
            wrapped = (
                state is not None
                and image.contains(state, 1, PF_R | PF_W, PF_X)
                and image.u32(boolean_call + 0x0C) == 0x1A8813F7
                and image.u32(boolean_call + 0x18) & 0xFFC003FF == 0x390003B7
                and image.u32(boolean_call + 0x1C) == 0x08DFFD09
                and image.u32(boolean_call + 0x20) == 0x6B0902FF
                and image.u32(changed) == 0x089FFD17
            )
            if legacy or wrapped:
                local.append(boolean_call + 4)
        if len(local) == 1:
            matches.append(local[0])
    if len(matches) != 1:
        raise ValueError(
            f"expected one XiaoAi visibility call graph, found {len(matches)}"
        )
    return matches[0]


def resolve(image: LoadedElf, entry: int) -> Resolution:
    exact_entry = image.contains(entry, 48, PF_R | PF_X) and (
        image.image[entry : entry + len(ENTRY_PREFIX)] == ENTRY_PREFIX
    )
    if not exact_entry:
        words = [image.u32(entry + index * 4) for index in range(8)]
        saves = sum(
            1 for word in words[1:]
            if word & 0xFFC00000 == 0xA9000000
            and (word >> 5) & 0x1F == 31
        )
        exact_entry = (
            image.contains(entry, 48, PF_R | PF_X)
            and words[0] & 0xFFC003FF == 0xD10003FF
            and saves >= 2
        )
    if not exact_entry:
        raise ValueError("app_entry_point is outside the side-boundary family")
    side, edge, _ = resolve_side(image)
    pointer, state, confirmations = resolve_runtime(image)
    rstring, _ = resolve_rstring(image)
    support = 0
    invoke = 0
    long_press = 0
    xiaoai_boolean_return = 0
    try:
        support = resolve_contextual_support(image)
        invoke = resolve_contextual_invoke(image, support)
        long_press = resolve_contextual_long_press(image)
    except ValueError:
        # Contextual Search is an optional extension. A missing or ambiguous
        # member clears the whole triple without rejecting the base profile.
        support = 0
        invoke = 0
        long_press = 0
    try:
        xiaoai_boolean_return = resolve_xiaoai_visibility(image)
    except ValueError:
        # XiaoAi visibility is independently optional. It must never weaken
        # the base side-boundary profile or any other optional extension.
        xiaoai_boolean_return = 0
    return Resolution(
        side, edge, pointer, state, confirmations, rstring, support, invoke,
        long_press, xiaoai_boolean_return,
    )


def parse_int(value: str) -> int:
    return int(value, 0)


def verify(profile: dict, path: Path) -> None:
    if profile["hook_topology"] != "side_boundary_only":
        raise ValueError(f"{profile['id']} is not a side-boundary profile")
    image = LoadedElf(path)
    if image.digest.lower() != profile["library_sha256"].lower():
        raise ValueError(f"{profile['id']} immutable library hash mismatch")
    entry = parse_int(profile["entry_offset"])
    result = resolve(image, entry)
    contextual = profile.get("contextual_search")
    abi = profile.get("abi", {})
    expected = Resolution(
        parse_int(profile["side_handler"]["offset"]),
        parse_int(profile["side_handler"]["edge_field_offset"]),
        parse_int(abi["runtime_pointer_offset"])
        if "runtime_pointer_offset" in abi else result.runtime_pointer,
        parse_int(abi["runtime_state_offset"])
        if "runtime_state_offset" in abi else result.runtime_state,
        result.runtime_confirmations,
        parse_int(abi["rstring_vtable_offset"])
        if "rstring_vtable_offset" in abi else result.rstring,
        result.contextual_support if contextual else 0,
        parse_int(contextual["invoke_offset"])
        if contextual else result.contextual_invoke,
        parse_int(contextual["long_press_handler_offset"])
        if contextual else result.contextual_long_press,
        result.xiaoai_boolean_return,
    )
    if result != expected:
        raise ValueError(f"{profile['id']} resolution mismatch: {result} != {expected}")

    original = image.image[result.side]
    image.image[result.side] ^= 1
    try:
        resolve_side(image)
    except ValueError:
        pass
    else:
        raise ValueError(f"{profile['id']} corrupted side boundary did not fail closed")
    finally:
        image.image[result.side] = original

    if result.contextual_long_press != 0:
        contextual_tail = (
            0x80
            if image.image[result.contextual_long_press : result.contextual_long_press + 4]
            == struct.pack("<I", 0xD10183FF)
            else 0xF4
        )
        original = image.image[result.contextual_long_press + contextual_tail]
        image.image[result.contextual_long_press + contextual_tail] ^= 1
        try:
            without_contextual = resolve(image, entry)
            if (
                without_contextual.side != result.side
                or without_contextual.runtime_pointer != result.runtime_pointer
                or without_contextual.rstring != result.rstring
                or without_contextual.contextual_support != 0
                or without_contextual.contextual_invoke != 0
                or without_contextual.contextual_long_press != 0
            ):
                raise ValueError(
                    f"{profile['id']} optional contextual failure damaged base profile"
                )
        finally:
            image.image[result.contextual_long_press + contextual_tail] = original

    if result.xiaoai_boolean_return != 0:
        original = image.image[result.xiaoai_boolean_return]
        image.image[result.xiaoai_boolean_return] ^= 1
        try:
            without_xiaoai = resolve(image, entry)
            if (
                without_xiaoai.side != result.side
                or without_xiaoai.runtime_pointer != result.runtime_pointer
                or without_xiaoai.rstring != result.rstring
                or without_xiaoai.contextual_support != result.contextual_support
                or without_xiaoai.contextual_invoke != result.contextual_invoke
                or without_xiaoai.contextual_long_press
                != result.contextual_long_press
                or without_xiaoai.xiaoai_boolean_return != 0
            ):
                raise ValueError(
                    f"{profile['id']} optional XiaoAi failure damaged another resolver"
                )
        finally:
            image.image[result.xiaoai_boolean_return] = original

    print(
        f"{profile['id']}: PASS side=0x{result.side:x} edge=0x{result.edge:x} "
        f"rstring=0x{result.rstring:x} runtime=0x{result.runtime_pointer:x}/"
        f"0x{result.runtime_state:x} confirmations={result.runtime_confirmations} "
        f"contextual=0x{result.contextual_long_press:x}/"
        f"0x{result.contextual_invoke:x} support=0x{result.contextual_support:x} "
        f"xiaoaiReturn=0x{result.xiaoai_boolean_return:x}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).with_name("launcher-profiles.json"),
    )
    parser.add_argument("--library", action="append", required=True, metavar="ID=PATH")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    profiles = {
        profile["id"]: profile
        for profile in (
            manifest["profiles"] + manifest.get("runtime_reference_profiles", [])
        )
    }
    for binding in args.library:
        profile_id, separator, raw_path = binding.partition("=")
        if not separator or profile_id not in profiles:
            raise ValueError(f"invalid --library binding: {binding}")
        verify(profiles[profile_id], Path(raw_path))


if __name__ == "__main__":
    main()
