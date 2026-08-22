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
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
# HD-25 (2026-08-18): ConfigLayers moved out of ConfigService.kt into ConfigResults.kt when the
# config god object decomposed — same package, same `perHead` field, new file. RE-ANCHORED to the
# exact file that now holds the declaration, NOT widened to the package: a directory-wide search
# would pass on the field living anywhere, which is precisely the resolution loss this campaign
# exists to prevent. If ConfigLayers moves again, move this path with it.
LAYERS = ROOT / "gateway/core/src/main/kotlin/splice/core/config/ConfigResults.kt"
# HD-24: configJson (the "perHead" emitter) and the /api/config route (the head query-param read)
# split across two files when ControlServer decomposed — the head param stayed in ControlServer's
# route table, "perHead" moved with configJson into ConfigRoutes. Concatenated like the campaign's
# other multi-file wall keys: ALL-OF still applies, and either file missing is a vacuity RED.
CTRL_FILES = [
    ROOT / "gateway/control/src/main/kotlin/splice/control/ControlServer.kt",
    ROOT / "gateway/control/src/main/kotlin/splice/control/api/ConfigRoutes.kt",
]
WEBUI = ROOT / "webui/src/entities/config/api/index.ts"


def detect(layers: str | None, ctrl: str | None, webui: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("ConfigResults.kt", layers), ("ControlServer.kt", ctrl), ("config entity api", webui)):
        if text is None:
            return [f"{name} missing — refusing to pass vacuously"]
    problems: list[str] = []
    if "val perHead:" not in (layers or ""):
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


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


def _read_all(paths: list[pathlib.Path]) -> str | None:
    """Concatenate every file's text, in order — None (vacuity RED) if any is missing."""
    texts: list[str] = []
    for p in paths:
        text = _read(p)
        if text is None:
            return None
        texts.append(text)
    return "\n".join(texts)


LAYERS_OK = "data class ConfigLayers(val perHead: Map<String, Map<String, Any?>>)"
CTRL_OK = 'call.request.queryParameters["head"]\nputJsonObject("perHead") {}'
WEBUI_OK = "config(head?: string)"


def selftest() -> int:
    fails = []
    if not detect("layers no field", "configJson global only", "fetch fixed"):
        fails.append("today's head-blind shape must be RED")
    if detect(LAYERS_OK, CTRL_OK, WEBUI_OK):
        fails.append(f"visible-layer shape must be GREEN, got {detect(LAYERS_OK, CTRL_OK, WEBUI_OK)}")
    if not detect("layers no field", CTRL_OK, WEBUI_OK):
        fails.append("a ConfigLayers without perHead must be RED")
    if not detect(LAYERS_OK, "configJson global only", WEBUI_OK):
        fails.append("a head-less /api/config must be RED")
    if not detect(LAYERS_OK, CTRL_OK, "fetch fixed"):
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
    problems = detect(_read(LAYERS), _read_all(CTRL_FILES), _read(WEBUI))
    if problems:
        print("JW-06 WALL RED — the per-head override layer is invisible:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-06 WALL GREEN: the per-head layer rides ConfigLayers, /api/config?head, and the webui fetch.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
