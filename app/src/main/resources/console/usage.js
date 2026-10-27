// Usage: what each plan and key used, and where it went, on splice's own answers. Every figure here is read from the
// control API and nothing is invented.
//
// WHERE EVERY VALUE COMES FROM.
//   GET /api/heads · /api/models · /api/status   the commands, each one's provider, the models' own labels
//   GET /api/accounts · /api/keys                a plan's windows as its provider reads them (Accounts' numbers), a key's
//                                                variable and the day splice first saw it
//   GET /api/sessions                            an agent's name and its project
//   GET /api/perf/turns?head=…&n=1               the window's TOTALS per account, model, session and day, folded by the
//                                                daemon over every request the filters match (TurnUsage.kt), so a week's
//                                                figures are never a count of the newest 2,000 rows
//   the same, n=2000 and usage=0                 the newest requests: a plan's spent windows, the projects of the
//                                                sessions in them, and whether a command is slow or failing today
//   GET /api/perf/summary                        how each command answered, per command (perf-heads.js)
//   PUT /api/accounts/names/{ref} · PATCH /api/auth/{head}/accounts/{label}   naming a login that has no name
//
// PLANS AND KEYS NEVER MEET IN ONE MONEY FIGURE (fin, Oct 11). A plan's requests read as their value at API prices,
// drawn hatched, never as money spent; only a key's requests are money. The two sit side by side, in the chart's Cost
// columns and in Where it went's lead, and are never added. A figure nobody reported is a dash, never a zero.
//
// THE DRAWING. Every quantity is drawn before it is written: a plan's windows are the kit's meters with their reset
// rings, what splice sent through each login is bar cells on scales the whole board shares, the window is columns whose
// parts carry their command where they have room, with each column's failed share under it, and every table row is one
// line of bars ending on one column. A qualifier is said once, on the band's head or in its legend. Each band is
// re-drawn on its own, so a hover moves nothing but the chart, and a value that changes grows into its new length.
CONSOLE.page("usage.html", (scope) => {
  "use strict";
  const { document, window, API, setTimeout, clearTimeout, setInterval, clearInterval, requestAnimationFrame, cancelAnimationFrame, addEventListener, removeEventListener } = scope;

const n0 = (n) => Math.round(n).toLocaleString("en-US");
const secs = (ms) => (ms < 1000 ? `${Math.round(ms)} ms` : ms < 60000 ? `${+(ms / 1000).toFixed(ms < 10000 ? 1 : 0)} s` : `${Math.floor(ms / 60000)} min ${Math.round((ms % 60000) / 1000)} s`);
const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const hourWord = (d) => `${d.getHours() % 12 || 12} ${d.getHours() < 12 ? "AM" : "PM"}`;
const dayClock = (d) => (d.toDateString() === NOW.toDateString() ? clock(d) : `${DAY[d.getDay()]} ${clock(d)}`);
const q = (xs, p) => { if (!xs.length) return null; const s = xs.slice().sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
const failed = (r) => isFailed(r.outcome);
const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone; // the daemon folds days in the viewer's zone
const HOUR = 3600e3;
const WINDOWS = { "5h": { label: "5 hours", word: "last 5 hours" }, "7d": { label: "7 days", word: "last 7 days" } };
const SEP = "\u001f"; // one account can carry several labels (its email, its selector name); a merged key joins them after the head
const MAX_HELD = 2000; // the most rows one read of a command carries (PerfRoutes.kt MAX_TURNS)
const LEN = { "5 hours": 300, Week: 10080, Month: 43200 };
const FAMILY = PROVIDER_FAMILY;
const NOT_READ = "Not read";
const EYE = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>';
const OPEN = DRAW.go;
const UP = G('<path d="M12 19V5M6 11l6-6 6 6"/>', 'class="go" aria-hidden="true"');
const DOWN = G('<path d="M12 5v14M6 13l6 6 6-6"/>', 'class="go" aria-hidden="true"');
const CUT = 12;       // the rows a list shows before its tail folds into one row
const MORE_STEP = 36;      // the rows one press of the tail adds: a list of 2,700 agents opens a screen at a time, never whole
const FIND_AT = 14;   // from this many rows the list carries a Find field
// the daemon's id for a login (AccountRef): a group keyed by one that no roster row holds is a login splice no longer has
const REF_SHAPE = /^[0-9a-f]{8}$/;
// a dash whose reason stands beside it in words, so it carries no title of its own
const BARE_DASH = `<i class="nr">—</i>`;
// a read that failed, in splice's words and never the code's, as the console says a page that did not open (console.js)
const WHY_NOT = { 500: "splice failed to send it", 502: "splice is not ready", 503: "splice is not ready", 504: "splice did not answer" };
// commands this read has not counted: still being read, or not read at all
const uncounted = () => (D.reading?.size ?? 0) + D.errors.length > 0;

const ui = { win: "7d", measure: "tokens", hover: null, by: "account", rank: "tokens", f: {}, shown: CUT, from: null, project: PICK.get(), pmenu: false,
  find: "", cold: new Set(), coldNew: null, naming: null, nameAt: "card", nameErr: null, nameDraft: "", nameBusy: false, landed: null, shook: null };
const root = document.getElementById("usage");

// ---------- what splice has told this page ----------
const S = { heads: [], providerOf: {}, labels: {}, sessions: {}, sessionsPending: true, keys: [], accounts: [], registry: [], groups: [], stood: false, error: null };
// one window's reads: the totals the daemon folded, and the newest rows. [all] is the window with the project pick alone
// (the plan and key cards), [scoped] the same under the filters opened below the cards.
const D = { loading: true, pending: false, every: null, all: null, scoped: null, rows: [], held: new Set(), errors: [], gen: 0 };

const providerOfHead = (key) => S.providerOf[key];
const cmdOf = (key) => S.heads.find((h) => h.key === key)?.command ?? key;
const sessTitle = (tag, fallback) => {
  const s = S.sessions[tag];
  return s?.name || s?.title || fallback || `Session ${tag}`;
};
const hasFilter = () => Object.keys(ui.f).length > 0;
const projectWord = () => (ui.project == null ? null : ui.project === NO_PROJECT_KEY ? "No project" : S.groups.find((g) => g.key === ui.project)?.label ?? ui.project);

async function readStanding() {
  const sessionsRead = API.get("/api/sessions");
  const [heads, models, keys, accounts, status, topology] = await Promise.all(
    ["/api/heads", "/api/models", "/api/keys", "/api/accounts", "/api/status", "/api/topology"].map((p) => API.get(p)));
  if (!heads.ok) { S.error = WHY_NOT[heads.status] ?? (heads.status ? "splice did not send it" : "splice did not answer"); console.warn(`commands: ${heads.status}`); return; }
  S.error = null;
  S.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key }));
  const bases = topology.body?.topology?.providers || {};
  const local = (name) => /^https?:\/\/(127\.0\.0\.1|\[?::1\]?|localhost)\b/.test(bases[name]?.base_url || "");
  for (const row of models.body?.heads || []) {
    S.providerOf[row.head] = FAMILY[row.provider] ?? (local(row.provider) ? "local" : row.provider);
    learnModels(S.labels, row);
  }
  S.keys = keys.body?.keys || [];
  S.accounts = accounts.body?.accounts || [];
  S.registry = status.body?.registry || [];
  S.stood = true;
  // Names and project lamps enrich the figures; a cold transcript listing must not hold the window's reads.
  Promise.all([sessionsRead, Promise.all(S.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/turns/live`)))]).then(([sessions, lives]) => {
    const rows = sessions.body?.sessions || [], turns = {};
    for (const s of rows) if (s.session_id) { S.sessions[s.session_id] = s; S.sessions[String(s.session_id).slice(0, 8)] = s; }
    S.heads.forEach((h, i) => { for (const t of lives[i].body?.turns || []) if (t.session && !t.stopped) turns[t.session] = { head: h.key, ...t }; });
    S.sessionsPending = false;
    S.groups = projectGroups(rows, turns, S.heads);
    const before = ui.project;
    ui.project = reconcilePick(ui.project, S.groups);
    if (before !== ui.project) again(); else render(true);
  });
}

// ---------- the daemon's questions, one per command ----------
// the window's columns: twenty quarter hours ending at the next quarter, or the seven calendar days ending today
function slices() {
  if (ui.win === "5h") {
    const end = Math.ceil(+NOW / 900e3) * 900e3;
    return Array.from({ length: 20 }, (_, i) => { const to = end - (19 - i) * 900e3; return { from: to - 900e3, to, label: new Date(to - 900e3).getMinutes() === 0 ? hourWord(new Date(to - 900e3)) : "", now: i === 19 }; });
  }
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() - (6 - i));
    const next = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1);
    return { from: +d, to: +next, day: d.toLocaleDateString("en-CA"), label: i === 6 ? "Today" : DAY[d.getDay()], now: i === 6 };
  });
}
const since = () => slices()[0].from;
function filterParams(p, f, label) {
  if (label != null) p.set("account", label);
  if (f.model) p.set("model", f.model);
  if (f.agent) p.set("session", String(f.agent).slice(0, 8));
  // No project is a pick of its own: the daemon reads it as project=none (TurnsFilter.kt), the way Requests asks
  // (splice-builder, Oct 12: the pick sent nothing and showed every project's figures under its name)
  if (ui.project && !f.wide) p.set("project", ui.project === NO_PROJECT_KEY ? "none" : ui.project);
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

const ZERO = () => ({ requests: 0, in: 0, cached: 0, write: 0, out: 0, noIn: 0, noOut: 0, cold: 0, coldIn: 0, coldUn: 0, cost: null, plan: 0, planValue: null, uncounted: 0, undeclared: 0, local: 0, unanswered: 0, failed: 0, limited: 0 });
function fold(into, g) {
  into.requests += g.requests ?? 0; into.in += g.input_tokens ?? 0; into.cached += g.cached_tokens ?? 0; into.out += g.output_tokens ?? 0;
  into.write += g.cache_write_tokens ?? 0; into.noIn += g.missing_input_requests ?? 0; into.noOut += g.missing_output_requests ?? 0; // a request with no count adds nothing, and is counted apart
  into.cold += g.cold_requests ?? 0; into.coldIn += g.cold_input_tokens ?? 0; into.coldUn += g.cold_requests == null ? g.requests ?? 0 : g.cold_unmarked_requests ?? 0; // a daemon that sends no mark marks none
  if (g.cost_usd != null) into.cost = (into.cost ?? 0) + g.cost_usd;
  into.plan += g.plan_requests ?? 0;
  if (g.plan_value_usd != null) into.planValue = (into.planValue ?? 0) + g.plan_value_usd;
  into.uncounted += g.unpriced_uncounted_requests ?? 0; into.undeclared += g.unpriced_undeclared_requests ?? 0;
  into.local += g.unpriced_local_requests ?? 0; into.unanswered += g.unanswered_requests ?? 0;
  into.failed += g.failed_requests ?? 0; into.limited += g.rate_limited_requests ?? 0; // TurnUsage's outcome mix (gap 408)
  return into;
}
/** The window's totals across every command that answered: per account, model, session and day, as the daemon folded them. */
function foldWindow(reads, heads) {
  const w = { total: ZERO(), byHead: new Map(), accounts: new Map(), models: new Map(), sessions: new Map(), days: new Map(), slices: new Map(), errors: [], pending: false, reading: new Set() };
  heads.forEach((head, i) => {
    const answer = (reads[i].body?.heads || []).find((x) => x.key === head.key);
    if (!reads[i].ok || !answer || answer.error) { w.errors.push(head.command); console.warn(`${head.command}: ${answer?.error ?? reads[i].status}`); return; }
    if (answer.read_pending) { w.pending = true; w.reading.add(head.key); return; }
    const u = answer.usage;
    if (!u) return;
    fold(w.total, u.totals);
    w.byHead.set(head.key, fold(w.byHead.get(head.key) ?? ZERO(), u.totals));
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
  // Count first. The multi-megabyte trails fill in after the totals and chart are already usable.
  const totals = await Promise.all(asks.map((a) => API.getReady(turnsUrl(a.h, a.held, a.f, a.label))));
  if (gen !== D.gen) return;
  const split = (scope) => { const idx = asks.map((a, i) => (a.f === scope ? i : -1)).filter((i) => i >= 0); return foldWindow(idx.map((i) => totals[i]), idx.map((i) => asks[i].h)); };
  D.scoped = split(scopes[0]);
  D.all = hasFilter() ? split(scopes[1]) : D.scoped;
  D.every = ui.project ? split(wide) : D.all;
  D.rows = []; D.held = new Set(); D.errors = [...D.scoped.errors];
  let pending = D.scoped.pending || D.all.pending || D.every.pending;
  const reading = new Set([...D.scoped.reading, ...D.all.reading, ...D.every.reading]);
  D.pending = pending; D.reading = reading; D.loading = false;
  render(true);
  const rows = await Promise.all(rowAsks.map((a) => API.getReady(turnsUrl(a.h, a.held, a.f, a.label))));
  if (gen !== D.gen) return;
  rowAsks.forEach((a, i) => {
    const answer = (rows[i].body?.heads || []).find((x) => x.key === a.h.key);
    if (!rows[i].ok || !answer || answer.error) { console.warn(`${a.h.command}: ${answer?.error ?? rows[i].status}`); if (!D.errors.includes(a.h.command)) D.errors.push(a.h.command); return; }
    if (answer.read_pending) { pending = true; reading.add(a.h.key); return; }
    D.held.add(a.h.key);
    for (const row of answer.rows || []) D.rows.push(mapRow(a.h, row));
  });
  if (rowAsks.length > headsFor(ui.f).length) { // several labels: keep the newest MAX_HELD of what they returned together, so the trail's axis is one reading
    D.rows.sort((x, y) => y.ts - x.ts);
    for (const head of D.held) {
      const mine = D.rows.filter((r) => r.head === head);
      if (mine.length > MAX_HELD) { const drop = new Set(mine.slice(MAX_HELD)); D.rows = D.rows.filter((r) => !drop.has(r)); }
    }
  }
  D.pending = pending; D.reading = reading; D.loading = false;
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
// ONE SPELLING for every count that runs to billions: 53.9B, 406M, 920K, 940 (the kit's DRAW.big).
const tok = (n) => DRAW.big(n);
const tokensOf = (s) => s.in + s.out;
// no request in the group reported a token count: its figure is missing, never 0 tokens (v0.4.0 section 9)
const noTokens = (s) => s.requests > 0 && s.noIn >= s.requests && s.noOut >= s.requests;
// A group whose only unpriced requests failed before any model answered spent $0 on them: the daemon counts those apart
// (unanswered_requests, TurnPriceGap), so a group of nothing else reads $0, never "No price" (Marlin, p229, Oct 12)
const spentOf = (s) => s.cost ?? (s.unanswered && !s.plan && !s.uncounted && !s.undeclared && !s.local ? 0 : null);
/** Why a group has no money figure at all, in one word, said beside the dash that stands where the figure would be. A
 *  plan's requests with no value are ones no price card covers. */
const noCostWhy = (s) => (s.uncounted ? "Uncounted" : s.undeclared ? "No price" : s.local ? "This computer" : s.plan ? "No price" : "Not reported");
const planValueOf = (s) => (s.plan ? s.planValue : null);
const share = (a, b) => (b > 0 ? Math.max(0, Math.min(100, (a / b) * 100)) : 0);
/** A bar's length on its column's scale: anything above nothing keeps a hairline, so 6 requests beside 77,796 still
 *  draws, and a true zero draws nothing at all (the kit's own rule for a stacked bar's smallest block). */
const barW = (a, b) => (a > 0 ? Math.max(1.1, share(a, b)) : 0);
/** A dollar figure where its column or legend already says it is an estimate: the kit's one spelling, without the "≈". */
const usd = (v) => DRAW.usd(v);
/** A failure rate as a figure: one decimal under 10%, so 1.4% and 0.6% never both read 1%, whole from 10%. */
const rateWord = (x) => (!(x > 0) ? "0%" : x < 0.001 ? "<0.1%" : x >= 0.0995 ? `${Math.round(x * 100)}%` : `${(x * 100).toFixed(1)}%`);
const chip = (head) => DRAW.chip({ text: cmdOf(head), data: ` style="--c:${colorOf(providerOfHead(head))}"` });
const chipsOf = (heads) => [...heads].map(chip).join("");
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
  // the pool slot a login sits in (edit_target.id) is one of its names too: a sole login on a command reaches splice
  // as "primary", and three rows read that word where the account's own name belongs
  && (a.ref ? a.ref === label : [a.account_labels?.[head], a.selector_key, a.label, a.display_name, a.edit_target?.id].some((x) => x && String(x).toLowerCase() === String(label).toLowerCase())));
const planName = (row, fallback) => (row.ref ? row.display_name : row.display_name || fallback || "");
/** An account in a row, a chip or a detail: a plan by its name, a key by its whole variable. The day splice first saw
 *  the key is drawn once, on the key's own card, where it belongs. */
function acctLabel(head, label, name) {
  const plan = planRowOf(head, label);
  if (plan) return esc(planName(plan, label));
  const key = keyRecord(label);
  if (key) return `<span class="var">${esc(key.name)}</span>`;
  return esc(name ?? label);
}

// ---------- plans and keys: one board of cards, grouped under each provider, as the Accounts page draws it ----------
function windowOf(pct, resetSec, lenSec, slot, current) {
  if (pct == null) return null;
  const month = slot === "long" && lenSec >= 28 * 86400;
  const label = slot === "short" ? "5 hours" : month ? "Month" : "Week";
  const left = resetSec ? Math.round((resetSec * 1000 - NOW.getTime()) / 60000) : null;
  const due = left != null && left >= 0;
  return { label, used: Math.round(pct), len: LEN[label], left: due ? left : null, resetMs: due ? resetSec * 1000 : null, stale: current === false };
}
/** A model's own slice of the week (Claude's Fable), drawn under the week it belongs to, as the Accounts page draws it. */
function modelWindows(row) {
  return (row.seven_day_models || []).filter((m) => m && m.used_percent != null).map((m) => {
    const left = m.resets_at ? Math.round((m.resets_at * 1000 - NOW.getTime()) / 60000) : null, due = left != null && left >= 0;
    return { label: m.model || "Model", used: Math.round(m.used_percent), len: LEN.Week, sub: true, left: due ? left : null, resetMs: due ? m.resets_at * 1000 : null, stale: false };
  });
}
const isPlanHead = (key) => {
  const h = S.registry.find((x) => x.key === key);
  return h && h.authKind !== "api-key" && h.family !== "local" && h.family !== "vast";
};
const isKeyHead = (key) => {
  const h = S.registry.find((x) => x.key === key);
  return h && h.authKind === "api-key" && h.family !== "local" && h.family !== "vast";
};
const reading = () => D.loading || !D.scoped;
/** The window's usage entries that belong to one plan account. */
const usageOfPlan = (row) => !D.all ? [] : [...D.all.accounts.values()].filter((e) => (row.heads || []).includes(e.head) && planRowOf(e.head, e.label) === row);
const sumOf = (entries) => entries.reduce((t, e) => fold2(t, e), ZERO());
function fold2(t, e) {
  t.requests += e.requests; t.in += e.in; t.cached += e.cached; t.out += e.out; t.plan += e.plan; t.uncounted += e.uncounted; t.undeclared += e.undeclared; t.local += e.local; t.unanswered += e.unanswered;
  t.failed += e.failed ?? 0; t.limited += e.limited ?? 0;
  t.write += e.write ?? 0; t.noIn += e.noIn ?? 0; t.noOut += e.noOut ?? 0;
  t.cold += e.cold ?? 0; t.coldIn += e.coldIn ?? 0; t.coldUn += e.coldUn ?? 0;
  if (e.cost != null) t.cost = (t.cost ?? 0) + e.cost;
  if (e.planValue != null) t.planValue = (t.planValue ?? 0) + e.planValue;
  return t;
}
// "No limits reported" only when the provider answered with none (Oct 12). Any other card with no windows is Not read:
// its meters stay as dashed empty tracks (.bar.gone), so the card keeps the shape of its neighbours.
const limitsWord = (row) => row.no_usage_at_epoch_seconds ? "No limits reported" : NOT_READ;
const signedOut = (row) => !row.credential_present || row.auth_exclusion_reason === "credential_missing" || !!row.refusal;
/** A login with no name of its own, that splice can name here: "Account" and the act beside it (the Accounts page's Name it). */
const canName = (row) => Boolean(row.label_default && row.can_rename);
const accountIdx = (row) => S.accounts.indexOf(row);
/** The filter an opened card or row set: its command and the labels it reads by. */
const pickedAccount = () => { const v = ui.f.account; if (!v) return null; const [head, ...rest] = v.split("|"); return { head, labels: rest.join("|").split(SEP) }; };

/** One window row of a card: the kit's meter, its percent, its reset ring, on the board's shared columns. */
function winRow(w) {
  const full = w.used >= 100;
  return `<span class="label${w.sub ? " sub" : ""}">${esc(w.label)}</span>`
    + DRAW.meter(Math.min(w.used, 100), `${w.sub ? "sub " : ""}${full ? "full " : ""}${w.stale ? "stale" : ""}`)
    + `<span class="pct${full ? " limit" : ""}">${w.used}%<span class="used">used</span></span>`
    + (w.left != null ? `<span class="when">${DRAW.ring(w.left / w.len)}Resets ${backWord(new Date(w.resetMs))}</span>` : `<span></span>`);
}
/** A window splice has not read: the dashed empty track the console draws for "not read since its reset", with the
 *  dash in its figure's place, so the card keeps its rows instead of collapsing to one word (console.css .bar.gone). */
const unreadRow = (label) => `<span class="label">${esc(label)}</span><div class="bar gone"></div><span class="pct">${DRAW.dash}</span><span></span>`;

/** What splice itself sent through a login in the window: three bar cells on scales the whole board shares, so the
 *  busiest login is found before a digit is read. While the window reads, each cell keeps its track, striped; a login
 *  with nothing in the window keeps the footer's height, so the board holds its shape from one window to the next. A
 *  figure nobody reported is a dashed track and a dash; a key with no price says why in the bar's place. */
function thruCells(s, mx, isKey) {
  if (reading()) return `<footer class="thru">${[0, 1, 2].map(() => GHOST.row([GHOST.bar(4.2), GHOST.track(), GHOST.bar(3.2)])).join("")}</footer>`;
  if (!s.requests) return `<footer class="thru none">${DRAW.nothing("No requests")}</footer>`;
  const missing = (label) => `<span class="barcell gone"><em>${label}</em><span class="vbar" aria-hidden="true"></span><b class="v">${DRAW.dash}</b></span>`;
  const cells = [];
  if (isKey) {
    const spent = spentOf(s);
    cells.push(spent == null
      ? `<span class="barcell why"><em>Spent</em><span class="whyw">${esc(noCostWhy(s))}</span><b class="v">${BARE_DASH}</b></span>`
      : DRAW.barCell({ w: barW(spent, mx.spend), text: usd(spent), label: "Spent" }));
  }
  cells.push(noTokens(s) ? missing("Tokens") : DRAW.barCell({ w: barW(tokensOf(s), mx.tok), text: tok(tokensOf(s)), label: "Tokens" }));
  cells.push(DRAW.barCell({ w: barW(s.requests, mx.req), text: n0(s.requests), label: "Requests" }));
  if (!isKey) cells.push(s.noIn < s.requests && s.in > 0 ? DRAW.barCell({ w: barW(s.cached, s.in), text: DRAW.pct(s.cached / s.in), label: "Cached" }) : missing("Cached"));
  return `<footer class="thru">${cells.join("")}</footer>`;
}
/** The name of a login with no name: "Account", and the act that gives it one, in place, wherever it was pressed (its
 *  card or its row in Where it went). */
function nameSlot(row, idx, at) {
  if (ui.naming === idx && ui.nameAt === at) {
    return `<span class="namebox"><input class="field nmfield" type="text" maxlength="48" aria-label="Name this account" value="${esc(ui.nameDraft ?? "")}" placeholder="Name">`
      + DRAW.act({ word: "Save", act: "namesave", cls: `small primary${ui.shook === idx ? " shook" : ""}`, busy: ui.nameBusy, data: ` data-k="${idx}"` })
      + DRAW.act({ word: "Cancel", act: "namestop", cls: "small quiet" })
      + `${ui.nameErr ? DRAW.result(ui.nameErr, "bad") : ""}</span>`;
  }
  return `<span class="nm">${esc(planName(row) || "Account")}</span>`
    + DRAW.act({ word: "Name it", act: "name", cls: `small${ui.shook === idx ? " shook" : ""}`, data: ` data-k="${idx}" data-at="${at}"` });
}

/** A card's height in rem, from what it is built of and never from its figures, so the board's columns are chosen the
 *  same way whatever the window says: its name, its window lines (or the one line that stands for them), the lines it
 *  carries over them (a limit, the request that spent the window) and its footer of bars, which is one height whether
 *  the login sent anything or not. The numbers are this card as it draws (measured at 2548 and 1440). */
const cardH = (kind, rows, out, lines = 0) => 11.9 + (kind === "key" ? 1.85 : out ? 2.4 : 2.35 * rows) + 1.9 * lines;

function planCard(row, mx) {
  const windows = [windowOf(row.five_hour_used_percent, row.five_hour_reset_epoch_seconds, row.five_hour_window_seconds, "short", row.five_hour_current),
    windowOf(row.seven_day_used_percent, row.seven_day_reset_epoch_seconds, row.seven_day_window_seconds, "long", row.seven_day_current)].filter(Boolean);
  const idx = accountIdx(row), out = signedOut(row), heads = row.heads || [];
  const mine = usageOfPlan(row), s = sumOf(mine), held = windows.some((w) => w.used >= 100);
  const rows = [];
  for (const w of windows) {
    rows.push(winRow(w));
    if (w.label === "Week" || w.label === "Month") for (const m of modelWindows(row)) rows.push(winRow(m));
  }
  const stale = windows.some((w) => w.stale) && row.observed_at_epoch_seconds ? row.observed_at_epoch_seconds * 1000 : null;
  if (stale) rows.push(`<span class="stale-at">${EYE}Last read ${backWord(new Date(stale))}</span>`);
  // a held login says so once, with the lock, over the bars it holds; a signed-out one offers the way back in their place
  const until = held ? windows.filter((w) => w.used >= 100 && w.resetMs).sort((a, b) => b.resetMs - a.resetMs)[0] : null;
  const body = out
    ? `<div class="outbox"><span class="state">Signed out</span><a class="act" href="accounts.html?head=${encodeURIComponent(heads[0] ?? "")}">Sign in${OPEN}</a></div>`
    : rows.length ? `<div class="windows">${rows.join("")}</div>`
      : row.no_usage_at_epoch_seconds ? `<div class="windows">${unreadRow("Limits")}</div><p class="nolimit">${limitsWord(row)}</p>`
        : `<div class="windows">${unreadRow("5 hours")}${unreadRow("Week")}</div>`;
  const ranOut = reading() ? null : D.rows.filter((r) => r.outcome === "error:plan-limit" && r.reset && r.reset - r.ts > 5 * HOUR && mine.some((e) => e.head === r.head && e.label === r.account)).sort((a, b) => a.ts - b.ts)[0];
  const lead = mine.length ? mine[0] : null;
  const door = lead ? ` data-act="pick" data-d="account" data-v="${esc(acctKeyOfAll(mine))}" role="button" tabindex="0" aria-label="${esc(`${planName(row) || "Account"}: where its requests went`)}"` : "";
  const pk = pickedAccount(), picked = Boolean(pk && heads.includes(pk.head) && pk.labels.some((l) => planRowOf(pk.head, l) === row));
  const lines = out ? 0 : rows.length;
  return { prov: providerOfHead(heads[0]), kind: "plan", name: planName(row) || "Account", used: Math.max(0, ...windows.map((w) => w.used)), s, heads: new Set(heads),
    rows: lines, h: cardH("plan", lines, out, (held ? 1 : 0) + (ranOut ? 1 : 0)),
    make: (withChips) => `<article class="card pcard${held ? " held" : ""}${out ? " out" : ""}${picked ? " picked" : ""}${ui.landed === idx ? " landed" : ""}" data-a="plan-${idx}" style="--c:${colorOf(providerOfHead(heads[0]))}"${door}>`
      + `<header>${canName(row) ? nameSlot(row, idx, "card") : `<span class="nm">${esc(planName(row))}</span>`}`
      + `${row.plan ? `<span class="pv">${esc(row.plan.charAt(0).toUpperCase() + row.plan.slice(1))}</span>` : ""}`
      + `${withChips ? `<span class="cmds">${heads.map(chip).join("")}</span>` : ""}</header>`
      + (held ? `<p class="heldline">${DRAW.lock(until?.resetMs ? `Limit reached until ${backWord(new Date(until.resetMs))}` : "Limit reached")}</p>` : "")
      + body
      + (ranOut ? `<p class="since"><a href="requests.html?win=7d#${encodeURIComponent(ranOut.id)}">Week ran out ${dayClock(new Date(ranOut.ts))}${OPEN}</a></p>` : "")
      + thruCells(s, mx, false) + (lead ? `<span class="drill" aria-hidden="true">${DOWN}</span>` : "") + `</article>` };
}

function keyCard(k, print, seen, replaced, mx) {
  const heads = (k.heads || []).map((x) => x.head).filter(isKeyHead);
  const label = `${k.name}:${print}`;
  const mine = !D.all ? [] : [...D.all.accounts.values()].filter((e) => heads.includes(e.head) && e.label === label), s = sumOf(mine), lead = mine[0];
  const door = s.requests && !reading() ? ` data-act="pick" data-d="account" data-v="${esc(acctKeyOf(lead.head, lead.label))}" role="button" tabindex="0" aria-label="${esc(`${k.name}: where its requests went`)}"` : "";
  const pk = pickedAccount(), picked = Boolean(pk && heads.includes(pk.head) && pk.labels.includes(label));
  return { prov: providerOfHead(heads[0]), kind: "key", name: k.name, used: -1, s, heads: new Set(heads), rows: -1, h: cardH("key"),
    make: (withChips) => `<article class="card kcard${picked ? " picked" : ""}" data-a="key-${esc(label)}" style="--c:${colorOf(providerOfHead(heads[0]))}"${door}>`
      + `<header><span class="nm"><span class="var">${esc(k.name)}</span>${seen ? ` · ${dayWord(new Date(seen * 1000))}` : ""}</span>`
      + `${replaced ? `<span class="pv">Replaced</span>` : ""}${withChips ? `<span class="cmds">${[...new Set(heads)].map(chip).join("")}</span>` : ""}</header>`
      // a key's provider sends no plan window: its limits read as not reported, never a zero (spec section 11)
      + `<p class="nolimit">Limits not reported by provider</p>`
      + thruCells(s, mx, true) + (door ? `<span class="drill" aria-hidden="true">${DOWN}</span>` : "") + `</article>` };
}

/** The board: every plan login and every key, each a card, grouped under its provider's name and dot, the group with
 *  the most logins first. Inside a group the login nearest its limit leads, so what is about to hurt reads first. A
 *  command every card of a group shares is said once, on the group's head; a group of one card keeps it on the card. */
function moneyHtml() {
  if (!S.stood) return boardHead() + ghostBoard();
  if (!S.accounts.length && !S.keys.length) {
    return boardHead() + `<div class="board" style="--n:${boardCols()}"><a class="add" href="accounts.html"><span class="plus" aria-hidden="true">+</span><span>Sign in to a plan or add a key on Accounts</span></a></div>`;
  }
  const plans = S.accounts.filter((row) => (row.heads || []).some(isPlanHead) && !(row.login_place && !row.credential_present && !row.observed_at_epoch_seconds));
  const keyRows = [];
  for (const k of S.keys) {
    if (!(k.heads || []).map((x) => x.head).filter(isKeyHead).length) continue;
    for (const [print, seen, replaced] of [[k.fingerprint, k.first_seen_epoch_seconds, false], ...(k.replaced || []).map((r) => [r.fingerprint, r.first_seen_epoch_seconds, true])]) keyRows.push({ k, print, seen, replaced });
  }
  const sums = [...plans.map((row) => sumOf(usageOfPlan(row))),
    ...keyRows.map(({ k, print }) => sumOf(!D.all ? [] : [...D.all.accounts.values()].filter((e) => (k.heads || []).map((x) => x.head).includes(e.head) && e.label === `${k.name}:${print}`)))];
  const mx = { tok: Math.max(1, ...sums.map(tokensOf)), req: Math.max(1, ...sums.map((s) => s.requests)), spend: Math.max(1e-9, ...sums.map((s) => spentOf(s) ?? 0)) };
  const cards = [...plans.map((row) => planCard(row, mx)), ...keyRows.map(({ k, print, seen, replaced }) => keyCard(k, print, seen, replaced, mx))];
  const groups = new Map();
  for (const c of cards) {
    const key = c.prov ?? "other";
    if (!groups.has(key)) groups.set(key, { prov: key, name: PROVIDER_NAME[key] ?? "Other", cards: [] });
    groups.get(key).cards.push(c);
  }
  const ordered = [...groups.values()].sort((a, b) => b.cards.length - a.cards.length || a.name.localeCompare(b.name));
  // the login nearest its limit leads, then by name: the board's order is the same whichever window is being read,
  // so a card does not move under the pointer when the window changes
  for (const g of ordered) {
    g.cards.sort((a, b) => b.used - a.used || a.name.localeCompare(b.name));
    const first = [...g.cards[0].heads].sort().join(" ");
    const shared = g.cards.length > 1 && g.cards.every((c) => [...c.heads].sort().join(" ") === first) && first;
    g.chips = shared ? chipsOf(g.cards[0].heads) : "";
    for (const c of g.cards) c.html = c.make(!shared);
  }
  const cols = boardCols();
  const stacks = packBoard(ordered, cols);
  keepShape(stacks, cols);
  return boardHead() + `<div class="board" style="--n:${cols}">`
    + stacks.map((st) => `<div class="pstack" style="--span:${st.span}">${st.items.map(groupHtml).join("")}</div>`).join("")
    + `</div>`;
}
const groupHtml = (it) => `<section class="ugroup" style="--c:${colorOf(it.g.prov)}"><h3><i class="blot" aria-hidden="true"></i>${esc(it.g.name)}${it.g.chips ? `<span class="cmds">${it.g.chips}</span>` : ""}</h3>`
  + `<div class="gcards" style="--k:${it.cols.length}">${it.cols.map((c) => `<div class="gcol">${c.map((card) => card.html).join("")}</div>`).join("")}</div></section>`;
/** A card draws two kinds of bar, and whose count each one is belongs to the band, not to every card: the window meters
 *  are each provider's limits, the bars under the rule are what splice itself sent, in the window and the project read. */
const boardHead = () => `<div class="bandhead"><h2>Plans and keys</h2>`
  + `<span class="pd-leg"><i class="sw lim"></i>Limits`
  + `<i class="sw cnt"></i>Counted by splice · ${projectWord() ? `${esc(projectWord())} · ` : ""}${WINDOWS[ui.win].word}</span></div>`;
/** How many columns of cards the board can hold at this width: a card wants 38rem, the gap between columns 2rem, and
 *  the board never runs past six. Measured on the page's own content box, as the project tiles measure theirs, so every
 *  width is the page's and never the window's. */
function boardCols() {
  const p = document.querySelector(".page");
  if (!p) return 1;
  const cs = getComputedStyle(p), rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  const w = (p.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)) / rem;
  return Math.max(1, Math.min(6, Math.floor((w + 2) / 40)));
}
/** The board's columns, filled as a board is: the provider with the most logins splits its cards down k columns, in
 *  order, column by column, and the others go into the columns left, each into the shortest. Where it takes the whole
 *  width, the rest form a row under it. k is the one that leaves the shortest board with the least empty screen under
 *  its columns, judged from each card's structure (cardH), so the providers keep their places from one read to the next. */
function packBoard(gs, n) {
  const HEAD = 3.7, GAP_G = 2.4, GAP_C = 1;
  const colH = (cards) => cards.reduce((a, c) => a + c.h, 0) + GAP_C * Math.max(0, cards.length - 1);
  const whole = (g) => ({ g, cols: [g.cards], h: HEAD + colH(g.cards) });
  const fill = (items, m) => {
    const st = Array.from({ length: m }, () => ({ span: 1, items: [], h: 0 }));
    for (const it of items.slice().sort((a, b) => b.h - a.h)) {
      const s = st.reduce((x, y) => (y.h < x.h ? y : x));
      s.h += (s.items.length ? GAP_G : 0) + it.h; s.items.push(it);
    }
    return st;
  };
  if (!gs.length) return [];
  if (n < 2) return [{ span: 1, items: gs.map(whole) }];
  const big = gs[0].cards.length >= 2 ? gs[0] : null;
  if (!big) return fill(gs.map(whole), n).filter((s) => s.items.length);
  const rest = gs.slice(1).map(whole);
  let best = null;
  for (let k = 1; k <= Math.min(n, big.cards.length); k++) {
    const per = Math.ceil(big.cards.length / k);
    const cols = Array.from({ length: k }, (_, i) => big.cards.slice(i * per, (i + 1) * per));
    if (cols.some((c) => !c.length)) continue;
    const inner = cols.map((c) => HEAD + colH(c)), bigH = Math.max(...inner);
    const st = fill(rest, k < n ? n - k : n);
    const heights = k < n ? [...inner, ...st.map((s) => s.h)] : inner.map((h, i) => (st[i].items.length ? bigH + GAP_G + st[i].h : h));
    const max = Math.max(...heights), holes = heights.reduce((a, h) => a + (max - h), 0);
    const score = max + holes / (2 * n);
    if (!best || score < best.score - 1e-6) best = { score, stacks: [{ span: k, items: [{ g: big, cols, h: bigH }] }, ...st.filter((s) => s.items.length)] };
  }
  return best.stacks;
}
/** A card before splice has answered at all: the card's own shape in striped bars, at the height the card itself will
 *  stand, so nothing under the board moves when it lands. [rows] is the window lines the card drew last time (-1 is a
 *  key, whose one line says its limits are not reported) and [h] the height it drew at. */
const ghostCard = (i, rows = 2, h = 0) => `<article class="card pcard ghosted" style="--i:${i}${h ? `;min-height:${h}rem` : ""}"><header>${GHOST.bar(7)}${GHOST.bar(2.6)}</header>`
  + (rows > 0 ? `<div class="windows">${Array.from({ length: rows }, () => `${GHOST.bar(3.2)}${GHOST.track()}${GHOST.bar(3)}${GHOST.bar(6)}`).join("")}</div>`
    : `<p class="nolimit">${GHOST.bar(12)}</p>`)
  + `<footer class="thru">${[0, 1, 2].map(() => GHOST.row([GHOST.bar(4.2), GHOST.track(), GHOST.bar(3.2)])).join("")}</footer></article>`;
// The board's own shape, kept from the last read: how many columns, which groups each held, how each group split its
// cards down its own columns, and each card's window lines and height. Shapes only: no figure is kept and none is
// drawn, so the first paint is the size of what lands.
const SHAPE_KEY = "splice-usage-board";
const keepShape = (stacks, cols) => {
  try { localStorage.setItem(SHAPE_KEY, JSON.stringify({ n: cols, s: stacks.map((st) => ({ p: st.span, g: st.items.map((it) => it.cols.map((c) => c.map((card) => [card.rows, Math.round(card.h * 10) / 10]))) })) })); }
  catch { /* a blocked or full store costs the ghost its shape and nothing else */ }
};
const okCard = (c) => Array.isArray(c) && c.length === 2 && c.every((v) => typeof v === "number");
const lastShape = (cols) => {
  try {
    const s = JSON.parse(localStorage.getItem(SHAPE_KEY) || "null");
    if (s && s.n === cols && Array.isArray(s.s) && s.s.length
      && s.s.every((st) => Array.isArray(st.g) && st.g.every((g) => Array.isArray(g) && g.every((c) => Array.isArray(c) && c.every(okCard))))) return s.s;
  } catch { /* unreadable */ }
  return Array.from({ length: cols }, () => ({ p: 1, g: [[[[2, cardH("plan", 2, false)], [2, cardH("plan", 2, false)]]]] }));
};
/** And the board they land in: the same columns and the same groups, so nothing jumps when the read lands. */
const ghostBoard = () => { const cols = boardCols(); let i = 0;
  return `<div class="board" style="--n:${cols}">` + lastShape(cols).map((st) =>
    `<div class="pstack" style="--span:${st.p}">` + st.g.map((sub) =>
      `<section class="ugroup"><h3>${GHOST.bar(5)}</h3><div class="gcards" style="--k:${sub.length}">${sub.map((c) => `<div class="gcol">${c.map(([r, h]) => ghostCard(i++, r, h)).join("")}</div>`).join("")}</div></section>`).join("")
    + `</div>`).join("") + `</div>`;
};

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
  if (sid === COMPARE_KEY) return { key: COMPARE_KEY, label: COMPARE_LABEL }; // the daemon's bucket of Compare models sends
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
    return [...m.values()].map((es) => {
      const lead = es.slice().sort((a, b) => b.requests - a.requests)[0], s = es.reduce((t, e) => fold2(t, e), ZERO());
      const row = planRowOf(lead.head, lead.label);
      return { k: acctKeyOfAll(es), many: es.length > 1, label: acctLabel(lead.head, lead.label, lead.name), sub: "", heads: lead.heads, s,
        nameIdx: row && canName(row) ? accountIdx(row) : null, row,
        // a login the roster no longer holds keeps the number it had while signed in (RosterAccountNames): it has no
        // card above, so its row says what it is
        gone: !row && isPlanHead(lead.head) && REF_SHAPE.test(String(lead.label)) };
    });
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
const niceTop = (v) => { const p = 10 ** Math.floor(Math.log10(Math.max(v, 1e-9))), m = v / p; return ([1, 1.5, 2, 3, 4, 5, 6, 8, 10].find((x) => m <= x) ?? 10) * p; };
const headsOrder = () => S.heads.map((h) => h.key);

// ---------- over time: one column per slice, stacked by command; a part opens its requests ----------
let alt = new Set();
const swatch = (head, hatch = false) => `<i class="${hatch ? "hatch" : alt.has(head) ? "alt" : ""}" style="--c:${colorOf(providerOfHead(head))}"></i>`;
/** The window, slice by slice: each command's tokens, the value of its plan requests at API prices, the money its key
 *  requests cost, and the failures among them. */
function chartSlices() {
  const w = D.scoped;
  return slices().map((x, i) => {
    const y = { ...x, tok: [], val: [], mon: [], failed: 0, limited: 0, n: 0 };
    for (const head of headsOrder()) {
      const s = ui.win === "5h" ? w.slices.get(head)?.[i] : w.days.get(head)?.get(x.day);
      if (!s) continue;
      y.failed += s.failed; y.limited += s.limited; y.n += s.requests;
      if (!noTokens(s) && tokensOf(s) > 0) y.tok.push([head, tokensOf(s)]);
      const v = planValueOf(s), m = spentOf(s);
      if (v > 0) y.val.push([head, v]);
      if (m > 0) y.mon.push([head, m]);
    }
    return y;
  });
}
const sumParts = (xs) => xs.reduce((a, [, v]) => a + v, 0);
function chartHtml() {
  const money = ui.measure === "cost";
  const sw = DRAW.switcher([["tokens", "Tokens"], ["cost", "Cost"]], ui.measure, "measure", "Measure");
  const headOf = (note) => `<div class="bandhead"><h2>${money ? "Cost" : "Tokens"} · ${WINDOWS[ui.win].word}</h2>${note}${sw}</div>`;
  const n = ui.win === "5h" ? 20 : 7;
  const xrow = (sl) => `<div class="xrow" style="--n:${n}">${(sl ?? Array.from({ length: n }, () => ({ label: "" }))).map((x) => `<span class="cx${x.now ? " part" : ""}">${esc(x.label)}</span>`).join("")}</div>`;
  // reading: the band keeps its head, its switch and its own shape, each column a striped bar of one height, so the
  // page under it never moves when the window's figures arrive, and no shape it invented reads as the window's own
  if (reading()) {
    return headOf("") + `<div class="figline">${GHOST.bar(7, "gh-hero")}${GHOST.bar(9)}</div>`
      + `<div class="plot ghosted"><div class="cbars" style="--n:${n}">`
      + Array.from({ length: n }, () => `<div class="cbar"><div class="cstack gh" style="height:62%"></div></div>`).join("")
      + `</div></div>${xrow(slices())}<div class="flane" style="--n:${n}"></div><div class="legend">${[6, 7, 5].map((r) => GHOST.bar(r)).join("")}</div>`;
  }
  const sl = chartSlices();
  const kinds = money ? ["val", "mon"].filter((k) => sl.some((x) => x[k].length)) : ["tok"];
  const shown = headsOrder().filter((h) => sl.some((x) => kinds.some((k) => x[k].some(([d]) => d === h))));
  alt = money ? new Set() : new Set(shown.filter((h, i) => shown.slice(0, i).some((d) => providerOfHead(d) === providerOfHead(h))));
  const fmt = (v) => (money ? usd(v) : tok(Math.round(v)));
  const all = D.scoped.total, reqs = all.requests, fails = all.failed;
  const head = headOf(readNote());
  // THE SECTION'S ANSWER, on one baseline: the window's own figure and what it is a figure of, each a door to exactly
  // those requests; then the failed share, red only when it hurts, with its count as the door to them.
  const failPart = fails ? `<span class="fsep" aria-hidden="true"></span><b class="fig-focal${fails / reqs >= 0.05 ? " lv-bad" : ""}">${rateWord(fails / reqs)}</b>`
    + `<a class="cap" href="${reqUrl({ outcome: "fail" })}"><b>${n0(fails)}</b> failed${all.limited ? `<span>, <b>${n0(all.limited)}</b> rate-limited</span>` : ""}${OPEN}</a>` : "";
  let lead;
  if (!money) {
    lead = `<div class="figline"><b class="fig-hero">${reqs && !noTokens(all) ? tok(tokensOf(all)) : DRAW.dash}</b>`
      + (reqs ? `<a class="cap" href="${reqUrl()}">tokens in <b>${n0(reqs)}</b> requests${OPEN}</a>` : `<span class="cap">tokens</span>`)
      + failPart + `</div>`;
  } else {
    const value = planValueOf(all), spent = spentOf(all);
    lead = `<div class="figline">`
      + (value != null || spent == null ? `<b class="fig-hero">${value != null ? usd(value) : DRAW.dash}</b><span class="cap"><i class="sw plan" aria-hidden="true"></i>on your plans</span>` : "")
      + (spent != null ? `${value != null ? `<span class="fsep" aria-hidden="true"></span>` : ""}<b class="${value != null ? "fig-focal" : "fig-hero"}">${usd(spent)}</b><span class="cap"><i class="sw money" aria-hidden="true"></i>spent on keys</span>` : "")
      + failPart + `</div>`;
  }
  const peak = Math.max(0, ...sl.map((x) => Math.max(0, ...kinds.map((k) => sumParts(x[k])))));
  const flane = `<div class="flane" style="--n:${n}"><span class="fl-k">Failed</span>${sl.map((x) => (x.failed
    ? `<a class="cfl${x.failed / x.n >= 0.05 ? " lv-bad" : ""}" href="${reqUrl({ from: x.from, to: x.to, outcome: "fail" })}" aria-label="${esc(failWords(x))}"><span class="ftrk"><i style="width:${barW(x.failed, x.n).toFixed(2)}%"></i></span><b>${rateWord(x.failed / x.n)}</b></a>`
    : `<span></span>`)).join("")}</div>`;
  const keys = shown.map((h) => `<span class="key">${swatch(h)}${esc(cmdOf(h))}</span>`).join("")
    + (money && kinds.includes("val") ? `<span class="key kind"><i class="hatch" style="--c:var(--text-mute)"></i>On your plans, at API prices</span>` : "")
    + (money && kinds.includes("mon") ? `<span class="key kind"><i style="--c:var(--text-mute)"></i>Spent on keys</span>` : "")
    // the last column is the one still filling: its top edge is open and its parts lighter, and the legend says so once
    + `<span class="key part"><i aria-hidden="true"></i>${ui.win === "7d" ? "Today, so far" : "This quarter hour, so far"}</span>`;
  // an empty window keeps its frame, its slots and its days, and says so once inside the plot
  if (!(peak > 0)) {
    const why = !reqs ? (uncounted() ? "Nothing counted yet" : `No requests in the ${WINDOWS[ui.win].word}`) : money ? "Nothing priced" : "No tokens counted";
    return head + lead + `<div class="plot empty"><div class="cbars" style="--n:${n}">${sl.map((x) => `<div class="cbar${x.now ? " part" : ""}"><span class="slot"></span></div>`).join("")}</div>`
      + `<p class="plotnone">${DRAW.nothing(why)}</p></div>${xrow(sl)}${flane}<div class="legend">${keys}</div>`;
  }
  const top = niceTop(peak);
  const stackOf = (x, k) => {
    const parts = x[k], total = sumParts(parts);
    if (!total) return `<div class="cstack ${k}" style="height:0%"></div>`;
    return `<div class="cstack ${k}" style="height:${((total / top) * 100).toFixed(2)}%"><span class="cfig">${fmt(total)}</span>`
      + parts.map(([h, v]) => `<a class="${k === "val" ? "hatch" : alt.has(h) ? "alt" : ""}" href="${reqUrl({ cmd: cmdOf(h), from: x.from, to: x.to })}" style="--c:${colorOf(providerOfHead(h))};flex:${((v / total) * 1000).toFixed(2)} 1 0" aria-label="${esc(`${cmdOf(h)} ${fmt(v)}`)}">`
        + `<span class="sl"><span class="nm">${esc(cmdOf(h))}</span> <b>${fmt(v)}</b></span></a>`).join("") + `</div>`;
  };
  const bars = sl.map((x, i) => `<div class="cbar${kinds.length > 1 ? " pair" : ""}${ui.hover === i ? " on" : ""}${x.now ? " part" : ""}" data-i="${i}">${kinds.map((k) => stackOf(x, k)).join("")}</div>`).join("");
  const grid = [1, .5].map((f) => `<div class="gl" style="bottom:${f * 100}%"><span>${fmt(top * f)}</span></div>`).join("");
  const tip = ui.hover != null ? tipHtml(sl[ui.hover], sl.length, kinds, fmt) : "";
  return head + lead + `<div class="plot">${grid}<div class="cbars" style="--n:${n}">${bars}</div>${tip}</div>${xrow(sl)}${flane}<div class="legend">${keys}</div>`;
}
/** A part's name and figure stay inside it only where they fit as drawn: the name goes first, then the figure. The tip,
 *  the legend and each part's own label for assistive tech still carry both. The figures over the columns stay only
 *  while no two of them touch: on narrow slots they would run into each other, and the tip carries them. */
function fitParts(el) {
  for (const s of el.querySelectorAll(".cstack > a > .sl, .tstack > i > b")) {
    const part = s.parentElement, fits = () => s.offsetWidth <= part.clientWidth - 6 && s.offsetHeight <= part.clientHeight;
    if (fits()) continue;
    s.querySelector(".nm, .w")?.remove();
    if (!fits()) s.remove();
  }
  const figs = [...el.querySelectorAll(".cfig")].map((f) => [f, f.getBoundingClientRect()]).sort((a, b) => a[1].left - b[1].left);
  if (figs.some(([, r], i) => i > 0 && r.left < figs[i - 1][1].right + 4)) for (const [f] of figs) f.remove();
}
// a slice's failed requests, and the rate-limited ones among them, in words: what a door to them reads aloud
const failWords = (x) => `${n0(x.failed)} failed of ${n0(x.n)} requests${x.limited ? `, ${n0(x.limited)} of them rate-limited` : ""}`;
function tipHtml(x, n, kinds, fmt) {
  const span = ui.win === "5h" ? `${clock(new Date(x.from))} to ${clock(new Date(x.to))}` : x.label === "Today" ? "Today, so far" : `${x.label} ${new Date(x.from).getDate()}`;
  const left = ((ui.hover + .5) / n) * 100;
  const part = (k) => {
    const total = sumParts(x[k]);
    const lines = x[k].slice().sort((a, b) => b[1] - a[1]).map(([h, v]) => `<p>${swatch(h, k === "val")}<span>${esc(cmdOf(h))}</span><i class="tbar" style="width:${share(v, total).toFixed(1)}%"></i><b>${fmt(v)}</b></p>`).join("");
    const word = k === "val" ? "On your plans" : k === "mon" ? "Spent on keys" : "All";
    return lines + (x[k].length > 1 || kinds.length > 1 ? `<p class="sum"><span>${word}</span><b>${fmt(total)}</b></p>` : "");
  };
  const body = kinds.map(part).join("");
  const fail = x.failed ? `<p class="sum fail"><span>Failed</span><i class="tbar f" style="width:${barW(x.failed, x.n).toFixed(1)}%"></i><b>${rateWord(x.failed / x.n)}</b></p>`
    + `<p class="sub"><b>${n0(x.failed)}</b> of <b>${n0(x.n)}</b>${x.limited ? `<span class="dot"></span><b>${n0(x.limited)}</b> rate-limited` : ""}</p>` : "";
  return `<div class="tip${left > 60 ? " lefty" : ""}" style="left:${left.toFixed(2)}%"><p class="tw">${span}</p>${body || `<p class="none">No requests</p>`}${fail}</div>`;
}

// ---------- the filters a drill set: drawn once, over the bands they shape ----------
function filterChips() {
  const chip1 = (d, text) => `<span class="fwrap"><span class="fbtn on"><em>${DIMS[d].one}</em>${text}</span><button class="fclear" type="button" data-act="unpick" data-d="${d}" aria-label="Clear">${ICON.close}</button></span>`;
  return Object.entries(ui.f).map(([d, v]) => {
    const text = d === "account" ? acctLabel(v.split("|")[0], v.split("|").slice(1).join("|").split(SEP)[0], D.all?.accounts.get(v.split(SEP)[0])?.name) : d === "model" ? esc(modelName(S.labels, v)) : esc(sessTitle(String(v).slice(0, 8)));
    return chip1(d, text);
  }).join("");
}
/** The scope line between the board and the chart: what the chart and the tables under it are narrowed to, each filter
 *  with its clear, Clear all with two or more, the way back to the card a drill started from, and the same requests on
 *  Requests. A drill lands here, so the filter it added is the first thing on screen over the bands it narrows. */
function scopeHtml() {
  const n = Object.keys(ui.f).length;
  if (!n) return "";
  return `<div class="fchips">${filterChips()}</div>`
    + (n > 1 ? `<button class="act quiet small" type="button" data-act="clear">Clear all</button>` : "")
    + `<span class="sgap"></span>`
    + (ui.from ? `<button class="act quiet small" type="button" data-act="back">${UP}Back to the card</button>` : "")
    + `<a class="act small" href="${reqUrl()}">Open on Requests${OPEN}</a>`;
}

// ---------- where it went: one line per thing, its tokens, requests and cost drawn on shared scales ----------
const plain = (html) => String(html).replace(/<[^>]*>/g, "");
/** The cost cell: a key's money solid, a plan's value at API prices hatched, each on a line of its own on one dollar
 *  scale, so a row that holds both draws both and never adds them; the reason in words where splice has no price at
 *  all. The two marks and the estimate are named once, in the band's head. */
function costCell(s, max) {
  const cost = spentOf(s), value = planValueOf(s);
  if (cost == null && value == null) return `<span class="cell cost none"><span class="whyw">${esc(noCostWhy(s))}</span><b class="v">${BARE_DASH}</b></span>`;
  const line = (v, plan) => `<span class="cline"><span class="vbar" aria-hidden="true"><b${plan ? ` class="plan"` : ""} style="width:${barW(v, max).toFixed(1)}%"></b></span>`
    + `<b class="v ${plan ? "val" : "money"}">${usd(v)}</b></span>`;
  return `<span class="cell cost${cost != null && value != null ? " two" : ""}">${cost != null ? line(cost, false) : ""}${value != null ? line(value, true) : ""}</span>`;
}
/** The tokens cell: one bar on the column's scale and its figure; a group no request reported tokens for is a dashed
 *  track and a dash, never 0. */
const tokCell = (s, max) => (noTokens(s) ? `<span class="cell tok"><span class="vbar gone" aria-hidden="true"></span><b class="v num">${DRAW.dash}</b></span>`
  : `<span class="cell tok">${DRAW.vbar(barW(tokensOf(s), max))}<b class="v num">${tok(tokensOf(s))}</b></span>`);
const ROWS_KEY = "splice-usage-rows";
const lastRows = (k, fallback) => { try { return JSON.parse(localStorage.getItem(ROWS_KEY) || "{}")[k] ?? fallback; } catch { return fallback; } };
const keepRows = (k, n) => { try { const all = JSON.parse(localStorage.getItem(ROWS_KEY) || "{}"); all[k] = n; localStorage.setItem(ROWS_KEY, JSON.stringify(all)); } catch { /* the ghost keeps its default size */ } };
const RANKS = [["tokens", "Tokens"], ["requests", "Requests"], ["cost", "Cost, estimated"]];
const rankBy = (e) => (ui.rank === "cost" ? Math.max(spentOf(e.s) ?? 0, planValueOf(e.s) ?? 0) : ui.rank === "requests" ? e.s.requests : tokensOf(e.s));
function byHtml() {
  const head = `<div class="bandhead"><h2>Where it went</h2>`
    + `<span class="pd-leg"><i class="sw money"></i>Spent<i class="sw plan"></i>On your plan, at API prices</span></div>`;
  const tools = (extra = "") => `<div class="byhead">${DRAW.switcher(Object.entries(DIMS).map(([k, v]) => [k, v.word]), ui.by, "by", "Group by")}${extra}</div>`;
  // the head row's columns are doors that rank the rows; the column the rows are ranked by carries the arrow
  const colsOf = (cls) => `<div class="${cls} bhead"><span>${DIMS[ui.by].one}</span>`
    + RANKS.map(([k, word]) => `<button class="cell rank${ui.rank === k ? " on" : ""}" type="button" data-act="rank" data-v="${k}" aria-pressed="${ui.rank === k}">${esc(word)}${ui.rank === k ? DOWN : ""}</button>`).join("")
    + `</div>`;
  // reading: the columns' names are already known, so the table reads as itself and the rows that land take the place
  // the stripes held. Its rows are .gbrow, never .brow: a .brow is a row of this window's figures.
  const waiting = () => head + `<div class="figline sub">${GHOST.fig(2.6)}${GHOST.bar(8)}</div>` + tools() + ghostRows(lastRows(`by-${ui.by}`, CUT + 1), 4, colsOf("gbrow"));
  if (reading()) return waiting();
  if ((ui.by === "project" || ui.by === "agent") && S.sessionsPending) return waiting();
  const every = groupsOf(ui.by).sort((a, b) => rankBy(b) - rankBy(a) || b.s.requests - a.s.requests || tokensOf(b.s) - tokensOf(a.s));
  const find = ui.find.trim().toLowerCase();
  const list = find ? every.filter((e) => plain(e.label).toLowerCase().includes(find) || plain(e.sub).toLowerCase().includes(find)) : every;
  // Rows next to each other that ran on the same commands share one line of chips over them, said once; a row with no
  // neighbour on its commands keeps its chips on itself. The rows keep their rank: a run is only ever consecutive rows.
  const order = [];
  for (let i = 0; i < list.length;) {
    const k = [...list[i].heads].sort().join(" ");
    let j = i + 1;
    while (j < list.length && [...list[j].heads].sort().join(" ") === k) j++;
    for (let x = i; x < j; x++) order.push({ e: list[x], first: x === i, size: j - i });
    i = j;
  }
  const mx = { tok: Math.max(1, ...list.map((e) => tokensOf(e.s))), req: Math.max(1, ...list.map((e) => e.s.requests)),
    cost: Math.max(1e-9, ...list.map((e) => Math.max(spentOf(e.s) ?? 0, planValueOf(e.s) ?? 0))) };
  const cut = order.length <= ui.shown + 1 ? order.length : ui.shown;
  const shown = order.slice(0, cut), tail = order.slice(cut).map((o) => o.e);
  const row = ({ e, first, size }) => {
    const f = ui.by === "project" ? ui.f : { ...ui.f, [ui.by]: e.k }, extra = ui.by === "project" ? { project: e.k } : {};
    const reqFig = `<span class="vbar" aria-hidden="true"><b style="width:${barW(e.s.requests, mx.req).toFixed(1)}%"></b></span><b class="v">${n0(e.s.requests)}</b>`;
    const naming = e.nameIdx != null && e.row;
    const name = naming ? nameSlot(e.row, e.nameIdx, "row") : `<span class="nm">${e.label}</span>`;
    const line = first && size > 1 ? `<div class="bgrp"><span class="cmds">${chipsOf(e.heads)}</span></div>` : "";
    const dot = ui.by === "project" ? "" : `<i class="dot-c" aria-hidden="true"></i>`;
    return line + `<div class="brow${e.gone ? " gone" : ""}" data-act="pick" data-d="${ui.by}" data-v="${esc(e.k)}" style="--c:${colorOf(providerOfHead([...e.heads][0]))}" role="button" tabindex="0">`
      + `<span class="who">${dot}${name}${e.sub ? `<span class="sb">${e.sub}</span>` : ""}${e.gone ? `<span class="state">Signed out</span>` : ""}`
      + `${size === 1 ? `<span class="cmds">${chipsOf(e.heads)}</span>` : ""}</span>`
      + tokCell(e.s, mx.tok)
      + (e.many ? `<span class="cell req num">${reqFig}</span>` : `<a class="cell req num open" href="${reqUrl({ ...extra, order: "price" }, f)}">${reqFig}${OPEN}</a>`)
      + costCell(e.s, mx.cost) + `</div>`;
  };
  // the tail is a row like the others, carrying its own count and its own totals, so what is folded away is still drawn
  const tailRow = tail.length ? (() => {
    const s = tail.reduce((t, e) => fold2(t, e.s), ZERO());
    const more = Math.min(MORE_STEP, tail.length);
    return `<div class="brow btail" data-act="more" role="button" tabindex="0" aria-label="${esc(`Show ${more} more ${DIMS[ui.by].word.toLowerCase()}`)}">`
      + `<span class="who"><span class="nm">${DRAW.n(tail.length)} more</span><span class="sb">${esc(tail.length === 1 ? DIMS[ui.by].one.toLowerCase() : DIMS[ui.by].word.toLowerCase())}</span>${DRAW.chev}</span>`
      + tokCell(s, mx.tok)
      + `<span class="cell req"><span class="vbar" aria-hidden="true"><b style="width:${barW(s.requests, mx.req).toFixed(1)}%"></b></span><b class="v">${n0(s.requests)}</b></span>`
      + costCell(s, mx.cost) + `</div>`;
  })() : "";
  const fewer = ui.shown > CUT && order.length > CUT + 1 ? `<button class="morepill" type="button" data-act="fewer">Fewer</button>` : "";
  const finder = every.length >= FIND_AT
    ? `<span class="findbox"><input class="field bfind" type="search" value="${esc(ui.find)}" placeholder="Find" aria-label="Find a ${esc(DIMS[ui.by].one.toLowerCase())}">`
      + `${find ? `<span class="found">${DRAW.n(list.length)} of ${DRAW.n(every.length)}</span>` : ""}</span>` : "";
  // THE BAND'S ANSWER: where the money went, the plans' value and the keys' spend side by side under their own marks,
  // never added; with no money at all, the tokens it ranks. How many rows there are is a caption, never the figure.
  const tot = D.scoped.total, value = planValueOf(tot), spent = spentOf(tot), count = every.length;
  // with nothing in the window the lead says what the figure is and the table under it says there were no requests:
  // "across 0 accounts" counts a thing that is not there
  const many = count ? `${DRAW.n(count)} ${esc(count === 1 ? DIMS[ui.by].one.toLowerCase() : DIMS[ui.by].word.toLowerCase())}` : "";
  const lead = `<div class="figline sub mlead">`
    + (value != null ? `<span class="mfig"><i class="sw plan" aria-hidden="true"></i><b class="fig-focal">${usd(value)}</b></span>` : "")
    + (spent != null ? `<span class="mfig"><i class="sw money" aria-hidden="true"></i><b class="fig-focal">${usd(spent)}</b></span>` : "")
    + (value == null && spent == null ? `<b class="fig-focal">${noTokens(tot) || !tot.requests ? DRAW.dash : tok(tokensOf(tot))}</b><span class="cap">tokens${many ? ` across ${many}` : ""}</span>` : `<span class="cap">across ${many}</span>`)
    + `</div>`;
  const rowsHtml = list.length ? shown.map(row).join("") + tailRow
    : `<div class="bnone">${DRAW.nothing(find ? "Nothing by that name" : uncounted() ? "Nothing counted yet" : "No requests")}</div>`;
  keepRows(`by-${ui.by}`, list.length ? shown.length + (tail.length ? 1 : 0) + shown.filter((o) => o.first && o.size > 1).length * .6 : 1);
  return head + lead + tools(finder) + `<div class="card btable">${colsOf("brow")}${rowsHtml}</div>${fewer}`;
}
/** Rows the size of the rows that will land, so the column keeps its height while the window reads. They are not
 *  .brow: a reading row is a shape, never a row of figures, and nothing on the page may read the two as one. */
const ghostRows = (n, cells, head = "") => `<div class="card btable ghosted">${head}${Array.from({ length: Math.max(3, Math.round(n)) }, (_, i) =>
  `<div class="gbrow" style="--i:${i}"><span class="who">${GHOST.bar(9)}</span>${Array.from({ length: cells - 1 }, () => `<span class="cell">${GHOST.track()}</span>`).join("")}</div>`).join("")}</div>`;

// ---------- each command's tokens: one bar per command, split as its provider counted them ----------
// Input holds what was read from the cache and what was written to it, as every provider here reports it. A figure no
// request reported is a dash, never 0: a provider that never reports a cache write (OpenAI's Responses) sends 0 on every
// request, so a window of input with no write at all is that silence, and Written reads as a dash.
function tokensHtml() {
  const legend = `<span class="pd-leg tk"><i class="sw s1"></i>Cached<i class="sw s2"></i>Written<i class="sw s3"></i>Fresh</span>`;
  const head = (body, lead = "") => `<div class="bandhead"><h2>Tokens by command</h2>${legend}</div>${lead}${body}`;
  const cols = `<div class="trow thead"><span>Command</span><span class="cell">Input</span><span class="cell">Written</span><span class="cell">Output</span><span class="cell">Reported</span><span class="cell">Cold turns</span></div>`;
  // the table is kept while the window reads, every row a striped bar the size its figures land at, its columns named
  if (reading()) return head(`<div class="card ttable ghosted">${cols}${Array.from({ length: Math.max(3, lastRows("tok", 6)) }, (_, i) =>
    `<div class="gtrow" style="--i:${i}"><span class="who">${GHOST.bar(7, "chip")}</span>${[0, 1, 2, 3, 4].map(() => `<span class="cell">${GHOST.track()}</span>`).join("")}</div>`).join("")}</div>`,
  `<div class="figline sub">${GHOST.fig(2.6)}${GHOST.bar(9)}</div>`);
  const w = D.scoped, rd = D.reading ?? new Set();
  // the command with the most input leads, so the column the rows are read by is the order they are in
  const list = headsOrder().map((h) => [h, w.byHead.get(h)]).filter(([h, s]) => rd.has(h) || s?.requests > 0)
    .sort((a, b) => (rd.has(a[0]) - rd.has(b[0])) || (b[1]?.in ?? 0) - (a[1]?.in ?? 0));
  keepRows("tok", list.length || 1);
  if (!list.length) return head(`<div class="card ttable">${cols}<div class="bnone">${DRAW.nothing(uncounted() ? "Nothing counted yet" : "No requests")}</div></div>`, `<div class="figline sub"><b class="fig-focal">${DRAW.dash}</b><span class="cap">read from cache</span></div>`);
  const mx = { in: Math.max(1, ...list.map(([, s]) => s?.in ?? 0)), out: Math.max(1, ...list.map(([, s]) => s?.out ?? 0)), write: Math.max(1, ...list.map(([, s]) => s?.write ?? 0)), cold: Math.max(1, ...list.map(([, s]) => s?.cold ?? 0)) };
  const tin = list.reduce((a, [, s]) => a + (s?.in ?? 0), 0), tcached = list.reduce((a, [, s]) => a + (s?.cached ?? 0), 0);
  const lead = `<div class="figline sub">${tin > 0 ? `<b class="fig-focal">${DRAW.pct(tcached / tin)}</b><span class="cap">of <b>${tok(tin)}</b> input read from cache</span>` : `<b class="fig-focal">${DRAW.dash}</b><span class="cap">read from cache</span>`}</div>`;
  const rows = list.map(([h, s]) => {
    if (rd.has(h)) return `<div class="trow"><span class="who">${chip(h)}</span>${[0, 1, 2, 3, 4].map(() => `<span class="cell">${GHOST.track()}</span>`).join("")}</div>`;
    const noIn = s.noIn >= s.requests, counted = s.requests - Math.max(s.noIn, s.noOut);
    const fresh = Math.max(0, s.in - s.cached - s.write);
    const segs = [["s1", "Cached", s.cached], ["s2", "Written", s.write], ["s3", "Fresh", fresh]];
    const stackW = barW(s.in, mx.in);
    const inCell = noIn ? `<span class="cell inp"><span class="ttrack empty"></span><b class="v">${DRAW.dash}</b></span>`
      : `<span class="cell inp"><span class="ttrack"><span class="tstack" style="width:${stackW.toFixed(1)}%">`
        + segs.map(([cls, word, v]) => (v > 0 ? `<i class="${cls}" style="width:${((v / s.in) * 100).toFixed(2)}%" title="${esc(`${word} ${tok(v)} · ${DRAW.pct(v / s.in)} of input`)}"><b><span class="w">${esc(word)} </span>${tok(v)}</b></i>` : "")).join("")
        + `</span></span><b class="v">${tok(s.in)}</b></span>`;
    // a provider that reports no cache write sends none on any request: the dash says it was not reported, never that
    // nothing was written
    const wrCell = s.write > 0 ? `<span class="cell wr">${DRAW.vbar(barW(s.write, mx.write))}<b class="v">${tok(s.write)}</b></span>`
      : `<span class="cell wr"><span class="vbar gone" aria-hidden="true"></span><b class="v"><i class="nr" title="Its provider reports no cache writes">—</i></b></span>`;
    const outCell = s.noOut >= s.requests ? `<span class="cell"><span class="vbar gone" aria-hidden="true"></span><b class="v">${DRAW.dash}</b></span>`
      : `<span class="cell">${DRAW.vbar(barW(s.out, mx.out))}<b class="v">${tok(s.out)}</b></span>`;
    const countCell = `<span class="cell rep" title="${esc(`${n0(counted)} of ${n0(s.requests)} requests carried a count`)}">${DRAW.vbar(share(counted, s.requests))}<b class="v">${DRAW.pct(counted / s.requests)}</b></span>`;
    const accts = [...w.accounts.values()].filter((a) => a.head === h && (a.cold > 0 || a.coldUn > 0)).sort((a, b) => b.cold - a.cold || b.coldUn - a.coldUn);
    const open = ui.cold.has(h) && accts.length > 0;
    const unmarked = s.coldUn > 0 ? `<small class="um">${n0(s.coldUn)} not marked</small>` : "";
    const coldBar = s.cold > 0 ? `<span class="vbar" aria-hidden="true"><b style="width:${barW(s.cold, mx.cold).toFixed(1)}%"></b></span><b class="v">${n0(s.cold)}</b>` : "";
    const coldCell = accts.length
      ? `<button class="cell cold disc" type="button" data-act="cold" data-k="${esc(h)}" aria-expanded="${open}" aria-controls="cold-${esc(h)}">${DRAW.chev}${coldBar}${unmarked}</button>`
      : `<span class="cell cold">${coldBar}${unmarked}</span>`;
    return `<div class="trow${open ? " open" : ""}" style="--c:${colorOf(providerOfHead(h))}"><span class="who">${chip(h)}</span>${inCell}${wrCell}${outCell}${countCell}${coldCell}</div>`
      + (open ? coldPlate(h, s, accts) : "");
  }).join("");
  return head(`<div class="card ttable">${cols}${rows}</div>`, lead);
}
/** The turns of one command that ran cache-cold after splice switched its account, account by account: how many, the
 *  input each sent again with what it is a share of, and the turns splice could not mark. */
function coldPlate(h, s, accts) {
  const mx = { cold: Math.max(1, ...accts.map((a) => a.cold)), in: Math.max(1, ...accts.map((a) => a.coldIn)) };
  // the turns splice could not mark are a column of the plate only where a login has any: a column of nothing says
  // nothing, and the command's own row already carries the count where it has one
  const un = accts.some((a) => a.coldUn > 0);
  const rows = accts.map((a) => `<div class="crow${un ? " un" : ""}"><span class="cn">${acctLabel(h, a.label, a.name)}</span>`
    + `<span class="cell">${a.cold > 0 ? `${DRAW.vbar(barW(a.cold, mx.cold))}<b class="v">${n0(a.cold)}</b>` : ""}</span>`
    + `<span class="cell">${a.coldIn > 0 ? `${DRAW.vbar(barW(a.coldIn, mx.in))}<b class="v">${tok(a.coldIn)}</b><small>of ${tok(a.in)}</small>` : ""}</span>`
    + (un ? `<span class="cell um">${a.coldUn > 0 ? `<b class="v">${n0(a.coldUn)}</b>` : ""}</span>` : "")    + `</div>`).join("");
  const lead = s.coldIn > 0 ? DRAW.focal(tok(s.coldIn), `input sent again, of ${tok(s.in)}`) : "";
  return `<div class="tdetail plate${ui.coldNew === h ? " unfold" : ""}" id="cold-${esc(h)}">`
    + DRAW.section("Cold after an account switch", lead
      + `<div class="ctable"><div class="crow chead${un ? " un" : ""}"><span>Account</span><span class="cell">Cold turns</span><span class="cell">Input sent again</span>`
      + `${un ? `<span class="cell um">Not marked</span>` : ""}</div>${rows}</div>`)
    + `</div>`;
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
  return `<span class="flag"><a href="${href}">${word}${OPEN}</a>${vs}</span>`;
}

// ---------- the page ----------
/** What each project tile and the picker carry on this page: the window's tokens, written as every figure here is. */
function projectFigures() {
  const w = D.every;
  if (!w || reading() || S.sessionsPending) return { of: () => null, all: null, unit: "token", show: tok };
  const byRow = rowProjects(), m = new Map();
  for (const e of w.sessions.values()) { const k = projectOfSid(e.sid, byRow).key; m.set(k, (m.get(k) ?? 0) + tokensOf(e)); }
  return { of: (k) => m.get(k) ?? 0, all: tokensOf(w.total), unit: "token", show: tok };
}
// Each command still being read says so where its figures go: its chip with a striped bar on the chart's own head, and
// its own row in Tokens, kept with striped cells. A command that could not be read stands apart, under Not read.
function readNote() {
  const errs = D.errors.map((cmd) => S.heads.find((h) => h.command === cmd)?.key).filter(Boolean);
  const pend = [...(D.reading ?? [])].filter((h) => !errs.includes(h));
  if (reading() || (!errs.length && !pend.length)) return "";
  return `<span class="rdg">`
    + (pend.length ? `<span class="rdset">${pend.map((h) => `<span class="rd">${chip(h)}${GHOST.bar(3)}</span>`).join("")}<span class="why">Not counted yet</span></span>` : "")
    + (errs.length ? `<span class="rdset bad">${errs.map(chip).join("")}<span class="why">${NOT_READ}</span></span>` : "")
    + `</span>`;
}
const PERF_HEADS = createPerfHeads(scope, { render: () => renderBand("perf"), chip, providerOfHead, cmdOf, flagOf, flagHtml, hasFilter, ui, S, n0, secs, OPEN, OUTCOME_WORD, tagWord, isFailed, NOT_READ });

const BANDS = {
  money: { el: () => document.getElementById("band-money"), html: moneyHtml },
  chart: { el: () => document.getElementById("band-chart"), html: chartHtml },
  by: { el: () => document.getElementById("band-by"), html: byHtml },
  tok: { el: () => document.getElementById("band-tok"), html: tokensHtml },
  perf: { el: () => document.getElementById("band-perf"), html: () => PERF_HEADS.html() },
};
/** One band re-drawn on its own: a hover moves nothing else, and [grow] lets what changed grow into its new length. */
function renderBand(name, grow = false) {
  const el = BANDS[name].el();
  if (!el) return;
  el.classList.toggle("grow", Boolean(grow));
  el.innerHTML = BANDS[name].html();
  if (name === "money" || name === "tok") DRAW.paint(el, { grow: grow ? "all" : null, reading: false });
  if (name === "chart" || name === "tok") fitParts(el);
}
// The project tiles are known only once the transcripts have been listed. Until then the line holds the shape it last
// landed in, hollow: a tile's own box per tile at a wide page, the picker's chip at a narrow one. It drew nothing, and
// the whole page jumped down by the tiles' height when they arrived.
const TILES_KEY = "splice-usage-tiles";
const lastTiles = () => { const n = Number(localStorage.getItem(TILES_KEY)); return Number.isFinite(n) && n > 0 ? Math.min(n, 12) : 0; };
const ghostPick = () => {
  const n = lastTiles();
  if (!n) return "";
  return tilesWide() ? `<nav class="ptiles" aria-hidden="true">${Array.from({ length: n }, () => `<i class="ghost ptg"></i>`).join("")}</nav>`
    : `<div class="lhead" aria-hidden="true">${GHOST.chip(13)}</div>`;
};
function renderPick() {
  const line = document.getElementById("pickline");
  line.innerHTML = S.sessionsPending ? ghostPick() : pickLineHtml(S.groups, ui.project, ui.pmenu, projectFigures());
  if (!S.sessionsPending) {
    try { localStorage.setItem(TILES_KEY, String(line.querySelectorAll(".ptile").length)); } catch { /* a blocked store costs the ghost its shape and nothing else */ }
  }
  document.getElementById("win").innerHTML = DRAW.switcher(Object.entries(WINDOWS).map(([k, v]) => [k, v.label]), ui.win, "win", "Window");
}
function renderScope() {
  const el = document.getElementById("scope");
  if (el) el.innerHTML = scopeHtml();
}
function render(grow = false) {
  tick();
  renderPick();
  if (S.error) {
    root.innerHTML = `<section class="route-error" role="alert">${ICON.bang}<span><b>Usage didn't open</b> <span class="why">${esc(S.error)}</span></span>`
      + `<button class="act small" type="button" data-act="reread">Try again</button></section>`;
    return;
  }
  if (!document.getElementById("band-money")) {
    root.innerHTML = `<section class="band money" id="band-money"></section><div class="scopeline" id="scope"></div><section class="band chart" id="band-chart"></section>`
      + `<div class="lower"><section class="band by" id="band-by"></section><div class="lcol"><section class="band tokens" id="band-tok"></section><div class="band perf" id="band-perf"></div></div></div>`;
  }
  renderScope();
  for (const name of ["money", "chart", "by", "tok", "perf"]) renderBand(name, grow);
}
// the figures on screen belong to the window and filters they were read under: a new one reads again and says so, never
// drawing the old figures under the new heading
const again = () => { D.loading = true; D.scoped = null; D.all = null; D.every = null; D.gen++; ui.find = ""; render(); readWindow(); };
async function boot() { render(); await readStanding(); render(); if (!S.error) await readWindow(); }

// ---------- naming a login, in place, on the card or the row it belongs to ----------
// splice's own name for a login with a ref: any words up to 48, never an email (AccountNameStore.kt). A login without
// one is named under its command, in that provider's own grammar (AccountLabelPolicy, AccountSelection.kt:17).
function nameError(row, v) {
  if (!v) return "Needs a name";
  const taken = S.accounts.some((x) => x !== row && (x.display_name || "").toLowerCase() === v.toLowerCase());
  if (row.ref) return v.length > 48 ? "Too long" : v.includes("@") ? "Can't hold an email" : taken ? "Name taken" : null;
  const claude = providerOfHead((row.heads || [])[0]) === "claude";
  const reserved = !claude && (v === "primary" || v === "auto" || v.endsWith("-quota"));
  const shape = claude ? /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/ : /^[a-z0-9][a-z0-9._-]{0,47}$/;
  return reserved ? "That name is kept by splice" : !shape.test(v) ? (claude ? "Letters, digits, - and _ only" : "Lowercase letters, digits, . - _ only") : taken ? "Name taken" : null;
}
const namePath = (row) => {
  if (row.ref) return `/api/accounts/names/${encodeURIComponent(row.ref)}`;
  const t = row.edit_target, label = t ? t.id : row.label || "primary";
  const qs = t ? `?target_kind=${encodeURIComponent(t.kind)}&target_id=${encodeURIComponent(t.id)}` : "";
  return `/api/auth/${encodeURIComponent((row.heads || [])[0] ?? "")}/accounts/${encodeURIComponent(label)}${qs}`;
};
const renderNames = () => { renderBand("money"); renderBand("by"); };
async function saveName(idx) {
  const row = S.accounts[idx];
  if (!row || ui.nameBusy) return;
  const field = root.querySelector(".nmfield");
  const v = (field?.value ?? "").trim();
  ui.nameDraft = v;
  const err = nameError(row, v);
  if (err) { ui.nameErr = err; ui.shook = idx; renderNames(); setTimeout(() => { ui.shook = null; }, 400); return; }
  ui.nameBusy = true; ui.nameErr = null; renderNames();
  const res = row.ref ? await API.put(namePath(row), { name: v }) : await API.patch(namePath(row), { label: v });
  ui.nameBusy = false;
  if (!res.ok) { ui.nameErr = res.status === 403 ? "Not renamed: refused" : "Not renamed"; ui.shook = idx; renderNames(); setTimeout(() => { ui.shook = null; renderNames(); }, 400); return; }
  const accounts = await API.get("/api/accounts");
  if (accounts.ok) S.accounts = accounts.body?.accounts || [];
  Object.assign(ui, { naming: null, nameErr: null, nameDraft: null, landed: idx });
  renderNames();
  setTimeout(() => { ui.landed = null; renderBand("money"); }, 1000);
}
const stopNaming = () => { Object.assign(ui, { naming: null, nameErr: null, nameDraft: null }); renderNames(); };

document.addEventListener("click", (e) => {
  if (ui.pmenu && !e.target.closest(".pmenu, [data-act=projmenu]")) { ui.pmenu = false; renderPick(); }
  if (e.target.closest("a")) return; // a link opens its requests
  if (e.target.closest(".namebox")) { // the field and its acts, never the card's or the row's door under them
    const act = e.target.closest("[data-act]");
    if (act?.dataset.act === "namesave") saveName(Number(act.dataset.k));
    if (act?.dataset.act === "namestop") stopNaming();
    return;
  }
  const el = e.target.closest("[data-act]");
  if (!el) return;
  const v = el.dataset.v;
  switch (el.dataset.act) {
    case "theme": toggleTheme(); break;
    case "reread": S.error = null; boot(); break;
    case "projmenu": ui.pmenu = !ui.pmenu; renderPick(); break;
    case "pickproj": ui.project = el.dataset.p || null; ui.pmenu = false; PICK.set(ui.project); ui.by = nextBy("project") === "project" ? "account" : ui.by; ui.shown = CUT; again(); break;
    case "win": if (v === ui.win) break; ui.win = v; ui.hover = null; ui.shown = CUT; again(); break;
    case "measure": if (v === ui.measure) break; ui.measure = v; ui.hover = null; renderBand("chart", true); break;
    case "by": if (v === ui.by) break; ui.by = v; ui.shown = CUT; ui.find = ""; renderBand("by", true); break;
    case "rank": if (v === ui.rank) break; ui.rank = v; ui.shown = CUT; renderBand("by", true); break;
    case "more": ui.shown += MORE_STEP; renderBand("by", true); break;
    case "fewer": ui.shown = CUT; renderBand("by"); BANDS.by.el()?.scrollIntoView({ block: "start", behavior: "smooth" }); break;
    case "cold": { const k = el.dataset.k; if (ui.cold.has(k)) { ui.cold.delete(k); ui.coldNew = null; } else { ui.cold.add(k); ui.coldNew = k; } renderBand("tok"); ui.coldNew = null; break; }
    case "name": ui.naming = Number(el.dataset.k); ui.nameAt = el.dataset.at || "card"; ui.nameErr = null; ui.nameDraft = ""; renderNames();
      root.querySelector(".nmfield")?.focus(); break;
    case "pick": {
      const card = el.closest(".pcard, .kcard");
      if (card) ui.from = { cls: card.matches(".pcard") ? "pcard" : "kcard", v, y: document.scrollingElement.scrollTop };
      const d = el.dataset.d;
      if (d === "project") { ui.project = v; PICK.set(v); } else ui.f = { ...ui.f, [d]: v };
      ui.by = nextBy(d); ui.shown = CUT; again();
      // the narrowed view starts at the filters it is narrowed by, then its chart, then the next step down
      const scope = document.getElementById("scope");
      (scope?.childElementCount ? scope : BANDS.chart.el())?.scrollIntoView({ block: "start", behavior: "smooth" }); break;
    }
    case "back": {
      if (!ui.from) break;
      const { y, cls, v: was } = ui.from; ui.from = null; renderScope();
      document.scrollingElement.scrollTo({ top: y, behavior: "smooth" });
      [...root.querySelectorAll(`.${cls}`)].find((c) => c.dataset.v === was)?.focus({ preventScroll: true }); break;
    }
    case "clear": ui.f = {}; ui.by = "account"; ui.shown = CUT; ui.from = null; again(); break;
    case "unpick": { const f = { ...ui.f }; delete f[el.dataset.d]; ui.f = f; if (!Object.keys(f).length) { ui.by = "account"; ui.from = null; } ui.shown = CUT; again(); break; }
  }
});
// a row that is a door answers the keyboard too, since it is a button to everything but the mouse; a button, a link or a
// field inside it answers for itself
document.addEventListener("keydown", (e) => {
  if (e.target.matches?.(".nmfield")) {
    if (e.key === "Enter") { e.preventDefault(); saveName(ui.naming); }
    if (e.key === "Escape") stopNaming();
    return;
  }
  if (e.key !== "Enter" && e.key !== " ") return;
  if (e.target.closest?.("button, a, input, textarea, select")) return;
  const el = e.target.closest?.("[role=button][data-act]");
  if (!el) return;
  e.preventDefault(); el.click();
});
root.addEventListener("input", (e) => {
  if (!e.target.matches(".bfind")) return;
  const at = e.target.selectionStart;
  ui.find = e.target.value; ui.shown = CUT;
  renderBand("by");
  const f = root.querySelector(".bfind");
  if (f) { f.focus(); try { f.setSelectionRange(at, at); } catch { /* a search field may refuse a range */ } }
});
// the pointer over a column opens its tip; anywhere else but the tip closes it
root.addEventListener("mouseover", (e) => {
  if (e.target.closest?.("#band-chart .tip")) return;
  const c = e.target.closest?.("#band-chart .cbar[data-i]"), i = c ? Number(c.dataset.i) : null;
  if (i === ui.hover) return;
  ui.hover = i; renderBand("chart");
});
root.addEventListener("mouseleave", () => { if (ui.hover != null) { ui.hover = null; renderBand("chart"); } });
// the board's columns, the project tiles and which labels fit inside their bars are the page's own width, so a resized
// window is drawn again for it
let sized = null;
addEventListener("resize", () => {
  clearTimeout(sized);
  sized = setTimeout(() => { if (ui.naming == null) { renderPick(); renderBand("money"); } renderBand("chart"); renderBand("tok"); }, 200);
});
// a door from elsewhere opens the page on a window and a filter: usage.html?account=head|label&by=project
{
  const A = new URLSearchParams(location.search);
  if (WINDOWS[A.get("win")]) ui.win = A.get("win");
  if (A.get("account")) ui.f.account = A.get("account");
  if (A.get("model")) ui.f.model = A.get("model");
  if (A.get("agent")) ui.f.agent = A.get("agent");
  if (DIMS[A.get("by")]) ui.by = A.get("by"); else if (hasFilter()) ui.by = nextBy(Object.keys(ui.f).at(-1));
}
boot();

});
