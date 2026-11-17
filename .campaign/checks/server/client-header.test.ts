// client-header: every launch of a command linked from a server tells the server which splice the laptop runs, as
// `x-splice-client: <version>` in ANTHROPIC_CUSTOM_HEADERS beside any headers the command already sends, and the
// server's profile names its minimum client. An older laptop's turns are answered with the version to upgrade to
// (server-client-version); this is the laptop's half.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { Splice, freePort, mockHead } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

let srv: Server;
let laptop: Splice;
let key = "";
let version = "";

beforeAll(async () => {
  srv = await Server.boot({ toml: `\n[server.client]\nminimum = "0.0.1"\n` });
  laptop = await Splice.boot(mockHead({ key: "local", command: "claude-local", port: await freePort(), url: srv.up.url, model: MODEL }));
  key = srv.mint("ann@example.com").key;
  expect(laptop.cli(["login", srv.base, "--key-stdin"], `${key}\n`).code).toBe(0);
  expect(laptop.cli(["remote", "add", srv.base]).code).toBe(0);
  const v = laptop.cli(["version"]);
  version = /\d+\.\d+\.\d+\S*/.exec(`${v.out}${v.err}`)?.[0] ?? "";
  expect(version).not.toBe("");
}, 900_000);
afterAll(async () => {
  await laptop?.dispose();
  await srv?.dispose();
}, 90_000);

async function envOf(command: string): Promise<Record<string, string>> {
  const body = new URLSearchParams({ dangerouslySkipPermissions: "0", cwd: laptop.home, launch: "1" });
  const r = await laptop.api(`/launch/${command}`, { method: "POST", body: body.toString(), headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" } });
  expect(r.status).toBe(200);
  return ((await r.json()) as { env: Record<string, string> }).env;
}

const headerLines = (env: Record<string, string>) =>
  (env.ANTHROPIC_CUSTOM_HEADERS ?? "")
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean);

test("a linked command's launch names the laptop's splice in x-splice-client", async () => {
  const lines = headerLines(await envOf(COMMAND));
  const mine = lines.filter((l) => l.toLowerCase().startsWith("x-splice-client:"));
  expect(mine).toHaveLength(1);
  expect(mine[0]!.slice("x-splice-client:".length).trim()).toBe(version);
}, 120_000);

test("the laptop's own commands send no x-splice-client", async () => {
  expect(headerLines(await envOf("claude-local")).some((l) => l.toLowerCase().startsWith("x-splice-client:"))).toBe(false);
}, 120_000);

test("the profile names the server's minimum client", async () => {
  const p = (await (await fetch(`${srv.base}/server/profile`, { headers: { authorization: `Bearer ${key}` } })).json()) as {
    client: { minimum: string | null };
  };
  expect(p.client.minimum).toBe("0.0.1");
});
