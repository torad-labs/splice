// NEW (Nov 15, 2026): a login whose provider refused its credential reads Signed out on its card. Two Claude logins
// whose refresh tokens Anthropic rejected kept their credential files, so their cards drew week-old readings with only a
// "Read" time, and nothing said they needed signing in. Through the real page against a stub daemon in /api/accounts'
// own shape: a login whose last read failed signed out says Signed out and offers Sign in under its own label, and a
// login whose last read failed for another reason keeps its reading.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const HOUR = 3_600_000;
const now = Date.now();
const sec = (ms: number) => Math.floor(ms / 1000);
const HEAD = "claude";
// a reading two days old, as a login keeps once its reads stop answering
const oldWindows = {
  seven_day_used_percent: 51, seven_day_reset_epoch_seconds: sec(now + 80 * HOUR), seven_day_window_seconds: 604_800,
  seven_day_current: false, observed_at_epoch_seconds: sec(now - 48 * HOUR),
};
const login = (name: string, label: string, extra: Record<string, unknown>) => ({
  label, display_name: name, ref: label.padEnd(8, "0").slice(0, 8), heads: [HEAD], account_labels: { [HEAD]: label },
  selector_key: label, provider: "anthropic", kind: "client", credential_present: true, identity_verified: true,
  can_rename: true, ...oldWindows, ...extra,
});
const failure = (reason: string) => ({ last_read_failure: { reason, at_epoch_seconds: sec(now - 60_000) } });
const answers: Record<string, unknown> = {
  "/api/status": { registry: [{ key: HEAD, label: HEAD, family: "anthropic", authKind: "client" }] },
  "/api/accounts": { accounts: [
    login("Costshield", "account-4", failure("signed_out")),
    login("Grailseeker", "account-10", failure("not_answering")),
  ] },
};
// the labels the page's sign-ins named
const signedIn: unknown[] = [];
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path === `/api/auth/${HEAD}/login`) {
      signedIn.push((await req.json().catch(() => null))?.label);
      return Response.json({ id: crypto.randomUUID(), state: "pending", label: "account-4", user_code: null, verification_uri: null, browser_url: null });
    }
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

async function onPage(check: (page: Page) => Promise<void>) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: "dark" });
  const page = await context.newPage();
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await expect(card(page, "Grailseeker")).toBeVisible({ timeout: 15_000 });
    await check(page);
  } finally {
    await context.close();
  }
}
const card = (page: Page, name: string) => page.locator("article.card").filter({ has: page.locator(".name", { hasText: new RegExp(`^${name}$`) }) });

test("a login whose provider refused its credential says Signed out and signs in again under its own label", () => onPage(async (page) => {
  const out = card(page, "Costshield");
  await expect(out).toContainText("Signed out");
  await expect(out).not.toContainText("51%");
  await out.getByRole("button", { name: "Sign in" }).click();
  await expect.poll(() => signedIn).toEqual(["account-4"]);
}), 30_000);

test("a login whose last read failed for another reason keeps its reading", () => onPage(async (page) => {
  const kept = card(page, "Grailseeker");
  await expect(kept).toContainText("51%");
  await expect(kept).not.toContainText("Signed out");
  bunExpect(await kept.getByRole("button", { name: "Sign in" }).count()).toBe(0);
}), 30_000);
