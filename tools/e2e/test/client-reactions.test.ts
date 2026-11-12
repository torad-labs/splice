// The client reaction record (failure-handling design, section 11, slice 0b) is what slices 3 to 5 choose statuses
// from, and its rows are the evidence ClientWire's KDoc cites. So loading it must refuse a record that leaves a case
// out, repeats or invents one, or holds a row that cannot be read, naming the case. And every row must name where its
// reaction was seen (a journal time in CT with its log, a transcript entry's kind and time, or a place in the client's
// shipped file) or say unobserved, so no status rests on a reaction nobody saw.
import { expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { CASES, loadClientReactions } from "../src/client-reactions.ts";

type Fields = Record<string, string | boolean>;
type Row = Record<string, unknown>;

const dir = mkdtempSync(join(tmpdir(), "client-reactions-"));
let written = 0;

const HEADER: Fields = { client: "claude-code", version: "9.9.9", built: "2026-10-26" };

function toml(rows: Row[], header: Fields = HEADER): string {
  const value = (v: unknown) => (typeof v === "string" ? JSON.stringify(v) : String(v));
  const lines = Object.entries(header).map(([k, v]) => `${k} = ${value(v)}`);
  for (const row of rows) {
    lines.push("", "[[case]]");
    for (const [k, v] of Object.entries(row)) if (k !== "evidence") lines.push(`${k} = ${value(v)}`);
    for (const e of (row.evidence as Fields[] | undefined) ?? []) {
      lines.push("[[case.evidence]]");
      for (const [k, v] of Object.entries(e)) lines.push(`${k} = ${value(v)}`);
    }
  }
  return `${lines.join("\n")}\n`;
}

/** What loading the file came to: "loaded", or the refusal's message. */
function outcome(path: string): string {
  try {
    loadClientReactions(path);
    return "loaded";
  } catch (e) {
    return (e as Error).message;
  }
}

/** Writes the text as a record file named for its version and loads it. */
function load(text: string): string {
  const sub = join(dir, String(written++));
  mkdirSync(sub);
  const path = join(sub, "9.9.9.toml");
  writeFileSync(path, text);
  return outcome(path);
}

const unobserved = (id: string): Row => ({
  id,
  answer: "HTTP 413 invalid_request_error",
  did: "No session met it.",
  printed: "unobserved",
  resent: "unobserved",
  identical: "unobserved",
  unobserved: true,
});

const observed = (id: string, evidence: Fields[]): Row => ({
  id,
  answer: "HTTP 529 overloaded_error",
  did: "Re-sent the same body after a growing backoff.",
  printed: "a retry line per attempt",
  resent: "yes",
  after: "0.6 s, growing to 300 s",
  times: "no give-up seen",
  identical: "yes",
  evidence,
});

const JOURNAL: Fields = { kind: "journal", at: "2026-10-24 19:29:07 CT", log: "daemon.log.1" };
const TRANSCRIPT: Fields = { kind: "transcript", entry: "system/api_error", at: "2026-10-24 19:29:37 CT" };
const SHIPPED: Fields = { kind: "shipped-code", at: "@249441490", note: "the retry predicate" };

const complete = (): Row[] => CASES.map((id) => unobserved(id));
const replace = (id: string, row: (r: Row) => Row): Row[] => complete().map((r) => (r.id === id ? row(r) : r));

test("a record with a missing case or a row that does not parse is refused", () => {
  // The whole record, every case present, loads, so each refusal below is about the one thing it breaks.
  expect(load(toml(complete()))).toBe("loaded");

  for (const id of CASES) expect(load(toml(complete().filter((r) => r.id !== id)))).toContain(`missing case ${id}`);
  const first = CASES[0];
  expect(load(toml([...complete(), unobserved(first)]))).toContain(`case ${first} appears twice`);
  expect(load(toml([...complete(), unobserved("http-999")]))).toContain("unknown case http-999");
  expect(load(`${toml(complete())}\n[[case]\nid = "x"\n`)).toContain("does not parse");
  expect(load(toml(complete(), { ...HEADER, version: "9.9.8" }))).toContain("version 9.9.8");
  expect(load(toml([]))).toContain("no [[case]] rows");

  // Every field a row must carry, left out or of the wrong kind, refuses that row by its id.
  for (const field of ["answer", "did", "printed", "resent", "identical"]) {
    for (const id of CASES) {
      const without = replace(id, (r) => Object.fromEntries(Object.entries(r).filter(([k]) => k !== field)));
      expect(load(toml(without))).toContain(`case ${id}: ${field}`);
      expect(load(toml(replace(id, (r) => ({ ...r, [field]: true }))))).toContain(`case ${id}: ${field}`);
    }
  }
  const id = CASES[5];
  expect(load(toml(replace(id, (r) => ({ ...r, resent: "maybe" }))))).toContain(`case ${id}: resent`);
  expect(load(toml(replace(id, (r) => ({ ...r, identical: "sometimes" }))))).toContain(`case ${id}: identical`);
  expect(load(toml(replace(id, (r) => ({ ...r, unobserved: "yes" }))))).toContain(`case ${id}: unobserved`);
  expect(load(toml(replace(id, (r) => ({ ...r, evidense: "typo" }))))).toContain(`case ${id}: unknown field evidense`);
});

test("every row names its evidence or says unobserved", () => {
  // The record this slice ships holds to it.
  expect(outcome(join(layout().repoRoot, "tools/e2e/fixtures/client-reactions/2.1.310.toml"))).toBe("loaded");

  // A row that tells what the client did without saying where it was seen is refused, whichever case it is.
  for (const id of CASES) {
    expect(load(toml(replace(id, () => observed(id, []))))).toContain(
      `case ${id}: names no evidence and does not say unobserved`,
    );
  }

  // Each kind of evidence is accepted when it names where the reaction was seen.
  const id = CASES[2];
  for (const e of [JOURNAL, TRANSCRIPT, SHIPPED, { ...JOURNAL, until: "2026-10-25 01:59 CT" }]) {
    expect(load(toml(replace(id, () => observed(id, [e]))))).toBe("loaded");
  }

  // And refused when it does not.
  const broken: Fields[] = [
    { kind: "journal", at: "2026-10-24 19:29:07 CT" },
    { kind: "journal", at: "2026-10-25T00:29:07Z", log: "daemon.log.1" },
    { kind: "journal", at: "Oct 24, 7:29 PM", log: "daemon.log.1" },
    { ...JOURNAL, until: "later" },
    { ...JOURNAL, lgo: "daemon.log" },
    { kind: "transcript", at: "2026-10-24 19:29:37 CT" },
    { kind: "transcript", entry: "system/api_error" },
    { kind: "shipped-code", at: "the retry code" },
    { kind: "shipped-code" },
    { kind: "hearsay", at: "2026-10-24 19:29:07 CT" },
    { at: "2026-10-24 19:29:07 CT", log: "daemon.log.1" },
  ];
  for (const e of broken) expect(load(toml(replace(id, () => observed(id, [e]))))).toContain(`case ${id}: evidence 1`);
  expect(load(toml(replace(id, () => observed(id, [JOURNAL, { kind: "journal" }]))))).toContain(`case ${id}: evidence 2`);

  // A row that says unobserved claims nothing: no evidence beside it, and no reaction it did not see.
  expect(load(toml(replace(id, (r) => ({ ...r, evidence: [JOURNAL] }))))).toContain(
    `case ${id}: says unobserved but names evidence`,
  );
  for (const field of ["printed", "resent", "identical"]) {
    expect(load(toml(replace(id, (r) => ({ ...r, [field]: "yes" }))))).toContain(
      `case ${id}: says unobserved but claims ${field}`,
    );
  }
});
