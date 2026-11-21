// nd-client: a Night Deck page reaches splice the way every console page does. Locked out (every read answers 401),
// it shows the console's lock, in the console's words, with `splice console` to run, and lifts it the moment another
// tab of this browser is signed in. `splice console <action>` drives it: the pilot reads it and knows the six pages.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { browser, daemon, NARROW, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
beforeAll(async () => { b = await browser(); }, 30_000);
afterAll(async () => { await b?.close(); });

test("locked out, a deck page shows the lock and lifts it when this browser signs in", async () => {
  const d: Daemon = daemon({ "*": () => Response.json({ error: "unauthorized" }, { status: 401 }) });
  const ctx = await b.newContext({ viewport: NARROW });
  try {
    const page = await ctx.newPage();
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(`${d.url}/probe/probe.html`, { waitUntil: "load" });
    await expect(page.getByText("Locked", { exact: true })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("splice console", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Copy" })).toBeVisible();
    d.answers["*"] = {};
    const other = await ctx.newPage();
    await other.goto(`${d.url}/probe/probe.html`, { waitUntil: "load" });
    await other.evaluate(() => localStorage.setItem("splice-console-access-key", "made-up-console-key"));
    await expect(page.getByText("Locked", { exact: true })).toHaveCount(0, { timeout: 10_000 });
    expect(errors).toEqual([]);
  } finally {
    await ctx.close();
    d.stop();
  }
}, 60_000);

test("the pilot reads a deck page and names the six pages", async () => {
  const orders = [
    { id: "o-read", action: "read", words: [] },
    { id: "o-go", action: "goto", words: ["nowhere"] },
  ];
  const d: Daemon = daemon(world({
    "/api/console/pilot/next": async () => {
      const next = orders.shift();
      if (next) return next;
      await Bun.sleep(400);
      return new Response(null, { status: 204 });
    },
  }));
  const ctx = await b.newContext({ viewport: NARROW });
  try {
    const page = await ctx.newPage();
    await page.goto(`${d.url}/probe/probe-pilot.html`, { waitUntil: "load" });
    await page.bringToFront();
    const answers = () => d.seen.filter((s) => s.method === "POST" && s.path === "/api/console/pilot/answer").map((s) => s.body as Record<string, unknown>);
    await expect.poll(() => answers().length, { timeout: 15_000 }).toBeGreaterThanOrEqual(2);
    const [read, go] = [answers().find((a) => a.id === "o-read"), answers().find((a) => a.id === "o-go")];
    expect(read?.ok).toBe(true);
    expect(String(read?.text)).toContain("Probe line for the pilot to read");
    expect(go?.ok).toBe(false);
    const said = String(go?.error).toLowerCase();
    for (const p of ["sessions", "accounts", "teams", "providers", "activity", "settings"]) expect(said).toContain(p);
  } finally {
    await ctx.close();
    d.stop();
  }
}, 60_000);
