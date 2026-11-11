// Usage, the header's door to Results by command and the Performance measurements folded under the bands. Results reads the
// daemon's fold over every request in its own window (1 hour, 24 hours or 7 days; the page's 5 hours as 24 hours and its
// 30 days as 7 days), so its door says what its figure covers wherever that differs from the doors beside it: the table's
// window when it is not the page's, and all requests when a project is picked. The measurements read the exact cohort
// (contract=1): 5 hours stays 5 hours, 30 days stays 30 days, a project narrows them, and their Requests links agree.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";
import { live } from "./live.ts";
import { performanceSummary, performanceRows } from "./usage-performance-fixture.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
type Row = Record<string, unknown>;
const group = (key: string, over: Row): Row => ({
  key, requests: 0, input_tokens: 0, cached_tokens: 0, output_tokens: 0, cost_usd: null, plan_requests: 0, plan_value_usd: null,
  unpriced_uncounted_requests: 0, unpriced_undeclared_requests: 0, unpriced_local_requests: 0, unanswered_requests: 0, ...over,
});
const proj = (p: string) => ({ id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" });
const rows = Array.from({ length: 6 }, (_, i) => ({ turn: `o${i}`, ts: now - 30_000 * (i + 1), first_byte: 100, total: 500, outcome: "ok", model: "m", account: "primary", session: "s1", project: proj("website") }));
let queryWorkerBusy = false, refuseSummary = false, refuseTurnsOnce = false, queryBusyResponses = 0;
let limitPerfHttp = false, admittedPerfHttp = 0, refuseSettings = false;
let artifactFailure: string | null = null;
let profileState = "idle", savedObjectives: Row[] = [];
const httpHolds = new Map<string, { entered: () => void; wait: Promise<void> }>();
function holdHttp(path: string, method = "GET") {
  let entered!: () => void, release!: () => void;
  const ready = new Promise<void>((resolve) => { entered = resolve; });
  const wait = new Promise<void>((resolve) => { release = resolve; });
  httpHolds.set(`${method} ${path}`, { entered, wait });
  return { ready, release };
}
const httpCapacity = () => Response.json({ schema_version: 1, status: "busy", reason: "control_query_capacity" }, { status: 429 });
let heldQuery: { path: string; cursor: boolean; entered: () => void; wait: Promise<void> } | null = null;
function holdQuery(path: string, cursor = false) {
  let entered!: () => void, release!: () => void;
  const ready = new Promise<void>((resolve) => { entered = resolve; });
  const wait = new Promise<void>((resolve) => { release = resolve; });
  heldQuery = { path, cursor, entered, wait };
  return { ready, release };
}
// Model the actual route's single query worker. Concurrent reads receive its real refusal shape;
// tests judge the selected cohort and rendered views, not request counts or implementation details.
async function workerResponse(url: URL) {
  const busy = () => Response.json({ schema_version: 1, contract_version: 1, status: "busy", error: { code: "query_busy", reason: "query_in_progress" } }, { status: 409 });
  if (url.pathname === "/api/perf/turns" && refuseTurnsOnce && !url.searchParams.has("cursor")) {
    refuseTurnsOnce = false;
    return httpCapacity();
  }
  if (queryWorkerBusy) { queryBusyResponses++; return busy(); }
  if (refuseSummary && url.pathname === "/api/perf/summary") return busy();
  queryWorkerBusy = true;
  try {
    if (heldQuery?.path === url.pathname && heldQuery.cursor === url.searchParams.has("cursor")) {
      const hold = heldQuery; heldQuery = null; hold.entered(); await hold.wait;
    } else await Bun.sleep(30);
    const picked = url.searchParams.get("project") === "/work/website", name = picked ? "current" : "superseded";
    const total_ms = { count: 20, p50: 500, p95: 900, max: 1200 };
    const summary = performanceSummary(url, { plan: {
      count: 20, read_status: "readable", metrics_complete: true, failure_share: picked ? .05 : .5,
      outcomes: { ok: picked ? 19 : 10, "error:overloaded": picked ? 1 : 10 }, total_ms,
    } });
    if (url.pathname === "/api/perf/summary") return Response.json(summary);
    if (url.pathname === "/api/perf/series") return Response.json({ ...summary, series: [{ from_ms: now - 1000, until_ms: now, metrics: { [`${name}_gauge`]: { last: 7 } } }] });
    if (url.pathname === "/api/perf/diagnose") return Response.json({ ...summary, evidence: [{ metric: `${name}_diagnosis`, mean_ms: 7, classification: "observed", inference: "recorded" }] });
    const cursor = url.searchParams.has("cursor"), turn = `${name}-${cursor ? "second" : "first"}-page`;
    return Response.json({ ...performanceRows(url), rows: [{ head: "plan", turn_id: turn, ts: now, model: "one-query-worker", outcome: "ok" }], total_matching: 110, next_cursor: cursor ? null : "page-two" });
  } finally { queryWorkerBusy = false; }
}

async function fixtureResponse(req: Request) {
    const url = new URL(req.url), path = url.pathname;
    if (limitPerfHttp) {
      if (path === "/api/perf/schema") return Response.json({ status: "ok", metrics: [] });
      if (path === "/api/perf/runtime") return Response.json({ status: "ok", as_of_ms: now, age_ms: 0, measurements: { process_cpu: 7 } });
      if (path === "/api/perf/profiles/capabilities") return Response.json({ status: "ok", capabilities: [{ id: "jfr.cpu", supported: true }] });
      if (path === "/api/perf/objectives") {
        if (req.method === "PUT") {
          if (refuseSettings) return httpCapacity();
          const body = await req.json() as { objectives: Row[] }; savedObjectives = body.objectives;
        }
        return Response.json({ schema_version: 1, status: "ok", revision: savedObjectives.length ? "saved-objective" : "initial", objectives: savedObjectives, rules: [] });
      }
      if (path.startsWith("/api/perf/profiles")) {
        if (path.endsWith("/artifact")) {
          if (artifactFailure === "malformed") return new Response('{"reason":', { status: 429, headers: { "Content-Type": "application/json" } });
          if (artifactFailure === "non-json") return new Response('{"reason":"artifact_reader_capacity"}', { status: 429, headers: { "Content-Type": "text/plain" } });
          if (artifactFailure === "unknown") return Response.json({ reason: "private_path_should_not_be_shown", path: "/fixture/private" }, { status: 429 });
          if (artifactFailure === "oversized") return Response.json({ reason: "artifact_reader_capacity", padding: "x".repeat(4096) }, { status: 429 });
          if (artifactFailure) return Response.json({ schema_version: 1, status: "busy", reason: artifactFailure }, { status: 429 });
          return new Response("fixture-profile", { headers: { "Content-Type": "application/octet-stream" } });
        }
        if (req.method === "POST") profileState = path.endsWith("/stop") ? "stopped" : "running";
        return Response.json({ status: profileState, profile_id: "bounded-profile", artifact_available: profileState === "stopped" });
      }
    }
    if (url.searchParams.get("model") === "one-query-worker" && url.searchParams.get("contract") === "1" && ["/api/perf/summary", "/api/perf/series", "/api/perf/diagnose", "/api/perf/turns"].includes(path)) return workerResponse(url);
    if (path === "/api/heads") return Response.json({ heads: [{ key: "plan", label: "claude-plan" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "plan", provider: "anthropic", models: [] }] });
    if (path === "/api/status") return Response.json({ registry: [{ key: "plan", family: "anthropic", authKind: "claude-oauth" }] });
    if (path === "/api/perf/summary") {
      const picked = url.searchParams.get("project") === "/work/website", bad = picked ? 1 : 3, count = picked ? 20 : 100;
      const total_ms = { count, p50: 500, p95: 900, max: 1200 };
      return Response.json(performanceSummary(url, { plan: { count, local_steps: 0, empty: false, read_status: "readable", metrics_complete: true,
        outcomes: { ok: count - bad, "error:overloaded": bad }, failure_share: bad / count, failure_shares: { "error:overloaded": bad / count },
        models: { m: { count, failure_share: bad / count, total_ms } }, total_ms, cache_hit_ratio: .8, retries: 2, refreshes: 0, peak_inflight: 3,
        time_before_first_byte_ms: { count, p50: 100, p95: 200, max: 300 } } }));
    }
    if (path === "/api/perf/turns") {
      if (url.searchParams.get("contract") === "1") return Response.json(performanceRows(url));
      const held = Number(url.searchParams.get("n"));
      const g = group("primary", { requests: 6, input_tokens: 6000, output_tokens: 600, plan_requests: 6, plan_value_usd: 1 });
      const usage = { totals: group("totals", g), accounts: [g], models: [], sessions: [], days: [group(new Date().toLocaleDateString("en-CA"), g)] };
      const out = held > 1 ? rows : [];
      return Response.json({ heads: [{ key: "plan", label: "plan", count: 6, returned: out.length, truncated: false, rows: out, ...(held > 1 ? {} : { usage }), asks: [] }] });
    }
    if (path === "/api/sessions") return Response.json({ sessions: [{ session_id: "s1-full", name: "api", head: "plan", availability: "live", status: "idle", cwd: "/work/website/repo", updated_at: new Date(now - 60_000).toISOString(), project: proj("website") }] });
    if (path === "/api/accounts") return Response.json({ accounts: [] });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (path === "/api/topology") return Response.json({ topology: { providers: {} } });
    if (path.endsWith("/turns/live")) return Response.json({ turns: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
}
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    // Legacy rows and the legacy fold belong to Usage's ordinary route; the guarded Perf contract has four admissions.
    const legacy = (path === "/api/perf/turns" || path === "/api/perf/summary") && url.searchParams.get("contract") !== "1";
    const guarded = limitPerfHttp && path.startsWith("/api/perf/") && !legacy;
    if (!guarded) return fixtureResponse(req);
    if (admittedPerfHttp >= 4) return httpCapacity();
    admittedPerfHttp++;
    try {
      const key = `${req.method} ${path}`, hold = httpHolds.get(key);
      if (hold) { httpHolds.delete(key); hold.entered(); await hold.wait; }
      else await Bun.sleep(60);
      return await fixtureResponse(req);
    } finally { admittedPerfHttp--; }
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 60_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

const door = (p: Page) => p.locator("#doors [data-act=door][data-v=results]");
const fold = (p: Page) => p.locator("#band-perf [data-act=perffold]");
const said = (p: Page) => door(p).locator(".upl-dq");
async function open(query: string, run: (p: Page) => Promise<void>) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } });
  const p = await context.newPage();
  try {
    await p.goto(`http://127.0.0.1:${server.port}/usage.html${query}`);
    // 3 of 100 requests failed in the fold, whatever project is picked, however the page rounds it
    await expect(door(p).locator("[data-tn=door-failed]")).toHaveText(/^3(\.0)?%$/, { timeout: 20_000 });
    await run(p);
  } finally {
    await context.close();
  }
}

test("Usage: where the Results table and the page agree, the door reads its figure as failed and nothing more", async () => {
  await open("", async (p) => {
    await expect(said(p)).toHaveText("failed");
  });
}, 90_000);

test("Usage: the Results door names the table's window when the page's window is one the table does not keep", async () => {
  await open("", async (p) => {
    await p.locator("#win [data-act=win][data-v='5h']").click();
    await expect(p.locator("#band-results [data-act=perfwin][aria-pressed=true], #band-results [data-act=perfwin][aria-checked=true]")).toContainText("24 hours", { timeout: 20_000 });
    await expect(said(p)).toContainText("24 hours", { timeout: 20_000 });
    await p.locator("#win [data-act=win][data-v='30d']").click();
    await expect(said(p)).toContainText("7 days", { timeout: 20_000 });
    // the table's own switch moves the door with it
    await p.locator("#band-results [data-act=perfwin][data-v='1h']").click();
    await expect(said(p)).toContainText("1 hour", { timeout: 20_000 });
    expect(await live(said(p), (el) => el.getClientRects().length)).toBe(1);
  });
}, 90_000);

test("Usage: with a project picked, the Results door says its figure is over all requests, as the band's head does", async () => {
  await open("?project=/work/website", async (p) => {
    await expect(p.locator("#band-results .bandhead")).toContainText("All requests", { timeout: 20_000 });
    await expect(said(p)).toContainText(/all requests/i, { timeout: 20_000 });
    expect(await live(said(p), (el) => el.getClientRects().length)).toBe(1);
  });
}, 90_000);

test("Usage: the folded Performance measurements open expandable diagnostics for a command", async () => {
  await open("", async (p) => {
    await fold(p).click();
    await p.locator("#band-perf .pc-deep > summary").click();
    await p.getByRole("button", { name: "Profiles", exact: true }).click();
    await p.getByRole("button", { name: "Overview", exact: true }).click();
    await expect(p.getByRole("button", { name: "Overview", exact: true })).toHaveAttribute("aria-current", "page");
    await p.locator('#band-perf [data-pc-command="plan"] > summary').click();
    const outcomes = p.locator(".pc-command-detail .pc-section").filter({ hasText: "Recorded outcomes" });
    const overloaded = outcomes.getByRole("link", { name: "error:overloaded", exact: true });
    await expect(overloaded).toBeVisible();
    expect(new URL((await overloaded.getAttribute("href"))!, "http://fixture").searchParams.get("outcome")).toBe("error:overloaded");
    await expect(outcomes).toContainText("3");
    await expect(p.locator(".pc-command-detail")).toContainText("Before first byte");
    await p.locator(".pc-command-detail").getByText("Stage sample counts", { exact: true }).click();
    await expect(p.getByRole("table", { name: "claude-plan stages", exact: true })).toContainText("Before first byte");
    await expect(p.getByRole("table", { name: "claude-plan models", exact: true })).toContainText("m");
    await expect(p.locator(".pc-command-detail")).toContainText("80%");
    await expect(p.locator(".pc-command-detail")).toContainText("do not add up");
    const link = p.locator(".pc-command-detail").getByRole("link", { name: "Open failures", exact: true });
    const href = new URL((await link.getAttribute("href"))!, "http://fixture");
    expect(href.searchParams.get("contract")).toBe("1");
    expect(href.searchParams.get("head")).toBe("plan");
    expect(href.searchParams.get("outcome")).toBe("failed");
    expect(Number(href.searchParams.get("until")) - Number(href.searchParams.get("from"))).toBe(7 * 86400000);
  });
}, 90_000);

test("Usage: bounded Perf admission preserves startup views and pending profile and objective actions", async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } });
  const p = await context.newPage(), releases: (() => void)[] = [];
  limitPerfHttp = true; profileState = "idle"; savedObjectives = [];
  try {
    await p.goto(`http://127.0.0.1:${server.port}/usage.html?contract=1&model=one-query-worker&project=/work/website&from=${now - 60000}&until=${now}`);
    const panel = p.locator("#band-perf"), content = panel.locator(".pc-content");
    await expect(content).toHaveAttribute("aria-busy", "false");
    await panel.locator('[data-pc-tab="runtime"]').click();
    await expect(content).toContainText("Current process sample");
    await expect(panel.getByRole("heading", { name: "CPU", exact: true })).toBeVisible();
    await expect(panel).toContainText("process CPU");
    await expect(panel).toContainText("7");
    await panel.locator('[data-pc-tab="profiles"]').click();
    await expect(content).toContainText("Capabilities: Ok");
    await expect(panel.getByRole("button", { name: "Start capture", exact: true })).toBeEnabled();

    // Occupy every server admission. A user action must remain pending while newer cohorts
    // supersede the reads; aborting a browser fetch would not release these held server requests.
    const reads = ["/api/perf/summary", "/api/perf/runtime", "/api/perf/alerts", "/api/perf/alerts/status"].map((path) => holdHttp(path));
    releases.push(...reads.map((hold) => hold.release));
    await panel.getByRole("button", { name: "Refresh", exact: true }).click();
    await Promise.all(reads.map((hold) => hold.ready));
    await panel.getByRole("button", { name: "Start capture", exact: true }).click();
    await expect(panel.locator(".pc-message")).toHaveText("Performance action pending…");
    for (const project of ["/work/old-a", "/work/old-b", "/work/website"]) {
      await panel.locator('[name="project"]').fill(project);
      await panel.getByRole("button", { name: "Measure", exact: true }).click();
    }
    await expect(panel.getByRole("button", { name: "Start capture", exact: true })).toBeDisabled();
    reads.forEach((hold) => hold.release());
    await expect(panel.locator(".pc-message")).toHaveText("Capture: Running");
    await expect(content).toHaveAttribute("aria-busy", "false");
    await expect(panel.locator(".pc-coverage .pc-state")).toHaveText("Ok");
    await panel.getByRole("button", { name: "Stop capture", exact: true }).click();
    await expect(panel.locator(".pc-message")).toHaveText("Capture: Stopped");
    const artifact = p.waitForEvent("download");
    await panel.getByRole("button", { name: "Download artifact", exact: true }).click();
    expect((await artifact).suggestedFilename()).toBe("splice-profile-bounded-profile.jfr");
    await expect(panel.locator(".pc-message")).toHaveText("Profile artifact downloaded");
    for (const refusal of ["control_query_capacity", "artifact_reader_capacity", "oversized", "unknown", "malformed", "non-json"]) {
      artifactFailure = refusal;
      await panel.getByRole("button", { name: "Download artifact", exact: true }).click();
      const reason = ["control_query_capacity", "artifact_reader_capacity"].includes(refusal) ? ` · ${refusal.replaceAll("_", " ")}` : "";
      await expect(panel.locator(".pc-message")).toHaveText(`Artifact: Error${reason}`);
      await expect(panel.getByRole("button", { name: "Download artifact", exact: true })).toBeEnabled();
    }
    artifactFailure = null;

    await panel.locator('[data-pc-tab="objectives"]').click();
    const revision = panel.locator("details.pc-detail").filter({ hasText: "Settings revision" });
    if (!(await revision.evaluate((element) => (element as HTMLDetailsElement).open))) await revision.locator("summary").click();
    await expect(revision).toContainText("initial");
    const editor = panel.locator("details[data-pc-editor]");
    const openEditor = async () => {
      if (!(await editor.evaluate((element) => (element as HTMLDetailsElement).open))) await editor.locator(":scope > summary").click();
    };
    await openEditor();
    const save = holdHttp("/api/perf/objectives", "PUT"); releases.push(save.release);
    await panel.locator('[name="settings"]').fill(JSON.stringify({ schema_version: 1, objectives: [{ id: "saved-through-refresh", cohort: {}, completion_ms: 1000 }], rules: [] }));
    await panel.getByRole("button", { name: "Save objectives and rules", exact: true }).click();
    await save.ready;
    // The earlier document is already submitted. New textarea edits and builder additions
    // must survive its completion and remain explicitly unsaved.
    await panel.locator('[name="settings"]').fill(JSON.stringify({ schema_version: 1, objectives: [{ id: "newer-unsaved-objective", cohort: {}, completion_ms: 2000 }], rules: [] }));
    await panel.getByText("Add objective", { exact: true }).click();
    const builder = panel.locator('[data-pc-form="add-objective"]');
    await builder.locator('[name="id"]').fill("builder-after-submit");
    await builder.getByRole("button", { name: "Add to draft", exact: true }).click();
    await panel.getByRole("button", { name: "Measure", exact: true }).click();
    await expect(panel.getByRole("button", { name: "Save objectives and rules", exact: true })).toBeDisabled();
    await expect(panel.getByRole("button", { name: "Discard edits and reload", exact: true })).toBeDisabled();
    save.release();
    await expect(panel.locator(".pc-message")).toHaveText("Settings saved. Newer draft edits are unsaved.");
    await expect(panel.locator(".pc-comparisons")).toContainText("saved-through-refresh");
    await expect(panel.locator(".pc-comparisons")).not.toContainText("newer-unsaved-objective");
    await expect(revision).toContainText("saved-objective");
    await expect(panel.locator('[name="settings"]')).toHaveValue(/newer-unsaved-objective/);
    await expect(panel.locator('[name="settings"]')).toHaveValue(/builder-after-submit/);
    const reload = holdHttp("/api/perf/objectives"); releases.push(reload.release);
    await panel.getByRole("button", { name: "Discard edits and reload", exact: true }).click();
    await reload.ready;
    await openEditor();
    await panel.locator('[name="settings"]').fill(JSON.stringify({ schema_version: 1, objectives: [{ id: "edited-during-reload", cohort: {}, completion_ms: 3000 }], rules: [] }));
    await panel.getByText("Add objective", { exact: true }).click();
    await builder.locator('[name="id"]').fill("builder-during-reload");
    // Updating measurements must not close the open builder or erase its unsubmitted fields.
    await panel.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(builder).toBeVisible();
    await expect(builder.locator('[name="id"]')).toHaveValue("builder-during-reload");
    await p.locator("#win [data-act=win][data-v='5h']").click();
    await expect(builder).toBeVisible();
    await expect(builder.locator('[name="id"]')).toHaveValue("builder-during-reload");
    await builder.getByRole("button", { name: "Add to draft", exact: true }).click();
    reload.release();
    await expect(panel.locator(".pc-message")).toHaveText("Objective settings reloaded. Newer draft edits are unsaved.");
    await expect(revision).toContainText("saved-objective");
    await expect(panel.locator(".pc-comparisons")).toContainText("saved-through-refresh");
    await expect(panel.locator(".pc-comparisons")).not.toContainText("edited-during-reload");
    await expect(panel.locator('[name="settings"]')).toHaveValue(/edited-during-reload/);
    await expect(panel.locator('[name="settings"]')).toHaveValue(/builder-during-reload/);
    await panel.getByRole("button", { name: "Discard edits and reload", exact: true }).click();
    await expect(panel.locator(".pc-message")).toHaveText("Objective settings reloaded");
    await expect(panel.locator('[name="settings"]')).not.toHaveValue(/edited-during-reload|builder-during-reload/);
    await openEditor();
    refuseSettings = true;
    await panel.getByRole("button", { name: "Save objectives and rules", exact: true }).click();
    await expect(panel.locator(".pc-message")).toHaveText("control_query_capacity");
    await expect(panel.getByRole("button", { name: "Save objectives and rules", exact: true })).toBeEnabled();
    await expect(content).toHaveAttribute("aria-busy", "false");
  } finally {
    releases.forEach((release) => release()); httpHolds.clear(); refuseSettings = false; artifactFailure = null;
    await context.close(); limitPerfHttp = false;
  }
}, 90_000);

test("Usage: the page's window and the measurements' own keep exact 5-hour, 30-day and 1-hour intervals", async () => {
  await open("", async (p) => {
    await fold(p).click();
    await p.locator("#band-perf .pc-deep > summary").click();
    const readFor = (duration: number) => p.waitForRequest((r) => {
      const u = new URL(r.url());
      return u.pathname === "/api/perf/summary" && u.searchParams.get("contract") === "1" && Number(u.searchParams.get("until")) - Number(u.searchParams.get("from")) === duration;
    });
    const fiveHours = readFor(5 * 3600000);
    await p.locator("#win [data-act=win][data-v='5h']").click();
    await fiveHours;
    await expect(p.locator('#band-perf [name="window"]')).toHaveValue("5h");
    const thirtyDays = readFor(30 * 86400000);
    await p.locator("#win [data-act=win][data-v='30d']").click();
    await thirtyDays;
    await expect(p.locator('#band-perf [name="window"]')).toHaveValue("30d");
    const oneHour = readFor(3600000);
    await p.locator('#band-perf [name="window"]').selectOption("1h");
    await p.getByRole("button", { name: "Measure", exact: true }).click();
    await oneHour;
  });
}, 90_000);

test("Usage: a selected project changes the measurements' population and survives request navigation", async () => {
  await open("?project=/work/website", async (p) => {
    await fold(p).click();
    await p.locator("#band-perf .pc-deep > summary").click();
    await expect(p.locator('#band-perf [name="project"]')).toHaveValue("/work/website");
    await p.locator('#band-perf [data-pc-command="plan"] > summary').click();
    await expect(p.locator(".pc-command-failure")).toHaveText("5% failed");
    const outcomes = p.locator(".pc-command-detail .pc-section").filter({ hasText: "Recorded outcomes" });
    await expect(outcomes.getByRole("link", { name: "ok", exact: true })).toBeVisible();
    await expect(outcomes).toContainText("19");
    const link = p.locator(".pc-command-detail").getByRole("link", { name: "Open requests", exact: true });
    const href = new URL((await link.getAttribute("href"))!, "http://fixture");
    expect(href.searchParams.get("project")).toBe("/work/website");
    expect(href.searchParams.get("head")).toBe("plan");
    expect(href.searchParams.get("local")).toBe("0");
    expect(Number(href.searchParams.get("until")) - Number(href.searchParams.get("from"))).toBe(7 * 86400000);
  });
}, 90_000);

test("Usage: a single query worker returns every view for the newest cohort and refresh", async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } });
  const p = await context.newPage(), first = holdQuery("/api/perf/summary");
  let pageTurn: ReturnType<typeof holdQuery> | null = null;
  try {
    await p.goto(`http://127.0.0.1:${server.port}/usage.html?contract=1&model=one-query-worker&from=${now - 60000}&until=${now}`);
    await first.ready;
    const panel = p.locator("#band-perf"), content = panel.locator(".pc-content");
    // Multiple cohort edits while a previous server read still owns the worker must publish only
    // the newest cohort. Aborting its browser response does not release this fixture's worker.
    for (const project of ["/work/old-a", "/work/old-b", "/work/website"]) {
      await panel.locator('[name="project"]').fill(project);
      await panel.getByRole("button", { name: "Measure", exact: true }).click();
    }
    first.release();
    await expect(content).toHaveAttribute("aria-busy", "false");
    await expect(panel.locator('[name="project"]')).toHaveValue("/work/website");
    await expect(panel.locator(".pc-command-failure")).toHaveText("5% failed");
    await panel.locator('[data-pc-tab="history"]').click();
    await panel.locator("details.pc-detail").filter({ hasText: "Exact history readings" }).locator("summary").click();
    await expect(panel.getByRole("table", { name: "History · last 120 returned scope intervals", exact: true })).toContainText("7");
    await expect(panel.locator('[name="historyMetric"]')).toHaveValue("current_gauge");
    await panel.locator('[data-pc-tab="timeline"]').click();
    await expect(content).toHaveAttribute("aria-busy", "false");
    await panel.getByText("Interpretation and next measurements", { exact: true }).click();
    await expect(panel.getByRole("table", { name: "Diagnosis evidence", exact: true })).toContainText("current diagnosis");
    const records = panel.getByRole("table", { name: "Recorded request rows · returned source order", exact: true });
    const requestLink = records.getByRole("link", { name: "Open request 1", exact: true });
    await expect(requestLink).toHaveAttribute("href", /#plan%3Acurrent-first-page$/);
    await expect(panel.locator(".pc-coverage .pc-state")).toHaveText("Ok");

    // A refresh supersedes an in-flight cursor page. Its old page cannot replace the refreshed
    // first page, and the subsequent intentional Next still reaches the actual second page.
    pageTurn = holdQuery("/api/perf/turns", true);
    await panel.getByRole("button", { name: "Next records", exact: true }).click();
    await pageTurn.ready;
    await expect(panel.getByRole("button", { name: "Next records", exact: true })).toBeDisabled();
    await panel.getByRole("button", { name: "Refresh", exact: true }).click();
    pageTurn.release();
    await expect(content).toHaveAttribute("aria-busy", "false");
    await expect(requestLink).toHaveAttribute("href", /#plan%3Acurrent-first-page$/);
    await expect(requestLink).not.toHaveAttribute("href", /current-second-page$/);
    await panel.getByRole("button", { name: "Next records", exact: true }).click();
    await expect(requestLink).toHaveAttribute("href", /#plan%3Acurrent-second-page$/);

    refuseSummary = true;
    await panel.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(content).toHaveAttribute("aria-busy", "false");
    await expect(panel.locator(".pc-coverage .pc-state")).toHaveText("Error");
    await expect(panel).toContainText("query in progress");
  } finally {
    first.release(); pageTurn?.release(); heldQuery = null; refuseSummary = false;
    await context.close();
  }
}, 90_000);

test("Usage: an accepted capacity retry keeps query ownership until its held response drains", async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } });
  const p = await context.newPage(), retry = holdQuery("/api/perf/turns");
  refuseTurnsOnce = true; queryBusyResponses = 0;
  try {
    await p.goto(`http://127.0.0.1:${server.port}/usage.html?contract=1&model=one-query-worker&from=${now - 60000}&until=${now}`);
    const panel = p.locator("#band-perf"), content = panel.locator(".pc-content");
    await expect(content).toHaveAttribute("aria-busy", "false");
    await panel.locator('[data-pc-tab="timeline"]').click();
    await retry.ready;
    await panel.locator('[name="project"]').fill("/work/website");
    await panel.getByRole("button", { name: "Measure", exact: true }).click();
    // The new cohort waits behind the accepted retry's worker ownership, even though the old view is stale.
    expect(queryBusyResponses).toBe(0);
    retry.release();
    await expect(content).toHaveAttribute("aria-busy", "false");
    await expect(panel.locator('[name="project"]')).toHaveValue("/work/website");
    await panel.locator('[data-pc-tab="overview"]').click();
    await expect(panel.locator(".pc-command-failure")).toHaveText("5% failed");
    expect(queryBusyResponses).toBe(0);
  } finally {
    retry.release(); heldQuery = null; refuseTurnsOnce = false;
    await context.close();
  }
}, 90_000);

test("Usage: automatic updates preserve the selected row page and its interval until Refresh", async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1400 } });
  const p = await context.newPage();
  try {
    await p.clock.install({ time: new Date(now) });
    await p.goto(`http://127.0.0.1:${server.port}/usage.html?contract=1&model=one-query-worker&project=/work/website&win=1h`);
    const panel = p.locator("#band-perf"), content = panel.locator(".pc-content");
    await expect(content).toHaveAttribute("aria-busy", "false");
    await panel.locator('[data-pc-tab="timeline"]').click();
    const records = panel.getByRole("table", { name: "Recorded request rows · returned source order", exact: true });
    const link = records.getByRole("link", { name: "Open request 1", exact: true });
    await expect(link).toHaveAttribute("href", /current-first-page$/);
    const first = new URL((await link.getAttribute("href"))!, "http://fixture");
    await panel.getByRole("button", { name: "Next records", exact: true }).click();
    await expect(link).toHaveAttribute("href", /current-second-page$/);
    const pinned = await link.getAttribute("href");
    await p.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); });
    const refreshed = p.waitForResponse((r) => {
      const u = new URL(r.url());
      return u.pathname === "/api/perf/summary" && u.searchParams.get("contract") === "1"
        && Number(u.searchParams.get("until")) > Number(first.searchParams.get("until"));
    });
    await p.clock.fastForward(16000);
    await refreshed;
    await expect(content).toHaveAttribute("aria-busy", "false");
    await expect(link).toHaveAttribute("href", pinned!);
    await expect(panel).toContainText("Row snapshot:");
    await panel.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(link).toHaveAttribute("href", /current-first-page$/);
    const fresh = new URL((await link.getAttribute("href"))!, "http://fixture");
    expect(Number(fresh.searchParams.get("until"))).toBeGreaterThan(Number(first.searchParams.get("until")));
    expect(fresh.searchParams.get("project")).toBe("/work/website");
  } finally { await context.close(); }
}, 90_000);
