// The runner the desktop-door cases share. A case is a name and a body; `run` runs every case in the order it was
// written and prints one line for each: PASS <name>, or FAIL <name>: <what happened instead>. A case that throws,
// or that takes too long, fails alone, and the cases after it still run. When the whole run is out of time, every
// case that has not reported fails, so each name is printed once whatever happens. splice is built from this
// checkout before the first case, as the checks build it, so a long build is no case's own time.

import { writeSync } from "node:fs";
import { jar } from "../../checks/server/lib/harness.ts";

/** What one case may take. */
const CASE_MS = 150_000;
/** What the build and all the cases of an item may take together: under the 900 s campaign gives a check. */
const BUDGET_MS = 780_000;
const started = Date.now();

type Body = () => void | Promise<void>;

const cases: { name: string; body: Body }[] = [];
const said = new Set<string>();

/** One case. Its name is its own, with no colon in it. */
export function check(name: string, body: Body): void {
  if (name.includes(":") || name !== name.trim() || !name) throw new Error(`not a case's name: "${name}"`);
  if (cases.some((c) => c.name === name)) throw new Error(`two cases are named "${name}"`);
  cases.push({ name, body });
}

/** A failure's reason on one line. */
function oneLine(e: unknown): string {
  const text = e instanceof Error ? e.message : String(e);
  return text.replace(/\s+/g, " ").trim().slice(0, 500) || "it failed without saying why";
}

function report(name: string, failure?: unknown): void {
  if (said.has(name)) return;
  said.add(name);
  writeSync(1, failure === undefined ? `PASS ${name}\n` : `FAIL ${name}: ${oneLine(failure)}\n`);
}

/** `body`, or a failure when it has not ended in `ms`. */
async function within(ms: number, body: Body): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const late = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`it had not ended after ${ms / 1000} s`)), ms);
  });
  try {
    await Promise.race([Promise.resolve().then(body), late]);
  } finally {
    clearTimeout(timer);
  }
}

/** Runs every case, then `cleanup`, and ends with 0: every case has printed its line by then. */
export async function run(cleanup: () => Promise<void>): Promise<never> {
  // What a case left running after it was given up on must not end the run.
  process.on("unhandledRejection", (e) => console.error(`after a case ended: ${oneLine(e)}`));
  process.on("uncaughtException", (e) => console.error(`after a case ended: ${oneLine(e)}`));
  try {
    jar();
  } catch (e) {
    for (const c of cases) report(c.name, e);
    process.exit(0);
  }
  const outOfTime = setTimeout(
    () => {
      for (const c of cases) report(c.name, `the build and the cases before it used the ${BUDGET_MS / 1000} s all of them have, so it did not run`);
      process.exit(0);
    },
    Math.max(0, BUDGET_MS - (Date.now() - started)),
  );
  for (const c of cases) {
    try {
      await within(CASE_MS, c.body);
      report(c.name);
    } catch (e) {
      report(c.name, e);
    }
  }
  clearTimeout(outOfTime);
  try {
    await within(60_000, cleanup);
  } catch (e) {
    console.error(`cleaning up: ${oneLine(e)}`);
  }
  process.exit(0);
}

/** `make`, run once and only when a case first asks: what several cases start from. When it fails, each case that
 *  asks fails with its reason. */
export function once<T>(make: () => Promise<T>): () => Promise<T> {
  let made: Promise<T> | undefined;
  return () => (made ??= make());
}

/** Fails the case with `what` unless `ok`. */
export function must(ok: boolean, what: string): asserts ok {
  if (!ok) throw new Error(what);
}

/** A value as a failure shows it. */
export function show(v: unknown): string {
  const text = typeof v === "string" ? JSON.stringify(v) : (JSON.stringify(v) ?? String(v));
  return text.length > 300 ? `${text.slice(0, 300)}...` : text;
}

/** Fails the case unless `got` is `want`, compared by what they hold. */
export function same(got: unknown, want: unknown, what: string): void {
  if (!Bun.deepEquals(got, want, true)) throw new Error(`${what} is ${show(got)}, not ${show(want)}`);
}
