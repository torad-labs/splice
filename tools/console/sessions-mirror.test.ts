// LAN share, milestone 6: a session that runs on another machine is listed here under its name, with a badge naming
// that machine where a command would go, never "direct" or "unknown head". Nothing here can act on it: its pane offers
// the SendMessage line another session uses to reach it, and no Stop, End, Continue on, Message, Note or Resume.
// Through the real Sessions page against a stub daemon that answers every other read with 200, as a worst case.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const mirror = {
  // as SessionsRoutes writes a mirror's row: its machine named, no folder here, its record's version and socket
  session_id: "s-mirror", name: "rig-lead@torad-server", machine: "torad-server", head: "unknown head", route: "direct",
  availability: "live", status: "busy", cwd: null, version: "splice-mirror/0.4.0", address: "uds:/tmp/cc-socks/91.sock",
  started_at: 1, updated_at: 2,
};
const local = {
  session_id: "s-local", name: "builder", head: "alpha", route: "head", availability: "live", status: "idle",
  cwd: "/work/desk", version: "2.1.309", address: "uds:/tmp/cc-socks/92.sock",
};
const inProject = {
  session_id: "s-proj", name: "lan-share", head: "alpha", route: "head", availability: "live", status: "idle",
  cwd: "/work/splice", project: { id: "p-splice", name: "splice" }, version: "2.1.309", address: "uds:/tmp/cc-socks/93.sock",
};
const answers: Record<string, unknown> = {
  "/api/sessions": { sessions: [mirror, local, inProject], note_versions: ["2.1.309", "splice-mirror/0.4.0"], reading: true },
  "/api/heads": { heads: [{ key: "alpha", label: "claude-alpha" }, { key: "beta", label: "claude-beta" }] },
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

test("another machine's session names its machine, and its pane offers only the line that reaches it", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  const card = page.locator(".card.s", { has: page.locator(".name", { hasText: "rig-lead" }) });
  await expect(card).toBeVisible({ timeout: 15_000 });
  const top = card.locator(".top");
  await expect(top.locator(".machine")).toHaveText("torad-server");
  await expect(top).not.toContainText("direct");
  await expect(top).not.toContainText("unknown head");
  await expect(top.locator(".name")).toHaveText("rig-lead");

  await top.locator(".name").click();
  const pane = page.locator("section.pane");
  await expect(pane).toBeVisible();
  await expect(pane.locator(".sendto")).toHaveCount(1);
  await expect(pane.locator(".sendto .cmd")).toHaveText('SendMessage(to="rig-lead@torad-server")');
  await expect(pane.locator(".acts button")).toHaveText([""]); // Close alone, an icon
  await expect(pane.locator("form.composer")).toHaveCount(0);
  await expect(pane).not.toContainText("Resume in a terminal");
  await page.close();
}, 30_000);

test("a session of this machine's own still offers what acts on it", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  const top = page.locator(".card.s", { has: page.locator(".name", { hasText: "builder" }) }).locator(".top");
  await expect(top).toBeVisible({ timeout: 15_000 });
  await expect(top.locator(".machine")).toHaveCount(0);
  await expect(top).toContainText("claude-alpha");
  await top.locator(".name").click();
  await expect(page.locator("section.pane form.composer")).toHaveCount(1);
  await page.close();
}, 30_000);

test("grouped, another machine's sessions sit under that machine's name, since nothing here is theirs", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  for (const by of ["Project", "Command"]) {
    await page.getByRole("button", { name: by, exact: true }).click();
    const group = page.locator(".pgroup", { has: page.locator(".pname", { hasText: "torad-server" }) });
    await expect(group.locator(".card.s .name")).toHaveText(["rig-lead"], { timeout: 15_000 });
  }
  await page.close();
}, 30_000);

test("at his width the tiles name the other machine, and its tile picks its sessions alone", async () => {
  const page = await browser.newPage({ viewport: { width: 3394, height: 1889 }, colorScheme: "dark" });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  const tiles = page.locator(".ptiles .ptile .tname");
  await expect(tiles).toHaveCount(4, { timeout: 15_000 });
  expect((await tiles.allTextContents()).sort()).toEqual(["Every project", "No project", "splice", "torad-server"]);
  await page.locator(".ptile", { has: page.locator(".tname", { hasText: "torad-server" }) }).click();
  await expect(page.locator(".card.s .name")).toHaveText(["rig-lead"]);
  await page.locator(".ptile", { has: page.locator(".tname", { hasText: "No project" }) }).click();
  await expect(page.locator(".card.s .name")).toHaveText(["builder"]);
  await page.close();
}, 30_000);

test("a machine's group offers no door to Requests, where none of its turns are, and a project's keeps one", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  const group = (name: string) => page.locator(".pgroup", { has: page.locator(".pname", { hasText: name }) });
  await expect(group("splice").locator(".pgo")).toHaveText("Open on Requests", { timeout: 15_000 });
  await expect(group("torad-server").locator(".pgo")).toHaveCount(0);
  await page.close();
}, 30_000);

test("another machine's session's pane says where its conversation is, and gives no Claude Code version here", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  await page.locator(".card.s .name", { hasText: "rig-lead" }).click({ timeout: 15_000 });
  const pane = page.locator("section.pane");
  await expect(pane.locator(".log")).toHaveText("Its conversation is on torad-server");
  await expect(pane.locator(".meta")).not.toContainText("Claude Code");
  await page.close();
}, 30_000);
