// restart-at-once, console-upgrade-steps: an upgrade started from the console no longer waits for running turns, so
// its steps say what it does now: Checked, Restarting, Serving. "Waiting for compactions" stands between Checked and
// Restarting only on a run whose output says it waits first for compactions the restart would cut. No run reads
// "Waiting for running turns". Through the real Settings page against a stub daemon, fed the lines `splice upgrade`
// prints once restart-at-once is built (ANSI already stripped by the route, as UpgradeRuns.kt does).

import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./lib/harness.ts";

const consoleDir = join(ROOT, "app/src/main/resources/console");
const UPGRADE = {
  installed: "0.4.0",
  latest: null,
  latest_unavailable_reason: "not checked",
  rollback_target: null,
  rollback_unavailable_reason: null,
  checked_at_epoch_millis: null,
};
const CHECKED = [
  "splice upgrade: from github.com/torad-labs/splice releases",
  "  ✓ splice.jar      sha256 ok",
  "  ✓ splice-launch   sha256 ok",
  "  ✓ staged          0.4.1 -> ~/.local/share/splice/releases/0.4.1",
  "  ✓ activated       0.4.1 (0.4.0 kept for --rollback)",
];
const ROLL =
  "splice: starting the next daemon beside the running one; the change applies as soon as it answers. An answer or " +
  "compaction already arriving finishes on the running one, except on a Muse command or a local runtime that pins " +
  "conversations to slots. A turn not yet answering ends with the restart line and Claude Code sends it again; new " +
  "requests go to the next daemon…";
const KEPT = [
  "splice: waiting first for the compactions running on beta, which this restart would cut: a Muse command and a " +
    "local runtime that pins conversations to slots stop whole at a restart, while every other command hands over at " +
    "once. --now restarts without waiting.",
  "splice: waiting for 1 compaction (beta, 12s in)",
];
const SERVING = "  ✓ daemon          restarted, serving 0.4.1";

let run: { state: string; output: string[] } = { state: "running", output: CHECKED };
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/upgrade/run") return Response.json({ run: { id: "r1", args: ["upgrade", "--to", "0.4.1"], ...run } });
    if (path === "/api/upgrade") return Response.json(UPGRADE);
    if (path === "/api/daemon/restart") return Response.json({ status: "idle", drain_ms: 45_000, hands_over: true });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha", hands_over: true }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/config") return Response.json({ layers: {} });
    if (path === "/api/perf/turns") return Response.json({ heads: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "settings.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 60_000);
afterAll(async () => {
  await browser?.close();
  server.stop(true);
});

async function withRun(state: string, output: string[]): Promise<Page> {
  run = { state, output };
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Version").first().click();
  await expect(page.locator(".run h3")).toBeVisible({ timeout: 15_000 });
  return page;
}
const steps = (page: Page) => page.locator(".run .step");

test("an upgrade handing over reads Checked, Restarting, Serving, with Restarting the step under way", async () => {
  const page = await withRun("running", [...CHECKED, ROLL]);
  try {
    await expect(steps(page)).toHaveText(["Checked", "Restarting", "Serving"]);
    await expect(steps(page).locator(".lamp.done")).toHaveCount(1);
    await expect(steps(page).nth(1)).not.toHaveClass(/pending/);
    await expect(steps(page).nth(2)).toHaveClass(/pending/);
    await expect(page.locator(".run")).not.toContainText("Waiting for running turns");
  } finally {
    await page.close();
  }
}, 60_000);

test("a finished upgrade has every step done", async () => {
  const page = await withRun("running", [...CHECKED, ROLL]);
  try {
    run = { state: "succeeded", output: [...CHECKED, ROLL, SERVING] };
    await expect(page.locator(".run h3")).toHaveText("Now on 0.4.1", { timeout: 10_000 });
    await expect(steps(page)).toHaveText(["Checked", "Restarting", "Serving"]);
    await expect(steps(page).locator(".lamp.done")).toHaveCount(3);
  } finally {
    await page.close();
  }
}, 60_000);

test("a run that waits first for compactions it would cut shows that step, under way", async () => {
  const page = await withRun("running", [...CHECKED, ...KEPT]);
  try {
    await expect(steps(page)).toHaveText(["Checked", "Waiting for compactions", "Restarting", "Serving"]);
    await expect(steps(page).locator(".lamp.done")).toHaveCount(1);
    await expect(steps(page).nth(1)).not.toHaveClass(/pending/);
    await expect(steps(page).nth(2)).toHaveClass(/pending/);
  } finally {
    await page.close();
  }
}, 60_000);
