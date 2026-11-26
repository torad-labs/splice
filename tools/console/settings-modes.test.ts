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
/** canvas's row with splice.toml's [modes.canvas] on at [on] (null: no table) */
const canvasRow = (on: boolean | null) => {
  const from = on === null ? "manifest" : "splice.toml";
  const st = (v: boolean) => ({ on: v, from });
  return {
    on: { value: on === true, from }, heads: { acme: st(on === true), beta: st(on === true) },
    callers: { door: st(on === true), head: st(on === true), desktop: st(on !== false) },
  };
};
const canvas = (): Mode => ({
  name: "canvas", summary: "Draws a made-up canvas.", kind: "mcp", server: "canvas", prompt: false, server_taken: false,
  tools: [{ name: "draw", offered_as: "mcp__canvas__draw", description: "Draw." }], resources: [], callers_off: [], on_for: ["desktop"],
  ...canvasRow(null),
});
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
    // the daemon's rule for a mode whose manifest says on_for = ["desktop"]: splice.toml's on, else Desktop alone
    "PUT /api/modes/canvas": (_u: URL, body: unknown) => {
      const on = (body as { on: boolean | null }).on;
      return Object.assign(modes.find((x) => x.name === "canvas")!, canvasRow(on));
    },
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

test("a mode on for Claude Desktop alone says what Desktop and the terminal get, and its switch sets every session", async () => {
  modes = [...fresh(), canvas()];
  const page = await at();
  const every = () => glass(page).locator('[data-mode="canvas"][data-every]');
  const now = () => glass(page).locator('[data-item="mode:canvas:every"] .lr-now');
  const sent = () => s.calls.filter((c) => c.method === "PUT" && c.path === "/api/modes/canvas").map((c) => c.body);
  try {
    const part = page.locator('[data-part="modes"]');
    await expect(part).toContainText("2");
    await expect(part).toContainText("of 3 on");
    await part.click();
    // the default: on in Claude Desktop alone, off in the terminal and on every command
    await expect(every().getByRole("radio", { name: "Default" })).toHaveAttribute("aria-checked", "true");
    await expect(now()).toContainText("Now on in Claude Desktop, off in claude in a terminal. Default: on in Claude Desktop only.");
    await expect(glass(page)).toContainText("In the terminal, by command");
    await expect(row(page, "canvas", "acme").getByRole("radio", { name: "Off" })).toHaveAttribute("aria-checked", "true");

    // On: every session, and every command's switch shows it
    await every().getByRole("radio", { name: "On" }).click();
    await expect.poll(sent).toEqual([{ on: true }]);
    await expect(now()).toContainText("Now on in Claude Desktop, on in claude in a terminal.");
    await expect(row(page, "canvas", "acme").getByRole("radio", { name: "On" })).toHaveAttribute("aria-checked", "true");
    await expect(page.locator('[data-part="modes"]')).toContainText("2");

    // Off: no session, Claude Desktop's included, and the mode no longer counts as on
    await every().getByRole("radio", { name: "Off" }).click();
    await expect.poll(sent).toEqual([{ on: true }, { on: false }]);
    await expect(now()).toContainText("Now off in Claude Desktop, off in claude in a terminal.");
    await expect(row(page, "canvas", "acme").getByRole("radio", { name: "Off" })).toHaveAttribute("aria-checked", "true");
    await expect(page.locator('[data-part="modes"]')).toContainText("1");

    // Default: the table cleared, Claude Desktop alone again
    await every().getByRole("radio", { name: "Default" }).click();
    await expect.poll(sent).toEqual([{ on: true }, { on: false }, { on: null }]);
    await expect(now()).toContainText("Now on in Claude Desktop, off in claude in a terminal.");
    bunExpect(modes.find((x) => x.name === "canvas")!.callers).toEqual(canvasRow(null).callers);
  } finally {
    await page.close();
  }
}, 30_000);

test("a mode without on_for has no switch for every session, as before", async () => {
  const page = await at();
  try {
    await page.locator('[data-part="modes"]').click();
    await expect(glass(page).locator("[data-every]")).toHaveCount(0);
    await expect(glass(page)).not.toContainText("In the terminal, by command");
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
