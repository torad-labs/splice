// A login added from the console is on its command when its tile closes (Nov 19, 2026: signing in to ChatGPT from the
// console did nothing anyone could see). A ChatGPT, Grok, Kimi or Muse command reads which logins it has when splice
// starts, and the restart a landed sign-in took started the same command over the same logins: the login was written,
// the tile closed as done, and the command went on without it, neither listed nor used, until someone restarted splice
// by hand. splice restarts itself for such a login now and the sign-in reads restarting (LoginSessions.kt). Through
// the real page against a stub daemon in the routes' own shapes (LoginRoutes.kt): the tile says splice is restarting,
// with nothing to press, stays through the seconds splice is away, and closes on the new login's card once the next
// splice answers; and a splice that cannot restart itself says to restart it, with its reason, in place of a tile
// that closed on nothing.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HEAD = "claudex";
const ADDED = "team";
const REFUSED = `signed in as '${ADDED}', and splice could not restart itself to use the login (the daemon runs under no supervisor). Run splice restart in a terminal.`;
const login = (name: string, extra: Record<string, unknown>) => ({
  label: name, display_name: name, ref: name.padEnd(8, "0").slice(0, 8), heads: [HEAD], provider: "openai", kind: "chatgpt-oauth",
  credential_present: true, identity_verified: true, can_rename: true, account_labels: { [HEAD]: name }, ...extra,
});
// The stub is one splice after another, as a restart is: `up` counts them, and while `away` it answers nothing a page
// can read. A sign-in lives in the splice that started it, so the next one answers 404 for its id.
const daemon = { up: 0, away: false, id: "", born: -1, read: 0, restarts: true };
const reset = (restarts: boolean) => Object.assign(daemon, { up: 0, away: false, id: "", born: -1, read: 0, restarts });
const status = (state: string, extra: Record<string, unknown> = {}) => ({
  id: daemon.id, state, label: null, user_code: null, verification_uri: null, browser_url: null, failure_kind: null, failure_reason: null, ...extra,
});
function signIn() {
  if (daemon.born !== daemon.up) return Response.json({ error: "no such login" }, { status: 404 });
  if (!daemon.restarts) return Response.json(status("failed", { label: ADDED, failure_kind: "needs_restart", failure_reason: REFUSED }));
  daemon.read += 1;
  // the first read finds the sign-in page still open; the next finds the login written and splice restarting for it
  if (daemon.read === 1) return Response.json(status("waiting", { browser_url: "https://auth.example.invalid/authorize" }));
  // splice goes once the page has read that, and the next one is up three seconds later
  setTimeout(() => { daemon.away = true; }, 200);
  setTimeout(() => { daemon.up += 1; daemon.away = false; }, 3200);
  return Response.json(status("restarting", { label: ADDED }));
}
// the manager's pill at the foot of the page is idle, as on a splice whose manager runs
const MANAGER = {
  folder: "/home/someone/.config/splice/manager", consented: true, state: "live", head: HEAD,
  session_id: "m-1", status: "idle", line: { model: "GPT-5.6 Codex", model_id: "gpt-5.6-codex", effort: "high", at: Date.now() },
  heads: [{ key: HEAD, label: HEAD, ready: true }],
};
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path.startsWith("/api/") && daemon.away) return new Response("", { status: 503 });
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) {
      Object.assign(daemon, { id: crypto.randomUUID(), born: daemon.up, read: 0 });
      return Response.json(status("waiting", { browser_url: "https://auth.example.invalid/authorize" }));
    }
    if (daemon.id && path === `/api/auth/${HEAD}/login/${daemon.id}`) return signIn();
    if (path === "/api/status") return Response.json({ registry: [{ key: HEAD, label: HEAD, family: "openai", authKind: "chatgpt-oauth" }] });
    if (path === "/api/accounts") {
      const first = login("work", { label: "primary", primary: true, selector_key: "primary", account_labels: { [HEAD]: "primary" } });
      // the command holds the added login only once a splice that started after the sign-in is the one answering
      const added = daemon.born >= 0 && daemon.up > daemon.born ? [login("side", { label: ADDED, selector_key: ADDED, account_labels: { [HEAD]: ADDED } })] : [];
      return Response.json({ accounts: [first, ...added] });
    }
    if (path === "/api/manager") return Response.json(MANAGER);
    if (/^\/api\/auth\/[^/]+\/order$/.test(path)) return Response.json({ order: [], effective_order: [], next_target: null, following_target: null });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
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

const card = (page: Page, name: string) => page.locator("article.card").filter({ has: page.locator(".name", { hasText: new RegExp(`^${name}(?: \\d+)?$`) }) });
// SIGNIN_SHOTS names a folder to keep what the tile looked like in, at the width and theme asked for
const shots = process.env.SIGNIN_SHOTS;
const width = Number(process.env.SIGNIN_WIDTH) || 1440;
const theme = process.env.SIGNIN_THEME === "day" ? "day" : "night";
const shot = async (page: Page, name: string) => { if (shots) await page.screenshot({ path: join(shots, `${name}-${theme}-${width}.png`) }); };

async function addAccount(restarts: boolean, check: (page: Page, tile: ReturnType<Page["locator"]>) => Promise<void>) {
  reset(restarts);
  const context = await browser.newContext({ viewport: { width, height: 1000 }, colorScheme: theme === "day" ? "light" : "dark" });
  await context.addInitScript((t) => localStorage.setItem("splice-console-theme", t), theme);
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page, "work")).toBeVisible({ timeout: 15_000 });
    await page.locator(`[data-act="add"][data-c="${HEAD}"]`).click();
    await page.locator(`[data-act="add-go"][data-c="${HEAD}"]`).click();
    await check(page, page.locator(`[data-key="add:${HEAD}"]`));
  } finally {
    await context.close();
  }
}

test("an added login the command did not have: the tile says splice is restarting, and closes on the login's card", () => addAccount(true, async (page, tile) => {
  await expect(tile.getByText("Restarting splice to use this login")).toBeVisible({ timeout: 10_000 });
  // signed in already: nothing is left to open or to cancel
  await expect(tile.getByRole("button")).toHaveCount(0);
  bunExpect(await card(page, "side").count()).toBe(0);
  await shot(page, "restarting");
  // the next splice answers, with the login on the command
  await expect(card(page, "side")).toBeVisible({ timeout: 20_000 });
  await expect(tile.getByText("Restarting splice")).toHaveCount(0);
  bunExpect(daemon.up).toBe(1);
}), 60_000);

test("a splice that cannot restart itself says Restart splice, with its reason and no Try again", () => addAccount(false, async (page, tile) => {
  await expect(tile.locator(".result", { hasText: "Restart splice" })).toBeVisible({ timeout: 10_000 });
  await expect(tile.locator(".reason")).toHaveText(REFUSED);
  // the account is signed in, so a Try again would sign it in a second time
  await expect(tile.getByRole("button", { name: "Try again" })).toHaveCount(0);
  await shot(page, "needs-restart");
}), 30_000);
