// What the comparison marks and how the prompt reads, as he sees them on a wide screen. A column's best is the figure
// as the table writes it: two windows that both read 1M are starred alike and a 500K beside them is not. After the
// answers, an edit that only adds words keys only "Added" beside the prompt. His plan reads from its box's top, and a
// plan longer than the box scrolls inside it instead of stretching the first row and leaving the models plate half
// empty beside it. Through the real page against a stub daemon, at a width where the prompt and the models plate share
// the first row.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
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

const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/heads") return Response.json({ heads: ["one", "two", "three"].map((key) => ({ key, label: key })) });
    if (path === "/api/models") {
      return Response.json({ heads: [
        { head: "one", provider: "anthropic", models: [{ id: "wide-a", label: "Wide A", context_window: 1_000_000, rates: { input: 3, output: 15 } }] },
        { head: "two", provider: "anthropic", models: [{ id: "wide-b", label: "Wide B", context_window: 1_048_576, rates: { input: 3, output: 15 } }] },
        { head: "three", provider: "anthropic", models: [{ id: "half-c", label: "Half C", context_window: 500_000, rates: { input: 0.15, output: 0.6 } }] },
      ] });
    }
    if (path === "/api/auth") return Response.json(Object.fromEntries(["one", "two", "three"].map((h) => [h, { present: true, env_var: `${h.toUpperCase()}_KEY` }])));
    if (path === "/api/perf/price") return Response.json({ cost_usd: null, cost_reason: "plan", plan_value_usd: 0.001 });
    if (path === "/api/playground" && req.method === "POST") {
      const sent = (await req.json()) as { model: string };
      return Response.json({
        request: { url: "https://api.example.test/v1/messages", headers: { "content-type": "application/json" }, body: { model: sent.model, stream: true } },
        response: { status: 200, body: { raw: stream("An answer") } },
      });
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
  await browser.close();
  server.stop(true);
});

// the wide screen: the prompt and the models plate share the first row
const open = async (): Promise<Page> => {
  const page = await browser.newPage({ viewport: { width: 2548, height: 1240 } });
  await page.goto(`http://127.0.0.1:${server.port}/compare.html`);
  for (const id of ["wide-a", "wide-b", "half-c"]) await page.locator(`button.pg-mrow[data-model="${id}"]`).click();
  return page;
};
const row = (page: Page, name: string) => page.locator(".pg-tr", { has: page.locator(".pg-rname", { hasText: name }) });

test("a column's best is starred as the table writes it: two windows that both read 1M alike, the 500K not", async () => {
  const page = await open();
  try {
    for (const name of ["Wide A", "Wide B"]) {
      await expect(row(page, name).locator('[data-f="win"]')).toHaveText("1M");
      await expect(row(page, name).locator('[data-f="win"] .pg-star')).toHaveCount(1);
    }
    await expect(row(page, "Half C").locator('[data-f="win"] .pg-star')).toHaveCount(0);
    // the cheapest price is the one starred, and the two that read alike above it are not
    await expect(row(page, "Half C").locator('[data-f="pin"] .pg-star')).toHaveCount(1);
    await expect(row(page, "Wide A").locator('[data-f="pin"] .pg-star')).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 60_000);

test("after answers, an edit that only adds words keys only Added beside the prompt", async () => {
  const page = await open();
  try {
    await page.locator("#prompt").fill("say hi");
    await page.locator("button.pg-send").click();
    await expect(page.locator("article.pgp", { hasText: "An answer" })).toHaveCount(3, { timeout: 20_000 });
    await page.locator("[data-act=write]").click();
    await page.locator("#prompt").fill("say hi and keep it short");
    await page.locator("#prompt").press("Escape");
    await expect(page.locator(".pg-md-out ins")).toHaveText("and keep it short");
    await expect(page.locator(".pg-dleg")).toHaveText("Added");
  } finally {
    await page.close();
  }
}, 60_000);

test("the plan reads from its box's top, and a long plan scrolls in its box beside the models plate", async () => {
  const page = await open();
  try {
    await page.locator("#prompt").fill("Compare the three");
    await page.locator("#prompt").press("Escape");
    const box = (await page.locator(".pg-md").boundingBox())!, text = (await page.locator(".pg-md-out p").first().boundingBox())!;
    expect(text.y - box.y).toBeLessThan(40); // the plan's first line sits under the box's top edge, not in its middle
    await page.locator("[data-act=write]").click();
    await page.locator("#prompt").fill(Array.from({ length: 80 }, (_, i) => `- step ${i + 1} of the plan`).join("\n"));
    await page.locator("#prompt").press("Escape");
    const out = page.locator(".pg-md-out");
    expect(await out.evaluate((el) => el.scrollHeight > el.clientHeight + 100)).toBe(true); // the rest of the plan is in the box, below
    const plate = (await page.locator("#cmp").boundingBox())!, table = (await page.locator("#cmp .pg-table").boundingBox())!;
    expect(plate.y + plate.height - (table.y + table.height)).toBeLessThan(120); // no empty block under the table
    await out.evaluate((el) => { el.scrollTop = el.scrollHeight; });
    await expect(page.locator(".pg-md-out li").last()).toBeInViewport();
  } finally {
    await page.close();
  }
}, 60_000);
