// The per-server MCP rows on Settings (hitstop's drawing, fin's words), through the real page against a stub daemon in
// the shape the daemon sends now (a `why` key and value per server it does not share, the commands using each shared
// one) and in the shape it sent before (no keys, no commands). A shared server reads "Used by" its commands and its last
// call, a server splice cannot share reads one word for why, a web server shows its address without the part of a URL
// that can hold a secret, and Test posts to the server's route and says what came back in fin's words.
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

test("a shared server reads Used by its commands and its last call; one splice cannot share reads one word", async () => {
  shape = NEW;
  const page = await openMcp();
  try {
    const names = await page.locator(".srv .nm").allInnerTexts();
    expect(names.slice(0, 3).sort()).toEqual(["context7", "linear", "playwright"]);
    expect(names.indexOf("sentry")).toBeLessThan(names.indexOf("postgres"));
    const shared = await row(page, "context7").innerText();
    expect(shared).toContain("Used by");
    expect(shared).toContain("claude-splice");
    expect(shared).toContain("claudex");
    // a command still on an older copy of the config joins Used by, and takes the server at its next session (fin, Oct 12)
    expect(shared).toContain("claude-grok");
    expect(shared).toContain("Next session");
    expect(shared).toContain("Last call");
    expect(await row(page, "linear").innerText()).not.toContain("Used by");
    const words: Record<string, string> = {
      postgres: "Per session", filesystem: "Per project", github: "Plugin-owned", memory: "Added in",
    };
    for (const [name, word] of Object.entries(words)) {
      const text = await row(page, name).innerText();
      expect(text).toContain(word);
      expect(await row(page, name).locator('[data-act="mtest"]').count()).toBe(0);
    }
    expect(await row(page, "postgres").innerText()).toContain("${DATABASE_URL}");
    expect(await row(page, "memory").innerText()).toContain("claudex");
    const set = await row(page, "notes").innerText();
    expect(set).toContain("Set by you");
    expect(set).toContain("Not shared");
    expect(await row(page, "notes").locator('[data-act="mtest"]').count()).toBe(0);
  } finally {
    await page.close();
  }
}, 30_000);

test("a web server shows its address without a query or login, and keeps its Test", async () => {
  shape = NEW;
  const page = await openMcp();
  try {
    const text = await row(page, "sentry").innerText();
    expect(text).toContain("mcp.sentry.dev/mcp");
    expect(text).not.toContain("SECRET123");
    expect(text).toContain("Shared");
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

test("the shape the daemon sent before still draws: no keys reads Not shared, no commands reads no Used by", async () => {
  shape = OLD;
  const page = await openMcp();
  try {
    expect(await row(page, "context7").innerText()).not.toContain("Used by");
    expect(await row(page, "postgres").innerText()).toContain("Not shared");
    expect(await row(page, "context7").locator('[data-act="mtest"]').count()).toBe(1);
  } finally {
    await page.close();
  }
}, 30_000);

test("the Servers header counts the shared servers against all of them, and a server with no word reads only Not shared", async () => {
  shape = { ...NEW, hosting: false, servers: { ...NEW.servers, postgres: { eligible: false, reason: "off", why: "hosting_disabled" } } };
  const page = await openMcp();
  try {
    expect(await page.locator(".sub h3 .hn", { hasText: "of" }).innerText()).toMatch(/^\d+ of \d+ shared$/);
    const text = await row(page, "postgres").innerText();
    expect(text).toContain("Not shared");
    expect(text).not.toContain("Per agent");
  } finally {
    await page.close();
  }
}, 30_000);

test("Right now holds Running, Set up and Hosted by splice with its chips, and no sub-line or Run by each session", async () => {
  shape = NEW;
  const page = await openMcp();
  try {
    const tiles = (await page.locator(".figs3 .fig3 .what").allInnerTexts()).map((s) => s.trim());
    expect(tiles).toEqual(["Running", "Set up", "Hosted by splice"]);
    const hosted = await page.locator(".fig3", { hasText: "Hosted by splice" }).innerText();
    for (const name of ["context7", "linear", "playwright"]) expect(hosted).toContain(name);
    expect(hosted).not.toContain("process");
    expect(hosted).not.toContain("session");
  } finally {
    await page.close();
  }
}, 30_000);
