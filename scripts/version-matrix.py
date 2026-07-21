#!/usr/bin/env python3
"""Print all supported Minecraft versions as a GitHub Actions matrix."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSIONS_FILE = ROOT / "versions" / "supported.txt"


def load_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise SystemExit(f"Invalid property in {path}: {raw_line}")
        properties[key.strip()] = value.strip()
    return properties


def main() -> None:
    entries: list[dict[str, object]] = []
    for raw_line in VERSIONS_FILE.read_text(encoding="utf-8").splitlines():
        minecraft = raw_line.strip()
        if not minecraft or minecraft.startswith("#"):
            continue
        profile_path = ROOT / "versions" / f"{minecraft}.properties"
        if not profile_path.is_file():
            raise SystemExit(f"Missing version profile: {profile_path}")
        profile = load_properties(profile_path)
        if profile.get("minecraft_version") != minecraft:
            raise SystemExit(f"{profile_path} has the wrong minecraft_version")
        try:
            java = int(profile.get("build_java_version", profile["java_version"]))
        except (KeyError, ValueError) as error:
            raise SystemExit(f"Invalid java_version in {profile_path}") from error
        generation = profile.get("build_generation")
        if generation not in {"doggyman", "ping_9", "unlimited_damage"}:
            raise SystemExit(f"Invalid build_generation in {profile_path}")
        entries.append({"minecraft": minecraft, "java": java, "generation": generation})

    if not entries:
        raise SystemExit("The supported version list is empty")
    print(json.dumps({"include": entries}, separators=(",", ":")))


if __name__ == "__main__":
    main()
