#!/usr/bin/env python3
"""
Generate extra Android IR databases from pinned Flipper repositories.

Sources:
  - Lucaslhm/Flipper-IRDB (CC0-1.0): TV, AC and projector useful commands.
  - flipperdevices/IRDB (MIT): extra TV and projector commands.

The universal scan only consumes POWER/OFF extras. The larger library is used
by the manual code/brand browser so non-power commands are never sent during a
shutdown sweep.
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from generate_flipper_power_database import (
    GENERIC_POWER_NAMES,
    OFF_NAMES,
    kotlin_escape,
    normalize_name,
    parse_ir_records,
    power_priority,
    record_to_raw,
)

COMMUNITY_DIRS = {
    "televisions": ("TVs", "TVs"),
    "airConditioners": ("ACs", "ACs"),
    "projectors": ("Projectors", "Projectors"),
}

OFFICIAL_DIRS = {
    "televisions": ("database/categories/TVs", "TVs"),
    "projectors": ("database/categories/Projector", "Projectors"),
}

TV_COMMANDS = {
    "power", "pwr", "power_toggle", "powertoggle", "toggle_power", "togglepower",
    "standby", "off", "power_off", "poweroff", "turn_off", "turnoff",
    "mute", "vol_up", "volume_up", "vol_down", "volume_down", "vol_dn",
    "ch_next", "ch_prev", "channel_up", "channel_down", "channel_next",
    "channel_prev", "input", "source", "input_next", "source_next", "av",
    "menu", "home", "ok", "enter", "up", "down", "left", "right", "back",
    "exit", "sleep", "info",
}

PROJECTOR_COMMANDS = TV_COMMANDS | {
    "freeze", "blank", "keystone_up", "keystone_down", "aspect", "zoom_up",
    "zoom_down", "eco",
}

AC_COMMANDS = {
    "power", "pwr", "power_toggle", "powertoggle", "toggle_power", "togglepower",
    "standby", "off", "power_off", "poweroff", "turn_off", "turnoff",
    "temp_up", "temp_down", "temperature_up", "temperature_down",
    "mode", "fan", "fan_up", "fan_down", "fan_speed", "swing",
    "swing_up", "swing_down", "cool_hi", "cool_lo", "heat_hi", "heat_lo",
    "cool", "heat", "dry", "dh", "auto", "turbo", "sleep",
}

LIBRARY_COMMANDS = {
    "televisions": TV_COMMANDS,
    "airConditioners": AC_COMMANDS,
    "projectors": PROJECTOR_COMMANDS,
}


def library_priority(category: str, name: str):
    normalized = normalize_name(name)
    power = power_priority(name)
    if power is not None:
        return power
    if normalized in LIBRARY_COMMANDS[category]:
        return 10
    return None


def collect_sources(source_specs, selector):
    candidates = []
    unsupported = Counter()

    for source_rank, (base, logical_dir) in enumerate(source_specs):
        if not base.exists():
            continue

        for path in sorted(base.rglob("*.ir")):
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue

            rel = path.relative_to(base).as_posix()

            for record in parse_ir_records(text):
                priority = selector(record.get("name", ""))
                if priority is None:
                    continue

                raw = record_to_raw(record)
                if raw is None:
                    if record.get("type", "").strip().lower() == "parsed":
                        unsupported[record.get("protocol", "UNKNOWN")] += 1
                    continue

                frequency, durations = raw
                if not (15000 <= frequency <= 60000):
                    continue
                if len(durations) < 2 or len(durations) > 10000:
                    continue
                if any(value <= 0 or value > 5_000_000 for value in durations):
                    continue
                if sum(durations) >= 2_000_000:
                    unsupported["RAW_OVER_2S"] += 1
                    continue

                signal_name = record.get("name", "Power")
                signal_id = f"flipper:{logical_dir}/{rel}#{signal_name}"
                candidates.append((
                    priority,
                    source_rank,
                    rel.lower(),
                    signal_name.lower(),
                    signal_id,
                    frequency,
                    durations,
                ))

    candidates.sort(key=lambda item: item[:4])

    seen = set()
    result = []
    for _, _, _, _, signal_id, frequency, durations in candidates:
        key = (frequency, tuple(durations))
        if key in seen:
            continue
        seen.add(key)
        result.append((signal_id, frequency, durations))

    return result, unsupported


def render_list(lines, property_name, entries):
    chunk_size = 4
    chunks = [
        entries[index:index + chunk_size]
        for index in range(0, len(entries), chunk_size)
    ]

    lines.append(f"    val {property_name}: List<IrCode> by lazy {{")
    if not chunks:
        lines.append("        emptyList()")
    else:
        lines.append("        buildList {")
        for index in range(len(chunks)):
            lines.append(f"            addAll({property_name}Chunk{index}())")
        lines.append("        }")
    lines.append("    }")
    lines.append("")

    for chunk_index, chunk in enumerate(chunks):
        lines.append(
            f"    private fun {property_name}Chunk{chunk_index}(): List<IrCode> = listOf("
        )
        for signal_id, frequency, durations in chunk:
            nums = ",".join(str(value) for value in durations)
            lines.append(
                f'        IrCode("{kotlin_escape(signal_id)}", '
                f'{frequency}, intArrayOf({nums}).toList()),'
            )
        lines.append("    )")
        lines.append("")


def render_kotlin(power, library):
    lines = [
        "// AUTO-GENERATED by scripts/generate_extended_ir_database.py",
        "// Community source: Lucaslhm/Flipper-IRDB (CC0-1.0)",
        "// Official source: flipperdevices/IRDB (MIT)",
        "",
        "package com.gokuencinar.iruniversal.ir",
        "",
        "object GeneratedExtendedIrDatabase {",
    ]

    render_list(lines, "televisionPowerExtras", power["televisions"])
    render_list(lines, "projectorPowerExtras", power["projectors"])
    render_list(lines, "televisionLibrary", library["televisions"])
    render_list(lines, "airConditionerLibrary", library["airConditioners"])
    render_list(lines, "projectorLibrary", library["projectors"])

    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("community_root", type=Path)
    parser.add_argument("official_root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    power = {}
    library = {}
    unsupported = Counter()

    for category, (dirname, logical_dir) in COMMUNITY_DIRS.items():
        specs = [(args.community_root / dirname, logical_dir)]
        official = OFFICIAL_DIRS.get(category)
        if official is not None:
            official_dir, official_logical = official
            specs.append((args.official_root / official_dir, official_logical))

        power_entries, power_unsupported = collect_sources(
            specs,
            lambda name: power_priority(name),
        )
        library_entries, library_unsupported = collect_sources(
            specs,
            lambda name, current=category: library_priority(current, name),
        )

        power[category] = power_entries
        library[category] = library_entries
        unsupported.update(power_unsupported)
        unsupported.update(library_unsupported)

        print(
            f"{category}: {len(power_entries)} POWER/OFF, "
            f"{len(library_entries)} useful library signals"
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_kotlin(power, library), encoding="utf-8")

    if unsupported:
        print(
            "Skipped unsupported parsed protocols:",
            ", ".join(
                f"{name}={count}"
                for name, count in unsupported.most_common()
            ),
        )

    print("Generated:", args.output)


if __name__ == "__main__":
    main()
