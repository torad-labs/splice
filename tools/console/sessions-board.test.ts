// The Sessions board (Night Deck): every team splice keeps is a column of the sessions bound to its slots, and the
// sessions on no team stand in a column of their own, reflowed so neither width scrolls sideways. Its acts write where
// the console writes them: a rename, a session dragged onto a team, a team disbanded. Search dims what does not match
// and reaches ended sessions through splice's history. The ended count holds across the reads that leave gone rows out,
// and a press made while the board redraws still opens the session it was made on. Through the real page against a
// stub daemon with made-up rows. (Rewritten from sessions-columns, sessions-gone-once, sessions-list and
// sessions-redraw, which pinned the old page's project blocks.) The need float counts the sessions waiting on the person,
// and pressing it leaves only them standing until it is pressed again.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const MIN = 60_000;
let reads = 0;
/** chart-mate waits on the person: only the need float's test turns it on */
let waiting = false;
const row = (id: string, name: string, over: Record<string, unknown> = {}) => ({
  session_id: id, name, head: "acme", route: "head", availability: "live", status: "idle", cwd: `/work/harbor/${name}`,
  last: { role: "assistant", tool: null, text: `${name} finished a step.`, ts: Date.now() - MIN },
  updated_at: new Date(Date.now() - 2 * MIN).toISOString(), status_updated_at: Date.now() - 2 * MIN,
  project: { id: "/work/harbor", name: "harbor", label: "harbor" }, ...over,
});
const gone = (id: string, name: string) => row(id, name, { availability: "gone", project: { id: "/work/dock", name: "dock", label: "dock" } });
let ended = [gone("e-1", "oldie"), gone("e-2", "elder")];
const mark = () => `mark-${ended.length}`;
const here = () => [
  row("s-lead", "chart-lead", { status: "working", status_updated_at: Date.now() - 7 * MIN }),
  row("s-mate", "chart-mate", waiting ? { status: "waiting", waiting_for: "permission" } : {}),
  // its last line changes on every read, so every read is a redraw he can see
  row("s-solo", "solo-run", { last: { role: "assistant", tool: null, text: `read number ${reads}`, ts: Date.now() } }),
];
let team = {
  id: "t1", name: "harbor-crew", goal: "Chart the harbor", repo: "/work/harbor", archived: false,
  slots: [{ id: "lead", role: "Lead", head: "acme", lead: true, session: "s-lead" }, { id: "mate", role: "Mate", head: "acme", lead: false, session: "s-mate" }],
};

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "acme", label: "claude-acme", gate: {} }] },
    "/api/status": { registry: [{ key: "acme", label: "claude-acme", family: "anthropic" }] },
    "/api/models": { heads: [{ head: "acme", models: [{ id: "acme-1", label: "Acme 1" }] }] },
    "/api/sessions": (url: URL) => {
      reads++;
      const same = url.searchParams.get("gone") === mark();
      return { reading: true, sessions: same ? here() : [...here(), ...ended], gone_mark: mark(), ...(same ? { gone_held: true } : {}) };
    },
    "/api/teams": () => ({ teams: [team] }),
    "PUT /api/teams/t1": (_u: URL, body: unknown) => { team = { ...team, ...(body as object) }; return team; },
    "PUT /api/teams/t1/sessions": (_u: URL, body: unknown) => {
      const b = (body as { bindings: Record<string, string | null> }).bindings;
      team = { ...team, slots: team.slots.map((sl) => (sl.id in b ? { ...sl, session: b[sl.id]! } : sl)) };
      return team;
    },
    "/api/sessions/s-mate/screen": { asked: "Do you want to proceed?", choices: [{ label: "Yes", choice: 1 }, { label: "No", choice: 2 }] },
    "/api/sessions/history": (url: URL) => ({ sessions: url.searchParams.get("query") === "elder" ? [gone("e-2", "elder")] : [] }),
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function open(width = 1440, height = 900): Promise<Page> {
  const page = await browser.newPage({ viewport: { width, height } });
  await page.goto(`${s.url}/sessions.html`);
  await expect(page.locator("#board .srow")).toHaveCount(3, { timeout: 10_000 });
  return page;
}
const column = (page: Page, name: string) => page.locator("#board section.team", { has: page.locator(".team-name", { hasText: name }) });
/** the column of sessions on no team is quiet (nothing working, nothing waiting), so it rests folded to its header */
async function unfoldLoose(page: Page) {
  const fold = page.locator("#board section.team.loose .team-fold");
  await expect(fold).toHaveText(/^Folded · 1 session · show$/);
  await fold.click();
  await expect(page.locator(".srow", { hasText: "solo-run" })).toBeVisible();
}

test("each team is a column of the sessions in its slots, the rest sit together, and neither width scrolls sideways", async () => {
  for (const [w, hgt] of [[1440, 900], [3840, 2160]] as const) {
    const page = await open(w, hgt);
    try {
      const crew = column(page, "harbor-crew");
      await expect(crew.locator(".srow")).toHaveText([/chart-lead/, /chart-mate/]);
      await expect(page.locator("#board section.team.loose .srow")).toHaveText([/solo-run/]);
      await expect(crew.locator(".team-counts")).toHaveText("1 working · 1 idle");
      bunExpect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    } finally {
      await page.close();
    }
  }
}, 60_000);

test("a team renamed on its header is written to splice and reads its new name", async () => {
  const page = await open();
  try {
    await column(page, "harbor-crew").locator(".team-name").dblclick();
    await page.locator(".team-rename").fill("dock-crew");
    await page.keyboard.press("Enter");
    await expect(column(page, "dock-crew")).toHaveCount(1, { timeout: 10_000 });
    const put = s.calls.find((c) => c.method === "PUT" && c.path === "/api/teams/t1");
    bunExpect(put?.body).toMatchObject({ id: "t1", name: "dock-crew" });
  } finally {
    team = { ...team, name: "harbor-crew" };
    await page.close();
  }
}, 30_000);

test("a session dragged onto a team takes a slot of its own there", async () => {
  const page = await open();
  try {
    await unfoldLoose(page);
    const from = await page.locator(".srow", { hasText: "solo-run" }).boundingBox();
    const to = await column(page, "harbor-crew").locator(".team-hd").boundingBox();
    await page.mouse.move(from!.x + 40, from!.y + 20);
    await page.mouse.down();
    await page.mouse.move(to!.x + 60, to!.y + 30, { steps: 12 });
    await page.mouse.up();
    await expect(column(page, "harbor-crew").locator(".srow", { hasText: "solo-run" })).toHaveCount(1, { timeout: 10_000 });
    const bind = s.calls.find((c) => c.method === "PUT" && c.path === "/api/teams/t1/sessions");
    bunExpect(Object.values((bind?.body as { bindings: Record<string, string> }).bindings)).toEqual(["s-solo"]);
  } finally {
    team = { ...team, slots: team.slots.filter((sl) => sl.session !== "s-solo") };
    await page.close();
  }
}, 30_000);

test("disbanding a team from its menu archives it on splice", async () => {
  const page = await open();
  try {
    await column(page, "harbor-crew").locator(".team-more").click();
    await page.locator(".menu button", { hasText: "Disband team" }).click();
    await expect.poll(() => s.calls.some((c) => c.method === "POST" && c.path === "/api/teams/t1/archive")).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);

test("search dims what does not match, lists ended matches from history, and clearing it takes them away", async () => {
  const page = await open();
  try {
    await page.locator("#q").fill("elder");
    await expect(page.locator("#pastHits .rl-row", { hasText: "elder" })).toHaveCount(1, { timeout: 10_000 });
    await expect(page.locator(".srow.is-dim")).toHaveCount(3);
    await page.locator("#q").fill("");
    await expect(page.locator("#pastHits")).toHaveCount(0, { timeout: 5_000 });
    await expect(page.locator(".srow.is-dim")).toHaveCount(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("the ended count holds through reads that leave gone rows out, and a session that ends later joins it", async () => {
  const page = await open();
  try {
    const ended0 = page.locator("#railR h2", { hasText: "Ended" });
    await expect(ended0).toHaveText("Ended · 2", { timeout: 10_000 });
    await page.waitForTimeout(6_000); // a read that sends the mark back, answered with the live rows alone
    await expect(ended0).toHaveText("Ended · 2");
    ended = [...ended, gone("e-3", "latest")];
    await expect(ended0).toHaveText("Ended · 3", { timeout: 10_000 });
  } finally {
    ended = ended.slice(0, 2);
    await page.close();
  }
}, 40_000);

test("a working session's time in its state counts from when that state began", async () => {
  const page = await open();
  try {
    await expect(page.locator(".srow", { hasText: "chart-lead" }).locator(".srow-st")).toHaveText(/^Working · 7 min$/);
  } finally {
    await page.close();
  }
}, 30_000);

test("a press made while the board redraws still opens the session it was made on", async () => {
  const page = await open();
  try {
    await unfoldLoose(page);
    for (let i = 0; i < 3; i++) await page.waitForTimeout(1_700);
    await page.locator(".srow", { hasText: "solo-run" }).click();
    await expect(page.locator("#conv")).toBeVisible();
    await expect(page.locator("#title")).toHaveText("solo-run");
  } finally {
    await page.close();
  }
}, 30_000);

test("the need float counts the sessions waiting on the person, and pressing it leaves only them standing until pressed again", async () => {
  waiting = true;
  const page = await open();
  try {
    const need = page.locator("#needBtn");
    await expect(need).toContainText("1 needs you");
    const standing = () => page.$$eval("#board .srow", (rs) => rs.filter((r) => !r.classList.contains("is-dim")).map((r) => r.querySelector(".srow-nm")?.textContent));
    await need.click();
    await expect.poll(standing).toEqual(["chart-mate"]);
    await need.click();
    await expect.poll(async () => (await standing()).length).toBe(3);
  } finally {
    waiting = false;
    await page.close();
  }
}, 30_000);

test("on a phone the need float keeps its words, clear of the switch beside it", async () => {
  waiting = true;
  const page = await open(390, 844);
  try {
    const words = page.locator("#needBtn span").last();
    await expect(words).toHaveText("1 needs you");
    const box = async (sel: string) => (await page.locator(sel).boundingBox())!;
    await expect.poll(async () => (await words.boundingBox())!.width).toBeGreaterThan(40);
    const need = await box("#needBtn"), sw = await box(".switch");
    bunExpect(need.x + need.width).toBeLessThanOrEqual(sw.x);
    bunExpect(await words.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
  } finally {
    waiting = false;
    await page.close();
  }
}, 30_000);
