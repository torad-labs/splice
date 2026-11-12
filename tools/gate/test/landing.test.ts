// Mutation-proves src/lib/landing.ts: each arm plants one state the runs on a sha can be in and
// asserts the install verdict it must get.
import { describe, expect, test } from "bun:test";
import { type WorkflowRun, verdict } from "../src/lib/landing.ts";

const SHA = "2036d895dea4e7bae80359881e3d570eff4bfbe1";
let next = 100;
const run = (name: string, conclusion: string | null, over: Partial<WorkflowRun> = {}): WorkflowRun => ({
  id: next++, name, event: "pull_request", status: conclusion === null ? "in_progress" : "completed",
  conclusion, head_sha: SHA, ...over,
});

describe("gate landing: the install verdict", () => {
  test("green: every workflow finished yes, and the ci run is named", () => {
    const ci = run("ci", "success");
    const v = verdict(SHA, [ci, run("secret-scan", "success"), run("automerge", "skipped")]);
    expect(v).toEqual({ kind: "green", ci: ci.id });
  });

  // THE GAP MARLIN FOUND: a scan still running has not said yes. The guard before this one read
  // only finished failures, so this sha would have been installed.
  test("pending: a workflow still running holds the install", () => {
    const v = verdict(SHA, [run("ci", "success"), run("secret-scan", null)]);
    expect(v.kind).toBe("pending");
    if (v.kind === "pending") expect(v.runs[0]).toContain("secret-scan");
  });

  test("pending: a queued workflow holds it too", () => {
    const v = verdict(SHA, [run("ci", "success"), run("codeql", null, { status: "queued" })]);
    expect(v.kind).toBe("pending");
  });

  // The Sep 29-30 state: ci green, an org scan red, and only ci was read.
  test("red: a failed org workflow refuses even with ci green", () => {
    const v = verdict(SHA, [run("ci", "success"), run("org-secret-scan", "failure")]);
    expect(v.kind).toBe("red");
    if (v.kind === "red") expect(v.runs[0]).toContain("org-secret-scan");
  });

  test("red: a cancelled workflow never ran to its end, so it is not a pass", () => {
    expect(verdict(SHA, [run("ci", "success"), run("coverage", "cancelled")]).kind).toBe("red");
  });

  test("red: timed_out and startup_failure refuse", () => {
    expect(verdict(SHA, [run("ci", "success"), run("e2e-docker", "timed_out")]).kind).toBe("red");
    expect(verdict(SHA, [run("ci", "success"), run("codeql", "startup_failure")]).kind).toBe("red");
  });

  test("red: no ci run at all means no jar to install", () => {
    const v = verdict(SHA, [run("secret-scan", "success")]);
    expect(v).toEqual({ kind: "red", runs: [`no green ci run on ${SHA}`] });
  });

  // The boring case: nothing reported for this sha is not a clean bill.
  test("red: no runs at all", () => {
    expect(verdict(SHA, []).kind).toBe("red");
  });

  test("latest wins: a green rerun replaces the red attempt before it", () => {
    const red = run("secret-scan", "failure");
    const ci = run("ci", "success");
    const rerun = run("secret-scan", "success");
    expect(verdict(SHA, [rerun, ci, red])).toEqual({ kind: "green", ci: ci.id });
  });

  test("latest wins: a red rerun replaces the green attempt before it", () => {
    const v = verdict(SHA, [run("ci", "success"), run("ci", "failure")]);
    expect(v.kind).toBe("red");
  });

  test("a push run and a pull-request run are two answers", () => {
    const v = verdict(SHA, [run("ci", "success"), run("e2e-docker", "success"), run("e2e-docker", "failure", { event: "push" })]);
    expect(v.kind).toBe("red");
  });

  test("runs on another sha are not this sha's answers", () => {
    const other = { head_sha: "0".repeat(40) };
    const ci = run("ci", "success");
    expect(verdict(SHA, [ci, run("secret-scan", "failure", other), run("codeql", null, other)])).toEqual({ kind: "green", ci: ci.id });
  });
});
