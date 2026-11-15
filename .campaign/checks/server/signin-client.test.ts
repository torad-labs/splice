// signin-client: `splice login <server>` signs a laptop in through the browser (PKCE, loopback redirect), keeps the
// key, and a remote command on that server launches Claude Code with the key through an apiKeyHelper, so a later
// sign-in reaches a running session without a restart. `splice logout <server>` forgets the key.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort, json, mockHead } from "./lib/harness.ts";
import { Issuer } from "./lib/issuer.ts";
import { COMMAND, Server } from "./lib/server.ts";

let srv: Server;
let laptop: Splice;
const issuer = new Issuer();
const KEY = /spk_[a-z0-9]{8}_[A-Za-z0-9]{32,}/;

beforeAll(async () => {
  await issuer.start();
  srv = await Server.boot({
    server:
      `\n[server.signin]\nissuer = "${issuer.url}"\nclient_id = "${issuer.clientId}"\n` +
      `client_secret_env = "SPLICE_CHECK_SIGNIN_SECRET"\ndomains = ["example.com"]\nkey_days = 30\n`,
    env: { SPLICE_CHECK_SIGNIN_SECRET: issuer.clientSecret },
  });
  laptop = await Splice.boot(mockHead({ key: "local", command: "claude-local", port: await freePort(), url: srv.up.url, model: "mock-model" }));
}, 900_000);
afterAll(async () => {
  await laptop?.dispose();
  await srv?.dispose();
  issuer.stop();
}, 90_000);

/** Run `splice login <server>` on the laptop and play the browser as `email`. */
async function login(email: string): Promise<{ code: number; said: string; authUrl: URL }> {
  const p = laptop.spawnCli(["login", srv.base]);
  let said = "";
  p.stdout!.on("data", (d) => (said += d));
  p.stderr!.on("data", (d) => (said += d));
  const exited = new Promise<number>((res) => p.on("exit", (c) => res(c ?? -1)));
  const deadline = Date.now() + 60_000;
  let found: RegExpMatchArray | null = null;
  while (!found && Date.now() < deadline && p.exitCode === null) {
    found = said.match(new RegExp(`${issuer.url.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/authorize\\?\\S+`));
    if (!found) await Bun.sleep(200);
  }
  if (!found) {
    p.kill();
    throw new Error(`splice login printed no authorization URL:\n${said}`);
  }
  const authUrl = new URL(found[0]);
  const back = await issuer.authorize(found[0], { email });
  await fetch(back).catch(() => null);
  const code = await Promise.race([exited, Bun.sleep(60_000).then(() => -2)]);
  if (code === -2) p.kill();
  return { code, said, authUrl };
}

/** What Claude Code would be launched with for a command on the laptop. */
async function recipe(command: string): Promise<{ env: Record<string, string>; argv: string[] }> {
  const body = new URLSearchParams({ dangerouslySkipPermissions: "0", cwd: laptop.home, launch: "1" });
  const r = await laptop.api(`/launch/${command}`, { method: "POST", body: body.toString(), headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" } });
  expect(r.status).toBe(200);
  return (await r.json()) as { env: Record<string, string>; argv: string[] };
}

/** The apiKeyHelper the recipe hands Claude Code: from a --settings argument, or the command's settings.json. */
function helperOf(rec: { env: Record<string, string>; argv: string[] }): string | null {
  const i = rec.argv.indexOf("--settings");
  const sources: string[] = [];
  if (i >= 0 && rec.argv[i + 1]) {
    const v = rec.argv[i + 1]!;
    sources.push(v.trim().startsWith("{") ? v : existsSync(v) ? readFileSync(v, "utf8") : "{}");
  }
  const dir = rec.env.CLAUDE_CONFIG_DIR;
  if (dir && existsSync(join(dir, "settings.json"))) sources.push(readFileSync(join(dir, "settings.json"), "utf8"));
  for (const s of sources) {
    const h = (JSON.parse(s) as { apiKeyHelper?: string }).apiKeyHelper;
    if (h) return h;
  }
  return null;
}

const runHelper = (h: string) => spawnSync("sh", ["-c", h], { env: laptop.env(), encoding: "utf8", timeout: 60_000 });

test("splice login signs in through the browser with PKCE and a loopback redirect, and says who it signed in", async () => {
  const r = await login("dana@example.com");
  expect(r.code).toBe(0);
  expect(r.said).toContain("dana@example.com");
  const q = r.authUrl.searchParams;
  expect(q.get("client_id")).toBe(issuer.clientId);
  expect(q.get("response_type")).toBe("code");
  expect(q.get("code_challenge_method")).toBe("S256");
  expect(q.get("redirect_uri")).toMatch(/^http:\/\/(127\.0\.0\.1|localhost):\d+\//);
  expect(q.get("state")).toBeTruthy();
  expect(q.get("nonce")).toBeTruthy();
  const keys = json<{ keys: { person: string; source: string; device: string | null }[] }>(srv.cli(["server", "key", "list", "--json", "--person", "dana@example.com"])).keys;
  expect(keys.length).toBe(1);
  expect(keys[0]!.source).toBe("signin");
  expect(keys[0]!.device).toBeTruthy();
}, 180_000);

test("a remote command on that server launches Claude Code with the key through an apiKeyHelper, and a turn runs", async () => {
  const added = laptop.cli(["remote", "add", `${srv.base}/${COMMAND}`]);
  expect(added.code).toBe(0);
  const rec = await recipe(COMMAND);
  expect(rec.env.ANTHROPIC_BASE_URL).toBe(`${srv.base}/${COMMAND}`);
  for (const v of Object.values(rec.env)) expect(v).not.toMatch(KEY);
  const helper = helperOf(rec);
  expect(helper).not.toBeNull();
  const out = runHelper(helper!);
  expect(out.status).toBe(0);
  const key = out.stdout.trim();
  expect(key).toMatch(new RegExp(`^${KEY.source}$`));
  const t = await srv.turn(key);
  expect(t.status).toBe(200);
  expect(t.reply).toContain("from-a");
}, 240_000);

test("signing in again from the same laptop sends the same device, and the helper hands out the new key", async () => {
  const before = (await recipe(COMMAND));
  const old = runHelper(helperOf(before)!).stdout.trim();
  const r = await login("dana@example.com");
  expect(r.code).toBe(0);
  const keys = json<{ keys: { device: string | null; revoked_at: string | null }[] }>(srv.cli(["server", "key", "list", "--json", "--person", "dana@example.com"])).keys;
  expect(keys.length).toBe(2);
  expect(new Set(keys.map((k) => k.device)).size).toBe(1);
  const now = runHelper(helperOf(await recipe(COMMAND))!).stdout.trim();
  expect(now).not.toBe(old);
  expect((await srv.turn(now)).status).toBe(200);
  expect((await srv.turn(old)).status).toBe(401);
}, 240_000);

test("splice logout <server> forgets the key: the helper hands out none", async () => {
  const r = laptop.cli(["logout", srv.base]);
  expect(r.code).toBe(0);
  const helper = helperOf(await recipe(COMMAND));
  const out = helper ? runHelper(helper) : null;
  expect(out === null || out.status !== 0 || !KEY.test(out.stdout)).toBe(true);
}, 120_000);
