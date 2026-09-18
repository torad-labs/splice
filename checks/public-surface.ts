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
  /** V4-149: the declaration's HEADER text — its own line plus the continuation lines up to the
   *  body. This is where a type in a parameter or return position lives, and it is the whole reason
   *  the interface grew a field: a type reached only through someone else's signature is part of the
   *  public contract even though no source file spells its name. */
  signature: string;
}

/** How many lines a declaration header may span before the scan gives up. A cap rather than
 *  "until the body", because a declaration whose header never closes would otherwise swallow the
 *  rest of the file and justify every name in it — the failure mode this whole row is about,
 *  inverted. Twenty is well past the widest real data class here. */
const SIGNATURE_MAX_LINES = 20;

/** A PUBLIC MEMBER of a declaration — the second half of the surface, and the half that defeated
 *  this checker in V4-104. `EconomicsStore.read(): List<EconomicsBucket>` is a member, so its return
 *  type is part of the contract, but it is neither a top-level declaration nor in its class's header.
 *  Requires the explicit `public`, which explicitApi() makes mandatory, so an indented `public` line
 *  is a member signature and not prose. */
const MEMBER_DECL = /^\s+(?:public\s|override\s+public\s|public\s+override\s)/;

/** The text that carries a declaration's reachable type names: its own header, plus the signature
 *  lines of its PUBLIC members.
 *
 *  Two approximations, both stated rather than hidden, because the honest shape of this wall is
 *  "close enough to stop lying" rather than "a Kotlin parser":
 *   · the header scan is CAPPED (SIGNATURE_MAX_LINES) so a header that never closes cannot swallow
 *     the file;
 *   · members are found by their `public` line, so a member's body is excluded and only the
 *     signature is read — but a type named ONLY inside a private member still slips through, which
 *     OVER-justifies. That direction is chosen deliberately: over-justifying costs a missed
 *     declaration, and under-justifying is what cost five good ones in V4-104. */
function signatureOf(lines: string[], at: number): string {
  const out: string[] = [];
  // The header: this line, through the continuation, to the body.
  let head = at;
  for (; head < lines.length && head - at < SIGNATURE_MAX_LINES; head += 1) {
    out.push(lines[head]);
    if (lines[head].includes("{") || /=\s*$/.test(lines[head])) break;
  }
  // The public members, to the next top-level declaration.
  for (let i = head + 1; i < lines.length; i += 1) {
    if (PUBLIC_DECL.test(lines[i])) break;
    if (MEMBER_DECL.test(lines[i])) out.push(lines[i]);
  }
  return out.join("\n");
}

const fqnOf = (d: Declaration): string => (d.pkg ? `${d.pkg}.${d.name}` : d.name);
/** Identifiers inside a declaration header — how the closure reads the type names a signature
 *  mentions. Matched on the SIMPLE name, because that is how a signature spells it. */
const IDENT = /[A-Za-z_][A-Za-z0-9_]*/g;
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
    const lines = text.split(/\r\n|\r|\n/);
    lines.forEach((line, i) => {
      const match = PUBLIC_DECL.exec(line);
      if (match === null) return;
      found.push({
        module,
        pkg,
        name: match[2],
        kind: match[1],
        rel,
        line: i + 1,
        signature: signatureOf(lines, i),
      });
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
  const all: Declaration[] = [];
  for (const module of libraries) all.push(...declarations(root, module));
  const examined = all.length;

  // ── ROOTS: a declaration another module NAMES, or whose package it star-imports. ──
  const justified = new Set<string>();
  for (const declaration of all) {
    const fqn = fqnOf(declaration);
    const token = new RegExp(`(?<![\\w.])${fqn.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?![\\w])`);
    for (const [other, { blob, stars }] of every) {
      if (other === declaration.module) continue;
      if (stars.has(declaration.pkg) || token.test(blob)) {
        justified.add(fqn);
        break;
      }
    }
  }

  // ── V4-149: THE CLOSURE OVER PUBLIC SIGNATURES. ──
  //
  // A name-reference census measures the wrong denominator, and V4-104 proved it by hand: a public
  // member's parameter or return type is part of the contract even when no source file spells it,
  // because the call site binds it by inference and a name-based blob scan cannot see that. Five
  // declarations could not be internalised for exactly this reason, and the Kotlin compiler — not
  // this checker — was the thing that said so.
  //
  // So justification propagates: if a declaration is consumed by another module, every declaration
  // its SIGNATURE mentions is reachable from that consumer too. Iterated to a fixpoint rather than
  // one hop, because a chain (consumed type -> parameter type -> field type) is the same argument
  // applied twice, and stopping at depth one would just relocate the blind spot.
  //
  // Deliberately matched on the SIMPLE name, since a signature says `List<EconomicsBucket>` and not
  // the FQN. That is a wider net than the roots' FQN token, and the width is the point: over-
  // justification here costs a missed declaration, while under-justification cost five good ones.
  const byName = new Map<string, Declaration[]>();
  for (const declaration of all) {
    const list = byName.get(declaration.name) ?? [];
    list.push(declaration);
    byName.set(declaration.name, list);
  }
  let changed = true;
  while (changed) {
    changed = false;
    for (const declaration of all) {
      if (!justified.has(fqnOf(declaration))) continue;
      for (const name of new Set(declaration.signature.match(IDENT) ?? [])) {
        for (const target of byName.get(name) ?? []) {
          if (!justified.has(fqnOf(target))) {
            justified.add(fqnOf(target));
            changed = true;
          }
        }
      }
    }
  }

  for (const declaration of all) {
    if (!justified.has(fqnOf(declaration))) offenders.push(declaration);
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

function readBaseline(
  root: string,
): { baseline: Set<string>; kept: Map<string, string>; recorded: string; problems: string[] } {
  const path = join(root, BASELINE_REL);
  if (!existsSync(path)) {
    return { baseline: new Set(), kept: new Map(), recorded: "", problems: [`${BASELINE_REL}: missing — the ratchet has no baseline to grade against. Write one with \`${SELF} --write-baseline\`.`] };
  }
  let data: Record<string, unknown>;
  try {
    data = JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
  } catch (error) {
    return { baseline: new Set(), kept: new Map(), recorded: "", problems: [`${BASELINE_REL}: is not valid JSON (${error}) — a baseline nobody can parse grades nothing`] };
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
    return { baseline: new Set(), kept: new Map(), recorded, problems };
  }
  // V4-149: `kept` is the companion to `offenders` — entries that CANNOT be burned, each with the
  // reason it cannot. Absent is legal (a baseline that needs no reasons has none); present but
  // malformed is not, because a reason nobody can read is the absence it was written to remove.
  const kept = new Map<string, string>();
  const rawKept = data.kept;
  if (rawKept !== undefined) {
    if (typeof rawKept !== "object" || rawKept === null || Array.isArray(rawKept)) {
      problems.push(`${BASELINE_REL}: \`kept\` must be an object mapping '<module> <fqn>' to a reason string`);
    } else {
      for (const [entry, reason] of Object.entries(rawKept as Record<string, unknown>)) {
        // The `law` key is the block's own prose header, not an entry, and is skipped by name.
        if (entry === "law") continue;
        kept.set(entry, typeof reason === "string" ? reason : "");
      }
    }
  }
  return { baseline: new Set(entries as string[]), kept, recorded, problems };
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
  const { baseline, kept, recorded, problems: baselineProblems } = readBaseline(root);
  const problems = [...measureProblems, ...baselineProblems];
  const measured = new Map<string, Declaration>();
  for (const d of offenders) measured.set(idOf(d), d);

  const out: string[] = [];
  out.push(`PUBLIC SURFACE RATCHET — baseline recorded ${recorded || "(none)"}`);
  out.push(`  ${"public top-level declarations".padEnd(34)} measured ${String(examined).padStart(4)}`);
  out.push(`  ${"unjustified (no other-module use)".padEnd(34)} measured ${String(measured.size).padStart(4)}   baseline ${String(baseline.size).padStart(4)}   [GATED]`);

  const growth = [...measured.keys()].filter((k) => !baseline.has(k)).sort();
  // V4-149: STALE NOW MEANS "NOTHING EXPLAINS IT". `kept` is where a burn-proof entry carries the
  // reason it cannot move, so the union of what is measured and what is explained is the set the
  // baseline is allowed to hold; an entry in neither is the unearned room this leg exists to catch.
  const stale = [...baseline].filter((k) => !measured.has(k) && !kept.has(k)).sort();
  // A blank reason is an absence wearing a label, which is worse than no reason at all: it reads as
  // discharged in a diff and discharges nothing. Checked separately from `stale` so the message can
  // say which failure this is.
  const blankReason = [...kept.keys()].filter((k) => (kept.get(k) ?? "").trim() === "").sort();
  // A reason for an entry the baseline does not hold is stale bookkeeping in the other direction —
  // and, since a burnt entry is REMOVED from `offenders`, it is also the shape a half-finished
  // burn-down leaves behind.
  const orphanKept = [...kept.keys()].filter((k) => !baseline.has(k)).sort();
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
  if (blankReason.length > 0) {
    problems.push(
      `BLANK REASON: ${blankReason.length} \`kept\` entry(ies) carry no reason. A blank reason is an ` +
        "absence wearing a label — it reads as discharged in a diff and discharges nothing, which " +
        "is worse than no reason at all because it stops the next reader asking:\n    " +
        blankReason.join("\n    "),
    );
  }
  if (orphanKept.length > 0) {
    problems.push(
      `ORPHAN KEPT: ${orphanKept.length} \`kept\` entry(ies) explain something the baseline does ` +
        "not hold. A burnt entry is REMOVED from `offenders`, so a reason left behind is either " +
        "stale bookkeeping or a half-finished burn-down wearing a justification:\n    " +
        orphanKept.join("\n    "),
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

/** V4-149: the same, with a `kept` block. A separate helper rather than an optional argument,
 *  because the arms that need it are ABOUT the block and an argument nobody passes reads as
 *  incidental. */
function keptFixture(root: string, entries: string[], kept: Record<string, string>): void {
  const path = join(root, BASELINE_REL);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify({ recorded: "2026-09-17", law: BASELINE_LAW, offenders: entries, kept }, null, 2) + "\n", "utf8");
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

  // ── V4-149 ──────────────────────────────────────────────────────────────────────────
  //
  // THE CLOSURE, PROVEN BOTH WAYS. The pair below differs in ONE character of intent: the first has
  // the member `public`, the second `internal`, and they must land on opposite verdicts. This is
  // V4-104's five declarations in miniature — a type named nowhere, reachable only through a
  // consumed class's public member — and the arm exists because the FIRST cut of the closure walked
  // top-level headers only and missed exactly this shape. An arm that only proved the green half
  // would have passed that broken cut.
  const closingSignature = (member: string): ((root: string) => void) => (root: string) => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Store.kt", `package fix.lib\n\npublic class Store {\n    ${member} fun read(): Hidden = Hidden()\n}\n\npublic class Hidden\n`);
    writeModule(root, ":other", "src/main/kotlin", "Use.kt", "package fix.other\nimport fix.lib.Store\ninternal class Use(val s: Store)\n");
    baselineFixture(root, []);
  };
  arm("14. CLOSURE — a public member's return type rides its consumed class (green)", closingSignature("public"), null);
  arm("15. CLOSURE guard — the SAME member made internal leaves the type offending (red)", closingSignature("internal"), "GROWTH");

  // A reason that is present but empty. It must fail, because "documented-but-unenforced" is the
  // state this row removes: an entry in `kept` is a claim to have explained something, and a blank
  // reason performs the explanation without making it.
  const blankReason = (root: string): void => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class One(val v: Int)\n");
    keptFixture(root, [":lib fix.lib.One"], { ":lib fix.lib.One": "   " });
  };
  arm("16. a blank `kept` reason is an absence wearing a label (red)", blankReason, "BLANK REASON");

  // And the other direction: a reason for something the baseline does not hold is a half-finished
  // burn-down wearing a justification.
  const orphanReason = (root: string): void => {
    fixture(root);
    writeModule(root, ":lib", "src/main/kotlin", "Api.kt", "package fix.lib\npublic class One(val v: Int)\n");
    keptFixture(root, [":lib fix.lib.One"], { ":lib fix.lib.Gone": "explaining something absent" });
  };
  arm("17. a `kept` reason for an entry the baseline does not hold (red)", orphanReason, "ORPHAN KEPT");

  if (failures.length > 0) {
    process.stdout.write("public-surface SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  x " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "public-surface SELFTEST OK — a consumed declaration, an internal one, a " +
      "testFixtures consumer, a star import, a recorded offender and a public member's return type " +
      "reached through its consumed class are green; a synthetic " +
      "unjustified public type, a sibling test-only caller, a stale baseline entry (justified " +
      "and deleted), an undated baseline, a tree with no library modules, a tree with no public " +
      "declarations, a missing module law, that SAME return type once its member is internal, a " +
      "blank `kept` reason and an orphan `kept` reason are all red\n",
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
