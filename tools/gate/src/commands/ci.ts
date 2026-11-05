import { collect, execute, prepare } from "../ci/runner.ts";
export const usage = "ci plan [base] | worker <id> | collect <receipts>    candidate-bound parallel CI";
export async function ci(argv: string[]): Promise<number> {
  try {
    const [verb, value] = argv;
    if (verb === "plan") return await prepare(value);
    if (verb === "worker" && value) return await execute(value);
    if (verb === "collect" && value) return await collect(value);
    throw new Error(usage);
  } catch (error) { console.error(`CI: FAIL — ${error instanceof Error ? error.message : String(error)}`); return 1; }
}
