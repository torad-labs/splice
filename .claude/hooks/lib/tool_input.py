"""Helpers for reading the PreToolUse / Bash data envelope.

Adapted from kandi/qgre lib/tool_input.py for torad-fleet: the brain is
TypeScript under src/ + the Kotlin :domain source sets (both compile into the
scanned dist/); host/ is the unscanned process-exec seam. No Android/iOS source
sets. Tool data shapes (Claude Code hook contract):

  Write     -> tool_input.file_path, tool_input.content
  Edit      -> tool_input.file_path, tool_input.old_string, tool_input.new_string
  MultiEdit -> tool_input.file_path, tool_input.edits[].old_string / new_string
  Bash      -> tool_input.command
"""
from __future__ import annotations

import re
from typing import Iterator

# D16: a shell command's quoted spans are DATA (prose, a note's text, a commit -m, a script
# literal), not command structure. Guards that match tool/marker tokens must run on the skeleton
# (quoted spans blanked) so prose naming a line-editor or a write verb never triggers them — the
# D13 family, shared by module 11 (astgrep-first) and module 07 (enforcement-layer Bash scan).
_QUOTED_SPAN = re.compile(r"'[^']*'|\"[^\"]*\"")


def unquoted_skeleton(command: str) -> str:
    """`command` with single/double-quoted spans replaced by spaces. Imperfect on nested/escaped
    quotes (rare in guarded shapes); the same-checker commit re-run backstops the exotic."""
    return _QUOTED_SPAN.sub(" ", command)


def file_path_of(data: dict) -> str:
    tool_input = data.get("tool_input") or {}
    if isinstance(tool_input, dict):
        return str(tool_input.get("file_path") or "")
    return ""


def command_of(data: dict) -> str:
    tool_input = data.get("tool_input") or {}
    if isinstance(tool_input, dict):
        return str(tool_input.get("command") or "")
    return ""


def proposed_contents(data: dict) -> Iterator[str]:
    """Yield each post-edit content fragment proposed by the tool call."""
    tool_name = data.get("tool_name") or ""
    tool_input = data.get("tool_input") or {}
    if not isinstance(tool_input, dict):
        return

    if tool_name == "Write":
        content = tool_input.get("content")
        if isinstance(content, str):
            yield content
    elif tool_name == "Edit":
        new_s = tool_input.get("new_string")
        if isinstance(new_s, str):
            yield new_s
    elif tool_name == "MultiEdit":
        edits = tool_input.get("edits") or []
        if isinstance(edits, list):
            for edit in edits:
                if isinstance(edit, dict):
                    new_s = edit.get("new_string")
                    if isinstance(new_s, str):
                        yield new_s


def is_write_or_edit(data: dict) -> bool:
    return data.get("tool_name") in ("Write", "Edit", "MultiEdit")


def is_bash(data: dict) -> bool:
    return data.get("tool_name") == "Bash"


def has_kt_extension(file_path: str) -> bool:
    return file_path.endswith(".kt")


def has_ts_extension(file_path: str) -> bool:
    return file_path.endswith((".ts", ".mts", ".cts", ".tsx"))


# Source that compiles / bundles into the scanned dist/ (TS brain + Kotlin).
_COMPILED_SOURCE_EXT: tuple[str, ...] = (
    ".ts", ".mts", ".cts", ".tsx", ".js", ".mjs", ".cjs", ".jsx", ".kt",
)


def has_compiled_source_ext(file_path: str) -> bool:
    return file_path.endswith(_COMPILED_SOURCE_EXT)


def _seg(file_path: str) -> str:
    """Guarantee a leading slash so `/host/`-style segment fragments match both
    absolute paths (/…/host/x) and relative ones (host/x)."""
    return file_path if file_path.startswith("/") else "/" + file_path


def _project_relative_parts(file_path: str) -> list[str]:
    parts = [part for part in file_path.replace("\\", "/").split("/") if part]
    for index in range(len(parts) - 1):
        if parts[index : index + 2] == ["plugins", "fleet"]:
            return parts[index + 2 :]
    return parts


# Generated / vendored — the whole hook chain skips these (mirrors kandi's
# is_path_exempt, repurposed: build artifacts, not an AI-SDK port).
_EXEMPT_PATH_FRAGMENTS: tuple[str, ...] = (
    "/dist/", "/build/", "/node_modules/", "/.gradle/",
)


def is_path_exempt(file_path: str) -> bool:
    return any(frag in _seg(file_path) for frag in _EXEMPT_PATH_FRAGMENTS)


# Test source sets — the scan-clean seam rules target production source that
# ships in dist/, not test fakes/fixtures.
_TEST_PATH_FRAGMENTS: tuple[str, ...] = (
    "/test/", "/commonTest/", "/jvmTest/", "/jsTest/",
)


def is_test_path(file_path: str) -> bool:
    return any(frag in _seg(file_path) for frag in _TEST_PATH_FRAGMENTS)


# The host/ helper is a standalone, NEVER-scanned Node process — the one place
# child_process is legal. It is the seam the scan-clean rules exempt.
_HOST_SEAM_FRAGMENTS: tuple[str, ...] = (
    "/host/",
)


def is_host_seam(file_path: str) -> bool:
    return any(frag in _seg(file_path) for frag in _HOST_SEAM_FRAGMENTS)


def targets_scanned_dist(data: dict) -> bool:
    """True iff this Write/Edit lands in source that compiles into the scanned
    dist/ — i.e. production TS/JS under the plugin src/ seam or production
    Kotlin source sets, not host/, scripts, e2e, tests, or generated files."""
    file_path = file_path_of(data)
    if not (is_write_or_edit(data) and has_compiled_source_ext(file_path)):
        return False
    if is_path_exempt(file_path) or is_test_path(file_path) or is_host_seam(file_path):
        return False

    rel_parts = _project_relative_parts(file_path)
    if has_kt_extension(file_path):
        return "src" in rel_parts and ("commonMain" in rel_parts or "jsMain" in rel_parts)
    return bool(rel_parts) and rel_parts[0] == "src"
