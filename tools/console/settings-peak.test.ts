// Settings' "Most at once": a busy command's day holds more requests than one read returns, so the daemon works out its
// peak over the whole day and the page draws that figure. Through the real page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const day = Date.now() - 3_600_000;
// the daemon's answer to a peak question: the whole day counted, the rows only the newest one
const turns = (key: string) => ({ heads: [{ key, label: key, count: 3_400, returned: 1, truncated: true, rows: [], at_once: { peak: key === "alpha" ? 7 : 3, at: day, counted: 3_400, queued: key === "alpha" ? 2 : 0 } }] });
const asked: string[] = [];
const server = Bun.serve({
  port: Number(process.env.SETTINGS_PEAK_PORT ?? 0),
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha" }, { key: "beta", label: "beta" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/config") return Response.json({ layers: {} });
    if (path === "/api/perf/turns") {
      if (url.searchParams.get("peak") === "1") asked.push(url.searchParams.get("head") ?? "");
      return Response.json(turns(url.searchParams.get("head") ?? ""));
    }
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
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("a command whose day holds more requests than one read returns still shows its peak, and nothing says not read", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
    await page.getByText("Many agents at once").first().click();
    const peaks = page.locator(".card.peaks");
    await expect(peaks).toBeVisible({ timeout: 15_000 });
    await expect(peaks.locator(".peak").filter({ hasText: "alpha" })).toContainText("7 of");
    await expect(peaks.locator(".peak").filter({ hasText: "alpha" })).toContainText("requests at once");
    await expect(peaks.locator(".peak").filter({ hasText: "beta" })).toContainText("3 of");
    await expect(peaks).not.toContainText("not read");
    await expect(peaks).not.toContainText("over 2,000");
    expect(asked.sort()).toEqual(["alpha", "beta"]);
  } finally {
    await page.close();
  }
}, 30_000);
