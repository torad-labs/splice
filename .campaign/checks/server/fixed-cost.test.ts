// server-fixed-cost: a provider the team pays for by the hour (a rented GPU box) says so with `hourly_usd`. A usage
// report meters it over the report's window from the host's boot time, read when the report is made: the hours the
// host has been up inside the window. The report carries `fixed: [{provider, hours, cost_usd}]` and
// `totals: {cost_usd, fixed_usd, actual_usd, reference_cost_usd}`, where actual is the rate card's cost plus the fixed
// cost. A daemon restart does not restart the meter, since the box kept running.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { json, mockHead } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

interface Report {
  window: { from: string; to: string };
  groups: { key: string; requests: number; cost_usd: number | null; reference_cost_usd: number | null }[];
  fixed: { provider: string; hours: number; cost_usd: number }[];
  totals: { cost_usd: number; fixed_usd: number; actual_usd: number; reference_cost_usd: number };
}

const HOURLY = 2.5;
let srv: Server;

beforeAll(async () => {
  srv = await Server.boot({
    head: (url, port) => mockHead({ key: "mock", command: COMMAND, port, url, model: MODEL, provider: `hourly_usd = ${HOURLY}` }),
    toml: `\n[server.reference]\ninput_usd_per_mtok = 3.0\ncached_usd_per_mtok = 0.3\noutput_usd_per_mtok = 15.0\n`,
  });
  const key = srv.mint("ann@example.com").key;
  for (let i = 0; i < 2; i++) expect((await srv.turn(key)).status).toBe(200);
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const report = (since: string): Report =>
  json<Report>(srv.cli(["server", "usage", "report", "--since", since, "--by", "person", "--format", "json"]));

/** The hours this host has been up within the last [windowHours], from the kernel's own boot time. */
function upHours(windowHours: number): number {
  const btime = Number(/^btime (\d+)$/m.exec(readFileSync("/proc/stat", "utf8"))![1]);
  return Math.min(windowHours, (Date.now() / 1000 - btime) / 3600);
}

const metered = async (since: string, windowHours: number) => {
  let r: Report = report(since);
  for (let i = 0; i < 20 && r.groups.reduce((n, g) => n + g.requests, 0) < 2; i++) {
    await Bun.sleep(500);
    r = report(since);
  }
  expect(r.fixed).toHaveLength(1);
  const row = r.fixed[0]!;
  expect(row.provider).toBe("mock");
  expect(row.hours).toBeCloseTo(upHours(windowHours), 1);
  expect(row.cost_usd).toBeCloseTo(row.hours * HOURLY, 4);
  return { r, row };
};

test("a provider paid by the hour is metered over the window from the host's boot time", async () => {
  await metered("1h", 1);
  await metered("3h", 3);
}, 120_000);

test("the totals add the fixed cost to the rate card's cost, beside the reference cost", async () => {
  const { r, row } = await metered("1h", 1);
  const card = r.groups.reduce((n, g) => n + (g.cost_usd ?? 0), 0);
  const ref = r.groups.reduce((n, g) => n + (g.reference_cost_usd ?? 0), 0);
  expect(card).toBeGreaterThan(0);
  expect(r.totals.cost_usd).toBeCloseTo(card, 6);
  expect(r.totals.fixed_usd).toBeCloseTo(row.cost_usd, 6);
  expect(r.totals.actual_usd).toBeCloseTo(card + row.cost_usd, 6);
  expect(r.totals.reference_cost_usd).toBeCloseTo(ref, 6);
}, 120_000);

test("a daemon restart does not restart the meter", async () => {
  await srv.s.restart();
  const { row } = await metered("1h", 1);
  expect(row.hours).toBeGreaterThan(upHours(1) - 0.05);
}, 300_000);

test("a provider with no hourly price has no fixed row, and its totals carry no fixed cost", async () => {
  const plain = await Server.boot();
  try {
    expect((await plain.turn(plain.mint("bo@example.com").key)).status).toBe(200);
    await Bun.sleep(1500);
    const r = json<Report>(plain.cli(["server", "usage", "report", "--since", "1h", "--by", "person", "--format", "json"]));
    expect(r.fixed).toEqual([]);
    expect(r.totals.fixed_usd).toBe(0);
    expect(r.totals.actual_usd).toBeCloseTo(r.totals.cost_usd, 6);
  } finally {
    await plain.dispose();
  }
}, 900_000);
