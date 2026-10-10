// Accounts: every account on every provider, how much of each window is used, when it comes back, which one
// serves next, and what each command may spend in a day. The drawing (Splice-Animator design/console, d3338fa) on
// splice's own answers: every name, number and state below is read from the control API, and every act calls it.
"use strict";

let NOW = new Date(); // read again at every load, so a reset reads in the viewer's own clock
const LEN = { "5 hours": 300, Week: 10080, Month: 43200, Day: 1440 };
const COLORS = { claude: "--claude", gpt: "--gpt", grok: "--grok", kimi: "--kimi", muse: "--muse", router: "--router", deepseek: "--deepseek", local: "--local", vast: "--local" };

// splice's budget day runs midnight to midnight on the DAEMON's clock (BudgetEnforcement.kt:10), and the daemon says
// when it next rolls over (day_resets_at_epoch_ms). A browser in another zone computing its own midnight disagreed
// with the admission that actually blocks (re-review, Oct 10). Only a daemon that answers nothing falls back here.
let DAY_RESETS_AT = null;
let DAY_STARTED_AT = null; // the instant the budget day now running began, from the daemon's own calendar day
function dayReset() {
  const next = DAY_RESETS_AT ?? new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() + 1).getTime();
  return { left: Math.round((next - NOW.getTime()) / 60000), resetMs: next };
}

// The provider a command belongs to, by the family splice gives its head (/api/status registry, ProviderFamilyRule.kt).
const FAMILY = {
  anthropic: { id: "claude", name: "Claude" }, openai: { id: "gpt", name: "ChatGPT" }, xai: { id: "grok", name: "Grok" },
  moonshot: { id: "kimi", name: "Kimi" }, meta: { id: "muse", name: "Muse" }, openrouter: { id: "router", name: "OpenRouter" },
  deepseek: { id: "deepseek", name: "DeepSeek" }, local: { id: "local", name: "This computer" },
  vast: { id: "vast", name: "vast.ai" }, // a rented GPU behind a tunnel: the provider says family = "vast" (fin's name)
};
// What Add provider offers: a provider splice can add, picked by the family it would run under.
const CATALOG = [
  { id: "claude", name: "Claude", kind: "plan" }, { id: "gpt", name: "ChatGPT", kind: "plan" },
  { id: "grok", name: "Grok", kind: "plan" }, { id: "kimi", name: "Kimi", kind: "plan" },
  { id: "muse", name: "Muse", kind: "plan" }, { id: "router", name: "OpenRouter", kind: "key" },
  { id: "deepseek", name: "DeepSeek", kind: "key" },
];

// A window as the page draws it, from splice's flat fields: its name from its slot, how much is used, and the minutes
// until it resets (null once the reading's reset has passed: the window has refilled since, and the page shows no time).
// A weekly window the provider gives no length is measured from the time left to its reset
// (QuotaSlots.weeklyWindowSeconds), so the long slot reads Week unless it is a month long.
//
// EACH WINDOW CARRIES ITS OWN STALENESS, from splice's own `*_current` field for that window
// (QuotaFreshness): one current window never vouches for another. A 5-hour reading minutes old sits
// beside a week whose reset has passed, and the week's figure is then the usage of a week that
// ended; drawing it unmarked reports spent capacity on a week that starts at zero. [current]
// undefined is a caller with no freshness to give, which is drawn as it always was.
function windowOf(pct, resetSec, lenSec, slot, current) {
  if (pct == null) return null;
  const month = slot === "long" && lenSec >= 28 * 86400;
  const label = slot === "short" ? "5 hours" : month ? "Month" : "Week";
  const left = resetSec ? Math.round((resetSec * 1000 - NOW.getTime()) / 60000) : null;
  const due = left != null && left >= 0;
  return { label, used: Math.round(pct), len: LEN[label], left: due ? left : null, resetMs: due ? resetSec * 1000 : null,
    // The reset splice last heard about, once it has gone by: the figure beside it is the ENDED window's, and the
    // cell has to say so. Null when no reset was ever reported, which is the one case the page knows nothing about.
    rolledMs: resetSec && !due ? resetSec * 1000 : null,
    stale: current === false };
}
// A model week's own freshness, under the account week it is drawn beneath: that week's reading is the one
// observation both came from, and the model's own reset decides whether its figure is still this week's.
// splice drops a rolled row (QuotaSnapshot.modelsRunningAt); a daemon older than that still sends one, and the
// page must not draw it as today's. Undefined week freshness (a daemon older than the field) leaves the row
// drawn as it always was.
function modelCurrent(weekCurrent, m) {
  if (weekCurrent === false) return false;
  return m.resets_at && m.resets_at * 1000 <= NOW.getTime() ? false : weekCurrent;
}
const plainPlan = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : undefined);
// An account's name on one command, as splice knows it there: two commands can each have an account called "primary"
// (ChatGPT's and Grok's), so the page keys an account by its commands and that name, and sends splice the name only.
const labelOf = (row) => row.selector_key || row.label || "primary";
const acctId = (row) => `${(row.heads || []).join("+")}/${labelOf(row)}`;
const labelOn = (a, head) => a.row?.account_labels?.[head] || labelOf(a.row);

// One read of everything the page shows. Each provider: its commands (with their order, pin and day) and its accounts.
async function load() {
  NOW = new Date();
  const [st, ac, ks, bg, ec, md, hd] = await Promise.all(["/api/status", "/api/accounts", "/api/keys", "/api/budgets", "/api/economics", "/api/models", "/api/heads"].map((p) => API.get(p)));
  if (!st.ok || !ac.ok) { data = []; offline = true; return; }
  offline = false;
  const budgets = new Map((bg.body?.budgets || []).map((b) => [b.head, b]));
  DAY_RESETS_AT = bg.body?.day_resets_at_epoch_ms ?? null;
  DAY_STARTED_AT = bg.body?.day_started_at_epoch_ms ?? null;
  // The day splice is counting begins where the daemon says it began: its own calendar day's midnight, never
  // tomorrow's minus 24 hours, which is wrong on the two days a year the clock changes. Only a daemon that answers
  // nothing falls back to this browser's midnight.
  const dayStart = DAY_STARTED_AT ?? new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate()).getTime();
  // Buckets are whole UTC hours, so where the day starts inside one (a zone offset by half an hour) that hour holds
  // turns of both days and its buckets cannot say which is which; an exact figure there needs the per-turn rows. The
  // figure counts the whole hours from the first boundary after the day began, and the opening hour is its own
  // number beside it, never guessed into either day.
  const HOUR = 3600000;
  const wholeFrom = Math.ceil(dayStart / HOUR) * HOUR;
  const sumOf = (rows) => rows.reduce((t, b) => ({ usd: t.usd + (b.cost_usd || 0), unpriced: t.unpriced + (b.unpriced_turns || 0) }), { usd: 0, unpriced: 0 });
  const spendToday = new Map((ec.body?.heads || []).map((h) => {
    const rows = h.buckets || [];
    const opening = wholeFrom > dayStart ? sumOf(rows.filter((b) => b.hour < wholeFrom && b.hour + HOUR > dayStart)) : null;
    return [h.key, { ...sumOf(rows.filter((b) => b.hour >= wholeFrom)), opening: opening && (opening.usd || opening.unpriced) ? opening : null }];
  }));
  // why a command's turns with no price have none, decided by the daemon once for this row and for Requests
  const priceWhy = new Map((ec.body?.heads || []).map((h) => [h.key, h.unpriced_reason]));
  // a local runtime splice could not reach on its port (/api/heads runtimeNotAnswering): its card says so, as a signed-out one does
  const down = new Set(((Array.isArray(hd.body) ? hd.body : hd.body?.heads) || []).filter((h) => h.runtimeNotAnswering).map((h) => h.key));
  const pinnedModel = new Map((md.body?.heads || []).map((h) => [h.head, (h.models || []).find((m) => m.pinned)?.label || h.pinned_model]));
  const keyRows = ks.body?.keys || [];
  const provs = new Map();
  for (const h of st.body.registry || []) {
    const fam = FAMILY[h.family] || { id: h.family || h.key, name: h.family || h.key };
    const kind = h.family === "local" || h.family === "vast" ? "local" : h.authKind === "api-key" ? "key" : "plan";
    if (!provs.has(fam.id)) provs.set(fam.id, { id: fam.id, name: fam.name, kind, cmds: [], accounts: [] });
    const p = provs.get(fam.id), b = budgets.get(h.key), today = spendToday.get(h.key) || { usd: 0, unpriced: 0 };
    p.cmds.push({ cmd: h.label || h.key, head: h.key, spent: b ? b.used_usd : today.usd, unpriced: b ? b.unpriced_turns : today.unpriced,
      opening: b ? null : today.opening, // only the bucket-built figure has a half-hour it cannot place; the budget's own is exact
      local: kind === "local", // nothing bills it per token, so no figure and no line
      onPlan: ((b && b.unpriced_reason) || priceWhy.get(h.key) || (kind === "plan" ? "plan" : "undeclared")) === "plan",
      budget: b && b.daily_usd != null ? { cap: b.daily_usd, block: b.action === "block" } : null, mode: "soonest", pin: null, order: [] });
  }
  for (const p of provs.values()) {
    const heads = new Set(p.cmds.map((c) => c.head));
    if (p.kind === "plan") {
      for (const row of ac.body.accounts || []) {
        if (!(row.heads || []).some((x) => heads.has(x))) continue;
        // Claude Code's own login place that never held a sign-in is no account (Marlin, Oct 10): no credential, no
        // account, no reading. One that was signed in and expired keeps all three, and shows.
        if (row.login_place && !row.credential_present && !row.account && !row.observed_at_epoch_seconds) continue;
        const windows = [windowOf(row.five_hour_used_percent, row.five_hour_reset_epoch_seconds, row.five_hour_window_seconds, "short", row.five_hour_current),
          windowOf(row.seven_day_used_percent, row.seven_day_reset_epoch_seconds, row.seven_day_window_seconds, "long", row.seven_day_current)].filter(Boolean);
        const models = (row.seven_day_models || []).map((m) => ({ name: m.model,
          ...windowOf(m.used_percent, m.resets_at, LEN.Week * 60, "long", modelCurrent(row.seven_day_current, m)) }));
        p.accounts.push({ id: acctId(row), row, name: row.display_name, email: row.account?.email || undefined, plan: plainPlan(row.plan), windows,
          // one observation time behind every bar, so the read line shows as soon as ANY bar drawn here is stale
          staleAt: [...windows, ...models].some((w) => w.stale) && row.observed_at_epoch_seconds ? row.observed_at_epoch_seconds * 1000 : null,
          out: !row.credential_present || row.auth_exclusion_reason === "credential_missing" || !!row.refusal,
          canRename: !!row.can_rename, canRemove: !!row.can_remove, native: !!row.carrying_request,
          // the provider answered with no usage for this account: no bars, never an older reading drawn as today's
          noUsage: !windows.length && !!row.no_usage_at_epoch_seconds,
          // each model's own weekly window, drawn under the week (Claude's Opus and Sonnet)
          models });
      }
    } else if (p.kind === "key") {
      for (const k of keyRows) {
        const uses = (k.heads || []).filter((x) => heads.has(x.head));
        if (!uses.length) continue;
        const from = uses[0].source;
        const day = (s) => (s ? new Date(s * 1000) : null); // the day splice first saw the key (KeyLedger.kt)
        p.accounts.push({ id: k.name, env: k.name, has: from !== "missing" && from !== "unknown", from: from === "missing" || from === "unknown" ? null : from, seen: day(k.first_seen_epoch_seconds) });
        // a key it replaced is its own account, with its own day, marked Replaced
        for (const r of k.replaced || []) p.accounts.push({ id: `${k.name}#${r.fingerprint}`, env: k.name, has: true, from: "replaced", seen: day(r.first_seen_epoch_seconds) });
      }
    } else {
      for (const c of p.cmds) p.accounts.push({ id: c.head, name: c.cmd, model: pinnedModel.get(c.head) || "", down: down.has(c.head) });
    }
  }
  // each plan command's own order and pin (AccountOrderStore.kt:1, :24): no order is the soonest-reset rule
  await Promise.all([...provs.values()].filter((p) => p.kind === "plan").flatMap((p) => p.cmds.map(async (c) => {
    const o = await API.get(`/api/auth/${encodeURIComponent(c.head)}/order`);
    const mine = p.accounts.filter((a) => (a.row.heads || []).includes(c.head));
    const byLabel = (label) => mine.find((a) => labelOn(a, c.head) === label)?.id;
    const ids = [...new Set((o.body?.effective_order || []).map(byLabel).filter(Boolean))];
    c.order = [...ids, ...mine.map((a) => a.id).filter((id) => !ids.includes(id))];
    c.mode = o.body?.order?.length ? "mine" : "soonest";
    c.orderable = new Set(o.body?.effective_order || []); // the labels splice will take in an order
    c.pin = mine.find((a) => (a.row.pinned_heads || []).includes(c.head))?.id || null; // pinned on THIS head, not on whichever head a shared row came from
    // the pool's own picks, named by label on this command
    c.serving = byLabel(o.body?.next_target) ?? null; c.following = byLabel(o.body?.following_target) ?? null;
  })));
  data = [...provs.values()];
}

let data = [];
let offline = false;
const FRESH_UI = () => ({ lane: {}, signin: {}, editor: null, addProv: null, menu: null, armed: null, removeErr: null, rename: null, renameErr: null, renameDraft: null, replace: null, pulse: null });
const ui = { probing: false, grow: null, ...FRESH_UI() };
const board = document.getElementById("board");
// ---------- time: what a person reads ----------
const clock = (d) => {
  const h = d.getHours() % 12 || 12, m = d.getMinutes(), ap = d.getHours() < 12 ? "AM" : "PM";
  return `${h}:${String(m).padStart(2, "0")} ${ap}`; // as every page writes a time (kit.js clock): "9:00 AM", never "9 AM"
};
// A time from the instant splice gave, rounded once to the minute, so every view of one reset prints the same minute (the
// walkers saw 5:38 and 5:39 for one reset when it was rebuilt from minutes left at each load).
function at(ms) {
  const d = new Date(Math.round(ms / 60000) * 60000);
  const days = Math.round((new Date(d).setHours(0, 0, 0, 0) - new Date(NOW).setHours(0, 0, 0, 0)) / 864e5);
  if (days === 0) return clock(d);
  if (Math.abs(days) < 7) return `${d.toLocaleDateString("en-US", { weekday: "short" })} ${clock(d)}`;
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

// ---------- the rules the page shows ----------
const held = (a) => (a.windows || []).some((w) => w.used >= 100);
const room = (a) => !a.out && !held(a);
const backAt = (a) => Math.max(0, ...a.windows.filter((w) => w.used >= 100).map((w) => w.left ?? 0));
const laneAccts = (p, c) => c.order.map((id) => p.accounts.find((a) => a.id === id)).filter(Boolean);
function ruled(p, c) { // the rule alone: his order, or the soonest reset among the accounts with room
  const list = laneAccts(p, c);
  if (c.mode === "mine") return list;
  const rank = (a) => (room(a) ? 0 : a.out ? 2 : 1);
  // splice's own order (AccountAvailability.resetOrder): the sooner of the two resets, then the week's, then the five
  // hours'; a full tie keeps splice's order, which ends on the primary account and then the name
  const left = (a, label) => a.windows.find((w) => w.label === label)?.left ?? Infinity;
  const keys = (a) => (room(a) ? [Math.min(left(a, "5 hours"), left(a, "Week")), left(a, "Week"), left(a, "5 hours")]
    : [held(a) ? backAt(a) : 0, 0, 0]);
  const byKeys = (a, b) => keys(a).reduce((d, k, i) => d || (k === keys(b)[i] ? 0 : k < keys(b)[i] ? -1 : 1), 0);
  return list.sort((a, b) => rank(a) - rank(b) || byKeys(a, b));
}
// A pin is tried first; a pinned account with no room falls through to the rule and keeps its pin (AccountPool.kt:84-85).
function ordered(p, c) {
  if (p.kind !== "plan") return p.accounts;
  const list = ruled(p, c), pinned = list.find((a) => a.id === c.pin);
  return pinned && room(pinned) ? [pinned, ...list.filter((a) => a !== pinned)] : list;
}
const serving = (p, c) => (p.kind === "plan" ? (c?.serving && p.accounts.find((a) => a.id === c.serving)) || ordered(p, c).find(room) : p.kind === "key" ? p.accounts.find((k) => k.has && k.from !== "replaced") : p.accounts[0]);
// Where the command goes when the account in use runs out, as splice picks it (AccountPool.followingLabel): the next
// login with room in the same order, or, when every other one is held, the one whose reset is nearest, served at that
// reset. A command with one account has nowhere to go.
function nextOf(p, c) {
  if (p.kind !== "plan" || !c?.following) return null;
  return p.accounts.find((a) => a.id === c.following) || null;
}

// A failed sign-in shows a state, never splice's sentence: the raw text belongs in the command's log on Models.
// splice names each failure's kind (failure_kind, ConsoleAccounts.kt LoginFailure); a kind it doesn't name reads Not
// completed. A file or config failure fails again until it's fixed, so it has no Try again.
const FAILS = {
  not_completed: { word: "Not completed", retry: true }, network: { word: "Network error", retry: true },
  file: { word: "File error" }, config: { word: "Config error" }, expired: { word: "Expired", retry: true },
  stopped: { word: "Stopped", retry: true }, cancelled: { word: "Cancelled", retry: true }, in_progress: { word: "In progress" },
  already_added: { word: "Already added", pulse: true }, in_use: { word: "In use", retry: true },
};
// A name splice takes, and one no other account of the command has (ClaudeAccountFolders.kt:199,
// OAuthAccountFiles.kt:264-266). On Claude: letters, digits, - and _, starting with a letter or digit, up to 64
// (ClaudeAccountFolders.kt:34, :195). Elsewhere: lowercase letters, digits, ., - and _, up to 48
// (AccountLabelPolicy, AccountSelection.kt:17), with primary, auto and a -quota ending reserved.
function nameError(p, a, v) {
  const reserved = p.id !== "claude" && (v === "primary" || v === "auto" || v.endsWith("-quota"));
  const shape = p.id === "claude" ? /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/ : /^[a-z0-9][a-z0-9._-]{0,47}$/;
  if (reserved || !shape.test(v)) return "Invalid name";
  if (p.accounts.some((x) => x !== a && x.name === v)) return "Name taken";
  return null;
}

// ---------- drawing ----------
const ICON = {
  grip: '<svg viewBox="0 0 10 18" fill="currentColor"><circle cx="2.5" cy="3" r="1.6"/><circle cx="7.5" cy="3" r="1.6"/><circle cx="2.5" cy="9" r="1.6"/><circle cx="7.5" cy="9" r="1.6"/><circle cx="2.5" cy="15" r="1.6"/><circle cx="7.5" cy="15" r="1.6"/></svg>',
  lock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/></svg>',
  bell: '<svg class="glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/></svg>',
  stop: '<svg class="glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8.2 3h7.6L21 8.2v7.6L15.8 21H8.2L3 15.8V8.2z"/><path d="M8.5 12h7"/></svg>',
  eye: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>',
  pin: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M9 3.5h6l-1.2 6 3.7 3.5h-11l3.7-3.5z"/><path d="M12 13v7.5"/></svg>',
  more: '<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>',
  wait: '<svg class="wait" viewBox="0 0 40 40" aria-hidden="true"><circle cx="20" cy="20" r="15"/></svg>',
};
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const colorOf = (id) => `var(${COLORS[id] || "--text-mute"})`;
const money = (v) => `≈$${v.toFixed(2)}`; // priced from the rate card, so an estimate on every command
const cap = (v) => `$${Number.isInteger(v) ? v : v.toFixed(2)}`;
const waiting = () => `<span class="waitbox" role="status">${ICON.wait}<span class="sr">Signing in</span></span>`;
const locked = (pop = false) => `<span class="state limit" role="img" aria-label="At limit"><span class="lockico${pop ? " lock-pop" : ""}">${ICON.lock}</span></span>`;

function ring(w) {
  const C = 2 * Math.PI * 15, f = Math.max(0, Math.min(1, w.left / w.len));
  return `<svg class="ring" viewBox="0 0 40 40" aria-hidden="true"><circle class="trk" cx="20" cy="20" r="15"/><circle class="arc" cx="20" cy="20" r="15" stroke-dasharray="${(C * f).toFixed(1)} ${C.toFixed(1)}"/></svg>`;
}
const bar = (v, cls = "") => `<div class="bar ${cls}"><b data-v="${v}"></b></div>`;
// A window still running says when it resets, with its ring. A window whose reported reset has already gone by says
// "Reset" and that time, past tense, with no ring: there is nothing left to fill, and "Week 93% used" beside an empty
// cell read as a live week at 93% when it was the ended week's figure (fin's word, Oct 10; the hollow bar is the
// other half of the same fact). A window that reported no reset at all keeps the empty cell, because there the page
// genuinely knows nothing to say.
const resets = (w) => (w.left != null ? `<span class="when">${ring(w)}Resets ${at(w.resetMs)}</span>`
  : w.rolledMs != null ? `<span class="when rolled">Reset ${at(w.rolledMs)}</span>` : "<span></span>");
// The budget day refills at the DAEMON's midnight, so its strip says that one word: a day name and "12:00 AM" read
// as a weekly date to both walkers (fin, Oct 10). The plan windows keep their times, which really move. Read from a
// browser in another zone the daemon's midnight is not midnight there, so the word is only used when it is one.
function refills(day) {
  const at0 = new Date(day.resetMs);
  const midnightHere = at0.getHours() === 0 && at0.getMinutes() === 0;
  return `<span class="when">${ring(day)}Resets ${midnightHere ? "midnight" : at(day.resetMs)}</span>`;
}

function windowsHtml(a, next = null) {
  let rows = "";
  for (const w of a.windows) {
    const full = w.used >= 100;
    // each bar is marked from ITS OWN reading, never the account's: a current 5-hour window beside a week
    // whose own reset has passed drew that week's spent figure as today's
    rows += `<span class="label">${w.label}</span>${bar(Math.min(w.used, 100), `${full ? "full" : ""} ${w.stale ? "stale" : ""}`)}`
      + `<span class="pct ${full ? "limit" : ""}">${w.used}%<span class="used">used</span></span>${resets(w)}`;
    if (w.label === "Week" && a.models) {
      for (const m of a.models) rows += `<span class="label sub">${m.name}</span>${bar(m.used, `sub ${m.used >= 100 ? "full" : ""} ${m.stale ? "stale" : ""}`)}<span class="pct">${m.used}%<span class="used">used</span></span>${resets(m)}`;
    }
  }
  if (a.staleAt != null) rows += `<span class="stale-at">${ICON.eye}Read ${at(a.staleAt)}</span>`;
  if (next) { // a held next is the one serving at its reset: its lock and that reset, on the windows' own columns
    const w = held(next) && next.windows.filter((x) => x.used >= 100).sort((x, y) => y.left - x.left)[0];
    rows += `<span class="next"><span class="label">Next</span><span class="who">${esc(next.name)}</span>${w ? `${locked()}${resets(w)}` : ""}</span>`;
  }
  return `<div class="windows">${rows}</div>`;
}

// A command's day: what it has spent since local midnight, against the budget he set, and when it refills. The
// command's tab names the row, because one provider can serve several commands. Where a plan provider's commands share
// one rail, the tab is pressed to show that command's order: pick is { p, on } there, and null everywhere else.
function meterHtml(c, pick = null) {
  const day = { ...dayReset(), len: LEN.Day };
  const tag = pick ? `<button class="chip tag" data-act="lane" data-p="${pick.p.id}" data-c="${c.cmd}" aria-pressed="${pick.on}">${esc(c.cmd)}</button>`
    : `<span class="chip tag">${esc(c.cmd)}</span>`;
  if (ui.editor && ui.editor.c === c.cmd) return `<div class="meter" data-meter="${c.cmd}">${editorHtml(c, tag)}</div>`;
  // A figure that leaves out a turn is no figure: until every turn has a price, the day shows only how many lack one
  // (Marlin, Oct 10), never "$0.00" over turns that cost something.
  // a spend splice is still reading (spend_pending, used_usd null after a restart) is no figure yet: it once threw here
  // and left the whole board empty on the first open after a restart
  const spent = c.unpriced || c.local || c.spent == null ? "" : money(c.spent);
  if (!c.budget) {
    return `<div class="meter windows spend" data-meter="${c.cmd}">${tag}<span class="label">Day</span><span class="money">${spent}${unpriced(c)}</span>`
      + `<button class="act quiet" data-act="edit-budget" data-c="${c.cmd}">Set budget</button>${refills(day)}${openingRow(c)}</div>`;
  }
  const pct = Math.min(100, Math.round((c.spent / c.budget.cap) * 100)), mode = c.budget.block ? "Block" : "Warn";
  const spoken = `${spent ? `${spent} of` : "Budget"} ${cap(c.budget.cap)}, ${mode}${unpriced(c) ? `, ${noPrice(c)}` : ""}`;
  // no fill without a figure, and no empty box either: the cell stays, so the columns line up with the other rows
  const fill = !spent ? "<span></span>" : `<div class="capbox">${bar(pct, c.spent >= c.budget.cap ? "full" : "")}</div>`;
  return `<div class="meter windows spend" data-meter="${c.cmd}">${tag}<span class="label">Day</span>${fill}`
    + `<button class="money act quiet" data-act="edit-budget" data-c="${c.cmd}" aria-label="${spoken}">${c.budget.block ? ICON.stop : ICON.bell}${spent ? `${spent} <em>of</em> ` : "<em>Budget</em> "}${cap(c.budget.cap)}${unpriced(c)}</button>${refills(day)}</div>`;
}
// turns on a model with no rate card, after the dollar figure (fin). A meter's money column is narrow at every width, so
// here the words take their own line under the figure, with no dot (fin)
// A plan's turns read "on your plan": the plan covered them, and "with no price" would read as splice missing data.
// Every other turn with no figure is a model with no rate card (fin's words, Marlin, Oct 10).
const noPrice = (c) => `${c.unpriced.toLocaleString("en-US")} ${c.unpriced === 1 ? "turn" : "turns"} ${c.onPlan ? "on your plan" : "with no price"}`;
const unpriced = (c) => (c.unpriced && !c.local ? `<span class="unpriced own">${noPrice(c)}</span>` : "");
// The hour holding the day's start, in a zone whose midnight is not on a UTC hour: its turns belong to two days and
// the hourly totals cannot split them, so they are said once, apart from the day's figure.
//
// A SENTENCE, SO IT TAKES THE WHOLE TILE, never the money column beside the figure: that column is 175 px at 3394 and
// wrapped these words into four ragged lines, doubling the tile's height for one aside (capture, Oct 10).
//
// Only the tile with no budget draws it: a budgeted head reads its day from the budget's own exact figure, which has
// no half-hour it cannot place, so `opening` is null there by construction (see where the command rows are built).
const openingRow = (c) => (c.opening && !c.local ? `<span class="unpriced opening">${openingNote(c.opening)}</span>` : "");
const openingNote = (o) => `${o.usd ? money(o.usd) : `${o.unpriced.toLocaleString("en-US")} ${o.unpriced === 1 ? "turn" : "turns"}`} in the hour holding midnight, not counted`;
function editorHtml(c, tag) {
  const ed = ui.editor;
  const presets = [5, 10, 25, 50, 100].map((v) => `<button data-act="preset" data-v="${v}" aria-pressed="${ed.cap === v}">$${v}</button>`).join("");
  return `${tag}<div class="editor"><span class="money-in">$<input class="field" id="cap-in" inputmode="decimal" value="${ed.cap}" aria-label="Budget cap"></span>`
    + `<span class="presets">${presets}</span>`
    + `<span class="mode"><button data-act="bmode" data-v="warn" aria-pressed="${!ed.block}">${ICON.bell}Warn</button><button data-act="bmode" data-v="block" aria-pressed="${ed.block}">${ICON.stop}Block</button></span>`
    + `<button class="act primary" data-act="save-budget" data-c="${c.cmd}">Set budget</button><button class="act quiet" data-act="cancel-budget">Cancel</button></div>`;
}

// ---------- sign-in: one element wherever an account signs in, with the step its provider really asks for ----------
// The step comes from splice's own answer: a device code to type on the provider's page (Kimi, Muse), a code pasted back
// (Claude Code's own sign-in), or a page that finishes the sign-in by itself (LoginRoutes.kt:56).
const CODE_HINT = `<span class="code-hint">Enter this code on the sign-in page</span>`; // the act on the code, where Open sign-in goes (fin)
function signingHtml(target) {
  const s = ui.signin[target];
  const cancel = `<button class="act quiet" data-act="cancel-signin" data-t="${target}">Cancel</button>`;
  if (s.state === "fail") {
    const f = s.fail, retry = f.retry ? `<button class="act primary" data-act="retry-signin" data-t="${target}">Try again</button>` : "";
    return `<span class="state limit">${esc(f.word)}</span>${retry}${cancel}`;
  }
  const open = s.url ? `<button class="act" data-act="open-signin" data-t="${target}">Open sign-in</button>` : "";
  if (s.by === "code") return `${waiting()}${s.code ? `<button class="usercode${s.swapped ? " swap" : ""}" data-act="copy-code" data-t="${target}">${esc(s.code)}</button>${CODE_HINT}` : ""}${open}${cancel}`;
  if (s.by === "paste") return `${waiting()}${open}<input class="field code-in" data-t="${target}" placeholder="Paste code" aria-label="Paste code" autocomplete="off" spellcheck="false">${cancel}`;
  return `${waiting()}${open}${cancel}`;
}
// how: { start: the POST that begins it, poll: the head its status is read under, native: a Claude Code sign-in }
function startSignin(target, how, spot = null) { // spot: the one card it shows on
  ui.signin[target] = { how, spot, by: how.native ? "paste" : "browser" };
  waitSignin(target);
}
const stopTimers = (s) => clearTimeout(s.timer);
async function waitSignin(target) {
  const s = ui.signin[target];
  stopTimers(s);
  Object.assign(s, { state: "wait", code: null, url: null, id: null, gen: (s.gen || 0) + 1 });
  render();
  const gen = s.gen, res = await API.post(s.how.start, s.how.body || {});
  if (ui.signin[target] !== s || s.gen !== gen) return;
  if (!res.ok || !res.body?.id) { failSignin(target, res.body); return; }
  s.id = res.body.id; seen(target, read(s, res.body));
}
// An add's answer carries its sign-in inside it (AddViews.kt:63); a command's sign-in answers as itself.
const read = (s, body) => (s.how.watch ? body?.sign_in || {} : body);
function seen(target, st) { // one status answer: show its step, finish, fail, or ask again in a second
  const s = ui.signin[target];
  if (!s || s.state !== "wait") return;
  if (st.state === "failed") { failSignin(target, st); return; }
  if (st.state === "signed_in" || st.state === "live_after_restart") { finishSignin(target); return; }
  const url = st.verification_uri || st.browser_url || null, by = st.user_code ? "code" : s.how.native ? "paste" : "browser";
  const changed = url !== s.url || by !== s.by || (st.user_code || null) !== s.code;
  s.swapped = !!(s.code && st.user_code && st.user_code !== s.code); // a device code that expired is replaced (DeviceLoginFlow.kt:88-93)
  Object.assign(s, { url, by, code: st.user_code || null });
  if (changed) { render(); s.swapped = false; }
  const gen = s.gen;
  s.timer = setTimeout(async () => {
    const res = await API.get(s.how.watch || `/api/auth/${encodeURIComponent(s.how.poll)}/login/${encodeURIComponent(s.id)}`);
    if (ui.signin[target] !== s || s.gen !== gen) return;
    if (!res.ok) { failSignin(target); return; }
    seen(target, read(s, res.body));
  }, 1000);
}
function failSignin(target, st = null) {
  const s = ui.signin[target];
  stopTimers(s);
  s.state = "fail"; s.fail = FAILS[st?.failure_kind] || FAILS.not_completed;
  if (s.fail.pulse && st?.label) { // Already added: the card already there for that account pulses once
    const there = data.flatMap((p) => p.accounts).find((a) => a.row?.label === st.label && a.row.heads.includes(s.how.poll));
    if (there) ui.pulse = there.id;
  }
  render();
}
async function finishSignin(target) {
  const s = ui.signin[target];
  stopTimers(s);
  if (s.how.done && !(await s.how.done())) return; // an add saves after its sign-in; a refused save says so in place
  delete ui.signin[target];
  if (target === "prov") ui.addProv = null;
  await refresh(s.spot ? s.spot.split("/")[1] : "new");
}
function cancelSignin(target) {
  if (ui.signin[target]) stopTimers(ui.signin[target]);
  delete ui.signin[target];
  if (target === "prov") dropAdd();
  render();
}
function openSignin(target) {
  const s = ui.signin[target];
  if (s.url) window.open(s.url, "_blank", "noopener");
  if (s.by === "paste") board.querySelector(`.code-in[data-t="${target}"]`)?.focus();
}
async function submitCode(field) {
  if (!field.value.trim() || field.readOnly) return;
  field.readOnly = true;
  const s = ui.signin[field.dataset.t];
  if (!s?.id) return;
  const res = await API.post(`/api/auth/${encodeURIComponent(s.how.poll)}/login/${encodeURIComponent(s.id)}/code`, { code: field.value.trim() });
  if (!res.ok) { failSignin(field.dataset.t); return; }
  seen(field.dataset.t, res.body);
}
// How an account signs in again: Claude Code's own sign-in through its place, any other through its command's login
// under its own name (ClaudeLoginRoutes.kt:20, LoginRoutes.kt:24).
function howFor(p, a, c) {
  const head = c ? c.head : a.row.heads[0];
  if (a.row?.login_place) return { start: `/api/claude-logins/${encodeURIComponent(a.row.login_place.id)}/login`, body: { label: null }, poll: head, native: true };
  // its name on that command; the first account of a ChatGPT, Grok, Kimi or Muse command is "primary" there
  const label = a.row?.account_labels?.[head] || a.row?.label;
  // A removed primary has no file to sign in to under its reserved name, so the login refuses before the browser
  // ever opens (OAuthAccountFiles.kt:181). Signing in with NO name is what creates the primary, so a signed-out
  // primary sends none. A named account still sends its name: signing it in nameless would move it into the
  // primary slot (re-review, Oct 10).
  const reserved = label === "primary" && !a.row?.credential_present;
  return { start: `/api/auth/${encodeURIComponent(head)}/login`, body: label && !reserved ? { label } : {}, poll: head };
}
// ---------- an account ----------
// serves: every command this account serves now, each riding it as a chip. Use now pins it on the rail's command.
function slotHtml(p, c, a, serves) {
  // the pin says what pressing it does, at rest and on touch: both walkers clicked a bare pin on a guess (p153, p154)
  const pin = c && c.pin === a.id ? `<button class="act quiet small pin" data-act="unpin" data-p="${p.id}" data-c="${c.cmd}">${ICON.pin}Unpin</button>` : "";
  const chips = serves.map((x) => `<span class="chip" data-key="chip:${x.cmd}"><i></i>${esc(x.cmd)}</span>`).join("");
  // "Not answering" is the word (fin, Oct 10): it is what the watch measures, no reply at the
  // address in time (LocalRuntimeReach.kt), and it is what splice status already prints.
  if (a.down) return `<span class="state">Not answering</span>`;
  if (serves.length && (!c || serves.includes(c))) return `${pin}${chips}`;
  if (a.out) return `<span class="state">Signed out</span>`;
  if (p.kind === "key" && !a.has) return `<span class="state">No key</span>`;
  if (held(a)) return `${pin}${locked()}`;
  if (p.kind === "plan") return `${chips}<button class="act quiet" data-act="use" data-p="${p.id}" data-c="${c.cmd}" data-a="${a.id}">Use now</button>`;
  return "";
}
// A key in the environment or a key file shadows the stored copy (KeyRoutes.kt:7-9): saving one there changes nothing.
const keyActs = (a) => a.from === "store";
function menuItems(p, a) {
  if (p.kind === "local") return [];
  if (p.kind === "key") return a.has && keyActs(a) ? [["replace", "Replace key"], ["remove", "Remove key"]] : [];
  return [...(a.canRename === false ? [] : [["rename", "Rename"]]), ...(a.out ? [] : [["resign", "Sign in again"]]),
    ...(a.canRemove === false ? [] : [["remove", "Remove"]])];
}
function menuHtml(p, a, spot) {
  const armed = ui.armed === spot, refused = ui.removeErr && ui.removeErr.spot === spot;
  return `<div class="menu${refused ? " failed" : ""}" role="menu">${menuItems(p, a).map(([act, label]) => {
    if (act === "remove" && refused) return `<span class="state limit menu-state" role="status">${ui.removeErr.word}</span>`;
    const text = act === "remove" && armed ? `Remove ${p.kind === "key" ? "API key" : a.name}` : label;
    return `<button role="menuitem" class="${act === "remove" ? `danger${armed ? " armed" : ""}` : ""}" data-act="${act}" data-p="${p.id}" data-a="${a.id}" data-s="${spot}">${esc(text)}</button>`;
  }).join("")}</div>`;
}
function nameHtml(p, a, spot) {
  // a key is named by its variable and the day splice first saw it; a replaced key is a new account with a new day
  const dayWord = (d) => d.toLocaleDateString("en-US", { month: "short", day: "numeric" }); // kit.js's, which this page doesn't load
  if (p.kind === "key") return `<span class="name"><span class="var">${esc(a.env)}</span>${a.has && a.seen ? ` · ${dayWord(a.seen)}` : ""}</span>`;
  if (ui.rename !== spot) return `<span class="name">${esc(a.name)}</span>`;
  const err = ui.renameErr;
  // one visible way to finish, as the key field's Save: both walkers had to guess Enter (p153, p154)
  return `<input class="field rename" data-a="${a.id}" data-s="${spot}" value="${esc(ui.renameDraft ?? a.name)}" aria-label="Name" spellcheck="false"${err ? ' aria-invalid="true" aria-describedby="rename-err"' : ""}>`
    + `<button class="act primary small" data-act="finish-rename">Save</button>`; // a refusal reads under it (cardHtml)
}
// c is the command whose order the rail shows on a plan provider, and null for a key or this computer. Each account shows
// once, so its spot is the provider and the account.
function cardHtml(p, c, a, i, serves) {
  const spot = `${p.id}/${a.id}`;
  const cls = ["card", serves.length ? "serving" : "", a.out || (p.kind === "key" && !a.has) ? "out" : "", held(a) ? "held" : ""].join(" ");
  const grip = p.kind === "plan" ? `<button class="grip" data-grip="${p.id}" data-c="${c.cmd}" data-a="${a.id}" aria-label="Move ${esc(a.name)}">${ICON.grip}</button>` : "";
  const sub = a.plan && ui.rename !== spot ? `<span class="plan">${esc(a.plan)}</span>` : "";
  const email = a.email && ui.rename !== spot ? `<div class="email">${esc(a.email)}</div>` : "";
  const more = menuItems(p, a).length
    ? `<button class="icon more" data-act="menu" data-p="${p.id}" data-a="${a.id}" data-s="${spot}" aria-label="More" aria-haspopup="menu" aria-expanded="${ui.menu === spot}">${ICON.more}</button>` : "";
  const mine = (t) => ui.signin[t] && (ui.signin[t].spot == null || ui.signin[t].spot === spot);
  const signT = mine(`out:${a.id}`) ? `out:${a.id}` : mine(`re:${a.id}`) ? `re:${a.id}` : null;
  const signRow = signT ? `<div class="outbox${ui.signin[signT].state === "fail" ? " failed" : ""}">${signingHtml(signT)}</div>` : "";
  let body;
  const keyField = (cancel) => `<div class="outbox"><input class="field" id="key-in" type="password" data-a="${a.id}" placeholder="Paste key" aria-label="API key" autocomplete="off" spellcheck="false">`
    + `<button class="act primary" data-act="save-replace" data-p="${p.id}" data-a="${a.id}" disabled>Save</button>${cancel ? `<button class="act quiet" data-act="cancel-replace">Cancel</button>` : ""}</div>`;
  if (p.kind === "key") {
    // the card's name carries the variable, so its source says only where the value comes from
    body = !a.has ? keyField(false) : ui.replace === spot ? keyField(true)
      : a.from === "environment" ? `<div class="keysrc"><span class="state">From environment</span></div>`
      : a.from === "file" ? `<div class="keysrc"><span class="state">From file</span></div>`
      // the value splice read before this one: its spend stays on Usage and its requests on Requests
      : a.from === "replaced" ? `<div class="keysrc"><span class="state mute">Replaced</span></div>` : "";
  } else if (a.out) {
    body = signRow || `<div class="outbox"><button class="act primary" data-act="signin" data-p="${p.id}" data-a="${a.id}">Sign in</button></div>`;
  } else if (p.kind === "plan" && a.noUsage) {
    // fin's words (Marlin, Oct 10): no time on it, and never "no usage", which reads as "used nothing"
    body = `<div class="keysrc"><span class="state">${esc(p.name)} doesn't report limits</span></div>${signRow}`;
  } else if (p.kind === "plan") body = windowsHtml(a, c && serving(p, c) === a ? nextOf(p, c) : null) + signRow;
  else body = `<div class="local-model">${a.down ? "" : "<i></i>"}${esc(a.model)}</div>`; // the dot says it answers, so only when it does
  const socket = p.kind === "plan" ? `<span class="socket">${i + 1}</span>` : "";
  return `<article class="${cls}" data-key="card:${spot}" data-p="${p.id}" data-a="${a.id}">${socket}<div class="top">${grip}`
    + `${nameHtml(p, a, spot)}${sub}<div class="slot">${slotHtml(p, c, a, serves)}</div>${more}</div>`
    + `${ui.rename === spot && ui.renameErr ? `<span class="state limit rename-err" id="rename-err">${ui.renameErr}</span>` : ""}${email}${ui.menu === spot ? menuHtml(p, a, spot) : ""}${body}</article>`;
}

// ---------- the tiles that end a rail, and the one that ends the page ----------
function addTileHtml(p, c) {
  const t = `add:${c.cmd}`, s = ui.signin[t];
  if (s) return `<div class="add signing${s.state === "fail" ? " failed" : ""}" data-key="${t}"><span class="socket"></span>${signingHtml(t)}</div>`;
  return `<button class="add" data-key="${t}" data-act="add" data-p="${p.id}" data-c="${c.cmd}"><span class="socket"></span><span class="plus">+</span>Add account</button>`;
}
function switchHtml(p, c) {
  if (laneAccts(p, c).length < 2) return "";
  const b = (v, label) => `<button data-act="mode" data-p="${p.id}" data-c="${c.cmd}" data-v="${v}" aria-pressed="${c.mode === v}">${label}</button>`;
  return `<div class="switch" role="group" aria-label="Account order${p.cmds.length > 1 ? `, ${esc(c.cmd)}` : ""}">${b("soonest", "Soonest reset")}${b("mine", "My order")}</div>`;
}
function railHtml(p, c) {
  const srv = new Map(p.cmds.map((x) => [x, serving(p, x)]));
  return `<div class="rail" data-rail="${p.id}">${ordered(p, c).map((a, i) => cardHtml(p, c, a, i, p.cmds.filter((x) => srv.get(x) === a))).join("")}${addTileHtml(p, c)}</div>`;
}
// Every provider reads the same: its name with the order switch, a day per command with the command as its tab, then
// each account once on one rail, carrying the chip of every command it serves. Each command keeps its own order
// (AccountOrderStore.kt:1, :24): where a plan provider serves two or more, the pressed tab picks whose order the rail and
// the switch show.
const laneOf = (p) => p.cmds.find((c) => c.cmd === ui.lane[p.id]) || p.cmds[0];
function providerHtml(p) {
  const head = (sw) => `<section class="provider" style="--c:${colorOf(p.id)}" data-key="prov:${p.id}"><header><h2><span class="blot"></span>${esc(p.name)}</h2>${sw}</header>`;
  if (p.kind !== "plan") {
    const srv = serving(p), meters = (p.kind === "local" ? p.cmds.filter((c) => c.budget) : p.cmds).map((c) => meterHtml(c)).join("");
    // a model on this computer or a rented GPU bills nothing per token: no Day row, unless he already set a budget on it
    // (Marlin and fin, Oct 10), which keeps its row with no figure and no line
    return `${head("")}${meters}<div class="rail plain" data-rail="${p.id}">${p.accounts.map((a, i) => cardHtml(p, null, a, i, p.kind === "local" ? p.cmds.filter((c) => c.head === a.id) : a === srv ? p.cmds : [])).join("")}</div></section>`;
  }
  const c = laneOf(p), many = p.cmds.length > 1;
  return `${head(switchHtml(p, c))}${p.cmds.map((x) => meterHtml(x, many ? { p, on: x === c } : null)).join("")}${railHtml(p, c)}</section>`;
}
function addProviderHtml() {
  const have = new Set(data.map((p) => p.id));
  const st = ui.addProv;
  if (!st) return `<section class="provider" data-key="addprov"><header></header><button class="add" data-act="add-provider"><span class="plus">+</span>Add provider</button></section>`;
  if (st.stage === "pick") {
    const picks = CATALOG.filter((c) => !have.has(c.id)).map((c) => `<button class="pick" style="--c:${colorOf(c.id)}" data-act="pick" data-id="${c.id}"><span class="blot">${c.name[0]}</span>${c.name}</button>`).join("");
    return `<section class="provider" data-key="addprov"><header></header><div class="add signing" style="--c:var(--key-light)"><div class="providers">${picks}</div><button class="act quiet" data-act="cancel-prov">Cancel</button></div></section>`;
  }
  const c = CATALOG.find((x) => x.id === st.id);
  const inner = st.stage === "key"
    ? `<input class="field" id="key-in" type="password" placeholder="Paste key" aria-label="API key" autocomplete="off" spellcheck="false"><button class="act primary" data-act="save-key" disabled>Add</button><button class="act quiet" data-act="cancel-prov">Cancel</button>`
    : signingHtml("prov");
  const failed = ui.signin.prov?.state === "fail" ? " failed" : "";
  return `<section class="provider" style="--c:${colorOf(c.id)}" data-key="addprov"><header><h2><span class="blot"></span>${c.name}</h2></header>`
    + `<div class="add signing${failed}">${inner}</div></section>`;
}

// ---------- the board: providers stacked into columns, each into the shortest so far ----------
// The placement holds until the width or the set of providers changes, so opening an editor never throws a provider
// into the next column.
let layout = null;
const acctWeight = (a) => (a.out ? 3 : 2 + (a.windows || []).length + 0.6 * (a.models?.length || 0));
const weight = (p) => 4 + (p.kind === "local" ? 0 : p.cmds.length) + (p.kind !== "plan" ? 3
  : 2 * p.cmds.length + 1 + laneAccts(p, laneOf(p)).reduce((t, a) => t + acctWeight(a), 0)); // a day per command, one rail
function columns() {
  const gap = parseFloat(getComputedStyle(board).columnGap), rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
  const n = Math.max(1, Math.floor((board.clientWidth + gap) / (40 * rem + gap)));
  const ids = data.map((p) => p.id).join();
  if (!layout || layout.n !== n || layout.ids !== ids) {
    const cols = Array.from({ length: n }, () => ({ w: 0, ids: [] }));
    const shortest = () => cols.reduce((m, c) => (c.w < m.w ? c : m));
    for (const p of data) { const c = shortest(); c.ids.push(p.id); c.w += weight(p); }
    layout = { n, ids, cols: cols.map((c) => c.ids), addAt: cols.indexOf(shortest()) };
  }
  return layout;
}
addEventListener("resize", () => { const n = layout?.n; if (columns().n !== n) render(); });

// ---------- render, with every moved object sliding from where it was ----------
// Chrome fires focusout on a focused field as the board replaces it; `drawing` keeps that from reading as him leaving it.
let drawing = false;
function render({ flip = false } = {}) {
  const before = new Map();
  if (flip) for (const el of document.querySelectorAll("[data-key]")) before.set(el.dataset.key, el.getBoundingClientRect());
  const L = columns();
  drawing = true;
  // Add provider sits under the shortest column while a provider is left to add; with all of them here it has none to offer
  const addable = ui.addProv || CATALOG.some((c) => !data.some((p) => p.id === c.id));
  board.innerHTML = L.cols.map((ids, i) => `<div class="col">${ids.map((id) => providerHtml(findProv(id))).join("")}`
    + `${addable && i === L.addAt ? addProviderHtml() : ""}</div>`).join("");
  drawing = false;
  for (const b of board.querySelectorAll(".bar > b")) {
    const host = b.closest("[data-a], [data-meter]"), v = b.dataset.v;
    const id = host && (host.dataset.a || host.dataset.meter);
    const growing = ui.grow && id && (ui.grow === "all" || ui.grow.has(id));
    if (ui.probing) { b.style.width = "0%"; b.parentElement.classList.add("reading"); }
    else if (growing) { b.style.width = "0%"; b.parentElement.classList.add("grow"); requestAnimationFrame(() => requestAnimationFrame(() => (b.style.width = `${v}%`))); }
    else b.style.width = `${v}%`;
  }
  for (const box of board.querySelectorAll(".capbox")) {
    const { c } = findCmd(box.closest("[data-meter]").dataset.meter);
    box.insertAdjacentHTML("beforeend", `<i class="cap" style="left:calc(${Math.min(100, (c.budget.cap / Math.max(c.budget.cap, c.spent)) * 100)}% - .08rem)"></i>`);
  }
  ui.grow = null;
  if (ui.pulse) { board.querySelectorAll(`.card[data-a="${ui.pulse}"]`).forEach((el) => el.classList.add("pulse-once")); ui.pulse = null; }
  const menu = board.querySelector(".menu"); // a menu that would cross the bottom of the window opens upward
  if (menu && menu.getBoundingClientRect().bottom > innerHeight - 12) menu.classList.add("up");
  const focus = board.querySelector(".field.rename") || board.querySelector("#key-in");
  if (focus) { focus.focus(); if (focus.select) focus.select(); }
  if (!flip) return;
  const moved = new Map();
  for (const el of board.querySelectorAll("[data-key]")) {
    const a = before.get(el.dataset.key);
    if (!a) continue;
    const b = el.getBoundingClientRect();
    let dx = a.left - b.left, dy = a.top - b.top;
    const host = el.parentElement.closest("[data-key]");
    if (host && moved.has(host)) { dx -= moved.get(host)[0]; dy -= moved.get(host)[1]; }
    moved.set(el, [a.left - b.left, a.top - b.top]);
    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) continue;
    el.style.transition = "none"; el.style.transform = `translate(${dx}px, ${dy}px)`;
    requestAnimationFrame(() => requestAnimationFrame(() => { el.style.transition = "transform var(--t-move) var(--e-io)"; el.style.transform = ""; }));
  }
}
const findProv = (id) => data.find((p) => p.id === id);
const findAcct = (id) => data.flatMap((p) => p.accounts).find((a) => a.id === id);
const provOf = (a) => data.find((p) => p.accounts.includes(a));
function findCmd(name) {
  for (const p of data) { const c = p.cmds.find((x) => x.cmd === name); if (c) return { p, c }; }
  return {};
}
// ---------- a fresh reading every time the page opens: the refresh glyph turns, then the bars grow ----------
// splice asks every provider for its limits (POST /api/usage/probe), then the page reads everything again.
async function probe() {
  const glyph = document.querySelector('[data-act="probe"]');
  ui.probing = true; glyph.classList.add("turning"); render();
  await API.post("/api/usage/probe");
  await load();
  ui.probing = false; glyph.classList.remove("turning"); ui.grow = "all"; render();
}
// After an act: read again, and let what changed slide into place (grow: the account or command whose bars refill).
async function refresh(grow = null) {
  await load();
  ui.grow = grow ? new Set([grow]) : null;
  render({ flip: true });
}

// ---------- adding an account, inside the tile ----------
// A new Claude account belongs to the command it was added on; any other provider's goes to every command sharing the file.
function addAccount(pid, cmd) {
  const { c } = findCmd(cmd);
  startSignin(`add:${cmd}`, { start: `/api/auth/${encodeURIComponent(c.head)}/login`, body: {}, poll: c.head });
}
// ---------- adding a provider: one add on splice's own route (AddRoutes.kt), saved once it can sign in ----------
// The provider picks the profile splice adds it from (GET /api/add/profiles). Saving writes its command and restarts
// splice (AddRoutes.kt:115), so the tile waits until splice answers again with the provider on the board.
const PROFILE = { claude: "claude", gpt: "codex", grok: "grok", kimi: "kimi", muse: "muse", router: "openrouter", deepseek: "deepseek" };
async function pickProvider(c) {
  const res = await API.post("/api/add", { profile: PROFILE[c.id] });
  const add = res.ok ? res.body : null;
  ui.addProv = { id: c.id, stage: add?.sign_in_by === "key" ? "key" : "sign", add: add?.id || null, env: add?.key_env || null };
  if (ui.addProv.stage === "key") { render(); document.getElementById("key-in")?.focus(); return; }
  const done = () => saveAdd(c.id, "prov");
  if (add?.sign_in_by === "login") {
    startSignin("prov", { start: `/api/add/${encodeURIComponent(add.id)}/login`, watch: `/api/add/${encodeURIComponent(add.id)}`, done });
    return;
  }
  const again = () => { dropAdd(); pickProvider(c); }; // Try again opens the add afresh: there is no sign-in to restart
  ui.signin.prov = { how: { done, again }, state: "wait" }; render(); // nothing to sign in to (Claude's login is forwarded)
  // splice refuses a provider whose name or command is taken with a 409 naming that field and no failure kind
  // (AddRefusal.field): that reads Already added, not Not completed
  const taken = res.status === 409 && ["name", "command"].includes(res.body?.field);
  if (add) finishSignin("prov"); else failSignin("prov", taken ? { failure_kind: "already_added" } : res.body);
}
// The save, then splice's restart: true once the provider is back on the board. A refused save, or a splice that does
// not come back in a minute and a half, shows on the tile it was added from.
async function saveAdd(pid, target) {
  const res = await API.post(`/api/add/${encodeURIComponent(ui.addProv.add)}/save`);
  if (!res.ok) { if (target === "key") keyState(document.getElementById("key-in"), "Not saved"); else failSignin(target, res.body); return false; }
  ui.addProv.add = null; // saved: nothing left to discard
  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    await load();
    if (data.some((p) => p.id === pid)) return true;
  }
  if (target === "key") keyState(document.getElementById("key-in"), "Not saved"); else failSignin(target);
  return false;
}
function dropAdd() { // an add that will not be saved is closed on splice too
  if (ui.addProv?.add) API.del(`/api/add/${encodeURIComponent(ui.addProv.add)}`);
  ui.addProv = null;
}
async function saveKey() {
  const st = ui.addProv, field = document.getElementById("key-in");
  if (!st.add || !st.env) { keyState(field, "Not saved"); return; }
  const res = await API.put(`/api/keys/${encodeURIComponent(st.env)}`, { value: pasted() });
  if (!res.ok) { keyState(field, "Not saved"); return; }
  field.parentElement.querySelectorAll("button").forEach((b) => { b.disabled = true; });
  if (await saveAdd(st.id, "key")) { ui.addProv = null; await refresh(st.id); }
  else field.parentElement.querySelectorAll("button").forEach((b) => { b.disabled = false; });
}
const pasted = () => document.getElementById("key-in").value.trim(); // the value goes one way: into the store, never back
// The key field's state, set in place so the pasted value is never written back into the page. Save stays off while the
// field is empty (KeyWrites.kt:26).
function keyState(field, word) {
  const save = field.parentElement.querySelector('[data-act="save-replace"], [data-act="save-key"]');
  let el = field.parentElement.querySelector(".key-err");
  if (save) save.disabled = !field.value.trim();
  if (!word) { el?.remove(); field.removeAttribute("aria-invalid"); field.removeAttribute("aria-describedby"); return; }
  if (!el) { el = Object.assign(document.createElement("span"), { className: "state limit key-err", id: "key-err" }); el.setAttribute("role", "status"); field.after(el); }
  el.textContent = word; field.setAttribute("aria-invalid", "true"); field.setAttribute("aria-describedby", "key-err");
  const box = field.parentElement; box.classList.remove("failed"); void box.offsetWidth; box.classList.add("failed");
}
// An order names only the accounts splice holds in this command's order (its effective_order): it refuses a whole order
// naming one it doesn't, such as a login place that never held a sign-in, and My order then fell back to Soonest reset.
const orderLabels = (c, list) => list.map((x) => labelOn(x, c.head)).filter((l) => c.orderable?.has(l));
// ---------- reorder: drag by the grip, or arrows on it ----------
// The new order is the command's own (PUT /api/auth/{head}/order); a refused one reads the order splice still holds.
async function moveTo(p, c, aid, index) {
  const list = [...ordered(p, c)], from = list.findIndex((a) => a.id === aid);
  const [a] = list.splice(from, 1); list.splice(Math.max(0, Math.min(index, list.length)), 0, a);
  const unpin = c.pin === aid; // moving the pinned account by hand is placing it: the pin gives way
  c.order = list.map((x) => x.id); c.mode = "mine"; if (unpin) c.pin = null; render({ flip: true });
  if (unpin) await API.del(`/api/auth/${encodeURIComponent(c.head)}/switch`);
  await API.put(`/api/auth/${encodeURIComponent(c.head)}/order`, { order: orderLabels(c, list) });
  await refresh();
}
// ---------- keys: arrows on a grip, Enter and Escape in the fields ----------
board.addEventListener("keydown", (e) => {
  const g = e.target.closest("[data-grip]");
  if (g && (e.key === "ArrowUp" || e.key === "ArrowDown")) {
    e.preventDefault();
    const p = findProv(g.dataset.grip), c = findCmd(g.dataset.c).c, i = ordered(p, c).findIndex((a) => a.id === g.dataset.a);
    moveTo(p, c, g.dataset.a, i + (e.key === "ArrowUp" ? -1 : 1));
    board.querySelector(`[data-grip][data-c="${c.cmd}"][data-a="${g.dataset.a}"]`)?.focus();
    return;
  }
  if (e.key !== "Enter") return;
  if (e.target.matches(".code-in")) submitCode(e.target);
  else if (e.target.matches(".rename")) saveRename(e.target, true);
  else if (e.target.matches("#key-in")) e.target.parentElement.querySelector('[data-act="save-replace"], [data-act="save-key"]')?.click();
});
board.addEventListener("paste", (e) => {
  if (e.target.matches(".code-in")) setTimeout(() => submitCode(e.target), 0);
  if (!e.target.matches("#key-in")) return;
  // A pasted key loses the spaces and line breaks at its ends; one left inside is a key splice won't store
  // (KeyWrites.kt:27). A text field would join it silently, so the paste is read here, and a key it can't take
  // leaves the field empty: behind the dots, one typed character would turn Save on for a key he can't see.
  e.preventDefault();
  const v = e.clipboardData.getData("text").trim(), f = e.target, bad = /[\r\n]/.test(v);
  f.value = bad ? "" : v;
  keyState(f, bad ? "Invalid key" : null);
});
board.addEventListener("input", (e) => {
  if (e.target.matches("#key-in")) keyState(e.target, null);
  // a typed amount is the choice now, so a preset it no longer matches loses its outline at once (Marlin's walk, Oct 10)
  if (e.target.matches("#cap-in")) {
    const v = Number(e.target.value);
    if (v > 0) ui.editor.cap = v;
    e.target.closest(".editor").querySelectorAll('[data-act="preset"]').forEach((b) => b.setAttribute("aria-pressed", String(Number(b.dataset.v) === v)));
  }
});
// Save takes the press without taking focus, so the field's blur can't save and redraw before Save's click lands. Tab
// between the field and Save keeps the rename open; leaving both saves, as leaving the field always did.
const inRename = (el) => !!el?.matches?.(".rename, [data-act=finish-rename]");
board.addEventListener("mousedown", (e) => { if (e.target.closest("[data-act=finish-rename]")) e.preventDefault(); });
board.addEventListener("focusout", (e) => {
  const f = board.querySelector(".field.rename");
  if (!drawing && f && inRename(e.target) && !inRename(e.relatedTarget)) saveRename(f, false);
});
const closeAll = () => Object.assign(ui, { menu: null, armed: null, removeErr: null, rename: null, renameErr: null, renameDraft: null, replace: null, editor: null });
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape" || !(ui.menu || ui.rename || ui.replace || ui.editor)) return;
  closeAll(); render();
});
// Enter on a name splice won't take keeps the field open with the reason; leaving the field keeps the old name.
// Enter on a name splice won't take keeps the field open with the reason; leaving the field keeps the old name.
async function saveRename(field, enter) {
  if (ui.rename !== field.dataset.s) return;
  const a = findAcct(field.dataset.a), v = field.value.trim();
  const err = v && v !== a.name ? nameError(provOf(a), a, v) : null;
  if (err && enter) { ui.renameErr = err; ui.renameDraft = v; render(); return; }
  if (v && !err && v !== a.name) {
    // a name splice would take but the save fails: the field stays with the reason, even when he leaves it
    const res = await API.patch(editPath(a), { label: v });
    if (!res.ok) { ui.renameErr = "Not renamed"; ui.renameDraft = v; render(); return; }
    Object.assign(ui, { rename: null, renameErr: null, renameDraft: null });
    await refresh(); return;
  }
  Object.assign(ui, { rename: null, renameErr: null, renameDraft: null }); render();
}
// Where an account is renamed or removed: under its command, naming exactly this login (AccountEditRoutes.kt:78, :95).
function editPath(a) {
  const t = a.row.edit_target, label = t ? t.id : a.row.label || "primary";
  const q = t ? `?target_kind=${encodeURIComponent(t.kind)}&target_id=${encodeURIComponent(t.id)}` : "";
  return `/api/auth/${encodeURIComponent(a.row.heads[0])}/accounts/${encodeURIComponent(label)}${q}`;
}

// ---------- every other act, each one splice's own route ----------
const headPath = (c, tail) => `/api/auth/${encodeURIComponent(c.head)}/${tail}`;
document.addEventListener("click", async (e) => {
  const t = e.target.closest("[data-act]");
  if (ui.menu && !e.target.closest(".menu, [data-act=menu]")) { ui.menu = null; ui.armed = null; ui.removeErr = null; if (!t) { render(); return; } }
  if (!t) return;
  const act = t.dataset.act, p = t.dataset.p && findProv(t.dataset.p), a = t.dataset.a && findAcct(t.dataset.a);
  const k = t.dataset.c && findCmd(t.dataset.c).c;
  switch (act) {
    case "theme": document.documentElement.dataset.theme = document.documentElement.dataset.theme === "day" ? "night" : "day"; break;
    case "probe": probe(); break;
    // Soonest reset is no order of his own (AccountPool.kt:101, :259); My order keeps the order shown, as his.
    case "mode": {
      const order = t.dataset.v === "mine" ? ruled(p, k) : [];
      k.mode = t.dataset.v; if (order.length) k.order = order.map((x) => x.id); render({ flip: true });
      await API.put(headPath(k, "order"), { order: orderLabels(k, order) }); await refresh(); break;
    }
    // Use now pins the account on that command: it serves from the next request, the rule stays, and the pin undoes it
    // (SwitchRoute.kt).
    case "use": k.pin = a.id; render({ flip: true }); await API.post(headPath(k, "switch"), { label: labelOn(a, k.head) }); await refresh(); break;
    case "unpin": k.pin = null; render({ flip: true }); await API.del(headPath(k, "switch")); await refresh(); break;
    case "lane": ui.lane[p.id] = k.cmd; closeAll(); render({ flip: true }); break; // the rail shows that command's order
    case "add": addAccount(p.id, k.cmd); break;
    case "signin": startSignin(`out:${a.id}`, howFor(p, a, null)); break;
    case "open-signin": openSignin(t.dataset.t); break;
    case "copy-code":
      navigator.clipboard?.writeText(ui.signin[t.dataset.t].code).catch(() => {});
      t.classList.add("copied"); setTimeout(() => t.classList.remove("copied"), 1200); break;
    case "retry-signin": { const s = ui.signin[t.dataset.t]; if (s.how.again) s.how.again(); else waitSignin(t.dataset.t); break; }
    case "cancel-signin": cancelSignin(t.dataset.t); break;
    case "menu": ui.menu = ui.menu === t.dataset.s ? null : t.dataset.s; ui.armed = null; ui.removeErr = null; render(); board.querySelector(".menu button")?.focus(); break;
    case "rename": ui.menu = null; ui.rename = t.dataset.s; render(); break;
    case "finish-rename": { const f = board.querySelector(".field.rename"); if (f) saveRename(f, true); break; } // Enter's path
    case "resign": ui.menu = null; startSignin(`re:${a.id}`, howFor(p, a, null), t.dataset.s); break;
    case "replace": ui.menu = null; ui.replace = t.dataset.s; render(); break;
    case "cancel-replace": ui.replace = null; render(); break;
    // Remove takes two presses on the same item; removal deletes the sign-in, so nothing comes back from it.
    case "remove": {
      if (ui.armed !== t.dataset.s) { ui.armed = t.dataset.s; render(); board.querySelector(".menu .armed")?.focus(); break; }
      const res = p.kind === "key" ? await API.del(`/api/keys/${encodeURIComponent(a.env)}`) : await API.del(editPath(a));
      if (!res.ok) { // refused: the menu stays open and says why. In use: Claude Code's own sign-in, carrying a request now
        ui.removeErr = { spot: t.dataset.s, word: a.native ? "In use" : "Not removed" }; ui.armed = null; render(); break;
      }
      ui.menu = null; ui.armed = null;
      board.querySelectorAll(`.card[data-a="${CSS.escape(a.id)}"]`).forEach((el) => el.classList.add("leaving"));
      setTimeout(() => refresh(), 170);
      break;
    }
    case "save-replace": {
      if (!pasted()) break;
      const res = await API.put(`/api/keys/${encodeURIComponent(a.env)}`, { value: pasted() });
      if (!res.ok) { keyState(document.getElementById("key-in"), "Not saved"); break; }
      ui.replace = null; await refresh(); break;
    }
    case "edit-budget": ui.editor = { c: k.cmd, cap: k.budget ? k.budget.cap : 10, block: k.budget ? k.budget.block : false }; render(); document.getElementById("cap-in")?.focus(); break;
    case "preset": ui.editor.cap = Number(t.dataset.v); render(); break;
    case "bmode": ui.editor.cap = Number(document.getElementById("cap-in").value) || ui.editor.cap; ui.editor.block = t.dataset.v === "block"; render(); break;
    // splice keeps the whole set of budgets (BudgetRoutes.kt:71): every other command's goes back unchanged.
    case "save-budget": {
      const v = Number(document.getElementById("cap-in").value), cap = v > 0 ? v : ui.editor.cap, block = ui.editor.block;
      const set = data.flatMap((x) => x.cmds).filter((c) => c.budget && c.cmd !== k.cmd).map((c) => ({ head: c.head, daily_usd: c.budget.cap, action: c.budget.block ? "block" : "warn" }));
      set.push({ head: k.head, daily_usd: cap, action: block ? "block" : "warn" });
      const res = await API.put("/api/budgets", { budgets: set });
      ui.editor = null;
      if (res.ok) await refresh(k.cmd); else render();
      break;
    }
    case "cancel-budget": ui.editor = null; render(); break;
    case "add-provider": ui.addProv = { stage: "pick" }; render(); break;
    case "cancel-prov": dropAdd(); render(); break;
    case "pick": pickProvider(CATALOG.find((x) => x.id === t.dataset.id)); break;
    case "save-key": if (pasted()) saveKey(); break;
  }
});

// a door from another page lands on one account: accounts.html?card=<provider>/<account>
const ADDR = new URLSearchParams(location.search);
probe().then(() => {
  if (!ADDR.get("card")) return;
  const el = board.querySelector(`[data-key^="card:${CSS.escape(ADDR.get("card"))}"]`);
  if (el) { el.scrollIntoView({ block: "center", behavior: "instant" }); el.classList.add("pulse-once"); }
});
