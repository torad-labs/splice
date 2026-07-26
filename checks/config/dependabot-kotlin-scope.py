#!/usr/bin/env python3
"""checks/config/dependabot-kotlin-scope.py — fail-closed guard for the Kotlin ignore block.

The CodeQL Kotlin extractor block (#18/#37) must freeze the Kotlin compiler/toolchain
only. A naive glob like `org.jetbrains.kotlin*` also swallows `org.jetbrains.kotlinx.*`
(kover, coroutines, serialization), silently freezing independently-versioned kotlinx
libraries. This script asserts the ignore block is narrow enough to block the toolchain
and wide enough to release kotlinx.
"""
import fnmatch
import sys

# Fail-closed: a missing parser is a red check, not a silent skip.
try:
    import yaml
except ImportError as exc:
    print(
        "dependabot-kotlin-scope: FAIL — PyYAML missing; install it or run in a CI image that has it",
        file=sys.stderr,
    )
    sys.exit(1)


def main():
    with open(".github/dependabot.yml") as f:
        dependabot = yaml.safe_load(f)

    gradle_update = None
    for update in dependabot.get("updates", []):
        if update.get("package-ecosystem") == "gradle":
            gradle_update = update
            break

    if gradle_update is None:
        print("dependabot-kotlin-scope: FAIL — no gradle update found", file=sys.stderr)
        sys.exit(1)

    ignores = gradle_update.get("ignore", [])
    globs = [entry["dependency-name"] for entry in ignores if "dependency-name" in entry]

    if not globs:
        print("dependabot-kotlin-scope: FAIL — no gradle ignore globs found", file=sys.stderr)
        sys.exit(1)

    # --- assertion 1: kotlinx library/plugin names must NOT match any gradle ignore glob ---
    kotlinx_names = [
        "org.jetbrains.kotlinx.kover",
        "org.jetbrains.kotlinx.kover:org.jetbrains.kotlinx.kover.gradle.plugin",
        "org.jetbrains.kotlinx:kover-gradle-plugin",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "org.jetbrains.kotlinx:kotlinx-serialization-json",
    ]
    for name in kotlinx_names:
        for glob in globs:
            if fnmatch.fnmatchcase(name, glob):
                print(
                    f"dependabot-kotlin-scope: FAIL — ignore glob '{glob}' swallows kotlinx name '{name}'",
                    file=sys.stderr,
                )
                sys.exit(1)
    print("dependabot-kotlin-scope: all kotlinx names released")

    # --- assertion 2: Kotlin toolchain/compiler names MUST still be blocked by at least one glob ---
    toolchain_names = [
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-compiler-embeddable",
        "org.jetbrains.kotlin.jvm",
        "org.jetbrains.kotlin.plugin.serialization",
        "org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin",
        "org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin",
    ]
    for name in toolchain_names:
        blocked = any(fnmatch.fnmatchcase(name, glob) for glob in globs)
        if not blocked:
            print(
                f"dependabot-kotlin-scope: FAIL — toolchain name '{name}' is not blocked by any ignore glob",
                file=sys.stderr,
            )
            sys.exit(1)
    print("dependabot-kotlin-scope: all toolchain names blocked")

    # --- assertion 3: grouping contract must remain intact so released deps arrive as one PR ---
    group = gradle_update.get("groups", {}).get("gradle-minor-patch")
    if group is None:
        print("dependabot-kotlin-scope: FAIL — gradle-minor-patch group missing", file=sys.stderr)
        sys.exit(1)
    if group.get("applies-to") != "version-updates":
        print(
            f"dependabot-kotlin-scope: FAIL — applies-to is {group.get('applies-to')!r}, expected 'version-updates'",
            file=sys.stderr,
        )
        sys.exit(1)
    if group.get("patterns") != ["*"]:
        print(
            f"dependabot-kotlin-scope: FAIL — patterns is {group.get('patterns')!r}, expected ['*']",
            file=sys.stderr,
        )
        sys.exit(1)
    if group.get("update-types") != ["minor", "patch"]:
        print(
            f"dependabot-kotlin-scope: FAIL — update-types is {group.get('update-types')!r}, expected ['minor', 'patch']",
            file=sys.stderr,
        )
        sys.exit(1)
    print("dependabot-kotlin-scope: gradle-minor-patch grouping contract intact")

    print("dependabot-kotlin-scope: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
