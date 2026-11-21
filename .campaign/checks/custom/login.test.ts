// cp-login: a provider's `login_command` is how a person signs in to it, as Claude Code's awsAuthRefresh is for
// Bedrock. `splice login <head>` runs it in the person's terminal, then the credential command, and says whether they
// are signed in and until when. The control plane's sign-in route, which the accounts page's Sign in calls, runs it in
// the daemon and reports it in the shape every sign-in has (the first address it prints as browser_url, then
// signed_in or failed). GET /api/auth says, for the head, whether splice holds a key and when it expires. A key splice
// holds is never in the environment the login command runs in.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { ask, session, toDoor } from "../door/lib/claude.ts";
import { Gateway, SELECTOR, TeamCli, acme, withCli } from "./lib/team.ts";

setDefaultTimeout(120_000);

const SSO = "https://sso.acme.example/device?user_code=WXYZ-1234";

let cli: TeamCli;
let gw: Gateway;
let s: Splice;

interface SignIn {
  id: string;
  state: string;
  browser_url: string | null;
  failure_reason: string | null;
}

async function auth(): Promise<{ present?: boolean; expires_at?: string }> {
  const j = (await (await s.api("/api/auth")).json()) as Record<string, { present?: boolean; expires_at?: string }>;
  return j.acme ?? {};
}

async function start(): Promise<SignIn> {
  const r = await s.api("/api/auth/acme/login", { method: "POST", body: "{}" });
  expect(r.status).toBe(200);
  return (await r.json()) as SignIn;
}

async function poll(id: string): Promise<SignIn> {
  return (await (await s.api(`/api/auth/acme/login/${id}`)).json()) as SignIn;
}

beforeAll(async () => {
  cli = new TeamCli();
  gw = new Gateway(cli).start();
  s = await Splice.boot(acme({ url: gw.url, port: await freePort(), cli }), withCli(cli));
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  gw?.stop();
  cli?.dispose();
});

describe("cp-login", () => {
  test("signed out, GET /api/auth says splice holds no key for the head", async () => {
    expect(cli.signedIn()).toBe(false);
    expect((await auth()).present).toBe(false);
  });

  test("splice login <head> runs the login command in the terminal, then says the person is signed in and until when", async () => {
    const r = s.cli(["login", "acme"]);
    expect(r.code, `${r.out}\n${r.err}`).toBe(0);
    const said = `${r.out}\n${r.err}`;
    expect(said).toContain(SSO);
    expect(said).toMatch(/signed in/i);
    expect(said).toContain("acme");
    expect(cli.signedIn()).toBe(true);
  });

  test("once signed in, a door turn runs on the provider with no restart, and GET /api/auth names the key's expiry", async () => {
    const marker = `cp-login-${crypto.randomUUID()}`;
    const t = await ask(await session(s), { model: SELECTOR, content: marker });
    expect(t.status).toBe(200);
    expect(gw.turnsWith(marker).length).toBe(1);
    const a = await auth();
    expect(a.present).toBe(true);
    expect(Number.isNaN(Date.parse(a.expires_at ?? ""))).toBe(false);
    expect(Math.abs(Date.parse(a.expires_at!) - (Date.now() + 3600_000))).toBeLessThan(120_000);
  });

  test("Sign in on the control plane runs the login command in the daemon and reports it as every sign-in does", async () => {
    cli.signOut();
    cli.loginTakes(3);
    const first = await start();
    expect(first.id).toBeTruthy();
    await until("the sign-in shows the address the command printed", async () => (await poll(first.id)).browser_url === SSO, 20_000);
    await until("the sign-in finishes", async () => (await poll(first.id)).state === "signed_in", 30_000);
    expect(cli.signedIn()).toBe(true);
    expect((await auth()).present).toBe(true);
  });

  test("a key splice holds is never in the environment the login command runs in", () => {
    const keys = cli.issued();
    expect(keys.length).toBeGreaterThan(0);
    const env = cli.envOf("login");
    expect(env).not.toBe("");
    for (const k of keys) expect(env).not.toContain(k);
  });

  test("a login command that fails is a failed sign-in, and splice login says so and exits non-zero", async () => {
    cli.signOut();
    cli.loginTakes(0);
    cli.loginFails(true);
    try {
      const first = await start();
      let now = first;
      await until("the sign-in fails", async () => (now = await poll(first.id)).state === "failed", 30_000);
      expect(now.failure_reason ?? "").toMatch(/exit|3/);
      const r = s.cli(["login", "acme"]);
      expect(r.code).not.toBe(0);
      expect(`${r.out}\n${r.err}`).toContain("acme");
    } finally {
      cli.loginFails(false);
    }
  });
});
