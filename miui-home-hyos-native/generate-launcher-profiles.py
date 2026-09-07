#!/usr/bin/env python3
"""Generate the constexpr launcher profile registry from JSON."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def cpp_name(value: str) -> str:
    parts = [part for part in re.split(r"[^A-Za-z0-9]+", value) if part]
    if not parts:
        raise ValueError(f"cannot form a C++ identifier from {value!r}")
    return "".join(part[:1].upper() + part[1:] for part in parts)


def hex_literal(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"0x[0-9a-fA-F]+", value):
        raise ValueError(f"invalid hexadecimal offset: {value!r}")
    return value.lower() + "u"


def fingerprint_bytes(value: str) -> bytes:
    if not isinstance(value, str):
        raise ValueError(f"fingerprint must be a hexadecimal string: {value!r}")
    try:
        return bytes.fromhex(value)
    except ValueError as error:
        raise ValueError(f"invalid fingerprint bytes: {value!r}") from error


def add_byte_array(lines: list[str], name: str, value: str) -> bool:
    data = fingerprint_bytes(value)
    if not data:
        return False
    lines.append(f"inline constexpr uint8_t {name}[] = {{")
    for offset in range(0, len(data), 8):
        values = " ".join(f"0x{byte:02x}," for byte in data[offset : offset + 8])
        lines.append(f"        {values}")
    lines.extend(("};", ""))
    return True


def require_mapping(owner: dict, field: str, profile_id: str) -> dict:
    value = owner.get(field)
    if not isinstance(value, dict):
        raise ValueError(f"profile {profile_id} is missing object {field}")
    return value


def generate(manifest: dict) -> str:
    if manifest.get("schema_version") != 1:
        raise ValueError(
            f"unsupported launcher profile schema: {manifest.get('schema_version')!r}"
        )
    profiles = manifest.get("profiles")
    if not isinstance(profiles, list) or not profiles:
        raise ValueError("launcher profile manifest is empty")

    lines = [
        "// Generated from launcher-profiles.json. Do not edit.",
        "#pragma once",
        "",
        '#include "launcher_profiles.h"',
        "",
        "namespace miui_home_profiles {",
        "",
    ]
    profile_names: list[str] = []
    seen_ids: set[str] = set()

    for profile in profiles:
        if not isinstance(profile, dict):
            raise ValueError("every launcher profile must be an object")
        profile_id = profile.get("id")
        if (
            not isinstance(profile_id, str)
            or not re.fullmatch(r"[0-9A-Za-z._-]+", profile_id)
            or profile_id in seen_ids
        ):
            raise ValueError(f"invalid or duplicate launcher profile id: {profile_id!r}")
        seen_ids.add(profile_id)
        version_name = profile.get("version_name")
        if not isinstance(version_name, str) or not version_name:
            raise ValueError(f"profile {profile_id} has no version name")
        suffix = cpp_name(profile_id)
        profile_name = f"kProfile{suffix}"
        profile_names.append(profile_name)

        fingerprints = profile.get("identity_fingerprints")
        if not isinstance(fingerprints, list) or not fingerprints:
            raise ValueError(f"profile {profile_id} has no identity fingerprint")
        identity_entries: list[str] = []
        for index, fingerprint in enumerate(fingerprints):
            if not isinstance(fingerprint, dict):
                raise ValueError(f"profile {profile_id} has an invalid identity entry")
            fingerprint_name = cpp_name(str(fingerprint.get("name", "")))
            array_name = f"kIdentity{suffix}{fingerprint_name}{index}"
            if not add_byte_array(lines, array_name, fingerprint.get("bytes", "")):
                raise ValueError(f"empty identity fingerprint in profile {profile_id}")
            identity_entries.append(
                f"        {{{hex_literal(fingerprint.get('offset'))}, {array_name}, "
                f"sizeof({array_name})}},"
            )
        identity_name = f"kIdentityFingerprints{suffix}"
        lines.append(f"inline constexpr CodeFingerprint {identity_name}[] = {{")
        lines.extend(identity_entries)
        lines.extend(("};", ""))

        side = require_mapping(profile, "side_handler", profile_id)
        side_name = f"kSideHandlerPrologue{suffix}"
        if not add_byte_array(lines, side_name, side.get("bytes", "")):
            raise ValueError(f"profile {profile_id} has no side-handler fingerprint")

        topology_name = profile.get("hook_topology")
        legacy = topology_name == "legacy_three_stage"
        if not legacy and topology_name != "side_boundary_only":
            raise ValueError(
                f"unknown hook topology in profile {profile_id}: {topology_name!r}"
            )
        pointer_name = f"kPointerHandlerPrologue{suffix}"
        touch_name = f"kTouchProcessorPrologue{suffix}"
        pointer = profile.get("pointer_handler")
        touch = profile.get("touch_processor")
        if legacy:
            if not isinstance(pointer, dict) or not isinstance(touch, dict):
                raise ValueError(
                    f"legacy profile {profile_id} is missing a diagnostic hook"
                )
            if not add_byte_array(lines, pointer_name, pointer.get("bytes", "")):
                raise ValueError(
                    f"legacy profile {profile_id} is missing a diagnostic hook fingerprint"
                )
            if not add_byte_array(lines, touch_name, touch.get("bytes", "")):
                raise ValueError(
                    f"legacy profile {profile_id} is missing a diagnostic hook fingerprint"
                )

        contextual = profile.get("contextual_search")
        drawer = profile.get("drawer_state")
        drawer_name = f"kDrawerStateHandlerPrologue{suffix}"
        has_drawer = isinstance(drawer, dict)
        if has_drawer and not add_byte_array(
            lines, drawer_name, drawer.get("bytes", "")
        ):
            raise ValueError(
                f"profile {profile_id} has no drawer-state fingerprint"
            )
        contextual_handler_name = f"kContextualLongPressHandlerPrologue{suffix}"
        contextual_invoke_name = f"kContextualSearchInvokePrologue{suffix}"
        has_contextual = isinstance(contextual, dict)
        if has_contextual:
            if not add_byte_array(
                lines,
                contextual_handler_name,
                contextual.get("long_press_handler_bytes", ""),
            ):
                raise ValueError(
                    f"profile {profile_id} has no contextual long-press fingerprint"
                )
            if not add_byte_array(
                lines,
                contextual_invoke_name,
                contextual.get("invoke_bytes", ""),
            ):
                raise ValueError(
                    f"profile {profile_id} has no contextual-search invoke fingerprint"
                )
        pilfer = require_mapping(profile, "pilfer", profile_id)
        caller_name = f"kAcceptedPilferCaller{suffix}"
        has_caller = add_byte_array(
            lines, caller_name, pilfer.get("accepted_caller_bytes", "")
        )
        abi = require_mapping(profile, "abi", profile_id)
        ready_value = abi.get("runtime_ready_value")
        if not isinstance(ready_value, int) or not 0 <= ready_value <= 0xFFFFFFFF:
            raise ValueError(f"profile {profile_id} has an invalid runtime ready value")

        topology = (
            "BusinessHookTopology::kLegacyThreeStage"
            if legacy
            else "BusinessHookTopology::kSideBoundaryOnly"
        )
        lines.extend(
            (
                f"inline constexpr LauncherProfile {profile_name} = {{",
                f"        {json.dumps(profile_id)},",
                f"        {json.dumps(version_name)},",
                f"        {hex_literal(profile.get('image_span'))},",
                f"        {hex_literal(profile.get('entry_offset'))},",
                f"        {identity_name},",
                f"        sizeof({identity_name}) / sizeof({identity_name}[0]),",
                f"        {topology},",
                f"        {hex_literal(side.get('offset'))},",
                f"        {side_name},",
                f"        sizeof({side_name}),",
                f"        {hex_literal(side.get('edge_field_offset'))},",
                f"        {hex_literal(pointer.get('offset')) if legacy else '0u'},",
                f"        {pointer_name if legacy else 'nullptr'},",
                f"        sizeof({pointer_name})" + ("," if legacy else "")
                if legacy
                else "        0u,",
                f"        {hex_literal(touch.get('offset')) if legacy else '0u'},",
                f"        {touch_name if legacy else 'nullptr'},",
                f"        sizeof({touch_name})" + ("," if legacy else "")
                if legacy
                else "        0u,",
                f"        {hex_literal(touch.get('gesture_type_field_offset')) if legacy else '0u'},",
                f"        {hex_literal(drawer.get('offset')) if has_drawer else '0u'},",
                f"        {drawer_name if has_drawer else 'nullptr'},",
                f"        sizeof({drawer_name})," if has_drawer else "        0u,",
                "        0u,",
                "        0u,",
                "        nullptr,",
                "        0u,",
                "        0u,",
                "        nullptr,",
                "        0u,",
                "        0u,",
                "        nullptr,",
                "        0u,",
                "        0u,",
                "        0u,",
                "        0u,",
                "        nullptr,",
                "        0u,",
                "        0u,",
                "        nullptr,",
                "        0u,",
                "        0u,",
                "        nullptr,",
                "        0u,",
                "        0u,",
                "        0u,",
                f"        {hex_literal(contextual.get('long_press_handler_offset')) if has_contextual else '0u'},",
                f"        {contextual_handler_name if has_contextual else 'nullptr'},",
                f"        sizeof({contextual_handler_name})," if has_contextual else "        0u,",
                f"        {hex_literal(contextual.get('invoke_offset')) if has_contextual else '0u'},",
                f"        {contextual_invoke_name if has_contextual else 'nullptr'},",
                f"        sizeof({contextual_invoke_name})," if has_contextual else "        0u,",
                # XiaoAi visibility is intentionally runtime-resolved from the
                # imported Bundle call graph. Static manifests never supply it.
                "        0u,",
                f"        {hex_literal(pilfer.get('accepted_return_offset'))},",
                f"        {hex_literal(pilfer.get('home_return_offset'))},",
                f"        {caller_name if has_caller else 'nullptr'},",
                f"        sizeof({caller_name})," if has_caller else "        0u,",
                f"        {hex_literal(abi.get('rstring_vtable_offset'))},",
                f"        {hex_literal(abi.get('runtime_pointer_offset'))},",
                f"        {hex_literal(abi.get('runtime_state_offset'))},",
                f"        {ready_value}u,",
                # Runtime Dart resolver fills these exact return epilogues.
                "        0u,",
                "        0u,",
                "        0u,",
                "        0u,",
                "        nullptr,",
                "        0u,",
                "        0u,",
                "        0u,",
                "};",
                "",
            )
        )

    lines.append("inline constexpr const LauncherProfile* kProfiles[] = {")
    lines.extend(f"        &{name}," for name in profile_names)
    lines.extend(("};", "", "}  // namespace miui_home_profiles", ""))
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).with_name("launcher-profiles.json"),
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    output = generate(manifest)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
