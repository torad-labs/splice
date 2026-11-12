// A login nobody named reads "Account" wherever the Requests page names it, by the console's one naming rule, and two such
// logins that would read alike are told apart by an ordinal counted across the whole Account menu, never per command; a
// login a person named is never renumbered (Requests filters, approved prototype, finish pass 2026-10-29). Through the
// real page against a stub daemon with two commands, each holding an unnamed login, and one named login.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const turn = (id: string, agoS: number, account: string) => ({ turn: id, turn_id: id, ts: now - agoS * 1000, outcome: "ok", model: "m", session: "0c3b07d2", total: 2_000, attempts: 1, retries: 0, account });
const heads: Record<string, { rows: ReturnType<typeof turn>[]; accounts: { key: string; label: string }[] }> = {
  alpha: { rows: [turn("a1", 30, "a-one"), turn("a2", 60, "a-two")], accounts: [{ key: "a-one", label: "Account" }, { key: "a-two", label: "Work" }] },
  beta: { rows: [turn("b1", 45, "b-one")], accounts: [{ key: "b-one", label: "Account 2" }] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }, { key: "beta", label: "claude-beta" }] });
    if (path === "/api/perf/turns") {
      const one = url.searchParams.get("n") === "1";
      return Response.json({ heads: Object.entries(heads).map(([key, h]) => ({ key, label: key, count: h.rows.length, returned: one ? 0 : h.rows.length, truncated: false, rows: one ? [] : h.rows,
        usage: { models: [], accounts: h.accounts, sessions: [] } })) });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("unnamed logins read Account, numbered across the whole Account menu, and a named one keeps its name", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    await page.locator('article[data-open="alpha:a1"]').first().waitFor({ timeout: 15_000 });
    await page.locator('.fbtn[data-m="account"]').click();
    const names = await page.locator(".macct .ml-name").evaluateAll((els) => els.map((e) => (e.textContent ?? "").replace(/\s+/g, " ").trim()));
    expect(names.sort()).toEqual(["Account 1", "Account 2", "Work"]);
  } finally {
    await page.close();
  }
}, 30_000);
