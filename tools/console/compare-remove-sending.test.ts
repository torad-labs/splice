// A model whose answer is still out can be taken off the comparison: with two models asked, he drops the slow one
// mid-send, its pane goes at once, Send comes back for the one left, and the slow answer is dropped when it lands
// instead of reappearing. Through the real page against a stub daemon that answers one model at once and holds the other.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const stream = (text: string) => [
  `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: 12 } } })}`,
  `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text } })}`,
  `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: 5 } })}`,
  `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}`,
].join("\n\n");
const reply = (model: string, text: string) => ({
  request: { url: "https://api.example.test/v1/messages", headers: { "content-type": "application/json" }, body: { model, stream: true } },
  response: { status: 200, body: { raw: stream(text) } },
});

let release: () => void = () => {};
const held = new Promise<void>((r) => { release = r; });
let asked: () => void = () => {};
const slowAsked = new Promise<void>((r) => { asked = r; });
let landed: () => void = () => {};
const slowLanded = new Promise<void>((r) => { landed = r; });

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "quick", label: "quick" }, { key: "steady", label: "steady" }] });
    if (path === "/api/models") {
      return Response.json({ heads: [
        { head: "quick", provider: "anthropic", models: [{ id: "fast-m", label: "Fast model" }] },
        { head: "steady", provider: "anthropic", models: [{ id: "slow-m", label: "Slow model" }] },
      ] });
    }
    if (path === "/api/auth") return Response.json({ quick: { present: true, env_var: "QUICK_KEY" }, steady: { present: true, env_var: "STEADY_KEY" } });
    if (path === "/api/perf/price") return Response.json({ cost_usd: null, cost_reason: "plan", plan_value_usd: 0.001 });
    if (path === "/api/playground" && req.method === "POST") {
      const sent = (await req.json()) as { model: string };
      if (sent.model === "slow-m") {
        asked();
        await held;
        landed();
        return Response.json(reply(sent.model, "The slow answer arrived late"));
      }
      return Response.json(reply(sent.model, "The quick answer is here"));
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "compare.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 20_000);
afterAll(async () => {
  release();
  await browser.close();
  server.stop(true);
});

test("a model still answering can be removed: its pane goes, Send comes back, and its late answer never shows", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/compare.html`);
    await page.locator("[data-act=add]").click();
    await page.locator('button.pg-mrow[data-model="fast-m"]').click();
    await page.locator('button.pg-mrow[data-model="slow-m"]').click();
    await page.locator("#prompt").fill("say hi");
    await page.locator("button.pg-send").click();
    await expect(page.locator("article.pgp", { hasText: "The quick answer is here" })).toHaveCount(1, { timeout: 20_000 });
    await slowAsked;
    const slow = page.locator('article.pgp[aria-label="Slow model"]');
    await expect(slow).toHaveCount(1);
    await slow.getByRole("button", { name: "Remove Slow model" }).click({ timeout: 5_000 });
    await expect(slow).toHaveCount(0);
    await expect(page.locator("button.pg-send")).toBeEnabled();
    release();
    await slowLanded;
    await page.waitForTimeout(600);
    await expect(page.locator("body")).not.toContainText("The slow answer arrived late");
    await expect(page.locator("article.pgp")).toHaveCount(1);
  } finally {
    await page.close();
  }
}, 60_000);
