// Cancel on a sign-in ends it on splice (Marcos, Nov 11, 2026: "it said it canceled, then when I tried sign in again,
// it said it was already in progress, so it didnt really cancelled"). Until that day the page's Cancel only forgot the
// sign-in in the page: splice kept it for its ten minutes and refused the next Sign in on that login. Through the real
// page against a stub daemon in the routes' own shapes (LoginRoutes.kt): Cancel asks splice to end the sign-in and the
// card lets go of it only once splice has answered, and a page opened while splice holds a sign-in shows it where it
// would have been pressed, with a Cancel that ends it.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HEAD = "claude-splice";
const windows = {
  five_hour_used_percent: 20, five_hour_reset_epoch_seconds: sec(now + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 30, seven_day_reset_epoch_seconds: sec(now + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(now - 60_000),
};
const answers: Record<string, unknown> = {
  "/api/status": { registry: [{ key: HEAD, label: HEAD, family: "anthropic", authKind: "client" }] },
  "/api/accounts": { accounts: [{
    label: "account-2", display_name: "work", ref: "work0000", heads: [HEAD], provider: "anthropic", kind: "client",
    credential_present: true, identity_verified: true, can_rename: true, selector_key: "account-2", ...windows,
  }] },
};

// The sign-ins the stub daemon holds, as splice holds them: open until ended, and ended only by a DELETE.
type Held = { id: string; label: string | null; open: boolean };
const held = new Map<string, Held>();
// When set, a DELETE waits for it: the moment between Cancel being pressed and splice having ended the sign-in.
let answerCancel: Promise<void> | null = null;
const view = (h: Held) => ({
  id: h.id, state: h.open ? "waiting" : "failed", label: h.label, user_code: null, verification_uri: null,
  browser_url: h.open ? "https://claude.example.invalid/authorize" : null,
  failure_kind: h.open ? null : "cancelled", failure_reason: h.open ? null : "the sign-in was cancelled",
});
const hold = (label: string | null) => {
  const h = { id: crypto.randomUUID(), label, open: true };
  held.set(h.id, h);
  return h;
};
const openOnSplice = () => [...held.values()].filter((h) => h.open);

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) return Response.json(view(hold("account-3")));
    if (path === "/api/auth/logins") return Response.json({ logins: openOnSplice().map((h) => ({ ...view(h), head: HEAD })) });
    const one = held.get(path.match(new RegExp(`^/api/auth/${HEAD}/login/([^/]+)$`))?.[1] ?? "");
    if (one && req.method === "DELETE") {
      await answerCancel;
      one.open = false;
      return Response.json(view(one));
    }
    if (one) return Response.json(view(one));
    if (path in answers) return Response.json(answers[path]);
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
const addTile = (page: Page) => page.locator(`[data-key="add:${HEAD}"]`);
// [before]: what splice already holds when the page opens
async function onPage(check: (page: Page) => Promise<void>, before: () => void = () => {}) {
  held.clear();
  answerCancel = null;
  before();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: "dark" });
  await context.addInitScript(() => localStorage.setItem("splice-console-theme", "night"));
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page, "work")).toBeVisible({ timeout: 15_000 });
    await check(page);
  } finally {
    await context.close();
  }
}

test("Cancel ends the sign-in on splice, and the card lets go of it only once splice has answered", () => onPage(async (page) => {
  await page.locator(`[data-act="add"][data-c="${HEAD}"]`).click();
  await page.locator(`[data-act="add-go"][data-c="${HEAD}"]`).click();
  await expect(addTile(page).getByRole("button", { name: "Open sign-in" })).toBeVisible({ timeout: 10_000 });
  let answer = () => {};
  answerCancel = new Promise<void>((done) => { answer = done; });
  await addTile(page).getByRole("button", { name: "Cancel" }).click();
  // splice has not answered: the sign-in is still open there, and the card says it is being cancelled, with nothing to press
  await expect(addTile(page).getByRole("button", { name: "Cancelling" })).toBeDisabled();
  bunExpect(openOnSplice()).toHaveLength(1);
  answer();
  await expect(page.locator(`[data-act="add"][data-c="${HEAD}"]`)).toBeVisible({ timeout: 10_000 });
  bunExpect(openOnSplice()).toHaveLength(0);
}), 30_000);

test("a page opened while splice holds sign-ins shows each where it would have been pressed, and its Cancel ends it", () => onPage(async (page) => {
  // one signs the login "work" in again, so it stands on that login's card; the other adds a login, so it stands on
  // the command's Add account
  await expect(card(page, "work").getByRole("button", { name: "Open sign-in" })).toBeVisible({ timeout: 10_000 });
  await expect(addTile(page).getByRole("button", { name: "Open sign-in" })).toBeVisible();
  await card(page, "work").getByRole("button", { name: "Cancel" }).click();
  await expect(card(page, "work").getByRole("button", { name: "Cancel" })).toHaveCount(0, { timeout: 10_000 });
  bunExpect(openOnSplice().map((h) => h.label)).toEqual(["account-9"]);
  await addTile(page).getByRole("button", { name: "Cancel" }).click();
  await expect(page.locator(`[data-act="add"][data-c="${HEAD}"]`)).toBeVisible({ timeout: 10_000 });
  bunExpect(openOnSplice()).toHaveLength(0);
}, () => { hold("account-2"); hold("account-9"); }), 30_000);
