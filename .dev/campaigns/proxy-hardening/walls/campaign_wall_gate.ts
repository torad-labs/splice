#!/usr/bin/env bun
/** campaign_wall_gate — an unwalled campaign item is structurally inexpressible.
 *
 *  WHAT  The proxy-hardening campaign's enforcement census. Items must name an enforcing WALL, laws
 *        must name an enforcing CHECKER, walls must be real and honest, and fences must not collide.
 *
 *  LAW   #924 ("you make drift not compile") + #954 (all-red-then-green: "the error list IS the
 *        complete, honest work inventory; green is EARNED, never protected"). Ported from qgre's
 *        dev/gates/law-wall-registry-gate script.
 *
 *  SEVERITY IS SPLIT (2026-07-26 review finding #2). Lumping "79 walls still to build" together with
 *        "a wall is lying" forced the whole gate out of `npm run gate`, which meant NOTHING ran it and
 *        a false-green could sit undetected forever. Now:
 *
 *          BLOCKING  — never acceptable, safe to wire into `npm run gate` today:
 *                      C1 census   an item has zero or >1 registry rows
 *                      C2 orphan   a registry row names no live item
 *                      C3 missing  a named wall is not on disk
 *                      C5 polarity a todo item's wall PASSES (vacuous) / a done item's wall FAILS
 *                      C6 control  a wall does not pass its OWN --selftest (positive control)
 *                      C10 live    two IN_FLIGHT items own the same file (real concurrent writers)
 *                      C11 unverif a wall could not be run inside the total budget — its polarity is
 *                                  UNKNOWN, which must never be reported as a pass (review finding #8)
 *                      C9 law      a law enforcer FAILS — the law is being broken right now
 *          ADVISORY  — the standing worklist, reported but non-blocking:
 *                      C4 unwalled an item has no wall yet
 *                      C7 fence    two TODO items in one phase share a derived fence. Advisory on
 *                                  purpose: fences here are superset heuristics to be narrowed by
 *                                  `edit-fence` at claim time, and an unclaimed item cannot write
 *                                  anything, so it cannot collide yet. C10 is the real hazard.
 *                      C8 unlawed  a law has no mechanical enforcer
 *
 *        Exit 0 = no BLOCKING findings. `--strict` makes advisory blocking too (the end state).
 *
 *  THE POLARITY LAW (C5): a wall for a todo item MUST FAIL. A wall that passes while its item is
 *        unfinished is VACUOUS — it does not detect the gap it claims to guard.
 *
 *  THE POSITIVE CONTROL (C6, review finding #1): polarity alone is not enough. A wall that is merely
 *        `exit(1)` also "fails on a todo item", so it looks honest for the campaign's whole life and
 *        only betrays you at the moment you mark the item done. PROVEN 2026-07-26 by registering a
 *        do-nothing wall: it counted as `walled` and produced zero findings. So every wall must also
 *        prove it CAN go green — `<wall> --selftest` runs its detection against synthetic
 *        closed-gap/open-gap inputs and must exit 0. Red-green, applied to the walls themselves.
 *
 *  RUN   npm run gate:campaign            blocking only (safe for the main ladder)
 *        npm run gate:campaign:strict     blocking + advisory (the worklist view)
 *        npm run gate:campaign:census     census only, no wall execution — EXIT 2, never 0, so it
 *                                         can never be mistaken in CI for a passing polarity run
 *        npm run gate:campaign:selftest   this gate's own red/green fixtures
 *
 *  LEDGER SOURCE  the board TOML itself, read once. The CLI is the sanctioned channel for WRITES,
 *        which is what its flock and atomic append buy; a read routed through a human-facing table
 *        only adds a column order nobody versioned. See ledgerItems.
 *
 *  V4-154: converted to TypeScript (bun) LAST, after the 35 walls it drives. A faithful port: the
 *  findings, their wording, the report, the exit codes and the selftest's cases are the Python
 *  file's. Its ledger read went straight to the TOML in V4-143's cutover (2026-09-18) rather than to
 *  the TypeScript CLI: that CLI prints the columns in a different ORDER, which the count cross-check
 *  could not have caught, so the cutover deleted the parse instead of re-aiming it. TWO DELIBERATE
 *  DEVIATIONS, both about
 *  the one thing this repo no longer allows, an interpreter spawned for its own sake:
 *    1. runWall's fallback for a suffix other than .sh/.ts used to run the wall under the Python
 *       interpreter. No wall of that kind exists (the 35 are .ts) and checks/no-python.ts forbids a
 *       new one, so the fallback now REFUSES by name, exit 126, which the gate reports as C6/C5 and
 *       never as a pass. Keeping it would have made this file a new invoker for a reason of its own.
 *    2. The selftest's synthetic walls are written as .ts and run under bun instead of as .py, and
 *       its C3 fixture names a missing .ts. The cases and their expected codes are unchanged.
 */
import { spawnSync } from "node:child_process";
import { constants } from "node:os";
import { mkdtempSync, rmSync, writeFileSync, existsSync, readFileSync } from "node:fs";
import { basename, isAbsolute, resolve, relative, join } from "node:path";
import { tmpdir } from "node:os";

const ROOT = resolve(import.meta.dir, "../../../..");
const CAMPAIGN = resolve(ROOT, ".dev/campaigns/proxy-hardening");
const REGISTRY = resolve(CAMPAIGN, "walls/wall_registry.toml");
const LAW_REGISTRY = resolve(CAMPAIGN, "walls/law_registry.toml");
const BOARD = resolve(ROOT, ".dev/campaigns/proxy-hardening.toml");

const RED_STATUSES = new Set(["todo", "in_flight"]);
const GREEN_STATUSES = new Set(["done", "verified"]);
const WALL_TIMEOUT_S = 120;
const TOTAL_WALL_BUDGET_S = 900;

const BLOCKING = "BLOCK";
const ADVISORY = "ADVIS";
export const SEVERITY: Record<string, string> = {
  C1: BLOCKING, C2: BLOCKING, C3: BLOCKING, C4: ADVISORY, C5: BLOCKING, C6: BLOCKING,
  C7: ADVISORY, C8: ADVISORY, C9: BLOCKING, C10: BLOCKING, C11: BLOCKING,
};

type Items = Map<string, [string, string]>; // id -> [phase, status], in ledger order
type Row = Record<string, unknown>;
type Stats = Record<string, number>;

/** Python's str.split() with no argument: split on runs of whitespace, no empty ends. */
function words(s: string): string[] {
  return s.split(/\s+/).filter((w) => w !== "");
}

function code(f: string): string {
  return words(f)[0] ?? "";
}

/** Python's str.isalpha(): non-empty and every character a letter. */
function isAlpha(s: string): boolean {
  return /^\p{L}+$/u.test(s);
}

/** Python's s[:n], which counts code points rather than UTF-16 units. */
function head(s: string, n: number): string {
  return Array.from(s).slice(0, n).join("");
}

/** Python's str.splitlines() on text-mode output (universal newlines already applied). */
function lines(s: string): string[] {
  const out = s.split(/\r\n|[\n\r\v\f\x1c\x1d\x1e\x85\u2028\u2029]/);
  if (out.length && out[out.length - 1] === "") out.pop();
  return out;
}

function die(msg: string): never {
  process.stderr.write(msg + "\n");
  process.exit(1);
}

function parseToml(path: string): Record<string, unknown> {
  return Bun.TOML.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
}

// ── inputs ───────────────────────────────────────────────────────────────────

/** id -> [phase, status], read straight from the ledger TOML.
 *
 *  IT USED TO SHELL OUT TO THE LEDGER CLI AND PARSE ITS COLUMNS, and that was two bugs waiting
 *  (V4-143, 2026-09-18). The CLI's listing is a HUMAN-FACING table: the bun CLI that replaced the
 *  Python one prints a leading bullet and orders the columns `id status phase`, where the old one
 *  printed `id phase status`. The bullet would have zeroed this parse and tripped the count
 *  cross-check below — but the SWAP would not have. Eighty-eight rows would have parsed, the count
 *  would have matched, and every row's phase would have been read as its status: a confident wrong
 *  verdict on every wall, from a check that looked like it ran.
 *
 *  So this reads the ledger it was already reading for the cross-check. The CLI is the sanctioned
 *  channel for WRITES — that is what its flock and its atomic append buy. For a read, routing
 *  through a formatted table only adds a column order nobody versioned. The cross-check is gone
 *  with the parse it was checking: there is no longer a second reading to disagree with the first. */
export function ledgerItems(board: string = BOARD): Items {
  const all = parseToml(board).items;
  if (!Array.isArray(all)) die(`campaign_wall_gate: ${relative(ROOT, board)} has no [[items]]`);
  const out: Items = new Map();
  for (const row of all as Row[]) {
    const id = String(row.id ?? "");
    if (id !== "") out.set(id, [String(row.phase ?? ""), String(row.status ?? "")]);
  }
  return out;
}

export function rowsOf(path: string, key: string): Row[] {
  if (!existsSync(path)) return [];
  const rows = parseToml(path)[key];
  return (Array.isArray(rows) ? rows : []).filter(
    (r): r is Row => typeof r === "object" && r !== null && !Array.isArray(r),
  );
}

export function itemFences(board: string = BOARD): Map<string, string[]> {
  const all = parseToml(board).items;
  const out = new Map<string, string[]>();
  for (const i of (Array.isArray(all) ? all : []) as Row[]) {
    out.set(String(i.id), Array.isArray(i.files) ? i.files.map(String) : []);
  }
  return out;
}

// ── wall execution ───────────────────────────────────────────────────────────

function resolveWall(wall: string): string {
  return isAbsolute(wall) ? wall : resolve(ROOT, wall);
}

export function runWall(wall: string, selftest = false, timeout: number = WALL_TIMEOUT_S): [number, string] {
  const target = resolveWall(wall);
  // THE DISPATCH IS BY SUFFIX, so the runner does not care what language a wall is written in — it
  // only has to know what RUNS that suffix. That is what let the walls convert one at a time under
  // the old runner. Anything else is refused (see the header, deviation 1): exit 126 is a red the
  // gate reports, never a pass.
  let cmd: string[];
  if (target.endsWith(".sh")) cmd = ["bash", target];
  else if (target.endsWith(".ts")) cmd = ["bun", target];
  else return [126, `no runner for '${basename(target)}': walls are .sh or .ts in this repo`];
  if (selftest) cmd.push("--selftest");
  const proc = spawnSync(cmd[0], cmd.slice(1), {
    cwd: ROOT, encoding: "utf8", timeout: timeout * 1000, killSignal: "SIGKILL", maxBuffer: 1 << 30,
  });
  if (proc.error && (proc.error as NodeJS.ErrnoException).code === "ETIMEDOUT") {
    return [124, `timed out after ${timeout}s`];
  }
  if (proc.error) throw proc.error; // a runner that cannot start is an environment fault, not a verdict
  const rc = proc.status ?? -(constants.signals[proc.signal as keyof typeof constants.signals] ?? 1);
  const tail = lines(((proc.stdout ?? "") + (proc.stderr ?? "")).replace(/\r\n?/g, "\n").trim());
  return [rc, tail.length ? head(tail[tail.length - 1], 190) : ""];
}

// ── the audit ────────────────────────────────────────────────────────────────

type AuditOpts = {
  fences?: Map<string, string[]> | null;
  laws?: Row[] | null;
  runPolarity?: boolean;
  runControls?: boolean;
  budgetS?: number;
};

export function audit(items: Items, rows: Row[], opts: AuditOpts = {}): [string[], Stats] {
  const { fences = null, laws = null, runPolarity = true, runControls = true } = opts;
  const findings: string[] = [];
  let left = opts.budgetS ?? TOTAL_WALL_BUDGET_S;

  /** Run a wall against the shared budget. null = not run (caller must raise C11, never pass). */
  const spend = (wall: string, selftest = false): [number, string] | null => {
    if (left <= 0) return null;
    const t0 = performance.now();
    const out = runWall(wall, selftest, Math.trunc(Math.max(5, Math.min(WALL_TIMEOUT_S, left))));
    left -= (performance.now() - t0) / 1000;
    return out;
  };
  const byId = new Map<string, Row[]>();
  for (const r of rows) {
    const id = String(r.id ?? "");
    if (!byId.has(id)) byId.set(id, []);
    byId.get(id)!.push(r);
  }

  for (const itemId of [...items.keys()].sort()) {
    const n = byId.get(itemId)?.length ?? 0;
    if (n === 0) {
      findings.push(`C1 UNREGISTERED  ${itemId}: in the ledger, absent from wall_registry.toml`);
    } else if (n > 1) {
      findings.push(`C1 AMBIGUOUS     ${itemId}: ${n} registry rows share this id`);
    }
  }
  for (const rowId of [...byId.keys()].sort()) {
    if (!items.has(rowId)) {
      findings.push(
        `C2 ORPHAN        ${rowId}: registry row names no ledger item. A row may NEVER be ` +
          "deleted to silence this — fix the id or the ledger.",
      );
    }
  }

  const stats: Stats = {
    total: items.size, unwalled: 0, walled: 0, retired: 0, green: 0, vacuous: 0, false_green: 0,
    uncontrolled: 0, laws: 0, unlawed: 0, lawed: 0, law_violations: 0,
  };

  for (const itemId of [...items.keys()].sort()) {
    const status = items.get(itemId)![1];
    const rs = byId.get(itemId) ?? [];
    if (rs.length !== 1) continue;
    const wall = String(rs[0].wall ?? "").trim();
    if (!wall) {
      // A RETIRED wall (restructure PR 6, plan §2.7): the guarantee moved into the owning module's
      // own test and the wrapper was deleted AFTER an equivalence proof — red and green on the same
      // violation on both sides. The row keeps the enduring home and the proof sentence, so the
      // item is still walled: by a test the ladder runs, not by a script this gate runs. A home
      // nobody writes is a lie the same way a missing .ts is (C3); a retirement without its proof
      // is a wall deleted on a promise.
      //
      // ONE ROW, SEVERAL HOMES. A wall that guarded two things splits into two — a unit test for
      // the behaviour, and an architecture law for the shape no runtime can observe (NF-04's parse
      // contract and its one-parser law; JW-01's boot net and the ordering that installs it).
      // `retired_to` therefore takes a string OR a list, and EVERY named home must exist: crediting
      // a row for one existing home would let the other be deleted under a green gate, which is the
      // disappearing-denominator shape this gate exists against.
      const homes = (Array.isArray(rs[0].retired_to) ? rs[0].retired_to : [rs[0].retired_to])
        .map((h: unknown) => String(h ?? "").trim())
        .filter((h: string) => h !== "");
      if (homes.length > 0) {
        const proof = String(rs[0].proof ?? "").trim();
        const missing = homes.filter((h: string) => !existsSync(resolveWall(h)));
        if (missing.length > 0) {
          findings.push(`C3 RETIRED HOME  ${itemId}: retired_to ${pyList(missing)} does not exist on disk`);
        } else if (!proof) {
          findings.push(`C3 RETIRED BLIND ${itemId}: retired_to ${pyList(homes)} carries no proof — a wall is retired on its equivalence proof, never on a promise`);
        } else {
          stats.retired += 1;
        }
        continue;
      }
      stats.unwalled += 1;
      findings.push(`C4 UNWALLED      ${itemId} [${status}]: no wall. The fix is BUILDING THE WALL, not editing the row.`);
      continue;
    }
    if (!existsSync(resolveWall(wall))) {
      findings.push(`C3 MISSING WALL  ${itemId}: wall '${wall}' does not exist on disk`);
      continue;
    }
    stats.walled += 1;

    // C6 — positive control. Runs FIRST: a wall with no proven green state cannot be trusted to
    // mean anything by C5, so its polarity verdict is worthless until this passes.
    if (runControls) {
      const res = spend(wall, true);
      if (res === null) {
        findings.push(
          `C11 UNVERIFIED   ${itemId}: total wall budget exhausted before '${wall}' could run its ` +
            "positive control — polarity UNKNOWN. Never reported as a pass.",
        );
        continue;
      }
      const [rc, note] = res;
      if (rc !== 0) {
        stats.uncontrolled += 1;
        findings.push(
          `C6 NO CONTROL    ${itemId}: wall '${wall}' does not pass its own --selftest (exit ${rc}). ` +
            "Without a positive control a do-nothing `exit(1)` is indistinguishable from real " +
            `enforcement. ${note}`,
        );
      }
    }

    if (!runPolarity) continue;
    const res = spend(wall);
    if (res === null) {
      findings.push(
        `C11 UNVERIFIED   ${itemId}: total wall budget exhausted before '${wall}' could run — ` +
          "polarity UNKNOWN. Never reported as a pass.",
      );
      continue;
    }
    const [rc, note] = res;
    if (RED_STATUSES.has(status) && rc === 0) {
      stats.vacuous += 1;
      findings.push(
        `C5 VACUOUS WALL  ${itemId} [${status}]: wall '${wall}' PASSES while the item is unfinished. ` +
          `It does not detect the gap it claims to guard. ${note}`,
      );
    } else if (GREEN_STATUSES.has(status) && rc !== 0) {
      stats.false_green += 1;
      findings.push(
        `C5 FALSE STATUS  ${itemId} [${status}]: wall '${wall}' FAILS (exit ${rc}) but the item claims done. ${note}`,
      );
    } else if (GREEN_STATUSES.has(status) && rc === 0) {
      stats.green += 1;
    }
  }

  // C7 — fence exclusivity, mechanical (review finding #4: `claim` does not check this)
  if (fences && fences.size) {
    const byPhase = new Map<string, Map<string, string[]>>();
    const inflight = new Map<string, string[]>();
    for (const [iid, [phase, status]] of items) {
      for (const f of fences.get(iid) ?? []) {
        if (!byPhase.has(phase)) byPhase.set(phase, new Map());
        const owners = byPhase.get(phase)!;
        if (!owners.has(f)) owners.set(f, []);
        owners.get(f)!.push(iid);
        if (status === "in_flight") {
          if (!inflight.has(f)) inflight.set(f, []);
          inflight.get(f)!.push(iid);
        }
      }
    }
    for (const phase of [...byPhase.keys()].sort()) {
      const owners = byPhase.get(phase)!;
      for (const f of [...owners.keys()].sort()) {
        const ids = owners.get(f)!;
        if (ids.length > 1) {
          findings.push(
            `C7 FENCE CLASH   ${phase}: '${f}' owned by ${[...ids].sort().join(", ")} in the same phase — ` +
              "derived fences overlap; narrow via edit-fence at claim time or serialize",
          );
        }
      }
    }
    for (const f of [...inflight.keys()].sort()) {
      const ids = inflight.get(f)!;
      if (ids.length > 1) {
        findings.push(
          `C10 LIVE CLASH  '${f}' is fenced by ${ids.length} IN_FLIGHT items (${[...ids].sort().join(", ")}) — ` +
            "two claimed agents are writing one file RIGHT NOW",
        );
      }
    }
  }

  // C8 — every law names an enforcer  |  C9 — that enforcer must RUN and the law must HOLD
  if (laws !== null) {
    stats.laws = laws.length;
    const ran = new Set<string>();
    for (const law of laws) {
      const tag = String(law.tag ?? "?");
      const wall = String(law.wall ?? "").trim();
      if (!wall) {
        stats.unlawed += 1;
        findings.push(
          `C8 UNLAWED       ${tag}: standing law with no mechanical enforcer — ` +
            "prose-only lawmaking (#924). The fix is building the checker.",
        );
        continue;
      }
      if (!existsSync(resolveWall(wall))) {
        findings.push(`C8 MISSING       ${tag}: named enforcer '${wall}' is not on disk`);
        continue;
      }
      stats.lawed += 1;
      // Several laws are enforced by checks INSIDE this gate (C5/C6/C7). Running this gate
      // from within itself would recurse, so those rows are satisfied by existence alone.
      if (basename(wall) === basename(import.meta.path) || !runPolarity || ran.has(wall)) continue;
      ran.add(wall);
      // A law enforcer's polarity is the INVERSE of an item wall's: it must PASS today
      // (the law is currently honored). Red means the law was BROKEN.
      const lres = spend(wall);
      if (lres === null) {
        findings.push(`C11 UNVERIFIED   ${tag}: budget exhausted before law enforcer '${wall}' could run`);
        continue;
      }
      const [rc, note] = lres;
      if (rc !== 0) {
        stats.law_violations += 1;
        findings.push(`C9 LAW VIOLATED  ${tag}: enforcer '${wall}' FAILS — the law is being broken right now. ${note}`);
      }
      if (runControls) {
        const sres = spend(wall, true);
        if (sres === null) {
          findings.push(`C11 UNVERIFIED   ${tag}: budget exhausted before its control could run`);
          continue;
        }
        const [sc, snote] = sres;
        if (sc !== 0) {
          stats.uncontrolled += 1;
          findings.push(
            `C6 NO CONTROL    ${tag}: law enforcer '${wall}' does not pass its own --selftest ` +
              `(exit ${sc}). ${snote}`,
          );
        }
      }
    }
  }

  return [findings, stats];
}

export function report(findings: string[], stats: Stats, strict: boolean): number {
  const sev = (f: string) => SEVERITY[code(f)] ?? BLOCKING;
  const blocking = findings.filter((f) => sev(f) === BLOCKING);
  const advisory = findings.filter((f) => sev(f) === ADVISORY);
  const out: string[] = [];

  if (blocking.length) {
    out.push("  ── BLOCKING ──");
    for (const f of blocking) out.push(`  ${f}`);
  }
  if (advisory.length) {
    out.push(`  ── ADVISORY (${advisory.length}) ── the standing worklist`);
    for (const f of advisory.slice(0, 6)) out.push(`  ${f}`);
    if (advisory.length > 6) out.push(`  … and ${advisory.length - 6} more`);
  }
  out.push("");
  out.push(
    `  items ${stats.total} | walled ${stats.walled} | retired-to-tests ${stats.retired} | UNWALLED ${stats.unwalled} | ` +
      `earned-green ${stats.green} | vacuous ${stats.vacuous} | false-green ${stats.false_green} | ` +
      `uncontrolled ${stats.uncontrolled}`,
  );
  out.push(`  laws ${stats.laws} | enforced ${stats.lawed} | UNLAWED ${stats.unlawed} | violated ${stats.law_violations}`);
  let rc = 0;
  if (blocking.length) {
    out.push(`\nCAMPAIGN WALL GATE: BLOCKED (${blocking.length} blocking, ${advisory.length} advisory)`);
    rc = 1;
  } else if (strict && advisory.length) {
    out.push(`\nCAMPAIGN WALL GATE: RED --strict (${advisory.length} advisory)`);
    out.push("  Red is the honest work inventory, not a failure.");
    rc = 1;
  } else {
    out.push(`\nCAMPAIGN WALL GATE: PASS (0 blocking, ${advisory.length} advisory outstanding)`);
  }
  process.stdout.write(out.join("\n") + "\n");
  return rc;
}

// ── selftest ─────────────────────────────────────────────────────────────────

const REAL = (rc: number) => `if (process.argv.includes("--selftest")) process.exit(0);\nprocess.exit(${rc});\n`;
const NOCTL = "process.exit(1);\n"; // the do-nothing wall the review caught

export function selftest(): number {
  const fails: string[] = [];
  const codes = (got: string[]) => new Set(got.map(code));
  const expect = (name: string, got: string[], want: string | null) => {
    const c = codes(got);
    if (want === null) {
      if (c.size) fails.push(`${name}: expected clean, got ${pyList([...c].sort())}`);
    } else if (!c.has(want)) {
      fails.push(`${name}: expected ${want}, got ${c.size ? pyList([...c].sort()) : "clean"}`);
    }
  };

  const td = mkdtempSync(join(tmpdir(), "campaign-wall-gate-"));
  try {
    const realRed = join(td, "red.ts");
    writeFileSync(realRed, REAL(1));
    const realGrn = join(td, "grn.ts");
    writeFileSync(realGrn, REAL(0));
    const noctl = join(td, "noctl.ts");
    writeFileSync(noctl, NOCTL);

    const I = (s: string): Items => new Map([["NF-01", ["W1", s]]]);
    const two = (p1: string, p2: string, s: string): Items =>
      new Map([["A-01", [p1, s]], ["A-02", [p2, s]]]);
    const twoRows = [{ id: "A-01", wall: "" }, { id: "A-02", wall: "" }];
    const shared = new Map([["A-01", ["x.kt"]], ["A-02", ["x.kt"]]]);
    const off = { runControls: false };

    expect("C1-unregistered", audit(I("todo"), [], off)[0], "C1");
    const dup = { id: "NF-01", wall: "" };
    expect("C1-ambiguous", audit(I("todo"), [dup, dup], off)[0], "C1");
    expect("C2-orphan", audit(new Map(), [{ id: "ZZ-99", wall: "" }], off)[0], "C2");
    expect("C3-missing", audit(I("todo"), [{ id: "NF-01", wall: "walls/__nope__.ts" }], off)[0], "C3");
    expect("C4-unwalled", audit(I("todo"), [{ id: "NF-01", wall: "" }], off)[0], "C4");
    // A retired wall: its home must exist and its proof must be written, else C3 — never C4, never a pass.
    expect("C3-retired-home-missing", audit(I("done"), [{ id: "NF-01", wall: "", retired_to: join(td, "__gone__Test.kt"), proof: "p" }], off)[0], "C3");
    expect("C3-retired-blind", audit(I("done"), [{ id: "NF-01", wall: "", retired_to: realGrn, proof: "" }], off)[0], "C3");
    const retiredOk = audit(I("done"), [{ id: "NF-01", wall: "", retired_to: realGrn, proof: "red and green on the same violation" }], off);
    expect("green-retired", retiredOk[0], null);
    if (retiredOk[1].retired !== 1 || retiredOk[1].unwalled !== 0) fails.push("retired: a retired row counts as retired, never as unwalled");
    // SEVERAL HOMES: a list is not satisfied by its first entry — every home must be on disk.
    expect("C3-retired-home-partial", audit(I("done"), [{ id: "NF-01", wall: "", retired_to: [realGrn, join(td, "__gone__Test.kt")], proof: "p" }], off)[0], "C3");
    const retiredTwo = audit(I("done"), [{ id: "NF-01", wall: "", retired_to: [realGrn, realRed], proof: "red and green on the same violation" }], off);
    expect("green-retired-multi", retiredTwo[0], null);
    if (retiredTwo[1].retired !== 1) fails.push("retired: a two-home row is ONE retired item, not two");
    expect("C5-vacuous", audit(I("todo"), [{ id: "NF-01", wall: realGrn }], off)[0], "C5");
    expect("C5-false-green", audit(I("verified"), [{ id: "NF-01", wall: realRed }], off)[0], "C5");

    // C6 — THE review finding: a do-nothing wall has honest polarity but no positive control
    const [f6] = audit(I("todo"), [{ id: "NF-01", wall: noctl }]);
    expect("C6-no-control", f6, "C6");
    if (codes(f6).has("C5")) {
      fails.push("C6-no-control: should NOT also raise C5 (its polarity is honest — that is the trap)");
    }

    expect("C7-phase-clash", audit(two("W1", "W1", "todo"), twoRows, { fences: shared, runControls: false })[0], "C7");
    const cross = audit(two("W1", "W2", "todo"), twoRows, { fences: shared, runControls: false })[0]
      .filter((x) => code(x) === "C7");
    if (cross.length) fails.push("C7-cross-phase: same file in DIFFERENT phases must not clash");

    const live = audit(two("W1", "W1", "in_flight"), twoRows, { fences: shared, runControls: false })[0];
    if (!codes(live).has("C10")) fails.push("C10-live-clash: two in_flight items sharing a file must raise C10");
    if (SEVERITY.C10 !== BLOCKING || SEVERITY.C7 !== ADVISORY) fails.push("severity: C10 must BLOCK and C7 must be ADVISORY");
    expect("C8-unlawed", audit(I("todo"), [{ id: "NF-01", wall: realRed }], { laws: [{ tag: "L1", wall: "" }], runControls: false })[0], "C8");
    // C9 — a law enforcer's polarity is INVERSE: a FAILING enforcer means the law is broken now
    expect("C9-law-violated", audit(I("todo"), [{ id: "NF-01", wall: realRed }], { laws: [{ tag: "L1", wall: realRed }], runControls: false })[0], "C9");
    const lawOk = audit(I("todo"), [{ id: "NF-01", wall: realRed }], { laws: [{ tag: "L1", wall: realGrn }] })[0]
      .filter((x) => code(x) === "C8" || code(x) === "C9");
    if (lawOk.length) fails.push(`law-honored: a PASSING enforcer must be clean, got ${pyList(lawOk)}`);
    const starved = audit(I("todo"), [{ id: "NF-01", wall: realRed }], { budgetS: 0.0 })[0];
    if (!codes(starved).has("C11")) fails.push("C11-budget: an exhausted budget must raise C11, never silently pass");
    if (SEVERITY.C11 !== BLOCKING) fails.push("C11 must BLOCK — an unverified wall is not a passing wall");
    expect("green-honest-wall", audit(I("todo"), [{ id: "NF-01", wall: realRed }])[0], null);
    expect("green-earned", audit(I("verified"), [{ id: "NF-01", wall: realGrn }])[0], null);
  } finally {
    rmSync(td, { recursive: true, force: true });
  }

  for (const c of ["C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11"]) {
    if (!(c in SEVERITY)) fails.push(`severity map missing ${c}`);
  }

  if (fails.length) {
    process.stdout.write("SELFTEST FAIL:\n");
    for (const x of fails) process.stdout.write("  " + x + "\n");
    return 1;
  }
  process.stdout.write(
    "SELFTEST OK — C1-C11 red cases fire; C6 catches the do-nothing wall WITHOUT a false C5; " +
      "cross-phase fence reuse stays clean; both correct-polarity cases pass; a retired wall needs " +
      "EVERY home it names on disk and its proof written\n",
  );
  return 0;
}

/** Python's repr of a list of str, for the selftest's failure lines. */
function pyList(xs: string[]): string {
  return "[" + xs.map((x) => (x.includes("'") && !x.includes('"') ? `"${x}"` : `'${x.replace(/'/g, "\\'")}'`)).join(", ") + "]";
}

// ── entry ────────────────────────────────────────────────────────────────────

const USAGE = "usage: campaign_wall_gate.ts [-h] [--selftest] [--strict] [--no-run]";
const HELP =
  `${USAGE}\n\ncampaign_wall_gate — an unwalled campaign item is structurally inexpressible.\n\n` +
  "options:\n  -h, --help  show this help message and exit\n  --selftest\n" +
  "  --strict    advisory findings also fail (the end state)\n" +
  "  --no-run    census only; exits 2, never 0\n";
const FLAGS = ["--selftest", "--strict", "--no-run", "--help"];

/** argparse's contract for this parser: the three flags, -h/--help, and an unambiguous prefix of a
 *  long flag (allow_abbrev); anything else is a usage error, exit 2. */
function parseArgs(argv: string[]): Set<string> {
  const got = new Set<string>();
  for (const a of argv) {
    if (a === "-h") {
      got.add("--help");
      continue;
    }
    const hits = a.startsWith("--") ? FLAGS.filter((f) => f === a || f.startsWith(a)) : [];
    const exact = hits.includes(a) ? [a] : hits;
    if (exact.length !== 1) {
      const why = exact.length > 1
        ? `ambiguous option: ${a} could match ${exact.join(", ")}`
        : `unrecognized arguments: ${a}`;
      process.stderr.write(`${USAGE}\ncampaign_wall_gate.ts: error: ${why}\n`);
      process.exit(2);
    }
    got.add(exact[0]);
  }
  return got;
}

function main(): number {
  const args = parseArgs(process.argv.slice(2));
  if (args.has("--help")) {
    process.stdout.write(HELP);
    return 0;
  }
  if (args.has("--selftest")) return selftest();
  const noRun = args.has("--no-run");

  process.stdout.write("── campaign wall gate — proxy-hardening ──\n");
  const items = ledgerItems();
  const [findings, stats] = audit(items, rowsOf(REGISTRY, "item"), {
    fences: itemFences(), laws: rowsOf(LAW_REGISTRY, "law"), runPolarity: !noRun, runControls: !noRun,
  });
  const rc = report(findings, stats, args.has("--strict"));
  if (noRun) {
    // Review finding #9: census mode must never be mistakable for a passing polarity run.
    process.stdout.write("  (--no-run: C5/C6 SKIPPED — census only. Exit 2 by construction.)\n");
    return 2;
  }
  return rc;
}

if (import.meta.main) {
  process.exit(main());
}
