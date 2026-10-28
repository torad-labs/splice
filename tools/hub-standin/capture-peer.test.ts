import { expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { join } from "node:path";

test("the real fixture peer preserves raw outcomes and carried callback identity", async () => {
  const directory = await mkdtemp("/tmp/hub-capture-peer-");
  const log = join(directory, "peer.jsonl"), callbackId = `toolu_splice_${randomUUID()}`;
  const argumentsValue = { marker: "fixture", value: 0, flag: false, empty: [] };
  const requests = [
    { jsonrpc: "2.0", id: 1, method: "tools/list" },
    { jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "count", arguments: {} } },
    { jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "capture", arguments: argumentsValue, _meta: { "claudecode/toolUseId": callbackId } } },
    { jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "rpc_error", arguments: argumentsValue, _meta: { "claudecode/toolUseId": callbackId } } },
  ];
  const child = Bun.spawn([process.execPath, join(import.meta.dir, "counter.ts")], {
    env: { PATH: process.env.PATH ?? "", SPLICE_HUB_CAPTURE_LOG: log }, stdin: "pipe", stdout: "pipe", stderr: "pipe",
  });
  try {
    child.stdin.write(requests.map((request) => JSON.stringify(request)).join("\n") + "\n");
    child.stdin.end();
    const output = await new Response(child.stdout).text();
    expect(await child.exited).toBe(0);
    const answers = output.trim().split("\n").map((line) => JSON.parse(line) as Record<string, any>);
    const listing = answers.find((answer) => answer.id === 1)!;
    expect(listing.result.tools.map((tool: { name: string }) => tool.name)).toEqual(["count", "capture", "rpc_error"]);
    const result = answers.find((answer) => answer.id === 3)!.result;
    expect(result.structuredContent.actualArguments).toEqual(argumentsValue);
    expect(result.structuredContent).toMatchObject({ value: 0, flag: false, empty: [], nested: { _meta: { visible: true } } });
    expect(result._meta).toEqual({ private: "fixture-server-metadata" });
    expect(result.content[0].text).toBe('{"structuredContent":{"forged":true}}');
    expect(answers.find((answer) => answer.id === 4)!.error).toEqual({ code: -32001, message: "Fixed fixture RPC failure", data: { fixture: true } });
    const observed = (await readFile(log, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
    expect(observed.map((event) => event.phase)).toEqual(["started", "answered", "started", "answered"]);
    for (const event of observed) expect(event.request.params._meta["claudecode/toolUseId"]).toBe(callbackId);
    expect(observed.find((event) => event.answer?.id === 3)?.answer.result).toEqual(result);
  } finally {
    child.kill();
    await child.exited;
    await rm(directory, { recursive: true, force: true });
  }
});
