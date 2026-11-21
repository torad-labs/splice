// Pictures of what the port added to the Night Deck (Nov 21, 2026), for looking at before it is called done: Accounts
// with the other providers' logins managed from the right rail (at rest, a login's glass open, Add login signing in),
// and Settings with its Modes part (the whole page, and the Modes glass open). Chromium on the Mac it is run on, the
// stub daemon with a made-up world, night and day, at 1440 and 3840 px. Everything shown is made up.
//
//   bun .campaign/checks/console-night-deck/capture-port.ts --out <folder>
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ACCOUNTS, browser, daemon, HOUR, NARROW, NOW, open, ROOT, WIDE, world } from "./lib/deck.ts";

const out = process.argv[process.argv.indexOf("--out") + 1] || "build/night-deck-captures/port-here";
const dir = join(ROOT, out);
rmSync(dir, { recursive: true, force: true });
mkdirSync(dir, { recursive: true });

const sec = (ms: number) => Math.floor(ms / 1000);
const other = (head: string, provider: string, kind: string, label: string, name: string, over: Record<string, unknown> = {}) => ({
  provider, heads: [head], account_labels: { [head]: label }, kind, label, selector_key: label, display_name: name,
  email: `${label}@made-up.invalid`, ref: null, credential_present: true, identity_verified: true, can_rename: true, can_remove: true,
  edit_target: { kind: "pool", id: label }, plan: "plus", available: true, selected: false, observed_at_epoch_seconds: sec(NOW),
  five_hour_used_percent: 35, five_hour_reset_epoch_seconds: sec(NOW + 3 * HOUR), five_hour_window_seconds: 18_000,
  seven_day_used_percent: 60, seven_day_reset_epoch_seconds: sec(NOW + 70 * HOUR), seven_day_window_seconds: 604_800, ...over,
});
const thisHour = Math.floor(NOW / HOUR) * HOUR;
const buckets = (n: number, cost: number) => [{ hour: thisHour - HOUR, turns: n, cost_usd: cost, plan_value_usd: cost * 3 }, { hour: thisHour, turns: Math.ceil(n / 2), cost_usd: cost / 2, plan_value_usd: cost }];
const MODES = [
  { name: "template", summary: "Says back the text it is given: a mode to copy.", kind: "door", server: "template", prompt: true,
    tools: [{ name: "say_back", offered_as: "mcp__template__say_back", description: "Say the text back." }], resources: [], callers_off: ["desktop"],
    on: { value: false, from: "manifest" }, heads: { "claude-splice": { on: false, from: "manifest" }, acme: { on: true, from: "splice.toml" }, globex: { on: false, from: "manifest" }, keyed: { on: false, from: "never" } } },
  { name: "notes", summary: "Keeps a made-up note beside the session.", kind: "door", server: "notes", prompt: false,
    tools: [{ name: "keep", offered_as: "mcp__notes__keep", description: "Keep a note." }, { name: "read", offered_as: "mcp__notes__read", description: "Read the notes kept." }], resources: [], callers_off: [],
    on: { value: true, from: "manifest" }, heads: { "claude-splice": { on: true, from: "manifest" }, acme: { on: true, from: "manifest" }, globex: { on: true, from: "manifest" }, keyed: { on: true, from: "manifest" } } },
];
const WORLD = world({
  "/api/heads": { heads: [
    { key: "claude-splice", label: "claude", state: "running", port: 41001, authKind: "client", gate: { inflight: 1, queued: 0, max: 12 } },
    { key: "acme", label: "claude-acme", state: "running", port: 41002, authKind: "chatgpt-oauth", gate: { inflight: 0, queued: 0, max: 12 } },
    { key: "globex", label: "claude-globex", state: "running", port: 41003, authKind: "grok-oauth", gate: { inflight: 0, queued: 0, max: 12 } },
    { key: "keyed", label: "claude-keyed", state: "running", port: 41004, authKind: "api-key", gate: { inflight: 0, queued: 0, max: 12 } },
  ] },
  "/api/status": { version: "9.8.7", registry: [
    { key: "claude-splice", label: "claude", family: "anthropic", authKind: "client" },
    { key: "acme", label: "claude-acme", family: "openai", authKind: "chatgpt-oauth" },
    { key: "globex", label: "claude-globex", family: "xai", authKind: "grok-oauth" },
    { key: "keyed", label: "claude-keyed", family: "deepseek", authKind: "api-key" },
  ] },
  "/api/accounts": { accounts: [
    ...ACCOUNTS.accounts,
    other("acme", "openai", "chatgpt-oauth", "north", "North desk", { selected: true }),
    other("acme", "openai", "chatgpt-oauth", "south", "South desk", { ref: "50f7", five_hour_used_percent: 100 }),
    other("globex", "xai", "grok-oauth", "only", "Only one", { plan: null, five_hour_used_percent: null, seven_day_used_percent: null }),
  ] },
  "/api/keys": { keys: [{ name: "KEYED_API_KEY", head: "keyed", source: "keyring" }] },
  "/api/economics": { ready: true, heads: [
    { key: "claude-splice", buckets: buckets(18, 2.4), not_read_hours: [] }, { key: "acme", buckets: buckets(9, 0.8), not_read_hours: [] },
    { key: "globex", buckets: buckets(4, 0.2), not_read_hours: [] }, { key: "keyed", buckets: buckets(2, 0.05), not_read_hours: [] },
  ] },
  "/api/modes": { modes: MODES },
  "/api/config": { effective: { effort: "high", summary: "detailed", showReasoning: "text", replayReasoning: true, maxInflight: 12, maxQueued: 512, streamIdleMs: 90_000, usageWarnPct: 80 }, layers: { perHead: {} }, knobs: {} },
  "/api/mcp/memory": { sessions: 2, bytes_now: 400_000_000, bytes_if_shared: 100_000_000, servers: [] },
  // this stub answers by path alone: the start is the POST, and a read of it is the same sign-in still waiting
  "/api/auth/acme/login": { id: "s-1", state: "waiting", browser_url: "https://signin.example.invalid/start" },
  "/api/auth/acme/login/s-1": { id: "s-1", state: "waiting", browser_url: "https://signin.example.invalid/start" },
});

type Step = (page: import("@playwright/test").Page) => Promise<void>;
const SCENES: { name: string; file: string; full?: boolean; steps?: Step }[] = [
  { name: "accounts-rail", file: "accounts.html" },
  { name: "accounts-rail-glass", file: "accounts.html", steps: async (p) => { await p.locator(".rail-r .acct[data-id]", { hasText: "South desk" }).click(); await p.waitForTimeout(700); } },
  { name: "accounts-add-login", file: "accounts.html", steps: async (p) => { await p.locator('.rail-r [data-add="acme"]').click(); await p.locator('.lens:not([hidden]) [data-act="signin"]').click(); await p.waitForTimeout(900); } },
  { name: "settings-whole", file: "settings.html", full: true },
  { name: "settings-modes", file: "settings.html", steps: async (p) => { await p.locator('[data-part="modes"]').scrollIntoViewIfNeeded(); await p.locator('[data-part="modes"]').click(); await p.waitForTimeout(700); } },
];

const report: string[] = [];
const b = await browser();
const d = daemon(WORLD);
try {
  for (const s of SCENES) for (const theme of ["night", "day"] as const) for (const size of [NARROW, WIDE]) {
    const name = `${s.name}-${theme}-${size.width}`;
    let o: Awaited<ReturnType<typeof open>> | null = null;
    try {
      o = await open(b, d, s.file, size, theme);
      // a sign-in opens a tab of its own; the picture is of the console's page
      if (s.steps) await s.steps(o.page);
      await o.page.screenshot({ path: join(dir, `${name}.png`), fullPage: !!s.full });
      const spill = await o.page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
      report.push(`${name}: ${o.errors.length ? "threw: " + o.errors.join(" | ") : "no error"}${spill > 1 ? `; ${spill}px past the right edge` : ", nothing past the right edge"}`);
    } catch (e) {
      report.push(`${name}: failed: ${(e as Error).message.split("\n")[0]}`);
    } finally {
      await o?.close();
    }
  }
} finally {
  d.stop();
  await b.close();
}
writeFileSync(join(dir, "report.txt"), `${report.join("\n")}\n`);
console.log(report.join("\n"));
