// Teams: a team is his. Members on different models, each a role on a command, with a goal; splice carries the
// members' sessions and reports what they do. Create one and start its members; track each member's state, their
// messages to each other, what each is doing and what it has cost.
//
// WHERE EVERY VALUE COMES FROM. The page carries no data of its own and keeps no copy of one:
//   GET  /api/teams                        the teams and their slots, archived included
//   GET  /api/sessions                     each bound session's own state, as its client registered it
//   GET  /api/heads · /api/models · /api/auth   the commands, the models each serves and its accounts
//   GET  /api/accounts                     each plan login's windows, as Accounts and Usage read them
//   GET  /api/teams/{id}/economics         turns, tokens and dollars per slot, over every session it held
//   GET  /api/teams/{id}/chat?from=&to=    the day's messages between members, from the sender's own transcript
//   GET  /api/teams/{id}/activity?from=&to=   the day's tool work per member, each a tool and its object
//   PUT  /api/teams · /api/teams/{id} · /api/teams/{id}/sessions · .../slots/{slot}/instructions
//   POST /api/teams/{id}/archive · /api/teams/{id}/restore · .../slots/{slot}/start · .../slots/{slot}/stop
// The day panels take the VIEWER's own day in epoch milliseconds, so the board turns over at his midnight.
//
// THE PAGE ANSWERS IN THE ORDER HE ASKS: does anyone need me (the tab's needs dot, and that member's card first), is the
// team moving (each member's lamp and word), what did they say and do (the chat beside the members, the activity under
// them), and what did it use (the team's band, each member's own figures and its plan's windows). The page is the one
// thing that scrolls: a long list shows its latest few and a count of the rest that opens them in place.
//
// A REFUSAL IS SHOWN WHERE IT HAPPENED, as a word. A start that does not come up puts its reason on the member's card
// beside Try again, because "the terminal is still showing a trust prompt" is what tells him what to do next.
CONSOLE.page("teams.html", (scope) => {
  "use strict";
  const { document, window, API, setTimeout, clearTimeout, setInterval, clearInterval, requestAnimationFrame, cancelAnimationFrame, addEventListener, removeEventListener } = scope;

// ---------- the marks this page adds to the kit's own (kit.js holds esc, G, COLORS, colorOf, clock) ----------
const GLYPH = {
  Edit: G('<path d="M4 20l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L8 18.5z"/><path d="M13.5 7l3 3"/>', 'aria-hidden="true"'),
  Read: G('<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4M9 12h6M9 16h6"/>', 'aria-hidden="true"'),
  Bash: G('<rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M7 10l3 2.5L7 15M12.5 15.5h4.5"/>', 'aria-hidden="true"'),
  Grep: G('<circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/>', 'aria-hidden="true"'),
  msg: G('<path d="M4 12h15M14 6.5l5.5 5.5-5.5 5.5"/>', 'aria-hidden="true"'),
  Agent: G('<circle cx="6" cy="5" r="2.2"/><circle cx="6" cy="19" r="2.2"/><circle cx="18" cy="7" r="2.2"/><path d="M6 7.2v9.6M18 9.2c0 4.5-6 3.6-11.3 7.4"/>', 'aria-hidden="true"'),
  tool: G('<path d="M14.5 6.5a4 4 0 1 0 3 6.8L21 17l-2 2-3.7-3.5A4 4 0 0 1 8.5 9"/><path d="M3 20l6-6"/>', 'aria-hidden="true"'),
  folder: G('<path d="M3 6.5h6l2 2h10v10.5H3z"/>', 'aria-hidden="true"'),
  back: G('<path d="M15 5l-7 7 7 7"/>', 'aria-hidden="true"'),
  next: G('<path d="M9 5l7 7-7 7"/>', 'aria-hidden="true"'),
  caret: G('<path d="M9 5l7 7-7 7"/>', 'class="caret" aria-hidden="true"'),
  trace: '<svg class="wave" viewBox="0 0 40 40" aria-hidden="true"><polyline class="base" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"/><polyline class="beat" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"/></svg>',
};
// Write and the notebook editor draw the same mark as their neighbours; a tool with no mark of its own gets the wrench.
GLYPH.Write = GLYPH.Edit;
GLYPH.MultiEdit = GLYPH.Edit;
GLYPH.NotebookEdit = GLYPH.Edit;
GLYPH.Glob = GLYPH.Grep;
GLYPH.Task = GLYPH.Agent;
GLYPH.SendMessage = GLYPH.msg;

// A slot names its HEAD; the kit colours a PROVIDER, and /api/models says which head runs which.
const headColor = (head) => colorOf(state.providerOf[head]);
// Every time on this page is epoch millis, as its routes report them; the kit's clock reads a Date.
const hhmm = (ms) => clock(new Date(ms));
const monthDay = (ms) => {
  const d = new Date(ms);
  return `${d.toLocaleString("en-US", { month: "short" })} ${d.getDate()}`;
};
// A figure whose reading may be missing, on its own baseline: the dash takes the figure's place, never a word and never
// a zero (DRAW.focal escapes its figure, so the dash's own markup needs this one).
const focal = (figure, words) => `<div class="pd-sub"><b>${figure == null ? DRAW.dash : esc(figure)}</b>${esc(words)}</div>`;
const plural = (n, word) => `${DRAW.n(n)} ${n === 1 ? word : `${word}s`}`;

// ---------- what the page holds: the daemon's answers, and what he is doing to them ----------
const state = {
  teams: [], sessions: {}, heads: [], models: {}, accounts: {}, pools: {}, providerOf: {}, modelLabel: {}, plans: [],
  economics: {}, priceWhy: {}, chat: null, activity: null, today: null, live: {}, stalls: {},
  // What each waiting member's screen is offering, by slot id: read only for a member whose ask is not in
  // its transcript, which is a permission prompt, since those choices belong to the client's own version.
  screens: {},
  // What a member's screen asks before its session registered (its folder trust question), by slot id: the start
  // stopped there and left the pane open for him to answer (Marlin, Oct 12).
  pending: {},
  // a first read that failed has nothing to show but its word; a later one keeps the board, dimmed, and says since when
  error: null, stale: null, loading: true, everRead: false, readAt: null, reading: true,
};
// [change] is the member he is changing on its own card: its role, command, model and account as he has set them so far
const ui = { words: new Map(), project: PICK.get(), pmenu: false, team: null, day: 0, menu: null, compose: null, pick: null, change: null, archived: false,
  starting: new Set(), startedAt: new Map(), unstarted: new Map(), trusting: new Map(), screenOpen: new Set(), ending: new Map(),
  armedEnd: new Map(), armedStop: new Set(), failed: new Map(), stopping: new Set(), answered: new Map(),
  // the day lists he opened past their latest few, by team and day; when each team's figures first landed, so its bars
  // grow in once and never on a re-read; and when each message was first drawn, so one that lands later rises in once
  chatOpen: new Set(), feedOpen: new Set(), landed: new Map(), firstSeen: new Map() };
// From his press until the member moves on, an answer keeps its words with the wait ring and the rest wait disabled; a
// Stop keeps its place reading Stopping. Same as Sessions (stopHtml, askHtml): the styles are sessions.css's.
const ANSWER_HOLD_MS = 30_000;
// how long a landing keeps its motion in the markup: a redraw inside it draws the same marks, so the motion is not cut
const GROW_MS = 700, ARRIVE_MS = 1500;
const root = document.getElementById("teams");
const sheet = document.getElementById("sheet");

const team = () => state.teams.find((t) => t.id === ui.team) || null;
// a member that left still names its own messages and its own figures: its history stays on the team (spec §14)
const leftOf = (tm) => (tm.left_slots || []).map((l) => ({ ...l.slot, left_epoch_millis: l.left_epoch_millis }));
const slotOf = (tm, id) => tm.slots.find((s) => s.id === id) || leftOf(tm).find((s) => s.id === id) || null;
const isLeft = (tm, slot) => !tm.slots.some((s) => s.id === slot.id);
const sessOf = (slot) => (slot.session ? state.sessions[slot.session] : null);
// ONE ACTIVE TEAM PER SESSION is what PUT refuses (Marlin), but older data may hold a session on two. Never pick one
// silently: a session that looks single-homed when it is not is the worse failure, so each team that holds it names
// the other. Archived teams do not count, they have let go.
const alsoOn = (tm, session) => state.teams
  .filter((t) => t.id !== tm.id && !t.archived && t.slots.some((y) => y.session === session))
  .map((t) => t.name);
/** A MEMBER'S NAME, the same wherever it stands (its card, the chat, the by-member bar, the timeline): its role, with
 *  its place when two current members share the role ("Builder 2"), and "(left)" once it has left the team. */
function nameOf(tm, slot) {
  const role = String(slot.role || slot.id || "").trim();
  if (isLeft(tm, slot)) return `${role} (left)`;
  const same = tm.slots.filter((s) => String(s.role || s.id || "").trim().toLowerCase() === role.toLowerCase());
  return same.length > 1 ? `${role} ${same.findIndex((s) => s.id === slot.id) + 1}` : role;
}

// The day the panels are asked for, as the VIEWER's own: midnight to midnight here, [from, to).
function dayWindow(back) {
  const from = new Date();
  from.setHours(0, 0, 0, 0);
  from.setDate(from.getDate() - back);
  const to = new Date(from);
  to.setDate(to.getDate() + 1);
  return { from: +from, to: +to };
}
const dayName = (back) => (back === 0 ? "Today" : back === 1 ? "Yesterday" : monthDay(dayWindow(back).from));

// ---------- reading ----------
/** The turns running now, by session: what a Stalled or Resumes line is read from (kit stallOf). */
async function readLive() {
  const lives = await Promise.all(state.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/turns/live`)));
  state.live = {};
  state.heads.forEach((h, i) => {
    for (const turn of lives[i].body?.turns || []) if (turn.session && !turn.stopped) state.live[turn.session] = { head: h.key, ...turn };
  });
}

// Read again stays off until the read it started has landed, its team's own reads included (splice-lead, Oct 12): a
// second click mid-read started a second read racing the first. A read asked for while one is out waits for it and then
// reads again, so an act's own read always sees what the act did.
let inFlight = null;
async function read() {
  while (inFlight) await inFlight;
  const again = document.querySelector('.head [data-act="read"]');
  // its own glyph turns while the read it started is in flight, so the act says it is working where it stands
  if (again) { again.disabled = true; again.classList.add("turning"); }
  inFlight = readAll();
  try {
    await inFlight;
  } finally {
    inFlight = null;
    if (again) { again.disabled = false; again.classList.remove("turning"); }
  }
}

/** WHEN THE PAGE LAST READ, in the head beside Read again: "As of 10:37 AM", moving on every five-second read. When a
 *  read fails after the board landed it reads "Not answering since 10:37 AM", in the limit red, and the act beside it
 *  reads Try again: one place says it for the whole page, and the board under it stays, dimmed. */
function markRead() {
  const el = document.querySelector(".head .readat");
  if (el) {
    const at = state.readAt ? hhmm(state.readAt) : "";
    el.textContent = !at ? "" : state.stale ? `${state.stale} since ${at}` : `As of ${at}`;
    el.classList.toggle("limit", Boolean(state.stale));
    if (state.readAt) el.setAttribute("datetime", new Date(state.readAt).toISOString());
  }
  const word = document.querySelector('.head [data-act="read"] > span');
  if (word) word.textContent = state.stale ? "Try again" : "Read again";
}

const takeSessions = (body) => {
  state.reading = body?.reading !== false; // off: a waiting member shows no question, as Sessions does
  state.sessions = Object.fromEntries((body?.sessions || []).filter((s) => s.session_id).map((s) => [s.session_id, s]));
};

async function readAll() {
  // the first read draws the shape of what will land; a later one keeps the board and turns Read again's glyph
  if (!state.everRead && !state.error) { state.loading = true; render(); }
  // the team list answers first on a daemon that is not slow: while the first frame is up, it takes the shape of what the
  // list says is coming (a team's page, or the empty one) without waiting for the rest
  const teamsRead = API.get("/api/teams").then((res) => {
    if (state.loading && res.ok && state.known == null) { state.known = (res.body?.teams || []).length; render(); }
    return res;
  });
  const [teams, sessions, heads, models, auth, plans] = await Promise.all([
    teamsRead, API.get("/api/sessions"), API.get("/api/heads"), API.get("/api/models"), API.get("/api/auth"),
    API.get("/api/accounts"),
  ]);
  // A READ THAT FAILS KEEPS WHAT IS ON THE PAGE: after the board landed, the teams and every member's state stay as they
  // were, dimmed, and the head says since when; only a first read that fails has nothing to show but its word (Marlin
  // p223: a failed launch's reread blanked every team). Without the sessions every member would read Gone.
  const refused = !teams.ok ? refusedAs(teams, "Not read", "/api/teams") : !sessions.ok ? refusedAs(sessions, "Not read", "/api/sessions") : null;
  state.error = refused && !state.everRead ? refused : null;
  state.stale = refused && state.everRead ? refused : null;
  state.loading = false;
  if (refused) { render(); return; }
  state.teams = teams.body?.teams || [];
  state.everRead = true;
  state.readAt = Date.now();
  takeSessions(sessions.body);
  // each plan's windows as its provider reads them, Accounts' and Usage's numbers, for a member's plan rows
  if (plans.ok) state.plans = plans.body?.accounts || [];
  // each head's own marks beside its name (HeadResolver.headStatuses, FleetMount's cannotStart): what a command can do
  // now, so the Command menu says it where the choice is made instead of after a start fails
  if (heads.ok) {
    state.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key, reanchorMs: h.gate?.stall_reanchor_ms ?? null, idleMs: h.gate?.stream_idle_ms ?? null, firstByteMs: h.gate?.first_byte_timeout_ms ?? null,
      down: Boolean(h.runtimeNotAnswering), unread: h.runtimeUnread === true, quotaUntil: h.quotaResetAtEpochSeconds ?? null, quotaFull: Boolean(h.quotaFull), cannotStart: h.cannotStart || null }));
  }
  await readLive();
  if (models.ok) {
    state.models = {};
    state.providerOf = {};
    for (const row of models.body?.heads || []) {
      // /api/models names a provider in its own module's words ("anthropic"), and the kit colours a FAMILY ("claude"),
      // so every member card drew in the muted colour instead of its command's (kit PROVIDER_FAMILY, as Usage reads it)
      state.providerOf[row.head] = PROVIDER_FAMILY[row.provider] ?? row.provider;
      learnModels(state.modelLabel, row); // every page names a model the same way: kit's labels, then its words
      // a model's context and whether its command defaults to it: what the manager's own model menu shows beside a name
      state.models[row.head] = (row.models || []).filter((m) => m.resolved !== false)
        .map((m) => ({ id: m.id, label: m.label || m.id, context: m.context_window ?? null, pinned: Boolean(m.pinned) }));
    }
  }
  // /api/auth answers one entry per HEAD, keyed by its key; a head with two or more logins lists them under account_pool
  // (AccountPoolJson). A slot picks by head, so each head offers its own pool's labels; a head with one login offers none.
  // Each pooled login also carries the ref /api/accounts keys the same login by, and the pool's own reading of its two
  // windows: that is how a member's account, which the economics name by label, finds its plan row. Nothing else of the
  // login is kept, its sign-in name least of all.
  if (auth.ok) {
    state.accounts = {};
    state.pools = {};
    for (const [head, entry] of Object.entries(auth.body || {})) {
      const pooled = (entry?.account_pool?.accounts || []).filter((a) => a?.label);
      if (pooled.length) state.accounts[head] = pooled.map((a) => a.label);
      state.pools[head] = pooled.map((a) => ({ label: a.label, ref: a.auth?.ref ?? null,
        five: a.five_hour_used_percent ?? null, fiveAt: a.five_hour_reset_epoch_seconds ?? null,
        seven: a.seven_day_used_percent ?? null, sevenAt: a.seven_day_reset_epoch_seconds ?? null }));
    }
  }
  if (!state.teams.some((t) => t.id === ui.team)) ui.team = state.teams.find((t) => !t.archived)?.id ?? state.teams[0]?.id ?? null;
  render();
  await readTeam();
}

/** The selected team's own three reads, each of which can refuse on its own. Only the newest read draws: one asked for
 *  another team or day that lands after it is dropped, and the day panels show their reading state until their own
 *  day lands, never the day before under the new day's name (marlin, Oct 13: Yesterday read No messages over 295). */
let teamRead = { gen: 0, key: null, team: null };
async function readTeam() {
  const tm = team();
  if (!tm) return;
  const key = `${tm.id}/${ui.day}`, gen = ++teamRead.gen, day0 = ui.day;
  if (teamRead.key !== key) { state.chat = null; state.activity = null; teamRead.key = key; }
  // Cleared before the first draw of ANOTHER team, not after: slot ids repeat across teams ("lead", "builder"), so a
  // screen left over from the team before would draw its choices on this team's card for one frame. The same team's
  // screens stay until their own read replaces them, so a question on the card does not blink on Read again.
  if (teamRead.team !== tm.id) { state.screens = {}; state.pending = {}; teamRead.team = tm.id; }
  render();
  const { from, to } = dayWindow(day0);
  const day = `from=${from}&to=${to}`;
  const [economics, chat, activity, perHead] = await Promise.all([
    API.get(`/api/teams/${tm.id}/economics`),
    API.get(`/api/teams/${tm.id}/chat?${day}`),
    API.get(`/api/teams/${tm.id}/activity?${day}`),
    API.get("/api/economics"),
  ]);
  if (gen !== teamRead.gen) return;
  // why a command's turns with no price have none, decided by the daemon once (as Accounts reads it): "plan" or not
  if (perHead.ok) state.priceWhy = Object.fromEntries((perHead.body?.heads || []).map((h) => [h.key, h.unpriced_reason]));
  state.economics[tm.id] = kept(economics, state.economics[tm.id], `economics ${tm.id}`);
  state.chat = kept(chat, state.chat, `chat ${tm.id}`);
  state.activity = kept(activity, state.activity, `activity ${tm.id}`);
  if (!state.economics[tm.id].error && !ui.landed.has(tm.id)) ui.landed.set(tm.id, Date.now());
  state.today = day0 === 0 ? state.activity : await readToday(tm);
  if (gen !== teamRead.gen) return;
  render();
  await readScreens(tm);
}
/** A team read that failed keeps the answer it replaces, and the head says since when; with nothing to keep, its word. */
function kept(answer, was, what) {
  if (answer.ok) return answer.body;
  const word = refusedAs(answer, "Not read", what);
  if (was && !was.error) { state.stale ??= word; return was; }
  return { error: word };
}

/** Today's activity, read on its own when the page shows another day, so a card's line is never a stepped day's. */
async function readToday(tm) {
  const { from, to } = dayWindow(0);
  const today = await API.get(`/api/teams/${tm.id}/activity?from=${from}&to=${to}`);
  return today.ok ? today.body : null;
}

/** The choices a waiting member is showing, for the asks its transcript does not carry: a permission prompt's
 *  words belong to the client's version, so they are read off the screen or not drawn at all. A screen that
 *  offers nothing leaves the card on its fallback, because "splice could not read a choice" is not "nothing is
 *  pending", and the two must never look alike. */
async function readScreens(tm) {
  const waiting = tm.slots.filter((slot) => {
    const s = sessOf(slot);
    return s?.waiting_for && !(s.last?.asks || []).length;
  });
  // a slot bound to a session that never registered: a start that stopped on a question, which its screen still shows
  const asking = tm.slots.filter((slot) => slot.session && !sessOf(slot));
  const screens = {}, pending = {};
  if (waiting.length || asking.length) {
    const read = await Promise.all([...waiting, ...asking].map((slot) => API.get(`/api/teams/${tm.id}/slots/${slot.id}/screen`)));
    waiting.forEach((slot, i) => {
      if (read[i].ok && read[i].body?.choices?.length) screens[slot.id] = read[i].body;
    });
    asking.forEach((slot, i) => {
      const r = read[waiting.length + i];
      if (r.ok && r.body?.choices?.length) pending[slot.id] = r.body;
    });
  }
  if (team()?.id !== tm.id) return;
  state.screens = screens;
  state.pending = pending;
  render();
}

/** A refused call's word on the page (fin, Oct 13): [word] for a refusal, "Not answering" when splice did not answer at
 *  all, and the daemon's sentence, or the status it answered, to the console's log under [what], never on the screen. */
function refusedAs(answer, word, what) {
  console.warn(`${what}: ${answer.status === 0 ? "splice is not answering" : answer.body?.error || `splice answered ${answer.status}`}`);
  return answer.status === 0 ? "Not answering" : word;
}

// ---------- a member's state, in the words its own client registered ----------
const WAITING = { "input needed": "Needs you", "permission prompt": "Needs you" };
/** What the member's running turn says about its silence: Stalled with the retries or the silence counter, or the resume
 *  countdown, drawn by the kit's own look() so Teams and Sessions say it alike. Null when nothing is wrong.
 *  An idle member whose newest request was turned away reads At limit or Signed out from the row's ended_by. */
function stallLook(slot, session) {
  if (!session || session.waiting_for || session.availability === "gone") return null;
  const running = session.status === "working" || session.status === "busy";
  // with no request in flight, the row's ended_by says how the newest one ended: At limit, or Signed out
  const turn = running ? state.live[slot.session] : undefined;
  const stall = stallOf(turn, state.heads.find((h) => h.key === turn?.head), running ? undefined : session.ended_by);
  if (!stall) return null;
  state.stalls[slot.id] = stall;
  return look({ id: slot.id, state: running ? "working" : "idle", stall });
}
function stateOf(session) {
  if (!session) return { cls: "ended", lamp: "<i></i>", word: "Gone", detail: "" };
  if (session.availability === "gone") return { cls: "ended", lamp: "<i></i>", word: "Ended", detail: "" };
  // No detail beside the word: the card's own ask, right below it, says what is wanted better than
  // Claude Code's "input needed" does, and the drawing has nothing there for the same reason.
  if (session.waiting_for) {
    // the two asks keep their own glyphs, as Sessions draws them: a permission is the shield, a question the bubble
    const lamp = session.waiting_for === "permission prompt" ? ICON.dialog : ICON.input;
    return { cls: "needs", lamp, word: WAITING[session.waiting_for] || "Needs you", detail: "" };
  }
  if (session.status === "working" || session.status === "busy") return { cls: "working", lamp: GLYPH.trace, word: "Working", detail: "" };
  const word = session.status ? session.status[0].toUpperCase() + session.status.slice(1) : "Idle";
  return { cls: session.availability === "stale" ? "stopped" : "idle", lamp: "<i></i>", word, detail: "" };
}
const liveNow = (session) => Boolean(session) && session.availability === "live" && !session.waiting_for &&
  (session.status === "working" || session.status === "busy");
/** THE ORDER THE BOARD READS IN: a member who needs him first, then one that is stuck (stalled, at a limit, signed out),
 *  then the team's own order, so the card that wants him is the first one he sees. */
function ordered(tm, slots) {
  const rank = (slot) => {
    const s = sessOf(slot);
    if (s?.waiting_for || (!tm.archived && !s && state.pending[slot.id])) return 0;
    return s && s.availability !== "gone" && stallLook(slot, s) ? 1 : 2;
  };
  return slots.map((slot, i) => ({ slot, i, r: rank(slot) })).sort((a, b) => a.r - b.r || a.i - b.i).map((x) => x.slot);
}

// ---------- the figures: turns, tokens and dollars, and the turns no rate card priced ----------
const econRow = (tm, slotId) => (state.economics[tm.id]?.slots || []).find((r) => r.slot === slotId) || null;
/** A member's checks, drawn only when the team's economics row carries a value: a member with none draws nothing,
 *  since a source sentence on every idle card is plumbing (marlin, Oct 13). Packet has no column at all: the message is
 *  the unit, and the chat shows it. */
function checksHtml(tm, slotId) {
  const row = econRow(tm, slotId);
  return row?.checks ? `<p class="checks">${esc(row.checks)}</p>` : "";
}

function useOf(tm, slotId) {
  const row = econRow(tm, slotId);
  if (!row) return null;
  // The tokens figure is everything the slot's turns moved, the two cache counts included, as the route reports them.
  const t = row.tokens || {};
  const tokens = (t.input || 0) + (t.cache_read || 0) + (t.cache_write || 0) + (t.output || 0);
  // a request that moved no tokens is in no money share (the route counts the ones that did, and the priced of those)
  const priced = row.priced_moved_turns ?? 0, unpriced = (row.moved_turns ?? 0) - priced, head = slotOf(tm, slotId)?.head;
  // a daemon that splits plan from key counts the slot's plan requests itself (plan_turns, as the economics buckets do);
  // before that, a head whose unpriced requests are on a plan counts them all there
  const onPlan = row.plan_turns ?? (state.priceWhy[head] === "plan" ? unpriced : 0);
  return { turns: row.turns ?? 0, tokens, usd: row.cost_usd ?? null, priced, unpriced, onPlan };
}
/** THE DOOR A FIGURE OPENS: exactly the requests it counted, on Requests. A slot that held ONE session has one — that
 *  session, from the moment it joined, on its command. A slot that held none, or two, has none: Requests takes a single
 *  session (requests.js ADDR), and a door that opens a different set than the figure counted is worse than no door. */
function slotDoor(tm, slot) {
  const held = slot.sessions_history?.length ? slot.sessions_history : slot.session ? [slot.session] : [];
  if (held.length !== 1) return null;
  const joined = (slot.joined_epoch_millis || {})[held[0]];
  const p = new URLSearchParams({ project: "", session: held[0] });
  const cmd = commandOf(slot.head);
  if (cmd) p.set("cmd", cmd);
  if (joined) p.set("from", String(joined));
  return `requests.html?${p}`;
}
/** A count with its unit, as a door when the figure has one: the figure itself opens its requests, never a "see all". */
const countDoor = (n, word, href) => {
  const text = plural(n, word);
  return href ? `<a class="rq" href="${href}">${text}${DRAW.go}</a>` : text;
};

// EVERY REQUEST OF A TEAM FALLS IN ONE OF FOUR PARTS, and they are one whole, so they are one bar. None of them is a
// failure, so none takes the limit red and none takes the kit's "nt" (which spells its own words): teams.css gives each
// its own neutral tone. On a plan and priced are what was paid for two ways; the other two are what no rate card reached.
const SPLIT = [["onPlan", "On your plan", "plan"], ["priced", "Priced", "priced"], ["noPrice", "No price", "noprice"], ["noTokens", "No tokens", "none"]];
const partsOf = (u) => ({
  onPlan: u.onPlan, priced: u.priced,
  noPrice: Math.max(0, u.unpriced - u.onPlan),
  noTokens: Math.max(0, u.turns - u.priced - u.unpriced),
});
/** The money split, drawn: one bar over the requests, each part's word and count inside its own segment. */
function splitStack(u, opts = {}) {
  const parts = partsOf(u);
  return DRAW.stack(SPLIT.map(([k, word, cls]) => ({ n: parts[k], word, cls })), u.turns, opts);
}
function totals(tm) {
  const us = [...tm.slots, ...leftOf(tm)].map((s) => useOf(tm, s.id)).filter(Boolean);
  const priced = us.filter((u) => u.usd != null);
  return {
    turns: us.reduce((a, u) => a + u.turns, 0),
    tokens: us.reduce((a, u) => a + u.tokens, 0),
    usd: priced.length ? priced.reduce((a, u) => a + u.usd, 0) : null,
    priced: us.reduce((a, u) => a + u.priced, 0),
    unpriced: us.reduce((a, u) => a + u.unpriced, 0),
    onPlan: us.reduce((a, u) => a + u.onPlan, 0),
  };
}
/** The oldest turn the perf files still hold, named only when it is after the team was made. */
function sinceOf(tm) {
  const oldest = state.economics[tm.id]?.oldest_turn_epoch_millis;
  return oldest && oldest > (tm.created_epoch_millis || 0) ? oldest : null;
}
/** Whether a team's figures landed a moment ago: its bars grow in on that draw only. */
const growing = (tm) => Date.now() - (ui.landed.get(tm.id) ?? -Infinity) < GROW_MS;

/** A member's requests as one bar on the TEAM's scale, cut into how they were paid in the tones of the team's own payment
 *  bar, each part named on hover. */
function splitBar(u, scale) {
  const parts = partsOf(u);
  const w = scale > 0 ? (u.turns / scale) * 100 : 0;
  const segs = SPLIT.filter(([k]) => parts[k] > 0)
    .map(([k, word, cls]) => `<i class="${cls}" style="flex-grow:${parts[k]}" title="${esc(`${word} ${DRAW.n(parts[k])}`)}"></i>`).join("");
  return `<span class="mbar" aria-hidden="true"><span style="width:${w.toFixed(2)}%">${segs}</span></span>`;
}
/** A MEMBER'S REQUESTS, DRAWN BEFORE THEY ARE WRITTEN, on one line: the split bar on the team's scale, then the count, a
 *  door to exactly those requests, its tokens and its price where any were priced. The Left strip and the timeline's
 *  column heads draw it; [brief] keeps the count and the price alone, for the timeline's narrow heads. */
function reqHtml(tm, slot, u, scale, { grow = false, brief = false } = {}) {
  // no requests, no tokens: "0 tokens" beside "0 requests" says nothing more (fin, Oct 12); a price is drawn only where
  // requests were priced, and a price splice could not read for requests that WERE priced is the dash
  const tokens = !brief && u.turns && u.tokens ? `<span>${DRAW.big(u.tokens)} tokens</span>` : "";
  const price = u.turns && u.priced > 0 ? `<span>${u.usd != null ? esc(dollars(u.usd)) : DRAW.dash} priced</span>` : "";
  return `<div class="mreq${grow ? " grow" : ""}">${splitBar(u, scale)}` +
    `<span class="mfig">${countDoor(u.turns, "request", slotDoor(tm, slot))}${tokens}${price}</span></div>`;
}

// ---------- a member's plan: what it moved on its plan, and the plan's own windows (web-console §4.13; marlin) ----------
// The daemon counts the member's requests and tokens on each plan account over the last 5 hours and 7 days, the windows
// Usage reads a plan over (TeamsEconomics). The plan's used share is the provider's own, read from /api/accounts as
// Accounts reads it, and only set beside the member's count: splice never says what share of a window is the member's.
/** The /api/accounts row of the login a member's requests were counted on. The economics name the login by its pool
 *  label on its head; /api/auth lists the pool's logins with the ref /api/accounts keys each one by, and a row also
 *  carries its label on each head. So the pool entry's ref is tried first, then the row's own label for the head, its
 *  selector, its label, its name or its ref. A ref-keyed row was missed when only the label was compared to the ref. */
function planRowOf(head, label) {
  const want = String(label).toLowerCase();
  const rows = state.plans.filter((a) => (a.heads || []).includes(head) && a.kind !== "api-key");
  const ref = (state.pools[head] || []).find((p) => String(p.label).toLowerCase() === want)?.ref;
  return (ref && rows.find((a) => a.ref === ref))
    || rows.find((a) => [a.account_labels?.[head], a.selector_key, a.label, a.display_name, a.ref]
      .some((x) => x != null && String(x).toLowerCase() === want))
    || null;
}
/** The plan a member's login is on: its /api/accounts row, else the pool's own reading of its two windows (which names
 *  no login and carries no window length), else nothing. */
function planOf(head, label) {
  const row = planRowOf(head, label);
  if (row) return row;
  const p = (state.pools[head] || []).find((x) => String(x.label).toLowerCase() === String(label).toLowerCase());
  return p ? { five_hour_used_percent: p.five, five_hour_reset_epoch_seconds: p.fiveAt, seven_day_used_percent: p.seven, seven_day_reset_epoch_seconds: p.sevenAt } : null;
}
/** A login's name as Accounts shows it, never an id where a name is read; the pool's label when no name is read. */
const accountName = (head, label) => planRowOf(head, label)?.display_name || label;
// Each window of a plan account, its name, and the plan fields its own share is read from (Usage's own). The member's own
// figures keep the span they were counted over (MINE below); the plan's long window is named as the plan runs it: a
// window of four weeks or more is its month.
const PLAN_WINDOWS = {
  "5h": { plan: () => "5 hours", pct: "five_hour_used_percent", at: "five_hour_reset_epoch_seconds", len: "five_hour_window_seconds", now: "five_hour_current", secs: 5 * 3600 },
  "7d": { plan: (len) => (len >= 28 * 86400 ? "Month" : "Week"), pct: "seven_day_used_percent", at: "seven_day_reset_epoch_seconds", len: "seven_day_window_seconds", now: "seven_day_current", secs: 7 * 86400 },
};
/** One window of the plan, as Accounts draws one: its name, the meter, "NN% used" and the reset ring with its time. At
 *  the limit the meter and the figure are red; read before the window moved, the fill is hollow; a share not reported,
 *  or one whose window has refilled since it was read, keeps the dashed track and the dash. */
function windowRow(plan, win, id) {
  const W = PLAN_WINDOWS[win];
  const name = `<span class="pwin">${esc(W.plan(plan?.[W.len] ?? 0))}</span>`;
  const resetMs = plan?.[W.at] ? plan[W.at] * 1000 : null, left = resetMs != null ? resetMs - Date.now() : null;
  const pct = plan && plan[W.pct] != null && !(left != null && left <= 0) ? Math.round(plan[W.pct]) : null;
  if (pct == null) {
    // a window whose reset has passed has refilled since splice read it: its share is unknown, its time is history
    const when = left != null && left <= 0 ? `<span class="when rolled">Refilled ${backWord(new Date(resetMs))}</span>` : `<span class="when"></span>`;
    return `<div class="prow" data-meter="${esc(id)}">${name}${DRAW.meter(null)}<span class="pct">${DRAW.dash}</span>${when}</div>`;
  }
  const full = pct >= 100, cls = [full ? "full" : "", plan[W.now] === false ? "stale" : ""].filter(Boolean).join(" ");
  const span = ((plan[W.len] || W.secs) ?? 0) * 1000;
  // the ring is drawn to the hundredth of its window, which is as fine as its arc can show; a finer one would redraw the
  // card on every read for a change no eye could see. Its time says what it is, in Sessions' and Usage's words: when the
  // window refills, and at the limit when the member is back.
  const ring = left != null && span ? `${DRAW.ring(Math.round((left / span) * 100) / 100)}<span>${full ? "Back" : "Resets"} ${backWord(new Date(resetMs))}</span>` : "";
  return `<div class="prow" data-meter="${esc(id)}">${name}${DRAW.meter(Math.min(pct, 100), cls)}` +
    `<span class="pct${full ? " limit" : ""}">${pct}%<span class="used">used</span></span><span class="when">${ring}</span></div>`;
}
/** EACH PLAN ACCOUNT A MEMBER MOVED REQUESTS ON, by the account's name, with the account's own windows. They stand apart
 *  from the member's figures, because splice never says what share of a window is one member's. */
function acctsHtml(tm, slotId) {
  const uses = econRow(tm, slotId)?.plan_windows || [];
  const logins = [...new Map(uses.filter((u) => u.account).map((u) => [`${u.head}\n${u.account}`, u])).values()];
  return logins.map((u) => {
    const plan = planOf(u.head, u.account);
    return `<div class="pacc" style="--c:${headColor(u.head)}"><em class="pacct">${esc(accountName(u.head, u.account))}</em>` +
      `${Object.keys(PLAN_WINDOWS).map((win) => windowRow(plan, win, `${tm.id}/${slotId}/${u.head}/${u.account}/${win}`)).join("")}</div>`;
  }).join("");
}
/** What a member moved on its plan over each span splice counts, summed over the accounts it moved it on. The daemon
 *  sends a span only where it counted a request in it, and the 5 hours lie inside the 7 days, so a member with a 7-day
 *  count and no 5-hour one moved none in the last 5 hours. */
function windowsOf(tm, slotId) {
  const by = new Map();
  for (const u of econRow(tm, slotId)?.plan_windows || []) {
    const w = by.get(u.window) || { turns: 0, tokens: 0 };
    w.turns += u.turns || 0;
    w.tokens += u.tokens || 0;
    by.set(u.window, w);
  }
  if (by.has("7d") && !by.has("5h")) by.set("5h", { turns: 0, tokens: 0 });
  return by;
}
/** THE SCALES A TEAM'S FIGURES ARE DRAWN ON: its largest requests and tokens over every member and every span shown, so
 *  a bar on one card compares by eye with the same column on its neighbours. [priced] is whether any member's requests
 *  were priced, which is when the cards carry a price column at all. */
function scalesOf(tm) {
  const sc = { turns: 0, tokens: 0, priced: false };
  for (const s of [...tm.slots, ...leftOf(tm)]) {
    const u = useOf(tm, s.id);
    if (!u) continue;
    sc.turns = Math.max(sc.turns, u.turns);
    sc.tokens = Math.max(sc.tokens, u.tokens);
    if (u.priced > 0) sc.priced = true;
    for (const w of windowsOf(tm, s.id).values()) { sc.turns = Math.max(sc.turns, w.turns); sc.tokens = Math.max(sc.tokens, w.tokens); }
  }
  return sc;
}
// the spans a member's own plan figures are counted over, the longer first, as the rows under its total read
const MINE = [["7d", "7 days"], ["5h", "5 hours"]];
/** The kit's bar for a table cell (DRAW.vbar), in the plan's own tone: what a member moved on its plan. */
const planBar = (w) => `<span class="vbar plan" aria-hidden="true"><b style="width:${Math.round(Math.max(0, Math.min(100, w)) * 10) / 10}%"></b></span>`;
/** A MEMBER'S OWN FIGURES AS ONE SMALL TABLE, so each unit is said once, in its column's head, and every count is drawn
 *  as a bar on the team's scale for its column before it is written. The first row, Total, is the member's own since
 *  joining, the span the team's band names once: its requests as the payment split with the count that opens exactly them, its
 *  tokens, and on a team where any request was priced, its price. "7 days" and "5 hours" are what it moved on its plan
 *  over each span, in the plan's own tone, and a plan prices nothing. Every figure of a column ends on one right edge. */
function figsHtml(tm, slot, u, sc, grow) {
  const wins = windowsOf(tm, slot.id);
  const pct = (n, max) => (max > 0 ? (n / max) * 100 : 0);
  const door = slotDoor(tm, slot);
  const count = door ? `<a class="rq" href="${door}">${DRAW.n(u.turns)}${DRAW.go}</a>` : DRAW.n(u.turns);
  // on a team where any request was priced, a member with none priced, or one whose price splice could not read, keeps
  // the dash in that column, so the column never holds a blank where a figure belongs
  const usd = !sc.priced ? "" : `<b class="v">${u.priced > 0 && u.usd != null ? esc(dollars(u.usd)) : DRAW.dash}</b>`;
  const head = `<div class="mtr mth"><span></span><span class="mh">Requests</span><span class="mh">Tokens</span>${sc.priced ? `<span class="mh pr">Priced</span>` : ""}</div>`;
  const all = `<div class="mtr"><span class="ms">Total</span>${splitBar(u, sc.turns)}<b class="v rf">${count}</b>` +
    `${DRAW.vbar(pct(u.tokens, sc.tokens))}<b class="v">${esc(DRAW.big(u.tokens))}</b>${usd}</div>`;
  const spans = MINE.filter(([k]) => wins.has(k)).map(([k, word]) => {
    const w = wins.get(k);
    return `<div class="mtr plan"><span class="ms">${word}</span>${planBar(pct(w.turns, sc.turns))}<b class="v rf">${DRAW.n(w.turns)}</b>` +
      `${planBar(pct(w.tokens, sc.tokens))}<b class="v">${esc(DRAW.big(w.tokens))}</b>${sc.priced ? "<span></span>" : ""}</div>`;
  }).join("");
  return `<div class="mtab${sc.priced ? " p3" : ""}${grow ? " grow" : ""}">${head}${all}${spans}</div>`;
}

/** A MEMBER'S OWN FIGURES: its table, then the windows of each plan account it used. The scope, since joining, is said
 *  once on the team's band. A seat with no session and no requests has none to draw; a member with none since joining
 *  and none on its plan says so in one line. */
function useHtml(tm, slot, sc, { vacant = false } = {}) {
  const u = useOf(tm, slot.id);
  if (!u || (vacant && !u.turns)) return "";
  if (!u.turns && !windowsOf(tm, slot.id).size) return `<div class="use"><p class="mnone">0 requests</p></div>`;
  return `<div class="use">${figsHtml(tm, slot, u, sc, growing(tm))}${acctsHtml(tm, slot.id)}</div>`;
}

// ---------- the pick: Sessions' projects, one pick across the pages (kit PICK) ----------
// A team is where its repo resolves; with no repo, where its lead works. A pick shows every team with a member in the
// project, so a team across two projects appears under each of them wherever its people are (PROJECTS.md).
const leadOf = (tm) => tm.slots.find((s) => s.lead) || tm.slots[0] || null;
const homeOf = (tm) => (tm.project ? tm : leadOf(tm)?.project ? leadOf(tm) : null);
const teamKey = (tm) => rowProjKey(homeOf(tm));
const groupsNow = () => projectGroups(Object.values(state.sessions), state.live, state.heads);
const picked = () => { const g = groupsNow(); return g.length >= 2 ? (ui.project = reconcilePick(ui.project, g)) : null; };
// a tile counts the teams its pick would show, archived ones left out as the tabs leave them
const teamCount = () => {
  const on = state.teams.filter((t) => !t.archived);
  return { of: (k) => on.filter((t) => inPick(t, k)).length, all: on.length, unit: "team" };
};
const inPick = (tm, pick = picked()) => pick == null || teamKey(tm) === pick || tm.slots.some((s) => rowProjKey(s) === pick);

// ---------- the tabs: one per team, its members' colours on it ----------
function tabHtml(tm) {
  const needs = tm.slots.some((s) => sessOf(s)?.waiting_for);
  // a member is filled only while its session runs: one that ended draws hollow, as one never started does (marlin p227)
  const running = (x) => { const s = sessOf(x); return Boolean(s) && s.availability !== "gone"; };
  const blots = tm.slots.map((s) => `<i style="--c:${headColor(s.head)}"${running(s) ? "" : ' class="open"'}></i>`).join("");
  const members = tm.slots.length, live = tm.slots.filter(running).length;
  // the dots' own count, and nothing about the dots: "a hollow dot is not running" narrated the drawing it sat on
  const dots = `${live} of ${members} running`;
  return `<button class="tab${needs ? " needs" : ""}" data-act="team" data-t="${esc(tm.id)}"${needs ? ' title="A member needs you"' : ""} aria-current="${ui.team === tm.id}">` +
    `<span class="tname">${esc(tm.name)}</span><span class="blots" role="img" title="${esc(dots)}" aria-label="${esc(dots)}">${blots}</span></button>`;
}
/** Every project's teams under its muted name, in Sessions' group order; a project Sessions has no live session in
 *  follows by name, and No project comes last. One cluster, or a project picked, draws the tabs alone. */
function clustersHtml(on) {
  const order = groupsNow().map((g) => g.key);
  const m = new Map();
  for (const tm of on) { const k = teamKey(tm); if (!m.has(k)) m.set(k, []); m.get(k).push(tm); }
  if (picked() != null || m.size < 2) return on.map(tabHtml).join("");
  const name = (k) => rowProjName(homeOf(m.get(k)[0]));
  const at = (k) => { const i = order.indexOf(k); return i < 0 ? order.length : i; };
  const keys = [...m.keys()].sort((a, b) => (a === NO_PROJECT_KEY) - (b === NO_PROJECT_KEY) || at(a) - at(b) || name(a).localeCompare(name(b)));
  return keys.map((k) => `<span class="tcluster" role="group" aria-label="${esc(name(k))}"><span class="tproj">${esc(name(k))}</span>${m.get(k).map(tabHtml).join("")}</span>`).join("");
}
/** THE PAGE'S TOOLBAR, one row: which team (the tabs in their projects, the archived fold, New team) on the left, and how
 *  to read it (the views and the day) at the row's right end, where the row had nothing; the views were a band of
 *  their own between the team's band and the room. */
function tabsHtml(tm) {
  const on = state.teams.filter((t) => !t.archived && inPick(t));
  const off = state.teams.filter((t) => t.archived && inPick(t));
  // the fold stands beside the tabs it extends, with its count on it, and opens them in place, its chevron turning: a
  // count a screen away from its own list is a count nobody connects to it (the catalog's principle 8)
  const fold = off.length ? `<button class="act quiet small fold" data-act="archived" aria-expanded="${ui.archived}">${DRAW.chev}${off.length} archived</button>` : "";
  const offTabs = ui.archived ? `<span class="rule" aria-hidden="true"></span>${off.map(tabHtml).join("")}` : "";
  // where the next team lands: the dashed tile the console uses for every add, so the act explains itself
  const newT = DRAW.addTile({ word: "New team", act: "new", cls: "tnew", pressed: ui.compose?.id === null });
  return `<div class="tbar"><nav class="tabs" aria-label="Teams">${clustersHtml(on)}${fold}${offTabs}${newT}</nav>${tm ? viewsHtml(tm) : ""}</div>`;
}

// ---------- a member: the slot's role on its command, its session's state as the lamp ----------
/** A MEMBER CARD, in four rows its neighbours share (teams.css .members), so the figures of one row of cards start
 *  level: its headline (lamp, name, state word, its acts), what it runs and where, what it is doing or asking, and its
 *  own figures, drawn on the team's scales [sc]. */
function memberHtml(tm, slot, sc = scalesOf(tm)) {
  const s = sessOf(slot);
  const key = `${tm.id}/${slot.id}`;
  const ro = tm.archived;
  // the tag says what the role does not: a role that already reads Lead carries none (fin, Oct 12)
  const lead = slot.lead && !/^lead$/i.test((slot.role || "").trim()) ? `<span class="lead">Lead</span>` : "";
  const command = commandOf(slot.head);
  const chip = DRAW.chip({ text: command, live: Boolean(s && liveNow(s)), cls: "hcmd" });
  // the model its session runs, which the sessions route carries; the slot's own only until the session says. It is an
  // object like its command, so it is a chip with the same provider edge, never a run of plain words.
  const model = s?.model ? modelLabel(slot.head, s.model) : modelLabel(slot.head, slot.model);
  if (!s || s.availability === "gone") return vacantHtml(tm, slot, { key, ro, lead, chip, model, sc, ended: Boolean(s) });
  state.stalls[slot.id] = null;
  const L = stallLook(slot, s) ?? stateOf(s);
  // THE FIVE-SECOND READ IS VISIBLE: a member whose state word is not the one it carried on the last read pulses
  // once in its own colour, so a change that lands while he is reading something else is not silent.
  const wasWord = ui.words.get(key);
  const just = wasWord !== undefined && wasWord !== L.word ? " just" : "";
  ui.words.set(key, L.word);
  const stop = !liveNow(s) || ro || ui.armedStop.has(key) ? ""
    : ui.stopping.has(key) ? `<button class="act quiet small stopping" disabled>${ICON.wait}Stopping</button>`
    : `<button class="act quiet small" data-act="stop" data-s="${esc(slot.id)}">Stop</button>`;
  // a refused Stop leaves the card where it is, Stop back in its place, and the refusal beside it
  const refused = ui.failed.get(`stop:${key}`) || ui.failed.get(`end:${key}`);
  const refusal = refused ? `<span class="refusal" role="status">${esc(refused)}</span>` : "";
  // End closes the session in any state, beside Stop, which only halts a turn and keeps the agent (fin, Marlin p218)
  const ending = ui.ending.get(key);
  const end = ro || ui.armedEnd.has(key) ? ""
    : ending ? `<button class="act quiet small stopping" disabled>${ICON.wait}Ending${secsSince(ending) ? ` · ${secsSince(ending)} s` : ""}</button>`
    : `<button class="act quiet small" data-act="end" data-s="${esc(slot.id)}">End</button>`;
  const now = L.cls === "ended" ? null : lastActivity(slot.id);
  const body = L.cls === "needs" && s.waiting_for ? askHtml(s, slot.id, ro, key) : now ? `<p class="now">${activityHtml(now)}</p>` : "";
  // What the member is on: the name its session carries, else the folder it is in, as the drawing reads it.
  const folder = s.repo?.root || s.cwd || "";
  const where = s.name || folder.split("/").pop();
  const heard = s.updated_at || s.status_updated_at;
  const also = alsoOn(tm, slot.session).join(", ");
  const opens = slot.session ? ` go" tabindex="0" data-go="${esc(slot.session)}` : "";
  const edit = editAct(tm, slot, key);
  const acts = refusal || stop || end || edit ? `<span class="acts">${refusal}${stop}${end}${edit}</span>` : "";
  // THE STATE STANDS APART FROM THE DATA: its word rides the headline beside the role, under its own lamp, and the
  // mono run below carries only what the member runs and where. Changed in place, that run is the form that changes it.
  return `<article class="card m ${L.cls}${just}${opens}" style="--c:${headColor(slot.head)}" data-key="m:${esc(slot.id)}" aria-label="${esc(nameOf(tm, slot))}">` +
    `<span class="lamp ${L.cls}" aria-hidden="true">${L.lamp}</span>` +
    `<div class="top"><span class="role">${esc(nameOf(tm, slot))}</span>${lead}<span class="word ${L.cls}">${L.word}</span>${L.detail ?? L.paneDetail ?? ""}${acts}</div>` +
    (ui.change?.key === key ? changeHtml(ui.change) : `<div class="meta">${chip}${model ? DRAW.chip({ text: model, cls: "mdl" }) : ""}` +
    `${where || also || heard ? `<div class="seen">${where ? `<span class="where">${esc(where)}</span>` : ""}${also ? `<span class="also">Also on ${esc(also)}</span>` : ""}` +
    `${heard ? `<span class="when">${backWord(new Date(heard))}</span>` : ""}</div>` : ""}</div>`) +
    `<div class="body">${body}${liveNow(s) && !ro ? armedStopHtml(key, slot.id, now) : ""}${armedEndHtml(key, slot.id)}${checksHtml(tm, slot.id)}</div>` +
    `${useHtml(tm, slot, sc)}</article>`;
}

const secsSince = (t) => Math.floor((Date.now() - t) / 1000);
/** Stop armed, End's pattern (Marlin, Oct 13): the first press names the turn it would end, by the member's last tool
 *  call, and only the second press stops it; Keep running, Escape or a tap elsewhere disarms it. */
function armedStopHtml(key, slotId, now) {
  if (!ui.armedStop.has(key)) return "";
  const turn = now ? `Ends the turn on ${activityHtml(now)}` : "Ends the running turn";
  return `<div class="armed end"><span class="word working">${ICON.wait}${turn}</span>` +
    `<button class="act danger small" data-act="stop-now" data-s="${esc(slotId)}">Stop</button>` +
    `<button class="act quiet small" data-act="stop-keep" data-s="${esc(slotId)}">Keep running</button></div>`;
}
/** End armed: what ending would lose, the Working word or his unsent words verbatim and muted, then the danger End and
 *  Restart's own no, Keep running. Nothing of ours is said: his words after Unsent say what goes (fin, Marlin p218). */
function armedEndHtml(key, slotId) {
  const armed = ui.armedEnd.get(key);
  if (!armed) return "";
  const lost = armed.draft != null ? `<span class="said">Unsent</span><span class="unsent">${esc(armed.draft)}</span>`
    : `<span class="word working">${ICON.wait}Working</span>`;
  return `<div class="armed end">${lost}<button class="act danger small" data-act="end-now" data-s="${esc(slotId)}">End</button>` +
    `<button class="act quiet small" data-act="end-keep" data-s="${esc(slotId)}">Keep running</button></div>`;
}

// ---------- a member changed in place: its role, command, model and account, on its own card ----------
/** The card's own Edit, at the end of its headline beside Stop and End: what the member runs is changed where it is
 *  read, and the team's form is for the team. An archived team is read-only, and an open form needs no second Edit. */
const editAct = (tm, slot, key) => (tm.archived || ui.change?.key === key ? ""
  : `<button class="act quiet small" data-act="change" data-s="${esc(slot.id)}">${GLYPH.Edit}Edit</button>`);
/** THE FORM WHERE THE CHIPS STOOD: the role as a field, then the command, the model and the account as the team form's
 *  own pickers, the account by its name. Save sends the team as it stands with this member as the form sets it; a refusal
 *  stays here as its word, and Cancel or Escape puts the chips back. */
function changeHtml(ch) {
  const btn = (what, val, label, word) => `<button class="pickbtn${val ? "" : " unset"}" data-act="cpick" data-what="${what}" aria-expanded="${ch.pick === what}">` +
    `${val ? label : word}${GLYPH.caret}</button>`;
  const head = btn("head", ch.head, `<span class="blot" style="--c:${headColor(ch.head)}"></span>${esc(commandOf(ch.head))}`, "Command");
  const model = btn("model", ch.model, esc(modelLabel(ch.head, ch.model)), "Model");
  const account = (state.accounts[ch.head] || []).length ? btn("account", ch.account, esc(accountName(ch.head, ch.account || "")), "Account") : "";
  const why = ch.error ? `<span class="refusal" role="status">${esc(ch.error)}</span>` : "";
  const save = ch.saving ? `<button class="act small stopping" disabled>${ICON.wait}Saving</button>`
    : `<button class="act small" data-act="change-save"${ch.head ? "" : " disabled"}>Save</button>`;
  return `<div class="meta mchange"><input class="field role-in" value="${esc(ch.role)}" placeholder="Role" aria-label="Role" autocomplete="off" spellcheck="false">` +
    `${head}${model}${account}${changeMenu(ch)}<div class="macts">${why}<button class="act quiet small" data-act="change-cancel">Cancel</button>${save}</div></div>`;
}
/** The open picker's choices, as the team form lists them: each command with what it can do now, each model with the
 *  context it holds, each account by its name. */
function changeMenu(ch) {
  if (ch.pick === "head") {
    return `<div class="menu pick-menu" data-what="head">${state.heads.map((h) =>
      `<button data-act="cset" data-what="head" data-v="${esc(h.key)}"><span class="blot" style="--c:${headColor(h.key)}"></span>${esc(h.command)}${headMark(h)}</button>`).join("")}</div>`;
  }
  if (ch.pick === "model") {
    const rows = state.models[ch.head] || [];
    if (!rows.length) return `<div class="menu pick-menu" data-what="model"><p class="empty">This command declares no models.</p></div>`;
    return `<div class="menu pick-menu" data-what="model">${rows.map((m) =>
      `<button data-act="cset" data-what="model" data-v="${esc(m.id)}">${esc(m.label)}` +
      `<small class="hmark">${[m.context ? `${DRAW.big(m.context)} context` : "", m.pinned ? "default" : ""].filter(Boolean).map(esc).join(" · ")}</small></button>`).join("")}</div>`;
  }
  if (ch.pick === "account") {
    return `<div class="menu pick-menu" data-what="account">${(state.accounts[ch.head] || []).map((a) =>
      `<button data-act="cset" data-what="account" data-v="${esc(a)}">${esc(accountName(ch.head, a))}</button>`).join("")}</div>`;
  }
  return "";
}
/** Saves a member changed in place: the team as it stands, with this member's role, command, model and account as the
 *  form sets them, through the same route the team's form saves by. */
async function saveChange(tm) {
  const ch = ui.change;
  if (!tm || !ch || ch.saving) return;
  Object.assign(ch, { saving: true, error: null, pick: null });
  render();
  const slots = tm.slots.map((s) => {
    const it = s.id === ch.slot ? { ...s, role: ch.role, head: ch.head, model: ch.model, account: ch.account } : s;
    return { id: it.id, role: String(it.role || "").trim() || it.id, head: it.head, model: it.model || undefined, account: it.account || undefined,
      lead: Boolean(it.lead), bind_by_rule: it.bind_by_rule || undefined, instructions: it.instructions?.trim() || undefined };
  });
  const answer = await API.put(`/api/teams/${tm.id}`, { id: tm.id, name: tm.name, goal: tm.goal || "", repo: tm.repo || "", slots });
  ch.saving = false;
  if (!answer.ok) { ch.error = refusedAs(answer, "Not saved", `change ${ch.key}`); render(); return; }
  ui.change = null;
  await read();
}

// ---------- what a waiting member is asking, and answering it where it waits ----------
// The question is a READ: /api/sessions carries each session's last transcript line, and an AskUserQuestion call
// brings its question and option labels with it (`last.asks`). Answering is the key the person would press — the
// option's own number, 1 to 9 as the screen lists them — sent to the member's pane by POST .../answer.
// A PERMISSION PROMPT carries no labels splice may spell: its choices belong to the client's version, and only the
// screen says which this one offers. So the card shows what is being asked and leaves the answer to the terminal.
// (This whole block moves to kit.js with Sessions, which draws the same ask on its own cards.)
function askHtml(session, slotId, readOnly, key) {
  const asked = (session.last?.asks || [])[0];
  const held = ui.answered.get(key);
  const going = held && Date.now() - held.at < ANSWER_HOLD_MS ? held : null;
  const busy = going ? " disabled" : "";
  // the pressed answer keeps its words with the ring; the rest wait disabled
  // NEITHER ANSWER IS LIT: the key light marks one thing per view and never a choice splice is making for him, so the
  // options stand equal and he picks (the client itself points at no option either).
  const pick = (choice, label) => going?.choice === choice
    ? `<button class="act answering" disabled>${ICON.wait}${esc(label)}</button>`
    : `<button class="act" data-act="answer" data-s="${esc(slotId)}" data-i="${choice}"${busy}>${esc(label)}</button>`;
  const failed = ui.failed.get(key);
  const why = failed ? `<p class="why limit">${esc(failed)}</p>` : "";
  // reading off: the question and its choices are the conversation, so the card says only where to answer it, with a
  // quiet door to the switch (Marlin 5:12 AM CT; fin; hitstop b5a39e3)
  // where an answer is given when it cannot be given here: the member's own terminal, with a door to its session
  const elsewhere = (door) => `<p class="why">Answer in its terminal${door ? ` · <a class="door" href="${door}">${esc(door.startsWith("settings") ? "Reading off" : "Its session")}${ICON.door}</a>` : ""}</p>`;
  if (!state.reading) return `<div class="ask">${elsewhere("settings.html#data")}${why}</div>`;
  const door = session.session_id ? `sessions.html#${encodeURIComponent(session.session_id)}` : null;
  if (!asked) {
    const offer = going?.offer ?? state.screens[slotId];
    const what = prompted(session.last, offer);
    // The choices the client itself drew, each pressing the digit beside it on the screen. When splice could
    // not read one, the card says where the answer is given rather than offering an act it cannot carry —
    // the second half of the sentence the answer route returns for a pane it cannot drive.
    if (!offer || readOnly) return `<div class="ask">${what}${elsewhere(door)}${why}</div>`;
    const acts = offer.choices.map((c) => pick(c.choice, keyless(c.label))).join("");
    return `<div class="ask">${what}<div class="answers">${acts}</div>${why}</div>`;
  }
  const buttons = readOnly || asked.multi ? "" :
    `<div class="answers">${asked.options.map((o, i) => pick(i + 1, o)).join("")}</div>`;
  // A MULTI-CHOICE QUESTION IS NOT A DEAD END: splice sends one keypress, so it cannot pick several here. Its options
  // are shown as the question's own words, and the way to answer is named with a door to the session
  // (sessions.css .scope).
  const chips = asked.multi
    ? `<div class="answers">${asked.options.map((o) => `<span class="scope">${esc(o)}</span>`).join("")}</div>${elsewhere(door)}`
    : "";
  return `<div class="ask"><p class="q">${esc(asked.question)}</p>${buttons}${chips}${why}</div>`;
}

/** An answer as the client words it, without the terminal's key for it at its end ("(esc)", "(shift+tab)"): here the
 *  button is the press, and the key belongs to the terminal. */
const keyless = (label) => String(label).replace(/\s*\((?:esc|enter|tab|shift\s*\+\s*tab|ctrl\s*\+\s*\w+)\)\s*$/i, "");

/** What is being asked, in the client's own words when the screen could be read: its first line is the call, as
 *  Bash(npm test) — the person approving a command has to see the command — and what follows is what it is for.
 *  Without a screen it is the transcript's own line, which names the tool and what it is for but never a command. */
function prompted(call, offer) {
  const said = (offer?.asked || "").split("\n").filter((line) => line.trim());
  if (!said.length) {
    return call?.tool ? `<p class="cmd"><span class="tname">${esc(call.tool)}</span>${esc(call.text || "")}</p>` : "";
  }
  const why = said.slice(1).join(" ") || call?.text || "";
  return `<p class="cmd">${esc(said[0])}</p>${why ? `<p class="why">${esc(why)}</p>` : ""}`;
}

/** How many turns a session has run, beside its name in the "Add existing" menu: a name does not say which of two quiet
 *  sessions is the one meant, the count does (fin). The row carries `turns` only when it was measured, so an absent key
 *  draws nothing, never a zero or a dash: a count nobody watched must not read as "ran nothing". A real 0 is drawn,
 *  because "0 turns" is what tells a reader this is not the session they meant. */
const turnsOf = (session) => {
  if (!Number.isInteger(session.turns)) return "";
  // `turns_partial` is the server's own comparison of where counting began against where the session began: true
  // means the count is a floor (and an unknown session start reads true), so it is drawn "12+". The client never
  // compares the two timestamps itself, because `started_at` carries no unit on the wire.
  const floor = session.turns_partial === true ? "+" : "";
  return ` · ${session.turns}${floor} ${session.turns === 1 && !floor ? "request" : "requests"}`;
};

/** A slot with no session: start one on its command, or hand it one of its command's that already runs. */
function vacantHtml(tm, slot, { key, ro, lead, chip, model, sc, ended = false }) {
  const failed = ui.failed.get(key), unstarted = ui.unstarted.get(key), asked = state.pending[slot.id];
  // a slot whose session ended reads the word Sessions shows for it; one that never had one, No session (fin)
  let word = `<span class="word">${ended ? "Ended" : "No session"}</span>`;
  let acts = "";
  let why = "";
  let lamp = `<span class="lamp open" aria-hidden="true"></span>`;
  if (ui.starting.has(key)) {
    // counting up, as Testing does on Settings, so a start that waits reads as waiting and not as stuck (fin)
    const secs = Math.floor((Date.now() - (ui.startedAt.get(key) || Date.now())) / 1000);
    word = `<span class="word starting">${ICON.wait}Starting${secs ? ` · ${secs} s` : ""}</span>`;
  } else if (asked && !ro) {
    // stopped at Claude Code's own question before it came up: his to answer, in the client's words, in the family of
    // a member's ask (Marlin, Oct 12; hitstop da3d8b2)
    lamp = `<span class="lamp needs" aria-hidden="true">${ICON.dialog}</span>`;
    word = `<span class="word needs">Needs you</span>`;
    why = trustHtml(slot.id, asked, ui.trusting.get(key));
  } else if (unstarted) {
    // one word for a member that did not come up, whoever ended it; only the timeout has a screen he has not seen, behind
    // a door that stays shut until he opens it (fin; hitstop da3d8b2)
    word = `<span class="word limit">Not started</span>`;
    // why it did not come up, on the card, as a given-up row says why (Marlin p223)
    why = (unstarted.why ? `<p class="why limit">${esc(unstarted.why)}</p>` : "")
      + (unstarted.path ? `<div class="ask trust"><p class="cmd">${folderBreaks(unstarted.path)}</p></div>` : "");
    acts = ro ? "" : `<button class="act" data-act="start" data-s="${esc(slot.id)}">Try again</button>`;
    if (unstarted.screen) {
      const open = ui.screenOpen.has(key);
      acts += `<button class="act quiet small" data-act="screen" data-s="${esc(slot.id)}" aria-expanded="${open}">${DRAW.chev}Last screen</button>` +
        (open ? `<pre class="screen unfold">${esc(unstarted.screen)}</pre>` : "");
    }
  } else if (failed) {
    word = `<span class="word limit">Not started</span>`;
    why = `<p class="why limit">${esc(failed)}</p>`;
    acts = ro ? "" : `<button class="act" data-act="start" data-s="${esc(slot.id)}">Try again</button>`;
  } else if (!ro) {
    const bindFailed = ui.failed.get(`bind:${key}`);
    if (bindFailed) why = `<p class="why limit">${esc(bindFailed)}</p>`;
    const free = Object.values(state.sessions).filter((x) =>
      x.head === slot.head && x.availability !== "gone" && !state.teams.some((t) => !t.archived && t.slots.some((y) => y.session === x.session_id)));
    // Start new is not lit: the key light marks ONE thing per view, and on a board of four open seats it would mark
    // four (the catalog's principle 3). It stays the plain act it is, on the card it acts on.
    acts = `<button class="act" data-act="start" data-s="${esc(slot.id)}">Start new</button>` +
      (free.length ? `<button class="act" data-act="use" data-s="${esc(slot.id)}" aria-expanded="${ui.menu === key}">Add existing${GLYPH.caret}</button>` : "");
    if (ui.menu === key) {
      acts += `<div class="menu use">${free.map((x) =>
        `<button data-act="bind" data-s="${esc(slot.id)}" data-to="${esc(x.session_id)}"><span>${esc(sessWord(x))}</span>` +
        `<span class="mmeta">${esc(x.model ? modelLabel(x.head, x.model) : commandOf(x.head))}${turnsOf(x)}</span></button>`).join("")}</div>`;
    }
  }
  const account = slot.account ? `<span class="acct">${esc(accountName(slot.head, slot.account))}</span>` : "";
  const edit = editAct(tm, slot, key);
  return `<article class="card m ${asked && !ro && !ui.starting.has(key) ? "needs trust" : "vacant"}" style="--c:${headColor(slot.head)}" data-key="m:${esc(slot.id)}" aria-label="${esc(nameOf(tm, slot))}">` +
    lamp +
    `<div class="top"><span class="role">${esc(nameOf(tm, slot))}</span>${lead}${word}${edit ? `<span class="acts">${edit}</span>` : ""}</div>` +
    (ui.change?.key === key ? changeHtml(ui.change) : `<div class="meta">${chip}${model ? DRAW.chip({ text: model, cls: "mdl" }) : ""}${account}</div>`) +
    `<div class="body">${slot.bind_by_rule ? ruleMark(tm.repo, slot.head) : ""}${why}${acts ? `<div class="slot-acts">${acts}</div>` : ""}</div>` +
    `${useHtml(tm, slot, sc, { vacant: true })}</article>`;
}

/** A folder as an object: its last [n] folders on a chip with the folder's mark, the whole path on hover. With no path it
 *  is the empty chip, dashed, that says what is missing. */
const folderWord = (path, n = 1) => String(path || "").trim().split("/").filter(Boolean).slice(-n).join("/");
function folderChip(path, n = 1) {
  const word = folderWord(path, n);
  return word ? `<span class="chip where" title="${esc(String(path).trim())}">${GLYPH.folder}<span>${esc(word)}</span></span>`
    : `<span class="chip where unset">${GLYPH.folder}<span>No repo</span></span>`;
}
/** WHAT JOINING BY RULE TAKES, DRAWN BY ITS PARTS, on the member's card and on its row of the form: a session on this
 *  command, in the team's folder, joins this member only while it has none of its own running. */
const ruleMark = (repo, head) => `<div class="rulemark"><h4 class="kicker">Joins by rule</h4>` +
  `${DRAW.chip({ text: commandOf(head) || "Any command", cls: "hcmd" })}<span class="said">in</span>${folderChip(repo)}` +
  `<span class="said">only while it has none running</span></div>`;
/** A NAME HE CAN MATCH TO A TERMINAL, for a session that carries none: the folder it runs in, else its command with
 *  the id fragment last. Two rows that differ only by eight hex characters name nothing. */
const sessWord = (x) => x.name || x.repo?.root?.split("/").filter(Boolean).pop() || x.cwd?.split("/").filter(Boolean).pop() ||
  `${commandOf(x.head) || "session"} ${x.session_id.slice(0, 8)}`;
// a head key no configured command has (one removed from config) names nothing; the sessions route's own "unknown head" still prints
const commandOf = (head) => state.heads.find((h) => h.key === head)?.command || (head === "unknown head" ? head : "");
const modelLabel = (head, id) => {
  const rows = state.models[head] || [];
  // a session reports its model as the command's prefix, two dashes, then the model's id: the label is under the id
  const bare = id?.includes("--") ? id.slice(id.indexOf("--") + 2) : id;
  // an id this command does not declare reads as Sessions names it (kit modelName), never as the raw id (marlin, Oct 13)
  return (id ? rows.find((m) => m.id === id || m.id === bare)?.label || modelName(state.modelLabel, bare) : rows[0]?.label) || "";
};

// ---------- what a member did: the tool's mark and its object, never splice's sentence ----------
const activityRows = () => (state.activity?.entries || []);
// the card's line is TODAY's latest tool work, whatever day the chat is showing (readTeam keeps today's own read)
const todayRows = () => (state.today?.entries || []);
const lastActivity = (slotId) => todayRows().filter((a) => a.slot === slotId && a.tool).at(-1) || null;
const activityHtml = (a) => `${GLYPH[a.tool] || GLYPH.tool}<span class="obj">${esc(a.object || a.tool)}</span>`;

// ---------- the room: members, their messages to each other, what each did ----------
// A day panel the history no longer holds says so in one word; the daemon's own sentence for why rides the word's hover.
const KEPT_WORD = { not_kept: "Not kept", deleted: "Deleted", off: "Off" };
function keptHtml(panel) {
  if (!panel) return "";
  if (panel.error) return `<p class="empty limit">${esc(panel.error)}</p>`;
  if (KEPT_WORD[panel.state]) return `<p class="empty"${panel.reason ? ` title="${esc(panel.reason)}"` : ""}>${KEPT_WORD[panel.state]}</p>`;
  if (panel.state === "partially_kept") return `<p class="cut"><span>Kept from ${hhmm(panel.oldest_kept_epoch_millis)}</span></p>`;
  return "";
}
const gone = (panel) => Boolean(panel?.error) || Boolean(KEPT_WORD[panel?.state]);

// THE CHAT RUNS AS TALL AS THE COLUMN BESIDE IT, AND NO TALLER: it holds the day's latest messages that fit there, and
// "N earlier" over them opens the rest in place (fitChat below). Narrow, where it stands under the members, it holds the
// latest eight. It never scrolls inside itself and never jumps back on a re-read.
const CHAT_LATEST = 8, CHAT_MIN = 3, FEED_LATEST = 12;
const msgId = (m) => `${m.at}|${m.from_slot || m.from || ""}|${m.to_slot || m.to || ""}`;
function chatHtml(tm, key) {
  const panel = state.chat;
  if (!panel) return GHOST.lines([22, 16, 19, 12]);
  if (gone(panel)) return keptHtml(panel);
  const msgs = panel.messages || [];
  if (!msgs.length) return `<p class="empty">No messages</p>`;
  // A MESSAGE THAT LANDS AFTER THE DAY WAS FIRST DRAWN RISES IN ONCE: the ones there on the first draw simply stand
  const now = Date.now(), first = !ui.firstSeen.has(key), seen = ui.firstSeen.get(key) || new Map();
  ui.firstSeen.set(key, seen);
  for (const m of msgs) if (!seen.has(msgId(m))) seen.set(msgId(m), first ? -Infinity : now);
  // A message whose text is not in its sender's transcript is drawn as who and when, with no box (marlin, Oct 13).
  // splice keeps no message text of its own: it reads the sender's transcript.
  const bare = msgs.filter((m) => !m.text?.trim()).length;
  // THE SHARE IS DRAWN, NOT SAID: how much of the day's talk its senders' transcripts still hold, as the one bar it is,
  // over the messages it is the share of. "N of M not in the transcript" was that share written out.
  const share = bare ? `<div class="kshare">${DRAW.stack([{ n: msgs.length - bare, word: "Kept", cls: "kept" },
    { n: bare, word: "No text", cls: "notext" }], msgs.length, { small: true })}</div>` : "";
  const open = ui.chatOpen.has(key);
  const more = `<button class="morepill" type="button" data-act="chat-more" data-k="${esc(key)}"${open ? "" : " hidden"}>${open ? "Fewer" : "<b>0</b> earlier"}</button>`;
  return keptHtml(panel) + share + more + msgs.map((m) => {
    const from = m.from_slot ? slotOf(tm, m.from_slot) : null;
    const arrive = now - seen.get(msgId(m)) < ARRIVE_MS ? " arrive" : "";
    // a message whose text its sender's transcript no longer holds keeps its own dashed shape, as the reference drew it
    const body = m.text?.trim() ? `<div class="body">${md(m.text)}</div>` : `<span class="notext">No text</span>`;
    return `<div class="tm${from ? "" : " outside"}${m.text?.trim() ? "" : " bare"}${arrive}" style="--c:${from ? headColor(from.head) : "var(--track-line)"}">` +
      `<div class="who">${party(tm, m.from_slot, m.from)}${GLYPH.msg}${party(tm, m.to_slot, m.to)}<time>${hhmm(m.at)}</time></div>${body}</div>`;
  }).join("");
}
/** The latest messages that fit beside the members, as many as fit, and at least three; the rest behind "N earlier".
 *  Measured on the page as drawn, as the kit fits a stacked bar's labels: the column's height is the layout's own. */
function fitChat() {
  const talk = root.querySelector(".room > .talk"), log = talk?.querySelector(":scope > .chat");
  const pill = log?.querySelector(":scope > .morepill");
  const msgs = log ? [...log.querySelectorAll(":scope > .tm")] : [];
  if (!pill || !msgs.length) return;
  talk.classList.remove("over");
  for (const m of msgs) m.hidden = false;
  if (ui.chatOpen.has(log.dataset.k)) { pill.hidden = false; return; }
  pill.hidden = true;
  const side = root.querySelector(".room > .members, .room > .mcol");
  let keep = Math.min(msgs.length, CHAT_LATEST);
  if (side && talk.getBoundingClientRect().left >= side.getBoundingClientRect().right) {
    const gap = parseFloat(getComputedStyle(log).rowGap) || 0;
    const room = talk.getBoundingClientRect().bottom - (parseFloat(getComputedStyle(talk).paddingBottom) || 0) - msgs[0].getBoundingClientRect().top;
    pill.hidden = false;
    const pillH = pill.getBoundingClientRect().height + gap;
    pill.hidden = true;
    const hs = msgs.map((m) => m.getBoundingClientRect().height);
    keep = 0;
    let used = 0;
    for (let i = msgs.length - 1; i >= 0; i--) {
      const next = used + (keep ? gap : 0) + hs[i];
      if (keep >= Math.min(CHAT_MIN, msgs.length) && next + (i > 0 ? pillH : 0) > room) break;
      used = next;
      keep++;
    }
    // three that do not fit beside a short column let the chat run past it rather than cut a message
    if (used + (keep < msgs.length ? pillH : 0) > room) talk.classList.add("over");
  }
  const hidden = msgs.length - keep;
  msgs.forEach((m, i) => { m.hidden = i < hidden; });
  if (hidden) { pill.hidden = false; pill.querySelector("b").textContent = DRAW.n(hidden); }
}

/** A member or a session outside the team, as the chat and the feed name it: its name in its command's colour. */
// A PARTY IS NEVER SHOWN AS AN ID (marlin): a session id outside the team takes the name the registry or history
// gives it, as Usage names an ended agent; a socket, or an id nothing names, reads Outside.
const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const pastNames = new Map(); // session id -> its name from history, null when history has none, undefined while asked
function pastName(id) {
  if (pastNames.has(id)) return pastNames.get(id);
  pastNames.set(id, undefined);
  API.get(`/api/sessions/history?limit=1&query=${encodeURIComponent(id)}`).then((res) => {
    const row = (res.body?.sessions || []).find((x) => x.session_id === id);
    pastNames.set(id, row?.name || row?.title || null);
    if (pastNames.get(id)) render();
  });
  return undefined;
}
function partyName(address) {
  const a = String(address || "");
  if (!a || a.startsWith("uds:")) return "";
  if (!SESSION_ID.test(a)) return a; // a name the sender typed
  const live = state.sessions[a];
  return live?.name || live?.title || pastName(a) || "";
}
function party(tm, slotId, address) {
  const s = slotId ? slotOf(tm, slotId) : null;
  if (s) return `<span class="party" style="--c:${headColor(s.head)}"><i></i>${esc(nameOf(tm, s))}</span>`;
  return `<span class="party out">${esc(partyName(address) || "Outside")}</span>`;
}

/** THE DAY'S TOOL WORK, NEWEST FIRST, flowed into columns so a row's object reads whole: the latest twelve, and the rest
 *  behind "N earlier", which opens them in place. */
function feedHtml(tm, key) {
  const panel = state.activity;
  if (!panel) return GHOST.lines([18, 21, 14]);
  if (gone(panel)) return keptHtml(panel);
  const rows = activityRows().filter((a) => a.slot).reverse();
  if (!rows.length) return `<p class="empty">No activity</p>`;
  const open = ui.feedOpen.has(key), shown = open ? rows : rows.slice(0, FEED_LATEST);
  const more = rows.length > FEED_LATEST ? DRAW.more(rows.length - FEED_LATEST, { word: "earlier", act: "feed-more", data: ` data-k="${esc(key)}"`, open, less: "Fewer" }) : "";
  return `<ol>${shown.map((a) => {
    const s = slotOf(tm, a.slot);
    return `<li style="--c:${s ? headColor(s.head) : "var(--track-line)"}"><time>${hhmm(a.at)}</time>${party(tm, a.slot, null)}` +
      `<span class="what">${activityHtml(a)}</span></li>`;
  }).join("")}</ol>${more}${keptHtml(panel)}`;
}
const panelHead = (word, aside = "") => `<div class="sechead"><h3 class="kicker">${esc(word)}</h3>${aside ? `<span class="sec-aside">${aside}</span>` : ""}</div>`;

// ---------- the views: one team read four ways (web-console §4.13 "Team board"; marlin, Oct 13) ----------
// Board is the walked default. By command racks the members under the command each runs on, By role under their role,
// the lead's first; both draw the day's messages between members under the groups. Timeline runs the day down the
// page, one column per member: what each did, and each message drawn from its sender's column to its receiver's at its
// time. The chat stays beside every view; the activity steps aside for the timeline, which holds every one of its rows.
// The choice is kept per team in this browser.
const VIEWS = [["board", "Board"], ["command", "By command"], ["role", "By role"], ["timeline", "Timeline"]];
const VIEW_STORE = "splice-teams-view:";
const viewChoice = new Map();
function viewOf(tm) {
  if (!viewChoice.has(tm.id)) {
    let v = null;
    try { v = localStorage.getItem(VIEW_STORE + tm.id); } catch { /* storage off: Board */ }
    viewChoice.set(tm.id, VIEWS.some(([k]) => k === v) ? v : "board");
  }
  return viewChoice.get(tm.id);
}
function setView(tm, v) {
  viewChoice.set(tm.id, v);
  try { localStorage.setItem(VIEW_STORE + tm.id, v); } catch { /* storage off: the choice holds until the page reloads */ }
}
// one segmented control, in neutral ink, as every other switch on the console reads (DRAW.switcher); the day the board
// stands on sits right beside it, so its name is printed once on the page and not over each panel
const viewsHtml = (tm) => `<div class="views">${DRAW.switcher(VIEWS, viewOf(tm), "view", "Views")}${dayHtml()}</div>`;
const dayHtml = () => `<div class="day"><button class="icon" data-act="day" data-d="1" aria-label="Earlier day"${ui.day >= 6 ? " disabled" : ""}>${GLYPH.back}</button>` +
  `<span class="dname">${dayName(ui.day)}</span>` +
  `<button class="icon" data-act="day" data-d="-1" aria-label="Later day"${ui.day === 0 ? " disabled" : ""}>${GLYPH.next}</button></div>`;

/** The day's messages from one member to another, oldest first: the hand-offs the views draw. */
const handoffs = () => (state.chat?.messages || []).filter((m) => m.from_slot && m.to_slot && m.from_slot !== m.to_slot);
/** A day panel still being read, or one the history no longer holds, in the words the chat uses for it. */
const panelWord = (panel) => (!panel ? GHOST.lines([16, 20]) : gone(panel) ? keptHtml(panel) : null);

/** The members in titled groups side by side: each group as wide as its cards in the Board's own columns (--n cards,
 *  teams.css .bays), its cards in a row under its name, so four members read across in one row whichever way they are
 *  grouped, and a group wraps under the others only when the row is full. */
function baysHtml(tm, groups) {
  const sc = scalesOf(tm);
  return `<div class="bays">${groups.map(({ name, color, slots }) => DRAW.group({ name, c: color, style: `--n:${slots.length}`,
    body: `<div class="members">${ordered(tm, slots).map((s) => memberHtml(tm, s, sc)).join("")}</div>` })).join("")}</div>`;
}
/** BETWEEN MEMBERS, DRAWN: one row per pair, sender to receiver, with each message a mark on one time rail every pair
 *  shares (from the hour of the day's first message to the hour after its last), the pair's count and its last time.
 *  The busiest pair is first, and a run of marks says when they talked before a time is read. */
function betweenHtml(tm) {
  const all = handoffs();
  const total = all.length ? `<span class="sec-aside">${plural(all.length, "message")}</span>` : "";
  let body = panelWord(state.chat);
  if (!body && !all.length) body = `<p class="empty">No messages between members</p>`;
  if (!body) {
    const H = 3_600_000, ats = all.map((m) => m.at);
    const t0 = Math.floor(Math.min(...ats) / H) * H, t1 = Math.max(t0 + H, Math.ceil(Math.max(...ats) / H) * H);
    const at = (t) => `${(((t - t0) / (t1 - t0)) * 100).toFixed(2)}%`;
    const pairs = new Map();
    for (const m of all) {
      const k = `${m.from_slot}\n${m.to_slot}`;
      if (!pairs.has(k)) pairs.set(k, { from: m.from_slot, to: m.to_slot, at: [] });
      pairs.get(k).at.push(m.at);
    }
    const rows = [...pairs.values()].sort((a, b) => b.at.length - a.at.length || Math.max(...b.at) - Math.max(...a.at)).map((p) => {
      const from = slotOf(tm, p.from);
      const marks = p.at.map((t) => `<i style="left:${at(t)}" title="${hhmm(t)}"></i>`).join("");
      return `<li style="--c:${headColor(from?.head)}"><span class="pair">${party(tm, p.from, null)}${GLYPH.msg}${party(tm, p.to, null)}</span>` +
        `<span class="ptrack" role="img" aria-label="${esc(plural(p.at.length, "message"))}">${marks}</span>` +
        `<b class="n">${DRAW.n(p.at.length)}</b><time>${hhmm(Math.max(...p.at))}</time></li>`;
    }).join("");
    const steps = Math.max(1, Math.min(6, Math.round((t1 - t0) / H)));
    const hours = Array.from({ length: steps + 1 }, (_, k) => { const t = t0 + (k * (t1 - t0)) / steps; return `<span style="left:${at(t)}">${hhmm(t)}</span>`; }).join("");
    body = `<ul class="pairs">${rows}<li class="hours" aria-hidden="true"><span></span><span class="ticks">${hours}</span></li></ul>`;
  }
  return DRAW.plate(DRAW.section("Between members", body, { aside: total }), "between");
}
function byCommandHtml(tm) {
  const heads = [...new Set(tm.slots.map((s) => s.head))];
  return `<div class="mcol">${baysHtml(tm, heads.map((h) => ({ name: commandOf(h) || h, color: headColor(h), slots: tm.slots.filter((s) => s.head === h) })))}${betweenHtml(tm)}</div>`;
}
function byRoleHtml(tm) {
  const key = (s) => (s.role || s.id).trim().toLowerCase();
  const order = [...new Set([...tm.slots.filter((s) => s.lead), ...tm.slots].map(key))];
  return `<div class="mcol">${baysHtml(tm, order.map((k) => {
    const slots = tm.slots.filter((s) => key(s) === k);
    return { name: slots[0].role || slots[0].id, color: null, slots };
  }))}${betweenHtml(tm)}</div>`;
}

// a day of activity samples is hundreds of rows; the timeline draws the latest this many and says how many it left out
const TIMELINE_ROWS = 400;
// A SILENCE IS DRAWN AS SPACE, IN PROPORTION TO ITS LENGTH: from two minutes of quiet on, a gap as tall as the log of its
// minutes (2 min is 1 rem, 5 min 2.3, half an hour 4.9, two hours 6.9), so a long quiet stands visibly taller than a
// short one without a word; past half an hour a dashed rule crosses it, and the clock on either side says the span.
const QUIET_MS = 2 * 60_000, BREAK_MS = 30 * 60_000;
const quietRem = (ms) => Math.log2(ms / 60_000);
/** WHAT A MESSAGE SAYS ON ITS ARROW: its first sentence, without the marks of a heading or a list, long enough to say
 *  something ("Pushed." alone runs on to the next one), and cut at a word with an ellipsis past 160 characters. The
 *  arrow itself cuts what its span cannot hold with an ellipsis, never mid-word without one. */
function firstLine(text) {
  const line = String(text || "").split("\n").map((l) => l.replace(/^[\s#>*\-\d.]+/, "").trim()).find(Boolean) || "";
  const said = line.match(/^.{24,}?[.!?](?=\s|$)/)?.[0] || line;
  return said.length <= 160 ? said : `${said.slice(0, 160).replace(/\s+\S*$/, "")}…`;
}
// a message whose text its sender's transcript no longer holds keeps the chat's own dashed mark
const NO_TEXT = `<span class="notext">No text</span>`;

function timelineHtml(tm) {
  const cols = tm.slots;
  const scale = scalesOf(tm).turns;
  const col = (id) => cols.findIndex((s) => s.id === id);
  const waiting = panelWord(state.chat) && panelWord(state.activity);
  const events = [
    ...(state.chat && !gone(state.chat) ? state.chat.messages || [] : []).filter((m) => col(m.from_slot) >= 0 || col(m.to_slot) >= 0).map((m) => ({ at: m.at, m })),
    ...activityRows().filter((a) => a.tool && col(a.slot) >= 0).map((a) => ({ at: a.at, a })),
  ].sort((x, y) => x.at - y.at);
  const shown = events.slice(-TIMELINE_ROWS);
  // a member with nothing on this day keeps its column, narrower, and says so at its head
  const busy = new Set(shown.flatMap((e) => (e.a ? [col(e.a.slot)] : [col(e.m.from_slot), col(e.m.to_slot)])));
  const tracks = cols.map((_, i) => (busy.has(i) || !shown.length ? "minmax(0, 1fr)" : "minmax(9rem, .4fr)")).join(" ");
  const heads = cols.map((s, i) => {
    const u = useOf(tm, s.id);
    return `<div class="tlhead" style="grid-column:${i + 2};--c:${headColor(s.head)}"><span class="party"><i></i>${esc(nameOf(tm, s))}</span>` +
      `${u ? reqHtml(tm, s, u, scale, { brief: true }) : ""}${shown.length && !busy.has(i) ? `<span class="none">Nothing this day</span>` : ""}</div>`;
  }).join("");
  let r = 1;
  const rows = shown.map((e, n) => {
    const gap = n ? e.at - shown[n - 1].at : 0;
    const quiet = gap >= QUIET_MS ? `<div class="tlq${gap >= BREAK_MS ? " brk" : ""}" style="grid-row:${++r};height:${quietRem(gap).toFixed(2)}rem" aria-hidden="true"></div>` : "";
    const row = `grid-row:${++r}`;
    const time = `<time style="${row}">${hhmm(e.at)}</time>`;
    if (e.a) {
      const i = col(e.a.slot);
      return `${quiet}${time}<div class="tlact" style="${row};grid-column:${i + 2};--c:${headColor(cols[i].head)}">${activityHtml(e.a)}</div>`;
    }
    const f = col(e.m.from_slot), t = col(e.m.to_slot), said = firstLine(e.m.text);
    if (f >= 0 && t >= 0) {
      const lo = Math.min(f, t), hi = Math.max(f, t);
      return `${quiet}${time}<div class="tlmsg${t < f ? " back" : ""}" style="${row};grid-column:${lo + 2} / ${hi + 3};--c:${headColor(cols[f].head)}"${said ? ` title="${esc(said)}"` : ""}>` +
        `${said ? `<span>${esc(said)}</span>` : NO_TEXT}</div>`;
    }
    const i = f >= 0 ? f : t;
    const other = partyName(f >= 0 ? e.m.to : e.m.from) || "Outside";
    return `${quiet}${time}<div class="tlnote" style="${row};grid-column:${i + 2};--c:${headColor(cols[i].head)}"${said ? ` title="${esc(said)}"` : ""}>${GLYPH.msg}<span>${f >= 0 ? "to" : "from"} ${esc(other)}</span>${said ? "" : NO_TEXT}</div>`;
  }).join("");
  const cut = events.length > shown.length ? `<p class="cut"><span>The latest ${shown.length} of ${events.length}</span></p>` : "";
  const empty = waiting || (!events.length ? `<p class="empty">Nothing on ${ui.day === 0 ? "this day yet" : "this day"}</p>` : "");
  return `<div class="mcol tlcol">${cut}<div class="tl" style="grid-template-columns:5.2rem ${tracks}">${heads}${rows}</div>${empty}</div>`;
}

/** The members as the chosen view draws them: on the Board, in the order that puts whoever needs him first. */
function membersHtml(tm) {
  const view = viewOf(tm);
  if (view === "command") return byCommandHtml(tm);
  if (view === "role") return byRoleHtml(tm);
  if (view === "timeline") return timelineHtml(tm);
  const sc = scalesOf(tm);
  // the Left strip stands under the cards' grid, not in it, so the cards' columns are only the ones cards fill
  const cards = `<div class="members" style="--n:${tm.slots.length}">${ordered(tm, tm.slots).map((s) => memberHtml(tm, s, sc)).join("")}</div>`;
  const left = leftHtml(tm, sc.turns);
  return left ? `<div class="mcol">${cards}${left}</div>` : cards;
}
/** The members removed from the team, each on one line with its command, when it left and what it did while it was on
 *  the team, on the team's own scale (spec §14). The row runs the members' whole width under the cards. Each keeps the
 *  one name it has across the page ("Builder (left)"), as the by-member bar and the chat name it. */
function leftHtml(tm, scale) {
  const went = leftOf(tm);
  if (!went.length) return "";
  return `<section class="leftm" aria-label="Left"><h3 class="kicker">Left</h3><ul>${went.map((s) => {
    const u = useOf(tm, s.id);
    return `<li style="--c:${headColor(s.head)}"><span class="party"><i></i>${esc(nameOf(tm, s))}</span>` +
      `${DRAW.chip({ text: commandOf(s.head) || s.head, cls: "hcmd" })}` +
      `${s.left_epoch_millis ? `<time>${monthDay(s.left_epoch_millis)}, ${hhmm(s.left_epoch_millis)}</time>` : "<span></span>"}` +
      `${u ? reqHtml(tm, s, u, scale, { grow: growing(tm) }) : ""}</li>`;
  }).join("")}</ul></section>`;
}

/** THE TEAM'S OWN SPLIT, BY MEMBER: the same whole as the payment bar, cut by who did it, each segment in its member's
 *  provider colour and a door to exactly its requests; a member who left is hatched, under the name it keeps here and
 *  in the chat ("Builder (left)"). */
function memberStack(tm, T, held, grow) {
  const segs = held.map((s) => ({ s, u: useOf(tm, s.id) })).sort((a, b) => b.u.turns - a.u.turns)
    .map(({ s, u }) => ({ n: u.turns, word: nameOf(tm, s), ink: headColor(s.head), href: slotDoor(tm, s), cls: isLeft(tm, s) ? "went" : "" }));
  return DRAW.stack(segs, T.turns, { small: true, grow });
}

/** WHAT THE TEAM HAS DONE SINCE ITS MEMBERS JOINED: one hero figure, its requests over the span they were counted from;
 *  its tokens and its price beside it; then the whole split twice, by how it was paid and by who did it. The scope is
 *  said here once, so no member card carries it again. */
function workHtml(tm) {
  const econ = state.economics[tm.id];
  if (!econ) {
    return DRAW.section("Since joining", `<div class="figrow">${GHOST.hero()}${GHOST.focal()}</div>${GHOST.stack()}`, { cls: "work" }) +
      DRAW.section("By member", GHOST.stack(), { cls: "by" });
  }
  if (econ.error) {
    return DRAW.section("Since joining", `<div class="pd-top"><b class="pd-rate">${DRAW.dash}</b><span class="pd-failed"><span>requests</span></span></div>` +
      `<p class="why limit">${esc(econ.error)}</p>`, { cls: "work" });
  }
  const T = totals(tm), grow = growing(tm);
  const since = sinceOf(tm) || tm.created_epoch_millis || null;
  const hero = DRAW.hero({ figure: DRAW.n(T.turns), of: `${T.turns === 1 ? "request" : "requests"}${since ? ` from ${monthDay(since)}` : ""}` });
  // the price is drawn only where requests were priced: a team wholly on a plan has no amount to show, and its payment
  // bar says why; a price splice could not read for requests that WERE priced is the dash
  const money = (T.tokens ? focal(DRAW.big(T.tokens), "tokens") : "") + (T.priced > 0 ? focal(T.usd != null ? dollars(T.usd) : null, "priced") : "");
  const held = [...tm.slots, ...leftOf(tm)].filter((s) => (useOf(tm, s.id)?.turns || 0) > 0);
  // the hero and its two focal figures stand on one baseline: three figures of one whole read as one line
  return DRAW.section("Since joining", `<div class="figrow">${hero}${money}</div>${T.turns ? splitStack(T, { grow }) : ""}`, { cls: "work" }) +
    (held.length > 1 ? DRAW.section("By member", memberStack(tm, T, held, grow), { cls: "by" }) : "");
}

/** THE TEAM'S BAND: who it is (its name with what may be done to it on the same line, its goal, its folder), then what it
 *  has done. One plate: the head and its figures were two bands that said the team's name and scope twice. */
function bandHtml(tm) {
  const ro = tm.archived;
  // End all: every started member's session ended, the team kept; armed with a chip per member it would cost (fin)
  const started = tm.slots.filter((x) => { const s = sessOf(x); return s && s.availability !== "gone"; });
  const tkey = `team:${tm.id}`, armed = ui.armedEnd.get(tkey), endingAll = ui.ending.get(tkey);
  const endAll = ro || !started.length || armed ? ""
    : endingAll ? `<button class="act stopping" disabled>${ICON.wait}Ending · ${secsSince(endingAll)} s</button>`
    : `<button class="act" data-act="end-all">End all</button>`;
  const armedRow = ro || !armed ? "" : `<div class="armed end">${armed.members.map((m) => { const x = tm.slots.find((y) => y.id === m.slot);
      return `<span class="chip${m.reason === "working" ? " live" : ""}" title="${m.reason === "draft" ? esc(`Unsent: ${m.draft || ""}`) : "Working"}"><i aria-hidden="true"></i>${esc(x ? nameOf(tm, x) : m.slot)}</span>`; }).join("")}` +
    `<button class="act danger small" data-act="end-all-now">End all</button><button class="act quiet small" data-act="end-all-keep">Keep running</button></div>`;
  // a refusal sits where it happened, as a word: End all's, Restore's with each member held and the team holding it
  const refusals = [
    ui.failed.get(tkey) ? esc(ui.failed.get(tkey)) : "",
    ro && ui.failed.has(`restore:${tm.id}`) ? ["Not restored", ...ui.failed.get(`restore:${tm.id}`)].join(" · ") : "",
    ui.failed.get("archive") ? esc(ui.failed.get("archive")) : "",
  ].filter(Boolean).map((w) => `<span class="refusal" role="status">${w}</span>`).join("");
  // an archived team's one way back: restoring it is what lets its members start again (TeamStart refuses until then).
  // Archive stands on the line beside Edit: a menu holding one act is a click in front of that act.
  const acts = ro ? `<button class="act" data-act="restore">Restore</button>`
    : `${endAll}<button class="act" data-act="edit">${GLYPH.Edit}Edit</button><button class="act quiet" data-act="archive">Archive</button>`;
  // the folder is the team's own object, so it rides the name's line with what may be done to the team, and the goal
  // reads under them at a reading measure
  const who = `<section class="psec who"><div class="named"><h2>${esc(tm.name)}</h2>${tm.repo ? folderChip(tm.repo, 2) : ""}` +
    `<div class="acts">${refusals}${acts}</div></div>${armedRow}${tm.goal ? `<p class="goal">${esc(tm.goal)}</p>` : ""}</section>`;
  return `<div class="plate band">${who}${workHtml(tm)}</div>`;
}

function boardHtml(tm) {
  const view = viewOf(tm), key = `${tm.id}/${ui.day}`;
  const msgs = state.chat && !gone(state.chat) ? (state.chat.messages || []).length : 0;
  const calls = state.activity && !gone(state.activity) ? activityRows().filter((a) => a.slot).length : 0;
  const talk = `<section class="plate talk${ui.chatOpen.has(key) ? " open" : ""}" aria-label="Chat">${panelHead("Chat", msgs ? plural(msgs, "message") : "")}` +
    `<div class="chat" data-k="${esc(key)}">${chatHtml(tm, key)}</div></section>`;
  const feed = view === "timeline" ? "" : `<section class="plate feed" aria-label="Activity">${panelHead("Activity", calls ? plural(calls, "tool call") : "")}` +
    `<div class="flow">${feedHtml(tm, key)}</div></section>`;
  return `<section class="team${tm.archived ? " archived" : ""}${state.stale ? " stale" : ""}" data-key="team:${esc(tm.id)}">` +
    `${bandHtml(tm)}<div class="room${view === "timeline" ? " tl" : ""}">${membersHtml(tm)}${talk}${feed}</div></section>`;
}

// ---------- the composer: name, goal, repo, and the members as rows ----------
const blank = () => ({ role: "", head: null, model: null, account: null, lead: false, bind_by_rule: false, instructions: "", open: false });
function composeFrom(tm) {
  return {
    id: tm ? tm.id : null, name: tm?.name || "", goal: tm?.goal || "", repo: tm?.repo || "", error: null,
    slots: tm ? tm.slots.map((s) => ({ ...blank(), ...s, open: false })) : [{ ...blank(), lead: true }, blank()],
  };
}
const ready = (c) => c.name.trim() && c.slots.length && c.slots.every((s) => s.head) && (!c.slots.some((s) => s.bind_by_rule) || c.repo.trim());
/** WHAT SAVE AND START TEAM WAIT FOR, said where they wait, in his terms: the form's three rules, and only the ones not
 *  met yet. A row that has no command keeps its dashed picker; nothing is marked red before he has done anything. */
function waitsOf(c) {
  const open = c.slots.filter((s) => !s.head).length;
  return [
    c.name.trim() ? "" : "Needs a name",
    open ? `${open} ${open === 1 ? "member needs" : "members need"} a command` : "",
    c.slots.some((s) => s.bind_by_rule) && !c.repo.trim() ? "Join by rule needs a repo" : "",
  ].filter(Boolean).join(" · ");
}

/** WHAT A COMMAND CAN DO NOW, where the choice is made: down, out of quota, still being checked, or at a window's
 *  limit, in the words the manager's own head menu uses. It is a mark, never a bar: a team saved today may be started
 *  tomorrow, so no command is taken off the list for being quiet this minute. */
function headMark(h) {
  if (!h) return "";
  const out = h.quotaUntil && h.quotaUntil * 1000 > Date.now();
  const word = h.down ? "Not answering" : out ? `Out of quota · ${backWord(new Date(h.quotaUntil * 1000))}`
    : h.cannotStart ? "Cannot start" : h.unread ? "Checking" : h.quotaFull ? "At limit" : null;
  return word ? `<small class="hmark${h.down || out || h.cannotStart ? " limit" : ""}">${esc(word)}</small>` : "";
}

function pickHtml(c, i) {
  const p = ui.pick;
  const s = c.slots[i];
  if (!p || p.i !== i) return "";
  if (p.what === "head") {
    return `<div class="menu pick-menu" data-what="head">${state.heads.map((h) =>
      `<button data-act="set" data-i="${i}" data-what="head" data-v="${esc(h.key)}"><span class="blot" style="--c:${headColor(h.key)}"></span>${esc(h.command)}${headMark(h)}</button>`).join("")}</div>`;
  }
  if (p.what === "model") {
    const rows = state.models[s.head] || [];
    if (!rows.length) return `<div class="menu pick-menu" data-what="model"><p class="empty">This command declares no models.</p></div>`;
    // the context it holds and the one its command falls back to, as the manager's own model menu shows them
    return `<div class="menu pick-menu" data-what="model">${rows.map((m) =>
      `<button data-act="set" data-i="${i}" data-what="model" data-v="${esc(m.id)}">${esc(m.label)}` +
      `<small class="hmark">${[m.context ? `${DRAW.big(m.context)} context` : "", m.pinned ? "default" : ""].filter(Boolean).map(esc).join(" · ")}</small></button>`).join("")}</div>`;
  }
  // an account by its name, as Accounts shows it, never the pool's id for it
  return `<div class="menu pick-menu" data-what="account">${(state.accounts[s.head] || []).map((a) =>
    `<button data-act="set" data-i="${i}" data-what="account" data-v="${esc(a)}">${esc(accountName(s.head, a))}</button>`).join("")}</div>`;
}

function slotRowHtml(c, s, i) {
  const btn = (what, val, label) => `<button class="pickbtn${val ? "" : " unset"}" data-act="pick" data-i="${i}" data-what="${what}" ` +
    `aria-expanded="${ui.pick?.i === i && ui.pick.what === what}">${val ? label : what === "head" ? "Command" : what === "model" ? "Model" : "Account"}${GLYPH.caret}</button>`;
  // a row with no command yet still shows the two pickers that follow, waiting: the columns are reserved either way,
  // so an empty row reads as the shape it will take instead of as a gap in the middle of itself
  const waiting = (what, word) => `<button class="pickbtn unset" data-what="${what}" disabled>${word}${GLYPH.caret}</button>`;
  const head = btn("head", s.head, `<span class="blot" style="--c:${headColor(s.head)}"></span>${esc(commandOf(s.head) || "")}`);
  const model = s.head ? btn("model", s.model, esc(modelLabel(s.head, s.model))) : waiting("model", "Model");
  const account = s.head ? ((state.accounts[s.head] || []).length ? btn("account", s.account, esc(s.account ? accountName(s.head, s.account) : "")) : "")
    : waiting("account", "Account");
  // a member Start team would start is one with no session running now, which is what the start route itself decides
  const running = s.session && state.sessions[s.session] && state.sessions[s.session].availability !== "gone";
  return `<article class="card slot-row" style="--c:${s.head ? headColor(s.head) : "var(--track-line)"}" data-key="row:${i}">` +
    `<div class="top"><input class="field role-in" data-i="${i}" value="${esc(s.role)}" placeholder="Role" aria-label="Role" list="roles" autocomplete="off" spellcheck="false">` +
    `${head}${model}${account}<button class="leadtog" data-act="lead" data-i="${i}" aria-pressed="${s.lead}">Lead</button>` +
    `<button class="leadtog" data-act="rule" data-i="${i}" aria-pressed="${Boolean(s.bind_by_rule)}">Join by rule</button>` +
    `<button class="act quiet small${s.instructions?.trim() ? " written" : ""}" data-act="instr" data-i="${i}" aria-expanded="${s.open}">Instructions</button>` +
    `${running ? `<span class="willstart on">Running</span>` : `<span class="willstart" title="Start team starts this member">Starts</span>`}` +
    `<button class="act quiet small" data-act="rm" data-i="${i}"${c.slots.length < 2 ? " disabled" : ""}>Remove</button>${pickHtml(c, i)}</div>` +
    (s.bind_by_rule ? ruleMark(c.repo, s.head) : "") +
    (s.open ? `<textarea class="field instr" data-i="${i}" rows="4" aria-label="Instructions">${esc(s.instructions || "")}</textarea>` : "") +
    `</article>`;
}

function composerHtml() {
  const c = ui.compose;
  const ok = ready(c) && !c.saving;
  // Not saved sits on the save row; Repo's row has a word only for the three refusals TeamRules makes of the repo (fin)
  const word = c.repoWord ? `<span class="state limit" role="status">${esc(c.repoWord)}</span>` : "";
  const repoRefused = Boolean(c.repoWord);
  // the folders this machine's sessions already run in, so a repo is picked rather than typed from memory
  const folders = [...new Set(Object.values(state.sessions).map((x) => x.repo?.root || x.cwd).filter(Boolean))].sort();
  return `<section class="compose" data-key="compose" role="dialog" aria-modal="true" aria-labelledby="t-title">` +
    `<h2 id="t-title">${c.id ? esc(state.teams.find((t) => t.id === c.id)?.name) : "New team"}</h2>` +
    `<section class="psec"><div class="sechead"><h3 class="kicker">The team</h3></div>` +
    `<div class="frow"><label for="t-name">Name</label><input class="field" id="t-name" value="${esc(c.name)}" autocomplete="off" spellcheck="false"></div>` +
    `<div class="frow"><label for="t-goal">Goal</label><textarea class="field" id="t-goal" rows="2">${esc(c.goal)}</textarea></div>` +
    `<div class="frow"><label for="t-repo">Repo</label><div class="withword"><input class="field" id="t-repo" value="${esc(c.repo)}" list="repos" autocomplete="off" spellcheck="false"${repoRefused ? ' aria-invalid="true"' : ""}>${word}</div></div></section>` +
    `<section class="psec"><div class="sechead"><h3 class="kicker">Members</h3>` +
    `<span class="sec-aside">${DRAW.n(c.slots.length)}</span></div>` +
    `<div class="rows">${c.slots.map((s, i) => slotRowHtml(c, s, i)).join("")}` +
    `${DRAW.addTile({ word: "Add member", act: "add-slot" })}</div></section>` +
    `<datalist id="roles"><option value="builder"><option value="reviewer"><option value="researcher"></datalist>` +
    `<datalist id="repos">${folders.map((f) => `<option value="${esc(f)}">`).join("")}</datalist>` +
    `<footer>` +
    (c.error ? `<span class="state limit" role="status">${esc(c.error)}</span>` : `<span class="waits">${esc(c.saving ? "" : waitsOf(c))}</span>`) +
    `<button class="act quiet" data-act="cancel">Cancel</button>` +
    `<button class="act quiet" data-act="save"${ok ? "" : " disabled"}>Save</button>` +
    `<button class="act primary" data-act="start-team"${ok ? "" : " disabled"}>Start team</button></footer></section>`;
}

// ---------- the page's other shapes: reading, no team yet, and a first read that failed ----------
/** THE PAGE READING: the shape of what will land, at its size and in its places — the tabs, the band with its hero and
 *  its two bars, the views, the member cards in the members' columns, the chat beside them and the activity under —
 *  striped, as the kit's ghosts are, so nothing moves when the data arrives. */
/** THE FIRST FRAME HOLDS THE SHAPE THIS BROWSER LAST LANDED ON: the team list is known only when its read answers, and the
 *  project tiles are drawn from the sessions, which land with it. Until then the frame is a team's page with as many
 *  tiles as the last page drew (none on a first visit), or the empty page's own shape where the last read found no team;
 *  the moment the team list answers, the frame follows it. So the page under it stays where it is when the data lands. */
const SHAPE_STORE = "splice-teams-shape";
function shapeGuess() {
  try {
    const v = localStorage.getItem(SHAPE_STORE);
    if (v === "none") return { none: true, tiles: 0 };
    if (v != null) return { none: false, tiles: Math.max(0, parseInt(v, 10) || 0) };
  } catch { /* storage off: nothing remembered */ }
  return { none: false, tiles: 0 };  // a first visit has drawn no tiles yet, so the frame draws none
}
function rememberShape(v) {
  try { if (localStorage.getItem(SHAPE_STORE) !== String(v)) localStorage.setItem(SHAPE_STORE, String(v)); } catch { /* storage off */ }
}
function loadingHtml() {
  const guess = shapeGuess();
  if (state.known === 0 || (state.known == null && guess.none)) {
    return `<div class="loading" role="status" aria-busy="true" aria-label="Reading teams">${emptyHtml(true)}</div>`;
  }
  const tiles = guess.none ? 0 : guess.tiles;
  // a card's figures as they will land: the column heads, the total row and the two span rows, then an account's name
  // and its two windows, each piece at its own size (kit GHOST)
  const pair = `<i class="ghost vtrack"></i><i class="ghost fig v"></i>`;
  const figs = `<div class="mtab"><div class="mtr mth"><span></span><span class="mh">${GHOST.bar(4.6)}</span><span class="mh">${GHOST.bar(3.6)}</span></div>` +
    [3.4, 4, 3.6].map((w) => `<div class="mtr"><span>${GHOST.bar(w)}</span>${pair}${pair}</div>`).join("") + `</div>`;
  const win = `<div class="prow">${GHOST.bar(3.4)}${GHOST.track()}<span class="pct">${GHOST.fig(4.2)}</span><span class="when">${GHOST.ring()}${GHOST.bar(6.2)}</span></div>`;
  const card = (i) => `<article class="card m vacant ghosted" style="--i:${i}" aria-hidden="true"><span class="lamp open"></span>` +
    `<div class="top">${GHOST.bar(7 + (i % 2) * 2)}${GHOST.state()}</div><div class="meta">${GHOST.chip()}${GHOST.chip(7)}</div>` +
    `<div class="body">${GHOST.bar(15 - i)}</div><div class="use">${figs}<div class="pacc">${GHOST.bar(8)}${win}${win}</div></div></article>`;
  const band = `<div class="plate band" aria-hidden="true"><section class="psec who"><div class="named">${GHOST.bar(11, "big")}${GHOST.chip(9)}</div>` +
    `${GHOST.lines([44, 30])}</section>` +
    DRAW.section("Since joining", `<div class="figrow">${GHOST.hero()}${GHOST.focal()}</div>${GHOST.stack()}${GHOST.lines([9])}`, { cls: "work" }) +
    DRAW.section("By member", `${GHOST.stack()}${GHOST.lines([7])}`, { cls: "by" }) + `</div>`;
  const panel = (cls, word, lines) => `<section class="plate ${cls}" aria-hidden="true">${panelHead(word)}<div class="${cls === "talk" ? "chat" : "flow"}">${GHOST.lines(lines)}</div></section>`;
  return `<div class="loading" role="status" aria-busy="true" aria-label="Reading teams">${GHOST.tiles(tiles)}` +
    `<div class="tbar" aria-hidden="true"><nav class="tabs">${[9, 11, 7].map((w) => GHOST.bar(w + 3, "tabg")).join("")}</nav>` +
    `<div class="views">${GHOST.bar(26, "tabg")}${GHOST.bar(9)}</div></div>${band}` +
    `<div class="room"><div class="members">${[0, 1, 2, 3].map(card).join("")}</div>` +
    `${panel("talk", "Chat", [22, 16, 19, 12])}${panel("feed", "Activity", [18, 21, 14])}</div></div>`;
}
/** NO TEAM YET: the page keeps the shape of what a team will fill, each part a dashed outline where its figure or its
 *  object will stand (the console's own absence shape, nothing striped and nothing moving), the one muted line in the
 *  name's place, and the New team tile where the first member card lands, beside the chat's and the activity's places. */
function emptyHtml(reading = false) {
  // while the read is out the same places are striped ghosts, and the page lands in the shape it was drawn in
  const bare = reading ? "" : " bare";
  const hole = (cls) => reading ? `<i class="ghost hole ${cls}" aria-hidden="true"></i>` : `<span class="hole ${cls}" aria-hidden="true"></span>`;
  const band = `<div class="plate band${bare}"><section class="psec who"><div class="named">${reading ? GHOST.bar(11, "big") : "<h2>No teams yet</h2>"}</div></section>` +
    DRAW.section("Since joining", `<div class="figrow">${hole("hero")}</div>${hole("wide")}`, { cls: "work" }) +
    DRAW.section("By member", hole("wide"), { cls: "by" }) + `</div>`;
  const place = (cls, word, lines) => `<section class="plate ${cls} hold${bare}" aria-label="${word}">${panelHead(word)}${reading && lines ? GHOST.lines(lines) : ""}</section>`;
  const tile = reading ? GHOST.card([8, 20, 14], 0, "seat") : DRAW.addTile({ word: "New team", act: "new", cls: "tnew first", pressed: ui.compose?.id === null });
  return `<section class="team${bare}">${band}<div class="room"><div class="members" style="--n:1">${tile}</div>` +
    `${place("talk", "Chat", [22, 16])}${place("feed", "Activity")}</div></section>`;
}
/** A FIRST READ THAT FAILED: what splice said, in one word, and the read again, in a dashed frame of its own where the
 *  room would stand. It is neither the reading shape nor the empty page, and it is drawn once. */
const failedHtml = () => `<div class="unread" role="alert"><span class="state limit">${esc(state.error)}</span>` +
  `<button class="act quiet small" data-act="read">Try again</button></div>`;

// ---------- render ----------
// A DRAW THAT CHANGES NOTHING TOUCHES NOTHING: the page is rebuilt as markup on every read and written only where the
// markup differs from what is on the page, so a five-second read that found the same team leaves every bar, open list,
// hover and caret where it was, and the room is never thrown back.
const drawn = { root: null, sheet: null };
function render() {
  const a = document.activeElement;
  const kind = a?.closest?.(".compose") && ["#t-name", "#t-goal", "#t-repo", ".role-in", ".instr"].find((k) => a.matches(k));
  const focus = kind ? { sel: kind.startsWith("#") ? kind : `${kind}[data-i="${a.dataset.i}"]`, pos: a.selectionStart } : null;
  const shown = state.teams.filter((t) => (!t.archived || ui.archived) && inPick(t));
  const was = ui.team;
  if (!shown.some((t) => t.id === ui.team)) ui.team = shown.find((t) => !t.archived)?.id ?? shown[0]?.id ?? null;
  // a team the pick or the fold moved him onto reads its own figures, as one he pressed does
  if (ui.team !== was && ui.team && state.everRead) setTimeout(readTeam, 0);
  const tm = shown.length ? team() : null;
  const html = state.error ? failedHtml() : state.loading ? loadingHtml() : !state.teams.length ? emptyHtml()
    : pickLineHtml(groupsNow(), picked(), ui.pmenu, teamCount()) + tabsHtml(tm) + (tm ? boardHtml(tm) : "");
  // what the pick line drew is what the next first frame holds a place for (the tiles, or nothing with fewer than two)
  if (!state.error && !state.loading) rememberShape(!state.teams.length ? "none" : groupsNow().length >= 2 ? groupsNow().length + 1 : 0);
  if (html !== drawn.root) {
    // a member's role being typed on its card keeps its caret through a redraw, as the team form's fields do
    const typing = a?.matches?.(".mchange .role-in") ? a.selectionStart : null;
    root.innerHTML = html;
    drawn.root = html;
    if (typing != null) { const el = root.querySelector(".mchange .role-in"); el?.focus(); el?.setSelectionRange(typing, typing); }
    // every meter takes its width once the markup is in the page; a team's meters grow in on the draw its figures landed
    DRAW.paint(root, { grow: tm && growing(tm) ? "all" : null });
    fitChat();
    root.querySelector(".menu button")?.focus();
  }
  const pane = ui.compose ? `<div class="scrim">${composerHtml()}</div>` : "";
  if (pane !== drawn.sheet) {
    sheet.innerHTML = pane;
    drawn.sheet = pane;
    const el = focus && sheet.querySelector(focus.sel);
    if (el) { el.focus(); if (focus.pos != null) el.setSelectionRange(focus.pos, focus.pos); }
    sheet.querySelector(".menu button")?.focus();
  }
  document.querySelector(".app").inert = Boolean(ui.compose);
  markRead();
}

// The room he just asked for arrives rather than appearing: one short rise, on the switch only, never on a re-read.
const roomIn = () => root.querySelector(".room")?.classList.add("roomin");

// ---------- the acts ----------
// A new slot's id comes from its role: builder, then builder-2. A slot that exists keeps its id, so its messages and
// its usage stay its own when he renames the role.
function slugs(slots) {
  const taken = new Set(slots.map((s) => s.id).filter(Boolean));
  return slots.map((s) => {
    if (s.id) return s.id;
    const base = (s.role.trim() || commandOf(s.head)).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "member";
    let id = base;
    for (let n = 2; taken.has(id); n++) id = `${base}-${n}`;
    taken.add(id);
    return id;
  });
}

/** Saves the composer. The store judges the repo and the composition; its refusal is what the person reads. Save and
 *  Start team hold while one save is in flight, and a new team's form keeps one Idempotency-Key from its first press,
 *  so a press after a failed or unanswered create can never make a second team (marlin, from splice-turns' audit). */
async function save(startAfter) {
  const c = ui.compose;
  if (c.saving) return;
  c.saving = true;
  render();
  if (!c.id) c.once ??= `console-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  const ids = slugs(c.slots);
  const body = {
    name: c.name.trim(),
    goal: c.goal.trim(),
    repo: c.repo.trim(),
    slots: c.slots.map((s, i) => ({
      id: ids[i], role: s.role.trim() || ids[i], head: s.head,
      model: s.model || undefined, account: s.account || undefined,
      lead: s.lead, bind_by_rule: s.bind_by_rule || undefined, instructions: s.instructions?.trim() || undefined,
    })),
  };
  const answer = c.id ? await API.put(`/api/teams/${c.id}`, { ...body, id: c.id }) : await createTeam(body, c.once);
  c.saving = false;
  if (!answer.ok) {
    c.error = refusedAs(answer, "Not saved", "save team");
    c.repoWord = answer.status === 0 ? null : repoWordOf(answer.body?.error);
    render();
    if (c.repoWord) sheet.querySelector("#t-repo")?.focus(); // a refusal of the repo leaves the person on Repo's row
    return;
  }
  const saved = answer.body;
  ui.compose = null;
  ui.pick = null;
  ui.team = saved?.id || ui.team;
  await read();
  if (startAfter && saved) {
    const fresh = state.teams.find((t) => t.id === saved.id);
    for (const slot of fresh?.slots.filter((s) => !s.session) || []) await startSlot(fresh, slot);
  }
}

/** A create needs an Idempotency-Key, so a retried create cannot make a second team. */
function createTeam(body, once) {
  return API.put("/api/teams", body, { "Idempotency-Key": once });
}

/** Starting a member opens a Claude Code session on its command, bound to the slot. A start that stops on a question
 *  (202, needs_you) leaves it on the card for him; asked again for that slot, the start waits on the same terminal. */
async function startSlot(tm, slot, path = null) {
  const key = `${tm.id}/${slot.id}`;
  ui.failed.delete(key);
  ui.unstarted.delete(key);
  ui.screenOpen.delete(key);
  ui.starting.add(key);
  ui.startedAt.set(key, Date.now());
  render();
  const tick = setInterval(render, 1000);
  const answer = await API.post(`/api/teams/${tm.id}/slots/${slot.id}/start`);
  clearInterval(tick);
  ui.starting.delete(key);
  ui.startedAt.delete(key);
  // the daemon's sentence goes to the console's log; the card says the fact and keeps what he has not seen (fin)
  if (answer.status === 504) {
    console.warn(`start ${key}: ${answer.body?.error || 504}`);
    ui.unstarted.set(key, { screen: answer.body?.screen || "", why: reasonOf(answer.body?.error) });
  } else if (answer.status === 409 && answer.body?.reason === "closed") {
    ui.unstarted.set(key, { path, why: "Terminal closed" });
  } else if (!answer.ok) {
    // the card's own Not started, with no reason line; Not answering when splice did not answer (fin)
    const word = refusedAs(answer, "Not started", `start ${key}`);
    if (answer.status === 0) ui.failed.set(key, word);
    else ui.unstarted.set(key, { why: null });
  }
  await read();
}

/** Repo's word for the three refusals TeamRules.kt makes of the repo, matched whole so no other refusal outlines Repo
 *  (fin): needed when a member joins by rule, a path that does not exist, and one that is not a folder. */
const repoWordOf = (said) => {
  const why = String(said || "");
  if (/^repo is needed when a member joins by rule\b/.test(why)) return "Needed";
  if (/^repo '.*' does not exist$/s.test(why)) return "Not found";
  return /^repo '.*' is not a directory$/s.test(why) ? "Not a folder" : null;
};
/** The reason line a start that gave up keeps on its card, as a state (fin; Marlin p223): "Claude Code had not registered
 *  after 45s" reads "Waited 45 s". Any other reason has no line; its sentence is already in the console's log. */
const reasonOf = (said) => {
  const waited = String(said || "").match(/had not registered after (\d+)\s*s/);
  return waited ? `Waited ${waited[1]} s` : null;
};
/** The folder a question names: its panel's line that is a path, as the client drew it. */
const pathOf = (offer) => (offer?.panel || []).map((l) => l.text).find((t) => /^[~/]/.test(t)) || null;
/** The client's question in its own words: the panel's lines up to its question mark, from after the last line that
 *  is a path or a heading ending in a colon, so "Accessing workspace:" and the advice after the question stay off. */
function questionOf(offer) {
  let said = [];
  for (const { text } of offer.panel || []) {
    if (/^[~/]/.test(text) || /:$/.test(text)) { said = []; continue; }
    said.push(text);
    const at = said.join(" ").indexOf("?");
    if (at >= 0) return said.join(" ").slice(0, at + 1);
  }
  return offer.asked || "";
}

/** The client's question as it drew it: the question verbatim, its folder as code, and its options as the buttons,
 *  verbatim, neither primary (the client itself points at No). Nothing is answered until he clicks, and splice never
 *  picks one for him. The pressed one holds the wait ring until the terminal has it, the rest wait disabled. */
/** A folder path written whole, free to wrap after any slash, so a long path breaks between folders and never inside a
 *  folder's name. */
const folderBreaks = (path) => esc(path).replaceAll("/", "/<wbr>");
function trustHtml(slotId, offer, pressed) {
  const path = pathOf(offer);
  const acts = offer.choices.map((c) => pressed !== undefined
    ? `<button class="act${pressed === c.choice ? " answering" : ""}" disabled>${pressed === c.choice ? ICON.wait : ""}${esc(c.label)}</button>`
    : `<button class="act" data-act="trust" data-s="${esc(slotId)}" data-i="${c.choice}" data-l="${esc(c.label)}">${esc(c.label)}</button>`).join("");
  return `<div class="ask trust"><p class="q">${esc(questionOf(offer))}</p>${path ? `<p class="cmd">${folderBreaks(path)}</p>` : ""}` +
    `<div class="answers">${acts}</div></div>`;
}

/** End: the member's session closed where it runs. With nothing to lose it goes on the first press; a turn mid-work or
 *  unsent words arm the card instead, and the second press ends it anyway. A refusal reads Not ended, its cause in the
 *  log (fin). */
async function endSlot(tm, slot, confirm) {
  const key = `${tm.id}/${slot.id}`;
  ui.armedEnd.delete(key); ui.failed.delete(`end:${key}`);
  ui.ending.set(key, Date.now());
  render();
  const tick = setInterval(render, 1000);
  const res = await API.post(`/api/sessions/${slot.session}/end`, confirm ? { confirm: true } : {});
  clearInterval(tick);
  ui.ending.delete(key);
  if (res.ok || res.body?.reason === "ended") { await read(); return; }
  const why = res.body?.reason;
  if (!confirm && why === "working") ui.armedEnd.set(key, {});
  else if (!confirm && why === "draft") ui.armedEnd.set(key, { draft: res.body?.draft ?? "" });
  else {
    if (res.body?.error) console.warn(`end ${key}:`, res.body.error);
    ui.failed.set(`end:${key}`, why === "not_ours" ? "Not ended · Started elsewhere" : "Not ended");
  }
  render();
}

/** End all: every started member ended; armed first with a chip for each member that is working or holds unsent words. */
async function endTeam(tm, confirm) {
  const key = `team:${tm.id}`;
  ui.armedEnd.delete(key);
  ui.failed.delete(key);
  ui.ending.set(key, Date.now());
  render();
  const tick = setInterval(render, 1000);
  const res = await API.post(`/api/teams/${tm.id}/end`, confirm ? { confirm: true } : {});
  clearInterval(tick);
  ui.ending.delete(key);
  if (!confirm && res.status === 409 && res.body?.reason === "costs") ui.armedEnd.set(key, { members: res.body.members || [] });
  else if (!res.ok) { console.warn(`end all ${tm.id}:`, res.body?.error || res.status); ui.failed.set(key, "Not ended"); }
  else for (const m of res.body?.members || []) if (m.status >= 400 && m.reason !== "ended") ui.failed.set(`end:${tm.id}/${m.slot}`, m.reason === "not_ours" ? "Not ended · Started elsewhere" : "Not ended");
  await read();
}

/** His answer to the question a start stopped on, then the start again on that same terminal: after Yes it waits for
 *  the member to come up, after No the terminal has closed and the slot is given back. */
async function answerTrust(tm, slot, choice, label) {
  const key = `${tm.id}/${slot.id}`;
  if (ui.trusting.has(key)) return;
  const path = pathOf(state.pending[slot.id]);
  ui.trusting.set(key, choice);
  render();
  const answered = await API.post(`/api/teams/${tm.id}/slots/${slot.id}/answer`, { choice, label });
  ui.trusting.delete(key);
  if (!answered.ok) {
    ui.failed.set(key, refusedAs(answered, "Not sent", `answer ${key}`));
    await read();
    return;
  }
  delete state.pending[slot.id];
  await startSlot(tm, slot, path);
}

/** Answering presses the option's own number in the member's terminal; a refusal stays on its card. */
async function answerSlot(tm, slot, choice) {
  const key = `${tm.id}/${slot.id}`;
  ui.failed.delete(key);
  ui.answered.set(key, { at: Date.now(), choice, offer: state.screens[slot.id] });
  render();
  const answered = await API.post(`/api/teams/${tm.id}/slots/${slot.id}/answer`, { choice });
  if (!answered.ok) {
    ui.answered.delete(key);
    ui.failed.set(key, refusedAs(answered, "Not sent", `answer ${key}`));
  }
  await read();
}

/** A refused act keeps the board: its word sits by the control that was refused, [where] in ui.failed. */
async function act(answer, whenRefused, where) {
  if (!answer.ok) {
    ui.failed.set(where, refusedAs(answer, whenRefused, where));
    render();
    return false;
  }
  await read();
  return true;
}

sheet.addEventListener("input", (e) => {
  const c = ui.compose;
  if (!c) return;
  const el = e.target;
  if (el.id === "t-name") c.name = el.value;
  else if (el.id === "t-goal") { c.goal = el.value; return; }
  else if (el.id === "t-repo") {
    c.repo = el.value;
    // the folder a rule watches is the one the form names now: each rule line's folder follows the typing in place, so
    // the field keeps its focus and its caret
    for (const chip of sheet.querySelectorAll(".rulemark .chip.where")) chip.outerHTML = folderChip(c.repo);
    if (c.error || c.repoWord) { // typing in Repo answers the refusal: its words and outline go, the field keeps its focus
      c.error = null;
      c.repoWord = null;
      el.removeAttribute("aria-invalid");
      sheet.querySelector(".withword .state")?.remove();
      sheet.querySelector(".compose footer .state")?.replaceWith(Object.assign(document.createElement("span"), { className: "waits" }));
    }
  }
  else if (el.matches(".role-in")) c.slots[+el.dataset.i].role = el.value;
  else if (el.matches(".instr")) { c.slots[+el.dataset.i].instructions = el.value; return; }
  const ok = Boolean(ready(c)) && !c.saving;
  for (const b of sheet.querySelectorAll('[data-act="save"], [data-act="start-team"]')) b.disabled = !ok;
  const waits = sheet.querySelector(".compose footer .waits");
  if (waits) waits.textContent = c.saving ? "" : waitsOf(c);
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && (ui.armedEnd.size || ui.armedStop.size)) { ui.armedEnd.clear(); ui.armedStop.clear(); render(); return; }
  if (e.key !== "Escape") return;
  if (ui.menu || ui.pick) { ui.menu = null; ui.pick = null; render(); }
  // a member's own form: its open picker first, then the form, which puts the member's chips back
  else if (ui.change?.pick) { ui.change.pick = null; render(); }
  else if (ui.change) { ui.change = null; render(); }
  else if (ui.compose) { ui.compose = null; ui.pick = null; render(); }
});

/** A member card bound to a session opens it in Sessions, which reads its door from the hash. */
function openSession(card) {
  CONSOLE.navigate(`sessions.html#${encodeURIComponent(card.dataset.go)}`);
}
document.addEventListener("keydown", (e) => {
  const card = e.target.closest?.(".card[data-go]");
  if (card && e.target === card && e.key === "Enter") openSession(card);
  // Enter in a member's own role field saves its form
  if (ui.change && e.key === "Enter" && e.target.matches?.(".mchange .role-in")) saveChange(team());
});
root.addEventListener("input", (e) => { if (ui.change && e.target.matches(".mchange .role-in")) ui.change.role = e.target.value; });

document.addEventListener("click", async (e) => {
  const b = e.target.closest("[data-act]");
  const card = e.target.closest(".card[data-go]");
  if (!b && card && !e.target.closest("a, button, input, textarea, select, .mchange")) { openSession(card); return; }
  // an armed End is disarmed by a tap anywhere but its own row, as Restart's is (fin)
  if ((ui.armedEnd.size || ui.armedStop.size) && !e.target.closest(".armed.end")) {
    ui.armedEnd.clear(); ui.armedStop.clear(); if (!b) { render(); return; }
  }
  if (ui.pmenu && !e.target.closest(".pmenu, [data-act=projmenu]")) { ui.pmenu = false; if (!b) { render(); return; } }
  if (!b) {
    if (ui.menu || ui.pick || ui.change?.pick) { ui.menu = null; ui.pick = null; if (ui.change) ui.change.pick = null; render(); }
    return;
  }
  const tm = team();
  const c = ui.compose;
  const i = +b.dataset.i;
  const slot = tm && b.dataset.s ? slotOf(tm, b.dataset.s) : null;
  switch (b.dataset.act) {
    case "theme": toggleTheme(); break;
    case "read": await read(); break;
    case "team": Object.assign(ui, { team: b.dataset.t, compose: null, pick: null, menu: null, change: null, day: 0 }); render(); roomIn(); await readTeam(); break;
    case "projmenu": ui.pmenu = !ui.pmenu; render(); break;
    // the same pick as Sessions and Requests; the open team stays when it has a member there, else the first that does
    case "pickproj": { const was = ui.team; ui.project = b.dataset.p || null; ui.pmenu = false; PICK.set(ui.project);
      Object.assign(ui, { menu: null, pick: null }); render(); if (ui.team !== was) { ui.day = 0; await readTeam(); } break; }
    case "archived": ui.archived = !ui.archived; render(); break;
    case "view": if (tm) { setView(tm, b.dataset.v); render(); roomIn(); } break;
    case "chat-more": if (!ui.chatOpen.delete(b.dataset.k)) ui.chatOpen.add(b.dataset.k); render(); break;
    case "feed-more": if (!ui.feedOpen.delete(b.dataset.k)) ui.feedOpen.add(b.dataset.k); render(); break;
    case "new": Object.assign(ui, { compose: composeFrom(null), menu: null, pick: null }); render(); sheet.querySelector("#t-name")?.focus(); break;
    case "edit": Object.assign(ui, { compose: composeFrom(tm), menu: null }); render(); sheet.querySelector("#t-name")?.focus(); break;
    case "archive":
      ui.menu = null; ui.failed.delete("archive");
      await act(await API.post(`/api/teams/${tm.id}/archive`), "Not archived", "archive");
      break;
    case "restore": {
      ui.failed.delete(`restore:${tm.id}`);
      const answer = await API.post(`/api/teams/${tm.id}/restore`);
      if (answer.ok) { await read(); break; }
      // fin: "Not restored", then each held member as its chip, "on", and the active team that holds it, read from the
      // teams this page already has; the daemon's own sentence goes to the console log, never onto the screen
      refusedAs(answer, "Not restored", `restore ${tm.id}`);
      ui.failed.set(`restore:${tm.id}`, tm.slots.filter((s) => s.session).flatMap((s) => alsoOn(tm, s.session).map((name) =>
        `<span class="party" style="--c:${headColor(s.head)}"><i></i>${esc(nameOf(tm, s))}</span> on ${esc(name)}`)));
      render();
      break;
    }
    case "day": ui.day = Math.max(0, Math.min(6, ui.day + +b.dataset.d)); render(); await readTeam(); break;
    case "start": await startSlot(tm, slot); break;
    case "use": { const k = `${tm.id}/${slot.id}`; ui.menu = ui.menu === k ? null : k; render(); break; }
    case "bind":
      ui.menu = null;
      ui.failed.delete(`bind:${tm.id}/${slot.id}`);
      await act(await API.put(`/api/teams/${tm.id}/sessions`, { bindings: { [slot.id]: b.dataset.to } }),
        "Not added", `bind:${tm.id}/${slot.id}`);
      break;
    case "stop": ui.armedStop.add(`${tm.id}/${slot.id}`); render(); break;
    case "stop-keep": ui.armedStop.delete(`${tm.id}/${slot.id}`); render(); break;
    case "stop-now": { // the refusal is a word by its reason key; its cause goes to the log (fin, kit stopRefusal)
      const key = `${tm.id}/${slot.id}`;
      ui.armedStop.delete(key); ui.failed.delete(`stop:${key}`); ui.stopping.add(key); render();
      const res = await API.post(`/api/teams/${tm.id}/slots/${slot.id}/stop`);
      ui.stopping.delete(key);
      const refused = stopRefusal(res);
      if (refused) { ui.failed.set(`stop:${key}`, refused); render(); } else await read();
      break;
    }
    case "answer": await answerSlot(tm, slot, Number(b.dataset.i)); break;
    case "trust": await answerTrust(tm, slot, Number(b.dataset.i), b.dataset.l); break;
    case "end": await endSlot(tm, slot, false); break;
    case "end-now": await endSlot(tm, slot, true); break;
    case "end-keep": ui.armedEnd.delete(`${tm.id}/${slot.id}`); render(); break;
    case "end-all": await endTeam(tm, false); break;
    case "end-all-now": await endTeam(tm, true); break;
    case "end-all-keep": ui.armedEnd.delete(`team:${tm.id}`); render(); break;
    case "screen": { const k = `${tm.id}/${slot.id}`; if (!ui.screenOpen.delete(k)) ui.screenOpen.add(k); render(); break; }
    // a member changed in place, on its own card
    case "change":
      Object.assign(ui, { menu: null, pick: null, change: { key: `${tm.id}/${slot.id}`, slot: slot.id, role: slot.role || "", head: slot.head,
        model: slot.model || null, account: slot.account || null, pick: null, error: null, saving: false } });
      render();
      root.querySelector(".mchange .role-in")?.focus();
      break;
    case "cpick": if (ui.change) { ui.change.pick = ui.change.pick === b.dataset.what ? null : b.dataset.what; render(); } break;
    case "cset": {
      const ch = ui.change;
      if (!ch) break;
      if (b.dataset.what === "head" && ch.head !== b.dataset.v) Object.assign(ch, { head: b.dataset.v, model: null, account: null });
      else ch[b.dataset.what] = b.dataset.v;
      ch.pick = null;
      render();
      break;
    }
    case "change-cancel": ui.change = null; render(); break;
    case "change-save": await saveChange(tm); break;
    // the composer
    case "pick": ui.pick = ui.pick?.i === i && ui.pick.what === b.dataset.what ? null : { i, what: b.dataset.what }; render(); break;
    case "set": {
      const s = c.slots[i];
      if (b.dataset.what === "head" && s.head !== b.dataset.v) Object.assign(s, { head: b.dataset.v, model: null, account: null });
      else s[b.dataset.what] = b.dataset.v;
      ui.pick = null;
      render();
      break;
    }
    case "lead": c.slots[i].lead = !c.slots[i].lead; render(); break;
    case "rule": c.slots[i].bind_by_rule = !c.slots[i].bind_by_rule; render(); break;
    case "instr":
      c.slots[i].open = !c.slots[i].open;
      render();
      if (c.slots[i].open) sheet.querySelector(`.instr[data-i="${i}"]`)?.focus();
      break;
    case "rm": c.slots.splice(i, 1); ui.pick = null; render(); break;
    case "add-slot": c.slots.push(blank()); render(); sheet.querySelectorAll(".role-in")[c.slots.length - 1]?.focus(); break;
    case "cancel": Object.assign(ui, { compose: null, pick: null }); render(); break;
    case "save": await save(false); break;
    case "start-team": await save(true); break;
  }
});

read();

// A countdown or a silence is only true as of the read it came from, so the turns and the sessions are read again every
// five seconds while the page is shown and nothing is being edited; without it Resumes in sat at 0:00 and a turn that
// had ended stayed Working until he pressed Read again (desk walk, Oct 10). A page whose read failed asks again in full
// on the same beat, so it comes back on its own the moment splice answers.
setInterval(async () => {
  // a locked console (api.js set body.locked on a 401) sends nothing until it is unlocked (gap 386b)
  if (document.hidden || document.body.classList.contains("locked") || state.loading || inFlight || ui.compose || ui.pick || ui.menu || ui.change) return;
  if (state.error || state.stale) { await read(); return; }
  const sessions = await API.get("/api/sessions");
  if (!sessions.ok) { state.stale = refusedAs(sessions, "Not read", "/api/sessions"); render(); return; }
  takeSessions(sessions.body);
  state.readAt = Date.now();
  await readLive();
  render();
}, 5000);

// the silence counter and the resume countdown move each second between reads, from when the turn was read
setInterval(() => {
  for (const el of root.querySelectorAll("[data-silent], [data-resume]")) {
    const st = state.stalls[el.dataset.silent || el.dataset.resume];
    if (!st) continue;
    const went = Date.now() - st.at, ms = el.dataset.silent ? st.ms + went : Math.max(0, st.resumeMs - went);
    el.lastElementChild.textContent = el.dataset.silent ? counter(ms) : `Resumes in ${counter(ms)}`;
    el.querySelector(".hand")?.style.setProperty("transform", `rotate(${Math.floor(ms / 1000) * 6}deg)`);
  }
}, 1000);
// the tiles come and go with the page's width, as Sessions' do; the chat refits the column beside it as that column
// changes height with the width, and once more when the fonts arrive, since a message's height is its font's
let wasWide = null, fitFrame = 0;
addEventListener("resize", () => {
  const w = tilesWide();
  if (w !== wasWide) { wasWide = w; if (!state.loading) render(); }
  cancelAnimationFrame(fitFrame);
  fitFrame = requestAnimationFrame(fitChat);
});
document.fonts?.ready.then(fitChat);

});
