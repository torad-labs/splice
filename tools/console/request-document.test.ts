import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const sent = {
  messages: Array.from({ length: 120 }, (_, i) => ({ role: "user", content: `message-${i}` })),
  payload: "start-marker-" + "x".repeat(100_000) + "-end-marker",
};
const returned = { output: "returned-document-marker" };
const row = { turn: "document-turn", turn_id: "document-turn", ts: now, outcome: "ok", model: "m", total: 1000, attempts: 0 };
const outline = { kind: "turn", ts: now, client: { headers: {}, body_deferred: true }, answer: { status: 200, body_deferred: true } };

function documentPage(url: URL) {
  let value: unknown = url.searchParams.get("section") === "answer" ? returned : sent;
  for (const part of (url.searchParams.get("path") || "").split("/").slice(1)) {
    value = (value as Record<string, unknown>)[part.replace(/~1/g, "/").replace(/~0/g, "~")];
  }
  const offset = Number(url.searchParams.get("offset")) || 0;
  if (typeof value === "string") return { type: "string", value: value.slice(offset, offset + 16_384), next: offset + 16_384 < value.length ? offset + 16_384 : null };
  const entries = Object.entries(value as object), keys = entries.slice(offset, offset + 25);
  return {
    type: Array.isArray(value) ? "array" : "object",
    children: keys.map(([key, child]) => typeof child === "object"
      ? { key, type: Array.isArray(child) ? "array" : "object" }
      : { key, type: typeof child, value: typeof child === "string" ? child.slice(0, 160) : child, complete: typeof child !== "string" || child.length <= 160 }),
    next: offset + 25 < entries.length ? offset + 25 : null,
  };
}

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "alpha" }] });
    if (path === "/api/heads/alpha/trace/document") return Response.json(documentPage(url));
    if (path === "/api/heads/alpha/trace") return Response.json({ records: [url.searchParams.get("view") === "outline" ? outline
      : { ...outline, client: { body: JSON.stringify(sent) }, answer: { status: 200, body: JSON.stringify(returned) } }] });
    if (path === "/api/perf/turns") return Response.json({ heads: [{ key: "alpha", count: 1, rows: [row], usage: { models: [], accounts: [], sessions: [] } }] });
    if (path === "/api/heads/alpha/capture") return Response.json({ enabled: true, retention_days: 7 });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => { browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] }); }, 20_000);
afterAll(async () => { await browser.close(); server.stop(true); });

test("request branches and tabs reveal their own document without loading the unseen contents", async () => {
  const page = await browser.newPage({ viewport: { width: 3828, height: 1889 }, colorScheme: "dark" });
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html#alpha:document-turn`);
    const pane = page.locator(".rqpane");
    await expect(pane.locator('.tree.root > li > .key').filter({ hasText: /^messages$/ })).toBeVisible();
    await expect(pane).not.toContainText("message-119");
    await expect(pane).not.toContainText("returned-document-marker");
    const messages = pane.locator(".tree.root > li").filter({ has: page.locator(':scope > .key', { hasText: /^messages$/ }) });
    await messages.locator(":scope > .node").click();
    await expect(messages.locator(".idx").first()).toHaveText("0");
    await messages.locator("li").filter({ has: page.locator(':scope > .idx', { hasText: /^0$/ }) }).locator(":scope > .node").click();
    await expect(messages).toContainText("message-0");
    await expect(messages).not.toContainText("message-119");
    await pane.getByRole("tab", { name: "Returned", exact: true }).click();
    await expect(pane).toContainText("returned-document-marker");
    expect(errors).toEqual([]);
    await page.screenshot({ path: "/tmp/splice-request-document-dark.png", fullPage: true });
  } finally { await page.close(); }
}, 30_000);

test("large request text keeps a bounded window and Download still contains the whole original document", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html#alpha:document-turn`);
    const pane = page.locator(".rqpane");
    const payload = pane.locator(".tree.root > li").filter({ has: page.locator(':scope > .key', { hasText: /^payload$/ }) });
    await payload.locator(":scope > .node").click();
    await expect(payload.locator(".doc-text")).toContainText("start-marker-");
    await expect(payload).not.toContainText("-end-marker");
    await expect.poll(async () => {
      if ((await payload.locator(".doc-text").innerText()).includes("-end-marker")) return true;
      await payload.evaluate((el) => [...el.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === "Load more")?.click());
      return false;
    }, { timeout: 10_000 }).toBe(true);
    await expect(payload.locator(".doc-text")).toContainText("-end-marker");
    expect((await payload.locator(".doc-text").innerText()).length).toBeLessThanOrEqual(65_536);
    await expect(payload.getByRole("button", { name: "Load earlier", exact: true })).toBeVisible();
    const downloading = page.waitForEvent("download");
    await pane.getByRole("button", { name: "Download", exact: true }).click();
    const download = await downloading, file = await download.path();
    expect(JSON.parse(readFileSync(file!, "utf8")).records[0].client.body).toBe(JSON.stringify(sent));
  } finally { await page.close(); }
}, 30_000);
