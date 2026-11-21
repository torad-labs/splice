// nd-compare: what the old Compare page did lives in Providers' Compare models glass: the models picked side by side,
// and one prompt sent to each of them through splice (POST /api/playground, as Compare sent it), their answers read side
// by side. The Compare page and its files are gone.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, lens, open, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world({ "/api/playground": { raw: "" } })); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

test("Compare models sends one prompt to the model picked, through splice", async () => {
  const o = await open(b, d, "providers.html");
  try {
    await o.page.locator('[data-part="compare"]').click();
    const l = await lens(o.page);
    await expect(l.locator(".cmp .cmp-h").first()).toBeVisible({ timeout: 10_000 });
    if (!(await l.locator(".cmp .cmp-h", { hasText: "Acme Large 2" }).count())) {
      await l.locator('[data-item="find"] .lr-in').fill("acme");
      await l.locator(".cmp-find button", { hasText: "Acme Large 2" }).first().click();
    }
    await expect(l.locator(".cmp .cmp-h", { hasText: "Acme Large 2" })).toHaveCount(1);
    const prompt = l.locator('[data-item="prompt"] textarea');
    await prompt.fill("Name three tide tables.");
    await prompt.press("Enter");
    await expect.poll(() => d.seen.filter((s) => s.method === "POST" && s.path === "/api/playground").map((s) => JSON.stringify(s.body)).join(" "),
      { timeout: 10_000 }).toMatch(/"head":"acme".*"prompt":"Name three tide tables\."|"prompt":"Name three tide tables\.".*"head":"acme"/);
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
  }
}, 60_000);

test("the Compare page and its own files are gone", () => {
  for (const f of ["compare.html", "compare.js", "compare.css"]) expect(existsSync(join(CONSOLE, f)), f).toBe(false);
});
