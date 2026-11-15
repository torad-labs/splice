// server-metrics: the operator watches the gateway live, per person and team: requests by status class, tokens by
// kind, time to first token, duration, prefill and decode speed, stream stalls, and what each person has in flight
// against their cap. Admins scrape it as Prometheus text from the listener; the same series go to the operator's
// OTLP collector. After a restart every person with a live key starts at zero, so a dashboard shows everyone.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { freePort, until } from "./lib/harness.ts";
import { COMMAND, Server } from "./lib/server.ts";

let srv: Server;
let admin = "";
let collector: ReturnType<typeof Bun.serve>;
const pushed: { path: string; type: string; body: Buffer }[] = [];

beforeAll(async () => {
  const otlp = await freePort();
  collector = Bun.serve({
    hostname: "127.0.0.1",
    port: otlp,
    fetch: async (req) => {
      pushed.push({ path: new URL(req.url).pathname, type: req.headers.get("content-type") ?? "", body: Buffer.from(await req.arrayBuffer()) });
      return new Response(new Uint8Array(0), { status: 200, headers: { "content-type": "application/x-protobuf" } });
    },
  });
  srv = await Server.boot({
    server: `admins = ["ops@example.com"]\n`,
    toml: `
[server.limits]
inflight_per_person = 3

[server.teams]
by_domain = { "example.com" = "core" }

[telemetry.operator]
otlp_endpoint = "http://127.0.0.1:${otlp}"
otlp_protocol = "http/protobuf"
otlp_signals = "metrics"
`,
  });
  admin = srv.mint("ops@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
  collector?.stop(true);
}, 60_000);

const scrape = async (key = admin) => {
  const r = await fetch(`${srv.base}/server/metrics`, { headers: { authorization: `Bearer ${key}` } });
  expect(r.status).toBe(200);
  return r.text();
};

/** The values of every sample of `name` whose labels include all of `labels`. */
function samples(text: string, name: string, labels: Record<string, string>): number[] {
  const out: number[] = [];
  for (const line of text.split("\n")) {
    const m = /^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)/.exec(line);
    if (!m || m[1] !== name) continue;
    const have: Record<string, string> = {};
    for (const p of (m[3] ?? "").matchAll(/([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g)) have[p[1]!] = p[2]!;
    if (Object.entries(labels).every(([k, v]) => have[k] === v)) out.push(Number(m[4]));
  }
  return out;
}
const one = (text: string, name: string, labels: Record<string, string>) => {
  const v = samples(text, name, labels);
  if (v.length !== 1) throw new Error(`${v.length} samples of ${name}${JSON.stringify(labels)}`);
  return v[0]!;
};

test("an admin scrapes Prometheus text from the listener; anyone else is refused", async () => {
  const r = await fetch(`${srv.base}/server/metrics`, { headers: { authorization: `Bearer ${admin}` } });
  expect(r.status).toBe(200);
  expect(r.headers.get("content-type")).toContain("text/plain");
  expect((await fetch(`${srv.base}/server/metrics`)).status).toBe(401);
  const someone = srv.mint("sam@example.com").key;
  expect((await fetch(`${srv.base}/server/metrics`, { headers: { authorization: `Bearer ${someone}` } })).status).toBe(403);
});

test("requests by person, team, command and status class; tokens by kind; latency and speed histograms", async () => {
  const ann = srv.mint("ann@example.com").key;
  const bob = srv.mint("bob@example.com").key;
  for (let i = 0; i < 2; i++) expect((await srv.turn(ann)).status).toBe(200);
  srv.up.behaviour.status = 500;
  try {
    await srv.turn(bob);
  } finally {
    srv.up.behaviour.status = null;
  }
  const who = { person: "ann@example.com" };
  await until("ann's two requests", async () => samples(await scrape(), "splice_server_requests_total", { ...who, status_class: "2xx" })[0] === 2, 20_000);
  const t = await scrape();
  expect(one(t, "splice_server_requests_total", { ...who, team: "core", command: COMMAND, status_class: "2xx" })).toBe(2);
  expect(one(t, "splice_server_requests_total", { person: "bob@example.com", status_class: "5xx" })).toBe(1);
  expect(one(t, "splice_server_tokens_total", { ...who, kind: "prompt" })).toBe(2400);
  expect(one(t, "splice_server_tokens_total", { ...who, kind: "cached" })).toBe(400);
  expect(one(t, "splice_server_tokens_total", { ...who, kind: "completion" })).toBe(80);
  for (const h of ["ttft_seconds", "duration_seconds", "prefill_tokens_per_second", "decode_tokens_per_second"]) {
    expect(one(t, `splice_server_${h}_count`, who)).toBe(2);
    expect(samples(t, `splice_server_${h}_bucket`, who).length).toBeGreaterThan(1);
  }
  expect(one(t, "splice_server_prefill_tokens_per_second_sum", who)).toBeCloseTo(4000, -1);
}, 120_000);

test("a stream that stalls records the stall", async () => {
  const cleo = srv.mint("cleo@example.com").key;
  srv.up.behaviour.stallAfter = 2;
  srv.up.behaviour.stallMs = 3_000;
  try {
    expect((await srv.turn(cleo)).status).toBe(200);
  } finally {
    srv.up.behaviour.stallAfter = null;
    srv.up.behaviour.stallMs = 0;
  }
  await until("cleo's stall", async () => (samples(await scrape(), "splice_server_stream_stall_seconds_sum", { person: "cleo@example.com" })[0] ?? 0) >= 2.5, 20_000);
}, 60_000);

test("in flight against the cap, live", async () => {
  const dan = srv.mint("dan@example.com").key;
  srv.up.behaviour.prefillMs = 4_000;
  try {
    const t = srv.turn(dan);
    await Bun.sleep(1_200);
    const during = await scrape();
    expect(one(during, "splice_server_inflight", { person: "dan@example.com" })).toBe(1);
    expect(one(during, "splice_server_inflight_limit", { person: "dan@example.com" })).toBe(3);
    expect((await t).status).toBe(200);
  } finally {
    srv.up.behaviour.prefillMs = 0;
  }
  await until("dan back to 0", async () => samples(await scrape(), "splice_server_inflight", { person: "dan@example.com" })[0] === 0, 10_000);
}, 60_000);

test("the same series reach the operator's OTLP collector, with the person on them", async () => {
  await until(
    "an OTLP metrics push naming ann",
    () => pushed.some((p) => p.path.endsWith("/v1/metrics") && p.body.includes("splice_server_requests_total") && p.body.includes("ann@example.com")),
    150_000,
  );
  expect(pushed.find((p) => p.path.endsWith("/v1/metrics"))!.type).toContain("application/x-protobuf");
}, 180_000);

test("after a restart everyone with a live key reads zero, and a person with none is gone", async () => {
  srv.mint("dora@example.com");
  srv.mint("ex@example.com");
  expect(srv.cli(["server", "key", "revoke-person", "ex@example.com"]).code).toBe(0);
  await srv.s.restart();
  const t = await scrape();
  const dora = samples(t, "splice_server_requests_total", { person: "dora@example.com" });
  expect(dora.length).toBeGreaterThanOrEqual(1);
  expect(dora.every((v) => v === 0)).toBe(true);
  expect(one(t, "splice_server_inflight", { person: "dora@example.com" })).toBe(0);
  expect(samples(t, "splice_server_requests_total", { person: "ann@example.com" }).every((v) => v === 0)).toBe(true);
  expect(t).not.toContain("ex@example.com");
}, 300_000);
