// server-keys: an operator issues, lists and revokes per-person keys from the CLI, the control plane, or (as an
// admin) over the server listener; only a hash of each key is kept.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { json } from "./lib/harness.ts";
import { Server, type Minted } from "./lib/server.ts";

let srv: Server;
beforeAll(async () => {
  srv = await Server.boot({ server: `admins = ["ops@example.com"]` });
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const KEY = /^spk_[a-z0-9]{8}_[A-Za-z0-9]{32,}$/;
interface Row {
  prefix: string;
  person: string;
  name: string | null;
  source: string;
  created_at: string;
  expires_at: string | null;
  last_used_at: string | null;
  revoked_at: string | null;
}
const list = (more: string[] = []) => {
  const r = srv.cli(["server", "key", "list", "--json", ...more]);
  expect(r.code).toBe(0);
  return { raw: r.out, rows: json<{ keys: Row[] }>(r).keys };
};
const rowOf = (prefix: string) => list().rows.find((k) => k.prefix === prefix);

test("create prints the key once, in the documented shape, for a lowercased email", () => {
  const k = srv.mint("Alice@Example.COM", ["--name", "laptop"]);
  expect(k.key).toMatch(KEY);
  expect(k.prefix).toBe(k.key.split("_").slice(0, 2).join("_"));
  expect(k.person).toBe("alice@example.com");
  expect(k.name).toBe("laptop");
  expect(k.source).toBe("operator");
  expect(k.expires_at).toBeNull();
});

test("list shows each key's prefix, person and dates, and never a secret", () => {
  const k = srv.mint("lena@example.com", ["--name", "ci"]);
  const { raw, rows } = list();
  const row = rows.find((r) => r.prefix === k.prefix)!;
  expect(row).toBeDefined();
  expect(row.person).toBe("lena@example.com");
  expect(row.name).toBe("ci");
  expect(row.source).toBe("operator");
  expect(Number.isNaN(Date.parse(row.created_at))).toBe(false);
  expect(row.revoked_at).toBeNull();
  expect(raw).not.toContain(k.key);
  expect(raw).not.toContain(k.key.split("_")[2]!);
  expect(list(["--person", "lena@example.com"]).rows.every((r) => r.person === "lena@example.com")).toBe(true);
});

test("only a hash is kept: no file in the server's home holds a key or its secret, before or after it is used", async () => {
  const k = srv.mint("hana@example.com");
  expect((await srv.turn(k.key)).status).toBe(200);
  const secret = k.key.split("_")[2]!;
  const holding = srv.s.files().filter((f) => f.bytes.includes(secret) || f.bytes.includes(k.key)).map((f) => f.path);
  expect(holding).toEqual([]);
}, 60_000);

test("a key that authenticates a turn records when it was last used", async () => {
  const k = srv.mint("uma@example.com");
  expect(rowOf(k.prefix)!.last_used_at).toBeNull();
  expect((await srv.turn(k.key)).status).toBe(200);
  const used = rowOf(k.prefix)!.last_used_at;
  expect(used).not.toBeNull();
  expect(Math.abs(Date.parse(used!) - Date.now())).toBeLessThan(120_000);
}, 60_000);

test("revoke-person revokes every key of that person on the next request, and no one else's", async () => {
  const a1 = srv.mint("olga@example.com");
  const a2 = srv.mint("olga@example.com", ["--name", "desktop"]);
  const b = srv.mint("bert@example.com");
  for (const k of [a1, a2, b]) expect((await srv.turn(k.key)).status).toBe(200);
  const r = srv.cli(["server", "key", "revoke-person", "OLGA@example.com"]);
  expect(r.code).toBe(0);
  expect(`${r.out}${r.err}`).toContain("2");
  expect((await srv.turn(a1.key)).status).toBe(401);
  expect((await srv.turn(a2.key)).status).toBe(401);
  expect((await srv.turn(b.key)).status).toBe(200);
  for (const k of [a1, a2]) expect(rowOf(k.prefix)!.revoked_at).not.toBeNull();
  expect(rowOf(b.prefix)!.revoked_at).toBeNull();
}, 90_000);

test("a key made to expire works until it expires, then is refused saying it expired", async () => {
  const k = srv.mint("ezra@example.com", ["--expires", "6s"]);
  expect(k.expires_at).not.toBeNull();
  expect((await srv.turn(k.key)).status).toBe(200);
  await Bun.sleep(Math.max(0, Date.parse(k.expires_at!) - Date.now()) + 1500);
  const t = await srv.turn(k.key);
  expect(t.status).toBe(401);
  expect(t.error?.message.toLowerCase()).toContain("expired");
}, 60_000);

test("a person that is not an email is refused", () => {
  const r = srv.cli(["server", "key", "create", "--person", "not-an-email", "--json"]);
  expect(r.code).not.toBe(0);
});

test("the control plane issues, lists and revokes keys with the management key, and refuses without it", async () => {
  const made = await srv.s.api("/api/server/keys", { method: "POST", body: JSON.stringify({ person: "carol@example.com", name: "api" }) });
  expect(made.status).toBeLessThan(300);
  const k = (await made.json()) as Minted;
  expect(k.key).toMatch(KEY);
  expect((await srv.turn(k.key)).status).toBe(200);
  const listed = (await (await srv.s.api("/api/server/keys")).json()) as { keys: Row[] };
  expect(listed.keys.some((r) => r.prefix === k.prefix && r.person === "carol@example.com")).toBe(true);
  expect(JSON.stringify(listed)).not.toContain(k.key);
  const del = await srv.s.api(`/api/server/keys/${k.prefix}`, { method: "DELETE" });
  expect(del.status).toBeLessThan(300);
  expect((await srv.turn(k.key)).status).toBe(401);
  const c2 = srv.mint("carol@example.com");
  const off = await srv.s.api(`/api/server/people/${encodeURIComponent("carol@example.com")}/keys`, { method: "DELETE" });
  expect(off.status).toBeLessThan(300);
  expect((await srv.turn(c2.key)).status).toBe(401);
  const anon = await fetch(`http://127.0.0.1:${srv.s.controlPort}/api/server/keys`);
  expect(anon.status).toBe(401);
}, 90_000);

test("an admin's key manages keys over the server listener; another person's key is refused 403", async () => {
  const ops = srv.mint("ops@example.com");
  const pat = srv.mint("pat@example.com");
  const auth = (k: string) => ({ "x-api-key": k, "content-type": "application/json" });
  const seen = await fetch(`${srv.base}/server/api/keys`, { headers: auth(ops.key) });
  expect(seen.status).toBe(200);
  expect(((await seen.json()) as { keys: Row[] }).keys.some((r) => r.prefix === pat.prefix)).toBe(true);
  const made = await fetch(`${srv.base}/server/api/keys`, { method: "POST", headers: auth(ops.key), body: JSON.stringify({ person: "nina@example.com" }) });
  expect(made.status).toBeLessThan(300);
  const nina = (await made.json()) as Minted;
  expect((await srv.turn(nina.key)).status).toBe(200);
  expect((await fetch(`${srv.base}/server/api/keys`, { headers: auth(pat.key) })).status).toBe(403);
  expect((await fetch(`${srv.base}/server/api/keys`)).status).toBe(401);
  const gone = await fetch(`${srv.base}/server/api/keys/${nina.prefix}`, { method: "DELETE", headers: auth(ops.key) });
  expect(gone.status).toBeLessThan(300);
  expect((await srv.turn(nina.key)).status).toBe(401);
}, 90_000);
