// gw-check: `splice claude check [--json]` says how Claude Code starts on this machine and what list each start gets:
// `claude`, every head command splice wrote that is still there, and every launcher on PATH that starts Claude Code,
// each with whether it goes through the Splice gateway and whether its session's list is the gateway's; and every model
// list it finds that would change a session's list. It reports the person's files and never edits them. On a machine
// whose heads were retired it says one way, one list, and nothing of splice's left.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { chmodSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { toDoor } from "../door/lib/claude.ts";
import { Splice, freePort, json, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { launchOf } from "./lib/gateway.ts";

setDefaultTimeout(240_000);

interface Start {
  command: string;
  kind: "claude" | "head" | "launcher";
  path: string | null;
  gateway: boolean;
  gateway_list: boolean;
  why: string | null;
}
interface Found {
  path: string;
  keys: string[];
  splice: boolean;
}
interface Check {
  one_way: boolean;
  one_list: boolean;
  starts: Start[];
  lists: Found[];
}

const logins = `/tmp/claude-1000/catalog-check-${process.pid}`;
let backend: CodexBackend;
let borealis: Upstream;
let s: Splice;
let tools = "";

/** `splice claude check --json`, with `tools` first on PATH. */
function check(): { code: number; said: Check } {
  const r = s.cli(["claude", "check", "--json"], undefined, { PATH: `${tools}:${process.env.PATH ?? ""}` });
  return { code: r.code, said: json<Check>(r) };
}

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  borealis = new Upstream("borealis", "borealis-large").start();
  s = await Splice.create(
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, model: "borealis-large" }),
    gptEnv(backend.url),
  );
  await s.start();
  tools = join(s.home, "tools");
  mkdirSync(tools, { recursive: true });
  expect(s.cli(["install"]).code).toBe(0);
  toDoor(s);
  await launchOf(s, "claude-astra");
  await launchOf(s, "claude-borealis");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
  borealis?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("gw-check", () => {
  test("with heads still there, it names each head command and says claude is not yet the one way", () => {
    const { said } = check();
    const claude = said.starts.find((x) => x.kind === "claude");
    expect(claude).toMatchObject({ command: "claude", gateway: true, gateway_list: true });
    for (const command of ["claude-astra", "claude-borealis"]) {
      expect(said.starts.some((x) => x.kind === "head" && x.command === command)).toBe(true);
    }
    expect(said.one_way).toBe(false);
  });

  test("on a machine whose heads were retired: one way, one list, and nothing of splice's left", () => {
    expect(s.cli(["claude", "retire", "--all"]).code).toBe(0);
    const { code, said } = check();
    expect(said.one_way).toBe(true);
    expect(said.one_list).toBe(true);
    expect(said.starts.filter((x) => x.kind !== "launcher")).toEqual([
      expect.objectContaining({ command: "claude", kind: "claude", gateway: true, gateway_list: true }),
    ]);
    expect(said.lists).toEqual([]);
    expect(code).toBe(0);
    const text = s.cli(["claude", "check"], undefined, { PATH: `${tools}:${process.env.PATH ?? ""}` });
    expect(text.out + text.err).toMatch(/Splice gateway/);
  });

  test("a model list in the person's own Claude settings is named, and left as it is", () => {
    const file = join(s.home, ".claude", "settings.json");
    mkdirSync(join(s.home, ".claude"), { recursive: true });
    const mine = `${JSON.stringify({ availableModels: ["claude-opus-5-5"], theme: "dark" }, null, 2)}\n`;
    writeFileSync(file, mine);
    const { code, said } = check();
    const found = said.lists.find((l) => l.path === file);
    expect(found).toMatchObject({ splice: false });
    expect(found!.keys).toContain("availableModels");
    expect(said.one_list).toBe(false);
    expect(code).not.toBe(0);
    expect(readFileSync(file, "utf8")).toBe(mine);
    rmSync(file);
  });

  test("a launcher on PATH that starts Claude Code past the gateway is named, and left as it is", () => {
    const script = join(tools, "claude-elsewhere");
    const text = '#!/bin/sh\nANTHROPIC_BASE_URL=https://router.example.invalid ANTHROPIC_AUTH_TOKEN=x exec claude "$@"\n';
    writeFileSync(script, text);
    chmodSync(script, 0o755);
    const { said } = check();
    const found = said.starts.find((x) => x.path === script);
    expect(found).toMatchObject({ kind: "launcher", gateway: false });
    expect(found!.why ?? "").toContain("ANTHROPIC_BASE_URL");
    expect(said.one_way).toBe(false);
    expect(readFileSync(script, "utf8")).toBe(text);
    rmSync(script);
  });

  test("a launcher's own CLAUDE_CONFIG_DIR is read for a model list too", () => {
    const dir = join(s.home, "their-claude");
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, "settings.json"), JSON.stringify({ modelPicker: { options: [{ model: "claude-opus-5-5" }] } }));
    const script = join(tools, "claude-work");
    writeFileSync(script, `#!/bin/sh\nCLAUDE_CONFIG_DIR=${dir} exec claude "$@"\n`);
    chmodSync(script, 0o755);
    const { said } = check();
    const found = said.lists.find((l) => l.path === join(dir, "settings.json"));
    expect(found).toMatchObject({ splice: false });
    expect(found!.keys).toContain("modelPicker");
    // it goes through the gateway: only its folder's list differs
    expect(said.starts.find((x) => x.path === script)).toMatchObject({ kind: "launcher", gateway: true, gateway_list: false });
    rmSync(script);
  });
});
