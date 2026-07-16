"""§manifest-single-channel — campaign ledgers are updated via manifest.py, never raw-edited.

Concept #945 §1b: dev/campaigns/manifest.py is the ONE sanctioned channel for
touching dev/campaigns/*.toml. Raw Edit/Write on an existing manifest collides
with concurrent agents (the CLI takes an flock), risks stripping the # comment
notes where decisions and resume pointers live, and dodges the tomllib
well-formedness gate that rolls back broken writes.

Creating a NEW campaign file via Write is allowed (that's how campaigns start);
everything after birth goes through the CLI. Style/discipline gate — fail-open.
"""
from __future__ import annotations

import pathlib
import sys
from typing import Optional

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent.parent))
from orchestrator.result import HookResult  # noqa: E402  # pyright: ignore[reportMissingImports]
from lib.tool_input import file_path_of, is_write_or_edit  # noqa: E402  # pyright: ignore[reportMissingImports]
from lib.astgrep_gate import find_project_root  # noqa: E402  # pyright: ignore[reportMissingImports]

MODULE_NAME = "08_manifest_single_channel"


def applies(data: dict) -> bool:
    return is_write_or_edit(data) and file_path_of(data).endswith(".toml")


def run(data: dict) -> Optional[HookResult]:
    file_path = file_path_of(data)
    root = find_project_root(file_path, data.get("cwd"))
    if root is None:
        return None

    p = pathlib.Path(file_path)
    if not p.is_absolute():
        p = root / file_path
    try:
        rel = p.resolve().relative_to(root).as_posix()
    except (OSError, ValueError):
        return None
    if not rel.startswith("dev/campaigns/"):
        return None

    if data.get("tool_name") == "Write" and not p.exists():
        return None  # birth of a new campaign manifest — allowed

    return HookResult(
        kind="block",
        payload=(
            "§manifest-single-channel — raw edit of a campaign ledger blocked\n\n"
            f"  target: {rel}\n\n"
            "The manifest is updated ONLY via the CLI (#945 §1b):\n"
            "  python3 dev/campaigns/manifest.py get <ID> | note <ID> \"text\" | "
            "set-status <ID> <todo|in_flight|done|verified> | add --id ... \n\n"
            "Raw edits collide with concurrent agents (the CLI flocks), can strip the "
            "# comment notes that carry decisions/resume pointers, and skip the tomllib "
            "rollback gate. If you need an operation the CLI lacks, extend manifest.py "
            "(that file is enforcement-layer, grant-gated) — do not hand-edit the ledger."
        ),
        module_name=MODULE_NAME,
    )
