// The manager sheet's long messages open and fold in place (shared-more-pill-unfold, finish pass): "+N more lines" opens the
// rest of a message the sheet clamps, and the pill that stood there becomes "Fewer lines", which folds it back to the
// lines it showed. Through the real page against a stub daemon whose manager has one long message in its conversation.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const manager = {
  folder: "/home/someone/.config/splice/manager", consented: true, state: "live", head: "claudex",
  session_id: "m-1", status: "idle", line: { model: "GPT-5.6 Codex", model_id: "gpt-5.6-codex", effort: "high", at: Date.now() },
  heads: [{ key: "claudex", label: "claudex", ready: true }],
};
const long = Array.from({ length: 24 }, (_, i) => `Line ${i + 1} of a long reply the manager wrote.`).join("\n\n");
const answers: Record<string, unknown> = {
  "/api/manager": manager,
  "/api/models": { heads: [{ head: "claudex", models: [] }] },
  "/api/sessions/m-1/transcript": { messages: [{ role: "assistant", text: long, ts: Date.now() - 60_000 }], earlier: null, moves: [] },
};
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "accounts.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("a long message opens with its more-lines pill and folds back with Fewer lines", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/accounts.html`);
    await page.locator(".cmp-field").click({ timeout: 15_000 });
    const body = page.locator(".cmp-md").first();
    const pill = page.locator(".cmp-md + .morepill");
    await expect(pill).toContainText("more lines", { timeout: 10_000 });
    const folded = (await body.boundingBox())!.height;
    await pill.click();
    await expect.poll(async () => (await body.boundingBox())!.height).toBeGreaterThan(folded * 2);
    // the pill that opened it now offers the way back
    const fewer = page.locator(".cmp-md + .morepill");
    await expect(fewer).toContainText("Fewer lines");
    await fewer.click();
    await expect.poll(async () => (await body.boundingBox())!.height).toBeLessThan(folded + 4);
    await expect(page.locator(".cmp-md + .morepill")).toContainText("more lines");
  } finally {
    await page.close();
  }
}, 40_000);
