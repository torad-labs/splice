// telemetry-relay: `[server.telemetry] forward_to` names the operator's OTLP collector. Claude Code on a laptop sends
// its own telemetry to POST /server/otlp/v1/{metrics,logs,traces} with the person's key, as JSON or protobuf. The
// server sets `splice.person`, `splice.team` and `user.email` on every resource, whatever the client claimed, keeps
// everything else, and forwards the request to the collector in the same encoding, without the person's key. The
// profile names the relay. No key is a 401; a server with no relay has no such route. A forward_to that is no http(s)
// URL is a boot finding.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { freePort } from "./lib/harness.ts";
import { jsonBody, protoBody, readJson, readProto, type Res, type Signal } from "./lib/otlp.ts";
import { Server } from "./lib/server.ts";

interface Got {
  path: string;
  type: string;
  headers: Record<string, string>;
  body: Uint8Array;
}

let srv: Server;
let collector: ReturnType<typeof Bun.serve>;
let collectorPort = 0;
let collectorStatus = 200;
const got: Got[] = [];
let ann = "";

const startCollector = () =>
  Bun.serve({
    hostname: "127.0.0.1",
    port: collectorPort,
    fetch: async (req) => {
      got.push({
        path: new URL(req.url).pathname,
        type: req.headers.get("content-type") ?? "",
        headers: Object.fromEntries(req.headers),
        body: new Uint8Array(await req.arrayBuffer()),
      });
      const proto = (req.headers.get("content-type") ?? "").includes("protobuf");
      return proto
        ? new Response(new Uint8Array(0), { status: collectorStatus, headers: { "content-type": "application/x-protobuf" } })
        : Response.json({}, { status: collectorStatus });
    },
  });

beforeAll(async () => {
  collectorPort = await freePort();
  collector = startCollector();
  srv = await Server.boot({
    toml: `
[server.telemetry]
forward_to = "http://127.0.0.1:${collectorPort}"

[server.teams]
by_domain = { "example.com" = "core" }
`,
  });
  ann = srv.mint("ann@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
  collector?.stop(true);
}, 60_000);

const CLAIMS = { "user.email": "mallory@evil.test", "splice.person": "mallory@evil.test", "splice.team": "red" };

const send = (signal: Signal, body: string | Uint8Array, key?: string) =>
  fetch(`${srv.base}/server/otlp/v1/${signal}`, {
    method: "POST",
    headers: {
      "content-type": typeof body === "string" ? "application/json" : "application/x-protobuf",
      ...(key ? { authorization: `Bearer ${key}` } : {}),
    },
    body,
  });

/** The collector's next request after [from], waiting for it a little. */
async function next(from: number): Promise<Got> {
  for (let i = 0; i < 50 && got.length <= from; i++) await Bun.sleep(100);
  expect(got.length).toBeGreaterThan(from);
  return got[from]!;
}

const stamped = (attributes: Record<string, string>) => {
  expect(attributes["splice.person"]).toBe("ann@example.com");
  expect(attributes["user.email"]).toBe("ann@example.com");
  expect(attributes["splice.team"]).toBe("core");
};

test("JSON logs are stamped with the person and their team over what the client claimed, and forwarded as JSON", async () => {
  const from = got.length;
  const res: Res[] = [{ attributes: { "service.name": "claude-code", "session.id": "s-1", ...CLAIMS }, mark: "record-one" }];
  const r = await send("logs", jsonBody("logs", res), ann);
  expect(r.status).toBe(200);
  const g = await next(from);
  expect(g.path).toBe("/v1/logs");
  expect(g.type).toContain("application/json");
  const [only] = readJson("logs", new TextDecoder().decode(g.body));
  stamped(only!.attributes);
  expect(only!.attributes["service.name"]).toBe("claude-code");
  expect(only!.attributes["session.id"]).toBe("s-1");
  expect(only!.text).toContain("record-one");
  expect(JSON.stringify(g.headers)).not.toContain(ann);
}, 60_000);

test("protobuf metrics are stamped the same way and forwarded as protobuf", async () => {
  const from = got.length;
  const res: Res[] = [{ attributes: { "service.name": "claude-code", ...CLAIMS }, mark: "claude_code.token.usage" }];
  const r = await send("metrics", protoBody("metrics", res), ann);
  expect(r.status).toBe(200);
  const g = await next(from);
  expect(g.path).toBe("/v1/metrics");
  expect(g.type).toContain("application/x-protobuf");
  const [only] = readProto(g.body);
  stamped(only!.attributes);
  expect(only!.attributes["service.name"]).toBe("claude-code");
  expect(only!.text).toContain("claude_code.token.usage");
  expect(JSON.stringify(g.headers)).not.toContain(ann);
}, 60_000);

test("traces reach the collector too, and every resource of a request is stamped, one with no attributes included", async () => {
  const from = got.length;
  const res: Res[] = [
    { attributes: { "service.name": "claude-code", ...CLAIMS }, mark: "span-one" },
    { attributes: {}, mark: "span-two" },
  ];
  expect((await send("traces", protoBody("traces", res), ann)).status).toBe(200);
  const g = await next(from);
  expect(g.path).toBe("/v1/traces");
  const read = readProto(g.body);
  expect(read).toHaveLength(2);
  for (const r of read) stamped(r.attributes);
  expect(read.map((r) => r.text).join(" ")).toContain("span-two");
  const from2 = got.length;
  expect((await send("traces", jsonBody("traces", res), ann)).status).toBe(200);
  const j = readJson("traces", new TextDecoder().decode((await next(from2)).body));
  expect(j).toHaveLength(2);
  for (const r of j) stamped(r.attributes);
}, 60_000);

test("a person with no team does not keep the team the client claimed", async () => {
  const from = got.length;
  const solo = srv.mint("solo@elsewhere.test").key;
  expect((await send("logs", jsonBody("logs", [{ attributes: { ...CLAIMS }, mark: "solo" }]), solo)).status).toBe(200);
  const [only] = readJson("logs", new TextDecoder().decode((await next(from)).body));
  expect(only!.attributes["splice.person"]).toBe("solo@elsewhere.test");
  expect(only!.attributes["user.email"]).toBe("solo@elsewhere.test");
  expect(only!.attributes["splice.team"] ?? "").not.toContain("red");
}, 60_000);

test("no key, an unknown key and a revoked key are each a 401, and nothing is forwarded", async () => {
  const from = got.length;
  const body = jsonBody("logs", [{ attributes: {}, mark: "nope" }]);
  expect((await send("logs", body)).status).toBe(401);
  expect((await send("logs", body, "spk_00000000_notakeynotakeynotakeynotakeynotakey")).status).toBe(401);
  const k = srv.mint("rev@example.com");
  expect(srv.cli(["server", "key", "revoke", k.prefix]).code).toBe(0);
  expect((await send("logs", body, k.key)).status).toBe(401);
  await Bun.sleep(500);
  expect(got.length).toBe(from);
}, 60_000);

test("a signal OTLP does not have is a 404", async () => {
  const r = await fetch(`${srv.base}/server/otlp/v1/profiles-nope`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${ann}` },
    body: "{}",
  });
  expect(r.status).toBe(404);
});

test("a collector that fails is a 502, 503 or 504 to the client, so its exporter tries again", async () => {
  collectorStatus = 500;
  try {
    const r = await send("logs", jsonBody("logs", [{ attributes: {}, mark: "fails" }]), ann);
    expect([502, 503, 504]).toContain(r.status);
  } finally {
    collectorStatus = 200;
  }
  collector.stop(true);
  try {
    const r = await send("logs", jsonBody("logs", [{ attributes: {}, mark: "down" }]), ann);
    expect([502, 503, 504]).toContain(r.status);
  } finally {
    collector = startCollector();
  }
}, 60_000);

test("the profile names the relay, and a server with no relay has neither the route nor a relay in its profile", async () => {
  const p = (await (await fetch(`${srv.base}/server/profile`, { headers: { authorization: `Bearer ${ann}` } })).json()) as {
    telemetry: { otlp: string } | null;
  };
  expect(p.telemetry).toEqual({ otlp: `${srv.base}/server/otlp` });
  const plain = await Server.boot();
  try {
    const key = plain.mint("bo@example.com").key;
    const r = await fetch(`${plain.base}/server/otlp/v1/logs`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${key}` },
      body: jsonBody("logs", [{ attributes: {}, mark: "x" }]),
    });
    expect(r.status).toBe(404);
    const q = (await (await fetch(`${plain.base}/server/profile`, { headers: { authorization: `Bearer ${key}` } })).json()) as {
      telemetry: unknown;
    };
    expect(q.telemetry).toBeNull();
  } finally {
    await plain.dispose();
  }
}, 900_000);

test("a forward_to that is no http(s) URL refuses boot, with a finding that names server.telemetry.forward_to", async () => {
  const bad = await Server.boot({ toml: `\n[server.telemetry]\nforward_to = "otel.lan:4318"\n`, noStart: true });
  try {
    await expect(bad.s.start()).rejects.toThrow();
    const log = readFileSync(bad.s.log, "utf8");
    expect(log).toContain("server.telemetry.forward_to");
  } finally {
    await bad.dispose();
  }
}, 900_000);
