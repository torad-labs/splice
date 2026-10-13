// Requests: every request a command sent upstream, found by session, searched by what was sent and what came back,
// filtered, and opened: its attempts on one time rail, the bodies both ways, its tokens and what it cost. The drawing
// (Splice-Animator design/console, d3338fa) on splice's own answers: every name, number and state below is read from
// the control API, and nothing on this page is invented.
//
// WHERE EVERY VALUE COMES FROM.
//   GET /api/heads · /api/models            the commands, each one's provider, and its models' own labels
//   GET /api/heads/retired                  the commands removed from Models, each its key and name: their requests stay
//                                           listed, searched and opened ("History stays", Marlin)
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
const GLYPH_OF = { "error:cancelled": "bang", "error:restarted": "bang", "error:at-capacity": "limit", "error:turn-cap": "bang", "error:plan-limit": "limit", "error:rate-limited": "limit", "error:rate-limit-relayed": "limit",
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
// The row names what happened, not the tag splice handed Claude Code: a cut stream is Cut off, a stall splice gave up on
// is the give-up's word, and a provider error the provider answered with a 429 is the rate limit it was, under the
// limit's glyph (Marlin, desk c, Oct 12: 43 rows read "Provider error" over a provider message of "Error").
function outcomeOf(r) {
  const base = OUTCOME[r.outcome];
  if (r.outcome === "failure:overloaded_error" && isCutOff(r)) return { ...base, word: WHY.cutoff.word };
  if (r.outcome === "failure:overloaded_error" && isStalled(r)) return { ...base, word: OUTCOME_WORD["error:turn-cap"] };
  if (r.outcome === "error:upstream-failed" && r.cause === "VENDOR_RATE_LIMITED") return OUTCOME["error:rate-limited"];
  // the provider's plan limit names itself on the row, and the account beside it says whose plan; kit.js keeps "" for
  // Sessions, where "At limit" describes an account and not a request (fin, Oct 12)
  if (r.outcome === "error:plan-limit") return { ...base, word: "Plan limit" };
  return base || { cls: "fail", word: tagWord(r.outcome) ?? "", glyph: "bang" };
}
// What sits beside the word: the reset a spent window named, or the retries splice ran inside the request. THE ROW
// DECIDES, not the tag: splice writes the reset when it turned a request away because every account was spent
// (ExhaustedAccountAdmission.kt:32), and a request that met the window mid-flight ends error:plan-limit with no reset
// in its row at all — 156 of them in one live day. Reading the lock off the tag would promise a time no row holds.
// THE LOCK NAMES ITS WINDOW the way Accounts names its rows (accounts.js LEN: "5 hours", "Day", "Week", "Month"), from
// the length the provider's own refusal gave (limit_window_seconds), so the two pages agree by construction. A refusal
// that named no window names none and gives its reset alone (fin, Oct 10 and 12): p157 and p158 each called
// the lock's cause a guess when it gave a time and no window.
const WINDOW_WORD = { 18000: "5 hours", 86400: "Day", 604800: "Week", 2592000: "Month" };
// A refusal that named no window gives the reset alone: the row's word already says what stopped it (fin, Oct 12)
const lockWord = (r) => WINDOW_WORD[r.window] ?? "";
const resetWord = (r) => `${lockWord(r) ? `${lockWord(r)} · ` : ""}Resets ${dayOf(r.reset)}${clock(r.reset)}`;
// Then what splice did inside the request: a broken stream it resumed or started over, in the timeline's tick word and
// the tick's colour (.mend, hitstop 8405b48), so a walker finds docs-site without timing it (p157), and its retries.
const besideOf = (r) => {
  if (r.reset) return `<span class="detail">${ICON.lock}${resetWord(r)}</span>`;
  const mend = r.resumed ? `<span class="detail mend">${r.resumedFresh ? WHY.startedover.word : WHY.resumed.word}</span>` : "";
  return mend + (r.retries ? `<span class="detail">${r.retries} ${r.retries === 1 ? "retry" : "retries"}</span>` : "");
};
// why a request has no dollar figure (TurnPriceGap.kt), in fin's words; one that was never answered says nothing
const REASON = { plan: "On your plan", local: "This computer", vast: "vast.ai", uncounted: "Uncounted", undeclared: "No price" };
// A key is an account: its variable and the day splice first saw THAT key, "OPENROUTER_API_KEY · Sep 14" (fin's
// words). A row names the key it was sent with as `VARIABLE:fingerprint` (KeyAccount.kt), so a rotated key reads as
// its own account and the one it replaced keeps its own history, marked Replaced — on Requests as on Accounts
// (Marcos, Oct 8). A plan's label has no colon and no key behind it, and prints as it stands.
const keyNameOf = (account) => { const s = String(account ?? ""), at = s.lastIndexOf(":"); return at < 0 ? s : s.slice(0, at); };
// A key reads as its provider, the heading Accounts draws over it and the name Continue on uses; its variable joins
// only when two keys of that provider feed the same command, so the line says which one it sent (fin).
const acctHtml = (r) => {
  if (r.acctLabel) return esc(r.acctLabel); // a login reads the label the daemon names it by, never an email or id (Oct 12)
  const key = state.keySeen[r.account], name = key?.name ?? keyNameOf(r.account), provider = PROVIDER_NAME[r.provider];
  const isKey = Boolean(key) || /^[A-Z0-9_]+$/.test(name);
  if (!isKey || !provider) return key ? `<span class="var">${esc(key.name)}</span> · ${dayWord(key.seen)}${key.replaced ? `<span class="detail">Replaced</span>` : ""}` : esc(name || r.account);
  const both = (state.keysOfHead[r.head] ?? 0) > 1 ? ` · <span class="var">${esc(name)}</span>` : "";
  // a bare variable, no fingerprint after it, is a row from before splice tracked keys: say so, never "1" and "2"
  if (!key && !String(r.account).includes(":")) return `${esc(provider)}, before key tracking`;
  const when = key ? ` · ${dayWord(key.seen)}${key.replaced ? `<span class="detail">Replaced</span>` : ""}` : "";
  return `${esc(provider)}${both}${when}`;
};
// The menu's own reading of each account: two entries that read the same (one variable, a key splice never saw
// next to rows from before it tracked keys) are told apart by their order, "OpenRouter 1" and "OpenRouter 2",
// and never by the key itself.
const acctNames = () => {
  const html = state.facets.accounts.map(([k, { head, account, label }]) => [k, acctHtml({ account, acctLabel: label, head: head.key, provider: state.providerOf[head.key] }), head.key]);
  const same = (h, head) => html.filter(([, x, hk]) => x === h && hk === head);
  const seen = {}, out = new Map();
  for (const [k, h, head] of html) {
    const n = same(h, head).length;
    seen[head + h] = (seen[head + h] ?? 0) + 1;
    out.set(k, n > 1 ? `${h} ${seen[head + h]}` : h);
  }
  return out;
};
// a request that failed before any model answered was not charged (TurnPriceGap's unanswered): it cost $0, which is a
// fact, never "No price", which is a model answering with no rate card (Marlin, p229 on desk a, Oct 12)
const costText = (r) => (r.cost != null ? money(r.cost) : r.reason === "unanswered" ? money(0) : REASON[r.reason] ?? "");
const DOWN = G('<path d="M6 9l6 6 6-6"/>', 'class="down" aria-hidden="true"');

// ---------- the filters, as /api/perf/turns takes them (TurnsFilter.kt), plus the search over bodies ----------
const WINDOWS = { "1h": ["1 hour", 3600e3], "5h": ["5 hours", 5 * 3600e3], "24h": ["24 hours", 24 * 3600e3], "7d": ["7 days", 7 * 24 * 3600e3] };
// priced at the model's card: under a cent it is kit's dollars (two significant digits, exactly 0 is "$0"), and from a
// cent a request keeps a tenth of a cent, so two small requests side by side still differ
const money = (usd) => (usd < 0.01 ? dollars(usd) : `≈$${usd.toFixed(usd < 1 ? 3 : 2)}`);
const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone; // the daemon folds the window's days in the viewer's zone
/** What a project's figure counts, on its tile or in the picker and its menu: the requests in the window under the
 *  filters he set, as the daemon counts them for that project (one n=1 read per project and command), No project included:
 *  the daemon lists the requests no project holds itself (project=none), so no figure here is a subtraction. */
async function readTileCounts() {
  const groups = pickGroups();
  if (groups.length < 2) return;
  const heads = askedHeads();
  const named = new Set([...groups.map((g) => g.key), ...state.rows.map(rowKey)]);
  const keys = [null, ...new Set([...named, NO_PROJECT_KEY])];
  const reads = await Promise.all(keys.flatMap((k) => heads.map((h) => API.get(turnsPath(h, true, ui.win, 1, k)))));
  const counts = new Map();
  keys.forEach((k, i) => {
    let n = 0;
    heads.forEach((h, j) => {
      const r = reads[i * heads.length + j], a = (r.body?.heads || []).find((x) => x.key === h.key);
      n = n == null || !r.ok || !a || a.error || a.read_pending ? null : n + (a.count ?? 0);
    });
    counts.set(k ?? "", n);
  });
  state.tileCounts = counts;
}
/** How many Compare models sends the window holds under the filters set, one n=1 read per command: the group is drawn only
 *  where this is above zero, so a daemon with none shows no Compare tile. */
async function readCompareCount() {
  const heads = askedHeads();
  const reads = await Promise.all(heads.map((h) => API.get(turnsPath(h, true, ui.win, 1, COMPARE_KEY))));
  state.compareN = reads.reduce((n, r, j) => {
    const a = (r.body?.heads || []).find((x) => x.key === heads[j].key);
    return n + (r.ok && a && !a.error ? a.count ?? 0 : 0);
  }, 0);
}
/** The first read: enough rows per command to draw the newest screens at once (ui.n starts at 200). */
const FIRST_HELD = 200;
const PENDING_RETRY_MS = 2000;
/** A newer read supersedes an older one still answering, so a slow first read never overwrites a filter he changed. */
let readGen = 0;
/** Draws the next rows when Show more scrolls into view; render re-aims it at the button it drew. */
const scrolledMore = new IntersectionObserver((seen) => {
  if (seen.some((e) => e.isIntersecting)) { ui.n += 200; render(); }
}, { rootMargin: "600px 0px" });
const MAX_HELD = 2000; // the most rows one read of a head can carry (PerfRoutes.kt MAX_TURNS); asking for more is clamped
const SESSIONS_IN_MENU = 50; // as many as one look down the menu takes in; the rest are reached from Sessions
const SEARCH_MIN = 2; // the shortest text the search route reads (TraceSearch.kt)

let state = {
  loading: true, compareN: 0, error: null, groups: [], titles: {}, tileCounts: null, heads: [], providerOf: {}, modelLabel: {}, sessions: {}, keySeen: {}, keysOfHead: {},
  rows: [], matched: 0, capped: false, facets: { models: [], accounts: [], sessions: [] }, headErrors: [], sessionsError: null, traceDays: null, capture: {}, config: null,
  // what Start saving answered, per command: { word, failed }, the word that stands beside "Not saved" once pressed
  saving: {},
  // what the search answered: how far back it read, why it stopped, and the turns it could not reach
  search: null,
  // when the window lists nothing: the nearest wider window that has matches, and how many (readWider)
  wider: null,
};
let ui = { project: PICK.get(), pmenu: false, open: null, q: "", win: "24h", cmd: null, sid: null, model: null, account: null, repo: null, from: null, to: null,
  outcome: "all", tag: null, why: null, compact: false, menu: null, n: 200, tab: "sent", nodes: new Set(), auto: true,
  order: "new" }; // Newest or Highest price (fin): what the read asks for; the list shows what the daemon says it applied
const root = document.getElementById("requests");
const wide = sideBySide;
// How far back every read asks: the window, or the stretch a door from another page named, which may reach further
// back than the window does. One figure, so the rows, the menus and the search all cover the same ground.
const since = (win = ui.win) => Math.min(ui.from ?? Infinity, +NOW - WINDOWS[win][1]);
const providerOfCmd = (cmd) => state.providerOf[(state.heads.find((h) => h.command === cmd) || {}).key];
// ONE SESSION, ONE NAME ON EVERY PAGE (PROJECTS.md): its name, else the first line it was asked, as its Sessions card
// reads; never its folder. The title comes from the session row, or from the request row for a session no longer live.
const sessTitle = (sid) => {
  if (!sid) return "";
  const tag = String(sid).slice(0, 8), s = state.sessions[sid] ?? state.sessions[tag];
  return s?.name || s?.title || state.titles[tag] || pastName(sid) || `Session ${tag}`;
};
// An ended agent is named by history, as Sessions and Teams name it: asked once per session, the row redrawn when it
// answers, and "Session 0296ce46" only while it is asked or when history has no name (Marlin, desk c, Oct 12: a
// Saturday row read "Session 0296ce46" where history names it qgre-builder)
const pastNames = new Map(); // session id -> its name from history, null when history has none, undefined while asked
function pastName(sid) {
  const id = String(sid);
  if (pastNames.has(id)) return pastNames.get(id);
  pastNames.set(id, undefined);
  asking.push(id);
  askNext();
  return undefined;
}
// at most four asks in flight, so opening the Session menu over a week of agents never sends a burst
const asking = [];
let inFlight = 0;
function askNext() {
  while (inFlight < 4 && asking.length) {
    const id = asking.shift();
    inFlight++;
    API.get(`/api/sessions/history?limit=1&query=${encodeURIComponent(id)}`).then((res) => {
      const row = (res.body?.sessions || []).find((x) => String(x.session_id).startsWith(id));
      pastNames.set(id, row?.name || row?.title || null);
      if (pastNames.get(id)) render();
    }).finally(() => { inFlight--; askNext(); });
  }
}
// ---------- the pick: the projects Sessions groups by, one pick across the pages (kit PICK) ----------
// A pick from a door names a project that may have no live session today; it is listed by the name its requests carry.
function pickGroups() {
  const g = state.groups.slice();
  // Compare models shows where it has requests, just before No project, and never as an empty group
  if ((state.compareN > 0 || ui.project === COMPARE_KEY) && !g.some((x) => x.key === COMPARE_KEY)) {
    const at = g.findIndex((x) => x.key === NO_PROJECT_KEY);
    g.splice(at < 0 ? g.length : at, 0, { key: COMPARE_KEY, label: COMPARE_LABEL, counts: [["needs", 0], ["stalled", 0], ["working", 0]], total: 0 });
  }
  if (ui.project && !g.some((x) => x.key === ui.project)) {
    const seen = state.rows.find((r) => rowProjKey(r) === ui.project);
    g.push({ key: ui.project, label: seen ? rowProjName(seen) : ui.project === NO_PROJECT_KEY ? "No project" : ui.project.split("/").pop(),
      counts: [["needs", 0], ["stalled", 0], ["working", 0]], total: 0 });
  }
  return g;
}
const rowProject = (r) => r.project ?? state.sessions[r.sid]?.project ?? state.sessions[String(r.sid ?? "").slice(0, 8)]?.project ?? null;
// the group a row belongs to: Compare models sends first, then its project, else No project
const rowKey = (r) => rowProjKey({ compare: r.compare, project: rowProject(r) });
const inPick = (r) => !ui.project || rowKey(r) === ui.project;

// ---------- reading splice ----------
/** The commands, their providers, the models' labels, the sessions requests belong to, and a key's first day. */
async function readStanding() {
  const [heads, models, sessions, keys, topology, retired] = await Promise.all([
    API.get("/api/heads"), API.get("/api/models"), API.get("/api/sessions"), API.get("/api/keys"), API.get("/api/topology"),
    API.get("/api/heads/retired"),
  ]);
  state.error = heads.ok ? null : LIST_NOT_READ;
  if (!heads.ok) console.warn(`commands: ${refusalOf(heads, "no answer")}`);
  state.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key, reanchorMs: h.gate?.stall_reanchor_ms ?? null, idleMs: h.gate?.stream_idle_ms ?? null, firstByteMs: h.gate?.first_byte_timeout_ms ?? null }));
  state.heads.push(...retiredHeads(retired, state.heads));
  // A provider splice reaches on this machine's own loopback is served from THIS COMPUTER, whatever it is called:
  // his own model servers and the tunnel to a rented GPU both answer at 127.0.0.1 (splice.toml base_url), and both
  // draw in the one colour the kit gives a local provider. The address is the fact; the name is not.
  const bases = topology.body?.topology?.providers || {};
  const local = (name) => /^https?:\/\/(127\.0\.0\.1|\[?::1\]?|localhost)\b/.test(bases[name]?.base_url || "");
  for (const row of models.body?.heads || []) {
    state.providerOf[row.head] = FAMILY[row.provider] ?? (local(row.provider) ? "local" : row.provider);
    learnModels(state.modelLabel, row);
  }
  // A session's name is its own page's to read. When that read refuses, the rows still list by the id splice recorded
  // and the refusal is said, because every row reading "Session 8cb8a71d" is not a day without names.
  state.sessionsError = sessions.ok ? null : LIST_NOT_READ;
  if (!sessions.ok) console.warn(`session names: ${refusalOf(sessions, "no answer")}`);
  state.sessions = {};
  for (const s of sessions.body?.sessions || []) {
    if (!s.session_id) continue;
    state.sessions[s.session_id] = s;
    state.sessions[String(s.session_id).slice(0, 8)] = s; // a perf row names its session by its first eight characters
  }
  // the projects in Sessions' order, ranked by the same reading of the same live turns (kit rowLook)
  const running = state.heads.filter((h) => !h.retired); // a removed command runs nothing and keeps no capture setting
  const lives = await Promise.all(running.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/turns/live`)));
  const turns = {};
  running.forEach((h, i) => { for (const t of lives[i].body?.turns || []) if (t.session && !t.stopped) turns[t.session] = { head: h.key, ...t }; });
  state.groups = projectGroups(sessions.body?.sessions || [], turns, state.heads);
  ui.project = reconcilePick(ui.project, state.groups);
  // Each key splice has seen under a variable, by the account label a request row carries: the one in use now and
  // every one it replaced (/api/keys serves both, KeyReaders.kt). A fingerprint this daemon has never seen leaves
  // the row with its variable alone, which is what the row itself proves.
  state.keySeen = {};
  state.keysOfHead = {};
  for (const k of keys.body?.keys || []) {
    if (!k.name) continue;
    for (const x of k.heads || []) state.keysOfHead[x.head] = (state.keysOfHead[x.head] ?? 0) + 1;
    const seen = (print, at, replaced) => {
      if (print && at) state.keySeen[`${k.name}:${print}`] = { name: k.name, seen: new Date(at * 1000), replaced };
    };
    seen(k.fingerprint, k.first_seen_epoch_seconds, false);
    for (const old of k.replaced || []) seen(old.fingerprint, old.first_seen_epoch_seconds, true);
  }
  // Whether each command is saving prompts and answers NOW, and for how long it keeps them: the setting's own figure,
  // per command, never a cut this page made up. The files outlive the knob, so a command switched off still has the
  // days it recorded, and this says which state a request with no records is in.
  const caps = await Promise.all(running.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/capture`)));
  running.forEach((h, i) => {
    const body = caps[i].ok ? caps[i].body : null;
    if (body) state.capture[h.key] = { on: body.enabled === true, days: body.retention_days ?? null };
  });
  const days = uniq(running.map((h) => state.capture[h.key]?.days).filter((d) => d != null));
  state.traceDays = days.length === 1 ? days[0] : null; // one figure to say only when every command agrees on it
  const cfg = await API.get("/api/config");
  state.config = cfg.ok ? cfg.body : null;
}

/** The silence tier splice acts on mid-answer for one command: its own value where it holds one, the daemon's
 *  otherwise. Reading it keeps the rail's quiet band to the silences splice itself would have asked about. */
function idleTier(head) {
  return idleTierOf(state.config, head.key);
}

/** A refusal under the name of what was being read, in the console's two words, so a page never says "none" where it
 *  means "could not read"; the daemon's sentence goes to the log, never the page (fin, Marlin, Oct 12). */
const refusalOf = (res, subject) => {
  console.warn(`${subject}: ${res.body?.error || res.status || "no answer"}`); // the daemon's sentence goes to the log (fin, Marlin)
  return `${subject}: ${res.status === 0 ? "Not answering" : "Not read"}`;
};

/** One head's window: the rows the filters asked for, and the window's own menus from an unfiltered read. */
// WHY_ASKS (what a door's "what happened" asks the daemon for) lives in kit.js, where Settings counts the same requests.
function turnsPath(head, filtered, win = ui.win, held = MAX_HELD, project = ui.project) {
  // local=0 on both reads, so the menus count the same requests the list does
  const p = new URLSearchParams({ head: head.key, since: String(since(win)), time_zone: ZONE, n: filtered ? String(held) : "1", local: "0" });
  if (filtered) {
    // a filtered read is rows or a count, never totals: the menus' totals come from the unfiltered read alone, and each
    // fold this dropped held the command's read for a whole week (splice-usage, desk c, Oct 12)
    p.set("usage", "0");
    if (ui.outcome === "fail") p.set("outcome", "failed");
    if (ui.outcome === "stopped") p.set("outcome", "stopped");
    // one kind of failure (?tag=, Usage's table): the exact tag is narrower than Failed and agrees with it
    if (ui.tag && ui.outcome !== "stopped") p.set("outcome", ui.tag);
    if (ui.order === "price") p.set("order", "price"); // the daemon keeps each command's highest priced, not its newest
    if (ui.model === "none") p.set("unattributed", "model");
    else if (ui.model) p.set("model", ui.model);
    if (ui.account === "none") p.set("unattributed", "account");
    else if (ui.account) p.set("account", ui.account.split("|").slice(1).join("|"));
    if (ui.sid) p.set("session", String(ui.sid).slice(0, 8));
    // the daemon narrows to the project's sessions itself (TurnsFilter.inProject), No project included (project=none), so the newest 2,000 are its own
    if (project) p.set("project", project === NO_PROJECT_KEY ? "none" : project); // none: the daemon lists the requests no project holds
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
    // the words the daemon tests itself are listed by that test, so the list holds exactly what a Settings tile counted
    if (ROW_TEST[ui.why]) p.set("test", ROW_TEST[ui.why]);
    // a named stretch begins at the daemon too, or a cap on the newest rows would cut the stretch's start off
    if (ui.from) p.set("from", String(+ui.from + 1));
  } else p.set("usage", "names"); // the menus list names only: the daemon skips the priced fold (TurnNames.kt); an older one ignores it
  return `/api/perf/turns?${p}`;
}

/** Which heads this read asks at all: a command filter, or an account picked on one head, narrows it to that head. */
function askedHeads() {
  const picked = ui.account && ui.account !== "none" ? ui.account.split("|")[0] : null;
  return state.heads.filter((h) => (!ui.cmd || h.command === ui.cmd) && (!picked || h.key === picked));
}

/** Each command's newest requests, at most [held] of them. The read answers at once with what splice has ready: a
 *  command whose saved requests splice is still reading after a restart is named so, and asked again shortly, rather
 *  than holding every other command's rows back (splice-lead, Marcos's live console: 28 to 45 s to settle). */
/** A command whose read failed is named with one word on the list, never splice's prose: the reason goes to the
 *  browser's log, as every source string does (fin). Partly read when some rows came back, so Not read is never said
 *  over rows on screen. */
const LIST_NOT_READ = "Not read", LIST_PARTLY_READ = "Partly read", LIST_NOT_SEARCHED = "Not searched";
function unreadable(errors, head, word, why) {
  console.warn(`${head.command}: ${why}`);
  errors.push({ cmd: head.command, word });
}
async function readRows(held = MAX_HELD) {
  const heads = askedHeads();
  const reads = await Promise.all(heads.map((h) => API.getReady(turnsPath(h, true, ui.win, held))));
  const rows = [], errors = [], pending = [];
  let matched = 0, capped = false, priced = ui.order === "price";
  heads.forEach((head, i) => {
    const filtered = reads[i];
    const answer = (filtered.body?.heads || []).find((x) => x.key === head.key);
    if (!filtered.ok || !answer) return unreadable(errors, head, LIST_NOT_READ, refusalOf(filtered, "no answer"));
    if (answer.read_pending) { pending.push(head.command); return; }
    if (answer.error) return unreadable(errors, head, LIST_NOT_READ, answer.error);
    if (answer.read_error) unreadable(errors, head, LIST_PARTLY_READ, answer.read_error);
    // the order a command's answer says it applied: a daemon that does not echo one kept the newest (splice-builder)
    if (answer.order !== "price") priced = false;
    matched += answer.count ?? 0;
    if (answer.truncated) capped = true;
    for (const row of answer.rows || []) rows.push(mapRow(head, row));
  });
  state.rows = linkRetries(linkCuts(rows));
  state.slowAt = slowLines(state.rows);
  state.matched = matched;
  state.capped = capped;
  state.order = priced ? "price" : "new";
  state.held = held;
  state.headErrors = errors;
  state.pending = pending;
}
/** The menus' models, accounts and sessions, from the window's unfiltered read. Asked AFTER the list has drawn, never
 *  beside it: the daemon answers one command's reads one at a time, and this one folds the window's whole usage (2.1 s
 *  and 8.8 MB for claude-splice's week on desk c, against 0.54 s for the list), so a day jump waited on the menus
 *  (Marlin, desk c, Oct 12: 17 s to show Saturday). The menus keep their last reading until this answers. */
async function readMenus(gen) {
  const heads = askedHeads();
  const reads = await Promise.all(heads.map((h) => API.getReady(turnsPath(h, false))));
  if (gen !== readGen) return;
  const facets = { models: new Map(), accounts: new Map(), sessions: new Map() };
  heads.forEach((head, i) => {
    const menus = (reads[i].body?.heads || []).find((x) => x.key === head.key)?.usage;
    for (const m of menus?.models || []) if (m.key) facets.models.set(m.key, modelName(state.modelLabel, m.key));
    for (const a of menus?.accounts || []) if (a.key) facets.accounts.set(`${head.key}|${a.key}`, { head, account: a.key, label: a.label ?? null });
    for (const s of menus?.sessions || []) if (s.key) facets.sessions.set(s.key, { head, last: s.last_model_ts_epoch_ms });
  });
  state.facets = { models: [...facets.models], accounts: [...facets.accounts], sessions: [...facets.sessions] };
}

/** One perf row as this page draws it. A key the writer never put in the row is left null, never zeroed: a provider
 *  that reported no tokens and a request that never got a first byte draw nothing where their figure would be. */
// SLOW, RETRIED AND FAILED LOOK DIFFERENT (v0.4.0 section 3's acceptance). A failure has its glyph and its red edge. A
// request that answered after more than one send takes the retries glyph in its lamp and the word Retried. One that
// answered slower than all but 5% of the same command's same model in this list reads Slow, its duration in the key
// light: the line is that model's own, never one figure across a fast model and a slow one, and it is drawn only from
// 20 clean answers up, so a short list marks nothing rather than guessing.
const SLOW_SHARE = 0.95, SLOW_MIN_ROWS = 20;
const answered = (r) => !r.compact && outcomeOf(r).cls === "clean";
function slowLines(rows) {
  const by = new Map();
  for (const r of rows) if (answered(r)) { const k = `${r.head}|${r.modelId}`; (by.get(k) ?? by.set(k, []).get(k)).push(r.total); }
  const lines = new Map();
  for (const [k, xs] of by) if (xs.length >= SLOW_MIN_ROWS) { xs.sort((a, b) => a - b); lines.set(k, xs[Math.floor(SLOW_SHARE * (xs.length - 1))]); }
  return lines;
}
const isSlow = (r) => answered(r) && r.total > (state.slowAt?.get(`${r.head}|${r.modelId}`) ?? Infinity);
const isRetried = (r) => answered(r) && r.attempts > 1;
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
  if (sid && row.title) state.titles[String(sid).slice(0, 8)] = row.title;
  return {
    id: `${head.key}:${row.turn || row.turn_id || row.ts}`, head: head.key, cmd: head.command, turn: row.turn ?? null,
    provider: state.providerOf[head.key] ?? null,
    ts: new Date(row.ts), sid, outcome: row.outcome, cause: row.cause ?? null, hash: row.messages_hash ?? null,
    model: modelName(state.modelLabel, row.model), modelId: row.model ?? null,
    account: row.account ?? null, acctLabel: row.account_label ?? null, compact: row.compact === true,
    attempts: row.attempts ?? 0, retries: row.retries ?? 0, sourceAttempts: row.source_attempts ?? null,
    total: row.total ?? 0, ttfb: row.first_byte ?? null, ftok: row.first_delta ?? null,
    // how long the gate held it behind a full limit, null when it went straight in (a 1 ms pass is not waiting)
    queued: row.admit_held_ms ?? null,
    silent: waited ? waited.ms : 0, silentFrom: waited ? waited.at - started : 0,
    resumed, resumedAt: resumed && waited ? waited.at + waited.ms - started : 0, resumedFresh: fresh, stallMs: stall,
    // the cache-write figure is LEFT OUT when the row carries none, like the other two: a refused request reports no
    // tokens at all (no cache_write_tokens key on any error:rate-limited or error:plan-limit row in the live file),
    // and the open request drew "Cache write 0" over it, which is a measurement nobody made. `cached` keeps its zero:
    // it is never printed, only compared, and it decides whether the "% cached" line is drawn at all.
    cut: row.cut_source_rounds ?? 0,
    in: row.in_tokens ?? null, cached: row.cached_tokens ?? 0, write: row.cache_write_tokens ?? null, out: row.out_tokens ?? null, inflight: row.inflight ?? null,
    cost: row.cost_usd ?? null, reason: row.cost_reason ?? null,
    // what a request on a plan account would have cost at API prices; the plan paid it, so it is never the cost (TurnUsage.kt)
    planValue: row.plan_value_usd ?? null,
    reset: row.earliest_reset_epoch_seconds ? new Date(row.earliest_reset_epoch_seconds * 1000) : null,
    // the spent window's length as the provider's refusal named it, null when it named none (limit_window_seconds)
    window: row.limit_window_seconds ?? null,
    // whether the command was saving when this ran: false only when the row says it was not (capture: false)
    saving: row.capture === false ? false : null,
    // what the provider answered, kept on the row even when no body was (provider_status, provider_message)
    providerStatus: row.provider_status ?? null, providerMessage: row.provider_message ?? null,
    // the project is the session's repo, named as Sessions names it (its root's last folder); /api/sessions carries the
    // root and never a name (SessionsRoutes repoJson), and /api/projects ids are roots, so a link may name either
    project: row.project ?? null,
    compare: row.compare === true,
    repo: repoName(state.sessions[sid]?.repo?.root), repoRoot: state.sessions[sid]?.repo?.root ?? null,
  };
}
/** Code mode cuts a source round on purpose and bills it to the same session's next request (cut_source_rounds, PerfKeys.kt;
 *  TurnUsage.kt). The cut request is ok and carries no tokens; the request that took them carries them, and its count
 *  looks too big with no reason. Each side names the other, and only where the other is in the loaded list: a row is
 *  never pointed at by a guess (fin, Oct 11). countedOn: the carrying row's id. earlier: how many cut requests the
 *  carrying row holds, and cutId their row when it is exactly one. */
function linkCuts(rows) {
  for (const r of rows) { r.countedOn = null; r.counted = false; r.cutId = null; }
  const lists = new Map();
  for (const r of [...rows].sort((x, y) => x.ts - y.ts)) {
    if (!r.sid) continue;
    const key = r.head + "|" + r.sid;
    if (!lists.has(key)) lists.set(key, []);
    lists.get(key).push(r);
  }
  // The cut requests waiting in a session are those since its last request that carried tokens; the next request that
  // carries tokens and holds cut rounds took them. When it holds at least as many as wait, each names it (a door). When
  // fewer, which of them it took cannot be known, so they say they were counted later and name no row (fin: don't guess a
  // row). A request with tokens and no cut rounds ends the wait: what waited before it has no carrier and says so.
  for (const list of lists.values()) {
    let waiting = [];
    for (const r of list) {
      if (r.outcome === "ok" && r.in == null && r.out == null) { waiting.push(r); continue; }
      if (r.in == null && r.out == null) continue;
      if (r.cut > 0) {
        for (const c of waiting) { c.counted = true; if (waiting.length <= r.cut) c.countedOn = r.id; }
        if (waiting.length === 1 && r.cut === 1) r.cutId = waiting[0].id;
      }
      waiting = [];
    }
  }
  return rows;
}
/** A request splice cut when it restarted and the client's re-send of it are one request to the person reading the list:
 *  the re-send carries the same messages in the same session (messages_hash, the match LiveTurns makes for a stop). The
 *  cut row names its re-send and the re-send names the cut, each only where the other is in the loaded list, as linkCuts
 *  does: a row is never pointed at by a guess (fin, Oct 11). retryId: the re-send's row. retryOf: the cut row. */
function linkRetries(rows) {
  for (const r of rows) { r.retryId = null; r.retryOf = null; }
  const later = [...rows].filter((r) => r.sid && r.hash).sort((x, y) => x.ts - y.ts);
  for (const [i, cut] of later.entries()) {
    if (cut.outcome !== "error:restarted") continue;
    const again = later.slice(i + 1).find((r) => r.head === cut.head && r.sid === cut.sid && r.hash === cut.hash && !r.retryOf);
    if (again) { cut.retryId = again.id; again.retryOf = cut.id; }
  }
  return rows;
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
    ts: hit.ts, total: hit.total, first_byte: hit.first_byte, first_delta: hit.first_delta, admit_held_ms: hit.admit_held_ms ?? undefined,
    in_tokens: hit.in_tokens, out_tokens: hit.out_tokens, cached_tokens: hit.cached_tokens, cache_write_tokens: hit.cache_write_tokens,
    outcome: hit.outcome ?? "ok", model: hit.model, session: hit.session, compact: hit.compact, attempts: hit.attempts,
    source_attempts: hit.source_attempts ?? undefined, turn: hit.turn, cost_usd: hit.cost_usd, cost_reason: hit.cost_usd == null ? "uncounted" : null,
  };
  return { ...mapRow(head, row), said: { where: hit.where, text: oneLine(hit.text) } };
}

/** The search over what was sent and what came back, one head at a time, each saying how far back it read. */
async function readSearch() {
  if (ui.q.length < SEARCH_MIN) { state.search = null; return; }
  const heads = askedHeads(); // a removed command's trace answers through its retired record (builder3, 0680d2ae9)
  const q = encodeURIComponent(ui.q);
  const reads = await Promise.all(heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/trace/search?q=${q}&since=${since()}&limit=100`)));
  const hits = [], errors = [];
  let backTo = null, stopped = null;
  heads.forEach((head, i) => {
    const res = reads[i];
    if (!res.ok) return unreadable(errors, head, LIST_NOT_SEARCHED, refusalOf(res, "no answer"));
    for (const hit of res.body?.hits || []) hits.push(mapHit(head, hit));
    // a head that stopped early sets the floor; one that read all it keeps reaches nothing older and sets none
    if (res.body?.stopped_on) {
      stopped = res.body.stopped_on;
      if (res.body.back_to_epoch_ms != null) backTo = Math.min(backTo ?? Infinity, res.body.back_to_epoch_ms);
    }
  });
  state.search = { hits, errors, backTo, stopped };
}

/** A hit the window's rows do not hold (they hold at most [MAX_HELD] per command) still answers to the filters the
 *  daemon applied to those rows, here, so a search with Failed on lists no ok request (walk p180, shot 022). An account
 *  filter cannot be judged from a hit, which names none, so it keeps only the rows the daemon matched. */
const hitPasses = (r) => (ui.outcome === "all" || (ui.outcome === "fail" ? outcomeOf(r).cls === "fail" : outcomeOf(r).cls === "stopped"))
  && (!ui.model || (ui.model === "none" ? !r.model : r.model === ui.model)) && !ui.account
  && (!ui.sid || String(r.sid ?? "").startsWith(String(ui.sid).slice(0, 8))) && (!ui.compact || r.compact)
  && (!ui.to || +r.ts <= ui.to) && (!ui.tag || ui.tag.split(",").includes(r.outcome)) && !Object.keys(WHY_ASKS[ui.why] || {}).length;
/** The list's rows: the window's requests, with a search's own hits folded in. A hit on a request already in the
 *  window marks that row; a hit on an older one is a row of its own, so a search reaches past what the rows hold. */
function searched() {
  if (!state.search) return state.rows;
  const byTurn = new Map(state.rows.map((r) => [r.id, r]));
  const out = [], seen = new Set();
  for (const hit of state.search.hits) {
    const known = byTurn.get(hit.id);
    if (known) { known.said = hit.said; out.push(known); } else if (hitPasses(hit)) out.push(hit);
    seen.add(hit.id);
  }
  // an agent is searched for by its name too: every request of a session whose name holds the words (Marlin's walk:
  // "infra" read No match with infra's rows in the list), and a request by the ending its row shows: "restart" finds
  // "splice restarted", "given up" finds Given up (walk p180: both walkers scrolled twenty screens for Saturday's restart).
  // And by its command's name, so a removed command's requests are found by the name he removed (Marlin's Models walk).
  const q = ui.q.toLowerCase();
  const named = (r) => state.sessions[r.sid]?.name?.toLowerCase().includes(q) || r.cmd.toLowerCase().includes(q)
    || outcomeOf(r).word.toLowerCase().includes(q);
  for (const r of state.rows) if (!seen.has(r.id) && named(r)) out.push(r);
  return out;
}

// ---------- what the daemon cannot filter: a project, a stretch of time and what happened ----------
// These narrow the rows the read already cut. The read asks for the window's newest 2,000, so a narrow one of these
// says how many of the window it had to look at, rather than reading as the whole window's answer.
const narrow = (rows) => rows.filter((r) => inPick(r) && (!ui.repo || r.repo === ui.repo || r.repoRoot === ui.repo)
  && (!ui.from || (+r.ts > ui.from && +r.ts <= (ui.to ?? +NOW)))
  && (!ui.tag || ui.tag.split(",").includes(r.outcome)) && (!ui.why || ROW_TEST[ui.why] || WHY[ui.why].has(r)));
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
  if (key === "win") {
    // a time to start from, as far back as the widest window reads: the list then holds the requests up to it, newest first
    const local = (ms) => { const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60e3); return d.toISOString().slice(0, 16); };
    const goto = `<div class="goto"><label for="goto">Up to</label><input id="goto" type="datetime-local" min="${local(+NOW - WINDOWS["7d"][1])}" max="${local(+NOW)}" value="${local(ui.to ?? +NOW)}"><button class="act quiet" data-act="goto">Show</button></div>`;
    // each of the week's days is one press, up to its end (Marlin, p225: a pick in the browser's calendar never reached
    // the field, so the field keeps typing and the arrow keys and loses its calendar button)
    const days = [0, 1, 2, 3, 4, 5, 6].map((i) => {
      const d = new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() - i), end = dayEnd(d);
      const on = i === 0 ? !ui.to : ui.to === end;
      return `<button role="menuitemradio" aria-checked="${on}" data-act="upto" data-v="${i === 0 ? "" : end}">${i === 0 ? "Today" : dayWordShort(d)}</button>`;
    }).join("");
    return `<div class="menu fmenu" role="menu">${Object.entries(WINDOWS).map(([k, [w]]) => item(k, w, ui.win === k)).join("")}${goto}<div class="days">${days}</div></div>`;
  }
  if (key === "cmd") return `<div class="menu fmenu" role="menu">${item("", "Every command", !ui.cmd)}${state.heads.map((h) => item(h.command, esc(h.command) + (h.retired ? REMOVED_TAG : ""), ui.cmd === h.command, `<i class="blot" style="--c:${colorOf(state.providerOf[h.key])}"></i>`)).join("")}</div>`;
  // the requests splice couldn't put a model or an account on (unattributed=model|account, TurnsFilter.kt)
  if (key === "model") return `<div class="menu fmenu" role="menu">${item("", "Every model", !ui.model)}${state.facets.models.map(([id, label]) => item(id, esc(label), ui.model === id)).join("")}${item("none", "No model", ui.model === "none")}</div>`;
  if (key === "account") { // an account on two commands is two accounts: a key's own variable and day, a plan's label
    return `<div class="menu fmenu" role="menu">${item("", "Every account", !ui.account)}`
      + (() => { const names = acctNames(); return state.facets.accounts.map(([k, { head }]) => item(k, `<span class="aname">${names.get(k)}<span class="aprov">${esc(head.command)}</span></span>`, ui.account === k)).join(""); })()
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
// The order is not a filter, so it sits apart at the row's end in the switch Outcome uses (hitstop 1912470, fin's words).
// It shows the order the daemon applied: a daemon that kept the newest shows Newest, never Highest price over them.
const orderHtml = () => `<div class="switch order" role="group" aria-label="Order">${[["new", "Newest"], ["price", "Highest price"]]
  .map(([v, w]) => `<button data-act="order" data-v="${v}" aria-pressed="${(state.order ?? ui.order) === v}">${w}</button>`).join("")}</div>`;
// In Highest price a plan row keeps "On your plan" and hangs its worth under it, the open Request's two-line form, so
// its word never changes with the sort and the figure shows why it ranks (fin, hitstop 20bfada). Newest keeps one line.
const worthHtml = (r) => (state.order === "price" && r.cost == null && r.planValue != null
  ? `<span class="worth">${money(r.planValue)} at API prices</span>` : "");
function filtersHtml() {
  const out = ui.outcome;
  const sw = `<div class="switch" role="group" aria-label="Outcome">${[["all", "All"], ["fail", "Failed"], ["stopped", "Stopped"]].map(([v, w]) => `<button data-act="outcome" data-v="${v}" aria-pressed="${out === v}">${w}</button>`).join("")}</div>`;
  // Started over happens during a request that can still end clean, so it is not an outcome (fin) but a door of its
  // own beside the switch (Marlin, p157; hitstop f80553d): the why filter, pressed when a door brings it, and it
  // combines with Failed and Stopped. Its toggle is its chip, so no second chip is drawn for it.
  const over = `<button class="ftog" data-act="why" data-v="startedover" aria-pressed="${ui.why === "startedover"}"><i class="tick"></i>${WHY.startedover.word}</button>`;
  const tog = (key, label, on) => `<button class="ftog" data-act="toggle" data-m="${key}" aria-pressed="${on}">${GLYPH.compact}${label}</button>`; // each toggle draws its rows' mark (fin; hitstop d91f182)
  return `<div class="filters">${pickBtn("win", WINDOWS[ui.win][0], false)}`
    + pickBtn("cmd", ui.cmd ? `<i class="blot" style="--c:${colorOf(providerOfCmd(ui.cmd))}"></i>${esc(ui.cmd)}` : "Command", Boolean(ui.cmd))
    + pickBtn("sid", ui.sid ? esc(sessTitle(ui.sid)) : "Session", Boolean(ui.sid))
    + pickBtn("model", ui.model ? (ui.model === "none" ? "No model" : esc(modelName(state.modelLabel, ui.model))) : "Model", Boolean(ui.model))
    + pickBtn("account", ui.account ? (ui.account === "none" ? "No account" : (acctNames().get(ui.account) ?? "")) : "Account", Boolean(ui.account))
    + (ui.repo ? `<span class="fwrap"><span class="fbtn on">${esc(repoName(ui.repo))}</span><button class="fclear" data-act="unset" data-m="repo" aria-label="Clear">${ICON.close}</button></span>` : "")
    + (ui.from ? `<span class="fwrap"><span class="fbtn on">${dayOf(new Date(ui.from + 1))}${ui.to ? `${clock(new Date(ui.from))} to ${clock(new Date(ui.to))}` : `Since ${clock(new Date(ui.from))}`}</span><button class="fclear" data-act="unset" data-m="from" aria-label="Clear">${ICON.close}</button></span>` : "")
    + (ui.to && !ui.from ? `<span class="fwrap"><span class="fbtn on">Up to ${dayOf(new Date(ui.to))}${clock(new Date(ui.to))}</span><button class="fclear" data-act="unset" data-m="to" aria-label="Clear">${ICON.close}</button></span>` : "")
    + (ui.tag ? `<span class="fwrap"><span class="fbtn on">${esc(OUTCOME_WORD[ui.tag.split(",")[0]] || tagWord(ui.tag.split(",")[0]) || "Failed")}</span><button class="fclear" data-act="unset" data-m="tag" aria-label="Clear">${ICON.close}</button></span>` : "")
    + (ui.why && ui.why !== "startedover" ? `<span class="fwrap"><span class="fbtn on">${esc(WHY[ui.why].word)}</span><button class="fclear" data-act="unset" data-m="why" aria-label="Clear">${ICON.close}</button></span>` : "")
    + `${sw}${over}${tog("compact", "Compactions", ui.compact)}${orderHtml()}</div>`;
}

// ---------- the list: the newest first, on the rail, an hour mark where the hour turns ----------
function rowHtml(r) {
  const o = outcomeOf(r), h = r.said, slow = isSlow(r), retried = isRetried(r);
  const lamp = r.compact ? GLYPH.compact : o.glyph ? GLYPH[o.glyph] : retried ? GLYPH.retries : "<i></i>";
  const marks = (retried ? `<span class="word marked">Retried</span>` : "") + (slow ? `<span class="word marked">Slow</span>` : "");
  const word = (o.word ? `<span class="word ${o.cls}">${o.word}</span>` : r.compact ? `<span class="word">Compaction</span>` : marks) + besideOf(r);
  // a figure the provider never reported is left out, never printed as 0
  // THE TWO FIGURES TRAVEL TOGETHER. In and out are one reading, and a meta line that wraps between them leaves
  // "4 out" alone under a row whose neighbour kept both ("1.5K out" and "2.2K out", live at 3828 — splice-lead,
  // Oct 11): the orphan is what reads as sloppy, not the wrap. Held in one group the line can only break BEFORE
  // the pair, so every row that is too long breaks in the same place and no row loses half a reading.
  const tokens = (r.in != null ? `<span class="tok">${kTok(r.in)} tokens in${r.in && r.cached > 0 ? `<em>${Math.max(1, Math.round((r.cached / r.in) * 100))}% cached</em>` : ""}</span>` : "")
    + (r.out != null ? `<span class="tok">${kTok(r.out)} out</span>` : "")
    + (r.write > 0 ? `<span class="tok">${kTok(r.write)} written to cache</span>` : "");
  const earlier = r.cut > 0 && r.in != null
    ? (r.cutId ? `<button class="tok quiet door" data-open="${esc(r.cutId)}">Counts 1 earlier request</button>` : `<span class="tok quiet">Counts ${r.cut} earlier request${r.cut === 1 ? "" : "s"}</span>`)
    : "";
  const toks = tokens ? `<span class="toks">${tokens}${earlier}</span>` : "";
  // how the send went, the open pane's figures on the row too: the wait for the first byte, every send past the first,
  // and how many requests the command was running when this one was let in (the gate's count at admission)
  const sends = (r.ttfb != null ? `<span class="tok">First byte ${secs(r.ttfb)}</span>` : "")
    + (r.attempts > 1 ? `<span class="tok">${r.attempts} attempts</span>` : "")
    + (r.inflight != null ? `<span class="tok quiet">${n0(r.inflight)} in flight</span>` : "");
  // a finished request with no tokens says where they went: counted on the request that took them, a door to it when it
  // is in the list; any other has no reading and says so. The request that took them says what its count holds.
  const noReading = r.outcome === "ok" && r.in == null && r.out == null
    ? (r.countedOn ? `<button class="tok quiet door" data-open="${esc(r.countedOn)}">Counted on the next request</button>` : r.counted ? `<span class="tok quiet">Counted on the next request</span>` : `<span class="tok quiet">No usage reported</span>`)
    : "";
  // the request splice cut when it restarted and the client's re-send of it, each a door to the other (linkRetries)
  const retry = r.retryId ? `<button class="tok quiet door" data-open="${esc(r.retryId)}">Sent again after the restart</button>`
    : r.retryOf ? `<button class="tok quiet door" data-open="${esc(r.retryOf)}">Resent after splice restarted</button>` : "";
  const match = h ? `<p class="match"><span class="where">${h.where === "sent" ? "Sent" : "Returned"}</span>${markIn(`… ${h.text} …`)}</p>` : "";
  return `<article class="card rq ${o.cls}${r.compact ? " compact" : ""}" style="--c:${colorOf(r.provider)}" data-open="${esc(r.id)}" aria-current="${ui.open === r.id}">`
    + `<span class="lamp ${o.cls}${r.compact ? " compact" : ""}${retried ? " retried" : ""}" aria-hidden="true">${lamp}</span>`
    + `<div class="top"><time>${state.order === "price" ? dayOf(r.ts) : ""}${clockS(r.ts)}</time><button class="name" data-open="${esc(r.id)}">${esc(r.compare ? COMPARE_LABEL : sessTitle(r.sid))}</button>${word}`
    + `<span class="dur${slow ? " slow" : ""}">${secs(r.total)}</span>${worthHtml(r)}<span class="cost${r.cost == null ? " gap" : ""}">${costText(r)}</span></div>`
    + `<div class="meta">${headChip(r)}<span>${esc(r.model ?? "No model")}</span>${r.account ? acctTag(r, ` title="${esc(r.account)}"`) : ""}${toks}${sends}${noReading}${retry}</div>${match}</article>`;
}
const markIn = (t) => RV.markIn(t);
// A WINDOW CAN HOLD MORE REQUESTS THAN ONE READ CARRIES. The daemon answers with the newest 2,000 of each command and
// says how many matched (PerfRoutes.kt, MAX_TURNS); on a live day one command's 24 hours held 19,842. Without this the
// list reads "200 of 2,000" on a window of twenty thousand, and turning Steps off changes nothing a reader can see
// while the daemon's own count moves by nine thousand.
function heldFoot() {
  if (!state.capped) return "";
  const n = state.matched;
  const which = state.order === "price" ? "highest priced" : "newest"; // the read kept the dearest, not the latest (fin)
  return `<p class="foot held">The ${n0(state.held ?? MAX_HELD)} ${which} of each command. ${n0(n)} request${n === 1 ? "" : "s"} in ${WINDOWS[ui.win][0].toLowerCase()}.</p>`;
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
// The list's order: newest first, or in Highest price the most at API prices first, a key's cost or a plan request's worth
// on the same card (fin; Marlin took price over tokens, which rank cached context), and a request with no price last,
// newest first among them. The daemon chose each command's rows by the same figure (PerfRoutes order=price).
const priceOf = (r) => r.cost ?? r.planValue ?? null;
const ordered = (rows) => rows.slice().sort(state.order === "price"
  ? (a, b) => (priceOf(b) ?? -1) - (priceOf(a) ?? -1) || +b.ts - +a.ts : (a, b) => +b.ts - +a.ts);
// WHILE THE FIRST READ IS OUT the list draws its own shape, hollow (Marcos, Oct 12: the dashed "Reading" box "looks
// awful", and it was the same box No requests uses, so loading read as an empty day). An hour tag and five slips on the
// rail, their words the striped bars a meter draws while it reads, fading down the page, so the rows land where the
// eye already is and nothing moves when they do. The filters' row holds ghost chips the same way.
const GHOST_SLIPS = [[11, 6, 18, 4], [9, 8, 14, 3.4], [13, 5, 16, 4.4], [8, 7, 12, 3.6], [10, 6, 15, 4]];
const ghostListHtml = () => `<div class="list ghosts" aria-busy="true" aria-label="Reading requests"><div class="hour"><span><i class="ghost" style="width:2.6rem"></i></span></div>`
  + GHOST_SLIPS.map(([name, chip, line, dur], i) => `<article class="card rq ghost-rq ghosted" style="--i:${i}" aria-hidden="true"><span class="lamp"></span>`
    + `<div class="top"><i class="ghost" style="width:6.4rem"></i><i class="ghost nm" style="width:${name}rem"></i><i class="ghost end" style="width:${dur}rem"></i></div>`
    + `<div class="meta"><i class="ghost chip" style="width:${chip}rem"></i><i class="ghost" style="width:${line}rem"></i></div></article>`).join("") + "</div>";
const ghostFiltersHtml = () => `<div class="filters" aria-hidden="true">${[5.6, 6.6, 6, 5.4, 6.2].map((w) => `<i class="ghost chip" style="width:${w}rem"></i>`).join("")}</div>`;
function listHtml() {
  const rows = ordered(filtered());
  // a command splice is still reading is not trouble: a quiet line, gone once its rows join (asked again shortly)
  const wait = (state.pending || []).map((cmd) => `<p class="list-wait">${ICON.wait}${esc(cmd)} \u00b7 Reading</p>`).join("");
  const said = (list) => list.map((e) => `<p class="list-wait unread">${esc(e.cmd)} \u00b7 ${e.word}</p>`).join("");
  const names = state.sessionsError ? [{ cmd: "Session names", word: state.sessionsError }] : [];
  const trouble = wait + said(state.headErrors.concat(names));
  // a command whose search failed sits under the rows the others found (fin)
  const unsearched = said(state.search?.errors || []);
  if (state.loading || state.reading) return ghostListHtml();
  if (state.error) return `<div class="list"><div class="nomatch"><span class="state">${esc(state.error)}</span></div></div>`;
  if (!rows.length && ui.q) return `<div class="list">${trouble}<div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clearq">Clear</button></div>${unsearched}${searchFoot()}</div>`;
  if (!rows.length) {
    const w = state.wider, widen = w ? `<button class="act" data-act="widen" data-v="${w.win}">${n0(w.n)}${w.floor ? "+" : ""} in the last ${WINDOWS[w.win][0]}</button>` : "";
    return `<div class="list">${trouble}<div class="nomatch"><span class="state">${anyFilter() ? "No match" : "No requests"}</span>${widen}${anyFilter() ? `<button class="act quiet" data-act="clearall">Clear</button>` : ""}</div></div>`;
  }
  const shown = rows.slice(0, ui.n), items = [];
  let hour = null, day = null;
  for (const r of shown) {
    const hk = `${r.ts.toDateString()} ${r.ts.getHours()}`;
    // in Highest price the rows are not in time order, so no hour mark: each row says its own day (hitstop 1912470)
    // the first mark and each new day name the day in full, so a screen that opens on today still says which day it is
    // (Marlin, desk c, Oct 12: the 24-hour view's first screen had no date)
    if (hk !== hour && state.order !== "price") {
      hour = hk; const h = r.ts.getHours(), dk = r.ts.toDateString();
      const named = dk !== day ? `${dk === NOW.toDateString() ? "Today" : r.ts.toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric" })} \u00b7 ` : dayOf(r.ts);
      const prev = new Date(r.ts.getFullYear(), r.ts.getMonth(), r.ts.getDate() - 1), back = dayEnd(prev);
      // a new day's heading takes him to the day before in one press (Marlin, p225: scrolling from Saturday never reached Friday)
      const earlier = dk !== day && back > +NOW - WINDOWS["7d"][1]
        ? `<button class="act quiet small" data-act="upto" data-v="${back}">Show ${dayWordShort(prev)}</button>` : "";
      day = dk; items.push(`<div class="hour"><span>${named}${h % 12 || 12} ${h < 12 ? "AM" : "PM"}</span>${earlier}</div>`);
    }
    items.push(rowHtml(r));
  }
  const more = rows.length > shown.length ? `<div class="more"><span class="count">${n0(shown.length)} of ${n0(rows.length)}</span><button class="act quiet" data-act="more">Show ${n0(Math.min(200, rows.length - shown.length))} more</button></div>`
    : "";
  return `<div class="list">${trouble}${items.join("")}${more}${unsearched}${heldFoot()}${searchFoot()}</div>`;
}
const anyFilter = () => Boolean(ui.cmd || ui.sid || ui.model || ui.account || ui.repo || ui.from || ui.tag || ui.why || ui.compact || ui.outcome !== "all" || ui.q);

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
    traces.set(r.id, { state: tr.cut ? "cut" : "kept", tr, body: res.body });
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
  if (!res.ok) {
    console.warn(`${head} saving: ${refusalOf(res, "no answer")}`);
    state.saving[head] = { word: "Not applied", failed: true };
    render();
    return;
  }
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
// every request with a session opens it, an ended agent included (Sessions reads history), at the moment this request
// finished, so its own answer is on the page (splice-sessions, Oct 12)
// The loaded record as the trace route answered it (TraceRoute), to copy or save whole: what a bug report or a
// provider ticket needs is the exact JSON, not the drawn view. Only once it is read; before that there is nothing to give.
const recordActs = (r) => (traces.get(r.id)?.body
  ? `<button class="act" data-act="copyrec">Copy JSON</button><button class="act" data-act="saverec">Download</button>` : "");
const recordJson = () => { const r = state.rows.find((x) => x.id === ui.open); return r ? JSON.stringify(traces.get(r.id)?.body ?? null, null, 2) : null; };

// The command and the login a request names open their own pages: the command on Models (models.js reads ?cmd=), the
// login on Accounts at that command's card (accounts.js reads ?head=). Plain words where the page is not served.
const served = (file) => PAGES.some(([f]) => f === file);
const headChip = (r) => (served("models.html")
  ? `<a class="chip" href="models.html?${new URLSearchParams({ cmd: r.head })}">${esc(r.cmd)}</a>` : `<span class="chip">${esc(r.cmd)}</span>`);
const acctTag = (r, attrs = "") => (served("accounts.html")
  ? `<a class="acct" href="accounts.html?${new URLSearchParams({ head: r.head })}"${attrs}>${acctHtml(r)}</a>`
  : `<span class="acct"${attrs}>${acctHtml(r)}</span>`);

const openSession = (r) => (r.sid && PAGES.some(([file]) => file === "sessions.html")
  ? `<a class="act" href="sessions.html?at=${+r.ts}#${esc(r.sid)}">Open session</a>` : "");

// Two states a walker could not read off the open request (Marlin, p225, Oct 12), in fin's words. A rate limit splice
// held unsent (attempts 0: the provider kept limiting the account, OutcomeSentences.kt) reads "Not sent" on the
// provider line, in the shape of No answer: the row's word says why, the account says whose, and a reset the row holds
// is the lock beside the word. A resumed or started-over request says what set it off, from the row alone: stall_ms is
// the silence that triggered the re-anchor, and a re-anchor with no silence was a broken stream (PerfKeys REANCHORS).
const LIMITED = (r) => r.outcome === "error:rate-limited" || r.outcome === "error:rate-limit-relayed" || r.outcome === "failure:rate_limit_error"
  || (r.outcome === "error:upstream-failed" && r.cause === "VENDOR_RATE_LIMITED");
function noteHtml(r, who) {
  let out = LIMITED(r) && r.attempts === 0 ? `<p class="upmsg"><span class="who">${esc(who)}</span><span class="state">Not sent</span></p>` : "";
  if (r.resumed > 0) {
    const n = r.resumed, word = r.resumedFresh ? WHY.startedover.word : WHY.resumed.word;
    const text = n > 1 ? `${word} ${n} times${r.stallMs ? ` · ${secs(r.stallMs)} of silence` : ""}`
      : `${word} after ${r.stallMs ? `a ${secs(r.stallMs)} silence` : "the stream broke"}`;
    out += `<p class="upmsg note mend"><span class="state">${text}</span></p>`;
  }
  // A code-mode step its script's source stream ended made no try of its own (Attempts 0): the tries were the source
  // turn's, counted on that turn's row, so they are told here and never added to a retry count (PerfKeys.SOURCE_ATTEMPTS)
  if (r.sourceAttempts != null) {
    const n = r.sourceAttempts;
    out += `<p class="upmsg note"><span class="state">The script's source stream reset after ${n} ${n === 1 ? "try" : "tries"}</span></p>`;
  }
  // A record shown while its command no longer saves them was kept from before saving was turned off, and the daemon
  // deletes a non-saving command's days at its next start (HeadKeptFiles), so the record says both (gap 597)
  if (RV_KEPT(traceState(r)) && state.capture[r.head]?.on === false) {
    out += `<p class="upmsg note"><span class="state">Saved before saving was turned off · deleted when splice next starts</span></p>`;
  }
  return out;
}
function paneHtml() {
  const r = filtered().find((x) => x.id === ui.open) ?? state.rows.find((x) => x.id === ui.open);
  if (!r) return "";
  const o = outcomeOf(r), lamp = r.compact ? GLYPH.compact : o.glyph ? GLYPH[o.glyph] : "<i></i>";
  const word = (o.word ? `<span class="word ${o.cls}">${o.word}</span>` : r.compact ? `<span class="word">Compaction</span>` : "") + besideOf(r);
  // a request nothing answered was not charged: Cost $0 and its attempts, and no token figures, since no model ran (p229)
  const unbilled = r.reason === "unanswered";
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
  return RV.paneHtml(r, { said: said + noteHtml(r, who), lampCls: `${o.cls}${r.compact ? " compact" : ""}`, lamp, word, name: r.compare ? COMPARE_LABEL : sessTitle(r.sid), acct: r.account ? acctTag(r) : "", chip: headChip(r),
    acts: `${openSession(r)}${recordActs(r)}<button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button>`,
    st, gone: RV_KEPT(st) || st === "reading" ? "" : goneHtml(r, st),
    tr: () => traceOf(r), cells: unbilled ? ["cost", "attempts"] : ["in", "write", "out", "cost", "attempts"], cost: costText(r),
    costSub: r.cost == null && r.planValue != null ? `${money(r.planValue)} at API prices` : "" });
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
  // the picker and its doors on their own line above the filters, with no group headers: the log keeps time order
  const tc = state.tileCounts;
  document.getElementById("pickline").innerHTML = state.loading ? ""
    : pickLineHtml(pickGroups(), ui.project, ui.pmenu, { of: (k) => tc?.get(k) ?? null, all: tc?.get("") ?? null, unit: "request" });
  document.getElementById("filters").innerHTML = state.loading ? ghostFiltersHtml() : filtersHtml();
  root.innerHTML = listHtml() + pane;
  // the rest of the day draws as he scrolls: the next rows join when Show more comes into view, and the button stays
  // for a keyboard or a reader that never scrolls (Marlin, Oct 11)
  scrolledMore.disconnect();
  const more = root.querySelector('.more [data-act="more"]');
  if (more) scrolledMore.observe(more);
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
async function reload({ standing = false, reading = false } = {}) {
  tick(); // the kit's clock, read again so a reset reads against the time the page is drawn at
  state.loading = standing;
  if (standing) render();
  // a pick he made reads the list again: the list reads as reading the moment he picks, with its menu closed, and no
  // No match is said for a read still in flight (Marlin, desk c, Oct 12: "10 Sat" sat 2 s on today's list, menu open)
  if (reading) { state.reading = true; render(); }
  if (standing) await readStanding();
  const gen = ++readGen;
  // the newest requests first, drawn as soon as they answer; the rest of the window follows behind them
  await Promise.all([readRows(FIRST_HELD), readSearch()]);
  if (gen !== readGen) return;
  if (state.reading || state.loading) { state.reading = state.loading = false; autoOpen(); render({ keep: false }); const top = ui.open && find(ui.open); if (top) readTrace(top); } // the rows are in: draw them, the menus and counts follow
  await Promise.all([readWider(), readCompareCount().then(readTileCounts), readMenus(gen)]);
  state.loading = false;
  autoOpen();
  render({ keep: false });
  const r = ui.open && find(ui.open);
  if (r) readTrace(r);
  if (state.capped) {
    await readRows();
    if (gen !== readGen) return;
    render();
  }
  // a command still being read is asked again shortly, so its rows join the list without a press
  if (state.pending?.length) setTimeout(() => { if (gen === readGen) reload(); }, PENDING_RETRY_MS);
}

// a search is sent to the daemon, so the page waits for him to stop typing rather than asking on every letter
let typing = null;
document.getElementById("q").addEventListener("input", (e) => {
  ui.q = e.target.value.trim(); ui.n = 200;
  clearTimeout(typing);
  typing = setTimeout(() => { ui.auto = true; reload({ reading: true }); }, 250);
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && ui.menu) { ui.menu = null; render(); }
  if (e.key === "Enter" && e.target.id === "goto") goTo(e.target.value);
});
document.addEventListener("click", (e) => {
  const el = e.target.closest("[data-act], [data-open]");
  // A click outside an open menu only closes it. It never also opens the request under it: a pick in the browser's own
  // calendar landed on the row beneath and opened a request he never chose (Marlin, p225 step 14).
  if (ui.menu && !e.target.closest(".fmenu, [data-act=menu]")) { ui.menu = null; render(); return; }
  if (ui.pmenu && !e.target.closest(".pmenu, [data-act=projmenu]")) { ui.pmenu = false; render(); return; }
  if (!el) return;
  if (!el.dataset.act) { if (e.target.closest("a")) return; open(el.dataset.open); return; }
  const v = el.dataset.v, m = el.dataset.m;
  switch (el.dataset.act) {
    case "theme": toggleTheme(); break;
    case "menu": ui.menu = ui.menu === m ? null : m; render(); break;
    case "projmenu": ui.pmenu = !ui.pmenu; ui.menu = null; render(); break;
    // the same pick as Sessions and Teams: the list is read again for the project, from the newest
    case "pickproj": ui.project = el.dataset.p || null; ui.pmenu = false; PICK.set(ui.project); ui.n = 200; again(); break;
    case "set": ui[m] = v || null; if (m === "win") ui.win = v; ui.menu = null; ui.n = 200; again(); break;
    case "unset": ui[m] = null; if (m === "from") ui.to = null; ui.n = 200; again(); break;
    case "outcome": ui.outcome = v; ui.n = 200; again(); break;
    case "order": ui.order = v; ui.n = 200; again(); break;
    case "startsave": startSaving(el.dataset.h); break;
    case "why": ui.why = ui.why === v ? null : v; ui.n = 200; again(); break;
    case "widen": ui.win = v; ui.n = 200; again(); break;
    case "toggle": ui[m] = !ui[m]; ui.n = 200; again(); break;
    case "clearall": Object.assign(ui, { cmd: null, sid: null, model: null, account: null, repo: null, from: null, to: null, outcome: "all", tag: null, why: null, compact: false });
      { const q = document.getElementById("q"); q.value = ""; ui.q = ""; } again(); break;
    // the search's own Clear empties the search and keeps every filter he set, Failed included (Marlin, desk c, Oct 12)
    case "clearq": { const q = document.getElementById("q"); q.value = ""; ui.q = ""; ui.n = 200; again(); break; }
    case "goto": goTo(document.getElementById("goto")?.value); break;
    case "upto": upTo(v ? +v : null); scrollTo(0, 0); break;
    case "more": ui.n += 200; render(); break;
    case "copyrec": { const j = recordJson(); if (j) navigator.clipboard?.writeText(j).then(() => { el.textContent = "Copied"; setTimeout(() => { el.textContent = "Copy JSON"; }, 1600); }, () => {}); break; }
    case "saverec": {
      const j = recordJson(); if (!j) break;
      const a = document.createElement("a"), url = URL.createObjectURL(new Blob([j], { type: "application/json" }));
      a.href = url; a.download = `splice-request-${ui.open}.json`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 0);
      break;
    }
    case "close": {
      ui.open = null; ui.auto = false; render();
      const at = ui.listAt; ui.listAt = null;
      if (at) { const list = root.querySelector(".list"); if (list) list.scrollTop = at.list; scrollTo(0, at.y); }
      break;
    }
    case "tab": case "node": case "frames": RV.click(el); render({ keep: true }); break; // the open request's own controls (reqview.js)
  }
});
/** Lists the requests up to [value] (the menu's day and time), in the narrowest window that reaches back to it. */
function goTo(value) {
  const at = value ? +new Date(value) : NaN;
  if (Number.isFinite(at)) upTo(at + 59_999); // the whole minute he picked
}
/** Lists the requests up to [at], or up to now when [at] is null or not past, in the narrowest window that reaches it. */
function upTo(at) {
  const to = at == null || at >= +NOW ? null : at;
  if (to != null) ui.win = Object.keys(WINDOWS).find((k) => +NOW - WINDOWS[k][1] <= to) ?? "7d";
  Object.assign(ui, { to, from: null, menu: null, n: 200 }); again();
}
// the last millisecond of [d]'s day, by the calendar, so a day with a clock change still ends at its own midnight
const dayEnd = (d) => +new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1) - 1;
// a day in fin's form, weekday then date, "Fri 9": built by hand, since the browser's own short form put the date first ("9 Fri")
const dayWordShort = (d) => `${d.toLocaleDateString("en-US", { weekday: "short" })} ${d.getDate()}`;
// a filter the daemon owns is a new read; one that hides the open request moves the page to the top match when the
// page opened it, and keeps his pick otherwise
function again() { ui.auto = ui.auto || !filtered().some((r) => r.id === ui.open); reload({ reading: true }); }
const autoOpen = () => {
  if (wide() && ui.auto) ui.open = ordered(filtered())[0]?.id ?? null;
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
ui.repo = ADDR.get("repo") ?? ui.repo; // ?project= is the pick (kit PICK), read where ui is made
if (ADDR.get("from")) { ui.from = +ADDR.get("from"); ui.to = ADDR.get("to") ? +ADDR.get("to") : null; } // no end: through now
if (WHY[ADDR.get("why")]) ui.why = ADDR.get("why"); // Started over's door arrives with its toggle pressed
if (["clean", "stopped", "fail"].includes(ADDR.get("outcome"))) ui.outcome = ADDR.get("outcome");
// one kind of failure as a person reads it: its outcome tag, or every tag under one word (Rate limited is three; marlin)
if (/^(error|failure):[a-z0-9_-]+(,(error|failure):[a-z0-9_-]+)*$/.test(ADDR.get("tag") ?? "")) ui.tag = ADDR.get("tag");
if (ADDR.get("compact") === "1") ui.compact = true; // Usage's compactions count opens with the Compactions toggle on
if (ADDR.get("order") === "price") ui.order = "price"; // Usage's Breakdown counts open the highest price first
if (ADDR.get("q")) { ui.q = ADDR.get("q"); document.getElementById("q").value = ui.q; }
// a door that names one request shows that request, never the top row. Doors encode the id ("claude-splice%3A…"), so
// it is decoded before it is matched: matched raw, Settings' Open it landed on the unfiltered top (Marlin's rewalk 4)
const askedOf = () => { try { return decodeURIComponent(location.hash.slice(1)); } catch { return location.hash.slice(1); } };
const asked = askedOf();
ui.auto = !asked;
const showAsked = (asked) => {
  const r = state.rows.find((x) => x.id === asked || x.turn === asked);
  if (!r) return;
  const at = ordered(filtered()).findIndex((x) => x.id === r.id);
  if (at >= ui.n) { ui.n = Math.ceil((at + 1) / 200) * 200; }
  open(r.id);
  requestAnimationFrame(() => document.querySelector(`[data-open="${CSS.escape(r.id)}"]`)?.scrollIntoView({ block: "center", behavior: "instant" }));
};
reload({ standing: true }).then(() => { if (asked) showAsked(asked); });
// a door followed while Requests is already open changes only the address's fragment, and the page follows it too
addEventListener("hashchange", () => { const id = askedOf(); if (id) showAsked(id); });
