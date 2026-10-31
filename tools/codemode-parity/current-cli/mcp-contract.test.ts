import { describe, expect, test } from "bun:test";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gatewayInput } from "./compare";
import { compareMcpBoundary, mcpFixtureCall, mcpFixtureTools, mcpGatewayFields, parseMcpTranscript, sourceMcpTranscript, type McpCall, type McpExchange, type McpRpcExchange } from "./mcp-contract";
import { mcpRpcErrorVectors } from "./mcp-rpc-cases";
import type { CliRun, CliScenario, ObjectValue } from "./types";

function transcript(calls: McpCall[]): string {
  const rows: ObjectValue[] = [
    { direction: "received", request: { jsonrpc: "2.0", id: 1, method: "tools/list" } },
    { direction: "sent", response: { jsonrpc: "2.0", id: 1, result: { tools: mcpFixtureTools() } } },
  ];
  calls.forEach((call, index) => {
    rows.push({ direction: "received", request: { jsonrpc: "2.0", id: index + 2, method: "tools/call", params: { name: call.name, arguments: call.arguments, ...(call.sourceContext ? { _meta: call.sourceContext } : {}) } } });
    rows.push({ direction: "sent", response: { jsonrpc: "2.0", id: index + 2, ...("error" in call ? { error: call.error } : { result: call.result }) } });
  });
  return rows.map(row => JSON.stringify(row)).join("\n") + "\n";
}

function candidate(calls: McpExchange[]): ObjectValue {
  const fields = mcpGatewayFields({ calls, tools: mcpFixtureTools() });
  const callbacks = (fields.callbacks as ObjectValue[]).map((call, index) => ({ ...call, id: `permission-${index}` }));
  return {
    boundary: "typed-mcp-delegate", callbacks,
    toolResults: callbacks.map((callback, index) => {
      const { _meta, ...visible } = calls[index]!.result;
      return { id: callback.id, origin: "MCP", disposition: "TOOL_OUTPUT", payload: calls[index]!.result, isError: calls[index]!.result.isError!, value: visible };
    }),
  };
}

describe("typed MCP delegation evidence", () => {
  test("the actual stdio peer returns full MCP values, ordinary failure and structured content", async () => {
    const directory = mkdtempSync(join(tmpdir(), "codemode-mcp-peer-"));
    const log = join(directory, "peer.jsonl");
    const child = Bun.spawn([process.execPath, join(import.meta.dir, "mcp-peer.ts"), log], { stdin: "pipe", stdout: "pipe", stderr: "pipe" });
    try {
      const requests = [
        { jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18" } },
        { jsonrpc: "2.0", id: 2, method: "tools/list" },
        ...["echo", "ordinary_failure", "structured"].map((name, index) => ({ jsonrpc: "2.0", id: index + 3, method: "tools/call", params: { name, arguments: { value: "actual-peer" } } })),
      ];
      child.stdin.write(requests.map(request => JSON.stringify(request)).join("\n") + "\n"); child.stdin.end();
      const [stdout, stderr, exit] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
      expect(exit, stderr).toBe(0);
      const responses = stdout.trim().split("\n").map(line => JSON.parse(line));
      expect(responses[2].result.content).toEqual([{ type: "text", text: "mcp-echo-actual-peer" }]);
      expect(responses[3].result.isError).toBe(true);
      expect(responses[4].result.structuredContent).toEqual({ value: "actual-peer", zero: 0, flag: false, empty: [], nested: { _meta: "public-nested-value" } });
      expect(responses[4].result._meta.privateFixtureMetadata).toBe("excluded-from-code-mode-result");
      expect(parseMcpTranscript(readFileSync(log, "utf8")).calls.map(call => call.name)).toEqual(["echo", "ordinary_failure", "structured"]);
    } finally { child.kill(); await child.exited; rmSync(directory, { recursive: true, force: true }); }
  });

  test("actual request ownership, schema and complete responses are required", () => {
    const bytes = transcript([mcpFixtureCall("echo", { value: "alpha" })]);
    expect(parseMcpTranscript(bytes).calls[0]?.arguments).toEqual({ value: "alpha" });
    expect(() => parseMcpTranscript(bytes.slice(0, -1))).toThrow("incomplete");
    const rows = bytes.trimEnd().split("\n").map(line => JSON.parse(line));
    expect(() => parseMcpTranscript(rows.slice(0, -1).map(row => JSON.stringify(row)).join("\n") + "\n")).toThrow("no result");
    const wrongOwner = structuredClone(rows); wrongOwner[3].response.id = 99;
    expect(() => parseMcpTranscript(wrongOwner.map(row => JSON.stringify(row)).join("\n") + "\n")).toThrow("unsolicited");
    const wrongCatalog = structuredClone(rows); wrongCatalog[1].response.result.tools[0].inputSchema.required = [];
    expect(() => parseMcpTranscript(wrongCatalog.map(row => JSON.stringify(row)).join("\n") + "\n")).toThrow("schemas");
    const wrongResult = structuredClone(rows); wrongResult[3].response.result.isError = true;
    expect(() => parseMcpTranscript(wrongResult.map(row => JSON.stringify(row)).join("\n") + "\n")).toThrow("bound input");
    const notification = structuredClone(rows); delete notification[2].request.id;
    expect(() => parseMcpTranscript(notification.map(row => JSON.stringify(row)).join("\n") + "\n")).toThrow("notifications");
    expect(() => mcpFixtureCall("not-advertised", { value: "alpha" })).toThrow("unadvertised");
    expect(() => mcpFixtureCall("echo", { value: "alpha", extra: true })).toThrow("schema");
  });

  test("an MCP case label cannot supply a typed result without actual peer exchanges", () => {
    const scenario: CliScenario = { schema: 1, id: "mcp", mcp: true, actions: [{ kind: "exec", source: "text(1)" }] };
    expect(() => gatewayInput(scenario)).toThrow("actual reference peer transcript");
    const actual = parseMcpTranscript(transcript([mcpFixtureCall("echo", { value: "bound" })]));
    const input = gatewayInput({ ...scenario, id: "not-a-case-dispatch" }, actual);
    expect((input.callbacks as ObjectValue[])[0]?.arguments).toEqual({ value: "bound" });
    expect(() => gatewayInput({ ...scenario, mcp: false }, actual)).toThrow("generic scenario");
  });

  test("typed values preserve ordinary errors and structured data and hide only top-level private metadata", () => {
    const calls = [mcpFixtureCall("echo", { value: "alpha" }), mcpFixtureCall("ordinary_failure", { value: "beta" }), mcpFixtureCall("structured", { value: "gamma" })];
    const actual = parseMcpTranscript(transcript(calls));
    expect(() => compareMcpBoundary(actual, candidate(calls))).not.toThrow();
    for (const change of [
      (value: ObjectValue) => { (value.toolResults as ObjectValue[])[1]!.disposition = "REJECT"; },
      (value: ObjectValue) => { (value.toolResults as ObjectValue[])[1]!.isError = false; },
      (value: ObjectValue) => { (value.toolResults as ObjectValue[])[0]!.origin = "GENERIC"; },
      (value: ObjectValue) => { (value.toolResults as ObjectValue[])[2]!.value = calls[2]!.result; },
      (value: ObjectValue) => { delete ((value.toolResults as ObjectValue[])[2]!.value as ObjectValue).structuredContent; },
      (value: ObjectValue) => { delete ((((value.toolResults as ObjectValue[])[2]!.value as ObjectValue).structuredContent as ObjectValue).nested as ObjectValue)._meta; },
      (value: ObjectValue) => { (value.callbacks as ObjectValue[])[1]!.id = (value.callbacks as ObjectValue[])[0]!.id!; },
      (value: ObjectValue) => { (value.callbacks as ObjectValue[])[0]!.arguments = { value: "wrong" }; },
      (value: ObjectValue) => { (value.toolResults as ObjectValue[])[0]!.value = "mcp-echo-alpha"; },
    ]) {
      const forged = structuredClone(candidate(calls)); change(forged);
      expect(() => compareMcpBoundary(actual, forged)).toThrow();
    }
  });

  test("the actual peer sends a bound JSON-RPC failure with no successful result", async () => {
    const directory = mkdtempSync(join(tmpdir(), "codemode-mcp-rpc-")), log = join(directory, "peer.jsonl");
    const child = Bun.spawn([process.execPath, join(import.meta.dir, "mcp-peer.ts"), log], { stdin: "pipe", stdout: "pipe", stderr: "pipe" });
    try {
      child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }) + "\n" + JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "echo", arguments: { value: "rpc-error-probe/object" } } }) + "\n"); child.stdin.end();
      const stdout = await new Response(child.stdout).text(); expect(await child.exited).toBe(0);
      const response = JSON.parse(stdout.trimEnd().split("\n")[1]!);
      expect(response.error).toEqual(mcpRpcErrorVectors[0]!.error); expect(response.result).toBeUndefined();
      const actual = parseMcpTranscript(readFileSync(log, "utf8"));
      const callbacks = mcpGatewayFields(actual).callbacks as ObjectValue[];
      expect(callbacks[0]!.origin).toBe("MCP_RPC_ERROR"); expect(callbacks[0]!.rpcError).toEqual(response.error); expect(callbacks[0]!.result).toBeUndefined();
      const forged = readFileSync(log, "utf8").trimEnd().split("\n").map(line => JSON.parse(line));
      forged.at(-1).response.result = { content: [] };
      expect(() => parseMcpTranscript(forged.map(row => JSON.stringify(row)).join("\n") + "\n")).toThrow("cannot also claim");
    } finally { child.kill(); await child.exited; rmSync(directory, { recursive: true, force: true }); }
  });

  test("RPC values require the actual source-resolved envelope and cannot become rejection or fabricated raw output", () => {
    const proof = JSON.parse(readFileSync(join(import.meta.dir, "../../../integrations/providers/codex/src/test/resources/codemode/mcp-rpc-errors-0.165.json"), "utf8"));
    const vector = proof.vectors[0], call: McpRpcExchange = { name: "echo", arguments: { value: "rpc-error-probe/object" }, error: vector.error };
    const actual = parseMcpTranscript(transcript([call]));
    const callback = { ...(mcpGatewayFields(actual).callbacks as ObjectValue[])[0]!, id: "permission-rpc" };
    const value: ObjectValue = { boundary: "typed-mcp-delegate", callbacks: [callback], toolResults: [{ id: callback.id, origin: "MCP", disposition: "TOOL_OUTPUT", isError: true, payload: vector.result, value: vector.result }] };
    expect(() => compareMcpBoundary(actual, value)).toThrow("actual source output");
    (actual.calls[0] as McpRpcExchange).codeModeValue = vector.result;
    expect(() => compareMcpBoundary(actual, value)).not.toThrow();
    for (const mutate of [
      (forged: ObjectValue) => { (forged.toolResults as ObjectValue[])[0]!.disposition = "REJECT"; },
      (forged: ObjectValue) => { (forged.toolResults as ObjectValue[])[0]!.payload = { content: [], isError: true }; },
      (forged: ObjectValue) => { (forged.callbacks as ObjectValue[])[0]!.origin = "MCP"; },
      (forged: ObjectValue) => { (forged.callbacks as ObjectValue[])[0]!.result = vector.result; },
    ]) { const forged = structuredClone(value); mutate(forged); expect(() => compareMcpBoundary(actual, forged)).toThrow(); }
  });

  test("the RPC projection binds a complete source script output to its actual originating MCP call", () => {
    const directory = mkdtempSync(join(tmpdir(), "codemode-rpc-source-"));
    const proof = JSON.parse(readFileSync(join(import.meta.dir, "../../../integrations/providers/codex/src/test/resources/codemode/mcp-rpc-errors-0.165.json"), "utf8")), vector = proof.vectors[0];
    const call: McpRpcExchange = { name: "echo", arguments: { value: "rpc-error-probe/object" }, error: vector.error, sourceContext: { callId: "exec-00000000-0000-0000-0000-000000000000", sessionId: "fixed-session", threadId: "fixed-session", itemId: "item-probe-rpc" } };
    writeFileSync(join(directory, "mcp-peer.jsonl"), transcript([call]));
    const source = { directory, scenario: { mcp: true }, completion: { sessionId: "fixed-session" }, observations: [{ input: [
      { type: "custom_tool_call", name: "exec", call_id: "probe-rpc" },
      { type: "custom_tool_call_output", call_id: "probe-rpc", output: [{ type: "input_text", text: "Script completed\nWall time 0.0 seconds\nOutput:\n" }, { type: "input_text", text: JSON.stringify({ tag: "object", resolved: vector.result }) }] },
    ] }] } as unknown as CliRun;
    try {
      expect((sourceMcpTranscript(source).calls[0] as McpRpcExchange).codeModeValue).toEqual(vector.result);
      const wrong = structuredClone(source); ((wrong.observations[0]!.input[1]!.output as ObjectValue[])[1]!).text = JSON.stringify({ tag: "another-call", resolved: vector.result });
      expect(() => sourceMcpTranscript(wrong)).toThrow();
      const rejected = structuredClone(source); ((rejected.observations[0]!.input[1]!.output as ObjectValue[])[1]!).text = JSON.stringify({ tag: "object", rejected: "failure" });
      expect(() => sourceMcpTranscript(rejected)).toThrow("rejected");
    } finally { rmSync(directory, { recursive: true, force: true }); }
  });
});
