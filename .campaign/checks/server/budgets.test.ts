// person-budgets: daily and monthly budgets for people and teams, priced from the model's rate card, that warn or
// block; a blocked turn reaches no upstream and says it was the budget; spend survives a restart.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { json, type Turn } from "./lib/harness.ts";
import { Server } from "./lib/server.ts";

// One mock turn: 1,000 fresh input, 200 cached and 40 output tokens at 2.0, 0.5 and 8.0 USD per million.
const TURN_USD = 0.00242;
let srv: Server;
beforeAll(async () => {
  srv = await Server.boot({ toml: `\n[server.budgets]\nperson_daily_usd = 1000\naction = "block"\n` });
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

interface Budget {
  scope: string;
  name: string;
  daily_usd: number | null;
  monthly_usd: number | null;
  action: string;
  spent_today_usd: number;
  spent_month_usd: number;
  state: string;
}
const budgets = () => json<{ budgets: Budget[] }>(srv.cli(["server", "budget", "list", "--json"])).budgets;
const budgetOf = (scope: string, name: string) => budgets().find((b) => b.scope === scope && b.name === name)!;
const set = (args: string[]) => expect(srv.cli(["server", "budget", "set", ...args]).code).toBe(0);
const blocked = (t: Turn) => {
  expect(t.status >= 400 || t.error !== null).toBe(true);
  expect(t.reply).not.toContain("from-a");
  expect(`${t.error?.message ?? ""}${t.body}`.toLowerCase()).toContain("budget");
};

test("a person's daily block budget refuses their next turn once spent, before any upstream, and no one else's", async () => {
  set(["--person", "amy@example.com", "--daily-usd", "0.001", "--action", "block"]);
  const amy = srv.mint("amy@example.com").key;
  expect((await srv.turn(amy)).status).toBe(200);
  const before = srv.up.completions().length;
  blocked(await srv.turn(amy));
  expect(srv.up.completions().length).toBe(before);
  expect((await srv.turn(srv.mint("ben@example.com").key)).status).toBe(200);
  const b = budgetOf("person", "amy@example.com");
  expect(b.daily_usd).toBe(0.001);
  expect(Math.abs(b.spent_today_usd - TURN_USD)).toBeLessThan(1e-6);
  expect(b.state).toBe("over");
}, 120_000);

test("a warn budget serves every turn and reads over once spent", async () => {
  set(["--person", "cal@example.com", "--daily-usd", "0.001", "--action", "warn"]);
  const cal = srv.mint("cal@example.com").key;
  expect((await srv.turn(cal)).status).toBe(200);
  expect((await srv.turn(cal)).status).toBe(200);
  const b = budgetOf("person", "cal@example.com");
  expect(b.action).toBe("warn");
  expect(Math.abs(b.spent_today_usd - 2 * TURN_USD)).toBeLessThan(1e-6);
  expect(b.state).toBe("over");
}, 120_000);

test("a monthly budget blocks like a daily one", async () => {
  set(["--person", "dee@example.com", "--monthly-usd", "0.001", "--action", "block"]);
  const dee = srv.mint("dee@example.com").key;
  expect((await srv.turn(dee)).status).toBe(200);
  blocked(await srv.turn(dee));
  expect(Math.abs(budgetOf("person", "dee@example.com").spent_month_usd - TURN_USD)).toBeLessThan(1e-6);
}, 120_000);

test("a team's budget counts every member's spend and blocks the whole team, and only that team", async () => {
  for (const p of ["eve@example.com", "fay@example.com"]) expect(srv.cli(["server", "person", "set", p, "--team", "red"]).code).toBe(0);
  set(["--team", "red", "--daily-usd", "0.004", "--action", "block"]);
  const eve = srv.mint("eve@example.com").key;
  const fay = srv.mint("fay@example.com").key;
  expect((await srv.turn(eve)).status).toBe(200);
  expect((await srv.turn(fay)).status).toBe(200);
  blocked(await srv.turn(eve));
  blocked(await srv.turn(fay));
  expect((await srv.turn(srv.mint("gus@example.com").key)).status).toBe(200);
  const red = budgetOf("team", "red");
  expect(Math.abs(red.spent_today_usd - 2 * TURN_USD)).toBeLessThan(1e-6);
}, 120_000);

test("spend survives a restart: a blocked person is still blocked after it", async () => {
  await srv.s.restart();
  const amy = srv.mint("amy@example.com").key;
  blocked(await srv.turn(amy));
  expect(Math.abs(budgetOf("person", "amy@example.com").spent_today_usd - TURN_USD)).toBeLessThan(1e-6);
}, 300_000);
