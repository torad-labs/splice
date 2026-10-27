// The manager sheet's Context row reads its share against the room it is a share of: "44% of 272K", the window the
// manager's own status line reports, for any model, including one splice's model catalog does not list (the
// shell's goal, round 2: "the share is read against a visible room for every model"). Through the real page
// against a stub daemon whose catalog lists nothing, so the window can only come from the status line.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const manager = {
  folder: "/home/someone/.config/splice/manager", consented: true, state: "live", head: "claudex",
  session_id: "m-1", status: "idle",
  line: { model: "GPT-5.6 Codex", model_id: "gpt-5.6-codex", effort: "high", at: Date.now(),
    context: { used: 121_000, window: 272_000, percent: 44, cache: 92 } },
  heads: [{ key: "claudex", label: "claudex", ready: true }],
};
const answers: Record<string, unknown> = {
  "/api/manager": manager,
  "/api/models": { heads: [{ head: "claudex", models: [] }] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
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

test("the Context row shows the share and the window it is counted against, for a model the catalog does not list", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
  const field = page.locator(".cmp-field");
  await expect(field).toBeVisible({ timeout: 15_000 });
  await field.click();
  const context = page.locator(".cmp-meters .cmp-mrow", { hasText: "Context" });
  await expect(context).toBeVisible({ timeout: 10_000 });
  // the share, then the room it is a share of, as the console writes token counts everywhere else
  await expect(context).toContainText(/44%[^0-9]*272K/);
  await page.close();
}, 40_000);
