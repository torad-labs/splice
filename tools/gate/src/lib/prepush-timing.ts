// The pre-push timing record: one JSON line per judged push in ~/.local/state/splice-gate/timings.jsonl, so where a push's
// minutes went is read off a file and not guessed from log timestamps. The numbers come from the report gradle's `--profile`
// writes into the pre-push tree (build/reports/profile/profile-<time>.html). Recording never judges: a report that is
// missing or unreadable leaves its fields empty, and a line that cannot be written is one note on stderr.
import { appendFileSync, mkdirSync, readdirSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

export const TIMINGS_FILE = join(homedir(), ".local", "state", "splice-gate", "timings.jsonl");
const TOP_TASKS = 10;

export interface TaskTime {
  readonly task: string;
  readonly s: number;
}

export interface Profile {
  /** The "Configuring Projects" time, or null when the report has none. */
  readonly configS: number | null;
  /** The slowest tasks, longest first. */
  readonly tasks: readonly TaskTime[];
}

/** One judged push. */
export interface PushTiming extends Profile {
  readonly at: string;
  readonly sha: string;
  readonly scope: string;
  /** Wall time of the gradle tier, or null when the scope ran no gradle. */
  readonly gradleS: number | null;
  /** Why the gate stopped gradle before it finished, or null. */
  readonly cancelled: string | null;
  /** "pass", or "fail:" and what failed. */
  readonly verdict: string;
}

/** Gradle's duration text as seconds: "0.038s", "3m26.70s", "1h2m3.4s". Null for text that is no duration. */
export function durationSeconds(text: string): number | null {
  const parts = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+(?:\.\d+)?)s)?$/.exec(text.trim());
  if (parts === null || (parts[1] === undefined && parts[2] === undefined && parts[3] === undefined)) return null;
  return Number(parts[1] ?? 0) * 3600 + Number(parts[2] ?? 0) * 60 + Number(parts[3] ?? 0);
}

const tenth = (seconds: number): number => Math.round(seconds * 10) / 10;

/** Configuration time and the slowest tasks of one profile report. */
export function parseProfile(html: string): Profile {
  const config = /<td>Configuring Projects<\/td>\s*<td class="numeric">([^<]*)<\/td>/.exec(html);
  const execution = html.indexOf("<h2>Task Execution</h2>");
  const tasks: TaskTime[] = [];
  if (execution >= 0) {
    for (const row of html.slice(execution).matchAll(/<td class="indentPath">([^<]+)<\/td>\s*<td class="numeric">([^<]*)<\/td>/g)) {
      const s = durationSeconds(row[2] ?? "");
      if (s !== null) tasks.push({ task: row[1] ?? "", s: tenth(s) });
    }
  }
  tasks.sort((a, b) => b.s - a.s);
  return { configS: config === null ? null : (durationSeconds(config[1] ?? "") ?? null), tasks: tasks.slice(0, TOP_TASKS) };
}

/** The newest profile report under [root] written at or after [sinceMs], parsed. Empty when there is none or it cannot be read. */
export function readProfile(root: string, sinceMs: number): Profile {
  const dir = join(root, "build", "reports", "profile");
  try {
    let newest: { path: string; at: number } | undefined;
    for (const name of readdirSync(dir)) {
      if (!/^profile-.*\.html$/.test(name)) continue;
      const path = join(dir, name);
      const at = statSync(path).mtimeMs;
      if (at >= sinceMs && (newest === undefined || at > newest.at)) newest = { path, at };
    }
    if (newest !== undefined) return parseProfile(readFileSync(newest.path, "utf8"));
  } catch {
    /* no report, or one that cannot be read: the line carries what it has */
  }
  return { configS: null, tasks: [] };
}

/** Appends one line to [file]. A short line is one atomic append, so two hooks never interleave. */
export function appendPushTiming(entry: PushTiming, file: string = TIMINGS_FILE): void {
  try {
    mkdirSync(dirname(file), { recursive: true });
    appendFileSync(file, `${JSON.stringify(entry)}\n`);
  } catch (error) {
    console.error(`pre-push: the timing line was not written to ${file}: ${error instanceof Error ? error.message : String(error)}`);
  }
}
