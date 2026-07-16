"""§campaign-inflight — the ledger is the memory; surface in-flight items at turn end.

Concept #945: agents die, sessions compact — durable state lives in
dev/campaigns/*.toml. At Stop, list items still marked in_flight so the session
either (a) records what it just finished (`manifest.py set-status <ID> done` +
`note`), or (b) consciously confirms the item is running on a PEER session
(fleet-builder) and turn-end is expected.

WARN, not block, by design: this orchestration model dispatches work to peer
sessions and legitimately ends turns while items are in flight — a block here
would fight the async workflow. The deliberate exception to block-preferred.
"""
from __future__ import annotations

import pathlib
import sys
import tomllib
from typing import Optional

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent.parent))
from orchestrator.result import HookResult  # noqa: E402  # pyright: ignore[reportMissingImports]
from lib.astgrep_gate import find_project_root  # noqa: E402  # pyright: ignore[reportMissingImports]

MODULE_NAME = "05_campaign_inflight_note"


def applies(data: dict) -> bool:
    return not data.get("stop_hook_active", False)


def run(data: dict) -> Optional[HookResult]:
    root = find_project_root(data.get("cwd"))
    if root is None:
        return None
    campaigns = root / "dev" / "campaigns"
    if not campaigns.is_dir():
        return None

    inflight: list[str] = []
    for manifest in sorted(campaigns.glob("*.toml")):
        try:
            doc = tomllib.loads(manifest.read_text(encoding="utf-8"))
        except (OSError, tomllib.TOMLDecodeError):
            continue
        for item in doc.get("items", []):
            if item.get("status") == "in_flight":
                title = str(item.get("title", ""))[:70]
                inflight.append(f"{item.get('id', '?')} [{manifest.stem}] {title}")

    if not inflight:
        return None

    return HookResult(
        kind="warn",
        payload=(
            "§campaign-inflight — items still in_flight at turn end:\n  - "
            + "\n  - ".join(inflight)
            + "\n  If YOU finished one: python3 dev/campaigns/manifest.py set-status <ID> done "
            "(+ note the evidence). If it runs on a peer (fleet-builder), this is expected."
        ),
        module_name=MODULE_NAME,
    )
