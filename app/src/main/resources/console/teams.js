// Teams: a team is his. Members on different models, each a role on a command, with a goal; splice carries the
// members' sessions and reports what they do. Create one and start its members; track each member's state, their
// messages to each other, what each is doing and what it has cost.
//
// WHERE EVERY VALUE COMES FROM. The page carries no data of its own and keeps no copy of one:
//   GET  /api/teams                        the teams and their slots, archived included
//   GET  /api/sessions                     each bound session's own state, as its client registered it
//   GET  /api/heads · /api/models · /api/auth   the commands, the models each serves and its accounts
//   GET  /api/teams/{id}/economics         turns, tokens and dollars per slot, over every session it held
//   GET  /api/teams/{id}/chat?from=&to=    the day's messages between members, from the sender's own transcript
//   GET  /api/teams/{id}/activity?from=&to=   the day's tool work per member, each a tool and its object
//   PUT  /api/teams · /api/teams/{id} · /api/teams/{id}/sessions · .../slots/{slot}/instructions
//   POST /api/teams/{id}/archive · .../slots/{slot}/start · .../slots/{slot}/stop
// The day panels take the VIEWER's own day in epoch milliseconds, so the board turns over at his midnight.
//
// A REFUSAL IS SHOWN WHERE IT HAPPENED, in the words the daemon used. A start that does not come up puts its own
// sentence on the member's card beside Try again, because "the terminal is still showing a trust prompt" is the
// only thing that tells him what to do next.
"use strict";

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
  more: G('<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>', 'aria-hidden="true"'),
  back: G('<path d="M15 5l-7 7 7 7"/>', 'aria-hidden="true"'),
  next: G('<path d="M9 5l7 7-7 7"/>', 'aria-hidden="true"'),
  caret: G('<path d="M9 5l7 7-7 7"/>', 'class="caret" aria-hidden="true"'),
  trace: '<svg class="wave" viewBox="0 0 40 40" aria-hidden="true"><polyline class="base" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"/><polyline class="beat" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"/></svg>',
  ask: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v11h-9l-4.5 3.5V16H4z"/><path d="M12 13.2h0"/><path d="M10 9.2a2 2 0 1 1 2.6 1.9"/></svg>',
  wait: '<svg class="wait" viewBox="0 0 40 40" aria-hidden="true"><circle cx="20" cy="20" r="14"/></svg>',
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
const big = (n) => (n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${Math.round(n / 1e3)}k` : String(n ?? 0));
const usd = (n) => `≈$${n.toFixed(2)}`;

// ---------- what the page holds: the daemon's answers, and what he is doing to them ----------
const state = {
  teams: [], sessions: {}, heads: [], models: {}, accounts: {}, providerOf: {},
  economics: {}, chat: null, activity: null,
  error: null, loading: true,
};
const ui = { team: null, day: 0, menu: null, compose: null, pick: null, archived: false, starting: new Set(), failed: new Map() };
const root = document.getElementById("teams");
const sheet = document.getElementById("sheet");

const team = () => state.teams.find((t) => t.id === ui.team) || null;
const slotOf = (tm, id) => tm.slots.find((s) => s.id === id) || null;
const sessOf = (slot) => (slot.session ? state.sessions[slot.session] : null);

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
async function read() {
  state.loading = true;
  render();
  const [teams, sessions, heads, models, auth] = await Promise.all([
    API.get("/api/teams"), API.get("/api/sessions"), API.get("/api/heads"), API.get("/api/models"), API.get("/api/auth"),
  ]);
  state.error = teams.ok ? null : refusalOf(teams, "The teams could not be read");
  state.teams = teams.body?.teams || [];
  state.sessions = Object.fromEntries((sessions.body?.sessions || []).filter((s) => s.session_id).map((s) => [s.session_id, s]));
  state.heads = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key }));
  state.models = {};
  state.providerOf = {};
  for (const row of models.body?.heads || []) {
    state.providerOf[row.head] = row.provider;
    state.models[row.head] = (row.models || []).filter((m) => m.resolved !== false).map((m) => ({ id: m.id, label: m.label || m.id }));
  }
  // /api/auth answers one row per ACCOUNT, each naming the heads that can send as it; a slot picks by head.
  state.accounts = {};
  for (const account of auth.body?.accounts || []) {
    for (const head of account.heads || []) {
      if (account.label) (state.accounts[head] ??= []).push(account.label);
    }
  }
  if (!state.teams.some((t) => t.id === ui.team)) ui.team = state.teams.find((t) => !t.archived)?.id ?? state.teams[0]?.id ?? null;
  state.loading = false;
  render();
  await readTeam();
}

/** The selected team's own three reads, each of which can refuse on its own. */
async function readTeam() {
  const tm = team();
  if (!tm) return;
  const { from, to } = dayWindow(ui.day);
  const day = `from=${from}&to=${to}`;
  const [economics, chat, activity] = await Promise.all([
    API.get(`/api/teams/${tm.id}/economics`),
    API.get(`/api/teams/${tm.id}/chat?${day}`),
    API.get(`/api/teams/${tm.id}/activity?${day}`),
  ]);
  state.economics[tm.id] = economics.ok ? economics.body : { error: refusalOf(economics, "The figures could not be read") };
  state.chat = chat.ok ? chat.body : { error: refusalOf(chat, "The messages could not be read") };
  state.activity = activity.ok ? activity.body : { error: refusalOf(activity, "The activity could not be read") };
  render();
}

/** The daemon's own sentence for a refused call, or a plain one when it sent none. */
function refusalOf(answer, fallback) {
  if (answer.status === 0) return "splice did not answer. It may not be running.";
  return answer.body?.error || `${fallback} (${answer.status}).`;
}

// ---------- a member's state, in the words its own client registered ----------
const WAITING = { "input needed": "Needs you", "permission prompt": "Needs you" };
function stateOf(session) {
  if (!session) return { cls: "ended", lamp: "<i></i>", word: "Gone", detail: "" };
  if (session.availability === "gone") return { cls: "ended", lamp: "<i></i>", word: "Ended", detail: "" };
  // No detail beside the word: the card's own ask, right below it, says what is wanted better than
  // Claude Code's "input needed" does, and the drawing has nothing there for the same reason.
  if (session.waiting_for) {
    return { cls: "needs", lamp: GLYPH.ask, word: WAITING[session.waiting_for] || "Needs you", detail: "" };
  }
  if (session.status === "working" || session.status === "busy") return { cls: "working", lamp: GLYPH.trace, word: "Working", detail: "" };
  const word = session.status ? session.status[0].toUpperCase() + session.status.slice(1) : "Idle";
  return { cls: session.availability === "stale" ? "stopped" : "idle", lamp: "<i></i>", word, detail: "" };
}
const liveNow = (session) => Boolean(session) && session.availability === "live" && !session.waiting_for &&
  (session.status === "working" || session.status === "busy");

// ---------- the figures: turns, tokens and dollars, and the turns no rate card priced ----------
function useOf(tm, slotId) {
  const row = (state.economics[tm.id]?.slots || []).find((r) => r.slot === slotId);
  if (!row) return null;
  // The tokens figure is everything the slot's turns moved, the two cache counts included, as the route reports them.
  const t = row.tokens || {};
  const tokens = (t.input || 0) + (t.cache_read || 0) + (t.cache_write || 0) + (t.output || 0);
  return { turns: row.turns ?? 0, tokens, usd: row.cost_usd ?? null, unpriced: row.unpriced_turns ?? 0 };
}
function figures(u, since) {
  if (!u) return "";
  const left = u.unpriced ? `<span class="unpriced">· leaves out ${u.unpriced} ${u.unpriced === 1 ? "turn" : "turns"} with no price</span>` : "";
  const money = u.usd == null ? (u.unpriced ? `<span class="unpriced">No price</span>` : "") : `<span>${usd(u.usd)}${left}</span>`;
  return `<span class="figs"><span>${u.turns} turns</span><span>${big(u.tokens)} tokens</span>${money}` +
    `${since ? `<span class="since">Since ${monthDay(since)}</span>` : ""}</span>`;
}
function totals(tm) {
  const us = tm.slots.map((s) => useOf(tm, s.id)).filter(Boolean);
  if (!us.length) return null;
  const priced = us.filter((u) => u.usd != null);
  return {
    turns: us.reduce((a, u) => a + u.turns, 0),
    tokens: us.reduce((a, u) => a + u.tokens, 0),
    usd: priced.length ? priced.reduce((a, u) => a + u.usd, 0) : null,
    unpriced: us.reduce((a, u) => a + u.unpriced, 0),
  };
}
/** The oldest turn the perf files still hold, named only when it is after the team was made. */
function sinceOf(tm) {
  const oldest = state.economics[tm.id]?.oldest_turn_epoch_millis;
  return oldest && oldest > (tm.created_epoch_millis || 0) ? oldest : null;
}

// ---------- the tabs: one per team, its members' colours on it ----------
function tabHtml(tm) {
  const needs = tm.slots.some((s) => sessOf(s)?.waiting_for);
  const blots = tm.slots.map((s) => `<i style="--c:${headColor(s.head)}"${sessOf(s) ? "" : ' class="open"'}></i>`).join("");
  return `<button class="tab${needs ? " needs" : ""}" data-act="team" data-t="${esc(tm.id)}" aria-current="${ui.team === tm.id}">` +
    `<span class="tname">${esc(tm.name)}</span><span class="blots" aria-hidden="true">${blots}</span></button>`;
}
function tabsHtml() {
  const on = state.teams.filter((t) => !t.archived);
  const off = state.teams.filter((t) => t.archived);
  const fold = off.length ? `<button class="act quiet fold" data-act="archived" aria-expanded="${ui.archived}">${off.length} archived</button>` : "";
  const newT = `<button class="act quiet new" data-act="new" aria-pressed="${ui.compose?.id === null}"><span class="plus" aria-hidden="true">+</span>New team</button>`;
  const offTabs = ui.archived ? `<span class="rule" aria-hidden="true"></span>${off.map(tabHtml).join("")}` : "";
  return `<nav class="tabs" aria-label="Teams">${on.map(tabHtml).join("")}${newT}${offTabs}${fold}</nav>`;
}

// ---------- a member: the slot's role on its command, its session's state as the lamp ----------
function memberHtml(tm, slot) {
  const s = sessOf(slot);
  const key = `${tm.id}/${slot.id}`;
  const ro = tm.archived;
  const lead = slot.lead ? `<span class="lead">Lead</span>` : "";
  const command = commandOf(slot.head);
  const chip = `<span class="chip">${s && liveNow(s) ? "<i></i>" : ""}${esc(command)}</span>`;
  const model = modelLabel(slot.head, slot.model);
  if (!s) return vacantHtml(tm, slot, { key, ro, lead, chip, model });
  const L = stateOf(s);
  const stop = liveNow(s) && !ro ? `<button class="act quiet small" data-act="stop" data-s="${esc(slot.id)}">Stop</button>` : "";
  const now = lastActivity(slot.id);
  const body = L.cls === "needs" ? askHtml(s, slot.id, ro, key) : now ? `<p class="now">${activityHtml(now)}</p>` : "";
  // What the member is on: the name its session carries, else the folder it is in, as the drawing reads it.
  const folder = s.repo?.root || s.cwd || "";
  const where = s.name || folder.split("/").pop();
  const heard = s.updated_at || s.status_updated_at;
  return `<article class="card m ${L.cls}" style="--c:${headColor(slot.head)}" data-key="m:${esc(slot.id)}" aria-label="${esc(slot.role)}">` +
    `<span class="lamp ${L.cls}" aria-hidden="true">${L.lamp}</span>` +
    `<div class="top"><span class="role">${esc(slot.role)}</span>${lead}${chip}${stop}</div>` +
    `<div class="meta"><span class="word ${L.cls}">${L.word}</span>${L.detail}<span>${esc(model)}</span>` +
    `${where ? `<span>${esc(where)}</span>` : ""}${heard ? `<span class="when">${hhmm(heard)}</span>` : ""}</div>` +
    `${body}<div class="use">${figures(useOf(tm, slot.id))}</div></article>`;
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
  const failed = ui.failed.get(key);
  const why = failed ? `<p class="why">${esc(failed)}</p>` : "";
  if (!asked) {
    const call = session.last;
    const what = call?.tool
      ? `<p class="cmd"><span class="tname">${esc(call.tool)}</span>${esc(call.text || "")}</p>`
      : "";
    // No labels splice may spell, so the card says where the answer is given rather than offering an act it
    // cannot carry — the second half of the sentence the answer route itself returns for a pane it cannot drive.
    return `<div class="ask">${what}<p class="why">Answer it in the terminal it runs in.</p>${why}</div>`;
  }
  const buttons = readOnly || asked.multi ? "" :
    `<div class="answers">${asked.options.map((o, i) =>
      `<button class="act${i ? "" : " primary"}" data-act="answer" data-s="${esc(slotId)}" data-i="${i + 1}">${esc(o)}</button>`).join("")}</div>`;
  const chips = asked.multi
    ? `<div class="answers">${asked.options.map((o) => `<span class="scope">${esc(o)}</span>`).join("")}</div>`
    : "";
  return `<div class="ask"><p class="q">${esc(asked.question)}</p>${buttons}${chips}${why}</div>`;
}

/** A slot with no session: start one on its command, or hand it one of its command's that already runs. */
function vacantHtml(tm, slot, { key, ro, lead, chip, model }) {
  const failed = ui.failed.get(key);
  let word = `<span class="word">No session</span>`;
  let acts = "";
  let why = "";
  if (ui.starting.has(key)) {
    word = `<span class="word starting">${GLYPH.wait}Starting</span>`;
  } else if (failed) {
    word = `<span class="word limit">Not started</span>`;
    why = `<p class="why">${esc(failed)}</p>`;
    acts = ro ? "" : `<button class="act" data-act="start" data-s="${esc(slot.id)}">Try again</button>`;
  } else if (!ro) {
    const free = Object.values(state.sessions).filter((x) =>
      x.head === slot.head && x.availability !== "gone" && !state.teams.some((t) => t.slots.some((y) => y.session === x.session_id)));
    acts = `<button class="act primary" data-act="start" data-s="${esc(slot.id)}">Start</button>` +
      (free.length ? `<button class="act" data-act="use" data-s="${esc(slot.id)}" aria-expanded="${ui.menu === key}">Use session</button>` : "");
    if (ui.menu === key) {
      acts += `<div class="menu use">${free.map((x) =>
        `<button data-act="bind" data-s="${esc(slot.id)}" data-to="${esc(x.session_id)}"><span>${esc(x.name || x.repo?.root?.split("/").pop() || x.session_id.slice(0, 8))}</span>` +
        `<span class="mmeta">${esc(modelLabel(x.head, null) || x.head)}</span></button>`).join("")}</div>`;
    }
  }
  return `<article class="card m vacant" style="--c:${headColor(slot.head)}" data-key="m:${esc(slot.id)}" aria-label="${esc(slot.role)}">` +
    `<span class="lamp open" aria-hidden="true"></span>` +
    `<div class="top"><span class="role">${esc(slot.role)}</span>${lead}${chip}</div>` +
    `<div class="meta">${word}<span>${esc(model)}</span>${slot.account ? `<span>${esc(slot.account)}</span>` : ""}</div>` +
    `${why}${acts ? `<div class="slot-acts">${acts}</div>` : ""}<div class="use">${figures(useOf(tm, slot.id))}</div></article>`;
}

const commandOf = (head) => state.heads.find((h) => h.key === head)?.command || head;
const modelLabel = (head, id) => {
  const rows = state.models[head] || [];
  return (id ? rows.find((m) => m.id === id)?.label || id : rows[0]?.label) || "";
};

// ---------- what a member did: the tool's mark and its object, never splice's sentence ----------
const activityRows = () => (state.activity?.entries || []);
const lastActivity = (slotId) => activityRows().filter((a) => a.slot === slotId && a.tool).at(-1) || null;
const activityHtml = (a) => `${GLYPH[a.tool] || GLYPH.tool}<span class="obj">${esc(a.object || a.tool)}</span>`;

// ---------- the room: members, their messages to each other, what each did ----------
/** A panel the history window no longer holds says so in the daemon's own words, at its own moment. */
function keptHtml(panel) {
  if (!panel) return "";
  if (panel.error) return `<p class="empty limit">${esc(panel.error)}</p>`;
  if (panel.state === "not_kept" || panel.state === "deleted" || panel.state === "off") {
    return `<p class="empty">${esc(panel.reason || "Not kept")}</p>`;
  }
  if (panel.state === "partially_kept") {
    return `<p class="cut"><span>Kept from ${hhmm(panel.oldest_kept_epoch_millis)}</span></p>`;
  }
  return "";
}
const gone = (panel) => Boolean(panel?.error) || ["not_kept", "deleted", "off"].includes(panel?.state);

function chatHtml(tm) {
  const panel = state.chat;
  if (!panel) return `<p class="empty">Reading…</p>`;
  if (gone(panel)) return keptHtml(panel);
  const msgs = panel.messages || [];
  if (!msgs.length) return keptHtml(panel) + `<p class="empty">No messages</p>`;
  return keptHtml(panel) + msgs.map((m) => {
    const from = m.from_slot ? slotOf(tm, m.from_slot) : null;
    const body = m.text == null
      ? `<div class="body none">${esc(m.missing_reason || "No text")}</div>`
      : `<div class="body">${md(m.text)}</div>`;
    return `<div class="tm${from ? "" : " outside"}" style="--c:${from ? headColor(from.head) : "var(--track-line)"}">` +
      `<div class="who">${party(tm, m.from_slot, m.from)}${GLYPH.msg}${party(tm, m.to_slot, m.to)}<time>${hhmm(m.at)}</time></div>${body}</div>`;
  }).join("");
}

/** A member or a session outside the team, as the chat and the feed name it: its role in its command's colour. */
function party(tm, slotId, address) {
  const s = slotId ? slotOf(tm, slotId) : null;
  if (s) return `<span class="party" style="--c:${headColor(s.head)}"><i></i>${esc(s.role)}</span>`;
  const name = String(address || "").replace(/^uds:.*$/, "");
  return `<span class="party out">${esc(name || "Outside")}</span>`;
}

function feedHtml(tm) {
  const panel = state.activity;
  if (!panel) return `<p class="empty">Reading…</p>`;
  if (gone(panel)) return keptHtml(panel);
  const rows = activityRows().filter((a) => a.slot);
  if (!rows.length) return keptHtml(panel) + `<p class="empty">No activity</p>`;
  return keptHtml(panel) + `<ol>${rows.map((a) => {
    const s = slotOf(tm, a.slot);
    return `<li style="--c:${s ? headColor(s.head) : "var(--track-line)"}"><time>${hhmm(a.at)}</time>${party(tm, a.slot, null)}` +
      `<span class="what">${activityHtml(a)}</span></li>`;
  }).join("")}</ol>`;
}

function boardHtml(tm) {
  const T = totals(tm);
  const ro = tm.archived;
  const more = ro ? "" : `<button class="act" data-act="edit">Edit</button>` +
    `<button class="icon" data-act="more" aria-label="More" aria-expanded="${ui.menu === "more"}">${GLYPH.more}</button>` +
    (ui.menu === "more" ? `<div class="menu"><button data-act="archive">Archive</button></div>` : "");
  const day = `<div class="day"><button class="icon" data-act="day" data-d="1" aria-label="Earlier day"${ui.day >= 6 ? " disabled" : ""}>${GLYPH.back}</button>` +
    `<span class="dname">${dayName(ui.day)}</span>` +
    `<button class="icon" data-act="day" data-d="-1" aria-label="Later day"${ui.day === 0 ? " disabled" : ""}>${GLYPH.next}</button></div>`;
  return `<section class="team${ro ? " archived" : ""}" data-key="team:${esc(tm.id)}">` +
    `<header class="team-head"><div class="who"><h2>${esc(tm.name)}</h2>${tm.goal ? `<p class="goal">${esc(tm.goal)}</p>` : ""}` +
    `<div class="where">${tm.repo ? `<span class="repo">${GLYPH.folder}${esc(tm.repo)}</span>` : ""}${T ? figures(T, sinceOf(tm)) : ""}</div></div>` +
    `<div class="acts">${more}</div></header>` +
    `<div class="room"><div class="members">${tm.slots.map((s) => memberHtml(tm, s)).join("")}</div>` +
    `<section class="talk" aria-label="Chat"><header>${day}</header><div class="log chat">${chatHtml(tm)}</div></section>` +
    `<section class="feed"><header><h3>Activity</h3><span class="dname">${dayName(ui.day)}</span></header>` +
    `<div class="flow">${feedHtml(tm)}</div></section></div></section>`;
}

// ---------- the composer: name, goal, repo, and the members as rows ----------
const blank = () => ({ role: "", head: null, model: null, account: null, lead: false, instructions: "", open: false });
function composeFrom(tm) {
  return {
    id: tm ? tm.id : null, name: tm?.name || "", goal: tm?.goal || "", repo: tm?.repo || "", error: null,
    slots: tm ? tm.slots.map((s) => ({ ...blank(), ...s, open: false })) : [{ ...blank(), lead: true }, blank()],
  };
}
const ready = (c) => c.name.trim() && c.slots.length && c.slots.every((s) => s.head);

function pickHtml(c, i) {
  const p = ui.pick;
  const s = c.slots[i];
  if (!p || p.i !== i) return "";
  if (p.what === "head") {
    return `<div class="menu pick-menu" data-what="head">${state.heads.map((h) =>
      `<button data-act="set" data-i="${i}" data-what="head" data-v="${esc(h.key)}"><span class="blot" style="--c:${headColor(h.key)}"></span>${esc(h.command)}</button>`).join("")}</div>`;
  }
  if (p.what === "model") {
    const rows = state.models[s.head] || [];
    if (!rows.length) return `<div class="menu pick-menu" data-what="model"><p class="empty">This command declares no models.</p></div>`;
    return `<div class="menu pick-menu" data-what="model">${rows.map((m) =>
      `<button data-act="set" data-i="${i}" data-what="model" data-v="${esc(m.id)}">${esc(m.label)}</button>`).join("")}</div>`;
  }
  return `<div class="menu pick-menu" data-what="account">${(state.accounts[s.head] || []).map((a) =>
    `<button data-act="set" data-i="${i}" data-what="account" data-v="${esc(a)}">${esc(a)}</button>`).join("")}</div>`;
}

function slotRowHtml(c, s, i) {
  const btn = (what, val, label) => `<button class="pickbtn${val ? "" : " unset"}" data-act="pick" data-i="${i}" data-what="${what}" ` +
    `aria-expanded="${ui.pick?.i === i && ui.pick.what === what}">${val ? label : what === "head" ? "Command" : what === "model" ? "Model" : "Account"}${GLYPH.caret}</button>`;
  const head = btn("head", s.head, `<span class="blot" style="--c:${headColor(s.head)}"></span>${esc(commandOf(s.head) || "")}`);
  const model = s.head ? btn("model", s.model, esc(modelLabel(s.head, s.model))) : "";
  const account = s.head && (state.accounts[s.head] || []).length ? btn("account", s.account, esc(s.account || "")) : "";
  return `<article class="card slot-row" style="--c:${s.head ? headColor(s.head) : "var(--track-line)"}" data-key="row:${i}">` +
    `<div class="top"><input class="field role-in" data-i="${i}" value="${esc(s.role)}" placeholder="Role" aria-label="Role" list="roles" autocomplete="off" spellcheck="false">` +
    `${head}${model}${account}<button class="leadtog" data-act="lead" data-i="${i}" aria-pressed="${s.lead}">Lead</button>` +
    `<button class="act quiet small" data-act="instr" data-i="${i}" aria-expanded="${s.open}">Instructions</button>` +
    `<button class="act quiet small" data-act="rm" data-i="${i}"${c.slots.length < 2 ? " disabled" : ""}>Remove</button>${pickHtml(c, i)}</div>` +
    (s.open ? `<textarea class="field instr" data-i="${i}" rows="4" aria-label="Instructions">${esc(s.instructions || "")}</textarea>` : "") +
    `</article>`;
}

function composerHtml() {
  const c = ui.compose;
  const ok = ready(c);
  const err = c.error ? `<span class="state limit" role="status">${esc(c.error)}</span>` : "";
  return `<section class="compose" data-key="compose" role="dialog" aria-modal="true" aria-labelledby="t-title">` +
    `<h2 id="t-title">${c.id ? esc(state.teams.find((t) => t.id === c.id)?.name) : "New team"}</h2>` +
    `<div class="frow"><label for="t-name">Name</label><input class="field" id="t-name" value="${esc(c.name)}" autocomplete="off" spellcheck="false"></div>` +
    `<div class="frow"><label for="t-goal">Goal</label><textarea class="field" id="t-goal" rows="2">${esc(c.goal)}</textarea></div>` +
    `<div class="frow"><label for="t-repo">Repo</label><input class="field" id="t-repo" value="${esc(c.repo)}" autocomplete="off" spellcheck="false"${c.error ? ' aria-invalid="true"' : ""}></div>` +
    `${err ? `<div class="frow">${err}</div>` : ""}` +
    `<h3>Members</h3><div class="rows">${c.slots.map((s, i) => slotRowHtml(c, s, i)).join("")}` +
    `<button class="add" data-act="add-slot"><span class="plus" aria-hidden="true">+</span>Add member</button></div>` +
    `<datalist id="roles"><option value="builder"><option value="reviewer"><option value="researcher"></datalist>` +
    `<footer><button class="act quiet" data-act="cancel">Cancel</button>` +
    `<button class="act quiet" data-act="save"${ok ? "" : " disabled"}>Save</button>` +
    `<button class="act primary" data-act="start-team"${ok ? "" : " disabled"}>Start team</button></footer></section>`;
}

// ---------- render ----------
function render() {
  const a = document.activeElement;
  const kind = a?.closest?.(".compose") && ["#t-name", "#t-goal", "#t-repo", ".role-in", ".instr"].find((k) => a.matches(k));
  const focus = kind ? { sel: kind.startsWith("#") ? kind : `${kind}[data-i="${a.dataset.i}"]`, pos: a.selectionStart } : null;
  const shown = state.teams.filter((t) => !t.archived || ui.archived);
  if (!shown.some((t) => t.id === ui.team)) ui.team = state.teams.find((t) => !t.archived)?.id ?? null;
  const tm = team();
  root.innerHTML = state.error
    ? `<p class="empty limit">${esc(state.error)}</p>`
    : state.loading
      ? `<p class="empty">Reading…</p>`
      : !state.teams.length
        ? `<div class="no-teams"><p>No teams</p><button class="act primary" data-act="new">New team</button></div>`
        : tabsHtml() + (tm ? boardHtml(tm) : "");
  sheet.innerHTML = ui.compose ? `<div class="scrim">${composerHtml()}</div>` : "";
  document.querySelector(".app").inert = Boolean(ui.compose);
  for (const log of root.querySelectorAll(".log.chat, .feed .flow")) log.scrollTop = log.scrollHeight;
  const el = focus && sheet.querySelector(focus.sel);
  if (el) { el.focus(); if (focus.pos != null) el.setSelectionRange(focus.pos, focus.pos); }
  (sheet.querySelector(".menu button") || root.querySelector(".menu button"))?.focus();
}

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

/** Saves the composer. The store judges the repo and the composition; its refusal is what the person reads. */
async function save(startAfter) {
  const c = ui.compose;
  const ids = slugs(c.slots);
  const body = {
    name: c.name.trim(),
    goal: c.goal.trim(),
    repo: c.repo.trim(),
    slots: c.slots.map((s, i) => ({
      id: ids[i], role: s.role.trim() || ids[i], head: s.head,
      model: s.model || undefined, account: s.account || undefined,
      lead: s.lead, instructions: s.instructions?.trim() || undefined,
    })),
  };
  const answer = c.id ? await API.put(`/api/teams/${c.id}`, { ...body, id: c.id }) : await createTeam(body);
  if (!answer.ok) {
    c.error = refusalOf(answer, "The team was not saved");
    render();
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

/** A create needs an Idempotency-Key, so a retried create cannot make a second team, and api.js sends no custom
 *  header: this one call is made here, with the key minted once per create. */
async function createTeam(body) {
  const mgmt = localStorage.getItem("splice-console-key") || "";
  const once = `console-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  try {
    const res = await fetch("/api/teams", {
      method: "PUT",
      headers: { Authorization: `Bearer ${mgmt}`, "Content-Type": "application/json", "Idempotency-Key": once },
      body: JSON.stringify(body),
    });
    const text = await res.text();
    let parsed = null;
    try { parsed = text ? JSON.parse(text) : null; } catch { parsed = null; }
    return { ok: res.ok, status: res.status, body: parsed };
  } catch {
    return { ok: false, status: 0, body: null };
  }
}

/** Starting a member opens a Claude Code session on its command, bound to the slot. */
async function startSlot(tm, slot) {
  const key = `${tm.id}/${slot.id}`;
  ui.failed.delete(key);
  ui.starting.add(key);
  render();
  const answer = await API.post(`/api/teams/${tm.id}/slots/${slot.id}/start`);
  ui.starting.delete(key);
  if (!answer.ok) {
    const screen = answer.body?.screen;
    ui.failed.set(key, [refusalOf(answer, "The member did not start"), screen].filter(Boolean).join("\n"));
  }
  await read();
}

/** Answering presses the option's own number in the member's terminal; a refusal stays on its card. */
async function answerSlot(tm, slot, choice) {
  const key = `${tm.id}/${slot.id}`;
  ui.failed.delete(key);
  const answered = await API.post(`/api/teams/${tm.id}/slots/${slot.id}/answer`, { choice });
  if (!answered.ok) ui.failed.set(key, refusalOf(answered, "The answer did not reach the member"));
  await read();
}

async function act(answer, whenRefused) {
  if (!answer.ok) {
    state.error = refusalOf(answer, whenRefused);
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
  else if (el.id === "t-goal") c.goal = el.value;
  else if (el.id === "t-repo") { c.repo = el.value; return; }
  else if (el.matches(".role-in")) c.slots[+el.dataset.i].role = el.value;
  else if (el.matches(".instr")) { c.slots[+el.dataset.i].instructions = el.value; return; }
  const ok = Boolean(ready(c));
  for (const b of sheet.querySelectorAll('[data-act="save"], [data-act="start-team"]')) b.disabled = !ok;
});

document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (ui.menu || ui.pick) { ui.menu = null; ui.pick = null; render(); }
  else if (ui.compose) { ui.compose = null; ui.pick = null; render(); }
});

document.addEventListener("click", async (e) => {
  const b = e.target.closest("[data-act]");
  if (!b) {
    if (ui.menu || ui.pick) { ui.menu = null; ui.pick = null; render(); }
    return;
  }
  const tm = team();
  const c = ui.compose;
  const i = +b.dataset.i;
  const slot = tm && b.dataset.s ? slotOf(tm, b.dataset.s) : null;
  switch (b.dataset.act) {
    case "theme": {
      const h = document.documentElement;
      h.dataset.theme = h.dataset.theme === "night" ? "day" : "night";
      break;
    }
    case "read": await read(); break;
    case "team": Object.assign(ui, { team: b.dataset.t, compose: null, pick: null, menu: null, day: 0 }); render(); await readTeam(); break;
    case "archived": ui.archived = !ui.archived; render(); break;
    case "new": Object.assign(ui, { compose: composeFrom(null), menu: null, pick: null }); render(); sheet.querySelector("#t-name")?.focus(); break;
    case "edit": Object.assign(ui, { compose: composeFrom(tm), menu: null }); render(); sheet.querySelector("#t-name")?.focus(); break;
    case "more": ui.menu = ui.menu === "more" ? null : "more"; render(); break;
    case "archive": ui.menu = null; await act(await API.post(`/api/teams/${tm.id}/archive`), "The team was not archived"); break;
    case "day": ui.day = Math.max(0, Math.min(6, ui.day + +b.dataset.d)); render(); await readTeam(); break;
    case "start": await startSlot(tm, slot); break;
    case "use": { const k = `${tm.id}/${slot.id}`; ui.menu = ui.menu === k ? null : k; render(); break; }
    case "bind":
      ui.menu = null;
      await act(await API.put(`/api/teams/${tm.id}/sessions`, { bindings: { [slot.id]: b.dataset.to } }), "The session was not bound");
      break;
    case "stop": await act(await API.post(`/api/teams/${tm.id}/slots/${slot.id}/stop`), "The turn was not stopped"); break;
    case "answer": await answerSlot(tm, slot, Number(b.dataset.i)); break;
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
