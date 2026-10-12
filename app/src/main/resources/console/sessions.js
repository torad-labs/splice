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
//   POST /api/sessions/{id}/say · answer · stop   the Message field, an answer's button, and Stop, in the pane splice
//                                           opened for it (SessionDrive.kt); a session it did not open draws none of them
//
// WHERE A SESSION MOVED is drawn from the transcript page's `moves` (ModelMoves): "Continued on [to] model · from
// [from] model", at the moment of the move, with its time and, when moved rows lost it, "Thinking not carried over"
// (fin, hitstop f38853e). A move kept from before Continue on named its start carries no from, and draws only where it went.
"use strict";

// `screens`: what a session's own prompt shows, by session id, read only for sessions splice opened (GET .../screen
// answers 200 for those and refuses the rest). A session with a screen is one splice can drive: it gets the Message
// field and its answers are buttons. One it can't keeps what its transcript says and draws no act it couldn't carry.
const state = { rows: [], heads: [], retired: [], providerOf: {}, modelLabel: {}, live: {}, logs: {}, screens: {}, usage: {}, error: null, loading: true };
const ui = { open: null, missing: null, auto: false, q: "", ended: false, results: new Set(), failed: new Map(), stopping: new Set(), drafts: {}, sending: new Set(), hold: false, order: [], looks: new Map(), answered: new Map(), leftover: {}, menu: null, choices: {}, moving: new Map(), held: {},
  // which groups he has folded, and whose Ended line he has opened: his, across a reload, until he closes the tab
  shut: new Set(JSON.parse(sessionStorage.getItem("splice.shut") || "[]")), rest: new Set(JSON.parse(sessionStorage.getItem("splice.rest") || "[]")), grouped: false, gorder: null, project: PICK.get(), pmenu: false };
const remember = (key, set) => sessionStorage.setItem(key, JSON.stringify([...set]));
const root = document.getElementById("sessions");
const wide = sideBySide;

/** The daemon's own sentence for a refused call, or a plain one when it sent none. */
function refusalOf(answer, fallback) {
  if (answer.status === 0) return "splice did not answer. It may not be running.";
  return answer.body?.error || `${fallback} (${answer.status}).`;
}

// ---------- reading ----------
async function read() {
  tick();
  const [sessions, heads, models, retired] = await Promise.all([API.get("/api/sessions"), API.get("/api/heads"), API.get("/api/models"),
    API.get("/api/heads/retired")]);
  state.reading = sessions.body?.reading !== false; // off: no question text, no choices, answered in the terminal
  state.error = sessions.ok ? null : refusalOf(sessions, "The sessions could not be read");
  state.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key, reanchorMs: h.gate?.stall_reanchor_ms ?? null, authKind: h.authKind ?? null, cannotStart: h.cannotStart ?? null }));
  // kept apart from the live commands: nothing runs on a removed one, so Stop, a move and the live reads never ask it
  state.retired = retiredHeads(retired, state.heads);
  for (const row of models.body?.heads || []) {
    state.providerOf[row.head] = PROVIDER_FAMILY[row.provider] ?? row.provider;
    learnModels(state.modelLabel, row);
  }
  // the turns running now, by session: what Stop ends, and the model a session is on while one runs
  const lives = await Promise.all(state.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h.key)}/turns/live`)));
  state.live = {};
  state.heads.forEach((h, i) => {
    for (const t of lives[i].body?.turns || []) if (t.session && !t.stopped) state.live[t.session] = { head: h.key, ...t };
  });
  state.rows = (sessions.body?.sessions || []).filter((s) => s.session_id).map(sessionOf);
  // an answer that landed shows as going until the session stops waiting, or for half a minute at most
  for (const [id, going] of ui.answered) if (find(id)?.state !== "needs" || Date.now() - going.at > ANSWER_HOLD_MS) ui.answered.delete(id);
  state.loading = false;
  await readScreens();
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

// a session splice cannot place reads "unknown head" (SessionsRoutes UNKNOWN_HEAD) and the card says so, as the spec's
// Sep 13 Resolved block has it (§4 reading, Oct 11); a head key no configured command has names nothing
// it reads as a quiet fact in the chip's slot, never as a chip: a chip claims a command (hitstop)
const UNPLACED = "unknown head";
// a session that never went through splice has no command to name: its row's route says direct, and the card says
// "direct", the spec's word for it (Reading, Oct 10), never "unknown head" (splice-lead, Oct 11: the telegram bridge)
const DIRECT = "direct";
const QUIET = new Set([UNPLACED, DIRECT]);
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
  const { state: st, stall } = rowLook(row, turn, state.heads.find((h) => h.key === turn?.head)?.reanchorMs);
  return {
    id: row.session_id, row, name: row.name || null, first: row.title || null, repo: folderOf(row), wt: row.repo?.worktree ?? null,
    project: row.project ?? null, // as the daemon resolved it (ProjectWire); absent is "No project", never a guess here
    head: row.head, cmd: cmdOf(row), provider: state.providerOf[row.head] ?? null,
    model: modelName(state.modelLabel, turn?.model ?? row.model ?? row.served_model), // a running turn's model is the one in use now; reading off: what splice served it
    team: row.team_name ?? null, state: st, live: st === "working" && Boolean(turn), turn, stall: fixOf(stall, row.head),
    ask: st === "needs" ? { kind: asked ? "input" : "dialog", asked, call: row.last } : null,
    at: new Date(rowAt(row)),
  };
}

const NOT_READ = "Not read";
/** The conversation, from its newest messages; Show earlier reads the page before. */
async function readLog(s, before = "end") {
  const res = await API.get(`/api/sessions/${encodeURIComponent(s.id)}/transcript?before=${encodeURIComponent(before)}&limit=200`);
  const held = state.logs[s.id];
  if (!res.ok) {
    // gone reads No transcript and a failed read Not read; the daemon's own words go to the console, never the page (fin)
    if (res.status !== 404) console.warn(refusalOf(res, "The conversation could not be read"));
    state.logs[s.id] = held && before !== "end" ? { ...held, error: NOT_READ }
      : { messages: null, error: res.status === 404 ? null : NOT_READ };
  } else if (res.body?.state === "off") {
    state.logs[s.id] = { messages: null, off: true }; // reading off: splice does not read his conversations
  } else {
    const page = res.body;
    const messages = before === "end" || !held?.messages ? page.messages : [...page.messages, ...held.messages];
    state.logs[s.id] = { messages, earlier: page.earlier ?? null, extended: before !== "end", moves: page.moves ?? held?.moves ?? [], unmoved: page.unmoved ?? held?.unmoved ?? [],
      unreadable: (before === "end" ? 0 : held?.unreadable ?? 0) + (page.unparseable_lines ?? 0) };
  }
  render({ stick: before === "end" });
}

// ---------- what the page says about a session (look() and the lamp are kit.js's) ----------
// THE FOLDER NEVER TITLES A CARD INSIDE A GROUP (Marlin, Oct 11: he knows a session by what he asked it, and the
// header already says where it is). Named, it is its name; unnamed, the first line of its first message as the
// daemon read it (SessionsRoutes `title`), then its worktree, then its tag. Flat, the folder still titles it, which
// is the page as it was before projects. Reading off leaves both name and title null, and a group falls to the
// worktree or the tag rather than to a folder the header would repeat.
const title = (s) => (ui.grouped ? s.name || s.first || s.wt || `Session ${s.id.slice(0, 8)}`
  : s.name || s.repo || `Session ${s.id.slice(0, 8)}`);
const color = (s) => colorOf(s.provider);

const FOLDER = G('<path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>');
/** Where a session is, said once and in the words the page already uses for it (Marlin, Oct 12):
 *  - the open pane names its project, the same name its group wears, never the folder ("repo" for most of his);
 *  - a session in no project names its folder from home with the folder glyph, on its card too, because there the
 *    folder is the only clue; `place` is the daemon's spelling of it ("~/notes"), and until a row carries one the
 *    folder's own name stands in, which is what the card said before projects;
 *  - a card in a group says nothing more, since the header already says it; flat, a named card keeps its folder. */
function whereHtml(s, inPane) {
  if (s.project) return inPane ? `<span>${esc(projName(s))}</span>` : !ui.grouped && s.name && s.repo ? `<span>${esc(s.repo)}</span>` : "";
  const place = s.row.place || s.repo;
  if (!place) return "";
  return inPane || ui.grouped ? `<span class="where">${FOLDER}${esc(place)}</span>` : s.name ? `<span>${esc(place)}</span>` : "";
}
function metaHtml(s, L, inPane) {
  const chip = inPane && QUIET.has(s.cmd) ? `<span class="state mute">${s.cmd}</span>` : inPane && s.cmd ? `<span class="chip" style="--c:${color(s)}">${s.live ? "<i></i>" : ""}${esc(s.cmd)}</span>` : "";
  const team = s.team ? `<span class="team">${ICON.team}${esc(s.team)}</span>` : "";
  const model = s.model && !ui.moving.has(s.id) ? `<span>${esc(s.model)}</span>` : ""; // a move in flight names its target model in the word (hitstop)
  const repo = whereHtml(s, inPane);
  const past = s.at.toDateString() !== NOW.toDateString();
  const when = `<span class="when">${past ? `${s.at.toLocaleDateString("en-US", { weekday: "short" })} ` : ""}${clock(s.at)}</span>`;
  const parts = `${chip}<span class="word ${L.cls}">${L.word}</span>${L.detail || ""}${inPane ? L.paneDetail || "" : ""}${team}${model}${repo}`;
  // on a list card everything but the time sits in one row that drops whole parts from its end when it runs out of
  // room, the folder first, then the model, then the team; the open pane has the width and shows every part (hitstop b6bae4e)
  return inPane ? `<div class="meta">${parts}</div>` : `<div class="meta"><span class="parts">${parts}</span>${when}</div>`;
}

// What a waiting session asks, and answering it where it waits. A question's words and options come off its transcript
// (`last.asks`); a permission's choices belong to the client's version, so they are read off its screen or not drawn.
// Each answer is the digit the person would press (POST .../answer). A session splice did not open can't be answered
// from here, and the card says where it is answered instead of offering an act it can't carry.
/** What a permission will run, off its screen: the tool, the command its client framed, whole, and the line above it that
 *  says why (hitstop 9e95ae4), so nothing is approved blind. Empty when the screen framed nothing. */
function runHtml(call, offer) {
  const panel = offer?.panel || [], at = panel.findIndex((l) => l.framed);
  if (at < 0) return "";
  const run = panel.filter((l) => l.framed).map((l) => l.text).join("\n"), why = at > 0 ? panel[at - 1].text : "";
  return `<p class="cmd"><span class="tname">${esc(call?.tool || panel[0].text)}</span>${esc(run)}</p>${why ? `<p class="why">${esc(why)}</p>` : ""}`;
}
const READING_OFF_ASK = "Answer it in the terminal it runs in";
function askHtml(s, where = "card") {
  // from his press until the session moves on, the answer he pressed keeps its words with the wait ring and the rest
  // wait disabled, drawn from the choices he saw: never "answer it in the terminal" meanwhile (Marlin p165, hitstop 8a04273)
  const going = ui.answered.get(s.id);
  // reading off: he told splice not to read his conversations, and a question and its choices are the conversation, so
  // the card says only that it waits and where to answer it (Marlin, 5:12 AM CT)
  // the line ends on a quiet door to the switch: the cause, and the way back (fin)
  // one door per pane: an open pane's log carries it, so its foot names only where to answer (fin)
  if (!state.reading) return `<div class="ask"><p class="why">${READING_OFF_ASK}${where === "pane" ? "" : ` · <a class="door" href="settings.html#data">Reading off${ICON.door}</a>`}</p></div>`;
  const a = s.ask, offer = going?.offer ?? state.screens[s.id], can = going ? true : drivable(s);
  const busy = going || ui.sending.has(s.id) ? " disabled" : "";
  const pick = (choice, label, i) => going?.choice === choice
    ? `<button class="act answering" disabled>${ICON.wait}${esc(label)}</button>`
    : `<button class="act${i ? "" : " primary"}" data-act="answer" data-s="${esc(s.id)}" data-i="${choice}"${busy}>${esc(label)}</button>`;
  if (a.asked) {
    const opts = a.asked.options || [];
    const answers = !opts.length ? "" : can && !a.asked.multi ? `<div class="answers">${opts.map((o, i) => pick(i + 1, o, i)).join("")}</div>`
      : `<div class="answers">${opts.map((o) => `<span class="scope">${esc(o)}</span>`).join("")}</div>`;
    return `<div class="ask"><p class="q">${esc(a.asked.question)}</p>${answers}${can ? "" : `<p class="why">Answer it in the terminal it runs in</p>`}</div>`;
  }
  // what it wants to do, off its transcript, then the question its screen puts
  const call = a.call?.tool ? `<p class="cmd"><span class="tname">${esc(a.call.tool)}</span>${esc(a.call.text || "")}</p>` : "";
  const q = offer?.asked ? `<p class="q">${esc(offer.asked)}</p>` : "";
  if (!offer?.choices?.length) return `<div class="ask">${call}<p class="why">Answer it in the terminal it runs in</p></div>`;
  // in the open session the refusal is the Message field's Deny, which also carries what to do instead
  const deny = where === "pane" && composerHtml(s) ? denyOf(s) : null;
  const choices = offer.choices.filter((c) => c !== deny);
  // the whole prompt its screen drew, when it drew one: the tool, what it runs, and why, so nothing is approved blind
  const said = runHtml(a.call, offer) || call + q;
  return `<div class="ask">${said}<div class="answers">${choices.map((c, i) => pick(c.choice, c.label, i)).join("")}</div></div>`;
}

const lastOf = (s) => s.row.last;
// From the press until splice answers, Stop keeps its place, disabled, reading Stopping with the wait ring (hitstop 9e95ae4)
const stopHtml = (s, cls, icon) => ui.stopping.has(s.id)
  ? `<button class="${cls} stopping" disabled>${ICON.wait}Stopping</button>`
  : canStop(s) ? `<button class="${cls}" data-act="stop" data-s="${esc(s.id)}">${icon}Stop</button>` : "";
const canStop = (s) => s.state === "working" && (s.live || drivable(s));
function cardHtml(s) {
  const L = movingLook(s) || look(s), last = lastOf(s);
  const stop = stopHtml(s, "act quiet small", "");
  const wt = s.wt ? `<span class="wt">${ICON.branch}${esc(s.wt)}</span>` : "";
  let body = "";
  if (s.state === "needs") body = askHtml(s);
  else if (last?.text) body = last.tool ? `<p class="last tool"><b>${esc(last.tool)}</b>${esc(last.text)}</p>` : `<p class="last">${esc(last.text.replace(/`/g, ""))}</p>`;
  const failed = ui.failed.get(s.id);
  // a card whose state changed since it was last drawn rings once where it stands
  const was = ui.looks.get(s.id); ui.looks.set(s.id, L.cls);
  return `<article class="card s ${L.cls}${was && was !== L.cls ? " changed" : ""}" style="--c:${color(s)}" data-key="s:${esc(s.id)}" data-open="${esc(s.id)}" aria-current="${ui.open === s.id}">`
    + `<span class="lamp ${L.cls}" aria-hidden="true">${L.lamp}</span>`
    + `<div class="top"><button class="name" data-open="${esc(s.id)}">${esc(title(s))}</button>${wt}${QUIET.has(s.cmd) ? `<span class="state mute">${s.cmd}</span>` : s.cmd ? `<span class="chip">${s.live ? "<i></i>" : ""}${esc(s.cmd)}</span>` : ""}${stop}</div>`
    + `${metaHtml(s, L, false)}${body}${failedHtml(s, failed, false)}</article>`;
}

// ---------- the list: what needs him first, then what runs, then what rests ----------
// a session a failed move left with nothing running waits on him as Needs you does: ranked, coloured and listed with it (Marlin, 6:08 AM)
const stranded = (s) => s.state === "ended" && ui.failed.get(s.id)?.unmoved === true && !ui.failed.get(s.id).back;
// a move that failed here is this page's own fact, so it lifts the card only here; the rest is the kit's rank
const RANK = (s) => (stranded(s) ? 0 : rankOf(s));
// a project's name matches every session in it, so typing "temple" keeps that whole group rather than the one card
// whose folder happens to be spelled that way (Marlin)
const matches = (s) => !ui.q || [s.name, s.first, s.repo, s.cmd, s.row.repo?.root, s.row.cwd, s.project?.label, s.project?.name]
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
// ---------- by project (Marcos, Oct 11: "If we don't separate sessions per project, it'll be really hard to
// navigate"): the grouping serves finding "the temple-code one", and must never cost seeing who is stuck on him ----
// The project is the daemon's answer on the row (ProjectWire), the same one Teams and Requests read; this page never
// resolves a path itself. A session in no project is "No project", which is a group like any other but always last.
const NO_PROJECT = "-";
const projKey = (s) => s.project?.id ?? NO_PROJECT;
const projName = (s) => s.project?.label || s.project?.name || "No project";
/** Two groups is what makes a list a grouped list: a first-time user with one project keeps the flat page. Read off
 *  every live session and never off the search, so a search that happens to land inside one project keeps the
 *  headers it had: it hides the groups it does not match, it does not undo the grouping (Marlin). */
const grouped = () => new Set(state.rows.filter((s) => s.state !== "ended" || stranded(s)).map(projKey)).size >= 2;
// THE PICK (kit PICK): one project, or every project. It only exists where there are two groups to pick between, so a
// first-time user with one project never meets it, and a pick left over from another page's desk filters nothing here
// until there are two. A picked project that has gone keeps its pick: the list says "No sessions" and the doors stay.
const picked = () => (grouped() ? ui.project : null);
const inPick = (s) => picked() == null || projKey(s) === picked();
/** By each group's most urgent card in the cards' own rank, then its last activity, ties by name, No project last.
 *  While his pointer is in the list the groups hold the order they were last drawn in, as the cards do. */
function groupsOf(list) {
  const m = new Map();
  for (const s of list) { const k = projKey(s); if (!m.has(k)) m.set(k, []); m.get(k).push(s); }
  const rank = (k) => Math.min(...m.get(k).map(RANK));
  const at = (k) => Math.max(...m.get(k).map((s) => +s.at));
  const name = (k) => projName(m.get(k)[0]);
  const keys = [...m.keys()].sort((a, b) => (a === NO_PROJECT) - (b === NO_PROJECT)
    || rank(a) - rank(b) || at(b) - at(a) || name(a).localeCompare(name(b)));
  if (ui.hold && ui.gorder) { const held = (k) => { const i = ui.gorder.indexOf(k); return i < 0 ? Infinity : i; }; keys.sort((a, b) => held(a) - held(b)); }
  return keys.map((k) => [k, m.get(k)]);
}
// fin's header: the name, then one lamp and its count for each live state present, in the cards' rank, then the
// muted total. No state words: the lamps are the words, and Idle and Ended are in the total.
const STATES = [["needs", 0], ["stalled", 1], ["working", 2]];
const BIG = { needs: ICON.input, stalled: ICON.flat, working: ICON.trace };
const countsOf = (g) => STATES.map(([k, r]) => [k, g.filter((s) => RANK(s) === r).length]).filter(([, n]) => n);
const bigCount = ([k, n]) => `<span class="pc big ${k}"><span class="lamp ${k}" aria-hidden="true">${BIG[k]}</span><b>${n}</b></span>`;
/** A project's ended cards under one line at its end, closed until he opens it; a search opens every one, because no
 *  fold may hide a match. Only Ended folds: Idle stays up, and nothing here knows which replies he has read. */
function restHtml(key, ended) {
  if (!ended.length) return "";
  const open = ui.rest.has(key) || Boolean(ui.q);
  return `<button class="act quiet fold prest" data-act="rest" data-p="${esc(key)}" aria-expanded="${open}">${ended.length} Ended${ICON.caret}</button>`
    + (open ? `<div class="rested">${ended.map(cardHtml).join("")}</div>` : "");
}
/** COLLAPSED, A GROUP STILL SHOWS ITS NEEDS-YOU CARDS (Marlin): nothing waiting on him is ever hidden by a fold. */
function groupHtml([key, g], ended) {
  const shut = ui.shut.has(key) && !ui.q, counts = countsOf(g), total = g.length + ended.length;
  const shown = shut ? g.filter((s) => RANK(s) === 0) : g;
  const go = key === NO_PROJECT ? ""
    : `<a class="pgo" href="requests.html?project=${encodeURIComponent(key)}">${ICON.door}<span>Open on Requests</span></a>`;
  return `<section class="pgroup${shut ? " shut" : ""}" data-key="g:${esc(key)}" aria-label="${esc(`${projName(g[0])}, ${countWords(counts, total, "session")}`)}">`
    + `<header class="phead"><button class="pfold" data-act="fold" data-p="${esc(key)}" aria-expanded="${!shut}">${ICON.caret}<span class="pname">${esc(projName(g[0]))}</span></button>`
    + `<span class="pcounts">${counts.map(bigCount).join("")}<span class="ptotal">${total}</span></span>${go}</header>`
    + `${shown.map(cardHtml).join("")}${shut ? "" : restHtml(key, ended)}</section>`;
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
  return liveInIt.length + state.rows.filter((s) => s.state === "ended" && !stranded(s) && projKey(s) === key).length;
}
function listHead() {
  const live = state.rows.filter((s) => s.state !== "ended" || stranded(s));
  if (!live.length) return "";
  // one project and nothing outside it: no picker and no headers, its name where the picker would be (Marlin)
  if (!grouped()) return live[0].project ? `<div class="lhead"><span class="pname solo">${esc(projName(live[0]))}</span></div>` : "";
  if (ui.wide) return ""; // the tiles across the page are the picker and the doors
  const { projects, doors } = pickOptions();
  return `<div class="lhead">${pickerHtml(projects, picked(), ui.pmenu, doors, state.rows.length)}</div>`;
}
/** The tiles, in the groups' order after Every project. A tile's total counts its sessions with the ended ones, which
 *  is what its header's total says; its lamps count the live states, as the header's do. */
function sessionTiles() {
  const live = state.rows.filter((s) => s.state !== "ended" || stranded(s));
  const items = groupsOf(live).map(([k, g]) => {
    const counts = countsOf(g), total = projTotal(k, g);
    return [k, projName(g[0]), counts, total, countWords(counts, total, "session")];
  });
  const every = sumCounts(items.map((x) => x[2])), all = state.rows.length;
  return tilesHtml([["", "Every project", every, all, countWords(every, all, "session")], ...items], picked());
}
function listHtml() {
  if (state.loading) return `<div class="list"><div class="nomatch"><span class="state">Reading</span></div></div>`;
  if (state.error) return `<div class="list"><div class="nomatch"><span class="state">${esc(state.error)}</span></div></div>`;
  const list = sorted(); ui.order = list.map((s) => s.id);
  const ended = state.rows.filter((s) => s.state === "ended" && !stranded(s) && matches(s) && inPick(s)).sort((a, b) => b.at - a.at);
  const head = listHead();
  if (!list.length && !ended.length) {
    // a picked project with nothing in it says so plainly, and its doors stay in the head (fin)
    return ui.q ? `<div class="list">${head}<div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clear">Clear</button></div></div>`
      : `<div class="list">${head}<div class="nomatch"><span class="state">No sessions</span></div></div>`;
  }
  // PICKED, THE LIST IS THAT PROJECT'S CARDS, WITH NO HEADER: the picker already names it. Its ended cards fold under
  // its own line, as they do in its group
  if (picked() != null) return `<div class="list">${head}${list.map(cardHtml).join("")}${restHtml(picked(), ended)}</div>`;
  let cards, loose = ended;
  if (ui.grouped) {
    const groups = groupsOf(list); ui.gorder = groups.map(([k]) => k);
    const keys = new Set(ui.gorder);
    cards = groups.map(([k, g]) => groupHtml([k, g], ended.filter((s) => projKey(s) === k))).join("");
    loose = ended.filter((s) => !keys.has(projKey(s))); // a project with nothing open today keeps its ended at the foot
  } else { ui.gorder = null; cards = list.map(cardHtml).join(""); }
  const fold = loose.length ? `<button class="act quiet fold" data-act="ended" aria-expanded="${ui.ended}">${loose.length} ended${ICON.caret}</button>` : "";
  const endedList = ui.ended && loose.length ? `<div class="ended">${loose.map(cardHtml).join("")}</div>` : "";
  return `<div class="list">${head}${cards}${fold}${endedList}</div>`;
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
/** A teammate's colour: the command its own session runs on, found by its name, else this session's. */
const senderColor = (name, s) => color(state.rows.find((x) => x.name && x.name === name) ?? s);
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
function logHtml(s) {
  const held = state.logs[s.id];
  if (!held) return `<div class="log"><p class="empty">Reading</p></div>`;
  if (held.off) return `<div class="log"><p class="empty"><a class="door" href="settings.html#data">Reading off${ICON.door}</a></p></div>`;
  if (!held.messages) return `<div class="log"><p class="empty">${esc(held.error ?? "No transcript")}</p></div>`;
  const items = [];
  if (held.earlier) items.push(`<button class="act quiet earlier" data-act="earlier" data-s="${esc(s.id)}">Show earlier</button>`);
  if (held.unreadable) items.push(`<p class="note">${held.unreadable} unreadable</p>`);
  if (held.error) items.push(`<p class="note">${esc(held.error)}</p>`);
  // A call and what came back are two rows on the wire, the call's (result: false, its input as text) and the
  // result's (role "tool"), joined by tool_use_id: drawn as one call that opens on its result, as the drawing has it.
  const results = new Map(held.messages.filter((m) => m.role === "tool" && m.tool_use_id).map((m) => [m.tool_use_id, m.text]));
  const calls = held.messages.filter((m) => m.tool && m.role !== "tool");
  const lastCall = calls.at(-1);
  // each move sits where it happened: before the first message written after it, oldest first. One older than every
  // message read so far waits for Show earlier, unless the conversation starts here.
  const first = held.messages.find((m) => m.ts)?.ts;
  const marks = (held.moves || []).filter((mv) => !held.earlier || first == null || mv.moved_at >= first)
    .sort((a, b) => a.moved_at - b.moved_at);
  // a move that did not come back draws no mark, but splice ended the client for it all the same (Marlin, #3)
  const tries = (held.unmoved || []).slice().sort((a, b) => a - b);
  let pressed = null, exit = EXIT_LOOKING;
  const markUpTo = (ts) => {
    while (marks.length && (ts == null || marks[0].moved_at <= ts)) { const mv = marks.shift(); pressed = mv.moved_at; exit = EXIT_LOOKING; items.push(jointHtml(mv)); }
    while (tries.length && ts != null && tries[0] <= ts) { pressed = tries.shift(); exit = EXIT_LOOKING; }
  };
  held.messages.forEach((m) => {
    if (m.ts) markUpTo(m.ts);
    const key = `${s.id}:${m.index}`;
    const at = m.ts ? `<time>${clock(new Date(m.ts))}</time>` : "";
    if (m.role === "tool") return; // drawn under its call
    const next = moveExit(m, pressed, exit);
    if (next !== exit) { exit = next; return; }
    if (m.tool) {
      // only the newest call of a running turn runs; a call with no result has nothing to open
      const result = results.get(m.tool_use_id) ?? null, open = ui.results.has(key), none = result == null;
      const running = none && s.live && m === lastCall && m === held.messages.at(-1);
      items.push(`<div class="call"><button class="tool" style="--c:${color(s)}" data-act="result" data-k="${esc(key)}" aria-expanded="${open}"${none ? " disabled" : ""}>`
        + `${running ? "<i></i>" : none ? "" : ICON.caret}<span class="tname">${esc(m.tool)}</span><span class="tsum">${esc(callSummary(m))}</span></button>`
        + `${open && !none ? (result.trim() ? `<pre class="result">${esc(result)}</pre>` : `<p class="result empty">No output</p>`) : ""}</div>`); // a call that printed nothing opens to say so (Marlin p164)
      return;
    }
    // the client's own mark where he refused a call or stopped a turn: a line in the conversation, never his words
    if (m.role === "system" && m.kind === "interrupted") { items.push(`<p class="cutoff">${ICON.stop}Stopped${at}</p>`); return; }
    if (!m.text) return;
    // a teammate's message reads as its sender's, beside the agent's side, with who sent it
    if (m.role === "peer") { items.push(`<div class="msg peer" style="--c:${senderColor(m.from, s)}"><p class="from"><i></i>${esc(m.from || "A teammate")}</p><div class="body">${md(m.text)}</div>${at}</div>`); return; }
    if (takenBack(s, m)) {
      const back = inPrompt(s) === (m.text || "").trim();
      items.push(`<div class="msg his takenback"><div class="body">${md(m.text)}</div><p class="taken">${ICON.stop}<span>${back ? "Stopped · Back in the prompt" : "Stopped"}</span>${at}</p></div>`);
      return;
    }
    items.push(`<div class="msg ${m.role === "user" ? "his" : "agent"}"><div class="body">${md(m.text)}</div>${at}</div>`);
  });
  markUpTo(null); // a move after the newest message, before anything was said on the new model
  return `<div class="log">${items.join("")}</div>`;
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

function paneHtml() {
  const s = state.rows.find((x) => x.id === ui.open);
  if (!s) return ui.missing ? missingHtml(ui.missing) : "";
  const L = movingLook(s) || look(s);
  const stop = stopHtml(s, "act", ICON.stop);
  const cont = movable(s) ? (ui.moving.has(s.id) ? `<button class="act stopping" disabled>${ICON.wait}Moving</button>`
    : `<button class="act" data-act="menu" data-s="${esc(s.id)}" aria-haspopup="menu" aria-expanded="${ui.menu === s.id}">Continue on</button>${ui.menu === s.id ? choicesHtml(s) : heldHtml(s)}`) : "";
  const close = `<button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button>`;
  const wt = s.wt ? `<span class="wt">${ICON.branch}${esc(s.wt)}</span>` : "";
  const ask = s.state === "needs" ? `<div class="askbox">${askHtml(s, "pane")}</div>` : "";
  const failed = ui.failed.get(s.id);
  return `<section class="pane" style="--c:${color(s)}" aria-label="${esc(title(s))}"><header><span class="lamp ${L.cls}" aria-hidden="true">${L.lamp}</span>`
    + `<div class="who"><div class="top"><span class="name">${esc(title(s))}</span>${wt}</div>${metaHtml(s, L, true)}${usageHtml(s)}</div>`
    + `<div class="acts">${stop}${cont}${close}</div></header>${failedHtml(s, failed, true)}${logHtml(s)}${ask}${composerHtml(s)}</section>`;
}

// A door to a session splice no longer lists, by link or address: its pane says No transcript where the log would be,
// and nothing else, since nothing about it can be read (Marlin, Oct 12; fin's word).
function missingHtml(id) {
  const close = `<button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button>`;
  return `<section class="pane missing" aria-label="Session"><header><div class="who"><div class="top"><span class="name">Session ${esc(String(id).slice(0, 8))}</span></div></div>`
    + `<div class="acts">${close}</div></header><div class="log"><p class="empty">No transcript</p></div></section>`;
}

/** The choice on a permission's screen that refuses it ("No, and tell Claude what to do differently"), or null. */
const denyOf = (s) => state.screens[s.id]?.choices?.find((c) => /^no\b/i.test(c.label)) ?? null;

// The Message field, only on a session splice can type into. While a permission waits, it is Deny: the refusing choice
// is pressed, then his words, if any, go in as what to do instead. While a question waits, its options answer it and
// no field is drawn, since his own words would land in the question's menu rather than as an answer.
function composerHtml(s) {
  if (!drivable(s)) return "";
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

// ---------- render ----------
function render({ stick = true, slide = false } = {}) {
  const before = slide ? new Map([...root.querySelectorAll(".list .s")].map((el) => [el.dataset.key, el.getBoundingClientRect().top])) : null;
  const oldLog = root.querySelector(".log"), keep = oldLog && !stick ? oldLog.scrollTop : null;
  // the five-second read redraws the page: the Message field keeps his focus and caret through it
  const say = document.activeElement?.id === "say" ? document.activeElement : null, caret = say ? [say.selectionStart, say.selectionEnd] : null;
  root.className = `sessions${ui.open || ui.missing ? " open" : ""}`;
  ui.grouped = grouped(); // before anything draws: the cards and the pane read it, and one session has one name on every page
  ui.wide = ui.grouped && !state.loading && tilesWide();
  const tiles = document.getElementById("ptiles");
  tiles.hidden = !ui.wide;
  tiles.innerHTML = ui.wide ? sessionTiles() : "";
  root.innerHTML = listHtml() + paneHtml();
  for (const p of root.querySelectorAll(".unmoved .screen")) p.scrollTop = p.scrollHeight; // the cause is in its last lines (hitstop)
  const field = root.querySelector("#say");
  if (field && (caret || ui.focusSay)) { field.focus(); if (caret) field.setSelectionRange(...caret); ui.focusSay = false; }
  // the conversation he opened is in the address, on the door Teams and Requests use, so Back returns to it
  const addr = ui.open && !ui.auto ? `#${ui.open}` : ui.missing ? `#${ui.missing}` : "";
  if (location.hash !== addr) history.replaceState(history.state, "", `${location.pathname}${location.search}${addr}`);
  const log = root.querySelector(".log");
  if (log) log.scrollTop = keep ?? log.scrollHeight;
  if (before) slideFrom(before);
}
/** The sliding move: each card starts where it stood and eases to its new place, on the shared list-move tokens
 *  (--t-move on --e-io, hitstop): a re-sort moves every card, and a list-sized move arrives on an in-out ease. */
const tok = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
function slideFrom(before) {
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  for (const el of root.querySelectorAll(".list .s")) {
    const dy = (before.get(el.dataset.key) ?? el.getBoundingClientRect().top) - el.getBoundingClientRect().top;
    if (Math.abs(dy) < 1) continue;
    el.animate([{ transform: `translateY(${dy}px)` }, { transform: "none" }], { duration: parseFloat(tok("--t-move")) || 420, easing: tok("--e-io") || "ease-in-out" });
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
  if (sorted().map((s) => s.id).join() !== held) render({ stick: false, slide: true });
}
root.addEventListener("pointerover", (e) => { pointerIn = Boolean(e.target.closest(".list")); holdList(); });
root.addEventListener("pointerleave", () => { pointerIn = false; holdList(); });
document.addEventListener("focusin", holdList);
document.addEventListener("focusout", () => setTimeout(holdList));
const find = (id) => state.rows.find((s) => s.id === id);

function open(id) {
  ui.open = id; ui.missing = null;
  render();
  const s = find(id);
  if (s && !state.logs[id]) readLog(s);
  if (s && s.state !== "ended" && !state.screens[id]) readScreen(s);
  if (s && !(id in state.usage)) readUsage(s);
}
/** What the session used today, on every command it ran on: the same per-session totals Requests sums
 *  (GET /api/perf/turns?session=), from local midnight. Null until read, and kept null when no command answered. */
async function readUsage(s) {
  const from = new Date(); from.setHours(0, 0, 0, 0);
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const heads = [...state.heads, ...state.retired]; // a removed command's requests today are still the session's
  const reads = await Promise.all(heads.map((h) => API.get(`/api/perf/turns?${new URLSearchParams({
    head: h.key, since: String(+from), time_zone: zone, n: "1", local: "0", session: s.id.slice(0, 8) })}`)));
  let seen = false; const used = { requests: 0, tin: 0, tout: 0, noIn: 0, noOut: 0, cost: 0, priced: 0, plan: 0, noPrice: 0, from: +from };
  reads.forEach((r, i) => {
    const t = (r.body?.heads || []).find((x) => x.key === heads[i].key)?.usage?.totals;
    if (!t) return;
    seen = true; used.requests += t.requests || 0; used.tin += t.input_tokens || 0; used.tout += t.output_tokens || 0;
    used.noIn += t.missing_input_requests || 0; used.noOut += t.missing_output_requests || 0;
    // a request that moved no tokens (refused, cut) is no money fact, and a local model's costs nothing: neither is counted
    const plan = t.unpriced_plan_requests || 0, local = t.unpriced_local_requests || 0;
    const moved = Math.max(0, (t.requests || 0) - (t.unanswered_requests || 0) - local);
    const unpriced = Math.max(0, (t.unpriced_requests || 0) - local), priced = Math.max(0, moved - unpriced);
    used.plan += plan; used.noPrice += unpriced - plan;
    if (t.cost_usd == null) used.noPrice += priced; else { used.cost += t.cost_usd; used.priced += priced; }
  });
  state.usage[s.id] = seen ? used : null;
  if (ui.open === s.id) render({ stick: false });
}
/** Today's use, one quiet line whose whole length opens its requests on Requests from midnight (hitstop 47fa7e9).
 *  A figure no request reported is left out, never summed to 0. Money is Marlin's mixed form (fin, Oct 10): "≈$1.20"
 *  alone when every request that moved tokens is priced, else the priced share tied to its count, "≈$1.20 for 8", then
 *  the rest by why they have no price, biggest first, each only when above 0. Under a cent reads to four places. */
function usageHtml(s) {
  const u = state.usage[s.id];
  if (!u?.requests) return "";
  const num = (v) => `<span class="num">${v}</span>`;
  const figs = [`${num(u.requests.toLocaleString("en-US"))} ${u.requests === 1 ? "request" : "requests"}`,
    u.noIn < u.requests ? `${num(kTok(u.tin))} tokens in` : "", u.noOut < u.requests ? `${num(kTok(u.tout))} ${u.noIn < u.requests ? "" : "tokens "}out` : "",
    ...moneyOf(u)].filter(Boolean);
  return `<a class="today" href="requests.html?${new URLSearchParams({ session: s.id, from: String(u.from) })}"><span class="words">Today · ${figs.join(" · ")}</span>${ICON.door}<span class="go">Open on Requests</span></a>`; // the arrow's words (fin; hitstop 22df5bf)
}

function moneyOf(u) {
  const num = (v) => `<span class="num">${v}</span>`;
  const rest = [[u.plan, "on your plan"], [u.noPrice, "with no price"]].filter(([n]) => n > 0).sort((a, b) => b[0] - a[0])
    .map(([n, w]) => `${num(n.toLocaleString("en-US"))} ${w}`);
  if (!u.priced) return rest;
  const usd = num(dollars(u.cost));
  return rest.length ? [`${usd} for ${num(u.priced.toLocaleString("en-US"))}`, ...rest] : [usd];
}

/** One session's screen, read the moment it opens, so its Message field is there without waiting for the next read. */
async function readScreen(s) {
  const res = await API.get(sessionPath(s, "screen"));
  if (res.ok && ui.open === s.id) { state.screens[s.id] = res.body; render({ stick: false }); }
}

// ---------- the acts ----------
const sessionPath = (s, act) => `/api/sessions/${encodeURIComponent(s.id)}/${act}`;

// Stop ends the running turn. In a pane splice opened it presses stop, as the person would (SessionDrive.kt), which
// leaves the session waiting for his next message; elsewhere it ends the turn at the head (LiveTurnsRoutes.kt). A 404
// from the head means the turn had already ended: the card just updates.
async function stop(s) {
  if (!s.turn && !drivable(s)) return;
  ui.stopping.add(s.id); ui.failed.delete(s.id); render({ stick: false });
  const res = drivable(s) ? await API.post(sessionPath(s, "stop"))
    : await API.post(`/api/heads/${encodeURIComponent(s.turn.head)}/turns/${encodeURIComponent(s.turn.id)}/stop`);
  ui.stopping.delete(s.id);
  const refused = stopRefusal(res);
  if (refused) ui.failed.set(s.id, refused);
  await reload();
}

// ---------- Continue on ----------
// The session goes on under another command's model: the same id and transcript, resumed there (POST .../continue,
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
function choicesHtml(s) {
  const groups = ui.choices[s.id];
  if (!groups) return "";
  const acctOf = (g) => (!g.acct ? "<span></span>" : g.keyVar !== undefined
    ? `<span class="acct">${esc(g.acct)}${g.keyVar ? ` · <span class="var">${esc(g.keyVar)}</span>` : ""}</span>` : acctPlanHtml(g.acct, g.plan));
  return `<div class="menu models" role="menu">${groups.map((g, gi) => g.names.map((n, i) => {
    const room = g.kind === "room" && g.used != null ? `<span class="room"><span class="bar sub"><b style="width:${g.used}%"></b></span><span class="pct">${g.used}%<span class="used">used</span></span></span>`
      : g.kind === "limit" ? `<span class="room out"><span class="pct limit">100%<span class="used">used</span></span>${g.until ? `${ICON.lock}Resets ${backWord(g.until)}` : ""}</span>`
      : g.kind === "unlinked" ? `<span class="room out">Not installed${g.fix ? ` · <code>${esc(g.fix)}</code>` : ""}</span>`
      : g.kind === "signedout" ? `<span class="room out">Signed out</span>` : `<span class="room"></span>`;
    const off = g.kind === "limit" || g.kind === "signedout" || g.kind === "unlinked";
    return `<button role="menuitem" class="line${gi && !i ? " plan" : ""}" style="--c:${colorOf(state.providerOf[g.head])}" data-act="continue" data-s="${esc(s.id)}" data-head="${esc(g.head)}" data-model="${esc(n.id)}" data-label="${esc(n.label)}"${off ? " disabled" : ""}>`
      + `<span class="blot"></span><span class="mname">${esc(n.label)}</span>${acctOf(g)}${room}</button>`;
  }).join("")).join("")}</div>`;
}
async function openChoices(s) {
  if (ui.menu === s.id) { ui.menu = null; render({ stick: false }); return; }
  delete ui.held[s.id]; // opening the models menu dismisses the held card (hitstop)
  ui.menu = s.id; ui.choices[s.id] = await readChoices(s);
  if (ui.menu === s.id) render({ stick: false });
}
/** Move it, then read the session again: it comes back Idle on the new command, and his next message runs there. */
async function continueOn(s, head, model, label, clear = false) {
  ui.menu = null; ui.moving.set(s.id, { head, label: label || head }); ui.failed.delete(s.id); render({ stick: false });
  const res = await API.post(sessionPath(s, "continue"), clear ? { head, model, clear: true } : { head, model });
  ui.moving.delete(s.id);
  // the prompt holds words he never sent: they are his, so the card shows them and nothing moves until he says
  if (!res.ok && res.body?.reason === "draft") {
    ui.held[s.id] = { words: String(res.body.draft ?? ""), head, model, label: label || head };
    render({ stick: false });
    return;
  }
  delete ui.held[s.id];
  if (res.ok) ui.focusSay = true;
  // a move that did not come back went on where it was (MoveArrival): its card says so, with Try again
  else if (res.body?.moved === false) ui.failed.set(s.id, { ...res.body, unmoved: true, to: head, model, label: label || head, was: s.head, wasModel: s.model });
  else ui.failed.set(s.id, refusalOf(res, "It could not be moved"));
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
  const m = ui.moving.get(s.id), f = ui.failed.get(s.id);
  if (m) return { cls: "moving", lamp: ICON.wait, word: `Continuing on ${cmdChip(m.head)} ${esc(m.label)}` };
  // when even the way back failed nothing runs on either command: not Idle (at its prompt), never Ended (fin, Marlin)
  if (stranded(s)) return { cls: "needs notrunning", lamp: "<i></i>", word: "Not running" };
  return null;
};
/** A move that did not happen, as a state: the command that did not start or answer, and where the session is now
 *  (fin). Try again while it went on where it was; when even the way back failed, the resume command and Copy. Opened,
 *  the attempt's last lines come first, the evidence before the ways forward, and only while reading is on (Marlin). */
function unmovedHtml(s, f, pane) {
  const missed = f.reason === "no_start" ? "didn't start" : `didn't answer in ${f.waited_s ?? 45} s`;
  const where = f.back ? ` · Still on ${cmdChip(f.was)} ${esc(f.wasModel ?? "")}` : ` · ${cmdChip(f.was)} didn't restart`;
  const why = `<p class="why unline">${cmdChip(f.to)} ${missed}${where}</p>`;
  if (!pane) return why;
  const screen = state.reading && f.screen ? `<pre class="screen">${esc(f.screen)}</pre>` : "";
  const run = f.back || !f.resume ? ""
    : `<div class="runline"><p class="cmd">${esc(f.resume)}</p><button class="act small" data-act="copy-resume" data-s="${esc(s.id)}">Copy</button></div>`;
  const again = `<button class="act small" data-act="continue" data-s="${esc(s.id)}" data-head="${esc(f.to)}" data-model="${esc(f.model ?? "")}" data-label="${esc(f.label)}">Try again</button>`;
  return `<div class="unmoved">${why}${screen}${run}${again}</div>`;
}
const failedHtml = (s, f, pane) => (!f ? "" : f.unmoved ? unmovedHtml(s, f, pane) : `<p class="why limit">${esc(f)}</p>`);

/** Press the numbered choice he picked, then read the session again: the card leaves Needs you once its client moves on. */
async function answer(s, choice) {
  ui.answered.set(s.id, { at: Date.now(), choice, offer: state.screens[s.id] }); ui.failed.delete(s.id); render({ stick: false });
  const res = await API.post(sessionPath(s, "answer"), { choice });
  if (!res.ok) { ui.answered.delete(s.id); ui.failed.set(s.id, refusalOf(res, "The answer did not reach it")); }
  await reload();
}

/** His messages Claude Code took back, which the log already marks: the one leftover the console clears unasked. */
const stoppedWords = (s) => new Set((state.logs[s.id]?.messages || []).filter((m) => m.role === "user" && (m.kind === "taken_back" || m === state.logs[s.id].messages.at(-1))).map((m) => (m.text || "").trim()));
/** His message, whole. While a permission waits, Deny first presses its refusing choice, then gives his words as what to
 *  do instead. A draft that did not go through stays in the field. */
async function send(s, text, clear = false) {
  ui.sending.add(s.id); ui.failed.delete(s.id); delete ui.leftover[s.id]; render({ stick: false });
  const deny = s.state === "needs" ? denyOf(s) : null;
  let res = deny ? await API.post(sessionPath(s, "answer"), { choice: deny.choice }) : { ok: true };
  if (res.ok && text) res = await API.post(sessionPath(s, "say"), clear ? { text, clear: true } : { text });
  // what is sent is exactly what he typed: his own stopped words in the prompt are cleared, anything else is shown
  if (!res.ok && res.body?.reason === "draft") {
    if (stoppedWords(s).has(String(res.body.draft).trim())) res = await API.post(sessionPath(s, "say"), { text, clear: true });
    else { ui.sending.delete(s.id); ui.leftover[s.id] = res.body.draft; render({ stick: false }); return; }
  }
  ui.sending.delete(s.id);
  if (res.ok) ui.drafts[s.id] = "";
  else ui.failed.set(s.id, refusalOf(res, deny ? "The refusal did not reach it" : "The message did not reach it"));
  ui.focusSay = true;
  await reload();
}
// Escape on the in-prompt card is its Cancel: nothing sent, nothing cleared, his message stays in the field (fin)
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && ui.open != null && ui.held[ui.open]) { delete ui.held[ui.open]; render({ stick: false }); return; }
  if (e.key === "Escape" && ui.menu) { ui.menu = null; render({ stick: false }); return; }
  if (e.key !== "Escape" || ui.open == null || ui.leftover[ui.open] == null) return;
  delete ui.leftover[ui.open]; ui.focusSay = true; render({ stick: false });
});
root.addEventListener("submit", (e) => {
  e.preventDefault();
  const s = find(e.target.dataset.s), text = e.target.querySelector("#say").value.trim();
  if (!s || ui.sending.has(s.id)) return;
  if (!text && !(s.state === "needs" && denyOf(s))) { e.target.querySelector("#say").focus(); return; }
  send(s, text);
});
root.addEventListener("input", (e) => { if (e.target.id === "say") ui.drafts[e.target.closest("form").dataset.s] = e.target.value; });

document.getElementById("q").addEventListener("input", (e) => {
  ui.q = e.target.value.trim().toLowerCase();
  // a search opens the ended sessions it matched: a match folded away reads as no match. The fold still closes them.
  if (ui.q && state.rows.some((s) => s.state === "ended" && matches(s))) ui.ended = true;
  if (ui.auto) { ui.open = null; autoOpen(); }
  render();
});
document.addEventListener("click", (e) => {
  // a press anywhere but the menu or its button closes Continue on's menu, as a console menu does
  if (ui.menu && !e.target.closest(".menu.models, [data-act=menu]")) { ui.menu = null; render({ stick: false }); }
  if (ui.pmenu && !e.target.closest(".pmenu, [data-act=projmenu]")) { ui.pmenu = false; render({ stick: false }); }
  const el = e.target.closest("[data-act], [data-open]");
  if (!el) return;
  if (!el.dataset.act) { // a card: open it
    if (e.target.closest("button:not(.name), a, input")) return;
    ui.auto = false; open(el.dataset.open); return;
  }
  const s = el.dataset.s && find(el.dataset.s);
  switch (el.dataset.act) {
    case "theme": toggleTheme(); break;
    case "ended": ui.ended = !ui.ended; render({ stick: false }); break;
    case "projmenu": ui.pmenu = !ui.pmenu; render({ stick: false }); break;
    // a pick is the same pick on Teams and Requests; the open session goes if it is not in the project he picked
    case "pickproj": { ui.project = el.dataset.p || null; ui.pmenu = false; PICK.set(ui.project);
      if (ui.open && !inPick(find(ui.open) ?? {})) { ui.open = null; ui.auto = false; }
      render({ stick: false }); if (!ui.open) autoOpen(); break; }
    // a group he folded, and a project's Ended line he opened, stay that way across every reload of this tab
    case "fold": { const p = el.dataset.p; ui.shut.has(p) ? ui.shut.delete(p) : ui.shut.add(p); remember("splice.shut", ui.shut); render({ stick: false }); break; }
    case "rest": { const p = el.dataset.p; ui.rest.has(p) ? ui.rest.delete(p) : ui.rest.add(p); remember("splice.rest", ui.rest); render({ stick: false }); break; }
    case "clear": { const q = document.getElementById("q"); q.value = ""; q.dispatchEvent(new Event("input")); q.focus(); break; }
    case "close": ui.open = null; ui.missing = null; ui.auto = false; render(); break;
    case "stop": if (s) stop(s); break;
    case "menu": if (s) openChoices(s); break;
    case "continue": if (s && !ui.moving.has(s.id)) continueOn(s, el.dataset.head, el.dataset.model, el.dataset.label); break;
    case "copy-resume": if (s) { navigator.clipboard?.writeText(ui.failed.get(s.id)?.resume ?? "").catch(() => {}); el.textContent = "Copied"; setTimeout(() => { el.textContent = "Copy"; }, 1600); } break;
    case "clear-send": if (s && ui.drafts[s.id]?.trim()) send(s, ui.drafts[s.id].trim(), true); break;
    case "keep": if (s) { delete ui.leftover[s.id]; ui.focusSay = true; render({ stick: false }); } break;
    case "held-cancel": if (s) { delete ui.held[s.id]; render({ stick: false }); } break;
    // the button says Copy, so nothing is cleared until his words are on the clipboard: a refused write moves nothing
    case "held-copy": if (s) heldCopy(s, el); break;
    case "answer": if (s && !ui.sending.has(s.id)) answer(s, Number(el.dataset.i)); break;
    case "result": { const k = el.dataset.k; ui.results.has(k) ? ui.results.delete(k) : ui.results.add(k); render({ stick: false }); break; }
    case "earlier": if (s && state.logs[s.id]?.earlier) readLog(s, state.logs[s.id].earlier); break;
  }
});

// Side by side, a session is always open: the top one unless he picked another. When the window narrows, a session the
// page opened on its own closes and the list comes back; one he opened stays.
function autoOpen() {
  ui.auto = wide() && !ui.open;
  if (!ui.auto) return;
  // a search no live session matches opens its newest ended match, so the room the pane holds is never left empty
  const top = sorted()[0] ?? (ui.q ? state.rows.filter((s) => s.state === "ended" && matches(s)).sort((x, y) => y.at - x.at)[0] : null);
  if (top) open(top.id);
}
addEventListener("resize", () => {
  if (!wide() && ui.auto) { ui.open = null; ui.auto = false; render({ stick: false }); } else if (wide() && !ui.open && !ui.missing) autoOpen();
  else render({ stick: false });
});

/** Every read, then one draw. The open conversation is read again only while its session can still add to it, and
 *  never once he has read back with Show earlier, which a fresh read from the end would throw away. */
async function reload() {
  await read();
  if (ui.open && !find(ui.open)) ui.open = null;
  render({ stick: false });
  const s = ui.open && find(ui.open);
  if (s && s.state !== "ended" && !state.logs[s.id]?.extended) readLog(s);
  if (s && s.state !== "ended") readUsage(s);
}

// the door's session is taken before the first draw, which writes the address from what is open (nothing yet)
const door = location.hash.slice(1);
render();
read().then(() => {
  ui.open = find(door)?.id ?? null; // a member card on Teams, or Open session on Requests, opens it here
  ui.missing = door && !ui.open ? door : null; // one splice no longer lists says so in the pane, never an empty page
  if (ui.open && find(ui.open).state === "ended") ui.ended = true; // a door to an ended session shows it among the ended
  render();
  if (ui.open) open(ui.open); else if (!ui.missing) autoOpen();
});
// what a session is doing changes on its own clock: the list is read again while the page is open
setInterval(() => { if (!document.hidden) reload(); }, 5000);
// the silence counter and the resume countdown move each second between reads, from when the turn was read
setInterval(() => {
  for (const el of root.querySelectorAll("[data-silent], [data-resume]")) {
    const st = find(el.dataset.silent || el.dataset.resume)?.stall;
    if (!st) continue;
    const gone = Date.now() - st.at, ms = el.dataset.silent ? st.ms + gone : Math.max(0, st.resumeMs - gone);
    el.lastElementChild.textContent = el.dataset.silent ? counter(ms) : `Resumes in ${counter(ms)}`;
    el.querySelector(".hand")?.style.setProperty("transform", `rotate(${Math.floor(ms / 1000) * 6}deg)`);
  }
}, 1000);
