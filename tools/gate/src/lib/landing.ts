// THE INSTALL VERDICT: may this sha go onto the everyday daemon? The orchestrator's install script
// asks it before fetching the jar CI built, so the rule that decides what gets installed lives here,
// under version control and under test, and the script outside the repository only moves files.
//
// Every workflow on the sha is part of the verdict, org ones included. Until Sep 30 only `ci` was
// read, and secret-scan sat red for 18 hours under three installs (Marlin, Sep 30). The first guard
// that followed refused only a finished failure, so a sha whose secret-scan was still running would
// have passed; a workflow that has not finished has not said yes. Only `success` and `skipped` are
// yes: `cancelled` means the check never ran to its end, which is not a pass either.
//
// The LATEST run per workflow and event is that workflow's answer, so a rerun that went green
// replaces the red attempt before it, and a push run and a pull-request run are two answers.

export interface WorkflowRun {
  readonly id: number;
  readonly name: string;
  readonly event: string;
  readonly status: string;
  readonly conclusion: string | null;
  readonly head_sha: string;
}

export type Verdict =
  | { readonly kind: "green"; readonly ci: number }
  | { readonly kind: "pending"; readonly runs: readonly string[] }
  | { readonly kind: "red"; readonly runs: readonly string[] };

const YES: ReadonlySet<string> = new Set(["success", "skipped"]);

const label = (run: WorkflowRun): string => `${run.name} ${run.event} (run ${run.id}, ${run.conclusion ?? run.status})`;

/** The pure core: `runs` is the `workflow_runs` array of the Actions runs listing for `sha`. */
export function verdict(sha: string, runs: readonly WorkflowRun[]): Verdict {
  const latest = new Map<string, WorkflowRun>();
  for (const run of runs) {
    if (run.head_sha !== sha) continue;
    const key = `${run.name}|${run.event}`;
    const seen = latest.get(key);
    if (!seen || run.id > seen.id) latest.set(key, run);
  }
  const answers = [...latest.values()].sort((a, b) => a.id - b.id);
  const running = answers.filter((run) => run.status !== "completed");
  if (running.length > 0) return { kind: "pending", runs: running.map(label) };
  const red = answers.filter((run) => !YES.has(run.conclusion ?? ""));
  if (red.length > 0) return { kind: "red", runs: red.map(label) };
  // The jar that gets installed is the one the green `ci` run built, so no ci run is no install.
  const ci = answers.filter((run) => run.name === "ci" && run.conclusion === "success").at(-1);
  if (!ci) return { kind: "red", runs: [`no green ci run on ${sha}`] };
  return { kind: "green", ci: ci.id };
}
