// Requests: every request a command sent upstream, found by session, searched by what was sent and what came back,
// filtered, and opened: its attempts on one time rail, the bodies both ways, its tokens and what it cost. The drawing
// (Splice-Animator design/console, d3338fa) on splice's own answers: every name, number and state below is read from
// the control API, and nothing on this page is invented.
//
// WHERE EVERY VALUE COMES FROM.
//   GET /api/heads · /api/models            the commands, each one's provider, and its models' own labels
//   GET /api/perf/turns?head=…              one head's window of requests, filtered BY THE DAEMON (TurnsFilter.kt):
//                                           since, n, outcome, model, account, session, unattributed, local, compact
//                                           (always local=0: a code-mode step posts nothing upstream and is no request,
//                                           PerfKeys.kt:83-84, and Usage leaves it out too, TurnBill.kt:110-112)
//   the same, n=1                           the window's own menus: its models, accounts and sessions, whatever the
//                                           filters are, so a menu never lists only what the current filter left
//   GET /api/heads/{head}/trace/search?q=   the turns whose sent or returned body holds the words, newest first, with
//                                           how far back the search read and why it stopped
//   GET /api/heads/{head}/trace?turn=ID     one request's own records, as its trace kept them
//   GET /api/sessions                       the session each request belongs to, by its name, and the project it ran in
//   GET /api/keys                           a key account's variable and the day splice first saw it
//   GET /api/config (knobs.js)              how long prompts and answers are kept, which is how far back a search can
//                                           look: the figure comes from the setting, never from a cut this page made up
//
// A FIGURE NOBODY REPORTED IS LEFT OUT, never printed as a zero: a provider that reported no tokens, a request that
// never got a first byte and a turn with no price each draw nothing where the number would be.
"use strict";

// ---------- what this page adds to the kit ----------
// kit.js carries the console's shared language: NOW and tick(), esc, G, ICON, COLORS, colorOf, clock, dayWord, md,
// OUTCOME_WORD, STOPPED, isClean, WHY and sideBySide. Only what Requests alone needs is here.
//
const FAMILY = PROVIDER_FAMILY; // kit.js: the provider /api/models names, as the colour it draws in

// the open request is reqview.js, shared with Compare models, and so are its clock and count words
const { clockS, dayOf, n0, secs, GLYPH } = REQVIEW;
const RV = REQVIEW.view(() => ui);
const RV_KEPT = REQVIEW.kept; // whether a state has records to draw, as the shared view decides it

// ---------- what an outcome tag says: fin's words, none of splice's sentences ----------
// Clean: ok and empty_message, no word. Stopped: client_abort and error:stopped. Every other tag is a failure, with its glyph.
// A provider's own failure takes the glyph of the FACT it shares: a rate limit is the limit, an api_error is the
// retries splice spent on it, and a refused credential is the credential glyph (its word, "Credentials refused",
// is what separates it from being signed out). The rest are the plain bang.
const GLYPH_OF = { "error:cancelled": "bang", "error:restarted": "bang", "error:at-capacity": "limit", "error:turn-cap": "bang", "error:plan-limit": "limit", "error:rate-limited": "limit",
  "error:all-accounts-exhausted": "limit", "error:budget-blocked": "limit", "error:auth-missing": "signout", "error:upstream-failed": "retries",
  "failure:overloaded_error": "retries", "error:conn-reset": "retries", "error:upstream-frame-too-large": "bang", empty_model: "bang", "error:unexpected": "bang",
  "failure:rate_limit_error": "limit", "failure:api_error": "retries", "failure:authentication_error": "signout",
  "failure:invalid_request_error": "bang", "failure:permission_error": "bang", "failure:not_found_error": "bang" };
const OUTCOME = Object.fromEntries(Object.keys(OUTCOME_WORD).concat(["ok", "empty_message"]).map((tag) => [tag, {
  cls: isClean(tag) ? "clean" : STOPPED.has(tag) ? "stopped" : "fail", word: OUTCOME_WORD[tag] ?? "" , glyph: GLYPH_OF[tag] }]));
// NO ROW READS "Failed": a tag fin has worded gets fin's word, and anything else shows the provider's own type made
// readable (kit.js tagWord), because a person can act on "Context window error" and can act on nothing at all with
// "Failed". "Failed" is the Outcome switch's label, which counts every failure, and belongs nowhere else on this page.
// The endings splice decides itself, whose outcome word already says who and why: no provider line is drawn for them
const SPLICE_ENDED = new Set(["error:restarted", "error:at-capacity", "error:budget-blocked", "error:all-accounts-exhausted", "error:turn-cap", "error:cancelled"]);
const outcomeOf = (r) => OUTCOME[r.outcome] || { cls: "fail", word: tagWord(r.outcome) ?? "", glyph: "bang" };
// What sits beside the word: the reset a spent window named, or the retries splice ran inside the request. THE ROW
// DECIDES, not the tag: splice writes the reset when it turned a request away because every account was spent
// (ExhaustedAccountAdmission.kt:32), and a request that met the window mid-flight ends error:plan-limit with no reset
// in its row at all — 156 of them in one live day. Reading the lock off the tag would promise a time no row holds.
// THE LOCK NAMES ITS WINDOW the way Accounts names its rows (accounts.js LEN: "5 hours", "Day", "Week", "Month"), from
// the length the provider's own refusal gave (limit_window_seconds), so the two pages agree by construction. A refusal
// that named no window reads "At limit", the kit's limit word, and names none (fin, Oct 10): p157 and p158 each called
// the lock's cause a guess when it gave a time and no window.
const WINDOW_WORD = { 18000: "5 hours", 86400: "Day", 604800: "Week", 2592000: "Month" };
const lockWord = (r) => WINDOW_WORD[r.window] ?? "At limit";
const resetWord = (r) => `${lockWord(r)} · Resets ${dayOf(r.reset)}${clock(r.reset)}`;
// Then what splice did inside the request: a broken stream it resumed or started over, in the timeline's tick word and
// the tick's colour (.mend, hitstop 8405b48), so a walker finds docs-site without timing it (p157), and its retries.
const besideOf = (r) => {
  if (r.reset) return `<span class="detail">${ICON.lock}${resetWord(r)}</span>`;
  const mend = r.resumed ? `<span class="detail mend">${r.resumedFresh ? WHY.startedover.word : WHY.resumed.word}</span>` : "";
  return mend + (r.retries ? `<span class="detail">${r.retries} ${r.retries === 1 ? "retry" : "retries"}</span>` : "");
};
// why a request has no dollar figure (TurnPriceGap.kt), in fin's words; one that was never answered says nothing
const REASON = { plan: "Plan", local: "This computer", vast: "vast.ai", unanswered: "", uncounted: "Uncounted", undeclared: "No price" };
// A key is an account: its variable and the day splice first saw THAT key, "OPENROUTER_API_KEY · Sep 14" (fin's
// words). A row names the key it was sent with as `VARIABLE:fingerprint` (KeyAccount.kt), so a rotated key reads as
// its own account and the one it replaced keeps its own history, marked Replaced — on Requests as on Accounts
// (Marcos, Oct 8). A plan's label has no colon and no key behind it, and prints as it stands.
const keyNameOf = (account) => { const s = String(account ?? ""), at = s.lastIndexOf(":"); return at < 0 ? s : s.slice(0, at); };
const acctHtml = (r) => {
  const key = state.keySeen[r.account];
  if (!key) return esc(keyNameOf(r.account) || r.account);
  const was = key.replaced ? `<span class="detail">Replaced</span>` : "";
  return `<span class="var">${esc(key.name)}</span> · ${dayWord(key.seen)}${was}`;
};
const costText = (r) => (r.cost != null ? money(r.cost) : REASON[r.reason] ?? "");
const DOWN = G('<path d="M6 9l6 6 6-6"/>', 'class="down" aria-hidden="true"');

// ---------- the filters, as /api/perf/turns takes them (TurnsFilter.kt), plus the search over bodies ----------
const WINDOWS = { "1h": ["1 hour", 3600e3], "5h": ["5 hours", 5 * 3600e3], "24h": ["24 hours", 24 * 3600e3], "7d": ["7 days", 7 * 24 * 3600e3] };
const money = (usd) => `≈$${usd < 0.01 ? +usd.toFixed(4) : usd.toFixed(usd < 1 ? 3 : 2)}`; // priced at the model's card
const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone; // the daemon folds the window's days in the viewer's zone
const MAX_HELD = 2000; // the most rows one read of a head can carry (PerfRoutes.kt MAX_TURNS); asking for more is clamped
const SESSIONS_IN_MENU = 50; // as many as one look down the menu takes in; the rest are reached from Sessions
const SEARCH_MIN = 2; // the shortest text the search route reads (TraceSearch.kt)

let state = {
  loading: true, error: null, heads: [], providerOf: {}, modelLabel: {}, sessions: {}, keySeen: {},
  rows: [], matched: 0, capped: false, facets: { models: [], accounts: [], sessions: [] }, headErrors: [], sessionsError: null, traceDays: null, capture: {}, config: null,
  // what Start saving answered, per command: { word, failed }, the word that stands beside "Not saved" once pressed
  saving: {},
  // what the search answered: how far back it read, why it stopped, and the turns it could not reach
  search: null,
  // when the window lists nothing: the nearest wider window that has matches, and how many (readWider)
  wider: null,
};
let ui = { open: null, q: "", win: "24h", cmd: null, sid: null, model: null, account: null, repo: null, from: null, to: null,
  outcome: "all", why: null, compact: false, menu: null, n: 200, tab: "sent", nodes: new Set(), auto: true };
const root = document.getElementById("requests");
const wide = sideBySide;
// How far back every read asks: the window, or the stretch a door from another page named, which may reach further
// back than the window does. One figure, so the rows, the menus and the search all cover the same ground.
const since = (win = ui.win) => Math.min(ui.from ?? Infinity, +NOW - WINDOWS[win][1]);
const providerOfCmd = (cmd) => state.providerOf[(state.heads.find((h) => h.command === cmd) || {}).key];
const sessTitle = (sid) => { const s = state.sessions[sid]; return s?.name || (sid ? `Session ${String(sid).slice(0, 8)}` : ""); };

// ---------- reading splice ----------
/** The commands, their providers, the models' labels, the sessions requests belong to, and a key's first day. */
async function readStanding() {
  const [heads, models, sessions, keys, topology] = await Promise.all([
    API.get("/api/heads"), API.get("/api/models"), API.get("/api/sessions"), API.get("/api/keys"), API.get("/api/topology"),
  ]);
  state.error = heads.ok ? null : refusalOf(heads, "The commands could not be read");
  state.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key }));
  // A provider splice reaches on this machine's own loopback is served from THIS COMPUTER, whatever it is called:
  // his own model servers and the tunnel to a rented GPU both answer at 127.0.0.1 (splice.toml base_url), and both
  // draw in the one colour the kit gives a local provider. The address is the fact; the name is not.
  const bases = topology.body?.topology?.providers || {};
  const local = (name) => /^https?:\/\/(127\.0\.0\.1|\[?::1\]?|localhost)\b/.test(bases[name]?.base_url || "");
  for (const row of models.body?.heads || []) {
    state.providerOf[row.head] = FAMILY[row.provider] ?? (local(row.provider) ? "local" : row.provider);
    for (const m of row.models || []) if (m.id) state.modelLabel[m.id] = m.label || m.id;
  }
  // A session's name is its own page's to read. When that read refuses, the rows still list by the id splice recorded
  // and the refusal is said, because every row reading "Session 8cb8a71d" is not a day without names.
  state.sessionsError = sessions.ok ? null : refusalOf(sessions, "The session names could not be read");
  state.sessions = {};
  for (const s of sessions.body?.sessions || []) {
    if (!s.session_id) continue;
    state.sessions[s.session_id] = s;
    state.sessions[String(s.session_id).slice(0, 8)] = s; // a perf row names its session by its first eight characters
  }
  // Each key splice has seen under a variable, by the account label a request row carries: the one in use now and
  // every one it replaced (/api/keys serves both, KeyReaders.kt). A fingerprint this daemon has never seen leaves
  // the row with its variable alone, which is what the row itself proves.
  state.keySeen = {};
  for (const k of keys.body?.keys || []) {
    if (!k.name) continue;
    const seen = (print, at, replaced) => {
      if (print && at) state.keySeen[`${k.name}:${print}`] = { name: k.name, seen: new Date(at * 1000), replaced };
    };
    seen(k.fingerprint, k.first_seen_epoch_seconds, false);
    for (const old of k.replaced || []) seen(old.fingerprint, old.first_seen_epoch_seconds, true);
  }
  // Whether each command is saving prompts and answers NOW, and for how long it keeps them: the setting's own figure,
  // per command, never a cut this page made up. The files outlive the knob, so a command switched off still has the
  // days it recorded, and this says which state a request with no records is in.
  const caps = await Promise.all(state.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/capture`)));
  state.heads.forEach((h, i) => {
    const body = caps[i].ok ? caps[i].body : null;
    if (body) state.capture[h.key] = { on: body.enabled === true, days: body.retention_days ?? null };
  });
  const days = uniq(state.heads.map((h) => state.capture[h.key]?.days).filter((d) => d != null));
  state.traceDays = days.length === 1 ? days[0] : null; // one figure to say only when every command agrees on it
  const cfg = await API.get("/api/config");
  state.config = cfg.ok ? cfg.body : null;
}

/** The silence tier splice acts on mid-answer for one command: its own value where it holds one, the daemon's
 *  otherwise. Reading it keeps the rail's quiet band to the silences splice itself would have asked about. */
function idleTier(head) {
  const cfg = state.config;
  if (!cfg) return Infinity; // a tier nobody answered draws no band, rather than a band at a figure this page chose
  const own = cfg.layers?.perHead?.[head.key] ?? {};
  const of = (key) => own[key] ?? cfg.effective?.[key];
  const tiers = [of("stallReanchorMs"), of("streamIdleMs")].filter((v) => typeof v === "number" && v > 0);
  return tiers.length ? Math.min(...tiers) : Infinity;
}

/** A refusal in the words the daemon used, under the name of what was being read, so a page never says "none" where
 *  it means "could not read" and never shows a bare sentence a reader cannot place. */
const refusalOf = (res, subject) => (res.status === 0 ? `${subject}: splice is not answering`
  : `${subject}: ${res.body?.error || `splice answered ${res.status}`}`);

/** One head's window: the rows the filters asked for, and the window's own menus from an unfiltered read. */
// What a door's "what happened" asks the daemon for (TurnsFilter.kt), so it narrows over the WHOLE window and not
// over the newest 2,000 the route already cut: the doors that name a wait open requests that are mostly old, which is
// exactly where a browser filter finds nothing. What the daemon reads is a SUPERSET of what the word means -- a
// silence must also have begun after the first byte, and only this page compares that -- so WHY's own check still
// runs over the answer and the two together are exact. `tier` is this command's own silence setting, not a figure
// this page chose. "Resumed" and "Started over" are asked APART, as fin's two phrases mean them: a request whose
// re-anchor re-posted it verbatim started over, and the resumed doors leave it out.
const WHY_ASKS = {
  gaveup: { outcome: "error:turn-cap" }, overloaded: { outcome: "failure:overloaded_error" },
  restarted: { outcome: "error:restarted" }, queued: { queued_ms: "1" }, waited: { silence_ms: "tier" },
  resumed: { resumed: "1", started_over: "0" }, silentresume: { resumed: "1", started_over: "0" },
  startedover: { started_over: "1" }, silentover: { started_over: "1" },
};
function turnsPath(head, filtered, win = ui.win) {
  // local=0 on both reads, so the menus count the same requests the list does
  const p = new URLSearchParams({ head: head.key, since: String(since(win)), time_zone: ZONE, n: filtered ? String(MAX_HELD) : "1", local: "0" });
  if (filtered) {
    if (ui.outcome === "fail") p.set("outcome", "failed");
    if (ui.outcome === "stopped") p.set("outcome", "stopped");
    if (ui.model === "none") p.set("unattributed", "model");
    else if (ui.model) p.set("model", ui.model);
    if (ui.account === "none") p.set("unattributed", "account");
    else if (ui.account) p.set("account", ui.account.split("|").slice(1).join("|"));
    if (ui.sid) p.set("session", String(ui.sid).slice(0, 8));
    if (ui.compact) p.set("compact", "1");
    if (ui.to) p.set("until", String(ui.to));
    for (const [key, value] of Object.entries(WHY_ASKS[ui.why] || {})) {
      const tier = value === "tier" ? idleTier(head) : null;
      // an exact tag is narrower than the switch's family and agrees with it, except when the switch says Stopped
      // and the door names a failure: then they disagree and the switch holds, which lists nothing, honestly
      if (key === "outcome" && ui.outcome === "stopped") continue;
      // no answered tier is no floor to ask for: the page's own check decides alone, as it does today
      if (tier != null && !Number.isFinite(tier)) continue;
      p.set(key, tier != null ? String(Math.round(tier)) : value);
    }
  }
  return `/api/perf/turns?${p}`;
}

/** Which heads this read asks at all: a command filter, or an account picked on one head, narrows it to that head. */
function askedHeads() {
  const picked = ui.account && ui.account !== "none" ? ui.account.split("|")[0] : null;
  return state.heads.filter((h) => (!ui.cmd || h.command === ui.cmd) && (!picked || h.key === picked));
}

async function readRows() {
  const heads = askedHeads();
  const reads = await Promise.all(heads.flatMap((h) => [API.get(turnsPath(h, true)), API.get(turnsPath(h, false))]));
  const rows = [], errors = [], facets = { models: new Map(), accounts: new Map(), sessions: new Map() };
  let matched = 0, capped = false;
  heads.forEach((head, i) => {
    const [filtered, window] = [reads[i * 2], reads[i * 2 + 1]];
    const answer = (filtered.body?.heads || []).find((x) => x.key === head.key);
    if (!filtered.ok || !answer) { errors.push(refusalOf(filtered, `${head.command}\u2019s requests could not be read`)); return; }
    if (answer.error) { errors.push(`${head.command}: ${answer.error}`); return; }
    if (answer.read_error) errors.push(`${head.command}: ${answer.read_error}`);
    matched += answer.count ?? 0;
    if (answer.truncated) capped = true;
    for (const row of answer.rows || []) rows.push(mapRow(head, row));
    const menus = (window.body?.heads || []).find((x) => x.key === head.key)?.usage;
    for (const m of menus?.models || []) if (m.key) facets.models.set(m.key, state.modelLabel[m.key] ?? m.key);
    for (const a of menus?.accounts || []) if (a.key) facets.accounts.set(`${head.key}|${a.key}`, { head, account: a.key });
    for (const s of menus?.sessions || []) if (s.key) facets.sessions.set(s.key, { head, last: s.last_model_ts_epoch_ms });
  });
  state.rows = rows;
  state.matched = matched;
  state.capped = capped;
  state.headErrors = errors;
  state.facets = { models: [...facets.models], accounts: [...facets.accounts], sessions: [...facets.sessions] };
}

/** One perf row as this page draws it. A key the writer never put in the row is left null, never zeroed: a provider
 *  that reported no tokens and a request that never got a first byte draw nothing where their figure would be. */
function mapRow(head, row) {
  const started = row.ts - (row.total ?? 0);
  // THE ONE SILENCE THE ROW RECORDS AS AN INTERVAL: up_gap_max_ms with its own start, which the keys say belong to the
  // same winning interval (PerfKeys.kt:133-135). It is drawn only when it is a silence splice would have asked about:
  // one that began after the first byte, so it is a quiet stretch mid-answer and not the ordinary wait for the first
  // one, and one at or past the tier the watchdog acts on mid-output, which is this command's own setting and not a
  // figure this page chose. Nothing else is placed: the other silences the row holds (up_content_gap_max_ms,
  // up_read_wait_max_ms) are a duration with no recorded start, and a band at a guessed place is worse than no band.
  const tier = idleTier(head);
  const gap = row.up_gap_max_ms ?? 0, gapAt = row.up_gap_max_start_epoch_ms ?? null;
  const firstByteAt = row.first_byte != null ? started + row.first_byte : null;
  const waited = gapAt && gap >= tier && (firstByteAt == null || gapAt >= firstByteAt) ? { ms: gap, at: gapAt } : null;
  // a re-anchor ends a silence, so the tick stands at the end of the one the row recorded; with no interval recorded
  // there is no tick, never a guessed place. A re-anchor that re-posted the request VERBATIM is a round STARTED OVER,
  // not an answer resumed from its partial (reanchors_from_scratch, ReanchorRunner's own comparison): two different
  // things to the person reading the row, and the row now says which.
  const resumed = row.reanchors ?? 0;
  const fresh = (row.reanchors_from_scratch ?? 0) > 0;
  const stall = row.stall_ms ?? null;
  const sid = row.session_id || row.session || null;
  return {
    id: `${head.key}:${row.turn || row.turn_id || row.ts}`, head: head.key, cmd: head.command, turn: row.turn ?? null,
    provider: state.providerOf[head.key] ?? null,
    ts: new Date(row.ts), sid, outcome: row.outcome, cause: row.cause ?? null,
    model: row.model ? state.modelLabel[row.model] ?? row.model : null, modelId: row.model ?? null,
    account: row.account ?? null, compact: row.compact === true,
    attempts: row.attempts ?? 0, retries: row.retries ?? 0,
    total: row.total ?? 0, ttfb: row.first_byte ?? null, ftok: row.first_delta ?? null,
    queued: row.admit_wait_ms ?? 0,
    silent: waited ? waited.ms : 0, silentFrom: waited ? waited.at - started : 0,
    resumed, resumedAt: resumed && waited ? waited.at + waited.ms - started : 0, resumedFresh: fresh, stallMs: stall,
    // the cache-write figure is LEFT OUT when the row carries none, like the other two: a refused request reports no
    // tokens at all (no cache_write_tokens key on any error:rate-limited or error:plan-limit row in the live file),
    // and the open request drew "Cache write 0" over it, which is a measurement nobody made. `cached` keeps its zero:
    // it is never printed, only compared, and it decides whether the "% cached" line is drawn at all.
    in: row.in_tokens ?? null, cached: row.cached_tokens ?? 0, write: row.cache_write_tokens ?? null, out: row.out_tokens ?? null,
    cost: row.cost_usd ?? null, reason: row.cost_reason ?? null,
    reset: row.earliest_reset_epoch_seconds ? new Date(row.earliest_reset_epoch_seconds * 1000) : null,
    // the spent window's length as the provider's refusal named it, null when it named none (limit_window_seconds)
    window: row.limit_window_seconds ?? null,
    // whether the command was saving when this ran: false only when the row says it was not (capture: false)
    saving: row.capture === false ? false : null,
    // what the provider answered, kept on the row even when no body was (provider_status, provider_message)
    providerStatus: row.provider_status ?? null, providerMessage: row.provider_message ?? null,
    // the project is the session's repo, named as Sessions names it (its root's last folder); /api/sessions carries the
    // root and never a name (SessionsRoutes repoJson), and /api/projects ids are roots, so a link may name either
    repo: repoName(state.sessions[sid]?.repo?.root), repoRoot: state.sessions[sid]?.repo?.root ?? null,
  };
}
const repoName = (root) => (root || "").split("/").filter(Boolean).pop() || null;

/** One search hit as a row. A hit carries its turn's own ending, so a request older than the perf window the page
 *  holds is drawn from the hit alone (TraceSearch.kt) — never dropped because the rows do not reach it. */
// A SNIPPET IS ONE LINE OF READING, not a slice of JSON. The body the daemon searched is a JSON string, so the line
// breaks inside it stand there as the two characters \ and n; the daemon collapses real whitespace and cannot collapse
// those. Left alone the list read "t.*\n\n**Amendment X.** Powers this Constitution..." where the words should be.
// A conversation is JSON inside JSON, so a break can stand as one backslash or several; any run of them before n, r or t
// is whitespace, and a run before a quote is the quote.
const oneLine = (text) => String(text ?? "").replace(/\\+[nrt]/g, " ").replace(/\\+"/g, '"').replace(/\s+/g, " ").trim();

function mapHit(head, hit) {
  const row = {
    ts: hit.ts, total: hit.total, first_byte: hit.first_byte, first_delta: hit.first_delta, admit_wait_ms: hit.admit_wait_ms,
    in_tokens: hit.in_tokens, out_tokens: hit.out_tokens, cached_tokens: hit.cached_tokens, cache_write_tokens: hit.cache_write_tokens,
    outcome: hit.outcome ?? "ok", model: hit.model, session: hit.session, compact: hit.compact, attempts: hit.attempts,
    turn: hit.turn, cost_usd: hit.cost_usd, cost_reason: hit.cost_usd == null ? "uncounted" : null,
  };
  return { ...mapRow(head, row), said: { where: hit.where, text: oneLine(hit.text) } };
}

/** The search over what was sent and what came back, one head at a time, each saying how far back it read. */
async function readSearch() {
  if (ui.q.length < SEARCH_MIN) { state.search = null; return; }
  const heads = askedHeads();
  const q = encodeURIComponent(ui.q);
  const reads = await Promise.all(heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/trace/search?q=${q}&since=${since()}&limit=100`)));
  const hits = [], errors = [];
  let backTo = null, stopped = null;
  heads.forEach((head, i) => {
    const res = reads[i];
    if (!res.ok) { errors.push(refusalOf(res, `${head.command}\u2019s prompts and answers could not be searched`)); return; }
    for (const hit of res.body?.hits || []) hits.push(mapHit(head, hit));
    // a head that stopped early sets the floor; one that read all it keeps reaches nothing older and sets none
    if (res.body?.stopped_on) {
      stopped = res.body.stopped_on;
      if (res.body.back_to_epoch_ms != null) backTo = Math.min(backTo ?? Infinity, res.body.back_to_epoch_ms);
    }
  });
  state.search = { hits, errors, backTo, stopped };
}

/** The list's rows: the window's requests, with a search's own hits folded in. A hit on a request already in the
 *  window marks that row; a hit on an older one is a row of its own, so a search reaches past what the rows hold. */
function searched() {
  if (!state.search) return state.rows;
  const byTurn = new Map(state.rows.map((r) => [r.id, r]));
  const out = [], seen = new Set();
  for (const hit of state.search.hits) {
    const known = byTurn.get(hit.id);
    if (known) { known.said = hit.said; out.push(known); } else out.push(hit);
    seen.add(hit.id);
  }
  // an agent is searched for by its name too: every request of a session whose name holds the words (Marlin's walk:
  // "infra" read No match with infra's rows in the list)
  const q = ui.q.toLowerCase();
  for (const r of state.rows) if (!seen.has(r.id) && state.sessions[r.sid]?.name?.toLowerCase().includes(q)) out.push(r);
  return out;
}

// ---------- what the daemon cannot filter: a project, a stretch of time and what happened ----------
// These narrow the rows the read already cut. The read asks for the window's newest 2,000, so a narrow one of these
// says how many of the window it had to look at, rather than reading as the whole window's answer.
const narrow = (rows) => rows.filter((r) => (!ui.repo || r.repo === ui.repo || r.repoRoot === ui.repo)
  && (!ui.from || (+r.ts > ui.from && +r.ts <= (ui.to ?? +NOW)))
  && (!ui.why || WHY[ui.why].has(r)));
const filtered = () => narrow(searched());

// AN EMPTY WINDOW WHOSE OLDER ROWS MATCH says how many and widens in one tap, for every door and filter (Marlin, after
// the Started over door opened on No match in 24 hours with 31 in the week; hitstop f80553d). One read at the widest
// window with the same filters, narrowed as the list is, then counted for each wider window, nearest first. A head whose
// read held its cap may not reach back to a window's start, and that window's figure is then a floor, drawn with a plus.
// A search or a named stretch of time is not widened: the search reads its own records, and a stretch is its own window.
async function readWider() {
  state.wider = null;
  const keys = Object.keys(WINDOWS), later = keys.slice(keys.indexOf(ui.win) + 1);
  if (!later.length || ui.q || ui.from || state.headErrors.length || filtered().length) return;
  const heads = askedHeads(), widest = later[later.length - 1];
  const reads = await Promise.all(heads.map((h) => API.get(turnsPath(h, true, widest))));
  const rows = [];
  let reach = -Infinity; // the latest point a capped head's read stopped at: older than this, its rows weren't carried
  for (const [i, head] of heads.entries()) {
    const answer = (reads[i].body?.heads || []).find((x) => x.key === head.key);
    if (!reads[i].ok || !answer || answer.error) return; // a count missing one command would be a wrong count
    const mine = (answer.rows || []).map((row) => mapRow(head, row));
    if (answer.truncated && mine.length) reach = Math.max(reach, Math.min(...mine.map((r) => +r.ts)));
    rows.push(...mine);
  }
  const kept = narrow(rows);
  for (const win of later) {
    const start = +NOW - WINDOWS[win][1], n = kept.filter((r) => +r.ts > start).length;
    if (n) { state.wider = { win, n, floor: reach > start }; return; }
  }
}
const uniq = (xs) => [...new Set(xs)];

function pickBtn(key, label, on) {
  return `<span class="fwrap"><button class="fbtn${on ? " on" : ""}" data-act="menu" data-m="${key}" aria-haspopup="menu" aria-expanded="${ui.menu === key}">${label}${DOWN}</button>`
    + `${on ? `<button class="fclear" data-act="unset" data-m="${key}" aria-label="Clear">${ICON.close}</button>` : ""}${ui.menu === key ? menuHtml(key) : ""}</span>`;
}
function menuHtml(key) {
  const item = (v, label, cur, extra = "") => `<button role="menuitemradio" aria-checked="${cur}" data-act="set" data-m="${key}" data-v="${esc(v)}">${extra}${label}</button>`;
  if (key === "win") return `<div class="menu fmenu" role="menu">${Object.entries(WINDOWS).map(([k, [w]]) => item(k, w, ui.win === k)).join("")}</div>`;
  if (key === "cmd") return `<div class="menu fmenu" role="menu">${item("", "Every command", !ui.cmd)}${state.heads.map((h) => item(h.command, esc(h.command), ui.cmd === h.command, `<i class="blot" style="--c:${colorOf(state.providerOf[h.key])}"></i>`)).join("")}</div>`;
  // the requests splice couldn't put a model or an account on (unattributed=model|account, TurnsFilter.kt)
  if (key === "model") return `<div class="menu fmenu" role="menu">${item("", "Every model", !ui.model)}${state.facets.models.map(([id, label]) => item(id, esc(label), ui.model === id)).join("")}${item("none", "No model", ui.model === "none")}</div>`;
  if (key === "account") { // an account on two commands is two accounts: a key's own variable and day, a plan's label
    return `<div class="menu fmenu" role="menu">${item("", "Every account", !ui.account)}`
      + state.facets.accounts.map(([k, { head, account }]) => item(k, `<span class="aname">${acctHtml({ account })}<span class="aprov">${esc(head.command)}</span></span>`, ui.account === k)).join("")
      + `${item("none", "No account", ui.account === "none")}</div>`;
  }
  if (key === "sid") { // the sessions with requests in the window, the latest first, each with its command's colour
    // A day holds more sessions than a menu can be read through — 1,559 on one live day — so this lists the ones he
    // worked in most recently and says how many it left out, rather than a scroll nobody can find a session in.
    const all = state.facets.sessions.slice().sort((a, b) => (b[1].last ?? 0) - (a[1].last ?? 0));
    const rows = all.slice(0, SESSIONS_IN_MENU);
    const rest = all.length - rows.length;
    return `<div class="menu fmenu sessions-menu" role="menu">${item("", "Every session", !ui.sid)}`
      + rows.map(([sid, { head, last }]) => item(sid, `<span class="sname">${esc(sessTitle(sid))}</span><span class="swhen">${last ? `${dayOf(new Date(last))}${clock(new Date(last))}` : ""}</span>`, ui.sid === sid,
        `<span class="blots"><i class="blot" style="--c:${colorOf(state.providerOf[head.key])}"></i></span>`)).join("")
      + (rest ? `<p class="mfoot">${n0(rows.length)} of ${n0(all.length)}, the ones worked in most recently</p>` : "") + `</div>`;
  }
  return "";
}
function filtersHtml() {
  const out = ui.outcome;
  const sw = `<div class="switch" role="group" aria-label="Outcome">${[["all", "All"], ["fail", "Failed"], ["stopped", "Stopped"]].map(([v, w]) => `<button data-act="outcome" data-v="${v}" aria-pressed="${out === v}">${w}</button>`).join("")}</div>`;
  // Started over happens during a request that can still end clean, so it is not an outcome (fin) but a door of its
  // own beside the switch (Marlin, p157; hitstop f80553d): the why filter, pressed when a door brings it, and it
  // combines with Failed and Stopped. Its toggle is its chip, so no second chip is drawn for it.
  const over = `<button class="ftog" data-act="why" data-v="startedover" aria-pressed="${ui.why === "startedover"}"><i></i>${WHY.startedover.word}</button>`;
  const tog = (key, label, on) => `<button class="ftog" data-act="toggle" data-m="${key}" aria-pressed="${on}"><i></i>${label}</button>`;
  const acct = ui.account && ui.account !== "none" ? state.facets.accounts.find(([k]) => k === ui.account) : null;
  return `<div class="filters">${pickBtn("win", WINDOWS[ui.win][0], false)}`
    + pickBtn("cmd", ui.cmd ? `<i class="blot" style="--c:${colorOf(providerOfCmd(ui.cmd))}"></i>${esc(ui.cmd)}` : "Command", Boolean(ui.cmd))
    + pickBtn("sid", ui.sid ? esc(sessTitle(ui.sid)) : "Session", Boolean(ui.sid))
    + pickBtn("model", ui.model ? (ui.model === "none" ? "No model" : esc(state.modelLabel[ui.model] ?? ui.model)) : "Model", Boolean(ui.model))
    + pickBtn("account", ui.account ? (ui.account === "none" ? "No account" : acctHtml({ account: acct?.[1].account ?? "" })) : "Account", Boolean(ui.account))
    + (ui.repo ? `<span class="fwrap"><span class="fbtn on">${esc(repoName(ui.repo))}</span><button class="fclear" data-act="unset" data-m="repo" aria-label="Clear">${ICON.close}</button></span>` : "")
    + (ui.from ? `<span class="fwrap"><span class="fbtn on">${dayOf(new Date(ui.from + 1))}${ui.to ? `${clock(new Date(ui.from))} to ${clock(new Date(ui.to))}` : `Since ${clock(new Date(ui.from))}`}</span><button class="fclear" data-act="unset" data-m="from" aria-label="Clear">${ICON.close}</button></span>` : "")
    + (ui.why && ui.why !== "startedover" ? `<span class="fwrap"><span class="fbtn on">${esc(WHY[ui.why].word)}</span><button class="fclear" data-act="unset" data-m="why" aria-label="Clear">${ICON.close}</button></span>` : "")
    + `${sw}${over}${tog("compact", "Compactions", ui.compact)}</div>`;
}

// ---------- the list: the newest first, on the rail, an hour mark where the hour turns ----------
function rowHtml(r) {
  const o = outcomeOf(r), h = r.said;
  const lamp = r.compact ? GLYPH.compact : o.glyph ? GLYPH[o.glyph] : "<i></i>";
  const word = (o.word ? `<span class="word ${o.cls}">${o.word}</span>` : r.compact ? `<span class="word">Compaction</span>` : "") + besideOf(r);
  // a figure the provider never reported is left out, never printed as 0
  const tokens = (r.in != null ? `<span class="tok">${kTok(r.in)} tokens in${r.in && r.cached / r.in >= .5 ? `<em>${Math.round((r.cached / r.in) * 100)}% cached</em>` : ""}</span>` : "")
    + (r.out != null ? `<span class="tok">${kTok(r.out)} out</span>` : "");
  const match = h ? `<p class="match"><span class="where">${h.where === "sent" ? "Sent" : "Returned"}</span>${markIn(`… ${h.text} …`)}</p>` : "";
  return `<article class="card rq ${o.cls}${r.compact ? " compact" : ""}" style="--c:${colorOf(r.provider)}" data-open="${esc(r.id)}" aria-current="${ui.open === r.id}">`
    + `<span class="lamp ${o.cls}${r.compact ? " compact" : ""}" aria-hidden="true">${lamp}</span>`
    + `<div class="top"><time>${clockS(r.ts)}</time><button class="name" data-open="${esc(r.id)}">${esc(sessTitle(r.sid))}</button>${word}`
    + `<span class="dur">${secs(r.total)}</span><span class="cost${r.cost == null ? " gap" : ""}">${costText(r)}</span></div>`
    + `<div class="meta"><span class="chip">${esc(r.cmd)}</span><span>${esc(r.model ?? "No model")}</span>${r.account ? `<span class="acct" title="${esc(r.account)}">${acctHtml(r)}</span>` : ""}${tokens}</div>${match}</article>`;
}
const markIn = (t) => RV.markIn(t);
// A WINDOW CAN HOLD MORE REQUESTS THAN ONE READ CARRIES. The daemon answers with the newest 2,000 of each command and
// says how many matched (PerfRoutes.kt, MAX_TURNS); on a live day one command's 24 hours held 19,842. Without this the
// list reads "200 of 2,000" on a window of twenty thousand, and turning Steps off changes nothing a reader can see
// while the daemon's own count moves by nine thousand.
function heldFoot() {
  if (!state.capped) return "";
  const n = state.matched;
  return `<p class="foot held">The newest ${n0(MAX_HELD)} of each command. ${n0(n)} request${n === 1 ? "" : "s"} in ${WINDOWS[ui.win][0].toLowerCase()}.</p>`;
}
// A SEARCH THAT READ EVERYTHING KEPT HAS NO FOOTER: its results are whole (fin, Oct 10). One that stopped early says the
// oldest record it actually read, which can never be later than a hit above it. fin's "Search further" waits on the
// route: /trace/search takes q, since and limit and nothing to resume from (TraceMount.kt), so there is no read to
// continue and no button that would do nothing.
// p157 read "Searched back to 1:34 PM, splice keeps them for 7 days" over a 10:12 AM hit while Settings said Forever:
// the time was the NEWEST of the heads' stopping points, and the window was a second figure that contradicted Settings.
// The time is now the OLDEST any head reached, and no keep-for figure appears here at all; the window lives on Settings.
function searchFoot() {
  const s = state.search;
  if (!s?.stopped || s.backTo == null) return "";
  return `<p class="foot searched">Searched back to ${dayOf(new Date(s.backTo))}${clock(new Date(s.backTo))}</p>`;
}
function listHtml() {
  const rows = filtered().slice().sort((a, b) => +b.ts - +a.ts);
  const errs = state.headErrors.concat(state.search?.errors || [], state.sessionsError ? [state.sessionsError] : []);
  const trouble = errs.length ? `<div class="list-err">${errs.map((e) => `<p>${esc(e)}</p>`).join("")}</div>` : "";
  if (state.loading) return `<div class="list"><div class="nomatch"><span class="state">Reading</span></div></div>`;
  if (state.error) return `<div class="list"><div class="nomatch"><span class="state">${esc(state.error)}</span></div></div>`;
  if (!rows.length && ui.q) return `<div class="list">${trouble}<div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clearall">Clear</button></div>${searchFoot()}</div>`;
  if (!rows.length) {
    const w = state.wider, widen = w ? `<button class="act" data-act="widen" data-v="${w.win}">${n0(w.n)}${w.floor ? "+" : ""} in the last ${WINDOWS[w.win][0]}</button>` : "";
    return `<div class="list">${trouble}<div class="nomatch"><span class="state">${anyFilter() ? "No match" : "No requests"}</span>${widen}${anyFilter() ? `<button class="act quiet" data-act="clearall">Clear</button>` : ""}</div></div>`;
  }
  const shown = rows.slice(0, ui.n), items = [];
  let hour = null;
  for (const r of shown) {
    const hk = `${r.ts.toDateString()} ${r.ts.getHours()}`;
    if (hk !== hour) { hour = hk; const h = r.ts.getHours(); items.push(`<div class="hour"><span>${dayOf(r.ts)}${h % 12 || 12} ${h < 12 ? "AM" : "PM"}</span></div>`); }
    items.push(rowHtml(r));
  }
  const more = rows.length > shown.length ? `<div class="more"><span class="count">${n0(shown.length)} of ${n0(rows.length)}</span><button class="act quiet" data-act="more">Show ${n0(Math.min(200, rows.length - shown.length))} more</button></div>`
    : "";
  return `<div class="list">${trouble}${items.join("")}${more}${heldFoot()}${searchFoot()}</div>`;
}
const anyFilter = () => Boolean(ui.cmd || ui.sid || ui.model || ui.account || ui.repo || ui.from || ui.why || ui.compact || ui.outcome !== "all" || ui.q);

// ---------- the open request: its own records, as its trace kept them ----------
// kept or cut: the records are there. unsaved, deleted, expired or norecords: only the perf row is, and the detail says
// which ONCE, in fin's words (Oct 10), each true to a cause splice measured:
//   unsaved    the row says its command was not saving when it ran (capture: false, TurnTelemetry): "Not saved"
//   deleted    the daemon says he deleted them: "Deleted"
//   expired    older than the command's keep-for: "Older than N days"
//   norecords  saving was on, or the row predates the fact, and the records are not there: "No records". Never a
//              cause, because saving was on (Marlin, Oct 10).
// The command's capture switch NOW is never read as the cause: a request older than a toggle ran under the old setting.
const traces = new Map(); // one read per request, held while the page is open
function traceState(r) {
  const held = traces.get(r.id);
  if (held?.state) return held.state;
  if (r.saving === false) return "unsaved";
  // a row that HAS a trace turn is being read, and nothing is drawn as missing while that read is in flight
  return r.turn ? "reading" : "norecords";
}
const traceOf = (r) => traces.get(r.id)?.tr ?? null;

/** Reads one request's records, then draws it again. A refusal becomes the state the view draws, in its own words. */
async function readTrace(r) {
  if (!r.turn || traces.has(r.id)) return;
  traces.set(r.id, { state: "reading", tr: null });
  const res = await API.get(`/api/heads/${encodeURIComponent(r.head)}/trace?turn=${encodeURIComponent(r.turn)}`);
  const records = res.ok ? res.body?.records || [] : [];
  if (res.ok && records.length) {
    const tr = traceFrom(records, r);
    traces.set(r.id, { state: tr.cut ? "cut" : "kept", tr });
  } else {
    traces.set(r.id, { state: refusedState(res, r), tr: null });
  }
  render();
}

/** Which state a refused read is in, from the daemon's own answer: the files he deleted, or the days the command no
 *  longer keeps. Anything else is "No records": a cause this page cannot establish is not worded. */
function refusedState(res, r) {
  const why = res.body?.error || "";
  if (why.includes("trace deleted")) return "deleted";
  const days = state.capture[r.head]?.days;
  if (days != null && +r.ts < +NOW - days * 24 * 3600e3) return "expired";
  return "norecords";
}

/** The one line the detail says when the records are not here, with the one control that changes it next time. */
function goneHtml(r, st) {
  const line = (word, act = "") => `<div class="gone"><span class="state">${word}</span>${act}</div>`;
  if (st === "deleted") return line("Deleted");
  if (st === "norecords") return line("No records");
  if (st === "expired") {
    const days = state.capture[r.head]?.days;
    return line(`Older than ${n0(days)} ${days === 1 ? "day" : "days"}`, `<a class="act quiet" href="settings.html#data">Keep longer</a>`);
  }
  // Not saved: while the command is still not saving, the button that starts it; once it saves, the word alone. The
  // switch applies to the command's next request with no restart (builder2, 993f070cb), so the button is back.
  const said = state.saving[r.head];
  const act = said ? `<span class="${said.failed ? "detail" : "said"}">${esc(said.word)}</span>`
    : state.capture[r.head]?.on === false ? `<button class="act quiet" data-act="startsave" data-h="${esc(r.head)}">Start saving</button>` : "";
  return line("Not saved", act);
}


/** Start saving: this command's Prompts and answers on, the same write Your data makes (PUT /capture). The daemon
 *  takes it on the command's next request and says so (restart_required false, CaptureRoutes.kt). A restart answer
 *  would be a daemon bug, not a state (fin): the row stays "Not saved" and the answer goes to the log. */
async function startSaving(head) {
  state.saving[head] = { word: "Saving" };
  render();
  const res = await API.put(`/api/heads/${encodeURIComponent(head)}/capture`, { enabled: true });
  if (!res.ok) { state.saving[head] = { word: refusalOf(res, "Saving could not be started"), failed: true }; render(); return; }
  state.capture[head] = { ...state.capture[head], on: true };
  if (res.body?.restart_required) { delete state.saving[head]; console.warn("capture switch answered restart_required", head, res.body); render(); return; }
  // Settings' word for a change the daemon took live (knobs.js), so the same fact reads the same on both pages
  state.saving[head] = { word: "Applied" };
  render();
}

/** The records as the view reads them: what Claude Code sent, every send upstream on the rail, and what came back. */
function traceFrom(records, r) {
  const turn = records.find((x) => x.kind === "turn");
  const started = +r.ts - r.total;
  let cut = Boolean(turn?.client?.truncated || turn?.answer?.truncated);
  const attempts = records.filter((x) => x.kind === "attempt").map((a) => {
    if (a.request?.truncated || a.response?.truncated) cut = true;
    const text = a.response?.text ?? "";
    const parsed = framesOf(text, a.transport);
    return {
      url: a.url ?? null, transport: a.transport ?? "http",
      start: Math.max(0, (a.ts ?? started) - (a.durationMs ?? 0) - started), ms: a.durationMs ?? 0,
      status: a.response?.status ?? null, headers: a.request?.headers ?? {}, body: jsonOf(a.request?.body),
      resHeaders: a.response?.headers ?? null, ...parsed,
      failure: a.failure ?? null, lock: a.response?.status === 429,
    };
  });
  const answer = turn?.answer;
  const back = answer?.stream ? framesOf(answer.body ?? "", "sse") : { body: jsonOf(answer?.body) };
  return { cut, sent: { headers: turn?.client?.headers ?? {}, body: jsonOf(turn?.client?.body) }, attempts,
    returned: { status: answer?.status ?? null, ...back } };
}

const jsonOf = (text) => { if (text == null) return null; try { return JSON.parse(text); } catch { return text; } };
/** A recorded answer as frames: an event stream's events, or the JSON objects a websocket round parsed, one per line.
 *  Text that is neither is handed on as it came, never reshaped into frames it does not have. */
function framesOf(text, transport) {
  if (!text) return { raw: null };
  if (transport === "ws") {
    const frames = text.split("\n").filter((l) => l.trim()).map((l) => { const data = jsonOf(l); return { event: data?.type ?? "frame", data }; });
    return frames.length ? { frames } : { raw: jsonOf(text) };
  }
  if (!/^(event|data):/m.test(text)) return { raw: jsonOf(text) };
  const frames = [];
  for (const block of text.split(/\n\n+/)) {
    if (!block.trim()) continue;
    let event = null, data = [];
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).trim());
    }
    const joined = data.join("\n");
    if (event || joined) frames.push({ event: event ?? jsonOf(joined)?.type ?? "data", data: joined === "[DONE]" ? joined : jsonOf(joined) });
  }
  return frames.length ? { frames } : { raw: jsonOf(text) };
}

// A LINK IS THERE ONLY ONCE ITS PAGE IS (Marlin, Oct 10), and the kit's PAGES is the one place that knows: this draws
// the moment Sessions lands and not an hour before, so it is never a 404 with an empty body.
const openSession = (r) => (r.sid && PAGES.some(([file]) => file === "sessions.html")
  ? `<a class="act" href="sessions.html#${esc(r.sid)}">Open session</a>` : "");

function paneHtml() {
  const r = filtered().find((x) => x.id === ui.open) ?? state.rows.find((x) => x.id === ui.open);
  if (!r) return "";
  const o = outcomeOf(r), lamp = r.compact ? GLYPH.compact : o.glyph ? GLYPH[o.glyph] : "<i></i>";
  const word = (o.word ? `<span class="word ${o.cls}">${o.word}</span>` : r.compact ? `<span class="word">Compaction</span>` : "") + besideOf(r);
  const priced = r.reason !== "unanswered"; // a request nothing answered has no cost to show
  const st = traceState(r);
  // What the provider answered, kept on the row whether or not the bodies were, so it shows in every trace state
  // (Marlin item 2; hitstop 442e831). The provider by Accounts' name, its status, its own words. A status of 200 is left
  // out, because the failure came inside the stream and a 200 beside it misleads. Nothing answered, no line.
  const code = r.providerStatus != null && r.providerStatus !== 200 ? r.providerStatus : null;
  const who = PROVIDER_NAME[r.provider] ?? r.cmd;
  // A send that got NO answer is splice's side to tell, because "was it splice or the provider?" is the first question
  // (Marlin; hitstop 56e1fe4). provider_status is absent both for a dropped connection and for a request splice held
  // before sending (PerfKeys.PROVIDER_STATUS), so the line also needs a send (an attempt) and no first byte. An ending
  // splice decided itself draws no line: its outcome word already says who and why (fin).
  const unanswered = o.cls === "fail" && !SPLICE_ENDED.has(r.outcome) && r.providerStatus == null && !r.providerMessage
    && r.attempts > 0 && r.ttfb == null;
  const said = r.providerMessage || code != null
    ? `<p class="upmsg"><span class="who">${esc(who)}</span>${code != null ? `<span class="code">${code}</span>` : ""}`
      + `${r.providerMessage ? `<q>${esc(r.providerMessage)}</q>` : ""}</p>`
    : unanswered ? `<p class="upmsg"><span class="who">${esc(who)}</span><span class="state">No answer</span></p>` : ""; // fin's words, hitstop f4581a6
  return RV.paneHtml(r, { lampCls: `${o.cls}${r.compact ? " compact" : ""}`, lamp, word, name: sessTitle(r.sid), acct: r.account ? acctHtml(r) : "",
    acts: `${openSession(r)}<button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button>`,
    st, gone: RV_KEPT(st) || st === "reading" ? "" : goneHtml(r, st), said,
    tr: () => traceOf(r), cells: priced ? ["in", "write", "out", "cost", "attempts"] : ["in", "write", "out", "attempts"], cost: costText(r) });
}

// ---------- render ----------
function render({ keep = true } = {}) {
  const sc = root.querySelector(".rqpane .scroll"), top = keep && sc ? sc.scrollTop : 0;
  // THE LAYOUT FOLLOWS THE PANE THAT DREW, never the request the page meant to hold open. A request stays open
  // through a filter that excludes it (paneHtml reads past the filter on purpose), but a reload whose window no
  // longer carries its row at all leaves nothing to draw — and the two-column layout then stood with an empty
  // second column, 2,660 px of nothing beside the list at 3,828. One answer decides both, so they cannot disagree.
  const pane = paneHtml();
  if (!pane) ui.open = null;
  root.className = `requests${pane ? " open" : ""}`;
  document.getElementById("filters").innerHTML = state.loading ? "" : filtersHtml();
  root.innerHTML = listHtml() + pane;
  const nsc = root.querySelector(".rqpane .scroll");
  if (nsc) nsc.scrollTop = top;
  const m = document.querySelector(".fmenu");
  if (m) m.querySelector("[aria-checked=true]")?.focus({ preventScroll: true });
}
const find = (id) => filtered().find((r) => r.id === id);
function open(id) {
  // where he was in the list, so closing the request puts him back there (Marlin's walk, p161)
  if (!ui.open) ui.listAt = { y: scrollY, list: root.querySelector(".list")?.scrollTop ?? 0 };
  ui.open = id; ui.auto = false; ui.tab = ui.tab || "sent";
  render({ keep: false });
  const r = find(id);
  if (r) readTrace(r);
}

/** Every read this page makes, then one draw. The window and the filters the daemon owns are read again each time. */
async function reload({ standing = false } = {}) {
  tick(); // the kit's clock, read again so a reset reads against the time the page is drawn at
  state.loading = standing;
  if (standing) render();
  if (standing) await readStanding();
  await Promise.all([readRows(), readSearch()]);
  await readWider();
  state.loading = false;
  autoOpen();
  render({ keep: false });
  const r = ui.open && find(ui.open);
  if (r) readTrace(r);
}

// a search is sent to the daemon, so the page waits for him to stop typing rather than asking on every letter
let typing = null;
document.getElementById("q").addEventListener("input", (e) => {
  ui.q = e.target.value.trim(); ui.n = 200;
  clearTimeout(typing);
  typing = setTimeout(() => { ui.auto = true; reload(); }, 250);
});
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && ui.menu) { ui.menu = null; render(); } });
document.addEventListener("click", (e) => {
  const el = e.target.closest("[data-act], [data-open]");
  if (ui.menu && !e.target.closest(".fmenu, [data-act=menu]")) { ui.menu = null; if (!el) { render(); return; } }
  if (!el) return;
  if (!el.dataset.act) { if (e.target.closest("a")) return; open(el.dataset.open); return; }
  const v = el.dataset.v, m = el.dataset.m;
  switch (el.dataset.act) {
    case "theme": document.documentElement.dataset.theme = document.documentElement.dataset.theme === "day" ? "night" : "day"; break;
    case "menu": ui.menu = ui.menu === m ? null : m; render(); break;
    case "set": ui[m] = v || null; if (m === "win") ui.win = v; ui.menu = null; ui.n = 200; again(); break;
    case "unset": ui[m] = null; if (m === "from") ui.to = null; ui.n = 200; again(); break;
    case "outcome": ui.outcome = v; ui.n = 200; again(); break;
    case "startsave": startSaving(el.dataset.h); break;
    case "why": ui.why = ui.why === v ? null : v; ui.n = 200; again(); break;
    case "widen": ui.win = v; ui.n = 200; again(); break;
    case "toggle": ui[m] = !ui[m]; ui.n = 200; again(); break;
    case "clearall": Object.assign(ui, { cmd: null, sid: null, model: null, account: null, repo: null, from: null, to: null, outcome: "all", why: null, compact: false });
      { const q = document.getElementById("q"); q.value = ""; ui.q = ""; } again(); break;
    case "more": ui.n += 200; render(); break;
    case "close": {
      ui.open = null; ui.auto = false; render();
      const at = ui.listAt; ui.listAt = null;
      if (at) { const list = root.querySelector(".list"); if (list) list.scrollTop = at.list; scrollTo(0, at.y); }
      break;
    }
    case "tab": case "node": case "frames": RV.click(el); render({ keep: true }); break; // the open request's own controls (reqview.js)
  }
});
// a filter the daemon owns is a new read; one that hides the open request moves the page to the top match when the
// page opened it, and keeps his pick otherwise
function again() { ui.auto = ui.auto || !filtered().some((r) => r.id === ui.open); reload(); }
const autoOpen = () => {
  if (wide() && ui.auto) ui.open = filtered().slice().sort((a, b) => +b.ts - +a.ts)[0]?.id ?? null;
  else if (!wide() && ui.auto) ui.open = null;
};
// a window widened past side-by-side opens the top request, and that request's records are read like any other open
// (the 3828 look, Oct 10: the pane sat on Reading because nothing asked)
addEventListener("resize", () => { autoOpen(); render(); const r = ui.open && find(ui.open); if (r) readTrace(r); });

// A door from another page carries its filters in the address (?win=7d&cmd=claude-grok&why=cut): the list opens
// filtered, with each filter as its chip, and the top match open where there's room. The project is spelled
// `project`, the word every other screen uses for it (a chart column on Usage opens
// ?project=splice&from=…&to=…); `repo` is the same filter under the name the session record gives it, kept so an
// older link still opens. A from-to span names epoch milliseconds, half-open as the daemon reads it, and `to`
// left out means "through now" rather than an open end nobody can see.
const ADDR = new URLSearchParams(location.search);
if (WINDOWS[ADDR.get("win")]) ui.win = ADDR.get("win");
for (const k of ["cmd", "model", "account", "session"]) if (ADDR.get(k)) ui[k === "session" ? "sid" : k] = ADDR.get(k);
ui.repo = ADDR.get("project") ?? ADDR.get("repo") ?? ui.repo;
if (ADDR.get("from")) { ui.from = +ADDR.get("from"); ui.to = ADDR.get("to") ? +ADDR.get("to") : null; } // no end: through now
if (WHY[ADDR.get("why")]) ui.why = ADDR.get("why"); // Started over's door arrives with its toggle pressed
if (["clean", "stopped", "fail"].includes(ADDR.get("outcome"))) ui.outcome = ADDR.get("outcome");
if (ADDR.get("q")) { ui.q = ADDR.get("q"); document.getElementById("q").value = ui.q; }
// a door that names one request shows that request, never the top row
const asked = location.hash.slice(1);
ui.auto = !asked;
reload({ standing: true }).then(() => {
  if (!asked) return;
  const r = state.rows.find((x) => x.id === asked || x.turn === asked);
  if (!r) return;
  const at = filtered().slice().sort((a, b) => +b.ts - +a.ts).findIndex((x) => x.id === r.id);
  if (at >= ui.n) { ui.n = Math.ceil((at + 1) / 200) * 200; }
  open(r.id);
  requestAnimationFrame(() => document.querySelector(`[data-open="${CSS.escape(r.id)}"]`)?.scrollIntoView({ block: "center", behavior: "instant" }));
});
