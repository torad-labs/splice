#!/usr/bin/env python3
"""V4-89 (ARCH-AUDIT 2026-09-17, audit B row 5; concept #924 "make drift not compile") — ONE ROLE,
ONE INTERFACE. Every `fun interface` in main sources is a NAMED SEAM, and two names that normalise
to the same single-abstract-method signature are either two roles that must say why, or one role
spelled twice.

WHY THIS EXISTS. kt-no-lambda-seam made every seam a named port and its header states the doctrine
this wall enforces the other half of: "one interface per role; a role with two spellings is one
interface". It also names the reason the matcher cannot do it — 200 seams over 132 signatures, and
the mapping is many-to-many in both directions. `() -> Boolean` is legitimately ten roles
(ClientGone and ClientFrameEmitted are read a few lines apart on the same retry path and drive
OPPOSITE decisions; collapsing them into a shared BooleanSupplier would be strictly worse than the
lambdas they replaced). And `() -> Long` is legitimately six roles PLUS two duplicates. Nothing in
the compiler, and nothing in an ast-grep rule, can tell those two situations apart — so this wall
does not try to. It requires that every shared signature be DISPOSITIONED IN WRITING, and treats
absence as the failure.

THE THREE DUPLICATES THIS ROW WAS OPENED FOR, each verified against the declarations rather than
taken from the audit (2026-09-17):
  ElapsedNow (provider-spi/RuntimeSeams.kt:40) == ElapsedClock (core/util/RuntimePorts.kt:143).
      ElapsedNow's KDoc: "A monotonic now-reading in milliseconds. The seam behind retry deadlines
      and the shared 429 cooldown." ElapsedClock's: "Reads a MONOTONIC timebase in milliseconds …
      for budgets, deadlines, watchdog caps and elapsed timings." Same role, two modules.
  AccountNow (provider-spi/AccountSelection.kt:22) == WallClock (core/util/RuntimePorts.kt:105).
      AccountNow's KDoc: "Epoch time seam used to compare provider reset timestamps." WallClock's
      contract is exactly "a real point in calendar time … compared against a foreign epoch".
  HeaderLookup (gateway/usage/RateLimitHeaders.kt:22) == QuotaHeaderRead (core/usage/QuotaHeaders.kt:15).
      QuotaHeaderRead's own KDoc ADMITS it: "The gateway's own HeaderLookup lives a module above
      this one, so the header families are decoded against this port." One role, duplicated to
      satisfy a module direction.
These three names are deliberately ABSENT from the disposition file, so this wall is RED on them by
name today. That red list IS the work list for the sibling fix row; this wall does not fix
instances and does not allowlist them away.

DENOMINATOR, FROM THE SOURCE (§24). Every `gateway/*/src/main/**/*.kt` file is parsed on disk and
every `fun interface` in it is enumerated — nested ones included, since a per-class seam is still a
seam. 200 declarations over 132 signatures at authoring, of which 26 signatures are shared by 2+
names. An interface added tomorrow is in scope with no edit to this file. THREE guards refuse a
vacuous pass: a parse yielding zero interfaces is a failure rather than a pass; the count of
`fun interface` occurrences in the comment-blanked source must EQUAL the number of declarations
parsed (parser drift, the same shape as quirks-keys-documented.py's @SerialName guard); and a
declaration whose body does not yield EXACTLY ONE abstract method is reported as UNTRUSTED rather
than silently grouped — a `fun interface` has one abstract method by language rule, so a different
count means this parser, not the code, is wrong.

SIGNATURE NORMALISATION, and why each part of it is load-bearing.
  param types + return type, whitespace removed. The row's definition.
  `suspend` is PART of the signature. Measured both ways: folding suspend in with non-suspend
  merged Ticker (`suspend (Long) -> Boolean`, the pacing seam whose false means "stop the loop")
  with PidAlive (`(Long) -> Boolean`, "is this pid alive"), and HeadSignIn/ProfileAdd with
  BrowserOpener/CredentialPresenceProbe/DirectoryProbe/VersionedRestart. Those are not near-misses
  that want a written reason; a suspend seam and a blocking one cannot be substituted for each
  other at all, so grouping them would have manufactured four false duplicates and buried the real
  ones.
  TYPE PARAMETERS ARE POSITIONAL (`#1`, `#2`), never their declared spelling. `CoalescedWork<T>`
  and `MaterializedRequest<R>` ARE the same shape; a normaliser that kept `T` and `R` would call
  them different and let a real duplicate through on a rename.
  The METHOD NAME is NOT part of the signature, deliberately. `invoke`, `beforeWrite`, `abort`,
  `run` and `keyPresentNow` all appear on `() -> Unit`-shaped ports; a duplicate role renamed from
  `invoke` to `run` is exactly the drift this wall is for, so the name must not be allowed to
  separate two groups.

DISPOSITION. checks/config/role-registry.toml carries one `[[groups]]` entry per shared signature:
  signature = the normalised signature, verbatim
  dated     = the date the disposition was written
  names     = the interface names this entry accounts for
  reason    = why these are DISTINCT ROLES. Non-empty, in words.
Absence is not a disposition. A name in a shared group with no entry covering it fails BY NAME.
The disposition is FAIL-CLOSED IN BOTH DIRECTIONS, which is what makes it a ratchet rather than a
list:
  GROWTH   a new name joining a dispositioned group is not in `names`, so it fails by name. Adding
           a seam that shares a shape costs one written sentence, every time.
  STALENESS a name in `names` that no longer exists, or an entry whose signature is no longer
           shared by 2+ names, fails as stale. A disposition cannot outlive its subject — which is
           how the fix row's own success is detected: the moment ElapsedNow is deleted, the
           `()->Long` entry must drop it or this wall reds.
  UNREASONED an entry with a missing, blank or whitespace-only `reason` is an absence wearing a
           label and fails by name, the same as no entry at all.

WHAT IS NOT CAUGHT, and why it is written down rather than implied.
  TWO DECLARATIONS OF THE SAME NAME. `SynthesizeExpiry` exists three times (codex, grok, kimi) and
  `PersistRotation` twice (codex, grok). Those groups hold ONE distinct name, so the 2+-names test
  does not reach them — and that is correct here, not a hole being excused: the 2026-09-15
  SEPARATION law requires vendor facts to live in that vendor's provider-* module, so a per-vendor
  expiry synthesis IS one role per vendor. The wall reports these under `report` so the count stays
  visible; it does not grade them.
  A DUPLICATE WITH DIFFERENT SIGNATURES. Two names for one role whose methods take different
  parameter lists (`(Long) -> Long` vs `(Long, Long) -> Long`) land in different groups and are
  invisible here. Grouping by anything looser than the signature would turn the 26 groups into
  noise; this wall's claim is exactly "same shape, undeclared intent", never "same meaning".
  A ROLE-INAPPROPRIATE REASON. This wall proves a reason EXISTS and is not blank. Whether it is a
  good reason is a reviewer's judgement, and the entries are checked-in text precisely so a
  reviewer sees them in a diff.

EXIT 0 = every shared signature is accounted for in writing. EXIT 1 = at least one name is not,
named by name, with its file:line and its signature siblings.
`--selftest` = the fixture proofs (both directions, plus the boring and vacuous cases).
`report`     = the whole census, for writing or reviewing a disposition.
"""
from __future__ import annotations

import pathlib
import re
import sys
import tempfile
import tomllib

ROOT = pathlib.Path(__file__).resolve().parents[1]

SOURCE_GLOB = "gateway/*/src/main/**/*.kt"
CONFIG_REL = "checks/config/role-registry.toml"

DECL = re.compile(r"\bfun\s+interface\s+(\w+)")
_METHOD_HEAD = re.compile(r"\s*(\w+)\s*\(")


def code_view(text: str) -> str:
    """Blank comments and string literals WITHOUT moving offsets or newlines.

    One lexical pass, so a `//` inside a string cannot open a comment and a quote inside a comment
    cannot open a string. Offsets survive so every finding still names file:LINE, which is this
    wall's contract. Raw strings (triple-quoted) are handled first: a KDoc example or a raw string
    containing the words `fun interface` must not become a phantom declaration, and the parser-drift
    guard counts occurrences in THIS view so the two halves cannot disagree.
    """
    out = list(text)
    n = len(text)
    i = 0

    def blank(start: int, end: int) -> None:
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        ch = text[i]
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            end = text.find("\n", i)
            end = n if end < 0 else end
            blank(i, end)
            i = end
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            blank(i, end)
            i = end
            continue
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            blank(i, end)
            i = end
            continue
        if ch in "\"'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == ch:
                    j += 1
                    break
                if text[j] == "\n":
                    break
                j += 1
            blank(i, j)
            i = j
            continue
        i += 1
    return "".join(out)


def _balanced(code: str, at: int, open_c: str, close_c: str) -> int:
    """Index of the close bracket matching the one at [at], or -1."""
    depth = 0
    i = at
    while i < len(code):
        if code[i] == open_c:
            depth += 1
        elif code[i] == close_c:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _angle_end(code: str, at: int) -> int:
    """Index of the `>` closing the type-parameter list opened at [at], or -1.

    Stops at `{` or `;` so a `<` used as a comparison cannot run the scan off the end of the file.
    """
    depth = 0
    i = at
    while i < len(code):
        ch = code[i]
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
            if depth == 0:
                return i
        elif ch in "{;":
            return -1
        i += 1
    return -1


def _split_top(text: str) -> list[str]:
    """Split on top-level commas, honouring (), [], {} and <>."""
    parts: list[str] = []
    buf: list[str] = []
    depth = 0
    for ch in text:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            continue
        buf.append(ch)
    if "".join(buf).strip():
        parts.append("".join(buf))
    return parts


def _type_param_names(text: str) -> list[str]:
    """`T`, `K`, `V` from a type-parameter list body, dropping bounds and variance."""
    names: list[str] = []
    for part in _split_top(text):
        head = part.strip().split(":")[0].strip()
        if head:
            names.append(head.split()[-1])
    return names


class Role:
    """One parsed `fun interface`: where it is, and the signature of its abstract method."""

    def __init__(self, name: str, path: str, line: int, signature: str, method: str) -> None:
        self.name = name
        self.path = path
        self.line = line
        self.signature = signature
        self.method = method

    @property
    def at(self) -> str:
        return f"{self.path}:{self.line}"


def _abstract_methods(body: str, owner_params: list[str]) -> tuple[list[tuple[str, str]], list[str]]:
    """([(method name, signature)], problems) for the TOP-LEVEL abstract methods of an interface body.

    Top-level only (bracket depth 0 within the body), so a nested enum's or data class's own
    functions are never mistaken for the seam's method — AccountCredentialIdentitySource has one
    abstract method beside two defaulted ones, a nested enum and a nested data class with an `init`,
    and a naive `fun` count reads it as seven.

    Abstract means no body: the signature is followed by neither `=` nor `{`. A defaulted method is
    not the seam.
    """
    problems: list[str] = []
    found: list[tuple[str, str]] = []
    depth = 0
    i = 0
    while i < len(body):
        ch = body[i]
        if ch in "{([":
            depth += 1
            i += 1
            continue
        if ch in "})]":
            depth -= 1
            i += 1
            continue
        is_fun = (
            depth == 0
            and body.startswith("fun", i)
            and (i == 0 or not (body[i - 1].isalnum() or body[i - 1] == "_"))
            and (i + 3 >= len(body) or not (body[i + 3].isalnum() or body[i + 3] == "_"))
        )
        if not is_fun:
            i += 1
            continue
        # Modifiers sit between the previous member boundary and this `fun`.
        back = i - 1
        while back >= 0 and body[back] not in "};\n":
            back -= 1
        suspend = re.search(r"\bsuspend\b", body[back + 1:i]) is not None

        cursor = i + 3
        method_params: list[str] = []
        while cursor < len(body) and body[cursor] in " \t":
            cursor += 1
        if cursor < len(body) and body[cursor] == "<":
            end = _angle_end(body, cursor)
            if end < 0:
                problems.append("unterminated method type-parameter list")
                i = cursor + 1
                continue
            method_params = _type_param_names(body[cursor + 1:end])
            cursor = end + 1
        head = _METHOD_HEAD.match(body[cursor:])
        if head is None:
            i += 3
            continue
        popen = cursor + head.end() - 1
        pclose = _balanced(body, popen, "(", ")")
        if pclose < 0:
            problems.append(f"unbalanced parameter list on `{head.group(1)}`")
            i = popen + 1
            continue
        param_text = body[popen + 1:pclose]
        rest = body[pclose + 1:]
        ret = "Unit"
        tail = rest
        colon = re.match(r"\s*:\s*", rest)
        if colon is not None:
            typed = rest[colon.end():]
            depth2 = 0
            cut = len(typed)
            for idx, c in enumerate(typed):
                if c in "([<":
                    depth2 += 1
                elif c in ")]>":
                    depth2 -= 1
                if depth2 == 0 and c in "={\n":
                    cut = idx
                    break
            ret = typed[:cut].strip() or "Unit"
            tail = typed[cut:]
        if re.match(r"\s*[={]", tail) is not None:
            i = pclose + 1
            continue  # defaulted: has a body, so not the seam

        names = list(dict.fromkeys(owner_params + method_params))
        positional = {n: f"#{k + 1}" for k, n in enumerate(names)}

        def normalise(kind: str) -> str:
            collapsed = re.sub(r"\s+", "", kind)
            for declared, slot in positional.items():
                collapsed = re.sub(r"\b" + re.escape(declared) + r"\b", slot, collapsed)
            return collapsed

        types: list[str] = []
        for raw in _split_top(param_text):
            param = raw.strip()
            if not param:
                continue
            param = re.sub(r"^(vararg\s+|noinline\s+|crossinline\s+)+", "", param)
            kind = param.split(":", 1)[1] if ":" in param else param
            types.append(normalise(kind.split("=")[0]))
        prefix = "suspend " if suspend else ""
        found.append((head.group(1), f"{prefix}({','.join(types)})->{normalise(ret)}"))
        i = pclose + 1
    return found, problems


def collect(root: pathlib.Path) -> tuple[list[Role], list[str]]:
    """(roles, problems) for the whole tree. problems is non-empty only on a parse that cannot be
    trusted, never on a merely undispositioned name.

    SELF-ACCOUNTING is the guard, not a second hand count. Every `fun interface` occurrence in the
    comment-blanked view must resolve to EXACTLY ONE outcome — a Role, or a named problem — and the
    totals are compared at the end. It catches the failure this parser is actually prone to: a
    branch that gives up on a declaration and moves the cursor on without recording anything, which
    would quietly shrink the denominator and make the wall greener. (The independent denominator
    lives in `denominator`, which asks ast-grep, and is run by
    checks/role-registry-selftest.sh — a regex cross-checking itself is a tautology, so that check
    does not belong inside this parser.)
    """
    roles: list[Role] = []
    problems: list[str] = []
    occurrences = 0
    resolved = 0
    for path in sorted(root.glob(SOURCE_GLOB)):
        rel = str(path.relative_to(root))
        code = code_view(path.read_text(encoding="utf-8"))
        for match in DECL.finditer(code):
            occurrences += 1
            name = match.group(1)
            line = code[:match.start()].count("\n") + 1
            cursor = match.end()
            owner_params: list[str] = []
            while cursor < len(code) and code[cursor] in " \t":
                cursor += 1
            if cursor < len(code) and code[cursor] == "<":
                end = _angle_end(code, cursor)
                if end < 0:
                    problems.append(f"{rel}:{line} {name}: unterminated type-parameter list")
                    resolved += 1
                    continue
                owner_params = _type_param_names(code[cursor + 1:end])
                cursor = end + 1
            brace = code.find("{", cursor)
            if brace < 0:
                problems.append(f"{rel}:{line} {name}: no interface body — this parser and the "
                                "source disagree, so no role list from this run can be trusted")
                resolved += 1
                continue
            end = _balanced(code, brace, "{", "}")
            if end < 0:
                problems.append(f"{rel}:{line} {name}: unbalanced interface body — this parser and "
                                "the source disagree, so no role list from this run can be trusted")
                resolved += 1
                continue
            methods, method_problems = _abstract_methods(code[brace + 1:end], owner_params)
            for problem in method_problems:
                problems.append(f"{rel}:{line} {name}: {problem}")
            if len(methods) != 1:
                problems.append(
                    f"{rel}:{line} {name}: parsed {len(methods)} abstract method(s); a `fun "
                    "interface` has exactly one by language rule, so this parser and the source "
                    "disagree and no signature from this run can be trusted"
                )
                resolved += 1
                continue
            method, signature = methods[0]
            roles.append(Role(name, rel, line, signature, method))
            resolved += 1
    if resolved != occurrences:
        problems.append(
            f"counted {occurrences} `fun interface` occurrence(s) in the comment-blanked sources "
            f"but resolved only {resolved} of them to a role or a named problem — a declaration "
            "this parser silently dropped shrinks the denominator, so no role list from this run "
            "can be trusted"
        )
    return roles, problems


def denominator(root: pathlib.Path) -> tuple[int, str]:
    """(count, detail) — an INDEPENDENT `fun interface` census, from ast-grep's Kotlin AST.

    The regex above cannot cross-check itself (§24: two hand-authored lists agreeing with each
    other is not a check against reality), so the external denominator comes from the parser CI
    already depends on for gate:rules. The rule matches `class_declaration` nodes whose text BEGINS
    with optional modifiers and then `fun interface` — anchored, because an unanchored regex also
    matches every enclosing class that merely CONTAINS a nested `fun interface` (measured
    2026-09-17: 202 unanchored vs 200 anchored, the two extras being GrokAuthJson and
    KimiRefreshedTokens, which each wrap one).

    Returns (-1, reason) when ast-grep cannot answer, so the caller decides whether that is fatal.
    """
    import json
    import shutil
    import subprocess

    binary = shutil.which("ast-grep")
    if binary is None:
        return -1, "ast-grep is not on PATH"
    rule = (
        "id: role-registry-denominator\n"
        "language: kotlin\n"
        "severity: hint\n"
        "message: fun interface\n"
        "files:\n"
        "  - gateway/*/src/main/**/*.kt\n"
        "rule:\n"
        "  kind: class_declaration\n"
        "  regex: '^((public|internal|private|protected|expect|actual|@\\w+)\\s+)*fun\\s+interface\\s'\n"
    )
    try:
        done = subprocess.run(
            [binary, "scan", "--inline-rules", rule, "--json=compact", str(root / "gateway")],
            capture_output=True, text=True, timeout=300,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return -1, f"ast-grep could not be run ({exc})"
    try:
        rows = json.loads(done.stdout or "[]")
    except json.JSONDecodeError:
        return -1, f"ast-grep produced no parseable JSON (exit {done.returncode}): {done.stderr[:200]}"
    return len(rows), f"ast-grep {len(rows)} declaration(s)"


def shared_groups(roles: list[Role]) -> dict[str, list[Role]]:
    """signature -> roles, for every signature carried by 2+ DISTINCT names."""
    by_signature: dict[str, list[Role]] = {}
    for role in roles:
        by_signature.setdefault(role.signature, []).append(role)
    return {
        signature: members
        for signature, members in by_signature.items()
        if len({member.name for member in members}) > 1
    }


def load_config(path: pathlib.Path) -> tuple[dict[str, dict], list[str]]:
    """signature -> entry, plus problems. A config that cannot be read is a failure, never a skip."""
    if not path.exists():
        return {}, [
            f"{path}: the disposition file is missing — with no dispositions every shared "
            "signature is an absence, so this cannot pass"
        ]
    try:
        doc = tomllib.loads(path.read_text(encoding="utf-8"))
    except tomllib.TOMLDecodeError as exc:
        return {}, [f"{path}: unparseable TOML ({exc}) — a disposition nobody can read is not one"]
    entries: dict[str, dict] = {}
    problems: list[str] = []
    for index, entry in enumerate(doc.get("groups", [])):
        signature = entry.get("signature")
        if not isinstance(signature, str) or not signature.strip():
            problems.append(f"{path}: groups[{index}] has no `signature`")
            continue
        if signature in entries:
            problems.append(
                f"{path}: two entries dispose `{signature}` — one signature, one disposition, or "
                "the second is dead text nobody reviews"
            )
            continue
        entries[signature] = entry
    return entries, problems


def audit(root: pathlib.Path, config_rel: str = CONFIG_REL) -> list[str]:
    roles, problems = collect(root)
    problems = list(problems)
    if not roles:
        problems.append(
            f"parsed 0 `fun interface` declarations under {SOURCE_GLOB} — refusing to pass "
            "vacuously, because a green over an empty denominator is what this wall exists to "
            "prevent"
        )
        return problems

    entries, config_problems = load_config(root / config_rel)
    problems.extend(config_problems)
    groups = shared_groups(roles)

    for signature, members in sorted(groups.items()):
        names = sorted({member.name for member in members})
        where = {member.name: member.at for member in members}
        entry = entries.get(signature)
        if entry is None:
            for name in names:
                siblings = ", ".join(n for n in names if n != name)
                problems.append(
                    f"NO DISPOSITION: {name} ({where[name]}) shares the signature {signature} with "
                    f"{siblings} and no entry in {config_rel} says they are distinct roles. Either "
                    f"reconcile the duplicate onto one interface, or add a [[groups]] entry with "
                    f"signature = \"{signature}\" and a written reason."
                )
            continue
        reason = entry.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            problems.append(
                f"{config_rel}: the entry for {signature} carries no reason — a disposition "
                "without a written reason is an absence wearing a label, and accounts for "
                f"{', '.join(names)} in name only"
            )
            continue
        if not isinstance(entry.get("dated"), str) or not str(entry.get("dated")).strip():
            problems.append(
                f"{config_rel}: the entry for {signature} carries no `dated` — an undated "
                "disposition cannot be aged out or reviewed"
            )
        listed = entry.get("names")
        if not isinstance(listed, list) or not all(isinstance(n, str) for n in listed):
            problems.append(f"{config_rel}: the entry for {signature} has no `names` list")
            continue
        for name in names:
            if name not in listed:
                siblings = ", ".join(n for n in names if n != name)
                problems.append(
                    f"NO DISPOSITION: {name} ({where[name]}) shares the signature {signature} with "
                    f"{siblings}. The entry in {config_rel} disposes that signature but does not "
                    f"list {name}: add it to `names` with the reason extended to cover it, or "
                    "reconcile it onto the interface that already holds this role."
                )
        for name in sorted(set(listed)):
            if name not in names:
                problems.append(
                    f"STALE DISPOSITION: {config_rel} lists {name} under {signature}, but no "
                    "interface of that name carries that signature any more. A disposition may not "
                    "outlive its subject — drop the name (and the entry, if it is the last one)."
                )

    for signature, entry in sorted(entries.items()):
        if signature not in groups:
            problems.append(
                f"STALE DISPOSITION: {config_rel} disposes {signature}, but that signature is no "
                "longer shared by two or more names in the tree. Remove the entry — a list that "
                "keeps entries nobody can reach is how an allowlist stops being reviewed."
            )
    return problems


# ── report ────────────────────────────────────────────────────────────────────────────────────────

def report(root: pathlib.Path, config_rel: str = CONFIG_REL) -> None:
    roles, problems = collect(root)
    groups = shared_groups(roles)
    entries, _ = load_config(root / config_rel)
    signatures = {role.signature for role in roles}
    repeated = {}
    for role in roles:
        repeated.setdefault(role.name, []).append(role)
    print(
        f"role-registry: {len(roles)} `fun interface` declaration(s) over {len(signatures)} "
        f"signature(s); {len(groups)} signature(s) shared by 2+ names; {len(entries)} disposition(s)"
    )
    for problem in problems:
        print(f"  UNTRUSTED: {problem}")
    for signature, members in sorted(groups.items(), key=lambda kv: (-len({m.name for m in kv[1]}), kv[0])):
        names = sorted({member.name for member in members})
        entry = entries.get(signature)
        state = "DISPOSED" if entry and str(entry.get("reason", "")).strip() else "NO DISPOSITION"
        print(f"  {signature}   [{len(names)} names]  {state}")
        for member in sorted(members, key=lambda m: (m.name, m.path)):
            mark = " " if entry and member.name in entry.get("names", []) else "!"
            print(f"    {mark} {member.name:32s} {member.at}  ({member.method})")
    same_name = {name: members for name, members in repeated.items() if len(members) > 1}
    if same_name:
        print(
            f"  (not graded: {len(same_name)} name(s) declared more than once — the SEPARATION law "
            "makes a per-vendor seam one role per vendor)"
        )
        for name, members in sorted(same_name.items()):
            print(f"    = {name}: " + ", ".join(f"{m.at} {m.signature}" for m in sorted(members, key=lambda m: m.path)))


# ── selftest ──────────────────────────────────────────────────────────────────────────────────────

_MODULE = "gateway/core/src/main/kotlin/splice/core"

COMPLIANT_SOURCE = '''package splice.core

/**
 * A KDoc that says `fun interface Decoy` in prose — the comment blanker must keep this out of the
 * denominator, and the parser-drift guard counts occurrences in the SAME blanked view.
 */
public fun interface ClientGone {
    public operator fun invoke(): Boolean
}

public fun interface ClientFrameEmitted {
    public operator fun invoke(): Boolean
}

/** A suspend seam of the "same" shape: proven NOT to join the group above. */
public fun interface Ticker {
    public suspend fun awaitTick(intervalMs: Long): Boolean
}

public fun interface PidAlive {
    public operator fun invoke(pid: Long): Boolean
}

/** One abstract method beside two DEFAULTED ones and two nested types — the shape that reads as
 *  seven `fun`s to a naive count (AccountCredentialIdentitySource). */
public fun interface IdentitySource {
    public fun identity(): String?

    public fun presence(): Presence = Presence.UNKNOWN

    public fun evidence(): Evidence {
        val id = identity()
        return Evidence(id)
    }

    public enum class Presence { PRESENT, UNKNOWN }

    public data class Evidence(public val id: String?) {
        public fun describe(): String = id ?: "none"
    }
}
'''

# Two generic seams whose type parameters are spelled differently: positional normalisation must
# put them in ONE group, or a rename hides a duplicate.
GENERIC_SOURCE = '''package splice.core

public fun interface CoalescedWork<T> {
    public suspend operator fun invoke(): T
}

public fun interface MaterializedRequest<R> {
    public suspend operator fun invoke(): R
}
'''

CONFIG_OK = '''[[groups]]
signature = "()->Boolean"
dated = "2026-09-17"
names = ["ClientFrameEmitted", "ClientGone"]
reason = "Read a few lines apart on the same retry path and driving OPPOSITE decisions."
'''

CONFIG_OK_GENERIC = CONFIG_OK + '''
[[groups]]
signature = "suspend ()->#1"
dated = "2026-09-17"
names = ["CoalescedWork", "MaterializedRequest"]
reason = "One coalesces concurrent callers onto a single in-flight computation; the other materialises a request body once per turn."
'''

CONFIG_BLANK_REASON = CONFIG_OK.replace(
    'reason = "Read a few lines apart on the same retry path and driving OPPOSITE decisions."',
    'reason = "   "',
)

CONFIG_NO_DATE = CONFIG_OK.replace('dated = "2026-09-17"\n', "")

CONFIG_STALE_NAME = CONFIG_OK.replace(
    'names = ["ClientFrameEmitted", "ClientGone"]',
    'names = ["ClientFrameEmitted", "ClientGone", "ClientVanished"]',
)

CONFIG_STALE_ENTRY = CONFIG_OK + '''
[[groups]]
signature = "(Zork)->Zork"
dated = "2026-09-17"
names = ["Gone", "Went"]
reason = "A signature no interface in the tree carries."
'''

# The synthetic duplicate the row requires: a THIRD `() -> Boolean` name the config does not list.
SYNTHETIC_DUPLICATE = '''
public fun interface ClientHungUp {
    public operator fun invoke(): Boolean
}
'''

# A `fun interface` the parser cannot account for: the drift guard must refuse the run.
DRIFT_SOURCE = COMPLIANT_SOURCE + '''
public fun interface Broken
'''


def _write(root: pathlib.Path, sources: dict[str, str], config: str | None) -> None:
    for name, text in sources.items():
        path = root / _MODULE / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    config_path = root / CONFIG_REL
    config_path.parent.mkdir(parents=True, exist_ok=True)
    if config is None:
        if config_path.exists():
            config_path.unlink()
    else:
        config_path.write_text(config, encoding="utf-8")


def _fresh(tmp: pathlib.Path, label: str) -> pathlib.Path:
    root = tmp / label
    root.mkdir(parents=True, exist_ok=True)
    return root


def selftest() -> int:
    failures: list[str] = []

    def case(label: str, sources: dict[str, str], config: str | None,
             want_red: bool, must_name: str = "") -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _fresh(pathlib.Path(tmp), "tree")
            _write(root, sources, config)
            hits = audit(root)
            if want_red and not hits:
                failures.append(f"{label}: must be RED, got GREEN")
            elif not want_red and hits:
                failures.append(f"{label}: must be GREEN, got: {hits}")
            elif want_red and must_name and not any(must_name in hit for hit in hits):
                failures.append(f"{label}: RED but not BY NAME ({must_name!r} absent): {hits}")

    # CONTROL: the compliant tree. Two roles sharing a shape, dispositioned with a reason; a
    # suspend sibling and a PidAlive sibling that must NOT join them; and an interface with
    # defaulted methods and nested types that must parse as ONE abstract method.
    case("control: dispositioned group, suspend and arity siblings apart",
         {"Ports.kt": COMPLIANT_SOURCE}, CONFIG_OK, want_red=False)

    # THE SYNTHETIC DUPLICATE the row requires: a third name in a dispositioned group.
    case("GROWTH: a third ()->Boolean name the disposition does not list",
         {"Ports.kt": COMPLIANT_SOURCE + SYNTHETIC_DUPLICATE}, CONFIG_OK,
         want_red=True, must_name="ClientHungUp")

    # An undispositioned group at all.
    case("NO DISPOSITION: a shared signature with no entry",
         {"Ports.kt": COMPLIANT_SOURCE}, config="", want_red=True, must_name="ClientGone")

    case("NO DISPOSITION: the config file is missing entirely",
         {"Ports.kt": COMPLIANT_SOURCE}, config=None, want_red=True, must_name="missing")

    # A reason that is present but empty — an absence wearing a label.
    case("UNREASONED: a disposition whose reason is whitespace",
         {"Ports.kt": COMPLIANT_SOURCE}, CONFIG_BLANK_REASON, want_red=True, must_name="no reason")

    case("UNDATED: a disposition with no `dated`",
         {"Ports.kt": COMPLIANT_SOURCE}, CONFIG_NO_DATE, want_red=True, must_name="no `dated`")

    # Staleness, both shapes — this is what detects the FIX row's success.
    case("STALE: a name in `names` that no interface carries",
         {"Ports.kt": COMPLIANT_SOURCE}, CONFIG_STALE_NAME,
         want_red=True, must_name="ClientVanished")

    case("STALE: an entry for a signature nothing shares",
         {"Ports.kt": COMPLIANT_SOURCE}, CONFIG_STALE_ENTRY,
         want_red=True, must_name="(Zork)->Zork")

    case("DUPLICATE ENTRY: one signature disposed twice",
         {"Ports.kt": COMPLIANT_SOURCE}, CONFIG_OK + CONFIG_OK,
         want_red=True, must_name="two entries dispose")

    # THE VACUOUS CASES — a wall that cannot fail is not a wall (§24).
    case("VACUOUS: zero interfaces must not pass",
         {"Empty.kt": "package splice.core\n\npublic class Nothing\n"}, CONFIG_OK,
         want_red=True, must_name="refusing to pass vacuously")

    case("UNTRUSTED: a `fun interface` with no body at all",
         {"Ports.kt": DRIFT_SOURCE}, CONFIG_OK, want_red=True, must_name="disagree")

    # THE BORING CASE: exactly one interface, no shared signature. GREEN, and the count is visible.
    with tempfile.TemporaryDirectory() as tmp:
        root = _fresh(pathlib.Path(tmp), "boring")
        _write(root, {"One.kt": "package splice.core\n\npublic fun interface Only {\n"
                                "    public operator fun invoke(): Boolean\n}\n"}, CONFIG_OK)
        hits = audit(root)
        # The lone interface is green on its own account; the shipped CONFIG_OK is now stale.
        if not any("STALE DISPOSITION" in hit for hit in hits):
            failures.append(f"boring case: a config entry for a vanished group must be STALE, got: {hits}")
        roles, problems = collect(root)
        if problems or len(roles) != 1 or shared_groups(roles):
            failures.append(
                f"boring case: expected exactly 1 role and 0 shared signatures, got "
                f"{len(roles)} role(s), {len(shared_groups(roles))} shared, problems={problems}"
            )
        _write(root, {}, "")
        if audit(root):
            failures.append(
                f"boring case: one interface and an empty config must be GREEN, got: {audit(root)}"
            )

    # GENERIC NORMALISATION: <T> and <R> are ONE group, so a rename cannot hide a duplicate.
    with tempfile.TemporaryDirectory() as tmp:
        root = _fresh(pathlib.Path(tmp), "generic")
        _write(root, {"Generic.kt": GENERIC_SOURCE}, "")
        roles, _ = collect(root)
        groups = shared_groups(roles)
        if list(groups) != ["suspend ()->#1"]:
            failures.append(
                f"generic normalisation: <T> and <R> must normalise into ONE group, got {list(groups)}"
            )
        if audit(root):
            pass  # undispositioned, so red — that is the point; the green twin is next
        _write(root, {"Generic.kt": GENERIC_SOURCE}, CONFIG_OK_GENERIC.replace(CONFIG_OK, ""))
        if audit(root):
            failures.append(f"generic normalisation: the dispositioned twin must be GREEN, got: {audit(root)}")

    # SUSPEND IS PART OF THE SIGNATURE: measured, not asserted.
    with tempfile.TemporaryDirectory() as tmp:
        root = _fresh(pathlib.Path(tmp), "suspend")
        _write(root, {"Ports.kt": COMPLIANT_SOURCE}, "")
        roles, _ = collect(root)
        by_name = {role.name: role.signature for role in roles}
        if by_name.get("Ticker") == by_name.get("PidAlive"):
            failures.append(
                "suspend must separate Ticker from PidAlive; both normalised to "
                f"{by_name.get('Ticker')}"
            )
        if by_name.get("IdentitySource") != "()->String?":
            failures.append(
                "an interface with defaulted methods and nested types must yield its ONE abstract "
                f"method, got {by_name.get('IdentitySource')!r}"
            )

    if failures:
        print("role-registry SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "role-registry SELFTEST OK — a dispositioned group is green; a third name joining it, an "
        "undispositioned group, a missing config, a whitespace reason, a missing date, a stale name, "
        "a stale entry, a doubled entry, zero interfaces and an unaccounted declaration are all red "
        "by name; suspend and arity keep distinct seams apart; <T> and <R> normalise into one group; "
        "the boring one-interface tree is green with count 1"
    )
    return 0


def main() -> int:
    argv = sys.argv[1:]
    if "--selftest" in argv:
        return selftest()
    root = ROOT
    config_rel = CONFIG_REL
    positional = [a for a in argv if not a.startswith("-") and a not in {"check", "report"}]
    if positional:
        root = pathlib.Path(positional[0])
    for arg in argv:
        if arg.startswith("--config="):
            config_rel = arg.split("=", 1)[1]
    if not root.exists():
        print(f"role-registry: tree {root} missing", file=sys.stderr)
        return 1
    root = root.resolve()
    if "report" in argv:
        report(root, config_rel)
        return 0
    problems = audit(root, config_rel)
    if problems:
        roles, _ = collect(root)
        print("role-registry RED:")
        for problem in problems:
            print("  " + problem)
        print(
            f"  (census: {len(roles)} `fun interface` declaration(s), "
            f"{len(shared_groups(roles))} signature(s) shared by 2+ names)"
        )
        return 1
    roles, _ = collect(root)
    print(
        f"role-registry GREEN: {len(roles)} `fun interface` declaration(s); every one of the "
        f"{len(shared_groups(roles))} shared signature(s) is accounted for in {config_rel} with a "
        "written reason"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
