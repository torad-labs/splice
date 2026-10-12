// Labels only (Eli's ruling via splice-lead, Oct 12): no page displays an account's email or account id. Through the
// real pages against a daemon answering in the label contract (a roster row's `display_name`, `ref`, `label_default`;
// a request row's `account` ref and `account_label`): Accounts, Requests and Sessions put no "@" and no id anywhere in
// the DOM, text or attribute, and a login splice named itself is offered its one naming on Accounts, beside its name.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const REF = "1a2b3c4d", NAMED = "5e6f7a8b";
const ID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const login = (ref: string, name: string, isDefault: boolean, label: string) => ({
  provider: "anthropic", heads: ["claude"], account_labels: { claude: label }, kind: "client", label, display_name: name,
  label_default: isDefault, ref, identity_verified: true, can_rename: true, can_remove: true,
  edit_target: { kind: "pool", id: label }, credential_present: true, plan: "max", single_login: false, primary: false,
  five_hour_used_percent: 20, five_hour_reset_epoch_seconds: Math.floor(now / 1000) + 3600, five_hour_window_seconds: 18_000,
  observed_at_epoch_seconds: Math.floor(now / 1000), pinned_heads: [], failover_positions: { claude: null },
});
const ROSTER: ReturnType<typeof login>[] = [login(REF, `Max · ${REF}`, true, "claude-2"), login(NAMED, "Work", false, "work")];
const turn = (id: string, agoS: number, ref: string, label: string) => ({
  turn: id, turn_id: id, ts: now - agoS * 1000, outcome: "ok", model: "m", session: "0c3b07d2", total: 2_000, attempts: 1, retries: 0,
  account: ref, account_label: label,
});
const ROWS = [turn("t-1", 30, REF, `Max · ${REF}`), turn("t-2", 60, NAMED, "Work")];
const answers: Record<string, unknown> = {
  "/api/status": { registry: [{ key: "claude", label: "claude-splice", family: "anthropic", authKind: "client" }] },
  "/api/accounts": { accounts: ROSTER, head_pools: {} },
  "/api/keys": { keys: [] },
  "/api/budgets": { budgets: [] },
  "/api/models": { heads: [] },
  "/api/heads": { heads: [{ key: "claude", label: "claude-splice" }] },
  "/api/heads/retired": { retired: [] },
  "/api/sessions": { reading: true, sessions: [{ session_id: "0c3b07d2-0000", name: "notes", head: "claude", route: "head", account: "work",
    availability: "live", status: "idle", cwd: "/work/notes", title: null, last: null, updated_at: new Date(now - 60_000).toISOString() }] },
};
const ADDED = "9c0d1e2f", puts: { path: string; body: unknown }[] = [];
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (req.method === "PUT" && path.startsWith("/api/accounts/names/")) {
      puts.push({ path, body: await req.json() });
      return Response.json({ ok: true });
    }
    if (req.method === "POST" && path === "/api/auth/claude/login") { // the sign-in lands at once: a new login on the roster
      if (!ROSTER.some((r) => r.ref === ADDED)) ROSTER.push(login(ADDED, `Max · ${ADDED}`, true, "claude-3"));
      return Response.json({ id: "L1", state: "signed_in" });
    }
    if (path === "/api/perf/turns") {
      return Response.json({ heads: [{ key: "claude", label: "claude-splice", count: ROWS.length, returned: ROWS.length, truncated: false, rows: ROWS,
        usage: { models: [], accounts: [{ key: REF, label: `Max · ${REF}`, requests: 1 }, { key: NAMED, label: "Work", requests: 1 }], sessions: [] } }] });
    }
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

/** Waits for a condition the page brings about on its own time. */
async function until(ok: () => boolean, ms: number) {
  for (const end = Date.now() + ms; !ok() && Date.now() < end; ) await Bun.sleep(50);
  expect(ok()).toBe(true);
}

/** Every text and attribute value on the page: what a person could read, hover or copy. */
const everything = (page: Page) => page.evaluate(() =>
  [document.body.innerText, ...[...document.querySelectorAll("*")].flatMap((el) => [...el.attributes].map((a) => a.value))].join("\n"));

async function sweep(path: string, ready: string) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/${path}`);
    await page.locator(ready).first().waitFor({ timeout: 15_000 });
    const all = await everything(page);
    expect(all).not.toContain("@");
    expect(ID.test(all)).toBe(false);
    return all;
  } finally {
    await page.close();
  }
}

test("Accounts names each login by its label, with no email or id anywhere on the page", async () => {
  const all = await sweep("accounts.html", ".card .name");
  expect(all).toContain(`Max · ${REF}`);
  expect(all).toContain("Work");
}, 30_000);

test("Requests names a login by its label, with no email or id anywhere on the page", async () => {
  const all = await sweep("requests.html", 'article[data-open="claude:t-1"]');
  expect(all).toContain(`Max · ${REF}`);
}, 30_000);

test("Sessions puts no email or id anywhere on the page", async () => {
  await sweep("sessions.html", 'article[data-open="0c3b07d2-0000"]');
}, 30_000);

test("a login splice named itself offers Name it beside its name, and a named one does not", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    const unnamed = page.locator(".card", { hasText: `Max · ${REF}` });
    await unnamed.waitFor({ timeout: 15_000 });
    expect(await page.locator(".card", { hasText: "Work" }).locator(".name-it").count()).toBe(0);
    await unnamed.locator(".name-it").click();
    const field = page.locator(".field.rename");
    await field.waitFor({ timeout: 5_000 });
    expect(await field.inputValue()).toBe("");
    expect(await field.getAttribute("placeholder")).toBe(`Max · ${REF}`);
  } finally {
    await page.close();
  }
}, 30_000);

test("Name it keeps the name against the login's ref, never its sign-in files", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  puts.length = 0;
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    const unnamed = page.locator(".card", { hasText: `Max · ${REF}` });
    await unnamed.waitFor({ timeout: 15_000 });
    await unnamed.locator(".name-it").click();
    await page.locator(".field.rename").fill("Side gig");
    await page.locator(".field.rename").press("Enter");
    await until(() => puts.length === 1, 5_000);
    expect(puts[0]).toEqual({ path: `/api/accounts/names/${REF}`, body: { name: "Side gig" } });
  } finally {
    await page.close();
  }
}, 30_000);

test("Add opens an empty name: Enter alone signs in unnamed, and a typed name is given to the login that lands", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  puts.length = 0;
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await page.locator('[data-act="add"]').first().click();
    const field = page.locator(".add-name");
    await field.waitFor({ timeout: 5_000 });
    expect(await field.inputValue()).toBe(""); // no "<Provider> N" saved as if he chose it (Marlin, Oct 12)
    await field.press("Enter");
    await until(() => ROSTER.some((r) => r.ref === ADDED), 10_000);
    await Bun.sleep(500);
    expect(puts).toEqual([]); // unnamed: the daemon's default names it

    ROSTER.splice(ROSTER.findIndex((r) => r.ref === ADDED), 1);
    await page.reload();
    await page.locator('[data-act="add"]').first().click();
    await page.locator(".add-name").fill("Side gig");
    await page.locator(".add-name").press("Enter");
    await until(() => puts.length === 1, 10_000);
    expect(puts[0]).toEqual({ path: `/api/accounts/names/${ADDED}`, body: { name: "Side gig" } });
  } finally {
    await page.close();
  }
}, 30_000);
