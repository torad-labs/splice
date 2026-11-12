// Two things Sessions offered before round 1 and lost in it (the integrity check, Oct 27). Pinning a session to a
// login that is at its limit is the move the daemon still takes and applies once that login can serve again, so the
// session goes there when the window refills instead of staying where it is. Under No project, the reason a folder
// has no project is what he acts on, so each reason names its folders when there are several. Through the real
// Sessions page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
type Row = Record<string, unknown>;
const row = (id: string, name: string, over: Row = {}): Row => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle", cwd: `/work/${name}`, title: null,
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts: now - 60_000 },
  updated_at: new Date(now - 60_000).toISOString(), ...over,
});
const project = (p: string) => ({ project: { id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" } });
const reset = Math.floor(now / 1000) + 3_600;
const login = (label: string, name: string, used: number) => ({
  label, display_name: name, heads: ["alpha"], credential_present: true, plan: "max",
  five_hour_used_percent: used, five_hour_reset_epoch_seconds: reset, five_hour_window_seconds: 18_000,
});

let pin: string | null = null;
let sessions: Row[] = [];
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ reading: true, sessions: sessions.map((s) => (s.session_id === "s-builder" ? { ...s, account_pin: pin } : s)) });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path === "/api/accounts") return Response.json({ accounts: [login("main", "Main", 20), login("spare", "Spare", 100)] });
    if (path === "/api/auth/alpha/order") return Response.json({ next_target: "main" });
    if (path === "/api/auth/alpha/switch" && req.method === "POST") { pin = String((await req.json()).label); return Response.json({ ok: true }); }
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

test("a login at its limit can still take the session, which moves there when the window refills", async () => {
  sessions = [row("s-builder", "builder", { account: "main", ...project("temple") }), row("s-other", "other", project("website"))];
  pin = null;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html#s-builder`);
    await page.locator('section.pane [data-act="logins"]').click({ timeout: 15_000 });
    const spare = page.locator(".lrow", { hasText: "Spare" });
    await expect(spare).toContainText("At limit");
    const use = spare.getByRole("button", { name: /Use/ });
    await expect(use).toBeVisible();
    await use.click();
    await expect(spare).toContainText("Pinned", { timeout: 10_000 });
    await expect(spare).toContainText("At limit"); // the limit is still said: the move waits for the refill
  } finally {
    await page.close();
  }
}, 40_000);

test("under No project each reason names the folders it holds", async () => {
  sessions = [
    row("s-a", "api", project("website")),
    row("s-n1", "scratch", { place: "~/scratch", repo: { root: "/home/me/scratch", reason: "the cwd is not inside a git repository" } }),
    row("s-n2", "vendored", { place: "/opt/vendor/lib", cwd: "/opt/vendor/lib", repo: { root: "/opt/vendor/lib", reason: "the cwd is outside the trusted roots ($HOME, /tmp, statuslineGitRoots), so it was not probed" } }),
  ];
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    const loose = page.locator(".pgroup", { hasText: "No project" }).first();
    await expect(loose).toBeVisible({ timeout: 15_000 });
    await expect(loose.locator("p", { hasText: "Not in a git repository" })).toContainText("scratch");
    await expect(loose.locator("p", { hasText: "Not in a git repository" })).not.toContainText("lib");
    const other = loose.locator("p", { hasText: /trusted|splice reads/i });
    await expect(other).toContainText("lib");
    await expect(other).not.toContainText("statuslineGitRoots"); // the setting's key is not his words
  } finally {
    await page.close();
  }
}, 40_000);
