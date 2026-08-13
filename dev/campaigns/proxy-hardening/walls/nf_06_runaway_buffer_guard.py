#!/usr/bin/env python3
"""WALL for NF-06 — every stream translator must carry the runaway-buffer guard, from ONE source.

GAP (RED at authoring, 2026-08-07): only ChatStreamTranslator trips into an honest failure above
its buffered-chars cap; Responses and Passthrough accumulate into structurally identical unbounded
StringBuilders. spliced is ONE process serving every head — a hostile/broken upstream streaming
deltas forever does not fail a turn, it OOMs codex+grok+kimi simultaneously.

GREEN requires ALL of:
  1. a shared splice.spi.BufferCapacity definition exists (one cap, one predicate — the same
     single-source move TerminalStates made for terminal precedence);
  2. ALL THREE translators (chat, responses, passthrough) reference BufferCapacity;
  3. the chat translator no longer carries its own private MAX_BUFFERED_CHARS (code MOTION,
     not a fourth copy).

EXIT 0 = guarded everywhere from one source. EXIT 1 = gap open.
--selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
SPI = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/BufferCapacity.kt"
CHAT = ROOT / "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatStreamTranslator.kt"
RESP = ROOT / "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesStreamTranslator.kt"
PASS = ROOT / "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughStreamTranslator.kt"


def detect(spi: str | None, chat: str | None, resp: str | None, pas: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    problems: list[str] = []
    for name, text in (("ChatStreamTranslator", chat), ("ResponsesStreamTranslator", resp),
                       ("PassthroughStreamTranslator", pas)):
        if text is None:
            return [f"{name}.kt missing — refusing to pass vacuously"]
    if spi is None or "object BufferCapacity" not in spi:
        problems.append("no shared splice.spi.BufferCapacity — the cap either does not exist or "
                        "is a per-dialect copy waiting to drift")
    for name, text in (("chat", chat), ("responses", resp), ("passthrough", pas)):
        if "BufferCapacity" not in (text or ""):
            problems.append(f"{name} translator does not reference BufferCapacity — its buffers "
                            "are unbounded (or guarded by a private fork)")
    if chat is not None and "MAX_BUFFERED_CHARS =" in chat:
        problems.append("chat still carries a private MAX_BUFFERED_CHARS — the lift must be code "
                        "MOTION, not a fourth copy that can drift")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


SPI_OK = "public object BufferCapacity { const val X = 1 }"
GUARDED = "if (BufferCapacity.over(a, b)) { latch() }"
UNGUARDED = "upstream.collect { evt -> onEvent(evt, sink) }"
CHAT_PRIVATE = "private const val MAX_BUFFERED_CHARS = 20_000_000\nBufferCapacity"


def selftest() -> int:
    fails = []
    if not detect(None, "x", UNGUARDED, UNGUARDED):
        fails.append("no shared BufferCapacity must be RED")
    if detect(SPI_OK, GUARDED, GUARDED, GUARDED):
        fails.append(f"all-guarded must be GREEN, got {detect(SPI_OK, GUARDED, GUARDED, GUARDED)}")
    if not detect(SPI_OK, GUARDED, UNGUARDED, GUARDED):
        fails.append("one unguarded translator must be RED")
    if not detect(SPI_OK, CHAT_PRIVATE, GUARDED, GUARDED):
        fails.append("a chat-side private MAX_BUFFERED_CHARS copy must be RED (motion, not a fork)")
    if not detect(SPI_OK, None, GUARDED, GUARDED):
        fails.append("a missing translator file must be RED, never a vacuous pass")
    if fails:
        print("NF-06 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-06 SELFTEST OK — red on missing shared cap, any unguarded translator, a private "
          "chat fork, and missing files; green only when all three guard from one source")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(SPI), _read(CHAT), _read(RESP), _read(PASS))
    if problems:
        print("NF-06 WALL RED — runaway-buffer guard is not one-source-three-dialects:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("NF-06 WALL GREEN: one BufferCapacity, three guarded translators, no private forks.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
