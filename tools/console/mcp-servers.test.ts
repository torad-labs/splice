// The per-server MCP rows on Settings (hitstop's drawing, fin's words), through the real page against a stub daemon in
// the shape the daemon sends now (a `why` key and value per server it does not share, the commands using each shared
// one) and in the shape it sent before (no keys, no commands). A shared server reads its commands and its last call under
// the columns "Used by" and "Last call", said once over the list; a server splice cannot share sits on the card of its one
// word for why; a web server shows its address without the part of a URL that can hold a secret; and Test posts to the
// server's route and says what came back in fin's words.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const lastCall = new Date(2026, 9, 12, 4, 30).getTime();
const HOSTED = new Set(["context7", "linear", "playwright"]);
const own = (name: string, file = "/home/u/.claude.json", kind = "global", extra: object = {}) =>
  ({ name, kind, source_file: file, disposition: HOSTED.has(name) && kind === "global" && file.endsWith("/u/.claude.json") ? "migrated" : "excluded", reason: "", ...extra });
const NEW = {
  hosting: true,
  servers: {
    context7: { eligible: true, hosted: true, commands: ["claude-splice", "claudex"], sessions: 2, last_activity: lastCall },
    linear: { eligible: true, hosted: false, commands: [], sessions: 0 },
    playwright: { eligible: true, hosted: true, commands: ["claude-splice"], sessions: 1 },
    sentry: { eligible: false, reason: "url transport", why: "transport", value: "https://mcp.sentry.dev/mcp?token=SECRET123" },
    postgres: { eligible: false, reason: "variable", why: "variable", value: "${DATABASE_URL}" },
    filesystem: { eligible: false, reason: "relative", why: "relative_path", value: "./src" },
    notes: { eligible: false, reason: "excluded by [daemon] mcp_hosting_exclude", why: "excluded" },
  },
  sources: {
    servers: [
      own("context7"), own("context7", "/home/u/.claude-claude-grok/.claude.json", "global", { disposition: "excluded", why: "until_restart" }),
      own("linear"), own("playwright"), own("sentry"), own("postgres"), own("filesystem"), own("notes"),
      own("github", "/home/u/.claude/plugins/cache/m/github/.mcp.json", "plugin_mcp_json", { disposition: "excluded", why: "plugin" }),
      own("memory", "/home/u/.claude-claudex/.claude.json", "global", { disposition: "excluded", why: "added_in" }),
    ],
  },
};
const OLD = {
  hosting: true,
  servers: {
    context7: { eligible: true, hosted: true, sessions: 1 },
    postgres: { eligible: false, reason: "has a variable" },
  },
  sources: { servers: [own("context7"), own("postgres")] },
};
let shape: object = NEW;
const posted: string[] = [];
const TESTS: Record<string, object> = {
  context7: { ok: true, started: false, round_trip_ms: 140, tools: 2 },
  linear: { ok: true, started: true, round_trip_ms: 2100, tools: 23 },
  playwright: { ok: false, started: false, round_trip_ms: 10000, error: "no reply", kind: "timeout" },
};
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    const url = new URL(req.url), path = url.pathname;
    if (req.method === "POST" && /^\/api\/mcp\/[^/]+\/test$/.test(path)) {
      const name = decodeURIComponent(path.split("/")[3] ?? "");
      posted.push(name);
      return Response.json(TESTS[name] ?? { ok: false, started: false, round_trip_ms: 5, error: "x", kind: "other" });
    }
    if (path === "/api/mcp") return Response.json(shape);
    if (path === "/api/heads") return Response.json({ heads: [{ key: "claude-splice", label: "claude-splice" }, { key: "claudex", label: "claudex" }] });
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});
let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});
async function openMcp(width = 1440) {
  const page = await browser.newPage({ viewport: { width, height: 1400 } });
  await page.goto(`http://127.0.0.1:${server.port}/settings.html`);
  await page.locator('[data-open="mcp"]').first().click();
  await page.locator(".srvs .srv").first().waitFor({ timeout: 15_000 });
  return page;
}
const row = (page: Awaited<ReturnType<typeof openMcp>>, name: string) => page.locator(".srv", { has: page.locator(`.nm:text-is("${name}")`) });
// a topic's part by its kicker: "Sharing", "Shared", "Not shared"
const section = (page: Awaited<ReturnType<typeof openMcp>>, kicker: string) =>
  page.locator("section.psec", { has: page.locator(`.sechead > .kicker:text-is("${kicker}")`) });
// the reason a server is not shared is said once, on the head of its reason's card (the Settings rebuild, Oct 26)
const reasonOf = async (page: Awaited<ReturnType<typeof openMcp>>, name: string) =>
  (await page.locator(".mcard", { has: row(page, name) }).locator(".mtop .mw").innerText()).trim();
const flat = (s: string) => s.replace(/\s+/g, " ").trim();

// The columns are named once over the rows and each reason once on its card (Settings' goal: "A row may change shape, a
// word may become ... a header said once. Nothing below goes away"): "Used by" and "Last call" head the Shared list, a
// server's commands and its last call sit in those columns, and a server splice cannot share sits on its reason's card.
test("a shared server reads its commands under Used by and its last call; one splice cannot share reads one word, on its reason's card", async () => {
  shape = NEW;
  const page = await openMcp();
  try {
    const names = await page.locator(".srv .nm").allInnerTexts();
    expect(names.slice(0, 3).sort()).toEqual(["context7", "linear", "playwright"]);
    expect(names.indexOf("sentry")).toBeLessThan(names.indexOf("postgres"));
    const head = await section(page, "Shared").locator(".srvhead").innerText();
    expect(head).toContain("Used by");
    expect(head).toContain("Last call");
    const uses = await row(page, "context7").locator(".fig.uses").innerText();
    expect(uses).toContain("claude-splice");
    expect(uses).toContain("claudex");
    // a command still on an older copy of the config joins Used by, and takes the server at its next session (fin, Oct 12)
    expect(uses).toContain("claude-grok");
    expect(uses).toContain("Next session");
    // its last call in the Last call column, as the browser's own clock reads it: another day leads with its date
    const last = await row(page, "context7").locator(".fig.t").last().innerText();
    const [day, clock] = await page.evaluate((ms) => [
      new Date(ms).toLocaleDateString("en-US", { month: "short", day: "numeric" }),
      new Date(ms).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" }),
    ], lastCall);
    expect(last).toContain(day);
    expect(last).toContain(clock);
    expect(await row(page, "linear").locator(".fig.uses .chip").count()).toBe(0);
    const words: Record<string, string> = {
      postgres: "Per session", filesystem: "Per project", github: "Plugin-owned", memory: "Added in",
    };
    for (const [name, word] of Object.entries(words)) {
      expect(await reasonOf(page, name)).toBe(word);
      expect(await row(page, name).locator('[data-act="mtest"]').count()).toBe(0);
    }
    expect(await row(page, "postgres").innerText()).toContain("${DATABASE_URL}");
    expect(await row(page, "memory").innerText()).toContain("claudex");
    expect(await reasonOf(page, "notes")).toBe("Set by you");
    expect(await section(page, "Not shared").locator(".srv", { has: page.locator('.nm:text-is("notes")') }).count()).toBe(1);
    expect(await row(page, "notes").locator('[data-act="mtest"]').count()).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);

// "Shared" is the kicker of the list a web server sits in, not a word on its row (Settings' goal, as above)
test("a web server shows its address without a query or login, sits in the Shared list, and keeps its Test", async () => {
  shape = NEW;
  const page = await openMcp();
  try {
    const text = await row(page, "sentry").innerText();
    expect(text).toContain("mcp.sentry.dev/mcp");
    expect(text).not.toContain("SECRET123");
    expect(await section(page, "Shared").locator(".srv", { has: page.locator('.nm:text-is("sentry")') }).count()).toBe(1);
    expect(await row(page, "sentry").locator('[data-act="mtest"]').count()).toBe(1);
  } finally {
    await page.close();
  }
}, 30_000);

test("Test posts to the server's route and says what came back: time and tools, Started, Given up", async () => {
  shape = NEW;
  posted.length = 0;
  const page = await openMcp();
  try {
    const said = async (name: string, expected: string) => {
      await row(page, name).locator('[data-act="mtest"]').click();
      await row(page, name).locator(".said", { hasText: expected }).waitFor({ timeout: 10_000 });
    };
    await said("context7", "140 ms · 2 tools");
    await said("linear", "Started · 2.1 s · 23 tools");
    await said("playwright", "Given up");
    expect(posted).toEqual(["context7", "linear", "playwright"]);
  } finally {
    await page.close();
  }
}, 30_000);

// the old shape carries no key for why a server is not shared, so it sits in Not shared under "No reason given", never
// a guessed reason; with no commands sent, its Used by column holds no command
test("the shape the daemon sent before still draws: no keys sits in Not shared with no reason guessed, no commands leaves Used by empty", async () => {
  shape = OLD;
  const page = await openMcp();
  try {
    expect(await row(page, "context7").locator(".fig.uses .chip").count()).toBe(0);
    expect(await section(page, "Not shared").locator(".srv", { has: page.locator('.nm:text-is("postgres")') }).count()).toBe(1);
    expect(await reasonOf(page, "postgres")).toBe("No reason given");
    expect(await row(page, "context7").locator('[data-act="mtest"]').count()).toBe(1);
  } finally {
    await page.close();
  }
}, 30_000);

// the count is the topic's one figure over its whole, "N of M servers shared", where the Servers header said "N of M
// shared"; a server whose key has no word of its own sits in Not shared on the card the switch's state names
test("the Sharing figure counts the shared servers against all of them, and a server with no word of its own sits only in Not shared", async () => {
  shape = { ...NEW, hosting: false, servers: { ...NEW.servers, postgres: { eligible: false, reason: "off", why: "hosting_disabled" } } };
  const page = await openMcp();
  try {
    const top = section(page, "Sharing").locator(".pd-top");
    expect(flat(await top.locator(".pd-rate").innerText())).toMatch(/^\d+$/);
    expect(flat(await top.locator(".pd-failed").innerText())).toMatch(/^of \d+ servers shared$/);
    expect(await section(page, "Not shared").locator(".srv", { has: page.locator('.nm:text-is("postgres")') }).count()).toBe(1);
    expect(await reasonOf(page, "postgres")).toBe("Sharing off");
    const text = await row(page, "postgres").innerText();
    expect(text).not.toContain("Per agent");
    expect(text).not.toContain("Per session");
  } finally {
    await page.close();
  }
}, 30_000);

// The Right now tiles became the Sharing topic's figure and bar, and the Shared list's kicker (Settings' report, Oct 26):
// Set up is the whole the figure counts against, Hosted by splice and At a web address are the bar's parts, each with its
// figure and no sub-line, Running is the count on the Shared kicker with a live lamp on each running row, and each hosted
// server is a row of the Shared list.
test("Sharing holds Set up as its whole, Hosted by splice as a part with no sub-line, and Shared counts what runs", async () => {
  shape = NEW;
  const page = await openMcp();
  try {
    const top = section(page, "Sharing").locator(".pd-top");
    expect(flat(await top.locator(".pd-rate").innerText())).toBe("4");
    expect(flat(await top.locator(".pd-failed").innerText())).toBe("of 9 servers shared");
    const part = (word: string) => section(page, "Sharing").locator(".pd-pop").locator(".pd-seg > span, .pd-lb", { hasText: word });
    expect(flat(await part("Hosted by splice").innerText())).toBe("Hosted by splice 3");
    expect(flat(await part("At a web address").innerText())).toBe("At a web address 1");
    expect(await section(page, "Sharing").innerText()).not.toContain("process");
    const list = await section(page, "Shared").locator(".srv .nm").allInnerTexts();
    for (const name of ["context7", "linear", "playwright"]) expect(list).toContain(name);
    expect(flat(await section(page, "Shared").locator(".sechead .hn").innerText())).toBe("2 running · 3 sessions");
    for (const name of ["context7", "playwright"]) expect(await row(page, name).locator(".tlamp.working").count()).toBe(1);
    expect(await row(page, "linear").locator(".tlamp.working").count()).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);
