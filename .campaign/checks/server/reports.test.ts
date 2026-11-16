// ledger-reports: the operator answers "who used what, and what did it cost" from the ledger, by person, team (as
// it was when the request ran, or as it is today), day, model or device, over any window, as JSON, CSV or a markdown
// table, from the CLI, the control plane or (for an admin) the listener; a reference rate card prices the same
// tokens at what a hosted model would have cost.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { json, until } from "./lib/harness.ts";
import { MODEL, Server } from "./lib/server.ts";

interface Group {
  key: string;
  requests: number;
  errors: number;
  client_gone: number;
  prompt_tokens: number;
  cached_tokens: number;
  completion_tokens: number;
  cost_usd: number | null;
  reference_cost_usd: number | null;
}
interface Report {
  window: { from: string; to: string };
  groups: Group[];
}

const TURN_USD = 0.00242;
const REF_USD = 0.00366;

let srv: Server;
const keys: Record<string, { key: string; prefix: string }> = {};
let quietFrom = "";

beforeAll(async () => {
  srv = await Server.boot({
    server: `admins = ["ops@example.com"]\n`,
    toml: `
[server.reference]
input_usd_per_mtok = 3.0
cached_usd_per_mtok = 0.3
output_usd_per_mtok = 15.0

[server.teams]
by_domain = { "example.com" = "core", "partner.org" = "partners" }
`,
  });
  for (const p of ["ann@example.com", "bob@example.com", "pat@partner.org", "ops@example.com"]) keys[p] = srv.mint(p);
  // ann: two good turns; bob: one good turn and one the upstream fails; pat: one good turn.
  for (const p of ["ann@example.com", "ann@example.com", "bob@example.com", "pat@partner.org"]) {
    expect((await srv.turn(keys[p]!.key)).status).toBe(200);
  }
  srv.up.behaviour.status = 500;
  try {
    await srv.turn(keys["bob@example.com"]!.key);
  } finally {
    srv.up.behaviour.status = null;
  }
  await until("5 rows in the ledger", () => total(report(["--by", "person"])) >= 5, 30_000);
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const report = (args: string[]): Report => json<Report>(srv.cli(["server", "usage", "report", "--since", "1h", "--format", "json", ...args]));
const total = (r: Report) => r.groups.reduce((n, g) => n + g.requests, 0);
const group = (r: Report, key: string) => {
  const g = r.groups.find((x) => x.key === key);
  if (!g) throw new Error(`no group ${key} in ${JSON.stringify(r.groups.map((x) => x.key))}`);
  return g;
};

test("by person: requests, errors, tokens, cost from the rate card and cost at the reference rates", () => {
  const r = report(["--by", "person"]);
  expect(Date.parse(r.window.to) - Date.parse(r.window.from)).toBeCloseTo(3_600_000, -4);
  const ann = group(r, "ann@example.com");
  expect([ann.requests, ann.errors, ann.client_gone]).toEqual([2, 0, 0]);
  expect([ann.prompt_tokens, ann.cached_tokens, ann.completion_tokens]).toEqual([2400, 400, 80]);
  expect(ann.cost_usd).toBeCloseTo(2 * TURN_USD, 6);
  expect(ann.reference_cost_usd).toBeCloseTo(2 * REF_USD, 6);
  const bob = group(r, "bob@example.com");
  expect([bob.requests, bob.errors]).toEqual([2, 1]);
  expect(bob.prompt_tokens).toBe(1200);
  expect(group(r, "pat@partner.org").requests).toBe(1);
});

test("by team (from the email's domain), by model, by device and by day", () => {
  const team = report(["--by", "team"]);
  expect(group(team, "core").requests).toBe(4);
  expect(group(team, "partners").requests).toBe(1);
  const model = report(["--by", "model"]);
  expect(group(model, MODEL).requests).toBe(5);
  const device = report(["--by", "device"]);
  expect(group(device, keys["ann@example.com"]!.prefix).requests).toBe(2);
  const day = report(["--by", "day"]);
  expect(day.groups.every((g) => /^\d{4}-\d{2}-\d{2}$/.test(g.key))).toBe(true);
  expect(total(day)).toBe(5);
});

test("a team change keeps old rows under the old team; current-team regroups them under today's", async () => {
  expect(srv.cli(["server", "person", "set", "bob@example.com", "--team", "partners"]).code).toBe(0);
  expect((await srv.turn(keys["bob@example.com"]!.key)).status).toBe(200);
  await until("6 rows", () => total(report(["--by", "team"])) >= 6, 30_000);
  const then = report(["--by", "team"]);
  expect(group(then, "core").requests).toBe(4);
  expect(group(then, "partners").requests).toBe(2);
  const now = report(["--by", "current-team"]);
  expect(group(now, "core").requests).toBe(2);
  expect(group(now, "partners").requests).toBe(4);
  quietFrom = new Date(Date.now() + 1_000).toISOString();
}, 60_000);

test("a window after every request holds nothing; --from and --to bound it", async () => {
  await Bun.sleep(1_500);
  const later = json<Report>(srv.cli(["server", "usage", "report", "--by", "person", "--format", "json", "--from", quietFrom, "--to", new Date().toISOString()]));
  expect(total(later)).toBe(0);
});

test("CSV and markdown carry the same groups", () => {
  const csv = srv.cli(["server", "usage", "report", "--by", "person", "--since", "1h", "--format", "csv"]);
  expect(csv.code).toBe(0);
  const lines = csv.out.trim().split("\n");
  expect(lines[0]).toContain("key");
  expect(lines[0]).toContain("requests");
  expect(lines[0]).toContain("cost_usd");
  expect(lines.some((l) => l.startsWith("ann@example.com,2,"))).toBe(true);
  const md = srv.cli(["server", "usage", "report", "--by", "person", "--since", "1h", "--format", "md"]);
  expect(md.code).toBe(0);
  expect(md.out).toMatch(/\|\s*ann@example\.com\s*\|\s*2\s*\|/);
});

test("the control plane answers the same report, and refuses without the management key", async () => {
  const r = await srv.s.api("/api/server/usage?by=person&since=1h&format=json");
  expect(r.status).toBe(200);
  const body = (await r.json()) as Report;
  expect(group(body, "ann@example.com").requests).toBe(2);
  const csv = await srv.s.api("/api/server/usage?by=team&since=1h&format=csv");
  expect(csv.headers.get("content-type")).toContain("text/csv");
  expect((await fetch(`http://127.0.0.1:${srv.s.controlPort}/api/server/usage?by=person&since=1h`)).status).toBe(401);
});

test("an admin reads the report over the listener; anyone else is refused", async () => {
  const at = `${srv.base}/server/api/usage?by=person&since=1h&format=json`;
  const admin = await fetch(at, { headers: { authorization: `Bearer ${keys["ops@example.com"]!.key}` } });
  expect(admin.status).toBe(200);
  expect(group((await admin.json()) as Report, "pat@partner.org").requests).toBe(1);
  expect((await fetch(at, { headers: { authorization: `Bearer ${keys["ann@example.com"]!.key}` } })).status).toBe(403);
  expect((await fetch(at)).status).toBe(401);
});
