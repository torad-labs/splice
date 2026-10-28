import { expect, test } from "bun:test";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expectedTaskRequests, startScriptedUpstream } from "./scripted-upstream";
import type { CliScenario } from "./types";

const scenario: CliScenario = { schema: 1, id: "interrupt-guard", interruption: { notification: "ready", resumePrompt: "resume" },
  actions: [{ kind: "exec", source: 'notify("ready");' }, { kind: "exec", source: 'text("resumed");' }] };

test("the owned post-yield request stays open until cancellation before the resumed action is sent", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codemode-scripted-interruption-"));
  const upstream = startScriptedUpstream(scenario, directory, "fixed");
  const address = `http://127.0.0.1:${upstream.server.port}/v1/responses`;
  const post = (input: object[], signal?: AbortSignal) => fetch(address, { method: "POST", body: JSON.stringify({ input }), signal });
  try {
    expect(expectedTaskRequests(scenario)).toBe(4);
    expect(expectedTaskRequests({ ...scenario, interruption: undefined })).toBe(3);
    expect(await (await post([])).text()).toContain('notify(\\"ready\\");');
    const controller = new AbortController();
    const response = await post([{ type: "custom_tool_call_output", call_id: "probe-interrupt-guard-0",
      output: "Script running with cell ID actual-native-cell\nWall time 0.0 seconds\nOutput:\n" }], controller.signal);
    const reader = response.body!.getReader();
    expect(new TextDecoder().decode((await reader.read()).value)).toContain("response.created");
    expect(upstream.interruptionReady()).toBe(true);
    const receipt = JSON.parse(await readFile(join(directory, "interruption-hold.json"), "utf8"));
    expect(receipt.cellId).toBe("actual-native-cell"); expect(receipt.abortedAt).toBeUndefined();
    controller.abort();
    await expect(reader.read()).rejects.toThrow();
    expect(await (await post([])).text()).toContain('text(\\"resumed\\");');
    expect(await (await post([])).text()).toContain(upstream.finalText);
    expect(upstream.counts().taskRequests).toBe(4);
    expect(upstream.counts().failure).toBeUndefined();
  } finally { upstream.server.stop(true); await upstream.flush(); await rm(directory, { recursive: true, force: true }); }
});

test("a notification alone cannot impersonate the primary yielded-cell input", async () => {
  const directory = await mkdtemp(join(tmpdir(), "codemode-scripted-interruption-"));
  const upstream = startScriptedUpstream(scenario, directory, "fixed");
  const post = (input: object[]) => fetch(`http://127.0.0.1:${upstream.server.port}/v1/responses`, { method: "POST", body: JSON.stringify({ input }) });
  try {
    await (await post([])).text();
    const response = await post([{ type: "custom_tool_call_output", name: "exec", call_id: "probe-interrupt-guard-0", output: "ready" }]);
    expect(response.status).toBe(500); expect(upstream.interruptionReady()).toBe(false);
    expect(upstream.counts().failure).toContain("exactly one primary exec result");
  } finally { upstream.server.stop(true); await upstream.flush(); await rm(directory, { recursive: true, force: true }); }
});
