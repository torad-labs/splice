// Settings, built from the finished drawing (Splice-Animator/design/console at d3338fa). A rail of the jobs a person
// comes with, and the open job beside it. Every value on this page is read from the daemon (GET /api/config through
// knobs.js, GET /api/history, GET /api/kept/*) and every change goes back to it; a figure splice does not keep is not
// drawn, and a store with no route to count or delete it says so on its own row instead of offering a Delete that
// would do nothing. Jobs whose settings the daemon reads only at start, and the jobs that need their own routes
// (the week's turns, MCP servers, the version), join as each one is true end to end.
"use strict";

const { fmt, KNOB, st: ks, val, word, rowHtml, ctlHtml, saidHtml, cmdChip, needsRestart } = KNOBS;
const SV = {
  file: G('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>'),
  open: G('<path d="M7 17L17 7M9 7h8v8"/>'),
  busy: G('<path d="M4 6h16M4 12h16M4 18h9"/><circle cx="18" cy="18" r="1.6"/>'),
  silent: G('<circle cx="12" cy="13" r="7"/><path d="M12 9v4l2.5 2M9 3h6"/>'),
  reasoning: G('<path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.4 1 2.5h6c0-1.1.3-1.8 1-2.5A6 6 0 0 0 12 3z"/>'),
  plan: G('<path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-5"/>'),
  mcp: G('<path d="M9 3v5M15 3v5M7 8h10v4a5 5 0 0 1-10 0z"/><path d="M12 17v4"/>'),
  projects: G('<path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>'),
  plus: G('<path d="M12 5v14M5 12h14"/>'),  // the kit's own PLUS lives inside its closure
  version: G('<path d="M12 19V5M6 11l6-6 6 6"/>'),
  data: G('<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>'),
};
const MiB = 1048576;
const DAY = 864e5;

// ---------- the jobs ----------
// Only the jobs people come to it with (Marlin, Oct 10): a job draws the settings the daemon takes live, in knobs.js's
// order, and a job none of whose settings is live is not on the rail until one is. The names are the
// mock's, with no description line under them (fin).
const TOPICS = [
  { id: "silent", name: "Silent models", also: "stall stuck wedged hang quiet resume timeout" },
  { id: "busy", name: "Many agents at once", keys: ["maxInflight", "maxQueued"], also: "overloaded busy queue waiting 429 failed errors retry" },
  { id: "reasoning", name: "Reasoning", also: "thinking summary show hide progress line replay" },
  { id: "plan", name: "Plan limits", also: "quota usage warning budget spend limit" },
  { id: "mcp", name: "MCP servers", also: "tools servers model context protocol" },
  { id: "projects", name: "Projects", also: "folder folders group grouping workspace monorepo sessions by project" },
  { id: "data", name: "Your data", also: "privacy prompts saved keep delete disk history logs" },
  { id: "version", name: "Version", also: "update upgrade new release roll back restart" },
];
const TOPIC = Object.fromEntries(TOPICS.map((x) => [x.id, x]));
const topicOf = (key) => KNOB[key].home || KNOB[key].adv || TOPICS.find((x) => x.keys && x.keys.includes(key)).id;
/** The settings this page offers: every one with a job here. One the running splice reads only at start (GET
 *  each knob's disposition in /api/config, Knob.kt) stays editable and reads "Applies on restart" (v0.4.0 section 9,
 *  Resolved, Marcos's decision, which the Oct 10 "every knob drawn applies live" reading gave way to). */
const liveKnobs = () => (KNOBS.others(), Object.values(KNOB))
  .filter((k) => shownKnob(k.key) && (k.home || k.adv ? TOPIC[k.home || k.adv] : TOPICS.some((x) => x.keys && x.keys.includes(k.key))));
// the poll interval means nothing while reading plan limits is off, and a value that does nothing would read as working (fin)
const shownKnob = (key) => (key !== "quotaPollIntervalMs" || val("quotaPoll") !== "off") && (key !== "stallReanchorMs" || KNOB.stallReanchorMs.only.length > 0);
/** The jobs on the rail: Your data always, the others once one of their settings is live. */
const jobs = () => TOPICS.filter((x) => x.id === "data" || x.id === "version" || liveKnobs().some((k) => topicOf(k.key) === x.id));
/** A job's live settings, in the order knobs.js lists them (the first two of Many agents at once are its own section). */
const keysOf = (id, skip = []) => liveKnobs().filter((k) => topicOf(k.key) === id && !k.adv && !skip.includes(k.key)).map((k) => k.key);
// A job's Advanced disclosure (v0.4.0 section 9, Resolved: advanced settings sit behind a per-section "Advanced"): the
// settings a person rarely needs, every knob without a row of its own among them (knobs.js ADV_HOME), closed on every
// load until pressed, and open while a search finds one of them. Every section ends in one.
const advKeys = (id) => liveKnobs().filter((k) => k.adv === id).map((k) => k.key);
function advancedHtml(id) {
  const keys = advKeys(id);
  if (!keys.length) return "";
  const open = ui.adv[id] || keys.some((key) => hits([KNOB[key].label, key, KNOB[key].also || ""].join(" ")));
  return `<section class="sub adv"><button class="act quiet advbtn" data-act="adv" data-id="${id}" aria-expanded="${open}">Advanced${ICON.caret}</button>${open ? blocks(keys) : ""}</section>`;
}

// A figure not read yet is undefined and draws "Reading"; one asked for and not answered is null and draws "Not read", its reason warned to the console.
const ui = { adv: {}, said: {}, capture: {}, week: {}, ver: undefined, run: null, headRows: [], saving: null, held: {}, open: "busy", shown: false, q: "", armed: null, bad: {}, ask: null, heads: [], hist: undefined, stores: undefined, kept: {}, peaks: undefined, mcp: undefined, mtest: {}, mtestAt: {}, topo: undefined, mmem: undefined, shareSaving: false, shareSaid: null, exSaid: {}, accts: undefined, projects: undefined, addPath: null, checking: false, checkSaid: null, restarting: false, restartSaid: null, restartWait: 0, err: null };
const READING = `<span class="none">Reading</span>`;
let drawLater = 0;
/** Many answers arrive in a burst; one drawing a moment later takes them all. */
/** The per-command reads and the week's counts are over a hundred requests, and a browser sends six at a time to one address: left to
 *  queue they hold every other call (a setting being saved, another page) behind them. Four at a time leaves the rest of the six free. */
const BULK = 4;
const bulk = { running: 0, waiting: [] };
const bulkGet = (path) => new Promise((resolve) => {
  const start = () => {
    bulk.running += 1;
    API.get(path).then(resolve).finally(() => { bulk.running -= 1; bulk.waiting.shift()?.(); });
  };
  if (bulk.running < BULK) start(); else bulk.waiting.push(start);
});
const later = () => { if (!drawLater) drawLater = setTimeout(() => { drawLater = 0; render(); }, 60); };

// ---------- what the daemon keeps ----------
// One read per store; a store whose route answers with an error is drawn as unreadable, never as empty.
/** Each store, each command's own read and each week's count start together and draw as they answer, so the cards are
 *  on the page at once and a slow store only keeps its own value waiting. */
function readKept() {
  const get = async (path, then) => { const r = await API.get(path); if (!r.ok) whyNot(path, r); then(r); later(); };
  get("/api/history", (r) => { ui.hist = r.ok ? r.body : null; });
  get("/api/kept/edges", (r) => { ui.kept.edges = r.ok ? r.body : null; });
  get("/api/kept/labels", (r) => { ui.kept.labels = r.ok ? r.body : null; });
  get("/api/kept/stores", (r) => { ui.stores = r.ok ? r.body.stores : null; });
  // the MCP host's own snapshot. The route is mounted only while splice hosts MCP (ControlServer.kt), so a 404 here is
  // the honest answer "nothing is hosted", not a failure: it is drawn as that sentence and never as an unread figure.
  get("/api/mcp", (r) => { ui.mcp = r.ok ? r.body : r.status === 404 ? false : null; });
  // the sharing choice ([daemon] mcp_share) and what every copy on this machine holds against one copy each
  get("/api/topology", (r) => { ui.topo = r.ok ? r.body : null; });
  get("/api/mcp/memory", (r) => { ui.mmem = r.ok ? r.body : null; nameHolders(); });
  // the plan windows Accounts draws, read here for the highest of each against the warn line these settings set
  get("/api/accounts", (r) => { ui.accts = r.ok ? (r.body?.accounts ?? []) : null; });
  // the projects the saved folders actually make, and whether each folder is still on disk (ProjectsRoutes.kt): the
  // row under each path is the daemon's own answer, never a listing this page takes itself
  get("/api/projects", (r) => { ui.projects = r.ok ? r.body : null; });
  // a rollback already running (started elsewhere) is picked up once the version is known: it names the release it goes back to
  get("/api/upgrade", (r) => { ui.ver = r.ok ? r.body : null; }).then(async () => {
    const upgradeRun = await API.get("/api/upgrade/run");
    if (!ui.run && upgradeRun.ok && upgradeRun.body?.run?.state === "running") { ui.run = runOf(upgradeRun.body.run); pollRun(ui.run.to, ui.run.way); }
  });
  readWeek();
  readHeads();
}
/** The commands and their models: two quick reads the first drawing waits for, because every setting names them. */
async function readBase() {
  const [heads, models] = await Promise.all([API.get("/api/heads"), API.get("/api/models")]);
  ui.heads = (heads.body?.heads || []).map((h) => h.key);
  ui.headRows = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key }));
  // the commands whose provider splice resumes mid-answer report a finite resume tier on their gate
  KNOB.stallReanchorMs.only = (heads.body?.heads || []).filter((h) => typeof h.gate?.stall_reanchor_ms === "number").map((h) => h.key);
  ks.cmds = ui.heads;
  ks.commandOf = Object.fromEntries(ui.headRows.map((h) => [h.key, h.command]));
  ks.providerOf = Object.fromEntries((models.body?.heads || []).map((r) => [r.head, r.provider]));
  // the fold set is ChatGPT models, so only the commands that reach that provider offer theirs
  ks.models = [...new Map((models.body?.heads || []).filter((r) => r.provider === "codex").flatMap((r) => r.models || []).map((m) => [m.id, { id: m.id, label: m.label || m.id }])).values()];
}
/** What each command keeps: the bodies its tap holds in memory (GET /api/heads/{head}/wire), what its trace holds and whether it is saving. */
async function readHeads() {
  const tapped = ui.heads.filter((h) => tapOf(h) > 0);
  const [wires, traces, caps] = await Promise.all([
    Promise.all(tapped.map((h) => bulkGet(`/api/heads/${encodeURIComponent(h)}/wire`))),
    Promise.all(ui.heads.map((h) => bulkGet(`/api/heads/${encodeURIComponent(h)}/trace/kept`))),
    Promise.all(ui.heads.map((h) => bulkGet(`/api/heads/${encodeURIComponent(h)}/capture`))),
  ]);
  wires.forEach((w, i) => { if (!w.ok) whyNot(`/api/heads/${tapped[i]}/wire`, w); });
  traces.forEach((t, i) => { if (!t.ok) whyNot(`/api/heads/${ui.heads[i]}/trace/kept`, t); });
  ui.held = Object.fromEntries(tapped.map((h, i) => [h, wires[i].ok ? (wires[i].body?.records ?? []).length : null]));
  ui.capture = Object.fromEntries(ui.heads.map((h, i) => [h, caps[i].ok && typeof caps[i].body?.enabled === "boolean" ? caps[i].body.enabled : null]));
  ui.kept.trace = traces.every((t) => t.ok) && traces.length ? traces.map((t) => t.body) : null;
  later();
}
const mbWord = (b) => { const mb = b / MiB; return mb >= 1000 ? `${+(mb / 1024).toFixed(1)} GB` : mb >= 1 ? `${+mb.toFixed(1)} MB` : b > 0 ? `${Math.max(1, Math.round(b / 1024))} KB` : "0 B"; };
const dateWord = (d) => d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
const atWord = (ms) => { const d = new Date(ms); return d.getHours() || d.getMinutes() ? `${dateWord(d)}, ${d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })}` : dateWord(d); };

// ---------- search ----------
const norm = (s) => String(s).toLowerCase();
const words = () => ui.q.trim().split(/\s+/).filter(Boolean);
const hits = (text) => { const w = words(); return w.length > 0 && w.every((x) => norm(text).includes(norm(x))); };
const jobHay = (id) => `${TOPIC[id].name} ${TOPIC[id].also}`;
// the line saying what a setting changes is searched with its name: he looks for a setting by what it does more often
// than by what it is called ("budget", "thinking"), and the words of that line are the ones he would type
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
/** The same settings as cards: each one its name, its value and the commands that override it, across a row that the
 *  cards fill however many there are. A label column shared by every setting left the right third of the row empty and
 *  put two sections' controls at different x (splice-lead's walk of the rebuilt pane, Oct 11), so a setting owns its
 *  card; .kin is the card's inner grid, which asks the card how wide it ended up (settings.css). */
const kcard = (inner, cls = "") => `<article class="kcard${cls}"><div class="kin">${inner}</div></article>`;
/** How many cards to a row, so the rows come out even: six settings read as two rows of three, never four and then two
 *  with half a row empty. Four or fewer stand on one row; above that, the fewest rows of at most four, shared evenly. */
const perRow = (n) => (n <= 4 ? Math.max(1, n) : Math.ceil(n / Math.ceil(n / 4)));
const cardRow = (cards, cls = "") => `<div class="kcards${cls}" style="--n:${perRow(cards.length)}">${cards.join("")}</div>`;
// ---------- what a setting shows under its control ----------
// AN ELEMENT, NEVER A SENTENCE, AND ONLY WHERE THE FACT IS ON THE WIRE (Marlin's ruling, Oct 12). Each of these is a
// fact this page already reads: the last plan read, the mark the account bars draw, the providers reporting no window,
// how many servers run now, where the reasoning text lands. Two the build list asked for are NOT here because the
// daemon does not carry them: the waiting line's own text lives in TurnProgressLine.kt and reaches no route, and
// nothing counts fold continuations, so those rows ship with their rename alone.
const seenAt = () => Math.max(0, ...(ui.accts || []).map((x) => x.observed_at_epoch_seconds || 0));
/** The providers whose accounts report no plan window at all: what the 5-hour stand-in covers. */
// Accounts' own rule for a login it shows as Signed out (accounts.js): its last reading is from before, so this pane draws
// none of it, where "100% Highest this week" stood on a signed-out login (splice-usage's walk, Oct 13)
const signedOut = (x) => !x.token_renews && (!x.credential_present || x.auth_exclusion_reason === "credential_missing"
  || (!!x.refusal && !(x.five_hour_used_percent >= 100 || x.seven_day_used_percent >= 100)));
// A login by its command and its name: four logins are named Primary on his install, so a bare name never said which one
// (splice-usage's walks, Oct 13). A name that is the command's own word is said once.
const loginWord = (x) => [...new Set([ks.commandOf?.[(x.heads || [])[0]], x.display_name || x.provider].filter(Boolean))].join(" · ");
// each login reporting no window
const quietProviders = () => [...new Set((ui.accts || [])
  .filter((x) => !signedOut(x) && x.five_hour_used_percent == null && x.seven_day_used_percent == null)
  .map(loginWord).filter(Boolean))];
/** Where the reasoning text lands for the picked option, as three lines of a terminal. */
function reasoningSketch() {
  const where = val("showReasoning");
  const think = `<span class="sk think">${where === "thinking" ? "⏺ Thinking… working out the order" : "⏺ Thinking…"}</span>`;
  // rep, never said: the kit's own .said is the word a saved setting shows, and it carries nowrap, so the line ran
  // 77px past the card at 1440
  const body = where === "text" ? `<span class="sk rep">Working out the order, then the reply.</span>` : "";
  const reply = `<span class="sk">Here is the change.</span>`;
  return `<div class="sketch" aria-hidden="true">${where === "off" ? "" : think}${body}${reply}</div>`;
}
function elOf(key) {
  if (key === "quotaPollIntervalMs") {
    const seen = seenAt();
    return seen ? `<p class="kel">Read ${esc(backWord(new Date(seen * 1000)))}</p>` : "";
  }
  if (key === "usageWarnPct") {
    const warn = Number(val("usageWarnPct")) || 80;
    return `<div class="kel"><div class="pct mark" style="--w:0%;--warn:${warn}%" role="img" aria-label="warns at ${warn}%"><b></b><i></i></div></div>`;
  }
  if (key === "usageWarnTokens5h") {
    const quiet = quietProviders();
    return quiet.length ? `<div class="kel"><span class="kword">No limit reported</span>${chipRow(quiet)}</div>` : "";
  }
  if (key === "mcpMaxServers") {
    const up = Object.values(ui.mcp?.servers || {}).filter((s) => s.hosted).length;
    return ui.mcp ? `<p class="kel"><b>${fmt(up)}</b> running</p>` : "";
  }
  if (key === "showReasoning") return reasoningSketch();
  return "";
}
const blocks = (keys, extra = []) => {
  const live = keys.filter((key) => shownKnob(key));
  const cards = live.map((key) => kcard(rowHtml(KNOB[key], { hl, lit: lit(knobHay(KNOB[key])), everyCommand: true, el: elOf(key) })));
  return cards.length + extra.length ? cardRow([...cards, ...extra]) : "";
};

// the per-command settings splice reads at start and the page only shows (restart-only, set in [heads.KEY.overrides])
const tapOf = (head) => Number(ks.cfg?.layers?.perHead?.[head]?.wireTap) || 0;
// whether a command is saving prompts and answers NOW: its capture switch (GET /api/heads/{head}/capture), which the daemon
// applies on the command's next request; the setting it booted with is only the fallback when that route did not answer
const traceOn = (head) => ui.capture[head] ?? ks.cfg?.layers?.perHead?.[head]?.trace !== false;

// ---------- Your data ----------
// Each store, what it holds, how long, and its Delete now. A store with no route to count or delete it says so.
const DATA_ROWS = [
  { id: "trace", g: "text", name: "Prompts and answers", unit: "requests", yours: true, door: ["requests.html", "Requests"] },
  // the stores a person's prompts sit in that no feature runs without: counted and cleared by /api/kept/stores, each naming the
  // feature that needs it in place of a switch, with the lifetime splice gives it (the history window, or its own)
  { id: "originals", g: "text", name: "Transcript copies", unit: "files", yours: true, store: "transcript_copies", knob: "keepTranscriptCopies", off: "When off, Continue on is one-way" },
  { id: "recordings", g: "text", name: "Compaction summaries", unit: "files", yours: true, store: "compaction_summaries", knob: "keepCompactionSummaries", off: "When off, retried compactions cost again", life: "2 hours" },
  { id: "journals", g: "text", name: "Code mode work", unit: "files", store: "code_mode", yours: true, knob: "keepCodeModeWork", off: "When off, a restart ends running scripts", life: "1 day unused" },
  { id: "reasoning", g: "text", name: "Reasoning between requests", own: true, door: ["sessions.html", "Sessions"] },
  { id: "hist", g: "records", name: "Usage and request history", unit: "requests", door: ["usage.html", "Usage"] },
  { id: "edges", g: "records", name: "Who messaged whom", unit: "messages", door: ["teams.html", "Teams"] },
  { id: "labels", g: "records", name: "What each agent is doing", unit: "lines", door: ["teams.html", "Teams"] },
];
const EP = { trace: null, hist: "/api/kept/turns", edges: "/api/kept/edges", labels: "/api/kept/labels" };
// a store behind /api/kept/stores is read from that one answer and cleared by its own name
const storeOf = (r) => r.store && ui.stores && ui.stores[r.store];
// what a store holds now: rows, bytes, since when. null when its route would not answer.
function heldOf(r) {
  if (r.id === "hist") return ui.hist === undefined ? undefined : ui.hist && { n: ui.hist.held.turns, bytes: ui.hist.held.bytes, since: ui.hist.held.oldest_epoch_ms };
  if (r.id === "trace") {
    const t = ui.kept.trace;
    return t === undefined ? undefined : t && { n: t.reduce((s, x) => s + x.records, 0), bytes: t.reduce((s, x) => s + x.bytes, 0), since: null };
  }
  if (r.store) {
    if (ui.stores === undefined) return undefined;
    const s = storeOf(r);
    return s && !s.error ? { n: s.files, bytes: s.bytes, since: s.oldest_epoch_ms ?? null } : null;
  }
  const k = ui.kept[r.id];
  return k === undefined ? undefined : k && { n: k.rows, bytes: k.bytes, since: k.oldest ? Date.parse(`${k.oldest}T00:00:00`) : null };
}
/** A store whose count has not come back yet (a store with no route to count it is never waiting). */
const stillReading = (r) => !(r.noRoute || r.own) && heldOf(r) === undefined;
const delBtn = (id, n, unit) => (!n ? "" : ui.armed === id
  ? `<span class="armed"><button class="act danger small" data-act="delete" data-id="${id}">Delete ${fmt(n)} ${unit}</button><button class="act quiet small" data-act="disarm">Cancel</button></span>`
  : `<button class="act quiet small" data-act="arm" data-id="${id}">Delete now</button>`);
const ALWAYS = `<span class="fixed quiet">Always saved</span>`; // a store whose daemon has no off switch
const YOURS = `<span class="yours">Your prompts</span>`;
// What Off costs, under the switch. Every row that has the line always draws it, quieter while On, in the room the row keeps, so a flip moves no row below (p179; Marlin reversed show-only-while-Off, Oct 11). Each line is true On or Off and wraps at a dot, never inside a part (fin).
const costHtml = (off, isOff) => (off ? `<p class="offcost${isOff ? " now" : ""}">${off.split(" · ").map((t) => `<span class="whole">${esc(t)}</span>`).join("\u00a0· ")}</p>` : "");
/** One store, as a card: its name, its size large, what it holds, its share of everything splice keeps, and its own
 *  controls. The card returns its Delete rather than drawing it, so every destructive act stands together in one
 *  section at the end instead of once per row (Marcos's verdict on the small-table pane, Oct 11). */
const drow = ({ cls = "", name, hay = name, yours, door, amount, keep = "", sw = "", del = "", off = "", offShown = false, more = "", wide = false }) => ({
  name, del,
  html: `<article class="dcard${cls}${wide ? " wide" : ""}${lit(hay)}">`
    + `<header class="dtop"><span class="nm">${hl(name)}</span>${yours ? YOURS : ""}${door ? `<a class="link" href="${door[0]}">${SV.open}${door[1]}</a>` : ""}</header>`
    + `<div class="dfig">${amount}</div>`
    + (keep || sw ? `<div class="dctl">${sw ? `<div class="dc"><span class="dcl">Save</span>${sw}</div>` : ""}${keep ? `<div class="dc"><span class="dcl">Keep for</span><div class="dcv">${keep}</div></div>` : ""}</div>` : "")
    + `${costHtml(off, offShown)}${more}</article>`,
});
/** Everything splice keeps, in bytes: the figure the pane leads with, and what each card's share is measured against. */
const allBytes = () => DATA_ROWS.reduce((s, r) => s + (heldOf(r)?.bytes ?? 0), 0);
/** How fast a store grows. Usage history is the one store the daemon measures (rate_bytes_per_month, over its own days);
 *  for every other store it is the size divided by the days since the oldest thing kept, and the words say so. */
function growOf(r, x) {
  if (r.id === "hist") return ui.hist && ui.hist.rate_bytes_per_month ? `About ${mbWord(ui.hist.rate_bytes_per_month)} a month` : "";
  if (!x.since || !x.bytes) return "";
  const days = Math.max(1, Math.round((Date.now() - x.since) / DAY));
  return `About ${mbWord(x.bytes / days)} a day on average`;
}
/** Everything that holds bytes, biggest first: the order the split bar, its legend and the cards all take their colour
 *  from, so one store is one colour wherever it appears on the pane. */
const bySize = () => DATA_ROWS.map((r) => ({ r, x: heldOf(r) })).filter((p) => p.x && p.x.bytes > 0).sort((a, b) => b.x.bytes - a.x.bytes);
const hueAt = (i) => `--c:var(--store-${(i % 6) + 1})`;
const hueOf = (r) => { const i = bySize().findIndex((p) => p.r.id === r.id); return i < 0 ? "" : hueAt(i); };
/** A store's share of everything kept, as a bar and a figure. Under half a percent reads "under 1%", never "0%". */
function shareOf(x, r) {
  const all = allBytes();
  if (!all || !x.bytes) return "";
  const pc = (x.bytes / all) * 100, word = pc < 1 ? "under 1%" : `${Math.round(pc)}%`;
  return `<div class="dsh" role="img" aria-label="${word} of ${mbWord(all)} kept"><i style="${hueOf(r)};--w:${Math.max(0.6, pc).toFixed(1)}%"></i></div>`
    + `<span class="dpc">${word} of everything kept</span>`;
}
/** Usage history, day by day, as the daemon measured it (its own per-day bytes). No other store has a series, so no
 *  other card draws one: an average is words, never a shape that would read as history. */
function daysChart(h) {
  if (!h || !h.days || h.days.length < 2) return "";
  const top = Math.max(...h.days.map((d) => d.bytes)) || 1;
  const bars = h.days.map((d) => `<i style="--h:${Math.max(2, (d.bytes / top) * 100).toFixed(1)}%"></i>`).join("");
  return `<div class="dchart"><div class="dbars" role="img" aria-label="${h.days.length} days, biggest ${mbWord(top)}">${bars}</div>`
    + `<div class="dcends"><span>${atWord(h.held.oldest_epoch_ms)}</span><span>Today</span></div></div>`;
}
const amountOf = (x, r) => (x.n || x.bytes
  ? `<b class="big">${mbWord(x.bytes)}</b><span class="dmeta">${fmt(x.n)} ${esc(r.unit)}${x.since ? ` · since ${atWord(x.since)}` : ""}</span>`
    + shareOf(x, r) + (growOf(r, x) ? `<span class="dgrow">${growOf(r, x)}</span>` : "") + (r.id === "hist" ? daysChart(ui.hist) : "")
  : `<span class="none">Nothing saved</span>`);
// A store splice could not read is a quiet "Not read" in the muted line, as every page says it; why goes to the console,
// where the read that failed is named (fin, Oct 11)
const unreadable = `<span class="none">Not read</span>`;
/** The reason a read failed, in the words every page warns with. */
const whyNot = (path, r) => console.warn(`${path}: ${r.status === 0 ? "splice is not answering" : r.body?.error || `splice answered ${r.status}`}`);

// The usage and request history. A shorter window waits for the yes in a band under the row: the held days as one bar,
// the ones that go hatched, the first day kept as a tick. The yes sends the moment it showed (PUT /api/history).
function histBar(h) {
  const total = h.held.bytes || 1, at = (h.cut.bytes / total) * 100, w = h.cut.cutoff_epoch_ms;
  const days = h.days.map((d) => `<i style="--mb:${d.bytes}"></i>`).join("");
  const tick = `<span class="hcut" style="--at:${at}%">${h.window.nothing ? "" : `<em>${atWord(w)}</em>`}</span>`;
  return `<div class="hbar"><span class="hend">${atWord(h.held.oldest_epoch_ms)}</span><div class="hdays" role="img" aria-label="${mbWord(h.cut.bytes)} goes; kept from ${h.window.nothing ? "today" : atWord(w)}">`
    + `${days}<span class="hgone" style="--w:${at}%"></span><b class="hfreed" style="--w:${at}%"><span>${mbWord(h.cut.bytes)}</span></b>${tick}</div><span class="hend">Today</span></div>`;
}
// The window the daemon keeps: the saved setting, else the one it falls back to with none saved, the wider of the records
// window and 35 days (SpliceConfig.historyWindow), which GET /api/history spells. knobs.js's 35 is only the starter file's,
// so an install with none saved read "Keep for 35 days" over 84 days it was keeping for 90 (splice-usage's walk, Oct 13).
const keptDays = () => {
  const w = ui.hist?.window;
  if (ks.cfg?.knobs?.historyRetentionDays?.scope !== "default" || !w) return val("historyRetentionDays");
  return w.forever ? "forever" : w.nothing ? 0 : w.days;
};
function histRow(r) {
  if (ui.hist === undefined) return drow({ name: r.name, door: r.door, amount: READING });
  if (!ui.hist) return drow({ name: r.name, door: r.door, amount: unreadable });
  const a = ui.ask, h = a && a.h ? a.h : ui.hist, x = heldOf(r), id = "del:hist";
  const month = ui.hist.rate_bytes_per_month;
  const rate = a || h.window.nothing || !month ? "" : `<span class="hrate">About ${mbWord(month)} a month</span>`;
  return drow({
    name: r.name, hay: `${r.name} ${KNOB.historyRetentionDays.also}`, door: r.door,
    sw: ALWAYS, // the daemon has no switch for turn statistics: only how far back they go
    amount: amountOf(x, r),
    keep: `${ctlHtml(KNOB.historyRetentionDays, a ? a.v : keptDays())}${saidHtml("historyRetentionDays")}${rate}`,
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
    more: a && a.h ? `<div class="dmore hask">${histBar(a.h)}<div class="hacts"><button class="act danger small" data-act="shorten">Delete ${fmt(a.h.cut.turns)} requests</button><button class="act quiet small" data-act="unask">Cancel</button></div></div>`
      : a ? `<div class="dmore hask"><div class="hbar reading"><span class="hend"></span><div class="hdays"></div><span class="hend">Today</span></div><div class="hacts"><button class="act danger small" disabled>Delete</button><button class="act quiet small" data-act="unask">Cancel</button></div></div>` : "",
  });
}
// How long prompts and answers stay, claiming no more than is true. Each command keeps them for "Keep prompts and
// answers for" when that knob is set, on the command or for all, and for the history window otherwise (splice-lead and
// splice-turns, Oct 12); its trace/kept answer carries that number as kept_days once the route has it. Sooner than either
// once trace passes its shared size caps, 32 GiB of bodies and 16 GiB of records, where the oldest whole day goes first
// (DayBodyBudget): at 12 GB a day the bodies last about 3 days.
const traceSetDays = (head) => {
  const own = Number(ks.cfg?.layers?.perHead?.[head]?.traceRetentionDays);
  if (own >= 1) return own;
  const all = ks.cfg?.knobs?.traceRetentionDays;
  return all && all.scope !== "default" && Number(all.value) >= 1 ? Number(all.value) : null;
};
function traceKeepWords() {
  const w = ui.hist.window, windowDays = w.forever ? Infinity : w.nothing ? 0 : w.days;
  const days = ui.heads.map((h, i) => {
    const body = ui.kept.trace?.[i];
    // the route's kept_days (227e4e5bb): null keeps every day; a body without the field is an older daemon
    if (body && "kept_days" in body) return body.kept_days === null ? Infinity : body.kept_days;
    return traceSetDays(h) ?? windowDays;
  });
  const n = days.length ? Math.max(...days) : windowDays;
  if (n === Infinity) return "Until its size caps, oldest day first";
  if (n === 0) return "Today only";
  return `Up to ${n} ${n === 1 ? "day" : "days"}, sooner past its size caps, oldest day first`;
}
function dataRow(r) {
  if (r.id === "hist") return histRow(r);
  if (r.own) { // splice keeps no copy: the reasoning rides inside the conversations Claude Code writes, so the switch stops it and Sessions deletes it
    return drow({ name: r.name, yours: true, door: r.door, amount: `<span class="none">Inside your conversations</span>`, keep: `<span class="fixed quiet">As long as the conversation</span>`, sw: `${ctlHtml(KNOB.replayReasoning, val("replayReasoning"))}`, del: `<a class="act quiet small" href="sessions.html">Delete in Sessions</a>` });
  }
  if (r.noRoute) { // no route counts or deletes it: say so on the row, never an empty Delete
    return drow({ name: r.name, yours: r.yours, amount: `<span class="none">Not counted</span>`, keep: "", sw: r.always ? ALWAYS : "" }); // a store the daemon has no off switch for
  }
  const x = heldOf(r), id = `del:${r.id}`;
  if (x === undefined) return drow({ name: r.name, yours: r.yours, door: r.door, amount: READING });
  if (!x) return drow({ name: r.name, yours: r.yours, door: r.door, amount: unreadable });
  const on = r.id === "trace" ? ui.heads.filter(traceOn) : null;
  // the copies keep their own days (the President, Oct 12), so their row sets them where the others show the history window
  const keep = r.id === "originals" ? `${ctlHtml(KNOB.transcriptCopyDays, val("transcriptCopyDays"))}${saidHtml("transcriptCopyDays")}`
    : r.id === "trace" && ui.hist ? `<span class="fixed quiet need">${esc(traceKeepWords())}</span>`
    : r.id === "edges" && ui.hist ? `<span class="fixed quiet">${esc(ui.hist.window.forever ? "Forever" : ui.hist.window.nothing ? "Today only" : `${ui.hist.window.days} days`)}</span>`
    : r.id === "labels" ? `<span class="fixed quiet">Today and yesterday</span>`
    : r.life ? `<span class="fixed quiet">${esc(r.life)}</span>` : "";
  const said = r.knob ? saidHtml(r.knob) : "";
  return drow({
    name: r.name, yours: r.yours, door: r.door, amount: amountOf(x, r), keep: keep + said, cls: said || r.knob ? " once" : "", off: r.off || "", offShown: !!r.knob && !val(r.knob),
    sw: r.knob ? ctlHtml(KNOB[r.knob], val(r.knob)) : r.needs ? `<span class="fixed quiet need">${esc(r.needs)}</span>` : on ? `<button class="count" data-act="saving" aria-expanded="${ui.saving === r.id}">${on.length} of ${ui.heads.length} commands${ICON.caret}</button>` : "",
    more: on && ui.saving === r.id ? `<div class="dmore"><div class="cmdsw">${ui.heads.map((h) => `<div class="cs${traceOn(h) ? "" : " off"}">${cmdChip(h)}<span class="switch"><button data-act="capture" data-head="${esc(h)}" data-v="true" aria-pressed="${traceOn(h)}">On</button><button data-act="capture" data-head="${esc(h)}" data-v="false" aria-pressed="${!traceOn(h)}">Off</button></span>${ui.said[`capture@${h}`] ? `<span class="said ${ui.bad[`capture@${h}`] ? "limit" : "ok"}">${esc(ui.said[`capture@${h}`])}</span>` : ""}</div>`).join("")}</div></div>` : "",
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
  });
}
function dataHtml() {
  const read = drow({ name: "Read your Claude Code conversations", door: ["sessions.html", "Sessions"], amount: `<span class="none">Nothing saved</span>`, keep: saidHtml("transcriptView"), sw: ctlHtml(KNOB.transcriptView, val("transcriptView")), off: "When off, Sessions, Requests and Teams show no conversations · Questions are answered in the terminal · Nothing is deleted", offShown: !val("transcriptView") });
  // what each tapped command holds in memory right now: counted, never shown, and no Delete since the daemon has none
  const taps = ui.heads.filter((h) => tapOf(h) > 0), heldN = taps.reduce((n, h) => n + (ui.held[h] ?? 0), 0);
  // The row draws when the fact exists: a command taps its requests or bodies are held. With neither it holds nothing and
  // offers nothing (the tap is restart-only and has no control here), so it is not drawn at all (fin, Oct 11).
  const memory = taps.length || heldN ? drow({
    name: "Last requests, in memory", yours: true,
    amount: taps.some((h) => ui.held[h] === undefined) ? READING : taps.some((h) => ui.held[h] === null) ? unreadable : heldN ? `<span class="none">${fmt(heldN)} ${heldN === 1 ? "request" : "requests"} held</span>` : `<span class="none">Nothing held</span>`,
    keep: `<span class="taps">${taps.map((h) => `<span class="tap">${cmdChip(h)}<span class="fixed">Last ${tapOf(h)}</span></span>`).join("")}</span>`,
    sw: `<span class="count">${taps.length} of ${ui.heads.length} commands</span>`,
  }) : "";
  const bytes = (g) => DATA_ROWS.filter((r) => r.g === g).reduce((s, r) => s + (heldOf(r)?.bytes ?? 0), 0);
  // a total over a store that was not read is not read either: "0 B" over unread stores is a figure that is not true
  const unreadIn = (g) => DATA_ROWS.some((r) => r.g === g && !(r.noRoute || r.own) && heldOf(r) === null);
  const sum = (g) => (DATA_ROWS.some((r) => r.g === g && stillReading(r)) ? "Reading" : unreadIn(g) ? "Not read"
    : `${mbWord(bytes(g))}${DATA_ROWS.some((r) => r.g === g && (r.noRoute || r.own)) ? " counted" : ""}`);
  const head = (name, g) => `<div class="dgroup"><h3>${name}</h3><span class="hn">${sum(g)}</span></div>`;
  // each store is built once: its card goes to its group, its Delete to the one section at the end
  const built = DATA_ROWS.map((r) => ({ r, p: dataRow(r) }));
  const sized = (b) => (heldOf(b.r)?.bytes ?? 0) > 0;
  // a group whose cards say the same kind of thing stands them level, so their Save and Keep for line up; Records holds
  // one card with a chart and two without, and there the short ones stay short (splice-lead, Oct 11)
  const cards = (g) => `<div class="dcards ${esc(g)}">${built.filter((b) => b.r.g === g && sized(b)).map((b) => b.p.html).join("")}</div>`;
  const dels = built.filter((b) => b.p.del && b.p.del.trim()).map((b) => {
    const x = heldOf(b.r);
    return `<div class="dfrow"><span class="nm">${esc(b.r.name)}</span><span class="dfs">${x && x.bytes ? mbWord(x.bytes) : ""}</span><span class="dfa">${b.p.del}</span></div>`;
  });
  return `<div class="dlist">${totalHtml()}`
    + `${head("Conversation text", "text")}${cards("text")}`
    + `${head("Records", "records")}${cards("records")}`
    + `<div class="dgroup"><h3>Nothing kept right now</h3><span class="hn">No space used</span></div><div class="dcards slim">${read.html}${built.filter((b) => !sized(b)).map((b) => b.p.html).join("")}${memory ? memory.html : ""}</div>`
    + freeHtml(dels) + `</div>`;
}
/** What splice keeps, all of it, as one figure and one bar split by store, biggest first. Each piece is the share that
 *  store holds, so where the space went is read in one look rather than summed from a column of small numbers. */
function totalHtml() {
  const reading = DATA_ROWS.some(stillReading), unread = DATA_ROWS.some((r) => !(r.noRoute || r.own) && heldOf(r) === null);
  const held = bySize();
  const all = allBytes();
  const fig = reading ? `<span class="none">Reading</span>` : unread ? `<span class="none">Not read</span>` : `<b>${mbWord(all)}</b>`;
  // six hues of their own, never the provider colours: on every page a colour names a provider, and these name stores
  const seg = (p, i) => `<i style="${hueAt(i)};--w:${((p.x.bytes / all) * 100).toFixed(2)}%" title="${esc(p.r.name)}"></i>`;
  const key = (p, i) => `<span class="dkey"><i style="${hueAt(i)}"></i>${esc(p.r.name)}<b>${mbWord(p.x.bytes)}</b></span>`;
  return `<section class="dtotal"><div class="dtfig">${fig}<span class="dtw">kept on this computer</span></div>`
    + (all && !reading ? `<div class="dtbar" role="img" aria-label="${held.map((p) => `${p.r.name} ${mbWord(p.x.bytes)}`).join(", ")}">${held.map(seg).join("")}</div>`
      + `<div class="dtkey">${held.map(key).join("")}</div>` : "")
    + `</section>`;
}
/** Every delete in one place at the end, away from the cards, so a destructive act is never a button beside a reading. */
function freeHtml(dels) {
  if (!dels.length) return "";
  return `<section class="dfree"><div class="dgroup"><h3>Free up space</h3><span class="hn">Deleting cannot be undone</span></div>`
    + `<div class="dfrows">${dels.join("")}</div></section>`;
}

// ---------- what happened this week ----------
// Counted from the requests Usage and Requests read (GET /api/perf/turns, one read per command and word), never kept
// here: a count is the daemon's `count` for the filter a door opens on Requests, so the figure and the page it opens
// agree. Six words are tested on the daemon over every row (TurnsTest.kt), never over the newest 2,000 the route would send.
const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
const SEC = 1000, MIN = 60000;
const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return +d; };
const reqUrl = (f) => `requests.html?${new URLSearchParams(f)}`;
const rowId = (head, row) => `${head}:${row.turn || row.turn_id || row.ts}`;
/** One figure's question of the daemon: the filter its word means and its floor in time. The daemon counts; no row is sent back
 *  but the newest, so a busy command's figure is its true count and never the size of a read. */
function askWeek(head, why, from) {
  const ask = why === "failed" ? { outcome: "failed" } : { ...WHY_ASKS[why] }, tier = idleTierOf(ks.cfg, head.key);
  if (ROW_TEST[why]) ask.test = ROW_TEST[why];
  if (why === "waited") { if (!Number.isFinite(tier)) return { head, why, err: "no silence setting answered" }; ask.silence_ms = String(Math.round(tier)); }
  return { head, why, query: new URLSearchParams({ n: "1", from: String(from), local: "0", usage: "0", ...ask }).toString() };
}
/** The figure one question's answer makes, or why it makes none. */
function countOf({ head, why }, answer) {
  if (!answer || answer.error || answer.read_error || typeof answer.count !== "number") return { head, err: answer?.error || answer?.read_error || "splice did not answer" };
  return { head, n: answer.count, newest: (answer.rows || [])[0] ?? null, longest: why === "waited" ? answer.longest ?? null : null, silence: why === "waited" ? answer.longest_silence_ms ?? 0 : 0 };
}
/** Every figure of one command from ONE read of its week: the daemon's cost is the file's, not the question's (about 3 s per
 *  read on a busy command), so nine questions one after another were half a minute. */
async function countsWeek(head, plan, week) {
  const asks = plan.map(([why, from]) => askWeek(head, why, from));
  const sent = asks.filter((a) => !a.err);
  if (!sent.length) return new Map(asks.map((a) => [a.why, { head, err: a.err }]));
  const q = new URLSearchParams({ head: head.key, since: String(week), time_zone: ZONE, n: "1", local: "0", usage: "0" });
  sent.forEach((a) => q.append("ask", a.query));
  const res = await bulkGet(`/api/perf/turns?${q}`);
  const answer = (res.body?.heads || []).find((x) => x.key === head.key);
  const answers = answer?.asks;
  const failure = !res.ok || !answer || !Array.isArray(answers) || answers.length !== sent.length ? answer?.error || answer?.read_error || `splice answered ${res.status}` : null;
  const byWhy = new Map(sent.map((a, i) => [a.why, failure ? { head, err: failure } : countOf(a, answers[i])]));
  asks.filter((a) => a.err).forEach((a) => byWhy.set(a.why, { head, err: a.err }));
  return byWhy;
}
/** The most requests a command held at once today, and when. The daemon works it out over every request of the day, each
 *  holding a slot from the start of its sending to its end, so a busy command's day, which is more rows than one read
 *  returns, still has its peak. */
async function readPeak(head) {
  const res = await bulkGet(`/api/perf/turns?${new URLSearchParams({ head: head.key, since: String(startOfToday()), time_zone: ZONE, n: "1", local: "0", usage: "0", peak: "1" })}`);
  const answer = (res.body?.heads || []).find((x) => x.key === head.key);
  // a peak not read is "Not read" or "Not answering" on the page, and why goes to the console, as whyNot does (fin, Oct 12)
  if (!res.ok || !answer || answer.error || answer.read_error) {
    console.warn(`/api/perf/turns peak for ${head.key}: ${res.status === 0 ? "splice is not answering" : answer?.error || answer?.read_error || `splice answered ${res.status}`}`);
    return { head, err: res.status === 0 ? "Not answering" : "Not read" };
  }
  const held = answer.at_once;
  if (!held) {
    console.warn(`/api/perf/turns peak for ${head.key}: the answer carried no at_once`);
    return { head, err: "Not read" };
  }
  return { head, peak: held.peak, at: held.at, n: held.counted, queued: held.queued };
}
function readWeek() {
  const day = startOfToday(), week = Date.now() - 7 * DAY;
  const plan = [["overloaded", day], ["cutoff", day], ["queued", day], ["restarted", week], ["gaveup", week], ["waited", week], ["silentresume", week], ["silentover", week]];
  Promise.all(ui.headRows.map(readPeak)).then((peaks) => { ui.peaks = peaks; later(); });
  Promise.all(ui.headRows.map((h) => countsWeek(h, plan, week))).then((perHead) => {
    plan.forEach(([why]) => { ui.week[why] = perHead.map((m) => m.get(why)); });
    later();
  });
}
const weekReading = (...words) => words.some((why) => ui.week[why] === undefined);
/** A word's figure across every command: the total, or null when any command's read did not answer. */
const weekTotal = (why) => { const per = ui.week[why]; return per && per.every((p) => !p.err) ? per.reduce((s, p) => s + p.n, 0) : null; };
const doorTo = (url, what, tail, chip) => `<a class="door" href="${url}">${SV.open}<span>${what}</span>${tail}${chip ? cmdChip(chip) : ""}<em>Open it</em></a>`;
const clockOf = (ts) => clock(new Date(ts));
const msLong = (ms) => (ms >= MIN ? `${Math.floor(ms / MIN)} min${ms % MIN ? ` ${Math.round((ms % MIN) / SEC)} s` : ""}` : `${Math.round(ms / SEC)} s`);
/** One figure: how many, opening them all on Requests, then each command's share. A figure he can open is a tile; a zero, or a
 *  count splice could not read, is a quiet line beside the tiles, never a tile of its own (fin, Oct 11). */
function figParts({ why, what, door, cmds = true }) {
  if (weekReading(why)) return { tile: `<div class="fig3 none"><span class="big">…</span><span class="what">${esc(what)}</span><span class="state">Reading</span></div>` };
  const per = ui.week[why] ?? [], total = weekTotal(why), from = why === "overloaded" || why === "cutoff" || why === "queued" ? { from: String(startOfToday()) } : { win: "7d" };
  if (total === null) {
    console.warn(`${what}: ${per.find((p) => p.err)?.err ?? "splice did not answer"}`);
    return { line: `<span class="fig"><span class="state limit">Not read</span> ${esc(what)}</span>` };
  }
  if (!total) return { line: `<span class="fig"><b>0</b> ${esc(what)}</span>` };
  const ask = why === "failed" ? { outcome: "fail" } : { why };
  const shares = per.filter((p) => p.n).sort((a, b) => b.n - a.n);
  return { tile: `<div class="fig3"><a class="lead" href="${reqUrl({ ...from, ...ask })}"><span class="big">${fmt(total)}</span><span class="what">${esc(what)}</span><em>${SV.open}Open on Requests</em></a>`
    + (cmds ? `<div class="cdoors">${shares.map((p) => `<a class="cdoor" href="${reqUrl({ ...from, ...ask, cmd: p.head.command })}">${cmdChip(p.head.key)}<b>${fmt(p.n)}</b></a>`).join("")}</div>` : "")
    + (door ? door(shares) : "") + `</div>` };
}
/** The figures of one group: the tiles he can open, then one quiet line holding the zeros. `n` is how many tiles were
 *  drawn, which is what a group's share of the row is weighed by: the ones that came back zero are in the line. */
function figsHtml(figs) {
  const parts = figs.map(figParts), lines = parts.filter((p) => p.line).map((p) => p.line);
  const tiles = parts.filter((p) => p.tile).map((p) => p.tile);
  return {
    n: tiles.length,
    // the count is on the row too: a group's tiles share its width evenly, so how wide one came out is the row's width
    // divided by this, which is what tells a lone tile on a full row from three tiles sharing one (settings.css)
    tiles: `<div class="figs3 n${tiles.length}">${tiles.join("")}</div>`,
    // the zeros ride on the head beside the name of what they belong to: under the tiles, "0 Given up" hung on its own
    // under the row with nothing to tie it to (splice-lead's walk of round two, Oct 11)
    zeros: lines.length ? `<span class="zeros">${lines.join('<span class="dot">·</span>')}</span>` : "",
  };
}
const latestDoor = (shares) => {
  const best = shares.filter((p) => p.newest).sort((a, b) => b.newest.ts - a.newest.ts)[0];
  return best ? doorTo(`requests.html?win=7d#${encodeURIComponent(rowId(best.head.key, best.newest))}`, "Latest", `<b>${clockOf(best.newest.ts)}</b>`, best.head.key) : "";
};
const longestDoor = (shares) => {
  const best = shares.filter((p) => p.longest).sort((a, b) => b.silence - a.silence)[0];
  return best ? doorTo(`requests.html?win=7d#${encodeURIComponent(rowId(best.head.key, best.longest))}`, "Longest silence", `<b>${msLong(best.silence)}</b>`, best.head.key) : "";
};
// --f is how many figures the group holds, so two groups share the row in proportion to what is in them and every
// figure comes out the same width: one group of one beside a group of three left most of the row empty at 1440 and 3828
// the head carries the group's name and whatever came back zero, over a line the width of the group, so a label over
// two tiles is read as theirs and not as the first one's (splice-lead: "the third card has none", Oct 11)
const groupHtml = (label, figs) => {
  const f = figsHtml(figs);
  return `<div class="fgroup" style="--f:${f.n || 1}"><div class="ghead"><span class="glabel">${label}</span>${f.zeros}</div>${f.tiles}</div>`;
};
const tallyHtml = (title, groups) => `<section class="tally"><h3>${title}</h3><div class="fgroups">${groups.join("")}</div></section>`;
/** A section whose figures stand under its own heading, with anything that came back zero on the heading's line. */
const headed = (title, f) => `<div class="thead"><h3>${title}</h3>${f.zeros}</div>${f.tiles}`;
const silentHtml = () => tallyHtml("This week", [
  groupHtml("Ended early", [
    { why: "restarted", what: WHY.restarted.word, door: latestDoor },
    { why: "gaveup", what: WHY.gaveup.word, door: latestDoor },
  ].sort((a, b) => (weekTotal(b.why) ?? 0) - (weekTotal(a.why) ?? 0))),
  groupHtml("Kept going", [
    { why: "waited", what: WHY.waited.word, door: longestDoor },
    { why: "silentresume", what: WHY.silentresume.word.replace(" after a silence", "") },
    { why: "silentover", what: WHY.silentover.word.replace(" after a silence", "") },
  ]),
]) + `<section class="sub">${blocks(keysOf("silent"))}</section>`;
const limitOf = (head) => Number(ks.cfg?.layers?.perHead?.[head]?.maxInflight ?? val("maxInflight")) || 0;
function peaksHtml() {
  if (ui.peaks === undefined) return `<section class="tally"><h3>Today</h3><div class="card peaks">${READING}</div></section>`;
  if (!ui.peaks.length) return "";
  const rows = [...ui.peaks].sort((a, b) => (b.peak ?? -1) - (a.peak ?? -1));
  // The commands that actually ran today lead, one card each; the ones that held nothing collapse to a line naming
  // them, instead of a full-width bar at 0 apiece (Marcos's verdict on this pane, Oct 11).
  const busy = rows.filter((p) => !p.err && p.peak > 0), idle = rows.filter((p) => !p.err && !p.peak), bad = rows.filter((p) => p.err);
  const card = (p) => {
    const key = p.head.key, lim = limitOf(key), pc = lim ? Math.min(100, (100 * p.peak) / lim) : 4;
    return `<article class="pcard${lim && p.peak >= lim ? " full" : ""}"><header>${cmdChip(key)}<span class="pat">${clockOf(p.at)}</span></header>`
      + `<div class="pbig"><b>${fmt(p.peak)}</b><span class="pof">${lim ? `of ${fmt(lim)} at once` : "Unlimited"}</span></div>`
      + `<span class="pbar"><b style="width:${pc}%"></b></span>`
      // a command that queued nothing reads as the zero of the figure above it, in the same words and with no door
      + (p.queued ? `<a class="door" href="${reqUrl({ from: String(startOfToday()), why: "queued", cmd: p.head.command })}">${SV.open}<span>Waited in line</span><b>${fmt(p.queued)}</b><em>Open on Requests</em></a>` : `<span class="pnone"><b>0</b> Waited in line</span>`)
      + `</article>`;
  };
  const quiet = idle.length
    ? `<p class="pidle"><span>No requests today</span>${idle.map((p) => cmdChip(p.head.key)).join("")}</p>` : "";
  const errs = bad.map((p) => `<p class="pidle"><span class="none">${esc(p.err)}</span>${cmdChip(p.head.key)}</p>`).join("");
  return `<section class="tally"><h3>Today</h3>${busy.length ? `<div class="pcards">${busy.map(card).join("")}</div>` : `<p class="pidle"><span>No requests today</span></p>`}${quiet}${errs}</section>`;
}
// ---------- Plan limits ----------
// Where the plans stand against the line these settings draw. The windows are /api/accounts' own, the ones Accounts
// shows per account; this pane reads only the highest of each, because the question here is whether the warn line sits
// in the right place, not which account is busy. The bar carries the line itself, so the setting is read as a mark on
// the figure rather than as a number in a box.
const pctWord = (p) => `${Math.round(p)}%`;
const planBar = (p, warn) => `<div class="pct${p >= warn ? " over" : ""}" style="--w:${Math.min(100, Math.max(1.5, p))}%;--warn:${warn}%" role="img" aria-label="${pctWord(p)} used, warns at ${warn}%"><b></b><i></i></div>`;
const resetWord = (secs) => (secs ? `Resets ${backWord(new Date(secs * 1000))}` : "");
function planNow() {
  const a = ui.accts;
  if (a === undefined) return `<div class="card peaks">${READING}</div>`;
  if (!a) return `<p class="pidle"><span class="state limit">Not read</span><span>splice did not answer for its accounts</span></p>`;
  const warn = Number(val("usageWarnPct")) || 80;
  const read = a.filter((x) => !signedOut(x) && (x.five_hour_used_percent != null || x.seven_day_used_percent != null));
  const top = (k) => read.filter((x) => x[k] != null).sort((p, q) => q[k] - p[k])[0];
  const five = top("five_hour_used_percent"), week = top("seven_day_used_percent");
  const over = read.filter((x) => Math.max(x.five_hour_used_percent ?? 0, x.seven_day_used_percent ?? 0) >= warn);
  const tiles = [], zeros = [];
  if (five) {
    tiles.push(statTile(pctWord(five.five_hour_used_percent), "Highest in 5 hours",
      planBar(five.five_hour_used_percent, warn) + chipRow([loginWord(five)]) + note(resetWord(five.five_hour_reset_epoch_seconds))));
  }
  if (week) {
    tiles.push(statTile(pctWord(week.seven_day_used_percent), "Highest this week",
      planBar(week.seven_day_used_percent, warn) + chipRow([loginWord(week)]) + note(resetWord(week.seven_day_reset_epoch_seconds))));
  }
  if (over.length) {
    // the accounts themselves, no sentence: the setting under this tile is named "Warn when a plan reaches", so what
    // splice does about them is already on the pane (fin, Marlin's order, Oct 12)
    tiles.push(statTile(fmt(over.length), `Over ${warn}%`, chipRow(over.map(loginWord))));
  } else if (read.length) { zeros.push(zeroFig(`over ${warn}%`)); }
  // NO FOOT LINE UNDER THE BARS (fin, Oct 12): when the plans were last read is the element under "Check plan usage
  // every", and the accounts reporting no window are the chips under the 5-hour row, each beside the setting it
  // answers. Repeating either here put the same fact on the pane twice, in two typefaces, four inches apart.
  // Where no account reports a window at all there are no bars to draw, so the state stands in their place.
  // "No limits reported" is the providers answering with none; a login splice has never read is Not read, since this
  // page asks for no reading of its own (marlin, Oct 12)
  const never = a.some((x) => !x.refusal && x.credential_present !== false && !x.observed_at_epoch_seconds && !x.no_usage_at_epoch_seconds);
  if (!tiles.length && !zeros.length) zeros.push(`<span class="fig"><span class="state">${never ? "Not read" : "No limits reported"}</span></span>`);
  return `<div class="thead"><h3>Right now</h3>${zeros.length ? `<span class="zeros">${zeros.join('<span class="dot">·</span>')}</span>` : ""}</div>`
    + (tiles.length ? `<div class="figs3 n${tiles.length}">${tiles.join("")}</div>` : "");
}
const planHtml = () => `<section class="tally">${planNow()}</section><section class="sub">${blocks(keysOf("plan"))}</section>`;

// ---------- MCP servers ----------
// GET /api/mcp is a SNAPSHOT, not a count over a window: the host keeps no tally of tool calls, of how long one took,
// or of how many servers an idle sweep stopped (McpStatus.kt, HostedServers.kt), so this pane says what is true now
// and never a week figure it would have to invent. Each server's own line is what the settings above it act on.
const statTile = (big, what, under = "") => `<div class="fig3"><span class="big">${big}</span><span class="what">${esc(what)}</span>${under}</div>`;
const srvChip = (name) => `<span class="mchip">${esc(name)}</span>`;
const zeroFig = (what) => `<span class="fig"><b>0</b> ${esc(what)}</span>`;
function mcpNow() {
  const m = ui.mcp;
  if (m === undefined) return `<div class="card peaks">${READING}</div>`;
  if (m === false) return "";
  if (!m) return `<p class="pidle"><span class="state limit">Not read</span><span>splice did not answer for its MCP servers</span></p>`;
  const live = Object.entries(m.servers || {}), up = live.filter(([, s]) => s.hosted);
  // one server is written in several files (a head's copy of the canonical home, a repo's own entry), so the figure is
  // how many SERVERS he set up, not how many entries splice read: 32 servers over 112 entries on this machine
  const regs = m.sources?.servers || [], names = [...new Set(regs.map((r) => r.name))];
  const hosts = [...new Set(regs.filter((r) => r.disposition === "migrated").map((r) => r.name))];
  const set = names.length || live.length;
  const held = up.reduce((n, [, s]) => n + (s.rss_bytes || 0), 0);
  const sessions = up.reduce((n, [, s]) => n + (s.sessions || 0), 0);
  const tiles = [], zeros = [];
  // A LABEL AND ITS ZERO ARE THE SAME WORDS (fin, Oct 12): the zero line stands in for the tile that did not draw, so
  // "0 Running" is read as the empty form of "Running". "Server running" against "0 running now" read as two figures.
  if (up.length) {
    tiles.push(statTile(fmt(up.length), "Running", chipRow(up.map(([n]) => n))));
  } else { zeros.push(zeroFig("Running")); }
  if (set) tiles.push(statTile(fmt(set), "Set up", note(regs.length > set ? `${fmt(regs.length)} entries in your files` : "")));
  if (hosts.length) {
    // THE FIGURE AND ITS CHIPS, NO SUB-LINE (fin, Marlin, Oct 12): a process count here is what runs now and so repeats
    // "Running", and a session count is the agent count the page does not draw, because splice cannot name those sessions.
    tiles.push(statTile(fmt(hosts.length), "Hosted by splice", chipRow(hosts)));
  } else if (set) { zeros.push(zeroFig("Hosted by splice")); }
  // no "Run by each session" tile: it added unlike things (a web address, a plugin's server, a per-project one), and each
  // server's row already says its own reason (fin, Marlin, Oct 12)
  // resident memory of the live hosted processes, read from /proc (McpStatus.kt:100), with the sessions on them under it
  if (held) tiles.push(statTile(mbWord(held), "Memory in use", note(sessions ? `${fmt(sessions)} ${sessions === 1 ? "session" : "sessions"}` : "")));
  const lines = [];
  // the one fact the settings above cannot fix: a server that failed, named with what it said
  live.filter(([, s]) => s.last_error).forEach(([n, s]) => lines.push(`<span class="fig">${srvChip(n)} <span class="state limit">${esc(s.last_error)}</span></span>`));
  return `<div class="thead"><h3>Right now</h3>${zeros.length ? `<span class="zeros">${zeros.join('<span class="dot">·</span>')}</span>` : ""}</div>`
    + (tiles.length ? `<div class="figs3 n${tiles.length}">${tiles.join("")}</div>` : "")
    + (lines.length ? `<div class="zeros under">${lines.join('<span class="dot">·</span>')}</div>` : "");
}
const chipRow = (names) => `<div class="cdoors">${names.map(srvChip).join("")}</div>`;
const note = (s) => (s ? `<p class="knote">${esc(s)}</p>` : "");
// ---------- MCP servers: one row each ----------
// The row is hitstop's drawing (Splice-Animator/design/console, Oct 12) and its words are fin's. The daemon says WHY a
// server is not shared as a stable key (McpSharing.kt, McpInventory.kt) with the value it names; this table is the only
// place those keys become words. A key it does not know reads "Not shared" and nothing more, never a guess.
const MCP_STATE = {
  cwd: { word: "Per session", line: "Starts in its own folder" },
  variable: { word: "Per session", line: "A different value in each session" },
  relative_path: { word: "Per project", line: "A different folder in each project" },
  location_flag: { word: "Per project", line: "A different place in each project" },
  roots: { word: "Per project", line: "Asks which project it's in" },
  directory: { word: "Per project", line: "One project's folder" },
  stateful: { word: "Keeps state", line: "Holds one session's own work" },
  per_session_choice: { word: "Per session", line: "Sharing is off" },
  no_command: { word: "No command" },
  unreadable: { word: "Unreadable" },
  plugin: { word: "Plugin-owned" },
  repo: { word: "In the repo" },
  project: { word: "Per project" },
  other_config: { word: "Other config" },
  added_in: { word: "Added in", cmd: true },
  excluded: { word: "Set by you" },
};
// keys that say a state and no word: the section's own switch says hosting is off, a race reads only its state, and a
// web server is shared by being one server (fin, Oct 12)
const MCP_SILENT = new Set(["hosting_disabled", "race", "transport", "until_restart"]);
const HOME = /^\/(?:home|Users)\/[^/]+/;
const homeWord = (p) => String(p || "").replace(HOME, "~");
// a command's own copy of the config lives in ~/.claude-<key>/.claude.json
const copyOf = (file) => /\/\.claude-([^/]+)\/\.claude\.json$/.exec(file || "")?.[1] ?? null;
// a plugin copy Claude Code cloned to a temp folder while installing, left in its cache
const TEMP_CLONE = /\/temp_[a-z]+_\d+[^/]*\//;
const fromWord = (e) => {
  if (!e) return "";
  if (e.kind === "plugin_mcp_json" || e.kind === "plugin_inline") {
    // Claude Code caches a plugin as <plugin>/<version>/, its manifest under .claude-plugin/, so its name is the folder
    // above both; a temp_subdir_ or temp_git_ clone it left mid-install carries no name at all (the live daemon, Oct 13)
    const file = String(e.source_file || "");
    if (TEMP_CLONE.test(file)) return "a plugin";
    const dirs = file.split("/").slice(0, -1);
    if (dirs.at(-1) === ".claude-plugin") dirs.pop();
    if (/^\d+(\.\d+)+/.test(dirs.at(-1) ?? "")) dirs.pop();
    const dir = dirs.pop();
    return dir ? `${dir} plugin` : "a plugin";
  }
  return homeWord(e.source_file);
};
// a project's own entry names its project by the folder it sits in, so a row shows that name and not the config path
const projectOf = (e) => (e?.kind === "project" ? String(e.scope || "").split("/").filter(Boolean).pop() ?? "" : "");
// where a server is declared, under its name: its project's chip, else its file or plugin, and a web server's address
const fromHtml = (s) => `<span class="from">${SV.file}${s.project ? `<span class="chip">${esc(s.project)}</span>` : `<span class="ft" title="${esc(s.from)}">${esc(s.from)}</span>`}${s.address ? `<code>${esc(s.address)}</code>` : ""}</span>`;
// a web server's address, without the part of a URL that can carry a credential: the host and path, never a query or a login
const addressOf = (v) => {
  try { const u = new URL(v); return u.host + (u.pathname === "/" ? "" : u.pathname); } catch { return ""; }
};
/** Every server by name, from what the daemon planned (servers) and what it found in the files (sources). */
function mcpServerRows(m) {
  const found = new Map();
  for (const e of m.sources?.servers || []) { if (!found.has(e.name)) found.set(e.name, []); found.get(e.name).push(e); }
  const names = new Set([...found.keys(), ...Object.keys(m.servers || {})]);
  return [...names].map((name) => {
    const live = m.servers?.[name] || null, entries = found.get(name) || [];
    // a server declared in a real place speaks for its row before a temp clone of a plugin does
    const own = entries.find((e) => !copyOf(e.source_file) && !TEMP_CLONE.test(e.source_file || ""))
      || entries.find((e) => !copyOf(e.source_file)) || entries[0];
    const stale = [...new Set(entries.filter((e) => e.why === "until_restart").map((e) => e.value || copyOf(e.source_file)).filter(Boolean))];
    const added = entries.find((e) => e.why === "added_in");
    const why = live && !live.eligible ? live.why : !live ? (own?.why ?? added?.why) : null;
    const value = live && !live.eligible ? live.value : own?.value;
    const web = why === "transport";
    const shared = !!live?.eligible || web;
    const state = shared ? null : (why === "added_in" || (!own && added) ? "added_in" : why);
    const copy = added || own;
    if (state && !MCP_STATE[state] && !MCP_SILENT.has(state)) console.warn(`mcp: no word for "${state}" on ${name}: ${live?.reason ?? own?.reason ?? ""}`);
    const at = own || added;
    const project = projectOf(at);
    return { name, from: fromWord(at), project, shared, web, address: web ? addressOf(value) : "", state, value,
      cmd: state === "added_in" ? (entries.length && value && why === "added_in" ? value : copyOf(copy?.source_file)) : null,
      cmds: live?.commands || [], stale, last: live?.last_activity || 0, err: live?.last_error || "", started: live?.started_at || 0,
      // the one process splice runs for it now and what it holds, read from /proc by the daemon (McpStatus.kt hosted)
      hosted: !!live?.hosted, rss: live?.rss_bytes ?? null };
  }).sort((a, b) => Number(b.shared) - Number(a.shared) || a.name.localeCompare(b.name));
}
const msWord = (ms) => (ms >= 1000 ? `${+(ms / 1000).toFixed(1)} s` : `${ms} ms`);
const testSaid = (name) => {
  const r = ui.mtest[name];
  // counting up, because a server that has to start can take most of a minute and a still word reads as hung
  if (r === "running") { const secs = Math.floor((Date.now() - (ui.mtestAt[name] || Date.now())) / 1000); return `<span class="said">${ICON.wait}Testing${secs ? ` · ${fmt(secs)} s` : ""}</span>`; }
  if (r?.ok) return `<span class="said ok">${r.started ? "Started · " : ""}${msWord(r.ms)}${r.tools == null ? "" : ` · ${fmt(r.tools)} ${r.tools === 1 ? "tool" : "tools"}`}</span>`;
  // the words the route sends for a failed test, after the short word: a failure with no reason left nothing to act on
  return r ? `<span class="said limit">${esc(r.word)}${r.why ? ` · ${esc(r.why)}` : ""}</span>` : "";
};
// a shared server's one process: the memory it holds now, Running where /proc gave none, Not running until a session
// asks for it (spec 8i: instance, sessions and memory per server; its sessions are the Used by chips beside it)
// a server at a web address has no process here, so its cell is empty, never Not running (marlin, Oct 12)
const memWord = (s) => (s.web ? "" : !s.hosted ? `<span class="state">Not running</span>` : s.rss ? esc(mbWord(s.rss)) : "Running");
function serverRow(s, dim) {
  const name = `<div class="sname"><span class="nm">${esc(s.name)}</span>${fromHtml(s)}</div>`;
  if (!s.shared) {
    const m = MCP_STATE[s.state];
    const cmd = m?.cmd && s.cmd ? cmdChip(s.cmd) : "", code = m && s.value && s.state !== "added_in" ? `<code>${esc(homeWord(s.value))}</code>` : "";
    // a row with a why has no switch, just the word; one the person set reads Not shared with Share beside it, which
    // takes it off mcp_hosting_exclude, and a row with no word reads only its state
    const why = m ? `<div class="why"><span class="w">${esc(m.word)}${cmd}${code}</span>${m.line ? `<span class="wl">${esc(m.line)}</span>` : ""}</div>` : "<span></span>";
    const state = s.state === "excluded" ? `<span class="acts">${exAct(s.name, false)}<span class="always">Not shared</span></span>`
      : !m ? `<span class="acts"><span class="always">Not shared</span></span>` : "<span></span>";
    return `<div class="srv out"><span class="lamp ended" aria-hidden="true"><i></i></span>${name}${why}${state}</div>`;
  }
  // a command whose own copy of the config is older joins at its next session, which rewrites the copy (fin, Oct 12)
  const soon = s.stale.length ? s.stale.map((c) => cmdChip(c).replace("class=\"chip\"", "class=\"chip soon\"")).join("") + `<span class="next">Next session</span>` : "";
  const used = s.cmds.length || soon ? `<span class="used">Used by${s.cmds.map((c) => cmdChip(c)).join("")}${soon}</span>` : "";
  const test = `${testSaid(s.name)}<button class="act quiet small" data-act="mtest" data-name="${esc(s.name)}"${ui.mtest[s.name] === "running" ? " disabled" : ""}>Test</button>`;
  return `<div class="srv"><span class="lamp working" aria-hidden="true">${ICON.trace}</span>${name}`
    + `<span class="fig uses">${used}</span><span class="fig mem">${memWord(s)}</span><span class="fig">${s.started ? `Started ${esc(atWord(s.started))}` : ""}${s.started && s.last ? " · " : ""}${s.last ? `Last call ${esc(atWord(s.last))}` : ""}${s.err ? ` <span class="state limit">${esc(s.err)}</span>` : ""}</span>`
    + `<span class="acts">${test}${exAct(s.name, true)}<span class="always">Shared</span></span></div>`;
}
const mcpServersHtml = () => {
  const m = ui.mcp;
  if (!m) return "";
  const rows = mcpServerRows(m);
  if (!rows.length) return "";
  return `<section class="sub"><h3>Servers<span class="hn">${fmt(rows.filter((r) => r.shared).length)} of ${fmt(rows.length)} shared</span></h3><div class="srvs${m.hosting === false ? " dim" : ""}">${rows.map(serverRow).join("")}</div></section>`;
};
/** POST /api/mcp/{name}/test: the daemon starts the server if it is not running, asks it for its tools, and says how long. */
async function testServer(name) {
  ui.mtest[name] = "running"; ui.mtestAt[name] = Date.now(); render();
  const tick = setInterval(render, 1000);
  const r = await API.post(`/api/mcp/${encodeURIComponent(name)}/test`, {});
  const b = r.body || {};
  clearInterval(tick);
  ui.mtest[name] = r.ok && b.ok ? { ok: true, started: !!b.started, ms: b.round_trip_ms, tools: b.tools }
    : { ok: false, word: { timeout: "Given up", spawn: "Not started", unshared: "Not shared" }[b.kind] ?? "Test failed", why: b.error };
  render();
}

// ---------- MCP servers: shared or one per session, and what sharing saves (mcp-hub.md, How it works 4) ----------
// The one stored choice is [daemon] mcp_share in splice.toml, the answer `splice setup` saves. Absent reads as shared,
// which is how splice hosts servers today, so the switch never shows a third "not asked" state (splice-config, Oct 12).
// ONE SWITCH, ONE FACT (Marlin, Oct 13, gap 464): servers are shared only when both mcp_share and mcp_hosting are on, so
// the switch reads both, and Shared turns on whichever was off. One per session writes mcp_share only.
const shareOn = () => ui.topo?.topology?.daemon?.mcp_share !== false && ui.topo?.topology?.daemon?.mcp_hosting !== false;
function shareCard() {
  if (!ui.topo) return ""; // an unread splice.toml draws no switch: one tap would write over what it never read
  const on = shareOn();
  const sw = `<span class="switch">${[[true, "Shared"], [false, "One per session"]].map(([v, w]) =>
    `<button data-act="mshare" data-v="${v}" aria-pressed="${v === on}"${ui.shareSaving ? " disabled" : ""}>${w}</button>`).join("")}</span>`;
  const said = ui.shareSaid ? `<span class="said${ui.shareSaid.bad ? " limit" : ""}">${esc(ui.shareSaid.word)}</span>` : "";
  return cardRow([kcard(`<div class="lbl">Share servers between sessions</div><div class="ctl col"><div class="line">${sw}${said}</div></div>`)]);
}
/** PUT /api/topology with [daemon] mcp_share changed, and mcp_hosting cleared when sharing is turned on. A changed choice applies to new connections; a running copy
 *  finishes with its session, so nothing here is cut. */
async function setShare(on) {
  if (!ui.topo || on === shareOn()) return;
  const t = structuredClone(ui.topo.topology ?? {});
  t.daemon = { ...(t.daemon ?? {}), mcp_share: on };
  if (on) delete t.daemon.mcp_hosting; // absent is on (Topology.kt mcpHosting)
  ui.shareSaving = true; ui.shareSaid = null; render();
  const r = await API.put("/api/topology", { topology: t });
  ui.shareSaving = false;
  if (!r.ok || !r.body?.ok) {
    const found = r.body?.findings ?? [];
    if (found.length) console.warn(`settings: saving mcp_share refused: ${found.map((x) => x.message).join("; ")}`);
    ui.shareSaid = { word: found.length ? "Config error" : "Not saved", bad: true };
    render();
    return;
  }
  ui.shareSaid = { word: r.body.restart_required ? "After a restart" : "New sessions" };
  await rereadMcp();
}
/** Share, or stop sharing, one server: Don't share adds it to [daemon] mcp_hosting_exclude, Share takes it off. */
const exAct = (name, shared) => {
  const said = ui.exSaid[name], busy = said === "saving";
  const word = said && !busy ? `<span class="said${said.bad ? " limit" : ""}">${esc(said.word)}</span>` : "";
  return `${word}<button class="act quiet small" data-act="mexclude" data-name="${esc(name)}" data-v="${shared}"${busy ? " disabled" : ""}>${shared ? "Don't share" : "Share"}</button>`;
};
async function setExcluded(name, out) {
  if (!ui.topo) return;
  const t = structuredClone(ui.topo.topology ?? {});
  const now = (t.daemon?.mcp_hosting_exclude ?? []).filter((x) => x !== name);
  t.daemon = { ...(t.daemon ?? {}), mcp_hosting_exclude: out ? [...now, name] : now };
  ui.exSaid[name] = "saving"; render();
  const r = await API.put("/api/topology", { topology: t });
  if (!r.ok || !r.body?.ok) {
    const found = r.body?.findings ?? [];
    if (found.length) console.warn(`settings: saving mcp_hosting_exclude refused: ${found.map((x) => x.message).join("; ")}`);
    ui.exSaid[name] = { word: found.length ? "Config error" : "Not saved", bad: true };
    render();
    return;
  }
  ui.exSaid[name] = { word: r.body.restart_required ? "After a restart" : "New sessions" };
  await rereadMcp();
}
async function rereadMcp() {
  const [topo, mcp, mem] = await Promise.all([API.get("/api/topology"), API.get("/api/mcp"), API.get("/api/mcp/memory")]);
  ui.topo = topo.ok ? topo.body : ui.topo;
  ui.mcp = mcp.ok ? mcp.body : mcp.status === 404 ? false : null;
  ui.mmem = mem.ok ? mem.body : null;
  nameHolders();
  render();
}
// A server sharing would serve with one copy: one the hub shares now, or one it runs per session only because sharing
// is off. Every other server keeps one copy per session either way and is named below with its reason.
const shareable = (s) => s.class === "shared" || s.why === "per_session_choice";
// one server reads one answer on this page: where the Servers list below keeps a server unshared for a reason the switch
// does not change, a project's server the hub does not host yet among them, its row here sits with the ones kept one per
// session, under that reason and out of the figures (marlin, desk m, Oct 12)
const asListed = (s, rows) => {
  const r = rows.get(s.label ?? s.name);
  return r && !r.shared && MCP_STATE[r.state] && r.state !== "per_session_choice" ? { ...s, class: "per_session", why: r.state, value: r.value } : s;
};
const copiesWord = (n) => `${fmt(n)} ${n === 1 ? "copy" : "copies"}`;
// a row names its server as the Servers list below does: Claude Code's own name for it and where it is declared, never
// the plugin_<plugin>_<server> id the census matches copies by (marlin, desk m, Oct 12)
const memName = (s) => `<div class="sname"><span class="nm">${esc(s.label ?? s.name)}</span>${s.source_file ? fromHtml({ from: fromWord(s), project: projectOf(s) }) : ""}</div>`;
// a server with no copy says why it has no figure: one at a web address has no process here, any other runs none now
const idle = (s) => (s.web ? "At a web address" : `<span class="state">Not running</span>`);
// the sessions holding a server now, its own copy or the hub's, each a door to it on Sessions (spec §8: ownership visible
// per session). A session with no name reads as Sessions titles it, once /api/sessions has answered for it
const HELD_SHOWN = 4;
const heldName = (h) => h.name || ui.snames?.get(h.session_id) || "Unnamed session";
const heldHtml = (s) => {
  const held = s.held_by || [];
  if (!held.length) return "";
  const shown = held.slice(0, HELD_SHOWN).map((h) => `<a class="chip sess" href="sessions.html#${esc(h.session_id)}">${esc(heldName(h))}</a>`);
  const rest = held.length - HELD_SHOWN;
  return `<span class="held">Used now by${shown.join("")}${rest > 0 ? `<span class="more">+${fmt(rest)}</span>` : ""}</span>`;
};
/** Titles for the holding sessions that carry no name, read only when one of them has none. */
async function nameHolders() {
  const held = (ui.mmem?.servers || []).flatMap((s) => s.held_by || []);
  if (!held.some((h) => !h.name)) return;
  const r = await API.get("/api/sessions");
  if (!r.ok) { whyNot("/api/sessions", r); return; }
  ui.snames = new Map((r.body?.sessions || []).map((x) => [x.session_id, x.name || x.title]));
  later();
}
const memRow = (s, what, shared) => `<div class="mrow">${memName(s)}<div class="mwhat">${what}${heldHtml(s)}</div>`
  + `<span class="fig">${s.copies ? copiesWord(s.copies) : idle(s)}</span><span class="fig">${s.copies ? `<b>${esc(mbWord(s.bytes_now))}</b> now` : ""}</span>`
  + `<span class="fig">${shared}</span></div>`;
const whyCell = (s) => {
  const m = MCP_STATE[s.why];
  if (!m) return `<span class="why"></span>`;
  const code = s.value ? `<code>${esc(homeWord(s.value))}</code>` : "";
  return `<span class="why"><span class="w">${esc(m.word)}${code}</span>${m.line ? `<span class="wl">${esc(m.line)}</span>` : ""}</span>`;
};
/** GET /api/mcp/memory: every copy of every declared stdio server on this machine, PSS summed, against one copy each. */
function savingsHtml() {
  const m = ui.mmem;
  if (m === undefined) return `<section class="tally"><div class="card peaks">${READING}</div></section>`;
  if (!m) return `<section class="tally"><p class="pidle"><span class="state limit">Not read</span><span>splice did not measure its MCP servers</span></p></section>`;
  // each row carries its class from the host's own sharing plan, unless the Servers list keeps it unshared (asListed)
  const byName = new Map((ui.mcp ? mcpServerRows(ui.mcp) : []).map((r) => [r.name, r]));
  const all = (m.servers || []).map((s) => asListed(s, byName));
  // every server lands in one list, running or not: the figures count the ones with copies (marlin, desk m, Oct 12)
  const listed = all.filter(shareable), share = listed.filter((s) => s.copies);
  const now = share.reduce((n, s) => n + s.bytes_now, 0), one = share.reduce((n, s) => n + s.bytes_if_shared, 0);
  const copies = share.reduce((n, s) => n + s.copies, 0);
  const tiles = share.length ? [
    statTile(mbWord(now), "In use now", note(copiesWord(copies))),
    // it counts servers, each at one copy once shared: the words say both, never a copy count it did not take
    statTile(mbWord(one), "Shared", note(`${fmt(share.length)} ${share.length === 1 ? "server" : "servers"}, one copy each`)),
    statTile(mbWord(Math.max(0, now - one)), "Sharing saves"),
  ] : [];
  const head = `<div class="thead"><h3>What sharing saves</h3>${tiles.length ? "" : `<span class="zeros">${zeroFig("copies running")}</span>`}</div>`;
  const rows = listed.map((s) => memRow(s, `<span class="why"></span>`, s.copies ? `<b>${esc(mbWord(s.bytes_if_shared))}</b> shared` : ""));
  // the hub keeps these one per session whatever the switch says: each says why, in the words its rows use above
  const kept = all.filter((s) => s.class === "per_session" && s.why !== "per_session_choice");
  // servers no plan decides yet (a project's, a repo's or a plugin's): Claude Code starts them itself, a copy a session
  const own = all.filter((s) => !shareable(s) && s.class !== "per_session");
  const list = (title, xs) => (xs.length ? `<section class="sub"><h3>${title}</h3><div class="mrows">${xs.map((s) => memRow(s, whyCell(s), "")).join("")}</div></section>` : "");
  return `<section class="tally">${head}${tiles.length ? `<div class="figs3 n3">${tiles.join("")}</div>` : ""}`
    + (rows.length ? `<div class="mrows">${rows.join("")}</div>` : "") + `</section>`
    + list("One per session either way", kept) + list("Started by Claude Code", own);
}

const mcpHtml = () => `${shareCard()}${savingsHtml()}<section class="tally">${mcpNow()}</section>${mcpServersHtml()}<section class="sub">${blocks(keysOf("mcp"))}</section>`;

// the three settings that cap splice itself, in the order they bite: how many run, how many wait, how big one may be
const LIMITS = ["maxInflight", "maxQueued", "maxRequestBytes"];
const busyHtml = () => {
  const limits = LIMITS.filter((key) => keysOf("busy").includes(key));
  // what splice does when a provider pushes back sits under that provider's figures; its own caps stand together
  return peaksHtml()
    + `<section class="tally">${headed("Providers", figsHtml([{ why: "overloaded", what: "Overloaded today" }, { why: "queued", what: "Waited in line" }, { why: "cutoff", what: "Cut off today" }]))}`
    + blocks(keysOf("busy", LIMITS)) + `</section>`
    + (limits.length ? `<section class="sub"><h3>splice's limits</h3>${blocks(limits)}</section>` : "");
};

// ---------- Version ----------
// What this daemon runs, where the newest is, and the way back, from GET /api/upgrade. The route never fetches, so the
// newest is a version only when a check has succeeded here; otherwise it is the releases page, one tap away. Go back is
// POST /api/upgrade {rollback}.
//
// ONE CARD PER VERSION THERE IS, AND NO CARD FOR A VERSION THERE IS NOT. Three cards of which two held no number read
// as a broken row (splice-lead, Oct 11): on a daemon that has never checked, Newest is permanently "Not checked" and
// Previous is "None". Running always has a number and takes the row; Previous joins it only when a release is on this
// machine to go back to. What the daemon cannot know is said in the card's own plain line, never in its sentence,
// which names fetching, verifying and polls ("no upgrade check has succeeded on this daemon; splice learns the latest
// version by fetching and verifying a release, which no poll may do").
const RELEASES = "https://github.com/torad-labs/splice/releases";
// ONE ROUTE, TWO DIRECTIONS: POST /api/upgrade takes him to a release or back to one, and the run that follows is the
// same run. Only the words differ, so the header says which way it went rather than "Going back to" over an upgrade.
const RUN_WORD = {
  up: { running: "Upgrading to", succeeded: "Now on", failed: "Not on", lost: "Not on" },
  back: { running: "Going back to", succeeded: "Back on", failed: "Not back on", lost: "Not back on" },
};
const vcard = (label, body, note = "") => kcard(`<div class="lbl">${esc(label)}</div><div class="ctl">${body}${note ? `<p class="vwhy">${esc(note)}</p>` : ""}</div>`, " vcard");
const vnum = (s) => `<b class="v">${esc(s)}</b>`;
function versionHtml() {
  const v = ui.ver;
  if (v === undefined) return `<span class="state">Reading</span>`;
  if (!v) return `<span class="state">Not read</span>`; // the word the Version job's own rail line uses; why is warned where it was read (fin)
  const back = v.rollback_target, run = ui.run, going = run && run.state === "running";
  // "Not checked" sits where "Checked Oct 12, 4:10 AM" would, in the same small line, so the version stays the largest
  // thing on the card (Marlin with hitstop, Oct 12)
  const checked = `<p class="kel">${v.checked_at_epoch_millis ? `Checked ${esc(atWord(v.checked_at_epoch_millis))}` : "Not checked"}</p>`;
  const releases = `<a class="act quiet" href="${RELEASES}" target="_blank" rel="noopener">${SV.open}Releases</a>`;
  // A STATE AND AN ACT, NOT A SENTENCE (fin, Marlin approved, Oct 12). What the check gave him is a state on this card:
  // "Not read" when a tap answered nothing, "Newest" when he is already on the newest. A newer version is a version
  // there is, so it takes a card of its own below instead of standing as a second bare number under "Running"; when
  // the check happened, or that it never has, is an element.
  const news = v.latest && v.latest !== v.installed;
  const newest = news ? ""
    : v.latest ? `<span class="state">Newest</span>`
    : v.checked_at_epoch_millis ? `<span class="state limit">Not read</span>` : "";
  // ONLY HIS TAP READS THE NETWORK (Marlin's privacy line, Oct 12): nothing here polls, and the page never checks when
  // it opens. GET /api/upgrade answers the snapshot a past tap left, and POST /api/upgrade/check is the only fetch.
  const act = ui.armed === "restart" ? "" : `<button class="act quiet" data-act="check"${ui.checking || going ? " disabled" : ""}>${ui.checking ? ICON.wait : ""}Check</button>`
    + (ui.checkSaid ? `<span class="said limit">${esc(ui.checkSaid)}</span>` : "");
  const cards = [vcard("Running", `${vnum(v.installed)}${newest}${act}${restartAct(going)}${checked}`)];
  // UPGRADE IS THE SAME ARMING AS GO BACK, on the card that holds the version it would take him to, because it is the
  // same decision in the other direction. The releases page is the door for reading the notes before he arms; while
  // he is armed the card holds the question and nothing else, so the yes and the no are the only things to tap.
  if (news) {
    cards.push(vcard("Newest", `${vnum(v.latest)}${ui.armed === "upgrade"
      ? `<button class="act primary" data-act="upgrade">Upgrade to ${esc(v.latest)}</button><button class="act quiet" data-act="disarm">Stay on ${esc(v.installed)}</button>`
      : `<button class="act quiet" data-act="arm" data-id="upgrade"${going ? " disabled" : ""}>Upgrade to ${esc(v.latest)}</button>${releases}`}`));
  }
  // Previous is a card only when there is a release to go back to, or when the read that would have found one failed:
  // "None" under a name is not news, and three cards of which two held no number read as a broken row.
  if (back) {
    cards.push(vcard("Previous", `${vnum(back)}${ui.armed === "rollback"
      ? `<button class="act primary" data-act="rollback">Go back to ${esc(back)}</button><button class="act quiet" data-act="disarm">Stay on ${esc(v.installed)}</button>`
      : `<button class="act quiet" data-act="arm" data-id="rollback"${going ? " disabled" : ""}>Go back to ${esc(back)}</button>`}`));
    // no line under it: "Previous" and "Go back to 0.3.2" already say what the card is (Marlin, Oct 12)
  } else if (v.rollback_unavailable_reason) {
    cards.push(vcard("Previous", `<span class="state">Not read</span>`, v.rollback_unavailable_reason));
  }
  return cardRow(cards, " vcards") + runHtml(run, back);
}
// A RUN IS ITS STEPS, AND THE LOG IS A DOOR ONLY WHEN IT FAILS (Marlin, Oct 12). The steps are read off the lines the
// run prints (UpgradeCommand, RestartCommand, DaemonStop, DaemonLaunch): a step is done once a later one has begun.
// The turns a run waits for before it activates are not this step, which is the wait after splice is told to stop.
const RUN_STEPS = ["Checked", "Stopping", "Waiting for running turns", "Starting"];
function runStep(output) {
  let at = 0;
  for (const l of output) {
    if (/restarted, serving/.test(l)) return RUN_STEPS.length;
    if (/stopping daemon/.test(l)) at = Math.max(at, 1);
    else if (at >= 1 && /waiting up to|in flight|compaction/.test(l)) at = Math.max(at, 2);
    else if (/starting the daemon/.test(l)) at = Math.max(at, 3);
  }
  return at;
}
function runHtml(run, back) {
  if (!run) return "";
  const words = RUN_WORD[run.way === "up" ? "up" : "back"];
  const failed = run.state === "failed" || run.state === "lost";
  const at = run.state === "succeeded" ? RUN_STEPS.length : runStep(run.output || []);
  const lamp = (i) => (i < at ? `<span class="lamp done" aria-hidden="true"><i></i></span>`
    : i > at ? `<span class="lamp idle" aria-hidden="true"><i></i></span>`
    : failed ? `<span class="lamp fail" aria-hidden="true">${ICON.close}</span>` : `<span class="lamp" aria-hidden="true">${ICON.wait}</span>`);
  const steps = `<ol class="steps">${RUN_STEPS.map((w, i) => `<li class="step${i > at ? " pending" : ""}">${lamp(i)}<span class="what">${esc(w)}</span></li>`).join("")}</ol>`;
  const door = failed && run.output?.length
    ? `<button class="logbtn" data-act="runlog" aria-expanded="${!!ui.runLog}">${ICON.caret}Full log</button>${ui.runLog ? `<ol class="rlog">${run.output.map((l) => `<li>${esc(l)}</li>`).join("")}</ol>` : ""}`
    : "";
  return `<section class="run"><header><h3>${words[run.state] ?? words.running} ${esc(run.to ?? back ?? "")}</h3></header>${steps}${door}</section>`;
}
/** Restart splice, beside the version it is running. With nothing in flight it is one press: splice is built to come
 *  back from a restart. With requests in flight it arms the way Remove does on Models (Marlin, Oct 12): the first press
 *  names the agents mid-request, each a chip with the Working lamp that opens its session, says how long the drain lets
 *  them finish before it cuts them (the daemon's own cap, GET /api/daemon/restart's drain_ms, never a number written
 *  here), and the second press restarts. fin's words, Marlin approved; Check steps aside while it is armed. What a restart waits for is said on the button, because a daemon that waits on a compaction looks
 *  the same as one that hung. */
function restartAct(going) {
  if (ui.armed === "restart" && !ui.restarting) {
    const then = ui.drainMs ? `<span class="said then">· ${KNOBS.dur(ui.drainMs)} to finish, then cut</span>` : "";
    return armedAsk("Mid-request", ui.cut || [], '<button class="act danger" data-act="restart-now">Restart splice</button>',
      '<button class="act quiet" data-act="disarm">Keep running</button>', then);
  }
  const wait = ui.restartWait;
  const word = !ui.restarting ? "Restart splice"
    : wait ? `Waiting for ${fmt(wait)} ${wait === 1 ? "compaction" : "compactions"}`
    : "Restarting";
  return `<button class="act quiet" data-act="restart"${ui.restarting || ui.restartArming || going ? " disabled" : ""}>${ui.restarting ? ICON.wait : ""}${word}</button>`
    + (ui.restartSaid ? `<span class="said limit">${esc(ui.restartSaid)}</span>` : "");
}
/** His tap, and the only thing on this page that reaches the network: POST /api/upgrade/check reads once and answers
 *  the snapshot, which the card then reads as its own state. A refusal is said beside the act in the console's words,
 *  never the daemon's sentence: already running, checked a moment ago, or not read. */
async function check() {
  if (ui.checking) return;
  ui.checking = true; ui.checkSaid = null; render();
  const r = await API.post("/api/upgrade/check", {});
  ui.checking = false;
  if (r.ok && r.body) {
    const s = r.body;
    ui.ver = { ...ui.ver, latest: s.latest ?? null, checked_at_epoch_millis: s.read_at_epoch_millis ?? ui.ver?.checked_at_epoch_millis ?? null };
    // a read that answered nothing is "Not read" on the card itself, so nothing is said twice
    if (s.state === "not_read" && s.reason) console.warn(`upgrade check: ${s.reason}`);
  } else {
    ui.checkSaid = r.status === 409 ? "Already checking" : r.status === 429 ? "Checked a moment ago" : "Not read";
    if (r.body?.error) console.warn(`upgrade check: ${r.body.error}`);
  }
  render();
}
/** A run already going when the page opened, read off the args splice started it with (`upgrade --rollback`, or
 *  `upgrade --to 0.4.1`): the way it went and the release it names, so a run started on another screen reads the
 *  same here as the one he started. Before Upgrade existed every such run was a rollback, and this read it as one. */
function runOf(run) {
  const args = run.args || [], back = args.includes("--rollback");
  const at = args.indexOf("--to");
  return { way: back ? "back" : "up", state: run.state, to: at >= 0 ? args[at + 1] : back ? ui.ver?.rollback_target : ui.ver?.latest, output: run.output || [] };
}
/** The one run, in whichever direction he asked for: [body] is what POST /api/upgrade is given, [way] the words the
 *  header reads it back in. A refusal is the run's first line, because the run section is where he is looking. */
async function startRun(way, to, body) {
  const res = await API.post("/api/upgrade", body);
  ui.run = res.ok ? { way, state: "running", to, output: [] } : { way, state: "failed", to, output: [res.body?.error || `splice answered ${res.status}`] };
  render();
  if (res.ok) pollRun(to, way);
}
const goBack = () => startRun("back", ui.ver?.rollback_target, { rollback: true });
const upgrade = () => startRun("up", ui.ver?.latest, { to: ui.ver?.latest });
/** Restart splice. The 202 says the phase it is entering, never one it has reached: draining, or waiting on the
 *  compactions it names. Then the daemon goes, and GET /api/daemon/restart answers again once its successor is up,
 *  which is the console's one signal that it is back. A refusal is said beside the act in the console's words. */
/** The first press: which sessions have a request in flight right now, read off each command's live turns the way
 *  Sessions reads them. None, and it restarts at once; some, and it arms with their names. */
async function pressRestart() {
  if (ui.restarting || ui.restartArming) return;
  ui.restartArming = true; render();
  const [cut, phase] = await Promise.all([midRequest(ui.heads || []), API.get("/api/daemon/restart")]);
  ui.restartArming = false;
  ui.drainMs = phase.body?.drain_ms ?? null;
  if (!cut.length) { restartSplice(); return; }
  ui.cut = cut;
  ui.armed = "restart";
  render();
}
async function restartSplice() {
  if (ui.restarting) return;
  ui.restarting = true; ui.restartSaid = null; ui.restartWait = 0; render();
  const r = await API.post("/api/daemon/restart", {});
  if (!r.ok) {
    ui.restarting = false;
    // 503 is the daemon saying nothing would bring it back, unwired or unsupervised: both mean the same to him here
    ui.restartSaid = r.status === 409 ? "Already restarting" : r.status === 503 ? "Nothing would bring it back" : "Not restarted";
    if (r.body?.error) console.warn(`restart: ${r.body.error}`);
    render();
    return;
  }
  ui.restartWait = r.body?.compactions ?? 0;
  render();
  pollRestart(0);
}
/** Until the daemon answers again, or long enough that saying nothing would be the lie. Every call while it is down
 *  fails, which is the wait, not an error. */
const RESTART_TRIES = 60;
function pollRestart(tries) {
  setTimeout(async () => {
    const r = await API.get("/api/daemon/restart");
    if (r.ok && r.body?.status === "waiting") { ui.restartWait = r.body.compactions ?? 0; render(); }
    if (r.ok && r.body?.status === "idle") { ui.restarting = false; ui.restartWait = 0; render(); refresh(); return; }
    if (tries >= RESTART_TRIES) { ui.restarting = false; ui.restartWait = 0; ui.restartSaid = "Not back yet"; render(); return; }
    pollRestart(tries + 1);
  }, 2000);
}
function pollRun(to, way) {
  setTimeout(async () => {
    const r = await API.get("/api/upgrade/run"), run = r.body?.run;
    if (run) ui.run = { way, state: run.state, to, output: run.output || [] };
    if (!run || run.state === "running") { render(); pollRun(to, way); return; }
    ui.ver = (await API.get("/api/upgrade")).body ?? ui.ver;
    render();
  }, 2000);
}

// ---------- Reasoning: the fold pair as one sentence ----------
// "When thinking stops short, continue [models] up to [3 times]" is ONE row, because the two knobs are one decision:
// the models splice asks to keep thinking, and how many times it asks (fin's build list, Marlin approved, Oct 12).
// An element would be how many answers were continued today, and nothing on the wire counts folds, so there is none.
const FOLD = ["foldReasoningModels", "foldMaxContinue"];
function foldCard() {
  const models = KNOB.foldReasoningModels, times = KNOB.foldMaxContinue;
  if (FOLD.some((key) => needsRestart(key) || !shownKnob(key)) || !keysOf("reasoning").includes(models.key)) return "";
  const lit2 = lit([knobHay(models), knobHay(times)].join(" "));
  return kcard(`<div class="lbl${lit2}" data-row="${models.key}">${hl(models.label)}</div>`
    + `<div class="ctl col"><div class="line">${ctlHtml(models, val(models.key))}${saidHtml(models.key)}</div>`
    + `<div class="line"><span class="upto">${hl(times.label)}</span>${ctlHtml(times, val(times.key))}${saidHtml(times.key)}</div></div>`);
}

// ---------- Projects ----------
// WHAT A PROJECT IS, AND NOTHING ELSE: one row, the folders under which each child folder is a project of its own.
// With none saved, a project is the git repo, which is the rule in force and what the rail says. The row is drawn
// here rather than by the kit because a list of paths is not one of its controls: each path carries the way to drop
// it, the projects it makes, and whether it is still on disk. The value is the kit's (PATCH /api/config
// projectsFolders, colon-separated), so a change reads "Applied" in the same place every other setting does.
const FOLDER_SEP = ":";
const foldersNow = () => String(val("projectsFolders") || "").split(FOLDER_SEP).map((s) => s.trim()).filter(Boolean);
/** What the daemon says about each saved folder: whether it is on disk, and the projects it makes. */
const folderFacts = () => {
  const rows = ui.projects?.folders || [];
  const by = Object.fromEntries(rows.map((f) => [f.path, f]));
  return foldersNow().map((path) => ({
    path,
    found: by[path] ? by[path].found !== false : null, // null while /api/projects has not answered
    makes: (ui.projects?.projects || []).filter((p) => p.folder === path).map((p) => p.name || p.label).filter(Boolean).sort(),
  }));
};
function pathRow(f) {
  const drop = `<button class="icon" data-act="dropfolder" data-path="${esc(f.path)}" aria-label="Remove ${esc(f.path)}">${ICON.close}</button>`;
  // a folder that has vanished still holds its place: splice never stops over it, and its sessions fall back to repos
  const gone = f.found === false ? `<span class="state limit">Not found</span>` : "";
  const makes = f.makes.length ? `<div class="pmakes">${f.makes.map((n) => `<span class="mchip quiet">${hl(n)}</span>`).join("")}</div>` : "";
  return `<div class="prow"><span class="ppath"><code>${hl(f.path)}</code>${drop}</span>${gone}</div>${makes}`;
}
function projectsHtml() {
  const k = KNOB.projectsFolders, folders = folderFacts();
  // WHAT HE SET IS NEVER QUIETER THAN WHAT IT MAKES (Marlin, Oct 12, from hitstop's mock eb1c855): the label, the path
  // and the field it is typed in stand at the project chips' size, and these acts take the medium button, not the
  // compact one, so Add is as tall as a chip. The pform class is what carries all of it.
  const adding = ui.addPath !== null
    ? `<span class="custom"><input class="field wide" data-pfield value="${esc(ui.addPath)}" placeholder="/home/you/code" aria-label="Projects folder">`
      + `<button class="act primary" data-act="savefolder">Save</button>`
      + `<button class="act quiet" data-act="cancelfolder">Cancel</button></span>`
      + (ui.bad.projectsFolders ? `<span class="said limit">${esc(ui.bad.projectsFolders)}</span>` : "")
    : `<button class="act quiet" data-act="addfolder">${SV.plus}Add</button>${saidHtml("projectsFolders")}`;
  const body = `<div class="plist">${folders.map(pathRow).join("")}<div class="pacts">${adding}</div></div>`;
  return `<section class="sub">${cardRow([kcard(`<div class="lbl" data-row="${k.key}">${hl(k.label)}</div><div class="ctl col">${body}</div>`, " pform")])}</section>`;
}

/** The saved folders with [typed] added, trimmed, with no empty and no repeat. */
const withPath = (typed) => [...new Set([...foldersNow(), String(typed).trim()])].filter(Boolean);
/** Save the list and say what happened where every other setting says it. A path the daemon will not take is refused
 *  in the console's own words, never the daemon's: it answers one message for the whole key, and he typed one path. */
async function saveFolders(paths) {
  const was = ui.addPath;
  const r = await KNOBS.patch("projectsFolders", paths.join(FOLDER_SEP));
  if (r.ok) { ui.addPath = null; delete ui.bad.projectsFolders; render(); await refreshProjects(); return; }
  ui.addPath = was;
  ui.bad.projectsFolders = was === null ? "Not saved" : "Not found";
  render();
}
/** The projects the folders make, re-read after a change so the chips under each path are the daemon's answer. */
async function refreshProjects() {
  const r = await API.get("/api/projects");
  ui.projects = r.ok ? r.body : null;
  render();
}

const PANES = {
  busy: busyHtml,
  silent: silentHtml,
  reasoning: () => `<section class="sub">${blocks(keysOf("reasoning", FOLD), [foldCard()])}</section>`,
  plan: planHtml,
  mcp: mcpHtml,
  projects: projectsHtml,
  data: dataHtml,
  version: versionHtml,
};

// ---------- the rail ----------
function summary(id) {
  if (id === "silent" && weekReading("restarted", "gaveup", "waited")) return ["Reading"];
  if (id === "busy" && weekReading("overloaded", "queued")) return ["Reading"];
  if (id === "silent") {
    const early = ["restarted", "gaveup"].map(weekTotal), waited = weekTotal("waited");
    return early.includes(null) || waited === null ? [`Asks after ${word(KNOB.firstByteTimeoutMs, val("firstByteTimeoutMs"))}`]
      : [`${fmt(early[0] + early[1])} ended early this week`, `${fmt(waited)} waited out`];
  }
  if (id === "version") return [ui.ver === undefined ? "Reading" : ui.ver ? ui.ver.installed : "Not read"];
  if (id === "reasoning") return [KNOB.showReasoning && !needsRestart("showReasoning") ? `Shows it ${word(KNOB.showReasoning, val("showReasoning")).toLowerCase()}` : `Progress line ${val("progressLine") ? "on" : "off"}`];
  if (id === "plan") return [`Warns at ${word(KNOB.usageWarnPct, val("usageWarnPct"))}`];
  if (id === "mcp") return [`${word(KNOB.mcpMaxServers, val("mcpMaxServers"))} at most`];
  // with nothing saved the rail says the rule in force, which is the one a first-time user is already under
  if (id === "projects") { const f = foldersNow(); return f.length ? f : ["One per repo"]; }
  if (id === "busy" && weekTotal("overloaded") !== null && weekTotal("queued") !== null) return [`${fmt(weekTotal("overloaded"))} overloaded today`, `${fmt(weekTotal("queued"))} waited in line`];
  if (id === "busy") return [`${word(KNOB.maxInflight, val("maxInflight"))} at once`, `${word(KNOB.maxQueued, val("maxQueued"))} waiting`];
  if (DATA_ROWS.some(stillReading)) return ["Reading"];
  const h = DATA_ROWS.filter((r) => !(r.noRoute || r.own)).map(heldOf), tag = DATA_ROWS.some((r) => r.noRoute || r.own) ? "counted" : "kept"; // a total that leaves a store out says counted
  return h.some((x) => x === null) ? ["Not read"] : [`${mbWord(h.reduce((s, x) => s + x.bytes, 0))} ${tag}`];
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
  // Your data's own first line is the same figure the rail card carries, so the pane header does not say it twice
  const x = TOPIC[ui.open], s = x.id === "data" ? [] : summary(x.id);
  const list = shown.length ? `<div class="list rail">${shown.map(cardHtml).join("")}</div>`
    : `<div class="list rail empty"><div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clear">Clear</button></div></div>`;
  const pane = shown.length ? `<div class="pane"><header><span class="lamp idle sock" aria-hidden="true">${SV[x.id]}</span><div class="who"><div class="top"><span class="name">${esc(x.name)}</span></div>`
    + `<div class="meta">${s.map((w) => `<span>${esc(w)}</span>`).join("")}</div></div>`
    + `<div class="acts"><button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button></div></header><div class="body kn">${PANES[x.id]()}${advancedHtml(x.id)}</div></div>` : "";
  const was = root.querySelector(".pane .body")?.scrollTop ?? 0;
  // a setting's typed field keeps its caret through a redraw (knobs.js keeps the text)
  const f = document.activeElement, fid = f?.id?.startsWith("kf-") ? f.id : null, pos = f?.selectionStart;
  root.innerHTML = `<div class="jobs${ui.shown ? " open" : ""}">${list}${pane}</div>`;
  if (was) { const b = root.querySelector(".pane .body"); if (b) b.scrollTop = was; }
  if (fid) { const g = document.getElementById(fid); if (g) { g.focus(); if (pos != null) g.setSelectionRange(pos, pos); } }
  KNOBS.focusCustom(root);
  root.querySelector(".pane .menu")?.scrollIntoView({ block: "nearest" });
  moreBelow();
}

// An open card taller than its pane says so on its lower edge: "more" is on the pane while content waits below the fold,
// and goes when the scroll reaches the end. Every card gets it, none draws it unless it overflows.
function moreBelow() {
  const body = root.querySelector(".pane .body");
  if (body) body.parentElement.classList.toggle("more", body.scrollHeight - body.scrollTop - body.clientHeight > 6);
}
document.addEventListener("scroll", (e) => { if (e.target.classList?.contains("body")) moreBelow(); }, true);
window.addEventListener("resize", moreBelow);

// A shorter history window that would delete turns waits for the yes; the daemon says what the cut would take.
ks.onAsk = (id, days) => {
  ui.ask = { v: days, h: null };
  API.get(`/api/history?days=${days ?? "forever"}`).then(async (r) => {
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
/** One command's prompt saving, on or off: the daemon takes it on that command's next request, and the row says so. */
async function setCapture(head, on) {
  const id = `capture@${head}`;
  delete ui.bad[id];
  const res = await API.put(`/api/heads/${encodeURIComponent(head)}/capture`, { enabled: on });
  if (!res.ok) { ui.bad[id] = true; ui.said[id] = "Not saved"; render(); return; }
  ui.capture[head] = on;
  ui.said[id] = res.body?.restart_required ? "Saves after splice restarts" : "Applied";
  render();
}
async function remove(id) {
  const key = id.slice(4), bad = () => { ui.bad[id] = "Not deleted"; };
  const store = DATA_ROWS.find((r) => r.id === key)?.store;
  const paths = key === "trace" ? ui.heads.map((h) => `/api/heads/${encodeURIComponent(h)}/trace/kept`) : [store ? `/api/kept/stores/${store}` : EP[key]];
  const answers = await Promise.all(paths.map((p) => API.del(p)));
  if (answers.some((a) => !a.ok)) bad(); else delete ui.bad[id];
  await refresh();
}
async function refresh() {
  const err = await KNOBS.load();
  if (err) { ui.err = err; render(); return; }
  ui.err = null;
  await readBase();
  render(); // the cards and every setting draw now; what the daemon keeps and counts fills in as each read answers
  readKept();
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
  // leaving a job drops what was said on it: a refusal he has walked away from is stale by the time he comes back
  if (d.open && !d.act) { ui.open = d.open; ui.shown = true; ui.armed = null; ui.checkSaid = null; ui.restartSaid = null; ks.custom = null; render(); return; }
  switch (d.act) {
    case "theme": toggleTheme(); return;
    case "close": ui.shown = false; break;
    case "clear": ui.q = ""; document.getElementById("q").value = ""; break;
    case "again": refresh(); return;
    case "capture": setCapture(d.head, d.v === "true"); return;
    case "saving": ui.saving = ui.saving === "trace" ? null : "trace"; break;
    case "arm": ui.armed = d.id; delete ui.bad[d.id]; break;
    case "disarm": ui.armed = null; break;
    case "rollback": ui.armed = null; goBack(); return;
    case "upgrade": ui.armed = null; upgrade(); return;
    case "check": check(); return;
    case "restart": pressRestart(); return;
    case "restart-now": ui.armed = null; restartSplice(); return;
    case "mtest": testServer(d.name); return;
    case "mexclude": setExcluded(d.name, d.v === "true"); return;
    case "mshare": setShare(d.v === "true"); return;
    case "adv": ui.adv[d.id] = !ui.adv[d.id]; break;
    case "runlog": ui.runLog = !ui.runLog; render(); return;
    case "delete": ui.armed = null; render(); remove(d.id); return;
    case "shorten": shorten(); return;
    case "unask": ui.ask = null; break;
    case "addfolder": ui.addPath = ""; delete ui.bad.projectsFolders; break;
    case "cancelfolder": ui.addPath = null; delete ui.bad.projectsFolders; break;
    case "savefolder": saveFolders(withPath(root.querySelector("[data-pfield]")?.value ?? "")); return;
    case "dropfolder": saveFolders(foldersNow().filter((p) => p !== d.path)); return;
    default: return;
  }
  render();
});
if (TOPIC[location.hash.slice(1)]) { ui.open = location.hash.slice(1); ui.shown = true; }
// before the first read answers, the rail draws its cards: their names and marks are this page's own, and each card's
// line is a striped bar until its figure lands (Marcos, Oct 12: a blank page under its title read as broken)
const SKETCH_LINES = [7, 10, 9, 6.5, 9, 6, 8];
const ghostHtml = () => `<div class="jobs"><div class="list rail" aria-busy="true" aria-label="Reading settings">${TOPICS.map((x, i) => `<article class="card job ghosted" aria-hidden="true">`
  + `<span class="lamp idle sock">${SV[x.id]}</span><div class="top"><span class="jname">${esc(x.name)}</span></div>`
  + `${SKETCH_LINES[i] ? `<div class="meta">${GHOST.bar(SKETCH_LINES[i])}</div>` : ""}</article>`).join("")}</div></div>`;
root.innerHTML = ghostHtml();
refresh();
