// Settings' Version pane: a card for every version there is and none for a version there is not, and one act that
// reads the network — his tap. A daemon that has never checked says so and offers the act; a tap that reads takes the
// version it found and opens the releases door with it; a tap that reads nothing, or is refused, is said in the
// console's own words. Through the real page against a stub daemon.
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
// what POST /api/upgrade/check answers, and every tap it received: the page must not read on its own.
let checkReply: { status: number; body: Record<string, unknown> } = { status: 200, body: {} };
const posts: string[] = [];
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
    if (path === "/api/upgrade/check") {
      posts.push(req.method);
      return Response.json(checkReply.body, { status: checkReply.status });
    }
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

/** The Version pane, open, with the daemon answering [body] for /api/upgrade and [reply] for a tap on Check. */
async function version(body: Record<string, unknown>, reply = { status: 200, body: {} as Record<string, unknown> }): Promise<Page> {
  upgrade = body;
  checkReply = reply;
  posts.length = 0;
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Version").first().click();
  await expect(page.locator(".vcard").first()).toBeVisible({ timeout: 15_000 });
  return page;
}

// The card is a state and an act, never a sentence (Marlin's ruling on the Settings words, Oct 12): "Not checked"
// beside the running version, with the one act that would read. The releases page is not a door yet, because until a
// check has found a newer version there is no news to go and read.
test("a daemon that has never checked shows its own version, the state and the act, with no empty second card", async () => {
  const page = await version(NEVER_CHECKED);
  try {
    const pane = page.locator(".pane");
    await expect(page.locator(".vcard")).toHaveCount(1);
    await expect(page.locator(".vcard .v")).toHaveText("0.4.0");
    await expect(page.locator(".vcard .state")).toHaveText("Not checked");
    await expect(pane.getByRole("button", { name: "Check" })).toBeEnabled();
    await expect(pane.getByRole("link", { name: /Releases/ })).toHaveCount(0);
    // no empty second card, no sentence, and none of the daemon's own words about fetching and verifying
    for (const gone of ["Newest", "None", "fetching and verifying", "no upgrade check", "See releases for"]) {
      await expect(pane).not.toContainText(gone);
    }
    expect(posts).toEqual([]); // opening the page reads nothing: only his tap does
  } finally {
    await page.close();
  }
}, 30_000);

// ONE TAP, ONE READ: a version he does not have is a version there is, so it takes a card of its own rather than
// standing as a second bare number under "Running", and the releases page is a door beside it.
test("his tap gives the version the check found its own card, with the releases door on it", async () => {
  const page = await version(NEVER_CHECKED, {
    status: 200,
    body: { state: "read", latest: "0.4.1", read_at_epoch_millis: Date.now(), reason: null },
  });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Check" }).click();
    await expect(page.locator(".vcard")).toHaveCount(2, { timeout: 10_000 });
    const newest = page.locator(".vcard", { hasText: "Newest" });
    await expect(newest.locator(".v")).toHaveText("0.4.1");
    await expect(newest.getByRole("link", { name: /Releases/ })).toBeVisible();
    await expect(page.locator(".vcard", { hasText: "Running" }).locator(".v")).toHaveText("0.4.0");
    await expect(pane).toContainText("Checked");
    expect(posts).toEqual(["POST"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a check that finds him already on the newest says so, and opens no door", async () => {
  const page = await version(NEVER_CHECKED, {
    status: 200,
    body: { state: "read", latest: "0.4.0", read_at_epoch_millis: Date.now(), reason: null },
  });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Check" }).click();
    await expect(page.locator(".vcard .state")).toHaveText("Newest", { timeout: 10_000 });
    await expect(pane.getByRole("link", { name: /Releases/ })).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

// A read that answered nothing never leaves an older version standing as the newest, and never repeats the daemon's
// sentence: the card says "Not read", the word every other unread figure on Settings uses.
test("a read that answered nothing says Not read, in the console's words and not the daemon's", async () => {
  const page = await version(NEVER_CHECKED, {
    status: 200,
    body: { state: "not_read", latest: null, read_at_epoch_millis: Date.now(), reason: "github.com could not be reached" },
  });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Check" }).click();
    await expect(page.locator(".vcard .state")).toHaveText("Not read", { timeout: 10_000 });
    await expect(pane).not.toContainText("could not be reached");
  } finally {
    await page.close();
  }
}, 30_000);

test("a tap refused because one is already running is said beside the act", async () => {
  const page = await version(NEVER_CHECKED, { status: 409, body: { error: "a check is already running" } });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Check" }).click();
    await expect(pane.locator(".vcard .said")).toHaveText("Already checking", { timeout: 10_000 });
    await expect(pane).not.toContainText("already running"); // the daemon's sentence stays in the log
    await expect(page.locator(".vcard .state")).toHaveText("Not checked"); // and nothing on the card moved
  } finally {
    await page.close();
  }
}, 30_000);

test("a tap refused because he checked a moment ago is said beside the act", async () => {
  const page = await version(NEVER_CHECKED, { status: 429, body: { error: "checked 4s ago" } });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Check" }).click();
    await expect(pane.locator(".vcard .said")).toHaveText("Checked a moment ago", { timeout: 10_000 });
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
