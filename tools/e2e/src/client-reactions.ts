// The client reaction record: what one version of Claude Code does with each answer splice can give (failure-handling
// design, section 11, slice 0b). Slices 3 to 5 choose ClientWire's statuses from it, and its rows are the evidence
// ClientWire's KDoc cites. A record lives at fixtures/client-reactions/<version>.toml, one [[case]] row per case in
// CASES.
//
// A row tells the answer splice gave, what the client did, what it printed, whether it sent the request again, after
// how long and how many times, and whether the body it sent again was byte-identical. Its evidence says where that was
// seen: a time in splice's journal (in CT, with its log), a transcript entry's kind and time, or a byte offset in the
// client's shipped file, read and never run. A row nobody saw says unobserved and claims nothing, and later slices keep
// today's status for it.
//
// loadClientReactions refuses, listing every problem at once, a record that does not parse, misses a case, repeats or
// invents one, or holds a row with a field missing or of the wrong kind, or a row that names no evidence and does not
// say unobserved.
import { readFileSync } from "node:fs";
import { basename } from "node:path";

export const CASES = [
  // HTTP answers, before any 200 goes out
  "http-400-invalid-request",
  "http-400-should-retry-false",
  "http-401",
  "http-403",
  "http-404",
  "http-408",
  "http-413",
  "http-503-should-retry-false",
  "http-529-stream",
  "http-529-non-stream",
  "http-429-retry-after-30s",
  "http-429-reset-hours-away",
  "http-429-should-retry-false",
  "persistent-retry-limit",
  // in-band errors after a committed 200, before and after model content
  "inband-invalid-request-before-content",
  "inband-invalid-request-after-content",
  "inband-authentication-before-content",
  "inband-authentication-after-content",
  "inband-permission-before-content",
  "inband-permission-after-content",
  "inband-not-found-before-content",
  "inband-not-found-after-content",
  "inband-rate-limit-before-content",
  "inband-rate-limit-after-content",
  "inband-api-error-before-content",
  "inband-api-error-after-content",
  "inband-overloaded-before-content",
  "inband-overloaded-after-content",
  "inband-signed-notice",
  // a held 200 and the stop reasons
  "committed-200-two-hours",
  "stop-pause-turn",
  "stop-refusal",
  // what the client sends, and when
  "note-during-retry-sleep",
  "compaction-timer",
  "compaction-429",
  "compaction-400-prompt-too-long",
  "custom-header-reaches-splice",
  "base-url-path-kept",
  "non-stream-requests",
  "resend-body-identical",
] as const;

export type CaseId = (typeof CASES)[number];
export type Resent = "yes" | "no" | "mixed" | "n/a" | "unobserved";
export type Identical = "yes" | "no" | "mixed" | "n/a" | "unobserved";

export type Evidence =
  | { kind: "journal"; at: string; until?: string; log: string; note?: string }
  | { kind: "transcript"; at: string; until?: string; entry: string; note?: string }
  | { kind: "shipped-code"; at: string; note?: string };

export interface Reaction {
  id: CaseId;
  /** The answer splice gave: status or in-band type, and the headers that matter. */
  answer: string;
  /** What the client did with it. */
  did: string;
  /** What the client printed, with any account detail removed. */
  printed: string;
  resent: Resent;
  /** How long after the answer it sent the request again. */
  after?: string;
  /** How many times it sent it again. */
  times?: string;
  /** Whether the body it sent again was byte-identical to the one answered. */
  identical: Identical;
  unobserved: boolean;
  evidence: Evidence[];
}

export interface ClientReactionRecord {
  client: string;
  version: string;
  built: string;
  cases: Reaction[];
}

export class RecordRefused extends Error {}

const CT_TIME = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2}(\.\d{1,3})?)? CT$/;
const OFFSET = /^@\d+$/;
const VERSION = /^\d+\.\d+\.\d+$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const RESENT: readonly string[] = ["yes", "no", "mixed", "n/a", "unobserved"];
const IDENTICAL: readonly string[] = ["yes", "no", "mixed", "n/a", "unobserved"];
const ROW_FIELDS = new Set(["id", "answer", "did", "printed", "resent", "after", "times", "identical", "unobserved", "evidence"]);
const EVIDENCE_FIELDS: Record<Evidence["kind"], readonly string[]> = {
  journal: ["kind", "at", "until", "log", "note"],
  transcript: ["kind", "at", "until", "entry", "note"],
  "shipped-code": ["kind", "at", "note"],
};
const CLAIMS = ["printed", "resent", "identical"] as const;

type Table = Record<string, unknown>;

const isTable = (x: unknown): x is Table => x !== null && typeof x === "object" && !Array.isArray(x);
const isText = (x: unknown): x is string => typeof x === "string" && x.trim() !== "";
const isCase = (x: string): x is CaseId => (CASES as readonly string[]).includes(x);

/** Reads and checks the record at [path]; throws RecordRefused naming every problem. */
export function loadClientReactions(path: string): ClientReactionRecord {
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch (e) {
    throw new RecordRefused(`${basename(path)} cannot be read: ${(e as Error).message}`);
  }
  return parseClientReactions(text, basename(path));
}

/** Checks a record's text; [file] is its file name, which must be <version>.toml. */
export function parseClientReactions(text: string, file: string): ClientReactionRecord {
  let doc: unknown;
  try {
    doc = Bun.TOML.parse(text);
  } catch (e) {
    throw new RecordRefused(`${file} does not parse as TOML: ${(e as Error).message}`);
  }
  if (!isTable(doc)) throw new RecordRefused(`${file} does not parse as a TOML table`);

  const problems: string[] = [];
  const { client, version, built } = doc;
  if (!isText(client)) problems.push("client must name the client, like claude-code");
  if (!isText(version) || !VERSION.test(version)) problems.push("version must be the client's version, like 2.1.310");
  else if (file !== `${version}.toml`) problems.push(`the record says version ${version} but its file is ${file}`);
  if (!isText(built) || !DATE.test(built)) problems.push("built must be the date the record was built, like 2026-10-26");

  const rows = doc.case;
  const cases: Reaction[] = [];
  if (rows === undefined) problems.push("the record has no [[case]] rows");
  else if (!Array.isArray(rows) || !rows.every(isTable)) problems.push("case must be [[case]] rows");
  else {
    const seen = new Set<string>();
    rows.forEach((row, i) => {
      const reaction = checkRow(row, i, seen, problems);
      if (reaction) cases.push(reaction);
    });
    for (const id of CASES) if (!seen.has(id)) problems.push(`missing case ${id}`);
  }

  if (problems.length > 0) throw new RecordRefused(`${file} is refused:\n- ${problems.join("\n- ")}`);
  return { client: client as string, version: version as string, built: built as string, cases };
}

function checkRow(row: Table, index: number, seen: Set<string>, problems: string[]): Reaction | null {
  const id = row.id;
  if (!isText(id)) {
    problems.push(`row ${index + 1}: id is missing`);
    return null;
  }
  if (!isCase(id)) {
    problems.push(`unknown case ${id}`);
    return null;
  }
  if (seen.has(id)) {
    problems.push(`case ${id} appears twice`);
    return null;
  }
  seen.add(id);
  const before = problems.length;
  const say = (problem: string) => problems.push(`case ${id}: ${problem}`);

  for (const key of Object.keys(row)) if (!ROW_FIELDS.has(key)) say(`unknown field ${key}`);
  for (const field of ["answer", "did", "printed"] as const) {
    if (row[field] === undefined) say(`${field} is missing`);
    else if (!isText(row[field])) say(`${field} must be text`);
  }
  for (const [field, allowed] of [["resent", RESENT], ["identical", IDENTICAL]] as const) {
    const value = row[field];
    if (value === undefined) say(`${field} is missing`);
    else if (typeof value !== "string" || !allowed.includes(value)) say(`${field} must be one of ${allowed.join(", ")}`);
  }
  for (const field of ["after", "times"] as const) {
    if (row[field] !== undefined && !isText(row[field])) say(`${field} must be text`);
  }
  if (row.unobserved !== undefined && typeof row.unobserved !== "boolean") say("unobserved must be true or false");

  const listed = row.evidence ?? [];
  const evidence: Evidence[] = [];
  if (!Array.isArray(listed) || !listed.every(isTable)) say("evidence must be [[case.evidence]] rows");
  else if (row.unobserved === true) {
    if (listed.length > 0) say("says unobserved but names evidence");
    for (const field of CLAIMS) {
      if (typeof row[field] === "string" && row[field] !== "unobserved") say(`says unobserved but claims ${field}`);
    }
  } else if (listed.length === 0) say("names no evidence and does not say unobserved");
  else {
    listed.forEach((entry, j) => {
      const checked = checkEvidence(entry, (problem) => say(`evidence ${j + 1} ${problem}`));
      if (checked) evidence.push(checked);
    });
  }

  if (problems.length > before) return null;
  return {
    id,
    answer: row.answer as string,
    did: row.did as string,
    printed: row.printed as string,
    resent: row.resent as Resent,
    ...(row.after === undefined ? {} : { after: row.after as string }),
    ...(row.times === undefined ? {} : { times: row.times as string }),
    identical: row.identical as Identical,
    unobserved: row.unobserved === true,
    evidence,
  };
}

function checkEvidence(entry: Table, say: (problem: string) => void): Evidence | null {
  const kind = entry.kind;
  if (kind === undefined) {
    say("has no kind: journal, transcript or shipped-code");
    return null;
  }
  if (kind !== "journal" && kind !== "transcript" && kind !== "shipped-code") {
    say(`has kind ${String(kind)}, not journal, transcript or shipped-code`);
    return null;
  }
  let ok = true;
  const fail = (problem: string) => {
    say(`(${kind}) ${problem}`);
    ok = false;
  };
  for (const key of Object.keys(entry)) if (!EVIDENCE_FIELDS[kind].includes(key)) fail(`has unknown field ${key}`);
  if (entry.note !== undefined && !isText(entry.note)) fail("note must be text");

  if (kind === "shipped-code") {
    if (typeof entry.at !== "string" || !OFFSET.test(entry.at)) fail("needs at, a byte offset in the shipped file like @249441490");
    return ok ? { kind, at: entry.at as string, ...note(entry) } : null;
  }
  if (typeof entry.at !== "string" || !CT_TIME.test(entry.at)) fail("needs at, a time in CT like 2026-10-24 19:29:07 CT");
  if (entry.until !== undefined && (typeof entry.until !== "string" || !CT_TIME.test(entry.until))) {
    fail("until must be a time in CT like 2026-10-25 01:59 CT");
  }
  const until = entry.until === undefined ? {} : { until: entry.until as string };
  if (kind === "journal") {
    if (!isText(entry.log)) fail("needs log, the log or trace file the time is in");
    return ok ? { kind, at: entry.at as string, ...until, log: entry.log as string, ...note(entry) } : null;
  }
  if (!isText(entry.entry)) fail("needs entry, the kind of transcript entry");
  return ok ? { kind, at: entry.at as string, ...until, entry: entry.entry as string, ...note(entry) } : null;
}

const note = (entry: Table) => (entry.note === undefined ? {} : { note: entry.note as string });
