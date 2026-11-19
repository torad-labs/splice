// nd-settings: settings.html is the approved Settings machine on the kit, on live reads: its parts show live figures
// (splice's version, one gate per command), the gates' glass holds Queue size at its live value, and a change saves at
// once through PATCH /api/config and says what the daemon answered. No rails and no composer.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, expectFrame, lens, open, WIDE, world, type Daemon } from "./lib/deck.ts";

const effective = { maxQueued: 777, maxInflight: 12, replayReasoning: true, streamIdleMs: 90_000, firstByteTimeoutMs: 90_000 };
const knobs = Object.fromEntries(Object.entries(effective).map(([k, v]) => [k, { value: v, scope: k === "maxQueued" ? "toml" : "default",
  disposition: k === "replayReasoning" ? "live" : "restart", editable: true }]));
let b: Browser;
let d: Daemon;
beforeAll(async () => {
  b = await browser();
  d = daemon(world({
    "/api/config": (req: Request, _url: URL, body: unknown) => req.method === "PATCH"
      ? { applied: body, rejected: {}, restart_required: [], persisted: "state/config.json" }
      : { effective, layers: { defaults: {}, toml: { maxQueued: 777 }, perHead: {}, file: {}, env: {}, runtime: {} }, knobs },
  }));
}, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("Settings has the shell the prototype has: no rails, no composer", async () => {
  await expectFrame(b, d, "settings.html", { current: "Settings", railL: false, railR: false, composer: false });
}, 90_000);

test("the parts show live figures, and the gates' glass holds Queue size at its value", async () => {
  for (const size of [undefined, WIDE]) {
    const o = await open(b, d, "settings.html", size);
    try {
      for (const p of ["cc", "watch", "reasoning", "limits", "mcp", "storage", "projects", "splice"]) await expect(o.page.locator(`[data-part="${p}"]`)).toHaveCount(1, { timeout: 10_000 });
      await expect(o.page.locator('[data-part="splice"]')).toContainText("9.8.7");
      await expect(o.page.locator("#gates")).toContainText("claude-acme");
      await o.page.locator('[data-part="gates"]').first().click();
      const l = await lens(o.page);
      await expect(l.locator(".lr", { hasText: "Queue size" }).first()).toContainText("777");
      expect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }
}, 60_000);

test("turning Reuse previous reasoning off saves at once and says Applied", async () => {
  const o = await open(b, d, "settings.html");
  try {
    await o.page.locator('[data-part="reasoning"]').click();
    const row = (await lens(o.page)).locator(".lr", { hasText: "Reuse previous reasoning" }).first();
    await row.locator(".seg-o", { hasText: "Off" }).click();
    await expect.poll(() => d.seen.filter((s) => s.method === "PATCH" && s.path === "/api/config").map((s) => JSON.stringify(s.body)).join(" "),
      { timeout: 10_000 }).toContain('"replayReasoning":false');
    await expect(row.locator(".sv")).toContainText("Applied", { timeout: 10_000 });
  } finally {
    await o.close();
  }
}, 60_000);

test("the old Settings page's own script and stylesheet are gone", () => {
  for (const f of ["settings.js", "settings.css"]) expect(existsSync(join(CONSOLE, f)), f).toBe(false);
});
