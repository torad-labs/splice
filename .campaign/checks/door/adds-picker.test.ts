// adds-picker: on a machine that has a `claude` head, `claude` stays on it. A session starts as it did, with nothing of
// the door's in its launch, and `/model` keeps Claude Code's own models and gains the models of every signed-in
// account, each labelled with its command. `splice claude --json` lists them on the head lane too, and an account
// signed in while splice runs is in the next session's picker. (docs/specs/door-default.md, decision 9.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { readSettings, slottedHead } from "../desktop/lib/desktop.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { added, claudeHead } from "./lib/adds.ts";
import { DOOR_VARS, lane, session } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA = "claude-astra--gpt-6-astra";
const TERRA = "claude-terra--gpt-6.1-terra";

const logins = `/tmp/claude-1000/adds-picker-${process.pid}`;
let anthropic: Provider;
let backend: CodexBackend;
let terraBackend: CodexBackend;
let borealis: Upstream;
let s: Splice;
let terraPort = 0;

/** The head `terra` and its ChatGPT login: written, it is signed in. */
const terra = () => gptHead({ key: "terra", command: "claude-terra", port: terraPort, backend: terraBackend.url, model: "gpt-6.1-terra", dir: logins });

/** The selectors the next session's `/model` lists beside Claude Code's own. */
async function listed(): Promise<string[]> {
  return (added(await session(s)) ?? []).map((r) => r.model);
}

beforeAll(async () => {
  anthropic = new Provider("anthropic").start();
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  terraBackend = CodexBackend.start([{ slug: "gpt-6.1-terra", tiers: [] }]);
  borealis = new Upstream("borealis", "borealis-large").start();
  terraPort = await freePort();
  const toml =
    claudeHead({ port: await freePort(), url: anthropic.url }) +
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
    slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" }) +
    terra();
  s = await Splice.create(toml, gptEnv(backend.url));
  await s.start();
  // terra's login is gone: the head is in splice.toml and signed in to nothing
  rmSync(join(logins, "terra-auth.json"));
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  anthropic?.stop();
  backend?.stop();
  terraBackend?.stop();
  borealis?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("adds-picker", () => {
  test("claude is on its head, and its launch carries nothing of the door", async () => {
    expect(lane(s).lane).toBe("head");
    const x = await session(s);
    expect(x.sets.ANTHROPIC_BASE_URL ?? "").toMatch(/^http:\/\/127\.0\.0\.1:\d+/);
    for (const name of [...DOOR_VARS, "CLAUDE_CODE_MAX_CONTEXT_TOKENS", "DISABLE_LOGIN_COMMAND"]) expect(x.sets[name]).toBeUndefined();
    expect(x.sets.CLAUDE_CONFIG_DIR).toBeUndefined();
    // the model a session starts on stays the person's own: the launch names none
    expect(x.sets.ANTHROPIC_MODEL).toBeUndefined();
  });

  test("the picker keeps Claude Code's own models and adds the models of signed-in accounts, each with its command", async () => {
    await until("the signed-in account's model is added", async () => (await listed()).includes(ASTRA));
    const rows = added(await session(s))!;
    expect(rows.map((r) => r.model)).toEqual([ASTRA]);
    expect(rows[0]!.label ?? "").not.toBe("");
    expect(rows[0]!.description ?? "").toContain("claude-astra");
    expect(rows[0]!.description ?? "").toMatch(/splice/i);
  });

  test("a head called with an API key and a head whose login is missing are not added", async () => {
    await until("the signed-in account's model is added", async () => (await listed()).includes(ASTRA));
    const models = await listed();
    expect(models.some((m) => m.startsWith("claude-borealis--"))).toBe(false);
    expect(models).not.toContain(TERRA);
  });

  test("splice claude --json lists the added models on the head lane, and names the head left out with the reason", async () => {
    await until("splice claude lists the added model", () => (lane(s).models ?? []).some((m) => m.model === ASTRA));
    const l = lane(s);
    expect(l.lane).toBe("head");
    expect((l.models ?? []).map((m) => ({ model: m.model, head: m.head, command: m.command }))).toEqual([{ model: ASTRA, head: "astra", command: "claude-astra" }]);
    const out = (l.left_out ?? []).find((h) => h.head === "terra");
    expect(out?.command).toBe("claude-terra");
    expect(out?.reason ?? "").toMatch(/sign|log/i);
    const text = s.cli(["claude"]);
    expect(text.code).toBe(0);
    expect(text.out).toContain("claude-astra");
  });

  test("an account signed in while splice runs is in the next session's picker, with no restart", async () => {
    terra();
    expect(existsSync(join(logins, "terra-auth.json"))).toBe(true);
    await until("the picker adds the account signed in", async () => (await listed()).includes(TERRA), 30_000);
    expect(await listed()).toEqual([ASTRA, TERRA]);
  }, 60_000);

  test("nothing of the picker is written into the person's settings file", () => {
    expect(readSettings(s).modelPicker).toBeUndefined();
    expect(readSettings(s).model).toBeUndefined();
  });
});
