// A console page at rest changes nothing. Once it has drawn what the daemon says, and the daemon keeps saying the
// same at the same time of day, no animation is left running and nothing in the page is written: no lamp pulsing for as long as it is alive,
// no sparkline easing to the numbers it already shows, no part rebuilt on a timer. A page that keeps drawing holds the
// browser's compositor on every frame, and every click and keystroke waits behind it: on Nov 21, 2026 the person who
// uses this console found it too slow to use, with each page holding most of a core while nobody touched it. Through
// the real pages against a stub daemon whose answers do not change, with the page's clock held still: a clock that
// ticks over to the next minute, or an age that grows by one, is a change the page is right to show, and with the time
// moving a run in four met one inside the seconds it watched.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const MIN = 60_000;
const NOW = Date.now();
// every time in a row is fixed when the stub starts, a whole number of minutes back, so nothing a page shows of it
// changes while the test looks
const row = (id: string, name: string, over: Record<string, unknown> = {}) => ({
  session_id: id, name, head: "acme", route: "head", availability: "live", status: "idle", cwd: `/work/harbor/${name}`,
  last: { role: "assistant", tool: null, text: `${name} finished a step.`, ts: NOW - 5 * MIN },
  updated_at: new Date(NOW - 5 * MIN).toISOString(), status_updated_at: NOW - 5 * MIN,
  project: { id: "/work/harbor", name: "harbor", label: "harbor" }, ...over,
});
const SESSIONS = { reading: true, sessions: [row("s-1", "chart-lead", { status: "working" }), row("s-2", "chart-mate"), row("s-3", "solo-run")] };
const TEAM = {
  id: "t1", name: "harbor-crew", goal: "Chart the harbor", repo: "/work/harbor", archived: false,
  slots: [{ id: "lead", role: "Lead", head: "acme", lead: true, session: "s-1" }, { id: "mate", role: "Mate", head: "acme", lead: false, session: "s-2" }],
};

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "acme", label: "claude-acme", state: "running", gate: { inflight: 1, queued: 0, max: 12 } }] },
    "/api/status": { version: "9.8.7", registry: [{ key: "acme", label: "claude-acme", family: "anthropic", authKind: "client" }] },
    "/api/models": { heads: [{ head: "acme", models: [{ id: "acme-1", label: "Acme 1" }] }] },
    "/api/sessions": SESSIONS,
    "/api/teams": { teams: [TEAM] },
    "/api/heads/acme/turns/live": { turns: [{ id: "t-1", session: "s-1", model: "acme-1", phase: "streaming", age_ms: 5 * MIN }], waiting: [] },
    "/api/sessions/history": { sessions: [] },
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

/** What [file] does over three seconds once it has had [settle] ms to draw and for anything that marks a change to
 *  finish: the animations still running, and every write made to the page, named by where it landed. */
async function atRest(file: string, settle: number): Promise<{ running: string[]; writes: string[] }> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    // the time of day stands still for the page, and its timers and animations run as they do
    await page.clock.setFixedTime(new Date(NOW));
    await page.goto(`${s.url}/${file}`);
    await expect(page.locator("nav.nav")).toBeVisible({ timeout: 15_000 });
    await page.waitForTimeout(settle);
    return await page.evaluate(async () => {
      const name = (n: Node | null): string => {
        const e = (n && n.nodeType === 1 ? n : n?.parentElement) as Element | null;
        if (!e) return "?";
        return e.tagName.toLowerCase() + (e.id ? `#${e.id}` : "") + (typeof e.className === "string" && e.className ? `.${e.className.trim().split(/\s+/).join(".")}` : "");
      };
      const writes: string[] = [];
      const seen = new MutationObserver((list) => { for (const m of list) writes.push(`${m.type}${m.attributeName ? `[${m.attributeName}]` : ""} ${name(m.target)}`); });
      seen.observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true });
      await new Promise((done) => setTimeout(done, 3_000));
      seen.disconnect();
      const running = document.getAnimations().filter((a) => a.playState === "running").map((a) => `${(a as CSSAnimation).animationName ?? "transition"} on ${name(((a.effect as KeyframeEffect | null)?.target ?? null) as Node | null)}`);
      return { running, writes: [...new Set(writes)] };
    });
  } finally {
    await page.close();
  }
}

// long enough for a lamp that has just come alive to finish its two pulses, and for a page's first reads to land
const SETTLE = 9_000;
for (const file of ["sessions.html", "accounts.html", "teams.html", "providers.html", "activity.html", "settings.html"]) {
  test(`${file} at rest runs no animation and writes nothing`, async () => {
    const got = await atRest(file, SETTLE);
    bunExpect(got.running).toEqual([]);
    bunExpect(got.writes).toEqual([]);
  }, 40_000);
}

test("a lamp that comes alive pulses and then rests", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`${s.url}/sessions.html`);
    const lamp = page.locator(".head .status .lamp.live");
    await expect(lamp).toBeVisible({ timeout: 15_000 });
    const pulsing = () => lamp.evaluate((el) => el.getAnimations().some((a) => a.playState === "running"));
    bunExpect(await pulsing()).toBe(true);
    await expect.poll(pulsing, { timeout: 12_000 }).toBe(false);
  } finally {
    await page.close();
  }
}, 40_000);
