import { collect, execute, prepare } from "../ci/runner.ts";
import { pullRequestBase } from "../ci/pr-base.ts";
import { layout } from "../lib/repo.ts";
export const usage = "ci plan [base] | plan-pr <base-sha> <head-sha> | worker <id> | collect <receipts>    candidate-bound parallel CI";
export async function ci(argv: string[]): Promise<number> {
  try {
    const [verb, value, head] = argv;
    if (verb === "plan-pr" && value && head && argv.length === 3) return await prepare(pullRequestBase(layout().repoRoot, value, head));
    if (verb === "plan") return await prepare(value);
    if (verb === "worker" && value) return await execute(value);
    if (verb === "collect" && value) return await collect(value);
    throw new Error(usage);
  } catch (error) { console.error(`CI: FAIL — ${error instanceof Error ? error.message : String(error)}`); return 1; }
}
