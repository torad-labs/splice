// Laws 25 and 27, mechanically, over the WHOLE ledger. (.dev/web-console/law-check.mjs until PR 5;
// `gate ledger laws` is its entry.)
//
// WHY. code-reviewer's M1-33 asked the only question worth asking about a gate: name the properties
// this campaign cares about that NO leg observes. Three came back and two were fixed. The third was
// this one — no leg opened the ledger, so laws 25 and 27 were enforced by the orchestrator
// remembering them, which is the same class of guarantee as a check that cannot fail. It was
// demonstrated the same night by four verify lines shipping with two broken legs each.
//
// LAW 25: a row whose fence can touch CSS carries a build leg, and the leg is `npx vite build`
//   specifically — not `npm run build -w console`, whose script is `tsc --noEmit && vite build`, so
//   a peer's in-flight type error would mask a CSS defect. An orphaned declaration reached the tree
//   and broke the build for every seat because ZERO done rows carried one.
//
// LAW 27: a verify leg asserts what must be PRESENT. An absence is what every broken instrument
//   produces, so a leg that passes on absence cannot tell a fixed defect from a dead tool. The three
//   shapes have all shipped and each one is caught by name:
//     if <cmd> | grep ...; then exit 1; fi   M1-08/09/10 — GREEN when world.test.ts is deleted,
//                                            because a missing file makes grep match nothing
//     ! grep ...                             a bare negation: the pass is the empty output
//     test -z "$(grep ...)"                  emptiness as the pass, spelled out
//
// THE DENOMINATOR IS THE LEDGER FILE, NOT THE ROWS THAT LOOK INTERESTING. Every row gets a
// disposition and a row in none of them fails BY NAME; a run that parses zero rows is an ERROR, not
// a clean report, because a law check that reads an empty ledger and reports clean is the joke
// version of itself (the command enforces both).
//
// READ THROUGH THE CLI, not by parsing TOML here: `manifest.ts list --plain` enumerates and
// `manifest.ts get <id> --raw` returns files/status/verify, which is the ledger's own reader. (The
// ledger-only-via-CLI law is about writes and the flock — a reader cannot corrupt it — but using
// the CLI costs nothing and keeps one reader.)
import { execFileSync } from "node:child_process";
import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";

export interface Row {
  readonly id: string;
  readonly status: string | null;
  readonly verify: string | null;
  readonly files: readonly string[];
  readonly unreadable?: boolean;
}

/** The ledger CLI's machine shapes. Its human `list` leads each line with a status glyph and its
 *  human `get` is a rendered view, so without --plain/--raw the parser below reads ZERO rows and the
 *  check stops at its zero-rows guard (DID NOT RUN, exit 2; measured). */
const MACHINE: Readonly<Record<string, string>> = { list: "--plain", get: "--raw" };
export const LEDGER_CLI = ".dev/campaigns/manifest.ts";

function manifest(root: string, ledger: string, args: readonly string[]): string {
  return execFileSync("bun", [LEDGER_CLI, ledger, ...args, MACHINE[args[0]!]!], { cwd: root, encoding: "utf8" });
}

/** Every row, with its fence, status and verify line, read through the CLI. */
export function readLedger(root: string, ledger: string): Row[] {
  const listed = manifest(root, ledger, ["list"]);
  const ids = listed
    .split("\n")
    .map((line) => line.trim().split(/\s+/)[0] ?? "")
    .filter((id) => /^[A-Z]+\d+-\d+$/.test(id));
  return ids.map((id) => {
    // THE LEDGER IS LIVE and the orchestrator writes to it while legs read it: a `get` hit a
    // half-rewritten item during this check's own first run and killed it with "item 'M1-07' not
    // found". One retry, and a row still unreadable is NAMED as unreadable rather than dropped — a
    // row quietly missing from the denominator is the failure this file is about.
    let text: string | null = null;
    for (let attempt = 0; attempt < 2 && text === null; attempt += 1) {
      try {
        text = manifest(root, ledger, ["get", id]);
      } catch {
        if (attempt === 0) Bun.sleepSync(1000);
      }
    }
    if (text === null) return { id, status: null, verify: null, files: [], unreadable: true };
    const files = /^files = \[(.*)\]$/m.exec(text)?.[1] ?? "";
    const one = (key: string): string | null => new RegExp(`^${key} = "(.*)"$`, "m").exec(text!)?.[1] ?? null;
    return { id, status: one("status"), verify: one("verify"), files: [...files.matchAll(/"([^"]+)"/g)].map((m) => m[1]!) };
  });
}

// --------------------------------------------------------------------- law 25

/**
 * Does a path on disk hold a .css file? THREE answers and not two: `yes`, `no`, `unknown`.
 *
 * This once returned a BOOLEAN and every `catch` arm returned `false`, so a tree the process could
 * not read answered "holds no CSS" in the same word a readable empty one does; `check()` then
 * computed `css.length > 0 && !buildLeg`, FALSE when css is empty — so on an unreadable tree EVERY
 * law-25 finding disappeared and the run printed `law 25: 0 violation(s)` and exited 0. The summary
 * was over a set that had emptied itself for a reason that has nothing to do with the property.
 *
 * ENOENT stays a real `no`. A fence may legitimately name a path its own row is about to create,
 * and "nothing is there" is an ANSWER — the tree said so. Anything else (EACCES, ELOOP, EIO,
 * ENOTDIR) is `unknown`, and an unknown fence is a finding, because a check must be able to say it
 * did not run (law 23). A .css found before the unreadable part decides the question: `yes` wins.
 */
function holdsCss(prefix: string): "yes" | "no" | "unknown" {
  const root = prefix.endsWith("/**") ? prefix.slice(0, -3) : prefix;
  let unknown = false;
  const note = (error: unknown): void => {
    if ((error as { code?: string }).code !== "ENOENT") unknown = true;
  };
  const walk = (dir: string, depth: number): boolean => {
    if (depth > 6) return false;
    let entries: string[];
    try {
      entries = readdirSync(dir);
    } catch (error) {
      note(error);
      return false;
    }
    for (const entry of entries) {
      const path = join(dir, entry);
      if (entry.endsWith(".css")) return true;
      try {
        if (statSync(path).isDirectory() && walk(path, depth + 1)) return true;
      } catch (error) {
        note(error);
      }
    }
    return false;
  };
  let found = false;
  try {
    found = statSync(root).isDirectory() ? walk(root, 0) : root.endsWith(".css");
  } catch (error) {
    note(error);
  }
  return found ? "yes" : unknown ? "unknown" : "no";
}

/** The fence entries that can touch CSS: an exact .css file, or a directory/glob holding one. */
export function cssFences(files: readonly string[]): string[] {
  return files.filter((entry) => entry.endsWith(".css") || holdsCss(entry) === "yes");
}

/** The fence entries whose CSS question the tree REFUSED to answer. Not `no`, and not silence. */
export function undecidableFences(files: readonly string[]): string[] {
  return files.filter((entry) => !entry.endsWith(".css") && holdsCss(entry) === "unknown");
}

// --------------------------------------------------------------------- law 27

/** The three absence-asserting shapes, as the regexes that match the real shipped lines. */
export const ABSENCE_SHAPES: readonly { readonly name: string; readonly re: RegExp }[] = [
  {
    name: "if-pipe-grep-then-exit",
    // `if npx vitest run tests/world.test.ts 2>&1 | grep -E -q 'src/pages/'; then exit 1; fi`
    // (M1-08/09/10). A deleted test file makes grep match nothing, so the leg passes.
    re: /\bif\s+[^;]*\bgrep\b[^;]*;\s*then\s+exit\s+[1-9]/,
  },
  { name: "bang-grep", re: /(?:^|[\s(])!\s*[^|;&]*\bgrep\b/ },
  { name: "empty-is-the-pass", re: /(?:\btest\s+|\[\s*)-[zn]\s+["']?\$\([^)]*\bgrep\b/ },
];

/** The law-27 shapes a verify line carries, by name. */
export function absenceShapes(verify: string | null): string[] {
  if (verify === null) return ["no verify line"];
  return ABSENCE_SHAPES.filter((shape) => shape.re.test(verify)).map((shape) => shape.name);
}

// ------------------------------------------------------------------- the check

export interface Finding {
  readonly id: string;
  readonly law: 0 | 25 | 27;
  readonly detail: string;
}
export type Disposition = "ok" | "law-25" | "law-27" | "both" | "unreadable" | "undecidable";
export interface LawReport {
  readonly findings: Finding[];
  readonly dispositions: Record<Disposition, number>;
  readonly rows: number;
}

export const BUILD_LEG = /npx\s+vite\s+build/;

/**
 * Both laws over a set of rows. Every row gets a disposition: `ok`, or the violations it carries.
 * A leg can carry both laws at once and is reported for both.
 */
export function check(
  rows: readonly Row[],
  { cssFencesOf = cssFences, undecidableOf = undecidableFences }: { cssFencesOf?: typeof cssFences; undecidableOf?: typeof undecidableFences } = {},
): LawReport {
  const findings: Finding[] = [];
  const dispositions: Record<Disposition, number> = { ok: 0, "law-25": 0, "law-27": 0, both: 0, unreadable: 0, undecidable: 0 };
  for (const row of rows) {
    if (row.unreadable === true) {
      findings.push({ id: row.id, law: 0, detail: "the ledger listed this row and get refused it (the ledger was being written while this leg read it) — it is unreadable, not absent" });
      dispositions.unreadable += 1;
      continue;
    }
    const css = cssFencesOf(row.files);
    const undecided = undecidableOf(row.files);
    const buildLeg = row.verify !== null && BUILD_LEG.test(row.verify);
    const missingBuild = css.length > 0 && !buildLeg;
    const shapes = absenceShapes(row.verify);
    const absent = shapes.length > 0 && shapes[0] !== "no verify line";
    // ONE disposition per row and they sum to the denominator (the command asserts the identity).
    // `undecidable` takes the seat because law 25's answer for this row is not `no`, it is absent:
    // a fence the tree refused to read cannot be graded green.
    if (undecided.length > 0) dispositions.undecidable += 1;
    else if (missingBuild && absent) dispositions.both += 1;
    else if (missingBuild) dispositions["law-25"] += 1;
    else if (absent) dispositions["law-27"] += 1;
    else dispositions.ok += 1;
    if (undecided.length > 0) {
      findings.push({ id: row.id, law: 25, detail: `DID NOT RUN: ${undecided.join(", ")} could not be read (not ENOENT), so whether this fence can touch CSS is unknown and law 25 was not judged` });
    }
    if (missingBuild) {
      findings.push({ id: row.id, law: 25, detail: `fence can touch CSS (${css.slice(0, 3).join(", ")}${css.length > 3 ? ", …" : ""}) and the verify carries no \`npx vite build\`` });
    }
    if (absent) {
      findings.push({ id: row.id, law: 27, detail: `${shapes.join(" + ")} — the pass is an absence: ${shapes.map((s) => ABSENCE_SHAPES.find((x) => x.name === s)!.re).join(" ")}` });
    }
  }
  return { findings, dispositions, rows: rows.length };
}
