// Settings' Projects pane: the folders under which each child folder is a project of its own. One row, drawn by the
// page rather than the kit, so it is checked through the real page: what each saved path shows, what a vanished one
// says, and that adding and dropping a path write the whole colon-separated list the daemon reads.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
let folders = ""; // the knob's value, as the daemon would answer it
let projects: Record<string, unknown> = { projects: [], folders: [] };
const patched: Record<string, unknown>[] = [];

const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "alpha", label: "alpha" }] },
  "/api/models": { heads: [] },
  "/api/perf/turns": { heads: [] },
  "/api/history": {},
  "/api/upgrade": { installed: "0.4.0", latest: null, rollback_target: null },
};
const server = Bun.serve({
  port: Number(process.env.SETTINGS_PROJECTS_PORT ?? 0),
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/config") {
      if (req.method === "PATCH") {
        const body = (await req.json()) as Record<string, string>;
        patched.push(body);
        folders = body.projectsFolders ?? folders;
        return Response.json({ applied: { projectsFolders: folders }, rejected: {}, restart_required: [] });
      }
      return Response.json({ layers: { runtime: { projectsFolders: folders } }, restart_required_keys: [] });
    }
    if (path === "/api/projects") return Response.json(projects);
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "settings.html" : path.slice(1));
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

/** The Projects pane, open, with the daemon holding [saved] and answering [body] for /api/projects. */
async function pane(saved: string, body: Record<string, unknown>): Promise<Page> {
  folders = saved;
  projects = body;
  patched.length = 0;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Projects", { exact: true }).first().click();
  await expect(page.locator(".pane")).toContainText("Projects folder", { timeout: 15_000 });
  return page;
}

test("with no folder saved the row offers Add and the rail says the rule in force", async () => {
  const page = await pane("", { projects: [], folders: [] });
  try {
    await expect(page.locator(".card.job", { hasText: "Projects" }).first()).toContainText("One per repo");
    await expect(page.locator(".pane").getByRole("button", { name: "Add" })).toBeVisible();
    await expect(page.locator(".pane .ppath")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("each saved path stands as typed, with the projects it makes under it", async () => {
  const page = await pane("~/code", {
    folders: [{ path: "~/code", found: true }],
    projects: [
      { id: "/home/u/code/billing-api", name: "billing-api", label: "billing-api", source: "folder", folder: "~/code" },
      { id: "/home/u/code/website", name: "website", label: "website", source: "folder", folder: "~/code" },
      { id: "/home/u/other", name: "other", label: "other", source: "repo", folder: null },
    ],
  });
  try {
    await expect(page.locator(".pane .ppath")).toHaveCount(1);
    await expect(page.locator(".pane .ppath")).toContainText("~/code");
    const makes = page.locator(".pane .pmakes");
    await expect(makes).toContainText("billing-api");
    await expect(makes).toContainText("website");
    await expect(makes).not.toContainText("other"); // a repo-mode project belongs to no saved folder
  } finally {
    await page.close();
  }
}, 30_000);

// A folder that has gone never stops splice: its row says so and its sessions fall back to the git-repo rule.
test("a saved folder that is no longer on disk reads Not found and keeps its place", async () => {
  const page = await pane("~/code:~/work", {
    folders: [{ path: "~/code", found: true }, { path: "~/work", found: false }],
    projects: [],
  });
  try {
    await expect(page.locator(".pane .ppath")).toHaveCount(2);
    await expect(page.locator(".pane .prow", { hasText: "~/work" })).toContainText("Not found");
    await expect(page.locator(".pane .prow", { hasText: "~/code" })).not.toContainText("Not found");
  } finally {
    await page.close();
  }
}, 30_000);

test("adding a folder writes the whole list and says Applied", async () => {
  const page = await pane("~/code", { folders: [{ path: "~/code", found: true }], projects: [] });
  try {
    await page.locator(".pane").getByRole("button", { name: "Add" }).click();
    await page.locator(".pane [data-pfield]").fill("/srv/work");
    await page.locator(".pane").getByRole("button", { name: "Save" }).click();
    await expect(page.locator(".pane")).toContainText("Applied", { timeout: 10_000 });
    expect(patched).toEqual([{ projectsFolders: "~/code:/srv/work" }]);
    await expect(page.locator(".pane .ppath")).toHaveCount(2);
  } finally {
    await page.close();
  }
}, 30_000);

test("dropping a path saves the rest and leaves the others alone", async () => {
  const page = await pane("~/code:~/work", {
    folders: [{ path: "~/code", found: true }, { path: "~/work", found: true }],
    projects: [],
  });
  try {
    await page.locator(".pane .prow", { hasText: "~/work" }).getByRole("button", { name: /Remove/ }).click();
    await expect(page.locator(".pane .ppath")).toHaveCount(1, { timeout: 10_000 });
    expect(patched).toEqual([{ projectsFolders: "~/code" }]);
    await expect(page.locator(".pane .ppath")).toContainText("~/code");
  } finally {
    await page.close();
  }
}, 30_000);
