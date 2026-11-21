// nd-settings-system: the system parts of the Settings machine open their glass with their own controls, in the
// names Settings uses: MCP servers, Storage's retention, and Splice itself with its version and its restarts.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { browser, daemon, lens, open, WIDE, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("MCP servers, Storage and Splice open their own controls", async () => {
  for (const size of [undefined, WIDE]) {
    const o = await open(b, d, "settings.html", size);
    try {
      const parts: [string, string[]][] = [
        ["mcp", ["Server instances", "Stop idle servers after"]],
        ["storage", ["Usage history", "Full prompts", "Conversation backups"]],
        ["splice", ["9.8.7", "Restart now", "Restart when idle"]],
      ];
      for (const [part, words] of parts) {
        await o.page.locator(`[data-part="${part}"]`).click();
        const l = await lens(o.page);
        for (const w of words) await expect(l).toContainText(w);
        await o.page.keyboard.press("Escape");
        await expect(o.page.locator(".lens:not([hidden])")).toHaveCount(0, { timeout: 5_000 });
      }
      expect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }
}, 60_000);
