// ccd-session console test: verifies suggested tasks (Start / Do it here / Dismiss) and chapters
// render in the open session inspector rail on Night Deck sessions.html.
import { afterAll, beforeAll, expect as bunExpect, setDefaultTimeout, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";

setDefaultTimeout(60_000);

const ROOT = join(import.meta.dir, "../..");
const CONSOLE_DIR = join(ROOT, "app/src/main/resources/console");

const calls: { method: string; path: string; body: unknown }[] = [];
let tasks = [
  {
    task_id: "task_deadbeef",
    session_id: "sess-ccd-1",
    title: "Clean up stale helper",
    tldr: "Noticed unused helper while fixing the parser; follow-up removes it cleanly.",
    prompt: "Remove unused helper in src/foo.ts and update tests.",
    cwd: "/tmp/project",
    state: "pending",
    created_at: new Date().toISOString(),
    started_session_id: null,
  },
];
const chapters = [
  {
    session_id: "sess-ccd-1",
    title: "Parser investigation",
    summary: "Traced tokenization edge case",
    created_at: new Date().toISOString(),
  },
];

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (req.method === "POST" && path.startsWith("/api/suggested-tasks/")) {
      const body = await req.json().catch(() => ({}));
      calls.push({ method: req.method, path, body });
      if (path.endsWith("/claim")) {
        tasks = tasks.map((t) => ({ ...t, state: "in_flight" }));
        return Response.json({ task: tasks[0] });
      }
      if (path.endsWith("/resolve")) {
        const outcome = (body as { outcome?: string }).outcome ?? "dismissed";
        tasks = tasks.map((t) => ({ ...t, state: outcome === "released" ? "pending" : outcome }));
        return Response.json({ task: tasks[0] });
      }
    }
    if (req.method === "POST" && path === "/api/sessions/sess-ccd-1/say") {
      const body = await req.json().catch(() => ({}));
      calls.push({ method: req.method, path, body });
      return Response.json({ ok: true });
    }
    if (path === "/api/suggested-tasks") return Response.json({ tasks });
    if (path === "/api/chapters") return Response.json({ chapters });
    if (path === "/api/sessions") {
      return Response.json({
        sessions: [
          {
            session_id: "sess-ccd-1",
            name: "parser-work",
            head: "codex",
            model: "gpt-5.3-codex",
            cwd: "/tmp/project",
            availability: "live",
            status: "working",
            turns: 4,
            started_at: Date.now() - 60_000,
            updated_at: Date.now(),
          },
        ],
      });
    }
    if (path === "/api/heads") return Response.json({ heads: [{ key: "codex", label: "claudex", running: true, healthy: true }] });
    if (path === "/api/teams") return Response.json({ teams: [] });
    if (path === "/api/accounts") return Response.json({ accounts: [] });
    if (path === "/api/models") return Response.json({ heads: [] });
    if (path.startsWith("/api/sessions/sess-ccd-1/transcript")) return Response.json({ messages: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(CONSOLE_DIR, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(CONSOLE_DIR)
      ? new Response(Bun.file(file))
      : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 30_000);
afterAll(async () => {
  await browser?.close();
  server.stop(true);
});

test("Sessions open rail shows suggested task chip and chapters, and Do it here claims, says, and resolves", async () => {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => localStorage.setItem("splice-console-access-key", "test-key"));
  const page = await ctx.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`, { waitUntil: "networkidle" });
    const row = page.locator('[data-sid="sess-ccd-1"]');
    await row.waitFor({ state: "visible", timeout: 15_000 });
    await row.click();
    await expect(page.locator(".rl-task-title")).toContainText("Clean up stale helper", { timeout: 10_000 });
    await expect(page.locator(".rl-chap-title")).toContainText("Parser investigation", { timeout: 10_000 });
    await page.locator(".rl-task").getByRole("button", { name: "Do it here" }).click();
    await expect(page.locator(".rl-task")).toHaveCount(0, { timeout: 10_000 });
    bunExpect(calls.map((c) => c.path)).toEqual([
      "/api/suggested-tasks/task_deadbeef/claim",
      "/api/sessions/sess-ccd-1/say",
      "/api/suggested-tasks/task_deadbeef/resolve",
    ]);
  } finally {
    await ctx.close();
  }
});
