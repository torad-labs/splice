// upstream-pool: one provider spread over several OpenAI-compatible upstreams, health-checked, balanced by the
// least in flight, with a person's preferred upstream and failover when one goes down or refuses a connection.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { until } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";
import { Upstream } from "./lib/upstream.ts";

const HEALTH_S = 1;
let srv: Server;
let b: Upstream;
let bPort = 0;

beforeAll(async () => {
  b = new Upstream("b", MODEL).start();
  bPort = b.port;
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
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
  b?.stop();
}, 60_000);

interface Up {
  provider: string;
  name: string;
  url: string;
  healthy: boolean;
  inflight: number;
}
const upstreams = async () => ((await (await srv.s.api("/api/server/upstreams")).json()) as { upstreams: Up[] }).upstreams;
const healthOf = async (name: string) => (await upstreams()).find((u) => u.name === name)?.healthy;
const burst = async (key: string, n: number) => Promise.all(Array.from({ length: n }, () => srv.turn(key)));

test("the control plane lists each upstream with its health and what it has in flight", async () => {
  const ups = await upstreams();
  expect(ups.map((u) => u.name).sort()).toEqual(["a", "b"]);
  for (const u of ups) {
    expect(u.provider).toBe("mock");
    expect(u.healthy).toBe(true);
    expect(u.inflight).toBe(0);
  }
  expect(ups.find((u) => u.name === "b")!.url).toBe(b.url);
});

test("turns in flight together are spread over the healthy upstreams", async () => {
  const key = srv.mint("ann@example.com").key;
  const turns = await burst(key, 6);
  for (const t of turns) expect(t.status).toBe(200);
  const fromA = turns.filter((t) => t.reply.includes("from-a")).length;
  const fromB = turns.filter((t) => t.reply.includes("from-b")).length;
  expect(fromA).toBeGreaterThanOrEqual(2);
  expect(fromB).toBeGreaterThanOrEqual(2);
}, 120_000);

test("an upstream failing its health check gets no turns until it is healthy again", async () => {
  const key = srv.mint("hal@example.com").key;
  b.behaviour.healthy = false;
  await until("b reads unhealthy", async () => (await healthOf("b")) === false, (HEALTH_S + 5) * 1000);
  const sentToB = b.completions().length;
  for (const t of await burst(key, 4)) {
    expect(t.status).toBe(200);
    expect(t.reply).toContain("from-a");
  }
  expect(b.completions().length).toBe(sentToB);
  b.behaviour.healthy = true;
  await until("b reads healthy", async () => (await healthOf("b")) === true, (HEALTH_S + 5) * 1000);
  const back = await burst(key, 6);
  expect(back.some((t) => t.reply.includes("from-b"))).toBe(true);
}, 120_000);

test("a turn whose upstream refuses the connection is sent to another one, before the health check notices", async () => {
  const key = srv.mint("rob@example.com").key;
  b.stop();
  try {
    for (const t of await burst(key, 4)) {
      expect(t.status).toBe(200);
      expect(t.reply).toContain("from-a");
    }
  } finally {
    b.start(bPort);
  }
  await until("b reads healthy again", async () => (await healthOf("b")) === true, (HEALTH_S + 5) * 1000);
}, 120_000);

test("a person's preferred upstream takes their turns, and another takes them while it is down", async () => {
  expect(srv.cli(["server", "person", "set", "pia@example.com", "--upstream", "b"]).code).toBe(0);
  const pia = srv.mint("pia@example.com").key;
  for (const t of await burst(pia, 3)) expect(t.reply).toContain("from-b");
  b.behaviour.healthy = false;
  await until("b reads unhealthy", async () => (await healthOf("b")) === false, (HEALTH_S + 5) * 1000);
  const t = await srv.turn(pia);
  expect(t.status).toBe(200);
  expect(t.reply).toContain("from-a");
  b.behaviour.healthy = true;
  await until("b reads healthy", async () => (await healthOf("b")) === true, (HEALTH_S + 5) * 1000);
}, 120_000);

test("with every upstream down, a turn is refused soon, saying no upstream is up", async () => {
  const key = srv.mint("zed@example.com").key;
  srv.up.behaviour.healthy = false;
  b.behaviour.healthy = false;
  try {
    await until("both read unhealthy", async () => (await upstreams()).every((u) => !u.healthy), (HEALTH_S + 5) * 1000);
    const t = await srv.turn(key);
    expect(t.status === 503 || t.error !== null).toBe(true);
    expect(t.reply).toBe("");
    expect(`${t.error?.message ?? ""}${t.body}`.toLowerCase()).toContain("upstream");
    expect(t.totalMs).toBeLessThan(30_000);
  } finally {
    srv.up.behaviour.healthy = true;
    b.behaviour.healthy = true;
  }
}, 120_000);
