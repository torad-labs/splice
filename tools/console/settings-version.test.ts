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
// what POST /api/upgrade was given, and what POST /api/daemon/restart answers and was asked.
const started: Record<string, unknown>[] = [];
const restarts: string[] = [];
let restartReply: { status: number; body: Record<string, unknown> } = { status: 202, body: { status: "draining" } };
let restartPhase: Record<string, unknown> = { status: "idle" };
// the run the daemon reports, when a test sets one: its state and the lines it printed so far
let runNow: { state: string; output: string[] } | null = null;
// the requests in flight on alpha, in GET /api/heads/{head}/turns/live's shape, and the sessions they belong to
let live: Record<string, unknown>[] = [];
const turn = (session: string) => ({ id: `t-${session}`, session, model: "claude-opus-5-5", compact: false, age_ms: 42_000, idle_ms: 900,
  seen_output: true, resumes: 0, retries: 0, will_resume: false, stopped: false });
const sessions = { sessions: [
  { session_id: "114e6cb0-9c3a-4d26-b259-f2b7f24ab56c", name: "changelog-pass", status: "busy" },
  { session_id: "80e8a500-0a41-4f21-b707-3d7009e22904", name: "release-notes", status: "idle" },
  { session_id: "5a5a5a5a-0000-4000-8000-000000000000", name: null, title: null, status: "busy" },
] };
const server = Bun.serve({
  port: Number(process.env.SETTINGS_VERSION_PORT ?? 0),
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/upgrade/check") {
      posts.push(req.method);
      return Response.json(checkReply.body, { status: checkReply.status });
    }
    if (path === "/api/daemon/restart") {
      if (req.method !== "POST") return Response.json(restartPhase);
      restarts.push(req.method);
      return Response.json(restartReply.body, { status: restartReply.status });
    }
    if (path === "/api/upgrade" && req.method === "POST") {
      started.push((await req.json()) as Record<string, unknown>);
      return Response.json({ run: { id: "r1", args: [], state: "running", output: [] } }, { status: 202 });
    }
    // null until he starts one, as the daemon answers on a machine that has never upgraded from the console
    if (path === "/api/upgrade/run") {
      const last = started.at(-1);
      const args = !last ? (runNow ? ["upgrade", "--to", "0.4.1"] : null) : last.rollback ? ["upgrade", "--rollback"] : ["upgrade", "--to", String(last.to)];
      return Response.json({ run: args ? { id: "r1", args, state: runNow?.state ?? "running", output: runNow?.output ?? [] } : null });
    }
    if (path === "/api/upgrade") return Response.json(upgrade);
    if (path === "/api/heads/alpha/turns/live") return Response.json({ head: "alpha", turns: live });
    if (path === "/api/sessions") return Response.json(sessions);
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
async function version(
  body: Record<string, unknown>,
  reply = { status: 200, body: {} as Record<string, unknown> },
  run: { state: string; output: string[] } | null = null,
): Promise<Page> {
  upgrade = body;
  checkReply = reply;
  posts.length = 0;
  started.length = 0;
  restarts.length = 0;
  restartReply = { status: 202, body: { status: "draining" } };
  restartPhase = { status: "idle", drain_ms: 45_000 };
  runNow = run;
  live = [];
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.getByText("Version").first().click();
  await expect(page.locator(".vcard").first()).toBeVisible({ timeout: 15_000 });
  return page;
}
// The card is a state and an act, never a sentence (Marlin's ruling on the Settings words, Oct 12). "Not checked" sits in
// the small line where "Checked" and its time would, so the version stays the largest thing on the card (Marlin with
// hitstop, Oct 12). The releases page is not a door yet: until a check has found a newer version there is no news.
test("a daemon that has never checked shows its own version, the state and the act, with no empty second card", async () => {
  const page = await version(NEVER_CHECKED);
  try {
    const pane = page.locator(".pane");
    await expect(page.locator(".vcard")).toHaveCount(1);
    await expect(page.locator(".vcard .v")).toHaveText("0.4.0");
    await expect(page.locator(".vcard .kel")).toHaveText("Not checked");
    await expect(page.locator(".vcard .state")).toHaveCount(0);
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
    await expect(page.locator(".vcard .kel")).toHaveText("Not checked"); // and nothing on the card moved
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

// UPGRADE IS THE SAME ARMING AS GO BACK, in the other direction: the first tap asks, the second starts the one run
// POST /api/upgrade drives, and the run header says which way it went rather than "Going back to" over an upgrade.
test("the version a check found is the version Upgrade takes him to, once he has answered the question", async () => {
  const page = await version(NEVER_CHECKED, {
    status: 200,
    body: { state: "read", latest: "0.4.1", read_at_epoch_millis: Date.now(), reason: null },
  });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Check" }).click();
    const newest = page.locator(".vcard", { hasText: "Newest" });
    await expect(newest.getByRole("button", { name: "Upgrade to 0.4.1" })).toBeVisible({ timeout: 10_000 });
    await newest.getByRole("button", { name: "Upgrade to 0.4.1" }).click();
    expect(started).toEqual([]); // the first tap asks and starts nothing
    await expect(newest.getByRole("button", { name: "Stay on 0.4.0" })).toBeVisible();
    await expect(newest.getByRole("link", { name: /Releases/ })).toHaveCount(0); // armed, the card holds the question alone
    await newest.getByRole("button", { name: "Upgrade to 0.4.1" }).click();
    await expect(pane.locator(".run h3")).toHaveText("Upgrading to 0.4.1", { timeout: 10_000 });
    expect(started).toEqual([{ to: "0.4.1" }]);
  } finally {
    await page.close();
  }
}, 30_000);

test("going back still reads as going back, and is still the only thing Go back sends", async () => {
  const page = await version({ ...NEVER_CHECKED, rollback_target: "0.3.2" });
  try {
    const pane = page.locator(".pane");
    await pane.getByRole("button", { name: "Go back to 0.3.2" }).click();
    await pane.getByRole("button", { name: "Go back to 0.3.2" }).click();
    await expect(pane.locator(".run h3")).toHaveText("Going back to 0.3.2", { timeout: 10_000 });
    expect(started).toEqual([{ rollback: true }]);
  } finally {
    await page.close();
  }
}, 30_000);

// A RESTART IS AN ACT, NOT A QUESTION: it drains in-flight turns and splice comes back from one. What it waits for is
// said on the button, because a daemon waiting on a compaction looks exactly like one that hung.
test("Restart splice takes the restart, and says what it is waiting for while it waits", async () => {
  const page = await version(NEVER_CHECKED);
  try {
    const pane = page.locator(".pane");
    restartReply = { status: 202, body: { status: "waiting", compactions: [{ head: "alpha", age_ms: 1200 }] } };
    restartPhase = { status: "waiting", compactions: [{ head: "alpha", age_ms: 1200 }] };
    await pane.getByRole("button", { name: "Restart splice" }).click();
    await expect(pane.getByRole("button", { name: "Waiting for 1 compaction" })).toBeDisabled({ timeout: 10_000 });
    expect(restarts).toEqual(["POST"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("with requests in flight Restart splice arms: Mid-request, a chip per agent opening its session, the daemon's drain cap, and only the second press restarts", async () => {
  const page = await version(NEVER_CHECKED);
  try {
    const pane = page.locator(".pane");
    live = [turn("114e6cb0-9c3a-4d26-b259-f2b7f24ab56c"), turn("5a5a5a5a-0000-4000-8000-000000000000"), { ...turn("80e8a500-0a41-4f21-b707-3d7009e22904"), stopped: true }];
    await pane.getByRole("button", { name: "Restart splice" }).click();
    const armed = pane.locator(".vcard .armed");
    await expect(armed.locator(".said").first()).toHaveText("Mid-request", { timeout: 10_000 });
    await expect(armed.locator(".said.then")).toHaveText("· 45 s to finish, then cut");
    await expect(pane.getByRole("button", { name: "Check" })).toHaveCount(0);
    await expect(armed.locator("a.chip")).toHaveText(["changelog-pass", "5a5a5a5a"]);
    await expect(armed.locator("a.chip").first()).toHaveAttribute("href", "sessions.html#114e6cb0-9c3a-4d26-b259-f2b7f24ab56c");
    expect(restarts).toEqual([]);
    await armed.getByRole("button", { name: "Keep running" }).click();
    await expect(pane.locator(".vcard .armed")).toHaveCount(0);
    await expect(pane.getByRole("button", { name: "Check" })).toBeVisible();
    expect(restarts).toEqual([]);
    await pane.getByRole("button", { name: "Restart splice" }).click();
    await page.locator(".vcard .armed").getByRole("button", { name: "Restart splice" }).click();
    await expect.poll(() => restarts).toEqual(["POST"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a restart nothing would bring it back from is refused in the console's words, not the daemon's", async () => {
  const page = await version(NEVER_CHECKED);
  try {
    const pane = page.locator(".pane");
    restartReply = {
      status: 503,
      body: { error: "nothing will restart this daemon: it was not started by systemd, so a drain would leave it down" },
    };
    await pane.getByRole("button", { name: "Restart splice" }).click();
    await expect(pane.locator(".vcard .said")).toHaveText("Nothing would bring it back", { timeout: 10_000 });
    await expect(pane).not.toContainText("systemd");
    await expect(pane.getByRole("button", { name: "Restart splice" })).toBeEnabled();
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

// ---------- a run: its steps, and the log only when it fails (Marlin, Oct 12) ----------
// The lines an upgrade prints, ANSI already stripped by the route (UpgradeRuns.kt), in the order a real run prints them.
const CHECKED = [
  "splice upgrade: from github.com/torad-labs/splice releases",
  "  ✓ splice.jar      sha256 ok",
  "  ✓ splice-launch   sha256 ok",
  "  ✓ staged          0.4.1 -> ~/.local/share/splice/releases/0.4.1",
  "  ✓ activated       0.4.1 (0.4.0 kept for --rollback)",
];
const STOPPING = ["splice: stopping daemon 0.4.0 on :8100…", "splice: waiting up to 60s for in-flight turns to finish"];
const steps = (page: Page) => page.locator(".run .step");

test("a running upgrade shows its steps, the ones behind it done, and no log", async () => {
  const page = await version(NEVER_CHECKED, undefined, { state: "running", output: [...CHECKED, ...STOPPING] });
  try {
    await expect(page.locator(".run h3")).toHaveText("Upgrading to 0.4.1", { timeout: 10_000 });
    await expect(steps(page)).toHaveText(["Checked", "Stopping", "Waiting for running turns", "Starting"]);
    await expect(steps(page).locator(".lamp.done")).toHaveCount(2); // checked, stopped; it waits for the turns now
    await expect(steps(page).nth(3)).toHaveClass(/pending/);
    await expect(page.locator(".run .logbtn, .run .rlog")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("a finished upgrade reads Now on its version with every step done, and still no log", async () => {
  const page = await version(NEVER_CHECKED, undefined, { state: "running", output: CHECKED });
  try {
    await expect(page.locator(".run h3")).toHaveText("Upgrading to 0.4.1", { timeout: 10_000 });
    runNow = { state: "succeeded", output: [...CHECKED, ...STOPPING, "splice: starting the daemon here (restart)…", "  ✓ daemon          restarted, serving 0.4.1"] };
    await expect(page.locator(".run h3")).toHaveText("Now on 0.4.1", { timeout: 10_000 });
    await expect(steps(page).locator(".lamp.done")).toHaveCount(4);
    await expect(page.locator(".run .logbtn, .run .rlog")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("a failed upgrade marks the step it stopped on, and its full log is a door", async () => {
  const page = await version(NEVER_CHECKED, undefined, { state: "running", output: CHECKED });
  try {
    await expect(page.locator(".run h3")).toHaveText("Upgrading to 0.4.1", { timeout: 10_000 });
    const failed = [...CHECKED, ...STOPPING, "splice: starting the daemon here (restart)…", "  ✗ daemon          did not answer after the restart; run: splice restart"];
    runNow = { state: "failed", output: failed };
    await expect(page.locator(".run h3")).toHaveText("Not on 0.4.1", { timeout: 10_000 });
    await expect(steps(page).nth(3).locator(".lamp.fail")).toHaveCount(1);
    await expect(page.locator(".run .rlog")).toHaveCount(0);
    await page.locator(".run .logbtn").click();
    await expect(page.locator(".run .rlog li")).toHaveCount(failed.length);
    await expect(page.locator(".run .rlog")).toContainText("did not answer after the restart");
  } finally {
    await page.close();
  }
}, 30_000);
