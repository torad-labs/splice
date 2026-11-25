// Settings' Modes part (Night Deck; modes/README.md), through the real page against a stub daemon with made-up modes:
// the part's figure is how many modes are on of how many splice carries; its glass names each mode, how it is answered,
// the callers it leaves out and the tools it adds as the model sees them; a command's On or Off saves at once with
// PUT /api/modes/<name> and the switch shows the row splice answered; a press back to the mode's own value clears the
// command's entry; a command the mode is never offered on has no switch; a refused save says why and goes back.
// (The mods lane built these switches on the old Settings page, which the Night Deck replaced; Nov 21, 2026.)
import { afterAll, beforeAll, beforeEach, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

type HeadRow = { on: boolean; from: string };
type Mode = { name: string; on: { value: boolean; from: string }; heads: Record<string, HeadRow>; [k: string]: unknown };
const fresh = (): Mode[] => [
  {
    name: "template", summary: "Says back the text it is given: a mode to copy.", kind: "door", server: "template", prompt: true,
    tools: [{ name: "say_back", offered_as: "mcp__template__say_back", description: "Say the text back." }], resources: [], callers_off: ["desktop"],
    on: { value: false, from: "manifest" }, heads: { acme: { on: false, from: "manifest" }, beta: { on: false, from: "manifest" } },
  },
  {
    name: "notes", summary: "Keeps a made-up note beside the session.", kind: "door", server: "notes", prompt: false,
    tools: [{ name: "keep", offered_as: "mcp__notes__keep", description: "Keep a note." }], resources: [], callers_off: [],
    on: { value: true, from: "manifest" }, heads: { acme: { on: true, from: "manifest" }, beta: { on: false, from: "never" } },
  },
];
let modes = fresh();
let refuse = "";

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "acme", label: "claude-acme", gate: { inflight: 0 } }, { key: "beta", label: "claude-beta", gate: { inflight: 0 } }] },
    "/api/status": { registry: [{ key: "acme", label: "claude-acme", family: "openai" }, { key: "beta", label: "claude-beta", family: "xai" }] },
    "/api/config": { effective: { effort: "high" }, layers: { perHead: {} }, knobs: {} },
    "/api/upgrade": { installed: "1.2.3", latest: "1.2.3", rollback_target: null },
    "/api/modes": () => ({ modes }),
    "PUT /api/modes/template": (_u: URL, body: unknown) => {
      if (refuse) return Response.json({ error: refuse }, { status: 409 });
      const heads = (body as { heads: Record<string, boolean | null> }).heads;
      const m = modes.find((x) => x.name === "template")!;
      for (const [h, v] of Object.entries(heads)) m.heads[h] = v === null ? { on: m.on.value, from: "manifest" } : { on: v, from: "splice.toml" };
      return m;
    },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});
beforeEach(() => { modes = fresh(); refuse = ""; s.calls.length = 0; });

async function at(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/settings.html`);
  await expect(page.locator('[data-part="splice"]')).toContainText("1.2.3", { timeout: 10_000 });
  return page;
}
const glass = (page: Page) => page.locator(".lens:not([hidden])").first();
const row = (page: Page, mode: string, cmd: string) => glass(page).locator(`[data-mode="${mode}"][data-cmd="${cmd}"]`);
const puts = () => s.calls.filter((c) => c.method === "PUT" && c.path === "/api/modes/template").map((c) => c.body);

test("the part counts the modes that are on, and its glass names each mode, what it adds and who it leaves out", async () => {
  const page = await at();
  try {
    const part = page.locator('[data-part="modes"]');
    await expect(part).toContainText("Modes");
    await expect(part).toContainText("1");
    await expect(part).toContainText("of 2 on");
    await part.click();
    const g = glass(page);
    await expect(g).toContainText("Says back the text it is given: a mode to copy.");
    await expect(g).toContainText("Answered inside Splice");
    await expect(g).toContainText("Not offered to Claude Desktop's own sessions");
    await expect(g).toContainText("mcp__template__say_back");
    await expect(g).toContainText("mcp__notes__keep");
  } finally {
    await page.close();
  }
}, 30_000);

test("a mode on for Claude Desktop alone counts as on, and its glass says so beside commands that are off", async () => {
  modes = [...fresh(), {
    name: "canvas", summary: "Draws a made-up canvas.", kind: "mcp", server: "canvas", prompt: false, server_taken: false,
    tools: [{ name: "draw", offered_as: "mcp__canvas__draw", description: "Draw." }], resources: [], callers_off: [], on_for: ["desktop"],
    on: { value: false, from: "manifest" }, heads: { acme: { on: false, from: "manifest" }, beta: { on: false, from: "manifest" } },
    callers: { door: { on: false, from: "manifest" }, head: { on: false, from: "manifest" }, desktop: { on: true, from: "manifest" } },
  }];
  const page = await at();
  try {
    const part = page.locator('[data-part="modes"]');
    await expect(part).toContainText("2");
    await expect(part).toContainText("of 3 on");
    await part.click();
    const g = glass(page);
    await expect(g).toContainText("MCP server · On for Claude Desktop's own sessions");
    await expect(row(page, "canvas", "acme").getByRole("radio", { name: "Off" })).toHaveAttribute("aria-checked", "true");
  } finally {
    await page.close();
  }
}, 30_000);

test("On for one command saves at once and says Applied; back to the mode's own value clears the command's entry", async () => {
  const page = await at();
  try {
    await page.locator('[data-part="modes"]').click();
    const acme = row(page, "template", "acme");
    await acme.getByRole("radio", { name: "On" }).click();
    await expect.poll(puts).toEqual([{ heads: { acme: true } }]);
    await expect(acme.locator(".sv")).toContainText("Applied");
    await expect(page.locator('[data-part="modes"]')).toContainText("2");
    await row(page, "template", "acme").getByRole("radio", { name: "Off" }).click();
    await expect.poll(puts).toEqual([{ heads: { acme: true } }, { heads: { acme: null } }]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a command the mode is never offered on has no switch, and says so", async () => {
  const page = await at();
  try {
    await page.locator('[data-part="modes"]').click();
    const beta = row(page, "notes", "beta");
    await expect(beta).toContainText("Never offered on this command");
    await expect(beta.getByRole("radio")).toHaveCount(0);
    await expect(row(page, "notes", "acme").getByRole("radio")).toHaveCount(2);
  } finally {
    await page.close();
  }
}, 30_000);

test("a save splice refuses says why on the row, and the switch goes back", async () => {
  const page = await at();
  try {
    refuse = "splice.toml could not be written";
    await page.locator('[data-part="modes"]').click();
    const acme = row(page, "template", "acme");
    await acme.getByRole("radio", { name: "On" }).click();
    await expect(acme.locator(".lr-why")).toHaveText("splice.toml could not be written");
    await expect(acme.getByRole("radio", { name: "Off" })).toHaveAttribute("aria-checked", "true");
    bunExpect(modes[0]!.heads.acme!.on).toBe(false);
  } finally {
    await page.close();
  }
}, 30_000);
