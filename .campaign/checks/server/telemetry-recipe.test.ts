// telemetry-recipe: a command linked from a server with a telemetry relay launches Claude Code with its telemetry
// sent to the relay: CLAUDE_CODE_ENABLE_TELEMETRY=1, metrics and logs exported over OTLP/HTTP to
// <server>/server/otlp, and an otelHeadersHelper that sends the person's key, which is never in the environment.
// Prompts are never logged (no OTEL_LOG_USER_PROMPTS). Where the command's organisation-managed settings
// (remote-settings.json) set telemetry of their own, splice adds none; managed settings that set none leave the relay
// on. A server with no relay gets no telemetry from splice. `remote add` and a `refresh` that changes the relay say, in
// one line, where the commands' telemetry goes, or that it is no longer relayed.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, rmSync, writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort, mockHead } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

let srv: Server;
let laptop: Splice;
let key = "";
let collectorPort = 0;
let added = "";

const withRelay = () => ({ toml: `\n[server.telemetry]\nforward_to = "http://127.0.0.1:${collectorPort}"\n` });

beforeAll(async () => {
  collectorPort = await freePort();
  srv = await Server.boot(withRelay());
  laptop = await Splice.boot(mockHead({ key: "local", command: "claude-local", port: await freePort(), url: srv.up.url, model: MODEL }));
  key = srv.mint("ann@example.com").key;
  expect(laptop.cli(["login", srv.base, "--key-stdin"], `${key}\n`).code).toBe(0);
  const add = laptop.cli(["remote", "add", srv.base]);
  expect(add.code).toBe(0);
  added = add.out;
}, 900_000);
afterAll(async () => {
  await laptop?.dispose();
  await srv?.dispose();
}, 90_000);

interface Recipe {
  env: Record<string, string>;
  argv: string[];
}

async function recipe(command: string): Promise<Recipe> {
  const body = new URLSearchParams({ dangerouslySkipPermissions: "0", cwd: laptop.home, launch: "1" });
  const r = await laptop.api(`/launch/${command}`, { method: "POST", body: body.toString(), headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" } });
  expect(r.status).toBe(200);
  return (await r.json()) as Recipe;
}

/** The settings Claude Code reads for [rec]: its --settings, then its config folder's settings.json. */
function settingsOf(rec: Recipe): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = [];
  const i = rec.argv.indexOf("--settings");
  if (i >= 0 && rec.argv[i + 1]) {
    const v = rec.argv[i + 1]!;
    out.push(JSON.parse(v.trim().startsWith("{") ? v : existsSync(v) ? readFileSync(v, "utf8") : "{}"));
  }
  const file = join(configDir(rec), "settings.json");
  if (existsSync(file)) out.push(JSON.parse(readFileSync(file, "utf8")));
  return out;
}

const configDir = (rec: Recipe) => rec.env.CLAUDE_CONFIG_DIR ?? join(laptop.home, ".claude");
const headersHelper = (rec: Recipe) => settingsOf(rec).map((s) => s.otelHeadersHelper).find((h) => typeof h === "string") as string | undefined;

/** Whether [rec] carries telemetry splice set: the switch, the relay's endpoint or the headers helper. */
const relayed = (rec: Recipe) =>
  rec.env.CLAUDE_CODE_ENABLE_TELEMETRY === "1" || (rec.env.OTEL_EXPORTER_OTLP_ENDPOINT ?? "").includes("/server/otlp") || headersHelper(rec) !== undefined;

test("a linked command sends its metrics and logs to the relay over OTLP/HTTP, and never its prompts", async () => {
  const rec = await recipe(COMMAND);
  expect(rec.env.CLAUDE_CODE_ENABLE_TELEMETRY).toBe("1");
  expect(rec.env.OTEL_EXPORTER_OTLP_ENDPOINT).toBe(`${srv.base}/server/otlp`);
  expect(rec.env.OTEL_METRICS_EXPORTER).toBe("otlp");
  expect(rec.env.OTEL_LOGS_EXPORTER).toBe("otlp");
  expect(["http/json", "http/protobuf"]).toContain(rec.env.OTEL_EXPORTER_OTLP_PROTOCOL ?? "");
  expect(rec.env.OTEL_LOG_USER_PROMPTS).toBeUndefined();
  for (const v of Object.values(rec.env)) expect(v).not.toContain(key);
}, 120_000);

test("the headers helper sends the person's key as a bearer", async () => {
  const rec = await recipe(COMMAND);
  const h = headersHelper(rec);
  expect(h).toBeDefined();
  const out = spawnSync("sh", ["-c", h!], { env: laptop.env(), encoding: "utf8", timeout: 60_000 });
  expect(out.status).toBe(0);
  const headers = JSON.parse(out.stdout) as Record<string, string>;
  const auth = Object.entries(headers).find(([k]) => k.toLowerCase() === "authorization")?.[1];
  expect(auth).toBe(`Bearer ${key}`);
}, 120_000);

test("the laptop's own commands get no telemetry from splice", async () => {
  expect(relayed(await recipe("claude-local"))).toBe(false);
}, 120_000);

test("managed settings that set telemetry of their own turn splice's off, and managed settings that set none leave it on", async () => {
  const dir = configDir(await recipe(COMMAND));
  mkdirSync(dir, { recursive: true });
  const managed = join(dir, "remote-settings.json");
  try {
    writeFileSync(managed, JSON.stringify({ env: { CLAUDE_CODE_ENABLE_TELEMETRY: "1", OTEL_EXPORTER_OTLP_ENDPOINT: "https://otel.corp.example" } }));
    const rec = await recipe(COMMAND);
    expect(rec.env.OTEL_EXPORTER_OTLP_ENDPOINT ?? "").not.toContain("/server/otlp");
    expect(headersHelper(rec)).toBeUndefined();
    writeFileSync(managed, JSON.stringify({ env: { SOME_OTHER_SETTING: "1" } }));
    const again = await recipe(COMMAND);
    expect(again.env.OTEL_EXPORTER_OTLP_ENDPOINT).toBe(`${srv.base}/server/otlp`);
    expect(headersHelper(again)).toBeDefined();
  } finally {
    rmSync(managed, { force: true });
  }
}, 120_000);

test("remote add tells the person where their commands' telemetry goes", () => {
  const line = added.split("\n").find((l) => l.includes("telemetry"));
  expect(line ?? "").toContain(`${srv.base}/server/otlp`);
});

test("once the server stops relaying, a refresh takes the telemetry away and says so", async () => {
  await srv.reconfigure({});
  const off = laptop.cli(["remote", "refresh", srv.base]);
  expect(off.code).toBe(0);
  const said = off.out.split("\n").find((l) => l.includes("telemetry"));
  expect(said).toBeDefined();
  expect(said!).not.toContain("/server/otlp");
  expect(relayed(await recipe(COMMAND))).toBe(false);
  await srv.reconfigure(withRelay());
  const on = laptop.cli(["remote", "refresh", srv.base]);
  expect(on.code).toBe(0);
  expect(on.out.split("\n").find((l) => l.includes("telemetry")) ?? "").toContain(`${srv.base}/server/otlp`);
  expect(relayed(await recipe(COMMAND))).toBe(true);
}, 300_000);
