#!/usr/bin/env bun
/** WALL for CX-19 — every captured oracle scenario must have a replay verdict, and the corpus
 *  must still be the corpus that was captured.
 *
 *  CX-19 as written in the audit was "add a response-side golden per dialect", where WE author the
 *  golden — grading ourselves. The migration oracle reshapes it: the golden comes from the legacy Node
 *  reference implementation, recorded byte-exactly by `npm run oracle:capture` while that stack was
 *  green (104/104, `cd server && node --test`).
 *
 *  GAP (RED at authoring, 2026-07-26): 11 scenarios captured, 0 replayed. Per bun#34441 a captured-
 *  but-unreplayed scenario "wasn't counted and wasn't protected against regression" — the fixtures are
 *  inert until something grades against them.
 *
 *  GREEN requires ALL of:
 *    1. every [[scenario]] row has left "not-yet-replayed"
 *    2. no row is "kotlin-wrong" (open bugs by definition)
 *    3. every "sanctioned" row cites its authority AND pins the new expected bytes — this applies
 *       IDENTICALLY to a [[divergence]] row (a field-level difference cutting across every scenario,
 *       which cannot be a [[scenario]] row without corrupting the roster). Before 2026-08-07 the
 *       loader read only the `scenario` array, so a divergence block was a comment with TOML syntax:
 *       deleting it left the wall's output byte-identical (found by the 2026-07-30 review repair,
 *       rescued from stash-archive/cx19-round2-repair-2026-07-30). Same law, one checker
 *       (_grade_status).
 *    4. every "passing" row carries its replay proof (a passed row STAYS enrolled — deleting it
 *       would un-protect the scenario, the exact bun#34441 hole; "leaves the red list" means the
 *       status stops being a problem, never that the row leaves the file)
 *    5. the row set still matches the fixture corpus both ways
 *    6. INTEGRITY (review finding #6): every fixture still hashes to the sha256 recorded in
 *       _manifest.json at capture time. Recording a hash and never checking it is the NO-SAVED-TRUTH
 *       hole qgre's zero_ratchet exists to close — a hand-edited fixture would otherwise pass silently.
 *    7. SURVIVABILITY (review finding #7): if server/ is gone the oracle can never be re-captured, so
 *       the fixtures must be present and intact. This wall says so out loud rather than letting
 *       `oracle:capture` fail confusingly later.
 *
 *  EXIT 0 = replayed, classified, and intact.  EXIT 1 = work remains or the corpus drifted.
 *  --selftest = the POSITIVE CONTROL (gate check C6).
 *
 *  V4-154: converted to TypeScript (bun). Two things needed care beyond the mechanical port: Python
 *  str() is not String() — str(None) is the four characters None, str(True) is True — and that
 *  coercion reaches a printed message (a row with no status renders as unknown status None), so the
 *  port carries a pyStr; and set arithmetic on the enrolled/fixture difference had to sort the same
 *  way. Both were exercised by a corpus and by driving the mutants as a CLI.
 */
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, resolve } from "node:path";

/** Python repr() of a string, and of a list of strings. An f-string that interpolates a LIST
 *  renders THIS -- `['a', 'b']` -- not JSON, so a port that used JSON.stringify produced a
 *  DIFFERENT failure message than the original on every red path while agreeing on every green
 *  one. Caught by driving the mutants as a CLI rather than feeding detect() a fixed corpus. */
/** Python escapes a character when str.isprintable() is False: categories Cc Cf Cs Co Cn Zl Zp,
 *  and Zs except the plain space. Only \n \r \t get short spellings; the rest render \xNN below
 *  0x100, \uNNNN below 0x10000, \UNNNNNNNN above. */
const NON_PRINTABLE = /[\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}\p{Zl}\p{Zp}\p{Zs}]/u;
function pyReprStr(s: string): string {
  const useDouble = s.includes("'") && !s.includes('"');
  const q = useDouble ? '"' : "'";
  let body = "";
  for (const ch of s) {
    const cp = ch.codePointAt(0) as number;
    if (ch === "\\") body += "\\\\";
    else if (ch === "\n") body += "\\n";
    else if (ch === "\r") body += "\\r";
    else if (ch === "\t") body += "\\t";
    else if (ch === q) body += "\\" + q;
    else if (NON_PRINTABLE.test(ch) && ch !== " ") {
      body +=
        cp < 0x100 ? "\\x" + cp.toString(16).padStart(2, "0")
        : cp < 0x10000 ? "\\u" + cp.toString(16).padStart(4, "0")
        : "\\U" + cp.toString(16).padStart(8, "0");
    } else body += ch;
  }
  return q + body + q;
}
function pyRepr(items: string[]): string {
  return "[" + items.map(pyReprStr).join(", ") + "]";
}

const ROOT = resolve(import.meta.dir, "../../../..");
const ORACLE = resolve(ROOT, ".dev/campaigns/proxy-hardening/oracle");
const EXPECT = resolve(ORACLE, "expectations.toml");
const FIXTURES = resolve(ORACLE, "fixtures");
const SERVER = resolve(ROOT, "server");

const UNREPLAYED = "not-yet-replayed";
const WRONG = "kotlin-wrong";
const SANCTIONED = "sanctioned";
const PASSING = "passing";

type Row = Record<string, unknown>;

/** Python str(). None renders as the text None, booleans capitalised, numbers as-is. */
function pyStr(v: unknown): string {
  if (v === null || v === undefined) return "None";
  if (v === true) return "True";
  if (v === false) return "False";
  return String(v);
}

function sha256(data: string | Buffer): string {
  return createHash("sha256").update(data).digest("hex");
}

function isDir(p: string): boolean {
  return existsSync(p) && statSync(p).isDirectory();
}

/** The status contract, applied identically to a [[scenario]] and a [[divergence]] row.
 *
 *  ONE function on purpose (2026-07-30 review repair): a written law that no checker applies
 *  is the bun#34441 shape this wall exists to close. */
function gradeStatus(ident: string, row: Row, counts: Record<string, number>, problems: string[]): void {
  const status = row["status"];
  if (status === UNREPLAYED) {
    counts["unreplayed"] += 1;
    problems.push(`UNREPLAYED   ${ident}: captured but never graded against the Kotlin gateway`);
  } else if (status === WRONG) {
    counts["wrong"] += 1;
    problems.push(`KOTLIN WRONG ${ident}: gateway diverges and the reference was right`);
  } else if (status === SANCTIONED) {
    counts["sanctioned"] += 1;
    if (pyStr(row["authority"] ?? "").trim() === "") {
      problems.push(`UNCITED      ${ident}: sanctioned divergence with no G-number / PR / campaign item`);
    }
    if (pyStr(row["pinned_sha256"] ?? "").trim() === "") {
      problems.push(
        `UNPINNED     ${ident}: sanctioned divergence does not pin the new expected ` + "bytes — an unmonitored hole",
      );
    }
  } else if (status === PASSING) {
    counts["passing"] += 1;
    if (pyStr(row["proof"] ?? "").trim() === "") {
      problems.push(
        `UNPROVEN     ${ident}: passing with no replay proof — a pass nobody observed ` + "is not a pass",
      );
    }
  } else {
    problems.push(`BAD STATUS   ${ident}: unknown status '${pyStr(status)}'`);
  }
}

// The field the RUNNER reads to decide whether an observed value is the sanctioned one. A row that
// carries neither is a WILDCARD in replay.mjs: isSanctioned() matched the leaf and accepted any
// value at all, at any depth. Naming them here is what keeps the two checkers on the same field.
const RUNNER_PINS = ["pinned_value", "expected_without_session_header"];

/** A sanctioned DIVERGENCE must pin bytes the runner can actually enforce.
 *
 *  Before this (review 2026-08-12) the wall required `pinned_sha256` while replay.mjs read
 *  `pinned_value` / `expected_without_session_header` — two checkers, two different fields, both
 *  reporting green over a row that sanctioned every observed value. Requiring the sha to be the
 *  hash OF the runner-readable pin makes drift between them structurally impossible, and caught a
 *  live defect on day one: the prompt_cache_key row's `pinned_sha256` was 32 hex characters (the
 *  cache-key suffix, pasted) and had never been a hash of anything. */
function gradeDivergencePin(ident: string, row: Row, problems: string[]): void {
  if (row["status"] !== SANCTIONED) return;
  const pin = RUNNER_PINS.map((k) => (pyStr(row[k] ?? "").trim() !== "" ? pyStr(row[k]) : null)).find(
    (v) => v !== null,
  );
  if (pin === undefined || pin === null) {
    problems.push(
      `WILDCARD     ${ident}: sanctioned with no ${RUNNER_PINS.join(" / ")} — the ` +
        "runner would accept ANY observed value at this field",
    );
    return;
  }
  const declared = pyStr(row["pinned_sha256"] ?? "").trim();
  const actual = sha256(pin);
  if (declared !== "" && declared !== actual) {
    problems.push(
      `PIN MISMATCH ${ident}: pinned_sha256 is not the sha256 of the pinned value ` +
        `(declared ${declared.slice(0, 16)}…, actual ${actual.slice(0, 16)}…)`,
    );
  }
}

/** Pure detection. digests: fixture stem -> sha256 of its current bytes. */
export function detect(
  rows: Row[],
  onDisk: Set<string>,
  manifest: Record<string, Record<string, unknown>>,
  digests: Record<string, string>,
  serverPresent: boolean,
  divergences: Row[] | null = null,
): [string[], Record<string, number>] {
  const problems: string[] = [];
  const named = new Set(rows.map((r) => pyStr(r["name"] ?? "")));

  for (const missing of [...onDisk].filter((x) => !named.has(x)).sort()) {
    problems.push(
      `UNENROLLED   ${missing}: fixture on disk with no expectations row — ` +
        "not counted, not protected (bun#34441)",
    );
  }
  for (const phantom of [...named].filter((x) => !onDisk.has(x)).sort()) {
    problems.push(`NO FIXTURE   ${phantom}: expectations row names a fixture that is not on disk`);
  }

  // integrity — the recorded sha256 must still hold
  for (const stem of Object.keys(digests).sort()) {
    const sha = digests[stem];
    const rec = (manifest[stem] ?? {})["sha256"];
    if (rec === undefined || rec === null) {
      problems.push(
        `UNRECORDED   ${stem}: fixture has no sha256 in _manifest.json — ` +
          "cannot prove it is the bytes that were captured",
      );
    } else if (rec !== sha) {
      problems.push(
        `CORRUPT      ${stem}: fixture bytes differ from the sha256 recorded at ` +
          "capture time. Either it was hand-edited (revert it) or it was legitimately " +
          "re-captured (re-run oracle:capture so the manifest agrees).",
      );
    }
  }

  if (!serverPresent && onDisk.size === 0) {
    problems.push(
      "ORACLE LOST  server/ is gone AND no fixtures remain — the migration oracle is " +
        "unrecoverable. It cannot be re-captured.",
    );
  }

  const counts: Record<string, number> = {
    rows: rows.length,
    unreplayed: 0,
    wrong: 0,
    sanctioned: 0,
    passing: 0,
    divergences: (divergences ?? []).length,
  };
  for (const r of rows) gradeStatus(pyStr(r["name"]), r, counts, problems);
  for (const d of divergences ?? []) {
    const ident = `divergence:${pyStr(d["field"] ?? "<unnamed>")}`;
    if (pyStr(d["field"] ?? "").trim() === "") {
      problems.push(`NO FIELD     ${ident}: a divergence row without a field cannot be tracked or retired`);
    }
    gradeStatus(ident, d, counts, problems);
    gradeDivergencePin(ident, d, problems);
  }
  return [problems, counts];
}

export function load(): [Row[], Set<string>, Record<string, Record<string, unknown>>, Record<string, string>, Row[]] {
  const doc = existsSync(EXPECT)
    ? (Bun.TOML.parse(readFileSync(EXPECT, "utf8")) as Record<string, unknown>)
    : {};
  const rows = (doc["scenario"] as Row[]) ?? [];
  const divergences = (doc["divergence"] as Row[]) ?? [];
  const onDisk = new Set<string>();
  const digests: Record<string, string> = {};
  let manifest: Record<string, Record<string, unknown>> = {};
  if (isDir(FIXTURES)) {
    const mf = resolve(FIXTURES, "_manifest.json");
    if (existsSync(mf)) {
      manifest =
        ((JSON.parse(readFileSync(mf, "utf8")) as Record<string, unknown>)["scenarios"] as Record<
          string,
          Record<string, unknown>
        >) ?? {};
    }
    const files = readdirSync(FIXTURES)
      .filter((f) => f.endsWith(".json"))
      .sort();
    for (const f of files) {
      const stem = basename(f, ".json");
      if (stem.startsWith("_")) continue;
      onDisk.add(stem);
      digests[stem] = sha256(readFileSync(resolve(FIXTURES, f)));
    }
  }
  return [rows, onDisk, manifest, digests, divergences];
}

function selftest(): number {
  const fails: string[] = [];
  const shaA = sha256("a");
  const shaB = sha256("b");
  const man: Record<string, Record<string, unknown>> = { s1: { sha256: shaA } };

  const kase = (
    name: string,
    rows: Row[],
    disk: Set<string>,
    manifest: Record<string, Record<string, unknown>>,
    digests: Record<string, string>,
    server: boolean,
    wantRed: boolean,
    needle: string | null = null,
  ): void => {
    const [got] = detect(rows, disk, manifest, digests, server);
    if (wantRed && got.length === 0) fails.push(`${name}: must be RED`);
    if (!wantRed && got.length > 0) fails.push(`${name}: must be GREEN, got ${pyRepr(got)}`);
    if (needle !== null && !got.some((g) => g.includes(needle))) {
      fails.push(`${name}: expected a '${needle}' finding, got ${pyRepr(got)}`);
    }
  };

  kase("unreplayed", [{ name: "s1", status: UNREPLAYED }], new Set(["s1"]), man, { s1: shaA }, true, true, "UNREPLAYED");
  kase("kotlin-wrong", [{ name: "s1", status: WRONG }], new Set(["s1"]), man, { s1: shaA }, true, true, "KOTLIN WRONG");
  kase("sanctioned uncited", [{ name: "s1", status: SANCTIONED, pinned_sha256: "x" }],
    new Set(["s1"]), man, { s1: shaA }, true, true, "UNCITED");
  kase("sanctioned unpinned", [{ name: "s1", status: SANCTIONED, authority: "G13" }],
    new Set(["s1"]), man, { s1: shaA }, true, true, "UNPINNED");
  kase("unenrolled fixture", [], new Set(["s1"]), man, { s1: shaA }, true, true, "UNENROLLED");
  kase("phantom row", [{ name: "s9", status: SANCTIONED, authority: "G", pinned_sha256: "p" }],
    new Set(), {}, {}, true, true, "NO FIXTURE");
  // passing must carry its receipt — a pass nobody observed is not a pass
  kase("passing unproven", [{ name: "s1", status: PASSING }], new Set(["s1"]), man, { s1: shaA }, true, true, "UNPROVEN");
  // THE integrity case (review finding #6) — a hand-edited fixture must not pass silently
  kase("corrupt fixture", [{ name: "s1", status: SANCTIONED, authority: "G13", pinned_sha256: "p" }],
    new Set(["s1"]), man, { s1: shaB }, true, true, "CORRUPT");
  kase("unrecorded fixture", [{ name: "s1", status: SANCTIONED, authority: "G", pinned_sha256: "p" }],
    new Set(["s1"]), {}, { s1: shaA }, true, true, "UNRECORDED");
  kase("oracle lost", [], new Set(), {}, {}, false, true, "ORACLE LOST");
  kase("bad status", [{ name: "s1", status: "whatever" }], new Set(["s1"]), man, { s1: shaA }, true, true, "BAD STATUS");
  // [[divergence]] rows ride the SAME checker — uncited/unpinned/field-less must be red
  const divCases: [string, Row, string][] = [
    ["divergence uncited", { field: "f.x", status: SANCTIONED, pinned_sha256: "p" }, "UNCITED"],
    ["divergence unpinned", { field: "f.x", status: SANCTIONED, authority: "PR#58" }, "UNPINNED"],
    ["divergence fieldless", { status: SANCTIONED, authority: "PR#58", pinned_sha256: "p" }, "NO FIELD"],
    // 2026-08-12: a sanction the RUNNER cannot enforce. This shape satisfied every wall check
    // while replay.mjs accepted any value at all at that leaf — two checkers, two fields, both
    // green over an unmonitored hole.
    ["divergence wildcard", { field: "f.x", status: SANCTIONED, authority: "PR#58", pinned_sha256: "p" }, "WILDCARD"],
    // ...and the sha must actually BE the hash of that pin (the live prompt_cache_key row
    // carried a 32-char cache-key suffix in the sha256 field and nothing ever noticed).
    [
      "divergence pin mismatch",
      { field: "f.x", status: SANCTIONED, authority: "PR#58", pinned_value: "v", pinned_sha256: "deadbeef" },
      "PIN MISMATCH",
    ],
  ];
  for (const [nm, div, needle] of divCases) {
    const [got] = detect([{ name: "s1", status: PASSING, proof: "replay" }], new Set(["s1"]), man, { s1: shaA },
      true, [div]);
    if (!got.some((g) => g.includes(needle))) {
      fails.push(`${nm}: expected a '${needle}' finding, got ${pyRepr(got)}`);
    }
  }
  // the green shapes: sanctioned (cited+pinned) and passing (proven), divergences held to the same law
  kase("green", [{ name: "s1", status: SANCTIONED, authority: "G13", pinned_sha256: "abc" }],
    new Set(["s1"]), man, { s1: shaA }, true, false);
  const [got] = detect(
    [{ name: "s1", status: PASSING, proof: "replay 2026-08-07" }], new Set(["s1"]), man, { s1: shaA }, true,
    [{
      field: "f.x", status: SANCTIONED, authority: "PR#58",
      pinned_value: "v", pinned_sha256: "4c94485e0c21ae6c41ce1dfe7b6bfaceea5ab68e40a2476f50208e526f506080",
    }],
  );
  if (got.length > 0) {
    fails.push(`green passing+divergence: must be GREEN, got ${pyRepr(got)}`);
  }

  if (fails.length > 0) {
    process.stdout.write("CX-19 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "CX-19 SELFTEST OK — red on unreplayed/kotlin-wrong/uncited/unpinned/unproven/unenrolled/" +
      "phantom/CORRUPT/unrecorded/oracle-lost/bad-status, for scenario AND divergence rows; green " +
      "only when every row is classified, cited/proven, pinned and byte-intact\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  if (!existsSync(EXPECT)) {
    process.stdout.write(
      `CX-19 WALL RED: ${EXPECT.slice(ROOT.length + 1)} missing — the oracle ledger is gone\n`,
    );
    return 1;
  }
  const [rows, onDisk, manifest, digests, divergences] = load();
  const [problems, counts] = detect(rows, onDisk, manifest, digests, isDir(SERVER), divergences);
  process.stdout.write(
    `CX-19 oracle: ${counts["rows"]} enrolled | ${onDisk.size} fixtures | ` +
      `unreplayed ${counts["unreplayed"]} | kotlin-wrong ${counts["wrong"]} | ` +
      `sanctioned ${counts["sanctioned"]} | passing ${counts["passing"]} | ` +
      `divergences ${counts["divergences"]} | ` +
      `server/ ${isDir(SERVER) ? "present" : "GONE (re-capture impossible)"}\n`,
  );
  if (problems.length > 0) {
    process.stdout.write("CX-19 WALL RED — the captured oracle is not yet grading anything:\n");
    for (const p of problems.slice(0, 14)) process.stdout.write(`  · ${p}\n`);
    if (problems.length > 14) {
      process.stdout.write(`  · … and ${problems.length - 14} more\n`);
    }
    return 1;
  }
  process.stdout.write("CX-19 WALL GREEN: every captured scenario replayed, classified, and byte-intact.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
