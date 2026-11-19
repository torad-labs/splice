// What the console-night-deck checks share: a stub daemon that serves this checkout's console folder by the names
// ConsoleMount serves (a plain name, or one under fonts/ or brand/) and answers /api/* from made-up rows, the way
// tools/console's tests read the pages; Chromium at the two widths the feature is judged at; and the frame every
// Night Deck page shares (the six tabs, which rails and composer it has, and how it scales from 1440 to 3840 px).
//
// Every value here is made up (the "acme" world). Nothing comes from anyone's machine.

import { chromium, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export const ROOT = spawnSync("git", ["rev-parse", "--show-toplevel"], { cwd: import.meta.dir, encoding: "utf8" }).stdout.trim();
export const CONSOLE = join(ROOT, "app/src/main/resources/console");
export const FIXTURES = join(import.meta.dir, "..", "fixtures");

/** The approved nav, in order. Each tab links to its page by this name, lower-cased, plus .html. */
export const SIX = ["Sessions", "Accounts", "Teams", "Providers", "Activity", "Settings"] as const;
/** The two widths every page is judged at. */
export const NARROW = { width: 1440, height: 900 };
export const WIDE = { width: 3840, height: 2160 };

/** What the stub answers an /api/* path with: a value (sent as JSON), a Response, or a function of the request. */
export type Answer = unknown | ((req: Request, url: URL, body: unknown) => unknown | Response | Promise<unknown | Response>);

export interface Seen {
  method: string;
  path: string;
  query: string;
  body: unknown;
}

const ASSET = /^((fonts|brand)\/)?[a-z0-9][a-z0-9-]*\.(html|css|js|woff2|svg|png)$/;
const TYPES: Record<string, string> = {
  html: "text/html; charset=utf-8", css: "text/css", js: "text/javascript", woff2: "font/woff2", svg: "image/svg+xml", png: "image/png",
};

export interface Daemon {
  url: string;
  seen: Seen[];
  answers: Record<string, Answer>;
  stop(): void;
}

/** A stub daemon: the console's files from this checkout, /api/* from [answers] (exact path, query ignored; "*" answers any other path), and {}
 *  for any other /api/* path, as pages-load.test.ts answers. Paths under /probe/ are served from this folder's fixtures. */
export function daemon(answers: Record<string, Answer> = {}): Daemon {
  const seen: Seen[] = [];
  const server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    async fetch(req) {
      const url = new URL(req.url);
      const path = url.pathname;
      if (path.startsWith("/api/")) {
        const text = req.method === "GET" || req.method === "HEAD" ? "" : await req.text();
        let body: unknown = text;
        try { body = text ? JSON.parse(text) : null; } catch { /* kept as text */ }
        seen.push({ method: req.method, path, query: url.search, body });
        const a = path in d.answers ? d.answers[path] : d.answers["*"];
        const out = typeof a === "function" ? await (a as (r: Request, u: URL, b: unknown) => unknown)(req, url, body) : a;
        if (out instanceof Response) return out;
        return Response.json(out ?? {});
      }
      if (path.startsWith("/probe/")) {
        const file = join(FIXTURES, path.slice("/probe/".length));
        return existsSync(file) ? new Response(readFileSync(file), { headers: { "content-type": TYPES.html! } }) : new Response("no", { status: 404 });
      }
      const name = path.slice(1);
      const file = join(CONSOLE, name);
      if (!ASSET.test(name) || !existsSync(file)) return new Response("", { status: 404 });
      return new Response(readFileSync(file), { headers: { "content-type": TYPES[name.split(".").pop()!]! } });
    },
  });
  const d: Daemon = { url: `http://127.0.0.1:${server.port}`, seen, answers, stop: () => server.stop(true) };
  return d;
}

export async function browser(): Promise<Browser> {
  return chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}

export interface Opened {
  ctx: BrowserContext;
  page: Page;
  errors: string[];
  close(): Promise<void>;
}

/** [file] on the stub at [size], in [theme] (saved the way the console saves it), with its page errors collected. */
export async function open(b: Browser, d: Daemon, file: string, size = NARROW, theme: "night" | "day" = "night"): Promise<Opened> {
  const ctx = await b.newContext({ viewport: size });
  await ctx.addInitScript((t) => { try { localStorage.setItem("splice-console-theme", t); } catch { /* storage off */ } }, theme);
  const page = await ctx.newPage();
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(`${d.url}/${file}`, { waitUntil: "load" });
  await page.waitForTimeout(1500);
  return { ctx, page, errors, close: () => ctx.close() };
}

export interface Frame {
  tabs: { text: string; href: string | null; current: boolean }[];
  navH: number;
  rootPx: number;
  railL: boolean;
  railR: boolean;
  composer: boolean;
  theme: string | undefined;
  scrollW: number;
  innerW: number;
}

/** What the shell drew: the nav's tabs, the rails and composer that show, the root size, and any sideways overflow. */
export async function frame(page: Page): Promise<Frame> {
  return page.evaluate(() => {
    const shown = (el: Element | null) => !!el && (el as HTMLElement).getClientRects().length > 0 && getComputedStyle(el).visibility !== "hidden" && getComputedStyle(el).display !== "none";
    const nav = document.querySelector("nav.nav");
    return {
      tabs: [...document.querySelectorAll("nav.nav a.tab")].map((a) => ({ text: (a.textContent || "").trim(), href: a.getAttribute("href"), current: a.getAttribute("aria-current") === "page" })),
      navH: nav ? nav.getBoundingClientRect().height : 0,
      rootPx: parseFloat(getComputedStyle(document.documentElement).fontSize),
      railL: shown(document.querySelector("aside.rail.rail-l")),
      railR: shown(document.querySelector("aside.rail.rail-r")),
      composer: shown(document.querySelector(".comp")),
      theme: document.documentElement.dataset.theme,
      scrollW: document.documentElement.scrollWidth,
      innerW: innerWidth,
    };
  });
}

export interface Want {
  /** The tab marked current, or null for a page that is not one of the six. */
  current: (typeof SIX)[number] | null;
  railL: boolean;
  railR: boolean;
  composer: boolean;
}

/** The page at 1440 and at 3840 (night), and once in day: no page error, the six tabs in order with [want.current]
 *  marked, the rails and composer [want] names and no others, nothing past the right edge, and the 3840 page drawn as
 *  a larger 1440 page (the nav twice as tall, within 10%). */
export async function expectFrame(b: Browser, d: Daemon, file: string, want: Want): Promise<void> {
  const seen: Frame[] = [];
  for (const [size, theme] of [[NARROW, "night"], [WIDE, "night"], [NARROW, "day"]] as const) {
    const o = await open(b, d, file, size, theme);
    try {
      const f = await frame(o.page);
      expect(o.errors, `${file} at ${size.width} (${theme}) threw`).toEqual([]);
      expect(f.tabs.map((t) => t.text)).toEqual([...SIX]);
      expect(f.tabs.map((t) => t.href)).toEqual(SIX.map((s) => `${s.toLowerCase()}.html`));
      expect(f.tabs.filter((t) => t.current).map((t) => t.text)).toEqual(want.current ? [want.current] : []);
      expect({ railL: f.railL, railR: f.railR, composer: f.composer }).toEqual({ railL: want.railL, railR: want.railR, composer: want.composer });
      expect(f.scrollW, `${file} overflows sideways at ${size.width}`).toBeLessThanOrEqual(f.innerW);
      expect(f.theme).toBe(theme);
      seen.push(f);
    } finally {
      await o.close();
    }
  }
  const [narrow, wide] = seen as [Frame, Frame];
  expect(narrow.navH).toBeGreaterThan(0);
  const ratio = wide.navH / narrow.navH;
  expect(ratio, "at 3840 the page should read as a larger 1440 page").toBeGreaterThan(1.8);
  expect(ratio).toBeLessThan(2.2);
}

/** The open glass panel (an inspect lens), once it has grown in. */
export async function lens(page: Page) {
  const l = page.locator(".lens:not([hidden])").first();
  await expect(l).toBeVisible({ timeout: 5_000 });
  return l;
}

// ---------- the made-up acme world, in the shapes today's API answers (as tools/console's stubs give them) ----------

export const NOW = Date.now();
export const MIN = 60_000;
export const HOUR = 60 * MIN;
const sec = (ms: number) => Math.floor(ms / 1000);

export const HEADS = {
  heads: [
    { key: "claude-splice", label: "claude", state: "running", port: 41001, gate: { inflight: 1, queued: 0, max: 12 } },
    { key: "acme", label: "claude-acme", state: "running", port: 41002, gate: { inflight: 0, queued: 0, max: 12 } },
  ],
};
export const STATUS = {
  version: "9.8.7",
  registry: [
    { key: "claude-splice", label: "claude", family: "anthropic", authKind: "client" },
    { key: "acme", label: "claude-acme", family: "openai", authKind: "api-key" },
  ],
};
export const MODELS = {
  heads: [
    { head: "claude-splice", models: [{ id: "claude-opus-5-5", label: "Opus 5.5" }] },
    { head: "acme", models: [{ id: "acme-large-2", label: "Acme Large 2" }] },
  ],
};

const proj = (name: string) => ({ id: `/work/${name}`, name, label: name, source: "folder", folder: "/work" });
export const session = (id: string, name: string, project: string, over: Record<string, unknown> = {}) => ({
  session_id: id, name, head: "claude-splice", route: "head", availability: "live", status: "idle", cwd: `/work/${project}`, title: null,
  last: { role: "assistant", tool: null, text: `${name} finished its last step.`, ts: NOW - 2 * MIN },
  updated_at: new Date(NOW - 2 * MIN).toISOString(), project: proj(project), ...over,
});
export const SESSIONS = {
  reading: true,
  sessions: [
    session("aaaa1111", "api-fixer", "harbor", { status: "working" }),
    session("bbbb2222", "map-builder", "harbor"),
    session("cccc3333", "docs-writer", "lighthouse"),
  ],
};
const slot = (id: string, role: string, s: string, lead = false) => ({ id, role, head: "claude-splice", lead, session: s, project: proj("harbor") });
export const TEAMS = {
  teams: [
    { id: "t1", name: "harbor-fix", goal: "Ship the harbor map", repo: "/work/harbor", project: proj("harbor"), archived: false,
      slots: [slot("lead", "Lead", "aaaa1111", true), slot("builder", "Builder", "bbbb2222")] },
    { id: "t2", name: "old-crew", goal: "Retire the old dock", repo: "/work/dock", project: proj("dock"), archived: true, slots: [] },
  ],
};

const windows = {
  five_hour_used_percent: 20, five_hour_reset_epoch_seconds: sec(NOW + 2 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 30, seven_day_reset_epoch_seconds: sec(NOW + 50 * HOUR), seven_day_window_seconds: 604_800,
  observed_at_epoch_seconds: sec(NOW - MIN),
};
const login = (name: string, label: string) => ({
  label, selector_key: label, display_name: name, ref: label.padEnd(8, "0").slice(0, 8), heads: ["claude-splice"], provider: "anthropic", kind: "client",
  credential_present: true, identity_verified: true, can_rename: true, ...windows,
});
export const ACCOUNTS = { accounts: [login("Acme work", "account-2"), login("Acme home", "account-3")] };
export const ORDER = { order: ["account-2", "account-3"], effective_order: ["account-2", "account-3"], next_target: "account-2", following_target: "account-3" };

/** Requests on the acme command: one that answered and one the provider refused for rate. */
export const ROWS = [
  { turn: "r-ok", turn_id: "r-ok", ts: NOW - 3 * MIN, outcome: "ok", model: "acme-large-2", session: "aaaa1111", session_id: "aaaa1111",
    total: 4_200, first_byte: 1_100, arrival_to_upstream_write_ms: 40, attempts: 1, retries: 0, in_tokens: 12_000, cached_tokens: 9_000,
    cache_write_tokens: 500, out_tokens: 800, cost_usd: 0.03, plan_value_usd: null, account_label: null, req_bytes: 48_000 },
  { turn: "r-429", turn_id: "r-429", ts: NOW - MIN, outcome: "error:rate-limited", model: "acme-large-2", session: "bbbb2222", session_id: "bbbb2222",
    total: 900, first_byte: null, arrival_to_upstream_write_ms: 35, attempts: 1, retries: 0, in_tokens: 0, cached_tokens: 0,
    cache_write_tokens: 0, out_tokens: 0, cost_usd: 0, plan_value_usd: null, account_label: null, req_bytes: 47_000 },
];
/** /api/perf/turns, per head: acme's rows, and none for any other head. */
export const perfTurns = (_req: Request, url: URL) => {
  const head = url.searchParams.get("head");
  const heads = HEADS.heads.filter((h) => !head || h.key === head).map((h) => {
    const rows = h.key === "acme" ? ROWS : [];
    return { key: h.key, label: h.key, count: rows.length, returned: rows.length, truncated: false, rows, usage: { models: [], accounts: [], sessions: [], days: [] } };
  });
  return { heads };
};

/** The answers every page can share. A check adds or replaces its own. */
export function world(over: Record<string, Answer> = {}): Record<string, Answer> {
  return {
    "/api/heads": HEADS,
    "/api/status": STATUS,
    "/api/models": MODELS,
    "/api/sessions": SESSIONS,
    "/api/teams": TEAMS,
    "/api/accounts": ACCOUNTS,
    "/api/auth/claude-splice/order": ORDER,
    "/api/perf/turns": perfTurns,
    "/api/upgrade": { installed: "9.8.7", latest: "9.8.7", rollback_target: null },
    ...over,
  };
}
