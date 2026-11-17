// remote-window: a command linked from a team server launches with the context window the server declares for its
// model, read from the profile. `splice remote set <command> --window N` pins another window on the laptop, and
// `--window server` gives the command back the server's.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { Splice, freePort, mockHead } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

let srv: Server;
let laptop: Splice;

beforeAll(async () => {
  srv = await Server.boot({ head: (url, port) => mockHead({ key: "mock", command: COMMAND, port, url, model: MODEL, window: 200_000 }) });
  laptop = await Splice.boot(mockHead({ key: "local", command: "claude-local", port: await freePort(), url: srv.up.url, model: MODEL }));
  const key = srv.mint("ann@example.com").key;
  expect(laptop.cli(["login", srv.base, "--key-stdin"], `${key}\n`).code).toBe(0);
  expect(laptop.cli(["remote", "add", srv.base]).code).toBe(0);
}, 900_000);
afterAll(async () => {
  await laptop?.dispose();
  await srv?.dispose();
}, 90_000);

/** The context window a launch of [command] hands Claude Code. */
async function windowOf(command: string): Promise<string | undefined> {
  const body = new URLSearchParams({ dangerouslySkipPermissions: "0", cwd: laptop.home, launch: "1" });
  const r = await laptop.api(`/launch/${command}`, { method: "POST", body: body.toString(), headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" } });
  expect(r.status).toBe(200);
  return ((await r.json()) as { env: Record<string, string> }).env.CLAUDE_CODE_MAX_CONTEXT_TOKENS;
}

test("a linked command launches with the window the server declares for its model", async () => {
  expect(await windowOf(COMMAND)).toBe("200000");
}, 120_000);

test("remote set --window N pins a window on the laptop, and remote lists it", async () => {
  const r = laptop.cli(["remote", "set", COMMAND, "--window", "64000"]);
  expect(r.code).toBe(0);
  expect(await windowOf(COMMAND)).toBe("64000");
  expect(laptop.cli(["remote"]).out).toContain("64000");
}, 120_000);

test("the pinned window outlives a restart of the laptop's daemon", async () => {
  await laptop.restart();
  expect(await windowOf(COMMAND)).toBe("64000");
}, 300_000);

test("remote set --window server gives the command back the server's window", async () => {
  const r = laptop.cli(["remote", "set", COMMAND, "--window", "server"]);
  expect(r.code).toBe(0);
  expect(await windowOf(COMMAND)).toBe("200000");
}, 120_000);

test("a window that is not a positive number, or a command that is not linked from a server, is refused", () => {
  expect(laptop.cli(["remote", "set", COMMAND, "--window", "lots"]).code).not.toBe(0);
  expect(laptop.cli(["remote", "set", COMMAND, "--window", "0"]).code).not.toBe(0);
  expect(laptop.cli(["remote", "set", "claude-local", "--window", "64000"]).code).not.toBe(0);
});
