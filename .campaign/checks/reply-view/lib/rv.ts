// What the reply-view checks share: a stub daemon that serves this checkout's reader (app/src/main/resources/console/
// reply-view/, at /reply-view/...) and the console's own assets beside it, the check fixtures at /probe/..., made-up
// transcripts at /api/sessions/{id}/transcript (paged the way the real route pages them) and a made-up live feed at
// /api/sessions/{id}/live (text/event-stream, driven by the test), every HTML page under a strict Content-Security-Policy;
// Chromium at a phone width and at 1440 px, night and day, collecting page errors and CSP violations.
//
// Every value here is made up. Nothing comes from anyone's machine or conversation.

import { chromium, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

export const ROOT = spawnSync("git", ["rev-parse", "--show-toplevel"], { cwd: import.meta.dir, encoding: "utf8" }).stdout.trim();
export const CONSOLE = join(ROOT, "app/src/main/resources/console");
export const READER = join(CONSOLE, "reply-view");
export const FIXTURES = join(import.meta.dir, "..", "fixtures");

/** The widths every check and screenshot is judged at. */
export const PHONE = { width: 375, height: 812 };
export const DESK = { width: 1440, height: 900 };
export const THEMES = ["night", "day"] as const;
export type Theme = (typeof THEMES)[number];

/** The policy every HTML page is served under: nothing inline, nothing from elsewhere, nothing evaluated. */
export const CSP =
  "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; " +
  "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

/** The console key the checks' browser holds (made up), stored where the console keeps it. */
export const KEY = "console-key-for-checks";
export const KEY_STORE = "splice-console-access-key";
export const THEME_STORE = "splice-console-theme";

const TYPES: Record<string, string> = {
  html: "text/html; charset=utf-8", css: "text/css; charset=utf-8", js: "text/javascript; charset=utf-8", woff2: "font/woff2",
  svg: "image/svg+xml", png: "image/png",
};
const CONSOLE_ASSET = /^((fonts|brand)\/)?[a-z0-9][a-z0-9-]*\.(html|css|js|woff2|svg|png)$/;
const READER_ASSET = /^reply-view\/[a-z0-9][a-z0-9-]*\.(html|css|js)$/;
const FIXTURE = /^[a-z0-9][a-z0-9-]*\.(html|css|js)$/;

/** One transcript message, in the shape GET /api/sessions/{id}/transcript sends it. */
export interface Msg {
  index: number;
  role: "user" | "assistant" | "system" | "tool" | "peer";
  text: string;
  ts?: number;
  tool?: string;
  tool_use_id?: string;
  kind?: string;
  from?: string;
  message?: string;
  model?: string;
  thinking?: string;
  compact?: boolean;
}

/** One feed event, as the reader is handed it: the SSE event name and its parsed data line. */
export interface Ev {
  event: string;
  data: Record<string, unknown>;
}

export interface Seen {
  method: string;
  path: string;
  query: string;
  auth: string | null;
}

export interface Stub {
  url: string;
  seen: Seen[];
  /** The transcript each session id answers; a test may push to it while the page runs. */
  transcripts: Record<string, Msg[]>;
  /** A status the transcript route answers instead (401, 404, ...), per session id. */
  transcriptStatus: Record<string, number>;
  /** A status the live route answers instead of a stream, per session id. */
  liveStatus: Record<string, number>;
  /** The `live` list the next `hello` names, per session id. */
  inFlight: Record<string, string[]>;
  /** Other /api/* paths and what they answer (sent as JSON). */
  answers: Record<string, unknown>;
  /** Send one event to every follower of [session]. */
  push(session: string, event: string, data: Record<string, unknown>): void;
  /** Send a `: ping` comment to every follower of [session]. */
  ping(session: string): void;
  /** Close every follower's stream of [session], as the daemon does to a slow reader. */
  drop(session: string): void;
  /** How many followers of [session] are connected now. */
  followers(session: string): number;
  stop(): void;
}

const enc = new TextEncoder();

/** The page of [all] the real route would answer for this query: `before=end` (or an `earlier` cursor) reads the newest
 *  [limit] messages back and hands an `earlier` cursor while older ones remain; `cursor=after:<index>` reads from the
 *  message with that index to the end; anything else reads forward from the start. */
function page(all: Msg[], url: URL): Record<string, unknown> {
  const limit = Number(url.searchParams.get("limit") ?? 100) || 100;
  const before = url.searchParams.get("before");
  const cursor = url.searchParams.get("cursor");
  const base = { path: "/work/made-up/transcript.jsonl", moves: [], unmoved: [], unparseable_lines: 0 };
  if (cursor?.startsWith("after:")) {
    const at = Number(cursor.slice("after:".length));
    return { ...base, messages: all.filter((m) => m.index >= at), more: false };
  }
  if (before !== null) {
    const end = before === "end" ? all.length : Number(before.replace(/^e:/, ""));
    const start = Math.max(0, end - limit);
    return { ...base, messages: all.slice(start, end), earlier: start > 0 ? `e:${start}` : null };
  }
  return { ...base, messages: all.slice(0, limit), next: null };
}

/** The stub daemon. */
export function stub(o: { transcripts?: Record<string, Msg[]>; answers?: Record<string, unknown> } = {}): Stub {
  const seen: Seen[] = [];
  const streams = new Map<string, Set<ReadableStreamDefaultController<Uint8Array>>>();
  const of = (s: string) => {
    let set = streams.get(s);
    if (!set) streams.set(s, (set = new Set()));
    return set;
  };
  const send = (s: string, chunk: string) => {
    for (const c of [...of(s)]) {
      try {
        c.enqueue(enc.encode(chunk));
      } catch {
        of(s).delete(c);
      }
    }
  };
  const st: Stub = {
    url: "",
    seen,
    transcripts: o.transcripts ?? {},
    transcriptStatus: {},
    liveStatus: {},
    inFlight: {},
    answers: o.answers ?? {},
    push: (s, event, data) => send(s, `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
    ping: (s) => send(s, ": ping\n\n"),
    drop: (s) => {
      for (const c of [...of(s)]) {
        try {
          c.close();
        } catch { /* already closed */ }
      }
      of(s).clear();
    },
    followers: (s) => of(s).size,
    stop: () => server.stop(true),
  };
  const html = (body: BodyInit, type: string) =>
    new Response(body, { headers: { "content-type": type, ...(type.startsWith("text/html") ? { "content-security-policy": CSP } : {}) } });
  const server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    idleTimeout: 0,
    fetch(req) {
      const url = new URL(req.url);
      const path = url.pathname;
      if (path.startsWith("/api/")) {
        seen.push({ method: req.method, path, query: url.search, auth: req.headers.get("authorization") });
        const authed = req.headers.get("authorization") === `Bearer ${KEY}`;
        const tr = /^\/api\/sessions\/([^/]+)\/transcript$/.exec(path);
        if (tr) {
          const id = tr[1]!;
          const status = st.transcriptStatus[id] ?? (authed ? 0 : 401);
          if (status) return Response.json({ error: status === 401 ? "unauthorized" : "no transcript for this session id" }, { status });
          const all = st.transcripts[id];
          if (!all) return Response.json({ error: "no transcript for this session id" }, { status: 404 });
          return Response.json(page([...all].sort((a, b) => a.index - b.index), url));
        }
        const lv = /^\/api\/sessions\/([^/]+)\/live$/.exec(path);
        if (lv) {
          const id = lv[1]!;
          const status = st.liveStatus[id] ?? (authed ? 0 : 401);
          if (status) return Response.json({ error: status === 401 ? "unauthorized" : "not found" }, { status });
          let me: ReadableStreamDefaultController<Uint8Array> | null = null;
          const body = new ReadableStream<Uint8Array>({
            start(c) {
              me = c;
              of(id).add(c);
              c.enqueue(enc.encode(`event: hello\ndata: ${JSON.stringify({ session: id, live: st.inFlight[id] ?? [] })}\n\n`));
            },
            cancel() {
              if (me) of(id).delete(me);
            },
          });
          return new Response(body, { headers: { "content-type": "text/event-stream", "cache-control": "no-store" } });
        }
        if (!authed) return Response.json({ error: "unauthorized" }, { status: 401 });
        return Response.json(path in st.answers ? st.answers[path] : {});
      }
      if (path.startsWith("/probe/")) {
        const name = path.slice("/probe/".length);
        const file = join(FIXTURES, name);
        if (!FIXTURE.test(name) || !existsSync(file)) return new Response("no", { status: 404 });
        return html(readFileSync(file), TYPES[name.split(".").pop()!]!);
      }
      const name = path.slice(1);
      if (READER_ASSET.test(name) || CONSOLE_ASSET.test(name)) {
        const file = join(CONSOLE, name);
        if (existsSync(file)) return html(readFileSync(file), TYPES[name.split(".").pop()!]!);
      }
      return new Response("", { status: 404 });
    },
  });
  st.url = `http://127.0.0.1:${server.port}`;
  return st;
}

export async function browser(): Promise<Browser> {
  return chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}

export interface Opened {
  ctx: BrowserContext;
  page: Page;
  /** Uncaught page errors. */
  errors: string[];
  /** What the page's policy refused: "<directive> <blocked uri>". */
  csp(): Promise<string[]>;
  close(): Promise<void>;
}

/** [path] on the stub at [size] in [theme]: the browser prefers that colour scheme, holds the console key, and has the
 *  console's theme saved (only while unset, so a reload keeps what the page saved). Clipboard reads are allowed. */
export async function open(b: Browser, st: Stub, path: string, size = DESK, theme: Theme = "night", o: { key?: boolean } = {}): Promise<Opened> {
  const ctx = await b.newContext({ viewport: size, colorScheme: theme === "night" ? "dark" : "light" });
  await ctx.grantPermissions(["clipboard-read", "clipboard-write"], { origin: st.url });
  await ctx.addInitScript(
    ([t, key, keyStore, themeStore]) => {
      (window as unknown as { __csp: string[] }).__csp = [];
      document.addEventListener("securitypolicyviolation", (e) => {
        (window as unknown as { __csp: string[] }).__csp.push(`${e.violatedDirective} ${e.blockedURI}`);
      });
      try {
        if (localStorage.getItem(themeStore!) === null) localStorage.setItem(themeStore!, t!);
        if (key && localStorage.getItem(keyStore!) === null) localStorage.setItem(keyStore!, key);
      } catch { /* storage off */ }
    },
    [theme, o.key === false ? "" : KEY, KEY_STORE, THEME_STORE] as const,
  );
  const page = await ctx.newPage();
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(`${st.url}${path}`, { waitUntil: "load" });
  return {
    ctx,
    page,
    errors,
    csp: () => page.evaluate(() => (window as unknown as { __csp: string[] }).__csp),
    close: () => ctx.close(),
  };
}

/** The host fixture (fixtures/host.html), once its import of /reply-view/reply-view.js has run. */
export async function host(b: Browser, st: Stub, size = DESK, theme: Theme = "night"): Promise<Opened> {
  const o = await open(b, st, "/probe/host.html", size, theme);
  await o.page.waitForFunction(() => (window as unknown as { RV_READY?: boolean }).RV_READY === true, null, { timeout: 10_000 });
  return o;
}

/** Mount the reader in the host fixture's container with [history] (and [live], if any). */
export async function mount(page: Page, history: Msg[], live: Ev[] = []): Promise<void> {
  await page.evaluate(([h, l]) => {
    const w = window as unknown as { RV: { mount: (el: Element, o: unknown) => unknown; host: Element; view: unknown } };
    w.RV.view = w.RV.mount(w.RV.host, { history: h, live: l });
  }, [history, live] as const);
}

/** Hand the mounted reader more transcript messages. */
export async function addHistory(page: Page, messages: Msg[]): Promise<void> {
  await page.evaluate((m) => {
    (window as unknown as { RV: { view: { addHistory: (m: unknown) => void } } }).RV.view.addHistory(m);
  }, messages);
}

/** Hand the mounted reader feed events. */
export async function addLive(page: Page, events: Ev[]): Promise<void> {
  await page.evaluate((e) => {
    (window as unknown as { RV: { view: { addLive: (e: unknown) => void } } }).RV.view.addLive(e);
  }, events);
}

/** What a person can read in [selector] now (innerText: a closed fold's content is not drawn, so not read). */
export async function readable(page: Page, selector = ".rv"): Promise<string> {
  return page.evaluate((s) => (document.querySelector(s) as HTMLElement | null)?.innerText ?? "", selector);
}

/** Nothing past the right edge: the page does not scroll sideways, and no element drawn inside the reader reaches past
 *  the reader's own right edge, except inside a block that scrolls sideways by itself (code, a result). */
export async function spills(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const out: string[] = [];
    const de = document.documentElement;
    if (de.scrollWidth > innerWidth) out.push(`the page scrolls sideways: ${de.scrollWidth} > ${innerWidth}`);
    const root = document.querySelector(".rv");
    if (!root) return out;
    const edge = root.getBoundingClientRect().right + 1;
    const scrolls = (el: Element | null): boolean => {
      for (let p = el; p && p !== root; p = p.parentElement) {
        const ox = getComputedStyle(p).overflowX;
        if (ox === "auto" || ox === "scroll" || ox === "hidden" || ox === "clip") return true;
      }
      return false;
    };
    for (const el of root.querySelectorAll("*")) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.right <= edge) continue;
      if (scrolls(el.parentElement)) continue;
      out.push(`${el.tagName.toLowerCase()}.${(el as HTMLElement).className} reaches ${Math.round(r.right)} past ${Math.round(edge)}`);
      if (out.length > 5) break;
    }
    return out;
  });
}

// ---------- the made-up world ----------

export const NOW = Date.UTC(2026, 10, 19, 15, 0, 0);
export const MIN = 60_000;
export const SESSION = "aaaa1111";

const at = (i: number) => NOW - 60 * MIN + i * 10_000;
const msg = (index: number, role: Msg["role"], text: string, over: Partial<Msg> = {}): Msg => ({ index, role, text, ts: at(index), ...over });
const call = (index: number, tool: string, id: string, input: unknown, message: string, over: Partial<Msg> = {}): Msg =>
  msg(index, "assistant", JSON.stringify(input), { tool, tool_use_id: id, message, model: "claude-opus-5-5", ...over });
const result = (index: number, tool: string, id: string, text: string): Msg => msg(index, "tool", text, { tool, tool_use_id: id });

/** A conversation's plain lines: the person, the model, the wrapping the harness puts around messages, notes. */
export const CHAT: Msg[] = [
  msg(0, "user", "Fix the tide table parser so it reads the new column."),
  msg(1, "user", "<system-reminder>\nMade-up reminder: the date is a made-up date.\n</system-reminder>"),
  msg(2, "user", "<command-name>/rename</command-name>\n<command-message>rename</command-message>\n<command-args>tide-fix</command-args>"),
  msg(3, "assistant", "I read the parser. The new column is skipped because the header row is split on tabs only.", { message: "msg_c1", model: "claude-opus-5-5" }),
  msg(4, "user", "Also check the harbor map.\n<system-reminder>Made-up reminder text the person never wrote.</system-reminder>"),
  msg(5, "system", "[Request interrupted by user]", { kind: "interrupted" }),
  msg(6, "peer", "The harbor map is rebuilt and the north pier label moved.", { from: "map-builder" }),
  msg(7, "system", "API Error: the made-up provider is overloaded."),
  msg(8, "assistant", `The column list is at https://example.com/harbor/${"columns-".repeat(40)}end and also in ${"x".repeat(240)} for reference.`, { message: "msg_c2", model: "claude-opus-5-5" }),
  msg(9, "system", "This session is being continued from a previous conversation. Summary: the parser now reads tabs and commas.", { compact: true }),
  msg(10, "user", "Never mind the harbor map.", { kind: "taken_back" }),
  msg(11, "user", "<local-command-stdout>Made-up command output: 3 files renamed</local-command-stdout>"),
  msg(12, "user", "<task-notification>\n<task-id>made-up-task</task-id>\n<status>completed</status>\n</task-notification>"),
];

const LONG = Array.from({ length: 300 }, (_, i) => `line ${i + 1} of the tide table`).join("\n");

/** Tool calls and their results, a long result, a JSON result, thinking, and a result whose call is on an earlier page. */
export const TOOLS: Msg[] = [
  msg(20, "assistant", "Running the parser tests first.", { message: "msg_t1", model: "claude-opus-5-5" }),
  call(21, "Bash", "toolu_b1", { command: "bun test parser", description: "Run the parser tests" }, "msg_t1"),
  result(22, "Bash", "toolu_b1", "3 pass\n0 fail\n<system-reminder>Made-up reminder inside a result.</system-reminder>"),
  call(23, "Read", "toolu_r1", { file_path: "/work/harbor/src/parsers/tide-table/header-row.ts", offset: 1, limit: 300 }, "msg_t2", {
    thinking: "The header row might use commas as well as tabs.",
  }),
  result(24, "Read", "toolu_r1", LONG),
  call(25, "Grep", "toolu_g1", { pattern: "splitHeader", path: "/work/harbor/src" }, "msg_t3"),
  result(26, "Grep", "toolu_g1", "src/tide.ts:12: const cols = splitHeader(row)"),
  call(27, "Edit", "toolu_e1", { file_path: "/work/harbor/src/parsers/tide-table/header-row.ts", old_string: 'row.split("\\t")', new_string: "row.split(/[\\t,]/)" }, "msg_t4"),
  result(28, "Edit", "toolu_e1", "The file /work/harbor/src/parsers/tide-table/header-row.ts has been updated."),
  call(29, "mcp__harbor__lookup", "toolu_m1", { station: "North Pier", window: { from: "06:00", to: "18:00" }, fields: ["height", "time"] }, "msg_t5"),
  result(30, "mcp__harbor__lookup", "toolu_m1", JSON.stringify({ station: "North Pier", high: { time: "09:12", height_m: 3.4 } })),
  result(31, "Bash", "toolu_x9", "Output from a call on an earlier page."),
];

/** The code the Markdown reply holds, exactly as a copy should give it back. */
export const TS_CODE = "export function splitHeader(row: string): string[] {\n  return row.split(/[\\t,]/).map((c) => c.trim());\n}";
export const SH_CODE =
  'bun test parser && echo "a very long line that keeps going past the width of a phone screen, to show it scrolls sideways inside its own block"';

export const MARKDOWN_TEXT = [
  "## Tide table fix",
  "",
  "The parser now reads **both** separators:",
  "",
  "- tabs",
  "- commas",
  "",
  "| Column | Kept |",
  "| --- | --- |",
  "| height | yes |",
  "| time | yes |",
  "",
  "> Made-up note: the harbor feed changed in spring.",
  "",
  "See [the harbor docs](https://example.com/harbor) and call `splitHeader(row)`.",
  "",
  "```ts",
  TS_CODE,
  "```",
  "",
  "```bash",
  SH_CODE,
  "```",
  "",
  '<img src="https://example.com/x.png" onerror="window.__pwned=1"> <script>window.__pwned=2</script>',
  "",
  "[a bad link](javascript:window.__pwned=3)",
].join("\n");

export const MARKDOWN: Msg[] = [
  msg(40, "user", "Show me the fix, and keep **these stars** as I typed them."),
  msg(41, "assistant", MARKDOWN_TEXT, { message: "msg_m1", model: "claude-opus-5-5" }),
];

/** Everything at once: what the reader's own page and the screenshots show. */
export const WORLD: Msg[] = [...CHAT, ...TOOLS, ...MARKDOWN];

/** /api/sessions, for the reader's page: its header names the session, and with no session it lists them. */
export const SESSIONS = {
  reading: true,
  sessions: [
    { session_id: SESSION, name: "api-fixer", head: "claude-splice", status: "working", availability: "live", cwd: "/work/harbor", project: { name: "harbor" }, updated_at: new Date(NOW).toISOString() },
    { session_id: "bbbb2222", name: "map-builder", head: "claude-splice", status: "idle", availability: "live", cwd: "/work/harbor", project: { name: "harbor" }, updated_at: new Date(NOW - 5 * MIN).toISOString() },
  ],
};

/** Feed events, as the reader is handed them. */
export const ev = (event: string, data: Record<string, unknown>): Ev => ({ event, data });
export const turnEv = (turn: string, message: string | null, over: Record<string, unknown> = {}): Ev =>
  ev("turn", { turn, message, agent: null, model: "claude-opus-5-5", head: "claude-splice", compact: false, at: NOW, ...over });
