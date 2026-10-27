// The Sessions page reads splice again every five seconds and draws what it read. A press or a keyboard focus that
// meets that draw must still do what the user meant: a press on a session opens it, and a focused control keeps the
// focus, so Enter acts on it. The console check of Oct 26 found both lost to the draw (round 2's carry list: "A press,
// and focus on a row or button, are lost to the 5 s redraw ... fix it first, with a test that presses during a redraw
// and sees the act happen"). Through the real Sessions page against a stub daemon whose list changes on every read,
// so each read is a draw the user can see.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
let reads = 0;
const row = (id: string, name: string, text: string, ts: number) => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle", cwd: "/work/desk",
  last: { role: "assistant", tool: null, text, ts },
});
// "ticker" says a new line on every read: the line on screen tells the test that a read was drawn
const sessions = () => ({
  sessions: [
    row("s-ticker", "ticker", `Read number ${reads} is in.`, 3_000),
    row("s-builder", "builder", "builder said its last line.", 2_000),
    row("s-keeper", "keeper", "keeper said its last line.", 1_000),
  ],
});
const transcript = { messages: [], path: "", earlier: null, moves: [], unmoved: [], unparseable_lines: 0 };
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") { reads += 1; return Response.json(sessions()); }
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

const card = (page: Page, name: string) => page.locator(".card.s", { has: page.locator(".name", { hasText: name }) });
const paneName = (page: Page) => page.locator("section.pane header .who .name");

/** Waits until the page has drawn a read made after this call: ticker's card no longer shows the line it showed. */
async function nextReadDrawn(page: Page) {
  const line = card(page, "ticker").locator(".last");
  const shown = (await line.innerText()).match(/Read number \d+ is in\./)?.[0] ?? "";
  await expect(line).toContainText(/Read number \d+ is in\./, { timeout: 15_000 });
  await expect(line).not.toContainText(shown, { timeout: 15_000 });
}

async function sessionsPage() {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html`);
  await expect(card(page, "builder")).toBeVisible({ timeout: 15_000 });
  await nextReadDrawn(page); // the page has settled into its five-second reads
  return { page, errors };
}

test("a press held while the page draws a new read still opens the session it was pressed on", async () => {
  const { page, errors } = await sessionsPage();
  const box = (await card(page, "builder").locator("button.name").boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await nextReadDrawn(page);
  await page.mouse.up();
  await expect(paneName(page)).toHaveText("builder", { timeout: 5_000 });
  bunExpect(errors).toEqual([]);
  await page.close();
}, 45_000);

test("a session's name keeps the keyboard focus through a new read, and Enter then opens that session", async () => {
  const { page, errors } = await sessionsPage();
  await card(page, "builder").locator("button.name").focus();
  await nextReadDrawn(page);
  await expect(card(page, "builder").locator("button.name")).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(paneName(page)).toHaveText("builder", { timeout: 5_000 });
  bunExpect(errors).toEqual([]);
  await page.close();
}, 45_000);

test("the open session's Session details fold keeps the keyboard focus through a new read, and Enter then folds the top", async () => {
  const { page, errors } = await sessionsPage();
  await card(page, "keeper").locator("button.name").click();
  await expect(paneName(page)).toHaveText("keeper", { timeout: 5_000 });
  const fold = page.locator("section.pane button.topfold");
  await expect(fold).toHaveAttribute("aria-expanded", "true");
  await fold.focus();
  await nextReadDrawn(page);
  await expect(fold).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(fold).toHaveAttribute("aria-expanded", "false", { timeout: 5_000 });
  bunExpect(errors).toEqual([]);
  await page.close();
}, 45_000);
