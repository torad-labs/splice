// cp-install: `splice add provider <file|url>` installs a team's published provider definition, one provider with its
// models, its heads and its door commands in splice.toml's own grammar, with no hand-edited TOML. A head in a
// definition may leave out port and discovery_prefix, which splice fills in. A definition with findings is refused,
// every finding listed at once as boot lists them, and splice.toml is left byte for byte as it was; `--check` says the
// same and writes nothing. Installing a newer definition of a provider already installed replaces it. Over the
// network a definition is read from https, or from http on this machine only.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Splice, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, lane, picker, session, toDoor } from "../door/lib/claude.ts";
import { Site } from "./lib/site.ts";
import { MODEL } from "./lib/team.ts";

setDefaultTimeout(180_000);

let team: Upstream;
let s: Splice;
let dir: string;
let served: Site;

/** A team's definition of provider `key`: no port, no discovery prefix, on an API key so it runs with no sign-in. */
function definition(key: string, label: string, url: string): string {
  return `
[providers.${key}]
dialect = "openai-chat"
base_url = "${url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }

[[providers.${key}.models]]
id = "${MODEL}"
label = "${label}"
context_window = 65536

[heads.${key}]
provider = "${key}"
pinned_model = "${MODEL}"
`;
}

function write(name: string, text: string): string {
  const f = join(dir, name);
  writeFileSync(f, text);
  return f;
}

const said = (r: { out: string; err: string }) => `${r.out}\n${r.err}`;

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), "cp-install-"));
  team = new Upstream("acme", MODEL).start();
  // served from a child process: s.cli is spawnSync and blocks this one (lib/site.ts)
  served = await new Site().start();
  // a machine with only splice on it
  s = await Splice.boot("");
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  team?.stop();
  served?.stop();
  rmSync(dir, { recursive: true, force: true });
});

describe("cp-install", () => {
  test("--check on a good definition says so and writes nothing", () => {
    const before = readFileSync(s.config, "utf8");
    const r = s.cli(["add", "provider", write("good.toml", definition("acme", "GLM-5.3 Flash", team.url)), "--check"]);
    expect(r.code, said(r)).toBe(0);
    expect(readFileSync(s.config, "utf8")).toBe(before);
  });

  test("a definition from a file is installed, its port and prefix filled in, and its model is in /model", async () => {
    const r = s.cli(["add", "provider", join(dir, "good.toml")]);
    expect(r.code, said(r)).toBe(0);
    expect(said(r)).toContain("acme");
    const toml = readFileSync(s.config, "utf8");
    expect(toml).toMatch(/\[providers\.acme\]/);
    expect(toml).toMatch(/\[heads\.acme\][\s\S]*?\bport\s*=\s*\d+/);
    await until("the installed model is in the picker", async () => (lane(s).models ?? []).some((m) => m.model === `claude-acme--${MODEL}`), 60_000);
    const t = await ask(await session(s), { model: `claude-acme--${MODEL}` });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(team.reply());
  });

  test("a definition from a URL on this machine is installed the same way", async () => {
    const r = s.cli(["add", "provider", served.publish("beta.toml", definition("beta", "Beta GLM", team.url))]);
    expect(r.code, said(r)).toBe(0);
    await until("the second provider's model is in the picker", async () => (picker(await session(s)) ?? []).some((m) => m.model === `claude-beta--${MODEL}`), 60_000);
  });

  test("a newer definition of an installed provider replaces it, keeping its head's port", async () => {
    const port = /\[heads\.acme\][\s\S]*?\bport\s*=\s*(\d+)/.exec(readFileSync(s.config, "utf8"))![1];
    const r = s.cli(["add", "provider", write("newer.toml", definition("acme", "GLM-5.3 Flash (new)", team.url))]);
    expect(r.code, said(r)).toBe(0);
    const toml = readFileSync(s.config, "utf8");
    expect(toml.match(/\[providers\.acme\]/g)?.length).toBe(1);
    expect(/\[heads\.acme\][\s\S]*?\bport\s*=\s*(\d+)/.exec(toml)![1]).toBe(port);
    await until("the picker shows the new label", async () => (picker(await session(s)) ?? []).some((m) => m.label === "GLM-5.3 Flash (new)"), 60_000);
  });

  test("a definition with findings is refused, every one listed, and splice.toml is left as it was", () => {
    const before = readFileSync(s.config, "utf8");
    const bad = `
[daemon]
control_port = 1

[providers.gamma]
dialect = "openai-chat"
base_url = ""
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }

[[providers.gamma.models]]
id = "${MODEL}"
context_window = 65536

[heads.gamma]
provider = "elsewhere"
pinned_model = "${MODEL}"

[door.commands.claude-gamma]
head = "nohead"
`;
    const file = write("bad.toml", bad);
    for (const args of [["add", "provider", file, "--check"], ["add", "provider", file]]) {
      const r = s.cli(args);
      expect(r.code).not.toBe(0);
      const text = said(r);
      for (const path of [/\bdaemon\b/, /providers\.gamma\.base_url/, /heads\.gamma\.provider/, /door\.commands\.claude-gamma/]) {
        expect(text).toMatch(path);
      }
      expect(readFileSync(s.config, "utf8")).toBe(before);
    }
  });

  test("a definition over plain http from another machine is refused, naming https", () => {
    const before = readFileSync(s.config, "utf8");
    const r = s.cli(["add", "provider", "http://definitions.acme.example/acme.toml"]);
    expect(r.code).not.toBe(0);
    expect(said(r)).toMatch(/https/);
    expect(readFileSync(s.config, "utf8")).toBe(before);
  });
});
