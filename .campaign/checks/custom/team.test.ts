// cp-team: what issue #680 is for, end to end. On a machine with only splice and a team's published provider
// definition installed from its URL: `claude` on the door, and the team's own door command, list only the team's model
// in /model; signing in runs the team's own flow; the key renews by itself; the session's counts are scaled to the
// model's own window; a model that is loading and a client that is out of date are told in the gateway's own words.
// The guide a team writes its definition from, docs/specs/custom-provider.md, holds a definition that splice accepts.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { reported } from "../desktop/lib/desktop.ts";
import { ROOT, Splice, until } from "../server/lib/harness.ts";
import { ask, lane, picker, session, slash, toDoor } from "../door/lib/claude.ts";
import { Site } from "./lib/site.ts";
import { Gateway, LABEL, MODEL, SELECTOR, TeamCli, errorOf, withCli } from "./lib/team.ts";

setDefaultTimeout(180_000);

const WINDOW = 65_536;

let cli: TeamCli;
let gw: Gateway;
let s: Splice;
let site: Site;
let dir: string;

/** The definition the team publishes: no port, no prefix, the team's CLI named bare, as on a laptop it is on PATH. */
function published(url: string): string {
  return `
[providers.acme]
dialect = "openai-chat"
base_url = "${url}"
auth = { kind = "command", credential_command = "acme-ai token --json", login_command = "acme-ai login" }
# on loopback only because the check runs the gateway here; a team's definition names its own address
local = false
error_messages = "whole"
send_version = true

[[providers.acme.models]]
id = "${MODEL}"
label = "${LABEL}"
description = "Acme's own GLM"
context_window = ${WINDOW}
max_output_tokens = 16384

[heads.acme]
provider = "acme"
pinned_model = "${MODEL}"
model_slots = { opus = "${MODEL}", sonnet = "${MODEL}", haiku = "${MODEL}", fable = "${MODEL}" }

[door.commands.claude-acme]
head = "acme"
`;
}

async function turn(command = "claude", marker = `cp-team-${crypto.randomUUID()}`) {
  const t = await ask(await session(s, command), { model: SELECTOR, content: marker });
  return { t, tried: gw.turnsWith(marker).map(Gateway.bearer) };
}

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), "cp-team-"));
  cli = new TeamCli();
  gw = new Gateway(cli, { usage: { prompt: WINDOW / 2, cached: 0, completion: 40 } }).start();
  // served from a child process: s.cli is spawnSync and blocks this one (lib/site.ts)
  site = await new Site().start();
  const url = site.publish("acme.toml", published(gw.url));
  s = await Splice.boot("", withCli(cli));
  const r = s.cli(["add", "provider", url]);
  if (r.code !== 0) throw new Error(`splice add provider exited ${r.code}:\n${r.out}\n${r.err}`);
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  gw?.stop();
  site?.stop();
  cli?.dispose();
  rmSync(dir, { recursive: true, force: true });
});

describe("cp-team", () => {
  test("claude on the door and the team's own command list only the team's model, and start on it", async () => {
    for (const command of ["claude", "claude-acme"]) {
      const x = await session(s, command);
      expect((picker(x) ?? []).map((r) => r.model)).toEqual([SELECTOR]);
      expect(x.sets.ANTHROPIC_MODEL).toBe(SELECTOR);
    }
    expect((lane(s).models ?? []).map((m) => m.label)).toEqual([LABEL]);
  });

  test("signed out, a turn says how to sign in, and /login is splice's", async () => {
    const { t, tried } = await turn();
    expect(errorOf(t)?.message ?? "").toContain("/login");
    expect(tried).toEqual([]);
    expect(slash(await session(s), "login")).not.toBeNull();
  });

  test("Sign in runs the team's own flow, and the next turn runs with no restart", async () => {
    const r = await s.api("/api/auth/acme/login", { method: "POST", body: "{}" });
    expect(r.status).toBe(200);
    const { id } = (await r.json()) as { id: string };
    await until("the sign-in finishes", async () => ((await (await s.api(`/api/auth/acme/login/${id}`)).json()) as { state: string }).state === "signed_in", 30_000);
    const { t } = await turn("claude-acme");
    expect(t.status).toBe(200);
    expect(t.reply).toBe(gw.model.reply());
  });

  test("the key renews by itself", async () => {
    cli.expiresIn(60);
    const held = (await turn()).tried.at(-1)!;
    gw.revoked.add(held);
    const a = await turn();
    const b = await turn();
    expect(a.t.status).toBe(200);
    expect(b.t.status).toBe(200);
    expect(b.tried.at(-1)).not.toBe(a.tried.at(-1));
    cli.expiresIn(3600);
  });

  test("the session's counts are scaled to the model's own window", async () => {
    const x = await session(s);
    const t = await ask(x, { model: SELECTOR });
    expect(reported(t) / Number(x.sets.CLAUDE_CODE_MAX_CONTEXT_TOKENS)).toBeCloseTo(0.5, 1);
  });

  test("a loading model and an outdated client are told in the gateway's own words", async () => {
    const loading = "GLM-5.3-Flash is loading on the Acme cluster. About 40 seconds are left.";
    gw.next.push({ status: 503, message: loading, headers: { "retry-after": "10" } });
    const a = (await turn()).t;
    expect(a.headers.get("retry-after")).toBe("10");
    expect(errorOf(a)?.message ?? "").toContain(loading);
    const outdated = "Your acme-ai CLI 1.2.0 is out of date. Run `acme-ai update`, then start claude again.";
    gw.next.push({ status: 426, message: outdated });
    expect(errorOf((await turn("claude-acme")).t)?.message ?? "").toContain(outdated);
  });

  test("the guide's example definition passes splice add provider --check", () => {
    const guide = readFileSync(join(ROOT, "docs/specs/custom-provider.md"), "utf8");
    const block = /```toml\n([\s\S]*?)```/.exec(guide)?.[1];
    expect(block).toBeDefined();
    const file = join(dir, "guide.toml");
    writeFileSync(file, block!);
    const r = s.cli(["add", "provider", file, "--check"]);
    expect(r.code, `${r.out}\n${r.err}`).toBe(0);
  });
});
