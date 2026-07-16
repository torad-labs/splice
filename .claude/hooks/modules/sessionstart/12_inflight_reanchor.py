"""§in-flight re-anchor — surface in-flight work at SessionStart.

If the current session already claimed an item, inject that single item back
into the seat. Otherwise, surface the current in-flight campaign items with the
same short report-back contract used at turn end.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys
import tomllib
from typing import Optional

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent.parent))
from orchestrator.result import HookResult  # noqa: E402  # pyright: ignore[reportMissingImports]
from lib.astgrep_gate import find_project_root  # noqa: E402  # pyright: ignore[reportMissingImports]

MODULE_NAME = "12_inflight_reanchor"
SESSIONSTART_SOURCES = {"startup", "resume", "clear", "compact"}
LINE_ONE = "§in-flight re-anchor — in_flight at session start:"
GUIDANCE = (
    "If YOURS: note progress via python3 dev/campaigns/manifest.py <path> note <ID> ...; "
    "set-status done only after its verify passes; then reply ONE line: <ID> done — see ledger. "
    "Laws: manifest.py laws"
)
MAX_PAYLOAD_CHARS = 500
MAX_ITEMS = 3
TITLE_LIMIT = 60


def _safe_id(value: object) -> str:
    raw = str(value or "default")
    return re.sub(r"[^a-zA-Z0-9._-]", "_", raw)[:128] or "default"


def _read_json(path: pathlib.Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _read_toml(path: pathlib.Path) -> dict:
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _pointer_path(root: pathlib.Path, session_id: object) -> pathlib.Path:
    return root / ".claude" / "state" / f"ledger-active-{_safe_id(session_id)}.json"


def _manifest_path(root: pathlib.Path, raw_path: object) -> pathlib.Path | None:
    if not isinstance(raw_path, str) or not raw_path:
        return None
    path = pathlib.Path(raw_path)
    if not path.is_absolute():
        path = root / path
    try:
        return path.resolve(strict=False)
    except OSError:
        return None


def _campaign_inflight_items(campaigns: pathlib.Path) -> list[dict[str, str]]:
    items: list[dict[str, str]] = []
    if not campaigns.is_dir():
        return items

    for manifest in sorted(campaigns.glob("*.toml")):
        doc = _read_toml(manifest)
        for item in doc.get("items", []):
            if item.get("status") != "in_flight":
                continue
            items.append(
                {
                    "id": str(item.get("id", "?")),
                    "stem": manifest.stem,
                    "title": str(item.get("title", ""))[:TITLE_LIMIT],
                }
            )
    return items


def _pointer_item(root: pathlib.Path, session_id: object) -> dict[str, str] | None:
    pointer = _read_json(_pointer_path(root, session_id))
    item_id = pointer.get("item_id")
    ledger_path = _manifest_path(root, pointer.get("ledger_path"))
    if not isinstance(item_id, str) or not item_id or ledger_path is None or not ledger_path.is_file():
        return None

    doc = _read_toml(ledger_path)
    for item in doc.get("items", []):
        if item.get("id") == item_id and item.get("status") == "in_flight":
            return {
                "id": item_id,
                "stem": ledger_path.stem,
                "title": str(item.get("title", ""))[:TITLE_LIMIT],
            }
    return None


def _payload(items: list[dict[str, str]]) -> str:
    lines = [LINE_ONE]
    for item in items[:MAX_ITEMS]:
        lines.append(f"- {item['id']} [{item['stem']}] {item['title']}")
    if len(items) > MAX_ITEMS:
        lines.append(f"… +{len(items) - MAX_ITEMS} more")
    lines.append(GUIDANCE)
    payload = "\n".join(lines)
    if len(payload) > MAX_PAYLOAD_CHARS:
        return payload[:MAX_PAYLOAD_CHARS]
    return payload


def applies(data: dict) -> bool:
    source = data.get("source")
    return source is None or source in SESSIONSTART_SOURCES


def run(data: dict) -> Optional[HookResult]:
    root = find_project_root(data.get("cwd"))
    if root is None:
        return None

    pointer_item = _pointer_item(root, data.get("session_id"))
    if pointer_item is not None:
        return HookResult(kind="inject", payload=_payload([pointer_item]), module_name=MODULE_NAME)

    items = _campaign_inflight_items(root / "dev" / "campaigns")
    if not items:
        return None
    return HookResult(kind="inject", payload=_payload(items), module_name=MODULE_NAME)
