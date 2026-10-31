// New command, when the templates it starts from were not read: the pane says so with a way to read them again beside it, and
// the templates arrive in the same pane when the second read answers. Through the real page in Chromium against a stub daemon,
// judged by what he sees (the first read of the templates fails, the second answers with one).
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
let profileReads = 0;
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha", running: true, healthy: true, dialect: "openai-chat" }] });
    if (path === "/api/topology") return Response.json({ topology: { heads: { alpha: { provider: "alpha-co" } }, providers: { "alpha-co": { base_url: "https://api.alpha.example" } } } });
    if (path === "/api/models") return Response.json({ heads: [{ head: "alpha", pinned_model: null, models: [{ id: "alpha-one", label: "Alpha One", resolved: true, slot: "opus" }] }] });
    if (path === "/api/doctor") return Response.json({ checks: [] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/projects") return Response.json({ projects: [], folders: [] });
    if (path === "/api/models/local") return Response.json({ providers: [] });
    if (path === "/api/claude-head") return Response.json({ mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" });
    if (path === "/api/add/profiles") {
      profileReads++;
      // the first read fails, the ones after it answer
      if (profileReads === 1) return new Response("down", { status: 503 });
      return Response.json({ profiles: [{ name: "grok", label: "Grok", command: "claude-grok", head_key: "grok", auth_kind: "api-key", requires_key: true, asks: ["key"], base_url: "https://api.grok.example" }] });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

test("templates that were not read say so with Read again beside them, and the second read fills the same pane", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/models.html`);
  await page.locator('[data-act="new"]').click({ timeout: 15_000 });
  const pane = page.locator(".add-pane");
  await expect(pane.getByText("No templates read")).toBeVisible({ timeout: 15_000 });
  await pane.getByRole("button", { name: "Read again" }).click();
  await expect(pane.locator('[data-act="tpl"]')).toHaveCount(1, { timeout: 15_000 });
  await expect(pane.getByText("No templates read")).toHaveCount(0);
  await page.close();
}, 60_000);
