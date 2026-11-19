// Shared by each case script: run from the candidate checkout; prints PASS/FAIL per case name.
import { join } from "node:path";
export const ROOT = process.cwd();
export const lib = (p: string) => join(ROOT, ".campaign/checks", p);
export async function runAll(names: string[], body: (out: (n: string, ok: boolean, why: string) => void) => Promise<void>) {
  const done = new Set<string>();
  const out = (n: string, ok: boolean, why: string) => {
    if (done.has(n)) return;
    done.add(n);
    console.log(ok ? `PASS ${n}` : `FAIL ${n}: ${why.replace(/\n/g, " | ").slice(0, 1200)}`);
  };
  try {
    await body(out);
  } catch (e) {
    for (const n of names) out(n, false, `could not run: ${String((e as Error)?.stack ?? e)}`);
  }
  for (const n of names) out(n, false, "case did not report");
  process.exit(0);
}
