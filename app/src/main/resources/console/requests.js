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
//   GET /api/heads/{head}/trace?turn=ID&view=outline   headers and timing, with every document body deferred
//   GET /api/heads/{head}/trace/document              one expanded branch or text page of a selected body
//   GET /api/sessions                       the session each request belongs to, by its name, and the project it ran in
//   GET /api/keys                           a key account's variable and the day splice first saw it
//   GET /api/config (knobs.js)              how long prompts and answers are kept, which is how far back a search can
//                                           look: the figure comes from the setting, never from a cut this page made up
//
// A FIGURE NOBODY REPORTED IS LEFT OUT, never printed as a zero: a provider that reported no tokens, a request that
// never got a first byte and a turn with no price each draw nothing where the number would be.
CONSOLE.page("requests.html", (scope) => {
  "use strict";
  const { document, window, API, setTimeout, clearTimeout, setInterval, clearInterval, requestAnimationFrame, cancelAnimationFrame, addEventListener, removeEventListener, IntersectionObserver } = scope;

// ---------- what this page adds to the kit ----------
// kit.js carries the console's shared language: NOW and tick(), esc, G, ICON, COLORS, colorOf, clock, dayWord, md,
// OUTCOME_WORD, STOPPED, isClean, WHY and sideBySide. Only what Requests alone needs is here.

// the open request is reqview.js, shared with Compare models, and so are its clock and count words
const { clockS, dayOf, n0, secs, GLYPH } = REQVIEW;
const RV = REQVIEW.view(() => ui, { API, document, IntersectionObserver, render });
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
// A compaction whose summary came back empty is splice's empty_compact (OutcomeTag.kt): a failure, worded the way the
// console names a compaction's result, so the band's legend never prints the tag itself
OUTCOME.empty_compact = { cls: "fail", word: "Empty summary", glyph: "bang" };
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
  // a stall splice gave up on and a turn past its cap are one ending, the give-up's word, as WHY.gaveup counts them both
  // (OUTCOME_WORD names no turn-cap, so a stalled row used to read "undefined")
  if ((r.outcome === "failure:overloaded_error" && isStalled(r)) || r.outcome === "error:turn-cap") return { cls: "fail", glyph: GLYPH_OF[r.outcome], ...base, word: WHY.gaveup.word };
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
// Retries are splice mending a request, never a failure, so they take body ink like a resume. On a [row], the mend the
// pressed toggle already names is not said again.
// [resetSaid]: a burst row above already names the reset every row in it shares, so the row does not say it again
const besideOf = (r, row = false, resetSaid = false) => {
  if (r.reset) return resetSaid ? "" : `<span class="detail">${ICON.lock}${resetWord(r)}</span>`;
  const why = r.resumedFresh ? "startedover" : "resumed", said = row && (ui.why === why || ui.why === (r.resumedFresh ? "silentover" : "silentresume"));
  const mend = r.resumed && !said ? `<span class="detail mend">${WHY[why].word}</span>` : "";
  // on a row the retries are the lamp's retries glyph and their count, named once in the list's key, so a long session
  // name and what happened to it keep one line; the open request, which has the room, says them in words
  const tries = !r.retries ? "" : row ? `<span class="detail mend rt">${ICON.retries}${r.retries}</span>`
    : `<span class="detail mend">${r.retries} ${r.retries === 1 ? "retry" : "retries"}</span>`;
  return mend + tries;
};
// why a request has no dollar figure (TurnPriceGap.kt), in fin's words; one that was never answered says nothing. A row
// draws the dash and keeps the reason under the pointer; the open request says it under its Cost.
const REASON = { plan: "On your plan", local: "This computer", vast: "vast.ai", uncounted: "Tokens not counted", undeclared: "No price" };
// A key is an account: its variable and the day splice first saw THAT key, "OPENROUTER_API_KEY · Sep 14" (fin's
// words). A row names the key it was sent with as `VARIABLE:fingerprint` (KeyAccount.kt), so a rotated key reads as
// its own account and the one it replaced keeps its own history, marked Replaced — on Requests as on Accounts
// (Marcos, Oct 8). A plan's label has no colon and no key behind it, and prints as it stands.
const keyNameOf = (account) => { const s = String(account ?? ""), at = s.lastIndexOf(":"); return at < 0 ? s : s.slice(0, at); };
// A key reads as its provider, the heading Accounts draws over it and the name Continue on uses; its variable joins
// only when two keys of that provider feed the same command, so the line says which one it sent (fin).
const acctHtml = (r) => {
  // a login reads the name he gave it, else the console's one rule (kit DRAW.name): never an email, an id or the bare word Account
  if (r.acctLabel) return esc(DRAW.name("login", { name: r.acctLabel, provider: r.provider }).text);
  const key = state.keySeen[r.account], name = key?.name ?? keyNameOf(r.account), provider = PROVIDER_NAME[r.provider];
  const isKey = Boolean(key) || /^[A-Z0-9_]+$/.test(name);
  if (!isKey || !provider) return key ? `<span class="var">${esc(key.name)}</span> · ${dayWord(key.seen)}${key.replaced ? `<span class="detail">Replaced</span>` : ""}` : esc(state.soleAccount?.[r.head] ?? (name || r.account));
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
// fact, never "No price", which is a model answering with no rate card (Marlin, p229 on desk a, Oct 12). The open
// request's Cost is the figure: a key's dollars, a plan request's worth at API prices with "On your plan" under it and
// the list's hollow mark, or the dash with the reason under it. The figure is never a word.
const costOf = (r) => {
  if (r.cost != null) return { v: money(r.cost), sub: "" };
  if (r.reason === "unanswered") return { v: money(0), sub: "" };
  if (r.planValue != null) return { v: money(r.planValue), sub: `<i class="sw plan" aria-hidden="true"></i>${REASON.plan}` };
  return { v: DRAW.dash, sub: REASON[r.reason] ? esc(REASON[r.reason]) : "" };
};
const DOWN = G('<path d="M6 9l6 6 6-6"/>', 'class="down" aria-hidden="true"');

// ---------- the filters, as /api/perf/turns takes them (TurnsFilter.kt), plus the search over bodies ----------
const WINDOWS = { "1h": ["1 hour", 3600e3], "5h": ["5 hours", 5 * 3600e3], "24h": ["24 hours", 24 * 3600e3], "7d": ["7 days", 7 * 24 * 3600e3] };
// priced at the model's card: under a cent it is kit's dollars (two significant digits, exactly 0 is "$0"), and from a
// cent a request keeps a tenth of a cent, so two small requests side by side still differ
const money = (usd) => (usd < 0.01 ? dollars(usd) : `≈$${usd.toFixed(usd < 1 ? 3 : 2)}`);
// the same figure in a column whose header already calls the cost estimated: the mark it would repeat on every row
// goes, the tenth of a cent stays, since two small requests side by side differ there and not at two decimals
const moneyFig = (usd) => money(usd).replace(/^≈/, "");
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
  // A project's count is a number, null while a head is still reading it (the tile shows stripes, which move), or false
  // when a head's read answered with a failure (the tile shows a dash, which stands still): one failed head means the
  // total will not come, whatever the others say.
  keys.forEach((k, i) => {
    let n = 0;
    heads.forEach((h, j) => {
      const r = reads[i * heads.length + j], a = (r.body?.heads || []).find((x) => x.key === h.key);
      if (n === false || !r.ok || !a || a.error) n = false;
      else if (n === null || a.read_pending) n = null;
      else n += a.count ?? 0;
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
  rows: [], matched: 0, capped: false, facets: { models: [], accounts: [], sessions: [] }, headErrors: [], sessionsError: null, sessionsPending: true, traceDays: null, capture: {}, config: null,
  // what Start saving answered, per command: { word, failed }, the word that stands beside "Not saved" once pressed
  saving: {},
  // what the search answered: how far back it read, why it stopped, and the turns it could not reach
  search: null,
  // when the window lists nothing: the nearest wider window that has matches, and how many (readWider)
  wider: null,
};
// bursts: the runs of failures he opened; kindsOpen and byAll: the band's two lists he opened past their first five
let ui = { project: PICK.get(), pmenu: false, open: null, q: "", win: "24h", cmd: null, sid: null, model: null, account: null, repo: null, from: null, to: null,
  outcome: "all", tag: null, why: null, compact: false, menu: null, n: 200, tab: "sent", nodes: new Set(), bursts: new Set(), kindsOpen: false, byAll: false,
  order: "new" }; // Newest or Highest price (fin): what the read asks for; the list shows what the daemon says it applied
const root = document.getElementById("requests");
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
  if (state.sessionsPending) return undefined;
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
  const sessionsRead = API.get("/api/sessions");
  const [heads, models, keys, topology, retired, accounts] = await Promise.all([
    API.get("/api/heads"), API.get("/api/models"), API.get("/api/keys"), API.get("/api/topology"),
    API.get("/api/heads/retired"), API.get("/api/accounts"),
  ]);
  // A COMMAND WITH ONE LOGIN names it as Accounts does: a login the row names only by its slot in the config ("primary")
  // reads the name its Accounts card carries ("Account" until he names it), so one login has one name on both pages
  state.soleAccount = {};
  const serving = {};
  for (const a of accounts.body?.accounts || []) for (const h of a.heads || []) (serving[h] ??= []).push(a.display_name);
  for (const [h, names] of Object.entries(serving)) if (names.length === 1 && names[0]) state.soleAccount[h] = names[0];
  state.error = heads.ok ? null : LIST_NOT_READ;
  if (!heads.ok) console.warn(`commands: ${refusalOf(heads, "no answer")}`);
  state.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key, reanchorMs: h.gate?.stall_reanchor_ms ?? null, idleMs: h.gate?.stream_idle_ms ?? null, firstByteMs: h.gate?.first_byte_timeout_ms ?? null }));
  state.heads.push(...retiredHeads(retired, state.heads));
  // A provider splice reaches on this machine's own loopback is served from THIS COMPUTER, whatever it is called (kit.js
  // providerFamilyOf, which Settings reads the same way): his own model servers and the tunnel to a rented GPU both
  // answer at 127.0.0.1 (splice.toml base_url), and both draw in the one colour the kit gives a local provider.
  const bases = topology.body?.topology?.providers || {};
  for (const row of models.body?.heads || []) {
    state.providerOf[row.head] = providerFamilyOf(row.provider, bases);
    learnModels(state.modelLabel, row);
  }
  const running = state.heads.filter((h) => !h.retired); // a removed command runs nothing and keeps no capture setting
  // A cold session listing only delays its names and project lamps, never the request list itself.
  Promise.all([sessionsRead, Promise.all(running.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/turns/live`)))]).then(([sessions, lives]) => {
    state.sessionsPending = false;
    state.sessionsError = sessions.ok ? null : LIST_NOT_READ;
    if (!sessions.ok) console.warn(`session names: ${refusalOf(sessions, "no answer")}`);
    state.sessions = {};
    for (const s of sessions.body?.sessions || []) {
      if (!s.session_id) continue;
      state.sessions[s.session_id] = s;
      state.sessions[String(s.session_id).slice(0, 8)] = s;
    }
    const turns = {};
    running.forEach((h, i) => { for (const t of lives[i].body?.turns || []) if (t.session && !t.stopped) turns[t.session] = { head: h.key, ...t }; });
    state.groups = projectGroups(sessions.body?.sessions || [], turns, state.heads);
    const before = ui.project;
    ui.project = reconcilePick(ui.project, state.groups);
    // the tiles' counts are read per project, so they wait on the projects: a window read that answered first found
    // no project to count and left every tile's figure hollow for good, so they are asked here once the projects land
    if (before !== ui.project) reload({ reading: true }); else { render(); readTileCounts().then(render); }
  });
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
  const [caps, cfg] = await Promise.all([
    Promise.all(running.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/capture`))), API.get("/api/config"),
  ]);
  running.forEach((h, i) => {
    const body = caps[i].ok ? caps[i].body : null;
    if (body) state.capture[h.key] = { on: body.enabled === true, days: body.retention_days ?? null };
  });
  const days = uniq(running.map((h) => state.capture[h.key]?.days).filter((d) => d != null));
  state.traceDays = days.length === 1 ? days[0] : null; // one figure to say only when every command agrees on it
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

/** The filters he set, as the daemon takes them, added to [p]. [outcome] false leaves out every filter that names an
 *  outcome (the switch, a tag, and a why that is an outcome), which is the band's ground: the band splits the outcomes
 *  itself. [why] and [compact] stand in for his own when a control counts what pressing it would list. */
// WHY_ASKS (what a door's "what happened" asks the daemon for) lives in kit.js, where Settings counts the same requests.
const OUTCOME_WHYS = new Set(["gaveup", "overloaded", "cutoff", "restarted"]);
function addFilters(p, head, { outcome = true, why = ui.why, compact = ui.compact, project = ui.project } = {}) {
  // an exact tag is narrower than the switch's family and agrees with it, except when the switch says Stopped or
  // Answered and the door names a failure: then they disagree and the switch holds, which lists nothing, honestly
  const apart = ui.outcome === "stopped" || ui.outcome === "clean";
  if (outcome) {
    if (ui.outcome === "fail") p.set("outcome", "failed");
    if (ui.outcome === "stopped") p.set("outcome", "stopped");
    if (ui.outcome === "clean") p.set("outcome", "ok,empty_message"); // kit isClean: answered
    // one kind of failure (?tag=, Usage's table)
    if (ui.tag && !apart) p.set("outcome", ui.tag);
  }
  if (ui.model === "none") p.set("unattributed", "model");
  else if (ui.model) p.set("model", ui.model);
  if (ui.account === "none") p.set("unattributed", "account");
  else if (ui.account) p.set("account", ui.account.split("|").slice(1).join("|"));
  if (ui.sid) p.set("session", String(ui.sid).slice(0, 8));
  // the daemon narrows to the project's sessions itself (TurnsFilter.inProject), No project included (project=none), so the newest 2,000 are its own
  if (project) p.set("project", project === NO_PROJECT_KEY ? "none" : project); // none: the daemon lists the requests no project holds
  if (compact) p.set("compact", "1");
  if (ui.to) p.set("until", String(ui.to));
  if (why && (outcome || !OUTCOME_WHYS.has(why))) {
    for (const [key, value] of Object.entries(WHY_ASKS[why] || {})) {
      const tier = value === "tier" ? idleTier(head) : null;
      if (key === "outcome" && (apart || !outcome)) continue;
      // no answered tier is no floor to ask for: the page's own check decides alone, as it does today
      if (tier != null && !Number.isFinite(tier)) continue;
      p.set(key, tier != null ? String(Math.round(tier)) : value);
    }
    // the words the daemon tests itself are listed by that test, so the list holds exactly what a Settings tile counted
    if (ROW_TEST[why]) p.set("test", ROW_TEST[why]);
  }
  // a named stretch begins at the daemon too, or a cap on the newest rows would cut the stretch's start off
  if (ui.from) p.set("from", String(+ui.from + 1));
  return p;
}
/** One head's window: the rows the filters asked for, or the window's own menus from an unfiltered read. */
function turnsPath(head, filtered, win = ui.win, held = MAX_HELD, project = ui.project) {
  // local=0 on both reads, so the menus count the same requests the list does
  const p = new URLSearchParams({ head: head.key, since: String(since(win)), time_zone: ZONE, n: filtered ? String(held) : "1", local: "0" });
  if (filtered) {
    // a list read is rows or a count, never totals: the band's one fold carries them (readBand)
    p.set("usage", "0");
    addFilters(p, head, { project });
    if (ui.order === "price") p.set("order", "price"); // the daemon keeps each command's highest priced, not its newest
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
  let matched = 0, capped = false, priced = ui.order === "price", reach = -Infinity;
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
    const mine = (answer.rows || []).map((row) => mapRow(head, row));
    // a capped command carried nothing older than its oldest row: an hour before that holds more than the list shows
    if (answer.truncated) { capped = true; if (mine.length) reach = Math.max(reach, Math.min(...mine.map((r) => +r.ts))); }
    rows.push(...mine);
  });
  state.reach = reach;
  state.rows = linkRetries(linkCuts(rows));
  state.slowAt = slowLines(state.rows);
  state.matched = matched;
  state.capped = capped;
  state.order = priced ? "price" : "new";
  state.held = held;
  state.headErrors = errors;
  state.pending = pending;
}
/** The menus' models, accounts and sessions, from each command's unfiltered answer, so a menu never lists only what
 *  the current filter left. */
function facetsOf(heads, answers) {
  const facets = { models: new Map(), accounts: new Map(), sessions: new Map() };
  heads.forEach((head, i) => {
    const menus = answers[i]?.usage;
    for (const m of menus?.models || []) if (m.key) facets.models.set(m.key, modelName(state.modelLabel, m.key));
    for (const a of menus?.accounts || []) if (a.key) facets.accounts.set(`${head.key}|${a.key}`, { head, account: a.key, label: a.label ?? null });
    for (const s of menus?.sessions || []) if (s.key) facets.sessions.set(s.key, { head, last: s.last_model_ts_epoch_ms });
  });
  return { models: [...facets.models], accounts: [...facets.accounts], sessions: [...facets.sessions] };
}

/** Several questions about one command's window in ONE read: every `ask` is its own filter set over the same since
 *  and zone, answered under `asks` in order (PerfRoutes askedWindow), so the band's counts cost one pass over the
 *  command's rows. Split where the address would pass what the daemon reads (Netty's 4,096 bytes), each further part
 *  under a bare count. A daemon that answers no asks says so by their absence: [asks] comes back null. */
const URL_MAX = 3600;
async function askAll(head, main, asks) {
  const base = `head=${encodeURIComponent(head.key)}&since=${since()}&time_zone=${encodeURIComponent(ZONE)}`, bare = "n=1&local=0&usage=0";
  const parts = [[]];
  let len = 16 + base.length + main.length;
  for (const a of asks) {
    const add = 5 + encodeURIComponent(a).length;
    if (len + add > URL_MAX && parts.at(-1).length) { parts.push([]); len = 16 + base.length + bare.length; }
    parts.at(-1).push(a);
    len += add;
  }
  const reads = await Promise.all(parts.map((list, i) => API.getReady(`/api/perf/turns?${base}&${i ? bare : main}${list.map((a) => `&ask=${encodeURIComponent(a)}`).join("")}`)));
  const answerOf = (res) => (res.ok ? (res.body?.heads || []).find((x) => x.key === head.key) ?? null : null);
  const first = answerOf(reads[0]);
  if (!first || first.error || first.read_pending || !Array.isArray(first.asks)) return { main: first, asks: null };
  return { main: first, asks: parts.flatMap((list, i) => list.map((_, j) => answerOf(reads[i])?.asks?.[j] ?? null)) };
}

// ---------- the band: the window's answer before a row is read ----------
// ONE READ PER COMMAND answers it (askAll): the window under every filter but the outcome, folded (requests, tokens,
// cost and what each menu item holds), the menus' names, its failed rows to tell the kinds apart, the stopped count,
// and what each toggle would list. A command whose failed rows were capped counts each kind they showed at the daemon,
// and what is left of its exact total is Other failures: a remainder, never a guess. The band keeps its last answer
// while the next is read, and draws its own shape, hollow, only for a window it has never answered.
const bandScope = () => JSON.stringify([askedHeads().map((h) => h.key), ui.win, ui.from, ui.to, ui.project, ui.model, ui.account, ui.sid, ui.compact,
  OUTCOME_WHYS.has(ui.why) ? null : ui.why]);
// THE BAND COUNTS WHAT THE LIST HOLDS. Outcomes splits every outcome of the window, so the failure rate keeps its
// meaning, and marks the part he pressed; Tokens and Cost count only what an outcome, a kind or a why he pressed lists,
// asked of the daemon as one more ask in the same read.
const selecting = () => ui.outcome !== "all" || Boolean(ui.tag) || OUTCOME_WHYS.has(ui.why);
const selScope = () => JSON.stringify([bandScope(), ui.outcome, ui.tag, OUTCOME_WHYS.has(ui.why) ? ui.why : null]);
/** A failure's kind as the band splits them: the give-up, the cut-off and the overload are one tag the row tells
 *  apart; every other kind is its tag's word, so its door (tag=) lists exactly what it counted. */
function kindOf(r) {
  if (r.outcome === "failure:overloaded_error" || r.outcome === "error:turn-cap") {
    const why = WHY.cutoff.has(r) ? "cutoff" : WHY.gaveup.has(r) ? "gaveup" : "overloaded";
    return { key: `why:${why}`, word: WHY[why].word, why };
  }
  const word = r.outcome === "error:plan-limit" ? "Plan limit" : OUTCOME[r.outcome]?.word || tagWord(r.outcome) || "Unnamed failure";
  return { key: `w:${word}`, word, tag: r.outcome };
}
const addKind = (kinds, k, n = 1) => {
  const g = kinds.get(k.key) ?? kinds.set(k.key, { ...k, tags: new Set(), n: 0 }).get(k.key);
  g.n += n;
  for (const t of k.tags ?? (k.tag ? [k.tag] : [])) g.tags.add(t);
};
async function readBand(gen) {
  const heads = askedHeads(), scope = bandScope();
  const ask = (head, set, opts = { outcome: false }) => {
    const p = addFilters(new URLSearchParams({ local: "0" }), head, opts);
    for (const [k, v] of Object.entries(set)) p.set(k, v);
    return p.toString();
  };
  const count = { n: "1", usage: "0" }, sel = selecting();
  const reads = await Promise.all(heads.map((head) => askAll(head, ask(head, { n: "1" }), [
    "n=1&local=0&usage=names",
    ask(head, { outcome: "failed", n: String(MAX_HELD), usage: "0" }),
    ask(head, { outcome: "stopped", ...count }),
    ask(head, count, { why: "startedover" }),
    ask(head, count, { compact: true }),
    ...(sel ? [ask(head, { n: "1" }, { outcome: true })] : []),
  ])));
  if (gen !== readGen) return;
  // a daemon that answers no asks: the menus the old way, and the band counts the listed rows
  if (reads.some((x) => x.main && !x.main.error && !x.main.read_pending && !x.asks)) {
    const menus = await Promise.all(heads.map((h) => API.getReady(turnsPath(h, false))));
    if (gen !== readGen) return;
    state.facets = facetsOf(heads, menus.map((res, i) => (res.body?.heads || []).find((x) => x.key === heads[i].key)));
    state.band = { scope, old: true };
    return;
  }
  const b = { scope, m: 0, failed: 0, stopped: 0, over: 0, compact: 0, other: 0, kinds: new Map(), totals: {}, perHead: new Map(), failedOf: new Map(),
    models: new Map(), accounts: new Map(), sessions: new Map(), missing: [],
    sel: sel ? { scope: selScope(), m: 0, totals: {}, sessions: new Map() } : null };
  const fold = (map, key, x) => { const into = map.get(key) ?? map.set(key, {}).get(key); for (const [k, v] of Object.entries(x)) if (typeof v === "number") into[k] = (into[k] ?? 0) + v; };
  const capped = [];
  heads.forEach((head, i) => {
    const { main, asks } = reads[i];
    if (!main || main.error || main.read_pending || !asks) { b.missing.push({ cmd: head.command, head: head.key, pending: Boolean(main?.read_pending) }); return; }
    const [, failed, stopped, over, compact, picked] = asks;
    if (b.sel && picked) {
      b.sel.m += picked.count ?? 0;
      for (const [k, v] of Object.entries(picked.usage?.totals || {})) if (typeof v === "number") b.sel.totals[k] = (b.sel.totals[k] ?? 0) + v;
      for (const x of picked.usage?.sessions || []) if (x.key) fold(b.sel.sessions, x.key, x);
    }
    b.m += main.count ?? 0;
    b.perHead.set(head.key, main.count ?? 0);
    b.failedOf.set(head.key, failed?.count ?? 0);
    for (const [k, v] of Object.entries(main.usage?.totals || {})) if (typeof v === "number") b.totals[k] = (b.totals[k] ?? 0) + v;
    for (const x of main.usage?.models || []) if (x.key) fold(b.models, x.key, x);
    for (const x of main.usage?.accounts || []) if (x.key) fold(b.accounts, `${head.key}|${x.key}`, x);
    for (const x of main.usage?.sessions || []) if (x.key) fold(b.sessions, x.key, x);
    b.failed += failed?.count ?? 0;
    b.stopped += stopped?.count ?? 0;
    b.over += over?.count ?? 0;
    b.compact += compact?.count ?? 0;
    const mine = new Map();
    for (const row of failed?.rows || []) addKind(mine, kindOf({ outcome: row.outcome, cause: row.cause ?? null }));
    if (failed?.truncated) capped.push({ head, kinds: [...mine.values()], total: failed.count ?? 0 });
    else for (const k of mine.values()) addKind(b.kinds, k, k.n);
  });
  // a capped command: each kind its newest failures showed, counted at the daemon, and the rest of its exact total
  await Promise.all(capped.map(async ({ head, kinds, total }) => {
    const res = await askAll(head, "n=1&local=0&usage=0", kinds.map((k) => ask(head, k.why
      ? { ...WHY_ASKS[k.why], test: ROW_TEST[k.why], ...count } : { outcome: [...k.tags].join(","), ...count })));
    let seen = 0;
    kinds.forEach((k, j) => { const n = res.asks?.[j]?.count; if (n == null) return; seen += n; addKind(b.kinds, k, n); });
    b.other += Math.max(0, total - seen);
  }));
  if (gen !== readGen) return;
  state.facets = facetsOf(heads, reads.map((x) => x.asks?.[0]));
  state.band = b;
}

/** One perf row as this page draws it. A key the writer never put in the row is left null, never zeroed: a provider
 *  that reported no tokens and a request that never got a first byte draw nothing where their figure would be. */
// SLOW, RETRIED AND FAILED LOOK DIFFERENT (v0.4.0 section 3's acceptance). A failure has its glyph and its red edge. A
// request that answered after more than one send takes the retries glyph in its lamp and says its retries. SLOW IS
// DRAWN: every row's time sits on one log axis with a tick where the slowest 5% of the same command's same model in
// this list begins, so a slow request's bar runs past its own tick (the redesign, Oct 26; it read the word Slow in the
// key light). The line is that model's own, never one figure across a fast model and a slow one, and it is drawn only
// from 20 clean answers up, so a short list marks nothing rather than guessing. The open request scales its rail to it.
const SLOW_SHARE = 0.95, SLOW_MIN_ROWS = 20;
const answered = (r) => !r.compact && outcomeOf(r).cls === "clean";
function slowLines(rows) {
  const by = new Map();
  for (const r of rows) if (answered(r)) { const k = `${r.head}|${r.modelId}`; (by.get(k) ?? by.set(k, []).get(k)).push(r.total); }
  const lines = new Map();
  for (const [k, xs] of by) if (xs.length >= SLOW_MIN_ROWS) { xs.sort((a, b) => a - b); lines.set(k, { typ: xs[Math.floor(0.5 * (xs.length - 1))], slow: xs[Math.floor(SLOW_SHARE * (xs.length - 1))] }); }
  return lines;
}
const slowLine = (r) => state.slowAt?.get(`${r.head}|${r.modelId}`) ?? null;
const isRetried = (r) => answered(r) && r.attempts > 1;
/** A row's lamp, its class and the words beside its name, the same on the list's row and in the open request. On a
 *  [row], a word the filter he set already says (its chip, or its pressed toggle) is not said again on every row. */
function stateOf(r, row = false, resetSaid = false) {
  const o = outcomeOf(r), retried = isRetried(r);
  const said = row && ((ui.tag && ui.tag.split(",").includes(r.outcome)) || (OUTCOME_WHYS.has(ui.why) && WHY[ui.why].has(r)));
  const word = o.word ? (said ? "" : `<span class="word ${o.cls}">${o.word}</span>`)
    : r.compact ? (row && ui.compact ? "" : `<span class="word">Compaction</span>`) : retried && !r.retries ? `<span class="word">Retried</span>` : "";
  return {
    o, retried,
    lamp: r.compact ? GLYPH.compact : o.glyph ? GLYPH[o.glyph] : retried ? GLYPH.retries : "<i></i>",
    lampCls: `${o.cls}${r.compact ? " compact" : ""}${retried ? " retried" : ""}`,
    word: word + besideOf(r, row, resetSaid),
  };
}
// THE WATERFALL'S COUNTERS (web-console.md, Turn waterfall): what the bar alone cannot say, each drawn only when it
// happened, so a clean request draws none (Marcos stopped a walk on walls of figures). Attempts already stands in the
// figures above, so retries are not said twice. Bytes each way show only on a request that did not answer cleanly,
// where they say how far it got. async_io_drops is left out: the row carries the head's running total, not this
// request's (PerfKeys.ASYNC_IO_DROPS), so on one request it would mislead.
const sizeWord = (b) => (b >= 1e6 ? `${+(b / 1e6).toFixed(b >= 1e7 ? 0 : 1)} MB` : b >= 1e3 ? `${Math.round(b / 1e3)} KB` : `${b} B`);
// Each counter is a tile: its glyph, its figure and a name of two or three words, with the longer reading under the
// pointer. The tool searches draw the share of the tools splice deferred as a ring, the share that sentence held.
function countsOf(row) {
  const c = [], n = (k) => row[k] ?? 0, tools = n("tools_eager") + n("tools_deferred");
  if (n("backoff_ms") > 0) c.push({ glyph: "gap", n: secs(n("backoff_ms")), label: "Waited between sends" });
  if (n("post_send_retries") > 0) c.push({ glyph: "retry", n: n0(n("post_send_retries")), label: "Retried after sending", title: "Sent again after its body had already gone out" });
  if (n("refreshes") > 0) c.push({ glyph: "key", n: n0(n("refreshes")), label: "Sign-ins renewed" });
  if (n("search_rounds") > 0) {
    const said = tools ? `${n0(n("tools_deferred"))} of ${n0(tools)} tools deferred` : null;
    c.push({ glyph: "label", n: n0(n("search_rounds")), label: "Tool searches", share: tools ? n("tools_deferred") / tools : null, title: said });
  }
  if (!isClean(row.outcome) && !STOPPED.has(row.outcome)) {
    const sent = row.upstream_req_bytes ?? row.req_bytes;
    if (sent > 0) c.push({ n: sizeWord(sent), label: "Sent" });
    if (row.sse_bytes_in > 0) c.push({ n: sizeWord(row.sse_bytes_in), label: "Received" });
  }
  return c;
}
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
    attempts: row.attempts ?? 0, retries: row.retries ?? 0, counts: countsOf(row), sourceAttempts: row.source_attempts ?? null,
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
const hitPasses = (r) => (ui.outcome === "all" || outcomeOf(r).cls === ({ fail: "fail", stopped: "stopped", clean: "clean" })[ui.outcome])
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
/** What picking a menu's item would list, from the band's fold: only where the fold says it (every request, or the
 *  failed ones under Failed), and only while that menu's own filter is off, since the fold counts within it. */
function menuCounts(key) {
  const b = bandOf();
  if (!b || b.listed != null || ui[key] || ui.tag || OUTCOME_WHYS.has(ui.why) || !["all", "fail"].includes(ui.outcome)) return null;
  const f = ui.outcome === "fail" ? "failed_requests" : "requests";
  if (key === "cmd") return { all: ui.outcome === "fail" ? b.failed : b.m, of: (k) => (ui.outcome === "fail" ? b.failedOf : b.perHead).get(k) };
  const map = b[{ model: "models", account: "accounts", sid: "sessions" }[key]];
  return { all: ui.outcome === "fail" ? b.failed : b.m, of: (k) => map.get(k)?.[f] ?? 0 };
}
function menuHtml(key) {
  const c = key === "win" ? null : menuCounts(key);
  // EVERY ITEM SAYS WHAT PICKING IT WOULD LIST, and draws its share of the menu's largest, so the busiest command,
  // model, account or session is found before a digit is read. The Every item carries the whole and no bar, since the
  // others are shares of it.
  const count = (n, most) => (n == null ? "" : `<span class="mc">${most > 0 ? DRAW.vbar(pctOf(n, most)) : ""}<b class="mn">${n0(n)}</b></span>`);
  const item = (v, label, cur, o = {}) => `<button${o.cls ? ` class="${o.cls}"` : ""} role="menuitemradio" aria-checked="${cur}" data-act="set" data-m="${key}" data-v="${esc(v)}">${o.extra ?? ""}${label}${count(o.n, o.most ?? 0)}</button>`;
  const every = (word, cur) => item("", word, cur, { n: c?.all ?? null });
  const mostOf = (keys) => (c ? Math.max(0, ...keys.map((k) => c.of(k) ?? 0)) : 0);
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
  if (key === "cmd") {
    const rows = state.heads.map((h) => ({ h, n: c?.of(h.key) ?? null }));
    // a command with nothing in this window goes last and quiet: he opened the menu to pick one that ran
    const sorted = c ? rows.filter((x) => x.n > 0).concat(rows.filter((x) => !(x.n > 0))) : rows;
    const most = Math.max(0, ...rows.map((x) => x.n ?? 0));
    return `<div class="menu fmenu" role="menu">${every("Every command", !ui.cmd)}`
      + sorted.map(({ h, n }) => item(h.command, esc(h.command) + (h.retired ? REMOVED_TAG : ""), ui.cmd === h.command,
        { extra: `<i class="blot" style="--c:${colorOf(state.providerOf[h.key])}"></i>`, n, most, cls: c && !(n > 0) ? "idle" : "" })).join("") + `</div>`;
  }
  // the requests splice couldn't put a model or an account on (unattributed=model|account, TurnsFilter.kt)
  if (key === "model") {
    const most = mostOf(state.facets.models.map(([id]) => id));
    return `<div class="menu fmenu" role="menu">${every("Every model", !ui.model)}`
      + state.facets.models.map(([id, label]) => item(id, esc(label), ui.model === id, { n: c?.of(id) ?? null, most })).join("")
      + `${item("none", "No model", ui.model === "none")}</div>`;
  }
  if (key === "account") { // an account on two commands is two accounts: a key's own variable and day, a plan's label
    const names = acctNames(), most = mostOf(state.facets.accounts.map(([k]) => k));
    // THE COMMAND IS SAID ONCE, over the accounts that ran on it, with its own dot, instead of once on every row: two
    // commands sharing a login read as two groups, and one command draws no heading at all
    const groups = new Map();
    for (const [k, { head }] of state.facets.accounts) (groups.get(head.key) ?? groups.set(head.key, { head, keys: [] }).get(head.key)).keys.push(k);
    const one = groups.size < 2;
    const group = ([, g]) => (one ? "" : `<p class="mgroup"><i class="blot" style="--c:${colorOf(state.providerOf[g.head.key])}"></i>${esc(g.head.command)}</p>`)
      + g.keys.map((k) => item(k, `<span class="aname">${names.get(k)}</span>`, ui.account === k, { n: c?.of(k) ?? null, most, cls: one ? "" : "ingroup" })).join("");
    return `<div class="menu fmenu" role="menu">${every("Every account", !ui.account)}${[...groups].map(group).join("")}`
      + `${item("none", "No account", ui.account === "none")}</div>`;
  }
  if (key === "sid") { // the sessions with requests in the window, the latest first, each with its command's colour
    // A day holds more sessions than a menu can be read through — 1,559 on one live day — so this lists the ones he
    // worked in most recently and says how many it left out, rather than a scroll nobody can find a session in.
    const all = state.facets.sessions.slice().sort((a, b) => (b[1].last ?? 0) - (a[1].last ?? 0));
    const rows = all.slice(0, SESSIONS_IN_MENU);
    const rest = all.length - rows.length;
    const most = mostOf(rows.map(([sid]) => sid));
    return `<div class="menu fmenu sessions-menu" role="menu">${every("Every session", !ui.sid)}`
      + rows.map(([sid, { head, last }]) => item(sid, `<span class="sname">${esc(sessTitle(sid))}</span><span class="swhen">${last ? `${dayOf(new Date(last))}${clock(new Date(last))}` : ""}</span>`, ui.sid === sid,
        { extra: `<span class="blots"><i class="blot" style="--c:${colorOf(state.providerOf[head.key])}"></i></span>`, n: c?.of(sid) ?? null, most })).join("")
      + (rest ? `<p class="mfoot">${n0(rows.length)} of ${n0(all.length)}, most recently active</p>` : "") + `</div>`;
  }
  return "";
}
// The order is not a filter: it changes the list, so it stands at the END OF THE LIST'S OWN HEAD LINE and nowhere else
// (one control, one place: drawn in the filters too, it read as a filter and answered twice). In the switch Outcome
// uses (hitstop 1912470, fin's words). It shows the order the daemon applied: a daemon that kept the newest shows
// Newest, never Highest price over them.
const orderHtml = () => `<div class="switch order" role="group" aria-label="Order">${[["new", "Newest"], ["price", "Highest price"]]
  .map(([v, w]) => `<button data-act="order" data-v="${v}" aria-pressed="${(state.order ?? ui.order) === v}">${w}</button>`).join("")}</div>`;
// EVERY CONTROL SAYS WHAT PRESSING IT WOULD LIST (the redesign, Oct 26). The outcome switch says it with the band right
// under it, whose hero and bar count each outcome, so the switch is its words alone; each toggle carries what the daemon
// counted with it pressed, so no press lands on an empty list unawares. Counted only where the daemon counted the whole
// window: a search or a repo lists rows the daemon cannot count.
function filtersHtml() {
  const out = ui.outcome, b = bandOf(), wb = b && b.listed == null ? b : null;
  const n = (v) => (wb && v != null ? `<b class="n">${n0(v)}</b>` : "");
  const sw = `<div class="switch" role="group" aria-label="Outcome">${[["all", "All"], ["clean", "Answered"], ["fail", "Failed"], ["stopped", "Stopped"]]
    .map(([v, w]) => `<button data-act="outcome" data-v="${v}" aria-pressed="${out === v}">${w}</button>`).join("")}</div>`;
  // Started over happens during a request that can still end clean, so it is not an outcome but a door of its own
  // beside the switch: the why filter, pressed when a door brings it, and it combines with Failed and Stopped. Its
  // toggle is its chip, so no second chip is drawn for it. A toggle the daemon counted none for in the window stays,
  // quiet and with no figure, so a zero is never printed and the control is still there to press.
  const quiet = (c, on) => (wb && c === 0 && !on ? " none" : "");
  const over = `<button class="ftog${quiet(wb?.over, ui.why === "startedover")}" data-act="why" data-v="startedover" aria-pressed="${ui.why === "startedover"}"><i class="tick"></i>${WHY.startedover.word}${wb?.over ? n(wb.over) : ""}</button>`;
  const tog = (key, label, on) => `<button class="ftog${quiet(wb?.compact, on)}" data-act="toggle" data-m="${key}" aria-pressed="${on}">${GLYPH.compact}${label}${wb?.compact ? n(wb.compact) : ""}</button>`; // each toggle draws its rows' mark
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
    + `${sw}${over}${tog("compact", "Compactions", ui.compact)}</div>`;
}

// ---------- the list: the newest first, on the rail, an hour mark where the hour turns ----------
// EVERY ROW ON THE SAME COLUMNS (the redesign, Oct 26): a request's tokens, time and cost are drawn on the column's
// shared scale, so the biggest, the slowest and the dearest are found before a digit is read, and what a column counts
// is said once, in its header, never on every row ("tokens in", "out", "First byte", "On your plan" did). The time is
// on the band's own log axis, hatched to the first byte, with a tick where its own model's slowest 5% begins.
const pctOf = (v, max) => (max > 0 && v > 0 ? Math.max(1.5, Math.min(100, (v / max) * 100)) : 0);
/** The shared scales of the rows listed: the most tokens in, the highest price, and the time axis every bar and the
 *  band's times share, from the quickest first byte to the longest request. */
function scalesOf(rows) {
  let tok = 0, cost = 0, cheap = Infinity, lo = Infinity, hi = 0;
  for (const r of rows) {
    if (r.in > tok) tok = r.in;
    const p = priceOf(r);
    if (p > cost) cost = p;
    if (p > 0 && p < cheap) cheap = p;
    for (const x of [r.ttfb, r.total, r.queued]) if (x > 0) { if (x < lo) lo = x; if (x > hi) hi = x; }
  }
  return { tok, cost, cax: cost > 0 ? costAxis(cheap, cost) : null, ax: hi > 0 ? DRAW.axis([lo], [hi]) : null };
}
// THE COST COLUMN IS A LOG AXIS OF DOLLARS, from the power of ten under the cheapest request listed to the one over the
// dearest, as the duration column is a log axis of time: one list holds a tenth of a cent beside a dollar, and on a
// straight scale the dearest request left every other bar a sliver. A tenfold step is the same length anywhere on it,
// and the column header marks each power of ten.
function costAxis(cheap, dear) {
  const a = Math.floor(Math.log10(cheap)), b = Math.max(a + 1, Math.ceil(Math.log10(dear)));
  const ticks = Array.from({ length: b - a + 1 }, (_, i) => +(10 ** (a + i)).toPrecision(1));
  return { ticks, at: (v) => (v > 0 ? Math.max(1.5, Math.min(100, ((Math.log10(v) - a) / (b - a)) * 100)) : 0) };
}
const dollarTick = (v) => `$${v >= 1 ? v.toLocaleString("en-US") : v}`;
function rowHtml(r, S, burst = null) {
  const { o, lamp, lampCls, word } = stateOf(r, true, Boolean(burst?.reset)), h = r.said, open = ui.open === r.id;
  const door = (id, text) => `<button class="door" data-open="${esc(id)}">${text}</button>`, note = (text) => `<span class="note">${text}</span>`;
  // where a cut request's tokens went and what the carrier's count holds, each a door to the other where both are listed
  // (linkCuts); a finished request with no tokens and no carrier says it has no reading; and the request splice cut when
  // it restarted with the client's re-send of it (linkRetries)
  const doors = (r.cut > 0 && r.in != null ? (r.cutId ? door(r.cutId, "Counts 1 earlier request") : note(`Counts ${r.cut} earlier request${r.cut === 1 ? "" : "s"}`)) : "")
    + (r.outcome === "ok" && r.in == null && r.out == null ? (r.countedOn ? door(r.countedOn, "Counted on the next request") : note(r.counted ? "Counted on the next request" : "No usage reported")) : "")
    + (r.retryId ? door(r.retryId, "Sent again after the restart") : r.retryOf ? door(r.retryOf, "Resent after splice restarted") : "");
  // tokens in on the column's scale with the cached part shaded inside it, then the two figures; one nobody reported is a dash
  const tk = `<span class="tk"><span class="tbar">${r.in != null ? `<b style="width:${pctOf(r.in, S.tok).toFixed(1)}%"><i style="width:${r.in ? Math.min(100, (r.cached / r.in) * 100).toFixed(1) : 0}%"></i></b>` : ""}</span>`
    + `<b class="v">${r.in != null ? kTok(r.in) : DRAW.dash}</b><b class="v">${r.out != null ? kTok(r.out) : DRAW.dash}</b></span>`;
  // the bar's length is the whole time on the log axis; the hatch inside it is the share of that time spent waiting for
  // the first byte, so half a request spent waiting reads as half its bar
  const at = (ms) => (S.ax ? S.ax.at(Math.max(1, ms)) : 0), run = at(r.total), line = slowLine(r);
  const pre = r.ttfb != null && r.total > 0 && run > 0 ? Math.min(100, (r.ttfb / r.total) * 100) : 0;
  const du = `<span class="du"><span class="dtrk"><i class="run" style="width:${run.toFixed(2)}%">${pre ? `<i class="pre" style="width:${pre.toFixed(1)}%"></i>` : ""}</i>`
    + `${line ? `<i class="p95" style="left:${at(line.slow).toFixed(2)}%"></i>` : ""}</span><b class="v">${secs(r.total)}</b></span>`;
  // a key's dollars solid; a plan request's worth at API prices hollow and muted, since the plan paid it; one nothing
  // answered cost $0. One with no price is the dash, the reason under the pointer and under its Cost once it is open
  // (TurnPriceGap). The column's header says the figures are estimates, so no row says it again.
  const price = priceOf(r), plan = r.cost == null && r.planValue != null;
  const co = price != null
    ? `<span class="co${plan ? " plan" : ""}"><span class="cbar"><b style="width:${(S.cax ? S.cax.at(price) : 0).toFixed(1)}%"></b></span><b class="v">${moneyFig(price)}</b></span>`
    : `<span class="co gap"><span class="cbar"></span><b class="v">${r.reason === "unanswered" ? moneyFig(0) : `<i class="nr" title="${esc(REASON[r.reason] ?? "Not reported")}">—</i>`}</b></span>`;
  const match = h ? `<p class="match"><span class="where">${h.where === "sent" ? "Sent" : "Returned"}</span>${markIn(`… ${h.text} …`)}</p>` : "";
  const none = (what) => `<i class="nr" title="No ${what}">—</i>`;
  // the chevron first, as every disclosure row on the console has it: it turns as the request opens under the row
  return `<article class="card rq ${o.cls}${r.compact ? " compact" : ""}${burst ? " inburst" : ""}${burst && unfolding === burst.id ? " unfold" : ""}" style="--c:${colorOf(r.provider)}" data-open="${esc(r.id)}" aria-current="${open}">`
    + `${DRAW.chev}<span class="lamp ${lampCls}" aria-hidden="true">${lamp}</span>`
    + `<div class="top"><time>${state.order === "price" ? dayOf(r.ts) : ""}${clockS(r.ts)}</time><div class="who"><button class="name" data-open="${esc(r.id)}" aria-expanded="${open}">${esc(r.compare ? COMPARE_LABEL : sessTitle(r.sid))}</button>${word}${doors}</div></div>`
    + `<div class="meta">${headChip(r)}<span class="mdl"${r.model ? ` title="${esc(r.model)}"` : ""}>${r.model ? esc(r.model) : none("model")}</span><span class="acc">${r.account ? acctTag(r) : none("account")}</span></div>`
    + `${tk}${du}${co}${match}</article>`;
}
/** The columns' names, said once over the rows on the rows' own grid, each over what it names: a figure's name over
 *  its figures, and over the duration bars the time axis they sit on. Muted and in sentence case, as Usage's results
 *  table names its columns. */
const headHtml = (S) => `<div class="rqhead"><span class="ch"></span><span class="lp"></span><div class="top"><span class="h">Time</span><div class="who"><span class="h">Session</span></div></div>`
  + `<div class="meta"><span class="h">Command</span><span class="h mdl">Model</span><span class="h acc">Account</span></div>`
  + `<span class="tk"><span class="h">Tokens</span><span class="h v">In</span><span class="h v">Out</span></span>`
  + `<span class="du"><span class="dax">${headTicks(S.ax).map(([v, cls]) => `<i class="${cls}" style="left:${S.ax.at(v).toFixed(2)}%">${DRAW.tickWord(v)}</i>`).join("")}</span><span class="h v">Duration</span></span>`
  + `<span class="co"><span class="dax">${S.cax ? headTicks(S.cax).map(([v, cls]) => `<i class="${cls}" style="left:${S.cax.at(v).toFixed(2)}%">${dollarTick(v)}</i>`).join("") : ""}</span><span class="h v">Est. cost</span></span></div>`;
/** The axis's ticks as a column's header can hold them: the two ends, and between them only ticks 30% of the axis
 *  from their neighbours, so no two labels touch even where the column is 13rem wide (1440). */
function headTicks(ax) {
  if (!ax) return [];
  const kept = [];
  for (const v of ax.ticks) if (!kept.length || ax.at(v) - ax.at(kept.at(-1)) >= 30) kept.push(v);
  const last = ax.ticks.at(-1);
  if (kept.at(-1) !== last) { if (kept.length > 1 && ax.at(last) - ax.at(kept.at(-1)) < 30) kept.pop(); kept.push(last); }
  return kept.map((v, i) => [v, i === 0 ? "first" : i === kept.length - 1 ? "tail" : "mid"]);
}
/** What the bars' parts mean, said once at the list's head beside the count of what it holds. */
const KEY = `<span class="lkey"><span class="lg"><i class="sw cached"></i>Cached tokens</span><span class="lg"><i class="sw pre"></i>Waiting for the first byte</span>`
  + `<span class="lg"><i class="sw p95"></i>Slowest 5% of its model</span><span class="lg"><i class="sw plan"></i>On your plan, at API prices</span>`
  + `<span class="lg">${ICON.retries}Retries</span></span>`;
const markIn = (t) => RV.markIn(t);
// A WINDOW CAN HOLD MORE REQUESTS THAN ONE READ CARRIES. The daemon answers with the newest 2,000 of each command and
// says how many matched (PerfRoutes.kt, MAX_TURNS); on a live day one command's 24 hours held 19,842. Without this the
// list reads "200 of 2,000" on a window of twenty thousand, and turning Steps off changes nothing a reader can see
// while the daemon's own count moves by nine thousand. It heads the list, where the rows it describes begin.
// The meter is the share of the window's requests the list holds; the window itself is the first filter's chip.
function heldFoot() {
  if (!state.capped) return "";
  const n = state.matched;
  const which = state.order === "price" ? "highest priced" : "newest"; // the read kept the dearest, not the latest (fin)
  return `<p class="foot held">${DRAW.vbar(n ? (Math.min(n, state.rows.length) / n) * 100 : 0)}<span><b>${n0(state.held ?? MAX_HELD)}</b> ${which} of each command, of <b>${n0(n)}</b> request${n === 1 ? "" : "s"}</span></p>`;
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
  return `<div class="foot searched"><span>Searched back to ${dayOf(new Date(s.backTo))}${clock(new Date(s.backTo))}</span></div>`;
}
// The list's order: newest first, or in Highest price the most at API prices first, a key's cost or a plan request's worth
// on the same card (fin; Marlin took price over tokens, which rank cached context), and a request with no price last,
// newest first among them. The daemon chose each command's rows by the same figure (PerfRoutes order=price).
const priceOf = (r) => r.cost ?? r.planValue ?? null;
const ordered = (rows) => rows.slice().sort(state.order === "price"
  ? (a, b) => (priceOf(b) ?? -1) - (priceOf(a) ?? -1) || +b.ts - +a.ts : (a, b) => +b.ts - +a.ts);
// WHILE THE FIRST READ IS OUT the list draws its own shape, hollow (Marcos, Oct 12: the dashed "Reading" box "looks
// awful", and it was the same box No requests uses, so loading read as an empty day): its head line, the table with its
// columns' header, an hour's sub-head, and rows at the rows' own height on the rows' own grid, each cell the striped bar
// a meter draws while it reads, fading down the page, so the rows land where the eye already is and nothing moves when
// they do. The filters' row holds ghost chips the same way.
const GHOST_SLIPS = [[11, 9, 8], [9, 11, 10], [13, 8, 7], [8, 10, 9], [10, 9, 11], [12, 11, 8]];
const ghostRowHtml = (i) => {
  const [name, model, acct] = GHOST_SLIPS[i % GHOST_SLIPS.length];
  return `<div class="rqg ghosted" style="--i:${(i / 5).toFixed(2)}" aria-hidden="true"><span class="ch"></span><span class="lamp"></span>`
    + `<div class="top">${GHOST.bar(5.6)}<div class="who">${GHOST.bar(name, "nm")}</div></div>`
    + `<div class="meta">${GHOST.chip(7.5)}${GHOST.bar(model)}${GHOST.bar(acct)}</div>`
    + `<span class="tk">${GHOST.track()}</span><span class="du">${GHOST.track()}</span><span class="co">${GHOST.track()}</span></div>`;
};
const ghostListHtml = () => `<div class="list ghosts" aria-busy="true" aria-label="Reading requests"><div class="lline">${KEY}${orderHtml()}</div>`
  + `<div class="rqtable card">${headHtml({ ax: null })}<div class="hour"><span class="hw">${GHOST.bar(4.4)}</span></div>`
  + Array.from({ length: 24 }, (_, i) => ghostRowHtml(i)).join("") + "</div></div>";
// the filters' row hollow: the five pickers, the outcome switch and the two toggles, each the size it lands at
const ghostFiltersHtml = () => `<div class="filters" aria-hidden="true">${[7, 7.9, 6.6, 6, 7].map((w) => GHOST.chip(w)).join("")}`
  + `${GHOST.bar(19.2, "chip gsw")}${GHOST.bar(10.1, "chip gtg")}${GHOST.bar(11.8, "chip gtg")}</div>`;
// while the projects are read the pick line holds its own shape, hollow: the tiles where the page is wide, on the
// tiles' own equal columns, else the picker
const ghostPickHtml = () => GHOST.tiles(10);
// THE HOUR MARK says its own day on any day but today, so a mark read mid-scroll still names it, and today's first
// names Today (Marlin, desk c, Oct 12: the 24-hour view's first screen had no date). It carries the hour's count and a
// bar as long as the hour was busy, failures in the limit's red, so the day's shape reads down the rail. An hour older
// than a capped command's reach holds more than the list carries, and its count says so with a plus.
const HOUR = 3600e3;
const hourWord = (d, first) => `${d.toDateString() !== NOW.toDateString() ? `${dayWordShort(d)} \u00b7 ` : first ? "Today \u00b7 " : ""}${d.getHours() % 12 || 12} ${d.getHours() < 12 ? "AM" : "PM"}`;
const hourOf = (d) => +new Date(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours());
function hourCounts(rows) {
  const hours = new Map();
  for (const r of rows) {
    const k = hourOf(r.ts), c = hours.get(k) ?? hours.set(k, { n: 0, ok: 0, nt: 0, f: 0 }).get(k), cls = outcomeOf(r).cls;
    c.n++;
    c[cls === "fail" ? "f" : cls === "stopped" ? "nt" : "ok"]++;
  }
  return hours;
}
const hourBar = (c, most) => `<span class="hbar" style="width:${Math.max(0.3, (c.n / most) * 6).toFixed(2)}rem" aria-hidden="true"><i class="ok" style="flex:${c.ok}"></i>${c.nt ? `<i class="nt" style="flex:${c.nt}"></i>` : ""}${c.f ? `<i class="f" style="flex:${c.f}"></i>` : ""}</span>`;
// A RUN OF FAILURES WITH ONE CAUSE IS ONE ROW: a restart or a spent limit fails every request in flight at once, and
// forty identical rows pushed the rest of the hour off the screen. Three or more failures in a row with the same word,
// each within 10 s of the one after it and inside one hour, fold into a burst row with how many, over how long, and
// what they hit; its chevron opens it to its rows. Newest order only, where a run is a run in time.
const BURST_MIN = 3, BURST_GAP = 10e3;
function burstsOf(rows) {
  const out = new Map();
  let run = [];
  const flush = () => {
    // keyed by each row it holds, so a row knows its burst, AND by the burst's own id, so a press on the burst finds it
    // a reset every row of the run shares (one spent window) is said once, on the burst row
    const resets = uniq(run.map((r) => (r.reset ? resetWord(r) : "")));
    if (run.length >= BURST_MIN) { const b = { id: `burst:${run[0].id}`, rows: run, reset: resets.length === 1 && resets[0] ? resets[0] : null }; for (const r of run) out.set(r.id, b); out.set(b.id, b); }
    run = [];
  };
  for (const r of rows) {
    const o = outcomeOf(r), prev = run.at(-1);
    if (o.cls === "fail" && prev && outcomeOf(prev).word === o.word && +prev.ts - +r.ts <= BURST_GAP && hourOf(prev.ts) === hourOf(r.ts)) { run.push(r); continue; }
    flush();
    if (o.cls === "fail") run = [r];
  }
  flush();
  return out;
}
let lastBursts = new Map(), unfolding = null; // the bursts the list drew, and the one whose rows open on this draw
// a new order or outcome is the list's answer to a press: the rows it lands with rise in once (requests.css .arrive)
let arriving = false;
function burstHtml(b, open) {
  const newest = b.rows[0], span = +newest.ts - +b.rows.at(-1).ts, { lamp, lampCls, o } = stateOf(newest, true);
  const cmds = uniq(b.rows.map((r) => r.cmd)), sids = uniq(b.rows.map((r) => r.sid).filter(Boolean));
  const chip = (cmd) => `<span class="chip" style="--c:${colorOf(providerOfCmd(cmd))}">${esc(cmd)}</span>`;
  const names = sids.slice(0, 3).map((sid) => `<span class="bsn">${esc(sessTitle(sid))}</span>`).join("") + (sids.length > 3 ? `<span class="bsn more">+${n0(sids.length - 3)}</span>` : "");
  return `<button class="rqburst ${o.cls}" type="button" data-act="burst" data-v="${esc(b.id)}" aria-expanded="${open}">${DRAW.chev}`
    + `<span class="lamp ${lampCls}" aria-hidden="true">${lamp}</span><time>${state.order === "price" ? dayOf(newest.ts) : ""}${clockS(newest.ts)}</time>`
    + `<span class="who"><b class="bn">${n0(b.rows.length)} requests</b><span class="word fail">${esc(o.word)}</span><span class="note">${span >= 1000 ? `in ${secs(span)}` : "within a second"}</span></span>`
    + `<span class="bcmd">${cmds.map(chip).join("")}</span><span class="bsess">${b.reset ? `<span class="detail">${ICON.lock}${esc(b.reset)}</span>` : ""}${names}</span></button>`;
}
/** The list: its head line, then one table with the open request ([pane]) in place right under its row. */
function listHtml(pane = "") {
  if (state.loading || state.reading) return ghostListHtml();
  const rows = ordered(filtered());
  // a command splice is still reading is not trouble: a quiet line with its chip, gone once its rows join (asked again shortly)
  const wait = (state.pending || []).map((cmd) => `<p class="list-wait"><span class="chip" style="--c:${colorOf(providerOfCmd(cmd))}">${esc(cmd)}</span>${GHOST.state()}</p>`).join("");
  // a refused read: the command's own chip, as its rows and the band's Outcomes name it, and the one word in red
  const said = (list) => list.map((e) => `<p class="list-wait unread">${e.names ? `<span>${esc(e.cmd)}</span>` : `<span class="chip" style="--c:${colorOf(providerOfCmd(e.cmd))}">${esc(e.cmd)}</span>`}`
    + `<span class="state limit">${e.word}</span></p>`).join("");
  const names = state.sessionsError ? [{ cmd: "Session names", word: state.sessionsError, names: true }] : [];
  const trouble = wait + said(state.headErrors.concat(names));
  // a command whose search failed sits under the rows the others found (fin)
  const unsearched = said(state.search?.errors || []);
  const S = scalesOf(rows), priced = state.order === "price", arrive = arriving;
  arriving = false;
  // nothing to list keeps the table and its header, and says so once where the rows go, with the act that finds some
  const table = (body, tail = "") => `<div class="list${priced ? " priced" : ""}"><div class="lline">${heldFoot()}${KEY}${orderHtml()}</div>${trouble}`
    + `<div class="rqtable card${arrive ? " arrive" : ""}">${headHtml(S)}${body}</div>${tail}</div>`;
  const empty = (word, acts = "") => `<div class="nomatch"><span class="state">${word}</span>${acts}</div>`;
  lastBursts = new Map();
  if (state.error) return table(`<div class="nomatch"><span class="state limit">${esc(state.error)}</span></div>`);
  if (!rows.length && ui.q) return table(empty("No match", `<button class="act quiet" data-act="clearq">Clear</button>`) + searchFoot(), unsearched);
  if (!rows.length) {
    const w = state.wider, widen = w ? `<button class="act" data-act="widen" data-v="${w.win}">${n0(w.n)}${w.floor ? "+" : ""} in the last ${WINDOWS[w.win][0]}</button>` : "";
    return table(empty(anyFilter() ? "No match" : "No requests", widen + (anyFilter() ? `<button class="act quiet" data-act="clearall">Clear</button>` : "")));
  }
  const shown = rows.slice(0, ui.n), items = [];
  // in Highest price the rows are not in time order, so no hour mark: each row says its own day (hitstop 1912470)
  const hours = priced ? null : hourCounts(rows), most = hours ? Math.max(...[...hours.values()].map((c) => c.n)) : 0;
  const oldest = shown.at(-1).ts.toDateString();
  // a search's rows each carry the line it matched, so they are never folded
  lastBursts = priced || ui.q ? new Map() : burstsOf(shown);
  let hour = null, day = null;
  for (const r of shown) {
    const hk = hourOf(r.ts), dk = r.ts.toDateString();
    if (hours && hk !== hour) {
      // a stretch with nothing in it stands on the rail as the gap it was, between the two hours around it
      const gap = hour == null ? 0 : Math.round((hour - hk) / HOUR) - 1;
      if (gap > 0) items.push(`<div class="hgap"><span>No requests for ${gap} hour${gap === 1 ? "" : "s"}</span></div>`);
      const c = hours.get(hk), floor = state.capped && hk < state.reach;
      // the oldest day listed takes him to the day before in one press (Marlin, p225: scrolling from Saturday never reached Friday)
      const prev = new Date(r.ts.getFullYear(), r.ts.getMonth(), r.ts.getDate() - 1), back = dayEnd(prev);
      const earlier = dk !== day && dk === oldest && back > +NOW - WINDOWS["7d"][1]
        ? `<button class="act quiet small" data-act="upto" data-v="${back}">Show ${dayWordShort(prev)}</button>` : "";
      items.push(`<div class="hour"><span class="hw">${hourWord(r.ts, hour == null)}</span><span class="hc"><b>${n0(c.n)}${floor ? "+" : ""}</b>${hourBar(c, most)}</span>${earlier}</div>`);
      hour = hk; day = dk;
    }
    const b = lastBursts.get(r.id);
    if (b) {
      const open = ui.bursts.has(b.id) || b.rows.some((x) => x.id === ui.open);
      if (b.rows[0] === r) items.push(burstHtml(b, open));
      if (!open) continue;
    }
    items.push(rowHtml(r, S, b));
    if (pane && r.id === ui.open) items.push(pane);
  }
  const more = rows.length > shown.length ? `<div class="more"><button class="act quiet" data-act="more">Show ${n0(Math.min(200, rows.length - shown.length))} more</button>`
    + `<span class="count">${DRAW.vbar((shown.length / rows.length) * 100)}${n0(shown.length)} of ${n0(rows.length)}</span></div>` : "";
  // an open request whose row this list does not hold (a filter hid it) stands at the table's head
  const lone = pane && !shown.some((r) => r.id === ui.open) ? pane : "";
  return table(`${lone}${items.join("")}${searchFoot()}`, `${more}${unsearched}`);
}
const anyFilter = () => Boolean(ui.cmd || ui.sid || ui.model || ui.account || ui.repo || ui.from || ui.tag || ui.why || ui.compact || ui.outcome !== "all" || ui.q);

// ---------- the band: did it go wrong, was it slow, what did it use (the redesign, Oct 26) ----------
/** The band's figures for the window on screen: the daemon's count of the whole window, or, where a filter the daemon
 *  cannot count is set (a search, a repo) or the daemon answers no asks, the listed rows counted here, and then the
 *  band says it counted those. Null while no answer for this window has come. */
function bandOf() {
  const b = state.band;
  if (!b || b.scope !== bandScope()) return null;
  if (!b.old && !ui.q && !ui.repo) return b;
  const rows = filtered(), out = { listed: rows.length, m: rows.length, failed: 0, stopped: 0, other: 0, kinds: new Map(), totals: {}, sessions: new Map(), missing: [] }, t = out.totals;
  const add = (k, v, into = t) => { into[k] = (into[k] ?? 0) + v; };
  for (const r of rows) {
    const cls = outcomeOf(r).cls;
    if (cls === "stopped") out.stopped++;
    if (cls === "fail") { out.failed++; addKind(out.kinds, kindOf(r)); }
    if (r.in != null) { add("input_tokens", r.in); add("cached_tokens", r.cached); }
    if (r.out != null) add("output_tokens", r.out);
    if (r.write > 0) add("cache_write_tokens", r.write);
    const s = r.sid ? out.sessions.get(r.sid) ?? out.sessions.set(r.sid, {}).get(r.sid) : null;
    if (r.cost != null) { add("cost_usd", r.cost); if (s) add("cost_usd", r.cost, s); }
    else if (r.planValue != null) { add("plan_value_usd", r.planValue); add("plan_requests", 1); if (s) add("plan_value_usd", r.planValue, s); }
  }
  return out;
}
// a failure rate as perf-heads writes it: one decimal under 10%, so 2.3% never rounds to a 2% that hides a jump
const rateWord = (x) => (x === 0 ? "0%" : x < 0.001 ? "<0.1%" : x < 0.1 ? `${(x * 100).toFixed(1)}%` : DRAW.pct(x));
/** The address of this list with [over] changed. The band's doors are links, so a new tab opens the same list, and on
 *  the page they are read back by the reader the page started from (applyAddress). */
function addressOf(over = {}) {
  const u = { ...ui, ...over }, a = new URLSearchParams();
  if (u.win !== "24h") a.set("win", u.win);
  for (const [k, v] of [["cmd", u.cmd], ["model", u.model], ["account", u.account], ["session", u.sid], ["project", u.project], ["repo", u.repo],
    ["from", u.from], ["to", u.to], ["why", u.why], ["outcome", u.outcome === "all" ? null : u.outcome], ["tag", u.tag], ["q", u.q]]) if (v != null && v !== "") a.set(k, String(v));
  if (u.compact) a.set("compact", "1");
  if (u.order === "price") a.set("order", "price");
  return `requests.html?${a}`;
}
let bandGrown = false; // the outcome bar grows in once, when the band first lands, and is simply drawn after that
const KINDS_SHOWN = 5;
/** How it went: the failure rate over its count, one bar of answered, failed and stopped with the part he pressed
 *  outlined, and under it the kinds of failure as bars on one scale, the commonest first, each a door to its rows. */
function outcomesHtml(b) {
  const miss = (b.missing || []).map((x) => `<span class="miss"><span class="chip" style="--c:${colorOf(state.providerOf[x.head])}">${esc(x.cmd)}</span>${x.pending ? GHOST.state() : `<span class="state limit">${LIST_NOT_READ}</span>`}</span>`).join("");
  const listed = b.listed != null, aside = (listed ? `<span class="pd-count">of the <b>${n0(b.listed)}</b> listed</span>` : "") + miss;
  const rate = b.m ? b.failed / b.m : 0, keep = OUTCOME_WHYS.has(ui.why) ? null : ui.why;
  const hero = DRAW.hero({ figure: rateWord(rate), level: rate >= 0.05 ? "bad" : rate >= 0.01 ? "watch" : "ok", count: b.failed, word: "failed", of: `of ${n0(b.m)} requests`,
    href: b.failed && !listed ? addressOf({ outcome: "fail", tag: null, why: keep }) : null });
  // a door lists exactly what its block counted: a why the row tells apart can't stand beside another why he pressed
  const doorOf = (k) => (listed || k.other ? null : k.why ? (keep ? null : addressOf({ why: k.why, tag: null, outcome: "all" })) : addressOf({ tag: [...k.tags].join(","), why: keep, outcome: "all" }));
  const on = (k) => !k.other && (k.why ? ui.why === k.why : ui.tag != null && ui.tag === [...k.tags].join(","));
  const kinds = [...b.kinds.values()].concat(b.other ? [{ word: "Other failures", n: b.other, other: true }] : []).sort((x, y) => y.n - x.n);
  const kindOn = kinds.some(on), door = (o) => (listed ? null : addressOf({ outcome: o, tag: null, why: keep }));
  const segs = [{ n: b.m - b.failed - b.stopped, word: "Answered", cls: `ok${ui.outcome === "clean" ? " on" : ""}`, href: door("clean") },
    { n: b.failed, word: "Failed", cls: `f0${ui.outcome === "fail" && !kindOn ? " on" : ""}`, href: door("fail") },
    { n: b.stopped, word: "Stopped", cls: `nt${ui.outcome === "stopped" ? " on" : ""}`, href: door("stopped") }];
  const grow = !bandGrown && !matchMedia("(prefers-reduced-motion: reduce)").matches;
  bandGrown = true;
  const most = kinds[0]?.n ?? 0, shown = ui.kindsOpen ? kinds : kinds.slice(0, KINDS_SHOWN);
  const kindRow = (k) => {
    const href = doorOf(k), inner = `<span class="kn">${esc(k.word)}</span>${DRAW.barCell({ w: pctOf(k.n, most), text: n0(k.n), level: "bad" })}`;
    return href ? `<a class="kr${on(k) ? " on" : ""}" href="${esc(href)}">${inner}</a>` : `<span class="kr">${inner}</span>`;
  };
  const more = kinds.length > KINDS_SHOWN ? DRAW.more(kinds.length - KINDS_SHOWN, { word: "more kinds", act: "kinds", open: ui.kindsOpen }) : "";
  const list = kinds.length ? `<div class="kinds" role="list" aria-label="Failures by kind">${shown.map(kindRow).join("")}${more}</div>` : "";
  return DRAW.section("Outcomes", hero + DRAW.stack(segs, b.m, { grow, label: `${n0(b.failed)} failed, ${n0(b.stopped)} stopped of ${n0(b.m)}` }) + list, { aside, cls: "rqout" });
}
/** The typical time and the slowest 5% of the answered requests listed, on the axis the list's duration bars share. */
const TIMES_HEAD = { cls: "pd-stages", head: "pd-grid", aside: `${DRAW.rangeLegend}<span class="pd-n">Typical</span><span class="pd-n">Slowest 5%</span>` };
// one stage on the kit's range grid: its name, its track, its typical time and its slowest 5%. The whole request is
// the section's answer, so its typical time is the figure the eye lands on (kit .pd-focus).
const stageHtml = (word, trk, typ, slow, focus = false) => `<div class="pd-st pd-grid${focus ? " pd-focus" : ""}"><span class="pd-sl">${word}</span>${trk}<span class="pd-n t">${typ}</span><span class="pd-n hi5">${slow}</span></div>`;
const noAxisRow = (note) => `<div class="pd-axis pd-grid"><span class="pd-sl">${note}</span><span class="pd-ax"></span><span></span><span></span></div>`;
function timesHtml(rows, ax) {
  const ok = rows.filter(answered);
  // nothing answered listed (Failed, say): the section keeps its shape, both stages on empty tracks with dashes for
  // their figures, over the same axis, whose caption counts the answers it measured: none
  if (!ok.length || !ax) {
    return DRAW.section("Times", ["First byte", "Whole request"].map((word, i) => stageHtml(word, `<span class="pd-trk"></span>`, DRAW.dash, DRAW.dash, i === 1)).join("")
      + (ax ? DRAW.axisRow(ax, "0 answered", "<span></span><span></span>") : noAxisRow("<small>0 answered</small>")), TIMES_HEAD);
  }
  const at = (xs, q) => xs[Math.floor(q * (xs.length - 1))];
  const lines = [["First byte", ok.map((r) => r.ttfb)], ["Whole request", ok.map((r) => r.total)], ["Waited in line", ok.map((r) => r.queued)]].map(([word, xs]) => [word, xs.filter((x) => x != null).sort((a, b) => a - b)]);
  const body = lines.filter(([word, xs]) => xs.length && (word !== "Waited in line" || xs.at(-1) > 0)).map(([word, xs]) => {
    // only the requests the gate held carry a wait, so its stage says how many of the answered ones that was
    const typ = at(xs, 0.5), slow = at(xs, 0.95), sub = word === "Waited in line" ? `<small>${n0(xs.length)} of ${n0(ok.length)}</small>` : "";
    return stageHtml(word + sub, DRAW.range(ax, typ, slow), DRAW.ms(typ), DRAW.ms(slow), word === "Whole request");
  }).join("") + DRAW.axisRow(ax, `${n0(ok.length)} answered, log scale`, "<span></span><span></span>");
  return DRAW.section("Times", body, TIMES_HEAD);
}
/** What the band's Tokens and Cost count, said on their kicker line when he pressed an outcome, a kind or a why. */
function scopeWord() {
  if (ui.tag) return OUTCOME_WORD[ui.tag.split(",")[0]] || tagWord(ui.tag.split(",")[0]) || "failed";
  if (OUTCOME_WHYS.has(ui.why)) return WHY[ui.why].word;
  return { fail: "failed", stopped: "stopped", clean: "answered" }[ui.outcome] ?? "";
}
const scopeCount = (sel) => (sel?.scope != null && sel.scope === selScope() ? `<span class="pd-count"><b>${n0(sel.m ?? 0)}</b> ${esc(scopeWord())}</span>` : "");
// the four parts of what the window's tokens were, in the chart colours, since they are parts of a whole and not outcomes
const TOKEN_INK = ["var(--splice-chart-1)", "var(--splice-chart-2)", "var(--splice-chart-3)", "var(--splice-chart-4)"];
/** What it used: the share of the tokens in that came from the cache as a ring, then one bar of every token the
 *  window moved, split into what came from the cache, what was written to it, what was new, and what came out, with
 *  the totals in and out on the kicker line. A window with none keeps the shape, hollow. */
function tokensHtml(t, sel) {
  const tin = t.input_tokens ?? 0, out = t.output_tokens ?? 0, cached = Math.min(tin, t.cached_tokens ?? 0), write = Math.min(tin - cached, t.cache_write_tokens ?? 0);
  const totals = tin || out ? `<span class="pd-count"><b>${DRAW.big(tin)}</b> in · <b>${DRAW.big(out)}</b> out</span>` : "";
  const aside = scopeCount(sel) + totals;
  if (!tin && !out) {
    return DRAW.section("Tokens", `<div class="tkband">${DRAW.statTile(DRAW.ring(0, { size: "focal" }), DRAW.dash, "Cache hit", { focal: true })}<div class="pd-pop sm"><div class="pd-bar none"></div></div></div>`, { aside, cls: "rqtok" });
  }
  const share = tin > 0 ? cached / tin : 0;
  const ring = DRAW.statTile(DRAW.ring(share, { size: "focal", label: `${DRAW.pct(share)} of tokens in came from the cache` }), tin ? DRAW.pct(share) : DRAW.dash, "Cache hit", { focal: true });
  const parts = [["Cached", cached], ["Cache write", write], ["New", tin - cached - write], ["Out", out]]
    .map(([word, n], i) => ({ n, word, text: DRAW.big(n), ink: TOKEN_INK[i] }));
  const bar = DRAW.stack(parts, tin + out, { small: true, label: `${DRAW.big(tin)} tokens in, ${DRAW.big(out)} out` });
  const cold = DRAW.tiles([t.cold_requests > 0 ? { icon: DRAW.glyph.cold, n: n0(t.cold_requests), label: "Cold after a switch", title: "Requests that ran without their cache after an account switch" } : null]);
  return DRAW.section("Tokens", `<div class="tkband">${ring}${bar}</div>${cold}`, { aside, cls: "rqtok" });
}
// a sum as the band says it: cents under $100, whole dollars from there, never the estimate's mark (its kicker says it)
const usdWord = (v) => (v >= 100 ? `$${Math.round(v).toLocaleString("en-US")}` : DRAW.usd(v));
const SESSIONS_BY_COST = 5, SESSIONS_OPEN = 25;
/** Where the money went: the sessions that cost the most, each on one scale with the list's own cost marks (a key's
 *  dollars solid, a plan's worth hollow), each a door to its requests, and what the rest came to behind a count that
 *  opens them in place. A filter to one session draws no split, since it would be the one line. */
function bySessionHtml(sessions) {
  if (ui.sid || !sessions?.size) return "";
  const all = [...sessions].map(([sid, x]) => ({ sid, paid: x.cost_usd ?? 0, plan: x.plan_value_usd ?? 0 }))
    .map((x) => ({ ...x, sum: x.paid + x.plan })).filter((x) => x.sum > 0).sort((x, y) => y.sum - x.sum);
  if (all.length < 2) return "";
  const top = all.slice(0, ui.byAll ? SESSIONS_OPEN : SESSIONS_BY_COST), most = top[0].sum, rest = all.slice(top.length);
  const restSum = rest.reduce((n, x) => n + x.sum, 0);
  const line = (x) => `<a class="byr" href="${esc(addressOf({ sid: x.sid }))}"><span class="bn">${esc(sessTitle(x.sid))}</span>`
    + `<span class="bb" aria-hidden="true"><b style="width:${pctOf(x.paid, most).toFixed(1)}%"></b><i style="width:${pctOf(x.plan, most).toFixed(1)}%"></i></span><span class="bv">${usdWord(x.sum)}</span></a>`;
  const opens = all.length > SESSIONS_BY_COST;
  const tail = rest.length || opens ? `<p class="byr rest"><span class="bn">${opens ? DRAW.more(rest.length, { word: "more", act: "bysess", open: ui.byAll }) : ""}</span><span class="bb"></span>`
    + `<span class="bv">${rest.length ? usdWord(restSum) : ""}</span></p>` : "";
  return `<div class="bys"><p class="byh">By session</p>${top.map(line).join("")}${tail}</div>`;
}
/** What it cost: the window's estimate as the figure, then one bar of the dollars paid on keys and the plan's worth
 *  at API prices (hollow, as the list draws a plan request), the share of requests with no price, and the sessions. */
function costHtml(t, sel) {
  const paid = t.cost_usd ?? 0, plan = t.plan_value_usd ?? 0, m = sel?.m ?? 0;
  const unpriced = (t.unpriced_uncounted_requests ?? 0) + (t.unpriced_local_requests ?? 0) + (t.unpriced_undeclared_requests ?? 0);
  // a window with no price at all is the dash alone: "estimated" qualifies a figure, and there is none to qualify
  const priced = paid + plan > 0 || t.cost_usd != null;
  const lead = DRAW.focal(priced ? usdWord(paid + plan) : "—", priced ? "estimated" : "");
  const bar = paid + plan > 0 ? DRAW.stack([{ n: paid, word: "Paid on keys", text: usdWord(paid), cls: "paid" }, { n: plan, word: "On your plan", text: usdWord(plan), cls: "onplan" }], paid + plan, { small: true })
    : `<div class="pd-pop sm"><div class="pd-bar none"></div></div>`;
  const gap = unpriced ? `<div class="kr"><span class="kn">With no price</span>${DRAW.barCell({ w: m ? pctOf(unpriced, m) : 0, text: n0(unpriced) })}</div>` : "";
  return DRAW.section("Cost", lead + bar + gap + bySessionHtml(sel?.sessions), { aside: scopeCount(sel), cls: "rqcost" });
}
// while a part of the band is read it draws its own shape, the size of what lands, on the landed parts' own grids, so
// nothing moves when it lands: the hero and its words, the bar, and the five kinds; both stages under their names; the
// ring beside the tokens' bar; the estimate, its bar, and the five dearest sessions with what the rest came to
const GHOST_TIMES = DRAW.section("Times", ["First byte", "Whole request"].map((word, i) => stageHtml(word, GHOST.range(), GHOST.fig(3), GHOST.fig(3), i === 1)).join("")
  + noAxisRow(GHOST.bar(8)), TIMES_HEAD);
const GHOST_KINDS = `<div class="kinds">${[11, 9, 12, 8, 10].map((w) => `<span class="kr"><span class="kn">${GHOST.bar(w)}</span>${GHOST.cell()}</span>`).join("")}</div>`;
const GHOST_TOKENS = DRAW.section("Tokens", `<div class="tkband"><span class="pd-stat focal">${GHOST.ring("focal")}<span>${GHOST.bar(3.4, "focalfig")}${GHOST.bar(4.6)}</span></span><div class="pd-pop sm">${GHOST.stack()}</div></div>`, { cls: "rqtok" });
const GHOST_COST = `${GHOST.focal(5)}<div class="pd-pop sm">${GHOST.stack()}</div><div class="bys"><p class="byh">By session</p>`
  + [[8, 90], [7, 62], [7.5, 55], [8.5, 40], [6.5, 31], [4, 0]].map(([w, p]) => `<p class="byr"><span class="bn">${GHOST.bar(w)}</span><span class="bb">${p ? `<i class="ghost" style="width:${p}%"></i>` : ""}</span><span class="bv">${GHOST.fig(3.4)}</span></p>`).join("") + "</div>";
const ghostBandHtml = (times) => DRAW.plate(`<div class="bcol">${DRAW.section("Outcomes", `${GHOST.hero()}${GHOST.stack()}${GHOST_KINDS}`, { cls: "rqout" })}`
  + `${times ?? GHOST_TIMES}</div><div class="bcol">${GHOST_TOKENS}${DRAW.section("Cost", GHOST_COST, { cls: "rqcost" })}</div>`, "rqband ghosts");
/** The band over the list: the outcomes split with the failure rate leading, the times on the list's own axis, the
 *  tokens with the share served from cache, and the cost with the sessions it went to, each drawn and each part a door
 *  to its rows. How it went stands in one column and what it used in the other (requests.css). */
function bandHtml() {
  const rows = state.loading || state.reading ? null : filtered(), b = bandOf();
  const times = rows ? timesHtml(rows, scalesOf(rows).ax) : null;
  if (!b) return ghostBandHtml(times);
  // a window that holds nothing has nothing to split, time or price: the list says No requests once, with its door to
  // the nearest window that has some, rather than four sections each saying none under it
  if (!b.m) return "";
  // Tokens and Cost count what an outcome he pressed lists; until that answer is in, they draw their own shapes
  const sel = b.listed != null || !selecting() ? b : b.sel?.scope === selScope() ? b.sel : null;
  const used = sel ? `${tokensHtml(sel.totals, sel)}${costHtml(sel.totals, sel)}` : `${GHOST_TOKENS}${DRAW.section("Cost", GHOST_COST, { cls: "rqcost" })}`;
  return DRAW.plate(`<div class="bcol">${outcomesHtml(b)}${times ?? GHOST_TIMES}</div><div class="bcol">${used}</div>`, "rqband");
}

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
let traceOwner = null, traceAbort = null;
function closeTrace() {
  traceAbort?.abort();
  traceOwner = null; traceAbort = null;
  RV.resetDocuments(); traces.clear();
}
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
  if (traceOwner !== r.id) { closeTrace(); traceOwner = r.id; traceAbort = new AbortController(); }
  if (!r.turn || traces.has(r.id)) return;
  const signal = traceAbort.signal;
  traces.set(r.id, { state: "reading", tr: null });
  const res = await API.scoped(signal).get(`/api/heads/${encodeURIComponent(r.head)}/trace?turn=${encodeURIComponent(r.turn)}&view=outline`);
  if (signal.aborted) return;
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
  const bodyOf = (part, record, section, key = "body") => part?.body_deferred
    ? RV.documentBody(`/api/heads/${encodeURIComponent(r.head)}/trace/document?${new URLSearchParams({ turn: r.turn, record, section })}`)
    : jsonOf(part?.[key]);
  let cut = Boolean(turn?.client?.truncated || turn?.answer?.truncated);
  const attempts = records.filter((x) => x.kind === "attempt").map((a, i) => {
    if (a.request?.truncated || a.response?.truncated) cut = true;
    const text = a.response?.text ?? "";
    const parsed = a.response?.body_deferred ? { raw: bodyOf(a.response, `attempt:${i}`, "response", "text") } : framesOf(text, a.transport);
    return {
      url: a.url ?? null, transport: a.transport ?? "http",
      start: Math.max(0, (a.ts ?? started) - (a.durationMs ?? 0) - started), ms: a.durationMs ?? 0,
      status: a.response?.status ?? null, headers: a.request?.headers ?? {}, body: bodyOf(a.request, `attempt:${i}`, "request"),
      resHeaders: a.response?.headers ?? null, ...parsed,
      failure: a.failure ?? null, lock: a.response?.status === 429,
    };
  });
  const answer = turn?.answer;
  const back = answer?.body_deferred ? { body: bodyOf(answer, "turn", "answer") }
    : answer?.stream ? framesOf(answer.body ?? "", "sse") : { body: jsonOf(answer?.body) };
  return { cut, sent: { headers: turn?.client?.headers ?? {}, body: bodyOf(turn?.client, "turn", "client") }, attempts,
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
// The record as the trace route answers it (TraceRoute), to copy or save whole: what a bug report or a provider ticket
// needs is the exact JSON, not the drawn view. Drawn from the start wherever records are kept or still being read, so
// nothing moves when they land; each press reads the whole record and spins where it stands while it does.
const recordActs = (r, st) => (r.turn && (RV_KEPT(st) || st === "reading")
  ? `${DRAW.act({ word: "Copy JSON", act: "copyrec" })}${DRAW.act({ word: "Download", act: "saverec" })}` : "");
const recordJson = async () => {
  const r = state.rows.find((x) => x.id === ui.open);
  if (!r) return null;
  // Export is the explicit request for the complete record. Browsing never takes this path.
  const answer = await API.get(`/api/heads/${encodeURIComponent(r.head)}/trace?turn=${encodeURIComponent(r.turn)}`);
  return answer.ok ? JSON.stringify(answer.body, null, 2) : null;
};

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
  // a resume the row placed stands on the rail as its tick, at the end of the silence it ended, and needs no line here
  if (r.resumed > 0 && !r.resumedAt) {
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
  const { o, lamp, lampCls, word } = stateOf(r);
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
  // a quote that is only "Error" says nothing the code beside it does not (Marlin, desk c, Oct 12: 43 of them)
  const quote = r.providerMessage && !(code != null && /^error\.?$/i.test(r.providerMessage.trim())) ? r.providerMessage : null;
  const said = quote || code != null
    ? `<p class="upmsg"><span class="who">${esc(who)}</span>${code != null ? `<span class="code">${code}</span>` : ""}`
      + `${quote ? `<q>${esc(quote)}</q>` : ""}</p>`
    : unanswered ? `<p class="upmsg"><span class="who">${esc(who)}</span><span class="state">No answer</span></p>` : ""; // fin's words, hitstop f4581a6
  // The row above it already names the session, the command, the model and the account, so the request opened under
  // it leads with one line: what the provider said, and its acts (Open session, Close); the record's own acts ride the
  // Record's kicker line. Timing sets the request beside this model's typical and slowest 5% on this command, so one
  // request reads as fast or slow against its own kind (slowLines).
  const c = costOf(r);
  return RV.paneHtml(r, { head: "line", said: said + noteHtml(r, who), lampCls, lamp, word, name: r.compare ? COMPARE_LABEL : sessTitle(r.sid), acct: r.account ? acctTag(r) : "", chip: headChip(r),
    acts: `${openSession(r)}<button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button>`, recActs: recordActs(r, st),
    st, gone: RV_KEPT(st) || st === "reading" ? "" : goneHtml(r, st),
    tr: () => traceOf(r), cells: unbilled ? ["cost", "attempts"] : ["in", "write", "out", "cost", "attempts"], cost: c.v,
    costSub: c.sub, ring: true, ref: answered(r) ? slowLine(r) : null });
}

// ---------- render ----------
const bandEl = document.getElementById("band");
let bandShown = "", paneWas = null;
function render() {
  // A request stays open through a filter that excludes it (paneHtml reads past the filter on purpose, and it stands
  // at the table's head), but a reload whose window no longer carries its row at all closes it.
  const pane = paneHtml();
  if (!pane) ui.open = null;
  if (!ui.open && traceOwner != null) closeTrace();
  const moved = paneWas !== ui.open;
  root.className = `requests${pane ? " open" : ""}`;
  // the picker and its doors on their own line above the filters, with no group headers: the log keeps time order
  const tc = state.tileCounts;
  document.getElementById("pickline").innerHTML = state.loading || state.sessionsPending ? ghostPickHtml()
    : pickLineHtml(pickGroups(), ui.project, ui.pmenu, { of: (k) => tc?.get(k) ?? null, all: tc?.get("") ?? null, unit: "request" });
  document.getElementById("filters").innerHTML = state.loading ? ghostFiltersHtml() : filtersHtml();
  // the band is drawn again only when what it says changed, so its parts keep their place under the pointer
  const band = state.error ? "" : bandHtml();
  if (band !== bandShown) { bandEl.innerHTML = band; bandShown = band; }
  // the open request stands in the table, right under its row, so the page is the one scroll at every width
  root.innerHTML = listHtml(pane);
  // the rest of the day draws as he scrolls: the next rows join when Show more comes into view, and the button stays
  // for a keyboard or a reader that never scrolls (Marlin, Oct 11)
  scrolledMore.disconnect();
  const more = root.querySelector('.more [data-act="more"]');
  if (more) scrolledMore.observe(more);
  placePane(moved);
  paneWas = ui.open;
  unfolding = null;
  RV.observeDocuments();
  const m = document.querySelector(".fmenu");
  if (m) m.querySelector("[aria-checked=true]")?.focus({ preventScroll: true });
}
/** The request opens under its row as the row's chevron turns (console.css .unfold, over the kit's snap time), only on
 *  the draw that opened it. The row he pressed stays under the pointer while a request open above it folds away, and
 *  a request that would open low on the screen brings its row up under the top bar. */
function placePane(moved) {
  const pane = root.querySelector(".rqpane"), row = root.querySelector('.rq[aria-current="true"]');
  const anchor = pressedAt; pressedAt = null;
  if (!pane || !moved) return;
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (row) {
    if (anchor != null) scrollBy(0, row.getBoundingClientRect().top - anchor);
    if (pane.getBoundingClientRect().top > innerHeight * 0.6) row.scrollIntoView({ block: "start", behavior: still ? "instant" : "smooth" });
  }
  pane.classList.add("unfold");
}
const find = (id) => filtered().find((r) => r.id === id);
let pressedAt = null; // where the row he pressed stood on screen, so opening it in place never moves it from under him
function open(id) {
  pressedAt = root.querySelector(`.rq[data-open="${CSS.escape(id)}"]`)?.getBoundingClientRect().top ?? null;
  ui.open = id; ui.tab = ui.tab || "sent";
  render();
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
  if (state.reading || state.loading) { state.reading = state.loading = false; render(); const top = ui.open && find(ui.open); if (top) readTrace(top); } // the rows are in: draw them, the band and the menus follow
  await Promise.all([readWider(), readCompareCount().then(readTileCounts), readBand(gen)]);
  if (gen !== readGen) return;
  state.loading = false;
  render();
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
  typing = setTimeout(() => reload({ reading: true }), 250);
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && ui.menu) { ui.menu = null; render(); }
  if (e.key === "Enter" && e.target.id === "goto") goTo(e.target.value);
});
document.addEventListener("click", async (e) => {
  const el = e.target.closest("[data-act], [data-open]");
  // A click outside an open menu only closes it. It never also opens the request under it: a pick in the browser's own
  // calendar landed on the row beneath and opened a request he never chose (Marlin, p225 step 14).
  if (ui.menu && !e.target.closest(".fmenu, [data-act=menu]")) { ui.menu = null; render(); return; }
  if (ui.pmenu && !e.target.closest(".pmenu, [data-act=projmenu]")) { ui.pmenu = false; render(); return; }
  // a door on the band is this list with one thing changed: followed here it filters in place, and a new tab (its
  // modified click or middle button) opens the same list from its address
  const door = e.target.closest("#band a[href^='requests.html?']");
  if (door && !e.metaKey && !e.ctrlKey && !e.shiftKey && e.button === 0) {
    e.preventDefault();
    applyAddress(new URLSearchParams(door.getAttribute("href").split("?")[1]), true);
    ui.menu = null; ui.n = 200; arriving = true; again();
    return;
  }
  if (!el) return;
  if (!el.dataset.act) {
    if (e.target.closest("a")) return;
    // the open request's own row folds it away again
    if (el.dataset.open === ui.open && el.closest(".rq")) { ui.open = null; render(); return; }
    open(el.dataset.open); return;
  }
  const v = el.dataset.v, m = el.dataset.m;
  switch (el.dataset.act) {
    case "theme": toggleTheme(); break;
    case "menu": ui.menu = ui.menu === m ? null : m; render(); break;
    case "projmenu": ui.pmenu = !ui.pmenu; ui.menu = null; render(); break;
    // the same pick as Sessions and Teams: the list is read again for the project, from the newest
    case "pickproj": ui.project = el.dataset.p || null; ui.pmenu = false; PICK.set(ui.project); ui.n = 200; again(); break;
    case "set": ui[m] = v || null; if (m === "win") ui.win = v; ui.menu = null; ui.n = 200; again(); break;
    case "unset": ui[m] = null; if (m === "from") ui.to = null; ui.n = 200; again(); break;
    case "outcome": ui.outcome = v; ui.n = 200; arriving = true; again(); break;
    case "order": ui.order = v; ui.n = 200; arriving = true; again(); break;
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
    // a burst opens to its rows in place; folding one that holds the open request folds that request too
    case "burst": {
      const holds = lastBursts.get(v)?.rows.some((r) => r.id === ui.open);
      if (ui.bursts.has(v) || holds) { ui.bursts.delete(v); if (holds) ui.open = null; } else { ui.bursts.add(v); unfolding = v; }
      render(); break;
    }
    case "kinds": ui.kindsOpen = !ui.kindsOpen; render(); break;
    case "bysess": ui.byAll = !ui.byAll; render(); break;
    // each spins where it stands while the whole record is read, and keeps its word (the redesign: no "Reading…")
    case "copyrec": {
      el.disabled = true; el.setAttribute("aria-busy", "true");
      const j = await recordJson(); el.disabled = false; el.removeAttribute("aria-busy");
      if (j) navigator.clipboard?.writeText(j).then(() => { el.textContent = "Copied"; setTimeout(() => { el.textContent = "Copy JSON"; }, 1600); }, () => { el.textContent = "Could not copy"; });
      else el.textContent = "Could not read · Retry";
      break;
    }
    case "saverec": {
      const id = ui.open;
      el.disabled = true; el.setAttribute("aria-busy", "true");
      const j = await recordJson(); el.disabled = false; el.removeAttribute("aria-busy"); el.textContent = j ? "Download" : "Could not read · Retry";
      if (!j) break;
      const a = document.createElement("a"), url = URL.createObjectURL(new Blob([j], { type: "application/json" }));
      a.href = url; a.download = `splice-request-${id}.json`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 0);
      break;
    }
    // the request folds away where it stands, so the list under it is where he left it
    case "close": ui.open = null; render(); break;
    case "tab": case "node": case "frames": case "document": RV.click(el); render(); break;
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
// a filter the daemon owns is a new read; the open request stays open while its request is still read
function again() { reload({ reading: true }); }
// the pick line is tiles or a picker by the width, so a new width draws again, once per frame
let resized = 0;
addEventListener("resize", () => { cancelAnimationFrame(resized); resized = requestAnimationFrame(render); });

// A door from another page carries its filters in the address (?win=7d&cmd=claude-grok&why=cut): the list opens
// filtered, with each filter as its chip, and the top match open where there's room. The project is spelled
// `project`, the word every other screen uses for it (a chart column on Usage opens
// ?project=splice&from=…&to=…); `repo` is the same filter under the name the session record gives it, kept so an
// older link still opens. A from-to span names epoch milliseconds, half-open as the daemon reads it, and `to`
// left out means "through now" rather than an open end nobody can see.
// one kind of failure as a person reads it: its outcome tag, or every tag under one word (Rate limited is three; marlin)
const TAGS = /^((error|failure):)?[a-z0-9_-]+(,((error|failure):)?[a-z0-9_-]+)*$/;
/** The filters an address names, set on the page. [fresh] clears every filter the address leaves out first, so a band
 *  door followed in place lists what the same address opens in a new tab; the project stays the pick's (kit PICK). */
function applyAddress(a, fresh = false) {
  if (fresh) Object.assign(ui, { win: "24h", cmd: null, sid: null, model: null, account: null, repo: null, from: null, to: null, outcome: "all", tag: null, why: null, compact: false, order: "new" });
  if (WINDOWS[a.get("win")]) ui.win = a.get("win");
  for (const k of ["cmd", "model", "account", "session"]) if (a.get(k)) ui[k === "session" ? "sid" : k] = a.get(k);
  ui.repo = a.get("repo") ?? ui.repo;
  if (a.get("from")) ui.from = +a.get("from");
  if (a.get("to")) ui.to = +a.get("to"); // a stretch's end, or the Up to a list was read to; no end is through now
  if (WHY[a.get("why")]) ui.why = a.get("why"); // Started over's door arrives with its toggle pressed
  if (["clean", "stopped", "fail"].includes(a.get("outcome"))) ui.outcome = a.get("outcome");
  if (TAGS.test(a.get("tag") ?? "")) ui.tag = a.get("tag");
  if (a.get("compact") === "1") ui.compact = true; // Usage's compactions count opens with the Compactions toggle on
  if (a.get("order") === "price") ui.order = "price"; // Usage's Breakdown counts open the highest price first
  if (fresh || a.get("q")) { ui.q = a.get("q") ?? ""; document.getElementById("q").value = ui.q; }
}
applyAddress(new URLSearchParams(location.search));
// a door that names one request shows that request, never the top row. Doors encode the id ("claude-splice%3A…"), so
// it is decoded before it is matched: matched raw, Settings' Open it landed on the unfiltered top (Marlin's rewalk 4)
const askedOf = () => { try { return decodeURIComponent(location.hash.slice(1)); } catch { return location.hash.slice(1); } };
const asked = askedOf();
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

});
