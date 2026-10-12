// A command whose daily budget is spent refuses a Compare send as it refuses a turn: the pane reads "Over budget", the
// same word Requests gives that outcome, with a door that opens THAT command's budget on Accounts. The daemon's own
// sentence stays out of the page. Through the real pages against a stub daemon that answers 429 as the playground route does.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const sentence = "head 'codex' has spent $2.00 today against its $2.00 daily budget";
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "codex", label: "codex", command: "codex" }] });
    if (path === "/api/models") return Response.json({ heads: [{ head: "codex", provider: "codex", models: [{ id: "gpt-6.1-sol", label: "GPT 6.1 Sol" }] }] });
    if (path === "/api/auth") return Response.json({ codex: { present: true, display_name: "primary" } });
    if (path === "/api/playground" && req.method === "POST") {
      return Response.json({ error: sentence, outcome: "error:budget-blocked", blocked: "budget", detail: "spent_usd=2.00", head: "codex" }, { status: 429 });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "compare.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
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

test("an over-budget command's Compare pane reads Over budget, never the daemon's sentence, and its door names that command", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const warned: string[] = [];
  page.on("console", (m) => { if (m.type() === "warning") warned.push(m.text()); });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/compare.html`);
    await page.locator("[data-act=add]").click();
    await page.locator("button.pg-pill").first().click();
    await page.locator("#prompt").fill("say hi");
    await page.locator("button.pg-send").click();
    await expect(page.locator(".state.limit")).toHaveText("Over budget", { timeout: 20_000 });
    await expect(page.locator("body")).not.toContainText("daily budget");
    await expect(page.locator("button[data-act=retry]")).toHaveCount(0);
    const door = page.getByRole("link", { name: "Set budget" });
    await expect(door).toHaveAttribute("href", "accounts.html?budget=codex");
    expect(warned.some((w) => w.includes(sentence))).toBe(true);
  } finally {
    await page.close();
  }
}, 60_000);
