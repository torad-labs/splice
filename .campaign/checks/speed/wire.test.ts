// speed-wire: a session at Fast or Ultrafast asks the ChatGPT backend for its tier the way Codex does, on every
// request of the session (a subagent's, a compaction, a turn that is not streamed), and a standard session asks for
// none.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { CodexBackend, FAST, ULTRAFAST, gptEnv, gptHead, type Seen } from "./lib/codex.ts";
import { ask, launch, prompt, type AskOptions, type Launched } from "./lib/session.ts";

let backend: CodexBackend;
let s: Splice;
let astra: Launched;

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [FAST, ULTRAFAST] }]);
  const logins = `/tmp/claude-1000/speed-wire-${process.pid}`;
  s = await Splice.boot(
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }),
    gptEnv(backend.url),
  );
  astra = await launch(s, "claude-astra");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
});

let n = 0;
/** What reached the backend for one new turn of `session`. */
async function sent(session: string, o: AskOptions = {}): Promise<Seen> {
  const marker = `SPEED-WIRE-${++n}`;
  const r = await ask(astra, session, marker, o);
  expect(r.status).toBe(200);
  const seen = backend.turnsWith(marker);
  expect(seen.length).toBe(1);
  return seen[0]!;
}

describe("speed-wire", () => {
  test("a Fast session asks for priority, and the routing hint names the tier", async () => {
    const one = crypto.randomUUID();
    prompt(astra, one, "/fast");
    const t = await sent(one);
    expect(t.body?.service_tier).toBe("priority");
    expect(t.headers["x-codex-routing-hint"]).toBe("model=gpt-6-astra;tier=priority");
  });

  test("an Ultrafast session asks for ultrafast, and the routing hint names it", async () => {
    const one = crypto.randomUUID();
    prompt(astra, one, "/ultrafast");
    const t = await sent(one);
    expect(t.body?.service_tier).toBe("ultrafast");
    expect(t.headers["x-codex-routing-hint"]).toBe("model=gpt-6-astra;tier=ultrafast");
  });

  test("a standard session asks for no tier at all", async () => {
    const one = crypto.randomUUID();
    const t = await sent(one);
    expect(t.body && "service_tier" in t.body).toBe(false);
    expect(t.headers["x-codex-routing-hint"]).toBe("model=gpt-6-astra");
    prompt(astra, one, "/fast");
    prompt(astra, one, "/fast");
    const back = await sent(one);
    expect(back.body && "service_tier" in back.body).toBe(false);
    expect(back.headers["x-codex-routing-hint"]).toBe("model=gpt-6-astra");
  });

  test("every request of the session carries its tier: a subagent's, a compaction, one not streamed", async () => {
    const one = crypto.randomUUID();
    prompt(astra, one, "/ultrafast");
    for (const o of [{ agent: crypto.randomUUID() }, { compaction: true }, { stream: false }] as AskOptions[]) {
      const t = await sent(one, o);
      expect(t.body?.service_tier).toBe("ultrafast");
      expect(t.headers["x-codex-routing-hint"]).toBe("model=gpt-6-astra;tier=ultrafast");
    }
  });
});
