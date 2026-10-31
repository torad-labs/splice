// The console opens with no secret in any address (Oct 26, 2026). `splice console` hands the page a single-use code in
// the address fragment (#c=…), never a key. The page takes the code out of the address before anything else, trades it
// once at /api/console/key for the console key, and reads splice with that key alone. A spent code signs nothing in, so
// a browser that holds no key shows Locked. An address from before, which carried the management key itself (#k=…), is
// cleared from view and its key is never kept or sent. The daemon's side of the trade (single use, the two-minute
// lifetime, what the key opens) is ConsoleSignInTest's; this stand-in answers the trade the way the daemon does.
import { afterAll, beforeAll, beforeEach, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const STORE = "splice-console-access-key";
// Where a browser signed in before Oct 26 kept what it was handed then: the management key itself.
const LEGACY_STORE = "splice-console-key";
const CONSOLE_KEY = "c0", MANAGEMENT_KEY = "a1";
const consoleKey = CONSOLE_KEY.repeat(32), managementKey = MANAGEMENT_KEY.repeat(32);
const fresh = new Set<string>();
const addresses: string[] = [], bearers: string[] = [], traded: string[] = [];
let refused = 0;

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    addresses.push(req.url);
    if (path === "/") return new Response(null, { status: 302, headers: { Location: "/accounts.html" } });
    if (path === "/api/console/key" && req.method === "POST") {
      const code = String(((await req.json().catch(() => ({}))) as { code?: unknown }).code ?? "");
      traded.push(code);
      if (!fresh.delete(code)) return Response.json({ error: "spent, expired or unknown" }, { status: 401 });
      return Response.json({ key: consoleKey }, { headers: { "Cache-Control": "no-store" } });
    }
    if (path.startsWith("/api/")) {
      const bearer = req.headers.get("authorization") ?? "";
      bearers.push(bearer);
      if (bearer === `Bearer ${consoleKey}`) return Response.json({});
      refused++;
      return Response.json({ error: "unauthorized" }, { status: 401 });
    }
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
const origin = `http://127.0.0.1:${server.port}`;
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});
beforeEach(() => {
  addresses.length = 0;
  bearers.length = 0;
  traded.length = 0;
  refused = 0;
});

/** A code the stand-in daemon will take once, as one `splice console` run mints it. */
function mint(): string {
  const code = crypto.getRandomValues(new Uint8Array(32)).reduce((hex, b) => hex + b.toString(16).padStart(2, "0"), "");
  fresh.add(code);
  return code;
}

test("a page handed a code clears it from the address, trades it once, and reads splice with the key it got", async () => {
  const code = mint();
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(`${origin}/#c=${code}`);
    await page.waitForFunction(([store, key]) => localStorage.getItem(store) === key, [STORE, consoleKey] as const, { timeout: 15_000 });
    await page.waitForLoadState("networkidle");
    expect(page.url()).toBe(`${origin}/accounts.html`);
    expect(traded).toEqual([code]);
    // The code went up once, in the trade's body: no address the browser asked for carried it.
    expect(addresses.filter((a) => a.includes(code))).toEqual([]);
    // Every read waited for the trade and carried the key it answered, so none was refused and nothing locked.
    expect(bearers.length).toBeGreaterThan(0);
    expect(bearers.filter((b) => b !== `Bearer ${consoleKey}`)).toEqual([]);
    expect(refused).toBe(0);
    expect(await page.locator(".lockedby").count()).toBe(0);
    // Reloaded, the page is still signed in by the key this browser kept, and trades nothing.
    await page.reload();
    await page.waitForLoadState("networkidle");
    expect(traded).toEqual([code]);
    expect(refused).toBe(0);
    expect(await page.locator(".lockedby").count()).toBe(0);
  } finally {
    await context.close();
  }
}, 40_000);

test("a spent code signs nothing in: a browser that holds no key shows Locked and keeps nothing", async () => {
  const code = mint();
  const first = await browser.newContext();
  const second = await browser.newContext();
  try {
    const signedIn = await first.newPage();
    await signedIn.goto(`${origin}/#c=${code}`);
    await signedIn.waitForFunction(([store, key]) => localStorage.getItem(store) === key, [STORE, consoleKey] as const, { timeout: 15_000 });
    // The same address again, in a browser holding no key: a tool that printed it, or a history entry reopened.
    const late = await second.newPage();
    await late.goto(`${origin}/#c=${code}`);
    await late.locator(".lockedby").waitFor({ timeout: 15_000 });
    expect(late.url()).toBe(`${origin}/accounts.html`);
    expect(await late.evaluate((store) => localStorage.getItem(store), STORE)).toBeNull();
    expect(traded).toEqual([code, code]);
  } finally {
    await first.close();
    await second.close();
  }
}, 40_000);

test("an address that carries the management key, as pages before Oct 26 did, is cleared and its key never kept or sent", async () => {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(`${origin}/#k=${managementKey}`);
    await page.locator(".lockedby").waitFor({ timeout: 15_000 });
    expect(page.url()).toBe(`${origin}/accounts.html`);
    expect(await page.evaluate((store) => localStorage.getItem(store), STORE)).toBeNull();
    expect(bearers.filter((b) => b.includes(managementKey))).toEqual([]);
    expect(traded).toEqual([]);
  } finally {
    await context.close();
  }
}, 40_000);

test("a browser that kept the management key before Oct 26 forgets it when a page loads, and sends it nowhere", async () => {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    // Kept the way a console page before Oct 26 kept it, from an address of the console's origin that runs no page.
    await page.goto(`${origin}/no-page-here`);
    await page.evaluate(([store, key]) => localStorage.setItem(store, key), [LEGACY_STORE, managementKey] as const);
    await page.goto(`${origin}/accounts.html`);
    await page.locator(".lockedby").waitFor({ timeout: 15_000 });
    expect(await page.evaluate((store) => localStorage.getItem(store), LEGACY_STORE)).toBeNull();
    expect(bearers.length).toBeGreaterThan(0);
    expect(bearers.filter((b) => b.includes(managementKey))).toEqual([]);
  } finally {
    await context.close();
  }
}, 40_000);
