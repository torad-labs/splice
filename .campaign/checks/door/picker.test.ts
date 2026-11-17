// door-picker: the `/model` of a `claude` on the door lists the models of signed-in accounts and nothing else. A head
// called with an API key is not in it and still runs when its selector is named, a head whose login is missing is left
// out and named with the reason, and an account signed in while splice runs is in the next session's picker.
// (docs/specs/door-default.md, decision 4.)

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { readSettings, slottedHead } from "../desktop/lib/desktop.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { ask, lane, picker, session, toDoor } from "./lib/claude.ts";

const ASTRA = "claude-astra--gpt-6-astra";
const TERRA = "claude-terra--gpt-6.1-terra";
const BOREALIS_BIG = "claude-borealis--borealis-large";

const logins = `/tmp/claude-1000/door-picker-${process.pid}`;
let backend: CodexBackend;
let borealis: Upstream;
let s: Splice;
let terraPort = 0;

/** The head `terra` and its ChatGPT login: written, it is signed in. */
const terra = () => gptHead({ key: "terra", command: "claude-terra", port: terraPort, backend: backend.url, model: "gpt-6.1-terra", dir: logins });

/** The selectors the next session's picker lists. */
async function listed(): Promise<string[]> {
  return (picker(await session(s)) ?? []).map((r) => r.model);
}

beforeAll(async () => {
  backend = CodexBackend.start([
    { slug: "gpt-6-astra", tiers: [] },
    { slug: "gpt-6.1-terra", tiers: [] },
  ]);
  borealis = new Upstream("borealis", "borealis-large").start();
  terraPort = await freePort();
  const toml =
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
    slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" }) +
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
  borealis?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("door-picker", () => {
  test("the picker lists the models of signed-in accounts, in place of Claude Code's own", async () => {
    await until("the signed-out head leaves the picker", async () => !(await listed()).includes(TERRA));
    const rows = picker(await session(s));
    expect(rows).not.toBeNull();
    expect(rows!.map((r) => r.model)).toEqual([ASTRA]);
    expect(rows![0]!.label ?? "").not.toBe("");
    expect(rows![0]!.description ?? "").toContain("claude-astra");
    expect(rows![0]!.description ?? "").toMatch(/splice/i);
    expect(lane(s).models).toEqual([{ model: ASTRA, label: rows![0]!.label!, head: "astra", command: "claude-astra" }]);
  });

  test("a session starts on a model the picker lists, and its first turn runs there", async () => {
    const x = await session(s);
    expect(lane(s).default_model).toBe(ASTRA);
    expect(x.sets.ANTHROPIC_MODEL).toBe(ASTRA);
    const marker = `door-picker-first-${crypto.randomUUID()}`;
    const t = await ask(x, { content: marker });
    expect(t.status).toBe(200);
    expect(backend.turnsWith(marker).length).toBe(1);
  });

  test("a head called with an API key is not in the picker, and its selector still runs when it is named", async () => {
    const x = await session(s);
    expect((picker(x) ?? []).some((r) => r.model.startsWith("claude-borealis--"))).toBe(false);
    const t = await ask(x, { model: BOREALIS_BIG });
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
    expect(await listed()).toEqual([ASTRA, TERRA]);
    const marker = `door-picker-terra-${crypto.randomUUID()}`;
    expect((await ask(await session(s), { model: TERRA, content: marker })).status).toBe(200);
    expect(backend.turnsWith(marker).length).toBe(1);
  }, 60_000);

  test("nothing of the picker is written into the person's settings file", () => {
    expect(readSettings(s).modelPicker).toBeUndefined();
    expect(readSettings(s).model).toBeUndefined();
  });
});
