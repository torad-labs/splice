import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { capturedCommand } from "../current-command";
import { sha256 } from "../qualification";
import { nativeReviewCompletion } from "./review-native-events";
import type { CliRun, CliScenario } from "./types";

export const reviewProbeFile = "fixture.txt";
export const reviewExecutionMarker = "REVIEW_INHERITED_CODEMODE_PROVIDER";
const before = "first\n", after = "second\n";

/** The source's review child inherits its provider; the model remains a declared local fixture. */
export function reviewProviderScenario(): CliScenario {
  return { schema: 1, id: "review-inherited-provider", review: { kind: "uncommitted" },
    actions: [{ kind: "exec", source: `text(${JSON.stringify(reviewExecutionMarker)});` }] };
}

export function reviewFinalText(nonce: string): string {
  assert.match(nonce, /^[a-f0-9]{32}$/);
  return JSON.stringify({ findings: [], overall_correctness: "patch is correct",
    overall_explanation: `REVIEW_CURRENT_${nonce}`, overall_confidence_score: 1 });
}

function gitEnvironment(home: string): Record<string, string> {
  return { ...Object.fromEntries(Object.entries(process.env).filter((entry): entry is [string, string] =>
    entry[1] !== undefined && !entry[0].startsWith("GIT_"))), HOME: home,
    XDG_CONFIG_HOME: resolve(home, ".config"), GIT_CONFIG_NOSYSTEM: "1" };
}

/** All Git state is created in this run's empty workspace and private home. */
export async function prepareReviewWorkspace(directory: string): Promise<void> {
  const workspace = resolve(directory, "workspace"), home = resolve(directory, "client-state");
  const commands: Array<Awaited<ReturnType<typeof capturedCommand>>> = [];
  const git = async (...args: string[]) => {
    const result = await capturedCommand(["git", "-C", workspace, ...args], workspace, undefined, 15000, gitEnvironment(home));
    commands.push(result);
    assert.equal(result.exitCode, 0, result.stderr);
    return result.stdout.trim();
  };
  await git("init", "--quiet");
  await writeFile(resolve(workspace, reviewProbeFile), before, { flag: "wx", mode: 0o600 });
  await git("add", "--", reviewProbeFile);
  await git("-c", "user.name=splice-fixture", "-c", "user.email=fixture@example.com", "commit", "--quiet", "-m", "test: establish private review fixture");
  const head = await git("rev-parse", "HEAD");
  assert.match(head, /^[a-f0-9]{40}$/);
  await writeFile(resolve(workspace, reviewProbeFile), after);
  await writeFile(resolve(directory, "review-workspace.json"), JSON.stringify({ schema: 1, head,
    file: reviewProbeFile, beforeSha256: sha256(before), afterSha256: sha256(after), commands }, null, 2) + "\n", { mode: 0o600 });
}

/** A fast one-shot review cannot finish before its actual CLI and host have been attested. */
export function reviewAttestation() {
  let releaseCli!: () => void, releaseHost!: () => void;
  const cli = new Promise<void>(done => { releaseCli = done; });
  const host = new Promise<void>(done => { releaseHost = done; });
  const state = { schema: 1, cliAttestedAt: null as number | null, hostAttestedAt: null as number | null,
    taskReleasedAt: [] as number[], finalReleasedAt: [] as number[], cancelled: false };
  const active = () => { if (state.cancelled) throw Error("Review attestation was cancelled"); };
  return {
    async beforeTask() { await cli; active(); state.taskReleasedAt.push(Date.now()); },
    async beforeFinal() { await host; active(); state.finalReleasedAt.push(Date.now()); },
    cliAttested() { active(); state.cliAttestedAt ??= Date.now(); releaseCli(); },
    hostAttested() { active(); assert.ok(state.cliAttestedAt !== null); state.hostAttestedAt ??= Date.now(); releaseHost(); },
    snapshot() { return structuredClone(state); },
    cancel() { state.cancelled = true; releaseCli(); releaseHost(); },
  };
}

export async function assertReviewProviderRun(run: CliRun): Promise<void> {
  assert.deepEqual(run.scenario, reviewProviderScenario(), "Review execution must use its exact stable plan");
  const completion = nativeReviewCompletion(resolve(run.directory, "client-state"), resolve(run.directory, "workspace"), reviewFinalText(run.nonce));
  assert.ok(completion, "Review requires its actual linked parent and child native completion");
  assert.deepEqual(run.completion, completion);
  assert.equal(run.taskRequests, run.scenario.actions.length + 1);
  assert.ok(run.hostProcesses.length > 0 && run.hostProcesses.every(host => host.sha256 === run.reference.host.sha256));
  const outputs = run.observations.flatMap(observation => observation.input).filter(item =>
    item.type === "custom_tool_call_output" && item.call_id === `probe-${run.scenario.id}-0`);
  assert.ok(outputs.some(output => JSON.stringify(output.output).includes(reviewExecutionMarker)), "Actual review child did not execute its inherited provider");
  const workspace = JSON.parse(await readFile(resolve(run.directory, "review-workspace.json"), "utf8"));
  assert.equal(workspace.schema, 1);
  assert.match(workspace.head, /^[a-f0-9]{40}$/);
  assert.equal(workspace.file, reviewProbeFile);
  assert.equal(workspace.beforeSha256, sha256(before));
  assert.equal(workspace.afterSha256, sha256(after));
  assert.equal(await readFile(resolve(run.directory, "workspace", reviewProbeFile), "utf8"), after);
  const actualHead = await capturedCommand(["git", "-C", resolve(run.directory, "workspace"), "rev-parse", "HEAD"], run.directory,
    undefined, 15000, gitEnvironment(resolve(run.directory, "client-state")));
  assert.equal(actualHead.exitCode, 0);
  assert.equal(actualHead.stdout.trim(), workspace.head);
  const initial = await capturedCommand(["git", "-C", resolve(run.directory, "workspace"), "show", `HEAD:${reviewProbeFile}`], run.directory,
    undefined, 15000, gitEnvironment(resolve(run.directory, "client-state")));
  assert.equal(initial.exitCode, 0);
  assert.equal(initial.stdout, before);
  const gate = JSON.parse(await readFile(resolve(run.directory, "review-attestation.json"), "utf8"));
  assert.equal(gate.schema, 1);
  assert.equal(gate.cancelled, false);
  assert.ok(Number.isSafeInteger(gate.cliAttestedAt) && Number.isSafeInteger(gate.hostAttestedAt) && gate.hostAttestedAt >= gate.cliAttestedAt);
  assert.equal(gate.taskReleasedAt.length, run.taskRequests);
  assert.equal(gate.finalReleasedAt.length, 1);
  assert.ok(gate.taskReleasedAt.every((time: number) => Number.isSafeInteger(time) && time >= gate.cliAttestedAt));
  assert.ok(gate.finalReleasedAt[0] >= gate.hostAttestedAt);
}
