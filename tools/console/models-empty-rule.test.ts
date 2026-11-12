// A compaction rule in force that reads its instructions from a file says when that file is empty: splice reads it as
// zero characters (GET /api/projects' compaction rows carry each rule's live length, 0 for an empty file), and a rung
// that showed only the file's name read as a rule with text in it. Through the real Models page's Projects tab against
// a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "alpha", label: "claude-alpha", running: true, healthy: true }] },
  "/api/topology": { topology: {
    heads: { alpha: { provider: "alpha-co" } },
    providers: { "alpha-co": { base_url: "https://api.alpha.example" } },
    compaction: { project: [{ path: "/work/desk", file: "~/notes/compact.md" }] },
  } },
  "/api/models": { heads: [{ head: "alpha", pinned_model: null, models: [{ id: "alpha-one", label: "Alpha One", resolved: true, slot: "opus" }] }] },
  // the project's rule is a file splice read, and the file holds nothing
  "/api/projects": { projects: [{ id: "desk", name: "desk", label: "desk", root: "/work/desk", source: "seen",
    compaction: [{ scope: "project", source: "project:/work/desk file:/home/u/notes/compact.md", chars: 0 }] }], folders: [] },
  "/api/projects/desk/files": { files: [] },
  "/api/doctor": { checks: [] },
  "/api/sessions": { sessions: [] },
  "/api/models/local": { providers: [] },
  "/api/claude-head": { mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" },
  "/api/add/profiles": { profiles: [] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

test("a compaction rule whose file is empty says Empty on its rung, beside the file's name", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/models.html?view=projects`);
  const rung = page.locator(".rung", { hasText: "compact.md" });
  await expect(rung).toBeVisible({ timeout: 15_000 });
  await expect(rung).toContainText(/\bEmpty\b|\b0 characters\b/);
  await page.close();
}, 30_000);
