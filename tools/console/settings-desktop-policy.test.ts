// Settings' Desktop policy part (GET and PATCH /api/desktop/policy), through the real page against a stub daemon with
// made-up keys: a choice the daemon holds no value for says Default, never "null"; Enter in a list's field adds the
// entry, empties the field and draws the glass again; every control waits while a save is out, so a second press can't
// race the first; and a value splice refuses keeps its reason under its row across later reads, until it saves.
import { afterAll, beforeAll, beforeEach, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

type Key = { key: string; label: string; description: string; type: string; options?: string[]; min?: number; max?: number; def: unknown };
const KEYS: Key[] = [
  { key: "codeDefaultPermissionMode", label: "Permission mode", description: "How Code asks first", type: "enum", options: ["manual", "acceptEdits", "plan", "auto", "dontAsk", "bypassPermissions"], def: null },
  { key: "mcpToolTimeoutSec", label: "MCP tool timeout", description: "How long one MCP tool call may run", type: "integer", min: 60, max: 3600, def: 1800 },
  { key: "coworkEgressAllowedHosts", label: "Cowork hosts", description: "Hosts Cowork may reach", type: "hosts", def: ["*"] },
  { key: "keepAwakeEnabled", label: "Keep awake", description: "Keep the computer awake", type: "boolean", def: true },
];
let held: Record<string, unknown> = {};
/** a PATCH waits for this before it is answered, while a test holds it */
let hold: Promise<void> | null = null;

const snapshot = () => ({
  groups: [{ id: "code", title: "Code & models", keys: KEYS.map(({ def, ...k }) => ({
    ...k, default: def, effective: held[k.key] ?? def, override: held[k.key] ?? null, overridden: k.key in held,
  })) }],
  locked: ["inferenceProvider"], overrides_error: null, ignored: [], restart_required: false,
});

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "acme", label: "claude-acme", gate: { inflight: 0 } }] },
    "/api/status": { registry: [{ key: "acme", label: "claude-acme", family: "openai" }] },
    "/api/config": { effective: { effort: "high" }, layers: { perHead: {} }, knobs: {} },
    "/api/upgrade": { installed: "1.2.3", latest: "1.2.3", rollback_target: null },
    "GET /api/desktop/policy": () => snapshot(),
    "PATCH /api/desktop/policy": async (_u: URL, body: unknown) => {
      if (hold) await hold;
      const b = body as Record<string, unknown>;
      const t = b.mcpToolTimeoutSec;
      if (typeof t === "number" && (t < 60 || t > 3600)) {
        return Response.json({ error: "mcpToolTimeoutSec: expected a whole number from 60 to 3600", keys: { mcpToolTimeoutSec: "expected a whole number from 60 to 3600" } }, { status: 400 });
      }
      for (const [k, v] of Object.entries(b)) { if (v === null) delete held[k]; else held[k] = v; }
      return { ...snapshot(), published: 1 };
    },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});
beforeEach(() => { held = {}; hold = null; s.calls.length = 0; });

async function glass(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/settings.html`);
  await expect(page.locator('[data-part="desktop"] .pt-fig')).toHaveText("0 set here", { timeout: 10_000 });
  await page.locator('[data-part="desktop"]').click();
  await expect(page.locator(".lens:not([hidden]) h3")).toHaveText("Desktop policy");
  return page;
}
const row = (page: Page, key: string) => page.locator(`.lens:not([hidden]) [data-item="dp:${key}"]`);
const patches = () => s.calls.filter((c) => c.method === "PATCH" && c.path === "/api/desktop/policy");

test("a choice splice holds no value for says Default, never null", async () => {
  const page = await glass();
  try {
    await expect(row(page, "codeDefaultPermissionMode").locator(".pickbtn-v")).toHaveText("Default");
  } finally {
    await page.close();
  }
}, 30_000);

test("Enter in a list's field adds the entry once, empties the field and draws the glass again", async () => {
  const page = await glass();
  try {
    const field = row(page, "coworkEgressAllowedHosts").locator("input");
    await field.fill("api.example.com");
    await field.press("Enter");
    await expect(row(page, "coworkEgressAllowedHosts").locator(".kvs b")).toHaveText(["*", "api.example.com"]);
    await expect(row(page, "coworkEgressAllowedHosts").locator("input")).toHaveValue("");
    bunExpect(patches().length).toBe(1);
    bunExpect(patches()[0]!.body).toEqual({ coworkEgressAllowedHosts: ["*", "api.example.com"] });
  } finally {
    await page.close();
  }
}, 30_000);

test("every control waits while a save is out, and the save draws the glass again", async () => {
  const page = await glass();
  let release = () => {};
  hold = new Promise<void>((r) => { release = r; });
  try {
    await row(page, "keepAwakeEnabled").getByRole("radio", { name: "Off" }).click();
    await expect(row(page, "mcpToolTimeoutSec").locator("input")).toBeDisabled();
    await expect(row(page, "keepAwakeEnabled").getByRole("radio", { name: "On" })).toBeDisabled();
    release();
    await expect(row(page, "keepAwakeEnabled").getByRole("button", { name: "Reset" })).toBeVisible();
    await expect(row(page, "mcpToolTimeoutSec").locator("input")).toBeEnabled();
    bunExpect(patches().length).toBe(1);
  } finally {
    release();
    await page.close();
  }
}, 30_000);

test("a refused value keeps its reason across later reads, and Enter saves a good one that clears it", async () => {
  const page = await glass();
  try {
    const field = row(page, "mcpToolTimeoutSec").locator("input");
    await field.fill("5");
    await field.press("Enter");
    await expect(row(page, "mcpToolTimeoutSec").locator(".lr-why")).toHaveText("expected a whole number from 60 to 3600");
    await expect(row(page, "mcpToolTimeoutSec").locator("input")).toHaveValue("1800");
    await page.evaluate(() => dispatchEvent(new CustomEvent("splice:signed-in")));
    await page.waitForTimeout(800);
    await expect(row(page, "mcpToolTimeoutSec").locator(".lr-why")).toHaveText("expected a whole number from 60 to 3600");

    await row(page, "mcpToolTimeoutSec").locator("input").fill("600");
    await row(page, "mcpToolTimeoutSec").locator("input").press("Enter");
    await expect(row(page, "mcpToolTimeoutSec").locator(".lr-why")).toHaveCount(0);
    await expect(row(page, "mcpToolTimeoutSec").locator("input")).toHaveValue("600");
    bunExpect(patches().length).toBe(2);
  } finally {
    await page.close();
  }
}, 30_000);
