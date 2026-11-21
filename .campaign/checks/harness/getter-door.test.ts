// getter-door: a session on the door (docs/specs/door-default.md) is gathered for on the head that holds its waiting
// turn. The gatherer names a selector of that head, so the door sends its requests there and to no other head, on that
// head's fast model, and what it found reaches that head's provider in the session's first user message.
// (docs/specs/harness.md, decision 9.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { slottedHead } from "../desktop/lib/desktop.ts";
import { type Session, session, toDoor } from "../door/lib/claude.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { KEYS, type Message, opening } from "./lib/capability.ts";
import { Fake, contextGetter, folder, register } from "./lib/gatherer.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let astra: Upstream;
let borealis: Upstream;
let fake: Fake;
let s: Splice;
let x: Session;
let n = 0;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  borealis = new Upstream("borealis", "borealis-large").start();
  fake = new Fake();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" }) +
      contextGetter(["astra", "borealis"]),
    { ...KEYS, PATH: fake.path },
  );
  toDoor(s);
  x = await session(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  borealis?.stop();
  fake?.dispose();
});

afterEach(() => fake.reset());

/** A first turn through the door, as the terminal's Claude Code sends it in Mantle mode: the door's address and key,
 *  the model the person picked, tools, and the first message as the person typed it. */
async function doorTurn(model: string, id: string, messages: Message[]): Promise<{ status: number; body: string }> {
  const user = JSON.stringify({ device_id: "c0ffee".repeat(11).slice(0, 64), account_uuid: "", session_id: id });
  const r = await fetch(`${x.url}/v1/messages?beta=true`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "anthropic-version": "2023-06-01",
      authorization: `Bearer ${x.key}`,
      "user-agent": "claude-cli/2.1.326 (external, cli)",
      "x-app": "cli",
      "x-claude-code-session-id": id,
    },
    body: JSON.stringify({
      model,
      max_tokens: 1024,
      stream: true,
      metadata: { user_id: user },
      tools: [{ name: "Read", description: "Read a file.", input_schema: { type: "object", properties: { file_path: { type: "string" } }, required: ["file_path"] } }],
      messages,
    }),
  });
  return { status: r.status, body: await r.text() };
}

/** The completions `up` was asked for whose body holds `text`. */
const holding = (up: Upstream, text: string) => up.completions().filter((c) => JSON.stringify(c.body).includes(text));

describe("getter-door", () => {
  const cases = [
    { head: "borealis", up: () => borealis, other: () => astra, picked: "claude-borealis--borealis-large", fast: "borealis-small" },
    { head: "astra", up: () => astra, other: () => borealis, picked: "claude-astra--astra-large", fast: "astra-small" },
  ];
  for (const c of cases) {
    test(`a door session on ${c.head} is gathered for on ${c.head}: the gatherer names its selector, and its requests reach that head's provider alone`, async () => {
      const typed = `Add a --json flag to the status command (${c.head}).`;
      const id = crypto.randomUUID();
      const cwd = folder(s, `p${++n}`);
      // a door session runs over the person's own Claude folder, where Claude Code registers it
      register(x.dir, id, cwd, typed);
      const sent = await doorTurn(c.picked, id, [{ role: "user", content: opening(typed) }]);
      expect(sent.status).toBe(200);

      await until("the gatherer has run", () => fake.of(typed).length === 1, 30_000);
      const run = fake.of(typed)[0]!;
      expect(run.cwd).toBe(cwd);
      // it names a selector of the head that holds the waiting turn
      expect(run.model ?? "").toStartWith(`claude-${c.head}--`);

      // its request reached that head's provider, on the fast model, and no other head's
      const own = holding(c.up(), `[gatherer ${run.run}]`);
      expect(own.length).toBeGreaterThanOrEqual(1);
      expect(own[0]!.body?.model).toBe(c.fast);
      expect(holding(c.other(), `[gatherer ${run.run}]`).length).toBe(0);

      // what it found reached that head's provider in the session's turn
      const turn = holding(c.up(), typed).filter((t) => !JSON.stringify(t.body).includes("[gatherer "));
      expect(turn.length).toBe(1);
      expect(JSON.stringify(turn[0]!.body)).toContain(`What the gatherer found (run ${run.run})`);
    });
  }
});
