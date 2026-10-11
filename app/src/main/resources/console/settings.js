// Settings, built from the finished drawing (Splice-Animator/design/console at d3338fa). A rail of the jobs a person
// comes with, and the open job beside it. Every value on this page is read from the daemon (GET /api/config through
// knobs.js, GET /api/history, GET /api/kept/*) and every change goes back to it; a figure splice does not keep is not
// drawn, and a store with no route to count or delete it says so on its own row instead of offering a Delete that
// would do nothing. Jobs whose settings the daemon reads only at start, and the jobs that need their own routes
// (the week's turns, MCP servers, the version), join as each one is true end to end.
"use strict";

const { fmt, KNOB, st: ks, val, word, rowHtml, ctlHtml, saidHtml, cmdChip, needsRestart } = KNOBS;
const SV = {
  open: G('<path d="M7 17L17 7M9 7h8v8"/>'),
  busy: G('<path d="M4 6h16M4 12h16M4 18h9"/><circle cx="18" cy="18" r="1.6"/>'),
  silent: G('<circle cx="12" cy="13" r="7"/><path d="M12 9v4l2.5 2M9 3h6"/>'),
  plan: G('<path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-5"/>'),
  mcp: G('<path d="M9 3v5M15 3v5M7 8h10v4a5 5 0 0 1-10 0z"/><path d="M12 17v4"/>'),
  data: G('<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>'),
};
const MiB = 1048576;
const DAY = 864e5;

// ---------- the jobs ----------
// Only the jobs people come to it with (Marlin, Oct 10): a job draws the settings the daemon takes live, in knobs.js's
// order, and a job none of whose settings is live is not on the rail until one is (Reasoning, today). The names are the
// mock's, with no description line under them (fin).
const TOPICS = [
  { id: "silent", name: "Silent models", also: "stall stuck wedged hang quiet resume timeout" },
  { id: "busy", name: "Many agents at once", keys: ["maxInflight", "maxQueued"], also: "overloaded busy queue waiting 429 failed errors retry" },
  { id: "plan", name: "Plan limits", also: "quota usage warning budget spend limit" },
  { id: "mcp", name: "MCP servers", also: "tools servers model context protocol" },
  { id: "data", name: "Your data", also: "privacy prompts saved keep delete disk history logs" },
];
const TOPIC = Object.fromEntries(TOPICS.map((x) => [x.id, x]));
const topicOf = (key) => KNOB[key].home || TOPICS.find((x) => x.keys && x.keys.includes(key)).id;
/** The settings this page offers: those with a job here that the daemon names as taken without a restart (GET
 *  /api/config's restart_required_keys, Knob.kt), so a knob that turns live joins and one that turns boot-only leaves
 *  with the daemon's own answer, never a list kept here. */
const liveKnobs = () => Object.values(KNOB)
  .filter((k) => !needsRestart(k.key) && shownKnob(k.key) && (k.home ? TOPIC[k.home] : TOPICS.some((x) => x.keys && x.keys.includes(k.key))));
// the poll interval means nothing while reading plan limits is off, and a value that does nothing would read as working (fin)
const shownKnob = (key) => key !== "quotaPollIntervalMs" || val("quotaPoll") !== "off";
/** The jobs on the rail: Your data always, the others once one of their settings is live. */
const jobs = () => TOPICS.filter((x) => x.id === "data" || liveKnobs().some((k) => topicOf(k.key) === x.id));
/** A job's live settings, in the order knobs.js lists them (the first two of Many agents at once are its own section). */
const keysOf = (id, skip = []) => liveKnobs().filter((k) => topicOf(k.key) === id && !skip.includes(k.key)).map((k) => k.key);

const ui = { saving: null, held: {}, open: "busy", shown: false, q: "", armed: null, bad: {}, ask: null, heads: [], hist: null, kept: {}, err: null };

// ---------- what the daemon keeps ----------
// One read per store; a store whose route answers with an error is drawn as unreadable, never as empty.
async function readKept() {
  const [hist, turns, edges, labels, heads, models] = await Promise.all([
    API.get("/api/history"), API.get("/api/kept/turns"), API.get("/api/kept/edges"), API.get("/api/kept/labels"), API.get("/api/heads"), API.get("/api/models"),
  ]);
  ui.hist = hist.ok ? hist.body : null;
  ui.kept = { turns: turns.ok ? turns.body : null, edges: edges.ok ? edges.body : null, labels: labels.ok ? labels.body : null };
  ui.heads = (heads.body?.heads || []).map((h) => h.key);
  ks.cmds = ui.heads;
  ks.providerOf = Object.fromEntries((models.body?.heads || []).map((r) => [r.head, r.provider]));
  // the bodies each tapped command holds in memory now, counted and never read out (GET /api/heads/{head}/wire)
  const tapped = ui.heads.filter((h) => tapOf(h) > 0);
  const wires = await Promise.all(tapped.map((h) => API.get(`/api/heads/${encodeURIComponent(h)}/wire`)));
  ui.held = Object.fromEntries(tapped.map((h, i) => [h, wires[i].ok ? (wires[i].body?.records ?? []).length : null]));
  const traces = await Promise.all(ui.heads.map((h) => API.get(`/api/heads/${encodeURIComponent(h)}/trace/kept`)));
  ui.kept.trace = traces.every((t) => t.ok) && traces.length ? traces.map((t) => t.body) : null;
}
const mbWord = (b) => { const mb = b / MiB; return mb >= 1000 ? `${+(mb / 1024).toFixed(1)} GB` : mb >= 1 ? `${+mb.toFixed(1)} MB` : b > 0 ? `${Math.max(1, Math.round(b / 1024))} KB` : "0 B"; };
const dateWord = (d) => d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
const atWord = (ms) => { const d = new Date(ms); return d.getHours() || d.getMinutes() ? `${dateWord(d)}, ${d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })}` : dateWord(d); };

// ---------- search ----------
const norm = (s) => String(s).toLowerCase();
const words = () => ui.q.trim().split(/\s+/).filter(Boolean);
const hits = (text) => { const w = words(); return w.length > 0 && w.every((x) => norm(text).includes(norm(x))); };
const jobHay = (id) => `${TOPIC[id].name} ${TOPIC[id].also}`;
const knobHay = (k) => [k.label, k.key, k.also || "", k.home ? "" : jobHay(topicOf(k.key))].join(" ");
function hl(s) {
  const w = words().map((x) => x.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  return w.length ? esc(s).replace(new RegExp(`(${w.join("|")})`, "gi"), "<mark>$1</mark>") : esc(s);
}
function found(id) {
  if (!words().length) return [];
  const f = liveKnobs().filter((k) => topicOf(k.key) === id && hits(knobHay(k))).map((k) => k.label);
  if (id === "data") f.push(...DATA_ROWS.filter((r) => hits(r.name)).map((r) => r.name));
  if (!f.length && hits(jobHay(id))) f.push(TOPIC[id].name);
  return [...new Set(f)];
}
const lit = (text) => (words().length && hits(text) ? " lit" : "");
const form = (keys) => `<div class="form">${keys.filter((key) => !needsRestart(key) && shownKnob(key)).map((key) => rowHtml(KNOB[key], { hl, lit: lit(knobHay(KNOB[key])) })).join("")}</div>`;

// the per-command settings splice reads at start and the page only shows (restart-only, set in [heads.KEY.overrides])
const tapOf = (head) => Number(ks.cfg?.layers?.perHead?.[head]?.wireTap) || 0;
const traceOn = (head) => ks.cfg?.layers?.perHead?.[head]?.trace !== false;

// ---------- Your data ----------
// Each store, what it holds, how long, and its Delete now. A store with no route to count or delete it says so.
const DATA_ROWS = [
  { id: "trace", g: "text", name: "Prompts and answers", unit: "requests", yours: true, door: ["requests.html", "Requests"] },
  { id: "originals", g: "text", name: "Transcript copies", noRoute: true, yours: true, always: true },
  { id: "recordings", g: "text", name: "Compaction summaries", noRoute: true, always: true },
  { id: "journals", g: "text", name: "Code mode work", noRoute: true, always: true },
  { id: "reasoning", g: "text", name: "Reasoning between requests", noRoute: true },
  { id: "hist", g: "records", name: "Usage and request history", unit: "requests", door: ["usage.html", "Usage"] },
  { id: "edges", g: "records", name: "Who messaged whom", unit: "messages", door: ["teams.html", "Teams"] },
  { id: "labels", g: "records", name: "What each agent is doing", unit: "lines", door: ["teams.html", "Teams"] },
];
const EP = { trace: null, hist: "/api/kept/turns", edges: "/api/kept/edges", labels: "/api/kept/labels" };
// what a store holds now: rows, bytes, since when. null when its route would not answer.
function heldOf(r) {
  if (r.id === "hist") return ui.hist && { n: ui.hist.held.turns, bytes: ui.hist.held.bytes, since: ui.hist.held.oldest_epoch_ms };
  if (r.id === "trace") {
    const t = ui.kept.trace;
    return t && { n: t.reduce((s, x) => s + x.records, 0), bytes: t.reduce((s, x) => s + x.bytes, 0), since: null };
  }
  const k = ui.kept[r.id];
  return k && { n: k.rows, bytes: k.bytes, since: k.oldest ? Date.parse(`${k.oldest}T00:00:00`) : null };
}
const delBtn = (id, n, unit) => (!n ? "" : ui.armed === id
  ? `<span class="armed"><button class="act danger small" data-act="delete" data-id="${id}">Delete ${fmt(n)} ${unit}</button><button class="act quiet small" data-act="disarm">Cancel</button></span>`
  : `<button class="act quiet small" data-act="arm" data-id="${id}">Delete now</button>`);
const ALWAYS = `<span class="fixed quiet">Always saved</span>`; // a store whose daemon has no off switch
const YOURS = `<span class="yours">Your prompts</span>`;
const drow = ({ cls = "", name, hay = name, yours, door, amount, keep = "", sw = "", del = "", more = "" }) =>
  `<div class="drow${cls}${lit(hay)}"><div class="dname"><span class="nm">${hl(name)}${yours ? YOURS : ""}</span>${door ? `<a class="link" href="${door[0]}">${SV.open}${door[1]}</a>` : ""}</div>`
  + `<div class="amt">${amount}</div><div class="keep">${keep}</div><div class="dsw">${sw}</div><div class="ddel">${del}</div>${more}</div>`;
const amountOf = (x, unit) => (x.n ? `<b>${fmt(x.n)}</b> ${unit}<span class="mb">${mbWord(x.bytes)}</span>${x.since ? `<span class="mb">Since ${atWord(x.since)}</span>` : ""}` : `<span class="none">Nothing saved</span>`);
const unreadable = `<span class="none">splice did not answer</span>`;

// The usage and request history. A shorter window waits for the yes in a band under the row: the held days as one bar,
// the ones that go hatched, the first day kept as a tick. The yes sends the moment it showed (PUT /api/history).
function histBar(h) {
  const total = h.held.bytes || 1, at = (h.cut.bytes / total) * 100, w = h.cut.cutoff_epoch_ms;
  const days = h.days.map((d) => `<i style="--mb:${d.bytes}"></i>`).join("");
  const tick = `<span class="hcut" style="--at:${at}%">${h.window.nothing ? "" : `<em>${atWord(w)}</em>`}</span>`;
  return `<div class="hbar"><span class="hend">${atWord(h.held.oldest_epoch_ms)}</span><div class="hdays" role="img" aria-label="${mbWord(h.cut.bytes)} goes; kept from ${h.window.nothing ? "today" : atWord(w)}">`
    + `${days}<span class="hgone" style="--w:${at}%"></span><b class="hfreed" style="--w:${at}%"><span>${mbWord(h.cut.bytes)}</span></b>${tick}</div><span class="hend">Today</span></div>`;
}
function histRow(r) {
  if (!ui.hist) return drow({ name: r.name, door: r.door, amount: unreadable });
  const a = ui.ask, h = a && a.h ? a.h : ui.hist, x = heldOf(r), id = "del:hist";
  const month = ui.hist.rate_bytes_per_month;
  const rate = a || h.window.nothing || !month ? "" : `<span class="hrate">About ${mbWord(month)} a month</span>`;
  return drow({
    name: r.name, hay: `${r.name} ${KNOB.historyRetentionDays.also}`, door: r.door,
    sw: ALWAYS, // the daemon has no switch for turn statistics: only how far back they go
    amount: amountOf(x, r.unit),
    keep: `${ctlHtml(KNOB.historyRetentionDays, a ? a.v : val("historyRetentionDays"))}${saidHtml("historyRetentionDays")}${rate}`,
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
    more: a && a.h ? `<div class="dmore hask">${histBar(a.h)}<div class="hacts"><button class="act danger small" data-act="shorten">Delete ${fmt(a.h.cut.turns)} requests</button><button class="act quiet small" data-act="unask">Cancel</button></div></div>`
      : a ? `<div class="dmore hask"><div class="hbar reading"><span class="hend"></span><div class="hdays"></div><span class="hend">Today</span></div><div class="hacts"><button class="act danger small" disabled>Delete</button><button class="act quiet small" data-act="unask">Cancel</button></div></div>` : "",
  });
}
function dataRow(r) {
  if (r.id === "hist") return histRow(r);
  if (r.noRoute) { // no route counts or deletes it: say so on the row, never an empty Delete
    return drow({ name: r.name, yours: r.yours, amount: `<span class="none">Not counted</span>`, keep: "", sw: r.always ? ALWAYS : "" }); // a store the daemon has no off switch for
  }
  const x = heldOf(r), id = `del:${r.id}`;
  if (!x) return drow({ name: r.name, yours: r.yours, door: r.door, amount: unreadable });
  const on = r.id === "trace" ? ui.heads.filter(traceOn) : null;
  const keep = r.id === "edges" && ui.hist ? `<span class="fixed quiet">${esc(ui.hist.window.forever ? "Forever" : ui.hist.window.nothing ? "Today only" : `${ui.hist.window.days} days`)}</span>`
    : r.id === "labels" ? `<span class="fixed quiet">Today and yesterday</span>` : "";
  return drow({
    name: r.name, yours: r.yours, door: r.door, amount: amountOf(x, r.unit), keep,
    sw: on ? `<button class="count" data-act="saving" aria-expanded="${ui.saving === r.id}">${on.length} of ${ui.heads.length} commands${ICON.caret}</button>` : "",
    more: on && ui.saving === r.id ? `<div class="dmore"><div class="cmdset">${ui.heads.map((h) => `${cmdChip(h)}<span class="fixed quiet">${traceOn(h) ? "Saved" : "Not saved"}</span>`).join("")}</div></div>` : "",
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
  });
}
function dataHtml() {
  const read = drow({ name: "Read your Claude Code conversations", door: ["sessions.html", "Sessions"], amount: `<span class="none">Nothing saved</span>`, keep: saidHtml("transcriptView"), sw: ctlHtml(KNOB.transcriptView, val("transcriptView")) });
  // what each tapped command holds in memory right now: counted, never shown, and no Delete since the daemon has none
  const taps = ui.heads.filter((h) => tapOf(h) > 0), heldN = taps.reduce((n, h) => n + (ui.held[h] ?? 0), 0);
  const memory = drow({
    cls: taps.length ? "" : " off", name: "Last requests, in memory", yours: true,
    amount: taps.some((h) => ui.held[h] === null) ? unreadable : heldN ? `<b>${fmt(heldN)}</b> ${heldN === 1 ? "request" : "requests"}` : `<span class="none">Nothing held</span>`,
    keep: `<span class="taps">${taps.map((h) => `<span class="tap">${cmdChip(h)}<span class="fixed">Last ${tapOf(h)}</span></span>`).join("")}</span>`,
    sw: `<span class="count">${taps.length} of ${ui.heads.length} commands</span>`,
  });
  const bytes = (g) => DATA_ROWS.filter((r) => r.g === g).reduce((s, r) => s + (heldOf(r)?.bytes ?? 0), 0);
  const head = (name, g) => `<div class="dgroup"><h3>${name}</h3><span class="hn">${mbWord(bytes(g))}${DATA_ROWS.some((r) => r.g === g && r.noRoute) ? " counted" : ""}</span></div>`;
  const dhead = `<div class="drow dhead"><span></span><span></span><span>Keep for</span><span>Save</span><span></span></div>`;
  const rows = (g) => DATA_ROWS.filter((r) => r.g === g).map(dataRow).join("");
  return `<div class="dlist">${read}${head("Conversation text", "text")}${dhead}${rows("text")}${memory}${head("Records", "records")}${rows("records")}</div>`;
}

const PANES = {
  busy: () => `<section class="sub"><h3>splice's limit</h3>${form(["maxInflight", "maxQueued"])}</section>`
    + (keysOf("busy", ["maxInflight", "maxQueued"]).length ? `<section class="sub">${form(keysOf("busy", ["maxInflight", "maxQueued"]))}</section>` : ""),
  silent: () => `<section class="sub">${form(keysOf("silent"))}</section>`,
  plan: () => `<section class="sub">${form(keysOf("plan"))}</section>`,
  mcp: () => `<section class="sub">${form(keysOf("mcp"))}</section>`,
  data: dataHtml,
};

// ---------- the rail ----------
function summary(id) {
  if (id === "silent") return [`Asks after ${word(KNOB.firstByteTimeoutMs, val("firstByteTimeoutMs"))}`];
  if (id === "plan") return [`Warns at ${word(KNOB.usageWarnPct, val("usageWarnPct"))}`];
  if (id === "mcp") return [`${word(KNOB.mcpMaxServers, val("mcpMaxServers"))} at most`];
  if (id === "busy") return [`${word(KNOB.maxInflight, val("maxInflight"))} at once`, `${word(KNOB.maxQueued, val("maxQueued"))} waiting`];
  const h = DATA_ROWS.filter((r) => !r.noRoute).map(heldOf), tag = DATA_ROWS.some((r) => r.noRoute) ? "counted" : "kept"; // a total that leaves a store out says counted
  return h.some((x) => x === null) ? ["Some stores did not answer"] : [`${mbWord(h.reduce((s, x) => s + x.bytes, 0))} ${tag}`];
}
function cardHtml(x) {
  const f = found(x.id), s = summary(x.id);
  return `<article class="card job" data-open="${x.id}" aria-current="${ui.open === x.id}">`
    + `<span class="lamp idle sock" aria-hidden="true">${SV[x.id]}</span>`
    + `<div class="top"><span class="jname">${hl(x.name)}</span></div>`
    + `<div class="meta">${s.map((w) => `<span>${esc(w)}</span>`).join("")}</div>`
    + (f.length ? `<div class="hits">${f.slice(0, 4).map((w) => `<span>${hl(w)}</span>`).join("")}${f.length > 4 ? `<span class="more">${f.length - 4} more</span>` : ""}</div>` : "")
    + `</article>`;
}

// ---------- the page ----------
const root = document.getElementById("st");
function render() {
  tick(); // times read in the person's present
  if (ui.err) { root.innerHTML = `<div class="list rail empty"><div class="nomatch"><span class="state limit">${esc(ui.err)}</span><button class="act quiet" data-act="again">Read again</button></div></div>`; return; }
  const shown = words().length ? jobs().filter((x) => found(x.id).length) : jobs();
  if (shown.length && !shown.some((x) => x.id === ui.open)) ui.open = shown[0].id;
  const x = TOPIC[ui.open], s = summary(x.id);
  const list = shown.length ? `<div class="list rail">${shown.map(cardHtml).join("")}</div>`
    : `<div class="list rail empty"><div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clear">Clear</button></div></div>`;
  const pane = shown.length ? `<div class="pane"><header><span class="lamp idle sock" aria-hidden="true">${SV[x.id]}</span><div class="who"><div class="top"><span class="name">${esc(x.name)}</span></div>`
    + `<div class="meta">${s.map((w) => `<span>${esc(w)}</span>`).join("")}</div></div>`
    + `<div class="acts"><button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button></div></header><div class="body kn">${PANES[x.id]()}</div></div>` : "";
  const was = root.querySelector(".pane .body")?.scrollTop ?? 0;
  root.innerHTML = `<div class="jobs${ui.shown ? " open" : ""}">${list}${pane}</div>`;
  if (was) { const b = root.querySelector(".pane .body"); if (b) b.scrollTop = was; }
  KNOBS.focusCustom(root);
  root.querySelector(".pane .menu")?.scrollIntoView({ block: "nearest" });
}

// A shorter history window that would delete turns waits for the yes; the daemon says what the cut would take.
ks.onAsk = (id, days) => {
  ui.ask = { v: days, h: null };
  API.get(`/api/history?days=${days === null ? "forever" : days}`).then(async (r) => {
    if (!ui.ask || ui.ask.v !== days) return;
    if (!r.ok || !r.body) { ui.ask = null; ks.bad[id] = "Not saved"; render(); return; }
    if (!r.body.cut || !r.body.cut.turns) { ui.ask = null; await KNOBS.patch(id, days, true); await refresh(); return; } // nothing held goes: it applies at once
    ui.ask.h = r.body; render();
  });
  delete ks.said[id];
  return true;
};
async function shorten() {
  const a = ui.ask; if (!a || !a.h) return;
  ui.ask = null;
  const r = await API.put("/api/history", { days: a.v === null ? "forever" : a.v, delete_before_epoch_ms: a.h.cut.cutoff_epoch_ms });
  if (r.ok) { ks.said.historyRetentionDays = "Applied"; delete ks.bad.historyRetentionDays; } else { ks.bad.historyRetentionDays = "Not saved"; delete ks.said.historyRetentionDays; }
  await refresh();
}
async function remove(id) {
  const key = id.slice(4), bad = () => { ui.bad[id] = "Not deleted"; };
  const paths = key === "trace" ? ui.heads.map((h) => `/api/heads/${encodeURIComponent(h)}/trace/kept`) : [EP[key]];
  const answers = await Promise.all(paths.map((p) => API.del(p)));
  if (answers.some((a) => !a.ok)) bad(); else delete ui.bad[id];
  await refresh();
}
async function refresh() {
  const err = await KNOBS.load();
  if (err) ui.err = err; else { ui.err = null; await readKept(); }
  render();
}

KNOBS.wire(root, render);
document.getElementById("q").addEventListener("input", (e) => { ui.q = e.target.value; ks.menu = null; ks.custom = null; render(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && (ui.armed || ui.ask)) { ui.armed = null; ui.ask = null; render(); } });
document.addEventListener("click", (e) => {
  if (KNOBS.click(e, render)) { render(); return; }
  const t = e.target.closest("[data-act], [data-open]");
  if (ui.armed && !e.target.closest("[data-act=delete], [data-act=arm]")) { ui.armed = null; if (!t) { render(); return; } }
  if (!t) return;
  const d = t.dataset;
  if (d.open && !d.act) { ui.open = d.open; ui.shown = true; ui.armed = null; ks.custom = null; render(); return; }
  switch (d.act) {
    case "theme": document.documentElement.dataset.theme = document.documentElement.dataset.theme === "day" ? "night" : "day"; return;
    case "close": ui.shown = false; break;
    case "clear": ui.q = ""; document.getElementById("q").value = ""; break;
    case "again": refresh(); return;
    case "saving": ui.saving = ui.saving === "trace" ? null : "trace"; break;
    case "arm": ui.armed = d.id; delete ui.bad[d.id]; break;
    case "disarm": ui.armed = null; break;
    case "delete": ui.armed = null; render(); remove(d.id); return;
    case "shorten": shorten(); return;
    case "unask": ui.ask = null; break;
    default: return;
  }
  render();
});
if (TOPIC[location.hash.slice(1)]) { ui.open = location.hash.slice(1); ui.shown = true; }
refresh();
