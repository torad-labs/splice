// nd-models: what the old Models page showed and did lives on Providers, in each command's glass (its context window,
// its system prompt, its logs) and in Add provider, and the Models page and its files are gone.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, lens, open, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("a command's glass on Providers holds its context window, system prompt and logs", async () => {
  const o = await open(b, d, "providers.html");
  try {
    await o.page.locator('[data-key="claude-acme"]').click();
    const l = await lens(o.page);
    for (const row of ["Context window", "System prompt", "Logs"]) await expect(l.getByText(row, { exact: true }).first()).toBeVisible();
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("the Models page and its own files are gone", () => {
  for (const f of ["models.html", "models.js", "models.css"]) expect(existsSync(join(CONSOLE, f)), f).toBe(false);
});
