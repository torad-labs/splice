// adds-turn: a `claude` session on its head runs the model each request names. A Claude model goes to Anthropic with
// the caller's own login, as it always did. A selector runs on its account's head with that head's login, and the
// caller's Claude login never reaches it. A session moves between the two and keeps its conversation.
// (docs/specs/door-default.md, decision 9.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { rmSync } from "node:fs";
import { rows } from "../desktop/lib/desktop.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort, mockHead, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { CALLER_TOKEN, carriesLogin, claudeHead, count, turn } from "./lib/adds.ts";
import { type Session, session } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const OPUS = "claude-opus-5-5";
const HAIKU = "claude-haiku-5-5";
const ASTRA = "claude-astra--gpt-6-astra";
const BOREALIS = "claude-borealis--borealis-large";

const logins = `/tmp/claude-1000/adds-turn-${process.pid}`;
let anthropic: Provider;
let backend: CodexBackend;
let borealis: Upstream;
let s: Splice;
let x: Session;

/** A word no other test's turns carry. */
const mark = () => `mark-${crypto.randomUUID().slice(0, 8)}`;

/** The requests Anthropic saw that carry `marker`. */
const atAnthropic = (marker: string) => anthropic.turns.filter((t) => t.raw.includes(marker));

/** The requests the API-key head's provider saw that carry `marker`. */
const atBorealis = (marker: string) => borealis.completions().filter((c) => JSON.stringify(c.body).includes(marker));

beforeAll(async () => {
  anthropic = new Provider("anthropic").start();
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  borealis = new Upstream("borealis", "borealis-large").start();
  const toml =
    claudeHead({ port: await freePort(), url: anthropic.url }) +
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
    mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, model: "borealis-large" });
  s = await Splice.create(toml, gptEnv(backend.url));
  await s.start();
  x = await session(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  anthropic?.stop();
  backend?.stop();
  borealis?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("adds-turn", () => {
  test("a turn that names a Claude model goes to Anthropic with the caller's own login, as it always did", async () => {
    const m = mark();
    const a = await turn(x, OPUS, { say: `Say OK. ${m}` });
    expect(a.status).toBe(200);
    expect(a.text).toBe(anthropic.reply());
    const seen = atAnthropic(m);
    expect(seen.length).toBe(1);
    expect(seen[0]!.body.model).toBe(OPUS);
    expect(seen[0]!.headers.authorization).toBe(`Bearer ${CALLER_TOKEN}`);
    expect(backend.turnsWith(m)).toEqual([]);
  });

  test("a turn that names a selector runs on that account's head, and Anthropic never sees it", async () => {
    const m = mark();
    const a = await turn(x, ASTRA, { say: `Say OK. ${m}` });
    expect(a.status).toBe(200);
    expect(a.text).toBe("OK");
    expect(backend.turnsWith(m).length).toBe(1);
    expect(backend.turnsWith(m)[0]!.body?.model).toBe("gpt-6-astra");
    expect(atAnthropic(m)).toEqual([]);
  });

  test("the caller's Claude login never reaches another provider: the head calls its own with its own login", async () => {
    const m = mark();
    expect((await turn(x, ASTRA, { say: `Say OK. ${m}` })).status).toBe(200);
    expect((await turn(x, BOREALIS, { say: `Say OK. ${m}` })).status).toBe(200);
    expect(backend.turnsWith(m).length).toBe(1);
    expect(atBorealis(m).length).toBe(1);
    expect(carriesLogin(backend.seen)).toBe(false);
    expect(carriesLogin(borealis.seen)).toBe(false);
    expect(backend.turnsWith(m)[0]!.headers.authorization ?? "").toMatch(/^Bearer .+/);
    expect(JSON.stringify(backend.seen.map((r) => r.body))).not.toContain(CALLER_TOKEN);
    expect(JSON.stringify(borealis.seen.map((r) => r.body))).not.toContain(CALLER_TOKEN);
  });

  test("a selector of a head that is not in the picker still runs when named", async () => {
    const m = mark();
    const a = await turn(x, BOREALIS, { say: `Say OK. ${m}` });
    expect(a.status).toBe(200);
    expect(a.text).toContain("from-borealis");
    expect(atBorealis(m).length).toBe(1);
    expect(atAnthropic(m)).toEqual([]);
  });

  test("a name that starts as a head's selectors do and names no model of it is refused by name, with nothing sent anywhere", async () => {
    const m = mark();
    const asked = "claude-astra--gpt-9-nowhere";
    const a = await turn(x, asked, { say: `Say OK. ${m}` });
    expect(a.status).toBeGreaterThanOrEqual(400);
    expect(a.status).toBeLessThan(500);
    expect(a.body).toContain(asked);
    expect(atAnthropic(m)).toEqual([]);
    expect(backend.turnsWith(m)).toEqual([]);
    expect(atBorealis(m)).toEqual([]);
  });

  test("any other name goes to Anthropic as before, one splice has never heard of included", async () => {
    const m = mark();
    const asked = "claude-nova-9-1";
    const a = await turn(x, asked, { say: `Say OK. ${m}` });
    expect(a.status).toBe(200);
    expect(atAnthropic(m).length).toBe(1);
    expect(atAnthropic(m)[0]!.body.model).toBe(asked);
  });

  test("a relayed turn is its own head's row, with the model that ran, and says it came through claude", async () => {
    const id = crypto.randomUUID();
    expect((await turn(x, ASTRA, { session: id })).status).toBe(200);
    let found: Awaited<ReturnType<typeof rows>> = [];
    await until("the turn's row on its head", async () => (found = await rows(s, "astra", id)).length > 0);
    expect(found.length).toBe(1);
    expect(found[0]!.door).toBe("claude");
    expect(String(found[0]!.model)).toContain("gpt-6-astra");
    expect(await rows(s, "claude", id)).toEqual([]);
  });

  test("a turn on a Claude model is the claude head's row, and carries no door", async () => {
    const id = crypto.randomUUID();
    expect((await turn(x, OPUS, { session: id })).status).toBe(200);
    let found: Awaited<ReturnType<typeof rows>> = [];
    await until("the turn's row on the claude head", async () => (found = await rows(s, "claude", id)).length > 0);
    expect(found.length).toBe(1);
    expect(found[0]!.door ?? null).toBeNull();
    expect(await rows(s, "astra", id)).toEqual([]);
  });

  test("a session moves from Claude to a selector and back, and each model is handed the conversation so far", async () => {
    const id = crypto.randomUUID();
    const [first, second, third] = [mark(), mark(), mark()];
    const opened = { role: "user", content: `Read the notes. ${first}` };
    expect((await turn(x, OPUS, { session: id, messages: [opened] })).status).toBe(200);

    // what Claude left in the conversation: its thinking, signed by Anthropic, a tool call and the tool's result
    const fromClaude = [
      opened,
      {
        role: "assistant",
        content: [
          { type: "thinking", thinking: "The notes are in notes.md.", signature: "EuYBCkQYAiJAanthropicsigned" },
          { type: "text", text: "Reading the notes." },
          { type: "tool_use", id: "toolu_01AddsTurnCheck", name: "Read", input: { file_path: "notes.md" } },
        ],
      },
      { role: "user", content: [{ type: "tool_result", tool_use_id: "toolu_01AddsTurnCheck", content: "the notes say: carry on" }, { type: "text", text: `Now summarise them. ${second}` }] },
    ];
    const onAstra = await turn(x, ASTRA, { session: id, messages: fromClaude });
    expect(onAstra.status).toBe(200);
    expect(onAstra.text).toBe("OK");
    const relayed = backend.turnsWith(second);
    expect(relayed.length).toBe(1);
    const handed = JSON.stringify(relayed[0]!.body);
    expect(handed).toContain(first);
    expect(handed).toContain("the notes say: carry on");

    const back = [...fromClaude, { role: "assistant", content: [{ type: "text", text: onAstra.text }] }, { role: "user", content: `And one line more. ${third}` }];
    expect((await turn(x, OPUS, { session: id, messages: back })).status).toBe(200);
    const again = atAnthropic(third);
    expect(again.length).toBe(1);
    expect(again[0]!.body.model).toBe(OPUS);
    expect(again[0]!.raw).toContain(first);
    expect(again[0]!.raw).toContain(second);
    expect(again[0]!.headers.authorization).toBe(`Bearer ${CALLER_TOKEN}`);
    expect(backend.turnsWith(third)).toEqual([]);
  });

  test("in a session that is on a selector, a request that names a Claude model still goes to Anthropic", async () => {
    const id = crypto.randomUUID();
    const m = mark();
    expect((await turn(x, ASTRA, { session: id, say: `Say OK. ${m}` })).status).toBe(200);
    // the small model Claude Code names for a title, in the same session
    expect((await turn(x, HAIKU, { session: id, say: `Write a title. ${m}`, stream: false })).status).toBe(200);
    const small = atAnthropic(m);
    expect(small.length).toBe(1);
    expect(small[0]!.body.model).toBe(HAIKU);
    expect(backend.turnsWith(m).length).toBe(1);
  });

  test("count_tokens for a selector is answered by its head, with a count", async () => {
    const c = await count(x, ASTRA, Array.from({ length: 400 }, (_, i) => `word${i}`).join(" "));
    expect(c.status).toBe(200);
    expect(c.input_tokens).toBeGreaterThan(0);
  });
});
