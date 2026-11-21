// Settings (Night Deck machine), through the real page against a stub daemon with made-up values: the week's counts are
// the daemon's own counts (above what one read could carry) and today's peak is the daemon's; a command can be given its
// own value and taken back to the default, each saved for that command alone; a value splice refuses reads Not saved and
// goes back, and one it reads at start says At restart and raises the Restart float; adding a project folder writes the
// whole list; a shorter usage history asks first, says what it deletes, and deletes only on the yes; upgrading and going
// back ask first and send what they name; Restart now asks splice to restart at once and says when it is back; a server
// splice cannot share says why. (Rewritten from settings-week-exact, settings-peak, settings-per-command,
// settings-projects, settings-version and settings-mcp-restore, which pinned the old Settings page; settings-data,
// settings-zero, settings-plan-logins and mcp-servers pinned its own tables and cards and went with it: a login's plan
// windows now live on Accounts.)
import { afterAll, beforeAll, beforeEach, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const NOW = Date.now(), DAY = 86_400_000;
const effective: Record<string, unknown> = {
  effort: "high", summary: "detailed", showReasoning: "text", replayReasoning: true, maxInflight: 12, maxQueued: 512,
  streamIdleMs: 90_000, firstByteTimeoutMs: 90_000, upstreamTimeoutMs: 1_800_000, projectsFolders: "/work/a",
};
const RESTART = new Set(["maxQueued"]);
let refuse: Record<string, string> = {};

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "acme", label: "claude-acme", gate: { inflight: 1 } }, { key: "beta", label: "claude-beta", gate: { inflight: 0 } }] },
    "/api/status": { registry: [{ key: "acme", label: "claude-acme", family: "openai" }, { key: "beta", label: "claude-beta", family: "openai" }] },
    "/api/config": (_u: URL, body: unknown, req: Request) => {
      if (req.method === "PATCH") {
        const b = body as Record<string, unknown>, rejected = Object.fromEntries(Object.keys(b).filter((k) => k in refuse).map((k) => [k, refuse[k]]));
        return { applied: b, rejected, restart_required: Object.keys(b).filter((k) => RESTART.has(k)), persisted: "state/config.json" };
      }
      return { effective, layers: { perHead: {} }, knobs: Object.fromEntries(Object.entries(effective).map(([k, v]) => [k, { value: v, scope: "default", disposition: RESTART.has(k) ? "restart" : "live", editable: true }])) };
    },
    "/api/perf/turns": (url: URL) => {
      const head = url.searchParams.get("head") ?? "";
      if (url.searchParams.get("peak") === "1") return { heads: [{ key: head, at_once: { peak: head === "acme" ? 7 : 0, at: NOW } }] };
      const asks = url.searchParams.getAll("ask");
      return { heads: [{ key: head, count: 1, returned: 1, rows: [], asks: asks.map(() => (head === "acme" ? { count: 2_345, longest_silence_ms: 125_000 } : { count: 0 })) }] };
    },
    "/api/projects": { projects: [{ name: "x", root: "/work/a/x" }, { name: "y", root: "/made/b/y" }] },
    "/api/history": (url: URL) => (url.searchParams.get("days")
      ? { cut: { bytes: 5_000_000, turns: 1_234, cutoff_epoch_ms: NOW - 7 * DAY } }
      : { window: { days: 35, forever: false, nothing: false }, held: { bytes: 9_000_000, turns: 5_000, oldest_epoch_ms: NOW - 60 * DAY } }),
    "/api/upgrade": { installed: "1.2.3", latest: "1.2.4", rollback_target: "1.2.2" },
    "/api/daemon/restart": (_u: URL, _b: unknown, req: Request) => (req.method === "POST" ? { ok: true } : { status: "idle" }),
    "/api/mcp/memory": { sessions: 2, bytes_now: 400_000_000, bytes_if_shared: 100_000_000, servers: [{ name: "made-up-notes", class: "unshared", why: "variable" }] },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});
beforeEach(() => { refuse = {}; s.calls.length = 0; });

async function at(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/settings.html`);
  await expect(page.locator('[data-part="splice"]')).toContainText("1.2.3", { timeout: 10_000 });
  return page;
}
const glass = (page: Page) => page.locator(".lens:not([hidden])").first();
const sent = (method: string, path: string) => s.calls.filter((c) => c.method === method && c.path === path);

test("the week's counts are the daemon's own, and today's peak is the daemon's, the idle commands under one line", async () => {
  const page = await at();
  try {
    await expect(page.locator('[data-part="watch"]')).toContainText("2,345");
    await expect(page.locator('[data-part="watch"]')).toContainText("2 min 5 s");
    await expect(page.locator('.gl[data-gate="acme"]')).toContainText("7");
    await expect(page.locator('.gl[data-gate="acme"]')).toContainText("of 12");
    await expect(page.locator('.gl[data-gate="*"]')).toContainText("1 more");
  } finally {
    await page.close();
  }
}, 30_000);

test("a command gets its own value, saved for it alone, and Back to default takes it back", async () => {
  const page = await at();
  try {
    await page.locator('[data-part="cc"]').click();
    await glass(page).getByRole("button", { name: "Add provider" }).click();
    await page.getByRole("menuitem", { name: /claude-beta/ }).click();
    await expect.poll(() => sent("PATCH", "/api/config").map((c) => `${c.query.get("head")} ${JSON.stringify(c.body)}`)).toContain('beta {"effort":"high"}');
    await glass(page).getByRole("button", { name: "Back to default" }).click();
    await expect.poll(() => sent("PATCH", "/api/config").map((c) => `${c.query.get("head")} ${JSON.stringify(c.body)}`)).toContain('beta {"effort":null}');
  } finally {
    await page.close();
  }
}, 30_000);

test("a value splice refuses reads Not saved with its reason and goes back; one read at start says At restart", async () => {
  refuse = { replayReasoning: "made-up reason" };
  const page = await at();
  try {
    await page.locator('[data-part="reasoning"]').click();
    const row = glass(page).locator(".lr", { hasText: "Reuse previous reasoning" }).first();
    await row.locator(".seg-o", { hasText: "Off" }).click();
    await expect(row.locator(".sv")).toContainText("Not saved");
    await expect(row).toContainText("made-up reason");
    await expect(row.getByRole("radio", { name: "On" })).toHaveAttribute("aria-checked", "true");
    await page.keyboard.press("Escape");
    await page.locator('.gl[data-gate="acme"]').click();
    const q = glass(page).locator(".lr", { hasText: "Queue size" }).first();
    await q.locator('button.notch-stop[title="1024"]').click();
    await expect(q.locator(".sv")).toContainText("At restart");
    await expect(page.locator("#needBtn")).toContainText("Restart · 1");
  } finally {
    await page.evaluate(() => sessionStorage.clear());
    await page.close();
  }
}, 30_000);

test("adding a project folder writes the whole list of folders", async () => {
  const page = await at();
  try {
    await page.locator('[data-part="projects"]').click();
    await glass(page).getByRole("button", { name: "Add folder" }).first().click();
    await page.getByRole("menuitem", { name: /\/made\/b/ }).click();
    await expect.poll(() => sent("PATCH", "/api/config").map((c) => JSON.stringify(c.body))).toContain('{"projectsFolders":"/work/a:/made/b"}');
  } finally {
    await page.close();
  }
}, 30_000);

test("a shorter usage history asks first, says what it deletes, and deletes only on the yes", async () => {
  const page = await at();
  try {
    await page.locator('[data-part="storage"]').click();
    const row = glass(page).locator(".lr", { hasText: "Usage history" }).first();
    await row.locator('button.notch-stop[title="7 days"]').click();
    await expect(row.locator(".lr-ask")).toContainText("5 MB");
    await expect(row.locator(".lr-ask")).toContainText("1,234 requests");
    bunExpect(sent("PUT", "/api/history")).toHaveLength(0);
    await row.getByRole("button", { name: "Delete" }).click();
    await expect.poll(() => sent("PUT", "/api/history").map((c) => JSON.stringify(c.body))).toEqual([JSON.stringify({ days: 7, delete_before_epoch_ms: NOW - 7 * DAY })]);
  } finally {
    await page.close();
  }
}, 30_000);

// Nov 21, 2026: a request is running in this world, so Restart now says what it cuts and takes a second press, as an
// upgrade and a rollback do (the page asks only while requests are running). This test still pressed it once.
test("upgrading, going back and restarting ask first while a request is running, send what they name, and Restart says when splice is back", async () => {
  const page = await at();
  try {
    await page.locator('[data-part="splice"]').click();
    await glass(page).getByRole("button", { name: "Upgrade to 1.2.4" }).click();
    bunExpect(sent("POST", "/api/upgrade")).toHaveLength(0);
    await glass(page).getByRole("button", { name: "Upgrade", exact: true }).click();
    await expect.poll(() => sent("POST", "/api/upgrade").map((c) => JSON.stringify(c.body))).toEqual(['{"to":"1.2.4"}']);
    await glass(page).getByRole("button", { name: "Rollback to 1.2.2" }).click();
    await glass(page).getByRole("button", { name: "Rollback", exact: true }).click();
    await expect.poll(() => sent("POST", "/api/upgrade").map((c) => JSON.stringify(c.body))).toContain('{"rollback":true}');
    await glass(page).getByRole("button", { name: "Restart now" }).click();
    bunExpect(sent("POST", "/api/daemon/restart")).toHaveLength(0);
    await glass(page).locator(".lr-ask").getByRole("button", { name: "Restart now" }).click();
    await expect.poll(() => sent("POST", "/api/daemon/restart").map((c) => c.query.get("now"))).toEqual(["1"]);
    await expect(glass(page).locator(".lr", { hasText: "Restart now" }).locator(".sv")).toContainText("Back", { timeout: 10_000 });
  } finally {
    await page.close();
  }
}, 30_000);

test("an MCP server splice cannot share says why", async () => {
  const page = await at();
  try {
    await page.locator('[data-part="mcp"]').click();
    await expect(glass(page).locator(".lr", { hasText: "Not shareable" })).toContainText("made-up-notes");
    await expect(glass(page).locator(".lr", { hasText: "Not shareable" })).toContainText("A different value in each session");
  } finally {
    await page.close();
  }
}, 30_000);
