// Sessions: every agent on every command and project, what it's doing, and its conversation. hitstop's drawing
// (Splice-Animator design/console/sessions.js), built on live data.
//
// WHERE EVERY VALUE COMES FROM. The page carries no data of its own:
//   GET  /api/sessions                      each session as its client registered it: status, what it waits for,
//                                           whether it still runs, its folder, its command, its team
//   GET  /api/heads · /api/models           the commands, the provider each runs on and its models' names
//   GET  /api/heads/{head}/turns/live       the turns running now, by session: the one Stop ends and the model it runs
//   GET  /api/sessions/{id}/transcript      the conversation, newest first, with the cursor to the page before it
//   POST /api/heads/{head}/turns/{id}/stop  Stop
//
// WHAT IS NOT DRAWN YET, because splice can't do it yet (BUILD.md): writing to a session and answering what it waits
// on (session-keyed terminal routes, building now), Continue on, the reason a turn stalled, a session's model when no
// turn is running, a teammate's message as its sender's, and the joint where a session moved. None is explained on
// screen: each appears with the read or the act that makes it true.
"use strict";

const state = { rows: [], heads: [], providerOf: {}, modelLabel: {}, live: {}, logs: {}, error: null, loading: true };
const ui = { open: null, auto: false, q: "", ended: false, results: new Set(), failed: new Map(), stopping: new Set() };
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
  state.loading = false;
}

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
    model: row.model ? state.modelLabel[row.model] ?? row.model : turn?.model ? state.modelLabel[turn.model] ?? turn.model : null,
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

// What a waiting session asks, as its transcript and screen say it. Answering it from here comes with the
// session-keyed answer route; until then the card says where the answer is given.
function askHtml(s) {
  const a = s.ask;
  if (a.asked) return `<div class="ask"><p class="q">${esc(a.asked.question)}</p>${a.asked.options?.length ? `<div class="answers">${a.asked.options.map((o) => `<span class="scope">${esc(o)}</span>`).join("")}</div>` : ""}</div>`;
  const call = a.call?.tool ? `<p class="cmd"><span class="tname">${esc(a.call.tool)}</span>${esc(a.call.text || "")}</p>` : "";
  return `<div class="ask">${call}<p class="why">Answer it in the terminal it runs in.</p></div>`;
}

const lastOf = (s) => s.row.last;
function cardHtml(s) {
  const L = look(s), last = lastOf(s);
  const stop = s.live ? `<button class="act quiet small" data-act="stop" data-s="${esc(s.id)}"${ui.stopping.has(s.id) ? " disabled" : ""}>Stop</button>` : "";
  const wt = s.wt ? `<span class="wt">${ICON.branch}${esc(s.wt)}</span>` : "";
  let body = "";
  if (s.state === "needs") body = askHtml(s);
  else if (s.state !== "ended" && last?.text) body = last.tool ? `<p class="last tool"><b>${esc(last.tool)}</b>${esc(last.text)}</p>` : `<p class="last">${esc(last.text.replace(/`/g, ""))}</p>`;
  const failed = ui.failed.get(s.id);
  return `<article class="card s ${L.cls}" style="--c:${color(s)}" data-key="s:${esc(s.id)}" data-open="${esc(s.id)}" aria-current="${ui.open === s.id}">`
    + `<span class="lamp ${L.cls}" aria-hidden="true">${L.lamp}</span>`
    + `<div class="top"><button class="name" data-open="${esc(s.id)}">${esc(title(s))}</button>${wt}${s.cmd ? `<span class="chip">${s.live ? "<i></i>" : ""}${esc(s.cmd)}</span>` : ""}${stop}</div>`
    + `${metaHtml(s, L, false)}${body}${failed ? `<p class="why limit">${esc(failed)}</p>` : ""}</article>`;
}

// ---------- the list: what needs him first, then what runs, then what rests ----------
const RANK = (s) => (s.state === "needs" ? 0 : stalled(s) ? 1 : s.state === "working" ? 2 : 3);
const matches = (s) => !ui.q || [s.name, s.repo, s.cmd, s.row.repo?.root, s.row.cwd].some((v) => v && v.toLowerCase().includes(ui.q));
function sorted() { // what waits on him: the longest waiting first; the rest: the latest first
  return state.rows.filter((s) => s.state !== "ended" && matches(s))
    .sort((a, b) => RANK(a) - RANK(b) || (RANK(a) <= 1 ? a.at - b.at : b.at - a.at));
}
function listHtml() {
  if (state.loading) return `<div class="list"><div class="nomatch"><span class="state">Reading</span></div></div>`;
  if (state.error) return `<div class="list"><div class="nomatch"><span class="state">${esc(state.error)}</span></div></div>`;
  const list = sorted(), ended = state.rows.filter((s) => s.state === "ended" && matches(s)).sort((a, b) => b.at - a.at);
  if (!list.length && !ended.length) {
    return ui.q ? `<div class="list"><div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clear">Clear</button></div></div>`
      : `<div class="list"><div class="nomatch"><span class="state">No sessions</span></div></div>`;
  }
  const fold = ended.length ? `<button class="act quiet fold" data-act="ended" aria-expanded="${ui.ended}">${ended.length} ended</button>` : "";
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
        + `${open && !none ? `<pre class="result">${esc(result)}</pre>` : ""}</div>`);
      return;
    }
    if (!m.text) return;
    items.push(`<div class="msg ${m.role === "user" ? "his" : "agent"}"><div class="body">${md(m.text)}</div>${at}</div>`);
  });
  return `<div class="log">${items.join("")}</div>`;
}

function paneHtml() {
  const s = state.rows.find((x) => x.id === ui.open);
  if (!s) return "";
  const L = look(s);
  const stop = s.live ? `<button class="act" data-act="stop" data-s="${esc(s.id)}"${ui.stopping.has(s.id) ? " disabled" : ""}>${ICON.stop}Stop</button>` : "";
  const close = `<button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button>`;
  const wt = s.wt ? `<span class="wt">${ICON.branch}${esc(s.wt)}</span>` : "";
  const ask = s.state === "needs" ? `<div class="askbox">${askHtml(s)}</div>` : "";
  const failed = ui.failed.get(s.id);
  return `<section class="pane" style="--c:${color(s)}" aria-label="${esc(title(s))}"><header><span class="lamp ${L.cls}" aria-hidden="true">${L.lamp}</span>`
    + `<div class="who"><div class="top"><span class="name">${esc(title(s))}</span>${wt}</div>${metaHtml(s, L, true)}</div>`
    + `<div class="acts">${stop}${close}</div></header>${failed ? `<p class="why limit">${esc(failed)}</p>` : ""}${logHtml(s)}${ask}</section>`;
}

// ---------- render ----------
function render({ stick = true } = {}) {
  const oldLog = root.querySelector(".log"), keep = oldLog && !stick ? oldLog.scrollTop : null;
  root.className = `sessions${ui.open ? " open" : ""}`;
  root.innerHTML = listHtml() + paneHtml();
  // the conversation he opened is in the address, on the door Teams and Requests use, so Back returns to it
  const addr = ui.open && !ui.auto ? `#${ui.open}` : "";
  if (location.hash !== addr) history.replaceState(history.state, "", `${location.pathname}${location.search}${addr}`);
  const log = root.querySelector(".log");
  if (log) log.scrollTop = keep ?? log.scrollHeight;
}
const find = (id) => state.rows.find((s) => s.id === id);

function open(id) {
  ui.open = id;
  render();
  const s = find(id);
  if (s && !state.logs[id]) readLog(s);
}

// ---------- the acts ----------
// Stop ends the running turn (LiveTurnsRoutes.kt). A 404 means the turn had already ended: the card just updates.
async function stop(s) {
  if (!s.turn) return;
  ui.stopping.add(s.id); ui.failed.delete(s.id); render({ stick: false });
  const res = await API.post(`/api/heads/${encodeURIComponent(s.turn.head)}/turns/${encodeURIComponent(s.turn.id)}/stop`);
  ui.stopping.delete(s.id);
  if (!res.ok && res.status !== 404) ui.failed.set(s.id, refusalOf(res, "The turn could not be stopped"));
  await reload();
}

document.getElementById("q").addEventListener("input", (e) => {
  ui.q = e.target.value.trim().toLowerCase();
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
    case "result": { const k = el.dataset.k; ui.results.has(k) ? ui.results.delete(k) : ui.results.add(k); render({ stick: false }); break; }
    case "earlier": if (s && state.logs[s.id]?.earlier) readLog(s, state.logs[s.id].earlier); break;
  }
});

// Side by side, a session is always open: the top one unless he picked another. When the window narrows, a session the
// page opened on its own closes and the list comes back; one he opened stays.
function autoOpen() {
  ui.auto = wide() && !ui.open;
  if (ui.auto) { const top = sorted()[0]; if (top) open(top.id); }
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
}

render();
read().then(() => {
  ui.open = find(location.hash.slice(1))?.id ?? null; // a member card on Teams, or Open session on Requests, opens it here
  if (ui.open && find(ui.open).state === "ended") ui.ended = true; // a door to an ended session shows it among the ended
  render();
  if (ui.open) readLog(find(ui.open)); else autoOpen();
});
// what a session is doing changes on its own clock: the list is read again while the page is open
setInterval(() => { if (!document.hidden) reload(); }, 5000);
