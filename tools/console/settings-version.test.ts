// Settings' Version pane: a card for every version there is and none for a version there is not. A daemon that has
// never checked for a release says so in plain words and still shows one number; a daemon with a release to go back to
// shows the second card and the way back. Through the real page against a stub daemon.
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
// what the daemon on this machine answers: it runs 0.4.0, no check has ever succeeded, and the previous release is a
// measured absence (null with no reason), which is exactly the shape that used to draw two cards holding no number
const NEVER_CHECKED = {
  installed: "0.4.0",
  latest: null,
  latest_basis: "unavailable",
  latest_unavailable_reason:
    "no upgrade check has succeeded on this daemon; splice learns the latest version by fetching and " +
    "verifying a release, which no poll may do",
  rollback_target: null,
  rollback_basis: "measured",
  rollback_unavailable_reason: null,
  checked_at_epoch_millis: null,
};
let upgrade: Record<string, unknown> = NEVER_CHECKED;
const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "alpha", label: "alpha" }] },
  "/api/models": { heads: [] },
  "/api/config": { layers: {} },
  "/api/perf/turns": { heads: [] },
  "/api/history": {},
};
const server = Bun.serve({
  port: Number(process.env.SETTINGS_VERSION_PORT ?? 0),
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/upgrade") return Response.json(upgrade);
    if (path in answers) return Response.json(answers[path]);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "settings.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
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

/** The Version pane, open, with the daemon answering [body] for /api/upgrade. */
async function version(body: Record<string, unknown>): Promise<Page> {
  upgrade = body;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Version").first().click();
  await expect(page.locator(".vcard").first()).toBeVisible({ timeout: 15_000 });
  return page;
}

test("a daemon that has never checked shows its own version and says so in plain words, with no empty second card", async () => {
  const page = await version(NEVER_CHECKED);
  try {
    const pane = page.locator(".pane");
    await expect(page.locator(".vcard")).toHaveCount(1);
    await expect(page.locator(".vcard .v")).toHaveText("0.4.0");
    await expect(pane).toContainText("Not checked yet");
    await expect(pane.getByRole("link", { name: /See releases/ })).toBeVisible();
    // none of the three words the empty cards used to carry, and none of the daemon's own sentence
    for (const gone of ["Newest", "None", "fetching and verifying", "no upgrade check"]) {
      await expect(pane).not.toContainText(gone);
    }
  } finally {
    await page.close();
  }
}, 30_000);

test("a machine with an earlier release on it shows that version too, with the way back", async () => {
  const page = await version({ ...NEVER_CHECKED, rollback_target: "0.3.2" });
  try {
    await expect(page.locator(".vcard")).toHaveCount(2);
    const previous = page.locator(".vcard", { hasText: "Previous" });
    await expect(previous.locator(".v")).toHaveText("0.3.2");
    await expect(previous.getByRole("button", { name: "Go back to 0.3.2" })).toBeVisible();
  } finally {
    await page.close();
  }
}, 30_000);

test("a release the daemon could not read is a card with its reason, not a silent absence", async () => {
  const page = await version({
    ...NEVER_CHECKED,
    rollback_basis: "unavailable",
    rollback_unavailable_reason: "the release layout could not be read: permission denied",
  });
  try {
    const previous = page.locator(".vcard", { hasText: "Previous" });
    await expect(previous).toContainText("Not read");
    await expect(previous).toContainText("permission denied");
  } finally {
    await page.close();
  }
}, 30_000);
