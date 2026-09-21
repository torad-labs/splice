#!/usr/bin/env bun
/**
 * V4-68 — a test JUnit never DISCOVERED is a green suite with a hole in it.
 *
 * WHY THIS EXISTS. A @Test method whose body returns a non-Unit value is not discovered by
 * JUnit: no failure, no skip, no warning, no line in any report. The suite is green, the
 * XML is complete-looking, and the test has never run once in its life. Measured 2026-09-16:
 * gateway/gateway/src/test/kotlin/head/HeadServerCapacityTest.kt declares four @Test methods
 * and TEST-head.HeadServerCapacityTest.xml reports tests=3 — the fourth ends in held.await()
 * inside `= runBlocking { ... }`, so the method returns a String, and it had never executed.
 * Nothing we own could see it: every gate reads what ran, and nothing compared that against
 * what was DECLARED.
 *
 * THE INSTRUMENT TRAP, measured while confirming the above, and the reason this wall ANCHORS
 * ON THE XML AND NEVER ON THE SHAPE OF THE SOURCE. A naive scan for an expression-bodied
 * @Test flags all FOUR methods in that class, because three of them end in a Unit-valued
 * expression while the fourth does not — and kotlinx's TestResult is a typealias for Unit on
 * the JVM, so `= runTest { }` is discovered and fine. A checker reasoning from syntax is
 * wrong in BOTH directions: it accuses the innocent and can be fooled by the guilty. So the
 * source here supplies only the DENOMINATOR (what was declared) and the XML supplies the
 * OBSERVATION (what ran). The comparison is the whole check.
 *
 * DENOMINATOR, from the SOURCE. For every .kt file under gateway/*\/src/test/kotlin, each data
 * class is found by a string- and comment-aware scan, and its test methods are counted at
 * MEMBER depth only — a nested class's tests are its own, and a local function inside a test
 * body is nobody's. That is what makes the count comparable to a per-class XML row.
 *
 * OBSERVATION, from the JUnit XML. gateway/<module>/build/test-results/test/*.xml, one file
 * per class, each holding a testcase count and the testcase NAMES — so a shortfall can name
 * the method that never ran instead of only a number.
 *
 * SHAPES. XML count LOWER than the denominator fails BY NAME, naming the missing methods.
 * Two shapes may legitimately report a HIGHER count, and each needs a written reason in
 * DISPOSITIONS below — never a bare allowlist entry: a @ParameterizedTest expands one method
 * into N cases, and a class may INHERIT test methods from a base class. A class with no XML
 * at all also needs a written reason (a test task that is disabled by configuration is a
 * decision, not an accident — but it is a decision someone must WRITE DOWN).
 *
 * WHAT IT CANNOT SEE. A class that never compiles is not in any XML and not in this scan's
 * dispositions unless its module is dispositioned. A test source set outside gateway/* is not
 * scanned. And a test that runs but asserts nothing is discovered, counted, and useless —
 * this wall counts executions, it cannot judge them. Finally the reverse drift — an XML row
 * for a class that no longer exists in the source — is REPORTED by --report and not failed:
 * it is stale build output, not a hole in the suite.
 *
 * THE XML READER IS LOCAL, and that is a deliberate difference from the original rather than a
 * translation of it. Python had xml.etree.ElementTree; bun ships no XML parser at all
 * (measured on checks/catalog-metadata-sync.ts, which hit the same wall). This checker needs
 * exactly three things from a JUnit document — the root element's `name` and `tests`
 * attributes, and the `name` of every `<testcase>` descendant — so it reads those with a
 * strict attribute scanner and DIES LOUDLY on a tag it cannot parse, rather than returning an
 * empty row that would read as "JUnit ran nothing" for a file that is merely unfamiliar.
 *
 * Usage:
 *     bun checks/config/tests-are-discovered.ts [check] [<root>]
 *     bun checks/config/tests-are-discovered.ts --report [<root>]
 *     bun checks/config/tests-are-discovered.ts --selftest
 *
 * A BARE RUN IS `check` — the one gating mode, so there is no non-gating default to mis-invoke.
 * `--report` is the census and sits behind an explicit verb the gate never uses.
 */
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// parents[2]: this file lives at checks/config/, so the repo root is two levels up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

// restructure PR 3: :client is the first module to live outside gateway/, so the test plane is a
// LIST of module homes. A test root this wall stops globbing has its classes leave the DENOMINATOR
// entirely — the wall then proves that every test it can still see ran, which is the exact shape
// of the failure it was written against.
// Every §2.2 module home, the ones that exist and the ones the next module commits create (a glob
// over an absent directory matches nothing, so the denominator can only grow as modules land).
const MODULE_HOMES = [
  "gateway/*", "client", "core", "upstream", "dialects/*", "providers/*", "daemon/*", "app", "quality/*",
];
const TEST_SOURCES = MODULE_HOMES.map((home) => `${home}/src/test/kotlin/**/*.kt`);
const TEST_RESULTS = MODULE_HOMES.map((home) => `${home}/build/test-results/test/*.xml`);

// Classes whose XML count is legitimately HIGHER than the source denominator. Every entry
// needs a written reason — the reason is the disposition, and an empty one fails the wall.
// Never add a bare class name here: if the reason is not known, the finding is real.
// name -> (reason, expected observed count). The COUNT is part of the disposition on purpose:
// a reason typed once and never revisited is a waiver that outlives its proof, so if the
// observed count moves away from the expected one the wall reds and it must be re-earned.
let DISPOSITIONS: Record<string, [string, number]> = {
  // Every entry below was verified against the class's own annotations before it was written,
  // and the count beside it is the observed XML count the disposition was earned for. Each is
  // the same shape: @ParameterizedTest expands one method into N cases, which a per-method
  // source count cannot see. (Inherited test methods would be the other shape; measured across
  // all six, none of them is explained by inheritance — CodexCodeModeActiveInterruptionTest and
  // CodexCodeModeInfrastructureTest do extend CodeModeBridgeTestSupport, but that base declares
  // no tests, and their declared count matches their own annotations exactly.)
  ResponsesWsRunnerTest: ["3 @ParameterizedTest methods expand to 9 cases (12 @Test + 9 = 21)", 21],
  CodexCodeModeReanchorTest: ["1 @ParameterizedTest expands to 2 cases (1 @Test + 2 = 3)", 3],
  CodexAuthAbsenceTest: ["1 @ParameterizedTest expands to 4 cases (2 @Test + 4 = 6)", 6],
  CodexCodeModeActiveInterruptionTest: ["1 @ParameterizedTest expands to 4 cases; no plain @Test", 4],
  CodexCodeModeInfrastructureTest: ["1 @ParameterizedTest expands to 2 cases; no plain @Test", 2],
  SseReaderTest: ["1 @ParameterizedTest expands to 6 cases (11 @Test + 6 = 17)", 17],
};

// Modules whose test task is disabled BY CONFIGURATION, so no XML can exist. The reason is
// the disposition; cite where the decision lives.
// Empty since the spikes module was deleted (restructure PR 1): every declared module runs its tests.
let MODULE_DISPOSITIONS: Record<string, string> = {};

const CLASS_DECL = /\bclass\s+(\w+)/g;
const MEMBER_ITEM_SOURCE = "@(Test|ParameterizedTest)\\b|\\bfun\\s+(`[^`]+`|\\w+)\\s*\\(";
const MEMBER_ITEM = new RegExp(MEMBER_ITEM_SOURCE, "y");
// The same shape read from the ORIGINAL text, where a backtick name is still spelled out.
const UNMASKED_FUN = /\bfun\s+(`[^`\n]+`|\w+)\s*\(/y;

// Where a declaration ENDS without a body: a blank line, or a line at COLUMN 0 that starts
// another declaration. Column 0 is load-bearing — an indented `val`/`var` is a constructor
// parameter of the very class being scanned, and treating it as a boundary would hide that
// class and its tests from the denominator entirely, which is a silent miss rather than a
// loud one. (A nested body-less declaration followed by an INDENTED declaration is the
// residual hole; no such shape exists in the tree today, measured: the fix removed exactly
// the 8 false spikes classes and no real one.)
const DECL_END_SOURCE = "\\n[ \\t]*\\n|\\n(?:@|class|object|interface|fun|enum|val|var)\\b";

// JUnit's own discovery rule, which is what this wall is really asserting: a @Test method
// must be public and return void. Kotlin enforces neither, so the shape is a trap.
// Carried from the original, where it is DEFINED AND NOT USED; the disposition table above is
// what earns the inheritance case. Kept because a reader following that sentence will look here.
const INHERIT = /\bclass\s+\w+[^{]*?:\s*([A-Za-z_][\w.]*)\s*(?:\(|\{|$)/;

class TestClass {
  readonly module: string;
  readonly name: string;
  readonly path: string;
  readonly methods: string[];

  constructor(module: string, name: string, path: string, methods: string[]) {
    this.module = module;
    this.name = name;
    this.path = path;
    this.methods = methods;
  }

  get count(): number {
    return this.methods.length;
  }
}

/** Python's repr() of a list of strings. */
const pyReprList = (items: string[]): string =>
  `[${items.map((s) => `'${s.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`).join(", ")}]`;

/** Blank every comment and string BODY, preserving length and newlines.
 *
 *  One scanner, not two: comments and strings both hide `class`/`fun`/braces from the
 *  structure scan, and a literal left intact is a false class — the first version of this
 *  file reported classes named `Heads`, `per` and `name`, every one of them a phrase inside
 *  a triple-quoted JSON body, because Kotlin's TRIPLE-quoted strings were not recognised
 *  and `\"\"\"` was read as an empty string followed by another string. Length-preserving so
 *  offsets into the masked text still address the original. */
function mask(source: string): string {
  const out: string[] = [];
  let i = 0;
  const n = source.length;
  const plain = (ch: string): string => (ch === "\n" ? "\n" : " ");
  const xs = (ch: string): string => (ch === "\n" ? "\n" : "x");
  while (i < n) {
    const ch = source[i];
    if (ch === "`") {
      // Kotlin's backtick identifier — how the test names are spelled here. Its body is
      // masked, NOT scanned for quotes: 125 methods in this tree carry an apostrophe
      // inside a backtick name ("the operator's servers..."), and reading that `'` as a
      // character literal swallowed the rest of the line, braces and all, which is what
      // made class bodies close early and counts read as 1.
      const end = source.indexOf("`", i + 1);
      const stop = end < 0 ? n : end + 1;
      for (const c of source.slice(i, stop)) out.push(xs(c));
      i = stop;
      continue;
    }
    if (ch === "/" && i + 1 < n && source[i + 1] === "/") {
      const nl = source.indexOf("\n", i);
      if (nl < 0) break;
      for (const c of source.slice(i, nl)) out.push(plain(c));
      i = nl;
      continue;
    }
    if (ch === "/" && i + 1 < n && source[i + 1] === "*") {
      const end = source.indexOf("*/", i + 2);
      const stop = end < 0 ? n : end + 2;
      for (const c of source.slice(i, stop)) out.push(plain(c));
      i = stop;
      continue;
    }
    if (source.startsWith('"""', i)) {
      const end = source.indexOf('"""', i + 3);
      const stop = end < 0 ? n : end + 3;
      for (const c of source.slice(i, stop)) out.push(xs(c));
      i = stop;
      continue;
    }
    if (ch === '"' || ch === "'") {
      let j = i + 1;
      while (j < n) {
        if (source[j] === "\\") {
          j += 2;
          continue;
        }
        if (source[j] === ch || source[j] === "\n") break;
        j += 1;
      }
      const stop = Math.min(j + 1, n);
      for (const c of source.slice(i, stop)) out.push(xs(c));
      i = stop;
      continue;
    }
    out.push(ch);
    i += 1;
  }
  return out.join("");
}

/** (open_brace, close_brace) of the declaration whose text starts at start, or null.
 *
 *  The brace must appear before the declaration ENDS, which is what the boundary scan is
 *  for: a declaration with NO body — `data class Topology(val daemon: DaemonConfig, ...)`
 *  in the spikes — is followed by the next class's `{`, and taking that one made every
 *  constructor-only data class look like a test class holding its neighbour's tests. */
function bodyRange(masked: string, start: number): [number, number] | null {
  let limit = masked.length;
  const boundaryRe = new RegExp(DECL_END_SOURCE, "g");
  boundaryRe.lastIndex = start;
  const boundary = boundaryRe.exec(masked);
  if (boundary !== null) limit = boundary.index;
  const openAt = masked.indexOf("{", start);
  if (openAt < 0 || openAt > limit) return null;
  let depth = 0;
  let i = openAt;
  while (i < masked.length) {
    if (masked[i] === "{") depth += 1;
    else if (masked[i] === "}") {
      depth -= 1;
      if (depth === 0) return [openAt, i];
    }
    i += 1;
  }
  return null;
}

/** The names of the test methods declared at MEMBER depth of a class body.
 *
 *  A method counts when a @Test/@ParameterizedTest annotation precedes it at member depth:
 *  the fun that follows a pending annotation is the annotated one, so helper functions and
 *  local functions are not mistaken for tests. Nested classes are skipped wholesale — their
 *  members belong to them, and the XML reports them under their own qualified name.
 *
 *  Names are read from the ORIGINAL text: a backtick name is masked to x's in [masked], and
 *  the XML quotes the name, so the masked copy cannot supply it. */
function memberItems(original: string, masked: string): string[] {
  const items: string[] = [];
  let depth = 0;
  let i = 0;
  let pending = 0;
  while (i < masked.length) {
    const ch = masked[i];
    if (ch === "{") {
      depth += 1;
      i += 1;
      continue;
    }
    if (ch === "}") {
      depth -= 1;
      i += 1;
      continue;
    }
    if (depth > 0) {
      // inside a nested class or a function body — its members are its own, and a local
      // fun in a test body is nobody's. Only brace depth is tracked here.
      i += 1;
      continue;
    }
    MEMBER_ITEM.lastIndex = i;
    UNMASKED_FUN.lastIndex = i;
    const match = MEMBER_ITEM.exec(masked);
    if (match === null) {
      i += 1;
      continue;
    }
    if (match[1] !== undefined) {
      pending += 1;
    } else {
      const raw = UNMASKED_FUN.exec(original);
      const name = (raw !== null ? raw[1] : (match[2] as string)).replace(/^`|`$/g, "");
      if (pending > 0) {
        items.push(name);
        pending = 0;
      }
    }
    i = match.index + match[0].length;
  }
  return items;
}

/** Every class in the file that declares test methods, NESTED classes included.
 *
 *  A nested class gets its outer name as a qualifier (`Outer$Inner`), because that is how
 *  JUnit writes the row it produces: gateway/app's `inner class Heads` inside SetupCommandTest
 *  ran as SetupCommandTest$Heads, and looking it up by simple name reported a phantom hole. */
function classesIn(path: string, module: string): TestClass[] {
  const source = readFileSync(path, "utf8");
  const masked = mask(source);
  const spans: [string, number, number][] = [];
  for (const match of masked.matchAll(CLASS_DECL)) {
    const span = bodyRange(masked, (match.index as number) + match[0].length);
    if (span !== null) spans.push([match[1], span[0], span[1]]);
  }
  const found: TestClass[] = [];
  for (const [name, openAt, closeAt] of spans) {
    let outer: string | null = null;
    for (const [otherName, otherOpen, otherClose] of spans) {
      if (otherName === name && otherOpen === openAt && otherClose === closeAt) continue;
      if (otherOpen < openAt && closeAt < otherClose) outer = otherName;
    }
    const qualified = outer !== null ? `${outer}$${name}` : name;
    const methods = memberItems(source.slice(openAt + 1, closeAt), masked.slice(openAt + 1, closeAt));
    if (methods.length > 0) found.push(new TestClass(module, qualified, path, methods));
  }
  return found;
}

/** The module DIRECTORY of a path — everything before its `/src/` or `/build/` segment (`gateway/app`,
 *  `client`, `daemon/head`), so two modules under one parent never share a key. A path with neither
 *  segment is outside every module home and fails by name rather than defaulting. */
function moduleOf(path: string): string {
  const cuts = ["/src/", "/build/"].map((segment) => path.indexOf(segment)).filter((at) => at >= 0);
  if (cuts.length === 0) throw new Error(`${path} has no /src/ or /build/ segment — it is under no module home`);
  return path.slice(0, Math.min(...cuts));
}

function scanSources(root: string): TestClass[] {
  const classes: TestClass[] = [];
  const files = TEST_SOURCES.flatMap((p) => [...new Bun.Glob(p).scanSync({ cwd: root, followSymlinks: true })]).sort();
  for (const rel of files) classes.push(...classesIn(join(root, rel), moduleOf(rel)));
  return classes;
}

interface XmlRow {
  count: number;
  names: Set<string>;
}

const XML_TAG = /<([A-Za-z_][\w.:-]*)((?:\s+[\w.:-]+\s*=\s*"[^"]*")*)\s*\/?>/g;
const XML_ATTR = /([\w.:-]+)\s*=\s*"([^"]*)"/g;

/** The root element's attributes, and the `name` of every <testcase> descendant.
 *
 *  A strict scanner rather than a parser, and it DIES on a document it cannot read. Python had
 *  xml.etree and bun ships none; an empty row returned for an unfamiliar file would read as
 *  "JUnit ran nothing", which is this wall's own subject read backwards. */
function parseJUnitXml(text: string): { rootAttrs: Map<string, string>; testcaseNames: string[] } {
  const rootAttrs = new Map<string, string>();
  const testcaseNames: string[] = [];
  let sawRoot = false;
  for (const tag of text.matchAll(XML_TAG)) {
    const attrs = new Map<string, string>();
    for (const a of (tag[2] ?? "").matchAll(XML_ATTR)) attrs.set(a[1], a[2]);
    if (!sawRoot) {
      sawRoot = true;
      for (const [k, v] of attrs) rootAttrs.set(k, v);
      continue;
    }
    if (tag[1] === "testcase") testcaseNames.push((attrs.get("name") ?? "").replace(/\(\)/g, ""));
  }
  if (!sawRoot) throw new Error("no element found in the JUnit document");
  return { rootAttrs, testcaseNames };
}

/** module -> simple class name -> (count, testcase names). */
function xmlRows(root: string): Map<string, Map<string, XmlRow>> {
  const rows = new Map<string, Map<string, XmlRow>>();
  const files = TEST_RESULTS.flatMap((p) => [...new Bun.Glob(p).scanSync({ cwd: root, followSymlinks: true })]).sort();
  for (const rel of files) {
    const module = moduleOf(rel);
    const { rootAttrs, testcaseNames } = parseJUnitXml(readFileSync(join(root, rel), "utf8"));
    const stem = (rel.split("/").pop() as string).replace(/\.xml$/, "");
    const name = (rootAttrs.get("name") || stem).split(".").pop() as string;
    const entry = new Map<string, XmlRow>();
    entry.set(name, { count: parseInt(rootAttrs.get("tests") || "0", 10), names: new Set(testcaseNames) });
    const existing = rows.get(module);
    if (existing === undefined) rows.set(module, entry);
    else for (const [k, v] of entry) existing.set(k, v);
  }
  return rows;
}

function audit(root: string): string[] {
  const problems: string[] = [];
  const classes = scanSources(root);
  if (classes.length === 0) {
    return [
      `no test class with a @Test method was parsed from ${TEST_SOURCES.join(", ")} — ` +
        "refusing to pass vacuously, because a green over an empty denominator is the " +
        "very signal this wall exists to distrust",
    ];
  }
  const rows = xmlRows(root);
  if (rows.size === 0) {
    return [
      `no JUnit XML found under ${TEST_RESULTS.join(", ")} — a checker reading ` +
        "an empty results directory is the bug it is hunting; run the test leg first " +
        "(this is why gate.sh runs it AFTER `gradle clean check`)",
    ];
  }

  for (const testClass of classes) {
    const moduleRows = rows.get(testClass.module) ?? new Map<string, XmlRow>();
    if (moduleRows.size === 0) {
      const reason = MODULE_DISPOSITIONS[testClass.module];
      if (reason === undefined) {
        problems.push(
          `${testClass.module}: ${testClass.name} declares ${testClass.count} test ` +
            `method(s) but the module produced NO XML at all — either its tests never ` +
            `ran or its test task is disabled; disposition the module with a reason`,
        );
      } else if (!reason.trim()) {
        problems.push(
          `${testClass.module}: module disposition carries NO reason — an ` +
            "undispositioned silence is exactly what this wall refuses",
        );
      }
      continue;
    }
    const row = moduleRows.get(testClass.name);
    if (row === undefined) {
      problems.push(
        `${testClass.module}: ${testClass.name} declares ${testClass.count} test ` +
          `method(s) and produced NO XML row — JUnit never ran the class`,
      );
      continue;
    }
    const observed = row.count;
    const missing = testClass.methods.filter((m) => !row.names.has(m));
    if (observed < testClass.count) {
      problems.push(
        `NOT DISCOVERED: ${testClass.module}:${testClass.name} declares ` +
          `${testClass.count} test method(s), the XML reports ${observed}` +
          (missing.length > 0 ? `; never ran: ${missing.join(", ")}` : "") +
          ` (${testClass.path})`,
      );
    } else if (observed > testClass.count) {
      const entry = DISPOSITIONS[testClass.name];
      if (entry === undefined) {
        problems.push(
          `HIGHER COUNT, no disposition: ${testClass.module}:${testClass.name} ` +
            `declares ${testClass.count} test method(s) but ran ${observed} — if that ` +
            "expansion is legitimate, add it to DISPOSITIONS with a written reason",
        );
      } else if (!entry[0].trim()) {
        problems.push(
          `${testClass.module}:${testClass.name} carries a disposition with NO ` +
            "reason — a blank reason is an absence wearing a label",
        );
      } else if (observed !== entry[1]) {
        problems.push(
          `${testClass.module}:${testClass.name} ran ${observed} cases, not the ` +
            `${entry[1]} its disposition was written for — the expansion moved, so ` +
            "the disposition is stale and must be re-earned",
        );
      }
    }
  }
  return problems;
}

// ── selftest fixtures ────────────────────────────────────────────────────────────────

const SOURCE_OK = `package head

import org.junit.jupiter.api.Test

class SampleTest {
    @Test
    fun \`a discovered test\`() = runBlocking { Unit }

    @Test
    fun \`a second discovered test\`() {
        assertEquals(1, 1)
    }

    private fun helper() = "not a test"
}
`;

// The measured shape: the fourth method's body returns a value, so JUnit skips it. The
// source is IDENTICAL in both trees below — only the XML differs, which is the point.
const SOURCE_UNDISCOVERED = `package head

import org.junit.jupiter.api.Test

class SampleTest {
    @Test
    fun \`a discovered test\`() = runBlocking { Unit }

    @Test
    fun \`an undiscovered test\`() = runBlocking { held.await() }
}
`;

const XML_OK = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="2" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()" classname="head.SampleTest"/>
</testsuite>
`;

const XML_SHORT = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
</testsuite>
`;

const XML_HIGHER = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="SampleTest" tests="4" skipped="0" failures="0" errors="0">
  <testcase name="a discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()" classname="head.SampleTest"/>
  <testcase name="a second discovered test()[1]" classname="head.SampleTest"/>
  <testcase name="a second discovered test()[2]" classname="head.SampleTest"/>
</testsuite>
`;

const SOURCE_EMPTY = `package head

class NoTestsHere {
    private fun helper() = 1
}
`;

function writeTree(root: string, source: string, xml: string | null, quietModule: string | null = null): void {
  const gateway = join(root, "gateway");
  if (existsSync(gateway)) rmSync(gateway, { recursive: true, force: true });
  const src = join(root, "gateway/gateway/src/test/kotlin/SampleTest.kt");
  mkdirSync(dirname(src), { recursive: true });
  writeFileSync(src, source, "utf8");
  const results = join(root, "gateway/gateway/build/test-results/test");
  // CLEARED FIRST: a case that asks for no XML must not inherit the previous case's XML,
  // which is this wall's own subject — an observation left over from an earlier run.
  if (existsSync(results)) rmSync(results, { recursive: true, force: true });
  if (xml !== null) {
    mkdirSync(results, { recursive: true });
    writeFileSync(join(results, "TEST-head.SampleTest.xml"), xml, "utf8");
  }
  if (quietModule !== null) {
    // A second module with tests and NO results: the per-module disposition shape, which
    // NOTE the fixture module name is deliberately NOT one the live table dispositioned — a
    // fixture that borrows a real module name inherits its real disposition and silently
    // stops testing anything, which is how this case first broke when `spikes` was added.
    // is only reachable while some OTHER module has XML (the global guard fires otherwise).
    const other = join(root, "gateway", quietModule, "src/test/kotlin/QuietTest.kt");
    mkdirSync(dirname(other), { recursive: true });
    writeFileSync(other, SOURCE_OK.replace(/SampleTest/g, "QuietTest"), "utf8");
  }
}

function mkdtemp(label: string): string {
  const dir = join(tmpdir(), `tests-are-discovered-${label}-${process.pid}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(dir, { recursive: true });
  return dir;
}

function selftest(): number {
  const failures: string[] = [];
  const root = mkdtemp("main");
  try {
    writeTree(root, SOURCE_OK, XML_OK);
    let problems = audit(root);
    if (problems.length > 0) failures.push(`compliant tree must be GREEN, got: ${pyReprList(problems)}`);

    // THE MEASURED BUG: same source shape as the compliant tree, one XML case short.
    writeTree(root, SOURCE_UNDISCOVERED, XML_SHORT);
    problems = audit(root);
    if (!problems.some((p) => p.includes("NOT DISCOVERED"))) {
      failures.push(`a short XML must be RED, got: ${pyReprList(problems)}`);
    }
    if (!problems.some((p) => p.includes("an undiscovered test"))) {
      failures.push(`the shortfall must name the method that never ran, got: ${pyReprList(problems)}`);
    }

    // A higher count with no reason is a finding, not a pass.
    writeTree(root, SOURCE_OK, XML_HIGHER);
    problems = audit(root);
    if (!problems.some((p) => p.includes("HIGHER COUNT"))) {
      failures.push(`an undispositioned higher count must be RED, got: ${pyReprList(problems)}`);
    }
    DISPOSITIONS = { SampleTest: ["", 4] };
    problems = audit(root);
    if (!problems.some((p) => p.includes("NO reason"))) {
      failures.push(`a blank disposition reason must be RED, got: ${pyReprList(problems)}`);
    }
    DISPOSITIONS = { SampleTest: ["one @ParameterizedTest expands to two cases", 3] };
    problems = audit(root);
    if (!problems.some((p) => p.includes("stale"))) {
      failures.push(`a disposition whose count moved must be RED, got: ${pyReprList(problems)}`);
    }
    DISPOSITIONS = { SampleTest: ["one @ParameterizedTest expands to two extra cases", 4] };
    problems = audit(root);
    if (problems.length > 0) {
      failures.push(`a reasoned higher count must be GREEN, got: ${pyReprList(problems)}`);
    }
    DISPOSITIONS = {};

    // A module with tests and no results, while a sibling module HAS results: the
    // per-module disposition shape. Undispositioned is a finding; a reason clears it.
    writeTree(root, SOURCE_OK, XML_OK, "silentmodule");
    problems = audit(root);
    if (!problems.some((p) => p.includes("silentmodule") && p.includes("NO XML at all"))) {
      failures.push(`a module with no XML must be RED by module name, got: ${pyReprList(problems)}`);
    }
    MODULE_DISPOSITIONS = { "gateway/silentmodule": "" };
    problems = audit(root);
    if (!problems.some((p) => p.includes("NO reason"))) {
      failures.push(`a blank module reason must be RED, got: ${pyReprList(problems)}`);
    }
    MODULE_DISPOSITIONS = { "gateway/silentmodule": "test task disabled by configuration unless -PrunX" };
    problems = audit(root);
    if (problems.length > 0) {
      failures.push(`a reasoned module disposition must be GREEN, got: ${pyReprList(problems)}`);
    }
    MODULE_DISPOSITIONS = {};

    // Vacuity guard: a parse that finds no test class must refuse, not pass.
    writeTree(root, SOURCE_EMPTY, XML_OK);
    problems = audit(root);
    if (!problems.some((p) => p.includes("refusing to pass vacuously"))) {
      failures.push(`a zero-class parse must be RED, got: ${pyReprList(problems)}`);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }

  // Vacuity guard: no XML anywhere must refuse, even with a real denominator. A
  // separate tree is used so the empty-results case is the ONLY thing under test.
  const root2 = mkdtemp("no-xml");
  try {
    writeTree(root2, SOURCE_OK, null);
    const problems = audit(root2);
    if (!problems.some((p) => p.includes("empty results directory"))) {
      failures.push(`an absent XML tree must be RED, got: ${pyReprList(problems)}`);
    }
  } finally {
    rmSync(root2, { recursive: true, force: true });
  }

  if (failures.length > 0) {
    process.stdout.write("tests-are-discovered SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "tests-are-discovered SELFTEST OK — a class whose XML is short by one is red by " +
      "name together with the method that never ran; an undispositioned or unreasoned " +
      "higher count is red; a module with no XML is red until dispositioned with a " +
      "reason; a parse yielding no test class and an empty results tree both refuse to " +
      "pass; the compliant tree is green\n",
  );
  return 0;
}

/** Python prints None for a missing count; JS would print null. */
const pyCount = (value: number | null): string => (value === null ? "None" : String(value));

function report(root: string): void {
  const classes = scanSources(root);
  const rows = xmlRows(root);
  process.stdout.write(`tests-are-discovered: ${classes.length} test class(es) parsed from source\n`);
  const ordered = [...classes].sort((a, b) =>
    a.module === b.module ? (a.name < b.name ? -1 : a.name > b.name ? 1 : 0) : a.module < b.module ? -1 : 1,
  );
  for (const testClass of ordered) {
    const row = (rows.get(testClass.module) ?? new Map<string, XmlRow>()).get(testClass.name);
    const observed = row === undefined ? null : row.count;
    let mark = "OK";
    if (observed === null) mark = "NO-XML";
    else if (observed < testClass.count) mark = "SHORT";
    else if (observed > testClass.count) {
      mark = testClass.name in DISPOSITIONS ? DISPOSITIONS[testClass.name][0] : "HIGHER-NO-REASON";
    }
    process.stdout.write(
      `  ${mark.padEnd(20)} ${testClass.module.padEnd(26)} ${testClass.name.padEnd(44)} ` +
        `declared=${testClass.count} xml=${pyCount(observed)}\n`,
    );
  }
  const known = new Set(classes.map((c) => `${c.module} ${c.name}`));
  const stale: [string, string][] = [];
  for (const [module, entries] of rows) {
    for (const name of entries.keys()) {
      if (!known.has(`${module} ${name}`)) stale.push([module, name]);
    }
  }
  stale.sort((a, b) => (a[0] === b[0] ? (a[1] < b[1] ? -1 : 1) : a[0] < b[0] ? -1 : 1));
  for (const [module, name] of stale) {
    process.stdout.write(`  STALE-XML            ${module.padEnd(26)} ${name.padEnd(44)} (no class in source)\n`);
  }
}

const USAGE = `usage: tests-are-discovered [check] [<root>] [--report] [--selftest]
  check      gate leg: every declared test method must appear in its JUnit XML (a bare run does this)
  --report   the census, including stale XML rows for classes that no longer exist
  --selftest red-green proof, out of tree
`;

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  let root = ROOT;
  for (const arg of argv) {
    if (arg === "check" || arg === "report" || arg === "--selftest") continue;
    if (arg.startsWith("-")) {
      process.stderr.write(`tests-are-discovered: unrecognised argument '${arg}'\n${USAGE}`);
      return 2;
    }
    root = arg;
    break;
  }
  if (!existsSync(root)) {
    process.stderr.write("tests-are-discovered: tree missing\n");
    return 1;
  }
  const resolved = realpathSync(root);
  if (argv.includes("report")) {
    report(resolved);
    return 0;
  }
  const problems = audit(resolved);
  if (problems.length > 0) {
    process.stdout.write("tests-are-discovered RED:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    return 1;
  }
  process.stdout.write(
    `tests-are-discovered GREEN: every test method declared in ${TEST_SOURCES.join(", ")} ` +
      "appears in the JUnit XML for its class\n",
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
