// Every page marks its own nav item whatever address it was opened at (a query, a fragment, or a filter in the address):
// Night Deck pages draw the nav from deck.js's one list, and only the page's own item reads aria-current="page". Through
// the real pages against a stub daemon that answers every read empty. (Rewritten from the old pages' nav-current.)
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

let s: Stub;
let browser: Browser;
beforeAll(async () => { s = stub({}); browser = await launch(); }, 30_000);
afterAll(async () => { await browser?.close(); s?.stop(); });

const OPENED: [string, string][] = [
  ["sessions.html#s-made-up", "Sessions"], ["accounts.html?x=1", "Accounts"], ["teams.html", "Teams"],
  ["activity.html?project=-&win=30d", "Activity"], ["activity.html#request=t-1", "Activity"],
  ["providers.html?cmd=claude-acme", "Providers"], ["providers.html?open=compare", "Providers"], ["server.html", "Server"], ["settings.html#gates", "Settings"],
];

test("each page marks its own nav item and only that one, whatever its address carries", async () => {
  for (const [address, name] of OPENED) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    try {
      await page.goto(`${s.url}/${address}`);
      const current = page.locator('.nav [aria-current="page"]');
      await expect(current).toHaveCount(1, { timeout: 10_000 });
      await expect(current).toHaveText(name);
    } finally {
      await page.close();
    }
  }
}, 60_000);
