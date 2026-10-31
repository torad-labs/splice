// A session that never went through splice has a mark where its command goes, named "Not through splice", and one splice
// cannot place a mark named "Command unknown"; the head line's legend says each mark's words once for the page, and the
// rows do not repeat them. The wire's own words, `direct` and `unknown head`, are never on the screen (Sessions' goal,
// Oct 26: "No code word is on the screen: no cwd, no unknown head, no direct"). Through the real Sessions page against a
// stub daemon.
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

test("a session that never went through splice wears a mark that says so, an unplaced one a mark that says Command unknown, a placed one names its command", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  const card = (name: string) => page.locator(".card.s", { has: page.locator(".name", { hasText: name }) }).locator(".top");
  await expect(card("bridge")).toBeVisible({ timeout: 15_000 });
  await expect(card("bridge").locator(".nocmd")).toHaveAttribute("aria-label", "Not through splice");
  await expect(card("bridge").locator(".nocmd")).toHaveCount(1);
  await expect(card("stray").locator(".nocmd")).toHaveAttribute("aria-label", "Command unknown");
  await expect(card("builder").locator(".nocmd")).toHaveCount(0);
  await expect(card("builder")).toContainText("claude-alpha");
  // the words are said once, in the legend on the head line, and not again on the rows that wear the marks
  await expect(page.locator(".quietleg")).toHaveText(["Not through splice", "Command unknown"]);
  await expect(card("bridge")).not.toContainText("Not through splice");
  await expect(card("stray")).not.toContainText("Command unknown");
  // neither wire word reaches the screen
  await expect(page.locator("main")).not.toContainText("unknown head");
  await expect(page.locator("main")).not.toContainText("direct");
  await page.close();
}, 30_000);
