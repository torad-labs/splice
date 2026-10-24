// `gate landing <sha>` — the install verdict for one sha, read from the Actions runs listing on stdin
// (`gh api "repos/<repo>/actions/runs?head_sha=<sha>&per_page=100" | bun tools/gate landing <sha>`),
// so the verdict stays a pure function of what GitHub reported. The rule lives in src/lib/landing.ts.
import { type WorkflowRun, verdict } from "../lib/landing.ts";

export const usage = "landing <sha>                          install verdict: exit 0 green (prints the ci run id), 1 red, 3 still running";

export async function landing(argv: readonly string[]): Promise<number> {
  const sha = argv[0];
  if (argv.length !== 1 || !sha || !/^[0-9a-f]{40}$/.test(sha)) {
    console.error("usage: gh api \"repos/<repo>/actions/runs?head_sha=<sha>&per_page=100\" | bun tools/gate landing <40-character sha>");
    return 2;
  }
  let runs: WorkflowRun[];
  try {
    runs = (JSON.parse(await Bun.stdin.text()) as { workflow_runs: WorkflowRun[] }).workflow_runs;
  } catch {
    console.error("landing: stdin is not an Actions runs listing");
    return 2;
  }
  if (!Array.isArray(runs)) {
    console.error("landing: stdin has no workflow_runs array");
    return 2;
  }
  const result = verdict(sha, runs);
  switch (result.kind) {
    case "green":
      console.log(result.ci);
      return 0;
    case "pending":
      console.error(`still running on ${sha}: ${result.runs.join(", ")}`);
      return 3;
    case "red":
      console.error(`not green on ${sha}: ${result.runs.join(", ")}`);
      return 1;
  }
}
