// A session that never went through splice says "direct" where its command goes, the spec's word for it, and one splice
// cannot place still says "unknown head". Through the real Sessions page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const row = (id: string, name: string, head: string, route: string) => ({
  session_id: id, name, head, route, availability: "live", status: "idle", cwd: "/work/desk",
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts: 1 },
});
const answers: Record<string, unknown> = {
  "/api/sessions": {
    sessions: [
      row("s-direct", "bridge", "unknown head", "direct"),
      row("s-unknown", "stray", "unknown head", "unknown"),
      row("s-head", "builder", "alpha", "head"),
    ],
  },
  "/api/heads": { heads: [{ key: "alpha", label: "claude-alpha" }] },
  "/api/models": { heads: [] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "sessions.html" : path.slice(1));
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

test("a direct session says direct, an unplaced one says unknown head, a placed one names its command", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  const card = (name: string) => page.locator(".card.s", { has: page.locator(".name", { hasText: name }) }).locator(".top");
  await expect(card("bridge")).toBeVisible({ timeout: 15_000 });
  await expect(card("bridge")).toContainText("direct");
  await expect(card("bridge")).not.toContainText("unknown head");
  await expect(card("stray")).toContainText("unknown head");
  await expect(card("builder")).toContainText("claude-alpha");
  await page.close();
});
