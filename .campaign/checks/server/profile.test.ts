// server-profile: a key holder asks the server what it offers, at GET /server/profile: each command it serves with
// its models and their context windows, the sign-in configuration (or null), the telemetry relay (or null) and the
// minimum client (or null). A laptop builds its commands from this one answer. No key, or a revoked one, is a 401.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { freePort, json, mockHead } from "./lib/harness.ts";
import { Issuer } from "./lib/issuer.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

interface Profile {
  commands: { command: string; models: { id: string; context_window: number }[] }[];
  signin: { issuer: string; client_id: string; domains: string[] } | null;
  telemetry: { otlp: string } | null;
  client: { minimum: string | null };
}

const TWO = "claude-two";
let srv: Server;
let plain: Server;
const issuer = new Issuer();

beforeAll(async () => {
  await issuer.start();
  const twoPort = await freePort();
  srv = await Server.boot({
    commands: [COMMAND, TWO],
    head: (url, port) =>
      mockHead({ key: "mock", command: COMMAND, port, url, model: MODEL, window: 200_000 }) +
      mockHead({ key: "two", command: TWO, port: twoPort, url, model: "mock-two", window: 64_000 }),
    server:
      `\n[server.signin]\nissuer = "${issuer.url}"\nclient_id = "${issuer.clientId}"\n` +
      `client_secret_env = "SPLICE_CHECK_SIGNIN_SECRET"\ndomains = ["example.com"]\nkey_days = 30\n`,
    env: { SPLICE_CHECK_SIGNIN_SECRET: issuer.clientSecret },
  });
  plain = await Server.boot();
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
  await plain?.dispose();
  issuer.stop();
}, 90_000);

const ask = (s: Server, key?: string) =>
  fetch(`${s.base}/server/profile`, { headers: key ? { authorization: `Bearer ${key}` } : {} });

test("a key holder reads every served command with its models and their windows, and the sign-in configuration", async () => {
  const key = srv.mint("ann@example.com").key;
  const r = await ask(srv, key);
  expect(r.status).toBe(200);
  expect(r.headers.get("content-type")).toContain("application/json");
  const p = (await r.json()) as Profile;
  const byName = Object.fromEntries(p.commands.map((c) => [c.command, c]));
  expect(Object.keys(byName).sort()).toEqual([COMMAND, TWO].sort());
  expect(byName[COMMAND]!.models).toEqual([{ id: MODEL, context_window: 200_000 }]);
  expect(byName[TWO]!.models).toEqual([{ id: "mock-two", context_window: 64_000 }]);
  expect(p.signin).not.toBeNull();
  expect(p.signin!.issuer).toBe(issuer.url);
  expect(p.signin!.client_id).toBe(issuer.clientId);
  expect(p.signin!.domains).toEqual(["example.com"]);
  expect(JSON.stringify(p)).not.toContain(issuer.clientSecret);
  expect(p.telemetry).toBeNull();
  expect(p.client.minimum).toBeNull();
});

test("a server with no sign-in says so with null", async () => {
  const key = plain.mint("bo@example.com").key;
  const r = await ask(plain, key);
  expect(r.status).toBe(200);
  const p = (await r.json()) as Profile;
  expect(p.commands.map((c) => c.command)).toEqual([COMMAND]);
  expect(p.signin).toBeNull();
});

test("no key, an unknown key and a revoked key are each a 401", async () => {
  expect((await ask(srv)).status).toBe(401);
  expect((await ask(srv, "spk_00000000_notakeynotakeynotakeynotakeynotakey")).status).toBe(401);
  const k = srv.mint("cy@example.com");
  expect((await ask(srv, k.key)).status).toBe(200);
  const revoked = srv.cli(["server", "key", "revoke", k.prefix]);
  expect(revoked.code).toBe(0);
  expect((await ask(srv, k.key)).status).toBe(401);
});

test("the profile is read from the config the server runs, so a command it stops serving leaves it", async () => {
  const key = srv.mint("dee@example.com").key;
  await srv.reconfigure({ head: (url, port) => mockHead({ key: "mock", command: COMMAND, port, url, model: MODEL, window: 200_000 }) });
  const p = (await (await ask(srv, key)).json()) as Profile;
  expect(p.commands.map((c) => c.command)).toEqual([COMMAND]);
  expect(json<{ keys: unknown[] }>(srv.cli(["server", "key", "list", "--json", "--person", "dee@example.com"])).keys.length).toBe(1);
}, 300_000);
