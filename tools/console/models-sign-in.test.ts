// A command's Sign in on Models follows the sign-in splice holds (Marcos, Nov 11, 2026: "TRhe whol state machine system
// for login is broken"). Models ran its own loop over a sign-in: it read "Not completed" however the sign-in had failed,
// went on drawing "Signing in" for five minutes over one a restarted splice no longer held, and its Cancel let go of the
// box before splice had answered. It runs the steps every page shares now (SIGNIN, api.js). Through the real page
// against a stub daemon in the routes' own shapes (LoginRoutes.kt).
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HEAD = "alpha";
// The sign-ins the stub daemon holds, as splice holds them: open until ended, and ended only by a DELETE or by splice.
type Held = { id: string; open: boolean; kind: string | null; reason: string | null };
const held = new Map<string, Held>();
// what the daemon does with a sign-in's reads: answers them, or has restarted and knows no such sign-in
let restarted = false;
// how long the daemon takes to answer a DELETE, so the page is seen waiting on it
let endDelayMs = 0;
// the command's sign-in is Claude Code's own: its page ends in a code to paste back, and the status says so
let pastes = false;
const pasted: string[] = [];
const view = (h: Held) => ({
  id: h.id, state: h.open ? "waiting" : h.kind ? "failed" : "signed_in", label: null,
  user_code: h.open && !pastes ? "ABCD-1234" : null, verification_uri: h.open && !pastes ? "https://signin.example.invalid/device" : null,
  browser_url: h.open && pastes ? "https://claude.example.invalid/authorize?code=true" : null, paste_code: h.open && pastes,
  failure_kind: h.open ? null : h.kind, failure_reason: h.open ? null : h.reason,
});
const openOnSplice = () => [...held.values()].filter((h) => h.open).length;
const end = (kind: string, reason: string) => { for (const h of held.values()) if (h.open) Object.assign(h, { open: false, kind, reason }); };

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) {
      const h: Held = { id: crypto.randomUUID(), open: true, kind: null, reason: null };
      held.set(h.id, h);
      return Response.json(view(h));
    }
    const coded = path.match(new RegExp(`^/api/auth/${HEAD}/login/([^/]+)/code$`))?.[1];
    if (coded && req.method === "POST") {
      const one = held.get(coded);
      if (!one) return Response.json({ error: "unknown native login id" }, { status: 404 });
      pasted.push(String((await req.json().catch(() => ({})))?.code ?? ""));
      one.open = false; // Claude Code took the code its page showed: the sign-in landed
      return Response.json(view(one));
    }
    const id = path.match(new RegExp(`^/api/auth/${HEAD}/login/([^/]+)$`))?.[1];
    if (id) {
      const one = held.get(id);
      if (!one || restarted) return Response.json({ error: "no such login" }, { status: 404 });
      if (req.method === "DELETE") {
        await new Promise((r) => setTimeout(r, endDelayMs));
        if (one.open) Object.assign(one, { open: false, kind: "cancelled", reason: "the sign-in was cancelled" });
      }
      return Response.json(view(one));
    }
    // one command, running, whose sign-in doctor reports as missing: Models offers Sign in on it
    if (path === "/api/heads") return Response.json({ heads: [{ key: HEAD, label: `claude-${HEAD}`, running: true, healthy: true, dialect: "openai-responses", authKind: "chatgpt-oauth" }] });
    if (path === "/api/topology") return Response.json({ topology: { heads: { [HEAD]: { provider: "alpha-co" } }, providers: { "alpha-co": { base_url: "https://api.alpha.example", auth: { kind: "chatgpt-oauth" } } } } });
    if (path === "/api/models") return Response.json({ heads: [{ head: HEAD, pinned_model: null, models: [{ id: "alpha-one", label: "Alpha One", resolved: true, slot: "opus" }] }] });
    if (path === "/api/doctor") return Response.json({ checks: [{ id: `auth/${HEAD}`, status: "fail", detail: "signed out" }] });
    if (path === "/api/sessions") return Response.json({ sessions: [] });
    if (path === "/api/projects") return Response.json({ projects: [], folders: [] });
    if (path === "/api/models/local") return Response.json({ providers: [] });
    if (path === "/api/claude-head") return Response.json({ mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" });
    if (path === "/api/add/profiles") return Response.json({ profiles: [] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

// The command's pane, with its sign-in started and its code on the page, or, for a sign-in whose page ends in a code to
// paste back, the field for it.
async function signingIn(check: (page: Page) => Promise<void>, paste = false) {
  held.clear(); restarted = false; endDelayMs = 0; pastes = paste; pasted.length = 0;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/models.html`);
    await page.locator(`article.card.h[data-open="${HEAD}"]`).click({ timeout: 15_000 });
    await page.locator(".pane header").getByRole("button", { name: "Sign in", exact: true }).click({ timeout: 15_000 });
    if (paste) await expect(page.locator(".pane header").getByRole("textbox", { name: "Paste code" })).toBeVisible({ timeout: 10_000 });
    else await expect(page.locator(".pane header .usercode")).toHaveText("ABCD-1234", { timeout: 10_000 });
    await check(page);
  } finally {
    await page.close();
  }
}
const header = (page: Page) => page.locator(".pane header");

test("Models: a sign-in splice no longer holds, because it restarted, says Stopped and gives Sign in back", () => signingIn(async (page) => {
  restarted = true;
  await expect(header(page).locator(".result")).toHaveText("Stopped", { timeout: 10_000 });
  await expect(header(page).locator(".usercode")).toHaveCount(0);
  await expect(header(page).getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
}), 60_000);

test("Models: a sign-in that fails says how in a word, with splice's own reason under it", () => signingIn(async (page) => {
  end("expired", "the sign-in was not finished in ten minutes");
  await expect(header(page).locator(".result")).toHaveText("Expired", { timeout: 10_000 });
  await expect(header(page).locator(".signwhy")).toHaveText("the sign-in was not finished in ten minutes");
}), 60_000);

// Claude Code's own sign-in: the page its link opens ends by showing a code, and the box had nowhere to put it.
test("Models: a sign-in whose page ends in a code to paste back has the field for it, and the code signs the command in", () => signingIn(async (page) => {
  const field = header(page).getByRole("textbox", { name: "Paste code" });
  await expect(header(page).getByRole("button", { name: "Open sign-in" })).toBeVisible();
  await field.fill("page-code-123");
  await field.press("Enter");
  await expect(field).toHaveCount(0, { timeout: 10_000 });
  await expect(header(page).locator(".result")).toHaveCount(0);
  bunExpect(pasted).toEqual(["page-code-123"]);
}, true), 60_000);

test("Models: Cancel ends the sign-in on splice, and the box reads Cancelling until splice has answered", () => signingIn(async (page) => {
  endDelayMs = 1500;
  await header(page).getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(header(page).getByRole("button", { name: "Cancelling" })).toBeDisabled();
  bunExpect(openOnSplice()).toBe(1); // splice has not answered yet, and the box is still there
  await expect(header(page).getByRole("button", { name: "Sign in", exact: true })).toBeVisible({ timeout: 10_000 });
  bunExpect(openOnSplice()).toBe(0);
  await expect(header(page).locator(".result")).toHaveCount(0);
}), 60_000);
