// remote-sync: a laptop takes its commands from the server's profile. `splice login <server> --key-stdin` keeps an
// operator-issued key the way a sign-in keeps its own (never in a config file), `splice remote add <server>` links
// every command the server offers, and `splice remote refresh <server>` links the ones it starts offering and
// unlinks the ones it drops, leaving the laptop's other commands alone.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort, mockHead } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

const TWO = "claude-two";
const THREE = "claude-three";
const LOCAL = "claude-local";
let srv: Server;
let laptop: Splice;
let key = "";
const ports: Record<string, number> = {};

/** The server's heads for [commands], each its own head on a port of its own. */
const heads = (commands: string[]) => (url: string, port: number) =>
  commands
    .map((c, i) => mockHead({ key: c === COMMAND ? "mock" : c.replace("claude-", ""), command: c, port: i === 0 ? port : ports[c]!, url, model: MODEL }))
    .join("");

beforeAll(async () => {
  for (const c of [TWO, THREE]) ports[c] = await freePort();
  srv = await Server.boot({ commands: [COMMAND, TWO], head: heads([COMMAND, TWO]) });
  laptop = await Splice.boot(mockHead({ key: "local", command: LOCAL, port: await freePort(), url: srv.up.url, model: MODEL }));
  key = srv.mint("ann@example.com").key;
}, 900_000);
afterAll(async () => {
  await laptop?.dispose();
  await srv?.dispose();
}, 90_000);

async function recipe(command: string): Promise<{ status: number; env: Record<string, string>; argv: string[] }> {
  const body = new URLSearchParams({ dangerouslySkipPermissions: "0", cwd: laptop.home, launch: "1" });
  const r = await laptop.api(`/launch/${command}`, { method: "POST", body: body.toString(), headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" } });
  if (r.status !== 200) return { status: r.status, env: {}, argv: [] };
  return { status: 200, ...((await r.json()) as { env: Record<string, string>; argv: string[] }) };
}

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

/** The commands `splice remote` lists on the laptop. */
const listed = () => {
  const r = laptop.cli(["remote"]);
  expect(r.code).toBe(0);
  return r.out
    .split("\n")
    .map((l) => l.trim().split(/\s+/)[0] ?? "")
    .filter((c) => c.startsWith("claude-"));
};

test("a key the server refuses is not kept", () => {
  const r = laptop.cli(["login", srv.base, "--key-stdin"], "spk_00000000_notakeynotakeynotakeynotakeynotakey\n");
  expect(r.code).not.toBe(0);
  expect(laptop.files().filter((f) => f.bytes.includes("notakeynotakey")).map((f) => f.path)).toEqual([]);
});

test("login --key-stdin keeps an operator-issued key outside every config file, and says whose it is", () => {
  const r = laptop.cli(["login", srv.base, "--key-stdin"], `${key}\n`);
  expect(r.code).toBe(0);
  expect(`${r.out}${r.err}`).toContain("ann@example.com");
  const holding = laptop.files().filter((f) => f.bytes.includes(key)).map((f) => f.path);
  expect(holding.length).toBeGreaterThanOrEqual(1);
  for (const p of holding) expect(p).toContain("server-logins");
});

test("remote add <server> links every command the server offers, each launched with the key through a helper", async () => {
  const r = laptop.cli(["remote", "add", srv.base]);
  expect(r.code).toBe(0);
  expect(listed().sort()).toEqual([COMMAND, TWO].sort());
  for (const c of [COMMAND, TWO]) {
    const rec = await recipe(c);
    expect(rec.status).toBe(200);
    expect(rec.env.ANTHROPIC_BASE_URL).toBe(`${srv.base}/${c}`);
    for (const v of Object.values(rec.env)) expect(v).not.toContain(key);
    const h = helperOf(rec);
    expect(h).not.toBeNull();
    const out = spawnSync("sh", ["-c", h!], { env: laptop.env(), encoding: "utf8", timeout: 60_000 });
    expect(out.stdout.trim()).toBe(key);
  }
  expect((await recipe(LOCAL)).status).toBe(200);
}, 300_000);

test("remote refresh <server> links a command the server starts offering and unlinks one it drops", async () => {
  await srv.reconfigure({ commands: [TWO, THREE], head: heads([TWO, THREE]) });
  const r = laptop.cli(["remote", "refresh", srv.base]);
  expect(r.code).toBe(0);
  expect(`${r.out}${r.err}`).toContain(THREE);
  expect(`${r.out}${r.err}`).toContain(COMMAND);
  expect(listed().sort()).toEqual([TWO, THREE].sort());
  const three = await recipe(THREE);
  expect(three.status).toBe(200);
  expect(three.env.ANTHROPIC_BASE_URL).toBe(`${srv.base}/${THREE}`);
  expect((await recipe(COMMAND)).status).not.toBe(200);
  expect((await recipe(LOCAL)).status).toBe(200);
}, 300_000);

test("refreshing again with nothing changed changes nothing", () => {
  const r = laptop.cli(["remote", "refresh", srv.base]);
  expect(r.code).toBe(0);
  expect(listed().sort()).toEqual([TWO, THREE].sort());
});
