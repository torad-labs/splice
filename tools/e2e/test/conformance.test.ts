// tools/e2e/test/conformance.test.ts — the conformance verb's own net (V4-282). Everything here runs without the daemon:
// the corpus is complete against the source's dialects and modes, the census fails BY NAME on each way a cell can go
// missing, every grader passes a faithful upstream request and fails a mutated one, and the generated topology carries
// one head per dialect x mode. The live 63-cell run is the ladder's conformance leg (tools/gate/config/ladder.json);
// this file is its conformanceSelftest leg.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import {
  RIGS,
  census,
  conformance,
  gradeCheck,
  gradeChecks,
  gradeSeam,
  itemText,
  loadCorpus,
  sourceDialects,
  sourceEnumWires,
  sourceModes,
  systemText,
  topologyToml,
  type Check,
  type Corpus,
  type Json,
} from "../src/commands/conformance.ts";

const CLI = resolve(import.meta.dir, "../index.ts");
const DIALECTS = ["anthropic-passthrough", "openai-chat", "openai-responses"];

const corpus = (): Corpus => structuredClone(loadCorpus());

describe("the committed corpus", () => {
  test("covers every dialect and mode the source declares, with nothing unlisted", () => {
    expect(census(loadCorpus(), sourceDialects(), sourceModes(), Object.keys(RIGS))).toEqual([]);
  });

  test("the census denominators are read from the Kotlin source", () => {
    expect(sourceDialects().sort()).toEqual([...DIALECTS].sort());
    expect(sourceModes().sort()).toEqual(["append", "replace", "strip"]);
  });

  test("no @carrier reference survives resolution, in any request or check", () => {
    const strings: string[] = [];
    const walk = (v: unknown): void => {
      if (typeof v === "string") strings.push(v);
      else if (Array.isArray(v)) v.forEach(walk);
      else if (v && typeof v === "object") Object.values(v).forEach(walk);
    };
    Object.values(loadCorpus().fixtures).forEach((f) => (walk(f.request), walk(f.expect)));
    expect(strings.filter((s) => /^@[a-z]+$/.test(s))).toEqual([]);
  });

  test("every text a check demands is in the client's request: no check is unsatisfiable by construction", () => {
    const missing: string[] = [];
    for (const [name, fx] of Object.entries(loadCorpus().fixtures)) {
      const sent = JSON.stringify(fx.request);
      for (const [dialect, checks] of Object.entries(fx.expect)) {
        for (const c of checks) {
          const wanted = c.kind === "item" || c.kind === "absent" ? [c.text] : c.kind === "order" ? c.texts : [];
          for (const text of wanted) if (!sent.includes(JSON.stringify(text).slice(1, -1))) missing.push(`${name}/${dialect}: ${text.slice(0, 40)}`);
        }
      }
    }
    expect(missing).toEqual([]);
  });

  test("the mid-conversation system messages take the three shapes Claude Code 2.1.283 sends", () => {
    const shapes = new Set<string>();
    for (const fx of Object.values(loadCorpus().fixtures)) {
      for (const m of (fx.request["messages"] as Array<Record<string, Json>>) ?? []) {
        if (m["role"] !== "system") continue;
        shapes.add("clear_at" in m ? "clear_at" : typeof m["content"] === "string" ? "string" : "blocks");
      }
    }
    expect([...shapes].sort()).toEqual(["blocks", "clear_at", "string"]);
  });
});

describe("the census names every way a cell goes missing", () => {
  const run = (c: Corpus, dialects = DIALECTS, modes = ["append", "replace", "strip"], rigs = DIALECTS) => census(c, dialects, modes, rigs);

  test("a dialect the source declares that the harness has no row for", () => {
    const problems = run(corpus(), [...DIALECTS, "openai-foo"]);
    expect(problems[0]).toBe("dialect openai-foo has no harness row (no provider, stream or head builder)");
    // and every class, which has no checks for it either: 1 harness row + 6 classes
    expect(problems.slice(1).every((p) => p.endsWith("has no checks for dialect openai-foo"))).toBe(true);
    expect(problems).toHaveLength(7);
  });

  test("a mode with no head prompt or no seam expectation", () => {
    const c = corpus();
    delete c.manifest.headPrompts["strip"];
    delete c.manifest.systemSeam["strip"];
    expect(run(c)).toEqual(["mode strip has no head prompt in the manifest", "mode strip has no system seam expectation in the manifest"]);
    expect(run(corpus(), DIALECTS, ["append", "replace", "strip", "rewrite"])).toHaveLength(2);
  });

  test("a class the manifest lists without a fixture, and a fixture the manifest does not list", () => {
    const c = corpus();
    delete c.fixtures["task-notification"];
    expect(run(c)).toEqual(["class task-notification is listed in the manifest and has no fixture file"]);
    const d = corpus();
    d.files.push("stray-class");
    expect(run(d)).toEqual(["fixture stray-class.json is not listed in the manifest"]);
  });

  test("a class with no checks for a dialect", () => {
    const c = corpus();
    c.fixtures["reasoning-blocks"]!.expect["openai-chat"] = [];
    expect(run(c)).toEqual(["class reasoning-blocks has no checks for dialect openai-chat"]);
  });

  test("a new enum entry in the Kotlin source is a new denominator", () => {
    const src = 'public enum class Dialect {\n    @SerialName("openai-responses")\n    A,\n\n    @SerialName("openai-foo")\n    B,\n}\n';
    expect(sourceEnumWires(src, "Dialect")).toEqual(["openai-responses", "openai-foo"]);
    expect(() => sourceEnumWires(src, "Missing")).toThrow("enum class Missing not found");
  });
});

// A faithful upstream request per wire, then a mutation of it that each check must catch.
const chatBody = (extra: Json[] = []): Json => ({
  messages: [
    { role: "system", content: "CLIENT" },
    { role: "user", content: "U1" },
    { role: "system", content: "NOTICE-TEXT" },
    { role: "assistant", content: "A1" },
    ...extra,
    { role: "user", content: "U2" },
  ],
});

describe("the graders pass a faithful request and fail a mutated one", () => {
  const item: Check = { kind: "item", role: "system", text: "NOTICE-TEXT" };

  test("item: the V4-277 shape, a dropped role=system message, fails by role and count", () => {
    expect(gradeCheck("openai-chat", chatBody(), item)).toBeNull();
    const dropped: Json = { messages: [{ role: "user", content: "U1" }, { role: "assistant", content: "A1" }] };
    expect(gradeCheck("openai-chat", dropped, item)).toContain("found 0");
  });

  test("item: right text under the wrong role is named, and a duplicate is not one item", () => {
    const wrongRole: Json = { messages: [{ role: "user", content: "NOTICE-TEXT" }] };
    expect(gradeCheck("openai-chat", wrongRole, item)).toContain("arrived under role user");
    const twice: Json = { messages: [{ role: "system", content: "NOTICE-TEXT" }, { role: "system", content: "NOTICE-TEXT" }] };
    expect(gradeCheck("openai-chat", twice, item)).toContain("found 2");
  });

  test("item: text blocks and a bare string read the same, on every wire's list", () => {
    const blocks: Json = { messages: [{ role: "system", content: [{ type: "text", text: "NOTICE-" }, { type: "text", text: "TEXT", cache_control: { type: "ephemeral" } }] }] };
    expect(gradeCheck("anthropic-passthrough", blocks, item)).toBeNull();
    expect(gradeCheck("openai-responses", { input: [{ type: "message", role: "system", content: "NOTICE-TEXT" }] }, item)).toBeNull();
    expect(gradeCheck("openai-responses", { messages: [{ role: "system", content: "NOTICE-TEXT" }] }, item)).toContain("found 0");
  });

  test("item: a truncated text is not the whole text", () => {
    expect(gradeCheck("openai-chat", { messages: [{ role: "system", content: "NOTICE-TEX" }] }, item)).toContain("found 0");
  });

  test("order: out of order and missing are each named", () => {
    const order: Check = { kind: "order", texts: ["U1", "NOTICE-TEXT", "A1", "U2"] };
    expect(gradeCheck("openai-chat", chatBody(), order)).toBeNull();
    const swapped: Json = { messages: [{ role: "assistant", content: "A1" }, { role: "system", content: "NOTICE-TEXT" }, { role: "user", content: "U1" }, { role: "user", content: "U2" }] };
    expect(gradeCheck("openai-chat", swapped, order)).toContain("out of order");
    expect(gradeCheck("openai-chat", { messages: [{ role: "user", content: "U1" }] }, order)).toContain("not delivered");
  });

  test("tool: whole-schema equality, and `keys` narrows it for a dialect that normalizes", () => {
    const schema: Json = { type: "object", additionalProperties: true };
    const tool: Check = { kind: "tool", name: "t", schema };
    const chat = (parameters: Json): Json => ({ tools: [{ type: "function", function: { name: "t", parameters } }] });
    expect(gradeCheck("openai-chat", chat(schema), tool)).toBeNull();
    expect(gradeCheck("openai-chat", chat({ type: "object", additionalProperties: true, properties: {} }), tool)).toContain("schema changed");
    const narrowed: Check = { kind: "tool", name: "t", schema, keys: ["type", "additionalProperties"] };
    const responses = (parameters: Json): Json => ({ tools: [{ type: "function", name: "t", parameters }] });
    expect(gradeCheck("openai-responses", responses({ type: "object", additionalProperties: true, properties: {} }), narrowed)).toBeNull();
    expect(gradeCheck("openai-responses", responses({ type: "object", additionalProperties: false }), narrowed)).toContain("schema changed");
    expect(gradeCheck("openai-responses", { tools: [] }, narrowed)).toContain("found 0");
    expect(gradeCheck("anthropic-passthrough", { tools: [{ name: "t", input_schema: schema }] }, tool)).toBeNull();
  });

  test("toolcall: the input survives, on each wire", () => {
    const call: Check = { kind: "toolcall", name: "t", input: { a: [1, { b: null }] } };
    const chat: Json = { messages: [{ role: "assistant", content: null, tool_calls: [{ id: "1", type: "function", function: { name: "t", arguments: '{"a":[1,{"b":null}]}' } }] }] };
    expect(gradeCheck("openai-chat", chat, call)).toBeNull();
    expect(gradeCheck("openai-responses", { input: [{ type: "function_call", name: "t", arguments: '{"a":[1,{"b":null}]}' }] }, call)).toBeNull();
    expect(gradeCheck("anthropic-passthrough", { messages: [{ role: "assistant", content: [{ type: "tool_use", name: "t", input: { a: [1, { b: null }] } }] }] }, call)).toBeNull();
    expect(gradeCheck("openai-responses", { input: [{ type: "function_call", name: "t", arguments: '{"a":[2]}' }] }, call)).toContain("changed its input");
  });

  test("thinking and redacted_thinking: byte for byte, a changed signature fails", () => {
    const thinking: Check = { kind: "thinking", text: "T", signature: "SIG" };
    const redacted: Check = { kind: "redacted", data: "BLOB" };
    const body = (sig: string): Json => ({ messages: [{ role: "assistant", content: [{ type: "thinking", thinking: "T", signature: sig }, { type: "redacted_thinking", data: "BLOB" }] }] });
    expect(gradeCheck("anthropic-passthrough", body("SIG"), thinking)).toBeNull();
    expect(gradeCheck("anthropic-passthrough", body("SIG"), redacted)).toBeNull();
    expect(gradeCheck("anthropic-passthrough", body("OTHER"), thinking)).toContain("byte for byte");
    expect(gradeCheck("anthropic-passthrough", { messages: [{ role: "assistant", content: [{ type: "text", text: "x" }] }] }, redacted)).toContain("byte for byte");
  });

  test("absent: a leak anywhere in the body fails", () => {
    const leak: Check = { kind: "absent", text: "SIG-LEAK" };
    expect(gradeCheck("openai-chat", chatBody(), leak)).toBeNull();
    expect(gradeCheck("openai-chat", chatBody([{ role: "assistant", content: "x SIG-LEAK y" }]), leak)).toContain("reached the upstream");
  });

  test("gradeChecks numbers and names each failing check", () => {
    const checks: Check[] = [item, { kind: "absent", text: "CLIENT" }];
    expect(gradeChecks("openai-chat", chatBody(), checks)).toEqual(['check 2 (absent): "CLIENT" reached the upstream']);
  });
});

describe("the system seam", () => {
  const row = { present: ["KEEP", "HEAD"], absent: ["STRIP"] };

  test("reads each wire where the dialect puts the system text", () => {
    expect(systemText("anthropic-passthrough", { system: [{ type: "text", text: "A" }, { type: "text", text: "B" }] })).toBe("AB");
    expect(systemText("anthropic-passthrough", { system: "AB" })).toBe("AB");
    expect(systemText("openai-chat", chatBody())).toBe("CLIENT");
    expect(systemText("openai-responses", { instructions: "I", input: [{ type: "message", role: "developer", content: "D" }, { role: "user", content: "U" }] })).toBe("I\nD");
  });

  test("fails a seam that lost text, and one that kept text a strip should have removed", () => {
    expect(gradeSeam("openai-responses", { instructions: "KEEP", input: [{ role: "developer", content: "HEAD" }] }, row)).toEqual([]);
    expect(gradeSeam("openai-responses", { instructions: "KEEP", input: [] }, row)).toEqual(['system seam lost "HEAD"']);
    expect(gradeSeam("openai-responses", { instructions: "KEEP STRIP", input: [{ role: "developer", content: "HEAD" }] }, row)).toEqual(['system seam still carries "STRIP"']);
  });

  test("itemText joins only the parts that carry text", () => {
    expect(itemText({ content: [{ type: "thinking", thinking: "no", signature: "s" }, { type: "text", text: "yes" }] })).toBe("yes");
  });
});

describe("the generated topology", () => {
  const dialects = DIALECTS;
  const modes = ["append", "replace", "strip"];
  const c = loadCorpus();
  const heads = dialects.flatMap((d) => modes.map((m) => `conf-${RIGS[d]!.short}-${m}`));
  const ports = { control: 4000, heads: Object.fromEntries(heads.map((k, n) => [k, 4001 + n])) };
  const parsed = Bun.TOML.parse(topologyToml(c.manifest, dialects, modes, ports, "http://127.0.0.1:1", "/tmp/auth.json")) as {
    providers: Record<string, { dialect: string; models: unknown[] }>;
    heads: Record<string, { provider: string; port: number; system_prompt: string; system_prompt_mode: string; claude: { command: string } }>;
  };

  test("one provider per dialect and one head per dialect x mode, each with its own port and command", () => {
    expect(Object.values(parsed.providers).map((p) => p.dialect).sort()).toEqual([...dialects].sort());
    expect(Object.keys(parsed.heads).sort()).toEqual([...heads].sort());
    expect(new Set(Object.values(parsed.heads).map((h) => h.port)).size).toBe(9);
    expect(new Set(Object.values(parsed.heads).map((h) => h.claude.command)).size).toBe(9);
  });

  test("every head carries its mode and that mode's prompt", () => {
    for (const [key, head] of Object.entries(parsed.heads)) {
      const mode = key.split("-").at(-1)!;
      expect(head.system_prompt_mode).toBe(mode);
      expect(head.system_prompt).toBe(c.manifest.headPrompts[mode]!);
    }
  });
});

describe("the argv contract", () => {
  test("a valueless --artifact, --class or --json is refused, never read as the default", async () => {
    for (const opt of ["--artifact", "--class", "--json"]) {
      expect(await conformance([opt])).toBe(2);
      expect(await conformance([opt, "--keep"])).toBe(2);
    }
  });

  test("a missing jar is a harness failure that names the producer", () => {
    const r = spawnSync(process.execPath, [CLI, "conformance", "--artifact", "/nonexistent/app-all.jar"], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    expect(r.status).toBe(2);
    expect(r.stderr).toContain(":app:shadowJar");
  });

  test("a class that is not in the manifest is a harness failure", () => {
    const r = spawnSync(process.execPath, [CLI, "conformance", "--class", "no-such-class", "--artifact", CLI], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    expect(r.status).toBe(2);
    expect(r.stderr).toContain("--class no-such-class is not a class");
  });
});
