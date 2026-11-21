// cp-command: a team's wrapper starts Claude Code on the door through a command of its own, declared in splice.toml as
// `[door.commands.<name>]` and linked like every command splice launches. A session of it starts on its head's pinned
// model and its /model lists that head's models (`models = "head"`, the default) or the door's whole picker
// (`models = "door"`). It carries a door key of its own. The person's plain `claude` lane, its starting model and its
// picker, do not move. Claude Code's side calls in such a session (titles, a subagent naming a tier) run on the
// command's head, as its model_slots say, even before the session names a model. A `--settings` the wrapper passes is
// merged into the one overlay splice hands Claude Code, beside splice's picker and /login hook, instead of replacing it.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { slottedHead } from "../desktop/lib/desktop.ts";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, lane, picker, session, toDoor } from "../door/lib/claude.ts";
import { MODEL, recipeWith, settingsOf } from "./lib/team.ts";

setDefaultTimeout(120_000);

const OTHER = "claude-other--other-model";
const BIG = `claude-acme--${MODEL}`;
const SMALL = "claude-acme--glm-mini";
const HAIKU = "anthropic.claude-haiku-5-5";
const OPUS = "anthropic.claude-opus-5-5";

let other: Upstream;
let team: Upstream;
let s: Splice;

/** The model the upstream ran the turn carrying `marker` as, or null when it ran none. */
function ranAs(u: Upstream, marker: string): string | null {
  const seen = u.completions().filter((c) => JSON.stringify(c.body?.messages ?? []).includes(marker));
  return seen.length ? String(seen.at(-1)!.body?.model) : null;
}

beforeAll(async () => {
  other = new Upstream("other", "other-model").start();
  team = new Upstream("acme", MODEL).start();
  // the person's own provider comes first, so the plain claude lane starts on it
  const toml =
    mockHead({ key: "other", command: "claude-other", port: await freePort(), url: other.url, model: "other-model" }) +
    slottedHead({ key: "acme", command: "claude-acme-head", port: await freePort(), url: team.url, big: MODEL, small: "glm-mini" }) +
    `
[door.commands.claude-acme]
head = "acme"

[door.commands.claude-acme-all]
head = "acme"
models = "door"
`;
  s = await Splice.boot(toml);
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  other?.stop();
  team?.stop();
});

describe("cp-command", () => {
  test("a session of the command is on the door, starts on its head's model, and lists that head's models alone", async () => {
    const x = await session(s, "claude-acme");
    expect(x.url).toBe((await session(s)).url);
    expect(x.sets.CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(x.sets.ANTHROPIC_MODEL).toBe(BIG);
    expect((picker(x) ?? []).map((r) => r.model)).toEqual([BIG, SMALL]);
    const t = await ask(x, { model: BIG });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(team.reply());
  });

  test("models = door lists the door's whole picker", async () => {
    const x = await session(s, "claude-acme-all");
    expect(x.sets.ANTHROPIC_MODEL).toBe(BIG);
    expect((picker(x) ?? []).map((r) => r.model)).toEqual((picker(await session(s)) ?? []).map((r) => r.model));
  });

  test("the person's plain claude lane does not move", async () => {
    await session(s, "claude-acme");
    expect(lane(s).default_model).toBe(OTHER);
    const plain = await session(s);
    expect(plain.sets.ANTHROPIC_MODEL).toBe(OTHER);
    expect((picker(plain) ?? []).map((r) => r.model)).toContain(OTHER);
  });

  test("the command carries a door key of its own, and both keys open the door", async () => {
    const x = await session(s, "claude-acme");
    const plain = await session(s);
    expect(x.key).not.toBe("");
    expect(x.key).not.toBe(plain.key);
    expect((await ask(x, { model: BIG })).status).toBe(200);
    expect((await ask(plain, { model: OTHER })).status).toBe(200);
  });

  test("a side call before the session names a model runs on the command's head, as its haiku slot", async () => {
    const x = await session(s, "claude-acme");
    const marker = `cp-command-title-${crypto.randomUUID()}`;
    const t = await ask(x, { model: HAIKU, content: marker });
    expect(t.status).toBe(200);
    expect(ranAs(team, marker)).toBe("glm-mini");
    expect(ranAs(other, marker)).toBeNull();
  });

  test("in the plain claude lane the same side call still runs on the lane's own model", async () => {
    const marker = `cp-command-plain-${crypto.randomUUID()}`;
    const t = await ask(await session(s), { model: HAIKU, content: marker });
    expect(t.status).toBe(200);
    expect(ranAs(team, marker)).toBeNull();
    expect(ranAs(other, marker)).not.toBeNull();
  });

  test("a subagent naming a tier in a session of the command runs on its head", async () => {
    const x = await session(s, "claude-acme");
    const id = crypto.randomUUID();
    expect((await ask(x, { model: BIG, session: id })).status).toBe(200);
    const marker = `cp-command-agent-${crypto.randomUUID()}`;
    const t = await ask(x, { model: OPUS, session: id, content: marker });
    expect(t.status).toBe(200);
    expect(ranAs(team, marker)).toBe(MODEL);
  });

  test("a --settings the wrapper passes is merged into the one overlay, beside splice's picker and /login hook", async () => {
    const dir = mkdtempSync(join(tmpdir(), "cp-command-"));
    const file = join(dir, "wrapper.json");
    writeFileSync(file, JSON.stringify({ hooks: { SessionStart: [{ hooks: [{ type: "command", command: "acme-ai session-start" }] }] } }));
    const inline = JSON.stringify({
      env: { OTEL_EXPORTER_OTLP_ENDPOINT: "https://otel.acme.example" },
      otelHeadersHelper: "acme-ai otel-headers",
    });
    const r = await recipeWith(s, "claude-acme", ["--settings", inline, `--settings=${file}`, "--verbose"]);
    const all = settingsOf(r.argv);
    expect(all.length).toBe(1);
    const one = all[0]!;
    expect((one.env as Record<string, string>).OTEL_EXPORTER_OTLP_ENDPOINT).toBe("https://otel.acme.example");
    expect(one.otelHeadersHelper).toBe("acme-ai otel-headers");
    const hooks = one.hooks as Record<string, { hooks: { command: string }[] }[]>;
    expect(JSON.stringify(hooks.SessionStart ?? [])).toContain("acme-ai session-start");
    expect((hooks.UserPromptSubmit ?? []).length).toBeGreaterThan(0);
    const rows = (one.modelPicker as { options?: { model: string }[] } | undefined)?.options ?? [];
    expect(rows.map((o) => o.model)).toEqual([BIG, SMALL]);
    expect(r.argv).toContain("--verbose");
  });

  test("a wrapper's settings cannot take the session off the door or replace the door's picker", async () => {
    const inline = JSON.stringify({
      env: { CLAUDE_CODE_USE_MANTLE: "0", ANTHROPIC_BEDROCK_MANTLE_BASE_URL: "https://elsewhere.example" },
      modelPicker: { replaceBuiltInOptions: true, options: [{ model: "elsewhere-model" }] },
    });
    const r = await recipeWith(s, "claude-acme", ["--settings", inline]);
    const one = settingsOf(r.argv)[0]!;
    const env = { ...r.env, ...((one.env as Record<string, string> | undefined) ?? {}) };
    expect(env.CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL).toBe((await session(s)).url);
    const rows = (one.modelPicker as { options?: { model: string }[] }).options ?? [];
    expect(rows.map((o) => o.model)).toEqual([BIG, SMALL]);
  });
});
