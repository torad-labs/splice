// Sessions: every agent on every command and project, what it's doing, and its conversation. hitstop's drawing
// (Splice-Animator design/console/sessions.js), built on live data.
//
// WHERE EVERY VALUE COMES FROM. The page carries no data of its own:
//   GET  /api/sessions                      each session as its client registered it: status, what it waits for,
//                                           whether it still runs, its folder, its command, its team
//   GET  /api/heads · /api/models           the commands, the provider each runs on and its models' names
//   GET  /api/heads/retired                 the commands removed from Models: a session on one still reads its name,
//                                           and a search for that name finds it ("History stays", Marlin)
//   GET  /api/heads/{head}/turns/live       the turns running now, by session: the one Stop ends and the model it runs
//   GET  /api/sessions/{id}/transcript      the conversation, newest first, with the cursor to the page before it
//   POST /api/heads/{head}/turns/{id}/stop  Stop, for a session splice did not open
//   GET  /api/sessions/{id}/screen          what a session splice opened is asking on its own screen, and its choices
//   POST /api/sessions/{id}/say · answer · stop   the Message field, an answer's button, and Stop, in the terminal splice
//                                           opened for it (SessionDrive.kt); a session it did not open draws none of them
//
// WHERE A SESSION MOVED is drawn from the transcript page's `moves` (ModelMoves): "Continued on [to] model · from
// [from] model", at the moment of the move, with its time and, when moved rows lost it, "Thinking not carried over"
// (fin, hitstop f38853e). A move kept from before Continue on named its start carries no from, and draws only where it went.
//
// ONE PAGE, ONE SCROLL (round 2, Oct 27). A session opens in place, under its own row inside its project's block, and
// the page scrolls once: the open session's conversation is drawn in the page's flow, its newest items standing and the
// older ones behind one count, so nothing on the page scrolls inside itself. The blocks stand in equal columns packed
// by how many rows each one draws, so opening a session moves no block to another column.
CONSOLE.page("sessions.html", (scope) => {
  "use strict";
  const { document, window, API, setTimeout, clearTimeout, setInterval, clearInterval, requestAnimationFrame, cancelAnimationFrame, addEventListener, removeEventListener } = scope;

const NAMES_KEY = "splice.sessions.names";
// `screens`: what a session's own prompt shows, by session id, read only for sessions splice opened (GET .../screen
// answers 200 for those and refuses the rest). A session with a screen is one splice can drive: it gets the Message
// field and its answers are buttons. One it can't keeps what its transcript says and draws no act it couldn't carry.
const state = { rows: [], past: new Map(), heads: [], retired: [], providerOf: {}, modelLabel: {}, window: {}, live: {}, logs: {}, screens: {}, usage: {}, error: null, readAt: null, loading: true };
const ui = { ending: new Map(), armedEnd: new Map(), armedStop: new Set(), open: null, missing: null, q: "", ended: false, endedBySearch: false, results: new Set(), full: new Set(), failed: new Map(), stopping: new Set(), drafts: {}, sending: new Set(), hold: false, order: [], looks: new Map(), answered: new Map(), leftover: {}, menu: null, choices: {}, moving: new Map(), held: {}, noted: new Map(), logins: {}, pinning: null,
  // what a press just opened, so only that lands with motion on the next draw, and figures that changed move to their new value
  shown: new Map(), landing: new Set(), figs: new Map(), grow: new Set(), lopen: new Set(), canSwitch: {},
  // which groups he has folded, and whose Ended line he has opened: his, across a reload, until he closes the tab
  shut: new Set(JSON.parse(sessionStorage.getItem("splice.shut") || "[]")), rest: new Set(JSON.parse(sessionStorage.getItem("splice.rest") || "[]")),
  idle: new Set(JSON.parse(sessionStorage.getItem("splice.idle") || "[]")), grouped: false, gorder: null, project: PICK.get(), by: sessionStorage.getItem("splice.by") || "project", pmenu: false,
  // how much room the open session's details take: his choice in this browser, across tabs and reloads, open at first
  topShut: localStorage.getItem("splice.sessions.top") === "shut",
  // the runs of calls he opened past their newest three, by the keys of the calls each one hid
  trails: new Set(),
  // when each row's state began, kept across reads, so a working row's time never runs backwards between two requests
  since: new Map(),
  // conversations opened past their newest items, the client's texts he opened, the copy lines he opened, a Copy that
  // said Copied, and the ended namesakes folded under each row
  logOpen: new Set(), sys: new Set(), lines: new Set(), namesakes: new Set(), allModels: new Set(), copied: new Map(), earlier: new Map(),
  // the session whose plate just opened (it unfolds once), whether to bring it on screen, and the board's columns
  unfold: null, reveal: false, cols: 1, blockAt: null, focusSay: false,
  // the names he gave sessions here (kept in this browser), the session whose name he is typing, and the projects with nothing
  // live whose ended sessions he opened
  names: loadNames(), renaming: null, looseOpen: new Set(JSON.parse(sessionStorage.getItem("splice.loose") || "[]")) };
const remember = (key, set) => sessionStorage.setItem(key, JSON.stringify([...set]));
/** THE NAMES HE GAVE (DRAW.nameIt): splice keeps the name a client registered and has no route to change it, so a name he
 *  types here is kept in this browser, by the session's id, and wins over the registered one on this page. */
function loadNames() {
  try { return new Map(Object.entries(JSON.parse(localStorage.getItem(NAMES_KEY) || "{}"))); } catch { return new Map(); }
}
function keepName(id, name) {
  if (name) ui.names.set(id, name); else ui.names.delete(id);
  try { localStorage.setItem(NAMES_KEY, JSON.stringify(Object.fromEntries(ui.names))); } catch { /* storage off: the name lasts until the tab closes */ }
}
const root = document.getElementById("sessions");

/** A refused call's word on the page (fin, Oct 13): [word] for a refusal, "Not answering" when splice did not answer at
 *  all, and the daemon's sentence, or the status it answered, to the console's log under [what], never on the screen. */
function refusedAs(answer, word, what) {
  console.warn(`${what}: ${answer.status === 0 ? "splice is not answering" : answer.body?.error || `splice answered ${answer.status}`}`);
  return answer.status === 0 ? "Not answering" : word;
}

// ---------- reading ----------
async function read() {
  tick();
  const [sessions, heads, models, retired] = await Promise.all([API.get("/api/sessions"), API.get("/api/heads"), API.get("/api/models"),
    API.get("/api/heads/retired")]);
  state.reading = sessions.body?.reading !== false; // off: no question text, no choices, answered in the terminal
  state.noteVersions = new Set(sessions.body?.note_versions || []); // the Claude Code versions a note is known to reach
  // A FAILED READ KEEPS THE ROWS IT HAD (gap: the last rows read were dropped, and the list he was reading vanished
  // behind one word). The rows stay, dimmed, under a line that says splice did not answer and when it last did.
  state.error = sessions.ok ? null : refusedAs(sessions, "Not read", "/api/sessions");
  if (sessions.ok) state.readAt = Date.now();
  state.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key, reanchorMs: h.gate?.stall_reanchor_ms ?? null, idleMs: h.gate?.stream_idle_ms ?? null, firstByteMs: h.gate?.first_byte_timeout_ms ?? null, authKind: h.authKind ?? null, cannotStart: h.cannotStart ?? null }));
  // kept apart from the live commands: nothing runs on a removed one, so Stop, a move and the live reads never ask it
  state.retired = retiredHeads(retired, state.heads);
  for (const row of models.body?.heads || []) {
    state.providerOf[row.head] = PROVIDER_FAMILY[row.provider] ?? row.provider;
    learnModels(state.modelLabel, row);
    // the room a model's conversation has, for the open session's context meter: the window the catalog declares for it
    for (const m of row.models || []) if (m.id && m.context_window) state.window[m.id] = m.context_window;
  }
  // the turns running now, by session: what Stop ends, and the model a session is on while one runs
  const lives = await Promise.all(state.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/turns/live`)));
  state.live = {}; state.waiting = {};
  state.heads.forEach((h, i) => {
    for (const t of lives[i].body?.turns || []) if (t.session && !t.stopped) state.live[t.session] = { head: h.key, ...t, readAt: Date.now() };
    // turns the gate queued and has not let in: the session's wait, in heldFor's shape (kit workingLook)
    for (const w of lives[i].body?.waiting || []) if (w.session) state.waiting[w.session] = { age_ms: w.waited_ms, readAt: Date.now() };
  });
  if (sessions.ok) state.rows = (sessions.body?.sessions || []).filter((s) => s.session_id).map(sessionOf);
  else state.rows = state.rows.map((s) => sessionOf(s.row)); // the read failed: the rows he has, read again off what we hold
  joinPast();
  // an answer that landed shows as going until the session stops waiting, or for half a minute at most
  for (const [id, going] of ui.answered) if (find(id)?.state !== "needs" || Date.now() - going.at > ANSWER_HOLD_MS) ui.answered.delete(id);
  state.loading = false;
  await readScreens();
}

// ---------- the agents that have ended: Claude Code keeps every conversation, and splice lists them from it ----------
// The registry holds only the agents its client still reports, so a week's ended agents come from history
// (GET /api/sessions/history): its newest page joins the Ended line, a search asks it too, and a door to an agent the
// list does not hold looks there before it says No transcript (Marlin, Oct 12: from a request, the agent opens, running
// or ended). A history row is the same row shape as a live one; it is drawn as ended, since nothing of it runs.
const HISTORY_PAGE = 50;
const UNKNOWN_HEAD = "unknown head";
function joinPast() {
  const live = new Set(state.rows.map((s) => s.id));
  for (const [id, row] of state.past) if (!live.has(id)) state.rows.push(sessionOf(row));
}
async function readPast(query = "") {
  const q = query ? `&query=${encodeURIComponent(query)}` : "";
  const res = await API.get(`/api/sessions/history?limit=${HISTORY_PAGE}${q}`);
  if (!res.ok || res.body?.state === "off") return [];
  const rows = (res.body?.sessions || []).filter((r) => r.session_id && r.availability !== "live" && r.availability !== "stale")
    .map((r) => ({ ...r, availability: "gone", head: r.head === UNKNOWN_HEAD ? null : r.head }));
  for (const r of rows) state.past.set(r.session_id, r);
  const known = new Set(state.rows.map((s) => s.id));
  for (const r of rows) if (!known.has(r.session_id)) state.rows.push(sessionOf(r));
  return rows;
}

/** The screens of the sessions that need him and of the open one: which splice can drive, and what each is offering. */
async function readScreens() {
  const ids = state.rows.filter((s) => s.state !== "ended" && (s.state === "needs" || s.id === ui.open)).map((s) => s.id);
  const read = await Promise.all(ids.map((id) => API.get(`/api/sessions/${encodeURIComponent(id)}/screen`)));
  const next = {};
  ids.forEach((id, i) => { if (read[i].ok) next[id] = read[i].body; });
  state.screens = next;
}
const ANSWER_HOLD_MS = 30_000;
const drivable = (s) => s.state !== "ended" && Boolean(state.screens[s.id]);
// A SESSION SPLICE DID NOT OPEN TAKES A NOTE (V4-444): a message from another session on its own socket, never his typing,
// so it cannot approve or run anything. Only a running client of a version the note is known to reach is offered one.
const notable = (s) => s.state !== "ended" && !drivable(s) && Boolean(s.row.address) && state.noteVersions?.has(s.row.version);
/** What another session types to reach this one (the spec's copyable SendMessage, §4): its name, else its socket. */
const sendTo = (s) => (s.row.availability !== "live" ? null : s.row.name?.trim() || s.row.address || null);
const sendLine = (s) => `SendMessage(to="${sendTo(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"')}")`;

// a session splice cannot place reads "unknown head" (SessionsRoutes UNKNOWN_HEAD) and the card says so, as the spec's
// Sep 13 Resolved block has it (§4 reading, Oct 11); a head key no configured command has names nothing
// it reads as a quiet fact in the chip's slot, never as a chip: a chip claims a command (hitstop)
const UNPLACED = "unknown head";
// a session that never went through splice has no command to name: its row's route says direct, and the card says
// "direct", the spec's word for it (Reading, Oct 10), never "unknown head" (Oct 11: a bridge that talks to splice)
const DIRECT = "direct";
const QUIET = new Set([UNPLACED, DIRECT]);
// THE WIRE'S WORDS ARE NOT HIS WORDS (the words pass, Oct 26): "direct" and "unknown head" are the route and the head
// key as the daemon writes them, and both stood in the command chip's place. The fact each one carries, said in the
// words the page uses everywhere else: one the command column draws as an empty command, dashed, since there is no
// command to name.
const QUIET_WORD = { [DIRECT]: "Not through splice", [UNPLACED]: "Command unknown" };
const commandOf = (head) => (state.heads.find((h) => h.key === head) ?? state.retired.find((h) => h.key === head))?.command
  ?? (head === UNPLACED ? head : null);
const cmdOf = (row) => (row.route === DIRECT ? DIRECT : commandOf(row.head));
const folderOf = (row) => (row.repo?.root || row.cwd || "").split("/").filter(Boolean).pop() || "";

/** A signed-out session's way back: Accounts on its command, in Accounts' own word for that kind of credential. */
const fixOf = (stall, head) => (stall?.kind !== "signout" || !head ? stall
  : { ...stall, fix: { href: `accounts.html?head=${encodeURIComponent(head)}`, word: state.heads.find((h) => h.key === head)?.authKind === "api-key" ? "Add key" : "Sign in" } });

/** One /api/sessions row in the drawing's session shape: the state the kit's look() reads, and what the card shows. */
function sessionOf(row) {
  const turn = state.live[row.session_id] ?? null;
  const asked = (row.last?.asks || [])[0];
  // the one reading Teams and Requests rank projects by (kit rowLook)
  const { state: st, stall } = rowLook(row, turn, state.heads.find((h) => h.key === turn?.head));
  const s = {
    // a linked worktree is named by its own folder, never its whole path, which ran past the card's edge (splice-usage, Oct 13)
    id: row.session_id, row, name: row.name || null, first: row.title || null, repo: folderOf(row), wt: row.repo?.worktree?.split("/").filter(Boolean).pop() || null,
    project: row.project ?? null, // as the daemon resolved it (ProjectWire); absent is "No project", never a guess here
    head: row.head, cmd: cmdOf(row), provider: state.providerOf[row.head] ?? null,
    model: modelName(state.modelLabel, turn?.model ?? row.model ?? row.served_model), // a running turn's model is the one in use now; reading off: what splice served it
    team: row.team_name ?? null, state: st, live: st === "working" && Boolean(turn), turn, wait: state.waiting?.[row.session_id] ?? null, stall: fixOf(stall, row.head),
    ask: st === "needs" ? { kind: asked ? "input" : "dialog", asked, call: row.last } : null,
    at: new Date(rowAt(row)),
  };
  s.since = sinceOf(s);
  return s;
}

// ---------- how long a row has been in the state it is in ----------
// THE STATE A ROW IS IN BEGAN ONCE, and its time counts from then. A working session's requests come and go inside one
// spell of work: the live turn is the request in flight, seconds old, and between two requests splice holds none, so a
// time read off the turn and a time read off the row's last activity took turns, and the figure jumped between seconds
// and minutes on every read (round 2's bug list). The start is the earliest the page knows for the spell (the request's
// own start, the status's last change), and it never moves later while the row stays in that state.
const stateKey = (s) => (stalled(s) ? `stalled:${s.stall.kind}` : s.state);
function sinceOf(s, now = Date.now()) {
  const key = stateKey(s), statusAt = Date.parse(s.row.status_updated_at ?? "");
  const fromTurn = (t) => (t?.age_ms != null ? (t.readAt ?? now) - t.age_ms : NaN);
  let at = NaN;
  if (key.startsWith("stalled") && s.stall?.kind === "silent") at = s.stall.at - s.stall.ms;
  else if (s.stall?.since != null) at = s.stall.since;
  else if (s.state === "working") at = Math.min(...[fromTurn(s.turn?.phase != null ? s.turn : null), fromTurn(s.wait), statusAt].filter(Number.isFinite));
  else if (s.state !== "ended") at = statusAt;
  if (!Number.isFinite(at)) at = +s.at;
  if (!Number.isFinite(at)) at = now;
  const was = ui.since.get(s.id);
  if (was && was.key === key && was.at < at) at = was.at;
  ui.since.set(s.id, { key, at });
  return Math.min(at, now);
}

// A READ ALREADY ON ITS WAY IS NEVER ASKED AGAIN. The browser holds six connections to splice; every 5 s tick fired the
// whole page's reads again while the last were still waiting on a slow /api/sessions, so they queued without end and
// an opened conversation sat on Reading for 98 and 126 s behind them (splice-usage's walk, Oct 13).
const inFlight = new Set();
async function alone(key, read) {
  if (inFlight.has(key)) return;
  inFlight.add(key);
  try { await read(); } finally { inFlight.delete(key); }
}

const NOT_READ = "Not read";
// A CONVERSATION OPENS ON ITS NEWEST 50 MESSAGES, and Show earlier reads the page before. 200 sent 0.3 to 1.5 MB of
// messages, tool results whole, on every 5 s read of a live session (splice-sessions, Oct 13).
const LOG_PAGE = 50;
/** The conversation, from its newest messages; Show earlier reads the page before, [limit] messages of it. */
const readLog = (s, before = "end", limit = LOG_PAGE) => alone(`log ${s.id} ${before}`, () => readLogNow(s, before, limit));
async function readLogNow(s, before, limit = LOG_PAGE) {
  const res = await API.get(`/api/sessions/${encodeURIComponent(s.id)}/transcript?before=${encodeURIComponent(before)}&limit=${limit}`);
  const held = state.logs[s.id];
  // A PAGE BEFORE JOINS ONLY THE CONVERSATION IT WAS ASKED FOR. One read again from its end meanwhile starts at another
  // message, and this page would join it across the messages between them, with nothing on screen saying they are missing.
  const back = before !== "end" && !String(before).startsWith("at:");
  if (back && held?.messages && String(held.earlier) !== String(before)) return;
  if (!res.ok) {
    // gone reads No transcript and a failed read Not read; the daemon's own words go to the console, never the page (fin)
    if (res.status !== 404) refusedAs(res, NOT_READ, `conversation ${s.id}`);
    state.logs[s.id] = held && before !== "end" ? { ...held, error: NOT_READ }
      : { messages: null, error: res.status === 404 ? null : NOT_READ };
  } else if (res.body?.state === "off") {
    state.logs[s.id] = { messages: null, off: true }; // reading off: splice does not read his conversations
  } else if (!Array.isArray(res.body?.messages)) {
    // an answer with no messages in it reads Not read: taking it as a page threw before anything was kept, which left
    // the pane on Reading for good (splice-usage, Oct 13: 180 s on one session)
    console.warn("The conversation came back with no messages in it");
    state.logs[s.id] = held && before !== "end" ? { ...held, error: NOT_READ } : { messages: null, error: NOT_READ };
  } else {
    const page = res.body;
    const messages = before === "end" || !held?.messages ? page.messages : [...page.messages, ...held.messages];
    state.logs[s.id] = { messages, path: page.path ?? held?.path ?? null, earlier: page.earlier ?? null, extended: before !== "end", at: String(before).startsWith("at:") || (held?.at && before !== "end"), moves: page.moves ?? held?.moves ?? [], unmoved: page.unmoved ?? held?.unmoved ?? [],
      unreadable: (before === "end" ? 0 : held?.unreadable ?? 0) + (page.unparseable_lines ?? 0) };
  }
  render();
}
// A LIVE CONVERSATION'S POLL ASKS ONLY FOR WHAT IS NEWER: its last message again, which may have grown, and what came
// after it, a few KB where a read from the end sent its whole page every five seconds (splice-runtime-lead, Oct 13). It
// keeps the earlier pages he read back. More than a page behind, a cursor the daemon refuses or reading turned off
// reads from the end.
const readNewer = (s) => alone(`log ${s.id} newer`, () => readNewerNow(s));
async function readNewerNow(s) {
  const shown = state.logs[s.id]?.messages, last = shown?.at(-1);
  if (!last) return readLogNow(s, "end");
  // A message of his is taken back when a later one of his answers the same reply before anything answers it, and the
  // daemon marks it only on a page holding both. So the poll reads again from his first message since the last reply,
  // the oldest one a poll can still change, and a Stop shows within one poll; with none, from the last message.
  const replied = shown.findLastIndex((m) => m.role === "assistant");
  const open = shown.slice(replied + 1).find((m) => m.role === "user") ?? last;
  const res = await API.get(`/api/sessions/${encodeURIComponent(s.id)}/transcript?cursor=${encodeURIComponent(`after:${open.index}`)}&limit=${LOG_PAGE}`);
  const held = state.logs[s.id];
  if (res.status === 400 || res.body?.more || res.body?.state === "off") return readLogNow(s, "end");
  if (!res.ok || !Array.isArray(res.body?.messages) || !held?.messages) return;
  const page = res.body, from = page.messages[0]?.index ?? Infinity;
  const kept = held.messages.filter((m) => m.index < from);
  if (JSON.stringify(held.messages.slice(kept.length)) === JSON.stringify(page.messages)) return; // nothing new
  state.logs[s.id] = { ...held, messages: [...kept, ...page.messages], moves: page.moves ?? held.moves, unmoved: page.unmoved ?? held.unmoved };
  render();
}

// ---------- what the page says about a session (look() and the lamp are kit.js's) ----------
// A SESSION IS CALLED BY WHAT HE WOULD CALL IT (Marcos, Oct 28: names are the user's words, never a machine id): the name he
// gave it here, else the name its client registered, else the first line of its first message as the daemon read it
// (SessionsRoutes `title`), then its worktree, then its folder where the page is flat. A name a client built from an id
// ("codex-1f2e3d4c-...") is an id however it is spelled, and the kit's rule reads a word and a whole UUID as a name; a
// session nothing can name reads as its command and project, with an ordinal where two would read alike.
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const registered = (name) => { const own = DRAW.ownName(name); return own && !UUID.test(own) ? own : ""; };
/** Words a person can read: a leading tag that carries an id ("[eli-dispatch 1f2e3d4c]") and any UUID come out. */
const readable = (text) => String(text ?? "").replace(/^\s*\[[^\]]*[0-9a-f]{6,}[^\]]*\]\s*/i, "").replace(UUID, "").replace(/\s+/g, " ").trim();
const ownOf = (s) => ui.names.get(s.id) || registered(s.name);
const label = (s) => ownOf(s) || (ui.grouped ? readable(s.first) || (s.wt && !ID_PART.test(s.wt) ? s.wt : "") : s.repo) || readable(s.first);
const generic = (s) => ({ command: QUIET.has(s.cmd) ? "" : s.cmd, project: projName(s) });
const title = (s) => label(s) || ui.generic?.get(s.id) || DRAW.name("session", generic(s)).text;
/** The sessions still running that nothing names, told apart by an ordinal in the order the client lists them: ended ones
 *  by the hundred would number into noise, so they keep the plain reading. */
function nameRows() {
  const lone = state.rows.filter((s) => s.state !== "ended" && !label(s));
  const named = DRAW.names("session", lone.map(generic));
  ui.generic = new Map(lone.map((s, i) => [s.id, named[i].text]));
}
const color = (s) => colorOf(s.provider);

const FOLDER = G('<path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>');
/** Where a session is, said once and in the words the page already uses for it (Marlin, Oct 12):
 *  - the open session names its project, the same name its group wears, never the folder ("repo" for most of his);
 *  - a session in no project names its folder from home with the folder glyph, on its card too, because there the
 *    folder is the only clue; `place` is the daemon's spelling of it ("~/notes"), and until a row carries one the
 *    folder's own name stands in, which is what the card said before projects;
 *  - a card in a group says nothing more, since the header already says it; flat, a named card keeps its folder. */
/** A folder in a row's own width: whole while it fits, else its last two parts, so the folder's own name is what
 *  survives rather than the home prefix every path shares. A part that is an id reads as an ellipsis: it names
 *  nothing he would know the folder by, and the whole path is the folder's title. */
const ID_PART = /^(?:[0-9a-f]{8,}|[0-9a-f]{8}-[0-9a-f-]{4,}|.*[0-9a-f]{12,}.*)$/i;
const placeWords = (place) => String(place).split("/").map((p) => (ID_PART.test(p) ? "…" : p)).join("/").replace(/(…\/)+…/g, "…");
/** A folder on a row or in a reason is its own name, which is what he knows it by, never a stretch of its path (the path is
 *  the mark's hover and the open session's facts); the home folder itself reads Home. */
function shortPlace(place) {
  const said = placeWords(place);
  const named = said.split("/").filter((p) => p && p !== "…" && p !== "~");
  return named.length ? named[named.length - 1] : said.startsWith("~") ? "Home" : said;
}
function whereHtml(s, inPlate) {
  if (s.project) return inPlate ? `<span>${esc(projName(s))}</span>` : (!ui.grouped || ui.by !== "project") && s.name && s.repo ? `<span>${esc(s.repo)}</span>` : "";
  const place = s.row.place || s.repo;
  if (!place) return "";
  // a folder too long for its cell breaks after a slash, never inside a folder's name
  if (inPlate) return `<span class="where" title="${esc(place)}">${FOLDER}<span>${esc(placeWords(place)).replace(/\//g, "/<wbr>")}</span></span>`;
  return ui.grouped ? `<span class="where" title="${esc(place)}">${esc(shortPlace(place))}</span>` : s.name ? `<span title="${esc(place)}">${esc(shortPlace(place))}</span>` : "";
}
/** The command a session runs on, as an object: its chip with the live dot. One that never went through splice, and one
 *  splice cannot place, have no command to name, so the column draws an empty command with the fact in it. Grouped by
 *  command, the block's own header names it, so the row keeps only the live dot. */
// ON A ROW the empty command is a mark, not three words on every such row: its arc goes round splice, its question mark
// is a command splice cannot place, and the head line's legend says each once for the page. The open session's facts,
// and the mark's own name for a screen reader, keep the words.
const QUIET_MARK = {
  [DIRECT]: `<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M2 12C3.5 5 12.5 5 14 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-dasharray="2.2 2.4"/><circle cx="8" cy="12" r="1.6" fill="currentColor"/></svg>`,
  [UNPLACED]: `<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M5.6 6a2.5 2.5 0 1 1 3.4 2.3c-.7.3-1 .8-1 1.5v.6" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><circle cx="8" cy="12.6" r="1" fill="currentColor"/></svg>`,
};
const quietMark = (cmd) => `<span class="nocmd mark" data-q="${cmd === DIRECT ? "d" : "u"}" role="img" aria-label="${esc(QUIET_WORD[cmd])}" title="${esc(QUIET_WORD[cmd])}">${QUIET_MARK[cmd]}</span>`;
/** The head line's legend for the empty command's marks the list draws now, each said once for the page: render reads
 *  which marks it drew, and draws the legend again when that changed. */
function quietLegend() {
  const kinds = [DIRECT, UNPLACED].filter((k) => (ui.quietSeen || "").includes(k === DIRECT ? "d" : "u"));
  return kinds.map((k) => `<span class="axleg quietleg">${QUIET_MARK[k].replace("<svg", '<svg class="qm"')}<span>${esc(QUIET_WORD[k])}</span></span>`).join("");
}
function cmdCell(s, cls = "", row = false) {
  if (QUIET.has(s.cmd)) return row ? quietMark(s.cmd) : `<span class="nocmd">${esc(QUIET_WORD[s.cmd])}</span>`;
  if (!s.cmd) return DRAW.dash;
  if (ui.grouped && ui.by === "head") return s.live ? `<span class="livedot" role="img" aria-label="Running now"><i></i></span>` : "";
  return DRAW.chip({ text: s.cmd, live: s.live, cls });
}
/** THE STATE SLOT: only what the lamp cannot say. The lamp already draws working, idle, ended, needs you and a stall,
 *  and the duration column draws how long; what stands here is the fact neither carries — the queue, a compaction, a
 *  silence nothing will resume, which account's limit holds it, a sign-out, a move in flight, the words splice gave up
 *  on. Nothing at all for a plain Working, Idle or Ended row, where the word only repeated the lamp. Open, the session
 *  says the rest its row has no room for: when a limit lifts, that its client has gone quiet. */
function stateCell(s, L, inPlate = false) {
  const t = s.turn, k = s.stall?.kind;
  const words = [];
  if (ui.moving.has(s.id)) words.push(`<span class="word moving">Continuing on</span>${cmdChip(ui.moving.get(s.id).head)}`);
  else if (stranded(s)) words.push(`<span class="word needs">Not running</span>`);
  else if (k === "limit") words.push(`<span class="word limit">${ICON.lock}At limit</span>${s.stall.acct ? acctPlanHtml(s.stall.acct, s.stall.plan) : ""}`
    + (inPlate && s.stall.until ? `<span class="detail soft">Back ${esc(backWord(s.stall.until))}</span>` : ""));
  else if (k === "signout") words.push(`<span class="word limit">Signed out</span>`);
  else if (k === "gaveup") words.push(`<span class="word ${gaveUpNeeds(s) ? "needs" : "limit"}">${esc(s.stall.word)}</span>`
    + (s.stall.n ? `<span class="detail ${gaveUpNeeds(s) ? "retried" : ""}">${ICON.retries}${s.stall.n}</span>` : ""));
  else if (k === "retries") words.push(`<span class="word limit">${ICON.retries}${s.stall.n} ${s.stall.n === 1 ? "retry" : "retries"}</span>`);
  else if (k === "silent" && s.stall.wontResume) words.push(`<span class="word limit">Won't resume</span>`);
  else if (resuming(s)) words.push(`<span class="detail resumes" data-resume="${esc(s.id)}">${ICON.watch(Math.floor(s.stall.resumeMs / 1000))}<span>Resumes in ${counter(s.stall.resumeMs)}</span></span>`);
  // a silence nothing will end on its own: on a row the flat red lamp says it, and the open title line says it in a word
  else if (inPlate && k === "silent") words.push(`<span class="word limit">Stalled</span>`);
  else if (s.state === "working" && t?.phase == null && s.wait) words.push(`<span class="word">In line</span>`);
  else if (s.state === "working" && t?.compact) words.push(`<span class="word">Compacting</span>`);
  // waiting for the model's first output: three dots that pulse on a row (its words on hover and for a screen reader), the
  // words on the open title line, where there is room
  else if (s.state === "working" && t?.phase === "connect") words.push(inPlate ? `<span class="detail soft">No output yet</span>`
    : `<span class="detail soft awaiting" role="img" aria-label="No output yet" title="No output yet"><i></i><i></i><i></i></span>`);
  else if (inPlate && s.row.availability === "stale" && !s.turn) words.push(`<span class="detail soft">Not heard from</span>`);
  // a stop in flight leads the words, while the wait ring alone holds Stop's place in the act track
  if (ui.stopping.has(s.id)) words.unshift(`<span class="word">Stopping</span>`);
  // a row with nothing the lamp cannot say draws nothing, so the state costs no room on the rows that have none
  return words.length ? `<span class="stw ${L.cls}">${words.join("")}</span>` : "";
}
/** A duration in ONE spelling, short enough for a column: seconds, minutes, hours, days, weeks. */
function spanWord(ms) {
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec} s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min} min`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} h`;
  const day = Math.floor(hr / 24);
  return day < 7 ? `${day} d` : `${Math.floor(day / 7)} w`;
}
/** How long the row has been in its state: from when that state began (sinceOf). */
const spanOf = (s, now = Date.now()) => Math.max(0, now - s.since);
// ONE FIXED LOG AXIS, 1 s TO 4 w (Marcos: "show, don't tell"): the bar's length is the time in state on a scale no data
// moves, so 12 s and 3 d read as lengths on one line and the same length is the same time on every row and every visit.
// Its ticks stand under every bar and in the legend, so a length is read against them without a figure (the kit's axis).
const AXIS = DRAW.timeAxis;
/** The duration cell: the bar on the one axis and the figure after it, so every row's figure ends on one edge. */
function spanCell(s) {
  const ms = spanOf(s), level = s.state === "needs" || gaveUpNeeds(s) ? "watch" : stalled(s) ? "bad" : "";
  return `<span class="barcell age timed${level ? ` lv-${level}` : ""}"${s.state === "ended" ? "" : ` data-span="${esc(s.id)}"`}>`
    + `${DRAW.vbar(AXIS.at(ms))}<b class="v">${spanWord(ms)}</b></span>`;
}

// What a waiting session asks, and answering it where it waits. A question's words and options come off its transcript
// (`last.asks`); a permission's choices belong to the client's version, so they are read off its screen or not drawn.
// Each answer is the digit the person would press (POST .../answer). A session splice did not open can't be answered
// from here: its options are drawn as words, and its act track says where it is answered instead.
// THE TERMINAL'S FRAME IS NOT THE COMMAND (gap: box-drawing characters shown as text). A client draws its prompt in a
// box; what it would run is the text inside it, so the frame's rules and corners come off before the words are shown.
const FRAME = /[─-╿▀-▟]/g;
const unframe = (text) => String(text ?? "").replace(FRAME, "").replace(/[ \t]+$/gm, "").trim();
/** A long run, bounded: the first lines, then the rest behind one pill, so the decision never sits a screen below its
 *  question. Nothing is approved blind — the whole command is one press away, and it is the same box. */
const CMD_LINES = 6;
function cmdBox(id, text, cls = "cmd") {
  const lines = String(text).split("\n"), open = ui.full.has(id);
  if (lines.length <= CMD_LINES) return `<p class="${cls}">${esc(text)}</p>`;
  const shown = open ? text : lines.slice(0, CMD_LINES).join("\n");
  return `<p class="${cls}">${esc(shown)}</p>`
    + DRAW.more(lines.length - CMD_LINES, { word: "more lines", one: "more line", act: "full", data: ` data-k="${esc(id)}"`, open, less: "Fewer lines" });
}
function runHtml(s, call, offer) {
  const panel = offer?.panel || [], at = panel.findIndex((l) => l.framed);
  if (at < 0) return "";
  const run = unframe(panel.filter((l) => l.framed).map((l) => l.text).join("\n")), why = at > 0 ? unframe(panel[at - 1].text) : "";
  // the tool in the words its own user would say (kit DRAW.toolWord); with no call, the screen's first line
  return `${why ? `<p class="why">${esc(why)}</p>` : ""}`
    + `<p class="cmd head"><span class="tname">${esc(call?.tool ? DRAW.toolWord(call.tool) : unframe(panel[0].text))}</span></p>${cmdBox(`c:${s.id}`, run)}`;
}
const READING_OFF_ASK = "Answer it in the terminal it runs in";
function askHtml(s, where = "card") {
  // from his press until the session moves on, the answer he pressed keeps its words with the wait ring and the rest
  // wait disabled, drawn from the choices he saw: never "answer it in the terminal" meanwhile (Marlin p165, hitstop 8a04273)
  const going = ui.answered.get(s.id);
  // reading off: he told splice not to read his conversations, and a question and its choices are the conversation, so
  // the card says only that it waits and where to answer it (Marlin, 5:12 AM CT), ending on a quiet door to the switch
  if (!state.reading) return `<div class="ask"><p class="why">${READING_OFF_ASK}${where === "pane" ? "" : ` · <a class="door" href="settings.html#data">Reading off${ICON.door}</a>`}</p></div>`;
  const a = s.ask, offer = going?.offer ?? state.screens[s.id], can = going ? true : drivable(s);
  const busy = going || ui.sending.has(s.id) ? " disabled" : "";
  // a question splice cannot answer is answered in the terminal it runs in: said once, on the question itself
  const term = can ? "" : `<p class="interm">${TOOL_GLYPH.term}In its terminal</p>`;
  const pick = (choice, label, i) => going?.choice === choice
    ? `<button class="act answering" disabled>${ICON.wait}${esc(label)}</button>`
    : `<button class="act${i ? "" : " primary"}" data-act="answer" data-s="${esc(s.id)}" data-i="${choice}"${busy}>${esc(label)}</button>`;
  if (a.asked) {
    const opts = a.asked.options || [];
    // where splice cannot answer, the options are the numbered words he will choose from in its terminal, drawn as text and
    // never as buttons that do nothing; where it is answered is said once, under them
    const answers = !opts.length ? "" : can && !a.asked.multi ? `<div class="answers">${opts.map((o, i) => pick(i + 1, o, i)).join("")}</div>`
      : `<ol class="opts">${opts.map((o, i) => `<li><b>${i + 1}</b>${esc(o)}</li>`).join("")}</ol>`;
    return `<div class="ask"><p class="q">${esc(a.asked.question)}</p>${answers}${term}</div>`;
  }
  // what it wants to do, off its transcript, then the question its screen puts
  const call = a.call?.tool ? `<p class="cmd head"><span class="tname">${esc(DRAW.toolWord(a.call.tool))}</span></p>${cmdBox(`c:${s.id}`, unframe(a.call.text || ""))}` : "";
  const q = offer?.asked ? `<p class="q">${esc(unframe(offer.asked))}</p>` : "";
  if (!offer?.choices?.length) return call ? `<div class="ask">${call}${term}</div>` : "";
  // in the open session the refusal is the Message field's Deny, which also carries what to do instead
  const deny = where === "pane" && composerHtml(s) ? denyOf(s) : null;
  const choices = offer.choices.filter((c) => c !== deny);
  // the whole prompt its screen drew, when it drew one: the tool, what it runs, and why, so nothing is approved blind
  const said = runHtml(s, a.call, offer) || call + q;
  return `<div class="ask">${said}<div class="answers">${choices.map((c, i) => pick(c.choice, c.label, i)).join("")}</div></div>`;
}

const lastOf = (s) => s.row.last;
// From the press until splice answers, Stop keeps its place, disabled, reading Stopping with the wait ring (hitstop
// 9e95ae4). [short]: on a row the wait ring alone holds the button's place, named for assistive tech, and the word
// Stopping leads the row's words (stateCell)
const stopHtml = (s, cls, icon, short = false) => ui.armedStop.has(s.id) && canStop(s) ? "" : ui.stopping.has(s.id)
  ? `<button class="${cls} stopping" disabled${short ? ` aria-label="Stopping" title="Stopping">${ICON.wait}` : `>${ICON.wait}Stopping`}</button>`
  : canStop(s) ? `<button class="${cls}" data-act="stop" data-s="${esc(s.id)}">${icon}Stop</button>` : "";
const canStop = (s) => s.state === "working" && (s.live || drivable(s));
// Stop armed, End's pattern (Marlin, Oct 13): the first press names the turn it would end, from what the session last
// did, and only the second press stops it; Keep running, Escape or the turn ending on its own disarms it.
function armedStopHtml(s, small) {
  if (!ui.armedStop.has(s.id) || !canStop(s)) return "";
  const last = lastOf(s), sz = small ? " small" : "";
  const did = last?.text ? (last.tool ? `${DRAW.toolWord(last.tool)} ${last.text}` : last.text.replace(/`/g, "")) : "";
  const turn = did ? `Ends the turn on ${did.length > 80 ? `${did.slice(0, 79)}…` : did}` : "Ends the running turn";
  return `<div class="armed end"><span class="said">${ICON.wait}${esc(turn)}</span>` +
    `<button class="act danger${sz}" data-act="stop-now" data-s="${esc(s.id)}">Stop</button>` +
    `<button class="act quiet${sz}" data-act="stop-keep" data-s="${esc(s.id)}">Keep running</button></div>`;
}
/** A query's words marked where they matched, so a search says why a row is in the list. */
function hi(text) {
  const out = esc(text);
  if (!ui.q) return out;
  const at = out.toLowerCase().indexOf(esc(ui.q).toLowerCase());
  return at < 0 ? out : `${out.slice(0, at)}<mark>${out.slice(at, at + ui.q.length)}</mark>${out.slice(at + ui.q.length)}`;
}
/** THE LAMP NAMES ITS STATE (round 2's bug list): a working row and a stalled one differed only by a drawing, which
 *  says nothing without sight. Its name is the word the drawing stands for. */
const lampName = (L) => L.name ?? String(L.word ?? "").replace(/<[^>]*>/g, "").split(" · ")[0].trim();
const lampHtml = (L) => `<span class="lamp ${L.cls}" role="img" aria-label="${esc(lampName(L))}" title="${esc(lampName(L))}">${L.lamp}</span>`;
// a worktree whose folder is an id names nothing he would know it by: it reads as a worktree, its folder its title
const wtWord = (wt) => (ID_PART.test(wt) ? "Worktree" : wt);
/** WHAT A SESSION IS DOING, AS A GLYPH AND ITS TARGET (Marcos: "show, don't tell"), never a sentence cut at the row's edge: the
 *  tool it last ran as the mark its own user would know it by, its word, and the file or command it was on, whole or not
 *  at all. A sentence it last said stands only where it is short enough to stand whole; its words, whole, are on the row's
 *  title and in the conversation one press away. */
const TOOL_GLYPH = {
  term: G('<path d="M4 6l6 6-6 6M12 18h8"/>'), file: G('<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4"/>'), pen: G('<path d="M4 20l1-4L16 5l3 3L8 19z"/>'),
  find: G('<circle cx="11" cy="11" r="6"/><path d="M16 16l4 4"/>'), agent: ICON.team, send: ICON.send,
  web: G('<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18"/>'), other: G('<circle cx="12" cy="12" r="3"/>'),
};
const LAYERS = G('<path d="M12 4l9 5-9 5-9-5z"/><path d="M3 14l9 5 9-5"/>');
const toolKind = (tool) => (/bash|shell|exec|terminal/i.test(tool) ? "term" : /^(?:read|view|notebookread)/i.test(tool) ? "file" : /^(?:edit|write|multiedit|notebookedit|apply)/i.test(tool) ? "pen"
  : /grep|glob|search|find|^ls$/i.test(tool) ? "find" : /agent|task/i.test(tool) ? "agent" : /message|send/i.test(tool) ? "send" : /^web|fetch/i.test(tool) ? "web" : "other");
const SAID_FIT = 28;
/** The file a call was on by its own name, or the command or search whole where it is short; else nothing. */
function targetOf(text) {
  const line = String(text ?? "").split("\n")[0].trim();
  if (/^(?:~|\.{0,2})?\/\S*$/.test(line) || /^[\w.-]+\/[\w./-]+$/.test(line)) return line.split("/").filter(Boolean).pop() ?? "";
  return line.length <= SAID_FIT ? line : "";
}
/** What the row says it is doing is the mark and target of the call it last ran. A sentence it last said never stands in a
 *  track every row of the page shares, since one short sentence set the width of all of them: it is the row's own hover and
 *  the last words of the conversation one press away. */
function saidOf(s) {
  const last = lastOf(s);
  if (s.state === "needs" || !last?.text) return { html: "", full: "" };
  if (last.tool) {
    const word = DRAW.toolWord(last.tool), target = targetOf(last.text);
    return { full: `${word} ${last.text}`, html: `<span class="tdo"><span class="tg" aria-hidden="true">${TOOL_GLYPH[toolKind(last.tool)]}</span><span class="tname">${esc(word)}</span>${target ? `<span class="tgt">${hi(target)}</span>` : ""}</span>` };
  }
  return { full: last.text.replace(/`/g, "").replace(/\s+/g, " ").trim(), html: "" };
}
/** A way forward on the row itself: a signed-out session's sign-in or key, and a session at its limit's door to Accounts, on
 *  its command, where the login that ran out is switched or waited for. */
function doorHtml(s) {
  const k = s.stall?.kind;
  if (k === "signout" && s.stall.fix) return `<a class="act quiet small" href="${esc(s.stall.fix.href)}">${esc(s.stall.fix.word)}</a>`;
  if (k === "limit" && s.head) return `<a class="act quiet small" href="accounts.html?head=${encodeURIComponent(s.head)}">Accounts${ICON.door}</a>`;
  return "";
}
/** The name's own field, in the title line where the name stood: Enter keeps it, Escape or Cancel leaves it as it was, and an
 *  empty one gives the session back the name its client registered. */
const renameHtml = (s) => `<form class="rename" data-s="${esc(s.id)}" data-rename="1"><input class="field" id="nm" aria-label="Name this session" placeholder="${esc(title(s))}" maxlength="80" autocomplete="off" value="${esc(ui.drafts[`n:${s.id}`] ?? ownOf(s))}">`
  + `<button class="act primary small" type="submit">Save</button><button class="act quiet small" type="button" data-act="rename-no" data-s="${esc(s.id)}">Cancel</button><span class="kept">Kept in this browser</span></form>`;
/** THE ROW'S LINE, on the tracks every row of the page shares: who it is (the chevron that opens it in place, its name,
 *  its branch), its words (where it is when the block does not say, what the lamp cannot say, then what it last said
 *  or ran), how long it has been in that state as a bar on one scale, its command, and its act (Marcos, Oct 26: "the
 *  row style just does not look good"). The open session's title bar is this same line. */
function lineHtml(s, L, inPlate) {
  const stop = stopHtml(s, "act quiet small", ICON.stop, true);
  const door = stop ? "" : doorHtml(s);
  // the worktree is a mark, never a cut name: its folder is on the mark's hover and among the open session's facts
  const wt = s.wt ? `<span class="wt" role="img" aria-label="${esc(`Worktree ${s.wt}`)}" title="${esc(`Worktree ${s.wt}`)}">${ICON.branch}</span>` : "";
  const more = (ui.earlier.get(s.id) || []).length;
  const said = `${more} ended ${more === 1 ? "session" : "sessions"} with this name`;
  const also = more ? `<span class="also" role="img" aria-label="${esc(said)}" title="${esc(said)}">${LAYERS}${DRAW.n(more)}</span>` : "";
  const place = inPlate ? "" : whereHtml(s, false);
  const word = stateCell(s, L, inPlate);
  const did = saidOf(s);
  const bare = !place && !word && !did.html;
  const doing = `<p class="last${place && !word && !did.html ? " only" : ""}">${place ? `<span class="place">${place}</span>` : ""}${word}${did.html}</p>`;
  const tag = inPlate ? "header" : "div";
  const name = inPlate && ui.renaming === s.id ? renameHtml(s)
    : `<button class="name" data-open="${esc(s.id)}" aria-expanded="${inPlate}" title="${esc(title(s))}">${DRAW.chev}${hi(title(s))}</button>`;
  // NAME IT STANDS BESIDE A LIVE SESSION NOBODY NAMED, where the name is missing (the Accounts card's own place for it):
  // its press opens the session with the name's field in the title line
  const nameIt = !inPlate && s.state !== "ended" && !ownOf(s) ? DRAW.nameIt({ data: ` data-s="${esc(s.id)}"` }) : "";
  // a row with no words gives their track to its name, so no name is cut beside an empty track
  return `<${tag} class="top${bare ? " bare" : ""}"${did.full ? ` title="${esc(did.full)}"` : ""}><span class="who">${name}${also}${wt}${nameIt}</span>`
    // the command keeps its name, its live dot and its provider's colour as one 1 px edge (.chip.edge), so down a block
    // the commands read as a quiet column under the names and the words
    + `${doing}${spanCell(s)}<span class="cmd">${cmdCell(s, "edge", !inPlate)}</span><span class="doing">${stop}${door}</span></${tag}>`;
}
/** A SESSION IN THE LIST: its line, and a session that needs him opens under its line to what it asks and the answers it
 *  takes, so nothing waiting on him is a click away. */
function cardHtml(s) {
  const L = movingLook(s) || look(s);
  const failed = ui.failed.get(s.id);
  // a card whose state changed since it was last drawn rings once where it stands
  const was = ui.looks.get(s.id); ui.looks.set(s.id, L.cls);
  const quiet = s.row.availability === "stale" && !s.turn ? " quiet" : ""; // alive, but its registration stopped refreshing (§4)
  return `<article class="card s ${L.cls}${quiet}${was && was !== L.cls ? " changed" : ""}${ui.landing.has(s.id) ? " landing" : ""}" style="--c:${color(s)}" data-key="s:${esc(s.id)}" data-open="${esc(s.id)}">`
    + lampHtml(L) + lineHtml(s, L, false)
    + `${s.state === "needs" ? askHtml(s) : ""}${armedStopHtml(s, true)}${failedHtml(s, failed, false)}</article>`;
}

// ---------- the list: what needs him first, then what runs, then what rests ----------
// a session a failed move left with nothing running waits on him as Needs you does: ranked, coloured and listed with it (Marlin, 6:08 AM)
const stranded = (s) => s.state === "ended" && ui.failed.get(s.id)?.unmoved === true && !ui.failed.get(s.id).back;
// a move that failed here is this page's own fact, so it lifts the card only here; the rest is the kit's rank
const RANK = (s) => (stranded(s) ? 0 : rankOf(s));
// a project's name matches every session in it, so typing a project's name keeps that whole group rather than the one card
// whose folder happens to be spelled that way (Marlin); the open session stays on the page whatever he types
const matches = (s) => !ui.q || s.id === ui.open || [ui.names.get(s.id), s.name, s.first, s.repo, s.cmd, s.row.repo?.root, s.row.cwd, s.project?.label, s.project?.name]
  .some((v) => v && v.toLowerCase().includes(ui.q));
function sorted() { // what waits on him: the longest waiting first; the rest: the latest first
  const list = state.rows.filter((s) => (s.state !== "ended" || stranded(s)) && matches(s) && inPick(s))
    .sort((a, b) => RANK(a) - RANK(b) || (RANK(a) <= 1 ? a.at - b.at : b.at - a.at));
  if (!ui.hold) return list;
  // while he works in the list it holds the order last drawn, so a card never moves under his pointer; a new one joins
  // at the end (Marlin p163, hitstop 9e95ae4)
  const at = (s) => { const i = ui.order.indexOf(s.id); return i < 0 ? Infinity : i; };
  return list.map((s, i) => [s, i]).sort(([a, i], [b, j]) => at(a) - at(b) || i - j).map(([s]) => s);
}
/** A SESSION IS LISTED ONCE (round 2's bug list). A session moved to another command goes on as its copy under the same
 *  name, and the original ends: the list showed both, one live and one ended, as two sessions. An ended session whose
 *  name another session of its block already carries, live or ended later, folds under that one, which says how many
 *  it holds; its open session links each of them, and opens it in place under the row. Live sessions never fold: two
 *  running agents are two rows. */
function foldNamesakes(live, ended) {
  ui.earlier = new Map();
  const keyOf = (s) => { const own = ownOf(s) || readable(s.first); return own ? `${groupKey(s)}\u0000${own}` : null; };
  const firsts = new Map();
  for (const s of live) { const k = keyOf(s); if (k && !firsts.has(k)) firsts.set(k, s); }
  const kept = [];
  for (const s of ended) { // newest first
    const k = keyOf(s), rep = k && firsts.get(k);
    if (rep) { if (!ui.earlier.has(rep.id)) ui.earlier.set(rep.id, []); ui.earlier.get(rep.id).push(s); continue; }
    if (k) firsts.set(k, s);
    kept.push(s);
  }
  return kept;
}
/** A row the list must draw whatever a fold or a page would do with it: the open session, and the row the open session
 *  is folded under, since its plate is drawn there. */
const holdsOpen = (s) => s.id === ui.open || (ui.earlier.get(s.id) || []).some((x) => x.id === ui.open);
// ---------- by project (Marcos, Oct 11: "If we don't separate sessions per project, it'll be really hard to
// navigate"): the grouping serves finding the one for a project, and must never cost seeing who is stuck on him ----
// The project is the daemon's answer on the row (ProjectWire), the same one Teams and Requests read; this page never
// resolves a path itself. A session in no project is "No project", which is a group like any other but always last.
const NO_PROJECT = "-";
const projKey = (s) => s.project?.id ?? NO_PROJECT;
const projName = (s) => s.project?.label || s.project?.name || "No project";
// BY COMMAND OR BY TEAM (gaps 420 and 422; web-console.md:380 and :591): the same groups, headers and folds keyed on the
// session's command or its team instead of its project, his choice kept for the tab. The project pick and its tiles
// belong to the project grouping alone, so the other two draw headers only. Keys carry their kind, so a fold made
// under one grouping never folds a group of another.
const BY = ["project", "head", "team"];
const BY_WORDS = { project: "Project", head: "Command", team: "Team" };
const groupKey = (s) => (ui.by === "head" ? (s.head ? `h:${s.head}` : NO_PROJECT)
  : ui.by === "team" ? (s.team ? `t:${s.team}` : NO_PROJECT) : projKey(s));
// a block named for the command takes the same plain words the command column uses, never the route or head key
const groupName = (s) => (ui.by === "head" ? (s.head ? QUIET_WORD[s.cmd || s.head] || s.cmd || s.head : "No command")
  : ui.by === "team" ? s.team || "No team" : projName(s));
// ONE SET WITH ONE LIT (the drawings pass, Oct 26): the kit's segmented switch draws the choice, in neutral ink.
const byHtml = () => `<span class="by"><span class="kicker" aria-hidden="true">Group by</span>`
  + `${DRAW.switcher(BY.map((b) => [b, BY_WORDS[b]]), ui.by, "by", "Group by")}</span>`;
/** Two groups is what makes a list a grouped list: a first-time user with one project keeps the flat page. Read off
 *  every live session and never off the search, so a search that happens to land inside one project keeps the
 *  headers it had: it hides the groups it does not match, it does not undo the grouping (Marlin). */
const grouped = () => new Set(state.rows.filter((s) => s.state !== "ended" || stranded(s)).map(groupKey)).size >= 2;
// THE PICK (kit PICK): one project, or every project. It only exists where there are two groups to pick between, so a
// first-time user with one project never meets it, and a pick left over from another page's desk filters nothing here
// until there are two. A picked project that has gone keeps its pick: the list says "No sessions" and the doors stay.
const picked = () => (grouped() && ui.by === "project" ? ui.project : null);
const inPick = (s) => picked() == null || projKey(s) === picked();
/** A group where someone waits on him first, then one with a stalled card, then every other group by its name, No
 *  project last: the kit's one order (kit projectGroups), which working, idle and the last activity no longer move.
 *  While his pointer is in the list the groups hold the order they were last drawn in, as the cards do. */
function groupsOf(list) {
  const m = new Map();
  for (const s of list) { const k = groupKey(s); if (!m.has(k)) m.set(k, []); m.get(k).push(s); }
  const rank = (k) => Math.min(2, ...m.get(k).map(RANK));
  const name = (k) => groupName(m.get(k)[0]);
  const keys = [...m.keys()].sort((a, b) => (a === NO_PROJECT) - (b === NO_PROJECT)
    || rank(a) - rank(b) || name(a).localeCompare(name(b)));
  if (ui.hold && ui.gorder) { const held = (k) => { const i = ui.gorder.indexOf(k); return i < 0 ? Infinity : i; }; keys.sort((a, b) => held(a) - held(b)); }
  return keys.map((k) => [k, m.get(k)]);
}
// fin's header: the name, then one lamp and its count for each live state present, in the cards' rank, then the
// live sessions drawn and the quiet total. No state words: the lamps are the words, and Idle and Ended are in the total.
const STATES = [["needs", 0], ["stalled", 1], ["working", 2]];
const BIG = { needs: ICON.input, stalled: ICON.flat, working: ICON.trace };
const countsOf = (g) => STATES.map(([k, r]) => [k, g.filter((s) => RANK(s) === r).length]).filter(([, n]) => n);
const bigCount = ([k, n], key = "") => `<span class="pc big ${k}"><span class="lamp ${k}" aria-hidden="true">${BIG[k]}</span><b data-f="c:${esc(key)}:${k}">${n}</b></span>`;
/** THE LIVE SESSIONS OF A BLOCK OR A TILE, DRAWN: one bar of their states, needs you, stalled, working and idle, in the
 *  lamps' colours, as long as their count on a scale every block (or every tile) shares, so a project with 30 running
 *  reads as one beside a project with 2 before a digit is read. */
const MIX = [["needs", "needs you"], ["stalled", "stalled"], ["working", "working"], ["idle", "idle"]];
function mixHtml(g, scale) {
  const n = MIX.map(([k, word], i) => [k, word, g.filter((s) => Math.min(3, RANK(s)) === i && (s.state !== "ended" || stranded(s))).length]).filter(([, , x]) => x);
  const live = n.reduce((a, [, , x]) => a + x, 0);
  if (!live) return `<span class="pmix none" aria-hidden="true"></span>`;
  const w = scale > 0 ? Math.max(3, Math.min(100, (live / scale) * 100)) : 100;
  return `<span class="pmix" role="img" aria-label="${esc(n.map(([, word, x]) => `${x} ${word}`).join(", "))}"><span class="pmix-in" style="width:${w.toFixed(1)}%">`
    + n.map(([k, , x]) => `<i class="${k}" style="flex:${x} 1 0"></i>`).join("") + `</span></span>`;
}
const liveOf = (g) => g.filter((s) => s.state !== "ended" || stranded(s)).length;
/** A project's ended cards under one line at its end, closed until he opens it; a search opens every one, because no
 *  fold may hide a match. Its fold and its rows come apart, so the fold can sit on the group's foot line. */
// A FOLD NEVER OPENS MORE THAN A PAGE OF ROWS (gap: one click grew the page from 1,974 to 28,458 px with 608 rows in one
// column). An open fold shows its newest page, newest first, and the rest stand behind one pill that pages on.
const PAGE_ROWS = 24;
const shownOf = (key, first = PAGE_ROWS) => ui.shown.get(key) ?? first;
/** The page of [rows] a fold shows, as pieces, with the pill for what is left under it. The open session is drawn
 *  wherever it stands in the fold, so a door to it never lands behind a pill. */
function pagePieces(key, rows, first = PAGE_ROWS) {
  const n = shownOf(key, first), shown = rows.slice(0, n);
  const open = rows.slice(n).find(holdsOpen);
  if (open) shown.push(open);
  const left = rows.length - shown.length;
  return [...shown.map(rowPiece), ...(left > 0 ? [{ html: `<div class="pmore">${DRAW.more(left, { word: "more", act: "more", data: ` data-p="${esc(key)}"` })}</div>`, w: 1 }] : [])];
}
/** One row as a piece of a block: its markup, how much of a column it takes, and the plate that opens under it. The
 *  open session's own line becomes its plate's title, so its piece is the plate alone; an ended namesake he opened from
 *  a row opens under that row. A plate weighs as its row did, so opening it moves no block to another column. */
function rowPiece(s) {
  const w = s.state === "needs" ? 2.5 : 1;
  if (ui.open === s.id) return { html: "", plate: plateHtml(s), w };
  const namesake = (ui.earlier.get(s.id) || []).find((x) => x.id === ui.open);
  return { html: cardHtml(s), plate: namesake ? plateHtml(namesake) : "", w };
}
const pieceHtml = (p) => p.html + (p.plate || "");
// A GROUP SHOWS WHAT MOVES AND FOLDS WHAT RESTS (Marcos, Oct 15: "organize sessions per project a bit more efficiently").
// Needs you, stalled and working always stand, then its latest idle sessions up to a handful of rows; the idle ones past
// that fold behind "N idle" on the group's foot line, beside its Ended fold, closed until he opens it. A search opens
// it, because no fold may hide a match, and a fold he opened stays open for the tab.
const HANDFUL = 6;
const FLAT = "*";
function rowPieces(key, g, ended) {
  const up = g.filter((s) => RANK(s) < 3), idle = g.filter((s) => RANK(s) === 3);
  const room = Math.max(0, HANDFUL - up.length), hidden = idle.length - room;
  const folds = hidden > 0, open = !folds || ui.idle.has(key) || Boolean(ui.q);
  const idleRows = open ? pagePieces(`i:${key}`, idle) : [...idle.slice(0, room), ...idle.slice(room).filter(holdsOpen)].map(rowPiece);
  const idleFold = folds ? DRAW.disc({ open, act: "idle", data: ` data-p="${esc(key)}"`, cls: "pidle door", inner: `<span><b>${DRAW.n(hidden)}</b> idle</span>` }) : "";
  const restOpen = ended.length && (ui.rest.has(key) || Boolean(ui.q) || ended.some(holdsOpen));
  const rest = ended.length ? DRAW.disc({ open: restOpen, act: "rest", data: ` data-p="${esc(key)}"`, cls: "prest door", inner: `<span><b>${DRAW.n(ended.length)}</b> ended</span>` }) : "";
  const foot = idleFold || rest ? [{ html: `<div class="pfoot">${idleFold}${rest}</div>`, w: 1, foot: true }] : [];
  const rested = restOpen ? pagePieces(`r:${key}`, ended) : [];
  return [...up.map(rowPiece), ...idleRows, ...foot, ...(rested.length ? [{ html: REST_OPEN, w: 0, wrap: 1 }, ...rested, { html: REST_SHUT, w: 0, wrap: -1 }] : [])];
}
const REST_OPEN = `<div class="rested">`, REST_SHUT = `</div>`;
/** COLLAPSED, A GROUP STILL SHOWS ITS NEEDS-YOU CARDS (Marlin): nothing waiting on him is ever hidden by a fold. */
function groupBlock([key, g], ended, { loose = false } = {}) {
  const shut = ui.shut.has(key) && !ui.q, counts = countsOf(g);
  const folded = g.reduce((a, s) => a + (ui.earlier.get(s.id)?.length ?? 0), 0) + ended.reduce((a, s) => a + (ui.earlier.get(s.id)?.length ?? 0), 0);
  const total = loose ? ended.length + folded : projTotal(key, g);
  const name = groupName(g[0] ?? ended[0]);
  // the door to this project's requests: the arrow it has everywhere else, with the name in its label rather than three
  // words repeated on every block
  const go = key === NO_PROJECT || ui.by !== "project" ? ""
    : `<a class="pgo" href="requests.html?project=${encodeURIComponent(key)}" aria-label="${esc(`${name} on Requests`)}" title="${esc(`${name} on Requests`)}">${ICON.door}</a>`;
  // its live sessions out of all of them: the count says which, once, in the head line's legend
  const tot = `<span class="ptot">${loose ? "" : `<b class="plive" data-f="l:${esc(key)}">${DRAW.n(liveOf(g))}</b><i class="sl" aria-hidden="true">/</i>`}<b class="ptotal" data-f="t:${esc(key)}">${DRAW.n(total)}</b></span>`;
  const pieces = shut ? g.filter((s) => RANK(s) === 0 || holdsOpen(s)).map(rowPiece) : loose ? pagePieces(`l:${key}`, ended, HANDFUL) : rowPieces(key, g, ended);
  const why = key === NO_PROJECT && ui.by === "project" ? noProjectWhy([...g, ...ended]) : "";
  const open = pieces.some((p) => p.plate);
  const section = (body, wide = 0) => `<section class="pgroup${shut ? " shut" : ""}${loose ? " loose" : ""}${wide ? " opened" : ""}"${wide ? ` data-cols="${wide}"` : ""} data-key="g:${esc(key)}${loose ? ":l" : ""}" aria-label="${esc(`${name}, ${countWords(counts, total, "session")}`)}">`
    + `<header class="phead"><button class="pfold" data-act="${loose ? "loose" : "fold"}" data-p="${esc(key)}" aria-expanded="${!shut}">${ICON.caret}<span class="pname">${esc(name)}</span></button>`
    + `${loose ? "" : `<span class="pcounts">${counts.map((c) => bigCount(c, key)).join("")}</span>${mixHtml(g, ui.maxLive)}`}${tot}${go}</header>${why}${body}</section>`;
  return {
    key: `${key}${loose ? ":l" : ""}`, open, html: section(pieces.map(pieceHtml).join("")),
    w: 1.6 + (why ? 0.8 : 0) + pieces.reduce((a, p) => a + p.w, 0),
    // the block that holds the open session, drawn across [n] of the board's columns (boardHtml)
    wide: (n) => section(spreadHtml(pieces, n), n),
  };
}
/** THE OPEN SESSION'S BLOCK ACROSS THE BOARD: its rows keep the width of a column of the board and run down [n] columns
 *  in their order, and the open session's plate spans them all, under its row, so the session he reads has the width
 *  and no column stands empty beside it. The rows before it stand above the plate, the rows after it below, and the
 *  block's foot line keeps the block's whole width where it falls. */
function spreadHtml(pieces, n) {
  const at = pieces.findIndex((p) => p.plate), p = pieces[at];
  const before = pieces.slice(0, at), after = pieces.slice(at + 1);
  // a plate opened from inside the block's ended rows closes their wrapper above it and opens it again below
  const inRest = before.reduce((d, x) => d + (x.wrap || 0), 0) > 0;
  const head = [...before, ...(p.html ? [{ html: p.html, w: p.w }] : []), ...(inRest ? [{ html: REST_SHUT, w: 0, wrap: -1 }] : [])];
  const tail = [...(inRest ? [{ html: REST_OPEN, w: 0, wrap: 1 }] : []), ...after];
  return `${rowColsHtml(head, n)}<div class="wideplate">${p.plate}</div>${rowColsHtml(tail, n)}`;
}
/** Rows down [n] columns in their order, as even as they divide, the foot line across the whole width where it falls,
 *  and a fold's ended rows kept together under their own hairline. */
function rowColsHtml(pieces, n) {
  const out = [];
  let run = [], rested = false;
  const flush = () => {
    if (!run.length) return;
    const k = Math.min(n, run.length), per = Math.ceil(run.length / k);
    const cols = Array.from({ length: k }, (_, i) => run.slice(i * per, (i + 1) * per)).filter((c) => c.length);
    out.push(`<div class="prows${rested ? " rested" : ""}" style="--n:${n}">${cols.map((c) => `<div class="pcol">${c.join("")}</div>`).join("")}</div>`);
    run = [];
  };
  for (const x of pieces) {
    if (x.wrap) { flush(); rested = x.wrap > 0; continue; }
    if (x.foot) { flush(); out.push(x.html); continue; }
    run.push(pieceHtml(x));
  }
  flush();
  return out.join("");
}
/** WHY THESE SESSIONS ARE IN NO PROJECT, SAID ONCE (gap: one sentence printed on eight rows, in the code's word for a
 *  folder). Each reason the repo wire gives, once, in plain words, with the folders it holds (round 2: with two
 *  reasons, which folder had which was lost), so the reason is what he acts on. A session with no reason says nothing. */
const REASON_WORD = {
  "the cwd is not inside a git repository": "Not in a git repository",
  "the cwd is outside the trusted roots ($HOME, /tmp, statuslineGitRoots), so it was not probed": "Outside the folders splice reads",
  "the cwd does not exist on this host": "Folder not on this computer",
  "the cwd is not an absolute path": "Folder path not whole",
};
const reasonWord = (r) => REASON_WORD[r] ?? String(r).replace(/\(.*?\)/g, "").replace(/\bcwd\b/g, "folder").replace(/\s+/g, " ").trim().replace(/^./, (c) => c.toUpperCase());
const FOLDERS_SAID = 3;
function noProjectWhy(rows) {
  const why = new Map();
  for (const s of rows) {
    const r = s.row.repo?.reason;
    if (!r) continue;
    const whole = s.row.place || s.repo || "", word = reasonWord(r), place = shortPlace(whole);
    if (!why.has(word)) why.set(word, new Map());
    if (place && !why.get(word).has(place)) why.get(word).set(place, whole);
  }
  // each folder is a chip with its own name, its path on its hover; the rest behind a count
  return [...why].map(([word, places]) => {
    const all = [...places], said = all.slice(0, FOLDERS_SAID), more = all.length - said.length;
    return `<p class="note why"><span class="rw">${esc(word)}</span><span class="rf">${said.map(([p, whole]) => `<span title="${esc(whole)}">${esc(p)}</span>`).join("")}${more > 0 ? `<b>+${DRAW.n(more)}</b>` : ""}</span></p>`;
  }).join("");
}
/** Every project in the groups' order, and the ones with someone waiting: what the picker lists and the doors are. */
function pickOptions() {
  const live = state.rows.filter((s) => s.state !== "ended" || stranded(s));
  const groups = groupsOf(live);
  return {
    projects: groups.map(([k, g]) => [k, projName(g[0]), projTotal(k, g)]),
    doors: groups.map(([k, g]) => [k, projName(g[0]), g.filter((s) => RANK(s) === 0).length]),
  };
}
/** A project's sessions with its ended ones, which is what its header's total and its tile say. */
function projTotal(key, liveInIt) {
  return liveInIt.length + state.rows.filter((s) => s.state === "ended" && !stranded(s) && groupKey(s) === key).length;
}
/** WHAT EVERY ROW'S BAR MEASURES, DRAWN ONCE FOR THE PAGE: the axis itself, from one second on, with the same ticks that stand
 *  under every bar, so a length is read against them and nothing is said in words but its name. */
const axisLegend = () => DRAW.timeLegend("Time in state");
/** THE LAMPS AND THE COUNT, SAID ONCE FOR THE PAGE: a lamp on a tile or a block's header carries only its count, and the lamp is
 *  the word; the count beside a block's name is its live sessions out of all of them, the ended ones too. */
const LAMP_WORDS = [["needs", "Needs you"], ["stalled", "Stalled"], ["working", "Working"]];
function lampLegend(live) {
  const lamps = LAMP_WORDS.filter((_, i) => live.some((s) => RANK(s) === i)).map(([k, word]) => ({ mark: DRAW.lamp(k), word }));
  const count = grouped() ? [{ mark: `<span class="lgn"><b>live</b><i>/</i><span>all</span></span>`, word: "" }] : [];
  return DRAW.legend([...lamps, ...count]);
}
function listHead() {
  const live = state.rows.filter((s) => s.state !== "ended" || stranded(s));
  if (!live.length) return "";
  // one project and nothing outside it: no picker and no headers, its name where the picker would be (Marlin)
  const by = live.length >= 2 ? byHtml() : ""; // the grouping switch, once there are two sessions to group
  const found = ui.q ? `<span class="found"><b>${DRAW.n(ui.shownCount ?? 0)}</b> ${ui.shownCount === 1 ? "match" : "matches"}</span>` : "";
  // grouped by something nothing has (no team in any session), the list stays one block: the switch says so beside itself
  const empty = !grouped() && ui.by !== "project" ? DRAW.nothing(ui.by === "team" ? "No teams" : "One command", { slot: true }) : "";
  // the legends at the line's left, what a query found and the grouping switch at its right end
  const ctl = `<div class="lctl"><div class="lleg">${lampLegend(live)}${quietLegend()}${axisLegend()}</div><div class="lact">${found}${empty}${by}</div></div>`;
  if (!grouped()) {
    const solo = live[0].project && ui.by === "project" ? `<span class="pname solo">${esc(projName(live[0]))}</span>` : "";
    return `<div class="lhead">${solo}${ctl}</div>`;
  }
  // the tiles are the project picker and its doors, and the list's own head line; by command or team there is no pick
  if (ui.wide) return `<div class="lhead wide"><div id="ptiles">${sessionTiles()}</div>${ctl}</div>`;
  if (ui.by !== "project") return `<div class="lhead">${ctl}</div>`;
  const { projects, doors } = pickOptions();
  return `<div class="lhead">${pickerHtml(projects, picked(), ui.pmenu, doors, state.rows.length)}${ctl}</div>`;
}
/** THE TILES, in the groups' order after Every project (the kit's picker at a wide page, drawn here with the live
 *  sessions leading). Each tile leads with its live sessions as its figure over one bar of their states, on the scale of
 *  every live session, so the tiles compare by eye; the total with its ended sessions is the quiet figure after it, the
 *  number its block's header says. The lamps carry their counts alone: the head line's legend names them once. */
function sessionTiles() {
  const live = state.rows.filter((s) => s.state !== "ended" || stranded(s));
  const all = state.rows.length;
  const tile = (k, label, g, total) => {
    const on = (picked() ?? "") === k, counts = countsOf(g), n = liveOf(g);
    const words = `${n} live, ${countWords(counts, total, "session")}`;
    return `<button class="ptile${on ? " on" : ""}" data-key="t:${esc(k)}" data-act="pickproj" data-p="${esc(k)}" aria-pressed="${on}" aria-label="${esc(`${label}, ${words}`)}" title="${esc(label)}">`
      + `<span class="ptop"><span class="tname">${esc(label)}</span></span>`
      + `<span class="plead"><b class="plive" data-f="l:${esc(k)}">${DRAW.n(n)}</b><i class="sl" aria-hidden="true">/</i><b class="ptotal" data-f="tt:${esc(k)}">${DRAW.n(total)}</b></span>`
      + mixHtml(g, live.length)
      + `<span class="plamps">${counts.map((c) => tlampHtml(c)).join("")}</span></button>`;
  };
  GHOST.remember("tiles", groupsOf(live).length + 1);
  return `<nav class="ptiles" aria-label="Projects"><div class="ptgrid">${tile("", "Every project", live, all)}${groupsOf(live).map(([k, g]) => tile(k, projName(g[0]), g, projTotal(k, g))).join("")}</div></nav>`;
}
// WHAT IS NOT IN THE LIST, WITHIN REACH AND NOT UNDER EVERY LIST (gap: a standing method footnote under 2,536 px of
// rows, in the CLI's flag). Headless runs never register, so the fact lives behind one disclosure on the list's foot
// line, and beside No sessions, where it answers why the list is empty (§4 Resolved).
const HEADLESS_WHY = "Runs started from a script never appear here";
const headlessHtml = () => `<div class="foothead">`
  + DRAW.disc({ open: ui.headless, inner: `<span>Not listed</span>`, act: "headless", cls: "quiet door" })
  + (ui.headless ? `<p class="note unfold">${esc(HEADLESS_WHY)}</p>` : "") + `</div>`;
/** A read that did not answer: the rows he has stay, dimmed, under one line that says so, when it last read, and the
 *  act that asks again (gap: the whole list was replaced by one word in a dashed box). */
function failStripHtml() {
  const at = state.readAt ? `<span class="detail">Last read ${clock(new Date(state.readAt))}</span>` : "";
  return `<div class="readfail"><span class="state limit">${esc(state.error)}</span>${at}`
    + `<button class="act small" data-act="retry">Try again</button></div>`;
}
// WHILE THE FIRST READ IS OUT the list draws its own blocks hollow, in the columns they will land in: the tiles, the legend line,
// then blocks of rows on the row's own tracks (the name, the words, the bar's track and its figure, the command chip, the act's
// empty place), striped and fading down the page, so the sessions land where the eye already is. Their number is what this
// page last drew (GHOST.count), so the first frame stands as tall as the list that lands and nothing below it moves; a
// first visit, with nothing drawn yet, draws a plain full page of them.
const GHOST_CARDS = [[12, 5, 14], [9, 6, 10], [14, 5, 18], [10, 6, 12], [11, 5, 8]];
const ghostRow = ([name, chip, last], i) => `<article class="card s ghosted" style="--i:${i}" aria-hidden="true">`
  + `<span class="lamp"></span><div class="top"><span class="who">${GHOST.bar(name, "nm")}</span>`
  + `<span class="last">${GHOST.bar(last)}</span>${GHOST.cell().replace("barcell", "barcell age")}`
  + `<span class="cmd">${GHOST.bar(chip, "chip")}</span><span class="doing"></span></div></article>`;
const GHOST_BLOCKS = 6, GHOST_ROWS = 4, GHOST_TILES = 7;
function ghostListHtml() {
  const blocks = Math.max(1, GHOST.count("blocks", GHOST_BLOCKS)), rows = Math.max(1, GHOST.count("rows", GHOST_ROWS));
  const block = (j) => ({ key: `g${j}`, html: `<section class="pgroup ghosted" aria-hidden="true"><header class="phead">${GHOST.bar(9 + (j % 3) * 2, "nm")}${GHOST.bar(6)}</header>`
    + Array.from({ length: rows }, (_, i) => GHOST_CARDS[(i + j) % GHOST_CARDS.length]).map(ghostRow).join("") + `</section>` });
  const tiles = GHOST.tiles(GHOST.count("tiles", GHOST_TILES));
  const legend = `<div class="lctl" aria-hidden="true"><div class="lleg">${GHOST.bar(9)}${GHOST.bar(12)}</div><div class="lact">${GHOST.bar(14)}</div></div>`;
  const head = tilesWide() ? `<div class="lhead wide">${tiles}${legend}</div>` : `<div class="lhead">${tiles ? "" : GHOST.chip(13)}${legend}</div>`;
  return `<div class="list ghosts" aria-busy="true" aria-label="Reading sessions">${head}${boardHtml(Array.from({ length: blocks }, (_, j) => block(j)), "ghosts")}</div>`;
}
/** THE BLOCKS ON THE KIT'S BALANCED BOARD (DRAW.masonry): each block goes where the board is shortest at that point,
 *  measured on what is drawn, so no column stands as a tall wall beside empty ones and the groups' order runs across the
 *  top. The board holds as many columns as a row's words need (colCount). While his pointer is in the list the blocks keep
 *  the order they were drawn in, as the rows keep theirs, so no block moves to another column under it.
 *
 *  THE OPEN SESSION TAKES THE BOARD'S WIDTH (Marcos, Oct 26: "we're wasting a lot of horizontal space"): its block spans
 *  all but one column where three or more fit and blocks follow it, so the list stays in sight beside the session he
 *  reads, and the whole row where it does not. Its rows keep a column's width and run down the columns it spans. */
function boardHtml(blocks, cls = "") {
  if (!blocks.length) return "";
  const n = Math.max(1, Math.min(ui.cols, blocks.length));
  ui.blockAt ??= new Map();
  const spot = (b) => `${cls}|${b.key}`;
  const list = ui.hold && blocks.every((b) => ui.blockAt.has(spot(b))) ? [...blocks].sort((a, b) => ui.blockAt.get(spot(a)) - ui.blockAt.get(spot(b))) : blocks;
  list.forEach((b, i) => ui.blockAt.set(spot(b), i));
  const at = n >= 2 ? list.findIndex((b) => b.open) : -1;
  const span = n < 3 || at === list.length - 1 ? n : n - 1;
  return `<div class="sboard mason${cls ? ` ${cls}` : ""}" style="--n:${n}">${list.map((b, i) => (i === at ? b.wide(span) : b.html)).join("")}</div>`;
}
/** One block of rows with no header (one project alone, or the one he picked), split down the columns the width holds
 *  when it has more rows than one column reads well, row by row and in order. A column is as wide as the board's
 *  column, so a few rows never stretch their words across the whole screen. The open session takes the width, as it
 *  does in a block (spreadHtml). */
function flatBoard(key, live, ended) {
  const pieces = rowPieces(key, live, ended);
  if (ui.cols >= 2 && pieces.some((p) => p.plate)) return `<div class="board sboard opened"><div class="rows">${spreadHtml(pieces, ui.cols)}</div></div>`;
  // the board's whole width is used: a column takes about three rows, so a list of ten rows stands in three columns and
  // not in one beside two thirds of an empty screen
  const weight = pieces.reduce((a, p) => a + p.w, 0);
  const n = Math.max(1, Math.min(ui.cols, Math.ceil(weight / 3.4)));
  // the rested rows stay with their wrapper: a column never starts inside it
  const cols = Array.from({ length: n }, () => []), per = weight / n;
  let c = 0, w = 0, inRest = false;
  for (const p of pieces) {
    if (!inRest && w >= per * (c + 1) && c < n - 1 && p.w > 0) c += 1;
    cols[c].push(pieceHtml(p));
    w += p.w;
    if (p.wrap) inRest = p.wrap > 0;
  }
  const empty = Array.from({ length: Math.max(0, ui.cols - n) }, () => `<div class="col"></div>`);
  return `<div class="board sboard">${cols.map((h) => `<div class="col"><div class="rows">${h.join("")}</div></div>`).join("")}${empty.join("")}</div>`;
}
function listHtml() {
  if (state.loading) return ghostListHtml();
  const missing = ui.missing ? missingHtml(ui.missing) : "";
  const list = sorted(); ui.order = list.map((s) => s.id);
  const ended = foldNamesakes(list, state.rows.filter((s) => s.state === "ended" && !stranded(s) && matches(s) && inPick(s)).sort((a, b) => b.at - a.at));
  ui.shownCount = list.length + ended.length;
  const fail = state.error ? failStripHtml() : "";
  const head = listHead();
  if (!list.length && !ended.length) {
    // a picked project with nothing in it says so plainly, and its doors stay in the head (fin)
    // the empty list is the row that would stand in it, drawn dashed, with the one fact it has; why a run may be missing from
    // it is the Not listed line under it
    const slot = `<span class="nrow" aria-hidden="true"><i class="nl"></i><i class="nb"></i><i class="nb s"></i></span>`;
    const box = ui.q ? `<div class="nomatch">${slot}<span class="state">No match</span><span class="why">${hi(ui.q)}</span><button class="act quiet" data-act="clear">Clear</button></div>`
      : `<div class="nomatch">${slot}<span class="state">No sessions</span></div>`;
    return `<div class="list">${fail}${missing}${head}${box}${headlessHtml()}</div>`;
  }
  const dim = state.error ? " dim" : ""; // the rows he has, held while splice is not answering
  let board, loose = ended;
  if (picked() != null) { // PICKED, THE LIST IS THAT PROJECT'S CARDS, WITH NO HEADER: the picker already names it
    board = flatBoard(picked(), list, ended); loose = [];
  } else if (!ui.grouped) {
    ui.gorder = null; board = list.length ? flatBoard(FLAT, list, []) : "";
  } else {
    const groups = groupsOf(list); ui.gorder = groups.map(([k]) => k);
    const keys = new Set(ui.gorder);
    ui.maxLive = Math.max(1, ...groups.map(([, g]) => liveOf(g)));
    board = boardHtml(groups.map(([k, g]) => groupBlock([k, g], ended.filter((s) => groupKey(s) === k))));
    GHOST.remember("blocks", groups.length);
    GHOST.remember("rows", Math.max(1, Math.round(groups.reduce((a, [, g]) => a + Math.min(g.length, HANDFUL), 0) / groups.length)));
    loose = ended.filter((s) => !keys.has(groupKey(s))); // a project with nothing open today keeps its ended at the foot
  }
  return `<div class="list${dim}">${fail}${missing}${head}${board}${looseHtml(loose)}${headlessHtml()}</div>`;
}
/** THE PROJECTS WITH NOTHING LIVE ARE TILES AT THE LIST'S FOOT, each with its ended sessions counted and drawn as a share of the
 *  most, and each opens on its own where it stands, as its project's block, newest first, so a row's project is always on
 *  screen: one press shows one project's sessions, never all of them at once (the one line that opened 18 projects grew the
 *  page by 3,500 px). A search opens every one it matched, because no fold may hide a match. Flat, with no groups to tile,
 *  they stay one line that opens them. */
function looseHtml(loose) {
  if (!loose.length) return "";
  if (!ui.grouped) {
    const fold = DRAW.disc({ open: ui.ended, act: "ended", cls: "lfold door", inner: `<span><b>${DRAW.n(loose.length)}</b> ended</span>` });
    return `<div class="lfoot">${fold}</div>${ui.ended ? `<div class="board sboard loose unfold"><div class="col"><div class="rows">${pagePieces("loose", loose).map((p) => p.html).join("")}</div></div></div>` : ""}`;
  }
  const m = new Map();
  for (const s of loose) { const k = groupKey(s); if (!m.has(k)) m.set(k, []); m.get(k).push(s); }
  const groups = [...m].sort(([, a], [, b]) => b[0].at - a[0].at);
  const count = (rows) => rows.length + rows.reduce((a, s) => a + (ui.earlier.get(s.id)?.length ?? 0), 0);
  const most = Math.max(...groups.map(([, rows]) => count(rows)));
  const shown = (k) => ui.ended || ui.looseOpen.has(k);
  const tiles = groups.map(([k, rows]) => {
    const n = count(rows), name = groupName(rows[0]), on = shown(k), share = ((n / most) * 100).toFixed(1);
    return `<button class="ptile ltile${on ? " on" : ""}" data-act="loose" data-p="${esc(k)}" aria-expanded="${on}" aria-label="${esc(`${name}, ${DRAW.n(n)} ended`)}"${DRAW.flip(`lt:${k}`)}>`
      + `<span class="ptop"><span class="tname">${esc(name)}</span><span class="pfigs"><span class="ptotal" data-f="lt:${esc(k)}">${DRAW.n(n)}</span></span></span>`
      + `<span class="pshare" aria-hidden="true"><i data-tw="" data-w="${share}" style="width:${share}%"></i></span></button>`;
  });
  const blocks = groups.filter(([k]) => shown(k)).map(([k, rows]) => groupBlock([k, []], rows, { loose: true }));
  return `<div class="lfoot loose">${DRAW.kicker("Ended")}<nav class="ptiles ltiles" aria-label="Projects with nothing running"><div class="ptgrid">${tiles.join("")}</div></nav></div>${boardHtml(blocks, "loose unfold")}`;
}

// ---------- the open session ----------
/** What a call was about, from its input as the transcript keeps it: the command it ran, the file it touched, what it
 *  searched for, the question it asked. An input this page can't read is shown as the transcript has it. */
function callSummary(m) {
  let input;
  try { input = JSON.parse(m.text); } catch { return m.text || ""; }
  if (!input || typeof input !== "object") return m.text || "";
  if (input.command) return input.command;
  if (input.file_path || input.notebook_path) return input.file_path || input.notebook_path;
  if (input.pattern) return input.path ? `${input.pattern} in ${input.path}` : input.pattern;
  if (input.questions?.[0]?.question) return input.questions[0].question;
  if (input.url) return input.url;
  const first = Object.values(input).find((v) => typeof v === "string" && v.trim());
  return first ?? "";
}
/** WHAT A CALL DID, ON ITS ONE LINE: the words its client wrote for it, when it wrote any; else the command as typed;
 *  else the file or the search, its path read from the session's own folder. [words] says the line is prose. */
function callLine(m, s) {
  let input;
  try { input = JSON.parse(m.text); } catch { return { text: m.text || "", words: false }; }
  if (!input || typeof input !== "object") return { text: m.text || "", words: false };
  if (typeof input.description === "string" && input.description.trim()) return { text: input.description.trim(), words: true };
  const cwd = String(s.row.cwd || "").replace(/\/+$/, "");
  const rel = (p) => (cwd && typeof p === "string" && p.startsWith(`${cwd}/`) ? p.slice(cwd.length + 1) : p);
  if (input.command) return { text: input.command, words: false };
  // A MESSAGE TO ANOTHER SESSION names who it went to, by the name the list knows it by, never its socket's path; the
  // address stays in the call's whole input, one press away
  if (typeof input.to === "string" && input.to.trim()) {
    const to = input.to.trim(), bare = to.replace(/^uds:/, "");
    const peer = state.rows.find((x) => x.name === to || (x.row.address && String(x.row.address).replace(/^uds:/, "") === bare));
    const who = peer?.name || (/[/:]/.test(to) ? "another session" : to);
    const what = typeof input.summary === "string" ? input.summary.trim() : "";
    return { text: what ? `To ${who} · ${what}` : `To ${who}`, words: true };
  }
  if (input.file_path || input.notebook_path) return { text: rel(input.file_path || input.notebook_path), words: false, file: true };
  if (input.pattern) return { text: input.path ? `${input.pattern} in ${rel(input.path)}` : input.pattern, words: false };
  return { text: callSummary(m), words: false };
}
/** A FILE'S PATH ON A CALL'S LINE: the file's own name stays whole and the folders before it give way from their start,
 *  so a path longer than the line never loses the one part he would name the file by. */
function pathHtml(p) {
  const at = p.lastIndexOf("/");
  if (at < 1) return esc(p);
  return `<span class="tdir"><bdi>${esc(p.slice(0, at + 1))}</bdi></span><span class="tfile">${esc(p.slice(at + 1))}</span>`;
}
/** A teammate's colour: the command its own session runs on, found by its name, else this session's. */
const senderColor = (name, s) => color(state.rows.find((x) => x.name && x.name === name) ?? s);
/** A teammate's name is a door to its own conversation, when this page holds it (gap: the name was plain text). */
function fromHtml(name, s) {
  const peer = name && state.rows.find((x) => x.name && x.name === name);
  const inner = `<i aria-hidden="true"></i>${esc(registered(name) || "A teammate")}`;
  return peer ? `<a class="from" href="#${encodeURIComponent(peer.id)}" data-open="${esc(peer.id)}">${inner}</a>` : `<p class="from">${inner}</p>`;
}
// ---------- the agent's own text, as it wrote it ----------
// The kit draws it (kit.js md, DRAW.md): headings, lists, tables, fenced blocks, bold and links, the shapes a Claude Code
// turn actually writes. A web address opens in a new tab under its own words, and a link to a file names the file,
// since the console cannot open his disk.
// A LONG MESSAGE FOLDS AFTER ITS FIRST LINES (one page, one scroll): a summary or a report a screen long stood between him
// and everything after it. Its first lines stand, and the rest is one press away, in place.
const CLAMP_LINES = 28, CLAMP_SHOWN = 14, CLAMP_CHARS = 3600;
/** The tag a dispatch puts before its words, with its id inside ("[eli-dispatch 1f2e3d4c]"), is a machine's note, not his words. */
const untagged = (text) => String(text ?? "").replace(/^\s*\[[^\]]*[0-9a-f]{6,}[^\]]*\]\s*/i, "");
function bodyHtml(s, m, text = m.role === "assistant" ? m.text : untagged(m.text)) {
  const key = `m:${s.id}:${m.index}`, lines = String(text).split("\n");
  const tall = lines.length > CLAMP_LINES, wide = !tall && text.length > CLAMP_CHARS;
  if (!tall && !wide) return DRAW.md(text);
  const open = ui.full.has(key), data = ` data-k="${esc(key)}"`;
  const left = tall ? lines.length - CLAMP_SHOWN : 0;
  const shown = open ? text : tall ? lines.slice(0, CLAMP_SHOWN).join("\n") : `${text.slice(0, CLAMP_CHARS / 2).replace(/\s+\S*$/, "")}…`;
  return DRAW.md(shown) + DRAW.more(left, { word: tall ? "more lines" : "", one: tall ? "more line" : "", act: "full", data, open, less: "Fewer lines" })
    .replace(/<b>0<\/b> /, "Whole message");
}
// A SLASH COMMAND IS ONE EVENT, NOT ITS MARKUP (gap: raw <command-name> tags shown as text). Claude Code writes a
// command he typed, its words and what it printed into the conversation in tags; drawn, it is the command as a chip and
// the line it printed.
const SLASH = { name: /<command-name>([^<]*)<\/command-name>/, args: /<command-args>([^<]*)<\/command-args>/, out: /<local-command-stdout>([\s\S]*?)<\/local-command-stdout>/ };
function slashHtml(text) {
  const name = text.match(SLASH.name)?.[1]?.trim(), out = text.match(SLASH.out)?.[1]?.trim();
  if (!name && !out) return null;
  const args = text.match(SLASH.args)?.[1]?.trim();
  const cmd = name ? `<span class="chip">${esc(name.startsWith("/") ? name : `/${name}`)}${args ? ` ${esc(args)}` : ""}</span>` : "";
  return `<p class="slash">${cmd}${out ? `<span class="said">${esc(out)}</span>` : ""}</p>`;
}
// WHAT THE CLIENT PUT INTO THE CONVERSATION IS ONE LINE (round 2's bug list): a compaction's summary, a skill it loaded,
// a notice about a message between sessions, a nudge of its own. Each read as a page of the conversation, with socket
// paths and ids in its first line. Each is one quiet line in plain words, that opens in place to the text as written.
const SYS_LINES = [
  [/^(?:This session is being continued from a previous conversation|Conversation compacted)/i, () => "Summary of the earlier conversation"],
  [/^Base directory for this skill:\s*(\S+)/i, (m) => `Skill · ${String(m[1]).split("/").filter(Boolean).pop()}`],
  [/^\[Cross-session delivery notice\]/i, () => "Delivery notice"],
  [/^\[Cross-session idle notice\]/i, () => "Idle notice"],
  [/^Cross-session message released/i, () => "Message released"],
  [/^(?:A process claiming the address|uds:\/)/i, () => "Address notice"],
  [/^\[Your previous response had no visible/i, () => "Reminder about an empty reply"],
  [/^API Error/i, () => "API error"],
  [/^([\w.-]+) is idle\b/, (m) => `${m[1]} is idle`],
];
/** A client's text in one line of his words: its kind, else its first line with its ids and socket paths left out. */
function sysWord(text) {
  const t = String(text ?? "").trim();
  for (const [re, word] of SYS_LINES) { const m = t.match(re); if (m) return word(m); }
  const first = t.split("\n").find((l) => l.trim()) ?? "";
  // a line that opens with a markup tag reads as the tag's own words, never as the tag
  const tag = first.trim().match(/^<\/?([a-z][\w-]*)[^>]*>/i);
  if (tag) return tag[1].replace(/[-_]+/g, " ").replace(/^./, (c) => c.toUpperCase());
  return first.replace(/^\[([^\]]+)\]\s*/, "$1 · ").replace(/uds:\S+/g, "").replace(/(?:\/[\w.@~-]+){2,}/g, "…")
    .replace(/\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b/gi, "").replace(/\b[0-9a-f]{8,}\b/gi, "").replace(/\s+/g, " ").trim() || "Note from the client";
}
/** One line for a client's text, or for a run of the same kind side by side: its words once and the count of them after,
 *  one press to open every text as written, in place. */
function sysHtml(s, group, at, word) {
  const key = `${s.id}:${group[0].index}`, open = ui.sys.has(key);
  const times = group.length > 1 ? `<b class="times">×${DRAW.n(group.length)}</b>` : "";
  return `<div class="sysline${open ? " open" : ""}">${DRAW.disc({ open, act: "sys", data: ` data-k="${esc(key)}"`, cls: "sysdisc", inner: `<span>${esc(word)}${times ? " " : ""}${times}</span>` })}${at}`
    + (open ? group.map((m) => `<div class="sysbody unfold">${bodyHtml(s, m)}</div>`).join("") : "") + `</div>`;
}
/** What the session's prompt holds now, read off its screen; empty when nothing or unread. */
const inPrompt = (s) => (state.screens[s.id]?.draft || "").trim();
/** A message of his that Claude Code took back when he stopped it before any answer (hitstop 0298d62): marked so by
 *  its transcript, or, for his newest message, by the prompt holding exactly its words. */
function takenBack(s, m) {
  if (m.role !== "user") return false;
  if (m.kind === "taken_back") return true;
  const msgs = state.logs[s.id]?.messages || [];
  return m === msgs.at(-1) && s.state !== "working" && inPrompt(s) !== "" && inPrompt(s) === (m.text || "").trim();
}
// WHILE A CONVERSATION IS BEING READ, the log draws the shape of what will land: two of his lines, an agent's block and
// a trail of calls at their own pitch, hollow and striped, where the word "Reading" stood alone in a 1,375 px box.
const GHOST_LOG = `<div class="log ghosts" aria-busy="true"><div class="msg his"><div class="body">${GHOST.lines([16, 9])}</div></div>`
  + `<div class="msg agent"><div class="body">${GHOST.lines([30, 26, 18])}</div></div>`
  + `<div class="trail" style="--tw:6">${[16, 22, 12].map((w) => `<div class="tline">${GHOST.bar(w)}</div>`).join("")}</div>`
  + `<div class="msg agent"><div class="body">${GHOST.lines([24, 12])}</div></div></div>`;
// THE CONVERSATION STANDS IN THE PAGE (one page, one scroll): its newest items, and the older ones it read behind one
// count at its top, which opens them in place; Show earlier then reads the page before.
const LOG_SHOWN = 12;
function logHtml(s) {
  const held = state.logs[s.id];
  if (!held) return GHOST_LOG;
  if (held.off) return `<div class="log"><p class="empty"><a class="door" href="settings.html#data">Reading off${ICON.door}</a></p></div>`;
  if (!held.messages) return `<div class="log"><p class="empty">${esc(held.error ?? "No transcript")}</p></div>`;
  // each item draws itself with its time, which stands once a minute: [ts] is its moment, [sep] a turn's hairline
  const items = [];
  // A call and what came back are two rows on the wire, the call's (result: false, its input as text) and the
  // result's (role "tool"), joined by tool_use_id: drawn as one call that opens on its result, as the drawing has it.
  const results = new Map(held.messages.filter((m) => m.role === "tool" && m.tool_use_id).map((m) => [m.tool_use_id, m.text]));
  // THE CALLS NOTHING HAS COME BACK FOR YET: after the newest words, with no result. Their output is not known, so they
  // never say No output: while the session works they run, on the client even when splice holds no turn, and carry the
  // running dot. A text the client puts in while a call runs, such as a skill it loaded, does not end the run.
  const inRun = (m) => m.tool || (m.role === "system" && m.kind !== "interrupted");
  let tail = held.messages.length;
  while (tail > 0 && inRun(held.messages[tail - 1])) tail -= 1;
  const out = new Set(held.messages.slice(tail).filter((m) => m.tool && m.role !== "tool" && !results.has(m.tool_use_id)));
  const working = s.state === "working" && !held.at; // a log read at a moment ends there, not where the session is
  // each move sits where it happened: before the first message written after it, oldest first. One older than every
  // message read so far waits for Show earlier, unless the conversation starts here.
  const first = held.messages.find((m) => m.ts)?.ts;
  const marks = (held.moves || []).filter((mv) => !held.earlier || first == null || mv.moved_at >= first)
    .sort((a, b) => a.moved_at - b.moved_at);
  // a move that did not come back draws no mark, but splice ended the client for it all the same (Marlin, #3)
  const tries = (held.unmoved || []).slice().sort((a, b) => a - b);
  let pressed = null, exit = EXIT_LOOKING;
  // A RUN is every call drawn between two things that are not calls: it lands as one trail when the next thing comes
  let run = [];
  const flush = () => { if (run.length) { const calls = run; items.push({ ts: calls.at(-1).ts, draw: () => trailHtml(s, calls, results, out, working) }); } run = []; };
  const put = (ts, draw, sep = false) => { flush(); items.push({ ts, draw, sep }); };
  const markUpTo = (ts) => {
    while (marks.length && (ts == null || marks[0].moved_at <= ts)) { const mv = marks.shift(); pressed = mv.moved_at; exit = EXIT_LOOKING; put(null, () => jointHtml(mv)); }
    while (tries.length && ts != null && tries[0] <= ts) { pressed = tries.shift(); exit = EXIT_LOOKING; }
  };
  held.messages.forEach((m) => {
    if (m.ts) markUpTo(m.ts);
    if (m.role === "tool") return; // drawn under its call
    const next = moveExit(m, pressed, exit);
    if (next !== exit) { exit = next; return; }
    if (m.tool) { run.push(m); return; }
    // the client's own mark where he refused a call or stopped a turn: a line in the conversation, never his words
    if (m.role === "system" && m.kind === "interrupted") { put(m.ts, (at) => `<p class="cutoff">${ICON.stop}Stopped${at}</p>`); return; }
    if (!m.text) return;
    if (m.role === "system") {
      // the same notice again, with nothing between, joins the line before it rather than standing as a twin of it
      const word = sysWord(m.text), prev = items.at(-1);
      if (!run.length && prev?.sys === word) { prev.group.push(m); return; }
      const group = [m];
      put(m.ts, (at) => sysHtml(s, group, at, word));
      Object.assign(items.at(-1), { sys: word, group });
      return;
    }
    // a teammate's message reads as its sender's, beside the agent's side, with who sent it
    if (m.role === "peer") { put(m.ts, (at) => `<div class="msg peer" style="--c:${senderColor(m.from, s)}">${fromHtml(m.from, s)}<div class="body">${bodyHtml(s, m)}</div>${at}</div>`); return; }
    // a command he typed and what it printed: one event, drawn, never its markup
    const slash = m.role === "user" ? slashHtml(m.text) : null;
    if (slash) { put(m.ts, () => slash); return; }
    if (takenBack(s, m)) {
      const back = inPrompt(s) === (m.text || "").trim();
      put(m.ts, (at) => `<div class="msg his takenback"><div class="body">${bodyHtml(s, m)}</div><p class="taken">${ICON.stop}<span>${back ? "Stopped · Back in the prompt" : "Stopped"}</span>${at}</p></div>`);
      return;
    }
    // where one turn ends and the next begins: a hairline before each of his messages, so a run of calls reads as one turn
    if (m.role === "user" && (items.length || run.length)) put(null, () => `<div class="tsep" aria-hidden="true"></div>`, true);
    put(m.ts, (at) => `<div class="msg ${m.role === "user" ? "his" : "agent"}"><div class="body">${bodyHtml(s, m)}</div>${at}</div>`);
  });
  markUpTo(null); // a move after the newest message, before anything was said on the new model
  flush();
  // the newest items stand; the older ones he has read fold behind one count, which opens them in place
  const open = ui.logOpen.has(s.id);
  let from = 0;
  if (!open) {
    let counted = 0;
    for (from = items.length; from > 0 && counted < LOG_SHOWN; ) { from -= 1; if (!items[from].sep) counted += 1; }
    while (from < items.length && items[from].sep) from += 1;
  }
  const hidden = items.slice(0, from).filter((it) => !it.sep).length;
  const head = [];
  if (hidden) head.push(DRAW.disc({ open: false, act: "logopen", data: ` data-s="${esc(s.id)}"`, cls: "logmore", inner: `<span><b>${DRAW.n(hidden)}</b> earlier</span>` }));
  else if (held.earlier) head.push(`<button class="act quiet small earlier" data-act="earlier" data-s="${esc(s.id)}">Show earlier</button>`);
  if (open && hidden === 0 && items.length > LOG_SHOWN) head.push(DRAW.disc({ open: true, act: "logopen", data: ` data-s="${esc(s.id)}"`, cls: "logmore", inner: `<span>Newest only</span>` }));
  if (held.unreadable) head.push(`<p class="note">${DRAW.n(held.unreadable)} unreadable</p>`);
  if (held.error) head.push(`<p class="note">${esc(held.error)}</p>`);
  // A TIME ONCE A MINUTE: a message's time stands when its minute is not the one the last time said
  let said = null;
  const body = items.slice(from).map((it) => {
    let at = "";
    if (it.ts) { const c = clock(new Date(it.ts)); if (c !== said) { at = `<time>${c}</time>`; said = c; } }
    return it.draw(at);
  });
  const newest = held.at ? `<button class="act quiet small earlier" data-act="newest" data-s="${esc(s.id)}">Show newest</button>` : "";
  return `<div class="log">${head.length ? `<div class="loghead">${head.join("")}</div>` : ""}${body.join("")}${newest}</div>`;
}
/** A RUN OF CALLS IS ONE QUIET LINE (Marcos, Oct 26: "the tool call ui is very busy"): a run of one or two calls shows them,
 *  one line each on one grid; a longer run is one line, its count with the tools it used as marks and counts, and under it
 *  the newest call, which is what the session is doing now. One press opens the rest in place, and it stays open while a call
 *  it hides is opened or has its result open. */
const TRAIL_FLAT = 2;
function trailHtml(s, calls, results, out, working) {
  const keyOf = (m) => `${s.id}:${m.index}`;
  const hidden = calls.length > TRAIL_FLAT ? calls.slice(0, -1) : [];
  const open = hidden.some((m) => ui.trails.has(keyOf(m)) || ui.results.has(keyOf(m)));
  const shown = open ? calls : calls.slice(hidden.length);
  // the tool word's track is as wide as the run's usual words: one long word among short ones (Send message among Bash
  // lines) runs on into its own line's text rather than pushing every other line's words to the right
  const lens = shown.map((m) => DRAW.toolWord(m.tool).length), sorted = [...lens].sort((a, b) => a - b);
  const typical = sorted[Math.floor((sorted.length - 1) / 2)] ?? 4, fits = (n) => n <= Math.max(typical + 2, typical * 1.5);
  const tw = Math.min(14, Math.max(4, ...lens.filter(fits)));
  const count = !hidden.length ? "" : DRAW.disc({ open, cls: "tmore", act: "trail", data: ` data-ks="${esc(hidden.map(keyOf).join(" "))}"`,
    inner: `<span><b>${DRAW.n(hidden.length)}</b> earlier calls${open ? "" : tally(hidden)}</span>` });
  return `<div class="trail" style="--tw:${tw}">${count}${shown.map((m) => callHtml(s, m, results, out, working, !fits(DRAW.toolWord(m.tool).length))).join("")}</div>`;
}
/** What a folded run held, by tool, the commonest first, every tool it used so the parts add up to the count: each tool's
 *  mark, its word and its number. */
function tally(calls) {
  const by = new Map();
  for (const m of calls) { const w = DRAW.toolWord(m.tool), at = by.get(w) ?? { n: 0, kind: toolKind(m.tool) }; at.n += 1; by.set(w, at); }
  return `<span class="tally">${[...by].sort((a, b) => b[1].n - a[1].n)
    .map(([w, at]) => `<span class="tk"><span class="tg" aria-hidden="true">${TOOL_GLYPH[at.kind]}</span>${esc(w)} <b>${DRAW.n(at.n)}</b></span>`).join("")}</span>`;
}
/** ONE CALL, ONE LINE: the mark, the tool in plain words, what it did, and No output at the line's end when it printed
 *  nothing (Marlin p164), never on a second line. Opened: its whole input when the line showed something else, then its
 *  result, bounded on the page rather than in a box that scrolls. A call nothing has come back for yet ([out]) runs
 *  while the session works, and says nothing of its output. */
function callHtml(s, m, results, out, working, long = false) {
  const key = `${s.id}:${m.index}`;
  const result = results.get(m.tool_use_id) ?? null, none = result == null, empty = !none && !result.trim();
  const running = out.has(m) && working;
  const line = callLine(m, s), whole = callSummary(m), more = line.text !== whole;
  const can = !none || more, open = can && ui.results.has(key);
  const mark = running ? "<i></i>" : can ? ICON.caret : "";
  const said = `<span class="tsum${line.words ? " words" : ""}${line.file ? " pathed" : ""}"${more ? "" : ` title="${esc(whole)}"`}>${line.file ? pathHtml(line.text) : esc(line.text)}</span>`;
  const nothing = (none && !out.has(m)) || empty ? `<span class="nothing">No output</span>` : "";
  // the output block carries its own class: `.result` is the kit's one-word result beside an act, and this is a tool's output
  const under = !open ? "" : `${more ? `<p class="tin">${esc(whole)}</p>` : ""}${!none && !empty ? cmdBox(`t:${key}`, result, "toolout") : ""}`;
  const word = DRAW.toolWord(m.tool);
  return `<div class="call${ui.landing.has(key) ? " landing" : ""}"><button class="tool${long ? " long" : ""}" type="button" data-act="result" data-k="${esc(key)}" aria-expanded="${open}"${can ? "" : " disabled"}>`
    + `${mark}<span class="tname" title="${esc(word)}">${esc(word)}</span><span class="twhat">${said}${nothing}</span></button>${under}</div>`;
}
/** The /exit splice typed to end the client for a move, and what Claude Code printed for it: the move's mechanics, which
 *  the joint already says, so they are not his conversation (Marlin #3). A move that did not come back is the same
 *  press with no joint. Only the first /exit after a mark, sent within
 *  the move's own wait, and the one line it printed next; an /exit he types himself later is his and stays. */
const MOVE_EXIT_MS = 60_000;
const EXIT_LOOKING = 0, EXIT_TYPED = 1, EXIT_DONE = 2;
/** Where reading [m] leaves the search for the move's /exit: a step on when [m] is part of it, and so hidden. */
function moveExit(m, pressed, exit) {
  if (pressed == null || exit === EXIT_DONE || !m.ts || m.ts - pressed > MOVE_EXIT_MS) return exit;
  if (exit === EXIT_LOOKING) return m.role === "user" && (m.text || "").trim() === "/exit" ? EXIT_TYPED : exit;
  return m.role === "system" && !m.kind ? EXIT_DONE : exit;
}
/** The rail where a session moved: each chip keeps its model, since a move can stay on one command and change only the
 *  model, and the time sits under it where a message's does, with what didn't come along beside it (hitstop f38853e). */
function jointHtml(mv) {
  const chip = (head) => `<span class="chip" style="--c:${colorOf(state.providerOf[head])}">${esc(commandOf(head) ?? head)}</span>`;
  const model = (id) => (id ? `<span class="model">${esc(modelName(state.modelLabel, id))}</span>` : "");
  const from = mv.from_command ? `<span class="model">from</span>${chip(mv.from_command)}${model(mv.from_model)}` : "";
  const at = [`<time>${clock(new Date(mv.moved_at))}</time>`, mv.thinking_dropped ? "<span>Thinking not carried over</span>" : ""].filter(Boolean).join(" · ");
  return `<div class="jwrap"><div class="joint"><span class="model">Continued on</span>${mv.command ? chip(mv.command) : ""}${model(mv.model)}${from}</div>`
    + `<p class="jat">${at}</p></div>`;
}

/** THE OPEN SESSION, IN PLACE (round 2): a plate under its own row, inside its block. Its title bar is the row's own line,
 *  raised in the selection's ink, and its name folds it away. Under it one line holds the details' fold and the acts on
 *  the whole session; then whatever must be seen or answered (an armed act, a refusal, what it asks), the details,
 *  which fold away so the conversation comes up under the acts (Marcos, Oct 26: "we should be able to collapse the top
 *  component"), the conversation, and the Message field. */
function plateHtml(s) {
  const L = movingLook(s) || look(s);
  const failed = ui.failed.get(s.id);
  const ask = s.state === "needs" ? askHtml(s, "pane") : "";
  // the conversation and the details share one body: the details in a rail beside it where the plate has the room, over it
  // where it has not (sessions.css), the same layout whatever the conversation holds, so nothing re-flows when it is read
  return `<section class="pane s ${L.cls}${ui.unfold === s.id ? " unfold" : ""}${ui.topShut ? " topshut" : ""}" style="--c:${color(s)}" data-key="p:${esc(s.id)}" aria-label="${esc(title(s))}">`
    + lampHtml(L) + lineHtml(s, L, true) + toolsHtml(s)
    + `${armedStopHtml(s, false)}${armedEndHtml(s)}${failedHtml(s, failed, true)}${ask ? `<div class="askbox">${ask}</div>` : ""}`
    + `<div class="pbody">${detailsHtml(s)}<div class="talk">${logHtml(s)}${composerHtml(s)}</div></div></section>`;
}
/** THE LINE OF ACTS ON THE WHOLE SESSION: the details' fold first, in the block folds' own disc, then End, Continue on
 *  and Close, which the name also is. Continue on's menu, and the prompt a move holds back, open in place on their own
 *  line under it, so the line of acts keeps its height wherever it stands. */
function toolsHtml(s) {
  const shut = ui.topShut;
  const fold = `<button class="topfold disc" type="button" data-act="top" aria-expanded="${!shut}" aria-controls="panetop-${esc(s.id)}">${DRAW.chev}<span>Details</span></button>`;
  const go = movable(s) && !ui.moving.has(s.id);
  const cont = !movable(s) ? "" : go
    ? `<button class="act" data-act="menu" data-s="${esc(s.id)}" aria-haspopup="menu" aria-expanded="${ui.menu === s.id}">Continue on</button>`
    : `<button class="act stopping" disabled>${ICON.wait}Continuing on</button>`;
  const close = `<button class="act quiet" data-act="close">Close</button>`;
  const under = go ? (ui.menu === s.id ? choicesHtml(s) : heldHtml(s)) : "";
  // THE ACT THAT NAMES IT (DRAW.nameIt): on a session nobody named it stands as Name it, on one that is named as Rename
  const rename = ui.renaming === s.id ? "" : DRAW.act({ word: ownOf(s) ? "Rename" : "Name it", act: "rename", cls: "quiet", data: ` data-s="${esc(s.id)}"` });
  return `<div class="tools">${fold}<div class="acts">${rename}${endHtml(s)}${cont}${close}</div></div>${under}`;
}
/** THE DETAILS: the facts that say which session it is, then what it did today, what it holds and where it sends, as
 *  drawings in two columns, then the lines to paste. They fold to nothing and back over 240 ms. */
function detailsHtml(s) {
  const shut = ui.topShut;
  return `<div class="panetop${shut ? " shut" : ""}" id="panetop-${esc(s.id)}"><div class="panetop-in"${shut ? " inert" : ""}>`
    + factsHtml(s)
    + `<div class="secs">${DRAW.section("Today", todayHtml(s), { cls: "today" })}${DRAW.section("Tokens", tokensHtml(s), { cls: "tokens" })}`
    + `${DRAW.section("Context", contextHtml(s), { cls: "context" })}${DRAW.section("Login", accountHtml(s), { cls: "login" })}</div>`
    + copiesHtml(s) + `</div></div>`;
}
/** THE FACTS OF ONE SESSION, EACH UNDER ITS OWN LABEL, a dash for any the daemon does not send: where it is (its project,
 *  or its folder with the reason it is in none), its command, how many turns it ran, when it started, its team, its
 *  Claude Code, and the ended sessions that carried its name before it. */
const NAMESAKES_SHOWN = 3;
function factsHtml(s) {
  const fact = (label, value, cls = "") => `<div class="fact${cls ? ` ${cls}` : ""}"><dt>${esc(label)}</dt><dd>${value || DRAW.dash}</dd></div>`;
  const team = s.team ? `<span class="team">${ICON.team}${esc(s.team)}</span>` : "";
  const started = s.row.started_at ? esc(backWord(new Date(s.row.started_at))) : "";
  const turns = s.row.turns == null ? "" : `${DRAW.n(s.row.turns)}${s.row.turns_partial ? "+" : ""}`;
  const reason = !s.project && s.row.repo?.reason ? `<span class="reason">${esc(reasonWord(s.row.repo.reason))}</span>` : "";
  // the sessions that carried its name, the newest three standing and the rest behind one count that opens them in place
  const before = (ui.earlier.get(s.id) || []).concat(namesakeOf(s) ? [namesakeOf(s)] : []).filter((x) => x.id !== s.id);
  const all = ui.namesakes.has(s.id), shown = all ? before : before.slice(0, NAMESAKES_SHOWN), left = before.length - NAMESAKES_SHOWN;
  const more = left > 0 ? DRAW.more(left, { word: "more", act: "namesakes", data: ` data-s="${esc(s.id)}"`, open: all, less: "Fewer" }) : "";
  const earlier = before.length ? fact("Earlier", shown.map((x) => `<a class="chip quiet" href="#${esc(x.id)}" data-open="${esc(x.id)}">${x.state === "ended" ? "Ended" : "Live"} ${esc(backWord(x.at))}</a>`).join("") + more, "earlier") : "";
  // the worktree's own folder, which the row draws as a mark alone
  const tree = s.wt && wtWord(s.wt) !== "Worktree" ? fact("Worktree", `<span class="where" title="${esc(s.wt)}">${ICON.branch}<span>${esc(s.wt)}</span></span>`) : "";
  return `<dl class="meta facts">${fact(s.project ? "Project" : "Folder", (whereHtml(s, true) || "") + reason, s.project ? "" : "folder")}${fact("Command", cmdCell(s, "edge"))}`
    + `${fact("Turns", turns)}${fact("Started", started)}${tree}${fact("Team", team)}${fact("Claude Code", s.row.version ? esc(s.row.version) : "")}${earlier}</dl>`;
}
/** The row an ended namesake is folded under, when this session is one of them: its link back to the newest. */
function namesakeOf(s) {
  for (const [id, list] of ui.earlier) if (list.some((x) => x.id === s.id)) return find(id) ?? null;
  return null;
}
/** ONE WHOLE, SPLIT, IN THE KIT'S OWN BAR, WITH THIS PAGE'S WORDS (kit DRAW.stack). A session's requests split by what
 *  paid for them, and its input split by where it came from, are not outcomes, so no part of either is the kit's quiet
 *  "nt" class: each part is named for itself. */
const splitBar = (segs, total, opts = {}) =>
  (segs.some((s) => s.n > 0) && total > 0 ? DRAW.stack(segs, total, opts) : "");
/** An empty split: the bar's own track with nothing in it, so a day with no requests keeps the day's shape. */
const EMPTY_SPLIT = `<div class="pd-pop sm"><div class="pd-bar empty" aria-hidden="true"></div></div>`;
/** TODAY: the requests this session sent since midnight as the one figure, a door to exactly those requests, and the
 *  whole split into one bar — on your plan, at API prices, with no price, on this computer, unanswered (gap: seven
 *  metrics in one sentence). The money, the requests that started on a cold cache and the ones that failed ride the
 *  bar's row; failures the No answer part already counts are not said twice. */
function todayHtml(s) {
  const u = state.usage[s.id];
  if (u === undefined) return GHOST.hero() + GHOST.stack();
  // none today is the figure 0 over the split's empty track; a read no command answered is the dash
  if (!u?.requests) return u === null ? `<div class="pd-top"><b class="pd-rate">${DRAW.unread.fig}</b></div>${EMPTY_SPLIT}` : `${DRAW.hero({ figure: "0", of: "requests today" })}${EMPTY_SPLIT}`;
  const href = `requests.html?${new URLSearchParams({ session: s.id, from: String(u.from) })}`;
  const hero = DRAW.hero({ figure: DRAW.n(u.requests), of: u.requests === 1 ? "request today" : "requests today", href });
  const segs = [{ n: u.plan, word: "On your plan", cls: "s1" }, { n: u.priced, word: "At API prices", cls: "s2" },
    { n: u.noPrice, word: "No price", cls: "s3" }, { n: u.local, word: "On this computer", cls: "s3" },
    { n: u.unans, word: "No answer", cls: "f0" }];
  const money = u.priced ? DRAW.focal(dollars(u.cost), "at API prices") : "";
  const cold = u.cold ? DRAW.statTile(DRAW.glyph.cold, DRAW.n(u.cold), "cold") : "";
  const failed = u.failed && u.failed !== u.unans ? `<span class="pd-stat fail">${DRAW.glyph.retry}<span><b>${DRAW.n(u.failed)}</b><small>failed</small></span></span>` : "";
  const tiles = cold || failed ? `<div class="pd-stats">${cold}${failed}</div>` : "";
  const split = splitBar(segs, u.requests, { small: true, bw: 24, tail: money + tiles, grow: ui.grow.has(`u:${s.id}`), label: `${DRAW.n(u.requests)} requests today, by what paid for them` });
  return hero + (split || money + tiles);
}
/** TOKENS: the cache share as the ring the Usage drill-down draws it, the input split into what came from the cache,
 *  what was written to it and what was new, then what went in and came out. */
function tokensHtml(s) {
  const u = state.usage[s.id];
  if (u === undefined) return `<div class="tokhead">${GHOST.ring("focal")}${GHOST.focal(6)}</div>${GHOST.stack()}`;
  // nothing went in or out today: the section keeps its figures' places, each a dash, over the split's empty track
  if (!u?.requests || (!u.tin && !u.tout)) return `<div class="tokhead"><div class="io">${DRAW.focal("—", "in")}${DRAW.focal("—", "out")}</div></div>${EMPTY_SPLIT}`;
  const share = u.tin > 0 ? u.cached / u.tin : null;
  const ring = share == null ? "" : DRAW.statTile(DRAW.ring(share, { size: "focal", label: `${DRAW.pct(share)} from the cache` }), DRAW.pct(share), "from cache", { focal: true });
  const io = `${DRAW.focal(u.noIn < u.requests ? DRAW.big(u.tin) : "—", "in")}${DRAW.focal(u.noOut < u.requests ? DRAW.big(u.tout) : "—", "out")}`;
  const fresh = Math.max(0, u.tin - u.cached - u.written);
  const split = u.tin > 0 ? splitBar([{ n: u.cached, text: DRAW.big(u.cached), word: "From cache", cls: "s1" }, { n: u.written, text: DRAW.big(u.written), word: "Written", cls: "s2" },
    { n: fresh, text: DRAW.big(fresh), word: "New", cls: "s3" }], u.tin, { small: true, bw: 24, grow: ui.grow.has(`u:${s.id}`), label: "Input tokens by where they came from" }) : "";
  return `<div class="tokhead">${ring}<div class="io">${io}</div></div>${split || EMPTY_SPLIT}`;
}
/** CONTEXT: how full the conversation's window is — the newest request's input against the room its model has, as
 *  Accounts draws a window. It is the share the client itself compacts on, so it says when a compaction is near. The
 *  model leads: it is what the room is measured against. */
function contextHtml(s) {
  const u = state.usage[s.id], win = u?.ctx?.model ? state.window[u.ctx.model] ?? state.window[modelKey(u.ctx.model)] : null;
  // a move in flight names its target model in the state (hitstop), so the model reads a dash meanwhile
  const model = `<p class="model"${s.model ? ` title="${esc(s.model)}"` : ""}>${s.model && !ui.moving.has(s.id) ? esc(s.model) : DRAW.dash}</p>`;
  if (u === undefined) return `${model}<div class="meterline">${GHOST.track()}${GHOST.fig(5)}</div>`;
  // nothing has gone out yet: the window's empty track; the newest request's figure with no room to measure it against
  // (a model splice was never told the size of): a dashed track with the figure that went in
  if (!u?.ctx?.in) return `${model}<div class="meterline">${DRAW.meter(null)}<span class="pct">${DRAW.dash}</span></div>`;
  if (!win) return `${model}<div class="meterline" title="The size of its window is not known">${DRAW.meter(null)}${DRAW.focal(DRAW.big(u.ctx.in), "in")}</div>`;
  const share = Math.min(1, u.ctx.in / win);
  return `${model}<div class="meterline" data-meter="x:${esc(s.id)}">${DRAW.meter(share * 100, share >= 1 ? "full" : "")}`
    + `<span class="pct">${DRAW.pct(share)}<span class="used">used</span></span>${DRAW.focal(DRAW.big(u.ctx.in), `of ${DRAW.big(win)}`)}</div>`;
}
/** THE LINES TO PASTE: what another session types to reach this one, what a terminal types to resume it, and where its
 *  conversation lives on disk (gap 423), as three Copy acts in his words, and the three lines whole behind one
 *  disclosure, so no line is ever cut and no id or path stands on the screen unasked. */
function copiesHtml(s) {
  const held = state.logs[s.id];
  const items = [["copy-send", "Reach it", sendTo(s) ? sendLine(s) : ""], ["copy-r", "Resume it", resumeLine(s)], ["copy-path", "Its file", held == null ? null : held.path || ""]];
  const open = ui.lines.has(s.id);
  const acts = items.map(([act, word, v]) => (v === "" ? "" : v == null ? GHOST.bar(5.5, "chip")
    : `<button class="act small copy" data-act="${act}" data-s="${esc(s.id)}">${ui.copied.get(`${act}:${s.id}`) > Date.now() ? "Copied" : esc(word)}</button>`)).join("");
  if (!acts) return "";
  const disc = DRAW.disc({ open, act: "lines", data: ` data-s="${esc(s.id)}"`, cls: "quiet linesdisc", inner: `<span>${open ? "Lines" : "Show lines"}</span>` });
  const lines = open ? `<dl class="clines unfold">${items.filter(([, , v]) => v).map(([, word, v]) => `<div><dt>${esc(word)}</dt><dd><code>${esc(v)}</code></dd></div>`).join("")}</dl>` : "";
  return `<div class="copies"><h3 class="kicker">Copy</h3><div class="cacts">${acts}</div>${disc}${lines}</div>`;
}

// A door to a session splice no longer lists, by link or address: a compact plate at the list's top says Not listed,
// with the search for it and Close, and No transcript where its conversation would be (Marlin, Oct 12; fin's word).
function missingHtml(id) {
  const close = `<button class="act quiet" data-act="close">Close</button>`;
  // nothing about it can be read, so the one thing it can offer is the search for its name among the rest
  const search = `<button class="act small" data-act="findmissing" data-p="${esc(String(id).slice(0, 8))}">Search for it</button>`;
  return `<section class="pane missing" data-key="m:${esc(id)}" aria-label="Session"><header class="mtop"><div class="who"><span class="name">Session</span></div>`
    + `<span class="pstate"><span class="word ended">Not listed</span></span><div class="acts">${search}${close}</div></header>`
    + `<div class="log"><p class="empty">No transcript</p></div></section>`;
}

/** The choice on a permission's screen that refuses it ("No, and tell Claude what to do differently"), or null. */
const denyOf = (s) => state.screens[s.id]?.choices?.find((c) => /^no\b/i.test(c.label)) ?? null;

// The Message field, only on a session splice can type into. While a permission waits, it is Deny: the refusing choice
// is pressed, then his words, if any, go in as what to do instead. While a question waits, its options answer it and
// no field is drawn, since his own words would land in the question's menu rather than as an answer.
/** The command that resumes this session in a terminal, copyable, whenever its command is known (gap 419). */
const resumeLine = (s) => (s.cmd && !QUIET.has(s.cmd) ? `${s.cmd} -r ${s.id}` : "");
/** A note to a session splice did not open: it arrives as a message from another session, and the page says only that it
 *  was handed over, since the client gives no receipt (SessionNoteRoute). */
function noteHtml(s) {
  const busy = ui.sending.has(s.id) ? " disabled" : "";
  const sent = ui.noted.has(s.id) ? DRAW.result("Note sent", "good") : "";
  return `<form class="composer note" data-s="${esc(s.id)}" data-note="1">${sent}<input class="field" id="say" placeholder="Note to this session" aria-label="Note to this session" autocomplete="off" value="${esc(ui.drafts[s.id] || "")}"${busy}>`
    + `<button class="send" type="submit" aria-label="Send note"${busy}>${ICON.send}</button></form>`;
}
function composerHtml(s) {
  if (!drivable(s)) return notable(s) ? noteHtml(s) : "";
  const dialog = s.state === "needs" && s.ask.kind === "dialog";
  if (s.state === "needs" && (!dialog || !denyOf(s))) return "";
  const busy = ui.sending.has(s.id) ? " disabled" : "";
  // words already in its prompt that are not his stopped message: shown over Send like a console menu, so the answer
  // to his press appears where he pressed, and cleared only on his say-so (Marlin; hitstop 0704658, fin's words)
  const left = ui.leftover[s.id];
  const leftover = left == null ? "" : `<div class="inprompt" role="group" aria-label="In the prompt"><div class="inprompt-card"><p class="lbl">In the prompt</p>`
    + `<p class="words">${esc(left)}</p><div class="acts"><button class="act" data-act="keep" data-s="${esc(s.id)}">Cancel</button>`
    + `<button class="act primary" data-act="clear-send" data-s="${esc(s.id)}"${busy}>Clear and send</button></div></div></div>`;
  return leftover + `<form class="composer" data-s="${esc(s.id)}"><input class="field" id="say" placeholder="${dialog ? "What to do instead" : "Message"}" aria-label="Message" autocomplete="off" value="${esc(ui.drafts[s.id] || "")}"${busy}>`
    + (dialog ? `<button class="act deny" type="submit"${busy}>Deny</button></form>` : `<button class="send" type="submit" aria-label="Send"${busy}>${ICON.send}</button></form>`);
}

// ---------- render: one keyed draw that changes only what changed ----------
// A DRAW KEEPS WHAT IS ON THE SCREEN (round 2's bug list: a press, and the focus on a row or a button, were lost to the
// five-second read). The page used to write its whole markup again on every read, so the element under his pointer or
// his keyboard was a new one each time. The new markup is now laid over the old: an element with a data-key stays the
// same element wherever it moves, and every other element is kept in place when it is the same kind, so a press, a
// focus, a running motion and a half-typed message survive every read.
const SRC = new WeakMap(); // a split the kit fits after it lands, by the markup it was drawn from
const FITS = ".pd-pop";
function rememberFits(node) {
  if (node.nodeType !== 1) return;
  if (node.matches(FITS)) SRC.set(node, node.outerHTML);
  for (const el of node.querySelectorAll(FITS)) SRC.set(el, el.outerHTML);
}
const keyOf = (n) => (n.nodeType === 1 ? n.getAttribute("data-key") : null);
function morphChildren(from, to) {
  const keyed = new Map();
  for (let c = from.firstElementChild; c; c = c.nextElementSibling) { const k = keyOf(c); if (k) keyed.set(k, c); }
  let cur = from.firstChild;
  for (let next = to.firstChild; next; ) {
    const after = next.nextSibling, key = keyOf(next);
    let match = null;
    if (key) { const k = keyed.get(key); if (k && k.tagName === next.tagName) { match = k; keyed.delete(key); } }
    else if (cur && !keyOf(cur) && cur.nodeType === next.nodeType && (cur.nodeType !== 1 || (cur.tagName === next.tagName && cur.id === next.id))) match = cur;
    if (match) {
      if (match === cur) cur = cur.nextSibling; else from.insertBefore(match, cur);
      morphNode(match, next);
    } else {
      rememberFits(next);
      from.insertBefore(next, cur);
    }
    next = after;
  }
  while (cur) { const n = cur.nextSibling; cur.remove(); cur = n; }
}
// a painted meter's width is the kit's (DRAW.paint), set after the markup lands: a draw leaves it be
const painted = (el) => (el.tagName === "B" && el.hasAttribute("data-v") && el.parentElement?.classList.contains("bar"))
  // a block on the balanced board keeps the place the kit measured for it (its rows and columns), which a draw cannot know
  || el.parentElement?.classList.contains("mason");
function morphNode(a, b) {
  if (a.nodeType !== 1) { if (a.nodeValue !== b.nodeValue) a.nodeValue = b.nodeValue; return; }
  // a split the kit fitted after it landed is kept whole while it would draw the same, and replaced when it would not
  if (a.matches(FITS)) {
    const src = b.outerHTML;
    if (SRC.get(a) !== src) { SRC.set(b, src); a.replaceWith(b); }
    return;
  }
  const keepStyle = painted(a);
  for (const { name } of [...a.attributes]) if (!b.hasAttribute(name) && !(keepStyle && name === "style")) a.removeAttribute(name);
  for (const { name, value } of [...b.attributes]) if (a.getAttribute(name) !== value && !(keepStyle && name === "style")) a.setAttribute(name, value);
  if (a.tagName === "INPUT") { const v = b.getAttribute("value") ?? ""; if (a.value !== v) a.value = v; return; }
  morphChildren(a, b);
}
function morph(target, html) {
  const tpl = document.createElement("template");
  tpl.innerHTML = html;
  morphChildren(target, tpl.content);
}
/** How many columns of blocks the list's width holds: a row's columns need about 72rem for the session's own words to
 *  read (Oct 26), so three at 3822, two at 2548 and one at 1440. */
const COL_REM = 72;
function colCount() {
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  const gap = (parseFloat(getComputedStyle(root).getPropertyValue("--gap-col")) || 2) * rem;
  return Math.max(1, Math.floor((root.clientWidth + gap) / (COL_REM * rem + gap)));
}
function render(opts = {}) {
  const { slide = false, settle = false } = opts;
  const before = (slide && !ui.unfold) || settle ? spots() : null;
  // the five-second read redraws the page: a name he had the keyboard on keeps it, on the row or on its open plate
  const active = document.activeElement;
  const named = active?.matches?.("button.name[data-open]") ? active.dataset.open : null;
  const say = active?.id === "say" ? [active.selectionStart, active.selectionEnd] : null;
  const naming = active?.id === "nm" ? [active.selectionStart, active.selectionEnd] : null;
  root.className = "sessions";
  ui.grouped = grouped(); // before anything draws: the cards and the plate read it, and one session has one name on every page
  nameRows();
  ui.wide = ui.grouped && ui.by === "project" && !state.loading && tilesWide();
  ui.cols = colCount();
  morph(root, listHtml());
  const seen = [...new Set([...root.querySelectorAll(".list .nocmd.mark")].map((el) => el.dataset.q))].sort().join("");
  if (seen !== (ui.quietSeen || "")) { ui.quietSeen = seen; morph(root, listHtml()); }
  fitTracks(); // the rows' tracks as wide as what they hold, before anything is measured
  // the blocks' places on the balanced board are measured now, so the places the motion below starts from and ends at are the
  // real ones and not the ones before the kit's own observer has laid the board
  for (const box of root.querySelectorAll(".mason")) layMason(box);
  paintBand();
  if (named && !root.contains(active)) root.querySelector(`button.name[data-open="${CSS.escape(named)}"]`)?.focus({ preventScroll: true });
  const field = root.querySelector("#say");
  if (field && (ui.focusSay || (say && document.activeElement !== field))) {
    field.focus({ preventScroll: !ui.focusSay });
    if (say) field.setSelectionRange(...say);
    ui.focusSay = false;
  }
  const nameField = root.querySelector("#nm");
  if (nameField && (ui.focusName || (naming && document.activeElement !== nameField))) {
    nameField.focus({ preventScroll: !ui.focusName });
    if (naming) nameField.setSelectionRange(...naming); else nameField.select();
    ui.focusName = false;
  }
  // the conversation he opened is in the address, on the door Teams and Requests use, so Back returns to it; while the
  // first read is out nothing is open yet, and the door he came by stays in the address (round 2's bug list)
  if (!state.loading) {
    const addr = ui.open ? `#${ui.open}` : ui.missing ? `#${ui.missing}` : "";
    if (decodeURIComponent(location.hash) !== addr) history.replaceState(history.state, "", `${location.pathname}${location.search}${addr}`);
  }
  if (before) (settle ? settleFrom : slideFrom)(before);
  land();
  reveal();
  ui.unfold = null;
}
// ---------- motion: only what changed moves ----------
/** Every meter and ring in the open session takes its width once the markup is in, and a figure that moved grows into it. */
function paintBand() {
  DRAW.paint(root, { grow: ui.grow.size ? ui.grow : null });
  ui.grow.clear();
}
/** WHAT A PRESS OPENED LANDS, AND A FIGURE THAT CHANGED MOVES TO ITS NEW VALUE (gap: a fold's rows arrived in one
 *  frame, and counts swapped on every read). The rows a fold or a pill just added fade up in order, and any figure whose
 *  reading changed since the last draw bumps once. Reduced motion gets every final state with no movement. */
function land() {
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (still) { ui.landing.clear(); for (const el of root.querySelectorAll("[data-f]")) ui.figs.set(el.dataset.f, el.textContent); return; }
  const rows = [...root.querySelectorAll(".list .s.landing")];
  rows.slice(0, 10).forEach((el, i) => el.animate([{ opacity: 0, transform: "translateY(-.4rem)" }, { opacity: 1, transform: "none" }],
    { duration: 240, delay: i * 20, easing: tok("--e-out") || "ease-out", fill: "backwards" }));
  // the calls a run's count line just opened fade in where they land, as rows a fold adds do
  [...root.querySelectorAll(".log .call.landing > .tool")].slice(0, 12).forEach((el, i) => el.animate(
    [{ opacity: 0, transform: "translateY(-.3rem)" }, { opacity: 1, transform: "none" }],
    { duration: 240, delay: i * 20, easing: tok("--e-out") || "ease-out", fill: "backwards" }));
  ui.landing.clear();
  for (const el of root.querySelectorAll("[data-f]")) {
    const key = el.dataset.f, now = el.textContent, was = ui.figs.get(key);
    ui.figs.set(key, now);
    if (was !== undefined && was !== now) el.animate([{ transform: "translateY(-.3rem)", opacity: .35 }, { transform: "none", opacity: 1 }],
      { duration: parseFloat(tok("--t-snap")) || 240, easing: tok("--e-out") || "ease-out" });
  }
}
/** A SESSION HE OPENS COMES ON SCREEN: its title bar under the top bar when its row sat low, so the plate unfolds where he
 *  reads; one that is already on screen stays where it is. */
function reveal() {
  if (!ui.reveal || !ui.open) return;
  const plate = root.querySelector(`[data-key="p:${CSS.escape(ui.open)}"]`);
  if (!plate) return;
  ui.reveal = false;
  const bar = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--bar-h")) * parseFloat(getComputedStyle(document.documentElement).fontSize) || 52;
  const top = plate.getBoundingClientRect().top;
  if (top >= bar && top <= window.innerHeight * 0.55) return;
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  window.scrollTo({ top: Math.max(0, window.scrollY + top - bar - 16), behavior: still ? "auto" : "smooth" });
}
/** THE DETAILS FOLD AWAY AND COME BACK (Marcos, Oct 26: "we should be able to collapse the top component"). They close
 *  over 240 ms on the same element, so the conversation rises in the same motion; his choice stays in this browser. */
function foldTop() {
  ui.topShut = !ui.topShut;
  localStorage.setItem("splice.sessions.top", ui.topShut ? "shut" : "open");
  ui.lopen.clear(); // the logins close with the details
  render();
}
/** THE ROW'S TRACKS ARE AS WIDE AS WHAT THEY HOLD (Marcos, Oct 26: "we're wasting a lot of horizontal space"): the name
 *  track is the widest name in the list with its branch mark, up to 30rem, the command track the widest command, and
 *  the act track the widest act, none at all when no row has one, so the words start one gap after the widest name and
 *  get the rest. A row with no words runs its name over the words' track, and the name track grows only by what that track cannot hold. The words' track steps in whole 3rem, so a changing tool name seldom shifts the columns. Written on the list's root
 *  only when a width changes, so a draw that changes nothing moves nothing. */
function fitTracks() {
  // the open session's title line is a row of the same tracks: its command chip and its Stop are measured with the rest, or
  // a Stop standing only on the open session has no track and draws over its command
  const rows = root.querySelectorAll(".list:not(.ghosts) .s > .top");
  if (!rows.length) return;
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
  let name = 0, bareName = 0, doing = 0, cmd = 0, act = 0;
  // EACH CELL IS MEASURED AT THE WIDTH ITS OWN CONTENT ASKS FOR (sessions.css .fit): measured inside the track it already
  // has, a name that did not fit read as the width it was squeezed to, and the track caught up only on a later read, so
  // a name stood cut for seconds. All the rows are laid out so in this one pass, then the class comes off before a paint.
  root.classList.add("fit");
  for (const top of rows) {
    // the open title line grows its own name track (sessions.css), so only its command and act count here
    if (!top.parentElement.classList.contains("pane")) {
      const who = top.querySelector(".who");
      if (top.classList.contains("bare")) bareName = Math.max(bareName, who.offsetWidth); else name = Math.max(name, who.offsetWidth);
      const last = top.querySelector(".last");
      if (last?.children.length) doing = Math.max(doing, last.offsetWidth);
    }
    const c = top.querySelector(".cmd > *");
    if (c) cmd = Math.max(cmd, c.offsetWidth);
    const d = top.querySelector(".doing");
    if (d?.children.length) act = Math.max(act, d.offsetWidth);
  }
  root.classList.remove("fit");
  // a row with no words lets its name run over the words' track: the name track grows only by what that track cannot hold
  name = Math.max(name, bareName - (doing ? doing + 2 * .667 * rem : 0));
  // a whole pixel over, since a width is read rounded and a name one pixel short would end in an ellipsis
  const w = (px, lo, hi) => `${Math.min(hi, Math.max(lo, Math.ceil(((px + 1) / rem) * 10) / 10))}rem`;
  for (const [prop, value] of [["--name-w", w(name, 4, 30)], ["--doing-w", doing ? `${Math.min(24, Math.max(4, Math.ceil((doing + 1) / rem / 3) * 3))}rem` : "0rem"], ["--cmd-w", w(cmd, cmd ? 3 : 0, 11)], ["--act-w", act ? w(act, 2, 10) : "0rem"]]) {
    if (root.style.getPropertyValue(prop) !== value) root.style.setProperty(prop, value);
  }
}
// the page's own faces land after the first draw, and every name grows with them
document.fonts?.ready?.then(fitTracks);
/** THE SLIDING MOVE: each row, block and tile starts where it stood and eases to its new place, on the shared list-move
 *  tokens (--t-move on --e-io, hitstop): a re-sort moves them, and a list-sized move arrives on an in-out ease. */
const tok = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const spots = () => new Map([...root.querySelectorAll(".list .s[data-key], .list .pgroup[data-key], .ptile[data-key]")].map((el) => [el.dataset.key, el.getBoundingClientRect()]));
function slideFrom(before) {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  for (const el of root.querySelectorAll(".list .s[data-key], .list .pgroup[data-key], .ptile[data-key]")) {
    const was = before.get(el.dataset.key);
    if (!was) continue;
    const now = el.getBoundingClientRect(), dx = was.left - now.left, dy = was.top - now.top;
    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) continue;
    // a row moving inside a block that moved slides once, with its block
    if (el.matches(".s") && el.closest(".pgroup") && before.has(el.closest(".pgroup").dataset.key)) {
      const g = el.closest(".pgroup"), gw = before.get(g.dataset.key), gn = g.getBoundingClientRect();
      if (Math.abs((gw.left - gn.left) - dx) < 1 && Math.abs((gw.top - gn.top) - dy) < 1) continue;
    }
    el.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: "none" }], { duration: parseFloat(tok("--t-move")) || 420, easing: tok("--e-io") || "ease-in-out" });
  }
}
/** WHAT AN OPENING OR A CLOSING RE-PACKED LANDS IN ITS NEW PLACE: a block or row that a plate sent to another column
 *  fades and settles there over 240 ms, as the plate itself unfolds, never travelling across the plate to get there.
 *  One that only moved down or up, pushed by what opened above it, keeps still, as rows under an opened row do. */
function settleFrom(before) {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const moved = (el) => { const was = before.get(el.dataset.key); return was && Math.abs(was.left - el.getBoundingClientRect().left) >= 1; };
  for (const el of root.querySelectorAll(".list .s[data-key], .list .pgroup[data-key]")) {
    if (!moved(el)) continue;
    // a row lands with its block when the block itself moved
    const g = el.matches(".s") ? el.closest(".pgroup[data-key]") : null;
    if (g && moved(g)) continue;
    el.animate([{ opacity: 0, transform: "translateY(-.4rem)" }, { opacity: 1, transform: "none" }], { duration: parseFloat(tok("--t-snap")) || 240, easing: tok("--e-out") || "ease-out" });
  }
}
// the list holds still while the pointer is over it or focus is inside it; on release it redraws, sliding only if the
// order it held is not the order it would draw
let pointerIn = false;
function holdList() {
  const hold = pointerIn || Boolean(document.activeElement?.closest?.(".list"));
  if (hold === ui.hold) return;
  ui.hold = hold;
  if (hold) return;
  const held = ui.order.join();
  if (sorted().map((s) => s.id).join() !== held) render({ slide: true });
}
root.addEventListener("pointerover", (e) => { pointerIn = Boolean(e.target.closest(".list")); holdList(); });
root.addEventListener("pointerleave", () => { pointerIn = false; holdList(); });
document.addEventListener("focusin", holdList);
document.addEventListener("focusout", () => setTimeout(holdList));
const find = (id) => state.rows.find((s) => s.id === id);

/** The rows a press is about to add: they land with motion on the next draw, and nothing else moves. */
function opening(key) {
  for (const s of state.rows) if (groupKey(s) === key || key === FLAT || key === picked()) ui.landing.add(s.id);
}
/** AN ENDED SESSION OPENS WHERE IT IS LISTED, so a door to one never lands behind a closed fold: its project's Ended
 *  line, or the list's foot where its project has nothing live. One folded under a live namesake needs neither: its
 *  plate is drawn under that row. */
function bringEnded(s) {
  if (!s || s.state !== "ended" || state.rows.some((x) => (ui.earlier.get(x.id) || []).some((e) => e.id === s.id))) return;
  if (state.rows.some((x) => x.state !== "ended" && groupKey(x) === groupKey(s))) ui.rest.add(groupKey(s));
  else if (ui.grouped) { ui.looseOpen.add(groupKey(s)); remember("splice.loose", ui.looseOpen); }
  else ui.ended = true;
}
/** Open a session in place: its plate unfolds under its row, comes on screen, and reads what it holds. */
function open(id, { reveal: show = true } = {}) {
  if (ui.open !== id) { ui.unfold = id; ui.menu = null; ui.renaming = null; ui.lopen.clear(); }
  ui.open = id; ui.missing = null; ui.reveal = show;
  bringEnded(find(id));
  render({ settle: true });
  const s = find(id);
  if (s && !(id in state.logs)) readLog(s); // null: a read at a moment is on its way
  if (s && s.state !== "ended" && !state.screens[id]) readScreen(s);
  if (s && !(id in state.usage)) readUsage(s);
  if (s?.head && !(s.head in ui.logins)) readLogins(s.head);
}
function close() {
  ui.open = null; ui.missing = null; ui.menu = null; ui.renaming = null; ui.lopen.clear();
  render({ settle: true });
}

// ---------- the login a session is on (spec section 11) ----------
/** The logins of one command's pool, by the label its pool knows each by, with the name Accounts gives it. A command
 *  with one login, or with no pool splice switches (its order route refuses), has nothing to switch and keeps an empty list. */
async function readLogins(head) {
  ui.logins[head] = null; // read in flight
  const [res, order] = await Promise.all([API.get("/api/accounts"), API.get(`/api/auth/${encodeURIComponent(head)}/order`)]);
  const rows = (res.body?.accounts || []).filter((r) => (r.heads || []).includes(head));
  // EVERY LOGIN'S ROOM COMES WITH IT (gap: six Use buttons that switched logins blind). Its nearest-full window, as
  // Accounts draws one: what is used of it, and how soon it refills. A command with one login draws it just the same:
  // the room it sends on is the fact, and only the act to switch needs a pool.
  const names = DRAW.names("login", rows.map((r) => ({ name: r.display_name || r.label, provider: state.providerOf[head], plan: r.plan })));
  ui.logins[head] = rows.map((r, i) => ({
    label: r.account_labels?.[head] || r.label, name: names[i].text, plan: r.plan ? r.plan.charAt(0).toUpperCase() + r.plan.slice(1) : null,
    ready: !!r.credential_present && !r.refusal, windows: windowsOf(r),
  }));
  ui.canSwitch[head] = rows.length > 1 && order.ok; // a pool splice switches: its order route answered
  render();
}
/** A login's two windows, the fullest first, each with what it has used and when it comes back; a window whose reset has
 *  gone by is dropped, as Accounts drops it: it is not spent now. */
function windowsOf(r) {
  return [["5 hours", r.five_hour_used_percent, r.five_hour_reset_epoch_seconds, r.five_hour_window_seconds],
    ["Week", r.seven_day_used_percent, r.seven_day_reset_epoch_seconds, r.seven_day_window_seconds]]
    .filter(([, pct, reset]) => pct != null && !(reset && reset * 1000 <= Date.now()))
    .map(([word, pct, reset, len]) => ({ word, pct, until: reset ? new Date(reset * 1000) : null, len: len || null }))
    .sort((a, b) => b.pct - a.pct);
}
// A LOGIN SPLICE NAMES AFTER THE COMMAND'S OWN SIGN-IN reads as that command's login, never as the key the wire carries
// ("native:claude-splice" stood on screen as a login's name).
const loginName = (head, label) => (ui.logins[head] || []).find((l) => l.label === label)?.name
  ?? (String(label).startsWith("native:") ? `${String(label).slice(7)} sign-in` : label);
/** A window's room, as Accounts draws it: the meter, NN% used, and the ring of how soon it refills with the time. */
function windowHtml(win) {
  if (!win) return `<span class="bar unread"></span><span class="pct">${DRAW.dash}</span><span class="ringpad"></span>`;
  const full = win.pct >= 100;
  const ring = win.until && win.len ? DRAW.ring(Math.max(0, Math.min(1, (win.until - Date.now()) / (win.len * 1000))), { size: "reset" }) : `<span class="ringpad"></span>`;
  return `${DRAW.meter(win.pct, full ? "full" : "")}<span class="pct${full ? " limit" : ""}">${Math.round(win.pct)}%<span class="used">used</span></span>${ring}`
    + (win.until ? `<span class="resets">${full ? "Back" : "Resets"} ${esc(backWord(win.until))}</span>` : "");
}
/** Which login this session's turns go out on: its name and plan, its fullest window's room with the ring and the time
 *  it refills, the pin, its last switch on a line of its own, and, on a command with more than one login, the chevron
 *  that opens every login it could move to, in place under it. */
function accountHtml(s) {
  const logins = ui.logins[s.head], r = s.row;
  if (!r.account) return `<p class="lname">${DRAW.dash}</p>`;
  if (logins == null) return `<p class="lname">${GHOST.bar(7)}</p><div class="meterline">${GHOST.track()}${GHOST.fig(3)}</div>`;
  const pin = r.account_pin, own = Boolean(pin) && pin !== r.account_pin_head;
  const now = logins.find((l) => l.label === r.account);
  const more = ui.canSwitch[s.head] && logins.length > 1, open = more && ui.lopen.has(s.id);
  const name = `<span class="lname">${esc(loginName(s.head, r.account))}${now?.plan ? `<span class="pv">${esc(now.plan)}</span>` : ""}</span>`;
  const line = more ? DRAW.disc({ open, controls: `logins-${esc(s.id)}`, inner: name, act: "logins", data: ` data-s="${esc(s.id)}"`, cls: "lhead-row" }) : name;
  const pinned = own ? `<span class="state">Pinned here</span>` : pin ? `<span class="state">Pinned on ${esc(s.cmd)}</span>` : "";
  return `<div class="lline">${line}${pinned}</div><div class="meterline" data-meter="l:${esc(s.id)}:${esc(r.account)}">${windowHtml(now?.windows?.[0] ?? null)}</div>`
    + switchHtml(s, r) + (open ? loginRows(s, logins, r, own) : "");
}
/** WHERE ITS TURNS MOVED (gap: "Moved from native:claude-splice to Apple 6:53 PM: previous login needs sign-in"): the
 *  two logins by name with a caret between, then splice's own reason and when, on a line of its own. */
function switchHtml(s, r) {
  const sw = r.account_switch;
  if (!sw) return "";
  const from = loginName(s.head, sw.from), to = loginName(s.head, sw.to), when = backWord(new Date(sw.at_epoch_millis));
  return `<p class="moved"><span class="chip quiet">${esc(from)}</span>${ICON.caret}<span class="chip quiet">${esc(to)}</span><span class="reason">${esc(sw.reason)}</span><span class="stamp">${esc(when)}</span></p>`;
}
/** Every login this command can send on, as Accounts draws an account's windows: its name and plan, its fullest window
 *  used, how soon that window refills, and its one act in the same place on every row. A login at its limit keeps
 *  the lock and still takes the session: the move is made when its window refills (round 2, restored). */
function loginRows(s, logins, r, own) {
  const busy = ui.pinning === s.id;
  const rows = logins.map((l) => {
    const on = l.label === r.account, win = l.windows?.[0] ?? null, full = win && win.pct >= 100;
    const pinnedHere = l.label === r.account_pin && !on;
    const use = (word) => `<button class="act small" data-act="pin-login" data-s="${esc(s.id)}" data-label="${esc(l.label)}">${word}</button>`;
    const act = busy ? `<span class="state">${ICON.wait}Switching</span>`
      : on ? (own ? `<button class="act quiet small" data-act="unpin-login" data-s="${esc(s.id)}">Unpin</button>` : `<span class="state on">Sending</span>`)
      : !l.ready ? `<a class="act quiet small" href="accounts.html?head=${encodeURIComponent(s.head)}">Sign in</a>`
      : pinnedHere ? `${full ? DRAW.lock("At limit") : ""}<span class="state">Pinned${full ? " · at refill" : ""}</span><button class="act quiet small" data-act="unpin-login" data-s="${esc(s.id)}">Unpin</button>`
      : full ? `${DRAW.lock("At limit")}${use("Use at refill")}`
      : use("Use");
    return `<div class="lrow${on ? " on" : ""}" data-meter="l:${esc(s.id)}:${esc(l.label)}">`
      + `<span class="lname">${esc(l.name)}${l.plan ? `<span class="pv">${esc(l.plan)}</span>` : ""}</span>${windowHtml(win)}`
      + `<span class="lact">${act}</span></div>`;
  }).join("");
  return `<div class="lrows unfold" id="logins-${esc(s.id)}">${rows}</div>`;
}
/** Pin this session alone to a login, from its next turn; the rest of the command keeps its rule (SwitchRoute). */
async function pinLogin(s, label) {
  ui.pinning = s.id; render();
  const res = label ? await API.post(`/api/auth/${encodeURIComponent(s.head)}/switch`, { label, session: s.id })
    : await API.del(`/api/auth/${encodeURIComponent(s.head)}/switch?session=${encodeURIComponent(s.id)}`);
  ui.pinning = null;
  if (!res.ok) ui.failed.set(s.id, refusedAs(res, label ? "Not switched" : "Not unpinned", `pin ${s.id}`));
  await reload();
}
/** What the session used today, on every command it ran on: the same per-session totals Requests sums
 *  (GET /api/perf/turns?session=), from local midnight. Null until read, and kept null when no command answered. */
const readUsage = (s) => alone(`usage ${s.id}`, () => { usageAt.set(s.id, Date.now()); return readUsageNow(s); });
// TODAY'S USAGE IS READ AGAIN EVERY 30 S, NOT ON EVERY 5 S RELOAD. It is one /api/perf/turns per command, 11 of them
// taking up to 1.8 s, and on every reload they held the browser's six connections to splice, so a card clicked then
// waited behind them: 1.5 s to its first text where a free connection gives 0.2 s (splice-sessions, Oct 13, at 3394).
const USAGE_EVERY_MS = 30_000;
const usageAt = new Map();
async function readUsageNow(s) {
  const from = new Date(); from.setHours(0, 0, 0, 0);
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const heads = [...state.heads, ...state.retired]; // a removed command's requests today are still the session's
  const reads = await Promise.all(heads.map((h) => API.get(`/api/perf/turns?${new URLSearchParams({
    head: h.key, since: String(+from), time_zone: zone, n: "1", local: "0", session: s.id.slice(0, 8) })}`)));
  let seen = false; const used = { requests: 0, tin: 0, tout: 0, cached: 0, written: 0, noIn: 0, noOut: 0, cost: 0, priced: 0, plan: 0,
    noPrice: 0, local: 0, unans: 0, cold: 0, failed: 0, ctx: null, from: +from };
  reads.forEach((r, i) => {
    const head = (r.body?.heads || []).find((x) => x.key === heads[i].key), t = head?.usage?.totals;
    if (!t) return;
    seen = true; used.requests += t.requests || 0; used.tin += t.input_tokens || 0; used.tout += t.output_tokens || 0; used.cached += t.cached_tokens || 0; used.written += t.cache_write_tokens || 0;
    used.noIn += t.missing_input_requests || 0; used.noOut += t.missing_output_requests || 0;
    used.cold += t.cold_requests || 0; used.failed += t.failed_requests || 0;
    // THE ROOM ITS CONVERSATION HAS LEFT comes off the newest request this session sent: what went in, and the model it
    // went to, whose window the catalog declares (/api/models). The same read already asks for that row.
    const newest = head.rows?.[0];
    if (newest?.ts != null && newest.in_tokens != null && (used.ctx == null || newest.ts > used.ctx.ts)) {
      used.ctx = { ts: newest.ts, in: newest.in_tokens, model: newest.model ?? null };
    }
    // a request that moved no tokens (refused, cut) is no money fact, and a local model's costs nothing: neither is counted
    const plan = t.unpriced_plan_requests || 0, local = t.unpriced_local_requests || 0;
    const moved = Math.max(0, (t.requests || 0) - (t.unanswered_requests || 0) - local);
    const unpriced = Math.max(0, (t.unpriced_requests || 0) - local), priced = Math.max(0, moved - unpriced);
    used.plan += plan; used.noPrice += unpriced - plan; used.local += local; used.unans += t.unanswered_requests || 0;
    if (t.cost_usd == null) used.noPrice += priced; else { used.cost += t.cost_usd; used.priced += priced; }
  });
  // a figure that moved since the last read grows into its new place, once; the first figures grow in as they land
  const was = state.usage[s.id];
  if (seen && (!was || was.requests !== used.requests || was.tin !== used.tin)) ui.grow.add(`u:${s.id}`);
  state.usage[s.id] = seen ? used : null;
  if (ui.open === s.id) render();
}
/** One session's screen, read the moment it opens, so its Message field is there without waiting for the next read. */
async function readScreen(s) {
  const res = await API.get(sessionPath(s, "screen"));
  if (res.ok && ui.open === s.id) { state.screens[s.id] = res.body; render(); }
}

// ---------- the acts ----------
const sessionPath = (s, act) => `/api/sessions/${encodeURIComponent(s.id)}/${act}`;

// Stop ends the running turn. In a terminal splice opened it presses stop, as the person would (SessionDrive.kt), which
// leaves the session waiting for his next message; elsewhere it ends the turn at the head (LiveTurnsRoutes.kt). A 404
// from the head means the turn had already ended: the card just updates.
async function stop(s) {
  if (!s.turn && !drivable(s)) return;
  ui.stopping.add(s.id); ui.failed.delete(s.id); render();
  const res = drivable(s) ? await API.post(sessionPath(s, "stop"))
    : await API.post(`/api/heads/${encodeURIComponent(s.turn.head)}/turns/${encodeURIComponent(s.turn.id)}/stop`);
  ui.stopping.delete(s.id);
  const refused = stopRefusal(res);
  if (refused) ui.failed.set(s.id, refused);
  await reload();
}

// ---------- End ----------
// End closes the session where it runs, in any state; Stop only halts a turn and keeps the agent (fin, Marlin p218).
// With nothing to lose it goes on the first press; a turn mid-work or unsent words arm it, Restart's pattern.
const endHtml = (s) => !drivable(s) || ui.armedEnd.has(s.id) ? ""
  : ui.ending.has(s.id) ? `<button class="act stopping" disabled>${ICON.wait}Ending · ${Math.floor((Date.now() - ui.ending.get(s.id)) / 1000)} s</button>`
  : `<button class="act" data-act="end" data-s="${esc(s.id)}">End</button>`;
function armedEndHtml(s) {
  const armed = ui.armedEnd.get(s.id);
  if (!armed) return "";
  const lost = armed.draft != null ? `<span class="said">Unsent</span><span class="unsent">${esc(armed.draft)}</span>`
    : `<span class="said">${ICON.wait}Working</span>`;
  return `<div class="armed end">${lost}<button class="act danger" data-act="end-now" data-s="${esc(s.id)}">End</button>` +
    `<button class="act quiet" data-act="end-keep" data-s="${esc(s.id)}">Keep running</button></div>`;
}
async function endSession(s, confirm) {
  ui.armedEnd.delete(s.id); ui.failed.delete(s.id);
  ui.ending.set(s.id, Date.now()); render();
  const tick = setInterval(() => render(), 1000);
  const res = await API.post(sessionPath(s, "end"), confirm ? { confirm: true } : {});
  clearInterval(tick); ui.ending.delete(s.id);
  const why = res.body?.reason;
  if (!res.ok && why !== "ended") {
    if (!confirm && why === "working") ui.armedEnd.set(s.id, {});
    else if (!confirm && why === "draft") ui.armedEnd.set(s.id, { draft: res.body?.draft ?? "" });
    else { if (res.body?.error) console.warn("end refused:", res.body.error); ui.failed.set(s.id, why === "not_ours" ? "Not ended · Started elsewhere" : "Not ended"); }
  }
  await reload();
}

// ---------- Continue on ----------
// The session goes on under another command's model: on another command as its copy under a new id, the original left
// as it was (the President's ruling on V4-169), and on the same command as itself (POST .../continue,
// SessionContinue). A session waiting on an answer can't move, since the move types into its prompt; one splice can't
// reach can't either, except one whose client already exited, which goes on in a new terminal.
const movable = (s) => s.state !== "needs" && (s.state === "ended" || drivable(s)) && state.heads.some((h) => h.key !== s.head);

/** One line per model on each other command, on the account that command would send as now (its pool's next pick,
 *  else its one account), with that account's nearest-full window as Accounts draws it (the mock's choicesOf, fin).
 *  Commands with room first, the soonest week to reset first and the 5 hours breaking a tie (his Oct 4 rule); keys,
 *  which report no quota, after them; then a command at its limit or signed out, disabled. */
async function readChoices(s) {
  const [ac, md, ks] = await Promise.all(["/api/accounts", "/api/models", "/api/keys"].map((p) => API.get(p)));
  const rows = ac.body?.accounts || [], keys = ks.body?.keys || [];
  // its own command is offered too, without the model it runs on now: another model on the same plan is a move (p176)
  const now = s.turn?.model ?? s.row.model ?? s.row.served_model; // reading off: what splice served it
  const groups = (await Promise.all((md.body?.heads || []).filter((h) => h.models?.length).map(async (h) => {
    const names = h.models.map((m) => ({ id: m.id, label: m.label || m.id }))
      .filter((n) => h.head !== s.head || (n.id !== now && n.label !== s.model));
    if (!names.length) return null;
    // a command splice cannot start says so, with its fix, before the press: a move onto it would only be refused
    const unstartable = state.heads.find((x) => x.key === h.head)?.cannotStart;
    if (unstartable) return { head: h.head, own: h.head === s.head, names, kind: "unlinked", fix: unstartable.match(/(splice install \S+?)\.?$/)?.[1] ?? null, rank: 4 };
    if (state.heads.find((x) => x.key === h.head)?.authKind === "api-key") {
      // a key reads as its provider, the heading Accounts draws over it; its variable joins only when two keys of
      // that provider feed this command, so the line says which one it sends (fin)
      const feeding = keys.filter((k) => (k.heads || []).some((x) => x.head === h.head)), key = feeding[0];
      const missing = !key || key.heads.find((x) => x.head === h.head).source === "missing";
      const provider = PROVIDER_NAME[state.providerOf[h.head]] ?? key?.name ?? null;
      return { head: h.head, own: h.head === s.head, names, kind: missing ? "signedout" : "key", acct: provider, keyVar: feeding.length > 1 ? key.name : null, rank: missing ? 3 : 2 };
    }
    const mine = rows.filter((r) => (r.heads || []).includes(h.head));
    const next = mine.length > 1 ? (await API.get(`/api/auth/${encodeURIComponent(h.head)}/order`)).body?.next_target : null;
    const row = mine.find((r) => (r.account_labels?.[h.head] || r.label) === next) || mine.find((r) => r.credential_present) || mine[0];
    const near = [[row?.five_hour_used_percent, row?.five_hour_reset_epoch_seconds], [row?.seven_day_used_percent, row?.seven_day_reset_epoch_seconds]]
      // a window whose reset has gone by holds the ended window's figure, which Accounts drops too: it is not spent now
      .filter(([pct, reset]) => pct != null && !(reset && reset * 1000 <= Date.now())).reduce((a, w) => (!a || w[0] > a[0] ? w : a), null);
    const kind = !row || !row.credential_present || row.refusal ? "signedout" : near && near[0] >= 100 ? "limit" : "room";
    return { head: h.head, own: h.head === s.head, names, kind, acct: row?.display_name ?? null, plan: row?.plan ? row.plan.charAt(0).toUpperCase() + row.plan.slice(1) : null,
      used: near ? Math.round(near[0]) : null, until: near?.[1] ? new Date(near[1] * 1000) : null, rank: kind === "room" ? 0 : 3,
      long: row?.seven_day_reset_epoch_seconds ?? Infinity, short: row?.five_hour_reset_epoch_seconds ?? Infinity };
  }))).filter(Boolean);
  // with room first, and among those its own command first; then the rest by when their windows come back
  return groups.sort((x, y) => x.rank - y.rank || (y.own ? 1 : 0) - (x.own ? 1 : 0) || x.long - y.long || x.short - y.short);
}
/** CONTINUE ON, ONE COMMAND AT A TIME: its chip, the account it would send as and that account's room, said once on
 *  the command's own line, then each of its models as a press under it, the first sixteen standing and the rest behind
 *  one count that opens them in place. A command at its limit, signed out or not installed says so on its line, and its
 *  models stay in view, disabled. */
const MODELS_SHOWN = 16;
function choicesHtml(s) {
  const groups = ui.choices[s.id];
  if (!groups) return "";
  const acctOf = (g) => (!g.acct ? "" : g.keyVar !== undefined
    ? `<span class="acct">${esc(g.acct)}${g.keyVar ? ` · <span class="var">${esc(g.keyVar)}</span>` : ""}</span>` : acctPlanHtml(g.acct, g.plan));
  return `<div class="menu models" role="menu">${groups.map((g) => {
    const room = g.kind === "room" && g.used != null ? `<span class="room"><span class="bar sub"><b style="width:${g.used}%"></b></span><span class="pct">${g.used}%<span class="used">used</span></span></span>`
      : g.kind === "limit" ? `<span class="room out"><span class="pct limit">100%<span class="used">used</span></span>${g.until ? `${ICON.lock}Resets ${backWord(g.until)}` : ""}</span>`
      : g.kind === "unlinked" ? `<span class="room out">Not installed${g.fix ? ` · <code>${esc(g.fix)}</code>` : ""}</span>`
      : g.kind === "signedout" ? `<span class="room out">Signed out</span>` : "";
    const off = g.kind === "limit" || g.kind === "signedout" || g.kind === "unlinked";
    const key = `${s.id}|${g.head}`, all = ui.allModels.has(key), left = g.names.length - MODELS_SHOWN;
    const more = left > 0 ? DRAW.more(left, { word: "more", act: "models", data: ` data-k="${esc(key)}"`, open: all, less: "Fewer" }) : "";
    const names = (all ? g.names : g.names.slice(0, MODELS_SHOWN)).map((n) => `<button role="menuitem" class="line" data-act="continue" data-s="${esc(s.id)}" data-head="${esc(g.head)}" data-model="${esc(n.id)}" data-label="${esc(n.label)}"${off ? " disabled" : ""}>${esc(n.label)}</button>`).join("") + more;
    return `<div class="mgroup${off ? " off" : ""}" role="group" aria-label="${esc(commandOf(g.head) ?? g.head)}"><div class="mhead">${cmdChip(g.head)}${acctOf(g)}${room}</div><div class="mnames">${names}</div></div>`;
  }).join("")}</div>`;
}
async function openChoices(s) {
  if (ui.menu === s.id) { ui.menu = null; render(); return; }
  delete ui.held[s.id]; // opening the models menu dismisses the held card (hitstop)
  ui.menu = s.id; ui.choices[s.id] = await readChoices(s);
  if (ui.menu === s.id) render();
}
/** Move it, then read the session again: it comes back Idle on the new command, and his next message runs there. */
async function continueOn(s, head, model, label, clear = false) {
  ui.menu = null; ui.moving.set(s.id, { head, label: label || head }); ui.failed.delete(s.id); render();
  const res = await API.post(sessionPath(s, "continue"), clear ? { head, model, clear: true } : { head, model });
  ui.moving.delete(s.id);
  // the prompt holds words he never sent: they are his, so the card shows them and nothing moves until he says
  if (!res.ok && res.body?.reason === "draft") {
    ui.held[s.id] = { words: String(res.body.draft ?? ""), head, model, label: label || head };
    render();
    return;
  }
  delete ui.held[s.id];
  // a move between commands goes on as its copy, under its own id (SessionContinue): the open session follows it there
  if (res.ok) { ui.focusSay = true; if (res.body?.session_id && ui.open === s.id) ui.open = res.body.session_id; }
  // a move that did not come back went on where it was (MoveArrival): its card says so, with Try again
  else if (res.body?.moved === false) ui.failed.set(s.id, { ...res.body, unmoved: true, to: head, model, label: label || head, was: s.head, wasModel: s.model });
  else ui.failed.set(s.id, refusedAs(res, "Not continued", `move ${s.id}`));
  await reload();
}
/** Copy his words, then clear and move. The clear waits on the clipboard: a button that says Copy never clears words
 *  it did not copy, so a refused write leaves the card up and the session where it is (Marlin, fin). */
async function heldCopy(s, button) {
  const h = ui.held[s.id];
  if (!h || h.copying) return; // a press while the copy is pending does nothing (hitstop 439f414)
  h.copying = true;
  try {
    await navigator.clipboard.writeText(h.words);
  } catch {
    h.copying = false;
    button.textContent = "Could not copy";
    return;
  }
  delete ui.held[s.id];
  await continueOn(s, h.head, h.model, h.label, true);
}

/** Words he typed and never sent, held under Continue on: the stopped message Claude Code put back, and anything he
 *  typed while the turn ran. They are his, so they are shown verbatim and go to his clipboard before anything is
 *  cleared (fin, hitstop 605ab5c). Cancel moves nothing. */
function heldHtml(s) {
  const h = ui.held[s.id];
  if (!h) return "";
  return `<div class="inprompt onmove" role="group" aria-label="In the prompt"><div class="inprompt-card">`
    + `<p class="lbl">In the prompt</p><p class="words">${esc(h.words)}</p>`
    + `<div class="acts"><button class="act" data-act="held-cancel" data-s="${esc(s.id)}">Cancel</button>`
    + `<button class="act primary" data-act="held-copy" data-s="${esc(s.id)}">Copy and continue on</button></div></div></div>`;
}

/** A command as the move mark draws it: its chip, in its provider's colour. */
const cmdChip = (head) => `<span class="chip" style="--c:${colorOf(state.providerOf[head])}">${esc(commandOf(head) ?? head)}</span>`;
/** While a move is in flight its old client has exited, so the session reads as the move, never as Ended: one verb
 *  family with the button and the mark, Continue on, Continuing on, Continued on (fin). */
const movingLook = (s) => {
  const m = ui.moving.get(s.id);
  if (m) return { cls: "moving", lamp: ICON.wait, word: `Continuing on ${cmdChip(m.head)} ${esc(m.label)}`, name: "Continuing on" };
  // when even the way back failed nothing runs on either command: not Idle (at its prompt), never Ended (fin, Marlin)
  if (stranded(s)) return { cls: "needs notrunning", lamp: "<i></i>", word: "Not running", name: "Not running" };
  return null;
};
/** A move that did not happen, as a state: the command that did not start or answer, and where the session is now
 *  (fin). Try again while it went on where it was; when even the way back failed, the resume command and Copy. Opened,
 *  the attempt's last lines come first, the evidence before the ways forward, and only while reading is on (Marlin):
 *  its last lines stand, and the rest is one press away in place, never a box scrolling inside the page. */
const SCREEN_LINES = 8;
function unmovedHtml(s, f, pane) {
  const missed = f.reason === "no_start" ? "didn't start" : `didn't answer in ${f.waited_s ?? 45} s`;
  const where = f.back ? ` · Still on ${cmdChip(f.was)} ${esc(f.wasModel ?? "")}` : ` · ${cmdChip(f.was)} didn't restart`;
  const why = `<p class="why unline">${cmdChip(f.to)} ${missed}${where}</p>`;
  if (!pane) return why;
  let screen = "";
  if (state.reading && f.screen) {
    const lines = String(f.screen).split("\n"), key = `u:${s.id}`, open = ui.full.has(key), left = lines.length - SCREEN_LINES;
    screen = `<pre class="screen">${esc(open || left <= 0 ? f.screen : lines.slice(-SCREEN_LINES).join("\n"))}</pre>`
      + (left > 0 ? DRAW.more(left, { word: "earlier lines", one: "earlier line", act: "full", data: ` data-k="${esc(key)}"`, open, less: "Last lines" }) : "");
  }
  const run = f.back || !f.resume ? ""
    : `<div class="runline"><p class="cmd">${esc(f.resume)}</p><button class="act small" data-act="copy-resume" data-s="${esc(s.id)}">${ui.copied.get(`copy-resume:${s.id}`) > Date.now() ? "Copied" : "Copy"}</button></div>`;
  const again = `<button class="act small" data-act="continue" data-s="${esc(s.id)}" data-head="${esc(f.to)}" data-model="${esc(f.model ?? "")}" data-label="${esc(f.label)}">Try again</button>`;
  return `<div class="unmoved">${why}${screen}${run}${again}</div>`;
}
const failedHtml = (s, f, pane) => (!f ? "" : f.unmoved ? unmovedHtml(s, f, pane) : `<p class="why limit">${esc(f)}</p>`);

/** Press the numbered choice he picked, then read the session again: the card leaves Needs you once its client moves on. */
async function answer(s, choice) {
  ui.answered.set(s.id, { at: Date.now(), choice, offer: state.screens[s.id] }); ui.failed.delete(s.id); render();
  const res = await API.post(sessionPath(s, "answer"), { choice });
  if (!res.ok) { ui.answered.delete(s.id); ui.failed.set(s.id, refusedAs(res, "Not sent", `answer ${s.id}`)); }
  await reload();
}

/** His messages Claude Code took back, which the log already marks: the one leftover the console clears unasked. */
const stoppedWords = (s) => new Set((state.logs[s.id]?.messages || []).filter((m) => m.role === "user" && (m.kind === "taken_back" || m === state.logs[s.id].messages.at(-1))).map((m) => (m.text || "").trim()));
/** His message, whole. While a permission waits, Deny first presses its refusing choice, then gives his words as what to
 *  do instead. A draft that did not go through stays in the field. */
async function send(s, text, clear = false) {
  ui.sending.add(s.id); ui.failed.delete(s.id); delete ui.leftover[s.id]; render();
  const deny = s.state === "needs" ? denyOf(s) : null;
  let res = deny ? await API.post(sessionPath(s, "answer"), { choice: deny.choice }) : { ok: true };
  if (res.ok && text) res = await API.post(sessionPath(s, "say"), clear ? { text, clear: true } : { text });
  // what is sent is exactly what he typed: his own stopped words in the prompt are cleared, anything else is shown
  if (!res.ok && res.body?.reason === "draft") {
    if (stoppedWords(s).has(String(res.body.draft).trim())) res = await API.post(sessionPath(s, "say"), { text, clear: true });
    else { ui.sending.delete(s.id); ui.leftover[s.id] = res.body.draft; render(); return; }
  }
  ui.sending.delete(s.id);
  if (res.ok) ui.drafts[s.id] = "";
  else ui.failed.set(s.id, refusedAs(res, "Not sent", `${deny ? "refusal" : "message"} ${s.id}`));
  ui.focusSay = true;
  await reload();
}
/** His note, whole, to a session splice did not open. A note that did not go through stays in the field. */
async function note(s, text) {
  ui.sending.add(s.id); ui.failed.delete(s.id); ui.noted.delete(s.id); render();
  const res = await API.post(`/api/sessions/${encodeURIComponent(s.id)}/message`, { text });
  ui.sending.delete(s.id);
  if (res.ok) { ui.drafts[s.id] = ""; ui.noted.set(s.id, Date.now()); setTimeout(() => { ui.noted.delete(s.id); render(); }, 4000); }
  else ui.failed.set(s.id, refusedAs(res, "Not sent", `note ${s.id}`));
  ui.focusSay = true;
  await reload();
}
/** Copy a line to his clipboard, and say Copied on the act that did it for a moment. */
const COPIED_MS = 1600;
function copy(act, s, text) {
  if (!text) return;
  navigator.clipboard?.writeText(text).catch(() => {});
  ui.copied.set(`${act}:${s.id}`, Date.now() + COPIED_MS);
  render();
  setTimeout(render, COPIED_MS + 50);
}
// Escape on the in-prompt card is its Cancel: nothing sent, nothing cleared, his message stays in the field (fin)
document.addEventListener("keydown", (e) => {
  // the search is one key away from anywhere on the page, as it is in a console
  if (e.key === "/" && !e.metaKey && !e.ctrlKey && !e.altKey && !/^(?:INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName ?? "")) {
    e.preventDefault(); document.getElementById("q").focus(); return;
  }
  if (e.key === "Escape" && ui.renaming) { stopRename(ui.renaming); return; }
  if (e.key === "Escape" && (ui.armedEnd.size || ui.armedStop.size)) {
    ui.armedEnd.clear(); ui.armedStop.clear(); render(); return;
  }
  if (e.key === "Escape" && ui.open != null && ui.held[ui.open]) { delete ui.held[ui.open]; render(); return; }
  if (e.key === "Escape" && ui.menu) { ui.menu = null; render(); return; }
  if (e.key === "Escape" && ui.lopen.size) { ui.lopen.clear(); render(); return; }
  if (e.key !== "Escape" || ui.open == null || ui.leftover[ui.open] == null) return;
  delete ui.leftover[ui.open]; ui.focusSay = true; render();
});
/** The name he was typing is kept or let go, and the title line shows the session's name again. */
function stopRename(id) {
  ui.renaming = null; delete ui.drafts[`n:${id}`];
  render();
}
root.addEventListener("submit", (e) => {
  e.preventDefault();
  if (e.target.dataset.rename) {
    const named = find(e.target.dataset.s);
    if (named) { keepName(named.id, e.target.querySelector("#nm").value.trim()); stopRename(named.id); }
    return;
  }
  const s = find(e.target.dataset.s), text = e.target.querySelector("#say").value.trim();
  if (!s || ui.sending.has(s.id)) return;
  if (!text && !(s.state === "needs" && denyOf(s))) { e.target.querySelector("#say").focus(); return; }
  if (e.target.dataset.note) note(s, text); else send(s, text);
});
root.addEventListener("input", (e) => {
  if (e.target.id === "say") ui.drafts[e.target.closest("form").dataset.s] = e.target.value;
  else if (e.target.id === "nm") ui.drafts[`n:${e.target.closest("form").dataset.s}`] = e.target.value;
});

let pastAsk = 0;
/** A search opens the ended sessions it matched, since a match folded away reads as no match, and clearing it closes
 *  what it opened; an ended line he opened himself stays open. */
function openedBySearch(found) {
  if (found && !ui.ended) { ui.ended = true; ui.endedBySearch = true; }
}
document.getElementById("q").addEventListener("input", (e) => {
  ui.q = e.target.value.trim().toLowerCase();
  // the search asks history too, a moment after the last key, so an ended agent is found by its name or words
  clearTimeout(pastAsk);
  const q = ui.q;
  if (q) pastAsk = setTimeout(() => readPast(q).then((rows) => {
    if (q !== ui.q) return;
    openedBySearch(rows.length > 0);
    render();
  }), 300);
  if (ui.q) openedBySearch(state.rows.some((s) => s.state === "ended" && matches(s)));
  else if (ui.endedBySearch) { ui.ended = false; ui.endedBySearch = false; }
  render({ slide: true });
});
document.addEventListener("click", (e) => {
  // a press anywhere but the menu or its button closes Continue on's menu, as a console menu does
  if (ui.menu && !e.target.closest(".menu.models, [data-act=menu]")) { ui.menu = null; render(); }
  if (ui.pmenu && !e.target.closest(".pmenu, [data-act=projmenu]")) { ui.pmenu = false; render(); }
  const el = e.target.closest("[data-act], [data-open]");
  if (!el || !root.contains(el) && !el.closest(".head")) return;
  if (!el.dataset.act) { // a row or a name: open it in place, or fold it away; a teammate's name opens its own session
    if (el.matches("a[data-open]")) { e.preventDefault(); open(el.dataset.open); return; }
    if (!el.matches("button.name") && e.target.closest("button, a, input, textarea, select, label")) return;
    if (ui.open === el.dataset.open) close(); else open(el.dataset.open);
    return;
  }
  const s = el.dataset.s && find(el.dataset.s);
  switch (el.dataset.act) {
    case "theme": toggleTheme(); break;
    case "ended": ui.ended = !ui.ended; ui.endedBySearch = false; if (ui.ended) for (const r of state.rows) if (r.state === "ended") ui.landing.add(r.id); render(); break;
    case "projmenu": ui.pmenu = !ui.pmenu; render(); break;
    // a pick is the same pick on Teams and Requests; the open session goes if it is not in the project he picked
    case "pickproj": { ui.project = el.dataset.p || null; ui.pmenu = false; PICK.set(ui.project);
      if (ui.open && !inPick(find(ui.open) ?? {})) ui.open = null;
      render({ slide: true }); break; }
    // a group he folded, and a project's Ended line he opened, stay that way across every reload of this tab
    case "by": { const to = el.dataset.by || el.dataset.v; ui.by = BY.includes(to) ? to : "project"; sessionStorage.setItem("splice.by", ui.by); for (const r of state.rows) ui.landing.add(r.id); render(); break; }
    case "fold": { const p = el.dataset.p; ui.shut.has(p) ? ui.shut.delete(p) : ui.shut.add(p); remember("splice.shut", ui.shut); opening(p); render(); break; }
    case "rest": { const p = el.dataset.p; ui.rest.has(p) ? ui.rest.delete(p) : ui.rest.add(p); remember("splice.rest", ui.rest); opening(p); render(); break; }
    case "idle": { const p = el.dataset.p; ui.idle.has(p) ? ui.idle.delete(p) : ui.idle.add(p); remember("splice.idle", ui.idle); opening(p); render(); break; }
    // one more page of what a fold holds, so a fold never opens 608 rows in one click
    case "more": { const p = el.dataset.p; ui.shown.set(p, shownOf(p, p.startsWith("l:") ? HANDFUL : PAGE_ROWS) + PAGE_ROWS); opening(p.replace(/^[ril]:/, "")); render(); break; }
    case "full": { const k = el.dataset.k; ui.full.has(k) ? ui.full.delete(k) : ui.full.add(k); render(); break; }
    case "logins": if (s) { ui.lopen.has(s.id) ? ui.lopen.delete(s.id) : ui.lopen.add(s.id); for (const l of ui.logins[s.head] || []) ui.grow.add(`l:${s.id}:${l.label}`); render(); } break;
    case "headless": ui.headless = !ui.headless; render(); break;
    case "retry": reload(); break;
    case "findmissing": { const q = document.getElementById("q"); q.value = el.dataset.p; q.dispatchEvent(new Event("input")); q.focus(); break; }
    case "clear": { const q = document.getElementById("q"); q.value = ""; q.dispatchEvent(new Event("input")); q.focus(); break; }
    case "close": close(); break;
    case "loose": { const p = el.dataset.p; ui.looseOpen.has(p) ? ui.looseOpen.delete(p) : ui.looseOpen.add(p); remember("splice.loose", ui.looseOpen); opening(p); render({ settle: true }); break; }
    // from a row's Name it the session opens in place first, since the name's field is its title line
    case "rename": if (s) { if (ui.open !== s.id) open(s.id); ui.renaming = s.id; ui.focusName = true; render(); } break;
    case "rename-no": if (s) stopRename(s.id); break;
    case "stop": if (s) { ui.armedStop.add(s.id); render(); } break;
    case "stop-now": if (s) { ui.armedStop.delete(s.id); stop(s); } break;
    case "stop-keep": if (s) { ui.armedStop.delete(s.id); render(); } break;
    case "end": if (s) endSession(s, false); break;
    case "end-now": if (s) endSession(s, true); break;
    case "end-keep": if (s) { ui.armedEnd.delete(s.id); render(); } break;
    case "menu": if (s) openChoices(s); break;
    case "pin-login": if (s && !ui.pinning) pinLogin(s, el.dataset.label); break;
    case "unpin-login": if (s && !ui.pinning) pinLogin(s, null); break;
    case "continue": if (s && !ui.moving.has(s.id)) continueOn(s, el.dataset.head, el.dataset.model, el.dataset.label); break;
    case "copy-r": if (s) copy("copy-r", s, resumeLine(s)); break;
    case "copy-send": if (s && sendTo(s)) copy("copy-send", s, sendLine(s)); break;
    case "copy-path": if (s) copy("copy-path", s, state.logs[s.id]?.path); break;
    case "copy-resume": if (s) copy("copy-resume", s, ui.failed.get(s.id)?.resume); break;
    case "lines": if (s) { ui.lines.has(s.id) ? ui.lines.delete(s.id) : ui.lines.add(s.id); render(); } break;
    case "namesakes": if (s) { ui.namesakes.has(s.id) ? ui.namesakes.delete(s.id) : ui.namesakes.add(s.id); render(); } break;
    case "models": { const k = el.dataset.k; ui.allModels.has(k) ? ui.allModels.delete(k) : ui.allModels.add(k); render(); } break;
    case "clear-send": if (s && ui.drafts[s.id]?.trim()) send(s, ui.drafts[s.id].trim(), true); break;
    case "keep": if (s) { delete ui.leftover[s.id]; ui.focusSay = true; render(); } break;
    case "held-cancel": if (s) { delete ui.held[s.id]; render(); } break;
    // the button says Copy, so nothing is cleared until his words are on the clipboard: a refused write moves nothing
    case "held-copy": if (s) heldCopy(s, el); break;
    case "answer": if (s && !ui.sending.has(s.id)) answer(s, Number(el.dataset.i)); break;
    case "result": { const k = el.dataset.k; ui.results.has(k) ? ui.results.delete(k) : ui.results.add(k); render(); break; }
    case "sys": { const k = el.dataset.k; ui.sys.has(k) ? ui.sys.delete(k) : ui.sys.add(k); render(); break; }
    case "top": foldTop(); break;
    // a run's count line: its hidden calls open in place, under his pointer, and fold away again
    case "trail": {
      const ks = (el.dataset.ks || "").split(" ").filter(Boolean);
      if (el.getAttribute("aria-expanded") === "true") for (const k of ks) { ui.trails.delete(k); ui.results.delete(k); }
      else for (const k of ks) { ui.trails.add(k); ui.landing.add(k); }
      render(); break;
    }
    case "logopen": if (s) { ui.logOpen.has(s.id) ? ui.logOpen.delete(s.id) : ui.logOpen.add(s.id); render(); } break;
    case "earlier": if (s && state.logs[s.id]?.earlier) { ui.logOpen.add(s.id); readLog(s, state.logs[s.id].earlier); } break;
    // back to the newest from a moment a door opened: the moment leaves the address with it
    case "newest": if (s) {
      const q = new URLSearchParams(location.search); q.delete("at");
      history.replaceState(history.state, "", `${location.pathname}${q.toString() ? `?${q}` : ""}${location.hash}`);
      readLog(s, "end");
    } break;
  }
});
// one width's columns and tiles are not another's: a window that changes width draws the list again
let resizing = 0;
addEventListener("resize", () => { cancelAnimationFrame(resizing); resizing = requestAnimationFrame(() => render()); });

/** Every read, then one draw. The open conversation is read again only while its session can still add to it: what
 *  is newer than its last message once it has one, which keeps the pages read back, and never while it shows a
 *  moment from a request's door. */
async function reload() {
  await read();
  if (ui.open && !find(ui.open)) ui.open = null;
  render({ slide: true });
  const s = ui.open && find(ui.open), held = s && state.logs[s.id];
  if (s && s.state !== "ended" && held !== null) {
    if (held?.messages && !held.at) readNewer(s);
    else if (!held?.extended) readLog(s);
  }
  if (s && s.state !== "ended" && Date.now() - (usageAt.get(s.id) ?? 0) >= USAGE_EVERY_MS) readUsage(s);
}

// the door's session is taken before the first draw, which writes the address from what is open (nothing yet)
const door = decodeURIComponent(location.hash.slice(1));
// ?at=<epoch ms> on the door: the moment a request ran, so its agent opens at that place in the conversation
const moment = Number(new URLSearchParams(location.search).get("at")) || null;
const byDoor = (id) => find(id) ?? state.rows.find((s) => id.length >= 8 && s.id.startsWith(id));
render();
read().then(async () => {
  // a door to an agent the list does not hold looks in history first: most of a week's agents have ended
  if (door && !byDoor(door)) await readPast(door);
  const s = door ? byDoor(door) : null;
  ui.missing = door && !s ? door : null; // one splice no longer lists says so at the list's top, never an empty page
  if (s && moment) { state.logs[s.id] = null; readLog(s, `at:${moment}`); }
  if (s) open(s.id); else render();
  readPast().then(() => render()); // the week's ended agents join the Ended line
});
// what a session is doing changes on its own clock: the list is read again while the page is open
// a locked console (api.js set body.locked on a 401) sends nothing until it is unlocked (gap 386b)
setInterval(() => { if (!document.hidden && !document.body.classList.contains("locked")) alone("reload", reload); }, 5000);
// the silence counter and the resume countdown move each second between reads, from when the turn was read
setInterval(() => {
  for (const el of root.querySelectorAll("[data-silent], [data-resume]")) {
    const st = find(el.dataset.silent || el.dataset.resume)?.stall;
    if (!st) continue;
    const gone = Date.now() - st.at, ms = el.dataset.silent ? st.ms + gone : Math.max(0, st.resumeMs - gone);
    el.lastElementChild.textContent = el.dataset.silent ? counter(ms) : `Resumes in ${counter(ms)}`;
    el.querySelector(".hand")?.style.setProperty("transform", `rotate(${Math.floor(ms / 1000) * 6}deg)`);
  }
  // a working turn's time since the gate admitted it moves the same way (kit workingLook)
  for (const el of root.querySelectorAll("[data-held]")) {
    const f = find(el.dataset.held), t = f?.turn?.phase != null ? f.turn : f?.wait;
    if (t?.age_ms != null) el.textContent = heldFor(t);
  }
  // how long each row has been in its state: the figure and its bar on the page's one scale, between reads
  for (const el of root.querySelectorAll("[data-span]")) {
    const f = find(el.dataset.span);
    if (!f) continue;
    const ms = spanOf(f), word = spanWord(ms), fig = el.matches("b") ? el : el.querySelector("b.v");
    if (fig && fig.textContent !== word) fig.textContent = word;
    const bar = el.querySelector(".vbar > b");
    if (bar) bar.style.width = `${Math.round(AXIS.at(ms) * 10) / 10}%`;
  }
}, 1000);

});
