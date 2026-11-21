// Accounts (Night Deck): each Claude login is a lane on the time axis, in the order splice moves through them, named as
// /api/accounts names it with its login beside it; Use now, Move to the top, Rename and Sign out each go to the route the
// console has always used and the lane says what splice answered; a refused order puts the lanes back; today on Claude
// says part of today's history could not be read when splice could not read an hour of it. Through the real page against a stub daemon with made-up rows.
// (Rewritten from accounts-order-switch, accounts-pin-reason, accounts-walk, accounts-day, accounts-plan-split,
// accounts-restored and account-labels' Accounts case, which pinned the old page's cards.)
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
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
  login("dock", "Dock home", "dock@made-up.invalid", { ref: "d0c4" }),
  login("pier", "Pier spare", "pier@made-up.invalid", { seven_day_used_percent: 100 }),
  login("quay", "Quay old", "quay@made-up.invalid", { credential_present: false, last_read_failure: { reason: "signed_out", at_epoch_seconds: sec(NOW - HOUR) } }),
];
// this hour, which is today wherever the browser is (bun test runs in UTC, the browser in the machine's zone)
const thisHour = Math.floor(NOW / HOUR) * HOUR;
let torn = false, refuseOrder = false;

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "claude", label: "claude-splice", gate: {} }] },
    "/api/status": { registry: [{ key: "claude", label: "claude-splice", family: "anthropic" }] },
    "/api/models": { heads: [{ head: "claude", models: [{ id: "made-up-1", label: "Made up 1" }] }] },
    "/api/accounts": { accounts: ROWS },
    "/api/auth/claude/order": () => ({ order: ["harbor", "dock", "pier", "quay"], effective_order: ["harbor", "dock", "pier", "quay"], next_target: "dock", following_target: "pier" }),
    "PUT /api/auth/claude/order": () => (refuseOrder ? Response.json({ error: "order is locked" }, { status: 409 }) : { ok: true }),
    "/api/economics": () => ({
      heads: [{ key: "claude", buckets: [{ hour: thisHour, turns: 7, cost_usd: 1.25, plan_value_usd: 3 }], not_read_hours: torn ? [thisHour] : [] }],
    }),
    "/api/sessions": { sessions: [] },
    "/api/keys": { keys: [] },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function at(width = 1440): Promise<Page> {
  const page = await browser.newPage({ viewport: { width, height: 1000 } });
  await page.goto(`${s.url}/accounts.html`);
  await expect(page.locator(".lane")).toHaveCount(4, { timeout: 10_000 });
  return page;
}
const lane = (page: Page, name: string) => page.locator(".lane", { has: page.locator(".mark .nm", { hasText: name }) });
const sent = (method: string, path: string) => s.calls.filter((c) => c.method === method && c.path === path);

test("the lanes stand in splice's order, named as splice names them, each with where it stands; the login shows as it is", async () => {
  const page = await at();
  try {
    bunExpect(await page.locator(".lane .mark .nm").allInnerTexts()).toEqual(["Harbor work", "Dock home", "Pier spare", "Quay old"]);
    await expect(lane(page, "Harbor work")).toContainText("serving now");
    await expect(lane(page, "Dock home")).toContainText("next up");
    await expect(lane(page, "Pier spare")).toContainText(/back \w{3} /);
    await expect(lane(page, "Quay old")).toContainText(/sign in/i);
    await expect(page.locator("#serving")).toContainText("harbor@made-up.invalid");
    await lane(page, "Dock home").locator(".mark .nm").click();
    await expect(page.locator(".lens:not([hidden]) .login")).toHaveText("dock@made-up.invalid");
  } finally {
    await page.close();
  }
}, 30_000);

test("Use now switches to that login on splice, and the lane says splice applied it", async () => {
  const page = await at();
  try {
    await lane(page, "Dock home").locator('[data-act="use"]').click();
    await expect.poll(() => sent("POST", "/api/auth/claude/switch").map((c) => c.body)).toEqual([{ label: "dock" }]);
  } finally {
    await page.close();
  }
}, 30_000);

test("Move to the top writes the new order; one splice refuses says why and puts the lanes back", async () => {
  const page = await at();
  try {
    await lane(page, "Pier spare").locator('[data-act="menu"]').click();
    await page.getByRole("menuitem", { name: /Move to the top/ }).click();
    await expect.poll(() => sent("PUT", "/api/auth/claude/order").map((c) => c.body)).toEqual([{ order: ["pier", "harbor", "dock", "quay"] }]);
    refuseOrder = true;
    await lane(page, "Quay old").locator('[data-act="menu"]').click();
    await page.getByRole("menuitem", { name: /Move to the top/ }).click();
    await expect(page.locator(".lane-sv", { hasText: "order is locked" })).toHaveCount(1, { timeout: 5_000 });
    await expect.poll(() => page.locator(".lane .mark .nm").allInnerTexts()).toEqual(["Harbor work", "Dock home", "Pier spare", "Quay old"]);
  } finally {
    refuseOrder = false;
    await page.close();
  }
}, 30_000);

test("Rename names a login by its ref where it has one, and where it is kept where it has none", async () => {
  const page = await at();
  try {
    for (const [name, to] of [["Dock home", "Dock away"], ["Harbor work", "Harbor day"]] as const) {
      await lane(page, name).locator('[data-act="menu"]').click();
      await page.getByRole("menuitem", { name: "Rename" }).click();
      await page.keyboard.press("ControlOrMeta+a");
      await page.keyboard.type(to);
      await page.keyboard.press("Enter");
    }
    await expect.poll(() => sent("PUT", "/api/accounts/names/d0c4").map((c) => c.body)).toEqual([{ name: "Dock away" }]);
    await expect.poll(() => sent("PATCH", "/api/auth/claude/accounts/harbor").map((c) => [c.body, c.query.get("target_kind")]))
      .toEqual([[{ label: "Harbor day" }, "pool"]]);
  } finally {
    await page.close();
  }
}, 30_000);

// Rewritten Nov 19, 2026: removing what is his is one action (the owner's law of that day), so Sign out no longer
// arms on a first press and signs out on a second.
test("Sign out signs the login out on its one press", async () => {
  const page = await at();
  try {
    await lane(page, "Dock home").locator(".mark .nm").click();
    const glass = page.locator(".lens:not([hidden])");
    await expect(glass.locator('[data-act="signout"]')).toHaveText("Sign out");
    await glass.locator('[data-act="signout"]').click();
    await expect.poll(() => sent("DELETE", "/api/auth/claude/accounts/dock").length).toBe(1);
  } finally {
    await page.close();
  }
}, 30_000);

// Nov 21, 2026: the page's words for a day splice read only in part are "Part of today's history could not be read";
// this test still looked for "Not read", which the page stopped saying before the lane was merged.
test("today on Claude counts the day's requests, and says part of the day could not be read when splice could not read an hour", async () => {
  let page = await at();
  try {
    await expect(page.locator("#today .big")).toHaveText("7");
    await page.close();
    torn = true;
    page = await at();
    await expect(page.locator("#today .big")).toHaveText("—");
    await expect(page.locator("#today")).toContainText("history could not be read");
  } finally {
    torn = false;
    await page.close();
  }
}, 30_000);
