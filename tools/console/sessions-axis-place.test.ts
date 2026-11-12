// The axis that names the ticks of a block's row bars (Sessions' open-plate walk, Oct 29): at one column, a block whose
// first session is the open one draws that session as its plate, so the axis stands under the plate, directly over the
// rows whose bars it names, and never alone above a plate that has no bars of its own. Through the real Sessions page
// against a stub daemon.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const mk = (id: string, name: string, status: string, ago: number, cwd = "/work/axis") => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status, cwd, version: "2.1.0",
  last: { role: "assistant", tool: status === "working" ? "Bash" : undefined, text: "ok", ts: now - ago, asks: [] },
  updated_at: new Date(now - ago).toISOString(),
  project: { id: cwd, name: cwd.slice(6), label: cwd.slice(6), source: "folder", folder: "/work" },
});
const sessions = [mk("a-1", "first-open", "working", 5_000), mk("a-2", "second-idle", "idle", 3_600_000), mk("b-1", "elsewhere", "working", 9_000, "/work/other")];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ reading: true, sessions });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (/\/screen$/.test(path)) return new Response("{}", { status: 404 });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("at one column the block's axis stands under the open session's plate, over the rows whose bars it names", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1400 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html#a-1`);
    await expect(page.locator("section.pane .sx-bar").first()).toBeVisible({ timeout: 15_000 });
    const own = ".pgroup:has(section.pane)";
    await expect(page.locator(`${own} .sx-axisrow`)).toHaveCount(1);
    const box = async (sel: string) => (await page.locator(sel).first().boundingBox())!;
    const plate = await box("section.pane"), axis = await box(`${own} .sx-axisrow`), row = await box(`${own} .s.card, ${own} .s:not(.pane)`);
    bunExpect(axis.y).toBeGreaterThanOrEqual(plate.y + plate.height - 1);
    bunExpect(axis.y + axis.height).toBeLessThanOrEqual(row.y + 1);
  } finally {
    await page.close();
  }
}, 60_000);
