#!/usr/bin/env python3
"""V4-92 — a module's PUBLIC surface must have a consumer in another module, or be internal.

WHY THIS EXISTS. Every library module in this tree runs under `explicitApi()`
(gateway/build-logic/src/main/kotlin/splice.module-law.gradle.kts), so every declaration
spells its visibility out loud — and `public` is what an author types when they are not
thinking about the module boundary, because it is what the compiler asks for and the
error message that demands it says nothing about who the reader is. The result measured
on 2026-09-17: 31 of the 41 public TYPES in :gateway are named by no other module's main
or testFixtures sources, and six public MEMBERS across the tree have only test callers.
A public declaration nobody outside the module consumes is not an API, it is a leak of
the module's internals into its ABI — the thing every architecture law above it is trying
to make inexpressible. `internal` is the same code with the boundary stated.

Nothing was measuring it. detekt has no such rule, the module law governs GRADLE edges and
not visibility, and Konsist's laws read packages rather than the consumer set. So the
surface grew for the whole campaign under a green gate, which is the same shape as the
2026-07-16 style pack that sat unrouted for a month (see checks/rule-routing.sh).

THE DENOMINATOR COMES FROM THE SOURCE, never a hand list (§24). Two files are parsed:
  · gateway/settings.gradle.kts — every include()d module path. That is the universe.
  · gateway/build-logic/.../splice.module-law.gradle.kts — `nonLibrary`, the set the build
    itself exempts from explicitApi (:app, :spikes, :arch-tests, :fir-checks). A module in
    that set has no explicit `public` to read and is not a library, so it is GRADED as a
    consumer and never as a producer. This is the row's "each non-:app module" read off
    the build rather than retyped: a module added to settings.gradle.kts tomorrow is in
    scope with no edit here, and a module moved into nonLibrary leaves scope the same way.
Two guards refuse a vacuous pass: zero library modules, or zero public declarations across
all of them, is a FAILURE and not a green — a checker that silently loses its denominator
is a checker that passes.

JUSTIFIED means a CONSUMER OUTSIDE THE MODULE, and the consumer set is main + testFixtures:
  · main sources of any other module, including the nonLibrary ones — :app is the
    composition root and consuming a library's API is its whole job;
  · testFixtures sources of any other module — a fixture is shipped, cross-module code, so
    a declaration a sibling's fixture needs is genuinely public.
A declaration is matched by its fully-qualified name (`import splice.x.Y` and a bare
`splice.x.Y` FQN use are the same token), or by a star import of its package.

src/test IS DELIBERATELY NOT A CONSUMER, and that is the point rather than an oversight.
A same-module test needs no visibility at all (`internal` is visible to the module's own
test source set), and a SIBLING module's test reaching a type is the shape audit row D 12
found six times over — Turn.trimToLast, Mirror.extractThinking,
CompactClassifier.markerPresent, UpstreamFailureClassifier.mapOutStatus,
GrokOAuth.grokRefreshForm, GrokAuthProvider.ineffectiveRefreshCount, each public solely so
a test could reach it. Counting test callers would make this wall green over exactly the
population it exists to name.

THE RATCHET. 156 declarations are unjustified today, so `internal or bust` cannot be the
gate leg without finishing the fix row first, and 156 exemptions would be the laundering a
baseline exists to prevent. What IS enforceable today is the DIRECTION, in the idiom
checks/concentration.py already uses for the HIGH band:
  · GROWTH fails. A public declaration that no other module consumes, and that the baseline
    does not record, is red BY NAME on the commit that adds it.
  · A STALE ENTRY fails. A baseline line whose declaration is gone, has become internal, or
    has gained a real consumer is a hard error with the remedy printed — a baseline held
    above the measured surface is unearned room for the next regression to hide in, which is
    the defect checks/concentration.py records twice (the 6.14 UpstreamClient ceiling).
  · IT CANNOT BE SATISFIED BY WEAKENING. Shrinking the baseline is the remedy; growing it is
    a dated diff that reads as exactly what it is.
The fix row burns the baseline down; `--write-baseline` reprints it from measurement, and the
resulting diff is the record of what moved.

NOT CAUGHT, stated here rather than discovered later.
  · MEMBERS. This wall reads TOP-LEVEL declarations. The six test-only symbols above are
    public MEMBERS of public types, referenced as `x.trimToLast(...)` with no FQN and no
    import, so no name-based rule can attribute them to their owner without a resolved type
    graph. They are recorded by name in the V4-92 ledger note as the fix row's inventory;
    catching them mechanically needs a compiler plugin (:fir-checks is where that would go),
    not a regex.
  · A DECLARATION CONSUMED ONLY BY A STRING. Reflection, a serializer name, a DI key — the
    FQN never appears, so the declaration reads as unjustified. The remedy is the same as for
    any false red: the fix row makes it internal and the compiler says so immediately.
  · SAME-PACKAGE CROSS-MODULE USE. Kotlin needs no import when two modules share a package,
    so such a use would be invisible here. Measured 2026-09-17: no package in this tree is
    declared by two modules, so the hole is empty today; the FQN and star-import matchers are
    what would have to grow if that ever changes, and this paragraph is the marker.

SELFTEST. `--selftest` builds temp trees and proves BOTH directions plus the boring cases:
GREEN on a consumed public declaration, on an internal one, and on a star-imported one;
RED BY NAME on a synthetic unjustified public type, on one whose only caller is a sibling's
src/test, on a baseline entry that no longer offends, on a baseline entry whose declaration
is gone, on a tree with no library modules, and on a tree whose parse yields no public
declarations at all.
"""
from __future__ import annotations

import argparse
import io
import json
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

# The two build files that ARE the denominator. Fixed paths on purpose: a checker that
# silently loses its source is a checker that passes.
GRADLE_ROOT_REL = "gateway"
SETTINGS_REL = "gateway/settings.gradle.kts"
MODULE_LAW_REL = "gateway/build-logic/src/main/kotlin/splice.module-law.gradle.kts"
BASELINE_REL = "checks/config/public-surface-baseline.json"

MODULE_PATH = re.compile(r'"(:[A-Za-z0-9._-]+)"')
NON_LIBRARY = re.compile(r"val nonLibrary = setOf\(([^)]*)\)", re.DOTALL)

# explicitApi() makes the modifier mandatory, so `public` at column 0 IS the public
# top-level surface. Every Kotlin spelling of a declaration is admitted — `fun interface`
# and `annotation class` included, the dodge checks/concentration.py records as DR-51.
PUBLIC_DECL = re.compile(
    r"^public\s+(?:(?:sealed|data|abstract|open|value|enum|fun|annotation|suspend|inline|expect|external|const)\s+)*"
    r"(class|interface|object|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
PACKAGE = re.compile(r"^package\s+([A-Za-z0-9_.]+)", re.MULTILINE)
STAR_IMPORT = re.compile(r"^import\s+([A-Za-z0-9_.]+)\.\*", re.MULTILINE)


class Declaration:
    """One public top-level declaration, and where it lives."""

    def __init__(self, module: str, package: str, name: str, kind: str, rel: str, line: int) -> None:
        self.module = module
        self.package = package
        self.name = name
        self.kind = kind
        self.rel = rel
        self.line = line

    @property
    def fqn(self) -> str:
        return f"{self.package}.{self.name}" if self.package else self.name

    @property
    def id(self) -> str:
        """Baseline identity: module + FQN. Deliberately NOT the file or the line — those
        churn on a move that changes nothing about the surface, and a baseline that goes
        stale on a rename teaches the reader to regenerate it without reading."""
        return f"{self.module} {self.fqn}"

    def locus(self) -> str:
        return f"{self.rel}:{self.line}"


def modules_of(root: pathlib.Path) -> tuple[list[str], set[str], list[str]]:
    """(every included module, the nonLibrary set, problems) — both read off the build."""
    problems: list[str] = []
    settings = root / SETTINGS_REL
    law = root / MODULE_LAW_REL
    if not settings.exists():
        return [], set(), [f"{SETTINGS_REL}: missing — the module universe IS the denominator, so its absence cannot pass"]
    if not law.exists():
        return [], set(), [f"{MODULE_LAW_REL}: missing — nonLibrary is what tells a producer from a consumer"]
    included = sorted(set(MODULE_PATH.findall(settings.read_text(encoding="utf-8"))))
    match = NON_LIBRARY.search(law.read_text(encoding="utf-8"))
    if match is None:
        problems.append(
            f"{MODULE_LAW_REL}: `val nonLibrary = setOf(...)` not found — the producer/consumer "
            "split cannot be derived, so no surface from this run can be trusted"
        )
        return included, set(), problems
    non_library = set(MODULE_PATH.findall(match.group(1)))
    return included, non_library, problems


def source_text(root: pathlib.Path, module: str, *subs: str) -> list[tuple[str, str]]:
    """[(relative path, text)] for every .kt under the given source sets of [module]."""
    out: list[tuple[str, str]] = []
    for sub in subs:
        directory = root / GRADLE_ROOT_REL / module.lstrip(":") / sub
        if not directory.is_dir():
            continue
        for path in sorted(directory.rglob("*.kt")):
            out.append((str(path.relative_to(root)), path.read_text(encoding="utf-8", errors="replace")))
    return out


def declarations(root: pathlib.Path, module: str) -> list[Declaration]:
    found: list[Declaration] = []
    for rel, text in source_text(root, module, "src/main/kotlin"):
        package_match = PACKAGE.search(text)
        package = package_match.group(1) if package_match else ""
        for number, line in enumerate(text.splitlines(), start=1):
            declaration = PUBLIC_DECL.match(line)
            if declaration is None:
                continue
            found.append(
                Declaration(module, package, declaration.group(2), declaration.group(1), rel, number)
            )
    return found


def consumers(root: pathlib.Path, modules: list[str]) -> dict[str, tuple[str, set[str]]]:
    """module -> (its consuming text, the packages it star-imports).

    main + testFixtures only. src/test is NOT here; see the module docstring — a sibling's
    test caller is the population this wall exists to name, not a justification."""
    out: dict[str, tuple[str, set[str]]] = {}
    for module in modules:
        texts = [text for _, text in source_text(root, module, "src/main/kotlin", "src/testFixtures/kotlin")]
        blob = "\n".join(texts)
        out[module] = (blob, set(STAR_IMPORT.findall(blob)))
    return out


def unjustified(root: pathlib.Path) -> tuple[list[Declaration], int, list[str]]:
    """(offenders, declarations examined, problems)."""
    included, non_library, problems = modules_of(root)
    if problems:
        return [], 0, problems
    libraries = [module for module in included if module not in non_library]
    if not libraries:
        return [], 0, [
            f"{SETTINGS_REL}: zero library modules after removing nonLibrary "
            f"({sorted(non_library)}) — refusing to pass vacuously, because a green over an "
            "empty denominator is what this wall exists to prevent"
        ]
    every = consumers(root, included)
    offenders: list[Declaration] = []
    examined = 0
    for module in libraries:
        for declaration in declarations(root, module):
            examined += 1
            token = re.compile(r"(?<![\w.])" + re.escape(declaration.fqn) + r"(?![\w])")
            justified = False
            for other, (blob, stars) in every.items():
                if other == module:
                    continue
                if declaration.package in stars or token.search(blob):
                    justified = True
                    break
            if not justified:
                offenders.append(declaration)
    if examined == 0:
        return [], 0, [
            f"parsed 0 public top-level declarations across {len(libraries)} library "
            "module(s) — every one of them runs under explicitApi(), so a zero here is a "
            "broken parser rather than a clean surface, and it must not read as green"
        ]
    return sorted(offenders, key=lambda d: d.id), examined, []


# ── the baseline ──────────────────────────────────────────────────────────────────────

BASELINE_LAW = (
    "V4-92 ratchet. Each entry is '<module> <fqn>': a public top-level declaration no other "
    "module's main or testFixtures sources name. Every line is DEBT, not permission — the gate "
    "fails when a declaration NOT listed here joins them (growth) and when a listed one stops "
    "offending (stale). Shrink it with `python3 checks/public-surface.py --write-baseline`; the "
    "diff is the record of what moved."
)
RECORDED = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def read_baseline(root: pathlib.Path) -> tuple[set[str], str, list[str]]:
    path = root / BASELINE_REL
    if not path.exists():
        return set(), "", [
            f"{BASELINE_REL}: missing — the ratchet has no baseline to grade against. Write one "
            "with `python3 checks/public-surface.py --write-baseline`."
        ]
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return set(), "", [f"{BASELINE_REL}: is not valid JSON ({error}) — a baseline nobody can parse grades nothing"]
    recorded = str(data.get("recorded", ""))
    problems: list[str] = []
    if not RECORDED.match(recorded):
        problems.append(
            f"{BASELINE_REL}: `recorded` is {recorded!r} — every baseline carries the ISO date it "
            "was measured, exactly as checks/concentration.py's RATCHET_RECORDED does; an undated "
            "baseline is how the next regression hides."
        )
    entries = data.get("offenders")
    if not isinstance(entries, list) or not all(isinstance(entry, str) for entry in entries):
        problems.append(f"{BASELINE_REL}: `offenders` must be a list of '<module> <fqn>' strings")
        return set(), recorded, problems
    return set(entries), recorded, problems


def write_baseline(root: pathlib.Path, offenders: list[Declaration], today: str) -> None:
    path = root / BASELINE_REL
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "recorded": today,
                "law": BASELINE_LAW,
                "offenders": [declaration.id for declaration in offenders],
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def ratchet(root: pathlib.Path) -> int:
    offenders, examined, problems = unjustified(root)
    baseline, recorded, baseline_problems = read_baseline(root)
    problems = problems + baseline_problems
    measured = {declaration.id: declaration for declaration in offenders}

    print(f"PUBLIC SURFACE RATCHET — baseline recorded {recorded or '(none)'}")
    print(f"  {'public top-level declarations':<34} measured {examined:>4}")
    print(f"  {'unjustified (no other-module use)':<34} measured {len(measured):>4}   baseline {len(baseline):>4}   [GATED]")

    growth = sorted(set(measured) - baseline)
    stale = sorted(baseline - set(measured))
    if growth:
        problems.append(
            f"GROWTH: {len(growth)} public declaration(s) no other module consumes are not in the "
            f"baseline. Make each one `internal` (the same code, with the module boundary stated), "
            f"or — if a consumer is genuinely coming — record it with "
            f"`python3 checks/public-surface.py --write-baseline`, which is a dated diff saying the "
            f"surface grew:\n    " + "\n    ".join(
                f"{entry}  ({measured[entry].kind} at {measured[entry].locus()})" for entry in growth
            )
        )
    if stale:
        problems.append(
            f"STALE: {len(stale)} baseline entry(ies) no longer offend — the declaration is gone, "
            f"became internal, or gained a real consumer. Re-measure with "
            f"`python3 checks/public-surface.py --write-baseline`. A baseline held above the "
            f"measured surface is unearned room for the next regression to hide in:\n    "
            + "\n    ".join(stale)
        )

    if problems:
        print(f"\nFAIL: public-surface ratchet — {len(problems)} problem(s):", file=sys.stderr)
        for problem in problems:
            print("  x " + problem, file=sys.stderr)
        return 1
    print(
        f"\nOK: public-surface ratchet holds — the {len(measured)} unjustified declaration(s) are "
        f"exactly the {recorded} baseline, and nothing listed there has stopped offending"
    )
    return 0


def report(root: pathlib.Path) -> int:
    offenders, examined, problems = unjustified(root)
    for problem in problems:
        print("  UNTRUSTED: " + problem)
    by_module: dict[str, list[Declaration]] = {}
    for declaration in offenders:
        by_module.setdefault(declaration.module, []).append(declaration)
    print(f"public-surface: {examined} public top-level declaration(s), {len(offenders)} unjustified")
    for module in sorted(by_module):
        print(f"\n  {module} — {len(by_module[module])} unjustified:")
        for declaration in by_module[module]:
            print(f"    {declaration.kind:9s} {declaration.fqn:62s} {declaration.locus()}")
    return 1 if problems else 0


# ── selftest ──────────────────────────────────────────────────────────────────────────

SETTINGS_FIXTURE = """rootProject.name = "fixture"
include(
    ":lib",
    ":other",
    ":app",
)
"""

LAW_FIXTURE = """val moduleLaw: Map<String, Set<String>> = mapOf(":lib" to emptySet())
val nonLibrary = setOf(":app")
"""


def write_module(root: pathlib.Path, module: str, sub: str, rel: str, text: str) -> None:
    path = root / GRADLE_ROOT_REL / module.lstrip(":") / sub / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def fixture(root: pathlib.Path, settings: str = SETTINGS_FIXTURE, law: str = LAW_FIXTURE) -> None:
    (root / SETTINGS_REL).parent.mkdir(parents=True, exist_ok=True)
    (root / SETTINGS_REL).write_text(settings, encoding="utf-8")
    (root / MODULE_LAW_REL).parent.mkdir(parents=True, exist_ok=True)
    (root / MODULE_LAW_REL).write_text(law, encoding="utf-8")


def baseline_fixture(root: pathlib.Path, entries: list[str], recorded: str = "2026-09-17") -> None:
    path = root / BASELINE_REL
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"recorded": recorded, "law": BASELINE_LAW, "offenders": entries}, indent=2) + "\n",
        encoding="utf-8",
    )


def selftest() -> int:  # noqa: C901 — one arm per fixture; splitting it hides the census
    failures: list[str] = []

    def arm(label: str, build, expect_red: str | None) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            build(root)
            offenders, _, problems = unjustified(root)
            # The arms assert the VERDICT, so the ratchet's own reporting is captured rather
            # than interleaved — a selftest whose output is 200 lines of fixture chatter is a
            # selftest nobody reads, and the reason a red arm goes unnoticed.
            captured = io.StringIO()
            stdout, stderr = sys.stdout, sys.stderr
            sys.stdout = sys.stderr = captured
            try:
                code = ratchet(root)
            finally:
                sys.stdout, sys.stderr = stdout, stderr
            names = [declaration.id for declaration in offenders] + problems
            if expect_red is None:
                if code != 0 or problems:
                    failures.append(f"{label} — must be GREEN, got exit {code}: {names}")
            else:
                if code == 0:
                    failures.append(f"{label} — MUST be RED, exited 0 (offenders {names})")
                elif expect_red not in captured.getvalue():
                    failures.append(
                        f"{label} — red for the wrong reason (expected {expect_red!r}): "
                        + captured.getvalue().strip().replace("\n", " ")[:220]
                    )

    # 1. a consumed public declaration is not a finding, and neither is an internal one.
    def consumed(root: pathlib.Path) -> None:
        fixture(root)
        write_module(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class Api\ninternal class Hidden\n")
        write_module(root, ":other", "src/main/kotlin", "Use.kt", "package fix.other\nimport fix.lib.Api\ninternal class Use(val a: Api)\n")
        baseline_fixture(root, [])
    arm("1. a public declaration another module imports is JUSTIFIED (and `internal` is out of scope)", consumed, None)

    # 2. THE MUTATION THIS ROW REQUIRES: an unjustified public type, baseline empty.
    def synthetic(root: pathlib.Path) -> None:
        consumed(root)
        write_module(root, ":lib", "src/main/kotlin", "Leak.kt", "package fix.lib\npublic class SelftestLeak(val v: Int)\n")
    arm("2. GROWTH — a synthetic unjustified public type with an empty baseline", synthetic, "GROWTH")
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        synthetic(root)
        offenders, _, _ = unjustified(root)
        if not any(declaration.name == "SelftestLeak" for declaration in offenders):
            failures.append(f"2. the synthetic leak must be named: {[d.id for d in offenders]}")

    # 3. the same tree with the offender recorded: the ratchet HOLDS.
    def recorded(root: pathlib.Path) -> None:
        synthetic(root)
        baseline_fixture(root, [":lib fix.lib.SelftestLeak"])
    arm("3. a RECORDED offender is not growth — the ratchet holds", recorded, None)

    # 4. a sibling module's src/test caller does NOT justify (audit D row 12's shape).
    def test_only(root: pathlib.Path) -> None:
        fixture(root)
        write_module(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class TestOnly(val v: Int)\n")
        write_module(root, ":other", "src/test/kotlin", "T.kt", "package fix.other\nimport fix.lib.TestOnly\nclass T { fun t() = TestOnly(1) }\n")
        baseline_fixture(root, [])
    arm("4. a caller in a sibling's src/test is NOT a justification", test_only, "GROWTH")

    # 5. a testFixtures caller IS a justification — a fixture is shipped cross-module code.
    def fixtures_ok(root: pathlib.Path) -> None:
        fixture(root)
        write_module(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class Shared(val v: Int)\n")
        write_module(root, ":other", "src/testFixtures/kotlin", "F.kt", "package fix.other\nimport fix.lib.Shared\npublic class F(val s: Shared)\n")
        baseline_fixture(root, [])
    arm("5. a caller in a sibling's src/testFixtures IS a justification", fixtures_ok, None)

    # 6. a star import of the package justifies every declaration in it.
    def star(root: pathlib.Path) -> None:
        fixture(root)
        write_module(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class Starred(val v: Int)\n")
        write_module(root, ":other", "src/main/kotlin", "Use.kt", "package fix.other\nimport fix.lib.*\ninternal class Use(val s: Starred)\n")
        baseline_fixture(root, [])
    arm("6. a star import of the package is a justification", star, None)

    # 7. STALE — a baseline entry that no longer offends.
    def stale_justified(root: pathlib.Path) -> None:
        consumed(root)
        baseline_fixture(root, [":lib fix.lib.Api"])
    arm("7. STALE — a baseline entry that has gained a consumer", stale_justified, "STALE")

    def stale_gone(root: pathlib.Path) -> None:
        consumed(root)
        baseline_fixture(root, [":lib fix.lib.DeletedLongAgo"])
    arm("8. STALE — a baseline entry whose declaration no longer exists", stale_gone, "STALE")

    # 9. an undated baseline is a hard error, not a pass.
    def undated(root: pathlib.Path) -> None:
        consumed(root)
        baseline_fixture(root, [], recorded="")
    arm("9. an undated baseline is a hard error", undated, "recorded")

    # 10. THE BORING CASES, which are the ones that get waved through (§24).
    def no_modules(root: pathlib.Path) -> None:
        fixture(root, settings='rootProject.name = "fixture"\ninclude(\n    ":app",\n)\n')
        write_module(root, ":app", "src/main/kotlin", "M.kt", "package fix.app\nclass M\n")
        baseline_fixture(root, [])
    arm("10. a tree whose every module is nonLibrary must REFUSE, not pass vacuously", no_modules, "vacuously")

    def no_declarations(root: pathlib.Path) -> None:
        fixture(root)
        write_module(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\ninternal class OnlyInternal\n")
        baseline_fixture(root, [])
    arm("11. a library tree with zero public declarations is a broken parse, not a clean surface", no_declarations, "broken parser")

    def one_declaration(root: pathlib.Path) -> None:
        fixture(root)
        write_module(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class One(val v: Int)\n")
        baseline_fixture(root, [":lib fix.lib.One"])
    arm("12. the one-item tree grades green WITH its count", one_declaration, None)

    def no_law(root: pathlib.Path) -> None:
        (root / SETTINGS_REL).parent.mkdir(parents=True, exist_ok=True)
        (root / SETTINGS_REL).write_text(SETTINGS_FIXTURE, encoding="utf-8")
        baseline_fixture(root, [])
    arm("13. a missing module law is a hard error — the producer/consumer split is derived from it", no_law, "missing")

    if failures:
        print("public-surface SELFTEST FAIL:")
        for failure in failures:
            print("  x " + failure)
        return 1
    print(
        "public-surface SELFTEST OK — a consumed declaration, an internal one, a "
        "testFixtures consumer, a star import and a recorded offender are green; a synthetic "
        "unjustified public type, a sibling test-only caller, a stale baseline entry (justified "
        "and deleted), an undated baseline, a tree with no library modules, a tree with no public "
        "declarations and a missing module law are all red"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", help="tree to measure (default: this repo)")
    parser.add_argument("--ratchet", action="store_true", help="gate leg: fail on growth or a stale baseline entry")
    parser.add_argument("--write-baseline", action="store_true", help="reprint the baseline from measurement")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    root = pathlib.Path(args.root).resolve() if args.root else ROOT
    if not root.exists():
        print(f"public-surface: {root} does not exist", file=sys.stderr)
        return 2

    if args.write_baseline:
        import datetime

        offenders, examined, problems = unjustified(root)
        if problems:
            print("refusing to write a baseline from an untrusted measurement:", file=sys.stderr)
            for problem in problems:
                print("  x " + problem, file=sys.stderr)
            return 2
        today = datetime.date.today().isoformat()
        write_baseline(root, offenders, today)
        print(
            f"wrote {BASELINE_REL}: {len(offenders)} unjustified of {examined} public top-level "
            f"declaration(s), recorded {today}. READ THE DIFF — it is the record of what moved."
        )
        return 0

    if args.json:
        offenders, examined, problems = unjustified(root)
        print(
            json.dumps(
                {
                    "examined": examined,
                    "problems": problems,
                    "offenders": [
                        {
                            "module": d.module,
                            "fqn": d.fqn,
                            "kind": d.kind,
                            "file": d.rel,
                            "line": d.line,
                        }
                        for d in offenders
                    ],
                },
                indent=2,
            )
        )
        return 1 if problems else 0

    if args.ratchet:
        return ratchet(root)
    return report(root)


if __name__ == "__main__":
    sys.exit(main())
