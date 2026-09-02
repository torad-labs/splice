#!/usr/bin/env python3
"""WALL for NF-06 — every stream translator must carry the runaway-buffer guard, from ONE source.

GAP (RED at authoring, 2026-08-07): only ChatStreamTranslator trips into an honest failure above
its buffered-chars cap; Responses and Passthrough accumulate into structurally identical unbounded
StringBuilders. spliced is ONE process serving every head — a hostile/broken upstream streaming
deltas forever does not fail a turn, it OOMs codex+grok+kimi simultaneously.

GREEN requires ALL of:
  1. a shared splice.spi.BufferCapacity definition exists (one cap, one predicate — the same
     single-source move TerminalStates made for terminal precedence);
  2. ALL THREE translators (chat, responses, passthrough) call BufferCapacity.over at the live guard;
  3. the chat translator no longer carries its own private MAX_BUFFERED_CHARS (code MOTION,
     not a fourth copy).

EXIT 0 = guarded everywhere from one source. EXIT 1 = gap open.
--selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
SPI = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/BufferCapacity.kt"
CHAT = ROOT / "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatStreamTranslator.kt"
RESP = ROOT / "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesStreamTranslator.kt"
PASS = ROOT / "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughStreamTranslator.kt"
_EXPECTED_SURFACES = {
    "chat": ("channels.textBuf.length", "channels.thinkingBuf.length",
             "toolCalls.retainedIndexEntryCount", "toolCalls.bufferedArgsChars"),
    "responses": ("state.textBuf.length", "state.thinkingBuf.length", "state.blocks.size",
                  "pendingArgsChars"),
    "passthrough": ("channels.textBuf.length", "channels.thinkingBuf.length",
                    "blocks.openBlockCount", "blocks.bufferedToolArgsChars"),
}
_GUARDED_COLLECT_RE = re.compile(
    r"\.takeWhile\s*\{(?P<body>.*?)\}\s*\.collect\s*\{", re.S
)
_OVER_ASSIGN_RE = re.compile(
    r"\bval\s+(?P<name>\w+)\s*=\s*!\s*BufferCapacity\.over\s*\((?P<args>.*?)\)", re.S
)


def has_live_guard(text: str, required: tuple[str, ...]) -> bool:
    """The one collect() must be gated by one four-surface takeWhile predicate."""
    guards = list(_GUARDED_COLLECT_RE.finditer(text))
    if len(guards) != 1 or len(re.findall(r"\.collect\s*\{", text)) != 1:
        return False
    predicate = guards[0].group("body")
    assignment = _OVER_ASSIGN_RE.search(predicate)
    return bool(
        assignment
        and all(token in assignment.group("args") for token in required)
        and re.search(r"\b" + re.escape(assignment.group("name")) + r"\s*$", predicate)
    )


def detect(
    spi: str | None,
    chat: str | None,
    resp: str | None,
    pas: str | None,
    *,
    chat_raw: str | None,
) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly.

    `spi`/`chat`/`resp`/`pas` are the CODE views (comments and imports stripped) and carry every
    REQUIRED token; `chat_raw` is the untouched chat text and carries the private-fork BAN. The two
    directions want opposite treatment — see code_only.
    """
    problems: list[str] = []
    for name, text in (("ChatStreamTranslator", chat), ("ResponsesStreamTranslator", resp),
                       ("PassthroughStreamTranslator", pas)):
        if text is None:
            return [f"{name}.kt missing — refusing to pass vacuously"]
    if spi is None or "object BufferCapacity" not in spi:
        problems.append("no shared splice.spi.BufferCapacity — the cap either does not exist or "
                        "is a per-dialect copy waiting to drift")
    for name, text in (("chat", chat), ("responses", resp), ("passthrough", pas)):
        if not has_live_guard(text or "", _EXPECTED_SURFACES[name]):
            problems.append(f"{name} driveTurn does not gate its collect() with a four-surface "
                            "BufferCapacity.over() takeWhile predicate — dead helpers, ignored "
                            "calls, or constant arguments do not bound retained buffers")
    if chat_raw is not None and "MAX_BUFFERED_CHARS =" in chat_raw:
        problems.append("chat still carries a private MAX_BUFFERED_CHARS — the lift must be code "
                        "MOTION, not a fourth copy that can drift")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
    REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
    Proven against this wall's own sources: with the passthrough `BufferCapacity.over(...)` call
    deleted and its literal text left in a TODO, the raw-matching wall printed WALL GREEN.

    Applied to _read (the required tokens) and deliberately NOT to _read_raw, which feeds the
    private-fork BAN. The two directions want opposite treatment: stripping makes a required token
    harder to satisfy, but would make a banned string easier to hide. Both stay strict this way.
    The import strip matters here specifically — `import splice.spi.BufferCapacity` would otherwise
    keep every translator green with its guard deleted."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read_raw(p: pathlib.Path) -> str | None:
    """Untouched file text — the view the BAN is matched against (see code_only)."""
    return p.read_text(encoding="utf-8") if p.exists() else None


def _read(p: pathlib.Path) -> str | None:
    return code_only(_read_raw(p))


SPI_OK = "public object BufferCapacity { const val X = 1 }"


def guarded(*args: str) -> str:
    joined = ", ".join(args)
    return ("fun driveTurn() { upstream.takeWhile { "
            f"val withinCapacity = !BufferCapacity.over({joined}); withinCapacity "
            "}.collect { evt -> onEvent(evt, sink) } }")


CHAT_GUARDED = guarded("channels.textBuf.length", "channels.thinkingBuf.length",
                       "toolIndexCount = toolCalls.retainedIndexEntryCount",
                       "pendingArgsLen = toolCalls.bufferedArgsChars")
RESP_GUARDED = guarded(
    "state.textBuf.length", "state.thinkingBuf.length", "toolIndexCount = state.blocks.size",
    "pendingArgsLen = pendingArgsChars",
).replace("driveTurn", "driveRound", 1)
PASS_GUARDED = guarded("channels.textBuf.length", "channels.thinkingBuf.length",
                       "toolIndexCount = blocks.openBlockCount",
                       "pendingArgsLen = blocks.bufferedToolArgsChars")
REFERENCED_ONLY = "fun driveTurn() { val capacity = BufferCapacity; upstream.collect { work() } }"
UNGUARDED = "fun driveTurn() { upstream.collect { evt -> onEvent(evt, sink) } }"
DEAD_GUARD = UNGUARDED + "\nfun telemetry() = BufferCapacity.over(0, 0)"
CONSTANT_GUARD = guarded("0", "0", "toolIndexCount = 0", "pendingArgsLen = 0")
CHAT_PRIVATE = "private const val MAX_BUFFERED_CHARS = 20_000_000\n" + CHAT_GUARDED


COMMENTED_GUARD = (
    "import splice.spi.BufferCapacity\n"
    "fun driveRound() { upstream.takeWhile {\n"
    "// val withinCapacity = !BufferCapacity.over(state.textBuf.length, "
    "state.thinkingBuf.length, toolIndexCount = state.blocks.size, "
    "pendingArgsLen = pendingArgsChars)\n"
    "withinCapacity\n"
    "}.collect { evt -> router.onEvent(evt, sink) } }"
)
HIDDEN_FORK = CHAT_GUARDED + "\n// private const val MAX_BUFFERED_CHARS = 20_000_000"


def selftest() -> int:
    fails = []
    if not detect(None, "x", UNGUARDED, UNGUARDED, chat_raw="x"):
        fails.append("no shared BufferCapacity must be RED")
    if detect(SPI_OK, CHAT_GUARDED, RESP_GUARDED, PASS_GUARDED, chat_raw=CHAT_GUARDED):
        fails.append(f"all-guarded must be GREEN, got "
                     f"{detect(SPI_OK, CHAT_GUARDED, RESP_GUARDED, PASS_GUARDED, chat_raw=CHAT_GUARDED)}")
    if not detect(SPI_OK, CHAT_GUARDED, UNGUARDED, PASS_GUARDED, chat_raw=CHAT_GUARDED):
        fails.append("one unguarded translator must be RED")
    for name, chat, resp, pas in (
        ("chat", REFERENCED_ONLY, RESP_GUARDED, PASS_GUARDED),
        ("responses", CHAT_GUARDED, REFERENCED_ONLY, PASS_GUARDED),
        ("passthrough", CHAT_GUARDED, RESP_GUARDED, REFERENCED_ONLY),
    ):
        if not detect(SPI_OK, chat, resp, pas, chat_raw=chat):
            fails.append(f"{name} mentioning BufferCapacity without a live guard must be RED")
    for label, broken in (("dead helper", DEAD_GUARD), ("constant arguments", CONSTANT_GUARD)):
        if not detect(SPI_OK, broken, RESP_GUARDED, PASS_GUARDED, chat_raw=broken):
            fails.append(f"a chat {label} must be RED")
    if not detect(SPI_OK, CHAT_PRIVATE, RESP_GUARDED, PASS_GUARDED, chat_raw=CHAT_PRIVATE):
        fails.append("a chat-side private MAX_BUFFERED_CHARS copy must be RED (motion, not a fork)")
    if not detect(SPI_OK, None, RESP_GUARDED, PASS_GUARDED, chat_raw=None):
        fails.append("a missing translator file must be RED, never a vacuous pass")
    # HD-26 comment-satisfiability controls. Both directions, so a later blind sweep that strips the
    # ban too (or stops stripping the required tokens) breaks the selftest instead of the invariant.
    if detect(SPI_OK, CHAT_GUARDED, COMMENTED_GUARD, PASS_GUARDED, chat_raw=CHAT_GUARDED):
        fails.append("the raw shape must read GREEN — otherwise this fixture is not the bug and "
                     "the control below proves nothing")
    if not detect(SPI_OK, CHAT_GUARDED, code_only(COMMENTED_GUARD), PASS_GUARDED,
                  chat_raw=CHAT_GUARDED):
        fails.append("a translator whose guard survives only as an import + comment must be RED — "
                     "required tokens are matched against code, never raw file text")
    if not detect(SPI_OK, code_only(HIDDEN_FORK), RESP_GUARDED, PASS_GUARDED, chat_raw=HIDDEN_FORK):
        fails.append("a private MAX_BUFFERED_CHARS fork commented out of the code view must still "
                     "be RED — the ban reads RAW so a comment cannot hide it")
    if fails:
        print("NF-06 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-06 SELFTEST OK — red on missing cap, dead/reference-only/constant guards, missing "
          "four-surface arguments, private chat fork, and missing files; green only when each live "
          "translation path gates collect() through BufferCapacity.over()")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(SPI), _read(CHAT), _read(RESP), _read(PASS), chat_raw=_read_raw(CHAT))
    if problems:
        print("NF-06 WALL RED — runaway-buffer guard is not one-source-three-dialects:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("NF-06 WALL GREEN: one BufferCapacity, three guarded translators, no private forks.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
