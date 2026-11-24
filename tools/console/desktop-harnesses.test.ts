// Desktop harnesses (GET and PATCH /api/desktop/harnesses), through the real Settings and Accounts pages against a stub
// daemon with made-up providers: Settings' part says GPT's harness, its glass picks Codex CLI and puts it back, every
// harness control waits while a save is out, a refused choice keeps its reason across later reads, a read that fails
// keeps the map last drawn, and a daemon with no route says so; Accounts shows each provider's harness beside it, GPT's
// Codex opt-in and Antigravity's switch are one press there, and the daemon's words are drawn as text, never as markup.
import { afterAll, beforeAll, beforeEach, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const HARNESSES = [
  { id: "claude-code", name: "Claude Code (Splice)", description: "Claude Code through Splice" },
  { id: "codex", name: "Codex CLI", description: "OpenAI's Codex CLI" },
  { id: "antigravity", name: "Antigravity", description: "agy" },
];
type Choice = { harness?: string; enabled?: boolean };
let held: Record<string, Choice> = {};
let served = true;
/** a PATCH waits for this before it is answered, while a test holds it */
let hold: Promise<void> | null = null;
/** what GET answers instead of the map, while a test sets it */
let fail: number | null = null;

const snapshot = () => {
  const gpt = { harness: held.openai?.harness ?? "claude-code", enabled: true };
  const agy = { harness: "antigravity", enabled: held.antigravity?.enabled ?? true };
  return {
    version: 1,
    default_harness: "claude-code",
    harnesses: HARNESSES,
    families: [
      { family: "openai", title: "GPT models", description: "Sessions on a GPT model <img src=x onerror=alert(1)>", ...gpt,
        set_by: held.openai ? "console" : "default", toggleable: false, choices: ["claude-code", "codex"],
        default: { harness: "claude-code", enabled: true }, providers: ["acme"], prefixes: ["claude-acme--", "claude-oai--"], configured: true },
      { family: "antigravity", title: "Antigravity", description: "Sessions on Antigravity's models", ...agy,
        set_by: held.antigravity ? "console" : "default", toggleable: true, choices: ["antigravity"],
        default: { harness: "antigravity", enabled: true }, providers: ["antigravity"], prefixes: ["claude-antigravity--"], configured: served },
    ],
    switches: [
      { prefix: "claude-acme--", provider: "acme", family: "openai", ...gpt },
      { prefix: "claude-globex--", provider: "globex", family: null, harness: "claude-code", enabled: true },
      { prefix: "claude-antigravity--", provider: "antigravity", family: "antigravity", ...agy },
    ],
    file: "desktop-harnesses.json", overrides_error: null, ignored: [],
    restart_required: held.antigravity?.enabled === false,
  };
};

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "claude", label: "claude-splice" }, { key: "acme", label: "claude-acme" }, { key: "globex", label: "claude-globex" }] },
    "/api/status": { registry: [
      { key: "claude", label: "claude-splice", family: "anthropic", authKind: "client" },
      { key: "acme", label: "claude-acme", family: "openai", authKind: "chatgpt-oauth" },
      { key: "globex", label: "claude-globex", family: "xai", authKind: "grok-oauth" },
    ] },
    "/api/models": { heads: [] },
    "/api/config": { effective: { effort: "high" }, layers: { perHead: {} }, knobs: {} },
    "/api/upgrade": { installed: "1.2.3", latest: "1.2.3", rollback_target: null },
    "/api/accounts": { accounts: [] },
    "/api/economics": { heads: [] },
    "/api/sessions": { sessions: [] },
    "/api/keys": { keys: [] },
    "GET /api/desktop/harnesses": () => (fail ? Response.json({ error: "splice answered " + fail }, { status: fail }) : snapshot()),
    "PATCH /api/desktop/harnesses": async (_u: URL, body: unknown) => {
      if (hold) await hold;
      const b = body as Record<string, Choice | null>;
      if (b.antigravity && (b.antigravity as Choice).enabled === false && !served) {
        return Response.json({ error: "antigravity: made-up refusal", families: { antigravity: "made-up refusal" } }, { status: 400 });
      }
      for (const [k, v] of Object.entries(b)) { if (v === null) delete held[k]; else held[k] = { ...held[k], ...v }; }
      return snapshot();
    },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});
beforeEach(() => { held = {}; served = true; hold = null; fail = null; s.calls.length = 0; });

async function settings(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/settings.html`);
  await expect(page.locator('[data-part="harnesses"] .pt-fig')).toHaveText("Claude Code for GPT", { timeout: 10_000 });
  await page.locator('[data-part="harnesses"]').click();
  await expect(page.locator(".lens:not([hidden]) h3")).toHaveText("Desktop harnesses");
  return page;
}
async function accounts(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/accounts.html`);
  await expect(page.locator(".rail-r .harness")).toHaveCount(3, { timeout: 10_000 });
  return page;
}
const row = (page: Page, family: string) => page.locator(`.lens:not([hidden]) [data-item="dh:${family}"]`);
const section = (page: Page, name: string) => page.locator(".rail-r .prov", { hasText: name });
const patches = () => s.calls.filter((c) => c.method === "PATCH" && c.path === "/api/desktop/harnesses");

test("Settings picks Codex CLI for GPT, says it is set here, and Reset puts Claude Code back", async () => {
  const page = await settings();
  try {
    await expect(row(page, "openai").getByRole("radio", { name: "Claude Code (Splice)" })).toHaveAttribute("aria-checked", "true");
    await row(page, "openai").getByRole("radio", { name: "Codex CLI" }).click();
    await expect(row(page, "openai").getByRole("button", { name: "Reset" })).toBeVisible();
    bunExpect(patches()[0]!.body).toEqual({ openai: { harness: "codex" } });
    await expect(page.locator('[data-part="harnesses"] .pt-fig')).toHaveText("Codex for GPT");
    await row(page, "openai").getByRole("button", { name: "Reset" }).click();
    await expect(row(page, "openai").getByRole("button", { name: "Reset" })).toHaveCount(0);
    bunExpect(patches()[1]!.body).toEqual({ openai: null });
    await expect(row(page, "rest")).toContainText("Claude Code (Splice)");
  } finally {
    await page.close();
  }
}, 30_000);

test("every harness control waits while a save is out, and a restart is asked for Antigravity", async () => {
  const page = await settings();
  let release = () => {};
  hold = new Promise<void>((r) => { release = r; });
  try {
    await row(page, "antigravity").getByRole("radio", { name: "Off" }).click();
    await expect(row(page, "openai").getByRole("radio", { name: "Codex CLI" })).toBeDisabled();
    release();
    await expect(page.locator(".lens:not([hidden]) .dp-need")).toContainText("Restart splice to apply");
    await expect(row(page, "openai").getByRole("radio", { name: "Codex CLI" })).toBeEnabled();
    bunExpect(patches().length).toBe(1);
    bunExpect(patches()[0]!.body).toEqual({ antigravity: { enabled: false } });
  } finally {
    release();
    await page.close();
  }
}, 30_000);

test("a refused choice keeps its reason across later reads; a failed read keeps the map; no route says so", async () => {
  served = false;
  const page = await settings();
  try {
    await expect(row(page, "antigravity")).toContainText("agy is not serving now");
    await row(page, "antigravity").getByRole("radio", { name: "Off" }).click();
    await expect(row(page, "antigravity").locator(".lr-why")).toHaveText("made-up refusal");
    await expect(row(page, "antigravity").getByRole("radio", { name: "On" })).toHaveAttribute("aria-checked", "true");
    fail = 500;
    await page.evaluate(() => dispatchEvent(new CustomEvent("splice:signed-in")));
    await page.waitForTimeout(800);
    await expect(row(page, "antigravity").locator(".lr-why")).toHaveText("made-up refusal");
    await expect(row(page, "openai")).toBeVisible();
  } finally {
    await page.close();
  }
  fail = 404;
  const bare = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await bare.goto(`${s.url}/settings.html`);
    await expect(bare.locator('[data-part="harnesses"] .pt-fig')).toHaveText("—", { timeout: 10_000 });
    await bare.locator('[data-part="harnesses"]').click();
    await expect(bare.locator(".lens:not([hidden])")).toContainText("This splice has no harness map");
  } finally {
    await bare.close();
  }
}, 30_000);

test("the daemon's words are drawn as text, never as markup", async () => {
  const page = await settings();
  try {
    await expect(row(page, "openai")).toContainText("<img src=x onerror=alert(1)>");
    bunExpect(await page.locator(".lens:not([hidden]) img").count()).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("Accounts shows each provider's harness, and GPT's Codex opt-in and Antigravity's switch are one press", async () => {
  const page = await accounts();
  try {
    await expect(section(page, "ChatGPT").locator(".harness .st")).toHaveText("Claude Code (Splice)");
    await expect(section(page, "Grok").locator(".harness .st")).toHaveText("Claude Code (Splice)");
    await expect(section(page, "Grok").locator(".harness button")).toHaveCount(0);
    await expect(section(page, "Grok").getByRole("link", { name: "Desktop harnesses" })).toHaveAttribute("href", "settings.html#harnesses");

    let release = () => {};
    hold = new Promise<void>((r) => { release = r; });
    await section(page, "ChatGPT").getByRole("button", { name: /Use Codex CLI/ }).click();
    await expect(section(page, "Antigravity").locator(".harness button")).toBeDisabled();
    release();
    await expect(section(page, "ChatGPT").locator(".harness .st")).toHaveText("Codex CLI");
    await expect(section(page, "ChatGPT").getByRole("button", { name: /Use Claude Code/ })).toBeEnabled();
    bunExpect(patches()[0]!.body).toEqual({ openai: { harness: "codex" } });

    await section(page, "Antigravity").getByRole("button", { name: /Turn off/ }).click();
    await expect(section(page, "Antigravity").locator(".harness .st")).toHaveText("Off");
    await expect(section(page, "Antigravity")).toContainText("Restart splice to apply");
    bunExpect(patches()[1]!.body).toEqual({ antigravity: { enabled: false } });
  } finally {
    await page.close();
  }
}, 30_000);
