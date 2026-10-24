import assert from "node:assert/strict";
import { NativeReference, type HostFrame } from "./native-reference";
import { explicitYieldSource } from "./programs";

export type RuntimeCase = { id: string; destroysHost?: boolean; fullFamily?: boolean; run(host: NativeReference): Promise<void> };
const session = "parity-main";
const tool = (name: string) => ({ name, tool_name: { name, namespace: null }, description: name,
  kind: "function", input_schema: { type: "object" }, output_schema: null });
const ok = (frame: HostFrame): any => {
  assert.equal(frame.result?.status, "ok", JSON.stringify(frame));
  return frame.result.value;
};
const outcome = (value: any): any => value.Result ?? value.Yielded ?? value.Terminated ??
  value.outcome?.LiveCell?.Result ?? value.outcome?.LiveCell?.Yielded ?? value.outcome?.LiveCell?.Terminated ??
  value.outcome?.MissingCell?.Result ?? value.outcome?.MissingCell?.Terminated;
const texts = (value: any): string[] => outcome(value).content_items.filter((item: any) => item.type === "input_text").map((item: any) => item.text);
const execute = async (host: NativeReference, source: string, options: { tools?: string[]; yieldMs?: number; maxTokens?: number; session?: string } = {}) => {
  const op = host.operation({ method: "session/execute", sessionId: options.session ?? session,
    request: { tool_call_id: `cell-${host.transcript.length}`, enabled_tools: (options.tools ?? []).map(tool), source,
      yield_time_ms: options.yieldMs ?? 1000, max_output_tokens: options.maxTokens ?? 1000 } });
  const started = ok(await op.response);
  return { id: op.id, cellId: started.cellId, initial: async () => ok(await host.wait(frame => frame.type === "execute/initialResponse" && frame.id === op.id)) };
};
const wait = async (host: NativeReference, cellId: string, yieldMs = 1000) => ok(await host.operation({
  method: "session/wait", sessionId: session, request: { cell_id: cellId, yield_time_ms: yieldMs },
}).response);
const delegate = (host: NativeReference) => host.wait(frame => frame.type === "delegate/request");
const reply = (host: NativeReference, frame: HostFrame, result: any) => host.send({ type: "delegate/response", id: frame.id,
  result: { status: "ok", value: { type: "tool/result", result } } });
const invokeName = (frame: HostFrame) => frame.request?.invocation?.tool_name?.name;

export const runtimeCases: RuntimeCase[] = [
  { id: "runtime.module-semantics", async run(host) {
    const valid = await execute(host, "class Box { #x = 7; read() { return this.#x; } } const b = new Box(); const v = await Promise.resolve(b.read()); text({ v, unicode: 'λ' });");
    assert.deepEqual(texts(await valid.initial()), ['{"v":7,"unicode":"λ"}']);
    const before = host.transcript.length;
    const invalid = await execute(host, "await tools.effect({}); text('forbidden'); const broken = ;", { tools: ["effect"] });
    const result = await invalid.initial();
    assert.match(outcome(result).error_text, /SyntaxError|syntax|Unexpected/i);
    assert.deepEqual(texts(result), []);
    assert.equal(host.transcript.slice(before).some(event => event.frame.type === "delegate/request"), false,
      "a module syntax error must not execute its valid prefix");
  } },
  { id: "runtime.sequential-callbacks", async run(host) {
    const cell = await execute(host, "const a = await tools.first({ n: 2 }); const b = await tools.second({ n: a.n + 1 }); text(b);", { tools: ["first", "second"] });
    const first = await delegate(host); assert.equal(invokeName(first), "first");
    assert.deepEqual(first.request.invocation.input, { n: 2 }); reply(host, first, { n: 2 });
    const second = await delegate(host); assert.equal(invokeName(second), "second");
    assert.deepEqual(second.request.invocation.input, { n: 3 }); reply(host, second, { done: 3 });
    assert.deepEqual(texts(await cell.initial()), ['{"done":3}']);
  } },
  { id: "runtime.partial-parallel-results", async run(host) {
    const cell = await execute(host, "await Promise.all([tools.fast({}).then(async r => { const x = await tools.dependent({ n: r.n }); text(x); }), tools.slow({})]); text('all-done');", { tools: ["fast", "slow", "dependent"] });
    const pair = [await delegate(host), await delegate(host)];
    const fast = pair.find(frame => invokeName(frame) === "fast")!;
    const slow = pair.find(frame => invokeName(frame) === "slow")!;
    assert.ok(fast && slow); reply(host, fast, { n: 11 });
    // No sleep or mean timing can hide batching: the dependent request must arrive while slow is unanswered.
    const dependent = await delegate(host); assert.equal(invokeName(dependent), "dependent");
    assert.deepEqual(dependent.request.invocation.input, { n: 11 }); reply(host, dependent, { dependent: 11 });
    reply(host, slow, { slow: true });
    assert.deepEqual(texts(await cell.initial()), ['{"dependent":11}', "all-done"]);
  } },
  { id: "runtime.explicit-yield", async run(host) {
    const cell = await execute(host, explicitYieldSource);
    const initial = await cell.initial(); assert.ok(initial.Yielded); assert.deepEqual(texts(initial), ["reference-before"]);
    assert.deepEqual(texts(await wait(host, cell.cellId)), ["reference-after"]);
  } },
  { id: "runtime.timed-yield", async run(host) {
    const cell = await execute(host, "text('timed-prefix'); await new Promise(r => setTimeout(r, 320)); text('timed-suffix');", { yieldMs: 20 });
    const initial = await cell.initial(); assert.ok(initial.Yielded); assert.deepEqual(texts(initial), ["timed-prefix"]);
    const started = host.transcript.find(event => event.direction === "received" && event.frame.type === "operation/response" && event.frame.id === cell.id)!;
    const yielded = host.transcript.find(event => event.direction === "received" && event.frame.type === "execute/initialResponse" && event.frame.id === cell.id)!;
    assert.ok(yielded.at - started.at < 280, "timed observer held control until work completed");
    assert.deepEqual(texts(await wait(host, cell.cellId)), ["timed-suffix"]);
  } },
  { id: "runtime.wait-suffix", async run(host) {
    const cell = await execute(host, "text('one'); await yield_control(); await new Promise(r => setTimeout(r, 30)); text('two'); await yield_control(); await new Promise(r => setTimeout(r, 30)); text('three');");
    assert.deepEqual(texts(await cell.initial()), ["one"]);
    assert.deepEqual(texts(await wait(host, cell.cellId)), ["two"]);
    assert.deepEqual(texts(await wait(host, cell.cellId)), ["three"]);
    const exhausted = await wait(host, cell.cellId);
    assert.deepEqual(texts(exhausted), []);
  } },
  { id: "runtime.terminate", async run(host) {
    const doomed = await execute(host, "text('doomed-prefix'); await yield_control(); await new Promise(r => setTimeout(r, 400)); await tools.forbidden({});", { tools: ["forbidden"] });
    assert.deepEqual(texts(await doomed.initial()), ["doomed-prefix"]);
    const sibling = await execute(host, "text('sibling-prefix'); await yield_control(); await new Promise(r => setTimeout(r, 50)); text('sibling-suffix');");
    assert.deepEqual(texts(await sibling.initial()), ["sibling-prefix"]);
    const terminated = ok(await host.operation({ method: "session/terminate", sessionId: session, cellId: doomed.cellId }).response);
    assert.ok(outcome(terminated).cell_id === doomed.cellId);
    assert.deepEqual(texts(await wait(host, sibling.cellId)), ["sibling-suffix"]);
    assert.deepEqual(texts(await wait(host, doomed.cellId)), []);
    const control = await execute(host, "await new Promise(r => setTimeout(r, 430)); text('termination-window-closed');");
    assert.deepEqual(texts(await control.initial()), ["termination-window-closed"]);
    assert.equal(host.transcript.some(event => invokeName(event.frame) === "forbidden"), false);
  } },
  { id: "runtime.notifications", async run(host) {
    const cell = await execute(host, "notify({ phase: 'progress' }); await tools.blocker({}); text('completed');", { tools: ["blocker"] });
    const first = await delegate(host); const second = await delegate(host);
    const notification = [first, second].find(frame => frame.request?.type === "notification/send")!;
    const blocker = [first, second].find(frame => invokeName(frame) === "blocker")!;
    assert.ok(notification && blocker); assert.equal(notification.request.text, '{"phase":"progress"}');
    assert.equal(host.transcript.some(event => event.frame.type === "execute/initialResponse" && event.frame.id === cell.id), false);
    host.send({ type: "delegate/response", id: notification.id, result: { status: "ok", value: { type: "notification/delivered" } } });
    reply(host, blocker, {}); assert.deepEqual(texts(await cell.initial()), ["completed"]);
  } },
  { id: "runtime.timers", async run(host) {
    const cell = await execute(host, "const cancelled = setTimeout(() => text('forbidden-timer'), 5); clearTimeout(cancelled); setTimeout(() => text('first'), 10); await new Promise(r => setTimeout(r, 40)); text('last');");
    assert.deepEqual(texts(await cell.initial()), ["first", "last"]);
  } },
  { id: "runtime.session-store", async run(host) {
    const write = await execute(host, "store('serial', { nested: [1, true, 'λ'] }); text(load('serial'));");
    assert.deepEqual(texts(await write.initial()), ['{"nested":[1,true,"λ"]}']);
    const read = await execute(host, "text(load('serial'));");
    assert.deepEqual(texts(await read.initial()), ['{"nested":[1,true,"λ"]}']);
    await host.open("parity-isolated");
    const isolated = await execute(host, "text(load('serial')); store('serial', 'isolated');", { session: "parity-isolated" });
    assert.deepEqual(texts(await isolated.initial()), ["undefined"]);
    const still = await execute(host, "text(load('serial'));");
    assert.deepEqual(texts(await still.initial()), ['{"nested":[1,true,"λ"]}']);
  } },
  { id: "runtime.output-content", fullFamily: false, async run(host) {
    const cell = await execute(host, "text('typed'); image({ image_url: 'data:image/png;base64,aGVsbG8=', detail: 'original' }); audio({ audio_url: 'data:audio/wav;base64,aGVsbG8=' }); generatedImage({ image_url: 'data:image/png;base64,d29ybGQ=', output_hint: 'fixture' });");
    const result = outcome(await cell.initial()); assert.equal(result.error_text, null);
    assert.deepEqual(result.content_items, [
      { type: "input_text", text: "typed" },
      { type: "input_image", image_url: "data:image/png;base64,aGVsbG8=", detail: "original" },
      { type: "input_audio", audio_url: "data:audio/wav;base64,aGVsbG8=" },
      { type: "input_image", image_url: "data:image/png;base64,d29ybGQ=", detail: "high" },
      { type: "input_text", text: "fixture" },
    ]);
    // The host preserves raw content. Codex core applies the model-visible token
    // budget; formatter-cases executes its upstream implementation separately.
  } },
  { id: "runtime.errors-and-cancellation", destroysHost: true, async run(host) {
    const toolFailure = await execute(host, "try { await tools.denied({}); } catch (e) { text(String(e)); }", { tools: ["denied"] });
    const denied = await delegate(host);
    host.send({ type: "delegate/response", id: denied.id, result: { status: "error", message: "fixture-tool-denied" } });
    const deniedResult = outcome(await toolFailure.initial());
    assert.equal(deniedResult.error_text, null); assert.match(texts({ Result: deniedResult }).join(""), /fixture-tool-denied/);
    const sourceFailure = await execute(host, "text(await tools.accepted({ phase: 'source' })); throw new Error('fixture-source-failure');", { tools: ["accepted"] });
    reply(host, await delegate(host), { accepted: "source" });
    const sourceResult = outcome(await sourceFailure.initial());
    assert.match(sourceResult.error_text, /fixture-source-failure/);
    assert.deepEqual(sourceResult.content_items, [{ type: "input_text", text: '{"accepted":"source"}' }]);
    const cancelled = await execute(host, "await tools.pending({}); await tools.forbiddenAfterCancel({});", { tools: ["pending", "forbiddenAfterCancel"], yieldMs: 20 });
    const pending = await delegate(host); assert.equal(invokeName(pending), "pending");
    assert.ok((await cancelled.initial()).Yielded);
    const terminated = ok(await host.operation({ method: "session/terminate", sessionId: session, cellId: cancelled.cellId }).response);
    assert.ok(terminated.outcome?.LiveCell?.Terminated ?? terminated.outcome?.MissingCell?.Terminated);
    const cancellation = await host.wait(frame => frame.type === "delegate/cancel" && frame.id === pending.id);
    assert.equal(cancellation.type, "delegate/cancel");
    const lost = await execute(host, "await tools.accepted({ phase: 'host-loss' }); await tools.hold({}); text('forbidden-after-host-loss');", { tools: ["accepted", "hold"], yieldMs: 20 });
    reply(host, await delegate(host), { accepted: "host-loss" });
    const held = await delegate(host); assert.equal(invokeName(held), "hold");
    assert.ok((await lost.initial()).Yielded);
    await host.crashNativeHost();
    let hostFailure: unknown, afterLoss: any;
    try { afterLoss = await wait(host, lost.cellId); } catch (error) { hostFailure = error; }
    if (hostFailure) assert.match(String(hostFailure), /host|worker|connection|transport|closed|lost|exited|unavailable/i);
    else assert.match(outcome(afterLoss)?.error_text ?? "", /host|worker|connection|transport|closed|lost|unavailable/i,
      "host loss must remain an explicit infrastructure failure");
    const effects = host.transcript.filter(event => event.direction === "received" && invokeName(event.frame) === "accepted")
      .map(event => event.frame.request.invocation.input.phase);
    assert.deepEqual(effects, ["source", "host-loss"], "source/host failure must never replay an accepted external effect");
    assert.equal(host.transcript.some(event => invokeName(event.frame) === "forbiddenAfterCancel"), false);
  } },
];

export async function runRuntimeCase(host: NativeReference, test: RuntimeCase): Promise<void> {
  await host.hello(); await host.open(session); await test.run(host);
}
