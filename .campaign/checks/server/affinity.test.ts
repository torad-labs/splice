// server-affinity: a Claude Code session's turns (by `X-Claude-Code-Session-Id`) go to the upstream that served its
// last turn while that upstream is healthy, so the server's prefix cache stays warm, even when another upstream has
// less in flight. New sessions are still balanced. A session whose upstream goes down moves once, and stays where it
// moved when the old one comes back.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { until, type Turn } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";
import { Upstream } from "./lib/upstream.ts";

const HEALTH_S = 1;
let srv: Server;
let b: Upstream;
let key = "";

beforeAll(async () => {
  b = new Upstream("b", MODEL).start();
  srv = await Server.boot({
    upstream: { prefillMs: 1500 },
    head: (aUrl, port) => `
[providers.mock]
dialect = "openai-chat"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
health_seconds = ${HEALTH_S}

[[providers.mock.upstreams]]
name = "a"
url = "${aUrl}"

[[providers.mock.upstreams]]
name = "b"
url = "${b.url}"

[[providers.mock.models]]
id = "${MODEL}"
label = "${MODEL}"
context_window = 131072

[heads.mock]
provider = "mock"
port = ${port}
discovery_prefix = "claude-mock--"
pinned_model = "${MODEL}"
[heads.mock.claude]
command = "${COMMAND}"
`,
  });
  b.behaviour.prefillMs = 1500;
  key = srv.mint("ann@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
  b?.stop();
}, 60_000);

interface Up {
  name: string;
  healthy: boolean;
}
const healthOf = async (name: string) =>
  ((await (await srv.s.api("/api/server/upstreams")).json()) as { upstreams: Up[] }).upstreams.find((u) => u.name === name)?.healthy;

/** One turn of [session]. */
const inSession = (session: string): Promise<Turn> => srv.turn(key, { headers: { "x-claude-code-session-id": session } });

/** Which upstream answered [t]. */
const servedBy = (t: Turn): string => {
  expect(t.status).toBe(200);
  const m = /from-([ab])/.exec(t.reply);
  if (!m) throw new Error(`no upstream named in ${JSON.stringify(t.reply)}`);
  return m[1]!;
};

test("new sessions are balanced, and each session's later turns stay on the upstream that served it", async () => {
  const sessions = ["s1", "s2", "s3", "s4"];
  const first = await Promise.all(sessions.map(inSession));
  const home = Object.fromEntries(sessions.map((s, i) => [s, servedBy(first[i]!)]));
  expect(new Set(Object.values(home)).size).toBe(2);
  for (let round = 0; round < 3; round++) {
    const again = await Promise.all(sessions.map(inSession));
    sessions.forEach((s, i) => expect(servedBy(again[i]!)).toBe(home[s]!));
  }
}, 180_000);

test("a session stays on its upstream even when the other one has less in flight", async () => {
  const sessions = ["t1", "t2", "t3"];
  const home: Record<string, string> = {};
  for (const s of sessions) home[s] = servedBy(await inSession(s));
  const counts = { a: 0, b: 0 } as Record<string, number>;
  for (const s of sessions) counts[home[s]!]! += 1;
  const busy = counts.a! >= 2 ? "a" : "b";
  const together = sessions.filter((s) => home[s] === busy);
  expect(together.length).toBeGreaterThanOrEqual(2);
  const turns = await Promise.all(together.map(inSession));
  for (const t of turns) expect(servedBy(t)).toBe(busy);
}, 180_000);

test("a session whose upstream goes down moves once, and stays where it moved when the old one is back", async () => {
  const s = "mover";
  const was = servedBy(await inSession(s));
  const other = was === "a" ? "b" : "a";
  const down = was === "a" ? srv.up : b;
  down.behaviour.healthy = false;
  try {
    await until(`${was} reads unhealthy`, async () => (await healthOf(was)) === false, (HEALTH_S + 5) * 1000);
    expect(servedBy(await inSession(s))).toBe(other);
  } finally {
    down.behaviour.healthy = true;
  }
  await until(`${was} reads healthy`, async () => (await healthOf(was)) === true, (HEALTH_S + 5) * 1000);
  for (let i = 0; i < 3; i++) expect(servedBy(await inSession(s))).toBe(other);
}, 180_000);
