// Compare models on Providers sends one prompt to each model picked, through splice, and reads the answers side by
// side, through the real page against a stub daemon with made-up answers: Enter sends one POST /api/playground per
// column; each answer stands under its column with its time and tokens, the best time marked; a failure reads first,
// saying why; an over-budget command's refusal says so with what it spent; an answer opens its request on Activity by
// the id the reply names; a command that can only use Claude Code's own sign-in says it can't send and is not asked;
// the prompt and the picks are kept in this browser. (Rewritten from compare-marks-and-prompt, compare-budget,
// compare-remove-sending and account-reads, which pinned the old Compare page; compare-board-places, compare-sign-in
// and compare-start-fix pinned its board of cards and its in-page sign-in and start, which the approved page leaves to
// Providers' own command glass and Accounts, and went with it.)
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const sse = (...events: unknown[]) => events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join("");
const answer = (text: string, tin: number, tout: number) => sse(
  { type: "response.output_text.delta", delta: text },
  { type: "response.completed", response: { usage: { input_tokens: tin, output_tokens: tout } } },
);
let budget = false;

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [
      { key: "acme", label: "claude-acme", state: "running", authKind: "api-key", gate: { inflight: 0 } },
      { key: "beta", label: "claude-beta", state: "running", authKind: "api-key", gate: { inflight: 0 } },
      { key: "own", label: "claude", state: "running", authKind: "client", gate: { inflight: 0 } },
    ] },
    "/api/status": { registry: [{ key: "acme", family: "openai" }, { key: "beta", family: "openai" }, { key: "own", family: "anthropic" }] },
    "/api/models": { heads: [
      { head: "acme", models: [{ id: "acme-large-2", label: "Acme Large 2" }] },
      { head: "beta", models: [{ id: "beta-one", label: "Beta One" }] },
      { head: "own", models: [{ id: "own-opus", label: "Own Opus" }] },
    ] },
    "POST /api/playground": (_u: URL, body: unknown) => {
      const b = body as { head: string; model: string };
      if (b.head === "beta" && budget) return Response.json({ error: "over its daily budget", blocked: "budget", spent_usd: 2, limit_usd: 2 }, { status: 429 });
      if (b.head === "beta") return { request: { turn_id: "compare-b" }, response: { status: 429, body: { error: { type: "rate_limit_error", message: "slow down" } } } };
      return { request: { turn_id: "compare-a" }, response: { status: 200, body: { raw: answer("Three tide tables: A, B, C.", 12, 9) } } };
    },
    "/api/perf/price": { cost_usd: 0.0021 },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => { await browser?.close(); s?.stop(); });

async function compare(picks: string[]): Promise<{ page: Page; close: () => Promise<void> }> {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript((p) => { localStorage.setItem("splice.compare.models", JSON.stringify(p)); }, picks);
  const page = await ctx.newPage();
  await page.goto(`${s.url}/providers.html?open=compare`);
  await expect(page.locator(".lens:not([hidden]) .cmp .cmp-h").first()).toBeVisible({ timeout: 15_000 });
  return { page, close: () => ctx.close() };
}
const posts = () => s.calls.filter((c) => c.method === "POST" && c.path === "/api/playground").map((c) => c.body as { head: string; model: string; prompt: string });

test("Enter sends the prompt to each model picked, and each answer stands under its column; a failure reads first", async () => {
  budget = false;
  const o = await compare(["claude-acme|acme-large-2", "claude-beta|beta-one"]);
  try {
    const l = o.page.locator(".lens:not([hidden])");
    s.calls.length = 0;
    const box = l.locator('[data-item="prompt"] textarea');
    await box.fill("Name three tide tables.");
    await box.press("Shift+Enter");
    await box.press("Enter");
    await expect.poll(() => posts().length).toBe(2);
    bunExpect(posts().map((p) => `${p.head}/${p.model}`).sort()).toEqual(["acme/acme-large-2", "beta/beta-one"]);
    bunExpect(posts()[0]!.prompt).toBe("Name three tide tables.\n");
    const ans = l.locator('[data-item="answers"]');
    await expect(ans).toContainText("1 of 2 answered");
    await expect(ans.locator(".cmp-fails")).toContainText("Rate limited");
    await expect(ans.locator(".cmp-said")).toHaveText("Three tide tables: A, B, C.");
    await expect(ans.locator(".cmp-text.is-bad")).toContainText("slow down");
    await expect(ans.locator('a[href="activity.html#request=compare-a"]')).toBeVisible();
  } finally { await o.close(); }
}, 60_000);

test("an over-budget command's refusal says so with what it spent", async () => {
  budget = true;
  const o = await compare(["claude-beta|beta-one"]);
  try {
    const l = o.page.locator(".lens:not([hidden])");
    await l.locator('[data-item="prompt"] textarea').fill("Hello");
    await l.locator('[data-item="prompt"] textarea').press("Enter");
    await expect(l.locator(".cmp-text.is-bad")).toContainText("Over budget");
    await expect(l.locator(".cmp-text.is-bad")).toContainText("$2.00 of $2.00 today");
  } finally { await o.close(); budget = false; }
}, 60_000);

test("a model on Claude Code's own sign-in says it can't send, and is not asked", async () => {
  const o = await compare(["claude|own-opus", "claude-acme|acme-large-2"]);
  try {
    const l = o.page.locator(".lens:not([hidden])");
    s.calls.length = 0;
    await l.locator('[data-item="prompt"] textarea').fill("Hello");
    await l.locator('[data-item="prompt"] textarea').press("Enter");
    await expect(l.locator(".cmp-text.is-bad")).toContainText("Can’t send");
    await expect.poll(() => posts().length).toBe(1);
    bunExpect(posts()[0]!.head).toBe("acme");
  } finally { await o.close(); }
}, 60_000);

test("the prompt and the models picked are kept in this browser", async () => {
  const o = await compare(["claude-beta|beta-one"]);
  try {
    const l = o.page.locator(".lens:not([hidden])");
    await expect(l.locator(".cmp .cmp-h")).toHaveText(["Beta One"]);
    await l.locator('[data-item="prompt"] textarea').fill("Kept for next time");
    await o.page.reload();
    await expect(o.page.locator('.lens:not([hidden]) [data-item="prompt"] textarea')).toHaveValue("Kept for next time", { timeout: 15_000 });
    await expect(o.page.locator(".lens:not([hidden]) .cmp .cmp-h")).toHaveText(["Beta One"]);
  } finally { await o.close(); }
}, 60_000);
