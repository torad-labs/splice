// capability-dialects: a capability's text goes into the first user message in each dialect's own shape. On a head
// that speaks OpenAI's Chat Completions and on one that speaks the Responses API, the provider gets the text after
// what the person typed, in the conversation's opening user turn, and gets the same opening on every later turn.
// (docs/specs/harness.md, decision 5.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, FAST, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { KEYS, Worker, capability, first, second, send } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

interface Seen {
  body: Record<string, unknown> | null;
}

let chat: Upstream;
let backend: CodexBackend;
let worker: Worker;
let s: Splice;
const on = {} as Record<"chat" | "gpt", Launched>;
const off = {} as Record<"chat" | "gpt", Launched>;

beforeAll(async () => {
  chat = new Upstream("chat", "chat-large").start();
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [FAST] }]);
  worker = new Worker().start();
  worker.text = (a) => `What the worker found for ${a.body.session}: src/main.ts:10-42 holds the entry point.`;
  const logins = `/tmp/claude-1000/harness-dialects-${process.pid}`;
  s = await Splice.boot(
    mockHead({ key: "chat", command: "claude-chat", port: await freePort(), url: chat.url, model: "chat-large" }) +
      mockHead({ key: "chatoff", command: "claude-chatoff", port: await freePort(), url: chat.url, model: "chat-large" }) +
      gptHead({ key: "gpt", command: "claude-gpt", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      gptHead({ key: "gptoff", command: "claude-gptoff", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      capability("brief", { heads: ["chat", "gpt"], url: worker.url }),
    { ...KEYS, ...gptEnv(backend.url) },
  );
  on.chat = await launch(s, "claude-chat");
  off.chat = await launch(s, "claude-chatoff");
  on.gpt = await launch(s, "claude-gpt");
  off.gpt = await launch(s, "claude-gptoff");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  chat?.stop();
  backend?.stop();
  worker?.stop();
});

/** The conversation's opening user turn as the provider got it: its user items up to the first that is not one. */
function opening(seen: Seen | undefined): string {
  const list = ((seen?.body?.messages ?? seen?.body?.input) as { role?: string }[] | undefined) ?? [];
  const from = list.findIndex((m) => m.role === "user");
  if (from < 0) return "";
  let to = from;
  while (to < list.length && list[to]!.role === "user") to++;
  return JSON.stringify(list.slice(from, to));
}

const DIALECTS: { what: string; key: "chat" | "gpt"; seen: (marker: string) => Seen[] }[] = [
  { what: "Chat Completions", key: "chat", seen: (marker) => chat.completions().filter((c) => JSON.stringify(c.body).includes(marker)) },
  { what: "the Responses API", key: "gpt", seen: (marker) => backend.turnsWith(marker) },
];

describe("capability-dialects", () => {
  for (const d of DIALECTS) {
    test(`${d.what}: the text follows what the person typed in the opening user turn, and stays there`, async () => {
      const marker = crypto.randomUUID().slice(0, 8);
      const { session, mine, sent } = await first(on[d.key], `Add a --json flag to the status command (${marker}).`);
      expect(sent.status).toBe(200);
      expect(sent.error).toBeNull();
      expect(worker.of(session).length).toBe(1);
      const text = worker.text(worker.of(session)[0]!);

      expect(d.seen(marker).length).toBe(1);
      const one = opening(d.seen(marker)[0]);
      expect(one).toContain(text);
      expect(one.indexOf(marker)).toBeGreaterThan(-1);
      expect(one.indexOf(marker)).toBeLessThan(one.indexOf(text));

      expect((await send(on[d.key], session, second(mine))).status).toBe(200);
      expect(d.seen(marker).length).toBe(2);
      expect(opening(d.seen(marker)[1])).toBe(one);
      expect(worker.of(session).length).toBe(1);
    }, 60_000);

    test(`${d.what}: a head the capability is not on for sends what it sends today`, async () => {
      const marker = crypto.randomUUID().slice(0, 8);
      const before = worker.asked.length;
      const { sent } = await first(off[d.key], `Add a --json flag to the status command (${marker}).`);
      expect(sent.status).toBe(200);
      expect(worker.asked.length).toBe(before);
      const one = opening(d.seen(marker)[0]);
      expect(one).toContain(marker);
      expect(one).not.toContain("What the worker found");
    }, 60_000);
  }
});
