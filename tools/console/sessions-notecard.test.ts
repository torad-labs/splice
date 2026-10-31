// A session waiting on a permission, as the board draws it (Sessions' board finish pass, Oct 29: the approved board's
// permission card holds the command it waits on, says where it is answered, and holds a note field beside it): the card on
// the row names the command, and its Note field sends what he types to that session, so a session splice cannot press an
// answer for can still be told something without opening it. Through the real Sessions page against a stub daemon.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const waiting = {
  session_id: "s-wait", name: "fence-lead", head: "alpha", route: "head", availability: "live", status: "idle", cwd: "/work/fence",
  version: "2.1.0", address: "peer-fence", waiting_for: "permission",
  last: { role: "assistant", tool: "Bash", text: JSON.stringify({ command: "bun test routing" }), ts: now - 60_000, asks: [] },
  updated_at: new Date(now - 60_000).toISOString(),
};
const posted: unknown[] = [];
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ reading: true, note_versions: ["2.1.0"], sessions: [waiting] });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (/\/screen$/.test(path)) return new Response("{}", { status: 404 }); // splice has no screen for it: it is answered in its terminal
    if (path === "/api/sessions/s-wait/message" && req.method === "POST") { posted.push(await req.json()); return Response.json({ ok: true }); }
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

test("the permission card on the board names the command and its Note field sends the note", async () => {
  const page = await browser.newPage({ viewport: { width: 2548, height: 1240 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    const card = page.locator(".s .ask").first();
    await expect(card).toContainText("bun test routing", { timeout: 15_000 });
    const field = card.getByLabel("Note to this session");
    await expect(field).toBeVisible();
    await field.fill("hold the routing tests");
    await card.getByRole("button", { name: "Send note" }).click();
    await expect.poll(() => posted.length, { timeout: 10_000 }).toBe(1);
    bunExpect(posted[0]).toEqual({ text: "hold the routing tests" });
  } finally {
    await page.close();
  }
}, 60_000);
