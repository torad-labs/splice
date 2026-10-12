// Usage: what each plan and key used, and where it went, on splice's own answers. The drawing is Splice-Animator
// design/console/usage.html (build 3); every figure here is read from the control API and nothing is invented.
//
// WHERE EVERY VALUE COMES FROM.
//   GET /api/heads · /api/models · /api/status   the commands, each one's provider, the models' own labels
//   GET /api/accounts · /api/keys                a plan's windows as its provider reads them (Accounts' numbers), a key's
//                                                variable and the day splice first saw it
//   GET /api/sessions                            an agent's name and its project
//   GET /api/perf/turns?head=…&n=1               the window's TOTALS per account, model, session and day, folded by the
//                                                daemon over every request the filters match (TurnUsage.kt), so a week's
//                                                figures are never a count of the newest 2,000 rows
//   the same, n=2000 and usage=0                 the newest requests: the 5-hour columns, each command's speed and its
//                                                failures. Where a command holds more than it returned, its card says so.
//
// PLANS AND KEYS NEVER MEET IN ONE MONEY FIGURE (fin, Oct 11). A plan's requests read "On your plan", with their value at
// API prices as a value, never as money spent; only a key's requests are money. A count that is splice's own says so,
// so it never passes for the provider's window. A figure nobody reported is left out, never printed as a zero.
"use strict";

const n0 = (n) => Math.round(n).toLocaleString("en-US");
const secs = (ms) => (ms < 1000 ? `${Math.round(ms)} ms` : ms < 60000 ? `${+(ms / 1000).toFixed(ms < 10000 ? 1 : 0)} s` : `${Math.floor(ms / 60000)} min ${Math.round((ms % 60000) / 1000)} s`);
const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const hourWord = (d) => `${d.getHours() % 12 || 12} ${d.getHours() < 12 ? "AM" : "PM"}`;
const dayClock = (d) => (d.toDateString() === NOW.toDateString() ? clock(d) : `${DAY[d.getDay()]} ${clock(d)}`);
const q = (xs, p) => { if (!xs.length) return null; const s = xs.slice().sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
const failed = (r) => isFailed(r.outcome);
const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone; // the daemon folds days in the viewer's zone
const HOUR = 3600e3, DAY_MS = 86400e3;
const WINDOWS = { "5h": { label: "5 hours", ms: 5 * HOUR }, "7d": { label: "7 days", ms: 7 * DAY_MS } };
const SEP = "\u001f"; // one account can carry several labels (its email, its selector name); a merged key joins them after the head
const MAX_HELD = 2000; // the most rows one read of a command carries (PerfRoutes.kt MAX_TURNS)
const LEN = { "5 hours": 300, Week: 10080, Month: 43200 };
const FAMILY = PROVIDER_FAMILY;
const NOT_READ = "Not read";
const EYE = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>';
const OPEN = '<svg class="go" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 17L17 7M9 7h8v8"/></svg>';
const UP = G('<path d="M12 19V5M6 11l6-6 6 6"/>', 'aria-hidden="true"');
const DOWN = '<svg class="go" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14M6 13l6 6 6-6"/></svg>';

const ui = { win: "7d", measure: "tokens", hover: null, by: "account", f: {}, more: false, open: new Set(), from: null, project: PICK.get(), pmenu: false };
const root = document.getElementById("usage");

// ---------- what splice has told this page ----------
const S = { heads: [], providerOf: {}, labels: {}, sessions: {}, keys: [], accounts: [], registry: [], groups: [], error: null };
// one window's reads: the totals the daemon folded, and the newest rows. [all] is the window with the project pick alone
// (the plan and key cards), [scoped] the same under the filters opened below the cards.
const D = { loading: true, pending: false, every: null, all: null, scoped: null, rows: [], held: {}, errors: [], gen: 0 };

const providerOfHead = (key) => S.providerOf[key];
const cmdOf = (key) => S.heads.find((h) => h.key === key)?.command ?? key;
const sessTitle = (tag, fallback) => {
  const s = S.sessions[tag];
  return s?.name || s?.title || fallback || `Session ${tag}`;
};
const hasFilter = () => Object.keys(ui.f).length > 0;

async function readStanding() {
  const [heads, models, sessions, keys, accounts, status, topology] = await Promise.all(
    ["/api/heads", "/api/models", "/api/sessions", "/api/keys", "/api/accounts", "/api/status", "/api/topology"].map((p) => API.get(p)));
  if (!heads.ok) { S.error = NOT_READ; console.warn(`commands: ${heads.status}`); return; }
  S.error = null;
  S.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key }));
  const bases = topology.body?.topology?.providers || {};
  const local = (name) => /^https?:\/\/(127\.0\.0\.1|\[?::1\]?|localhost)\b/.test(bases[name]?.base_url || "");
  for (const row of models.body?.heads || []) {
    S.providerOf[row.head] = FAMILY[row.provider] ?? (local(row.provider) ? "local" : row.provider);
    learnModels(S.labels, row);
  }
  S.sessions = {};
  const rowsOfSessions = sessions.body?.sessions || [];
  for (const s of rowsOfSessions) if (s.session_id) { S.sessions[s.session_id] = s; S.sessions[String(s.session_id).slice(0, 8)] = s; }
  S.keys = keys.body?.keys || [];
  S.accounts = accounts.body?.accounts || [];
  S.registry = status.body?.registry || [];
  const lives = await Promise.all(S.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/turns/live`)));
  const turns = {};
  S.heads.forEach((h, i) => { for (const t of lives[i].body?.turns || []) if (t.session && !t.stopped) turns[t.session] = { head: h.key, ...t }; });
  S.groups = projectGroups(rowsOfSessions, turns, S.heads);
  ui.project = reconcilePick(ui.project, S.groups);
}

// ---------- the daemon's questions, one per command ----------
// the window's columns: twenty quarter hours ending at the next quarter, or the seven calendar days ending today
function slices() {
  if (ui.win === "5h") {
    const end = Math.ceil(+NOW / 900e3) * 900e3;
    return Array.from({ length: 20 }, (_, i) => { const to = end - (19 - i) * 900e3; return { from: to - 900e3, to, label: new Date(to - 900e3).getMinutes() === 0 ? hourWord(new Date(to - 900e3)) : "" }; });
  }
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() - (6 - i));
    const next = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1);
    return { from: +d, to: +next, day: d.toLocaleDateString("en-CA"), label: i === 6 ? "Today" : DAY[d.getDay()] };
  });
}
const since = () => slices()[0].from;
function filterParams(p, f, label) {
  if (label != null) p.set("account", label);
  if (f.model) p.set("model", f.model);
  if (f.agent) p.set("session", String(f.agent).slice(0, 8));
  if (ui.project && ui.project !== NO_PROJECT_KEY && !f.wide) p.set("project", ui.project);
  return p;
}
// each 15-minute column of the 5-hour window is one more question on the same read (`ask`), so the whole window is read once
function turnsUrl(head, held, f, label) {
  const p = filterParams(new URLSearchParams({ head: head.key, since: String(since()), time_zone: ZONE, n: String(held), local: "0" }), f, label);
  if (held > 1) p.set("usage", "0");
  else if (ui.win === "5h") for (const sl of slices()) p.append("ask", filterParams(new URLSearchParams({ n: "1", local: "0", from: String(sl.from + 1), until: String(sl.to) }), f, label).toString());
  return `/api/perf/turns?${p}`;
}
/** The labels one account filter reads: the daemon matches one label exactly, so an account with several is asked once per label and summed. */
const labelsOf = (f) => (f.account ? f.account.split("|").slice(1).join("|").split(SEP) : [null]);
const headsFor = (f) => S.heads.filter((h) => !f.account || f.account.split("|")[0] === h.key);

const ZERO = () => ({ requests: 0, in: 0, cached: 0, out: 0, cost: null, plan: 0, planValue: null, uncounted: 0, undeclared: 0, local: 0, unanswered: 0 });
function fold(into, g) {
  into.requests += g.requests ?? 0; into.in += g.input_tokens ?? 0; into.cached += g.cached_tokens ?? 0; into.out += g.output_tokens ?? 0;
  if (g.cost_usd != null) into.cost = (into.cost ?? 0) + g.cost_usd;
  into.plan += g.plan_requests ?? 0;
  if (g.plan_value_usd != null) into.planValue = (into.planValue ?? 0) + g.plan_value_usd;
  into.uncounted += g.unpriced_uncounted_requests ?? 0; into.undeclared += g.unpriced_undeclared_requests ?? 0;
  into.local += g.unpriced_local_requests ?? 0; into.unanswered += g.unanswered_requests ?? 0;
  return into;
}
/** The window's totals across every command that answered: per account, model, session and day, as the daemon folded them. */
function foldWindow(reads, heads) {
  const w = { total: ZERO(), accounts: new Map(), models: new Map(), sessions: new Map(), days: new Map(), slices: new Map(), byHead: new Map(), errors: [], pending: false };
  heads.forEach((head, i) => {
    const answer = (reads[i].body?.heads || []).find((x) => x.key === head.key);
    if (!reads[i].ok || !answer || answer.error) { w.errors.push(head.command); console.warn(`${head.command}: ${answer?.error ?? reads[i].status}`); return; }
    if (answer.read_pending) { w.pending = true; return; }
    const u = answer.usage;
    const seen = w.byHead.get(head.key);
    w.byHead.set(head.key, { count: (seen?.count ?? 0) + (answer.count ?? 0), returned: (seen?.returned ?? 0) + (answer.returned ?? 0), truncated: seen?.truncated === true || answer.truncated === true });
    if (!u) return;
    fold(w.total, u.totals);
    const cols = (answer.asks || []).map((a) => fold(ZERO(), a.usage?.totals ?? {})), had = w.slices.get(head.key);
    w.slices.set(head.key, had ? had.map((c, i) => fold2(c, cols[i] ?? ZERO())) : cols);
    const into = (map, key, g, extra) => { const e = map.get(key) ?? { ...ZERO(), ...extra, heads: new Set() }; fold(e, g); e.heads.add(head.key); map.set(key, e); };
    for (const g of u.accounts || []) if (g.key != null) into(w.accounts, `${head.key}|${g.key}`, g, { head: head.key, label: g.key, name: g.label ?? null });
    for (const g of u.models || []) if (g.key != null) into(w.models, g.key, g, { id: g.key });
    for (const g of u.sessions || []) if (g.key != null) into(w.sessions, g.key, g, { sid: g.key, lastModel: g.last_model });
    for (const g of u.days || []) {
      const byDay = w.days.get(head.key) ?? new Map();
      byDay.set(g.key, fold(byDay.get(g.key) ?? ZERO(), g)); w.days.set(head.key, byDay);
    }
  });
  return w;
}
async function readWindow() {
  const gen = ++D.gen;
  const wide = { wide: true }; // every project, for the tiles' figures
  const scopes = [...(hasFilter() ? [ui.f, {}] : [{}]), ...(ui.project ? [wide] : [])];
  const asks = scopes.flatMap((f) => headsFor(f).flatMap((h) => labelsOf(f).map((label) => ({ f, h, held: 1, label }))));
  const rowAsks = headsFor(ui.f).flatMap((h) => labelsOf(ui.f).map((label) => ({ f: ui.f, h, held: MAX_HELD, label })));
  const [totals, rows] = await Promise.all([
    Promise.all(asks.map((a) => API.getReady(turnsUrl(a.h, a.held, a.f, a.label)))), Promise.all(rowAsks.map((a) => API.getReady(turnsUrl(a.h, a.held, a.f, a.label))))]);
  if (gen !== D.gen) return;
  const split = (scope) => { const idx = asks.map((a, i) => (a.f === scope ? i : -1)).filter((i) => i >= 0); return foldWindow(idx.map((i) => totals[i]), idx.map((i) => asks[i].h)); };
  D.scoped = split(scopes[0]);
  D.all = hasFilter() ? split(scopes[1]) : D.scoped;
  D.every = ui.project ? split(wide) : D.all;
  D.rows = []; D.held = {}; D.errors = [...D.scoped.errors];
  let pending = D.scoped.pending || D.all.pending || D.every.pending;
  rowAsks.forEach((a, i) => {
    const answer = (rows[i].body?.heads || []).find((x) => x.key === a.h.key);
    if (!rows[i].ok || !answer || answer.error) { if (!D.errors.includes(a.h.command)) D.errors.push(a.h.command); return; }
    if (answer.read_pending) { pending = true; return; }
    const had = D.held[a.h.key];
    D.held[a.h.key] = { count: (had?.count ?? 0) + (answer.count ?? 0), returned: (had?.returned ?? 0) + (answer.returned ?? 0), truncated: had?.truncated === true || answer.truncated === true };
    for (const row of answer.rows || []) D.rows.push(mapRow(a.h, row));
  });
  if (rowAsks.length > headsFor(ui.f).length) { // several labels: keep the newest MAX_HELD of what they returned together, so the trail's axis is one reading
    D.rows.sort((x, y) => y.ts - x.ts);
    for (const [head, h] of Object.entries(D.held)) {
      const mine = D.rows.filter((r) => r.head === head);
      if (mine.length > MAX_HELD) { const drop = new Set(mine.slice(MAX_HELD)); D.rows = D.rows.filter((r) => !drop.has(r)); h.returned = MAX_HELD; h.truncated = true; }
    }
  }
  D.pending = pending; D.loading = false;
  render();
  if (pending) setTimeout(() => { if (gen === D.gen) readWindow(); }, 2000);
}
function mapRow(head, r) {
  const sid = r.session_id || r.session || null;
  if (sid && r.title) S.sessions[String(sid).slice(0, 8)] ??= { title: r.title };
  return {
    id: `${head.key}:${r.turn || r.turn_id || r.ts}`, head: head.key, cmd: head.command, ts: r.ts, total: r.total ?? 0, ttfb: r.first_byte ?? null,
    in: r.in_tokens ?? null, cached: r.cached_tokens ?? 0, out: r.out_tokens ?? null, cost: r.cost_usd ?? null, planValue: r.plan_value_usd ?? null, reason: r.cost_reason ?? null,
    outcome: r.outcome, cause: r.cause ?? null, sid, model: r.model ?? null, account: r.account ?? null,
    reset: r.earliest_reset_epoch_seconds ? r.earliest_reset_epoch_seconds * 1000 : null, project: r.project ?? null,
  };
}

// ---------- how a figure reads ----------
const tokWord = (n) => (n >= 999.5e6 ? `${+(n / 1e9).toFixed(n >= 1e10 ? 0 : 1)} bn` : kTok(n)); // a week of tokens is billions; kTok stops at M
const tokensOf = (s) => s.in + s.out;
/** What a group cost, in fin's words: a key's requests are money; a plan's read "On your plan" with their API-price value
 *  as what they would cost at API prices; a request nobody could price says why. Plan and key never share one figure. */
function costCell(s) {
  const out = [];
  if (s.cost != null) out.push(`<span class="money">${dollars(s.cost)}</span>`);
  if (s.plan) out.push(`<span class="gap">On your plan</span>${s.planValue != null ? `<span class="val">${dollars(s.planValue)} at API prices</span>` : ""}`);
  if (!out.length) out.push(`<span class="gap">${s.uncounted ? "Uncounted" : s.undeclared ? "No price" : s.local ? "This computer" : ""}</span>`);
  return out.join("");
}
const chip = (head) => `<span class="chip" style="--c:${colorOf(providerOfHead(head))}">${esc(cmdOf(head))}</span>`;
const acctKeyOf = (head, label) => `${head}|${label}`;
/** The key of an account that may carry several labels: the one with the most requests first. */
const acctKeyOfAll = (entries) => { const e = entries.slice().sort((a, b) => b.requests - a.requests); return acctKeyOf(e[0].head, e.map((x) => x.label).join(SEP)); };
const keyNameOf = (label) => { const s = String(label ?? ""), at = s.lastIndexOf(":"); return at < 0 ? s : s.slice(0, at); };
const keyRecord = (label) => {
  const name = keyNameOf(label), print = String(label).slice(name.length + 1);
  for (const k of S.keys) {
    if (k.name !== name) continue;
    if (k.fingerprint === print) return { name, seen: k.first_seen_epoch_seconds, replaced: false };
    const old = (k.replaced || []).find((r) => r.fingerprint === print);
    if (old) return { name, seen: old.first_seen_epoch_seconds, replaced: true };
  }
  return null;
};
/** The plan account a usage entry belongs to. A login the daemon names carries a `ref` and a `label`: it matches by the ref
 *  and reads by the label alone. A row without them (a daemon before the label contract) matches by every name it has and reads
 *  as it always did, so the page changes by itself the moment the daemon serves the ref. */
const planRowOf = (head, label) => S.accounts.find((a) => (a.heads || []).includes(head) && a.kind !== "api-key"
  && (a.ref ? a.ref === label : [a.account?.email, a.account_labels?.[head], a.selector_key, a.label, a.display_name].some((x) => x && String(x).toLowerCase() === String(label).toLowerCase())));
const planName = (row, fallback) => (row.ref ? row.label : row.account?.email || row.display_name || fallback || "");
/** An account as every page names it: a plan by its name, a key by its variable and the day splice first saw it. */
function acctLabel(head, label, name) {
  const plan = planRowOf(head, label);
  if (plan) return esc(planName(plan, label));
  const key = keyRecord(label);
  if (key) return `<span class="var">${esc(key.name)}</span>${key.seen ? ` · ${dayWord(new Date(key.seen * 1000))}` : ""}`;
  return esc(name ?? label);
}

// ---------- plans: what each one used, as its provider reads it (Accounts' numbers) ----------
function windowOf(pct, resetSec, lenSec, slot, current) {
  if (pct == null) return null;
  const month = slot === "long" && lenSec >= 28 * 86400;
  const label = slot === "short" ? "5 hours" : month ? "Month" : "Week";
  const left = resetSec ? Math.round((resetSec * 1000 - NOW.getTime()) / 60000) : null;
  const due = left != null && left >= 0;
  return { label, used: Math.round(pct), len: LEN[label], left: due ? left : null, resetMs: due ? resetSec * 1000 : null, stale: current === false };
}
function ring(len, left) {
  const C = 2 * Math.PI * 15, f = Math.max(0, Math.min(1, left / len));
  return `<svg class="ring" viewBox="0 0 40 40" aria-hidden="true"><circle class="trk" cx="20" cy="20" r="15"/><circle class="arc" cx="20" cy="20" r="15" stroke-dasharray="${(C * f).toFixed(1)} ${C.toFixed(1)}"/></svg>`;
}
const isPlanHead = (key) => {
  const h = S.registry.find((x) => x.key === key);
  return h && h.authKind !== "api-key" && h.family !== "local" && h.family !== "vast";
};
const isKeyHead = (key) => {
  const h = S.registry.find((x) => x.key === key);
  return h && h.authKind === "api-key" && h.family !== "local" && h.family !== "vast";
};
/** The window's usage entries that belong to one plan account. */
const reading = () => D.loading || !D.scoped;
const usageOfPlan = (row) => !D.all ? [] : [...D.all.accounts.values()].filter((e) => (row.heads || []).includes(e.head) && planRowOf(e.head, e.label) === row);
const sumOf = (entries) => entries.reduce((t, e) => fold2(t, e), ZERO());
function fold2(t, e) {
  t.requests += e.requests; t.in += e.in; t.cached += e.cached; t.out += e.out; t.plan += e.plan; t.uncounted += e.uncounted; t.undeclared += e.undeclared; t.local += e.local;
  if (e.cost != null) t.cost = (t.cost ?? 0) + e.cost;
  if (e.planValue != null) t.planValue = (t.planValue ?? 0) + e.planValue;
  return t;
}
const uncut = (heads) => heads.every((h) => D.held[h] && !D.held[h].truncated);
const DRILLS = () => `<span class="drill">${DIMS[nextBy("account")].word}${DOWN}</span>`;
function plansHtml() {
  const cards = S.accounts.filter((row) => (row.heads || []).some(isPlanHead) && !(row.login_place && !row.credential_present && !row.account && !row.observed_at_epoch_seconds)).map((row) => {
    const windows = [windowOf(row.five_hour_used_percent, row.five_hour_reset_epoch_seconds, row.five_hour_window_seconds, "short", row.five_hour_current),
      windowOf(row.seven_day_used_percent, row.seven_day_reset_epoch_seconds, row.seven_day_window_seconds, "long", row.seven_day_current)].filter(Boolean);
    const out = !row.credential_present || row.auth_exclusion_reason === "credential_missing" || !!row.refusal;
    const mine = usageOfPlan(row), s = sumOf(mine), held = windows.some((w) => w.used >= 100);
    const stale = windows.some((w) => w.stale) && row.observed_at_epoch_seconds ? row.observed_at_epoch_seconds * 1000 : null;
    const wins = out ? `<p class="state">Signed out</p>` : windows.length ? `<div class="windows">${windows.map((w) => `<span class="label">${w.label}</span><div class="bar${w.used >= 100 ? " full" : ""}${w.stale ? " stale" : ""}"><b style="width:${Math.min(w.used, 100)}%"></b></div>`
      + `<span class="pct${w.used >= 100 ? " limit" : ""}">${w.used}%<span class="used">used</span></span><span class="when">${w.left != null ? `${ring(w.len, w.left)}Resets ${backWord(new Date(w.resetMs))}` : ""}</span>`).join("")}`
      + (stale ? `<span class="stale-at">${EYE}Read ${backWord(new Date(stale))}</span>` : "") + `</div>` : `<p class="state">No limits reported</p>`;
    const heads = row.heads || [];
    const lead = mine.length ? mine[0] : null;
    const ranOut = reading() ? null : D.rows.filter((r) => r.outcome === "error:plan-limit" && r.reset && r.reset - r.ts > 5 * HOUR && mine.some((e) => e.head === r.head && e.label === r.account)).sort((a, b) => a.ts - b.ts)[0];
    const door = lead ? ` data-act="pick" data-d="account" data-v="${esc(acctKeyOfAll(mine))}" tabindex="0"` : "";
    return `<article class="card pcard${held ? " held" : ""}" style="--c:${colorOf(providerOfHead(heads[0]))}"${door}>`
      + `<header><span class="nm">${esc(planName(row))}</span><span class="pv">${esc(PROVIDER_NAME[providerOfHead(heads[0])] ?? "")}${row.plan ? ` · ${esc(row.plan.charAt(0).toUpperCase() + row.plan.slice(1))}` : ""}</span><span class="cmds">${heads.map(chip).join("")}</span></header>`
      + wins + (ranOut ? `<p class="since"><a href="requests.html?win=7d#${encodeURIComponent(ranOut.id)}">Ran out ${dayClock(new Date(ranOut.ts))}</a></p>` : "")
      + `<footer><span class="thru">${reading() ? `<span class="ctd">Counted by splice</span>Reading` : s.requests ? `<span class="ctd">Counted by splice</span><b>${tokWord(tokensOf(s))}</b> tokens<span class="dot"></span>${Math.round(100 * s.cached / Math.max(1, s.in))}% cached<span class="dot"></span>${n0(s.requests)} requests` : "No requests"}</span>${lead ? DRILLS() : ""}</footer></article>`;
  });
  return cards.length ? `<section class="plans"><header><h2>Plans</h2></header><div class="grid">${cards.join("")}</div></section>` : "";
}

// ---------- keys: what each one spent ----------
function keysHtml() {
  const cards = [];
  for (const k of S.keys) {
    const heads = (k.heads || []).map((x) => x.head).filter(isKeyHead);
    if (!heads.length) continue;
    for (const [print, seen, replaced] of [[k.fingerprint, k.first_seen_epoch_seconds, false], ...(k.replaced || []).map((r) => [r.fingerprint, r.first_seen_epoch_seconds, true])]) {
      const label = `${k.name}:${print}`;
      const mine = !D.all ? [] : [...D.all.accounts.values()].filter((e) => heads.includes(e.head) && e.label === label), s = sumOf(mine), lead = mine[0];
      const door = s.requests && !reading() ? ` data-act="pick" data-d="account" data-v="${esc(acctKeyOf(lead.head, lead.label))}" tabindex="0"` : "";
      cards.push({ s, html: `<article class="card kcard" style="--c:${colorOf(providerOfHead(heads[0]))}"${door}>`
        + `<header><span class="nm"><span class="var">${esc(k.name)}</span>${seen ? ` · ${dayWord(new Date(seen * 1000))}` : ""}${replaced ? `<span class="detail">Replaced</span>` : ""}</span><span class="pv">${esc(PROVIDER_NAME[providerOfHead(heads[0])] ?? "")}</span><span class="cmds">${[...new Set(heads)].map(chip).join("")}</span></header>`
        + (reading() ? `<div class="spent"><span class="none">Reading</span></div>` : s.requests ? `<div class="spent"><span class="label">Cost</span>${s.cost != null ? `<span class="money">${dollars(s.cost)}</span>` : `<span class="none">${s.uncounted ? "Uncounted" : "No price"}</span>`}</div><footer><span class="thru"><span class="ctd">Counted by splice</span>${n0(s.requests)} requests</span>${DRILLS()}</footer>`
          : `<div class="spent"><span class="none">No requests</span></div>`) + `</article>` });
    }
  }
  cards.sort((a, b) => (b.s.cost ?? -1) - (a.s.cost ?? -1) || b.s.requests - a.s.requests);
  return cards.length ? `<section class="keys"><header><h2>Keys</h2></header><div class="grid">${cards.map((x) => x.html).join("")}</div></section>` : "";
}

// ---------- what a value can be grouped by; opening one goes a step down with its filter added ----------
const DIMS = {
  account: { word: "Accounts", one: "Account" },
  project: { word: "Projects", one: "Project" },
  agent: { word: "Agents", one: "Agent" },
  model: { word: "Models", one: "Model" },
};
const DRILL = ["account", "project", "agent", "model"];
const isSet = (d) => (d === "project" ? ui.project != null : d in ui.f);
const nextBy = (d) => DRILL.find((x) => x !== d && !isSet(x)) ?? "model";
function reqUrl(extra = {}, f = ui.f) {
  const p = new URLSearchParams({ win: ui.win });
  if (ui.project) p.set("project", ui.project);
  if (f.account) p.set("account", f.account.split(SEP)[0]);
  if (f.model) p.set("model", f.model);
  if (f.agent) p.set("session", String(f.agent).slice(0, 8));
  for (const [k, v] of Object.entries(extra)) p.set(k, v);
  return `requests.html?${p}`;
}
const projectOfSid = (sid, byRow) => {
  const tag = String(sid).slice(0, 8), p = S.sessions[sid]?.project ?? S.sessions[tag]?.project ?? byRow.get(tag) ?? null;
  return { key: rowProjKey({ project: p }), label: rowProjName({ project: p }) };
};
const rowProjects = () => new Map(D.rows.filter((r) => r.sid && r.project).map((r) => [String(r.sid).slice(0, 8), r.project]));
const unionHeads = (a, b) => new Set([...a, ...b]);
/** The breakdown's rows: the daemon's totals for the window under the filters, folded by what they are grouped by. */
function groupsOf(by) {
  const w = D.scoped;
  if (by === "account") { // one plan account can reach splice under more than one label (its email, its selector name): one row
    const m = new Map();
    for (const e of w.accounts.values()) { const id = planRowOf(e.head, e.label) ? `${e.head}|${S.accounts.indexOf(planRowOf(e.head, e.label))}` : acctKeyOf(e.head, e.label); (m.get(id) ?? m.set(id, []).get(id)).push(e); }
    return [...m.values()].map((es) => { const lead = es.slice().sort((a, b) => b.requests - a.requests)[0], s = es.reduce((t, e) => fold2(t, e), ZERO());
      return { k: acctKeyOfAll(es), many: es.length > 1, label: acctLabel(lead.head, lead.label, lead.name), sub: PROVIDER_NAME[providerOfHead(lead.head)] ?? "", heads: lead.heads, s }; });
  }
  if (by === "model") return [...w.models.values()].map((e) => ({ k: e.id, label: esc(modelName(S.labels, e.id)), sub: "", heads: e.heads, s: e }));
  const byRow = rowProjects();
  if (by === "agent") return [...w.sessions.values()].map((e) => ({ k: e.sid, label: esc(sessTitle(String(e.sid).slice(0, 8))), sub: esc(projectOfSid(e.sid, byRow).label), heads: e.heads, s: e }));
  const m = new Map();
  for (const e of w.sessions.values()) {
    const p = projectOfSid(e.sid, byRow), g = m.get(p.key) ?? { k: p.key, label: esc(p.label), sub: "", heads: new Set(), s: ZERO() };
    fold2(g.s, e); g.heads = unionHeads(g.heads, e.heads); m.set(p.key, g);
  }
  return [...m.values()];
}
const measureOf = (s) => (ui.measure === "cost" ? s.cost ?? 0 : tokensOf(s));
const measureText = (v) => (ui.measure === "cost" ? dollars(v) : tokWord(Math.round(v)));
const niceTop = (v) => { const p = 10 ** Math.floor(Math.log10(v)), m = v / p; return ([1, 1.5, 2, 3, 4, 5, 6, 8, 10].find((x) => m <= x)) * p; };
const headsOrder = () => S.heads.map((h) => h.key);

// ---------- over time: one bar per slice, stacked by command; a part opens its requests ----------
let alt = new Set();
const swatch = (head) => `<i class="${alt.has(head) ? "alt" : ""}" style="--c:${colorOf(providerOfHead(head))}"></i>`;
function chartHtml() {
  const w = D.scoped, sl = slices().map((x) => ({ ...x, vals: [] }));
  sl.forEach((x, i) => {
    for (const head of headsOrder()) {
      const s = ui.win === "5h" ? w.slices.get(head)?.[i] : w.days.get(head)?.get(x.day);
      const v = s ? measureOf(s) : 0;
      if (v > 0) x.vals.push([head, v, s.requests]);
    }
    x.total = x.vals.reduce((a, [, v]) => a + v, 0); x.n = x.vals.reduce((a, [, , n]) => a + n, 0);
  });
  const shown = headsOrder().filter((h) => sl.some((x) => x.vals.some(([d]) => d === h)));
  alt = new Set(shown.filter((h, i) => shown.slice(0, i).some((d) => providerOfHead(d) === providerOfHead(h))));
  const max = Math.max(...sl.map((x) => x.total), 1e-9), top = niceTop(max);
  const bars = sl.map((x, i) => `<div class="col${ui.hover === i ? " on" : ""}" data-i="${i}"><div class="stack" style="height:${((x.total / top) * 100).toFixed(2)}%">`
    + x.vals.map(([head, v]) => `<a class="${alt.has(head) ? "alt" : ""}" href="${reqUrl({ cmd: cmdOf(head), from: x.from, to: x.to })}" style="--c:${colorOf(providerOfHead(head))};flex:${((v / x.total) * 1000).toFixed(2)} 1 0" aria-label="${esc(cmdOf(head))}"></a>`).join("")
    + `</div><span class="x">${x.label}</span></div>`).join("");
  const grid = [1, .5].map((f) => `<div class="gl" style="bottom:${f * 100}%"><span>${measureText(top * f)}</span></div>`).join("");
  const legend = `<div class="legend">${shown.map((h) => `<span class="key">${swatch(h)}${esc(cmdOf(h))}</span>`).join("")}</div>`;
  const sw = `<div class="switch" role="group" aria-label="Measure">${[["tokens", "Tokens"], ["cost", "Cost"]].map(([v, t]) => `<button data-act="measure" data-v="${v}" aria-pressed="${ui.measure === v}">${t}</button>`).join("")}</div>`;
  const tip = ui.hover != null ? tipHtml(sl[ui.hover], sl.length) : "";
  return `<section class="chart card"><header>${sw}</header><div class="plot">${grid}<div class="cols" style="--n:${sl.length}">${bars}</div>${tip}</div>${legend}</section>`;
}
function tipHtml(x, n) {
  const span = ui.win === "5h" ? `${clock(new Date(x.from))} to ${clock(new Date(x.to))}` : x.label === "Today" ? "Today" : `${x.label} ${new Date(x.from).getDate()}`;
  const left = ((ui.hover + .5) / n) * 100;
  return `<div class="tip${left > 60 ? " lefty" : ""}" style="left:${left.toFixed(2)}%"><p class="tw">${span}</p>${x.vals.slice().reverse().map(([head, v]) => `<p>${swatch(head)}<span>${esc(cmdOf(head))}</span><b>${measureText(v)}</b></p>`).join("") || `<p class="none">No requests</p>`}`
    + `${x.vals.length > 1 ? `<p class="sum"><span>All</span><b>${measureText(x.total)}</b></p>` : ""}</div>`;
}

// ---------- by any one thing: each value's tokens, requests and cost ----------
function filterChips() {
  const chip1 = (d, text) => `<span class="fwrap"><span class="fbtn on"><em>${DIMS[d].one}</em>${text}</span><button class="fclear" data-act="unpick" data-d="${d}" aria-label="Clear">${ICON.close}</button></span>`;
  return Object.entries(ui.f).map(([d, v]) => {
    const text = d === "account" ? acctLabel(v.split("|")[0], v.split("|").slice(1).join("|").split(SEP)[0], D.all?.accounts.get(v.split(SEP)[0])?.name) : d === "model" ? esc(modelName(S.labels, v)) : esc(sessTitle(String(v).slice(0, 8)));
    return chip1(d, text);
  }).join("");
}
function byHtml() {
  const list = groupsOf(ui.by).sort((a, b) => measureOf(b.s) - measureOf(a.s) || b.s.requests - a.s.requests);
  const max = Math.max(...list.map((e) => tokensOf(e.s)), 1), shown = ui.more ? list : list.slice(0, 12);
  const row = (e) => {
    const f = ui.by === "project" ? ui.f : { ...ui.f, [ui.by]: e.k }, extra = ui.by === "project" ? { project: e.k } : {};
    return `<div class="brow" data-act="pick" data-d="${ui.by}" data-v="${esc(e.k)}" style="--c:${colorOf(providerOfHead([...e.heads][0]))}" tabindex="0">`
      + `<div class="who"><span class="nm">${e.label}</span><span class="sb">${e.sub ? `<span class="st">${e.sub}</span>` : ""}${[...e.heads].map(chip).join("")}</span></div>`
      + `<div class="share"><div class="sbar" style="width:${((tokensOf(e.s) / max) * 100).toFixed(2)}%"></div><span class="num">${tokWord(tokensOf(e.s))}</span></div>`
      + `${e.many ? `<span class="num">${n0(e.s.requests)}</span>` : `<a class="num open" href="${reqUrl({ ...extra, order: "price" }, f)}">${n0(e.s.requests)}${OPEN}</a>`}<span class="cost">${costCell(e.s)}</span></div>`;
  };
  const sw = `<div class="switch" role="group" aria-label="Group by">${Object.entries(DIMS).map(([k, v]) => `<button data-act="by" data-v="${k}" aria-pressed="${ui.by === k}">${v.word}</button>`).join("")}</div>`;
  const open = hasFilter() ? `<a class="act" href="${reqUrl()}">${OPEN}Open on Requests</a>` : "";
  const back = ui.from ? `<button class="act quiet small" data-act="back">${UP}Back to ${ui.from.where}</button>` : "";
  const more = list.length > 12 ? `<button class="act quiet" data-act="more">${ui.more ? "Fewer" : `${list.length - 12} more`}</button>` : "";
  const head = `<div class="brow bhead"><span>${DIMS[ui.by].one}</span><span>Tokens</span><span class="num">Requests</span><span class="cost">Cost</span></div>`;
  return `<section class="by card"><header>${sw}<div class="fchips">${filterChips()}</div>${back}${open}</header>${list.length ? head + shown.map(row).join("") + more : `<p class="empty">No requests</p>`}</section>`;
}

// ---------- each command: how fast it answers, what failed, and when ----------
// Typical is the median, Slowest the longest; the band between runs to the 95th. Under them, every request a dot at its
// time, as high as it took, and every failure a tick that opens it, so where the dots rise or the ticks begin is since when.
// From the newest requests the command returned: a command that holds more says so on its card.
const LOG = (ms) => Math.min(1, Math.max(0, (Math.log10(Math.max(ms, 100)) - 2) / (Math.log10(180000) - 2)));
const LAT_HEAD = `<div class="lat-head"><span>Typical</span><span>95th</span><span>Slowest</span></div>`; // 95th: 19 in 20 requests were faster
function strip(label, xs, scaleWords = false) {
  if (!xs.length) return "";
  const p50 = q(xs, .5), p95 = q(xs, .95), max = Math.max(...xs), at = (ms) => `${(LOG(ms) * 100).toFixed(2)}%`;
  const ticks = [1000, 10000, 60000].map((ms) => `<span class="tk" style="left:${at(ms)}">${scaleWords ? `<span>${secs(ms).replace(" min 0 s", " min")}</span>` : ""}</span>`).join("");
  return `<div class="lat"><span class="label">${label}</span><span class="vals"><span>${secs(p50)}</span><span>${secs(p95)}</span><span>${secs(max)}</span></span>`
    + `<div class="scale">${ticks}<span class="span" style="left:${at(p50)};width:calc(${at(p95)} - ${at(p50)})"></span><span class="mk max" style="left:${at(max)}"></span></div></div>`;
}
// the stretch the rows in hand cover: the whole window, or, for a command that holds more than it returned, from its oldest
// returned request, with its own hour or day words
function axis(rs, cut) {
  const sl = slices(), t1 = cut ? +NOW : sl.at(-1).to, t0 = cut && rs.length ? Math.min(...rs.map((r) => r.ts)) - 60e3 : sl[0].from;
  if (!cut) return { t0, t1, words: sl.filter((b) => b.label).map((b) => [b.from, b.label]) };
  const step = [HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY_MS].find((st) => (t1 - t0) / st <= 8) ?? DAY_MS, words = [];
  for (let t = Math.ceil(t0 / step) * step; t < t1; t += step) {
    const d = new Date(t);
    words.push([t, step >= DAY_MS ? DAY[d.getDay()] : d.getHours() === 0 ? DAY[d.getDay()] : hourWord(d)]);
  }
  return { t0, t1, words };
}
function trail(rs, f = null, cut = false) {
  const { t0, t1, words: marks } = axis(rs, cut), span = t1 - t0, x = (r) => `${(((+r.ts - t0) / span) * 100).toFixed(2)}%`;
  const dots = rs.filter((r) => !failed(r)).map((r) => `<i style="left:${x(r)};bottom:${(LOG(r.total) * 100).toFixed(1)}%"></i>`).join("");
  const ticks = rs.filter(failed).map((r) => `<a class="ft" href="requests.html?win=${ui.win}#${encodeURIComponent(r.id)}" style="left:${x(r)}" aria-label="${esc(OUTCOME_WORD[r.outcome] || "Failed")} at ${dayClock(new Date(r.ts))}"></a>`).join("");
  const words = marks.map(([t, label]) => `<span style="left:${(((t - t0) / span) * 100).toFixed(2)}%">${label}</span>`).join("");
  const base = q(rs.filter((r) => !failed(r)).map((r) => r.total), .5);
  const since = f ? `<span class="since" style="left:${x({ ts: f.from })};${f.to ? `width:calc(${x({ ts: f.to })} - ${x({ ts: f.from })})` : "right:0"}"></span>` : "";
  return `<div class="trail"><div class="field">${since}${base ? `<span class="base" style="bottom:${(LOG(base) * 100).toFixed(1)}%"></span>` : ""}${dots}${ticks}</div><div class="xw">${words}</div></div>`;
}
// Slow or failing today, and since when, against the command's own week: slow is a trailing run of today's requests, most
// over 1.6 times the typical whole request before today; failing is a burst of today's failures within 5 minutes of each
// other, at least three, a spent plan window aside (Plans shows that). Since is the run's first request.
const day0 = () => { const d = new Date(NOW); d.setHours(0, 0, 0, 0); return +d; };
function flagOf(head) {
  const wk = D.rows.filter((r) => r.head === head), D0 = day0();
  const today = wk.filter((r) => r.ts >= D0).sort((a, b) => a.ts - b.ts), before = wk.filter((r) => r.ts < D0);
  const typ = q(before.filter((r) => !failed(r)).map((r) => r.total), .5);
  const ok = today.filter((r) => !failed(r)), slow = (r) => typ && r.total > 1.6 * typ;
  let at = null;
  for (let i = ok.length - 1; i >= 0; i--) {
    const rest = ok.slice(i);
    if (slow(ok[i]) && rest.filter(slow).length >= .8 * rest.length) at = i;
  }
  if (at != null && ok.length - at >= 5) {
    const run = ok.slice(at);
    return { kind: "slow", from: run[0].ts - run[0].total, to: null, now: q(run.map((r) => r.total), .5), typ };
  }
  const GAP = 5 * 60e3, bad = (r) => failed(r) && r.outcome !== "error:plan-limit";
  let best = null, cur = null;
  for (const r of today.filter(bad)) {
    if (cur && r.ts - r.total - cur.at(-1).ts <= GAP) cur.push(r); else cur = [r];
    const len = cur.at(-1).ts - (cur[0].ts - cur[0].total);
    if (cur.length >= 3 && (!best || cur.length > best.n || (cur.length === best.n && len < best.len))) best = { n: cur.length, len, run: [...cur] };
  }
  return best ? { kind: "failing", from: Math.min(...best.run.map((r) => r.ts - r.total)), to: Math.max(...best.run.map((r) => r.ts)), n: best.n } : null;
}
function flagHtml(head, f) {
  const href = reqUrl({ win: "24h", cmd: cmdOf(head), from: f.from, ...(f.to ? { to: f.to } : {}), outcome: f.kind === "failing" ? "fail" : "all" });
  const word = f.kind === "slow" ? `Slow since ${clock(new Date(f.from))}` : `Today ${clock(new Date(f.from))} to ${clock(new Date(f.to))}`;
  const vs = f.kind === "slow" ? `<span class="vs"><b>${secs(f.now)}</b> since then<span class="dot"></span><b>${secs(f.typ)}</b> before today</span>` : `<span class="vs"><b>${f.n}</b> failed</span>`;
  return `<p class="flag"><a href="${href}">${word}${OPEN}</a>${vs}</p>`;
}
/** What share of the requests in hand failed, in words a reader can place: under one in a hundred says so. */
const failShare = (n, of) => (!of ? "" : n / of < 0.005 ? "under 1%" : `${Math.round((100 * n) / of)}%`);
function headCard(head, f = null, foldable = !f) {
  const rs = D.rows.filter((r) => r.head === head), fails = rs.filter(failed), held = D.held[head];
  const counts = new Map(); for (const r of fails) counts.set(r.outcome, (counts.get(r.outcome) || 0) + 1);
  const lastReset = (o) => fails.filter((r) => r.outcome === o && r.reset).reduce((a, r) => (!a || r.reset > a ? r.reset : a), null);
  const word = (o) => (o === "error:plan-limit" ? `<span class="lk">${ICON.lock}${lastReset(o) ? `Resets ${dayClock(new Date(lastReset(o)))}` : ""}</span>` : esc(OUTCOME_WORD[o] ?? tagWord(o) ?? "Failed"));
  const kinds = [...counts].sort((a, b) => b[1] - a[1]).map(([o, n]) => `<span class="f"><b>${n}</b> ${COUNT_WORD[o] ? esc(countWord(o)) : word(o)}</span>`).join("");
  const models = [...new Set(rs.map((r) => r.model).filter(Boolean))].map((m) => modelName(S.labels, m));
  const fold = !foldable ? "" : `<button class="icon fold" data-act="head" data-v="${esc(head)}" aria-label="Fold">${ICON.close}</button>`;
  const total = held?.count ?? rs.length, cut = held?.truncated ? `<p class="models cut">Newest ${n0(held.returned)} of ${n0(held.count)}</p>` : "";
  return `<article class="card head-card${f ? " lead" : ""}" style="--c:${colorOf(providerOfHead(head))}"><header>${chip(head)}<a class="count" href="${reqUrl({ cmd: cmdOf(head) })}">${n0(total)} requests${OPEN}</a>${fold}</header>`
    + (f ? flagHtml(head, f) : "") + `<p class="models">${models.map(esc).join('<span class="dot"></span>')}</p>${cut}`
    + LAT_HEAD + strip("First byte", rs.filter((r) => r.ttfb != null).map((r) => r.ttfb)) + strip("Whole request", rs.map((r) => r.total), true) + trail(rs, f, held?.truncated === true)
    + `<p class="fails${fails.length ? " bad" : ""}">${fails.length ? `<a class="all" href="${reqUrl({ cmd: cmdOf(head), outcome: "fail" })}"><b>${n0(fails.length)}</b> failed${OPEN}</a><span class="share">${failShare(fails.length, rs.length)}</span>` : `<span><b>0</b> failed</span>`}${kinds}</p></article>`;
}
function headRow(head) {
  const rs = D.rows.filter((r) => r.head === head), fails = rs.filter(failed).length;
  return `<button class="hrow" data-act="head" data-v="${esc(head)}" style="--c:${colorOf(providerOfHead(head))}" aria-expanded="false">${chip(head)}<span class="n">${n0(D.held[head]?.count ?? rs.length)} requests</span>`
    + `<span class="t"><em>Typical</em>${secs(q(rs.map((r) => r.total), .5) ?? 0)}</span><span class="fl${fails ? " bad" : ""}"><b>${n0(fails)}</b> failed</span>${ICON.caret}</button>`;
}
function headsHtml() {
  const order = headsOrder(), used = order.filter((h) => (D.held[h]?.count ?? 0) > 0), idle = order.filter((h) => !used.includes(h) && !D.errors.includes(cmdOf(h)));
  const flags = new Map(used.map((h) => [h, flagOf(h)]).filter(([, f]) => f));
  const lead = [...flags.keys()], rest = used.filter((h) => !flags.has(h));
  const open = (h) => ui.open.has(h) || used.length === 1;
  const quiet = idle.length && !hasFilter() && ui.project == null ? `<p class="quiet-heads"><span>No requests</span>${idle.map(chip).join("")}</p>` : "";
  const list = rest.length ? `<div class="hlist card">${rest.map((h) => (open(h) ? headCard(h, null, used.length > 1) : headRow(h))).join("")}</div>` : "";
  return `<section class="heads"><header><h2>Commands</h2></header>${lead.map((h) => headCard(h, flags.get(h))).join("")}${list}${quiet}</section>`;
}

// ---------- the page ----------
/** What each project tile and the picker carry on this page: the window's tokens, written as Breakdown writes them. */
function projectFigures() {
  const w = D.every;
  if (!w || reading()) return { of: () => null, all: null, unit: "token", show: tokWord };
  const byRow = rowProjects(), m = new Map();
  for (const e of w.sessions.values()) { const k = projectOfSid(e.sid, byRow).key; m.set(k, (m.get(k) ?? 0) + tokensOf(e)); }
  return { of: (k) => m.get(k) ?? 0, all: tokensOf(w.total), unit: "token", show: tokWord };
}
function render() {
  tick();
  document.getElementById("pickline").innerHTML = pickLineHtml(S.groups, ui.project, ui.pmenu, projectFigures());
  const n = Object.keys(ui.f).length;
  document.getElementById("hf").innerHTML = n ? filterChips() + (n > 1 ? `<button class="act quiet small" data-act="clear">Clear all</button>` : "") : "";
  document.getElementById("win").innerHTML = Object.entries(WINDOWS).map(([k, v]) => `<button data-act="win" data-v="${k}" aria-pressed="${ui.win === k}">${v.label}</button>`).join("");
  if (S.error) { root.innerHTML = `<p class="empty">${S.error}</p>`; return; }
  const waiting = reading(), hold = (title) => `<section><header><h2>${title}</h2></header><p class="empty">Reading</p></section>`;
  const note = waiting ? "" : D.errors.length ? `<p class="empty">${NOT_READ}: ${D.errors.map(esc).join(", ")}</p>` : D.pending ? `<p class="empty">Reading</p>` : "";
  root.innerHTML = `${note}<div class="money-band">${plansHtml()}${keysHtml()}</div>${waiting ? hold("Over time") : chartHtml()}<div class="lower"><section class="breakdown"><header><h2>Breakdown</h2></header>${waiting ? `<p class="empty">Reading</p>` : byHtml()}</section>${waiting ? hold("Commands") : headsHtml()}</div>`;
}
// the figures on screen belong to the window and filters they were read under: a new one reads again and says so, never
// drawing the old figures under the new heading
const again = () => { D.loading = true; D.scoped = null; D.all = null; D.every = null; D.gen++; render(); readWindow(); };
document.addEventListener("click", (e) => {
  if (ui.pmenu && !e.target.closest(".pmenu, [data-act=projmenu]")) { ui.pmenu = false; render(); }
  if (e.target.closest("a")) return; // a link opens its requests
  const el = e.target.closest("[data-act]");
  if (!el) return;
  const v = el.dataset.v;
  switch (el.dataset.act) {
    case "theme": toggleTheme(); break;
    case "projmenu": ui.pmenu = !ui.pmenu; render(); break;
    case "pickproj": ui.project = el.dataset.p || null; ui.pmenu = false; PICK.set(ui.project); ui.by = nextBy("project") === "project" ? "account" : ui.by; again(); break;
    case "win": ui.win = v; ui.hover = null; ui.more = false; again(); break;
    case "measure": ui.measure = v; render(); break;
    case "by": ui.by = v; ui.more = false; render(); break;
    case "more": ui.more = !ui.more; render(); break;
    case "head": ui.open.has(v) ? ui.open.delete(v) : ui.open.add(v); render(); break;
    case "pick": {
      const card = el.closest(".pcard, .kcard");
      if (card) ui.from = { where: card.matches(".pcard") ? "Plans" : "Keys", cls: card.matches(".pcard") ? "pcard" : "kcard", v, y: document.scrollingElement.scrollTop };
      const d = el.dataset.d;
      if (d === "project") { ui.project = v; PICK.set(v); } else ui.f = { ...ui.f, [d]: v };
      ui.by = nextBy(d); ui.more = false; again();
      root.querySelector(".by")?.scrollIntoView({ block: "start", behavior: "instant" }); break;
    }
    case "back": {
      const { y, cls, v: was } = ui.from; ui.from = null; render();
      document.scrollingElement.scrollTo({ top: y, behavior: "instant" });
      [...root.querySelectorAll(`.${cls}`)].find((c) => c.dataset.v === was)?.focus({ preventScroll: true }); break;
    }
    case "clear": ui.f = {}; ui.by = "account"; ui.more = false; ui.from = null; again(); break;
    case "unpick": { const f = { ...ui.f }; delete f[el.dataset.d]; ui.f = f; if (!Object.keys(f).length) { ui.by = "account"; ui.from = null; } again(); break; }
  }
});
root.addEventListener("mouseover", (e) => { const c = e.target.closest(".col"); const i = c ? Number(c.dataset.i) : null; if (i !== ui.hover && (c || !e.target.closest(".tip"))) { ui.hover = i; render(); } });
root.addEventListener("mouseleave", () => { if (ui.hover != null) { ui.hover = null; render(); } });
// a door from elsewhere opens the page on a window and a filter: usage.html?account=head|label&by=project
{
  const A = new URLSearchParams(location.search);
  if (WINDOWS[A.get("win")]) ui.win = A.get("win");
  if (A.get("account")) ui.f.account = A.get("account");
  if (A.get("model")) ui.f.model = A.get("model");
  if (A.get("agent")) ui.f.agent = A.get("agent");
  if (DIMS[A.get("by")]) ui.by = A.get("by"); else if (hasFilter()) ui.by = nextBy(Object.keys(ui.f).at(-1));
}
(async () => { render(); await readStanding(); render(); await readWindow(); })();
