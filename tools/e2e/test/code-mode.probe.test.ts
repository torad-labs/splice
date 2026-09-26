/** The code-mode PROBE's own net: the budget, the proxy handler, the synthetic workspace and the
 *  A/B row builder, all without a daemon or a vendor. The gate leg is
 *  `bun tools/e2e code-mode probe --selftest`, which runs this file.
 *
 *  V4-145 (carried): converted from code_mode_probe.py's ProbeTests. Each mock.patch of a module
 *  global is a swap on `probeSeams` / `compareSeams`, restored in `finally`.
 *
 *  Restructure PR 5: a real `bun test` file. The ASSERTIONS stay `check.*` from the compat layer
 *  rather than becoming `expect`: they compare the tagged Python tree (int vs float, key order,
 *  int precision past 2^53), which is what these receipts and budget snapshots are about, and
 *  `expect`'s structural equality would silently accept a float where the wire carries an int.
 */
import { describe, expect, test } from "bun:test";
import { readFileSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { dumps, loadsBytes, obj, type PyObj, type PyValue } from "../src/compat/python-json.ts";
import { check, get, OSError, ValueError } from "../src/compat/python-values.ts";
import {
  Budget, compareSeams, compareConfigure, CONTRACTS, GUIDANCE_SCENARIO, INPUT_BUDGET, MAX_REQUESTS, PROBE_CASES,
  ProbeWorkspace, probeSeams, proxyHandler, runCase, runComparison, runCompare, validatePromptExperiment,
  type ComparisonArgs,
} from "../src/commands/code-mode.ts";

/** ZipFile(path, "w").writestr(name, data) for each entry: stored, no compression. */
function writeZip(path: string, entries: [string, string][]): void {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const data = Buffer.from(text, "utf8");
    const nameBytes = Buffer.from(name, "utf8");
    const crc = Bun.hash.crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, nameBytes, data);
    centrals.push(central, nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const cd = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cd.length, 12);
  eocd.writeUInt32LE(offset, 16);
  writeFileSync(path, Buffer.concat([...locals, cd, eocd]));
}

/** A handler double: the MagicMock the original built, with the attributes do_POST touches. */
function fakeHandler(body: Uint8Array, onWrite?: (chunk: Uint8Array) => void) {
  const map = new Map<string, string>([["Content-Length", String(body.length)], ["Authorization", "Bearer synthetic-upstream"]]);
  let offset = 0;
  const h = {
    path: "/responses",
    closeConnection: false,
    headers: { get: (k: string, d: string | null = null) => map.get(k) ?? d, items: () => [...map.entries()] as [string, string][] },
    rfile: { read: (n: number) => { const out = body.subarray(offset, offset + n); offset += out.length; return out; } },
    writes: [] as Uint8Array[],
    sendErrorCalls: 0,
    wfile: { write: (c: Uint8Array | string) => { const b = typeof c === "string" ? Buffer.from(c) : c; h.writes.push(b); onWrite?.(b); }, flush: () => {} },
    sendResponse: () => {},
    sendHeader: () => {},
    endHeaders: () => {},
    sendError: () => {
      h.sendErrorCalls++;
    },
  };
  return h;
}
/** HTTPSConnection double whose response.read1 yields `chunks` in order. */
function fakeUpstream(chunks: Uint8Array[]) {
  const calls = { connect: 0 };
  const factory = () => {
    calls.connect++;
    const queue = [...chunks];
    return {
      request: () => {},
      getresponse: async () => ({
        status: 200,
        getheader: () => "text/event-stream",
        read1: async () => {
          const next = queue.shift();
          if (next === undefined) throw new Error("StopIteration");
          return next;
        },
        read: async () => new Uint8Array(0),
      }),
      close: () => {},
    };
  };
  return { factory, calls };
}
const enc = (s: string) => Buffer.from(s, "utf8");
const P = (v: unknown): PyValue => fromPlain(v);
function fromPlain(v: unknown): PyValue {
  if (v === null || v === undefined) return null;
  if (typeof v === "boolean" || typeof v === "string") return v;
  if (typeof v === "number") return { __pyNum: String(v), isFloat: !Number.isInteger(v) };
  if (Array.isArray(v)) return v.map(fromPlain);
  return obj(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, fromPlain(x)]));
}
async function withTempDir<T>(fn: (dir: string) => Promise<T> | T): Promise<T> {
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), "tmp"));
  try {
    return await fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
const snapGet = (b: Budget, k: string) => get(b.snapshot(), k);

describe("probe", () => {
  test("prompt only experiment rejects auto injection before launch", async () => {
    await withTempDir(async (directory) => {
      const artifact = join(directory, "app.jar");
      writeZip(artifact, [["splice/provider/codex/code-mode-orchestration.txt", "synthetic guidance"]]);
      const args = { artifact, prompt_guidance: true } as never;
      let launched = 0;
      let served = 0;
      const old = { ...compareSeams };
      try {
        compareSeams.Popen = (() => { launched++; throw new Error("launched"); }) as never;
        compareSeams.ThreadingHTTPServer = (() => { served++; throw new Error("served"); }) as never;
        await check.raises((e) => e instanceof ValueError, () => runCompare(args), /adds guidance automatically/);
        expect(launched).toBe(0);
        expect(served).toBe(0);
      } finally {
        Object.assign(compareSeams, old);
      }
      writeZip(artifact, [["legacy.txt", "no automatic guidance"]]);
      validatePromptExperiment(args);
    });
  });

  test("guidance comparison enables identical provider configs", async () => {
    await withTempDir(async (root) => {
      const source = join(root, "source-auth.json");
      writeFileSync(source, '{"tokens":{"access_token":"synthetic"}}');
      const [env] = await compareConfigure(root, source, 12345, true);
      const providers = (Bun.TOML.parse(readFileSync(env.SPLICE_CONFIG as string, "utf8")) as Record<string, Record<string, Record<string, unknown>>>).providers;
      check.equal(providers!.baseline, providers!.code_mode);
      check.true((providers!.baseline!.quirks as Record<string, unknown>).code_mode);
    });
  });

  test("guidance only appends system text and records real callback ids", async () => {
    const scenario = GUIDANCE_SCENARIO;
    const captured: PyObj[] = [];
    const [caseName, prompt] = scenario.CASES[1] as [string, string];
    const reply = async (_port: number, _method: string, _path: string, body?: PyValue) => {
      captured.push(loadsBytes(Buffer.from(dumps(body as PyValue))) as PyObj);
      if (captured.length % 2) {
        return P({ content: [{ type: "tool_use", id: "toolu_splice_test", name: "LSP", input: {
          operation: "findReferences", file_path: "app/limits.py", symbol: "build_client",
        } }] });
      }
      return obj([["content", [obj([["type", "text"], ["text", dumps(CONTRACTS[caseName] as PyObj)]])]], ["stop_reason", "end_turn"]]);
    };
    for (const suffix of ["", scenario.GUIDANCE]) {
      const old = probeSeams.requestJson;
      probeSeams.requestJson = reply as never;
      let row: PyObj;
      try {
        row = await runCase(1, "gpt-6-astra", caseName, prompt, scenario, suffix);
      } finally {
        probeSeams.requestJson = old;
      }
      check.true(get(row, "passed"), dumps(row));
      check.equal(1, get(row, "bridge_callbacks"));
      check.equal(1, get(row, "bridge_callback_batches"));
      check.equal(P({ LSP: 1 }), get(row, "tool_counts"));
    }
    const pop = (o: PyObj, k: string) => {
      const v = get(o, k);
      return [v, obj(o.__pyObj.filter(([key]) => key !== k))] as const;
    };
    const [existingSystem, existing] = pop(captured[0] as PyObj, "system");
    const [guidedSystem, guided] = pop(captured[2] as PyObj, "system");
    check.equal(scenario.SYSTEM, existingSystem);
    check.equal(scenario.SYSTEM + "\n\n" + scenario.GUIDANCE, guidedSystem);
    check.equal(existing, guided);
  });

  test("upstream shape counts guidance and script declaration separately", () => {
    const budget = new Budget();
    const payload = P({ input: [{ type: "additional_tools", tools: [{ type: "custom", name: "splice_exec" }] }] }) as PyObj;
    budget.recordShape(payload);
    (get(payload, "input") as PyValue[]).push(P({ role: "developer", content: "<code_mode_orchestration>guide</code_mode_orchestration>" }));
    budget.recordShape(payload);
    check.equal(2, snapGet(budget, "code_tool_requests"));
    check.equal(1, snapGet(budget, "guidance_requests"));
  });

  test("isolated comparison config changes only bridge policy", async () => {
    await withTempDir(async (root) => {
      const source = join(root, "source-auth.json");
      writeFileSync(source, '{"tokens":{"access_token":"synthetic"}}');
      const [env, control, baseline, codeMode] = await compareConfigure(root, source, 12345);
      const config = Bun.TOML.parse(readFileSync(env.SPLICE_CONFIG as string, "utf8")) as Record<string, Record<string, Record<string, Record<string, unknown>>>>;
      const providers = config.providers;
      const baseQuirks = providers!.baseline!.quirks as Record<string, unknown>;
      const codeQuirks = providers!.code_mode!.quirks as Record<string, unknown>;
      check.false(baseQuirks.code_mode);
      delete baseQuirks.code_mode;
      check.true(codeQuirks.code_mode);
      delete codeQuirks.code_mode;
      check.equal(providers!.baseline, providers!.code_mode);
      check.true((baseQuirks.tool_surface as Record<string, unknown>).enabled);
      check.true(baseQuirks.account_id_header);
      check.equal("high", (config.daemon as unknown as Record<string, unknown>).effort);
      check.equal(3, new Set([control, baseline, codeMode]).size);
      check.equal(readFileSync(source).toString("latin1"), readFileSync(join(root, "auth.json")).toString("latin1"));
      check.equal(0o600, statSync(join(root, "auth.json")).mode & 0o777);
      check.true((env.CODEX_OAUTH_TOKEN_URL as string).startsWith("http://127.0.0.1:"));
      check.equal("off", env.CLAUDEX_QUOTA_POLL);
    });
  });

  test("isolated comparison tears down without vendor requests", async () => {
    await withTempDir(async (root) => {
      const [artifact, auth, receipt] = ["app.jar", "auth.json", "receipt.json"].map((n) => join(root, n)) as [string, string, string];
      writeFileSync(artifact, "synthetic jar");
      writeFileSync(auth, '{"tokens":{"access_token":"synthetic"}}');
      const args = { artifact, auth_file: auth, receipt };
      const previous = process.env.SPLICE_PROBE_BEARER;
      const launch = { terminate: 0, waits: [] as (number | undefined)[] };
      const old = { ...compareSeams };
      try {
        compareSeams.runComparison = (async () => {
          writeFileSync(receipt, '{"runs":[],"failure":null}');
        }) as never;
        compareSeams.requestJson = (async () => P({ ok: true, readyHeads: 2 })) as never;
        compareSeams.Popen = (() => ({
          pid: 1, poll: () => null, kill: () => {},
          terminate: () => { launch.terminate++; },
          wait: (timeout?: number) => { launch.waits.push(timeout); return null; },
        })) as never;
        await runCompare(args);
      } finally {
        Object.assign(compareSeams, old);
      }
      expect(launch.terminate).toBe(1);
      check.equal([10], launch.waits);
      check.equal(previous, process.env.SPLICE_PROBE_BEARER);
      const saved = loadsBytes(readFileSync(receipt));
      check.equal(0, get(get(saved, "final_accounting"), "requests"));
      check.isNone(get(get(saved, "final_accounting"), "error"));
      check.equal('{"tokens":{"access_token":"synthetic"}}', readFileSync(auth, "utf8"));
    });
  });

  test("terminal usage is recorded before forwarding", async () => {
    const budget = new Budget();
    const body = enc(dumps(P({ model: "gpt-6-astra", reasoning: { effort: "high" } })));
    const terminal = enc("data: " + dumps(P({ type: "response.completed", response: {
      usage: { input_tokens: INPUT_BUDGET, output_tokens: 2 }, output: [],
    } })) + "\n\n");
    const handler = fakeHandler(body, (chunk) => {
      check.equal(Buffer.from(terminal).toString("latin1"), Buffer.from(chunk).toString("latin1"));
      check.equal(INPUT_BUDGET, snapGet(budget, "input_tokens"));
      check.false(budget.reserve(1));
    });
    const up = fakeUpstream([terminal, new Uint8Array(0)]);
    const old = probeSeams.httpsConnection;
    probeSeams.httpsConnection = up.factory as never;
    try {
      await proxyHandler(budget).POST(handler);
    } finally {
      probeSeams.httpsConnection = old;
    }
    expect(handler.writes.length).toBe(1);
    check.equal(INPUT_BUDGET, snapGet(budget, "input_tokens"));
    check.equal(2, snapGet(budget, "output_tokens"));
  });

  test("streamed calls count once with sparse or repeated terminal output", async () => {
    const items = [
      { type: "custom_tool_call", id: "script-item", call_id: "script", name: "splice_exec" },
      { type: "tool_search_call", id: "search-item", call_id: "search" },
    ];
    for (const terminalOutput of [[], items]) {
      const budget = new Budget();
      const events: unknown[] = [];
      items.forEach((item, index) => {
        for (const kind of ["response.output_item.added", "response.output_item.done"]) {
          events.push({ type: kind, output_index: index, item });
        }
      });
      events.push({ type: "response.completed", response: { usage: { input_tokens: 100, output_tokens: 10 }, output: terminalOutput } });
      const chunks = [...events.map((e) => enc("data: " + dumps(P(e)) + "\n\n")), new Uint8Array(0)];
      const body = enc(dumps(P({ model: "gpt-6-astra", reasoning: { effort: "high" } })));
      // Dedupe is response-local: a later response may reuse an item identifier.
      for (const expected of [1, 2]) {
        const handler = fakeHandler(body);
        const up = fakeUpstream(chunks);
        const old = probeSeams.httpsConnection;
        probeSeams.httpsConnection = up.factory as never;
        try {
          await proxyHandler(budget).POST(handler);
        } finally {
          probeSeams.httpsConnection = old;
        }
        check.equal(expected, snapGet(budget, "code_calls"), `terminal_output=${terminalOutput.length > 0}`);
        check.equal(expected, snapGet(budget, "search_calls"), `terminal_output=${terminalOutput.length > 0}`);
      }
    }
  });

  test("other model or effort is rejected before vendor dispatch", async () => {
    for (const payload of [[1], { model: "other" }, { model: "gpt-6-astra", reasoning: null },
      { model: "gpt-6-astra", reasoning: { effort: "low" } }]) {
      const budget = new Budget();
      const handler = fakeHandler(enc(dumps(P(payload))));
      const up = fakeUpstream([]);
      const old = probeSeams.httpsConnection;
      probeSeams.httpsConnection = up.factory as never;
      try {
        await proxyHandler(budget).POST(handler);
        check.equal(0, up.calls.connect, `payload=${JSON.stringify(payload)}`);
      } finally {
        probeSeams.httpsConnection = old;
      }
      check.equal(0, snapGet(budget, "requests"));
      check.equal(1, handler.sendErrorCalls);
    }
  });

  test("request budget is enforced before dispatch", () => {
    const budget = new Budget();
    for (let i = 0; i < MAX_REQUESTS; i++) check.true(budget.reserve(10));
    check.false(budget.reserve(10));
    check.equal(MAX_REQUESTS, snapGet(budget, "requests"));
  });

  test("missing usage stops future calls", () => {
    const budget = new Budget();
    budget.finish(null);
    check.false(budget.reserve(1));
  });

  test("token threshold stops future calls", () => {
    const budget = new Budget();
    budget.finish(P({ input_tokens: INPUT_BUDGET, output_tokens: 0 }));
    check.false(budget.reserve(1));
  });

  test("incomplete usage stops future calls", () => {
    const budget = new Budget();
    budget.finish(P({ input_tokens: 1 }));
    check.false(budget.reserve(1));
  });

  test("invalid cache usage halts but keeps known token spend", () => {
    for (const details of [null, { cached_tokens: -1 }, { cached_tokens: "1" }, { cached_tokens: 11 }]) {
      const budget = new Budget();
      budget.finish(P({ input_tokens: 10, output_tokens: 2, input_tokens_details: details }));
      check.false(budget.reserve(1), `details=${JSON.stringify(details)}`);
      check.equal(10, snapGet(budget, "input_tokens"));
      check.equal(2, snapGet(budget, "output_tokens"));
    }
  });

  test("in flight usage is accounted after halt", () => {
    const budget = new Budget();
    budget.finish(null);
    budget.finish(P({ input_tokens: 10, output_tokens: 2 }));
    check.false(budget.reserve(1));
    check.equal(10, snapGet(budget, "input_tokens"));
  });

  test("metrics failure retains executed case in receipt", async () => {
    await withTempDir(async (directory) => {
      const artifact = join(directory, "synthetic.jar");
      writeFileSync(artifact, "synthetic artifact");
      const receipt = join(directory, "receipt.json");
      const args: ComparisonArgs = { artifact, receipt, model: "gpt-6-astra", baseline_port: 1, code_mode_port: 2, metrics_port: 3 };
      const responses: (PyValue | Error)[] = [new Budget().snapshot(), new OSError("private detail")];
      const old = { ...probeSeams };
      try {
        probeSeams.requestJson = (async () => {
          const next = responses.shift();
          if (next instanceof Error) throw next;
          return next;
        }) as never;
        probeSeams.runCase = (async () => obj([["passed", true]])) as never;
        await check.raises((e) => e instanceof OSError, () => runComparison(args));
      } finally {
        Object.assign(probeSeams, old);
      }
      const saved = loadsBytes(readFileSync(receipt));
      check.equal(1, get(saved, "completed_runs"));
      check.false(get((get(saved, "runs") as PyValue[])[0] as PyValue, "accounting_complete"));
      check.equal("OSError", get(saved, "failure"));
      check.notIn("private detail", readFileSync(receipt, "utf8"));
    });
  });

  test("repeated read cannot replace missing file evidence", () => {
    const workspace = new ProbeWorkspace();
    for (let i = 0; i < 3; i++) workspace.execute({ name: "Read", input: P({ file_path: "a.txt" }) });
    check.false(workspace.correct("independent-reads", "110"));
    for (const path of ["b.txt", "c.txt"]) workspace.execute({ name: "Read", input: P({ file_path: path }) });
    check.true(workspace.correct("independent-reads", "The sum is 110."));
  });

  test("tools are fake and unknown operations fail", async () => {
    const workspace = new ProbeWorkspace();
    check.equal("21", workspace.execute({ name: "Read", input: P({ file_path: "a.txt" }) }));
    await check.raises((e) => e instanceof ValueError, () => workspace.execute({ name: "Bash", input: P({ command: "anything" }) }));
  });

  test("failed request is a failed row without exception content", async () => {
    const old = probeSeams.requestJson;
    probeSeams.requestJson = (async () => {
      throw new ValueError("private vendor content");
    }) as never;
    let row: PyObj;
    try {
      row = await runCase(1, "gpt-6-astra", ...PROBE_CASES[0]!);
    } finally {
      probeSeams.requestJson = old;
    }
    check.false(get(row, "passed"));
    check.equal("ValueError", get(row, "error"));
    check.notIn("private vendor content", dumps(row));
    check.equal(1, get(row, "client_requests"));
  });

  test("invalid tool is counted in failed row", async () => {
    const response = P({ content: [{ type: "tool_use", id: "call-1", name: "Bash", input: {} }] });
    const old = probeSeams.requestJson;
    probeSeams.requestJson = (async () => response) as never;
    let row: PyObj;
    try {
      row = await runCase(1, "gpt-6-astra", ...PROBE_CASES[0]!);
    } finally {
      probeSeams.requestJson = old;
    }
    check.false(get(row, "passed"));
    check.equal(1, get(row, "tool_calls"));
    check.equal(1, get(row, "failed_tool_calls"));
  });

  test("repeat counts use arguments not only tool names", () => {
    const workspace = new ProbeWorkspace();
    for (const path of ["a.txt", "b.txt", "a.txt"]) workspace.execute({ name: "Read", input: P({ file_path: path }) });
    check.equal(1, workspace.repeated_calls);
  });

  test("notification is not a poll", () => {
    const workspace = new ProbeWorkspace();
    workspace.execute({ name: "Agent", input: P({ prompt: "7 times 8" }) });
    check.isNotNone(workspace.notification);
    check.true((workspace.notification ?? "").includes("56"));
    check.true(workspace.correct("background-result", "56"));
  });
});
