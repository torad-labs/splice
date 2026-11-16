// signin-server: the server trades an OIDC authorization code (PKCE) for a verified email and mints that person a
// key with an expiry, one per device; every way the ID token can be wrong is refused, and sign-in is rate limited.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { json } from "./lib/harness.ts";
import { Issuer, pkce, type Identity } from "./lib/issuer.ts";
import { Server } from "./lib/server.ts";

let srv: Server;
const issuer = new Issuer();
const KEY = /^spk_[a-z0-9]{8}_[A-Za-z0-9]{32,}$/;

beforeAll(async () => {
  await issuer.start();
  srv = await Server.boot({
    server:
      `\n[server.signin]\nissuer = "${issuer.url}"\nclient_id = "${issuer.clientId}"\n` +
      `client_secret_env = "SPLICE_CHECK_SIGNIN_SECRET"\ndomains = ["example.com"]\nkey_days = 30\nrate_per_minute = 40\n`,
    env: { SPLICE_CHECK_SIGNIN_SECRET: issuer.clientSecret },
  });
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
  issuer.stop();
}, 60_000);

interface Answer {
  status: number;
  body: { key?: string; prefix?: string; person?: string; expires_at?: string; error?: { type: string; message: string } } | null;
}

/** A laptop's sign-in, done by hand: the server's sign-in config, the issuer's redirect, then the code traded. */
async function signIn(who: Identity, device: string, o: { nonce?: string; verifier?: string } = {}): Promise<Answer> {
  const cfg = (await (await fetch(`${srv.base}/server/signin`)).json()) as { client_id: string; scopes: string[]; authorization_endpoint: string };
  const { verifier, challenge } = pkce();
  const redirect = "http://127.0.0.1:1/callback";
  const nonce = `n-${crypto.randomUUID()}`;
  const q = new URLSearchParams({ response_type: "code", client_id: cfg.client_id, redirect_uri: redirect, scope: cfg.scopes.join(" "), state: "st", nonce, code_challenge: challenge, code_challenge_method: "S256" });
  const code = new URL(await issuer.authorize(`${cfg.authorization_endpoint}?${q}`, who)).searchParams.get("code");
  const r = await fetch(`${srv.base}/server/signin/token`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, code_verifier: o.verifier ?? verifier, redirect_uri: redirect, nonce: o.nonce ?? nonce, device }),
  });
  return { status: r.status, body: (await r.json().catch(() => null)) as Answer["body"] };
}

const keysOf = (person: string) => {
  const r = srv.cli(["server", "key", "list", "--json", "--person", person]);
  return json<{ keys: { prefix: string; source: string; device: string | null; expires_at: string | null }[] }>(r).keys;
};

test("the sign-in config is open and gives a laptop everything it needs, and no secret", async () => {
  const r = await fetch(`${srv.base}/server/signin`);
  expect(r.status).toBe(200);
  const text = await r.text();
  const cfg = JSON.parse(text) as Record<string, unknown>;
  expect(cfg.issuer).toBe(issuer.url);
  expect(cfg.client_id).toBe(issuer.clientId);
  expect(cfg.authorization_endpoint).toBe(`${issuer.url}/authorize`);
  expect(cfg.scopes).toEqual(expect.arrayContaining(["openid", "email"]));
  expect(cfg.domains).toEqual(["example.com"]);
  expect(text).not.toContain(issuer.clientSecret);
});

test("a verified email in an allowed domain gets a key that expires in key_days, bound to its device", async () => {
  const a = await signIn({ email: "Alice@Example.com" }, "dev-a");
  expect(a.status).toBe(200);
  expect(a.body?.key).toMatch(KEY);
  expect(a.body?.person).toBe("alice@example.com");
  const days = (Date.parse(a.body!.expires_at!) - Date.now()) / 86_400_000;
  expect(days).toBeGreaterThan(29);
  expect(days).toBeLessThan(31);
  const t = await srv.turn(a.body!.key);
  expect(t.status).toBe(200);
  const row = keysOf("alice@example.com").find((k) => k.prefix === a.body!.prefix)!;
  expect(row.source).toBe("signin");
  expect(row.device).toBe("dev-a");
  const trade = issuer.tokenRequests.at(-1)!;
  expect(trade.client_id).toBe(issuer.clientId);
  expect(trade.code_verifier?.length).toBeGreaterThan(40);
}, 60_000);

const refusals: [string, Identity, { nonce?: string; verifier?: string }?][] = [
  ["an email outside the allowed domains", { email: "bob@other.org" }],
  ["an unverified email", { email: "vic@example.com", email_verified: false }],
  ["a token signed by a key the issuer does not publish", { email: "sig@example.com", foreignKey: true }],
  ["an expired ID token", { email: "old@example.com", expiresIn: -120 }],
  ["a token for another audience", { email: "aud@example.com", aud: "someone-else" }],
  ["a token from another issuer", { email: "iss@example.com", iss: "https://issuer.invalid" }],
  ["a nonce that is not the one the laptop sent", { email: "non@example.com" }, { nonce: "not-the-nonce" }],
  ["a code_verifier that does not match the challenge", { email: "pk@example.com" }, { verifier: "x".repeat(43) }],
];
for (const [what, who, o] of refusals) {
  test(`${what} is refused 401 and no key is minted`, async () => {
    const r = await signIn(who, "dev-x", o);
    expect(r.status).toBe(401);
    expect(r.body?.error?.type).toBe("authentication_error");
    expect(r.body?.key).toBeUndefined();
    expect(keysOf(who.email.toLowerCase()).length).toBe(0);
  }, 30_000);
}

test("a new sign-in keeps the old key on that device until the new one is used, and never touches other devices", async () => {
  const k1 = (await signIn({ email: "dev@example.com" }, "laptop")).body!.key!;
  const k2 = (await signIn({ email: "dev@example.com" }, "laptop")).body!.key!;
  expect(k1).not.toBe(k2);
  expect((await srv.turn(k1)).status).toBe(200);
  expect((await srv.turn(k2)).status).toBe(200);
  expect((await srv.turn(k1)).status).toBe(401);
  const k3 = (await signIn({ email: "dev@example.com" }, "desktop")).body!.key!;
  expect((await srv.turn(k3)).status).toBe(200);
  expect((await srv.turn(k2)).status).toBe(200);
}, 90_000);

test("sign-in requests past rate_per_minute from one address are answered 429 with Retry-After", async () => {
  const answers = await Promise.all(
    Array.from({ length: 60 }, () =>
      fetch(`${srv.base}/server/signin/token`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code: "junk", code_verifier: "junk", redirect_uri: "http://127.0.0.1:1/callback", nonce: "n", device: "d" }) }),
    ),
  );
  const limited = answers.filter((r) => r.status === 429);
  expect(limited.length).toBeGreaterThan(0);
  expect(Number(limited[0]!.headers.get("retry-after"))).toBeGreaterThanOrEqual(1);
}, 60_000);
