// The Models page asks splice only while it can be seen, one round at a time, and reads the lists that change only when
// someone saves or edits a file once a minute, not every 5 s (Oct 25: nine routes every 5 s, in a hidden tab too, with
// rounds piling up behind a slow splice). What he reads on a command's card stays as current as it was where it moves: a
// command that starts or appears outside the page, a session that starts on it, his own Start, a tab he comes back to,
// and each list that failed to read, wherever the page shows it. A pane he opens is never read again under him, and a
// page he leaves is gone from the tab.
// Through the real page in Chromium on a clock the test holds, against a daemon that answers as splice does. No test here
// counts requests: each judges what the page shows, or what the tab still holds.
import { afterAll, beforeAll, beforeEach, expect, test } from "bun:test";
import { chromium, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const CARD = 'article.card.h[data-open="alpha"]';
// What the daemon says about its command alpha, and beta once it is added. A test changes it the way a terminal, a hand
// edit or a restart would.
const world = {
  running: false, label: "Alpha One", url: "https://api.alpha.example", signedOut: false, beta: false,
  sessions: [] as Record<string, unknown>[],
  // the projects splice has seen, the runtime on this computer that answers alpha's provider ("" when none does), and
  // each command's own settings in splice.toml
  projects: [] as Record<string, unknown>[], runtime: "", own: {} as Record<string, Record<string, unknown>>,
  // the routes that answer 500, as a route that threw or a daemon on its way down does
  fail: new Set<string>(),
  // each route asked since the test cleared it: it only tells a test that a round has gone out, and is never judged
  asked: new Set<string>(),
};
// One route held until the test lets it answer, the way a slow listing holds a round open.
let held: { path: string; arrived: () => void; until: Promise<void> } | null = null;
const releases: (() => void)[] = [];
function hold(path: string) {
  let arrived = () => {}, release = () => {};
  const asked = new Promise<void>((resolve) => { arrived = resolve; });
  const until = new Promise<void>((resolve) => { release = resolve; });
  held = { path, arrived, until };
  releases.push(release);
  return { asked, release };
}
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    world.asked.add(path);
    const slow = held?.path === path ? held : null;
    if (slow) {
      held = null;
      slow.arrived();
      await slow.until;
    }
    if (world.fail.has(path)) return Response.json({ error: "failed" }, { status: 500 });
    if (path === "/api/heads") {
      return Response.json({ heads: [
        { key: "alpha", label: "claude-alpha", running: world.running, healthy: world.running },
        ...(world.beta ? [{ key: "beta", label: "claude-beta", running: true, healthy: true }] : []),
      ] });
    }
    if (path === "/api/topology") {
      return Response.json({ topology: {
        heads: { alpha: { provider: "alpha-co" }, ...(world.beta ? { beta: { provider: "beta-co" } } : {}) },
        providers: { "alpha-co": { base_url: world.url }, ...(world.beta ? { "beta-co": { base_url: "https://api.beta.example" } } : {}) },
      } });
    }
    if (path === "/api/models") {
      return Response.json({ heads: [
        { head: "alpha", pinned_model: null, models: [{ id: "alpha-one", label: world.label, resolved: true, slot: "opus" }] },
        ...(world.beta ? [{ head: "beta", pinned_model: null, models: [{ id: "beta-one", label: "Beta One", resolved: true, slot: "opus" }] }] : []),
      ] });
    }
    // a stopped command is a failed daemon/head check in doctor, which offers the fix that starts it; a running command
    // that lost its login is a failed auth check
    if (path === "/api/doctor") {
      return Response.json({ checks: [
        ...(world.running ? [] : [{ id: "daemon/head alpha", status: "fail", detail: "alpha is not running", fix_id: "start-alpha" }]),
        ...(world.signedOut ? [{ id: "auth/alpha", status: "fail", detail: "alpha is not signed in" }] : []),
      ] });
    }
    if (path === "/api/sessions") return Response.json({ sessions: world.sessions });
    if (path === "/api/projects") return Response.json({ projects: world.projects, folders: [] });
    if (path.startsWith("/api/projects/") && path.endsWith("/files")) return Response.json({ files: [] });
    if (path === "/api/models/local") {
      return Response.json({ providers: world.runtime
        ? [{ provider: "alpha-co", url: world.url, runtime: world.runtime, version: "0.3.30", models: [] }]
        : [] });
    }
    if (path === "/api/claude-head") return Response.json({ mode: "wrapped", shim_path: "/home/u/.local/bin/claude", resolves_to: "/home/u/.local/bin/claude" });
    if (path === "/api/add/profiles") return Response.json({ profiles: [] });
    if (path === "/api/config") return Response.json({ layers: { perHead: world.own } });
    if (path === "/api/doctor/fix/start-alpha" && req.method === "POST") {
      world.running = true;
      return Response.json({});
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return path !== "/" && existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  for (const release of releases.splice(0)) release();
  await browser.close();
  server.stop(true);
});
beforeEach(() => {
  for (const release of releases.splice(0)) release();
  held = null;
  Object.assign(world, {
    running: false, label: "Alpha One", url: "https://api.alpha.example", signedOut: false, beta: false, sessions: [],
    projects: [], runtime: "", own: {},
  });
  world.fail.clear();
  world.asked.clear();
});

// The page has no call on its way but the [held] the test itself keeps out. A round is a chain of reads with no gap
// between them, so a page that shows none in flight is between rounds, and what it drew is what the last round read.
async function settled(page: Page, held = 0) {
  const give = Date.now() + 10_000;
  while ((await page.evaluate<number>("API.pending()")) > held) {
    if (Date.now() > give) throw new Error("the page still has calls in flight after 10 s");
    await Bun.sleep(25);
  }
}
// The page's clock moves only here, so a 5 s tick comes when the test says and never on its own. The clock then stands
// a second or so past the tick, so every answer lands in page time after the tick that asked for it, as a real one does.
async function step(page: Page, ms: number) {
  await page.clock.runFor(ms);
  await settled(page);
}
// Steps 5 s at a time until a round has asked [path], which is how a test knows the minute's read of the lists went out.
async function stepUntilAsked(page: Page, path: string) {
  world.asked.clear();
  for (let i = 0; i < 15 && !world.asked.has(path); i++) await step(page, 5_000);
  if (!world.asked.has(path)) throw new Error(`no round asked ${path} in 75 s of page time`);
}
// Steps 5 s at a time until the card's model line reads [model], which is how a test knows the lists were read, whenever
// in the minute that fell. At most [ticks] steps, then it says so.
async function stepUntilModel(page: Page, model: string, ticks: number) {
  for (let taken = 0; taken < ticks; taken++) {
    await step(page, 5_000);
    if ((await cardOf(page))?.[1] === model) return;
  }
  throw new Error(`the card did not read ${model} in ${ticks} ticks`);
}
// The tab goes out of view or comes back, as the browser does it: the page reads document.hidden, then hears the event.
const setVisible = (page: Page, visible: boolean) =>
  page.evaluate((v) => {
    Object.defineProperty(document, "hidden", { configurable: true, get: () => !v });
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (v ? "visible" : "hidden") });
    document.dispatchEvent(new Event("visibilitychange"));
  }, visible);
// What a command's card says, as he reads it: its state, then its model. A command that needs him says its state once, in
// the card's top-right slot ("Not started", "Signed out"); one that is fine says no word, and its lamp is the state:
// "ready" when it is up and idle, "working" while a session runs on it.
const cardOf = (page: Page, key = "alpha") =>
  page.evaluate((sel) => {
    const card = document.querySelector(sel);
    if (!card) return null;
    const lamp = card.querySelector(".lamp")?.classList;
    const state = card.querySelector(".cstate")?.textContent?.trim() || ["working", "ready"].find((c) => lamp?.contains(c)) || "";
    return [state, card.querySelector(".meta .cmodel")?.textContent ?? ""];
  }, `article.card.h[data-open="${key}"]`);
// What [read] finds on the page once it is as wanted, or after 5 s of real time, whichever comes first.
async function showing(page: Page, read: (page: Page) => Promise<unknown>, want: unknown) {
  const give = Date.now() + 5_000;
  let seen = await read(page);
  while (JSON.stringify(seen) !== JSON.stringify(want) && Date.now() < give) {
    await Bun.sleep(50);
    seen = await read(page);
  }
  return seen;
}
// The card once it reads as wanted, or after 5 s of real time, whichever comes first.
const cardReading = (page: Page, want: string[], key = "alpha") => showing(page, (p) => cardOf(p, key), want);
// The Models page on a screen too narrow to open a command beside its list. A pane holds a draft, and the page reads
// nothing under a draft, so with one open nothing below would be tested. Loaded, then held: its clock stands still.
async function open(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1024, height: 900 } });
  try {
    await page.clock.install({ time: new Date("2026-10-25T12:00:00Z") });
    await page.goto(`http://127.0.0.1:${server.port}/models.html`);
    await page.locator(CARD).waitFor({ timeout: 15_000 });
    expect(await page.locator(".cmds.open").count()).toBe(0);
    await settled(page);
    await page.clock.pauseAt((await page.evaluate<number>("Date.now()")) + 1_000);
    await settled(page);
    return page;
  } catch (error) {
    await page.close();
    throw error;
  }
}

test("Back on the tab after two minutes hidden, what changed meanwhile shows at once", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    await setVisible(page, false);
    await step(page, 120_000);
    await step(page, 1_000);
    // started from a terminal, its doctor check cleared, and the model renamed in splice.toml, all while nobody looked
    world.running = true;
    world.label = "Alpha Two";
    await setVisible(page, true);
    await settled(page);
    expect(await cardReading(page, ["ready", "Alpha Two"])).toEqual(["ready", "Alpha Two"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("A tab out of view keeps its card as it was while the daemon changes under it, then shows it at once", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    await setVisible(page, false);
    // started from a terminal and renamed in splice.toml after the tab went out of view, and then a minute and more of
    // ticks: a page that kept asking would have read both at the first of them
    world.running = true;
    world.label = "Alpha Two";
    for (let s = 5; s <= 65; s += 5) await step(page, 5_000);
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    await setVisible(page, true);
    await settled(page);
    expect(await cardReading(page, ["ready", "Alpha Two"])).toEqual(["ready", "Alpha Two"]);
  } finally {
    await page.close();
  }
}, 60_000);

test("A tab that goes out of view while a tick's read is out reads no lists after it", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    // started from a terminal and renamed in splice.toml; the tick's read of the commands answers slowly
    const slow = hold("/api/heads");
    world.running = true;
    world.label = "Alpha Two";
    await page.clock.runFor(5_000);
    await slow.asked;
    // the tab goes out of view while that read is out. Its answer shows the command moved, which brings the lists along
    // when the page may still ask, and the page may not
    await setVisible(page, false);
    slow.release();
    await settled(page);
    expect((await cardOf(page))?.[1]).toBe("Alpha One");
    await setVisible(page, true);
    await settled(page);
    expect(await cardReading(page, ["ready", "Alpha Two"])).toEqual(["ready", "Alpha Two"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("A command started outside the page shows as ready within one tick", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    world.running = true;
    await step(page, 5_000);
    expect(await cardReading(page, ["ready", "Alpha One"])).toEqual(["ready", "Alpha One"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("A command added outside the page shows its card with its model within one tick", async () => {
  world.running = true;
  const page = await open();
  try {
    expect(await cardOf(page, "beta")).toBeNull();
    // added from a terminal: heads, topology and the roster all list beta at once
    world.beta = true;
    await step(page, 5_000);
    expect(await cardReading(page, ["ready", "Beta One"], "beta")).toEqual(["ready", "Beta One"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("A session that starts or ends on a command shows on its card within one tick", async () => {
  world.running = true;
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["ready", "Alpha One"]);
    world.sessions = [{ session_id: "s-1", head: "alpha", status: "working" }];
    await step(page, 5_000);
    expect(await cardReading(page, ["working", "Alpha One"])).toEqual(["working", "Alpha One"]);
    world.sessions = [];
    await step(page, 5_000);
    expect(await cardReading(page, ["ready", "Alpha One"])).toEqual(["ready", "Alpha One"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("A change to the lists made outside the console shows within a minute", async () => {
  world.running = true;
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["ready", "Alpha One"]);
    world.label = "Alpha Two";
    for (let s = 5; s <= 60; s += 5) await step(page, 5_000);
    expect(await cardReading(page, ["ready", "Alpha Two"])).toEqual(["ready", "Alpha Two"]);
  } finally {
    await page.close();
  }
}, 60_000);

test("A change made just after a read of the lists shows a minute on, whichever read it was", async () => {
  const page = await open();
  try {
    // started from a terminal: the tick's heads show it moved, and the same round reads the lists after them
    world.running = true;
    await step(page, 5_000);
    expect(await cardReading(page, ["ready", "Alpha One"])).toEqual(["ready", "Alpha One"]);
    // renamed in splice.toml just after that read: twelve ticks on, a minute, the card shows it
    world.label = "Alpha Two";
    for (let i = 0; i < 12; i++) await step(page, 5_000);
    expect(await cardReading(page, ["ready", "Alpha Two"])).toEqual(["ready", "Alpha Two"]);
    // renamed again just after that minute's own read: the next minute shows it too
    world.label = "Alpha Three";
    for (let i = 0; i < 12; i++) await step(page, 5_000);
    expect(await cardReading(page, ["ready", "Alpha Three"])).toEqual(["ready", "Alpha Three"]);
  } finally {
    await page.close();
  }
}, 60_000);

test("The lists are read once a minute: eleven ticks after a read of them leave the card as it was", async () => {
  world.running = true;
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["ready", "Alpha One"]);
    // the first read of the lists after the page loaded, wherever in its minute that falls
    world.label = "Alpha Two";
    await stepUntilModel(page, "Alpha Two", 12);
    // renamed just after that read: each tick on reads what moves, and the card keeps what the lists said until the twelfth
    world.label = "Alpha Three";
    for (let tick = 1; tick <= 11; tick++) {
      await step(page, 5_000);
      expect(await cardOf(page)).toEqual(["ready", "Alpha Two"]);
    }
    await step(page, 5_000);
    expect(await cardReading(page, ["ready", "Alpha Three"])).toEqual(["ready", "Alpha Three"]);
  } finally {
    await page.close();
  }
}, 60_000);

// Each list the minute's read asks but topology, whose failure leaves every list as it was: where the page shows it, and
// what it shows once read. A read that failed shows something else until the lists are read again: the card's model
// reads None, a signed-out command reads ready, the claude switch goes, a local runtime reads as its TOML key, the
// projects read none, and a command's own settings leave its pane. [look] goes where that shows, once they are read.
const LISTS: {
  route: string; what: string; world: Partial<typeof world>;
  look?: (page: Page) => Promise<void>; read: (page: Page) => Promise<unknown>; answer: unknown;
}[] = [
  { route: "/api/models", what: "the roster", world: { running: true }, read: (page) => cardOf(page), answer: ["ready", "Alpha One"] },
  {
    route: "/api/doctor", what: "doctor's checks", world: { running: true, signedOut: true },
    read: (page) => cardOf(page), answer: ["Signed out", "Alpha One"],
  },
  {
    route: "/api/claude-head", what: "where plain claude goes", world: {},
    read: (page) => page.evaluate(() => [...document.querySelectorAll('#claude [aria-pressed="true"]')].map((b) => b.textContent)),
    answer: ["Through splice"],
  },
  {
    // alpha served by LM Studio on this computer, under a provider name splice has no word for
    route: "/api/models/local", what: "the local runtimes", world: { running: true, runtime: "LM Studio", url: "http://127.0.0.1:1234" },
    read: (page) => page.evaluate((sel) => document.querySelector(`${sel} .prov`)?.textContent ?? null, CARD),
    answer: "LM Studio",
  },
  {
    route: "/api/projects", what: "the projects", world: {
      projects: [{ id: "/home/u/src/splice", name: "splice", label: "splice", source: "repo", folder: null, root: "/home/u/src/splice", compaction: [] }],
    },
    look: (page) => page.locator('[data-act="view"][data-v="projects"]').click(),
    read: (page) => page.evaluate(() => [...document.querySelectorAll(".proj-list .pname")].map((n) => n.textContent)),
    answer: ["splice"],
  },
  {
    // alpha holds its own Requests at once in splice.toml, which its pane lists under its own settings
    route: "/api/config", what: "the settings", world: { own: { alpha: { maxInflight: 4 } } },
    look: async (page) => {
      await page.locator(`${CARD} .chip`).click();
      await page.locator(".pane").waitFor({ timeout: 5_000 });
    },
    read: (page) => page.evaluate(() => [...document.querySelectorAll(".pane .kn .lbl[data-row]")].map((l) => l.textContent)),
    answer: ["Requests at once"],
  },
];
for (const list of LISTS) {
  test(`A failed read of ${list.what} is read again at the next tick, never kept for a minute (${list.route})`, async () => {
    Object.assign(world, list.world);
    const page = await open();
    try {
      // the minute's read of the lists: this one answers 500, alone, while every other read answers
      world.fail.add(list.route);
      await stepUntilAsked(page, list.route);
      world.fail.clear();
      await step(page, 5_000);
      await list.look?.(page);
      expect(await showing(page, list.read, list.answer)).toEqual(list.answer);
    } finally {
      await page.close();
    }
  }, 60_000);
}

test("A command opened while a tick's reads are out keeps its pane as it was drawn", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    // started from a terminal on a new address in splice.toml; the tick sees it move, and its sessions answer slowly
    const slow = hold("/api/sessions");
    world.running = true;
    world.url = "https://api2.alpha.example";
    await page.clock.runFor(5_000);
    await slow.asked;
    // he opens alpha while that round is out: its pane is drawn from what the page holds, and he touches nothing
    await page.locator(`${CARD} .chip`).click();
    await page.locator(".pane").waitFor({ timeout: 5_000 });
    slow.release();
    await settled(page);
    expect(await page.locator(".pane").count()).toBe(1);
    expect(await page.locator('.pane [data-act="save"]').count()).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("A tick waits for the round still out, so a slow splice is read at its own pace", async () => {
  world.running = true;
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["ready", "Alpha One"]);
    // the tick's read of the sessions answers slowly, which holds its round open
    const slow = hold("/api/sessions");
    await page.clock.runFor(5_000);
    await slow.asked;
    // a session starts on alpha, and the next tick comes while that round is still out: the page asks nothing, so the
    // card cannot know of the session yet
    world.sessions = [{ session_id: "s-1", head: "alpha", status: "working" }];
    await page.clock.runFor(5_000);
    await settled(page, 1);
    expect(await cardOf(page)).toEqual(["ready", "Alpha One"]);
    slow.release();
    await settled(page);
    expect(await cardReading(page, ["working", "Alpha One"])).toEqual(["working", "Alpha One"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("Models left while its first read is out leaves nothing of itself in the tab", async () => {
  const page = await browser.newPage({ viewport: { width: 1024, height: 900 } });
  try {
    // a page this tab has already shown, so the way back to it from Models is quick
    await page.goto(`http://127.0.0.1:${server.port}/compare.html`);
    const toModels = page.locator('.side .nav a[href="models.html"]');
    await toModels.waitFor({ timeout: 15_000 });
    const slow = hold("/api/claude-head");
    await toModels.click();
    await slow.asked;
    await page.evaluate(() => {
      (window as unknown as { left: WeakRef<Element> }).left = new WeakRef(document.getElementById("models") as Element);
    });
    await page.locator('.side .nav a[href="compare.html"]').click();
    await page.waitForURL(/compare\.html$/);
    slow.release();
    await settled(page);
    const cdp = await page.context().newCDPSession(page);
    await cdp.send("HeapProfiler.collectGarbage");
    expect(await page.evaluate(() => (window as unknown as { left: WeakRef<Element> }).left.deref() === undefined)).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);

test("Start on a stopped command's card brings it to ready at once", async () => {
  const page = await open();
  try {
    expect(await cardOf(page)).toEqual(["Not started", "Alpha One"]);
    await page.locator(`${CARD} [data-act="fix"]`).click();
    await settled(page);
    expect(await cardReading(page, ["ready", "Alpha One"])).toEqual(["ready", "Alpha One"]);
  } finally {
    await page.close();
  }
}, 30_000);
