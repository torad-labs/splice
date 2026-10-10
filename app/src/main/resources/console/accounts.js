// Accounts: every account on every provider, how much of each window is used, when it comes back, which one
// serves next, and what each command may spend in a day. The drawing (Splice-Animator design/console, d3338fa) on
// splice's own answers: every name, number and state below is read from the control API, and every act calls it.
"use strict";

let NOW = new Date(); // read again at every load, so a reset reads in the viewer's own clock
const LEN = { "5 hours": 300, Week: 10080, Month: 43200, Day: 1440 };
const COLORS = { claude: "--claude", gpt: "--gpt", grok: "--grok", kimi: "--kimi", muse: "--muse", router: "--router", deepseek: "--deepseek", local: "--local" };

// splice's budget day is the UTC day (BudgetEnforcement.kt:10), so it refills at UTC midnight, read in the viewer's clock.
function dayLeft() {
  const next = Date.UTC(NOW.getUTCFullYear(), NOW.getUTCMonth(), NOW.getUTCDate() + 1);
  return Math.round((next - NOW.getTime()) / 60000);
}

// The provider a command belongs to, by the family splice gives its head (/api/status registry, ProviderFamilyRule.kt).
const FAMILY = {
  anthropic: { id: "claude", name: "Claude" }, openai: { id: "gpt", name: "ChatGPT" }, xai: { id: "grok", name: "Grok" },
  moonshot: { id: "kimi", name: "Kimi" }, meta: { id: "muse", name: "Muse" }, openrouter: { id: "router", name: "OpenRouter" },
  deepseek: { id: "deepseek", name: "DeepSeek" }, local: { id: "local", name: "This computer" },
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
function windowOf(pct, resetSec, lenSec, slot) {
  if (pct == null) return null;
  const month = slot === "long" && lenSec >= 28 * 86400;
  const label = slot === "short" ? "5 hours" : month ? "Month" : "Week";
  const left = resetSec ? Math.round((resetSec * 1000 - NOW.getTime()) / 60000) : null;
  return { label, used: Math.round(pct), len: LEN[label], left: left != null && left >= 0 ? left : null };
}
const plainPlan = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : undefined);
const acctId = (row) => row.selector_key || row.label || "primary";

// One read of everything the page shows. Each provider: its commands (with their order, pin and day) and its accounts.
async function load() {
  NOW = new Date();
  const [st, ac, ks, bg, ec, md] = await Promise.all(["/api/status", "/api/accounts", "/api/keys", "/api/budgets", "/api/economics", "/api/models"].map((p) => API.get(p)));
  if (!st.ok || !ac.ok) { data = []; offline = true; return; }
  offline = false;
  const budgets = new Map((bg.body?.budgets || []).map((b) => [b.head, b]));
  const dayStart = Date.UTC(NOW.getUTCFullYear(), NOW.getUTCMonth(), NOW.getUTCDate());
  const spendToday = new Map((ec.body?.heads || []).map((h) => [h.key, (h.buckets || []).filter((b) => b.hour >= dayStart)
    .reduce((t, b) => ({ usd: t.usd + (b.cost_usd || 0), unpriced: t.unpriced + (b.unpriced_turns || 0) }), { usd: 0, unpriced: 0 })]));
  const pinnedModel = new Map((md.body?.heads || []).map((h) => [h.head, (h.models || []).find((m) => m.pinned)?.label || h.pinned_model]));
  const keyRows = ks.body?.keys || [];
  const provs = new Map();
  for (const h of st.body.registry || []) {
    const fam = FAMILY[h.family] || { id: h.family || h.key, name: h.family || h.key };
    const kind = h.family === "local" ? "local" : h.authKind === "api-key" ? "key" : "plan";
    if (!provs.has(fam.id)) provs.set(fam.id, { id: fam.id, name: fam.name, kind, cmds: [], accounts: [] });
    const p = provs.get(fam.id), b = budgets.get(h.key), today = spendToday.get(h.key) || { usd: 0, unpriced: 0 };
    p.cmds.push({ cmd: h.label || h.key, head: h.key, spent: b ? b.used_usd : today.usd, unpriced: b ? b.unpriced_turns : today.unpriced,
      budget: b && b.daily_usd != null ? { cap: b.daily_usd, block: b.action === "block" } : null, mode: "soonest", pin: null, order: [] });
  }
  for (const p of provs.values()) {
    const heads = new Set(p.cmds.map((c) => c.head));
    if (p.kind === "plan") {
      for (const row of ac.body.accounts || []) {
        if (!(row.heads || []).some((x) => heads.has(x))) continue;
        const windows = [windowOf(row.five_hour_used_percent, row.five_hour_reset_epoch_seconds, row.five_hour_window_seconds, "short"),
          windowOf(row.seven_day_used_percent, row.seven_day_reset_epoch_seconds, row.seven_day_window_seconds, "long")].filter(Boolean);
        const fresh = row.five_hour_current || row.seven_day_current;
        p.accounts.push({ id: acctId(row), row, name: row.display_name, email: row.account?.email || undefined, plan: plainPlan(row.plan), windows,
          staleAt: !fresh && row.observed_at_epoch_seconds && windows.length ? Math.round((row.observed_at_epoch_seconds * 1000 - NOW.getTime()) / 60000) : null,
          out: !row.credential_present || row.auth_exclusion_reason === "credential_missing" || !!row.refusal,
          canRename: !!row.can_rename, canRemove: !!row.can_remove, native: !!row.carrying_request,
          // each model's own weekly window, drawn under the week (Claude's Opus and Sonnet)
          models: (row.seven_day_models || []).map((m) => [m.model, Math.round(m.used_percent)]) });
      }
    } else if (p.kind === "key") {
      for (const k of keyRows) {
        const uses = (k.heads || []).filter((x) => heads.has(x.head));
        if (!uses.length) continue;
        const from = uses[0].source;
        p.accounts.push({ id: k.name, env: k.name, has: from !== "missing" && from !== "unknown", from: from === "missing" || from === "unknown" ? null : from, seen: null });
      }
    } else {
      for (const c of p.cmds) p.accounts.push({ id: c.head, name: c.cmd, model: pinnedModel.get(c.head) || "" });
    }
  }
  // each plan command's own order and pin (AccountOrderStore.kt:1, :24): no order is the soonest-reset rule
  await Promise.all([...provs.values()].filter((p) => p.kind === "plan").flatMap((p) => p.cmds.map(async (c) => {
    const o = await API.get(`/api/auth/${encodeURIComponent(c.head)}/order`);
    const mine = p.accounts.filter((a) => (a.row.heads || []).includes(c.head));
    const ids = (o.body?.effective_order || []).filter((id) => mine.some((a) => a.id === id));
    c.order = [...ids, ...mine.map((a) => a.id).filter((id) => !ids.includes(id))];
    c.mode = o.body?.order?.length ? "mine" : "soonest";
    c.pin = mine.find((a) => a.row.pinned)?.id || null;
    c.serving = o.body?.next_target ?? null; c.following = o.body?.following_target ?? null; // the pool's own picks
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
function at(left) {
  const d = new Date(NOW.getTime() + left * 60000);
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
  const key = (a) => (room(a) ? a.windows[0]?.left ?? Infinity : held(a) ? backAt(a) : 0);
  return list.sort((a, b) => rank(a) - rank(b) || key(a) - key(b));
}
// A pin is tried first; a pinned account with no room falls through to the rule and keeps its pin (AccountPool.kt:84-85).
function ordered(p, c) {
  if (p.kind !== "plan") return p.accounts;
  const list = ruled(p, c), pinned = list.find((a) => a.id === c.pin);
  return pinned && room(pinned) ? [pinned, ...list.filter((a) => a !== pinned)] : list;
}
const serving = (p, c) => (p.kind === "plan" ? (c?.serving && p.accounts.find((a) => a.id === c.serving)) || ordered(p, c).find(room) : p.kind === "key" ? p.accounts.find((k) => k.has) : p.accounts[0]);
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
const resets = (w) => (w.left == null ? "<span></span>" : `<span class="when">${ring(w)}Resets ${at(w.left)}</span>`); // a reading whose reset has passed shows no time

function windowsHtml(a, next = null) {
  const stale = a.staleAt != null;
  let rows = "";
  for (const w of a.windows) {
    const full = w.used >= 100;
    rows += `<span class="label">${w.label}</span>${bar(Math.min(w.used, 100), `${full ? "full" : ""} ${stale ? "stale" : ""}`)}`
      + `<span class="pct ${full ? "limit" : ""}">${w.used}%<span class="used">used</span></span>${resets(w)}`;
    if (w.label === "Week" && a.models) {
      for (const [m, used] of a.models) rows += `<span class="label sub">${m}</span>${bar(used, `sub ${used >= 100 ? "full" : ""} ${stale ? "stale" : ""}`)}<span class="pct">${used}%<span class="used">used</span></span><span></span>`;
    }
  }
  if (stale) rows += `<span class="stale-at">${ICON.eye}Read ${at(a.staleAt)}</span>`;
  if (next) { // a held next is the one serving at its reset: its lock and that reset, on the windows' own columns
    const w = held(next) && next.windows.filter((x) => x.used >= 100).sort((x, y) => y.left - x.left)[0];
    rows += `<span class="next"><span class="label">Next</span><span class="who">${esc(next.name)}</span>${w ? `${locked()}${resets(w)}` : ""}</span>`;
  }
  return `<div class="windows">${rows}</div>`;
}

// A command's day: what it has spent since the UTC day began, against the budget he set, and when it refills. The
// command's tab names the row, because one provider can serve several commands. Where a plan provider's commands share
// one rail, the tab is pressed to show that command's order: pick is { p, on } there, and null everywhere else.
function meterHtml(c, pick = null) {
  const day = { left: dayLeft(), len: LEN.Day };
  const tag = pick ? `<button class="chip tag" data-act="lane" data-p="${pick.p.id}" data-c="${c.cmd}" aria-pressed="${pick.on}">${esc(c.cmd)}</button>`
    : `<span class="chip tag">${esc(c.cmd)}</span>`;
  if (ui.editor && ui.editor.c === c.cmd) return `<div class="meter" data-meter="${c.cmd}">${editorHtml(c, tag)}</div>`;
  if (!c.budget) {
    return `<div class="meter windows spend" data-meter="${c.cmd}">${tag}<span class="label">Day</span><span class="money">${money(c.spent)}${unpriced(c)}</span>`
      + `<button class="act quiet" data-act="edit-budget" data-c="${c.cmd}">Set budget</button>${resets(day)}</div>`;
  }
  const pct = Math.min(100, Math.round((c.spent / c.budget.cap) * 100)), mode = c.budget.block ? "Block" : "Warn";
  const spoken = `${money(c.spent)} of ${cap(c.budget.cap)}, ${mode}${c.unpriced ? `, leaves out ${c.unpriced} ${c.unpriced === 1 ? "turn" : "turns"} with no price` : ""}`;
  return `<div class="meter windows spend" data-meter="${c.cmd}">${tag}<span class="label">Day</span><div class="capbox">${bar(pct, c.spent >= c.budget.cap ? "full" : "")}</div>`
    + `<button class="money act quiet" data-act="edit-budget" data-c="${c.cmd}" aria-label="${spoken}">${c.budget.block ? ICON.stop : ICON.bell}${money(c.spent)} <em>of</em> ${cap(c.budget.cap)}${unpriced(c)}</button>${resets(day)}</div>`;
}
// turns on a model with no rate card, after the dollar figure (fin). A meter's money column is narrow at every width, so
// here the words take their own line under the figure, with no dot (fin)
const unpriced = (c) => (c.unpriced ? `<span class="unpriced own">leaves out ${c.unpriced} ${c.unpriced === 1 ? "turn" : "turns"} with no price</span>` : "");
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
  if (!res.ok || !res.body?.id) { failSignin(target); return; }
  s.id = res.body.id; seen(target, res.body);
}
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
    const res = await API.get(`/api/auth/${encodeURIComponent(s.how.poll)}/login/${encodeURIComponent(s.id)}`);
    if (ui.signin[target] !== s || s.gen !== gen) return;
    if (!res.ok) { failSignin(target); return; }
    seen(target, res.body);
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
  delete ui.signin[target];
  if (target === "prov") ui.addProv = null;
  await refresh(s.spot ? s.spot.split("/")[1] : "new");
}
function cancelSignin(target) {
  if (ui.signin[target]) stopTimers(ui.signin[target]);
  delete ui.signin[target];
  if (target === "prov") ui.addProv = null;
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
  return { start: `/api/auth/${encodeURIComponent(head)}/login`, body: a.row?.label ? { label: a.row.label } : {}, poll: head };
}
// ---------- an account ----------
// serves: every command this account serves now, each riding it as a chip. Use now pins it on the rail's command.
function slotHtml(p, c, a, serves) {
  const pin = c && c.pin === a.id ? `<button class="pin" data-act="unpin" data-p="${p.id}" data-c="${c.cmd}" aria-label="Unpin" aria-pressed="true">${ICON.pin}</button>` : "";
  const chips = serves.map((x) => `<span class="chip" data-key="chip:${x.cmd}"><i></i>${esc(x.cmd)}</span>`).join("");
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
  return `<input class="field rename" data-a="${a.id}" data-s="${spot}" value="${esc(ui.renameDraft ?? a.name)}" aria-label="Name" spellcheck="false"${err ? ' aria-invalid="true" aria-describedby="rename-err"' : ""}>`
    + (err ? `<span class="state limit" id="rename-err">${err}</span>` : "");
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
  } else if (p.kind === "plan") body = windowsHtml(a, c && serving(p, c) === a ? nextOf(p, c) : null) + signRow;
  else body = `<div class="local-model"><i></i>${esc(a.model)}</div>`;
  const socket = p.kind === "plan" ? `<span class="socket">${i + 1}</span>` : "";
  return `<article class="${cls}" data-key="card:${spot}" data-p="${p.id}" data-a="${a.id}">${socket}<div class="top">${grip}`
    + `${nameHtml(p, a, spot)}${sub}<div class="slot">${slotHtml(p, c, a, serves)}</div>${more}</div>${email}${ui.menu === spot ? menuHtml(p, a, spot) : ""}${body}</article>`;
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
    const srv = serving(p), meters = p.kind === "local" ? "" : p.cmds.map((c) => meterHtml(c)).join(""); // no rates, no day to budget
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
  board.innerHTML = L.cols.map((ids, i) => `<div class="col">${ids.map((id) => providerHtml(findProv(id))).join("")}`
    + "</div>").join("");
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
// ---------- reorder: drag by the grip, or arrows on it ----------
// The new order is the command's own (PUT /api/auth/{head}/order); a refused one reads the order splice still holds.
async function moveTo(p, c, aid, index) {
  const list = [...ordered(p, c)], from = list.findIndex((a) => a.id === aid);
  const [a] = list.splice(from, 1); list.splice(Math.max(0, Math.min(index, list.length)), 0, a);
  const unpin = c.pin === aid; // moving the pinned account by hand is placing it: the pin gives way
  c.order = list.map((x) => x.id); c.mode = "mine"; if (unpin) c.pin = null; render({ flip: true });
  if (unpin) await API.del(`/api/auth/${encodeURIComponent(c.head)}/switch`);
  await API.put(`/api/auth/${encodeURIComponent(c.head)}/order`, { order: c.order });
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
board.addEventListener("input", (e) => { if (e.target.matches("#key-in")) keyState(e.target, null); });
board.addEventListener("focusout", (e) => { if (!drawing && e.target.matches(".rename")) saveRename(e.target, false); });
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
      const order = t.dataset.v === "mine" ? ruled(p, k).map((x) => x.id) : [];
      k.mode = t.dataset.v; if (order.length) k.order = order; render({ flip: true });
      await API.put(headPath(k, "order"), { order }); await refresh(); break;
    }
    // Use now pins the account on that command: it serves from the next request, the rule stays, and the pin undoes it
    // (SwitchRoute.kt).
    case "use": k.pin = a.id; render({ flip: true }); await API.post(headPath(k, "switch"), { label: a.id }); await refresh(); break;
    case "unpin": k.pin = null; render({ flip: true }); await API.del(headPath(k, "switch")); await refresh(); break;
    case "lane": ui.lane[p.id] = k.cmd; closeAll(); render({ flip: true }); break; // the rail shows that command's order
    case "add": addAccount(p.id, k.cmd); break;
    case "signin": startSignin(`out:${a.id}`, howFor(p, a, null)); break;
    case "open-signin": openSignin(t.dataset.t); break;
    case "copy-code":
      navigator.clipboard?.writeText(ui.signin[t.dataset.t].code).catch(() => {});
      t.classList.add("copied"); setTimeout(() => t.classList.remove("copied"), 1200); break;
    case "retry-signin": waitSignin(t.dataset.t); break;
    case "cancel-signin": cancelSignin(t.dataset.t); break;
    case "menu": ui.menu = ui.menu === t.dataset.s ? null : t.dataset.s; ui.armed = null; ui.removeErr = null; render(); board.querySelector(".menu button")?.focus(); break;
    case "rename": ui.menu = null; ui.rename = t.dataset.s; render(); break;
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
  }
});

// a door from another page lands on one account: accounts.html?card=<provider>/<account>
const ADDR = new URLSearchParams(location.search);
probe().then(() => {
  if (!ADDR.get("card")) return;
  const el = board.querySelector(`[data-key^="card:${CSS.escape(ADDR.get("card"))}"]`);
  if (el) { el.scrollIntoView({ block: "center", behavior: "instant" }); el.classList.add("pulse-once"); }
});
