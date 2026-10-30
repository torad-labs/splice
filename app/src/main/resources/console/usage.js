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
// PLANS AND KEYS NEVER MEET IN ONE MONEY FIGURE. A plan's requests read as their value at API prices,
// drawn hatched, never as money spent; only a key's requests are money. The two sit side by side, in the chart's Cost
// columns and in Where it went's lead, and are never added. A figure nobody reported is a dash, never a zero.
//
// THE DRAWING. Every quantity is drawn before it is written: a plan's windows are the kit's meters with their reset
// rings, what splice sent through each login is bar cells on scales the whole board shares, the window is columns whose
// parts carry their command where they have room, with each column's failed share under it, and every table row is one
// line of bars ending on one column. A qualifier is said once, on the band's head or in its legend. Each band is
// re-drawn on its own, and a value that changes moves from its last length to its new one.
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
const WINDOWS = { "5h": { label: "5 hours" }, "7d": { label: "7 days" }, "30d": { label: "30 days" } };
// the calendar days a day-column window holds
const DAYS = { "7d": 7, "30d": 30 };
const SEP = "\u001f"; // one account can carry several labels (its email, its selector name); a merged key joins them after the head
const MAX_HELD = 2000; // the most rows one read of a command carries (PerfRoutes.kt MAX_TURNS)
const LEN = { "5 hours": 300, Week: 10080, Month: 43200 };
const FAMILY = PROVIDER_FAMILY;
const NOT_READ = "Not read";
const EYE = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>';
const OPEN = DRAW.go;
const UP = G('<path d="M12 19V5M6 11l6-6 6 6"/>', 'class="go" aria-hidden="true"');
const DOWN = G('<path d="M12 5v14M6 13l6 6 6-6"/>', 'class="go" aria-hidden="true"');
const CUT = 15;       // the rows a list shows before its tail folds into one row
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

const ui = { win: "7d", measure: "tokens", by: "account", rank: "tokens", f: {}, shown: CUT, from: null, project: PICK.get(), pmenu: false,
  find: "", cold: new Set(), coldNew: null, naming: null, nameAt: "card", nameErr: null, nameDraft: "", nameBusy: false, landed: null, shook: null, signing: {}, signShook: null };
const root = document.getElementById("usage");

// ---------- what splice has told this page ----------
const S = { heads: [], providerOf: {}, labels: {}, declared: new Map(), sessionRows: [], liveHead: {}, sessions: {}, sessionsPending: true, keys: [], accounts: [], registry: [], groups: [], stood: false, error: null };
// one window's reads: the totals the daemon folded, and the newest rows. [all] is the window with the project pick alone
// (the plan and key cards), [scoped] the same under the filters opened below the cards.
const D = { loading: true, stale: false, pending: false, every: null, all: null, scoped: null, rows: [], held: new Set(), errors: [], gen: 0 };

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
    for (const m of row.models || []) if (m.id && !S.declared.has(modelKey(m.id))) S.declared.set(modelKey(m.id), m.id);
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
    S.sessionRows = rows;
    for (const [sid, t] of Object.entries(turns)) S.liveHead[String(sid).slice(0, 8)] = t.head;
    S.sessionsPending = false;
    S.groups = projectGroups(rows, turns, S.heads);
    const before = ui.project;
    ui.project = reconcilePick(ui.project, S.groups);
    if (before !== ui.project) again(); else render(true);
  });
}

// ---------- the daemon's questions, one per command ----------
// the window's columns: twenty quarter hours ending at the next quarter, or the calendar days (7 or 30) ending today
function slices() {
  if (ui.win === "5h") {
    const end = Math.ceil(+NOW / 900e3) * 900e3;
    return Array.from({ length: 20 }, (_, i) => { const to = end - (19 - i) * 900e3; return { from: to - 900e3, to, label: new Date(to - 900e3).getMinutes() === 0 ? hourWord(new Date(to - 900e3)) : "", now: i === 19 }; });
  }
  const n = DAYS[ui.win];
  return Array.from({ length: n }, (_, i) => {
    const d = new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() - (n - 1 - i));
    const next = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1);
    // a month of columns is named by its date, and by its month where the month begins; a week by its weekdays
    const label = n > 7 ? (d.getDate() === 1 || i === 0 ? d.toLocaleDateString("en-US", { month: "short", day: "numeric" }) : String(d.getDate())) : i === n - 1 ? "Today" : DAY[d.getDay()];
    return { from: +d, to: +next, day: d.toLocaleDateString("en-CA"), label, now: i === n - 1 };
  });
}
const since = () => slices()[0].from;
function filterParams(p, f, label, model) {
  if (label != null) p.set("account", label);
  if (model ?? f.model) p.set("model", model ?? f.model);
  if (f.agent) p.set("session", String(f.agent).slice(0, 8));
  // No project is a pick of its own: the daemon reads it as project=none (TurnsFilter.kt), the way Requests asks. Sent
  // as nothing, it would show every project's figures under its name.
  if (ui.project && !f.wide) p.set("project", ui.project === NO_PROJECT_KEY ? "none" : ui.project);
  return p;
}
// each 15-minute column of the 5-hour window is one more question on the same read (`ask`), so the whole window is read once
function turnsUrl(head, held, f, label, model) {
  const p = filterParams(new URLSearchParams({ head: head.key, since: String(since()), time_zone: ZONE, n: String(held), local: "0" }), f, label, model);
  if (held > 1) p.set("usage", "0");
  else if (ui.win === "5h") for (const sl of slices()) p.append("ask", filterParams(new URLSearchParams({ n: "1", local: "0", from: String(sl.from + 1), until: String(sl.to) }), f, label, model).toString());
  return `/api/perf/turns?${p}`;
}
/** The labels one account filter reads: the daemon matches one label exactly, so an account with several is asked once per label and summed. */
const labelsOf = (f) => (f.account ? f.account.split("|").slice(1).join("|").split(SEP) : [null]);
/** The daemon keeps a request when its model is the id asked, exactly, and requests carry the id with the date or window a vendor
 *  stamps on it. A model filter named without them (an address from Accounts) asks for every id the window holds under that
 *  name, each one read and summed; a name that is an id the window holds, or that none holds, is asked as it stands. */
const bareModel = (id) => modelKey(id).replace(DATED, "");
function modelIdsOf(name, held) {
  const ids = [...held];
  if (ids.includes(name)) return [name];
  const same = ids.filter((id) => bareModel(id) === bareModel(name));
  return same.length ? same : [name];
}
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
  const open = {}, read = new Map();
  const scopes = [...(hasFilter() ? [ui.f, open] : [open]), ...(ui.project ? [wide] : [])];
  const get = (a) => { const url = turnsUrl(a.h, a.held, a.f, a.label, a.model); if (!read.has(url)) read.set(url, API.getReady(url)); return read.get(url); };
  // a model asked by its name is asked by every id the window holds under it: the unfiltered read says which
  let ids = null;
  if (ui.f.model) {
    const early = headsFor(open).map((h) => ({ f: open, h, held: 1, label: null }));
    const seen = foldWindow(await Promise.all(early.map(get)), early.map((a) => a.h)).models;
    if (gen !== D.gen) return;
    ids = modelIdsOf(ui.f.model, seen.keys());
  }
  D.modelIds = ids;
  const modelsOf = (f) => (f.model && ids ? ids : [null]);
  const asks = scopes.flatMap((f) => headsFor(f).flatMap((h) => labelsOf(f).flatMap((label) => modelsOf(f).map((model) => ({ f, h, held: 1, label, model })))));
  const rowAsks = headsFor(ui.f).flatMap((h) => labelsOf(ui.f).flatMap((label) => modelsOf(ui.f).map((model) => ({ f: ui.f, h, held: MAX_HELD, label, model }))));
  // Count first. The multi-megabyte trails fill in after the totals and chart are already usable.
  const totals = await Promise.all(asks.map(get));
  if (gen !== D.gen) return;
  const split = (scope) => { const idx = asks.map((a, i) => (a.f === scope ? i : -1)).filter((i) => i >= 0); return foldWindow(idx.map((i) => totals[i]), idx.map((i) => asks[i].h)); };
  D.scoped = split(scopes[0]);
  D.all = hasFilter() ? split(scopes[1]) : D.scoped;
  D.every = ui.project ? split(wide) : D.all;
  D.rows = []; D.held = new Set(); D.errors = [...D.scoped.errors];
  let pending = D.scoped.pending || D.all.pending || D.every.pending;
  const reading = new Set([...D.scoped.reading, ...D.all.reading, ...D.every.reading]);
  D.pending = pending; D.reading = reading; D.loading = false; D.stale = false; markStale();
  // the band moves with whatever above it changed height in the same landing; the rows travel only for their own change of place
  const bandTop = () => (document.getElementById("band-by")?.getBoundingClientRect().top ?? 0) + scrollY, topWas = bandTop();
  landing = true; render(true); landing = false;
  const shift = bandTop() - topWas;
  if (Number.isFinite(shift) && Math.abs(shift) > 0.5) for (const [place, at] of TWF) if (place.includes("|by-")) TWF.set(place, { x: at.x, y: at.y + shift });
  const rows = await Promise.all(rowAsks.map(get));
  if (gen !== D.gen) return;
  rowAsks.forEach((a, i) => {
    const answer = (rows[i].body?.heads || []).find((x) => x.key === a.h.key);
    if (!rows[i].ok || !answer || answer.error) { console.warn(`${a.h.command}: ${answer?.error ?? rows[i].status}`); if (!D.errors.includes(a.h.command)) D.errors.push(a.h.command); return; }
    if (answer.read_pending) { pending = true; reading.add(a.h.key); return; }
    D.held.add(a.h.key);
    for (const row of answer.rows || []) D.rows.push(mapRow(a.h, row));
  });
  if (rowAsks.length > headsFor(ui.f).length) { // several labels or model ids: keep the newest MAX_HELD of what they returned together, so the trail's axis is one reading
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
// (unanswered_requests, TurnPriceGap), so a group of nothing else reads $0, never "No price"
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
/** A row's own commands, in the console's quiet chip: a list where the chip repeats row after row keeps it under the
 *  name it rides on, while a group's head keeps the full chip. */
const rowChipsOf = (heads) => [...heads].map((h) => DRAW.chip({ text: cmdOf(h), cls: "quiet", data: ` style="--c:${colorOf(providerOfHead(h))}"` })).join("");
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
/** What a login is called, by the console's one rule (kit DRAW.name): the name he gave it, else "Account", with
 *  two that read alike told apart by a muted number in the roster's order. An address or an id is not a name, so it is not
 *  drawn as one. */
let nameBook = null;
const loginFacts = (a, fallback) => ({ name: a.ref ? a.display_name : a.display_name || fallback, provider: providerOfHead((a.heads || [])[0]), ids: DRAW.loginIds(a),
  plan: a.plan ? a.plan.charAt(0).toUpperCase() + a.plan.slice(1) : "" });
function planNamed(row, fallback) {
  if (nameBook?.src !== S.accounts) {
    const plans = S.accounts.filter((a) => a.kind !== "api-key"), named = DRAW.names("login", plans.map((a) => loginFacts(a)));
    nameBook = { src: S.accounts, by: new Map(plans.map((a, i) => [a, named[i]])) };
  }
  return nameBook.by.get(row) ?? DRAW.name("login", loginFacts(row, fallback));
}
const planName = (row, fallback) => planNamed(row, fallback).text;
/** The same name as markup, its ordinal muted. */
const planNameHtml = (row, fallback) => DRAW.nameHtml(planNamed(row, fallback));
/** A key by its variable and the day splice first saw it, in the same words on its card, its rows, its filter chip and
 *  its cold plate: a key that replaced another carries the same variable, and the day is what tells the two apart. */
const keyWord = (name, seen) => `<span class="var">${esc(name)}</span>${seen ? ` · ${dayWord(new Date(seen * 1000))}` : ""}`;
/** An account in a row, a chip or a detail: a plan by its name, a key by its variable and day. A login the roster no
 *  longer holds reads by the number the daemon kept for it, and by the one name wherever it stands: its card on the board,
 *  its row, the filter it sets. */
function acctLabel(head, label, name) {
  const plan = planRowOf(head, label);
  if (plan) return planNameHtml(plan, label);
  const key = keyRecord(label);
  if (key) return keyWord(key.name, key.seen);
  return DRAW.nameHtml(goneNamed(head, label, name));
}
/** What a login splice carried requests for but the roster no longer holds is called: the name its owner gave it, else "Account"
 *  numbered on after the daemon's listed logins, in the order of the refs that identify them (so each keeps its number between
 *  reads). No "(signed out)" is added, since its card and its rows say it is no longer in splice, and no number the daemon kept for
 *  it is drawn, since it would read as one of the roster's. */
let goneBook = null;
// what a login splice carried requests for but no longer holds says it is, on its card and its row: never Signed out, which
// is a login of the roster to sign in again (lead look at the installed console, Oct 29)
const GONE_WORD = "No longer in splice";
function goneOrder() {
  const src = D.all ?? null;
  if (goneBook?.src !== src || goneBook.size !== DRAW.rosterSize()) {
    const keys = [...new Set(goneLogins().filter((g) => !DRAW.ownName(g.name)).map((g) => acctKeyOf(g.head, g.label)))].sort();
    goneBook = { src, size: DRAW.rosterSize(), keys };
  }
  return goneBook;
}
function goneNamed(head, label, name) {
  const n = DRAW.name("login", { name: name ?? label, provider: providerOfHead(head) });
  if (n.own) return n;
  const { keys, size } = goneOrder(), at = keys.indexOf(acctKeyOf(head, label));
  if (at < 0 || size + keys.length < 2) return { text: n.base, base: n.base, ord: 0, own: false };
  return { text: `${n.base} ${size + at + 1}`, base: n.base, ord: size + at + 1, own: false };
}
const goneName = (head, label, name) => goneNamed(head, label, name).text;
/** The logins the daemon read requests for that no roster row holds (an 8-hex id with no login), in the window's order,
 *  and as last seen while a window is being read, so the board keeps their cards between reads. */
const GONE_KEY = "splice-usage-gone";
function goneLogins() {
  if (D.all) {
    const now = [...D.all.accounts.values()].filter((e) => isPlanHead(e.head) && REF_SHAPE.test(String(e.label)) && !planRowOf(e.head, e.label))
      .map((e) => ({ head: e.head, label: e.label, name: e.name }));
    try { localStorage.setItem(GONE_KEY, JSON.stringify(now)); } catch { /* a blocked store costs the next first paint one jump */ }
    return now;
  }
  try {
    const kept = JSON.parse(localStorage.getItem(GONE_KEY) || "[]");
    return Array.isArray(kept) ? kept.filter((g) => g && typeof g.head === "string" && S.heads.some((h) => h.key === g.head)) : [];
  } catch { return []; }
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
// A card with no window read keeps its meters as dashed empty tracks (.bar.gone), so it keeps the shape of its neighbours.
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

// the three things a card's footer counts, each by its own mark: the group's key names them once, so no card repeats a word
const MARK = {
  spent: `<i class="upb-mk sp" aria-hidden="true">$</i>`,
  tok: `<i class="upb-mk tk" aria-hidden="true"><svg viewBox="0 0 16 16"><rect x="2" y="2.4" width="12" height="2.8" rx="1.4"/><rect x="2" y="6.6" width="8.4" height="2.8" rx="1.4"/><rect x="2" y="10.8" width="5" height="2.8" rx="1.4"/></svg></i>`,
  req: `<i class="upb-mk rq" aria-hidden="true"><svg viewBox="0 0 16 16"><path d="M4 12 12 4M6 4h6v6"/></svg></i>`,
};
const GONE_TRACK = `<span class="vbar gone" aria-hidden="true"></span>`;
/** One row of a card's footer: its mark, its bar on the board's shared scale, its figure ending on the board's one edge. A row
 *  with no figure to draw keeps its dashed empty track; the reason a money row has no figure stands in the row's own label. */
const thruRow = (kind, track, fig, label = "", title = "") => `<div class="upb-r"${title ? ` title="${esc(title)}"` : ""}>${MARK[kind]}${label ? `<span class="lab">${esc(label)}</span>` : ""}${track}<b class="v"${fig && fig !== BARE_DASH ? ` data-tn=""` : ""}>${fig}</b></div>`;
/** What splice itself sent through a login in the window: bars on scales the whole board shares, so the busiest login is
 *  found before a digit is read. Tokens is one bar whose lighter part is what was read from the cache, so a cache that
 *  saves little shows as a bar that is mostly dark. While the window reads each row keeps its track, striped. A login with
 *  nothing in the window keeps all its rows as empty tracks and says so once, on the card. A figure nobody reported is a
 *  dashed track and a dash; a key whose money has no figure says why in the row's label, never inside the figure's cell. */
function thruCells(s, mx, isKey) {
  const rows = isKey ? 3 : 2;
  if (reading()) return `<footer class="thru upb-thru">${Array.from({ length: rows }, () => GHOST.row([GHOST.bar(1.3), GHOST.track(), GHOST.bar(3.2)])).join("")}</footer>`;
  // a card nothing was sent through says so once above and keeps the height its rows would take, with nothing drawn in it
  if (!s.requests) return `<footer class="thru upb-thru upb-held" style="--k:${isKey ? 3 : 2}"></footer>`;
  const cells = [];
  if (isKey) {
    const spent = spentOf(s), why = noCostWhy(s);
    cells.push(spent == null ? thruRow("spent", GONE_TRACK, BARE_DASH, why === "Not reported" ? "Not priced" : why, "Not priced") : thruRow("spent", DRAW.vbar(barW(spent, mx.spend)), usd(spent)));
  }
  if (noTokens(s)) cells.push(thruRow("tok", GONE_TRACK, BARE_DASH));
  else {
    const total = tokensOf(s), cached = s.noIn < s.requests && total > 0 ? Math.min(100, (s.cached / total) * 100) : 0;
    if (cached) splitDrawn = true;
    cells.push(thruRow("tok", DRAW.vbar(barW(total, mx.tok), "", cached), tok(total), "", cached ? `${tok(s.cached)} of ${tok(total)} read from the cache` : ""));
  }
  cells.push(thruRow("req", DRAW.vbar(barW(s.requests, mx.req)), n0(s.requests)));
  return `<footer class="thru upb-thru">${cells.join("")}</footer>`;
}
/** The line a card with nothing counted in the window says once, on itself. */
const noReq = (s) => (!reading() && !s.requests ? `<p class="upb-nores">No requests</p>` : "");
/** What a card leads with, at 1.7rem on its own baseline: a plan with a window read, its highest used share by level with
 *  that window's name beside it; a plan with no limits to read, a signed-out login and a key, what splice counted (a key its
 *  spend first); a card with nothing to report leads with its name and draws no figure. */
function leadFig(s, windows, { key = false, out = false } = {}) {
  const hi = !key && !out && windows.length ? windows.filter((w) => !w.sub).reduce((a, w) => (a == null || w.used > a.used ? w : a), null) : null;
  if (hi) return `<p class="upb-fig${hi.used >= 100 ? " lim" : hi.used >= 80 ? " lvl" : ""}"><b data-tn="">${hi.used}%</b><span class="q">${esc(hi.label)} used</span></p>`;
  if (reading()) return `<p class="upb-fig">${GHOST.bar(6)}</p>`;
  if (!s.requests) return "";
  const spent = key ? spentOf(s) : null;
  if (spent != null) return `<p class="upb-fig">${MARK.spent}<b data-tn="">${usd(spent)}</b></p>`;
  return noTokens(s) ? "" : `<p class="upb-fig">${MARK.tok}<b data-tn="">${tok(tokensOf(s))}</b></p>`;
}
/** The name of a login with no name: "Account", and the act that gives it one, in place, wherever it was pressed (its
 *  card or its row in Where it went). */
function nameSlot(row, idx, at) {
  if (ui.naming === idx && ui.nameAt === at) {
    return `<span class="namebox${ui.shook === idx ? " shook" : ""}"><input class="field nmfield" type="text" maxlength="48" aria-label="Name this account" value="${esc(ui.nameDraft ?? "")}" placeholder="Name">`
      + DRAW.act({ word: "Save", act: "namesave", cls: `small primary${ui.shook === idx ? " shook" : ""}`, busy: ui.nameBusy, data: ` data-k="${idx}"` })
      + DRAW.act({ word: "Cancel", act: "namestop", cls: "small quiet" })
      + `${ui.nameErr ? DRAW.result(ui.nameErr, "bad") : ""}</span>`;
  }
  return `<span class="nm">${planNameHtml(row)}</span>`
    + DRAW.act({ word: "Name it", act: "name", cls: `small${ui.shook === idx ? " shook" : ""}`, data: ` data-k="${idx}" data-at="${at}"` });
}

/** A card's height in rem, from what it is built of and never from its figures, so the board's columns are chosen the
 *  same way whatever the window says: the figure it leads with, its name, its window lines (or the one line that stands for
 *  them), the lines it carries over them (a limit, the request that spent the window) and its footer of bars, which is one
 *  height whether the login sent anything or not. The numbers are this card as it draws. */
const cardH = (kind, rows, out, lines = 0, s = null) => {
  const foot = kind === "key" ? 7 : 5.14;
  const body = kind === "key" ? 1.9 : out ? 3.35 : rows > 0 ? 2.345 * rows : 1.9;
  const lead = kind === "key" || rows > 0 || out ? 2.9 : 0;
  return 4.9 + body + foot + lead + 1.9 * lines + (s && !s.requests ? 1.9 : 0);
};

// the logins at their limit as the last board drew them (null before the first draw), so a lock pops in only on a login
// that has just reached its limit
let heldKnown = null, heldNow = new Set();
/** The windows a login reports, as its card draws them. */
const windowsOf = (row) => [windowOf(row.five_hour_used_percent, row.five_hour_reset_epoch_seconds, row.five_hour_window_seconds, "short", row.five_hour_current),
  windowOf(row.seven_day_used_percent, row.seven_day_reset_epoch_seconds, row.seven_day_window_seconds, "long", row.seven_day_current)].filter(Boolean);
/** A login with a window at its limit: its row in Where it went carries the lock its card has. */
const heldRow = (row) => Boolean(row) && !signedOut(row) && windowsOf(row).some((w) => w.used >= 100);
function planCard(row, mx) {
  const windows = windowsOf(row);
  const idx = accountIdx(row), out = signedOut(row), heads = row.heads || [];
  const mine = usageOfPlan(row), s = sumOf(mine), held = windows.some((w) => w.used >= 100);
  if (held) heldNow.add(idx);
  const rows = [];
  for (const w of windows) {
    rows.push(winRow(w));
    if (w.label === "Week" || w.label === "Month") for (const m of modelWindows(row)) rows.push(winRow(m));
  }
  const stale = windows.some((w) => w.stale) && row.observed_at_epoch_seconds ? row.observed_at_epoch_seconds * 1000 : null;
  if (stale) rows.push(`<span class="stale-at">${EYE}Last read ${backWord(new Date(stale))}</span>`);
  // a held login says so once, with the lock, over the bars it holds
  const until = held ? windows.filter((w) => w.used >= 100 && w.resetMs).sort((a, b) => b.resetMs - a.resetMs)[0] : null;
  // a provider that answered with no limits says so once, in one muted line with no track beside it; a login with no
  // window read at all is two dashed tracks and their dashes, which say it without a word
  const body = out ? signInBox(`plan-${idx}`)
    : rows.length ? `<div class="windows">${rows.join("")}</div>`
      : row.no_usage_at_epoch_seconds ? `<p class="nolimit">No limits reported</p>`
        : `<div class="windows">${unreadRow("5 hours")}${unreadRow("Week")}</div>`;
  const ranOut = reading() ? null : D.rows.filter((r) => r.outcome === "error:plan-limit" && r.reset && r.reset - r.ts > 5 * HOUR && mine.some((e) => e.head === r.head && e.label === r.account)).sort((a, b) => a.ts - b.ts)[0];
  const lead = mine.length ? mine[0] : null;
  const door = lead ? ` data-act="pick" data-d="account" data-v="${esc(acctKeyOfAll(mine))}" role="button" tabindex="0" aria-label="${esc(`${planName(row) || "Account"}: where its requests went`)}"` : "";
  const pk = pickedAccount(), picked = Boolean(pk && heads.includes(pk.head) && pk.labels.some((l) => planRowOf(pk.head, l) === row));
  // a login with no window read still draws its unread rows (two, or the one line that says it has no limits)
  const lines = out ? 0 : rows.length || (row.no_usage_at_epoch_seconds ? 0 : 2);
  const ran = ranOut ? `<a class="ranout" href="requests.html?win=7d#${encodeURIComponent(ranOut.id)}">Ran out ${dayClock(new Date(ranOut.ts))}${OPEN}</a>` : "";
  const fig = leadFig(s, windows, { out });
  return { prov: providerOfHead(heads[0]), kind: "plan", name: planName(row) || "Account", used: out || !windows.length ? -1 : Math.max(0, ...windows.filter((w) => !w.sub).map((w) => w.used)), s, heads: new Set(heads),
    rows: lines, h: cardH("plan", lines, out, held || ran ? 1 : 0, s),
    make: (withChips) => `<article class="card pcard${held ? " held" : ""}${out ? " out" : ""}${picked ? " picked" : ""}${ui.landed === idx ? " landed" : ""}" data-a="plan-${idx}" style="--c:${colorOf(providerOfHead(heads[0]))}"${door}>`
      + topRow(fig, headEnd(withChips ? heads : [], out))
      + `<header>${canName(row) ? nameSlot(row, idx, "card") : `<span class="nm">${planNameHtml(row)}</span>`}`
      + `${row.plan && !planName(row).toLowerCase().includes(row.plan.toLowerCase()) ? `<span class="pv">${esc(row.plan.charAt(0).toUpperCase() + row.plan.slice(1))}</span>` : ""}</header>`
      // the lock pops in over the kit's pop time only on a login that has just reached its limit, never on a redraw
      + (held ? `<p class="heldline">${DRAW.lock(until?.resetMs ? `Limit reached until ${backWord(new Date(until.resetMs))}` : "Limit reached", { pop: heldKnown != null && !heldKnown.has(idx) })}${ran}</p>` : ran ? `<p class="heldline">${ran}</p>` : "")
      + body + noReq(s)
      + thruCells(s, mx, false) + (lead ? `<span class="drill" aria-hidden="true">${DRAW.go}</span>` : "") + `</article>` };
}
/** The top line of a card: the figure it leads with at the start, and at the end the commands that reach it where the group
 *  does not say them once, and a signed-out login's state. A card with neither has no top line. */
const topRow = (fig, end) => (fig || end ? `<div class="upb-top">${fig}${end}</div>` : "");
/** Where a signed-out login's bars would be: the one way back in, as the card's own button, which works on the card (signStart). */
function signInBox(target) {
  const g = ui.signing[target];
  const data = ` data-t="${esc(target)}"`;
  if (!g) return `<div class="outbox"><button class="act primary" type="button" data-act="signin"${data}>Sign in</button></div>`;
  const stop = DRAW.act({ word: "Cancel", act: "signstop", cls: "small quiet", data });
  if (g.state === "fail") {
    return `<div class="upb-login bad${ui.signShook === target ? " shook" : ""}"><p class="run why">Not completed</p>${g.why ? `<p class="upb-why">${esc(g.why)}</p>` : ""}`
      + `<div class="upb-loginrow">${DRAW.act({ word: "Try again", act: "signin", cls: "small primary", data })}${stop}</div></div>`;
  }
  return `<div class="upb-login"><p class="run"><i class="upb-spin" aria-hidden="true"></i>Signing in</p>`
    + (g.code ? `<button class="upb-code" type="button" data-act="signcopy"${data} aria-label="Copy the code">${esc(g.code)}</button><p class="upb-hint">Enter this code on the sign-in page</p>` : "")
    + `<div class="upb-loginrow">${g.url ? DRAW.act({ word: "Open sign-in", act: "signopen", cls: "small", data }) : ""}`
    + (g.by === "paste" ? `<input class="field upb-codein" type="text"${data} placeholder="Paste code" aria-label="Paste code" autocomplete="off" spellcheck="false">` : "")
    + `${stop}</div></div>`;
}
/** The end of a card's header: the commands that reach it where the group does not say them once, and a signed-out
 *  login's state, as Accounts puts it, at the right. */
const headEnd = (heads, out) => (heads.length || out
  ? `<span class="hend">${heads.length ? `<span class="cmds">${heads.map(chip).join("")}</span>` : ""}${out ? `<span class="state mute">Signed out</span>` : ""}</span>` : "");

/** A login splice carried requests for that the roster no longer holds: its card stands on the board under the name its row
 *  in Where it went has, so a row finds its card. It has no windows to draw, so its card is its name, the way back in and
 *  what splice sent through it. */
function goneCard(g, mx) {
  const label = acctLabel(g.head, g.label, g.name), key = acctKeyOf(g.head, g.label);
  const mine = D.all ? [D.all.accounts.get(key)].filter(Boolean) : [], s = sumOf(mine);
  const door = mine.length ? ` data-act="pick" data-d="account" data-v="${esc(key)}" role="button" tabindex="0" aria-label="${esc(`${plain(label)}: where its requests went`)}"` : "";
  const pk = pickedAccount(), picked = Boolean(pk && pk.head === g.head && pk.labels.includes(g.label));
  return { prov: providerOfHead(g.head), kind: "plan", gone: true, name: plain(label), used: -1, s, heads: new Set([g.head]), rows: 0, h: cardH("plan", 0, true, 0, s),
    make: (withChips) => `<article class="card pcard out gone${picked ? " picked" : ""}" data-a="gone-${esc(key)}" style="--c:${colorOf(providerOfHead(g.head))}"${door}>`
      + topRow(leadFig(s, [], { out: true }), headEnd(withChips ? [g.head] : [], false))
      + `<header><span class="nm">${label}</span></header>`
      // where a signed-out login has its Sign in, the plain fact: splice holds this login no more, so there is nothing to sign
      // in again here, and what it sent stays below (lead look at the installed console, Oct 29)
      + `<div class="outbox"><span class="state mute">${GONE_WORD}</span></div>` + noReq(s) + thruCells(s, mx, false) + (mine.length ? `<span class="drill" aria-hidden="true">${DRAW.go}</span>` : "") + `</article>` };
}

function keyCard(k, print, seen, replaced, mx) {
  const heads = (k.heads || []).map((x) => x.head).filter(isKeyHead);
  const label = `${k.name}:${print}`;
  const mine = !D.all ? [] : [...D.all.accounts.values()].filter((e) => heads.includes(e.head) && e.label === label), s = sumOf(mine), lead = mine[0];
  const door = s.requests && !reading() ? ` data-act="pick" data-d="account" data-v="${esc(acctKeyOf(lead.head, lead.label))}" role="button" tabindex="0" aria-label="${esc(`${k.name}: where its requests went`)}"` : "";
  const pk = pickedAccount(), picked = Boolean(pk && heads.includes(pk.head) && pk.labels.includes(label));
  return { prov: providerOfHead(heads[0]), kind: "key", name: k.name, used: -1, s, heads: new Set(heads), rows: -1, h: cardH("key", 0, false, 0, s),
    make: (withChips) => `<article class="card kcard${picked ? " picked" : ""}" data-a="key-${esc(label)}" style="--c:${colorOf(providerOfHead(heads[0]))}"${door}>`
      + topRow(leadFig(s, [], { key: true }), headEnd(withChips ? [...new Set(heads)] : [], false))
      + `<header><span class="nm">${keyWord(k.name, seen)}</span>${replaced ? `<span class="pv">Replaced</span>` : ""}</header>`
      // a key's provider sends no plan window: its limits read as not reported, never a zero (spec section 11)
      + `<p class="nolimit">Limits not reported by provider</p>` + noReq(s)
      + thruCells(s, mx, true) + (door ? `<span class="drill" aria-hidden="true">${DRAW.go}</span>` : "") + `</article>` };
}

/** The board: every plan login and every key, each a card, grouped under its provider's name and dot, the group with
 *  the most logins first. Inside a group the login nearest its limit leads, so what is about to hurt reads first. A
 *  command every card of a group shares is said once, on the group's head; a group of one card keeps it on the card. */
function moneyHtml() {
  if (!S.stood) return boardHead() + ghostBoard();
  if (!S.accounts.length && !S.keys.length) {
    return boardHead() + DRAW.masonry([`<a class="add" href="accounts.html"><span class="plus" aria-hidden="true">+</span><span>Add account</span></a>`], 38, { cls: "plans" });
  }
  const plans = S.accounts.filter((row) => (row.heads || []).some(isPlanHead) && !(row.login_place && !row.credential_present && !row.observed_at_epoch_seconds));
  const keyRows = [];
  for (const k of S.keys) {
    if (!(k.heads || []).map((x) => x.head).filter(isKeyHead).length) continue;
    for (const [print, seen, replaced] of [[k.fingerprint, k.first_seen_epoch_seconds, false], ...(k.replaced || []).map((r) => [r.fingerprint, r.first_seen_epoch_seconds, true])]) keyRows.push({ k, print, seen, replaced });
  }
  const gone = goneLogins();
  const sums = [...plans.map((row) => sumOf(usageOfPlan(row))),
    ...keyRows.map(({ k, print }) => sumOf(!D.all ? [] : [...D.all.accounts.values()].filter((e) => (k.heads || []).map((x) => x.head).includes(e.head) && e.label === `${k.name}:${print}`))),
    ...gone.map((g) => sumOf(!D.all ? [] : [D.all.accounts.get(acctKeyOf(g.head, g.label))].filter(Boolean)))];
  const mx = { tok: Math.max(1, ...sums.map(tokensOf)), req: Math.max(1, ...sums.map((s) => s.requests)), spend: Math.max(1e-9, ...sums.map((s) => spentOf(s) ?? 0)) };
  if (!reading()) splitDrawn = false;
  heldNow = new Set();
  const cards = [...plans.map((row) => planCard(row, mx)), ...gone.map((g) => goneCard(g, mx)), ...keyRows.map(({ k, print, seen, replaced }) => keyCard(k, print, seen, replaced, mx))];
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
  // a card's html is made above, against the logins the last board held; this board is the next one's last
  heldKnown = heldNow;
  keepShape(ordered);
  return boardHead(cards) + DRAW.masonry(planBoard(ordered, boardCols()).map(groupHtml), 38, { cls: "plans" });
}
/** One column of a provider's cards, as a block of the balanced board. Every column of a provider is headed by its dot and
 *  name, so no column starts headless; the first also carries the commands that reach the whole group and the key to the
 *  footer's marks in words, and each next column carries the same marks alone. */
const groupHtml = (it) => {
  const g = it.g, keyed = g.cards.some((c) => c.kind === "key"), word = (w) => (it.first ? `<span>${w}</span>` : "");
  const keys = `<span class="upb-keys">${keyed ? `<span class="upb-kk">${MARK.spent}${word("Spent")}</span>` : ""}<span class="upb-kk">${MARK.tok}${word("Tokens")}</span>`
    + `${it.first && splitDrawn ? `<span class="upb-kk"><i class="sw cached" aria-hidden="true"></i><span>Cached</span></span>` : ""}<span class="upb-kk">${MARK.req}${word("Requests")}</span></span>`;
  return `<section class="ugroup" data-cols="1" style="--c:${colorOf(g.prov)}"><h3><i class="blot" aria-hidden="true"></i>${esc(g.name)}${it.first && g.chips ? `<span class="cmds">${g.chips}</span>` : ""}${keys}</h3>`
    + `<div class="gcol">${it.col.map((card) => card.html).join("")}</div></section>`;
};
// whether the last read drew a cache share inside a card's tokens bar, so the key names the lighter part only then
let splitDrawn = false;
/** The band's head: how many of the plan logins stand at their limit, with each login's highest used share as one rung of a
 *  ladder (the one nearest its limit first), so the board's answer is read before a card is. A login whose limits splice
 *  has not read is a dashed rung. Before the first read it is the same shape in stripes. */
const boardHead = (cards = null) => {
  if (!cards) return `<div class="bandhead upb-head"><div class="upb-lead"><h2>Plans and keys</h2><p class="upb-hero">${GHOST.bar(9)}</p><p class="upb-key"><span><i class="lim" aria-hidden="true"></i>Limits</span><span><i aria-hidden="true"></i>Counted by splice</span></p></div><div class="upb-lad" aria-hidden="true"><p class="kicker">Highest used</p><div class="upb-rungs">${Array.from({ length: 14 }, () => `<i class="upb-rung gh"></i>`).join("")}</div></div></div>`;
  // ONE WHOLE ON EVERY PAGE (lead look, Oct 29): the logins whose limits splice reads, as the top bar and Settings count them,
  // so a signed-out login, one whose provider reports no limits and one splice no longer holds are not in it. The ladder
  // keeps a dashed rung for a login of the roster with no reading; a login splice no longer holds has no rung.
  const plans = cards.filter((c) => c.kind === "plan" && !c.gone), read = plans.filter((c) => c.used >= 0), held = read.filter((c) => c.used >= 100).length;
  const hero = read.length ? `<p class="upb-hero"><b${held ? ` class="lim"` : ""} data-tn="">${held}</b><span>of ${read.length} ${read.length === 1 ? "login" : "logins"} at their limit</span></p>` : "";
  const rungs = plans.slice().sort((a, b) => b.used - a.used).map((c) => (c.used < 0 ? `<i class="upb-rung none" title="${esc(c.name)}"></i>`
    : `<i class="upb-rung${c.used >= 100 ? " full" : ""}" title="${esc(`${c.name}: ${c.used}%`)}"><b style="height:${Math.max(1, Math.min(100, c.used))}%;--c:${colorOf(c.prov)}"></b></i>`)).join("");
  return `<div class="bandhead upb-head"><div class="upb-lead"><h2>Plans and keys</h2>${hero}<p class="upb-key"><span><i class="lim" aria-hidden="true"></i>Limits</span><span><i aria-hidden="true"></i>Counted by splice</span></p></div>`
    + (plans.length ? `<div class="upb-lad"><p class="kicker">Highest used</p><div class="upb-rungs" role="img" aria-label="Each login's highest used share, the nearest its limit first">${rungs}</div></div>` : "") + `</div>`;
};
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
/** The board's blocks, in the order the kit's balanced board (DRAW.masonry) places them: the provider with the most logins
 *  splits its cards, in order, down k columns, each column a block of its own, and every other provider is one block that
 *  goes where the board is shortest, so a short provider fills the room under a tall column. k is the one that leaves the
 *  whole board shortest, worked out as the board places them: from what the cards are built of and never from their
 *  figures, so the board keeps its places when the window or a figure changes. */
function planBoard(gs, n) {
  if (!gs.length) return [];
  const GAP = 1, HEAD = 3.73, BETWEEN = 2.4; // rem: between cards, a provider's head, between blocks in a column
  const whole = (g) => ({ g, col: g.cards, first: true });
  const heightOf = (cards) => HEAD + cards.reduce((t, c) => t + c.h + GAP, -GAP);
  // the board as the grid fills it: the provider's columns side by side at the top, then each other provider, in the order
  // the markup lists them, under the shortest column
  const tallest = (cols, seq) => {
    const h = Array(n).fill(0);
    cols.forEach((c, i) => { h[i] = heightOf(c); });
    for (const g of seq) { const i = h.indexOf(Math.min(...h)); h[i] += (h[i] ? BETWEEN : 0) + heightOf(g.cards); }
    return Math.max(...h);
  };
  // the order the other providers are listed in decides which column each lands under, so it is the one that leaves the
  // board shortest (the first order is the board's own, and another is taken only where it saves room worth a card's
  // line); with many providers the tallest block goes first, which the grid fills evenly enough
  const orders = (items) => {
    if (items.length > 7) return [items, [...items].sort((a, b) => heightOf(b.cards) - heightOf(a.cards))];
    const out = [], pick = (left, got) => { if (!left.length) out.push(got); else left.forEach((x, i) => pick([...left.slice(0, i), ...left.slice(i + 1)], [...got, x])); };
    pick(items, []);
    return out;
  };
  const place = (cols, items) => {
    let seq = items, t = tallest(cols, items);
    for (const o of orders(items)) { const v = tallest(cols, o); if (v < t - 1.9) { t = v; seq = o; } }
    return { t, seq };
  };
  const big = n > 1 && gs[0].cards.length >= 2 ? gs[0] : null;
  if (!big) return (n > 1 && gs.length > 2 ? place([], gs).seq : gs).map(whole);
  const rest = gs.slice(1);
  const cards = big.cards, m = cards.length, kMax = Math.min(n, m);
  const pre = [0];
  for (const c of cards) pre.push(pre[pre.length - 1] + c.h + GAP);
  const seg = (a, b) => pre[b] - pre[a] - GAP;
  // the first i cards split in order down j columns at the least tallest column: cut[j][i] is where the last column starts
  const best = Array.from({ length: kMax + 1 }, () => Array(m + 1).fill(Infinity)), cut = best.map((r) => r.slice());
  best[0][0] = 0;
  for (let j = 1; j <= kMax; j++) for (let i = j; i <= m; i++) for (let p = j - 1; p < i; p++) {
    const v = Math.max(best[j - 1][p], seg(p, i));
    if (v < best[j][i]) { best[j][i] = v; cut[j][i] = p; }
  }
  const split = (j) => { const cols = []; for (let c = j, i = m; c > 0; i = cut[c][i], c--) cols.unshift(cards.slice(cut[c][i], i)); return cols; };
  let cols = split(1), seq = rest, least = Infinity;
  for (let j = 1; j <= kMax; j++) { const c = split(j), p = place(c, rest); if (p.t <= least + 1e-6) { least = p.t; cols = c; seq = p.seq; } }
  return [...cols.map((col, i) => ({ g: big, col, first: i === 0 })), ...seq.map(whole)];
}
/** A card before splice has answered at all: the card's own shape in striped bars, at the height the card itself will
 *  stand, so nothing under the board moves when it lands. [rows] is the window lines the card drew last time (-1 is a
 *  key, whose one line says its limits are not reported) and [h] the height it drew at. */
const ghostCard = (i, rows = 2, h = 0) => `<article class="card pcard ghosted" style="--i:${i}${h ? `;min-height:${h}rem` : ""}"><div class="upb-top"><p class="upb-fig">${GHOST.bar(6)}</p></div><header>${GHOST.bar(7)}</header>`
  + (rows > 0 ? `<div class="windows">${Array.from({ length: rows }, () => `${GHOST.bar(3.2)}${GHOST.track()}${GHOST.bar(3)}${GHOST.bar(6)}`).join("")}</div>`
    : `<p class="nolimit">${GHOST.bar(12)}</p>`)
  + `<footer class="thru upb-thru">${Array.from({ length: rows < 0 ? 3 : 2 }, () => GHOST.row([GHOST.bar(1.3), GHOST.track(), GHOST.bar(3.2)])).join("")}</footer></article>`;
// The board's own shape, kept from the last read: each provider's group, in the board's order, with every card's window
// lines and height. The columns they fall in are worked out again for the width at hand, so a window of another width
// still opens on the board it will land as. Shapes only: no figure is kept and none is drawn.
const SHAPE_KEY = "splice-usage-board";
const keepShape = (groups) => {
  try { localStorage.setItem(SHAPE_KEY, JSON.stringify(groups.map((g) => g.cards.map((card) => [card.rows, Math.round(card.h * 10) / 10])))); }
  catch { /* a blocked or full store costs the ghost its shape and nothing else */ }
};
const okCard = (c) => Array.isArray(c) && c.length === 2 && c.every((v) => typeof v === "number");
// before a first read there is nothing to remember: a console with eight logins under one provider (each its two windows
// and the model's own slice under the week), one login under each of three more and a key under each of two, the shape
// the ghost holds until the first read has landed
const TYPICAL = [Array.from({ length: 8 }, () => [3, cardH("plan", 3, false)]), [[2, cardH("plan", 2, false)]], [[1, cardH("plan", 1, false)]], [[1, cardH("plan", 1, false)]],
  [[-1, cardH("key", 0, false)]], [[-1, cardH("key", 0, false)]]];
const lastShape = () => {
  try {
    const s = JSON.parse(localStorage.getItem(SHAPE_KEY) || "null");
    if (Array.isArray(s) && s.length && s.every((g) => Array.isArray(g) && g.length && g.every(okCard))) return s;
  } catch { /* unreadable */ }
  return TYPICAL;
};
/** And the board they land in: the same groups packed into the same columns, so nothing jumps when the read lands. */
const ghostBoard = () => { let i = 0;
  const groups = lastShape().map((cards) => ({ cards: cards.map(([rows, h]) => ({ rows, h })) }));
  return DRAW.masonry(planBoard(groups, boardCols()).map((it) =>
    `<section class="ugroup" data-cols="1"><h3>${GHOST.bar(5)}</h3><div class="gcol">${it.col.map((card) => ghostCard(i++, card.rows, card.h)).join("")}</div></section>`), 38, { cls: "plans" });
};

// ---------- what a value can be grouped by; opening one goes a step down with its filter added ----------
/** A model by the name its command declares; one nobody declared by its id without the trailing date a vendor stamps on it (the
 *  date stays on hover), and a request that named none says so. */
const NO_MODEL = new Set(["", "?", "unknown", "none"]);
const DATED = /-\d{8}$/;
function modelLabel(id) {
  if (id == null || NO_MODEL.has(String(id).trim().toLowerCase())) return { text: "No model named", tip: "", none: true };
  const known = S.labels[id] ?? S.labels[modelKey(id)];
  if (known) return { text: known, tip: "" };
  return { text: modelName(S.labels, String(id).replace(DATED, "")), tip: DATED.test(String(id)) ? String(id) : "" };
}
/** Agents named by the console's one rule (command · project, the name the user gave winning), two that read alike told apart
 *  by an ordinal in the order they started and, under the name, the time each started. The live ones that have sent
 *  nothing are named in the same pass, so a name never changes when a window holds more of them. */
function agentBook() {
  const byRow = rowProjects(), sent = new Set([...(D.scoped?.sessions.keys() ?? [])].map((k) => String(k).slice(0, 8)));
  const items = [...(D.scoped?.sessions.values() ?? [])].map((e) => ({ sid: e.sid, head: [...e.heads][0], idle: false }));
  for (const r of S.sessionRows) {
    const tag = String(r.session_id ?? "").slice(0, 8);
    if (tag && r.availability !== "gone" && !sent.has(tag)) items.push({ sid: r.session_id, head: S.liveHead[tag] ?? null, idle: true });
  }
  const facts = items.map((it) => {
    const row = S.sessions[it.sid] ?? S.sessions[String(it.sid).slice(0, 8)] ?? {}, p = projectOfSid(it.sid, byRow);
    return { name: row.name, title: row.title, command: it.head ? cmdOf(it.head) : "", project: p.key === NO_PROJECT_KEY || p.key === COMPARE_KEY ? "" : p.label, started: row.started_at ?? null };
  });
  const named = facts.map((f) => DRAW.name("session", f));
  const book = new Map(), alike = new Map();
  named.forEach((n, i) => (alike.get(n.text) ?? alike.set(n.text, []).get(n.text)).push(i));
  // two that read alike are told apart by when each started; a name the user gave is never numbered, an unnamed one is
  for (const idx of alike.values()) if (idx.length > 1) idx.sort((a, b) => +new Date(facts[a].started ?? 0) - +new Date(facts[b].started ?? 0)).forEach((i, n) => { named[i] = { ...named[i], text: named[i].own ? named[i].text : `${named[i].text} ${n + 1}`, alike: true }; });
  items.forEach((it, i) => {
    const f = facts[i], sub = named[i].alike && f.started && !Number.isNaN(+new Date(f.started)) ? `since ${backWord(new Date(f.started))}` : "";
    book.set(String(it.sid).slice(0, 8), { text: it.sid === COMPARE_KEY ? COMPARE_LABEL : named[i].text, sub, idle: it.idle, sid: it.sid, head: it.head });
  });
  return book;
}
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
  if (f.model) p.set("model", f.model === ui.f.model && D.modelIds?.length === 1 ? D.modelIds[0] : f.model);
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
  if (by === "model") return [...w.models.values()].map((e) => { const m = modelLabel(e.id); return { k: e.id, label: esc(m.text), tip: m.tip, none: m.none, sub: "", heads: e.heads, s: e }; });
  const byRow = rowProjects();
  if (by === "agent") { const book = agentBook(); return [...w.sessions.values()].map((e) => { const a = book.get(String(e.sid).slice(0, 8)); return { k: e.sid, label: esc(a?.text ?? sessTitle(String(e.sid).slice(0, 8))), sub: esc(a?.sub ?? ""), heads: e.heads, s: e }; }); }
  const m = new Map();
  for (const e of w.sessions.values()) {
    const p = projectOfSid(e.sid, byRow), g = m.get(p.key) ?? { k: p.key, label: esc(p.label), sub: "", heads: new Set(), s: ZERO() };
    fold2(g.s, e); g.heads = unionHeads(g.heads, e.heads); m.set(p.key, g);
  }
  return [...m.values()];
}
/** What the list knows of and the window never saw: every login and key the roster holds, every project with a live session, every
 *  model a command declares, every live agent that sent nothing. Only the unfiltered list has them: under a filter, the
 *  rest of the roster is not what the list is about. Each reads as an item of its own with no figures. */
function idleOf(by, active) {
  if (hasFilter() || ui.project != null) return [];
  // a command still being counted, or one that could not be read, has not said what went through it: nothing it serves is
  // said to have had no requests until it has answered
  const unsure = new Set([...(D.reading ?? []), ...D.errors.map((cmd) => S.heads.find((h) => h.command === cmd)?.key).filter(Boolean)]);
  if (unsure.size && by !== "account") return [];
  const have = new Set(active.map((e) => e.k)), out = [];
  const idle = (k, label, extra = {}) => out.push({ k: `idle:${k}`, label, sub: "", heads: new Set(), s: ZERO(), idle: true, ...extra });
  if (by === "account") {
    const named = new Set(active.map((e) => e.row).filter(Boolean));
    const usedKey = (name) => [...D.scoped.accounts.values()].some((e) => keyRecord(e.label)?.name === name);
    for (const a of S.accounts) if (a.kind !== "api-key" && !named.has(a) && !(a.heads || []).some((h) => unsure.has(h))) idle(`${(a.heads || []).join(",")}|${a.ref ?? a.display_name ?? a.label}`, planNameHtml(a));
    for (const k of S.keys) if (!unsure.size && !usedKey(k.name)) idle(`key|${k.name}|${k.fingerprint}`, keyWord(k.name, k.first_seen_epoch_seconds));
  } else if (by === "project") {
    for (const g of S.groups) if (!have.has(g.key)) idle(g.key, esc(g.label));
  } else if (by === "model") {
    const seen = new Set(active.map((e) => modelKey(e.k).replace(DATED, "")));
    for (const [key, id] of S.declared) if (!seen.has(key.replace(DATED, ""))) idle(id, esc(modelLabel(id).text));
  } else if (by === "agent") {
    const book = agentBook();
    for (const a of book.values()) if (a.idle) idle(a.sid, esc(a.text), { sub: esc(a.sub) });
  }
  return out.sort((a, b) => plain(a.label).localeCompare(plain(b.label)));
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
  const headOf = (note) => `<div class="bandhead"><h2>${money ? "Cost" : "Tokens"}</h2>${note}${sw}</div>`;
  const n = ui.win === "5h" ? 20 : DAYS[ui.win];
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
    // one run of words, so the comma stays on the word it follows (a flex caption spaces every child apart)
    + `<a class="cap" href="${reqUrl({ outcome: "fail" })}"><span><b>${n0(fails)}</b> failed${all.limited ? `, <b>${n0(all.limited)}</b> rate-limited` : ""}</span>${OPEN}</a>` : "";
  let lead;
  if (!money) {
    lead = `<div class="figline"><b class="fig-hero">${reqs && !noTokens(all) ? tok(tokensOf(all)) : DRAW.dash}</b>`
      + (reqs ? `<a class="cap" href="${reqUrl()}"><span>tokens in <b>${n0(reqs)}</b> requests</span>${OPEN}</a>` : `<span class="cap">tokens</span>`)
      + failPart + `</div>`;
  } else {
    const value = planValueOf(all), spent = spentOf(all);
    lead = `<div class="figline">`
      + (value != null || spent == null ? `<b class="fig-hero">${value != null ? usd(value) : DRAW.dash}</b><span class="cap"><i class="sw plan" aria-hidden="true"></i>on your plan, at API prices</span>` : "")
      + (spent != null ? `${value != null ? `<span class="fsep" aria-hidden="true"></span>` : ""}<b class="${value != null ? "fig-focal" : "fig-hero"}">${usd(spent)}</b><span class="cap"><i class="sw money" aria-hidden="true"></i>spent on keys</span>` : "")
      + failPart + `</div>`;
  }
  const peak = Math.max(0, ...sl.map((x) => Math.max(0, ...kinds.map((k) => sumParts(x[k])))));
  // the lane's bars share one scale of their own, from nothing to the worst column's rate, so the column that differs
  // reads first and a small rate is not a hairline beside a scale that runs to 100%
  const laneTop = niceTop(Math.max(0.01, ...sl.map((x) => (x.n ? x.failed / x.n : 0))));
  const flane = `<div class="flane" style="--n:${n}"><span class="fl-k" title="${esc(`Bars run from 0 to ${rateWord(laneTop)}`)}">Failed</span>${sl.map((x) => (x.failed
    ? `<a class="cfl${x.failed / x.n >= 0.05 ? " lv-bad" : ""}" href="${reqUrl({ from: x.from, to: x.to, outcome: "fail" })}" aria-label="${esc(failWords(x))}"><span class="ftrk"><i style="width:${barW(x.failed / x.n, laneTop).toFixed(2)}%"></i></span><b>${rateWord(x.failed / x.n)}</b></a>`
    : `<span></span>`)).join("")}</div>`;
  const keys = shown.map((h) => `<span class="key">${swatch(h)}${esc(cmdOf(h))}</span>`).join("")
    + (money && kinds.includes("val") ? `<span class="key kind"><i class="hatch" style="--c:var(--text-mute)"></i>On your plan</span>` : "")
    + (money && kinds.includes("mon") ? `<span class="key kind"><i style="--c:var(--text-mute)"></i>Spent on keys</span>` : "")
    // the last column is the one still filling: its top edge is open and its parts lighter, and the legend says so once
    + `<span class="key part"><i aria-hidden="true"></i>${ui.win === "5h" ? "This quarter hour, so far" : "Today, so far"}</span>`;
  // an empty window keeps its frame, its slots and its days, and says so once inside the plot
  if (!(peak > 0)) {
    const why = !reqs ? (uncounted() ? "Nothing counted yet" : "No requests") : money ? "Nothing priced" : "No tokens counted";
    return head + lead + `<div class="plot empty"><div class="cbars" style="--n:${n}">${sl.map((x) => `<div class="cbar${x.now ? " part" : ""}"><span class="slot"></span></div>`).join("")}</div>`
      + `<p class="plotnone">${DRAW.nothing(why)}</p></div>${xrow(sl)}${flane}<div class="legend">${keys}</div>`;
  }
  const top = niceTop(peak);
  const stackOf = (x, k, i) => {
    const parts = x[k], total = sumParts(parts), at = `${i}:${k === "mon" ? "m" : "p"}`;
    if (!total) return `<div class="cstack ${k}" data-ck="${at}" style="height:0%"></div>`;
    return `<div class="cstack ${k}" data-ck="${at}" style="height:${((total / top) * 100).toFixed(2)}%"><span class="cfig">${fmt(total)}</span>`
      + parts.map(([h, v]) => `<a class="${k === "val" ? "hatch" : alt.has(h) ? "alt" : ""}" href="${reqUrl({ cmd: cmdOf(h), from: x.from, to: x.to })}" style="--c:${colorOf(providerOfHead(h))};flex:${((v / total) * 1000).toFixed(2)} 1 0" aria-label="${esc(`${cmdOf(h)} ${fmt(v)}`)}">`
        // a column of one part carries its figure once, over the column; a part inside it says whose it is
        + `<span class="sl"><span class="nm">${esc(cmdOf(h))}</span>${parts.length > 1 ? ` <b>${fmt(v)}</b>` : ""}</span></a>`).join("") + `</div>`;
  };
  // a column carries its own tip, drawn with it and shown by hover or focus, so the pointer moving across the chart
  // redraws nothing and a keyboard reaches every column
  const bars = sl.map((x, i) => `<div class="cbar${kinds.length > 1 ? " pair" : ""}${x.now ? " part" : ""}" data-i="${i}" data-href="${reqUrl({ from: x.from, to: x.to })}" tabindex="0" role="link" aria-label="${esc(`${tipWhen(x)}: ${kinds.map((k) => fmt(sumParts(x[k]))).join(", ")}`)}">${kinds.map((k) => stackOf(x, k, i)).join("")}${tipHtml(x, i, sl.length, kinds, fmt)}</div>`).join("");
  // the axis is drawn at round values, so its money reads in whole dollars: cents of zero on a gridline say nothing
  const grid = [1, .5].map((f) => `<div class="gl" style="bottom:${f * 100}%"><span>${money ? fmt(top * f).replace(/\.00$/, "") : fmt(top * f)}</span></div>`).join("");
  return head + lead + `<div class="plot">${grid}<div class="cbars" style="--n:${n}">${bars}</div></div>${xrow(sl)}${flane}<div class="legend">${keys}</div>`;
}
/** A part's name and figure stay inside it only where they fit as drawn: the name goes first, then the figure. The tip,
 *  the legend and each part's own label for assistive tech still carry both. The figures over the columns stay only
 *  while no two of them touch: on narrow slots they would run into each other, and the tip carries them. */
function fitParts(el) {
  for (const s of el.querySelectorAll(".cstack > a > .sl")) {
    const part = s.parentElement, fits = () => s.offsetWidth <= part.clientWidth - 6 && s.offsetHeight <= part.clientHeight;
    if (fits()) continue;
    s.querySelector(".nm, .w")?.remove();
    if (!fits()) s.remove();
  }
  const figs = [...el.querySelectorAll(".cfig")].map((f) => [f, f.getBoundingClientRect()]).sort((a, b) => a[1].left - b[1].left);
  if (figs.some(([, r], i) => i > 0 && r.left < figs[i - 1][1].right + 4)) for (const [f] of figs) f.remove();
}
/** A row's commands give way to its name, measured as drawn like the chart's labels. Where the name, its project and its
 *  chips do not all fit the row's first cell, chips leave from the end and one "+N" stands in their place, naming them
 *  on hover and to assistive tech; opening the row narrows the page to it, where the chart's legend and Tokens by
 *  command list every one. So no chip runs over the bars beside it, and no name is cut to make room for a chip. */
function fitChips(el) {
  // what an element holds ends past its own right edge: a name its ellipsis cuts by less than a pixel is cut all the
  // same (two letters go for the ellipsis), and whole-pixel widths would call it a fit
  const cut = (x) => {
    if (x == null) return false;
    const r = document.createRange();
    r.selectNodeContents(x);
    return r.getBoundingClientRect().right > x.getBoundingClientRect().right + .05;
  };
  for (const who of el.querySelectorAll(".brow .who")) {
    const box = who.querySelector(":scope > .cmds"), nm = who.querySelector(":scope > .nm"), sb = who.querySelector(":scope > .sb");
    const tight = () => cut(box) || cut(who) || cut(nm) || cut(sb);
    if (box && tight()) {
      const chips = [...box.querySelectorAll(".chip")], gone = [];
      const more = document.createElement("span");
      more.className = "chip quiet more";
      box.append(more);
      while (chips.length) {
        const c = chips.pop();
        gone.unshift(c.textContent);
        c.remove();
        more.textContent = `+${gone.length}`;
        if (!tight()) break;
      }
      more.title = gone.join(", ");
      more.setAttribute("aria-label", `and ${gone.join(", ")}`);
    }
    // a name longer than the whole cell keeps its start, and its hover gives it whole
    for (const t of [nm, sb]) if (cut(t)) t.title = t.textContent;
  }
}
// a slice's failed requests, and the rate-limited ones among them, in words: what a door to them reads aloud
const failWords = (x) => `${n0(x.failed)} failed of ${n0(x.n)} requests${x.limited ? `, ${n0(x.limited)} of them rate-limited` : ""}`;
const tipWhen = (x) => (ui.win === "5h" ? `${clock(new Date(x.from))} to ${clock(new Date(x.to))}` : x.now ? "Today, so far" : new Date(x.from).toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric" }));
function tipHtml(x, i, n, kinds, fmt) {
  const span = tipWhen(x);
  const part = (k) => {
    const total = sumParts(x[k]);
    const lines = x[k].slice().sort((a, b) => b[1] - a[1]).map(([h, v]) => `<p>${swatch(h, k === "val")}<span>${esc(cmdOf(h))}</span><i class="tbar" style="width:${share(v, total).toFixed(1)}%"></i><b>${fmt(v)}</b></p>`).join("");
    const word = k === "val" ? "On your plan" : k === "mon" ? "Spent on keys" : "All";
    return lines + (x[k].length > 1 || kinds.length > 1 ? `<p class="sum"><span>${word}</span><b>${fmt(total)}</b></p>` : "");
  };
  const body = kinds.map(part).join("");
  const fail = x.failed ? `<p class="sum fail"><span>Failed</span><i class="tbar f" style="width:${barW(x.failed, x.n).toFixed(1)}%"></i><b>${rateWord(x.failed / x.n)}</b></p>`
    + `<p class="sub"><b>${n0(x.failed)}</b> of <b>${n0(x.n)}</b>${x.limited ? `<span class="dot"></span><b>${n0(x.limited)}</b> rate-limited` : ""}</p>` : "";
  return `<div class="tip${(i + .5) / n > .6 ? " lefty" : ""}" aria-hidden="true"><p class="tw">${span}</p>${body || `<p class="none">No requests</p>`}${fail}</div>`;
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
  if (cost == null && value == null) return `<span class="cell cost none" title="${esc(noCostWhy(s))}" aria-label="${esc(`Not priced: ${noCostWhy(s)}`)}"><span class="vbar gone" aria-hidden="true"></span><b class="v">${BARE_DASH}</b></span>`;
  const line = (v, plan) => `<span class="cline"><span class="vbar" aria-hidden="true"><b${plan ? ` class="plan"` : ""} data-tw="${plan ? "v" : "m"}" data-w="${barW(v, max).toFixed(1)}" style="width:${barW(v, max).toFixed(1)}%"></b></span>`
    + `<b class="v ${plan ? "val" : "money"}" data-tn="${plan ? "v" : "m"}">${usd(v)}</b></span>`;
  return `<span class="cell cost${cost != null && value != null ? " two" : ""}">${cost != null ? line(cost, false) : ""}${value != null ? line(value, true) : ""}</span>`;
}
/** The tokens cell: one bar on the column's scale and its figure; a group no request reported tokens for is a dashed
 *  track and a dash, never 0. */
const tokCell = (s, max) => (noTokens(s) ? `<span class="cell tok"><span class="vbar gone" aria-hidden="true"></span><b class="v num">${DRAW.dash}</b></span>`
  : `<span class="cell tok">${DRAW.vbar(barW(tokensOf(s), max))}<b class="v num" data-tn="">${tok(tokensOf(s))}</b></span>`);
/** The share of an input bar's track the longest bar takes: the names of the parts too narrow to carry their figure stand after its end. */
const SPAN = 0.7;
const ROWS_KEY = "splice-usage-rows";
const lastRows = (k, fallback) => { try { return JSON.parse(localStorage.getItem(ROWS_KEY) || "{}")[k] ?? fallback; } catch { return fallback; } };
const keepRows = (k, n) => { try { const all = JSON.parse(localStorage.getItem(ROWS_KEY) || "{}"); all[k] = n; localStorage.setItem(ROWS_KEY, JSON.stringify(all)); } catch { /* the ghost keeps its default size */ } };
const RANKS = [["tokens", "Tokens"], ["requests", "Requests"], ["cost", "Cost, estimated"]];
const rankBy = (e) => (ui.rank === "cost" ? Math.max(spentOf(e.s) ?? 0, planValueOf(e.s) ?? 0) : ui.rank === "requests" ? e.s.requests : tokensOf(e.s));
function byHtml() {
  // the legend names the marks the rows draw: a window where no row has key money draws no solid mark, so it names none
  // the head carries what the list is grouped by and, from 14 rows, its Find, so the list starts right under it
  const headOf = (money, plan, unpriced, extra = "") => `<div class="bandhead"><h2>Where it went</h2>`
    + (money || plan || unpriced ? `<span class="pd-leg">${money ? `<i class="sw money"></i>Spent` : ""}${plan ? `<i class="sw plan"></i>On your plan, at API prices` : ""}${unpriced ? `<i class="sw gone"></i>Not priced` : ""}</span>` : "")
    + extra + DRAW.switcher(Object.entries(DIMS).map(([k, v]) => [k, v.word]), ui.by, "by", "Group by") + `</div>`;
  // the head row's columns are doors that rank the rows; the column the rows are ranked by carries the arrow
  const colsOf = (cls) => `<div class="${cls} bhead"><span>${DIMS[ui.by].one}</span>`
    + RANKS.map(([k, word]) => `<button class="cell rank${ui.rank === k ? " on" : ""}" type="button" data-act="rank" data-v="${k}" aria-pressed="${ui.rank === k}">${esc(word)}${ui.rank === k ? DOWN : ""}</button>`).join("")
    + `</div>`;
  // reading: the columns' names are already known, so the table reads as itself and the rows that land take the place
  // the stripes held. Its rows are .gbrow, never .brow: a .brow is a row of this window's figures.
  const waiting = () => headOf(true, true, false) + `<div class="figline sub">${GHOST.fig(2.6)}${GHOST.bar(8)}</div>` + ghostRows(lastRows(`by-${ui.by}`, CUT + 2), 4, colsOf("gbrow"));
  if (reading()) return waiting();
  if ((ui.by === "project" || ui.by === "agent") && S.sessionsPending) return waiting();
  const sent = groupsOf(ui.by).sort((a, b) => rankBy(b) - rankBy(a) || b.s.requests - a.s.requests || tokensOf(b.s) - tokensOf(a.s));
  const every = [...sent, ...idleOf(ui.by, sent)];
  const find = ui.find.trim().toLowerCase();
  const list = find ? every.filter((e) => plain(e.label).toLowerCase().includes(find) || plain(e.sub).toLowerCase().includes(find)) : every;
  const live = list.filter((e) => !e.idle);
  // Rows that ran on the same commands stand together under one line of chips, said once, whatever the ranking: the groups
  // stand in the order of their best row and the rows inside each keep their rank. A row with no neighbour on its commands
  // keeps its chips on itself.
  const sets = new Map();
  for (const e of live) { const k = [...e.heads].sort().join(" "); (sets.get(k) ?? sets.set(k, []).get(k)).push(e); }
  const order = [];
  for (const rows of sets.values()) rows.forEach((e, i) => order.push({ e, first: i === 0, size: rows.length }));
  // what no request reached stands after everything one did, in the list's own order
  for (const e of list) if (e.idle) order.push({ e, first: false, size: 1 });
  const mx = { tok: Math.max(1, ...live.map((e) => tokensOf(e.s))), req: Math.max(1, ...live.map((e) => e.s.requests)),
    cost: Math.max(1e-9, ...live.map((e) => Math.max(spentOf(e.s) ?? 0, planValueOf(e.s) ?? 0))) };
  const cut = order.length <= ui.shown + 1 ? order.length : ui.shown;
  const shown = order.slice(0, cut), tail = order.slice(cut).map((o) => o.e);
  const row = ({ e, first, size }, at) => {
    const rank = `<span class="upl-rank" aria-hidden="true">${at + 1}</span>`;
    if (e.idle) {
      const none = `<span class="cell"><span class="vbar gone" aria-hidden="true"></span></span>`;
      return `<div class="brow idle" data-key="by-${ui.by}-${esc(e.k)}"><span class="who">${rank}${ui.by === "project" ? "" : `<i class="dot-c idle" aria-hidden="true"></i>`}<span class="nm">${e.label}</span>${e.sub ? `<span class="sb">${e.sub}</span>` : ""}<span class="state">No requests</span></span>${none}${none}${none}</div>`;
    }
    const f = ui.by === "project" ? ui.f : { ...ui.f, [ui.by]: e.k }, extra = ui.by === "project" ? { project: e.k } : {};
    const reqFig = `<span class="vbar" aria-hidden="true"><b data-tw="" data-w="${barW(e.s.requests, mx.req).toFixed(1)}" style="width:${barW(e.s.requests, mx.req).toFixed(1)}%"></b></span><b class="v" data-tn="">${n0(e.s.requests)}</b>`;
    const naming = e.nameIdx != null && e.row;
    const name = naming ? nameSlot(e.row, e.nameIdx, "row") : `<span class="nm${e.none ? " nomodel" : ""}"${e.tip ? ` title="${esc(e.tip)}"` : ""}>${e.label}</span>`;
    const line = first && size > 1 ? `<div class="bgrp"><span class="cmds">${chipsOf(e.heads)}</span></div>` : "";
    const dot = ui.by === "project" ? "" : `<i class="dot-c" aria-hidden="true"></i>`;
    // a login splice no longer holds says so, as its card does; it has no way back in to offer, since nothing here is
    // signed out (a login of the roster that is signs in again on its card and on Accounts)
    const outWord = e.gone ? `<span class="state">${GONE_WORD}</span>` : "";
    // a login at its limit carries the lock its card has, so a row finds its state as well as its card
    const held = heldRow(e.row) ? `<span class="rowlock" role="img" aria-label="At its limit" data-tip="At its limit">${ICON.lock}</span>` : "";
    return line + `<div class="brow${e.gone ? " gone" : ""}${e.row && ui.landed != null && S.accounts[ui.landed] === e.row ? " landed" : ""}" data-key="by-${ui.by}-${esc(e.k)}"${DRAW.flip(`by-${ui.by}-${e.k}`)} data-act="pick" data-d="${ui.by}" data-v="${esc(e.k)}" style="--c:${colorOf(providerOfHead([...e.heads][0]))}" role="button" tabindex="0">`
      + `<span class="who">${rank}${dot}${name}${held}${e.sub ? `<span class="sb">${e.sub}</span>` : ""}${outWord}`
      + `${size === 1 ? `<span class="cmds">${rowChipsOf(e.heads)}</span>` : ""}</span>`
      + tokCell(e.s, mx.tok)
      + (e.many ? `<span class="cell req num">${reqFig}</span>` : `<a class="cell req num open" href="${reqUrl({ ...extra, order: "price" }, f)}">${reqFig}${OPEN}</a>`)
      + costCell(e.s, mx.cost) + `</div>`;
  };
  // the tail is a row like the others, carrying its own count and its own totals, so what is folded away is still drawn
  const tailRow = tail.length ? (() => {
    const s = tail.reduce((t, e) => fold2(t, e.s), ZERO());
    const more = Math.min(MORE_STEP, tail.length);
    // a tail of items no request reached has no figure to add up: its three cells are the empty tracks the rows themselves draw
    if (tail.every((e) => e.idle)) {
      const gone = `<span class="cell"><span class="vbar gone" aria-hidden="true"></span></span>`;
      return `<div class="brow btail" data-act="more" role="button" tabindex="0" aria-label="${esc(`Show ${more} more ${DIMS[ui.by].word.toLowerCase()}`)}">`
        + `<span class="who"><span class="nm">${DRAW.n(tail.length)} more</span><span class="sb">${esc(tail.length === 1 ? DIMS[ui.by].one.toLowerCase() : DIMS[ui.by].word.toLowerCase())}</span>${DRAW.chev}</span>${gone}${gone}${gone}</div>`;
    }
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
  const rowsHtml = list.length ? shown.map((o, i) => row(o, i)).join("") + tailRow
    : `<div class="bnone">${DRAW.nothing(find ? "Nothing by that name" : uncounted() ? "Nothing counted yet" : "No requests")}</div>`;
  // a list still being counted, or narrowed by Find, is not the size the next read lands at: its ghost keeps the last
  // whole count
  if (!find && (list.length || !uncounted())) keepRows(`by-${ui.by}`, list.length ? shown.length + (tail.length ? 1 : 0) + shown.filter((o) => o.first && o.size > 1).length * .6 : 1);
  const head = headOf(sent.some((e) => spentOf(e.s) != null), sent.some((e) => planValueOf(e.s) != null), sent.some((e) => spentOf(e.s) == null && planValueOf(e.s) == null), finder);
  return head + lead + `<div class="card btable">${colsOf("brow")}${rowsHtml}</div>${fewer}`;
}
/** Rows the size of the rows that will land, so the column keeps its height while the window reads. They are not
 *  .brow: a reading row is a shape, never a row of figures, and nothing on the page may read the two as one. */
const ghostRows = (n, cells, head = "") => `<div class="card btable ghosted">${head}${Array.from({ length: Math.max(3, Math.round(n)) }, (_, i) =>
  `<div class="gbrow" style="--i:${i}"><span class="who">${GHOST.bar(9)}</span>${Array.from({ length: cells - 1 }, () => `<span class="cell">${GHOST.track()}</span>`).join("")}</div>`).join("")}</div>`;

// ---------- each command's tokens: one bar per command, split as its provider counted them ----------
// Input holds what was read from the cache and what was written to it, as every provider here reports it. A figure no
// request reported is a dash, never 0: a provider that never reports a cache write (OpenAI's Responses) sends 0 on every
// request, so a window of input with no write at all is that silence: its bar has no written part, and says on hover that
// the provider reports none, so the absence never reads as nothing written.
function tokensHtml() {
  // the legend names the three parts of an input bar and, only where a command has the mark, the glyph of its turns that ran
  // cache-cold after an account switch
  const legendOf = (cold) => `<span class="pd-leg tk"><i class="sw s1"></i>Cached<i class="sw s2"></i>Written<i class="sw s3"></i>Fresh`
    + `${cold ? `<span class="coldkey">${DRAW.glyph.cold}</span>After a switch` : ""}</span>`;
  const head = (body, lead = "", cold = false) => `<div class="bandhead"><h2>Tokens by command</h2>${legendOf(cold)}</div>${lead}${body}`;
  const heads = (mx) => `<div class="trow thead"><span>Command</span>${[["Input", mx ? `0 to ${tok(mx.in)}` : ""], ["Output", mx ? `0 to ${tok(mx.out)}` : ""], ["No count", mx ? `% of requests, 0 to ${DRAW.pct(mx.miss / 100) ?? ""}` : ""]]
    .map(([w, end]) => `<span class="cell hh"><b>${w}</b>${end ? `<small>${end}</small>` : ""}</span>`).join("")}</div>`;
  const cols = heads(null);
  // the table is kept while the window reads, every row a striped bar the size its figures land at, its columns named
  if (reading()) return head(`<div class="card ttable ghosted">${cols}${Array.from({ length: Math.max(3, lastRows("tok", 7)) }, (_, i) =>
    `<div class="gtrow" style="--i:${i}"><span class="who">${GHOST.bar(7, "chip")}</span>${[0, 1, 2].map(() => `<span class="cell">${GHOST.track()}</span>`).join("")}</div>`).join("")}</div>`,
  `<div class="figline sub cachelead">${GHOST.ring("focal")}${GHOST.fig(2.6)}${GHOST.bar(9)}</div>`);
  const w = D.scoped, rd = D.reading ?? new Set();
  // the command with the most input leads, so the column the rows are read by is the order they are in
  const list = headsOrder().map((h) => [h, w.byHead.get(h)]).filter(([h, s]) => rd.has(h) || s?.requests > 0)
    .sort((a, b) => (rd.has(a[0]) - rd.has(b[0])) || (b[1]?.in ?? 0) - (a[1]?.in ?? 0));
  if (list.length || !uncounted()) keepRows("tok", list.length || 1);
  if (!list.length) return head(`<div class="card ttable"><div class="bnone">${DRAW.nothing(uncounted() ? "Nothing counted yet" : "No requests")}</div></div>`);
  const mx = { in: Math.max(1, ...list.map(([, s]) => s?.in ?? 0)), out: Math.max(1, ...list.map(([, s]) => s?.out ?? 0)),
    // the share of a command's requests that carried no count, on one scale across the commands that counted some, so the
    // one that differs reads first; a command that counted none has no share to draw
    miss: Math.max(1, ...list.map(([, s]) => (s && s.requests && !noTokens(s) ? share(Math.max(s.noIn, s.noOut), s.requests) : 0))) };
  const tin = list.reduce((a, [, s]) => a + (s?.in ?? 0), 0), tcached = list.reduce((a, [, s]) => a + (s?.cached ?? 0), 0);
  // the share of the input read from the cache is drawn first, as the ring Results' Cache hit draws, then written
  const lead = `<div class="figline sub cachelead">${tin > 0 ? `${DRAW.ring(tcached / tin, { size: "focal", label: `${DRAW.pct(tcached / tin)} of input read from cache` })}<b class="fig-focal">${DRAW.pct(tcached / tin)}</b><span class="cap">of <b>${tok(tin)}</b> input read from cache</span>` : `${DRAW.ring(0, { size: "focal" })}<b class="fig-focal">${DRAW.dash}</b><span class="cap">input read from cache</span>`}</div>`;
  let coldAny = false;
  const rows = list.map(([h, s], at) => {
    if (rd.has(h)) return `<div class="trow"><span class="who"><span class="upl-rank" aria-hidden="true">${at + 1}</span>${chip(h)}</span>${[0, 1, 2].map(() => `<span class="cell">${GHOST.track()}</span>`).join("")}</div>`;
    const none = noTokens(s), noIn = s.noIn >= s.requests, counted = s.requests - Math.max(s.noIn, s.noOut);
    const fresh = Math.max(0, s.in - s.cached - s.write);
    const segs = [["s1", s.cached, "Cached"], ["s2", s.write, "Written"], ["s3", fresh, "Fresh"]];
    const stackW = barW(s.in, mx.in);
    // The input bar is the one place the cache split is drawn: what was read from the cache, what was written to it and
    // what was fresh. A part with room carries its figure; every part's figure, and the share each is, is on the bar's hover
    // and read aloud. A provider that reports no cache write sends none on any request, so the bar has no written part and
    // its hover says the provider reports none: never that nothing was written.
    const said = [`Cached ${tok(s.cached)}`, s.write > 0 ? `Written ${tok(s.write)}` : null, `Fresh ${tok(fresh)}`].filter(Boolean).join(" · ")
      + (s.write > 0 ? "" : ` · its provider reports no cache writes`);
    const inCell = noIn || none ? `<span class="cell inp"><span class="ttrack empty"></span><b class="v">${DRAW.dash}</b></span>`
      // every part is drawn and named: a part with room carries its figure, a narrower one is named after the bar's end; a
      // part its provider never reports (a cache write) is a dash there
      : `<span class="cell inp"><span class="ttrack" role="img" aria-label="${esc(`Input ${tok(s.in)}: ${said}`)}" data-tip="${esc(said)}"><span class="tstack" data-tw="tin:${esc(h)}" data-w="${(stackW * SPAN).toFixed(2)}" style="width:${(stackW * SPAN).toFixed(2)}%">`
        + segs.map(([cls, v, word]) => `<i class="${cls}${v > 0 ? "" : " nil"}" data-part="${word}" style="width:${s.in > 0 ? ((v / s.in) * 100).toFixed(2) : 0}%"><b>${v > 0 || cls !== "s2" ? tok(v) : DRAW.dash}</b></i>`).join("")
        + `</span><span class="tout"></span></span><b class="v">${tok(s.in)}</b></span>`;
    const outCell = none || s.noOut >= s.requests ? `<span class="cell"><span class="vbar gone" aria-hidden="true"></span><b class="v">${DRAW.dash}</b></span>`
      : `<span class="cell">${DRAW.vbar(barW(s.out, mx.out))}<b class="v">${tok(s.out)}</b></span>`;
    const missing = s.requests - counted;
    const countCell = none ? `<span class="cell rep"><span class="vbar gone" aria-hidden="true"></span><b class="v">${DRAW.dash}</b></span>` : `<span class="cell rep" title="${esc(`${n0(missing)} of ${n0(s.requests)} requests carried no count`)}">${DRAW.vbar(share(missing, s.requests) > 0 ? Math.max(1.1, share(missing, s.requests) / mx.miss * 100) : 0)}<b class="v" data-tn="">${DRAW.pct(missing / s.requests)}</b></span>`;
    const accts = [...w.accounts.values()].filter((a) => a.head === h && (a.cold > 0 || a.coldUn > 0)).sort((a, b) => b.cold - a.cold || b.coldUn - a.coldUn);
    const open = ui.cold.has(h) && accts.length > 0;
    // TURNS THAT RAN CACHE-COLD AFTER AN ACCOUNT SWITCH are a mark on the command they belong to, drawn only where there are
    // any (as the drill-down's glyph tiles are): its glyph, the count, and the turns splice could not tie to an account. A
    // command with logins to name opens them in place.
    const cold = s.cold > 0 || s.coldUn > 0;
    if (cold) coldAny = true;
    const mark = `${DRAW.glyph.cold}${s.cold > 0 ? `<b class="v">${n0(s.cold)}</b>` : ""}${s.coldUn > 0 ? `<small class="um">${n0(s.coldUn)} not marked</small>` : ""}`;
    const sayCold = `${s.cold > 0 ? `${n0(s.cold)} turns sent again after an account switch` : "Turns after an account switch"}${s.coldUn > 0 ? `, ${n0(s.coldUn)} not marked to an account` : ""}`;
    const coldMark = !cold ? "" : accts.length
      ? `<button class="cold disc" type="button" data-act="cold" data-k="${esc(h)}" aria-expanded="${open}" aria-controls="cold-${esc(h)}" aria-label="${esc(sayCold)}">${DRAW.chev}${mark}</button>`
      : `<span class="cold" role="img" aria-label="${esc(sayCold)}">${mark}</span>`;
    return `<div class="trow${open ? " open" : ""}" data-key="tok-${esc(h)}" style="--c:${colorOf(providerOfHead(h))}"><span class="who"><span class="upl-rank" aria-hidden="true">${at + 1}</span>${chip(h)}${none ? `<span class="state">No counts</span>` : ""}${coldMark}</span>${inCell}${outCell}${countCell}</div>`
      + (open ? coldPlate(h, s, accts) : "");
  }).join("");
  return head(`<div class="card ttable">${heads(mx)}${rows}</div>`, lead, coldAny);
}
/** The turns of one command that ran cache-cold after splice switched its account, account by account: how many, the
 *  input each sent again with what it is a share of, and the turns splice could not mark. */
function coldPlate(h, s, accts) {
  const mx = { cold: Math.max(1, ...accts.map((a) => a.cold)), resent: Math.max(1e-9, ...accts.map((a) => (a.in > 0 ? (a.coldIn / a.in) * 100 : 0))) };
  // the turns splice could not mark are a column of the plate only where a login has any: a column of nothing says
  // nothing, and the command's own row already carries the count where it has one
  const un = accts.some((a) => a.coldUn > 0);
  const gone = (a) => !planRowOf(h, a.label) && isPlanHead(h) && REF_SHAPE.test(String(a.label));
  const rows = accts.map((a) => `<div class="crow${un ? " un" : ""}"><span class="cn">${acctLabel(h, a.label, a.name)}${gone(a) ? `<span class="state">Signed out</span>` : ""}</span>`
    + `<span class="cell">${a.cold > 0 ? `${DRAW.vbar(barW(a.cold, mx.cold))}<b class="v">${n0(a.cold)}</b>` : ""}</span>`
    + `<span class="cell"${a.coldIn > 0 ? ` title="${esc(`${tok(a.coldIn)} of ${tok(a.in)} input sent again`)}"` : ""}>${a.coldIn > 0 && a.in > 0 ? `${DRAW.vbar(barW((a.coldIn / a.in) * 100, mx.resent), `cold-${h}-${a.label}`)}<b class="v">${DRAW.pct(a.coldIn / a.in)}</b><small class="um">${tok(a.coldIn)}</small>` : ""}</span>`
    + (un ? `<span class="cell um">${a.coldUn > 0 ? `<b class="v">${n0(a.coldUn)}</b>` : ""}</span>` : "")    + `</div>`).join("");
  const lead = s.coldIn > 0 ? DRAW.focal(tok(s.coldIn), `input sent again, of ${tok(s.in)}`) : "";
  return `<div class="tdetail plate${ui.coldNew === h ? " unfold" : ""}" id="cold-${esc(h)}">`
    + DRAW.section("Account switches", lead
      + `<div class="ctable"><div class="crow chead${un ? " un" : ""}"><span>Account</span><span class="cell hh"><b>Cold turns</b><small>0 to ${n0(mx.cold)}</small></span><span class="cell hh"><b>Input sent again, % of its input</b><small>0 to ${DRAW.pct(mx.resent / 100)}</small></span>`
      + `${un ? `<span class="cell um hh"><b>Not marked</b></span>` : ""}</div>${rows}</div>`)
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
  const word = f.kind === "slow" ? `Slow since ${clock(new Date(f.from))}` : `Failing ${clock(new Date(f.from))} to ${clock(new Date(f.to))}`;
  // a slow run's two times are marks on the stages' axis; a failing burst says how many it was
  const vs = f.kind === "slow" ? "" : `<span class="vs"><b>${f.n}</b> failed</span>`;
  return `<span class="flag ${f.kind}"><a href="${href}">${word}${OPEN}</a>${vs}</span>`;
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
const PERF_HEADS = createPerfHeads(scope, { render: () => { renderBand("perf"); renderDoors(); }, chip, providerOfHead, cmdOf, flagOf, flagHtml, hasFilter, ui, S, n0, secs, OPEN, OUTCOME_WORD, tagWord, isFailed, NOT_READ });

const BANDS = {
  money: { el: () => document.getElementById("band-money"), html: moneyHtml },
  chart: { el: () => document.getElementById("band-chart"), html: chartHtml },
  by: { el: () => document.getElementById("band-by"), html: byHtml },
  tok: { el: () => document.getElementById("band-tok"), html: tokensHtml },
  perf: { el: () => document.getElementById("band-perf"), html: () => PERF_HEADS.html() },
};
/** A window change keeps every bar at the length it had, striped and with its figure left off, until the new window's read
 *  lands; then each bar moves to its new length. The columns wait too: the chart is not drawn for a window whose figures
 *  are not here yet. */
function markStale() { document.querySelector(".page")?.classList.toggle("upl-stale", D.stale); }
function winChange(v) {
  ui.win = v; ui.shown = CUT; ui.find = "";
  if (!D.scoped) { again(); return; }
  D.stale = true; markStale(); renderWin();
  readWindow();
}
/** The heights each column of the chart stands at, by the place it holds in the chart: the primary stack (tokens, or a plan's
 *  value) and the key money stack, so a switch from Tokens to Cost moves the same column and a new window moves the first
 *  columns both windows have. */
const columnHeights = (el) => {
  const box = el.querySelector(".cbars")?.getBoundingClientRect();
  return new Map([...el.querySelectorAll(".cstack[data-ck]")].map((c) => {
    const r = c.getBoundingClientRect();
    return [c.dataset.ck, { h: c.style.height, left: box ? r.left - box.left : 0, width: r.width, px: r.height, node: c }];
  }));
};
/** The columns a window change keeps move from their old height to the new, the ones it adds grow from nothing, and the ones
 *  it no longer has fold down to nothing where they stood, all over the one move time. */
function glideColumns(el, was) {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const stacks = [...el.querySelectorAll(".cstack[data-ck]")], kept = new Set(stacks.map((c) => c.dataset.ck));
  for (const c of stacks) {
    const to = c.style.height, from = was.get(c.dataset.ck)?.h ?? "0%";
    if (from === to) continue;
    c.style.transition = "none"; c.style.height = from;
    c.getBoundingClientRect();
    c.style.transition = "height var(--t-snap) var(--e-out)"; c.style.height = to;
  }
  const box = el.querySelector(".cbars"), leaving = [];
  for (const [key, old] of was) {
    if (kept.has(key) || !box || !(old.px > 0)) continue;
    const ghost = old.node.cloneNode(true);
    ghost.removeAttribute("data-ck");
    ghost.classList.add("uch-leave");
    Object.assign(ghost.style, { position: "absolute", left: `${old.left}px`, bottom: "0", width: `${old.width}px`, height: `${old.px}px`, transition: "none" });
    box.append(ghost); leaving.push(ghost);
  }
  if (leaving.length) {
    box.getBoundingClientRect();
    for (const g of leaving) { g.style.transition = "height var(--t-snap) var(--e-out), opacity var(--t-snap) var(--e-out)"; g.style.height = "0px"; g.style.opacity = "0"; }
  }
  if (leaving.length) holdFold(el);
  setTimeout(() => { for (const c of stacks) c.style.transition = ""; for (const g of leaving) g.remove(); }, FOLD_MS);
}
/** One band re-drawn on its own: a hover moves nothing else, and [grow] lets what changed grow into its new length. */
const drawn = new WeakMap();
// a band whose columns or rows are still folding away is drawn again once they have, never in the middle of it
const FOLD_MS = 300;
const folding = new WeakMap(), foldTimers = new Map();
const holdFold = (el) => folding.set(el, performance.now() + FOLD_MS);
/** The rows of a table by the thing each stands for, with where each stood. */
const rowsOf = (el) => new Map([...el.querySelectorAll(".brow[data-key], .trow[data-key]")].map((node, at) => [node.dataset.key, { node, at, h: node.getBoundingClientRect().height }]));
/** A new window's read lands on rows drawn for the old one: a row the new window brings arrives with its bars growing from
 *  nothing, and a row it does not have folds down where it stood, over the one move time. The rows both have move their bars
 *  by the kit's own tween. */
function flowRows(el, was) {
  if (!was?.size || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const now = rowsOf(el), after = [...now.values()];
  for (const [key, row] of now) if (!was.has(key)) row.node.classList.add("arrive");
  const folding = [];
  for (const [key, old] of was) {
    if (now.has(key)) continue;
    const ghost = old.node.cloneNode(true);
    ghost.removeAttribute("data-key"); ghost.removeAttribute("data-act"); ghost.removeAttribute("role"); ghost.removeAttribute("tabindex");
    ghost.classList.add("leave");
    ghost.style.height = `${old.h}px`;
    const at = after[Math.min(old.at, after.length - 1)]?.node;
    if (!at) continue;
    old.at >= after.length ? at.after(ghost) : at.before(ghost);
    folding.push(ghost);
  }
  if (!folding.length) return;
  el.getBoundingClientRect();
  for (const g of folding) g.classList.add("folds");
  holdFold(el);
  setTimeout(() => { for (const g of folding) g.remove(); }, FOLD_MS);
}
let landing = false;
function renderBand(name, grow = false) {
  const el = BANDS[name].el();
  if (!el || (D.stale && name === "chart")) return;
  const html = BANDS[name].html();
  // a band drawn again with what it already shows is left as it stands, so columns and rows still folding away are not cut short
  const wait = (folding.get(el) ?? 0) - performance.now();
  if (wait > 0) {
    clearTimeout(foldTimers.get(name));
    foldTimers.set(name, setTimeout(() => renderBand(name, grow), wait + 20));
    return;
  }
  const keeps = name === "chart" || name === "by" || name === "tok";
  if (keeps && drawn.get(el) === html) return;
  if (keeps) drawn.set(el, html);
  const was = name === "chart" ? columnHeights(el) : null;
  const before = landing && (name === "by" || name === "tok") ? rowsOf(el) : null;
  el.classList.toggle("grow", Boolean(grow) && !was?.size && !before?.size); // what stood moves to its new length instead
  el.innerHTML = html;
  if (name === "money" || name === "tok") DRAW.paint(el, { grow: grow ? "all" : name === "money" && ui.landed != null ? new Set([`plan-${ui.landed}`]) : null, reading: false });
  if (name === "chart" || name === "tok") fitParts(el);
  if (name === "tok") fitInput(el);
  if (before) flowRows(el, before);
  if (name === "chart" && was.size) glideColumns(el, was);
  if (name === "by") fitChips(el);
  if (name === "by" || name === "tok" || name === "perf") settleLower();
}
/** An input bar's parts: one at least 3rem wide carries its figure inside; a narrower one is named after the bar's end by its
 *  swatch and figure, so every part of every bar is read where it is drawn. */
function fitInput(el) {
  const room = 3 * parseFloat(getComputedStyle(document.documentElement).fontSize);
  for (const track of el.querySelectorAll(".ttrack")) {
    const out = track.querySelector(".tout");
    if (!out) continue;
    out.textContent = "";
    for (const part of track.querySelectorAll(".tstack > i")) {
      const fig = part.querySelector("b");
      if (!fig || part.offsetWidth >= room) continue;
      const tag = document.createElement("span");
      tag.className = "tl";
      tag.innerHTML = `<i class="sw ${part.className.split(" ")[0]}" aria-hidden="true"></i>`;
      tag.append(fig);
      out.append(tag);
    }
  }
}
/** The three tables' board, laid where the last of them landed: Tokens stands under the shorter column, so when a table
 *  grows or shrinks (a Results row opens, Find narrows the list, a window holds fewer accounts) it may change columns,
 *  and it travels to its new place over the kit's move time rather than appearing there. One pass per draw, after every
 *  band the draw touched. */
let lowerQueued = false;
function settleLower() {
  if (lowerQueued) return;
  lowerQueued = true;
  queueMicrotask(() => {
    lowerQueued = false;
    const box = document.getElementById("lower");
    if (!box) return;
    DRAW.lay(box);
    const tok = document.getElementById("band-tok");
    if (tok) flipMove(tok);
  });
}
// The project tiles are known only once the transcripts have been listed. Until then the line holds the shape it last
// landed in, hollow: a tile's own box per tile at a wide page, the picker's chip at a narrow one. It drew nothing, and
// the whole page jumped down by the tiles' height when they arrived.
function renderPick() {
  const line = document.getElementById("pickline");
  line.innerHTML = S.sessionsPending ? GHOST.tiles(GHOST.count("tiles", 6)) : pickLineHtml(S.groups, ui.project, ui.pmenu, projectFigures());
  if (!S.sessionsPending) {
    GHOST.remember("tiles", S.groups.length >= 2 ? S.groups.length + 1 : 0);
  }
  renderWin();
  renderDoors();
}
const renderWin = () => { document.getElementById("win").innerHTML = DRAW.switcher(Object.entries(WINDOWS).map(([k, v]) => [k, v.label]), ui.win, "win", "Window"); };
/** The three lower bands each answer with one figure, and the header carries it as a door that scrolls to the band: the
 *  spend, the failed share and the cache share are read on the first screen, whatever stands between it and the bands. */
function renderDoors() {
  const el = document.getElementById("doors");
  if (!el) return;
  const door = (to, word, body) => `<button class="upl-door" type="button" data-act="door" data-v="${to}" aria-label="${esc(word)}"><span class="upl-dk">${esc(word)}</span><span class="upl-dv">${body}</span>${DRAW.go}</button>`;
  const wait = `${GHOST.bar(5)}`;
  const out = [];
  const w = D.scoped, read = !S.error && w && !reading();
  let by = wait;
  if (read) {
    const tot = w.total, value = planValueOf(tot), spent = spentOf(tot);
    by = value != null || spent != null
      ? (value != null ? `<i class="sw plan" aria-hidden="true"></i><b class="upl-df" data-tn="door-plan">${usd(value)}</b>` : "") + (spent != null ? `<i class="sw money" aria-hidden="true"></i><b class="upl-df" data-tn="door-spent">${usd(spent)}</b>` : "")
      : `<b class="upl-df" data-tn="door-tok">${noTokens(tot) || !tot.requests ? DRAW.dash : tok(tokensOf(tot))}</b><span class="upl-dq">tokens</span>`;
  }
  out.push(door("by", "Where it went", by));
  // Results reads every request over its own window, beside two doors on the page's window and pick, so its door says
  // what its figure covers wherever the two differ, in the band's words: its window, and all requests under a pick.
  const lead = PERF_HEADS.lead();
  const span = lead && lead.win !== ui.win ? ` in ${lead.word}` : "";
  const all = ui.project != null || hasFilter() ? ", all requests" : "";
  out.push(door("perf", "Results by command", !lead ? wait : lead.none ? `<span class="upl-dq">No requests${span}</span>`
    : `<b class="upl-df lv-${lead.level}" data-tn="door-failed">${lead.figure}</b><span class="upl-dq">failed${span}${all}</span>`));
  let tk = wait;
  if (read) {
    const all = [...w.byHead.values()], tin = all.reduce((a, x) => a + x.in, 0), tc = all.reduce((a, x) => a + x.cached, 0);
    tk = tin > 0 ? `${DRAW.ring(tc / tin, { size: "focal", label: `${DRAW.pct(tc / tin)} of input read from cache` })}<b class="upl-df" data-tn="door-cache">${DRAW.pct(tc / tin)}</b><span class="upl-dq">from cache</span>`
      : `<b class="upl-df">${DRAW.dash}</b><span class="upl-dq">from cache</span>`;
  }
  out.push(door("tok", "Tokens by command", tk));
  el.innerHTML = out.join("");
}
function renderScope() {
  const el = document.getElementById("scope");
  if (el) el.innerHTML = scopeHtml();
}
// only the first landing of a window's figures grows from nothing; every later read moves each bar and figure from the
// length it had (the kit's tween keys), so a re-read is not a redraw
let landedOnce = false;
function render(grow = false) {
  if (grow && !reading()) { grow = !landedOnce; landedOnce = true; } else if (grow) grow = false;
  tick();
  renderPick();
  if (S.error) {
    root.innerHTML = `<section class="route-error" role="alert">${ICON.bang}<span><b>Usage didn't open</b> <span class="why">${esc(S.error)}</span></span>`
      + `<button class="act small" type="button" data-act="reread">Try again</button></section>`;
    return;
  }
  if (!document.getElementById("band-money")) {
    root.innerHTML = `<section class="band money" id="band-money"></section><div class="scopeline" id="scope"></div><section class="band chart" id="band-chart"></section>`
      // the first two tables stand side by side where the page is wide, and Tokens by command takes the whole row under
      // them, so a Results row that opens or a list that grows never moves Tokens to another column
      + `<div class="lower mason" id="lower"><section class="band by" id="band-by"></section><div class="band perf" id="band-perf"></div>`
      + `<section class="band tokens" id="band-tok" data-cols="all" data-flip="usage-tok"></section></div>`;
  }
  renderScope();
  for (const name of ["money", "chart", "by", "tok", "perf"]) renderBand(name, grow);
}
// the figures on screen belong to the window and filters they were read under: a new one reads again and says so, never
// drawing the old figures under the new heading
const again = () => { D.stale = false; markStale(); D.loading = true; D.scoped = null; D.all = null; D.every = null; D.gen++; ui.find = ""; render(); readWindow(); };
async function boot() { render(); await readStanding(); render(); if (!S.error) await readWindow(); }

// ---------- signing a login in again, on its own card ----------
// The card's button becomes a box that runs the provider's own step (a code to type on its page, a code pasted back, or a page
// that finishes by itself), then the card draws its meters once; a sign-in that does not complete says so in the box.
function howFor(target) {
  if (target.startsWith("plan-")) {
    const row = S.accounts[Number(target.slice(5))], head = (row?.heads || [])[0];
    if (!row || !head) return null;
    if (row.login_place) return { start: `/api/claude-logins/${encodeURIComponent(row.login_place.id)}/login`, body: { label: null }, poll: head, native: true };
    const label = row.account_labels?.[head] || row.label;
    // a removed primary has no file to sign in to under its reserved name: signing in with no name is what makes it again
    return { start: `/api/auth/${encodeURIComponent(head)}/login`, body: label && !(label === "primary" && !row.credential_present) ? { label } : {}, poll: head };
  }
  const head = target.slice(5).split("|")[0];
  return head ? { start: `/api/auth/${encodeURIComponent(head)}/login`, body: {}, poll: head } : null;
}
const stopSign = (g) => clearTimeout(g?.timer);
async function signStart(target) {
  const how = howFor(target);
  if (!how) return;
  stopSign(ui.signing[target]);
  const g = ui.signing[target] = { how, state: "wait", by: how.native ? "paste" : "browser", code: null, url: null, id: null, gen: (ui.signing[target]?.gen || 0) + 1 };
  renderBand("money");
  const res = await API.post(how.start, how.body || {});
  if (ui.signing[target] !== g) return;
  if (!res.ok || !res.body?.id) { signFail(target, res.body); return; }
  g.id = res.body.id;
  signSeen(target, res.body);
}
function signSeen(target, st) {
  const g = ui.signing[target];
  if (!g || g.state !== "wait") return;
  if (st?.state === "failed") { signFail(target, st); return; }
  if (st?.state === "signed_in" || st?.state === "live_after_restart") { signDone(target); return; }
  const url = st?.verification_uri || st?.browser_url || null, by = st?.user_code ? "code" : g.how.native ? "paste" : "browser", code = st?.user_code || null;
  const changed = url !== g.url || by !== g.by || code !== g.code;
  Object.assign(g, { url, by, code });
  if (changed) renderBand("money");
  g.timer = setTimeout(async () => {
    const res = await API.get(`/api/auth/${encodeURIComponent(g.how.poll)}/login/${encodeURIComponent(g.id)}`);
    if (ui.signing[target] !== g) return;
    if (!res.ok) { signFail(target, null); return; }
    signSeen(target, res.body);
  }, 1000);
}
function signFail(target, st) {
  const g = ui.signing[target];
  if (!g) return;
  stopSign(g);
  const why = st?.failure_reason ?? st?.error;
  Object.assign(g, { state: "fail", why: typeof why === "string" ? why.trim() : "" });
  ui.signShook = target;
  renderBand("money");
  setTimeout(() => { ui.signShook = null; }, 400);
}
async function signDone(target) {
  stopSign(ui.signing[target]);
  delete ui.signing[target];
  const accounts = await API.get("/api/accounts");
  if (accounts.ok) S.accounts = accounts.body?.accounts || [];
  ui.landed = target.startsWith("plan-") ? Number(target.slice(5)) : null;
  renderBand("money"); renderBand("by");
  setTimeout(() => { ui.landed = null; }, 1000);
}
function signStop(target) { stopSign(ui.signing[target]); delete ui.signing[target]; renderBand("money"); }
function signCode(field) {
  const g = ui.signing[field.dataset.t];
  if (!g?.id || !field.value.trim() || field.readOnly) return;
  field.readOnly = true;
  API.post(`/api/auth/${encodeURIComponent(g.how.poll)}/login/${encodeURIComponent(g.id)}/code`, { code: field.value.trim() }).then((res) => {
    if (ui.signing[field.dataset.t] !== g) return;
    if (!res.ok) signFail(field.dataset.t, res.body); else signSeen(field.dataset.t, res.body);
  });
}

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
  const col = e.target.closest(".cbar[data-href]"); // a column's own body opens exactly its slice, every command
  if (col) { location.href = col.dataset.href; return; }
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
    case "win": if (v === ui.win) break; winChange(v); break;
    case "door": document.getElementById(`band-${v}`)?.scrollIntoView({ block: "start", behavior: "smooth" }); break;
    case "measure": if (v === ui.measure) break; ui.measure = v; renderBand("chart"); break;
    case "by": if (v === ui.by) break; ui.by = v; ui.shown = CUT; ui.find = ""; renderBand("by", true); break;
    case "rank": if (v === ui.rank) break; ui.rank = v; ui.shown = CUT; renderBand("by", true); break;
    case "more": { // only the rows the tail let go of arrive; the rows already on screen stay still
      const was = ui.shown; ui.shown += MORE_STEP; renderBand("by");
      [...(BANDS.by.el()?.querySelectorAll(".btable > .brow:not(.bhead):not(.btail)") ?? [])].slice(was).forEach((r, i) => { r.classList.add("arrive"); r.style.setProperty("--i", String(Math.min(i, 12))); });
      break;
    }
    case "fewer": ui.shown = CUT; renderBand("by"); BANDS.by.el()?.scrollIntoView({ block: "start", behavior: "smooth" }); break;
    case "cold": { const k = el.dataset.k; if (ui.cold.has(k)) { ui.cold.delete(k); ui.coldNew = null; } else { ui.cold.add(k); ui.coldNew = k; } renderBand("tok"); ui.coldNew = null; break; }
    case "signin": signStart(el.dataset.t); break;
    case "signstop": signStop(el.dataset.t); break;
    case "signopen": { const g = ui.signing[el.dataset.t]; if (g?.url) window.open(g.url, "_blank", "noopener"); root.querySelector(`.upb-codein[data-t="${CSS.escape(el.dataset.t)}"]`)?.focus(); break; }
    case "signcopy": { const g = ui.signing[el.dataset.t]; if (g?.code) navigator.clipboard?.writeText(g.code).catch(() => {}); break; }
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
  if (e.key === "Escape" && ui.pmenu) { ui.pmenu = false; renderPick(); return; }
  if (e.target.matches?.(".upb-codein")) { if (e.key === "Enter") { e.preventDefault(); signCode(e.target); } return; }
  if (e.target.matches?.(".nmfield")) {
    if (e.key === "Enter") { e.preventDefault(); saveName(ui.naming); }
    if (e.key === "Escape") stopNaming();
    return;
  }
  if (e.key !== "Enter" && e.key !== " ") return;
  if (e.target.matches?.(".cbar[data-href]")) { e.preventDefault(); location.href = e.target.dataset.href; return; }
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
// the board's columns, the project tiles, which labels fit inside their bars and how many of a row's chips fit beside
// its name are the page's own width, so a resized window is drawn again for it; a name being typed or a search being
// typed keeps its field
let sized = null;
const typing = () => ui.naming != null || document.activeElement?.matches?.(".bfind, .upb-codein") === true;
addEventListener("resize", () => {
  clearTimeout(sized);
  sized = setTimeout(() => { if (!typing()) { renderPick(); renderBand("money"); } renderBand("chart"); renderBand("tok"); if (!typing()) renderBand("by"); }, 200);
});
// what fits is measured in the console's own faces: once they have loaded, the bands that measure are drawn again
document.fonts?.ready.then(() => { if (reading()) return; renderBand("chart"); renderBand("tok"); if (!typing()) renderBand("by"); });
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
