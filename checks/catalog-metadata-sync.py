#!/usr/bin/env python3
"""Fail when the version catalog outruns dependency-verification metadata.

WHY THIS EXISTS (PR #91, and every gradle Dependabot PR before it): Dependabot can edit
gateway/gradle/libs.versions.toml but cannot run the metadata regeneration, so every catalog bump
arrives with gradle/verification-metadata.xml still pinning the OLD versions. With
verify-metadata=true that is a guaranteed red — but it surfaces six minutes into the gradle leg
of the gate, as a wall of "Dependency verification failed" noise. This check states the same
fact statically, in under a second, with the remedy attached.

What is checked, per catalog table:
  [libraries]  every entry carrying a resolvable version must appear in the metadata as a
               component (group, name, version). Versionless entries (BOM riders) are skipped.
  [plugins]    checked as their marker artifact: (id, id + ".gradle.plugin", version). A plugin
               applied by bare id inside build-logic never resolves its marker, so the obligation
               degrades to version presence (kotlin-serialization is that case today).
  [versions]   keys referenced by neither table are FLOOR pins (netty-style: declared so the
               resolver and Dependabot have a line to hold/bump, materialised only as
               transitives). No (group, name) is derivable statically, so the obligation is
               presence: at least one metadata component at that version. A floor that matches
               nothing is either an unregenerated bump or an inert floor that should be dropped —
               both worth a red.

Transitive drift is out of scope by design: it cannot be seen statically, and the remediation
below repins transitives and directs anyway. Unparseable catalog shapes are a loud death, never
a silent skip — a skipped entry is exactly how drift would hide (brain #924).

Usage:
    python3 checks/catalog-metadata-sync.py [catalog.toml] [verification-metadata.xml]
"""
from __future__ import annotations

import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parent.parent
NS = "{https://schema.gradle.org/dependency-verification}"

REMEDY = """\
catalog-metadata-sync: gateway/gradle/libs.versions.toml declares versions that
gradle/verification-metadata.xml does not pin. Regenerate from the gateway directory —
BOTH passes, the shadowJar license pass fetches poms that `check` alone never resolves:

    ./gradlew --write-verification-metadata sha256 clean check
    ./gradlew --write-verification-metadata sha256 :app:shadowJar --no-daemon --no-parallel

then commit the regenerated gradle/verification-metadata.xml (precedent: PR #91)."""


def die(msg: str) -> NoReturn:
    print(f"catalog-metadata-sync: {msg}", file=sys.stderr)
    raise SystemExit(2)


def version_of(key: str, entry: dict, versions: dict[str, str], used: set[str]) -> str | None:
    """Resolve an entry's version to a literal, None when legitimately absent, death otherwise."""
    v = entry.get("version")
    if v is None:
        return None
    if isinstance(v, str):
        return v
    if isinstance(v, dict) and isinstance(v.get("ref"), str):
        ref = v["ref"]
        if ref not in versions:
            die(f"{key}: version.ref {ref!r} not in [versions]")
        used.add(ref)
        return versions[ref]
    die(f"{key}: unsupported version shape {v!r} — extend this checker, do not skip")


def main() -> int:
    catalog_path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "gateway/gradle/libs.versions.toml"
    metadata_path = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "gateway/gradle/verification-metadata.xml"

    catalog = tomllib.loads(catalog_path.read_text())
    versions = catalog.get("versions", {})
    for key, v in versions.items():
        if not isinstance(v, str):
            die(f"[versions] {key}: rich version {v!r} — extend this checker, do not skip")

    components: set[tuple[str, str, str]] = set()
    for c in ET.parse(metadata_path).getroot().iter(f"{NS}component"):
        components.add((c.get("group", ""), c.get("name", ""), c.get("version", "")))
    pinned_versions = {v for _, _, v in components}

    used: set[str] = set()
    missing: list[str] = []

    for key, entry in catalog.get("libraries", {}).items():
        if isinstance(entry, str):
            parts = entry.split(":")
            if len(parts) != 3:
                die(f"libraries.{key}: unsupported shorthand {entry!r}")
            group, name, version = parts
        elif isinstance(entry, dict):
            module = entry.get("module")
            if isinstance(module, str) and module.count(":") == 1:
                group, name = module.split(":")
            elif isinstance(entry.get("group"), str) and isinstance(entry.get("name"), str):
                group, name = entry["group"], entry["name"]
            else:
                die(f"libraries.{key}: no module/group+name — extend this checker, do not skip")
            version = version_of(f"libraries.{key}", entry, versions, used)
            if version is None:
                continue  # BOM rider: version supplied at resolution time, nothing to compare
        else:
            die(f"libraries.{key}: unsupported entry {entry!r}")
        if (group, name, version) not in components:
            missing.append(f"libraries.{key}: {group}:{name}:{version} not pinned in metadata")

    for key, entry in catalog.get("plugins", {}).items():
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            die(f"plugins.{key}: unsupported entry {entry!r}")
        version = version_of(f"plugins.{key}", entry, versions, used)
        if version is None:
            continue
        marker = (entry["id"], entry["id"] + ".gradle.plugin", version)
        # A plugin requested through the plugins DSL resolves its marker; one applied by bare id
        # inside build-logic (implementation jar on that classpath) never does, so regeneration
        # cannot pin a marker for it. Degrade to version presence — an unregenerated bump still
        # has no component at the new version and stays red.
        if marker not in components and version not in pinned_versions:
            missing.append(
                f"plugins.{key}: {marker[0]}:{marker[1]}:{version} (marker) not pinned, "
                f"and no component at {version}"
            )

    for key in versions.keys() - used:
        if versions[key] not in pinned_versions:
            missing.append(
                f"[versions] {key} = \"{versions[key]}\": floor version matches no pinned component "
                "(unregenerated bump, or an inert floor to drop)"
            )

    if missing:
        for line in sorted(missing):
            print(f"  {line}")
        print(REMEDY)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
