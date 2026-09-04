#!/usr/bin/env python3
"""Verify launcher profile RVAs and fingerprints against local ELF files."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path


PT_LOAD = 1
PF_X = 1


def parse_int(value: str) -> int:
    return int(value, 0)


def parse_bytes(value: str) -> bytes:
    return bytes.fromhex(value)


def load_segments(data: bytes) -> list[tuple[int, int, int, int, int]]:
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError("expected a little-endian ELF64 image")
    program_offset = struct.unpack_from("<Q", data, 0x20)[0]
    entry_size = struct.unpack_from("<H", data, 0x36)[0]
    entry_count = struct.unpack_from("<H", data, 0x38)[0]
    if entry_size < 56:
        raise ValueError("invalid ELF64 program-header size")
    result = []
    for index in range(entry_count):
        offset = program_offset + index * entry_size
        p_type, flags, file_offset, virtual_address = struct.unpack_from(
            "<IIQQ", data, offset
        )
        file_size = struct.unpack_from("<Q", data, offset + 32)[0]
        memory_size = struct.unpack_from("<Q", data, offset + 40)[0]
        if p_type == PT_LOAD:
            result.append(
                (virtual_address, file_offset, file_size, memory_size, flags)
            )
    return result


def rva_to_file_offset(
    rva: int,
    size: int,
    segments: list[tuple[int, int, int, int, int]],
    require_executable: bool = False,
) -> int:
    for virtual_address, file_offset, file_size, _memory_size, flags in segments:
        if require_executable and not flags & PF_X:
            continue
        delta = rva - virtual_address
        if delta >= 0 and delta + size <= file_size:
            return file_offset + delta
    raise ValueError(f"RVA 0x{rva:x} (+0x{size:x}) is outside file-backed LOAD data")


def require_load_memory(
    rva: int, size: int, segments: list[tuple[int, int, int, int, int]]
) -> None:
    for virtual_address, _file_offset, _file_size, memory_size, _flags in segments:
        delta = rva - virtual_address
        if delta >= 0 and delta + size <= memory_size:
            return
    raise ValueError(f"RVA 0x{rva:x} (+0x{size:x}) is outside LOAD memory")


def resolve_dart_runtime_profile(
    image: bytes, segments: list[tuple[int, int, int, int, int]]
) -> dict[str, int]:
    """Mirror the production AOT structural resolver and require uniqueness."""

    def words(rva: int, count: int) -> list[int]:
        offset = rva_to_file_offset(rva, count * 4, segments, True)
        return list(struct.unpack_from(f"<{count}I", image, offset))

    def bl_target(rva: int, instruction: int) -> int | None:
        if instruction & 0xFC000000 != 0x94000000:
            return None
        immediate = instruction & 0x03FFFFFF
        if immediate & (1 << 25):
            immediate -= 1 << 26
        return rva + immediate * 4

    def load_x0_offset(instruction: int) -> int | None:
        if instruction & 0xFFC003FF != 0xF9400000:
            return None
        return ((instruction >> 10) & 0xFFF) * 8

    def pool_object(add: int, load: int, register: int) -> int | None:
        registers = (27 << 5) | register
        if add & 0xFF8003FF != 0x91000000 | registers:
            return None
        if load & 0xFFC003FF != 0xF9400000 | (register << 5) | register:
            return None
        value = (add >> 10) & 0xFFF
        if add & 0x00400000:
            value <<= 12
        value += ((load >> 10) & 0xFFF) * 8
        return value if value < 0x1000000 else None

    executable_ranges = [
        (virtual, virtual + file_size)
        for virtual, _file, file_size, _memory, flags in segments
        if flags & PF_X
    ]

    def candidates(prefix: tuple[int, ...], count: int):
        prefix_bytes = struct.pack(f"<{len(prefix)}I", *prefix)
        for start, end in executable_ranges:
            file_start = rva_to_file_offset(start, 1, segments, True)
            file_end = file_start + end - start
            position = file_start
            while True:
                position = image.find(prefix_bytes, position, file_end)
                if position < 0:
                    break
                rva = start + position - file_start
                position += 1
                if rva & 3 or rva + count * 4 > end:
                    continue
                yield rva, words(rva, count)

    drawer = []
    drawer_prefix = (0xA9BF79FD, 0xAA0F03FD, 0xD10021EF,
                     0xF81F83A2, 0xF9403F40)
    for rva, code in candidates(drawer_prefix, 35):
        all_apps = load_x0_offset(code[5])
        home = load_x0_offset(code[17])
        first_call = bl_target(rva + 44, code[11])
        second_call = bl_target(rva + 92, code[23])
        if (
            all_apps is not None and home is not None
            and all_apps == home + 0x10 and home != 0
            and code[6:9] == [0xF9402370, 0x6B10001F, 0x54000081]
            and code[12:17] == [0xAA0003E1, 0xF85F83A0, 0x6B01001F,
                                0x54000200, 0xF9403F40]
            and code[18:21] == [0xF9402370, 0x6B10001F, 0x54000081]
            and code[24:35] == [0xF85F83A1, 0x6B00003F, 0x910082D0,
                                0x9100C2D1, 0x9A911202, 0xAA0203E0,
                                0x14000002, 0x9100C2C0, 0xAA1D03EF,
                                0xA8C179FD, 0xD65F03C0]
            and first_call is not None and first_call == second_call
        ):
            drawer.append((rva, all_apps, home, first_call))
    if len(drawer) != 1:
        raise ValueError(f"Dart resolver drawer candidates: {len(drawer)}")
    drawer_rva, all_apps, home, unbox = drawer[0]

    transition = []
    transition_prefix = (0xA9BF79FD, 0xAA0F03FD,
                         0xD10041EF, 0xF81F83A1)
    for rva, code in candidates(transition_prefix, 12):
        branch_immediate = (code[4] >> 5) & 0x3FFF
        if branch_immediate & (1 << 13):
            branch_immediate -= 1 << 14
        branch_target = rva + 16 + branch_immediate * 4
        if (
            code[4] & 0xFFF8001F == 0x36200002
            and rva + 0x100 <= branch_target <= rva + 0x300
            and code[5] == 0xF9403F40
            and load_x0_offset(code[6]) is not None
            and code[7:11] == [0xF9402370, 0x6B10001F,
                               0x54000061, 0xF9403362]
            and bl_target(rva + 44, code[11]) == unbox
        ):
            transition.append(rva)
    if len(transition) != 1:
        raise ValueError(
            f"Dart resolver transition candidates: {len(transition)}"
        )

    enters = []
    enter_prefix = (0xA9BF79FD, 0xAA0F03FD, 0xD10041EF,
                    0xAA0103E2, 0xF81F83A1, 0xF9403F40)
    for rva, code in candidates(enter_prefix, 29):
        state = load_x0_offset(code[6])
        argument = pool_object(code[19], code[20], 3)
        shared = pool_object(code[21], code[22], 5)
        prepare = bl_target(rva + 64, code[16])
        publish = bl_target(rva + 96, code[24])
        if (
            state is not None and argument is not None and shared is not None
            and code[7:10] == [0xF9402370, 0x6B10001F, 0x54000061]
            and bl_target(rva + 44, code[11]) == unbox
            and code[14:16] == [0xF90001F0, 0xF9402B64]
            and code[17:19] == [0xAA0003E1, 0xF85F83A2]
            and (
                (argument + 8 == shared and code[23] == 0xF9438364)
                or code[23] & 0xFFC003FF == 0xF9400364
            )
            and code[25:29] == [0xAA1603E0, 0xAA1D03EF,
                                0xA8C179FD, 0xD65F03C0]
            and prepare is not None and publish is not None
        ):
            enters.append((rva, state, argument, shared, prepare, publish))

    exits = []
    exit_prefix = (0xA9BF79FD, 0xAA0F03FD, 0xD10041EF)
    for rva, code in candidates(exit_prefix, 44):
        state = load_x0_offset(code[21])
        shared = pool_object(code[34], code[35], 2)
        prepare = bl_target(rva + 124, code[31])
        publish = bl_target(rva + 156, code[39])
        if (
            code[3:6] == [0xB8413080, 0xB841F081, 0x8B1C8021]
            and state is not None and shared is not None
            and code[22:25] == [0xF9402370, 0x6B10001F, 0x54000061]
            and bl_target(rva + 104, code[26]) in (unbox, unbox - 0x3C)
            and code[29:31] == [0xF90001F0, 0xF9402B64]
            and code[32] == 0xAA0003E1
            and code[33] & 0xFFFFFFE0 == 0xF85F83A0
            and code[38] & 0xFFC003FF == 0xF9400364
            and code[40:44] == [0xAA1603E0, 0xAA1D03EF,
                                0xA8C179FD, 0xD65F03C0]
            and prepare is not None and publish is not None
        ):
            exits.append((rva, state, shared, prepare, publish))

    def same_family(enter, exit_):
        return (
            enter[1] != 0
            and enter[1] == exit_[1]
            and enter[3] != 0
            and enter[3:] == exit_[2:]
        )

    selected = []
    if (
        len(enters) == 1
        and len(exits) == 1
        and same_family(enters[0], exits[0])
    ):
        selected.append((enters[0], exits[0]))
    else:
        for enter in enters:
            for exit_ in exits:
                if not same_family(enter, exit_) or enter[2] < 8:
                    continue
                lower_siblings = sum(
                    1
                    for sibling in enters
                    if sibling != enter
                    and same_family(sibling, exit_)
                    and sibling[2] + 8 == enter[2]
                )
                if lower_siblings == 1:
                    selected.append((enter, exit_))
    if len(selected) != 1:
        raise ValueError(
            "Dart resolver Overview candidates/relationship: "
            f"enter={len(enters)} exit={len(exits)} selected={len(selected)}"
        )
    selected_enter, selected_exit = selected[0]

    editing_queries = []
    query_prefix = (0xA9BF79FD, 0xAA0F03FD, 0xD10041EF, 0xF81F83A1)
    for query_rva, query_code in candidates(query_prefix, 16):
        if (
            query_code[4] & 0xFFC00000 == 0xF9400000
            and query_code[6:9] == [0x6B16001F, 0x540001A1, 0xF9403F40]
            and query_code[9] & 0xFFC00000 == 0xF9400000
            and query_code[10:13]
            == [0xF9402370, 0x6B10001F, 0x54000061]
            and bl_target(query_rva + 14 * 4, query_code[14]) is not None
        ):
            editing_queries.append(query_rva)

    editing_refreshes = []
    notify_prefix = (0xA9BF79FD, 0xAA0F03FD, 0xD100A1EF,
                     0xF81F83A1, 0xD28000A1)
    for notify_rva, notify in candidates(notify_prefix, 63):
        refresh_rva = bl_target(notify_rva + 61 * 4, notify[61])
        if (
            bl_target(notify_rva + 5 * 4, notify[5]) is not None
            and notify[53:57] == [0xAA1603E0, 0xAA1D03EF,
                                  0xA8C179FD, 0xD65F03C0]
            and notify[60] == 0xF85F83A1
            and refresh_rva is not None
            and notify[62] == 0xAA0003E1
            and words(refresh_rva, 2) == [0xA9BF79FD, 0xAA0F03FD]
        ):
            editing_refreshes.append(refresh_rva)

    editing = []
    if len(editing_refreshes) == 1:
        editing_refresh = editing_refreshes[0]
        for editing_query in editing_queries:
            returns = []
            for caller in range(editing_refresh, editing_refresh + 0x500, 4):
                if bl_target(caller, words(caller, 1)[0]) == editing_query:
                    returns.append(caller + 4)
            if len(returns) == 2:
                editing.append((editing_refresh, editing_query,
                                returns[0], returns[1]))
    if len(editing) != 1:
        raise ValueError(
            "Dart resolver editing graph: "
            f"query={len(editing_queries)} refresh={len(editing_refreshes)} "
            f"graph={len(editing)}"
        )
    _editing_refresh, editing_query, editing_return_a, editing_return_b = editing[0]

    return_tail = (0xAA1D03EF, 0xA8C179FD, 0xD65F03C0)

    def return_epilogues(start: int, span: int, first: int) -> list[int]:
        result = []
        for rva in range(start, start + span, 4):
            if words(rva, 4) == [first, *return_tail]:
                result.append(rva)
        return result

    drawer_callers = []
    for start, end in executable_ranges:
        executable_words = words(start, (end - start) // 4)
        for index, instruction in enumerate(executable_words):
            caller = start + index * 4
            if bl_target(caller, instruction) == transition[0]:
                drawer_callers.append(caller)
    drawer_caller_prefix = [
        0xA9BF79FD, 0xAA0F03FD, 0xF9400FA0,
        0xB8417001, 0x8B1C8021, 0xB840F020,
        0x8B1C8000, 0xAA0003E1, 0xF9400BA2,
    ]
    drawer_epilogue = (
        drawer_callers[0] + 4 if len(drawer_callers) == 1 else 0
    )
    enter_epilogue = selected_enter[0] + 25 * 4
    exit_epilogue = selected_exit[0] + 40 * 4
    editing_false_epilogues = return_epilogues(
        editing_query, 0x200, 0x9100C2C0
    )
    editing_true_epilogues = (
        return_epilogues(
            editing_query,
            editing_false_epilogues[0] - editing_query,
            0x910082C0,
        )
        if len(editing_false_epilogues) == 1
        else []
    )
    if (
        len(drawer_callers) != 1
        or words(drawer_callers[0] - len(drawer_caller_prefix) * 4,
                 len(drawer_caller_prefix)) != drawer_caller_prefix
        or words(drawer_epilogue, 4) != [0xAA1603E0, *return_tail]
        or words(enter_epilogue, 4) != [0xAA1603E0, *return_tail]
        or words(exit_epilogue, 4) != [0xAA1603E0, *return_tail]
        or not 1 <= len(editing_true_epilogues) <= 4
        or len(editing_false_epilogues) != 1
    ):
        raise ValueError(
            "Dart resolver return epilogues: "
            f"drawer_callers={len(drawer_callers)} "
            f"editing_true={len(editing_true_epilogues)} "
            f"editing_false={len(editing_false_epilogues)}"
        )
    return {
        "progress_end_offset": drawer_rva,
        "transition_complete_offset": transition[0],
        "all_apps_state_slot_offset": all_apps,
        "home_state_slot_offset": home,
        "overview_enter_offset": selected_enter[0],
        "overview_exit_offset": selected_exit[0],
        "editing_query_offset": editing_query,
        "editing_query_return_offset_a": editing_return_a,
        "editing_query_return_offset_b": editing_return_b,
        "drawer_transition_epilogue_offset": drawer_epilogue,
        "overview_enter_epilogue_offset": enter_epilogue,
        "overview_exit_epilogue_offset": exit_epilogue,
        "editing_true_epilogue_offsets": editing_true_epilogues,
        "editing_false_epilogue_offsets": editing_false_epilogues,
    }


def verify_bytes(
    profile_id: str,
    label: str,
    rva: int,
    expected: bytes,
    image: bytes,
    segments: list[tuple[int, int, int, int, int]],
) -> None:
    file_offset = rva_to_file_offset(
        rva, len(expected), segments, require_executable=True
    )
    actual = image[file_offset : file_offset + len(expected)]
    if actual != expected:
        raise ValueError(
            f"{profile_id} {label} mismatch at RVA 0x{rva:x}: "
            f"expected {expected.hex()}, got {actual.hex()}"
        )


def verify_profile(profile: dict, library_path: Path) -> None:
    image = library_path.read_bytes()
    profile_id = profile["id"]
    digest = hashlib.sha256(image).hexdigest()
    if digest.lower() != profile["library_sha256"].lower():
        raise ValueError(
            f"{profile_id} library SHA-256 mismatch: expected "
            f"{profile['library_sha256']}, got {digest}"
        )
    segments = load_segments(image)
    for fingerprint in profile["identity_fingerprints"]:
        verify_bytes(
            profile_id,
            f"identity/{fingerprint['name']}",
            parse_int(fingerprint["offset"]),
            parse_bytes(fingerprint["bytes"]),
            image,
            segments,
        )
    for label in (
        "side_handler",
        "pointer_handler",
        "touch_processor",
        "drawer_state",
    ):
        hook = profile.get(label)
        if hook is None:
            continue
        expected = parse_bytes(hook["bytes"])
        verify_bytes(
            profile_id,
            label,
            parse_int(hook["offset"]),
            expected,
            image,
            segments,
        )
    print(f"{profile_id}: PASS {library_path} sha256={digest}")


def verify_dart_profile(
    profile: dict, reference: dict, library_path: Path
) -> None:
    image = library_path.read_bytes()
    digest = hashlib.sha256(image).hexdigest()
    if digest.lower() != reference["library_sha256"].lower():
        raise ValueError(
            f"{profile['id']} Dart library SHA-256 mismatch: expected "
            f"{reference['library_sha256']}, got {digest}"
        )
    segments = load_segments(image)
    verify_bytes(
        profile["id"], "dart_drawer_state/progress_end",
        parse_int(reference["progress_end_offset"]),
        parse_bytes(reference["progress_end_bytes"]), image, segments,
    )
    verify_bytes(
        profile["id"], "dart_drawer_state/transition_complete",
        parse_int(reference["transition_complete_offset"]),
        parse_bytes(reference["transition_complete_bytes"]), image, segments,
    )
    build_offset = rva_to_file_offset(
        parse_int(reference["snapshot_build_id_offset"]),
        len(parse_bytes(reference["snapshot_build_id_bytes"])), segments,
    )
    expected_build_id = parse_bytes(reference["snapshot_build_id_bytes"])
    if image[build_offset : build_offset + len(expected_build_id)] != expected_build_id:
        raise ValueError(f"{profile['id']} Dart snapshot build ID mismatch")
    for label in ("enter", "exit"):
        verify_bytes(
            profile["id"], f"dart_overview_state/{label}",
            parse_int(reference[f"overview_{label}_offset"]),
            parse_bytes(reference[f"overview_{label}_bytes"]), image, segments,
        )
    verify_bytes(
        profile["id"], "dart_editing_state/query",
        parse_int(reference["editing_query_offset"]),
        parse_bytes(reference["editing_query_bytes"]), image, segments,
    )
    resolved = resolve_dart_runtime_profile(image, segments)
    expected = {
        "progress_end_offset": parse_int(reference["progress_end_offset"]),
        "transition_complete_offset": parse_int(
            reference["transition_complete_offset"]
        ),
        "all_apps_state_slot_offset": parse_int(
            reference["all_apps_state_slot_offset"]
        ),
        "home_state_slot_offset": parse_int(
            reference["home_state_slot_offset"]
        ),
        "overview_enter_offset": parse_int(
            reference["overview_enter_offset"]
        ),
        "overview_exit_offset": parse_int(
            reference["overview_exit_offset"]
        ),
        "editing_query_offset": parse_int(
            reference["editing_query_offset"]
        ),
        "editing_query_return_offset_a": parse_int(
            reference["editing_query_return_offset_a"]
        ),
        "editing_query_return_offset_b": parse_int(
            reference["editing_query_return_offset_b"]
        ),
    }
    if resolved != expected:
        raise ValueError(
            f"{profile['id']} Dart runtime resolver mismatch: "
            f"expected {expected}, got {resolved}"
        )
    corrupted = bytearray(image)
    drawer_file_offset = rva_to_file_offset(
        resolved["progress_end_offset"], 4, segments, True
    )
    corrupted[drawer_file_offset] ^= 0x01
    try:
        resolve_dart_runtime_profile(bytes(corrupted), segments)
    except ValueError:
        pass
    else:
        raise ValueError(
            f"{profile['id']} Dart resolver accepted a corrupted unique drawer"
        )
    print(f"{profile['id']}: PASS {library_path} sha256={digest}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).with_name("launcher-profiles.json"),
    )
    parser.add_argument(
        "--library",
        action="append",
        default=[],
        metavar="PROFILE_ID=PATH",
    )
    parser.add_argument(
        "--dart-library",
        action="append",
        default=[],
        metavar="PROFILE_ID=PATH",
    )
    parser.add_argument(
        "--resolve-dart-library",
        action="append",
        default=[],
        type=Path,
        metavar="PATH",
        help=(
            "resolve and print an arbitrary mapped-Dart ELF without a "
            "manifest binding"
        ),
    )
    args = parser.parse_args()
    if (
        not args.library
        and not args.dart_library
        and not args.resolve_dart_library
    ):
        parser.error(
            "at least one --library, --dart-library, or "
            "--resolve-dart-library input is required"
        )
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    profiles = {profile["id"]: profile for profile in manifest["profiles"]}
    profile_references = {
        profile["id"]: profile
        for profile in (
            manifest["profiles"]
            + manifest.get("runtime_reference_profiles", [])
        )
    }
    dart_references = {
        profile["id"]: profile
        for profile in manifest.get("dart_runtime_reference_profiles", [])
    }
    for binding in args.library:
        profile_id, separator, raw_path = binding.partition("=")
        if not separator or profile_id not in profiles:
            raise ValueError(f"invalid --library binding: {binding}")
        verify_profile(profiles[profile_id], Path(raw_path))
    for binding in args.dart_library:
        profile_id, separator, raw_path = binding.partition("=")
        if (
            not separator
            or profile_id not in profile_references
            or profile_id not in dart_references
        ):
            raise ValueError(f"invalid --dart-library binding: {binding}")
        verify_dart_profile(
            profile_references[profile_id],
            dart_references[profile_id],
            Path(raw_path),
        )
    for path in args.resolve_dart_library:
        image = path.read_bytes()
        resolved = resolve_dart_runtime_profile(image, load_segments(image))
        print(
            json.dumps(
                {"library": str(path), "resolved": resolved},
                sort_keys=True,
            )
        )


if __name__ == "__main__":
    main()
