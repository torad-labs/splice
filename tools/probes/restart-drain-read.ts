// NEW: Oct 12, 2026 — how a restart's drain read, the same way at every install (788223ea7: a draining head stops
// accepting at the start of the drain, so a request that arrives during it waits for the next daemon instead of
// being refused).
//
//   bun tools/probes/restart-drain-read.ts <restart time: ISO with offset, or epoch ms> [--before-s 120] [--after-s 300] [--json]
//
// It reads /api/perf/turns for every head over a window around the restart and counts, with no ids and no bodies:
//   before the drain  error:restarted turns that were already running when the drain began, and ended before the budget
//   during the drain  error:restarted turns that arrived after it began (refused at admission: the number 788223ea7 is to take to 0)
//   cut at the budget error:restarted turns that were running when it began and were cut when the 45 s drain ran out
//   empty_retries     the sum of the empty-answer retries the window's turns made
// The key is read from SPLICE_MGMT_KEY, else from the mgmt-key file in the state root, and is never printed.
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const RESTARTED = "error:restarted";
// why: HeadDeps' DEFAULT_STOP_DRAIN_MS; a turn still running when the drain began and cut near it was cut by the budget.
export const DRAIN_BUDGET_MS = 45_000;
// why: a row's ts is when it was written, a moment after the cut itself.
const BUDGET_SLACK_MS = 2_000;
const MAX_ROWS = 2_000;

export type Row = { ts: number; outcome: string; total?: number; empty_retries?: number };
export type TurnsAnswer = { heads: Array<{ key: string; count: number; returned: number; truncated: boolean; rows: Row[] }> };
export type Fetcher = (path: string) => Promise<unknown>;
export type Counts = { before: number; during: number; budget: number; emptyRetries: number; partial: boolean };

/** One head's rows classified against the moment the drain began. A row's arrival is its end less its duration. */
export function classify(rows: Row[], drainStart: number, partial: boolean): Counts {
  const counts: Counts = { before: 0, during: 0, budget: 0, emptyRetries: 0, partial };
  for (const row of rows) {
    counts.emptyRetries += row.empty_retries ?? 0;
    if (row.outcome !== RESTARTED) continue;
    const arrived = row.ts - (row.total ?? 0);
    if (arrived >= drainStart) counts.during += 1;
    else if (row.ts >= drainStart + DRAIN_BUDGET_MS - BUDGET_SLACK_MS) counts.budget += 1;
    else counts.before += 1;
  }
  return counts;
}

export async function readDrain(fetcher: Fetcher, drainStart: number, beforeMs: number, afterMs: number): Promise<Record<string, Counts>> {
  const listed = (await fetcher("/api/heads")) as { heads?: Array<{ key: string }> };
  const window = `since=${drainStart - beforeMs}&until=${drainStart + afterMs}&n=${MAX_ROWS}`;
  const out: Record<string, Counts> = {};
  for (const { key } of listed.heads ?? []) {
    const answer = (await fetcher(`/api/perf/turns?head=${encodeURIComponent(key)}&${window}`)) as TurnsAnswer;
    const head = answer.heads.find((h) => h.key === key);
    if (!head) continue;
    out[key] = classify(head.rows, drainStart, head.truncated);
  }
  return out;
}

const ct = (ms: number) => new Date(ms).toLocaleString("en-US", { timeZone: "America/Chicago", hour: "numeric", minute: "2-digit", second: "2-digit" }) + " CT";

export function report(counts: Record<string, Counts>, drainStart: number): string {
  const lines = [`restart at ${ct(drainStart)}; counts only`];
  const total: Counts = { before: 0, during: 0, budget: 0, emptyRetries: 0, partial: false };
  for (const [key, c] of Object.entries(counts)) {
    total.before += c.before; total.during += c.during; total.budget += c.budget; total.emptyRetries += c.emptyRetries;
    lines.push(`${key}: before the drain ${c.before}, during the drain ${c.during}, cut at the budget ${c.budget}, empty_retries ${c.emptyRetries}${c.partial ? " (window held more than 2,000 rows: counts are of the newest)" : ""}`);
  }
  lines.push(`all heads: before the drain ${total.before}, during the drain ${total.during}, cut at the budget ${total.budget}, empty_retries ${total.emptyRetries}`);
  return lines.join("\n");
}

function managementKey(): string {
  const fromEnv = process.env.SPLICE_MGMT_KEY?.trim();
  if (fromEnv) return fromEnv;
  // the state root the daemon reads its key from: SPLICE_STATE_DIR, else ~/.splice/state, else the pre-0.4 root
  const roots = [process.env.SPLICE_STATE_DIR?.trim(), join(homedir(), ".splice", "state"), join(homedir(), ".claude-codex", "state")];
  const file = roots.filter((root): root is string => Boolean(root)).map((root) => join(root, "mgmt-key")).find((path) => existsSync(path));
  if (!file) throw new Error("no management key: set SPLICE_MGMT_KEY or SPLICE_STATE_DIR");
  return readFileSync(file, "utf8").trim();
}

async function main(argv: string[]): Promise<number> {
  const when = argv[0];
  const flag = (name: string, fallback: number) => {
    const at = argv.indexOf(name);
    return at < 0 ? fallback : Number(argv[at + 1]);
  };
  const start = when === undefined ? NaN : /^\d+$/.test(when) ? Number(when) : Date.parse(when);
  if (Number.isNaN(start)) {
    console.error("usage: bun tools/probes/restart-drain-read.ts <restart time: ISO with offset, or epoch ms> [--before-s 120] [--after-s 300] [--json]");
    return 2;
  }
  const port = process.env.SPLICE_CONTROL_PORT?.trim() || "3096";
  const key = managementKey();
  const fetcher: Fetcher = async (path) => {
    const response = await fetch(`http://127.0.0.1:${port}${path}`, { headers: { Authorization: `Bearer ${key}` } });
    if (!response.ok) throw new Error(`${path} answered ${response.status}`);
    return response.json();
  };
  const counts = await readDrain(fetcher, start, flag("--before-s", 120) * 1000, flag("--after-s", 300) * 1000);
  console.log(argv.includes("--json") ? JSON.stringify({ restart: start, heads: counts }) : report(counts, start));
  return 0;
}

if (import.meta.main) process.exit(await main(process.argv.slice(2)));
