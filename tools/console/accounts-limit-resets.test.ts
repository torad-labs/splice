// Accounts (Night Deck): a Claude login's glass lists its limit resets as splice reads them (GET /api/auth/{head}/resets):
// each kind, how many are left, and Use one where one can be spent now. A spend cannot be taken back, so it takes two
// presses and goes to POST /api/auth/{head}/reset with the login and the kind; a kind that cannot be used now says why,
// and a login with none says what splice answered. Through the real page against a stub daemon with made-up rows.
// (The old page had these under a login's menu, "Limit resets"; the rebuilt page left them out until Nov 21, 2026.)
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const HOUR = 3_600_000, NOW = Date.now(), sec = (ms: number) => Math.floor(ms / 1000);
const login = (label: string, name: string, email: string, over: Record<string, unknown> = {}) => ({
  provider: "anthropic", heads: ["claude"], account_labels: { claude: label }, kind: "client", label, selector_key: label,
  display_name: name, email, ref: null, credential_present: true, identity_verified: true, can_rename: true, can_remove: true,
  edit_target: { kind: "pool", id: label }, plan: "max", available: true, selected: false,
  five_hour_used_percent: 20, five_hour_reset_epoch_seconds: sec(NOW + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 40, seven_day_reset_epoch_seconds: sec(NOW + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(NOW), ...over,
});
const ROWS = [
  login("harbor", "Harbor work", "harbor@made-up.invalid", { selected: true }),
  login("pier", "Pier spare", "pier@made-up.invalid", { seven_day_used_percent: 100 }),
  login("dock", "Dock home", "dock@made-up.invalid"),
];
const KINDS: Record<string, unknown> = {
  pier: { label: "pier", offered: true, kinds: [
    { kind: "fable", name: "Fable reset", left: 0, total: 1, usable_now: false, not_now: "This login has no Fable reset left." },
    { kind: "weekly", name: "weekly reset", left: 1, total: 1, usable_now: true, ends_at: "2099-01-01T00:00:00Z" },
    { kind: "5-hour", name: "5-hour reset", left: 2, total: 2, usable_now: false, not_now: "A 5-hour reset can be used only once this login reaches a limit it clears." },
  ] },
  dock: { label: "dock", offered: false, not_offered: "This login's plan has no limit resets.", kinds: [] },
};

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "claude", label: "claude-splice", gate: {} }] },
    "/api/status": { registry: [{ key: "claude", label: "claude-splice", family: "anthropic" }] },
    "/api/models": { heads: [{ head: "claude", models: [{ id: "made-up-1", label: "Made up 1" }] }] },
    "/api/accounts": { accounts: ROWS },
    "/api/auth/claude/order": () => ({ order: ["harbor", "pier", "dock"], effective_order: ["harbor", "pier", "dock"], next_target: "dock" }),
    "/api/auth/claude/resets": (url: URL) => ({ head: "claude", logins: [KINDS[url.searchParams.get("label") ?? ""]].filter(Boolean) }),
    "POST /api/auth/claude/reset": () => ({ ok: true, label: "pier", kind: "weekly", outcome: "reset", said: "The weekly reset was used on this login. None left.", left: 0, cleared: ["seven_day"], read_again: true }),
    "/api/economics": { heads: [] },
    "/api/sessions": { sessions: [] },
    "/api/keys": { keys: [] },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function glassOf(name: string): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/accounts.html`);
  await expect(page.locator(".lane")).toHaveCount(3, { timeout: 10_000 });
  await page.locator(".lane", { has: page.locator(".mark .nm", { hasText: name }) }).locator(".mark .nm").click();
  return page;
}
const spends = () => s.calls.filter((c) => c.method === "POST" && c.path === "/api/auth/claude/reset").map((c) => c.body);

test("a Claude login's glass lists each kind of reset with how many are left, and why one cannot be used now", async () => {
  const page = await glassOf("Pier spare");
  try {
    const resets = page.locator(".lens:not([hidden]) .resets");
    await expect(resets.locator(".rs")).toHaveCount(3, { timeout: 5_000 });
    await expect(resets.locator(".rs").nth(0)).toContainText("Fable reset");
    await expect(resets.locator(".rs").nth(0)).toContainText("None left");
    await expect(resets.locator(".rs").nth(1)).toContainText("1 of 1 left");
    await expect(resets.locator(".rs").nth(1).locator('[data-act="reset-arm"]')).toHaveText("Use one");
    await expect(resets.locator(".rs").nth(2)).toContainText("can be used only once this login reaches a limit");
    await expect(resets.locator('[data-act="reset-arm"]')).toHaveCount(1);
  } finally {
    await page.close();
  }
}, 30_000);

test("Use one asks before it spends, Cancel spends nothing, and Reset now sends the login and the kind once", async () => {
  const page = await glassOf("Pier spare");
  try {
    const resets = page.locator(".lens:not([hidden]) .resets");
    await resets.locator('[data-act="reset-arm"]').click();
    await expect(resets.locator(".lr-ask")).toContainText("It cannot be undone");
    await resets.locator('[data-act="reset-keep"]').click();
    await expect(resets.locator(".lr-ask")).toHaveCount(0);
    expect(spends()).toEqual([]);
    await resets.locator('[data-act="reset-arm"]').click();
    await resets.locator('[data-act="reset-use"]').click();
    await expect(resets).toContainText("The weekly reset was used on this login", { timeout: 5_000 });
    expect(spends()).toEqual([{ label: "pier", kind: "weekly" }]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a login with no resets says what splice answered, and offers nothing to press", async () => {
  const page = await glassOf("Dock home");
  try {
    const resets = page.locator(".lens:not([hidden]) .resets");
    await expect(resets).toContainText("This login's plan has no limit resets.", { timeout: 5_000 });
    await expect(resets.locator("button")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);
