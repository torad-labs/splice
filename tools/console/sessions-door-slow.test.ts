// A link to one session (sessions.html#<id>, the door Teams and Requests use) keeps naming that session in the address
// while the page reads splice for the first time, however slow that read is, so a reload in that moment opens the same
// session. The console check of Oct 26 found the first draw clears the address before the list arrives, and a reload
// during a slow first read then opened the top session instead (round 2's carry list). Through the real Sessions page
// against a stub daemon whose list answers 2.5 s late, at Marcos's own width.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const row = (id: string, name: string, ts: number) => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle", cwd: "/work/desk",
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts },
});
const transcript = { messages: [], path: "", earlier: null, moves: [], unmoved: [], unparseable_lines: 0 };
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") {
      await Bun.sleep(2_500);
      return Response.json({ sessions: [row("s-top", "topmost", 3_000), row("s-builder", "builder", 2_000)] });
    }
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (/^\/api\/sessions\/[^/]+\/transcript$/.test(path)) return Response.json(transcript);
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

test("a session's link keeps naming it while the first read is slow, and a reload in that moment opens the same session", async () => {
  const page = await browser.newPage({ viewport: { width: 2548, height: 1240 }, deviceScaleFactor: 1.5 });
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html#s-builder`, { waitUntil: "domcontentloaded" });
  await page.waitForTimeout(800); // the page has drawn, and its first read is still on the wire
  bunExpect(new URL(page.url()).hash).toBe("#s-builder");
  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.locator("section.pane header .who .name")).toHaveText("builder", { timeout: 15_000 });
  bunExpect(new URL(page.url()).hash).toBe("#s-builder");
  bunExpect(errors).toEqual([]);
  await page.close();
}, 45_000);
