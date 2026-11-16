// server-listener: a [server] table serves the commands it names to anyone holding a live key, over HTTP or HTTPS,
// on any address; without a live key nothing reaches the upstream.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Splice, freePort, mockHead } from "./lib/harness.ts";
import { COMMAND, Server } from "./lib/server.ts";

let srv: Server;
beforeAll(async () => {
  srv = await Server.boot({ server: `trusted_proxies = ["127.0.0.1/32"]` });
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

test("/health answers without a key", async () => {
  const r = await fetch(`${srv.base}/health`);
  expect(r.status).toBe(200);
});

test("a turn with no key is refused 401 authentication_error and reaches no upstream", async () => {
  const before = srv.up.completions().length;
  const t = await srv.turn(undefined);
  expect(t.status).toBe(401);
  expect(t.error?.type).toBe("authentication_error");
  expect(srv.up.completions().length).toBe(before);
}, 30_000);

test("a key the server never issued is refused 401, and the answer does not echo it", async () => {
  const fake = `spk_abcd1234_${"Q".repeat(40)}`;
  const t = await srv.turn(fake);
  expect(t.status).toBe(401);
  expect(t.error?.type).toBe("authentication_error");
  expect(t.body).not.toContain(fake);
}, 30_000);

test("a person's key runs a turn, sent as x-api-key or as Authorization: Bearer", async () => {
  const k = srv.mint("alice@example.com");
  const a = await srv.turn(k.key);
  expect(a.status).toBe(200);
  expect(a.reply).toContain("from-a");
  const b = await srv.turn(k.key, { bearer: true });
  expect(b.status).toBe(200);
  expect(b.reply).toContain("from-a");
}, 60_000);

test("a revoked key is refused on the very next request, saying it was revoked", async () => {
  const k = srv.mint("rita@example.com");
  expect((await srv.turn(k.key)).status).toBe(200);
  const r = srv.cli(["server", "key", "revoke", k.prefix]);
  expect(r.code).toBe(0);
  const t = await srv.turn(k.key);
  expect(t.status).toBe(401);
  expect(t.error?.message.toLowerCase()).toContain("revoked");
}, 60_000);

test("the directory and a command's launch answer a key holder, and refuse anyone else", async () => {
  const k = srv.mint("dora@example.com");
  expect((await fetch(`${srv.base}/`)).status).toBe(401);
  expect((await fetch(`${srv.base}/${COMMAND}/launch`)).status).toBe(401);
  const dir = await fetch(`${srv.base}/`, { headers: { "x-api-key": k.key } });
  expect(dir.status).toBe(200);
  expect(await dir.text()).toContain(COMMAND);
  const launch = await fetch(`${srv.base}/${COMMAND}/launch`, { headers: { authorization: `Bearer ${k.key}` } });
  expect(launch.status).toBe(200);
  expect(await launch.text()).toContain("mock-model");
}, 60_000);

test("a request forwarded from a public address by a trusted proxy is served: the key admits, not the LAN", async () => {
  const k = srv.mint("paul@example.com");
  const t = await srv.turn(k.key, { headers: { "x-forwarded-for": "203.0.113.9", "x-forwarded-proto": "https" } });
  expect(t.status).toBe(200);
  expect(t.reply).toContain("from-a");
}, 60_000);

test("with tls_cert and tls_key the listener speaks HTTPS, and plain HTTP gets no answer", async () => {
  const dir = mkdtempSync(join(tmpdir(), "splice-check-tls-"));
  const cert = join(dir, "cert.pem");
  const key = join(dir, "key.pem");
  const o = spawnSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", cert, "-days", "2", "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"], { encoding: "utf8" });
  expect(o.status).toBe(0);
  const tls = await Server.boot({ tls: { cert, key } });
  try {
    const h = await fetch(`${tls.base}/health`, { tls: { rejectUnauthorized: false } } as RequestInit);
    expect(h.status).toBe(200);
    const k = tls.mint("tess@example.com");
    const t = await tls.turn(k.key);
    expect(t.status).toBe(200);
    expect(t.reply).toContain("from-a");
    const plain = await fetch(`http://127.0.0.1:${tls.port}/health`).then((r) => r.status, () => 0);
    expect(plain).not.toBe(200);
  } finally {
    await tls.dispose();
    rmSync(dir, { recursive: true, force: true });
  }
}, 900_000);

test("a [server] beside an enabled [share], or one naming a command no head has, is refused at boot by name", async () => {
  const head = mockHead({ key: "mock", command: COMMAND, port: await freePort(), url: srv.up.url, model: "mock-model" });
  const both = await Splice.create(`[share]\nenabled = true\nport = ${await freePort()}\n\n[server]\nlisten = "127.0.0.1:${await freePort()}"\ncommands = ["${COMMAND}"]\n${head}`);
  const unknown = await Splice.create(`[server]\nlisten = "127.0.0.1:${await freePort()}"\ncommands = ["claude-nobody"]\n${head}`);
  try {
    const a = both.cli(["check-config"]);
    expect(a.code).not.toBe(0);
    expect(`${a.out}${a.err}`.toLowerCase()).toContain("share");
    expect(`${a.out}${a.err}`.toLowerCase()).toContain("server");
    const b = unknown.cli(["check-config"]);
    expect(b.code).not.toBe(0);
    expect(`${b.out}${b.err}`).toContain("claude-nobody");
  } finally {
    await both.dispose();
    await unknown.dispose();
  }
}, 300_000);
