// nd-sessions-open: pressing a session on the board opens its conversation in place: what it said, read live from its
// transcript; the live band with its own lane and its teammates'; the composer pointed at it, sending what is typed
// to that session; and Back returns to the board.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { browser, daemon, MIN, NOW, open, WIDE, world, type Daemon } from "./lib/deck.ts";

const transcript = {
  messages: [
    { index: 0, role: "user", text: "Fix the tide table parser.", ts: NOW - 9 * MIN },
    { index: 1, role: "assistant", text: "The tide table parser now reads the new column.", ts: NOW - 5 * MIN },
  ],
  earlier: null, moves: [], unmoved: [], unparseable_lines: 0,
};
let b: Browser;
let d: Daemon;
beforeAll(async () => {
  b = await browser();
  d = daemon(world({
    "/api/sessions/aaaa1111/transcript": transcript,
    "/api/sessions/aaaa1111/say": { ok: true },
  }));
}, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("a session opens into its conversation, its team's lanes and the composer, and Back returns to the board", async () => {
  for (const size of [undefined, WIDE]) {
    const o = await open(b, d, "sessions.html", size);
    try {
      await o.page.locator("#board .srow", { hasText: "api-fixer" }).first().click();
      await expect(o.page.locator("#conv")).toBeVisible({ timeout: 10_000 });
      await expect(o.page.locator("#board")).toBeHidden();
      await expect(o.page.locator("#thread")).toContainText("The tide table parser now reads the new column.");
      await expect(o.page.locator("#bdLanes")).toContainText("api-fixer");
      await expect(o.page.locator("#bdLanes")).toContainText("map-builder");
      await expect(o.page.locator(".comp-context")).toContainText("api-fixer");
      await o.page.locator("#back").click();
      await expect(o.page.locator("#board")).toBeVisible();
      await expect(o.page.locator("#conv")).toBeHidden();
      expect(o.errors).toEqual([]);
    } finally {
      await o.close();
    }
  }
}, 60_000);

test("what is typed in the composer on an open session is sent to that session", async () => {
  const o = await open(b, d, "sessions.html");
  try {
    await o.page.locator("#board .srow", { hasText: "api-fixer" }).first().click();
    await expect(o.page.locator("#conv")).toBeVisible({ timeout: 10_000 });
    await o.page.locator("#compWrap textarea").click();
    await o.page.keyboard.type("Run the parser tests next");
    await o.page.keyboard.press("Enter");
    await expect.poll(() => d.seen.filter((s) => s.method === "POST" && s.path === "/api/sessions/aaaa1111/say")
      .map((s) => JSON.stringify(s.body)).join(" "), { timeout: 10_000 }).toContain("Run the parser tests next");
  } finally {
    await o.close();
  }
}, 60_000);
