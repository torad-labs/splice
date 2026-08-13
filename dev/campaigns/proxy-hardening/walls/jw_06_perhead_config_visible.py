#!/usr/bin/env python3
"""WALL for JW-06 — the per-head config override layer must be VISIBLE in /api/config.

GAP (RED at authoring, 2026-08-07): [heads.<key>.overrides] is a real, tested precedence layer
inside ConfigService, but ConfigLayers has no per-head field and configJson always emits the
global view — "why is kimi's maxInflight 8 when the panel says 100" is unanswerable from the
dashboard's own provenance feature, precisely for the heads that were tuned.

GREEN requires ALL of:
  1. ConfigLayers carries the perHead map;
  2. /api/config accepts a head parameter (effective becomes getConfig(headKey)) and emits the
     perHead layer in its real precedence position;
  3. the webui config surface can select a head (the selector reaches the fetch).

EXIT 0 = layer visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
SVC = ROOT / "gateway/core/src/main/kotlin/splice/core/config/ConfigService.kt"
CTRL = ROOT / "gateway/control/src/main/kotlin/splice/control/ControlServer.kt"
WEBUI = ROOT / "webui/src/entities/config/api/index.ts"


def detect(svc: str | None, ctrl: str | None, webui: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("ConfigService.kt", svc), ("ControlServer.kt", ctrl), ("config entity api", webui)):
        if text is None:
            return [f"{name} missing — refusing to pass vacuously"]
    problems: list[str] = []
    if "val perHead:" not in (svc or ""):
        problems.append("ConfigLayers has no perHead field — the layer exists in mergedRaw but "
                        "the transparency surface cannot show it")
    if 'queryParameters["head"]' not in (ctrl or "") and "parameters[\"head\"]" not in (ctrl or ""):
        problems.append("/api/config takes no head parameter — the effective view is global-only "
                        "for exactly the heads that were tuned")
    if '"perHead"' not in (ctrl or ""):
        problems.append("configJson never emits the perHead layer")
    if "head?" not in (webui or "") and "head:" not in (webui or ""):
        problems.append("the webui config fetch cannot select a head")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


SVC_OK = "data class ConfigLayers(val perHead: Map<String, Map<String, Any?>>)"
CTRL_OK = 'call.request.queryParameters["head"]\nputJsonObject("perHead") {}'
WEBUI_OK = "config(head?: string)"


def selftest() -> int:
    fails = []
    if not detect("layers no field", "configJson global only", "fetch fixed"):
        fails.append("today's head-blind shape must be RED")
    if detect(SVC_OK, CTRL_OK, WEBUI_OK):
        fails.append(f"visible-layer shape must be GREEN, got {detect(SVC_OK, CTRL_OK, WEBUI_OK)}")
    if not detect("layers no field", CTRL_OK, WEBUI_OK):
        fails.append("a ConfigLayers without perHead must be RED")
    if not detect(SVC_OK, "configJson global only", WEBUI_OK):
        fails.append("a head-less /api/config must be RED")
    if not detect(SVC_OK, CTRL_OK, "fetch fixed"):
        fails.append("a webui that cannot select a head must be RED")
    if not detect(None, CTRL_OK, WEBUI_OK):
        fails.append("missing files must be RED, never a vacuous pass")
    if fails:
        print("JW-06 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-06 SELFTEST OK — red on hidden layer, head-less endpoint, fixed fetch, and missing "
          "files; green only when the per-head layer is visible end to end")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(SVC), _read(CTRL), _read(WEBUI))
    if problems:
        print("JW-06 WALL RED — the per-head override layer is invisible:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-06 WALL GREEN: the per-head layer rides ConfigLayers, /api/config?head, and the webui fetch.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
