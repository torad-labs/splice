// A Claude sign-in's page ends in a code to paste back, and every card that starts one has the field for it (Marcos,
// Nov 11, 2026: "then trhe sign in in splice showed still in progress"). splice runs Claude Code's own sign-in for every
// Claude login, and the link Claude Code prints ends on a page that shows a code ("Paste code here if prompted"). Only
// a command's own Claude Code login drew the field: Sign in again on an added login, and + Add account, opened the
// same page and left its code with nowhere to go, so the sign-in stayed open until its ten minutes ran out. The step
// is splice's to say now (paste_code in a sign-in's status, LoginRoutes.kt). Through the real page against a stub
// daemon in the routes' own shapes.
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
  // an added login of the command's pool: no login place of its own, so its sign-in starts at the command's login route
  "/api/accounts": { accounts: [{
    label: "account-2", display_name: "work", ref: "work0000", heads: [HEAD], provider: "anthropic", kind: "client",
    credential_present: true, identity_verified: true, can_rename: true, selector_key: "account-2",
    account_labels: { [HEAD]: "account-2" }, edit_target: { kind: "pool", id: "account-2" }, ...windows,
  }] },
};

// The sign-ins the stub daemon holds: waiting on Claude Code's page until the code that page showed is pasted back.
type Held = { id: string; label: string | null; state: "waiting" | "signed_in" | "failed"; reason: string | null };
const held = new Map<string, Held>();
// what the page sent to the start route, and the codes it pasted back
const started: unknown[] = [];
const pasted: string[] = [];
const view = (h: Held) => ({
  id: h.id, state: h.state, label: h.label, user_code: null, verification_uri: null,
  browser_url: h.state === "waiting" ? "https://claude.example.invalid/authorize?code=true" : null,
  paste_code: h.state === "waiting",
  failure_kind: h.state === "failed" ? "not_completed" : null, failure_reason: h.reason,
});

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) {
      const body = await req.json().catch(() => ({}));
      started.push(body);
      const h: Held = { id: crypto.randomUUID(), label: body?.label ?? "account-3", state: "waiting", reason: null };
      held.set(h.id, h);
      return Response.json(view(h));
    }
    if (path === "/api/auth/logins") return Response.json({ logins: [] });
    const code = path.match(new RegExp(`^/api/auth/${HEAD}/login/([^/]+)/code$`))?.[1];
    if (code && req.method === "POST") {
      const one = held.get(code);
      const body = await req.json().catch(() => ({}));
      if (!one) return Response.json({ error: "unknown native login id" }, { status: 404 });
      pasted.push(String(body?.code ?? ""));
      // Claude Code takes the code its page showed and signs the account in; any other is not that page's
      if (body?.code !== "page-code-123") return Response.json({ error: "native login is not accepting that code" }, { status: 409 });
      one.state = "signed_in";
      return Response.json(view(one));
    }
    const one = held.get(path.match(new RegExp(`^/api/auth/${HEAD}/login/([^/]+)$`))?.[1] ?? "");
    if (one && req.method === "DELETE") { if (one.state === "waiting") Object.assign(one, { state: "failed", reason: "the sign-in was cancelled" }); return Response.json(view(one)); }
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
async function onPage(check: (page: Page) => Promise<void>) {
  held.clear(); started.length = 0; pasted.length = 0;
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
const openOnSplice = () => [...held.values()].filter((h) => h.state === "waiting").length;

test("Sign in again on an added Claude login has the field for the code its page shows, and the code signs it in", () => onPage(async (page) => {
  const there = card(page, "work");
  await there.getByRole("button", { name: "More" }).click();
  await page.getByRole("menuitem", { name: "Sign in again" }).click();
  await expect(there.getByRole("button", { name: "Open sign-in" })).toBeVisible({ timeout: 10_000 });
  bunExpect(started).toEqual([{ label: "account-2" }]);
  const field = there.getByRole("textbox", { name: "Paste code" });
  await expect(field).toBeVisible();
  await field.fill("page-code-123");
  await field.press("Enter");
  // splice took the code and signed the login in: the sign-in leaves the card
  await expect(there.getByRole("button", { name: "Open sign-in" })).toHaveCount(0, { timeout: 10_000 });
  await expect(there.locator(".result.bad")).toHaveCount(0);
  bunExpect(pasted).toEqual(["page-code-123"]);
  bunExpect(openOnSplice()).toBe(0);
}), 30_000);

test("+ Add account on a Claude command has the field too, and a code its page did not show ends the sign-in with splice's reason", () => onPage(async (page) => {
  await page.locator(`[data-act="add"][data-c="${HEAD}"]`).click();
  await page.locator(`[data-act="add-go"][data-c="${HEAD}"]`).click();
  const field = addTile(page).getByRole("textbox", { name: "Paste code" });
  await expect(field).toBeVisible({ timeout: 10_000 });
  await field.fill("some-other-code");
  await field.press("Enter");
  await expect(addTile(page)).toContainText("native login is not accepting that code", { timeout: 10_000 });
  await expect(addTile(page).getByRole("button", { name: "Try again" })).toBeVisible();
  // the page and splice agree it is over: the refused sign-in is ended there too
  await expect.poll(openOnSplice, { timeout: 10_000 }).toBe(0);
}), 30_000);
