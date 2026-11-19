// door-picker: the `/model` of a `claude` on the door lists the models of signed-in accounts, and of a head called with
// an API key or a local server the models splice.toml declares for it, or every model it lists when it declares none
// (Nov 18, 2026: until then a key head had no row, and a custom API added with its own key and model could not be
// picked). The rest of what a key head lists still runs when its selector is named. A head whose login is missing is
// left out and named with the reason, and an account signed in while splice runs is in the next session's picker.
// (docs/specs/door-default.md, decision 4.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { readSettings, slottedHead } from "../desktop/lib/desktop.ts";
import { Splice, freePort, mockHead, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { ask, lane, picker, session, toDoor } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA = "claude-astra--gpt-6-astra";
const TERRA = "claude-terra--gpt-6.1-terra";
const BOREALIS_BIG = "claude-borealis--borealis-large";
const BOREALIS_SMALL = "claude-borealis--borealis-small";
// A local model server that declares no model in splice.toml: every model it lists is a row.
const LOCAL = "claude-local--local-model";

const logins = `/tmp/claude-1000/door-picker-${process.pid}`;
// Each ChatGPT login has a catalog of its own, with one model: a head lists what its login's catalog serves.
let backend: CodexBackend;
let terraBackend: CodexBackend;
let borealis: Upstream;
let local: Upstream;
let s: Splice;
let terraPort = 0;

/** The head `terra` and its ChatGPT login: written, it is signed in. */
const terra = () => gptHead({ key: "terra", command: "claude-terra", port: terraPort, backend: terraBackend.url, model: "gpt-6.1-terra", dir: logins });

/** The selectors the next session's picker lists. */
async function listed(): Promise<string[]> {
  return (picker(await session(s)) ?? []).map((r) => r.model);
}

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  terraBackend = CodexBackend.start([{ slug: "gpt-6.1-terra", tiers: [] }]);
  // borealis lists only its large model; splice.toml declares both
  borealis = new Upstream("borealis", "borealis-large").start();
  local = new Upstream("local", "local-model").start();
  terraPort = await freePort();
  const localHead = mockHead({ key: "local", command: "claude-local", port: await freePort(), url: local.url, model: "local-model" })
    // a local server whose table declares no model: what it lists is what it serves
    .replace(/\n\[\[providers\.local\.models\]\][\s\S]*?(?=\n\[heads\.local\])/, "\n");
  const toml =
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
    slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" }) +
    localHead +
    terra();
  s = await Splice.create(toml, gptEnv(backend.url));
  await s.start();
  // terra's login is gone: the head is in splice.toml and signed in to nothing
  rmSync(join(logins, "terra-auth.json"));
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
  terraBackend?.stop();
  borealis?.stop();
  local?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("door-picker", () => {
  test("the picker lists signed-in accounts and the models of key heads, in place of Claude Code's own", async () => {
    await until("the signed-out head leaves the picker", async () => !(await listed()).includes(TERRA));
    await until("the local server's listed model is in the picker", async () => (await listed()).includes(LOCAL), 60_000);
    const rows = picker(await session(s));
    expect(rows).not.toBeNull();
    expect(rows!.map((r) => r.model)).toEqual([ASTRA, BOREALIS_BIG, BOREALIS_SMALL, LOCAL]);
    expect(rows![0]!.label ?? "").not.toBe("");
    expect(rows![0]!.description ?? "").toContain("claude-astra");
    expect(rows![0]!.description ?? "").toMatch(/splice/i);
    expect(rows![1]!.description ?? "").toContain("claude-borealis");
    expect(lane(s).models).toEqual([
      { model: ASTRA, label: rows![0]!.label!, head: "astra", command: "claude-astra" },
      { model: BOREALIS_BIG, label: rows![1]!.label!, head: "borealis", command: "claude-borealis" },
      { model: BOREALIS_SMALL, label: rows![2]!.label!, head: "borealis", command: "claude-borealis" },
      { model: LOCAL, label: rows![3]!.label!, head: "local", command: "claude-local" },
    ]);
  });

  test("a session starts on the first signed-in account's model, and its first turn runs there", async () => {
    const x = await session(s);
    expect(lane(s).default_model).toBe(ASTRA);
    expect(x.sets.ANTHROPIC_MODEL).toBe(ASTRA);
    const marker = `door-picker-first-${crypto.randomUUID()}`;
    const t = await ask(x, { model: x.sets.ANTHROPIC_MODEL, content: marker });
    expect(t.status).toBe(200);
    expect(backend.turnsWith(marker).length).toBe(1);
  });

  test("a key head's declared model runs when picked", async () => {
    const t = await ask(await session(s), { model: BOREALIS_BIG });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(borealis.reply());
  });

  test("a head whose login is missing is left out, and splice claude names it with the reason", async () => {
    await until("splice claude names the signed-out head", () => (lane(s).left_out ?? []).some((h) => h.head === "terra"));
    const out = (lane(s).left_out ?? []).find((h) => h.head === "terra")!;
    expect(out.command).toBe("claude-terra");
    expect(out.reason).toMatch(/sign|log/i);
    const text = s.cli(["claude"]);
    expect(text.code).toBe(0);
    expect(text.out).toContain("claude-terra");
  });

  test("an account signed in while splice runs is in the next session's picker, with no restart", async () => {
    terra();
    expect(existsSync(join(logins, "terra-auth.json"))).toBe(true);
    await until("the picker lists the account signed in", async () => (await listed()).includes(TERRA), 30_000);
    expect(await listed()).toEqual([ASTRA, BOREALIS_BIG, BOREALIS_SMALL, LOCAL, TERRA]);
    const marker = `door-picker-terra-${crypto.randomUUID()}`;
    expect((await ask(await session(s), { model: TERRA, content: marker })).status).toBe(200);
    expect(terraBackend.turnsWith(marker).length).toBe(1);
    expect(backend.turnsWith(marker).length).toBe(0);
  }, 60_000);

  test("nothing of the picker is written into the person's settings file", () => {
    expect(readSettings(s).modelPicker).toBeUndefined();
    expect(readSettings(s).model).toBeUndefined();
  });
});
