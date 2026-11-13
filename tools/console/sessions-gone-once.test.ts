// The sessions that are gone are read once. A machine keeps the row of every session it has seen (3,259 on the machine
// this was measured on, Nov 12, 2026: 3,227 of them gone, 2.3 MB an answer), and the Sessions page reads the list
// every five seconds. The daemon names the gone rows by a mark; a read that sends the mark back is answered with the
// sessions still here alone. What is checked here is what he sees: the page goes on showing every session, the
// ended ones too, through the reads that leave them out, and a session that ends between two reads joins the others
// at the next read. Through the real Sessions page against a stub daemon that answers as the daemon does.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
type Row = Record<string, unknown>;
let reads = 0;
const row = (id: string, name: string, over: Row = {}): Row => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle", cwd: `/work/temple/${id}`,
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts: 1 },
  updated_at: Date.now() - 2 * HOUR,
  project: { id: "/work/temple", name: "temple", label: "temple", source: "folder", folder: "/work" },
  ...over,
});
const gone = (id: string, name: string, hoursAgo: number) => row(id, name, { availability: "gone", updated_at: Date.now() - hoursAgo * HOUR });
// "ticker" last ran a command with a new target on every read, so the target on screen says a later read was drawn.
const here = (): Row[] => [
  row("s-ticker", "ticker", { last: { role: "assistant", tool: "Bash", text: `echo read ${reads}`, ts: 3 }, updated_at: Date.now() }),
  // a second project, so the page groups its list and its header counts every session it holds
  row("s-builder", "builder", { cwd: "/work/website/s-builder", project: { id: "/work/website", name: "website", label: "website", source: "folder", folder: "/work" } }),
];
let ended: Row[] = [gone("e-1", "oldie", 30), gone("e-2", "elder", 40)];
// The mark stands for the gone rows' bytes, as the daemon's does: it differs once one is added.
const mark = () => `mark-${ended.length}`;
/** What each read of the list asked with (the mark it sent, or null) and how many rows it was answered with. The reads a
 *  page sends before its first answer lands carry no mark: there is none to send yet. */
const asked: { held: string | null; rows: number }[] = [];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/api/sessions") {
      reads += 1;
      const held = url.searchParams.get("gone");
      const same = held === mark();
      const sessions = same ? here() : [...here(), ...ended];
      asked.push({ held, rows: sessions.length });
      return Response.json({ sessions, reading: true, gone_mark: mark(), ...(same ? { gone_held: true } : {}) });
    }
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

/** The page's own count of the sessions it holds: "N live of TOTAL sessions". */
const total = (page: Page) => page.locator(".sx-hero .sx-hword b");
const card = (page: Page, name: string) => page.locator(".card.s", { has: page.locator(".name", { hasText: name }) });

/** Waits until the page has drawn a read made after this call: ticker's card no longer shows the target it showed. */
async function nextReadDrawn(page: Page) {
  const target = card(page, "ticker").locator(".last .tgt");
  await expect(target).toHaveText(/^echo read \d+$/, { timeout: 15_000 });
  const shown = await target.innerText();
  await expect(target).not.toHaveText(shown, { timeout: 15_000 });
}

test("ended sessions are read once and stay on the page; one that ends later joins them at the next read", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1200 } });
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  try {
    await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
    await expect(card(page, "builder")).toBeVisible({ timeout: 15_000 });
    // The header counts every session of the list: the two still here and the two that ended.
    await expect(total(page)).toHaveText("4", { timeout: 15_000 });
    await nextReadDrawn(page);
    await nextReadDrawn(page);
    // Two reads later the ended ones are still counted, though only the first answer carried them.
    await expect(total(page)).toHaveText("4");
    // The reads drawn since were answered with the two still here alone: the page sent the mark of the rows it holds.
    bunExpect(asked.at(-1)).toEqual({ held: "mark-2", rows: 2 });

    // A session ends between two reads: the next read is answered with every row again, and the page shows five.
    ended = [gone("e-3", "fresh", 1), ...ended];
    await expect(total(page)).toHaveText("5", { timeout: 15_000 });
    await nextReadDrawn(page);
    await expect(total(page)).toHaveText("5");
    bunExpect(asked.at(-1)).toEqual({ held: "mark-3", rows: 2 });
    bunExpect(errors).toEqual([]);
  } finally {
    await page.close();
  }
}, 120_000);
