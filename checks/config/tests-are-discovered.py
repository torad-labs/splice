#!/usr/bin/env python3
"""V4-68 — a test JUnit never DISCOVERED is a green suite with a hole in it.

WHY THIS EXISTS. A @Test method whose body returns a non-Unit value is not discovered by
JUnit: no failure, no skip, no warning, no line in any report. The suite is green, the
XML is complete-looking, and the test has never run once in its life. Measured 2026-09-16:
gateway/gateway/src/test/kotlin/head/HeadServerCapacityTest.kt declares four @Test methods
and TEST-head.HeadServerCapacityTest.xml reports tests=3 — the fourth ends in held.await()
inside `= runBlocking { ... }`, so the method returns a String, and it had never executed.
Nothing we own could see it: every gate reads what ran, and nothing compared that against
what was DECLARED.

THE INSTRUMENT TRAP, measured while confirming the above, and the reason this wall ANCHORS
ON THE XML AND NEVER ON THE SHAPE OF THE SOURCE. A naive scan for an expression-bodied
@Test flags all FOUR methods in that class, because three of them end in a Unit-valued
expression while the fourth does not — and kotlinx's TestResult is a typealias for Unit on
the JVM, so `= runTest { }` is discovered and fine. A checker reasoning from syntax is
wrong in BOTH directions: it accuses the innocent and can be fooled by the guilty. So the
source here supplies only the DENOMINATOR (what was declared) and the XML supplies the
OBSERVATION (what ran). The comparison is the whole check.

DENOMINATOR, from the SOURCE. For every .kt file under gateway/*/src/test/kotlin, each data
class is found by a string- and comment-aware scan, and its test methods are counted at
MEMBER depth only — a nested class's tests are its own, and a local function inside a test
body is nobody's. That is what makes the count comparable to a per-class XML row.

OBSERVATION, from the JUnit XML. gateway/<module>/build/test-results/test/*.xml, one file
per class, each holding a testcase count and the testcase NAMES — so a shortfall can name
the method that never ran instead of only a number.

SHAPES. XML count LOWER than the denominator fails BY NAME, naming the missing methods.
Two shapes may legitimately report a HIGHER count, and each needs a written reason in
DISPOSITIONS below — never a bare allowlist entry: a @ParameterizedTest expands one method
into N cases, and a class may INHERIT test methods from a base class. A class with no XML
at all also needs a written reason (a test task that is disabled by configuration is a
decision, not an accident — but it is a decision someone must WRITE DOWN).

WHAT IT CANNOT SEE. A class that never compiles is not in any XML and not in this scan's
dispositions unless its module is dispositioned. A test source set outside gateway/* is not
scanned. And a test that runs but asserts nothing is discovered, counted, and useless —
this wall counts executions, it cannot judge them. Finally the reverse drift — an XML row
for a class that no longer exists in the source — is REPORTED by --report and not failed:
it is stale build output, not a hole in the suite.
"""
from __future__ import annotations

import pathlib
import re
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]

TEST_SOURCES = "gateway/*/src/test/kotlin/**/*.kt"
TEST_RESULTS = "gateway/*/build/test-results/test/*.xml"

# Classes whose XML count is legitimately HIGHER than the source denominator. Every entry
# needs a written reason — the reason is the disposition, and an empty one fails the wall.
# Never add a bare class name here: if the reason is not known, the finding is real.
# name -> (reason, expected observed count). The COUNT is part of the disposition on purpose:
# a reason typed once and never revisited is a waiver that outlives its proof, so if the
# observed count moves away from the expected one the wall reds and it must be re-earned.
DISPOSITIONS: dict[str, tuple[str, int]] = {
    # Every entry below was verified against the class's own annotations before it was written,
    # and the count beside it is the observed XML count the disposition was earned for. Each is
    # the same shape: @ParameterizedTest expands one method into N cases, which a per-method
    # source count cannot see. (Inherited test methods would be the other shape; measured across
    # all six, none of them is explained by inheritance — CodexCodeModeActiveInterruptionTest and
    # CodexCodeModeInfrastructureTest do extend CodeModeBridgeTestSupport, but that base declares
    # no tests, and their declared count matches their own annotations exactly.)
    "ResponsesWsRunnerTest": ("3 @ParameterizedTest methods expand to 9 cases (12 @Test + 9 = 21)", 21),
    "CodexCodeModeReanchorTest": ("1 @ParameterizedTest expands to 2 cases (1 @Test + 2 = 3)", 3),
    "CodexAuthAbsenceTest": ("1 @ParameterizedTest expands to 4 cases (2 @Test + 4 = 6)", 6),
    "CodexCodeModeActiveInterruptionTest": ("1 @ParameterizedTest expands to 4 cases; no plain @Test", 4),
    "CodexCodeModeInfrastructureTest": ("1 @ParameterizedTest expands to 2 cases; no plain @Test", 2),
    "SseReaderTest": ("1 @ParameterizedTest expands to 6 cases (11 @Test + 6 = 17)", 17),
}

# Modules whose test task is disabled BY CONFIGURATION, so no XML can exist. The reason is
# the disposition; cite where the decision lives.
MODULE_DISPOSITIONS: dict[str, str] = {
    "spikes": (
        "its test task is DISABLED by configuration, so no XML can exist: "
        "gateway/spikes/build.gradle.kts sets `enabled = providers.gradleProperty('runSpikes')"
        ".isPresent` and says in its own comment that spikes are experiments with receipts, not "
        "CI tests, run explicitly with -PrunSpikes. The six spike classes are consequently "
        "UNOBSERVED by this wall by design — a decision recorded here rather than an absence "
        "waved through"
    ),
}

CLASS_DECL = re.compile(r"\bclass\s+(\w+)")
MEMBER_ITEM = re.compile(r"@(Test|ParameterizedTest)\b|\bfun\s+(`[^`]+`|\w+)\s*\(")
# The same shape read from the ORIGINAL text, where a backtick name is still spelled out.
UNMASKED_FUN = re.compile(r"\bfun\s+(`[^`\n]+`|\w+)\s*\(")

# Where a declaration ENDS without a body: a blank line, or a line at COLUMN 0 that starts
# another declaration. Column 0 is load-bearing — an indented `val`/`var` is a constructor
# parameter of the very class being scanned, and treating it as a boundary would hide that
# class and its tests from the denominator entirely, which is a silent miss rather than a
# loud one. (A nested body-less declaration followed by an INDENTED declaration is the
# residual hole; no such shape exists in the tree today, measured: the fix removed exactly
# the 8 false spikes classes and no real one.)
DECL_END = re.compile(r"\n[ \t]*\n|\n(?:@|class|object|interface|fun|enum|val|var)\b")

# JUnit's own discovery rule, which is what this wall is really asserting: a @Test method
# must be public and return void. Kotlin enforces neither, so the shape is a trap.
INHERIT = re.compile(r"\bclass\s+\w+[^{]*?:\s*([A-Za-z_][\w.]*)\s*(?:\(|\{|$)", re.MULTILINE)


class TestClass:
    def __init__(self, module: str, name: str, path: str, methods: list[str]) -> None:
        self.module = module
        self.name = name
        self.path = path
        self.methods = methods

    @property
    def count(self) -> int:
        return len(self.methods)


def mask(source: str) -> str:
    """Blank every comment and string BODY, preserving length and newlines.

    One scanner, not two: comments and strings both hide `class`/`fun`/braces from the
    structure scan, and a literal left intact is a false class — the first version of this
    file reported classes named `Heads`, `per` and `name`, every one of them a phrase inside
    a triple-quoted JSON body, because Kotlin's TRIPLE-quoted strings were not recognised
    and `\"\"\"` was read as an empty string followed by another string. Length-preserving so
    offsets into the masked text still address the original.
    """
    out: list[str] = []
    i = 0
    n = len(source)
    plain = {"\n": "\n"}
    while i < n:
        ch = source[i]
        if ch == "`":
            # Kotlin's backtick identifier — how the test names are spelled here. Its body is
            # masked, NOT scanned for quotes: 125 methods in this tree carry an apostrophe
            # inside a backtick name ("the operator's servers..."), and reading that `'` as a
            # character literal swallowed the rest of the line, braces and all, which is what
            # made class bodies close early and counts read as 1.
            end = source.find("`", i + 1)
            stop = n if end < 0 else end + 1
            out.extend(plain.get(c, "x") for c in source[i:stop])
            i = stop
            continue
        if ch == "/" and i + 1 < n and source[i + 1] == "/":
            nl = source.find("\n", i)
            if nl < 0:
                break
            out.extend(plain.get(c, " ") for c in source[i:nl])
            i = nl
            continue
        if ch == "/" and i + 1 < n and source[i + 1] == "*":
            end = source.find("*/", i + 2)
            stop = n if end < 0 else end + 2
            out.extend(plain.get(c, " ") for c in source[i:stop])
            i = stop
            continue
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            stop = n if end < 0 else end + 3
            out.extend(plain.get(c, "x") for c in source[i:stop])
            i = stop
            continue
        if ch == '"' or ch == "'":
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] == ch or source[j] == "\n":
                    break
                j += 1
            stop = min(j + 1, n)
            out.extend(plain.get(c, "x") for c in source[i:stop])
            i = stop
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def body_range(masked: str, start: int) -> tuple[int, int] | None:
    """(open_brace, close_brace) of the declaration whose text starts at start, or None.

    The brace must appear before the declaration ENDS, which is what the boundary scan is
    for: a declaration with NO body — `data class Topology(val daemon: DaemonConfig, ...)`
    in the spikes — is followed by the next class's `{`, and taking that one made every
    constructor-only data class look like a test class holding its neighbour's tests."""
    limit = len(masked)
    boundary = DECL_END.search(masked, start)
    if boundary is not None:
        limit = boundary.start()
    open_at = masked.find("{", start)
    if open_at < 0 or open_at > limit:
        return None
    depth = 0
    i = open_at
    while i < len(masked):
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                return open_at, i
        i += 1
    return None


def member_items(original: str, masked: str) -> list[str]:
    """The names of the test methods declared at MEMBER depth of a class body.

    A method counts when a @Test/@ParameterizedTest annotation precedes it at member depth:
    the fun that follows a pending annotation is the annotated one, so helper functions and
    local functions are not mistaken for tests. Nested classes are skipped wholesale — their
    members belong to them, and the XML reports them under their own qualified name.

    Names are read from the ORIGINAL text: a backtick name is masked to x's in [masked], and
    the XML quotes the name, so the masked copy cannot supply it."""
    items: list[str] = []
    depth = 0
    i = 0
    pending = 0
    while i < len(masked):
        ch = masked[i]
        if ch == "{":
            depth += 1
            i += 1
            continue
        if ch == "}":
            depth -= 1
            i += 1
            continue
        if depth > 0:
            # inside a nested class or a function body — its members are its own, and a local
            # fun in a test body is nobody's. Only brace depth is tracked here.
            i += 1
            continue
        match = MEMBER_ITEM.match(masked, i)
        if match is None:
            i += 1
            continue
        if match.group(1) is not None:
            pending += 1
        else:
            raw = UNMASKED_FUN.match(original, i)
            name = (raw.group(1) if raw else match.group(2)).strip("`")
            if pending:
                items.append(name)
                pending = 0
        i = match.end()
    return items


def classes_in(path: pathlib.Path, module: str) -> list[TestClass]:
    """Every class in the file that declares test methods, NESTED classes included.

    A nested class gets its outer name as a qualifier (`Outer$Inner`), because that is how
    JUnit writes the row it produces: gateway/app's `inner class Heads` inside SetupCommandTest
    ran as SetupCommandTest$Heads, and looking it up by simple name reported a phantom hole."""
    source = path.read_text(encoding="utf-8")
    masked = mask(source)
    spans: list[tuple[str, int, int]] = []
    for match in CLASS_DECL.finditer(masked):
        span = body_range(masked, match.end())
        if span is not None:
            spans.append((match.group(1), span[0], span[1]))
    found: list[TestClass] = []
    for name, open_at, close_at in spans:
        outer = None
        for other_name, other_open, other_close in spans:
            if (other_name, other_open, other_close) == (name, open_at, close_at):
                continue
            if other_open < open_at and close_at < other_close:
                outer = other_name
        qualified = f"{outer}${name}" if outer else name
        methods = member_items(source[open_at + 1:close_at], masked[open_at + 1:close_at])
        if methods:
            found.append(TestClass(module, qualified, str(path), methods))
    return found


def scan_sources(root: pathlib.Path) -> list[TestClass]:
    classes: list[TestClass] = []
    for path in sorted(root.glob(TEST_SOURCES)):
        module = path.parts[path.parts.index("gateway") + 1]
        classes.extend(classes_in(path, module))
    return classes


def xml_rows(root: pathlib.Path) -> dict[str, dict[str, tuple[int, set[str]]]]:
    """module -> simple class name -> (count, testcase names)."""
    rows: dict[str, dict[str, tuple[int, set[str]]]] = {}
    for path in sorted(root.glob(TEST_RESULTS)):
        parts = pathlib.Path(path).parts
        module = parts[parts.index("gateway") + 1]
        root_el = ET.parse(path).getroot()
        name = (root_el.get("name") or pathlib.Path(path).stem).split(".")[-1]
        names = {tc.get("name", "").replace("()", "") for tc in root_el.iter("testcase")}
        rows.setdefault(module, {})[name] = (int(root_el.get("tests") or 0), names)
    return rows


def audit(root: pathlib.Path) -> list[str]:
    problems: list[str] = []
    classes = scan_sources(root)
    if not classes:
        return [
            "no test class with a @Test method was parsed from gateway/*/src/test/kotlin — "
            "refusing to pass vacuously, because a green over an empty denominator is the "
            "very signal this wall exists to distrust",
        ]
    rows = xml_rows(root)
    if not rows:
        return [
            "no JUnit XML found under gateway/*/build/test-results/test — a checker reading "
            "an empty results directory is the bug it is hunting; run the test leg first "
            "(this is why gate.sh runs it AFTER `gradle clean check`)",
        ]

    for test_class in classes:
        module_rows = rows.get(test_class.module, {})
        if not module_rows:
            reason = MODULE_DISPOSITIONS.get(test_class.module)
            if reason is None:
                problems.append(
                    f"{test_class.module}: {test_class.name} declares {test_class.count} test "
                    f"method(s) but the module produced NO XML at all — either its tests never "
                    f"ran or its test task is disabled; disposition the module with a reason",
                )
            elif not reason.strip():
                problems.append(
                    f"{test_class.module}: module disposition carries NO reason — an "
                    "undispositioned silence is exactly what this wall refuses",
                )
            continue
        row = module_rows.get(test_class.name)
        if row is None:
            problems.append(
                f"{test_class.module}: {test_class.name} declares {test_class.count} test "
                f"method(s) and produced NO XML row — JUnit never ran the class",
            )
            continue
        observed, observed_names = row
        missing = [m for m in test_class.methods if m not in observed_names]
        if observed < test_class.count:
            problems.append(
                f"NOT DISCOVERED: {test_class.module}:{test_class.name} declares "
                f"{test_class.count} test method(s), the XML reports {observed}"
                + (f"; never ran: {', '.join(missing)}" if missing else "")
                + f" ({test_class.path})",
            )
        elif observed > test_class.count:
            entry = DISPOSITIONS.get(test_class.name)
            if entry is None:
                problems.append(
                    f"HIGHER COUNT, no disposition: {test_class.module}:{test_class.name} "
                    f"declares {test_class.count} test method(s) but ran {observed} — if that "
                    "expansion is legitimate, add it to DISPOSITIONS with a written reason",
                )
            elif not entry[0].strip():
                problems.append(
                    f"{test_class.module}:{test_class.name} carries a disposition with NO "
                    "reason — a blank reason is an absence wearing a label",
                )
            elif observed != entry[1]:
                problems.append(
                    f"{test_class.module}:{test_class.name} ran {observed} cases, not the "
                    f"{entry[1]} its disposition was written for — the expansion moved, so "
                    "the disposition is stale and must be re-earned",
                )
    return problems


# ── selftest fixtures ────────────────────────────────────────────────────────────────

SOURCE_OK = '''package head

import org.junit.jupiter.api.Test

class SampleTest {
    @Test
    fun `a discovered test`() = runBlocking { Unit }

    @Test
    fun `a second discovered test`() {
        assertEquals(1, 1)
    }

    private fun helper() = "not a test"
}
'''

# The measured shape: the fourth method's body returns a value, so JUnit skips it. The
# source is IDENTICAL in both trees below — only the XML differs, which is the point.
SOURCE_UNDISCOVERED = '''package head

import org.junit.jupiter.api.Test

class SampleTest {
    @Test
    fun `a discovered test`() = runBlocking { Unit }

    @Test
    fun `an undiscovered test`() = runBlocking { held.await() }
}
'''

XML_OK = '''<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="2" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()" classname="head.SampleTest"/>
</testsuite>
'''

XML_SHORT = '''<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
</testsuite>
'''

XML_HIGHER = '''<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="4" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()[1]" classname="head.SampleTest"/>
  <testcase name="a second discovered test()[2]" classname="head.SampleTest"/>
</testsuite>
'''

SOURCE_EMPTY = '''package head

class NoTestsHere {
    private fun helper() = 1
}
'''


def write_tree(
    root: pathlib.Path,
    source: str,
    xml: str | None,
    quiet_module: str | None = None,
) -> None:
    if (root / "gateway").exists():
        shutil.rmtree(root / "gateway")
    src = root / "gateway" / "gateway" / "src" / "test" / "kotlin" / "SampleTest.kt"
    src.parent.mkdir(parents=True, exist_ok=True)
    src.write_text(source, encoding="utf-8")
    results = root / "gateway" / "gateway" / "build" / "test-results" / "test"
    # CLEARED FIRST: a case that asks for no XML must not inherit the previous case's XML,
    # which is this wall's own subject — an observation left over from an earlier run.
    if results.exists():
        shutil.rmtree(results)
    if xml is not None:
        results.mkdir(parents=True, exist_ok=True)
        (results / "TEST-head.SampleTest.xml").write_text(xml, encoding="utf-8")
    if quiet_module is not None:
        # A second module with tests and NO results: the per-module disposition shape, which
        # NOTE the fixture module name is deliberately NOT one the live table dispositioned — a
        # fixture that borrows a real module name inherits its real disposition and silently
        # stops testing anything, which is how this case first broke when `spikes` was added.
        # is only reachable while some OTHER module has XML (the global guard fires otherwise).
        other = root / "gateway" / quiet_module / "src" / "test" / "kotlin" / "QuietTest.kt"
        other.parent.mkdir(parents=True, exist_ok=True)
        other.write_text(SOURCE_OK.replace("SampleTest", "QuietTest"), encoding="utf-8")


def selftest() -> int:
    failures: list[str] = []
    global DISPOSITIONS, MODULE_DISPOSITIONS
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)

        write_tree(root, SOURCE_OK, XML_OK)
        problems = audit(root)
        if problems:
            failures.append(f"compliant tree must be GREEN, got: {problems}")

        # THE MEASURED BUG: same source shape as the compliant tree, one XML case short.
        write_tree(root, SOURCE_UNDISCOVERED, XML_SHORT)
        problems = audit(root)
        if not any("NOT DISCOVERED" in p for p in problems):
            failures.append(f"a short XML must be RED, got: {problems}")
        if not any("an undiscovered test" in p for p in problems):
            failures.append(f"the shortfall must name the method that never ran, got: {problems}")

        # A higher count with no reason is a finding, not a pass.
        write_tree(root, SOURCE_OK, XML_HIGHER)
        problems = audit(root)
        if not any("HIGHER COUNT" in p for p in problems):
            failures.append(f"an undispositioned higher count must be RED, got: {problems}")
        DISPOSITIONS = {"SampleTest": ("", 4)}
        problems = audit(root)
        if not any("NO reason" in p for p in problems):
            failures.append(f"a blank disposition reason must be RED, got: {problems}")
        DISPOSITIONS = {"SampleTest": ("one @ParameterizedTest expands to two cases", 3)}
        problems = audit(root)
        if not any("stale" in p for p in problems):
            failures.append(f"a disposition whose count moved must be RED, got: {problems}")
        DISPOSITIONS = {"SampleTest": ("one @ParameterizedTest expands to two extra cases", 4)}
        problems = audit(root)
        if problems:
            failures.append(f"a reasoned higher count must be GREEN, got: {problems}")
        DISPOSITIONS = {}

        # A module with tests and no results, while a sibling module HAS results: the
        # per-module disposition shape. Undispositioned is a finding; a reason clears it.
        write_tree(root, SOURCE_OK, XML_OK, quiet_module="silentmodule")
        problems = audit(root)
        if not any("silentmodule" in p and "NO XML at all" in p for p in problems):
            failures.append(f"a module with no XML must be RED by module name, got: {problems}")
        MODULE_DISPOSITIONS = {"silentmodule": ""}
        problems = audit(root)
        if not any("NO reason" in p for p in problems):
            failures.append(f"a blank module reason must be RED, got: {problems}")
        MODULE_DISPOSITIONS = {"silentmodule": "test task disabled by configuration unless -PrunX"}
        problems = audit(root)
        if problems:
            failures.append(f"a reasoned module disposition must be GREEN, got: {problems}")
        MODULE_DISPOSITIONS = {}

        # Vacuity guard: a parse that finds no test class must refuse, not pass.
        write_tree(root, SOURCE_EMPTY, XML_OK)
        problems = audit(root)
        if not any("refusing to pass vacuously" in p for p in problems):
            failures.append(f"a zero-class parse must be RED, got: {problems}")

        # Vacuity guard: no XML anywhere must refuse, even with a real denominator. A
        # separate tree is used so the empty-results case is the ONLY thing under test.
        with tempfile.TemporaryDirectory() as tmp2:
            root2 = pathlib.Path(tmp2)
            write_tree(root2, SOURCE_OK, None)
            problems = audit(root2)
            if not any("empty results directory" in p for p in problems):
                failures.append(f"an absent XML tree must be RED, got: {problems}")

    if failures:
        print("tests-are-discovered SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "tests-are-discovered SELFTEST OK — a class whose XML is short by one is red by "
        "name together with the method that never ran; an undispositioned or unreasoned "
        "higher count is red; a module with no XML is red until dispositioned with a "
        "reason; a parse yielding no test class and an empty results tree both refuse to "
        "pass; the compliant tree is green"
    )
    return 0


def report(root: pathlib.Path) -> None:
    classes = scan_sources(root)
    rows = xml_rows(root)
    print(f"tests-are-discovered: {len(classes)} test class(es) parsed from source")
    for test_class in sorted(classes, key=lambda c: (c.module, c.name)):
        row = rows.get(test_class.module, {}).get(test_class.name)
        observed = row[0] if row else None
        mark = "OK"
        if observed is None:
            mark = "NO-XML"
        elif observed < test_class.count:
            mark = "SHORT"
        elif observed > test_class.count:
            mark = (
                DISPOSITIONS[test_class.name][0]
                if test_class.name in DISPOSITIONS
                else "HIGHER-NO-REASON"
            )
        print(f"  {mark:20s} {test_class.module:26s} {test_class.name:44s} declared={test_class.count} xml={observed}")
    known = {(c.module, c.name) for c in classes}
    stale = [
        (module, name)
        for module, entries in rows.items()
        for name in entries
        if (module, name) not in known
    ]
    for module, name in sorted(stale):
        print(f"  STALE-XML            {module:26s} {name:44s} (no class in source)")


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    root = ROOT
    for arg in sys.argv[1:]:
        if arg not in {"check", "report", "--selftest"} and not arg.startswith("-"):
            root = pathlib.Path(arg)
            break
    if not root.exists():
        print("tests-are-discovered: tree missing", file=sys.stderr)
        return 1
    root = root.resolve()
    if "report" in sys.argv:
        report(root)
        return 0
    problems = audit(root)
    if problems:
        print("tests-are-discovered RED:")
        for problem in problems:
            print("  " + problem)
        return 1
    print(
        "tests-are-discovered GREEN: every test method declared in gateway/*/src/test/kotlin "
        "appears in the JUnit XML for its class"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
