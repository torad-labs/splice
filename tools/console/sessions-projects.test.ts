// Sessions grouped by project. He has two jobs on this page: see who is stuck on him, anywhere, and find the one
// session in the project he is working in. The grouping serves the second and must never cost the first, so what is
// checked here is the order, the header's counts, that a folded group still shows what waits on him, that a project
// name matches every session in it, and that one project alone keeps the flat page. Through the real page.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
type Row = Record<string, unknown>;
/** One /api/sessions row: [p] is the project the daemon resolved, null for a session in none. */
const row = (id: string, name: string, p: string | null, over: Row = {}): Row => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle",
  cwd: `/work/${p ?? "loose"}/${id}`, title: null,
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts: 1 },
  updated_at: new Date(Date.now() - 2 * HOUR).toISOString(),
  ...(p ? { project: { id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" } } : {}),
  ...over,
});
const needs = (id: string, name: string, p: string | null) => row(id, name, p, { waiting_for: "input", status: "idle" });
const working = (id: string, name: string, p: string | null) => row(id, name, p, { status: "working" });
const ended = (id: string, name: string, p: string | null) => row(id, name, p, { availability: "gone" });

let sessions: Row[] = [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ sessions, reading: true });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path === "/api/models") return Response.json({ heads: [] });
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
}, 60_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

/** The page, with the daemon answering [rows]. */
async function page(rows: Row[]): Promise<Page> {
  sessions = rows;
  const p = await browser.newPage({ viewport: { width: 1440, height: 1200 } });
  await p.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  await expect(p.locator(".list")).toBeVisible({ timeout: 15_000 });
  return p;
}
const names = (p: Page) => p.locator(".pgroup .pname").allTextContents();

const THREE = [
  working("w-1", "api", "website"),
  row("i-1", "notes", "website"),
  needs("n-1", "asking", "temple"),
  working("w-2", "builder", "temple"),
  row("l-1", "stray", null),
];

// ORDER: the group with the most urgent card first, No project last, whatever the activity times say.
test("groups come in the order of their most urgent card, with No project last", async () => {
  const p = await page(THREE);
  try {
    expect(await names(p)).toEqual(["temple", "website", "No project"]);
  } finally {
    await p.close();
  }
}, 60_000);

// The header is the line he finds at a glance: the name, a lamp and count per live state present, then the total.
test("a group's header counts each live state it holds, and totals its sessions", async () => {
  const p = await page([...THREE, ended("e-1", "done", "temple")]);
  try {
    const temple = p.locator(".pgroup", { hasText: "temple" }).first();
    await expect(temple.locator(".pc.needs b")).toHaveText("1");
    await expect(temple.locator(".pc.working b")).toHaveText("1");
    await expect(temple.locator(".ptotal")).toHaveText("3"); // the ended one is in the total, not a lamp
    await expect(temple.locator(".pgroup .pc.stalled")).toHaveCount(0);
  } finally {
    await p.close();
  }
}, 60_000);

// NOTHING WAITING ON HIM IS EVER HIDDEN BY A FOLD (Marlin): the second job must not cost the first.
test("a folded group still shows the cards that wait on him, and hides the rest", async () => {
  const p = await page(THREE);
  try {
    const temple = p.locator(".pgroup", { hasText: "temple" }).first();
    await expect(temple.locator(".card.s")).toHaveCount(2);
    await temple.locator(".pfold").click();
    await expect(temple.locator(".card.s")).toHaveCount(1);
    await expect(temple.locator(".card.s")).toContainText("asking");
  } finally {
    await p.close();
  }
}, 60_000);

test("a project's name finds every session in it, and the groups stay", async () => {
  const p = await page(THREE);
  try {
    await p.locator("#q").fill("website");
    await expect(p.locator(".pgroup")).toHaveCount(1, { timeout: 10_000 });
    await expect(p.locator(".pgroup .pname")).toHaveText("website");
    await expect(p.locator(".pgroup .card.s")).toHaveCount(2); // both, though neither is named "website"
  } finally {
    await p.close();
  }
}, 60_000);

// A first-time user with one project keeps the page he had: headers are what two groups buy, not one.
test("one project and nothing outside it stays a flat list", async () => {
  const p = await page([working("w-1", "api", "website"), row("i-1", "notes", "website")]);
  try {
    await expect(p.locator(".pgroup")).toHaveCount(0);
    await expect(p.locator(".list .card.s")).toHaveCount(2);
  } finally {
    await p.close();
  }
}, 60_000);

// ONE NAME FOR WHERE A SESSION IS (Marlin, Oct 12): the open pane names the project its group wears, never the folder,
// which reads "repo" for most of his sessions.
test("the open session names its project, the same name its group wears", async () => {
  const p = await page(THREE);
  try {
    await p.locator(".pgroup", { hasText: "temple" }).first().locator(".card.s .name", { hasText: "builder" }).click();
    const meta = p.locator(".pane .meta").first();
    await expect(meta).toContainText("temple", { timeout: 10_000 });
    await expect(meta).not.toContainText("w-2"); // its folder
  } finally {
    await p.close();
  }
}, 60_000);

// In no project the folder is the only clue, so that card names it, from home, as the daemon spells it.
test("a session in no project names its folder from home on its card", async () => {
  const p = await page([...THREE.slice(0, 4), row("l-1", "stray", null, { place: "~/notes" })]);
  try {
    const loose = p.locator(".pgroup", { hasText: "No project" }).first();
    await expect(loose.locator(".card.s .where")).toHaveText("~/notes");
    await expect(p.locator(".pgroup", { hasText: "temple" }).first().locator(".card.s .where")).toHaveCount(0);
  } finally {
    await p.close();
  }
}, 60_000);

// The header says where a session is, so the card does not repeat it; unnamed, the card takes the first line of its
// first message, which the daemon reads for it, and never the folder.
test("inside a group a card is titled by what was asked of it, never by its folder", async () => {
  const p = await page([
    row("u-1", "", "temple", { name: null, title: "Fix the retry ladder in the gate" }),
    working("w-1", "api", "website"),
  ]);
  try {
    const temple = p.locator(".pgroup", { hasText: "temple" }).first();
    await expect(temple.locator(".card.s .name")).toHaveText("Fix the retry ladder in the gate");
    await expect(temple.locator(".card.s")).not.toContainText("u-1");
  } finally {
    await p.close();
  }
}, 60_000);
