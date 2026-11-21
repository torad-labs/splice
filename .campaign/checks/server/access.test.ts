// server-access: an operator decides which commands a team and a person may use. `splice server team set <team>
// --commands a,b` sets a team's, `splice server person set <email> --commands a,b|all` sets a person's, and the
// person's setting wins over the team's. A turn on a command the person may not use is a 403 permission_error naming
// the command, and nothing reaches the upstream; someone with no setting at all may use every command.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { freePort, mockHead, turn, type Turn } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

const TWO = "claude-two";
let srv: Server;

beforeAll(async () => {
  const twoPort = await freePort();
  srv = await Server.boot({
    commands: [COMMAND, TWO],
    head: (url, port) =>
      mockHead({ key: "mock", command: COMMAND, port, url, model: MODEL }) +
      mockHead({ key: "two", command: TWO, port: twoPort, url, model: MODEL }),
    toml: `\n[server.teams]\nby_domain = { "example.com" = "core", "partner.test" = "partners" }\n`,
  });
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

/** A turn on [command] with [key]. */
const on = (command: string, key: string): Promise<Turn> => turn(`${srv.base}/${command}/v1/messages`, { key, model: MODEL });

/** How many turns reached the upstream so far. */
const reached = () => srv.up.completions().length;

const forbidden = (t: Turn, command: string) => {
  expect(t.status).toBe(403);
  expect(t.error?.type).toBe("permission_error");
  expect(t.error?.message).toContain(command);
};

test("someone with no setting may use every command", async () => {
  const key = srv.mint("free@elsewhere.test").key;
  expect((await on(COMMAND, key)).status).toBe(200);
  expect((await on(TWO, key)).status).toBe(200);
}, 120_000);

test("a team's commands hold for its people: another command is a 403 naming it, and nothing reaches the upstream", async () => {
  const r = srv.cli(["server", "team", "set", "core", "--commands", COMMAND]);
  expect(r.code).toBe(0);
  const ann = srv.mint("ann@example.com").key;
  expect((await on(COMMAND, ann)).status).toBe(200);
  const before = reached();
  forbidden(await on(TWO, ann), TWO);
  expect(reached()).toBe(before);
  const partner = srv.mint("pat@partner.test").key;
  expect((await on(TWO, partner)).status).toBe(200);
}, 120_000);

test("a person's own commands win over the team's, both wider and narrower", async () => {
  expect(srv.cli(["server", "person", "set", "bea@example.com", "--commands", "all"]).code).toBe(0);
  const bea = srv.mint("bea@example.com").key;
  expect((await on(TWO, bea)).status).toBe(200);
  expect(srv.cli(["server", "person", "set", "cal@example.com", "--commands", TWO]).code).toBe(0);
  const cal = srv.mint("cal@example.com").key;
  expect((await on(TWO, cal)).status).toBe(200);
  forbidden(await on(COMMAND, cal), COMMAND);
}, 120_000);

test("a change takes effect on the person's next turn, with the keys they already hold", async () => {
  const dan = srv.mint("dan@example.com").key;
  forbidden(await on(TWO, dan), TWO);
  expect(srv.cli(["server", "team", "set", "core", "--commands", `${COMMAND},${TWO}`]).code).toBe(0);
  expect((await on(TWO, dan)).status).toBe(200);
}, 120_000);

test("a command the server does not serve is refused at the CLI, and so is an empty list", () => {
  expect(srv.cli(["server", "team", "set", "core", "--commands", "claude-nope"]).code).not.toBe(0);
  expect(srv.cli(["server", "person", "set", "eve@example.com", "--commands", "claude-nope"]).code).not.toBe(0);
  expect(srv.cli(["server", "team", "set", "core", "--commands", ""]).code).not.toBe(0);
});
