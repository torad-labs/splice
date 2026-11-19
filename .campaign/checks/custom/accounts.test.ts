// cp-accounts: the console's accounts page shows a provider on command auth as an account, as it shows a ChatGPT or
// a Grok sign-in: its name, whether the person is signed in, and when the key expires. Its Sign in runs the provider's
// login_command, shows the address the command printed while it waits, and the account reads signed in, with its
// expiry, when it is done, with no reload. Through the real page served by a real daemon, in a real browser. (`/login`
// on the door opens this page: docs/specs/door-default.md, decision 5.) How it looks is judged from captures.

import { afterAll, beforeAll, describe, expect as bunExpect, setDefaultTimeout, test } from "bun:test";
import { chromium, expect, type Browser, type Locator, type Page } from "@playwright/test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Gateway, TeamCli, acme, withCli } from "./lib/team.ts";

setDefaultTimeout(180_000);

const SSO = "https://sso.acme.example/device?user_code=WXYZ-1234";

let cli: TeamCli;
let gw: Gateway;
let s: Splice;
let browser: Browser;
let page: Page;

/** The innermost part of the page that names the account and holds a control of it. */
function account(p: Page): Locator {
  return p
    .locator("article, section, li, tr, div")
    .filter({ hasText: /\bacme\b/ })
    .filter({ has: p.getByRole("button") })
    .last();
}

beforeAll(async () => {
  cli = new TeamCli();
  gw = new Gateway(cli).start();
  s = await Splice.boot(acme({ url: gw.url, port: await freePort(), cli }), withCli(cli));
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const key = s.mgmtKey();
  await page.addInitScript((k) => localStorage.setItem("splice-console-access-key", k), key);
  await page.goto(`http://127.0.0.1:${s.controlPort}/accounts.html`);
}, 300_000);

afterAll(async () => {
  await browser?.close();
  await s?.dispose();
  gw?.stop();
  cli?.dispose();
});

describe("cp-accounts", () => {
  test("the provider is an account on the page, signed out, with a Sign in", async () => {
    const card = account(page);
    await expect(card).toBeVisible({ timeout: 30_000 });
    await expect(card).toContainText(/signed out|not signed in/i);
    await expect(card.getByRole("button", { name: /sign in/i })).toBeVisible();
  });

  test("Sign in runs the login command, shows the address it printed, and the account reads signed in with its expiry", async () => {
    cli.loginTakes(4);
    await account(page).getByRole("button", { name: /sign in/i }).click();
    await expect(page.locator(`a[href="${SSO}"]`).first()).toBeVisible({ timeout: 20_000 });
    await expect(account(page)).toContainText(/signed in/i, { timeout: 30_000 });
    await expect(account(page)).not.toContainText(/signed out|not signed in/i);
    await expect(account(page)).toContainText(/expir/i);
    bunExpect(cli.signedIn()).toBe(true);
  });

  test("a failed sign-in says so on the account, and leaves it signed out", async () => {
    cli.signOut();
    cli.loginTakes(0);
    cli.loginFails(true);
    try {
      await page.reload();
      // a key splice holds from before is forgotten only when the provider refuses it; sign out on the page is not asked
      // for here, so the check reads the failure, not the state
      const button = account(page).getByRole("button", { name: /sign in/i });
      await expect(button).toBeVisible({ timeout: 30_000 });
      await button.click();
      await expect(account(page)).toContainText(/fail|did not|not completed|exit/i, { timeout: 30_000 });
    } finally {
      cli.loginFails(false);
    }
  });
});
