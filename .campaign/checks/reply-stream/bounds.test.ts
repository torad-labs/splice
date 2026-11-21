// live-bounds: what the live route never does. A follower that stops reading never slows a turn: once it is far enough
// behind its response is ended, with no further event, and it can connect again. A turn whose client leaves ends for
// its followers as `cut`. Nothing can be sent through the route, and only the control plane serves it: a head's port
// and the door answer it with no stream.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { type Door, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ClientTurn, Follower, SlowAnthropic, anthropicKeyHead } from "./lib/live.ts";

setDefaultTimeout(300_000);

const ASTRA = "claude-astra--astra-large";
const ANT = "claude-ant--claude-opus-5-5";
const BIG = "claude-big--claude-opus-5-5";

/** A provider on Anthropic's Messages API whose every reply is one long text block written as fast as it can go:
 *  [words] distinct words of about 330 characters, so the reply is about 8 MB, many times what a socket that is not
 *  read can hold plus a follower's queue of 4096 events. */
class BigAnthropic {
  readonly words: string[];
  private server: ReturnType<typeof Bun.serve> | null = null;
  private n = 0;

  constructor(words = 24_000) {
    const pad = () => Array.from({ length: 9 }, () => crypto.randomUUID()).join("");
    this.words = Array.from({ length: words }, (_, i) => ` big-${i}-${pad()}`);
  }

  start(): this {
    this.server = Bun.serve({ port: 0, hostname: "127.0.0.1", idleTimeout: 0, fetch: (req) => this.answer(req) });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
  }

  get url(): string {
    return `http://127.0.0.1:${this.server!.port}`;
  }

  text(): string {
    return this.words.join("");
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    const raw = req.method === "POST" ? await req.text() : "";
    let body: Record<string, unknown> | null = null;
    try {
      body = raw ? (JSON.parse(raw) as Record<string, unknown>) : null;
    } catch {}
    if (path.endsWith("/v1/models")) return Response.json({ data: [], has_more: false });
    if (path.endsWith("/v1/messages/count_tokens")) return Response.json({ input_tokens: 42 });
    if (!path.endsWith("/v1/messages")) return Response.json({ type: "error", error: { type: "not_found_error", message: "no route" } }, { status: 404 });
    const id = `msg_big_${++this.n}_${crypto.randomUUID().slice(0, 8)}`;
    const usage = { input_tokens: 12, cache_creation_input_tokens: 0, cache_read_input_tokens: 0, output_tokens: 1 };
    if (body?.stream !== true) {
      return Response.json({ id, type: "message", role: "assistant", model: body?.model, content: [{ type: "text", text: this.text() }], stop_reason: "end_turn", stop_sequence: null, usage });
    }
    const sse = (f: Record<string, unknown>) => `event: ${f.type}\ndata: ${JSON.stringify(f)}\n\n`;
    const words = this.words;
    const enc = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        try {
          controller.enqueue(enc.encode(sse({ type: "message_start", message: { id, type: "message", role: "assistant", model: body?.model, content: [], stop_reason: null, stop_sequence: null, usage } })));
          controller.enqueue(enc.encode(sse({ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } })));
          for (let k = 0; k < words.length; k += 200) {
            controller.enqueue(enc.encode(words.slice(k, k + 200).map((w) => sse({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: w } })).join("")));
            await Bun.sleep(0);
          }
          controller.enqueue(
            enc.encode(
              sse({ type: "content_block_stop", index: 0 }) +
                sse({ type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 40 } }) +
                sse({ type: "message_stop" }),
            ),
          );
          controller.close();
        } catch {}
      },
    });
    return new Response(stream, { headers: { "content-type": "text/event-stream", "cache-control": "no-cache" } });
  }
}

/** A follower that reads the response head and `hello` and then reads nothing more until told to, the way a stalled
 *  reader does: bash holds the socket and reads it byte by byte, so nothing past `hello` leaves the kernel's buffer. */
class StalledFollower {
  out = "";
  exited = false;
  private readonly proc: ReturnType<typeof Bun.spawn>;

  constructor(port: number, session: string, key: string) {
    const script = [
      'exec 3<>"/dev/tcp/127.0.0.1/$PORT" || exit 3',
      "printf 'GET /api/sessions/%s/live HTTP/1.1\\r\\nHost: 127.0.0.1:%s\\r\\nAuthorization: Bearer %s\\r\\nAccept: text/event-stream\\r\\nConnection: close\\r\\n\\r\\n' \"$SESSION\" \"$PORT\" \"$KEY\" >&3",
      'while IFS= read -r -t 30 line <&3; do printf "%s\\n" "$line"; case "$line" in "event: hello"*) break;; esac; done',
      'IFS= read -r -t 5 line <&3 && printf "%s\\n" "$line"',
      "echo READY",
      "read -r _",
      "cat <&3",
    ].join("\n");
    const proc = Bun.spawn(["bash", "-c", script], {
      env: { ...process.env, PORT: String(port), SESSION: session, KEY: key },
      stdin: "pipe",
      stdout: "pipe",
      stderr: "ignore",
    });
    this.proc = proc;
    void (async () => {
      const dec = new TextDecoder();
      for await (const chunk of proc.stdout) this.out += dec.decode(chunk, { stream: true });
    })();
    void proc.exited.then(() => (this.exited = true));
  }

  /** Start reading again: whatever is left on the socket is read until the server ends the response. */
  resume(): void {
    const stdin = this.proc.stdin as import("bun").FileSink;
    stdin.write("\n");
    stdin.flush();
  }

  kill(): void {
    this.proc.kill();
  }
}

let astra: Upstream;
let ant: SlowAnthropic;
let big: BigAnthropic;
let s: Splice;
let d: Door;
const ports: Record<string, number> = {};
const followers: Follower[] = [];

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  ant = new SlowAnthropic("ant", { gapMs: 150, thoughts: 4, words: 40 }).start();
  big = new BigAnthropic().start();
  ports.astra = await freePort();
  ports.ant = await freePort();
  ports.big = await freePort();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: ports.astra, url: astra.url, big: "astra-large", small: "astra-small" }) +
      anthropicKeyHead({ key: "ant", command: "claude-ant", port: ports.ant, url: ant.url, model: "claude-opus-5-5" }) +
      anthropicKeyHead({ key: "big", command: "claude-big", port: ports.big, url: big.url, model: "claude-opus-5-5", window: 1_000_000 }),
  );
  d = turnOn(s, "--model", ASTRA);
}, 900_000);

afterAll(async () => {
  for (const f of followers) f.close();
  await s?.dispose();
  astra?.stop();
  ant?.stop();
  big?.stop();
});

async function follow(session: string): Promise<Follower> {
  const f = await Follower.open(s, session);
  followers.push(f);
  return f;
}

async function timed(t: ClientTurn, ms: number): Promise<number> {
  const t0 = Date.now();
  let timer: ReturnType<typeof setTimeout> | undefined;
  await Promise.race([t.done, new Promise((_, reject) => (timer = setTimeout(() => reject(new Error(`the turn did not finish in ${ms} ms`)), ms)))]);
  clearTimeout(timer);
  return Date.now() - t0;
}

describe("live-bounds", () => {
  test("a follower that stops reading never slows a turn: it is ended, with no further event, and can connect again", async () => {
    // how long the reply takes with nobody following
    const alone = ClientTurn.send(d.url, d.key, { model: BIG, session: crypto.randomUUID(), content: "write it all" });
    const baseline = await timed(alone, 240_000);
    expect(alone.status).toBe(200);
    expect(alone.textDeltas().join("")).toBe(big.text());

    const session = crypto.randomUUID();
    const stalled = new StalledFollower(s.controlPort, session, s.mgmtKey());
    try {
      await until(`the stalled follower has its hello (so far: ${JSON.stringify(stalled.out.slice(0, 400))})`, () => stalled.out.includes("READY"), 30_000);
      expect(stalled.out.split("\n")[0]).toMatch(/^HTTP\/1\.1 200/);
      expect(stalled.out).toContain("event: hello");

      // the same reply with a follower that reads nothing: the client still gets all of it, about as fast
      const t = ClientTurn.send(d.url, d.key, { model: BIG, session, content: "write it all" });
      const took = await timed(t, baseline * 3 + 30_000);
      expect(t.status).toBe(200);
      expect(t.textDeltas().join("")).toBe(big.text());
      expect(took).toBeLessThan(baseline * 3 + 30_000);

      // the stalled follower's response was ended: reading again finds the end of it, without the turn's `end`
      const before = stalled.out.length;
      stalled.resume();
      await until("the stalled follower's response ends", () => stalled.exited, 60_000);
      const after = stalled.out.slice(before);
      expect(after).not.toContain("event: end");
      expect((after.match(/event: text/g) ?? []).length).toBeLessThan(t.textDeltas().length);
    } finally {
      stalled.kill();
    }

    // and it can connect again
    const again = await follow(session);
    expect(again.status).toBe(200);
    expect(await again.hello()).toEqual({ session, live: [] });
  });

  test("a turn whose client leaves ends for its followers as cut", async () => {
    const session = crypto.randomUUID();
    const f = await follow(session);
    await f.hello();
    const t = ClientTurn.send(d.url, d.key, { model: ANT, session, content: "a long answer, please" });
    await until("the client has some of the text", () => t.textDeltas().length >= 3, 60_000);
    const message = t.message()!;
    t.abort();
    await t.done;
    const turn = await f.turnOf(message);
    const id = String(turn.turn);
    expect(turn.compact).toBe(false);
    expect(await f.endOf(id, 30_000)).toEqual({ turn: id, stop_reason: null, error: "cut" });
    expect(f.of(id).filter((e) => e.event === "end").length).toBe(1);
    expect(f.ended).toBe(false);
  });

  test("nothing can be sent through the route", async () => {
    const url = `http://127.0.0.1:${s.controlPort}/api/sessions/${crypto.randomUUID()}/live`;
    for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
      const r = await fetch(url, { method, headers: { authorization: `Bearer ${s.mgmtKey()}`, "content-type": "application/json" }, body: JSON.stringify({ text: "hi" }) });
      await r.body?.cancel();
      expect(r.status === 404 || r.status === 405).toBe(true);
    }
  });

  test("only the control plane serves it: a head's port and the door answer with no stream", async () => {
    const path = `/api/sessions/${crypto.randomUUID()}/live`;
    const bases = [d.url, ...Object.values(ports).map((p) => `http://127.0.0.1:${p}`)];
    for (const base of bases) {
      for (const key of [s.mgmtKey(), d.key]) {
        const ac = new AbortController();
        const timer = setTimeout(() => ac.abort(), 5_000);
        const r = await fetch(`${base}${path}`, { headers: { authorization: `Bearer ${key}`, accept: "text/event-stream" }, signal: ac.signal }).catch(() => null);
        clearTimeout(timer);
        if (!r) continue;
        const type = r.headers.get("content-type") ?? "";
        await r.body?.cancel().catch(() => {});
        expect(r.status === 200 && type.includes("event-stream")).toBe(false);
      }
    }
  });
});
