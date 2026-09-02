#!/usr/bin/env python3
"""WALL for SH-10 — the kimi credential file is MERGED on refresh, never rewritten from scratch.

GAP (RED at authoring, 2026-08-07): KimiAuthProvider writes kimiAuthJson(...) — a fixed six-key
object — over whatever was on disk, dropping every field kimi-cli/kimi-code stores beside ours.
This is the exact shape the 2026-07-18 audit condemned and grok/codex already fixed with merges.

GREEN requires BOTH:
  1. a shared merge primitive exists (mergedCredentialJson in splice.core) — "merge, never
     rewrite" as a property of the primitive, not a habit three files independently remember
     and one already forgot;
  2. the kimi refresh persists the MERGED object through the atomic 0600 credential write
     (SecureFile.writeAtomic0600), not a from-scratch kimiAuthJson(...) rebuild.

EXIT 0 = merged. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
# 2026-08-16 — HD-M8, the :core style slice. `mergedCredentialJson` was a top-level function; the
# Kotlin style law bans those, and both of its arguments are kotlinx JsonObjects (a foreign receiver
# that cannot host a member), so it became a member of `object CredentialJson`. detekt then forces
# two things at once: MatchingDeclarationName wants the file named after its single declaration, and
# MemberNameEqualsClassName forbids `object MergedCredentialJson { fun mergedCredentialJson }` — so
# the FILE moved, MergedCredentialJson.kt -> CredentialJson.kt. Same primitive, same function name,
# same merge order; the token below is unchanged and the "core source missing" arm still refuses a
# vacuous pass if this path ever goes stale again.
CORE = ROOT / "gateway/core/src/main/kotlin/splice/core/auth/CredentialJson.kt"
KIMI = ROOT / "gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/KimiAuthProvider.kt"

# 2026-08-18 — HD-28, the re-anchor. Every arm below used to key on `writeSecure(`, the name of a
# one-line PRIVATE forward (`private fun writeSecure(p, c) { SecureFile.writeAtomic0600(p, c) }`)
# that each provider happened to carry. That is an incidental spelling, not the invariant, and it
# failed in BOTH directions: renaming or deleting the forward reddened a wall whose behaviour was
# intact, while routing the persist through a differently-named helper that skipped the atomic 0600
# write stayed GREEN — the exact world-readable-window regression #924 extracted SecureFile to make
# inexpressible. The anchor is now the call that CARRIES the invariant, SecureFile.writeAtomic0600,
# so the wall is blind to what any wrapper is called and red the instant the atomic write is not
# what persists the credential.
ATOMIC_WRITE = "SecureFile.writeAtomic0600("

# 2026-08-18 — HD-26, the regression arm made real. The re-anchor above moved the PRESENCE check
# onto SecureFile.writeAtomic0600 correctly and then left the regression arm as two literal
# spellings of the write:
#     SecureFile.writeAtomic0600(authPath, kimiAuthJson(
#     SecureFile.writeAtomic0600(authPath, oauth.kimiAuthJson(
# Neither can EVER match KimiAuthProvider.kt. The persist there goes through the private forward
# `writeSecure(path, content)`, so the atomic write only ever sees the forward's parameter names —
# the merge is built at one call site and the atomic write happens at another. PROVEN out-of-tree
# rather than argued: copy the real CredentialJson.kt + KimiAuthProvider.kt under a fixture root,
# rewrite the single line
#     writeSecure(authPath, merged.toString())
# to
#     writeSecure(authPath, oauth.kimiAuthJson(attempt.tokens, clock()).toString())
# — the merge computed and then dropped, which is EXACTLY the regression this wall is named for —
# and the wall printed "SH-10 WALL GREEN", exit 0. The only arm that had been holding anything was
# the bare-identifier fallback `"mergedCredentialJson(" not in kimi`, and a dead merge satisfies it.
#
# The anchor is now the DATAFLOW at the real call site: whatever content reaches the atomic write
# must come from CredentialJson.mergedCredentialJson, either inlined there or carried by a local
# whose value is that call. This is NARROWER than the bare fallback it replaces, not wider — it
# never asks whether an identifier appears somewhere in the module, only whether the value being
# persisted is the merge. It is blind to the local's name and to the forward's name, which is the
# same blindness the HD-28 re-anchor bought, held one level further in.
MERGE_CALL = "CredentialJson.mergedCredentialJson("

_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)

# A private forward under ANY name, in either body form:
#     private fun writeSecure(path: Path, content: String) { SecureFile.writeAtomic0600(path, content) }
#     private fun writeSecure(path: Path, content: String): Unit = SecureFile.writeAtomic0600(path, content)
_FORWARD = re.compile(
    r"private fun (?P<name>\w+)\s*\(\s*(?P<path>\w+)\s*:[^,]+,\s*"
    r"(?P<content>\w+)\s*:[^)]+\)[^={]*(?:\{[^{}]*|=\s*)"
    + re.escape(ATOMIC_WRITE)
    + r"\s*(?P=path)\s*,\s*(?P=content)\s*\)",
    re.S,
)

# The value handed to the persist, when it is carried by a local rather than inlined.
_LOCAL = re.compile(r"^(\w+)(?:\.toString\(\))?$")
_MERGE_VALUE_RE = re.compile(r"^\s*" + re.escape(MERGE_CALL))


def _mask_strings(text: str) -> str:
    """Blank Kotlin strings/chars without moving offsets."""
    chars = list(text)
    i = 0
    while i < len(chars):
        if text.startswith('"""', i):
            close = text.find('"""', i + 3)
            end = len(chars) if close < 0 else close + 3
        elif chars[i] in ('"', "'"):
            quote = chars[i]
            end = i + 1
            while end < len(chars):
                if chars[end] == "\\":
                    end += 2
                elif chars[end] == quote:
                    end += 1
                    break
                else:
                    end += 1
        else:
            i += 1
            continue
        for at in range(i, min(end, len(chars))):
            if chars[at] not in "\r\n":
                chars[at] = " "
        i = end
    return "".join(chars)


def _scope_stacks(text: str, positions: list[int]) -> dict[int, tuple[int, ...]]:
    """Resolve every requested brace ancestry in one lexical pass."""
    targets = sorted(set(positions))
    if not targets:
        return {}
    structure = _mask_strings(text)
    result: dict[int, tuple[int, ...]] = {}
    stack: list[int] = []
    target = 0
    for at, ch in enumerate(structure):
        while target < len(targets) and targets[target] == at:
            result[targets[target]] = tuple(stack)
            target += 1
        if ch == "{":
            stack.append(at)
        elif ch == "}" and stack:
            stack.pop()
    while target < len(targets):
        result[targets[target]] = tuple(stack)
        target += 1
    return result


def _shadowed_by_parameter(name: str, use_at: int, text: str, used: tuple[int, ...]) -> bool:
    """Function, lambda, and loop parameters shadow outer merged properties/locals."""
    structure = _mask_strings(text)
    for function in re.finditer(r"\bfun\s+\w+\s*\((?P<params>[^)]*)\)[^{=]*\{", structure[:use_at]):
        if function.end() - 1 in used and re.search(r"\b" + re.escape(name) + r"\s*:",
                                                   function.group("params")):
            return True
    for brace in used:
        before = structure[max(0, brace - 300):brace]
        if re.search(r"\bfor\s*\(\s*" + re.escape(name) + r"\s+in\b[^{}]*$", before):
            return True
        lambda_head = structure[brace + 1:min(use_at, brace + 300)]
        parameters = re.match(r"\s*(?P<params>[^(){};\n]*?)\s*->", lambda_head)
        if parameters and re.search(r"\b" + re.escape(name) + r"\b",
                                    parameters.group("params")):
            return True
    return False


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring — and to a wall that reads CALL SITES, a comment is one.

    This cuts both ways here, unlike the required-token walls (sh_11/cx_01/cx_02/cx_09/cx_18/jw_08)
    that carry the same stripper for the strict direction only. FAIL-OPEN: a commented-out
    `val merged = CredentialJson.mergedCredentialJson(...)` sitting above a live
    `val merged = oauth.kimiAuthJson(...)` makes the local's trace find the merge in the comment.
    FALSE-RED: a commented-out old from-scratch write beside the live merged one reads as a second,
    unmerged persist. Both shapes are pinned in the selftest.
    """
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _close_paren(text: str, open_idx: int) -> int | None:
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return None


def _split_args(inside: str) -> list[str]:
    parts: list[str] = []
    cur: list[str] = []
    depth = 0
    for ch in inside:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur).strip())
    return parts


def _persist_contents(kimi: str) -> list[tuple[str, int]]:
    """The content argument of every live credential persist — the atomic write itself plus any
    private forward that reaches it.

    A forward's own body is the DEFINITION of that alias, not a second persist, so its span is
    blanked before call sites are collected. Without that the wall would demand that the
    `SecureFile.writeAtomic0600(path, content)` INSIDE `private fun writeSecure` trace to the
    merge, which is nonsense — `content` is a parameter.
    """
    names = {ATOMIC_WRITE[:-1]}
    chars = list(kimi)
    for m in _FORWARD.finditer(kimi):
        names.add(m.group("name"))
        close = _close_paren(kimi, m.end() - 1)
        for i in range(m.start(), len(kimi) if close is None else close + 1):
            chars[i] = " "
    scrubbed = "".join(chars)

    contents: list[tuple[str, int]] = []
    for name in sorted(names):
        for m in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\s*\(", scrubbed):
            open_idx = m.end() - 1
            close = _close_paren(scrubbed, open_idx)
            if close is None:
                continue
            args = _split_args(scrubbed[open_idx + 1 : close])
            if len(args) >= 2:
                contents.append((args[1], m.start()))
    return contents


def _assigned_expression(text: str, start: int) -> str:
    """The expression assigned at `start` (just past the `=`): through the end of its first balanced
    bracket group, or to end of line if it opens none.

    Enough to tell `val x = CredentialJson.mergedCredentialJson(...)` from
    `val x = oauth.kimiAuthJson(...)` across the two-line wrap the real file uses, without
    pretending to be a Kotlin parser.
    """
    depth = 0
    opened = False
    for i in range(start, len(text)):
        ch = text[i]
        if ch in "([{":
            depth += 1
            opened = True
        elif ch in ")]}":
            depth -= 1
            if opened and depth == 0:
                return text[start : i + 1]
        elif ch == "\n" and not opened and text[start:i].strip():
            return text[start:i]
    return text[start:]


def _reaches_merge(content: str, persist_at: int, kimi: str) -> bool:
    """Does the value handed to this persist come from the shared merge?

    Two shapes, deliberately only two: the merge inlined at the write, or a visible LOCAL whose
    nearest preceding assignment is the merge. The order and lexical visibility are load-bearing:
    a post-write assignment or a same-name merge in a closed sibling function cannot define the
    value already written here.

    STATED LIMIT, so the next reader knows it is a choice and not an oversight: a THIRD shape — the
    merge reaching the write through some other transform, say `json.encodeToString(merged)` instead
    of `merged.toString()` — reddens this wall. That is the fail-closed direction, and the RED names
    the exact expression it saw, so it is a one-line read rather than a mystery.
    """
    if _MERGE_VALUE_RE.match(content):
        return True
    local = _LOCAL.match(content)
    if not local:
        return False
    name = local.group(1)
    structure = _mask_strings(kimi)
    assignments = list(re.finditer(
        r"\b(?:val|var)\s+" + re.escape(name) + r"\b[^=\n]*=", structure
    ))
    scopes = _scope_stacks(kimi, [persist_at] + [assign.start() for assign in assignments])
    used = scopes[persist_at]
    if _shadowed_by_parameter(name, persist_at, kimi, used):
        return False

    visible = []
    for assign in assignments:
        assigned = scopes[assign.start()]
        ancestor = len(assigned) <= len(used) and used[:len(assigned)] == assigned
        preceding_local = assign.start() < persist_at
        later_member = assign.start() > persist_at and len(assigned) < len(used)
        if ancestor and (preceding_local or later_member):
            visible.append((len(assigned), assign.start() if preceding_local else -assign.start(), assign))
    if not visible:
        return False
    nearest = max(visible, key=lambda candidate: (candidate[0], candidate[1]))[2]
    return bool(_MERGE_VALUE_RE.match(_assigned_expression(kimi, nearest.end())))


def detect(core: str | None, kimi: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if kimi is None:
        return ["KimiAuthProvider.kt missing — refusing to pass vacuously"]
    if ATOMIC_WRITE not in kimi:
        return ["the kimi credential write no longer reaches SecureFile.writeAtomic0600 — the "
                "atomic 0600 primitive is what closes the world-readable window; refusing to pass "
                "vacuously"]
    problems: list[str] = []
    if core is None or "fun mergedCredentialJson(" not in core:
        problems.append("no shared mergedCredentialJson primitive — merge-never-rewrite is still "
                        "a per-provider habit")
    contents = _persist_contents(kimi)
    if not contents:
        problems.append("SecureFile.writeAtomic0600 is present but nothing calls it with a "
                        "(path, content) pair — the persist shape changed; refusing to pass "
                        "vacuously")
        return problems
    unmerged = [c for c, at in contents if not _reaches_merge(c, at, kimi)]
    if not unmerged:
        return problems
    written = ", ".join(f"`{c}`" for c in unmerged)
    if MERGE_CALL not in kimi:
        problems.append(f"kimi's write no longer routes through the shared merge primitive — the "
                        f"credential persist is handed {written}")
    else:
        problems.append(
            f"kimi still rewrites the credential file from scratch — the persist is handed "
            f"{written}, not the CredentialJson.mergedCredentialJson result, so every foreign "
            f"field (device_id, vendor keys) is dropped on each refresh (the 2026-07-18 audit "
            f"shape grok/codex already fixed). The merge EXISTING in the file is not the "
            f"invariant; the merge being what reaches the atomic write is."
        )
    return problems


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


CORE_OK = "public fun mergedCredentialJson(onDisk: JsonObject?, replacements: JsonObject): JsonObject"
FRESH = "oauth.kimiAuthJson(attempt.tokens, clock())"
_MERGE = f"val merged = CredentialJson.mergedCredentialJson(onDisk, {FRESH})"
KIMI_OPEN = ATOMIC_WRITE + f"authPath, {FRESH}.toString())"
KIMI_OK = _MERGE + "\n" + ATOMIC_WRITE + "authPath, merged.toString())"


def _via_forward(content: str, *, persist: str = "writeSecure") -> str:
    """A persist routed through a private forward — the PRODUCTION shape, and precisely the one the
    two dead literal spellings could never see, because the merge and the atomic write sit at
    different call sites."""
    return (f"{persist}(authPath, {content})\n"
            f"private fun {persist}(path: Path, content: String) {{\n"
            f"    {ATOMIC_WRITE}path, content)\n"
            f"}}")


# Direction (b): the persist may go through a private forward under ANY name. The wall must be blind
# to that name — it was `writeSecure` for a year and reddening on the rename is the defect the HD-28
# re-anchor removed.
KIMI_WRAPPED = _MERGE + "\n" + _via_forward("merged.toString()", persist="persistCredentialFile")
# Direction (a): the hole the old `writeSecure(` anchor left wide open — a correctly MERGED write
# handed to a helper that never reaches the atomic 0600 primitive. Merge intact, world-readable
# window back. The old anchor passed this; this one must not.
KIMI_UNSAFE = (
    _MERGE + "\n"
    "writeSecure(authPath, merged.toString())\n"
    "private fun writeSecure(path: Path, content: String) { Files.writeString(path, content) }"
)
# THE REGRESSION, byte-for-byte the out-of-tree mutation that proved the two literal spellings dead
# (2026-08-18): the merge is still computed — so `mergedCredentialJson(` is still in the file, and
# the bare-identifier fallback is still satisfied — and the atomic write is handed the freshly-built
# six-key object anyway. This printed GREEN before the dataflow anchor.
KIMI_DEAD_MERGE = _MERGE + "\n" + _via_forward(f"{FRESH}.toString()")
# The same regression one step along: the merge lands in a local nobody persists while a DIFFERENT
# local carries the from-scratch object to the write. Pins that the trace follows the VALUE, not the
# presence of a `val merged` somewhere above.
KIMI_WRONG_LOCAL = f"val fresh = {FRESH}\n" + _MERGE + "\n" + _via_forward("fresh.toString()")
# A PURE RENAME — local AND forward renamed, merge still reaching the write. Must stay GREEN.
KIMI_RENAMED = (
    f"val credentialFile = CredentialJson.mergedCredentialJson(onDisk, {FRESH})\n"
    + _via_forward("credentialFile.toString()", persist="persistCredentials")
)
# The merge inlined at the write with no local at all — also GREEN. A local is one way to carry the
# value, never the invariant.
KIMI_INLINE = _via_forward(f"CredentialJson.mergedCredentialJson(onDisk, {FRESH}).toString()")
# A merge assignment AFTER the persist is not the provenance of the value already written. The old
# whole-file search accepted it merely because the same local name appeared eventually.
KIMI_POSTHOC_MERGE = (
    _via_forward("credentialFile.toString()", persist="persistCredentials") + "\n"
    f"val credentialFile = CredentialJson.mergedCredentialJson(onDisk, {FRESH})"
)
# A same-name merge in a closed sibling scope cannot define the value read by refresh(). The live
# outer value is fresh, so persisting it is still a rewrite even though a lexical decoy is merged.
KIMI_CROSS_SCOPE_MERGE = (
    f"val credentialFile = {FRESH}\n"
    f"fun decoy() {{ val credentialFile = CredentialJson.mergedCredentialJson(onDisk, {FRESH}) }}\n"
    "fun refresh() { persistCredentials(authPath, credentialFile.toString()) }\n"
    "private fun persistCredentials(path: Path, content: String) {\n"
    f"    {ATOMIC_WRITE}path, content)\n"
    "}"
)
KIMI_PARAMETER_SHADOW = (
    f"val credentialFile = CredentialJson.mergedCredentialJson(onDisk, {FRESH})\n"
    "fun refresh(credentialFile: JsonObject) { "
    "persistCredentials(authPath, credentialFile.toString()) }\n"
    "private fun persistCredentials(path: Path, content: String) {\n"
    f"    {ATOMIC_WRITE}path, content)\n"
    "}"
)
KIMI_LAMBDA_SHADOW = (
    f"val credentialFile = CredentialJson.mergedCredentialJson(onDisk, {FRESH})\n"
    f"fun refresh() {{ listOf({FRESH}).map {{ credentialFile -> "
    "persistCredentials(authPath, credentialFile.toString()) } } }\n"
    "private fun persistCredentials(path: Path, content: String) {\n"
    f"    {ATOMIC_WRITE}path, content)\n"
    "}"
)
KIMI_TYPED_LAMBDA_SHADOW = KIMI_LAMBDA_SHADOW.replace(
    "credentialFile ->", "credentialFile: JsonObject ->"
)
KIMI_UNRELATED_LAMBDA = (
    f"val credentialFile = CredentialJson.mergedCredentialJson(onDisk, {FRESH})\n"
    "fun refresh() { listOf(credentialFile).map { x -> x }; "
    "persistCredentials(authPath, credentialFile.toString()) }\n"
    "private fun persistCredentials(path: Path, content: String) {\n"
    f"    {ATOMIC_WRITE}path, content)\n"
    "}"
)
KIMI_NESTED_RESULT = (
    f"val credentialFile = run {{ CredentialJson.mergedCredentialJson(onDisk, {FRESH}); {FRESH} }}\n"
    + _via_forward("credentialFile.toString()", persist="persistCredentials")
)
KIMI_DROPPED_FORWARD = (
    _MERGE + "\nwriteSecure(authPath, merged.toString())\n"
    "private fun writeSecure(path: Path, content: String) {\n"
    f"    {ATOMIC_WRITE}path, {FRESH}.toString())\n"
    "}"
)
# Comments lie in both directions to a wall that follows a value; both are fed through code_only,
# exactly as the real file is. FAIL-OPEN: the merge exists only in a comment while the live local
# holds the from-scratch object.
KIMI_COMMENT_LIE = "// " + _MERGE + f"\nval merged = {FRESH}\n" + _via_forward("merged.toString()")
# FALSE-RED: a commented-out old from-scratch write beside the live merged one is not a second
# persist and must not redden a correct file.
KIMI_COMMENT_GHOST = (
    _MERGE + f"\n// writeSecure(authPath, {FRESH}.toString())\n" + _via_forward("merged.toString()")
)


def selftest() -> int:
    fails = []

    def red(label: str, kimi: str | None, core: str | None = CORE_OK) -> None:
        if not detect(core, kimi):
            fails.append(f"{label} must be RED")

    def green(label: str, kimi: str | None, core: str | None = CORE_OK) -> None:
        if detect(core, kimi):
            fails.append(f"{label} must be GREEN, got {detect(core, kimi)}")

    red("from-scratch rewrite with no primitive", KIMI_OPEN, core=None)
    green("primitive + merged write", KIMI_OK)
    red("primitive present but kimi still rewriting", KIMI_OPEN)
    red("kimi merged but no shared primitive (a private fork can drift)", KIMI_OK, core=None)
    red("missing KimiAuthProvider.kt — never a vacuous pass", None)
    red("an unrecognized persist shape — never a vacuous pass", "class KimiAuthProvider")
    green("a merged write through a RENAMED private forward that still reaches the atomic write",
          KIMI_WRAPPED)
    red("a merged write through a helper that SKIPS SecureFile.writeAtomic0600 — the merge is not "
        "the only invariant", KIMI_UNSAFE)
    # The four arms this wall lacked: the two literal FROM_SCRATCH spellings they replace could not
    # match the production call site at all, so the regression the wall is named for passed GREEN.
    red("THE REGRESSION — merge computed, from-scratch object persisted through the forward",
        KIMI_DEAD_MERGE)
    red("merge computed into a local nobody persists while another local carries the fresh object",
        KIMI_WRONG_LOCAL)
    green("a pure rename of the local AND the forward, merge still reaching the write", KIMI_RENAMED)
    green("the merge inlined at the write with no local at all", KIMI_INLINE)
    red("a same-name merge assignment that occurs only AFTER the persist", KIMI_POSTHOC_MERGE)
    red("a same-name merge assignment in a closed sibling scope", KIMI_CROSS_SCOPE_MERGE)
    red("a fresh function parameter shadowing an outer merged value", KIMI_PARAMETER_SHADOW)
    red("a fresh lambda parameter shadowing an outer merged value", KIMI_LAMBDA_SHADOW)
    red("a typed lambda parameter shadowing an outer merged value", KIMI_TYPED_LAMBDA_SHADOW)
    green("an unrelated lambda that merely reads the merged value", KIMI_UNRELATED_LAMBDA)
    red("a nested expression that calls merge but returns the fresh object", KIMI_NESTED_RESULT)
    red("a persist forward that discards its content parameter", KIMI_DROPPED_FORWARD)
    red("a merge that exists only in a COMMENT above a live from-scratch local",
        code_only(KIMI_COMMENT_LIE))
    green("a commented-out old from-scratch write beside the live merged one",
          code_only(KIMI_COMMENT_GHOST))

    if fails:
        print("SH-10 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-10 SELFTEST OK — red on fresh/nested/shadowed values, posthoc or sibling merges, "
          "content-dropping forwards, missing primitive/file, unsafe writes, and comment-only "
          "merges; green only when the resolved merge value reaches the atomic write")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(CORE), _read(KIMI))
    if problems:
        print("SH-10 WALL RED — the kimi credential refresh does not merge-and-atomically-persist:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-10 WALL GREEN: kimi merges rotations onto the on-disk object via the shared primitive "
          "and persists them through SecureFile.writeAtomic0600.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
