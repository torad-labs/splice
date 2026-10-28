// A request splice ended before any attempt asked no provider, so its pane has no token tiles, neither "Not reported"
// nor zeros, and Attempts 0 stands alone (fin, Oct 12). A request that was sent and got no counts back draws its Tokens
// as one dash on a dashed track, "Not reported" under the pointer (Requests' goal, Oct 28: a request with no counts is a
// dash, never a word per figure). Through the real Requests page against a stub daemon.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const turn = (id: string, extra: Record<string, unknown>) => ({
  turn: id, turn_id: id, ts: now - 30_000, model: "m", session: "0c3b07d2", retries: 0, ...extra,
});
const rows = [
  turn("t-unsent", { outcome: "error:budget", attempts: 0, in: 0, write: 0, out: 0, total: 0 }),
  turn("t-silent", { outcome: "ok", attempts: 1, total: 2_000 }),
];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/perf/turns") {
      return Response.json({ heads: [{ key: "alpha", label: "alpha", count: 2, returned: 2, truncated: false, rows,
        usage: { models: [], accounts: [], sessions: [] } }] });
    }
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

/** The pane's figure tiles, each as its label, its value as drawn, and the words under the pointer on a dash. */
async function tiles(id: string): Promise<{ label: string; value: string; dashes: (string | null)[]; track: boolean }[]> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    await page.locator(`article[data-open="alpha:${id}"]`).click({ timeout: 15_000 });
    await page.locator(".pane .figs").waitFor({ timeout: 5_000 });
    return await page.locator(".pane .figs .fig").evaluateAll((els) => els.map((e) => ({
      label: e.querySelector(".label")?.textContent?.trim() ?? "",
      value: e.querySelector(".v")?.textContent?.trim() ?? "",
      dashes: [...e.querySelectorAll(".nr")].map((d) => d.getAttribute("title")),
      track: !!e.querySelector(".fbar.unread"),
    })));
  } finally {
    await page.close();
  }
}

test("a request that never left splice shows no token tiles, and Attempts 0 stands", async () => {
  const shown = await tiles("t-unsent");
  expect(shown.some((t) => /^Tokens|^Cache write/.test(t.label))).toBe(false);
  expect(shown.find((t) => t.label === "Attempts")?.value).toBe("0");
  expect(shown.map((t) => `${t.label} ${t.value}`).join(" ")).not.toContain("Not reported");
}, 30_000);

test("a request that was sent and got no counts back draws its Tokens as one dash on a dashed track, Not reported under the pointer", async () => {
  const tokens = (await tiles("t-silent")).find((t) => t.label === "Tokens");
  expect(tokens?.value).toBe("—");
  expect(tokens?.dashes).toEqual(["Not reported"]);
  expect(tokens?.track).toBe(true);
}, 30_000);
