// Sessions: every agent on every command and project, what it's doing, and its conversation. hitstop's drawing
// (Splice-Animator design/console/sessions.js), built on live data.
//
// WHERE EVERY VALUE COMES FROM. The page carries no data of its own:
//   GET  /api/sessions                      each session as its client registered it: status, what it waits for,
//                                           whether it still runs, its folder, its command, its team
//   GET  /api/heads · /api/models           the commands, the provider each runs on and its models' names
//   GET  /api/heads/{head}/turns/live       the turns running now, by session: the one Stop ends and the model it runs
//   GET  /api/sessions/{id}/transcript      the conversation, newest first, with the cursor to the page before it
//   POST /api/heads/{head}/turns/{id}/stop  Stop, for a session splice did not open
//   GET  /api/sessions/{id}/screen          what a session splice opened is asking on its own screen, and its choices
//   POST /api/sessions/{id}/say · answer · stop   the Message field, an answer's button, and Stop, in the pane splice
//                                           opened for it (SessionDrive.kt); a session it did not open draws none of them
//
// WHAT IS NOT DRAWN YET, because splice can't do it yet (BUILD.md): Continue on, the reason a turn stalled, a session's
// model when no turn is running, a teammate's message as its sender's, and the joint where a session moved. None is
// explained on screen: each appears with the read or the act that makes it true.
"use strict";

// `screens`: what a session's own prompt shows, by session id, read only for sessions splice opened (GET .../screen
// answers 200 for those and refuses the rest). A session with a screen is one splice can drive: it gets the Message
// field and its answers are buttons. One it can't keeps what its transcript says and draws no act it couldn't carry.
const state = { rows: [], heads: [], providerOf: {}, modelLabel: {}, live: {}, logs: {}, screens: {}, usage: {}, error: null, loading: true };
const ui = { open: null, auto: false, q: "", ended: false, results: new Set(), failed: new Map(), stopping: new Set(), drafts: {}, sending: new Set(), hold: false, order: [], looks: new Map(), answered: new Map(), leftover: {} };
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
  const [sessions, heads, models] = await Promise.all([API.get("/api/sessions"), API.get("/api/heads"), API.get("/api/models")]);
  state.error = sessions.ok ? null : refusalOf(sessions, "The sessions could not be read");
  state.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key }));
  for (const row of models.body?.heads || []) {
    state.providerOf[row.head] = PROVIDER_FAMILY[row.provider] ?? row.provider;
    for (const m of row.models || []) if (m.id) state.modelLabel[m.id] = m.label || m.id;
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

// an ended session's process is gone, so splice can't read which command it was on, and its row says so in words
// ("unknown head", SessionsRoutes UNKNOWN_HEAD): that is no command, and nothing is drawn for it
const commandOf = (head) => state.heads.find((h) => h.key === head)?.command ?? null;
const folderOf = (row) => (row.repo?.root || row.cwd || "").split("/").filter(Boolean).pop() || "";

/** One /api/sessions row in the drawing's session shape: the state the kit's look() reads, and what the card shows. */
function sessionOf(row) {
  const turn = state.live[row.session_id] ?? null;
  const ended = row.availability === "gone";
  const working = !ended && (Boolean(turn) || row.status === "working" || row.status === "busy");
  const asked = (row.last?.asks || [])[0];
  const st = ended ? "ended" : row.waiting_for ? "needs" : working ? "working" : "idle";
  return {
    id: row.session_id, row, name: row.name || null, repo: folderOf(row), wt: row.repo?.worktree ?? null,
    head: row.head, cmd: commandOf(row.head), provider: state.providerOf[row.head] ?? null,
    model: ((m) => (m ? state.modelLabel[m] ?? m : null))(turn?.model ?? row.model), // a running turn's model is the one in use now
    team: row.team?.name ?? null, state: st, live: st === "working" && Boolean(turn), turn,
    ask: st === "needs" ? { kind: asked ? "input" : "dialog", asked, call: row.last } : null,
    at: new Date(row.updated_at || row.status_updated_at || row.started_at || 0),
  };
}

/** The conversation, from its newest messages; Show earlier reads the page before. */
async function readLog(s, before = "end") {
  const res = await API.get(`/api/sessions/${encodeURIComponent(s.id)}/transcript?before=${encodeURIComponent(before)}&limit=200`);
  const held = state.logs[s.id];
  if (!res.ok) {
    state.logs[s.id] = held && before !== "end" ? { ...held, error: refusalOf(res, "The earlier messages could not be read") }
      : { messages: null, error: res.status === 404 ? null : refusalOf(res, "The conversation could not be read") };
  } else {
    const page = res.body;
    const messages = before === "end" || !held?.messages ? page.messages : [...page.messages, ...held.messages];
    state.logs[s.id] = { messages, earlier: page.earlier ?? null, extended: before !== "end",
      unreadable: (before === "end" ? 0 : held?.unreadable ?? 0) + (page.unparseable_lines ?? 0) };
  }
  render({ stick: before === "end" });
}

// ---------- what the page says about a session (look() and the lamp are kit.js's) ----------
const title = (s) => s.name || s.repo || `Session ${s.id.slice(0, 8)}`;
const color = (s) => colorOf(s.provider);

function metaHtml(s, L, inPane) {
  const chip = inPane && s.cmd ? `<span class="chip" style="--c:${color(s)}">${s.live ? "<i></i>" : ""}${esc(s.cmd)}</span>` : "";
  const team = s.team ? `<span class="team">${ICON.team}${esc(s.team)}</span>` : "";
  const model = s.model ? `<span>${esc(s.model)}</span>` : "";
  const repo = s.name && s.repo ? `<span>${esc(s.repo)}</span>` : "";
  const past = s.at.toDateString() !== NOW.toDateString();
  const when = `<span class="when">${past ? `${s.at.toLocaleDateString("en-US", { weekday: "short" })} ` : ""}${clock(s.at)}</span>`;
  return `<div class="meta">${chip}<span class="word ${L.cls}">${L.word}</span>${L.detail || ""}${team}${model}${repo}${inPane ? "" : when}</div>`;
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
function askHtml(s, where = "card") {
  // from his press until the session moves on, the answer he pressed keeps its words with the wait ring and the rest
  // wait disabled, drawn from the choices he saw: never "answer it in the terminal" meanwhile (Marlin p165, hitstop 8a04273)
  const going = ui.answered.get(s.id);
  const a = s.ask, offer = going?.offer ?? state.screens[s.id], can = going ? true : drivable(s);
  const busy = going || ui.sending.has(s.id) ? " disabled" : "";
  const pick = (choice, label, i) => going?.choice === choice
    ? `<button class="act answering" disabled>${ICON.wait}${esc(label)}</button>`
    : `<button class="act${i ? "" : " primary"}" data-act="answer" data-s="${esc(s.id)}" data-i="${choice}"${busy}>${esc(label)}</button>`;
  if (a.asked) {
    const opts = a.asked.options || [];
    const answers = !opts.length ? "" : can && !a.asked.multi ? `<div class="answers">${opts.map((o, i) => pick(i + 1, o, i)).join("")}</div>`
      : `<div class="answers">${opts.map((o) => `<span class="scope">${esc(o)}</span>`).join("")}</div>`;
    return `<div class="ask"><p class="q">${esc(a.asked.question)}</p>${answers}${can ? "" : `<p class="why">Answer it in the terminal it runs in.</p>`}</div>`;
  }
  // what it wants to do, off its transcript, then the question its screen puts
  const call = a.call?.tool ? `<p class="cmd"><span class="tname">${esc(a.call.tool)}</span>${esc(a.call.text || "")}</p>` : "";
  const q = offer?.asked ? `<p class="q">${esc(offer.asked)}</p>` : "";
  if (!offer?.choices?.length) return `<div class="ask">${call}<p class="why">Answer it in the terminal it runs in.</p></div>`;
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
  const L = look(s), last = lastOf(s);
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
    + `<div class="top"><button class="name" data-open="${esc(s.id)}">${esc(title(s))}</button>${wt}${s.cmd ? `<span class="chip">${s.live ? "<i></i>" : ""}${esc(s.cmd)}</span>` : ""}${stop}</div>`
    + `${metaHtml(s, L, false)}${body}${failed ? `<p class="why limit">${esc(failed)}</p>` : ""}</article>`;
}

// ---------- the list: what needs him first, then what runs, then what rests ----------
const RANK = (s) => (s.state === "needs" ? 0 : stalled(s) ? 1 : s.state === "working" ? 2 : 3);
const matches = (s) => !ui.q || [s.name, s.repo, s.cmd, s.row.repo?.root, s.row.cwd].some((v) => v && v.toLowerCase().includes(ui.q));
function sorted() { // what waits on him: the longest waiting first; the rest: the latest first
  const list = state.rows.filter((s) => s.state !== "ended" && matches(s))
    .sort((a, b) => RANK(a) - RANK(b) || (RANK(a) <= 1 ? a.at - b.at : b.at - a.at));
  if (!ui.hold) return list;
  // while he works in the list it holds the order last drawn, so a card never moves under his pointer; a new one joins
  // at the end (Marlin p163, hitstop 9e95ae4)
  const at = (s) => { const i = ui.order.indexOf(s.id); return i < 0 ? Infinity : i; };
  return list.map((s, i) => [s, i]).sort(([a, i], [b, j]) => at(a) - at(b) || i - j).map(([s]) => s);
}
function listHtml() {
  if (state.loading) return `<div class="list"><div class="nomatch"><span class="state">Reading</span></div></div>`;
  if (state.error) return `<div class="list"><div class="nomatch"><span class="state">${esc(state.error)}</span></div></div>`;
  const list = sorted(); ui.order = list.map((s) => s.id);
  const ended = state.rows.filter((s) => s.state === "ended" && matches(s)).sort((a, b) => b.at - a.at);
  if (!list.length && !ended.length) {
    return ui.q ? `<div class="list"><div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clear">Clear</button></div></div>`
      : `<div class="list"><div class="nomatch"><span class="state">No sessions</span></div></div>`;
  }
  const fold = ended.length ? `<button class="act quiet fold" data-act="ended" aria-expanded="${ui.ended}">${ended.length} ended${ICON.caret}</button>` : "";
  const endedList = ui.ended ? `<div class="ended">${ended.map(cardHtml).join("")}</div>` : "";
  return `<div class="list">${list.map(cardHtml).join("")}${fold}${endedList}</div>`;
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
  held.messages.forEach((m) => {
    const key = `${s.id}:${m.index}`;
    const at = m.ts ? `<time>${clock(new Date(m.ts))}</time>` : "";
    if (m.role === "tool") return; // drawn under its call
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
  return `<div class="log">${items.join("")}</div>`;
}

function paneHtml() {
  const s = state.rows.find((x) => x.id === ui.open);
  if (!s) return "";
  const L = look(s);
  const stop = stopHtml(s, "act", ICON.stop);
  const close = `<button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button>`;
  const wt = s.wt ? `<span class="wt">${ICON.branch}${esc(s.wt)}</span>` : "";
  const ask = s.state === "needs" ? `<div class="askbox">${askHtml(s, "pane")}</div>` : "";
  const failed = ui.failed.get(s.id);
  return `<section class="pane" style="--c:${color(s)}" aria-label="${esc(title(s))}"><header><span class="lamp ${L.cls}" aria-hidden="true">${L.lamp}</span>`
    + `<div class="who"><div class="top"><span class="name">${esc(title(s))}</span>${wt}</div>${metaHtml(s, L, true)}${usageHtml(s)}</div>`
    + `<div class="acts">${stop}${close}</div></header>${failed ? `<p class="why limit">${esc(failed)}</p>` : ""}${logHtml(s)}${ask}${composerHtml(s)}</section>`;
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
  root.className = `sessions${ui.open ? " open" : ""}`;
  root.innerHTML = listHtml() + paneHtml();
  const field = root.querySelector("#say");
  if (field && (caret || ui.focusSay)) { field.focus(); if (caret) field.setSelectionRange(...caret); ui.focusSay = false; }
  // the conversation he opened is in the address, on the door Teams and Requests use, so Back returns to it
  const addr = ui.open && !ui.auto ? `#${ui.open}` : "";
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
  ui.open = id;
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
  const reads = await Promise.all(state.heads.map((h) => API.get(`/api/perf/turns?${new URLSearchParams({
    head: h.key, since: String(+from), time_zone: zone, n: "1", local: "0", session: s.id.slice(0, 8) })}`)));
  let seen = false; const used = { requests: 0, tin: 0, tout: 0, noIn: 0, noOut: 0, cost: 0, unpriced: 0, from: +from };
  reads.forEach((r, i) => {
    const t = (r.body?.heads || []).find((x) => x.key === state.heads[i].key)?.usage?.totals;
    if (!t) return;
    seen = true; used.requests += t.requests || 0; used.tin += t.input_tokens || 0; used.tout += t.output_tokens || 0;
    used.noIn += t.missing_input_requests || 0; used.noOut += t.missing_output_requests || 0;
    used.cost += t.cost_usd || 0; used.unpriced += (t.unpriced_requests || 0) + (t.requests && t.cost_usd == null ? 1 : 0);
  });
  state.usage[s.id] = seen ? used : null;
  if (ui.open === s.id) render({ stick: false });
}
/** Today's use, one quiet line whose whole length opens its requests on Requests from midnight (hitstop 47fa7e9).
 *  A figure no request reported is left out, never summed to 0, and a cost is said only when every request is priced:
 *  a cost over some of them would read as the whole session's. */
function usageHtml(s) {
  const u = state.usage[s.id];
  if (!u?.requests) return "";
  const num = (v) => `<span class="num">${v}</span>`;
  const figs = [`${num(u.requests.toLocaleString("en-US"))} ${u.requests === 1 ? "request" : "requests"}`,
    u.noIn < u.requests ? `${num(kTok(u.tin))} tokens in` : "", u.noOut < u.requests ? `${num(kTok(u.tout))} ${u.noIn < u.requests ? "" : "tokens "}out` : "",
    u.unpriced === 0 ? num(`≈${u.cost < 0.01 ? u.cost.toFixed(4) : u.cost.toFixed(2)}`) : ""].filter(Boolean);
  return `<a class="today" href="requests.html?${new URLSearchParams({ session: s.id, from: String(u.from) })}"><span class="words">Today · ${figs.join(" · ")}</span>${ICON.door}</a>`;
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
  const el = e.target.closest("[data-act], [data-open]");
  if (!el) return;
  if (!el.dataset.act) { // a card: open it
    if (e.target.closest("button:not(.name), a, input")) return;
    ui.auto = false; open(el.dataset.open); return;
  }
  const s = el.dataset.s && find(el.dataset.s);
  switch (el.dataset.act) {
    case "theme": document.documentElement.dataset.theme = document.documentElement.dataset.theme === "day" ? "night" : "day"; break;
    case "ended": ui.ended = !ui.ended; render({ stick: false }); break;
    case "clear": { const q = document.getElementById("q"); q.value = ""; q.dispatchEvent(new Event("input")); q.focus(); break; }
    case "close": ui.open = null; ui.auto = false; render(); break;
    case "stop": if (s) stop(s); break;
    case "clear-send": if (s && ui.drafts[s.id]?.trim()) send(s, ui.drafts[s.id].trim(), true); break;
    case "keep": if (s) { delete ui.leftover[s.id]; ui.focusSay = true; render({ stick: false }); } break;
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
  if (!wide() && ui.auto) { ui.open = null; ui.auto = false; render({ stick: false }); } else if (wide() && !ui.open) autoOpen();
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
  if (ui.open && find(ui.open).state === "ended") ui.ended = true; // a door to an ended session shows it among the ended
  render();
  if (ui.open) open(ui.open); else autoOpen();
});
// what a session is doing changes on its own clock: the list is read again while the page is open
setInterval(() => { if (!document.hidden) reload(); }, 5000);
