// A command's glass on Providers holds what the old Models page showed and changed about it, through the real page
// against a stub daemon with made-up values: the wire it speaks is named; a system prompt typed and saved, and a context
// window picked, each reach splice.toml as one PUT /api/topology carrying that change; a signed-out command offers Sign
// in, which starts splice's own sign-in for it; and a log longer than the glass shows is reached line by line back to its
// first line; a header and a behaviour are saved to the provider's table, sharing with Claude Code is the command's own,
// and a test request is sent only when pressed. (Rewritten from models-restored and models-sign-in, which pinned the old Models page; models-calls-tools,
// models-empty-rule, models-new-command-read, models-views-finish and models-poll pinned its cards, tabs and timers,
// which the approved Providers page does not have, and went with it.)
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const LINES = Array.from({ length: 300 }, (_, i) => `[2026-10-29 09:${String(Math.floor(i / 60)).padStart(2, "0")}:${String(i % 60).padStart(2, "0")}.000] [acme] note line ${String(i + 1).padStart(3, "0")}`);
const topology = () => ({
  heads: { acme: { provider: "acme-co", port: 41002 }, beta: { provider: "beta-co" } },
  providers: { "acme-co": { base_url: "https://api.acme.example", auth: { kind: "api-key", env: "ACME_KEY" }, models: [{ id: "acme-large-2", context_window: 200000, max_context_window: 400000 }] },
    "beta-co": { base_url: "https://api.beta.example" } },
});
const puts: Record<string, unknown>[] = [];

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [
      { key: "acme", label: "claude-acme", state: "running", port: 41002, dialect: "openai-chat", authKind: "api-key", gate: { inflight: 0 } },
      { key: "beta", label: "claude-beta", state: "running", port: 41003, dialect: "anthropic-messages", authKind: "acme-oauth", gate: { inflight: 0 } },
    ] },
    "/api/status": { version: "9.8.7", registry: [{ key: "acme", label: "claude-acme", family: "openai" }, { key: "beta", label: "claude-beta", family: "openai" }] },
    "/api/models": { heads: [
      { head: "acme", pinned_model: "acme-large-2", models: [{ id: "acme-large-2", label: "Acme Large 2", context_window: 200000, max_context_window: 400000 }] },
      { head: "beta", models: [{ id: "beta-one", label: "Beta One" }] },
    ] },
    "/api/topology": (_u: URL, body: unknown, req: Request) => {
      if (req.method === "PUT") { puts.push((body as { topology: Record<string, unknown> }).topology); return { ok: true, restart_required: false }; }
      return { topology: topology(), vocab: { auth_kinds: [{ kind: "api-key", label: "API key" }, { kind: "acme-oauth", label: "Acme sign-in" }],
        share: ["settings", "mcps"], quirks: [{ name: "store", kind: "bool", dialects: ["openai-chat"] }] } };
    },
    "/api/doctor": { checks: [{ id: "auth/beta", status: "fail", cause: "signed_out" }] },
    "POST /api/playground": { request: { turn_id: "compare-t" }, response: { status: 200, body: { raw: "" } } },
    "/api/projects": { projects: [{ id: "harbor", name: "harbor", root: "/work/harbor" }] },
    "/api/logs/acme": { path: "/var/splice/daemon.log", lines: LINES },
    "POST /api/auth/beta/login": { id: "s-1", state: "waiting", browser_url: "https://signin.example.invalid/start" },
    "/api/auth/beta/login/s-1": { id: "s-1", state: "waiting", browser_url: "https://signin.example.invalid/start" },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => { await browser?.close(); s?.stop(); });

async function glass(cmd: string): Promise<{ page: Page; errors: string[]; close: () => Promise<void> }> {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage(), errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(`${s.url}/providers.html?cmd=${cmd}`);
  await expect(page.locator(".lens:not([hidden]) h3")).toContainText(cmd, { timeout: 15_000 });
  return { page, errors, close: () => ctx.close() };
}

test("the wire a command speaks is named, and a typed system prompt is saved into splice.toml", async () => {
  const o = await glass("claude-acme");
  try {
    const l = o.page.locator(".lens:not([hidden])");
    await expect(l.locator('[data-item="where"]')).toContainText("OpenAI Chat");
    const row = l.locator('[data-item="prompt"]');
    await row.locator("textarea").fill("Answer in short sentences.");
    puts.length = 0;
    await row.getByRole("button", { name: "Save", exact: true }).click();
    await expect(row.locator(".sv")).toContainText("Applied");
    bunExpect((puts[0] as { heads: Record<string, { system_prompt?: string }> }).heads.acme?.system_prompt).toBe("Answer in short sentences.");
    bunExpect(o.errors).toEqual([]);
  } finally { await o.close(); }
}, 60_000);

test("a context window picked is written to the provider's row for that model", async () => {
  const o = await glass("claude-acme");
  try {
    const row = o.page.locator('.lens:not([hidden]) [data-item="window"]');
    await expect(row.locator(".pickbtn")).toContainText("200k");
    puts.length = 0;
    await row.locator(".pickbtn").click();
    await o.page.locator(".menu button", { hasText: "400k" }).click();
    await expect(row.locator(".sv")).toContainText("Applied");
    const p = puts[0] as { providers: Record<string, { models: { id: string; context_window: number }[] }> };
    bunExpect(p.providers["acme-co"]?.models[0]).toMatchObject({ id: "acme-large-2", context_window: 400000 });
  } finally { await o.close(); }
}, 60_000);

test("a signed-out command offers Sign in, and Sign in starts splice's sign-in for it", async () => {
  const o = await glass("claude-beta");
  try {
    const need = o.page.locator('.lens:not([hidden]) [data-item="fix"]');
    await expect(need).toContainText("Signed out");
    await need.getByRole("button", { name: "Sign in" }).click();
    await expect.poll(() => s.calls.some((c) => c.method === "POST" && c.path === "/api/auth/beta/login")).toBe(true);
    await expect(o.page.locator(".lens:not([hidden]) a", { hasText: "Open the sign-in page" })).toBeVisible();
  } finally { await o.close(); }
}, 60_000);

test("every line of a long log is reached, back to the first and forward to the newest", async () => {
  const o = await glass("claude-acme");
  try {
    const row = o.page.locator('.lens:not([hidden]) [data-item="logs"]');
    await row.getByRole("button", { name: "Show" }).click();
    const log = row.locator(".pc-log");
    await expect(log).toContainText("note line 300");
    await expect(log).not.toContainText("note line 001");
    for (let i = 0; i < 10 && !(await log.textContent())?.includes("note line 001"); i++) await row.getByRole("button", { name: /earlier$/ }).click();
    await expect(log).toContainText("note line 001");
    await row.getByRole("button", { name: "Newest" }).click();
    await expect(log).toContainText("note line 300");
  } finally { await o.close(); }
}, 60_000);

test("a header, a behaviour and what it shares with Claude Code are saved; a test request goes only when pressed", async () => {
  const o = await glass("claude-acme");
  try {
    const l = o.page.locator(".lens:not([hidden])");
    bunExpect(s.calls.some((c) => c.method === "POST" && c.path === "/api/playground")).toBe(false);
    const signin = l.locator('[data-item="signin"]');
    await signin.getByRole("button", { name: "Add header" }).click();
    await signin.getByLabel("Header name").fill("X-Team");
    await signin.getByLabel("Header value").fill("harbor");
    puts.length = 0;
    await signin.getByRole("button", { name: "Save headers" }).click();
    await expect(signin.locator(".sv")).toContainText("Applied");
    bunExpect((puts[0] as { providers: Record<string, { extra_headers?: Record<string, string> }> }).providers["acme-co"]?.extra_headers).toEqual({ "X-Team": "harbor" });
    const quirks = l.locator('[data-item="quirks"]');
    puts.length = 0;
    await quirks.locator(".pickbtn").first().click();
    await o.page.locator(".menu button", { hasText: "Off" }).click();
    await expect(quirks.locator(".sv")).toContainText("Applied");
    bunExpect((puts[0] as { providers: Record<string, { quirks?: Record<string, unknown> }> }).providers["acme-co"]?.quirks).toEqual({ store: false });
    const share = l.locator('[data-item="share"]');
    await expect(share.locator(".pickbtn")).toContainText("2 of 2 shared");
    puts.length = 0;
    await share.locator(".pickbtn").click();
    await o.page.locator(".menu button", { hasText: "MCP servers" }).click();
    await expect(share.locator(".sv")).toContainText("Applied");
    bunExpect((puts[0] as { heads: Record<string, { claude?: { isolate?: string[] } }> }).heads.acme?.claude?.isolate).toEqual(["mcps"]);
    await l.locator('[data-item="test"]').getByRole("button", { name: "Send one" }).click();
    await expect.poll(() => s.calls.filter((c) => c.method === "POST" && c.path === "/api/playground").map((c) => (c.body as { head: string }).head)).toEqual(["acme"]);
  } finally { await o.close(); }
}, 60_000);

// Rewritten Nov 19, 2026: removing a command is one action (the owner's law of that day), so no ask stands before it.
test("Remove removes the command on its one press", async () => {
  const o = await glass("claude-acme");
  try {
    const acts = o.page.locator('.lens:not([hidden]) [data-item="actions"]');
    await acts.getByRole("button", { name: "Remove", exact: true }).click();
    await expect.poll(() => s.calls.filter((c) => c.method === "DELETE" && c.path === "/api/heads/acme").length).toBe(1);
  } finally { await o.close(); }
}, 60_000);
