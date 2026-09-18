#!/usr/bin/env bun
/**
 * V4-92 — a module's PUBLIC surface must have a consumer in another module, or be internal.
 *
 * WHY THIS EXISTS. Every library module in this tree runs under `explicitApi()`
 * (gateway/build-logic/src/main/kotlin/splice.module-law.gradle.kts), so every declaration
 * spells its visibility out loud — and `public` is what an author types when they are not
 * thinking about the module boundary, because it is what the compiler asks for and the
 * error message that demands it says nothing about who the reader is. The result measured
 * on 2026-09-17: 31 of the 41 public TYPES in :gateway are named by no other module's main
 * or testFixtures sources, and six public MEMBERS across the tree have only test callers.
 * A public declaration nobody outside the module consumes is not an API, it is a leak of
 * the module's internals into its ABI — the thing every architecture law above it is trying
 * to make inexpressible. `internal` is the same code with the boundary stated.
 *
 * Nothing was measuring it. detekt has no such rule, the module law governs GRADLE edges and
 * not visibility, and Konsist's laws read packages rather than the consumer set. So the
 * surface grew for the whole campaign under a green gate, which is the same shape as the
 * 2026-07-16 style pack that sat unrouted for a month (see checks/rule-routing.sh).
 *
 * THE DENOMINATOR COMES FROM THE SOURCE, never a hand list (§24). Two files are parsed:
 *   · gateway/settings.gradle.kts — every include()d module path. That is the universe.
 *   · gateway/build-logic/.../splice.module-law.gradle.kts — `nonLibrary`, the set the build
 *     itself exempts from explicitApi (:app, :spikes, :arch-tests, :fir-checks). A module in
 *     that set has no explicit `public` to read and is not a library, so it is GRADED as a
 *     consumer and never as a producer. This is the row's "each non-:app module" read off
 *     the build rather than retyped: a module added to settings.gradle.kts tomorrow is in
 *     scope with no edit here, and a module moved into nonLibrary leaves scope the same way.
 * Two guards refuse a vacuous pass: zero library modules, or zero public declarations across
 * all of them, is a FAILURE and not a green — a checker that silently loses its denominator
 * is a checker that passes.
 *
 * JUSTIFIED means a CONSUMER OUTSIDE THE MODULE, and the consumer set is main + testFixtures:
 *   · main sources of any other module, including the nonLibrary ones — :app is the
 *     composition root and consuming a library's API is its whole job;
 *   · testFixtures sources of any other module — a fixture is shipped, cross-module code, so
 *     a declaration a sibling's fixture needs is genuinely public.
 * A declaration is matched by its fully-qualified name (`import splice.x.Y` and a bare
 * `splice.x.Y` FQN use are the same token), or by a star import of its package.
 *
 * src/test IS DELIBERATELY NOT A CONSUMER, and that is the point rather than an oversight.
 * A same-module test needs no visibility at all (`internal` is visible to the module's own
 * test source set), and a SIBLING module's test reaching a type is the shape audit row D 12
 * found six times over — Turn.trimToLast, Mirror.extractThinking,
 * CompactClassifier.markerPresent, UpstreamFailureClassifier.mapOutStatus,
 * GrokOAuth.grokRefreshForm, GrokAuthProvider.ineffectiveRefreshCount, each public solely so
 * a test could reach it. Counting test callers would make this wall green over exactly the
 * population it exists to name.
 *
 * THE RATCHET. 156 declarations are unjustified today, so `internal or bust` cannot be the
 * gate leg without finishing the fix row first, and 156 exemptions would be the laundering a
 * baseline exists to prevent. What IS enforceable today is the DIRECTION, in the idiom
 * checks/concentration.ts already uses for the HIGH band:
 *   · GROWTH fails. A public declaration that no other module consumes, and that the baseline
 *     does not record, is red BY NAME on the commit that adds it.
 *   · A STALE ENTRY fails. A baseline line whose declaration is gone, has become internal, or
 *     has gained a real consumer is a hard error with the remedy printed — a baseline held
 *     above the measured surface is unearned room for the next regression to hide in, which is
 *     the defect checks/concentration.ts records twice (the 6.14 UpstreamClient ceiling).
 *   · IT CANNOT BE SATISFIED BY WEAKENING. Shrinking the baseline is the remedy; growing it is
 *     a dated diff that reads as exactly what it is.
 * The fix row burns the baseline down; `--write-baseline` reprints it from measurement, and the
 * resulting diff is the record of what moved.
 *
 * NOT CAUGHT, stated here rather than discovered later.
 *   · MEMBERS. This wall reads TOP-LEVEL declarations. The six test-only symbols above are
 *     public MEMBERS of public types, referenced as `x.trimToLast(...)` with no FQN and no
 *     import, so no name-based rule can attribute them to their owner without a resolved type
 *     graph. They are recorded by name in the V4-92 ledger note as the fix row's inventory;
 *     catching them mechanically needs a compiler plugin (:fir-checks is where that would go),
 *     not a regex.
 *   · A DECLARATION CONSUMED ONLY BY A STRING. Reflection, a serializer name, a DI key — the
 *     FQN never appears, so the declaration reads as unjustified. The remedy is the same as for
 *     any false red: the fix row makes it internal and the compiler says so immediately.
 *   · SAME-PACKAGE CROSS-MODULE USE. Kotlin needs no import when two modules share a package,
 *     so such a use would be invisible here. Measured 2026-09-17: no package in this tree is
 *     declared by two modules, so the hole is empty today; the FQN and star-import matchers are
 *     what would have to grow if that ever changes, and this paragraph is the marker.
 *
 * SELFTEST. `--selftest` builds temp trees and proves BOTH directions plus the boring cases:
 * GREEN on a consumed public declaration, on an internal one, and on a star-imported one;
 * RED BY NAME on a synthetic unjustified public type, on one whose only caller is a sibling's
 * src/test, on a baseline entry that no longer offends, on a baseline entry whose declaration
 * is gone, on a tree with no library modules, and on a tree whose parse yields no public
 * declarations at all.
 *
 * THERE IS NO BARE MODE, AND FIXING THAT WAS PART OF THIS PORT. The bare invocation used to run
 * the CENSUS, whose exit code is `1 if problems else 0` where `problems` means an untrustworthy
 * measurement — so it exited 0 whenever the measurement was trustworthy, whatever the ratchet
 * would have said about growth. A mis-invocation therefore answered 0 while the gate was red,
 * which is a gate that did not run wearing a pass. The census is still here and is still useful;
 * it lives behind `--report`, explicitly, and the bare invocation is misuse: help on stdout,
 * exit 2, and nothing measured.
 *
 * Usage:
 *     bun checks/public-surface.ts --ratchet [ROOT]         # gate leg: fail on growth or a stale entry
 *     bun checks/public-surface.ts --report [ROOT]          # the census: counts and loci by module
 *     bun checks/public-surface.ts --json [ROOT]            # the census as JSON
 *     bun checks/public-surface.ts --write-baseline [ROOT]  # reprint the baseline from measurement
 *     bun checks/public-surface.ts --selftest               # red-green proof, out of tree
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");

// The two build files that ARE the denominator. Fixed paths on purpose: a checker that
// silently loses its source is a checker that passes.
const GRADLE_ROOT_REL = "gateway";
const SETTINGS_REL = "gateway/settings.gradle.kts";
const MODULE_LAW_REL = "gateway/build-logic/src/main/kotlin/splice.module-law.gradle.kts";
const BASELINE_REL = "checks/config/public-surface-baseline.json";

const MODULE_PATH = /"(:[A-Za-z0-9._-]+)"/g;
const NON_LIBRARY = /val nonLibrary = setOf\(([^)]*)\)/s;

// explicitApi() makes the modifier mandatory, so `public` at column 0 IS the public
// top-level surface. Every Kotlin spelling of a declaration is admitted — `fun interface`
// and `annotation class` included, the dodge checks/concentration.ts records as DR-51.
const PUBLIC_DECL =
  /^public\s+(?:(?:sealed|data|abstract|open|value|enum|fun|annotation|suspend|inline|expect|external|const)\s+)*(class|interface|object|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)/;
const PACKAGE = /^package\s+([A-Za-z0-9_.]+)/m;
const STAR_IMPORT = /^import\s+([A-Za-z0-9_.]+)\.\*/gm;

/** One public top-level declaration, and where it lives. */
interface Declaration {
  module: string;
  pkg: string;
  name: string;
  kind: string;
  rel: string;
  line: number;
}

const fqnOf = (d: Declaration): string => (d.pkg ? `${d.pkg}.${d.name}` : d.name);
/** Baseline identity: module + FQN. Deliberately NOT the file or the line — those churn on a
 *  move that changes nothing about the surface, and a baseline that goes stale on a rename
 *  teaches the reader to regenerate it without reading. */
const idOf = (d: Declaration): string => `${d.module} ${fqnOf(d)}`;
const locusOf = (d: Declaration): string => `${d.rel}:${d.line}`;

const USAGE = [
  "usage: public-surface.ts [--ratchet | --report | --json | --write-baseline | --selftest] [ROOT]",
  "",
  "  --ratchet         gate leg: fail on growth or a stale baseline entry",
  "  --report          the census: counts and loci by module",
  "  --json            the census as JSON",
  "  --write-baseline  reprint the baseline from measurement",
  "  --selftest        red-green proof, out of tree",
];

/** (every included module, the nonLibrary set, problems) — both read off the build. */
function modulesOf(root: string): { included: string[]; nonLibrary: Set<string>; problems: string[] } {
  const problems: string[] = [];
  const settings = join(root, SETTINGS_REL);
  const law = join(root, MODULE_LAW_REL);
  if (!existsSync(settings)) {
    return { included: [], nonLibrary: new Set(), problems: [`${SETTINGS_REL}: missing — the module universe IS the denominator, so its absence cannot pass`] };
  }
  if (!existsSync(law)) {
    return { included: [], nonLibrary: new Set(), problems: [`${MODULE_LAW_REL}: missing — nonLibrary is what tells a producer from a consumer`] };
  }
  const included = [...new Set(readFileSync(settings, "utf8").match(MODULE_PATH) ?? [])]
    .map((m) => m.slice(1, -1))
    .sort();
  const match = NON_LIBRARY.exec(readFileSync(law, "utf8"));
  if (match === null) {
    problems.push(
      `${MODULE_LAW_REL}: \`val nonLibrary = setOf(...)\` not found — the producer/consumer ` +
        "split cannot be derived, so no surface from this run can be trusted",
    );
    return { included, nonLibrary: new Set(), problems };
  }
  const nonLibrary = new Set((match[1].match(MODULE_PATH) ?? []).map((m) => m.slice(1, -1)));
  return { included, nonLibrary, problems };
}

/** [(relative path, text)] for every .kt under the given source sets of [module]. */
function sourceText(root: string, module: string, ...subs: string[]): [string, string][] {
  const out: [string, string][] = [];
  for (const sub of subs) {
    const directory = join(root, GRADLE_ROOT_REL, module.replace(/^:/, ""), sub);
    if (!existsSync(directory)) continue;
    const pattern = new Bun.Glob("**/*.kt");
    // followSymlinks, because python's pathlib.rglob descends through a symlinked directory and
    // Bun.Glob does not by default. Measured on this repo's own harness shape: a symlinked module
    // yields its files only with this flag, and the census silently reads zero without it.
    const files = [...pattern.scanSync({ cwd: directory, followSymlinks: true })].sort();
    for (const rel of files) {
      const full = join(directory, rel);
      out.push([relative(root, full), readFileSync(full, "utf8")]);
    }
  }
  return out;
}

function declarations(root: string, module: string): Declaration[] {
  const found: Declaration[] = [];
  for (const [rel, text] of sourceText(root, module, "src/main/kotlin")) {
    const packageMatch = PACKAGE.exec(text);
    const pkg = packageMatch ? packageMatch[1] : "";
    text.split(/\r\n|\r|\n/).forEach((line, i) => {
      const match = PUBLIC_DECL.exec(line);
      if (match === null) return;
      found.push({ module, pkg, name: match[2], kind: match[1], rel, line: i + 1 });
    });
  }
  return found;
}

/** module -> (its consuming text, the packages it star-imports).
 *
 *  main + testFixtures only. src/test is NOT here; see the module docstring — a sibling's
 *  test caller is the population this wall exists to name, not a justification. */
function consumers(root: string, modules: string[]): Map<string, { blob: string; stars: Set<string> }> {
  const out = new Map<string, { blob: string; stars: Set<string> }>();
  for (const module of modules) {
    const texts = sourceText(root, module, "src/main/kotlin", "src/testFixtures/kotlin").map(([, t]) => t);
    const blob = texts.join("\n");
    // matchAll, NOT match: with the `g` flag a pattern carrying a capture group returns the FULL
    // matches — `import fix.lib.*` — and the group is dropped. On the committed tree that is
    // invisible, because no main or testFixtures source star-imports a package that carries an
    // unjustified declaration; the oracle's arm 6 is what exposed it.
    out.set(module, { blob, stars: new Set([...blob.matchAll(STAR_IMPORT)].map((m) => m[1])) });
  }
  return out;
}

/** (offenders, declarations examined, problems). */
function unjustified(root: string): { offenders: Declaration[]; examined: number; problems: string[] } {
  const { included, nonLibrary, problems } = modulesOf(root);
  if (problems.length > 0) return { offenders: [], examined: 0, problems };
  const libraries = included.filter((m) => !nonLibrary.has(m));
  if (libraries.length === 0) {
    return {
      offenders: [],
      examined: 0,
      problems: [
        `${SETTINGS_REL}: zero library modules after removing nonLibrary ` +
          `([${[...nonLibrary].sort().join(", ")}]) — refusing to pass vacuously, because a green over an ` +
          "empty denominator is what this wall exists to prevent",
      ],
    };
  }
  const every = consumers(root, included);
  const offenders: Declaration[] = [];
  let examined = 0;
  for (const module of libraries) {
    for (const declaration of declarations(root, module)) {
      examined += 1;
      const fqn = fqnOf(declaration);
      const token = new RegExp(`(?<![\\w.])${fqn.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?![\\w])`);
      let justified = false;
      for (const [other, { blob, stars }] of every) {
        if (other === module) continue;
        if (stars.has(declaration.pkg) || token.test(blob)) {
          justified = true;
          break;
        }
      }
      if (!justified) offenders.push(declaration);
    }
  }
  if (examined === 0) {
    return {
      offenders: [],
      examined: 0,
      problems: [
        `parsed 0 public top-level declarations across ${libraries.length} library ` +
          "module(s) — every one of them runs under explicitApi(), so a zero here is a " +
          "broken parser rather than a clean surface, and it must not read as green",
      ],
    };
  }
  return { offenders: offenders.sort((a, b) => (idOf(a) < idOf(b) ? -1 : idOf(a) > idOf(b) ? 1 : 0)), examined, problems: [] };
}

// ── the baseline ──────────────────────────────────────────────────────────────────────

const SELF = "bun checks/public-surface.ts";

const BASELINE_LAW =
  "V4-92 ratchet. Each entry is '<module> <fqn>': a public top-level declaration no other " +
  "module's main or testFixtures sources name. Every line is DEBT, not permission — the gate " +
  "fails when a declaration NOT listed here joins them (growth) and when a listed one stops " +
  `offending (stale). Shrink it with \`${SELF} --write-baseline\`; the ` +
  "diff is the record of what moved.";

const RECORDED = /^\d{4}-\d{2}-\d{2}$/;

function readBaseline(root: string): { baseline: Set<string>; recorded: string; problems: string[] } {
  const path = join(root, BASELINE_REL);
  if (!existsSync(path)) {
    return { baseline: new Set(), recorded: "", problems: [`${BASELINE_REL}: missing — the ratchet has no baseline to grade against. Write one with \`${SELF} --write-baseline\`.`] };
  }
  let data: Record<string, unknown>;
  try {
    data = JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
  } catch (error) {
    return { baseline: new Set(), recorded: "", problems: [`${BASELINE_REL}: is not valid JSON (${error}) — a baseline nobody can parse grades nothing`] };
  }
  const recorded = String(data.recorded ?? "");
  const problems: string[] = [];
  if (!RECORDED.test(recorded)) {
    problems.push(
      `${BASELINE_REL}: \`recorded\` is '${recorded}' — every baseline carries the ISO date it ` +
        "was measured, exactly as checks/concentration.ts's RATCHET_RECORDED does; an undated " +
        "baseline is how the next regression hides.",
    );
  }
  const entries = data.offenders;
  if (!Array.isArray(entries) || !entries.every((e) => typeof e === "string")) {
    problems.push(`${BASELINE_REL}: \`offenders\` must be a list of '<module> <fqn>' strings`);
    return { baseline: new Set(), recorded, problems };
  }
  return { baseline: new Set(entries as string[]), recorded, problems };
}

function writeBaseline(root: string, offenders: Declaration[], today: string): void {
  const path = join(root, BASELINE_REL);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(
    path,
    JSON.stringify({ recorded: today, law: BASELINE_LAW, offenders: offenders.map(idOf) }, null, 2) + "\n",
    "utf8",
  );
}

function ratchet(root: string): number {
  const { offenders, examined, problems: measureProblems } = unjustified(root);
  const { baseline, recorded, problems: baselineProblems } = readBaseline(root);
  const problems = [...measureProblems, ...baselineProblems];
  const measured = new Map<string, Declaration>();
  for (const d of offenders) measured.set(idOf(d), d);

  const out: string[] = [];
  out.push(`PUBLIC SURFACE RATCHET — baseline recorded ${recorded || "(none)"}`);
  out.push(`  ${"public top-level declarations".padEnd(34)} measured ${String(examined).padStart(4)}`);
  out.push(`  ${"unjustified (no other-module use)".padEnd(34)} measured ${String(measured.size).padStart(4)}   baseline ${String(baseline.size).padStart(4)}   [GATED]`);

  const growth = [...measured.keys()].filter((k) => !baseline.has(k)).sort();
  const stale = [...baseline].filter((k) => !measured.has(k)).sort();
  if (growth.length > 0) {
    problems.push(
      `GROWTH: ${growth.length} public declaration(s) no other module consumes are not in the ` +
        "baseline. Make each one `internal` (the same code, with the module boundary stated), " +
        "or — if a consumer is genuinely coming — record it with " +
        `\`${SELF} --write-baseline\`, which is a dated diff saying the ` +
        "surface grew:\n    " +
        growth.map((entry) => `${entry}  (${measured.get(entry)!.kind} at ${locusOf(measured.get(entry)!)})`).join("\n    "),
    );
  }
  if (stale.length > 0) {
    problems.push(
      `STALE: ${stale.length} baseline entry(ies) no longer offend — the declaration is gone, ` +
        "became internal, or gained a real consumer. Re-measure with " +
        `\`${SELF} --write-baseline\`. A baseline held above the ` +
        "measured surface is unearned room for the next regression to hide in:\n    " +
        stale.join("\n    "),
    );
  }

  process.stdout.write(out.join("\n") + "\n");
  if (problems.length > 0) {
    process.stderr.write(`\nFAIL: public-surface ratchet — ${problems.length} problem(s):\n`);
    for (const problem of problems) process.stderr.write("  x " + problem + "\n");
    return 1;
  }
  process.stdout.write(
    `\nOK: public-surface ratchet holds — the ${measured.size} unjustified declaration(s) are ` +
      `exactly the ${recorded} baseline, and nothing listed there has stopped offending\n`,
  );
  return 0;
}

/** The census. Exit is about the MEASUREMENT's trustworthiness, never about growth — which is
 *  why the bare invocation no longer reaches it: see the header. */
function report(root: string): number {
  const { offenders, examined, problems } = unjustified(root);
  for (const problem of problems) process.stdout.write("  UNTRUSTED: " + problem + "\n");
  const byModule = new Map<string, Declaration[]>();
  for (const d of offenders) {
    const list = byModule.get(d.module) ?? [];
    list.push(d);
    byModule.set(d.module, list);
  }
  process.stdout.write(`public-surface: ${examined} public top-level declaration(s), ${offenders.length} unjustified\n`);
  for (const module of [...byModule.keys()].sort()) {
    const list = byModule.get(module)!;
    process.stdout.write(`\n  ${module} — ${list.length} unjustified:\n`);
    for (const d of list) process.stdout.write(`    ${d.kind.padEnd(9)} ${fqnOf(d).padEnd(62)} ${locusOf(d)}\n`);
  }
  return problems.length > 0 ? 1 : 0;
}

// ── selftest ──────────────────────────────────────────────────────────────────────────

const SETTINGS_FIXTURE = `rootProject.name = "fixture"
include(
    ":lib",
    ":other",
    ":app",
)
`;

const LAW_FIXTURE = `val moduleLaw: Map<String, Set<String>> = mapOf(":lib" to emptySet())
val nonLibrary = setOf(":app")
`;

function writeModule(root: string, module: string, sub: string, rel: string, text: string): void {
  const path = join(root, GRADLE_ROOT_REL, module.replace(/^:/, ""), sub, rel);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text, "utf8");
}

function fixture(root: string, settings: string = SETTINGS_FIXTURE, law: string = LAW_FIXTURE): void {
  mkdirSync(dirname(join(root, SETTINGS_REL)), { recursive: true });
  writeFileSync(join(root, SETTINGS_REL), settings, "utf8");
  mkdirSync(dirname(join(root, MODULE_LAW_REL)), { recursive: true });
  writeFileSync(join(root, MODULE_LAW_REL), law, "utf8");
}

function baselineFixture(root: string, entries: string[], recorded = "2026-09-17"): void {
  const path = join(root, BASELINE_REL);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify({ recorded, law: BASELINE_LAW, offenders: entries }, null, 2) + "\n", "utf8");
}

function selftest(): number {
  const failures: string[] = [];

  const arm = (label: string, build: (root: string) => void, expectRed: string | null): void => {
    const root = mkdtemp();
    try {
      build(root);
      const { offenders, problems } = unjustified(root);
      // The arms assert the VERDICT, so the ratchet's own reporting is captured rather
      // than interleaved — a selftest whose output is 200 lines of fixture chatter is a
      // selftest nobody reads, and the reason a red arm goes unnoticed.
      const captured = captureOutput(() => ratchet(root));
      const names = [...offenders.map(idOf), ...problems];
      if (expectRed === null) {
        if (captured.code !== 0 || problems.length > 0) {
          failures.push(`${label} — must be GREEN, got exit ${captured.code}: ${names}`);
        }
      } else if (captured.code === 0) {
        failures.push(`${label} — MUST be RED, exited 0 (offenders ${names})`);
      } else if (!captured.text.includes(expectRed)) {
        failures.push(
          `${label} — red for the wrong reason (expected '${expectRed}'): ` +
            captured.text.trim().replace(/\n/g, " ").slice(0, 220),
        );
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  };

  // 1. a consumed public declaration is not a finding, and neither is an internal one.
  const consumed = (root: string): void => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class Api\ninternal class Hidden\n");
    writeModule(root, ":other", "src/main/kotlin", "Use.kt", "package fix.other\nimport fix.lib.Api\ninternal class Use(val a: Api)\n");
    baselineFixture(root, []);
  };
  arm("1. a public declaration another module imports is JUSTIFIED (and `internal` is out of scope)", consumed, null);

  // 2. THE MUTATION THIS ROW REQUIRES: an unjustified public type, baseline empty.
  const synthetic = (root: string): void => {
    consumed(root);
    writeModule(root, ":lib", "src/main/kotlin", "Leak.kt", "package fix.lib\npublic class SelftestLeak(val v: Int)\n");
  };
  arm("2. GROWTH — a synthetic unjustified public type with an empty baseline", synthetic, "GROWTH");
  {
    const root = mkdtemp();
    try {
      synthetic(root);
      const { offenders } = unjustified(root);
      if (!offenders.some((d) => d.name === "SelftestLeak")) {
        failures.push(`2. the synthetic leak must be named: ${offenders.map(idOf)}`);
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  }

  // 3. the same tree with the offender recorded: the ratchet HOLDS.
  const recorded = (root: string): void => {
    synthetic(root);
    baselineFixture(root, [":lib fix.lib.SelftestLeak"]);
  };
  arm("3. a RECORDED offender is not growth — the ratchet holds", recorded, null);

  // 4. a sibling module's src/test caller does NOT justify (audit D row 12's shape).
  const testOnly = (root: string): void => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class TestOnly(val v: Int)\n");
    writeModule(root, ":other", "src/test/kotlin", "T.kt", "package fix.other\nimport fix.lib.TestOnly\nclass T { fun t() = TestOnly(1) }\n");
    baselineFixture(root, []);
  };
  arm("4. a caller in a sibling's src/test is NOT a justification", testOnly, "GROWTH");

  // 5. a testFixtures caller IS a justification — a fixture is shipped cross-module code.
  const fixturesOk = (root: string): void => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class Shared(val v: Int)\n");
    writeModule(root, ":other", "src/testFixtures/kotlin", "F.kt", "package fix.other\nimport fix.lib.Shared\npublic class F(val s: Shared)\n");
    baselineFixture(root, []);
  };
  arm("5. a caller in a sibling's src/testFixtures IS a justification", fixturesOk, null);

  // 6. a star import of the package justifies every declaration in it.
  const star = (root: string): void => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class Starred(val v: Int)\n");
    writeModule(root, ":other", "src/main/kotlin", "Use.kt", "package fix.other\nimport fix.lib.*\ninternal class Use(val s: Starred)\n");
    baselineFixture(root, []);
  };
  arm("6. a star import of the package is a justification", star, null);

  // 7. STALE — a baseline entry that no longer offends.
  const staleJustified = (root: string): void => {
    consumed(root);
    baselineFixture(root, [":lib fix.lib.Api"]);
  };
  arm("7. STALE — a baseline entry that has gained a consumer", staleJustified, "STALE");

  const staleGone = (root: string): void => {
    consumed(root);
    baselineFixture(root, [":lib fix.lib.DeletedLongAgo"]);
  };
  arm("8. STALE — a baseline entry whose declaration no longer exists", staleGone, "STALE");

  // 9. an undated baseline is a hard error, not a pass.
  const undated = (root: string): void => {
    consumed(root);
    baselineFixture(root, [], "");
  };
  arm("9. an undated baseline is a hard error", undated, "recorded");

  // 10. THE BORING CASES, which are the ones that get waved through (§24).
  const noModules = (root: string): void => {
    fixture(root, 'rootProject.name = "fixture"\ninclude(\n    ":app",\n)\n');
    writeModule(root, ":app", "src/main/kotlin", "M.kt", "package fix.app\nclass M\n");
    baselineFixture(root, []);
  };
  arm("10. a tree whose every module is nonLibrary must REFUSE, not pass vacuously", noModules, "vacuously");

  const noDeclarations = (root: string): void => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\ninternal class OnlyInternal\n");
    baselineFixture(root, []);
  };
  arm("11. a library tree with zero public declarations is a broken parse, not a clean surface", noDeclarations, "broken parser");

  const oneDeclaration = (root: string): void => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class One(val v: Int)\n");
    baselineFixture(root, [":lib fix.lib.One"]);
  };
  arm("12. the one-item tree grades green WITH its count", oneDeclaration, null);

  const noLaw = (root: string): void => {
    mkdirSync(dirname(join(root, SETTINGS_REL)), { recursive: true });
    writeFileSync(join(root, SETTINGS_REL), SETTINGS_FIXTURE, "utf8");
    baselineFixture(root, []);
  };
  arm("13. a missing module law is a hard error — the producer/consumer split is derived from it", noLaw, "missing");

  if (failures.length > 0) {
    process.stdout.write("public-surface SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  x " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "public-surface SELFTEST OK — a consumed declaration, an internal one, a " +
      "testFixtures consumer, a star import and a recorded offender are green; a synthetic " +
      "unjustified public type, a sibling test-only caller, a stale baseline entry (justified " +
      "and deleted), an undated baseline, a tree with no library modules, a tree with no public " +
      "declarations and a missing module law are all red\n",
  );
  return 0;
}

function mkdtemp(): string {
  const dir = join(tmpdir(), `public-surface-${process.pid}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(dir, { recursive: true });
  return dir;
}

/** Run [body] with stdout and stderr captured, returning its exit code and the text. */
function captureOutput(body: () => number): { code: number; text: string } {
  const chunks: string[] = [];
  const realOut = process.stdout.write.bind(process.stdout);
  const realErr = process.stderr.write.bind(process.stderr);
  const grab = (chunk: unknown): boolean => {
    chunks.push(String(chunk));
    return true;
  };
  process.stdout.write = grab as typeof process.stdout.write;
  process.stderr.write = grab as typeof process.stderr.write;
  try {
    const code = body();
    return { code, text: chunks.join("") };
  } finally {
    process.stdout.write = realOut;
    process.stderr.write = realErr;
  }
}

function main(argv: string[]): number {
  let root: string | null = null;
  const flags = { ratchet: false, report: false, json: false, writeBaseline: false, selftest: false };
  for (const a of argv) {
    if (a === "--ratchet") flags.ratchet = true;
    else if (a === "--report") flags.report = true;
    else if (a === "--json") flags.json = true;
    else if (a === "--write-baseline") flags.writeBaseline = true;
    else if (a === "--selftest") flags.selftest = true;
    else if (a.startsWith("--")) {
      process.stderr.write(`${USAGE.join("\n")}\n`);
      return 2;
    } else if (root === null) root = a;
    else {
      process.stderr.write(`${USAGE.join("\n")}\n`);
      return 2;
    }
  }

  if (flags.selftest) return selftest();
  const resolved = root ? resolve(root) : ROOT;
  if (!existsSync(resolved)) {
    process.stderr.write(`public-surface: ${resolved} does not exist\n`);
    return 2;
  }

  if (flags.writeBaseline) {
    const { offenders, examined, problems } = unjustified(resolved);
    if (problems.length > 0) {
      process.stderr.write("refusing to write a baseline from an untrusted measurement:\n");
      for (const problem of problems) process.stderr.write("  x " + problem + "\n");
      return 2;
    }
    const today = new Date().toISOString().slice(0, 10);
    writeBaseline(resolved, offenders, today);
    process.stdout.write(
      `wrote ${BASELINE_REL}: ${offenders.length} unjustified of ${examined} public top-level ` +
        `declaration(s), recorded ${today}. READ THE DIFF — it is the record of what moved.\n`,
    );
    return 0;
  }

  if (flags.json) {
    const { offenders, examined, problems } = unjustified(resolved);
    process.stdout.write(
      JSON.stringify(
        {
          examined,
          problems,
          offenders: offenders.map((d) => ({ module: d.module, fqn: fqnOf(d), kind: d.kind, file: d.rel, line: d.line })),
        },
        null,
        2,
      ) + "\n",
    );
    return problems.length > 0 ? 1 : 0;
  }

  if (flags.ratchet) return ratchet(resolved);
  if (flags.report) return report(resolved);
  // NO BARE MODE: see the header. A census that answers 0 while the gate is red is a gate that
  // did not run wearing a pass, so misuse exits 2 and measures nothing.
  process.stdout.write(`${USAGE.join("\n")}\n`);
  return 2;
}

process.exit(main(process.argv.slice(2)));
