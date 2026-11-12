import { afterEach, expect, test } from "bun:test";
import { mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { sha256 } from "../qualification";
import { prepareReviewWorkspace, reviewAttestation, reviewFinalText, reviewProviderScenario } from "./review-contract";
import { startScriptedUpstream } from "./scripted-upstream";

const temporary: string[] = [];
afterEach(async () => { await Promise.all(temporary.splice(0).map(path => rm(path, { recursive: true, force: true }))); });
async function directory() {
  const path = await mkdtemp(resolve(tmpdir(), "splice-review-contract-"));
  temporary.push(path);
  return path;
}

test("the inherited-provider plan is stable while its declared review output belongs to one fresh run", () => {
  expect(reviewProviderScenario()).toEqual(reviewProviderScenario());
  const first = reviewFinalText("a".repeat(32)), second = reviewFinalText("b".repeat(32));
  expect(first).not.toBe(second);
  expect(JSON.parse(first)).toEqual({ findings: [], overall_correctness: "patch is correct",
    overall_explanation: `REVIEW_CURRENT_${"a".repeat(32)}`, overall_confidence_score: 1 });
  expect(() => reviewFinalText("stale")).toThrow();
});

test("review task and final responses remain blocked until their respective actual attestations", async () => {
  const gates = reviewAttestation();
  let task = false, final = false;
  const taskWait = gates.beforeTask().then(() => { task = true; });
  const finalWait = gates.beforeFinal().then(() => { final = true; });
  await Promise.resolve();
  expect([task, final]).toEqual([false, false]);
  expect(() => gates.hostAttested()).toThrow();
  gates.cliAttested();
  await taskWait;
  expect([task, final]).toEqual([true, false]);
  gates.hostAttested();
  await finalWait;
  expect([task, final]).toEqual([true, true]);
  expect(gates.snapshot().cancelled).toBe(false);
});

test("cancelling attestation wakes blocked fixture requests as failures", async () => {
  const gates = reviewAttestation();
  const waits = Promise.allSettled([gates.beforeTask(), gates.beforeFinal()]);
  gates.cancel();
  for (const result of await waits) {
    expect(result.status).toBe("rejected");
    if (result.status === "rejected") expect(String(result.reason)).toContain("cancelled");
  }
  expect(() => gates.cliAttested()).toThrow("cancelled");
});

test("the local producer emits the declared ReviewOutput without rewriting returned packets", async () => {
  const path = await directory(), scenario = reviewProviderScenario();
  expect(() => startScriptedUpstream(scenario, path, "a".repeat(32))).toThrow("attestation gates");
  const gates = reviewAttestation();
  const source = startScriptedUpstream(scenario, path, "a".repeat(32), gates);
  try {
    gates.cliAttested(); gates.hostAttested();
    for (let index = 0; index < 2; index++) {
      const response = await fetch(`http://127.0.0.1:${source.server.port}/v1/responses`, {
        method: "POST", body: JSON.stringify({ input: [] }), headers: { "content-type": "application/json" },
      });
      expect(response.status).toBe(200);
      await response.text();
    }
    expect(source.counts()).toEqual({ taskRequests: 2, auxiliaryRequests: 0, failure: undefined });
    await source.flush();
    const frames = JSON.parse(await readFile(resolve(path, "frames.json"), "utf8"));
    const completed = frames.filter((frame: any) => frame.type === "response.completed");
    expect(completed[1].response.output[0].content[0].text).toBe(reviewFinalText("a".repeat(32)));
    expect(gates.snapshot().taskReleasedAt.length).toBe(2);
    expect(gates.snapshot().finalReleasedAt.length).toBe(1);
  } finally { gates.cancel(); source.server.stop(true); await source.flush(); }
});

test("the actual private Git workspace retains an exact baseline and uncommitted fixture change", async () => {
  const path = await directory();
  await mkdir(resolve(path, "workspace"), { mode: 0o700 });
  await mkdir(resolve(path, "client-state"), { mode: 0o700 });
  await prepareReviewWorkspace(path);
  const receipt = JSON.parse(await readFile(resolve(path, "review-workspace.json"), "utf8"));
  expect(receipt.head).toMatch(/^[a-f0-9]{40}$/);
  expect(receipt.commands.every((command: any) => command.exitCode === 0)).toBe(true);
  expect(receipt.beforeSha256).toBe(sha256("first\n"));
  expect(receipt.afterSha256).toBe(sha256("second\n"));
  expect(await readFile(resolve(path, "workspace/fixture.txt"), "utf8")).toBe("second\n");
});
